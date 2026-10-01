package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.loottracker.LootTrackerCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerIndex;
import com.osrstelemetry.plugin.loottracker.LootTrackerPersistence;
import com.osrstelemetry.plugin.loottracker.LootTrackerRecord;
import com.osrstelemetry.plugin.loottracker.LootTrackerSource;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * SMART SLAYER OFF-TASK LOOT -- PIPELINE SEPARATION.
 *
 * Wires the REAL EventLedger to the REAL SessionRuntimeCoordinator and the
 * REAL LootTrackerCoordinator exactly as OsrsTelemetryPlugin.startUp()
 * does (two independent addListener() registrations), then drives
 * SERVER_NPC_LOOT through EventLedger.appendAndWait() -- the same entry
 * point ServerNpcLootCollector uses. Proves the off-task KNOWN_MISMATCH
 * rule is a Current Session ownership decision ONLY:
 *
 *   - events.jsonl keeps the canonical event (written before fan-out),
 *   - the Loot Tracker records it once under its real source NPC, live
 *     and on rebuild,
 *   - the Slayer session / current assignment bucket never own it -- also
 *     when it shares a tick with combat XP or task progress (the
 *     same-tick promotion leak fixed alongside this test).
 */
public class SlayerOffTaskLootPipelineSeparationTest
{
	private static final long ACCOUNT = 700_900_500L;
	private static final Gson GSON = new Gson();
	private static final String TUROTH = "Turoth";
	private static final String TUROTH_LOC = "Fremennik Slayer Dungeon";
	private static final int COINS = 995;

	private Path testRoot;
	private LocalStateStore store;
	private EventLedger ledger;
	private SessionRuntimeCoordinator session;
	private LootTrackerCoordinator lootTracker;
	private long tick;
	private Instant base;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("offtask-loot-pipeline-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		ledger = new EventLedger(GSON);
		ledger.start();

		session = new SessionRuntimeCoordinator(store);
		installClientThreadClient(session);
		session.ensureAccountLoaded(ACCOUNT);

		lootTracker = new LootTrackerCoordinator(GSON);
		lootTracker.start();
		lootTracker.ensureAccountLoaded(ACCOUNT);
		// Drain the (empty) account-load rebuild before any live event, so
		// live-vs-rebuild ordering can never be what a test measures.
		lootTracker.shutdown();
		lootTracker.start();

		// Same order as OsrsTelemetryPlugin.startUp().
		ledger.addListener(session);
		ledger.addListener(lootTracker);

		tick = 1L;
		base = Instant.now().minusSeconds(5);
	}

	@After
	public void tearDown() throws Exception
	{
		if (ledger != null)
		{
			ledger.removeListener(session);
			ledger.removeListener(lootTracker);
			ledger.shutdown();
		}
		if (lootTracker != null)
		{
			lootTracker.shutdown();
		}
		if (store != null)
		{
			store.shutdown();
		}
		if (testRoot != null)
		{
			TestFilepaths.rooted(testRoot).deleteRecursively();
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Minimal Client for the coordinator's own onEvent() tick tagging:
	 * reports "on the client thread" with the test's current tick, exactly
	 * what a live SERVER_NPC_LOOT dispatch sees. Without it onEvent() leaves
	 * the signal untagged and it is (correctly) never flushed.
	 */
	private void installClientThreadClient(SessionRuntimeCoordinator target) throws Exception
	{
		net.runelite.api.Client client = (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(), new Class<?>[]{net.runelite.api.Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "isClientThread":
						return true;
					case "getTickCount":
						return (int) tick;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					case "toString":
						return "TestClient";
					default:
						Class<?> rt = method.getReturnType();
						if (rt == boolean.class)
						{
							return false;
						}
						if (rt.isPrimitive() && rt != void.class)
						{
							return rt == long.class ? (Object) 0L : rt == double.class ? (Object) 0d : rt == float.class ? (Object) 0f : (Object) 0;
						}
						return null;
				}
			});
		java.lang.reflect.Field f = SessionRuntimeCoordinator.class.getDeclaredField("client");
		f.setAccessible(true);
		f.set(target, client);
	}

	/** Session-only evidence (task progress / XP) straight into the coordinator. */
	private void enqueue(SessionSignal signal)
	{
		session.testEnqueueSignal(signal.withGameTick(tick));
	}

	/** Raw SERVER_NPC_LOOT through the real EventLedger -> both listeners. */
	private void appendLoot(String source, Integer sourceId, long coins)
	{
		EventPayloads.ServerNpcLoot payload = new EventPayloads.ServerNpcLoot(source, sourceId,
			Arrays.asList(new EventPayloads.ServerNpcLootItem(COINS, "Coins", (int) coins)));
		assertTrue("durably written", ledger.appendAndWait(ACCOUNT, EventType.SERVER_NPC_LOOT, payload, 2000));
	}

	/** Processes everything queued since the last call as ONE same-tick batch. */
	private void processTick()
	{
		session.testProcessTick(++tick, Instant.now());
	}

	private void startTurothTask()
	{
		enqueue(SessionSignal.slayerTaskProgress(base, TUROTH, TUROTH_LOC, 1, 163));
		processTick();
		appendLoot("Turoth", 426, 10);
		processTick();
	}

	private Session current()
	{
		return session.testCurrentSession();
	}

	private static long sessionCoins(Session s)
	{
		long t = 0;
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			t += coins(g.getItems());
		}
		return t;
	}

