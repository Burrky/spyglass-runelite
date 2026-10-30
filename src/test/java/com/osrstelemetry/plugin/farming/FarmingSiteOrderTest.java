package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

/** Site Order Optimizer: exact up to 12 sites, never worse than the identity order, groups walkable sites. */
public class FarmingSiteOrderTest
{
	private static long brute(int[] arrival, int[][] walk)
	{
		int n = arrival.length;
		int[] perm = new int[n];
		for (int i = 0; i < n; i++)
		{
			perm[i] = i;
		}
		long[] best = {Long.MAX_VALUE};
		permute(perm, 0, arrival, walk, best);
		return best[0];
	}

	private static void permute(int[] a, int k, int[] arrival, int[][] walk, long[] best)
	{
		if (k == a.length)
		{
			best[0] = Math.min(best[0], FarmingSiteOrder.cost(a, arrival, walk));
			return;
		}
		for (int i = k; i < a.length; i++)
		{
			int t = a[k];
			a[k] = a[i];
			a[i] = t;
			permute(a, k + 1, arrival, walk, best);
			t = a[k];
			a[k] = a[i];
			a[i] = t;
		}
	}

	@Test
	public void exactOrder_matchesBruteForce_onRandomInstances()
	{
		Random r = new Random(7);
		for (int round = 0; round < 40; round++)
		{
			int n = 2 + r.nextInt(6);
			int[] arrival = new int[n];
			int[][] walk = new int[n][n];
			for (int i = 0; i < n; i++)
			{
				arrival[i] = 5 + r.nextInt(40);
				for (int j = 0; j < n; j++)
				{
					walk[i][j] = i == j ? 0 : r.nextBoolean() ? 1 + r.nextInt(60) : FarmingSiteOrder.UNREACHABLE;
				}
			}
			int[] order = FarmingSiteOrder.order(arrival, walk);
			assertEquals(brute(arrival, walk), FarmingSiteOrder.cost(order, arrival, walk));
		}
	}

	@Test
	public void walkableNeighbours_areVisitedTogether()
	{
		// sites 0 and 2 are 4 ticks apart on foot; every teleport costs 20
		int[] arrival = {20, 20, 20};
		int[][] walk = {{0, 999, 4}, {999, 0, 999}, {4, 999, 0}};
		int[] order = FarmingSiteOrder.order(arrival, walk);
		String s = order[0] + "" + order[1] + order[2];
		assertTrue(s, s.contains("02") || s.contains("20"));
		assertEquals(44, FarmingSiteOrder.cost(order, arrival, walk));
	}

	@Test
	public void manySites_useTheHeuristic_andStayAPermutation()
	{
		int n = 15;
		int[] arrival = new int[n];
		int[][] walk = new int[n][n];
		for (int i = 0; i < n; i++)
		{
			arrival[i] = 10 + i;
			for (int j = 0; j < n; j++)
			{
				walk[i][j] = i == j ? 0 : Math.abs(i - j) * 3;
			}
		}
		int[] order = FarmingSiteOrder.order(arrival, walk);
		boolean[] seen = new boolean[n];
		for (int i : order)
		{
			seen[i] = true;
		}
		for (boolean b : seen)
		{
			assertTrue(b);
		}
		int[] identity = new int[n];
		for (int i = 0; i < n; i++)
		{
			identity[i] = i;
		}
		assertTrue(FarmingSiteOrder.cost(order, arrival, walk) <= FarmingSiteOrder.cost(identity, arrival, walk));
	}
}
