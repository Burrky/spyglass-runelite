package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * LIVE REGRESSION (post native-Slayer migration): a broader Slayer session
 * spanning several assignments reaches a Cave Kraken assignment, the player
 * walks to Kraken Cove and starts killing the Kraken boss. Observed: the
 * broader Slayer session FINALIZED into History (Cave Kraken x1 as its last
 * assignment) and a standalone "Kraken" Current Session started with its own
 * timer, XP and loot.
 *
 * Root cause: ActivitySignalClassifier.decideCombatBranch() treated
 * SLAYER -&gt; BOSSING as a real switch with no task-compatibility check.
 * Against a SUSPENDED Slayer session (idle while travelling) the engine
 * finalizes on specific different-identity evidence -- it only parks from
 * ACTIVE -- hence the History entry. Expected (product rule): when the active
 * Slayer assignment is compatible with the boss (task-family membership),
 * SLAYER owns the encounter: same session id, same elapsed time, kills / loot
 * on the Cave Kraken assignment, XP in the broader session.
 *
 * Real per-kill order: BOSS_ACTIVITY_CONTEXT (first interaction) ...
 * NPC_DEATH -&gt; SLAYER_TASK_PROGRESS -&gt; ~1.8s -&gt; BOSS_KILL -&gt; SERVER_NPC_LOOT.
 */
public class SessionRuntimeCoordinatorBossOnMatchingTaskTest extends SlayerCoordinatorTestSupport
{
	private static final String TASK = "Cave Kraken";
	private static final String LOCATION = "Kraken Cove";

	private long sessionFiles() throws Exception
	{
		Thread.sleep(250);
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

	private static SessionSignal loot(Instant at, String source, int itemId, String item, int qty)
	{
		return SessionSignal.serverNpcLoot(at, source, Collections.singletonList(new SessionSignal.LootDrop(itemId, item, qty)));
	}

	private static SessionAggregates.SlayerAssignmentRecord currentRecord(Session s)
	{
		List<SessionAggregates.SlayerAssignmentRecord> records = s.getAggregates().getSlayerAssignments();
		return records.get(records.size() - 1);
	}

	private static long qty(SessionAggregates.SlayerAssignmentRecord r, String item)
	{
		long total = 0;
		for (SessionAggregates.LootItemAggregate i : r.getItems())
		{
			if (item.equals(i.getItemName()))
			{
				total += i.getQuantity();
			}
		}
		return total;
	}

	private static long xp(Session s, String skill)
	{
		Long v = s.getAggregates().getXpGainedBySkill().get(skill);
		return v == null ? 0L : v;
	}

	/**
	 * Broader Slayer session: Nechryael (completed), Kalphites (skipped by a
	 * direct replacement), then Cave Kraken assigned. Returns the instant the
	 * Cave Kraken assignment arrived.
	 */
	private Instant establishMultiAssignmentSessionEndingOnCaveKraken()
	{
		int remaining = 3;
		for (int i = 0; i < 3; i++)
		{
			Instant t = T0.plusSeconds(20L * i);
			remaining--;
			feed(SessionSignal.slayerTaskProgress(t, NECH, NECH_LOC, 1, remaining), t);
			feed(loot(t.plusSeconds(1), "Nechryael", 995, "Coins", 100), t.plusSeconds(1));
		}
		xp("SLAYER", 315L, T0);
		feed(SessionSignal.slayerTaskCompleted(T0.plusSeconds(70), NECH, null), T0.plusSeconds(70));

		Instant kalphAssigned = T0.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(kalphAssigned, KALPHITES, KALPHITE_LOC, 140), kalphAssigned);
		feed(SessionSignal.slayerTaskProgress(kalphAssigned.plusSeconds(30), KALPHITES, KALPHITE_LOC, 1, 139), kalphAssigned.plusSeconds(30));
		feed(loot(kalphAssigned.plusSeconds(31), "Kalphite Worker", 526, "Bones", 1), kalphAssigned.plusSeconds(31));

		Instant krakenAssigned = kalphAssigned.plusSeconds(90);
		feed(SessionSignal.slayerTaskAssigned(krakenAssigned, TASK, LOCATION, 100), krakenAssigned);
		Session s = current();
		assertEquals("cave kraken@kraken cove", s.getActivityIdentity().getActivityKey());
		assertEquals("setup: three assignment instances in one broader session", 3, s.getAggregates().getSlayerAssignments().size());
		return krakenAssigned;
	}

