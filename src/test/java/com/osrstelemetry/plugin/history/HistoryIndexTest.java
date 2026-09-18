package com.osrstelemetry.plugin.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Real-file
 * coverage of HistoryIndex.refresh() -- discovery, the ~10 day display
 * window filter, newest-first ordering, never-re-parse-once-indexed,
 * and fail-open skipping of a malformed file.
 * Matches SessionPersistenceTest/SessionLoadoutBackwardCompatibilityTest's
 * own established real-file, no-Client style.
 */
public class HistoryIndexTest
{
	private static final long TEST_ACCOUNT_HASH = 888_777_222L;
	private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	private LocalStateStore store;
	private SessionPersistence persistence;

	@Before
	public void setUp()
	{
		deleteAccountDir(TEST_ACCOUNT_HASH);
		store = new LocalStateStore();
		store.start();
		persistence = new SessionPersistence(store);
	}

	@After
	public void tearDown()
	{
		store.shutdown();
		deleteAccountDir(TEST_ACCOUNT_HASH);
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

	private static void awaitAsyncChain() throws InterruptedException
	{
		Thread.sleep(300);
	}

	/** Writes a real finalized session file directly (bypassing the async store) so tests don't need to wait on it. */
	private static void writeSessionFileDirect(long accountHash, String sessionId, String finalizedAt) throws Exception
	{
		String json = "{"
			+ "\"sessionId\":\"" + sessionId + "\","
			+ "\"activityIdentity\":{\"activityType\":\"SLAYER\",\"activityKey\":\"gargoyles:catacombs\",\"displayName\":\"Gargoyles\"},"
			+ "\"state\":\"FINALIZED\","
			+ "\"startedAt\":\"2026-01-01T10:00:00Z\","
			+ "\"finalizedAt\":\"" + finalizedAt + "\","
			+ "\"accumulatedActiveDurationMillis\":60000,"
			+ "\"aggregates\":{\"xpGainedBySkill\":{},\"lootDrops\":[]}"
			+ "}";
		Files.write(TelemetryPaths.sessionFile(accountHash, sessionId).toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void refresh_discoversRealFinalizedSession() throws Exception
	{
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-1", "2026-01-15T11:00:00Z");

		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertEquals(1, result.size());
		assertEquals("session-1", result.get(0).getSessionId());
	}

	@Test
	public void refresh_emptyDirectory_returnsEmptyList()
	{
		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertTrue(result.isEmpty());
	}

	@Test
	public void refresh_ordersNewestFirst() throws Exception
	{
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-old", "2026-01-10T10:00:00Z");
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-new", "2026-01-15T10:00:00Z");
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-mid", "2026-01-12T10:00:00Z");

		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertEquals(3, result.size());
		assertEquals("session-new", result.get(0).getSessionId());
		assertEquals("session-mid", result.get(1).getSessionId());
		assertEquals("session-old", result.get(2).getSessionId());
	}

	@Test
	public void refresh_excludesSessionsOutsideDisplayWindow_butKeepsThemIndexedForCount() throws Exception
	{
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-recent", "2026-01-14T10:00:00Z");
		// 20 days before NOW -- outside a 10-day window.
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-ancient", "2025-12-26T10:00:00Z");

		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertEquals(1, result.size());
		assertEquals("session-recent", result.get(0).getSessionId());
		// The old file was never deleted, and IS still tracked internally
		// (display-only retention: old files are never deleted) --
		// it is simply excluded from the returned display snapshot.
		assertEquals(2, index.indexedCount());
		assertTrue(TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, "session-ancient").exists());
	}

	@Test
	public void refresh_malformedFile_skippedWithoutCrashingOtherEntries() throws Exception
	{
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-good", "2026-01-15T10:00:00Z");
		Files.write(TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, "session-corrupt").toPath(),
			"{ not valid json at all".getBytes(StandardCharsets.UTF_8));

		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertEquals("a malformed session file must be skipped, never crash the whole refresh",
			1, result.size());
		assertEquals("session-good", result.get(0).getSessionId());
	}

	@Test
	public void refresh_calledTwice_doesNotReParseAlreadyIndexedFiles() throws Exception
	{
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-1", "2026-01-15T10:00:00Z");

		HistoryIndex index = new HistoryIndex();
		index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);
		assertEquals(1, index.indexedCount());

		// Corrupt the already-indexed file on disk -- if refresh() were
		// re-parsing it, this second call would now fail to find it (or
		// throw); since it must never re-read an already-indexed file,
		// the cached entry is untouched and still present.
		Files.write(TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, "session-1").toPath(),
			"{ corrupted after first index".getBytes(StandardCharsets.UTF_8));

		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertEquals("an already-indexed session must never be re-parsed from disk",
			1, result.size());
		assertEquals("session-1", result.get(0).getSessionId());
	}

	@Test
	public void refresh_realSessionPersistenceWrite_discoveredCorrectly() throws Exception
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, NOW.minusSeconds(3600));
		Instant suspendedAt = NOW.minusSeconds(3600).plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		Session finalized = engine.advanceTime(expiry).getFinalized();

		persistence.persistFinalized(TEST_ACCOUNT_HASH, finalized, null);
		awaitAsyncChain();

		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), expiry.plusSeconds(60));

		assertEquals(1, result.size());
		assertEquals(finalized.getSessionId(), result.get(0).getSessionId());
	}

	@Test
	public void refresh_differentAccounts_neverCrossContaminate() throws Exception
	{
		long otherAccountHash = 888_777_111L;
		deleteAccountDir(otherAccountHash);
		try
		{
			writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-a", "2026-01-15T10:00:00Z");
			writeSessionFileDirect(otherAccountHash, "session-b", "2026-01-15T10:00:00Z");

			HistoryIndex index = new HistoryIndex();
			List<HistoryEntry> result = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

			assertEquals(1, result.size());
			assertEquals("session-a", result.get(0).getSessionId());
		}
		finally
		{
			deleteAccountDir(otherAccountHash);
		}
	}

	@Test
	public void refresh_skippedMalformedFile_retriedOnNextRefresh() throws Exception
	{
		File corruptFile = TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, "session-fixable");
		Files.write(corruptFile.toPath(), "{ not valid json".getBytes(StandardCharsets.UTF_8));

		HistoryIndex index = new HistoryIndex();
		List<HistoryEntry> first = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);
		assertTrue(first.isEmpty());

		// The file "heals" (e.g. a retried write completes) between polls.
		writeSessionFileDirect(TEST_ACCOUNT_HASH, "session-fixable", "2026-01-15T10:00:00Z");

		List<HistoryEntry> second = index.refresh(TEST_ACCOUNT_HASH, persistence, Duration.ofDays(10), NOW);

		assertEquals("a file that was skipped for being malformed must be retried, not permanently blacklisted",
			1, second.size());
	}
}
