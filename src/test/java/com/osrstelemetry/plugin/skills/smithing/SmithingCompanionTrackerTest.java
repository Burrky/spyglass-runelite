package com.osrstelemetry.plugin.skills.smithing;

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
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Smithing companion's causal model, driven exactly as the collector
 * drives it (per tick: Smithing / Magic XP, inventory / bank diffs, Blast
 * Furnace varbits, chat, coal bag clicks): actual output is the production,
 * actual removals are the inputs; withdrawals, deposits, drops, coal bag
 * moves, belt contributions and Giants' Foundry changes never are.
 */
public class SmithingCompanionTrackerTest
{
	static final int IRON_ORE = ItemID.IRON_ORE;
	static final int COAL = ItemID.COAL;
	static final int STEEL = ItemID.STEEL_BAR;
	static final int MITH = ItemID.MITHRIL_BAR;
	static final int RUNE_BAR = ItemID.RUNITE_BAR;
	static final int OVERWORLD = 12_850;

	SmithingCompanionTracker t;
	int tick;
	long now;
	Map<Integer, Long> inv;
	Map<Integer, Long> bank;
	int region = OVERWORLD;
	final Map<Integer, Long> produced = new LinkedHashMap<>();
	final Map<Integer, Long> banked = new LinkedHashMap<>();
	final Map<Integer, Long> consumed = new LinkedHashMap<>();
	final Map<String, Long> occ = new LinkedHashMap<>();

	@Before
	public void setUp()
	{
		t = new SmithingCompanionTracker();
		tick = 1_000;
		now = 9_000_000L;
		inv = new LinkedHashMap<>();
		bank = new LinkedHashMap<>();
		produced.clear();
		banked.clear();
		consumed.clear();
		occ.clear();
	}

	// ---- driving -----------------------------------------------------------------------------------------

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
		t.onTick(tick, now, region);
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

	/** One action on this tick: inventory changes, Smithing XP, optional Magic XP; then the tick closes. */
	void act(long xp, long magicXp, Object... changes)
	{
		put(changes);
		push();
		if (xp > 0)
		{
			t.onSkillXp(tick, xp, now);
		}
		if (magicXp > 0)
		{
			t.onOtherSkillXp("MAGIC", tick, magicXp, now);
		}
		step();
	}

	long p(int item)
	{
		return produced.getOrDefault(item, 0L);
	}

	long c(int item)
	{
		return consumed.getOrDefault(item, 0L);
	}

	long o(String key)
	{
		return occ.getOrDefault(key, 0L);
	}

	void settle()
	{
		steps(3);
	}

	// ---- smelting ----------------------------------------------------------------------------------------

	@Test
	public void smeltingSteel_actualBarsAndExactInputs_recipeBecomesTheMethod()
	{
		login(IRON_ORE, 14, COAL, 28);
		for (int i = 0; i < 3; i++)
		{
			act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
			steps(3);
		}
		settle();
		assertEquals(3L, p(STEEL));
		assertEquals(3L, c(IRON_ORE));
		assertEquals(6L, c(COAL));
		assertEquals(3L, o("made:smelt_steel"));
		assertEquals(6L, o("in:smelt_steel:" + COAL));
		assertEquals("smelt_steel", t.getMethodKey());
	}

