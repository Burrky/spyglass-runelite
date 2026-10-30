package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One live route request (canonical §28.11), pure: from the player's tile to the cheapest of several goal groups, each with
 * the cost still to come once it is reached (0 for the stop itself; the hop and the walk after it for a network entrance).
 * The comparison bound {@code maxCost} is the whole teleport journey it competes with, in the same units (walking tiles;
 * transitions count two tiles a tick). Outcomes are kept apart: FOUND; EXCEEDS_COMPARISON (proven: walking costs more than
 * the journey); UNREACHABLE (proven: everything reachable explored); BUDGET_EXHAUSTED (stopped at the node budget, nothing
 * proven); DATA_UNAVAILABLE (no goals, or cancelled).
 */
public final class RoutePlanner
{
	/** Tiles explored at most by one live search. */
	public static final int MAX_NODES = 1_500_000;

	private RoutePlanner()
	{
	}

	/** A goal group: its tiles and the cost still to come after reaching one. */
	public static final class Goal
	{
		final int[] tiles;
		final int remaining;

		public Goal(int[] tiles, int remaining)
		{
			this.tiles = tiles;
			this.remaining = remaining;
		}
	}

	public static LiveRoute plan(Pathfinder pf, String target, int origin, Map<String, Goal> goals, NavAccess access, int maxCost, int maxNodes)
	{
		return plan(pf, target, origin, goals, access, maxCost, maxNodes, null, 0);
	}

	public static LiveRoute plan(Pathfinder pf, String target, int origin, Map<String, Goal> goals, NavAccess access, int maxCost, int maxNodes,
		java.util.function.BooleanSupplier cancelled, long generation)
	{
		long t0 = System.nanoTime();
		List<Integer> tiles = new ArrayList<>();
		List<Integer> extra = new ArrayList<>();
		List<String> group = new ArrayList<>();
		for (Map.Entry<String, Goal> e : goals.entrySet())
		{
			for (int t : e.getValue().tiles)
			{
				tiles.add(t);
				extra.add(e.getValue().remaining);
				group.add(e.getKey());
			}
		}
		if (tiles.isEmpty())
		{
			return new LiveRoute(target, origin, null, NavOutcome.DATA_UNAVAILABLE, null, 0, 0, generation);
		}
		// Where the player stands may be a tile the bundled map blocks (beside a bird house, a patch's edge, a scenery object's
		// footprint; live 2026-10-29, Verdant Valley): the search starts from the standable tiles of the nearest ring instead
		// of finding nothing (pass 31). A standable origin is used as it is.
		int[] starts = pf.map() == null ? new int[]{origin} : Pathfinder.approach(pf.map(), origin, 2);
		if (starts.length == 0)
		{
			starts = new int[]{origin};
		}
		Pathfinder.Search s = pf.toBest(starts, tiles.stream().mapToInt(Integer::intValue).toArray(),
			extra.stream().mapToInt(Integer::intValue).toArray(), access, Math.max(0, maxCost), maxNodes, cancelled);
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		if (s.getStatus() == Pathfinder.Status.CANCELLED)
		{
			return new LiveRoute(target, origin, null, NavOutcome.DATA_UNAVAILABLE, null, s.explored(), ms, generation);
		}
		int found = s.getFound();
		if (found >= 0)
		{
			int i = tiles.indexOf(found);
			long total = (long) s.cost(found) + extra.get(i);
			if (total <= maxCost)
			{
				return new LiveRoute(target, origin, s.path(found), NavOutcome.FOUND, group.get(i), s.explored(), ms, generation);
			}
			return new LiveRoute(target, origin, null, NavOutcome.EXCEEDS_COMPARISON, null, s.explored(), ms, generation);
		}
		NavOutcome o;
		switch (s.getStatus())
		{
			case COST_EXCEEDED:
				o = NavOutcome.EXCEEDS_COMPARISON;
				break;
			case BUDGET_EXHAUSTED:
				o = NavOutcome.BUDGET_EXHAUSTED;
				break;
			default:
				o = s.isExhaustive() ? NavOutcome.UNREACHABLE : NavOutcome.BUDGET_EXHAUSTED;
		}
		return new LiveRoute(target, origin, null, o, null, s.explored(), ms, generation);
	}
}
