package com.osrstelemetry.plugin.farming;

/**
 * A crop cycle that a native observation ended (the patch was seen cleared:
 * empty or weeds), kept as history. Immutable (Gson-friendly).
 */
public final class ClosedCropCycle
{
	private final CropCycle cycle;
	private final long closedAt;
	private final PatchCondition closedBy;

	ClosedCropCycle(CropCycle cycle, long closedAt, PatchCondition closedBy)
	{
		this.cycle = cycle;
		this.closedAt = closedAt;
		this.closedBy = closedBy;
	}

	public CropCycle getCycle()
	{
		return cycle;
	}

	public long getClosedAt()
	{
		return closedAt;
	}

	public PatchCondition getClosedBy()
	{
		return closedBy;
	}
}
