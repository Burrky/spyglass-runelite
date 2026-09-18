package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Emits the non-authoritative EventType.BOSS_ACTIVITY_CONTEXT signal --
 * see that constant's javadoc for the full rationale and the exact
 * distinction from BOSS_KILL.
 *
 * SIGNAL SELECTED: net.runelite.api.events.InteractingChanged, filtered
 * to event.getSource() == client.getLocalPlayer() and
 * event.getTarget() instanceof NPC -- this choice rests on
 * well-established RuneLite API knowledge (InteractingChanged/
 * Actor#getInteracting()) plus this codebase's own prior source-level
 * reference to the exact same technique: NpcDeathCollector's class
 * javadoc already discusses `npc.getInteracting() == local player` as
 * a real, known filtering technique, previously considered and
 * rejected there for a DIFFERENT purpose (kill attribution -- too
 * strong a claim for that event). The purpose here is narrower and
 * different: activity CONTEXT evidence for session identity, not kill
 * or damage attribution, which is exactly what this technique is
 * suited for.
 *
 * NOT location-based: this collector never reads a WorldPoint or
 * region as primary proof of anything -- see class javadoc on
 * EventType.BOSS_ACTIVITY_CONTEXT.
 *
 * DEDUP / HEARTBEAT DESIGN: InteractingChanged already only fires when
 * RuneLite observes the interaction target actually change (not once
 * per game tick), so this collector is not defending against a raw
 * per-tick flood. What it DOES defend against is a single continuous
 * boss fight producing MANY InteractingChanged deliveries for the
 * SAME resolved boss -- real OSRS bosses routinely swap NPC instances/
 * sub-targets mid-fight (phase transitions, multi-NPC encounters like
 * Grotesque Guardians switching between Dawn and Dusk, a boss briefly
 * losing and regaining a valid interacting target). decide() tracks,
 * per account, the canonical boss name the player is CURRENTLY
 * understood to be engaging (lastTrackedBossByAccount) and only emits
 * when that resolved value actually CHANGES -- including changing to
 * null when the interaction ends or moves to an unresolved NPC. This
 * is deliberately NOT "emit once ever per account": clearing the
 * tracked value on a break means re-engaging the SAME boss later
 * (e.g. after fully disengaging, or after the fight moved through
 * unrelated trash NPCs) emits a fresh BOSS_ACTIVITY_CONTEXT signal,
 * which matters for genuinely resuming a SUSPENDED BOSSING session via
 * ActivitySignalClassifier.decideCombatBranch()'s own exact-identity-
 * match fast path -- not merely for the very first encounter.
 *
 * NO PLAYER-FACING TOGGLE: unlike some other collectors in this
 * project (e.g. NpcDeathCollector/collectNpcDeaths(),
 * ServerNpcLootCollector/collectLoot()), this collector runs
 * unconditionally whenever the plugin/session runtime is enabled --
 * there is deliberately no collectBossActivityContext() config item.
 * This project's architecture does not expose a per-signal collection
 * toggle for this signal; the earlier config toggle and its check here
 * were removed (see PR history for the fix).
 */
public class BossActivityContextCollector
{
	private final Client client;
	private final KnownBossRegistry knownBossRegistry;
	private final EventLedger eventLedger;

	/**
	 * Per-account "what boss (if any) is the local player currently
	 * understood to be engaging" -- see class javadoc's DEDUP /
	 * HEARTBEAT DESIGN section. Absent/null means "not currently
	 * engaging a known boss."
	 */
	private final Map<Long, String> lastTrackedBossByAccount = new ConcurrentHashMap<>();

	@Inject
	public BossActivityContextCollector(Client client, KnownBossRegistry knownBossRegistry, EventLedger eventLedger)
	{
		this.client = client;
		this.knownBossRegistry = knownBossRegistry;
		this.eventLedger = eventLedger;
	}

	public void resetForAccountSwitch(long accountHash)
	{
		lastTrackedBossByAccount.remove(accountHash);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Actor source = event.getSource();
		boolean sourceIsLocalPlayer = source != null && source == client.getLocalPlayer();
		if (!sourceIsLocalPlayer)
		{
			// Not the local player's own interaction (another player or
			// NPC interacting with something) -- irrelevant, and left
			// untouched: see decide()'s own doc for why this branch never
			// mutates tracked state either.
			return;
		}

		Actor target = event.getTarget();
		String npcName = (target instanceof NPC) ? ((NPC) target).getName() : null;

		long accountHash = client.getAccountHash();
		String candidate = decide(true, npcName, knownBossRegistry, accountHash, lastTrackedBossByAccount);
		if (candidate == null)
		{
			return;
		}

		eventLedger.append(accountHash, EventType.BOSS_ACTIVITY_CONTEXT, new EventPayloads.BossActivityContext(candidate));
	}

	/**
	 * Pure core decision, factored out for the same reason as
	 * NpcDeathCollector.buildPayload(): unit-testable without mocking
	 * RuneLite's EventBus/Client/Actor/NPC types (this project has no
	 * Mockito dependency). Takes already-read primitive/plain-object
	 * values only -- KnownBossRegistry is itself RuneLite-API-free (see
	 * its own class javadoc), so this method never touches RuneLite at
	 * all.
	 *
	 * `trackedByAccount` is mutated as a side effect (updated to the new
	 * resolved value, or removed on a break) -- deliberately not a pure
	 * function in the strictest sense, but this mirrors how the
	 * production caller (onInteractingChanged()) actually needs to use
	 * it, and keeps a single source of truth for the dedup/heartbeat
	 * decision rather than splitting it across two methods that could
	 * drift out of sync. A plain java.util.Map (e.g. a HashMap) is
	 * sufficient for tests -- no RuneLite type is required.
	 *
	 * @return the canonical boss display name to emit as a new
	 * BOSS_ACTIVITY_CONTEXT signal, or null if nothing should be emitted
	 * for this call (source not the local player, target not a
	 * recognized boss NPC, or the resolved boss is unchanged from what
	 * is already tracked for this account).
	 */
	static String decide(
		boolean sourceIsLocalPlayer,
		String npcName,
		KnownBossRegistry registry,
		long accountHash,
		Map<Long, String> trackedByAccount)
	{
		if (!sourceIsLocalPlayer)
		{
			return null;
		}

		String resolved = npcName == null ? null : registry.resolveCandidateBossName(accountHash, npcName);
		String previouslyTracked = trackedByAccount.get(accountHash);

		if (resolved == null)
		{
			// Interaction ended, or target is not a recognized boss NPC --
			// clear tracked state so a later re-engagement with a
			// genuinely-known boss (including the SAME one) is treated as
			// fresh evidence rather than deduped away.
			trackedByAccount.remove(accountHash);
			return null;
		}

		if (resolved.equals(previouslyTracked))
		{
			return null;
		}

		trackedByAccount.put(accountHash, resolved);
		return resolved;
	}
}
