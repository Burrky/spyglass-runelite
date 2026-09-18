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

		LifecycleResult result = engine.onQualifyingActivity(ZULRAH, T0.plusSeconds(30));

		assertEquals(SessionState.FINALIZED, result.getFinalized().getState());
		assertEquals(oldId, result.getFinalized().getSessionId());
		assertEquals(GARGOYLES, result.getFinalized().getActivityIdentity());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(ZULRAH, result.getCurrent().getActivityIdentity());
		assertNotEquals(oldId, result.getCurrent().getSessionId());
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
		Session finalizedOld = engine.onQualifyingActivity(ZULRAH, T0.plusSeconds(11).plus(Duration.ofMinutes(2)))
			.getFinalized();

		assertEquals("the old session's duration must stop at its own last evidence, never extended into the gap",
			durationAtLastGargoylesHeartbeat, finalizedOld.getAccumulatedActiveDurationMillis());
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

		assertEquals("a second, coherent observation of the same candidate identity confirms the switch "
			+ "once the established session's inertia has decayed enough to allow it",
			SessionState.FINALIZED, confirmed.getFinalized().getState());
		assertEquals(originalId, confirmed.getFinalized().getSessionId());
		assertEquals(GARGOYLES, confirmed.getFinalized().getActivityIdentity());
		assertEquals("DESIGN CORRECTION: the established ACTIVE session must finalize at T2 (the "
			+ "confirming observation's own timestamp), never backdated to T1 (the candidate's "
			+ "first-seen instant) -- the session was genuinely still ACTIVE for the whole T1..T2 gap, "
			+ "so no historical boundary is fabricated from ambiguous evidence",
			secondAgility.toString(), confirmed.getFinalized().getFinalizedAt());

		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());
		assertEquals(AGILITY, confirmed.getCurrent().getActivityIdentity());
		assertNotEquals(originalId, confirmed.getCurrent().getSessionId());
		assertEquals("DESIGN CORRECTION: the new session must start at T2, never T1",
			secondAgility.toString(), confirmed.getCurrent().getStartedAt());
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

		assertEquals("a second coherent observation confirms once the established session's inertia has "
			+ "decayed to the floor -- materially sooner than requiring indefinitely more repetition",
			SessionState.FINALIZED, confirmed.getFinalized().getState());
		assertEquals(originalId, confirmed.getFinalized().getSessionId());
		assertEquals(CRAFTING, confirmed.getFinalized().getActivityIdentity());
		assertEquals(secondCooking.toString(), confirmed.getFinalized().getFinalizedAt());
		assertEquals(SessionState.ACTIVE, confirmed.getCurrent().getState());
		assertEquals(COOKING, confirmed.getCurrent().getActivityIdentity());
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
		assertEquals(SessionState.FINALIZED, confirmed.getFinalized().getState());
		assertEquals(originalId, confirmed.getFinalized().getSessionId());
		assertEquals(COOKING, confirmed.getCurrent().getActivityIdentity());
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

		assertEquals("SPECIFIC evidence must never be gated behind confirmation, even against an ACTIVE "
			+ "combat-branch session and even for a non-combat-branch candidate identity",
			SessionState.FINALIZED, result.getFinalized().getState());
		assertEquals(originalId, result.getFinalized().getSessionId());
		assertEquals(AGILITY, result.getCurrent().getActivityIdentity());
		assertEquals("no confirmation delay -- the switch happens at this single observation's own "
			+ "timestamp", observedAt.toString(), result.getFinalized().getFinalizedAt());
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
