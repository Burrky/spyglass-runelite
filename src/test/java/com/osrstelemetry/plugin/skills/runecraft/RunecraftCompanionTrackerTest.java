package com.osrstelemetry.plugin.skills.runecraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Runecraft companion's INPUT -> OUTPUT model, driven as the collector
 * drives it: runes produced exactly as they arrived, inputs consumed by exact
 * identity, never confused with banking, withdrawals, pouch moves or
 * Guardians of the Rift; the method from the craft itself; pouches from the
 * game's own state; trips bank -> craft -> bank (never fabricated mid-run)
 * with per-trip yields; binds; the Arceuus loop's phases; Abyss as route.
 */
public class RunecraftCompanionTrackerTest
{
	static final int PURE = ItemID.BLANKRUNE_HIGH;
	static final int NATURE = ItemID.NATURERUNE;
	static final int FRAGMENTS = ItemID.BIGBLANKRUNE;
	private static final int REGION = 9_547;

	RunecraftCompanionTracker t;
	int tick;
	long now;
	Map<Integer, Long> inv;
	Map<Integer, Long> bank;
	final Map<Integer, Long> produced = new LinkedHashMap<>();
	final Map<Integer, Long> banked = new LinkedHashMap<>();
	final Map<Integer, Long> consumed = new LinkedHashMap<>();
	final Map<String, Long> occurrences = new LinkedHashMap<>();
	final Map<String, Long> evidence = new LinkedHashMap<>();
	int region = REGION;

	@Before
	public void setUp()
	{
		t = new RunecraftCompanionTracker();
		tick = 500;
		now = 7_000_000L;
		inv = new LinkedHashMap<>();
		bank = new LinkedHashMap<>();
		produced.clear();
		banked.clear();
		consumed.clear();
		occurrences.clear();
		evidence.clear();
		region = REGION;
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
		t.onTick(tick, now, region);
		ProductionLedger.Drain d = t.getLedger().drain();
		d.getProduced().forEach((k, v) -> produced.merge(k, v, Long::sum));
		d.getBanked().forEach((k, v) -> banked.merge(k, v, Long::sum));
		d.getConsumed().forEach((k, v) -> consumed.merge(k, v, Long::sum));
		t.drainOccurrences().forEach((k, v) -> occurrences.merge(k, v, Long::sum));
		evidence.putAll(t.drainContextEvidence());
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
		for (int i = 0; i < items.length; i += 2)
		{
			inv.put((Integer) items[i], ((Number) items[i + 1]).longValue());
		}
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		pushBank();
		step();
	}

	/** One altar craft: XP, inputs out, runes in -- the same tick. Inputs: (item, qty) pairs with negative quantities. */
	void craftRaw(Object... deltas)
	{
		t.onSkillXp(tick, 250, now);
		for (int i = 0; i < deltas.length; i += 2)
		{
			inv.merge((Integer) deltas[i], ((Number) deltas[i + 1]).longValue(), Long::sum);
		}
		push();
		steps(4);
	}

	void craft(int essence, long used, Object... runeQty)
	{
		Object[] all = new Object[runeQty.length + 2];
		all[0] = essence;
		all[1] = -used;
		System.arraycopy(runeQty, 0, all, 2, runeQty.length);
		craftRaw(all);
	}

	void bankOpen()
	{
		t.setBankOpen(true);
		step();
	}

	void bankClose()
	{
		t.setBankOpen(false);
		step();
	}

	void withdraw(int item, long qty)
	{
		inv.merge(item, qty, Long::sum);
		bank.merge(item, -qty, Long::sum);
		push();
		pushBank();
		step();
	}

	void deposit(int item)
	{
		long q = inv.getOrDefault(item, 0L);
		inv.put(item, 0L);
		bank.merge(item, q, Long::sum);
		push();
		pushBank();
		steps(3);
	}

	long p(int item)
	{
		return produced.getOrDefault(item, 0L);
	}

