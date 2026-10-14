package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import com.osrstelemetry.plugin.ui.model.SlayerDashboard;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * TASK UNITS vs ACTUAL KILLS through the real coordinator. Task units stay
 * the game's own task count; actual kills are every owned NPC death of the
 * task's encounter (targets, subordinates, superiors), by exact NPC, per
 * assignment; superiors per task and per Slayer session; a new task starts
 * its own counts and a finished task never changes; older records keep
 * their old meaning (never a fabricated 0).
 */
public class SlayerActualKillsTest extends SlayerCoordinatorTestSupport
{
	private static final String GREATER = "Greater Nechryael";
	private static final String SPAWN = "Death spawn";
	private static final String NECHRYARCH = "Nechryarch";

	private Instant t = T0;

	private static List<SessionAggregates.SlayerAssignmentRecord> ledger(Session s)
	{
		return s.getAggregates().getSlayerAssignments();
	}

	private static SessionAggregates.SlayerAssignmentRecord last(Session s)
	{
		return ledger(s).get(ledger(s).size() - 1);
	}

	private Instant next()
	{
		t = t.plusSeconds(2);
		return t;
	}

	private void kill(String npc, String role)
	{
		Instant at = next();
		feed(SessionSignal.slayerEncounterKill(at, npc, role), at);
	}

	private void unitKill(String task, String npc, int remainingAfter)
	{
		Instant at = next();
		feed(SessionSignal.slayerTaskProgress(at, task, task.equals(NECH) ? NECH_LOC : KALPHITE_LOC, 1, remainingAfter), at);
		feed(SessionSignal.slayerEncounterKill(at, npc, "TASK"), at);
	}

	private void assign(String task, int amount)
	{
		Instant at = next();
		feed(SessionSignal.slayerTaskAssigned(at, task, task.equals(NECH) ? NECH_LOC : KALPHITE_LOC, amount, 8), at);
	}

	/** The acceptance scenario: 170 task units, 235 actual kills (169 + 1 Greater + 64 Death spawn + 1 Nechryarch). */
	private void nechryaelEncounter()
	{
		assign(NECH, 170);
		int remaining = 170;
		for (int i = 0; i < 169; i++)
		{
			unitKill(NECH, NECH, --remaining);
			if (i < 64)
			{
				kill(SPAWN, "SUBORDINATE");
			}
		}
		kill(NECHRYARCH, "SUPERIOR");
		unitKill(NECH, GREATER, --remaining);
	}

	@Test
	public void oneToOneTask_unitsEqualActualKills()
	{
		assign(KALPHITES, 100);
		for (int r = 99; r >= 0; r--)
		{
			unitKill(KALPHITES, "Kalphite Worker", r);
		}
		SessionAggregates.SlayerAssignmentRecord rec = ledger(current()).get(0);
		assertEquals(Integer.valueOf(100), rec.observedUnitsCompleted());
		assertEquals(Long.valueOf(100), rec.actualKills());
		assertTrue(rec.breakdownComplete());
	}

	@Test
	public void subordinatesAndSuperior_170TaskUnits_235ActualKills_exactBreakdown()
	{
		nechryaelEncounter();
		SessionAggregates.SlayerAssignmentRecord rec = last(current());
		assertEquals("task units stay the game's own count", Integer.valueOf(170), rec.observedUnitsCompleted());
		assertEquals(Long.valueOf(235), rec.actualKills());
		assertEquals(Long.valueOf(169), rec.getKillsByNpc().get(NECH));
		assertEquals(Long.valueOf(64), rec.getKillsByNpc().get(SPAWN));
		assertEquals(Long.valueOf(1), rec.getKillsByNpc().get(GREATER));
		assertEquals(Long.valueOf(1), rec.getKillsByNpc().get(NECHRYARCH));
		assertEquals(Long.valueOf(1), rec.superiorKills());
		assertEquals(Long.valueOf(1), rec.getSuperiorsByNpc().get(NECHRYARCH));
		assertTrue(rec.breakdownComplete());
		CurrentSessionSnapshot ui = ui(t.plusSeconds(1));
		assertEquals("the live KILLS cell is actual kills", Integer.valueOf(235), ui.getSlayerCurrentTask().getKills());
		assertEquals(Integer.valueOf(170), ui.getSlayerAssignments().get(0).getUnitsCompleted());
		assertEquals(Integer.valueOf(235), ui.getSlayerAssignments().get(0).getKills());
		assertEquals(Long.valueOf(1), ui.getSlayerCurrentTask().getSuperiorsThisTask());
	}

	@Test
	public void lootPerKill_isPerActualKill_neverPerTaskUnit()
	{
		assertEquals("1.63M over 235 actual kills", Long.valueOf(6_950), SlayerDashboard.perKill(1_633_250L, 235));
		assertEquals("the 170 task units would claim far more", Long.valueOf(9_607), SlayerDashboard.perKill(1_633_250L, 170));
	}

