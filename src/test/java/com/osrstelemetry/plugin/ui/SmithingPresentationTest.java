package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionPanelSource;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.smithing.SmithingCompanionDefinition;
import com.osrstelemetry.plugin.skills.smithing.SmithingCompanionTracker;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * The Smithing companion as the player sees it, rendered by the shared Current
 * Session / History code at 150 / 180 / 213 / 260 px with real layout passes:
 * RECIPE, CURRENT BATCH (or BLAST FURNACE) built by the real tracker, SESSION
 * in the recipe's own units, PRODUCTION with BANKED (no repeated rate),
 * INPUTS USED, account potential, coal bag, LEVEL / STOCKPILE targets, long
 * names, Smithing before any recipe evidence, History per recipe and an old
 * record. No clipping, no horizontal scroll, no dead sections.
 */
public class SmithingPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-13T21:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	// ---- fixtures ---------------------------------------------------------------------------------

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name.replace("'", "\\u0027") + "','quantity':" + qty + "}";
	}

	private static Session smithing(String state, String produced, String banked, String methodMillis, String consumed, String occurrences,
		String lastMethod, long activeMillis)
	{
		String stats = "'skillStats':{'skillKey':'smithing'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}")
			+ (consumed == null ? "" : ",'consumed':[" + consumed + "]")
			+ (occurrences == null ? "" : ",'occurrences':{" + occurrences + "}")
			+ (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'") + "}";
		return GSON.fromJson(("{'sessionId':'s-sm','activityIdentity':{'activityType':'SKILLING','activityKey':'smithing',"
			+ "'displayName':'Smithing'},'state':'" + state + "','startedAt':'2026-10-13T19:58:00Z','lastActiveAt':'2026-10-13T21:00:00Z',"
			+ "'accumulatedActiveDurationMillis':" + activeMillis + "," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-13T21:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'SMITHING':84300},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static Session active(String produced, String banked, String methodMillis, String consumed, String occurrences, String method)
	{
		return smithing("ACTIVE", produced, banked, methodMillis, consumed, occurrences, method, 3_600_000L);
	}

	static final class Source implements SkillCompanionPanelSource
	{
		SkillCompanionView view = SkillCompanionView.EMPTY;
		SkillGoal goal = SkillGoal.DEFAULT;
		Integer xp = Experience.getXpForLevel(80) + 10_000;
		Map<Integer, Long> bank;

		@Override
		public SkillCompanionDefinition definition()
		{
			return SmithingCompanionDefinition.INSTANCE;
		}

		@Override
		public SkillCompanionView view()
		{
			return view;
		}

		@Override
		public SkillGoal goal()
		{
			return goal;
		}

		@Override
		public void setGoal(SkillGoal g)
		{
			goal = g;
		}

		@Override
		public Integer skillXp()
		{
			return xp;
		}

		@Override
		public Long bankQuantity(int itemId)
		{
			return bank == null ? null : bank.getOrDefault(itemId, 0L);
		}
	}

	/** A live view carrying the real tracker's CURRENT BATCH / BLAST FURNACE section. */
	private static SkillCompanionView view(String method, SkillCompanionView.EventView event, Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return new SkillCompanionView("smithing", method, "s-sm", Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), null, Collections.<SkillCompanionView.ContextLine>emptyList(), banking)
			.withExtras(null, null, null, null, null, event);
	}

	/** The real tracker after two observed actions of {@code deltas} from {@code carried}; optionally a bank seen and a coal bag checked. */
	private static SmithingCompanionTracker tracker(Map<Integer, Long> carried, Map<Integer, Long> bankSeen, String bagCheck, Object... deltas)
	{
		SmithingCompanionTracker t = new SmithingCompanionTracker();
		int tick = 10;
		long now = 1_000_000L;
		Map<Integer, Long> inv = new HashMap<>(carried);
		t.onInventory(tick, now, new HashMap<>(inv), inv.size());
		if (bankSeen != null)
		{
			t.onBank(tick, now, new HashMap<>(bankSeen));
		}
		if (bagCheck != null)
		{
			t.onChat(tick, now, bagCheck);
		}
		for (int i = 0; i < 2; i++)
		{
			for (int d = 0; d < deltas.length; d += 2)
			{
				inv.merge((Integer) deltas[d], ((Number) deltas[d + 1]).longValue(), Long::sum);
			}
			inv.values().removeIf(v -> v <= 0);
			t.onInventory(tick, now, new HashMap<>(inv), inv.size());
			t.onSkillXp(tick, 100, now);
			for (int k = 0; k < 4; k++)
			{
				tick++;
				now += 600;
				t.onTick(tick, now, 12_850);
			}
		}
		return t;
	}

	private static Map<Integer, Long> carried(Object... pairs)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((Integer) pairs[i], ((Number) pairs[i + 1]).longValue());
		}
		return m;
	}

	private static CurrentSessionView render(Session session, Source s)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setSkillCompanionSource(s);
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(session), NOW));
		return v;
	}

	private static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	private static Component named(Container root, String name)
	{
		for (Component c : all(root))
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
		}
		return null;
	}

	private static String cell(Container root, String grid, String caption)
	{
		Component g = named(root, grid);
		Component c = g == null ? null : named((Container) g, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		if (c == null)
		{
			return null;
		}
		if (c instanceof JLabel)
		{
			return ((JLabel) c).getText() == null ? null : ((JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
		}
		List<String> parts = new ArrayList<>();
		for (Component k : all((Container) c))
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null && !((JLabel) k).getText().trim().isEmpty())
			{
				parts.add(((JLabel) k).getText().replaceAll("<[^>]*>", "").trim());
			}
		}
		return parts.isEmpty() ? null : parts.get(parts.size() - 1);
	}

	private static String lineStarting(Container root, String prefix)
	{
		for (Component c : all(root))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				String t = ((JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
				if (t.startsWith(prefix))
				{
					return t;
				}
			}
		}
		return null;
	}

	private static void fitsEveryWidth(Container v, String what)
	{
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(v, width);
			assertTrue(what + " @" + width + ": " + problems, problems.isEmpty());
		}
	}

	private static final String STEEL_PRODUCED = item(ItemID.STEEL_BAR, "Steel bar", 412);
	private static final String STEEL_OCC = "'made:smelt_steel':412,'in:smelt_steel:" + ItemID.IRON_ORE + "':412,'in:smelt_steel:"
		+ ItemID.COAL + "':824";

	// ---- Current Session ---------------------------------------------------------------------------

	@Test
	public void smelting_batchFromTheRealTracker_sessionProductionInputs_noRepeatedRate_levelTarget()
	{
		Source s = new Source();
		SmithingCompanionTracker t = tracker(carried(ItemID.IRON_ORE, 85, ItemID.COAL, 146), null, null,
			ItemID.IRON_ORE, -1, ItemID.COAL, -2, ItemID.STEEL_BAR, 1);
		s.view = view("smelt_steel", t.event(1_000_000L), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap())
			.withStock(t.stock(1_000_000L));
		s.goal = SkillGoal.DEFAULT.withTargetLevel(90);
		CurrentSessionView v = render(active(STEEL_PRODUCED, item(ItemID.STEEL_BAR, "Steel bar", 392), "'smelt_steel':3600000",
			item(ItemID.IRON_ORE, "Iron ore", 412) + "," + item(ItemID.COAL, "Coal", 824), STEEL_OCC, "smelt_steel"), s);
		assertEquals("Steel bars", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull("no generic CURRENT BATCH for normal smelting", named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNull("the 412 is never shown twice: no SESSION total", named(v, CurrentSessionView.SC_ACTIONS_NAME));
		assertEquals("the output sprite + amount", ";" + ItemID.STEEL_BAR + ":412", SkillItems.production(v));
		assertNull("no caption cell, no live BANKED", named(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME));
		assertEquals("the recipe's own rate", "412 bars/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertNotNull("titled USED", lineStarting(v, "USED"));
		assertEquals(";" + ItemID.IRON_ORE + ":412;" + ItemID.COAL + ":824", SkillItems.grid(v, CurrentSessionView.SC_INPUTS_ITEMS_NAME));
		assertEquals("Open your bank to count your supplies", text(v, CurrentSessionView.SC_STOCK_LINE_PREFIX + 0));
		assertNull(lineStarting(v, "Account potential"));
		assertEquals(String.format("%,d", Experience.getXpForLevel(90) - s.xp),
			cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
		fitsEveryWidth(v, "smelting");
	}

	@Test
	public void anvil_multiBar_canMake_accountPotential_stockpileFromTheBank()
	{
		Source s = new Source();
		SmithingCompanionTracker t = tracker(carried(ItemID.MITHRIL_BAR, 83, ItemID.HAMMER, 1), carried(ItemID.MITHRIL_BAR, 1_357), null,
			ItemID.MITHRIL_BAR, -5, ItemID.MITHRIL_PLATEBODY, 1);
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new HashMap<>();
		banking.put(ItemID.MITHRIL_PLATEBODY, new SkillGoalPlanner.BankingSample(56, 2, 2_400_000L));
		s.view = view("mithril_platebody", t.event(1_000_000L), banking).withStock(t.stock(1_000_000L));
		s.bank = carried(ItemID.MITHRIL_PLATEBODY, 60);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID.MITHRIL_PLATEBODY).withTargetQuantity(200);
		CurrentSessionView v = render(active(item(ItemID.MITHRIL_PLATEBODY, "Mithril platebody", 28), null, "'mithril_platebody':480000",
			item(ItemID.MITHRIL_BAR, "Mithril bar", 140), "'made:mithril_platebody':28,'in:mithril_platebody:" + ItemID.MITHRIL_BAR + "':140",
			"mithril_platebody"), s);
		assertEquals("Mithril platebody", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertEquals("1,357 banked bars / 5 per platebody (the carried bars are not added)", "Can make from bank: 271 items",
			text(v, CurrentSessionView.SC_STOCK_LINE_PREFIX + 0));
		assertEquals(";" + ItemID.MITHRIL_BAR + ":1,357", SkillItems.grid(v, CurrentSessionView.SC_STOCK_ITEMS_PREFIX + 0));
		assertNull(lineStarting(v, "Account potential"));
		assertNull(named(v, CurrentSessionView.SC_ACTIONS_NAME));
		assertEquals("28 in 8 minutes: shown after 2 minutes of smithing", "210 items/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertEquals(";" + ItemID.MITHRIL_PLATEBODY + ":28", SkillItems.production(v));
		assertEquals(";" + ItemID.MITHRIL_BAR + ":140", SkillItems.grid(v, CurrentSessionView.SC_INPUTS_ITEMS_NAME));
		assertEquals("60", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("140", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertNotNull("ETA from BANKED/hr (56 in 40 min)", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		assertTrue(text(v, CurrentSessionView.SC_ETA_LINE_NAME).startsWith("ETA ~"));
		fitsEveryWidth(v, "anvil");
	}

	@Test
	public void rateWarmUp_twoMinutesForSteadySmithing_theBlastFurnaceKeepsFive()
	{
		Source s = new Source();
		s.view = view("smelt_steel", null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView early = render(active(item(ItemID.STEEL_BAR, "Steel bar", 30), null, "'smelt_steel':100000", null,
			"'made:smelt_steel':30", "smelt_steel"), s);
		assertNull("1m 40s: no rate yet", named(early, CurrentSessionView.SC_RATE_NAME));
		CurrentSessionView warm = render(active(item(ItemID.STEEL_BAR, "Steel bar", 45), null, "'smelt_steel':150000", null,
			"'made:smelt_steel':45", "smelt_steel"), s);
		assertEquals("2m 30s: the rate appears", "1,080 bars/hr", text(warm, CurrentSessionView.SC_RATE_NAME));
		Source bf = new Source();
		bf.view = view("bf_steel", null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView furnace = render(active(item(ItemID.STEEL_BAR, "Steel bar", 54), null, "'bf_steel':180000", null,
			"'made:bf_steel':54", "bf_steel"), bf);
		assertNull("Blast Furnace collections swing: still the shared 5 minutes", named(furnace, CurrentSessionView.SC_RATE_NAME));
	}

	@Test
	public void stockpile_bankUnknown_isNeverZero_noEta()
	{
		Source s = new Source();
		s.view = view("smelt_gold", null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID.GOLD_BAR).withTargetQuantity(1_000);
		CurrentSessionView v = render(active(item(ItemID.GOLD_BAR, "Gold bar", 90), null, "'smelt_gold':900000", null,
			"'made:smelt_gold':90", "smelt_gold"), s);
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("ETA -", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		fitsEveryWidth(v, "bank unknown");
	}

	@Test
	public void stockpile_reached_andNeverAProductionRateEta()
	{
		Source s = new Source();
		s.view = view("smelt_gold", null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		s.bank = carried(ItemID.GOLD_BAR, 400);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID.GOLD_BAR).withTargetQuantity(1_000);
		CurrentSessionView v = render(active(item(ItemID.GOLD_BAR, "Gold bar", 1_200), null, "'smelt_gold':3600000", null,
			"'made:smelt_gold':1200", "smelt_gold"), s);
		assertEquals("1,200 produced/hr, but nothing banked yet: no ETA", "ETA -", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		s.bank = carried(ItemID.GOLD_BAR, 1_000);
		v = render(active(item(ItemID.GOLD_BAR, "Gold bar", 1_200), null, "'smelt_gold':3600000", null, "'made:smelt_gold':1200",
			"smelt_gold"), s);
		assertEquals("Done", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		fitsEveryWidth(v, "reached");
	}

	@Test
	public void multiOutput_dartTips_andCannonballs_countTheirOwnUnits()
	{
		Source s = new Source();
		SmithingCompanionTracker t = tracker(carried(ItemID.RUNITE_BAR, 40, ItemID.HAMMER, 1), null, null,
			ItemID.RUNITE_BAR, -1, ItemID.RUNE_DART_TIP, 10);
		s.view = view("rune_dart_tips", t.event(1_000_000L), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(active(item(ItemID.RUNE_DART_TIP, "Rune dart tip", 1_380), null, "'rune_dart_tips':1800000",
			item(ItemID.RUNITE_BAR, "Runite bar", 138), "'made:rune_dart_tips':1380,'in:rune_dart_tips:" + ItemID.RUNITE_BAR + "':138",
			"rune_dart_tips"), s);
		assertEquals(";" + ItemID.RUNE_DART_TIP + ":1,380", SkillItems.production(v));
		assertEquals("2,760 dart tips/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		fitsEveryWidth(v, "dart tips");

		Source c = new Source();
		c.view = view("cannonball_steel", null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView cv = render(active(item(ItemID.MCANNONBALL, "Steel cannonball", 2_144), null, "'cannonball_steel':3600000",
			item(ItemID.STEEL_BAR, "Steel bar", 536), "'made:cannonball_steel':2144,'in:cannonball_steel:" + ItemID.STEEL_BAR + "':536",
			"cannonball_steel"), c);
		assertEquals("Steel cannonballs", text(cv, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(cv, CurrentSessionView.SC_EVENT_NAME));
		assertEquals(";" + ItemID.MCANNONBALL + ":2,144", SkillItems.production(cv));
		assertEquals("2,144 cannonballs/hr", text(cv, CurrentSessionView.SC_RATE_NAME));
		assertEquals(";" + ItemID.STEEL_BAR + ":536", SkillItems.grid(cv, CurrentSessionView.SC_INPUTS_ITEMS_NAME));
		fitsEveryWidth(cv, "cannonballs");
	}

	@Test
	public void blastFurnace_view_andCoalBag_andHotBars()
	{
		SmithingCompanionTracker t = tracker(carried(ItemID.IRON_ORE, 27, ItemID.COAL_BAG, 1), null,
			"The coal bag contains 27 pieces of coal.");
		for (Object[] v : new Object[][]{{VarbitID.BLAST_FURNACE_STEEL_BARS, 0}, {VarbitID.BLAST_FURNACE_IRON_ORE, 0},
			{VarbitID.BLAST_FURNACE_COAL, 0}})
		{
			t.onFurnaceVar(20, 2_000_000L, (Integer) v[0], (Integer) v[1]);
		}
		t.onFurnaceVar(21, 2_000_600L, VarbitID.BLAST_FURNACE_STEEL_BARS, 27);
		Map<Integer, Long> inv = carried(ItemID.IRON_ORE, 27, ItemID.COAL_BAG, 1, ItemID.STEEL_BAR, 27);
		t.onInventory(22, 2_001_200L, inv, inv.size());
		t.onFurnaceVar(22, 2_001_200L, VarbitID.BLAST_FURNACE_STEEL_BARS, 0);
		for (int k = 23; k < 28; k++)
		{
			t.onTick(k, 2_000_000L + k * 600L, 7757);
		}
		t.onFurnaceVar(28, 2_005_000L, VarbitID.BLAST_FURNACE_IRON_ORE, 27);
		t.onFurnaceVar(28, 2_005_000L, VarbitID.BLAST_FURNACE_COAL, 54);
		t.onFurnaceVar(28, 2_005_000L, VarbitID.BLAST_FURNACE_STEEL_BARS, 27);
		t.onFurnaceVar(28, 2_005_000L, VarbitID.BLAST_FURNACE_BARS_HOT, 1);
		Source s = new Source();
		s.view = view("bf_steel", t.event(2_005_000L), Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(active(item(ItemID.STEEL_BAR, "Steel bar", 27), null, "'bf_steel':600000", null,
			"'made:bf_steel':27", "bf_steel"), s);
		assertEquals("Steel bars (BF)", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals(";" + ItemID.COAL + ":54 loaded;" + ItemID.IRON_ORE + ":27 loaded;" + ItemID.STEEL_BAR + ":27 ready",
			SkillItems.grid(v, CurrentSessionView.SC_EVENT_ITEMS_PREFIX + 0));
		assertNull("no dead IN FURNACE / BARS READY cells", named(v, CurrentSessionView.SC_EVENT_GRID_NAME));
		assertEquals("Trips before coal: 2", lineStarting(v, "Trips before coal"));
		assertNull("never a theoretical XP rate", lineStarting(v, "XP/hr"));
		assertNotNull(lineStarting(v, "Bars are hot"));
		assertEquals("Coal bag: 27 coal", lineStarting(v, "Coal bag"));
		fitsEveryWidth(v, "blast furnace");
	}

	@Test
	public void longRecipeNames_andIronFailures_fit()
	{
		for (String[] r : new String[][]{{"superheat_adamantite", "Adamantite bars (Superheat)"},
			{"large_keel_adamant", "Large adamant keel parts"}, {"incendiary_mithril", "Mithril incendiary shot"},
			{"adamant_javelin_heads", "Adamant javelin heads"}})
		{
			Source s = new Source();
			s.view = view(r[0], null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
			int out = com.osrstelemetry.plugin.skills.smithing.SmithingRecipes.recipe(r[0]).getOutputItem();
			CurrentSessionView v = render(active(item(out, "Out", 40), null, "'" + r[0] + "':900000", null, "'made:" + r[0] + "':40", r[0]),
				s);
			assertEquals(r[1], text(v, CurrentSessionView.SC_METHOD_NAME));
			fitsEveryWidth(v, r[0]);
		}
		Source s = new Source();
		s.view = view("smelt_iron", null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(active(item(ItemID.IRON_BAR, "Iron bar", 51), null, "'smelt_iron':900000",
			item(ItemID.IRON_ORE, "Iron ore", 100), "'made:smelt_iron':51,'fail:smelt_iron':49,'in:smelt_iron:" + ItemID.IRON_ORE + "':100",
			"smelt_iron"), s);
		assertEquals("49 iron ore failed to smelt", text(v, CurrentSessionView.SC_INPUTS_LINE_PREFIX + 0));
		assertEquals(";" + ItemID.IRON_ORE + ":100", SkillItems.grid(v, CurrentSessionView.SC_INPUTS_ITEMS_NAME));
		fitsEveryWidth(v, "iron failures");
	}

	@Test
	public void beforeAnyRecipeEvidence_onlyTheTarget_noDeadSections()
	{
		Source s = new Source();
		CurrentSessionView v = render(smithing("ACTIVE", null, null, null, null, null, null, 120_000L), s);
		assertNotNull(named(v, CurrentSessionView.SC_BLOCK_NAME));
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.SC_PRODUCTION_NAME));
		assertNull(named(v, CurrentSessionView.SC_ACTIONS_NAME));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		fitsEveryWidth(v, "generic");
	}

	// ---- History ----------------------------------------------------------------------------------

	private static FactLine statsLine(HistoryView list)
	{
		for (Component c : all(list))
		{
			if (HistoryView.ROW_STATS_NAME.equals(c.getName()))
			{
				return (FactLine) c;
			}
		}
		return null;
	}

	@Test
	public void history_perRecipe_neverBlended_naturalVerbs()
	{
		Session done = smithing("FINALIZED", STEEL_PRODUCED + "," + item(ItemID.MITHRIL_PLATEBODY, "Mithril platebody", 28),
			item(ItemID.STEEL_BAR, "Steel bar", 392), "'smelt_steel':3600000,'mithril_platebody':600000",
			item(ItemID.IRON_ORE, "Iron ore", 412) + "," + item(ItemID.COAL, "Coal", 824) + "," + item(ItemID.MITHRIL_BAR, "Mithril bar", 140),
			STEEL_OCC + ",'made:mithril_platebody':28,'in:mithril_platebody:" + ItemID.MITHRIL_BAR + "':140", "mithril_platebody", 4_200_000L);
		HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
		list.render(Collections.singletonList(HistoryEntry.from(done)));
		assertEquals("Steel bars" + FactLine.SEPARATOR + "412 smelted" + FactLine.SEPARATOR + "+1 recipe" + FactLine.SEPARATOR + "84,300 XP",
			statsLine(list).text());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		// Pass 28: a resource's breakdown rows sit behind "Details" until asked for.
		assertNull(named(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 1));
		((DisclosureToggle) named(d, HistoryDetailView.SKILL_DETAILS_TOGGLE_NAME)).toggle();
		assertEquals("Steel bars", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 0));
		assertEquals("Banked", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 1));
		assertEquals("Iron ore", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 2));
		assertEquals("Coal", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 3));
		assertEquals("Mithril platebody", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 4));
		assertEquals("Mithril bar", text(d, HistoryDetailView.SKILL_OWN_ROW_PREFIX + 5));
		assertNull("never the generic blended row", named(d, HistoryDetailView.SKILL_PRODUCED_NAME));
		for (int width : WIDTHS)
		{
			assertTrue(width + "", SidebarClippingRegressionTest.audit(d, width).isEmpty());
			assertTrue("list " + width, SidebarClippingRegressionTest.audit(list, width).isEmpty());
		}
	}

	@Test
	public void history_oldRecord_withoutSmithingOccurrences_rendersSafely()
	{
		Session old = smithing("FINALIZED", STEEL_PRODUCED, null, null, null, null, null, 1_200_000L);
		HistoryEntry entry = HistoryEntry.from(old);
		assertNotNull(entry);
		HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
		list.render(Collections.singletonList(entry));
		assertEquals("412 made" + FactLine.SEPARATOR + "84,300 XP", statsLine(list).text());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(old));
		for (int width : WIDTHS)
		{
			assertTrue(width + "", SidebarClippingRegressionTest.audit(d, width).isEmpty());
		}
	}
}
