package com.osrstelemetry.plugin.travel;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.base;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.ChargeKnowledge;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.account.poh.PohKnowledge;
import java.util.Arrays;
import java.util.HashSet;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Charges and daily uses from the game's own messages (canonical §28.13), and house destinations from what was seen in the
 * house (Troll Stronghold and Weiss were not recognised: the live finding of 2026-10-22).
 */
public class ChargesAndHouseRoutesTest
{
	private static final long DAY = 86_400_000L;
	private static final TransportCatalog CATALOG = TransportCatalogTest.CATALOG;

	private static AccountCapability.Builder cloak(ChargeKnowledge k)
	{
		return base(1).worn(items(ItemID.ARDY_CAPE_MEDIUM, 1)).charges(k);
	}

	private static TransportAvailability eval(String id, AccountCapability cap, EvidenceSource e, long now)
	{
		return TransportEvaluator.evaluate(CATALOG.get(id), cap, e, now);
	}

	@Test
	public void dailyUses_fromTheGamesMessage_depletedIsExcluded_andTheDailyResetRestoresThem()
	{
		long noon = Math.floorDiv(NOW, DAY) * DAY + DAY / 2;
		ChargeKnowledge some = ChargeKnowledge.EMPTY.withMessage("You have used 2 of your 5 Ardougne Farm teleports for today.", false, noon);
		assertEquals(Integer.valueOf(3), some.remaining(ChargeKnowledge.ARDOUGNE_FARM, noon));
		TransportAvailability a = eval("ardougne_cloak.farm", cloak(some).build(), EvidenceSource.NONE, noon);
		assertEquals("a known count is no longer 'charges unknown'", Availability.READY, a.getState());
		assertTrue(String.join(" | ", a.getHave()), String.join(" | ", a.getHave()).contains("2 of 5 used today"));

		ChargeKnowledge spent = some.withMessage("<col=ef1020>You have used 5 of your 5 Ardougne Farm teleports for today.</col>", false, noon + 1000);
		assertEquals(Availability.DEPLETED, eval("ardougne_cloak.farm", cloak(spent).build(), EvidenceSource.NONE, noon + 2000).getState());

		// After 00:00 UTC the day's uses start again; the elite cloak (unlimited) never depended on them.
		assertEquals(Integer.valueOf(5), spent.remaining(ChargeKnowledge.ARDOUGNE_FARM, noon + DAY));
		assertEquals(Availability.READY, eval("ardougne_cloak.farm", cloak(spent).build(), EvidenceSource.NONE, noon + DAY).getState());
		assertEquals(Availability.READY,
			eval("ardougne_cloak.farm", base(1).worn(items(ItemID.ARDY_CAPE_ELITE, 1)).charges(spent).build(), EvidenceSource.NONE, noon).getState());
	}

	@Test
	public void unknownStaysUnknown_andUnrelatedMessagesChangeNothing()
	{
		assertEquals(Availability.CHARGES_UNKNOWN,
			eval("ardougne_cloak.farm", cloak(ChargeKnowledge.EMPTY).build(), EvidenceSource.NONE, NOW).getState());
		assertSame(ChargeKnowledge.EMPTY, ChargeKnowledge.EMPTY.withMessage("You pick some fruit.", true, NOW));
		assertNull(ChargeKnowledge.EMPTY.remaining(ChargeKnowledge.XERICS, NOW));
	}

	@Test
	public void storedCharges_talismanAndWhistle_lastReportedCount_whistleOnlyWhenOneIsCarried()
	{
		ChargeKnowledge k = ChargeKnowledge.EMPTY.withMessage("The talisman has 0 charges.", false, NOW);
		assertEquals(Integer.valueOf(0), k.remaining(ChargeKnowledge.XERICS, NOW));
		assertEquals(Availability.DEPLETED,
			eval("xerics_talisman.xerics_glade", base(1).inventory(items(ItemID.XERIC_TALISMAN, 1)).charges(k).build(), EvidenceSource.NONE, NOW).getState());
		ChargeKnowledge recharged = k.withMessage("The talisman has 1,234 charges.", false, NOW + 1);
		assertEquals(Integer.valueOf(1234), recharged.remaining(ChargeKnowledge.XERICS, NOW + 1));
		assertEquals("a daily reset never refills stored charges", Integer.valueOf(1234), recharged.remaining(ChargeKnowledge.XERICS, NOW + 3 * DAY));

		assertSame("two whistles: the message names no tier", ChargeKnowledge.EMPTY,
			ChargeKnowledge.EMPTY.withMessage("Your quetzal whistle has 4 charges remaining.", false, NOW));
		assertEquals(Integer.valueOf(4),
			ChargeKnowledge.EMPTY.withMessage("Your quetzal whistle has 4 charges remaining.", true, NOW).remaining(ChargeKnowledge.QUETZAL_WHISTLE, NOW));
	}