	@Test
	public void subordinate_neverStealsIdentity_neverATask_neverASession()
	{
		kill(SPAWN, "SUBORDINATE");
		assertNull("an encounter kill is never lifecycle evidence", current());
		assign(NECH, 10);
		unitKill(NECH, NECH, 9);
		String id = current().getSessionId();
		kill(SPAWN, "SUBORDINATE");
		kill(SPAWN, "SUBORDINATE");
		assertEquals(id, current().getSessionId());
		assertEquals(1, ledger(current()).size());
		assertEquals(NECH, last(current()).getTaskName());
		assertEquals("Death spawn uses no task unit", Integer.valueOf(1), last(current()).observedUnitsCompleted());
		assertEquals(Long.valueOf(3), last(current()).actualKills());
	}

	@Test
	public void unrelatedNearbyNpc_isNeverTaskOwned()
	{
		assertNull(SlayerEncounterKills.role(NECH, null, "Rat"));
		assertNull("another task's summon is not this task's", SlayerEncounterKills.role("Gargoyles", NpcID.SLAYER_NECHRYAEL_SPAWN, SPAWN));
		assertNull(SlayerEncounterKills.role(NECH, null, "Gargoyle"));
	}

	@Test
	public void superior_isIdentifiedByExactNpcId_neverByName()
	{
		assertEquals(SlayerEncounterKills.Role.SUPERIOR, SlayerEncounterKills.role(NECH, NpcID.SUPERIOR_NECHRYAEL, NECHRYARCH));
		assertEquals(SlayerEncounterKills.Role.SUPERIOR, SlayerEncounterKills.role("Gargoyles", NpcID.SUPERIOR_GARGOYLE, "Marble gargoyle"));
		assertEquals("Greater Nechryael is an ordinary task variant", SlayerEncounterKills.Role.TASK,
			SlayerEncounterKills.role(NECH, null, GREATER));
		assertEquals("a superior's NAME without its id is only the task family", SlayerEncounterKills.Role.TASK,
			SlayerEncounterKills.role(NECH, null, NECHRYARCH));
		assertEquals(SlayerEncounterKills.Role.SUBORDINATE, SlayerEncounterKills.role(NECH, NpcID.SLAYER_NECHRYAEL_SPAWN, SPAWN));
		assertEquals(SlayerEncounterKills.Role.SUBORDINATE, SlayerEncounterKills.role(NECH, NpcID.SUPERIOR_NECHRYAEL_MAGIC_SPAWN, "Chaotic death spawn"));
		assertTrue(SlayerEncounterKills.superiorCount() >= 40);
		assertFalse(SlayerEncounterKills.isSuperior(NpcID.SLAYER_NECHRYAEL));
	}

	@Test
	public void superior_addsAnActualKillAndASuperior_keepsItsIdentity_noNewTaskOrSession()
	{
		assign(NECH, 20);
		unitKill(NECH, NECH, 19);
		String id = current().getSessionId();
		kill(NECHRYARCH, "SUPERIOR");
		kill("Marble gargoyle", "SUPERIOR");
		SessionAggregates.SlayerAssignmentRecord rec = last(current());
		assertEquals(Long.valueOf(3), rec.actualKills());
		assertEquals(Long.valueOf(2), rec.superiorKills());
		assertEquals(Long.valueOf(1), rec.getSuperiorsByNpc().get(NECHRYARCH));
		assertEquals(Long.valueOf(1), rec.getSuperiorsByNpc().get("Marble gargoyle"));
		assertEquals(id, current().getSessionId());
		assertEquals(1, ledger(current()).size());
		assertEquals("superiors never assumed to use a task unit", Integer.valueOf(1), rec.observedUnitsCompleted());
	}

	@Test
	public void superiorLoot_staysTaskOwned_underItsExactSource()
	{
		assertEquals(SlayerTaskFamilyRegistry.LootSourceCompatibility.MATCH,
			SlayerTaskFamilyRegistry.lootSourceCompatibility(NECH, NECHRYARCH));
		assign(NECH, 20);
		unitKill(NECH, NECH, 19);
		Instant at = next();
		feed(SessionSignal.serverNpcLoot(at, NECHRYARCH, Collections.singletonList(new SessionSignal.LootDrop(995, "Coins", 5_000))), at);
		assertEquals(5_000L, last(current()).getItems().get(0).getQuantity());
	}

