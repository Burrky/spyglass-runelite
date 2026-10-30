package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportDef;
import com.osrstelemetry.plugin.account.EvidenceSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Farming's destinations and domain evidence for the generic transport core. */
public class FarmingTravelTest
{
	private static final long T = 1_800_000_000_000L;
	private static final String PORT_SARIM = "spirit_tree.port_sarim";
	private static final String KEY = FarmingTravel.EVIDENCE_PREFIX + "port_sarim";

	private static int raw(PatchCondition c)
	{
		return FarmingKnowledgeFixtures.raw(FarmingPatchType.SPIRIT_TREE, FarmingProduce.SPIRIT_TREE, c, -1);
	}

	private static Known known(Map<String, PatchKnowledge> k)
	{
		return FarmingTravel.evidence(k, T + 7_200_000L).get(KEY);
	}

	@Test
	public void everyDestination_namesARealPatch_andARealTransport_andEveryHerbPatchIsCovered()
	{
		TransportCatalog catalog = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
		FarmingPatchCatalog patches = FarmingPatchCatalog.standard();
		for (Map.Entry<String, List<String>> e : FarmingTravel.destinations().entrySet())
		{
			assertNotNull("patch " + e.getKey(), patches.patch(e.getKey()));
			for (String id : e.getValue())
			{
				assertNotNull(e.getKey() + " -> " + id, catalog.get(id));
			}
		}
		Set<String> herbs = new HashSet<>();
		for (FarmingPatchDef p : patches.allPatches())
		{
			if (p.getType() == FarmingPatchType.HERB)
			{
				herbs.add(p.getPatchId());
			}
		}
		assertTrue("every herb patch has transports: " + herbs, FarmingTravel.destinations().keySet().containsAll(herbs));
		for (TransportDef t : catalog.all())
		{
			for (com.osrstelemetry.plugin.account.RequirementSet.EvidenceReq r : com.osrstelemetry.plugin.account.RequirementSet.list(t.requirements().evidence))
			{
				if (r.key.startsWith("poh."))
				{
					assertTrue("a facility the house observer records: " + r.key,
						com.osrstelemetry.plugin.account.poh.PohKnowledge.knownKeys().contains(r.key));
					continue;
				}
				if (r.key.matches("varp:\\d+&\\d+|varbit:\\d+[=<>]\\d+"))
				{
					continue; // a game variable read live (a quetzal landing built, sea boots teleports used today)
				}
				assertTrue(r.key, r.key.startsWith(FarmingTravel.EVIDENCE_PREFIX));
				assertNotNull("evidence names a real patch: " + r.key,
					patches.patch("spirit_tree." + r.key.substring(FarmingTravel.EVIDENCE_PREFIX.length())));
			}
		}
	}

	@Test
	public void spiritTreeEvidence_comesFromObservationOnly()
	{
		Map<String, PatchKnowledge> k = new HashMap<>();
		assertEquals("never observed", Known.UNKNOWN, known(k));
		FarmingKnowledgeFixtures.see(k, PORT_SARIM, raw(PatchCondition.GROWING), T);
		assertEquals("growing when last seen: maybe grown by now, not assumed", Known.UNKNOWN, known(k));
		FarmingKnowledgeFixtures.change(k, PORT_SARIM, raw(PatchCondition.GROWING), raw(PatchCondition.GROWN), T + 1000);
		assertEquals(Known.YES, known(k));
		assertTrue(FarmingTravel.evidence(k, T + 7_200_000L).explain(KEY).contains("grown when last seen 1h 59m ago"));
		FarmingKnowledgeFixtures.change(k, PORT_SARIM, raw(PatchCondition.GROWN), raw(PatchCondition.CHECK_HEALTH), T + 2000);
		assertEquals(Known.UNKNOWN, known(k));
		Map<String, PatchKnowledge> dead = new HashMap<>();
		FarmingKnowledgeFixtures.see(dead, PORT_SARIM, raw(PatchCondition.DEAD), T);
		assertEquals(Known.NO, known(dead));
		assertEquals(Known.UNKNOWN, FarmingTravel.evidence(null, T).get(KEY));
		assertEquals(Known.UNKNOWN, FarmingTravel.evidence(k, T).get("other.key"));
	}

	@Test
	public void evidenceNeverExpiresWithTime()
	{
		Map<String, PatchKnowledge> k = new HashMap<>();
		FarmingKnowledgeFixtures.see(k, PORT_SARIM, raw(PatchCondition.GROWN), T);
		EvidenceSource yearLater = FarmingTravel.evidence(k, T + 365L * 24 * 3_600_000L);
		assertEquals(Known.YES, yearLater.get(KEY));
	}

