package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Integrity audit (live XP): a durable window removes only what it carried.
 * It is resolved one tick after it closes, by which time the next tick's
 * StatChanged gains are already pending -- those must stay visible.
 */
public class LiveXpReconcileTest
{
	@Test
	public void reconcileRemovesOnlyTheAppliedWindow_keepingGainsAfterIt()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("s", "ATTACK", 100); // inside the closed window
		tracker.recordDelta("s", "ATTACK", 7);   // next tick, before the window resolves
		tracker.reconcile("s", "ATTACK", 100L);
		assertEquals("display = durable 100 + pending 7, never a dip", 7L, tracker.getPending("s", "ATTACK"));
	}

	@Test
	public void reconcileNeverGoesNegative_andNullClearsAll()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("s", "ATTACK", 40); // live tracking began mid-window
		tracker.reconcile("s", "ATTACK", 100L);
		assertEquals(0L, tracker.getPending("s", "ATTACK"));
		tracker.recordDelta("s", "STRENGTH", 25);
		tracker.reconcile("s", "STRENGTH", null);
		assertEquals(0L, tracker.getPending("s", "STRENGTH"));
	}

	@Test
	public void otherSkillsAndOtherSessions_untouched()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("s", "ATTACK", 10);
		tracker.recordDelta("s", "HITPOINTS", 4);
		tracker.reconcile("s", "ATTACK", 10L);
		tracker.reconcile("other", "HITPOINTS", 4L);
		assertEquals(4L, tracker.getPending("s", "HITPOINTS"));
	}
}
