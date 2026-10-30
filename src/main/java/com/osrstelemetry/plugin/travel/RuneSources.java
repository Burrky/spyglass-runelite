package com.osrstelemetry.plugin.travel;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * How many of each base rune a set of items supplies for spell costs. Pure.
 *
 * <h3>Rules and provenance (OSRS Wiki, checked 2026-10-15)</h3>
 * <ul>
 *   <li>Combination runes count as one of each of their two elements (steam =
 *   water + fire, mist = air + water, dust = air + earth, smoke = air + fire,
 *   mud = water + earth, lava = earth + fire).</li>
 *   <li>A wielded elemental staff, battlestaff or mystic staff supplies
 *   unlimited runes of its element; a combination battlestaff / mystic staff
 *   supplies both elements. Only when <em>worn</em>: a staff in the inventory
 *   supplies nothing.</li>
 *   <li>A wielded charged tome of fire supplies unlimited fire runes and a
 *   charged tome of water unlimited water runes; their uncharged variants
 *   supply nothing. The Kodai wand supplies unlimited water runes.</li>
 *   <li>Tome of earth is NOT included: its rune-supply behaviour was not
 *   verified for this phase.</li>
 * </ul>
 * Rune pouch contents are passed in by the caller only when the pouch's own
 * location is known (see {@link TransportEvaluator}).
 */
public final class RuneSources
{
	public static final long UNLIMITED = Long.MAX_VALUE;

	public static final int AIR = ItemID.AIRRUNE;
	public static final int WATER = ItemID.WATERRUNE;
	public static final int EARTH = ItemID.EARTHRUNE;
	public static final int FIRE = ItemID.FIRERUNE;

	private static final Set<Integer> BASE = new LinkedHashSet<>(java.util.Arrays.asList(
		ItemID.AIRRUNE, ItemID.WATERRUNE, ItemID.EARTHRUNE, ItemID.FIRERUNE, ItemID.LAWRUNE, ItemID.NATURERUNE, ItemID.SOULRUNE, ItemID.ASTRALRUNE));

	private static final Map<Integer, int[]> COMBINATION = new HashMap<>();
	private static final Map<Integer, int[]> WORN_PROVIDERS = new LinkedHashMap<>();

	static
	{
		COMBINATION.put(ItemID.STEAMRUNE, new int[]{WATER, FIRE});
		COMBINATION.put(ItemID.MISTRUNE, new int[]{AIR, WATER});
		COMBINATION.put(ItemID.DUSTRUNE, new int[]{AIR, EARTH});
		COMBINATION.put(ItemID.SMOKERUNE, new int[]{AIR, FIRE});
		COMBINATION.put(ItemID.MUDRUNE, new int[]{WATER, EARTH});
		COMBINATION.put(ItemID.LAVARUNE, new int[]{EARTH, FIRE});

		provider(new int[]{AIR}, ItemID.STAFF_OF_AIR, ItemID.AIR_BATTLESTAFF, ItemID.MYSTIC_AIR_STAFF);
		provider(new int[]{WATER}, ItemID.STAFF_OF_WATER, ItemID.WATER_BATTLESTAFF, ItemID.MYSTIC_WATER_STAFF, ItemID.TOME_OF_WATER, ItemID.KODAI_WAND);
		provider(new int[]{EARTH}, ItemID.STAFF_OF_EARTH, ItemID.EARTH_BATTLESTAFF, ItemID.MYSTIC_EARTH_STAFF);
		provider(new int[]{FIRE}, ItemID.STAFF_OF_FIRE, ItemID.FIRE_BATTLESTAFF, ItemID.MYSTIC_FIRE_STAFF, ItemID.TOME_OF_FIRE);
		provider(new int[]{EARTH, FIRE}, ItemID.LAVA_BATTLESTAFF, ItemID.MYSTIC_LAVA_STAFF);
		provider(new int[]{WATER, EARTH}, ItemID.MUD_BATTLESTAFF, ItemID.MYSTIC_MUD_STAFF);
		provider(new int[]{WATER, FIRE}, ItemID.STEAM_BATTLESTAFF, ItemID.MYSTIC_STEAM_BATTLESTAFF);
		provider(new int[]{AIR, FIRE}, ItemID.SMOKE_BATTLESTAFF, ItemID.MYSTIC_SMOKE_BATTLESTAFF);
		provider(new int[]{AIR, WATER}, ItemID.MIST_BATTLESTAFF, ItemID.MYSTIC_MIST_BATTLESTAFF);
		provider(new int[]{AIR, EARTH}, ItemID.DUST_BATTLESTAFF, ItemID.MYSTIC_DUST_BATTLESTAFF);
	}

	private RuneSources()
	{
	}

	private static void provider(int[] runes, int... items)
	{
		for (int item : items)
		{
			WORN_PROVIDERS.put(item, runes);
		}
	}

	public static boolean isBaseRune(int itemId)
	{
		return BASE.contains(itemId);
	}

	/** Runes a worn item supplies without limit, or an empty array. */
	public static int[] providedBy(int itemId)
	{
		int[] r = WORN_PROVIDERS.get(itemId);
		return r == null ? new int[0] : r.clone();
	}

	public static Set<Integer> providerItems()
	{
		return Collections.unmodifiableSet(WORN_PROVIDERS.keySet());
	}

	/**
	 * Base-rune supply from loose runes ({@code runes}) plus unlimited runes from
	 * {@code providers} (worn items, or for bank planning, provider items held).
	 */
	public static Map<Integer, Long> supply(Map<Integer, Integer> runes, Map<Integer, Integer> providers)
	{
		Map<Integer, Long> out = new HashMap<>();
		if (runes != null)
		{
			for (Map.Entry<Integer, Integer> e : runes.entrySet())
			{
				int id = e.getKey();
				long qty = Math.max(0, e.getValue());
				if (BASE.contains(id))
				{
					add(out, id, qty);
				}
				int[] combo = COMBINATION.get(id);
				if (combo != null)
				{
					for (int base : combo)
					{
						add(out, base, qty);
					}
				}
			}
		}
		if (providers != null)
		{
			for (Integer item : providers.keySet())
			{
				for (int base : providedBy(item))
				{
					out.put(base, UNLIMITED);
				}
			}
		}
		return out;
	}

	private static void add(Map<Integer, Long> out, int rune, long qty)
	{
		long cur = out.getOrDefault(rune, 0L);
		out.put(rune, cur == UNLIMITED ? UNLIMITED : cur + qty);
	}

	/** Adds two supplies (unlimited wins). */
	public static Map<Integer, Long> plus(Map<Integer, Long> a, Map<Integer, Long> b)
	{
		Map<Integer, Long> out = new HashMap<>(a);
		for (Map.Entry<Integer, Long> e : b.entrySet())
		{
			if (e.getValue() == UNLIMITED)
			{
				out.put(e.getKey(), UNLIMITED);
			}
			else
			{
				add(out, e.getKey(), e.getValue());
			}
		}
		return out;
	}
}
