package com.osrstelemetry.plugin.wintertodt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Playstyle + goal: account-scoped planning state, never telemetry. */
public class WintertodtPreferencesTest
{
	private static final long A = 8_001L;
	private static final long B = 8_002L;
	private Path root;

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("wt-prefs-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(root).deleteRecursively();
	}

	private static LocalStateStore store()
	{
		LocalStateStore s = new LocalStateStore(new Gson());
		s.start();
		return s;
	}

	@Test
	public void playstyleAndGoal_persistPerAccount()
	{
		LocalStateStore s = store();
		WintertodtPreferences prefs = new WintertodtPreferences(s);
		prefs.loadForAccount(A);
		prefs.setMethod(WintertodtPlaystyle.Method.SOLO);
		prefs.setFocus(WintertodtPlaystyle.Focus.POINTS);
		prefs.setGoal(WintertodtGoal.DEFAULT.withFamily(WintertodtGoal.Family.UNIQUE).withUnique(WintertodtUnique.TOME_OF_FIRE));
		prefs.loadForAccount(B);
		assertEquals("another account starts at Auto", WintertodtPlaystyle.Method.AUTO, prefs.getMethod());
		assertNull("with no remembered focus", prefs.getRememberedFocus());
		assertEquals(WintertodtGoal.DEFAULT, prefs.getGoal());
		prefs.setGoal(WintertodtGoal.DEFAULT.withFiremakingTarget(85));
		s.shutdown();

		LocalStateStore again = store();
		WintertodtPreferences reloaded = new WintertodtPreferences(again);
		reloaded.loadForAccount(A);
		assertEquals(WintertodtPlaystyle.Method.SOLO, reloaded.getMethod());
		assertEquals(WintertodtPlaystyle.Focus.POINTS, reloaded.getFocus());
		assertEquals(WintertodtUnique.TOME_OF_FIRE, reloaded.getGoal().getUnique());
		assertEquals(WintertodtGoal.Family.UNIQUE, reloaded.getGoal().getFamily());
		reloaded.loadForAccount(B);
		assertEquals(WintertodtPlaystyle.Method.AUTO, reloaded.getMethod());
		assertEquals(85, reloaded.getGoal().getFiremakingTarget());
		again.shutdown();
		assertFalse("planning never writes the event log", Files.exists(TestFilepaths.path(TelemetryPaths.eventsFile(A))));
		assertFalse(Files.exists(TestFilepaths.path(TelemetryPaths.eventsFile(B))));
	}

	@Test
	public void autoToManual_usesLastFocus_xpTheFirstTime()
	{
		LocalStateStore st = store();
		WintertodtPreferences prefs = new WintertodtPreferences(st);
		prefs.loadForAccount(A);
		assertEquals(WintertodtPlaystyle.Method.AUTO, prefs.getMethod());
		prefs.setMethod(WintertodtPlaystyle.Method.MASS);
		assertEquals("first manual method: XP", WintertodtPlaystyle.Focus.XP, prefs.getFocus());
		prefs.setFocus(WintertodtPlaystyle.Focus.POINTS);
		assertEquals("changing focus keeps the method", WintertodtPlaystyle.Method.MASS, prefs.getMethod());
		prefs.setMethod(WintertodtPlaystyle.Method.SOLO);
		assertEquals("Mass -> Solo keeps the focus", WintertodtPlaystyle.Focus.POINTS, prefs.getFocus());
		prefs.setMethod(WintertodtPlaystyle.Method.AUTO);
		assertEquals(WintertodtPlaystyle.Method.AUTO, prefs.getMethod());
		assertEquals("Auto keeps lastFocus internally", WintertodtPlaystyle.Focus.POINTS, prefs.getRememberedFocus());
		prefs.setMethod(WintertodtPlaystyle.Method.MASS);
		assertEquals("Auto -> Mass restores lastFocus", WintertodtPlaystyle.Focus.POINTS, prefs.getFocus());
		st.shutdown();
	}

	@Test
	public void resolveAuto_onlyWhileAuto_neverFlips()
	{
		LocalStateStore st = store();
		WintertodtPreferences prefs = new WintertodtPreferences(st);
		prefs.loadForAccount(A);
		assertFalse("half a pair does not resolve", prefs.resolveAuto(WintertodtPlaystyle.Method.MASS, null));
		assertTrue(prefs.resolveAuto(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.POINTS));
		assertEquals(WintertodtPlaystyle.Method.MASS, prefs.getMethod());
		assertEquals(WintertodtPlaystyle.Focus.POINTS, prefs.getFocus());
		assertFalse("resolved: later evidence never flips it", prefs.resolveAuto(WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.XP));
		assertEquals(WintertodtPlaystyle.Method.MASS, prefs.getMethod());
		prefs.setMethod(WintertodtPlaystyle.Method.AUTO);
		assertTrue("re-selecting Auto re-enables inference", prefs.resolveAuto(WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.XP));
		assertEquals(WintertodtPlaystyle.Method.SOLO, prefs.getMethod());
		st.shutdown();
	}

	@Test
	public void legacyFiles_loadAndMigrate_allSixValues()
	{
		assertLegacy("MASS_XP", 1L, WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.XP);
		assertLegacy("MASS_REWARDS", 2L, WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.POINTS);
		assertLegacy("SOLO_XP", 3L, WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.XP);
		assertLegacy("SOLO_REWARDS", 4L, WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.POINTS);
		assertLegacy("AUTO", 5L, WintertodtPlaystyle.Method.AUTO, null);
		assertLegacy("WORLD_HOP_XP", 6L, WintertodtPlaystyle.Method.AUTO, WintertodtPlaystyle.Focus.XP);
	}

	private void assertLegacy(String legacy, long account, WintertodtPlaystyle.Method method, WintertodtPlaystyle.Focus focus)
	{
		LocalStateStore s = store();
		WintertodtPreferences.Document old = new WintertodtPreferences.Document();
		old.playstyle = legacy;
		old.family = "UNIQUE";
		old.unique = "PHOENIX";
		s.write(TelemetryPaths.stateFile(account, "wintertodt_planning"), old);
		s.shutdown();

		LocalStateStore again = store();
		WintertodtPreferences prefs = new WintertodtPreferences(again);
		prefs.loadForAccount(account);
		assertEquals(legacy, method, prefs.getMethod());
		assertEquals(legacy, focus, prefs.getRememberedFocus());
		assertEquals("the goal survives the migration", WintertodtGoal.Family.UNIQUE, prefs.getGoal().getFamily());
		// The first change writes the new shape; it reloads the same.
		prefs.setGoal(prefs.getGoal().withFiremakingTarget(90));
		again.shutdown();
		LocalStateStore third = store();
		WintertodtPreferences reloaded = new WintertodtPreferences(third);
		reloaded.loadForAccount(account);
		assertEquals(legacy, method, reloaded.getMethod());
		third.shutdown();
	}
}
