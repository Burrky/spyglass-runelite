package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The one current action (canonical §28.13), worded for each surface from shared rules: a short label written for under
 * the player (never cut from the guide line), a fuller guide line, the target to highlight, across every patch family and
 * every kind of travel step.
 */
public class FarmingWordingTest
{
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;

	private static FarmingRunEvaluator.PatchProgress p(String id, FarmingRunEvaluator.Detail d, FarmingProduce crop)
	{
		FarmingRunEvaluator.Outcome o = d == FarmingRunEvaluator.Detail.REPLANTED || d == FarmingRunEvaluator.Detail.PLANTED_UNSEEN
			? FarmingRunEvaluator.Outcome.DONE : FarmingRunEvaluator.Outcome.PENDING;
		return new FarmingRunEvaluator.PatchProgress(id, CATALOG.patch(id), o, d, null, crop, false);
	}

	private static FarmingRunIntent intent(FarmingRunType type, String... ids)
	{
		Map<String, String> planned = new LinkedHashMap<>();
		for (String id : ids)
		{
			planned.put(id, FarmingRunIntent.REPLANT_ANY);
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-wording", type, NOW, NOW, FarmingRunIntent.State.ACTIVE, null, null,
			Arrays.asList(ids), Collections.emptyList(), Collections.emptyList(), planned, new FarmingRunIntent.Options(null, true), Collections.emptyMap());
	}

	private static FarmingAction act(FarmingRunEvaluator.PatchProgress p, FarmingPreferences prefs)
	{
		return FarmingWording.patch(p, null, prefs, false, false, null, 3000, 3000, 0);
	}

	@Test
	public void everyPatchAction_hasItsOwnShortLabel_neverACutSentence()
	{
		FarmingPreferences prefs = FarmingPreferences.defaults();
		Object[][] cases = {
			{"tree.falador", FarmingRunEvaluator.Detail.TO_CHECK_HEALTH, "Check health", "Check the tree's health"},
			{"herb.weiss", FarmingRunEvaluator.Detail.TO_HARVEST, "Harvest", null},
			{"herb.weiss", FarmingRunEvaluator.Detail.TO_PLANT, "Plant seeds", null},
			{"tree.falador", FarmingRunEvaluator.Detail.TO_PLANT, "Plant sapling", "Plant a sapling"},
			{"mushroom.canifis", FarmingRunEvaluator.Detail.TO_PLANT, "Plant spores", null},
			{"herb.weiss", FarmingRunEvaluator.Detail.TO_CLEAR, "Clear dead plant", null},
			{"herb.weiss", FarmingRunEvaluator.Detail.TO_CURE, "Cure plant", null},
			{"tree.falador", FarmingRunEvaluator.Detail.STUMP_TO_CLEAR, "Dig up stump", "Dig up the stump with a spade"},
			{"hespori.farming_guild", FarmingRunEvaluator.Detail.TO_HARVEST, "Hespori grown", null},
		};
		for (Object[] c : cases)
		{
			FarmingAction a = act(p((String) c[0], (FarmingRunEvaluator.Detail) c[1], null), prefs);
			assertEquals(c[0] + " " + c[1], c[2], a.getLabel());
			if (c[3] != null)
			{
				assertEquals(c[3], a.getGuide());
			}
			assertFalse("no prefix like 'Tree:'", a.getLabel().contains(":") || a.getGuide().contains(":"));
			assertFalse("never cut", a.getLabel().endsWith("…"));
			assertEquals(FarmingAction.Stage.ACT, a.getStage());
			assertEquals("the patch is the target", FarmingAction.Target.Kind.PATCH, a.getTarget().kind);
		}
	}

	@Test
	public void clearing_followsTheFamily_andThePreference_paidRemovalPointsAtTheGardener()
	{
		FarmingPreferences pay = FarmingPreferences.defaults().withTreeClearing(FarmingPreferences.TreeClearing.PAY);
		FarmingPreferences chop = FarmingPreferences.defaults().withTreeClearing(FarmingPreferences.TreeClearing.CHOP);
		FarmingAction hardwood = act(p("hardwood.anglers_retreat", FarmingRunEvaluator.Detail.TO_REPLACE, null), pay);
		assertEquals("Pay for removal", hardwood.getLabel());
		assertTrue(hardwood.getGuide(), hardwood.getGuide().startsWith("Pay the gardener to remove the") && hardwood.getGuide().contains("200 coins"));
		assertEquals(FarmingAction.Target.Kind.NPC, hardwood.getTarget().kind);
		assertEquals("the gardener of this patch: offers Pay, nearest the patch", "Pay", hardwood.getTarget().action);
		assertNotEquals("Pay for removal", act(p("hardwood.anglers_retreat", FarmingRunEvaluator.Detail.TO_REPLACE, null), chop).getLabel());

		FarmingAction redwood = act(p("redwood.farming_guild", FarmingRunEvaluator.Detail.TO_REPLACE, null), chop);
		assertEquals("a redwood is only removed by paying, whatever the preference", "Pay for removal", redwood.getLabel());
		assertTrue(redwood.getGuide(), redwood.getGuide().contains("Alexandra") && redwood.getGuide().contains("2,000"));

		for (String id : new String[]{"calquat.kastori", "spirit_tree.port_sarim", "celastrus.farming_guild", "crystal_tree.prifddinas"})
		{
			FarmingAction a = act(p(id, FarmingRunEvaluator.Detail.TO_REPLACE, null), pay);
			assertNotEquals(id + ": no gardener service invented", "Pay for removal", a.getLabel());
			assertEquals(id, FarmingAction.Target.Kind.PATCH, a.getTarget().kind);
		}
	}

