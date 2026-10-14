package com.osrstelemetry.plugin.skills.woodcutting;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Point;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.eventbus.Subscribe;

/**
 * The Woodcutting companion's native wiring: the SAME shared
 * {@link SkillCompanionCollector} Mining, Fishing and Agility use (session
 * binding, bounded telemetry -- now with the auto-burned part of production
 * and Forestry occurrences --, live view, bank view) with Woodcutting's
 * tracker, plus Woodcutting's own evidence, all event-driven:
 * <ul>
 * <li>"Chop down" / "Chop" / "Cut" on a tree object: a tree HINT and the
 * CURRENT TREE (object id + scene tile); that object despawning = it
 * fell;</li>
 * <li>Firemaking XP (via the shared other-skill hook) and whether a charged
 * infernal axe is carried (inventory or worn), read once per tick;</li>
 * <li>Forestry event NPCs / objects spawning and despawning (native ids);</li>
 * <li>"Fill" / "Empty" on a log basket, a click on the Woodcutting
 * Leprechaun (his free banking), "Drop" / "Take".</li>
 * </ul>
 * Independent of RuneLite's own Woodcutting plugin (its patterns and event
 * ids were studied, never used at runtime).
 */
@Singleton
public class WoodcuttingCompanionCollector extends SkillCompanionCollector
{
	@Inject
	public WoodcuttingCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
		// Infernal-axe Firemaking XP and Forestry events' other-skill XP belong to the Woodcutting session.
		setContextEvidenceSink(coordinator::noteSkillContextEvidence);
	}

	WoodcuttingCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, WoodcuttingCompanionDefinition.INSTANCE, new WoodcuttingCompanionTracker());
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	@Override
	protected void setContextEvidenceSink(ContextEvidenceSink sink)
	{
		super.setContextEvidenceSink(sink);
	}

	WoodcuttingCompanionTracker woodcutting()
	{
		return (WoodcuttingCompanionTracker) tracker;
	}

	/** Scene tile key of an object (scene x / y + plane). */
	static long tile(int sceneX, int sceneY, int plane)
	{
		return ((long) plane << 24) | ((long) (sceneX & 0xFFF) << 12) | (sceneY & 0xFFF);
	}

	@Override
	protected void onMenuOption(String option, String target, MenuOptionClicked event)
	{
		String o = option.toLowerCase(Locale.ROOT);
		String t = target == null ? "" : target.toLowerCase(Locale.ROOT);
		int tick = client.getTickCount();
		if (t.contains("basket") && (o.equals("fill") || o.equals("empty")))
		{
			if (o.equals("fill"))
			{
				woodcutting().onBasketFill(tick);
			}
			else
			{
				woodcutting().onBasketEmpty(tick);
			}
			return;
		}
		if (o.equals("drop"))
		{
			woodcutting().onDropClick(tick);
			return;
		}
		if (o.equals("take"))
		{
			woodcutting().onTakeClick(tick);
			return;
		}
		MenuEntry entry = event == null ? null : event.getMenuEntry();
		NPC npc = entry == null ? null : entry.getNpc();
		if (npc != null)
		{
			if (npc.getId() == NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN)
			{
				woodcutting().onLeprechaunClick(tick);
			}
			return;
		}
		MenuAction action = event == null ? null : event.getMenuAction();
		boolean objectAction = action == MenuAction.GAME_OBJECT_FIRST_OPTION || action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		if (objectAction && isChopOption(o))
		{
			woodcutting().onTreeClick(clock.getAsLong(), target, event.getId(), tile(event.getParam0(), event.getParam1(), plane()));
		}
	}

	static boolean isChopOption(String option)
	{
		return option.equals("chop down") || option.equals("chop") || option.equals("cut") || option.equals("chop-down");
	}

	private int plane()
	{
		try
		{
			return client.getTopLevelWorldView().getPlane();
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	@Override
	protected void onOtherContainer(int containerId, ItemContainer container)
	{
		// Read each tick (onWoodcuttingTick); nothing to keep here.
	}

	@Subscribe
	public void onWoodcuttingTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		woodcutting().setInfernalCarried(holdsBurningAxe(client.getItemContainer(InventoryID.INV))
			|| holdsBurningAxe(client.getItemContainer(InventoryID.WORN)));
	}

	static boolean holdsBurningAxe(ItemContainer container)
	{
		if (container == null)
		{
			return false;
		}
		for (Item item : container.getItems())
		{
			if (item != null && WoodcuttingCompanionDefinition.BURNING_AXES.contains(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	@Subscribe
	public void onWoodcuttingGameState(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			woodcutting().onSceneLoading(clock.getAsLong());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (npc != null && ForestryEvent.forNpc(npc.getId()) != null)
		{
			woodcutting().onNpcSpawned(npc.getId(), clock.getAsLong());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if (npc != null && ForestryEvent.forNpc(npc.getId()) != null)
		{
			woodcutting().onNpcDespawned(npc.getId(), clock.getAsLong());
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		NPCComposition old = event.getOld();
		long now = clock.getAsLong();
		if (old != null && ForestryEvent.forNpc(old.getId()) != null)
		{
			woodcutting().onNpcDespawned(old.getId(), now);
		}
		if (npc != null && ForestryEvent.forNpc(npc.getId()) != null)
		{
			woodcutting().onNpcSpawned(npc.getId(), now);
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject o = event.getGameObject();
		if (o != null && ForestryEvent.forObject(o.getId()) != null)
		{
			woodcutting().onObjectSpawned(o.getId(), clock.getAsLong());
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		GameObject o = event.getGameObject();
		if (o == null)
		{
			return;
		}
		Point min = o.getSceneMinLocation();
		long key = min == null ? Long.MIN_VALUE : tile(min.getX(), min.getY(), o.getPlane());
		woodcutting().onObjectDespawned(o.getId(), key, clock.getAsLong());
	}
}
