package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

/**
 * Pest Control round lifecycle, Avg Game / round-time inputs, HUD
 * interpretation and the commendation balance -- pure, no client.
 */
public class PestControlGameTrackerTest
{
	private static final Instant T = Instant.parse("2026-10-02T03:43:38Z");

	@Test
	public void observedEntry_opensARound_withAStartTime()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(100, true, T);
		PestControlGameState s = t.state();
		assertNotNull(s);
		assertEquals(T, s.getStartedAt());
		assertEquals(T, s.getOpenedAt());
		assertNull("nothing read yet is never guessed", s.getActivityPercent());
	}

	@Test
	public void midGameLogin_isNotObservedFromStart_noGameTime_noAvgGame()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(100, false, T);
		assertNull("joined part-way: no game time", t.state().getStartedAt());
		assertEquals("but its island time still counts as round time", T, t.state().getOpenedAt());
		assertNull("a win of a round joined part-way never enters Avg Game", t.onWin(150));
		assertEquals(Long.valueOf(60 * 600L), t.onExit(160));
	}

	@Test
	public void win_returnsEntryToWinSeconds_once()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(1000, true, T);
		// Live log 2026-10-01: entry 23:43:38, win 23:45:38 = 200 ticks.
		assertEquals(Integer.valueOf(120), t.onWin(1200));
		assertNull("the repeated win line is not a second game", t.onWin(1203));
	}

	@Test
	public void lateWin_justAfterLeavingTheIsland_stillBelongsToThatRound()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(1000, true, T);
		assertEquals(Long.valueOf(200 * 600L), t.onExit(1200));
		assertEquals(Integer.valueOf(121), t.onWin(1202));
		assertNull(t.onWin(1205));
	}

	@Test
	public void winLongAfterLeaving_isNotScored()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(1000, true, T);
		t.onExit(1200);
		assertNull(t.onWin(1200 + PestControlGameTracker.LATE_WIN_TICKS + 1));
	}

	@Test
	public void lostRound_isRoundTime_withNoScore()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(1000, true, T);
		assertEquals("a lost game is valid active time", Long.valueOf(300 * 600L), t.onExit(1300));
		assertNull(t.state());
	}

	@Test
	public void eachRound_clearsAndReinitialises()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(1000, true, T);
		t.onHud(60, 80, 3);
		t.onWin(1100);
		t.onExit(1101);
		assertNull("no current game in the outpost", t.state());
		Instant second = T.plusSeconds(200);
		t.onEnter(1400, true, second);
		PestControlGameState s = t.state();
		assertNull("the last round's HUD never leaks", s.getActivityPercent());
		assertNull(s.getPortalsDestroyed());
		assertEquals(second, s.getStartedAt());
		assertEquals("the new round can be scored", Integer.valueOf(60), t.onWin(1500));
	}

	@Test
	public void exitWithoutARound_isNothing()
	{
		assertNull(new PestControlGameTracker().onExit(5));
	}

	@Test
	public void hud_isIgnoredOffTheIsland()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onHud(50, 50, 2);
		assertNull(t.state());
	}

	@Test
	public void activityPercent_isTheActivityVarbitOver50()
	{
		// Varbit 5662: 0..50, starts each game at 25, caps at 50.
		assertEquals(Integer.valueOf(0), PestControlGameTracker.activityPercent(0));
		assertEquals(Integer.valueOf(2), PestControlGameTracker.activityPercent(1));
		assertEquals(Integer.valueOf(24), PestControlGameTracker.activityPercent(12));
		assertEquals(Integer.valueOf(26), PestControlGameTracker.activityPercent(13));
		assertEquals("a game's starting value", Integer.valueOf(50), PestControlGameTracker.activityPercent(25));
		assertEquals(Integer.valueOf(74), PestControlGameTracker.activityPercent(37));
		assertEquals(Integer.valueOf(76), PestControlGameTracker.activityPercent(38));
		assertEquals(Integer.valueOf(98), PestControlGameTracker.activityPercent(49));
		assertEquals(Integer.valueOf(100), PestControlGameTracker.activityPercent(50));
	}

	@Test
	public void activityPercent_outOfRange_isUnknown_neverClampedIntoAGuess()
	{
		assertNull(PestControlGameTracker.activityPercent(-1));
		assertNull(PestControlGameTracker.activityPercent(51));
		assertNull(PestControlGameTracker.activityPercent(Integer.MAX_VALUE));
	}

	@Test
	public void activityPercent_isMonotonic_andAlwaysWithin0to100()
	{
		int previous = -1;
		for (int v = 0; v <= PestControlGameTracker.ACTIVITY_MAX; v++)
		{
			int pct = PestControlGameTracker.activityPercent(v);
			assertTrue(pct >= 0 && pct <= 100);
			assertTrue(pct > previous);
			previous = pct;
		}
	}

	@Test
	public void knightPercent_fromHitpointsOrPercentText()
	{
		assertEquals(Integer.valueOf(100), PestControlGameTracker.knightPercent("200"));
		assertEquals(Integer.valueOf(92), PestControlGameTracker.knightPercent("<col=ffffff>184</col>"));
		assertEquals(Integer.valueOf(45), PestControlGameTracker.knightPercent("45%"));
		assertNull(PestControlGameTracker.knightPercent("Void Knight"));
		assertNull(PestControlGameTracker.knightPercent(null));
	}

	@Test
	public void portalDead_onlyForAnExactZero()
	{
		assertTrue(PestControlGameTracker.isPortalDead("0"));
		assertTrue(PestControlGameTracker.isPortalDead(" <col=ff0000>0</col> "));
		assertFalse(PestControlGameTracker.isPortalDead("250"));
		assertFalse(PestControlGameTracker.isPortalDead("10"));
		assertFalse(PestControlGameTracker.isPortalDead(null));
	}

	@Test
	public void balance_fromTheMessageBox_live()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		assertNull("unknown is never 0", t.getBalance());
		// Live 2026-10-01 (MESBOX, tags removed by the collector).
		assertEquals(Integer.valueOf(11), t.onBalanceMessage(
			"You now have 11 Void Knight Commendation points! You can speak to a Void Knight to exchange your points for rewards."));
		assertEquals(Integer.valueOf(11), t.getBalance());
		assertEquals(Integer.valueOf(1234), t.onBalanceMessage("You now have 1,234 Void Knight Commendation points!"));
		assertNull(t.onBalanceMessage("You now have 3 Wintertodt points"));
	}

	@Test
	public void award_advancesAKnownBalance_neverAnUnknownOne()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		assertNull(t.onAward(8));
		t.onBalanceMessage("You now have 3 Void Knight Commendation points!");
		assertEquals(Integer.valueOf(11), t.onAward(8));
		assertEquals("the restated balance wins", Integer.valueOf(11), t.onBalanceMessage("You now have 11 Void Knight Commendation points!"));
	}

	@Test
	public void landerPoints_lastNumberOfAPointsLine()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		assertEquals(Integer.valueOf(42), t.onLanderPoints("Commendation points: 42"));
		assertNull(t.onLanderPoints("Players Ready: 5"));
	}

	@Test
	public void seed_onlyFillsAnUnknownBalance_andForgetClears()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.seedBalance(90);
		assertEquals(Integer.valueOf(90), t.getBalance());
		t.seedBalance(5);
		assertEquals(Integer.valueOf(90), t.getBalance());
		t.forgetBalance();
		assertNull(t.getBalance());
		t.seedBalance(-1);
		assertNull(t.getBalance());
	}

	@Test
	public void reset_forgetsRoundAndBalance()
	{
		PestControlGameTracker t = new PestControlGameTracker();
		t.onEnter(1, true, T);
		t.onBalanceMessage("You now have 3 Void Knight Commendation points!");
		t.reset();
		assertFalse(t.isInGame());
		assertNull(t.getBalance());
		assertNull(t.onExit(9));
	}
}
