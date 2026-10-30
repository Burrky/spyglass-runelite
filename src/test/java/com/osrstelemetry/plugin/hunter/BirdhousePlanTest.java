package com.osrstelemetry.plugin.hunter;

import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.MIN;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_BUILT;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_PART;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_SEEDED;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.T0;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.all;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.NeedResult;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Pass 29: bird house preparation from what each space shows (supplies, levels, the tier, the journey). */
public class BirdhousePlanTest
{
	static ItemNeed need(BirdhousePlan p, String key)
	{
		for (ItemNeed n : p.getRequest().getNeeds())
		{
			if (n.getKey().equals(key))
			{
				return n;
			}
		}
		return null;
	}

	static BirdhousePlan plan(BirdhouseTraps t, BirdhouseTier tier, AccountCapability cap, EnumSet<BirdhouseSpace> spaces)
	{
		BirdhouseRun r = BirdhouseRun.start(spaces, tier, t, T0);
		return BirdhousePlan.plan(r, t, cap, Collections.emptyList(), false, T0);
	}

	@Test
	public void fourFullHouses_logsOnly_noClockworks_toolsAndFortySeeds()
	{
		BirdhouseTraps t = BirdhouseRunTest.full();
		BirdhousePlan p = plan(t, BirdhouseTier.REDWOOD, AccountFixtures.base(99).build(), EnumSet.allOf(BirdhouseSpace.class));
		assertEquals(4, p.getHousesToBuild());
		ItemNeed houses = need(p, BirdhousePlan.NEED_HOUSES);
		assertEquals(4, houses.getQuantity());
		assertTrue(houses.accepts(ItemID.REDWOOD_LOGS) && houses.accepts(ItemID.BIRDHOUSE_REDWOOD));
		assertNull("every emptied house gives its clockwork back", need(p, BirdhousePlan.NEED_CLOCKWORK));
		assertNotNull(need(p, BirdhousePlan.NEED_HAMMER));
		assertNotNull(need(p, BirdhousePlan.NEED_CHISEL));
		assertEquals(40, need(p, BirdhousePlan.NEED_SEEDS).getQuantity());
		assertTrue(p.getUnmet().isEmpty());
	}