	long c(int item)
	{
		return consumed.getOrDefault(item, 0L);
	}

	long trips(String method)
	{
		SkillCycleStats s = t.getCycles().getPending().get(method);
		return s == null ? 0L : s.getCount();
	}

	SkillCompanionView.EventView.Field field(String caption)
	{
		SkillCompanionView.EventView e = t.event(now);
		if (e == null)
		{
			return null;
		}
		for (SkillCompanionView.EventView.Field f : e.getFields())
		{
			if (f.getCaption().equals(caption))
			{
				return f;
			}
		}
		return null;
	}

	boolean hasLine(String text)
	{
		SkillCompanionView.EventView e = t.event(now);
		if (e != null)
		{
			for (SkillCompanionView.ContextLine l : e.getLines())
			{
				if (l.getText().equals(text))
				{
					return true;
				}
			}
		}
		return false;
	}

	// ---- input -> output -------------------------------------------------------------------

	@Test
	public void standardCraft_runesProduced_essenceConsumed_exactly()
	{
		login(PURE, 27);
		craft(PURE, 27, NATURE, 54);
		assertEquals("the actual output (any multiplier) is the authority", 54L, p(NATURE));
		assertEquals(27L, c(PURE));
		assertEquals("nature", t.getMethodKey());
		assertEquals("essence is never production", 0L, p(PURE));
	}

	@Test
	public void multipliersAndRaiments_areSimplyTheActualOutput()
	{
		login(PURE, 81);
		craft(PURE, 27, ItemID.COSMICRUNE, 54);
		craft(PURE, 27, ItemID.COSMICRUNE, 61); // a few extra runes from Raiments of the Eye
		craft(PURE, 27, ItemID.COSMICRUNE, 54);
		assertEquals(169L, p(ItemID.COSMICRUNE));
		assertEquals(81L, c(PURE));
	}

	@Test
	public void everyEssenceType_keepsItsExactIdentity()
	{
		login(ItemID.BLANKRUNE, 20, PURE, 20, ItemID.BLANKRUNE_DAEYALT, 20);
		craft(ItemID.BLANKRUNE, 20, ItemID.AIRRUNE, 20);
		craft(PURE, 20, ItemID.AIRRUNE, 20);
		craft(ItemID.BLANKRUNE_DAEYALT, 20, ItemID.AIRRUNE, 30);
		assertEquals(20L, c(ItemID.BLANKRUNE));
		assertEquals(20L, c(PURE));
		assertEquals("daeyalt stays daeyalt", 20L, c(ItemID.BLANKRUNE_DAEYALT));
		assertEquals(70L, p(ItemID.AIRRUNE));
	}

	@Test
	public void withdrawalsAndPouchMoves_areNeverCraftsOrConsumption()
	{
		login(ItemID.RCU_POUCH_GIANT, 1);
		t.setBankOpen(true);
		bank.put(PURE, 1000L);
		bank.put(NATURE, 500L);
		pushBank();
		step();
		withdraw(PURE, 27);
		withdraw(NATURE, 100);
		t.setBankOpen(false);
		steps(3);
		// Fill: the essence leaves the inventory, the pouch's own varbit rises -- no runes: no craft.
		inv.merge(PURE, -12L, Long::sum);
		push();
		t.setPouchAmount(RunecraftPouch.GIANT, 12);
		steps(4);
		assertTrue(produced.isEmpty());
		assertTrue(consumed.isEmpty());
		assertEquals(27L, t.essenceCarried());
		// Empty: back into the inventory.
		inv.merge(PURE, 12L, Long::sum);
		push();
		t.setPouchAmount(RunecraftPouch.GIANT, 0);
		steps(4);
		assertTrue(consumed.isEmpty());
		assertEquals(27L, t.essenceCarried());
	}

