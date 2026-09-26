package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * LIVE REGRESSION: BOSSING/Kraken performed on a matching "Cave Kraken"
 * Slayer assignment. Real live event order per kill:
 *   NPC_DEATH -> SLAYER_TASK_PROGRESS(Cave Kraken) -> ~1.8s -> BOSS_KILL(Kraken)
 *   -> SERVER_NPC_LOOT(Kraken)
 * Before the fix, the SLAYER_TASK_PROGRESS was an immediate SPECIFIC switch to
 * SLAYER/Cave Kraken, which resumed the parked regular Slayer session A
 * (finalizing Kraken B), and the following BOSS_KILL then parked A again and
 * minted a FRESH Kraken session -- every kill.
 */
public class SessionRuntimeCoordinatorBossOnMatchingTaskTest
{
	private static final long ACCOUNT = 700_900_100L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final Gson GSON = new Gson();
	private static final String TASK = "Cave Kraken";
	private static final String LOCATION = "Kraken Cove";

	private LocalStateStore store;
	private SessionRuntimeCoordinator coordinator;
	private Path testRoot;
	private long tick;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("boss-on-matching-task-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
		coordinator.ensureAccountLoaded(ACCOUNT);
		tick = 1L;
	}

	@After
	public void tearDown() throws Exception
	{
		if (store != null)
		{
			store.shutdown();
		}
		if (testRoot != null)
		{
			TestFilepaths.rooted(testRoot).deleteRecursively();
		}
	}

	/** One signal per game tick, resolved on the next tick -- mirrors the live spacing. */
	private void feed(SessionSignal signal, Instant at)
	{
		coordinator.testEnqueueSignal(signal.withGameTick(tick));
		coordinator.testProcessTick(++tick, at);
	}

	private static SessionSignal loot(Instant at, String source, int itemId, String item)
	{
		return SessionSignal.serverNpcLoot(at, source,
			Collections.singletonList(new SessionSignal.LootDrop(itemId, item, 1)));
	}

	private long sessionFileCount() throws Exception
	{
		Path dir = testRoot.resolve(String.valueOf(ACCOUNT)).resolve("sessions");
		if (!Files.exists(dir))
		{
			return 0;
		}
		try (Stream<Path> files = Files.list(dir))
		{
			return files.count();
		}
	}

	private static long magicXp(SessionAggregates agg)
	{
		long total = 0;
		for (java.util.Map.Entry<String, Long> e : agg.getXpGainedBySkill().entrySet())
		{
			if (e.getKey().equalsIgnoreCase("magic"))
			{
				total += e.getValue();
			}
		}
		return total;
	}

	/** Regular Slayer/Cave Kraken A (cave krakens), then a real kill of the Kraken boss -> B, A parked. */
	private String[] establishSlayerAThenKrakenB()
	{
		feed(SessionSignal.npcDeath(T0), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(1), TASK, LOCATION, 1, 40), T0.plusSeconds(1));
		feed(loot(T0.plusSeconds(2), "Cave kraken", 526, "Bones"), T0.plusSeconds(2));
		Session a = coordinator.testCurrentSession();
		assertEquals(ActivityType.SLAYER, a.getActivityIdentity().getActivityType());

		Instant k1 = T0.plusSeconds(60);
		feed(SessionSignal.npcDeath(k1), k1);
		feed(SessionSignal.slayerTaskProgress(k1.plusMillis(1200), TASK, LOCATION, 1, 39), k1.plusMillis(1200));
		feed(SessionSignal.bossKill(k1.plusMillis(3000), "Kraken", 11), k1.plusMillis(3000));
		feed(loot(k1.plusMillis(3000), "Kraken", 995, "Coins"), k1.plusMillis(3600));

