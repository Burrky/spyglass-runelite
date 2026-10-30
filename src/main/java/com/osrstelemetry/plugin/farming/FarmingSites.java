package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each farming site is, for preparation estimates (walk tiles from a
 * transport's arrival, site ordering). A site is one catalogue area (e.g. the
 * Catherby herb, allotments and flower share "Catherby").
 *
 * <h3>Provenance</h3>
 * Tiles are RuneLite's world-map farming patch markers
 * ({@code worldmap/FarmingPatchLocation}, master @ e98399f931c6, BSD-2; see
 * THIRD_PARTY_NOTICES.md). A patch takes the first marker lying in its area's
 * owning region, else in one of its alias regions (the same region data the
 * capture uses); a patch with no marker in its regions has no tile (unknown),
 * never a guessed one. Markers are map icons: within a few tiles of the
 * patches, good for estimates, not for walking.
 */
public final class FarmingSites
{
	/**
	 * RuneLite FarmingPatchLocation marker tiles (x, y, plane) with the patch types each group names ("Allotment/Herb/Flower"):
	 * a patch takes the marker of its own type in its regions. Several areas have two or more markers in one region (the Gnome
	 * Stronghold tree and fruit tree 38 tiles apart; Brimhaven; the Farming Guild; Kastori; Prifddinas; Harmony): before
	 * 2026-10-22 (canonical §28.16) a patch took the first marker in its region whatever its type, so the Gnome Stronghold
	 * tree's stop, gardener search and route goal were the fruit tree's.
	 */
	static final Object[][] MARKERS = {
		{3793, 2836, 0, "ALLOTMENT"}, {1269, 3730, 0, "ALLOTMENT"},
		{3289, 6100, 0, "ALLOTMENT FLOWER"},
		{1729, 3558, 0, "ALLOTMENT HERB FLOWER"}, {3598, 3524, 0, "ALLOTMENT HERB FLOWER"}, {3052, 3309, 0, "ALLOTMENT HERB FLOWER"},
		{2810, 3462, 0, "ALLOTMENT HERB FLOWER"}, {2663, 3375, 0, "ALLOTMENT HERB FLOWER"}, {1587, 3101, 0, "ALLOTMENT HERB FLOWER"},
		{1235, 3724, 0, "ANIMA HERB"},
		{3084, 3356, 0, "BELLADONNA"}, {1449, 3354, 0, "BELLADONNA"},
		{2938, 3223, 0, "BUSH"}, {2589, 3862, 0, "BUSH"}, {3182, 3356, 0, "BUSH"}, {2615, 3224, 0, "BUSH"},
		{1259, 3729, 0, "BUSH FLOWER"},
		{3313, 3201, 0, "CACTUS"}, {1264, 3745, 0, "CACTUS"},
		{2793, 3099, 0, "CALQUAT"}, {1365, 3035, 0, "CALQUAT"}, {3125, 2403, 0, "CALQUAT"},
		{1242, 3755, 0, "CELASTRUS FRUIT_TREE"},
		{3292, 6120, 0, "CRYSTAL_TREE"},
		{1352, 3025, 0, "FLOWER"},
		{2487, 3181, 0, "FRUIT_TREE"}, {2343, 3160, 0, "FRUIT_TREE"}, {2472, 3445, 0, "FRUIT_TREE"}, {2858, 3432, 0, "FRUIT_TREE"},
		{2765, 3211, 0, "FRUIT_TREE"}, {1347, 3058, 0, "FRUIT_TREE"},
		{3707, 3838, 0, "HARDWOOD_TREE"}, {1683, 2971, 0, "HARDWOOD_TREE"}, {2472, 2705, 0, "HARDWOOD_TREE"},
		{3789, 2840, 0, "HERB"}, {2847, 3933, 0, "HERB"}, {2828, 3696, 0, "HERB"},
		// Hespori: RuneLite's icon lies outside the patch's region 19_157; the centre of the 3x3 patch footprint in the cave chamber
		// (world collision map, region 19_157; §28.11) is the one in its region.
		{1182, 10068, 0, "HESPORI"}, {1247, 10087, 0, "HESPORI"},
		{2572, 3102, 0, "HOPS"}, {2661, 3523, 0, "HOPS"}, {3224, 3313, 0, "HOPS"}, {2812, 3334, 0, "HOPS"}, {1367, 2938, 0, "HOPS"},
		{3449, 3471, 0, "MUSHROOM"},
		{1233, 3754, 0, "REDWOOD"},
		{3730, 10271, 0, "SEAWEED"},
		{3056, 3259, 0, "SPIRIT_TREE"}, {1690, 3540, 0, "SPIRIT_TREE"}, {3614, 3856, 0, "SPIRIT_TREE"}, {2799, 3205, 0, "SPIRIT_TREE"},
		{1254, 3753, 0, "SPIRIT_TREE"},
		{3226, 3457, 0, "TREE"}, {2933, 3436, 0, "TREE"}, {3189, 3233, 0, "TREE"}, {2434, 3418, 0, "TREE"}, {3005, 3375, 0, "TREE"},
		{1234, 3736, 0, "TREE"}, {1366, 3318, 0, "TREE"},
	};

	public static final class Tile
	{
		public final int x;
		public final int y;
		public final int plane;

		public Tile(int x, int y, int plane)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
		}

