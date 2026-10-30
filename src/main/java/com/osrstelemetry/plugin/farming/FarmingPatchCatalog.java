package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.VarbitID;

/**
 * The catalogued farming areas and how a location resolves to the patches
 * whose state the farming varbits currently carry.
 *
 * Area rows, alias ("extra") regions and bounds rules are derived from
 * RuneLite's FarmingWorld (master @ e98399f931c6, BSD 2-Clause, Copyright (c)
 * 2018 NotFoxtrot, Copyright (c) 2018 Abex; see THIRD_PARTY_NOTICES.md).
 *
 * COVERAGE: every RuneLite farming area, with every herb, allotment, flower,
 * tree, fruit tree, bush, hops and special patch (hardwood, redwood, spirit
 * tree, calquat, celastrus, cactus, seaweed, mushroom, belladonna, Hespori,
 * anima, crystal tree). NOT catalogued, by decision: compost bins (E/N), the
 * Hosidius vinery's grape vines, and the Great Conch coral nurseries (not
 * Spyglass run types). A patch that is not listed is simply never read; the
 * areas that share a map region (Falador / Port Sarim in 12083, Catherby /
 * its fruit tree in 11317) are separated by the bounds rules reproduced
 * below, so no varbit is ever attributed to the wrong patch.
 *
 * Patch ids are persisted: an existing id is never renamed.
 */
public final class FarmingPatchCatalog
{
	/** The x / y / plane rule RuneLite's FarmingRegion.isInBounds applies. */
	interface Bounds
	{
		boolean contains(FarmingLocation location);
	}

	static final class Area
	{
		final String name;
		final int owningRegionId;
		final int[] aliasRegionIds;
		final Bounds bounds;
		final List<FarmingPatchDef> patches;

		Area(String name, int owningRegionId, int[] aliasRegionIds, Bounds bounds, List<FarmingPatchDef> patches)
		{
			this.name = name;
			this.owningRegionId = owningRegionId;
			this.aliasRegionIds = aliasRegionIds;
			this.bounds = bounds;
			this.patches = patches;
		}
	}

	private static final Bounds ANYWHERE = location -> true;

	/** Falador 12083: not the region's south edge, which carries the Port Sarim spirit tree instead. */
	static final Bounds FALADOR_ALLOTMENTS = location -> location.getY() >= 3272;

	/** Catherby 11062: in the north-east corner only the ground floor of the patch area transmits these (upstairs sends different varbits). */
	static final Bounds CATHERBY = location ->
	{
		if (location.getX() >= 2816 && location.getY() < 3456)
		{
			return location.getX() < 2840 && location.getY() >= 3440 && location.getPlane() == 0;
		}
		return true;
	};

	/** Port Sarim 12082 (and the south edge of 12083): the spirit tree, not Falador's allotments. */
	static final Bounds PORT_SARIM = location -> location.getY() < 3272;

	/** Catherby 11317: the fruit tree is sent everywhere the allotment box rule does not apply, and always upstairs. */
	static final Bounds CATHERBY_FRUIT_TREE = location -> location.getX() >= 2840 || location.getY() < 3440 || location.getPlane() == 1;

	/**
	 * Fossil Island: the hardwood trees are sent anywhere on plane 0, except on a
	 * few stair and ladder tiles where the varbits arrive a tick early.
	 */
	static final Bounds FOSSIL_ISLAND = location ->
	{
		int x = location.getX();
		int y = location.getY();
		if (x == 3753 && y >= 3868 && y <= 3870)
		{
			return false;
		}
		if ((x == 3729 || x == 3728 || x == 3747 || x == 3746) && y <= 3832 && y >= 3830)
		{
			return false;
		}
		return location.getPlane() == 0;
	};

	private static final FarmingPatchCatalog DEFAULT = new FarmingPatchCatalog(defaultAreas());

