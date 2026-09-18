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
import net.runelite.api.NPCComposition;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Emits the non-authoritative, context-only
 * EventType.NPC_INTERACTION_TARGET signal -- see that constant's
 * javadoc for the full rationale.
 *
 * SIGNAL SELECTED: net.runelite.api.events.InteractingChanged, filtered
 * to event.getSource() == client.getLocalPlayer() and
 * event.getTarget() instanceof NPC -- the EXACT same technique
 * BossActivityContextCollector already uses (and Josh's own Windows
 * build already proved compiles/works against the real client jar for
 * that collector), deliberately reused rather than reinvented. The one
 * addition here is an explicit ATTACKABILITY check (see
 * isAttackableNpc()) so this collector never mistakes a banker,
 * shopkeeper, quest NPC, pet, or other merely-talked-to NPC for a
 * combat target merely because the player clicked it -- something
 * BossActivityContextCollector does not need, since KnownBossRegistry's
 * own name resolution already only ever matches previously-confirmed
 * boss names.
 *
 * ATTACKABLE NPC FILTERING: reads {@code npc.getTransformedComposition()}
 * (not the raw {@code npc.getComposition()}) -- the standard RuneLite
 * idiom for resolving an NPC's CURRENT visible form/composition,
 * correct across multi-form/transforming NPCs (quest-state swaps,
 * disguises, boss phase changes) without this collector needing to
 * understand any specific transform table itself. An NPC is treated as
 * genuinely attackable only when that composition's
 * {@code getActions()} array contains the exact menu-action string
 * "Attack" (the same right-click "Attack" option every attackable NPC
 * in OSRS exposes) -- a banker's "Bank"/"Collect"/"Exchange" actions,
 * a shopkeeper's "Trade", or a quest NPC's "Talk-to" never include
 * this, so they are correctly excluded with zero hand-maintained
 * NPC-name/ID list. {@code getTransformedComposition()} returning null
 * (an NPC mid-transform frame, or any other unresolved-composition
 * case) and a null/empty {@code getActions()} array are both handled
 * defensively as "not attackable" -- never as "attackable by default."
 *
 * DEDUP / HEARTBEAT DESIGN: mirrors BossActivityContextCollector's own
 * decide()/lastTrackedBossByAccount precedent exactly, for the exact
 * same reason -- a single continuous fight can produce many
 * InteractingChanged deliveries for the SAME attackable NPC name
 * (instance swaps, sub-target flicker) that would otherwise flood
 * events.jsonl with redundant NPC_INTERACTION_TARGET signals carrying
 * no new information. decide() tracks, per account, the attackable NPC
 * name the player is CURRENTLY understood to be targeting and only
 * emits when that name actually CHANGES -- including changing to null
 * when the interaction ends or moves to a non-attackable/non-NPC
 * target, so re-engaging the SAME NPC later (after a genuine break)
 * emits fresh context again rather than staying permanently deduped.
 * Deliberately keyed by NAME ONLY, not by NPC id -- see
 * ActivitySignalClassifier.genericNpcCombatIdentity()'s own javadoc for
 * why generic combat identity itself is name-based (a boss/monster that
 * legitimately changes runtime NPC id mid-fight, e.g. a phase
 * transition, must not read as "a different NPC" here either).
 *
 * NO PLAYER-FACING TOGGLE: matches BossActivityContextCollector's own
 * "runs unconditionally whenever the plugin/session runtime is
 * enabled" design -- there is deliberately no config item gating this
 * collector.
 */
public class NpcInteractionTargetCollector
{
	private final Client client;
	private final EventLedger eventLedger;

	/**
	 * Per-account "which attackable NPC name (if any) is the local
	 * player currently understood to be targeting" -- see class
	 * javadoc's DEDUP / HEARTBEAT DESIGN section. Absent/null means "not
	 * currently targeting a recognized attackable NPC."
	 */
	private final Map<Long, String> lastTrackedNpcNameByAccount = new ConcurrentHashMap<>();

	/**
	 * Parallel to lastTrackedNpcNameByAccount -- the numeric NPC id last observed for
	 * whichever attackable NPC the player is currently tracked as
	 * targeting (see onInteractingChanged()). Kept in sync whenever a
	 * name is tracked/cleared, independent of decide()'s own dedup gate,
	 * so it always reflects the freshest observed id for the current
	 * target even across an id change (e.g. a phase/instance swap) that
	 * decide() itself treats as "same NPC, no new signal." Purely
	 * read-only UI plumbing -- getCurrentNpcName()/getCurrentNpcId()
	 * below are the only new surface this adds; onInteractingChanged()'s
	 * own telemetry emission and decide()'s dedup logic are unchanged.
	 */
	private final Map<Long, Integer> lastTrackedNpcIdByAccount = new ConcurrentHashMap<>();

	@Inject
	public NpcInteractionTargetCollector(Client client, EventLedger eventLedger)
	{
		this.client = client;
		this.eventLedger = eventLedger;
	}

	public void resetForAccountSwitch(long accountHash)
	{
		lastTrackedNpcNameByAccount.remove(accountHash);
		lastTrackedNpcIdByAccount.remove(accountHash);
	}

	/**
	 * The attackable NPC name the local player is CURRENTLY understood to be targeting (the same
	 * value this collector would emit if decide() saw a change right
	 * now), or null. Safe to call off the client thread -- reads only
	 * this collector's own ConcurrentHashMaps and
	 * client.getAccountHash() (a cached identifier -- see
	 * OsrsTelemetryPlugin.shutDown()'s own comment on that same call
	 * being safe off-thread).
	 */
	public String getCurrentNpcName()
	{
		return lastTrackedNpcNameByAccount.get(client.getAccountHash());
	}