	/** One full Kraken kill in the live order; returns the instant after its loot. */
	private Instant krakenKill(Instant t, int remainingAfter, int kc)
	{
		feed(SessionSignal.npcDeath(t), t);
		feed(SessionSignal.slayerTaskProgress(t.plusMillis(1200), TASK, LOCATION, 1, remainingAfter), t.plusMillis(1200));
		feed(SessionSignal.bossKill(t.plusMillis(3000), "Kraken", kc), t.plusMillis(3000));
		feed(loot(t.plusMillis(3000), "Kraken", 3272, "Kraken tentacle", 1), t.plusMillis(3600));
		return t.plusMillis(3600);
	}

	private void assertSlayerOwnsKraken(String sessionId, String startedAt)
	{
		Session s = current();
		assertEquals("same broader Slayer session id", sessionId, s.getSessionId());
		assertEquals("same broader elapsed-time origin", startedAt, s.getStartedAt());
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals("cave kraken@kraken cove", s.getActivityIdentity().getActivityKey());
		assertNull("nothing parked: no standalone boss session displaced Slayer", coordinator.testInterruptedCandidate());
	}

	// ---------------------------------------------------------------- live repro

	@Test
	public void liveRepro_suspendedWhileTravelling_krakenContextKillsLootXp_allStayInTheBroaderSlayerSession() throws Exception
	{
		Instant assigned = establishMultiAssignmentSessionEndingOnCaveKraken();
		Session before = current();
		String id = before.getSessionId();
		String startedAt = before.getStartedAt();
		long nechCoins = qty(before.getAggregates().getSlayerAssignments().get(0), "Coins");
		long filesBefore = sessionFiles();

		// Travel / banking: idle past the suspend threshold.
		Instant travelled = assigned.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(90);
		coordinator.testProcessTick(++tick, travelled);
		assertEquals("setup: the broader session is SUSPENDED while travelling", SessionState.SUSPENDED, current().getState());

		// First evidence at the cove: boss context from interacting with Kraken.
		feed(SessionSignal.bossActivityContext(travelled.plusSeconds(5), "Kraken"), travelled.plusSeconds(5));
		assertSlayerOwnsKraken(id, startedAt);
		assertEquals("resumed, not replaced", SessionState.ACTIVE, current().getState());

		Instant t = travelled.plusSeconds(20);
		int remaining = 100;
		for (int kill = 1; kill <= 4; kill++)
		{
			remaining--;
			t = krakenKill(t, remaining, 200 + kill);
			xp("SLAYER", 255L, t.minusSeconds(29));
			assertSlayerOwnsKraken(id, startedAt);
			t = t.plusSeconds(45);
		}

		Session s = current();
		SessionAggregates.SlayerAssignmentRecord caveKraken = currentRecord(s);
		assertEquals("cave kraken@kraken cove", caveKraken.getAssignmentKey());
		assertEquals("every Kraken kill consumed a Cave Kraken unit", Integer.valueOf(4), caveKraken.getUnitsCompleted());
		assertEquals(Integer.valueOf(96), caveKraken.getLatestRemaining());
		assertEquals("Kraken loot is owned by the Cave Kraken assignment", 4L, qty(caveKraken, "Kraken tentacle"));
		assertEquals("earlier assignments untouched", nechCoins, qty(s.getAggregates().getSlayerAssignments().get(0), "Coins"));
		assertEquals(0L, qty(s.getAggregates().getSlayerAssignments().get(1), "Kraken tentacle"));
		assertEquals("Kraken XP stays in the broader Slayer session", 315L + 4 * 255L, xp(s, "SLAYER"));
		assertEquals("Kraken XP is attributed to the Cave Kraken assignment",
			Long.valueOf(4 * 255L), caveKraken.getXpGainedBySkill().get("SLAYER"));
		assertEquals("no finalized History entry was written", filesBefore, sessionFiles());
	}

