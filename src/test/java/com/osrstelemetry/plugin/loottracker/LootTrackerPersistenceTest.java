package com.osrstelemetry.plugin.loottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises
 * {@link LootTrackerPersistence#rebuild} against a REAL events.jsonl
 * file, written by the real {@link EventLedger} -- the same
 * "real file I/O, real executor" integration-style coverage
 * EventLedgerTest already established for this project's telemetry
 * ledger, extended here to the read-back/rebuild side nothing
 * previously exercised. Confirms: only SERVER_NPC_LOOT lines are
 * picked up (every other event type in the same file is correctly
 * ignored), eventId-based dedup survives a real rebuild, and the
 * retention cutoff excludes lines observed before it.
 */
public class LootTrackerPersistenceTest
{
	private static final long TEST_ACCOUNT_HASH = 999_222_444L;
	private static final Gson TEST_GSON = new Gson();

	private EventLedger ledger;
	private final LootTrackerPersistence persistence = new LootTrackerPersistence(TEST_GSON);

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

	private static EventPayloads.ServerNpcLoot loot(String sourceName, Integer sourceId, int itemId, String itemName, int qty)
	{
		return new EventPayloads.ServerNpcLoot(sourceName, sourceId,
			Arrays.asList(new EventPayloads.ServerNpcLootItem(itemId, itemName, qty)));
	}

	@Test
	public void rebuild_onlyPicksUpServerNpcLootLines_ignoringEveryOtherEventType() throws Exception
	{
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.LEVEL_UP,
			new EventPayloads.LevelUp("WOODCUTTING", 89, 90, 5346332), 2000);
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.SERVER_NPC_LOOT,
			loot("Zulrah", 2042, 2444, "Zulrah's scales", 100), 2000);
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.QUEST_COMPLETED,
			new EventPayloads.QuestCompleted(1, "QUEST_A", "Quest A"), 2000);

		LootTrackerIndex index = new LootTrackerIndex();
		int applied = persistence.rebuild(TEST_ACCOUNT_HASH, index, null);

		assertEquals(1, applied);
		List<LootTrackerSource> sources = index.snapshotSources();
		assertEquals(1, sources.size());
		assertEquals("Zulrah", sources.get(0).getSourceName());
	}

	@Test
	public void rebuild_missingEventsFile_yieldsEmptyIndex_noException()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		int applied = persistence.rebuild(TEST_ACCOUNT_HASH, index, null);

		assertEquals(0, applied);
		assertTrue(index.snapshotSources().isEmpty());
	}

	@Test
	public void rebuild_preservesMultipleRecordsForTheSameSource_asDistinctAtomicEvents() throws Exception
	{
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.SERVER_NPC_LOOT,
			loot("Gargoyle", 412, 561, "Nature rune", 5), 2000);
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.SERVER_NPC_LOOT,
			loot("Gargoyle", 412, 561, "Nature rune", 3), 2000);

		LootTrackerIndex index = new LootTrackerIndex();
		int applied = persistence.rebuild(TEST_ACCOUNT_HASH, index, null);

		assertEquals(2, applied);
		LootTrackerSource source = index.snapshotSources().get(0);
		assertEquals(2, source.getKillCount());
		assertEquals(8L, source.getItemTotals().get(0).getQuantity());
	}

	@Test
	public void rebuild_runTwiceAgainstTheSameIndex_isIdempotentViaEventIdDedup() throws Exception
	{
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.SERVER_NPC_LOOT,
			loot("Vorkath", 8061, 1, "Dragonbone", 1), 2000);

		LootTrackerIndex index = new LootTrackerIndex();
		int firstRun = persistence.rebuild(TEST_ACCOUNT_HASH, index, null);
		int secondRun = persistence.rebuild(TEST_ACCOUNT_HASH, index, null);

		assertEquals(1, firstRun);
		assertEquals(0, secondRun);
		assertEquals(1, index.snapshotSources().get(0).getKillCount());
	}

	@Test
	public void rebuild_retentionCutoff_excludesRecordsObservedBeforeIt() throws Exception
	{
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.SERVER_NPC_LOOT,
			loot("Zulrah", 2042, 2444, "Zulrah's scales", 100), 2000);

		// A cutoff strictly in the future excludes everything ever
		// appended so far -- confirms the retention bound actually
		// takes effect, without a fragile real-clock timing assumption.
		Instant farFuture = Instant.now().plusSeconds(3600);

		LootTrackerIndex index = new LootTrackerIndex();
		int applied = persistence.rebuild(TEST_ACCOUNT_HASH, index, farFuture);

		assertEquals(0, applied);
		assertTrue(index.snapshotSources().isEmpty());
	}

	@Test
	public void rebuild_retentionCutoff_includesRecordsObservedAtOrAfterIt() throws Exception
	{
		ledger.appendAndWait(TEST_ACCOUNT_HASH, EventType.SERVER_NPC_LOOT,
			loot("Zulrah", 2042, 2444, "Zulrah's scales", 100), 2000);

		Instant longAgo = Instant.now().minusSeconds(3600);

		LootTrackerIndex index = new LootTrackerIndex();
		int applied = persistence.rebuild(TEST_ACCOUNT_HASH, index, longAgo);

		assertEquals(1, applied);
	}
}
