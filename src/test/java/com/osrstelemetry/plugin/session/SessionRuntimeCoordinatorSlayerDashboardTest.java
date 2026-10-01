package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/**
 * SMART SLAYER DASHBOARD data through the real coordinator: native bracelet
 * effects and observed kills per assignment, the assignment's master,
 * persistence, and the CURRENT TASK rates (task active time only).
 */
public class SessionRuntimeCoordinatorSlayerDashboardTest extends SlayerCoordinatorTestSupport
{
	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(Session s)
	{
		return s.getAggregates().getSlayerAssignments();
	}

	private static SessionAggregates.SlayerAssignmentRecord last(Session s)
	{
		return ledger(s).get(ledger(s).size() - 1);
	}

	private void bracelet(String effect, Instant at)
	{
		feed(SessionSignal.slayerBraceletEffect(at, effect), at);
	}

	@Test
	public void observedKills_unitsMinusExpeditiousPlusSlaughter_braceletProcsOnTheAssignment()
	{
		Instant t = T0;
		feed(SessionSignal.slayerTaskAssigned(t, KALPHITES, KALPHITE_LOC, 100, 5), t);
		int remaining = 100;
		for (int i = 1; i <= 3; i++)
		{
			remaining--;
			feed(SessionSignal.slayerTaskProgress(t.plusSeconds(10L * i), KALPHITES, KALPHITE_LOC, 1, remaining), t.plusSeconds(10L * i));
		}
		// A kill the Bracelet of slaughter kept from counting: chat only, remaining unchanged.
		bracelet("SLAUGHTER", t.plusSeconds(40));
		// An Expeditious kill: one progress event consuming 2 units, plus its chat.
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(50), KALPHITES, KALPHITE_LOC, 2, remaining - 2), t.plusSeconds(50));
		bracelet("EXPEDITIOUS", t.plusSeconds(50));

		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertEquals(Integer.valueOf(5), r.getUnitsCompleted());
		assertEquals(Integer.valueOf(1), r.getSlaughterProcs());
		assertEquals(Integer.valueOf(1), r.getExpeditiousProcs());
		assertEquals("3 ordinary + 1 slaughtered + 1 expeditious kill", Integer.valueOf(5), r.observedKills());
		assertEquals(Integer.valueOf(5), r.getMasterId());
		assertEquals("bracelets are not activity: the remaining count is only ever native", Integer.valueOf(95),
			current().getAggregates().getLatestSlayerCurrentRemaining());
	}

	@Test
	public void repeatedProcs_accumulate_andAfterCompletionAreNeverCreditedToTheFinishedTask()
	{
		Instant t = T0;
		feed(SessionSignal.slayerTaskAssigned(t, NECH, NECH_LOC, 3, 8), t);
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(10), NECH, NECH_LOC, 1, 2), t.plusSeconds(10));
		bracelet("SLAUGHTER", t.plusSeconds(20));
		bracelet("SLAUGHTER", t.plusSeconds(30));
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(40), NECH, NECH_LOC, 2, 0), t.plusSeconds(40));
		bracelet("EXPEDITIOUS", t.plusSeconds(40));
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(41), NECH, null), t.plusSeconds(41));
		SessionAggregates.SlayerAssignmentRecord nech = last(current());
		assertEquals(Integer.valueOf(2), nech.getSlaughterProcs());
		assertEquals(Integer.valueOf(1), nech.getExpeditiousProcs());
		assertEquals("1 + 2 slaughtered + 1 expeditious", Integer.valueOf(4), nech.observedKills());

		// Travelling with the bracelet: a stray message after completion owns nothing.
		bracelet("SLAUGHTER", t.plusSeconds(90));
		assertEquals(Integer.valueOf(2), last(current()).getSlaughterProcs());

		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(120), KALPHITES, KALPHITE_LOC, 50, 5), t.plusSeconds(120));
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(130), KALPHITES, KALPHITE_LOC, 1, 49), t.plusSeconds(130));
		bracelet("SLAUGHTER", t.plusSeconds(131));
		assertEquals(Integer.valueOf(1), last(current()).getSlaughterProcs());
		assertEquals(Integer.valueOf(2), ledger(current()).get(0).getSlaughterProcs());
		assertEquals("same-name later assignment / new task is its own record", 2, ledger(current()).size());
	}

	@Test
	public void braceletEffect_isNeverLifecycleEvidence_andNeverCreditedOutsideSlayer()
	{
		bracelet("SLAUGHTER", T0);
		assertNull("no session from a chat message", current());

		feed(SessionSignal.bossKill(T0.plusSeconds(5), "Zulrah", 10), T0.plusSeconds(5));
		bracelet("EXPEDITIOUS", T0.plusSeconds(6));
		assertEquals(ActivityType.BOSSING, current().getActivityIdentity().getActivityType());
		assertTrue(current().getAggregates().getSlayerAssignments() == null
			|| current().getAggregates().getSlayerAssignments().isEmpty());
	}

	@Test
	public void suspendedSlayerSession_braceletDoesNotResumeIt()
	{
		feed(SessionSignal.slayerTaskAssigned(T0, NECH, NECH_LOC, 30, 8), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(10), NECH, NECH_LOC, 1, 29), T0.plusSeconds(10));
		Instant idle = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(30);
		coordinator.testProcessTick(++tick, idle);
		assertEquals(SessionState.SUSPENDED, current().getState());
		bracelet("SLAUGHTER", idle.plusSeconds(5));
		assertEquals(SessionState.SUSPENDED, current().getState());
	}

	@Test
	public void midTaskRecord_adoptsItsMasterFromProgress_andStaysWithoutGuessedKillsWhenSeededFromAnOlderFile()
	{
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 40, null, 8), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(10), NECH, NECH_LOC, 1, 39, null, 8), T0.plusSeconds(10));
		SessionAggregates.SlayerAssignmentRecord r = last(current());
		assertTrue(r.isBootstrapped());
		assertEquals(Integer.valueOf(8), r.getMasterId());
		assertEquals("observed from the session's first unit", Integer.valueOf(2), r.observedKills());
	}

	@Test
	public void restart_keepsProcsAndMaster_oldRecordsHaveNoKillClaim() throws Exception
	{
		Instant base = Instant.now().minusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(base, KALPHITES, KALPHITE_LOC, 50, 6), base);
		feed(SessionSignal.slayerTaskProgress(base.plusSeconds(10), KALPHITES, KALPHITE_LOC, 1, 49), base.plusSeconds(10));
		bracelet("SLAUGHTER", base.plusSeconds(11));
		String id = current().getSessionId();
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		Session back = current();
		assertEquals(id, back.getSessionId());
		SessionAggregates.SlayerAssignmentRecord r = last(back);
		assertEquals(Integer.valueOf(1), r.getSlaughterProcs());
		assertEquals(Integer.valueOf(6), r.getMasterId());
		assertEquals(Integer.valueOf(2), r.observedKills());

		SessionAggregates.SlayerAssignmentRecord legacy = new Gson().fromJson(
			"{\"ordinal\":1,\"taskName\":\"Nechryael\",\"unitsCompleted\":12}", SessionAggregates.SlayerAssignmentRecord.class);
		assertNull("pre-tracking record: no master", legacy.getMasterId());
		assertNull("pre-tracking record: units alone are never called kills", legacy.observedKills());
	}

	@Test
	public void currentTaskRates_useTaskActiveTimeOnly_suspensionExcluded()
	{
		Instant t = T0;
		feed(SessionSignal.slayerTaskAssigned(t, KALPHITES, KALPHITE_LOC, 100, 5), t);
		int remaining = 100;
		for (int i = 1; i <= 10; i++)
		{
			remaining--;
			Instant k = t.plusSeconds(60L * i);
			feed(SessionSignal.slayerTaskProgress(k, KALPHITES, KALPHITE_LOC, 1, remaining), k);
		}
		Instant lastKill = t.plusSeconds(600);
		// Bank for a long time (suspends), then come back for one more kill.
		Instant back = lastKill.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(600);
		coordinator.testProcessTick(++tick, back.minusSeconds(1));
		remaining--;
		feed(SessionSignal.slayerTaskProgress(back, KALPHITES, KALPHITE_LOC, 1, remaining), back);

		CurrentSessionSnapshot.SlayerCurrentTaskView view = ui(back).getSlayerCurrentTask();
		assertEquals(Integer.valueOf(11), view.getKills());
		long activeMs = current().getAccumulatedActiveDurationMillis();
		assertTrue("suspended time excluded: " + activeMs, activeMs < SessionLifecycleEngine.SUSPEND_TIMEOUT.toMillis() + 601_000L);
		assertEquals(Long.valueOf(Math.round(11 * 3_600_000.0 / activeMs)), view.getKillsPerHour());
		assertEquals(Integer.valueOf(11), view.getProgressPercent());
		assertEquals(Integer.valueOf(89), view.getRemaining());
	}

	@Test
	public void eventMapper_carriesMasterAndBraceletPayloads()
	{
		SessionSignal assigned = SessionEventMapper.map(EventType.SLAYER_TASK_ASSIGNED,
			new EventPayloads.SlayerTaskAssigned("Kalphites", 140, null, 5), T0);
		assertEquals(Integer.valueOf(5), assigned.getSlayerMasterId());
		SessionSignal progress = SessionEventMapper.map(EventType.SLAYER_TASK_PROGRESS,
			new EventPayloads.SlayerTaskProgress("Kalphites", null, 140, 139, -1, 1, 140, 10), T0);
		assertEquals(Integer.valueOf(10), progress.getSlayerMasterId());
		assertNull("master 0 = none", SessionEventMapper.map(EventType.SLAYER_TASK_ASSIGNED,
			new EventPayloads.SlayerTaskAssigned("Kalphites", 140, null, 0), T0).getSlayerMasterId());
		SessionSignal effect = SessionEventMapper.map(EventType.SLAYER_BRACELET_EFFECT,
			new EventPayloads.SlayerBraceletEffect("EXPEDITIOUS"), T0);
		assertEquals(SignalKind.SLAYER_BRACELET_EFFECT, effect.getKind());
		assertEquals("EXPEDITIOUS", effect.getSlayerBraceletEffect());
		assertNull("never a boss name", effect.getSlayerTaskName());
	}

	@Test
	public void freshTaskTransition_completeAwaitingNewTaskFirstKill_sameSession_freshCurrentTaskFacts()
	{
		Instant t = T0;
		feed(SessionSignal.slayerTaskAssigned(t, NECH, NECH_LOC, 2, 8), t);
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(10), NECH, NECH_LOC, 1, 1), t.plusSeconds(10));
		bracelet("SLAUGHTER", t.plusSeconds(15));
		feed(SessionSignal.slayerTaskProgress(t.plusSeconds(20), NECH, NECH_LOC, 1, 0), t.plusSeconds(20));
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(21), NECH, null), t.plusSeconds(21));
		String id = current().getSessionId();
		CurrentSessionSnapshot.SlayerCurrentTaskView awaiting = ui(t.plusSeconds(30)).getSlayerCurrentTask();
		assertTrue(awaiting.isComplete());
		assertEquals(Integer.valueOf(3), awaiting.getKills());

		Instant assigned = t.plusSeconds(90);
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 120, 5), assigned);
		CurrentSessionSnapshot.SlayerCurrentTaskView fresh = ui(assigned).getSlayerCurrentTask();
		assertEquals(id, current().getSessionId());
		assertEquals(KALPHITES, fresh.getTaskName());
		assertEquals(Integer.valueOf(120), fresh.getRemaining());
		assertEquals(Integer.valueOf(0), fresh.getProgressPercent());
		assertEquals(Integer.valueOf(0), fresh.getKills());
		assertEquals(Integer.valueOf(0), fresh.getSlaughterProcs());
		assertEquals(Integer.valueOf(5), fresh.getMasterId());

		feed(SessionSignal.slayerTaskProgress(assigned.plusSeconds(20), KALPHITES, KALPHITE_LOC, 1, 119), assigned.plusSeconds(20));
		CurrentSessionSnapshot.SlayerCurrentTaskView first = ui(assigned.plusSeconds(20)).getSlayerCurrentTask();
		assertEquals(id, current().getSessionId());
		assertEquals(Integer.valueOf(1), first.getKills());
		assertEquals(Integer.valueOf(119), first.getRemaining());
		assertEquals("previous task kept in TASK HISTORY", 2, ui(assigned.plusSeconds(20)).getSlayerAssignments().size());
		assertEquals(Integer.valueOf(3), ui(assigned.plusSeconds(20)).getSlayerAssignments().get(1).getKills());
	}
}
