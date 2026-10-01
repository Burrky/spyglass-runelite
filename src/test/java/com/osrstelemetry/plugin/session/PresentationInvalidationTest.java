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
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The runtime side of event-driven UI refresh: the session runtime and the
 * Loot Tracker announce exactly the changes a player can see -- promptly,
 * from the thread that made them -- and never depend on anyone listening.
 */
public class PresentationInvalidationTest
{
	private static final long ACCOUNT = 700_900_960L;
	private static final Gson GSON = new Gson();
	private static final String TUROTH = "Turoth";
	private static final String TUROTH_LOC = "Fremennik Slayer Dungeon";
	private static final int COINS = 995;

	private Path testRoot;
	private LocalStateStore store;
	private EventLedger ledger;
	private SessionRuntimeCoordinator session;
	private LootTrackerCoordinator lootTracker;
	private final AtomicInteger sessionChanges = new AtomicInteger();
	private final AtomicInteger lootChanges = new AtomicInteger();
	private long tick;
	private Instant base;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("presentation-invalidation-test");
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
		lootTracker.shutdown();
		lootTracker.start();
		ledger.addListener(session);
		ledger.addListener(lootTracker);
		session.setCurrentSessionListener(sessionChanges::incrementAndGet);
		lootTracker.setChangeListener(lootChanges::incrementAndGet);
		tick = 1L;
		base = Instant.now().minusSeconds(60);
	}

	@After
	public void tearDown() throws Exception
	{
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

	private void enqueue(SessionSignal signal)
	{
		session.testEnqueueSignal(signal.withGameTick(tick));
	}

	private void closeTick()
	{
		session.testProcessTick(++tick, Instant.now());
	}

	private void appendLoot(String source, Integer sourceId, long coins)
	{
		assertTrue(ledger.appendAndWait(ACCOUNT, EventType.SERVER_NPC_LOOT, new EventPayloads.ServerNpcLoot(source, sourceId,
			Arrays.asList(new EventPayloads.ServerNpcLootItem(COINS, "Coins", (int) coins))), 2000));
	}

	private void startTurothTask()
	{
		enqueue(SessionSignal.slayerTaskProgress(base, TUROTH, TUROTH_LOC, 1, 163));
		closeTick();
	}

	private long pending(String skill)
	{
		Long v = session.getCurrentSessionSnapshot().getLivePendingXpBySkill().get(skill);
		return v == null ? 0L : v;
	}

	// ------------------------------------------------------------------ XP

	@Test
	public void rawXpWithNoSession_neverFabricatesOwnership_norNotifies()
	{
		session.recordLiveXpDelta("WOODCUTTING", 25);
		assertEquals(0, sessionChanges.get());
		assertTrue(!session.getCurrentSessionSnapshot().isPresent());
	}

	@Test
	public void firstLegitimateSession_notifiesOnceEstablished()
	{
		enqueue(SessionSignal.xpChange(base, "WOODCUTTING", 25, base.minusSeconds(30)));
		assertEquals("queued, not yet visible", 0, sessionChanges.get());
		closeTick();
		assertEquals(1, sessionChanges.get());
		assertNotNull(session.testCurrentSession());
	}

	@Test
	public void rawXp_notifiesImmediately_pendingVisibleBeforeDurable_andReconcilesWithoutDoubleCounting()
	{
		enqueue(SessionSignal.xpChange(base, "WOODCUTTING", 25, base.minusSeconds(30)));
		closeTick();
		sessionChanges.set(0);

		session.recordLiveXpDelta("WOODCUTTING", 40);
		assertEquals("no timer, no durable flush needed", 1, sessionChanges.get());
		assertEquals(40L, pending("WOODCUTTING"));
		assertEquals(Long.valueOf(25), session.getCurrentSessionSnapshot().getXpGainedBySkill().get("WOODCUTTING"));

		// Same tick, several skills (e.g. combat): each still appears.
		session.recordLiveXpDelta("FLETCHING", 5);
		session.recordLiveXpDelta("WOODCUTTING", 10);
		assertEquals(50L, pending("WOODCUTTING"));
		assertEquals(5L, pending("FLETCHING"));

		// Durable XP_CHANGE for the same 50 XP: pending reconciled, never doubled.
		Instant later = base.plusSeconds(30);
		enqueue(SessionSignal.xpChange(later, "WOODCUTTING", 50, base.plusSeconds(1)));
		closeTick();
		assertEquals(0L, pending("WOODCUTTING"));
		assertEquals(Long.valueOf(75), session.getCurrentSessionSnapshot().getXpGainedBySkill().get("WOODCUTTING"));
	}

	// ------------------------------------------------------------------ loot / rewards

	@Test
	public void acceptedNpcLoot_dirtiesBothSurfaces_rejectedOffTaskLoot_onlyTheLootTracker()
	{
		startTurothTask();
		sessionChanges.set(0);
		lootChanges.set(0);

		appendLoot(TUROTH, 426, 10);
		assertEquals("Loot Tracker applied it live", 1, lootChanges.get());
		closeTick();
		assertEquals("accepted into the session", 1, sessionChanges.get());

		appendLoot("Undead chicken", 2993, 7);
		closeTick();
		assertEquals("rejected off-task loot changes nothing visible in Current Session", 1, sessionChanges.get());
		assertEquals("the same canonical loot is still a Loot Tracker change", 2, lootChanges.get());
	}

	@Test
	public void unchangedTicks_andIgnoredSignals_neverNotify()
	{
		startTurothTask();
		sessionChanges.set(0);
		for (int i = 0; i < 5; i++)
		{
			enqueue(SessionSignal.npcInteractionTarget(Instant.now(), 426, TUROTH));
			closeTick();
			closeTick();
		}
		assertEquals(0, sessionChanges.get());
	}

	@Test
	public void structuredReward_dirtiesSessionAndLootTracker()
	{
		enqueue(SessionSignal.structuredActivityLocation(base, "barrows", 14231));
		closeTick();
		enqueue(SessionSignal.xpChange(base.plusSeconds(1), "RANGED", 40, base));
		closeTick();
		sessionChanges.set(0);
		lootChanges.set(0);
		assertTrue(ledger.appendAndWait(ACCOUNT, EventType.STRUCTURED_REWARD, new EventPayloads.StructuredReward("barrows", "barrows", "Barrows",
			Collections.singletonList(new EventPayloads.StructuredRewardItem(COINS, "Coins", 500))), 2000));
		assertEquals(1, lootChanges.get());
		closeTick();
		assertEquals(1, sessionChanges.get());
		assertNotNull(lootTracker.getIndex().snapshotSource(LootTrackerIndex.structuredSourceKey("barrows")));
	}

	@Test
	public void duplicateOrUnknownLoot_doesNotDirtyTheLootTracker()
	{
		lootTracker.onEvent(ACCOUNT, EventType.SERVER_NPC_LOOT, new EventPayloads.ServerNpcLoot("Goblin", 3029,
			Arrays.asList(new EventPayloads.ServerNpcLootItem(COINS, "Coins", 1))), Instant.now(), "same-id");
		lootTracker.onEvent(ACCOUNT, EventType.SERVER_NPC_LOOT, new EventPayloads.ServerNpcLoot("Goblin", 3029,
			Arrays.asList(new EventPayloads.ServerNpcLootItem(COINS, "Coins", 1))), Instant.now(), "same-id");
		lootTracker.onEvent(ACCOUNT, EventType.STRUCTURED_REWARD, new EventPayloads.StructuredReward(null, null, "Brimstone Chest",
			Collections.singletonList(new EventPayloads.StructuredRewardItem(COINS, "Coins", 1))), Instant.now(), "other-id");
		assertEquals(1, lootChanges.get());
	}

	// ------------------------------------------------------------------ Slayer / completions / bosses / state

	@Test
	public void slayerProgress_assignment_completionMetric_bossCount_andStateChanges_allNotify()
	{
		startTurothTask();
		assertEquals("new Slayer session", 1, sessionChanges.get());

		enqueue(SessionSignal.slayerTaskProgress(base.plusSeconds(10), TUROTH, TUROTH_LOC, 1, 162));
		closeTick();
		assertEquals("task remaining", 2, sessionChanges.get());

		enqueue(SessionSignal.slayerTaskAssigned(base.plusSeconds(20), "Kalphites", "Kalphite Lair", 40));
		closeTick();
		assertTrue("new assignment", sessionChanges.get() >= 3);

		int beforeBoss = sessionChanges.get();
		enqueue(SessionSignal.bossKill(base.plusSeconds(30), "Zulrah", 101));
		closeTick();
		assertEquals("boss session / count", beforeBoss + 1, sessionChanges.get());
		enqueue(SessionSignal.bossKill(base.plusSeconds(90), "Zulrah", 102));
		closeTick();
		assertEquals("boss count increment", beforeBoss + 2, sessionChanges.get());

		// ACTIVE -> SUSPENDED by timeout, with no event at all.
		int beforeSuspend = sessionChanges.get();
		session.testProcessTick(++tick, base.plusSeconds(90).plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(5));
		assertEquals(SessionState.SUSPENDED, session.testCurrentSession().getState());
		assertEquals(beforeSuspend + 1, sessionChanges.get());
	}

	@Test
	public void structuredCompletion_notifies()
	{
		enqueue(SessionSignal.structuredActivityLocation(base, "guardians_of_the_rift", 14484));
		closeTick();
		enqueue(SessionSignal.xpChange(base.plusSeconds(1), "RUNECRAFT", 40, base));
		closeTick();
		int before = sessionChanges.get();
		enqueue(SessionSignal.structuredActivityCompletion(base.plusSeconds(2), "guardians_of_the_rift", 55));
		closeTick();
		assertEquals(before + 1, sessionChanges.get());
		assertEquals(1, session.testCurrentSession().getAggregates().getActivityCompletions().getSessionCompletions());
	}

	@Test
	public void accountSwitch_notifies()
	{
		startTurothTask();
		int before = sessionChanges.get();
		session.ensureAccountLoaded(ACCOUNT + 1);
		assertEquals(before + 1, sessionChanges.get());
		assertNull(session.testCurrentSession());
	}

	// ------------------------------------------------------------------ UI independence

	@Test
	public void aThrowingListener_neverBreaksRuntimeProcessing()
	{
		session.setCurrentSessionListener(() ->
		{
			throw new IllegalStateException("UI is gone");
		});
		lootTracker.setChangeListener(() ->
		{
			throw new IllegalStateException("UI is gone");
		});
		startTurothTask();
		appendLoot(TUROTH, 426, 10);
		closeTick();
		session.recordLiveXpDelta("SLAYER", 20);
		assertEquals(10L, session.testCurrentSession().getAggregates().getLootDrops().get(0).getItems().get(0).getQuantity());
		assertEquals(1, lootTracker.getIndex().snapshotSources().size());
	}

	@Test
	public void noListenerAtAll_telemetryIdentical()
	{
		session.setCurrentSessionListener(null);
		lootTracker.setChangeListener(null);
		startTurothTask();
		appendLoot(TUROTH, 426, 10);
		closeTick();
		assertEquals(TUROTH, session.testCurrentSession().getAggregates().getLootDrops().get(0).getSourceName());
		assertEquals(1, lootTracker.getIndex().snapshotSources().size());
	}
}
