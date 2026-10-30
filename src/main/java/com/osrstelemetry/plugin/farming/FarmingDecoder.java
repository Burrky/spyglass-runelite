package com.osrstelemetry.plugin.farming;

/**
 * Raw patch varbit value -> {@link PatchReading}, for every supported patch type ({@link FarmingPatchType}).
 * Pure and stateless. Rows come from {@link FarmingDecoderTable} (derived from
 * RuneLite's PatchImplementation, see THIRD_PARTY_NOTICES.md).
 *
 * {@link #VERSION} changes whenever any row's meaning changes. Persisted
 * observations keep the raw value and the version they were decoded with, so
 * a corrected decoder can re-derive them.
 */
public final class FarmingDecoder
{
	/** Bump whenever a decoded meaning changes. */
	public static final int VERSION = 1;

	private FarmingDecoder()
	{
	}

	public static PatchReading decode(FarmingPatchType type, int raw)
	{
		for (FarmingDecoderTable.Row row : rows(type))
		{
			if (raw >= row.lo && raw <= row.hi)
			{
				int stage = stage(row, raw);
				PatchCondition condition = row.condition;
				if (condition == PatchCondition.WEEDS && stage == 0)
				{
					condition = PatchCondition.EMPTY;
				}
				return new PatchReading(type, raw, VERSION, condition, row.produce, stage, row.subState);
			}
		}
		return new PatchReading(type, raw, VERSION, PatchCondition.UNKNOWN, null, -1, PatchSubState.NONE);
	}

	private static FarmingDecoderTable.Row[] rows(FarmingPatchType type)
	{
		return FarmingDecoderTable.rows(type);
	}

	private static int stage(FarmingDecoderTable.Row row, int raw)
	{
		switch (row.stageMode)
		{
			case FarmingDecoderTable.OFFSET:
				return raw - row.stageParam;
			case FarmingDecoderTable.REVERSE:
				return row.stageParam - raw;
			case FarmingDecoderTable.CONST:
				return row.stageParam;
			case FarmingDecoderTable.LAST:
				return row.produce == null ? -1 : row.produce.getStages() - 1;
			default:
				return -1;
		}
	}
}