	private final List<Area> areas;
	private final Map<Integer, List<Area>> areasByRegion = new HashMap<>();
	private final Map<String, FarmingPatchDef> patchesById = new HashMap<>();

	FarmingPatchCatalog(List<Area> areas)
	{
		this.areas = Collections.unmodifiableList(new ArrayList<>(areas));
		for (Area area : areas)
		{
			areasByRegion.computeIfAbsent(area.owningRegionId, k -> new ArrayList<>()).add(area);
			for (int alias : area.aliasRegionIds)
			{
				List<Area> list = areasByRegion.computeIfAbsent(alias, k -> new ArrayList<>());
				if (!list.contains(area))
				{
					list.add(area); // RuneLite lists Great Conch's own region among its aliases too
				}
			}
			for (FarmingPatchDef patch : area.patches)
			{
				if (patchesById.put(patch.getPatchId(), patch) != null)
				{
					throw new IllegalArgumentException("Duplicate patchId " + patch.getPatchId());
				}
			}
		}
	}

	public static FarmingPatchCatalog standard()
	{
		return DEFAULT;
	}

	public FarmingPatchDef patch(String patchId)
	{
		return patchesById.get(patchId);
	}

	/** The owning region first, then the alias regions, of the area holding {@code patchId}; empty if unknown. */
	int[] regionsOf(String patchId)
	{
		for (Area area : areas)
		{
			for (FarmingPatchDef p : area.patches)
			{
				if (p.getPatchId().equals(patchId))
				{
					int[] out = new int[area.aliasRegionIds.length + 1];
					out[0] = area.owningRegionId;
					System.arraycopy(area.aliasRegionIds, 0, out, 1, area.aliasRegionIds.length);
					return out;
				}
			}
		}
		return new int[0];
	}

	public List<FarmingPatchDef> allPatches()
	{
		List<FarmingPatchDef> out = new ArrayList<>(patchesById.values());
		out.sort(Comparator.comparing(FarmingPatchDef::getPatchId));
		return out;
	}

	/** The patches the farming varbits describe at {@code location}; {@link TransmissionContext#NONE} when none. */
	public TransmissionContext resolve(FarmingLocation location)
	{
		if (location == null)
		{
			return TransmissionContext.NONE;
		}
		List<Area> candidates = areasByRegion.get(location.getRegionId());
		if (candidates == null)
		{
			return TransmissionContext.NONE;
		}
		List<FarmingPatchDef> patches = new ArrayList<>();
		for (Area area : candidates)
		{
			if (area.bounds.contains(location))
			{
				patches.addAll(area.patches);
			}
		}
		if (patches.isEmpty())
		{
			return TransmissionContext.NONE;
		}
		patches.sort(Comparator.comparing(FarmingPatchDef::getPatchId));
		return new TransmissionContext(patches);
	}

	/** Every area in the catalogue (for tests and presentation). */
	List<Area> areas()
	{
		return areas;
	}