	/** Companion to getCurrentNpcName() -- the same target's NPC id, or null. */
	public Integer getCurrentNpcId()
	{
		return lastTrackedNpcIdByAccount.get(client.getAccountHash());
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Actor source = event.getSource();
		boolean sourceIsLocalPlayer = source != null && source == client.getLocalPlayer();
		if (!sourceIsLocalPlayer)
		{
			// Not the local player's own interaction -- irrelevant, and
			// left untouched: see decide()'s own doc for why this branch
			// never mutates tracked state either.
			return;
		}

		Actor target = event.getTarget();
		NPC npc = (target instanceof NPC) ? (NPC) target : null;

		String attackableNpcName = null;
		Integer npcId = null;
		if (npc != null)
		{
			NPCComposition composition = safeTransformedComposition(npc);
			String[] actions = composition == null ? null : safeActions(composition);
			if (isAttackableNpc(actions))
			{
				String name = safeName(composition);
				if (name != null)
				{
					attackableNpcName = name;
					npcId = safeNpcId(npc);
				}
			}
		}

		long accountHash = client.getAccountHash();

		// Keep the id map in sync with the name map on every call,
		// independent of decide()'s own dedup gate below -- see
		// lastTrackedNpcIdByAccount's own javadoc.
		if (attackableNpcName != null)
		{
			lastTrackedNpcIdByAccount.put(accountHash, npcId);
		}
		else
		{
			lastTrackedNpcIdByAccount.remove(accountHash);
		}

		String candidate = decide(true, attackableNpcName, accountHash, lastTrackedNpcNameByAccount);
		if (candidate == null)
		{
			return;
		}

		eventLedger.append(accountHash, EventType.NPC_INTERACTION_TARGET, new EventPayloads.NpcInteractionTarget(npcId, candidate));
	}

	private static NPCComposition safeTransformedComposition(NPC npc)
	{
		try
		{
			return npc.getTransformedComposition();
		}
		catch (RuntimeException e)
		{
			// Defensive only -- "handle null names/compositions/actions
			// safely" (HARD PRODUCT REQUIREMENT). Treated as
			// "unknown/not attackable" rather than propagating, exactly
			// like every other defensive read in this project (e.g.
			// SessionRuntimeCoordinator.safeTickCount()).
			return null;
		}
	}

	private static String[] safeActions(NPCComposition composition)
	{
		try
		{
			return composition.getActions();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String safeName(NPCComposition composition)
	{
		try
		{
			return composition.getName();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * npcId mirrors NpcDeathCollector.buildPayload()'s own "do not
	 * fabricate missing values" precedent: NPC#getId() carries no
	 * documented guarantee of always returning a real value (-1 is the
	 * conventional "not currently known" result, e.g. a mid-transform
	 * frame) -- any non-positive raw id is left null.
	 */
	private static Integer safeNpcId(NPC npc)
	{
		int rawId = npc.getId();
		return rawId >= 0 ? rawId : null;
	}

	/**
	 * Pure core attackability check, factored out so it is directly
	 * unit-testable with plain String[] input -- no NPCComposition
	 * mock/instance is needed (this project has no Mockito dependency).
	 * "Attack" is the exact, case-sensitive menu-action string OSRS/
	 * RuneLite uses for the ordinary attack option; a null actions array
	 * (unresolved composition) or a null-only/empty array (an NPC with
	 * no menu actions at all) both safely return false -- never true by
	 * default.
	 */
	static boolean isAttackableNpc(String[] actions)
	{
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Attack".equals(action))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Pure core decision, factored out for the same reason as
	 * BossActivityContextCollector.decide() -- unit-testable without
	 * mocking RuneLite's EventBus/Client/Actor/NPC/NPCComposition types.
	 * `attackableNpcName` is null whenever the production caller already
	 * determined the interaction target is not the local player's own,
	 * not an NPC at all, not attackable, or has no resolvable name --
	 * this method makes no RuneLite-aware decision of its own, only the
	 * dedup/heartbeat one.
	 *
	 * `trackedByAccount` is mutated as a side effect, mirroring
	 * BossActivityContextCollector.decide()'s own documented tradeoff.
	 *
	 * @return the attackable NPC's display name to emit as a new
	 * NPC_INTERACTION_TARGET signal, or null if nothing should be
	 * emitted for this call (source not the local player, no attackable
	 * NPC target, or the tracked target name is unchanged from what is
	 * already tracked for this account).
	 */
	static String decide(
		boolean sourceIsLocalPlayer,
		String attackableNpcName,
		long accountHash,
		Map<Long, String> trackedByAccount)
	{
		if (!sourceIsLocalPlayer)
		{
			return null;
		}

		if (attackableNpcName == null)
		{
			// Interaction ended, target isn't an NPC, or the NPC isn't
			// genuinely attackable -- clear tracked state so a later
			// re-engagement with a genuinely attackable NPC (including
			// the SAME one) is treated as fresh evidence rather than
			// deduped away.
			trackedByAccount.remove(accountHash);
			return null;
		}

		String previouslyTracked = trackedByAccount.get(accountHash);
		if (attackableNpcName.equals(previouslyTracked))
		{
			return null;
		}

		trackedByAccount.put(accountHash, attackableNpcName);
		return attackableNpcName;
	}
}
