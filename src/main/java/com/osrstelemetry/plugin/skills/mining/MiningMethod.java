package com.osrstelemetry.plugin.skills.mining;

import java.util.Locale;
import net.runelite.api.gameval.ItemID;

/**
 * The first-pass Mining METHODS the Mining companion recognises. A method is
 * identified by what it causally PRODUCES (and, as a hint only, by the rock
 * the player clicked "Mine" on) -- never by XP-drop size.
 *
 * Deliberately NOT here (see MiningCompanionDefinition's javadoc for why):
 * Motherlode Mine, Blast Mine, Volcanic Mine, Zalcano, shooting stars,
 * Barronite / Camdozaal, sunstone, salts, dense essence. Mining XP from those
 * still counts in the session; the companion simply shows no method for it.
 */
public enum MiningMethod
{
	CLAY("Clay", "CLAY MINED", "clay", true, new String[]{"clay rocks"}, ItemID.CLAY, ItemID.SOFTCLAY),
	COPPER("Copper", "COPPER MINED", "ore", true, new String[]{"copper rocks"}, ItemID.COPPER_ORE),
	TIN("Tin", "TIN MINED", "ore", true, new String[]{"tin rocks"}, ItemID.TIN_ORE),
	IRON("Iron", "IRON MINED", "ore", true, new String[]{"iron rocks"}, ItemID.IRON_ORE),
	SILVER("Silver", "SILVER MINED", "ore", true, new String[]{"silver rocks"}, ItemID.SILVER_ORE),
	COAL("Coal", "COAL MINED", "coal", true, new String[]{"coal rocks"}, ItemID.COAL),
	GOLD("Gold", "GOLD MINED", "ore", true, new String[]{"gold rocks"}, ItemID.GOLD_ORE),
	MITHRIL("Mithril", "MITHRIL MINED", "ore", true, new String[]{"mithril rocks"}, ItemID.MITHRIL_ORE),
	ADAMANTITE("Adamantite", "ADAMANT MINED", "ore", true, new String[]{"adamantite rocks"}, ItemID.ADAMANTITE_ORE),
	RUNITE("Runite", "RUNITE MINED", "ore", true, new String[]{"runite rocks"}, ItemID.RUNITE_ORE),
	BLURITE("Blurite", "BLURITE MINED", "ore", true, new String[]{"blurite rocks"}, ItemID.BLURITE_ORE),
	LEAD("Lead", "LEAD MINED", "ore", true, new String[]{"lead rocks"}, ItemID.LEAD_ORE),
	NICKEL("Nickel", "NICKEL MINED", "ore", true, new String[]{"nickel rocks"}, ItemID.NICKEL_ORE),
	LOVAKITE("Lovakite", "LOVAKITE MINED", "ore", true, new String[]{"lovakite rocks"}, ItemID.LOVAKITE_ORE),
	DAEYALT("Daeyalt essence", "ESSENCE MINED", "essence", true, new String[]{"daeyalt essence"}, ItemID.BLANKRUNE_DAEYALT),
	AMETHYST("Amethyst", "AMETHYST", "amethyst", true, new String[]{"amethyst crystals"}, ItemID.AMETHYST),
	GRANITE("Granite", "GRANITE", "granite", true, new String[]{"granite"},
		ItemID.ENAKH_GRANITE_TINY, ItemID.ENAKH_GRANITE_SMALL, ItemID.ENAKH_GRANITE_MEDIUM),
	SANDSTONE("Sandstone", "SANDSTONE", "sandstone", true, new String[]{"sandstone"},
		ItemID.ENAKH_SANDSTONE_TINY, ItemID.ENAKH_SANDSTONE_SMALL, ItemID.ENAKH_SANDSTONE_MEDIUM, ItemID.ENAKH_SANDSTONE_LARGE),
	/** One uncut gem per success (65 XP each); the gems themselves ARE the production. */
	GEM_ROCKS("Gem rocks", "GEMS MINED", "gems", true, new String[]{"gem rocks"}),
	/** Cam Torum: blessed bone shards (stackable) or, rarely, a calcified deposit. */
	CALCIFIED("Calcified rocks", "BONE SHARDS", "shards", false, new String[]{"calcified rocks"}, ItemID.BLESSED_BONE_SHARD);

	private final String label;
	private final String productionCaption;
	private final String rateNoun;
	private final boolean tripMatters;
	private final String[] rockNames;
	private final int[] primaryItems;

	MiningMethod(String label, String productionCaption, String rateNoun, boolean tripMatters, String[] rockNames, int... primaryItems)
	{
		this.label = label;
		this.productionCaption = productionCaption;
		this.rateNoun = rateNoun;
		this.tripMatters = tripMatters;
		this.rockNames = rockNames;
		this.primaryItems = primaryItems;
	}

	public String getLabel()
	{
		return label;
	}

	public String getProductionCaption()
	{
		return productionCaption;
	}

	public String getRateNoun()
	{
		return rateNoun;
	}

	/** Inventory (or gem storage) capacity decides when to bank / drop: CURRENT TRIP is shown. */
	public boolean isTripMatters()
	{
		return tripMatters;
	}

	public int[] getPrimaryItems()
	{
		return primaryItems.clone();
	}

	/** The persisted key ("iron", "gem_rocks") -- stable across label changes. */
	public String getKey()
	{
		return name().toLowerCase(Locale.ROOT);
	}

	public static MiningMethod fromKey(String key)
	{
		if (key == null)
		{
			return null;
		}
		for (MiningMethod m : values())
		{
			if (m.getKey().equals(key))
			{
				return m;
			}
		}
		return null;
	}

	/** The method whose primary product this item is, or null (gems are not any single ore method's product). */
	public static MiningMethod producing(int itemId)
	{
		for (MiningMethod m : values())
		{
			for (int id : m.primaryItems)
			{
				if (id == itemId)
				{
					return m;
				}
			}
		}
		return null;
	}

	/** The method a "Mine" click on this object names ("Iron rocks" -> IRON), or null. Hint only. */
	public static MiningMethod forRockName(String target)
	{
		if (target == null)
		{
			return null;
		}
		String t = target.toLowerCase(Locale.ROOT);
		for (MiningMethod m : values())
		{
			for (String name : m.rockNames)
			{
				if (t.contains(name))
				{
					return m;
				}
			}
		}
		return null;
	}
}