	@Test
	public void savedForm_roundTrips()
	{
		ChargeKnowledge k = ChargeKnowledge.EMPTY.withMessage("You have used 1 of your 3 Cabbage teleports for today.", false, NOW)
			.withMessage("The talisman has 12 charges.", false, NOW);
		assertEquals(k, ChargeKnowledge.fromSaved(k.toSaved()));
		assertEquals(ChargeKnowledge.EMPTY, ChargeKnowledge.fromSaved(null));
	}

	private static PohKnowledge visit(PohKnowledge k, boolean building, String... seen)
	{
		return k.withVisit(new HashSet<>(Arrays.asList(seen)), building, 1000);
	}

	@Test
	public void eachDestination_keepsItsFacility_portalNexusOrRightClick()
	{
		PohKnowledge k = visit(PohKnowledge.empty(), true, "poh.portal.catherby", "poh.portal.troll_stronghold", "poh.nexus", PohKnowledge.NEXUS_READ,
			"poh.nexus.draynor_manor", "poh.nexus.kourend", "poh.nexus.lassar");
		AccountCapability cap = base(1).inventory(items(ItemID.POH_TABLET_TELEPORTTOHOUSE, 3)).build();
		EvidenceSource e = k.evidence();
		// Live findings 2026-10-22: Catherby is a portal (never "nexus → Catherby"); Draynor Manor is a nexus slot (never a portal).
		assertEquals(Availability.READY, eval("poh.tab.portal.catherby", cap, e, NOW).getState());
		assertTrue(eval("poh.tab.nexus.catherby", cap, e, NOW).getState() != Availability.READY);
		assertEquals(Availability.READY, eval("poh.tab.nexus.draynor_manor", cap, e, NOW).getState());
		assertTrue(eval("poh.tab.portal.draynor_manor", cap, e, NOW).getState() != Availability.READY);
		assertEquals(Availability.READY, eval("poh.tab.portal.troll_stronghold", cap, e, NOW).getState());
		assertEquals("lassar", PohKnowledge.nexusDestination("<col=ccccff>2</col>: Lassar"));
	}

	@Test
	public void theNexusAlone_establishesNoDestination_andOnlyItsTeleportMenuCounts()
	{
		PohKnowledge unread = visit(PohKnowledge.empty(), true, "poh.nexus", "poh.portal.varrock");
		assertEquals("a nexus whose Teleport Menu was never read: unknown", Known.UNKNOWN, unread.answer("poh.nexus.catherby"));
		assertEquals(Known.NO, unread.answer("poh.portal.catherby"));
		AccountCapability cap = base(1).inventory(items(ItemID.POH_TABLET_TELEPORTTOHOUSE, 3)).build();
		assertTrue("unknown is never planned as usable", eval("poh.tab.nexus.catherby", cap, unread.evidence(), NOW).getState() != Availability.READY);
		PohKnowledge read = visit(unread, true, "poh.nexus", "poh.portal.varrock", PohKnowledge.NEXUS_READ, "poh.nexus.kourend");
		assertEquals(Known.YES, read.answer("poh.nexus.kourend"));
		assertEquals("read: a destination not listed is not there", Known.NO, read.answer("poh.nexus.catherby"));
	}

