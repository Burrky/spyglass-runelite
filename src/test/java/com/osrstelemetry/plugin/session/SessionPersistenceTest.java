package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project.
 *
 * Covers serialization round-trips, restart/rehydration semantics,
 * finalized-record persistence and write ordering, and account
 * isolation — all against the real
 * LocalStateStore/TelemetryPaths, matching BankSnapshotBaselineTest's
 * own G-J section's established pattern (real files, no Client, two
 * distinct account hashes for isolation checks).
 */
public class SessionPersistenceTest
{
	private static final long TEST_ACCOUNT_HASH = 888_777_555L;
	private static final long OTHER_ACCOUNT_HASH = 888_777_666L;

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	private static final Gson GSON = new Gson();

	private LocalStateStore store;
	private SessionPersistence persistence;

	@Before
	public void setUp() throws Exception
	{
		deleteAccountDir(TEST_ACCOUNT_HASH);
		deleteAccountDir(OTHER_ACCOUNT_HASH);
		store = new LocalStateStore(GSON);
		store.start();
		persistence = new SessionPersistence(store);
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		deleteAccountDir(TEST_ACCOUNT_HASH);
		deleteAccountDir(OTHER_ACCOUNT_HASH);
	}

	private static void deleteAccountDir(long accountHash)
	{
		deleteRecursively(TelemetryPaths.accountDir(accountHash));
	}

