package com.osrstelemetry.plugin.skills.smithing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The recipe table as data: every recipe unique and resolvable, every output a
 * Smithing resource and every input named (telemetry sends names, never
 * guesses), ratios as the game has them (Blast Furnace coal halved, anvil
 * multi-output counts, keel parts), CAN MAKE from the limiting input, and the
 * definition's History rows per recipe (banking shown once per item, never
 * split by a guess).
 */
public class SmithingRecipesTest
{
	@Test
	public void everyRecipe_uniqueResolvable_outputsAreResources_inputsAreNamed()
	{
		List<SmithingRecipe> all = SmithingRecipes.all();
		assertEquals(222, all.size());
		Set<String> keys = new HashSet<>();
		for (SmithingRecipe r : all)
		{
			assertTrue(r.getKey(), keys.add(r.getKey()));
			assertEquals(r, SmithingRecipes.recipe(r.getKey()));
			assertNotNull(r.getKey(), SmithingCompanionDefinition.INSTANCE.resource(r.getOutputItem()));
			assertTrue(r.getKey(), SmithingCompanionDefinition.INSTANCE.methodProduces(r.getKey(), r.getOutputItem()));
			assertEquals(r.getName(), SmithingCompanionDefinition.INSTANCE.methodLabel(r.getKey()));
			for (SmithingRecipe.Input in : r.getInputs())
			{
				assertNotNull(r.getKey() + " " + in.getItemId(), SmithingCompanionDefinition.INSTANCE.inputName(in.getItemId()));
				assertTrue(in.getPerAction() > 0);
			}
			assertTrue(r.getOutputPerAction() > 0);
		}
		assertNull("an output is not an input name unless it is one", SmithingCompanionDefinition.INSTANCE.inputName(ItemID.RUNE_PLATEBODY));
		assertEquals("Coal", SmithingCompanionDefinition.INSTANCE.inputName(ItemID.COAL));
	}

	@Test
	public void ratios_asTheGameHasThem()
	{
		assertEquals(2, SmithingRecipes.recipe("smelt_steel").input(ItemID.COAL).getPerAction());
		assertEquals(1, SmithingRecipes.recipe("bf_steel").input(ItemID.COAL).getPerAction());
		assertEquals(8, SmithingRecipes.recipe("smelt_runite").input(ItemID.COAL).getPerAction());
		assertEquals(4, SmithingRecipes.recipe("bf_runite").input(ItemID.COAL).getPerAction());
		assertEquals(2, SmithingRecipes.recipe("smelt_lead").input(ItemID.LEAD_ORE).getPerAction());
		assertEquals(2, SmithingRecipes.recipe("smelt_cupronickel").input(ItemID.COPPER_ORE).getPerAction());
		assertEquals(ItemID.NICKEL_ORE, SmithingRecipes.recipe("smelt_cupronickel").primary().getItemId());
		assertNull("no Blast Furnace lovakite or blurite", SmithingRecipes.recipe("bf_lovakite"));
		assertNull(SmithingRecipes.recipe("bf_blurite"));
		assertEquals(5, SmithingRecipes.recipe("rune_platebody").primary().getPerAction());
		assertEquals(3, SmithingRecipes.recipe("adamant_warhammer").primary().getPerAction());
		assertEquals(10, SmithingRecipes.recipe("rune_dart_tips").getOutputPerAction());
		assertEquals(15, SmithingRecipes.recipe("steel_nails").getOutputPerAction());
		assertEquals(15, SmithingRecipes.recipe("mithril_arrowtips").getOutputPerAction());
		assertEquals(10, SmithingRecipes.recipe("iron_bolts_unf").getOutputPerAction());
		assertEquals(5, SmithingRecipes.recipe("bronze_javelin_heads").getOutputPerAction());
		assertEquals(5, SmithingRecipes.recipe("rune_knives").getOutputPerAction());
		assertEquals(5, SmithingRecipes.recipe("rune_keel_parts").primary().getPerAction());
		assertEquals(4, SmithingRecipes.recipe("cannonball_steel").getOutputPerAction());
		assertEquals(ItemID.MCANNONBALL, SmithingRecipes.recipe("cannonball_steel").getOutputItem());
		assertEquals(15, SmithingRecipes.recipe("dragon_nails").getOutputPerAction());
		assertEquals(3, SmithingRecipes.producing(ItemID.STEEL_BAR).size());
		assertEquals(SmithingProcess.BLAST_FURNACE, SmithingRecipes.producing(ItemID.STEEL_BAR, SmithingProcess.BLAST_FURNACE).getProcess());
	}

