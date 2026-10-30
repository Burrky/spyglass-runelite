package com.osrstelemetry.plugin.skills.fishing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.SupplyTracker;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Fishing companion's production model, driven exactly as the collector
 * drives it (per tick: XP, chat, inventory / bank snapshots) -- the same
 * causal model and the same shared chassis as Mining.
 */
public class FishingCompanionTrackerTest
{
	private static final int LOBSTER = ItemID.RAW_LOBSTER;
	private static final int SHARK = ItemID.RAW_SHARK;
	private static final int TROUT = ItemID.RAW_TROUT;
	private static final int SALMON = ItemID.RAW_SALMON;
	private static final int SHRIMP = ItemID.RAW_SHRIMP;
	private static final int ANCHOVIES = ItemID.RAW_ANCHOVIES;
	private static final int TUNA = ItemID.RAW_TUNA;
	private static final int SWORDFISH = ItemID.RAW_SWORDFISH;
	private static final int KARAMBWAN = ItemID.TBWT_RAW_KARAMBWAN;
	private static final int KARAMBWANJI = ItemID.TBWT_RAW_KARAMBWANJI;
	private static final int MINNOW = ItemID.MINNOW;
	private static final int FEATHER = ItemID.FEATHER;
	private static final int BAIT = ItemID.FISHING_BAIT;
	private static final int LEAPING_TROUT = ItemID.BRUT_SPAWNING_TROUT;
	private static final int BARREL = ItemID.FISH_BARREL_CLOSED;
	private static final int BARREL_OPEN = ItemID.FISH_BARREL_OPEN;
	private static final int REGION = 11_317;

	private FishingCompanionTracker t;
	private int tick;
	private long now;
	private Map<Integer, Long> inv;
	private Map<Integer, Long> bank;
	private final Map<Integer, Long> produced = new LinkedHashMap<>();
	private final Map<Integer, Long> banked = new LinkedHashMap<>();

	@Before
	public void setUp()
	{
		t = new FishingCompanionTracker();
		tick = 1_000;
		now = 10_000_000L;
		inv = new LinkedHashMap<>();
		bank = new LinkedHashMap<>();
		produced.clear();
		banked.clear();
	}

	// ---- helpers ---------------------------------------------------------------------------

	private static boolean stackable(int item)
	{
		SkillResource r = FishingCompanionDefinition.INSTANCE.resource(item);
		return item == FEATHER || item == BAIT || item == ItemID.PISCARILIUS_SANDWORMS || item == ItemID.WILDERNESS_FISHING_BAIT
			|| item == ItemID.BRUT_FISH_CUTS || (r != null && r.isStackable());
	}

	private int slots()
	{
		int used = 0;
		for (Map.Entry<Integer, Long> e : inv.entrySet())
		{
			if (e.getValue() > 0)
			{
				used += stackable(e.getKey()) ? 1 : (int) (long) e.getValue();
			}
		}
		return used;
	}

	private void login(Object... items)
	{
		for (int i = 0; i < items.length; i += 2)
		{
			inv.put((Integer) items[i], ((Number) items[i + 1]).longValue());
		}
		t.onInventory(tick, now, new HashMap<>(inv), slots());
		t.onBank(tick, now, new HashMap<>(bank));
	}

	private void pushInventory()
	{
		inv.values().removeIf(v -> v <= 0);
		t.onInventory(tick, now, new HashMap<>(inv), slots());
	}

	private void pushBank()
	{
		bank.values().removeIf(v -> v <= 0);
		t.onBank(tick, now, new HashMap<>(bank));
	}

	private void step()
	{
		tick++;
		now += 600;
		t.onTick(tick, now, REGION);
	}

	private void steps(int n)
	{
		for (int i = 0; i < n; i++)
		{
			step();
		}
	}

	private static String line(int fish)
	{
		switch (fish)
		{
			case ItemID.RAW_LOBSTER:
				return "You catch a lobster.";
			case ItemID.RAW_SHARK:
				return "You catch a shark!";
			case ItemID.RAW_TROUT:
				return "You catch a trout.";
			case ItemID.RAW_SALMON:
				return "You catch a salmon.";
			case ItemID.RAW_SHRIMP:
				return "You catch some shrimps.";
			case ItemID.RAW_ANCHOVIES:
				return "You catch some anchovies.";
			case ItemID.RAW_TUNA:
				return "You catch a tuna.";
			case ItemID.RAW_SWORDFISH:
				return "You catch a swordfish.";
			case ItemID.TBWT_RAW_KARAMBWAN:
				return "You catch a karambwan.";
			case ItemID.BRUT_SPAWNING_TROUT:
				return "You catch a leaping trout.";
			default:
				return null;
		}
	}

