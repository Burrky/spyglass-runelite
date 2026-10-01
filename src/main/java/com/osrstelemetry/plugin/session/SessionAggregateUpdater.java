package com.osrstelemetry.plugin.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure, deterministic
 * application of a MetricUpdate onto a Session's SessionAggregates.
 *
 * Never deduplicates or drops anything by timestamp -- every
 * MetricUpdate handed to apply() is applied in full, unconditionally.
 * The "at most one lifecycle heartbeat per tick" rule lives entirely
 * in SessionSignalBatchResolver; metrics themselves must each still be
 * credited even when several arrive in the same batch.
 */
final class SessionAggregateUpdater
{
	private SessionAggregateUpdater()
	{
	}

	static void apply(SessionAggregates aggregates, MetricUpdate update)
	{
		if (update == null)
		{
			return;
		}

		if (update.getXpSkill() != null)
		{
			long delta = update.getXpDelta() == null ? 0L : update.getXpDelta();
			aggregates.getXpGainedBySkill().merge(update.getXpSkill(), delta, Long::sum);

			// SMART SLAYER: the same XP, once, into the record it belongs to
			// by its own reference time (see xpRecordFor()).
			SessionAggregates.SlayerAssignmentRecord xpRecord = xpRecordFor(aggregates, update.getReferenceTime());
			if (xpRecord != null && xpRecord.getXpGainedBySkill() != null)
			{
				xpRecord.getXpGainedBySkill().merge(update.getXpSkill(), delta, Long::sum);
			}
		}

		if (update.getLootDrops() != null)
		{
			SessionAggregates.LootDropGroup group = new SessionAggregates.LootDropGroup();
			group.setSourceName(update.getLootSourceName());
			group.setObservedAt(update.getLootObservedAt());
			List<SessionAggregates.LootItemAggregate> items = new ArrayList<>();
			for (SessionSignal.LootDrop drop : update.getLootDrops())
			{
				SessionAggregates.LootItemAggregate item = new SessionAggregates.LootItemAggregate();
				item.setItemId(drop.getItemId());
				item.setItemName(drop.getItemName());
				item.setQuantity(drop.getQuantity());
				items.add(item);
			}
			group.setItems(items);
			aggregates.getLootDrops().add(group);

			// SMART SLAYER: the same drop, once, into the current assignment
			// record (if any). Ownership was decided upstream; this only
			// partitions loot the session already owns.
			SessionAggregates.SlayerAssignmentRecord current = currentSlayerAssignment(aggregates);
			if (current != null)
			{
				for (SessionSignal.LootDrop drop : update.getLootDrops())
				{
					mergeItem(current.getItems(), drop.getItemId(), drop.getItemName(), drop.getQuantity());
				}
			}
		}

		if (update.getReliableCountAuthoritativeValue() != null)
		{
			applyReliableCount(aggregates, update);
		}

		if (update.getActivityCompletionKey() != null)
		{
			applyActivityCompletion(aggregates, update);
		}

		if (update.getActivityStatsKey() != null)
		{
			applyActivityStats(aggregates, update);
		}

		if (update.getSlayerBraceletEffect() != null)
		{
			// Same attribution rule as XP (the record current and unfinished at
			// the activation's own time), except that the completing kill's own
			// proc may share the completion instant: the end is inclusive here.
			SessionAggregates.SlayerAssignmentRecord record = xpRecordFor(aggregates, update.getReferenceTime());
			SessionAggregates.SlayerAssignmentRecord latest = currentSlayerAssignment(aggregates);
			if (record == null && latest != null && update.getReferenceTime() != null
				&& latest.getEndReason() == SlayerAssignmentEndReason.COMPLETED
				&& update.getReferenceTime().toString().equals(latest.getCompletedAt()))
			{
				record = latest;
			}
			if (record != null)
			{
				if ("SLAUGHTER".equals(update.getSlayerBraceletEffect()))
				{
					record.setSlaughterProcs((record.getSlaughterProcs() == null ? 0 : record.getSlaughterProcs()) + 1);
				}
				else if ("EXPEDITIOUS".equals(update.getSlayerBraceletEffect()))
				{
					record.setExpeditiousProcs((record.getExpeditiousProcs() == null ? 0 : record.getExpeditiousProcs()) + 1);
				}
			}
		}

		if (update.getSlayerProgressValue() != null)
		{
			// Live evidence: session_state.json's
			// slayerProgressDelta was observed holding an ABSOLUTE
			// remaining count instead of an accumulated total. This value
			// (see MetricUpdate.slayerProgress()/SessionSignal.slayerTaskProgress())
			// is always ONE event's own taskUnitsConsumed, never an absolute
			// remaining/initial value -- so, exactly like the XP accumulation
			// immediately above, it must be ADDED to whatever was already
			// credited this session, never used to overwrite it. Overwriting
			// here was the root cause of the live bug (slayerProgressDelta
			// ending up equal to the raw currentRemaining of the latest
			// event instead of the sum of units actually consumed).
			int alreadyCredited = aggregates.getSlayerProgressDelta() == null ? 0 : aggregates.getSlayerProgressDelta();
			aggregates.setSlayerProgressDelta(alreadyCredited + update.getSlayerProgressValue());

			// SMART SLAYER: the same authoritative units, once, into the
			// current assignment's own completed-unit count.
			SessionAggregates.SlayerAssignmentRecord current = currentSlayerAssignment(aggregates);
			if (current != null && current.getUnitsCompleted() != null)
			{
				current.setUnitsCompleted(current.getUnitsCompleted() + update.getSlayerProgressValue());
			}
		}

		if (update.getSlayerCurrentRemaining() != null)
		{
			// The OPPOSITE rule from slayerProgressDelta immediately
			// above -- this is an ABSOLUTE task-state number (the game's
			// own currentRemaining as of this one event), so it must be
			// OVERWRITTEN with the latest value, never summed/accumulated.
			// An update with no slayerCurrentRemaining (this branch simply
			// not entered) leaves whatever was already recorded untouched
			// -- it is never reset to null just because a later event
			// happened not to carry one.
			aggregates.setLatestSlayerCurrentRemaining(update.getSlayerCurrentRemaining());
			SessionAggregates.SlayerAssignmentRecord current = currentSlayerAssignment(aggregates);
			if (current != null)
			{
				current.setLatestRemaining(update.getSlayerCurrentRemaining());
				// COMPLETED: remaining reached 0, via SLAYER_TASK_COMPLETED's
				// reconciliation metric or a final PROGRESS to 0 (RuneLite can
				// report amount 0 before the task name clears); both carry the
				// event time as reference time. The record stays current
				// (AWAITING_TASK) until a genuinely new assignment is installed.
				if (update.getSlayerCurrentRemaining() == 0 && current.getEndReason() == null)
				{
					current.setEndReason(SlayerAssignmentEndReason.COMPLETED);
					current.setCompletedAt(update.getReferenceTime() == null ? null : update.getReferenceTime().toString());
				}
			}
		}
	}

