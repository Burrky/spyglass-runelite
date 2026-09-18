package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Direct, isolated coverage of {@link LiveXpTracker}'s own
 * ownership/reconciliation contract -- independent of
 * SessionRuntimeCoordinator/SkillsCollector's own wiring (covered
 * separately by SessionRuntimeCoordinatorTest/GenericNpcCombatRecognitionTest-
 * style full-pipeline tests and SkillsCollectorTest's pure
 * evaluateLiveXpDelta() tests).
 */
public class LiveXpTrackerTest
{
	// 1: a recorded delta is immediately visible via getPending()/getPendingForSession().
	@Test
	public void recordDelta_isImmediatelyVisible()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);
		assertEquals(40L, tracker.getPending("session-1", "Strength"));
		assertEquals(Long.valueOf(40L), tracker.getPendingForSession("session-1").get("Strength"));
	}

	// 2: multiple deltas for the same (session, skill) accumulate.
	@Test
	public void recordDelta_accumulatesAcrossMultipleCalls()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);
		tracker.recordDelta("session-1", "Strength", 15L);
		assertEquals(55L, tracker.getPending("session-1", "Strength"));
	}

	// delta <= 0 is always ignored -- never fabricates positive live XP.
	@Test
	public void recordDelta_ignoresZeroOrNegativeDeltas()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 0L);
		tracker.recordDelta("session-1", "Strength", -5L);
		assertEquals(0L, tracker.getPending("session-1", "Strength"));
	}

	// null sessionId/skill is always ignored -- no honest owner to attach to.
	@Test
	public void recordDelta_ignoresNullSessionOrSkill()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta(null, "Strength", 40L);
		tracker.recordDelta("session-1", null, 40L);
		assertTrue(tracker.getPendingForSession("session-1").isEmpty());
	}

	// 6: a session switch (a delta for a DIFFERENT sessionId) discards
	// every previously-tracked amount -- the old session's pending XP
	// must never leak into the new one's display.
	@Test
	public void recordDelta_sessionSwitch_discardsThePreviousOwnersPendingXp()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("woodcutting-session", "Woodcutting", 100L);
		assertEquals(100L, tracker.getPending("woodcutting-session", "Woodcutting"));

		tracker.recordDelta("combat-session", "Strength", 10L);

		assertEquals(0L, tracker.getPending("woodcutting-session", "Woodcutting"));
		assertTrue(tracker.getPendingForSession("woodcutting-session").isEmpty());
		assertEquals(10L, tracker.getPending("combat-session", "Strength"));
	}

	// 3: reconcile() (a real XP_CHANGE flush landing) resets the live
	// pending amount for that (session, skill) to zero -- this is what
	// guarantees the exact-once display total (canonical + live never
	// double-counts once XP_CHANGE catches up).
	@Test
	public void reconcile_resetsPendingForThatSkillToZero()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);
		tracker.reconcile("session-1", "Strength");
		assertEquals(0L, tracker.getPending("session-1", "Strength"));
	}

	// reconcile() only clears the skill it names -- an unrelated skill's
	// own pending amount is untouched.
	@Test
	public void reconcile_onlyClearsTheNamedSkill()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);
		tracker.recordDelta("session-1", "Attack", 25L);
		tracker.reconcile("session-1", "Strength");
		assertEquals(0L, tracker.getPending("session-1", "Strength"));
		assertEquals(25L, tracker.getPending("session-1", "Attack"));
	}

	// 7: reconcile() for a sessionId that is NOT the current owner is a
	// no-op -- a delayed XP_CHANGE for an abandoned/finalized session
	// arriving after a real switch must never disturb the NEW current
	// owner's own live view.
	@Test
	public void reconcile_forAnAbandonedSessionAfterASwitch_isANoOp()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("woodcutting-session", "Woodcutting", 100L);
		tracker.recordDelta("combat-session", "Strength", 10L);

		// Delayed XP_CHANGE for the OLD (abandoned) session finally lands.
		tracker.reconcile("woodcutting-session", "Woodcutting");

		// The new current owner's own live pending XP is completely unaffected.
		assertEquals(10L, tracker.getPending("combat-session", "Strength"));
	}

	// getPending()/getPendingForSession() for a sessionId that has never
	// been recorded against (or is not the current owner) return 0/empty.
	@Test
	public void getPending_forUnknownOrStaleSession_returnsZeroOrEmpty()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);

		assertEquals(0L, tracker.getPending("session-2", "Strength"));
		assertTrue(tracker.getPendingForSession("session-2").isEmpty());
		assertEquals(0L, tracker.getPending(null, "Strength"));
	}

	// getPendingForSession() returns a defensive copy -- mutating it
	// never affects the tracker's own internal state.
	@Test
	public void getPendingForSession_returnsADefensiveCopy()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);

		java.util.Map<String, Long> copy = tracker.getPendingForSession("session-1");
		try
		{
			copy.put("Strength", 999L);
		}
		catch (UnsupportedOperationException expected)
		{
			// Also acceptable -- an unmodifiable view throwing on mutation.
		}

		assertEquals(40L, tracker.getPending("session-1", "Strength"));
	}

	// 9: reset() (account switch) clears every tracked owner/amount --
	// no fake live XP survives into the new account.
	@Test
	public void reset_clearsOwnerAndAllPendingAmounts()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);
		tracker.reset();

		assertEquals(0L, tracker.getPending("session-1", "Strength"));
		assertTrue(tracker.getPendingForSession("session-1").isEmpty());

		// A fresh account's own session can now become the owner cleanly.
		tracker.recordDelta("session-1", "Strength", 5L);
		assertEquals(5L, tracker.getPending("session-1", "Strength"));
	}

	// An ordinary suspend/resume (the SAME sessionId recorded again
	// later) is NOT treated as a switch -- pending XP survives across it.
	@Test
	public void recordDelta_sameSessionIdAgainLater_isNotTreatedAsASwitch()
	{
		LiveXpTracker tracker = new LiveXpTracker();
		tracker.recordDelta("session-1", "Strength", 40L);
		tracker.recordDelta("session-1", "Strength", 5L);
		assertEquals(45L, tracker.getPending("session-1", "Strength"));
	}
}
