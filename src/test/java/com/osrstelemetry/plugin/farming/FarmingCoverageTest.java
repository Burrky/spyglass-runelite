package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * The expanded catalogue and decoder: every RuneLite area (except compost,
 * grapes and coral), safe shared regions, stable ids, and per-type decoding of
 * the object states RuneLite's comments name.
 */
public class FarmingCoverageTest
{
	private final FarmingPatchCatalog catalog = FarmingPatchCatalog.standard();

	private static PatchReading decode(FarmingPatchType type, int raw)
	{
		return FarmingDecoder.decode(type, raw);
	}

	@Test
	public void phaseBIds_areAllStillPresent_withTheirOriginalIdentity()
	{
		String[] original = {
			"allotment.falador.nw", "allotment.falador.se", "herb.falador",
			"allotment.catherby.n", "allotment.catherby.s", "herb.catherby",
			"allotment.ardougne.n", "allotment.ardougne.s", "herb.ardougne",
			"allotment.morytania.nw", "allotment.morytania.se", "herb.morytania",
			"tree.falador", "tree.taverley", "tree.varrock", "tree.lumbridge", "tree.gnome_stronghold",
		};
		for (String id : original)
		{
			assertNotNull(id, catalog.patch(id));
		}
		assertEquals(12083, catalog.patch("herb.falador").getOwningRegionId());
		assertEquals(14391, catalog.patch("herb.morytania").getOwningRegionId());
		assertEquals(9781, catalog.patch("tree.gnome_stronghold").getOwningRegionId());
	}

	@Test
	public void everyDecodedType_hasCataloguedPatches_andTheNormalRunSitesExist()
	{
		Set<FarmingPatchType> seen = EnumSet.noneOf(FarmingPatchType.class);
		for (FarmingPatchDef p : catalog.allPatches())
		{
			seen.add(p.getType());
		}
		assertEquals(EnumSet.allOf(FarmingPatchType.class), seen);
		for (String id : new String[]{"herb.hosidius", "herb.farming_guild", "herb.harmony", "herb.troll_stronghold", "herb.weiss",
			"herb.civitas", "allotment.prifddinas.n", "fruit_tree.brimhaven", "fruit_tree.catherby", "fruit_tree.lletya",
			"bush.rimmington", "hops.yanille", "hardwood.fossil_island.west", "spirit_tree.port_sarim", "calquat.tai_bwo_wannai",
			"celastrus.farming_guild", "redwood.farming_guild", "anima.farming_guild", "hespori.farming_guild",
			"crystal_tree.prifddinas", "seaweed.fossil_island.n", "mushroom.canifis", "belladonna.draynor_manor", "cactus.al_kharid"})
		{
			assertNotNull(id, catalog.patch(id));
		}
		assertTrue(catalog.allPatches().size() > 80);
	}

	@Test
	public void areasSharingARegion_neverReadTheSameVarbitAtOneLocation()
	{
		// Sample every tile of every catalogued region, on planes 0 and 1: a resolved context never
		// holds two patches on one varbit.
		Set<Integer> regions = new HashSet<>();
		for (FarmingPatchCatalog.Area area : catalog.areas())
		{
			regions.add(area.owningRegionId);
			for (int r : area.aliasRegionIds)
			{
				regions.add(r);
			}
		}
		for (int region : regions)
		{
			int baseX = (region >> 8) << 6;
			int baseY = (region & 0xFF) << 6;
			for (int dx = 0; dx < 64; dx += 1)
			{
				for (int dy = 0; dy < 64; dy += 1)
				{
					for (int plane = 0; plane <= 1; plane++)
					{
						TransmissionContext ctx = catalog.resolve(new FarmingLocation(baseX + dx, baseY + dy, plane));
						Set<Integer> varbits = new HashSet<>();
						for (FarmingPatchDef p : ctx.getPatches())
						{
							assertTrue(region + " " + dx + "," + dy + " duplicates varbit " + p.getVarbit() + " in " + ctx, varbits.add(p.getVarbit()));
						}
					}
				}
			}
		}
	}