	@Test
	public void runesWithoutXp_neverProduction()
	{
		login();
		inv.put(NATURE, 300L);
		push();
		steps(4);
		assertTrue(produced.isEmpty());
	}

	@Test
	public void sameCraft_neverCountedTwice()
	{
		login(PURE, 27);
		t.onSkillXp(tick, 250, now);
		step();
		t.onSkillXp(tick, 10, now);
		inv.put(PURE, 0L);
		inv.put(NATURE, 54L);
		push();
		steps(6);
		assertEquals(54L, p(NATURE));
		assertEquals(27L, c(PURE));
	}

	@Test
	public void unrelatedLossOnACraftTick_isNeverAnInput()
	{
		login(PURE, 27, ItemID.LOBSTER, 5);
		craftRaw(PURE, -27L, NATURE, 54L, ItemID.LOBSTER, -1L);
		assertEquals(0L, c(ItemID.LOBSTER));
		assertEquals(27L, c(PURE));
	}

	@Test
	public void banking_banksOnlyCraftedRunes()
	{
		login(NATURE, 100, PURE, 27);
		craft(PURE, 27, NATURE, 54);
		t.setBankOpen(true);
		deposit(NATURE);
		assertEquals(54L, (long) banked.getOrDefault(NATURE, 0L));
	}

	// ---- method --------------------------------------------------------------------------------

	@Test
	public void everyStandardAltar_namedByItsRune()
	{
		for (RunecraftMethod m : RunecraftMethod.values())
		{
			if (m.isMixed() || m.isArceuus() || m.isBinding() || m == RunecraftMethod.SUNFIRE)
			{
				continue;
			}
			setUp();
			login(PURE, 10);
			craft(PURE, 10, m.getRune(), 10);
			assertEquals(m.name(), m.getKey(), t.getMethodKey());
			assertEquals(m.name(), 10L, p(m.getRune()));
			assertEquals(m.name(), 10L, c(PURE));
		}
	}

	@Test
	public void oneStrayCraft_neverReplacesTheMethod()
	{
		login(PURE, 200);
		craft(PURE, 27, NATURE, 54);
		craft(PURE, 27, NATURE, 54);
		craft(PURE, 27, ItemID.LAWRUNE, 27);
		assertEquals("nature", t.getMethodKey());
		craft(PURE, 27, ItemID.LAWRUNE, 27);
		assertEquals("a sustained switch is a new method in the same session", "law", t.getMethodKey());
	}

	@Test
	public void ourania_mixedOutput_isOneMethod()
	{
		login(PURE, 28);
		craft(PURE, 28, NATURE, 6, ItemID.LAWRUNE, 4, ItemID.COSMICRUNE, 9, ItemID.CHAOSRUNE, 11);
		assertEquals("ourania", t.getMethodKey());
		assertEquals(30L, p(NATURE) + p(ItemID.LAWRUNE) + p(ItemID.COSMICRUNE) + p(ItemID.CHAOSRUNE));
		inv.put(PURE, 28L);
		push();
		step();
		craft(PURE, 28, NATURE, 28);
		assertEquals("one single-type ZMI craft never replaces the method", "ourania", t.getMethodKey());
	}

	@Test
	public void trueBloodAltar_andArceuusBlood_areDifferentMethods()
	{
		login(PURE, 25);
		craft(PURE, 25, ItemID.BLOODRUNE, 25);
		assertEquals("blood", t.getMethodKey());
		assertEquals("Blood runes — True Altar", RunecraftCompanionDefinition.INSTANCE.methodLabel("blood"));
		setUp();
		login(FRAGMENTS, 104);
		craft(FRAGMENTS, 104, ItemID.BLOODRUNE, 104);
		assertEquals("blood_arceuus", t.getMethodKey());
		assertEquals(104L, c(FRAGMENTS));
		assertEquals("Blood runes — Arceuus", RunecraftCompanionDefinition.INSTANCE.methodLabel("blood_arceuus"));
	}