	/** One catch into the inventory: Fishing XP, the catch line and the fish on one tick (supply used if given). */
	private void fish(int item, Integer supplyItem)
	{
		t.onSkillXp(tick, 50, now);
		String l = line(item);
		if (l != null)
		{
			t.onChat(tick, now, l);
		}
		inv.merge(item, 1L, Long::sum);
		if (supplyItem != null)
		{
			inv.merge(supplyItem, -1L, Long::sum);
		}
		pushInventory();
		step();
		step();
	}

	private void fish(int item)
	{
		fish(item, null);
	}

	private void fishMany(int item, int n)
	{
		for (int i = 0; i < n; i++)
		{
			fish(item);
		}
	}

	/** One catch that went straight into the open barrel: XP and the line, no inventory change. */
	private void fishIntoBarrel(int item)
	{
		t.onSkillXp(tick, 50, now);
		t.onChat(tick, now, line(item));
		step();
		step();
	}

	private void deposit(int item, long qty)
	{
		t.setBankOpen(true);
		inv.merge(item, -qty, Long::sum);
		bank.merge(item, qty, Long::sum);
		pushInventory();
		pushBank();
		step();
		t.setBankOpen(false);
	}

	private void settle()
	{
		steps(3);
		t.settleAll();
		ProductionLedger.Drain d = t.getLedger().drain();
		d.getProduced().forEach((k, v) -> produced.merge(k, v, Long::sum));
		d.getBanked().forEach((k, v) -> banked.merge(k, v, Long::sum));
	}

	private long total(int item)
	{
		return produced.getOrDefault(item, 0L);
	}

	private long bankedOf(int item)
	{
		return banked.getOrDefault(item, 0L);
	}

	// ---- production -------------------------------------------------------------------------

	@Test
	public void oneCatch_isOneFish_andNamesTheMethod()
	{
		login();
		fish(LOBSTER);
		settle();
		assertEquals(1L, total(LOBSTER));
		assertEquals("lobster", t.getMethodKey());
	}

	@Test
	public void aFullInventory_isCountedExactly()
	{
		login();
		fishMany(LOBSTER, 28);
		settle();
		assertEquals(28L, total(LOBSTER));
	}

	@Test
	public void lineAndInventoryGain_areOneCatch_neverTwo()
	{
		login(BARREL_OPEN, 1);
		// The barrel is open, but the fish reached the inventory: the line must claim that gain, not route a second fish.
		fish(LOBSTER);
		settle();
		assertEquals(1L, total(LOBSTER));
		assertEquals(0, t.getBarrel().getAddedThisTrip());
	}

	@Test
	public void xpOnly_noLine_isStillCausal()
	{
		login();
		t.onSkillXp(tick, 90, now);
		inv.put(LOBSTER, 1L);
		pushInventory();
		settle();
		assertEquals(1L, total(LOBSTER));
	}

	@Test
	public void lineOnly_noXpEvent_isStillCausal()
	{
		login();
		t.onChat(tick, now, "You catch a lobster.");
		inv.put(LOBSTER, 1L);
		pushInventory();
		settle();
		assertEquals(1L, total(LOBSTER));
	}

	@Test
	public void evidenceOnAnAdjacentTick_stillCounts_beyondIt_doesNot()
	{
		login();
		t.onSkillXp(tick, 90, now);
		step();
		inv.put(LOBSTER, 1L);
		pushInventory();
		settle();
		assertEquals("XP one tick before the fish: causal", 1L, total(LOBSTER));

		t.onSkillXp(tick, 90, now);
		steps(4);
		inv.put(LOBSTER, 2L);
		pushInventory();
		settle();
		assertEquals("a gain four ticks later is not that catch", 1L, total(LOBSTER));
	}

	@Test
	public void withdrawalOrPickUp_isNeverProduction()
	{
		login();
		inv.put(SHARK, 5L);
		pushInventory();
		settle();
		assertEquals(0L, total(SHARK));
		assertNull(t.getMethodKey());
	}

