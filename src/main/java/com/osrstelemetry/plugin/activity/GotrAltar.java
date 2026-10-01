package com.osrstelemetry.plugin.activity;

import net.runelite.api.Quest;

/**
 * The twelve Guardians of the Rift rune altars, in the game's own guardian
 * order -- the order of the GotR HUD script's (5980) "active guardian"
 * arguments: elemental 1..4, catalytic 1..8.
 *
 * INDEX MAPPING (verified live, 2026-10-01): the player stepped through the
 * active rift into the Earth altar while the elemental index was 3, the Fire
 * altar at 4, and the Cosmic / Nature / Death altars while the catalytic index
 * was 3 / 5 / 7 -- exactly the game's guardian order below, which fixes the
 * remaining positions (Air 1, Water 2; Mind 1, Body 2, Chaos 4, Law 6, Blood 8).
 *
 * MECHANICS (OSRS Wiki, Guardians of the Rift / Guardian essence):
 * <ul>
 * <li>level / quest: the altar's Runecraft and quest requirement;</li>
 * <li>xpPerEssence: the rune's standard Runecraft experience per essence
 * (guardian essence is not flat -- it matches the rune);</li>
 * <li>cell tier: the barrier cell the altar's stones charge -- higher-tier
 * cells give more energy to BOTH types, which is why the wiki's even-points
 * strategy prioritises Blood/Death, then Fire/Earth.</li>
 * </ul>
 * Every guardian stone gives the same 2 energy of its type regardless of the
 * altar, so for points the altar matters only through its cell tier.
 */
public enum GotrAltar
{
	AIR(Type.ELEMENTAL, 1, "Air", 1, 5.0, CellTier.WEAK, null),
	WATER(Type.ELEMENTAL, 2, "Water", 5, 6.0, CellTier.MEDIUM, null),
	EARTH(Type.ELEMENTAL, 3, "Earth", 9, 6.5, CellTier.STRONG, null),
	FIRE(Type.ELEMENTAL, 4, "Fire", 14, 7.0, CellTier.OVERCHARGED, null),
	MIND(Type.CATALYTIC, 1, "Mind", 2, 5.5, CellTier.WEAK, null),
	BODY(Type.CATALYTIC, 2, "Body", 20, 7.5, CellTier.WEAK, null),
	COSMIC(Type.CATALYTIC, 3, "Cosmic", 27, 8.0, CellTier.MEDIUM, Quest.LOST_CITY),
	CHAOS(Type.CATALYTIC, 4, "Chaos", 35, 8.5, CellTier.MEDIUM, null),
	NATURE(Type.CATALYTIC, 5, "Nature", 44, 9.0, CellTier.STRONG, null),
	LAW(Type.CATALYTIC, 6, "Law", 54, 9.5, CellTier.STRONG, Quest.TROLL_STRONGHOLD),
	DEATH(Type.CATALYTIC, 7, "Death", 65, 10.0, CellTier.OVERCHARGED, Quest.MOURNINGS_END_PART_II),
	BLOOD(Type.CATALYTIC, 8, "Blood", 77, 10.5, CellTier.OVERCHARGED, Quest.SINS_OF_THE_FATHER);

	public enum Type
	{
		ELEMENTAL("Elemental"),
		CATALYTIC("Catalytic");

		private final String label;

		Type(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	public enum CellTier
	{
		WEAK,
		MEDIUM,
		STRONG,
		OVERCHARGED
	}

	private final Type type;
	private final int guardianIndex;
	private final String displayName;
	private final int runecraftLevel;
	private final double xpPerEssence;
	private final CellTier cellTier;
	private final Quest quest;

	GotrAltar(Type type, int guardianIndex, String displayName, int runecraftLevel, double xpPerEssence, CellTier cellTier, Quest quest)
	{
		this.type = type;
		this.guardianIndex = guardianIndex;
		this.displayName = displayName;
		this.runecraftLevel = runecraftLevel;
		this.xpPerEssence = xpPerEssence;
		this.cellTier = cellTier;
		this.quest = quest;
	}

	/** The altar for a HUD-script guardian index, or null when out of range (no game / no active guardian). */
	public static GotrAltar of(Type type, Integer guardianIndex)
	{
		if (type == null || guardianIndex == null)
		{
			return null;
		}
		for (GotrAltar a : values())
		{
			if (a.type == type && a.guardianIndex == guardianIndex)
			{
				return a;
			}
		}
		return null;
	}

	public Type getType()
	{
		return type;
	}

	public int getGuardianIndex()
	{
		return guardianIndex;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getRunecraftLevel()
	{
		return runecraftLevel;
	}

	public double getXpPerEssence()
	{
		return xpPerEssence;
	}

	public CellTier getCellTier()
	{
		return cellTier;
	}

	/** The quest the altar requires, or null. */
	public Quest getQuest()
	{
		return quest;
	}
}