	@Test
	public void everyFurnaceBar_smeltsAsItsOwnRecipe_withItsOwnInputs()
	{
		for (SmithingRecipes.Bar bar : SmithingRecipes.bars())
		{
			setUp();
			SmithingRecipe r = SmithingRecipes.recipe(SmithingRecipes.SMELT + bar.getKey());
			Object[] start = new Object[r.getInputs().size() * 2];
			Object[] use = new Object[r.getInputs().size() * 2 + 2];
			for (int i = 0; i < r.getInputs().size(); i++)
			{
				SmithingRecipe.Input in = r.getInputs().get(i);
				start[2 * i] = in.getItemId();
				start[2 * i + 1] = 30;
				use[2 * i] = in.getItemId();
				use[2 * i + 1] = -in.getPerAction();
			}
			use[use.length - 2] = bar.getBarItem();
			use[use.length - 1] = 1;
			login(start);
			act(10, 0, use);
			act(10, 0, use);
			settle();
			assertEquals(bar.getKey(), 2L, p(bar.getBarItem()));
			for (SmithingRecipe.Input in : r.getInputs())
			{
				assertEquals(bar.getKey() + " " + in.getName(), 2L * in.getPerAction(), c(in.getItemId()));
			}
			assertEquals(bar.getKey(), r.getKey(), t.getMethodKey());
		}
	}

	@Test
	public void ironTooImpure_isAFailedSmelt_theOreIsAnInput_noBarIsInvented()
	{
		login(IRON_ORE, 10);
		act(12, 0, IRON_ORE, -1, ItemID.IRON_BAR, 1);
		put(IRON_ORE, -1);
		push();
		t.onChat(tick, now, "The ore is too impure and you fail to refine it.");
		step();
		act(12, 0, IRON_ORE, -1, ItemID.IRON_BAR, 1);
		settle();
		assertEquals(2L, p(ItemID.IRON_BAR));
		assertEquals(3L, c(IRON_ORE));
		assertEquals(1L, o("fail:smelt_iron"));
		assertEquals(2L, o("made:smelt_iron"));
	}

	@Test
	public void oreDisappearingWithoutTheGamesLine_isNeverAFailure()
	{
		login(IRON_ORE, 10);
		act(0, 0, IRON_ORE, -1);
		settle();
		assertEquals(0L, o("fail:smelt_iron"));
		assertEquals("a drop is not an input", 0L, c(IRON_ORE));
	}

	// ---- never production -----------------------------------------------------------------------------------

	@Test
	public void withdrawalDepositDropRearrange_areNeverProduction_depositOfOwnBarsIsBanked()
	{
		login(IRON_ORE, 10, COAL, 20);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		settle();
		// Deposit both, withdraw 10 others.
		bank.put(STEEL, 100L);
		pushBank();
		t.setBankOpen(true);
		put(STEEL, -2);
		push();
		bank.merge(STEEL, 2L, Long::sum);
		pushBank();
		step();
		put(STEEL, 10);
		push();
		bank.merge(STEEL, -10L, Long::sum);
		pushBank();
		step();
		t.setBankOpen(false);
		settle();
		// A gain with no XP (picking up / rearranging) is nothing.
		act(0, 0, STEEL, 3);
		settle();
		assertEquals(2L, p(STEEL));
		assertEquals(2L, (long) banked.getOrDefault(STEEL, 0L));
	}

	@Test
	public void outputWithoutItsInputConsumed_orWithoutXp_isNeverAnAction()
	{
		login(MITH, 5);
		act(250, 0, ItemID.MITHRIL_PLATEBODY, 1);
		steps(3);
		act(0, 0, MITH, -5, ItemID.MITHRIL_PLATEBODY, 1);
		settle();
		assertEquals(0L, p(ItemID.MITHRIL_PLATEBODY));
		assertNull(t.getMethodKey());
	}

	// ---- anvil ---------------------------------------------------------------------------------------------

