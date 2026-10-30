package com.osrstelemetry.plugin.skills.mining;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Mining companion's production model, driven exactly as the collector
 * drives it (per tick: XP, chat, inventory / bank snapshots), settled the
 * way the live client settles it.
 *
 * TOTAL = every resource Mining caused (never "the inventory went up", never
 * lowered by drop / bank / move). BANKED = this session's production
 * positively seen entering the bank. Gems keep their exact identity, also
 * when they go straight into an open gem bag.
 */
public class MiningCompanionTrackerTest
{
	private static final int IRON = ItemID.IRON_ORE;
	private static final int COAL = ItemID.COAL;
	private static final int SAPPHIRE = ItemID.UNCUT_SAPPHIRE;
	private static final int RUBY = ItemID.UNCUT_RUBY;
	private static final int SHARDS = ItemID.BLESSED_BONE_SHARD;
	private static final int DEPOSIT = ItemID.CALCIFIED_DEPOSIT;
	private static final int GEM_BAG_OPEN = ItemID.GEM_BAG_OPEN;
	private static final int GEM_BAG = ItemID.GEM_BAG;
	private static final int MINE = 12_850;

	private MiningCompanionTracker t;
	private int tick;
	private long now;
	private Map<Integer, Long> inv;
	private Map<Integer, Long> bank;
	/** Everything produced so far (drained totals + pending). */
	private final Map<Integer, Long> produced = new LinkedHashMap<>();
	private final Map<Integer, Long> banked = new LinkedHashMap<>();

