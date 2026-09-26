package com.osrstelemetry.plugin.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Typed containers for future aggregate metrics. Still NOT wired to
 * any live scheduler or RuneLite events -- SessionAggregateUpdater
 * (pure, unit-tested) is what knows how to fold a MetricUpdate into
 * these fields, but nothing calls it from a real event yet.
 *
 * CORRECTION: `reliableCount` was previously a bare `Integer`,
 * which silently conflated two DIFFERENT numbers -- (A) the
 * authoritative account-wide value a game message reports (e.g. "Your
 * Zulrah kill count is: 812") and (B) how many of those kills/
 * completions actually happened DURING this session. Displaying (A)
 * labeled as "Kills" for a session would be wrong the moment a player
 * had killed a boss they already had hundreds of prior kills on.
 * `reliableCount` is now a `ReliableCount` object that keeps both
 * numbers distinct -- see its own javadoc for exactly how
 * `sessionOccurrences` is derived (SessionAggregateUpdater).
 */
@Data
public class SessionAggregates
{
	/** Skill name -> XP delta accumulated while this session was ACTIVE. Empty, never populated outside tests. */
	private Map<String, Long> xpGainedBySkill = new HashMap<>();

	/** One entry per atomic SERVER_NPC_LOOT notification observed while this session was ACTIVE. Empty, never populated outside tests. */
	private List<LootDropGroup> lootDrops = new ArrayList<>();

	/**
	 * The one "Reliable Count" metric for this session's activity, per
	 * the Kills/Completions/Task progress/no row policy -- null (no row)
	 * until a qualifying authoritative signal
	 * (currently only BOSS_KILL) has been observed for this session.
	 * See ReliableCount's own javadoc for the authoritative-value vs.
	 * session-occurrences distinction.
	 */
	private ReliableCount reliableCount;

	/**
	 * The total number of Slayer TASK UNITS consumed while this
	 * session was ACTIVE -- the SUM of each SLAYER_TASK_PROGRESS
	 * event's own taskUnitsConsumed, which is the observed decrease in
	 * the assignment's authoritative remaining-count value for that one
	 * event -- see EventPayloads.SlayerTaskProgress and
	 * SessionAggregateUpdater.apply(). This is NOT a physical NPC-kill
	 * count and must never be labeled "kills" anywhere player-facing:
	 * game mechanics (e.g. an expeditious-bracelet-style proc, or a
	 * bracelet-of-slaughter-style save) can make one physical kill
	 * consume more than one task unit, or zero, independent of any
	 * missed-observation reconciliation. This is NEVER an absolute
	 * task-state number either -- never currentRemaining,
	 * previousRemaining, or initialAmount from any single event. The
	 * field name is kept as-is (no persisted-document migration
	 * needed; see {@link #getSlayerTaskUnitsConsumed()} for an
	 * additive, honestly-named accessor) but the VALUE it stores has
	 * been corrected: it previously (incorrectly) held the most
	 * recently observed absolute amountRemaining, which made it
	 * collapse to whatever the latest event's remaining count happened
	 * to be instead of reflecting how much progress actually occurred
	 * during the session.
	 *
	 * Still retained: useful for session/
	 * history analytics even though the player-facing panel's PRIMARY
	 * live number is now latestSlayerCurrentRemaining below, not this
	 * field -- see CurrentSessionView's own javadoc.
	 */
	private Integer slayerProgressDelta;

	/**
	 * Honestly-named alias for {@link #getSlayerProgressDelta()} --
	 * same value, same TASK-UNIT semantics (never a physical NPC-kill
	 * count). The persisted field/Lombok accessor is kept as
	 * `slayerProgressDelta` to avoid a persisted-document migration;
	 * new call sites that want a self-documenting name should prefer
	 * this method over the raw getter.
	 */
	public Integer getSlayerTaskUnitsConsumed()
	{
		return slayerProgressDelta;
	}

	/**
	 * The LATEST observed authoritative currentRemaining from
	 * the most recent SLAYER_TASK_PROGRESS event this session has seen
	 * -- an ABSOLUTE task-state number, deliberately the opposite of
	 * slayerProgressDelta above: this is never summed/accumulated, only
	 * ever OVERWRITTEN by SessionAggregateUpdater.apply() with each new
	 * event's own value (when that event actually carries one -- an
	 * event with no currentRemaining leaves this field exactly as it
	 * was, it never resets to null). null until the first
	 * SLAYER_TASK_PROGRESS event carrying a currentRemaining has been
	 * observed this session; the UI must never invent a number when
	 * this is null (see CurrentSessionSnapshot/CurrentSessionView).
	 */
	private Integer latestSlayerCurrentRemaining;

	@Data
	public static class LootDropGroup
	{
		private String sourceName;
		/** ISO-8601 String, matching this project's timestamp convention (see Session's own javadoc). */
		private String observedAt;
		private List<LootItemAggregate> items = new ArrayList<>();
	}

	@Data
	public static class LootItemAggregate
	{
		private Integer itemId;
		private String itemName;
		private long quantity;
	}

	/**
	 * Which kind of
	 * authoritative count this is. COMPLETIONS is defined now for
	 * RAID_COMPLETION/ACTIVITY_COMPLETION's own authoritative
	 * completion-count messages, even
	 * though nothing populates it yet -- those signals are not
	 * yet linked to any modeled ActivityIdentity/session (raids have no
	 * ActivityType of their own), so attaching a session-occurrences
	 * count to "whatever combat session happens to be active" would be
	 * a fabricated linkage. That linkage is deferred until raid/
	 * activity identity is actually modeled.
	 */
	public enum ReliableCountKind
	{
		KILLS,
		COMPLETIONS
	}

	/**
	 * Keeps the
	 * authoritative, account-wide absolute value a game message reports
	 * (`authoritativeCurrentValue`) strictly separate from how many of
	 * those kills/completions this SPECIFIC session can claim
	 * (`sessionOccurrences`). `sourceKey` records which activity's
	 * identity key this count applies to (e.g. a boss's activityKey),
	 * so SessionAggregateUpdater can tell a genuinely new source (reset
	 * the session-occurrences baseline) apart from the same source
	 * advancing further (compute a real delta) -- see that class for
	 * the exact algorithm, including how the very first occurrence in a
	 * session is honestly counted and how an observed jump greater than
	 * 1 is preserved rather than assumed to be exactly 1.
	 */
	@Data
	public static class ReliableCount
	{
		private ReliableCountKind kind;
		private String sourceKey;
		private Integer authoritativeCurrentValue;
		private int sessionOccurrences;
	}
}
