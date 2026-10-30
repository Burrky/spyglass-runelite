package com.osrstelemetry.plugin.farming;

import java.util.Map;

/**
 * Builds observed patch knowledge through the real attribution path, for tests
 * outside this package (UI). Nothing here bypasses {@link CropCycleAttribution}.
 */
public final class FarmingKnowledgeFixtures
{
	private FarmingKnowledgeFixtures()
	{
	}

	/** The first raw value the decoder reads as {@code produce} in {@code condition} (stage {@code stage}, or any when -1). */
	public static int raw(FarmingPatchType type, FarmingProduce produce, PatchCondition condition, int stage)
	{
		for (int raw = 0; raw < 256; raw++)
		{
			PatchReading r = FarmingDecoder.decode(type, raw);
			if (r.getProduce() == produce && r.getCondition() == condition && (stage < 0 || r.getStage() == stage))
			{
				return raw;
			}
		}
		throw new IllegalArgumentException(type + " " + produce + " " + condition + " " + stage);
	}

	/** A baseline reading (first sight) of {@code raw} at {@code at}. */
	public static void see(Map<String, PatchKnowledge> known, String patchId, int raw, long at)
	{
		FarmingPatchDef p = FarmingFixtures.patch(patchId);
		known.put(patchId, CropCycleAttribution.apply(known.get(patchId),
			new PatchObservation(PatchObservation.Kind.BASELINE, p, FarmingDecoder.decode(p.getType(), raw), null, at, 0L, 1)));
	}

	/** An observed change from whatever is known to {@code raw} at {@code at}. */
	public static void change(Map<String, PatchKnowledge> known, String patchId, int fromRaw, int raw, long at)
	{
		FarmingPatchDef p = FarmingFixtures.patch(patchId);
		known.put(patchId, CropCycleAttribution.apply(known.get(patchId), new PatchObservation(PatchObservation.Kind.CHANGE, p,
			FarmingDecoder.decode(p.getType(), raw), FarmingDecoder.decode(p.getType(), fromRaw), at, at - 600, 2)));
	}

	/** A compost bin as the game showed it (raw varbit value), first seen at {@code since}, last at {@code at}. */
	public static CompostBin.Seen bin(int raw, long since, long at)
	{
		return new CompostBin.Seen(raw, since, at);
	}
}
