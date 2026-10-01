package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.history.HistoryIndex;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * SMART SLAYER -> PERSISTENCE -> HISTORY, end to end through the real
 * coordinator: a multi-task Slayer session built from real signals, left
 * idle until the engine genuinely finalizes it to disk, then loaded back
 * exactly as History loads it (SessionPersistence.loadFinalized()).
 *
 * Records, oldest first:
 *   #1 Nechryael  -- COMPLETED, loot + XP, fully observed
 *   #2 Kalphites  -- REPLACED_EARLY ("Skipped"), loot
 *   #3 Nechryael  -- same name again, REPLACED_EARLY with zero loot
 *   #4 Turoth     -- unfinished when the session finalized (no status)
 */
public class SlayerHistoryRoundTripTest extends SlayerCoordinatorTestSupport
{
	private static final String TUROTH = "Turoth";
	private static final String TUROTH_LOC = "Fremennik Slayer Dungeon";

	private static SessionSignal loot(Instant at, String source, long coins)
	{
		List<SessionSignal.LootDrop> drops = new ArrayList<>();
		drops.add(new SessionSignal.LootDrop(995, "Coins", coins));
		return SessionSignal.serverNpcLoot(at, source, drops);
	}

	/** Builds the four-assignment session from `base`; returns the instant of its last activity. */
	private Instant buildMultiTaskSession(Instant base)
	{
		feed(SessionSignal.slayerTaskAssigned(base, NECH, NECH_LOC, 2), base);
		feed(SessionSignal.slayerTaskProgress(base.plusSeconds(10), NECH, NECH_LOC, 1, 1), base.plusSeconds(10));
		feed(loot(base.plusSeconds(11), "Nechryael", 100), base.plusSeconds(11));
		xp("SLAYER", 100L, base.plusSeconds(12));
		xp("HITPOINTS", 40L, base.plusSeconds(12));
		feed(SessionSignal.slayerTaskCompleted(base.plusSeconds(60), NECH, null), base.plusSeconds(60));
		feed(loot(base.plusSeconds(61), "Nechryael", 50), base.plusSeconds(61));

		feed(SessionSignal.slayerTaskAssigned(base.plusSeconds(120), KALPHITES, KALPHITE_LOC, 143), base.plusSeconds(120));
		feed(SessionSignal.slayerTaskProgress(base.plusSeconds(150), KALPHITES, KALPHITE_LOC, 1, 142), base.plusSeconds(150));
		feed(loot(base.plusSeconds(151), "Kalphite Worker", 7), base.plusSeconds(151));

		feed(SessionSignal.slayerTaskAssigned(base.plusSeconds(200), NECH, NECH_LOC, 3), base.plusSeconds(200));
		feed(SessionSignal.slayerTaskAssigned(base.plusSeconds(210), TUROTH, TUROTH_LOC, 50), base.plusSeconds(210));
		feed(SessionSignal.slayerTaskProgress(base.plusSeconds(240), TUROTH, TUROTH_LOC, 1, 49), base.plusSeconds(240));
		feed(loot(base.plusSeconds(241), "Turoth", 10), base.plusSeconds(241));
		return base.plusSeconds(241);
	}