	@Test
	public void anvil_oneBarMultiBarMultiOutput_actualOutputAndExactBars_recipeSwitch()
	{
		login(MITH, 73, RUNE_BAR, 10, ItemID.HAMMER, 1);
		for (int i = 0; i < 3; i++)
		{
			act(250, 0, MITH, -5, ItemID.MITHRIL_PLATEBODY, 1);
			steps(4);
		}
		assertEquals("mithril_platebody", t.getMethodKey());
		for (int i = 0; i < 2; i++)
		{
			act(150, 0, MITH, -3, ItemID.MITHRIL_PLATELEGS, 1);
			steps(4);
		}
		for (int i = 0; i < 2; i++)
		{
			act(75, 0, RUNE_BAR, -1, ItemID.RUNE_DART_TIP, 10);
			steps(4);
		}
		settle();
		assertEquals(3L, p(ItemID.MITHRIL_PLATEBODY));
		assertEquals(2L, p(ItemID.MITHRIL_PLATELEGS));
		assertEquals(20L, p(ItemID.RUNE_DART_TIP));
		assertEquals(21L, c(MITH));
		assertEquals(2L, c(RUNE_BAR));
		assertEquals(3L, o("made:mithril_platebody"));
		assertEquals(20L, o("made:rune_dart_tips"));
		assertEquals(15L, o("in:mithril_platebody:" + MITH));
		assertEquals("rune_dart_tips", t.getMethodKey());
	}

	@Test
	public void multiOutput_actualArrivalIsTheCount_neverTheRecipesTheory()
	{
		login(ItemID.STEEL_BAR, 10);
		act(37, 0, STEEL, -1, ItemID.NAILS, 15);
		act(37, 0, STEEL, -1, ItemID.NAILS, 13);
		settle();
		assertEquals(28L, p(ItemID.NAILS));
		assertEquals(28L, o("made:steel_nails"));
	}

	// ---- superheat ------------------------------------------------------------------------------------------

	@Test
	public void superheat_magicXpOnTheSameTick_isItsOwnProcess_furnaceIsNot()
	{
		login(IRON_ORE, 20, COAL, 40, ItemID.NATURERUNE, 20);
		act(17, 53, IRON_ORE, -1, COAL, -2, ItemID.NATURERUNE, -1, STEEL, 1);
		act(17, 53, IRON_ORE, -1, COAL, -2, ItemID.NATURERUNE, -1, STEEL, 1);
		steps(3);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		settle();
		assertEquals(3L, p(STEEL));
		assertEquals(2L, o("made:superheat_steel"));
		assertEquals(1L, o("made:smelt_steel"));
		assertEquals("runes are Magic's, never a Smithing input", 0L, c(ItemID.NATURERUNE));
		assertEquals(3L, c(IRON_ORE));
	}

	// ---- cannonballs ----------------------------------------------------------------------------------------

	@Test
	public void cannonballs_theBarPouredTicksBefore_matchesTheArrival_singleAndDoubleMould()
	{
		login(STEEL, 27, ItemID.AMMO_MOULD, 1);
		act(0, 0, STEEL, -1);
		steps(6);
		act(25, 0, ItemID.MCANNONBALL, 4);
		steps(3);
		assertEquals(4L, p(ItemID.MCANNONBALL));
		assertEquals(1L, c(STEEL));
		// Double mould: two bars, eight cannonballs.
		put(ItemID.AMMO_MOULD, -1, ItemID.DOUBLE_AMMO_MOULD, 1);
		push();
		step();
		act(0, 0, STEEL, -2);
		steps(6);
		act(51, 0, ItemID.MCANNONBALL, 8);
		settle();
		assertEquals(12L, p(ItemID.MCANNONBALL));
		assertEquals(3L, c(STEEL));
		assertEquals(12L, o("made:cannonball_steel"));
	}

	@Test
	public void cannonballsWithoutAPour_orWithoutXp_areNotProduction()
	{
		login(STEEL, 27, ItemID.AMMO_MOULD, 1);
		act(25, 0, ItemID.MCANNONBALL, 4);
		steps(40);
		act(0, 0, STEEL, -1);
		steps(6);
		act(0, 0, ItemID.MCANNONBALL, 4);
		settle();
		assertEquals(0L, p(ItemID.MCANNONBALL));
	}

