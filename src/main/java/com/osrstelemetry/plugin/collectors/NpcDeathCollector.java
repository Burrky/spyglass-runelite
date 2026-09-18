package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.slayer.SlayerPluginService;

/**
 * Renamed from NpcKillCollector as part of an NPC kill telemetry
 * source audit, to correct the event's name to match what it actually
 * proves. Summarized here for anyone reading only this file.
 *
 * RENAME: NpcKillCollector -> NpcDeathCollector, EventType.NPC_KILL ->
 * EventType.NPC_DEATH, EventPayloads.NpcKill -> EventPayloads.NpcDeath,
 * collectNpcKills -> collectNpcDeaths. Nothing about the signal, the
 * filtering logic, or the payload fields changed — only the name.
 * "Kill" implied a player-attribution claim (the local player caused
 * this death / received credit for it) that ActorDeath cannot prove —
 * see the SCOPE LIMITATION paragraph below, which was already true
 * before this rename and is now reflected honestly in the name itself.
 *
 * SIGNAL SELECTED: net.runelite.api.events.ActorDeath, filtered to
 * actor instanceof NPC. Candidates investigated and rejected:
 *
 *  - net.runelite.api.events.NpcDespawned: fires for many non-death
 *    reasons (render-distance despawn, teleport-away, and critically
 *    the well-known OSRS mechanic of a boss's NPC ID despawning and a
 *    different NPC ID spawning mid-fight for a phase transition) —
 *    an unacceptable false-positive source with no reliable way to
 *    filter it back out from inside this plugin.
 *
 *  - net.runelite.client.events.NpcLootReceived (this project's
 *    existing LootCollector signal) and the newer, more
 *    server-authoritative net.runelite.client.events.ServerNpcLoot
 *    (Jagex's own in-game loot tracker feature, confirmed via
 *    RuneLite's core LootTrackerPlugin#onServerNpcLoot() source, which
 *    also has to manually filter out pickpocket-sourced loot via a
 *    tick guard — evidence that even the newer signal conflates
 *    non-death loot-granting mechanics): both are LOOT-triggered, not
 *    DEATH-triggered. A genuine death with zero drops (a real,
 *    routine case — e.g. a Slayer task killing a monster that simply
 *    didn't drop anything that hit) must still be observable as
 *    NPC_DEATH, so a loot-gated signal cannot be the source of truth
 *    for this event. NPC_LOOT_ATTRIBUTED (see LootCollector) remains
 *    this project's separate loot-specific event.
 *
 *  - A hand-rolled animation/health-based heuristic (watching
 *    AnimationChanged for known death-animation IDs, or
 *    Actor#getHealthRatio() reaching 0): rejected as a strictly
 *    worse reimplementation of what ActorDeath already does inside
 *    RuneLite's own client hooks, which maintain per-NPC
 *    death-animation exception handling this plugin has no practical
 *    way to replicate or keep in sync with.
 *
 * ActorDeath is RuneLite's own general-purpose death-detection
 * signal, confirmed via RuneLite's core ScreenshotPlugin#onActorDeath()
 * handler to fire for both Player and NPC actors (that handler's own
 * `instanceof Player` filter is only meaningful if the event also
 * fires for NPCs). One documented real false-negative edge case
 * exists (RuneLite GitHub issue #15471, a specific boss's phase-2
 * death animation failing to register ActorDeath) — an honest,
 * disclosed limitation affecting complex multi-phase boss content
 * specifically, not ordinary monster deaths. No false-positive
 * evidence was found for ActorDeath in this audit.
 *
 * SCOPE LIMITATION (disclosed, not silently assumed away — and the
 * exact reason this event is named NPC_DEATH, not NPC_KILL): ActorDeath
 * is an Actor/render-level "this actor died" signal, not a
 * damage-attribution signal — Actor has no "who dealt the killing
 * blow" accessor. This event therefore means "the local client
 * observed this specific NPC instance die," not "you personally
 * killed this NPC" — it will also fire for other players' kills of
 * nearby, non-instanced NPCs. No unverified interaction-based
 * filtering (e.g. npc.getInteracting() == local player) has been
 * added to narrow this, since no such filtering was requested and it
 * would itself be an unverified heuristic; this is intentionally the
 * same "don't guess without a defensible mapping" discipline already
 * applied below to Slayer-task attribution. A future NPC_KILL event
 * (local-player kill attribution specifically) may be considered
 * separately only if a reliable source for that specific fact is
 * verified — not built by inference on top of this event.
 *
 * SLAYER CORRELATION: activeSlayerTaskName/activeSlayerTaskLocation
 * are a LIVE read of SlayerPluginService's current task at the exact
 * moment of this death (never a timing-window correlation against a
 * separately-timestamped SLAYER_TASK_PROGRESS event — see
 * buildPayload()) — contextual metadata only. There is deliberately
 * no onSlayerTask=true/false claim anywhere on this payload: no
 * defensible RuneLite/Jagex mechanism was found for mapping a
 * specific dead NPC to a specific active Slayer assignment (e.g. a
 * random rat dying while Gargoyles is the active task must not read
 * as a Slayer kill merely because a task happens to be active).
 * Determining whether a given NPC_DEATH actually counted toward the
 * concurrently-active Slayer assignment is left as explicit future
 * work.
 *
 * DEDUPLICATION: none added. ActorDeath is a single, atomic @Value
 * event (unlike Slayer's multi-varp/varbit coalescing) with no
 * evidence found that RuneLite fires it more than once for one
 * death, so no dedup logic is introduced here — see
 * "Avoid complicated deduplication unless the chosen API actually
 * requires it."
 */
