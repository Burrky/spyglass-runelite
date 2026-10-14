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
import com.osrstelemetry.plugin.skills.fishing.FishingCompanionDefinition;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
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
 * The Fishing companion as the player sees it, rendered by the SAME
 * Current Session / History code as Mining (no Fishing-specific panel):
 * natural captions, FISH CAUGHT with a breakdown line, the fish barrel line,
 * SUPPLIES with RUNWAY, stackable methods without a trip, targets -- at 150 /
 * 180 / 213 / 260 px -- and History's compact row, FISHING block and CAUGHT
 * grid. Mining and Fishing companions registered side by side never mix.
 */
public class FishingPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-12T17:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private static final int LOBSTER = ItemID.RAW_LOBSTER;
	private static final int TROUT = ItemID.RAW_TROUT;
	private static final int SALMON = ItemID.RAW_SALMON;
	private static final int KARAMBWAN = ItemID.TBWT_RAW_KARAMBWAN;
	private static final int MINNOW = ItemID.MINNOW;

	// ---- fixtures ---------------------------------------------------------------------------------

	private static Session fishing(String state, String produced, String banked, String methodMillis, String lastMethod)
	{
		String stats = "'skillStats':{'skillKey':'fishing'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}") + (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'")
			+ "}";
		return GSON.fromJson(("{'sessionId':'s-fish','activityIdentity':{'activityType':'SKILLING','activityKey':'fishing',"
			+ "'displayName':'Fishing'},'state':'" + state + "','startedAt':'2026-10-12T15:58:00Z','lastActiveAt':'2026-10-12T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-12T17:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'FISHING':84300},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static Session mining()
	{
		return GSON.fromJson(("{'sessionId':'s-mine','activityIdentity':{'activityType':'SKILLING','activityKey':'mining',"
			+ "'displayName':'Mining'},'state':'ACTIVE','startedAt':'2026-10-12T15:58:00Z','lastActiveAt':'2026-10-12T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'MINING':38600},'lootDrops':[],"
			+ "'producedItems':[" + item(ItemID.IRON_ORE, "Iron ore", 900) + "],'skillStats':{'skillKey':'mining',"
			+ "'methodMillis':{'iron':3600000},'lastMethod':'iron'}}}").replace('\'', '"'), Session.class);
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
		final SkillCompanionDefinition def;
		SkillCompanionView view = SkillCompanionView.EMPTY;
		SkillGoal goal = SkillGoal.DEFAULT;
		Integer xp = Experience.getXpForLevel(70) + 10_000;
		Map<Integer, Long> bank;

		Source(SkillCompanionDefinition def)
		{
			this.def = def;
		}

		Source()
		{
			this(FishingCompanionDefinition.INSTANCE);
		}

		@Override
		public SkillCompanionDefinition definition()
		{
			return def;
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

	private static SkillCompanionView view(String skill, String method, SkillCompanionView.TripView trip,
		List<SkillCompanionView.ContextLine> context)
	{
		return new SkillCompanionView(skill, method, null, Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), trip,
			context == null ? Collections.<SkillCompanionView.ContextLine>emptyList() : context,
			Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
	}

	private static SkillCompanionView view(String method, SkillCompanionView.TripView trip)
	{
		return view("fishing", method, trip, null);
	}

	private static SkillCompanionView.TripView trip(int space, Long estMillis)
	{
		return new SkillCompanionView.TripView(space, estMillis, null, null, null, 0, false);
	}

	private static CurrentSessionView render(Session session, Source... sources)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		for (Source s : sources)
		{
			v.setSkillCompanionSource(s);
		}
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

	// ---- Current Session ---------------------------------------------------------------------------

	@Test
	public void ordinaryFishing_lobsters_naturalCaption()
	{
		Source s = new Source();
		s.view = view("lobster", trip(9, 95_000L));
		CurrentSessionView v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 412), item(LOBSTER, "Raw lobster", 364),
			"'lobster':3600000", "lobster"), s);
		assertEquals("Lobster", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("9", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_SPACE_LEFT_CELL));
		assertEquals("~1:35", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		assertEquals("412", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "LOBSTERS"));
		assertEquals("364", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, CurrentSessionView.SC_BANKED_CELL));
		assertEquals("412 lobsters/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertNull("no supplies for cages", named(v, CurrentSessionView.SC_SUPPLY_NAME));
		fitsEveryWidth(v, "lobsters");
	}

	@Test
	public void flyFishing_fishCaught_breakdown_methodRate_andSupplies()
	{
		Source s = new Source();
		s.view = view("fly", trip(14, null)).withSupply(new SkillCompanionView.SupplyView("Feathers", 1_240L, 31L * 60_000L));
		CurrentSessionView v = render(fishing("ACTIVE", item(TROUT, "Raw trout", 700) + "," + item(SALMON, "Raw salmon", 312), null,
			"'fly':3600000", "fly"), s);
		assertEquals("Fly fishing", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("1,012", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "FISH CAUGHT"));
		assertEquals("Trout 700 • Salmon 312", text(v, CurrentSessionView.SC_BREAKDOWN_NAME));
		assertEquals("one multi-output method: its own rate", "1,012 fish/hr", text(v, CurrentSessionView.SC_RATE_NAME));
		assertEquals("1,240", cell(v, CurrentSessionView.SC_SUPPLY_GRID_NAME, "FEATHERS"));
		assertEquals(SkillGoalPlanner.etaText(31L * 60_000L), cell(v, CurrentSessionView.SC_SUPPLY_GRID_NAME, CurrentSessionView.SC_RUNWAY_CELL));
		fitsEveryWidth(v, "fly fishing");
	}

	@Test
	public void karambwan_withKarambwanjiRunway_unknownRunwayShowsNoGuess()
	{
		Source s = new Source();
		s.view = view("karambwan", trip(20, 240_000L)).withSupply(new SkillCompanionView.SupplyView("Karambwanji", 300L, null));
		CurrentSessionView v = render(fishing("ACTIVE", item(KARAMBWAN, "Raw karambwan", 180), null, "'karambwan':3600000", "karambwan"), s);
		assertEquals("180", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "KARAMBWAN"));
		assertEquals("300", cell(v, CurrentSessionView.SC_SUPPLY_GRID_NAME, "KARAMBWANJI"));
		String runway = cell(v, CurrentSessionView.SC_SUPPLY_GRID_NAME, CurrentSessionView.SC_RUNWAY_CELL);
		assertTrue("no runway before the evidence: " + runway, runway == null || "-".equals(runway));
		fitsEveryWidth(v, "karambwan");
	}

	@Test
	public void outOfBait_saysOut()
	{
		Source s = new Source();
		s.view = view("karambwan", trip(20, null)).withSupply(new SkillCompanionView.SupplyView("Karambwanji", 0L, 0L));
		CurrentSessionView v = render(fishing("ACTIVE", item(KARAMBWAN, "Raw karambwan", 18), null, "'karambwan':600000", "karambwan"), s);
		assertEquals("Out", cell(v, CurrentSessionView.SC_SUPPLY_GRID_NAME, CurrentSessionView.SC_RUNWAY_CELL));
	}

	@Test
	public void fishBarrel_unknownContents_isHonest_thenKnown()
	{
		Source s = new Source();
		s.view = view("lobster", new SkillCompanionView.TripView(20, null, "Fish barrel", null, 28, 12, true));
		CurrentSessionView v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 20), null, "'lobster':600000", "lobster"), s);
		assertEquals("Fish barrel +12 this trip", text(v, CurrentSessionView.SC_STORAGE_NAME));
		assertEquals("-", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		fitsEveryWidth(v, "barrel unknown");

		s.view = view("lobster", new SkillCompanionView.TripView(20, 300_000L, "Fish barrel", 8, 28, 8, true).withEstIncludingStorage());
		v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 20), null, "'lobster':600000", "lobster"), s);
		assertEquals("Fish barrel 8 / 28", text(v, CurrentSessionView.SC_STORAGE_NAME));
		assertEquals("~5:00", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_EST_FULL_CELL));
		fitsEveryWidth(v, "barrel known");
	}

	@Test
	public void minnows_stackable_noTrip_contextLine()
	{
		Source s = new Source();
		s.view = view("fishing", "minnows", trip(20, null), Collections.singletonList(new SkillCompanionView.ContextLine(
			"Flying fish eating your minnows", SkillCompanionView.ContextLine.Tone.ALERT, "tip")));
		CurrentSessionView v = render(fishing("ACTIVE", item(MINNOW, "Minnow", 2_412), null, "'minnows':3600000", "minnows"), s);
		assertNull("stackable: inventory space is never the limit", named(v, CurrentSessionView.SC_TRIP_NAME));
		assertEquals("2,412", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "MINNOWS"));
		assertEquals("Flying fish eating your minnows", text(v, CurrentSessionView.SC_CONTEXT_PREFIX + 0));
		fitsEveryWidth(v, "minnows");
	}

	@Test
	public void unknownMethod_noMethodRow_stillShowsWhatWasCaught()
	{
		Source s = new Source();
		CurrentSessionView v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 3), null, null, null), s);
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("3", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "LOBSTERS"));
		fitsEveryWidth(v, "unknown method");
	}

	@Test
	public void levelTarget_usesFishingXp()
	{
		Source s = new Source();
		s.view = view("lobster", trip(10, null));
		s.goal = SkillGoal.DEFAULT.withTargetLevel(76);
		CurrentSessionView v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 412), null, "'lobster':3600000", "lobster"), s);
		int left = Experience.getXpForLevel(76) - s.xp;
		assertEquals(String.format("%,d", left), cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_XP_LEFT_CELL));
		fitsEveryWidth(v, "level target");
	}

	@Test
	public void stockpileTarget_isMethodAware_andNoEtaWithoutBanking()
	{
		Source s = new Source();
		s.bank = new HashMap<>();
		s.bank.put(SALMON, 900L);
		s.view = view("fly", trip(14, null));
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(SALMON).withTargetQuantity(2_000);
		CurrentSessionView v = render(fishing("ACTIVE", item(TROUT, "Raw trout", 70) + "," + item(SALMON, "Raw salmon", 31), null,
			"'fly':600000", "fly"), s);
		@SuppressWarnings("unchecked")
		JComboBox<Object> combo = (JComboBox<Object>) named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME);
		assertEquals("fly fishing: trout and salmon", 2, combo.getItemCount());
		assertEquals("900", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("1,100", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertEquals("ETA -", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		fitsEveryWidth(v, "stockpile");
	}

	@Test
	public void stockpileBeforeAnyCatch_hintIsFishingWorded()
	{
		Source s = new Source();
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE);
		CurrentSessionView v = render(fishing("ACTIVE", null, null, null, null), s);
		String hint = text(v, CurrentSessionView.SC_ETA_LINE_NAME);
		assertTrue(hint, hint == null || hint.startsWith("Catch something first"));
	}

	@Test
	public void miningAndFishingSideBySide_eachSessionReadsOnlyItsOwnCompanion()
	{
		Source fish = new Source();
		fish.view = view("lobster", trip(9, null));
		Source mine = new Source(MiningCompanionDefinition.INSTANCE);
		mine.view = view("mining", "iron", trip(4, null), null);

		CurrentSessionView v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 12), null, "'lobster':600000", "lobster"), mine, fish);
		assertEquals("Lobster", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("9", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_SPACE_LEFT_CELL));

		v = render(mining(), mine, fish);
		assertEquals("Iron", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("4", cell(v, CurrentSessionView.SC_TRIP_GRID_NAME, CurrentSessionView.SC_SPACE_LEFT_CELL));
		assertEquals("900", cell(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME, "IRON MINED"));
	}

	@Test
	public void separateTargetsPerSkill()
	{
		Source fish = new Source();
		Source mine = new Source(MiningCompanionDefinition.INSTANCE);
		CurrentSessionView v = render(fishing("ACTIVE", item(LOBSTER, "Raw lobster", 12), null, "'lobster':600000", "lobster"), mine, fish);
		v.selectSkillGoal(SkillGoal.DEFAULT.withTargetLevel(80));
		assertEquals(80, fish.goal.getTargetLevel());
		assertEquals("Mining's target is untouched", SkillGoal.DEFAULT, mine.goal);
	}

	// ---- History ------------------------------------------------------------------------------------

	@Test
	public void history_compactRow_fishingBlock_andCaughtGrid()
	{
		Session done = fishing("FINALIZED", item(KARAMBWAN, "Raw karambwan", 1_842), item(KARAMBWAN, "Raw karambwan", 1_700),
			"'karambwan':3600000", "karambwan");
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
		assertEquals("Karambwan" + FactLine.SEPARATOR + "1,842 caught" + FactLine.SEPARATOR + "84,300 XP", stats.text());

		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Caught 1,842", text(d, HistoryDetailView.SKILL_PRODUCED_NAME));
		assertEquals("Banked 1,700 (92%)", text(d, HistoryDetailView.SKILL_BANKED_NAME));
		assertEquals("Fishing XP 84,300", text(d, HistoryDetailView.SKILL_XP_NAME));
		assertEquals("Karambwan", text(d, HistoryDetailView.SKILL_METHOD_PREFIX + "karambwan"));
		assertEquals("CAUGHT", text(d, HistoryDetailView.PRODUCED_ITEMS_NAME));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}
}
