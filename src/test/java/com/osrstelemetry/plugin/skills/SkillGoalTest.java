package com.osrstelemetry.plugin.skills;

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
import net.runelite.api.Experience;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Skill TARGETS: exact XP-table levels, bank-based stockpiles, ETAs only
 * from observed rates (and the banked rate for a stockpile), and the
 * account-scoped planning file that never touches telemetry.
 */
public class SkillGoalTest
{
	private static final long A = 9_101L;
	private static final long B = 9_102L;
	private Path root;

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("skill-goal-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(root).deleteRecursively();
	}

	// ---- LEVEL -----------------------------------------------------------------------------------

	@Test
	public void level_aboveCurrent_exactXpLeft_noEtaBeforeARate()
	{
		int xp = Experience.getXpForLevel(80) + 1_234;
		SkillGoalPlanner.LevelPlan p = SkillGoalPlanner.level(92, xp, null);
		assertFalse(p.isReached());
		assertEquals(Long.valueOf(Experience.getXpForLevel(92) - xp), p.getXpLeft());
		assertNull("no default rate is ever assumed", p.getEtaMillis());
	}

	@Test
	public void level_eta_fromTheObservedRate()
	{
		int xp = Experience.getXpForLevel(91);
		SkillGoalPlanner.LevelPlan p = SkillGoalPlanner.level(92, xp, 60_000L);
		long left = Experience.getXpForLevel(92) - xp;
		assertEquals(Long.valueOf((long) Math.ceil(left / 60_000d * 3_600_000d)), p.getEtaMillis());
	}

	@Test
	public void level_equalOrBelowCurrent_isReached()
	{
		assertTrue(SkillGoalPlanner.level(60, Experience.getXpForLevel(60), null).isReached());
		assertTrue(SkillGoalPlanner.level(60, Experience.getXpForLevel(75), null).isReached());
		assertEquals(Long.valueOf(0L), SkillGoalPlanner.level(60, Experience.getXpForLevel(75), null).getXpLeft());
	}

	@Test
	public void level_recalculatesAfterALevel()
	{
		SkillGoalPlanner.LevelPlan before = SkillGoalPlanner.level(92, Experience.getXpForLevel(85), null);
		SkillGoalPlanner.LevelPlan after = SkillGoalPlanner.level(92, Experience.getXpForLevel(86), null);
		assertEquals(Experience.getXpForLevel(86) - Experience.getXpForLevel(85), before.getXpLeft() - after.getXpLeft());
	}

	@Test
	public void level_unknownXp_isUnknown()
	{
		assertNull(SkillGoalPlanner.level(92, null, 50_000L).getXpLeft());
	}

	// ---- STOCKPILE ---------------------------------------------------------------------------------

	@Test
	public void stockpile_startsFromTheBank_notFromZero()
	{
		SkillGoalPlanner.StockpilePlan p = SkillGoalPlanner.stockpile(5_000, 1_842L, null);
		assertEquals(Long.valueOf(1_842L), p.getStock());
		assertEquals(Long.valueOf(3_158L), p.getRemaining());
		assertNull(p.getEtaMillis());
	}

	@Test
	public void stockpile_unknownBank_isUnknown_neverZero()
	{
		SkillGoalPlanner.StockpilePlan p = SkillGoalPlanner.stockpile(5_000, null, null);
		assertNull(p.getStock());
		assertNull(p.getRemaining());
	}

	@Test
	public void stockpile_eta_usesTheBankedRate_onlyAfterTwoDeposits()
	{
		// One deposit: no ETA, however productive the session is.
		assertNull(SkillGoalPlanner.stockpile(5_000, 2_242L, new SkillGoalPlanner.BankingSample(400, 1, 3_600_000L)).getEtaMillis());
		// Two deposits, 700 banked in one hour of session time: 2,758 left -> ~3.94 h.
		SkillGoalPlanner.StockpilePlan p = SkillGoalPlanner.stockpile(5_000, 2_242L,
			new SkillGoalPlanner.BankingSample(700, 2, 3_600_000L));
		assertEquals((long) Math.ceil(2_758 / 700d * 3_600_000d), (long) p.getEtaMillis());
		assertNull("too little time", SkillGoalPlanner.stockpile(5_000, 0L, new SkillGoalPlanner.BankingSample(56, 2, 60_000L)).getEtaMillis());
	}

	@Test
	public void stockpile_reached()
	{
		SkillGoalPlanner.StockpilePlan p = SkillGoalPlanner.stockpile(1_000, 1_200L, null);
		assertTrue(p.isReached());
		assertEquals(Long.valueOf(0L), p.getRemaining());
	}

	@Test
	public void etaText()
	{
		assertNull(SkillGoalPlanner.etaText(null));
		assertEquals("~7h 23m", SkillGoalPlanner.etaText((7 * 60 + 23) * 60_000L));
		assertEquals("~42m", SkillGoalPlanner.etaText(42 * 60_000L));
		assertEquals("~1m", SkillGoalPlanner.etaText(5_000L));
		assertEquals("~140h", SkillGoalPlanner.etaText(140 * 3_600_000L));
	}

	// ---- goal values + persistence -------------------------------------------------------------------

	@Test
	public void goal_clampsOutOfRangeValues()
	{
		SkillGoal g = new SkillGoal(SkillGoal.Family.LEVEL, 150, null, -3);
		assertEquals(SkillGoal.DEFAULT_LEVEL, g.getTargetLevel());
		assertEquals(SkillGoal.DEFAULT_QUANTITY, g.getTargetQuantity());
		assertEquals(SkillGoal.Family.LEVEL, new SkillGoal(null, 50, null, 10).getFamily());
	}

	private static LocalStateStore store()
	{
		LocalStateStore s = new LocalStateStore(new Gson());
		s.start();
		return s;
	}

	@Test
	public void targets_persistPerAccount_perSkill_neverTelemetry()
	{
		LocalStateStore s = store();
		SkillGoalPreferences prefs = new SkillGoalPreferences(s);
		prefs.loadForAccount(A);
		assertEquals(SkillGoal.DEFAULT, prefs.getGoal("mining"));
		SkillGoal stock = SkillGoal.DEFAULT.withFamily(SkillGoal.Family.STOCKPILE).withResource(21347).withTargetQuantity(5_000);
		prefs.setGoal("mining", stock);
		prefs.setGoal("woodcutting", SkillGoal.DEFAULT.withTargetLevel(90));
		prefs.loadForAccount(B);
		assertEquals("another account has its own", SkillGoal.DEFAULT, prefs.getGoal("mining"));
		prefs.setGoal("mining", SkillGoal.DEFAULT.withTargetLevel(92));
		s.shutdown();

		LocalStateStore again = store();
		SkillGoalPreferences reloaded = new SkillGoalPreferences(again);
		reloaded.loadForAccount(A);
		assertEquals(stock, reloaded.getGoal("mining"));
		assertEquals(90, reloaded.getGoal("Woodcutting").getTargetLevel());
		reloaded.loadForAccount(B);
		assertEquals(92, reloaded.getGoal("mining").getTargetLevel());
		again.shutdown();
		assertFalse("planning never writes the event log", Files.exists(TestFilepaths.path(TelemetryPaths.eventsFile(A))));
		assertFalse(Files.exists(TestFilepaths.path(TelemetryPaths.eventsFile(B))));
	}
}