	@Test
	public void changes_slotsKeptWhenNotReread_replacedWhenReread_removedWithTheNexus()
	{
		PohKnowledge a = visit(PohKnowledge.empty(), true, "poh.nexus", "poh.portal.weiss", PohKnowledge.NEXUS_READ, "poh.nexus.kourend");
		PohKnowledge b = visit(a, true, "poh.nexus", "poh.portal.weiss");
		assertEquals("not reopened: the last read slots stand", Known.YES, b.answer("poh.nexus.kourend"));
		PohKnowledge c = visit(b, true, "poh.nexus", "poh.portal.weiss", PohKnowledge.NEXUS_READ, "poh.nexus.lassar");
		assertEquals("reread: replaced", Known.NO, c.answer("poh.nexus.kourend"));
		assertEquals(Known.YES, c.answer("poh.nexus.lassar"));
		PohKnowledge d = visit(c, true, "poh.portal.weiss");
		assertEquals("nexus removed: its slots go", Known.NO, d.answer("poh.nexus.lassar"));
		PohKnowledge e = visit(c, true, "poh.nexus");
		assertEquals("portal removed", Known.NO, e.answer("poh.portal.weiss"));
	}

	@Test
	public void aVisitWithoutBuildingMode_countsOnlyWhenTheLayoutIsExactlyTheOwnHouse()
	{
		PohKnowledge own = visit(PohKnowledge.empty(), true, "poh.nexus", "poh.portal.weiss", "poh.fairy_ring");
		PohKnowledge same = visit(own, false, "poh.nexus", "poh.portal.weiss", "poh.fairy_ring", PohKnowledge.NEXUS_READ, "poh.nexus.kourend");
		assertEquals("the same house: its nexus menu counts", Known.YES, same.answer("poh.nexus.kourend"));
		PohKnowledge friend = visit(own, false, "poh.nexus", "poh.portal.catherby", PohKnowledge.NEXUS_READ, "poh.nexus.kourend");
		assertEquals("another layout (a friend's house): unused", Known.UNKNOWN, friend.answer("poh.nexus.kourend"));
		assertEquals(Known.NO, friend.answer("poh.portal.catherby"));
	}

	@Test
	public void theFirstCollectorsNexusSlots_areDropped_portalsKept()
	{
		PohKnowledge old = visit(PohKnowledge.empty(), true, "poh.nexus", "poh.portal.catherby", "poh.nexus.read", "poh.nexus.draynor_manor",
			"poh.nexus.catherby");
		PohKnowledge m = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(old), PohKnowledge.class).loaded();
		assertEquals(Known.YES, m.answer("poh.portal.catherby"));
		assertEquals("re-read from the Teleport Menu", Known.UNKNOWN, m.answer("poh.nexus.catherby"));
		assertEquals(Known.UNKNOWN, m.answer("poh.nexus.draynor_manor"));
		assertFalse(m.getFacilities().contains("poh.nexus.read"));
	}

	@Test
	public void varrockAndGrandExchange_areDifferentLandings_andTheDiaryPortalOffersBoth()
	{
		assertEquals(new HashSet<>(Arrays.asList("poh.portal.varrock", "poh.portal_alt.grand_exchange")),
			PohKnowledge.facilitiesOf(net.runelite.api.gameval.ObjectID.POH_PORTAL_MARBLE_VARROCK_VARROCK1ST));
		assertEquals(new HashSet<>(Arrays.asList("poh.portal.grand_exchange", "poh.portal_alt.varrock")),
			PohKnowledge.facilitiesOf(net.runelite.api.gameval.ObjectID.POH_PORTAL_MARBLE_VARROCK_GE1ST));
		assertEquals("no diary: Varrock only", java.util.Collections.singleton("poh.portal.varrock"),
			PohKnowledge.facilitiesOf(net.runelite.api.gameval.ObjectID.POH_PORTAL_MARBLE_VARROCK));
		TransportDef v = CATALOG.get("poh.tab.portal.varrock");
		TransportDef ge = CATALOG.get("poh.tab.portal_alt.grand_exchange");
		assertEquals(3213, v.arrival.x);
		assertEquals("RuneLite TeleportLocationData VARROCK_GE", 3164, ge.arrival.x);
		assertEquals(3478, ge.arrival.y);
	}
}
