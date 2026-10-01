package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Regressions from the final integrity audit (session side): a cancelled
 * Slayer task is never a completion, a same-tick kill's loot follows the
 * session that kill creates, and a manual re-evaluate never drops signals
 * that were still pending when it was clicked.
 */
public class IntegrityAuditSessionTest extends SlayerCoordinatorTestSupport
{
	private static final int COINS = 995;

	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(Session s)
	{
		return s.getAggregates().getSlayerAssignments();
	}

	private static SessionSignal loot(Instant at, String source, long qty)
	{
		List<SessionSignal.LootDrop> drops = new ArrayList<>();
		drops.add(new SessionSignal.LootDrop(COINS, "Coins", qty));
		return SessionSignal.serverNpcLoot(at, source, drops);
	}

	private static long coins(Session s)
	{
		long total = 0;
		for (SessionAggregates.LootDropGroup g : s.getAggregates().getLootDrops())
		{
			for (SessionAggregates.LootItemAggregate i : g.getItems())
			{
				if (i.getItemId() != null && i.getItemId() == COINS)
				{
					total += i.getQuantity();
				}
			}
		}
		return total;
	}

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

	/** Several signals as ONE closed game tick. */
	private void feedSameTick(Instant at, SessionSignal... signals)
	{
		for (SessionSignal s : signals)
		{
			coordinator.testEnqueueSignal(s.withGameTick(tick));
		}
		coordinator.testProcessTick(++tick, at);
	}

	// ------------------------------------------------------------ cancellation