		@Override
		public String toString()
		{
			return x + "," + y + "," + plane;
		}
	}

	public static final class Site
	{
		private final String name;
		private final Tile tile;
		private final List<String> patchIds;

		Site(String name, Tile tile, List<String> patchIds)
		{
			this.name = name;
			this.tile = tile;
			this.patchIds = Collections.unmodifiableList(patchIds);
		}

		public String getName()
		{
			return name;
		}

		/** Null when no marker lies in the site's regions (unknown). */
		public Tile getTile()
		{
			return tile;
		}

		public List<String> getPatchIds()
		{
			return patchIds;
		}
	}

	private FarmingSites()
	{
	}

	static int region(int x, int y)
	{
		return (x >> 6) << 8 | (y >> 6);
	}

	/**
	 * The marker tile for a patch: the marker of its own type in its owning or alias regions, else (no marker of its type there)
	 * the first marker in those regions; null when none (unknown, never guessed).
	 */
	public static Tile tileOf(FarmingPatchCatalog catalog, String patchId)
	{
		FarmingPatchDef def = catalog.patch(patchId);
		String type = def == null ? null : def.getType().name();
		Tile any = null;
		for (int region : catalog.regionsOf(patchId))
		{
			for (Object[] m : MARKERS)
			{
				int x = (Integer) m[0];
				int y = (Integer) m[1];
				if (region(x, y) != region)
				{
					continue;
				}
				if (type != null && java.util.Arrays.asList(((String) m[3]).split(" ")).contains(type))
				{
					return new Tile(x, y, (Integer) m[2]);
				}
				if (any == null)
				{
					any = new Tile(x, y, (Integer) m[2]);
				}
			}
		}
		return any;
	}

	/** Patches of one area further apart than this (marker tiles) are separate stops (Falador farm vs. Falador Park). */
	static final int STOP_RADIUS = 30;

	/**
	 * The stop key of a patch: its catalogue area, split when the area's patches lie far apart (Falador's farm and park,
	 * Lumbridge's hops and tree, the Gnome Stronghold's tree and fruit tree, Brimhaven's fruit and spirit trees). Patches whose
	 * markers chain within {@link #STOP_RADIUS} of each other are one stop (the Farming Guild is one); a split stop is keyed by
	 * its first patch in catalogue order. Patches at one stop share travel, tools and a walk; each keeps its own identity.
	 */
	public static String stopOf(FarmingPatchCatalog catalog, String patchId)
	{
		FarmingPatchDef def = catalog.patch(patchId);
		if (def == null)
		{
			return patchId;
		}
		List<FarmingPatchDef> area = new ArrayList<>();
		for (FarmingPatchDef d : catalog.allPatches())
		{
			if (d.getAreaName().equals(def.getAreaName()))
			{
				area.add(d);
			}
		}
		// Single-linkage groups over the area's located patches; an unlocated patch belongs to the area's first group.
		List<List<FarmingPatchDef>> groups = new ArrayList<>();
		for (FarmingPatchDef d : area)
		{
			Tile t = tileOf(catalog, d.getPatchId());
			List<FarmingPatchDef> joined = null;
			for (java.util.Iterator<List<FarmingPatchDef>> it = groups.iterator(); it.hasNext(); )
			{
				List<FarmingPatchDef> g = it.next();
				boolean link = t == null;
				for (FarmingPatchDef o : g)
				{
					Tile ot = tileOf(catalog, o.getPatchId());
					link |= ot == null || near(t, ot);
				}
				if (!link)
				{
					continue;
				}
				if (joined == null)
				{
					g.add(d);
					joined = g;
				}
				else
				{
					joined.addAll(g);
					it.remove();
				}
			}
			if (joined == null)
			{
				List<FarmingPatchDef> g = new ArrayList<>();
				g.add(d);
				groups.add(g);
			}
		}
		if (groups.size() <= 1)
		{
			return def.getAreaName();
		}
		for (List<FarmingPatchDef> g : groups)
		{
			if (g.contains(def))
			{
				FarmingPatchDef first = g.get(0);
				for (FarmingPatchDef d : area)
				{
					if (g.contains(d))
					{
						first = d;
						break;
					}
				}
				return def.getAreaName() + "#" + first.getPatchId();
			}
		}
		return def.getAreaName();
	}

	static boolean near(Tile a, Tile b)
	{
		return a.plane == b.plane && Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)) <= STOP_RADIUS;
	}

	/** Every catalogued patch at the same stop (selected or not): they share the stop's travel. */
	public static List<String> stopmates(FarmingPatchCatalog catalog, String patchId)
	{
		String key = stopOf(catalog, patchId);
		List<String> out = new ArrayList<>();
		for (FarmingPatchDef d : catalog.allPatches())
		{
			if (stopOf(catalog, d.getPatchId()).equals(key))
			{
				out.add(d.getPatchId());
			}
		}
		return out;
	}

	/**
	 * Groups patches into stops (catalogue area, split by distance), in
	 * first-seen order. A split area's stops are named by their patch types
	 * ("Falador · tree") so two stops never read the same.
	 */
	public static List<Site> sitesOf(FarmingPatchCatalog catalog, List<String> patchIds)
	{
		Map<String, List<String>> byStop = new LinkedHashMap<>();
		for (String id : patchIds)
		{
			byStop.computeIfAbsent(stopOf(catalog, id), k -> new ArrayList<>()).add(id);
		}
		List<Site> out = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : byStop.entrySet())
		{
			Tile tile = null;
			for (String id : e.getValue())
			{
				tile = tileOf(catalog, id);
				if (tile != null)
				{
					break;
				}
			}
			String key = e.getKey();
			String name = key;
			int hash = key.indexOf('#');
			if (hash >= 0)
			{
				java.util.Set<String> types = new java.util.LinkedHashSet<>();
				for (String id : e.getValue())
				{
					FarmingPatchDef d = catalog.patch(id);
					types.add(d == null ? "patch" : d.getType().getDisplayName().toLowerCase(java.util.Locale.ROOT));
				}
				name = key.substring(0, hash) + " \u00b7 " + String.join(" & ", types);
			}
			out.add(new Site(name, tile, e.getValue()));
		}
		return out;
	}
}
