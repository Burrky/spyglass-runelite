package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.CurrentSessionSectionOrder;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.SlayerXpScope;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JSeparator;
import org.junit.Test;

/**
 * SLAYER XP SCOPE = CURRENT TASK in the real (headless) Current Session
 * view: which skill cards render, and that an empty Current Task XP set
 * leaves NO XP container and NO stray SECTION_GAP in either XP/Loot order.
 *
 * The rendered body (everything after the last separator -- the Slayer
 * dashboard's -- + its 6px spacer) is reduced to tokens: S = the in-section
 * Current Task / All Tasks selector row (its own 4px spacer is part of it),
 * N = "No XP on this task yet", X = one skill card, G = a 6px strut,
 * L = the task-history (loot) section.
 */
public class CurrentSessionViewCurrentTaskXpLayoutTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final Pattern XP_LABEL = Pattern.compile("^\\+[\\d,]+ XP$");

	private static final class Cfg implements OsrsTelemetryConfig
	{
		SlayerXpScope scope = SlayerXpScope.CURRENT_TASK;
		CurrentSessionSectionOrder order = CurrentSessionSectionOrder.XP_FIRST;

		@Override
		public SlayerXpScope slayerXpScope()
		{
			return scope;
		}

		@Override
		public CurrentSessionSectionOrder currentSessionSectionOrder()
		{
			return order;
		}
	}

	/** Slayer session: whole-session XP in 6 skills; the current assignment has `taskXp`; optional loot. */
	private static CurrentSessionSnapshot slayer(Map<String, Long> taskXp, boolean withLoot)
	{
		Session s = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.SLAYER, "kalphites", "Kalphites"), T0).getCurrent();
		Map<String, Long> session = s.getAggregates().getXpGainedBySkill();
		session.put("DEFENCE", 2000L);
		session.put("HUNTER", 10L);
		session.put("MAGIC", 50L);
		session.put("PRAYER", 5L);
		session.put("STRENGTH", 900L);
		session.put("RANGED", 5000L);
		SessionAggregates.SlayerAssignmentRecord r = new SessionAggregates.SlayerAssignmentRecord();
		r.setOrdinal(1);
		r.setTaskName("Kalphites");
		r.setXpGainedBySkill(new LinkedHashMap<>(taskXp));
		r.setActiveDurationStartMillis(0L);
		if (withLoot)
		{
			SessionAggregates.LootItemAggregate i = new SessionAggregates.LootItemAggregate();
			i.setItemName("Bones");
			i.setQuantity(1);
			r.getItems().add(i);
			SessionAggregates.LootDropGroup g = new SessionAggregates.LootDropGroup();
			g.setSourceName("Kalphite Worker");
			g.getItems().add(i);
			s.getAggregates().getLootDrops().add(g);
		}
		List<SessionAggregates.SlayerAssignmentRecord> ledger = new ArrayList<>();
		ledger.add(r);
		s.getAggregates().setSlayerAssignments(ledger);
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, null), T0);
	}

	private static Map<String, Long> xp(Object... skillQty)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (int i = 0; i < skillQty.length; i += 2)
		{
			m.put((String) skillQty[i], ((Number) skillQty[i + 1]).longValue());
		}
		return m;
	}

	private static String body(Cfg cfg, CurrentSessionSnapshot snap) throws Exception
	{
		CurrentSessionView v = new CurrentSessionView(null, null, null, cfg);
		v.render(snap);
		java.lang.reflect.Field f = CurrentSessionView.class.getDeclaredField("content");
		f.setAccessible(true);
		Container content = (Container) f.get(v);
		int sep = -1;
		for (int i = 0; i < content.getComponentCount(); i++)
		{
			if (content.getComponent(i) instanceof JSeparator)
			{
				sep = i;
			}
		}
		assertTrue("header separator present", sep >= 0);
		StringBuilder sb = new StringBuilder();
		for (int i = sep + 2; i < content.getComponentCount(); i++)
		{
			Component c = content.getComponent(i);
			if (c instanceof Box.Filler)
			{
				if (sb.length() > 0 && sb.charAt(sb.length() - 1) == 'S' && c.getPreferredSize().height == 4)
				{
					continue;
				}
				assertEquals("only 6px struts between body blocks", CurrentSessionView.SECTION_GAP, c.getPreferredSize().height);
				sb.append('G');
			}
			else if (hasNamed(c, "xpScope:"))
			{
				sb.append('S');
			}
			else if (hasNamed(c, CurrentSessionView.XP_SCOPE_EMPTY_NAME))
			{
				sb.append('N');
			}
			else
			{
				sb.append(hasXpLabel(c) ? 'X' : 'L');
			}
		}
		return sb.toString();
	}

	private static boolean hasNamed(Component c, String prefix)
	{
		if (c.getName() != null && c.getName().startsWith(prefix))
		{
			return true;
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				if (hasNamed(k, prefix))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasXpLabel(Component c)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null && XP_LABEL.matcher(((JLabel) c).getText()).matches())
		{
			return true;
		}
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				if (hasXpLabel(k))
				{
					return true;
				}
			}
		}
		return false;
	}

	// 1 + 9 ---------------------------------------------------------------
	@Test
	public void currentTask_noTaskXp_isLootOnly_inBothOrders_noEmptyXpContainerNoStrayGap() throws Exception
	{
		Cfg cfg = new Cfg();
		for (CurrentSessionSectionOrder order : CurrentSessionSectionOrder.values())
		{
			cfg.order = order;
			// The XP section keeps only its scope selector + a quiet "no XP yet"
			// line (so All Tasks stays one click away); never empty skill cards.
			String expected = order == CurrentSessionSectionOrder.XP_FIRST ? "SNGL" : "LGSN";
			assertEquals(order + ": selector + no-XP line, task history", expected, body(cfg, slayer(xp(), true)));
			assertEquals(order + ": zero-loot task card too", expected, body(cfg, slayer(xp(), false)));
		}
	}

	// 2 + 3 + 10 ----------------------------------------------------------
	@Test
	public void currentTask_onlyNonZeroCards_normalOrderAndSixPxGapResume() throws Exception
	{
		Cfg cfg = new Cfg();
		CurrentSessionSnapshot one = slayer(xp("RANGED", 136L), true);
		CurrentSessionSnapshot three = slayer(xp("RANGED", 136L, "HITPOINTS", 46L, "SLAYER", 34L, "DEFENCE", 0L), true);

		cfg.order = CurrentSessionSectionOrder.XP_FIRST;
		assertEquals("first task XP: selector, one card, gap, loot", "SXGL", body(cfg, one));
		assertEquals("three cards (a 0-XP skill never renders), gap, loot", "SXGXGXGL", body(cfg, three));
		cfg.order = CurrentSessionSectionOrder.LOOT_FIRST;
		assertEquals("LGSX", body(cfg, one));
		assertEquals("LGSXGXGX", body(cfg, three));
		cfg.order = CurrentSessionSectionOrder.XP_FIRST;
		assertEquals("zero-loot task card still follows the XP block", "SXGXGXGL", body(cfg, slayer(xp("RANGED", 136L, "HITPOINTS", 46L, "SLAYER", 34L), false)));
	}

	// 4 + 7 + 8 -----------------------------------------------------------
	@Test
	public void scopeSwitchesLive_allTasksRestoresWholeSession_currentTaskShowsOnlyAssignmentCards() throws Exception
	{
		Cfg cfg = new Cfg();
		CurrentSessionSnapshot snap = slayer(xp("RANGED", 136L), true);
		cfg.scope = SlayerXpScope.ALL_TASKS;
		assertEquals("All Tasks: all 6 session skills, unchanged", "SXGXGXGXGXGXGL", body(cfg, snap));
		cfg.scope = SlayerXpScope.CURRENT_TASK;
		assertEquals("Current Task: only the task's skill", "SXGL", body(cfg, snap));
		cfg.scope = SlayerXpScope.ALL_TASKS;
		assertEquals("switching back is immediate", "SXGXGXGXGXGXGL", body(cfg, snap));
	}

	// 11 ------------------------------------------------------------------
	@Test
	public void nonSlayerSession_ignoresScope_rendersEveryGainingSkill() throws Exception
	{
		Session boss = new SessionLifecycleEngine().onQualifyingActivity(
			new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah"), T0).getCurrent();
		boss.getAggregates().getXpGainedBySkill().put("RANGED", 5000L);
		boss.getAggregates().getXpGainedBySkill().put("HITPOINTS", 1600L);
		CurrentSessionSnapshot snap = CurrentSessionSnapshot.from(SessionSnapshot.capture(boss, null, null), T0);
		assertEquals(snap.getXpEntries(), snap.getXpEntries(SlayerXpScope.CURRENT_TASK));
		Cfg cfg = new Cfg();
		for (SlayerXpScope scope : SlayerXpScope.values())
		{
			cfg.scope = scope;
			assertEquals(scope + ": both cards, no Loot section, no trailing gap", "XGX", body(cfg, snap));
		}
	}
}
