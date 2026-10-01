package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.SlayerXpScope;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * SLAYER XP SCOPE = CURRENT TASK -- card VISIBILITY through the real
 * coordinator pipeline: only skills with &gt; 0 XP in the CURRENT assignment
 * get a card. A pure display rule: canonical session XP and the
 * per-assignment XP model are asserted unchanged alongside.
 */
public class CurrentTaskXpCardVisibilityTest extends SlayerCoordinatorTestSupport
{
	private static Map<String, Long> cards(List<CurrentSessionSnapshot.XpEntry> entries)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (CurrentSessionSnapshot.XpEntry e : entries)
		{
			m.put(e.getSkill(), e.getXpGained());
		}
		return m;
	}

	private static Set<String> set(String... skills)
	{
		return new HashSet<>(Arrays.asList(skills));
	}

	private SessionAggregates.SlayerAssignmentRecord currentRecord()
	{
		List<SessionAggregates.SlayerAssignmentRecord> l = coordinator.testCurrentSession().getAggregates().getSlayerAssignments();
		return l.get(l.size() - 1);
	}

	/** Nechryael task with DEFENCE/HITPOINTS/PRAYER/MAGIC XP, completed; then Kalphites assigned at T0+120. */
	private Instant broadSessionThenNewKalphiteTask()
	{
		feed(SessionSignal.slayerTaskProgress(T0, NECH, NECH_LOC, 1, 2), T0);
		xp("DEFENCE", 1000L, T0);
		xp("HITPOINTS", 330L, T0);
		xp("PRAYER", 50L, T0);
		xp("MAGIC", 20L, T0);
		feed(SessionSignal.slayerTaskProgress(T0.plusSeconds(60), NECH, NECH_LOC, 1, 1), T0.plusSeconds(60));
		feed(SessionSignal.slayerTaskCompleted(T0.plusSeconds(61), NECH, null), T0.plusSeconds(61));
		Instant assigned = T0.plusSeconds(120);
		feed(SessionSignal.slayerTaskAssigned(assigned, KALPHITES, KALPHITE_LOC, 100), assigned);
		return assigned;
	}

	// 1 + 4 + 6 -----------------------------------------------------------
	@Test
	public void newAssignment_zeroTaskXp_rendersNoCards_eventhoughTheSessionHasOldSkillXp()
	{
		Instant assigned = broadSessionThenNewKalphiteTask();
		CurrentSessionSnapshot v = ui(assigned);

		assertTrue("Current Task: no +0 wall -- zero cards", v.getXpEntries(SlayerXpScope.CURRENT_TASK).isEmpty());
		assertEquals("All Tasks still shows the whole session",
			set("DEFENCE", "HITPOINTS", "PRAYER", "MAGIC"), cards(v.getXpEntries(SlayerXpScope.ALL_TASKS)).keySet());
		assertEquals("canonical session XP untouched", Long.valueOf(1000L),
			coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().get("DEFENCE"));
		assertTrue("assignment XP model untouched: fresh empty map", currentRecord().getXpGainedBySkill().isEmpty());
	}

	// 2 + 3 --------------------------------------------------------------
	@Test
	public void firstTaskXp_addsOnlyThatCard_thenEachNewSkillAppearsAsItGainsXp()
	{
		Instant assigned = broadSessionThenNewKalphiteTask();
		Instant kill = assigned.plusSeconds(30);
		feed(SessionSignal.slayerTaskProgress(kill, KALPHITES, KALPHITE_LOC, 1, 99), kill);
		xp("RANGED", 136L, kill);
		assertEquals("first task XP -> exactly that card", set("RANGED"),
			cards(ui(kill.plusSeconds(40)).getXpEntries(SlayerXpScope.CURRENT_TASK)).keySet());

		xp("HITPOINTS", 46L, kill.plusSeconds(10));
		xp("SLAYER", 34L, kill.plusSeconds(10));
		Map<String, Long> task = cards(ui(kill.plusSeconds(60)).getXpEntries(SlayerXpScope.CURRENT_TASK));
		assertEquals(set("RANGED", "HITPOINTS", "SLAYER"), task.keySet());
		assertEquals(Long.valueOf(136L), task.get("RANGED"));
		assertEquals("task-local HITPOINTS only, not the session's 376", Long.valueOf(46L), task.get("HITPOINTS"));
		assertEquals(Long.valueOf(34L), task.get("SLAYER"));
		for (CurrentSessionSnapshot.XpEntry e : ui(kill.plusSeconds(60)).getXpEntries(SlayerXpScope.CURRENT_TASK))
		{
			assertTrue("never a +0 card: " + e.getSkill(), e.getXpGained() > 0);
		}
	}

	// 5 + 6 + 7 + 8 ------------------------------------------------------
	@Test
	public void completion_keepsFrozenNonZeroCards_scopeTogglesExact_newAssignmentClears()
	{
		Instant assigned = broadSessionThenNewKalphiteTask();
		Instant kill = assigned.plusSeconds(30);
		feed(SessionSignal.slayerTaskProgress(kill, KALPHITES, KALPHITE_LOC, 1, 1), kill);
		xp("RANGED", 136L, kill);
		xp("HITPOINTS", 46L, kill);
		xp("SLAYER", 34L, kill);
		Instant completedAt = kill.plusSeconds(40);
		feed(SessionSignal.slayerTaskCompleted(completedAt, KALPHITES, null), completedAt);
		xp("MAGIC", 30L, completedAt.plusSeconds(20)); // travel XP after completion

		// 5: AWAITING_TASK keeps the completed task's non-zero cards, frozen.
		CurrentSessionSnapshot a = ui(completedAt.plusSeconds(60));
		List<CurrentSessionSnapshot.XpEntry> taskA = a.getXpEntries(SlayerXpScope.CURRENT_TASK);
		assertEquals(set("RANGED", "HITPOINTS", "SLAYER"), cards(taskA).keySet());
		CurrentSessionSnapshot b = ui(completedAt.plusSeconds(600));
		assertEquals("values frozen", cards(taskA), cards(b.getXpEntries(SlayerXpScope.CURRENT_TASK)));
		assertEquals("rates frozen", rates(taskA), rates(b.getXpEntries(SlayerXpScope.CURRENT_TASK)));

		// 7: All Tasks restores the whole-session card set from the same snapshot.
		assertEquals(set("DEFENCE", "HITPOINTS", "PRAYER", "MAGIC", "RANGED", "SLAYER"),
			cards(b.getXpEntries(SlayerXpScope.ALL_TASKS)).keySet());
		assertEquals(Long.valueOf(376L), cards(b.getXpEntries(SlayerXpScope.ALL_TASKS)).get("HITPOINTS"));
		assertEquals(b.getXpEntries(), b.getXpEntries(SlayerXpScope.ALL_TASKS));
		// 8: and back to Current Task gives only the assignment's non-zero cards.
		assertEquals(set("RANGED", "HITPOINTS", "SLAYER"), cards(b.getXpEntries(SlayerXpScope.CURRENT_TASK)).keySet());

		// 6: a new assignment starts clean.
		Instant next = completedAt.plusSeconds(700);
		feed(SessionSignal.slayerTaskAssigned(next, NECH, NECH_LOC, 150), next);
		CurrentSessionSnapshot c = ui(next);
		assertTrue("old assignment's cards disappear", c.getXpEntries(SlayerXpScope.CURRENT_TASK).isEmpty());
		assertEquals(6, c.getXpEntries(SlayerXpScope.ALL_TASKS).size());
	}

	private static Map<String, Long> rates(List<CurrentSessionSnapshot.XpEntry> entries)
	{
		Map<String, Long> m = new LinkedHashMap<>();
		for (CurrentSessionSnapshot.XpEntry e : entries)
		{
			m.put(e.getSkill(), e.getXpPerHour());
		}
		return m;
	}
}