	/**
	 * Which assignment record an XP metric belongs to, by its own
	 * reference time t (XP window start / event time):
	 * the newest record that had started by t (a bootstrapped record has
	 * no known start and accepts anything up to its own end); then only
	 * while that record was still unfinished at t -- XP from a window that
	 * began at/after completion (travel, teleports) or after a replacement
	 * boundary is never credited to the ended record. t unknown: only the
	 * current, still-unfinished record. Null = credit no record.
	 */
	static SessionAggregates.SlayerAssignmentRecord xpRecordFor(SessionAggregates aggregates, java.time.Instant t)
	{
		List<SessionAggregates.SlayerAssignmentRecord> ledger = aggregates.getSlayerAssignments();
		if (ledger == null || ledger.isEmpty())
		{
			return null;
		}
		if (t == null)
		{
			SessionAggregates.SlayerAssignmentRecord newest = ledger.get(ledger.size() - 1);
			return newest.getEndReason() == null ? newest : null;
		}
		for (int i = ledger.size() - 1; i >= 0; i--)
		{
			SessionAggregates.SlayerAssignmentRecord r = ledger.get(i);
			java.time.Instant start = parse(r.getAssignedAt());
			if (start != null && t.isBefore(start))
			{
				continue;
			}
			java.time.Instant end = r.getEndReason() == SlayerAssignmentEndReason.COMPLETED
				? parse(r.getCompletedAt())
				: parse(r.getEndedAt());
			if (r.getEndReason() == SlayerAssignmentEndReason.COMPLETED && end == null)
			{
				return null;
			}
			return end == null || t.isBefore(end) ? r : null;
		}
		return null;
	}

	private static java.time.Instant parse(String iso)
	{
		return iso == null ? null : java.time.Instant.parse(iso);
	}

