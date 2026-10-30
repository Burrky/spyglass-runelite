package com.osrstelemetry.plugin.skills.herblore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Herblore companion's RECIPE -> INPUTS -> PROCESSING -> OUTPUT FAMILY
 * model, driven exactly as the collector drives it: a mix is the recipe's
 * output gained + its primary input consumed (+ Herblore XP for every recipe
 * but unfinished potions) on one tick, bank closed; outputs counted in doses
 * of their family; inputs consumed by exact identity; withdrawals, deposits,
 * Potion Storage moves, decanting, combining and Mastering Mixology never
 * production; actual bonus doses / saved secondaries only with the item that
 * explains them; CURRENT BATCH from the limiting ingredient.
 */
public class HerbloreCompanionTrackerTest
{
	static final int SNAP_UNF = ItemID.SNAPDRAGONVIAL;
	static final int EGGS = ItemID.RED_SPIDERS_EGGS;
	static final int SR4 = ItemID._4DOSE2RESTORE;
	static final int SR3 = ItemID._3DOSE2RESTORE;
	static final int SR2 = ItemID._2DOSE2RESTORE;
	static final int SR1 = ItemID._1DOSE2RESTORE;
	static final int RANARR_UNF = ItemID.RANARRVIAL;
	static final int SNAPE = ItemID.SNAPE_GRASS;
	static final int PP3 = ItemID._3DOSEPRAYERRESTORE;
	static final int RANARR = ItemID.RANARR_WEED;
	static final int GRIMY_RANARR = ItemID.UNIDENTIFIED_RANARR;
	static final int VIAL = ItemID.VIAL_WATER;
	static final int SE3 = ItemID._3DOSE2ENERGY;
	static final int SE4 = ItemID._4DOSE2ENERGY;
	static final int AMYLASE = ItemID.AMYLASE;
	static final int STAM3 = ItemID._3DOSESTAMINA;
	static final int STAM4 = ItemID._4DOSESTAMINA;

	HerbloreCompanionTracker t;
	int tick;
	long now;
	Map<Integer, Long> inv;
	Map<Integer, Long> bank;
	final Map<Integer, Long> produced = new LinkedHashMap<>();
	final Map<Integer, Long> banked = new LinkedHashMap<>();
	final Map<Integer, Long> consumed = new LinkedHashMap<>();
	final Map<String, Long> occ = new LinkedHashMap<>();

	@Before
	public void setUp()
	{
		t = new HerbloreCompanionTracker();
		tick = 1_000;
		now = 9_000_000L;
		inv = new LinkedHashMap<>();
		bank = new LinkedHashMap<>();
		produced.clear();
		banked.clear();
		consumed.clear();
		occ.clear();
	}

	void push()
	{
		inv.values().removeIf(v -> v <= 0);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
	}

	void pushBank()
	{
		bank.values().removeIf(v -> v <= 0);
		t.onBank(tick, now, new HashMap<>(bank));
	}

	void step()
	{
		tick++;
		now += 600;
		t.onTick(tick, now, 12_850);
		ProductionLedger.Drain d = t.getLedger().drain();
		d.getProduced().forEach((k, v) -> produced.merge(k, v, Long::sum));
		d.getBanked().forEach((k, v) -> banked.merge(k, v, Long::sum));
		d.getConsumed().forEach((k, v) -> consumed.merge(k, v, Long::sum));
		t.drainOccurrences().forEach((k, v) -> occ.merge(k, v, Long::sum));
	}

	void steps(int n)
	{
		for (int i = 0; i < n; i++)
		{
			step();
		}
	}

	void login(Object... items)
	{
		put(items);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		step();
	}

	void put(Object... items)
	{
		for (int i = 0; i < items.length; i += 2)
		{
			inv.merge((Integer) items[i], ((Number) items[i + 1]).longValue(), Long::sum);
		}
	}

	/** One tick of item deltas, with Herblore XP when {@code xp}. */
	void tickWith(boolean xp, Object... deltas)
	{
		if (xp)
		{
			t.onSkillXp(tick, 142, now);
		}
		put(deltas);
		push();
		step();
	}

