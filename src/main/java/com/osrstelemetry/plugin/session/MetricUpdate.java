package com.osrstelemetry.plugin.session;

import java.time.Instant;
import java.util.List;

/**
 * A pure description of an
 * aggregate change -- distinguishing the authoritative-value vs.
 * session-occurrences reliable-count split, and, for the
 * slayer-progress kind, optionally carrying the originating event's
 * own authoritative currentRemaining -- kept entirely separate from SignalDecisionKind's
 * lifecycle decision so a whole game-tick batch's metrics can all be
 * applied together regardless of which single classification (if any)
 * governed the lifecycle transition for that tick (see
 * SessionSignalBatchResolver).
 *
 * Exactly one of the four kinds of update is populated per instance
 * (see the static factories) -- never a general-purpose bag of every
 * field at once. slayerCurrentRemaining rides ALONGSIDE
 * slayerProgressValue as part of the SAME slayer-progress kind (both
 * originate from the same SLAYER_TASK_PROGRESS event) rather than being
 * a fifth, separate kind.
 */
final class MetricUpdate
{
	private final String xpSkill;
	private final Long xpDelta;

	private final String lootSourceName;
	private final String lootObservedAt;
	private final List<SessionSignal.LootDrop> lootDrops;

	private final SessionAggregates.ReliableCountKind reliableCountKind;
	private final String reliableCountSourceKey;
	private final Integer reliableCountAuthoritativeValue;

	private final Integer slayerProgressValue;
	private final Integer slayerCurrentRemaining;

	/**
	 * SMART SLAYER: when this metric's underlying game event happened (an
	 * XP window's own START, or the observing signal's time), used only to
	 * credit it to the right Slayer assignment record -- e.g. a final
	 * kill's XP whose window began before the task completed. Null =
	 * unknown (credited to the current, unfinished assignment only).
	 * Never affects session-wide aggregation.
	 */
	/** One structured-activity completion (activity key) and the game's running count, if known. */
	private String activityCompletionKey;
	private Integer activityCompletionAuthoritativeValue;

	/** STRUCTURED ACTIVITY STATS (see SessionAggregates.ActivitySessionStats); key non-null = a stats update. */
	private String activityStatsKey;
	private Long currencyGained;
	/** Which currency currencyGained is for (null = the activity's single currency). */
	private String currencyKey;
	/** STRUCTURED_ACTIVITY_ROUND_TIME: one round's in-area milliseconds. */
	private Long roundMillis;
	/** STRUCTURED_ACTIVITY_ROUND_TIME: the round was seen from start to end. */
	private boolean completeRound;
	/** STRUCTURED_ACTIVITY_ROUND_TIME: the round's playstyle label (History), or null. */
	private String roundPlaystyle;
	/** STRUCTURED_ACTIVITY_ACTION: which action, and how many. */
	private String actionKey;
	private Long actionCount;
	/** Produced items (output, never loot) for the activity's own session. */
	private List<SessionSignal.LootDrop> producedItems;
	private Integer score;
	private Integer scoreCompletionCount;
	private boolean scoreTrusted;
	private boolean scoreUpdate;

	private Instant referenceTime;

	/** SLAYER_BRACELET_EFFECT: "SLAUGHTER" / "EXPEDITIOUS" -- one proc for the assignment current at referenceTime. */
	private String slayerBraceletEffect;
	/** SLAYER_ENCOUNTER_KILL: one actual kill of this exact NPC, and its role, for the assignment current at referenceTime. */
	private String encounterKillNpc;
	private String encounterKillRole;

	/** SKILL_PRODUCTION: the companion update, applied only to the SKILLING session of the same skill key. */
	private SkillProductionUpdate skillProduction;

	private MetricUpdate(
		String xpSkill,
		Long xpDelta,
		String lootSourceName,
		String lootObservedAt,
		List<SessionSignal.LootDrop> lootDrops,
		SessionAggregates.ReliableCountKind reliableCountKind,
		String reliableCountSourceKey,
		Integer reliableCountAuthoritativeValue,
		Integer slayerProgressValue,
		Integer slayerCurrentRemaining)
	{
		this.xpSkill = xpSkill;
		this.xpDelta = xpDelta;
		this.lootSourceName = lootSourceName;
		this.lootObservedAt = lootObservedAt;
		this.lootDrops = lootDrops;
		this.reliableCountKind = reliableCountKind;
		this.reliableCountSourceKey = reliableCountSourceKey;
		this.reliableCountAuthoritativeValue = reliableCountAuthoritativeValue;
		this.slayerProgressValue = slayerProgressValue;
		this.slayerCurrentRemaining = slayerCurrentRemaining;
	}

