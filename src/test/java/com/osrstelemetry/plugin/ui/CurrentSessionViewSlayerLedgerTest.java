package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.CurrentSessionSectionOrder;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

/**
 * Current Session's Slayer task ledger UI (real headless Swing): card
 * order, collapse defaults, collapse surviving re-renders, auto-collapse on
 * a new assignment, the shared 5-column grid, the unchanged session-wide
 * LOOT header, XP/Loot order + 6px gap, and unchanged non-Slayer loot.
 * Items carry no item ID, so no ItemManager/price lookup is needed.
 */
public class CurrentSessionViewSlayerLedgerTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static OsrsTelemetryConfig config(CurrentSessionSectionOrder order)
	{
		return new OsrsTelemetryConfig()
		{
			@Override
			public CurrentSessionSectionOrder currentSessionSectionOrder()
			{
				return order;
			}
		};
	}

	private static SessionAggregates.LootItemAggregate item(String name, long qty)
	{
		SessionAggregates.LootItemAggregate i = new SessionAggregates.LootItemAggregate();
		i.setItemName(name);
		i.setQuantity(qty);
		return i;
	}

	private static Session slayerSession(String task)
	{
		return new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.SLAYER, task.toLowerCase(), task), T0).getCurrent();
	}

	/** (Re)sets `s`'s ledger to `assignments` instances (last = current), each with 2 items; optional Slayer XP. */
	private static CurrentSessionSnapshot snapshotOf(Session s, int assignments, boolean withXp)
	{
		SessionAggregates agg = s.getAggregates();
		agg.getLootDrops().clear();
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		for (int o = 1; o <= assignments; o++)
		{
			SessionAggregates.SlayerAssignmentRecord a = new SessionAggregates.SlayerAssignmentRecord();
			a.setOrdinal(o);
			a.setTaskName("Task " + o);
			a.setAssignmentKey("task " + o);
			a.getItems().add(item("Bones", o));
			a.getItems().add(item("Ashes", 10 * o));
			ledger.add(a);

			SessionAggregates.LootDropGroup g = new SessionAggregates.LootDropGroup();
			g.setSourceName("Monster " + o);
			g.getItems().add(item("Bones", o));
			g.getItems().add(item("Ashes", 10 * o));
			agg.getLootDrops().add(g);
		}
		agg.setSlayerAssignments(ledger);
		agg.setLatestSlayerCurrentRemaining(5);
		if (withXp)
		{
			agg.getXpGainedBySkill().put("SLAYER", 1000L);
		}
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0);
	}

	private static CurrentSessionSnapshot slayerSnapshot(String sessionTask, int assignments, boolean withXp)
	{
		return snapshotOf(slayerSession(sessionTask), assignments, withXp);
	}

	private static CurrentSessionView view(CurrentSessionSectionOrder order)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, config(order));
		v.setSize(213, 2000);
		return v;
	}

	private static void collectCards(Container root, List<JPanel> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JPanel && c.getName() != null && c.getName().startsWith(CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX))
			{
				out.add((JPanel) c);
			}
			else if (c instanceof Container)
			{
				collectCards((Container) c, out);
			}
		}
	}

	private static List<JPanel> cards(CurrentSessionView v)
	{
		List<JPanel> out = new ArrayList<>();
		collectCards(v, out);
		return out;
	}

	private static int ordinalOf(JPanel card)
	{
		return Integer.parseInt(card.getName().substring(CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX.length()));
	}

	private static JPanel gridIn(JPanel card)
	{
		for (Component c : card.getComponents())
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof GridLayout)
			{
				return (JPanel) c;
			}
		}
		return null;
	}

	private static boolean expanded(CurrentSessionView v, int ordinal)
	{
		for (JPanel card : cards(v))
		{
			if (ordinalOf(card) == ordinal)
			{
				return gridIn(card) != null;
			}
		}
		throw new AssertionError("no card " + ordinal);
	}

	// 18 -----------------------------------------------------------------
	@Test
	public void cardOrder_currentFirst_thenOlderNewestFirst()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		v.render(slayerSnapshot("Task 3", 3, false));
		List<JPanel> cards = cards(v);
		assertEquals(3, cards.size());
		assertEquals(3, ordinalOf(cards.get(0)));
		assertEquals(2, ordinalOf(cards.get(1)));
		assertEquals(1, ordinalOf(cards.get(2)));
	}

	// 19 + 23 ------------------------------------------------------------
	@Test
	public void collapseDefaults_currentExpandedWithShared5ColumnGrid_olderCollapsed()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		v.render(slayerSnapshot("Task 3", 3, false));
		assertTrue(expanded(v, 3));
		assertFalse(expanded(v, 2));
		assertFalse(expanded(v, 1));
		GridLayout layout = (GridLayout) gridIn(cards(v).get(0)).getLayout();
		assertEquals(LootGridCell.GRID_COLUMNS, layout.getColumns());
		assertEquals(5, layout.getColumns());
		assertTrue(gridIn(cards(v).get(0)).getComponent(0) instanceof LootGridCell);
	}

	// 20 + 22 ------------------------------------------------------------
	@Test
	public void collapseState_survivesOrdinaryRerenders_includingManuallyCollapsedCurrent()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		Session session = slayerSession("Task 3");
		v.render(snapshotOf(session, 3, false));
		v.toggleSlayerTaskCard(1); // reopen an old task
		v.toggleSlayerTaskCard(3); // collapse the current task
		for (int i = 0; i < 3; i++)
		{
			v.render(snapshotOf(session, 3, false)); // the 1-second poll: a fresh snapshot of the SAME session
		}
		assertTrue(expanded(v, 1));
		assertFalse(expanded(v, 2));
		assertFalse("manual collapse of the current task sticks", expanded(v, 3));
	}

	// 21 -----------------------------------------------------------------
	@Test
	public void newAssignment_autoCollapsesPrevious_expandsNew_keepsOlderOverrides()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		Session session = slayerSession("Task 2");
		v.render(snapshotOf(session, 2, false));
		v.toggleSlayerTaskCard(1);
		assertTrue(expanded(v, 2));
		assertTrue(expanded(v, 1));

		v.render(snapshotOf(session, 3, false)); // same session, new assignment instance #3
		assertTrue("new current expanded", expanded(v, 3));
		assertFalse("previous current auto-collapsed", expanded(v, 2));
		assertTrue("an older task the player opened stays open", expanded(v, 1));
	}

	@Test
	public void differentSession_resetsCollapseState()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		v.render(slayerSnapshot("Task 2", 2, false));
		v.toggleSlayerTaskCard(1);
		v.render(slayerSnapshot("Task 2", 2, false)); // a DIFFERENT session (new engine -> new sessionId)
		assertFalse(expanded(v, 1));
		assertTrue(expanded(v, 2));
	}

	// 24 -----------------------------------------------------------------
	@Test
	public void sessionWideLootHeader_unchangedByCollapseState()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		CurrentSessionSnapshot snap = slayerSnapshot("Task 3", 3, false);
		v.render(snap);
		String before = headerTexts(v);
		v.toggleSlayerTaskCard(3);
		v.toggleSlayerTaskCard(1);
		assertEquals(before, headerTexts(v));
		assertTrue("Slayer's assignment-card section is TASK HISTORY", before.contains("TASK HISTORY"));
		assertTrue(!before.contains("LOOT"));
		assertEquals("session-wide entries cover every assignment", 2, snap.getLootEntries().size());
		assertEquals(6L + 60L, snap.getLootEntries().stream().mapToLong(CurrentSessionSnapshot.LootEntry::getQuantity).sum());
	}

	/** Texts of the LOOT section's own header row (first child of the section). */
	private static String headerTexts(CurrentSessionView v)
	{
		JPanel card = cards(v).get(0);
		Container section = card.getParent();
		Container headerRow = (Container) section.getComponent(0);
		StringBuilder sb = new StringBuilder();
		for (Component c : headerRow.getComponents())
		{
			if (c instanceof JLabel)
			{
				sb.append(((JLabel) c).getText()).append('|');
			}
		}
		return sb.toString();
	}

	// 25 -----------------------------------------------------------------
	@Test
	public void xpLootOrder_andSixPxGap_unchangedWithTheLedger()
	{
		for (CurrentSessionSectionOrder order : CurrentSessionSectionOrder.values())
		{
			CurrentSessionView v = view(order);
			v.render(slayerSnapshot("Task 2", 2, true));
			Container content = cards(v).get(0).getParent().getParent();
			int lootIdx = indexOf(content, cards(v).get(0).getParent());
			int last = content.getComponentCount() - 1;
			if (order == CurrentSessionSectionOrder.XP_FIRST)
			{
				assertEquals("loot is last", last, lootIdx);
				assertGap(content.getComponent(lootIdx - 1));
				assertFalse(content.getComponent(lootIdx - 2) instanceof Box.Filler);
			}
			else
			{
				assertEquals("one XP card after loot + gap", last, lootIdx + 2);
				assertGap(content.getComponent(lootIdx + 1));
			}
		}
	}

	private static void assertGap(Component c)
	{
		assertTrue(c instanceof Box.Filler);
		assertEquals(CurrentSessionView.SECTION_GAP, c.getPreferredSize().height);
		assertEquals(6, c.getPreferredSize().height);
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
		return -1;
	}

	// 26 -----------------------------------------------------------------
	@Test
	public void nonSlayerSession_lootRendersExactlyAsBefore_singleGridNoLedger()
	{
		Session s = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.BOSSING, "kraken", "Kraken"), T0).getCurrent();
		SessionAggregates.LootDropGroup g = new SessionAggregates.LootDropGroup();
		g.setSourceName("Kraken");
		g.getItems().add(item("Bones", 1));
		s.getAggregates().getLootDrops().add(g);
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0);
		assertTrue(snap.getSlayerAssignments().isEmpty());

		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		v.render(snap);
		assertTrue(cards(v).isEmpty());
		JPanel grid = findGrid(v);
		assertNotNull("the single session grid", grid);
		assertEquals(5, ((GridLayout) grid.getLayout()).getColumns());
	}

	@Test
	public void slayerLedgerAtZeroLoot_stillRendersTheCurrentAssignmentCard()
	{
		Session s = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.SLAYER, "kalphites", "Kalphites"), T0).getCurrent();
		SessionAggregates.SlayerAssignmentRecord a = new SessionAggregates.SlayerAssignmentRecord();
		a.setOrdinal(1);
		a.setTaskName("Kalphites");
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(a);
		s.getAggregates().setSlayerAssignments(ledger);
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0));
		assertEquals(1, cards(v).size());
		assertNull("no grid for an empty bucket", gridIn(cards(v).get(0)));
	}

	private static JPanel findGrid(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof GridLayout
				&& ((JPanel) c).getComponentCount() > 0 && ((JPanel) c).getComponent(0) instanceof LootGridCell)
			{
				return (JPanel) c;
			}
			if (c instanceof Container)
			{
				JPanel nested = findGrid((Container) c);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- end reason / xN

	private static CurrentSessionSnapshot endStateSnapshot()
	{
		Session s = slayerSession("Kalphites");
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		String[] names = {"Black Demons", "Scorpions", "Kalphites"};
		for (int o = 1; o <= 3; o++)
		{
			SessionAggregates.SlayerAssignmentRecord a = new SessionAggregates.SlayerAssignmentRecord();
			a.setOrdinal(o);
			a.setTaskName(names[o - 1]);
			ledger.add(a);
		}
		ledger.get(0).setInitialAmount(75);
		ledger.get(0).setLatestRemaining(58);
		ledger.get(0).setUnitsCompleted(17);
		ledger.get(0).setEndReason(com.osrstelemetry.plugin.session.SlayerAssignmentEndReason.REPLACED_EARLY);
		ledger.get(1).setInitialAmount(20);
		ledger.get(1).setLatestRemaining(0);
		ledger.get(1).setUnitsCompleted(20);
		ledger.get(1).setEndReason(com.osrstelemetry.plugin.session.SlayerAssignmentEndReason.COMPLETED);
		ledger.get(2).setInitialAmount(143);
		ledger.get(2).setLatestRemaining(143);
		ledger.get(2).setUnitsCompleted(0);
		s.getAggregates().setSlayerAssignments(ledger);
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0);
	}

	private static String titleOf(JPanel card)
	{
		Container header = (Container) card.getComponent(0);
		for (Component c : header.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getText().startsWith("\u25be") || c instanceof JLabel && ((JLabel) c).getText().startsWith("\u25b8"))
			{
				return ((JLabel) c).getText();
			}
		}
		throw new AssertionError("no title");
	}

	@Test
	public void cardTitles_taskNameXn_skippedOnlyForReplacedEarly_tooltipCarriesDetail()
	{
		CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
		v.render(endStateSnapshot());
		List<JPanel> cards = cards(v);
		assertEquals("\u25be Kalphites x0", titleOf(cards.get(0)));
		assertEquals("completed tasks carry no badge", "\u25b8 Scorpions x20", titleOf(cards.get(1)));
		assertEquals("\u25b8 Black Demons x17 \u00b7 Skipped", titleOf(cards.get(2)));

		CurrentSessionSnapshot.SlayerAssignmentView skipped = endStateSnapshot().getSlayerAssignments().get(2);
		String tip = CurrentSessionView.slayerTaskCardTooltip(skipped);
		assertTrue(tip, tip.contains("17 completed"));
		assertTrue("the assignment size is never shown", !tip.contains("/ 75"));
		assertTrue(tip, tip.contains("58 remaining"));
		assertTrue(tip, tip.contains("Skipped"));
	}

	@Test
	public void cardHeader_isSidebarSafe_atNarrowWidth_valueNeverPushedOffCard()
	{
		for (int width : new int[] {150, 213})
		{
			CurrentSessionView v = view(CurrentSessionSectionOrder.XP_FIRST);
			v.setSize(width, 2000);
			v.render(endStateSnapshot());
			layout(v);
			for (JPanel card : cards(v))
			{
				Container header = (Container) card.getComponent(0);
				for (Component c : header.getComponents())
				{
					assertTrue("header child inside the card at " + width, c.getX() >= 0 && c.getX() + c.getWidth() <= header.getWidth());
				}
				assertTrue("card inside the sidebar at " + width, card.getWidth() <= width);
			}
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

	// ---------------------------------------------------------------- Slayer XP scope in the view

	private static final class MutableScopeConfig implements OsrsTelemetryConfig
	{
		com.osrstelemetry.plugin.SlayerXpScope scope = com.osrstelemetry.plugin.SlayerXpScope.ALL_TASKS;

		@Override
		public com.osrstelemetry.plugin.SlayerXpScope slayerXpScope()
		{
			return scope;
		}
	}

	private static Set<String> labelTexts(Container root, Set<String> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null)
			{
				out.add(((JLabel) c).getText());
			}
			if (c instanceof Container)
			{
				labelTexts((Container) c, out);
			}
		}
		return out;
	}

	@Test
	public void xpScope_switchesLive_nonDestructively_andOnlyForSlayer()
	{
		Session s = slayerSession("Kalphites");
		s.getAggregates().getXpGainedBySkill().put("DEFENCE", 120_000L);
		SessionAggregates.SlayerAssignmentRecord r = new SessionAggregates.SlayerAssignmentRecord();
		r.setOrdinal(1);
		r.setTaskName("Kalphites");
		r.setXpGainedBySkill(new java.util.LinkedHashMap<>());
		r.getXpGainedBySkill().put("DEFENCE", 18_000L);
		r.setActiveDurationStartMillis(0L);
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(r);
		s.getAggregates().setSlayerAssignments(ledger);
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0);

		MutableScopeConfig cfg = new MutableScopeConfig();
		CurrentSessionView v = new CurrentSessionView(null, null, null, cfg);
		v.render(snap);
		assertTrue(labelTexts(v, new java.util.HashSet<>()).contains("+120,000 XP"));
		cfg.scope = com.osrstelemetry.plugin.SlayerXpScope.CURRENT_TASK;
		v.render(snap);
		assertTrue(labelTexts(v, new java.util.HashSet<>()).contains("+18,000 XP"));
		cfg.scope = com.osrstelemetry.plugin.SlayerXpScope.ALL_TASKS;
		v.render(snap);
		assertTrue("switching back restores the session value", labelTexts(v, new java.util.HashSet<>()).contains("+120,000 XP"));
		assertEquals(Long.valueOf(120_000L), s.getAggregates().getXpGainedBySkill().get("DEFENCE"));

		Session boss = new SessionLifecycleEngine().onQualifyingActivity(new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah"), T0).getCurrent();
		boss.getAggregates().getXpGainedBySkill().put("RANGED", 5_000L);
		cfg.scope = com.osrstelemetry.plugin.SlayerXpScope.CURRENT_TASK;
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(boss, null, null), T0));
		assertTrue("non-Slayer sessions ignore the scope", labelTexts(v, new java.util.HashSet<>()).contains("+5,000 XP"));
	}
}
