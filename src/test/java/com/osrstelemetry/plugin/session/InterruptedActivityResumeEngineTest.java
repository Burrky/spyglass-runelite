package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Dedicated engine-level coverage for the INTERRUPTED-ACTIVITY RESUME
 * (A -> brief B -> A) feature: a REAL, CONFIRMED activity switch away
 * from a genuinely ACTIVE session (A) parks A as the ONE resumable
 * interrupted candidate instead of finalizing it immediately, so a
 * brief, genuinely-real B that is shortly followed by A's own evidence
 * returning resumes A with its ORIGINAL sessionId/startedAt, while B
 * still becomes its own separate, real, finalized History session.
 * Entirely against the real, pure, production SessionLifecycleEngine --
 * no mocking, no test-duplicated decision logic, matching
 * SessionLifecycleEngineTest's own convention.
 *
 * Every genuine "B" switch below is driven with EvidenceStrength.SPECIFIC
 * (an immediate, ungated switch -- a real BOSS_KILL/authoritative
 * SLAYER_TASK_PROGRESS in practice) purely so each test's own real-switch
 * moment is a single, deterministic call, rather than re-proving the
 * separate ORDINARY weak-evidence arm/confirm machinery
 * SessionLifecycleEngineTest already exhaustively covers (including its
 * own now-adapted interrupted-resume assertions) -- this file is about
 * what happens to the OUTGOING session once a real switch has already
 * been decided, and about the feature's own restart/candidate-lifecycle
 * contract, not about how the switch DECISION itself is reached.
 */
public class InterruptedActivityResumeEngineTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static final ActivityIdentity WOODCUTTING =
		new ActivityIdentity(ActivityType.SKILLING, "woodcutting", "Woodcutting");
	private static final ActivityIdentity CRAFTING =
		new ActivityIdentity(ActivityType.SKILLING, "crafting", "Crafting");
	private static final ActivityIdentity COOKING =
		new ActivityIdentity(ActivityType.SKILLING, "cooking", "Cooking");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");
	private static final ActivityIdentity ZULRAH =
		new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah");
	private static final ActivityIdentity GENERIC_COMBAT =
		new ActivityIdentity(ActivityType.COMBAT, "combat", "Combat");
	private static final ActivityIdentity GENERIC_COMBAT_RAT =
		new ActivityIdentity(ActivityType.COMBAT, "combat:rat", "Rat combat");

	private static long totalXp(Session session, String skill)
	{
		Long value = session.getAggregates().getXpGainedBySkill().get(skill);
		return value == null ? 0L : value;
	}

	private static void apply(Session target, List<MetricUpdate> metrics)
	{
		for (MetricUpdate metric : metrics)
		{
			SessionAggregateUpdater.apply(target.getAggregates(), metric);
		}
	}

	// =====================================================================
	// REQUIRED TEST 1: ACTIVE A -> genuine B -> A inside 5 minutes --
	// same A sessionId, same A startedAt, A active duration excludes the
	// B interval, B finalized separately.
	// =====================================================================
	@Test
	public void activeA_briefRealB_returnsWithinWindow_resumesSameSessionIdAndStartedAt_excludesBIntervalFromDuration()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		Session a = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent();
		String aSessionId = a.getSessionId();
		String aStartedAt = a.getStartedAt();

		// A genuine, immediate (SPECIFIC-strength) switch to Crafting --
		// this IS a real, decided switch, exactly like a real BOSS_KILL
		// would be for a combat identity.
		Instant t1 = T0.plusSeconds(150);
		LifecycleResult switched = engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertNull("A must be PARKED, not immediately finalized", switched.getFinalized());
		assertEquals(aSessionId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(WOODCUTTING, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(t1, engine.getInterruptedCandidateInterruptedAt());
		assertEquals(t1.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW), engine.getInterruptedCandidateExpiresAt());
		assertEquals(SessionState.ACTIVE, engine.getInterruptedCandidate().getState());
		assertEquals(CRAFTING, switched.getCurrent().getActivityIdentity());
		String bSessionId = switched.getCurrent().getSessionId();
		assertFalse(aSessionId.equals(bSessionId));

		// Genuine return to Woodcutting, well inside the 5-minute window.
		Instant t2 = t1.plusSeconds(120);
		LifecycleResult resumed = engine.onQualifyingActivity(WOODCUTTING, t2, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertNull("A must have no resumable candidate left once it has resumed", engine.getInterruptedCandidate());
		assertEquals("B must finalize as its own, separate History session", SessionState.FINALIZED, resumed.getFinalized().getState());
		assertEquals(bSessionId, resumed.getFinalized().getSessionId());
		assertEquals(CRAFTING, resumed.getFinalized().getActivityIdentity());
		assertEquals(t2.toString(), resumed.getFinalized().getFinalizedAt());

		assertEquals("A must resume with the SAME sessionId", aSessionId, resumed.getCurrent().getSessionId());
		assertEquals("A must resume with the SAME original startedAt", aStartedAt, resumed.getCurrent().getStartedAt());
		assertEquals(SessionState.ACTIVE, resumed.getCurrent().getState());
		assertEquals(WOODCUTTING, resumed.getCurrent().getActivityIdentity());

		// T1 -> T2 (the entire B interval) must contribute ZERO active
		// duration to A: A had accumulated zero duration before the
		// switch (a single heartbeat), and a further reinforcing A
		// heartbeat now must add ONLY its own elapsed time from T2, never
		// anything spanning the T1..T2 interruption gap.
		assertEquals(0L, resumed.getCurrent().getAccumulatedActiveDurationMillis());
		Instant t3 = t2.plusSeconds(30);
		LifecycleResult reinforced = engine.onQualifyingActivity(WOODCUTTING, t3);
		assertEquals("only the post-resume 30s must be counted -- never the T1..T2 interruption gap",
			30_000L, reinforced.getCurrent().getAccumulatedActiveDurationMillis());
	}

	// =====================================================================
	// REQUIRED TEST 2: metric ownership -- A XP/loot stays A, B XP/loot
	// stays B, returning A's own metrics go to A, exact once.
	// =====================================================================
	@Test
	public void metricOwnership_aAndBEachKeepOnlyTheirOwnMetrics_returningAMetricsGoToAExactlyOnce()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		LifecycleResult started = engine.onQualifyingActivity(WOODCUTTING, T0, Arrays.asList(MetricUpdate.xp("woodcutting", 50)));
		apply(started.getCurrent(), started.getMetricsForCurrent());
		String aSessionId = started.getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult switched = engine.onQualifyingActivity(CRAFTING, t1,
			Arrays.asList(MetricUpdate.xp("crafting", 10)), EvidenceStrength.SPECIFIC);
		apply(switched.getCurrent(), switched.getMetricsForCurrent());

		Instant t1b = t1.plusSeconds(30);
		LifecycleResult moreB = engine.onQualifyingActivity(CRAFTING, t1b, Arrays.asList(MetricUpdate.xp("crafting", 15)));
		apply(moreB.getCurrent(), moreB.getMetricsForCurrent());

		Instant t2 = t1.plusSeconds(90);
		LifecycleResult resumed = engine.onQualifyingActivity(WOODCUTTING, t2,
			Arrays.asList(MetricUpdate.xp("woodcutting", 20)), EvidenceStrength.SPECIFIC);
		apply(resumed.getCurrent(), resumed.getMetricsForCurrent());

		assertEquals("A must own its pre-interruption XP plus its own returning XP, exactly once",
			70L, totalXp(resumed.getCurrent(), "woodcutting"));
		assertEquals("A must never receive any of B's Crafting XP", 0L, totalXp(resumed.getCurrent(), "crafting"));

		assertEquals("B's finalized record must own exactly its own combined XP, exactly once",
			25L, totalXp(resumed.getFinalized(), "crafting"));
		assertEquals("B must never receive any of A's Woodcutting XP", 0L, totalXp(resumed.getFinalized(), "woodcutting"));
		assertEquals(aSessionId, resumed.getCurrent().getSessionId());
	}

	// =====================================================================
	// REQUIRED TEST 3: return exactly AT the interruption-window boundary
	// resumes A ("at or before" per spec).
	//
	// B is deliberately given its OWN reinforcing heartbeat partway
	// through, pushing its own unrelated idle-suspend threshold safely
	// past whatever boundary this test checks -- otherwise
	// INTERRUPTED_RESUME_WINDOW == SUSPEND_TIMEOUT and both start
	// ticking from the SAME instant T1, so B's own idle-suspend would
	// otherwise coincidentally fire at the exact same instant this test
	// means to probe, testing the wrong thing.
	// =====================================================================
	@Test
	public void returnExactlyAtInterruptionWindowBoundary_resumesA()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		engine.onQualifyingActivity(CRAFTING, t1.plusSeconds(200));

		Instant boundary = t1.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW);
		LifecycleResult resumed = engine.onQualifyingActivity(WOODCUTTING, boundary, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals("exactly AT the boundary must still resume A -- 'at or before' per spec",
			aSessionId, resumed.getCurrent().getSessionId());
		assertEquals(SessionState.ACTIVE, resumed.getCurrent().getState());
		assertNull(engine.getInterruptedCandidate());
	}

	// =====================================================================
	// REQUIRED TEST 4: return just AFTER the boundary does NOT resume A;
	// A already finalizes at T1 (never fabricating extra active minutes);
	// the returning evidence is a brand-new sessionId.
	// =====================================================================
	@Test
	public void returnJustAfterInterruptionWindowBoundary_doesNotResumeA_aAlreadyFinalizedAtT1_returningAIsNewSessionId()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		engine.onQualifyingActivity(CRAFTING, t1.plusSeconds(200));

		Instant justAfterBoundary = t1.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusMillis(1);
		LifecycleResult result = engine.onQualifyingActivity(WOODCUTTING, justAfterBoundary, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals("A must finalize via the background expiry path, exactly once, AT T1 -- never at the "
			+ "later moment this happened to be noticed, never fabricating extra active minutes",
			SessionState.FINALIZED, result.getAdditionalFinalized().getState());
		assertEquals(aSessionId, result.getAdditionalFinalized().getSessionId());
		assertEquals(t1.toString(), result.getAdditionalFinalized().getFinalizedAt());

		assertEquals("the returning Woodcutting evidence must be a BRAND NEW session, never A's original one",
			WOODCUTTING, result.getCurrent().getActivityIdentity());
		assertFalse(aSessionId.equals(result.getCurrent().getSessionId()));

		// CORRECTED (was stale): A's own candidacy is gone for good (asserted
		// above via additionalFinalized), but THIS SAME event is itself ALSO a
		// genuine ACTIVE -> different-identity real switch -- B (Crafting) was
		// still genuinely ACTIVE (reinforced at t1+200s, nowhere near its own
		// idle timeout) when the Woodcutting evidence arrived, so B is now
		// freshly parked as the NEW interrupted candidate, per the feature's
		// universal design (applied to every genuine ACTIVE -> different-
		// identity switch, not a leftover of A's own, now-dead candidacy).
		assertEquals("B is now the freshly-parked candidate (not null, and not A)",
			bSessionId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(CRAFTING, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(justAfterBoundary, engine.getInterruptedCandidateInterruptedAt());
	}

	// =====================================================================
	// REQUIRED TEST 5: candidate expiry while B remains current -- A
	// finalizes at T1, B is completely unaffected.
	// =====================================================================
	@Test
	public void candidateExpiry_whileBRemainsCurrent_aFinalizesAtT1_bUnchanged()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = engine.getCurrentSession().getSessionId();

		// Keep B safely ACTIVE (well within its own unrelated 5-minute
		// idle window) all the way past A's interrupted-resume window
		// boundary -- see the boundary tests' own comment for why this
		// matters.
		engine.onQualifyingActivity(CRAFTING, t1.plusSeconds(200));

		Instant pastAWindow = t1.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1);
		LifecycleResult advanced = engine.advanceTime(pastAWindow);

		assertEquals("A must finalize via the background expiry path, exactly once, at T1",
			SessionState.FINALIZED, advanced.getAdditionalFinalized().getState());
		assertEquals(aSessionId, advanced.getAdditionalFinalized().getSessionId());
		assertEquals(t1.toString(), advanced.getAdditionalFinalized().getFinalizedAt());

		assertNull("B's own lifecycle must be completely untouched by A's unrelated candidate expiry",
			advanced.getFinalized());
		assertEquals(bSessionId, advanced.getCurrent().getSessionId());
		assertEquals(SessionState.ACTIVE, advanced.getCurrent().getState());
		assertNull(engine.getInterruptedCandidate());
	}

	// =====================================================================
	// REQUIRED TEST 6: A -> B -> C. A finalizes (displaced); B becomes
	// the ONE candidate; a later return to A must NOT resurrect it.
	// =====================================================================
	@Test
	public void aThenBThenC_aDisplacedAndFinalized_bBecomesTheOnlyCandidate_laterReturnToACannotResurrectIt()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		assertNull(toB.getFinalized());
		assertEquals(aSessionId, engine.getInterruptedCandidate().getSessionId());

		// B -> C while A is still parked, well within A's own window.
		Instant t1b = t1.plusSeconds(30);
		LifecycleResult toC = engine.onQualifyingActivity(COOKING, t1b, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertNull("B parking must not itself finalize anything via the PRIMARY slot", toC.getFinalized());
		assertEquals("A must be displaced/finalized permanently, AT ITS OWN interruption instant, never t1b",
			SessionState.FINALIZED, toC.getAdditionalFinalized().getState());
		assertEquals(aSessionId, toC.getAdditionalFinalized().getSessionId());
		assertEquals(t1.toString(), toC.getAdditionalFinalized().getFinalizedAt());

		// Exactly ONE candidate afterward: B, never A.
		assertEquals(bSessionId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(CRAFTING, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(t1b, engine.getInterruptedCandidateInterruptedAt());
		assertEquals(COOKING, toC.getCurrent().getActivityIdentity());

		// A later return to Woodcutting (A's own original identity) must
		// NOT resurrect A -- A is permanently gone; this is a brand-new
		// session, and it displaces B (the one candidate at the time) in
		// the process.
		Instant t2 = t1b.plusSeconds(30);
		LifecycleResult returnToWoodcutting = engine.onQualifyingActivity(WOODCUTTING, t2, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertFalse("A must never resurrect once superseded by a second real switch",
			aSessionId.equals(returnToWoodcutting.getCurrent().getSessionId()));
		assertEquals(WOODCUTTING, returnToWoodcutting.getCurrent().getActivityIdentity());
		assertEquals(bSessionId, returnToWoodcutting.getAdditionalFinalized().getSessionId());
	}

	// B can still resume from C, from ITS OWN window, once it is the
	// current candidate -- the direct counterpart to the test above.
	@Test
	public void aThenBThenC_bResumesFromCWithinItsOwnWindow()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(WOODCUTTING, T0);

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		String bStartedAt = toB.getCurrent().getStartedAt();

		Instant t1b = t1.plusSeconds(30);
		engine.onQualifyingActivity(COOKING, t1b, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String cSessionId = engine.getCurrentSession().getSessionId();

		// Return to Crafting (B's own identity) within B's own 5-minute
		// interrupted-resume window (anchored at t1b, B's own
		// interruption instant).
		Instant t2 = t1b.plusSeconds(60);
		LifecycleResult resumed = engine.onQualifyingActivity(CRAFTING, t2, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals("B must resume with its OWN original sessionId", bSessionId, resumed.getCurrent().getSessionId());
		assertEquals("B must resume with its OWN original startedAt", bStartedAt, resumed.getCurrent().getStartedAt());
		assertEquals(SessionState.ACTIVE, resumed.getCurrent().getState());
		assertEquals("C must finalize as its own separate History session", SessionState.FINALIZED, resumed.getFinalized().getState());
		assertEquals(cSessionId, resumed.getFinalized().getSessionId());
		assertNull(engine.getInterruptedCandidate());
	}

	// =====================================================================
	// REQUIRED TEST 7: no candidate tree -- at most one resumable
	// interrupted prior session ever exists, across a longer chain.
	// =====================================================================
	@Test
	public void atMostOneResumableInterruptedCandidateEver_acrossALongerChain()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(WOODCUTTING, T0);

		Instant t1 = T0.plusSeconds(60);
		engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertEquals(WOODCUTTING, engine.getInterruptedCandidate().getActivityIdentity());

		Instant t2 = t1.plusSeconds(30);
		engine.onQualifyingActivity(COOKING, t2, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertEquals("only ONE candidate may ever exist -- the most recently displaced session, never a tree",
			CRAFTING, engine.getInterruptedCandidate().getActivityIdentity());

		Instant t3 = t2.plusSeconds(30);
		engine.onQualifyingActivity(ZULRAH, t3, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertEquals(COOKING, engine.getInterruptedCandidate().getActivityIdentity());

		Instant t4 = t3.plusSeconds(30);
		engine.onQualifyingActivity(GARGOYLES, t4, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertEquals(ZULRAH, engine.getInterruptedCandidate().getActivityIdentity());
	}

	// =====================================================================
	// REQUIRED TEST 8: a weak/unconfirmed B challenger does NOT park A.
	// =====================================================================
	@Test
	public void weakUnconfirmedChallenger_doesNotParkA()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		LifecycleResult result = engine.onQualifyingActivity(CRAFTING, T0.plusSeconds(15),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertNull(result.getFinalized());
		assertNull("a single incidental ORDINARY observation must never park A -- only a genuinely "
			+ "CONFIRMED switch may", engine.getInterruptedCandidate());
		assertEquals(aSessionId, result.getCurrent().getSessionId());
		assertEquals(WOODCUTTING, result.getCurrent().getActivityIdentity());
	}

	// =====================================================================
	// REQUIRED TEST 9: identity refinement does NOT park A; sessionId
	// stays unchanged.
	// =====================================================================
	@Test
	public void identityRefinement_doesNotParkA_sessionIdUnchanged()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String sessionId = engine.onQualifyingActivity(GENERIC_COMBAT, T0).getCurrent().getSessionId();
		String startedAt = engine.getCurrentSession().getStartedAt();

		LifecycleResult refined = engine.refineIdentity(GARGOYLES, T0.plusSeconds(10));

		assertNull(refined.getFinalized());
		assertNull("identity refinement must never create a resumable interrupted candidate -- it is a "
			+ "correction of the SAME session's own identity, never a real switch away from it",
			engine.getInterruptedCandidate());
		assertEquals(sessionId, refined.getCurrent().getSessionId());
		assertEquals(startedAt, refined.getCurrent().getStartedAt());
		assertEquals(GARGOYLES, refined.getCurrent().getActivityIdentity());
	}

	// =====================================================================
	// REQUIRED TEST 10: an outgoing already-SUSPENDED A uses the
	// existing 30-minute resume behavior, unchanged -- no second
	// interrupted-candidate mechanism is ever created for it.
	// =====================================================================
	@Test
	public void outgoingAlreadySuspendedSession_usesExisting30MinuteResumeBehavior_neverCreatesInterruptedCandidate()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		LifecycleResult suspendResult = engine.advanceTime(suspendedAt);
		assertEquals(SessionState.SUSPENDED, suspendResult.getCurrent().getState());
		assertNull("a mere idle suspend must never itself create an interrupted candidate",
			engine.getInterruptedCandidate());

		// A genuine different-activity switch away from the SUSPENDED
		// session -- this must go through the existing, completely
		// untouched SUSPENDED-branch immediate-finalize path, never the
		// interrupted-resume PARK mechanism (which only ever populates
		// from a genuinely ACTIVE outgoing session -- see
		// applyRealSwitch()'s own javadoc).
		Instant switchAt = suspendedAt.plusSeconds(60);
		LifecycleResult switched = engine.onQualifyingActivity(CRAFTING, switchAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals("a real switch away from an already-SUSPENDED session must finalize it IMMEDIATELY, "
			+ "exactly as before this feature -- never parked", SessionState.FINALIZED, switched.getFinalized().getState());
		assertEquals(aSessionId, switched.getFinalized().getSessionId());
		assertEquals(switchAt.toString(), switched.getFinalized().getFinalizedAt());
		assertNull("no second interrupted-candidate mechanism may ever be created for an outgoing "
			+ "SUSPENDED session", engine.getInterruptedCandidate());
		assertEquals(CRAFTING, switched.getCurrent().getActivityIdentity());
	}

	// =====================================================================
	// REQUIRED TEST 11: client restart inside the window -- current B
	// reloads (conservatively SUSPENDED, per the engine's own
	// pre-existing, unrelated rehydration contract), the interrupted A
	// candidate reloads, and a genuine return to A still resumes its
	// ORIGINAL sessionId (exercising tryResumeInterruptedCandidate()'s
	// own SUSPENDED-branch resume check -- see its javadoc for why this
	// is necessary at all: without it, B always reloading SUSPENDED
	// after ANY restart would otherwise silently strand an
	// otherwise-still-valid parked candidate).
	// =====================================================================
	@Test
	public void restartInsideWindow_bReloadsConservativelySuspended_returningAStillResumesOriginalSessionId()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();
		String aStartedAt = liveEngine.getCurrentSession().getStartedAt();

		Instant t1 = T0.plusSeconds(60);
		liveEngine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant persistedExpiresAt = liveEngine.getInterruptedCandidateExpiresAt();

		// Restart 2 minutes later -- comfortably inside the still-open
		// 5-minute window.
		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine.RehydrationResult rehydrated = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, persistedExpiresAt);

		assertNull("the candidate is still within its own window -- restart must never finalize it",
			rehydrated.getFinalizedInterruptedCandidateDuringRehydration());
		SessionLifecycleEngine engine = rehydrated.getEngine();
		assertEquals("B must reload as the current session", persistedCurrent.getSessionId(),
			engine.getCurrentSession().getSessionId());
		// Pre-existing, unrelated conservative-rehydration behavior: ANY
		// restored persisted-ACTIVE session always reloads SUSPENDED,
		// regardless of true elapsed wall time -- exactly the case
		// tryResumeInterruptedCandidate() exists to handle.
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());
		assertEquals("the interrupted A candidate must reload, unchanged", aSessionId,
			engine.getInterruptedCandidate().getSessionId());

		// A genuine return to Woodcutting, still inside the reloaded
		// candidate's own window.
		Instant returnAt = t1.plusSeconds(150);
		LifecycleResult resumed = engine.onQualifyingActivity(WOODCUTTING, returnAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals("the reloaded candidate must resume with its ORIGINAL sessionId", aSessionId,
			resumed.getCurrent().getSessionId());
		assertEquals("the reloaded candidate must resume with its ORIGINAL startedAt", aStartedAt,
			resumed.getCurrent().getStartedAt());
		assertEquals(SessionState.ACTIVE, resumed.getCurrent().getState());
		assertNull(engine.getInterruptedCandidate());
	}

	// =====================================================================
	// REQUIRED TEST 15: candidate expiry and B's OWN independent
	// lifecycle event landing on the SAME advanceTime() call -- neither
	// finalization is lost or duplicated. Deliberately uses the ACTUAL
	// coincidental-clock scenario the spec calls out (B never
	// reinforced, so its own idle-suspend threshold and A's own
	// interrupted-resume window both start ticking from the SAME
	// instant T1), rather than an artificially engineered collision.
	// =====================================================================
	@Test
	public void candidateExpiryAndCurrentSessionLifecycleEvent_sameProcessingStep_neitherFinalizationLost()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aSessionId = engine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = engine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();

		// B never receives any further reinforcement, so its OWN
		// idle-suspend-then-resume-window-expiry clock and A's own
		// interrupted-resume-window clock both start ticking from the
		// SAME instant T1 (INTERRUPTED_RESUME_WINDOW == SUSPEND_TIMEOUT).
		Instant farBeyondBoth = t1.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT)
			.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(1);

		LifecycleResult advanced = engine.advanceTime(farBeyondBoth);

		assertEquals("B's own lifecycle must still independently finalize via its ordinary idle-suspend-"
			+ "then-resume-window-expiry path", SessionState.FINALIZED, advanced.getFinalized().getState());
		assertEquals(bSessionId, advanced.getFinalized().getSessionId());

		assertEquals("A's parked-candidate expiry must ALSO be reported, in the SAME call, via the "
			+ "SEPARATE additionalFinalized slot -- never dropped because the primary finalized slot was "
			+ "already used by B this same call", SessionState.FINALIZED, advanced.getAdditionalFinalized().getState());
		assertEquals(aSessionId, advanced.getAdditionalFinalized().getSessionId());
		assertEquals(t1.toString(), advanced.getAdditionalFinalized().getFinalizedAt());

		assertNull(advanced.getCurrent());
		assertNull(engine.getInterruptedCandidate());
	}

	// =====================================================================
	// GAP FIX REGRESSION (SUSPENDED-branch weak-evidence CONFIRM ->
	// resume): the pre-existing SUSPENDED two-observation weak-evidence
	// arm/confirm machinery (pendingCandidateIdentity et al.) is a
	// SEPARATE call site from the SUSPENDED-branch's own two
	// already-fixed IMMEDIATE-switch call sites. B always reloads
	// SUSPENDED after a restart (see restartInsideWindow... above), so an
	// ORDINARY-strength return to A -- the completely normal case for a
	// generic SKILLING/COMBAT activity, which almost never generates
	// SPECIFIC/AUTHORITATIVE evidence -- must ALSO resume the parked A
	// once CONFIRMED, not mint a brand-new session. This does not touch
	// whether/when the weak-evidence arm/confirm machinery itself decides
	// to arm or confirm -- only what happens to an ALREADY-confirmed real
	// switch.
	// =====================================================================

	// REQUIRED TEST: generic SKILLING restart case -- Woodcutting A ->
	// genuine Crafting B -> restart while B is current -> B rehydrates
	// SUSPENDED -> first ORDINARY Woodcutting observation arms the
	// existing SUSPENDED weak candidate (must NOT resume yet) -> second
	// COHERENT Woodcutting observation confirms (must resume A with its
	// ORIGINAL sessionId/startedAt, exclude the interruption gap, and
	// finalize B separately; the combined buffered+confirming metrics
	// land on restored A exactly once; A's own resumability is fully
	// consumed -- it must never separately expire/finalize afterward).
	@Test
	public void restartInsideWindow_bReloadsSuspended_genericSkillingOrdinaryTwoObservationConfirm_resumesOriginalA()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();
		String aStartedAt = liveEngine.getCurrentSession().getStartedAt();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = liveEngine.onQualifyingActivity(CRAFTING, t1,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant persistedExpiresAt = liveEngine.getInterruptedCandidateExpiresAt();

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine.RehydrationResult rehydrated = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, persistedExpiresAt);
		SessionLifecycleEngine engine = rehydrated.getEngine();
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());
		assertEquals(aSessionId, engine.getInterruptedCandidate().getSessionId());

		// First ORDINARY Woodcutting observation: ARMS the existing
		// SUSPENDED weak-evidence candidate only.
		Instant firstReturn = restartAt.plusSeconds(10);
		LifecycleResult armed = engine.onQualifyingActivity(WOODCUTTING, firstReturn,
			Arrays.asList(MetricUpdate.xp("woodcutting", 7)), EvidenceStrength.ORDINARY);

		assertNull("a single arming observation must not finalize anything", armed.getFinalized());
		assertNull("a single arming observation must not finalize anything via the additional slot either",
			armed.getAdditionalFinalized());
		assertEquals("a single arming observation must not resume A -- B remains current",
			bSessionId, engine.getCurrentSession().getSessionId());
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());
		assertEquals("the interrupted candidate must be completely untouched by a mere arming observation",
			aSessionId, engine.getInterruptedCandidate().getSessionId());

		// Second, COHERENT Woodcutting observation: CONFIRMS the real
		// switch -- this is exactly the fixed gap.
		Instant secondReturn = firstReturn.plusSeconds(10);
		LifecycleResult confirmed = engine.onQualifyingActivity(WOODCUTTING, secondReturn,
			Arrays.asList(MetricUpdate.xp("woodcutting", 3)), EvidenceStrength.ORDINARY);

		assertEquals("the ORIGINAL A sessionId must return -- never a new session", aSessionId,
			confirmed.getCurrent().getSessionId());
		assertEquals("the ORIGINAL A startedAt must return", aStartedAt, confirmed.getCurrent().getStartedAt());
		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());

		assertEquals("B must finalize as its own separate History session, anchored at the candidate's "
			+ "own first-seen instant (firstReturn), never the later confirming event's timestamp",
			SessionState.FINALIZED, confirmed.getFinalized().getState());
		assertEquals(bSessionId, confirmed.getFinalized().getSessionId());
		assertEquals(firstReturn.toString(), confirmed.getFinalized().getFinalizedAt());

		assertNull("the original parked A must never separately expire/finalize afterward -- it has "
			+ "already fully resumed", engine.getInterruptedCandidate());

		apply(confirmed.getCurrent(), confirmed.getMetricsForCurrent());
		assertEquals("the combined buffered-plus-confirming returning-A metrics must land on restored A, "
			+ "exactly once", 10L, totalXp(confirmed.getCurrent(), "woodcutting"));
	}

	// REQUIRED TEST: the generic COMBAT (COMBAT-vs-COMBAT, same-rank,
	// different-key) equivalent of the test above -- the same
	// SUSPENDED-branch weak-evidence arm/confirm path also gates ordinary
	// evidence against an established combat-branch session (see
	// isWeakEvidenceAgainstEstablishedSession()'s own COMBAT-vs-COMBAT
	// same-rank handling), so it must resume through the identical fix.
	@Test
	public void restartInsideWindow_bReloadsSuspended_genericCombatOrdinaryTwoObservationConfirm_resumesOriginalA()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(GENERIC_COMBAT, T0).getCurrent().getSessionId();
		String aStartedAt = liveEngine.getCurrentSession().getStartedAt();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = liveEngine.onQualifyingActivity(GENERIC_COMBAT_RAT, t1,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant persistedExpiresAt = liveEngine.getInterruptedCandidateExpiresAt();

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine.RehydrationResult rehydrated = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, persistedExpiresAt);
		SessionLifecycleEngine engine = rehydrated.getEngine();
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());
		assertEquals(aSessionId, engine.getInterruptedCandidate().getSessionId());

		Instant firstReturn = restartAt.plusSeconds(10);
		LifecycleResult armed = engine.onQualifyingActivity(GENERIC_COMBAT, firstReturn,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull(armed.getFinalized());
		assertEquals("a single arming observation must not resume A", bSessionId,
			engine.getCurrentSession().getSessionId());
		assertEquals(aSessionId, engine.getInterruptedCandidate().getSessionId());

		Instant secondReturn = firstReturn.plusSeconds(10);
		LifecycleResult confirmed = engine.onQualifyingActivity(GENERIC_COMBAT, secondReturn,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertEquals("the ORIGINAL A sessionId must return", aSessionId, confirmed.getCurrent().getSessionId());
		assertEquals("the ORIGINAL A startedAt must return", aStartedAt, confirmed.getCurrent().getStartedAt());
		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());
		assertEquals(bSessionId, confirmed.getFinalized().getSessionId());
		assertEquals(firstReturn.toString(), confirmed.getFinalized().getFinalizedAt());
		assertNull(engine.getInterruptedCandidate());
	}

	// CORRECTED (per explicit follow-up instruction): a CONFIRMED weak
	// candidate that does NOT match the parked A's identity is a
	// genuine second real switch (A -> B -> C, generalized to B being
	// SUSPENDED) -- A can never remain resumable across it, or
	// "A -> B -> restart -> C -> A" could resurrect A across two
	// genuine activity changes, violating the one-interruption model.
	// A must be permanently finalized HERE, at its own ORIGINAL
	// interruption instant, and cleared -- never merely left dangling
	// for its own time-based expiry to eventually clean up. B must NOT
	// be parked as a new candidate (SUSPENDED outgoing sessions are
	// never parked).
	@Test
	public void confirmedWeakCandidate_notMatchingParkedA_finalizesAAndCreatesNewC()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();
		String aStartedAt = liveEngine.getCurrentSession().getStartedAt();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = liveEngine.onQualifyingActivity(CRAFTING, t1,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant persistedExpiresAt = liveEngine.getInterruptedCandidateExpiresAt();

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine engine = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, persistedExpiresAt).getEngine();

		// Weak evidence for a THIRD identity (Cooking) -- not A's own
		// (Woodcutting) -- arms and then confirms a genuine B -> C switch.
		Instant firstReturn = restartAt.plusSeconds(10);
		engine.onQualifyingActivity(COOKING, firstReturn, Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		Instant secondReturn = firstReturn.plusSeconds(10);
		LifecycleResult confirmed = engine.onQualifyingActivity(COOKING, secondReturn,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertEquals("C gets a brand new session", COOKING, confirmed.getCurrent().getActivityIdentity());
		assertFalse("C must never reuse A's own sessionId",
			aSessionId.equals(confirmed.getCurrent().getSessionId()));
		assertEquals("B must still finalize normally, at its own candidate-first-seen instant",
			bSessionId, confirmed.getFinalized().getSessionId());
		assertEquals(firstReturn.toString(), confirmed.getFinalized().getFinalizedAt());

		assertEquals("parked A must be finalized EXACTLY ONCE, via the additionalFinalized slot",
			SessionState.FINALIZED, confirmed.getAdditionalFinalized().getState());
		assertEquals(aSessionId, confirmed.getAdditionalFinalized().getSessionId());
		assertEquals("A's finalizedAt must be its ORIGINAL interruption timestamp (t1), never firstReturn/secondReturn",
			t1.toString(), confirmed.getAdditionalFinalized().getFinalizedAt());

		assertNull("the interrupted candidate slot must become null -- A is permanently gone",
			engine.getInterruptedCandidate());

		// A can never later resume -- a subsequent return to Woodcutting is a
		// brand new session, exactly like the pre-existing A -> B -> C test.
		Instant laterReturn = secondReturn.plusSeconds(10);
		LifecycleResult laterAttempt = engine.onQualifyingActivity(WOODCUTTING, laterReturn,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertFalse("A cannot resurrect once permanently superseded by a second real switch",
			aSessionId.equals(laterAttempt.getCurrent().getSessionId()));
		assertFalse("the later session must not merely coincide with A's own startedAt either",
			aStartedAt.equals(laterAttempt.getCurrent().getStartedAt()));
	}

	// EQUIVALENT COVERAGE (per explicit follow-up instruction): the
	// AUTHORITATIVE/SPECIFIC-evidence immediate-switch call site (while
	// SUSPENDED, still within B's OWN resume window) must apply the
	// identical "non-matching real switch permanently kills parked A"
	// rule. (The MATCHING/resume half of this call site is already
	// covered by restartInsideWindow_bReloadsConservativelySuspended_
	// returningAStillResumesOriginalSessionId above.)
	@Test
	public void authoritativeImmediateSwitchWhileSuspended_notMatchingParkedA_finalizesAAndCreatesNewC()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = liveEngine.onQualifyingActivity(CRAFTING, t1,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant persistedExpiresAt = liveEngine.getInterruptedCandidateExpiresAt();

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine engine = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, persistedExpiresAt).getEngine();
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());

		// AUTHORITATIVE/SPECIFIC evidence for a non-matching identity (Zulrah,
		// BOSSING) -- an immediate switch, no confirmation needed.
		Instant switchAt = restartAt.plusSeconds(10);
		LifecycleResult switched = engine.onQualifyingActivity(ZULRAH, switchAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals(ZULRAH, switched.getCurrent().getActivityIdentity());
		assertFalse(aSessionId.equals(switched.getCurrent().getSessionId()));
		assertEquals("B finalizes normally at the authoritative event's own timestamp",
			bSessionId, switched.getFinalized().getSessionId());
		assertEquals(switchAt.toString(), switched.getFinalized().getFinalizedAt());

		assertEquals("parked A must be finalized exactly once, via the additionalFinalized slot",
			SessionState.FINALIZED, switched.getAdditionalFinalized().getState());
		assertEquals(aSessionId, switched.getAdditionalFinalized().getSessionId());
		assertEquals("A's finalizedAt must be its ORIGINAL interruption instant (t1), never switchAt",
			t1.toString(), switched.getAdditionalFinalized().getFinalizedAt());
		assertNull("A must be permanently gone -- cannot later resume", engine.getInterruptedCandidate());
	}

	// EQUIVALENT COVERAGE, matching half: an event whose own timestamp is
	// PAST B's own (much longer, 30-minute) resume window, while a
	// matching parked A candidate is still eligible, must still resume A.
	// NOTE ON REACHABILITY: with the current fixed constants
	// (RESUME_WINDOW = 30 minutes, always > INTERRUPTED_RESUME_WINDOW =
	// SUSPEND_TIMEOUT = 5 minutes, and B always starts at-or-after A's own
	// interruption instant T1), B's own resume-window-elapsed boundary is
	// structurally ALWAYS at least 30 minutes later than A's own candidate
	// boundary -- so this exact call site can never see a still-eligible A
	// through ordinary elapsed real time; the top-level
	// expireInterruptedCandidateIfNeeded() housekeeping always resolves A
	// first. This test constructs the combination directly via
	// rehydrate()'s own raw candidate-expiry parameter (already an
	// established, legitimate technique in this file -- see the restart
	// tests above) purely to prove the DISPLACEMENT/RESUME code at this
	// third call site is itself correct, defensively, in case that
	// constant ordering ever changes.
	@Test
	public void resumeWindowElapsedSwitchWhileSuspended_matchingParkedA_resumesA()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();
		String aStartedAt = liveEngine.getCurrentSession().getStartedAt();

		Instant t1 = T0.plusSeconds(60);
		liveEngine.onQualifyingActivity(CRAFTING, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		// Artificially extended candidate expiry -- see this test's own
		// reachability note above.
		Instant extendedExpiresAt = t1.plusSeconds(3600);

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine engine = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, extendedExpiresAt).getEngine();
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());
		String bSessionId = engine.getCurrentSession().getSessionId();
		Instant bResumeExpiry = engine.getCurrentSession().resumeWindowExpiresAtInstant();

		// Strictly past B's OWN resume window, but still within A's
		// (artificially extended) candidate window.
		Instant elapsedReturn = bResumeExpiry.plusSeconds(1);
		LifecycleResult resumed = engine.onQualifyingActivity(WOODCUTTING, elapsedReturn,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals("the ORIGINAL A sessionId must return", aSessionId, resumed.getCurrent().getSessionId());
		assertEquals("the ORIGINAL A startedAt must return", aStartedAt, resumed.getCurrent().getStartedAt());
		assertEquals(SessionState.ACTIVE, resumed.getCurrent().getState());
		assertEquals("B finalizes at its own deterministic resume-window-expiry instant, never elapsedReturn",
			bSessionId, resumed.getFinalized().getSessionId());
		assertEquals(bResumeExpiry.toString(), resumed.getFinalized().getFinalizedAt());
		assertNull("A's candidate slot must be fully consumed by the resume", engine.getInterruptedCandidate());
	}

	// EQUIVALENT COVERAGE, non-matching half of the same call site -- see
	// the reachability note on the matching test just above (identical
	// construction technique and rationale).
	@Test
	public void resumeWindowElapsedSwitchWhileSuspended_notMatchingParkedA_finalizesAAndCreatesNewC()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = liveEngine.onQualifyingActivity(CRAFTING, t1,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant extendedExpiresAt = t1.plusSeconds(3600);

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine engine = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, extendedExpiresAt).getEngine();
		Instant bResumeExpiry = engine.getCurrentSession().resumeWindowExpiresAtInstant();

		Instant elapsedReturn = bResumeExpiry.plusSeconds(1);
		LifecycleResult switched = engine.onQualifyingActivity(ZULRAH, elapsedReturn,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		assertEquals(ZULRAH, switched.getCurrent().getActivityIdentity());
		assertFalse(aSessionId.equals(switched.getCurrent().getSessionId()));
		assertEquals(bSessionId, switched.getFinalized().getSessionId());
		assertEquals(bResumeExpiry.toString(), switched.getFinalized().getFinalizedAt());

		assertEquals("parked A must be finalized exactly once, via the additionalFinalized slot",
			SessionState.FINALIZED, switched.getAdditionalFinalized().getState());
		assertEquals(aSessionId, switched.getAdditionalFinalized().getSessionId());
		assertEquals("A's finalizedAt must be its ORIGINAL interruption instant (t1), never elapsedReturn",
			t1.toString(), switched.getAdditionalFinalized().getFinalizedAt());
		assertNull("A must be permanently gone", engine.getInterruptedCandidate());
	}

	// REQUIRED TEST: a single, first weak observation alone must never
	// resume A and must never mutate the interrupted candidate at all --
	// B remains current (still SUSPENDED). This is the direct converse
	// half of the two tests above, isolated on its own.
	@Test
	public void firstWeakObservationAlone_neverResumesA_neverMutatesInterruptedCandidate_bRemainsCurrent()
	{
		SessionLifecycleEngine liveEngine = new SessionLifecycleEngine();
		String aSessionId = liveEngine.onQualifyingActivity(WOODCUTTING, T0).getCurrent().getSessionId();

		Instant t1 = T0.plusSeconds(60);
		LifecycleResult toB = liveEngine.onQualifyingActivity(CRAFTING, t1,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		String bSessionId = toB.getCurrent().getSessionId();
		Session persistedCurrent = liveEngine.getCurrentSession();
		Session persistedCandidate = liveEngine.getInterruptedCandidate();
		Instant persistedInterruptedAt = liveEngine.getInterruptedCandidateInterruptedAt();
		Instant persistedExpiresAt = liveEngine.getInterruptedCandidateExpiresAt();

		Instant restartAt = t1.plusSeconds(120);
		SessionLifecycleEngine engine = SessionLifecycleEngine.rehydrate(
			persistedCurrent, restartAt, persistedCandidate, persistedInterruptedAt, persistedExpiresAt).getEngine();

		Instant firstReturn = restartAt.plusSeconds(10);
		LifecycleResult armed = engine.onQualifyingActivity(WOODCUTTING, firstReturn,
			Arrays.asList(MetricUpdate.xp("woodcutting", 7)), EvidenceStrength.ORDINARY);

		assertNull(armed.getFinalized());
		assertNull(armed.getAdditionalFinalized());
		assertEquals("B must remain current -- unresumed", bSessionId, armed.getCurrent().getSessionId());
		assertEquals(SessionState.SUSPENDED, armed.getCurrent().getState());
		assertEquals("the parked A candidate must be completely untouched by a single arming observation",
			aSessionId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(WOODCUTTING, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(persistedInterruptedAt, engine.getInterruptedCandidateInterruptedAt());
		assertEquals(persistedExpiresAt, engine.getInterruptedCandidateExpiresAt());
	}
}