	private static void deleteRecursively(File dir)
	{
		File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				if (f.isDirectory())
				{
					deleteRecursively(f);
				}
				else
				{
					f.delete();
				}
			}
		}
	}

	// --- 21/22/23: serialization round-trips ---

	@Test
	public void serializationRoundTrip_active()
	{
		Session original = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();

		Session roundTripped = GSON.fromJson(GSON.toJson(original), Session.class);

		assertEquals(original, roundTripped);
		assertEquals(SessionState.ACTIVE, roundTripped.getState());
		assertEquals(GARGOYLES, roundTripped.getActivityIdentity());
	}

	@Test
	public void serializationRoundTrip_suspended()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session original = engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT)).getCurrent();

		Session roundTripped = GSON.fromJson(GSON.toJson(original), Session.class);

		assertEquals(original, roundTripped);
		assertEquals(SessionState.SUSPENDED, roundTripped.getState());
		assertNotNull(roundTripped.getSuspendedAt());
		assertNotNull(roundTripped.getResumeWindowExpiresAt());
	}

	@Test
	public void serializationRoundTrip_finalizedRecord()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		Session original = engine.advanceTime(expiry).getFinalized();

		Session roundTripped = GSON.fromJson(GSON.toJson(original), Session.class);

		assertEquals(original, roundTripped);
		assertEquals(SessionState.FINALIZED, roundTripped.getState());
		assertEquals(expiry.toString(), roundTripped.getFinalizedAt());
	}

	// --- 24/25/26: restart/rehydration semantics ---

	@Test
	public void rehydrate_persistedActive_convertsConservativelyWithoutCountingOfflineTime()
	{
		Session persisted = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();
		long durationBeforeRehydration = persisted.getAccumulatedActiveDurationMillis();

		// RuneLite was closed for a full day; rehydration happens far later.
		Instant rehydratedAt = T0.plus(java.time.Duration.ofDays(1));
		SessionLifecycleEngine.RehydrationResult result = SessionLifecycleEngine.rehydrate(persisted, rehydratedAt);

		// The offline gap vastly exceeds the 30-minute resume window, so
		// the conservative SUSPENDED reconstruction immediately finalizes.
		assertNotNull(result.getFinalizedDuringRehydration());
		assertEquals(SessionState.FINALIZED, result.getFinalizedDuringRehydration().getState());
		assertEquals("no offline time may ever be added to active duration",
			durationBeforeRehydration, result.getFinalizedDuringRehydration().getAccumulatedActiveDurationMillis());
		assertNull(result.getEngine().getCurrentSession());
	}

	@Test
	public void rehydrate_persistedSuspendedInsideWindow_remainsResumable()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		Session persisted = engine.advanceTime(suspendedAt).getCurrent();

		// Rehydrated 10 minutes into the 30-minute window.
		SessionLifecycleEngine.RehydrationResult result =
			SessionLifecycleEngine.rehydrate(persisted, suspendedAt.plus(java.time.Duration.ofMinutes(10)));

		assertNull(result.getFinalizedDuringRehydration());
		assertEquals(SessionState.SUSPENDED, result.getEngine().getCurrentSession().getState());

		// And it genuinely still resumes on a matching heartbeat.
		LifecycleResult resumed = result.getEngine().onQualifyingActivity(
			GARGOYLES, suspendedAt.plus(java.time.Duration.ofMinutes(11)));
		assertEquals(SessionState.ACTIVE, resumed.getCurrent().getState());
	}

	@Test
	public void rehydrate_persistedSuspendedOutsideWindow_finalizes()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		Session persisted = engine.advanceTime(suspendedAt).getCurrent();
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);

		SessionLifecycleEngine.RehydrationResult result =
			SessionLifecycleEngine.rehydrate(persisted, expiry.plus(java.time.Duration.ofMinutes(1)));

		assertNotNull(result.getFinalizedDuringRehydration());
		assertEquals(SessionState.FINALIZED, result.getFinalizedDuringRehydration().getState());
		assertEquals("timeout-driven finalization must use the deterministic expiry, not the rehydration wall time",
			expiry.toString(), result.getFinalizedDuringRehydration().getFinalizedAt());
		assertNull(result.getEngine().getCurrentSession());
	}

	// --- 27: finalized session writes to correct sessions path ---

	/**
	 * NOTE ON WAITING FOR THE ASYNC CHAIN: persistFinalized() chains a
	 * second write() call from inside the first write's onWritten
	 * callback (see SessionPersistence javadoc), both submitted to
	 * LocalStateStore's single-thread executor. Calling shutdown()
	 * immediately after persistFinalized() returns is NOT safe here the
	 * way it is for a single, non-chained write (see
	 * LocalStateStoreTest's "no sleep — shutdown() itself waits"
	 * comment): shutdown() can flip the executor to SHUTDOWN before the
	 * first task's onWritten callback gets a chance to submit the
	 * second task, causing that nested submit() to be silently
	 * rejected. This mirrors LocalStateStore's own javadoc reasoning for
	 * why real shutdown-time chaining uses the blocking writeAndWait()
	 * instead — ordinary runtime chaining (the case here)
	 * does not go through shutdown() at all, so a short sleep here
	 * (not shutdown()) is what correctly waits for both queued tasks to
	 * finish without introducing a race the production code path never
	 * has.
	 */
	private static void awaitAsyncChain() throws InterruptedException
	{
		Thread.sleep(300);
	}

	@Test
	public void persistFinalized_writesToCorrectSessionsPath() throws Exception
	{
		Session finalized = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();
		finalized.setState(SessionState.FINALIZED);
		finalized.setFinalizedAt(T0.plusSeconds(10).toString());

		persistence.persistFinalized(TEST_ACCOUNT_HASH, finalized, null);
		awaitAsyncChain();

		File expected = TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, finalized.getSessionId());
		assertTrue("finalized record must exist at sessions/{sessionId}.json", expected.exists());

		Session reloaded = store.readIfExists(expected, Session.class);
		assertEquals(SessionState.FINALIZED, reloaded.getState());
		assertEquals(finalized.getSessionId(), reloaded.getSessionId());
	}

	@Test
	public void persistFinalized_clearsSessionStateOnlyAfterSuccess() throws Exception
	{
		Session finalized = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();
		finalized.setState(SessionState.FINALIZED);
		finalized.setFinalizedAt(T0.plusSeconds(10).toString());

		persistence.persistFinalized(TEST_ACCOUNT_HASH, finalized, null);
		awaitAsyncChain();

		Session currentAfter = store.readIfExists(
			TelemetryPaths.sessionStateFile(TEST_ACCOUNT_HASH), Session.class);
		assertNull("session_state.json must no longer represent the finalized session as current",
			currentAfter);
	}

	// --- 28: account isolation ---

	@Test
	public void accountDirectoriesRemainIsolated() throws Exception
	{
		Session accountASession = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();
		persistence.persistCurrent(TEST_ACCOUNT_HASH, accountASession);
		store.shutdown(); // single, non-chained write — safe to drain via shutdown(), see LocalStateStoreTest's own convention

		Session loadedForOther = persistence.loadCurrent(OTHER_ACCOUNT_HASH);
		Session loadedForTest = persistence.loadCurrent(TEST_ACCOUNT_HASH);

		assertNull("account B must never see account A's current session", loadedForOther);
		assertNotNull(loadedForTest);
		assertEquals(accountASession.getSessionId(), loadedForTest.getSessionId());
		assertFalse("session directories themselves must be different paths",
			TelemetryPaths.accountDir(TEST_ACCOUNT_HASH).equals(TelemetryPaths.accountDir(OTHER_ACCOUNT_HASH)));
	}

	// --- 29: persistence failure does not silently discard the only current session copy ---

	@Test
	public void persistFinalizedFailure_doesNotClearSessionState() throws Exception
	{
		// Seed session_state.json with a known-good SUSPENDED session first,
		// as if it were the last successfully persisted current state.
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Session suspended = engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT)).getCurrent();
		persistence.persistCurrent(TEST_ACCOUNT_HASH, suspended);
		store.shutdown(); // single, non-chained write — safe to drain via shutdown()
		store = new LocalStateStore(GSON);
		store.start();
		persistence = new SessionPersistence(store);

		// Simulate the finalized-record write itself failing: same technique
		// LocalStateStoreTest uses for its own failure test (a parent
		// directory that cannot be created because a same-named file
		// already occupies that path).
		File accountDir = TelemetryPaths.accountDir(TEST_ACCOUNT_HASH);
		File sessionsDirBlocker = new File(accountDir, "sessions");
		deleteRecursively(sessionsDirBlocker);
		sessionsDirBlocker.delete();
		java.nio.file.Files.write(sessionsDirBlocker.toPath(), "not a directory".getBytes());

		Session toFinalize = suspended;
		toFinalize.setState(SessionState.FINALIZED);
		toFinalize.setFinalizedAt(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plus(SessionLifecycleEngine.RESUME_WINDOW).toString());

		persistence.persistFinalized(TEST_ACCOUNT_HASH, toFinalize, null);
		store.shutdown();

		store = new LocalStateStore(GSON);
		store.start();
		Session stillCurrent = store.readIfExists(TelemetryPaths.sessionStateFile(TEST_ACCOUNT_HASH), Session.class);
		assertNotNull("a failed finalized-record write must never clear the only surviving copy of the session",
			stillCurrent);
		assertEquals(SessionState.SUSPENDED, stillCurrent.getState());

		sessionsDirBlocker.delete();
	}
}
