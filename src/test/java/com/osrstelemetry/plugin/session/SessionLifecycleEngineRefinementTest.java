package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.time.Instant;
import org.junit.Test;

/**
 * Pure,
 * deterministic tests for SessionLifecycleEngine.refineIdentity() --
 * its invariants (sessionId/startedAt/duration/aggregates
 * preservation, never finalizing, ActivityIdentity immutability) and
 * its exact-boundary runtime-ordering contract. Does not weaken
 * or duplicate the existing SessionLifecycleEngineTest.java coverage
 * of the base ACTIVE/SUSPENDED/FINALIZED machinery.
 */
public class SessionLifecycleEngineRefinementTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
	private static final ActivityIdentity GENERIC_COMBAT = ActivitySignalClassifier.genericCombatIdentity();
	private static final ActivityIdentity SLAYER_GARGOYLES = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
	private static final ActivityIdentity BOSSING_ZULRAH = ActivitySignalClassifier.bossIdentity("Zulrah");

	// Test 1: refineIdentity preserves sessionId.
	@Test
	public void refinePreservesSessionId()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		String sessionId = engine.getCurrentSession().getSessionId();
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		assertEquals(sessionId, engine.getCurrentSession().getSessionId());
	}

	// Test 2: refineIdentity preserves startedAt.
	@Test
	public void refinePreservesStartedAt()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		String startedAt = engine.getCurrentSession().getStartedAt();
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		assertEquals(startedAt, engine.getCurrentSession().getStartedAt());
	}

	// Test 3: refineIdentity accumulates duration exactly like an ordinary heartbeat -- never resets it.
	@Test
	public void refineAccumulatesDurationLikeAHeartbeat()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		engine.onQualifyingActivity(GENERIC_COMBAT, T0.plusSeconds(30));
		assertEquals(30_000L, engine.getCurrentSession().getAccumulatedActiveDurationMillis());

		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(50));
		assertEquals(50_000L, engine.getCurrentSession().getAccumulatedActiveDurationMillis());
	}

	// Test 4: refineIdentity preserves the same SessionAggregates instance (never replaced).
	@Test
	public void refinePreservesAggregatesInstance()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		SessionAggregates aggregates = engine.getCurrentSession().getAggregates();
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		assertSame(aggregates, engine.getCurrentSession().getAggregates());
	}

	// Test 5: refineIdentity actually changes the session's ActivityIdentity.
	@Test
	public void refineChangesActivityIdentity()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		assertEquals(BOSSING_ZULRAH, engine.getCurrentSession().getActivityIdentity());
	}

	// Test 6: refineIdentity never finalizes the session.
	@Test
	public void refineNeverFinalizes()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		assertNull(result.getFinalized());
		assertEquals(SessionState.ACTIVE, engine.getCurrentSession().getState());
	}

	// Test 7: refineIdentity with an out-of-order observedAt is rejected -- no mutation at all.
	@Test
	public void refineRejectsOutOfOrderTimestamp()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0.plusSeconds(30));
		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, T0);
		assertEquals(GENERIC_COMBAT, result.getCurrent().getActivityIdentity());
		assertEquals(GENERIC_COMBAT, engine.getCurrentSession().getActivityIdentity());
	}

	// Test 8: refineIdentity on an engine with no current session is a safe no-op.
	@Test
	public void refineOnEmptyEngineIsNoOp()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, T0);
		assertNull(result.getCurrent());
		assertNull(engine.getCurrentSession());
	}

	// Test 9: after a refinement, ordinary heartbeats against the new identity continue the same session normally.
	@Test
	public void heartbeatsAfterRefineContinueNormally()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		LifecycleResult result = engine.onQualifyingActivity(BOSSING_ZULRAH, T0.plusSeconds(20));
		assertNull(result.getFinalized());
		assertEquals(20_000L, engine.getCurrentSession().getAccumulatedActiveDurationMillis());
	}

	// Test 10: refineIdentity suspends the session (keeping the new identity) if observedAt has itself reached the 5-minute threshold.
	@Test
	public void refineSuspendsAtDeterministicThresholdWhenDue()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		Instant refineAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, refineAt);
		assertEquals(SessionState.SUSPENDED, result.getCurrent().getState());
		assertEquals(BOSSING_ZULRAH, result.getCurrent().getActivityIdentity());
		assertEquals(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).toString(), result.getCurrent().getSuspendedAt());
	}

	// Test 11: refineIdentity resumes a SUSPENDED session at or before its resume window, preserving sessionId/duration.
	@Test
	public void refineResumesSuspendedSessionWithinWindow()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		String sessionId = engine.getCurrentSession().getSessionId();
		long durationBefore = engine.getCurrentSession().getAccumulatedActiveDurationMillis();

		Instant resumeAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plus(SessionLifecycleEngine.RESUME_WINDOW).minusSeconds(1);
		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, resumeAt);
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(sessionId, result.getCurrent().getSessionId());
		assertEquals(durationBefore, result.getCurrent().getAccumulatedActiveDurationMillis());
		assertEquals(BOSSING_ZULRAH, result.getCurrent().getActivityIdentity());
		assertNull(result.getCurrent().getSuspendedAt());
	}

	// Test 12: refineIdentity resumes exactly AT the resume-window boundary (boundary-inclusive).
	@Test
	public void refineResumesExactlyAtResumeWindowBoundary()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		Instant expiry = engine.getCurrentSession().resumeWindowExpiresAtInstant();

		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, expiry);
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(BOSSING_ZULRAH, result.getCurrent().getActivityIdentity());
	}

	// Test 13: refineIdentity strictly after the resume window is a no-op -- it must never resurrect an expired session.
	@Test
	public void refineDoesNotResurrectExpiredSuspendedSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(SLAYER_GARGOYLES, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		Instant expiry = engine.getCurrentSession().resumeWindowExpiresAtInstant();

		LifecycleResult result = engine.refineIdentity(BOSSING_ZULRAH, expiry.plusSeconds(1));
		assertEquals(SessionState.SUSPENDED, result.getCurrent().getState());
		assertEquals(SLAYER_GARGOYLES, result.getCurrent().getActivityIdentity());
	}

	// Test 14: refining does not mutate the original ActivityIdentity object -- ActivityIdentity stays immutable.
	@Test
	public void refineDoesNotMutateOriginalIdentityObject()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(10));
		assertEquals(ActivityType.COMBAT, GENERIC_COMBAT.getActivityType());
		assertEquals("combat", GENERIC_COMBAT.getActivityKey());
	}

	// Test 15: the engine itself trusts its caller and permits a second refinement in sequence -- sessionId/startedAt still preserved.
	@Test
	public void sequentialRefinementsStillPreserveIdentityInvariants()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0);
		String sessionId = engine.getCurrentSession().getSessionId();
		String startedAt = engine.getCurrentSession().getStartedAt();

		engine.refineIdentity(SLAYER_GARGOYLES, T0.plusSeconds(10));
		engine.refineIdentity(BOSSING_ZULRAH, T0.plusSeconds(20));

		assertEquals(sessionId, engine.getCurrentSession().getSessionId());
		assertEquals(startedAt, engine.getCurrentSession().getStartedAt());
		assertEquals(BOSSING_ZULRAH, engine.getCurrentSession().getActivityIdentity());
	}

	// Test 16: an out-of-order refineIdentity call never moves lastActiveAt backwards.
	@Test
	public void refineOutOfOrderDoesNotMoveLastActiveAt()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GENERIC_COMBAT, T0.plusSeconds(30));
		String lastActiveBefore = engine.getCurrentSession().getLastActiveAt();
		engine.refineIdentity(BOSSING_ZULRAH, T0);
		assertEquals(lastActiveBefore, engine.getCurrentSession().getLastActiveAt());
	}

	// Test 17: the runtime-ordering contract -- processing a genuine same-identity/refinement event AT the
	// resume-window boundary before any timeout advancement resumes the session; advancing time to that same
	// instant FIRST, with no event, finalizes it instead. A future coordinator must always process pending events
	// for instant T before applying timeout advancement for T.
	@Test
	public void eventBeforeTimeoutAdvancementResumesWhileTimeoutAloneFinalizes()
	{
		SessionLifecycleEngine eventFirstEngine = new SessionLifecycleEngine();
		eventFirstEngine.onQualifyingActivity(SLAYER_GARGOYLES, T0);
		eventFirstEngine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		Instant expiry = eventFirstEngine.getCurrentSession().resumeWindowExpiresAtInstant();
		eventFirstEngine.refineIdentity(BOSSING_ZULRAH, expiry);
		assertEquals(SessionState.ACTIVE, eventFirstEngine.getCurrentSession().getState());

		SessionLifecycleEngine timeoutFirstEngine = new SessionLifecycleEngine();
		timeoutFirstEngine.onQualifyingActivity(SLAYER_GARGOYLES, T0);
		timeoutFirstEngine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		Instant sameExpiry = timeoutFirstEngine.getCurrentSession().resumeWindowExpiresAtInstant();
		LifecycleResult timeoutResult = timeoutFirstEngine.advanceTime(sameExpiry);
		assertNull(timeoutFirstEngine.getCurrentSession());
		assertEquals(SessionState.FINALIZED, timeoutResult.getFinalized().getState());
	}
}
