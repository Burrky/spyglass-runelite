package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Dedicated full-stack (SessionRuntimeCoordinator + SessionPersistence +
 * LocalStateStore + real disk I/O) coverage for the INTERRUPTED-ACTIVITY
 * RESUME (A -> brief B -> A) feature's PERSISTENCE contract: restart
 * safety, the account-switch/orderly-shutdown boundaries ("no candidate
 * may disappear silently"), and the manual "Re-evaluate Session" path's
 * preserved corrective semantics (it must never manufacture a hidden
 * resumable prior session). See InterruptedActivityResumeEngineTest for
 * the pure engine-level lifecycle/metric-ownership/boundary coverage
 * this file deliberately does not re-prove.
 */
public class InterruptedActivityResumeCoordinatorTest
{
	private static final long ACCOUNT_A = 810_200_300L;
	private static final long ACCOUNT_B = 810_200_400L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final Gson GSON = new Gson();

	private LocalStateStore store;
	private SessionRuntimeCoordinator coordinator;
	private Path testRoot;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("interrupted-activity-resume-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));

		store = new LocalStateStore(GSON);
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
	}

	@After
	public void tearDown() throws Exception
	{
		if (store != null)
		{
			store.shutdown();
		}
		if (testRoot != null)
		{
			TestFilepaths.rooted(testRoot).deleteRecursively();
		}
	}

	private Session readFinalizedFile(String sessionId) throws Exception
	{
		return store.readIfExists(TelemetryPaths.sessionFile(ACCOUNT_A, sessionId), Session.class);
	}

	// A genuine, confirmed ACTIVE Slayer(Gargoyles) -> Agility switch, via
	// the SAME real, two-observation-confirmed hysteresis path already
	// exhaustively proved elsewhere (see SessionRuntimeCoordinatorTest's
	// own liveBug_incidentalAgilityXpWhileActiveGargoyles... test, which
	// this mirrors) -- exists purely to set up "A parked, B current"
	// state with the minimum ceremony, not to re-prove that decision
	// itself.
	private String establishActiveGargoylesThenSwitchToAgility(Instant confirmingAt) throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String gargoylesSessionId = coordinator.testCurrentSession().getSessionId();

		Instant firstAgility = T0.plusSeconds(34);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstAgility, "AGILITY", 6L).withGameTick(2L));
		coordinator.testProcessTick(3L, firstAgility);

		coordinator.testEnqueueSignal(SessionSignal.xpChange(confirmingAt, "AGILITY", 4L).withGameTick(3L));
		coordinator.testProcessTick(4L, confirmingAt);

		return gargoylesSessionId;
	}

	// =====================================================================
	// REQUIRED TEST 12: restart AFTER the candidate has already expired
	// -- A finalizes exactly once, at T1, and no resumability remains.
	// T0 is a fixed point far in the past relative to whenever this test
	// actually executes, so ensureAccountLoaded()'s own real
	// Instant.now() is guaranteed to already be long past the
	// candidate's 5-minute window -- a completely realistic "restarted
	// the client long afterward" scenario, deterministic by construction
	// without needing to inject a fake clock.
	// =====================================================================
	@Test
	public void restartAfterCandidateExpiry_aFinalizesExactlyOnceAtT1_noResumabilityRemains() throws Exception
	{
		Instant confirmingAt = T0.plusSeconds(70);
		String gargoylesSessionId = establishActiveGargoylesThenSwitchToAgility(confirmingAt);
		Thread.sleep(300);

		InterruptedCandidateRecord beforeRestart = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNotNull("the parked Gargoyles candidate must be durably persisted before restart", beforeRestart);
		assertNull("A must not yet be finalized anywhere before restart", readFinalizedFile(gargoylesSessionId));

		// Simulate a client restart: a brand-new coordinator against the
		// SAME on-disk store/account.
		SessionRuntimeCoordinator restarted = new SessionRuntimeCoordinator(store);
		restarted.ensureAccountLoaded(ACCOUNT_A);

		Thread.sleep(300);
		Session finalizedA = readFinalizedFile(gargoylesSessionId);
		assertNotNull("A must finalize exactly once, durably, during rehydration", finalizedA);
		assertEquals(SessionState.FINALIZED, finalizedA.getState());
		assertEquals("A must finalize AT ITS OWN ORIGINAL interruption instant, never at restart time",
			confirmingAt.toString(), finalizedA.getFinalizedAt());

		InterruptedCandidateRecord afterRestart = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNull("no resumability may remain once the candidate has finalized", afterRestart);
	}

	// =====================================================================
	// REQUIRED TEST 13: account switch -- the parked candidate finalizes
	// under the OLD account only, never leaking into the new account.
	// =====================================================================
	@Test
	public void accountSwitchWithParkedCandidate_flushesUnderOldAccountOnly_neverLeaksToNewAccount() throws Exception
	{
		Instant confirmingAt = T0.plusSeconds(70);
		String gargoylesSessionId = establishActiveGargoylesThenSwitchToAgility(confirmingAt);
		Thread.sleep(300);

		InterruptedCandidateRecord beforeSwitch = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNotNull(beforeSwitch);

		coordinator.ensureAccountLoaded(ACCOUNT_B);
		Thread.sleep(300);

		Session finalizedUnderA = readFinalizedFile(gargoylesSessionId);
		assertNotNull("A must be flushed/finalized under the OLD account exactly", finalizedUnderA);
		assertEquals(SessionState.FINALIZED, finalizedUnderA.getState());
		assertEquals("finalized AT ITS OWN interruption instant, never at switch time",
			confirmingAt.toString(), finalizedUnderA.getFinalizedAt());

		InterruptedCandidateRecord afterSwitchOldAccount = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNull("the old account's candidate record must be cleared once flushed", afterSwitchOldAccount);

		InterruptedCandidateRecord newAccountCandidate = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_B);
		assertNull("the new account must never inherit the old account's interrupted candidate",
			newAccountCandidate);
		assertNull("account B must start from a clean hydration, never an invented transition",
			coordinator.testCurrentSession());
	}

	// =====================================================================
	// REQUIRED TEST 14: orderly plugin shutdown -- the parked candidate
	// is never lost, and finalizes exactly once, at T1.
	// =====================================================================
	@Test
	public void orderlyShutdown_parkedCandidateNotLost_finalizesOnceAtT1() throws Exception
	{
		Instant confirmingAt = T0.plusSeconds(70);
		String gargoylesSessionId = establishActiveGargoylesThenSwitchToAgility(confirmingAt);

		coordinator.flushPendingAndShutdown();

		Thread.sleep(300);
		Session finalizedA = readFinalizedFile(gargoylesSessionId);
		assertNotNull("the parked candidate must never disappear silently on orderly shutdown", finalizedA);
		assertEquals(SessionState.FINALIZED, finalizedA.getState());
		assertEquals(confirmingAt.toString(), finalizedA.getFinalizedAt());

		InterruptedCandidateRecord afterShutdown = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNull(afterShutdown);
	}

	// =====================================================================
	// LIVE REGRESSION: Crafting A -> Cooking B -> close RuneLite ->
	// restart -> return to Crafting inside A's window. Drives the REAL
	// production shutdown path of the OLD process (the exact coordinator
	// method RuneLite's ClientShutdown / Plugin.shutDown() invoke,
	// followed by the store drain OsrsTelemetryPlugin.shutDown() does),
	// then constructs FRESH LocalStateStore + SessionRuntimeCoordinator
	// objects exactly as a new client process does and loads the same
	// account via ensureAccountLoaded() (what handleLogin() calls). The
	// pre-existing restart coverage skipped the old process's shutdown
	// path entirely (engine tests hand the in-memory candidate straight
	// to rehydrate()), which is precisely where the candidate was being
	// destroyed. Timestamps are anchored to the real clock because
	// ensureAccountLoaded() rehydrates against Instant.now().
	// =====================================================================
	@Test
	public void liveRestart_clientShutdownOnly_craftingAResumesWithSameSessionId() throws Exception
	{
		assertRealRestartResumesCraftingA(false);
	}

	// Real RuneLite close where Plugin.shutDown() ALSO runs after
	// ClientShutdown: the later shutDown must not destroy the candidate
	// ClientShutdown just preserved.
	@Test
	public void liveRestart_clientShutdownThenPluginShutDown_candidateSurvives_craftingAResumes() throws Exception
	{
		assertRealRestartResumesCraftingA(true);
	}

	// MANUAL plugin disable (no ClientShutdown): Spyglass could not
	// observe gameplay while disabled, so A must finalize exactly once at
	// its ORIGINAL interruption instant, the record must be cleared, and a
	// later fresh runtime must never resume A -- even inside the
	// original 5-minute wall-clock window.
	@Test
	public void manualPluginDisable_craftingAFinalizesAtT1_cleared_freshRuntimeCannotResumeA() throws Exception
	{
		String[] ids = establishCraftingAThenCookingB();
		String aSessionId = ids[0];
		String bSessionId = ids[1];
		Instant t1 = Instant.parse(ids[2]);

		coordinator.flushPendingAndShutdown();
		store.shutdown();

		Session finalizedA = readFinalizedFile(aSessionId);
		assertNotNull("A must finalize on a manual disable", finalizedA);
		assertEquals(SessionState.FINALIZED, finalizedA.getState());
		assertEquals("A must finalize at its ORIGINAL interruption instant", t1.toString(), finalizedA.getFinalizedAt());
		assertNull("the candidate record must be cleared on a manual disable",
			new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A));

		store = new LocalStateStore(GSON);
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		assertNull("a fresh runtime must have no resumable candidate", coordinator.testInterruptedCandidate());
		assertEquals(bSessionId, coordinator.testCurrentSession().getSessionId());

		Instant firstReturn = Instant.now().plusSeconds(5);
		Instant secondReturn = firstReturn.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstReturn, "CRAFTING", 280L).withGameTick(100L));
		coordinator.testProcessTick(101L, firstReturn);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(secondReturn, "CRAFTING", 630L).withGameTick(101L));
		coordinator.testProcessTick(102L, secondReturn);

		Session current = coordinator.testCurrentSession();
		assertEquals("crafting", current.getActivityIdentity().getActivityKey());
		assertFalse("returning Crafting must be a NEW session, never resurrected A",
			aSessionId.equals(current.getSessionId()));
		Thread.sleep(300);
		Session aAgain = readFinalizedFile(aSessionId);
		assertEquals("A must have finalized exactly once, untouched afterward", t1.toString(), aAgain.getFinalizedAt());
	}

	// Crafting A (active) -> genuine Cooking B switch through the real
	// coordinator path, anchored to the real clock (ensureAccountLoaded()
	// rehydrates against Instant.now()). Returns {aSessionId, bSessionId,
	// aInterruptedAt, aStartedAt}.
	private String[] establishCraftingAThenCookingB() throws Exception
	{
		Instant base = Instant.now().minusSeconds(150);
		long tick = 1L;

		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(base, "CRAFTING", 140L).withGameTick(tick));
		coordinator.testProcessTick(++tick, base);
		Instant craftingAgain = base.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(craftingAgain, "CRAFTING", 980L).withGameTick(tick));
		coordinator.testProcessTick(++tick, craftingAgain);
		Session a = coordinator.testCurrentSession();
		assertEquals("crafting", a.getActivityIdentity().getActivityKey());

		Instant cookingAt = craftingAgain;
		for (int i = 0; i < 6 && coordinator.testInterruptedCandidate() == null; i++)
		{
			cookingAt = cookingAt.plusSeconds(20);
			coordinator.testEnqueueSignal(SessionSignal.xpChange(cookingAt, "COOKING", 2100L).withGameTick(tick));
			coordinator.testProcessTick(++tick, cookingAt);
		}
		assertNotNull("setup: A must be parked by a genuine Cooking switch", coordinator.testInterruptedCandidate());
		assertEquals(a.getSessionId(), coordinator.testInterruptedCandidate().getSessionId());
		Session b = coordinator.testCurrentSession();
		assertEquals("cooking", b.getActivityIdentity().getActivityKey());
		Thread.sleep(300);
		InterruptedCandidateRecord parked = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNotNull(parked);
		return new String[] {a.getSessionId(), b.getSessionId(), parked.getInterruptedAt(), a.getStartedAt()};
	}

	private void assertRealRestartResumesCraftingA(boolean pluginShutDownFollows) throws Exception
	{
		String[] ids = establishCraftingAThenCookingB();
		String aSessionId = ids[0];
		String bSessionId = ids[1];
		String aStartedAt = ids[3];

		// 3./4. Real shutdown of the OLD process -- the exact entry point
		// onClientShutdown() drives (beginClientShutdownFinalization()
		// marks client shutdown synchronously, then runs the blocking
		// finalization on scheduledExecutor -- called directly here since
		// that injected executor does not exist in tests) -- optionally followed by
		// the Plugin.shutDown() sequence, then the store drain.
		coordinator.markClientShutdownBegun();
		coordinator.finalizeForClientShutdownBlocking();
		if (pluginShutDownFollows)
		{
			coordinator.flushPendingAndShutdown();
		}
		store.shutdown();

		InterruptedCandidateRecord persistedBefore = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNotNull("the candidate record must survive the old process's shutdown path", persistedBefore);
		assertNull("shutdown must not finalize A", readFinalizedFile(aSessionId));

		// 5./6. FRESH runtime objects, as real plugin startup builds them,
		// then the same account logs in.
		store = new LocalStateStore(GSON);
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// 7.
		Session current = coordinator.testCurrentSession();
		assertEquals(bSessionId, current.getSessionId());
		assertEquals("cooking", current.getActivityIdentity().getActivityKey());
		assertEquals(SessionState.SUSPENDED, current.getState());
		Session candidate = coordinator.testInterruptedCandidate();
		assertNotNull("the interrupted candidate must be restored into the fresh engine", candidate);
		assertEquals(aSessionId, candidate.getSessionId());
		assertEquals("crafting", candidate.getActivityIdentity().getActivityKey());
		Thread.sleep(300);
		InterruptedCandidateRecord persistedAfter = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNotNull(persistedAfter);
		assertEquals(persistedBefore.getInterruptedAt(), persistedAfter.getInterruptedAt());
		assertEquals(persistedBefore.getExpiresAt(), persistedAfter.getExpiresAt());
		assertEquals(persistedBefore.getSession().getSessionId(), persistedAfter.getSession().getSessionId());
		Instant expiresAt = persistedAfter.expiresAtInstant();

		// 8. Ordinary Crafting evidence through the real coordinator path.
		Instant firstReturn = Instant.now().plusSeconds(5);
		Instant secondReturn = firstReturn.plusSeconds(30);
		assertFalse("setup: the return must be inside A's window", secondReturn.isAfter(expiresAt));
		coordinator.testEnqueueSignal(SessionSignal.xpChange(firstReturn, "CRAFTING", 280L).withGameTick(100L));
		coordinator.testProcessTick(101L, firstReturn);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(secondReturn, "CRAFTING", 630L).withGameTick(101L));
		coordinator.testProcessTick(102L, secondReturn);

		// 9.
		Session resumed = coordinator.testCurrentSession();
		assertEquals("original A must resume -- never a fresh Crafting session", aSessionId, resumed.getSessionId());
		assertEquals(aStartedAt, resumed.getStartedAt());
		assertEquals(SessionState.ACTIVE, resumed.getState());
		assertNull(coordinator.testInterruptedCandidate());

		Thread.sleep(300);
		Session finalizedB = readFinalizedFile(bSessionId);
		assertNotNull("B must finalize separately", finalizedB);
		assertEquals(SessionState.FINALIZED, finalizedB.getState());
		assertNull("A must not have been finalized anywhere", readFinalizedFile(aSessionId));
		assertNull("no candidate record may linger once A resumed",
			new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A));
		try (java.util.stream.Stream<Path> files = Files.list(testRoot.resolve(String.valueOf(ACCOUNT_A)).resolve("sessions")))
		{
			assertEquals("exactly one finalized session file (B) -- no fresh Crafting session minted",
				1L, files.count());
		}
	}

	// =====================================================================
	// REQUIRED TEST 20: manual "Re-evaluate Session" regression -- it
	// must never manufacture a hidden resumable prior session/History
	// entry, even in a scenario the AUTOMATIC pipeline would genuinely
	// park (allowInterruptedResume=false is threaded through
	// specifically to guarantee this -- see reEvaluateCurrentSession()'s
	// own wiring). The outgoing session must finalize IMMEDIATELY and
	// DURABLY, exactly as manual re-evaluation always has, before this
	// feature existed.
	// =====================================================================
	@Test
	public void manualReEvaluation_neverParksOutgoingSession_evenInAScenarioTheAutomaticPathWouldHaveParked() throws Exception
	{
		coordinator.ensureAccountLoaded(ACCOUNT_A);

		// Establish a plain generic COMBAT session (no recent NPC target
		// tracked) -- the exact same setup already proven elsewhere to
		// switch immediately via the manual button
		// (manualReEvaluation_freshEvidence_switchesImmediately_withoutWaitingForNormalCandidateThreshold
		// in SessionRuntimeCoordinatorTest), which this test extends to
		// also verify the interrupted-candidate persistence contract that
		// one does not check.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		Session established = coordinator.testCurrentSession();
		String establishedSessionId = established.getSessionId();

		coordinator.reEvaluateCurrentSession("Giant rat", 55, T0.plusSeconds(5));

		Session afterManualCall = coordinator.testCurrentSession();
		assertFalse("a real activity switch must start a brand-new session",
			establishedSessionId.equals(afterManualCall.getSessionId()));

		Thread.sleep(300);
		Session finalizedOld = readFinalizedFile(establishedSessionId);
		assertNotNull("manual re-evaluation must finalize the outgoing session IMMEDIATELY and DURABLY -- "
			+ "never park it", finalizedOld);
		assertEquals(SessionState.FINALIZED, finalizedOld.getState());
		assertEquals(T0.plusSeconds(5).toString(), finalizedOld.getFinalizedAt());

		InterruptedCandidateRecord candidateRecord = new SessionPersistence(store).loadInterruptedCandidate(ACCOUNT_A);
		assertNull("manual re-evaluation must never manufacture a hidden resumable prior session",
			candidateRecord);
	}
}