	@Test
	public void newTask_resetsTaskCounts_sessionSuperiorsContinue_finishedTaskNeverChanges()
	{
		assign(NECH, 3);
		unitKill(NECH, NECH, 2);
		kill(NECHRYARCH, "SUPERIOR");
		unitKill(NECH, NECH, 1);
		unitKill(NECH, NECH, 0);
		Instant done = next();
		feed(SessionSignal.slayerTaskCompleted(done, NECH, null), done);
		SessionAggregates.SlayerAssignmentRecord finished = last(current());
		long before = finished.actualKills();
		// Awaiting a new task: nothing is credited (the collector never emits without an active task).
		assign(KALPHITES, 50);
		unitKill(KALPHITES, "Kalphite Soldier", 49);
		kill("Kalphite Soldier", "TASK");
		SessionAggregates.SlayerAssignmentRecord kalphites = last(current());
		assertEquals(2, ledger(current()).size());
		assertEquals(Long.valueOf(2), kalphites.actualKills());
		assertEquals("new task: its own superior count", Long.valueOf(0), kalphites.superiorKills());
		assertEquals("the finished task never changes", Long.valueOf(before), ledger(current()).get(0).actualKills());
		assertEquals(Long.valueOf(1), ledger(current()).get(0).superiorKills());
		CurrentSessionSnapshot ui = ui(t.plusSeconds(1));
		assertEquals(Long.valueOf(0), ui.getSlayerCurrentTask().getSuperiorsThisTask());
		assertEquals("the Slayer session's superiors continue", Long.valueOf(1), ui.getSlayerCurrentTask().getSuperiorsThisSession());
	}

	@Test
	public void multipleSuperiorsAcrossTasks_sessionAggregate()
	{
		assign(NECH, 5);
		unitKill(NECH, NECH, 4);
		kill(NECHRYARCH, "SUPERIOR");
		kill(NECHRYARCH, "SUPERIOR");
		assign(KALPHITES, 5);
		unitKill(KALPHITES, "Kalphite Worker", 4);
		kill("Chasm crawler", "SUPERIOR");
		CurrentSessionSnapshot ui = ui(t.plusSeconds(1));
		assertEquals(Long.valueOf(1), ui.getSlayerCurrentTask().getSuperiorsThisTask());
		assertEquals(Long.valueOf(3), ui.getSlayerCurrentTask().getSuperiorsThisSession());
		assertEquals(Long.valueOf(2), ledger(current()).get(0).getSuperiorsByNpc().get(NECHRYARCH));
	}

	@Test
	public void braceletSemantics_unchanged_andAreTheFloorOfActualKills()
	{
		assign(KALPHITES, 100);
		Instant at = next();
		feed(SessionSignal.slayerTaskProgress(at, KALPHITES, KALPHITE_LOC, 1, 99), at);
		at = next();
		feed(SessionSignal.slayerBraceletEffect(at, "SLAUGHTER"), at);
		SessionAggregates.SlayerAssignmentRecord rec = last(current());
		assertEquals("unchanged unit-derived kills", Integer.valueOf(2), rec.observedKills());
		assertEquals("no death seen by the encounter tracking: the unit-derived kills are the floor", Long.valueOf(2), rec.actualKills());
		assertFalse("a breakdown that misses kills is not shown", rec.breakdownComplete());
		kill(SPAWN, "SUBORDINATE");
		assertEquals("subordinates add on top of the floor", Long.valueOf(3), rec.actualKills());
	}

	@Test
	public void taskFamilyAffinity_forDeathSpawnLoot_unchanged()
	{
		assertEquals(SlayerTaskFamilyRegistry.LootSourceCompatibility.UNKNOWN, SlayerTaskFamilyRegistry.lootSourceCompatibility(NECH, SPAWN));
	}

	@Test
	public void olderRecord_withoutEncounterTracking_keepsItsOldMeaning_neverAFabricatedZero()
	{
		SessionAggregates.SlayerAssignmentRecord old = new SessionAggregates.SlayerAssignmentRecord();
		old.setUnitsCompleted(40);
		old.setSlaughterProcs(0);
		old.setExpeditiousProcs(0);
		assertNull(old.trackedKills());
		assertEquals(Long.valueOf(40), old.actualKills());
		assertNull(old.superiorKills());
		assertFalse(old.breakdownComplete());
		SessionAggregates.SlayerAssignmentRecord legacy = new SessionAggregates.SlayerAssignmentRecord();
		assertNull("nothing known: nothing shown", legacy.actualKills());
	}

	@Test
	public void deathAfterTheTaskCompleted_isNeverCreditedToTheFinishedTask()
	{
		assign(NECH, 1);
		unitKill(NECH, NECH, 0);
		Instant done = next();
		feed(SessionSignal.slayerTaskCompleted(done, NECH, null), done);
		long before = last(current()).actualKills();
		kill(NECH, "TASK");
		assertEquals(Long.valueOf(before), last(current()).actualKills());
	}

	@Test
	public void encounterKills_surviveARestart()
	{
		Instant base = Instant.now().minusSeconds(120);
		t = base;
		assign(NECH, 20);
		unitKill(NECH, NECH, 19);
		kill(SPAWN, "SUBORDINATE");
		kill(NECHRYARCH, "SUPERIOR");
		String id = current().getSessionId();
		coordinator.finalizeForClientShutdownBlocking();
		restartPlugin();
		assertEquals(id, current().getSessionId());
		SessionAggregates.SlayerAssignmentRecord rec = last(current());
		assertEquals(Long.valueOf(3), rec.actualKills());
		assertEquals(Long.valueOf(1), rec.superiorKills());
	}
}
