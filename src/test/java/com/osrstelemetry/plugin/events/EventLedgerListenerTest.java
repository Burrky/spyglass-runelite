package com.osrstelemetry.plugin.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project.
 *
 * Covers TelemetryEventListener registration/notification directly
 * against the real EventLedger -- no mocking. This is also test letter
 * S from the wiring task's own list ("malformed/failed session
 * persistence -> telemetry ledger remains unaffected"), verified here
 * at its true source: a listener that throws must never affect the
 * durable write, since EventLedger is the one place that guarantee is
 * actually implemented.
 */
public class EventLedgerListenerTest
{
	private static final long TEST_ACCOUNT_HASH = 999_222_333L;
	private static final Gson TEST_GSON = new Gson();

	private EventLedger ledger;

	@Before
	public void setUp() throws Exception
	{
		deleteAccountDir();
		ledger = new EventLedger(TEST_GSON);
		ledger.start();
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.shutdown();
		deleteAccountDir();
	}

	private void deleteAccountDir() throws Exception
	{
		File dir = TestFilepaths.file(TelemetryPaths.accountDir(TEST_ACCOUNT_HASH));
		if (dir.exists())
		{
			File[] files = dir.listFiles();
			if (files != null)
			{
				for (File f : files)
				{
					Files.deleteIfExists(f.toPath());
				}
			}
			Files.deleteIfExists(dir.toPath());
		}
	}