	@Test
	public void sailingCannonballs_everyTierIsAMould()
	{
		login(ItemID.RUNITE_BAR, 5, ItemID.DOUBLE_AMMO_MOULD, 1);
		act(0, 0, ItemID.RUNITE_BAR, -2);
		steps(6);
		act(200, 0, ItemID.RUNE_CANNONBALL, 8);
		settle();
		assertEquals(8L, p(ItemID.RUNE_CANNONBALL));
		assertEquals("cannonball_rune", t.getMethodKey() == null ? "cannonball_rune" : t.getMethodKey());
		assertEquals(8L, o("made:cannonball_rune"));
	}

	@Test
	public void chainshot_combinesTwoCannonballsAndAChain()
	{
		login(ItemID.MCANNONBALL, 10, ItemID.CHAIN, 3);
		act(24, 0, ItemID.MCANNONBALL, -2, ItemID.CHAIN, -1, ItemID.STEEL_CHAINSHOT_CANNONBALL, 2);
		settle();
		assertEquals(2L, p(ItemID.STEEL_CHAINSHOT_CANNONBALL));
		assertEquals(2L, c(ItemID.MCANNONBALL));
		assertEquals(1L, c(ItemID.CHAIN));
	}

	// ---- Blast Furnace ------------------------------------------------------------------------------------

	void furnace(int varbit, int value)
	{
		t.onFurnaceVar(tick, now, varbit, value);
	}

	void baseline()
	{
		for (SmithingRecipes.FurnaceVar f : SmithingRecipes.furnaceOres())
		{
			furnace(f.getVarbit(), 0);
		}
		for (SmithingRecipes.Bar b : SmithingRecipes.bars())
		{
			if (b.getBfBarVarbit() > 0)
			{
				furnace(b.getBfBarVarbit(), 0);
			}
		}
	}

	@Test
	public void blastFurnace_beltIsNotProduction_collectionIs_inputsSettleExactly_noDuplicate()
	{
		login(IRON_ORE, 27, ItemID.COAL_BAG, 1);
		baseline();
		// Belt: 27 iron and 27 coal into the furnace (inventory down, furnace up).
		put(IRON_ORE, -27);
		push();
		furnace(VarbitID.BLAST_FURNACE_IRON_ORE, 27);
		furnace(VarbitID.BLAST_FURNACE_COAL, 27);
		step();
		settle();
		assertEquals("belt contribution is never production", 0L, p(STEEL));
		assertEquals("nor consumption", 0L, c(IRON_ORE));
		// Smelt: furnace down, dispenser up (with XP), still nothing produced.
		furnace(VarbitID.BLAST_FURNACE_IRON_ORE, 0);
		furnace(VarbitID.BLAST_FURNACE_COAL, 0);
		furnace(VarbitID.BLAST_FURNACE_STEEL_BARS, 27);
		t.onSkillXp(tick, 472, now);
		step();
		settle();
		assertEquals(0L, p(STEEL));
		assertEquals(27L, t.pendingBars(STEEL));
		// Collect in two parts.
		put(STEEL, 14);
		push();
		furnace(VarbitID.BLAST_FURNACE_STEEL_BARS, 13);
		step();
		settle();
		put(STEEL, 13);
		push();
		furnace(VarbitID.BLAST_FURNACE_STEEL_BARS, 0);
		step();
		settle();
		assertEquals(27L, p(STEEL));
		assertEquals(27L, c(IRON_ORE));
		assertEquals(27L, c(COAL));
		assertEquals(27L, o("made:bf_steel"));
		assertEquals(0L, o("made:smelt_steel"));
		assertEquals(0L, t.pendingBars(STEEL));
		assertEquals("bf_steel", t.getMethodKey());
	}

