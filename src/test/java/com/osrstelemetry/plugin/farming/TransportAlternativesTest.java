package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.poh.PohKnowledge;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The live route reports of 2026-10-22 (canonical §28.14), as whole journeys chosen from the shared catalogue and walks, never
 * per-patch preferences: Etceteria by the ring of wealth (sea boots and the sailor, with its 55 Agility stepping stone, the
 * alternative); Kastori by its own whistle landing once built; Auburnvale tree by the Quetzacalli Gorge landing and the Mountain
 * Guide. A house of Josh's facilities (Draynor Manor, Kourend and Lassar in the nexus; no Catherby there).
 */
public class TransportAlternativesTest
{
	private static TransportCatalog catalog;
	private static final FarmingPatchCatalog PATCHES = FarmingPatchCatalog.standard();
	private static final PohKnowledge HOUSE = PohKnowledge.empty().withVisit(new HashSet<>(Arrays.asList("poh.nexus", "poh.portal.watchtower",
		"poh.portal.civitas", "poh.portal.lunar_isle", "poh.portal.kharyrll", "poh.portal.arceuus_library", "poh.portal.barrows", "poh.portal.falador",
		"poh.jewellery_box.ornate", "poh.portal.kourend", "poh.portal.lumbridge", "poh.portal.catherby", "poh.portal.varrock",
		"poh.portal.troll_stronghold", "poh.fairy_ring", "poh.portal.camelot", "poh.portal.weiss", "poh.portal.ardougne", PohKnowledge.NEXUS_READ,
		"poh.nexus.draynor_manor", "poh.nexus.kourend", "poh.nexus.lassar")), true, 1000);

	@BeforeClass
	public static void load()
	{
		com.osrstelemetry.plugin.navigation.AccountRouter.useExecutor(Runnable::run);
		catalog = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	}

	private static AccountCapability account(boolean landingsBuilt)
	{
		// Ring of wealth (5), a quetzal whistle and house tablets carried, Fremennik sea boots 4 worn; easy Fremennik diary done, no boots teleport used today.
		return AccountFixtures.maxed().inventory(AccountFixtures.items(11980, 1, 33120, 1, 8013, 10, 995, 100000)).worn(AccountFixtures.items(13132, 1))
			.varp(4182, landingsBuilt ? 0xFFFF : 0).varbit(4491, 1).varbit(5005, 0).build();
	}

	private static FarmingPreparation.SitePlan stop(String patchId, FarmingRunType type, AccountCapability cap)
	{
		EvidenceSource ev = HOUSE.evidence();
		List<TransportAvailability> av = TransportEvaluator.evaluateAll(catalog, cap, ev, AccountFixtures.NOW);
		Map<String, String> plans = new LinkedHashMap<>();
		plans.put(patchId, FarmingRunIntent.REPLANT_ANY);
		FarmingRunIntent intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "alt", type, AccountFixtures.NOW, AccountFixtures.NOW,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options("NONE", false), Collections.emptyMap()).withPreparing(true);
		return FarmingPreparation.plan(intent, PATCHES, cap, av, ev).getSites().get(0);
	}

	private static String legs(FarmingPreparation.SitePlan s)
	{
		return s.getLegs().stream().map(l -> l.text).collect(Collectors.joining(" | "));
	}

	@Test
	public void etceteria_byTheRingOfWealth_withTheSeaBootsAndTheStepppingStone_asTheAlternative()
	{
		AccountCapability cap = account(false);
		FarmingPreparation.SitePlan s = stop("bush.etceteria", FarmingRunType.BUSH, cap);
		assertEquals("ring_of_wealth.miscellania", s.getTransport().getTransport().id);
		assertTrue(s.getAlternatives().stream().anyMatch(a -> a.getTransport().id.startsWith("fremennik_sea_boots.")));
		com.osrstelemetry.plugin.navigation.AccountRouter.Key key = com.osrstelemetry.plugin.navigation.AccountRouter.bundled().planning(cap);
		com.osrstelemetry.plugin.navigation.RouteTable.Walk boots = FarmingTravel.walk(PATCHES, "bush.etceteria", catalog.get("fremennik_sea_boots.rellekka_4"),
			key);
		String steps = boots.getTransitions().stream().map(t -> t.instruction()).collect(Collectors.joining(" | "));
		assertTrue(steps, steps.contains("Take the sailor to Miscellania") && steps.contains("Cross the stepping stone"));
		assertTrue("the shortcut's requirement is the account's own: " + boots.getRequirement(), boots.getRequirement().toString().contains("55 Agility"));
		int ring = FarmingTravel.afterTeleport(PATCHES, "bush.etceteria", catalog.get("ring_of_wealth.miscellania"), key);
		int sea = FarmingTravel.afterTeleport(PATCHES, "bush.etceteria", catalog.get("fremennik_sea_boots.rellekka_4"), key);
		assertTrue("the ring's walk (" + ring + ") beats the boots' boat and stone (" + sea + ")", ring < sea);
	}

	@Test
	public void kastori_byItsOwnWhistleLanding_onlyOnceBuilt()
	{
		assertEquals("quetzal_whistle.kastori", stop("flower.kastori", FarmingRunType.ALLOTMENT, account(true)).getTransport().getTransport().id);
		FarmingPreparation.SitePlan unbuilt = stop("flower.kastori", FarmingRunType.ALLOTMENT, account(false));
		assertNotEquals("an unbuilt landing is never offered", "quetzal_whistle.kastori", unbuilt.getTransport().getTransport().id);
		assertTrue(legs(unbuilt), legs(unbuilt).contains("achilka"));
	}

	@Test
	public void auburnvaleTree_byTheGorgeLanding_andTheMountainGuide()
	{
		FarmingPreparation.SitePlan s = stop("tree.auburnvale", FarmingRunType.TREE, account(true));
		assertEquals("quetzal_whistle.quetzacalli_gorge", s.getTransport().getTransport().id);
		assertTrue(legs(s), legs(s).contains("Mountain Guide"));
		assertFalse("not through the house and the renu", s.getTransport().getTransport().id.startsWith("poh."));
	}
}