	/** The session's current (latest) Slayer assignment instance, or null when it has no ledger. */
	static SessionAggregates.SlayerAssignmentRecord currentSlayerAssignment(SessionAggregates aggregates)
	{
		List<SessionAggregates.SlayerAssignmentRecord> ledger = aggregates.getSlayerAssignments();
		return ledger == null || ledger.isEmpty() ? null : ledger.get(ledger.size() - 1);
	}

	/**
	 * Ledger bootstrap. When `assignment` is a SLAYER identity and the
	 * session has no ledger yet (created/established mid-task, refined into
	 * Slayer, or a pre-ledger session file), creates record #1 seeded with
	 * ALL loot the session already owns, so the ledger partitions the
	 * complete session loot from the start. No-op when a ledger exists
	 * (never a duplicate record) or for a non-Slayer identity.
	 *
	 * `sessionActiveDurationMillis` (the session's accumulated active time)
	 * becomes the record's task-time start. Also upgrades a record written
	 * before XP/time tracking existed: the CURRENT unfinished record starts
	 * tracking from now. Bootstrapped units = every unit this session
	 * already observed (exact: a pre-ledger session never spanned an
	 * assignment change); bootstrapped XP/time start at 0 (conservative --
	 * Spyglass cannot know which earlier session XP was this task's).
	 *
	 * @return true when record #1 was created by this call
	 */
	static boolean ensureSlayerAssignmentLedger(SessionAggregates aggregates, ActivityIdentity assignment, long sessionActiveDurationMillis)
	{
		if (aggregates == null || assignment == null || assignment.getActivityType() != ActivityType.SLAYER)
		{
			return false;
		}
		if (aggregates.getSlayerAssignments() != null && !aggregates.getSlayerAssignments().isEmpty())
		{
			SessionAggregates.SlayerAssignmentRecord current = currentSlayerAssignment(aggregates);
			if (current.getXpGainedBySkill() == null && current.getEndReason() == null)
			{
				current.setXpGainedBySkill(new java.util.LinkedHashMap<>());
				current.setActiveDurationStartMillis(sessionActiveDurationMillis);
			}
			return false;
		}
		SessionAggregates.SlayerAssignmentRecord first = newAssignment(1, assignment);
		first.setBootstrapped(true);
		first.setLatestRemaining(aggregates.getLatestSlayerCurrentRemaining());
		first.setUnitsCompleted(aggregates.getSlayerProgressDelta() == null ? 0 : aggregates.getSlayerProgressDelta());
		first.setActiveDurationStartMillis(sessionActiveDurationMillis);
		if (aggregates.getLootDrops() != null)
		{
			for (SessionAggregates.LootDropGroup group : aggregates.getLootDrops())
			{
				if (group == null || group.getItems() == null)
				{
					continue;
				}
				for (SessionAggregates.LootItemAggregate item : group.getItems())
				{
					if (item != null)
					{
						mergeItem(first.getItems(), item.getItemId(), item.getItemName(), item.getQuantity());
					}
				}
			}
		}
		first.setXpGainedBySkill(new java.util.LinkedHashMap<>());
		if (first.getUnitsCompleted() == 0)
		{
			// Units seeded from an older file were counted before bracelet
			// tracking existed: those records never claim a kill count.
			startBraceletTracking(first);
		}
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(first);
		aggregates.setSlayerAssignments(ledger);
		return true;
	}

	/**
	 * Installs a NEW assignment instance (authoritative ASSIGNED on a
	 * retained Slayer session); the caller bootstraps the ledger first. The
	 * previous record is closed (endedAt; its task time ends at
	 * `sessionActiveDurationMillis`, where the new one's starts), never
	 * merged or cleared; the new one starts with no loot. The previous
	 * record gets REPLACED_EARLY only when it had not already COMPLETED and
	 * its real remaining is known and &gt; 0 -- unknown remaining leaves the
	 * end reason null (never a guessed skip). Its remaining, units and loot
	 * are never altered.
	 */
	static SessionAggregates.SlayerAssignmentRecord beginSlayerAssignment(SessionAggregates aggregates, ActivityIdentity assignment, java.time.Instant observedAt, Integer amount, long sessionActiveDurationMillis)
	{
		List<SessionAggregates.SlayerAssignmentRecord> ledger = aggregates.getSlayerAssignments();
		if (ledger == null)
		{
			ledger = new ArrayList<>();
			aggregates.setSlayerAssignments(ledger);
		}
		int ordinal = 1;
		if (!ledger.isEmpty())
		{
			SessionAggregates.SlayerAssignmentRecord previous = ledger.get(ledger.size() - 1);
			if (previous.getEndedAt() == null)
			{
				previous.setEndedAt(observedAt.toString());
			}
			if (previous.getEndReason() == null && previous.getLatestRemaining() != null && previous.getLatestRemaining() > 0)
			{
				previous.setEndReason(SlayerAssignmentEndReason.REPLACED_EARLY);
			}
			if (previous.getActiveDurationEndMillis() == null)
			{
				previous.setActiveDurationEndMillis(sessionActiveDurationMillis);
			}
			ordinal = previous.getOrdinal() + 1;
		}
		SessionAggregates.SlayerAssignmentRecord next = newAssignment(ordinal, assignment);
		next.setAssignedAt(observedAt.toString());
		next.setInitialAmount(amount);
		next.setLatestRemaining(amount);
		next.setUnitsCompleted(0);
		next.setXpGainedBySkill(new java.util.LinkedHashMap<>());
		next.setActiveDurationStartMillis(sessionActiveDurationMillis);
		startBraceletTracking(next);
		ledger.add(next);
		return next;
	}

