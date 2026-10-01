package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.activity.TemporossGameState;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * Tempoross Current Session: the activity owns the title; CURRENT GAME
 * mirrors the game's own HUD (Energy / Essence / Storm as thin bars, and
 * Points) only while a game is actually up and the session is ACTIVE;
 * SESSION carries Games • Games/hr exactly once; XP and Loot follow.
 * Nothing is guessed, and it fits the narrowest sidebar.
 */
public class TemporossPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-09-20T10:50:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private static Session session(String type, String key, String name, String state, String xpJson, String extra)
	{
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'" + state + "','startedAt':'2026-09-20T10:00:00Z',"
			+ "'lastActiveAt':'2026-09-20T10:42:00Z','accumulatedActiveDurationMillis':2520000,"
			+ "'aggregates':{'xpGainedBySkill':" + xpJson + ",'lootDrops':[]" + extra + "}}").replace('\'', '"'), Session.class);
	}

	private static Session tempoross(String state)
	{
		return session("STRUCTURED_ACTIVITY", "tempoross", "Tempoross", state, "{'FISHING':42000,'COOKING':3100}",
			",'activityCompletions':{'activityKey':'tempoross','sessionCompletions':6,'latestAuthoritativeValue':410}");
	}

	/** Games / permits earned / trusted-score population, as the session pipeline records them. */
	private static Session temporossWithStats(int games, long permits, int scored, long scoreTotal, Integer best)
	{
		return session("STRUCTURED_ACTIVITY", "tempoross", "Tempoross", "ACTIVE", "{'FISHING':42000}",
			",'activityCompletions':{'activityKey':'tempoross','sessionCompletions':" + games + "}"
				+ ",'activityStats':{'activityKey':'tempoross','currencyGained':" + permits + ",'scoredCompletions':" + scored
				+ ",'scoreTotal':" + scoreTotal + (best == null ? "" : ",'bestScore':" + best) + "}");
	}

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	private static CurrentSessionView view(TemporossGameState game)
	{
		CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
		view.setTemporossGameSupplier(() -> game);
		return view;
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

	private static int countNamed(Container root, String name)
	{
		int n = 0;
		for (Component c : all(root))
		{
			if (name.equals(c.getName()))
			{
				n++;
			}
		}
		return n;
	}

	private static List<String> bars(Container root)
	{
		List<String> out = new ArrayList<>();
		for (Component c : all(root))
		{
			if (c.getName() != null && c.getName().startsWith(CurrentSessionView.TEMPOROSS_BAR_PREFIX))
			{
				out.add(c.getName().substring(CurrentSessionView.TEMPOROSS_BAR_PREFIX.length()));
			}
		}
		return out;
	}

	private static int indexOf(Container root, String name)
	{
		List<Component> list = all(root);
		for (int i = 0; i < list.size(); i++)
		{
			if (name.equals(list.get(i).getName()))
			{
				return i;
			}
		}
		return -1;
	}

	private static void layout(Container c)
	{
		c.doLayout();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layout((Container) k);
			}
		}
	}

	private static final TemporossGameState FULL = TemporossGameState.of(87, 64, 23, 1_450);

	// UI 1: the activity owns the title.
	@Test
	public void title_isTempoross_notTheSkill()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(tempoross("ACTIVE")));
		assertEquals("Tempoross", ((JLabel) named(v, CurrentSessionView.HEADLINE_NAME)).getText());
		assertNull("no duplicate activity line", named(v, CurrentSessionView.ACTIVITY_LINE_NAME));
	}

	// UI 2-5: current game block: three bars + points, from the game's own values.
	@Test
	public void currentGame_showsTheThreeBarsAndPoints()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(tempoross("ACTIVE")));
		assertNotNull(named(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME));
		assertEquals(java.util.Arrays.asList("Energy:87", "Essence:64", "Storm:23"), bars(v));
		assertEquals("1,450", ((JLabel) named(v, CurrentSessionView.TEMPOROSS_POINTS_NAME)).getText());
		CurrentSessionView.ThinBar energy = (CurrentSessionView.ThinBar) named(v, CurrentSessionView.TEMPOROSS_BAR_PREFIX + "Energy:87");
		assertEquals(87, energy.getPercent());
		assertEquals("thin", 4, energy.getPreferredSize().height);
	}

	// UI 6: games once, inside the block -- never also in the header.
	@Test
	public void sessionGames_appearExactlyOnce_insideTheBlock()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(tempoross("ACTIVE")));
		assertEquals(1, countNamed(v, CurrentSessionView.COMPLETION_METRIC_NAME));
		String text = ((JLabel) named(v, CurrentSessionView.COMPLETION_METRIC_NAME)).getText();
		assertEquals("Games: 6", text);
		assertEquals(1, countNamed(v, CurrentSessionView.COMPLETION_RATE_NAME));
		assertTrue(((JLabel) named(v, CurrentSessionView.COMPLETION_RATE_NAME)).getText().startsWith("Games/hr: "));
		assertTrue("games line sits after the current game",
			indexOf(v, CurrentSessionView.COMPLETION_METRIC_NAME) > indexOf(v, CurrentSessionView.TEMPOROSS_POINTS_NAME));
	}

	// UI 7: no game up (cove, between games, not read yet): no bars, games stay.
	@Test
	public void noGame_omitsTheCurrentGame_neverGuessed_butGamesStay()
	{
		CurrentSessionView v = view(null);
		v.render(snap(tempoross("ACTIVE")));
		assertTrue(bars(v).isEmpty());
		assertNull(named(v, CurrentSessionView.TEMPOROSS_POINTS_NAME));
		assertEquals(1, countNamed(v, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	// UI 8: partial HUD read -- only what the game actually showed.
	@Test
	public void partialRead_showsOnlyKnownValues()
	{
		CurrentSessionView v = view(TemporossGameState.of(null, 40, null, 0));
		v.render(snap(tempoross("ACTIVE")));
		assertEquals(java.util.Collections.singletonList("Essence:40"), bars(v));
		assertEquals("0", ((JLabel) named(v, CurrentSessionView.TEMPOROSS_POINTS_NAME)).getText());
	}

	// UI 9: a SUSPENDED Tempoross session never shows live game bars.
	@Test
	public void suspendedSession_showsNoLiveGame()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(tempoross("SUSPENDED")));
		assertTrue(bars(v).isEmpty());
		assertNull(named(v, CurrentSessionView.TEMPOROSS_POINTS_NAME));
	}

	// UI 10: other activities never get the Tempoross block, nor read it.
	@Test
	public void otherActivities_getNoTemporossBlock_andDoNotReadTheSupplier()
	{
		AtomicReference<Integer> reads = new AtomicReference<>(0);
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setTemporossGameSupplier(() ->
		{
			reads.set(reads.get() + 1);
			return FULL;
		});
		v.render(snap(session("STRUCTURED_ACTIVITY", "guardians_of_the_rift", "Guardians of the Rift", "ACTIVE", "{'RUNECRAFT':52000}",
			",'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':8,'latestAuthoritativeValue':1208}")));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME));
		assertEquals("GotR keeps its own SESSION grid", 1, countNamed(v, CurrentSessionView.GOTR_SESSION_GRID_NAME));
		v.render(snap(session("SKILLING", "fishing", "Fishing", "ACTIVE", "{'FISHING':5000}", "")));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME));
		assertEquals(Integer.valueOf(0), reads.get());
	}

	// UI 11: Barrows is untouched by the Tempoross block.
	@Test
	public void barrows_stillGetsItsOwnBlockOnly()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(session("STRUCTURED_ACTIVITY", "barrows", "Barrows", "ACTIVE", "{'HITPOINTS':12000}",
			",'activityCompletions':{'activityKey':'barrows','sessionCompletions':7,'latestAuthoritativeValue':312}")));
		assertNotNull(named(v, CurrentSessionView.BARROWS_BLOCK_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME));
		assertEquals(1, countNamed(v, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	// UI 12: the block sits before the XP section.
	@Test
	public void block_comesBeforeXp()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(tempoross("ACTIVE")));
		int block = indexOf(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME);
		assertTrue(block >= 0);
		int firstXp = -1;
		List<Component> list = all(v);
		for (int i = 0; i < list.size(); i++)
		{
			if (list.get(i) instanceof JLabel && "Fishing".equals(((JLabel) list.get(i)).getText()))
			{
				firstXp = i;
				break;
			}
		}
		assertTrue("XP after the block (" + block + " vs " + firstXp + ")", firstXp < 0 || firstXp > block);
	}

	// UI 13-14: narrow widths -- bars span the width, nothing overflows or clips.
	@Test
	public void narrowWidths_barsFit_andNothingOverflows()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(tempoross("ACTIVE")));
		Component block = named(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME);
		for (int width : WIDTHS)
		{
			v.setSize(width, 4000);
			for (int i = 0; i < 3; i++)
			{
				layout(v);
			}
			for (Component c : all((Container) block))
			{
				Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v);
				assertTrue(width + "px: " + c.getName() + " overflows (" + (r.x + r.width) + ")", r.x >= 0 && r.x + r.width <= width + 1);
				if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
				{
					assertTrue(width + "px: '" + ((JLabel) c).getText() + "' clipped", c.getWidth() >= c.getPreferredSize().width);
				}
			}
			for (String bar : bars(v))
			{
				Component b = named(v, CurrentSessionView.TEMPOROSS_BAR_PREFIX + bar);
				assertEquals(width + "px bar height", 4, b.getHeight());
				assertTrue(width + "px bar spans the block (" + b.getWidth() + ")", b.getWidth() >= block.getWidth() - 1 && b.getWidth() > 0);
			}
		}
	}

	// UI 15: re-render (the coalesced refresh) reflects a changed game, with no stale bars.
	@Test
	public void rerender_reflectsTheNewGameState_andClearsWhenTheGameEnds()
	{
		AtomicReference<TemporossGameState> game = new AtomicReference<>(FULL);
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setTemporossGameSupplier(game::get);
		v.render(snap(tempoross("ACTIVE")));
		game.set(TemporossGameState.of(0, 90, 55, 2_010));
		v.render(snap(tempoross("ACTIVE")));
		assertEquals(java.util.Arrays.asList("Energy:0", "Essence:90", "Storm:55"), bars(v));
		assertEquals("2,010", ((JLabel) named(v, CurrentSessionView.TEMPOROSS_POINTS_NAME)).getText());
		game.set(null);
		v.render(snap(tempoross("ACTIVE")));
		assertTrue(bars(v).isEmpty());
		assertFalse(countNamed(v, CurrentSessionView.COMPLETION_METRIC_NAME) != 1);
	}

	// ------------------------------------------------------------------ SESSION stats rows

	// UI 7-11 + completed-game state.
	@Test
	public void session_showsGamesRatePermitsAvgBest()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(temporossWithStats(3, 11, 3, 14_760, 5_670)));
		assertEquals("Games: 3", text(v, CurrentSessionView.COMPLETION_METRIC_NAME));
		assertTrue(text(v, CurrentSessionView.COMPLETION_RATE_NAME).startsWith("Games/hr: "));
		assertEquals("Permits", text(v, CurrentSessionView.TEMPOROSS_PERMITS_NAME + ":label"));
		assertEquals("+11", text(v, CurrentSessionView.TEMPOROSS_PERMITS_NAME));
		assertEquals("Avg 4,920", text(v, CurrentSessionView.TEMPOROSS_AVG_NAME));
		assertEquals("Best 5,670", text(v, CurrentSessionView.TEMPOROSS_BEST_NAME));
		assertTrue("stats follow the games line", indexOf(v, CurrentSessionView.TEMPOROSS_AVG_NAME)
			> indexOf(v, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	// UI 12: failed games only -- Games 0, no permits, no Avg/Best, no empty rows.
	@Test
	public void failedGamesOnly_showGamesZero_andNoStatRows()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(session("STRUCTURED_ACTIVITY", "tempoross", "Tempoross", "ACTIVE", "{'FISHING':9000}",
			",'activityCompletions':{'activityKey':'tempoross','sessionCompletions':0}")));
		assertEquals("Games: 0", text(v, CurrentSessionView.COMPLETION_METRIC_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_PERMITS_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_AVG_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_BEST_NAME));
	}

	// Partial games only: games count, no Avg/Best row (nothing trustworthy to average).
	@Test
	public void partialGamesOnly_countGames_butShowNoAvgBest()
	{
		CurrentSessionView v = view(null);
		v.render(snap(temporossWithStats(2, 7, 0, 0, null)));
		assertEquals("Games: 2", text(v, CurrentSessionView.COMPLETION_METRIC_NAME));
		assertEquals("+7", text(v, CurrentSessionView.TEMPOROSS_PERMITS_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_AVG_NAME));
	}

	// UI 14-20: every row at every width -- inside, unclipped, no horizontal scroll, compact height.
	@Test
	public void fullCard_narrowWidths_noClipNoOverflow_compactHeight()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(temporossWithStats(12, 1_234, 12, 12 * 5_000L, 12_345)));
		Component block = named(v, CurrentSessionView.TEMPOROSS_BLOCK_NAME);
		for (int width : WIDTHS)
		{
			v.setSize(width, 4000);
			for (int i = 0; i < 3; i++)
			{
				layout(v);
			}
			assertTrue(width + "px: no horizontal scroll (" + block.getPreferredSize().width + ")",
				block.getPreferredSize().width <= width);
			for (Component c : all((Container) block))
			{
				Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v);
				assertTrue(width + "px: " + c.getName() + " overflows (" + (r.x + r.width) + ")", r.x >= 0 && r.x + r.width <= width + 1);
				if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
				{
					assertTrue(width + "px: '" + ((JLabel) c).getText() + "' clipped", c.getWidth() >= c.getPreferredSize().width);
				}
			}
			// Captions, points, three bar rows, games, permits, avg/best: no dead space.
			assertTrue(width + "px: compact (" + block.getHeight() + "px)", block.getHeight() <= 190);
		}
	}

	// Barrows never inherits Tempoross rows, even if a session carried stats.
	@Test
	public void barrows_neverShowsTemporossStatRows()
	{
		CurrentSessionView v = view(FULL);
		v.render(snap(session("STRUCTURED_ACTIVITY", "barrows", "Barrows", "ACTIVE", "{'HITPOINTS':12000}",
			",'activityCompletions':{'activityKey':'barrows','sessionCompletions':7}"
				+ ",'activityStats':{'activityKey':'barrows','currencyGained':5,'scoredCompletions':1,'scoreTotal':10,'bestScore':10}")));
		assertNotNull(named(v, CurrentSessionView.BARROWS_BLOCK_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_PERMITS_NAME));
		assertNull(named(v, CurrentSessionView.TEMPOROSS_AVG_NAME));
		assertEquals("Chests: 7", text(v, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	// History: the session summary (never live game state) survives the activity.
	@Test
	public void history_showsTheSessionSummary_oldSessionsShowNothingExtra()
	{
		String withStats = GSON.toJson(temporossWithStats(3, 11, 3, 14_760, 5_670))
			.replace("\"state\":\"ACTIVE\"", "\"state\":\"FINALIZED\",\"finalizedAt\":\"2026-09-20T11:00:00Z\"");
		HistoryDetailModel model = HistoryDetailModel.from(GSON.fromJson(withStats, Session.class));
		assertEquals("Permits +11   \u2022   Avg pts 4,920   \u2022   Best pts 5,670", model.getActivityStatsText());
		HistoryDetailView detail = new HistoryDetailView(null, null, null, CONFIG, null);
		detail.render(model);
		// Dedicated SESSION block replaces the cramped inline header lines.
		assertNull(named(detail, HistoryDetailView.ACTIVITY_STATS_NAME));
		assertNull(named(detail, HistoryDetailView.COMPLETION_METRIC_NAME));
		assertNotNull(named(detail, HistoryDetailView.SESSION_BLOCK_NAME));
		assertEquals("Games 3", text(detail, HistoryDetailView.SESSION_GAMES_NAME));
		assertEquals("Permits +11", text(detail, HistoryDetailView.SESSION_CURRENCY_PREFIX + "single"));
		assertEquals("Avg 4,920", text(detail, HistoryDetailView.SESSION_AVG_NAME));
		assertEquals("Best 5,670", text(detail, HistoryDetailView.SESSION_BEST_NAME));

		String old = GSON.toJson(tempoross("ACTIVE"))
			.replace("\"state\":\"ACTIVE\"", "\"state\":\"FINALIZED\",\"finalizedAt\":\"2026-09-20T11:00:00Z\"");
		HistoryDetailModel oldModel = HistoryDetailModel.from(GSON.fromJson(old, Session.class));
		assertNull(oldModel.getActivityStatsText());
		HistoryDetailView oldDetail = new HistoryDetailView(null, null, null, CONFIG, null);
		oldDetail.render(oldModel);
		assertNull(named(oldDetail, HistoryDetailView.ACTIVITY_STATS_NAME));
		assertNotNull("old sessions still get the Games line", named(oldDetail, HistoryDetailView.SESSION_GAMES_NAME));
		assertNull(named(oldDetail, HistoryDetailView.SESSION_CURRENCY_PREFIX + "single"));
		assertNull(named(oldDetail, HistoryDetailView.SESSION_AVG_NAME));
	}

	@Test
	public void percentParsing_isDefensive()
	{
		assertEquals(Integer.valueOf(87), TemporossGameState.percentOf("Energy: 87%"));
		assertEquals(Integer.valueOf(0), TemporossGameState.percentOf("0%"));
		assertEquals(Integer.valueOf(100), TemporossGameState.percentOf("<col=ffffff>Essence: 100 %</col>"));
		assertNull(TemporossGameState.percentOf("Energy"));
		assertNull(TemporossGameState.percentOf(null));
		assertNull(TemporossGameState.percentOf("Storm: 101%"));
		assertNull(TemporossGameState.of(null, null, null, null));
	}
}
