package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Site Order Optimizer (canonical §14.3), Farming-owned: a PLANNED order of
 * sites for the preparation summary ("First: Falador"). Never persisted, never
 * a cursor: the run page keeps deriving what is next from live observations,
 * and the player may go in any order.
 *
 * Costs (ticks, estimates): arriving at a site by its chosen transport costs
 * {@link #TELEPORT_TICKS} plus the walk from the arrival tile; walking from the
 * previous site costs the walk between their tiles. Running covers two tiles a
 * tick. Exact (Held-Karp) up to {@link #EXACT_LIMIT} sites, else nearest
 * neighbour plus 2-opt. Most legs are teleports whose cost does not depend on
 * the origin, so the order matters mainly for sites within walking distance.
 */
public final class FarmingSiteOrder
{
	public static final int TELEPORT_TICKS = 5;
	public static final int EXACT_LIMIT = 12;
	static final int UNREACHABLE = 1_000_000;

	private FarmingSiteOrder()
	{
	}

	/**
	 * @param arrivalCost  per site: ticks to reach it by its chosen transport from anywhere (UNREACHABLE when none)
	 * @param walk         walk ticks between sites (UNREACHABLE when unknown / different plane)
	 * @return site indices in planned order
	 */
	public static int[] order(int[] arrivalCost, int[][] walk)
	{
		int n = arrivalCost.length;
		if (n == 0)
		{
			return new int[0];
		}
		return n <= EXACT_LIMIT ? heldKarp(arrivalCost, walk) : twoOpt(nearest(arrivalCost, walk), arrivalCost, walk);
	}

	static int leg(int from, int to, int[] arrivalCost, int[][] walk)
	{
		int t = arrivalCost[to];
		if (from >= 0)
		{
			t = Math.min(t, walk[from][to]);
		}
		return t;
	}

	public static long cost(int[] order, int[] arrivalCost, int[][] walk)
	{
		long c = 0;
		int prev = -1;
		for (int i : order)
		{
			c += leg(prev, i, arrivalCost, walk);
			prev = i;
		}
		return c;
	}

	static int[] heldKarp(int[] arrivalCost, int[][] walk)
	{
		int n = arrivalCost.length;
		int full = 1 << n;
		long[][] dp = new long[full][n];
		int[][] parent = new int[full][n];
		for (long[] row : dp)
		{
			Arrays.fill(row, Long.MAX_VALUE);
		}
		for (int j = 0; j < n; j++)
		{
			dp[1 << j][j] = arrivalCost[j];
			parent[1 << j][j] = -1;
		}
		for (int mask = 1; mask < full; mask++)
		{
			for (int last = 0; last < n; last++)
			{
				long base = dp[mask][last];
				if (base == Long.MAX_VALUE || (mask & 1 << last) == 0)
				{
					continue;
				}
				for (int next = 0; next < n; next++)
				{
					if ((mask & 1 << next) != 0)
					{
						continue;
					}
					int nm = mask | 1 << next;
					long c = base + leg(last, next, arrivalCost, walk);
					if (c < dp[nm][next])
					{
						dp[nm][next] = c;
						parent[nm][next] = last;
					}
				}
			}
		}
		int mask = full - 1;
		int last = 0;
		for (int j = 1; j < n; j++)
		{
			if (dp[mask][j] < dp[mask][last])
			{
				last = j;
			}
		}
		int[] out = new int[n];
		for (int k = n - 1; k >= 0; k--)
		{
			out[k] = last;
			int p = parent[mask][last];
			mask &= ~(1 << last);
			last = p;
		}
		return out;
	}

	static int[] nearest(int[] arrivalCost, int[][] walk)
	{
		int n = arrivalCost.length;
		boolean[] used = new boolean[n];
		int[] out = new int[n];
		int prev = -1;
		for (int k = 0; k < n; k++)
		{
			int best = -1;
			for (int j = 0; j < n; j++)
			{
				if (!used[j] && (best < 0 || leg(prev, j, arrivalCost, walk) < leg(prev, best, arrivalCost, walk)))
				{
					best = j;
				}
			}
			used[best] = true;
			out[k] = best;
			prev = best;
		}
		return out;
	}

	static int[] twoOpt(int[] order, int[] arrivalCost, int[][] walk)
	{
		int[] best = order.clone();
		long bestCost = cost(best, arrivalCost, walk);
		boolean improved = true;
		while (improved)
		{
			improved = false;
			for (int i = 0; i < best.length - 1; i++)
			{
				for (int k = i + 1; k < best.length; k++)
				{
					int[] candidate = best.clone();
					for (int a = i, b = k; a < b; a++, b--)
					{
						int t = candidate[a];
						candidate[a] = candidate[b];
						candidate[b] = t;
					}
					long c = cost(candidate, arrivalCost, walk);
					if (c < bestCost)
					{
						best = candidate;
						bestCost = c;
						improved = true;
					}
				}
			}
		}
		return best;
	}

	/** Walk ticks between two tiles (running two tiles a tick), or UNREACHABLE when either is unknown or on another plane. */
	public static int walkTicks(FarmingSites.Tile a, FarmingSites.Tile b)
	{
		if (a == null || b == null || a.plane != b.plane)
		{
			return UNREACHABLE;
		}
		return (Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)) + 1) / 2;
	}

	static List<Integer> asList(int[] order)
	{
		List<Integer> out = new ArrayList<>();
		for (int i : order)
		{
			out.add(i);
		}
		return out;
	}
}