	@Test
	public void arceuusSoul_fromFragments()
	{
		login(FRAGMENTS, 60);
		craft(FRAGMENTS, 60, ItemID.SOULRUNE, 60);
		assertEquals("soul_arceuus", t.getMethodKey());
	}

	@Test
	public void arceuusLoop_phases_andMiningCraftingAreRunecraftContext()
	{
		login(FRAGMENTS, 40);
		craft(FRAGMENTS, 40, ItemID.BLOODRUNE, 40);
		assertEquals(RunecraftCompanionTracker.Phase.BINDING, t.getPhase());
		// Mining a dense runestone: Mining + Crafting XP, a dense block arrives.
		t.onOtherSkillXp("MINING", tick, 12, now);
		t.onOtherSkillXp("CRAFTING", tick, 8, now);
		inv.put(ItemID.ARCEUUS_ESSENCE_BLOCK, 1L);
		push();
		steps(4);
		assertEquals(RunecraftCompanionTracker.Phase.MINING, t.getPhase());
		assertTrue(evidence.containsKey("MINING") && evidence.containsKey("CRAFTING"));
		// Venerating: dense blocks become dark blocks.
		inv.put(ItemID.ARCEUUS_ESSENCE_BLOCK, 0L);
		inv.put(ItemID.ARCEUUS_ESSENCE_BLOCK_DARK, 1L);
		push();
		steps(4);
		assertEquals(RunecraftCompanionTracker.Phase.VENERATING, t.getPhase());
		// Chiselling: a dark block becomes 4 fragments with Crafting XP.
		t.onOtherSkillXp("CRAFTING", tick, 8, now);
		inv.put(ItemID.ARCEUUS_ESSENCE_BLOCK_DARK, 0L);
		inv.put(FRAGMENTS, 4L);
		push();
		steps(4);
		assertEquals(RunecraftCompanionTracker.Phase.CHISELLING, t.getPhase());
		SkillCompanionView.EventView e = t.event(now);
		assertEquals("Current phase", e.getTitle());
		assertEquals("Chiselling", e.getName());
		assertEquals("4", field("FRAGMENTS").getValue());
		assertNull("no bank trip is claimed for the Arceuus loop", field("ESSENCE"));
		assertTrue("none of this was production or consumption", produced.size() == 1 && consumed.size() == 1);
	}

	@Test
	public void ordinaryMining_isNeverRunecraftContext()
	{
		login(PURE, 10);
		craft(PURE, 10, NATURE, 20);
		t.onOtherSkillXp("MINING", tick, 35, now);
		inv.put(ItemID.IRON_ORE, 1L);
		push();
		steps(4);
		assertFalse(evidence.containsKey("MINING"));
	}

	// ---- transformations with catalysts -------------------------------------------------------

	@Test
	public void combination_catalystIsNotEssence_andBindsAreObserved()
	{
		login(PURE, 26, ItemID.EARTHRUNE, 26, ItemID.FIRE_TALISMAN, 2);
		craftRaw(PURE, -13L, ItemID.EARTHRUNE, -13L, ItemID.FIRE_TALISMAN, -1L, ItemID.LAVARUNE, 7L);
		assertEquals(7L, p(ItemID.LAVARUNE));
		assertEquals(13L, c(PURE));
		assertEquals("the earth runes were a catalyst: an input by name, never essence", 13L, c(ItemID.EARTHRUNE));
		assertEquals(1L, c(ItemID.FIRE_TALISMAN));
		assertEquals("lava", t.getMethodKey());
		assertEquals(Long.valueOf(13L), occurrences.get("bind_attempts:lava"));
		assertEquals(Long.valueOf(7L), occurrences.get("bind_bound:lava"));
		assertEquals("Bound 7 / 13 (54%)", RunecraftCompanionDefinition.INSTANCE.occurrenceLines("lava", occurrences).get(0));
		assertTrue(hasLine("No binding necklace: binds can fail"));
		t.setBindingNecklace(true);
		assertFalse(hasLine("No binding necklace: binds can fail"));
	}

