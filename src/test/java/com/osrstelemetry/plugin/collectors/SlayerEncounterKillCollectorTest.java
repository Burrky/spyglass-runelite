package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * Actual-kill ownership from native evidence only: a death is the player's
 * when the NPC was fighting the player, the player was attacking it, or the
 * player targeted it moments before; only during an active task; only for
 * the task's own targets, subordinates and superiors (by exact id) -- a
 * random NPC dying nearby never is.
 */
public class SlayerEncounterKillCollectorTest
{
	private static final Gson GSON = new Gson();

	private final List<EventPayloads.SlayerEncounterKill> kills = new ArrayList<>();
	private int tick = 100;
	private Actor myTarget;
	private final Player me = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
		(p, m, a) -> "getInteracting".equals(m.getName()) ? this.myTarget : "hashCode".equals(m.getName()) ? 1 : "equals".equals(m.getName())
			? p == a[0] : null);

	private SlayerEncounterKillCollector collector(String task, int remaining)
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getLocalPlayer":
					return me;
				case "getTickCount":
					return tick;
				case "getAccountHash":
					return 1L;
				default:
					return null;
			}
		});
		SlayerCommittedTask committed = new SlayerCommittedTask()
		{
			@Override
			public String getTaskName()
			{
				return task;
			}

			@Override
			public int getRemaining()
			{
				return remaining;
			}
		};
		EventLedger ledger = new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				if (type == EventType.SLAYER_ENCOUNTER_KILL)
				{
					kills.add((EventPayloads.SlayerEncounterKill) payload);
				}
			}
		};
		return new SlayerEncounterKillCollector(client, committed, ledger);
	}

	private NPC npc(String name, int id, int index, Actor interacting)
	{
		return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getName":
					return name;
				case "getId":
					return id;
				case "getIndex":
					return index;
				case "getInteracting":
					return interacting;
				case "hashCode":
					return index;
				case "equals":
					return p == a[0];
				default:
					return null;
			}
		});
	}

	@Test
	public void fightingThePlayer_targetAndSubordinateAndSuperior_areActualKills()
	{
		SlayerEncounterKillCollector c = collector("Nechryael", 100);
		c.onActorDeath(new ActorDeath(npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 1, me)));
		c.onActorDeath(new ActorDeath(npc("Death spawn", NpcID.SLAYER_NECHRYAEL_SPAWN, 2, me)));
		c.onActorDeath(new ActorDeath(npc("Nechryarch", NpcID.SUPERIOR_NECHRYAEL, 3, me)));
		assertEquals(3, kills.size());
		assertEquals("TASK", kills.get(0).getRole());
		assertEquals("SUBORDINATE", kills.get(1).getRole());
		assertEquals("Death spawn", kills.get(1).getNpcName());
		assertEquals("SUPERIOR", kills.get(2).getRole());
		assertEquals(Integer.valueOf(NpcID.SUPERIOR_NECHRYAEL), kills.get(2).getNpcId());
	}

	@Test
	public void attackedByThePlayer_orTargetedMomentsBefore_isOwned()
	{
		SlayerEncounterKillCollector c = collector("Nechryael", 100);
		NPC fleeing = npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 5, null);
		myTarget = fleeing;
		c.onActorDeath(new ActorDeath(fleeing));
		myTarget = null;
		NPC targeted = npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 6, null);
		c.onInteractingChanged(new InteractingChanged(me, targeted));
		tick += 5;
		c.onActorDeath(new ActorDeath(targeted));
		assertEquals(2, kills.size());
		NPC stale = npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 7, null);
		c.onInteractingChanged(new InteractingChanged(me, stale));
		tick += SlayerEncounterKillCollector.TARGET_MEMORY_TICKS + 1;
		c.onActorDeath(new ActorDeath(stale));
		assertEquals("a target long abandoned is not this player's kill", 2, kills.size());
	}

	@Test
	public void unrelatedNearbyNpc_orSomeoneElsesKill_isNeverTaskOwned()
	{
		SlayerEncounterKillCollector c = collector("Nechryael", 100);
		c.onActorDeath(new ActorDeath(npc("Rat", 2_854, 9, me)));
		c.onActorDeath(new ActorDeath(npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 10, null)));
		assertTrue(kills.isEmpty());
	}

	@Test
	public void noActiveTask_nothingIsCredited()
	{
		SlayerEncounterKillCollector awaiting = collector("Nechryael", 0);
		awaiting.onActorDeath(new ActorDeath(npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 1, me)));
		SlayerEncounterKillCollector none = collector(null, 0);
		none.onActorDeath(new ActorDeath(npc("Nechryael", NpcID.SLAYER_NECHRYAEL, 1, me)));
		assertTrue(kills.isEmpty());
		assertNull(SlayerEncounterKillCollector.encounterRole(false, 50, "Nechryael", NpcID.SLAYER_NECHRYAEL, "Nechryael"));
	}
}