	@Test
	public void liveRepro_activeSlayerSession_krakenKills_neverParkSlayerOrStartABossSession() throws Exception
	{
		Instant assigned = establishMultiAssignmentSessionEndingOnCaveKraken();
		String id = current().getSessionId();
		String startedAt = current().getStartedAt();
		long filesBefore = sessionFiles();

		// Regular cave krakens first (session ACTIVE), then the boss.
		feed(SessionSignal.slayerTaskProgress(assigned.plusSeconds(40), TASK, LOCATION, 1, 99), assigned.plusSeconds(40));
		feed(loot(assigned.plusSeconds(41), "Cave kraken", 995, "Coins", 40), assigned.plusSeconds(41));
		assertEquals(SessionState.ACTIVE, current().getState());

		Instant t = assigned.plusSeconds(70);
		int remaining = 99;
		for (int kill = 1; kill <= 3; kill++)
		{
			remaining--;
			t = krakenKill(t, remaining, 10 + kill).plusSeconds(40);
			assertSlayerOwnsKraken(id, startedAt);
		}
		SessionAggregates.SlayerAssignmentRecord caveKraken = currentRecord(current());
		assertEquals(Integer.valueOf(4), caveKraken.getUnitsCompleted());
		assertEquals(3L, qty(caveKraken, "Kraken tentacle"));
		assertEquals(40L, qty(caveKraken, "Coins"));
		assertEquals(filesBefore, sessionFiles());
	}

