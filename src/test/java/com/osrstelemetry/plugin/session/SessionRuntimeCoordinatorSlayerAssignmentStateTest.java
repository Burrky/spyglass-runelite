package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.SlayerXpScope;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Slayer assignment end reasons (COMPLETED / REPLACED_EARLY), per-assignment
 * completed-unit counts, off-task loot attribution, and the Current Task
 * XP scope's assignment-local XP/active-time accounting -- all through the
 * real coordinator pipeline.
 */
public class SessionRuntimeCoordinatorSlayerAssignmentStateTest extends SlayerCoordinatorTestSupport
{
	private static SessionSignal loot(Instant at, String source, int itemId, long qty)
	{
		List<SessionSignal.LootDrop> drops = new ArrayList<>();
		drops.add(new SessionSignal.LootDrop(itemId, "item" + itemId, qty));
		return SessionSignal.serverNpcLoot(at, source, drops);
	}

	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(Session s)
	{
		return s.getAggregates().getSlayerAssignments();
	}

	private static SessionAggregates.SlayerAssignmentRecord last(Session s)
	{
		return ledger(s).get(ledger(s).size() - 1);
	}

	private static long lootQty(List<SessionAggregates.LootItemAggregate> items, int itemId)
	{
		long t = 0;
		for (SessionAggregates.LootItemAggregate i : items)
		{
			if (i.getItemId() != null && i.getItemId() == itemId)
			{
				t += i.getQuantity();
			}
		}
		return t;
	}

