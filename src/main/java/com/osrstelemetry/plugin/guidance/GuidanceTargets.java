package com.osrstelemetry.plugin.guidance;

import com.osrstelemetry.plugin.farming.FarmingAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Finds what the current action is about in the loaded scene (canonical §28.13): the patch objects by their varbit, an
 * object by its id or its name near the route's tile (anywhere inside the house), an NPC by its name or by the menu action
 * it offers, nearest the patch it serves (the gardener of that patch, not every gardener in sight). Client thread only; a
 * scan runs when the target changes and every few ticks, bounded to the target's area, never per frame. The result is a
 * list of scene references the overlay draws; nothing is worked out while drawing.
 */
public final class GuidanceTargets
{
	/** Re-scan at most this often, in ticks, while the target stays the same (objects spawn and despawn as the player walks). */
	static final int RESCAN_TICKS = 3;
	/** A patch is looked for within this many tiles of the player (the stop's patches are close by). */
	static final int PATCH_RADIUS = 20;

	private FarmingAction.Target target = FarmingAction.Target.NONE;
	private int scannedAt = Integer.MIN_VALUE;
	private List<TileObject> objects = Collections.emptyList();
	private List<NPC> npcs = Collections.emptyList();
	/** The target patch's ground footprints (world x1, y1, x2, y2, plane), drawn as one quiet outline, never per plant model. */
	private List<int[]> footprints = Collections.emptyList();

	public List<int[]> footprints()
	{
		return footprints;
	}

	public List<TileObject> objects()
	{
		return objects;
	}

	public List<NPC> npcs()
	{
		return npcs;
	}

	public void clear()
	{
		target = FarmingAction.Target.NONE;
		scannedAt = Integer.MIN_VALUE;
		objects = Collections.emptyList();
		npcs = Collections.emptyList();
		footprints = Collections.emptyList();
	}

	/** Forget one despawned reference (and look again on the next update). */
	public boolean drop(Object gone)
	{
		if (objects.contains(gone) || npcs.contains(gone))
		{
			List<TileObject> o = new ArrayList<>(objects);
			o.remove(gone);
			List<NPC> n = new ArrayList<>(npcs);
			n.remove(gone);
			objects = Collections.unmodifiableList(o);
			npcs = Collections.unmodifiableList(n);
			scannedAt = Integer.MIN_VALUE;
			return true;
		}
		return false;
	}

	/** Brings the references up to date for this target; true when they changed. */
	public boolean update(Client client, FarmingAction.Target t, WorldPoint player, int tick)
	{
		FarmingAction.Target want = t == null ? FarmingAction.Target.NONE : t;
		boolean same = want.equals(target);
		if (same && tick - scannedAt < RESCAN_TICKS)
		{
			return false;
		}
		target = want;
		scannedAt = tick;
		List<TileObject> o = Collections.emptyList();
		List<NPC> n = Collections.emptyList();
		List<int[]> f = Collections.emptyList();
		WorldView view = client.getTopLevelWorldView();
		if (view != null && player != null && want.kind != FarmingAction.Target.Kind.NONE)
		{
			if (want.kind == FarmingAction.Target.Kind.NPC)
			{
				n = npcs(view, want, player);
			}
			else if (want.kind == FarmingAction.Target.Kind.PATCH)
			{
				f = footprints(view, objects(client, view, want, player));
			}
			else
			{
				o = objects(client, view, want, player);
				if (o.isEmpty() && !want.ids.isEmpty())
				{
					// Shortest Path's tables put an NPC's id where an object's would be ("Travel Mountain Guide 7600"):
					// a transport person is looked for by that id (or name) near the same tile.
					n = npcs(view, want, player);
				}
			}
		}
		boolean changed = !o.equals(objects) || !n.equals(npcs) || !sameFootprints(f, footprints);
		objects = Collections.unmodifiableList(o);
		npcs = Collections.unmodifiableList(n);
		footprints = Collections.unmodifiableList(f);
		return changed;
	}

