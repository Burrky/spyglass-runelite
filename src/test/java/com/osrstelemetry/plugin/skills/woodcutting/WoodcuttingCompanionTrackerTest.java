package com.osrstelemetry.plugin.skills.woodcutting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Woodcutting companion's production model, driven exactly as the
 * collector drives it: causal production, method stability, the infernal
 * axe (produced + auto-burned, never banked, never inferred), the log
 * basket, secondary resources (nests, bark, leaves, fossils), the current
 * tree, and CURRENT TRIP.
 */
public class WoodcuttingCompanionTrackerTest
{
	private static final int YEW = ItemID.YEW_LOGS;
	private static final int MAGIC = ItemID.MAGIC_LOGS;
	private static final int REDWOOD = ItemID.REDWOOD_LOGS;
	private static final int LOGS = ItemID.LOGS;
	private static final int BARK = ItemID.FORESTRY_CURRENCY;
	private static final int INFERNAL = ItemID.INFERNAL_AXE;
	private static final int YEW_TREE = 10822;
	private static final long TILE = 7L;

	private WoodcuttingSim s;

	@Before
	public void setUp()
	{
		s = new WoodcuttingSim();
	}

	// ---- production ------------------------------------------------------------------------

	@Test
	public void eachCut_isProduced_andNamesTheTree()
	{
		s.login();
		s.cuts(YEW, 5);
		assertEquals(5L, s.p(YEW));
		assertEquals("yew", s.t.getMethodKey());
		assertEquals(0L, s.b(YEW));
	}

	@Test
	public void cutWithoutLine_isStillProduced_fromWoodcuttingXp()
	{
		s.login();
		s.cut(YEW, false, 1);
		s.steps(3);
		assertEquals(1L, s.p(YEW));
	}

	@Test
	public void logGainWithoutWoodcutting_isNeverProduction()
	{
		s.login();
		s.inv.put(YEW, 10L);
		s.pushInventory();
		s.steps(4);
		assertEquals(0L, s.p(YEW));
		assertNull(s.t.getMethodKey());
	}

	@Test
	public void withdrawingWithTheBankOpen_isNeverProduction_evenWithXp()
	{
		s.login();
		s.t.setBankOpen(true);
		s.bank.put(YEW, 100L);
		s.pushBank();
		s.step();
		s.t.onSkillXp(s.tick, 175, s.now);
		s.inv.put(YEW, 28L);
		s.bank.put(YEW, 72L);
		s.pushInventory();
		s.pushBank();
		s.steps(4);
		assertEquals(0L, s.p(YEW));
	}

	@Test
	public void depositing_banksAtMostWhatThisSessionProduced()
	{
		s.login(YEW, 3);
		s.cuts(YEW, 10);
		s.depositAll(YEW);
		assertEquals(10L, s.p(YEW));
		assertEquals("only this session's 10, never the 3 brought along", 10L, s.b(YEW));
		assertEquals(1, s.t.drainDeposits().size());
	}

	@Test
	public void droppingOrBurningLater_neverLowersProduction_andNeverBanks()
	{
		s.login();
		s.cuts(YEW, 6);
		s.t.onDropClick(s.tick);
		s.inv.put(YEW, 2L);
		s.pushInventory();
		s.steps(4);
		// Manual firemaking later: logs leave the inventory with Firemaking XP only.
		s.t.onOtherSkillXp("FIREMAKING", s.tick, 200, s.now);
		s.inv.put(YEW, 1L);
		s.pushInventory();
		s.steps(4);
		s.depositAll(YEW);
		assertEquals(6L, s.p(YEW));
		assertEquals(1L, s.b(YEW));
		assertEquals(0L, s.burnedOf(YEW));
	}

	// ---- method stability ------------------------------------------------------------------

	@Test
	public void oneStrayTree_neverSwitchesTheMethod()
	{
		s.login();
		s.cuts(YEW, 3);
		s.cuts(MAGIC, 1);
		assertEquals("yew", s.t.getMethodKey());
		s.cuts(YEW, 1);
		s.cuts(MAGIC, 1);
		assertEquals("yew", s.t.getMethodKey());
		s.cuts(MAGIC, 1);
		assertEquals("two in a row confirm the switch", "magic", s.t.getMethodKey());
	}

