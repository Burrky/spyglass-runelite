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

	/**
	 * SMART SLAYER. One Slayer session can span several assignments;
	 * slayerProgressDelta and Slayer XP stay SESSION-wide totals. These
	 * record both values when the CURRENT assignment was installed, so the
	 * "~XP remaining" estimate uses this assignment's own units/XP only
	 * (never blended across monsters). Both null = only one assignment so
	 * far (including every older session file) -- the session totals are
	 * then the assignment's own. Additive/nullable: old JSON loads unchanged.
	 */
	private Integer slayerAssignmentBaselineProgressDelta;
	private Long slayerAssignmentBaselineSlayerXp;

	/**
	 * SMART SLAYER ASSIGNMENT LEDGER, oldest -&gt; newest; the LAST record is
	 * the current assignment. Its loot items are a PARTITION of this
	 * session's canonical {@link #lootDrops}, never an independent store:
	 * every drop the session is credited with is also added to the last
	 * record, so the records' items sum to the session-wide loot.
	 *
	 * Null/empty for non-Slayer sessions and every session file written
	 * before this field existed. A Slayer session without one is
	 * bootstrapped (SessionAggregateUpdater.ensureSlayerAssignmentLedger())
	 * with one record seeded from ALL its existing loot -- exact, since
	 * released sessions never spanned a task rollover. Item IDs/names/
	 * quantities only; GP is priced at render time.
	 */
	private List<SlayerAssignmentRecord> slayerAssignments;

	/**
	 * STRUCTURED ACTIVITY: generic completion metric (games, chests, ...)
	 * for a STRUCTURED_ACTIVITY session. Additive and nullable -- absent on
	 * every other session and in every pre-existing JSON file. The label
	 * ("Games") is not persisted; it comes from the activity registry.
	 */
	private ActivityCompletionCount activityCompletions;

	/**
	 * STRUCTURED ACTIVITY: session-summary stats an activity's own native
	 * state supports (Tempoross: reward permits earned, final points of
	 * fully observed games). Additive and nullable -- absent on every other
	 * session and in every pre-existing JSON file. No transient game state
	 * (energy / essence / storm / live points) is ever stored here.
	 */
	private ActivitySessionStats activityStats;

	/**
	 * Items this session's activity PRODUCED (confirmed production only --
	 * e.g. GotR runes crafted at its altars), cumulative per item, first-
	 * produced order. Never loot; never reduced by dropping or spending.
	 * Additive/nullable: older sessions load with null.
	 */
	private List<ProducedItemAggregate> producedItems;

	/**
	 * One Slayer assignment INSTANCE within a session: its boundaries and
	 * end reason, task-unit count, and the slices of the session's loot,
	 * XP and active time it owns. {@code ordinal} is a 1-based sequence
	 * unique within the session (never reused, never derived from the task
	 * name) -- two consecutive assignments of the same task are two
	 * instances. Fields from endReason on are additive/nullable: older
	 * records load with them null and are treated conservatively.
	 */
	@Data
	public static class SlayerAssignmentRecord
	{
		private int ordinal;
		/** Player-facing task name (the assignment identity's display name). */
		private String taskName;
		/** The assignment's ActivityIdentity key (task@location, normalized). */
		private String assignmentKey;
		/** ISO-8601; when the authoritative ASSIGNED was observed. Null when bootstrapped mid-task. */
		private String assignedAt;
		/** Authoritative assigned amount when observed via ASSIGNED; null when bootstrapped mid-task. */
		private Integer initialAmount;
		/**
		 * The game's own original size of this assignment (varp
		 * SLAYER_COUNT_ORIGINAL adjusted by any quantity modifier, read
		 * natively) -- ASSIGNMENT METADATA ONLY. Recorded for a bootstrapped
		 * record, where Spyglass joined mid-task and initialAmount stays
		 * null. Knowing the task was originally 180 does not mean Spyglass
		 * observed the first 107 units: this is never used to derive
		 * unitsCompleted, XP, active duration or any other session-owned
		 * metric, never changes bootstrapped provenance, and never replaces
		 * initialAmount (whose meaning, "amount when the ASSIGNED was
		 * observed", is unchanged). Null when unknown and on older records.
		 */
		private Integer assignedInitialAmount;
		/** Latest authoritative remaining for this assignment (0 = completed). */
		private Integer latestRemaining;
		/** ISO-8601; when a newer assignment superseded this one (completion or replacement). Null while current. */
		private String endedAt;
		/** True when created from existing session state rather than an observed ASSIGNED (mid-task start / legacy file). */
		private boolean bootstrapped;
		private List<LootItemAggregate> items = new ArrayList<>();

		/**
		 * How this assignment ended; null while current/in progress, and
		 * for any assignment whose end Spyglass did not authoritatively
		 * observe (session ended mid-task, legacy record). COMPLETED: the
		 * authoritative completion path set remaining to 0. REPLACED_EARLY:
		 * a genuinely new authoritative assignment replaced it while it
		 * still had remaining &gt; 0 (shown to players as "Skipped").
		 */
		private SlayerAssignmentEndReason endReason;
		/** ISO-8601; when the authoritative completion was observed. Null unless COMPLETED. */
		private String completedAt;
		/**
		 * Authoritative Slayer task units Spyglass observed being consumed
		 * while this was the session's current assignment (every
		 * SLAYER_TASK_PROGRESS unit, plus the completion reconciliation of a
		 * final unit RuneLite reports no trailing progress for). For a
		 * fully observed assignment this equals initialAmount - remaining;
		 * for one bootstrapped mid-task it counts only from Spyglass's own
		 * observation onward -- never pre-observation progress. Null only on
		 * legacy records.
		 */
		private Integer unitsCompleted;
		/**
		 * XP (per skill, same keys as SessionAggregates#xpGainedBySkill)
		 * this session gained while this was the current, unfinished
		 * assignment -- a non-destructive slice of the canonical session
		 * XP, credited by each XP metric's own reference time (so a final
		 * kill's XP whose window began before completion still counts, and
		 * XP after completion/replacement never does). Null = not tracked
		 * (legacy record; installed conservatively from 0 on load).
		 */
		private Map<String, Long> xpGainedBySkill;
		/** Session accumulatedActiveDurationMillis when this assignment's XP/time tracking began. */
		private Long activeDurationStartMillis;
		/** Session accumulatedActiveDurationMillis when it ended (completion or replacement); null while current and unfinished. */
		private Long activeDurationEndMillis;

		/**
		 * The Slayer master varbit value this assignment came from (1 Turael/Aya
		 * ... 10 Mortimer), from the authoritative ASSIGNED, or -- for a record
		 * Spyglass joined mid-task -- the first observed progress of the same
		 * assignment. Null when unknown and on older records.
		 */
		private Integer masterId;
		/**
		 * Native Bracelet of slaughter / Expeditious bracelet activations
		 * (the game's own chat confirmation) seen while this was the current
		 * assignment. Null on older records = not tracked (never shown as 0).
		 */
		private Integer slaughterProcs;
		private Integer expeditiousProcs;
		/**
		 * ACTUAL KILLS of this assignment's encounter (exact NPC name -> owned
		 * deaths): the task's own targets, subordinates it summons, its
		 * superiors -- see SlayerEncounterKills. Not task units. Null on older
		 * records and before tracking began = unknown (never shown as 0).
		 */
		private Map<String, Long> killsByNpc;
		/** Of those, superior creatures (exact name -> kills); null = not tracked. */
		private Map<String, Long> superiorsByNpc;
		/** Of those, subordinates the task's monsters summoned (exact name -> kills); null = not tracked. */
		private Map<String, Long> subordinatesByNpc;

		/** Every tracked owned death of this assignment's encounter; null when not tracked (older record). Derived. */
		public Long trackedKills()
		{
			return sum(killsByNpc);
		}

		/**
		 * ACTUAL KILLS: every owned NPC death of this assignment's encounter --
		 * its own targets and superiors, plus the subordinates they summoned.
		 * Never task units, and never derived from them: the unit-derived
		 * {@link #observedKills()} only FLOORS the target-and-superior part (a
		 * target death the encounter tracking could not see is never lost),
		 * and subordinates are always added on top (they use no task unit), so
		 * nothing is counted twice. Null when neither is known. Derived, not
		 * persisted.
		 */
		public Long actualKills()
		{
			Long tracked = trackedKills();
			Integer observed = observedKills();
			if (tracked == null)
			{
				return observed == null ? null : observed.longValue();
			}
			Long sub = sum(subordinatesByNpc);
			long subordinates = sub == null ? 0L : sub;
			long targets = tracked - subordinates;
			return (observed == null ? targets : Math.max(targets, observed)) + subordinates;
		}

		/** Whether the per-NPC breakdown accounts for every actual kill (else only the total is shown). */
		public boolean breakdownComplete()
		{
			Long tracked = trackedKills();
			return tracked != null && tracked.equals(actualKills());
		}

		/** Superior creatures killed on this assignment; null when not tracked. Derived. */
		public Long superiorKills()
		{
			return sum(superiorsByNpc);
		}

		private static Long sum(Map<String, Long> m)
		{
			if (m == null)
			{
				return null;
			}
			long n = 0L;
			for (Long v : m.values())
			{
				n += v == null ? 0L : v;
			}
			return n;
		}

		/**
		 * Kills Spyglass observed for this assignment: task units it saw
		 * consumed, minus extra units an Expeditious bracelet consumed, plus
		 * kills a Bracelet of slaughter kept from consuming a unit. Never
		 * {@code initial - remaining}; subordinate NPCs that consume no unit
		 * never count. Null when units are unknown (legacy) or when bracelet
		 * tracking did not exist for this record (pre-tracking file: the
		 * unit count alone cannot be called kills). Derived, not persisted.
		 */
		public Integer observedKills()
		{
			if (unitsCompleted == null || slaughterProcs == null || expeditiousProcs == null)
			{
				return null;
			}
			return Math.max(0, unitsCompleted - expeditiousProcs + slaughterProcs);
		}

		/**
		 * The player-facing "xN": this record's own observed unit count; for a
		 * legacy record without one, initialAmount - remaining when both are
		 * authoritatively known (a fully observed assignment); otherwise null
		 * (unknown -- no count is shown rather than a guess). Never negative.
		 * Derived, not persisted (Gson serializes fields only).
		 */
		public Integer observedUnitsCompleted()
		{
			if (unitsCompleted != null)
			{
				return Math.max(0, unitsCompleted);
			}
			if (!bootstrapped && initialAmount != null && latestRemaining != null)
			{
				return Math.max(0, initialAmount - latestRemaining);
			}
			return null;
		}
	}

	@Data
	public static class ActivityCompletionCount
	{
		/** Structured activity key the count belongs to. */
		private String activityKey;
		/** Completions observed during this session (never fabricated). */
		private int sessionCompletions;
		/** The game's own latest running count, when the source carries one. */
		private Integer latestAuthoritativeValue;
	}

	@Data
	public static class ActivitySessionStats
	{
		/** Structured activity key the stats belong to. */
		private String activityKey;
		/** Native reward currency gained this session (sum of positive changes; spending never subtracts). */
		private long currencyGained;
		/**
		 * Per-currency breakdown of currencyGained (e.g. GotR "elemental_points"
		 * / "catalytic_points"), in first-earned order. Additive/nullable:
		 * older sessions load with null and fall back to currencyGained.
		 */
		private java.util.Map<String, Long> currencyGainedByKey;
		/** Completed games whose final score was trustworthy (observed from start); the Avg/Best population. */
		private int scoredCompletions;
		/** Sum of those games' final scores. */
		private long scoreTotal;
		/** Highest of those games' final scores, or null when none. */
		private Integer bestScore;
		/**
		 * Total time spent inside the activity's play area across rounds (win
		 * or loss), from game ticks; 0 when the activity does not track it.
		 * The denominator of per-hour rates that must exclude lobby waiting.
		 */
		private long roundMillis;
		/** Rounds whose in-area time is included in roundMillis. */
		private int roundsTimed;
		/** Of those, rounds seen from start to end (their time): the Avg Round population. */
		private long completeRoundMillis;
		private int completeRounds;
		/** Counted actions this session (action key -> total), e.g. Wintertodt roots fed; null when none. */
		private java.util.Map<String, Long> actionCounts;
		/** Wintertodt: the session's effective playstyle label for History ("Mass • Points", or "Auto" if never resolved); null when none. */
		private String playstyle;
		/** The game's running count of the last scored completion (duplicate-evidence guard), or null. */
		private Integer lastScoredCompletionCount;

		/** Arithmetic mean over scoredCompletions, or null when there are none. */
		public Long averageScore()
		{
			return scoredCompletions <= 0 ? null : Math.round(scoreTotal / (double) scoredCompletions);
		}
	}

	/**
	 * SKILL COMPANION session stats (see skills.SkillCompanions), alongside producedItems
	 * (which holds the TOTAL production per resource): what of this session's
	 * production was positively observed entering the bank, and how long each
	 * method was trained. Null for every session without a skill companion
	 * (and for every session written before it existed).
	 */
	private SkillSessionStats skillStats;

	@Data
	public static class SkillSessionStats
	{
		/** The SKILLING session key ("mining"). */
		private String skillKey;
		/** BANKED per resource (this session's own production only), first-banked order; null when none. */
		private List<ProducedItemAggregate> banked;
		/** Training time per method key ("amethyst" -> ms); null when none. */
		private Map<String, Long> methodMillis;
		/** The method current at the latest update (History label fallback). */
		private String lastMethod;
		/**
		 * ADDED (Skills Phase 3: Agility). Completed cycles per method key
		 * (Agility: laps per course); null for skills without cycles and in
		 * every session written before this existed.
		 */
		private Map<String, CycleAggregate> cycles;
		/**
		 * ADDED (Skills Phase 4). The part of producedItems a tool transformed
		 * the moment it was produced, per resource; a subset of production,
		 * never banked.
		 * Null when none and in every session written before this existed.
		 */
		private List<ProducedItemAggregate> autoTransformed;
		/**
		 * ADDED (Skills Phase 4). Contextual occurrences per kind key (events
		 * the player took part in); null when none and in every older session.
		 */
		private Map<String, Long> occurrences;
		/**
		 * ADDED (Skills Phase 5). Session-owned inputs a transformation used up,
		 * per item (e.g. essence crafted into runes); never production. Null
		 * when none and in every older session.
		 */
		private List<ProducedItemAggregate> consumed;
	}

	/** Valid completed cycles of one method (Agility: one course's laps). Durations in ms; bestMillis 0 = none. */
	@Data
	public static class CycleAggregate
	{
		private long count;
		private long totalMillis;
		private long bestMillis;
		private long failures;
	}

	/**
	 * One item an activity PRODUCED this session (e.g. GotR runes crafted)
	 * and its cumulative exact quantity. Output -- deliberately separate from
	 * loot (LootDropGroup / LootItemAggregate), which is what was received.
	 */
	@Data
	public static class ProducedItemAggregate
	{
		private Integer itemId;
		private String itemName;
		private long quantity;
	}

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
