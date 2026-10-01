package com.osrstelemetry.plugin.history;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
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

		return new HistoryEntry(session.getSessionId(), session.getActivityIdentity(), session.getStartedAt(),
			session.getFinalizedAt(), session.getAccumulatedActiveDurationMillis(), totalXp, reliableCount,
			lootDropCount, session.getStartingLoadout() != null, session.getEndingLoadout() != null,
			slayerAssignmentCount(session),
			session.getActivityIdentity().getActivityType() == ActivityType.STRUCTURED_ACTIVITY
				? (aggregates == null || aggregates.getActivityCompletions() == null ? 0 : aggregates.getActivityCompletions().getSessionCompletions())
				: null);
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