	@Test
	public void cancelledTask_creditsNoUnseenUnits_isNeverCompleted_andNextAssignmentMarksItSkipped()
	{
		Instant t = nechryaelTask(T0, 75, 3);
		SessionAggregates.SlayerAssignmentRecord before = ledger(current()).get(0);
		assertEquals(Integer.valueOf(3), before.getUnitsCompleted());
		Integer deltaBefore = current().getAggregates().getSlayerProgressDelta();

		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(10), NECH, null, true), t.plusSeconds(10));

		SessionAggregates.SlayerAssignmentRecord r = ledger(current()).get(0);
		assertNull("a cancelled task is not COMPLETED", r.getEndReason());
		assertEquals("no fabricated units", Integer.valueOf(3), r.getUnitsCompleted());
		assertEquals("real remaining kept", Integer.valueOf(72), r.getLatestRemaining());
		assertEquals(deltaBefore, current().getAggregates().getSlayerProgressDelta());

		feed(SessionSignal.slayerTaskAssigned(t.plusSeconds(30), KALPHITES, KALPHITE_LOC, 100), t.plusSeconds(30));
		assertEquals(2, ledger(current()).size());
		assertEquals(SlayerAssignmentEndReason.REPLACED_EARLY, ledger(current()).get(0).getEndReason());
		assertEquals(Integer.valueOf(3), ledger(current()).get(0).getUnitsCompleted());
	}

	@Test
	public void realCompletion_stillReconcilesTheFinalUnit()
	{
		Instant t = nechryaelTask(T0, 3, 2);
		feed(SessionSignal.slayerTaskCompleted(t.plusSeconds(10), NECH, null, false), t.plusSeconds(10));
		SessionAggregates.SlayerAssignmentRecord r = ledger(current()).get(0);
		assertEquals(SlayerAssignmentEndReason.COMPLETED, r.getEndReason());
		assertEquals(Integer.valueOf(3), r.getUnitsCompleted());
	}

	@Test
	public void cancellationFlag_survivesTheEventMapperAndTickTagging()
	{
		EventPayloads.SlayerTaskCompleted cancelled = new EventPayloads.SlayerTaskCompleted(NECH, 5, "NORMAL", 70);
		cancelled.setCancelled(Boolean.TRUE);
		SessionSignal s = SessionEventMapper.map(EventType.SLAYER_TASK_COMPLETED, cancelled, T0);
		assertTrue(s.isSlayerTaskCancelled());
		assertTrue(s.withGameTick(9L).isSlayerTaskCancelled());

		// Old events (no field) and real completions stay completions.
		EventPayloads.SlayerTaskCompleted old = new Gson().fromJson(
			"{\"monster\":\"Nechryael\",\"streak\":5,\"streakLabel\":\"NORMAL\",\"points\":70}", EventPayloads.SlayerTaskCompleted.class);
		assertNull(old.getCancelled());
		assertTrue(!SessionEventMapper.map(EventType.SLAYER_TASK_COMPLETED, old, T0).isSlayerTaskCancelled());
		// A real completion serializes exactly as before (no new field in the JSON).
		String json = new Gson().toJson(new EventPayloads.SlayerTaskCompleted(NECH, 6, "NORMAL", 85));
		assertTrue(json, !json.contains("cancelled"));
	}

	// ------------------------------------------------------------ same-tick switch loot

	@Test
	public void offTaskLootRejectedBySlayerSession_followsTheSameTickBossKillIntoTheNewBossSession()
	{
		Instant t = nechryaelTask(T0, 50, 2);
		String slayerSessionId = current().getSessionId();
		assertEquals(ActivityType.SLAYER, current().getActivityIdentity().getActivityType());

		Instant kill = t.plusSeconds(20);
		feedSameTick(kill, SessionSignal.bossKill(kill, "Zulrah", 10), loot(kill, "Zulrah", 5000));

		Session now = current();
		assertTrue("the boss kill switched sessions", !slayerSessionId.equals(now.getSessionId()));
		assertEquals(ActivityType.BOSSING, now.getActivityIdentity().getActivityType());
		assertEquals("the kill's own loot belongs to the session it created", 5000L, coins(now));
		// Never copied into the old task's bucket.
		for (SessionAggregates.SlayerAssignmentRecord r : ledger(now) == null ? new ArrayList<SessionAggregates.SlayerAssignmentRecord>() : ledger(now))
		{
			assertTrue(r.getItems() == null || r.getItems().isEmpty());
		}
	}

	@Test
	public void offTaskLootAlone_isStillRejectedFromTheSlayerSession()
	{
		Instant t = nechryaelTask(T0, 50, 2);
		Instant at = t.plusSeconds(20);
		feed(loot(at, "Zulrah", 5000), at);
		assertEquals(ActivityType.SLAYER, current().getActivityIdentity().getActivityType());
		assertEquals(0L, coins(current()));
	}

	// ------------------------------------------------------------ manual re-evaluate

	@Test
	public void manualReEvaluate_neverDropsSignalsStillPendingForTheOpenTick()
	{
		Instant start = T0;
		coordinator.reEvaluateCurrentSession("Hill Giant", 2098, start);
		Session s = current();
		assertNotNull(s);
		assertEquals(ActivityType.COMBAT, s.getActivityIdentity().getActivityType());

		// A combat XP window and this NPC's loot are observed on the still-open tick...
		Instant observed = start.plusSeconds(40);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(observed, "ATTACK", 120L, start.plusSeconds(10)).withGameTick(tick));
		coordinator.testEnqueueSignal(loot(observed, "Hill Giant", 77).withGameTick(tick));
		// ...the player clicks Re-evaluate a moment later...
		coordinator.reEvaluateCurrentSession("Hill Giant", 2098, observed.plusMillis(300));
		assertTrue("re-evaluate is stamped before the pending signal",
			current().lastActiveAtInstant().isBefore(observed));
		// ...and the tick closes: nothing that was pending is dropped.
		coordinator.testProcessTick(++tick, observed.plusMillis(600));
		assertEquals(s.getSessionId(), current().getSessionId());
		assertEquals(Long.valueOf(120L), current().getAggregates().getXpGainedBySkill().get("ATTACK"));
		assertEquals(77L, coins(current()));
	}
}
