package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

/**
 * TASK COUNT vs ACTUAL KILLS as the player sees them, rendered by the real
 * Current Session / History code at 150 / 180 / 213 / 260 px: the card
 * header keeps the task count ("Nechryael x170"), a line under it says the
 * actual kills ("235 actual kills") and superiors ("1 superior"); expanded,
 * TASK (TASK COUNT | ACTUAL KILLS), LOOT (TOTAL | LOOT / KILL per actual
 * kill), SUPERIORS and KILL BREAKDOWN; one compact superior line under the
 * live 3 x 2 grid only once one was killed; History's session superiors;
 * older records unchanged. No clipping, no horizontal scroll.
 */
public class SlayerActualKillsPresentationTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final int[] WIDTHS = {150, 180, 213, 260};
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};

	private static SessionAggregates.LootItemAggregate item(String name, long qty)
	{
		SessionAggregates.LootItemAggregate i = new SessionAggregates.LootItemAggregate();
		i.setItemName(name);
		i.setQuantity(qty);
		return i;
	}

	private static Map<String, Long> counts(Object... pairs)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
		}
		return m;
	}

	/** A Nechryael-style record: units, plus tracked deaths (null = an older record without tracking). */
	private static SessionAggregates.SlayerAssignmentRecord record(int ordinal, String task, int units, Map<String, Long> kills,
		Map<String, Long> superiors, Map<String, Long> subordinates)
	{
		SessionAggregates.SlayerAssignmentRecord a = new SessionAggregates.SlayerAssignmentRecord();
		a.setOrdinal(ordinal);
		a.setTaskName(task);
		a.setAssignmentKey(task.toLowerCase());
		a.setUnitsCompleted(units);
		a.setSlaughterProcs(kills == null ? null : 0);
		a.setExpeditiousProcs(kills == null ? null : 0);
		a.setKillsByNpc(kills);
		a.setSuperiorsByNpc(superiors);
		a.setSubordinatesByNpc(subordinates);
		a.setLatestRemaining(0);
		a.getItems().add(item("Bones", ordinal));
		return a;
	}

	private static SessionAggregates.SlayerAssignmentRecord nechryael(int ordinal)
	{
		return record(ordinal, "Nechryael", 170, counts("Nechryael", 169, "Death spawn", 64, "Greater Nechryael", 1, "Nechryarch", 1),
			counts("Nechryarch", 1), counts("Death spawn", 64));
	}

	private static Session slayer(List<SessionAggregates.SlayerAssignmentRecord> ledger)
	{
		Session s = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.SLAYER, ledger.get(ledger.size() - 1).getTaskName().toLowerCase(),
				ledger.get(ledger.size() - 1).getTaskName()), T0).getCurrent();
		s.getAggregates().setSlayerAssignments(new ArrayList<>(ledger));
		s.getAggregates().setLatestSlayerCurrentRemaining(5);
		ledger.get(ledger.size() - 1).setLatestRemaining(5);
		return s;
	}

	private static CurrentSessionView render(Session s)
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, CONFIG);
		v.setSize(213, 2000);
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0));
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

	private static String text(Container root, String name)
	{
		Component c = named(root, name);
		if (c instanceof JLabel)
		{
			return ((JLabel) c).getText().replaceAll("<[^>]*>", "").trim();
		}
		if (c instanceof Container)
		{
			StringBuilder sb = new StringBuilder();
			for (Component k : all((Container) c))
			{
				if (k instanceof JLabel && ((JLabel) k).getText() != null)
				{
					sb.append(sb.length() == 0 ? "" : " | ").append(((JLabel) k).getText().replaceAll("<[^>]*>", "").trim());
				}
			}
			return sb.toString();
		}
		return null;
	}

	private static String labels(Container root)
	{
		StringBuilder sb = new StringBuilder();
		for (Component k : all(root))
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null)
			{
				sb.append(((JLabel) k).getText()).append(" | ");
			}
		}
		return sb.toString();
	}

	private static String cell(Container root, String grid, String caption)
	{
		Component g = named(root, grid);
		Component c = g == null ? null : named((Container) g, SlayerStatGrid.VALUE_PREFIX + caption);
		return c == null ? null : ((JLabel) c).getText();
	}

	private static JPanel card(Container root, String prefix, int ordinal)
	{
		return (JPanel) named(root, prefix + ordinal);
	}

	private static void fits(Container v, String what)
	{
		for (int width : WIDTHS)
		{
			List<String> problems = SidebarClippingRegressionTest.audit(v, width);
			assertTrue(what + " @" + width + ": " + problems, problems.isEmpty());
		}
	}

	// ---- Current Session ---------------------------------------------------------------------------

	@Test
	public void taskCountAndActualKills_bothVisible_collapsedAndExpanded_superiorLine()
	{
		CurrentSessionView v = render(slayer(Collections.singletonList(nechryael(1))));
		JPanel card = card(v, CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX, 1);
		assertNotNull(card);
		assertTrue("the header keeps the task count", labels(card).contains("Nechryael x170"));
		assertEquals("235 actual kills", text(card, SlayerTaskCards.SUB_LINE_PREFIX + 0));
		assertEquals("1 superior", text(card, SlayerTaskCards.SUB_LINE_PREFIX + 1));
		// The current card is expanded by default: TASK and the breakdown.
		assertEquals("170", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.TASK_COUNT_CELL));
		assertEquals("235", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.ACTUAL_KILLS_CELL));
		assertEquals("Nechryarch | 1", text(card, SlayerTaskCards.DETAIL_SUPERIOR_PREFIX + "Nechryarch"));
		assertEquals("Death spawn | 64", text(card, SlayerTaskCards.DETAIL_BREAKDOWN_PREFIX + "Death spawn"));
		assertEquals("Greater Nechryael | 1", text(card, SlayerTaskCards.DETAIL_BREAKDOWN_PREFIX + "Greater Nechryael"));
		assertEquals("Total | 235", text(card, SlayerTaskCards.DETAIL_BREAKDOWN_TOTAL));
		// The live grid: KILLS = actual kills; one compact superior line, the 3 x 2 grid untouched.
		assertEquals("235", cell(v, "slayerStatGrid", CurrentSessionView.KILLS_CELL));
		assertEquals("Superiors: 1 this task • 1 session", text(v, CurrentSessionView.SLAYER_SUPERIORS_NAME));
		fits(v, "170 / 235 + superior");
	}

	@Test
	public void lootPerKill_inTheExpandedCard_isPerActualKill()
	{
		JPanel details = SlayerTaskCards.details(170, 235, 1_633_250L, counts("Nechryarch", 1),
			counts("Nechryael", 169, "Death spawn", 64, "Greater Nechryael", 1, "Nechryarch", 1), true);
		assertEquals("1.63M", cell(details, SlayerTaskCards.DETAIL_LOOT_GRID, SlayerTaskCards.LOOT_TOTAL_CELL));
		assertEquals("6,950 gp", cell(details, SlayerTaskCards.DETAIL_LOOT_GRID, SlayerTaskCards.LOOT_PER_KILL_CELL));
		JPanel host = new JPanel();
		host.setLayout(new javax.swing.BoxLayout(host, javax.swing.BoxLayout.Y_AXIS));
		host.add(details);
		fits(host, "loot facts");
	}

	@Test
	public void oneToOneTask_noSuperior_noSuperiorLines()
	{
		SessionAggregates.SlayerAssignmentRecord kalphites = record(1, "Kalphites", 100, counts("Kalphite Worker", 100), counts(),
			counts());
		CurrentSessionView v = render(slayer(Collections.singletonList(kalphites)));
		JPanel card = card(v, CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX, 1);
		assertEquals("100 actual kills", text(card, SlayerTaskCards.SUB_LINE_PREFIX + 0));
		assertNull("no superior line", named(card, SlayerTaskCards.SUB_LINE_PREFIX + 1));
		assertNull(named(v, CurrentSessionView.SLAYER_SUPERIORS_NAME));
		assertNull("one kind of NPC: no breakdown", named(card, SlayerTaskCards.DETAIL_BREAKDOWN_TOTAL));
		assertEquals("100", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.ACTUAL_KILLS_CELL));
		fits(v, "1:1");
	}

	@Test
	public void severalSessionSuperiors_currentTaskNone_lineShowsBoth()
	{
		SessionAggregates.SlayerAssignmentRecord first = nechryael(1);
		first.setEndReason(SlayerAssignmentEndReason.COMPLETED);
		SessionAggregates.SlayerAssignmentRecord gargs = record(2, "Gargoyles", 80, counts("Gargoyle", 80, "Marble gargoyle", 2),
			counts("Marble gargoyle", 2), counts());
		SessionAggregates.SlayerAssignmentRecord now = record(3, "Kalphites", 10, counts("Kalphite Worker", 10), counts(), counts());
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(first);
		ledger.add(gargs);
		ledger.add(now);
		CurrentSessionView v = render(slayer(ledger));
		assertEquals("Superiors: 0 this task • 3 session", text(v, CurrentSessionView.SLAYER_SUPERIORS_NAME));
		fits(v, "session superiors");
	}

	@Test
	public void incompleteBreakdown_isNeverShown_onlyTheTrustedTotal()
	{
		// Units say 120 targets died; encounter tracking only saw 100 of them (+10 summons): 130 actual, no breakdown.
		SessionAggregates.SlayerAssignmentRecord r = record(1, "Nechryael", 120, counts("Nechryael", 100, "Death spawn", 10), counts(),
			counts("Death spawn", 10));
		CurrentSessionView v = render(slayer(Collections.singletonList(r)));
		JPanel card = card(v, CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX, 1);
		assertEquals("130 actual kills", text(card, SlayerTaskCards.SUB_LINE_PREFIX + 0));
		assertNull(named(card, SlayerTaskCards.DETAIL_BREAKDOWN_TOTAL));
		fits(v, "incomplete breakdown");
	}

	@Test
	public void longTaskAndNpcNames_hugeCounts_fitEveryWidth()
	{
		SessionAggregates.SlayerAssignmentRecord r = record(1, "Crawling Hands and Other Very Long Task Name", 123_456,
			counts("Crawling Hand of the Extremely Long Name", 123_455, "Crushing hand", 1, "A Summoned Subordinate With A Long Name", 99_999),
			counts("Crushing hand", 1), counts("A Summoned Subordinate With A Long Name", 99_999));
		CurrentSessionView v = render(slayer(Collections.singletonList(r)));
		fits(v, "long names");
	}

	@Test
	public void olderRecord_withoutTracking_rendersAsBefore_noNewLinesNoZeroes()
	{
		SessionAggregates.SlayerAssignmentRecord old = record(1, "Nechryael", 170, null, null, null);
		old.setSlaughterProcs(null);
		old.setExpeditiousProcs(null);
		CurrentSessionView v = render(slayer(Collections.singletonList(old)));
		JPanel card = card(v, CurrentSessionView.SLAYER_TASK_CARD_NAME_PREFIX, 1);
		assertTrue(labels(card).contains("Nechryael x170"));
		assertNull("unknown kills are never a 0", named(card, SlayerTaskCards.SUB_LINE_PREFIX + 0));
		assertNull(named(v, CurrentSessionView.SLAYER_SUPERIORS_NAME));
		assertEquals("170", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.TASK_COUNT_CELL));
		assertEquals("-", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.ACTUAL_KILLS_CELL));
		fits(v, "old record");
	}

	// ---- History ---------------------------------------------------------------------------------

	private static Session finished(List<SessionAggregates.SlayerAssignmentRecord> ledger)
	{
		Session s = slayer(ledger);
		// History detail reads the persisted aggregates only.
		return s;
	}

	@Test
	public void history_collapsedAndExpandedCards_andSessionSuperiors()
	{
		SessionAggregates.SlayerAssignmentRecord first = nechryael(1);
		first.setEndReason(SlayerAssignmentEndReason.COMPLETED);
		SessionAggregates.SlayerAssignmentRecord gargs = record(2, "Gargoyles", 80, counts("Gargoyle", 80, "Marble gargoyle", 2),
			counts("Marble gargoyle", 2), counts());
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(first);
		ledger.add(gargs);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(finished(ledger)));
		JPanel card = card(d, HistoryDetailView.HISTORY_TASK_CARD_NAME_PREFIX, 1);
		assertNotNull(card);
		assertEquals("235 actual kills", text(card, SlayerTaskCards.SUB_LINE_PREFIX + 0));
		assertEquals("1 superior", text(card, SlayerTaskCards.SUB_LINE_PREFIX + 1));
		assertNull("collapsed", named(card, SlayerTaskCards.DETAIL_TASK_GRID));
		assertEquals("Superiors killed: 3 (Nechryarch 1 • Marble gargoyle 2)", text(d, HistoryDetailView.SLAYER_SUPERIORS_NAME));
		fits(d, "history collapsed");
		d.toggleSlayerTask(1);
		card = card(d, HistoryDetailView.HISTORY_TASK_CARD_NAME_PREFIX, 1);
		assertEquals("170", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.TASK_COUNT_CELL));
		assertEquals("235", cell(card, SlayerTaskCards.DETAIL_TASK_GRID, SlayerTaskCards.ACTUAL_KILLS_CELL));
		assertEquals("Total | 235", text(card, SlayerTaskCards.DETAIL_BREAKDOWN_TOTAL));
		assertEquals("Nechryarch | 1", text(card, SlayerTaskCards.DETAIL_SUPERIOR_PREFIX + "Nechryarch"));
		fits(d, "history expanded");
	}

	@Test
	public void history_oldRecords_noSuperiorLine_noKillLine()
	{
		SessionAggregates.SlayerAssignmentRecord a = record(1, "Nechryael", 170, null, null, null);
		a.setSlaughterProcs(null);
		a.setExpeditiousProcs(null);
		SessionAggregates.SlayerAssignmentRecord b = record(2, "Gargoyles", 80, null, null, null);
		b.setSlaughterProcs(null);
		b.setExpeditiousProcs(null);
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(a);
		ledger.add(b);
		HistoryDetailView d = new HistoryDetailView(null, null, null, CONFIG, null);
		d.render(HistoryDetailModel.from(finished(ledger)));
		assertNull(named(d, HistoryDetailView.SLAYER_SUPERIORS_NAME));
		assertNull(named(card(d, HistoryDetailView.HISTORY_TASK_CARD_NAME_PREFIX, 1), SlayerTaskCards.SUB_LINE_PREFIX + 0));
		fits(d, "history old");
	}
}
