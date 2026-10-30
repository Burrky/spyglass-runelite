package com.osrstelemetry.plugin.guidance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Walking path over the loaded scene (canonical §14.1): breadth-first search
 * over the client's collision flags, 8-directional with OSRS movement rules.
 * A step is blocked by a wall on the side of the tile it leaves or the side of
 * the tile it enters, or a fully blocked tile; a diagonal step also needs both
 * cardinal neighbours passable for it. No world-scale data: when the target is
 * outside the scene the path ends at the passable scene tile closest to it
 * ("progressive continuation"), and is re-planned on the next scene load.
 * Closed doors and gates read as walls (the player opens them).
 *
 * <p>Pure: flags are passed in ({@code flags[x][y]} in scene coordinates, as
 * {@code CollisionData.getFlags()} returns them). Flag values are RuneLite's
 * {@code CollisionDataFlag} constants (runelite-api 1.13.1).
 */
public final class LocalWalker
{
	static final int NW = 1;
	static final int N = 2;
	static final int NE = 4;
	static final int E = 8;
	static final int SE = 16;
	static final int S = 32;
	static final int SW = 64;
	static final int W = 128;
	/** OBJECT | FLOOR_DECORATION | FLOOR (CollisionDataFlag.BLOCK_MOVEMENT_FULL). */
	static final int FULL = 256 | 262144 | 2097152;

	private static final int[] DX = {0, 1, 0, -1, 1, 1, -1, -1};
	private static final int[] DY = {1, 0, -1, 0, 1, -1, -1, 1};

	private LocalWalker()
	{
	}

	/** A scene tile (scene coordinates). */
	public static final class Tile
	{
		public final int x;
		public final int y;

		public Tile(int x, int y)
		{
			this.x = x;
			this.y = y;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Tile && ((Tile) o).x == x && ((Tile) o).y == y;
		}

		@Override
		public int hashCode()
		{
			return x * 31 + y;
		}

		@Override
		public String toString()
		{
			return "(" + x + "," + y + ")";
		}
	}

	/** Whether one step from (x, y) by (dx, dy) is allowed. */
	static boolean canStep(int[][] f, int x, int y, int dx, int dy)
	{
		int w = f.length;
		int h = f[0].length;
		int tx = x + dx;
		int ty = y + dy;
		if (tx < 0 || ty < 0 || tx >= w || ty >= h)
		{
			return false;
		}
		int from = f[x][y];
		int to = f[tx][ty];
		if ((to & FULL) != 0)
		{
			return false;
		}
		if (dx == 0 || dy == 0)
		{
			return (from & side(dx, dy)) == 0 && (to & side(-dx, -dy)) == 0;
		}
		// Diagonal: the corner walls of both tiles, and both cardinal moves.
		if ((from & corner(dx, dy)) != 0 || (to & corner(-dx, -dy)) != 0)
		{
			return false;
		}
		return canStep(f, x, y, dx, 0) && canStep(f, x, y, 0, dy) && canStep(f, x + dx, y, 0, dy) && canStep(f, x, y + dy, dx, 0);
	}

	private static int side(int dx, int dy)
	{
		if (dy > 0)
		{
			return N;
		}
		if (dy < 0)
		{
			return S;
		}
		return dx > 0 ? E : W;
	}

	private static int corner(int dx, int dy)
	{
		if (dx > 0)
		{
			return dy > 0 ? NE : SE;
		}
		return dy > 0 ? NW : SW;
	}

	/**
	 * The shortest walk from {@code start} to any tile within {@code radius} of
	 * {@code target} (Chebyshev), or, when none is reachable inside the scene,
	 * to the reachable tile closest to the target. The path excludes the start
	 * tile. Empty when the start is outside the grid or already there.
	 */
	public static List<Tile> path(int[][] flags, Tile start, Tile target, int radius)
	{
		if (flags == null || flags.length == 0 || start == null || target == null)
		{
			return Collections.emptyList();
		}
		int w = flags.length;
		int h = flags[0].length;
		if (start.x < 0 || start.y < 0 || start.x >= w || start.y >= h)
		{
			return Collections.emptyList();
		}
		if (dist(start, target) <= radius)
		{
			return Collections.emptyList();
		}
		int[] prev = new int[w * h];
		java.util.Arrays.fill(prev, -2);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int s = start.x * h + start.y;
		prev[s] = -1;
		queue.add(s);
		int best = s;
		int bestDist = dist(start, target);
		int found = -1;
		while (!queue.isEmpty())
		{
			int cur = queue.poll();
			int cx = cur / h;
			int cy = cur % h;
			for (int d = 0; d < 8; d++)
			{
				if (!canStep(flags, cx, cy, DX[d], DY[d]))
				{
					continue;
				}
				int nx = cx + DX[d];
				int ny = cy + DY[d];
				int n = nx * h + ny;
				if (prev[n] != -2)
				{
					continue;
				}
				prev[n] = cur;
				int dd = Math.max(Math.abs(nx - target.x), Math.abs(ny - target.y));
				if (dd <= radius)
				{
					found = n;
					queue.clear();
					break;
				}
				if (dd < bestDist)
				{
					bestDist = dd;
					best = n;
				}
				queue.add(n);
			}
		}
		int end = found >= 0 ? found : best;
		List<Tile> out = new ArrayList<>();
		for (int c = end; c != s && c >= 0; c = prev[c])
		{
			out.add(new Tile(c / h, c % h));
		}
		Collections.reverse(out);
		return out;
	}

