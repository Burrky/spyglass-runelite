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
		}

		if (update.getReliableCountAuthoritativeValue() != null)
		{
			applyReliableCount(aggregates, update);
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
		}
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
