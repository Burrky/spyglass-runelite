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
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.agility.AgilityCompanionDefinition;
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
 * The Agility companion as the player sees it -- COURSE, CURRENT LAP,
 * SESSION (laps, laps/hr, avg, best), REWARDS (marks / crystal shards /
 * pyramid tops, never for a course without one), TARGET -- rendered by the
 * SAME Current Session / History code as Mining and Fishing, at 150 / 180 /
 * 213 / 260 px with real layout passes.
 */
public class AgilityPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-12T17:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	// ---- fixtures ---------------------------------------------------------------------------------

	private static Session agility(String state, String produced, String banked, String methodMillis, String cycles, String lastMethod)
	{
		String stats = "'skillStats':{'skillKey':'agility'" + (banked == null ? "" : ",'banked':[" + banked + "]")
			+ (methodMillis == null ? "" : ",'methodMillis':{" + methodMillis + "}") + (cycles == null ? "" : ",'cycles':{" + cycles + "}")
			+ (lastMethod == null ? "" : ",'lastMethod':'" + lastMethod + "'") + "}";
		return GSON.fromJson(("{'sessionId':'s-agi','activityIdentity':{'activityType':'SKILLING','activityKey':'agility',"
			+ "'displayName':'Agility'},'state':'" + state + "','startedAt':'2026-10-12T15:58:00Z','lastActiveAt':'2026-10-12T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000," + (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-12T17:05:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'AGILITY':62400},'lootDrops':[]"
			+ (produced == null ? "" : ",'producedItems':[" + produced + "]") + "," + stats + "}}").replace('\'', '"'), Session.class);
	}

	private static String item(int id, String name, long qty)
	{
		return "{'itemId':" + id + ",'itemName':'" + name + "','quantity':" + qty + "}";
	}

	private static String laps(String course, long count, long total, long best, long falls)
	{
		return "'" + course + "':{'count':" + count + ",'totalMillis':" + total + ",'bestMillis':" + best + ",'failures':" + falls + "}";
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
		Integer xp = Experience.getXpForLevel(80) + 10_000;
		Map<Integer, Long> bank;

		Source(SkillCompanionDefinition def)
		{
			this.def = def;
		}

		Source()
		{
			this(AgilityCompanionDefinition.INSTANCE);
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

	private static SkillCompanionView view(String course, SkillCompanionView.CycleView lap)
	{
		return view(course, lap, Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());
	}

	private static SkillCompanionView view(String course, SkillCompanionView.CycleView lap, Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		return new SkillCompanionView("agility", course, "s-agi", Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
			Collections.<String, Long>emptyMap(), null, Collections.<SkillCompanionView.ContextLine>emptyList(), banking)
			.withCycles(lap, Collections.<String, SkillCycleStats>emptyMap(), Collections.<String, SkillCycleStats>emptyMap());
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

	// ---- rooftops ---------------------------------------------------------------------------------

	@Test
	public void highLevelRooftop_courseLapSessionRewardsTarget()
	{
		Source s = new Source();
		s.view = view("ardougne", new SkillCompanionView.CycleView("ardougne", 5, 7, 41_000L, true));
		s.goal = SkillGoal.DEFAULT.withTargetLevel(90);
		CurrentSessionView v = render(agility("ACTIVE", item(ItemID.GRACE, "Mark of grace", 7), null, "'ardougne':2520000",
			laps("ardougne", 38, 38 * 66_000L, 58_000, 0), "ardougne"), s);
		assertEquals("Ardougne Rooftop", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("5 / 7", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "OBSTACLE"));
		assertEquals("0:41", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "LAP TIME"));
		assertEquals("38", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "LAPS"));
		assertEquals("54.3", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "LAPS / HR"));
		assertEquals("1:06", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "AVG LAP"));
		assertEquals("0:58", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "BEST LAP"));
		assertEquals("7", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "MARKS"));
		assertEquals("10.0", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "MARKS / HR"));
		assertNull("laps are never TOTAL / BANKED", named(v, CurrentSessionView.SC_PRODUCTION_GRID_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_TARGET_GRID_NAME));
		assertNull("no inventory trip for Agility", named(v, CurrentSessionView.SC_TRIP_NAME));
		fitsEveryWidth(v, "ardougne");
	}

	@Test
	public void lowLevelRooftop_noRateYet_noMarksYet()
	{
		Source s = new Source();
		s.view = view("draynor", new SkillCompanionView.CycleView("draynor", 2, 7, 9_000L, true));
		CurrentSessionView v = render(agility("ACTIVE", null, null, "'draynor':120000", laps("draynor", 2, 100_000, 49_000, 0), "draynor"), s);
		assertEquals("Draynor Village Rooftop", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("-", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "LAPS / HR"));
		assertEquals("a rooftop shows its marks even at zero", "0", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "MARKS"));
		assertEquals("-", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "MARKS / HR"));
		fitsEveryWidth(v, "draynor");
	}

	@Test
	public void midCourse_unsynchronisedLap_showsPosition_noTimer()
	{
		Source s = new Source();
		s.view = view("seers", new SkillCompanionView.CycleView("seers", 4, 6, null, false));
		CurrentSessionView v = render(agility("ACTIVE", null, null, "'seers':60000", null, "seers"), s);
		assertEquals("4 / 6", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "OBSTACLE"));
		assertEquals("-", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "LAP TIME"));
		assertEquals("0", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "LAPS"));
		assertEquals("-", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "BEST LAP"));
		fitsEveryWidth(v, "unsynced");
	}

	@Test
	public void falls_compactContextLine_only()
	{
		Source s = new Source();
		s.view = view("canifis", new SkillCompanionView.CycleView("canifis", 1, 8, 3_000L, true));
		CurrentSessionView v = render(agility("ACTIVE", null, null, "'canifis':900000", laps("canifis", 12, 12 * 70_000L, 66_000, 2), "canifis"), s);
		assertEquals("2 falls this session", text(v, CurrentSessionView.SC_CONTEXT_PREFIX + 0));
		fitsEveryWidth(v, "falls");
	}

	// ---- Prifddinas -----------------------------------------------------------------------------------

	@Test
	public void prifddinas_shardsNotMarks_andARewardTarget()
	{
		Source s = new Source();
		s.bank = new HashMap<>();
		s.bank.put(ItemID.PRIF_CRYSTAL_SHARD, 438L);
		Map<Integer, SkillGoalPlanner.BankingSample> banking = new HashMap<>();
		banking.put(ItemID.PRIF_CRYSTAL_SHARD, new SkillGoalPlanner.BankingSample(40, 2, 3_000_000L));
		s.view = view("prifddinas", new SkillCompanionView.CycleView("prifddinas", 4, 12, 38_000L, true), banking);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID.PRIF_CRYSTAL_SHARD).withTargetQuantity(1_000);
		CurrentSessionView v = render(agility("ACTIVE", item(ItemID.PRIF_CRYSTAL_SHARD, "Crystal shard", 27), null, "'prifddinas':2292000",
			laps("prifddinas", 31, 31 * 74_000L, 65_000, 0), "prifddinas"), s);
		assertEquals("Prifddinas Agility Course", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("4 / 12", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "OBSTACLE"));
		assertEquals("27", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "CRYSTAL SHARDS"));
		assertEquals("42.4", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "SHARDS / HR"));
		assertNull("no marks on Prifddinas", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "MARKS"));
		assertEquals("438", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("562", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_REMAINING_CELL));
		assertTrue(text(v, CurrentSessionView.SC_ETA_LINE_NAME).startsWith("ETA "));
		@SuppressWarnings("unchecked")
		JComboBox<Object> combo = (JComboBox<Object>) named(v, CurrentSessionView.SC_TARGET_RESOURCE_NAME);
		assertEquals("Prifddinas: crystal shards only", 1, combo.getItemCount());
		fitsEveryWidth(v, "prif");
	}

	@Test
	public void rewardTarget_unknownBank_isADash_neverZero()
	{
		Source s = new Source();
		s.view = view("ardougne", null);
		s.goal = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(ItemID.GRACE).withTargetQuantity(260);
		CurrentSessionView v = render(agility("ACTIVE", item(ItemID.GRACE, "Mark of grace", 3), null, "'ardougne':600000",
			laps("ardougne", 9, 9 * 66_000L, 60_000, 0), "ardougne"), s);
		assertEquals("-", cell(v, CurrentSessionView.SC_TARGET_GRID_NAME, CurrentSessionView.SC_IN_BANK_CELL));
		assertEquals("ETA -", text(v, CurrentSessionView.SC_ETA_LINE_NAME));
		fitsEveryWidth(v, "target without ETA");
	}

	// ---- Pyramid / no-reward course ---------------------------------------------------------------------

	@Test
	public void pyramid_obstacleCount_withoutAnInventedTotal_andTops()
	{
		Source s = new Source();
		s.view = view("agility_pyramid", new SkillCompanionView.CycleView("agility_pyramid", 17, null, 78_000L, true));
		CurrentSessionView v = render(agility("ACTIVE", item(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID, "Pyramid top", 8), null,
			"'agility_pyramid':3900000", laps("agility_pyramid", 14, 14 * 474_000L, 441_000, 0), "agility_pyramid"), s);
		assertEquals("17", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "OBSTACLES"));
		assertEquals("1:18", cell(v, CurrentSessionView.SC_CYCLE_GRID_NAME, "LAP TIME"));
		assertEquals("8", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "PYRAMID TOPS"));
		assertEquals("7:54", cell(v, CurrentSessionView.SC_CYCLE_SESSION_GRID_NAME, "AVG LAP"));
		fitsEveryWidth(v, "pyramid");
	}

	@Test
	public void courseWithoutAReward_hasNoRewardsSection()
	{
		Source s = new Source();
		s.view = view("ape_atoll", new SkillCompanionView.CycleView("ape_atoll", 3, 6, 20_000L, true));
		CurrentSessionView v = render(agility("ACTIVE", null, null, "'ape_atoll':900000", laps("ape_atoll", 20, 20 * 44_000L, 41_000, 0),
			"ape_atoll"), s);
		assertEquals("Ape Atoll", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull("no dead REWARDS block", named(v, CurrentSessionView.SC_REWARDS_NAME));
		assertNotNull(named(v, CurrentSessionView.SC_CYCLE_SESSION_NAME));
		fitsEveryWidth(v, "ape atoll");
	}

	@Test
	public void longCourseName_fitsTheNarrowestSidebar()
	{
		Source s = new Source();
		s.view = view("colossal_wyrm_advanced", new SkillCompanionView.CycleView("colossal_wyrm_advanced", 3, 6, 21_000L, true));
		CurrentSessionView v = render(agility("ACTIVE", item(ItemID.VARLAMORE_WYRM_AGILITY_TERMITE, "Termites", 120), null,
			"'colossal_wyrm_advanced':1200000", laps("colossal_wyrm_advanced", 25, 25 * 47_000L, 45_000, 0), "colossal_wyrm_advanced"), s);
		assertEquals("Colossal Wyrm (Advanced)", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("120", cell(v, CurrentSessionView.SC_REWARDS_GRID_NAME, "TERMITES"));
		fitsEveryWidth(v, "long name");
	}

	@Test
	public void noCourseYet_genericAgility_noSections()
	{
		Source s = new Source();
		CurrentSessionView v = render(agility("ACTIVE", null, null, null, null, null), s);
		assertNull(named(v, CurrentSessionView.SC_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.SC_CYCLE_NAME));
		assertNull(named(v, CurrentSessionView.SC_CYCLE_SESSION_NAME));
		assertNull(named(v, CurrentSessionView.SC_REWARDS_NAME));
		fitsEveryWidth(v, "generic agility");
	}

	@Test
	public void agilityAndMiningSideBySide_neverMix()
	{
		Source agi = new Source();
		agi.view = view("ardougne", new SkillCompanionView.CycleView("ardougne", 5, 7, 41_000L, true));
		Source mine = new Source(MiningCompanionDefinition.INSTANCE);
		CurrentSessionView v = render(agility("ACTIVE", null, null, "'ardougne':600000", null, "ardougne"), mine, agi);
		assertEquals("Ardougne Rooftop", text(v, CurrentSessionView.SC_METHOD_NAME));
		assertEquals("Course", ((JLabel) all((Container) named(v, CurrentSessionView.SC_METHOD_NAME).getParent()).stream()
			.filter(c -> c instanceof JLabel && "Course".equals(((JLabel) c).getText())).findFirst().orElse(new JLabel("?"))).getText());
	}

	// ---- History -----------------------------------------------------------------------------------------

	@Test
	public void history_compactRow_andMixedCourses()
	{
		Session done = agility("FINALIZED", item(ItemID.GRACE, "Mark of grace", 9), item(ItemID.GRACE, "Mark of grace", 9),
			"'seers':1440000,'ardougne':2460000", laps("seers", 18, 18 * 45_000L, 43_000, 0) + "," + laps("ardougne", 32, 32 * 66_000L, 61_000, 2),
			"ardougne");
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
		assertEquals("Ardougne Rooftop" + FactLine.SEPARATOR + "50 laps" + FactLine.SEPARATOR + "62,400 XP", stats.text());

		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Laps 50", text(d, HistoryDetailView.SKILL_CYCLES_NAME));
		assertNull("lap times of different courses are never blended", named(d, HistoryDetailView.SKILL_CYCLE_AVG_NAME));
		assertEquals("Falls 2", text(d, HistoryDetailView.SKILL_FAILURES_NAME));
		assertEquals("Agility XP 62,400", text(d, HistoryDetailView.SKILL_XP_NAME));
		assertEquals("Marks of grace 9", text(d, HistoryDetailView.SKILL_REWARD_PREFIX + ItemID.GRACE));
		assertNotNull(named(d, HistoryDetailView.SKILL_METHOD_PREFIX + "seers"));
		assertNotNull(named(d, HistoryDetailView.SKILL_METHOD_PREFIX + "ardougne"));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void history_singleCourse_avgAndBest()
	{
		Session done = agility("FINALIZED", null, null, "'prifddinas':2292000", laps("prifddinas", 31, 31 * 74_000L, 65_000, 0), "prifddinas");
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(done));
		assertEquals("Avg lap", text(d, HistoryDetailView.SKILL_CYCLE_AVG_NAME));
		assertNotNull(named(d, HistoryDetailView.SKILL_CYCLE_BEST_NAME));
		assertNull(named(d, HistoryDetailView.SKILL_FAILURES_NAME));
	}

	@Test
	public void history_oldAgilitySession_rendersAsBefore()
	{
		Session old = GSON.fromJson(("{'sessionId':'old','activityIdentity':{'activityType':'SKILLING','activityKey':'agility',"
			+ "'displayName':'Agility'},'state':'FINALIZED','startedAt':'2026-01-01T10:00:00Z','finalizedAt':'2026-01-01T11:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'AGILITY':50000},'lootDrops':[]}}")
			.replace('\'', '"'), Session.class);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(old));
		assertNull(named(d, HistoryDetailView.SKILL_BLOCK_NAME));
		assertNull(named(d, HistoryDetailView.SKILL_CYCLES_NAME));
	}
}
