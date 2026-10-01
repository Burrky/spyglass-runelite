package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.CurrentSessionSectionOrder;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.SlayerXpScope;
import com.osrstelemetry.plugin.model.SlayerLiveStatus;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.session.SlayerAssignmentEndReason;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * SMART SLAYER DASHBOARD in the real (headless) Current Session view:
 * CURRENT TASK, SLAYER STATUS, the in-section XP scope selector and TASK
 * HISTORY -- including the rules about what is never shown.
 */
public class CurrentSessionViewSlayerDashboardTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private static class Cfg implements OsrsTelemetryConfig
	{
		SlayerXpScope scope = SlayerXpScope.ALL_TASKS;

		@Override
		public SlayerXpScope slayerXpScope()
		{
			return scope;
		}

		@Override
		public CurrentSessionSectionOrder currentSessionSectionOrder()
		{
			return CurrentSessionSectionOrder.XP_FIRST;
		}
	}

	private static SessionAggregates.SlayerAssignmentRecord record(int ordinal, String task, int size, int remaining, Integer master)
	{
		SessionAggregates.SlayerAssignmentRecord r = new SessionAggregates.SlayerAssignmentRecord();
		r.setOrdinal(ordinal);
		r.setTaskName(task);
		r.setAssignmentKey(task.toLowerCase());
		r.setAssignedAt(T0.toString());
		r.setInitialAmount(size);
		r.setLatestRemaining(remaining);
		r.setUnitsCompleted(size - remaining);
		r.setSlaughterProcs(0);
		r.setExpeditiousProcs(0);
		r.setMasterId(master);
		r.setXpGainedBySkill(new LinkedHashMap<>());
		r.setActiveDurationStartMillis(0L);
		return r;
	}

	/** Slayer session on `current` (last); session XP in Slayer + Ranged; current task XP only Slayer. */
	private static CurrentSessionSnapshot snapshot(List<SessionAggregates.SlayerAssignmentRecord> ledger, long activeMillis)
	{
		SessionAggregates.SlayerAssignmentRecord current = ledger.get(ledger.size() - 1);
		Session s = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.SLAYER, current.getAssignmentKey(), current.getTaskName()), T0).getCurrent();
		try
		{
			java.lang.reflect.Method m = Session.class.getDeclaredMethod("setAccumulatedActiveDurationMillis", long.class);
			m.setAccessible(true);
			m.invoke(s, activeMillis);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
		s.getAggregates().getXpGainedBySkill().put("SLAYER", 9000L);
		s.getAggregates().getXpGainedBySkill().put("RANGED", 30000L);
		current.getXpGainedBySkill().put("SLAYER", 1200L);
		s.getAggregates().setLatestSlayerCurrentRemaining(current.getLatestRemaining());
		s.getAggregates().setSlayerAssignments(ledger);
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0);
	}

	private static List<SessionAggregates.SlayerAssignmentRecord> konarTask()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = new ArrayList<>();
		SessionAggregates.SlayerAssignmentRecord bugs = record(1, "Cave bugs", 17, 0, 1);
		bugs.setEndReason(SlayerAssignmentEndReason.COMPLETED);
		bugs.setCompletedAt(T0.toString());
		bugs.setEndedAt(T0.toString());
		bugs.setActiveDurationEndMillis(0L);
		l.add(bugs);
		SessionAggregates.SlayerAssignmentRecord wyrms = record(2, "Wyrms", 136, 17, 8);
		wyrms.setSlaughterProcs(2);
		wyrms.setExpeditiousProcs(4);
		l.add(wyrms);
		return l;
	}

	private static SlayerLiveStatus status(int master, int points, int streak, String label, String task, String location,
		int modId, int modValue, boolean neg)
	{
		return new SlayerLiveStatus(master, points, streak, label, task, location, modId, modValue, neg);
	}

	private static CurrentSessionView view(Cfg cfg, SlayerLiveStatus status)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, cfg);
		v.setSlayerStatusSupplier(() -> status);
		return v;
	}

	private static List<Component> all(Container root)
	{
		List<Component> out = new ArrayList<>();
		for (Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof Container)
			{
				out.addAll(all((Container) c));
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

	private static String stat(Container root, String label)
	{
		Component c = named(root, CurrentSessionView.SLAYER_STAT_PREFIX + label);
		return c == null ? null : ((StatLine) c).getValue().getText();
	}

	private static List<String> texts(Container root)
	{
		List<String> out = new ArrayList<>();
		for (Component c : all(root))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				out.add(((JLabel) c).getText());
			}
		}
		return out;
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

	private static final SlayerLiveStatus KONAR = status(8, 1284, 49, "NORMAL", "Wyrms", "Karuulm Slayer Dungeon", 0, 0, false);

	private static String cell(Container root, String caption)
	{
		Component c = named(root, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static final String[] SIX = {CurrentSessionView.KILLS_CELL, CurrentSessionView.KILLS_PER_HOUR_CELL,
		CurrentSessionView.LOOT_PER_KILL_CELL, CurrentSessionView.POINTS_CELL, CurrentSessionView.STREAK_CELL,
		CurrentSessionView.NEXT_BONUS_CELL};

	// ---------------------------------------------------------------- LAYOUT

	@Test
	public void layout_heroThenFullWidthMasterContext_thenTheSixCellGrid_thenBracelets_noStatusHeading()
	{
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(snapshot(konarTask(), 30L * 60_000L));
		Container block = (Container) named(v, CurrentSessionView.SLAYER_BLOCK_NAME);
		SlayerStatGrid grid = (SlayerStatGrid) named(v, "slayerStatGrid");
		assertNotNull(grid);
		List<String> cellOrder = new ArrayList<>();
		for (Component c : grid.getComponents())
		{
			cellOrder.add(c.getName());
		}
		List<String> expected = new ArrayList<>();
		for (String c : SIX)
		{
			expected.add(SlayerStatGrid.CELL_PREFIX + c);
		}
		assertEquals("exactly the six permanent cells, in order", expected, cellOrder);

		int heroAt = indexOf(block, named(v, CurrentSessionView.SLAYER_REMAINING_NAME).getParent());
		int masterAt = indexOf(block, named(v, CurrentSessionView.SLAYER_STAT_PREFIX + "Master"));
		int locationAt = indexOf(block, named(v, CurrentSessionView.SLAYER_STAT_PREFIX + "Location"));
		int gridAt = indexOf(block, grid);
		int braceletsAt = indexOf(block, named(v, CurrentSessionView.SLAYER_BRACELET_ROW_NAME));
		assertTrue(heroAt < masterAt && masterAt < locationAt && locationAt < gridAt && gridAt < braceletsAt);
		for (String outside : new String[] {"Master", "Location", "Modifier", "Slaughter", "Expeditious"})
		{
			assertNull(outside + " never becomes a grid cell", named(grid, SlayerStatGrid.CELL_PREFIX + outside.toUpperCase()));
		}
		for (String t : texts(v))
		{
			assertFalse("no SLAYER STATUS heading", t.equalsIgnoreCase("SLAYER STATUS"));
		}
	}

	private static int indexOf(Container parent, Component child)
	{
		for (int i = 0; i < parent.getComponentCount(); i++)
		{
			if (parent.getComponent(i) == child)
			{
				return i;
			}
		}
		throw new AssertionError("not a direct child: " + child);
	}

	// ---------------------------------------------------------------- CURRENT TASK

	@Test
	public void currentTask_heroAndCells_killsRatesBracelets()
	{
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(snapshot(konarTask(), 30L * 60_000L));
		assertEquals("17 remaining", text(v, CurrentSessionView.SLAYER_REMAINING_NAME));
		assertEquals("119/136", "87%", text(v, CurrentSessionView.SLAYER_PROGRESS_NAME));
		assertNotNull(named(v, CurrentSessionView.SLAYER_PROGRESS_NAME + ":bar:87"));
		assertEquals("Konar quo Maten", stat(v, "Master"));
		assertEquals("Karuulm Slayer Dungeon", stat(v, "Location"));
		assertNull("no modifier for Konar", stat(v, "Modifier"));
		assertEquals("119 units - 4 expeditious + 2 slaughtered", "117", cell(v, CurrentSessionView.KILLS_CELL));
		assertEquals("234", cell(v, CurrentSessionView.KILLS_PER_HOUR_CELL));
		assertEquals("no valued loot yet", "-", cell(v, CurrentSessionView.LOOT_PER_KILL_CELL));
		assertEquals("Slaughter 2", text(v, CurrentSessionView.SLAYER_SLAUGHTER_NAME));
		assertEquals("Expeditious 4", text(v, CurrentSessionView.SLAYER_EXPEDITIOUS_NAME));
		assertEquals(SpyglassTheme.ACCENT_SAGE, named(v, CurrentSessionView.SLAYER_SLAUGHTER_NAME).getForeground());
		assertEquals(SpyglassTheme.ACCENT_AMBER, named(v, CurrentSessionView.SLAYER_EXPEDITIOUS_NAME).getForeground());
	}

	@Test
	public void currentTask_neverShowsTheAssignmentSize_orARedundantTaskLabel()
	{
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(snapshot(konarTask(), 30L * 60_000L));
		for (String t : texts(v))
		{
			assertFalse("assignment size leaked: " + t, t.contains("136"));
			assertFalse(t, t.matches(".*\\d+ / \\d+.*"));
			assertFalse(t, t.toLowerCase().contains("assigned"));
			assertFalse("no 'Konar task:' label", t.contains("task:"));
		}
	}

	@Test
	public void braceletRow_onlyWhenTheyHappened_singleEffectAlone_andNoKillsWithoutTracking()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		l.get(1).setSlaughterProcs(0);
		l.get(1).setExpeditiousProcs(0);
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(snapshot(l, 30L * 60_000L));
		assertNull("no reserved space", named(v, CurrentSessionView.SLAYER_BRACELET_ROW_NAME));
		assertEquals("119", cell(v, CurrentSessionView.KILLS_CELL));

		l.get(1).setSlaughterProcs(11);
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals("Slaughter 11", text(v, CurrentSessionView.SLAYER_SLAUGHTER_NAME));
		assertNull(named(v, CurrentSessionView.SLAYER_EXPEDITIOUS_NAME));

		l.get(1).setSlaughterProcs(null);
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals("untracked record: units are never called kills", "-", cell(v, CurrentSessionView.KILLS_CELL));
		assertEquals("-", cell(v, CurrentSessionView.KILLS_PER_HOUR_CELL));
		assertEquals("the six cells always exist", 6, ((Container) named(v, "slayerStatGrid")).getComponentCount());
	}

	@Test
	public void killsPerHour_waitsForTheRateFloor()
	{
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(snapshot(konarTask(), 20_000L));
		assertEquals("117", cell(v, CurrentSessionView.KILLS_CELL));
		assertEquals("-", cell(v, CurrentSessionView.KILLS_PER_HOUR_CELL));
	}

	@Test
	public void mortimer_modifierRowAndHisOwnStreak_noStandardBonus()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		l.get(1).setMasterId(10);
		CurrentSessionView v = view(new Cfg(), status(10, 300, 33, "MORTIMER", "Wyrms", null, 2, 65, false));
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals("Mortimer", stat(v, "Master"));
		assertEquals("Quantity +65", stat(v, "Modifier"));
		assertNull("no Konar location", stat(v, "Location"));
		assertEquals("33", cell(v, CurrentSessionView.STREAK_CELL));
		assertEquals("Mortimer's task streak",
			((javax.swing.JComponent) named(v, SlayerStatGrid.CELL_PREFIX + CurrentSessionView.STREAK_CELL)).getToolTipText());
		assertEquals("no streak bonus for Mortimer", "-", cell(v, CurrentSessionView.NEXT_BONUS_CELL));
	}

	@Test
	public void krystilia_wildernessStreak_andItsNextBonus()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		l.get(1).setMasterId(7);
		CurrentSessionView v = view(new Cfg(), status(7, 900, 47, "WILDERNESS", "Wyrms", null, 0, 0, false));
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals("Krystilia", stat(v, "Master"));
		assertEquals("47", cell(v, CurrentSessionView.STREAK_CELL));
		assertEquals("50th in 3", cell(v, CurrentSessionView.NEXT_BONUS_CELL));
	}

	@Test
	public void liveContext_onlyWhenTheGamesTaskIsThisAssignment()
	{
		CurrentSessionView v = view(new Cfg(), status(8, 1284, 49, "NORMAL", "Hellhounds", "Stronghold Slayer Cave", 2, 20, false));
		v.render(snapshot(konarTask(), 30L * 60_000L));
		assertEquals("recorded master still shown", "Konar quo Maten", stat(v, "Master"));
		assertNull(stat(v, "Location"));
		assertNull(stat(v, "Modifier"));

		CurrentSessionView unknown = view(new Cfg(), null);
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		l.get(1).setMasterId(null);
		unknown.render(snapshot(l, 30L * 60_000L));
		assertNull("unavailable master is omitted", stat(unknown, "Master"));
		assertEquals("no live status -> quiet dashes, never guesses", "-", cell(unknown, CurrentSessionView.POINTS_CELL));
		assertEquals("-", cell(unknown, CurrentSessionView.STREAK_CELL));
		assertEquals("-", cell(unknown, CurrentSessionView.NEXT_BONUS_CELL));
		assertEquals("17 remaining", text(unknown, CurrentSessionView.SLAYER_REMAINING_NAME));
	}

	// ---------------------------------------------------------------- STATUS CELLS

	@Test
	public void statusCells_pointsGold_streak_nextBonusEvery10th()
	{
		CurrentSessionView v = view(new Cfg(), status(5, 83, 2, "NORMAL", "Wyrms", null, 0, 0, false));
		v.render(snapshot(konarTask(), 30L * 60_000L));
		assertEquals("83", cell(v, CurrentSessionView.POINTS_CELL));
		assertEquals(SpyglassTheme.ACCENT_GOLD, named(v, SlayerStatGrid.VALUE_PREFIX + CurrentSessionView.POINTS_CELL).getForeground());
		assertEquals("2", cell(v, CurrentSessionView.STREAK_CELL));
		assertEquals("live QA case: streak 2 -> the 10th, not the 50th", "10th in 8", cell(v, CurrentSessionView.NEXT_BONUS_CELL));
		assertNull("one Turael task is not boosting", named(v, CurrentSessionView.SLAYER_POINTS_MASTER_HINT_NAME));

		CurrentSessionView fifty = view(new Cfg(), KONAR);
		fifty.render(snapshot(konarTask(), 30L * 60_000L));
		assertEquals("50th next", cell(fifty, CurrentSessionView.NEXT_BONUS_CELL));
	}

	private static List<SessionAggregates.SlayerAssignmentRecord> turaelRun()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = new ArrayList<>();
		for (int o = 1; o <= 3; o++)
		{
			SessionAggregates.SlayerAssignmentRecord r = record(o, "Cave bugs", 10, 0, 1);
			r.setEndReason(SlayerAssignmentEndReason.COMPLETED);
			r.setCompletedAt(T0.toString());
			r.setEndedAt(T0.toString());
			r.setActiveDurationEndMillis(0L);
			l.add(r);
		}
		l.get(2).setEndedAt(null); // the awaiting current record
		return l;
	}

	@Test
	public void turaelBoosting_hintWhenTheNextTaskEarnsABonus_ordinary10thBoundaryToo()
	{
		for (int streak : new int[] {9, 19, 49, 99})
		{
			CurrentSessionView v = view(new Cfg(), status(1, 600, streak, "NORMAL", null, null, 0, 0, false));
			v.render(snapshot(turaelRun(), 30L * 60_000L));
			assertEquals("streak " + streak, "Use a points master", text(v, CurrentSessionView.SLAYER_POINTS_MASTER_HINT_NAME));
		}
		CurrentSessionView notNext = view(new Cfg(), status(1, 600, 47, "NORMAL", null, null, 0, 0, false));
		notNext.render(snapshot(turaelRun(), 30L * 60_000L));
		assertNull(named(notNext, CurrentSessionView.SLAYER_POINTS_MASTER_HINT_NAME));
		assertEquals("50th in 3", cell(notNext, CurrentSessionView.NEXT_BONUS_CELL));

		List<SessionAggregates.SlayerAssignmentRecord> two = turaelRun();
		two.remove(0);
		two.get(0).setOrdinal(1);
		CurrentSessionView ordinary = view(new Cfg(), status(1, 600, 9, "NORMAL", null, null, 0, 0, false));
		ordinary.render(snapshot(two, 30L * 60_000L));
		assertNull("two Turael tasks are ordinary use", named(ordinary, CurrentSessionView.SLAYER_POINTS_MASTER_HINT_NAME));

		List<SessionAggregates.SlayerAssignmentRecord> unknownMaster = turaelRun();
		unknownMaster.get(1).setMasterId(null);
		CurrentSessionView uncertain = view(new Cfg(), status(1, 600, 9, "NORMAL", null, null, 0, 0, false));
		uncertain.render(snapshot(unknownMaster, 30L * 60_000L));
		assertNull("uncertain -> omitted", named(uncertain, CurrentSessionView.SLAYER_POINTS_MASTER_HINT_NAME));
	}

	// ---------------------------------------------------------------- AWAITING

	@Test
	public void awaitingNewTask_completionState_cellsStayVisible_noFakeProgress()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		SessionAggregates.SlayerAssignmentRecord done = l.get(1);
		done.setLatestRemaining(0);
		done.setUnitsCompleted(136);
		done.setEndReason(SlayerAssignmentEndReason.COMPLETED);
		done.setCompletedAt(T0.toString());
		CurrentSessionView v = view(new Cfg(), status(8, 1302, 50, "NORMAL", null, null, 0, 0, false));
		v.render(snapshot(l, 30L * 60_000L));
		assertNotNull(named(v, CurrentSessionView.SLAYER_TASK_COMPLETE_NAME));
		assertTrue(texts(v).contains("Grab a new task"));
		assertNull(named(v, CurrentSessionView.SLAYER_REMAINING_NAME));
		assertNull(named(v, CurrentSessionView.SLAYER_PROGRESS_NAME));
		assertNull("no live task -> no location", stat(v, "Location"));
		assertEquals("1,302", cell(v, CurrentSessionView.POINTS_CELL));
		assertEquals("50", cell(v, CurrentSessionView.STREAK_CELL));
		assertEquals("60th in 10", cell(v, CurrentSessionView.NEXT_BONUS_CELL));
	}

	// ---------------------------------------------------------------- TASK HISTORY

	@Test
	public void taskHistory_isTheOnlyHistorySection_noSeparateLootHeading()
	{
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(snapshot(konarTask(), 30L * 60_000L));
		assertEquals("TASK HISTORY", ((JLabel) named(v, CurrentSessionView.LOOT_SECTION_TITLE_NAME)).getText());
		int titles = 0;
		for (String t : texts(v))
		{
			assertFalse("no separate LOOT section for Slayer: " + t, t.equals("LOOT") || t.startsWith("LOOT  "));
			assertFalse("no second Tasks This Session section", t.toUpperCase().contains("TASKS THIS SESSION"));
			if (t.equals("TASK HISTORY"))
			{
				titles++;
			}
		}
		assertEquals(1, titles);
		assertNotNull("current card", named(v, CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX + 2));
		assertNotNull("completed card", named(v, CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX + 1));
	}

	@Test
	public void nonSlayerSession_keepsItsLootHeading_andHasNoDashboard()
	{
		Session boss = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah"), T0).getCurrent();
		SessionAggregates.LootItemAggregate i = new SessionAggregates.LootItemAggregate();
		i.setItemName("Zulrah's scales");
		i.setQuantity(100);
		SessionAggregates.LootDropGroup g = new SessionAggregates.LootDropGroup();
		g.setSourceName("Zulrah");
		g.getItems().add(i);
		boss.getAggregates().getLootDrops().add(g);
		CurrentSessionView v = view(new Cfg(), KONAR);
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(boss, null, null), T0));
		assertEquals("LOOT", ((JLabel) named(v, CurrentSessionView.LOOT_SECTION_TITLE_NAME)).getText());
		assertNull(named(v, CurrentSessionView.SLAYER_BLOCK_NAME));
	}

	// ---------------------------------------------------------------- XP SCOPE

	@Test
	public void xpScopeSelector_livesInTheXpSection_switchesBothWays_displayOnly()
	{
		Cfg cfg = new Cfg();
		List<SlayerXpScope> written = new ArrayList<>();
		CurrentSessionView v = view(cfg, KONAR);
		v.setXpScopeWriter(written::add);
		CurrentSessionSnapshot snap = snapshot(konarTask(), 30L * 60_000L);
		long sessionSlayerXp = snap.getXpEntries(SlayerXpScope.ALL_TASKS).stream()
			.filter(e -> e.getSkill().equals("SLAYER")).findFirst().get().getXpGained();
		v.render(snap);
		assertNotNull("All Tasks is the default", named(v, CurrentSessionView.XP_SCOPE_ALL_NAME + ":selected"));
		assertTrue(texts(v).contains("+30,000 XP"));

		v.selectXpScope(SlayerXpScope.CURRENT_TASK);
		assertEquals(SlayerXpScope.CURRENT_TASK, written.get(0));
		assertNotNull(named(v, CurrentSessionView.XP_SCOPE_CURRENT_NAME + ":selected"));
		assertTrue("current assignment's XP only", texts(v).contains("+1,200 XP"));
		assertFalse(texts(v).contains("+30,000 XP"));

		v.selectXpScope(SlayerXpScope.ALL_TASKS);
		assertNotNull(named(v, CurrentSessionView.XP_SCOPE_ALL_NAME + ":selected"));
		assertTrue("previous-task XP restored", texts(v).contains("+30,000 XP"));
		assertTrue(texts(v).contains("+9,000 XP"));
		assertEquals("display scope never touches the totals", sessionSlayerXp,
			snap.getXpEntries(SlayerXpScope.ALL_TASKS).stream().filter(e -> e.getSkill().equals("SLAYER")).findFirst().get().getXpGained());
	}

	@Test
	public void xpScope_overrideYieldsToTheConfigOnceItCatchesUp()
	{
		Cfg cfg = new Cfg();
		CurrentSessionView v = view(cfg, KONAR);
		v.render(snapshot(konarTask(), 30L * 60_000L));
		v.selectXpScope(SlayerXpScope.CURRENT_TASK);
		assertEquals(SlayerXpScope.CURRENT_TASK, v.effectiveXpScope());
		cfg.scope = SlayerXpScope.CURRENT_TASK; // the writer persisted it
		assertEquals(SlayerXpScope.CURRENT_TASK, v.effectiveXpScope());
		cfg.scope = SlayerXpScope.ALL_TASKS; // later changed from Settings
		assertEquals(SlayerXpScope.ALL_TASKS, v.effectiveXpScope());
	}

	// ---------------------------------------------------------------- RESPONSIVE

	/** Lays the view out at each width; asserts nothing clips or overflows; returns the grid's columns per width. */
	private static int[] layoutAndCheck(CurrentSessionView v)
	{
		Component block = named(v, CurrentSessionView.SLAYER_BLOCK_NAME);
		SlayerStatGrid grid = (SlayerStatGrid) named(v, "slayerStatGrid");
		Component selector = named(v, CurrentSessionView.XP_SCOPE_ALL_NAME + ":selected").getParent().getParent();
		int[] cols = new int[WIDTHS.length];
		for (int w = 0; w < WIDTHS.length; w++)
		{
			int width = WIDTHS[w];
			v.setSize(width, 4000);
			for (int i = 0; i < 4; i++)
			{
				layout(v);
			}
			List<Component> checked = all((Container) block);
			checked.addAll(all((Container) selector));
			for (Component c : checked)
			{
				Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), v);
				assertTrue(width + "px: " + c.getName() + " overflows (" + (r.x + r.width) + ")", r.x >= 0 && r.x + r.width <= width + 1);
				if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
				{
					assertTrue(width + "px: '" + ((JLabel) c).getText() + "' clipped (" + c.getWidth() + " < "
						+ c.getPreferredSize().width + ")", c.getWidth() >= c.getPreferredSize().width);
				}
			}
			assertTrue(width + "px: no horizontal scroll", grid.getPreferredSize().width <= width);
			assertTrue(width + "px: compact dashboard (" + block.getHeight() + ")", block.getHeight() < 360);
			cols[w] = grid.columns();
			if (cols[w] == 3)
			{
				Component kills = named(grid, SlayerStatGrid.CELL_PREFIX + CurrentSessionView.KILLS_CELL);
				Component loot = named(grid, SlayerStatGrid.CELL_PREFIX + CurrentSessionView.LOOT_PER_KILL_CELL);
				Component points = named(grid, SlayerStatGrid.CELL_PREFIX + CurrentSessionView.POINTS_CELL);
				assertEquals("row 1: kills .. loot/kill", kills.getY(), loot.getY());
				assertTrue("row 2 below", points.getY() > kills.getY());
				assertEquals("points under kills", kills.getX(), points.getX());
			}
			if (cols[w] == 2)
			{
				Component kills = named(grid, SlayerStatGrid.CELL_PREFIX + CurrentSessionView.KILLS_CELL);
				Component lootCell = named(grid, SlayerStatGrid.CELL_PREFIX + CurrentSessionView.LOOT_PER_KILL_CELL);
				assertEquals("same order, row-major: loot/kill starts row 2", kills.getX(), lootCell.getX());
				assertTrue(lootCell.getY() > kills.getY());
			}
		}
		return cols;
	}

	@Test
	public void responsive_typicalValues_3x2At213And260_2x3At150_noClip()
	{
		// The live-QA screenshot's values: Duradel task, 36 kills, 63/hr, points 83, streak 2.
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		l.get(1).setMasterId(5);
		l.get(1).setUnitsCompleted(31);
		l.get(1).setLatestRemaining(25);
		CurrentSessionView v = view(new Cfg(), status(5, 83, 2, "NORMAL", "Wyrms", null, 0, 0, false));
		v.render(snapshot(l, 30L * 60_000L));
		int[] cols = layoutAndCheck(v);
		assertEquals("150px reflows to 2 x 3", 2, cols[0]);
		assertTrue("180px stays a readable grid (" + cols[1] + ")", cols[1] >= 2);
		assertEquals("213px: 3 x 2", 3, cols[2]);
		assertEquals("260px: 3 x 2", 3, cols[3]);
	}

	@Test
	public void responsive_worstCaseValues_stay3x2At213_andNeverClipAt150()
	{
		// Long master + location, 6-digit points, a 4-digit bonus ordinal.
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		l.get(1).setMasterId(5);
		CurrentSessionView v = view(new Cfg(), status(5, 64_000, 991, "NORMAL", "Wyrms", "Karuulm Slayer Dungeon", 0, 0, false));
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals("1,000th in 9", cell(v, CurrentSessionView.NEXT_BONUS_CELL));
		int[] cols = layoutAndCheck(v);
		assertEquals("213px: 3 x 2", 3, cols[2]);
		assertEquals("260px: 3 x 2", 3, cols[3]);
		assertTrue("150px: narrower layout, never clipped text", cols[0] < 3);
	}

	@Test
	public void lootPerKill_assignmentLocal_inTheSelectedValuationMode() throws Exception
	{
		final com.osrstelemetry.plugin.LootValuationMode[] mode = {com.osrstelemetry.plugin.LootValuationMode.GRAND_EXCHANGE};
		Cfg cfg = new Cfg()
		{
			@Override
			public com.osrstelemetry.plugin.LootValuationMode lootValuationMode()
			{
				return mode[0];
			}
		};
		CurrentSessionView v = view(cfg, KONAR);
		java.lang.reflect.Field f = CurrentSessionView.class.getDeclaredField("itemValuationCache");
		f.setAccessible(true);
		Object cache = f.get(v);
		for (String[] prices : new String[][] {{"gePrices", "2000"}, {"haPrices", "600"}})
		{
			java.lang.reflect.Field pf = cache.getClass().getDeclaredField(prices[0]);
			pf.setAccessible(true);
			Object resolving = pf.get(cache);
			java.lang.reflect.Method put = resolving.getClass().getDeclaredMethod("put", Object.class, Object.class);
			put.setAccessible(true);
			put.invoke(resolving, 1515, Long.parseLong(prices[1]));
		}
		List<SessionAggregates.SlayerAssignmentRecord> l = konarTask();
		SessionAggregates.LootItemAggregate yew = new SessionAggregates.LootItemAggregate();
		yew.setItemId(1515);
		yew.setItemName("Yew logs");
		yew.setQuantity(117);
		l.get(1).getItems().add(yew);
		SessionAggregates.LootItemAggregate other = new SessionAggregates.LootItemAggregate();
		other.setItemId(1515);
		other.setItemName("Yew logs");
		other.setQuantity(1000);
		l.get(0).getItems().add(other);
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals("117 logs x 2,000 / 117 kills -- the earlier task's loot never counts",
			net.runelite.client.util.QuantityFormatter.quantityToStackSize(2000), cell(v, CurrentSessionView.LOOT_PER_KILL_CELL));
		mode[0] = com.osrstelemetry.plugin.LootValuationMode.HIGH_ALCH;
		v.render(snapshot(l, 30L * 60_000L));
		assertEquals(net.runelite.client.util.QuantityFormatter.quantityToStackSize(600), cell(v, CurrentSessionView.LOOT_PER_KILL_CELL));
	}
}
