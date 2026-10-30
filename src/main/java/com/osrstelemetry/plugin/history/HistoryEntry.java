package com.osrstelemetry.plugin.history;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * A lightweight, list-row-sized summary of one finalized session --
 * everything History's recent-activity list needs to render a row
 * WITHOUT holding this session's full aggregates/loadout data in
 * memory for every entry in the index. Built once per session, the
 * first time {@link HistoryIndex} discovers its file -- a finalized
 * session file is immutable, so this summary never needs to be
 * recomputed for a sessionId already indexed.
 *
 * Deliberately excludes the full xpGainedBySkill map, the full
 * lootDrops list, and starting/ending loadout entirely -- all of that
 * is read lazily, only when a specific entry is opened (see
 * HistoryCoordinator.loadDetail(), which re-reads the full Session
 * record via SessionPersistence.loadFinalized()), per spec Part R.
 * The few derived numbers here (totalXpGained, lootDropCount) are
 * cheap summaries computed once at discovery time, not a second
 * source of truth -- the detail view always renders from the
 * session's own already-persisted aggregates directly, never from
 * these fields (spec Part D/G/H: never re-derived, never mixed with
 * another source).
 */
@Getter
public final class HistoryEntry
{
	private final String sessionId;
	private final ActivityIdentity activityIdentity;
	private final String startedAt;
	private final String finalizedAt;
	private final long accumulatedActiveDurationMillis;
	private final long totalXpGained;
	private final SessionAggregates.ReliableCount reliableCount;
	private final int lootDropCount;
	private final boolean hasStartingLoadout;
	private final boolean hasEndingLoadout;
	/**
	 * Persisted Slayer assignment INSTANCES in this session (same-name tasks
	 * counted separately, skipped ones included); 0 for non-Slayer sessions
	 * and legacy files without a ledger. Presentation only -- see
	 * {@link #displayTitle}.
	 */
	private final int slayerAssignmentCount;
	/** Structured-activity completions this session; null when the session has no completion metric. */
	private final Integer activityCompletionCount;
	/**
	 * Skill companion sessions (Mining): everything produced (TOTAL), and the
	 * method trained longest (its key, e.g. "amethyst"); 0 / null otherwise.
	 * Cheap summaries for the list row -- the detail view reads the session.
	 */
	private long skillProducedTotal;
	private String skillPrimaryMethod;
	/** Completed cycles (Agility: laps across all courses); 0 for skills without cycles and older entries. */
	private long skillCycleTotal;
	/**
	 * ADDED (pass 28) for {@link HistoryEligibility}: the session's Slayer task
	 * progress (0 when absent, including older files) and every produced
	 * item's quantity (0 when none).
	 */
	private int slayerProgress;
	private long producedQuantity;
	/** ADDED (Phase 6): the skill's own list-row facts (SkillCompanionDefinition#historyRowFacts); null = the generic facts. */
	private List<String> skillRowFacts;

	/** Title of a Slayer session that spanned more than one assignment. */
	public static final String MULTI_TASK_SLAYER_TITLE = "Slayer Session";

	private HistoryEntry(String sessionId, ActivityIdentity activityIdentity, String startedAt, String finalizedAt,
		long accumulatedActiveDurationMillis, long totalXpGained, SessionAggregates.ReliableCount reliableCount,
		int lootDropCount, boolean hasStartingLoadout, boolean hasEndingLoadout, int slayerAssignmentCount,
		Integer activityCompletionCount)
	{
		this.activityCompletionCount = activityCompletionCount;
		this.sessionId = sessionId;
		this.activityIdentity = activityIdentity;
		this.startedAt = startedAt;
		this.finalizedAt = finalizedAt;
		this.accumulatedActiveDurationMillis = accumulatedActiveDurationMillis;
		this.totalXpGained = totalXpGained;
		this.reliableCount = reliableCount;
		this.lootDropCount = lootDropCount;
		this.hasStartingLoadout = hasStartingLoadout;
		this.hasEndingLoadout = hasEndingLoadout;
		this.slayerAssignmentCount = slayerAssignmentCount;
	}