	@Test
	public void sameTickProgressAndBossKill_staysSlayerOwned_bothOrders() throws Exception
	{
		Instant assigned = establishMultiAssignmentSessionEndingOnCaveKraken();
		String id = current().getSessionId();
		String startedAt = current().getStartedAt();

		Instant t1 = assigned.plusSeconds(30);
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t1, TASK, LOCATION, 1, 99).withGameTick(tick));
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t1, "Kraken", 50).withGameTick(tick));
		coordinator.testEnqueueSignal(loot(t1, "Kraken", 3272, "Kraken tentacle", 1).withGameTick(tick));
		coordinator.testProcessTick(++tick, t1);
		assertSlayerOwnsKraken(id, startedAt);

		Instant t2 = t1.plusSeconds(40);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(t2, "Kraken", 51).withGameTick(tick));
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(t2, TASK, LOCATION, 1, 98).withGameTick(tick));
		coordinator.testProcessTick(++tick, t2);
		assertSlayerOwnsKraken(id, startedAt);
		assertEquals(Integer.valueOf(2), currentRecord(current()).getUnitsCompleted());
		assertEquals(1L, qty(currentRecord(current()), "Kraken tentacle"));
	}

	@Test
	public void adjacentTick_bossKillBeforeItsProgress_staysSlayerOwned() throws Exception
	{
		Instant assigned = establishMultiAssignmentSessionEndingOnCaveKraken();
		String id = current().getSessionId();
		String startedAt = current().getStartedAt();
		Instant t = assigned.plusSeconds(30);
		feed(SessionSignal.bossKill(t, "Kraken", 50), t);
		assertSlayerOwnsKraken(id, startedAt);
		feed(SessionSignal.slayerTaskProgress(t.plusMillis(600), TASK, LOCATION, 1, 99), t.plusMillis(600));
		assertSlayerOwnsKraken(id, startedAt);
	}

	@Test
	public void restartWhileOnCaveKrakenTask_rehydratedSuspendedSession_krakenResumesIt() throws Exception
	{
		// Wall-clock based: rehydration judges the resume window against now.
		Instant base = Instant.now().minusSeconds(240);
		feed(SessionSignal.slayerTaskProgress(base, NECH, NECH_LOC, 1, 0), base);
		feed(SessionSignal.slayerTaskCompleted(base.plusSeconds(1), NECH, null), base.plusSeconds(1));
		feed(SessionSignal.slayerTaskAssigned(base.plusSeconds(60), TASK, LOCATION, 100), base.plusSeconds(60));
		feed(SessionSignal.slayerTaskProgress(base.plusSeconds(90), TASK, LOCATION, 1, 99), base.plusSeconds(90));
		String id = current().getSessionId();
		String startedAt = current().getStartedAt();
		coordinator.finalizeForClientShutdownBlocking();

		restartPlugin();
		Instant back = Instant.now().minusSeconds(30);
		feed(SessionSignal.bossActivityContext(back, "Kraken"), back);
		krakenKill(back.plusSeconds(5), 98, 77);
		Session s = current();
		assertEquals("re-enable / login on the task: the same broader session continues", id, s.getSessionId());
		assertEquals(startedAt, s.getStartedAt());
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals(1L, qty(currentRecord(s), "Kraken tentacle"));
		assertEquals(2, s.getAggregates().getSlayerAssignments().size());
	}


	@Test
	public void coldStart_bossKillBeforeAnySlayerSession_ownTaskProgressRefinesTheSameSessionIntoSlayer() throws Exception
	{
		Instant t = T0;
		feed(SessionSignal.bossKill(t, "Kraken", 300), t);
		Session boss = current();
		assertEquals(ActivityType.BOSSING, boss.getActivityIdentity().getActivityType());
		feed(loot(t.plusMillis(600), "Kraken", 3272, "Kraken tentacle", 1), t.plusMillis(600));

		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(2), TASK, LOCATION, 1, 50), t.plusSeconds(2));
		Session s = current();
		assertEquals("refined in place: same id", boss.getSessionId(), s.getSessionId());
		assertEquals(boss.getStartedAt(), s.getStartedAt());
		assertEquals("cave kraken@kraken cove", s.getActivityIdentity().getActivityKey());
		assertEquals("the boss loot seen before the refine seeds the assignment ledger", 1L, qty(currentRecord(s), "Kraken tentacle"));

		krakenKill(t.plusSeconds(40), 49, 301);
		assertEquals(boss.getSessionId(), current().getSessionId());
		assertEquals(2L, qty(currentRecord(current()), "Kraken tentacle"));
		assertNull(coordinator.testInterruptedCandidate());
	}

	@Test
	public void duplicateNativeRecheck_zeroUnitProgress_doesNotDisturbOwnership() throws Exception
	{
		Instant assigned = establishMultiAssignmentSessionEndingOnCaveKraken();
		String id = current().getSessionId();
		String startedAt = current().getStartedAt();
		krakenKill(assigned.plusSeconds(30), 99, 50);
		// A coalesced native recheck re-reporting the same assignment.
		feed(SessionSignal.slayerTaskAssigned(assigned.plusSeconds(40), TASK, LOCATION, 100), assigned.plusSeconds(40));
		assertSlayerOwnsKraken(id, startedAt);
		assertEquals("duplicate assignment is a no-op, not a new instance", 3, current().getAggregates().getSlayerAssignments().size());
		krakenKill(assigned.plusSeconds(80), 98, 51);
		assertSlayerOwnsKraken(id, startedAt);
	}

	@Test
	public void otherRegisteredFamilyBosses_cerberusOnHellhounds_vorkathOnBlueDragons_staySlayerOwned() throws Exception
	{
		String[][] pairs = {{"Hellhounds", "Taverley Dungeon", "Cerberus", "Hellhound"}, {"Blue dragons", "Taverley Dungeon", "Vorkath", "Blue dragon"}};
		Instant t = T0;
		for (String[] p : pairs)
		{
			feed(SessionSignal.slayerTaskAssigned(t, p[0], p[1], 50), t);
			feed(SessionSignal.slayerTaskProgress(t.plusSeconds(10), p[0], p[1], 1, 49), t.plusSeconds(10));
			Session slayer = current();
			assertEquals(ActivityType.SLAYER, slayer.getActivityIdentity().getActivityType());
			feed(SessionSignal.bossActivityContext(t.plusSeconds(20), p[2]), t.plusSeconds(20));
			feed(SessionSignal.slayerTaskProgress(t.plusSeconds(25), p[0], p[1], 1, 48), t.plusSeconds(25));
			feed(SessionSignal.bossKill(t.plusSeconds(27), p[2], 9), t.plusSeconds(27));
			feed(loot(t.plusSeconds(27), p[2], 995, "Coins", 5000), t.plusSeconds(28));
			Session s = current();
			assertEquals(p[2] + " on " + p[0], slayer.getSessionId(), s.getSessionId());
			assertEquals(slayer.getActivityIdentity(), s.getActivityIdentity());
			assertEquals(5000L, qty(currentRecord(s), "Coins"));
			t = t.plusSeconds(600);
		}
	}

	@Test
	public void incompatibleBoss_onCaveKrakenTask_isStillItsOwnSession() throws Exception
	{
		Instant assigned = establishMultiAssignmentSessionEndingOnCaveKraken();
		String id = current().getSessionId();
		feed(SessionSignal.bossKill(assigned.plusSeconds(30), "Zulrah", 700), assigned.plusSeconds(30));
		Session s = current();
		assertEquals(ActivityType.BOSSING, s.getActivityIdentity().getActivityType());
		assertEquals("zulrah", s.getActivityIdentity().getActivityKey());
		assertNotNull("the Slayer session is parked (ACTIVE displaced), unchanged behavior", coordinator.testInterruptedCandidate());
		assertEquals(id, coordinator.testInterruptedCandidate().getSessionId());
	}
}
