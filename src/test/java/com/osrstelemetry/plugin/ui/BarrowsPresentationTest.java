package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.activity.BarrowsBrother;
import com.osrstelemetry.plugin.activity.BarrowsRunProgress;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import org.junit.Test;

/**
 * Barrows / structured-activity presentation: the activity (or boss) owns
 * the Current Session title, never a skill it happens to produce; Barrows
 * gets an activity-native block (current-run brothers from the game's own
 * varbits, session chests); Slayer, skilling and generic combat keep their
 * existing titles.
 */
public class BarrowsPresentationTest
{
	private static final Gson GSON = new Gson();
	private static final OsrsTelemetryConfig CONFIG = new OsrsTelemetryConfig()
	{
	};
	private static final Instant NOW = Instant.parse("2026-09-20T10:50:00Z");

	private static Session session(String type, String key, String name, String xpJson, String extra)
	{
		return GSON.fromJson(("{'sessionId':'s-" + key + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'" + name + "'},'state':'ACTIVE','startedAt':'2026-09-20T10:00:00Z',"
			+ "'lastActiveAt':'2026-09-20T10:42:00Z','accumulatedActiveDurationMillis':2520000,"
			+ "'aggregates':{'xpGainedBySkill':" + xpJson + ",'lootDrops':[]" + extra + "}}").replace('\'', '"'), Session.class);
	}

	private static Session barrows(String xpJson)
	{
		return session("STRUCTURED_ACTIVITY", "barrows", "Barrows", xpJson,
			",'activityCompletions':{'activityKey':'barrows','sessionCompletions':7,'latestAuthoritativeValue':312}");
	}

	private static CurrentSessionSnapshot snap(Session s)
	{
		return CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW);
	}

	private static BarrowsRunProgress progress(BarrowsBrother... cleared)
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		for (BarrowsBrother b : cleared)
		{
			varbits.put(b.getKilledVarbit(), 1);
		}
		return BarrowsRunProgress.read(id -> varbits.getOrDefault(id, 0));
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

	private static List<String> chipNames(Container root)
	{
		List<String> out = new ArrayList<>();
		for (Component c : all(root))
		{
			if (c.getName() != null && c.getName().startsWith(CurrentSessionView.BARROWS_BROTHER_PREFIX))
			{
				out.add(c.getName().substring(CurrentSessionView.BARROWS_BROTHER_PREFIX.length()));
			}
		}
		return out;
	}

	private static String headline(CurrentSessionView view)
	{
		return ((JLabel) named(view, CurrentSessionView.HEADLINE_NAME)).getText();
	}

	// ------------------------------------------------------------------ title hierarchy

	@Test
	public void barrowsOwnsTheTitle_evenWithHitpointsOrMagicXp()
	{
		assertEquals("Barrows", snap(barrows("{}")).getHeadline());
		assertEquals("Barrows", snap(barrows("{'HITPOINTS':12000}")).getHeadline());
		assertEquals("Barrows", snap(barrows("{'MAGIC':40000,'HITPOINTS':12000}")).getHeadline());
		assertTrue(snap(barrows("{'MAGIC':40000}")).isActivityHeadline());
		// The skill is still the XP section's business.
		assertEquals("MAGIC", snap(barrows("{'MAGIC':40000,'HITPOINTS':12000}")).getXpEntries().get(0).getSkill());
	}

	@Test
	public void bossOwnsTheTitle_skillingAndCombatKeepSkillTitles_slayerUnchanged()
	{
		assertEquals("Zulrah", snap(session("BOSSING", "zulrah", "Zulrah", "{'MAGIC':30000,'HITPOINTS':10000}", "")).getHeadline());
		assertEquals("Mining", snap(session("SKILLING", "mining", "Mining", "{'MINING':5000}", "")).getHeadline());
		assertEquals("Strength", snap(session("COMBAT", "hill giant", "Hill Giant", "{'STRENGTH':3000,'HITPOINTS':1000}", "")).getHeadline());
		assertEquals("generic combat on HP-only XP keeps its generic skill title", "Hitpoints",
			snap(session("COMBAT", "hill giant", "Hill Giant", "{'HITPOINTS':1000}", "")).getHeadline());
		assertEquals("Slayer", snap(session("SLAYER", "gargoyles", "Gargoyles", "{'SLAYER':3000,'HITPOINTS':1000}", "")).getHeadline());
		assertFalse(snap(session("SLAYER", "gargoyles", "Gargoyles", "{'SLAYER':3000}", "")).isActivityHeadline());
	}

	@Test
	public void viewTitle_isBarrows_andNeverRepeatedAsTheActivityLine()
	{
		for (String xp : new String[] {"{'HITPOINTS':12000}", "{'MAGIC':40000,'HITPOINTS':12000}"})
		{
			CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
			view.render(snap(barrows(xp)));
			assertEquals("Barrows", headline(view));
			assertNull("no duplicate 'Barrows' activity line", named(view, CurrentSessionView.ACTIVITY_LINE_NAME));
		}
	}

	@Test
	public void historyTitle_isTheActivity()
	{
		String json = GSON.toJson(barrows("{'HITPOINTS':12000}"))
			.replace("\"state\":\"ACTIVE\"", "\"state\":\"FINALIZED\",\"finalizedAt\":\"2026-09-20T11:00:00Z\"");
		Session finalized = GSON.fromJson(json, Session.class);
		HistoryEntry entry = HistoryEntry.from(finalized);
		assertNotNull(entry);
		assertEquals("Barrows", HistoryEntry.displayTitle(finalized.getActivityIdentity(), 0));
	}

	// ------------------------------------------------------------------ Barrows block

	@Test
	public void barrowsBlock_showsBrothersFromTheGame_andChestsOnce()
	{
		CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
		view.setBarrowsProgressSupplier(() -> progress(BarrowsBrother.AHRIM, BarrowsBrother.DHAROK, BarrowsBrother.KARIL, BarrowsBrother.VERAC));
		view.render(snap(barrows("{'HITPOINTS':12000}")));

		assertNotNull(named(view, CurrentSessionView.BARROWS_BLOCK_NAME));
		assertEquals("Brothers 4 / 6", ((JLabel) named(view, CurrentSessionView.BARROWS_BROTHER_COUNT_NAME)).getText());
		List<String> chips = chipNames(view);
		assertEquals(6, chips.size());
		assertTrue(chips.contains("AHRIM:cleared"));
		assertTrue(chips.contains("DHAROK:cleared"));
		assertTrue(chips.contains("GUTHAN:remaining"));
		assertTrue(chips.contains("KARIL:cleared"));
		assertTrue(chips.contains("TORAG:remaining"));
		assertTrue(chips.contains("VERAC:cleared"));
		assertEquals("chests appear once, inside the Barrows block", 1, countNamed(view, CurrentSessionView.COMPLETION_METRIC_NAME));
		assertTrue(((JLabel) named(view, CurrentSessionView.COMPLETION_METRIC_NAME)).getText().startsWith("Chests: 7"));
	}

	@Test
	public void unknownRunState_isOmitted_neverGuessed_butChestsStay()
	{
		CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
		view.setBarrowsProgressSupplier(() -> null); // not read yet / logged out
		view.render(snap(barrows("{'HITPOINTS':12000}")));
		assertNull(named(view, CurrentSessionView.BARROWS_BROTHER_COUNT_NAME));
		assertTrue(chipNames(view).isEmpty());
		assertEquals(1, countNamed(view, CurrentSessionView.COMPLETION_METRIC_NAME));
	}

	@Test
	public void otherStructuredActivities_keepTheirHeaderMetric_andGetNoBarrowsBlock()
	{
		CurrentSessionView view = new CurrentSessionView(null, null, null, CONFIG);
		view.setBarrowsProgressSupplier(() -> progress(BarrowsBrother.AHRIM));
		view.render(snap(session("STRUCTURED_ACTIVITY", "guardians_of_the_rift", "Guardians of the Rift", "{'RUNECRAFT':52000}",
			",'activityCompletions':{'activityKey':'guardians_of_the_rift','sessionCompletions':8,'latestAuthoritativeValue':1208}")));
		assertEquals("Guardians of the Rift", headline(view));
		assertNull(named(view, CurrentSessionView.BARROWS_BLOCK_NAME));
		assertEquals("GotR shows its games in its own SESSION grid", 1, countNamed(view, CurrentSessionView.GOTR_SESSION_GRID_NAME));
	}

	@Test
	public void runProgress_readsOnlyTheGamesVarbits()
	{
		assertEquals(0, progress().getClearedCount());
		assertEquals(1, progress(BarrowsBrother.TORAG).getClearedCount());
		assertEquals(6, progress(BarrowsBrother.values()).getClearedCount());
		assertEquals(progress(BarrowsBrother.TORAG), progress(BarrowsBrother.TORAG, BarrowsBrother.TORAG));
		for (BarrowsBrother b : BarrowsBrother.values())
		{
			assertEquals(b, BarrowsBrother.forKilledVarbit(b.getKilledVarbit()));
		}
		assertNull(BarrowsBrother.forKilledVarbit(463)); // the monster-points varbit is not a brother
	}

	@Test
	public void schematic_placesEachMoundInItsOwnCell()
	{
		boolean[] used = new boolean[9];
		for (BarrowsBrother b : BarrowsBrother.values())
		{
			int cell = b.getSchematicRow() * 3 + b.getSchematicColumn();
			assertFalse("one brother per cell", used[cell]);
			used[cell] = true;
		}
	}
}