	private static long currentBucketCoins(Session s)
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = s.getAggregates().getSlayerAssignments();
		return coins(l.get(l.size() - 1).getItems());
	}

	private static long allBucketsCoins(Session s)
	{
		long t = 0;
		for (SessionAggregates.SlayerAssignmentRecord r : s.getAggregates().getSlayerAssignments())
		{
			t += coins(r.getItems());
		}
		return t;
	}

	private static long coins(List<SessionAggregates.LootItemAggregate> items)
	{
		long t = 0;
		for (SessionAggregates.LootItemAggregate i : items)
		{
			if (i.getItemId() != null && i.getItemId() == COINS)
			{
				t += i.getQuantity();
			}
		}
		return t;
	}

	private static boolean sessionHasSource(Session s, String source)
	{
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			if (source.equals(g.getSourceName()))
			{
				return true;
			}
		}
		return false;
	}

	private static LootTrackerSource trackerSource(LootTrackerIndex index, String name, Integer id)
	{
		return index.snapshotSource(LootTrackerIndex.sourceKey(name, id));
	}

	private static long trackerCoins(LootTrackerSource src)
	{
		long t = 0;
		for (LootTrackerRecord r : src.getRecords())
		{
			for (com.osrstelemetry.plugin.loottracker.LootTrackerItem i : r.getItems())
			{
				if (i.getItemId() == COINS)
				{
					t += i.getQuantity();
				}
			}
		}
		return t;
	}

	private static int trackerRecordCount(LootTrackerIndex index)
	{
		int n = 0;
		for (LootTrackerSource s : index.snapshotSources())
		{
			n += s.getRecords().size();
		}
		return n;
	}

	/** Every SERVER_NPC_LOOT envelope in events.jsonl for {@code source}. */
	private List<JsonObject> rawLootLines(String source) throws Exception
	{
		Path events = TestFilepaths.file(TelemetryPaths.eventsFile(ACCOUNT)).toPath();
		List<JsonObject> out = new ArrayList<>();
		for (String line : Files.readAllLines(events, StandardCharsets.UTF_8))
		{
			if (line.isEmpty())
			{
				continue;
			}
			JsonObject env = new JsonParser().parse(line).getAsJsonObject();
			if ("SERVER_NPC_LOOT".equals(env.get("eventType").getAsString())
				&& source.equals(env.getAsJsonObject("payload").get("sourceName").getAsString()))
			{
				out.add(env);
			}
		}
		return out;
	}

	private LootTrackerIndex rebuildFromDisk()
	{
		LootTrackerIndex rebuilt = new LootTrackerIndex();
		new LootTrackerPersistence(GSON).rebuild(ACCOUNT, rebuilt, null);
		return rebuilt;
	}

	// ------------------------------------------------------------------
	// 1 + 2 + 3 + 4 + 7: KNOWN_MISMATCH, drop alone in its tick
	// ------------------------------------------------------------------

	@Test
	public void knownMismatch_rejectedFromSessionAndBucket_butRawEventAndLootTrackerKeepIt_onceUnderRealSource() throws Exception
	{
		startTurothTask();
		Session before = current();
		String id = before.getSessionId();
		assertEquals(10L, sessionCoins(before));
		assertEquals(10L, currentBucketCoins(before));

		appendLoot("Undead chicken", 2993, 7);
		processTick();

		// 1. Current Session: rejected, Slayer / Turoth remains current.
		Session s = current();
		assertEquals("Slayer session remains current", id, s.getSessionId());
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals("Slayer session loot unchanged", 10L, sessionCoins(s));
		assertTrue("no Undead chicken group in canonical session loot", !sessionHasSource(s, "Undead chicken"));
		assertEquals("Turoth assignment bucket unchanged", 10L, currentBucketCoins(s));
		assertEquals("partition invariant", sessionCoins(s), allBucketsCoins(s));

		// 2 + 7. Live Loot Tracker: the SAME event, once, under the real NPC.
		LootTrackerIndex live = lootTracker.getIndex();
		LootTrackerSource chicken = trackerSource(live, "Undead chicken", 2993);
		assertNotNull("Loot Tracker recorded the off-task drop", chicken);
		assertEquals("Undead chicken", chicken.getSourceName());
		assertEquals(Integer.valueOf(2993), chicken.getSourceId());
		assertEquals("exactly one record -- no duplicate", 1, chicken.getRecords().size());
		assertEquals(7L, trackerCoins(chicken));
		assertEquals("Turoth drop still its own source", 10L, trackerCoins(trackerSource(live, "Turoth", 426)));
		assertEquals("one tracker record per appended event", 2, trackerRecordCount(live));

		// 3. Raw telemetry: exactly one canonical line in events.jsonl.
		List<JsonObject> raw = rawLootLines("Undead chicken");
		assertEquals("canonical SERVER_NPC_LOOT line kept", 1, raw.size());
		assertEquals(7, raw.get(0).getAsJsonObject("payload").getAsJsonArray("items").get(0).getAsJsonObject().get("quantity").getAsInt());

		// 4. Historical rebuild includes it, once, keyed by its real eventId.
		LootTrackerIndex rebuilt = rebuildFromDisk();
		LootTrackerSource rebuiltChicken = trackerSource(rebuilt, "Undead chicken", 2993);
		assertNotNull("rebuild includes the off-task drop", rebuiltChicken);
		assertEquals(1, rebuiltChicken.getRecords().size());
		assertEquals(raw.get(0).get("eventId").getAsString(), rebuiltChicken.getRecords().get(0).getEventId());
		assertEquals(7L, trackerCoins(rebuiltChicken));
		assertEquals("rebuild totals == live totals", trackerRecordCount(live), trackerRecordCount(rebuilt));
		assertEquals("rebuild is idempotent (eventId dedup)", 0, new LootTrackerPersistence(GSON).rebuild(ACCOUNT, rebuilt, null));
	}

	// ------------------------------------------------------------------
	// 1 (same-tick): the fixed promotion leak
	// ------------------------------------------------------------------

	@Test
	public void knownMismatch_sameTickAsCombatXpOrTaskProgress_neverPromotedOntoTheSlayerSession() throws Exception
	{
		startTurothTask();
		String id = current().getSessionId();

		// Chicken drop in the same tick as ordinary combat XP (the batch
		// resolves to the Slayer session itself).
		Instant t1 = Instant.now();
		enqueue(SessionSignal.xpChange(t1, "ATTACK", 40L, base.plusSeconds(1)));
		enqueue(SessionSignal.xpChange(t1, "HITPOINTS", 13L, base.plusSeconds(1)));
		appendLoot("Undead chicken", 2993, 7);
		processTick();
		assertEquals(id, current().getSessionId());
		assertEquals("not promoted via combat XP", 10L, sessionCoins(current()));
		assertEquals(10L, currentBucketCoins(current()));

		// ...and in the same tick as a real Turoth kill's task progress.
		enqueue(SessionSignal.slayerTaskProgress(Instant.now(), TUROTH, TUROTH_LOC, 1, 162));
		appendLoot("Undead chicken", 2993, 9);
		processTick();
		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals("not promoted via task progress", 10L, sessionCoins(s));
		assertEquals(10L, currentBucketCoins(s));
		assertEquals(sessionCoins(s), allBucketsCoins(s));
		assertEquals(Integer.valueOf(162), s.getAggregates().getSlayerAssignments().get(0).getLatestRemaining());

		// Loot Tracker still has both drops, once each, under the real source.
		LootTrackerSource chicken = trackerSource(lootTracker.getIndex(), "Undead chicken", 2993);
		assertEquals(2, chicken.getRecords().size());
		assertEquals(16L, trackerCoins(chicken));
		assertEquals(2, rawLootLines("Undead chicken").size());
		assertEquals(16L, trackerCoins(trackerSource(rebuildFromDisk(), "Undead chicken", 2993)));
	}

	// ------------------------------------------------------------------
	// 5: MATCH reaches all three consumers
	// ------------------------------------------------------------------

	@Test
	public void match_reachesSession_currentBucket_andLootTracker_once_aloneAndSameTick() throws Exception
	{
		startTurothTask();
		String id = current().getSessionId();

		appendLoot("Spiked turoth", 427, 5); // listed family variant
		processTick();
		Instant t = Instant.now();
		enqueue(SessionSignal.xpChange(t, "ATTACK", 40L, base.plusSeconds(1)));
		appendLoot("Turoth", 426, 3); // same tick as combat XP
		processTick();

		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals("session owns every MATCH drop exactly once", 18L, sessionCoins(s));
		assertEquals("current Turoth bucket owns them too", 18L, currentBucketCoins(s));
		assertEquals(sessionCoins(s), allBucketsCoins(s));

		LootTrackerIndex live = lootTracker.getIndex();
		assertEquals(13L, trackerCoins(trackerSource(live, "Turoth", 426)));
		assertEquals(2, trackerSource(live, "Turoth", 426).getRecords().size());
		assertEquals(5L, trackerCoins(trackerSource(live, "Spiked turoth", 427)));
		assertEquals(3, trackerRecordCount(live));
		assertEquals(3, trackerRecordCount(rebuildFromDisk()));
	}

	// ------------------------------------------------------------------
	// 6: UNKNOWN keeps the documented Current Session fallback
	// ------------------------------------------------------------------

	@Test
	public void unknown_keepsExistingSessionOwnership_andLootTrackerStillGetsTheCanonicalEvent() throws Exception
	{
		startTurothTask();
		String id = current().getSessionId();

		// No Slayer task targets this NPC: off-task cannot be proven, so the
		// documented fallback is existing ACTIVE-session ownership.
		appendLoot("Death Spawn", 10, 3);
		processTick();

		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals("UNKNOWN kept by the ACTIVE Slayer session", 13L, sessionCoins(s));
		assertEquals("...and its current assignment bucket", 13L, currentBucketCoins(s));
		assertTrue(sessionHasSource(s, "Death Spawn"));

		LootTrackerSource spawn = trackerSource(lootTracker.getIndex(), "Death Spawn", 10);
		assertNotNull(spawn);
		assertEquals(1, spawn.getRecords().size());
		assertEquals(3L, trackerCoins(spawn));
		assertEquals(1, rawLootLines("Death Spawn").size());
		assertEquals(1, trackerSource(rebuildFromDisk(), "Death Spawn", 10).getRecords().size());
	}

	// ------------------------------------------------------------------
	// 7: one canonical event -> one tracker record, across every case
	// ------------------------------------------------------------------

	@Test
	public void everyAppendedLootEvent_isExactlyOneLootTrackerRecord_regardlessOfSessionDecision() throws Exception
	{
		startTurothTask();                       // MATCH
		appendLoot("Undead chicken", 2993, 7);   // KNOWN_MISMATCH
		processTick();
		appendLoot("Death Spawn", 10, 3);        // UNKNOWN
		processTick();
		Instant t = Instant.now();
		enqueue(SessionSignal.xpChange(t, "ATTACK", 40L, base.plusSeconds(1)));
		appendLoot("Goblin", 3029, 1);           // KNOWN_MISMATCH, same tick as XP
		processTick();

		LootTrackerIndex live = lootTracker.getIndex();
		assertEquals("4 events -> 4 records, none dropped, none doubled", 4, trackerRecordCount(live));
		Set<String> liveIds = new HashSet<>();
		for (LootTrackerSource src : live.snapshotSources())
		{
			for (LootTrackerRecord r : src.getRecords())
			{
				assertTrue("distinct record ids", liveIds.add(r.getEventId()));
			}
		}
		assertEquals(4, trackerRecordCount(rebuildFromDisk()));
		assertNull("Goblin never reached session loot", sessionHasSource(current(), "Goblin") ? "leaked" : null);
		assertEquals("session: Turoth 10 + Death Spawn 3 only", 13L, sessionCoins(current()));
	}
}