	@Test
	public void dropping_neverLowersTotal()
	{
		login();
		fishMany(TROUT, 4);
		inv.put(TROUT, 0L);
		pushInventory();
		settle();
		assertEquals(4L, total(TROUT));
		assertEquals(0L, bankedOf(TROUT));
	}

	@Test
	public void nothingIsCaughtWithTheBankOpen()
	{
		login();
		t.setBankOpen(true);
		t.onSkillXp(tick, 10, now);
		inv.put(LOBSTER, 3L);
		pushInventory();
		pushBank();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals(0L, total(LOBSTER));
	}

	// ---- banked ----------------------------------------------------------------------------------

	@Test
	public void deposit_banksOnlyThisSessionsCatch()
	{
		login(LOBSTER, 3);
		fishMany(LOBSTER, 5);
		deposit(LOBSTER, 8);
		settle();
		assertEquals(5L, total(LOBSTER));
		assertEquals("the 3 carried in were never this session's", 5L, bankedOf(LOBSTER));
		List<? extends FishingCompanionTracker.Deposit> deposits = t.drainDeposits();
		assertEquals(1, deposits.size());
		assertEquals(5L, deposits.get(0).getQuantity());
	}

	@Test
	public void depositBox_everyDecreaseIsADeposit()
	{
		login();
		fishMany(SHARK, 3);
		t.setDepositBoxOpen(true);
		inv.put(SHARK, 0L);
		pushInventory();
		step();
		t.setDepositBoxOpen(false);
		settle();
		assertEquals(3L, bankedOf(SHARK));
	}

	@Test
	public void droppedThenDeposited_neverBanksMoreThanHeld()
	{
		login();
		fishMany(LOBSTER, 4);
		inv.merge(LOBSTER, -2L, Long::sum);
		pushInventory();
		step();
		deposit(LOBSTER, 2);
		settle();
		assertEquals(4L, total(LOBSTER));
		assertEquals(2L, bankedOf(LOBSTER));
	}

	// ---- methods ------------------------------------------------------------------------------

	@Test
	public void flyFishing_troutAndSalmon_isOneStableMethod()
	{
		login(FEATHER, 500);
		for (int i = 0; i < 6; i++)
		{
			fish(i % 3 == 0 ? SALMON : TROUT, FEATHER);
		}
		settle();
		assertEquals("fly", t.getMethodKey());
		assertEquals("never churns between species", 0L, t.getMethods().getTransitions());
		assertEquals(4L, total(TROUT));
		assertEquals(2L, total(SALMON));
	}

	@Test
	public void smallNet_shrimpAndAnchovies_isOneMethod()
	{
		login();
		fish(SHRIMP);
		fish(ANCHOVIES);
		fish(SHRIMP);
		settle();
		assertEquals("small_net", t.getMethodKey());
		assertEquals(0L, t.getMethods().getTransitions());
	}

	@Test
	public void harpoon_tunaAndSwordfish_isOneMethod()
	{
		login();
		fish(TUNA);
		fish(SWORDFISH);
		fish(TUNA);
		settle();
		assertEquals("harpoon", t.getMethodKey());
		assertEquals(0L, t.getMethods().getTransitions());
	}

	@Test
	public void aRealSwitch_needsConfirmation_unlessTheSpotClickNamedIt()
	{
		login();
		fishMany(LOBSTER, 3);
		fish(SHARK);
		settle();
		assertEquals("one shark is not yet a switch", "lobster", t.getMethodKey());
		fish(SHARK);
		settle();
		assertEquals("shark", t.getMethodKey());

		// A click on a lobster spot's "Cage" makes the next lobster switch at once.
		t.onSpotClick(now, "LOBSTER", "Cage");
		fish(LOBSTER);
		settle();
		assertEquals("lobster", t.getMethodKey());
	}

	@Test
	public void spotClickAlone_neverAdoptsAMethod()
	{
		login();
		t.onSpotClick(now, "SHARK", "Harpoon");
		settle();
		assertNull(t.getMethodKey());
	}

	@Test
	public void unsupportedFishing_xpWithoutAKnownCatch_inventsNoMethod()
	{
		login();
		t.onSkillXp(tick, 60, now);
		t.onChat(tick, now, "You catch a slimy eel.");
		inv.put(ItemID.MORT_SLIMEY_EEL, 1L);
		pushInventory();
		settle();
		assertNull(t.getMethodKey());
		assertTrue(t.getLedger().getPendingProduced().isEmpty());
	}

