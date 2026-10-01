package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * SMART SLAYER ASSIGNMENT LOOT LEDGER: per-assignment-INSTANCE partition
 * of a Slayer session's own canonical loot, through the real coordinator
 * pipeline (SERVER_NPC_LOOT -> existing session ownership -> session-wide
 * loot -> current assignment bucket).
 */
public class SessionRuntimeCoordinatorSlayerAssignmentLootTest extends SlayerCoordinatorTestSupport
{
	private static SessionSignal loot(Instant at, String source, Object... idNameQty)
	{
		java.util.List<SessionSignal.LootDrop> drops = new java.util.ArrayList<>();
		for (int i = 0; i < idNameQty.length; i += 3)
		{
			drops.add(new SessionSignal.LootDrop((Integer) idNameQty[i], (String) idNameQty[i + 1], ((Number) idNameQty[i + 2]).longValue()));
		}
		return SessionSignal.serverNpcLoot(at, source, drops);
	}

	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(Session s)
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = s.getAggregates().getSlayerAssignments();
		return l == null ? Collections.<SessionAggregates.SlayerAssignmentRecord>emptyList() : l;
	}

	private static long qty(List<SessionAggregates.LootItemAggregate> items, int itemId)
	{
		long total = 0;
		for (SessionAggregates.LootItemAggregate i : items)
		{
			if (i.getItemId() != null && i.getItemId() == itemId)
			{
				total += i.getQuantity();
			}
		}
		return total;
	}

	private static Map<String, Long> sessionLootTotals(Session s)
	{
		Map<String, Long> m = new HashMap<>();
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			for (SessionAggregates.LootItemAggregate i : g.getItems())
			{
				m.merge(String.valueOf(i.getItemId()), i.getQuantity(), Long::sum);
			}
		}
		return m;
	}

	private static Map<String, Long> ledgerLootTotals(Session s)
	{
		Map<String, Long> m = new HashMap<>();
		for (SessionAggregates.SlayerAssignmentRecord a : ledger(s))
		{
			for (SessionAggregates.LootItemAggregate i : a.getItems())
			{
				m.merge(String.valueOf(i.getItemId()), i.getQuantity(), Long::sum);
			}
		}
		return m;
	}

	/** Nechryael kills with remaining 3 -> 1 and one loot drop each (coins 100 + bones 1). */
	private void nechryaelKills(Instant base, int fromRemaining, int kills)
	{
		int remaining = fromRemaining;
		for (int i = 0; i < kills; i++)
		{
			Instant t = base.plusSeconds(20L * i);
			remaining--;
			feed(SessionSignal.slayerTaskProgress(t, NECH, NECH_LOC, 1, remaining), t);
			feed(loot(t.plusSeconds(1), "Nechryael", 995, "Coins", 100, 526, "Bones", 1), t.plusSeconds(1));
		}
	}

	private void completeNechryael(Instant at)
	{
		feed(SessionSignal.slayerTaskCompleted(at, NECH, null), at);
	}

	// 1 ------------------------------------------------------------------
	@Test
	public void firstAssignment_exactlyOneBucket_onlyOnceARealSlayerSessionExists()
	{
		feed(SessionSignal.slayerTaskAssigned(T0, NECH, NECH_LOC, 4), T0);
		assertNull("assignment state alone creates nothing", current());

		nechryaelKills(T0.plusSeconds(30), 4, 1);
		Session s = current();
		assertEquals(1, ledger(s).size());
		SessionAggregates.SlayerAssignmentRecord a = ledger(s).get(0);
		assertEquals(1, a.getOrdinal());
		assertEquals(NECH, a.getTaskName());
		assertEquals("nechryael@catacombs of kourend", a.getAssignmentKey());
		assertFalse("the ASSIGNED observed just before the session existed makes it fully observed", a.isBootstrapped());
		assertEquals(Integer.valueOf(4), a.getInitialAmount());
		assertEquals(T0.toString(), a.getAssignedAt());
		assertEquals(Integer.valueOf(3), a.getLatestRemaining());
		assertEquals(100L, qty(a.getItems(), 995));
	}

	// 2 ------------------------------------------------------------------
	@Test
	public void duplicateAssignedObservation_noDuplicateBucket()
	{
		nechryaelKills(T0, 4, 3);
		completeNechryael(T0.plusSeconds(70));
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(100), KALPHITES, KALPHITE_LOC, 143), T0.plusSeconds(100));
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(101), KALPHITES, KALPHITE_LOC, 143), T0.plusSeconds(101));
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(130), KALPHITES, KALPHITE_LOC, 1, 142), T0.plusSeconds(130));
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(140), KALPHITES, KALPHITE_LOC, 142), T0.plusSeconds(140));
		assertEquals(2, ledger(current()).size());
		assertEquals(Integer.valueOf(142), ledger(current()).get(1).getLatestRemaining());
	}

	// 3 ------------------------------------------------------------------
	@Test
	public void taskCompletion_sameBucketRetained_remainingZero_lootPreserved()
	{
		nechryaelKills(T0, 4, 3);
		String id = current().getSessionId();
		completeNechryael(T0.plusSeconds(70));
		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals(1, ledger(s).size());
		assertEquals(Integer.valueOf(0), ledger(s).get(0).getLatestRemaining());
		assertNull("still the current assignment until a new one arrives", ledger(s).get(0).getEndedAt());
		assertEquals(300L, qty(ledger(s).get(0).getItems(), 995));
	}

	// 4 + 5 --------------------------------------------------------------
	@Test
	public void newTaskAfterCompletion_newEmptyBucket_sameTaskNameTwice_isTwoInstances()
	{
		nechryaelKills(T0, 4, 3);
		String id = current().getSessionId();
		completeNechryael(T0.plusSeconds(70));

		// The SAME task (name + location) assigned again: a distinct instance.
		Instant assigned = T0.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(assigned, NECH, NECH_LOC, 5), assigned);
		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals(2, ledger(s).size());
		SessionAggregates.SlayerAssignmentRecord first = ledger(s).get(0);
		SessionAggregates.SlayerAssignmentRecord second = ledger(s).get(1);
		assertEquals(1, first.getOrdinal());
		assertEquals(2, second.getOrdinal());
		assertEquals(NECH, second.getTaskName());
		assertEquals(first.getAssignmentKey(), second.getAssignmentKey());
		assertEquals(assigned.toString(), first.getEndedAt());
		assertEquals(Integer.valueOf(0), first.getLatestRemaining());
		assertTrue("new instance starts at 0 loot", second.getItems().isEmpty());
		assertEquals(Integer.valueOf(5), second.getInitialAmount());
		assertFalse(second.isBootstrapped());

		nechryaelKills(assigned.plusSeconds(30), 5, 2);
		assertEquals("instance #1 untouched", 300L, qty(ledger(current()).get(0).getItems(), 995));
		assertEquals("instance #2 gets only its own loot", 200L, qty(ledger(current()).get(1).getItems(), 995));
	}

	// 6 ------------------------------------------------------------------
	@Test
	public void directReplacement_AtoB_Apreserved_Bnew_noFakeCompletion()
	{
		nechryaelKills(T0, 4, 2); // remaining 2
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(60), KALPHITES, KALPHITE_LOC, 143), T0.plusSeconds(60));
		List<SessionAggregates.SlayerAssignmentRecord> l = ledger(current());
		assertEquals(2, l.size());
		assertEquals("A keeps its real remaining -- no fabricated completion", Integer.valueOf(2), l.get(0).getLatestRemaining());
		assertEquals(200L, qty(l.get(0).getItems(), 995));
		assertEquals(KALPHITES, l.get(1).getTaskName());
		assertTrue(l.get(1).getItems().isEmpty());
	}

	// 7 + 8 + 14 ---------------------------------------------------------
	@Test
	public void lootAttribution_onceIntoSessionAndOnceIntoCurrentBucket_totalsAgree_multiItemRepeated()
	{
		nechryaelKills(T0, 4, 3);
		Session s = current();
		assertEquals("3 loot groups in the session", 3, s.getAggregates().getLootDrops().size());
		SessionAggregates.SlayerAssignmentRecord a = ledger(s).get(0);
		assertEquals(2, a.getItems().size());
		assertEquals(300L, qty(a.getItems(), 995));
		assertEquals(3L, qty(a.getItems(), 526));

		completeNechryael(T0.plusSeconds(70));
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(100), KALPHITES, KALPHITE_LOC, 143), T0.plusSeconds(100));
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(130), KALPHITES, KALPHITE_LOC, 1, 142), T0.plusSeconds(130));
		feed(loot(T0.plusSeconds(131), "Kalphite Worker", 995, "Coins", 7, 1123, "Adamant arrow", 25), T0.plusSeconds(131));
		feed(loot(T0.plusSeconds(151), "Kalphite Worker", 995, "Coins", 3), T0.plusSeconds(151));

		s = current();
		assertEquals(10L, qty(ledger(s).get(1).getItems(), 995));
		assertEquals(25L, qty(ledger(s).get(1).getItems(), 1123));
		assertEquals("partition invariant", sessionLootTotals(s), ledgerLootTotals(s));
	}

	// 9 ------------------------------------------------------------------
	@Test
	public void finalKill_completionBeforeLoot_lootStaysWithCompletedAssignment()
	{
		nechryaelKills(T0, 4, 3); // remaining 1
		Instant last = T0.plusSeconds(70);
		// Real order: the final unit disappears (COMPLETED, no trailing PROGRESS), THEN the drop.
		feed(SessionSignal.slayerTaskCompleted(last, NECH, null), last);
		feed(loot(last.plusSeconds(1), "Nechryael", 995, "Coins", 100, 526, "Bones", 1), last.plusSeconds(1));
		assertEquals(400L, qty(ledger(current()).get(0).getItems(), 995));

		// Travel to the master; new task -- the final kill's loot stays put.
		feed(SessionSignal.slayerTaskAssigned(last.plusSeconds(60), KALPHITES, KALPHITE_LOC, 143), last.plusSeconds(60));
		assertEquals(400L, qty(ledger(current()).get(0).getItems(), 995));
		assertTrue(ledger(current()).get(1).getItems().isEmpty());
	}

	// 10 -----------------------------------------------------------------
	@Test
	public void caveKrakenTask_krakenBossLoot_ownedByTheCaveKrakenAssignmentBucket()
	{
		feed(SessionSignal.slayerTaskProgress(T0, "Cave Kraken", "Kraken Cove", 1, 99), T0);
		feed(loot(T0.plusSeconds(1), "Cave kraken", 995, "Coins", 50), T0.plusSeconds(1));
		Session slayer = current();
		assertEquals(ActivityType.SLAYER, slayer.getActivityIdentity().getActivityType());

		int remaining = 99;
		for (int kill = 1; kill <= 3; kill++)
		{
			Instant t = T0.plusSeconds(60L * kill);
			remaining--;
			feed(SessionSignal.npcDeath(t), t);
			feed(SessionSignal.slayerTaskProgress(t.plusMillis(1200), "Cave Kraken", "Kraken Cove", 1, remaining), t.plusMillis(1200));
			feed(SessionSignal.bossKill(t.plusMillis(3000), "Kraken", 10 + kill), t.plusMillis(3000));
			feed(loot(t.plusMillis(3000), "Kraken", 995, "Coins", 1000), t.plusMillis(3600));
			assertEquals("Slayer continuity", slayer.getSessionId(), current().getSessionId());
		}
		Session s = current();
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals("loot owned once, by the Slayer session", 3050L, sessionLootTotals(s).get("995").longValue());
		assertEquals("the Cave Kraken bucket owns the boss loot too (bucket = partition of session loot)",
			3050L, qty(ledger(s).get(0).getItems(), 995));
		assertNull(coordinator.testInterruptedCandidate());
	}


	/** Slayer A (Nechryael, completed) parked behind a genuine Crafting switch. */
	private Session parkSlayerBehindCrafting()
	{
		nechryaelKills(T0, 4, 3);
		completeNechryael(T0.plusSeconds(70));
		Session a = current();
		Instant t = T0.plusSeconds(80);
		for (int i = 0; i < 8 && coordinator.testInterruptedCandidate() == null; i++)
		{
			t = t.plusSeconds(20);
			feed(SessionSignal.xpChange(t, "CRAFTING", 140L), t);
		}
		assertEquals(a.getSessionId(), coordinator.testInterruptedCandidate().getSessionId());
		return a;
	}

	// 11 + 12 ------------------------------------------------------------
	@Test
	public void parkedSlayer_newAssignmentGetsBucket_craftingKeepsOwnership_realKillResumes_newLootInNewBucket()
	{
		Session a = parkSlayerBehindCrafting();
		String craftingId = current().getSessionId();
		long aBucketCoins = qty(ledger(a).get(0).getItems(), 995);

		Instant assigned = T0.plusSeconds(260);
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 143), assigned);
		assertEquals("Crafting stays current", craftingId, current().getSessionId());
		Session parked = coordinator.testInterruptedCandidate();
		assertEquals(a.getSessionId(), parked.getSessionId());
		assertEquals(2, ledger(parked).size());
		assertTrue(ledger(parked).get(1).getItems().isEmpty());
		assertTrue("Crafting has no Slayer ledger", ledger(current()).isEmpty());
		assertEquals(aBucketCoins, qty(ledger(parked).get(0).getItems(), 995));

		Instant k = assigned.plusSeconds(20);
		feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, 142), k);
		feed(loot(k.plusSeconds(1), "Kalphite Worker", 995, "Coins", 9), k.plusSeconds(1));
		assertEquals("real Slayer evidence resumes A", a.getSessionId(), current().getSessionId());
		assertEquals(9L, qty(ledger(current()).get(1).getItems(), 995));
		assertEquals(aBucketCoins, qty(ledger(current()).get(0).getItems(), 995));
		assertEquals(sessionLootTotals(current()), ledgerLootTotals(current()));
	}

	// 13 -----------------------------------------------------------------
	@Test
	public void zeroLootAssignment_bucketPersists()
	{
		for (int i = 0; i < 2; i++)
		{
			feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(20L * i), NECH, NECH_LOC, 1, 2 - i), T0.plusSeconds(20L * i));
		}
		completeNechryael(T0.plusSeconds(50));
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(90), KALPHITES, KALPHITE_LOC, 143), T0.plusSeconds(90));
		List<SessionAggregates.SlayerAssignmentRecord> l = ledger(current());
		assertEquals(2, l.size());
		assertTrue(l.get(0).getItems().isEmpty());
		assertTrue(l.get(1).getItems().isEmpty());
	}

	// 15 + 16 ------------------------------------------------------------
	@Test
	public void persistenceRestart_idsOrderLootSurvive_noDuplicateBucket_currentStillCorrect() throws Exception
	{
		Instant base = Instant.now().minusSeconds(240);
		nechryaelKills(base, 4, 3);
		completeNechryael(base.plusSeconds(70));
		Instant assigned = base.plusSeconds(100);
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 143), assigned);
		feed(SessionSignal.slayerTaskProgress(assigned.plusSeconds(10), KALPHITES, KALPHITE_LOC, 1, 142), assigned.plusSeconds(10));
		feed(loot(assigned.plusSeconds(11), "Kalphite Worker", 995, "Coins", 9), assigned.plusSeconds(11));
		String id = current().getSessionId();
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals("no duplicate bucket on restore", 2, ledger(s).size());
		assertEquals(Arrays.asList(1, 2), Arrays.asList(ledger(s).get(0).getOrdinal(), ledger(s).get(1).getOrdinal()));
		assertEquals(300L, qty(ledger(s).get(0).getItems(), 995));
		assertEquals(9L, qty(ledger(s).get(1).getItems(), 995));

		Instant k = Instant.now().plusSeconds(2);
		feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, 141), k);
		feed(loot(k.plusSeconds(1), "Kalphite Worker", 995, "Coins", 1), k.plusSeconds(1));
		assertEquals(id, current().getSessionId());
		assertEquals("current assignment still #2 after restart", 10L, qty(ledger(current()).get(1).getItems(), 995));
	}

	@Test
	public void legacySessionWithoutLedger_restoredWithOneBootstrappedBucketHoldingAllItsLoot() throws Exception
	{
		Instant base = Instant.now().minusSeconds(120);
		nechryaelKills(base, 4, 3);
		String id = current().getSessionId();
		current().getAggregates().setSlayerAssignments(null); // exactly what a pre-ledger file contains
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals(1, ledger(s).size());
		assertTrue(ledger(s).get(0).isBootstrapped());
		assertEquals(sessionLootTotals(s), ledgerLootTotals(s));

		SessionAggregates old = GSON.fromJson("{\"lootDrops\":[{\"sourceName\":\"Nechryael\",\"items\":[{\"itemId\":995,\"quantity\":5}]}]}", SessionAggregates.class);
		assertNull("old JSON loads without the ledger field", old.getSlayerAssignments());
		SessionAggregateUpdater.ensureSlayerAssignmentLedger(old, ActivitySignalClassifier.skillingIdentity("CRAFTING"), 0L);
		assertNull("never bootstrapped for a non-Slayer identity", old.getSlayerAssignments());
	}

	// 17 -----------------------------------------------------------------
	@Test
	public void snapshots_areDeepCopies_andReadOnly()
	{
		nechryaelKills(T0, 4, 2);
		SessionSnapshot snap = coordinator.getCurrentSessionSnapshot();
		List<SessionAggregates.SlayerAssignmentRecord> copied = snap.getSlayerAssignments();
		assertEquals(1, copied.size());
		assertNotEquals("never the engine's live object", System.identityHashCode(ledger(current()).get(0)), System.identityHashCode(copied.get(0)));

		feed(loot(T0.plusSeconds(50), "Nechryael", 995, "Coins", 100), T0.plusSeconds(50));
		assertEquals("snapshot unaffected by later mutation", 200L, qty(copied.get(0).getItems(), 995));
		try
		{
			copied.get(0).getItems().clear();
			fail("snapshot item list must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// ok
		}
		try
		{
			copied.clear();
			fail("snapshot ledger must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// ok
		}

		CurrentSessionSnapshot ui = CurrentSessionSnapshot.from(snap, T0.plusSeconds(50));
		assertEquals(1, ui.getSlayerAssignments().size());
		try
		{
			ui.getSlayerAssignments().get(0).getLootEntries().clear();
			fail("UI loot entries must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// ok
		}
	}
}