	/** Bracelet procs (and therefore observed kills) are counted from this record's start. */
	private static void startBraceletTracking(SessionAggregates.SlayerAssignmentRecord record)
	{
		record.setSlaughterProcs(0);
		record.setExpeditiousProcs(0);
	}

	private static SessionAggregates.SlayerAssignmentRecord newAssignment(int ordinal, ActivityIdentity assignment)
	{
		SessionAggregates.SlayerAssignmentRecord a = new SessionAggregates.SlayerAssignmentRecord();
		a.setOrdinal(ordinal);
		a.setTaskName(assignment.getDisplayName());
		a.setAssignmentKey(assignment.getActivityKey());
		return a;
	}

	/** Same item-identity rule Current Session uses to aggregate loot: item ID when known, else name. */
	private static void mergeItem(List<SessionAggregates.LootItemAggregate> items, Integer itemId, String itemName, long quantity)
	{
		for (SessionAggregates.LootItemAggregate existing : items)
		{
			boolean same = itemId != null
				? itemId.equals(existing.getItemId())
				: existing.getItemId() == null && Objects.equals(itemName, existing.getItemName());
			if (same)
			{
				existing.setQuantity(existing.getQuantity() + quantity);
				return;
			}
		}
		SessionAggregates.LootItemAggregate item = new SessionAggregates.LootItemAggregate();
		item.setItemId(itemId);
		item.setItemName(itemName);
		item.setQuantity(quantity);
		items.add(item);
	}

	/**
	 * Keeps `authoritativeCurrentValue` (the raw account-wide
	 * number a game message reported) strictly separate from
	 * `sessionOccurrences` (how many of those are attributable to THIS
	 * session).
	 *
	 * First qualifying observation for this exact source+kind within
	 * this session (no ReliableCount yet, or the session's activity was
	 * refined/switched to a different source): there is no in-session
	 * baseline to diff against, so the HONEST rule is that the
	 * authoritative signal that just arrived represents the
	 * kill/completion that just happened -- it is counted as exactly 1
	 * session occurrence, regardless of how large the account-wide
	 * absolute value is.
	 *
	 * Subsequent observation for the SAME source+kind: the session
	 * delta is `newAuthoritativeValue - previousAuthoritativeValue`.
	 * If the producer's real observed jump is larger than 1 (e.g. a
	 * missed chat message caused two prior kills to go unobserved),
	 * that real delta is preserved rather than assumed to be 1. A
	 * non-advancing or duplicate observation (delta <= 0) contributes
	 * zero -- it must never subtract from what was already credited.
	 */
	/** One completion; a different activity key (never expected within one session) starts over. */
	private static void applyActivityCompletion(SessionAggregates aggregates, MetricUpdate update)
	{
		SessionAggregates.ActivityCompletionCount count = aggregates.getActivityCompletions();
		if (count == null || !update.getActivityCompletionKey().equals(count.getActivityKey()))
		{
			count = new SessionAggregates.ActivityCompletionCount();
			count.setActivityKey(update.getActivityCompletionKey());
			aggregates.setActivityCompletions(count);
		}
		count.setSessionCompletions(count.getSessionCompletions() + 1);
		if (update.getActivityCompletionAuthoritativeValue() != null)
		{
			count.setLatestAuthoritativeValue(update.getActivityCompletionAuthoritativeValue());
		}
	}

