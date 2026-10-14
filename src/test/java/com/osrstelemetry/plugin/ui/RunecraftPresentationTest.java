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
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.runecraft.RunecraftCompanionDefinition;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The Runecraft companion as the player sees it -- METHOD, CURRENT TRIP
 * (essence + pouches, or the Arceuus CURRENT PHASE), SESSION (trips, runes /
 * trip), PRODUCTION (runes, essence used, other inputs, binds; Ourania as a
 * mix), TARGET, History -- rendered by the shared Current Session / History
 * code at 150 / 180 / 213 / 260 px with real layout passes.
 */
public class RunecraftPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-13T20:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};
	private static final int NATURE = ItemID.NATURERUNE;
	private static final int PURE = ItemID.BLANKRUNE_HIGH;

	// ---- fixtures ---------------------------------------------------------------------------------

	private static Session runecraft(String state, String produced, String banked, String methodMillis, String consumed, String cycles,
		String occurrences, String lastMethod, String xp, long activeMillis)
	{
		String stats = "'skillStats':{'skillKey':'runecraft'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}")
			+ (consumed == null ? "" : ",'consumed':[" + consumed + "]")
			+ (cycles == null ? "" : ",'cycles':{" + cycles + "}")
			+ (occurrences == null ? "" : ",'occurrences':{" + occurrences + "}")
			+ (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'") + "}";
		return GSON.fromJson(("{'sessionId':'s-rc','activityIdentity':{'activityType':'SKILLING','activityKey':'runecraft',"
			+ "'displayName':'Runecraft'},'state':'" + state + "','startedAt':'2026-10-13T18:58:00Z','lastActiveAt':'2026-10-13T20:00:00Z',"
			+ "'accumulatedActiveDurationMillis':" + activeMillis + "," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-13T20:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{" + xp + "},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static Session active(String produced, String methodMillis, String consumed, String cycles, String occurrences, String method)
	{
		return runecraft("ACTIVE", produced, null, methodMillis, consumed, cycles, occurrences, method, "'RUNECRAFT':84300", 3_600_000L);
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name + "','quantity':" + qty + "}";
	}

	private static String trips(String method, long count, long total, long best)
	{
		return "'" + method + "':{'count':" + count + ",'totalMillis':" + total + ",'bestMillis':" + best + ",'failures':0}";
	}

	static final class Source implements SkillCompanionPanelSource
	{
		SkillCompanionView view = SkillCompanionView.EMPTY;
		SkillGoal goal = SkillGoal.DEFAULT;
		Integer xp = Experience.getXpForLevel(85) + 20_000;
		Map<Integer, Long> bank;

		@Override
		public SkillCompanionDefinition definition()
		{
			return RunecraftCompanionDefinition.INSTANCE;
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

	private static SkillCompanionView view(String method, SkillCompanionView.EventView event)
	{
		return view(method, event, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
	}

	private static SkillCompanionView view(String method, SkillCompanionView.EventView event, Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return new SkillCompanionView("runecraft", method, "s-rc", Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), null, Collections.<SkillCompanionView.ContextLine>emptyList(), banking)
			.withCycles(null, Collections.<String, SkillCycleStats>emptyMap(), Collections.<String, SkillCycleStats>emptyMap())
			.withExtras(null, null, null, null, null, event);
	}

	private static SkillCompanionView.EventView trip(String name, long elapsed, String essence, String pouches, String... lines)
	{
		List<SkillCompanionView.EventView.Field> fields = new ArrayList<>();
		fields.add(new SkillCompanionView.EventView.Field("ESSENCE", essence, null));
		if (pouches != null)
		{
			fields.add(new SkillCompanionView.EventView.Field("POUCHES", pouches, null));
		}
		List<SkillCompanionView.ContextLine> ls = new ArrayList<>();
		for (String l : lines)
		{
			ls.add(new SkillCompanionView.ContextLine(l, SkillCompanionView.ContextLine.Tone.ALERT, null));
		}
		return new SkillCompanionView.EventView("trip", name, elapsed, fields, ls);
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

	private static final String NATURE_RUN = item(NATURE, "Nature rune", 1555);

	// ---- Current Session ---------------------------------------------------------------------------

	@Test
	public void natureStandardAltar_tripSessionProductionTarget()
	{
		Source s = new Source();
		s.view = view("nature", trip("Since bank", 72_000L, "42", "3 loaded"));
		s.goal = SkillGoal.DEFAULT.withTargetLevel(91);
		CurrentSessionView v = render(active(NATURE_RUN, "'nature':3600000", item(PURE, "Pure essence", 756),
			trips("nature", 18, 18 * 190_000L, 171_000), "'trip_runes:nature':1555,'trip_essence:nature':756", "nature"), s);
		assertEquals("Nature runes", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("Since bank  1:12", text(v, CurrentSessionView.SC_EVENT_TITLE_NAME));
		assertEquals("42", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "ESSENCE"));
		assertEquals("3 loaded", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "POUCHES"));
		assertEquals("18", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "TRIPS"));
		assertEquals("86.4", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "RUNES / TRIP"));
		assertNull("RUNES / TRIP replaces BEST TRIP", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "BEST TRIP"));
		assertEquals("1,555", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "NATURE RUNES"));
		assertEquals("756 essence used", text(v, CurrentSessionView.SC_CONSUMED_NAME));
		assertEquals("1,555 runes/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertNull("no other inputs", named(v, CurrentSessionView.SC_OTHER_INPUTS_NAME));
		assertNull("runes are stackable: no slot trip", named(v, CurrentSessionView.SC_TRIP_NAME));
		assertNull("no current-cycle section is claimed", named(v, CurrentSessionView.SC_CYCLE_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		fitsEveryWidth(v, "nature");
	}

	@Test
	public void pouchHeavy_withDegradedWarning()
	{
		Source s = new Source();
		s.view = view("blood", trip("Since bank", 30_000L, "101", "5 loaded", "Colossal pouch degraded (holds 30 of 40)",
			"Giant pouch degraded (holds 9 of 12)"));
		CurrentSessionView v = render(active(item(ItemID.BLOODRUNE, "Blood rune", 900), "'blood':3600000", item(PURE, "Pure essence", 900),
			null, null, "blood"), s);
		assertEquals("Blood runes — True Altar", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("Colossal pouch degraded (holds 30 of 40)", text(v, CurrentSessionView.SC_EVENT_LINE_PREFIX + 0));
		fitsEveryWidth(v, "pouches");
	}

	@Test
	public void midTrip_joined_noTripTimer_noFabricatedTrip()
	{
		Source s = new Source();
		s.view = view("nature", trip(null, 0L, "27", null));
		CurrentSessionView v = render(active(item(NATURE, "Nature rune", 54), "'nature':120000", item(PURE, "Pure essence", 27), null, null,
			"nature"), s);
		assertNull("joined mid-run: no trip time is claimed", named(v, CurrentSessionView.SC_EVENT_TITLE_NAME));
		assertEquals("27", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "ESSENCE"));
		assertEquals("0", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "TRIPS"));
		assertEquals("-", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "RUNES / TRIP"));
		fitsEveryWidth(v, "mid-trip");
	}

	@Test
	public void levelTarget_andStockpileTarget_usesBankedRate()
	{
		Source s = new Source();
		s.view = view("nature", null);
		s.goal = SkillGoal.DEFAULT.withTargetLevel(91);
		Session session = active(NATURE_RUN, "'nature':3600000", item(PURE, "Pure essence", 756), null, null, "nature");
		CurrentSessionView v = render(session, s);
		assertEquals(String.format("%,d", Experience.getXpForLevel(91) - s.xp),
			cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
		assertTrue(cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_ETA_CELL).startsWith("~"));
		fitsEveryWidth(v, "level");

		s.bank = new HashMap<>();
		s.bank.put(NATURE, 62_400L);
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new HashMap<>();
		banking.put(NATURE, new SkillGoalPlanner.BankingSample(1_200, 15, 3_000_000L));
		s.view = view("nature", null, banking);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(NATURE).withTargetQuantity(100_000);
		v = render(session, s);
		assertEquals("62,400", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("37,600", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		@SuppressWarnings("unchecked")
		JComboBox<Object> combo = (JComboBox<Object>) named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME);
		assertEquals("Nature runes only, never every rune", 1, combo.getItemCount());
		// 1,200 banked in 50 minutes -> 37,600 more takes ~26h at the BANKED rate (the 1,555/hr crafted rate would claim ~24h).
		String eta = text(v, CurrentSessionView.SC_ETA_LINE_NAME);
		assertTrue(eta, eta.startsWith("ETA ~26h"));
		fitsEveryWidth(v, "stockpile");
	}

	@Test
	public void ourania_mixedOutput_asShares()
	{
		Source s = new Source();
		s.view = view("ourania", trip("Since bank", 45_000L, "28", "2 loaded"));
		CurrentSessionView v = render(active(item(NATURE, "Nature rune", 860) + "," + item(ItemID.LAWRUNE, "Law rune", 640) + ","
			+ item(ItemID.COSMICRUNE, "Cosmic rune", 590) + "," + item(ItemID.CHAOSRUNE, "Chaos rune", 1020) + ","
			+ item(ItemID.BODYRUNE, "Body rune", 700) + "," + item(ItemID.DEATHRUNE, "Death rune", 210), "'ourania':3600000",
			item(PURE, "Pure essence", 1104), null, null, "ourania"), s);
		assertEquals("Ourania Altar", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("4,020", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "TOTAL RUNES"));
		assertEquals("Chaos 25% • Nature 21% • Body 17% • Law 16% • +2 more", text(v, CurrentSessionView.SC_BREAKDOWN_NAME));
		assertEquals("4,020 runes/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertEquals("1,104 essence used", text(v, CurrentSessionView.SC_CONSUMED_NAME));
		fitsEveryWidth(v, "ourania");
	}

	@Test
	public void combinationRune_catalystsByName_andObservedBinds()
	{
		Source s = new Source();
		s.view = view("lava", trip("Since bank", 20_000L, "0", "4 loaded", "No binding necklace: binds can fail"));
		CurrentSessionView v = render(active(item(ItemID.LAVARUNE, "Lava rune", 412), "'lava':3600000",
			item(PURE, "Pure essence", 448) + "," + item(ItemID.EARTHRUNE, "Earth rune", 448) + "," + item(ItemID.FIRE_TALISMAN, "Fire talisman", 16),
			null, "'bind_attempts:lava':448,'bind_bound:lava':412", "lava"), s);
		assertEquals("Lava runes", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("448 essence used", text(v, CurrentSessionView.SC_CONSUMED_NAME));
		assertEquals("Also used: Earth rune 448 • Fire talisman 16", text(v, CurrentSessionView.SC_OTHER_INPUTS_NAME));
		assertEquals("Bound 412 / 448 (92%)", text(v, CurrentSessionView.SC_OCCURRENCE_LINE_PREFIX + 0));
		assertEquals("No binding necklace: binds can fail", text(v, CurrentSessionView.SC_EVENT_LINE_PREFIX + 0));
		fitsEveryWidth(v, "lava");
	}

	@Test
	public void arceuusBlood_currentPhase_notACurrentTrip()
	{
		Source s = new Source();
		SkillCompanionView.EventView phase = new SkillCompanionView.EventView("arceuus", "Chiselling", 14_000L, Arrays.asList(
			new SkillCompanionView.EventView.Field("FRAGMENTS", "52", null), new SkillCompanionView.EventView.Field("DARK BLOCKS", "13", null)),
			Collections.<SkillCompanionView.ContextLine>emptyList()).withTitle("Current phase");
		s.view = view("blood_arceuus", phase);
		CurrentSessionView v = render(active(item(ItemID.BLOODRUNE, "Blood rune", 1300), "'blood_arceuus':3600000",
			item(ItemID.BIGBLANKRUNE, "Dark essence fragments", 1300), null, null, "blood_arceuus"), s);
		assertEquals("Blood runes — Arceuus", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("Chiselling  0:14", text(v, CurrentSessionView.SC_EVENT_TITLE_NAME));
		assertEquals("52", cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "FRAGMENTS"));
		assertNull(cell(v, CurrentSessionView.SC_EVENT_GRID_NAME, "ESSENCE"));
		assertEquals("1,300 essence used", text(v, CurrentSessionView.SC_CONSUMED_NAME));
		boolean phaseCaption = false;
		for (Component c : all(v))
		{
			phaseCaption |= c instanceof JLabel && ((JLabel) c).getText() != null && "CURRENT PHASE".equalsIgnoreCase(((JLabel) c).getText().replaceAll("<[^>]*>", "").trim());
		}
		assertTrue("the section is the method's current phase", phaseCaption);
		fitsEveryWidth(v, "arceuus blood");
	}

	@Test
	public void arceuusSoul_layout()
	{
		Source s = new Source();
		s.view = view("soul_arceuus", new SkillCompanionView.EventView("arceuus", "Mining dense essence", 61_000L, Arrays.asList(
			new SkillCompanionView.EventView.Field("FRAGMENTS", "0", null), new SkillCompanionView.EventView.Field("DARK BLOCKS", "0", null)),
			Collections.singletonList(new SkillCompanionView.ContextLine("18 dense blocks to venerate", SkillCompanionView.ContextLine.Tone.QUIET,
				null))).withTitle("Current phase"));
		CurrentSessionView v = render(active(item(ItemID.SOULRUNE, "Soul rune", 400), "'soul_arceuus':3600000",
			item(ItemID.BIGBLANKRUNE, "Dark essence fragments", 400), null, null, "soul_arceuus"), s);
		assertEquals("Soul runes — Arceuus", text(v, CurrentSessionView.SC_METHOD_NAME));
		fitsEveryWidth(v, "arceuus soul");
	}

	@Test
	public void scarExtracts_areAnInputLine_withActualOutput()
	{
		Source s = new Source();
		s.view = view("nature", trip("Since bank", 25_000L, "0", null));
		CurrentSessionView v = render(active(item(NATURE, "Nature rune", 27 * 61), "'nature':900000",
			item(PURE, "Pure essence", 27) + "," + item(ItemID.SCAR_EXTRACT_MANGLED, "Mangled extract", 27), null, null, "nature"), s);
		assertEquals("1,647", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "NATURE RUNES"));
		assertEquals("27 essence used", text(v, CurrentSessionView.SC_CONSUMED_NAME));
		assertEquals("Also used: Mangled extract 27", text(v, CurrentSessionView.SC_OTHER_INPUTS_NAME));
		fitsEveryWidth(v, "scar");
	}

	@Test
	public void beforeMethodEvidence_plainSession_noDeadSections()
	{
		Source s = new Source();
		s.view = view(null, null);
		CurrentSessionView v = render(runecraft("ACTIVE", null, null, null, null, null, null, null, "'RUNECRAFT':300", 60_000L), s);
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.SC_PRODUCTION_NAME));
		assertNull(named(v, CurrentSessionView.SC_EVENT_NAME));
		assertNull(named(v, CurrentSessionView.SC_CYCLE_SESSION_NAME));
		assertNotNull("the level target is always there", named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		fitsEveryWidth(v, "generic");
	}

	@Test
	public void longLabels_fitEveryWidth()
	{
		Source s = new Source();
		s.view = view("blood", trip("Since bank", 3_725_000L, "12,345", "5 loaded", "Colossal pouch degraded (holds 35 of 40)",
			"No binding necklace: binds can fail", "Route: Abyss"));
		CurrentSessionView v = render(active(item(ItemID.BLOODRUNE, "Blood rune", 1_234_567), "'blood':36000000",
			item(ItemID.BLANKRUNE_DAEYALT, "Daeyalt essence", 123_456) + "," + item(ItemID.SCAR_EXTRACT_SCARRED, "Scarred extract", 12_345)
				+ "," + item(ItemID.SUNFIRESPLINTER, "Sunfire splinter", 99_999),
			trips("blood", 1234, 1234 * 190_000L, 171_000), "'trip_runes:blood':1234567", "blood"), s);
		fitsEveryWidth(v, "long labels");
	}

	// ---- History -----------------------------------------------------------------------------------

	@Test
	public void history_compactRow_detail_yields_inputs_binds()
	{
		Session done = runecraft("FINALIZED", NATURE_RUN, NATURE_RUN, "'nature':3600000",
			item(PURE, "Pure essence", 700) + "," + item(ItemID.BLANKRUNE_DAEYALT, "Daeyalt essence", 56), trips("nature", 18, 18 * 190_000L, 171_000),
			"'trip_runes:nature':1555,'trip_essence:nature':756", "nature", "'RUNECRAFT':84300", 3_600_000L);
		HistoryEntry entry = HistoryEntry.from(done);
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
		assertEquals("Nature runes" + FactLine.SEPARATOR + "18 trips" + FactLine.SEPARATOR + "1,555 crafted" + FactLine.SEPARATOR + "84,300 XP",
			stats.text());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Trips 18", text(d, HistoryDetailView.SKILL_CYCLES_NAME));
		assertEquals("Runes / trip", text(d, HistoryDetailView.SKILL_CYCLE_YIELD_PREFIX + "trip_runes:"));
		assertNotNull(named(d, HistoryDetailView.SKILL_CYCLE_YIELD_PREFIX + "trip_essence:"));
		assertEquals("Crafted 1,555", text(d, HistoryDetailView.SKILL_PRODUCED_NAME));
		assertEquals("Essence used 756", text(d, HistoryDetailView.SKILL_CONSUMED_NAME));
		assertEquals("Daeyalt essence 56", text(d, HistoryDetailView.SKILL_CONSUMED_PREFIX + ItemID.BLANKRUNE_DAEYALT));
		assertEquals("Runecraft XP 84,300", text(d, HistoryDetailView.SKILL_XP_NAME));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void history_combination_bindLine_andCatalystRows()
	{
		Session done = runecraft("FINALIZED", item(ItemID.LAVARUNE, "Lava rune", 412), null, "'lava':3600000",
			item(PURE, "Pure essence", 448) + "," + item(ItemID.EARTHRUNE, "Earth rune", 448), null,
			"'bind_attempts:lava':448,'bind_bound:lava':412", "lava", "'RUNECRAFT':40000", 3_600_000L);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Bound 412 / 448 (92%)", text(d, HistoryDetailView.SKILL_OCCURRENCE_LINE_PREFIX + 0));
		assertEquals("Earth rune 448", text(d, HistoryDetailView.SKILL_CONSUMED_PREFIX + ItemID.EARTHRUNE));
		assertEquals("Essence used 448", text(d, HistoryDetailView.SKILL_CONSUMED_NAME));
	}

	@Test
	public void history_mixedMethods_neverBlendTripAverages()
	{
		Session done = runecraft("FINALIZED", NATURE_RUN + "," + item(ItemID.LAWRUNE, "Law rune", 400), null, "'nature':1800000,'law':1800000",
			item(PURE, "Pure essence", 1100), trips("nature", 9, 9 * 190_000L, 171_000) + "," + trips("law", 8, 8 * 210_000L, 190_000),
			"'trip_runes:nature':1555,'trip_runes:law':400", "law", "'RUNECRAFT':80000", 3_600_000L);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Trips 17", text(d, HistoryDetailView.SKILL_CYCLES_NAME));
		assertNull("two methods: no blended runes / trip", named(d, HistoryDetailView.SKILL_CYCLE_YIELD_PREFIX + "trip_runes:"));
		assertNull(named(d, HistoryDetailView.SKILL_CYCLE_AVG_NAME));
	}

	@Test
	public void history_oldRunecraftSession_rendersAsBefore()
	{
		Session old = GSON.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'runecraft',"
			+ "'displayName':'Runecraft'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'RUNECRAFT':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		HistoryEntry entry = HistoryEntry.from(old);
		assertEquals(0L, entry.getSkillProducedTotal());
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(old));
		assertNull(named(d, HistoryDetailView.SKILL_BLOCK_NAME));
		assertFalse(entry.getSkillCycleTotal() > 0);
	}
}