	@Test
	public void blastFurnace_barsSmeltedBeforeSpyglassSawThem_collectWithoutInventedInputs()
	{
		login(ItemID.ICE_GLOVES, 0);
		furnace(VarbitID.BLAST_FURNACE_GOLD_BARS, 20);
		step();
		put(ItemID.GOLD_BAR, 20);
		push();
		furnace(VarbitID.BLAST_FURNACE_GOLD_BARS, 0);
		step();
		settle();
		assertEquals(20L, p(ItemID.GOLD_BAR));
		assertEquals("no inputs were seen: none are invented", 0L, c(ItemID.GOLD_ORE));
		assertEquals(20L, o("made:bf_gold"));
	}

	@Test
	public void blastFurnace_aGainWithoutTheDispenserFalling_isNotCollection()
	{
		login();
		baseline();
		bank.put(STEEL, 50L);
		pushBank();
		t.setBankOpen(true);
		put(STEEL, 27);
		push();
		bank.put(STEEL, 23L);
		pushBank();
		step();
		t.setBankOpen(false);
		settle();
		assertEquals(0L, p(STEEL));
	}

	// ---- coal bag ---------------------------------------------------------------------------------------------

	@Test
	public void coalBag_unknownUntilChecked_fillAndEmptyAreStorageNeverConsumption()
	{
		login(COAL, 27, ItemID.COAL_BAG, 1, IRON_ORE, 27);
		assertNull(t.getCoalBag());
		t.onChat(tick, now, "The coal bag contains 9 pieces of coal.");
		settle();
		assertEquals(Long.valueOf(9L), t.getCoalBag());
		t.onCoalBagOption(tick, "Fill");
		put(COAL, -18);
		push();
		step();
		settle();
		assertEquals(Long.valueOf(27L), t.getCoalBag());
		t.onCoalBagOption(tick, "Empty");
		put(COAL, 27);
		push();
		step();
		settle();
		assertEquals(Long.valueOf(0L), t.getCoalBag());
		assertEquals("bag movement is never consumption", 0L, c(COAL));
		t.onChat(tick, now, "The coal bag is empty.");
		settle();
		assertEquals(Long.valueOf(0L), t.getCoalBag());
	}

	@Test
	public void coalBag_aSmeltThatMayHaveUsedTheBag_makesItUnknown_neverGuessed()
	{
		login(IRON_ORE, 10, COAL, 1, ItemID.COAL_BAG_OPEN, 1);
		t.onChat(tick, now, "The coal bag contains 27 pieces of coal.");
		settle();
		act(17, 0, IRON_ORE, -1, COAL, -1, STEEL, 1);
		settle();
		assertEquals(1L, p(STEEL));
		assertEquals("only what left the inventory is an observed input", 1L, c(COAL));
		assertNull("the bag supplied the rest: unknown, never guessed", t.getCoalBag());
	}

	// ---- Giants' Foundry ------------------------------------------------------------------------------------

	@Test
	public void giantsFoundry_nothingIsARecipeABatchOrProduction_leavingResumes()
	{
		login(MITH, 28, ItemID.HAMMER, 1);
		region = SmithingCompanionTracker.FOUNDRY_REGION;
		step();
		assertTrue(t.isInFoundry());
		act(300, 0, MITH, -14);
		act(300, 0, MITH, -5, ItemID.MITHRIL_PLATEBODY, 1);
		settle();
		assertEquals(0L, p(ItemID.MITHRIL_PLATEBODY));
		assertEquals(0L, c(MITH));
		assertNull(t.event(now));
		assertEquals(1, t.context(now).size());
		region = OVERWORLD;
		steps(4);
		act(250, 0, MITH, -5, ItemID.MITHRIL_PLATEBODY, 1);
		settle();
		assertEquals(1L, p(ItemID.MITHRIL_PLATEBODY));
		assertEquals(5L, c(MITH));
	}

	// ---- CURRENT BATCH / ACCOUNT POTENTIAL ------------------------------------------------------------------

	static String field(SkillCompanionView.EventView e, String caption)
	{
		for (SkillCompanionView.EventView.Field f : e.getFields())
		{
			if (caption.equals(f.getCaption()))
			{
				return f.getValue();
			}
		}
		return null;
	}

