package com.osrstelemetry.plugin.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run — this environment has no network access to
 * pull the RuneLite client jar this test compiles against (see
 * plugin/README.md). Run with `./gradlew test` locally as part of the
 * verification pass.
 *
 * Covers what's actually feasible to unit-test here: file-level
 * behavior of the ledger itself (unique IDs, one valid JSON object per
 * line, session ID stability within a run). It deliberately does not
 * try to test collector -> event routing, since that needs a live
 * Client/EventBus, which belongs in integration/manual testing instead
 * of being mocked into a false sense of coverage here.
 *
 * appendAndWait() TESTS: added
 * alongside the new synchronous/waitable append primitive introduced to
 * fix a bug where a fire-and-forget append() could be
 * dropped if EventLedger.shutdown() ran before its graceful drain
 * window got a chance to complete the queued write. These exercise
 * appendAndWait()'s actual success/rejection lifecycle end to end
 * (real file I/O, real executor), not just its return type.
 */
public class EventLedgerTest
{
	private static final long TEST_ACCOUNT_HASH = 999_000_111L;
	private static final Gson TEST_GSON = new Gson();

	private EventLedger ledger;

	@Before
	public void setUp() throws Exception
	{
		deleteAccountDir();
		ledger = new EventLedger(TEST_GSON);
		// EventLedger now requires
		// start() before append() will do anything (see its
		// lifecycle javadoc) — this test was calling append() against
		// a ledger that had never been started, which is not how the
		// plugin actually uses it (start() is called from
		// OsrsTelemetryPlugin.startUp()).
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
		File dir = TelemetryPaths.accountDir(TEST_ACCOUNT_HASH);
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
	public void appendedEventsHaveUniqueIds() throws Exception
	{
		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("WOODCUTTING", 89, 90, 5346332));
		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 70, 71, 737627));

		awaitQuiescence();

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals(2, lines.size());

		JsonObject first = new JsonParser().parse(lines.get(0)).getAsJsonObject();
		JsonObject second = new JsonParser().parse(lines.get(1)).getAsJsonObject();

		assertNotEquals(
			"Two distinct events must never share an eventId — this is the entire idempotency mechanism",
			first.get("eventId").getAsString(),
			second.get("eventId").getAsString()
		);
	}

	@Test
	public void everyLineIsExactlyOneValidJsonObjectWithRequiredEnvelopeFields() throws Exception
	{
		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(2343, "DESERT_TREASURE_II__THE_FALLEN_EMPIRE", "Desert Treasure II - The Fallen Empire"));

		awaitQuiescence();

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals(1, lines.size());

		JsonObject event = new JsonParser().parse(lines.get(0)).getAsJsonObject();
		assertTrue(event.has("eventId"));
		assertTrue(event.has("accountHash"));
		assertTrue(event.has("eventType"));
		assertTrue(event.has("occurredAt"));
		assertTrue(event.has("sessionId"));
		assertTrue(event.has("schemaVersion"));
		assertEquals("QUEST_COMPLETED", event.get("eventType").getAsString());
	}

	@Test
	public void sessionIdIsStableWithinARunAndChangesOnNewSession() throws Exception
	{
		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "QUEST_A", "Quest A"));
		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(2, "QUEST_B", "Quest B"));
		awaitQuiescence();

		ledger.startNewSession();
		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(3, "QUEST_C", "Quest C"));
		awaitQuiescence();

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		String sessionA = new JsonParser().parse(lines.get(0)).getAsJsonObject().get("sessionId").getAsString();
		String sessionB = new JsonParser().parse(lines.get(1)).getAsJsonObject().get("sessionId").getAsString();
		String sessionC = new JsonParser().parse(lines.get(2)).getAsJsonObject().get("sessionId").getAsString();

		assertEquals("Events within one session must share a sessionId", sessionA, sessionB);
		assertNotEquals("startNewSession() must actually produce a new session id", sessionB, sessionC);
	}

	@Test
	public void appendBeforeStartIsSafeNoOp() throws Exception
	{
		EventLedger neverStarted = new EventLedger(TEST_GSON);
		neverStarted.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("WOODCUTTING", 89, 90, 5346332));
		awaitQuiescence();

		assertTrue(
			"append() before start() must not produce a file",
			!TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).exists()
		);
	}

	@Test
	public void enableDisableEnableCycleProducesAWorkingLedgerEachTime() throws Exception
	{
		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 1, 2, 100));
		ledger.shutdown();

		// Re-start (simulates plugin disable -> re-enable within one
		// client process) — must not throw and must still work.
		ledger.start();
		ledger.append(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 2, 3, 300));
		awaitQuiescence();

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals("both the pre- and post-restart appends must have been written", 2, lines.size());
	}

	// =====================================================================
	// appendAndWait() — synchronous/waitable append primitive
	// (bank-snapshot shutdown-durability pass)
	// =====================================================================

	@Test
	public void appendAndWait_returnsTrueAndEventIsOnDiskBeforeItReturns() throws Exception
	{
		boolean success = ledger.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("MINING", 50, 51, 123456), 2000);

		assertTrue("appendAndWait() must report success for a normal append", success);

		// No awaitQuiescence() sleep here at all — the entire point of
		// appendAndWait() is that the event is already durable the
		// instant it returns.
		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals(1, lines.size());
		JsonObject event = new JsonParser().parse(lines.get(0)).getAsJsonObject();
		assertEquals("LEVEL_UP", event.get("eventType").getAsString());
	}

	@Test
	public void appendAndWait_buildsTheSameEnvelopeShapeAsAppend() throws Exception
	{
		ledger.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(9001, "A_QUEST", "A Quest"), 2000);

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals(1, lines.size());
		JsonObject event = new JsonParser().parse(lines.get(0)).getAsJsonObject();

		assertTrue(event.has("eventId"));
		assertTrue(event.has("accountHash"));
		assertTrue(event.has("eventType"));
		assertTrue(event.has("occurredAt"));
		assertTrue(event.has("sessionId"));
		assertTrue(event.has("schemaVersion"));
	}

	@Test
	public void appendAndWait_beforeStartReturnsFalseAndWritesNothing() throws Exception
	{
		EventLedger neverStarted = new EventLedger(TEST_GSON);
		boolean success = neverStarted.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("WOODCUTTING", 1, 2, 10), 2000);

		assertFalse("appendAndWait() before start() must report failure, not success", success);
		assertFalse(
			"appendAndWait() before start() must not produce a file",
			TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).exists()
		);
	}

	@Test
	public void appendAndWait_afterShutdownReturnsFalseAndWritesNothing() throws Exception
	{
		ledger.shutdown();

		boolean success = ledger.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 1, 2, 10), 2000);

		assertFalse("appendAndWait() must report failure once the ledger has been shut down", success);
	}

	@Test
	public void appendAndWait_ordersConsistentlyWithOrdinaryAppendOnTheSameLedger() throws Exception
	{
		// append() (async) followed immediately by appendAndWait() —
		// both go through the SAME single-writer executor, so
		// appendAndWait() returning must mean append()'s event landed
		// first (submission order == disk order for one writer thread).
		ledger.append(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "FIRST", "First"));
		boolean success = ledger.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(2, "SECOND", "Second"), 2000);

		assertTrue(success);

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals(2, lines.size());
		JsonObject firstPayload = new JsonParser().parse(lines.get(0)).getAsJsonObject().getAsJsonObject("payload");
		JsonObject secondPayload = new JsonParser().parse(lines.get(1)).getAsJsonObject().getAsJsonObject("payload");
		assertEquals("First", firstPayload.get("questName").getAsString());
		assertEquals("Second", secondPayload.get("questName").getAsString());
	}

	@Test
	public void appendAndWait_enableDisableEnableCycleStillWorksEachTime() throws Exception
	{
		assertTrue(ledger.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 1, 2, 100), 2000));

		ledger.shutdown();
		ledger.start();
		ledger.startNewSession();

		assertTrue(ledger.appendAndWait(
			TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("FISHING", 2, 3, 300), 2000));

		List<String> lines = Files.readAllLines(TelemetryPaths.eventsFile(TEST_ACCOUNT_HASH).toPath());
		assertEquals("both the pre- and post-restart appendAndWait() calls must have succeeded and persisted", 2, lines.size());
	}

	private void awaitQuiescence() throws InterruptedException
	{
		// The ledger's writer executor is a single background thread;
		// give it a moment to drain rather than asserting immediately
		// after a fire-and-forget append().
		TimeUnit.MILLISECONDS.sleep(200);
	}
}