	private static List<Area> defaultAreas()
	{
		List<Area> a = new ArrayList<>();
		// Ids of the first (Phase B) catalogue are kept exactly: herb.<site>, allotment.<site>.<n|s|nw|se>, tree.<site>.
		a.add(area("Al Kharid", 13106, ANYWHERE, aliases(13362, 13105), p("cactus.al_kharid", "", FarmingPatchType.CACTUS, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Aldarin", 5421, ANYWHERE, aliases(5165, 5166, 5422, 5677, 5678), p("hops.aldarin", "", FarmingPatchType.HOPS, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Anglers' Retreat", 9770, ANYWHERE, aliases(), p("hardwood.anglers_retreat", "", FarmingPatchType.HARDWOOD_TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Ardougne", 10290, ANYWHERE, aliases(10546), p("bush.ardougne", "", FarmingPatchType.BUSH, VarbitID.FARMING_TRANSMIT_A)));
		a.add(allotmentSite("Ardougne", "ardougne", 10548, ANYWHERE, aliases(), "n", "North", "s", "South"));
		a.add(area("Auburnvale", 5427, ANYWHERE, aliases(5428, 5684),
			p("tree.auburnvale", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_A),
			p("belladonna.auburnvale", "", FarmingPatchType.BELLADONNA, VarbitID.FARMING_TRANSMIT_B)));
		a.add(area("Avium Savannah", 6702, ANYWHERE, aliases(6446), p("hardwood.avium_savannah", "", FarmingPatchType.HARDWOOD_TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Brimhaven", 11058, ANYWHERE, aliases(11057),
			p("fruit_tree.brimhaven", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_A),
			p("spirit_tree.brimhaven", "", FarmingPatchType.SPIRIT_TREE, VarbitID.FARMING_TRANSMIT_B)));
		a.add(allotmentSite("Catherby", "catherby", 11062, CATHERBY, aliases(11061, 11318, 11317), "n", "North", "s", "South"));
		a.add(area("Catherby", 11317, CATHERBY_FRUIT_TREE, aliases(), p("fruit_tree.catherby", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(allotmentSite("Civitas illa Fortis", "civitas", 6192, ANYWHERE, aliases(6447, 6448, 6449, 6191, 6193), "n", "North", "s", "South"));
		a.add(area("Champions' Guild", 12596, ANYWHERE, aliases(), p("bush.champions_guild", "", FarmingPatchType.BUSH, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Draynor Manor", 12340, ANYWHERE, aliases(), p("belladonna.draynor_manor", "", FarmingPatchType.BELLADONNA, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Entrana", 11060, ANYWHERE, aliases(11316), p("hops.entrana", "", FarmingPatchType.HOPS, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Etceteria", 10300, ANYWHERE, aliases(),
			p("bush.etceteria", "", FarmingPatchType.BUSH, VarbitID.FARMING_TRANSMIT_A),
			p("spirit_tree.etceteria", "", FarmingPatchType.SPIRIT_TREE, VarbitID.FARMING_TRANSMIT_B)));
		a.add(area("Falador", 11828, ANYWHERE, aliases(12084), p("tree.falador", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(allotmentSite("Falador", "falador", 12083, FALADOR_ALLOTMENTS, aliases(), "nw", "North West", "se", "South East"));
		a.add(area("Fossil Island", 14651, FOSSIL_ISLAND, aliases(14907, 14908, 15164, 14652, 14906, 14650, 15162, 15163),
			p("hardwood.fossil_island.east", "East", FarmingPatchType.HARDWOOD_TREE, VarbitID.FARMING_TRANSMIT_A),
			p("hardwood.fossil_island.middle", "Middle", FarmingPatchType.HARDWOOD_TREE, VarbitID.FARMING_TRANSMIT_B),
			p("hardwood.fossil_island.west", "West", FarmingPatchType.HARDWOOD_TREE, VarbitID.FARMING_TRANSMIT_C)));
		a.add(area("Fossil Island (underwater)", 15008, ANYWHERE, aliases(),
			p("seaweed.fossil_island.n", "North", FarmingPatchType.SEAWEED, VarbitID.FARMING_TRANSMIT_A),
			p("seaweed.fossil_island.s", "South", FarmingPatchType.SEAWEED, VarbitID.FARMING_TRANSMIT_B)));
		a.add(area("Gnome Stronghold", 9781, ANYWHERE, aliases(9782, 9526, 9525),
			p("tree.gnome_stronghold", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_A),
			p("fruit_tree.gnome_stronghold", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_B)));
		// Great Conch: the calquat only (its two coral nurseries, A and B, are not catalogued).
		a.add(area("Great Conch", 12581, ANYWHERE, aliases(12325, 12326, 12327, 12580, 12581, 12582, 12583, 12836, 12837, 12838, 12839, 13092, 13093, 13194),
			p("calquat.great_conch", "", FarmingPatchType.CALQUAT, VarbitID.FARMING_TRANSMIT_C)));
		a.add(area("Harmony", 15148, ANYWHERE, aliases(),
			p("allotment.harmony", "", FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_A),
			p("herb.harmony", "", FarmingPatchType.HERB, VarbitID.FARMING_TRANSMIT_B)));
		a.add(area("Kastori", 5423, ANYWHERE, aliases(5167, 5424),
			p("calquat.kastori", "", FarmingPatchType.CALQUAT, VarbitID.FARMING_TRANSMIT_A),
			p("fruit_tree.kastori", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_B),
			p("flower.kastori", "", FarmingPatchType.FLOWER, VarbitID.FARMING_TRANSMIT_C)));
		// RuneLite "Kourend" 6967: Hosidius (compost E not catalogued).
		Area hosidius = allotmentSite("Hosidius", "hosidius", 6967, ANYWHERE, aliases(6711), "ne", "North East", "sw", "South West");
		List<FarmingPatchDef> hosidiusPatches = new ArrayList<>(hosidius.patches);
		hosidiusPatches.add(p("spirit_tree.hosidius", "", FarmingPatchType.SPIRIT_TREE, VarbitID.FARMING_TRANSMIT_F).at("Hosidius", 6967));
		a.add(new Area("Hosidius", 6967, aliases(6711), ANYWHERE, hosidiusPatches));
		a.add(area("Lletya", 9265, ANYWHERE, aliases(11103), p("fruit_tree.lletya", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Lumbridge", 12851, ANYWHERE, aliases(), p("hops.lumbridge", "", FarmingPatchType.HOPS, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Lumbridge", 12594, ANYWHERE, aliases(12850), p("tree.lumbridge", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Canifis", 13622, ANYWHERE, aliases(13878), p("mushroom.canifis", "", FarmingPatchType.MUSHROOM, VarbitID.FARMING_TRANSMIT_A)));
		a.add(allotmentSite("Port Phasmatys", "morytania", 14391, ANYWHERE, aliases(14390), "nw", "North West", "se", "South East"));
		a.add(area("Port Sarim", 12082, PORT_SARIM, aliases(12083), p("spirit_tree.port_sarim", "", FarmingPatchType.SPIRIT_TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Rimmington", 11570, ANYWHERE, aliases(11826), p("bush.rimmington", "", FarmingPatchType.BUSH, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Seers' Village", 10551, ANYWHERE, aliases(10550), p("hops.seers_village", "", FarmingPatchType.HOPS, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Tai Bwo Wannai", 11056, ANYWHERE, aliases(), p("calquat.tai_bwo_wannai", "", FarmingPatchType.CALQUAT, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Taverley", 11573, ANYWHERE, aliases(11829), p("tree.taverley", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Tree Gnome Village", 9777, ANYWHERE, aliases(10033), p("fruit_tree.tree_gnome_village", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Troll Stronghold", 11321, ANYWHERE, aliases(), p("herb.troll_stronghold", "", FarmingPatchType.HERB, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Varrock", 12854, ANYWHERE, aliases(12853), p("tree.varrock", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Yanille", 10288, ANYWHERE, aliases(), p("hops.yanille", "", FarmingPatchType.HOPS, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Weiss", 11325, ANYWHERE, aliases(), p("herb.weiss", "", FarmingPatchType.HERB, VarbitID.FARMING_TRANSMIT_A)));
		a.add(area("Farming Guild", 5021, ANYWHERE, aliases(), p("hespori.farming_guild", "", FarmingPatchType.HESPORI, VarbitID.FARMING_TRANSMIT_J)));
		// The full 3x3 region area centred on the Farming Guild (big compost N not catalogued).
		a.add(area("Farming Guild", 4922, ANYWHERE, aliases(5177, 5178, 5179, 4921, 4923, 4665, 4666, 4667),
			p("tree.farming_guild", "", FarmingPatchType.TREE, VarbitID.FARMING_TRANSMIT_G),
			p("herb.farming_guild", "", FarmingPatchType.HERB, VarbitID.FARMING_TRANSMIT_E),
			p("bush.farming_guild", "", FarmingPatchType.BUSH, VarbitID.FARMING_TRANSMIT_B),
			p("flower.farming_guild", "", FarmingPatchType.FLOWER, VarbitID.FARMING_TRANSMIT_H),
			p("allotment.farming_guild.n", "North", FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_C),
			p("allotment.farming_guild.s", "South", FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_D),
			p("cactus.farming_guild", "", FarmingPatchType.CACTUS, VarbitID.FARMING_TRANSMIT_F),
			p("spirit_tree.farming_guild", "", FarmingPatchType.SPIRIT_TREE, VarbitID.FARMING_TRANSMIT_A),
			p("fruit_tree.farming_guild", "", FarmingPatchType.FRUIT_TREE, VarbitID.FARMING_TRANSMIT_K),
			p("anima.farming_guild", "", FarmingPatchType.ANIMA, VarbitID.FARMING_TRANSMIT_M),
			p("celastrus.farming_guild", "", FarmingPatchType.CELASTRUS, VarbitID.FARMING_TRANSMIT_L),
			p("redwood.farming_guild", "", FarmingPatchType.REDWOOD, VarbitID.FARMING_TRANSMIT_I)));
		// All of Prifddinas, and all of Prifddinas underground (compost D not catalogued).
		a.add(area("Prifddinas", 13151, ANYWHERE, aliases(12895, 12894, 13150, 12994, 12993, 12737, 12738, 12126, 12127, 13250),
			p("allotment.prifddinas.n", "North", FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_A),
			p("allotment.prifddinas.s", "South", FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_B),
			p("flower.prifddinas", "", FarmingPatchType.FLOWER, VarbitID.FARMING_TRANSMIT_C),
			p("crystal_tree.prifddinas", "", FarmingPatchType.CRYSTAL_TREE, VarbitID.FARMING_TRANSMIT_E)));
		return a;
	}

	private static int[] aliases(int... regions)
	{
		return regions;
	}

	/** An unplaced patch: its area fills in the site name and owning region. */
	private static final class Unplaced
	{
		final String id;
		final String label;
		final FarmingPatchType type;
		final int varbit;

		Unplaced(String id, String label, FarmingPatchType type, int varbit)
		{
			this.id = id;
			this.label = label;
			this.type = type;
			this.varbit = varbit;
		}

		FarmingPatchDef at(String areaName, int region)
		{
			return new FarmingPatchDef(id, areaName, label, type, region, varbit);
		}
	}

	private static Unplaced p(String id, String label, FarmingPatchType type, int varbit)
	{
		return new Unplaced(id, label, type, varbit);
	}

	private static Area area(String name, int region, Bounds bounds, int[] aliases, Unplaced... patches)
	{
		List<FarmingPatchDef> defs = new ArrayList<>();
		for (Unplaced u : patches)
		{
			defs.add(u.at(name, region));
		}
		return new Area(name, region, aliases, bounds, defs);
	}

	/** The standard allotment site: allotments on A and B, flower on C, herb on D (compost E not catalogued). */
	private static Area allotmentSite(String name, String slug, int region, Bounds bounds, int[] aliases,
		String aSlug, String aLabel, String bSlug, String bLabel)
	{
		return area(name, region, bounds, aliases,
			p("allotment." + slug + "." + aSlug, aLabel, FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_A),
			p("allotment." + slug + "." + bSlug, bLabel, FarmingPatchType.ALLOTMENT, VarbitID.FARMING_TRANSMIT_B),
			p("flower." + slug, "", FarmingPatchType.FLOWER, VarbitID.FARMING_TRANSMIT_C),
			p("herb." + slug, "", FarmingPatchType.HERB, VarbitID.FARMING_TRANSMIT_D));
	}
}