		Session b = coordinator.testCurrentSession();
		assertEquals(ActivityType.BOSSING, b.getActivityIdentity().getActivityType());
		assertEquals("kraken", b.getActivityIdentity().getActivityKey());
		assertNotNull("setup: regular Slayer A parked as the interrupted candidate", coordinator.testInterruptedCandidate());
		assertEquals(a.getSessionId(), coordinator.testInterruptedCandidate().getSessionId());
		return new String[] {a.getSessionId(), b.getSessionId(), b.getStartedAt()};
	}

	@Test
	public void krakenBossOnCaveKrakenTask_realEventOrder_oneContinuousBossSession_slayerANeverResumed() throws Exception
	{
		String[] ids = establishSlayerAThenKrakenB();
		String aSessionId = ids[0];
		String bSessionId = ids[1];
		String bStartedAt = ids[2];
		Thread.sleep(300);
		long filesBefore = sessionFileCount();
		long previousDuration = coordinator.testCurrentSession().getAccumulatedActiveDurationMillis();

		int remaining = 39;
		// Kills 2..5 stay inside parked A's own 5-minute window, so A is
		// still a live candidate that a buggy path COULD resurrect.
		for (int kill = 2; kill <= 5; kill++)
		{
			Instant t = T0.plusSeconds(60L * kill);
			remaining--;

			feed(SessionSignal.npcDeath(t), t);
			assertEquals(bSessionId, coordinator.testCurrentSession().getSessionId());

			feed(SessionSignal.slayerTaskProgress(t.plusMillis(1200), TASK, LOCATION, 1, remaining), t.plusMillis(1200));
			Session afterProgress = coordinator.testCurrentSession();
			assertEquals("matching task progress must never switch away from the Kraken boss (kill " + kill + ")",
				bSessionId, afterProgress.getSessionId());
			assertEquals(ActivityType.BOSSING, afterProgress.getActivityIdentity().getActivityType());
			assertEquals("regular Slayer A must never be resumed by matching task progress",
				aSessionId, coordinator.testInterruptedCandidate() == null ? aSessionId : coordinator.testInterruptedCandidate().getSessionId());

			feed(SessionSignal.xpChange(t.plusMillis(2400), "MAGIC", 500L), t.plusMillis(2400));
			feed(SessionSignal.bossKill(t.plusMillis(3000), "Kraken", 10 + kill), t.plusMillis(3000));
			feed(loot(t.plusMillis(3000), "Kraken", 995, "Coins"), t.plusMillis(3600));

			Session b = coordinator.testCurrentSession();
			assertEquals("B's sessionId must never change (kill " + kill + ")", bSessionId, b.getSessionId());
			assertEquals("B's startedAt must never reset", bStartedAt, b.getStartedAt());
			assertEquals(SessionState.ACTIVE, b.getState());
			assertEquals("kraken", b.getActivityIdentity().getActivityKey());
			assertTrue("active duration must keep growing", b.getAccumulatedActiveDurationMillis() > previousDuration);
			previousDuration = b.getAccumulatedActiveDurationMillis();

			SessionAggregates agg = b.getAggregates();
			assertEquals("boss sessionOccurrences must increase with every kill",
				kill, agg.getReliableCount().getSessionOccurrences());
			assertEquals(Integer.valueOf(10 + kill), agg.getReliableCount().getAuthoritativeCurrentValue());
			assertEquals("Slayer remaining must track the task on the boss session",
				Integer.valueOf(remaining), agg.getLatestSlayerCurrentRemaining());
			assertEquals("magic XP must be cumulative on ONE session",
				500L * (kill - 1), magicXp(agg));
			assertEquals("Kraken loot must be cumulative on ONE session", kill, agg.getLootDrops().size());
		}

		assertEquals("self-consumed task units must all land on the boss session",
			Integer.valueOf(4), coordinator.testCurrentSession().getAggregates().getSlayerProgressDelta());
		Thread.sleep(300);
		assertEquals("no session may be finalized while continuously bossing (no duplicate/churn finalizations)",
			filesBefore, sessionFileCount());
		assertNotNull("A remains the untouched parked candidate", coordinator.testInterruptedCandidate());
		assertEquals(aSessionId, coordinator.testInterruptedCandidate().getSessionId());
	}

	// Interrupted recovery is NOT neutered: a genuine return to killing
	// ordinary Cave krakens (proven by a different-NPC loot, the existing
	// disambiguation path) resumes the ORIGINAL regular Slayer A.
	@Test
	public void genuineReturnToRegularCaveKrakens_withinWindow_resumesOriginalSlayerA() throws Exception
	{
		String[] ids = establishSlayerAThenKrakenB();
		String aSessionId = ids[0];
		String bSessionId = ids[1];

		Instant t = T0.plusSeconds(120);
		feed(SessionSignal.npcDeath(t), t);
		feed(SessionSignal.slayerTaskProgress(t.plusMillis(1200), TASK, LOCATION, 1, 38), t.plusMillis(1200));
		assertEquals("ambiguous progress alone must not switch", bSessionId, coordinator.testCurrentSession().getSessionId());
		feed(loot(t.plusMillis(1800), "Cave kraken", 526, "Bones"), t.plusMillis(1800));

		Session current = coordinator.testCurrentSession();
		assertEquals("a genuine return to regular Cave krakens must resume ORIGINAL A", aSessionId, current.getSessionId());
		assertEquals(ActivityType.SLAYER, current.getActivityIdentity().getActivityType());
		assertEquals(SessionState.ACTIVE, current.getState());
		assertTrue("A must have been consumed by the resume, never left parked",
			coordinator.testInterruptedCandidate() == null
				|| !aSessionId.equals(coordinator.testInterruptedCandidate().getSessionId()));
		assertEquals("the progress unit belongs to the regular kill on A", Integer.valueOf(38),
			current.getAggregates().getLatestSlayerCurrentRemaining());
		assertFalse(bSessionId.equals(current.getSessionId()));
	}
}
