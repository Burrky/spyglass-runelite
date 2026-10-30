package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * STRUCTURED_REWARD through the REAL EventLedger into BOTH independent
 * listeners -- SessionRuntimeCoordinator (ownership only when the reward's
 * source belongs to the matching activity) and LootTrackerCoordinator
 * (always, exactly once, under the canonical reward source) -- exactly like
 * SERVER_NPC_LOOT. Current Session is never the Loot Tracker's source.
 */
public class StructuredRewardPipelineTest
{
	private static final long ACCOUNT = 700_900_950L;
	private static final Gson GSON = new Gson();
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
		testRoot = Files.createTempDirectory("structured-reward-pipeline-test");
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
		lootTracker.shutdown(); // drain the empty account-load rebuild before any live event
		lootTracker.start();
		ledger.addListener(session);
		ledger.addListener(lootTracker);
		tick = 1L;
		base = Instant.now().minusSeconds(120);
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.removeListener(session);
		ledger.removeListener(lootTracker);
		ledger.shutdown();
		lootTracker.shutdown();
		store.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

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
					default:
						Class<?> rt = method.getReturnType();
						if (rt == boolean.class)
						{
							return false;
						}
						if (rt.isPrimitive() && rt != void.class)
						{
							return rt == long.class ? (Object) 0L : (Object) 0;
						}
						return null;
				}
			});
		java.lang.reflect.Field f = SessionRuntimeCoordinator.class.getDeclaredField("client");
		f.setAccessible(true);
		f.set(target, client);
	}

	// ------------------------------------------------------------------ helpers

	private void enqueue(SessionSignal signal)
	{
		session.testEnqueueSignal(signal.withGameTick(tick));
	}

	private void closeTick()
	{
		session.testProcessTick(++tick, Instant.now());
	}

	/** Enter the activity's location and play expected XP on three ticks. */
	private void playActivity(String activityKey, int region, String skill)
	{
		enqueue(SessionSignal.structuredActivityLocation(base, activityKey, region));
		closeTick();
		for (int i = 1; i <= 3; i++)
		{
			Instant t = base.plusSeconds(5L * i);
			enqueue(SessionSignal.xpChange(t, skill, 20, t.minusSeconds(2)));
			closeTick();
		}
		assertEquals(activityKey, session.testCurrentSession().getActivityIdentity().getActivityKey());
	}

	private void moveTo(String activityKey, int region)
	{
		enqueue(SessionSignal.structuredActivityLocation(base.plusSeconds(30), activityKey, region));
		closeTick();
	}

	private void slayer(int remaining)
	{
		enqueue(SessionSignal.slayerTaskProgress(base.plusSeconds(1), "Bloodveld", null, 1, remaining));
	}

	/** One canonical reward claim, appended exactly as the collector does, through the real ledger. */
	private void reward(String activityKey, String sourceKey, String sourceName, long coins)
	{
		assertTrue(ledger.appendAndWait(ACCOUNT, EventType.STRUCTURED_REWARD, new EventPayloads.StructuredReward(activityKey, sourceKey, sourceName,
			Collections.singletonList(new EventPayloads.StructuredRewardItem(COINS, "Coins", (int) coins))), 2000));
	}

	private static long sessionCoins(Session s, String sourceName)
	{
		long total = 0;
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			if (sourceName == null || sourceName.equals(g.getSourceName()))
			{
				for (SessionAggregates.LootItemAggregate i : g.getItems())
				{
					total += i.getQuantity();
				}
			}
		}
		return total;
	}

	private LootTrackerSource tracked(String rewardSourceKey)
	{
		return lootTracker.getIndex().snapshotSource(LootTrackerIndex.structuredSourceKey(rewardSourceKey));
	}

	private int trackedRecords()
	{
		int n = 0;
		for (LootTrackerSource s : lootTracker.getIndex().snapshotSources())
		{
			n += s.getRecords().size();
		}
		return n;
	}

	// ------------------------------------------------------------------ A-E

	@Test
	public void a_matchingActivity_sessionOwnsIt_lootTrackerRecordsItOnce_perActivity() throws Exception
	{
		Object[][] cases = {
			{"barrows", 14231, "RANGED", 14231, "barrows", "Barrows"},
			{"guardians_of_the_rift", 14484, "RUNECRAFT", 14484, "guardians_of_the_rift", "Guardians of the Rift"},
			{"tempoross", 12076, "FISHING", 12588, "tempoross_reward_pool", "Tempoross Reward Pool"},
			{"wintertodt", 6462, "FIREMAKING", 6461, "wintertodt_reward_cart", "Wintertodt Reward Cart"},
		};
		for (Object[] c : cases)
		{
			tearDown();
			setUp();
			playActivity((String) c[0], (Integer) c[1], (String) c[2]);
			if (!c[3].equals(c[1]))
			{
				moveTo((String) c[0], (Integer) c[3]);
			}
			reward((String) c[0], (String) c[4], (String) c[5], 777);
			closeTick();
			Session s = session.testCurrentSession();
			assertEquals(c[0] + ": session owns its own reward", 777L, sessionCoins(s, (String) c[5]));
			LootTrackerSource src = tracked((String) c[4]);
			assertNotNull(c[0] + ": Loot Tracker has the canonical source", src);
			assertEquals(c[5], src.getSourceName());
			assertNull("no fake NPC id", src.getSourceId());
			assertEquals(c[0] + ": exactly once", 1, src.getRecords().size());
			assertEquals(1, trackedRecords());
		}
	}

	@Test
	public void b_noCurrentSession_lootTrackerStillRecordsItOnce()
	{
		reward("barrows", "barrows", "Barrows", 500);
		closeTick();
		assertNull(session.testCurrentSession());
		assertEquals(1, tracked("barrows").getRecords().size());
	}

	@Test
	public void c_wrongCurrentActivity_sessionGetsNothing_lootTrackerOnce()
	{
		playActivity("barrows", 14231, "RANGED");
		reward("guardians_of_the_rift", "guardians_of_the_rift", "Guardians of the Rift", 300);
		closeTick();
		assertEquals("Barrows", session.testCurrentSession().getActivityIdentity().getDisplayName());
		assertEquals(0L, sessionCoins(session.testCurrentSession(), null));
		assertEquals(1, tracked("guardians_of_the_rift").getRecords().size());
	}

	@Test
	public void c2_sourceMustMatchActivity_notJustAnyStructuredSession()
	{
		playActivity("guardians_of_the_rift", 14484, "RUNECRAFT");
		// A mismatched pairing (GotR activity, Barrows chest source) is never owned.
		reward("guardians_of_the_rift", "barrows", "Barrows", 300);
		closeTick();
		assertEquals(0L, sessionCoins(session.testCurrentSession(), null));
	}

	@Test
	public void d_parkedSlayer_getsNothing_lootTrackerOnce()
	{
		slayer(99);
		closeTick();
		String slayerId = session.testCurrentSession().getSessionId();
		playActivity("tempoross", 12076, "FISHING");
		assertEquals(slayerId, session.testInterruptedCandidate().getSessionId());
		// A reward for a different activity, while Slayer is parked and Tempoross current.
		reward("barrows", "barrows", "Barrows", 400);
		closeTick();
		assertTrue(session.testInterruptedCandidate().getAggregates().getLootDrops().isEmpty());
		assertEquals(0L, sessionCoins(session.testCurrentSession(), null));
		assertEquals(1, tracked("barrows").getRecords().size());
	}

	@Test
	public void e_sameTickUnrelatedEvidence_doesNotLeakOwnership()
	{
		slayer(99);
		closeTick();
		String slayerId = session.testCurrentSession().getSessionId();
		// Same tick: Slayer progress + a GotR reward claim (no GotR location / session).
		slayer(98);
		reward("guardians_of_the_rift", "guardians_of_the_rift", "Guardians of the Rift", 250);
		closeTick();
		assertEquals(slayerId, session.testCurrentSession().getSessionId());
		assertEquals("never Bloodveld's loot", 0L, sessionCoins(session.testCurrentSession(), null));
		assertEquals(1, tracked("guardians_of_the_rift").getRecords().size());
	}

	// ------------------------------------------------------------------ unrelated chests

	@Test
	public void unrelatedChests_neverOwned_andNeverGuessedIntoTheLootTracker()
	{
		slayer(99);
		closeTick();
		// There is no producer for this one; even a hand-made event is owned by nobody.
		reward(null, null, "Crystal Chest", 1000);
		closeTick();
		assertEquals("Bloodveld does not own a crystal chest", 0L, sessionCoins(session.testCurrentSession(), null));

		playActivity("barrows", 14231, "RANGED");
		reward("barrows", null, "Crystal Chest", 1000);
		closeTick();
		assertEquals("Barrows does not own an unrelated chest", 0L, sessionCoins(session.testCurrentSession(), null));
		assertEquals("unknown sources are not guessed into the Loot Tracker", 0, trackedRecords());
	}

	@Test
	public void tempoross_casketOpenedLater_isNeverATemporossReward()
	{
		playActivity("tempoross", 12076, "FISHING");
		moveTo("tempoross", 12588);
		reward("tempoross", null, "Casket (Tempoross)", 900);
		closeTick();
		assertEquals(0L, sessionCoins(session.testCurrentSession(), null));
		assertEquals(0, trackedRecords());
		reward("tempoross", "tempoross_reward_pool", "Tempoross Reward Pool", 100);
		closeTick();
		assertEquals(100L, sessionCoins(session.testCurrentSession(), "Tempoross Reward Pool"));
		assertEquals(1, tracked("tempoross_reward_pool").getRecords().size());
	}

	// ------------------------------------------------------------------ exactly once, live vs rebuild

	@Test
	public void liveAndRebuiltCopiesOfOneReward_shareTheEnvelopeEventId()
	{
		reward("barrows", "barrows", "Barrows", 10);
		reward("guardians_of_the_rift", "guardians_of_the_rift", "Guardians of the Rift", 20);
		ledger.shutdown(); // drain events.jsonl
		LootTrackerIndex live = lootTracker.getIndex();
		assertEquals(2, trackedRecords());
		int reapplied = new LootTrackerPersistence(GSON).rebuild(ACCOUNT, live, null);
		assertEquals("every rebuilt record is a duplicate of a live one (same eventId)", 0, reapplied);

		LootTrackerIndex fresh = new LootTrackerIndex();
		assertEquals(2, new LootTrackerPersistence(GSON).rebuild(ACCOUNT, fresh, null));
		LootTrackerRecord liveRecord = live.snapshotSource(LootTrackerIndex.structuredSourceKey("barrows")).getRecords().get(0);
		LootTrackerRecord rebuilt = fresh.snapshotSource(LootTrackerIndex.structuredSourceKey("barrows")).getRecords().get(0);
		assertEquals(liveRecord.getEventId(), rebuilt.getEventId());
		assertEquals(liveRecord.getObservedAt(), rebuilt.getObservedAt());
		assertEquals(liveRecord.getItems(), rebuilt.getItems());
	}

	// ------------------------------------------------------------------ independent rewards (the Brimstone chest)

	private static final int PURE_ESSENCE_NOTED = 7937;

	@Test
	public void brimstoneChest_duringABloodveldTask_isTheChestsOwnRecord_neverBloodvelds_andRecordedOnce()
	{
		slayer(99);
		closeTick();
		String slayerId = session.testCurrentSession().getSessionId();
		// What ServerNpcLootCollector now writes for the game's "loot from Konar quo Maten" (npc 8623): one structured reward.
		EventPayloads.StructuredReward chest = com.osrstelemetry.plugin.collectors.ServerNpcLootCollectorAccess.independentReward(8623, "Konar quo Maten",
			Collections.singletonList(new EventPayloads.ServerNpcLootItem(PURE_ESSENCE_NOTED, "Pure essence", 5095)));
		assertNotNull("Konar's loot is the Brimstone chest", chest);
		assertEquals("brimstone_chest", chest.getSourceKey());
		assertTrue(ledger.appendAndWait(ACCOUNT, EventType.STRUCTURED_REWARD, chest, 2000));
		closeTick();
		assertEquals(slayerId, session.testCurrentSession().getSessionId());
		assertTrue("Bloodveld owns none of it", session.testCurrentSession().getAggregates().getLootDrops().isEmpty());
		LootTrackerSource src = tracked("brimstone_chest");
		assertNotNull(src);
		assertEquals("Brimstone chest", src.getSourceName());
		assertEquals("one claim, one record", 1, trackedRecords());
		assertEquals(5095, src.getRecords().get(0).getItems().get(0).getQuantity());
	}

	@Test
	public void anOlderKonarNpcLootEvent_isReadAsTheChest_inTheSessionAndTheLootTracker()
	{
		slayer(99);
		closeTick();
		// As the earlier build wrote it: SERVER_NPC_LOOT named for Konar.
		assertTrue(ledger.appendAndWait(ACCOUNT, EventType.SERVER_NPC_LOOT, new EventPayloads.ServerNpcLoot("Konar quo Maten", 8623,
			Collections.singletonList(new EventPayloads.ServerNpcLootItem(PURE_ESSENCE_NOTED, "Pure essence", 5095))), 2000));
		closeTick();
		assertTrue("never the Slayer session's loot", session.testCurrentSession().getAggregates().getLootDrops().isEmpty());
		assertEquals(1, tracked("brimstone_chest").getRecords().size());
		assertEquals(1, trackedRecords());
		// Ordinary NPC loot is untouched.
		assertNull(com.osrstelemetry.plugin.collectors.ServerNpcLootCollectorAccess.independentReward(1618, "Bloodveld",
			Collections.singletonList(new EventPayloads.ServerNpcLootItem(995, "Coins", 30))));
	}
}
