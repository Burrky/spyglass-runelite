package com.osrstelemetry.plugin.farming;

/**
 * The crop currently attributed to a patch, retained across time away,
 * logouts and new observation sequences until a native observation shows it
 * is gone or replaced. Immutable (Gson-friendly).
 *
 * <ul>
 *   <li>{@code plantedAt}: only for {@link Provenance#OBSERVED_PLANTING}.</li>
 *   <li>Stage evidence for the current {@code lastCondition} / {@code lastStage}:
 *   {@code stageFirstSeenAt} is when Spyglass first saw it, and
 *   {@code stageTransitionObserved} says whether the change INTO it was
 *   witnessed (a CHANGE between two consecutive eligible ticks of one
 *   observation sequence). Only then is {@code stageFirstSeenAt} the time of the
 *   transition ("stage transition observed"). Otherwise the stage was met after
 *   a gap and the transition happened at some unknown earlier time ("stage
 *   first seen at"): {@code stageFirstSeenAt} is only a time it was known to be
 *   present. Seeing the same stage again never moves either field.</li>
 *   <li>Growth-timer basis: {@code plantedAt} when planting was observed;
 *   otherwise the stage evidence above, described honestly. A later projection
 *   (Phase D) may estimate readiness from it; that estimate is never written
 *   back here.</li>
 *   <li>{@code lastCondition} is always a NATIVE observation. HARVESTABLE here
 *   means readiness was actually seen; a timer-derived READY is computed at
 *   presentation time and keeps its separate source. DISEASED and DEAD here
 *   were observed and outrank any estimate: only a later native observation
 *   (a cure, or a new cycle) replaces them, never elapsed time.</li>
 * </ul>
 */
public final class CropCycle
{
	public enum Provenance
	{
		OBSERVED_PLANTING,
		FIRST_SEEN
	}

	private final FarmingProduce produce;
	private final Provenance provenance;
	private final Long plantedAt;
	private final long firstSeenAt;
	private final long lastSeenAt;
	private final PatchCondition lastCondition;
	private final int lastStage;
	private final int lastRaw;
	/** When {@code lastCondition}/{@code lastStage} was first seen. */
	private final long stageFirstSeenAt;
	/** True only when the change into the current stage was witnessed tick-to-tick. */
	private final boolean stageTransitionObserved;

	CropCycle(FarmingProduce produce, Provenance provenance, Long plantedAt, long firstSeenAt, long lastSeenAt,
		PatchCondition lastCondition, int lastStage, int lastRaw, long stageFirstSeenAt, boolean stageTransitionObserved)
	{
		this.produce = produce;
		this.provenance = provenance;
		this.plantedAt = provenance == Provenance.OBSERVED_PLANTING ? plantedAt : null;
		this.firstSeenAt = firstSeenAt;
		this.lastSeenAt = lastSeenAt;
		this.lastCondition = lastCondition;
		this.lastStage = lastStage;
		this.lastRaw = lastRaw;
		this.stageFirstSeenAt = stageFirstSeenAt;
		this.stageTransitionObserved = stageTransitionObserved;
	}

	/** {@code transitionObserved}: the observation was a same-sequence tick-to-tick CHANGE. */
	static CropCycle started(FarmingProduce produce, Provenance provenance, PatchReading reading, long at, boolean transitionObserved)
	{
		return new CropCycle(produce, provenance, at, at, at, reading.getCondition(), reading.getStage(), reading.getRaw(), at, transitionObserved);
	}

	public FarmingProduce getProduce()
	{
		return produce;
	}

	public Provenance getProvenance()
	{
		return provenance;
	}

	public Long getPlantedAt()
	{
		return plantedAt;
	}

	public long getFirstSeenAt()
	{
		return firstSeenAt;
	}

	public long getLastSeenAt()
	{
		return lastSeenAt;
	}

	public PatchCondition getLastCondition()
	{
		return lastCondition;
	}

	public int getLastStage()
	{
		return lastStage;
	}

	public int getLastRaw()
	{
		return lastRaw;
	}

	/** When the current stage was first seen: the transition time only if {@link #isStageTransitionObserved()}. */
	public long getStageFirstSeenAt()
	{
		return stageFirstSeenAt;
	}

	/** The change into the current stage was witnessed tick-to-tick, so {@link #getStageFirstSeenAt()} is when it happened. */
	public boolean isStageTransitionObserved()
	{
		return stageTransitionObserved;
	}

	/** Readiness was natively observed (harvestable), not estimated. */
	public boolean isObservedHarvestable()
	{
		return lastCondition == PatchCondition.HARVESTABLE;
	}

	/**
	 * A new native observation of this same cycle. A different stage takes
	 * {@code at} as its first-seen time, marked as an observed transition only
	 * when {@code transitionObserved}; the same stage keeps its evidence.
	 */
	CropCycle seen(PatchReading reading, long at, boolean transitionObserved)
	{
		boolean sameStage = reading.getCondition() == lastCondition && reading.getStage() == lastStage;
		return new CropCycle(produce, provenance, plantedAt, firstSeenAt, Math.max(lastSeenAt, at), reading.getCondition(),
			reading.getStage(), reading.getRaw(), sameStage ? stageFirstSeenAt : at, sameStage ? stageTransitionObserved : transitionObserved);
	}

	/** The same raw value read by a newer decoder: condition and stage corrected, times and evidence kept. */
	CropCycle redecoded(PatchReading reading)
	{
		return new CropCycle(produce, provenance, plantedAt, firstSeenAt, lastSeenAt, reading.getCondition(), reading.getStage(),
			reading.getRaw(), stageFirstSeenAt, stageTransitionObserved);
	}

	/** An unchanged native read: freshness only. */
	CropCycle touched(long at)
	{
		return new CropCycle(produce, provenance, plantedAt, firstSeenAt, Math.max(lastSeenAt, at), lastCondition, lastStage, lastRaw, stageFirstSeenAt,
			stageTransitionObserved);
	}
}
