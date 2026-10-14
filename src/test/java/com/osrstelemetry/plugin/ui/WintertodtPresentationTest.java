package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.activity.WintertodtState;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import com.osrstelemetry.plugin.wintertodt.WintertodtGoal;
import com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource;
import com.osrstelemetry.plugin.wintertodt.WintertodtPlaystyle;
import com.osrstelemetry.plugin.wintertodt.WintertodtTools;
import com.osrstelemetry.plugin.wintertodt.WintertodtUnique;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * The Wintertodt companion by context: ACTIVE ROUND (CURRENT ROUND,
 * PLAYSTYLE, SESSION), INTERMISSION (BETWEEN ROUNDS, PLAYSTYLE, SESSION) and
 * CAMP (REWARD CART, REWARD HUNT, SESSION), plus the History block -- at
 * every sidebar width, with the hunt's rate line painted centered.
 */
public class WintertodtPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-10-02T16:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	/** 40 min active: 6 subdues, 4,452 points, 13 rewards, 5 complete rounds of 3:30, actions. */
	private static final String STATS =
		",'activityCompletions':{'activityKey':'wintertodt','sessionCompletions':6}"
			+ ",'activityStats':{'activityKey':'wintertodt','currencyGained':13,'currencyGainedByKey':{'reward_cart_rewards':13},"
			+ "'scoredCompletions':6,'scoreTotal':4452,'bestScore':1040,'roundMillis':1260000,'roundsTimed':6,"
			+ "'completeRoundMillis':1050000,'completeRounds':5,'actionCounts':{'roots_fed':180,'kindling_fed':24,'braziers_lit':3}}";

	/** Same session, but an even roots / kindling mix: the focus is ambiguous, so Auto stays unresolved. */
	private static final String MIXED = STATS.replace("'roots_fed':180,'kindling_fed':24", "'roots_fed':100,'kindling_fed':100");

	private static Session wt(String state, String extra)
	{
		return GSON.fromJson(("{'sessionId':'s-wt','activityIdentity':{'activityType':'STRUCTURED_ACTIVITY',"
			+ "'activityKey':'wintertodt','displayName':'Wintertodt'},'state':'" + state + "',"
			+ "'startedAt':'2026-10-02T15:20:00Z','lastActiveAt':'2026-10-02T15:59:00Z','accumulatedActiveDurationMillis':2400000,"
			+ (state.equals("FINALIZED") ? "'finalizedAt':'2026-10-02T16:10:00Z'," : "")
			+ "'aggregates':{'xpGainedBySkill':{'FIREMAKING':310000,'WOODCUTTING':9000,'AGILITY':540},'lootDrops':[]" + extra + "}}")
			.replace('\'', '"'), Session.class);
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	static final class Source implements WintertodtPanelSource
	{
		WintertodtState state;
		/** null = derive from the state (arena when there is one), like the interface default. */
		WintertodtPanelSource.Location location;
		Integer cart;
		Map<Skill, Integer> xp = new HashMap<>();
		Set<WintertodtTools> tools = EnumSet.of(WintertodtTools.AXE, WintertodtTools.TINDERBOX);
		WintertodtGoal goal = WintertodtGoal.DEFAULT;
		WintertodtPlaystyle.Method method = WintertodtPlaystyle.Method.AUTO;
		/** The remembered focus (kept while Auto); null = never one. */
		WintertodtPlaystyle.Focus focus;
		int writes;
		int resolves;

		Source with(WintertodtPlaystyle.Method m, WintertodtPlaystyle.Focus f)
		{
			method = m;
			focus = f;
			return this;
		}

		@Override
		public WintertodtState state()
		{
			return state;
		}

		@Override
		public Location location()
		{
			return location != null ? location : state != null ? Location.ARENA : Location.ELSEWHERE;
		}

		@Override
		public Integer cartRewards()
		{
			return cart;
		}

		Source inCamp(Integer cartRewards)
		{
			state = null;
			location = Location.CAMP;
			cart = cartRewards;
			return this;
		}

		@Override
		public Integer skillXp(Skill skill)
		{
			return xp.get(skill);
		}

		@Override
		public Set<WintertodtTools> tools()
		{
			return tools;
		}

		@Override
		public WintertodtGoal goal()
		{
			return goal;
		}

		@Override
		public void setGoal(WintertodtGoal g)
		{
			goal = g;
			writes++;
		}

		// Mirrors WintertodtPreferences (its own rules are WintertodtPreferencesTest's).
		@Override
		public WintertodtPlaystyle.Method method()
		{
			return method;
		}

		@Override
		public WintertodtPlaystyle.Focus focus()
		{
			return focus == null ? WintertodtPlaystyle.Focus.XP : focus;
		}

		@Override
		public void setMethod(WintertodtPlaystyle.Method m)
		{
			method = m;
			if (m != WintertodtPlaystyle.Method.AUTO && focus == null)
			{
				focus = WintertodtPlaystyle.Focus.XP;
			}
			writes++;
		}

		@Override
		public void setFocus(WintertodtPlaystyle.Focus f)
		{
			focus = f;
			writes++;
		}

		@Override
		public void resolveAuto(WintertodtPlaystyle.Method m, WintertodtPlaystyle.Focus f)
		{
			if (method == WintertodtPlaystyle.Method.AUTO && m != null && m != WintertodtPlaystyle.Method.AUTO && f != null)
			{
				method = m;
				focus = f;
				resolves++;
			}
		}
	}

	private static WintertodtState round(int warmth, int energy, int points, int roots, int kindling, WintertodtState.Action action)
	{
		return new WintertodtState(WintertodtState.Phase.ROUND, warmth, energy, points, null, action, NOW.minusSeconds(60),
			NOW.minusSeconds(60), roots, kindling, true, null);
	}

	private static WintertodtState intermission(Integer countdown)
	{
		return new WintertodtState(WintertodtState.Phase.INTERMISSION, 90, null, null, countdown, null, null, null, 0, 0, false, null);
	}

	private static CurrentSessionView view(Source s)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setWintertodtSource(s);
		v.setNowSupplier(() -> NOW);
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

	// ---- CURRENT ROUND -------------------------------------------------------------------

	private static WintertodtState roundWithActions(int points, Map<String, Integer> actions)
	{
		return new WintertodtState(WintertodtState.Phase.ROUND, 70, 50, points, null, null, NOW.minusSeconds(95),
			NOW.minusSeconds(95), 0, 0, true, null, actions);
	}

	private static Map<String, Integer> counts(Object... kv)
	{
		Map<String, Integer> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((String) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	@Test
	public void currentRound_autoUnresolved_isTheNeutralPresentation()
	{
		Source s = new Source();
		s.state = round(74, 62, 358, 20, 0, WintertodtState.Action.FEEDING_ROOTS);
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("still Auto", WintertodtPlaystyle.Method.AUTO, s.method);
		assertEquals("74%", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_WARMTH_CELL));
		assertEquals("62%", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_ENERGY_CELL));
		assertEquals("358", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_POINTS_CELL));
		assertEquals("0", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_REWARDS_CELL));
		assertNull(cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_ROUND_TIME_CELL));
		assertEquals("Feeding roots", text(v, CurrentSessionView.WT_ACTION_NAME));
		assertEquals("142 pts to qualify", text(v, CurrentSessionView.WT_THRESHOLD_NAME));
		assertEquals("Inventory +200 pts • covers it", text(v, CurrentSessionView.WT_INVENTORY_NAME));
		assertNull("no break panel during a round", named(v, CurrentSessionView.WT_BREAK_GRID_NAME));
		assertNull("no solo line outside Solo", named(v, CurrentSessionView.WT_ROUND_ACTIONS_NAME));
	}

	@Test
	public void xpFocus_roundTimeCell_qualifyThenJustQualified_noNextRewardNag()
	{
		Source s = new Source().with(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.XP);
		s.state = round(74, 62, 358, 20, 0, null);
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("1:00", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_ROUND_TIME_CELL));
		assertNull("XP: no REWARDS cell", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_REWARDS_CELL));
		assertEquals("142 pts to qualify", text(v, CurrentSessionView.WT_THRESHOLD_NAME));
		assertEquals("inventory shown while it helps qualify", "Inventory +200 pts • covers it", text(v, CurrentSessionView.WT_INVENTORY_NAME));

		s.state = round(80, 40, 720, 20, 12, null);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("Qualified", text(v, CurrentSessionView.WT_THRESHOLD_NAME));
		assertNull("qualified: no inventory nag", named(v, CurrentSessionView.WT_INVENTORY_NAME));
		for (Component c : all(v))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				assertFalse(((JLabel) c).getText().contains("Next reward"));
			}
		}
	}

	@Test
	public void pointsFocus_rewardsCell_nextRewardLine_inventoryCoversIt()
	{
		Source s = new Source().with(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.POINTS);
		s.state = round(80, 40, 720, 0, 12, null);
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("2", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_REWARDS_CELL));
		assertNull(cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_ROUND_TIME_CELL));
		assertEquals("Next reward in 280 pts", text(v, CurrentSessionView.WT_THRESHOLD_NAME));
		assertEquals("Inventory +300 pts • covers it", text(v, CurrentSessionView.WT_INVENTORY_NAME));
		s.state = round(80, 40, 720, 0, 0, null);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertNull("nothing carried: no inventory line", named(v, CurrentSessionView.WT_INVENTORY_NAME));
	}

	@Test
	public void solo_addsOneActionContextLine_noZeros_massHasNone()
	{
		Map<String, Integer> actions = counts(com.osrstelemetry.plugin.activity.WintertodtRoundTracker.KINDLING_FED, 42,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.ROOTS_FED, 0,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.REPAIRED, 3,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.HEALED, 2);
		Source s = new Source().with(WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.POINTS);
		s.state = roundWithActions(1200, actions);
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("Round actions: 42 kindling • 3 repairs • 2 heals", text(v, CurrentSessionView.WT_ROUND_ACTIONS_NAME));
		s.method = WintertodtPlaystyle.Method.MASS;
		v.render(snap(wt("ACTIVE", MIXED)));
		assertNull("Mass: no solo line", named(v, CurrentSessionView.WT_ROUND_ACTIONS_NAME));
		s.method = WintertodtPlaystyle.Method.SOLO;
		s.state = roundWithActions(1200, counts(com.osrstelemetry.plugin.activity.WintertodtRoundTracker.REPAIRED, 1));
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("Round actions: 1 repair", text(v, CurrentSessionView.WT_ROUND_ACTIONS_NAME));
		s.state = roundWithActions(1200, null);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertNull("nothing tracked yet: no line", named(v, CurrentSessionView.WT_ROUND_ACTIONS_NAME));
	}

	@Test
	public void unknownHudValues_areDashes_neverGuesses()
	{
		Source s = new Source();
		s.state = new WintertodtState(WintertodtState.Phase.ROUND, null, null, null, null, null, null, NOW, 0, 0, false, null);
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		for (String c : new String[]{CurrentSessionView.WT_WARMTH_CELL, CurrentSessionView.WT_ENERGY_CELL,
			CurrentSessionView.WT_POINTS_CELL, CurrentSessionView.WT_REWARDS_CELL})
		{
			assertEquals(c, "-", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, c));
		}
		assertNull(named(v, CurrentSessionView.WT_THRESHOLD_NAME));
		s.with(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.XP);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("round joined part-way: no round time", "-",
			cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_ROUND_TIME_CELL));
	}

	// ---- BETWEEN ROUNDS -------------------------------------------------------------------

	@Test
	public void betweenRounds_mass_countdownToolsReady_andTheGapTip()
	{
		Source s = new Source().with(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.XP);
		s.state = intermission(43);
		s.xp.put(Skill.AGILITY, Experience.getXpForLevel(70));
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("0:43", cell(v, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_NEXT_GAME_CELL));
		assertEquals("Tools ready", cell(v, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_READINESS_CELL));
		assertEquals("Gap shortcut: 18 Agility XP per crossing", text(v, CurrentSessionView.WT_OPPORTUNITY_NAME));
		assertNull(named(v, CurrentSessionView.WT_ROUND_GRID_NAME));
		s.focus = WintertodtPlaystyle.Focus.POINTS;
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("Points: fletching needs a knife", "Need knife",
			cell(v, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_READINESS_CELL));
	}

	@Test
	public void betweenRounds_solo_hammerReadiness_noGapTip_lowAgilityNeverSuggested()
	{
		Source s = new Source().with(WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.XP);
		s.state = intermission(30);
		s.xp.put(Skill.AGILITY, Experience.getXpForLevel(70));
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("Need hammer", cell(v, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_READINESS_CELL));
		assertNull("Solo: solo readiness, no Gap tip", named(v, CurrentSessionView.WT_OPPORTUNITY_NAME));
		s.focus = WintertodtPlaystyle.Focus.POINTS;
		s.tools = EnumSet.allOf(WintertodtTools.class);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("Tools ready", cell(v, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_READINESS_CELL));

		Source low = new Source();
		low.state = intermission(30);
		low.xp.put(Skill.AGILITY, Experience.getXpForLevel(45));
		CurrentSessionView lv = view(low);
		lv.render(snap(wt("ACTIVE", MIXED)));
		assertNull("below 60 Agility: never suggested", named(lv, CurrentSessionView.WT_OPPORTUNITY_NAME));
		assertEquals("Auto: axe + tinderbox only", "Tools ready",
			cell(lv, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_READINESS_CELL));
	}

	@Test
	public void worldHopAdvice_isGone_everywhere()
	{
		Source s = new Source();
		s.state = intermission(43);
		s.xp.put(Skill.AGILITY, Experience.getXpForLevel(40));
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		for (Component c : all(v))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				assertFalse(((JLabel) c).getText(), ((JLabel) c).getText().toLowerCase().contains("hop"));
			}
		}
		for (WintertodtPlaystyle.Method m : WintertodtPlaystyle.Method.values())
		{
			assertFalse(m.getLabel().toLowerCase().contains("hop"));
		}
	}

	@Test
	public void suspendedSession_showsNoLiveState()
	{
		Source s = new Source();
		s.state = round(74, 62, 358, 0, 0, null);
		CurrentSessionView v = view(s);
		v.render(snap(wt("SUSPENDED", STATS)));
		assertNull(named(v, CurrentSessionView.WT_ROUND_GRID_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_SESSION_GRID_NAME));
	}

	// ---- PLAYSTYLE -----------------------------------------------------------------------------

	private static String selected(Container v, String name)
	{
		Component c = named(v, name);
		return c == null ? null : ((JComboBox<?>) c).getSelectedItem().toString();
	}

	@Test
	public void auto_showsOnlyOneDropdown_noHelperText()
	{
		CurrentSessionView v = view(new Source());
		v.render(snap(wt("ACTIVE", "")));
		assertEquals("Auto", selected(v, CurrentSessionView.WT_METHOD_NAME));
		assertNull("Auto: no Focus selector", named(v, CurrentSessionView.WT_FOCUS_NAME));
		Container row = (Container) named(v, CurrentSessionView.WT_PLAYSTYLE_NAME);
		assertEquals("one dropdown", 1, row.getComponentCount());
		List<String> options = new ArrayList<>();
		JComboBox<?> method = (JComboBox<?>) named(v, CurrentSessionView.WT_METHOD_NAME);
		for (int i = 0; i < method.getItemCount(); i++)
		{
			options.add(method.getItemAt(i).toString());
		}
		assertEquals(java.util.Arrays.asList("Auto", "Mass", "Solo"), options);
	}

	@Test
	public void manualChanges_lastFocusCarries_massSoloKeepsFocus_focusKeepsMethod()
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		v.selectWintertodtMethod(WintertodtPlaystyle.Method.MASS);
		assertEquals("Mass", selected(v, CurrentSessionView.WT_METHOD_NAME));
		assertEquals("first time: XP", "XP", selected(v, CurrentSessionView.WT_FOCUS_NAME));
		v.selectWintertodtFocus(WintertodtPlaystyle.Focus.POINTS);
		assertEquals("focus keeps the method", "Mass", selected(v, CurrentSessionView.WT_METHOD_NAME));
		assertEquals("Points", selected(v, CurrentSessionView.WT_FOCUS_NAME));
		v.selectWintertodtMethod(WintertodtPlaystyle.Method.SOLO);
		assertEquals("Mass -> Solo keeps the focus", "Points", selected(v, CurrentSessionView.WT_FOCUS_NAME));
		v.selectWintertodtMethod(WintertodtPlaystyle.Method.AUTO);
		assertNull("Auto hides Focus at once", named(v, CurrentSessionView.WT_FOCUS_NAME));
		assertEquals("kept internally", WintertodtPlaystyle.Focus.POINTS, s.focus);
		v.selectWintertodtMethod(WintertodtPlaystyle.Method.MASS);
		assertEquals("Auto -> Mass uses lastFocus", "Points", selected(v, CurrentSessionView.WT_FOCUS_NAME));
	}

	@Test
	public void auto_resolvesIntoAnExplicitPair_onlyWithBothDimensions_andNeverFlips()
	{
		Source s = new Source();
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", MIXED)));
		assertEquals("method known, focus ambiguous: Auto", WintertodtPlaystyle.Method.AUTO, s.method);
		String oneRepair = MIXED.replace("'braziers_lit':3", "'braziers_lit':3,'braziers_repaired':1,'pyromancers_healed':1");
		v.render(snap(wt("ACTIVE", oneRepair)));
		assertEquals("one repair / heal forces nothing", WintertodtPlaystyle.Method.AUTO, s.method);

		v.render(snap(wt("ACTIVE", STATS)));
		assertEquals(WintertodtPlaystyle.Method.MASS, s.method);
		assertEquals(WintertodtPlaystyle.Focus.XP, s.focus);
		assertEquals("Mass", selected(v, CurrentSessionView.WT_METHOD_NAME));
		assertEquals("XP", selected(v, CurrentSessionView.WT_FOCUS_NAME));

		String soloPoints = STATS.replace("'completeRoundMillis':1050000", "'completeRoundMillis':4500000")
			.replace("'roots_fed':180,'kindling_fed':24", "'roots_fed':20,'kindling_fed':300");
		v.render(snap(wt("ACTIVE", soloPoints)));
		assertEquals("resolved: no flip on later evidence", WintertodtPlaystyle.Method.MASS, s.method);
		assertEquals(WintertodtPlaystyle.Focus.XP, s.focus);
		assertEquals(1, s.resolves);

		v.selectWintertodtMethod(WintertodtPlaystyle.Method.AUTO);
		v.render(snap(wt("ACTIVE", soloPoints)));
		assertEquals("Auto again re-enables inference", WintertodtPlaystyle.Method.SOLO, s.method);
		assertEquals(WintertodtPlaystyle.Focus.POINTS, s.focus);
		assertEquals(2, s.resolves);
	}

	// ---- CONTEXT ------------------------------------------------------------------------------------

	@Test
	public void context_followsLocationAndArenaPhase_campNeedsALiveSession()
	{
		WintertodtState r = round(80, 60, 300, 0, 0, null);
		WintertodtState i = intermission(30);
		WintertodtPanelSource.Location arena = WintertodtPanelSource.Location.ARENA;
		WintertodtPanelSource.Location camp = WintertodtPanelSource.Location.CAMP;
		WintertodtPanelSource.Location away = WintertodtPanelSource.Location.ELSEWHERE;
		com.osrstelemetry.plugin.session.SessionState active = com.osrstelemetry.plugin.session.SessionState.ACTIVE;
		com.osrstelemetry.plugin.session.SessionState suspended = com.osrstelemetry.plugin.session.SessionState.SUSPENDED;
		com.osrstelemetry.plugin.session.SessionState done = com.osrstelemetry.plugin.session.SessionState.FINALIZED;
		assertEquals(CurrentSessionView.WintertodtContext.ROUND, CurrentSessionView.wintertodtContext(active, arena, r));
		assertEquals(CurrentSessionView.WintertodtContext.INTERMISSION, CurrentSessionView.wintertodtContext(active, arena, i));
		assertEquals(CurrentSessionView.WintertodtContext.CAMP, CurrentSessionView.wintertodtContext(active, camp, null));
		assertEquals("a paused session inside its resume window still manages its rewards",
			CurrentSessionView.WintertodtContext.CAMP, CurrentSessionView.wintertodtContext(suspended, camp, null));
		assertEquals(CurrentSessionView.WintertodtContext.NONE, CurrentSessionView.wintertodtContext(done, camp, null));
		assertEquals(CurrentSessionView.WintertodtContext.NONE, CurrentSessionView.wintertodtContext(null, camp, null));
		assertEquals(CurrentSessionView.WintertodtContext.NONE, CurrentSessionView.wintertodtContext(active, away, null));
		assertEquals("no live round while paused", CurrentSessionView.WintertodtContext.NONE,
			CurrentSessionView.wintertodtContext(suspended, arena, r));
	}

	@Test
	public void activeRound_isTheGameplayCompanion_noRewardHunt()
	{
		Source s = new Source().with(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.POINTS);
		s.state = round(74, 62, 600, 0, 0, null);
		s.cart = 209;
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", STATS)));
		assertNotNull(named(v, CurrentSessionView.WT_ROUND_GRID_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_PLAYSTYLE_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_SESSION_GRID_NAME));
		assertNull(named(v, CurrentSessionView.WT_HUNT_NAME));
		assertNull(named(v, CurrentSessionView.WT_HUNT_TARGET_NAME));
		assertNull(named(v, CurrentSessionView.WT_CART_NAME));
		assertNull("no 50% / 90% milestones anywhere", cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, "50% CHANCE"));
	}

	@Test
	public void intermission_isBetweenRounds_noRewardHunt()
	{
		Source s = new Source().with(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.XP);
		s.state = intermission(45);
		s.cart = 9;
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", STATS)));
		assertEquals("0:45", cell(v, CurrentSessionView.WT_BREAK_GRID_NAME, CurrentSessionView.WT_NEXT_GAME_CELL));
		assertNotNull(named(v, CurrentSessionView.WT_PLAYSTYLE_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_SESSION_GRID_NAME));
		assertNull(named(v, CurrentSessionView.WT_HUNT_NAME));
		assertNull(named(v, CurrentSessionView.WT_CART_NAME));
	}

	// ---- CAMP / REWARD CART -------------------------------------------------------------------------

	private static String stats(int earned)
	{
		return STATS.replace("'currencyGained':13,'currencyGainedByKey':{'reward_cart_rewards':13}",
			earned == 0 ? "'currencyGained':0" : "'currencyGained':" + earned + ",'currencyGainedByKey':{'reward_cart_rewards':" + earned + "}");
	}

	private static CurrentSessionView camp(Source s, int earned)
	{
		CurrentSessionView v = view(s);
		v.render(snap(wt("ACTIVE", stats(earned))));
		return v;
	}

	@Test
	public void camp_isTheRewardCartCompanion_noPlaystyle_noRound()
	{
		CurrentSessionView v = camp(new Source().inCamp(9), 9);
		assertNotNull(named(v, CurrentSessionView.WT_CART_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_HUNT_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_HUNT_TARGET_NAME));
		assertNotNull(named(v, CurrentSessionView.WT_SESSION_GRID_NAME));
		assertNull("no playstyle controls in camp", named(v, CurrentSessionView.WT_PLAYSTYLE_NAME));
		assertNull(named(v, CurrentSessionView.WT_METHOD_NAME));
		assertNull(named(v, CurrentSessionView.WT_ROUND_GRID_NAME));
		assertNull(named(v, CurrentSessionView.WT_BREAK_GRID_NAME));
		assertEquals("Phoenix", selected(v, CurrentSessionView.WT_HUNT_TARGET_NAME));
		assertEquals("1 / 5,000 per reward", text(v, CurrentSessionView.WT_HUNT_RATE_NAME));
	}

	@Test
	public void camp_cartBalance_andSessionEarned_neverConflated()
	{
		int[][] cases = {{0, 0}, {9, 9}, {40, 9}, {209, 9}, {8, 9}, {0, 9}};
		for (int[] c : cases)
		{
			CurrentSessionView v = camp(new Source().inCamp(c[0]), c[1]);
			String where = "cart " + c[0] + " / session " + c[1];
			assertEquals(where, String.format(java.util.Locale.ROOT, "%,d", c[0]),
				cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_CART_REWARDS_CELL));
			assertEquals(where, c[1] == 0 ? "0" : "+" + c[1],
				cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_SESSION_EARNED_CELL));
			assertEquals(where, com.osrstelemetry.plugin.wintertodt.WintertodtRewardHunt.percent(1 - Math.pow(4999.0 / 5000, c[0])),
				cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL));
			assertEquals(where, com.osrstelemetry.plugin.wintertodt.WintertodtRewardHunt.percent(1 - Math.pow(4999.0 / 5000, c[1])),
				cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_SESSION_CHANCE_CELL));
		}
		CurrentSessionView v = camp(new Source().inCamp(209), 9);
		assertEquals("0.18%", cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_SESSION_CHANCE_CELL));
		assertEquals("4.09%", cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL));
	}

	@Test
	public void camp_searchingTheCart_dropsTheCartOnly()
	{
		Source s = new Source().inCamp(9);
		CurrentSessionView v = camp(s, 9);
		String sessionChance = cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_SESSION_CHANCE_CELL);
		String cartChance = cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL);
		s.cart = 8;
		v.render(snap(wt("ACTIVE", stats(9))));
		assertEquals("8", cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_CART_REWARDS_CELL));
		assertEquals("+9", cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_SESSION_EARNED_CELL));
		assertEquals(sessionChance, cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_SESSION_CHANCE_CELL));
		assertTrue("cart chance drops", !cartChance.equals(cell(v, CurrentSessionView.WT_HUNT_GRID_NAME,
			CurrentSessionView.WT_CHANCE_IN_CART_CELL)));
		s.cart = 0;
		v.render(snap(wt("ACTIVE", stats(9))));
		assertEquals("0", cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_CART_REWARDS_CELL));
		assertEquals("0%", cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL));
		assertEquals("+9", cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_SESSION_EARNED_CELL));
		assertEquals(sessionChance, cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_SESSION_CHANCE_CELL));
	}

	@Test
	public void camp_unknownCart_showsADash_neverAnInventedChance()
	{
		CurrentSessionView v = camp(new Source().inCamp(null), 9);
		assertEquals("-", cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_CART_REWARDS_CELL));
		assertEquals("-", cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL));
		assertEquals("+9", cell(v, CurrentSessionView.WT_CART_GRID_NAME, CurrentSessionView.WT_SESSION_EARNED_CELL));
		assertEquals("0.18%", cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_SESSION_CHANCE_CELL));
	}

	@Test
	public void camp_huntTarget_isPlanningState_andEachTargetUsesItsOwnRate()
	{
		Source s = new Source().inCamp(42);
		CurrentSessionView v = camp(s, 9);
		v.selectWintertodtGoal(s.goal.withUnique(WintertodtUnique.TOME_OF_FIRE));
		assertEquals(1, s.writes);
		assertEquals(WintertodtUnique.TOME_OF_FIRE, s.goal.getUnique());
		assertEquals("Tome of fire", selected(v, CurrentSessionView.WT_HUNT_TARGET_NAME));
		assertEquals(com.osrstelemetry.plugin.wintertodt.WintertodtRewardHunt.percent(
				com.osrstelemetry.plugin.wintertodt.WintertodtRewardHunt.chance(WintertodtUnique.TOME_OF_FIRE, 42)),
			cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL));
		s.goal = s.goal.withUnique(WintertodtUnique.PYROMANCER_OUTFIT);
		v.render(snap(wt("ACTIVE", stats(9))));
		assertEquals(com.osrstelemetry.plugin.wintertodt.WintertodtRewardHunt.percent(
				com.osrstelemetry.plugin.wintertodt.WintertodtRewardHunt.chance(WintertodtUnique.PYROMANCER_OUTFIT, 42)),
			cell(v, CurrentSessionView.WT_HUNT_GRID_NAME, CurrentSessionView.WT_CHANCE_IN_CART_CELL));
		assertTrue(text(v, CurrentSessionView.WT_HUNT_RATE_NAME).startsWith("4 pieces"));
	}

	@Test
	public void camp_everyTarget_fitsEveryWidth_rateLineCentered()
	{
		for (WintertodtUnique u : WintertodtUnique.values())
		{
			for (int width : WIDTHS)
			{
				String where = u + " @" + width;
				Source s = new Source().inCamp(8000);
				s.goal = s.goal.withUnique(u);
				CurrentSessionView v = camp(s, 1234);
				liveLayout(v, width);
				List<String> problems = SidebarClippingRegressionTest.audit(v, width);
				assertTrue(where + ": " + problems, problems.isEmpty());
				Component combo = named(v, CurrentSessionView.WT_HUNT_TARGET_NAME);
				assertTrue(where + ": dropdown fits", combo.getWidth() > 0 && combo.getWidth() <= width);
				Container section = (Container) named(v, CurrentSessionView.WT_HUNT_NAME);
				Component grid = named(v, CurrentSessionView.WT_HUNT_GRID_NAME);
				JLabel rate = (JLabel) named(v, CurrentSessionView.WT_HUNT_RATE_NAME);
				double gridCenter = javax.swing.SwingUtilities.convertPoint(grid, 0, 0, section).x + grid.getWidth() / 2.0;
				if (rate.getHeight() <= rate.getFont().getSize() * 2)
				{
					assertEquals(where + ": rate drawn centered", gridCenter, paintedCenterX(rate, section), 2.0);
				}
				assertNull(where, named(v, CurrentSessionView.WT_PLAYSTYLE_NAME));
			}
		}
	}

	// ---- SESSION ----------------------------------------------------------------------------------

	@Test
	public void sessionGrid_subduesPointsAvgRewardsRoundRate()
	{
		CurrentSessionView v = view(new Source());
		v.render(snap(wt("ACTIVE", STATS)));
		String g = CurrentSessionView.WT_SESSION_GRID_NAME;
		assertEquals("6", cell(v, g, CurrentSessionView.WT_SUBDUES_CELL));
		assertEquals("4,452", cell(v, g, CurrentSessionView.WT_TOTAL_POINTS_CELL));
		assertEquals("742", cell(v, g, CurrentSessionView.WT_AVG_POINTS_CELL));
		assertEquals("+13", cell(v, g, CurrentSessionView.WT_SESSION_REWARDS_CELL));
		assertEquals("3:30", cell(v, g, CurrentSessionView.WT_AVG_ROUND_CELL));
		// 13 rewards over 41 active minutes (40 accumulated + the live minute)
		assertEquals("19.0", cell(v, g, CurrentSessionView.WT_REWARDS_RATE_CELL));
		assertNull("no duplicate header line", named(v, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	@Test
	public void rewardRate_usesTheActualAwards()
	{
		String actual = STATS.replace("'currencyGained':13,'currencyGainedByKey':{'reward_cart_rewards':13}",
			"'currencyGained':9,'currencyGainedByKey':{'reward_cart_rewards':9}");
		Source s = new Source();
		CurrentSessionView v = view(s);
		CurrentSessionSnapshot snap = snap(wt("ACTIVE", actual));
		v.render(snap);
		assertEquals("+9", cell(v, CurrentSessionView.WT_SESSION_GRID_NAME, CurrentSessionView.WT_SESSION_REWARDS_CELL));
		assertEquals(String.format(java.util.Locale.ROOT, "%.1f", 9 * 3_600_000d / snap.getActiveDurationMillis()),
			cell(v, CurrentSessionView.WT_SESSION_GRID_NAME, CurrentSessionView.WT_REWARDS_RATE_CELL));
	}

	// ---- widths / centering --------------------------------------------------------------------

	private static void liveLayout(Container root, int width)
	{
		root.setSize(width, 6000);
		for (int i = 0; i < 5; i++)
		{
			layoutTree(root);
		}
	}

	private static void layoutTree(Container c)
	{
		c.doLayout();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layoutTree((Container) k);
			}
		}
	}

	private static double paintedCenterX(JLabel label, Container in)
	{
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, label.getWidth()), Math.max(1, label.getHeight()),
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		label.paint(g);
		g.dispose();
		int min = Integer.MAX_VALUE;
		int max = -1;
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					min = Math.min(min, x);
					max = Math.max(max, x);
				}
			}
		}
		assertTrue("painted", max >= 0);
		return javax.swing.SwingUtilities.convertPoint(label, 0, 0, in).x + (min + max + 1) / 2.0;
	}

	/** Every playstyle shape: Auto unresolved (MIXED evidence) and the four explicit pairs. */
	private static final WintertodtPlaystyle.Method[] METHODS = {WintertodtPlaystyle.Method.AUTO,
		WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Method.SOLO};
	private static final WintertodtPlaystyle.Focus[] FOCUSES = {null,
		WintertodtPlaystyle.Focus.XP, WintertodtPlaystyle.Focus.POINTS, WintertodtPlaystyle.Focus.XP, WintertodtPlaystyle.Focus.POINTS};

	@Test
	public void everyArenaStateAndPlaystyle_fitsEveryWidth_withNoRewardHunt()
	{
		Map<String, Integer> soloCounts = counts(com.osrstelemetry.plugin.activity.WintertodtRoundTracker.KINDLING_FED, 142,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.ROOTS_FED, 31,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.LIT, 4,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.REPAIRED, 13,
			com.osrstelemetry.plugin.activity.WintertodtRoundTracker.HEALED, 12);
		WintertodtState[] states = {new WintertodtState(WintertodtState.Phase.ROUND, 74, 62, 358, null, WintertodtState.Action.FLETCHING,
			NOW.minusSeconds(754), NOW.minusSeconds(754), 20, 3, true, null, soloCounts), intermission(43)};
		for (int p = 0; p < METHODS.length; p++)
		{
			for (WintertodtState state : states)
			{
				{
					for (int width : WIDTHS)
					{
						String where = METHODS[p] + "/" + FOCUSES[p] + " " + state.getPhase() + " @" + width;
						Source s = new Source().with(METHODS[p], FOCUSES[p]);
						s.state = state;
						s.cart = 209;
						s.xp.put(Skill.FIREMAKING, Experience.getXpForLevel(80));
						s.xp.put(Skill.AGILITY, Experience.getXpForLevel(70));
						CurrentSessionView v = view(s);
						v.render(snap(wt("ACTIVE", MIXED)));
						assertEquals(where + ": Auto stays unresolved here", METHODS[p], s.method);
						liveLayout(v, width);
						assertNull(where + ": no Reward Hunt in the arena", named(v, CurrentSessionView.WT_HUNT_NAME));
						assertNull(where + ": no Reward Cart in the arena", named(v, CurrentSessionView.WT_CART_NAME));
						assertNull(where + ": no hunt chances in the arena", named(v, CurrentSessionView.WT_HUNT_GRID_NAME));
						assertNotNull(where + ": session", named(v, CurrentSessionView.WT_SESSION_GRID_NAME));
						assertEquals(where + ": the right hierarchy", state.getPhase() == WintertodtState.Phase.ROUND,
							named(v, CurrentSessionView.WT_ROUND_GRID_NAME) != null);
						assertEquals(where, state.getPhase() == WintertodtState.Phase.INTERMISSION,
							named(v, CurrentSessionView.WT_BREAK_GRID_NAME) != null);
						List<String> problems = SidebarClippingRegressionTest.audit(v, width);
						assertTrue(where + ": " + problems, problems.isEmpty());
						for (Component c : all((Container) named(v, CurrentSessionView.WT_BLOCK_NAME)))
						{
							if (c instanceof JComboBox)
							{
								assertTrue(where + ": dropdown fits", c.getWidth() > 0 && c.getWidth() <= width);
							}
						}
						Container row = (Container) named(v, CurrentSessionView.WT_PLAYSTYLE_NAME);
						Component method = named(v, CurrentSessionView.WT_METHOD_NAME);
						Component focus = named(v, CurrentSessionView.WT_FOCUS_NAME);
						if (METHODS[p] == WintertodtPlaystyle.Method.AUTO)
						{
							assertNull(where, focus);
							assertEquals(where + ": the one dropdown spans the row", row.getWidth(), method.getWidth(), 1);
						}
						else
						{
							assertNotNull(where + ": Focus shown", focus);
							assertTrue(where + ": second dropdown visible", focus.isVisible() && focus.getWidth() >= 40);
							assertTrue(where + ": second dropdown inside the row", focus.getX() + focus.getWidth() <= row.getWidth());
							assertTrue(where + ": side by side", focus.getX() >= method.getX() + method.getWidth());
							assertEquals(where + ": balanced", method.getWidth(), focus.getWidth(), 1);
						}
						boolean xp = FOCUSES[p] == WintertodtPlaystyle.Focus.XP;
						if (state.getPhase() == WintertodtState.Phase.ROUND)
						{
							assertEquals(where, xp ? "12:34" : null, cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_ROUND_TIME_CELL));
							assertEquals(where, xp ? null : "0", cell(v, CurrentSessionView.WT_ROUND_GRID_NAME, CurrentSessionView.WT_REWARDS_CELL));
							assertEquals(where, METHODS[p] == WintertodtPlaystyle.Method.SOLO,
								named(v, CurrentSessionView.WT_ROUND_ACTIONS_NAME) != null);
						}
					}
				}
			}
		}
	}

	// ---- HISTORY -------------------------------------------------------------------------------------

	@Test
	public void history_outcomesOnly()
	{
		HistoryDetailModel model = HistoryDetailModel.from(wt("FINALIZED", STATS));
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(model);
		assertEquals("Subdues 6", text(d, HistoryDetailView.SESSION_GAMES_NAME));
		assertEquals("Avg 742 pts", text(d, HistoryDetailView.SESSION_AVG_NAME));
		assertEquals("Points 4,452", text(d, HistoryDetailView.SESSION_CURRENCY_PREFIX + "points"));
		assertEquals("Rewards +13", text(d, HistoryDetailView.SESSION_CURRENCY_PREFIX + "rewards"));
		assertEquals("Avg round 3:30", text(d, HistoryDetailView.WT_ROUND_ROW_NAME));
		assertEquals("19.5/hr", text(d, HistoryDetailView.SESSION_RATE_NAME));
		assertEquals("Roots fed 180   •   Kindling fed 24   •   Lit 3", text(d, HistoryDetailView.WT_ACTIONS_NAME));
		assertEquals("Downtime: Agility +540 XP", text(d, HistoryDetailView.WT_DOWNTIME_NAME));
		assertNull("no reward hunt in History", named(d, CurrentSessionView.WT_HUNT_NAME));
		assertNull("no cart balance in History", named(d, CurrentSessionView.WT_CART_NAME));
		assertNull("an older session has no playstyle row", named(d, HistoryDetailView.WT_PLAYSTYLE_ROW_NAME));
		assertNull("no live round in History", named(d, CurrentSessionView.WT_ROUND_GRID_NAME));
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(d, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
		assertFalse(text(d, HistoryDetailView.SESSION_GAMES_NAME).isEmpty());
	}

	@Test
	public void history_showsThePersistedPlaystyleLabel()
	{
		for (String label : new String[]{"Mass • XP", "Mass • Points", "Solo • XP", "Solo • Points", "Auto"})
		{
			String stats = STATS.replace("'braziers_lit':3}}", "'braziers_lit':3},'playstyle':'" + label + "'}");
			HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
			d.render(HistoryDetailModel.from(wt("FINALIZED", stats)));
			assertEquals("Playstyle " + label, text(d, HistoryDetailView.WT_PLAYSTYLE_ROW_NAME));
			for (int width : WIDTHS)
			{
				List<String> problems = SidebarClippingRegressionTest.audit(d, width);
				assertTrue(label + " @" + width + ": " + problems, problems.isEmpty());
			}
		}
	}
}
