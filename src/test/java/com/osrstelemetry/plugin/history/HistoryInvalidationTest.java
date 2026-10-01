package com.osrstelemetry.plugin.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * History is refreshed when a finalized session file actually reaches disk
 * -- not by polling -- and announces a changed list once.
 */
public class HistoryInvalidationTest
{
	private static final long ACCOUNT = 700_900_970L;
	private static final Gson GSON = new Gson();

	private Path testRoot;
	private LocalStateStore store;
	private HistoryCoordinator history;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("history-invalidation-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		history = new HistoryCoordinator(store);
		history.start();
	}

	@After
	public void tearDown() throws Exception
	{
		history.shutdown();
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private static Session finalized(String id)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'SKILLING','activityKey':'mining','displayName':'Mining'},"
			+ "'state':'FINALIZED','startedAt':'" + java.time.Instant.now().minusSeconds(600) + "','lastActiveAt':'" + java.time.Instant.now().minusSeconds(60)
			+ "','finalizedAt':'" + java.time.Instant.now() + "','accumulatedActiveDurationMillis':60000,'aggregates':{'xpGainedBySkill':{'MINING':100}}}")
			.replace('\'', '"'), Session.class);
	}

	@Test
	public void finalizedFileWritten_refreshesHistory_andNotifiesOnce() throws Exception
	{
		history.ensureAccountLoaded(ACCOUNT);
		Thread.sleep(200);
		AtomicInteger changes = new AtomicInteger();
		CountDownLatch changed = new CountDownLatch(1);
		history.setChangeListener(() ->
		{
			changes.incrementAndGet();
			changed.countDown();
		});

		for (int i = 0; i < 5; i++)
		{
			history.getRecentEntries(); // pure reads never scan the sessions directory
		}
		assertEquals(0, history.getRecentEntries().size());

		SessionPersistence persistence = new SessionPersistence(store);
		persistence.setFinalizedWriteListener(history::requestRefresh);
		persistence.persistAdditionalFinalized(ACCOUNT, finalized("s-1"));

		assertTrue("History refreshed once the file was written", changed.await(5, TimeUnit.SECONDS));
		assertEquals(1, history.getRecentEntries().size());
		assertEquals("s-1", history.getRecentEntries().get(0).getSessionId());
		Thread.sleep(200);
		assertEquals("one changed list, one notification", 1, changes.get());

		history.requestRefresh();
		Thread.sleep(300);
		assertEquals("an unchanged re-scan does not notify", 1, changes.get());
	}

	@Test
	public void refreshRequestedWhileOneRuns_isNotLost() throws Exception
	{
		history.ensureAccountLoaded(ACCOUNT);
		SessionPersistence persistence = new SessionPersistence(store);
		persistence.setFinalizedWriteListener(history::requestRefresh);
		for (int i = 0; i < 5; i++)
		{
			persistence.persistAdditionalFinalized(ACCOUNT, finalized("s-" + i));
		}
		long deadline = System.currentTimeMillis() + 5000;
		while (history.getRecentEntries().size() < 5 && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(50);
		}
		assertEquals(5, history.getRecentEntries().size());
	}

	@Test
	public void initialScanFlag_unknownUntilTheCurrentAccountHasBeenScanned() throws Exception
	{
		// Logged out / nothing loaded: "unknown", never "this player has no history".
		org.junit.Assert.assertFalse(history.isInitialScanComplete());
		history.ensureAccountLoaded(ACCOUNT);
		long deadline = System.currentTimeMillis() + 5000;
		while (!history.isInitialScanComplete() && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(20);
		}
		assertTrue("scanned once: an empty list now really means no sessions", history.isInitialScanComplete());
		assertTrue(history.getRecentEntries().isEmpty());
	}
}
