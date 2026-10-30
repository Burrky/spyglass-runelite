package com.osrstelemetry.plugin.farming;

/**
 * Everything persisted about one patch for one owner. Immutable (Gson-friendly).
 *
 * <ul>
 *   <li>{@code lastRaw} / {@code decoderVersion} / {@code lastObservedAt}: the
 *   most recent BASELINE or CHANGE (the raw value is the record).</li>
 *   <li>{@code lastSeenAt}: freshness, also advanced by unchanged settled
 *   reads, which are never logged.</li>
 *   <li>{@code cycle}: the crop currently attributed to the patch, or null
 *   when none is known. Retained across time away and new sequences; ended
 *   only by a native observation (cleared, or a different / regressed crop).
 *   A dead or diseased crop keeps its cycle; an UNKNOWN reading erases
 *   nothing.</li>
 *   <li>{@code lastClosedCycle}: the most recent cycle a native observation
 *   closed (seen cleared), kept as history.</li>
 *   <li>{@code lastObservedPlanting}: the latest planting actually seen,
 *   kept as history even when it can no longer be attributed to the crop now
 *   present.</li>
 *   <li>{@code decodeStale}: a newer decoder could not decode the stored raw
 *   value; projections must treat this patch as stale.</li>
 * </ul>
 */
public final class PatchKnowledge
{
	private final String patchId;
	private final int region;
	private final int varbit;
	private final int lastRaw;
	private final int decoderVersion;
	private final long lastObservedAt;
	private final long lastSeenAt;
	private final CropCycle cycle;
	private final PlantingEvent lastObservedPlanting;
	private final ClosedCropCycle lastClosedCycle;
	private final boolean decodeStale;

	PatchKnowledge(String patchId, int region, int varbit, int lastRaw, int decoderVersion, long lastObservedAt, long lastSeenAt,
		CropCycle cycle, PlantingEvent lastObservedPlanting, ClosedCropCycle lastClosedCycle, boolean decodeStale)
	{
		this.patchId = patchId;
		this.region = region;
		this.varbit = varbit;
		this.lastRaw = lastRaw;
		this.decoderVersion = decoderVersion;
		this.lastObservedAt = lastObservedAt;
		this.lastSeenAt = lastSeenAt;
		this.cycle = cycle;
		this.lastObservedPlanting = lastObservedPlanting;
		this.lastClosedCycle = lastClosedCycle;
		this.decodeStale = decodeStale;
	}

	public String getPatchId()
	{
		return patchId;
	}

	public int getRegion()
	{
		return region;
	}

	public int getVarbit()
	{
		return varbit;
	}

	public int getLastRaw()
	{
		return lastRaw;
	}

	public int getDecoderVersion()
	{
		return decoderVersion;
	}

	public long getLastObservedAt()
	{
		return lastObservedAt;
	}

	public long getLastSeenAt()
	{
		return lastSeenAt;
	}

	public CropCycle getCycle()
	{
		return cycle;
	}

	public PlantingEvent getLastObservedPlanting()
	{
		return lastObservedPlanting;
	}

	public ClosedCropCycle getLastClosedCycle()
	{
		return lastClosedCycle;
	}

	public boolean isDecodeStale()
	{
		return decodeStale;
	}

	PatchKnowledge seenUnchanged(long at)
	{
		return new PatchKnowledge(patchId, region, varbit, lastRaw, decoderVersion, lastObservedAt, Math.max(lastSeenAt, at),
			cycle == null ? null : cycle.touched(at), lastObservedPlanting, lastClosedCycle, decodeStale);
	}

	PatchKnowledge withDecoder(int version, CropCycle newCycle, boolean stale)
	{
		return new PatchKnowledge(patchId, region, varbit, lastRaw, version, lastObservedAt, lastSeenAt, newCycle, lastObservedPlanting, lastClosedCycle, stale);
	}
}