	@Test
	public void everyStop_hasARoute_fromRouteData_andTheGreatConchOnlyByItsRing()
	{
		FarmingPatchCatalog patches = FarmingPatchCatalog.standard();
		java.util.TreeSet<String> uncovered = new java.util.TreeSet<>();
		for (FarmingPatchDef p : patches.allPatches())
		{
			if (FarmingTravel.forStop(patches, p.getPatchId()).isEmpty())
			{
				uncovered.add(p.getPatchId());
			}
		}
		assertTrue("every patch has a walk from some catalogue arrival: " + uncovered, uncovered.isEmpty());
		assertEquals("the Great Conch is an island reached by fairy ring CJQ",
			java.util.Arrays.asList("fairy_ring.cjq", "poh.spell.fairy_ring.cjq", "poh.tab.fairy_ring.cjq"),
			FarmingTravel.forStop(patches, "calquat.great_conch"));
		List<String> falador = FarmingTravel.forStop(patches, "allotment.falador.nw");
		assertTrue("an allotments-only Falador visit shares the herb patch's teleports: " + falador, falador.contains("spell.falador"));
		assertTrue("cheapest first", falador.indexOf("explorers_ring.cabbage_patch") < falador.indexOf("spell.varrock"));
	}

	@Test
	public void walks_comeWithTheirTransitionsAndRequirements_andPlainWalksAreKeptAsAlternatives()
	{
		FarmingPatchCatalog patches = FarmingPatchCatalog.standard();
		TransportCatalog catalog = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
		com.osrstelemetry.plugin.account.AccountCapability low = com.osrstelemetry.plugin.account.AccountFixtures.base(40).build();
		com.osrstelemetry.plugin.account.AccountCapability high = com.osrstelemetry.plugin.account.AccountFixtures.maxed().build();
		TransportDef falador = catalog.get("spell.falador");
		// Taverley: the 66 Agility climbing rocks are a shortcut, not the only way (an ordinary walk exists).
		com.osrstelemetry.plugin.navigation.RouteTable.Walk fast = FarmingTravel.walk(patches, "tree.taverley", falador,
			com.osrstelemetry.plugin.navigation.NavAccess.planning(high));
		com.osrstelemetry.plugin.navigation.RouteTable.Walk plain = FarmingTravel.walk(patches, "tree.taverley", falador,
			com.osrstelemetry.plugin.navigation.NavAccess.planning(low));
		assertNotNull(fast);
		assertNotNull("40 Agility still walks there", plain);
		assertTrue(fast.getCost() < plain.getCost());
		assertEquals("66 Agility", fast.getRequirement().toString());
		assertTrue(plain.getRequirement().isNone());
		assertTrue(FarmingTravel.routeUsable(patches, "tree.taverley", falador, low));
		// Entrana: the monks' boat from Port Sarim is a step of the journey.
		List<FarmingTravel.Leg> entrana = FarmingTravel.legs(patches, "hops.entrana", "explorers_ring.cabbage_patch");
		assertTrue(entrana.toString(), entrana.stream().anyMatch(l -> l.text.contains("Monk of Entrana")));
		// The underwater seaweed: the dive is a step; nothing reaches it without one.
		List<FarmingTravel.Leg> sea = FarmingTravel.legs(patches, "seaweed.fossil_island.n", "digsite_pendant.fossil_island");
		assertTrue(sea.toString(), sea.get(sea.size() - 1).text.startsWith("Dive from the rowboat"));
		// Kastori without the landing built: the achilka boat from Tal Teklan.
		TransportDef auburn = catalog.get("quetzal_whistle.auburnvale");
		com.osrstelemetry.plugin.navigation.RouteTable.Walk kastori = FarmingTravel.walk(patches, "calquat.kastori", auburn,
			com.osrstelemetry.plugin.navigation.NavAccess.UNGATED);
		assertNotNull(kastori);
		assertTrue(kastori.getTransitions().toString(), kastori.getTransitions().stream().anyMatch(t -> t.instruction().contains("achilka")));
		// BIS is in the unicorn pen (OSRS Wiki: "no other access"): no walk to the Ardougne bush exists.
		assertFalse(FarmingTravel.walkExists(patches, "bush.ardougne", catalog.get("fairy_ring.bis")));
	}
}