	@Test
	public void protection_isAStepAfterPlanting_untilTheGameConfirmsThePayment()
	{
		FarmingPreferences prefs = FarmingPreferences.defaults().withProtect(FarmingRunType.HARDWOOD, true);
		FarmingRunIntent run = intent(FarmingRunType.HARDWOOD, "hardwood.anglers_retreat");
		FarmingRunEvaluator.PatchProgress planted = p("hardwood.anglers_retreat", FarmingRunEvaluator.Detail.REPLANTED, FarmingProduce.TEAK);
		assertTrue(FarmingWording.protectionPending(planted, run, prefs));
		FarmingAction a = FarmingWording.patch(planted, run, prefs, true, false, null, 1, 2, 0);
		assertEquals("Pay for protection", a.getLabel());
		assertTrue(a.getGuide(), a.getGuide().startsWith("Pay the gardener to protect this crop") && a.getGuide().contains("15"));
		assertEquals(FarmingAction.Target.Kind.NPC, a.getTarget().kind);
		assertFalse("the setting alone is not proof; the game's message is",
			FarmingWording.protectionPending(planted, run.withProtected("hardwood.anglers_retreat"), prefs));
		assertFalse("not chosen: no step", FarmingWording.protectionPending(planted, run, FarmingPreferences.defaults()));
	}

	@Test
	public void travelSteps_nameTheirDestinationAndCode()
	{
		FarmingAction ring = FarmingWording.hop(true, "ALQ", null, 3200, 3200, 0);
		assertEquals("Fairy ring → ALQ", ring.getLabel());
		assertEquals("Use fairy ring ALQ", ring.getGuide().replaceAll(" \\(.*\\)$", ""));
		FarmingAction walk = FarmingWording.walkToEntrance(true, 17, 3200, 3200, 0);
		assertEquals("Walk to fairy ring · 17 tiles", walk.getLabel());
		FarmingAction patch = FarmingWording.walkToPatch(Collections.singleton(4774), 42);
		assertEquals("Walk to patch · 42 tiles", patch.getLabel());
		assertEquals("counts down", "Walk to patch · 41 tiles", patch.withTiles(41).getLabel());
		assertEquals("Walk to patch · 1 tile", patch.withTiles(1).getLabel());

		TransportDef xeric = TRANSPORTS.get("xerics_talisman.xerics_glade");
		FarmingAction x = FarmingWording.teleport(xeric, null, false);
		assertEquals(FarmingAction.Stage.TELEPORT, x.getStage());
		assertTrue(x.getLabel(), x.getLabel().contains("→") && x.getLabel().contains("Xeric's Glade"));

		TransportDef house = TRANSPORTS.get("poh.tab.portal.weiss");
		FarmingAction outside = FarmingWording.teleport(house, null, false);
		assertTrue("before the house, the step is getting there: " + outside.getLabel(), outside.getLabel().toLowerCase().contains("house"));
		FarmingAction inside = FarmingWording.teleport(house, null, true);
		assertEquals("the portal, and only the portal", "Use the Weiss portal in your house", inside.getGuide());
		assertEquals(FarmingAction.Target.Kind.OBJECT, inside.getTarget().kind);
		assertFalse("the nexus is not this route's facility", inside.getTarget().name.contains("nexus"));
		assertFalse(inside.getTarget().ids.isEmpty());

		// Live finding 2026-10-22: Draynor Manor is in the nexus, not a portal; the route and its words say so.
		FarmingAction nexus = FarmingWording.teleport(TRANSPORTS.get("poh.tab.nexus.draynor_manor"), null, true);
		assertEquals("Nexus → Draynor Manor", nexus.getLabel());
		assertEquals("Use your portal nexus: Draynor Manor", nexus.getGuide());
		assertEquals("Portal nexus", nexus.getTarget().name);

		// A diary portal's right-click destination names the portal it is on.
		FarmingAction ge = FarmingWording.teleport(TRANSPORTS.get("poh.tab.portal_alt.grand_exchange"), null, true);
		assertEquals("Right-click the Varrock portal in your house: Grand Exchange", ge.getGuide());
		assertFalse(ge.getTarget().ids.isEmpty());
	}
}