	@Test
	public void temporossHarpoonfish_isNeverFishingProduction()
	{
		login();
		for (int i = 0; i < 5; i++)
		{
			t.onSkillXp(tick, 65, now);
			t.onChat(tick, now, "You catch a harpoonfish.");
			inv.merge(ItemID.TEMPOROSS_RAW_HARPOONFISH, 1L, Long::sum);
			pushInventory();
			steps(2);
		}
		settle();
		assertTrue("Tempoross fish are not resources of the Fishing companion", produced.isEmpty());
		assertNull(t.getMethodKey());
	}

	// ---- fish barrel ------------------------------------------------------------------------------

	@Test
	public void openBarrel_catchesFish_asProductionToStorage_contentsUnknown()
	{
		login(BARREL_OPEN, 1);
		for (int i = 0; i < 4; i++)
		{
			fishIntoBarrel(LOBSTER);
		}
		settle();
		assertEquals(4L, total(LOBSTER));
		assertEquals("lobster", t.getMethodKey());
		assertEquals(4, t.getBarrel().getAddedThisTrip());
		assertNull("never claims what was in it before", t.getBarrel().getKnownContents());
		assertEquals("storage is not the bank", 0L, bankedOf(LOBSTER));
		SkillCompanionView.TripView trip = t.trip(now);
		assertEquals("Fish barrel", trip.getStorageName());
		assertNull(trip.getStorageUsed());
		assertEquals(4, trip.getStorageAddedThisTrip());
		assertTrue(trip.isStorageCatching());
	}

	@Test
	public void closedBarrel_catchesNothing()
	{
		login(BARREL, 1);
		fishIntoBarrel(LOBSTER);
		settle();
		assertEquals("a line with no fish anywhere and no open barrel is not a catch", 0L, total(LOBSTER));
	}

	@Test
	public void barrelEmptiedIntoTheBank_banksThisSessionsFish_andBecomesKnown()
	{
		login(BARREL_OPEN, 1);
		fishIntoBarrel(SWORDFISH);
		fishIntoBarrel(TUNA);
		fishIntoBarrel(TUNA);
		// Bank: the barrel's whole contents arrive in the bank with no inventory decrease.
		t.setBankOpen(true);
		pushBank();
		bank.merge(TUNA, 2L + 5L, Long::sum); // 5 were already in the barrel before this session
		bank.merge(SWORDFISH, 1L, Long::sum);
		pushBank();
		pushInventory();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals(2L, bankedOf(TUNA));
		assertEquals(1L, bankedOf(SWORDFISH));
		assertEquals(Integer.valueOf(0), t.getBarrel().getKnownContents());

		fishIntoBarrel(TUNA);
		settle();
		assertEquals(Integer.valueOf(1), t.getBarrel().getKnownContents());
		assertEquals(Integer.valueOf(27), t.getBarrel().getKnownRoom());
		SkillCompanionView.TripView trip = t.trip(now);
		assertEquals(Integer.valueOf(1), trip.getStorageUsed());
		assertEquals(Integer.valueOf(28), trip.getStorageCapacity());
	}

	@Test
	public void emptyingTheBarrelIntoTheInventory_isAMove_neverACatch()
	{
		login(BARREL_OPEN, 1);
		fishIntoBarrel(LOBSTER);
		fishIntoBarrel(LOBSTER);
		settle();
		t.onBarrelEmpty(tick);
		inv.put(LOBSTER, 2L);
		pushInventory();
		settle();
		assertEquals(2L, total(LOBSTER));
		assertEquals(0, t.getBarrel().getAddedThisTrip());
		// ...and they bank from the inventory as this session's own.
		deposit(LOBSTER, 2);
		settle();
		assertEquals(2L, bankedOf(LOBSTER));
	}

	@Test
	public void emptyingWhileStillFishing_countsOnlyTheNewCatch()
	{
		login(BARREL_OPEN, 1);
		for (int i = 0; i < 5; i++)
		{
			fishIntoBarrel(LOBSTER);
		}
		t.onBarrelEmpty(tick);
		t.onSkillXp(tick, 90, now);
		t.onChat(tick, now, "You catch a lobster.");
		inv.put(LOBSTER, 6L);
		pushInventory();
		settle();
		assertEquals("5 routed + 1 caught, never 5 + 6", 6L, total(LOBSTER));
	}