	@Test
	public void aether_isABindingTransformation()
	{
		login(PURE, 20, ItemID.SOULRUNE, 20, ItemID.COSMIC_SOUL_CATALYST, 20);
		t.setBindingNecklace(true);
		craftRaw(PURE, -20L, ItemID.SOULRUNE, -20L, ItemID.COSMIC_SOUL_CATALYST, -20L, ItemID.AETHERRUNE, 20L);
		assertEquals("aether", t.getMethodKey());
		assertEquals(20L, p(ItemID.AETHERRUNE));
		assertEquals(20L, c(ItemID.COSMIC_SOUL_CATALYST));
		assertEquals(20L, c(ItemID.SOULRUNE));
		assertEquals(Long.valueOf(20L), occurrences.get("bind_bound:aether"));
	}

	@Test
	public void sunfire_atTheShrine_withSplintersAndFireRunes()
	{
		login(PURE, 10, ItemID.FIRERUNE, 10, ItemID.SUNFIRESPLINTER, 40);
		craftRaw(PURE, -10L, ItemID.FIRERUNE, -10L, ItemID.SUNFIRESPLINTER, -40L, ItemID.SUNFIRERUNE, 20L);
		assertEquals("sunfire", t.getMethodKey());
		assertEquals(20L, p(ItemID.SUNFIRERUNE));
		assertEquals(40L, c(ItemID.SUNFIRESPLINTER));
		assertTrue("no bind statistics for a craft that cannot fail", occurrences.isEmpty());
	}

	@Test
	public void scarExtracts_areConsumedInputs_andTheBonusIsSimplyTheOutput()
	{
		login(PURE, 27, ItemID.SCAR_EXTRACT_MANGLED, 27);
		craftRaw(PURE, -27L, ItemID.SCAR_EXTRACT_MANGLED, -27L, NATURE, 27L * 61);
		assertEquals("nature", t.getMethodKey());
		assertEquals(27L * 61, p(NATURE));
		assertEquals(27L, c(ItemID.SCAR_EXTRACT_MANGLED));
		assertEquals(27L, c(PURE));
		assertFalse(RunecraftCompanionDefinition.INSTANCE.isPrimaryInput(ItemID.SCAR_EXTRACT_MANGLED));
	}

	// ---- pouches -------------------------------------------------------------------------------

	@Test
	public void pouches_exactNativeContents_andLoadedCount()
	{
		login(PURE, 2, ItemID.RCU_POUCH_SMALL, 1, ItemID.RCU_POUCH_GIANT, 1, ItemID.RCU_POUCH_COLOSSAL, 1);
		t.setPouchAmount(RunecraftPouch.SMALL, 3);
		t.setPouchAmount(RunecraftPouch.GIANT, 12);
		t.setPouchAmount(RunecraftPouch.COLOSSAL, 0);
		// A pouch not carried never counts, whatever its varbit says.
		t.setPouchAmount(RunecraftPouch.LARGE, 9);
		craft(PURE, 2, NATURE, 4);
		inv.put(PURE, 25L);
		push();
		steps(3);
		assertEquals("25 loose + 3 + 12 in pouches", "40", field("ESSENCE").getValue());
		assertEquals("2 loaded", field("POUCHES").getValue());
	}

	@Test
	public void degradedPouch_isAWarningWithItsCurrentCapacity_noCountdown()
	{
		login(PURE, 10, ItemID.RCU_POUCH_LARGE_DEGRADE, 1, ItemID.RCU_POUCH_COLOSSAL, 1);
		t.setRunecraftLevel(91);
		t.setPouchDegradation(RunecraftPouch.LARGE, 900);
		t.setPouchDegradation(RunecraftPouch.COLOSSAL, 0);
		craft(PURE, 10, NATURE, 20);
		assertTrue(hasLine("Large pouch degraded (holds 3 of 9)"));
		for (SkillCompanionView.ContextLine l : t.event(now).getLines())
		{
			assertFalse(l.getText(), l.getText().toLowerCase().contains("uses") || l.getText().toLowerCase().contains("left"));
		}
		assertFalse("an intact colossal pouch is no warning", hasLine("Colossal pouch degraded (holds 40 of 40)"));
	}

