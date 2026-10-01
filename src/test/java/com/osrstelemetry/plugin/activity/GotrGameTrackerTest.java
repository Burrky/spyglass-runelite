package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Final energy and provenance of won GotR games (the Avg / Best population). */
public class GotrGameTrackerTest
{
	@Test
	public void fullyObservedWin_scoresItsFinalTotalEnergy()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		t.onEnergy(150, 90);
		t.onEnergy(420, 380);
		GotrGameTracker.Result r = t.onCompletion(12, 420, 380);
		assertEquals(Integer.valueOf(12), r.getRiftsClosed());
		assertEquals(Integer.valueOf(800), r.getTotalEnergy());
		assertTrue(r.isObservedFromStart());
	}

	@Test
	public void finalValue_survivesTheVarbitsResettingBeforeTheMessage()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		t.onEnergy(500, 520);
		GotrGameTracker.Result r = t.onCompletion(3, 0, 0);
		assertEquals(Integer.valueOf(1020), r.getTotalEnergy());
		assertTrue(r.isObservedFromStart());
	}

	@Test
	public void gameFirstSeenAboveZero_isPartial_stillReportsEnergy_neverTrusted()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(300, 200); // login / re-enable mid-game
		t.onEnergy(450, 400);
		GotrGameTracker.Result r = t.onCompletion(4, 450, 400);
		assertEquals(Integer.valueOf(850), r.getTotalEnergy());
		assertFalse(r.isObservedFromStart());
	}

	@Test
	public void energyDroppingWithoutAZeroWeSaw_isANewUnwatchedGame()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		t.onEnergy(600, 500);
		t.onEnergy(80, 40); // reset while unwatched, then re-earned
		assertFalse(t.onCompletion(5, 80, 40).isObservedFromStart());
	}

	@Test
	public void failedGame_dropsItsTracking_andTheNextGameMustProveItsStart()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		t.onEnergy(400, 300);
		assertTrue(GotrGameTracker.isFailure("The Great Guardian was defeated!"));
		t.onFailure();
		t.onEnergy(400, 300); // varbits still hold the lost game's energy
		GotrGameTracker.Result r = t.onCompletion(6, 400, 300);
		assertFalse("the lost game's energy can never become a trusted win", r.isObservedFromStart());
		t.onEnergy(0, 0);
		t.onEnergy(200, 210);
		assertTrue(t.onCompletion(7, 200, 210).isObservedFromStart());
	}

	@Test
	public void completionConsumesTheGame_aSecondCompletionWithoutAZeroIsUntrusted()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		t.onEnergy(300, 300);
		assertTrue(t.onCompletion(8, 300, 300).isObservedFromStart());
		assertFalse(t.onCompletion(9, 300, 300).isObservedFromStart());
	}

	@Test
	public void zeroEnergyWin_hasNoScore()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		GotrGameTracker.Result r = t.onCompletion(10, 0, 0);
		assertNull(r.getTotalEnergy());
		assertFalse(r.isObservedFromStart());
	}

	@Test
	public void reset_forgetsProvenance()
	{
		GotrGameTracker t = new GotrGameTracker();
		t.onEnergy(0, 0);
		t.onEnergy(100, 100);
		t.reset();
		assertFalse(t.onCompletion(11, 100, 100).isObservedFromStart());
	}

	@Test
	public void failureText_isMatchedOnlyForTheGamesOwnMessage()
	{
		assertFalse(GotrGameTracker.isFailure("The Great Guardian successfully closed the rift!"));
		assertFalse(GotrGameTracker.isFailure(null));
	}
}