	private static List<TileObject> objects(Client client, WorldView view, FarmingAction.Target t, WorldPoint player)
	{
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		int plane = view.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return Collections.emptyList();
		}
		int baseX = view.getBaseX();
		int baseY = view.getBaseY();
		// The area to look in: around the player (patches), around the route's tile, or the whole scene (inside the house).
		int cx;
		int cy;
		int r;
		if (t.kind == FarmingAction.Target.Kind.PATCH)
		{
			cx = player.getX() - baseX;
			cy = player.getY() - baseY;
			r = PATCH_RADIUS;
		}
		else if (t.x >= 0)
		{
			if (t.plane != plane)
			{
				return Collections.emptyList();
			}
			cx = t.x - baseX;
			cy = t.y - baseY;
			r = Math.max(2, t.radius);
		}
		else
		{
			cx = 52;
			cy = 52;
			r = 60;
		}
		List<TileObject> out = new ArrayList<>();
		// One verdict per object id per scan (the definition, its impostor and its name are looked at once).
		java.util.Map<Integer, Boolean> verdicts = new java.util.HashMap<>();
		Tile[][] layer = tiles[plane];
		for (int x = Math.max(0, cx - r); x <= Math.min(layer.length - 1, cx + r); x++)
		{
			for (int y = Math.max(0, cy - r); y <= Math.min(layer[x].length - 1, cy + r); y++)
			{
				Tile tile = layer[x][y];
				if (tile == null)
				{
					continue;
				}
				if (t.kind == FarmingAction.Target.Kind.PATCH)
				{
					for (GameObject g : tile.getGameObjects())
					{
						if (g != null && !out.contains(g) && patch(client, g, t))
						{
							out.add(g);
						}
					}
					continue;
				}
				for (GameObject g : tile.getGameObjects())
				{
					consider(client, g, t, out, verdicts);
				}
				consider(client, tile.getWallObject(), t, out, verdicts);
				consider(client, tile.getDecorativeObject(), t, out, verdicts);
				consider(client, tile.getGroundObject(), t, out, verdicts);
			}
		}
		// A located target is one object (the nearest such door or ladder); inside the house every match counts (a portal
		// and the nexus can both lead there).
		if (t.x >= 0 && out.size() > 1)
		{
			TileObject best = null;
			int bestD = Integer.MAX_VALUE;
			for (TileObject o : out)
			{
				WorldPoint w = o.getWorldLocation();
				int d = Math.max(Math.abs(w.getX() - t.x), Math.abs(w.getY() - t.y));
				if (d < bestD)
				{
					bestD = d;
					best = o;
				}
			}
			return Collections.singletonList(best);
		}
		return out;
	}

	private static boolean patch(Client client, GameObject g, FarmingAction.Target t)
	{
		ObjectComposition c = client.getObjectDefinition(g.getId());
		if (c == null || c.getVarbitId() <= 0 || !t.ids.contains(c.getVarbitId()))
		{
			return false;
		}
		if (t.regions.isEmpty())
		{
			return true;
		}
		WorldPoint w = g.getWorldLocation();
		return w != null && t.regions.contains(w.getRegionID()); // the same varbit is another patch in another region
	}

	/** Each patch object's footprint on the ground (world tiles), merged when one object repeats on several tiles. */
	static List<int[]> footprints(WorldView view, List<TileObject> objects)
	{
		List<int[]> out = new ArrayList<>();
		for (TileObject o : objects)
		{
			if (!(o instanceof GameObject))
			{
				continue;
			}
			GameObject g = (GameObject) o;
			if (g.getSceneMinLocation() == null || g.getSceneMaxLocation() == null)
			{
				continue;
			}
			int[] fp = {g.getSceneMinLocation().getX() + view.getBaseX(), g.getSceneMinLocation().getY() + view.getBaseY(),
				g.getSceneMaxLocation().getX() + view.getBaseX(), g.getSceneMaxLocation().getY() + view.getBaseY(), g.getPlane()};
			boolean dup = false;
			for (int[] e : out)
			{
				dup |= java.util.Arrays.equals(e, fp);
			}
			if (!dup)
			{
				out.add(fp);
			}
		}
		return out;
	}

	private static boolean sameFootprints(List<int[]> a, List<int[]> b)
	{
		if (a.size() != b.size())
		{
			return false;
		}
		for (int i = 0; i < a.size(); i++)
		{
			if (!java.util.Arrays.equals(a.get(i), b.get(i)))
			{
				return false;
			}
		}
		return true;
	}

	private static void consider(Client client, TileObject o, FarmingAction.Target t, List<TileObject> out, java.util.Map<Integer, Boolean> verdicts)
	{
		if (o == null || out.contains(o))
		{
			return;
		}
		if (t.ids.contains(o.getId()))
		{
			out.add(o);
			return;
		}
		if (t.name == null)
		{
			return;
		}
		Boolean v = verdicts.get(o.getId());
		if (v == null)
		{
			ObjectComposition c = client.getObjectDefinition(o.getId());
			if (c != null && c.getImpostorIds() != null)
			{
				ObjectComposition i = c.getImpostor();
				c = i == null ? c : i;
			}
			v = c != null && named(c.getName(), t.name) && offers(c.getActions(), t.action);
			verdicts.put(o.getId(), v);
		}
		if (v)
		{
			out.add(o);
		}
	}

	private static List<NPC> npcs(WorldView view, FarmingAction.Target t, WorldPoint player)
	{
		NPC best = null;
		int bestD = Integer.MAX_VALUE;
		int ax = t.x >= 0 ? t.x : player.getX();
		int ay = t.y >= 0 ? t.y : player.getY();
		for (NPC npc : view.npcs())
		{
			if (npc == null)
			{
				continue;
			}
			NPCComposition c = npc.getTransformedComposition();
			boolean byId = !t.ids.isEmpty() && t.ids.contains(npc.getId());
			if (c == null || !byId && (t.name == null && t.ids.isEmpty() ? false : t.name != null ? !named(c.getName(), t.name) : true)
				|| !offers(c.getActions(), t.action))
			{
				continue;
			}
			WorldPoint w = npc.getWorldLocation();
			if (w == null || t.x >= 0 && w.getPlane() != t.plane)
			{
				continue;
			}
			int d = Math.max(Math.abs(w.getX() - ax), Math.abs(w.getY() - ay));
			if (d <= Math.max(2, t.radius) && d < bestD)
			{
				bestD = d;
				best = npc;
			}
		}
		return best == null ? Collections.emptyList() : Collections.singletonList(best);
	}

	/** The game's name matches one of the "|" alternatives: exactly, or as its last words ("Ornate jewellery box"). */
	static boolean named(String name, String want)
	{
		if (name == null || want == null)
		{
			return false;
		}
		String n = name.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
		for (String w : want.split("\\|"))
		{
			String x = w.trim().toLowerCase(Locale.ROOT);
			if (!x.isEmpty() && (n.equals(x) || n.endsWith(" " + x)))
			{
				return true;
			}
		}
		return false;
	}

	/** The menu offers this action (a prefix: "Pay" matches "Pay (north)"); no action wanted matches anything. */
	static boolean offers(String[] actions, String want)
	{
		if (want == null || want.isEmpty())
		{
			return true;
		}
		if (actions == null)
		{
			return false;
		}
		String w = want.toLowerCase(Locale.ROOT);
		for (String a : actions)
		{
			if (a != null && a.toLowerCase(Locale.ROOT).startsWith(w))
			{
				return true;
			}
		}
		return false;
	}
}