	@Test
	public void pouchCapacities_followTheGamesTables()
	{
		assertEquals(3, RunecraftPouch.SMALL.capacity(0, 99));
		assertEquals(6, RunecraftPouch.MEDIUM.capacity(0, 99));
		assertEquals(3, RunecraftPouch.MEDIUM.capacity(500, 99));
		assertEquals(0, RunecraftPouch.MEDIUM.capacity(900, 99));
		assertEquals(9, RunecraftPouch.LARGE.capacity(0, 99));
		assertEquals(12, RunecraftPouch.GIANT.capacity(100, 99));
		assertEquals(8, RunecraftPouch.GIANT.capacity(350, 99));
		assertEquals(40, RunecraftPouch.COLOSSAL.capacity(0, 85));
		assertEquals(27, RunecraftPouch.COLOSSAL.capacity(0, 80));
		assertEquals(16, RunecraftPouch.COLOSSAL.capacity(0, 60));
		assertEquals(8, RunecraftPouch.COLOSSAL.capacity(0, 30));
		assertEquals("degraded + scaled", 20, RunecraftPouch.COLOSSAL.capacity(900, 99));
		assertTrue(RunecraftPouch.GIANT.isDegraded(1000));
		assertFalse(RunecraftPouch.GIANT.isDegraded(0));
		assertEquals(RunecraftPouch.LARGE, RunecraftPouch.forItem(ItemID.RCU_POUCH_LARGE_DEGRADE));
		for (RunecraftPouch p : RunecraftPouch.values())
		{
			assertEquals(p, RunecraftPouch.forAmountVarbit(p.getAmountVarbit()));
		}
	}

	// ---- trips --------------------------------------------------------------------------------

	@Test
	public void trips_bankCraftBank_countOnce_withYields_neverMidRun()
	{
		login(PURE, 27);
		// Started mid-run: the first craft and the next bank only synchronise.
		craft(PURE, 27, NATURE, 54);
		bankOpen();
		assertEquals(0L, trips("nature"));
		assertTrue(occurrences.isEmpty());
		bank.put(PURE, 1000L);
		pushBank();
		withdraw(PURE, 27);
		bankClose();
		assertEquals("Since bank", t.event(now).getName());
		steps(20);
		craft(PURE, 27, NATURE, 54);
		steps(20);
		bankOpen();
		assertEquals(1L, trips("nature"));
		assertTrue(t.getCycles().getPending().get("nature").getTotalMillis() > 0);
		assertEquals(Long.valueOf(54L), occurrences.get("trip_runes:nature"));
		assertEquals(Long.valueOf(27L), occurrences.get("trip_essence:nature"));
		// A bank visit without a craft never counts.
		withdraw(PURE, 27);
		bankClose();
		bankOpen();
		assertEquals(1L, trips("nature"));
		assertEquals(Long.valueOf(54L), occurrences.get("trip_runes:nature"));
	}

	@Test
	public void trip_thatCraftedTwoMethods_neverCounts()
	{
		login(PURE, 27);
		craft(PURE, 27, NATURE, 54);
		bankOpen();
		bank.put(PURE, 1000L);
		pushBank();
		withdraw(PURE, 54);
		bankClose();
		craft(PURE, 27, NATURE, 54);
		craft(PURE, 27, ItemID.LAWRUNE, 27);
		bankOpen();
		assertEquals(0L, trips("nature"));
		assertEquals(0L, trips("law"));
	}

