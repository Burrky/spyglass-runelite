package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's light-touch planning choices in the preview (canonical §3.3):
 * include / exclude per patch, what to plant per patch (or per run type),
 * and a few run options per run type. Session-only and in memory: never persisted, never
 * part of observed knowledge, and it creates no run. Choices survive switching
 * previews; {@link #clear()} drops them (an account change).
 *
 * Only explicit choices are stored; everything else follows the defaults,
 * which are recomputed from the current status each time.
 */
public final class FarmingPlanDraft
{
	/** A growing patch is included by default only when it will be ready this soon. */
	public static final long INCLUDE_IF_READY_WITHIN_MILLIS = 15 * 60_000L;

	public enum Compost
	{
		NONE("No compost"),
		COMPOST("Compost"),
		SUPERCOMPOST("Supercompost"),
		ULTRACOMPOST("Ultracompost"),
		BOTTOMLESS("Bottomless bucket"),
		/** The Lunar spell (83 Magic, Lunar Diplomacy): supercompost, or ultracompost once the ash covered tome is read. */
		FERTILE_SOIL("Fertile Soil");

		private final String label;

		Compost(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	/** "Harvest only": a valid planned crop meaning nothing is replanted. */
	public static final FarmingProduce HARVEST_ONLY = null;

	private final Map<String, Boolean> included = new HashMap<>();
	private final Map<String, String> plans = new HashMap<>();
	private final Map<FarmingRunType, String> typePlans = new EnumMap<>(FarmingRunType.class);
	private boolean useBounty;
	private final Map<FarmingRunType, Compost> compost = new EnumMap<>(FarmingRunType.class);
	private final Map<FarmingRunType, Boolean> protection = new EnumMap<>(FarmingRunType.class);

	/** Default include rule: ready, clear, dead or unknown patches are worth visiting; well-growing ones are not. */
	public static boolean defaultIncluded(FarmingModel.Row row, long now)
	{
		PatchStatus s = row.getStatus();
		switch (s.getDisplay())
		{
			case GROWING_TIMED:
				return s.getReadyAt() - now <= INCLUDE_IF_READY_WITHIN_MILLIS;
			case GROWING:
			case GROWN:
				return false;
			default:
				return true;
		}
	}

	public boolean isIncluded(FarmingModel.Row row, long now)
	{
		FarmingPatchType type = row.getPatch().getType();
		if (type == FarmingPatchType.ALLOTMENT && !includeAllotments || type == FarmingPatchType.FLOWER && !includeFlowers)
		{
			return false; // that half of "Allotments & Flowers" is switched off
		}
		Boolean choice = included.get(row.getPatchId());
		return choice != null ? choice : defaultIncluded(row, now);
	}

	private boolean includeAllotments = true;
	private boolean includeFlowers;

	public boolean isIncludeAllotments()
	{
		return includeAllotments;
	}

	public boolean isIncludeFlowers()
	{
		return includeFlowers;
	}

	/**
	 * "Include allotments" / "Include flowers": either, both or neither. A
	 * change applies to every patch of that family (per-patch ticks for it are
	 * reset); the other family is never switched on with it.
	 */
	public void setInclude(FarmingPatchType family, boolean on, java.util.Collection<FarmingModel.Row> rows)
	{
		if (family == FarmingPatchType.ALLOTMENT)
		{
			includeAllotments = on;
		}
		else if (family == FarmingPatchType.FLOWER)
		{
			includeFlowers = on;
		}
		if (rows != null)
		{
			for (FarmingModel.Row r : rows)
			{
				if (r.getPatch().getType() == family)
				{
					included.remove(r.getPatchId());
				}
			}
		}
	}

	/** Restores the saved family switches (preferences) without touching per-patch choices. */
	public void loadFamilies(boolean allotments, boolean flowers)
	{
		includeAllotments = allotments;
		includeFlowers = flowers;
	}

	public void setIncluded(String patchId, boolean include)
	{
		included.put(patchId, include);
	}

	/**
	 * The plan for a patch, as a name: the player's explicit choice for this
	 * patch; else their choice for every patch of its type; else
	 * {@link FarmingRunIntent#REPLANT_ANY} ("plant something suitable; you
	 * decide what"). Never the crop last seen there: an observed previous
	 * crop is history, not a decision to plant it again.
	 */
	public String plan(FarmingModel.Row row)
	{
		String id = row.getPatchId();
		String explicit = plans.get(id);
		if (explicit != null)
		{
			return explicit;
		}
		String forType = typePlans.get(FarmingRunType.of(row.getPatch().getType()));
		if (forType != null && (FarmingRunIntent.HARVEST_ONLY.equals(forType) || FarmingRunIntent.REPLANT_ANY.equals(forType)
			|| cropFits(forType, row.getPatch().getType())))
		{
			return forType;
		}
		return FarmingRunIntent.REPLANT_ANY;
	}

	/** The explicit crop of {@link #plan}, or null (harvest only, or "anything suitable"). */
	public FarmingProduce plannedCrop(FarmingModel.Row row, FarmingModel model)
	{
		return crop(plan(row));
	}

	/** "Any suitable seed", "Harvest only", "Snapdragon". */
	public String planLabel(FarmingModel.Row row)
	{
		return label(plan(row), row.getPatch().getType());
	}

	public static String label(String plan, FarmingPatchType type)
	{
		if (FarmingRunIntent.HARVEST_ONLY.equals(plan))
		{
			return "Harvest only";
		}
		FarmingProduce p = crop(plan);
		if (p != null)
		{
			return p.getDisplayName();
		}
		return "Replant";
	}

	static FarmingProduce crop(String plan)
	{
		if (plan == null || FarmingRunIntent.HARVEST_ONLY.equals(plan) || FarmingRunIntent.REPLANT_ANY.equals(plan))
		{
			return null;
		}
		try
		{
			return FarmingProduce.valueOf(plan);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static boolean cropFits(String plan, FarmingPatchType type)
	{
		FarmingProduce p = crop(plan);
		return p != null && p.getPatchType() == type;
	}

	/** An explicit crop for one patch; {@link #HARVEST_ONLY} (null) = harvest only. */
	public void setPlannedCrop(String patchId, FarmingProduce produce)
	{
		plans.put(patchId, produce == null ? FarmingRunIntent.HARVEST_ONLY : produce.name());
	}

	/** Back to "plant something suitable" for one patch. */
	public void setReplantAny(String patchId)
	{
		plans.put(patchId, FarmingRunIntent.REPLANT_ANY);
	}

	/** A plan for every patch of a run type that has no choice of its own (a crop, REPLANT_ANY, HARVEST_ONLY; null clears). */
	public void setTypePlan(FarmingRunType type, String plan)
	{
		if (plan == null)
		{
			typePlans.remove(type);
		}
		else
		{
			typePlans.put(type, plan);
		}
	}

	public String getTypePlan(FarmingRunType type)
	{
		return typePlans.get(type);
	}

	public boolean isUseBounty()
	{
		return useBounty;
	}

	public void setUseBounty(boolean use)
	{
		useBounty = use;
	}

	/** The crops a patch of this type can be planned with (harvest only is offered separately). */
	public static List<FarmingProduce> plantable(FarmingPatchType type)
	{
		List<FarmingProduce> out = new ArrayList<>();
		for (FarmingProduce p : FarmingProduce.values())
		{
			if (p.getPatchType() == type && p != FarmingProduce.SCARECROW)
			{
				out.add(p);
			}
		}
		return out;
	}

	public Compost getCompost(FarmingRunType type)
	{
		return compost.getOrDefault(type, Compost.NONE);
	}

	public void setCompost(FarmingRunType type, Compost choice)
	{
		compost.put(type, choice);
	}

	public boolean isProtectionPaid(FarmingRunType type)
	{
		return type.offersProtection() && protection.getOrDefault(type, Boolean.FALSE);
	}

	public void setProtectionPaid(FarmingRunType type, boolean pay)
	{
		protection.put(type, pay);
	}

	/** Included rows and their distinct sites, for "7 of 9 patches · 7 sites". */
	public int[] scope(List<FarmingModel.Row> rows, long now)
	{
		int count = 0;
		java.util.Set<String> sites = new java.util.HashSet<>();
		for (FarmingModel.Row r : rows)
		{
			if (isIncluded(r, now))
			{
				count++;
				sites.add(r.getPatch().getAreaName());
			}
		}
		return new int[] {count, rows.size(), sites.size()};
	}

	/** An account change: nothing planned for one account carries over to another. */
	public void clear()
	{
		includeAllotments = true;
		includeFlowers = false;
		included.clear();
		plans.clear();
		typePlans.clear();
		useBounty = false;
		compost.clear();
		protection.clear();
	}
}
