package com.osrstelemetry.plugin.hunter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Seeds a bird house accepts (OSRS Wiki, "Bird house trapping", Suitable bird bait, read 2026-10-28): a house takes 10 low
 * level or 5 high level seeds, any mix ("a stack of 7 Barley seeds and fill the remaining 3 with Hammerstone"). Counted
 * here in units: a low level seed is one unit, a high level seed two, and a house takes {@link #UNITS_PER_HOUSE}.
 */
public final class BirdhouseSeeds
{
	public static final int UNITS_PER_HOUSE = 10;

	/** Seed item → units (1 low level, 2 high level), cheapest kinds first. */
	public static final Map<Integer, Integer> UNITS;

	static
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		for (int id : new int[]{ItemID.BARLEY_SEED, ItemID.HAMMERSTONE_HOP_SEED, ItemID.ASGARNIAN_HOP_SEED, ItemID.JUTE_SEED, ItemID.YANILLIAN_HOP_SEED,
			ItemID.KRANDORIAN_HOP_SEED, ItemID.GUAM_SEED, ItemID.MARRENTILL_SEED, ItemID.TARROMIN_SEED, ItemID.HARRALANDER_SEED, ItemID.MARIGOLD_SEED,
			ItemID.ROSEMARY_SEED, ItemID.NASTURTIUM_SEED, ItemID.WOAD_SEED, ItemID.LIMPWURT_SEED, ItemID.WHITE_LILY_SEED, ItemID.POTATO_SEED,
			ItemID.ONION_SEED, ItemID.CABBAGE_SEED, ItemID.TOMATO_SEED, ItemID.SWEETCORN_SEED, ItemID.STRAWBERRY_SEED, ItemID.WATERMELON_SEED,
			ItemID.SNAPE_GRASS_SEED, ItemID.REDBERRY_BUSH_SEED, ItemID.POISONIVY_BUSH_SEED, ItemID.CADAVABERRY_BUSH_SEED, ItemID.DWELLBERRY_BUSH_SEED,
			ItemID.JANGERBERRY_BUSH_SEED, ItemID.WHITEBERRY_BUSH_SEED})
		{
			m.put(id, 1);
		}
		for (int id : new int[]{ItemID.WILDBLOOD_HOP_SEED, ItemID.RANARR_SEED, ItemID.TOADFLAX_SEED, ItemID.IRIT_SEED, ItemID.AVANTOE_SEED,
			ItemID.KWUARM_SEED, ItemID.SNAPDRAGON_SEED, ItemID.CADANTINE_SEED, ItemID.LANTADYME_SEED, ItemID.DWARF_WEED_SEED, ItemID.TORSTOL_SEED})
		{
			m.put(id, 2);
		}
		UNITS = Collections.unmodifiableMap(m);
	}

	private BirdhouseSeeds()
	{
	}

	public static boolean accepts(int itemId)
	{
		return UNITS.containsKey(itemId);
	}

	/** Units the given items provide (other items count nothing). */
	public static long units(Map<Integer, Integer> items)
	{
		long u = 0;
		if (items != null)
		{
			for (Map.Entry<Integer, Integer> e : items.entrySet())
			{
				Integer per = UNITS.get(e.getKey());
				if (per != null && e.getValue() != null && e.getValue() > 0)
				{
					u += (long) per * e.getValue();
				}
			}
		}
		return u;
	}

	/** Every accepted seed id, low level first (preparation's "any of these"). */
	public static int[] ids()
	{
		int[] out = new int[UNITS.size()];
		int i = 0;
		for (int id : UNITS.keySet())
		{
			out[i++] = id;
		}
		return out;
	}

	/** Units each of {@link #ids()} provides (same order). */
	public static int[] unitsPerId()
	{
		int[] out = new int[UNITS.size()];
		int i = 0;
		for (int u : UNITS.values())
		{
			out[i++] = u;
		}
		return out;
	}
}
