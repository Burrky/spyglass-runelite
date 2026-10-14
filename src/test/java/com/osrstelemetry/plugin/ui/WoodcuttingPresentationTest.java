package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.osrstelemetry.plugin.skills.woodcutting.WoodcuttingCompanionDefinition;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The Woodcutting companion as the player sees it -- TREE, CURRENT TREE,
 * FORESTRY EVENT (only while one runs), CURRENT TRIP, PRODUCTION (with the
 * auto-burned part), SECONDARY (nests, bark, leaves, Forestry events),
 * TARGET -- rendered by the SAME Current Session / History code as Mining,
 * Fishing and Agility, at 150 / 180 / 213 / 260 px with real layout passes.
 */
public class WoodcuttingPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-13T17:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};
	private static final int REDWOOD = ItemID.REDWOOD_LOGS;
	private static final int YEW = ItemID.YEW_LOGS;
	private static final int BARK = ItemID.FORESTRY_CURRENCY;
	private static final int LOGS_ID = ItemID.LOGS;

	// ---- fixtures ---------------------------------------------------------------------------------

	private static Session woodcutting(String state, String produced, String banked, String methodMillis, String burned, String occurrences,
		String lastMethod, String xp)
	{
		return woodcutting(state, produced, banked, methodMillis, burned, occurrences, lastMethod, xp, 3_600_000L);
	}

	private static Session woodcutting(String state, String produced, String banked, String methodMillis, String burned, String occurrences,
		String lastMethod, String xp, long activeMillis)
	{
		String stats = "'skillStats':{'skillKey':'woodcutting'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}")
			+ (burned == null ? "" : ",'autoTransformed':[" + burned + "]")
			+ (occurrences == null ? "" : ",'occurrences':{" + occurrences + "}")
			+ (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'") + "}";
		return GSON.fromJson(("{'sessionId':'s-wc','activityIdentity':{'activityType':'SKILLING','activityKey':'woodcutting',"
			+ "'displayName':'Woodcutting'},'state':'" + state + "','startedAt':'2026-10-13T15:58:00Z','lastActiveAt':'2026-10-13T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':" + activeMillis + "," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-13T17:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{" + xp + "},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name + "','quantity':" + qty + "}";
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	static final class Source implements SkillCompanionPanelSource
	{
		SkillCompanionView view = SkillCompanionView.EMPTY;
		SkillGoal goal = SkillGoal.DEFAULT;
		Integer xp = Experience.getXpForLevel(92) + 50_000;
		Map<Integer, Long> bank;

		@Override
		public SkillCompanionDefinition definition()
		{
			return WoodcuttingCompanionDefinition.INSTANCE;
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

	private static SkillCompanionView view(String tree, SkillCompanionView.TripView trip, SkillCompanionView.FocusView focus,
		SkillCompanionView.EventView event, Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return new SkillCompanionView("woodcutting", tree, "s-wc", Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), trip, Collections.<SkillCompanionView.ContextLine>emptyList(), banking)
			.withExtras(null, null, null, null, focus, event);
	}

	private static CurrentSessionView render(Session session, Source s)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setSkillCompanionSource(s);
		v.render(snap(session));
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
		return c == null ? null : ((JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
	}

	private static void fitsEveryWidth(Container v, String what)
	{
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(v, width);
			assertTrue(what + " @" + width + ": " + problems, problems.isEmpty());
		}
	}

	private static final String REDWOOD_PRODUCED = item(REDWOOD, "Redwood logs", 4824) + "," + item(ItemID.BIRD_NEST_SEEDS, "Bird nest", 9)
		+ "," + item(ItemID.WC_CLUE_NEST_ELITE, "Clue nest (elite)", 1) + "," + item(BARK, "Anima-infused bark", 1240)
		+ "," + item(ItemID.LEAVES_MAGIC, "Magic leaves", 20) + "," + item(ItemID.LEAVES_YEW, "Yew leaves", 15);

	// ---- Current Session --------------------------------------------------------------------------------

	@Test
	public void infernalRedwood_productionWithAutoBurned_currentTree_trip()
	{
		Source s = new Source();
		s.view = view("redwood", new SkillCompanionView.TripView(17, 95_000L, null, null, null, 0, false),
			new SkillCompanionView.FocusView("Redwood tree", 34, 72_000L), null,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		s.goal = SkillGoal.DEFAULT.withTargetLevel(99);
		CurrentSessionView v = render(woodcutting("ACTIVE", REDWOOD_PRODUCED, item(REDWOOD, "Redwood logs", 4300), "'redwood':3600000",
			item(REDWOOD, "Redwood logs", 265), "'forestry:rising_roots':3,'forestry:poachers':1", "redwood",
			"'WOODCUTTING':86300,'FIREMAKING':9500"), s);
		assertEquals("Redwood", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("REDWOOD LOGS cell counts every log, burned ones included", "4,824",
			cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "REDWOOD LOGS"));
		assertEquals("4,300", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, CurrentSessionView.SC_BANKED_CELL));
		assertEquals("265 auto-burned", text(v, CurrentSessionView.SC_AUTO_TRANSFORMED_NAME));
		assertEquals("4,824 logs/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertNull("nests / bark / leaves are never by-product lines under the logs", named(v, CurrentSessionView.SC_BYPRODUCT_PREFIX + 0));
		assertEquals("Redwood tree", text(v, CurrentSessionView.SC_FOCUS_LABEL_NAME));
		assertEquals("34", cell(v, CurrentSessionView.SC_FOCUS_GRID_NAME, "LOGS"));
		assertEquals("1:12", cell(v, CurrentSessionView.SC_FOCUS_GRID_NAME, "TREE TIME"));
		assertEquals("17", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_SPACE_LEFT_CELL));
		assertEquals("~1:35", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		assertNull("no event running: no FORESTRY EVENT section", named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		fitsEveryWidth(v, "redwood");
	}

	@Test
	public void secondary_nestsBarkLeavesAndEvents_ownSection_withRates()
	{
		Source s = new Source();
		s.view = view("redwood", null, null, null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", REDWOOD_PRODUCED, null, "'redwood':3600000", null,
			"'forestry:rising_roots':3,'forestry:poachers':1", "redwood", "'WOODCUTTING':86300"), s);
		assertEquals("10", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "NESTS"));
		assertEquals("10.0", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "NESTS / HR"));
		assertEquals("1,240", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "BARK EARNED"));
		assertEquals("1,240", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "BARK / HR"));
		assertEquals("Leaves: Magic 20 • Yew 15", text(v, CurrentSessionView.SC_SECONDARY_LINE_PREFIX + 0));
		assertEquals("Forestry events: 4", text(v, CurrentSessionView.SC_SECONDARY_LINE_PREFIX + 1));
		assertEquals("primary only", "4,824", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "REDWOOD LOGS"));
		assertNull("nothing burned: no auto-burned line", named(v, CurrentSessionView.SC_AUTO_TRANSFORMED_NAME));
		fitsEveryWidth(v, "secondary");
	}

	@Test
	public void secondary_rateWaitsForEnoughTime()
	{
		Source s = new Source();
		s.view = view("yew", null, null, null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", item(YEW, "Yew logs", 40) + "," + item(ItemID.BIRD_NEST_SEEDS, "Bird nest", 1),
			null, "'yew':300000", null, null, "yew", "'WOODCUTTING':7000"), s);
		assertEquals("1", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "NESTS"));
		assertEquals("-", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "NESTS / HR"));
		assertNull("no bark yet: no bark cells", cell(v, CurrentSessionView.SC_SECONDARY_GRID_NAME, "BARK EARNED"));
	}

	@Test
	public void forestryEvent_onlyWhileRunning_withItsOwnFields()
	{
		Source s = new Source();
		SkillCompanionView.EventView poachers = new SkillCompanionView.EventView("poachers", "Poachers", 72_000L, Arrays.asList(
			new SkillCompanionView.EventView.Field("BARK", "18", null), new SkillCompanionView.EventView.Field("TRAPS", "6", null)),
			Collections.singletonList(new SkillCompanionView.ContextLine("Not eligible for full rewards",
				SkillCompanionView.ContextLine.Tone.ALERT, null)));
		s.view = view("yew", null, null, poachers, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", item(YEW, "Yew logs", 120), null, "'yew':1200000", null, null, "yew",
			"'WOODCUTTING':21000,'HUNTER':900"), s);
		assertEquals("Poachers  1:12", text(v, CurrentSessionView.SC_EVENT_TITLE_NAME));
		assertEquals("18", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "BARK"));
		assertEquals("6", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "TRAPS"));
		assertEquals("Not eligible for full rewards", text(v, CurrentSessionView.SC_EVENT_LINE_PREFIX + 0));
		fitsEveryWidth(v, "poachers");
	}

	@Test
	public void sapling_longMulchLine_fitsEveryWidth()
	{
		Source s = new Source();
		SkillCompanionView.EventView sapling = new SkillCompanionView.EventView("struggling_sapling", "Struggling Sapling", 41_000L,
			Collections.singletonList(new SkillCompanionView.EventView.Field("BARK", "0", null)), Arrays.asList(
				new SkillCompanionView.ContextLine("Mulch: 1 Splintered bark • 2 Rotting leaves • 3 Wild mushrooms",
					SkillCompanionView.ContextLine.Tone.NORMAL, null),
				new SkillCompanionView.ContextLine("Sapling withering", SkillCompanionView.ContextLine.Tone.ALERT, null)));
		s.view = view("magic", null, null, sapling, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", item(ItemID.MAGIC_LOGS, "Magic logs", 33), null, "'magic':900000", null, null,
			"magic", "'WOODCUTTING':8000"), s);
		assertNotNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		fitsEveryWidth(v, "sapling");
	}

	@Test
	public void stockpileTarget_logsOrBark_usesTheBankedRate()
	{
		Source s = new Source();
		s.bank = new HashMap<>();
		s.bank.put(REDWOOD, 12_000L);
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new LinkedHashMap<>();
		banking.put(REDWOOD, new SkillGoalPlanner.BankingSample(4_300, 9, 3_000_000L));
		s.view = view("redwood", null, null, null, banking);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(REDWOOD).withTargetQuantity(20_000);
		CurrentSessionView v = render(woodcutting("ACTIVE", REDWOOD_PRODUCED, item(REDWOOD, "Redwood logs", 4300), "'redwood':3600000",
			item(REDWOOD, "Redwood logs", 265), null, "redwood", "'WOODCUTTING':86300"), s);
		assertEquals("12,000", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("8,000", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertTrue(text(v, CurrentSessionView.SC_ETA_LINE_NAME).startsWith("ETA "));
		assertFalse(text(v, CurrentSessionView.SC_ETA_LINE_NAME).contains("-"));
		@SuppressWarnings("unchecked")
		JComboBox<Object> combo = (JComboBox<Object>) named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME);
		assertEquals("redwood logs and anima-infused bark", 2, combo.getItemCount());
		fitsEveryWidth(v, "stockpile");
	}

	@Test
	public void basketTrip_storageLine()
	{
		Source s = new Source();
		s.view = view("yew", new SkillCompanionView.TripView(20, null, "Log basket", 12, 28, 12, true), null, null,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", item(YEW, "Yew logs", 60), null, "'yew':900000", null, null, "yew",
			"'WOODCUTTING':10500"), s);
		assertEquals("Log basket 12 / 28", text(v, CurrentSessionView.SC_STORAGE_NAME));
		fitsEveryWidth(v, "basket");
	}

	private static SkillCompanionView plain(String tree)
	{
		return view(tree, null, null, null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
	}

	@Test
	public void yewNormal_noAutoBurnLine_noEvent()
	{
		Source s = new Source();
		s.view = view("yew", new SkillCompanionView.TripView(9, 140_000L, null, null, null, 0, false),
			new SkillCompanionView.FocusView("Yew tree", 6, 30_000L), null, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", item(YEW, "Yew logs", 520), item(YEW, "Yew logs", 476), "'yew':3600000", null, null,
			"yew", "'WOODCUTTING':91000"), s);
		assertEquals("Yew", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("520", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "YEW LOGS"));
		assertEquals("520 logs/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertNull(named(v, CurrentSessionView.SC_AUTO_TRANSFORMED_NAME));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNull("no secondary produced: no section", named(v, CurrentSessionView.SC_SECONDARY_NAME));
		fitsEveryWidth(v, "yew");
	}

	@Test
	public void infernalYew_autoBurnedLine()
	{
		Source s = new Source();
		s.view = plain("yew");
		CurrentSessionView v = render(woodcutting("ACTIVE", item(YEW, "Yew logs", 1000), item(YEW, "Yew logs", 735), "'yew':3600000",
			item(YEW, "Yew logs", 265), null, "yew", "'WOODCUTTING':175000,'FIREMAKING':23000"), s);
		assertEquals("1,000", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "YEW LOGS"));
		assertEquals("735", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, CurrentSessionView.SC_BANKED_CELL));
		assertEquals("265 auto-burned", text(v, CurrentSessionView.SC_AUTO_TRANSFORMED_NAME));
		fitsEveryWidth(v, "infernal yew");
	}

	@Test
	public void levelTarget_above_equal_below()
	{
		Source s = new Source();
		s.view = plain("redwood");
		Session session = woodcutting("ACTIVE", item(REDWOOD, "Redwood logs", 900), null, "'redwood':3600000", null, null, "redwood",
			"'WOODCUTTING':86300");
		s.goal = SkillGoal.DEFAULT.withTargetLevel(99);
		CurrentSessionView v = render(session, s);
		assertEquals(String.format("%,d", Experience.getXpForLevel(99) - s.xp),
			cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
		assertTrue(cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL).startsWith("~"));
		fitsEveryWidth(v, "level 99");
		s.goal = SkillGoal.DEFAULT.withTargetLevel(92);
		assertEquals("Reached", cell(render(session, s), CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL));
		s.goal = SkillGoal.DEFAULT.withTargetLevel(80);
		assertEquals("Reached", cell(render(session, s), CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL));
	}

	@Test
	public void levelTarget_noEtaBeforeConfidence()
	{
		Source s = new Source();
		s.view = plain("yew");
		s.goal = SkillGoal.DEFAULT.withTargetLevel(99);
		CurrentSessionView v = render(woodcutting("ACTIVE", item(YEW, "Yew logs", 9), null, "'yew':60000", null, null, "yew",
			"'WOODCUTTING':1575", 60_000L), s);
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL));
		assertNull("no rate before enough time", named(v, CurrentSessionView.SC_RATE_NAME));
	}

	@Test
	public void sulliuscep_mushrooms_fossilLine_noCurrentTree()
	{
		Source s = new Source();
		s.view = plain("sulliuscep");
		CurrentSessionView v = render(woodcutting("ACTIVE", item(ItemID.BITTERCAP_MUSHROOM, "Mushroom", 210) + ","
			+ item(ItemID.MORTMYREMUSHROOM, "Mort myre fungus", 19) + "," + item(ItemID.FOSSIL_NUMULITE, "Numulite", 64) + ","
			+ item(ItemID.FOSSIL_SMALL_UNID, "Unidentified small fossil", 2), null, "'sulliuscep':2760000", null, null, "sulliuscep",
			"'WOODCUTTING':29000"), s);
		assertEquals("Sulliuscep", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("229", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "MUSHROOMS"));
		assertEquals("Mushrooms 210 \u2022 Fungus 19", text(v, CurrentSessionView.SC_BREAKDOWN_NAME));
		assertEquals("Fossils: Numulite 64 \u2022 Small 2", text(v, CurrentSessionView.SC_BYPRODUCT_PREFIX + 0));
		assertNull(named(v, CurrentSessionView.SC_FOCUS_NAME));
		fitsEveryWidth(v, "sulliuscep");
	}

	@Test
	public void unknownTree_noMethodRow_noInventedRate()
	{
		Source s = new Source();
		s.view = plain(null);
		CurrentSessionView v = render(woodcutting("ACTIVE", item(LOGS_ID, "Logs", 12), null, null, null, null, null, "'WOODCUTTING':300"), s);
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("12", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "LOGS CUT"));
		assertNull(named(v, CurrentSessionView.SC_RATE_NAME));
		assertNull("no tree: no CURRENT TRIP claimed", named(v, CurrentSessionView.SC_TRIP_NAME));
		fitsEveryWidth(v, "unknown");
	}

	@Test
	public void longLabels_fitEveryWidth()
	{
		Source s = new Source();
		SkillCompanionView.EventView lep = new SkillCompanionView.EventView("leprechaun", "Woodcutting Leprechaun", 3_725_000L,
			Collections.singletonList(new SkillCompanionView.EventView.Field("BARK", "12,345", null)), Collections.singletonList(
				new SkillCompanionView.ContextLine("Not eligible for full rewards", SkillCompanionView.ContextLine.Tone.ALERT, null)));
		s.view = view("arctic_pine", new SkillCompanionView.TripView(0, null, "Forestry basket", 28, 28, 28, false),
			new SkillCompanionView.FocusView("Arctic Pine tree (Neitiznot)", 12_345, 3_725_000L), lep,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
		CurrentSessionView v = render(woodcutting("ACTIVE", item(ItemID.ARCTIC_PINE_LOG, "Arctic pine logs", 123_456) + ","
			+ item(ItemID.LEAVES, "Leaves", 20) + "," + item(ItemID.LEAVES_OAK, "Oak leaves", 20) + "," + item(ItemID.LEAVES_WILLOW, "Willow leaves", 20)
			+ "," + item(ItemID.LEAVES_MAPLE, "Maple leaves", 20) + "," + item(ItemID.LEAVES_YEW, "Yew leaves", 20) + ","
			+ item(ItemID.LEAVES_MAGIC, "Magic leaves", 20) + "," + item(ItemID.WC_CLUE_NEST_BEGINNER, "Clue nest (beginner)", 1234) + ","
			+ item(BARK, "Anima-infused bark", 123_456), null, "'arctic_pine':36000000", item(ItemID.ARCTIC_PINE_LOG, "Arctic pine logs", 45_678),
			"'forestry:leprechaun':123", "arctic_pine", "'WOODCUTTING':9999999"), s);
		assertEquals("Full", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		assertTrue(text(v, CurrentSessionView.SC_SECONDARY_LINE_PREFIX + 0).endsWith("+2 more"));
		fitsEveryWidth(v, "long labels");
	}

	// ---- History --------------------------------------------------------------------------------------

	@Test
	public void history_compactRow_primaryOnly_andDetailedBlock()
	{
		Session done = woodcutting("FINALIZED", REDWOOD_PRODUCED + "," + item(YEW, "Yew logs", 200), item(REDWOOD, "Redwood logs", 4300)
				+ "," + item(BARK, "Anima-infused bark", 1240), "'redwood':3300000,'yew':300000", item(REDWOOD, "Redwood logs", 265),
			"'forestry:rising_roots':3,'forestry:poachers':1", "redwood", "'WOODCUTTING':86300");
		HistoryEntry entry = HistoryEntry.from(done);
		assertEquals("logs only, never nests / bark / leaves", 5_024L, entry.getSkillProducedTotal());
		HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
		list.render(Collections.singletonList(entry));
		FactLine stats = null;
		for (Component c : all(list))
		{
			if (HistoryView.ROW_STATS_NAME.equals(c.getName()))
			{
				stats = (FactLine) c;
			}
		}
		assertNotNull(stats);
		assertEquals("Redwood" + FactLine.SEPARATOR + "5,024 cut" + FactLine.SEPARATOR + "86,300 XP", stats.text());

		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Cut 5,024", text(d, HistoryDetailView.SKILL_PRODUCED_NAME));
		assertEquals("Banked 4,300 (86%)", text(d, HistoryDetailView.SKILL_BANKED_NAME));
		assertEquals("Auto-burned 265 (5%)", text(d, HistoryDetailView.SKILL_AUTO_TRANSFORMED_NAME));
		assertEquals("Woodcutting XP 86,300", text(d, HistoryDetailView.SKILL_XP_NAME));
		assertEquals("Nests 10", text(d, HistoryDetailView.SKILL_SECONDARY_PREFIX + "nest"));
		assertEquals("Anima-infused bark 1,240", text(d, HistoryDetailView.SKILL_SECONDARY_PREFIX + "bark"));
		assertEquals("Leaves 35", text(d, HistoryDetailView.SKILL_SECONDARY_PREFIX + "leaf"));
		assertEquals("Forestry events 4", text(d, HistoryDetailView.SKILL_OCCURRENCES_NAME));
		assertNotNull(named(d, HistoryDetailView.SKILL_OCCURRENCE_PREFIX + "forestry:rising_roots"));
		assertNotNull(named(d, HistoryDetailView.SKILL_METHOD_PREFIX + "redwood"));
		assertNotNull(named(d, HistoryDetailView.SKILL_METHOD_PREFIX + "yew"));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void history_oldWoodcuttingSession_rendersAsBefore()
	{
		Session old = GSON.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'woodcutting',"
			+ "'displayName':'Woodcutting'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'WOODCUTTING':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		HistoryEntry entry = HistoryEntry.from(old);
		assertEquals(0L, entry.getSkillProducedTotal());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(old));
		assertNull(named(d, HistoryDetailView.SKILL_BLOCK_NAME));
	}
}