	@Before
	public void setUp()
	{
		t = new MiningCompanionTracker();
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
		SkillResource r = MiningCompanionDefinition.INSTANCE.resource(item);
		return item == GEM_BAG || item == GEM_BAG_OPEN || item == ItemID.GEM_SACK_OPEN || (r != null && r.isStackable());
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

	/** Advance one game tick (600 ms). */
	private void step()
	{
		tick++;
		now += 600;
		t.onTick(tick, now, MINE);
	}

	private void steps(int n)
	{
		for (int i = 0; i < n; i++)
		{
			step();
		}
	}

	/** One ordinary success: Mining XP, the success line and the item, on the same tick. */
	private void mine(int item, long xp, String line)
	{
		t.onMiningXp(tick, xp, now);
		if (line != null)
		{
			t.onChat(tick, now, line);
		}
		inv.merge(item, 1L, Long::sum);
		pushInventory();
		step();
	}

	private void mineIron(int n)
	{
		for (int i = 0; i < n; i++)
		{
			mine(IRON, 35, "You manage to mine some iron.");
			step();
		}
	}

	private void drop(int item, long qty)
	{
		inv.merge(item, -qty, Long::sum);
		pushInventory();
		step();
	}

	/** Deposit with the bank open: inventory down, bank up, on the same tick. */
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

	// ---- one / many / mixed -------------------------------------------------------------------

	@Test
	public void oneSuccess_isOneResource_andNamesTheMethod()
	{
		login();
		mine(IRON, 35, "You manage to mine some iron.");
		settle();
		assertEquals(1L, total(IRON));
		assertEquals("iron", t.getMethodKey());
	}

	@Test
	public void manyResources_areCountedExactly()
	{
		login();
		mineIron(27);
		settle();
		assertEquals(27L, total(IRON));
	}

	@Test
	public void varrockArmourDoubleOre_countsWhatActuallyArrived()
	{
		login();
		t.onMiningXp(tick, 70, now);
		inv.merge(IRON, 2L, Long::sum);
		pushInventory();
		settle();
		assertEquals(2L, total(IRON));
	}

	@Test
	public void mixedResources_keepTheirIdentity()
	{
		login();
		mineIron(3);
		mine(COAL, 50, "You manage to mine some coal.");
		step();
		mine(COAL, 50, "You manage to mine some coal.");
		settle();
		assertEquals(3L, total(IRON));
		assertEquals(2L, total(COAL));
	}

	// ---- causality -----------------------------------------------------------------------------

	@Test
	public void inventoryGainWithoutMining_isNeverProduction()
	{
		login();
		inv.put(IRON, 5L);
		pushInventory();
		settle();
		assertEquals("a pick-up / transfer with no Mining cause", 0L, total(IRON));
	}

	@Test
	public void xpOnAnAdjacentTick_stillCorroborates_once()
	{
		login();
		t.onMiningXp(tick, 35, now);
		step();
		inv.merge(IRON, 1L, Long::sum);
		pushInventory();
		settle();
		assertEquals(1L, total(IRON));
	}

	@Test
	public void repeatedContainerUpdate_isNotDoubleCounted()
	{
		login();
		t.onMiningXp(tick, 35, now);
		inv.merge(IRON, 1L, Long::sum);
		pushInventory();
		pushInventory();
		step();
		pushInventory();
		settle();
		assertEquals(1L, total(IRON));
	}

	@Test
	public void attemptWithNoResource_isNotFabricated()
	{
		login();
		t.onChat(tick, now, "You swing your pick at the rock.");
		steps(5);
		settle();
		assertTrue(produced.isEmpty());
		assertNull(t.getMethodKey());
	}

	@Test
	public void unsupportedMethodXp_producesNothing_andNamesNoMethod()
	{
		login();
		for (int i = 0; i < 5; i++)
		{
			t.onMiningXp(tick, 40, now);
			steps(3);
		}
		settle();
		assertTrue(produced.isEmpty());
		assertNull("ambiguous: generic Mining, never a guess", t.getMethodKey());
	}

	@Test
	public void withdrawal_isNotProduction()
	{
		bank.put(IRON, 500L);
		login();
		t.setBankOpen(true);
		inv.put(IRON, 28L);
		bank.put(IRON, 472L);
		pushInventory();
		pushBank();
		t.onMiningXp(tick, 35, now);
		settle();
		assertEquals("nothing is mined with the bank open", 0L, total(IRON));
	}

	// ---- TOTAL / drop / bank -----------------------------------------------------------------

	@Test
	public void powermining_dropDoesNotLowerTotal()
	{
		login();
		mineIron(28);
		drop(IRON, 28);
		mineIron(28);
		settle();
		assertEquals(56L, total(IRON));
		assertEquals(0L, bankedOf(IRON));
	}

	@Test
	public void banking_isATripBoundary_notAReset()
	{
		login();
		mineIron(28);
		deposit(IRON, 28);
		mineIron(28);
		settle();
		assertEquals(56L, total(IRON));
		assertEquals(28L, bankedOf(IRON));
		deposit(IRON, 28);
		settle();
		assertEquals(56L, total(IRON));
		assertEquals(56L, bankedOf(IRON));
	}

	@Test
	public void ironHundred_bankSeventyTwo_holdTwentyEight()
	{
		login();
		mineIron(28);
		deposit(IRON, 28);
		mineIron(28);
		deposit(IRON, 28);
		mineIron(16);
		deposit(IRON, 16);
		mineIron(28);
		settle();
		assertEquals(100L, total(IRON));
		assertEquals(72L, bankedOf(IRON));
	}

	@Test
	public void broughtAlong_isNeverBanked_asProduction()
	{
		login(IRON, 5);
		mineIron(10);
		deposit(IRON, 15);
		settle();
		assertEquals(10L, total(IRON));
		assertEquals("only this session's own 10", 10L, bankedOf(IRON));
	}

	@Test
	public void withdrawingBankedOre_neverLowersBanked_redepositingNeverCountsAgain()
	{
		login();
		mineIron(28);
		deposit(IRON, 28);
		settle();
		assertEquals(28L, bankedOf(IRON));
		// Withdraw 10 of the banked ore, then put it back: neither changes what this session banked.
		t.setBankOpen(true);
		inv.merge(IRON, 10L, Long::sum);
		bank.merge(IRON, -10L, Long::sum);
		pushInventory();
		pushBank();
		step();
		settle();
		assertEquals("withdrawing never decrements banked", 28L, bankedOf(IRON));
		deposit(IRON, 10);
		settle();
		assertEquals("redepositing never counts again", 28L, bankedOf(IRON));
		assertEquals(28L, total(IRON));
	}

	@Test
	public void droppedThenBanked_banksOnlyWhatIsStillHeld()
	{
		login();
		mineIron(20);
		drop(IRON, 5);
		deposit(IRON, 15);
		settle();
		assertEquals(20L, total(IRON));
		assertEquals(15L, bankedOf(IRON));
	}

	@Test
	public void depositBox_banksInventoryRemovals()
	{
		login();
		mineIron(12);
		t.setDepositBoxOpen(true);
		inv.put(IRON, 0L);
		pushInventory();
		step();
		t.setDepositBoxOpen(false);
		settle();
		assertEquals(12L, bankedOf(IRON));
	}

	@Test
	public void bankRemovalWithoutMatchingBankGain_isNotBanked()
	{
		login();
		mineIron(10);
		t.setBankOpen(true);
		inv.put(IRON, 0L);
		pushInventory();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals("no proven bank increase: conservative", 0L, bankedOf(IRON));
	}

	@Test
	public void deposits_areReportedForTheBankedRate()
	{
		login();
		mineIron(5);
		deposit(IRON, 5);
		settle();
		List<MiningCompanionTracker.Deposit> deposits = t.drainDeposits();
		assertEquals(1, deposits.size());
		assertEquals(5L, deposits.get(0).getQuantity());
		assertTrue(t.drainDeposits().isEmpty());
	}

	// ---- gems ----------------------------------------------------------------------------------

	@Test
	public void gemTableGem_isASecondaryResource_andKeepsTheMethod()
	{
		login();
		mineIron(3);
		t.onChat(tick, now, "You just found a Sapphire!");
		inv.merge(SAPPHIRE, 1L, Long::sum);
		pushInventory();
		step();
		mineIron(2);
		settle();
		assertEquals(1L, total(SAPPHIRE));
		assertEquals(5L, total(IRON));
		assertEquals("iron", t.getMethodKey());
	}

	@Test
	public void gemLineAndInventoryGem_areOneGem()
	{
		login();
		t.onChat(tick, now, "You just found a Ruby!");
		step();
		inv.merge(RUBY, 1L, Long::sum);
		pushInventory();
		settle();
		assertEquals(1L, total(RUBY));
	}

	@Test
	public void sapphireStraightIntoAnOpenGemBag_countsWithItsIdentity()
	{
		login(GEM_BAG_OPEN, 1);
		mineIron(2);
		t.onChat(tick, now, "You just found a Sapphire!");
		t.onChat(tick, now, "You put it straight into your open gem bag.");
		settle();
		assertEquals("TOTAL +1, Sapphire +1, never in the inventory", 1L, total(SAPPHIRE));
		assertEquals(1L, t.getLedger().heldInStorage(SAPPHIRE));
		assertFalse(inv.containsKey(SAPPHIRE));
	}

	@Test
	public void rubyStraightIntoAnOpenGemBag_countsOnce()
	{
		login(GEM_BAG_OPEN, 1);
		t.onChat(tick, now, "You just found a Ruby!");
		t.onChat(tick, now, "You put it straight into your open gem bag.");
		step();
		pushInventory();
		settle();
		assertEquals(1L, total(RUBY));
		assertEquals(1, t.getStorage().getAddedThisTrip());
	}

	@Test
	public void closedGemBag_doesNotCatch_gemGoesToInventory()
	{
		login(GEM_BAG, 1);
		t.onChat(tick, now, "You just found a Ruby!");
		inv.merge(RUBY, 1L, Long::sum);
		pushInventory();
		settle();
		assertEquals(1L, total(RUBY));
		assertEquals(0L, t.getLedger().heldInStorage(RUBY));
	}

	@Test
	public void emptyingTheGemBagToTheInventory_isNotProduction()
	{
		login(GEM_BAG_OPEN, 1);
		for (int i = 0; i < 3; i++)
		{
			t.onChat(tick, now, "You just found a Sapphire!");
			t.onChat(tick, now, "You put it straight into your open gem bag.");
			steps(3);
		}
		inv.put(SAPPHIRE, 3L);
		pushInventory();
		settle();
		assertEquals(3L, total(SAPPHIRE));
		assertEquals("moved, still held: bankable later", 3L, t.getLedger().heldInInventory(SAPPHIRE));
	}

	@Test
	public void tenGemsIntoStorage_sixReliablyBanked()
	{
		login(GEM_BAG_OPEN, 1);
		for (int i = 0; i < 10; i++)
		{
			t.onChat(tick, now, "You just found a Sapphire!");
			t.onChat(tick, now, "You put it straight into your open gem bag.");
			steps(3);
		}
		inv.put(SAPPHIRE, 10L);
		pushInventory();
		steps(3);
		deposit(SAPPHIRE, 6);
		settle();
		assertEquals(10L, total(SAPPHIRE));
		assertEquals(6L, bankedOf(SAPPHIRE));
	}

	@Test
	public void gemBagEmptiedIntoTheBank_banksThisSessionsGems_only()
	{
		login(GEM_BAG_OPEN, 1);
		for (int i = 0; i < 3; i++)
		{
			t.onChat(tick, now, "You just found a Sapphire!");
			t.onChat(tick, now, "You put it straight into your open gem bag.");
			steps(3);
		}
		// "Empty" with the bank open: the bag held 12 old sapphires too.
		t.setBankOpen(true);
		bank.merge(SAPPHIRE, 15L, Long::sum);
		pushBank();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals(3L, total(SAPPHIRE));
		assertEquals("pre-existing bag gems are never claimed", 3L, bankedOf(SAPPHIRE));
		assertEquals("the whole bag went: now known empty", Integer.valueOf(0), t.getStorage().getKnownContents());
	}

	@Test
	public void gemRock_withXpAndNoOre_isTheGemRocksMethod()
	{
		login();
		for (int i = 0; i < 3; i++)
		{
			t.onMiningXp(tick, 65, now);
			inv.merge(i == 0 ? SAPPHIRE : ItemID.UNCUT_OPAL, 1L, Long::sum);
			pushInventory();
			steps(4);
		}
		settle();
		assertEquals("gem_rocks", t.getMethodKey());
		assertEquals(1L, total(SAPPHIRE));
		assertEquals(2L, total(ItemID.UNCUT_OPAL));
	}

	@Test
	public void gemRockIntoTheBag_withNoNamingLine_isCountedUnidentified_neverGuessed()
	{
		login(GEM_BAG_OPEN, 1);
		t.onMineClick(now, "Gem rocks");
		t.onMiningXp(tick, 65, now);
		inv.merge(ItemID.UNCUT_OPAL, 1L, Long::sum);
		pushInventory();
		steps(4);
		t.onMiningXp(tick, 65, now);
		t.onChat(tick, now, "You put it straight into your open gem bag.");
		settle();
		assertEquals(1L, total(MiningCompanionDefinition.UNIDENTIFIED_GEM));
		assertEquals(1L, total(ItemID.UNCUT_OPAL));
	}

	@Test
	public void everyInventoryMethod_isDetectedFromItsOwnProduct()
	{
		for (MiningMethod m : MiningMethod.values())
		{
			if (m == MiningMethod.GEM_ROCKS)
			{
				continue;
			}
			setUp();
			login();
			for (int i = 0; i < 2; i++)
			{
				t.onMiningXp(tick, 30, now);
				inv.merge(m.getPrimaryItems()[0], 1L, Long::sum);
				pushInventory();
				steps(4);
			}
			settle();
			assertEquals(m.name(), m.getKey(), t.getMethodKey());
			assertEquals(m.name(), 2L, total(m.getPrimaryItems()[0]));
		}
	}

	// ---- method stability ----------------------------------------------------------------------

	@Test
	public void oneStraySuccess_doesNotFlipTheMethod()
	{
		login();
		mineIron(5);
		mine(COAL, 50, "You manage to mine some coal.");
		step();
		mineIron(1);
		settle();
		assertEquals("iron", t.getMethodKey());
	}

	@Test
	public void twoConsecutiveSuccesses_switchTheMethod_inTheSameSession()
	{
		login();
		mineIron(5);
		mine(COAL, 50, "You manage to mine some coal.");
		step();
		mine(COAL, 50, "You manage to mine some coal.");
		settle();
		assertEquals("coal", t.getMethodKey());
	}

	@Test
	public void clickedRockPlusOneSuccess_switchesPromptly()
	{
		login();
		mineIron(5);
		t.onMineClick(now, "<col=ffff>Coal rocks");
		mine(COAL, 50, "You manage to mine some coal.");
		settle();
		assertEquals("coal", t.getMethodKey());
	}

	@Test
	public void methodTime_isBookedToTheCurrentMethod()
	{
		login();
		mineIron(20);
		settle();
		Long millis = t.getMethods().getSessionMillis().get("iron");
		assertNotNull(millis);
		assertTrue(millis > 15_000L);
	}

	// ---- calcified rocks ---------------------------------------------------------------------

	@Test
	public void calcified_shardsAreTheMainProduct_depositsSecondary_waterAndSlippingContext()
	{
		login();
		for (int i = 0; i < 4; i++)
		{
			t.onMiningXp(tick, 33, now);
			inv.merge(SHARDS, 2L, Long::sum);
			pushInventory();
			steps(3);
		}
		t.onMiningXp(tick, 36, now);
		inv.merge(DEPOSIT, 1L, Long::sum);
		pushInventory();
		steps(3);
		t.onChat(tick, now, "Some cracks around the cave begin to ooze water.");
		settle();
		assertEquals(8L, total(SHARDS));
		assertEquals(1L, total(DEPOSIT));
		assertEquals("calcified", t.getMethodKey());
		List<SkillCompanionView.ContextLine> ctx = t.context(now);
		assertEquals(1, ctx.size());
		assertTrue(ctx.get(0).getText().startsWith("Water flowing"));
		assertTrue("water lasts ~30 s at most", t.context(now + 31_000L).isEmpty());

		t.onChat(tick, now, "Your pickaxe keeps slipping on the rocks as it's still wet from the last deposit.");
		assertEquals("Pickaxe slipping (world hop)", t.context(now).get(0).getText());
		tick++;
		t.onTick(tick, now, MINE + 1);
		assertTrue("leaving the mine clears it", t.context(now).isEmpty());
	}

	// ---- CURRENT TRIP ---------------------------------------------------------------------------

	@Test
	public void estFull_needsSeveralSuccesses_neverOne()
	{
		login();
		mineIron(1);
		settle();
		SkillCompanionView.TripView trip = t.trip(now);
		assertEquals(Integer.valueOf(27), trip.getSpaceLeft());
		assertNull("one gain never extrapolates", trip.getEstFullMillis());

		for (int i = 0; i < 6; i++)
		{
			mine(IRON, 35, "You manage to mine some iron.");
			steps(5);
		}
		settle();
		trip = t.trip(now);
		assertEquals(Integer.valueOf(21), trip.getSpaceLeft());
		assertNotNull(trip.getEstFullMillis());
		// About one ore per 6 ticks (3.6 s): 21 slots ~ 75 s.
		double seconds = trip.getEstFullMillis() / 1000.0;
		assertTrue("~75 s, was " + seconds, seconds > 65 && seconds < 85);
	}

	@Test
	public void stackableShards_useNoNewSlots_soNoFillRate()
	{
		login();
		for (int i = 0; i < 8; i++)
		{
			t.onMiningXp(tick, 33, now);
			inv.merge(SHARDS, 2L, Long::sum);
			pushInventory();
			steps(4);
		}
		settle();
		assertNull(t.trip(now).getEstFullMillis());
		assertEquals(Integer.valueOf(27), t.trip(now).getSpaceLeft());
	}

	// ---- boundaries -----------------------------------------------------------------------------

	@Test
	public void logout_rebaselinesContainers_withoutInventingProduction()
	{
		login();
		mineIron(3);
		settle();
		t.onLogout();
		// Relogin: the same 3 ore are simply there.
		t.onInventory(tick, now, new HashMap<>(inv), slots());
		t.onMiningXp(tick, 35, now);
		settle();
		assertEquals(3L, total(IRON));
	}

	@Test
	public void reset_forgetsEverything()
	{
		login();
		mineIron(3);
		t.reset();
		assertFalse(t.getLedger().hasPending());
		assertNull(t.getMethodKey());
		assertNull(t.trip(now));
	}
}
