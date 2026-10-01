package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** GotR reward points EARNED this session: positive-delta accounting over a chat-only balance. */
public class GotrRewardPointTrackerTest
{
	private static final String TOTALS = "Total elemental energy: %s. Total catalytic energy: %s.";

	private static String totals(String e, String c)
	{
		return String.format(TOTALS, e, c);
	}

	@Test
	public void firstObservedBalance_isBaselineOnly_preSessionBalanceNeverCounted()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		GotrRewardPointTracker.Gains g = t.onGameMessage(totals("41", "39"));
		assertEquals(0, g.getElementalGained());
		assertEquals(0, g.getCatalyticGained());
		assertEquals(41, g.getElementalBalance());
		assertEquals(39, g.getCatalyticBalance());
	}

	@Test
	public void nextGame_countsEachCurrencyIndependently()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		t.onGameMessage(totals("41", "39"));
		GotrRewardPointTracker.Gains g = t.onGameMessage(totals("48", "47"));
		assertEquals(7, g.getElementalGained());
		assertEquals(8, g.getCatalyticGained());
	}

	@Test
	public void searches_moveTheBaselineDown_soSpendingNeverReducesEarned()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		t.onGameMessage(totals("41", "39"));
		t.onSearch();
		t.onSearch();
		t.onSearch(); // now 38 / 36
		GotrRewardPointTracker.Gains g = t.onGameMessage(totals("44", "42"));
		assertEquals(6, g.getElementalGained());
		assertEquals(6, g.getCatalyticGained());
	}

	@Test
	public void searchWithUnknownBalance_staysUnknown_andNeverGoesNegative()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		t.onSearch();
		assertNull(t.getElementalBalance());
		t.onGameMessage(totals("0", "1"));
		t.onSearch();
		assertEquals(Integer.valueOf(0), t.getElementalBalance());
		assertEquals(Integer.valueOf(0), t.getCatalyticBalance());
	}

	@Test
	public void checkAnswer_isABaseline_soTheFirstGameAfterItCounts()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		assertTrue(t.onCheckText("You have 39 catalytic energy and 41 elemental energy. You can use this energy to search the Rift for 39 more times."));
		GotrRewardPointTracker.Gains g = t.onGameMessage(totals("45", "43"));
		assertEquals(4, g.getElementalGained());
		assertEquals(4, g.getCatalyticGained());
	}

	@Test
	public void checkAnswerInAMessageBox_withLineBreaks_isRead()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		assertTrue(t.onCheckText("You have 10 catalytic energy and 12<br>elemental energy."));
		assertEquals(Integer.valueOf(12), t.getElementalBalance());
		assertEquals(Integer.valueOf(10), t.getCatalyticBalance());
	}

	@Test
	public void thousandsSeparators_andCaseAreTolerated()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		t.onGameMessage(totals("1,041", "1,039"));
		GotrRewardPointTracker.Gains g = t.onGameMessage("total elemental energy: 1,050. total catalytic energy: 1,045.");
		assertEquals(9, g.getElementalGained());
		assertEquals(6, g.getCatalyticGained());
	}

	@Test
	public void unrelatedMessages_changeNothing()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		assertNull(t.onGameMessage("Amount of rifts you have closed: 12."));
		assertNull(t.onGameMessage(null));
		assertFalse(t.onCheckText("You have 5 coins."));
		assertNull(t.getElementalBalance());
	}

	@Test
	public void reset_forgetsBalances_soLoginNeverFabricatesGains()
	{
		GotrRewardPointTracker t = new GotrRewardPointTracker();
		t.onGameMessage(totals("10", "10"));
		t.reset();
		GotrRewardPointTracker.Gains g = t.onGameMessage(totals("90", "90"));
		assertEquals(0, g.getElementalGained());
		assertEquals(0, g.getCatalyticGained());
	}
}
