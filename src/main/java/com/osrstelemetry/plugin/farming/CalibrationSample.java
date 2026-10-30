package com.osrstelemetry.plugin.farming;

/**
 * Evidence of an exact farm growth tick: a same-sequence, tick-to-tick change
 * that only the server's growth tick can cause (RuneLite FarmingTracker's
 * isObservedGrowthTick rules, adapted). The growth tick happened in
 * ({@link #getPreviousObservedAt()}, {@link #getObservedAt()}]. Projection
 * and offset maths are later work; this only preserves the evidence.
 */
public final class CalibrationSample
{
	private final String patchId;
	private final FarmingProduce produce;
	/** The growth-tick length that applied to the previous state (RuneLite: previous state's tick rate). */
	private final int tickMinutes;
	private final int fromRaw;
	private final int toRaw;
	private final PatchCondition fromCondition;
	private final PatchCondition toCondition;
	private final int fromStage;
	private final int toStage;
	private final long previousObservedAt;
	private final long observedAt;
	private final long sequence;
	private final int decoderVersion;

	CalibrationSample(String patchId, FarmingProduce produce, int tickMinutes, PatchReading from, PatchReading to, long previousObservedAt, long observedAt, long sequence)
	{
		this.patchId = patchId;
		this.produce = produce;
		this.tickMinutes = tickMinutes;
		this.fromRaw = from.getRaw();
		this.toRaw = to.getRaw();
		this.fromCondition = from.getCondition();
		this.toCondition = to.getCondition();
		this.fromStage = from.getStage();
		this.toStage = to.getStage();
		this.previousObservedAt = previousObservedAt;
		this.observedAt = observedAt;
		this.sequence = sequence;
		this.decoderVersion = to.getDecoderVersion();
	}

	public String getPatchId()
	{
		return patchId;
	}

	public FarmingProduce getProduce()
	{
		return produce;
	}

	public int getTickMinutes()
	{
		return tickMinutes;
	}

	public int getFromRaw()
	{
		return fromRaw;
	}

	public int getToRaw()
	{
		return toRaw;
	}

	public PatchCondition getFromCondition()
	{
		return fromCondition;
	}

	public PatchCondition getToCondition()
	{
		return toCondition;
	}

	public int getFromStage()
	{
		return fromStage;
	}

	public int getToStage()
	{
		return toStage;
	}

	public long getPreviousObservedAt()
	{
		return previousObservedAt;
	}

	public long getObservedAt()
	{
		return observedAt;
	}

	public long getSequence()
	{
		return sequence;
	}

	public int getDecoderVersion()
	{
		return decoderVersion;
	}

	/**
	 * Whether a same-sequence tick-to-tick change is a growth tick. Adapted from
	 * RuneLite's FarmingTracker.isObservedGrowthTick: weeds and produce changes
	 * never count; only a GROWING previous state has a growth tick rate (so
	 * diseased-to-dead, a 0-rate state in RuneLite, is excluded); and growing
	 * to harvestable counts only for produce without a health check. Spyglass's
	 * separate CHECK_HEALTH condition is RuneLite's "growing at the last stage",
	 * so growing to check-health at stage + 1 counts, and check-health to
	 * harvestable (a player action) does not.
	 */
	static boolean isGrowthTick(PatchReading from, PatchReading to)
	{
		FarmingProduce produce = from.getProduce();
		if (produce == null || produce == FarmingProduce.WEEDS || to.getProduce() != produce || produce.getTickMinutes() <= 0)
		{
			return false;
		}
		if (from.getCondition() != PatchCondition.GROWING)
		{
			return false;
		}
		switch (to.getCondition())
		{
			case GROWING:
			case CHECK_HEALTH:
				return to.getStage() - from.getStage() == 1;
			case DISEASED:
				return true;
			case HARVESTABLE:
				return !produce.requiresHealthCheck();
			default:
				return false;
		}
	}
}