	@Test
	public void fillingTheBarrel_isAMove_andItBanksLater()
	{
		login(BARREL, 1);
		fishMany(LOBSTER, 3);
		t.onBarrelFill(tick);
		inv.put(LOBSTER, 0L);
		pushInventory();
		settle();
		assertEquals(3L, total(LOBSTER));
		t.setBankOpen(true);
		pushBank();
		bank.merge(LOBSTER, 3L, Long::sum);
		pushBank();
		pushInventory();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals(3L, bankedOf(LOBSTER));
	}

	@Test
	public void barrelFull_isAContextLine_andCatchesGoToTheInventory()
	{
		login(BARREL_OPEN, 1);
		fishIntoBarrel(LOBSTER);
		t.onChat(tick, now, "Your fish barrel is full.");
		step();
		List<SkillCompanionView.ContextLine> ctx = t.context(now);
		assertEquals(1, ctx.size());
		assertEquals("Fish barrel full", ctx.get(0).getText());
		assertFalse("a full barrel catches nothing", t.trip(now).isStorageCatching());
		fish(LOBSTER);
		settle();
		assertEquals(2L, total(LOBSTER));
	}

	@Test
	public void stackableCatches_neverGoInTheBarrel()
	{
		assertFalse(FishingCompanionDefinition.barrelAccepts(KARAMBWANJI));
		assertFalse(FishingCompanionDefinition.barrelAccepts(MINNOW));
		assertTrue(FishingCompanionDefinition.barrelAccepts(LOBSTER));
		assertFalse(FishingCompanionDefinition.barrelAccepts(FEATHER));
	}

	// ---- current trip ----------------------------------------------------------------------------

	@Test
	public void trip_spaceLeft_andEstFull_fromObservedCatches()
	{
		login();
		assertNull("no rate before the evidence", t.trip(now).getEstFullMillis());
		for (int i = 0; i < 8; i++)
		{
			fish(LOBSTER);
			steps(10);
		}
		settle();
		SkillCompanionView.TripView trip = t.trip(now);
		assertEquals(Integer.valueOf(20), trip.getSpaceLeft());
		assertNotNull(trip.getEstFullMillis());
		// ~12 ticks (7.2 s) per lobster: 20 more is ~144 s.
		assertTrue(trip.getEstFullMillis() > 120_000L && trip.getEstFullMillis() < 170_000L);
		assertFalse(trip.isEstIncludesStorage());
	}

	@Test
	public void trip_withAnOpenBarrelOfUnknownContents_claimsNoEstimate()
	{
		login(BARREL_OPEN, 1);
		for (int i = 0; i < 8; i++)
		{
			fishIntoBarrel(LOBSTER);
			steps(10);
		}
		settle();
		assertNull("the barrel's room is unknown: no fake EST", t.trip(now).getEstFullMillis());
	}

	@Test
	public void trip_withAKnownBarrel_includesItsRoom()
	{
		login(BARREL_OPEN, 1);
		t.setBankOpen(true);
		pushBank();
		bank.merge(LOBSTER, 10L, Long::sum);
		pushBank();
		pushInventory();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals(Integer.valueOf(0), t.getBarrel().getKnownContents());
		for (int i = 0; i < 8; i++)
		{
			fishIntoBarrel(LOBSTER);
			steps(10);
		}
		settle();
		SkillCompanionView.TripView trip = t.trip(now);
		assertTrue(trip.isEstIncludesStorage());
		// 27 slots + 20 barrel places at ~7.2 s each.
		assertTrue(trip.getEstFullMillis() > 300_000L);
	}

	// ---- supplies --------------------------------------------------------------------------------

	@Test
	public void supplies_countIsTheInventory_runwayOnlyAfterEnoughEvidence()
	{
		login(FEATHER, 200);
		fish(TROUT, FEATHER);
		settle();
		SkillCompanionView.SupplyView s = t.supply(now);
		assertNotNull(s);
		assertEquals("Feathers", s.getName());
		assertEquals(199L, s.getCount());
		assertNull("too little evidence: no runway", s.getRunwayMillis());

		for (int i = 0; i < 30; i++)
		{
			fish(i % 2 == 0 ? TROUT : SALMON, FEATHER);
			steps(8);
		}
		settle();
		s = t.supply(now);
		assertEquals(169L, s.getCount());
		assertNotNull(s.getRunwayMillis());
		assertTrue(t.getSupply().getConsumed() >= SupplyTracker.MIN_CONSUMED);
		// ~10 ticks per feather: 169 left is roughly 17 minutes.
		long mins = s.getRunwayMillis() / 60_000L;
		assertTrue("runway ~17 min, was " + mins, mins >= 13 && mins <= 21);
	}

