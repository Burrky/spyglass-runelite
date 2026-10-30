package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.session.Session;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import org.junit.Test;

/** Pass 28: History detail keeps secondary sections (the starting loadout) collapsed until asked, by mouse or keyboard. */
public class HistoryDetailDisclosureTest
{
	private static Session session(String id, String provenance)
	{
		String loadout = "'startingLoadout':{'inventory':[{'slot':0,'itemId':995,'name':'Coins','quantity':1000}],"
			+ "'equipment':[{'slot':3,'itemId':4151,'name':'Abyssal whip','quantity':1,'equipmentSlot':'WEAPON'}],"
			+ "'capturedAt':'2026-10-27T11:59:00Z','provenance':'" + provenance + "'}";
		return new Gson().fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'COMBAT','activityKey':'goblin','displayName':'Goblin'},"
			+ "'state':'FINALIZED','startedAt':'2026-10-27T11:00:00Z','finalizedAt':'2026-10-27T12:00:00Z','accumulatedActiveDurationMillis':600000,"
			+ "'aggregates':{'xpGainedBySkill':{'ATTACK':400}}," + loadout + "}").replace('\'', '"'), Session.class);
	}

	private static Component named(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
			if (c instanceof Container)
			{
				Component f = named((Container) c, name);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	private static boolean hasText(Container root, String text)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null && ((JLabel) c).getText().contains(text))
			{
				return true;
			}
			if (c instanceof Container && hasText((Container) c, text))
			{
				return true;
			}
		}
		return false;
	}

	@Test
	public void loadout_collapsedUntilAsked_keyboardToo_andResetPerSession()
	{
		HistoryDetailView d = new HistoryDetailView(null, null, null, new OsrsTelemetryConfig()
		{
		}, null);
		d.render(HistoryDetailModel.from(session("a", "PRE_START")));
		DisclosureToggle t = (DisclosureToggle) named(d, HistoryDetailView.LOADOUT_TOGGLE_NAME);
		assertNotNull(t);
		assertTrue(t.isFocusable());
		assertFalse(hasText(d, "Inventory</") || hasText(d, "Equipment"));
		assertFalse(hasText(d, "Captured before"));

		// Space on the focused toggle, exactly as the keyboard sends it.
		Object key = t.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
		t.getActionMap().get(key).actionPerformed(new ActionEvent(t, ActionEvent.ACTION_PERFORMED, ""));
		assertTrue(((DisclosureToggle) named(d, HistoryDetailView.LOADOUT_TOGGLE_NAME)).isExpanded());
		assertTrue(hasText(d, "Equipment"));
		assertTrue(hasText(d, "Captured before"));

		// A price refresh redraw keeps it open; another session starts collapsed.
		d.render(HistoryDetailModel.from(session("a", "PRE_START")));
		assertTrue(((DisclosureToggle) named(d, HistoryDetailView.LOADOUT_TOGGLE_NAME)).isExpanded());
		d.render(HistoryDetailModel.from(session("b", "PRE_START")));
		assertFalse(((DisclosureToggle) named(d, HistoryDetailView.LOADOUT_TOGGLE_NAME)).isExpanded());
		assertFalse(hasText(d, "Equipment"));
	}

	@Test
	public void unavailableLoadout_saysSo_withoutAToggle()
	{
		HistoryDetailView d = new HistoryDetailView(null, null, null, new OsrsTelemetryConfig()
		{
		}, null);
		d.render(HistoryDetailModel.from(session("c", "UNAVAILABLE")));
		assertNull(named(d, HistoryDetailView.LOADOUT_TOGGLE_NAME));
		assertTrue(hasText(d, "Not captured for this session"));
	}
}
