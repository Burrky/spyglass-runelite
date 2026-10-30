package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import net.runelite.api.Experience;
import org.junit.Test;

/** Pass 28: the skill card's second metric in the real view -- saved choice, keyboard, readiness gate. */
public class XpCardViewTest
{
	private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

	private static CurrentSessionSnapshot snap(long activeMillis, long xp, long absoluteXp)
	{
		Session s = new Gson().fromJson(("{'sessionId':'x','activityIdentity':{'activityType':'SKILLING','activityKey':'cooking','displayName':'Cooking'},"
			+ "'state':'ACTIVE','startedAt':'2026-10-28T10:00:00Z','lastActiveAt':'2026-10-28T12:00:00Z','accumulatedActiveDurationMillis':" + activeMillis
			+ ",'aggregates':{'xpGainedBySkill':{'COOKING':" + xp + "},'lootDrops':[]}}").replace('\'', '"'), Session.class);
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s, null, Collections.singletonMap("COOKING", absoluteXp)), NOW);
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
			if (name.equals(c.getName()) && c instanceof JLabel)
			{
				return (JLabel) c;
			}
		}
		return null;
	}

	@Test
	public void secondMetric_followsTheSavedChoice_switchesByKeyboard_andWaitsForTheRate()
	{
		String[] saved = {null};
		CurrentSessionView v = new CurrentSessionView(null, null, null, new OsrsTelemetryConfig()
		{
		});
		v.setXpMetricStore(() -> saved[0], x -> saved[0] = x);
		long at = Experience.getXpForLevel(80) + 10_000;
		long left = Experience.getXpForLevel(81) - at;
		v.render(snap(3_600_000L, 60_000L, at));

		assertEquals("60,000 XP/hr", named(v, CurrentSessionView.XP_RATE_NAME).getText());
		JLabel second = named(v, CurrentSessionView.XP_SECOND_NAME);
		assertEquals("default: XP remaining", String.format("%,d XP to 81", left), second.getText());
		assertTrue(second.isFocusable());

		// Space on the focused metric, as the keyboard sends it: saved, and the card shows time to level.
		Object key = second.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("SPACE"));
		second.getActionMap().get(key).actionPerformed(new ActionEvent(second, ActionEvent.ACTION_PERFORMED, ""));
		assertEquals("TIME_TO_LEVEL", saved[0]);
		String eta = named(v, CurrentSessionView.XP_SECOND_NAME).getText();
		assertTrue(eta, eta.startsWith("~") && eta.endsWith("to 81"));

		// Before five minutes: no rate, so XP remaining whatever the choice; never an invented ETA.
		CurrentSessionView fresh = new CurrentSessionView(null, null, null, new OsrsTelemetryConfig()
		{
		});
		fresh.setXpMetricStore(() -> "TIME_TO_LEVEL", x -> { });
		fresh.render(snap(120_000L, 2_000L, at));
		assertEquals(String.format("%,d XP to 81", left), named(fresh, CurrentSessionView.XP_SECOND_NAME).getText());
		String rate = named(fresh, CurrentSessionView.XP_RATE_NAME).getText();
		assertTrue("no rate before five minutes: " + rate, !rate.matches("[0-9,]+ XP/hr"));

		// Max level: no second metric at all.
		CurrentSessionView max = new CurrentSessionView(null, null, null, new OsrsTelemetryConfig()
		{
		});
		max.render(snap(3_600_000L, 60_000L, Experience.getXpForLevel(99) + 5));
		assertNull(named(max, CurrentSessionView.XP_SECOND_NAME));
		assertNotNull(named(max, CurrentSessionView.XP_RATE_NAME));
	}
}