	@Test
	public void sharkLures_spriteCountAndObservedUse_timeLeftOnlyAfterRealEvidence()
	{
		login(ItemID.SHARK_LURE, 60);
		fish(ItemID.RAW_SHARK, ItemID.SHARK_LURE);
		settle();
		assertEquals("shark", t.getMethodKey());
		SkillCompanionView.SupplyView s = t.supply(now);
		assertNotNull(s);
		assertEquals("Shark lures", s.getName());
		assertEquals(ItemID.SHARK_LURE, s.getItemId());
		assertEquals(59L, s.getCount());
		assertNull("one lure is no rate", s.getRunwayMillis());
		// Only what actually disappeared counts: every other catch uses a lure here (never one per catch assumed).
		for (int i = 0; i < 30; i++)
		{
			fish(ItemID.RAW_SHARK, i % 2 == 0 ? ItemID.SHARK_LURE : null);
			steps(8);
		}
		settle();
		s = t.supply(now);
		assertEquals(44L, s.getCount());
		assertEquals(16L, t.getSupply().getConsumed());
		assertNotNull("enough real use seen: an estimate", s.getRunwayMillis());
	}

	@Test
	public void supplies_depositsWithdrawalsAndDrops_areNeverConsumption()
	{
		login(FEATHER, 100);
		fishMany(TROUT, 1);
		settle();
		long used = t.getSupply().getConsumed();
		deposit(FEATHER, 50);
		inv.merge(FEATHER, -10L, Long::sum);
		pushInventory();
		settle();
		assertEquals(used, t.getSupply().getConsumed());
		inv.merge(FEATHER, 500L, Long::sum);
		pushInventory();
		settle();
		assertEquals("a withdrawal raises the usable count at once", 540L, t.supply(now).getCount());
	}

	@Test
	public void supplies_neverAssumeOneBaitPerCatch()
	{
		login(FEATHER, 100);
		fish(TROUT, FEATHER);
		fish(TROUT, null); // a catch that used no feather
		fish(TROUT, FEATHER);
		settle();
		assertEquals("two feathers actually disappeared", 2L, t.getSupply().getConsumed());
	}

	@Test
	public void supplies_hiddenForMethodsWithout()
	{
		login(FEATHER, 100);
		fishMany(LOBSTER, 3);
		settle();
		assertNull(t.supply(now));
	}

	@Test
	public void supplies_reScopeOnAMethodChange()
	{
		login(FEATHER, 100, BAIT, 100);
		for (int i = 0; i < 12; i++)
		{
			fish(TROUT, FEATHER);
		}
		settle();
		assertTrue(t.getSupply().getConsumed() >= 10);
		fish(ItemID.RAW_PIKE, BAIT);
		fish(ItemID.RAW_PIKE, BAIT);
		settle();
		assertEquals("pike", t.getMethodKey());
		assertEquals("Fishing bait", t.supply(now).getName());
		assertTrue("the fly-fishing rate does not carry over", t.getSupply().getConsumed() < 10);
	}

	@Test
	public void supplies_outOfBait_isZero()
	{
		login(FEATHER, 2);
		fish(TROUT, FEATHER);
		fish(TROUT, FEATHER);
		settle();
		assertEquals(0L, t.supply(now).getCount());
	}

	@Test
	public void karambwan_usesKarambwanji_asItsSupply()
	{
		login(KARAMBWANJI, 300);
		for (int i = 0; i < 3; i++)
		{
			fish(KARAMBWAN, KARAMBWANJI);
		}
		settle();
		assertEquals("karambwan", t.getMethodKey());
		assertEquals(3L, total(KARAMBWAN));
		assertEquals("bait is never production", 0L, total(KARAMBWANJI));
		SkillCompanionView.SupplyView s = t.supply(now);
		assertEquals("Karambwanji", s.getName());
		assertEquals(297L, s.getCount());
	}

	@Test
	public void barbarian_supplyIsWhatIsCarriedMost()
	{
		login(FEATHER, 50, BAIT, 400);
		fish(LEAPING_TROUT, BAIT);
		settle();
		assertEquals("barbarian", t.getMethodKey());
		assertEquals("Fishing bait", t.supply(now).getName());
		assertEquals("every barbarian supply counts", 449L, t.supply(now).getCount());
	}