	/** Idles past suspend + resume window so the engine finalizes and persists it; returns the loaded file. */
	private Session finalizeAndLoad(Session live, Instant last)
	{
		coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1));
		coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT)
			.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(5));
		assertNull("the Slayer session genuinely finalized", current());
		assertNotNull("finalizedAt stamped", live.getFinalizedAt());
		store.shutdown(); // drains the async finalized write
		store = new LocalStateStore(GSON);
		store.start();
		return new SessionPersistence(store).loadFinalized(ACCOUNT, live.getSessionId());
	}

	@Test
	public void newSchema_everyAssignmentField_survivesFinalizeAndLoad_forHistory()
	{
		Instant last = buildMultiTaskSession(T0);
		Session live = current();
		List<SessionAggregates.SlayerAssignmentRecord> liveLedger = live.getAggregates().getSlayerAssignments();
		assertEquals(4, liveLedger.size());

		Session loaded = finalizeAndLoad(live, last);
		assertNotNull("finalized file exists and loads", loaded);
		List<SessionAggregates.SlayerAssignmentRecord> ledger = loaded.getAggregates().getSlayerAssignments();

		// Deep equality (Lombok @Data): ordinal, taskName, assignmentKey, assignedAt,
		// initialAmount, latestRemaining, endedAt, bootstrapped, items, endReason,
		// completedAt, unitsCompleted, xpGainedBySkill, activeDurationStart/EndMillis.
		assertEquals("every field of every record round-trips", liveLedger, ledger);

		// ...and they are real values, not null == null.
		SessionAggregates.SlayerAssignmentRecord nech = ledger.get(0);
		assertEquals(1, nech.getOrdinal());
		assertEquals(NECH, nech.getTaskName());
		assertEquals("nechryael@catacombs of kourend", nech.getAssignmentKey());
		assertEquals(T0.toString(), nech.getAssignedAt());
		assertEquals(Integer.valueOf(2), nech.getInitialAmount());
		assertEquals(Integer.valueOf(0), nech.getLatestRemaining());
		assertFalse(nech.isBootstrapped());
		assertEquals(SlayerAssignmentEndReason.COMPLETED, nech.getEndReason());
		assertNotNull(nech.getCompletedAt());
		assertEquals(T0.plusSeconds(120).toString(), nech.getEndedAt());
		assertEquals(Integer.valueOf(2), nech.getUnitsCompleted());
		assertEquals(150L, nech.getItems().get(0).getQuantity());
		assertEquals(Long.valueOf(100L), nech.getXpGainedBySkill().get("SLAYER"));
		assertNotNull(nech.getActiveDurationStartMillis());
		assertNotNull(nech.getActiveDurationEndMillis());

		SessionAggregates.SlayerAssignmentRecord kal = ledger.get(1);
		assertEquals(SlayerAssignmentEndReason.REPLACED_EARLY, kal.getEndReason());
		assertEquals(Integer.valueOf(142), kal.getLatestRemaining());
		assertEquals(Integer.valueOf(1), kal.getUnitsCompleted());
		assertEquals(7L, kal.getItems().get(0).getQuantity());

		SessionAggregates.SlayerAssignmentRecord nech2 = ledger.get(2);
		assertEquals(NECH, nech2.getTaskName());
		assertEquals("same-name task is its own instance", 3, nech2.getOrdinal());
		assertEquals(SlayerAssignmentEndReason.REPLACED_EARLY, nech2.getEndReason());
		assertTrue("zero-loot record kept", nech2.getItems().isEmpty());

		SessionAggregates.SlayerAssignmentRecord turoth = ledger.get(3);
		assertNull("unfinished at finalize: never a guessed status", turoth.getEndReason());
		assertNull(turoth.getEndedAt());
		assertEquals(10L, turoth.getItems().get(0).getQuantity());

		// Partition invariant survives on disk.
		long sessionCoins = 0;
		for (SessionAggregates.LootDropGroup g : loaded.getAggregates().getLootDrops())
		{
			sessionCoins += g.getItems().get(0).getQuantity();
		}
		assertEquals(167L, sessionCoins);
	}

	@Test
	public void historyEntry_multiTaskTitle_isPresentationOnly_identityAndIdUnchanged()
	{
		Instant last = buildMultiTaskSession(T0);
		Session live = current();
		String id = live.getSessionId();
		Session loaded = finalizeAndLoad(live, last);

		HistoryEntry entry = HistoryEntry.from(loaded);
		assertEquals(4, entry.getSlayerAssignmentCount());
		assertEquals("Slayer Session", HistoryEntry.displayTitle(entry.getActivityIdentity(), entry.getSlayerAssignmentCount()));
		assertEquals("session ID is canonical", id, entry.getSessionId());
		assertEquals("persisted identity untouched (still the final assignment)", TUROTH, loaded.getActivityIdentity().getDisplayName());
		assertTrue("file name is the session ID", TelemetryPaths.sessionFile(ACCOUNT, id).exists());

		List<HistoryEntry> indexed = new HistoryIndex().refresh(ACCOUNT, new SessionPersistence(store), Duration.ofDays(3650), Instant.now());
		assertEquals(1, indexed.size());
		assertEquals(id, indexed.get(0).getSessionId());
		assertEquals(4, indexed.get(0).getSlayerAssignmentCount());
	}

	@Test
	public void restartMidSession_sessionStateJson_preservesEveryAssignmentField()
	{
		// Relative to now: a restore after a long gap would (correctly) finalize it instead.
		buildMultiTaskSession(Instant.now().minusSeconds(300));
		List<SessionAggregates.SlayerAssignmentRecord> before = new ArrayList<>(current().getAggregates().getSlayerAssignments());
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		assertEquals("session_state.json round trip", before, current().getAggregates().getSlayerAssignments());
	}

	@Test
	public void singleTaskSlayerSession_keepsTaskTitle()
	{
		feed(SessionSignal.slayerTaskAssigned(T0, NECH, NECH_LOC, 2), T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(10), NECH, NECH_LOC, 1, 1), T0.plusSeconds(10));
		feed(loot(T0.plusSeconds(11), "Nechryael", 100), T0.plusSeconds(11));
		Session live = current();
		Session loaded = finalizeAndLoad(live, T0.plusSeconds(11));
		HistoryEntry entry = HistoryEntry.from(loaded);
		assertEquals(1, entry.getSlayerAssignmentCount());
		assertEquals(NECH, HistoryEntry.displayTitle(entry.getActivityIdentity(), entry.getSlayerAssignmentCount()));
	}

	@Test
	public void krakenOnCaveKrakenTask_finalizesAsTheSlayerSession_withTheBossLootInTheTaskCard()
	{
		feed(SessionSignal.slayerTaskProgress(T0, "Cave Kraken", "Kraken Cove", 1, 99), T0);
		feed(loot(T0.plusSeconds(1), "Cave kraken", 50), T0.plusSeconds(1));
		Instant t = T0.plusSeconds(60);
		feed(SessionSignal.npcDeath(t), t);
		feed(SessionSignal.slayerTaskProgress(t.plusMillis(1200), "Cave Kraken", "Kraken Cove", 1, 98), t.plusMillis(1200));
		feed(SessionSignal.bossKill(t.plusMillis(3000), "Kraken", 11), t.plusMillis(3000));
		feed(loot(t.plusMillis(3000), "Kraken", 1000), t.plusMillis(3600));
		Session s = current();
		assertEquals(ActivityType.SLAYER, s.getActivityIdentity().getActivityType());

		HistoryEntry entry = HistoryEntry.from(copyFinalized(s));
		assertEquals("one task card: the Cave Kraken assignment", 1, entry.getSlayerAssignmentCount());
		assertEquals("Kraken boss loot sits in the Cave Kraken card with the regular cave kraken loot",
			java.util.Arrays.asList(1050L),
			quantities(copyFinalized(s).getAggregates().getSlayerAssignments().get(0).getItems()));
	}


	private static Session copyFinalized(Session s)
	{
		Session copy = GSON.fromJson(GSON.toJson(s), Session.class);
		copy.setFinalizedAt(Instant.now().toString());
		return copy;
	}

	private static List<Long> quantities(List<SessionAggregates.LootItemAggregate> items)
	{
		List<Long> q = new ArrayList<>();
		for (SessionAggregates.LootItemAggregate i : items)
		{
			q.add(i.getQuantity());
		}
		return q;
	}
}