	/** This same metric with its game-event reference time set (see referenceTime). */
	MetricUpdate withReferenceTime(Instant referenceTime)
	{
		MetricUpdate copy = new MetricUpdate(xpSkill, xpDelta, lootSourceName, lootObservedAt, lootDrops,
			reliableCountKind, reliableCountSourceKey, reliableCountAuthoritativeValue, slayerProgressValue, slayerCurrentRemaining);
		copy.referenceTime = referenceTime;
		copy.activityCompletionKey = activityCompletionKey;
		copy.activityCompletionAuthoritativeValue = activityCompletionAuthoritativeValue;
		copy.activityStatsKey = activityStatsKey;
		copy.currencyGained = currencyGained;
		copy.currencyKey = currencyKey;
		copy.roundMillis = roundMillis;
		copy.completeRound = completeRound;
		copy.roundPlaystyle = roundPlaystyle;
		copy.actionKey = actionKey;
		copy.actionCount = actionCount;
		copy.producedItems = producedItems;
		copy.score = score;
		copy.scoreCompletionCount = scoreCompletionCount;
		copy.scoreTrusted = scoreTrusted;
		copy.scoreUpdate = scoreUpdate;
		copy.slayerBraceletEffect = slayerBraceletEffect;
		copy.encounterKillNpc = encounterKillNpc;
		copy.encounterKillRole = encounterKillRole;
		copy.skillProduction = skillProduction;
		return copy;
	}

	Instant getReferenceTime()
	{
		return referenceTime;
	}

	static MetricUpdate xp(String skill, long delta)
	{
		return new MetricUpdate(skill, delta, null, null, null, null, null, null, null, null);
	}

	static MetricUpdate loot(String sourceName, Instant observedAt, List<SessionSignal.LootDrop> drops)
	{
		return new MetricUpdate(null, null, sourceName, observedAt.toString(), drops, null, null, null, null, null);
	}

	/**
	 * `authoritativeValue` is the raw absolute account-wide
	 * number a game message reported (e.g. account KC) -- NOT a
	 * session-scoped kill count. SessionAggregateUpdater is solely
	 * responsible for deriving how many of those are attributable to
	 * the current session.
	 */
	static MetricUpdate reliableCount(SessionAggregates.ReliableCountKind kind, String sourceKey, int authoritativeValue)
	{
		return new MetricUpdate(null, null, null, null, null, kind, sourceKey, authoritativeValue, null, null);
	}

