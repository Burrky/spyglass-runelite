package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks for one account's actual requirements (canonical §28.11). The generated route data gives, per destination and start,
 * the unrestricted optimum and two fallbacks; when the account can use the unrestricted optimum it is provably the best
 * usable walk. Otherwise the answer comes from a search over the real graph with exactly the transitions this account can
 * use — a reverse search from the destination, giving every start at once — so a mix of quests, skills and unlocks that no
 * precomputed variant represents is still routed. Unknown requirements stay closed (they are not "allowed").
 *
 * <p>Account searches run on the given executor (one at a time per destination and access); until one finishes the answer
 * is the cheapest usable precomputed variant (a real, usable walk, possibly not the best) or {@link NavOutcome#DATA_UNAVAILABLE}.
 * With no executor (tests, generation) searches run in the caller. Results are cached per access fingerprint (the exact set
 * of transitions allowed) and destination, a bounded number of them.
 *
 * <p>Thread-safe.
 */
public final class AccountRouter
{
	/** Account searches stop here (about 70 MB of scratch at most, a few seconds off the client thread). */
	public static final int MAX_NODES = 4_000_000;
	/**
	 * Results kept: every destination (about 80) for a few access sets (planning, live, possible) without eviction. A smaller
	 * cache thrashes when a whole catalogue is evaluated: each eviction is another full search.
	 */
	private static final int CACHE = 320;

	/** An access with its fingerprint: which transitions it allows. */
	public static final class Key
	{
		final NavAccess access;
		final BitSet allowed;

		Key(NavAccess access, BitSet allowed)
		{
			this.access = access;
			this.allowed = allowed;
		}

		public NavAccess access()
		{
			return access;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Key && ((Key) o).allowed.equals(allowed);
		}

		@Override
		public int hashCode()
		{
			return allowed.hashCode();
		}
	}

	/** One answer. */
	public static final class Answer
	{
		private final NavOutcome outcome;
		private final RouteTable.Walk walk;
		private final boolean best;

		Answer(NavOutcome outcome, RouteTable.Walk walk, boolean best)
		{
			this.outcome = outcome;
			this.walk = walk;
			this.best = best;
		}

		public NavOutcome getOutcome()
		{
			return outcome;
		}

		/** The walk (FOUND), else null. */
		public RouteTable.Walk getWalk()
		{
			return walk;
		}

		/** Proven the cheapest usable walk for this access (false: a usable walk while the account search is pending). */
		public boolean isBest()
		{
			return best;
		}
	}

	private static final class Result
	{
		final Map<String, RouteTable.Walk> walks;
		final boolean exhaustive;

		Result(Map<String, RouteTable.Walk> walks, boolean exhaustive)
		{
			this.walks = walks;
			this.exhaustive = exhaustive;
		}
	}

	private final Pathfinder pf;
	private final RouteTable table;
	private volatile Executor executor;
	private final Map<java.util.List<Object>, Result> cache = new LinkedHashMap<java.util.List<Object>, Result>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<java.util.List<Object>, Result> e)
		{
			return size() > CACHE;
		}
	};
	private final java.util.Set<java.util.List<Object>> pending = new java.util.HashSet<>();
	private final AtomicLong version = new AtomicLong();
	private final AtomicLong searches = new AtomicLong();
	private static final long BUMP_MILLIS = 3000;
	private volatile long lastBump;
	private final Map<Object, Map<String, Key>> keys = Collections.synchronizedMap(new java.util.WeakHashMap<>());

	public AccountRouter(Pathfinder pf, RouteTable table)
	{
		this.pf = pf;
		this.table = table;
	}

	private static volatile AccountRouter shared;

	public static AccountRouter bundled()
	{
		AccountRouter r = shared;
		if (r == null)
		{
			synchronized (AccountRouter.class)
			{
				r = shared;
				if (r == null)
				{
					r = new AccountRouter(Pathfinder.bundled(), RouteTable.bundled());
					r.executor = sharedExecutor;
					shared = r;
				}
			}
		}
		return r;
	}

	/** Account searches run here from now on (null: in the caller). */
	public void setExecutor(Executor executor)
	{
		this.executor = executor;
	}

	private static volatile Executor sharedExecutor;

	/**
	 * The background executor for the shared router, set before its data has loaded (plugin start-up): from then on no
	 * caller ever runs an account search on its own thread, whichever thread first asks. Null (tests, tools): synchronous.
	 */
	public static void useExecutor(Executor executor)
	{
		sharedExecutor = executor;
		AccountRouter r = shared;
		if (r != null)
		{
			r.executor = executor;
		}
	}

	/** Increases whenever an account search finishes (callers recompute plans that may now differ). */
	/** How many account searches have run (diagnostics and tests). */
	public long searches()
	{
		return searches.get();
	}

	/** The shared router's version without loading it (0 until it exists): safe on the client thread. */
	public static long sharedVersion()
	{
		AccountRouter r = shared;
		return r == null ? 0 : r.version();
	}

	public long version()
	{
		return version.get();
	}

	public RouteTable table()
	{
		return table;
	}

	/**
	 * The access's fingerprint. Memoised per {@code owner} (an immutable account snapshot) and {@code mode} ("plan", "live"),
	 * so a plan asks once per snapshot, not per walk.
	 */
	public Key key(NavAccess access, Object owner, String mode)
	{
		if (owner != null)
		{
			Map<String, Key> m = keys.get(owner);
			Key k = m == null ? null : m.get(mode);
			if (k != null)
			{
				return k;
			}
		}
		List<Transition> all = pf.transitions().all();
		BitSet b = new BitSet(all.size());
		for (int i = 0; i < all.size(); i++)
		{
			if (access.allowsTransition(all.get(i)))
			{
				b.set(i);
			}
		}
		Key k = new Key(access, b);
		if (owner != null)
		{
			keys.computeIfAbsent(owner, o -> new java.util.concurrent.ConcurrentHashMap<>()).put(mode, k);
		}
		return k;
	}

	/** The planning key for an account snapshot (skills, quests, unlocks checked; items and restrictions are to bring/do). */
	public Key planning(com.osrstelemetry.plugin.account.AccountCapability cap)
	{
		return key(cap == null ? NavAccess.UNGATED : NavAccess.planning(cap), cap, "plan");
	}

	/** Unread skills, quests and unlocks allowed: tells a closed walk from one that waits on something unread. */
	public Key possible(com.osrstelemetry.plugin.account.AccountCapability cap)
	{
		return key(cap == null ? NavAccess.ANY : NavAccess.possible(cap), cap, "possible");
	}

	/** The live key for an account snapshot (also what is carried and worn now). */
	public Key live(com.osrstelemetry.plugin.account.AccountCapability cap)
	{
		return key(cap == null ? NavAccess.UNGATED : NavAccess.of(cap), cap, "live");
	}

	/** The cheapest usable walk from {@code from} to {@code to} for this access, with what is known about it. */
	public Answer walk(String to, String from, Key key)
	{
		NavOutcome exists = table.existence(to, from);
		if (exists == NavOutcome.UNREACHABLE)
		{
			return new Answer(NavOutcome.UNREACHABLE, null, true); // no walk at all, whatever the account can use
		}
		List<RouteTable.Walk> variants = table.variants(to, from);
		if (!variants.isEmpty() && allowed(variants.get(0), key))
		{
			return new Answer(NavOutcome.FOUND, variants.get(0), true); // the unrestricted optimum is usable: nothing beats it
		}
		if (exists == NavOutcome.DATA_UNAVAILABLE && !table.hasDestination(to))
		{
			return new Answer(NavOutcome.DATA_UNAVAILABLE, null, false);
		}
		java.util.List<Object> id = id(to, key);
		Result r;
		synchronized (cache)
		{
			r = cache.get(id);
		}
		if (r == null)
		{
			Executor ex = executor;
			if (ex == null)
			{
				r = compute(to, key);
			}
			else
			{
				schedule(to, key, id, ex);
			}
		}
		if (r != null)
		{
			RouteTable.Walk w = r.walks.get(from);
			if (w != null)
			{
				return new Answer(NavOutcome.FOUND, w, true);
			}
			return new Answer(r.exhaustive ? NavOutcome.UNREACHABLE : NavOutcome.BUDGET_EXHAUSTED, null, r.exhaustive);
		}
		// Pending: the cheapest precomputed variant this account can use is a real usable walk meanwhile.
		for (RouteTable.Walk w : variants)
		{
			if (allowed(w, key))
			{
				return new Answer(NavOutcome.FOUND, w, false);
			}
		}
		return new Answer(NavOutcome.DATA_UNAVAILABLE, null, false);
	}

	private boolean allowed(RouteTable.Walk w, Key key)
	{
		List<Transition> all = pf.transitions().all();
		for (Transition t : w.getTransitions())
		{
			int i = indexOf(t);
			if (i < 0 ? !key.access.allowsTransition(t) : !key.allowed.get(i))
			{
				return false;
			}
		}
		return true;
	}

	private final Map<Transition, Integer> index = Collections.synchronizedMap(new java.util.IdentityHashMap<>());

	private int indexOf(Transition t)
	{
		Integer i = index.get(t);
		if (i == null)
		{
			List<Transition> all = pf.transitions().all();
			synchronized (index)
			{
				if (index.isEmpty())
				{
					for (int k = 0; k < all.size(); k++)
					{
						index.put(all.get(k), k);
					}
				}
			}
			i = index.get(t);
		}
		return i == null ? -1 : i;
	}

	/** Destination and the exact allowed-transition set (BitSet equality: two accounts share a result only if they allow the same). */
	private static java.util.List<Object> id(String to, Key key)
	{
		return java.util.Arrays.asList(to, key.allowed);
	}

	private void schedule(String to, Key key, java.util.List<Object> id, Executor ex)
	{
		synchronized (pending)
		{
			if (!pending.add(id))
			{
				return;
			}
		}
		try
		{
			ex.execute(() -> {
				try
				{
					compute(to, key);
				}
				finally
				{
					boolean drained;
					synchronized (pending)
					{
						pending.remove(id);
						drained = pending.isEmpty();
					}
					// Plans built on the interim answers are redone when the queue drains, or every few seconds while a long
					// queue (a whole catalogue for a new account) is worked through: not once per search.
					long now = System.currentTimeMillis();
					if (drained || now - lastBump >= BUMP_MILLIS)
					{
						lastBump = now;
						version.incrementAndGet();
					}
				}
			});
		}
		catch (RuntimeException e)
		{
			synchronized (pending)
			{
				pending.remove(id);
			}
		}
	}

	/** One reverse search from the destination with exactly this access; cached. */
	Result compute(String to, Key key)
	{
		int[] goals = table.goals(to);
		List<String> starts = table.allStarts();
		int[] tiles = new int[starts.size()];
		for (int i = 0; i < tiles.length; i++)
		{
			tiles[i] = table.start(starts.get(i));
		}
		searches.incrementAndGet();
		Pathfinder.Search s = pf.fromAll(goals, tiles, key.access, Integer.MAX_VALUE, MAX_NODES, () -> Thread.currentThread().isInterrupted());
		if (s.getStatus() == Pathfinder.Status.CANCELLED)
		{
			return null; // shut down mid-search: nothing cached, nothing claimed
		}
		Map<String, RouteTable.Walk> walks = new HashMap<>();
		for (int i = 0; i < tiles.length; i++)
		{
			NavPath p = s.path(tiles[i]);
			if (p != null)
			{
				walks.put(starts.get(i), new RouteTable.Walk(p.cost(), p.walkTiles(), new ArrayList<>(p.transitions())));
			}
		}
		Result r = new Result(walks, s.isExhaustive());
		synchronized (cache)
		{
			cache.put(id(to, key), r);
		}
		if (executor == null)
		{
			version.incrementAndGet(); // synchronous (tests, tools): the caller already has the answer
		}
		return r;
	}
}