	@Test
	public void clickedTree_confirmsASwitchOnItsFirstLog()
	{
		s.login();
		s.cuts(YEW, 3);
		s.click("Magic tree", 10834, 9L);
		s.cuts(MAGIC, 1);
		assertEquals("magic", s.t.getMethodKey());
	}

	@Test
	public void woodcuttingXpWithoutLogs_keepsTheTree_andInventsNothing()
	{
		s.login();
		s.cuts(YEW, 3);
		for (int i = 0; i < 5; i++)
		{
			// Forestry rising roots: Woodcutting XP, no log.
			s.t.onSkillXp(s.tick, 90, s.now);
			s.steps(3);
		}
		assertEquals("yew", s.t.getMethodKey());
		assertEquals(3L, s.p(YEW));
	}

	// ---- infernal axe ------------------------------------------------------------------------

	@Test
	public void infernalBurn_isProducedAndAutoBurned_neverBanked()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.cuts(YEW, 2);
		s.burn(YEW, true);
		s.steps(3);
		assertEquals(3L, s.p(YEW));
		assertEquals(1L, s.burnedOf(YEW));
		s.depositAll(YEW);
		assertEquals("only the two that reached the inventory can bank", 2L, s.b(YEW));
	}

	@Test
	public void infernalBurnWithoutALine_takesTheTreeBeingCut()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.click("Redwood tree", 29668, TILE);
		s.burn(REDWOOD, false);
		s.steps(3);
		assertEquals(1L, s.burnedOf(REDWOOD));
		assertEquals(1L, s.p(REDWOOD));
		assertEquals("redwood", s.t.getMethodKey());
	}

	@Test
	public void infernalBurn_unknownTreeAndNoLine_isNeverGuessed()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.burn(YEW, false);
		s.steps(3);
		assertTrue(s.produced.isEmpty());
		assertTrue(s.burned.isEmpty());
	}

	@Test
	public void firemakingXpWithoutACarriedInfernalAxe_isNeverABurn()
	{
		s.login();
		s.cuts(YEW, 2);
		s.burn(YEW, true);
		s.steps(3);
		assertEquals(0L, s.burnedOf(YEW));
		assertEquals(2L, s.p(YEW));
	}

	@Test
	public void aLogNotAppearing_isNeverInferredAsBurned()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.cuts(YEW, 2);
		// Woodcutting XP and a line, no Firemaking XP, no gain (no basket either): nothing invented.
		s.t.onSkillXp(s.tick, 175, s.now);
		s.t.onChat(s.tick, s.now, "You get some yew logs.");
		s.steps(4);
		assertEquals(2L, s.p(YEW));
		assertEquals(0L, s.burnedOf(YEW));
	}

	@Test
	public void firemakingXpOnALogGainTick_isNotABurn()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.t.onSkillXp(s.tick, 175, s.now);
		s.t.onOtherSkillXp("FIREMAKING", s.tick, 87, s.now);
		s.inv.merge(YEW, 1L, Long::sum);
		s.pushInventory();
		s.steps(4);
		assertEquals(1L, s.p(YEW));
		assertEquals(0L, s.burnedOf(YEW));
	}

	@Test
	public void infernalChopping_reportsFiremakingAsWoodcuttingContext()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.cuts(YEW, 1);
		assertTrue(s.evidence.containsKey("FIREMAKING"));
	}

	@Test
	public void noInfernalAxe_noFiremakingContextEvidence()
	{
		s.login();
		s.cuts(YEW, 3);
		s.burn(YEW, true);
		s.steps(3);
		assertFalse(s.evidence.containsKey("FIREMAKING"));
	}

	@Test
	public void manualFiremakingAlone_reportsNothing()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		for (int i = 0; i < 5; i++)
		{
			s.t.onOtherSkillXp("FIREMAKING", s.tick, 200, s.now);
			s.steps(3);
		}
		assertFalse(s.evidence.containsKey("FIREMAKING"));
		assertTrue(s.produced.isEmpty());
	}

	@Test
	public void sulliuscep_neverReportsFiremakingContext()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.click("Sulliuscep", 30602, TILE);
		s.cuts(ItemID.BITTERCAP_MUSHROOM, 2);
		assertEquals(2L, s.p(ItemID.BITTERCAP_MUSHROOM));
		assertFalse(s.evidence.containsKey("FIREMAKING"));
	}

	@Test
	public void manyBurns_eachOnceAndExact()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.click("Yew tree", YEW_TREE, TILE);
		for (int i = 0; i < 30; i++)
		{
			if (i % 3 == 0)
			{
				s.burn(YEW, i % 2 == 0);
			}
			else
			{
				s.cut(YEW);
			}
			s.steps(2);
		}
		s.steps(3);
		assertEquals(30L, s.p(YEW));
		assertEquals(10L, s.burnedOf(YEW));
	}

	// ---- trip -------------------------------------------------------------------------------

	@Test
	public void trip_estFull_usesOnlyInventorySlotsActuallyFilled()
	{
		s.login(INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.click("Yew tree", YEW_TREE, TILE);
		for (int i = 0; i < 12; i++)
		{
			if (i % 3 == 2)
			{
				s.burn(YEW, true);
			}
			else
			{
				s.cut(YEW);
			}
			s.steps(4);
		}
		s.steps(3);
		SkillCompanionView.TripView trip = s.t.trip(s.now);
		assertNotNull(trip);
		assertEquals(Integer.valueOf(28 - 1 - 8), trip.getSpaceLeft());
		assertNotNull(trip.getEstFullMillis());
		// 8 slots in ~33 s (a cut every 3 s, every third burned): 19 slots ~ 78 s. Counting burns as
		// slots would claim ~52 s.
		assertTrue("est " + trip.getEstFullMillis(), trip.getEstFullMillis() > 65_000L && trip.getEstFullMillis() < 110_000L);
	}

	@Test
	public void basket_catchesLogs_andBanksOnlyWhenEmptied()
	{
		s.login(ItemID.LOG_BASKET_OPEN, 1);
		s.click("Yew tree", YEW_TREE, TILE);
		for (int i = 0; i < 4; i++)
		{
			s.t.onSkillXp(s.tick, 175, s.now);
			s.t.onChat(s.tick, s.now, "You get some yew logs.");
			s.steps(4);
		}
		assertEquals(4L, s.p(YEW));
		assertEquals(Long.valueOf(4L), Long.valueOf(s.t.getLedger().heldInStorage(YEW)));
		SkillCompanionView.TripView trip = s.t.trip(s.now);
		assertEquals("Log basket", trip.getStorageName());
		assertEquals(4, trip.getStorageAddedThisTrip());
		// Emptied into the bank: the bank gains logs the inventory never gave.
		s.t.setBankOpen(true);
		s.bank.put(YEW, 4L);
		s.pushBank();
		s.steps(4);
		s.t.setBankOpen(false);
		assertEquals(4L, s.b(YEW));
	}

	@Test
	public void infernalBurnWithAnOpenBasket_isABurnNotTheBasket()
	{
		s.login(ItemID.LOG_BASKET_OPEN, 1, INFERNAL, 1);
		s.t.setInfernalCarried(true);
		s.burn(YEW, true);
		s.steps(3);
		assertEquals(1L, s.burnedOf(YEW));
		assertEquals(0L, s.t.getLedger().heldInStorage(YEW));
	}

	// ---- secondary --------------------------------------------------------------------------

	@Test
	public void nest_countsOnlyAfterTheGameDroppedOne_exactVariant()
	{
		s.login();
		s.cuts(YEW, 2);
		s.inv.put(ItemID.BIRD_NEST_SEEDS, 1L);
		s.pushInventory();
		s.steps(4);
		assertEquals("a nest picked up with no fall line never counts", 0L, s.p(ItemID.BIRD_NEST_SEEDS));
		s.t.onChat(s.tick, s.now, "A bird's nest falls out of the tree.");
		s.steps(5);
		s.inv.put(ItemID.WC_CLUE_NEST_ELITE, 1L);
		s.pushInventory();
		s.steps(4);
		assertEquals(1L, s.p(ItemID.WC_CLUE_NEST_ELITE));
		// One fall, one nest: a second pickup is not claimed.
		s.inv.put(ItemID.BIRD_NEST_RING, 1L);
		s.pushInventory();
		s.steps(4);
		assertEquals(0L, s.p(ItemID.BIRD_NEST_RING));
		assertEquals("nests are never logs", 2L, s.p(YEW));
	}

	@Test
	public void nest_pickedUpTooLate_neverCounts()
	{
		s.login();
		s.t.onChat(s.tick, s.now, "A bird's nest falls out of the tree.");
		s.steps(250);
		s.inv.put(ItemID.BIRD_NEST_SEEDS, 1L);
		s.pushInventory();
		s.steps(4);
		assertEquals(0L, s.p(ItemID.BIRD_NEST_SEEDS));
	}

	@Test
	public void bark_countsWithTheAwardLine_only()
	{
		s.login();
		s.t.onChat(s.tick, s.now, "You've been awarded 12 Anima-infused bark.");
		s.inv.put(BARK, 12L);
		s.pushInventory();
		s.steps(4);
		assertEquals(12L, s.p(BARK));
		s.inv.put(BARK, 112L);
		s.pushInventory();
		s.steps(4);
		assertEquals("a withdrawal is never earned bark", 12L, s.p(BARK));
	}

	@Test
	public void leaves_countOnlyAsASaplingOrEntReward()
	{
		s.login();
		s.inv.put(ItemID.LEAVES_YEW, 20L);
		s.pushInventory();
		s.steps(4);
		assertEquals(0L, s.p(ItemID.LEAVES_YEW));
		s.t.onObjectSpawned(ObjectID.GATHERING_EVENT_SAPLING_2X2, s.now);
		s.step();
		s.inv.put(ItemID.LEAVES_MAGIC, 20L);
		s.pushInventory();
		s.steps(4);
		assertEquals(20L, s.p(ItemID.LEAVES_MAGIC));
		s.t.onTakeClick(s.tick);
		s.inv.put(ItemID.LEAVES_OAK, 5L);
		s.pushInventory();
		s.steps(4);
		assertEquals("picked up off the ground, not a reward", 0L, s.p(ItemID.LEAVES_OAK));
	}

	@Test
	public void sulliuscep_mushroomsAndFossils_withWoodcuttingXp()
	{
		s.login();
		s.click("Sulliuscep", 30602, TILE);
		s.cuts(ItemID.BITTERCAP_MUSHROOM, 3);
		s.t.onSkillXp(s.tick, 127, s.now);
		s.inv.put(ItemID.FOSSIL_NUMULITE, 7L);
		s.pushInventory();
		s.steps(4);
		assertEquals(3L, s.p(ItemID.BITTERCAP_MUSHROOM));
		assertEquals(7L, s.p(ItemID.FOSSIL_NUMULITE));
		assertEquals("sulliuscep", s.t.getMethodKey());
		assertNull("a sulliuscep moves after every cut: no current tree", s.t.focus(s.now));
	}

	// ---- current tree ------------------------------------------------------------------------

	@Test
	public void currentTree_logsAndTime_untilItFalls()
	{
		s.login();
		s.click("Yew tree", YEW_TREE, TILE);
		assertNull("nothing before its first log", s.t.focus(s.now));
		s.cuts(YEW, 4);
		SkillCompanionView.FocusView f = s.t.focus(s.now);
		assertNotNull(f);
		assertEquals("Yew tree", f.getLabel());
		assertEquals(4L, f.getProduced());
		assertTrue(f.getElapsedMillis() > 0);
		s.t.onObjectDespawned(YEW_TREE, TILE, s.now);
		s.step();
		SkillCompanionView.FocusView fallen = s.t.focus(s.now);
		assertNotNull("the fallen tree stays until a new tree gives a log (no collapse)", fallen);
		assertEquals("Yew tree", fallen.getLabel());
		assertEquals(4L, fallen.getProduced());
		assertEquals("Last tree: 4 logs", fallen.getNote());
		assertTrue(fallen.getNoteTooltip().startsWith("Yew tree fell: 4 logs"));
		assertTrue("no separate fell line", s.t.context(s.now).isEmpty());
	}

	@Test
	public void currentTree_staysStableBetweenTrees_untilTheNewTreeGivesItsFirstLog()
	{
		s.login();
		s.click("Yew tree", YEW_TREE, TILE);
		s.cuts(YEW, 1);
		s.t.onObjectDespawned(YEW_TREE, TILE, s.now);
		s.step();
		long frozen = s.t.focus(s.now).getElapsedMillis();
		s.steps(5);
		assertEquals("a fallen tree's time is final", frozen, (long) s.t.focus(s.now).getElapsedMillis());
		assertEquals("singular", "Last tree: 1 log", s.t.focus(s.now).getNote());
		// Clicking the next tree is only a click: the previous tree stays shown.
		s.click("Yew tree", YEW_TREE, TILE + 7);
		assertEquals(1L, s.t.focus(s.now).getProduced());
		s.cuts(YEW, 2);
		SkillCompanionView.FocusView next = s.t.focus(s.now);
		assertEquals("the new tree's own logs replace it", 2L, next.getProduced());
		assertEquals("the last fallen tree is still noted", "Last tree: 1 log", next.getNote());
		s.t.onObjectDespawned(YEW_TREE, TILE + 7, s.now);
		assertEquals("Last tree: 2 logs", s.t.focus(s.now).getNote());
	}

	@Test
	public void lastTreeText_pluralsAndUnits()
	{
		assertEquals("1 log", WoodcuttingCompanionTracker.countText(1, WoodcuttingMethod.YEW));
		assertEquals("4 logs", WoodcuttingCompanionTracker.countText(4, WoodcuttingMethod.YEW));
		assertEquals("1,204 logs", WoodcuttingCompanionTracker.countText(1_204, WoodcuttingMethod.YEW));
		assertEquals("3 bark", WoodcuttingCompanionTracker.countText(3, WoodcuttingMethod.HOLLOW));
	}

	@Test
	public void currentTree_otherObjectDespawning_isIgnored_newTreeStartsOver()
	{
		s.login();
		s.click("Yew tree", YEW_TREE, TILE);
		s.cuts(YEW, 2);
		s.t.onObjectDespawned(YEW_TREE, TILE + 1, s.now);
		s.t.onObjectDespawned(1234, TILE, s.now);
		assertEquals(2L, s.t.focus(s.now).getProduced());
		s.click("Yew tree", YEW_TREE, TILE + 5);
		s.cuts(YEW, 1);
		assertEquals(1L, s.t.focus(s.now).getProduced());
		s.t.onSceneLoading(s.now);
		assertEquals("a scene reload keeps the last tree shown (no collapse)", 1L, s.t.focus(s.now).getProduced());
		assertNull("a scene reload is never a fallen tree", s.t.focus(s.now).getNote());
		assertTrue(s.t.context(s.now).isEmpty());
	}

	@Test
	public void currentTree_neverEstimatesWhatIsLeft()
	{
		for (java.lang.reflect.Method m : SkillCompanionView.FocusView.class.getDeclaredMethods())
		{
			String n = m.getName().toLowerCase(java.util.Locale.ROOT);
			assertFalse(n, n.contains("remaining") || n.contains("deplet") || n.contains("left"));
		}
	}

	// ---- leprechaun --------------------------------------------------------------------------

	@Test
	public void leprechaunBanking_isAProvenDeposit()
	{
		s.login();
		s.cuts(YEW, 5);
		s.t.onNpcSpawned(NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN, s.now);
		s.t.onLeprechaunClick(s.tick);
		s.steps(2);
		s.inv.put(YEW, 0L);
		s.pushInventory();
		s.steps(4);
		assertEquals(5L, s.b(YEW));
	}

	@Test
	public void droppingNearTheLeprechaun_isNeverBanked()
	{
		s.login();
		s.cuts(YEW, 5);
		s.t.onLeprechaunClick(s.tick);
		s.step();
		s.t.onDropClick(s.tick);
		s.inv.put(YEW, 4L);
		s.pushInventory();
		s.steps(4);
		assertEquals(0L, s.b(YEW));
	}

	// ---- more production / trip / secondary cases -----------------------------------------------

	@Test
	public void lineAndGain_areOneCut_neverTwo()
	{
		s.login();
		for (int i = 0; i < 4; i++)
		{
			s.t.onSkillXp(s.tick, 175, s.now);
			s.t.onChat(s.tick, s.now, "You get some yew logs.");
			s.step();
			s.inv.merge(YEW, 1L, Long::sum);
			s.pushInventory();
			s.steps(4);
		}
		assertEquals(4L, s.p(YEW));
		assertTrue("no basket: nothing routed anywhere else", s.burned.isEmpty());
	}

	@Test
	public void manualFletchingLater_neverSubtracts()
	{
		s.login();
		s.cuts(YEW, 5);
		for (int i = 0; i < 5; i++)
		{
			s.t.onOtherSkillXp("FLETCHING", s.tick, 75, s.now);
			s.inv.merge(YEW, -1L, Long::sum);
			s.inv.merge(ItemID.UNSTRUNG_YEW_LONGBOW, 1L, Long::sum);
			s.pushInventory();
			s.steps(4);
		}
		assertEquals(5L, s.p(YEW));
		assertEquals("bows are never Woodcutting production", 0L, s.p(ItemID.UNSTRUNG_YEW_LONGBOW));
	}

	@Test
	public void mixedTrees_keepTheirOwnProducts()
	{
		s.login();
		s.cuts(YEW, 3);
		s.cuts(MAGIC, 2);
		s.cuts(LOGS, 1);
		assertEquals(3L, s.p(YEW));
		assertEquals(2L, s.p(MAGIC));
		assertEquals(1L, s.p(LOGS));
	}

	@Test
	public void trip_dropsFreeSpace_noRateBeforeEnoughCuts_noTripWithoutATree()
	{
		s.login();
		assertNull("no tree yet: trip still known (the panel hides it without a method)", s.t.trip(s.now).getEstFullMillis());
		s.cuts(YEW, 2);
		SkillCompanionView.TripView early = s.t.trip(s.now);
		assertEquals(Integer.valueOf(26), early.getSpaceLeft());
		assertNull("two cuts are not enough to claim a rate", early.getEstFullMillis());
		s.t.onDropClick(s.tick);
		s.inv.put(YEW, 0L);
		s.pushInventory();
		s.steps(4);
		assertEquals(Integer.valueOf(28), s.t.trip(s.now).getSpaceLeft());
		assertFalse(WoodcuttingCompanionDefinition.INSTANCE.tripMatters(null));
	}

	@Test
	public void leaves_withdrawnDuringAnEvent_areNeverAReward()
	{
		s.login();
		s.t.onObjectSpawned(ObjectID.GATHERING_EVENT_SAPLING_2X2, s.now);
		s.t.setBankOpen(true);
		s.bank.put(ItemID.LEAVES_YEW, 50L);
		s.pushBank();
		s.step();
		s.inv.put(ItemID.LEAVES_YEW, 50L);
		s.bank.put(ItemID.LEAVES_YEW, 0L);
		s.pushInventory();
		s.pushBank();
		s.steps(4);
		s.t.setBankOpen(false);
		assertEquals(0L, s.p(ItemID.LEAVES_YEW));
	}

	@Test
	public void bark_banks_onlyWhatWasEarned()
	{
		s.login(BARK, 100);
		s.t.onChat(s.tick, s.now, "You've been awarded 30 Anima-infused bark.");
		s.inv.put(BARK, 130L);
		s.pushInventory();
		s.steps(4);
		s.depositAll(BARK);
		assertEquals(30L, s.p(BARK));
		assertEquals("the 100 carried in are never this session's", 30L, s.b(BARK));
	}

	@Test
	public void currentTree_changingTreeKind_startsANewTree()
	{
		s.login();
		s.click("Yew tree", YEW_TREE, TILE);
		s.cuts(YEW, 3);
		s.click("Magic tree", 10834, TILE + 9);
		s.cuts(MAGIC, 1);
		SkillCompanionView.FocusView f = s.t.focus(s.now);
		assertEquals("Magic tree", f.getLabel());
		assertEquals(1L, f.getProduced());
		s.cuts(YEW, 1);
		assertEquals("a log from another tree kind never counts on this tree", 1L, s.t.focus(s.now).getProduced());
	}

	// ---- lifecycle ----------------------------------------------------------------------------

	@Test
	public void logout_forgetsInventoryAndTree_keepsNothingStale()
	{
		s.login();
		s.click("Yew tree", YEW_TREE, TILE);
		s.cuts(YEW, 2);
		s.t.onLogout();
		assertNull(s.t.focus(s.now));
		assertNull(s.t.trip(s.now));
		assertFalse(s.t.hasInventoryBaseline());
	}
}