	@Test
	public void canMake_limitingInput_catalystsAndBag_neverAtTheBlastFurnace()
	{
		Map<Integer, Long> inv = new HashMap<>();
		inv.put(ItemID.IRON_ORE, 83L);
		inv.put(ItemID.COAL, 142L);
		assertEquals(71L, SmithingRecipes.recipe("smelt_steel").canMake(inv, 0, 0));
		assertEquals(83L, SmithingRecipes.recipe("smelt_steel").canMake(inv, 0, 27));
		assertEquals("catalysts halve the coal of that many bars", 75L, SmithingRecipes.recipe("smelt_steel").canMake(inv, 8, 0));
		assertEquals(83L, SmithingRecipes.recipe("bf_steel").canMake(inv, 0, 0));
		Map<Integer, Long> bars = Collections.singletonMap(ItemID.RUNITE_BAR, 13L);
		assertEquals("13 bars -> 130 dart tips", 130L, SmithingRecipes.recipe("rune_dart_tips").canMake(bars, 0, 0));
		assertEquals(2L, SmithingRecipes.recipe("rune_platebody").canMake(bars, 0, 0));
		assertEquals(0L, SmithingRecipes.recipe("rune_platebody").canMake(Collections.<Integer, Long>emptyMap(), 0, 0));
	}

	@Test
	public void historyRows_perRecipe_bankingOncePerItem_neverSplit()
	{
		Map<Integer, Long> banked = Collections.singletonMap(ItemID.STEEL_BAR, 300L);
		Map<String, Long> millis = new LinkedHashMap<>();
		millis.put("smelt_steel", 1_800_000L);
		millis.put("bf_steel", 3_600_000L);
		Map<String, Long> occ = new LinkedHashMap<>();
		occ.put("made:smelt_steel", 100L);
		occ.put("made:bf_steel", 900L);
		occ.put("in:bf_steel:" + ItemID.COAL, 880L);
		List<SkillCompanionDefinition.HistoryRow> rows = SmithingCompanionDefinition.INSTANCE.historyRows(new SkillCompanionDefinition.SessionTotals(
			Collections.singletonMap(ItemID.STEEL_BAR, 1_000L), banked, null, millis, occ, 5_400_000L, 0L));
		assertEquals("Steel bars (BF)", rows.get(0).getLabel());
		assertEquals("900 smelted • 900/hr", rows.get(0).getValue());
		assertEquals("  Banked (all steel bars)", rows.get(1).getLabel());
		assertEquals("no per-recipe rate for a shared item", "300", rows.get(1).getValue());
		assertEquals("  Coal", rows.get(2).getLabel());
		assertEquals("Steel bars", rows.get(3).getLabel());
		assertEquals("100 smelted • 200/hr", rows.get(3).getValue());
		for (SkillCompanionDefinition.HistoryRow r : rows)
		{
			assertTrue("banked shown once", !r.getLabel().contains("Banked") || r == rows.get(1));
		}
		assertEquals(Arrays.asList("Steel bars (BF)", "900 smelted", "+1 recipe"),
			SmithingCompanionDefinition.INSTANCE.historyRowFacts(new SkillCompanionDefinition.SessionTotals(null, null, null, millis, occ, 0L, 0L)));
	}
}
