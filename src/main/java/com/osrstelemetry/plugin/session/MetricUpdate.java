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