	@Test
	public void boundsRules_match_RuneLite()
	{
		assertEquals("hardwood.fossil_island.east;hardwood.fossil_island.middle;hardwood.fossil_island.west;",
			catalog.resolve(new FarmingLocation(3715, 3835, 0)).getKey());
		assertTrue("not upstairs on Fossil Island", catalog.resolve(new FarmingLocation(3715, 3835, 1)).isEmpty());
		assertTrue("the early-varbit ladder tile", catalog.resolve(new FarmingLocation(3729, 3831, 0)).isEmpty());
		assertEquals(catalog.resolve(new FarmingLocation(1240, 3730, 0)).getKey(),
			catalog.resolve(new FarmingLocation(1250, 3740, 0)).getKey());
	}

	@Test
	public void ids_followTheTypeSiteSlugScheme_andAreUnique()
	{
		Map<String, FarmingPatchType> prefixes = new HashMap<>();
		prefixes.put("herb", FarmingPatchType.HERB);
		prefixes.put("allotment", FarmingPatchType.ALLOTMENT);
		prefixes.put("tree", FarmingPatchType.TREE);
		prefixes.put("flower", FarmingPatchType.FLOWER);
		prefixes.put("bush", FarmingPatchType.BUSH);
		prefixes.put("fruit_tree", FarmingPatchType.FRUIT_TREE);
		prefixes.put("hops", FarmingPatchType.HOPS);
		prefixes.put("hardwood", FarmingPatchType.HARDWOOD_TREE);
		prefixes.put("redwood", FarmingPatchType.REDWOOD);
		prefixes.put("spirit_tree", FarmingPatchType.SPIRIT_TREE);
		prefixes.put("anima", FarmingPatchType.ANIMA);
		prefixes.put("belladonna", FarmingPatchType.BELLADONNA);
		prefixes.put("cactus", FarmingPatchType.CACTUS);
		prefixes.put("seaweed", FarmingPatchType.SEAWEED);
		prefixes.put("calquat", FarmingPatchType.CALQUAT);
		prefixes.put("celastrus", FarmingPatchType.CELASTRUS);
		prefixes.put("crystal_tree", FarmingPatchType.CRYSTAL_TREE);
		prefixes.put("mushroom", FarmingPatchType.MUSHROOM);
		prefixes.put("hespori", FarmingPatchType.HESPORI);
		for (FarmingPatchDef p : catalog.allPatches())
		{
			String prefix = p.getPatchId().substring(0, p.getPatchId().indexOf('.'));
			assertEquals(p.getPatchId(), prefixes.get(prefix), p.getType());
			assertTrue(p.getPatchId(), p.getPatchId().matches("[a-z_]+\\.[a-z_]+(\\.[a-z_]+)?"));
		}
	}

	// -- decoding ----------------------------------------------------------------

