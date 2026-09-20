package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.collectors.LoadoutArchive;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.io.File;
import java.time.Instant;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-
 * end coverage of the REAL production wiring path -- the two-arg
 * SessionPersistence(store, loadoutArchive) constructor
 * SessionRuntimeCoordinator now uses -- as opposed to
 * LoadoutResolverTest (the resolution ladder in isolation) or
 * SessionLoadoutBackwardCompatibilityTest (an explicitly
 * pre-attached loadout with a null/no-op archive). Proves:
 *   1. a brand-new session's FIRST persistCurrent() call resolves and
 *      attaches a real starting loadout from live archive data;
 *   2. a second persistCurrent() call for the SAME session object does
 *      NOT re-resolve (identity refinement/repeated
 *      persistence must never replace an already-attached starting
 *      loadout);
 *   3. persistFinalized() resolves and attaches an ending loadout;
 *   4. an account with no archive data at all still finalizes cleanly
 *      with an explicit UNAVAILABLE loadout, never blocking
 *      persistence.
 *
 * Real LocalStateStore/TelemetryPaths, matching
 * SessionPersistenceTest/SessionLoadoutBackwardCompatibilityTest's own
 * established real-file style.
 *
 * Archive
 * observations that exercise a PRE_START starting-loadout resolution
 * are recorded far enough before T0 to land at-or-before the
 * resolver's target instant (T0 - 30s -- see
 * LoadoutResolver.STARTING_LOADOUT_TARGET_OFFSET), not merely before
 * T0 itself.
 */
public class SessionPersistenceLoadoutResolutionTest
{
	private static final long TEST_ACCOUNT_HASH = 888_777_333L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");
	private static final Gson GSON = new Gson();

	private LocalStateStore store;
	private LoadoutArchive archive;
	private SessionPersistence persistence;

	@Before
	public void setUp()
	{
		deleteAccountDir(TEST_ACCOUNT_HASH);
		store = new LocalStateStore(GSON);
		store.start();
		archive = new LoadoutArchive();
		persistence = new SessionPersistence(store, archive);
	}

	@After
	public void tearDown()
	{
		store.shutdown();
		deleteAccountDir(TEST_ACCOUNT_HASH);
	}

	private static void deleteAccountDir(long accountHash)
	{
		deleteRecursively(TestFilepaths.file(TelemetryPaths.accountDir(accountHash)));
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

	private static void awaitAsyncChain() throws InterruptedException
	{
		Thread.sleep(300);
	}

	@Test
	public void firstPersistCurrent_resolvesAndAttachesRealStartingLoadout()
	{
		archive.record(T0.minusSeconds(45),
			Collections.singletonList(new StorageState.StorageItem(0, 995, "Coins", 500, null)),
			Collections.emptyList());

		Session session = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();
		assertNull("a freshly-created session must not already have a loadout", session.getStartingLoadout());

		persistence.persistCurrent(TEST_ACCOUNT_HASH, session);

		assertNotNull("persistCurrent() must resolve and attach a starting loadout for a brand-new session",
			session.getStartingLoadout());
		assertEquals(LoadoutProvenance.PRE_START, session.getStartingLoadout().getProvenance());
		assertEquals(995, session.getStartingLoadout().getInventory().get(0).getItemId());
	}

	@Test
	public void secondPersistCurrent_neverReplacesAlreadyAttachedStartingLoadout()
	{
		archive.record(T0.minusSeconds(45),
			Collections.singletonList(new StorageState.StorageItem(0, 995, "Coins", 500, null)),
			Collections.emptyList());

		Session session = new SessionLifecycleEngine().onQualifyingActivity(GARGOYLES, T0).getCurrent();
		persistence.persistCurrent(TEST_ACCOUNT_HASH, session);
		LoadoutSnapshot firstResolved = session.getStartingLoadout();

		// Simulate a later observation arriving (e.g. the player picked
		// something up) that must NOT retroactively become this
		// session's "starting" loadout.
		archive.record(T0.plusSeconds(5),
			Collections.singletonList(new StorageState.StorageItem(0, 1234, "Different item", 1, null)),
			Collections.emptyList());
		persistence.persistCurrent(TEST_ACCOUNT_HASH, session);

		assertEquals("a second persistCurrent() call for the same session object must not re-resolve the starting loadout",
			firstResolved, session.getStartingLoadout());
	}

	@Test
	public void persistFinalized_resolvesAndAttachesEndingLoadout() throws Exception
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		Session finalized = engine.advanceTime(expiry).getFinalized();

		archive.record(expiry.minusSeconds(5),
			Collections.singletonList(new StorageState.StorageItem(0, 1359, "Swordfish", 3, null)),
			Collections.emptyList());

		persistence.persistFinalized(TEST_ACCOUNT_HASH, finalized, null);
		awaitAsyncChain();

		Session reloaded = persistence.loadFinalized(TEST_ACCOUNT_HASH, finalized.getSessionId());
		assertNotNull(reloaded);
		assertNotNull("persistFinalized() must resolve and attach an ending loadout", reloaded.getEndingLoadout());
		assertEquals(1359, reloaded.getEndingLoadout().getInventory().get(0).getItemId());
	}

	@Test
	public void noArchiveData_finalizesCleanlyWithExplicitUnavailableLoadout_neverBlocks() throws Exception
	{
		// archive is real but has never recorded anything for this account.
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		Session finalized = engine.advanceTime(expiry).getFinalized();

		persistence.persistFinalized(TEST_ACCOUNT_HASH, finalized, null);
		awaitAsyncChain();

		Session reloaded = persistence.loadFinalized(TEST_ACCOUNT_HASH, finalized.getSessionId());
		assertNotNull("finalization must succeed even with zero archive data", reloaded);
		assertEquals(SessionState.FINALIZED, reloaded.getState());
		assertNotNull(reloaded.getStartingLoadout());
		assertEquals(LoadoutProvenance.UNAVAILABLE, reloaded.getStartingLoadout().getProvenance());
		assertNotNull(reloaded.getEndingLoadout());
		assertEquals(LoadoutProvenance.UNAVAILABLE, reloaded.getEndingLoadout().getProvenance());
	}
}