	@Test
	public void bareSpacesNeedAClockworkEach_carriedHousesCoverThemFirst()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(0, 0, R_SEEDED, R_SEEDED), T0 - 100 * MIN);
		AccountCapability none = AccountFixtures.base(99).build();
		BirdhousePlan p = plan(t, BirdhouseTier.MAGIC, none, EnumSet.allOf(BirdhouseSpace.class));
		assertEquals(2, need(p, BirdhousePlan.NEED_CLOCKWORK).getQuantity());
		// Two magic bird houses carried: they go to the bare spaces; the rest is logs with returned clockworks.
		AccountCapability two = AccountFixtures.base(99).inventory(AccountFixtures.items(ItemID.BIRDHOUSE_MAGIC, 2)).build();
		BirdhousePlan q = plan(t, BirdhouseTier.MAGIC, two, EnumSet.allOf(BirdhouseSpace.class));
		assertEquals(2, q.getHousesCarried());
		assertNull(need(q, BirdhousePlan.NEED_CLOCKWORK));
		assertNotNull("two still made from logs", need(q, BirdhousePlan.NEED_HAMMER));
		// Four carried: no logs, clockwork or tools.
		AccountCapability four = AccountFixtures.base(99).inventory(AccountFixtures.items(ItemID.BIRDHOUSE_MAGIC, 4)).build();
		BirdhousePlan r = plan(t, BirdhouseTier.MAGIC, four, EnumSet.allOf(BirdhouseSpace.class));
		assertNull(need(r, BirdhousePlan.NEED_HAMMER));
		assertNull(need(r, BirdhousePlan.NEED_CHISEL));
	}

	@Test
	public void housesOnlyShortOfSeeds_needNoTier_andHighLevelSeedsCountDouble()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(R_BUILT, R_PART, R_BUILT, R_BUILT), T0);
		AccountCapability cap = AccountFixtures.base(1).inventory(AccountFixtures.items(ItemID.RANARR_SEED, 10, ItemID.BARLEY_SEED, 20)).build();
		BirdhousePlan p = plan(t, null, cap, EnumSet.allOf(BirdhouseSpace.class));
		assertFalse(p.isTierNeeded());
		assertNull(need(p, BirdhousePlan.NEED_HOUSES));
		assertEquals(40, need(p, BirdhousePlan.NEED_SEEDS).getQuantity());
		PreparationState s = PreparationEvaluator.evaluate(p.getRequest(), cap, T0);
		NeedResult seeds = s.result(BirdhousePlan.NEED_SEEDS);
		assertTrue("10 ranarr (20) + 20 barley (20) = 40", seeds.isSatisfied());
	}

	@Test
	public void theTierIsThePlayersChoice_andLevelsAreChecked()
	{
		BirdhouseTraps t = BirdhouseRunTest.full();
		BirdhousePlan none = plan(t, null, AccountFixtures.base(99).build(), EnumSet.allOf(BirdhouseSpace.class));
		assertTrue("never picked for the player", none.isTierNeeded());
		assertNull(need(none, BirdhousePlan.NEED_HOUSES));
		AccountCapability low = AccountFixtures.base(99).level(Skill.HUNTER, 80).level(Skill.CRAFTING, 95).build();
		BirdhousePlan redwood = plan(t, BirdhouseTier.REDWOOD, low, EnumSet.allOf(BirdhouseSpace.class));
		assertEquals(1, redwood.getUnmet().size());
		assertTrue(redwood.getUnmet().get(0), redwood.getUnmet().get(0).startsWith("Hunter 89"));
		AccountCapability unread = com.osrstelemetry.plugin.account.AccountCapability.builder().owner(AccountFixtures.OWNER).build();
		assertEquals(2, plan(t, BirdhouseTier.REDWOOD, unread, EnumSet.allOf(BirdhouseSpace.class)).getUnknown().size());
	}

	@Test
	public void unreadSpaces_arePlannedAsAFullReset_withANote()
	{
		BirdhousePlan p = plan(BirdhouseTraps.empty(), BirdhouseTier.OAK, AccountFixtures.base(99).build(), EnumSet.of(BirdhouseSpace.MEADOW_NORTH));
		assertEquals(1, p.getHousesToBuild());
		assertEquals(1, need(p, BirdhousePlan.NEED_CLOCKWORK).getQuantity());
		assertTrue(p.getNotes().toString(), p.getNotes().toString().contains("not read yet"));
	}

	@Test
	public void journey_digsitePendantToFossilIsland_thenTheIslandInRouteOrder()
	{
		TransportCatalog catalog = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
		AccountCapability cap = AccountFixtures.maxed().inventory(AccountFixtures.items(ItemID.NECKLACE_OF_DIGSITE_5, 1)).build();
		List<TransportAvailability> all = TransportEvaluator.evaluateAll(catalog, cap, EvidenceSource.NONE, T0);
		BirdhouseTraps t = BirdhouseRunTest.full();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.REDWOOD, t, T0);
		BirdhousePlan p = BirdhousePlan.plan(r, t, cap, all, false, T0);
		assertEquals(3, p.getStops().size());
		assertEquals("verdant_valley", p.getStops().get(0).getStop());
		assertEquals("meadow_north", p.getStops().get(1).getStop());
		assertEquals("meadow_south", p.getStops().get(2).getStop());
		assertNotNull(p.getStops().get(0).getTransport());
		assertTrue(p.getStops().get(0).getTransport().getTransport().id, p.getStops().get(0).getTransport().getTransport().id.contains("digsite"));
		assertFalse("its journey is spelled out (ladder, mushtree)", p.getStops().get(0).getLegs().isEmpty());
		boolean travel = false;
		for (ItemNeed n : p.getRequest().getNeeds())
		{
			travel |= n.accepts(ItemID.NECKLACE_OF_DIGSITE_5);
		}
		assertTrue("the pendant is a travel need", travel);
	}
}