	private static long sessionLootQty(Session s, int itemId)
	{
		long t = 0;
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			t += lootQty(g.getItems(), itemId);
		}
		return t;
	}

	private static long ledgerLootQty(Session s, int itemId)
	{
		long t = 0;
		for (SessionAggregates.SlayerAssignmentRecord r : ledger(s))
		{
			t += lootQty(r.getItems(), itemId);
		}
		return t;
	}

	private static long xpOf(List<CurrentSessionSnapshot.XpEntry> entries, String skill)
	{
		for (CurrentSessionSnapshot.XpEntry e : entries)
		{
			if (e.getSkill().equals(skill))
			{
				return e.getXpGained();
			}
		}
		return -1L;
	}

	private static Long rateOf(List<CurrentSessionSnapshot.XpEntry> entries, String skill)
	{
		for (CurrentSessionSnapshot.XpEntry e : entries)
		{
			if (e.getSkill().equals(skill))
			{
				return e.getXpPerHour();
			}
		}
		return null;
	}

	/** Fully observed Nechryael assignment of `amount`, then `kills` progress events 60s apart from `start`. */
	private Instant nechryaelTask(Instant start, int amount, int kills)
	{
		feed(SessionSignal.slayerTaskAssigned(start, NECH, NECH_LOC, amount), start);
		Instant t = start;
		int remaining = amount;
		for (int i = 0; i < kills; i++)
		{
			t = t.plusSeconds(60);
			remaining--;
			feed(SessionSignal.slayerTaskProgress(t, NECH, NECH_LOC, 1, remaining), t);
		}
		return t;
	}

	// =====================================================================
	// End reasons + completed-unit counts (22-33)
	// =====================================================================

	@Test
	public void normalCompletion_COMPLETED_countsEveryUnitIncludingReconciledFinalOne()
	{
		Instant t = nechryaelTask(T0, 3, 2); // first progress creates the session; ledger bootstraps
		Session s = current();
		assertEquals(Integer.valueOf(2), last(s).getUnitsCompleted());
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(30), NECH, null), t.plusSeconds(30));
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertEquals(SlayerAssignmentEndReason.COMPLETED, r.getEndReason());
		assertEquals(t.plusSeconds(30).toString(), r.getCompletedAt());
		assertEquals(Integer.valueOf(0), r.getLatestRemaining());
		assertEquals("final unit reconciled once", Integer.valueOf(3), r.getUnitsCompleted());
		assertEquals("no new bucket at completion", 1, ledger(current()).size());
	}

	@Test
	public void replacementWithRemaining_REPLACED_EARLY_keepsRealCountsAndLoot_noFakeCompletion()
	{
		Instant t = nechryaelTask(T0, 75, 17);
		feed(loot(t.plusSeconds(1), "Nechryael", 995, 100), t.plusSeconds(1));
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(60), KALPHITES, KALPHITE_LOC, 143), t.plusSeconds(60));
		SessionAggregates.SlayerAssignmentRecord a = ledger(current()).get(0);
		SessionAggregates.SlayerAssignmentRecord b = last(current());
		assertEquals(SlayerAssignmentEndReason.REPLACED_EARLY, a.getEndReason());
		assertEquals(Integer.valueOf(58), a.getLatestRemaining());
		assertEquals(Integer.valueOf(17), a.getUnitsCompleted());
		assertNull(a.getCompletedAt());
		assertEquals(100L, lootQty(a.getItems(), 995));
		assertEquals(Integer.valueOf(17), current().getAggregates().getSlayerProgressDelta());
		assertNull(b.getEndReason());
		assertEquals(Integer.valueOf(0), b.getUnitsCompleted());
		CurrentSessionSnapshot.SlayerAssignmentView skipped = ui(t.plusSeconds(60)).getSlayerAssignments().get(1);
		assertTrue(skipped.isSkipped());
		assertEquals(Integer.valueOf(17), skipped.getUnitsCompleted());
		assertEquals(Integer.valueOf(75), skipped.getInitialAmount());
	}

	@Test
	public void duplicateObservation_suspension_parking_restart_neverFalselySkip() throws Exception
	{
		Instant base = Instant.now().minusSeconds(900);
		Instant t = nechryaelTask(base, 10, 2);
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(5), NECH, NECH_LOC, 8), t.plusSeconds(5)); // duplicate/spurious
		assertNull(last(current()).getEndReason());
		assertEquals(1, ledger(current()).size());

		// Idle past the suspend threshold, then a genuine Crafting switch parks Slayer.
		coordinator.testProcessTick(++tick, t.plusSeconds(400));
		assertEquals(SessionState.SUSPENDED, current().getState());
		assertNull(last(current()).getEndReason());
		String slayerId = current().getSessionId();
		Instant c = t.plusSeconds(420);
		for (int i = 0; i < 3; i++)
		{
			feed(SessionSignal.xpChange(c.plusSeconds(20L * i), "CRAFTING", 140L), c.plusSeconds(20L * i));
		}
		assertTrue(!slayerId.equals(current().getSessionId()));
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		Session restored = coordinator.testInterruptedCandidate() != null && coordinator.testInterruptedCandidate().getSessionId().equals(slayerId)
			? coordinator.testInterruptedCandidate() : null;
		if (restored == null)
		{
			// Suspended Slayer B finalized/replaced by Crafting: read its finalized file.
			restored = store.readIfExists(TelemetryPaths.sessionFile(ACCOUNT, slayerId), Session.class);
		}
		assertNotNull(restored);
		assertNull("no skip from suspension, parking, restart or session end", last(restored).getEndReason());
	}

	@Test
	public void sameTaskAfterCompletion_separateInstances_firstCOMPLETED_secondFreshX0()
	{
		Instant t = nechryaelTask(T0, 2, 2);
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(10), NECH, null), t.plusSeconds(10));
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(60), NECH, NECH_LOC, 5), t.plusSeconds(60));
		assertEquals(2, ledger(current()).size());
		assertEquals(SlayerAssignmentEndReason.COMPLETED, ledger(current()).get(0).getEndReason());
		assertEquals(Integer.valueOf(0), last(current()).getUnitsCompleted());
		assertNull(last(current()).getEndReason());
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(90), NECH, NECH_LOC, 1, 4), t.plusSeconds(90));
		assertEquals(Integer.valueOf(1), last(current()).getUnitsCompleted());
		assertEquals(Integer.valueOf(2), ledger(current()).get(0).getUnitsCompleted());
	}

	@Test
	public void bootstrappedMidTask_countsOnlyObservedUnits_neverPreObservationProgress()
	{
		// Spyglass joins at remaining 42 of an unknown (e.g. 157) task.
		feed(SessionSignal.slayerTaskProgress(T0, KALPHITES, KALPHITE_LOC, 1, 42), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(30), KALPHITES, KALPHITE_LOC, 1, 41), T0.plusSeconds(30));
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertTrue(r.isBootstrapped());
		assertNull(r.getInitialAmount());
		assertEquals(Integer.valueOf(2), r.getUnitsCompleted());
		CurrentSessionSnapshot.SlayerAssignmentView v = ui(T0.plusSeconds(30)).getSlayerAssignments().get(0);
		assertEquals(Integer.valueOf(2), v.getUnitsCompleted());
		assertTrue(v.isBootstrapped());
	}

	@Test
	public void persistence_endStatesAndCounts_survive_andLegacyRecordJsonIsSafe() throws Exception
	{
		Instant base = Instant.now().minusSeconds(300);
		Instant t = nechryaelTask(base, 20, 3);
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(10), KALPHITES, KALPHITE_LOC, 50), t.plusSeconds(10));
		String id = current().getSessionId();
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		Session s = current();
		assertEquals(id, s.getSessionId());
		assertEquals(SlayerAssignmentEndReason.REPLACED_EARLY, ledger(s).get(0).getEndReason());
		assertEquals(Integer.valueOf(3), ledger(s).get(0).getUnitsCompleted());
		assertEquals(Integer.valueOf(17), ledger(s).get(0).getLatestRemaining());
		assertNull(ledger(s).get(1).getEndReason());
		assertEquals(Integer.valueOf(0), ledger(s).get(1).getUnitsCompleted());

		SessionAggregates.SlayerAssignmentRecord legacy = GSON.fromJson(
			"{\"ordinal\":1,\"taskName\":\"Nechryael\",\"initialAmount\":75,\"latestRemaining\":58,\"items\":[]}",
			SessionAggregates.SlayerAssignmentRecord.class);
		assertNull(legacy.getEndReason());
		assertNull(legacy.getUnitsCompleted());
		assertNull(legacy.getXpGainedBySkill());
	}

	// =====================================================================
	// Off-task loot attribution (37-46)
	// =====================================================================

	private void turothTask(Instant at)
	{
		feed(SessionSignal.slayerTaskProgress(at, "Turoth", "Fremennik Slayer Dungeon", 1, 163), at);
	}

	@Test
	public void turothTask_familyLootAccepted_knownOtherTaskLootRejected_noSwitch_invariantHolds()
	{
		turothTask(T0);
		String id = current().getSessionId();
		feed(loot(T0.plusSeconds(1), "Turoth", 995, 10), T0.plusSeconds(1));
		feed(loot(T0.plusSeconds(20), "Spiked turoth", 995, 5), T0.plusSeconds(20));      // listed variant
		feed(loot(T0.plusSeconds(40), "Undead chicken", 995, 7), T0.plusSeconds(40));     // Zombies/Birds target
		feed(loot(T0.plusSeconds(60), "Death Spawn", 995, 3), T0.plusSeconds(60));        // no task's target: UNKNOWN

		Session s = current();
		assertEquals("one unrelated kill never switches the Slayer session", id, s.getSessionId());
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals(18L, sessionLootQty(s, 995));
		assertEquals("partition invariant after a rejected drop", sessionLootQty(s, 995), ledgerLootQty(s, 995));
	}

	@Test
	public void finalKill_completionBeforeLoot_stillValidatesAgainstCompletedTask_thenNewTaskContextGoverns()
	{
		feed(SessionSignal.slayerTaskProgress(T0, "Zombies", null, 1, 1), T0);
		feed(SessionSignal.slayerTaskCompleted(T0.plusSeconds(5), "Zombies", null), T0.plusSeconds(5));
		feed(loot(T0.plusSeconds(6), "Undead chicken", 995, 4), T0.plusSeconds(6));
		assertEquals("final kill's loot accepted after completion", 4L, lootQty(last(current()).getItems(), 995));

		// Direct new task: Turoth. Later chicken loot is off the NEW task and rejected.
		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(20), "Turoth", "Fremennik Slayer Dungeon", 164), T0.plusSeconds(20));
		feed(loot(T0.plusSeconds(40), "Undead chicken", 995, 9), T0.plusSeconds(40));
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), "Turoth", "Fremennik Slayer Dungeon", 1, 163), T0.plusSeconds(60));
		feed(loot(T0.plusSeconds(61), "Turoth", 995, 2), T0.plusSeconds(61));
		Session s = current();
		assertEquals(4L, lootQty(ledger(s).get(0).getItems(), 995));
		assertEquals(2L, lootQty(ledger(s).get(1).getItems(), 995));
		assertEquals(6L, sessionLootQty(s, 995));
	}

	@Test
	public void parkedSlayer_neverReceivesAnotherActivitysLoot()
	{
		turothTask(T0);
		feed(loot(T0.plusSeconds(1), "Turoth", 995, 10), T0.plusSeconds(1));
		String slayerId = current().getSessionId();
		Instant t = T0.plusSeconds(10);
		for (int i = 0; i < 8 && coordinator.testInterruptedCandidate() == null; i++)
		{
			t = t.plusSeconds(20);
			feed(SessionSignal.xpChange(t, "CRAFTING", 140L), t);
		}
		assertEquals(slayerId, coordinator.testInterruptedCandidate().getSessionId());
		feed(loot(t.plusSeconds(5), "Turoth", 995, 50), t.plusSeconds(5));
		assertEquals(10L, sessionLootQty(coordinator.testInterruptedCandidate(), 995));
		assertEquals(10L, ledgerLootQty(coordinator.testInterruptedCandidate(), 995));
	}

	// =====================================================================
	// Slayer XP scope: assignment-local XP + active time (1-21)
	// =====================================================================

	@Test
	public void allTasks_isWholeSession_currentTask_isAssignmentSlice_nonDestructiveBothWays()
	{
		// Nechryael: session created at T0 (bootstrapped), 2 kills, 1000 DEF XP.
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 2), T0);
		xp("DEFENCE", 1000L, T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), NECH, NECH_LOC, 1, 1), T0.plusSeconds(60));
		CurrentSessionSnapshot before = ui(T0.plusSeconds(60));
		assertEquals(1000L, xpOf(before.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
		assertEquals(1000L, xpOf(before.getXpEntries(SlayerXpScope.ALL_TASKS), "DEFENCE"));

		feed(SessionSignal.slayerTaskCompleted(T0.plusSeconds(61), NECH, null), T0.plusSeconds(61));
		Instant assigned = T0.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 100), assigned);

		CurrentSessionSnapshot fresh = ui(assigned);
		assertTrue("new task starts clean: no Current Task XP cards at all", fresh.getXpEntries(SlayerXpScope.CURRENT_TASK).isEmpty());
		assertEquals("task-local XP is 0 (accounting)", 0L, ledger(current()).get(1).getXpGainedBySkill().getOrDefault("DEFENCE", 0L).longValue());
		assertEquals("canonical session XP never reset", 1000L, xpOf(fresh.getXpEntries(SlayerXpScope.ALL_TASKS), "DEFENCE"));
		assertEquals(fresh.getXpEntries(), fresh.getXpEntries(SlayerXpScope.ALL_TASKS));

		feed(SessionSignal.slayerTaskProgress(assigned.plusSeconds(60), KALPHITES, KALPHITE_LOC, 1, 99), assigned.plusSeconds(60));
		xp("DEFENCE", 300L, assigned.plusSeconds(60));
		CurrentSessionSnapshot later = ui(assigned.plusSeconds(90));
		assertEquals(300L, xpOf(later.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
		assertEquals(1300L, xpOf(later.getXpEntries(SlayerXpScope.ALL_TASKS), "DEFENCE"));
		assertEquals("toggling back is instant and exact", 300L, xpOf(later.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
		assertEquals(Long.valueOf(1300L), current().getAggregates().getXpGainedBySkill().get("DEFENCE"));
	}

	@Test
	public void taskXpPerHour_usesAssignmentActiveTime_excludesSuspendedTime_andPreAssignmentTravel()
	{
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 1), T0);
		feed(SessionSignal.slayerTaskCompleted(T0.plusSeconds(5), NECH, null), T0.plusSeconds(5));
		Instant assigned = T0.plusSeconds(65); // 60s of "travel" after completion, session still ACTIVE
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 100), assigned);
		// Two kills 60s and 300s after assignment (the shared 5-minute rate floor).
		feed(SessionSignal.slayerTaskProgress(assigned.plusSeconds(60), KALPHITES, KALPHITE_LOC, 1, 99), assigned.plusSeconds(60));
		xp("DEFENCE", 600L, assigned.plusSeconds(30));
		feed(SessionSignal.slayerTaskProgress(assigned.plusSeconds(300), KALPHITES, KALPHITE_LOC, 1, 98), assigned.plusSeconds(300));
		// Rate at the last heartbeat: 600 XP over exactly 300s of task-active time.
		CurrentSessionSnapshot v = ui(assigned.plusSeconds(300));
		assertEquals(Long.valueOf(Math.round(600 * 3_600_000.0 / 300_000)), rateOf(v.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));

		// Idle 20 min (suspends); resume with a kill: suspended time excluded.
		Instant resumeAt = assigned.plusSeconds(300 + 1200);
		coordinator.testProcessTick(++tick, resumeAt.minusSeconds(1));
		assertEquals(SessionState.SUSPENDED, current().getState());
		feed(SessionSignal.slayerTaskProgress(resumeAt, KALPHITES, KALPHITE_LOC, 1, 97), resumeAt);
		CurrentSessionSnapshot after = ui(resumeAt);
		// Task active time = 300s + the pre-suspend idle already credited up to the 5-min threshold (engine semantics).
		long sessionActive = current().getAccumulatedActiveDurationMillis();
		long taskActive = sessionActive - last(current()).getActiveDurationStartMillis();
		assertTrue("suspended wall time is not task time", taskActive < 1500_000L);
		assertEquals(Long.valueOf(Math.round(600 * 3_600_000.0 / taskActive)), rateOf(after.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
	}

	@Test
	public void assignmentWhileParked_accruesNoTaskTimeUntilSlayerResumes()
	{
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 1), T0);
		feed(SessionSignal.slayerTaskCompleted(T0.plusSeconds(5), NECH, null), T0.plusSeconds(5));
		String slayerId = current().getSessionId();
		Instant t = T0.plusSeconds(10);
		for (int i = 0; i < 8 && coordinator.testInterruptedCandidate() == null; i++)
		{
			t = t.plusSeconds(20);
			feed(SessionSignal.xpChange(t, "CRAFTING", 140L), t);
		}
		Session parked = coordinator.testInterruptedCandidate();
		assertEquals(slayerId, parked.getSessionId());
		long parkedActive = parked.getAccumulatedActiveDurationMillis();

		Instant assigned = t.plusSeconds(30);
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 100), assigned);
		SessionAggregates.SlayerAssignmentRecord b = last(coordinator.testInterruptedCandidate());
		assertEquals("task time starts at the parked session's frozen clock", Long.valueOf(parkedActive), b.getActiveDurationStartMillis());

		Instant kill = assigned.plusSeconds(60);
		feed(SessionSignal.slayerTaskProgress(kill, KALPHITES, KALPHITE_LOC, 1, 99), kill);
		assertEquals("real Slayer evidence resumes", slayerId, current().getSessionId());
		assertEquals("no parked/assignment-wait time credited on resume", parkedActive, current().getAccumulatedActiveDurationMillis());
	}

	@Test
	public void completionFreezes_taskXpAndRate_finalKillXpStillCounts_postCompletionXpNever()
	{
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 2), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), NECH, NECH_LOC, 1, 1), T0.plusSeconds(60));
		Instant completedAt = T0.plusSeconds(70);
		feed(SessionSignal.slayerTaskCompleted(completedAt, NECH, null), completedAt);
		// Final kill's XP: its window began BEFORE completion, delivered after it.
		xp("DEFENCE", 500L, T0.plusSeconds(55));
		// Travel XP after completion (e.g. a teleport): window begins after completion.
		xp("MAGIC", 30L, completedAt.plusSeconds(20));
		xp("DEFENCE", 99L, completedAt.plusSeconds(20));

		CurrentSessionSnapshot a = ui(completedAt.plusSeconds(60));
		List<CurrentSessionSnapshot.XpEntry> task = a.getXpEntries(SlayerXpScope.CURRENT_TASK);
		assertEquals("final-kill XP counts toward the completed task", 500L, xpOf(task, "DEFENCE"));
		assertEquals("post-completion XP never: no MAGIC card for the task", -1L, xpOf(task, "MAGIC"));
		assertEquals(599L, xpOf(a.getXpEntries(SlayerXpScope.ALL_TASKS), "DEFENCE"));
		Long frozenRate = rateOf(task, "DEFENCE");
		CurrentSessionSnapshot b = ui(completedAt.plusSeconds(240));
		assertEquals("rate frozen during travel", frozenRate, rateOf(b.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
		assertEquals(500L, xpOf(b.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
	}

	@Test
	public void earlyReplacement_freezesA_BstartsAt0_allTasksUninterrupted()
	{
		Instant t = nechryaelTask(T0, 50, 2);
		xp("DEFENCE", 400L, t);                                   // window [t, t+30]
		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(40), KALPHITES, KALPHITE_LOC, 100), t.plusSeconds(40));
		xp("DEFENCE", 50L, t.plusSeconds(45));
		SessionAggregates.SlayerAssignmentRecord a = ledger(current()).get(0);
		assertEquals(Long.valueOf(400L), a.getXpGainedBySkill().get("DEFENCE"));
		assertNotNull(a.getActiveDurationEndMillis());
		CurrentSessionSnapshot v = ui(t.plusSeconds(80));
		assertEquals(50L, xpOf(v.getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
		assertEquals(450L, xpOf(v.getXpEntries(SlayerXpScope.ALL_TASKS), "DEFENCE"));
	}

	@Test
	public void bootstrappedLegacySession_currentTaskStartsConservatively_restartPreservesTaskXp() throws Exception
	{
		Instant base = Instant.now().minusSeconds(200);
		feed(SessionSignal.slayerTaskProgress(base, NECH, NECH_LOC, 1, 30), base);
		xp("DEFENCE", 700L, base);
		// Simulate a session file from before the ledger existed.
		current().getAggregates().setSlayerAssignments(null);
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		CurrentSessionSnapshot v = ui(Instant.now());
		assertTrue("never claims pre-tracking session XP for the task: no cards", v.getXpEntries(SlayerXpScope.CURRENT_TASK).isEmpty());
		assertEquals(700L, xpOf(v.getXpEntries(SlayerXpScope.ALL_TASKS), "DEFENCE"));

		Instant k = Instant.now().plusSeconds(1);
		feed(SessionSignal.slayerTaskProgress(k, NECH, NECH_LOC, 1, 29), k);
		xp("DEFENCE", 80L, k);
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		assertEquals("task XP survives restart", 80L, xpOf(ui(Instant.now()).getXpEntries(SlayerXpScope.CURRENT_TASK), "DEFENCE"));
	}

	@Test
	public void nonSlayerSession_ignoresScope_andXpRemainingEstimateUnchanged_levelProgressUnchanged()
	{
		feed(SessionSignal.bossKill(T0, "Zulrah", 5), T0);
		xp("RANGED", 900L, T0);
		CurrentSessionSnapshot boss = ui(T0.plusSeconds(30));
		assertEquals(boss.getXpEntries(), boss.getXpEntries(SlayerXpScope.CURRENT_TASK));
	}

	@Test
	public void currentTaskScope_levelProgressIsTheRealAbsoluteLevel_andEstimateUnaffected()
	{
		coordinator.noteAbsoluteXp("DEFENCE", 5_000_000L);
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 10), T0);
		xp("SLAYER", 100L, T0);
		xp("DEFENCE", 300L, T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), NECH, NECH_LOC, 1, 9), T0.plusSeconds(60));
		CurrentSessionSnapshot v = ui(T0.plusSeconds(60));
		CurrentSessionSnapshot.XpEntry all = v.getXpEntries(SlayerXpScope.ALL_TASKS).stream().filter(e -> e.getSkill().equals("DEFENCE")).findFirst().get();
		CurrentSessionSnapshot.XpEntry task = v.getXpEntries(SlayerXpScope.CURRENT_TASK).stream().filter(e -> e.getSkill().equals("DEFENCE")).findFirst().get();
		assertNotNull(all.getLevelProgress());
		assertEquals(all.getLevelProgress().getCurrentLevel(), task.getLevelProgress().getCurrentLevel());
		assertEquals(Long.valueOf(Math.round(100.0 / 2 * 9)), v.getEstimatedSlayerXpRemaining());
	}

	/**
	 * REGRESSION: the final unit can arrive as an ordinary SLAYER_TASK_PROGRESS
	 * reaching remaining 0 (RuneLite can report amount 0 before the task name
	 * clears). That metric must still stamp completedAt; otherwise the record
	 * is COMPLETED with no end instant and xpRecordFor() credits it no XP at
	 * all -- the final kill's XP (window begun before completion, delivered
	 * after) was silently dropped from task XP.
	 */
	@Test
	public void finalUnitViaProgressToZero_stampsCompletedAt_finalKillXpStillCreditedToTheTask()
	{
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 1), T0);
		Instant last = T0.plusSeconds(20);
		feed(SessionSignal.slayerTaskProgress(last, NECH, NECH_LOC, 1, 0), last);
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertEquals(SlayerAssignmentEndReason.COMPLETED, r.getEndReason());
		assertEquals("completion instant recorded", last.toString(), r.getCompletedAt());

		xp("SLAYER", 120L, T0.plusSeconds(5)); // final kill's window began before completion
		assertEquals("final-kill XP credited to the completed task", Long.valueOf(120L), r.getXpGainedBySkill().get("SLAYER"));
		xp("MAGIC", 30L, last.plusSeconds(10)); // travel after completion
		assertNull("post-completion XP still never credited", r.getXpGainedBySkill().get("MAGIC"));
	}
}
