package com.osrstelemetry.plugin.navigation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPatchDef;
import com.osrstelemetry.plugin.farming.FarmingSites;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Build tool (not shipped): writes {@code navigation/routes.json} (canonical §28.10). For every Farming stop, every bird house stop and every world
 * network entrance, three unbounded reverse searches (any transition; none skill-gated; none gated) give each catalogue
 * arrival's walk there. Run after changing the collision map, the transitions, the travel catalogue, the network entrances or
 * the patch markers: {@code java ... RouteTableGenerator src/main/resources/com/osrstelemetry/plugin/navigation/routes.json}.
 */
public final class RouteTableGenerator
{
	static final NavAccess NO_SKILL = r -> r.getUnresolved().isEmpty() && r.getSkills().isEmpty();
	static final NavAccess[] VARIANTS = {NavAccess.ANY, NO_SKILL, NavAccess.UNGATED};

	public static void main(String[] args) throws Exception
	{
		long t0 = System.currentTimeMillis();
		Gson gson = new Gson();
		Pathfinder pf = Pathfinder.bundled();
		WorldCollision map = pf.map();
		List<TransportDef> base = TransportCatalog.loadBase(gson);
		List<TransportCatalog.NetworkNode> nodes = TransportCatalog.nodes(gson, base);

		RouteTable.Doc doc = new RouteTable.Doc();
		doc.schema = RouteTable.SCHEMA;
		doc.transitions = RouteTable.fingerprint(pf.transitions());
		doc.generatedFrom = "WorldCollision + TransitionCatalog (Shortest Path @ 142e73e2, BSD-2) + spyglass_transitions.tsv; travel catalogue; network-nodes.json; FarmingSites markers; bird house spaces";
		doc.starts = new TreeMap<>();
		doc.goals = new TreeMap<>();
		doc.routes = new TreeMap<>();
		doc.exhaustive = new TreeMap<>();

		Map<String, Integer> starts = new LinkedHashMap<>();
		for (TransportDef t : base)
		{
			if (t.arrival == null)
			{
				continue;
			}
			int[] s = Pathfinder.approach(map, NavPoint.pack(t.arrival.x, t.arrival.y, t.arrival.plane), 5);
			if (s.length == 0)
			{
				System.out.println("UNSTANDABLE arrival " + t.id);
				continue;
			}
			starts.put("arr:" + t.id, s[0]);
		}
		starts.forEach((k, p) -> doc.starts.put(k, new int[]{NavPoint.x(p), NavPoint.y(p), NavPoint.plane(p)}));

		Map<String, int[]> dests = new LinkedHashMap<>();
		FarmingPatchCatalog patches = FarmingPatchCatalog.standard();
		for (FarmingPatchDef p : patches.allPatches())
		{
			String stop = FarmingSites.stopOf(patches, p.getPatchId());
			FarmingSites.Tile tile = FarmingSites.tileOf(patches, p.getPatchId());
			if (tile == null || dests.containsKey("stop:" + stop))
			{
				continue;
			}
			dests.put("stop:" + stop, Pathfinder.approach(map, NavPoint.pack(tile.x, tile.y, tile.plane), 4));
		}
		// Bird house stops (Hunter, Fossil Island): one destination per stop, aimed at its first space.
		for (com.osrstelemetry.plugin.hunter.BirdhouseSpace b : com.osrstelemetry.plugin.hunter.BirdhouseSpace.values())
		{
			if (!dests.containsKey(b.getStopKey()))
			{
				com.osrstelemetry.plugin.hunter.BirdhouseSpace a = com.osrstelemetry.plugin.hunter.BirdhouseSpace.anchorOf(b.getStop());
				dests.put(b.getStopKey(), Pathfinder.approach(map, NavPoint.pack(a.getX(), a.getY(), a.getPlane()), 4));
			}
		}
		for (TransportCatalog.NetworkNode n : nodes)
		{
			dests.put("node:" + n.id, Pathfinder.approach(map, NavPoint.pack(n.x, n.y, n.plane), 4));
		}
		for (Map.Entry<String, int[]> d : dests.entrySet())
		{
			long s0 = System.currentTimeMillis();
			if (d.getValue().length == 0)
			{
				System.out.println("NO STANDABLE GOAL " + d.getKey());
			}
			addDestination(doc, pf, d.getKey(), d.getValue(), starts);
			System.out.println(d.getKey() + " starts=" + doc.routes.getOrDefault(d.getKey(), new TreeMap<>()).size() + " exhaustive="
				+ doc.exhaustive.get(d.getKey()) + " ms=" + (System.currentTimeMillis() - s0));
		}
		File out = new File(args[0]);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))
		{
			new GsonBuilder().disableHtmlEscaping().create().toJson(doc, w);
		}
		System.out.println("WROTE " + out + " " + out.length() + " bytes in " + (System.currentTimeMillis() - t0) / 1000 + " s");
	}

	/** One destination's rows: three unbounded reverse searches (any; no skill-gated; none gated); shared with the tests. */
	static void addDestination(RouteTable.Doc doc, Pathfinder pf, String key, int[] goals, Map<String, Integer> starts)
	{
		int[] g = new int[goals.length * 3];
		for (int i = 0; i < goals.length; i++)
		{
			g[3 * i] = NavPoint.x(goals[i]);
			g[3 * i + 1] = NavPoint.y(goals[i]);
			g[3 * i + 2] = NavPoint.plane(goals[i]);
		}
		doc.goals.put(key, g);
		if (goals.length == 0)
		{
			return;
		}
		int[] startTiles = starts.values().stream().mapToInt(Integer::intValue).toArray();
		Map<Transition, Integer> index = new java.util.IdentityHashMap<>();
		for (int i = 0; i < pf.transitions().all().size(); i++)
		{
			index.put(pf.transitions().all().get(i), i);
		}
		Map<String, List<int[]>> rows = new TreeMap<>();
		boolean exhaustive = true;
		for (NavAccess access : VARIANTS)
		{
			Pathfinder.Search search = pf.fromAll(goals, startTiles, access, Integer.MAX_VALUE, 40_000_000);
			exhaustive &= search.isExhaustive();
			for (Map.Entry<String, Integer> s : starts.entrySet())
			{
				NavPath path = search.path(s.getValue());
				if (path == null)
				{
					continue;
				}
				List<Transition> ts = path.transitions();
				int[] row = new int[2 + ts.size()];
				row[0] = path.cost();
				row[1] = path.walkTiles();
				for (int i = 0; i < ts.size(); i++)
				{
					row[2 + i] = index.get(ts.get(i));
				}
				List<int[]> l = rows.computeIfAbsent(s.getKey(), k -> new ArrayList<>());
				boolean dup = false;
				for (int[] r : l)
				{
					dup |= java.util.Arrays.equals(r, row);
				}
				if (!dup)
				{
					l.add(row);
				}
			}
		}
		Map<String, int[][]> m = new TreeMap<>();
		rows.forEach((k, v) -> m.put(k, v.toArray(new int[0][])));
		doc.routes.put(key, m);
		doc.exhaustive.put(key, exhaustive);
	}
}
