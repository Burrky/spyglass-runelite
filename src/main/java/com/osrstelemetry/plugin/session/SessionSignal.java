package com.osrstelemetry.plugin.session;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A small, immutable, typed input model for ActivitySignalClassifier --
 * deliberately NOT a raw RuneLite EventBus class and NOT an untyped
 * Map<String,Object>. A future RuneLite router is responsible for
 * translating real events into these pure signals; this class only
 * defines the shape, the classifier that consumes it, and the pure
 * batch resolver (SessionSignalBatchResolver) that groups and resolves
 * a tick's worth of them.
 *
 * GAME TICK: observedAt remains the historical wall-clock
 * timestamp used by lifecycle/persistence (unchanged meaning). gameTick
 * is a SEPARATE, OPTIONAL field representing RuneLite's own tick
 * counter (conceptually `client.getTickCount()`), because several real
 * events belonging to one game action/tick can be observed at slightly
 * different wall-clock Instants -- exact Instant equality is no longer
 * assumed to be a reliable batch key (see SessionSignalBatchResolver).
 * This class never imports net.runelite.api.Client or any RuneLite
 * event class -- the future thin router is what will call
 * withGameTick(client.getTickCount()) when constructing a signal; pure
 * tests can supply arbitrary tick values directly. gameTick is null
 * for any signal that was never tagged with one, in which case the
 * batch resolver falls back to exact observedAt equality as its key.
 *
 * SLAYER CURRENT REMAINING: a SEPARATE,
 * OPTIONAL field on SLAYER_TASK_PROGRESS signals ONLY, carrying
 * EventPayloads.SlayerTaskProgress's own authoritative currentRemaining
 * for THIS one observed event -- never previousRemaining/initialAmount,
 * and never re-derived from slayerUnitsConsumed. Deliberately additive:
 * the original 4-arg slayerTaskProgress() factory below still exists
 * and now simply delegates with a null currentRemaining, so every
 * existing caller/test is completely unaffected. Nullable because older
 * or synthetic callers may not have this value; consumers must never
 * invent one when absent (see SessionAggregateUpdater/SessionSnapshot/
 * CurrentSessionSnapshot's own javadoc for how "latest observed value,
 * or nothing displayed" is honored end to end).
 *
 * Every field beyond kind/observedAt is nullable -- only the fields
 * relevant to a given SignalKind are ever populated (see the static
 * factory methods below, one per kind, which is the only supported way
 * to construct an instance so an impossible combination, e.g. a
 * BOSS_KILL signal with a slayerTaskName, can never be built).
 */
public final class SessionSignal
{
	private final SignalKind kind;
	private final Instant observedAt;

	private final String skill;
	private final Long xpDelta;

	/**
	 * The XP_CHANGE aggregation window's own START --
	 * i.e. when SkillsCollector actually BEGAN accumulating this delta
	 * (EventPayloads.XpChange#getWindowStart(), previously discarded
	 * entirely by SessionEventMapper) -- deliberately DIFFERENT from
	 * getObservedAt() above, which for XP_CHANGE is the FLUSH/processing
	 * time (when the ~30s-aggregated window finally closed), not when
	 * the underlying XP was actually earned. A real session switch can
	 * happen strictly BETWEEN this window's own start and its eventual
	 * flush -- see classifyXpChange()'s own ownership-guard javadoc for
	 * exactly how this is used to keep a delayed XP_CHANGE from ever
	 * being credited to a session that did not even exist yet when the
	 * XP was earned. Null for every signal built via the legacy 3-arg
	 * xpChange() factory (every other existing caller/test) -- the
	 * ownership guard is a no-op whenever this is null, so nothing about
	 * this field is required for any signal kind/caller that does not
	 * opt into it.
	 */
	private final Instant xpWindowStart;

	/**
	 * This XP_CHANGE window's own ABSOLUTE new XP value at
	 * the moment it closed (EventPayloads.XpChange#getNewXp(), previously
	 * discarded entirely, exactly like xpWindowStart used to be) --
	 * DELIBERATELY an absolute skill total, never a delta. Together with
	 * ClassifierContext's own per-session "XP at session start" baseline
	 * (see ClassifierContext.noteSessionSwitchXpBaseline()), this is what
	 * lets classifyXpChange() EXACTLY separate a window that SPANS a
	 * session switch into its pre-switch and post-switch portions,
	 * without ever guessing a proportional time-based split (see
	 * classifyXpChange()'s own javadoc for the full boundary-ownership
	 * rule this enables). Null for every signal built via the 3-arg or
	 * 4-arg xpChange() overloads (every pre-existing caller/test) -- the
	 * boundary-split logic simply cannot attempt an exact split without
	 * it and falls back to the conservative whole-window ignore, so
	 * nothing about this field is required for any caller that does not
	 * opt into it.
	 */
	private final Long xpNewValue;

	private final String slayerTaskName;
	private final String slayerTaskLocation;
	private final Integer slayerUnitsConsumed;
	private final Integer slayerInitialAmount;
	private final Integer slayerCurrentRemaining;

	private final String bossOrActivitySource;
	private final Integer reliableCount;

	private final List<LootDrop> lootDrops;

	private final Long gameTick;

	// See SignalKind.NPC_INTERACTION_TARGET's own javadoc. Both nullable --
	// populated only for that one kind; every other kind leaves them
	// null, exactly like every other kind-specific field on this class.
	private final Integer npcId;
	private final String npcName;

	/** Structured-activity key for the STRUCTURED_* kinds (null = outside, for a location signal). */
	private final String structuredActivityKey;
	/** Template region id of a STRUCTURED_ACTIVITY_LOCATION signal. */
	private final Integer regionId;
	/**
	 * SLAYER_TASK_COMPLETED only: the task was cleared WITHOUT being
	 * finished (cancelled/blocked for points) -- see
	 * EventPayloads.SlayerTaskCompleted#cancelled. False otherwise.
	 */
	private final boolean slayerTaskCancelled;
	/**
	 * STRUCTURED_ACTIVITY_SCORE: the game's final score (null = unknown).
	 * STRUCTURED_ACTIVITY_CURRENCY: the positive amount gained.
	 */
	private final Long activityAmount;
	/** STRUCTURED_ACTIVITY_SCORE only: the game was observed from its start (see TemporossGameTracker). */
	private final boolean activityAmountTrusted;

	private SessionSignal(
		SignalKind kind,
		Instant observedAt,
		String skill,
		Long xpDelta,
		String slayerTaskName,
		String slayerTaskLocation,
		Integer slayerUnitsConsumed,
		Integer slayerInitialAmount,
		String bossOrActivitySource,
		Integer reliableCount,
		List<LootDrop> lootDrops,
		Long gameTick,
		Integer slayerCurrentRemaining,
		Integer npcId,
		String npcName,
		Instant xpWindowStart,
		Long xpNewValue,
		String structuredActivityKey,
		Integer regionId)
	{
		this(kind, observedAt, skill, xpDelta, slayerTaskName, slayerTaskLocation, slayerUnitsConsumed, slayerInitialAmount,
			bossOrActivitySource, reliableCount, lootDrops, gameTick, slayerCurrentRemaining, npcId, npcName, xpWindowStart,
			xpNewValue, structuredActivityKey, regionId, false, null, false);
	}

	private SessionSignal(
		SignalKind kind,
		Instant observedAt,
		String skill,
		Long xpDelta,
		String slayerTaskName,
		String slayerTaskLocation,
		Integer slayerUnitsConsumed,
		Integer slayerInitialAmount,
		String bossOrActivitySource,
		Integer reliableCount,
		List<LootDrop> lootDrops,
		Long gameTick,
		Integer slayerCurrentRemaining,
		Integer npcId,
		String npcName,
		Instant xpWindowStart,
		Long xpNewValue,
		String structuredActivityKey,
		Integer regionId,
		boolean slayerTaskCancelled,
		Long activityAmount,
		boolean activityAmountTrusted)
	{
		this.kind = Objects.requireNonNull(kind, "kind");
		this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
		this.skill = skill;
		this.xpDelta = xpDelta;
		this.slayerTaskName = slayerTaskName;
		this.slayerTaskLocation = slayerTaskLocation;
		this.slayerUnitsConsumed = slayerUnitsConsumed;
		this.slayerInitialAmount = slayerInitialAmount;
		this.bossOrActivitySource = bossOrActivitySource;
		this.reliableCount = reliableCount;
		this.lootDrops = lootDrops == null ? null : Collections.unmodifiableList(lootDrops);
		this.gameTick = gameTick;
		this.slayerCurrentRemaining = slayerCurrentRemaining;
		this.npcId = npcId;
		this.npcName = npcName;
		this.xpWindowStart = xpWindowStart;
		this.xpNewValue = xpNewValue;
		this.structuredActivityKey = structuredActivityKey;
		this.regionId = regionId;
		this.slayerTaskCancelled = slayerTaskCancelled;
		this.activityAmount = activityAmount;
		this.activityAmountTrusted = activityAmountTrusted;
	}

	public static SessionSignal xpChange(Instant observedAt, String skill, long xpDelta)
	{
		return xpChange(observedAt, skill, xpDelta, null);
	}

	/**
	 * Same contract as the 3-arg overload, plus
	 * {@code windowStart} -- see this class's own xpWindowStart field
	 * javadoc for exactly what it means and how classifyXpChange() uses
	 * it. {@code windowStart == null} (including via the 3-arg overload
	 * above) is completely equivalent to the pre-fix behavior: the
	 * ownership guard never triggers, so every existing caller/test is
	 * entirely unaffected.
	 */
	public static SessionSignal xpChange(Instant observedAt, String skill, long xpDelta, Instant windowStart)
	{
		return xpChange(observedAt, skill, xpDelta, windowStart, null);
	}

	/**
	 * Same contract as the 4-arg overload, plus
	 * {@code newXpValue} -- see this class's own xpNewValue field javadoc.
	 * {@code newXpValue == null} (including via the 3-arg/4-arg overloads
	 * above) is completely equivalent to the pre-fix behavior: a spanning
	 * window can never be exactly split without it, so classifyXpChange()
	 * conservatively falls back to ignoring the whole window exactly as
	 * before -- every existing caller/test is entirely unaffected.
	 */
	public static SessionSignal xpChange(Instant observedAt, String skill, long xpDelta, Instant windowStart, Long newXpValue)
	{
		return new SessionSignal(SignalKind.XP_CHANGE, observedAt, skill, xpDelta, null, null, null, null, null, null, null, null, null, null, null, windowStart, newXpValue, null, null);
	}

	public static SessionSignal slayerTaskAssigned(Instant observedAt, String taskName, String taskLocation, Integer initialAmount)
	{
		return new SessionSignal(SignalKind.SLAYER_TASK_ASSIGNED, observedAt, null, null, taskName, taskLocation, null, initialAmount, null, null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * `unitsConsumed` is EventPayloads.SlayerTaskProgress's own
	 * taskUnitsConsumed field -- how many task units this ONE observed
	 * progress event consumed (normally 1; can legitimately be &gt;1 for
	 * a genuinely-missed intermediate observation) -- never
	 * currentRemaining/previousRemaining/initialAmount or any other
	 * absolute task-state number. See SessionAggregateUpdater.apply()
	 * for why this must be ACCUMULATED across a session, never merely
	 * the latest value.
	 *
	 * Kept exactly as-is (delegates to the 5-arg overload with a null
	 * currentRemaining) so every existing caller is unaffected -- see
	 * the 5-arg overload below for its own addition.
	 */
	public static SessionSignal slayerTaskProgress(Instant observedAt, String taskName, String taskLocation, int unitsConsumed)
	{
		return slayerTaskProgress(observedAt, taskName, taskLocation, unitsConsumed, null);
	}

	/**
	 * ADDITIVE overload carrying this one event's own authoritative
	 * `currentRemaining` (EventPayloads.SlayerTaskProgress's own field)
	 * alongside `unitsConsumed` -- see this class's own javadoc. Never
	 * previousRemaining/initialAmount; nullable so a caller with no
	 * remaining-count evidence can still build a signal exactly as
	 * before.
	 */
	public static SessionSignal slayerTaskProgress(Instant observedAt, String taskName, String taskLocation, int unitsConsumed, Integer currentRemaining)
	{
		return slayerTaskProgress(observedAt, taskName, taskLocation, unitsConsumed, currentRemaining, null);
	}

	/**
	 * ADDITIVE overload also carrying the task's native original size
	 * (SLAYER_COUNT_ORIGINAL +/- modifier) as ASSIGNMENT METADATA only --
	 * read solely to fill SlayerAssignmentRecord#assignedInitialAmount on a
	 * bootstrapped record. Never used for units, XP, timing or lifecycle;
	 * null when not yet known.
	 */
	public static SessionSignal slayerTaskProgress(Instant observedAt, String taskName, String taskLocation, int unitsConsumed,
		Integer currentRemaining, Integer nativeInitialAmount)
	{
		return new SessionSignal(SignalKind.SLAYER_TASK_PROGRESS, observedAt, null, null, taskName, taskLocation, unitsConsumed, nativeInitialAmount, null, null, null, null, currentRemaining, null, null, null, null, null, null);
	}

	/**
	 * Progress plus the master varbit at that observation (assignment
	 * METADATA, never identity). Carried in the reliableCount slot, which
	 * no Slayer signal otherwise uses -- read it only via getSlayerMasterId().
	 */
	public static SessionSignal slayerTaskProgress(Instant observedAt, String taskName, String taskLocation, int unitsConsumed,
		Integer currentRemaining, Integer nativeInitialAmount, Integer masterId)
	{
		return new SessionSignal(SignalKind.SLAYER_TASK_PROGRESS, observedAt, null, null, taskName, taskLocation, unitsConsumed, nativeInitialAmount, null, masterId, null, null, currentRemaining, null, null, null, null, null, null);
	}

	/** Assignment plus its master varbit (metadata; see the masterId overload of slayerTaskProgress). */
	public static SessionSignal slayerTaskAssigned(Instant observedAt, String taskName, String taskLocation, Integer initialAmount, Integer masterId)
	{
		return new SessionSignal(SignalKind.SLAYER_TASK_ASSIGNED, observedAt, null, null, taskName, taskLocation, null, initialAmount, null, masterId, null, null, null, null, null, null, null, null, null);
	}

	/** Native bracelet activation; `effect` is "SLAUGHTER" or "EXPEDITIOUS" (carried in the source slot). */
	public static SessionSignal slayerBraceletEffect(Instant observedAt, String effect)
	{
		return new SessionSignal(SignalKind.SLAYER_BRACELET_EFFECT, observedAt, null, null, null, null, null, null, effect, null, null, null, null, null, null, null, null, null, null);
	}

	/** An owned Slayer-encounter NPC death: the NPC's exact name (task slot) and its role (source slot). */
	public static SessionSignal slayerEncounterKill(Instant observedAt, String npcName, String role)
	{
		return new SessionSignal(SignalKind.SLAYER_ENCOUNTER_KILL, observedAt, null, null, npcName, null, null, null, role, null, null, null, null, null, null, null, null, null, null);
	}

	/** SLAYER_ENCOUNTER_KILL: the dead NPC's exact name, else null. */
	public String getEncounterKillNpc()
	{
		return kind == SignalKind.SLAYER_ENCOUNTER_KILL ? slayerTaskName : null;
	}

	/** SLAYER_ENCOUNTER_KILL: "TASK" / "SUBORDINATE" / "SUPERIOR", else null. */
	public String getEncounterKillRole()
	{
		return kind == SignalKind.SLAYER_ENCOUNTER_KILL ? bossOrActivitySource : null;
	}

	/** SLAYER_TASK_ASSIGNED / SLAYER_TASK_PROGRESS: the Slayer master varbit, or null. */
	public Integer getSlayerMasterId()
	{
		return kind == SignalKind.SLAYER_TASK_ASSIGNED || kind == SignalKind.SLAYER_TASK_PROGRESS ? reliableCount : null;
	}

	/** SLAYER_BRACELET_EFFECT: "SLAUGHTER" / "EXPEDITIOUS", else null. */
	public String getSlayerBraceletEffect()
	{
		return kind == SignalKind.SLAYER_BRACELET_EFFECT ? bossOrActivitySource : null;
	}

	public static SessionSignal slayerTaskCompleted(Instant observedAt, String taskName, String taskLocation)
	{
		return slayerTaskCompleted(observedAt, taskName, taskLocation, false);
	}

	/** {@code cancelled}: the task was cleared without being finished -- see {@link #isSlayerTaskCancelled()}. */
	public static SessionSignal slayerTaskCompleted(Instant observedAt, String taskName, String taskLocation, boolean cancelled)
	{
		return new SessionSignal(SignalKind.SLAYER_TASK_COMPLETED, observedAt, null, null, taskName, taskLocation, null, null, null, null, null, null, null, null, null, null, null, null, null, cancelled, null, false);
	}

	public static SessionSignal bossKill(Instant observedAt, String bossName, int reliableCount)
	{
		return new SessionSignal(SignalKind.BOSS_KILL, observedAt, null, null, null, null, null, null, bossName, reliableCount, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * Deliberately mirrors bossKill()'s shape minus reliableCount --
	 * reliableCount is passed as null unconditionally, both here and
	 * because ActivitySignalClassifier.classifyBossActivityContext()
	 * always calls decideCombatBranch() with a null MetricUpdate for
	 * this kind, structurally guaranteeing this signal can never carry
	 * kill-count authority. See EventType.BOSS_ACTIVITY_CONTEXT's
	 * javadoc for the full non-authoritative-vs-BOSS_KILL distinction.
	 */
	public static SessionSignal bossActivityContext(Instant observedAt, String bossName)
	{
		return new SessionSignal(SignalKind.BOSS_ACTIVITY_CONTEXT, observedAt, null, null, null, null, null, null, bossName, null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * See SignalKind.NPC_INTERACTION_TARGET's own javadoc -- context only,
	 * never itself lifecycle-carrying. npcId is nullable (mirrors
	 * NpcDeath.npcId's own "no fabricated sentinel for an unresolved
	 * composition" precedent); npcName is expected non-null in
	 * practice (the production collector never emits this kind for a
	 * null-named target) but is not asserted non-null here, matching
	 * this class's existing "every field beyond kind/observedAt is
	 * nullable" discipline.
	 */
	public static SessionSignal npcInteractionTarget(Instant observedAt, Integer npcId, String npcName)
	{
		return new SessionSignal(SignalKind.NPC_INTERACTION_TARGET, observedAt, null, null, null, null, null, null, null, null, null, null, null, npcId, npcName, null, null, null, null);
	}

	/**
	 * See SignalKind.RAW_COMBAT_XP_OBSERVED's own javadoc. Deliberately
	 * reuses the existing `skill` field (the exact
	 * same slot xpChange() populates) rather than adding a new one --
	 * this signal is a skill NAME only; xpDelta is always null here and
	 * getXpDelta() always returns null for this kind, structurally
	 * preventing this signal from ever carrying an XP value.
	 */
	/**
	 * See SignalKind#STRUCTURED_LIVE_XP. {@code delta} and {@code xpAfter}
	 * (absolute XP once this gain landed; nullable) are carried only so the
	 * coordinator can attach the gain to the live display and place the
	 * new session's exact XP boundary -- never applied as a metric.
	 */
	public static SessionSignal structuredLiveXp(Instant observedAt, String skill, long delta, Long xpAfter)
	{
		return new SessionSignal(SignalKind.STRUCTURED_LIVE_XP, observedAt, skill, delta, null, null, null, null, null, null, null, null, null, null, null, null, xpAfter, null, null);
	}

	public static SessionSignal rawCombatXpObserved(Instant observedAt, String skill)
	{
		return new SessionSignal(SignalKind.RAW_COMBAT_XP_OBSERVED, observedAt, skill, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal raidCompletion(Instant observedAt, String activityName)
	{
		return new SessionSignal(SignalKind.RAID_COMPLETION, observedAt, null, null, null, null, null, null, activityName, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal activityCompletion(Instant observedAt, String activityName)
	{
		return activityCompletion(observedAt, activityName, null);
	}

	/** completionCount: the game's own running count from the chat message, when known. */
	public static SessionSignal activityCompletion(Instant observedAt, String activityName, Integer completionCount)
	{
		return new SessionSignal(SignalKind.ACTIVITY_COMPLETION, observedAt, null, null, null, null, null, null, activityName, completionCount, null, null, null, null, null, null, null, null, null);
	}

	/** Entered ({@code activityKey != null}) or left a structured-activity location. Context only. */
	public static SessionSignal structuredActivityLocation(Instant observedAt, String activityKey, int regionId)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_LOCATION, observedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, activityKey, regionId);
	}

	/** One completion from an activity's own chat pattern; completionCount is the game's running count, or null. */
	public static SessionSignal structuredActivityCompletion(Instant observedAt, String activityKey, Integer completionCount)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_COMPLETION, observedAt, null, null, null, null, null, null, null, completionCount, null, null, null, null, null, null, null, activityKey, null);
	}

	/** One claim of a structured reward source; rewardSourceKey is its canonical key (StructuredRewardSource#getKey). */
	public static SessionSignal structuredReward(Instant observedAt, String activityKey, String rewardSourceKey, List<LootDrop> drops)
	{
		return new SessionSignal(SignalKind.STRUCTURED_REWARD, observedAt, null, null, null, null, null, null, rewardSourceKey, null, drops, null, null, null, null, null, null, activityKey, null);
	}

	public static SessionSignal questCompleted(Instant observedAt, String questName)
	{
		return new SessionSignal(SignalKind.QUEST_COMPLETED, observedAt, null, null, null, null, null, null, questName, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal npcDeath(Instant observedAt)
	{
		return new SessionSignal(SignalKind.NPC_DEATH, observedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal npcLootAttributed(Instant observedAt)
	{
		return new SessionSignal(SignalKind.NPC_LOOT_ATTRIBUTED, observedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal serverNpcLoot(Instant observedAt, String sourceName, List<LootDrop> drops)
	{
		return new SessionSignal(SignalKind.SERVER_NPC_LOOT, observedAt, null, null, null, null, null, null, sourceName, null, drops, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal collectionLogNewItem(Instant observedAt, String itemName)
	{
		return new SessionSignal(SignalKind.COLLECTION_LOG_NEW_ITEM, observedAt, null, null, null, null, null, null, itemName, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal combatAchievementCompleted(Instant observedAt, String achievementName)
	{
		return new SessionSignal(SignalKind.COMBAT_ACHIEVEMENT_COMPLETED, observedAt, null, null, null, null, null, null, achievementName, null, null, null, null, null, null, null, null, null, null);
	}

	public static SessionSignal storageSnapshot(Instant observedAt)
	{
		return new SessionSignal(SignalKind.STORAGE_SNAPSHOT, observedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * Attaches the RuneLite game-tick counter this signal
	 * belongs to, returning a new immutable copy (this class never
	 * mutates in place). The future thin router is expected to call
	 * this with `client.getTickCount()` immediately after building a
	 * signal from a real event; pure tests may supply any long value.
	 */
	public SessionSignal withGameTick(long gameTick)
	{
		SessionSignal tagged = new SessionSignal(
			kind, observedAt, skill, xpDelta, slayerTaskName, slayerTaskLocation, slayerUnitsConsumed,
			slayerInitialAmount, bossOrActivitySource, reliableCount, lootDrops, gameTick, slayerCurrentRemaining,
			npcId, npcName, xpWindowStart, xpNewValue, structuredActivityKey, regionId, slayerTaskCancelled,
			activityAmount, activityAmountTrusted);
		tagged.skillProduction = skillProduction;
		return tagged;
	}

	/**
	 * SKILL_PRODUCTION only: the skill companion's update. Set once by
	 * {@link #skillProduction(Instant, SkillProductionUpdate)} (and carried by
	 * {@link #withGameTick}); null for every other kind.
	 */
	private SkillProductionUpdate skillProduction;

	/** See SignalKind#SKILL_PRODUCTION. */
	public static SessionSignal skillProduction(Instant observedAt, SkillProductionUpdate update)
	{
		SessionSignal s = new SessionSignal(SignalKind.SKILL_PRODUCTION, observedAt, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, false, null, false);
		s.skillProduction = Objects.requireNonNull(update, "update");
		return s;
	}

	/** SKILL_PRODUCTION only: the update; null for every other kind. */
	public SkillProductionUpdate getSkillProduction()
	{
		return kind == SignalKind.SKILL_PRODUCTION ? skillProduction : null;
	}

	/** See SignalKind#STRUCTURED_ACTIVITY_SCORE. completionCount: the game's running count (dedupe), or null. */
	public static SessionSignal structuredActivityScore(Instant observedAt, String activityKey, Integer completionCount,
		Integer score, boolean observedFromStart)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_SCORE, observedAt, null, null, null, null, null, null, null,
			completionCount, null, null, null, null, null, null, null, activityKey, null, false,
			score == null ? null : (long) score, observedFromStart);
	}

	/** See SignalKind#STRUCTURED_ACTIVITY_CURRENCY. {@code gained} &gt; 0. */
	public static SessionSignal structuredActivityCurrency(Instant observedAt, String activityKey, long gained)
	{
		return structuredActivityCurrency(observedAt, activityKey, null, gained);
	}

	/**
	 * See SignalKind#STRUCTURED_ACTIVITY_CURRENCY. {@code currencyKey}
	 * identifies which of the activity's currencies (null = its only one);
	 * carried in the source-string slot, read via {@link #getActivityCurrencyKey()}.
	 */
	public static SessionSignal structuredActivityCurrency(Instant observedAt, String activityKey, String currencyKey, long gained)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_CURRENCY, observedAt, null, null, null, null, null, null, currencyKey,
			null, null, null, null, null, null, null, null, activityKey, null, false, gained, false);
	}

	/**
	 * See SignalKind#STRUCTURED_ACTIVITY_PRODUCTION. The produced items ride
	 * in the item-list slot, read via {@link #getProducedItems()} -- they are
	 * output, and no loot path ever reads this kind.
	 */
	public static SessionSignal structuredActivityProduction(Instant observedAt, String activityKey, List<LootDrop> producedItems)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_PRODUCTION, observedAt, null, null, null, null, null, null, null,
			null, producedItems, null, null, null, null, null, null, activityKey, null, false, null, false);
	}

	/** See SignalKind#STRUCTURED_ACTIVITY_ROUND_TIME. The milliseconds ride in the amount slot. */
	public static SessionSignal structuredActivityRoundTime(Instant observedAt, String activityKey, long roundMillis)
	{
		return structuredActivityRoundTime(observedAt, activityKey, roundMillis, false);
	}

	/** Same, plus whether the round was seen from start to end (rides in the trusted flag). */
	public static SessionSignal structuredActivityRoundTime(Instant observedAt, String activityKey, long roundMillis, boolean completeRound)
	{
		return structuredActivityRoundTime(observedAt, activityKey, roundMillis, completeRound, null);
	}

	/** Same, plus the round's effective playstyle label (History only; rides in the source slot). */
	public static SessionSignal structuredActivityRoundTime(Instant observedAt, String activityKey, long roundMillis, boolean completeRound,
		String playstyle)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_ROUND_TIME, observedAt, null, null, null, null, null, null, playstyle,
			null, null, null, null, null, null, null, null, activityKey, null, false, roundMillis, completeRound);
	}

	/** See SignalKind#STRUCTURED_ACTIVITY_ACTION. The action key rides in the source slot, the count in the amount slot. */
	public static SessionSignal structuredActivityAction(Instant observedAt, String activityKey, String action, long count)
	{
		return new SessionSignal(SignalKind.STRUCTURED_ACTIVITY_ACTION, observedAt, null, null, null, null, null, null, action,
			null, null, null, null, null, null, null, null, activityKey, null, false, count, false);
	}

	/** STRUCTURED_ACTIVITY_CURRENCY only: which currency (null = the activity's single currency). */
	public String getActivityCurrencyKey()
	{
		return kind == SignalKind.STRUCTURED_ACTIVITY_CURRENCY ? bossOrActivitySource : null;
	}

	/** STRUCTURED_ACTIVITY_ROUND_TIME only: the round's playstyle label (e.g. "Mass • Points"), or null. */
	public String getActivityRoundPlaystyle()
	{
		return kind == SignalKind.STRUCTURED_ACTIVITY_ROUND_TIME ? bossOrActivitySource : null;
	}

	/** STRUCTURED_ACTIVITY_ACTION only: which counted action (e.g. "roots_fed"). */
	public String getActivityActionKey()
	{
		return kind == SignalKind.STRUCTURED_ACTIVITY_ACTION ? bossOrActivitySource : null;
	}

	/** STRUCTURED_ACTIVITY_PRODUCTION only: the produced items (id, name, exact quantity). */
	public List<LootDrop> getProducedItems()
	{
		return kind == SignalKind.STRUCTURED_ACTIVITY_PRODUCTION ? lootDrops : null;
	}

	public Long getActivityAmount()
	{
		return activityAmount;
	}

	public boolean isActivityAmountTrusted()
	{
		return activityAmountTrusted;
	}

	public SignalKind getKind()
	{
		return kind;
	}

	public Instant getObservedAt()
	{
		return observedAt;
	}

	public Long getGameTick()
	{
		return gameTick;
	}

	public String getStructuredActivityKey()
	{
		return structuredActivityKey;
	}

	public Integer getRegionId()
	{
		return regionId;
	}

	public boolean isSlayerTaskCancelled()
	{
		return slayerTaskCancelled;
	}

	public String getSkill()
	{
		return skill;
	}

	public Long getXpDelta()
	{
		return xpDelta;
	}

	/**
	 * See this class's own xpWindowStart field javadoc. Null unless built via the 4-arg
	 * xpChange(..., windowStart) factory.
	 */
	public Instant getXpWindowStart()
	{
		return xpWindowStart;
	}

	/**
	 * See this class's own xpNewValue
	 * field javadoc. Null unless built via the 5-arg
	 * xpChange(..., windowStart, newXpValue) factory.
	 */
	public Long getXpNewValue()
	{
		return xpNewValue;
	}

	public String getSlayerTaskName()
	{
		return slayerTaskName;
	}

	public String getSlayerTaskLocation()
	{
		return slayerTaskLocation;
	}

	public Integer getSlayerUnitsConsumed()
	{
		return slayerUnitsConsumed;
	}

	public Integer getSlayerInitialAmount()
	{
		return slayerInitialAmount;
	}

	/**
	 * This ONE SLAYER_TASK_PROGRESS event's own authoritative currentRemaining,
	 * or null when this signal was built without one (the 4-arg
	 * slayerTaskProgress() factory, or any other signal kind). Never
	 * previousRemaining/initialAmount.
	 */
	public Integer getSlayerCurrentRemaining()
	{
		return slayerCurrentRemaining;
	}

	public String getBossOrActivitySource()
	{
		return bossOrActivitySource;
	}

	public Integer getReliableCount()
	{
		return reliableCount;
	}

	public List<LootDrop> getLootDrops()
	{
		return lootDrops;
	}

	/**
	 * Nullable -- see npcInteractionTarget()'s own javadoc. Null for every SignalKind
	 * other than NPC_INTERACTION_TARGET.
	 */
	public Integer getNpcId()
	{
		return npcId;
	}

	/** See getNpcId(). */
	public String getNpcName()
	{
		return npcName;
	}

	/**
	 * One item stack within a single atomic SERVER_NPC_LOOT
	 * notification -- item id/name/quantity, kept as its own
	 * value so a whole notification's drops stay grouped together
	 * rather than flattened into one global list (see
	 * SessionAggregates.LootDropGroup).
	 */
	public static final class LootDrop
	{
		private final Integer itemId;
		private final String itemName;
		private final long quantity;

		public LootDrop(Integer itemId, String itemName, long quantity)
		{
			this.itemId = itemId;
			this.itemName = Objects.requireNonNull(itemName, "itemName");
			this.quantity = quantity;
		}

		public Integer getItemId()
		{
			return itemId;
		}

		public String getItemName()
		{
			return itemName;
		}

		public long getQuantity()
		{
			return quantity;
		}
	}
}