	/**
	 * Builds a summary from a fully-parsed finalized Session. Returns
	 * null (never throws) if the session is missing a field a valid
	 * finalized record must have (sessionId, activityIdentity,
	 * finalizedAt) -- HistoryIndex treats a null result as "skip this
	 * file, log it, move on" (spec Part Y), never a crash.
	 */
	public static HistoryEntry from(Session session)
	{
		if (session == null || session.getSessionId() == null || session.getActivityIdentity() == null
			|| session.getFinalizedAt() == null)
		{
			return null;
		}

		SessionAggregates aggregates = session.getAggregates();
		long totalXp = 0L;
		int lootDropCount = 0;
		SessionAggregates.ReliableCount reliableCount = null;
		if (aggregates != null)
		{
			Map<String, Long> xpBySkill = aggregates.getXpGainedBySkill();
			if (xpBySkill != null)
			{
				for (Long delta : xpBySkill.values())
				{
					if (delta != null)
					{
						totalXp += delta;
					}
				}
			}
			lootDropCount = aggregates.getLootDrops() != null ? aggregates.getLootDrops().size() : 0;
			reliableCount = aggregates.getReliableCount();
		}

		HistoryEntry entry = new HistoryEntry(session.getSessionId(), session.getActivityIdentity(), session.getStartedAt(),
			session.getFinalizedAt(), session.getAccumulatedActiveDurationMillis(), totalXp, reliableCount,
			lootDropCount, session.getStartingLoadout() != null, session.getEndingLoadout() != null,
			slayerAssignmentCount(session),
			session.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
				? (aggregates == null || aggregates.getActivityCompletions() == null ? 0 : aggregates.getActivityCompletions().getSessionCompletions())
				: null);
		if (aggregates != null)
		{
			entry.slayerProgress = aggregates.getSlayerProgressDelta() == null ? 0 : Math.max(0, aggregates.getSlayerProgressDelta());
			entry.producedQuantity = producedTotal(aggregates);
		}
		if (aggregates != null && aggregates.getSkillStats() != null)
		{
			com.osrstelemetry.plugin.skills.SkillCompanionDefinition def =
				com.osrstelemetry.plugin.skills.SkillCompanions.forSkillKey(aggregates.getSkillStats().getSkillKey());
			entry.skillProducedTotal = def != null && def.historyCountsPrimaryOnly() ? primaryTotal(aggregates, def) : producedTotal(aggregates);
			entry.skillPrimaryMethod = primaryMethod(aggregates.getSkillStats());
			entry.skillCycleTotal = cycleTotal(aggregates.getSkillStats());
			if (def != null)
			{
				List<String> facts = def.historyRowFacts(skillTotals(aggregates, session.getAccumulatedActiveDurationMillis(), def));
				entry.skillRowFacts = facts == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(facts));
			}
		}
		return entry;
	}

	/**
	 * ADDED (Phase 6). One finished skill session's semantic totals as generic
	 * maps (produced, banked, consumed, per-method time, occurrences, the
	 * skill's own XP) -- what a definition's History rows / facts read.
	 */
	public static com.osrstelemetry.plugin.skills.SkillCompanionDefinition.SessionTotals skillTotals(SessionAggregates aggregates,
		long activeMillis, com.osrstelemetry.plugin.skills.SkillCompanionDefinition def)
	{
		Map<Integer, Long> produced = new java.util.LinkedHashMap<>();
		Map<Integer, Long> banked = new java.util.LinkedHashMap<>();
		Map<Integer, Long> consumed = new java.util.LinkedHashMap<>();
		Map<String, Long> millis = new java.util.LinkedHashMap<>();
		Map<String, Long> occurrences = new java.util.LinkedHashMap<>();
		long xp = 0L;
		if (aggregates != null)
		{
			addItems(produced, aggregates.getProducedItems());
			SessionAggregates.SkillSessionStats stats = aggregates.getSkillStats();
			if (stats != null)
			{
				addItems(banked, stats.getBanked());
				addItems(consumed, stats.getConsumed());
				if (stats.getMethodMillis() != null)
				{
					millis.putAll(stats.getMethodMillis());
				}
				if (stats.getOccurrences() != null)
				{
					occurrences.putAll(stats.getOccurrences());
				}
			}
			if (aggregates.getXpGainedBySkill() != null && def != null)
			{
				for (Map.Entry<String, Long> e : aggregates.getXpGainedBySkill().entrySet())
				{
					if (e.getKey() != null && e.getValue() != null && def.getSkill().name().equalsIgnoreCase(e.getKey()))
					{
						xp += e.getValue();
					}
				}
			}
		}
		return new com.osrstelemetry.plugin.skills.SkillCompanionDefinition.SessionTotals(produced, banked, consumed, millis, occurrences,
			activeMillis, xp);
	}

	private static void addItems(Map<Integer, Long> into, List<SessionAggregates.ProducedItemAggregate> items)
	{
		if (items == null)
		{
			return;
		}
		for (SessionAggregates.ProducedItemAggregate p : items)
		{
			if (p != null && p.getItemId() != null && p.getQuantity() > 0)
			{
				into.merge(p.getItemId(), p.getQuantity(), Long::sum);
			}
		}
	}

	/** Valid completed cycles over every method (Agility: all laps); 0 when none. */
	static long cycleTotal(SessionAggregates.SkillSessionStats stats)
	{
		long total = 0L;
		if (stats != null && stats.getCycles() != null)
		{
			for (SessionAggregates.CycleAggregate c : stats.getCycles().values())
			{
				if (c != null && c.getCount() > 0)
				{
					total += c.getCount();
				}
			}
		}
		return total;
	}

	/** Only the skill's PRIMARY production (never secondary families); unknown ids never count. */
	static long primaryTotal(SessionAggregates aggregates, com.osrstelemetry.plugin.skills.SkillCompanionDefinition def)
	{
		long total = 0L;
		if (aggregates.getProducedItems() != null)
		{
			for (SessionAggregates.ProducedItemAggregate p : aggregates.getProducedItems())
			{
				com.osrstelemetry.plugin.skills.SkillResource r = p == null ? null : def.resource(p.getItemId());
				if (r != null && r.getRole() == com.osrstelemetry.plugin.skills.SkillResource.Role.PRIMARY && p.getQuantity() > 0)
				{
					total += p.getQuantity();
				}
			}
		}
		return total;
	}

	static long producedTotal(SessionAggregates aggregates)
	{
		long total = 0L;
		if (aggregates.getProducedItems() != null)
		{
			for (SessionAggregates.ProducedItemAggregate p : aggregates.getProducedItems())
			{
				if (p != null && p.getQuantity() > 0)
				{
					total += p.getQuantity();
				}
			}
		}
		return total;
	}

	/** The method trained longest this session (the last one seen when no time was recorded); null when none. */
	public static String primaryMethod(SessionAggregates.SkillSessionStats stats)
	{
		if (stats == null)
		{
			return null;
		}
		String best = null;
		long bestMillis = -1L;
		if (stats.getMethodMillis() != null)
		{
			for (Map.Entry<String, Long> e : stats.getMethodMillis().entrySet())
			{
				if (e.getKey() != null && e.getValue() != null && e.getValue() > bestMillis)
				{
					best = e.getKey();
					bestMillis = e.getValue();
				}
			}
		}
		return best != null ? best : stats.getLastMethod();
	}

	/**
	 * Number of persisted Slayer assignment records, or 0 when the session
	 * is not a Slayer session or has no ledger (never inferred from loot).
	 */
	public static int slayerAssignmentCount(Session session)
	{
		if (session == null || session.getActivityIdentity() == null
			|| session.getActivityIdentity().getActivityType() != ActivityType.SLAYER
			|| session.getAggregates() == null || session.getAggregates().getSlayerAssignments() == null)
		{
			return 0;
		}
		return session.getAggregates().getSlayerAssignments().size();
	}

	/**
	 * History's title for a finalized session. A Slayer session that spanned
	 * several assignments is a "Slayer Session", never just its final task;
	 * everything else (including a single-task Slayer session and every
	 * legacy file) keeps its activity's own display name. Presentation only:
	 * the session's persisted identity and ID never change.
	 */
	public static String displayTitle(ActivityIdentity identity, int slayerAssignmentCount)
	{
		if (slayerAssignmentCount > 1)
		{
			return MULTI_TASK_SLAYER_TITLE;
		}
		return identity == null || identity.getDisplayName() == null ? "Unknown activity" : identity.getDisplayName();
	}

	/** "1 task" / "6 tasks". */
	public static String taskCountText(int count)
	{
		return count + (count == 1 ? " task" : " tasks");
	}
}
