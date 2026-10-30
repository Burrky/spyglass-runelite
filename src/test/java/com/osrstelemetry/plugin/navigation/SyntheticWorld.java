package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tiny hand-drawn world for tests that must not lean on the bundled data (canonical §28.11): one 64×64 region, drawn as
 * text ('.' walkable, '#' blocked; the first row is the highest y), with hand-made transitions. Independent of the real map
 * and of the generated route data, so the pathfinder, the route variants and the account router are checked against
 * answers worked out by hand.
 */
final class SyntheticWorld
{
	final int x0;
	final int y0;
	private final Map<Integer, byte[]> regions = new HashMap<>();
	private final List<Transition> transitions = new ArrayList<>();

	SyntheticWorld(int regionX, int regionY, String... plane0)
	{
		this.x0 = regionX * 64;
		this.y0 = regionY * 64;
		BitSet bits = new BitSet(64 * 64 * 4);
		boolean[][] walk = new boolean[64][64];
		for (int r = 0; r < plane0.length; r++)
		{
			int y = plane0.length - 1 - r;
			for (int x = 0; x < plane0[r].length(); x++)
			{
				walk[x][y] = plane0[r].charAt(x) == '.';
			}
		}
		for (int x = 0; x < 64; x++)
		{
			for (int y = 0; y < 64; y++)
			{
				int base = (y * 64 + x) * 4;
				if (walk[x][y] && y + 1 < 64 && walk[x][y + 1])
				{
					bits.set(base); // north open
				}
				if (walk[x][y] && x + 1 < 64 && walk[x + 1][y])
				{
					bits.set(base + 1); // east open
				}
			}
		}
		byte[] bytes = bits.toByteArray();
		byte[] full = new byte[64 * 64 * 4 / 8];
		System.arraycopy(bytes, 0, full, 0, bytes.length);
		regions.put(regionX << 16 | regionY, full);
	}

	/** The packed tile at local (x, y). */
	int at(int x, int y)
	{
		return NavPoint.pack(x0 + x, y0 + y, 0);
	}

	SyntheticWorld transition(int fromX, int fromY, int toX, int toY, String option, String target, NavRequirement req)
	{
		transitions.add(new Transition(at(fromX, fromY), at(toX, toY), Transition.Family.PASSAGE, option, target, 1, req, 1, null));
		return this;
	}

	Pathfinder pathfinder()
	{
		return new Pathfinder(WorldCollision.fromRegions(regions), TransitionCatalog.of(transitions));
	}

	static NavRequirement req(String skills, String quests, String items, String varbits)
	{
		return NavRequirement.parse(skills, quests, items, varbits, null, new HashMap<>());
	}

	/** A route table built the way the generator builds the real one (three variants per start), for one destination. */
	static RouteTable table(Pathfinder pf, String dest, int[] goals, Map<String, Integer> starts)
	{
		RouteTable.Doc doc = new RouteTable.Doc();
		doc.schema = RouteTable.SCHEMA;
		doc.transitions = RouteTable.fingerprint(pf.transitions());
		doc.starts = new TreeMap<>();
		doc.goals = new TreeMap<>();
		doc.routes = new TreeMap<>();
		doc.exhaustive = new TreeMap<>();
		starts.forEach((k, p) -> doc.starts.put(k, new int[]{NavPoint.x(p), NavPoint.y(p), NavPoint.plane(p)}));
		RouteTableGenerator.addDestination(doc, pf, dest, goals, new LinkedHashMap<>(starts));
		return new RouteTable(doc, pf.transitions());
	}
}
