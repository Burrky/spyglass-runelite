package com.osrstelemetry.plugin.travel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Canonical §11.3: every catalogue file parses, passes the structural rules,
 * and every numeric item id equals the gameval constant it names (resolved by
 * reflection here, test-time only, against the project's RuneLite 1.13.1 API).
 */
public class TransportCatalogTest
{
	static final TransportCatalog CATALOG = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;

	private static int itemId(String gameval) throws Exception
	{
		Field f = ItemID.class.getField(gameval);
		assertTrue(gameval + " is a constant", Modifier.isStatic(f.getModifiers()));
		return f.getInt(null);
	}

	@Test
	public void everyFileLoads_andTheCatalogueIsValid()
	{
		long base = CATALOG.all().stream().filter(t -> t.via == null).count();
		assertTrue("Tier 0, 148 house facility routes and the derived house network routes: " + base, base >= 240 && base <= 300);
		assertEquals(new ArrayList<String>(), CATALOG.validate());
		Set<TransportDef.Type> types = EnumSet.noneOf(TransportDef.Type.class);
		for (TransportDef t : CATALOG.all())
		{
			types.add(t.type);
		}
		assertEquals("every farming-relevant family is covered", EnumSet.allOf(TransportDef.Type.class), types);
	}

	@Test
	public void everyItemAndRuneId_matchesItsGamevalName() throws Exception
	{
		int checked = 0;
		for (TransportDef t : CATALOG.all())
		{
			for (TransportDef.ItemVariant v : TransportDef.list(t.items))
			{
				assertEquals(t.id + " " + v.gameval, itemId(v.gameval), v.id);
				checked++;
			}
			for (TransportDef.RuneCost r : TransportDef.list(t.runes))
			{
				assertEquals(t.id + " " + r.gameval, itemId(r.gameval), r.id);
				checked++;
			}
			TransportDef.WieldReq w = t.wield;
			if (w != null)
			{
				for (TransportDef.ItemRef i : w.items)
				{
					assertEquals(t.id + " " + i.gameval, itemId(i.gameval), i.id);
					checked++;
				}
			}
		}
		assertTrue("checked " + checked, checked > 60);
	}

	@Test
	public void everyEntryHasProvenance_andUnverifiedEntriesAreFlaggedNotHidden()
	{
		List<String> unverified = new ArrayList<>();
		for (TransportDef t : CATALOG.all())
		{
			assertNotNull(t.id, t.provenance.source);
			assertFalse(t.id, t.provenance.ref.isEmpty());
			if (!t.isVerified())
			{
				unverified.add(t.id);
				assertFalse(t.id + " explains why it is unverified", TransportDef.list(t.notes).isEmpty());
			}
		}
		assertEquals("every Tier-0 entry is verified (the mechanism stays for future entries)", java.util.Collections.emptyList(), unverified);
	}

	@Test
	public void idsArePrefixedByFamily_andUnique()
	{
		Set<String> ids = new HashSet<>();
		for (TransportDef t : CATALOG.all())
		{
			assertTrue(ids.add(t.id));
			if (t.via != null)
			{
				assertTrue(t.id, t.id.startsWith("via."));
				continue; // world network journeys: checked in worldNetworkJourneys_*
			}
			if (t.id.startsWith("poh."))
			{
				continue; // house routes: poh.tab.* / poh.spell.* (checked in houseRoutes_needObservedFacilities)
			}
			if (t.type == TransportDef.Type.SPELL)
			{
				assertTrue(t.id, t.id.startsWith("spell."));
			}
			if (t.type == TransportDef.Type.TABLET)
			{
				assertTrue(t.id, t.id.startsWith("tablet."));
			}
			if (t.type == TransportDef.Type.FAIRY_RING)
			{
				assertTrue(t.id, t.id.startsWith("fairy_ring."));
			}
		}
	}

	@Test
	public void requiredQuests_areOnlyTheOnesTheCatalogueNames()
	{
		Set<Quest> q = CATALOG.requiredQuests();
		assertTrue(q.contains(Quest.PLAGUE_CITY));
		assertTrue(q.contains(Quest.FAIRYTALE_II__CURE_A_QUEEN));
		assertTrue(q.contains(Quest.TREE_GNOME_VILLAGE));
		assertTrue("bounded read set", q.size() < 20);
	}

	@Test
	public void validator_rejectsBrokenEntries()
	{
		TransportDef bad = new TransportDef();
		bad.id = "Bad Id";
		bad.type = TransportDef.Type.SPELL;
		bad.arrival = new TransportDef.Point();
		bad.arrival.plane = 4;
		List<String> problems = new TransportCatalog(java.util.Collections.singletonList(bad)).validate();
		assertTrue(problems.toString(), problems.size() >= 4);
	}

	@Test
	public void unlocksThatCannotBeObserved_areProofRequirements_neverNotesOrCaveats() throws Exception
	{
		for (String file : TransportCatalog.FILES)
		{
			String json = new String(TransportCatalog.class.getResourceAsStream(TransportCatalog.DIR + file).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			assertFalse(file + " has no caveat field (a caveat could let READY through an unknown unlock)", json.contains("\"caveats\""));
		}
		for (String id : new String[]{"fairy_ring.cir", "fairy_ring.akr", "farming_cape.farming_guild", "fairy_ring.ais", "fairy_ring.ajp", "fairy_ring.ckq"})
		{
			assertFalse(id, com.osrstelemetry.plugin.account.RequirementSet.list(CATALOG.get(id).requirements().proofs).isEmpty());
		}
		assertEquals("only the three free-to-play standard spells", 3, CATALOG.all().stream().filter(t -> !t.requirements().membersOnly()).count());
	}

	@Test
	public void houseRoutes_needObservedFacilities()
	{
		int n = 0;
		for (TransportDef t : CATALOG.all())
		{
			boolean house = t.via == null && (t.id.startsWith("poh.") || t.name.toLowerCase().contains("house"));
			if (!house)
			{
				continue;
			}
			n++;
			assertTrue(t.id, t.id.startsWith("poh.tab.") || t.id.startsWith("poh.spell."));
			boolean needsFacility = false;
			for (com.osrstelemetry.plugin.account.RequirementSet.EvidenceReq r : com.osrstelemetry.plugin.account.RequirementSet.list(t.requirements().evidence))
			{
				needsFacility |= r.key.startsWith("poh.");
			}
			assertTrue("a house route is planned only from an observed facility: " + t.id, needsFacility);
		}
		// 16 facility routes; each portal, right-click portal and nexus destination by tablet and by spell (132, canonical §28.14,
		// one route per facility so the instruction names the one used); 2 per fairy ring and spirit tree destination.
		assertEquals("148 facility routes + 2 per fairy ring and spirit tree destination", 148 + 2 * networks(), n);
	}

	private static int networks()
	{
		int n = 0;
		for (TransportDef t : CATALOG.all())
		{
			n += t.network != null ? 1 : 0;
		}
		return n;
	}

	@Test
	public void houseNetworkRoutes_keepTheDestinationsOwnRequirements_andAddTheHouseFacility()
	{
		TransportDef ring = CATALOG.get("fairy_ring.cir");
		TransportDef tab = CATALOG.get("poh.tab.fairy_ring.cir");
		TransportDef spell = CATALOG.get("poh.spell.fairy_ring.cir");
		assertNull("a derived route is a direct teleport", tab.network);
		assertEquals(ring.arrival.x, tab.arrival.x);
		assertNotNull("the staff stays required", tab.wield);
		assertTrue(com.osrstelemetry.plugin.account.RequirementSet.list(tab.requirements().evidence).stream().anyMatch(e -> e.key.equals("poh.fairy_ring")));
		assertEquals(com.osrstelemetry.plugin.account.RequirementSet.list(ring.requirements().proofs).size(),
			com.osrstelemetry.plugin.account.RequirementSet.list(tab.requirements().proofs).size());
		assertEquals("STANDARD", spell.requirements().spellbook);
		TransportDef grown = CATALOG.get("poh.tab.spirit_tree.etceteria");
		assertEquals("both the house tree and the grown Etceteria tree", 2, com.osrstelemetry.plugin.account.RequirementSet.list(grown.requirements().evidence).size());
		assertEquals("Etceteria lies at x≈2600 (RuneLite's 3614 corrected)", 2614, CATALOG.get("spirit_tree.etceteria").arrival.x);
	}
}