	@Test
	public void checkHealthObjects_decodeAsCheckHealth_forEveryHealthCheckType()
	{
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.BUSH, 250).getCondition());
		assertEquals(FarmingProduce.REDBERRIES, decode(FarmingPatchType.BUSH, 250).getProduce());
		assertEquals(5, decode(FarmingPatchType.BUSH, 250).getStage());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.FRUIT_TREE, 34).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.HARDWOOD_TREE, 15).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.REDWOOD, 37).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.SPIRIT_TREE, 44).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.CACTUS, 31).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.CALQUAT, 34).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.CELASTRUS, 13).getCondition());
		assertEquals(PatchCondition.CHECK_HEALTH, decode(FarmingPatchType.CRYSTAL_TREE, 14).getCondition());
	}

	@Test
	public void harvestableStates_andStumps()
	{
		PatchReading apples = decode(FarmingPatchType.FRUIT_TREE, 20);
		assertEquals(PatchCondition.HARVESTABLE, apples.getCondition());
		assertEquals(PatchSubState.STANDING, apples.getSubState());
		assertEquals(6, apples.getStage());
		assertEquals(PatchSubState.STUMP, decode(FarmingPatchType.FRUIT_TREE, 33).getSubState());
		assertEquals(PatchSubState.STUMP, decode(FarmingPatchType.HARDWOOD_TREE, 17).getSubState());
		assertEquals(PatchCondition.HARVESTABLE, decode(FarmingPatchType.BUSH, 14).getCondition());
		assertEquals(PatchCondition.HARVESTABLE, decode(FarmingPatchType.HESPORI, 7).getCondition());
		assertEquals(PatchCondition.HARVESTABLE, decode(FarmingPatchType.SEAWEED, 8).getCondition());
	}

	@Test
	public void grown_isAFullyGrownSpiritTree_orAScarecrow()
	{
		PatchReading spirit = decode(FarmingPatchType.SPIRIT_TREE, 20);
		assertEquals(PatchCondition.GROWN, spirit.getCondition());
		assertEquals(FarmingProduce.SPIRIT_TREE, spirit.getProduce());
		assertEquals(PatchCondition.GROWING, decode(FarmingPatchType.SPIRIT_TREE, 19).getCondition());
		PatchReading scarecrow = decode(FarmingPatchType.FLOWER, 36);
		assertEquals(PatchCondition.GROWN, scarecrow.getCondition());
		assertEquals(FarmingProduce.SCARECROW, scarecrow.getProduce());
	}

	@Test
	public void weeds_empty_disease_death_andUnknownGaps()
	{
		assertEquals(PatchCondition.EMPTY, decode(FarmingPatchType.HOPS, 3).getCondition());
		assertEquals(PatchCondition.WEEDS, decode(FarmingPatchType.HOPS, 0).getCondition());
		assertEquals(PatchCondition.DEAD, decode(FarmingPatchType.FLOWER, 201).getCondition());
		assertEquals(FarmingProduce.MARIGOLD, decode(FarmingPatchType.FLOWER, 201).getProduce());
		assertEquals(PatchCondition.DISEASED, decode(FarmingPatchType.SPIRIT_TREE, 21).getCondition());
		assertEquals(PatchCondition.GROWING, decode(FarmingPatchType.ANIMA, 8).getCondition());
		assertEquals(FarmingProduce.ATTAS, decode(FarmingPatchType.ANIMA, 8).getProduce());
		// RuneLite maps no state here: never treated as empty.
		assertEquals(PatchCondition.UNKNOWN, decode(FarmingPatchType.HERB, 220).getCondition());
		assertEquals(PatchCondition.UNKNOWN, decode(FarmingPatchType.REDWOOD, 56).getCondition());
		assertEquals(PatchCondition.UNKNOWN, decode(FarmingPatchType.CRYSTAL_TREE, 16).getCondition());
	}

	@Test
	public void everyProduceOfAType_belongsToThatType_andReachesItsReadyStage()
	{
		for (FarmingPatchType type : FarmingPatchType.values())
		{
			List<Integer> ready = new ArrayList<>();
			for (int raw = 0; raw < 256; raw++)
			{
				PatchReading r = decode(type, raw);
				if (r.getProduce() != null && r.getProduce() != FarmingProduce.WEEDS)
				{
					assertEquals(type + " " + raw, type, r.getProduce().getPatchType());
				}
				if (r.getCondition() == PatchCondition.GROWING && r.getProduce() != null && r.getProduce() != FarmingProduce.WEEDS)
				{
					assertTrue(type + " " + raw + " growing at stage " + r.getStage(), r.getStage() < r.getProduce().getReadyStage()
						|| type == FarmingPatchType.ANIMA);
				}
				if (r.getCondition() == PatchCondition.HARVESTABLE || r.getCondition() == PatchCondition.CHECK_HEALTH)
				{
					ready.add(raw);
				}
			}
			if (type != FarmingPatchType.ANIMA)
			{
				assertFalse(type + " has a ready state", ready.isEmpty());
			}
		}
	}

	@Test
	public void healthCheckFlags_matchRuneLite()
	{
		List<FarmingPatchType> checked = new ArrayList<>();
		for (FarmingPatchType t : FarmingPatchType.values())
		{
			if (t.isHealthCheckRequired())
			{
				checked.add(t);
			}
		}
		assertEquals(Arrays.asList(FarmingPatchType.TREE, FarmingPatchType.BUSH, FarmingPatchType.FRUIT_TREE, FarmingPatchType.HARDWOOD_TREE,
			FarmingPatchType.REDWOOD, FarmingPatchType.SPIRIT_TREE, FarmingPatchType.CACTUS, FarmingPatchType.CALQUAT, FarmingPatchType.CELASTRUS,
			FarmingPatchType.CRYSTAL_TREE, FarmingPatchType.HESPORI), checked);
		assertNull(FarmingProduce.WEEDS.getPatchType());
	}
}