	/**
	 * `unitsConsumed` is this ONE observed Slayer progress
	 * event's own taskUnitsConsumed (see
	 * SessionSignal.slayerTaskProgress()'s javadoc) -- the observed
	 * decrease in the assignment's authoritative remaining-count value
	 * for this one event, NOT a physical NPC-kill count. It is not
	 * generally 1: game mechanics can make a single physical kill
	 * consume more than one task unit (e.g. an expeditious-bracelet-
	 * style proc) or zero (e.g. a bracelet-of-slaughter-style save),
	 * independent of any missed-observation reconciliation. Deliberately
	 * never an absolute task-state number
	 * (currentRemaining/previousRemaining/initialAmount) and never
	 * converted into a generic "kill" -- see SessionAggregateUpdater.apply(),
	 * which is solely responsible for accumulating these per-event values
	 * into SessionAggregates.slayerProgressDelta (a TASK-UNIT total,
	 * never a kill count) across the whole session.
	 *
	 * `currentRemaining` is this SAME event's own authoritative
	 * currentRemaining, nullable, threaded through unmodified so
	 * SessionAggregateUpdater can retain the LATEST observed value
	 * (never accumulated/summed, unlike unitsConsumed) for the
	 * player-facing display -- see SessionAggregates.latestSlayerCurrentRemaining's
	 * own javadoc.
	 */
	static MetricUpdate activityCompletion(String activityKey, Integer authoritativeValue)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.activityCompletionKey = activityKey;
		m.activityCompletionAuthoritativeValue = authoritativeValue;
		return m;
	}

	static MetricUpdate activityCurrency(String activityKey, long gained)
	{
		return activityCurrency(activityKey, null, gained);
	}

	static MetricUpdate activityCurrency(String activityKey, String currencyKey, long gained)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.activityStatsKey = activityKey;
		m.currencyGained = gained;
		m.currencyKey = currencyKey;
		return m;
	}

	/** One round's in-area time for the activity's own session (routed by activityStatsKey). */
	static MetricUpdate activityRoundTime(String activityKey, long roundMillis)
	{
		return activityRoundTime(activityKey, roundMillis, false);
	}

	static MetricUpdate activityRoundTime(String activityKey, long roundMillis, boolean completeRound)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.activityStatsKey = activityKey;
		m.roundMillis = roundMillis;
		m.completeRound = completeRound;
		return m;
	}

	static MetricUpdate activityRoundTime(String activityKey, long roundMillis, boolean completeRound, String playstyle)
	{
		MetricUpdate m = activityRoundTime(activityKey, roundMillis, completeRound);
		m.roundPlaystyle = playstyle;
		return m;
	}

	String getRoundPlaystyle()
	{
		return roundPlaystyle;
	}

	/** One round's total of one counted action for the activity's own session. */
	static MetricUpdate activityAction(String activityKey, String action, long count)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.activityStatsKey = activityKey;
		m.actionKey = action;
		m.actionCount = count;
		return m;
	}

	boolean isCompleteRound()
	{
		return completeRound;
	}

	String getActionKey()
	{
		return actionKey;
	}

	Long getActionCount()
	{
		return actionCount;
	}

	Long getRoundMillis()
	{
		return roundMillis;
	}

	/** Produced items for the activity's own session (routed by activityStatsKey, like the other session stats). */
	/** A skill companion update for the SKILLING session of {@code update.getSkillKey()} (routed by that key). */
	static MetricUpdate skillProduction(SkillProductionUpdate update)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.skillProduction = update;
		return m;
	}

	SkillProductionUpdate getSkillProduction()
	{
		return skillProduction;
	}

	/** The SKILLING session key a skill companion update belongs to, or null. */
	String getSkillKey()
	{
		return skillProduction == null ? null : skillProduction.getSkillKey();
	}

	static MetricUpdate activityProduction(String activityKey, List<SessionSignal.LootDrop> items)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.activityStatsKey = activityKey;
		m.producedItems = items;
		return m;
	}

	String getCurrencyKey()
	{
		return currencyKey;
	}

	List<SessionSignal.LootDrop> getProducedItems()
	{
		return producedItems;
	}

	/** One completed game's final score; untrusted / unknown scores still consume the completion count (dedupe). */
	static MetricUpdate activityScore(String activityKey, Integer completionCount, Integer score, boolean trusted)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.activityStatsKey = activityKey;
		m.scoreUpdate = true;
		m.scoreCompletionCount = completionCount;
		m.score = score;
		m.scoreTrusted = trusted;
		return m;
	}

	String getActivityStatsKey()
	{
		return activityStatsKey;
	}

	Long getCurrencyGained()
	{
		return currencyGained;
	}

	boolean isScoreUpdate()
	{
		return scoreUpdate;
	}

	Integer getScore()
	{
		return score;
	}

	Integer getScoreCompletionCount()
	{
		return scoreCompletionCount;
	}

	boolean isScoreTrusted()
	{
		return scoreTrusted;
	}

	String getActivityCompletionKey()
	{
		return activityCompletionKey;
	}

	Integer getActivityCompletionAuthoritativeValue()
	{
		return activityCompletionAuthoritativeValue;
	}

	static MetricUpdate slayerBracelet(String effect)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.slayerBraceletEffect = effect;
		return m;
	}

	String getSlayerBraceletEffect()
	{
		return slayerBraceletEffect;
	}

	static MetricUpdate slayerEncounterKill(String npcName, String role)
	{
		MetricUpdate m = new MetricUpdate(null, null, null, null, null, null, null, null, null, null);
		m.encounterKillNpc = npcName;
		m.encounterKillRole = role;
		return m;
	}

	String getEncounterKillNpc()
	{
		return encounterKillNpc;
	}

	String getEncounterKillRole()
	{
		return encounterKillRole;
	}

	static MetricUpdate slayerProgress(int unitsConsumed, Integer currentRemaining)
	{
		return new MetricUpdate(null, null, null, null, null, null, null, null, unitsConsumed, currentRemaining);
	}

	String getXpSkill()
	{
		return xpSkill;
	}

	Long getXpDelta()
	{
		return xpDelta;
	}

	String getLootSourceName()
	{
		return lootSourceName;
	}

	String getLootObservedAt()
	{
		return lootObservedAt;
	}

	List<SessionSignal.LootDrop> getLootDrops()
	{
		return lootDrops;
	}

	SessionAggregates.ReliableCountKind getReliableCountKind()
	{
		return reliableCountKind;
	}

	String getReliableCountSourceKey()
	{
		return reliableCountSourceKey;
	}

	Integer getReliableCountAuthoritativeValue()
	{
		return reliableCountAuthoritativeValue;
	}

	Integer getSlayerProgressValue()
	{
		return slayerProgressValue;
	}

	/** This event's own authoritative currentRemaining, or null. */
	Integer getSlayerCurrentRemaining()
	{
		return slayerCurrentRemaining;
	}
}