	void mixSuperRestore(int times)
	{
		for (int i = 0; i < times; i++)
		{
			tickWith(true, SNAP_UNF, -1, EGGS, -1, SR3, 1);
			step();
		}
		steps(3);
	}

	long o(String key)
	{
		return occ.getOrDefault(key, 0L);
	}

	// ---- production ----------------------------------------------------------------------------

	@Test
	public void standardFinishedPotion_exactOutputDosesAndConsumedInputs()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		mixSuperRestore(3);
		assertEquals(Long.valueOf(3), produced.get(SR3));
		assertEquals(Long.valueOf(3), consumed.get(SNAP_UNF));
		assertEquals(Long.valueOf(3), consumed.get(EGGS));
		assertEquals(3L, o("mix:super_restore"));
		assertEquals("3 mixes x 3 doses", 9L, o("units:super_restore"));
		assertEquals(3L, o("in:super_restore:" + SNAP_UNF));
		assertEquals("super_restore", t.getMethodKey());
	}

	@Test
	public void twoMixesOnOneTick_countTwice_neverDuplicated()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		tickWith(true, SNAP_UNF, -2, EGGS, -2, SR3, 2);
		steps(10);
		assertEquals(2L, o("mix:super_restore"));
		assertEquals(6L, o("units:super_restore"));
		assertEquals(Long.valueOf(2), produced.get(SR3));
		t.settleAll();
		steps(5);
		assertEquals("settling again never re-counts", 2L, o("mix:super_restore"));
	}

	@Test
	public void finishedPotionWithoutHerbloreXp_isNotAMix()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		tickWith(false, SNAP_UNF, -1, EGGS, -1, SR3, 1);
		steps(5);
		assertTrue(produced.isEmpty());
		assertEquals(0L, o("mix:super_restore"));
	}

	@Test
	public void withdrawal_deposit_andStorageMoves_areNeverProduction()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		mixSuperRestore(2);
		t.setBankOpen(true);
		bank.put(SR4, 100L);
		pushBank();
		step();
		// Deposit the two made potions into the bank.
		t.onSkillXp(tick, 142, now);
		put(SR3, -2);
		push();
		bank.put(SR3, 2L);
		pushBank();
		step();
		// Withdraw restores, with stray XP on the tick: never production.
		t.onSkillXp(tick, 142, now);
		put(SR4, 20);
		push();
		bank.put(SR4, 80L);
		pushBank();
		steps(5);
		assertEquals(Long.valueOf(2), produced.get(SR3));
		assertNull(produced.get(SR4));
		assertEquals(Long.valueOf(2), banked.get(SR3));
		assertEquals(2L, o("mix:super_restore"));
	}

	@Test
	public void decanting_isNeverProduction_andKeepsTheDoses()
	{
		login(SR1, 4, ItemID.VIAL_EMPTY, 0);
		Map<Integer, Long> before = new HashMap<>(inv);
		tickWith(false, SR1, -4, SR4, 1, ItemID.VIAL_EMPTY, 3);
		steps(5);
		assertTrue(produced.isEmpty());
		assertEquals("decanting keeps every dose", HerbloreRecipes.SUPER_RESTORE.units(before), HerbloreRecipes.SUPER_RESTORE.units(inv));
	}

	@Test
	public void combiningDoses_nextToARealMix_isNeverProduction()
	{
		login(SNAP_UNF, 14, EGGS, 14, SR1, 1, SR3, 1);
		long dosesBefore = HerbloreRecipes.SUPER_RESTORE.units(inv);
		// XP from a mix the tick before; then combine (1)+(3) -> (4) with no input of the recipe consumed.
		tickWith(true, SNAP_UNF, -1, EGGS, -1, SR3, 1);
		tickWith(false, SR1, -1, SR3, -1, SR4, 1, ItemID.VIAL_EMPTY, 1);
		steps(5);
		assertEquals("only the real mix", 1L, o("mix:super_restore"));
		assertEquals(3L, o("units:super_restore"));
		assertEquals("combining kept the doses", dosesBefore + 3, HerbloreRecipes.SUPER_RESTORE.units(inv));
	}

	@Test
	public void recipeSwitch_staysOneTracker_perRecipeCountsNeverBlend()
	{
		login(RANARR_UNF, 7, SNAPE, 7, SNAP_UNF, 7, EGGS, 7);
		for (int i = 0; i < 3; i++)
		{
			tickWith(true, RANARR_UNF, -1, SNAPE, -1, PP3, 1);
			step();
		}
		steps(3);
		assertEquals("prayer", t.getMethodKey());
		mixSuperRestore(3);
		assertEquals("super_restore", t.getMethodKey());
		assertEquals(3L, o("mix:prayer"));
		assertEquals(3L, o("mix:super_restore"));
		assertEquals(9L, o("units:prayer"));
		assertEquals(9L, o("units:super_restore"));
	}

	@Test
	public void oneStrayChange_neverSwitchesTheRecipe_andUnrelatedDeltasAreIgnored()
	{
		login(SNAP_UNF, 14, EGGS, 14, RANARR_UNF, 1, SNAPE, 1);
		mixSuperRestore(3);
		// One prayer potion between restores: one stray success never replaces an established recipe.
		tickWith(true, RANARR_UNF, -1, SNAPE, -1, PP3, 1);
		steps(2);
		assertEquals("super_restore", t.getMethodKey());
		// A coin pick-up with XP nearby: nothing.
		tickWith(true, ItemID.COINS, 500);
		steps(3);
		assertNull(produced.get(ItemID.COINS));
		assertEquals(3L, o("mix:super_restore"));
	}

	@Test
	public void unfinishedPotion_noXpNeeded_herbAndVialConsumed()
	{
		login(RANARR, 14, VIAL, 14);
		tickWith(false, RANARR, -1, VIAL, -1, RANARR_UNF, 1);
		tickWith(false, RANARR, -1, VIAL, -1, RANARR_UNF, 1);
		steps(5);
		assertEquals(Long.valueOf(2), produced.get(RANARR_UNF));
		assertEquals(Long.valueOf(2), consumed.get(RANARR));
		assertEquals(Long.valueOf(2), consumed.get(VIAL));
		assertEquals("ranarr_unf", t.getMethodKey());
		assertEquals(2L, o("units:ranarr_unf"));
	}

	@Test
	public void herbCleaning_isItsOwnRecipe_withXp()
	{
		login(GRIMY_RANARR, 28);
		for (int i = 0; i < 3; i++)
		{
			tickWith(true, GRIMY_RANARR, -1, RANARR, 1);
		}
		steps(5);
		assertEquals("clean_ranarr", t.getMethodKey());
		assertEquals(Long.valueOf(3), produced.get(RANARR));
		assertEquals(3L, o("mix:clean_ranarr"));
		assertNull("no batch for cleaning", t.event(now));
	}

	// ---- Mastering Mixology --------------------------------------------------------------------

	@Test
	public void mixology_noRecipeProduction_noBatch_noStockpile_andGenericHerbloreStillWorksOutside()
	{
		login(ItemID.MM_MOX_PASTE, 50, RANARR, 10, SR3, 0);
		// Refiner: herbs in, Herblore XP, paste out -- never a recipe.
		tickWith(true, RANARR, -5, ItemID.MM_MOX_PASTE, 30);
		// A potion pack opened: its restores are never production.
		put(ItemID.MM_POTION_PACK_HIGH, 1);
		push();
		step();
		tickWith(false, ItemID.MM_POTION_PACK_HIGH, -1, SR3, 4);
		// Even a cleaning-looking change inside the activity is not a recipe.
		tickWith(true, GRIMY_RANARR, 0);
		steps(5);
		assertTrue(produced.isEmpty());
		assertNull(t.getMethodKey());
		assertNull(t.event(now));
		assertFalse(t.context(now).isEmpty());
		// Leave: the activity's items are gone and its grace passed -- generic Herblore works again.
		inv.remove(ItemID.MM_MOX_PASTE);
		push();
		now += HerbloreCompanionTracker.MIXOLOGY_GRACE_MILLIS + 1_000;
		steps(3);
		put(GRIMY_RANARR, 5);
		push();
		step();
		tickWith(true, GRIMY_RANARR, -1, RANARR, 1);
		steps(5);
		assertEquals(Long.valueOf(1), produced.get(RANARR));
		assertTrue(t.context(now).isEmpty());
	}

	@Test
	public void carryingAnUnopenedRewardPack_neverBlocksRealMixes()
	{
		login(SNAP_UNF, 14, EGGS, 14, ItemID.MM_POTION_PACK_MED, 1);
		mixSuperRestore(2);
		assertEquals(2L, o("mix:super_restore"));
		assertTrue(t.context(now).isEmpty());
	}

	// ---- doses + effects ------------------------------------------------------------------------

	@Test
	public void bonusDose_onlyWithADoseAddingAmulet_andProducedDosesReflectIt()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		t.setWorn(Collections.singleton(ItemID.AMULET_OF_CHEMISTRY));
		tickWith(true, SNAP_UNF, -1, EGGS, -1, SR4, 1);
		tickWith(true, SNAP_UNF, -1, EGGS, -1, SR3, 1);
		steps(5);
		assertEquals(7L, o("units:super_restore"));
		assertEquals(1L, o("bonus:super_restore"));
		assertEquals(Long.valueOf(1), produced.get(SR4));
	}

	@Test
	public void noAmulet_noBonusClaimed_evenWhenAFourDoseArrives()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		tickWith(true, SNAP_UNF, -1, EGGS, -1, SR4, 1);
		steps(5);
		assertEquals(4L, o("units:super_restore"));
		assertEquals(0L, o("bonus:super_restore"));
	}

	@Test
	public void alwaysFourDosePotion_neverABonus()
	{
		login(ItemID.UNFINISHED_ANTIDOTE_, 5, ItemID.YEW_ROOTS, 5);
		t.setWorn(Collections.singleton(ItemID.AMULET_OF_CHEMISTRY));
		tickWith(true, ItemID.UNFINISHED_ANTIDOTE_, -1, ItemID.YEW_ROOTS, -1, ItemID.ANTIDOTE_4, 1);
		steps(5);
		assertEquals(4L, o("units:antidote_plus"));
		assertEquals(0L, o("bonus:antidote_plus"));
	}

	@Test
	public void upgrade_preservesDoses_perDoseIngredient_andBonusIsBeyondTheBaseDoses()
	{
		login(SE3, 5, AMYLASE, 30);
		tickWith(true, SE3, -1, AMYLASE, -3, STAM3, 1);
		steps(4);
		assertEquals(3L, o("units:stamina"));
		assertEquals(0L, o("bonus:stamina"));
		assertEquals(Long.valueOf(3), consumed.get(AMYLASE));
		assertEquals("input counted by the base potion it used", 1L, o("mix:stamina"));
		t.setWorn(Collections.singleton(ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED));
		tickWith(true, SE3, -1, AMYLASE, -3, STAM4, 1);
		steps(4);
		assertEquals(7L, o("units:stamina"));
		assertEquals(1L, o("bonus:stamina"));
	}

	@Test
	public void savedSecondary_isActual_withGoggles_neverWithoutThem_neverWithAReagentPouch()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		mixSuperRestore(1);
		t.setWorn(Collections.singleton(ItemID.MM_ALCHEMIST_HAT));
		tickWith(true, SNAP_UNF, -1, SR3, 1);
		tickWith(true, SNAP_UNF, -1, EGGS, -1, SR3, 1);
		steps(5);
		assertEquals("one mix used no eggs", 1L, o("saved:super_restore"));
		assertEquals(Long.valueOf(2), consumed.get(EGGS));
		// A reagent pouch: secondaries used from it never leave the inventory -> nothing is claimed.
		put(ItemID.MM_SECONDARY_POUCH_OPEN, 1);
		push();
		step();
		tickWith(true, SNAP_UNF, -1, SR3, 1);
		steps(5);
		assertEquals(1L, o("saved:super_restore"));
		// Without goggles: never a saving.
		inv.remove(ItemID.MM_SECONDARY_POUCH_OPEN);
		push();
		t.setWorn(Collections.<Integer>emptySet());
		step();
		tickWith(true, SNAP_UNF, -1, SR3, 1);
		steps(5);
		assertEquals(1L, o("saved:super_restore"));
	}

	@Test
	public void divinePotions_neverClaimSavedCrystalDust()
	{
		login(ItemID._4DOSE2ATTACK, 3, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 12);
		t.setWorn(Collections.singleton(ItemID.MM_ALCHEMIST_HAT));
		tickWith(true, ItemID._4DOSE2ATTACK, -1, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, -3, ItemID._4DOSEDIVINEATTACK, 1);
		steps(5);
		assertEquals(4L, o("units:divine_super_attack"));
		assertEquals(0L, o("saved:divine_super_attack"));
	}

	@Test
	public void alchemistsAmuletCharges_shownOnlyOnceTheNativeCounterMovedWithAnObservedBonus()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		t.setWorn(new HashSet<>(Collections.singleton(ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED)));
		t.setAlchemistCharges(50, now);
		mixSuperRestore(1);
		assertNull("counter never trusted on its own say-so", t.validatedAlchemistCharges());
		t.setAlchemistCharges(49, now);
		tickWith(true, SNAP_UNF, -1, EGGS, -1, SR4, 1);
		steps(5);
		assertEquals(Integer.valueOf(49), t.validatedAlchemistCharges());
		boolean line = false;
		for (SkillCompanionView.ContextLine l : t.event(now).getLines())
		{
			line |= l.getText().startsWith("Alchemist's amulet: 49");
		}
		assertTrue(line);
	}

	// ---- Potion Storage ---------------------------------------------------------------------------

	@Test
	public void storageDeposit_banksTheSessionsDoses_storageWithdrawal_isNothing()
	{
		login(SNAP_UNF, 14, EGGS, 14);
		mixSuperRestore(3);
		t.setBankOpen(true);
		pushBank();
		t.onStorage(tick, now, Collections.singletonMap("super_restore", 400L));
		step();
		// Deposit the 3 restores straight into Potion Storage: the bank gains nothing, the storage +9 doses.
		put(SR3, -3);
		push();
		t.onStorage(tick, now, Collections.singletonMap("super_restore", 409L));
		step();
		// Withdraw 5 (4) from storage: never production.
		put(SR4, 5);
		push();
		t.onStorage(tick, now, Collections.singletonMap("super_restore", 389L));
		steps(5);
		assertEquals(Long.valueOf(3), banked.get(SR3));
		assertNull(produced.get(SR4));
		assertEquals(Long.valueOf(389), t.liveStorageUnits("super_restore"));
	}

	@Test
	public void storageDeposit_ofWhatThePlayerBroughtAlong_isNeverBanked()
	{
		login(SR4, 10);
		t.setBankOpen(true);
		pushBank();
		t.onStorage(tick, now, Collections.singletonMap("super_restore", 0L));
		step();
		put(SR4, -10);
		push();
		t.onStorage(tick, now, Collections.singletonMap("super_restore", 40L));
		steps(5);
		assertTrue(banked.isEmpty());
	}

	// ---- CURRENT BATCH -----------------------------------------------------------------------------

	/**
	 * What the batch can still make: the CAN MAKE cell, or -- when that would
	 * only repeat the limiting input's count (pass 28) -- the number the
	 * limiting input's own tooltip names. Never both.
	 */
	private static String canMake(SkillCompanionView.EventView e)
	{
		String cell = field(e, "CAN MAKE");
		String limit = null;
		for (SkillCompanionView.EventView.Field f : e.getFields())
		{
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("runs out first: you can make ([0-9,]+) more").matcher(f.getTooltip());
			if (m.find())
			{
				assertNull("one limiting input at most", limit);
				assertEquals("the limiting input's count is the batch", f.getValue(), m.group(1));
				limit = m.group(1);
			}
		}
		assertTrue("CAN MAKE or a limiting input, never both", cell == null || limit == null);
		return cell != null ? cell : limit;
	}

	private static String field(SkillCompanionView.EventView e, String caption)
	{
		for (SkillCompanionView.EventView.Field f : e.getFields())
		{
			if (f.getCaption().equals(caption))
			{
				return f.getValue();
			}
		}
		return null;
	}

	@Test
	public void batch_secondaryLimited_unfinishedLimited_equal_insufficient_andLive()
	{
		login(SNAP_UNF, 20, EGGS, 30);
		mixSuperRestore(2);
		SkillCompanionView.EventView e = t.event(now);
		assertEquals("Current batch", e.getTitle());
		assertEquals("18", field(e, "UNFINISHED"));
		assertEquals("28", field(e, "SECONDARY"));
		assertEquals("unfinished-limited", "18", canMake(e));
		put(EGGS, -20);
		push();
		assertEquals("secondary-limited", "8", canMake(t.event(now)));
		put(EGGS, 10);
		push();
		assertEquals("equal", "18", canMake(t.event(now)));
		inv.remove(EGGS);
		push();
		assertEquals("insufficient", "0", canMake(t.event(now)));
	}

	@Test
	public void batch_unfinishedPotion_herbOrVialLimited()
	{
		login(RANARR, 20, VIAL, 50);
		tickWith(false, RANARR, -1, VIAL, -1, RANARR_UNF, 1);
		steps(4);
		SkillCompanionView.EventView e = t.event(now);
		assertEquals("19", field(e, "RANARR"));
		assertEquals("49", field(e, "VIALS"));
		assertEquals("herb-limited", "19", canMake(e));
		put(VIAL, -45);
		push();
		assertEquals("vial-limited", "4", canMake(t.event(now)));
	}

	@Test
	public void batch_upgrade_countsBaseDoses_andPerDoseIngredient()
	{
		login(SE4, 10, SE3, 2, AMYLASE, 30);
		tickWith(true, SE3, -1, AMYLASE, -3, STAM3, 1);
		steps(4);
		SkillCompanionView.EventView e = t.event(now);
		assertEquals("43", field(e, "SUPER ENERGY DOSES"));
		assertEquals("27", field(e, "SECONDARY"));
		assertEquals("27 doses", canMake(e));
	}

	@Test
	public void batch_reagentPouch_makesASecondaryLimitALowerBound()
	{
		login(SNAP_UNF, 20, EGGS, 5);
		mixSuperRestore(1);
		put(ItemID.MM_SECONDARY_POUCH_OPEN, 1);
		push();
		SkillCompanionView.EventView e = t.event(now);
		assertEquals("≥ 4", canMake(e));
		boolean line = false;
		for (SkillCompanionView.ContextLine l : e.getLines())
		{
			line |= l.getText().startsWith("Reagent pouch");
		}
		assertTrue(line);
	}

	static String line(SkillCompanionView.EventView v, String prefix)
	{
		for (SkillCompanionView.ContextLine l : v.getLines())
		{
			if (l.getText().startsWith(prefix))
			{
				return l.getText();
			}
		}
		return null;
	}

	static String itemText(SkillCompanionView.EventView v, int i)
	{
		return v.getItemGroups().get(0).getItems().get(i).getText();
	}

	void seeBank(Object... items)
	{
		t.setBankOpen(true);
		for (int i = 0; i < items.length; i += 2)
		{
			bank.put((Integer) items[i], ((Number) items[i + 1]).longValue());
		}
		pushBank();
		step();
	}

	@Test
	public void inBank_unknownUntilTheBankIsSeen_neverZero_noCanMake()
	{
		login(SNAP_UNF, 10, EGGS, 10);
		assertNull("no recipe yet: no IN BANK", t.stock(now));
		mixSuperRestore(1);
		SkillCompanionView.EventView v = t.stock(now);
		assertEquals("In bank", v.getTitle());
		assertEquals("UNFINISHED  \u2014", itemText(v, 0));
		assertEquals("SECONDARY  \u2014", itemText(v, 1));
		assertEquals(SNAP_UNF, v.getItemGroups().get(0).getItems().get(0).getItemId());
		assertNull(line(v, "Bank can make"));
		assertNull(line(v, "Potential XP"));
		assertEquals("Open your bank to count your supplies", v.getLines().get(0).getText());
		for (SkillCompanionView.ContextLine l : t.event(now).getLines())
		{
			assertFalse("never 'Account potential'", l.getText().contains("Account potential"));
		}
	}

	@Test
	public void inBank_bankCanMake_scarcestInput_potentialXpAtTheObservedXp_neverTheInventory()
	{
		login(SNAP_UNF, 10, EGGS, 10);
		mixSuperRestore(2);
		seeBank(SNAP_UNF, 100, EGGS, 80, ItemID.SNAPDRAGON, 50, VIAL, 60);
		t.setBankOpen(false);
		step();
		SkillCompanionView.EventView v = t.stock(now);
		assertEquals("UNFINISHED  100", itemText(v, 0));
		assertEquals("SECONDARY  80", itemText(v, 1));
		// Eggs are the scarcest; herbs + vials are never counted as unfinished; the 8 carried are CURRENT BATCH's.
		assertEquals("Bank can make: 80", line(v, "Bank can make"));
		assertEquals("80 x 142 XP each mix actually gave", "Potential XP: 11,360", line(v, "Potential XP"));
		assertEquals("8", canMake(t.event(now)));
	}

	@Test
	public void potentialXp_unknownUntilTwoCleanSamplesAgree()
	{
		login(SNAP_UNF, 10, EGGS, 10);
		seeBank(SNAP_UNF, 100, EGGS, 100);
		t.setBankOpen(false);
		step();
		mixSuperRestore(1);
		assertEquals("Bank can make: 100", line(t.stock(now), "Bank can make"));
		assertNull("one sample is not a rate", line(t.stock(now), "Potential XP"));
		// A tick whose XP disagrees (shared / split XP) never makes the value trusted.
		t.onSkillXp(tick, 400, now);
		put(SNAP_UNF, -1, EGGS, -1, SR3, 1);
		push();
		steps(4);
		assertNull(line(t.stock(now), "Potential XP"));
	}

	@Test
	public void inBank_doseKeepingRecipe_storageUnread_isAFloor_thenExactWithStorage()
	{
		login(SE3, 5, AMYLASE, 30);
		tickWith(true, SE3, -1, AMYLASE, -3, STAM3, 1);
		steps(4);
		seeBank(SE4, 10, AMYLASE, 100);
		SkillCompanionView.EventView floor = t.stock(now);
		assertEquals("40 doses in the bank, Potion Storage not read: at least", "Bank can make: \u2265 40 doses", line(floor, "Bank can make"));
		t.onStorage(tick, now, Collections.singletonMap("super_energy", 12L));
		step();
		SkillCompanionView.EventView exact = t.stock(now);
		assertEquals("Bank can make: 52 doses", line(exact, "Bank can make"));
		assertTrue(itemText(exact, 0).endsWith("52 doses"));
	}

	@Test
	public void amuletOfChemistryCrumbled_warnsWhileItMatters_clearsWhenWornAgain()
	{
		login(SNAP_UNF, 10, EGGS, 10);
		t.setWorn(Collections.singleton(ItemID.AMULET_OF_CHEMISTRY));
		mixSuperRestore(1);
		assertTrue(t.context(now).isEmpty());
		t.onChat(tick, now, "Your amulet of chemistry helps you create a 4-dose potion. It then crumbles to dust.");
		t.setWorn(Collections.<Integer>emptySet());
		assertEquals("Amulet of chemistry crumbled", t.context(now).get(0).getText());
		assertEquals(SkillCompanionView.ContextLine.Tone.ALERT, t.context(now).get(0).getTone());
		t.setWorn(Collections.singleton(ItemID.AMULET_OF_CHEMISTRY));
		assertTrue("a new amulet worn: gone", t.context(now).isEmpty());
		t.onChat(tick, now, "Your amulet of chemistry helps you create a 4-dose potion. It then crumbles to dust.");
		t.setWorn(Collections.<Integer>emptySet());
		assertFalse(t.context(now).isEmpty());
		assertTrue("never permanent", t.context(now + HerbloreCompanionTracker.CHEMISTRY_WARNING_MILLIS + 1).isEmpty());
	}

	@Test
	public void batch_followsTheRecipe_andNoneBeforeOne()
	{
		login(SNAP_UNF, 10, EGGS, 10, RANARR_UNF, 10, SNAPE, 3);
		assertNull(t.event(now));
		mixSuperRestore(1);
		assertEquals("9", canMake(t.event(now)));
		for (int i = 0; i < 2; i++)
		{
			tickWith(true, RANARR_UNF, -1, SNAPE, -1, PP3, 1);
			step();
		}
		steps(3);
		assertEquals("prayer", t.getMethodKey());
		assertEquals("1", canMake(t.event(now)));
	}
}
