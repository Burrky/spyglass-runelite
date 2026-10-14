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
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.Collections;
import javax.swing.JLabel;
import org.junit.Test;

/**
 * The structured-activity completion metric renders generically ("Games: 8",
 * "Games/hr: 11.4") in Current Session and History, inside the existing
 * layouts, and nowhere else.
 */
public class StructuredActivityDisplayTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	/** 42 minutes of active play. */
	private static final long ACTIVE_MILLIS = 42L * 60_000L;

	private static Session session(String state, String type, String key, String name, String aggregates)
	{
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'" + state + "','startedAt':'2026-09-20T10:00:00Z',"
			+ "'lastActiveAt':'2026-09-20T10:42:00Z'" + ("FINALIZED".equals(state) ? ",'finalizedAt':'2026-09-20T11:00:00Z'" : "")
			+ ",'accumulatedActiveDurationMillis':" + ACTIVE_MILLIS + ",'aggregates':" + aggregates + "}").replace('\'', '"'), Session.class);
	}

	private static final String GOTR_AGGREGATES =
		"{'xpGainedBySkill':{'RUNECRAFT':52000,'MINING':3000},'lootDrops':[],"
			+ "'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':8,'latestAuthoritativeValue':1208}}";

	/** Any component by name (labels, FactLines, panels). */
	private static Component component(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
			if (c instanceof Container)
			{
				Component found = component((Container) c, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static JLabel named(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && name.equals(c.getName()))
			{
				return (JLabel) c;
			}
			if (c instanceof Container)
			{
				JLabel found = named((Container) c, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static JLabel startingWith(Container root, String prefix)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null && UiText.visible(((JLabel) c).getText()).startsWith(prefix))
			{
				return (JLabel) c;
			}
			if (c instanceof Container)
			{
				JLabel found = startingWith((Container) c, prefix);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void layoutAt(Container c, int width)
	{
		c.setSize(width, 3000);
		for (int pass = 0; pass < 2; pass++)
		{
			c.invalidate();
			layout(c);
		}
	}

	private static void layout(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}

	@Test
	public void metricText_isGeneric_andRateNeedsOneMinute()
	{
		assertEquals("Games: 8   •   Games/hr: 11.4", CompletionMetricText.summary("Games", 8, ACTIVE_MILLIS));
		assertEquals("Games: 1", CompletionMetricText.summary("Games", 1, 30_000L));
		assertEquals("Games: 0", CompletionMetricText.summary("Games", 0, ACTIVE_MILLIS));
		assertNull(CompletionMetricText.perHour(0, ACTIVE_MILLIS));
	}

	@Test
	public void currentSession_snapshotAndView_showTheMetric()
	{
		Session live = session("SUSPENDED", "STRUCTURED_ACTIVITY", "guardians_of_the_rift", "Guardians of the Rift", GOTR_AGGREGATES);
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(live), Instant.parse("2026-09-20T10:50:00Z"));
		assertEquals("Games", snap.getCompletionLabel());
		assertEquals(8, snap.getCompletionCount());

		for (int width : new int[] {150, 213, 260})
		{
			CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
			view.render(snap);
			layoutAt(view, width);
			// GotR's SESSION is its own stat grid (GAMES / GAMES/HR / ...), never
			// repeated in the header.
			assertNull(component(view, CurrentSessionView.COMPLETION_METRIC_NAME));
			JLabel metric = named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.GOTR_GAMES_CELL);
			assertNotNull(metric);
			assertEquals("8", metric.getText());
			JLabel rate = named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.GOTR_GAMES_PER_HOUR_CELL);
			assertNotNull(rate);
			assertEquals("11.4", rate.getText());
			if (width >= 213)
			{
				assertTrue(width + "px: metric fully visible", metric.getWidth() >= metric.getPreferredSize().width);
				assertTrue(width + "px: rate fully visible", rate.getWidth() >= rate.getPreferredSize().width);
			}
		}
	}

	@Test
	public void currentSession_noMetric_forOrdinarySessions_orActivitiesWithoutACompletionSource()
	{
		Session mining = session("SUSPENDED", "SKILLING", "mining", "Mining", "{'xpGainedBySkill':{'MINING':100}}");
		Session foundry = session("SUSPENDED", "STRUCTURED_ACTIVITY", "giants_foundry", "Giants Foundry", "{'xpGainedBySkill':{'SMITHING':100}}");
		for (Session s : new Session[] {mining, foundry})
		{
			CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(s), Instant.parse("2026-09-20T10:50:00Z"));
			assertNull(snap.getCompletionLabel());
			CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
			view.render(snap);
			layoutAt(view, 213);
			assertNull(component(view, CurrentSessionView.COMPLETION_METRIC_NAME));
		}
	}

	@Test
	public void currentSession_zeroCompletionsYet_showsZeroWithoutRate()
	{
		Session live = session("SUSPENDED", "STRUCTURED_ACTIVITY", "tempoross", "Tempoross", "{'xpGainedBySkill':{'FISHING':900}}");
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(live), Instant.parse("2026-09-20T10:50:00Z"));
		assertEquals("Games", snap.getCompletionLabel());
		assertEquals(0, snap.getCompletionCount());
	}

	@Test
	public void history_detailAndList_showTheMetric()
	{
		Session finalized = session("FINALIZED", "STRUCTURED_ACTIVITY", "barrows", "Barrows",
			"{'xpGainedBySkill':{'MAGIC':40000},'lootDrops':[],'activityCompletions':{'activityKey':'barrows','sessionCompletions':6}}");
		HistoryDetailModel model = HistoryDetailModel.from(finalized);
		assertEquals("Chests", model.getCompletionLabel());
		assertEquals(6, model.getCompletionCount());
		HistoryDetailView detail = new HistoryDetailView(null, null, null, CONFIG, null);
		detail.render(model);
		layoutAt(detail, 213);
		java.awt.Component metric = component(detail, HistoryDetailView.COMPLETION_METRIC_NAME);
		assertNotNull(metric);
		assertEquals("wraps between facts, never clips", "Chests: 6" + FactLine.SEPARATOR + "Chests/hr: 8.6", UiText.visible(metric));

		HistoryEntry entry = HistoryEntry.from(finalized);
		HistoryView list = new HistoryView(null, null, null, CONFIG, null);
		list.render(Collections.singletonList(entry));
		layoutAt(list, 213);
		assertNotNull("the row's stats line leads with the metric", startingWith(list, "Chests: 6"));
		assertNotNull(startingWith(list, "Barrows"));
	}

	@Test
	public void history_ordinarySession_unchanged()
	{
		Session finalized = session("FINALIZED", "SKILLING", "mining", "Mining", "{'xpGainedBySkill':{'MINING':100}}");
		HistoryDetailModel model = HistoryDetailModel.from(finalized);
		assertNull(model.getCompletionLabel());
		HistoryDetailView detail = new HistoryDetailView(null, null, null, CONFIG, null);
		detail.render(model);
		assertNull(component(detail, HistoryDetailView.COMPLETION_METRIC_NAME));
		assertNull(HistoryEntry.from(finalized).getActivityCompletionCount());
	}

	// ---- Pest Control: "Games won" + commendation points (simple UI) --------

	private static final String PEST_AGGREGATES =
		"{'xpGainedBySkill':{'ATTACK':4000,'HITPOINTS':1300},'lootDrops':[],"
			+ "'activityCompletions':{'activityKey':'pest_control','sessionCompletions':3},"
			+ "'activityStats':{'activityKey':'pest_control','currencyGained':24,'currencyGainedByKey':{'commendation_points':24}}}";

	@Test
	public void pestControl_currentSession_sessionGrid_atEveryWidth()
	{
		// Superseded the live-QA repair's single header line: the approved
		// dashboard carries GAMES WON | COMMENDATIONS / AVG GAME | COMMENDS / HR.
		Session live = session("SUSPENDED", "STRUCTURED_ACTIVITY", "pest_control", "Pest Control", PEST_AGGREGATES);
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(live), Instant.parse("2026-09-20T10:50:00Z"));
		assertEquals("only rewarded wins are countable", "Games won", snap.getCompletionLabel());
		assertEquals(3, snap.getCompletionCount());
		for (int width : new int[] {150, 180, 213, 260})
		{
			CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
			view.render(snap);
			layoutAt(view, width);
			assertNull("no duplicate header line", component(view, CurrentSessionView.COMPLETION_METRIC_NAME));
			assertEquals("3", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_GAMES_CELL)));
			assertEquals("+24", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_COMMENDATIONS_CELL)));
			// No round time recorded (older session): active time is the fallback denominator, 24 / 0.7 h.
			assertEquals("34.3", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_RATE_CELL)));
			java.util.List<String> problems = SidebarClippingRegressionTest.audit(view, width);
			assertTrue(width + ": " + problems, problems.isEmpty());
		}
	}

	@Test
	public void pestControl_noWinYet_showsZeroGames_neverAFabricatedRate()
	{
		Session live = session("ACTIVE", "STRUCTURED_ACTIVITY", "pest_control", "Pest Control", "{'xpGainedBySkill':{'ATTACK':100}}");
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(live), Instant.parse("2026-09-20T10:50:00Z"));
		CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
		view.render(snap);
		layoutAt(view, 213);
		assertEquals("0", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_GAMES_CELL)));
		assertEquals("-", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_COMMENDATIONS_CELL)));
		assertEquals("-", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_AVG_CELL)));
		assertEquals("-", UiText.visible(named(view, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.PEST_RATE_CELL)));
	}

	@Test
	public void pestControl_history_sessionBlock_gamesWon_andCommendations()
	{
		Session finalized = session("FINALIZED", "STRUCTURED_ACTIVITY", "pest_control", "Pest Control", PEST_AGGREGATES);
		HistoryDetailModel model = HistoryDetailModel.from(finalized);
		assertEquals("Games won", model.getCompletionLabel());
		HistoryDetailView detail = new HistoryDetailView(null, null, null, CONFIG, null);
		detail.render(model);
		layoutAt(detail, 213);
		assertNull("SESSION block, not the inline line", component(detail, HistoryDetailView.COMPLETION_METRIC_NAME));
		assertEquals("Games won 3", UiText.visible(component(detail, HistoryDetailView.SESSION_GAMES_NAME)));
		assertEquals("Commendations +24", UiText.visible(component(detail, HistoryDetailView.SESSION_CURRENCY_PREFIX + "single")));
		assertEquals("34.3/hr", UiText.visible(component(detail, HistoryDetailView.SESSION_RATE_NAME)));

		HistoryView list = new HistoryView(null, null, null, CONFIG, null);
		list.render(Collections.singletonList(HistoryEntry.from(finalized)));
		layoutAt(list, 213);
		assertNotNull("the row leads with Games won, never a misleading Games", startingWith(list, "Games won: 3"));
	}

	@Test
	public void otherActivitiesKeepTheirRateLabel()
	{
		assertEquals("Games/hr", CompletionMetricText.rateLabel("Games"));
		assertEquals("Chests/hr", CompletionMetricText.rateLabel("Chests"));
		assertEquals("Games/hr", CompletionMetricText.rateLabel("Games won"));
	}
}
