package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A walkable route (canonical §28.10): tiles in order, and for each tile the transition taken to reach it (null when walked).
 * Immutable.
 */
public final class NavPath
{
	private final int[] tiles;
	private final Transition[] via;
	private final int cost;

	NavPath(int[] tiles, Transition[] via, int cost)
	{
		this.tiles = tiles;
		this.via = via;
		this.cost = cost;
	}

	public int size()
	{
		return tiles.length;
	}

	public int tile(int i)
	{
		return tiles[i];
	}

	/** The transition taken to arrive at tile i, or null (walked, or the start). */
	public Transition via(int i)
	{
		return via[i];
	}

	/** Total cost in walking tiles (transitions counted by their duration). */
	public int cost()
	{
		return cost;
	}

	public int start()
	{
		return tiles[0];
	}

	public int end()
	{
		return tiles[tiles.length - 1];
	}

	/** Every transition on the way, in order. */
	public List<Transition> transitions()
	{
		List<Transition> out = new ArrayList<>();
		for (Transition t : via)
		{
			if (t != null)
			{
				out.add(t);
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** Everything the route needs (every transition's requirement). */
	public NavRequirement requirement()
	{
		NavRequirement r = NavRequirement.NONE;
		for (Transition t : via)
		{
			if (t != null)
			{
				r = r.and(t.requirement);
			}
		}
		return r;
	}

	/** The index of the tile nearest p on p's plane (Chebyshev), or -1 when none is on that plane. */
	public int nearest(int p)
	{
		int best = -1;
		int bestD = Integer.MAX_VALUE;
		for (int i = 0; i < tiles.length; i++)
		{
			int d = NavPoint.distance(tiles[i], p);
			if (d < bestD)
			{
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/** Walking tiles (excluding transitions). */
	public int walkTiles()
	{
		int n = 0;
		for (int i = 1; i < tiles.length; i++)
		{
			if (via[i] == null)
			{
				n++;
			}
		}
		return n;
	}
}
