package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Least-cost search over the world: walking on the {@link WorldCollision} map (eight directions, the game's diagonal rule) and
 * {@link Transition}s the {@link NavAccess} allows (canonical §28.10). Costs are walking tiles; a transition costs its
 * duration. Dijkstra with a binary heap and open-addressing tables, so a search touches only what it explores.
 *
 * <p>Every search is bounded by cost and by nodes explored, and says how it ended: {@link Status#FOUND},
 * {@link Status#UNREACHABLE} (everything reachable was explored: no route exists with this access), {@link Status#COST_EXCEEDED} (every route left costs more than the bound), {@link Status#BUDGET_EXHAUSTED} (stopped at the node budget: nothing proven) or {@link Status#CANCELLED}
 * (stopped at the bound: a route may exist but costs more). Reverse searches run from the goals outward over reversed
 * moves, so one search gives every start's distance to the goals.
 *
 * <p>Not thread-safe per instance (scratch tables are reused); the map and transitions it reads are shared and immutable.
 * Run it off the client thread.
 */
public final class Pathfinder
{
	public enum Status
	{
		FOUND,
		UNREACHABLE,
		/** Stopped at the node budget: a route may exist; nothing is proven. */
		BUDGET_EXHAUSTED,
		/** Every route left costs more than the bound: walking loses to the comparison (proven). */
		COST_EXCEEDED,
		/** Stopped on request (a newer search replaced it). */
		CANCELLED
	}

	private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] DY = {1, 1, 0, -1, -1, -1, 0, 1};

	private final WorldCollision map;
	private final TransitionCatalog transitions;
	private final List<Transition> index;
	private final Map<Integer, int[]> outgoing = new HashMap<>();
	private final Map<Integer, int[]> incoming = new HashMap<>();

	public Pathfinder(WorldCollision map, TransitionCatalog transitions)
	{
		this.map = map;
		this.transitions = transitions;
		this.index = transitions.all();
		Map<Integer, List<Integer>> out = new HashMap<>();
		Map<Integer, List<Integer>> in = new HashMap<>();
		for (int i = 0; i < index.size(); i++)
		{
			Transition t = index.get(i);
			out.computeIfAbsent(t.from, k -> new ArrayList<>(2)).add(i);
			in.computeIfAbsent(t.to, k -> new ArrayList<>(2)).add(i);
		}
		out.forEach((k, v) -> outgoing.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
		in.forEach((k, v) -> incoming.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
	}

	/**
	 * Where a tile is reached from: the tile itself when it can be stood on, otherwise the standable tiles of the nearest ring
	 * around it (an object's footprint — a fairy ring, a patch — is blocked; the player stands beside it). Empty when nothing
	 * within {@code maxRadius} can be stood on.
	 */
	public static int[] approach(WorldCollision map, int p, int maxRadius)
	{
		int x = NavPoint.x(p);
		int y = NavPoint.y(p);
		int z = NavPoint.plane(p);
		if (!map.blocked(x, y, z))
		{
			return new int[]{p};
		}
		for (int r = 1; r <= maxRadius; r++)
		{
			List<Integer> ring = new ArrayList<>();
			for (int dx = -r; dx <= r; dx++)
			{
				for (int dy = -r; dy <= r; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) == r && !map.blocked(x + dx, y + dy, z))
					{
						ring.add(NavPoint.pack(x + dx, y + dy, z));
					}
				}
			}
			if (!ring.isEmpty())
			{
				return ring.stream().mapToInt(Integer::intValue).toArray();
			}
		}
		return new int[0];
	}

	public static Pathfinder bundled()
	{
		return new Pathfinder(WorldCollision.bundled(), TransitionCatalog.bundled());
	}

	public WorldCollision map()
	{
		return map;
	}

	public TransitionCatalog transitions()
	{
		return transitions;
	}

	/** The outcome of one search. */
	public final class Search
	{
		private final Status status;
		private final boolean exhaustive;
		private final int found;
		private final boolean reverse;
		private final Table table;

		Search(Status status, boolean exhaustive, int found, boolean reverse, Table table)
		{
			this.status = status;
			this.exhaustive = exhaustive;
			this.found = found;
			this.reverse = reverse;
			this.table = table;
		}

		public Status getStatus()
		{
			return status;
		}

		/**
		 * Whether everything reachable from the sources (with this access) was explored: a tile not settled is then proven
		 * unreachable. False when the search stopped early (goal found first, cost bound, budget, cancelled).
		 */
		public boolean isExhaustive()
		{
			return exhaustive;
		}

		/** The goal reached first (forward search), or -1. */
		public int getFound()
		{
			return found;
		}

		/** Cost from the sources to p (forward) or from p to the goals (reverse); -1 when not settled by this search. */
		public int cost(int p)
		{
			int i = table.find(p);
			return i < 0 || !table.settled(i) ? -1 : table.dist[i];
		}

		public int explored()
		{
			return table.size;
		}

		/** Forward: the route from a source to {@code p}. Reverse: the route from {@code p} to a goal. Null when not settled. */
		public NavPath path(int p)
		{
			int i = table.find(p);
			if (i < 0 || !table.settled(i))
			{
				return null;
			}
			List<Integer> tiles = new ArrayList<>();
			List<Transition> via = new ArrayList<>();
			int cur = p;
			int guard = 0;
			while (true)
			{
				int k = table.find(cur);
				tiles.add(cur);
				int v = table.via[k];
				via.add(v >= 0 ? index.get(v) : null);
				int parent = table.parent[k];
				if (parent == cur || guard++ > 2_000_000)
				{
					break;
				}
				cur = parent;
			}
			int[] t = tiles.stream().mapToInt(Integer::intValue).toArray();
			Transition[] w = new Transition[t.length];
			if (!reverse)
			{
				// collected goal → source: reverse, and shift "via" so that via[i] is how tile i was entered
				int n = t.length;
				int[] ft = new int[n];
				Transition[] fw = new Transition[n];
				for (int j = 0; j < n; j++)
				{
					ft[j] = t[n - 1 - j];
					fw[j] = via.get(n - 1 - j);
				}
				return new NavPath(ft, fw, cost(p));
			}
			// reverse search: collected start → goal already; via[k] is the transition from tile k to its parent (next tile)
			for (int j = 1; j < t.length; j++)
			{
				w[j] = via.get(j - 1);
			}
			return new NavPath(t, w, cost(p));
		}
	}

	/**
	 * Forward search from {@code sources} until the first of {@code goals} is settled.
	 *
	 * @param maxCost  stop once the cheapest open node costs more (COST_EXCEEDED)
	 * @param maxNodes stop once this many tiles were reached (BUDGET_EXHAUSTED)
	 */
	public Search toNearest(int[] sources, int[] goals, NavAccess access, int maxCost, int maxNodes)
	{
		return run(sources, goals, access, maxCost, maxNodes, false, true, null);
	}

	/** Forward search from {@code sources}, settling every one of {@code goals} it can within the bounds. */
	public Search toAll(int[] sources, int[] goals, NavAccess access, int maxCost, int maxNodes)
	{
		return run(sources, goals, access, maxCost, maxNodes, false, false, null);
	}

	/** As {@link #toAll}, stopping (CANCELLED) once {@code cancelled} answers true; it is polled every few thousand tiles. */
	public Search toAll(int[] sources, int[] goals, NavAccess access, int maxCost, int maxNodes, java.util.function.BooleanSupplier cancelled)
	{
		return run(sources, goals, access, maxCost, maxNodes, false, false, cancelled);
	}

	/**
	 * Forward search for the goal with the least total cost, where reaching goal {@code i} still costs {@code extra[i]} (the
	 * hop and walk after a network entrance, say). Stops as soon as no open tile can beat the best total: FOUND with
	 * {@link Search#getFound()} the winning goal.
	 */
	public Search toBest(int[] sources, int[] goals, int[] extra, NavAccess access, int maxCost, int maxNodes, java.util.function.BooleanSupplier cancelled)
	{
		return run(sources, goals, access, maxCost, maxNodes, false, false, cancelled, extra);
	}

	/** Reverse search from {@code goals}: settles each of {@code starts} it can, with its cost and route to the goals. */
	public Search fromAll(int[] goals, int[] starts, NavAccess access, int maxCost, int maxNodes)
	{
		return run(goals, starts, access, maxCost, maxNodes, true, false, null);
	}

	/** As {@link #fromAll}, cancellable. */
	public Search fromAll(int[] goals, int[] starts, NavAccess access, int maxCost, int maxNodes, java.util.function.BooleanSupplier cancelled)
	{
		return run(goals, starts, access, maxCost, maxNodes, true, false, cancelled);
	}

	private Search run(int[] sources, int[] goals, NavAccess access, int maxCost, int maxNodes, boolean reverse, boolean firstOnly,
		java.util.function.BooleanSupplier cancelled)
	{
		return run(sources, goals, access, maxCost, maxNodes, reverse, firstOnly, cancelled, null);
	}

	private Search run(int[] sources, int[] goals, NavAccess access, int maxCost, int maxNodes, boolean reverse, boolean firstOnly,
		java.util.function.BooleanSupplier cancelled, int[] extra)
	{
		long bestTotal = Long.MAX_VALUE;
		Map<Integer, Integer> extraOf = null;
		if (extra != null)
		{
			extraOf = new HashMap<>();
			for (int i = 0; i < goals.length; i++)
			{
				extraOf.merge(goals[i], extra[i], Math::min);
			}
		}
		boolean exhaustive = false;
		int pops = 0;
		Table table = new Table(1 << 16);
		LongHeap heap = new LongHeap();
		Table goalSet = new Table(Math.max(16, goals.length * 2));
		for (int g : goals)
		{
			goalSet.insert(g);
		}
		int remaining = goalSet.size;
		Map<Integer, Boolean> allowed = new HashMap<>();
		for (int s : sources)
		{
			int k = table.insert(s);
			table.dist[k] = 0;
			table.parent[k] = s;
			table.via[k] = -1;
			heap.push(0, s);
		}
		int found = -1;
		Status status = Status.UNREACHABLE;
		while (true)
		{
			if (heap.isEmpty())
			{
				exhaustive = true;
				break;
			}
			if (cancelled != null && (++pops & 4095) == 0 && cancelled.getAsBoolean())
			{
				status = Status.CANCELLED;
				break;
			}
			long top = heap.pop();
			int cost = (int) (top >>> 32);
			int p = (int) top;
			int k = table.find(p);
			if (table.settled(k) || table.dist[k] != cost)
			{
				continue;
			}
			if (extraOf != null && cost >= bestTotal)
			{
				status = Status.FOUND; // nothing open can beat the best goal's total
				break;
			}
			if (cost > maxCost)
			{
				status = found >= 0 ? Status.FOUND : Status.COST_EXCEEDED;
				break;
			}
			table.settle(k);
			if (extraOf != null)
			{
				Integer e = extraOf.get(p);
				if (e != null && (long) cost + e < bestTotal)
				{
					bestTotal = (long) cost + e;
					found = p;
				}
				if (table.size > maxNodes)
				{
					status = Status.BUDGET_EXHAUSTED;
					break;
				}
				expand(table, heap, p, cost, access, reverse, allowed);
				continue;
			}
			if (goalSet.find(p) >= 0)
			{
				remaining--;
				if (found < 0)
				{
					found = p;
				}
				if (firstOnly || remaining == 0)
				{
					status = Status.FOUND;
					break;
				}
			}
			if (table.size > maxNodes)
			{
				status = Status.BUDGET_EXHAUSTED;
				break;
			}
			expand(table, heap, p, cost, access, reverse, allowed);
		}
		if (status == Status.UNREACHABLE && found >= 0)
		{
			status = Status.FOUND;
		}
		return new Search(status, exhaustive, found, reverse, table);
	}

	private void expand(Table table, LongHeap heap, int p, int cost, NavAccess access, boolean reverse, Map<Integer, Boolean> allowed)
	{
		int x = NavPoint.x(p);
		int y = NavPoint.y(p);
		int z = NavPoint.plane(p);
		for (int d = 0; d < 8; d++)
		{
			int nx = x + DX[d];
			int ny = y + DY[d];
			boolean ok = reverse ? map.canStep(nx, ny, z, -DX[d], -DY[d]) : map.canStep(x, y, z, DX[d], DY[d]);
			if (ok)
			{
				relax(table, heap, p, NavPoint.pack(nx, ny, z), cost + 1, -1, reverse);
			}
		}
		int[] ts = (reverse ? incoming : outgoing).get(p);
		if (ts != null)
		{
			for (int ti : ts)
			{
				Transition t = index.get(ti);
				Boolean ok = allowed.get(ti);
				if (ok == null)
				{
					ok = access.allowsTransition(t);
					allowed.put(ti, ok);
				}
				if (ok)
				{
					relax(table, heap, p, reverse ? t.from : t.to, cost + t.cost(), ti, reverse);
				}
			}
		}
	}

	private static void relax(Table table, LongHeap heap, int from, int to, int cost, int via, boolean reverse)
	{
		if (Wilderness.contains(to) && (reverse || !Wilderness.contains(from)))
		{
			return; // never into the Wilderness (one standing in it may walk out)
		}
		int k = table.find(to);
		if (k < 0)
		{
			k = table.insert(to);
			table.dist[k] = Integer.MAX_VALUE;
		}
		if (table.settled(k) || cost >= table.dist[k])
		{
			return;
		}
		table.dist[k] = cost;
		table.parent[k] = from;
		table.via[k] = via;
		heap.push(cost, to);
	}

	/** Open-addressing table: packed point → dist, parent, via, settled. Grows as needed. */
	static final class Table
	{
		int[] keys;
		int[] dist;
		int[] parent;
		int[] via;
		boolean[] done;
		int size;
		private int mask;

		Table(int capacity)
		{
			int c = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
			alloc(c);
		}

		private void alloc(int c)
		{
			keys = new int[c];
			Arrays.fill(keys, -1);
			dist = new int[c];
			parent = new int[c];
			via = new int[c];
			done = new boolean[c];
			mask = c - 1;
			size = 0;
		}

		private static int hash(int k)
		{
			int h = k * 0x9E3779B1;
			return h ^ (h >>> 16);
		}

		int find(int key)
		{
			int i = hash(key) & mask;
			while (true)
			{
				int k = keys[i];
				if (k == key)
				{
					return i;
				}
				if (k == -1)
				{
					return -1;
				}
				i = (i + 1) & mask;
			}
		}

		int insert(int key)
		{
			if ((size + 1) * 2 > keys.length)
			{
				grow();
			}
			int i = hash(key) & mask;
			while (keys[i] != -1 && keys[i] != key)
			{
				i = (i + 1) & mask;
			}
			if (keys[i] == -1)
			{
				keys[i] = key;
				size++;
			}
			return i;
		}

		boolean settled(int i)
		{
			return i >= 0 && done[i];
		}

		void settle(int i)
		{
			done[i] = true;
		}

		private void grow()
		{
			int[] ok = keys;
			int[] od = dist;
			int[] op = parent;
			int[] ov = via;
			boolean[] odone = done;
			alloc(ok.length * 2);
			for (int j = 0; j < ok.length; j++)
			{
				if (ok[j] != -1)
				{
					int i = insert(ok[j]);
					dist[i] = od[j];
					parent[i] = op[j];
					via[i] = ov[j];
					done[i] = odone[j];
				}
			}
		}
	}

	/** Min-heap of (cost, point) packed into longs. */
	static final class LongHeap
	{
		private long[] a = new long[1024];
		private int n;

		void push(int cost, int p)
		{
			if (n == a.length)
			{
				a = Arrays.copyOf(a, n * 2);
			}
			long v = (long) cost << 32 | (p & 0xFFFFFFFFL);
			int i = n++;
			while (i > 0)
			{
				int parent = (i - 1) >>> 1;
				if (a[parent] <= v)
				{
					break;
				}
				a[i] = a[parent];
				i = parent;
			}
			a[i] = v;
		}

		long pop()
		{
			long top = a[0];
			long last = a[--n];
			int i = 0;
			while (true)
			{
				int c = 2 * i + 1;
				if (c >= n)
				{
					break;
				}
				if (c + 1 < n && a[c + 1] < a[c])
				{
					c++;
				}
				if (a[c] >= last)
				{
					break;
				}
				a[i] = a[c];
				i = c;
			}
			if (n > 0)
			{
				a[i] = last;
			}
			return top;
		}

		boolean isEmpty()
		{
			return n == 0;
		}
	}
}
