package com.osrstelemetry.plugin.farming;

/**
 * A planting Spyglass actually saw: a same-sequence tick-to-tick change from
 * an empty patch to stage 0 of a crop. A historical fact; it stays true even
 * after the crop it started is gone. Immutable (Gson-friendly).
 */
public final class PlantingEvent
{
	private final long at;
	private final FarmingProduce produce;
	private final int raw;

	PlantingEvent(long at, FarmingProduce produce, int raw)
	{
		this.at = at;
		this.produce = produce;
		this.raw = raw;
	}

	public long getAt()
	{
		return at;
	}

	public FarmingProduce getProduce()
	{
		return produce;
	}

	public int getRaw()
	{
		return raw;
	}
}
