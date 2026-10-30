package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The run types the Farming page offers (canonical §2.2): six main types and
 * the Special row. A run type groups patch types for browsing and previewing;
 * selecting one never creates a run, a route or guidance.
 *
 * Flowers are companion rows of the Allotment preview, not a run type.
 */
public enum FarmingRunType
{
	HERB("Herb", "Herb", false, FarmingPatchType.HERB),
	TREE("Tree", "Tree", false, FarmingPatchType.TREE),
	FRUIT("Fruit", "Fruit tree", false, FarmingPatchType.FRUIT_TREE),
	BUSH("Bush", "Bush", false, FarmingPatchType.BUSH),
	HOPS("Hops", "Hops", false, FarmingPatchType.HOPS),
	ALLOTMENT("Allot. & Fl.", "Allotments & Flowers", false, FarmingPatchType.ALLOTMENT),
	HARDWOOD("Hardwood", "Hardwood", true, FarmingPatchType.HARDWOOD_TREE),
	REDWOOD("Redwood", "Redwood", true, FarmingPatchType.REDWOOD),
	SPIRIT("Spirit", "Spirit tree", true, FarmingPatchType.SPIRIT_TREE),
	CALQUAT("Calquat", "Calquat", true, FarmingPatchType.CALQUAT),
	CELASTRUS("Celastrus", "Celastrus", true, FarmingPatchType.CELASTRUS),
	CACTUS("Cactus", "Cactus", true, FarmingPatchType.CACTUS),
	SEAWEED("Seaweed", "Seaweed", true, FarmingPatchType.SEAWEED),
	MUSHROOM("Mushroom", "Mushroom", true, FarmingPatchType.MUSHROOM),
	BELLADONNA("Bellad.", "Belladonna", true, FarmingPatchType.BELLADONNA),
	HESPORI("Hespori", "Hespori", true, FarmingPatchType.HESPORI),
	ANIMA("Anima", "Anima", true, FarmingPatchType.ANIMA),
	CRYSTAL("Crystal", "Crystal tree", true, FarmingPatchType.CRYSTAL_TREE),
	/**
	 * Not a crop: the compost bins the player chose (canonical §28.17), a run component of its own, alone or combined with crop
	 * types. It has no patches; nothing that lists crop types includes it ({@link #isCrop()}).
	 */
	COMPOST("Compost", "Compost bins", false, null);

	private final String buttonLabel;
	private final String title;
	private final boolean special;
	private final FarmingPatchType primary;

	FarmingRunType(String buttonLabel, String title, boolean special, FarmingPatchType primary)
	{
		this.buttonLabel = buttonLabel;
		this.title = title;
		this.special = special;
		this.primary = primary;
	}

	/** Fits a ~69 px button. */
	public String getButtonLabel()
	{
		return buttonLabel;
	}

	/** "Herb", "Fruit tree": the preview heading reads "{title} patches". */
	public String getTitle()
	{
		return title;
	}

	/** A crop run type (every type but {@link #COMPOST}). */
	public boolean isCrop()
	{
		return primary != null;
	}

	public boolean isSpecial()
	{
		return special;
	}

	public FarmingPatchType getPrimary()
	{
		return primary;
	}

	/** The second patch family of a category (flowers in "Allotments & Flowers"), included only when the player turns it on. */
	public Set<FarmingPatchType> getCompanions()
	{
		return this == ALLOTMENT ? Collections.unmodifiableSet(EnumSet.of(FarmingPatchType.FLOWER)) : Collections.emptySet();
	}

	public boolean covers(FarmingPatchType type)
	{
		return primary != null && type == primary || getCompanions().contains(type);
	}

	/**
	 * A gardener can be paid to protect these (OSRS Wiki, Farming: allotment,
	 * hops, bush, tree, fruit tree and some special patches; herbs and flowers
	 * cannot). Hardwood and calquat payments are sourced (FarmingSeeds).
	 */
	public boolean offersProtection()
	{
		return this == TREE || this == FRUIT || this == HOPS || this == BUSH || this == ALLOTMENT || this == HARDWOOD || this == CALQUAT || this == CACTUS
			|| this == CELASTRUS || this == REDWOOD || this == SPIRIT;
	}

	/** The run type a patch type is browsed under (flowers: Allotment). */
	public static FarmingRunType of(FarmingPatchType type)
	{
		for (FarmingRunType r : values())
		{
			if (r.covers(type))
			{
				return r;
			}
		}
		throw new IllegalArgumentException("No run type for " + type);
	}

	/** A saved selection name, or null (the overview) when absent or no longer valid. */
	public static FarmingRunType parse(String saved)
	{
		if (saved == null || saved.isEmpty())
		{
			return null;
		}
		try
		{
			return valueOf(saved);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
