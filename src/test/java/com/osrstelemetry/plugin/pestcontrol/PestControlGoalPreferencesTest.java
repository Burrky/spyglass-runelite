package com.osrstelemetry.plugin.pestcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.api.Skill;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Goal persistence: account-scoped planning state in its own file -- never
 * events.jsonl, never a session.
 */
public class PestControlGoalPreferencesTest
{
	private static final long A = 7_001L;
	private static final long B = 7_002L;
	private Path root;

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("pc-goal-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(root).deleteRecursively();
	}

	private static LocalStateStore store()
	{
		LocalStateStore store = new LocalStateStore(new Gson());
		store.start();
		return store;
	}

	@Test
	public void goalAndBalance_surviveARestart_perAccount()
	{
		LocalStateStore store = store();
		PestControlGoalPreferences prefs = new PestControlGoalPreferences(store);
		prefs.loadForAccount(A);
		PestControlGoal goal = PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.XP).withXpSkill(Skill.PRAYER).withXpTargetLevel(70);
		prefs.setGoal(goal);
		prefs.recordBalance(321);
		prefs.loadForAccount(B);
		assertEquals("another account starts from the default", PestControlGoal.DEFAULT, prefs.getGoal());
		assertNull("and its own (unknown) balance", prefs.getBalance());
		prefs.setGoal(PestControlGoal.DEFAULT.withFamily(PestControlGoal.Family.SUPPLIES));
		store.shutdown();

		LocalStateStore again = store();
		PestControlGoalPreferences reloaded = new PestControlGoalPreferences(again);
		reloaded.loadForAccount(A);
		assertEquals(goal, reloaded.getGoal());
		assertEquals(Integer.valueOf(321), reloaded.getBalance());
		reloaded.loadForAccount(B);
		assertEquals(PestControlGoal.Family.SUPPLIES, reloaded.getGoal().getFamily());
		again.shutdown();
	}

	@Test
	public void forgetBalance_persistsUnknown()
	{
		LocalStateStore store = store();
		PestControlGoalPreferences prefs = new PestControlGoalPreferences(store);
		prefs.loadForAccount(A);
		prefs.recordBalance(50);
		prefs.forgetBalance();
		store.shutdown();
		LocalStateStore again = store();
		PestControlGoalPreferences reloaded = new PestControlGoalPreferences(again);
		reloaded.loadForAccount(A);
		assertNull(reloaded.getBalance());
		again.shutdown();
	}

	@Test
	public void goalFile_isNotTheEventLog_andNoSessionIsWritten() throws Exception
	{
		LocalStateStore store = store();
		PestControlGoalPreferences prefs = new PestControlGoalPreferences(store);
		prefs.loadForAccount(A);
		prefs.setGoal(PestControlGoal.DEFAULT.withVoidGoal(PestControlGoal.VoidGoal.ELITE_SET));
		store.shutdown();
		assertEquals(true, Files.exists(TestFilepaths.path(TelemetryPaths.stateFile(A, "pest_control_goal"))));
		assertEquals(false, Files.exists(TestFilepaths.path(TelemetryPaths.eventsFile(A))));
		assertEquals(false, Files.exists(TestFilepaths.path(TelemetryPaths.sessionsDir(A)))
			&& Files.list(TestFilepaths.path(TelemetryPaths.sessionsDir(A))).findAny().isPresent());
	}

	@Test
	public void corruptOrRenamedEnums_fallBackToDefaults()
	{
		PestControlGoalPreferences.Document d = new PestControlGoalPreferences.Document();
		d.family = "NOT_A_FAMILY";
		d.xpSkill = "MINING";
		d.packTarget = -4;
		PestControlGoal g = PestControlGoalPreferences.toGoal(d);
		assertEquals(PestControlGoal.Family.VOID, g.getFamily());
		assertEquals(Skill.DEFENCE, g.getXpSkill());
		assertEquals(20, g.getPackTarget());
	}
}
