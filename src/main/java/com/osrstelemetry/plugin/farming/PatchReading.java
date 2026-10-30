package com.osrstelemetry.plugin.farming;

import java.util.Objects;

/**
 * One raw patch value and what the decoder of a given version made of it.
 * Immutable. The raw value is the record; the decoded fields can always be
 * re-derived from it with a newer decoder.
 */
public final class PatchReading
{
	private final FarmingPatchType patchType;
	private final int raw;
	private final int decoderVersion;
	private final PatchCondition condition;
	/** Null for empty/weeds, for a dead herb (produce unknown) and for UNKNOWN. */
	private final FarmingProduce produce;
	/** Growth stage, harvest lives left, weed level or disease/dead stage, as RuneLite numbers it; -1 when unknown. */
	private final int stage;
	private final PatchSubState subState;

	PatchReading(FarmingPatchType patchType, int raw, int decoderVersion, PatchCondition condition, FarmingProduce produce, int stage, PatchSubState subState)
	{
		this.patchType = patchType;
		this.raw = raw;
		this.decoderVersion = decoderVersion;
		this.condition = condition;
		this.produce = produce;
		this.stage = stage;
		this.subState = subState;
	}

	public FarmingPatchType getPatchType()
	{
		return patchType;
	}

	public int getRaw()
	{
		return raw;
	}

	public int getDecoderVersion()
	{
		return decoderVersion;
	}

	public PatchCondition getCondition()
	{
		return condition;
	}

	public FarmingProduce getProduce()
	{
		return produce;
	}

	public int getStage()
	{
		return stage;
	}

	public PatchSubState getSubState()
	{
		return subState;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof PatchReading))
		{
			return false;
		}
		PatchReading r = (PatchReading) o;
		return raw == r.raw && decoderVersion == r.decoderVersion && stage == r.stage && patchType == r.patchType
			&& condition == r.condition && produce == r.produce && subState == r.subState;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(patchType, raw, decoderVersion, condition, produce, stage, subState);
	}

	@Override
	public String toString()
	{
		return patchType + ":" + raw + "=" + condition + (produce == null ? "" : "/" + produce) + "@" + stage
			+ (subState == PatchSubState.NONE ? "" : "/" + subState) + " (v" + decoderVersion + ")";
	}
}