	@Test
	public void abyss_isRouteContext_neverTheMethod()
	{
		login(PURE, 27);
		craft(PURE, 27, NATURE, 54);
		bankOpen();
		bank.put(PURE, 1000L);
		pushBank();
		withdraw(PURE, 27);
		bankClose();
		region = RunecraftCompanionDefinition.ABYSS_REGION;
		steps(10);
		region = REGION;
		craft(PURE, 27, NATURE, 54);
		assertEquals("nature", t.getMethodKey());
		assertTrue(hasLine("Route: Abyss"));
		bankOpen();
		assertFalse("a new trip starts without the route", hasLine("Route: Abyss"));
	}

	// ---- GotR --------------------------------------------------------------------------------

	@Test
	public void gotrRegion_isNeverGenericRunecraft()
	{
		region = RunecraftCompanionDefinition.GOTR_REGION;
		login(ItemID.GOTR_GUARDIAN_ESSENCE, 20, ItemID.RCU_POUCH_COLOSSAL, 1);
		t.setPouchAmount(RunecraftPouch.COLOSSAL, 40);
		step();
		t.onSkillXp(tick, 200, now);
		inv.put(ItemID.GOTR_GUARDIAN_ESSENCE, 0L);
		inv.put(NATURE, 40L);
		push();
		steps(4);
		t.setBankOpen(true);
		step();
		t.setBankOpen(false);
		step();
		assertTrue(produced.isEmpty());
		assertTrue(consumed.isEmpty());
		assertNull(t.getMethodKey());
		assertNull("no generic trip / pouch section inside GotR", t.event(now));
		assertTrue(t.getCycles().getPending().isEmpty());
	}

	@Test
	public void definition_onTheSharedChassis()
	{
		RunecraftCompanionDefinition d = RunecraftCompanionDefinition.INSTANCE;
		assertEquals(d, com.osrstelemetry.plugin.skills.SkillCompanions.forSkillKey("runecraft"));
		assertEquals("Nature rune", d.resource(NATURE).getName());
		assertEquals("Nature", d.resource(NATURE).getShortName());
		assertEquals("Blood rune", d.resource(ItemID.BLOODRUNE).getName());
		assertEquals("NATURE RUNES", d.productionCaption("nature", java.util.Collections.singletonList(d.resource(NATURE))));
		assertEquals("TOTAL RUNES", d.productionCaption("ourania", java.util.Arrays.asList(d.resource(NATURE), d.resource(ItemID.LAWRUNE))));
		assertEquals(1, d.stockpileResources("nature").size());
		assertEquals(ItemID.LAVARUNE, d.stockpileResources("lava").get(0).getItemId());
		assertTrue("ZMI's stockpile choices come from what it crafted", d.stockpileResources("ourania").isEmpty());
		assertEquals("Pure essence", d.inputName(PURE));
		assertEquals("Daeyalt essence", d.inputName(ItemID.BLANKRUNE_DAEYALT));
		assertEquals("Dark essence fragments", d.inputName(FRAGMENTS));
		assertNull("guardian essence is GotR's, never an input here", d.inputName(ItemID.GOTR_GUARDIAN_ESSENCE));
		assertTrue(d.isPrimaryInput(ItemID.BLANKRUNE_DAEYALT));
		assertFalse(d.isPrimaryInput(ItemID.EARTHRUNE));
		assertFalse(d.tripMatters("nature"));
		assertTrue(d.breakdownAsShares("ourania"));
		assertFalse(d.breakdownAsShares("nature"));
		for (RunecraftMethod m : RunecraftMethod.values())
		{
			assertEquals(m, RunecraftMethod.fromKey(m.getKey()));
			if (!m.isMixed())
			{
				assertTrue(m.name(), RunecraftCompanionDefinition.isRune(m.getRune()));
				assertNotNull(m.name(), d.resource(m.getRune()));
			}
		}
	}
}
