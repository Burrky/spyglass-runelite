package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.osrstelemetry.plugin.events.EventPayloads;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-network caveat as everywhere else
 * in this project's test module (see plugin/README.md).
 *
 * Renamed from NpcKillCollectorTest (event truth-semantics
 * correction) — targets NpcDeathCollector.buildPayload()/
 * formatWorldLocation(), unchanged from the prior NpcKillCollector
 * versions apart from the rename. This is the pure Client/EventLedger/
 * RuneLite-API-independent core of the NPC_DEATH payload decision —
 * factored out for the same reason as
 * SlayerCollector.evaluateSlayerTransition(): unit-testable without
 * mocking RuneLite's NPC/Client/SlayerPluginService (this project has
 * no Mockito dependency — see BankSnapshotBaselineTest's javadoc for
 * the same constraint noted before).
 *
 * The audit's minimum test list for NPC_DEATH is mostly architectural,
 * not unit-testable in isolation without mocking RuneLite's
 * EventBus/ActorDeath/NPC types this project deliberately avoids
 * depending on for tests; where that's the case, the guarantee and
 * where to see it enforced by construction is noted in a comment
 * instead of a fabricated test:
 *
 *  - "ActorDeath with NPC -> NPC_DEATH": enforced by onActorDeath()
 *    calling eventLedger.append(..., EventType.NPC_DEATH, ...) exactly
 *    once per invocation, unconditionally on a successful instanceof
 *    NPC check (see NpcDeathCollector.onActorDeath()).
 *  - "player ActorDeath -> no NPC_DEATH": enforced by the same method's
 *    `if (!(actor instanceof NPC)) { return; }` guard — a Player actor
 *    never reaches the append() call.
 *  - "active Slayer task context does not imply on-task attribution" /
 *    "no dependency on Slayer progress": tested directly below
 *    (scenarios 5-7) — buildPayload() never gates emission on
 *    slayerRemaining, only whether to attach contextual task-name/
 *    location fields, and never produces any onSlayerTask-style
 *    boolean.
 *  - "no dependency on loot": enforced by construction —
 *    NpcDeathCollector has no reference to LootCollector, NpcLootAttributed,
 *    or EventType.NPC_LOOT_ATTRIBUTED anywhere in its source.
 *  - "config gating still works": onActorDeath()'s first line is
 *    `if (!config.collectNpcDeaths()) { return; }` — architectural,
 *    same pattern as every other collector in this project (see
 *    OsrsTelemetryConfig javadoc: "read live by its collector on every
 *    relevant event").
 *  - "null/unavailable NPC metadata is not fabricated": tested directly
 *    below (scenarios 2-3).
 */
public class NpcDeathCollectorTest
{
	// --- formatWorldLocation ---

	@Test
	public void formatWorldLocation_buildsCommaSeparatedTriple()
	{
		assertEquals("3210,3424,0", NpcDeathCollector.formatWorldLocation(3210, 3424, 0));
	}

	// --- npcId / combatLevel nullability ("do not fabricate missing values") ---

	@Test
	public void buildPayload_positiveIdAndCombatLevel_areKept()
	{
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Gargoyle", 111, 89, "1,2,0", 0, null, null);

		assertEquals("Gargoyle", payload.getNpcName());
		assertEquals(Integer.valueOf(111), payload.getNpcId());
		assertEquals(Integer.valueOf(89), payload.getCombatLevel());
	}

	@Test
	public void buildPayload_negativeRawId_isNulledNotFabricated()
	{
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Gargoyle", -1, 89, "1,2,0", 0, null, null);

		assertNull(payload.getNpcId());
	}

	@Test
	public void buildPayload_nonPositiveCombatLevel_isNulledNotFabricated()
	{
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Gargoyle", 111, 0, "1,2,0", 0, null, null);

		assertNull(payload.getCombatLevel());
	}

	// --- Slayer correlation: contextual only, never a death-emission gate, never on-task attribution ---

	@Test
	public void buildPayload_noActiveTask_slayerFieldsNull()
	{
		// A non-task NPC death while no Slayer assignment exists at all.
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Chicken", 41, 1, "1,2,0", 0, null, null);

		assertNull(payload.getActiveSlayerTaskName());
		assertNull(payload.getActiveSlayerTaskLocation());
	}

	@Test
	public void buildPayload_activeTask_attachesContextualTaskFieldsOnly()
	{
		// Normal on-task death: Gargoyles active, remaining > 0. Context
		// only — this test does not (and the payload cannot) assert any
		// "this death counted toward the task" claim, because no such
		// field exists.
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Gargoyle", 111, 89, "1,2,0", 138, "Gargoyles", "Wilderness Slayer Cave");

		assertEquals("Gargoyles", payload.getActiveSlayerTaskName());
		assertEquals("Wilderness Slayer Cave", payload.getActiveSlayerTaskLocation());
	}

	@Test
	public void buildPayload_unrelatedNpcWhileTaskActive_stillAttachesTaskContextOnly()
	{
		// The audit's own worked example: a chicken dies while Gargoyles
		// is assigned. Task context is attached (it is genuinely active),
		// but nothing on this payload claims the chicken IS on-task —
		// there is no onSlayerTask field at all, and the dead NPC's own
		// identity (npcName) is completely independent of the attached
		// task fields.
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Chicken", 2854, 1, "1,2,0", 138, "Gargoyles", "Wilderness Slayer Cave");

		assertEquals("Chicken", payload.getNpcName());
		assertEquals("Gargoyles", payload.getActiveSlayerTaskName());
	}

	@Test
	public void buildPayload_zeroRemaining_treatedAsNoActiveTask()
	{
		// Mirrors SlayerCollector.resolveCurrent()'s own "only set task
		// fields when remaining > 0" convention — a just-completed task
		// (remaining == 0) must not leak stale task name/location onto
		// an unrelated death.
		EventPayloads.NpcDeath payload = NpcDeathCollector.buildPayload(
			"Cow", 81, 2, "1,2,0", 0, "Gargoyles", "Wilderness Slayer Cave");

		assertNull(payload.getActiveSlayerTaskName());
		assertNull(payload.getActiveSlayerTaskLocation());
	}
}
