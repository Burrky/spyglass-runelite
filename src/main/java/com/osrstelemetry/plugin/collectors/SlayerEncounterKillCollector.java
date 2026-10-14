package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.session.SlayerEncounterKills;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * ACTUAL KILLS of the active Slayer task's encounter (never task units).
 * <p>
 * An NPC death is OWNED only with direct native evidence that the local
 * player was in that fight: at death the NPC was interacting with the local
 * player, or the local player was interacting with it, or the local player
 * targeted that exact NPC (by index) within {@link #TARGET_MEMORY_TICKS}. A
 * random NPC dying nearby is never owned. An owned death becomes a
 * SLAYER_ENCOUNTER_KILL only while a Slayer task is active (the same
 * committed task NpcDeathCollector reads) and only when the NPC belongs to
 * that task's encounter -- its own target, a subordinate it summons, or its
 * superior (SlayerEncounterKills, explicit tables). Exact NPC identity is
 * kept; nothing is flattened into the task's name. Event-driven; no scene
 * scans.
 */
@Singleton
public class SlayerEncounterKillCollector
{
	/** How long a target the player attacked still owns its death (a few attack cycles). */
	static final int TARGET_MEMORY_TICKS = 16;
	private static final int MAX_REMEMBERED = 32;

	private final Client client;
	private final SlayerCommittedTask committedTask;
	private final EventLedger eventLedger;
	/** NPC index -> tick the local player last targeted it (bounded). */
	private final Map<Integer, Integer> targeted = new LinkedHashMap<Integer, Integer>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest)
		{
			return size() > MAX_REMEMBERED;
		}
	};

	@Inject
	public SlayerEncounterKillCollector(Client client, SlayerCommittedTask committedTask, EventLedger eventLedger)
	{
		this.client = client;
		this.committedTask = committedTask;
		this.eventLedger = eventLedger;
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Player me = client.getLocalPlayer();
		if (me != null && event.getSource() == me && event.getTarget() instanceof NPC)
		{
			targeted.remove(((NPC) event.getTarget()).getIndex());
			targeted.put(((NPC) event.getTarget()).getIndex(), client.getTickCount());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) actor;
		Player me = client.getLocalPlayer();
		Integer lastTargeted = targeted.remove(npc.getIndex());
		boolean owned = me != null && (npc.getInteracting() == me || me.getInteracting() == npc
			|| (lastTargeted != null && client.getTickCount() - lastTargeted <= TARGET_MEMORY_TICKS));
		Integer id = npc.getId() >= 0 ? npc.getId() : null;
		String role = encounterRole(owned, committedTask.getRemaining(), committedTask.getTaskName(), id, npc.getName());
		if (role != null && npc.getName() != null)
		{
			eventLedger.append(client.getAccountHash(), EventType.SLAYER_ENCOUNTER_KILL,
				new EventPayloads.SlayerEncounterKill(npc.getName(), id, role));
		}
	}

	/**
	 * Pure decision: the role of an owned death during an active task (the
	 * committed task's remaining &gt; 0 -- it still carries the task on the
	 * completing kill's own tick, exactly as NpcDeathCollector relies on);
	 * null when not an actual kill of the task's encounter, or while awaiting
	 * a new task.
	 */
	static String encounterRole(boolean owned, int remaining, String taskName, Integer npcId, String npcName)
	{
		if (!owned || taskName == null || remaining <= 0)
		{
			return null;
		}
		SlayerEncounterKills.Role role = SlayerEncounterKills.role(taskName, npcId, npcName);
		return role == null ? null : role.name();
	}
}
