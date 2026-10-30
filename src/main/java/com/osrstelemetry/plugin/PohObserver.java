package com.osrstelemetry.plugin;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.poh.PohKnowledge;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Observes the player's house (canonical §13, phase G). While inside a house
 * (an instanced scene with the house exit portal) it reads the facilities
 * from the scene's objects; when the visit ends it records them, as the
 * player's own house only if building mode was on at some point during the
 * visit (impossible in a friend's house). Read only: nothing is clicked or
 * changed. Saved per RuneScape profile.
 */
@Slf4j
@Singleton
public class PohObserver
{
	static final String GROUP = "osrstelemetry";
	static final String KEY = "pohKnowledge";
	private static final int SCAN_EVERY_TICKS = 5;

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;
	private volatile PohKnowledge knowledge = PohKnowledge.empty();
	private boolean inHouse;
	private boolean buildingModeSeen;
	private final Set<String> seen = new LinkedHashSet<>();
	private int ticks;

	@Inject
	public PohObserver(Client client, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
	}

	/** A scene loaded since the last look: the first tick after it checks for the house at once (guidance words the next step from it). */
	private boolean sceneChecked;

	/** Whether the player is inside their house now (client thread). */
	public boolean isInHouse()
	{
		return inHouse;
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOADING || e.getGameState() == GameState.HOPPING || e.getGameState() == GameState.LOGIN_SCREEN)
		{
			sceneChecked = false;
		}
	}

	/** The current profile's house knowledge (any thread). */
	public PohKnowledge knowledge()
	{
		return knowledge;
	}

	public void load()
	{
		PohKnowledge k = null;
		try
		{
			String json = configManager.getRSProfileConfiguration(GROUP, KEY);
			k = json == null || json.isEmpty() ? null : gson.fromJson(json, PohKnowledge.class);
		}
		catch (RuntimeException e)
		{
			log.debug("house knowledge not readable", e);
		}
		PohKnowledge loaded = k == null ? PohKnowledge.empty() : k.loaded();
		if (k != null && !loaded.getFacilities().equals(k.copy().getFacilities()) || k != null && !loaded.getUnconfirmed().equals(k.copy().getUnconfirmed()))
		{
			save(loaded); // the outdated nexus slots are dropped once, then read again from the Teleport Menu
		}
		knowledge = loaded;
		inHouse = false;
		seen.clear();
		buildingModeSeen = false;
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (++ticks % SCAN_EVERY_TICKS != 0 && !inHouse && sceneChecked)
		{
			return;
		}
		sceneChecked = true;
		boolean house = client.isInInstancedRegion() && sceneHas(ObjectID.POH_EXIT_PORTAL);
		if (house)
		{
			inHouse = true;
			buildingModeSeen |= client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1;
			readNexusMenus();
			if (ticks % SCAN_EVERY_TICKS == 0)
			{
				scan();
			}
		}
		else if (inHouse)
		{
			finishVisit();
		}
	}

	private void finishVisit()
	{
		PohKnowledge next = knowledge.withVisit(seen, buildingModeSeen, System.currentTimeMillis());
		knowledge = next;
		save(next);
		inHouse = false;
		seen.clear();
		buildingModeSeen = false;
	}

	private void save(PohKnowledge k)
	{
		try
		{
			configManager.setRSProfileConfiguration(GROUP, KEY, gson.toJson(k));
		}
		catch (RuntimeException e)
		{
			log.debug("house knowledge not saved", e);
		}
	}

	private boolean sceneHas(int objectId)
	{
		Scene scene = client.getScene();
		if (scene == null)
		{
			return false;
		}
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] col : plane)
			{
				for (Tile t : col)
				{
					if (t != null && holds(t, objectId))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	private void scan()
	{
		Scene scene = client.getScene();
		if (scene == null)
		{
			return;
		}
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] col : plane)
			{
				for (Tile t : col)
				{
					if (t == null)
					{
						continue;
					}
					for (GameObject g : t.getGameObjects())
					{
						add(g);
					}
					add(t.getWallObject());
					add(t.getDecorativeObject());
					add(t.getGroundObject());
				}
			}
		}
	}

	private void add(TileObject o)
	{
		if (o != null)
		{
			seen.addAll(facilitiesOf(o.getId()));
		}
	}

	/** Object id → facilities: its current form first (a multi-form portal), then the id table, else the name (newer frames). */
	private final java.util.Map<Integer, Set<String>> byName = new java.util.HashMap<>();

	private Set<String> facilitiesOf(int id)
	{
		net.runelite.api.ObjectComposition c = client.getObjectDefinition(id);
		if (c != null && c.getImpostorIds() != null)
		{
			// A multi-form object (a portal whose left-click destination follows a diary choice): what it is now, never cached.
			net.runelite.api.ObjectComposition now = c.getImpostor();
			Set<String> f = now == null ? java.util.Collections.emptySet() : PohKnowledge.facilitiesOf(now.getId());
			if (f.isEmpty() && now != null)
			{
				f = PohKnowledge.facilitiesOfName(now.getName());
			}
			if (!f.isEmpty())
			{
				return f;
			}
		}
		Set<String> known = PohKnowledge.facilitiesOf(id);
		if (!known.isEmpty())
		{
			return known;
		}
		Set<String> cached = byName.get(id);
		if (cached != null)
		{
			return cached;
		}
		Set<String> f = c == null || c.getImpostorIds() != null ? java.util.Collections.<String>emptySet() : PohKnowledge.facilitiesOfName(c.getName());
		if (c != null && c.getImpostorIds() == null)
		{
			byName.put(id, f);
		}
		return f;
	}

	/**
	 * The portal nexus's slots, read from its Teleport Menu while it is open during the visit (canonical §28.14): the menu
	 * lists the slotted teleports with their keys ("1: Draynor Manor"); Shortest Path reads the same menu for its nexus key
	 * binds. Only rows shown are read (a hidden widget and everything under it is skipped). The configuration screen is never
	 * read: its Slots list holds pending changes until they are confirmed and paid for. Recorded with the rest of the visit,
	 * so only the player's own house counts.
	 */
	private void readNexusMenus()
	{
		net.runelite.api.widgets.Widget menu = client.getWidget(net.runelite.api.gameval.InterfaceID.TelenexusTeleport.UNIVERSE);
		if (menu != null && !menu.isHidden() && collectNexus(menu))
		{
			seen.add(PohKnowledge.NEXUS_READ);
		}
	}

	private boolean collectNexus(net.runelite.api.widgets.Widget w)
	{
		boolean any = false;
		java.util.ArrayDeque<net.runelite.api.widgets.Widget> todo = new java.util.ArrayDeque<>();
		todo.add(w);
		int visited = 0;
		while (!todo.isEmpty() && visited++ < 2000)
		{
			net.runelite.api.widgets.Widget x = todo.poll();
			if (x.isHidden())
			{
				continue; // a row not shown (and its children) is not a slot
			}
			String text = x.getText();
			for (String row : text == null ? new String[0] : text.split("(?i)<br>"))
			{
				String dest = PohKnowledge.nexusDestination(row);
				if (dest != null)
				{
					seen.add("poh.nexus." + dest);
					any = true;
				}
			}
			for (net.runelite.api.widgets.Widget[] kids : new net.runelite.api.widgets.Widget[][]{x.getStaticChildren(), x.getDynamicChildren(),
				x.getNestedChildren()})
			{
				if (kids != null)
				{
					for (net.runelite.api.widgets.Widget k : kids)
					{
						if (k != null)
						{
							todo.add(k);
						}
					}
				}
			}
		}
		return any;
	}

	private static boolean holds(Tile t, int objectId)
	{
		for (GameObject g : t.getGameObjects())
		{
			if (g != null && g.getId() == objectId)
			{
				return true;
			}
		}
		TileObject[] others = {t.getWallObject(), t.getDecorativeObject(), t.getGroundObject()};
		for (TileObject o : others)
		{
			if (o != null && o.getId() == objectId)
			{
				return true;
			}
		}
		return false;
	}
}
