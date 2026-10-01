package com.osrstelemetry.plugin.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integrity audit (History): a scan started for one account can never
 * publish into, or be indexed under, another account; no refresh request is
 * lost; a rejected submit never wedges refreshes; newest-first is by instant.
 */
public class HistoryAuditRaceTest
{
	private static final long ACCOUNT_A = 700_900_111L;
	private static final long ACCOUNT_B = 700_900_222L;
	private static final Gson GSON = new Gson();

	/** Runs submitted tasks only when the test says so. */
	private static final class ManualExecutor extends AbstractExecutorService
	{
		final List<Runnable> tasks = new ArrayList<>();
		boolean rejecting;

		@Override
		public void execute(Runnable command)
		{
			if (rejecting)
			{
				throw new RejectedExecutionException("rejecting");
			}
			tasks.add(command);
		}

		void runAll()
		{
			while (!tasks.isEmpty())
			{
				tasks.remove(0).run();
			}
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
		{
			return true;
		}
	}

	private Path testRoot;
	private LocalStateStore store;
	private HistoryCoordinator history;
	private final ManualExecutor executor = new ManualExecutor();

	static Session finalized(String id, Instant finalizedAt)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'SKILLING','activityKey':'mining','displayName':'Mining'},"
			+ "'state':'FINALIZED','startedAt':'" + finalizedAt.minusSeconds(600) + "','lastActiveAt':'" + finalizedAt.minusSeconds(60)
			+ "','finalizedAt':'" + finalizedAt + "','accumulatedActiveDurationMillis':60000,'aggregates':{'xpGainedBySkill':{'MINING':100}}}")
			.replace('\'', '"'), Session.class);
	}

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("history-audit-race-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		SessionPersistence persistence = new SessionPersistence(store);
		Instant now = Instant.now();
		persistence.persistAdditionalFinalized(ACCOUNT_A, finalized("a-1", now.minusSeconds(300)));
		persistence.persistAdditionalFinalized(ACCOUNT_A, finalized("a-2", now.minusSeconds(200)));
		persistence.persistAdditionalFinalized(ACCOUNT_B, finalized("b-1", now.minusSeconds(100)));
		store.shutdown(); // drain: the files exist before any scan
		store.start();

		history = new HistoryCoordinator(store);
		java.lang.reflect.Field f = HistoryCoordinator.class.getDeclaredField("executor");
		f.setAccessible(true);
		f.set(history, executor);
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private List<String> ids()
	{
		List<String> out = new ArrayList<>();
		for (HistoryEntry e : history.getRecentEntries())
		{
			out.add(e.getSessionId());
		}
		return out;
	}

	@Test
	public void accountSwitchBeforeTheQueuedScanRuns_showsAndIndexesOnlyTheNewAccount()
	{
		history.ensureAccountLoaded(ACCOUNT_A); // scan queued (worker busy from here)
		history.ensureAccountLoaded(ACCOUNT_B); // switch while it is in flight
		executor.runAll();
		assertEquals(Collections.singletonList("b-1"), ids());

		// B's index was never polluted with A's sessions.
		history.requestRefresh();
		executor.runAll();
		assertEquals(Collections.singletonList("b-1"), ids());
	}

	@Test
	public void accountSwitchDuringARunningScan_neverPublishesTheOldAccountIntoTheNewOne()
	{
		history.ensureAccountLoaded(ACCOUNT_A);
		AtomicInteger notifications = new AtomicInteger();
		history.setChangeListener(() ->
		{
			if (notifications.incrementAndGet() == 1)
			{
				// The first publication (account A) triggers the switch while
				// the worker is still running.
				history.ensureAccountLoaded(ACCOUNT_B);
			}
		});
		executor.runAll();
		assertEquals(Collections.singletonList("b-1"), ids());
		history.requestRefresh();
		executor.runAll();
		assertEquals(Collections.singletonList("b-1"), ids());
	}

	@Test
	public void manyRequestsWhileBusy_areServedByOneMoreScan_noneLost()
	{
		history.ensureAccountLoaded(ACCOUNT_A);
		for (int i = 0; i < 10; i++)
		{
			history.requestRefresh();
		}
		assertEquals("one worker at a time", 1, executor.tasks.size());
		executor.runAll();
		assertEquals(2, ids().size());
		assertEquals("worker released", 0, executor.tasks.size());
		history.requestRefresh();
		assertEquals("a later request starts a new scan", 1, executor.tasks.size());
		executor.runAll();
	}

	@Test
	public void rejectedSubmit_neverWedgesLaterRefreshes()
	{
		executor.rejecting = true;
		history.ensureAccountLoaded(ACCOUNT_A); // must not throw into login handling
		executor.rejecting = false;
		history.requestRefresh();
		assertEquals("a later request still starts a scan", 1, executor.tasks.size());
		executor.runAll();
		assertEquals(2, ids().size());
	}

	@Test
	public void rejectedDetailLoad_answersNull()
	{
		history.ensureAccountLoaded(ACCOUNT_A);
		executor.runAll();
		executor.rejecting = true;
		AtomicReference<Object> result = new AtomicReference<>("unset");
		history.loadDetail("a-1", result::set);
		assertNull(result.get());
	}

	@Test
	public void requestsBeforeAnyAccountIsLoaded_scanNothing()
	{
		history.requestRefresh();
		assertTrue(executor.tasks.isEmpty());
	}

	@Test
	public void newestFirst_isByInstant_evenWithinOneSecond()
	{
		Instant second = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
		HistoryIndex index = new HistoryIndex();
		SessionPersistence persistence = new SessionPersistence(store);
		persistence.persistAdditionalFinalized(ACCOUNT_B + 1, finalized("whole", second));
		persistence.persistAdditionalFinalized(ACCOUNT_B + 1, finalized("later", second.plusMillis(500)));
		store.shutdown();
		store.start();
		List<HistoryEntry> entries = index.refresh(ACCOUNT_B + 1, persistence, Duration.ofDays(10), Instant.now());
		assertEquals("later", entries.get(0).getSessionId());
		assertEquals("whole", entries.get(1).getSessionId());
	}
}