	static String line(SkillCompanionView.EventView e, String prefix)
	{
		for (SkillCompanionView.ContextLine l : e.getLines())
		{
			if (l.getText().startsWith(prefix))
			{
				return l.getText();
			}
		}
		return null;
	}

	static String itemText(SkillCompanionView.EventView e, int i)
	{
		return e.getItemGroups().get(0).getItems().get(i).getText();
	}

	static String contextLine(List<SkillCompanionView.ContextLine> lines, String prefix)
	{
		for (SkillCompanionView.ContextLine l : lines)
		{
			if (l.getText().startsWith(prefix))
			{
				return l.getText();
			}
		}
		return null;
	}

	@Test
	public void noGenericBatch_forAnvilOrSmelting_canMakeFromBank_onlyWithTheBankSeen()
	{
		login(MITH, 78, ItemID.HAMMER, 1);
		act(250, 0, MITH, -5, ItemID.MITHRIL_PLATEBODY, 1);
		act(250, 0, MITH, -5, ItemID.MITHRIL_PLATEBODY, 1);
		settle();
		assertNull("no generic CURRENT BATCH at an anvil", t.event(now));
		SkillCompanionView.EventView st = t.stock(now);
		assertEquals("In bank", st.getTitle());
		assertNull("bank never seen: unknown, never zero", st.getItemGroups().get(0).getItems().get(0).getValues().get(0));
		assertEquals("Open your bank to count your supplies", st.getLines().get(0).getText());
		bank.put(MITH, 1_362L);
		pushBank();
		step();
		st = t.stock(now);
		assertEquals(Long.valueOf(1_362L), st.getItemGroups().get(0).getItems().get(0).getValues().get(0));
		assertEquals("1,362 / 5 (never the 68 carried, never 'Account potential')", "Can make from bank: 272 items",
			st.getLines().get(0).getText());

		setUp();
		login(IRON_ORE, 83, COAL, 142);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		settle();
		assertNull("no generic CURRENT BATCH while smelting", t.event(now));
		bank.put(IRON_ORE, 100L);
		bank.put(COAL, 150L);
		pushBank();
		step();
		assertEquals("coal is the scarcest: 150 / 2", "Can make from bank: 75 bars", t.stock(now).getLines().get(0).getText());
	}

	@Test
	public void coalBag_isAQuietContextLine_catalystsAndTheBagStillCountInCanMake()
	{
		login(IRON_ORE, 20, COAL, 10, ItemID.COAL_BAG, 1);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		act(17, 0, IRON_ORE, -1, COAL, -2, STEEL, 1);
		settle();
		assertEquals("Coal bag: not checked", contextLine(t.context(now), "Coal bag"));
		t.onChat(tick, now, "The coal bag contains 27 pieces of coal.");
		settle();
		assertEquals("Coal bag: 27 coal", contextLine(t.context(now), "Coal bag"));
		SmithingRecipe steel = SmithingRecipes.recipe("smelt_steel");
		Map<Integer, Long> carried = new HashMap<>();
		carried.put(IRON_ORE, 18L);
		carried.put(COAL, 6L);
		assertEquals("(6 + 27) / 2 = 16", 16L, steel.canMake(carried, 0L, 27L));
		// 4 catalysed bars use 1 coal each (4), the remaining 29 coal make 14 more: 18.
		assertEquals(18L, steel.canMake(carried, 4L, 27L));
	}

