package com.osrstelemetry.plugin.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SlayerAssignmentEndReason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Pure Smart Slayer dashboard rules: masters, progress, milestones, boosting, modifiers, rates. */
public class SlayerDashboardTest
{
	// ---- master names ------------------------------------------------

	@Test
	public void everyNativeMaster_hasAPlayerFacingName_neverAnId()
	{
		String[] expected = {null, "Turael / Aya", "Mazchna / Achtryn", "Vannaka", "Chaeldar", "Duradel / Kuradal",
			"Nieve / Steve", "Krystilia", "Konar quo Maten", "Spria", "Mortimer"};
		for (int id = 0; id <= 10; id++)
		{
			assertEquals("master " + id, expected[id], SlayerDashboard.masterName(id));
		}
		assertNull("unknown future value is never shown as a number", SlayerDashboard.masterName(11));
		assertNull(SlayerDashboard.masterName(null));
	}

	// ---- progress ----------------------------------------------------

	@Test
	public void progressPercent_floorsAndOnlyReaches100AtZeroRemaining()
	{
		assertEquals("119/136 = 87.5% floors to 87", Integer.valueOf(87), SlayerDashboard.progressPercent(17, 136));
		assertEquals(Integer.valueOf(99), SlayerDashboard.progressPercent(1, 136));
		assertEquals(Integer.valueOf(100), SlayerDashboard.progressPercent(0, 136));
		assertEquals(Integer.valueOf(0), SlayerDashboard.progressPercent(136, 136));
		assertNull("unknown size", SlayerDashboard.progressPercent(17, null));
		assertNull("inconsistent", SlayerDashboard.progressPercent(140, 136));
		assertNull(SlayerDashboard.progressPercent(null, 136));
	}

	@Test
	public void assignmentSize_observedAmountElseNativeOriginalForMidTaskRecords()
	{
		SessionAggregates.SlayerAssignmentRecord observed = new SessionAggregates.SlayerAssignmentRecord();
		observed.setInitialAmount(136);
		assertEquals(Integer.valueOf(136), SlayerDashboard.assignmentSize(observed));

		SessionAggregates.SlayerAssignmentRecord joined = new SessionAggregates.SlayerAssignmentRecord();
		joined.setBootstrapped(true);
		joined.setInitialAmount(99);
		assertNull("a mid-task record's own amount is not the task size", SlayerDashboard.assignmentSize(joined));
		joined.setAssignedInitialAmount(180);
		assertEquals(Integer.valueOf(180), SlayerDashboard.assignmentSize(joined));
	}

	// ---- milestones ----------------------------------------------------

	@Test
	public void nextBonus_isTheNextMultipleOf10_everyBonusTaskCounts()
	{
		String[][] cases = {
			{"2", "10th in 8"}, {"9", "10th next"}, {"12", "20th in 8"}, {"19", "20th next"}, {"42", "50th in 8"},
			{"49", "50th next"}, {"91", "100th in 9"}, {"99", "100th next"}, {"198", "200th in 2"}, {"248", "250th in 2"},
			{"0", "10th in 10"}, {"10", "20th in 10"}, {"990", "1,000th in 10"}, {"999", "1,000th next"}, {"1000", "1,010th in 10"},
			{"1001", "1,010th in 9"}, {"111", "120th in 9"}};
		for (String[] c : cases)
		{
			assertEquals("streak " + c[0], c[1], SlayerDashboard.milestoneText(Integer.parseInt(c[0]), "NORMAL"));
		}
		assertEquals("Krystilia's Wilderness streak earns bonuses too", "50th in 3", SlayerDashboard.milestoneText(47, "WILDERNESS"));
	}


	@Test
	public void milestone_neverForMortimerOrUnknownSource()
	{
		assertNull("Mortimer's points are not raised by milestones", SlayerDashboard.milestoneText(49, "MORTIMER"));
		assertNull(SlayerDashboard.milestoneText(49, null));
		assertNull(SlayerDashboard.milestoneText(null, "NORMAL"));
	}

	@Test
	public void nextBonus_isCompactInformation_neverAWarning()
	{
		for (int streak = 0; streak < 1100; streak++)
		{
			String t = SlayerDashboard.milestoneText(streak, "NORMAL");
			assertFalse(t, t.contains("!") || t.contains("bonus") || t.contains("WARNING") || t.contains("Turael"));
			assertTrue("never more than 10 tasks away: " + t, SlayerDashboard.tasksToNextMilestone(streak, "NORMAL") <= 10);
		}
	}


	// ---- Turael boosting ---------------------------------------------

	private static SessionAggregates.SlayerAssignmentRecord rec(Integer master, SlayerAssignmentEndReason end, boolean finished)
	{
		SessionAggregates.SlayerAssignmentRecord r = new SessionAggregates.SlayerAssignmentRecord();
		r.setMasterId(master);
		r.setEndReason(end);
		if (finished)
		{
			r.setEndedAt("2026-01-01T10:00:00Z");
		}
		return r;
	}

	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(SessionAggregates.SlayerAssignmentRecord... records)
	{
		return new ArrayList<>(Arrays.asList(records));
	}

