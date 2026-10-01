package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.CurrentSessionSectionOrder;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.SlayerXpScope;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerItem;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import com.osrstelemetry.plugin.ui.model.SlayerDashboard;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import org.junit.Test;

/**
 * RELEASE CANDIDATE polish: the misleading Re-evaluate control is gone, rates
 * wait for the shared five-minute floor (with an honest placeholder), first-use
 * and empty states say something useful, the Loot tab can never swallow
 * mouse-wheel scrolling, and History rows stay clickable and readable.
 */
public class ReleaseCandidatePolishTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-09-20T10:50:00Z");
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};

	private static Session session(String type, String key, String name, String state, long activeMillis, String aggregates)
	{
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'" + state + "','startedAt':'2026-09-20T10:00:00Z',"
			+ "'lastActiveAt':'" + NOW + "','accumulatedActiveDurationMillis':" + activeMillis + ","
			+ (state.equals("FINALIZED") ? "'finalizedAt':'2026-09-20T11:00:00Z'," : "")
			+ "'aggregates':" + aggregates + "}").replace('\'', '"'), Session.class);
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
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

	private static JLabel named(Container root, String name)
	{
		for (Component c : all(root))
		{
			if (name.equals(c.getName()))
			{
				return (JLabel) c;
			}
		}
		return null;
	}

	private static Component component(Container root, String name)
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

	private static List<String> texts(Container root)
	{
		List<String> out = new ArrayList<>();
		for (Component c : all(root))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				out.add(UiText.visible(((JLabel) c).getText()));
			}
			if (c instanceof AbstractButton && ((AbstractButton) c).getText() != null)
			{
				out.add(((AbstractButton) c).getText());
			}
		}
		return out;
	}

	// ------------------------------------------------------------- RE-EVALUATE

	@Test
	public void reEvaluateControl_isGone_fromEveryCurrentSessionHeader()
	{
		for (String state : new String[]{"ACTIVE", "SUSPENDED"})
		{
			for (String type : new String[]{"COMBAT", "SLAYER", "BOSSING", "SKILLING"})
			{
				CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
				v.render(snap(session(type, "k", "Hill Giant", state, 600_000L, "{'xpGainedBySkill':{'ATTACK':5000},'lootDrops':[]}")));
				for (Component c : all(v))
				{
					assertFalse(type + "/" + state + ": no buttons in Current Session's header", c instanceof JButton);
					if (c instanceof JComponent && ((JComponent) c).getToolTipText() != null)
					{
						assertFalse(((JComponent) c).getToolTipText().toLowerCase().contains("re-evaluate"));
					}
				}
				assertFalse(texts(v).contains("↻"));
			}
		}
	}

	// ------------------------------------------------------------- FIRST USE

	@Test
	public void firstUse_welcomeOnlyWhenNothingIsTracked_ordinaryEmptyStateOtherwise()
	{
		CurrentSessionView fresh = new CurrentSessionView(null, null, null, CONFIG);
		fresh.setTrackedDataSupplier(() -> false);
		fresh.render(CurrentSessionSnapshot.empty());
		assertEquals(CurrentSessionView.WELCOME_TITLE, named(fresh, CurrentSessionView.EMPTY_TITLE_NAME).getText());
		assertTrue(texts(fresh).contains(CurrentSessionView.WELCOME_TEXT));

		CurrentSessionView experienced = new CurrentSessionView(null, null, null, CONFIG);
		experienced.setTrackedDataSupplier(() -> true);
		experienced.render(CurrentSessionSnapshot.empty());
		assertEquals(CurrentSessionView.NO_SESSION_TITLE, named(experienced, CurrentSessionView.EMPTY_TITLE_NAME).getText());
		assertFalse(texts(experienced).contains(CurrentSessionView.WELCOME_TITLE));

		CurrentSessionView unwired = new CurrentSessionView(null, null, null, CONFIG);
		unwired.render(CurrentSessionSnapshot.empty());
		assertEquals("unknown -> the ordinary empty state", CurrentSessionView.NO_SESSION_TITLE,
			named(unwired, CurrentSessionView.EMPTY_TITLE_NAME).getText());

		// Welcome never competes with a real session.
		fresh.render(snap(session("SKILLING", "mining", "Mining", "ACTIVE", 600_000L, "{'xpGainedBySkill':{'MINING':100},'lootDrops':[]}")));
		assertNull(named(fresh, CurrentSessionView.EMPTY_TITLE_NAME));
		assertFalse(texts(fresh).contains(CurrentSessionView.WELCOME_TITLE));
	}

	@Test
	public void lootIndex_isEmpty_isCheapAndExact()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		assertTrue(index.isEmpty());
		index.apply(new LootTrackerRecord("e1", "Goblin", 1, NOW, Collections.singletonList(new LootTrackerItem(995, "Coins", 5))));
		assertFalse(index.isEmpty());
	}

	// ------------------------------------------------------------- RATES

	@Test
	public void rateFloor_isOneSharedFiveMinutes_forXpKillsAndCompletions()
	{
		assertEquals(5L * 60_000L, CurrentSessionSnapshot.MIN_DURATION_FOR_RATE_MILLIS);
		assertEquals(CurrentSessionSnapshot.MIN_DURATION_FOR_RATE_MILLIS, CompletionMetricText.MIN_DURATION_FOR_RATE_MILLIS);
		assertNull(CompletionMetricText.perHour(3, 299_999L));
		assertEquals(36.0, CompletionMetricText.perHour(3, 300_000L), 1e-9);
		assertNull(SlayerDashboard.perHour(10, 299_999L));
		assertEquals(Long.valueOf(120L), SlayerDashboard.perHour(10, 300_000L));
	}

	@Test
	public void hugeEarlyXpDrop_totalExact_noAbsurdProjection_untilTheFloor_thenTheHonestAverage()
	{
		// A 1,000,000 XP lamp/quest drop 70 seconds into a session.
		String agg = "{'xpGainedBySkill':{'STRENGTH':1000000},'absoluteXpBySkill':{'STRENGTH':14000000},'lootDrops':[]}";
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.render(snap(session("COMBAT", "c", "Goblin", "SUSPENDED", 70_000L, agg)));
		CurrentSessionSnapshot early = snap(session("COMBAT", "c", "Goblin", "SUSPENDED", 70_000L, agg));
		assertEquals("the total is exact", 1_000_000L, early.getXpEntries().get(0).getXpGained());
		assertNull("no 51M XP/hr projection", early.getXpEntries().get(0).getXpPerHour());
		JLabel lane = named(v, CurrentSessionView.XP_RATE_NAME);
		assertNotNull("a quiet placeholder, never a blank lane", lane);
		assertEquals("XP/hr in 4m", lane.getText());
		assertEquals(SpyglassTheme.TEXT_MUTED, lane.getForeground());
		assertEquals(CurrentSessionView.RATE_WARMUP_TOOLTIP, lane.getToolTipText());
		assertTrue(texts(v).contains("+1,000,000 XP"));

		CurrentSessionView later = new CurrentSessionView(null, null, null, CONFIG);
		later.render(snap(session("COMBAT", "c", "Goblin", "SUSPENDED", 300_000L, agg)));
		assertEquals("from the floor on: the real session average (nothing smoothed or discarded)",
			"12,000,000 XP/hr", named(later, CurrentSessionView.XP_RATE_NAME).getText());
	}

	@Test
	public void rateWarmupText_wholeMinutesRoundedUp_neverZero()
	{
		assertEquals("XP/hr in 5m", CurrentSessionView.rateWarmupText(300_000L));
		assertEquals("XP/hr in 2m", CurrentSessionView.rateWarmupText(61_000L));
		assertEquals("XP/hr in 1m", CurrentSessionView.rateWarmupText(1_000L));
		assertEquals("XP/hr in 1m", CurrentSessionView.rateWarmupText(0L));
	}

	// ------------------------------------------------------------- EMPTY STATES

	@Test
	public void emptyStates_shortPlayerFacingCopy()
	{
		assertEquals("No loot tracked yet. Drops from supported activities appear here automatically.", LootTrackerView.EMPTY_NO_LOOT);
		assertEquals("No sessions yet", HistoryView.EMPTY_TITLE);
		assertEquals("Finished sessions from the last 10 days appear here automatically.", HistoryView.EMPTY_TEXT);

		HistoryView history = new HistoryView(null, null, null, CONFIG, id -> { });
		history.render(Collections.<HistoryEntry>emptyList());
		assertTrue(texts(history).contains(HistoryView.EMPTY_TEXT));
		history.render(Collections.singletonList(HistoryEntry.from(
			session("SKILLING", "mining", "Mining", "FINALIZED", 600_000L, "{'xpGainedBySkill':{'MINING':100},'lootDrops':[]}"))));
		assertFalse("the empty state disappears once a session exists", texts(history).contains(HistoryView.EMPTY_TEXT));
	}

	@Test
	public void lootEmptyState_onlyWithoutData_disappearsOnceLootExists() throws Exception
	{
		TelemetryPaths.init(TestFilepaths.rooted(Files.createTempDirectory("rc-loot-empty")));
		LootTrackerPreferences prefs = new LootTrackerPreferences(new LocalStateStore(GSON));
		prefs.loadForAccount(31L);
		LootTrackerIndex index = new LootTrackerIndex();
		LootTrackerView v = new LootTrackerView(null, null, CONFIG, prefs);
		v.render(LootTrackerSnapshot.from(index, prefs));
		assertTrue(texts(v).contains(LootTrackerView.EMPTY_NO_LOOT));
		index.apply(new LootTrackerRecord("e1", "Goblin", 1, NOW, Collections.singletonList(new LootTrackerItem(995, "Coins", 5))));
		v.render(LootTrackerSnapshot.from(index, prefs));
		assertFalse(texts(v).contains(LootTrackerView.EMPTY_NO_LOOT));
	}

	// ------------------------------------------------------------- WHEEL ROUTING

	/**
	 * Swing delivers a wheel event to the deepest component under the cursor
	 * that listens for wheel events (or to a nested scroll pane). So the wheel
	 * scrolls RuneLite's sidebar from anywhere on a card exactly when nothing
	 * inside the tab listens for it and no inner scroll pane exists.
	 */
	private static void assertWheelReachesTheSidebar(String where, Container root)
	{
		for (Component c : all(root))
		{
			assertEquals(where + ": " + c.getClass().getSimpleName() + " " + c.getName() + " would swallow the mouse wheel",
				0, c.getMouseWheelListeners().length);
			assertFalse(where + ": no nested scroll pane", c instanceof JScrollPane || c instanceof JViewport);
		}
	}

	private static void press(Container root, String text)
	{
		for (Component c : all(root))
		{
			if (c instanceof JButton && text.equals(((JButton) c).getText()))
			{
				for (ActionListener l : ((JButton) c).getActionListeners())
				{
					l.actionPerformed(null);
				}
				return;
			}
		}
		throw new AssertionError("no button " + text);
	}

	@Test
	public void lootTab_wheelOverCardsItemsLabelsAndBlankSpace_scrollsTheSidebar_inEveryView() throws Exception
	{
		TelemetryPaths.init(TestFilepaths.rooted(Files.createTempDirectory("rc-wheel")));
		LootTrackerPreferences prefs = new LootTrackerPreferences(new LocalStateStore(GSON));
		prefs.loadForAccount(32L);
		LootTrackerIndex index = new LootTrackerIndex();
		for (int i = 0; i < 120; i++)
		{
			index.apply(new LootTrackerRecord("e" + i, "Source " + (i % 7), i % 7, NOW.plusSeconds(i),
				Arrays.asList(new LootTrackerItem(995, "Coins", 100 + i), new LootTrackerItem(561, "Nature rune", 3))));
		}
		LootTrackerView v = new LootTrackerView(null, null, CONFIG, prefs);
		v.setSize(225, 4000);
		v.render(LootTrackerSnapshot.from(index, prefs));
		assertWheelReachesTheSidebar("Grouped", v);
		press(v, "Grouped");
		assertWheelReachesTheSidebar("Individual", v);
		press(v, "Load more");
		assertWheelReachesTheSidebar("Individual after Load more", v);
		for (Component c : all(v))
		{
			if (c instanceof JTextField)
			{
				((JTextField) c).setText("nature");
			}
		}
		assertWheelReachesTheSidebar("Individual while searching", v);
		press(v, "Individual");
		assertWheelReachesTheSidebar("Grouped while searching", v);

		CurrentSessionView session = new CurrentSessionView(null, null, null, CONFIG);
		session.render(snap(session("SKILLING", "mining", "Mining", "ACTIVE", 600_000L, "{'xpGainedBySkill':{'MINING':100},'lootDrops':[]}")));
		assertWheelReachesTheSidebar("Current Session", session);
		HistoryView history = new HistoryView(null, null, null, CONFIG, id -> { });
		history.render(Collections.singletonList(HistoryEntry.from(
			session("SKILLING", "mining", "Mining", "FINALIZED", 600_000L, "{'xpGainedBySkill':{'MINING':100},'lootDrops':[]}"))));
		assertWheelReachesTheSidebar("History", history);
	}

	// ------------------------------------------------------------- HISTORY ROWS

	@Test
	public void historyRow_titleWhenAndStatsLines_fullTitleOnTheRow_andEveryClickReachesTheRow()
	{
		Session s = session("STRUCTURED_ACTIVITY", "barrows", "Barrows", "FINALIZED", 2_520_000L,
			"{'xpGainedBySkill':{'MAGIC':40000},'lootDrops':[],'activityCompletions':{'activityKey':'barrows','sessionCompletions':6}}");
		HistoryView list = new HistoryView(null, null, null, CONFIG, id -> { });
		list.render(Collections.singletonList(HistoryEntry.from(s)));
		JLabel title = named(list, HistoryView.ROW_TITLE_NAME);
		FactLine when = (FactLine) component(list, HistoryView.ROW_WHEN_NAME);
		FactLine stats = (FactLine) component(list, HistoryView.ROW_STATS_NAME);
		assertEquals("Barrows", title.getText());
		assertTrue(when.text().endsWith("42:00"));
		assertEquals("Chests: 6" + FactLine.SEPARATOR + "40,000 XP", stats.text());
		JComponent row = (JComponent) title.getParent().getParent();
		assertTrue("the row carries the full title + date", row.getToolTipText().startsWith("Barrows — "));
		for (Component c : all(row))
		{
			assertEquals("no child swallows the click that opens the entry: " + c, 0, c.getMouseListeners().length);
		}
	}

	@Test
	public void factLine_wrapsBetweenFacts_neverInside_noLineStartsWithABullet_andIsCheapPlainLabels()
	{
		JPanel host = new JPanel(new java.awt.BorderLayout());
		FactLine line = new FactLine(net.runelite.client.ui.FontManager.getRunescapeSmallFont(), SpyglassTheme.TEXT_SECONDARY)
			.fact("Sep 20, 11:00 AM").fact("3:28:40").fact("Games: 16").fact("423,100 XP").fact("3 drops");
		host.add(line, java.awt.BorderLayout.NORTH);
		for (int width : new int[]{110, 150, 180, 213, 260, 600})
		{
			host.setSize(width, 400);
			host.doLayout();
			line.setSize(width, line.getPreferredSize().height);
			line.doLayout();
			int lineHeight = 0;
			for (Component c : line.getComponents())
			{
				assertFalse("plain labels only (no HTML parsing per row)", ((JLabel) c).getText().startsWith("<html>"));
				lineHeight = Math.max(lineHeight, c.getPreferredSize().height);
				if (c.getWidth() == 0)
				{
					assertEquals("only a separator is ever dropped (at a wrap)", FactLine.SEPARATOR, ((JLabel) c).getText());
					continue;
				}
				assertTrue(width + "px: inside the line", c.getX() + c.getWidth() <= width);
				if (FactLine.SEPARATOR.equals(((JLabel) c).getText()))
				{
					assertTrue(width + "px: a bullet never starts a line", c.getX() > 0);
				}
				else
				{
					assertEquals(width + "px: a fact is never cut: " + ((JLabel) c).getText(), c.getPreferredSize().width, c.getWidth());
				}
			}
			int lines = line.getPreferredSize().height / lineHeight;
			assertTrue(width + "px: wraps as needed (" + lines + ")", width >= 600 ? lines == 1 : lines >= 2);
		}
		String sep = FactLine.SEPARATOR;
		assertEquals("Sep 20, 11:00 AM" + sep + "3:28:40" + sep + "Games: 16" + sep + "423,100 XP" + sep + "3 drops", line.text());
	}

	@Test
	public void historyCompactDate_noYear_andNeverThrows()
	{
		assertNotNull(HistoryView.formatCompact("2026-09-20T11:00:00Z"));
		assertFalse(HistoryView.formatCompact("2026-09-20T11:00:00Z").contains("2026"));
		assertNull(HistoryView.formatCompact("not a date"));
		assertNull(HistoryView.formatCompact(null));
	}

	// ------------------------------------------------------------- SETTINGS

	@Test
	public void settingsLabels_matchTheInPanelWording()
	{
		assertEquals("All tasks", SlayerXpScope.ALL_TASKS.toString());
		assertEquals("Current task", SlayerXpScope.CURRENT_TASK.toString());
		assertEquals("XP first", CurrentSessionSectionOrder.XP_FIRST.toString());
		assertEquals("Loot first", CurrentSessionSectionOrder.LOOT_FIRST.toString());
		assertEquals("defaults unchanged", SlayerXpScope.ALL_TASKS, CONFIG.slayerXpScope());
		assertEquals(CurrentSessionSectionOrder.XP_FIRST, CONFIG.currentSessionSectionOrder());
	}

	@Test
	public void loadMore_sitsAtTheBottomOfTheIndividualStream_andStaysThereAfterEachClick() throws Exception
	{
		TelemetryPaths.init(TestFilepaths.rooted(Files.createTempDirectory("rc-load-more")));
		LootTrackerPreferences prefs = new LootTrackerPreferences(new LocalStateStore(GSON));
		prefs.loadForAccount(33L);
		LootTrackerIndex index = new LootTrackerIndex();
		for (int i = 0; i < 130; i++)
		{
			index.apply(new LootTrackerRecord("e" + i, "Source " + (i % 4), i % 4, NOW.plusSeconds(i),
				Collections.singletonList(new LootTrackerItem(995, "Coins", 1 + i))));
		}
		LootTrackerView v = new LootTrackerView(null, null, CONFIG, prefs);
		v.setSize(225, 6000);
		v.render(LootTrackerSnapshot.from(index, prefs));
		press(v, "Grouped");
		for (int click = 0; click <= 2; click++)
		{
			List<Component> order = all(v);
			int lastRecord = -1;
			int button = -1;
			for (int i = 0; i < order.size(); i++)
			{
				Component c = order.get(i);
				if (LootTrackerView.RECORD_CARD_NAME.equals(c.getName()))
				{
					lastRecord = i;
				}
				if (c instanceof JButton && "Load more".equals(((JButton) c).getText()))
				{
					button = i;
				}
			}
			if (click < 2)
			{
				assertTrue("Load more exists while older drops remain", button >= 0);
				assertTrue("Load more comes after every shown drop (the bottom), click " + click, button > lastRecord);
				press(v, "Load more");
			}
			else
			{
				assertEquals("all 130 drops shown: no Load more left", -1, button);
			}
		}
	}
}
