package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project (see LootCollectorTest,
 * BankSnapshotBaselineTest, ServerNpcLootCollectorTest). Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Entirely against the real, pure, production SessionLifecycleEngine —
 * no test-duplicated decision logic, no mocking (this project has no
 * Mockito dependency), every timestamp supplied explicitly, matching
 * BankSnapshotBaseline's own pure-decision-rule testing style.
 */
public class SessionLifecycleEngineTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");
	private static final ActivityIdentity ZULRAH =
		new ActivityIdentity(ActivityType.BOSSING, "zulrah", "Zulrah");
	private static final ActivityIdentity WOODCUTTING =
		new ActivityIdentity(ActivityType.SKILLING, "woodcutting", "Woodcutting");
	private static final ActivityIdentity MINING =
		new ActivityIdentity(ActivityType.SKILLING, "mining", "Mining");
	private static final ActivityIdentity AGILITY =
		new ActivityIdentity(ActivityType.SKILLING, "agility", "Agility");
	private static final ActivityIdentity CRAFTING =
		new ActivityIdentity(ActivityType.SKILLING, "crafting", "Crafting");
	private static final ActivityIdentity COOKING =
		new ActivityIdentity(ActivityType.SKILLING, "cooking", "Cooking");

	// --- 1/2/3: creation ---

	@Test
	public void firstQualifyingActivity_createsActiveSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		LifecycleResult result = engine.onQualifyingActivity(GARGOYLES, T0);

		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertNull(result.getFinalized());
		assertEquals(T0.toString(), result.getCurrent().getStartedAt());
		assertEquals(T0.toString(), result.getCurrent().getLastActiveAt());
		assertEquals(0L, result.getCurrent().getAccumulatedActiveDurationMillis());
	}

	@Test
	public void session_getsUuidSessionId()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		Session session = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent();

		assertNotEquals(null, session.getSessionId());
		// Will throw IllegalArgumentException if not a valid UUID string.
		java.util.UUID.fromString(session.getSessionId());
	}

	@Test
	public void identity_isPreserved()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		Session session = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent();

		assertEquals(GARGOYLES, session.getActivityIdentity());
		assertEquals("Gargoyles", session.getActivityIdentity().getDisplayName());
	}

	// --- 4/5: same-identity heartbeats stay ACTIVE and accumulate ---

	@Test
	public void sameIdentityHeartbeat_staysActive()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		LifecycleResult second = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(5));

		assertEquals(SessionState.ACTIVE, second.getCurrent().getState());
		assertNull(second.getFinalized());
	}

	@Test
	public void activeDuration_accumulatesBetweenHeartbeats()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(5));
		Session session = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(11)).getCurrent();

		// 10:00:00 -> 10:00:05 -> 10:00:11 == 11 seconds.
		assertEquals(11_000L, session.getAccumulatedActiveDurationMillis());
	}

	// --- 6/7/8: five-minute suspension ---

	@Test
	public void trailingInactivity_notAddedToActiveDuration()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session afterOneHeartbeat = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(5)).getCurrent();
		long durationBeforeIdle = afterOneHeartbeat.getAccumulatedActiveDurationMillis();

		// Five minutes pass with no further qualifying activity.
		LifecycleResult advanced = engine.advanceTime(T0.plusSeconds(5).plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));

		assertEquals(SessionState.SUSPENDED, advanced.getCurrent().getState());
		assertEquals("idle time must never be added to active duration",
			durationBeforeIdle, advanced.getCurrent().getAccumulatedActiveDurationMillis());
	}

	@Test
	public void exact5MinuteBoundary_suspendsCorrectly()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant threshold = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);

		LifecycleResult result = engine.advanceTime(threshold);

		assertEquals(SessionState.SUSPENDED, result.getCurrent().getState());
		assertEquals("suspendedAt must be the exact threshold, not an arbitrary later check time",
			threshold.toString(), result.getCurrent().getSuspendedAt());
		assertEquals(threshold.plus(SessionLifecycleEngine.RESUME_WINDOW).toString(),
			result.getCurrent().getResumeWindowExpiresAt());
	}

	@Test
	public void lateSchedulerCheck_usesDeterministicThresholdTimestamp()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant threshold = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);

		// Scheduler wakes up 10 minutes late.
		LifecycleResult result = engine.advanceTime(threshold.plus(Duration.ofMinutes(10)));

		assertEquals("suspendedAt must still be the deterministic threshold, never the late check time",
			threshold.toString(), result.getCurrent().getSuspendedAt());
	}

	// --- 9/10/11/12: resume within window ---

	@Test
	public void suspended_sameIdentityResumesWithinWindow()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		Instant resumeAt = suspendedAt.plus(Duration.ofMinutes(10));
		LifecycleResult result = engine.onQualifyingActivity(GARGOYLES, resumeAt);

		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertNull(result.getFinalized());
		assertNull(result.getCurrent().getSuspendedAt());
		assertNull(result.getCurrent().getResumeWindowExpiresAt());
		assertEquals(resumeAt.toString(), result.getCurrent().getLastActiveAt());
	}

	@Test
	public void resume_preservesSessionId()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		Session resumed = engine.onQualifyingActivity(GARGOYLES, suspendedAt.plusSeconds(1)).getCurrent();

		assertEquals(originalId, resumed.getSessionId());
	}

	@Test
	public void resume_preservesAccumulatedActiveDuration()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		long durationBeforeSuspend = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(11))
			.getCurrent().getAccumulatedActiveDurationMillis();
		Instant suspendedAt = T0.plusSeconds(11).plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		Session resumed = engine.onQualifyingActivity(GARGOYLES, suspendedAt.plusSeconds(1)).getCurrent();

		assertEquals(durationBeforeSuspend, resumed.getAccumulatedActiveDurationMillis());
	}

	@Test
	public void suspendedGap_addsZeroActiveDuration()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		long durationAtSuspend = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(11))
			.getCurrent().getAccumulatedActiveDurationMillis();
		Instant suspendedAt = T0.plusSeconds(11).plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		// Resume after a further 20-minute suspended gap.
		Session resumed = engine.onQualifyingActivity(GARGOYLES, suspendedAt.plus(Duration.ofMinutes(20))).getCurrent();

		assertEquals("the entire suspended gap must contribute zero active duration",
			durationAtSuspend, resumed.getAccumulatedActiveDurationMillis());
	}

	// --- 13/14/15: different activity finalizes and starts new ---

	@Test
	public void differentIdentityWhileActive_finalizesOldStartsNew()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String oldId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();

		Instant switchAt = T0.plusSeconds(30);
		LifecycleResult result = engine.onQualifyingActivity(ZULRAH, switchAt);

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): a genuine
		// switch now PARKS the outgoing session as the ONE resumable
		// interrupted candidate instead of finalizing it immediately --
		// see SessionLifecycleEngine's own interruptedCandidate field
		// javadoc. getFinalized() is null at this exact transition; the
		// parked candidate carries what used to be asserted here.
		assertNull(result.getFinalized());
		assertEquals(oldId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(GARGOYLES, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(ZULRAH, result.getCurrent().getActivityIdentity());
		assertNotEquals(oldId, result.getCurrent().getSessionId());

		// Letting the interrupted-resume window elapse without returning
		// proves the parked candidate eventually finalizes exactly as
		// this test originally asserted synchronously.
		LifecycleResult expiry = engine.advanceTime(switchAt.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals(SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(oldId, expiry.getAdditionalFinalized().getSessionId());
		assertEquals(GARGOYLES, expiry.getAdditionalFinalized().getActivityIdentity());
	}

	@Test
	public void differentIdentityWhileSuspended_finalizesOldStartsNew()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String oldId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		LifecycleResult result = engine.onQualifyingActivity(ZULRAH, suspendedAt.plusSeconds(5));

		assertEquals(SessionState.FINALIZED, result.getFinalized().getState());
		assertEquals(oldId, result.getFinalized().getSessionId());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(ZULRAH, result.getCurrent().getActivityIdentity());
	}

	@Test
	public void oldSession_doesNotReceiveGapTimeBeforeNewActivity()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		long durationAtLastGargoylesHeartbeat = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(11))
			.getCurrent().getAccumulatedActiveDurationMillis();

		// A full two minutes pass before the switch to a different activity.
		engine.onQualifyingActivity(ZULRAH, T0.plusSeconds(11).plus(Duration.ofMinutes(2)));

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): the outgoing
		// session is now PARKED as the ONE interrupted candidate instead
		// of finalized immediately -- its own accumulatedActiveDurationMillis
		// is untouched by the switch either way (park never calls
		// accumulate() any more than finalize ever did), so this
		// assertion holds identically against the parked candidate.
		Session parkedOld = engine.getInterruptedCandidate();

		assertEquals("the old session's duration must stop at its own last evidence, never extended into the gap",
			durationAtLastGargoylesHeartbeat, parkedOld.getAccumulatedActiveDurationMillis());
	}

	// --- 16: exact resume-window boundary (event takes precedence) ---

	@Test
	public void exactResumeWindowBoundary_stillResumes()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant boundary = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);

		LifecycleResult result = engine.onQualifyingActivity(GARGOYLES, boundary);

		assertEquals("A genuine same-identity event AT the boundary still resumes",
			SessionState.ACTIVE, result.getCurrent().getState());
		assertNull(result.getFinalized());
	}

	@Test
	public void differentActivityAtExactResumeBoundary_finalizesAndStartsNew()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant boundary = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);

		LifecycleResult result = engine.onQualifyingActivity(ZULRAH, boundary);

		assertEquals(SessionState.FINALIZED, result.getFinalized().getState());
		assertEquals(ZULRAH, result.getCurrent().getActivityIdentity());
	}

	// --- 17/18: expiration finalizes ---

	@Test
	public void expiration_finalizesSuspendedSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);

		LifecycleResult result = engine.advanceTime(expiry);

		assertEquals(SessionState.FINALIZED, result.getFinalized().getState());
		assertNull(result.getCurrent());
		assertEquals(expiry.toString(), result.getFinalized().getFinalizedAt());
	}

	@Test
	public void lateSchedulerInvocation_usesDeterministicExpiryTimestamp()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);

		// Scheduler wakes up an hour after the resume window actually expired.
		LifecycleResult result = engine.advanceTime(expiry.plus(Duration.ofHours(1)));

		assertEquals("finalizedAt must be the deterministic expiry instant, never the late check time",
			expiry.toString(), result.getFinalized().getFinalizedAt());
	}

	@Test
	public void hugeGapSingleHeartbeat_finalizesViaExpiryAndStartsNew()
	{
		// A single qualifying event arrives so long after lastActiveAt
		// that both the suspend threshold AND the resume window have
		// already elapsed as of that event's own timestamp — no separate
		// advanceTime() call in between.
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String oldId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);

		LifecycleResult result = engine.onQualifyingActivity(GARGOYLES, expiry.plusSeconds(1));

		assertEquals(SessionState.FINALIZED, result.getFinalized().getState());
		assertEquals(oldId, result.getFinalized().getSessionId());
		assertEquals(expiry.toString(), result.getFinalized().getFinalizedAt());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertNotEquals("even the SAME identity gets a brand new session once the old one already expired",
			oldId, result.getCurrent().getSessionId());
	}

	// --- 19/20: timestamp safety ---

	@Test
	public void duplicateTimestamp_doesNotDoubleCount()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(5));
		long durationAfterFirstPair = engine.getCurrentSession().getAccumulatedActiveDurationMillis();

		// The exact same timestamp arrives again (e.g. a duplicate event).
		Session afterDuplicate = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(5)).getCurrent();

		assertEquals(durationAfterFirstPair, afterDuplicate.getAccumulatedActiveDurationMillis());
	}

	@Test
	public void outOfOrderTimestamp_cannotCreateNegativeDuration()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(30));
		Session before = engine.getCurrentSession();
		long durationBefore = before.getAccumulatedActiveDurationMillis();
		String lastActiveAtBefore = before.getLastActiveAt();

		// An event timestamped BEFORE the session's own lastActiveAt.
		LifecycleResult result = engine.onQualifyingActivity(GARGOYLES, T0);

		assertSame("an out-of-order event must be rejected without mutating the session",
			before, result.getCurrent());
		assertEquals(durationBefore, result.getCurrent().getAccumulatedActiveDurationMillis());
		assertEquals(lastActiveAtBefore, result.getCurrent().getLastActiveAt());
		assertNull(result.getFinalized());
		assertTrue(result.getCurrent().getAccumulatedActiveDurationMillis() >= 0);
	}

	// --- 30: illegal transitions cannot be forced through the public API ---

	@Test
	public void stateEnum_illegalTransition_notReachableViaPublicApi() throws Exception
	{
		// Session's setters are package-private (@Setter(AccessLevel.PACKAGE))
		// specifically so no code outside com.osrstelemetry.plugin.session
		// can force an arbitrary state transition directly — the only
		// public way to change a Session's state is through
		// SessionLifecycleEngine's own methods, which this whole test
		// class already exercises exhaustively.
		Method setState = Session.class.getDeclaredMethod("setState", SessionState.class);
		assertFalse("Session.setState must not be public", Modifier.isPublic(setState.getModifiers()));

		Method setAccumulated = Session.class.getDeclaredMethod("setAccumulatedActiveDurationMillis", long.class);
		assertFalse("Session.setAccumulatedActiveDurationMillis must not be public",
			Modifier.isPublic(setAccumulated.getModifiers()));
	}

	// --- ActivityIdentity equality contract, load-bearing for resume/switch decisions ---

	@Test
	public void activityIdentity_equality_ignoresDisplayNameUsesTypeAndKey()
	{
		ActivityIdentity a = new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");
		ActivityIdentity sameIdentityDifferentDisplay =
			new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles (Catacombs of Kourend)");
		ActivityIdentity differentKey = new ActivityIdentity(ActivityType.SLAYER, "gargoyles:slayer_tower", "Gargoyles");
		ActivityIdentity differentType = new ActivityIdentity(ActivityType.COMBAT, "gargoyles:catacombs", "Gargoyles");

		assertEquals("displayName must never affect equality", a, sameIdentityDifferentDisplay);
		assertEquals(a.hashCode(), sameIdentityDifferentDisplay.hashCode());
		assertNotEquals(a, differentKey);
		assertNotEquals(a, differentType);
	}

	// =====================================================================
	// SUSPENDED-BRANCH WEAK-EVIDENCE CONFIRMATION GATE. Live evidence:
	// a single incidental AGILITY +3
	// XP_CHANGE, observed while SUSPENDED BOSSING/Grotesque Guardians was
	// still resumable, immediately finalized that session before the
	// player's genuine return (a same-boss BOSS_KILL) ever arrived. The
	// gate is scoped to exactly one case: current SUSPENDED identity is
	// combat-branch (COMBAT/SLAYER/BOSSING) and the new identity is NOT
	// (SKILLING) -- see SessionLifecycleEngine.isWeakEvidenceAgainstEstablishedSession().
	// =====================================================================

	// Live report case 1 (must preserve): SUSPENDED BOSSING + one
	// incidental non-combat XP event + the SAME boss again later -> the
	// ORIGINAL session resumes, completely unaffected by the earlier weak
	// blip.
	@Test
	public void weakEvidenceWhileSuspended_doesNotImmediatelyFinalize_sameBossLaterStillResumesSameSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(ZULRAH, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		// One incidental Woodcutting XP event (weak, non-combat-branch
		// evidence) while SUSPENDED.
		LifecycleResult afterWeakEvidence = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30));

		assertNull("a single isolated weak signal must never finalize an established, still-resumable "
			+ "combat-branch session", afterWeakEvidence.getFinalized());
		assertEquals("the suspended session itself must be left completely untouched by the weak signal",
			SessionState.SUSPENDED, afterWeakEvidence.getCurrent().getState());
		assertEquals(ZULRAH, afterWeakEvidence.getCurrent().getActivityIdentity());
		assertEquals(originalId, afterWeakEvidence.getCurrent().getSessionId());

		// The player actually returns to the same boss.
		LifecycleResult afterRealReturn = engine.onQualifyingActivity(ZULRAH, suspendedAt.plusSeconds(90));

		assertNull(afterRealReturn.getFinalized());
		assertEquals("the SAME original boss session must resume, never a fresh one",
			originalId, afterRealReturn.getCurrent().getSessionId());
		assertEquals(SessionState.ACTIVE, afterRealReturn.getCurrent().getState());
		assertEquals(ZULRAH, afterRealReturn.getCurrent().getActivityIdentity());
	}

	// Live report case 2 (must preserve -- the protection must not make a
	// suspended session impossible to leave): SUSPENDED BOSSING + GENUINE,
	// REPEATED Woodcutting evidence -> the boss session eventually
	// finalizes and a real Woodcutting session starts.
	@Test
	public void repeatedCoherentWeakEvidence_confirmsGenuineSwitch_finalizesOldStartsNew()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(ZULRAH, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		Instant firstWoodcutting = suspendedAt.plusSeconds(30);
		LifecycleResult armed = engine.onQualifyingActivity(WOODCUTTING, firstWoodcutting);
		assertNull("the first weak signal only arms a candidate -- it must not finalize anything yet",
			armed.getFinalized());

		Instant secondWoodcutting = suspendedAt.plusSeconds(60);
		LifecycleResult confirmed = engine.onQualifyingActivity(WOODCUTTING, secondWoodcutting);

		assertEquals("a SECOND, coherent observation of the SAME candidate identity must confirm the "
			+ "switch and finalize the old boss session",
			SessionState.FINALIZED, confirmed.getFinalized().getState());
		assertEquals(originalId, confirmed.getFinalized().getSessionId());
		assertEquals(ZULRAH, confirmed.getFinalized().getActivityIdentity());
		assertEquals("the old session must finalize at the candidate's OWN first-seen instant, never the "
			+ "later confirming event's timestamp",
			firstWoodcutting.toString(), confirmed.getFinalized().getFinalizedAt());

		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());
		assertEquals(WOODCUTTING, confirmed.getCurrent().getActivityIdentity());
		assertNotEquals(originalId, confirmed.getCurrent().getSessionId());
		assertEquals("the new session must start at the candidate's OWN first-seen instant, never the "
			+ "confirming event's later timestamp -- no activity time is fabricated for either session",
			firstWoodcutting.toString(), confirmed.getCurrent().getStartedAt());
	}

	// A different candidate identity arriving before confirmation replaces
	// (never accumulates with) the pending one -- "coherent" means the
	// same candidate repeating, not any two arbitrary weak signals.
	@Test
	public void differentWeakCandidateBeforeConfirmation_replacesPendingCandidate_neitherConfirmedYet()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30));
		LifecycleResult afterDifferentCandidate = engine.onQualifyingActivity(MINING, suspendedAt.plusSeconds(45));

		assertNull("a different weak candidate must not itself confirm anything -- it replaces the "
			+ "pending one instead of accumulating toward confirmation",
			afterDifferentCandidate.getFinalized());
		assertEquals(SessionState.SUSPENDED, afterDifferentCandidate.getCurrent().getState());
		assertEquals(ZULRAH, afterDifferentCandidate.getCurrent().getActivityIdentity());

		// Mining must now need its OWN second observation to confirm --
		// repeating Woodcutting again does NOT confirm Mining, and does
		// not confirm Woodcutting either (Woodcutting is no longer the
		// pending candidate, Mining replaced it).
		LifecycleResult afterWoodcuttingAgain = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(60));
		assertNull("Woodcutting was replaced as the pending candidate by Mining, so repeating it now "
			+ "only re-arms it -- it must not retroactively confirm",
			afterWoodcuttingAgain.getFinalized());
		assertEquals(SessionState.SUSPENDED, afterWoodcuttingAgain.getCurrent().getState());
	}

	// Live report case (must preserve): an AUTHORITATIVE different
	// combat-branch identity (a different boss) while SUSPENDED still
	// switches IMMEDIATELY, with no confirmation delay at all -- unaffected
	// by the SUSPENDED-branch weak-evidence gate, which is scoped to non-combat-branch evidence
	// only. (Already covered by differentIdentityWhileSuspended_finalizesOldStartsNew
	// above; this test states the "no confirmation needed" property
	// explicitly for that gate's own regression coverage.)
	@Test
	public void authoritativeDifferentCombatBranchEvidenceWhileSuspended_switchesImmediately_noConfirmationNeeded()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		LifecycleResult result = engine.onQualifyingActivity(ZULRAH, suspendedAt.plusSeconds(5));

		assertEquals("a single authoritative combat-branch signal (a real BOSS_KILL/SLAYER_TASK_PROGRESS) "
			+ "must switch immediately -- never gated behind confirmation like weak SKILLING evidence",
			SessionState.FINALIZED, result.getFinalized().getState());
		assertEquals(ZULRAH, result.getCurrent().getActivityIdentity());
	}

	// A pending, never-confirmed candidate must not leak into whatever
	// comes after the ORIGINAL session's own resume window genuinely
	// expires.
	@Test
	public void pendingCandidate_discardedWhenOriginalResumeWindowExpires()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		// Arm a Woodcutting candidate, but never confirm it.
		engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30));

		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		LifecycleResult timedOut = engine.advanceTime(expiry);

		assertEquals(SessionState.FINALIZED, timedOut.getFinalized().getState());
		assertEquals(ZULRAH, timedOut.getFinalized().getActivityIdentity());
		assertNull("no session should be left current after a plain timeout with no new activity",
			timedOut.getCurrent());

		// A fresh Mining event afterward must start a completely new
		// session, not be treated as confirming the old, now-irrelevant
		// Woodcutting candidate.
		LifecycleResult afterFreshActivity = engine.onQualifyingActivity(MINING, expiry.plusSeconds(10));
		assertNull(afterFreshActivity.getFinalized());
		assertEquals(MINING, afterFreshActivity.getCurrent().getActivityIdentity());
		assertEquals(SessionState.ACTIVE, afterFreshActivity.getCurrent().getState());
	}
	// =====================================================================
	// "Unconfirmed candidate metrics duplicated across
	// sessions." Live evidence: SUSPENDED BOSSING/GG -> genuine switch to
	// Woodcutting, correctly gated behind the candidate-confirmation
	// mechanism above -- but the finalized old GG session ALSO ended up
	// with the confirmed session's own WOODCUTTING XP (525 in both). Root
	// cause: a candidate batch's own MetricUpdates were applied to
	// getCurrent() unconditionally, even while that call only armed/
	// replaced a pending candidate -- i.e. while getCurrent() was still
	// the OLD established session, not any kind of holding area. Fix:
	// pendingCandidateMetrics, buffered next to the existing
	// pendingCandidateIdentity/pendingCandidateFirstSeenAt pair, plus
	// LifecycleResult's new metricsForCurrent/contextualMetricsTarget/
	// contextualMetrics fields that tell the caller exactly which Session
	// each metric belongs to. Test letters A-J below match the live bug
	// report's own lettered TEST REQUIREMENTS list.
	// =====================================================================

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

	// A. First weak Woodcutting signal -> its metric stays provisional,
	// not yet applied anywhere (metricsForCurrent empty, no contextual
	// target either -- there was no prior candidate to abandon).
	@Test
	public void a_firstWeakSignal_metricRemainsProvisional_notAppliedToOldSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		LifecycleResult armed = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("woodcutting", 300)));

		assertTrue("a single arming batch's metric must not be applied anywhere yet",
			armed.getMetricsForCurrent().isEmpty());
		assertNull("no prior candidate existed to abandon, so there is nothing contextual to commit",
			armed.getContextualMetricsTarget());
		assertEquals("the old session's own aggregates must be untouched by the mere arming of a candidate",
			0L, totalXp(armed.getCurrent(), "woodcutting"));
	}

	// B. Second coherent Woodcutting signal confirms -> both batches' XP
	// (the live report's "WOODCUTTING=525") move to the new Woodcutting
	// session exactly once; the old GG session receives none of it.
	@Test
	public void b_confirmingSecondSignal_movesBothBatchesMetricsToNewSessionOnly_oldSessionGetsNone()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String oldSessionId = engine.onQualifyingActivity(ZULRAH, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		LifecycleResult armed = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("woodcutting", 300)));
		apply(armed.getCurrent(), armed.getMetricsForCurrent());

		LifecycleResult confirmed = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(60),
			Arrays.asList(MetricUpdate.xp("woodcutting", 225)));

		assertEquals(SessionState.FINALIZED, confirmed.getFinalized().getState());
		assertEquals(oldSessionId, confirmed.getFinalized().getSessionId());
		assertNull("Case 1: the finalized old GG session must receive NO candidate Woodcutting XP",
			confirmed.getContextualMetricsTarget());
		assertEquals("both the arming batch's and the confirming batch's XP must move to the new "
			+ "session, combined, exactly once -- the live-reported 525 total",
			2, confirmed.getMetricsForCurrent().size());

		apply(confirmed.getCurrent(), confirmed.getMetricsForCurrent());
		assertEquals(525L, totalXp(confirmed.getCurrent(), "woodcutting"));
		assertEquals("the old, now-finalized session must show zero Woodcutting XP",
			0L, totalXp(confirmed.getFinalized(), "woodcutting"));
	}

	// C. An incidental non-combat signal (Agility XP), then the SAME boss
	// resumes -> the incidental XP is retained as CONTEXTUAL XP on that
	// resumed session, applied exactly once, no separate Agility session.
	@Test
	public void c_incidentalSignalThenSameBossResumes_contextualXpAppliedOnceToResumedSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(ZULRAH, T0).getCurrent().getSessionId();
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		LifecycleResult armed = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("agility", 3)));
		apply(armed.getCurrent(), armed.getMetricsForCurrent());

		LifecycleResult resumed = engine.onQualifyingActivity(ZULRAH, suspendedAt.plusSeconds(90),
			Collections.<MetricUpdate>emptyList());

		assertNull(resumed.getFinalized());
		assertEquals("the SAME original session must resume, never a new Agility session",
			originalId, resumed.getCurrent().getSessionId());
		assertEquals(ZULRAH, resumed.getCurrent().getActivityIdentity());
		assertSame("the abandoned candidate's metrics must be committed to the resumed session itself",
			resumed.getCurrent(), resumed.getContextualMetricsTarget());
		assertEquals(1, resumed.getContextualMetrics().size());

		apply(resumed.getContextualMetricsTarget(), resumed.getContextualMetrics());
		assertEquals("the incidental Agility XP must appear exactly once",
			3L, totalXp(resumed.getCurrent(), "agility"));
	}

	// D. Candidate A replaced by candidate B -> A's buffered metrics are
	// committed once to the still-established session; B starts with a
	// clean provisional buffer that never mixes with A's.
	@Test
	public void d_candidateReplacedByDifferentCandidate_firstCommittedOnce_secondStartsClean()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		LifecycleResult armedA = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("woodcutting", 10)));
		assertTrue(armedA.getMetricsForCurrent().isEmpty());

		LifecycleResult replaced = engine.onQualifyingActivity(MINING, suspendedAt.plusSeconds(45),
			Arrays.asList(MetricUpdate.xp("mining", 5)));

		assertSame("candidate A's buffered metrics must be committed once to the established session",
			replaced.getCurrent(), replaced.getContextualMetricsTarget());
		assertEquals(1, replaced.getContextualMetrics().size());
		apply(replaced.getContextualMetricsTarget(), replaced.getContextualMetrics());
		assertEquals(10L, totalXp(replaced.getCurrent(), "woodcutting"));
		assertTrue("candidate B's own batch is only buffered, not applied yet",
			replaced.getMetricsForCurrent().isEmpty());

		LifecycleResult confirmedB = engine.onQualifyingActivity(MINING, suspendedAt.plusSeconds(60),
			Arrays.asList(MetricUpdate.xp("mining", 2)));

		assertNull("no further abandonment happens at B's own confirmation",
			confirmedB.getContextualMetricsTarget());
		apply(confirmedB.getCurrent(), confirmedB.getMetricsForCurrent());
		assertEquals("B's confirmed session must contain only B's own metrics (5 + 2), never A's Woodcutting XP",
			7L, totalXp(confirmedB.getCurrent(), "mining"));
		assertEquals(0L, totalXp(confirmedB.getCurrent(), "woodcutting"));
	}

	// E. Resume window expires with an unconfirmed candidate still
	// pending -> its buffered metrics are retained, committed exactly
	// once to the session that finalizes as a result of the timeout; no
	// fabricated candidate session is ever created.
	@Test
	public void e_resumeWindowExpiresWithPendingCandidate_metricsRetainedOnceOnFinalizedSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("woodcutting", 50)));

		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		LifecycleResult timedOut = engine.advanceTime(expiry);

		assertEquals(SessionState.FINALIZED, timedOut.getFinalized().getState());
		assertEquals(ZULRAH, timedOut.getFinalized().getActivityIdentity());
		assertNull(timedOut.getCurrent());
		assertSame("the abandoned candidate's metrics must land on the finalized session",
			timedOut.getFinalized(), timedOut.getContextualMetricsTarget());
		apply(timedOut.getContextualMetricsTarget(), timedOut.getContextualMetrics());
		assertEquals(50L, totalXp(timedOut.getFinalized(), "woodcutting"));
	}

	// F. A pending candidate, then an AUTHORITATIVE different activity
	// (a different boss) arrives -> the abandoned candidate's metrics are
	// committed once to the OLD (finalizing) session and must not
	// contaminate the new authoritative session.
	@Test
	public void f_authoritativeSwitchWithPendingCandidate_abandonedMetricsDoNotContaminateNewActivity()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("woodcutting", 50)));

		LifecycleResult authoritative = engine.onQualifyingActivity(GARGOYLES, suspendedAt.plusSeconds(45),
			Arrays.asList(MetricUpdate.xp("slayer", 5)));

		assertEquals(SessionState.FINALIZED, authoritative.getFinalized().getState());
		assertEquals(ZULRAH, authoritative.getFinalized().getActivityIdentity());
		assertSame("the abandoned Woodcutting candidate's metrics belong to the OLD finalizing session",
			authoritative.getFinalized(), authoritative.getContextualMetricsTarget());
		apply(authoritative.getContextualMetricsTarget(), authoritative.getContextualMetrics());
		assertEquals(50L, totalXp(authoritative.getFinalized(), "woodcutting"));

		apply(authoritative.getCurrent(), authoritative.getMetricsForCurrent());
		assertEquals("the new authoritative session must contain only its own metric",
			5L, totalXp(authoritative.getCurrent(), "slayer"));
		assertEquals("the new authoritative session must never be contaminated by the abandoned candidate",
			0L, totalXp(authoritative.getCurrent(), "woodcutting"));
	}

	// G. Multiple MetricUpdates within one candidate batch all preserve
	// exact-once ownership through to confirmation.
	@Test
	public void g_multipleMetricUpdatesInOneCandidateBatch_allPreserveExactOnceOwnership()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);

		engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("woodcutting", 10), MetricUpdate.xp("woodcutting", 5)));

		LifecycleResult confirmed = engine.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(60),
			Arrays.asList(MetricUpdate.xp("woodcutting", 20)));

		assertEquals(3, confirmed.getMetricsForCurrent().size());
		apply(confirmed.getCurrent(), confirmed.getMetricsForCurrent());
		assertEquals(35L, totalXp(confirmed.getCurrent(), "woodcutting"));
	}

	// H. Existing behavior unchanged: ordinary Woodcutting activity with
	// no current session starts immediately, its metrics applied directly
	// -- no candidate machinery involved at all.
	@Test
	public void h_noCurrentSession_ordinaryActivityStartsImmediately_metricsAppliedDirectly()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		LifecycleResult result = engine.onQualifyingActivity(WOODCUTTING, T0,
			Arrays.asList(MetricUpdate.xp("woodcutting", 15)));

		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertNull(result.getFinalized());
		assertNull(result.getContextualMetricsTarget());
		assertEquals(1, result.getMetricsForCurrent().size());
		apply(result.getCurrent(), result.getMetricsForCurrent());
		assertEquals(15L, totalXp(result.getCurrent(), "woodcutting"));
	}

	// I. Delayed Slayer XP behavior unchanged: metric-only against an
	// existing SAME-identity combat session -- no candidate is ever armed
	// or touched by a same-identity heartbeat's own metrics.
	@Test
	public void i_sameIdentityHeartbeatMetrics_appliedDirectly_noCandidateInvolved()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);

		LifecycleResult result = engine.onQualifyingActivity(GARGOYLES, T0.plusSeconds(5),
			Arrays.asList(MetricUpdate.xp("slayer", 12)));

		assertNull(result.getContextualMetricsTarget());
		assertEquals(1, result.getMetricsForCurrent().size());
		apply(result.getCurrent(), result.getMetricsForCurrent());
		assertEquals(12L, totalXp(result.getCurrent(), "slayer"));
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(GARGOYLES, result.getCurrent().getActivityIdentity());
	}

	// J. Committing abandoned-candidate metrics contextually must never
	// inflate active duration or move lastActiveAt beyond what the
	// resume itself already does on its own merits.
	@Test
	public void j_contextualMetricCommit_doesNotInflateActiveDurationOrLastActiveAt()
	{
		SessionLifecycleEngine withCandidate = new SessionLifecycleEngine();
		withCandidate.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		withCandidate.advanceTime(suspendedAt);
		withCandidate.onQualifyingActivity(WOODCUTTING, suspendedAt.plusSeconds(30),
			Arrays.asList(MetricUpdate.xp("agility", 3)));
		LifecycleResult resumedWithCandidate = withCandidate.onQualifyingActivity(ZULRAH, suspendedAt.plusSeconds(90),
			Collections.<MetricUpdate>emptyList());

		SessionLifecycleEngine withoutCandidate = new SessionLifecycleEngine();
		withoutCandidate.onQualifyingActivity(ZULRAH, T0);
		withoutCandidate.advanceTime(suspendedAt);
		LifecycleResult resumedWithoutCandidate = withoutCandidate.onQualifyingActivity(ZULRAH, suspendedAt.plusSeconds(90),
			Collections.<MetricUpdate>emptyList());

		assertEquals("committing an abandoned candidate's metrics contextually must not change lastActiveAt "
			+ "beyond the resume's own timestamp",
			resumedWithoutCandidate.getCurrent().getLastActiveAt(), resumedWithCandidate.getCurrent().getLastActiveAt());
		assertEquals("committing an abandoned candidate's metrics contextually must not inflate active duration",
			resumedWithoutCandidate.getCurrent().getAccumulatedActiveDurationMillis(),
			resumedWithCandidate.getCurrent().getAccumulatedActiveDurationMillis());
	}

	// =====================================================================
	// EVIDENCE-STRENGTH-AWARE WEAK-EVIDENCE GATE, GENERALIZED TO ACTIVE.
	// Live evidence: a single
	// incidental AGILITY +6 XP_CHANGE, observed 34-38 seconds into a
	// genuinely ACTIVE SLAYER/Gargoyles session (well within the 5-minute
	// suspend timeout), immediately replaced it with a brand-new
	// SKILLING/Agility session -- the original "different identity
	// while ACTIVE -> immediate finalize+start" rule treated every
	// differing identity as equally authoritative, exactly the same
	// over-eager assumption the SUSPENDED-branch gate already corrected
	// for that branch. Generalized here: only EvidenceStrength.ORDINARY evidence
	// against a combat-branch ACTIVE session is gated (see
	// SessionLifecycleEngine.isWeakEvidenceAgainstEstablishedSession()),
	// and a CONFIRMED switch is anchored at T2 (the confirming
	// observation's own timestamp), NEVER T1 (the candidate's first-seen
	// instant) -- see the design correction described in
	// SessionLifecycleEngine's own class javadoc. Test names below
	// intentionally echo the SUSPENDED-branch gate's test names above
	// (same shape, ACTIVE instead of SUSPENDED, T2 instead of T1).
	// =====================================================================

	// Case A: the exact live bug reproduction. One incidental ORDINARY
	// Agility observation must NOT finalize the still-genuinely-ACTIVE
	// Gargoyles session; a SECOND, coherent Agility observation confirms
	// the switch, anchored at T2 -- never backdated to T1.
	@Test
	public void weakEvidenceWhileActive_singleIncidentalObservation_doesNotFinalize_confirmationAnchorsAtT2NotT1()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();

		// The exact live sequence: 34 seconds into an ACTIVE session, one
		// incidental Agility XP_CHANGE arrives -- ORDINARY strength,
		// exactly what ActivitySignalClassifier.classifyNonCombatXp() now
		// tags ordinary skill XP with.
		Instant firstAgility = T0.plusSeconds(34);
		LifecycleResult afterFirstWeak = engine.onQualifyingActivity(AGILITY, firstAgility,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertNull("a single incidental ORDINARY-strength observation must never finalize a genuinely "
			+ "ACTIVE combat-branch session -- this is the exact live bug",
			afterFirstWeak.getFinalized());
		assertEquals("the established session must remain completely untouched and ACTIVE through the "
			+ "first weak observation", SessionState.ACTIVE, afterFirstWeak.getCurrent().getState());
		assertEquals(GARGOYLES, afterFirstWeak.getCurrent().getActivityIdentity());
		assertEquals(originalId, afterFirstWeak.getCurrent().getSessionId());

		// A SECOND, coherent Agility observation confirms the switch --
		// but only once EVIDENCE-WEIGHTED HYSTERESIS's own decay has had
		// time to bring ACTIVE's required-confirmation count down from
		// its peak (3, one MORE than SUSPENDED ever requires -- see
		// SessionLifecycleEngine's own class javadoc) to its floor (2,
		// reached ACTIVE_INERTIA_DECAY_PERIOD -- one fifth of
		// SUSPEND_TIMEOUT, i.e. 60s -- after the established session's
		// own last reinforcing evidence, T0). 69s total puts this
		// comfortably past that point.
		Instant secondAgility = T0.plusSeconds(69);
		LifecycleResult confirmed = engine.onQualifyingActivity(AGILITY, secondAgility,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): the confirmed
		// switch now PARKS Gargoyles as the ONE resumable interrupted
		// candidate instead of finalizing it immediately at T2 -- see
		// SessionLifecycleEngine's own interruptedCandidate field
		// javadoc. This is deliberate, per the interrupted-resume
		// feature's own design: the DESIGN CORRECTION this test protects
		// (T2 anchoring, never T1) still holds -- it just now shows up as
		// the parked candidate's own interruptedAt/eventual finalizedAt,
		// verified below by letting its window elapse.
		assertNull("the outgoing session is PARKED, not finalized, at the moment of confirmation",
			confirmed.getFinalized());
		assertEquals(originalId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(GARGOYLES, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals("DESIGN CORRECTION preserved: the interruption instant is T2 (the confirming "
			+ "observation's own timestamp), never backdated to T1",
			secondAgility, engine.getInterruptedCandidateInterruptedAt());

		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());
		assertEquals(AGILITY, confirmed.getCurrent().getActivityIdentity());
		assertNotEquals(originalId, confirmed.getCurrent().getSessionId());
		assertEquals("DESIGN CORRECTION: the new session must start at T2, never T1",
			secondAgility.toString(), confirmed.getCurrent().getStartedAt());

		// Letting the interrupted-resume window elapse without returning
		// proves the parked candidate eventually finalizes exactly as
		// this test originally asserted synchronously, still anchored at
		// T2, never T1.
		LifecycleResult expiry = engine.advanceTime(secondAgility.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals(SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(originalId, expiry.getAdditionalFinalized().getSessionId());
		assertEquals(GARGOYLES, expiry.getAdditionalFinalized().getActivityIdentity());
		assertEquals(secondAgility.toString(), expiry.getAdditionalFinalized().getFinalizedAt());
	}

	// =====================================================================
	// EVIDENCE-WEIGHTED HYSTERESIS (guarding against
	// "session ownership/hysteresis is too sticky,
	// equally so for ACTIVE and SUSPENDED"). See SessionLifecycleEngine's
	// own class javadoc for the full design. Live evidence: a SUSPENDED
	// BOSSING/Zulrah session survived 4 wolves, a camel, 3 goats, and an
	// entire inventory of Crafting; separately, an ACTIVE
	// SKILLING/Crafting session took nearly two minutes to yield to
	// sustained Cooking. Tests below exercise the two SKILLING identities
	// from that exact live report (CRAFTING/COOKING) directly, since the
	// original bug was SKILLING established vs. SKILLING challenger, not
	// combat-branch -- generalizing the coverage above (GARGOYLES/AGILITY)
	// rather than duplicating it.
	// =====================================================================

	// Required regression: "active skilling -> brief incidental different
	// skill, no switch." A confidently-current (just-reinforced) ACTIVE
	// SKILLING session must survive ONE incidental different-skill
	// observation, exactly like the combat-branch case above -- this was
	// never in question (floor requirement is 2, always above a lone
	// observation's count of 1), but the live bug was specifically
	// SKILLING-established, so this proves it directly rather than only
	// by extension from the combat-branch coverage.
	@Test
	public void activeSkilling_briefIncidentalDifferentSkill_doesNotSwitch()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(CRAFTING, T0).getCurrent().getSessionId();

		LifecycleResult result = engine.onQualifyingActivity(COOKING, T0.plusSeconds(15),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertNull("a single incidental Cooking observation must never switch an ACTIVE Crafting session",
			result.getFinalized());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(CRAFTING, result.getCurrent().getActivityIdentity());
		assertEquals(originalId, result.getCurrent().getSessionId());
	}

	// Required regression: "active skilling -> sustained different skill,
	// timely switch." Once the established Crafting session has gone long
	// enough without its OWN reinforcing evidence for its inertia to decay
	// to the floor (ACTIVE_INERTIA_DECAY_PERIOD after lastActiveAt -- see
	// SessionLifecycleEngine's own javadoc), a SECOND coherent Cooking
	// observation is enough to confirm -- the SAME count SUSPENDED would
	// have needed, never more, and never gated behind a fixed elapsed-time
	// lock (the switch is still driven entirely by the second observation
	// actually arriving, not by a timer firing on its own).
	@Test
	public void activeSkilling_sustainedDifferentSkillAfterEstablishedSessionGoesStale_confirmsTimely()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(CRAFTING, T0).getCurrent().getSessionId();

		Instant firstCooking = T0.plusSeconds(34);
		LifecycleResult armed = engine.onQualifyingActivity(COOKING, firstCooking,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull("the first Cooking observation only arms a candidate", armed.getFinalized());

		// Total elapsed since Crafting's own last reinforcement (T0) is
		// now past ACTIVE_INERTIA_DECAY_PERIOD (60s) -- inertia has fully
		// decayed to the floor, so this SECOND coherent observation is
		// enough, without needing a third.
		Instant secondCooking = T0.plusSeconds(70);
		LifecycleResult confirmed = engine.onQualifyingActivity(COOKING, secondCooking,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): PARKED, not
		// finalized, at confirmation -- see the test above's own comment
		// for the full rationale.
		assertNull(confirmed.getFinalized());
		assertEquals(originalId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(CRAFTING, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(secondCooking, engine.getInterruptedCandidateInterruptedAt());
		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());
		assertEquals(COOKING, confirmed.getCurrent().getActivityIdentity());

		LifecycleResult expiry = engine.advanceTime(secondCooking.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals("a second coherent observation confirms once the established session's inertia has "
			+ "decayed to the floor -- materially sooner than requiring indefinitely more repetition",
			SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(originalId, expiry.getAdditionalFinalized().getSessionId());
		assertEquals(CRAFTING, expiry.getAdditionalFinalized().getActivityIdentity());
		assertEquals(secondCooking.toString(), expiry.getAdditionalFinalized().getFinalizedAt());
	}

	// Required regression: "continuing current-activity evidence
	// reinforces ownership." A reinforcing Crafting heartbeat between two
	// Cooking observations abandons the pending Cooking candidate
	// entirely (exactly like the established identity returning in Case
	// B above) AND resets the decay clock (lastActiveAt) -- so the SAME
	// total elapsed time that would have been enough to confirm without
	// that reinforcement (see the test above) is no longer enough
	// afterward: the challenger must start over from its own first
	// observation, evaluated against a freshly-reinforced (not stale)
	// session.
	@Test
	public void continuingCurrentActivityEvidence_reinforcesOwnership_resetsChallengerProgress()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(CRAFTING, T0).getCurrent().getSessionId();

		// Arm a Cooking candidate, same as the test above.
		engine.onQualifyingActivity(COOKING, T0.plusSeconds(34),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// The player goes back to genuinely crafting before Cooking's
		// second observation ever arrives -- reinforces Crafting's own
		// ownership and abandons the pending Cooking candidate.
		Instant reinforcedAt = T0.plusSeconds(40);
		LifecycleResult reinforced = engine.onQualifyingActivity(CRAFTING, reinforcedAt);
		assertNull(reinforced.getFinalized());
		assertEquals(CRAFTING, reinforced.getCurrent().getActivityIdentity());

		// The reinforcement above cleared the pending candidate entirely,
		// so THIS is only its first observation since then (a fresh arm,
		// not a confirming match) -- at the exact same wall-clock instant
		// (T0+70s) that confirmed a switch in the test above with no
		// intervening reinforcement. Reinforcement must genuinely reset
		// the challenger's progress, not merely delay it.
		LifecycleResult notYetConfirmed = engine.onQualifyingActivity(COOKING, T0.plusSeconds(70),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull("reinforcement must genuinely reset the challenger's accumulated progress -- the exact "
			+ "instant that confirmed a switch without reinforcement isn't even a second observation here",
			notYetConfirmed.getFinalized());
		assertEquals(CRAFTING, notYetConfirmed.getCurrent().getActivityIdentity());
		assertEquals(originalId, notYetConfirmed.getCurrent().getSessionId());

		// A second Cooking observation, now comfortably past the decay
		// period measured from the REINFORCED lastActiveAt (T0+40s),
		// finally confirms.
		Instant finalCooking = T0.plusSeconds(105);
		LifecycleResult confirmed = engine.onQualifyingActivity(COOKING, finalCooking,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): PARKED, not
		// finalized, at confirmation.
		assertNull(confirmed.getFinalized());
		assertEquals(originalId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(COOKING, confirmed.getCurrent().getActivityIdentity());

		LifecycleResult expiry = engine.advanceTime(finalCooking.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals(SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(originalId, expiry.getAdditionalFinalized().getSessionId());
	}

	// =====================================================================
	// DOWNRANKING COMBAT CHALLENGE: a generic COMBAT candidate challenging
	// an established SLAYER/BOSSING session requires
	// ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS (3) coherent observations,
	// flat and non-decaying -- conservative because current telemetry
	// cannot distinguish an encounter's own subordinate NPCs (e.g. a
	// Death Spawn) from a genuinely unrelated off-task NPC, so the same
	// requirement applies to both. Same-rank COMBAT-vs-COMBAT and SKILLING
	// candidates are unaffected.
	// =====================================================================

	private static final ActivityIdentity NECHRYAEL =
		new ActivityIdentity(ActivityType.SLAYER, "nechryael:slayer tower", "Nechryael");

	// Two Death Spawn observations, even 200 seconds apart, must not
	// confirm -- proves there is no time-based decay in this requirement.
	@Test
	public void downrankingCombatChallenge_twoConfirmations_neverSufficesRegardlessOfElapsedTime_noTimeBasedErosion()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String nechryaelId = engine.onQualifyingActivity(NECHRYAEL, T0).getCurrent().getSessionId();
		ActivityIdentity deathSpawn = ActivitySignalClassifier.genericNpcCombatIdentity("Death Spawn");

		LifecycleResult afterFirst = engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(2),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull("the first Death Spawn observation only arms a candidate", afterFirst.getFinalized());
		assertEquals(NECHRYAEL, afterFirst.getCurrent().getActivityIdentity());

		LifecycleResult afterSecond = engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(200),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull("a SECOND Death Spawn observation must never be enough to displace an ACTIVE "
			+ "Nechryael session, no matter how much wall-clock time has passed since Nechryael's own "
			+ "last reinforcement -- ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS is a flat 3 with no "
			+ "time-based erosion of any kind",
			afterSecond.getFinalized());
		assertEquals("the Nechryael session must remain completely untouched -- same identity, same "
			+ "sessionId, no fragmentation", NECHRYAEL, afterSecond.getCurrent().getActivityIdentity());
		assertEquals(nechryaelId, afterSecond.getCurrent().getSessionId());
		assertEquals(SessionState.ACTIVE, afterSecond.getCurrent().getState());
	}

	// GENERALIZED OWNERSHIP/CHURN FIX (live QA cases: Nechryael/Death Spawn,
	// and Kalphite Workers -- the second reproducing WITHOUT any subordinate
	// actor at all, proving a flat observation count alone was the defect,
	// not anything subordinate-specific). Three coherent Death Spawn
	// observations landing within a handful of real seconds -- exactly what
	// continuous combat against a recurring subordinate/family-registry-gap
	// NPC produces -- must NOT confirm: ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS
	// (an observation COUNT) is necessary but no longer sufficient by itself;
	// ACTIVE_DOWNRANK_SUSTAINED_DURATION (a real elapsed-time SPAN) must also
	// be satisfied. This test replaces the prior (incorrect) expectation that
	// three closely-spaced observations alone were enough -- that was the
	// live-reproduced bug.
	@Test
	public void downrankingCombatChallenge_thirdCoherentConfirmationButNotSustained_staysArmed_doesNotConfirm()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String nechryaelId = engine.onQualifyingActivity(NECHRYAEL, T0).getCurrent().getSessionId();
		ActivityIdentity deathSpawn = ActivitySignalClassifier.genericNpcCombatIdentity("Death Spawn");

		engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(2),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(4),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		Instant thirdAt = T0.plusSeconds(6);
		LifecycleResult afterThird = engine.onQualifyingActivity(deathSpawn, thirdAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertNull("a THIRD coherent Death Spawn observation, only 6 seconds after the first, "
			+ "has not yet SPANNED ACTIVE_DOWNRANK_SUSTAINED_DURATION -- it must stay armed, not "
			+ "confirm -- this is the exact live-QA Nechryael churn this fix closes",
			afterThird.getFinalized());
		assertEquals("the Nechryael session must remain completely untouched",
			NECHRYAEL, afterThird.getCurrent().getActivityIdentity());
		assertEquals(nechryaelId, afterThird.getCurrent().getSessionId());
		assertEquals(SessionState.ACTIVE, afterThird.getCurrent().getState());
		assertNull("no interrupted candidate is parked -- nothing was confirmed",
			engine.getInterruptedCandidate());
	}

	// A downranking challenger that genuinely SUSTAINS -- its own
	// observations spanning at least ACTIVE_DOWNRANK_SUSTAINED_DURATION,
	// with no intervening Nechryael reinforcement -- still confirms.
	// "Slayer + genuinely unrelated/off-task sustained combat -> eventually
	// allowed to leave Slayer" must keep holding; the fix raises the bar, it
	// never makes an ACTIVE Slayer/Bossing session impossible to leave.
	@Test
	public void downrankingCombatChallenge_thirdConfirmationGenuinelySustained_confirmsSwitch()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String nechryaelId = engine.onQualifyingActivity(NECHRYAEL, T0).getCurrent().getSessionId();
		ActivityIdentity deathSpawn = ActivitySignalClassifier.genericNpcCombatIdentity("Death Spawn");

		engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(2),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(30),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// Comfortably past ACTIVE_DOWNRANK_SUSTAINED_DURATION (derived from
			// SUSPEND_TIMEOUT / 5 = 60s) measured from the FIRST observation
			// (T0+2), never from the second.
		Instant thirdAt = T0.plusSeconds(75);
		LifecycleResult afterThird = engine.onQualifyingActivity(deathSpawn, thirdAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertNull("a third observation that has now genuinely SUSTAINED past the required "
			+ "elapsed span must confirm (park, not immediately finalize)",
			afterThird.getFinalized());
		assertEquals(nechryaelId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(NECHRYAEL, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals(thirdAt, engine.getInterruptedCandidateInterruptedAt());
		assertEquals(ActivityType.COMBAT, afterThird.getCurrent().getActivityIdentity().getActivityType());
		assertEquals(deathSpawn, afterThird.getCurrent().getActivityIdentity());
		assertNotEquals(nechryaelId, afterThird.getCurrent().getSessionId());

		LifecycleResult expiry = engine.advanceTime(thirdAt.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals(SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(nechryaelId, expiry.getAdditionalFinalized().getSessionId());
		assertEquals(NECHRYAEL, expiry.getAdditionalFinalized().getActivityIdentity());
		assertEquals(thirdAt.toString(), expiry.getAdditionalFinalized().getFinalizedAt());
	}

	// LIVE QA CASE 1 shape (Nechryael): periodic authoritative reinforcement
	// (mirroring recurring SLAYER_TASK_PROGRESS heartbeats a few seconds
	// apart) interleaved with repeated Death Spawn bursts, across several
	// minutes of simulated continuous play. Because every reinforcing tick
	// resets the downranking candidate's span to zero, it can never
	// accumulate ACTIVE_DOWNRANK_SUSTAINED_DURATION of uninterrupted
	// candidacy -- the session must never churn away from SLAYER even once.
	@Test
	public void liveQaCase1Shape_periodicNechryaelReinforcementBetweenDeathSpawnBursts_neverChurnsAcrossLongPlay()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String nechryaelId = engine.onQualifyingActivity(NECHRYAEL, T0).getCurrent().getSessionId();
		ActivityIdentity deathSpawn = ActivitySignalClassifier.genericNpcCombatIdentity("Death Spawn");

		Instant t = T0;
		LifecycleResult afterReinforcement = null;
		for (int cycle = 0; cycle < 20; cycle++)
		{
			// Two closely-spaced Death Spawn bursts (arm + one repeat),
			// exactly like the live QA telemetry -- never a third before
			// the next reinforcement resets them.
			t = t.plusSeconds(3);
			engine.onQualifyingActivity(deathSpawn, t,
				Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
			t = t.plusSeconds(1);
			LifecycleResult afterBurst = engine.onQualifyingActivity(deathSpawn, t,
				Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
			assertEquals("cycle " + cycle + ": Nechryael must still be current after two Death "
				+ "Spawn observations", nechryaelId, afterBurst.getCurrent().getSessionId());

			// Authoritative Nechryael reinforcement (e.g. the next
			// SLAYER_TASK_PROGRESS tick) resets the candidate entirely.
			t = t.plusSeconds(3);
			afterReinforcement = engine.onQualifyingActivity(NECHRYAEL, t);
			assertEquals("cycle " + cycle + ": reinforcement must keep the SAME Nechryael session, "
				+ "never fragmenting", nechryaelId, afterReinforcement.getCurrent().getSessionId());
			assertNull("cycle " + cycle + ": nothing should ever be finalized across this entire "
				+ "reproduction", afterReinforcement.getFinalized());
		}

		// afterReinforcement is the LAST call made to the engine (this loop's
		// final iteration), so its own getCurrent() reflects the engine's
		// exact final state -- the same observation mechanism every other
		// test in this suite already uses (LifecycleResult.getCurrent()), not
		// a standalone engine-level accessor.
		assertEquals("after 20 full burst/reinforcement cycles (several simulated minutes of "
			+ "continuous play), the session must still be the ONE original Nechryael session -- no "
			+ "churn, ever", nechryaelId, afterReinforcement.getCurrent().getSessionId());
		assertEquals(NECHRYAEL, afterReinforcement.getCurrent().getActivityIdentity());
		assertNull("no interrupted candidate should ever have been parked", engine.getInterruptedCandidate());
	}

	// LIVE QA CASE 2 shape (Kalphite Workers): explicitly reproduced WITHOUT
	// any subordinate/minion actor at all -- "Kalphite Worker" is simply the
	// task's own on-task monster name, which SlayerTaskFamilyRegistry does not
	// (and, per Josh's instruction, must not be patched to) list as an alias
	// of "Kalphites", so every one of its combat-XP ticks is honestly
	// proposed as an off-task ORDINARY candidate. Proves the fix is a pure
	// evidence-precedence/timing correction, not anything parent/subordinate-
	// specific: the SAME periodic-reinforcement-resets-the-candidate shape
	// prevents churn here too, with zero registry changes.
	@Test
	public void liveQaCase2Shape_kalphiteWorkerRepeatedCombatNoSubordinateActor_neverChurnsAcrossLongPlay()
	{
		ActivityIdentity kalphites =
			new ActivityIdentity(ActivityType.SLAYER, "kalphites@kalphite lair", "Kalphites");
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String kalphitesId = engine.onQualifyingActivity(kalphites, T0).getCurrent().getSessionId();
		ActivityIdentity kalphiteWorker = ActivitySignalClassifier.genericNpcCombatIdentity("Kalphite Worker");

		Instant t = T0;
		LifecycleResult afterReinforcement = null;
		for (int cycle = 0; cycle < 20; cycle++)
		{
			t = t.plusSeconds(1);
			engine.onQualifyingActivity(kalphiteWorker, t,
				Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
			t = t.plusSeconds(1);
			LifecycleResult afterBurst = engine.onQualifyingActivity(kalphiteWorker, t,
				Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
			assertEquals("cycle " + cycle + ": Kalphites must still be current after two Kalphite "
				+ "Worker observations", kalphitesId, afterBurst.getCurrent().getSessionId());

			t = t.plusSeconds(2);
			afterReinforcement = engine.onQualifyingActivity(kalphites, t);
			assertEquals("cycle " + cycle + ": Slayer task-progress reinforcement must keep the SAME "
				+ "Kalphites session, never fragmenting into generic COMBAT/Kalphite Worker",
				kalphitesId, afterReinforcement.getCurrent().getSessionId());
			assertEquals(ActivityType.SLAYER, afterReinforcement.getCurrent().getActivityIdentity().getActivityType());
		}

		// afterReinforcement is the LAST call made to the engine (this loop's
		// final iteration), so its own getCurrent() reflects the engine's
		// exact final state -- the same observation mechanism every other
		// test in this suite already uses (LifecycleResult.getCurrent()), not
		// a standalone engine-level accessor.
		assertEquals("one continuous, uninterrupted Kalphites Slayer task must produce exactly "
			+ "one session, never the SLAYER -> COMBAT -> SLAYER churn the live QA video showed",
			kalphitesId, afterReinforcement.getCurrent().getSessionId());
		assertNull(engine.getInterruptedCandidate());
	}

	// Owner evidence returning before the third confirmation abandons the
	// challenger and preserves the same sessionId.
	@Test
	public void downrankingCombatChallenge_ownerEvidenceReturnsBeforeThirdConfirmation_abandonsChallengerPreservesSessionId()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String nechryaelId = engine.onQualifyingActivity(NECHRYAEL, T0).getCurrent().getSessionId();
		ActivityIdentity deathSpawn = ActivitySignalClassifier.genericNpcCombatIdentity("Death Spawn");

		// Two Death Spawn observations -- arms, then repeats, but never
		// reaches the 3-confirmation requirement.
		engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(2),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(4),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// Nechryael's own evidence returns (e.g. another
		// SLAYER_TASK_PROGRESS tick) before a third Death Spawn
		// observation ever arrives.
		LifecycleResult reinforced = engine.onQualifyingActivity(NECHRYAEL, T0.plusSeconds(6));
		assertNull("owner evidence returning must abandon the pending challenger, not finalize anything",
			reinforced.getFinalized());
		assertEquals(NECHRYAEL, reinforced.getCurrent().getActivityIdentity());
		assertEquals(nechryaelId, reinforced.getCurrent().getSessionId());

		// The abandonment must be genuine, not merely delayed: a further
		// Death Spawn observation right after reinforcement is only the
		// candidate's first observation since the reset, so it alone
		// must not confirm either.
		LifecycleResult notYetConfirmed = engine.onQualifyingActivity(deathSpawn, T0.plusSeconds(8),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull("reinforcement must genuinely reset the downranking challenger's progress -- this is "
			+ "only its first observation since Nechryael's own evidence returned",
			notYetConfirmed.getFinalized());
		assertEquals(NECHRYAEL, notYetConfirmed.getCurrent().getActivityIdentity());
		assertEquals(nechryaelId, notYetConfirmed.getCurrent().getSessionId());
	}

	// Same-rank COMBAT-vs-COMBAT is explicitly NOT a downranking
	// challenge -- an established generic COMBAT session gets no extra
	// inertia against another generic COMBAT candidate, exactly as before
	// this fix (the separately-tuned generic-NPC-recognition system is
	// left completely untouched).
	@Test
	public void sameRankCombatVsCombatChallenge_unaffectedByDownrankTreatment_confirmsAtFlatFloor()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity cow = ActivitySignalClassifier.genericNpcCombatIdentity("Cow");
		ActivityIdentity goat = ActivitySignalClassifier.genericNpcCombatIdentity("Goat");
		String cowSessionId = engine.onQualifyingActivity(cow, T0).getCurrent().getSessionId();

		engine.onQualifyingActivity(goat, T0.plusSeconds(2),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		Instant confirmAt = T0.plusSeconds(4);
		LifecycleResult confirmed = engine.onQualifyingActivity(goat, confirmAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): PARKED, not
		// finalized, at confirmation.
		assertNull("a same-rank generic-combat challenger must still confirm (park) at the flat floor "
			+ "(2), unaffected by the downranking-challenge decay treatment -- it is never NPC-specific, "
			+ "only rank-based, and COMBAT-vs-COMBAT is same-rank", confirmed.getFinalized());
		assertEquals(cowSessionId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(goat, confirmed.getCurrent().getActivityIdentity());

		LifecycleResult expiry = engine.advanceTime(confirmAt.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals(SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(cowSessionId, expiry.getAdditionalFinalized().getSessionId());
	}

	// isDownrankingCombatChallenge() scope: candidateType == COMBAT AND
	// establishedType in {SLAYER, BOSSING} -- exactly, nothing broader.
	@Test
	public void isDownrankingCombatChallenge_scopedToGenericCombatCandidateAgainstSlayerOrBossing() throws Exception
	{
		Method method = SessionLifecycleEngine.class.getDeclaredMethod(
			"isDownrankingCombatChallenge", ActivityType.class, ActivityType.class);
		method.setAccessible(true);

		assertTrue((Boolean) method.invoke(null, ActivityType.SLAYER, ActivityType.COMBAT));
		assertTrue((Boolean) method.invoke(null, ActivityType.BOSSING, ActivityType.COMBAT));
		assertFalse("same-rank COMBAT vs. COMBAT is not a downranking challenge",
			(Boolean) method.invoke(null, ActivityType.COMBAT, ActivityType.COMBAT));
		assertFalse("a SLAYER candidate is never a downranking challenge, even against BOSSING",
			(Boolean) method.invoke(null, ActivityType.BOSSING, ActivityType.SLAYER));
		assertFalse("a BOSSING candidate is never a downranking challenge, even against SLAYER",
			(Boolean) method.invoke(null, ActivityType.SLAYER, ActivityType.BOSSING));
	}

	// Required regression: "suspended session requires materially less
	// challenger evidence than active session." The exact same
	// weak-evidence challenger pattern (two coherent observations, close
	// together, immediately after the established session's own last
	// reinforcement) confirms against a SUSPENDED session but NOT against
	// a freshly-reinforced ACTIVE one -- SUSPENDED's fixed requirement (2)
	// is strictly less than ACTIVE's peak requirement (3), proving
	// SUSPENDED's ownership is materially weaker by construction, not by
	// coincidence of timing.
	@Test
	public void suspendedSession_requiresMateriallyLessChallengerEvidenceThanActiveSession()
	{
		// SUSPENDED: two close-together coherent observations confirm.
		SessionLifecycleEngine suspendedEngine = new SessionLifecycleEngine();
		suspendedEngine.onQualifyingActivity(ZULRAH, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		suspendedEngine.advanceTime(suspendedAt);
		suspendedEngine.onQualifyingActivity(CRAFTING, suspendedAt.plusSeconds(10),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		LifecycleResult suspendedResult = suspendedEngine.onQualifyingActivity(CRAFTING, suspendedAt.plusSeconds(20),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertEquals("SUSPENDED confirms with exactly two close-together coherent observations",
			SessionState.FINALIZED, suspendedResult.getFinalized().getState());

		// ACTIVE, freshly reinforced at its own equivalent T0: the SAME
		// two close-together observations must NOT yet confirm.
		SessionLifecycleEngine activeEngine = new SessionLifecycleEngine();
		activeEngine.onQualifyingActivity(ZULRAH, T0);
		activeEngine.onQualifyingActivity(CRAFTING, T0.plusSeconds(10),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		LifecycleResult activeResult = activeEngine.onQualifyingActivity(CRAFTING, T0.plusSeconds(20),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertNull("the SAME two close-together observations must NOT confirm against a freshly-reinforced "
			+ "ACTIVE session -- ACTIVE requires strictly MORE challenger evidence than SUSPENDED",
			activeResult.getFinalized());
		assertEquals(SessionState.ACTIVE, activeResult.getCurrent().getState());
		assertEquals(ZULRAH, activeResult.getCurrent().getActivityIdentity());
	}

	// Case B: the established session's OWN identity returns before
	// confirmation -- the pending ACTIVE candidate is abandoned, its
	// buffered metric committed once as a contextual metric on the
	// still-current (never disturbed) session.
	@Test
	public void weakEvidenceWhileActive_originalIdentityReturnsFirst_candidateAbandonedContextually()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);

		Instant weakAt = T0.plusSeconds(34);
		engine.onQualifyingActivity(AGILITY, weakAt, Arrays.asList(MetricUpdate.xp("agility", 6)),
			EvidenceStrength.ORDINARY);

		Instant realReturnAt = weakAt.plusSeconds(10);
		LifecycleResult afterReturn = engine.onQualifyingActivity(GARGOYLES, realReturnAt);

		assertNull("returning to the established session's own identity must never finalize it",
			afterReturn.getFinalized());
		assertEquals(SessionState.ACTIVE, afterReturn.getCurrent().getState());
		assertEquals(GARGOYLES, afterReturn.getCurrent().getActivityIdentity());
		assertEquals("the abandoned Agility candidate's buffered metric must be committed once, "
			+ "contextually, to the still-current Gargoyles session",
			afterReturn.getCurrent(), afterReturn.getContextualMetricsTarget());
		assertEquals(1, afterReturn.getContextualMetrics().size());
		apply(afterReturn.getCurrent(), afterReturn.getContextualMetrics());
		assertEquals(6L, totalXp(afterReturn.getCurrent(), "agility"));
	}

	// Case D (forward-looking design property -- not yet exercised by any
	// real production signal, since no current classifier branch tags a
	// SKILLING identity SPECIFIC/AUTHORITATIVE): AUTHORITATIVE/SPECIFIC
	// evidence must always switch/refine immediately, never gated by the
	// weak-evidence candidate mechanism, regardless of ACTIVE/SUSPENDED or
	// candidate identity type. Exercises the engine's own strength-gated
	// predicate directly (via the 4-arg overload) to prove the gate keys
	// off EvidenceStrength -- not merely off ActivityType -- so a future
	// generic-NPC-recognition pass can supply SPECIFIC/AUTHORITATIVE
	// evidence for a non-combat-branch identity without any redesign here.
	@Test
	public void authoritativeOrSpecificEvidenceWhileActive_switchesImmediately_neverGatedRegardlessOfIdentityType()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();

		Instant observedAt = T0.plusSeconds(34);
		LifecycleResult result = engine.onQualifyingActivity(AGILITY, observedAt,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);

		// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): "never gated
		// behind confirmation" still holds -- SPECIFIC evidence still
		// switches at this single observation, with no confirmation
		// delay -- it is now PARKED rather than immediately finalized,
		// exactly like every other genuine real switch.
		assertNull("SPECIFIC evidence must never be gated behind confirmation, even against an ACTIVE "
			+ "combat-branch session and even for a non-combat-branch candidate identity",
			result.getFinalized());
		assertEquals(originalId, engine.getInterruptedCandidate().getSessionId());
		assertEquals(AGILITY, result.getCurrent().getActivityIdentity());
		assertEquals("no confirmation delay -- the switch happens at this single observation's own "
			+ "timestamp", observedAt, engine.getInterruptedCandidateInterruptedAt());

		LifecycleResult expiry = engine.advanceTime(observedAt.plus(SessionLifecycleEngine.INTERRUPTED_RESUME_WINDOW).plusSeconds(1));
		assertEquals(SessionState.FINALIZED, expiry.getAdditionalFinalized().getState());
		assertEquals(originalId, expiry.getAdditionalFinalized().getSessionId());
		assertEquals(observedAt.toString(), expiry.getAdditionalFinalized().getFinalizedAt());
	}

	// A pending ACTIVE candidate that never gets its confirming second
	// observation before the established session's own suspend threshold
	// is reached must not leak silently -- it is abandoned here too,
	// committed once as contextual metrics to the now-SUSPENDED session
	// it genuinely occurred during.
	@Test
	public void activeCandidate_abandonedWhenSuspendThresholdReachedBeforeConfirmation()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);

		Instant weakAt = T0.plusSeconds(34);
		engine.onQualifyingActivity(AGILITY, weakAt, Arrays.asList(MetricUpdate.xp("agility", 6)),
			EvidenceStrength.ORDINARY);

		// A LATER event, past the suspend threshold measured from the
		// established session's own lastActiveAt (T0) -- never confirming
		// the pending Agility candidate. This new MINING evidence is
		// itself weak against the now-SUSPENDED Gargoyles session, so it
		// only arms a FRESH SUSPENDED-branch candidate of its own.
		Instant pastThreshold = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(5);
		LifecycleResult result = engine.onQualifyingActivity(MINING, pastThreshold,
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertNull(result.getFinalized());
		assertEquals(SessionState.SUSPENDED, result.getCurrent().getState());
		assertEquals(GARGOYLES, result.getCurrent().getActivityIdentity());

		assertEquals("the abandoned ACTIVE Agility candidate's buffered metric must be committed once, "
			+ "contextually, to the now-SUSPENDED Gargoyles session -- never lost",
			result.getCurrent(), result.getContextualMetricsTarget());
		assertEquals(1, result.getContextualMetrics().size());
		apply(result.getCurrent(), result.getContextualMetrics());
		assertEquals(6L, totalXp(result.getCurrent(), "agility"));
	}

	// Case E (SUSPENDED unchanged): the legacy 3-arg overload -- used by
	// every pre-existing test/caller above with no strength to supply --
	// must still default to ORDINARY, so the SUSPENDED branch's own gate
	// (exercised extensively above) continues to fire exactly as it
	// did before, and the SAME default now also extends the
	// gate to ACTIVE: a legacy 3-arg call with
	// weak, non-combat-branch evidence against an ACTIVE combat-branch
	// session must be gated, not switch immediately.
	@Test
	public void legacyThreeArgOverload_defaultsToOrdinaryStrength_gatesWeakEvidenceWhileActive()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String originalId = engine.onQualifyingActivity(GARGOYLES, T0).getCurrent().getSessionId();

		LifecycleResult result = engine.onQualifyingActivity(AGILITY, T0.plusSeconds(34),
			Collections.<MetricUpdate>emptyList());

		assertNull("the legacy 3-arg overload must default to ORDINARY, so a single incidental "
			+ "observation is gated exactly like a real ORDINARY-tagged signal would be",
			result.getFinalized());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(GARGOYLES, result.getCurrent().getActivityIdentity());
		assertEquals(originalId, result.getCurrent().getSessionId());
	}
}