	@Test
	public void blastFurnace_coalLoaded_tripsBeforeCoal_barsReady_onlyWhatIsThere()
	{
		login(IRON_ORE, 27);
		baseline();
		furnace(VarbitID.BLAST_FURNACE_STEEL_BARS, 27);
		step();
		put(STEEL, 27);
		push();
		furnace(VarbitID.BLAST_FURNACE_STEEL_BARS, 0);
		step();
		settle();
		assertNull("an empty furnace: no dead zero cells, no panel", t.event(now));
		furnace(VarbitID.BLAST_FURNACE_COAL, 54);
		furnace(VarbitID.BLAST_FURNACE_IRON_ORE, 27);
		step();
		SkillCompanionView.EventView e = t.event(now);
		assertEquals("Blast Furnace", e.getTitle());
		assertTrue(e.getFields().isEmpty());
		assertEquals("54 loaded", itemText(e, 0));
		assertEquals(COAL, e.getItemGroups().get(0).getItems().get(0).getItemId());
		assertEquals("27 loaded", itemText(e, 1));
		assertEquals("no bars yet: no 'ready' sprite", 2, e.getItemGroups().get(0).getItems().size());
		assertEquals("54 coal / (1 per steel bar x 27 ore a trip)", "Trips before coal: 2", line(e, "Trips before coal"));
		furnace(VarbitID.BLAST_FURNACE_STEEL_BARS, 13);
		furnace(VarbitID.BLAST_FURNACE_BARS_HOT, 1);
		step();
		e = t.event(now);
		assertEquals("13 ready", itemText(e, 2));
		assertEquals(STEEL, e.getItemGroups().get(0).getItems().get(2).getItemId());
		assertNotNull(line(e, "Bars are hot"));
		t.setWorn(new HashSet<>(Collections.singletonList(ItemID.ICE_GLOVES)));
		assertNull(line(t.event(now), "Bars are hot"));
		for (SkillCompanionView.ContextLine l : t.event(now).getLines())
		{
			assertFalse("never a theoretical XP rate", l.getText().contains("XP"));
		}
	}

	@Test
	public void blastFurnace_gold_isSimpler_noCoalNoTrips()
	{
		login(ItemID.GOLD_ORE, 27);
		baseline();
		furnace(VarbitID.BLAST_FURNACE_GOLD_BARS, 27);
		step();
		put(ItemID.GOLD_BAR, 27);
		push();
		furnace(VarbitID.BLAST_FURNACE_GOLD_BARS, 0);
		step();
		settle();
		furnace(VarbitID.BLAST_FURNACE_GOLD_ORE, 27);
		step();
		SkillCompanionView.EventView e = t.event(now);
		assertNotNull(e);
		assertEquals(1, e.getItemGroups().get(0).getItems().size());
		assertEquals("27 loaded", itemText(e, 0));
		assertNull(line(e, "Trips before coal"));
	}

	@Test
	public void iron_noRingOfForging_isARedWarning_goneTheMomentItNoLongerApplies()
	{
		login(IRON_ORE, 20);
		act(12, 0, IRON_ORE, -1, ItemID.IRON_BAR, 1);
		act(12, 0, IRON_ORE, -1, ItemID.IRON_BAR, 1);
		settle();
		List<SkillCompanionView.ContextLine> lines = t.context(now);
		assertEquals("No Ring of Forging equipped", lines.get(0).getText());
		assertEquals("Iron may fail to smelt", lines.get(1).getText());
		assertEquals(SkillCompanionView.ContextLine.Tone.DANGER, lines.get(0).getTone());
		long before = t.getRevision();
		t.setWorn(new HashSet<>(Collections.singletonList(ItemID.RING_OF_FORGING)));
		assertTrue("the panel repaints at once", t.getRevision() > before);
		assertTrue(t.context(now).isEmpty());
		t.setWorn(Collections.<Integer>emptySet());
		assertFalse(t.context(now).isEmpty());
		inv.remove(IRON_ORE);
		push();
		settle();
		assertTrue("no iron left: no warning", t.context(now).isEmpty());
	}

	@Test
	public void beforeAnyRecipeEvidence_noBatch()
	{
		login(IRON_ORE, 20, COAL, 40);
		assertNull(t.event(now));
		assertFalse(t.getLedger().hasPending());
	}
}