	// ---- stackable methods -----------------------------------------------------------------------

	@Test
	public void karambwanji_severalPerCatch_countedFromTheInventory()
	{
		login();
		t.onSkillXp(tick, 5, now);
		t.onChat(tick, now, "You catch 7 Karambwanji.");
		inv.put(KARAMBWANJI, 7L);
		pushInventory();
		steps(2);
		t.onSkillXp(tick, 5, now);
		t.onChat(tick, now, "You catch 6 Karambwanji.");
		inv.put(KARAMBWANJI, 13L);
		pushInventory();
		settle();
		assertEquals(13L, total(KARAMBWANJI));
		assertEquals("karambwanji", t.getMethodKey());
		assertFalse(FishingCompanionDefinition.INSTANCE.tripMatters("karambwanji"));
	}

	@Test
	public void minnows_stackable_andTheFlyingFishWarning()
	{
		login();
		t.onSkillXp(tick, 26, now);
		inv.put(MINNOW, 12L);
		pushInventory();
		settle();
		assertEquals(12L, total(MINNOW));
		assertEquals("minnows", t.getMethodKey());
		assertFalse(FishingCompanionDefinition.INSTANCE.tripMatters("minnows"));
		t.onChat(tick, now, "A flying fish jumps up and eats some of your minnows!");
		inv.put(MINNOW, 9L);
		pushInventory();
		settle();
		assertEquals("eaten minnows never lower the total", 12L, total(MINNOW));
		assertEquals("Flying fish eating your minnows", t.context(now).get(0).getText());
		now += 15_000L;
		assertTrue(t.context(now).isEmpty());
	}

	// ---- lifecycle -------------------------------------------------------------------------------

	@Test
	public void logout_forgetsContainers_reset_forgetsEverything()
	{
		login(BARREL_OPEN, 1);
		fishIntoBarrel(LOBSTER);
		settle();
		t.onLogout();
		assertFalse(t.hasInventoryBaseline());
		assertFalse(t.getBarrel().isHeld());
		assertNull(t.trip(now));
		assertEquals("method survives a logout", "lobster", t.getMethodKey());
		t.reset();
		assertNull(t.getMethodKey());
		assertNull(t.supply(now));
	}

	// ---- method evidence (barbarian ownership) ----------------------------------------------------

	@Test
	public void methodEvidence_fromCatchLinesAndSpotClicks_drainedOnce()
	{
		login();
		assertNull(t.drainMethodEvidence());
		t.onChat(tick, now, "You catch a leaping salmon.");
		com.osrstelemetry.plugin.skills.SkillTracker.MethodEvidence e = t.drainMethodEvidence();
		assertEquals("barbarian", e.getMethodKey());
		assertEquals(now, e.getAtMillis());
		assertNull("drained", t.drainMethodEvidence());
		t.onSpotClick(now + 600, "BARB_FISH", "Use-rod");
		assertEquals("barbarian", t.drainMethodEvidence().getMethodKey());
		t.onSpotClick(now, "INFERNAL_EEL", "Bait");
		assertNull("unsupported spots prove nothing", t.drainMethodEvidence());
		t.onChat(tick, now, "You catch a slimy eel.");
		assertNull(t.drainMethodEvidence());
	}

	@Test
	public void methodEvidence_isNeverProductionOrASuccess()
	{
		login();
		t.onSpotClick(now, "BARB_FISH", "Use-rod");
		t.onChat(tick, now, "You catch a leaping trout.");
		settle();
		assertTrue("a line with no fish anywhere and no barrel is not a catch", produced.isEmpty());
		assertNull(t.getMethodKey());
	}

	@Test
	public void barbarian_firstCatch_namesTheMethod()
	{
		login(FEATHER, 100);
		fish(LEAPING_TROUT, FEATHER);
		settle();
		assertEquals("barbarian", t.getMethodKey());
		assertEquals(1L, total(LEAPING_TROUT));
		assertTrue(FishingCompanionDefinition.INSTANCE.methodNeedsOwnershipEvidence("barbarian"));
		assertFalse(FishingCompanionDefinition.INSTANCE.methodNeedsOwnershipEvidence("fly"));
		assertFalse(com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition.INSTANCE.methodNeedsOwnershipEvidence("iron"));
	}
}
