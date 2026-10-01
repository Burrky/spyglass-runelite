package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * SMART SLAYER TASK STATE: one broader Slayer session, a mutable current
 * assignment (ACTIVE_TASK -> AWAITING_TASK -> ACTIVE_TASK, or a direct
 * task replacement), driven only by authoritative SLAYER_TASK_* signals
 * (SlayerCollector's varp/varbit-derived task state) through the real
 * coordinator pipeline. Assignment state is never activity evidence.
 */
public class SessionRuntimeCoordinatorSmartSlayerTaskStateTest extends SlayerCoordinatorTestSupport
{
	private long finalizedSessionFiles() throws Exception
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

	private static long slayerXp(Session s)
	{
		Long v = s.getAggregates().getXpGainedBySkill().get("SLAYER");
		return v == null ? 0L : v;
	}

	/** Nechryael task (remaining 4 -> 1) with Slayer XP and loot, ending ACTIVE with 1 remaining. */
	private Session establishNechryaelTaskWithOneRemaining(Instant base)
	{
		int remaining = 4;
		for (int i = 0; i < 3; i++)
		{
			Instant t = base.plusSeconds(20L * i);
			remaining--;
			feed(SessionSignal.slayerTaskProgress(t, NECH, NECH_LOC, 1, remaining), t);
			feed(SessionSignal.xpChange(t.plusSeconds(1), "SLAYER", 105L), t.plusSeconds(1));
			feed(SessionSignal.serverNpcLoot(t.plusSeconds(2), "Nechryael",
				Collections.singletonList(new SessionSignal.LootDrop(995, "Coins", 100))), t.plusSeconds(2));
		}
		Session s = coordinator.testCurrentSession();
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		assertEquals(Integer.valueOf(1), s.getAggregates().getLatestSlayerCurrentRemaining());
		return s;
	}

	/** Final kill: RuneLite reports no trailing 1 -> 0 PROGRESS, just the task disappearing (COMPLETED). */
	private void completeTask(Instant at)
	{
		feed(SessionSignal.xpChange(at, "SLAYER", 105L), at);
		feed(SessionSignal.slayerTaskCompleted(at.plusSeconds(1), NECH, null), at.plusSeconds(1));
	}

	// 1 ------------------------------------------------------------------
	@Test
	public void activeTask_toAwaitingTask_sameSession_noFinalization_aggregatesRetained() throws Exception
	{
		Session a = establishNechryaelTaskWithOneRemaining(T0);
		String id = a.getSessionId();
		String startedAt = a.getStartedAt();
		long lootGroups = a.getAggregates().getLootDrops().size();

		completeTask(T0.plusSeconds(70));

		Session s = coordinator.testCurrentSession();
		assertEquals(id, s.getSessionId());
		assertEquals(startedAt, s.getStartedAt());
		assertEquals(SessionState.ACTIVE, s.getState());
		assertEquals(Integer.valueOf(0), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("final unit reconciled exactly once", Integer.valueOf(4), s.getAggregates().getSlayerProgressDelta());
		assertEquals(420L, slayerXp(s));
		assertEquals(lootGroups, s.getAggregates().getLootDrops().size());
		assertEquals(0, finalizedSessionFiles());
		CurrentSessionSnapshot view = ui(T0.plusSeconds(72));
		assertEquals("AWAITING_TASK: remaining 0", Integer.valueOf(0), view.getSlayerCurrentRemaining());
		assertTrue("existing 'Task complete -- Grab a new task' presentation", view.isSlayerTaskComplete());
	}

	// 2 ------------------------------------------------------------------
	@Test
	public void awaitingTask_toActiveTask_newAssignmentInstalledInPlace_noResetNoChurn() throws Exception
	{
		Session a = establishNechryaelTaskWithOneRemaining(T0);
		String id = a.getSessionId();
		String startedAt = a.getStartedAt();
		completeTask(T0.plusSeconds(70));
		long durationBefore = coordinator.testCurrentSession().getAccumulatedActiveDurationMillis();
		long lootBefore = coordinator.testCurrentSession().getAggregates().getLootDrops().size();

		// Travel to a master, new task (authoritative task-state).
		Instant assignedAt = T0.plusSeconds(160);
		feed(SessionSignal.slayerTaskAssigned(assignedAt, KALPHITES, KALPHITE_LOC, 143), assignedAt);

		Session s = coordinator.testCurrentSession();
		assertEquals("same logical Slayer session", id, s.getSessionId());
		assertEquals(startedAt, s.getStartedAt());
		assertEquals("assignment is not activity: no duration credited", durationBefore, s.getAccumulatedActiveDurationMillis());
		assertEquals("kalphites@kalphite lair", s.getActivityIdentity().getActivityKey());
		assertEquals(KALPHITES, s.getActivityIdentity().getDisplayName());
		assertEquals(Integer.valueOf(143), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("session task-unit total retained", Integer.valueOf(4), s.getAggregates().getSlayerProgressDelta());
		assertEquals(420L, slayerXp(s));
		assertEquals(lootBefore, s.getAggregates().getLootDrops().size());
		CurrentSessionSnapshot view = ui(assignedAt);
		assertEquals("ACTIVE_TASK: remaining is the new assignment's amount", Integer.valueOf(143), view.getSlayerCurrentRemaining());
		assertFalse("'Grab a new task' disappears", view.isSlayerTaskComplete());
		assertEquals("Kalphites", view.getDisplayName());

		// First kill on the new task: ordinary same-identity heartbeat, never a switch.
		Instant k = assignedAt.plusSeconds(90);
		feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, 142), k);
		s = coordinator.testCurrentSession();
		assertEquals(id, s.getSessionId());
		assertEquals(startedAt, s.getStartedAt());
		assertEquals(Integer.valueOf(142), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals(Integer.valueOf(5), s.getAggregates().getSlayerProgressDelta());
		assertNull("nothing parked -- no churn", coordinator.testInterruptedCandidate());
		assertEquals(0, finalizedSessionFiles());
	}

	// 3 ------------------------------------------------------------------
	@Test
	public void directTaskReplacement_activeAtoActiveB_sameSession_noFabricatedCompletion() throws Exception
	{
		Session a = establishNechryaelTaskWithOneRemaining(T0);
		String id = a.getSessionId();

		// SlayerCollector emits ASSIGNED (never COMPLETED) when the task identity changes while a task is held.
		Instant replacedAt = T0.plusSeconds(80);
		feed(SessionSignal.slayerTaskAssigned(replacedAt, KALPHITES, KALPHITE_LOC, 143), replacedAt);

		Session s = coordinator.testCurrentSession();
		assertEquals(id, s.getSessionId());
		assertEquals("kalphites@kalphite lair", s.getActivityIdentity().getActivityKey());
		assertEquals(Integer.valueOf(143), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("no fabricated final unit for the skipped task", Integer.valueOf(3), s.getAggregates().getSlayerProgressDelta());
		assertEquals(0, finalizedSessionFiles());
	}

	// 4 ------------------------------------------------------------------
	@Test
	public void duplicateAssignmentObservations_areIdempotent_noResetOfInProgressCount() throws Exception
	{
		Session a = establishNechryaelTaskWithOneRemaining(T0);
		completeTask(T0.plusSeconds(70));
		Instant assignedAt = T0.plusSeconds(160);
		feed(SessionSignal.slayerTaskAssigned(assignedAt, KALPHITES, KALPHITE_LOC, 143), assignedAt);
		feed(SessionSignal.slayerTaskAssigned(assignedAt.plusSeconds(1), KALPHITES, KALPHITE_LOC, 143), assignedAt.plusSeconds(1));
		Instant k = assignedAt.plusSeconds(30);
		feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, 142), k);
		Integer baseline = coordinator.testCurrentSession().getAggregates().getSlayerAssignmentBaselineProgressDelta();

		// A later re-observation of the SAME in-progress assignment (e.g. a spurious login-time ASSIGNED).
		feed(SessionSignal.slayerTaskAssigned(k.plusSeconds(5), KALPHITES, KALPHITE_LOC, 142), k.plusSeconds(5));

		Session s = coordinator.testCurrentSession();
		assertEquals(a.getSessionId(), s.getSessionId());
		assertEquals(Integer.valueOf(142), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals(Integer.valueOf(5), s.getAggregates().getSlayerProgressDelta());
		assertEquals("baseline not re-recorded", baseline, s.getAggregates().getSlayerAssignmentBaselineProgressDelta());
		assertNull(coordinator.testInterruptedCandidate());
		assertEquals(0, finalizedSessionFiles());
	}

	// 5 ------------------------------------------------------------------
	@Test
	public void loginTaskStateAlone_neverCreatesASession()
	{
		feed(SessionSignal.slayerTaskAssigned(T0, KALPHITES, KALPHITE_LOC, 143), T0);
		assertNull("a known assignment alone is not 'doing Slayer'", coordinator.testCurrentSession());
		assertNull(coordinator.testInterruptedCandidate());
	}

	// 6 ------------------------------------------------------------------
	@Test
	public void nonSlayerCurrent_assignmentNeverStealsOwnership()
	{
		feed(SessionSignal.xpChange(T0, "CRAFTING", 140L), T0);
		Session crafting = coordinator.testCurrentSession();
		assertEquals(ActivityType.SKILLING, crafting.getActivityIdentity().getActivityType());

		feed(SessionSignal.slayerTaskAssigned(T0.plusSeconds(10), KALPHITES, KALPHITE_LOC, 143), T0.plusSeconds(10));

		Session s = coordinator.testCurrentSession();
		assertEquals(crafting.getSessionId(), s.getSessionId());
		assertEquals("crafting", s.getActivityIdentity().getActivityKey());
		assertNull(s.getAggregates().getLatestSlayerCurrentRemaining());
		assertNull(coordinator.testInterruptedCandidate());
	}

	/** Slayer A (awaiting) -> genuine Crafting switch that parks A. Returns A's id. */
	private String parkAwaitingSlayerBehindCrafting()
	{
		Session a = establishNechryaelTaskWithOneRemaining(T0);
		completeTask(T0.plusSeconds(70));
		Instant t = T0.plusSeconds(80);
		for (int i = 0; i < 8 && coordinator.testInterruptedCandidate() == null; i++)
		{
			t = t.plusSeconds(20);
			feed(SessionSignal.xpChange(t, "CRAFTING", 140L), t);
		}
		assertNotNull("setup: genuine Crafting switch parks Slayer A", coordinator.testInterruptedCandidate());
		assertEquals(a.getSessionId(), coordinator.testInterruptedCandidate().getSessionId());
		assertEquals("crafting", coordinator.testCurrentSession().getActivityIdentity().getActivityKey());
		return a.getSessionId();
	}

	// 7 ------------------------------------------------------------------
	@Test
	public void interruptedCandidate_assignmentAloneNeverResurrects_onlyRealSlayerActivityDoes() throws Exception
	{
		String aId = parkAwaitingSlayerBehindCrafting();
		String craftingId = coordinator.testCurrentSession().getSessionId();

		Instant assignedAt = T0.plusSeconds(260);
		feed(SessionSignal.slayerTaskAssigned(assignedAt, KALPHITES, KALPHITE_LOC, 143), assignedAt);

		assertEquals("Crafting keeps ownership", craftingId, coordinator.testCurrentSession().getSessionId());
		Session parked = coordinator.testInterruptedCandidate();
		assertNotNull("A is still just parked -- not resumed by task state", parked);
		assertEquals(aId, parked.getSessionId());
		assertEquals("parked Slayer session carries the new assignment", "kalphites@kalphite lair", parked.getActivityIdentity().getActivityKey());
		assertEquals(Integer.valueOf(143), parked.getAggregates().getLatestSlayerCurrentRemaining());

		// Genuine return to Slayer (real kill on the new task) inside A's window: ordinary A -> B -> A.
		Instant k = assignedAt.plusSeconds(20);
		feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, 142), k);
		Session s = coordinator.testCurrentSession();
		assertEquals("same broader Slayer session resumes on real activity", aId, s.getSessionId());
		assertEquals(Integer.valueOf(142), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("Crafting finalized separately, exactly once", 1, finalizedSessionFiles());
	}

	@Test
	public void engineRule_parkedSlayerCandidate_reKeyedOnlyInsideItsOwnWindow_neverResumed()
	{
		ActivityIdentity nech = ActivitySignalClassifier.slayerIdentity(NECH, NECH_LOC);
		ActivityIdentity kalphites = ActivitySignalClassifier.slayerIdentity(KALPHITES, KALPHITE_LOC);
		ActivityIdentity crafting = ActivitySignalClassifier.skillingIdentity("CRAFTING");

		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		String aId = engine.onQualifyingActivity(nech, T0, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC).getCurrent().getSessionId();
		Instant t1 = T0.plusSeconds(30);
		engine.onQualifyingActivity(crafting, t1, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		assertEquals(aId, engine.getInterruptedCandidate().getSessionId());
		Instant expiresAt = engine.getInterruptedCandidateExpiresAt();

		SessionLifecycleEngine.SlayerAssignmentUpdate late = engine.reassignSlayerTask(kalphites, expiresAt.plusSeconds(1));
		assertNull("after the candidate's own window: no-op", late.getTarget());
		assertEquals(nech, engine.getInterruptedCandidate().getActivityIdentity());

		SessionLifecycleEngine.SlayerAssignmentUpdate atBoundary = engine.reassignSlayerTask(kalphites, expiresAt);
		assertNotNull(atBoundary.getTarget());
		assertFalse(atBoundary.isTargetCurrent());
		assertEquals(kalphites, engine.getInterruptedCandidate().getActivityIdentity());
		assertEquals("never resumed by task state", crafting, engine.getCurrentSession().getActivityIdentity());
		assertEquals("window/timestamps untouched", expiresAt, engine.getInterruptedCandidateExpiresAt());

		SessionLifecycleEngine.SlayerAssignmentUpdate nonSlayer = engine.reassignSlayerTask(crafting, T0);
		assertNull("non-Slayer identities are never installed as assignments", nonSlayer.getTarget());
	}

	@Test
	public void engineRule_suspendedSlayerCurrent_reKeyedInsideResumeWindow_withoutResumingOrCreditingTime()
	{
		ActivityIdentity nech = ActivitySignalClassifier.slayerIdentity(NECH, NECH_LOC);
		ActivityIdentity kalphites = ActivitySignalClassifier.slayerIdentity(KALPHITES, KALPHITE_LOC);
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		Session a = engine.onQualifyingActivity(nech, T0, Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC).getCurrent();
		engine.advanceTime(T0.plusSeconds(400)); // idle past 5 min -> SUSPENDED
		assertEquals(SessionState.SUSPENDED, a.getState());
		String lastActive = a.getLastActiveAt();
		long duration = a.getAccumulatedActiveDurationMillis();

		SessionLifecycleEngine.SlayerAssignmentUpdate u = engine.reassignSlayerTask(kalphites, T0.plusSeconds(600));
		assertTrue(u.isTargetCurrent());
		assertTrue(u.isIdentityChanged());
		assertEquals(SessionState.SUSPENDED, a.getState());
		assertEquals(lastActive, a.getLastActiveAt());
		assertEquals(duration, a.getAccumulatedActiveDurationMillis());
		assertEquals(kalphites, a.getActivityIdentity());

		assertNull("past the resume window: no-op", engine.reassignSlayerTask(nech, a.resumeWindowExpiresAtInstant().plusSeconds(1)).getTarget());
		assertFalse("same assignment again: idempotent", engine.reassignSlayerTask(kalphites, T0.plusSeconds(601)).isIdentityChanged());
	}

	// 8 + 9 --------------------------------------------------------------
	@Test
	public void newAssignment_immediatelyDrivesBossAffinity_krakenStaysInsideTheBroaderSlayerSession() throws Exception
	{
		Session a = establishNechryaelTaskWithOneRemaining(T0);
		completeTask(T0.plusSeconds(70));
		Instant assignedAt = T0.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(assignedAt, "Cave Kraken", "Kraken Cove", 100), assignedAt);
		assertEquals(a.getSessionId(), coordinator.testCurrentSession().getSessionId());
		assertEquals("cave kraken@kraken cove", coordinator.testCurrentSession().getActivityIdentity().getActivityKey());

		// Kraken boss on the NEW Cave Kraken task: the new assignment's family
		// immediately makes the boss compatible -- Slayer owns every kill.
		int remaining = 100;
		for (int kill = 1; kill <= 4; kill++)
		{
			Instant t = assignedAt.plusSeconds(60L * kill);
			remaining--;
			feed(SessionSignal.npcDeath(t), t);
			feed(SessionSignal.slayerTaskProgress(t.plusMillis(1200), "Cave Kraken", "Kraken Cove", 1, remaining), t.plusMillis(1200));
			feed(SessionSignal.bossKill(t.plusMillis(3000), "Kraken", 10 + kill), t.plusMillis(3000));
			Session s = coordinator.testCurrentSession();
			assertEquals(a.getSessionId(), s.getSessionId());
			assertEquals(a.getStartedAt(), s.getStartedAt());
			assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());
		}
		assertNull("Slayer was never parked", coordinator.testInterruptedCandidate());
		assertEquals(Integer.valueOf(remaining), coordinator.testCurrentSession().getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals("Nechryael + Cave Kraken assignments in the one session", 2,
			coordinator.testCurrentSession().getAggregates().getSlayerAssignments().size());
		assertEquals(0, finalizedSessionFiles());
	}


	// 10 -----------------------------------------------------------------
	@Test
	public void xpEstimate_isPerAssignment_neverBlendedAcrossTasks()
	{
		establishNechryaelTaskWithOneRemaining(T0); // 3 units, 315 Slayer XP (105/unit)
		completeTask(T0.plusSeconds(70));          // 4 units, 420 XP
		Instant assignedAt = T0.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(assignedAt, KALPHITES, KALPHITE_LOC, 100), assignedAt);
		assertNull("no estimate before this assignment has its own >=2 units", ui(assignedAt).getEstimatedSlayerXpRemaining());

		Instant k = assignedAt.plusSeconds(30);
		feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, 99), k);
		feed(SessionSignal.xpChange(k.plusSeconds(1), "SLAYER", 40L), k.plusSeconds(1));
		feed(SessionSignal.slayerTaskProgress(k.plusSeconds(20), KALPHITES, KALPHITE_LOC, 1, 98), k.plusSeconds(20));
		feed(SessionSignal.xpChange(k.plusSeconds(21), "SLAYER", 40L), k.plusSeconds(21));

		// Kalphite rate is 40/unit (not the blended (420+80)/6); 98 remaining.
		assertEquals(Long.valueOf(40L * 98), ui(k.plusSeconds(22)).getEstimatedSlayerXpRemaining());
		assertEquals("session-wide task-unit total still whole-session", Integer.valueOf(6),
			coordinator.testCurrentSession().getAggregates().getSlayerProgressDelta());
	}

	@Test
	public void xpEstimate_singleAssignmentSession_unchangedFromBefore()
	{
		establishNechryaelTaskWithOneRemaining(T0); // 3 units, 315 XP, 1 remaining
		assertNull(coordinator.testCurrentSession().getAggregates().getSlayerAssignmentBaselineProgressDelta());
		assertEquals(Long.valueOf(105L), ui(T0.plusSeconds(60)).getEstimatedSlayerXpRemaining());
	}

	// 11 -----------------------------------------------------------------
	@Test
	public void persistence_newAssignmentSurvivesRestart_andOldJsonWithoutBaselinesStillLoads() throws Exception
	{
		Instant base = Instant.now().minusSeconds(200);
		Session a = establishNechryaelTaskWithOneRemaining(base);
		completeTask(base.plusSeconds(70));
		Instant assignedAt = base.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(assignedAt, KALPHITES, KALPHITE_LOC, 143), assignedAt);
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		Session s = coordinator.testCurrentSession();
		assertNotNull(s);
		assertEquals(a.getSessionId(), s.getSessionId());
		assertEquals("kalphites@kalphite lair", s.getActivityIdentity().getActivityKey());
		assertEquals(Integer.valueOf(143), s.getAggregates().getLatestSlayerCurrentRemaining());
		assertEquals(Integer.valueOf(4), s.getAggregates().getSlayerAssignmentBaselineProgressDelta());
		assertEquals(Long.valueOf(420L), s.getAggregates().getSlayerAssignmentBaselineSlayerXp());

		SessionAggregates old = GSON.fromJson("{\"xpGainedBySkill\":{\"SLAYER\":10},\"slayerProgressDelta\":2,\"latestSlayerCurrentRemaining\":5}", SessionAggregates.class);
		assertNull(old.getSlayerAssignmentBaselineProgressDelta());
		assertNull(old.getSlayerAssignmentBaselineSlayerXp());
		assertEquals(Integer.valueOf(5), old.getLatestSlayerCurrentRemaining());
	}
}
