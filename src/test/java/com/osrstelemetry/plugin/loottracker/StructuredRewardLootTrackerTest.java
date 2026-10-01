package com.osrstelemetry.plugin.loottracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * STRUCTURED_REWARD as a first-class Loot Tracker input: canonical source
 * keys, alias canonicalization, unknown sources skipped, eventId dedup,
 * and the events.jsonl rebuild (mixed / legacy / NPC-only files).
 */
public class StructuredRewardLootTrackerTest
{
	private static final long ACCOUNT = 999_222_777L;
	private static final Gson GSON = new Gson();
	private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

	private Path testRoot;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("structured-reward-loot-tracker-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private static EventPayloads.StructuredReward reward(String activity, String sourceKey, String name, int itemId, String itemName, int qty)
	{
		return new EventPayloads.StructuredReward(activity, sourceKey, name,
			Arrays.asList(new EventPayloads.StructuredRewardItem(itemId, itemName, qty)));
	}

	private static String envelope(String eventId, EventType type, Instant at, Object payload)
	{
		return GSON.toJson(new com.osrstelemetry.plugin.events.TelemetryEvent(eventId, Long.toString(ACCOUNT), type, at.toString(), "s", payload));
	}

	private void writeEvents(String... lines) throws Exception
	{
		StringBuilder sb = new StringBuilder();
		for (String l : lines)
		{
			sb.append(l).append('\n');
		}
		TelemetryPaths.eventsFile(ACCOUNT).write(sb.toString().getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private LootTrackerCoordinator live()
	{
		LootTrackerCoordinator c = new LootTrackerCoordinator(GSON);
		c.start();
		c.ensureAccountLoaded(ACCOUNT);
		c.shutdown();
		c.start();
		return c;
	}

	@Test
	public void liveReward_recordedUnderItsCanonicalSource_withTheEnvelopeEventId()
	{
		LootTrackerCoordinator c = live();
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("barrows", "barrows", "Barrows", 4708, "Ahrim's hood", 1), T0, "ev-1");
		LootTrackerSource src = c.getIndex().snapshotSource("structured:barrows");
		assertEquals("Barrows", src.getSourceName());
		assertNull(src.getSourceId());
		assertTrue(src.isStructuredReward());
		assertEquals("ev-1", src.getRecords().get(0).getEventId());
		assertEquals(4708, src.getRecords().get(0).getItems().get(0).getItemId());
		// The same envelope delivered again (e.g. live + concurrent rebuild) is one record.
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("barrows", "barrows", "Barrows", 4708, "Ahrim's hood", 1), T0, "ev-1");
		assertEquals(1, c.getIndex().snapshotSource("structured:barrows").getRecords().size());
		c.shutdown();
	}

	@Test
	public void sameCausalSource_underDifferentNames_isOneSource()
	{
		LootTrackerCoordinator c = live();
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("tempoross", "tempoross_reward_pool", "Tempoross Reward Pool", 995, "Coins", 1), T0, "a");
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("tempoross", null, "Reward pool (Tempoross)", 995, "Coins", 2), T0.plusSeconds(1), "b");
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("guardians_of_the_rift", null, "Rewards Guardian", 565, "Blood rune", 3), T0, "c");
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("guardians_of_the_rift", "guardians_of_the_rift", "Guardians of the Rift", 565, "Blood rune", 4), T0, "d");
		List<LootTrackerSource> sources = c.getIndex().snapshotSources();
		assertEquals(2, sources.size());
		LootTrackerSource pool = c.getIndex().snapshotSource("structured:tempoross_reward_pool");
		assertEquals("Tempoross Reward Pool", pool.getSourceName());
		assertEquals(2, pool.getRecords().size());
		assertEquals("Guardians of the Rift", c.getIndex().snapshotSource("structured:guardians_of_the_rift").getSourceName());
		c.shutdown();
	}

	@Test
	public void unknownSources_andNpcLootNames_areNeverGuessed()
	{
		LootTrackerCoordinator c = live();
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward(null, null, "Brimstone Chest", 995, "Coins", 1), T0, "x");
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("tempoross", null, "Casket (Tempoross)", 995, "Coins", 1), T0, "y");
		c.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, reward("wintertodt", null, "Supply crate (Wintertodt)", 995, "Coins", 1), T0, "z");
		assertTrue(c.getIndex().snapshotSources().isEmpty());
		c.shutdown();
	}

	@Test
	public void npcKeysUnchanged_andNamespacesNeverCollide()
	{
		String npc = LootTrackerIndex.sourceKey("Barrows", null);
		assertNotEquals(npc, LootTrackerIndex.structuredSourceKey("barrows"));
		assertFalse(LootTrackerIndex.isStructuredSourceKey(npc));
		assertFalse("an NPC literally named like a structured key is still an NPC key",
			LootTrackerIndex.isStructuredSourceKey(LootTrackerIndex.sourceKey("structured:barrows", 1)));
		assertEquals("Zulrah" + '\u0000' + "2042", LootTrackerIndex.sourceKey("Zulrah", 2042));
	}

	@Test
	public void rebuild_includesStructuredRewards_legacyPayloads_retention_andDedup() throws Exception
	{
		writeEvents(
			envelope("n1", EventType.SERVER_NPC_LOOT, T0, new EventPayloads.ServerNpcLoot("Zulrah", 2042,
				Arrays.asList(new EventPayloads.ServerNpcLootItem(2444, "Zulrah's scales", 100)))),
			envelope("r1", EventType.STRUCTURED_REWARD, T0.plusSeconds(10), reward("barrows", "barrows", "Barrows", 4708, "Ahrim's hood", 1)),
			// Written by the earlier build: no sourceKey, RuneLite's event name.
			"{\"eventId\":\"r2\",\"accountHash\":\"1\",\"eventType\":\"STRUCTURED_REWARD\",\"occurredAt\":\"" + T0.plusSeconds(20)
				+ "\",\"payload\":{\"activityKey\":\"tempoross\",\"sourceName\":\"Reward pool (Tempoross)\",\"items\":[{\"itemId\":995,\"itemName\":\"Coins\",\"quantity\":5}]}}",
			envelope("r3", EventType.STRUCTURED_REWARD, T0.plusSeconds(30), reward(null, null, "Brimstone Chest", 995, "Coins", 9)),
			envelope("r0", EventType.STRUCTURED_REWARD, T0.minusSeconds(3600 * 24 * 400), reward("barrows", "barrows", "Barrows", 995, "Coins", 1)),
			envelope("r1", EventType.STRUCTURED_REWARD, T0.plusSeconds(10), reward("barrows", "barrows", "Barrows", 4708, "Ahrim's hood", 1)));
		LootTrackerIndex index = new LootTrackerIndex();
		int applied = new LootTrackerPersistence(GSON).rebuild(ACCOUNT, index, T0.minusSeconds(3600 * 24 * 365));
		assertEquals("npc + barrows + legacy tempoross; unknown skipped, too-old skipped, duplicate eventId skipped", 3, applied);
		assertEquals(1, index.snapshotSource("structured:barrows").getRecords().size());
		assertEquals("Tempoross Reward Pool", index.snapshotSource("structured:tempoross_reward_pool").getSourceName());
		assertEquals(1, index.snapshotSource(LootTrackerIndex.sourceKey("Zulrah", 2042)).getRecords().size());
	}

	@Test
	public void rebuild_npcOnlyFile_isExactlyAsBefore() throws Exception
	{
		writeEvents(
			envelope("n1", EventType.SERVER_NPC_LOOT, T0, new EventPayloads.ServerNpcLoot("Gargoyle", 412,
				Arrays.asList(new EventPayloads.ServerNpcLootItem(561, "Nature rune", 5)))),
			envelope("n2", EventType.SERVER_NPC_LOOT, T0.plusSeconds(5), new EventPayloads.ServerNpcLoot("Gargoyle", 412,
				Arrays.asList(new EventPayloads.ServerNpcLootItem(561, "Nature rune", 7)))));
		LootTrackerIndex index = new LootTrackerIndex();
		assertEquals(2, new LootTrackerPersistence(GSON).rebuild(ACCOUNT, index, null));
		assertEquals(1, index.snapshotSources().size());
		LootTrackerSource g = index.snapshotSources().get(0);
		assertEquals(LootTrackerIndex.sourceKey("Gargoyle", 412), g.getSourceKey());
		assertFalse(g.isStructuredReward());
		assertEquals(Integer.valueOf(412), g.getSourceId());
	}
}
