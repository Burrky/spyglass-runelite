package com.osrstelemetry.plugin.farming;

/**
 * One catalogued patch. Its identity is the stable {@link #getPatchId()},
 * backed by the owning region and the transmit varbit: the same varbit means a
 * different patch in every farming area, so the varbit alone identifies nothing.
 */
public final class FarmingPatchDef
{
	private final String patchId;
	private final String areaName;
	private final String label;
	private final FarmingPatchType type;
	private final int owningRegionId;
	private final int varbit;

	FarmingPatchDef(String patchId, String areaName, String label, FarmingPatchType type, int owningRegionId, int varbit)
	{
		this.patchId = patchId;
		this.areaName = areaName;
		this.label = label;
		this.type = type;
		this.owningRegionId = owningRegionId;
		this.varbit = varbit;
	}

	public String getPatchId()
	{
		return patchId;
	}

	public String getAreaName()
	{
		return areaName;
	}

	/** "North West", "South" ... or empty when the area has one patch of this type. */
	public String getLabel()
	{
		return label;
	}

	public FarmingPatchType getType()
	{
		return type;
	}

	public int getOwningRegionId()
	{
		return owningRegionId;
	}

	public int getVarbit()
	{
		return varbit;
	}

	@Override
	public String toString()
	{
		return patchId;
	}
}