	@Test
	public void addedListener_isNotifiedSynchronouslyOnAppend()
	{
		List<EventType> seen = new ArrayList<>();
		ledger.addListener((accountHash, type, payload, observedAt) -> seen.add(type));

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));

		// No sleep/await at all -- notifyListeners() runs synchronously on
		// the calling thread, before append() even returns (see
		// EventLedger's own javadoc); this must already be true the
		// instant append() returns, unlike the durable disk write below.
		assertEquals(1, seen.size());
		assertEquals(EventType.QUEST_COMPLETED, seen.get(0));
	}

	@Test
	public void addedListener_isNotifiedOnAppendAndWaitToo()
	{
		List<EventType> seen = new ArrayList<>();
		ledger.addListener((accountHash, type, payload, observedAt) -> seen.add(type));

		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.BOSS_KILL,
			new EventPayloads.BossKill("zulrah", "Zulrah", 1, "AUTHORITATIVE"), 2000);

		assertEquals(1, seen.size());
	}

	@Test
	public void removedListener_receivesNoFurtherEvents()
	{
		AtomicInteger count = new AtomicInteger();
		TelemetryEventListener listener = (accountHash, type, payload, observedAt) -> count.incrementAndGet();

		ledger.addListener(listener);
		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP, new EventPayloads.LevelUp("FISHING", 1, 2, 10));
		assertEquals(1, count.get());

		ledger.removeListener(listener);
		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP, new EventPayloads.LevelUp("FISHING", 2, 3, 20));
		assertEquals("a removed listener must never be notified again", 1, count.get());
	}

	@Test
	public void reAddingAfterRemove_stillReceivesExactlyOneNotificationPerEvent()
	{
		// Regression coverage for the exact class of bug this project's
		// own client-thread lifecycle audit already found for EventBus
		// registration: addListener() on every re-enable without a
		// matching removeListener() on disable would silently accumulate
		// duplicate listeners.
		AtomicInteger count = new AtomicInteger();
		TelemetryEventListener listener = (accountHash, type, payload, observedAt) -> count.incrementAndGet();

		ledger.addListener(listener);
		ledger.removeListener(listener);
		ledger.addListener(listener);

		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP, new EventPayloads.LevelUp("FISHING", 1, 2, 10));

		assertEquals("re-adding after a proper removal must never result in double notification", 1, count.get());
	}

	@Test
	public void listenerThatThrows_neverPreventsOrCorruptsTheDurableWrite() throws Exception
	{
		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new RuntimeException("simulated session-persistence failure");
		});

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));

		awaitQuiescence();

		List<String> lines = Files.readAllLines(TestFilepaths.path(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH)));
		assertEquals("the durable telemetry event must be written regardless of a failing listener", 1, lines.size());
	}

	@Test
	public void oneListenerThrowing_neverPreventsOtherListenersFromRunning()
	{
		AtomicInteger secondListenerCount = new AtomicInteger();

		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new RuntimeException("simulated failure in the first listener");
		});
		ledger.addListener((accountHash, type, payload, observedAt) -> secondListenerCount.incrementAndGet());

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));

		assertEquals(1, secondListenerCount.get());
	}

	@Test
	public void listenerReceivesTheSameAccountHashTypeAndPayloadPassedToAppend()
	{
		List<Object[]> seen = new ArrayList<>();
		ledger.addListener((accountHash, type, payload, observedAt) -> seen.add(new Object[] {accountHash, type, payload, observedAt}));

		EventPayloads.QuestCompleted payload = new EventPayloads.QuestCompleted(42, "A_QUEST", "A Quest");
		Instant before = Instant.now();
		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED, payload);
		Instant after = Instant.now();

		assertEquals(1, seen.size());
		Object[] call = seen.get(0);
		assertEquals(TEST_ACCOUNT_HASH, call[0]);
		assertEquals(EventType.QUEST_COMPLETED, call[1]);
		assertTrue("the exact same payload instance must be passed through, never a copy", call[2] == payload);
		Instant observedAt = (Instant) call[3];
		assertTrue(!observedAt.isBefore(before) && !observedAt.isAfter(after));
	}

	@Test
	public void appendBeforeStart_neverNotifiesListeners()
	{
		EventLedger neverStarted = new EventLedger(TEST_GSON);
		AtomicInteger count = new AtomicInteger();
		neverStarted.addListener((accountHash, type, payload, observedAt) -> count.incrementAndGet());

		neverStarted.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP, new EventPayloads.LevelUp("FISHING", 1, 2, 10));

		assertEquals(
			"an event that was never durably queued must never be reported to a session listener either",
			0, count.get());
	}

	@Test
	public void appendAfterShutdown_neverNotifiesListeners()
	{
		// ADVERSARIAL HARDENING PASS, item 13.B -- this is the structural
		// proof of the enforced order, not just an exception-catching
		// test. EventLedger.append() now submits the durable write FIRST
		// and only calls notifyListeners() if that submission succeeds
		// (see EventLedger's own ordering comment). Once shutdown() has
		// been called, executor.submit() throws RejectedExecutionException
		// and append() returns before notifyListeners() is ever reached.
		// If the old (buggy) order were still in place -- notify first,
		// submit second -- this listener WOULD still fire even though the
		// write itself is rejected, since nothing about calling the
		// listener depends on the executor at all. This test can only
		// pass under the fixed order.
		AtomicInteger count = new AtomicInteger();
		ledger.addListener((accountHash, type, payload, observedAt) -> count.incrementAndGet());

		ledger.shutdown();

		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP, new EventPayloads.LevelUp("FISHING", 1, 2, 10));

		assertEquals(
			"a durable write that was rejected (executor shut down) must never reach a session listener either -- "
				+ "proves submit-then-notify ordering, not just that exceptions are caught",
			0, count.get());
	}

	@Test
	public void appendAndWaitAfterShutdown_neverNotifiesListenersAndReturnsFalse()
	{
		// Same structural proof as above, for appendAndWait(): the
		// RejectedExecutionException path returns false immediately,
		// before notifyListeners() is reached.
		AtomicInteger count = new AtomicInteger();
		ledger.addListener((accountHash, type, payload, observedAt) -> count.incrementAndGet());

		ledger.shutdown();

		boolean result = ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 1, 2, 10), 2000);

		assertEquals("a rejected durable write must report failure to the caller", false, result);
		assertEquals(
			"a durable write that was rejected (executor shut down) must never reach a session listener either",
			0, count.get());
	}

	@Test
	public void listenerObservesEventBeforeAppendReturns_confirmingSynchronousNotificationAfterAcceptance()
	{
		// Confirms the OTHER half of the contract: when durable acceptance
		// DOES succeed (the normal case), notification is still
		// synchronous on the calling thread -- a session listener does
		// not need to poll or wait for a separate async callback. This
		// combined with the two tests above pins down the full order:
		// build envelope -> durable accept (submit) -> notify, all before
		// append()/appendAndWait() return to the caller.
		List<EventType> seenBeforeReturnCheck = new ArrayList<>();
		ledger.addListener((accountHash, type, payload, observedAt) -> seenBeforeReturnCheck.add(type));

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(7, "B_QUEST", "B Quest"));

		assertEquals("notification must have already happened by the time append() returns",
			1, seenBeforeReturnCheck.size());
	}

	@Test
	public void listenerThatThrowsAssertionError_neverPropagatesOutOfAppend() throws Exception
	{
		// TARGETED CORRECTNESS AUDIT, item 2: AssertionError extends
		// Error, NOT RuntimeException -- the original bug description
		// specifically called out that the old `catch (RuntimeException
		// e)` would NOT have caught this. This project has hit real
		// AssertionErrors during RuneLite development (per the audit
		// request), so this is not a theoretical case. If
		// notifyListeners() only caught RuntimeException, this
		// append() call would throw AssertionError right back out to
		// US -- the simulated collector -- which is exactly the failure
		// this test proves does not happen: append() must return
		// normally, and the durable write must still land on disk.
		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new AssertionError("simulated session-runtime bug, not a RuntimeException");
		});

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));

		awaitQuiescence();

		List<String> lines = Files.readAllLines(TestFilepaths.path(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH)));
		assertEquals("an AssertionError thrown by a listener must never prevent the durable write either",
			1, lines.size());
	}

	@Test
	public void listenerThatThrowsAssertionError_neverPropagatesOutOfAppendAndWaitAndDurableResultUnaffected()
	{
		// Same proof as above, for appendAndWait() specifically: the
		// listener's AssertionError must not propagate to the collector
		// AND must not change the durable result the collector is
		// waiting on (the future's own outcome was already decided in
		// step (1), before notifyListeners() ever ran -- see
		// EventLedger's own updated ordering javadoc).
		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new AssertionError("simulated session-runtime bug in a shutdown-critical caller's listener");
		});

		boolean result = ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.BOSS_KILL,
			new EventPayloads.BossKill("zulrah", "Zulrah", 1, "AUTHORITATIVE"), 2000);

		assertEquals("a listener's AssertionError must never affect the durable write's own success result",
			true, result);
	}

	@Test
	public void oneListenerThrowingAssertionError_neverPreventsOtherListenersFromRunning()
	{
		AtomicInteger secondListenerCount = new AtomicInteger();

		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new AssertionError("simulated bug in the first listener");
		});
		ledger.addListener((accountHash, type, payload, observedAt) -> secondListenerCount.incrementAndGet());

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));

		assertEquals(1, secondListenerCount.get());
	}

	@Test
	public void laterListenerStillRunsAfterAnEarlierOneThrowsRuntimeException()
	{
		// FINAL CORRECTION PASS: explicit "later listeners still run"
		// coverage for the RuntimeException case, matching the same
		// already-existing coverage for AssertionError below.
		AtomicInteger secondListenerCount = new AtomicInteger();

		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new RuntimeException("simulated failure in the first listener");
		});
		ledger.addListener((accountHash, type, payload, observedAt) -> secondListenerCount.incrementAndGet());

		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));

		assertEquals(1, secondListenerCount.get());
	}

	@Test
	public void listenerThatThrowsAFatalVirtualMachineError_isNotSwallowed()
	{
		// FINAL CORRECTION PASS: proves the narrowed catch boundary.
		// notifyListeners() now deliberately re-throws VirtualMachineError/
		// ThreadDeath rather than logging-and-swallowing them (see
		// EventLedger's own updated javadoc for the reasoning). A real
		// OutOfMemoryError/StackOverflowError can't be safely triggered
		// on demand in a test, so this uses a minimal anonymous
        // VirtualMachineError subclass -- a real, genuine instance of the
		// exact type the boundary is defined against, without actually
		// destabilizing the test JVM -- as the representative fatal case.
		ledger.addListener((accountHash, type, payload, observedAt) ->
		{
			throw new VirtualMachineError("simulated fatal JVM condition") {};
		});

		boolean propagated;
		try
		{
			ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
				new EventPayloads.QuestCompleted(1, "A_QUEST", "A Quest"));
			propagated = false;
		}
		catch (VirtualMachineError expected)
		{
			propagated = true;
		}

		assertTrue("a genuine VirtualMachineError thrown by a listener must propagate out of append(), "
			+ "not be logged and silently suppressed", propagated);
	}

	private void awaitQuiescence() throws InterruptedException
	{
		TimeUnit.MILLISECONDS.sleep(200);
	}
}
