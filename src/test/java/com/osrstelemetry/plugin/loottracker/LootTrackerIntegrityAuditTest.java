package com.osrstelemetry.plugin.loottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integrity audit (Loot Tracker data side): recency independent of apply
 * order, account isolation of live events, and preference write ordering.
 */
public class LootTrackerIntegrityAuditTest
{
	private static final Instant T = Instant.parse("2026-09-20T12:00:00Z");
	private static final long ACCOUNT_A = 11L;
	private static final long ACCOUNT_B = 22L;

	private Path testRoot;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("loot-integrity-audit-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private static LootTrackerRecord record(String id, Instant at)
	{
		return new LootTrackerRecord(id, "Zulrah", 2042, at, Collections.singletonList(new LootTrackerItem(995, "Coins", 100)));
	}

	@Test
	public void liveRecordAppliedBeforeTheRebuildsOlderRecords_keepsTrueFirstAndLastObserved()
	{
		LootTrackerIndex index = new LootTrackerIndex();
		index.apply(record("live", T));                         // live drop lands first
		index.apply(record("old1", T.minusSeconds(5 * 86400))); // rebuild then appends history
		index.apply(record("old2", T.minusSeconds(86400)));
		LootTrackerSource source = index.snapshotSources().get(0);
		assertEquals(T.minusSeconds(5 * 86400), source.getFirstObservedAt());
		assertEquals(T, source.getLastObservedAt());
		assertEquals(T, source.filteredAfter(T.minusSeconds(2 * 86400)).getLastObservedAt());
	}

	@Test
	public void liveEventForAnotherAccount_neverLandsInTheLoadedAccountsIndex()
	{
		LootTrackerCoordinator coordinator = new LootTrackerCoordinator(new Gson());
		coordinator.start();
		coordinator.ensureAccountLoaded(ACCOUNT_A);
		coordinator.shutdown(); // drain the (empty) rebuild
		coordinator.start();
		EventPayloads.ServerNpcLoot loot = new EventPayloads.ServerNpcLoot("Zulrah", 2042,
			Collections.singletonList(new EventPayloads.ServerNpcLootItem(995, "Coins", 100)));

		coordinator.onEvent(ACCOUNT_B, EventType.SERVER_NPC_LOOT, loot, T, "b-1");
		assertTrue(coordinator.getIndex().snapshotSources().isEmpty());

		coordinator.onEvent(ACCOUNT_A, EventType.SERVER_NPC_LOOT, loot, T, "a-1");
		assertEquals(1, coordinator.getIndex().snapshotSources().size());
		coordinator.shutdown();
	}

	@Test
	public void openAllThenCollapseAll_finalPersistedStateIsTheLastAction() throws Exception
	{
		List<String> keys = Arrays.asList("k1", "k2", "k3");
		LocalStateStore store = new LocalStateStore(new Gson());
		store.start();
		LootTrackerPreferences prefs = new LootTrackerPreferences(store);
		prefs.loadForAccount(ACCOUNT_A);
		for (int i = 0; i < 25; i++)
		{
			prefs.setCollapsed(keys, false); // Open All
			prefs.setCollapsed(keys, true);  // Collapse All
		}
		store.shutdown();

		LocalStateStore reopened = new LocalStateStore(new Gson());
		reopened.start();
		LootTrackerPreferences reloaded = new LootTrackerPreferences(reopened);
		reloaded.loadForAccount(ACCOUNT_A);
		for (String key : keys)
		{
			assertTrue(key, reloaded.isCollapsed(key));
		}
		reloaded.setCollapsed(keys, false);
		reopened.shutdown();

		LocalStateStore third = new LocalStateStore(new Gson());
		third.start();
		LootTrackerPreferences again = new LootTrackerPreferences(third);
		again.loadForAccount(ACCOUNT_A);
		assertFalse(again.isCollapsed("k1"));
		third.shutdown();
	}
}
