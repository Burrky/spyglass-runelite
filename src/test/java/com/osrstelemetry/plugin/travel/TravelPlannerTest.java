package com.osrstelemetry.plugin.travel;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.bank;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** The cost-only choice for preparation (canonical §14.2, Phase H). */
public class TravelPlannerTest
{
	private static final TransportCatalog CATALOG = TransportCatalogTest.CATALOG;

	private static List<TransportAvailability> eval(AccountCapability cap, String... ids)
	{
		List<TransportAvailability> out = new ArrayList<>();
		for (String id : ids)
		{
			out.add(TransportEvaluator.evaluate(CATALOG.get(id), cap, EvidenceSource.NONE, NOW));
		}
		return out;
	}

	@Test
	public void shortestWalk_winsAmongUsable_andUnusableStatesAreNeverChosen()
	{
		// Ardougne herb patch marker 2663,3375: the cloak lands at 2673,3375 (10 tiles), the spell at 2664,3306 (69).
		AccountCapability cap = maxed().spellbookRaw(0).worn(items(ItemID.ARDY_CAPE_ELITE, 1))
			.inventory(items(ItemID.WATERRUNE, 10, ItemID.LAWRUNE, 10)).build();
		TravelPlanner.Choice c = TravelPlanner.chooseForPreparation(2663, 3375, 0, eval(cap, "spell.ardougne", "ardougne_cloak.farm"));
		assertEquals("ardougne_cloak.farm", c.getChosen().getTransport().id);
		assertEquals(10, c.getWalkTiles());
		assertEquals(1, c.getAlternatives().size());

		AccountCapability depletedOnly = maxed().inventory(items(ItemID.XERIC_TALISMAN_EMPTY, 1)).build();
		assertNull(TravelPlanner.chooseForPreparation(1738, 3550, 0, eval(depletedOnly, "xerics_talisman.xerics_glade")).getChosen());
	}

	@Test
	public void inBankIsEligibleDuringPreparation_andFromAnywhereBeatsANetwork()
	{
		AccountCapability cap = maxed().bank(bank(3_600_000L, ItemID.ECTOPHIAL, 1)).worn(items(ItemID.DRAMEN_STAFF, 1)).build();
		TravelPlanner.Choice c = TravelPlanner.chooseForPreparation(3598, 3524, 0, eval(cap, "fairy_ring.alq", "ectophial.ectofuntus"));
		assertEquals("ectophial.ectofuntus", c.getChosen().getTransport().id);
		assertEquals(Availability.IN_BANK, c.getChosen().getState());
		TravelPlanner.Choice ringOnly = TravelPlanner.chooseForPreparation(3598, 3524, 0, eval(cap, "fairy_ring.alq"));
		assertTrue(ringOnly.isNetwork());
	}

	@Test
	public void ties_preferBetterState_thenNoConsumption()
	{
		// Falador: the tablet and the spell land on the same tile.
		AccountCapability cap = maxed().spellbookRaw(0).inventory(items(ItemID.POH_TABLET_FALADORTELEPORT, 3, ItemID.AIRRUNE, 30, ItemID.WATERRUNE, 10,
			ItemID.LAWRUNE, 10)).build();
		TravelPlanner.Choice c = TravelPlanner.chooseForPreparation(3052, 3309, 0, eval(cap, "tablet.falador", "spell.falador"));
		assertEquals("equal walk, both carried: id order decides deterministically", "spell.falador", c.getChosen().getTransport().id);
		AccountCapability ring = maxed().worn(items(ItemID.LUMBRIDGE_RING_ELITE, 1)).inventory(items(ItemID.POH_TABLET_FALADORTELEPORT, 3)).build();
		assertEquals("explorers_ring.cabbage_patch",
			TravelPlanner.chooseForPreparation(3052, 3309, 0, eval(ring, "tablet.falador", "explorers_ring.cabbage_patch")).getChosen().getTransport().id);
	}

	@Test
	public void housePreference_choosesAUsableHouseJourney_andFallsBackWhenNoneIsUsable()
	{
		// Draynor: the amulet of glory and the house's ornate jewellery box land on the same tile.
		EvidenceSource box = new EvidenceSource()
		{
			@Override
			public com.osrstelemetry.plugin.account.Known get(String key)
			{
				return key.startsWith("poh.jewellery_box") ? com.osrstelemetry.plugin.account.Known.YES : com.osrstelemetry.plugin.account.Known.UNKNOWN;
			}

			@Override
			public String explain(String key)
			{
				return "test";
			}
		};
		AccountCapability cap = maxed().worn(items(ItemID.AMULET_OF_GLORY_6, 1)).inventory(items(ItemID.POH_TABLET_TELEPORTTOHOUSE, 5)).build();
		List<TransportAvailability> c = new ArrayList<>();
		c.add(TransportEvaluator.evaluate(CATALOG.get("glory.draynor_village"), cap, box, NOW));
		c.add(TransportEvaluator.evaluate(CATALOG.get("poh.tab.jewellery.draynor_village"), cap, box, NOW));
		assertEquals("fastest: the carried amulet (no way into the house first)", "glory.draynor_village",
			TravelPlanner.chooseByJourney(t -> 10, c, false).getChosen().getTransport().id);
		TravelPlanner.Choice house = TravelPlanner.chooseByJourney(t -> 10, c, true);
		assertEquals("prefer my house: the jewellery box", "poh.tab.jewellery.draynor_village", house.getChosen().getTransport().id);
		assertEquals("the carried amulet is still named as the other way", "glory.draynor_village", house.getAlternatives().get(0).getTransport().id);
		assertEquals("preferred and usable: the house journey wins even when slower", "poh.tab.jewellery.draynor_village",
			TravelPlanner.chooseByJourney(t -> TravelPlanner.isHouse(t) ? 500 : 10, c, true).getChosen().getTransport().id);
		assertEquals("its way from the arrival closed for this account: not usable, the other way", "glory.draynor_village",
			TravelPlanner.chooseByJourney(t -> TravelPlanner.isHouse(t) ? -1 : 10, c, true).getChosen().getTransport().id);
		// The box not seen in this house: the preference never makes it usable.
		List<TransportAvailability> unseen = new ArrayList<>();
		unseen.add(TransportEvaluator.evaluate(CATALOG.get("glory.draynor_village"), cap, EvidenceSource.NONE, NOW));
		unseen.add(TransportEvaluator.evaluate(CATALOG.get("poh.tab.jewellery.draynor_village"), cap, EvidenceSource.NONE, NOW));
		assertEquals("an unconfirmed facility: the fastest usable", "glory.draynor_village", TravelPlanner.chooseByJourney(t -> 10, unseen, true).getChosen().getTransport().id);
		// No way into the house (no tablet, cape or spell): not usable either.
		AccountCapability noTabs = maxed().worn(items(ItemID.AMULET_OF_GLORY_6, 1)).build();
		List<TransportAvailability> noEntry = new ArrayList<>();
		noEntry.add(TransportEvaluator.evaluate(CATALOG.get("glory.draynor_village"), noTabs, box, NOW));
		noEntry.add(TransportEvaluator.evaluate(CATALOG.get("poh.tab.jewellery.draynor_village"), noTabs, box, NOW));
		assertEquals("no way into the house: the fastest usable", "glory.draynor_village", TravelPlanner.chooseByJourney(t -> 10, noEntry, true).getChosen().getTransport().id);
	}
}