	/**
	 * The shortest walk to the patch itself: to any tile next to its footprint
	 * (the cardinal neighbours of the object's tiles, never the object's own
	 * blocked tiles), so the path ends where the player can interact. Empty
	 * when the player already stands on such a tile, or when none is reachable
	 * (the caller then falls back to {@link #path}).
	 */
	public static List<Tile> pathToObject(int[][] flags, Tile start, Tile min, Tile max)
	{
		if (flags == null || flags.length == 0 || start == null || min == null || max == null)
		{
			return Collections.emptyList();
		}
		int w = flags.length;
		int h = flags[0].length;
		java.util.Set<Integer> goals = new java.util.HashSet<>();
		for (int x = min.x - 1; x <= max.x + 1; x++)
		{
			for (int y = min.y - 1; y <= max.y + 1; y++)
			{
				boolean inside = x >= min.x && x <= max.x && y >= min.y && y <= max.y;
				boolean corner = (x < min.x || x > max.x) && (y < min.y || y > max.y);
				if (!inside && !corner && x >= 0 && y >= 0 && x < w && y < h && (flags[x][y] & FULL) == 0)
				{
					goals.add(x * h + y);
				}
			}
		}
		if (start.x < 0 || start.y < 0 || start.x >= w || start.y >= h || goals.isEmpty() || goals.contains(start.x * h + start.y))
		{
			return Collections.emptyList();
		}
		int[] prev = new int[w * h];
		java.util.Arrays.fill(prev, -2);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int s = start.x * h + start.y;
		prev[s] = -1;
		queue.add(s);
		int found = -1;
		while (!queue.isEmpty() && found < 0)
		{
			int cur = queue.poll();
			int cx = cur / h;
			int cy = cur % h;
			for (int d = 0; d < 8; d++)
			{
				if (!canStep(flags, cx, cy, DX[d], DY[d]))
				{
					continue;
				}
				int n = (cx + DX[d]) * h + cy + DY[d];
				if (prev[n] != -2)
				{
					continue;
				}
				prev[n] = cur;
				if (goals.contains(n))
				{
					found = n;
					break;
				}
				queue.add(n);
			}
		}
		if (found < 0)
		{
			return Collections.emptyList();
		}
		List<Tile> out = new ArrayList<>();
		for (int c = found; c != s && c >= 0; c = prev[c])
		{
			out.add(new Tile(c / h, c % h));
		}
		Collections.reverse(out);
		return out;
	}

	/** Whether the player stands next to the object (a tile {@link #pathToObject} would end on). */
	public static boolean beside(Tile p, Tile min, Tile max)
	{
		boolean inX = p.x >= min.x && p.x <= max.x;
		boolean inY = p.y >= min.y && p.y <= max.y;
		return inX && (p.y == min.y - 1 || p.y == max.y + 1) || inY && (p.x == min.x - 1 || p.x == max.x + 1);
	}

	/**
	 * A copy of the flags with closed doors treated as open: their tile's wall
	 * sides and the facing side of each neighbour are cleared (a door the
	 * player opens on the way, never a wall).
	 */
	public static int[][] withOpenDoors(int[][] flags, List<Tile> doors)
	{
		int[][] f = new int[flags.length][];
		for (int i = 0; i < flags.length; i++)
		{
			f[i] = flags[i].clone();
		}
		int w = f.length;
		int h = w == 0 ? 0 : f[0].length;
		for (Tile d : doors)
		{
			if (d.x < 0 || d.y < 0 || d.x >= w || d.y >= h)
			{
				continue;
			}
			f[d.x][d.y] &= ~(N | E | S | W | NE | NW | SE | SW);
			if (d.y + 1 < h)
			{
				f[d.x][d.y + 1] &= ~(S | SE | SW);
			}
			if (d.y > 0)
			{
				f[d.x][d.y - 1] &= ~(N | NE | NW);
			}
			if (d.x + 1 < w)
			{
				f[d.x + 1][d.y] &= ~(W | NW | SW);
			}
			if (d.x > 0)
			{
				f[d.x - 1][d.y] &= ~(E | NE | SE);
			}
		}
		return f;
	}

	static int dist(Tile a, Tile b)
	{
		return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
	}
}
