package com.osrstelemetry.plugin.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingAction;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * Shared target resolution (canonical §28.14): a patch is one quiet indicator (its objects' ground footprints in its own
 * region, never every plant model, never the same varbit's patch elsewhere); a transport person whose id the route tables put
 * in the object slot (the Mountain Guide, 7600) is found as the NPC it is; a gardener nearest the patch is the one outlined.
 */
public class GuidanceTargetsTest
{
	private static final int BASE_X = 3000;
	private static final int BASE_Y = 3264;
	private static final int VARBIT = 4771;
	private static final int PATCH_OBJECT = 8550;

	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, m, args) -> {
			if (m.getName().equals("equals"))
			{
				return self == args[0];
			}
			if (m.getName().equals("hashCode"))
			{
				return System.identityHashCode(self);
			}
			Function<Object[], Object> f = answers.get(m.getName());
			if (f != null)
			{
				return f.apply(args);
			}
			Class<?> r = m.getReturnType();
			return r == int.class ? 0 : r == boolean.class ? (Object) false : r == long.class ? 0L : null;
		});
	}

	private static Map<String, Function<Object[], Object>> map(Object... kv)
	{
		Map<String, Function<Object[], Object>> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			Object v = kv[i + 1];
			m.put((String) kv[i], v instanceof Function ? castF(v) : a -> v);
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Function<Object[], Object> castF(Object v)
	{
		return (Function<Object[], Object>) v;
	}

	/** A game object at scene (x1, y1)–(x2, y2). */
	private static GameObject object(int id, int x1, int y1, int x2, int y2)
	{
		WorldPoint w = new WorldPoint(BASE_X + x1, BASE_Y + y1, 0);
		return fake(GameObject.class, map("getId", id, "getSceneMinLocation", new Point(x1, y1), "getSceneMaxLocation", new Point(x2, y2),
			"getWorldLocation", w, "getPlane", 0));
	}

	private static NPC npc(int id, String name, String[] actions, int x, int y)
	{
		NPCComposition c = fake(NPCComposition.class, map("getName", name, "getActions", actions));
		return fake(NPC.class, map("getId", id, "getTransformedComposition", c, "getWorldLocation", new WorldPoint(x, y, 0)));
	}

	private static Client client(Map<String, GameObject> byTile, Map<Integer, ObjectComposition> defs, List<NPC> npcs)
	{
		Tile[][][] tiles = new Tile[4][104][104];
		for (Map.Entry<String, GameObject> e : byTile.entrySet())
		{
			String[] xy = e.getKey().split(",");
			GameObject[] here = {e.getValue()};
			tiles[0][Integer.parseInt(xy[0])][Integer.parseInt(xy[1])] = fake(Tile.class, map("getGameObjects", here));
		}
		for (int x = 0; x < 104; x++)
		{
			for (int y = 0; y < 104; y++)
			{
				if (tiles[0][x][y] == null)
				{
					tiles[0][x][y] = fake(Tile.class, map("getGameObjects", new GameObject[0]));
				}
			}
		}
		Scene scene = fake(Scene.class, map("getTiles", tiles));
		IndexedObjectSet<NPC> set = fake(IndexedObjectSet.class, map("iterator", (Function<Object[], Object>) a -> npcs.iterator()));
		WorldView view = fake(WorldView.class, map("getScene", scene, "getPlane", 0, "getBaseX", BASE_X, "getBaseY", BASE_Y, "npcs", set));
		return fake(Client.class, map("getTopLevelWorldView", view, "getObjectDefinition", (Function<Object[], Object>) a -> defs.get((Integer) a[0])));
	}

	private static ObjectComposition def(int varbit, String name, String... actions)
	{
		return fake(ObjectComposition.class, map("getVarbitId", varbit, "getName", name, "getActions", actions));
	}

	@Test
	public void aPatch_isItsObjectsFootprints_inItsOwnRegion_neverEachPlantModel()
	{
		Map<String, GameObject> scene = new HashMap<>();
		scene.put("50,50", object(PATCH_OBJECT, 50, 50, 54, 51)); // the L-shaped allotment: two objects
		scene.put("50,52", object(PATCH_OBJECT, 50, 52, 51, 57));
		scene.put("60,60", object(1, 60, 60, 60, 60)); // something else
		Map<Integer, ObjectComposition> defs = new HashMap<>();
		defs.put(PATCH_OBJECT, def(VARBIT, "Allotment", "Rake", "Inspect"));
		defs.put(1, def(0, "Tree", "Chop down"));
		Client c = client(scene, defs, Collections.emptyList());
		int region = new WorldPoint(BASE_X + 50, BASE_Y + 50, 0).getRegionID();
		GuidanceTargets t = new GuidanceTargets();
		assertTrue(t.update(c, FarmingAction.Target.patches(Collections.singleton(VARBIT), Collections.singleton(region)),
			new WorldPoint(BASE_X + 48, BASE_Y + 50, 0), 1));
		assertEquals("two footprints, one quiet indicator", 2, t.footprints().size());
		assertTrue("no object outlines for a patch", t.objects().isEmpty());
		assertTrue(Arrays.equals(new int[]{BASE_X + 50, BASE_Y + 50, BASE_X + 54, BASE_Y + 51, 0}, t.footprints().get(0)));
		t.update(c, FarmingAction.Target.patches(Collections.singleton(VARBIT), Collections.singleton(region + 1)), new WorldPoint(BASE_X + 48, BASE_Y + 50, 0),
			10);
		assertTrue("the same varbit in another region is another patch", t.footprints().isEmpty());
	}

	@Test
	public void aTransportPerson_namedByIdInTheObjectSlot_isFoundAsTheNpc()
	{
		NPC guide = npc(7600, "Mountain Guide", new String[]{"Talk-to", "Travel"}, BASE_X + 30, BASE_Y + 30);
		NPC other = npc(1234, "Man", new String[]{"Talk-to"}, BASE_X + 31, BASE_Y + 30);
		Client c = client(Collections.emptyMap(), Collections.emptyMap(), Arrays.asList(other, guide));
		GuidanceTargets t = new GuidanceTargets();
		t.update(c, FarmingAction.Target.object(new HashSet<>(Collections.singletonList(7600)), "Mountain Guide", "Travel", BASE_X + 30, BASE_Y + 31, 0, 3),
			new WorldPoint(BASE_X + 20, BASE_Y + 20, 0), 1);
		assertEquals(Collections.singletonList(guide), t.npcs());
	}

	@Test
	public void theGardenerOfThisPatch_isTheOneOutlined()
	{
		String[] pay = {"Talk-to", "Pay"};
		NPC near = npc(1, "Prissy Scilla", pay, BASE_X + 40, BASE_Y + 40);
		NPC far = npc(2, "Prissy Scilla", pay, BASE_X + 47, BASE_Y + 40);
		Client c = client(Collections.emptyMap(), Collections.emptyMap(), Arrays.asList(far, near));
		GuidanceTargets t = new GuidanceTargets();
		t.update(c, FarmingAction.Target.npc("Prissy Scilla", "Pay", BASE_X + 41, BASE_Y + 40, 0, 10), new WorldPoint(BASE_X + 46, BASE_Y + 40, 0), 1);
		assertEquals(Collections.singletonList(near), t.npcs());
	}

	@Test
	public void aCompostBin_isFoundAsItsOwnPlacedObject_atItsTile()
	{
		// The scene object carries the placed multiloc's id (7836 at Falador) whatever the bin shows (§28.16).
		Map<String, GameObject> scene = new HashMap<>();
		GameObject bin = object(7836, 56, 48, 56, 48);
		scene.put("56,48", bin);
		scene.put("50,50", object(PATCH_OBJECT, 50, 50, 54, 51));
		Map<Integer, ObjectComposition> defs = new HashMap<>();
		defs.put(7836, def(4775, "Compost Bin"));
		defs.put(PATCH_OBJECT, def(VARBIT, "Allotment", "Rake"));
		Client c = client(scene, defs, Collections.emptyList());
		GuidanceTargets t = new GuidanceTargets();
		t.update(c, FarmingAction.Target.object(Collections.singleton(7836), "Compost Bin|Big Compost Bin", null, BASE_X + 56, BASE_Y + 48, 0, 2),
			new WorldPoint(BASE_X + 50, BASE_Y + 48, 0), 1);
		assertEquals(Collections.singletonList(bin), t.objects());
		assertTrue("the bin, not the patches", t.footprints().isEmpty());
	}
}