public class NpcDeathCollector
{
	private final Client client;
	private final SlayerPluginService slayerPluginService;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	@Inject
	public NpcDeathCollector(Client client, SlayerPluginService slayerPluginService, EventLedger eventLedger, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.slayerPluginService = slayerPluginService;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!config.collectNpcDeaths())
		{
			return;
		}

		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			// Player death (or any other Actor subtype) — out of scope,
			// not an NPC death observation.
			return;
		}

		NPC npc = (NPC) actor;

		WorldPoint location = npc.getWorldLocation();
		String worldLocation = location != null
			? formatWorldLocation(location.getX(), location.getY(), location.getPlane())
			: null;

		EventPayloads.NpcDeath payload = buildPayload(
			npc.getName(),
			npc.getId(),
			npc.getCombatLevel(),
			worldLocation,
			slayerPluginService.getRemainingAmount(),
			slayerPluginService.getTask(),
			slayerPluginService.getTaskLocation());

		eventLedger.append(client.getAccountHash(), EventType.NPC_DEATH, payload);
	}

	/**
	 * Pure, Client/EventLedger-independent core of the payload-building
	 * decision — factored out for the same reason as
	 * SlayerCollector.evaluateSlayerTransition(): unit-testable without
	 * mocking RuneLite's NPC/Client/SlayerPluginService (this project
	 * has no Mockito dependency and wasn't asked to add one — see
	 * BankSnapshotBaselineTest's javadoc for the same constraint applied
	 * before). Callers pass already-read primitive values; this method
	 * only decides how to normalize/gate them into the payload — it
	 * never reads from RuneLite APIs itself.
	 *
	 * npcId/combatLevel nullability: "do not fabricate missing values."
	 * NPC#getId() and Actor#getCombatLevel() carry no documented
	 * guarantee of always returning a real value (e.g. -1/0-or-below
	 * are the conventional "not currently known" results for these
	 * kinds of RuneLite accessors) — rather than assume a specific
	 * sentinel is impossible here, any non-positive raw value is
	 * treated as "not available" and left null instead of stored as a
	 * misleading not-actually-real ID/level.
	 *
	 * Slayer fields: attached only when slayerRemaining > 0 (a task is
	 * currently active) — this exactly mirrors SlayerCollector.
	 * resolveCurrent()'s own "only set task fields when remaining > 0"
	 * rule, so activeSlayerTaskName/activeSlayerTaskLocation are never
	 * populated from stale/leftover values of a just-completed task.
	 * This is a live snapshot of "what's active right now," never a
	 * claim about this specific NPC's relationship to that task, and
	 * never a claim about who caused the death.
	 */
	static EventPayloads.NpcDeath buildPayload(
		String npcName,
		int rawNpcId,
		int rawCombatLevel,
		String worldLocation,
		int slayerRemaining,
		String slayerTaskName,
		String slayerTaskLocation)
	{
		Integer npcId = rawNpcId >= 0 ? rawNpcId : null;
		Integer combatLevel = rawCombatLevel > 0 ? rawCombatLevel : null;

		boolean hasActiveTask = slayerRemaining > 0;
		String activeSlayerTaskName = hasActiveTask ? slayerTaskName : null;
		String activeSlayerTaskLocation = hasActiveTask ? slayerTaskLocation : null;

		return new EventPayloads.NpcDeath(npcName, npcId, combatLevel, worldLocation, activeSlayerTaskName, activeSlayerTaskLocation);
	}

	static String formatWorldLocation(int x, int y, int plane)
	{
		return x + "," + y + "," + plane;
	}
}