	private static final SlayerAssignmentEndReason DONE = SlayerAssignmentEndReason.COMPLETED;

	@Test
	public void boosting_threeConsecutiveCompletedZeroPointTasks()
	{
		assertTrue(SlayerDashboard.looksLikeTuraelBoosting(ledger(rec(1, DONE, true), rec(1, DONE, true), rec(9, DONE, true),
			rec(1, null, false))));
		assertTrue("the awaiting (completed, current) record counts",
			SlayerDashboard.looksLikeTuraelBoosting(ledger(rec(1, DONE, true), rec(1, DONE, true), rec(1, DONE, false))));
	}

	@Test
	public void boosting_ordinaryTuraelUseIsNotRecognized()
	{
		assertFalse("one Turael task", SlayerDashboard.looksLikeTuraelBoosting(ledger(rec(1, DONE, true), rec(5, null, false))));
		assertFalse("two", SlayerDashboard.looksLikeTuraelBoosting(ledger(rec(1, DONE, true), rec(1, DONE, true))));
		assertFalse("a skip breaks the run", SlayerDashboard.looksLikeTuraelBoosting(
			ledger(rec(1, DONE, true), rec(1, SlayerAssignmentEndReason.REPLACED_EARLY, true), rec(1, DONE, true), rec(1, DONE, true))));
		assertFalse("a points master breaks the run", SlayerDashboard.looksLikeTuraelBoosting(
			ledger(rec(1, DONE, true), rec(1, DONE, true), rec(5, DONE, true))));
		assertFalse("unknown master is uncertain -> omitted", SlayerDashboard.looksLikeTuraelBoosting(
			ledger(rec(1, DONE, true), rec(null, DONE, true), rec(1, DONE, true))));
		assertFalse(SlayerDashboard.looksLikeTuraelBoosting(null));
	}

	@Test
	public void pointsMasterHint_onlyWhenBoostingAndTheNextTaskIsABonusTask()
	{
		for (int streak : new int[] {9, 19, 49, 99, 249, 999})
		{
			assertTrue("streak " + streak, SlayerDashboard.showPointsMasterHint(true, streak, "NORMAL"));
		}
		assertFalse("bonus in 2", SlayerDashboard.showPointsMasterHint(true, 248, "NORMAL"));
		assertFalse("bonus in 8", SlayerDashboard.showPointsMasterHint(true, 12, "NORMAL"));
		assertFalse("not boosting", SlayerDashboard.showPointsMasterHint(false, 49, "NORMAL"));
		assertFalse("not boosting at a 10th either", SlayerDashboard.showPointsMasterHint(false, 9, "NORMAL"));
		assertFalse("Wilderness streak", SlayerDashboard.showPointsMasterHint(true, 49, "WILDERNESS"));
		assertFalse(SlayerDashboard.showPointsMasterHint(true, 49, "MORTIMER"));
		assertFalse("unknown streak source", SlayerDashboard.showPointsMasterHint(true, 49, null));
	}


	// ---- modifiers -----------------------------------------------------

	@Test
	public void modifier_onlyConfirmedIdsAreNamed()
	{
		assertEquals("Quantity +65", SlayerDashboard.modifierText(2, 65, false));
		assertEquals("Quantity -20", SlayerDashboard.modifierText(2, 20, true));
		assertEquals("+25 points", SlayerDashboard.modifierText(1, 25, false));
		for (int unconfirmed = 3; unconfirmed <= 6; unconfirmed++)
		{
			assertNull("id " + unconfirmed + " is not guessed", SlayerDashboard.modifierText(unconfirmed, 50, false));
		}
		assertNull("no modifier", SlayerDashboard.modifierText(0, 0, false));
		assertNull(SlayerDashboard.modifierText(2, 0, false));
	}

	// ---- rates ---------------------------------------------------------

	@Test
	public void killsPerHour_usesTaskActiveTimeAndTheSharedRateFloor()
	{
		assertNull("one kill after 20s is not a rate", SlayerDashboard.perHour(1, 20_000L));
		assertEquals(Long.valueOf(178), SlayerDashboard.perHour(89, 30L * 60_000L));
		assertNull(SlayerDashboard.perHour(0, 3_600_000L));
		assertNull(SlayerDashboard.perHour(null, 3_600_000L));
	}

	@Test
	public void lootPerKill_observedOnly_omittedWithoutKills()
	{
		assertEquals(Long.valueOf(3200L), SlayerDashboard.perKill(67_200L, 21));
		assertNull(SlayerDashboard.perKill(5000L, 0));
		assertNull(SlayerDashboard.perKill(5000L, null));
	}
}