	/**
	 * Currency: only positive gains accrue (a spend never produces an
	 * update, and a non-positive amount is ignored), so the total never goes
	 * backwards. Score: a completion count already scored is a duplicate and
	 * changes nothing; only a TRUSTED score (game observed from its start,
	 * final value known and positive) enters Avg/Best.
	 */
	private static void applyActivityStats(SessionAggregates aggregates, MetricUpdate update)
	{
		SessionAggregates.ActivitySessionStats stats = aggregates.getActivityStats();
		if (stats == null || !update.getActivityStatsKey().equals(stats.getActivityKey()))
		{
			stats = new SessionAggregates.ActivitySessionStats();
			stats.setActivityKey(update.getActivityStatsKey());
			aggregates.setActivityStats(stats);
		}
		if (update.getCurrencyGained() != null && update.getCurrencyGained() > 0)
		{
			stats.setCurrencyGained(stats.getCurrencyGained() + update.getCurrencyGained());
			if (update.getCurrencyKey() != null)
			{
				if (stats.getCurrencyGainedByKey() == null)
				{
					stats.setCurrencyGainedByKey(new java.util.LinkedHashMap<>());
				}
				stats.getCurrencyGainedByKey().merge(update.getCurrencyKey(), update.getCurrencyGained(), Long::sum);
			}
		}
		if (update.getProducedItems() != null)
		{
			applyProducedItems(aggregates, update.getProducedItems());
		}
		if (!update.isScoreUpdate())
		{
			return;
		}
		Integer count = update.getScoreCompletionCount();
		Integer last = stats.getLastScoredCompletionCount();
		if (count != null && last != null && count <= last)
		{
			return;
		}
		if (count != null)
		{
			stats.setLastScoredCompletionCount(count);
		}
		Integer score = update.getScore();
		if (update.isScoreTrusted() && score != null && score > 0)
		{
			stats.setScoredCompletions(stats.getScoredCompletions() + 1);
			stats.setScoreTotal(stats.getScoreTotal() + score);
			stats.setBestScore(stats.getBestScore() == null ? score : Math.max(stats.getBestScore(), score));
		}
	}

	/** Cumulative per item (id when known, else name); non-positive quantities ignored. Never subtracts. */
	private static void applyProducedItems(SessionAggregates aggregates, List<SessionSignal.LootDrop> items)
	{
		for (SessionSignal.LootDrop item : items)
		{
			if (item == null || item.getQuantity() <= 0)
			{
				continue;
			}
			if (aggregates.getProducedItems() == null)
			{
				aggregates.setProducedItems(new ArrayList<>());
			}
			SessionAggregates.ProducedItemAggregate existing = null;
			for (SessionAggregates.ProducedItemAggregate p : aggregates.getProducedItems())
			{
				if (item.getItemId() != null ? item.getItemId().equals(p.getItemId())
					: p.getItemId() == null && Objects.equals(item.getItemName(), p.getItemName()))
				{
					existing = p;
					break;
				}
			}
			if (existing == null)
			{
				existing = new SessionAggregates.ProducedItemAggregate();
				existing.setItemId(item.getItemId());
				existing.setItemName(item.getItemName());
				aggregates.getProducedItems().add(existing);
			}
			if (existing.getItemName() == null && item.getItemName() != null)
			{
				existing.setItemName(item.getItemName());
			}
			existing.setQuantity(existing.getQuantity() + item.getQuantity());
		}
	}

	private static void applyReliableCount(SessionAggregates aggregates, MetricUpdate update)
	{
		SessionAggregates.ReliableCount existing = aggregates.getReliableCount();
		int authoritativeValue = update.getReliableCountAuthoritativeValue();

		boolean isNewSource = existing == null
			|| existing.getKind() != update.getReliableCountKind()
			|| !Objects.equals(existing.getSourceKey(), update.getReliableCountSourceKey());

		if (isNewSource)
		{
			SessionAggregates.ReliableCount fresh = new SessionAggregates.ReliableCount();
			fresh.setKind(update.getReliableCountKind());
			fresh.setSourceKey(update.getReliableCountSourceKey());
			fresh.setAuthoritativeCurrentValue(authoritativeValue);
			fresh.setSessionOccurrences(1);
			aggregates.setReliableCount(fresh);
			return;
		}

		int previousValue = existing.getAuthoritativeCurrentValue() == null ? authoritativeValue : existing.getAuthoritativeCurrentValue();
		int delta = authoritativeValue - previousValue;
		if (delta > 0)
		{
			existing.setSessionOccurrences(existing.getSessionOccurrences() + delta);
		}
		existing.setAuthoritativeCurrentValue(authoritativeValue);
	}
}
