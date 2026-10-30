package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** The first-use Farming tutorial (canonical §28.18): evidence-done steps, credit, skips, persistence, replay. */
public class FarmingTutorialTest
{
	private static FarmingTutorial.Facts facts()
	{
		FarmingTutorial.Facts f = new FarmingTutorial.Facts();
		f.accountRead = true;
		f.farmingLevel = 30;
		return f;
	}

	@Test
	public void knownStorage_isCredited_andInaccessibleStorage_isNeverAsked()
	{
		FarmingTutorial.Facts f = facts();
		f.bankSeen = true;
		FarmingTutorial t = FarmingTutorial.fresh().decide(false, 1L).withEvidence(f);
		assertEquals("the introduction comes first, required", FarmingTutorial.Step.INTRO, t.current(f));
		assertFalse(FarmingTutorial.Step.INTRO.isSkippable());
		t = t.withAcknowledged(FarmingTutorial.Step.INTRO, f);
		assertTrue("bank seen and tool store read: setup done (no Guild at 30 Farming, no house travel)", t.isSetupComplete(f));
		assertEquals(FarmingTutorial.Step.PICK, t.current(f));
		f.farmingLevel = 45;
		assertEquals("the seed vault, once the Guild opens", FarmingTutorial.Step.SEED_VAULT, t.current(f));
		assertFalse(t.isSetupComplete(f));
	}

	@Test
	public void setupBlocksUntilDoneOrSkipped_neverADeadEnd()
	{
		FarmingTutorial.Facts f = facts();
		f.houseChosen = true;
		FarmingTutorial t = FarmingTutorial.fresh().decide(false, 1L).withEvidence(f).withAcknowledged(FarmingTutorial.Step.INTRO, f);
		assertEquals(FarmingTutorial.Step.BANK, t.current(f));
		assertTrue("a storage step says what skipping means", FarmingTutorial.Step.BANK.getSkipHint() != null);
		t = t.withSkipped(FarmingTutorial.Step.BANK, f);
		assertEquals(FarmingTutorial.Step.HOUSE, t.current(f));
		assertFalse(t.isSetupComplete(f));
		t = t.withSkipped(FarmingTutorial.Step.HOUSE, f);
		assertTrue(t.isSetupComplete(f));
	}

	@Test
	public void theFlow_advancesOnWhatTheGameShows_oneStepAtATime_andResumesAfterARestart()
	{
		FarmingTutorial.Facts f = facts();
		f.bankSeen = true;
		FarmingTutorial t = FarmingTutorial.fresh().decide(false, 1L).withEvidence(f).withAcknowledged(FarmingTutorial.Step.INTRO, f);
		f.typeSelected = true;
		f.runOpen = true;
		f.preparing = true;
		t = t.withEvidence(f);
		assertEquals(FarmingTutorial.Step.BANK_FILTER, t.current(f));
		assertSame("nothing new seen: nothing to save", t, t.withEvidence(f));
		Gson gson = new Gson();
		FarmingTutorial back = gson.fromJson(gson.toJson(t), FarmingTutorial.class);
		assertEquals("resumes where it was", FarmingTutorial.Step.BANK_FILTER, back.current(f));
		f.bankFilterOn = true;
		f.preparing = false;
		f.patchesDone = 1;
		t = back.withEvidence(f);
		assertEquals("advice waits for Got it", FarmingTutorial.Step.SKIP, t.current(f));
		t = t.withAcknowledged(FarmingTutorial.Step.SKIP, f);
		assertEquals(FarmingTutorial.Step.FINISH, t.current(f));
		f.runFinished = true;
		t = t.withEvidence(f);
		assertNull(t.current(f));
		assertTrue(t.isFinished());
		FarmingTutorial again = t.replay();
		f.runOpen = false;
		f.typeSelected = false;
		f.patchesDone = 0;
		f.runFinished = false;
		f.bankFilterOn = false;
		assertEquals("replay: the introduction again, then the run tips; setup stays credited", FarmingTutorial.Step.INTRO, again.current(f));
		assertEquals(FarmingTutorial.Step.PICK, again.withAcknowledged(FarmingTutorial.Step.INTRO, f).current(f));
	}

	@Test
	public void itStartsByItselfOnlyForAFirstTimeSetup_andAProfileThatRanBeforeIsCredited()
	{
		FarmingTutorial.Facts f = facts();
		assertTrue(FarmingTutorial.fresh().isUndecided());
		FarmingTutorial veteran = FarmingTutorial.fresh().decide(true, 5L);
		assertNull("ran Farming before: never shown by itself", veteran.current(f));
		assertTrue(veteran.isFinished() && veteran.isCreditedExperience() && !veteran.isUndecided());
		assertTrue(veteran.isSetupComplete(f));
		FarmingTutorial first = FarmingTutorial.fresh().decide(false, 5L);
		assertFalse(first.isUndecided());
		assertEquals(FarmingTutorial.Step.INTRO, first.current(f));
	}

	@Test
	public void completion_survivesSettingsChanges_andSaving()
	{
		FarmingTutorial.Facts f = facts();
		FarmingTutorial done = FarmingTutorial.fresh().decide(false, 1L).withFinished();
		f.houseChosen = true; // the travel preference changed after completion
		f.farmingLevel = 99;
		assertNull("a later settings change never reopens it", done.current(f));
		Gson gson = new Gson();
		FarmingTutorial back = gson.fromJson(gson.toJson(done), FarmingTutorial.class);
		assertNull(back.current(f));
		assertFalse("a saved, finished tutorial is never undecided again", back.isUndecided());
	}

	@Test
	public void aBinsOnlyRun_isTaughtAsBins_andItsFirstBinCounts()
	{
		FarmingTutorial.Facts f = facts();
		f.binsOnly = true;
		assertEquals("Work your first bin", FarmingTutorial.Step.PATCH.titleFor(f));
		f.binsWorked = 1;
		assertTrue(FarmingTutorial.satisfied(FarmingTutorial.Step.PATCH, f));
	}
}
