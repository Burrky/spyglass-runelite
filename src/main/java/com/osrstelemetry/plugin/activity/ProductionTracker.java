package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Attributes PRODUCED items to confirmed production actions (pure; client
 * thread only). Never counts an item merely for appearing somewhere.
 *
 * <h3>Destinations</h3>
 * Output can land in more than one place: the inventory, and a secondary
 * store such as the rune pouch (crafted runes of a type the pouch already
 * holds go straight into it). Each destination is observed as a snapshot
 * (item id -> total quantity) and diffed against its own previous snapshot.
 * Moving items BETWEEN destinations is -N in one and +N in the other: it
 * nets to zero and is never production.
 *
 * <h3>Bounded per-tick correlation</h3>
 * Every observed change is booked into a small per-tick bucket: whether a
 * recipe input was consumed on that tick, the NET change of every output
 * across all destinations, and whether the corroborating skill gained XP.
 * Because each bucket is a sum of consecutive snapshot diffs, it is exact
 * whatever order the inventory, pouch and XP updates arrive in within the
 * tick. A bucket's positive net output is production only when:
 * <ol>
 * <li>an input was consumed on that tick or an adjacent one, in the
 * activity's own context (caller decides); and</li>
 * <li>the skill gained XP within {@link #XP_WINDOW_TICKS} of that
 * consumption.</li>
 * </ol>
 * One XP gain may corroborate several crafts on the same / adjacent ticks
 * (rapid craft -> essence-pouch refill -> craft), and a craft is never
 * missed because its XP arrived before, between or after the item updates.
 * Each bucket's output is emitted incrementally and at most once in total:
 * a later update on the same tick only emits what was not emitted yet, so
 * a craft split between inventory and pouch is +all, never twice. Buckets
 * older than the correlation window are dropped (bounded memory).
 *
 * Anything that adds outputs without consuming an input nearby -- reward
 * items, pick-ups, withdrawals, pouch transfers -- is never production, and
 * anything that removes outputs only moves the baseline: produced totals
 * never go down.
 *
 * Login / re-enable / account switch: {@link #reset()} -- the first
 * snapshot of each destination and the first XP value seen afterwards are
 * baseline only.
 */
public final class ProductionTracker
{
	/** XP may arrive on the same tick as the consumption, or one tick either side; outputs likewise. */
	static final int XP_WINDOW_TICKS = 1;
	/** A bucket can still be corroborated until this many ticks have passed (output -> input -> XP). */
	private static final int RETAIN_TICKS = 2 * XP_WINDOW_TICKS;

	private final ProductionRecipe recipe;
	private Map<Integer, Long> lastInventory;
	private Map<Integer, Long> lastPouch;
	private long lastSkillXp = -1;
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();

	private static final class Bucket
	{
		boolean consumed;
		boolean xp;
		final Map<Integer, Long> net = new HashMap<>();
		final Map<Integer, Long> emitted = new HashMap<>();
	}

	public ProductionTracker(ProductionRecipe recipe)
	{
		this.recipe = recipe;
	}

	public void reset()
	{
		lastInventory = null;
		lastPouch = null;
		lastSkillXp = -1;
		buckets.clear();
	}

	/**
	 * One inventory snapshot (item id -> total quantity). Returns the produced
	 * items newly confirmed by this observation, else an empty map.
	 */
	public Map<Integer, Long> onInventory(Map<Integer, Long> inventory, int tick, boolean inContext)
	{
		Map<Integer, Long> tracked = trackedOf(inventory, true);
		Map<Integer, Long> previous = lastInventory;
		lastInventory = tracked;
		if (previous == null || !inContext)
		{
			return Collections.emptyMap();
		}
		Bucket b = bucket(tick);
		for (int id : recipe.getConsumedItemIds())
		{
			if (tracked.getOrDefault(id, 0L) < previous.getOrDefault(id, 0L))
			{
				b.consumed = true;
			}
		}
		bookOutputs(b, previous, tracked);
		return settle(tick);
	}

	/**
	 * One snapshot of a secondary output store (the rune pouch). Only outputs
	 * are read from it; its first snapshot after {@link #reset()} is baseline.
	 */
	public Map<Integer, Long> onSecondaryStore(Map<Integer, Long> store, int tick, boolean inContext)
	{
		Map<Integer, Long> tracked = trackedOf(store, false);
		Map<Integer, Long> previous = lastPouch;
		lastPouch = tracked;
		if (previous == null || !inContext || previous.equals(tracked))
		{
			return Collections.emptyMap();
		}
		bookOutputs(bucket(tick), previous, tracked);
		return settle(tick);
	}

	/** The corroborating skill's absolute XP. Returns produced items confirmed by this gain, else empty. */
	public Map<Integer, Long> onSkillXp(long xp, int tick)
	{
		boolean gained = lastSkillXp >= 0 && xp > lastSkillXp;
		lastSkillXp = xp;
		if (!gained)
		{
			return Collections.emptyMap();
		}
		bucket(tick).xp = true;
		return settle(tick);
	}

	/** Once per game tick: buckets past the correlation window expire (anything uncorroborated is never counted). */
	public void onTick(int tick)
	{
		buckets.headMap(tick - RETAIN_TICKS).clear();
	}

	public ProductionRecipe getRecipe()
	{
		return recipe;
	}

	/** Test/diagnostic: how many ticks are currently held for correlation. */
	int heldTicks()
	{
		return buckets.size();
	}

	private Bucket bucket(int tick)
	{
		// Bounded even if onTick is never called: drop anything outside the window.
		buckets.headMap(tick - RETAIN_TICKS).clear();
		return buckets.computeIfAbsent(tick, t -> new Bucket());
	}

	private void bookOutputs(Bucket b, Map<Integer, Long> previous, Map<Integer, Long> now)
	{
		for (int id : recipe.getProducedItemIds())
		{
			long delta = now.getOrDefault(id, 0L) - previous.getOrDefault(id, 0L);
			if (delta != 0)
			{
				b.net.merge(id, delta, Long::sum);
			}
		}
	}

	/** Emits, for every corroborated bucket around {@code tick}, the positive net output not emitted yet. */
	private Map<Integer, Long> settle(int tick)
	{
		Map<Integer, Long> out = null;
		for (Map.Entry<Integer, Bucket> e : buckets.subMap(tick - RETAIN_TICKS, true, tick + RETAIN_TICKS, true).entrySet())
		{
			Bucket b = e.getValue();
			if (b.net.isEmpty() || !corroborated(e.getKey()))
			{
				continue;
			}
			for (Map.Entry<Integer, Long> n : b.net.entrySet())
			{
				long already = b.emitted.getOrDefault(n.getKey(), 0L);
				long add = n.getValue() - already;
				if (add > 0)
				{
					b.emitted.put(n.getKey(), n.getValue());
					if (out == null)
					{
						out = new LinkedHashMap<>();
					}
					out.merge(n.getKey(), add, Long::sum);
				}
			}
		}
		return out == null ? Collections.emptyMap() : out;
	}

	/** An input was consumed within the window of this output tick, and XP was gained within the window of that consumption. */
	private boolean corroborated(int outputTick)
	{
		for (Iterator<Map.Entry<Integer, Bucket>> it = buckets.subMap(outputTick - XP_WINDOW_TICKS, true,
			outputTick + XP_WINDOW_TICKS, true).entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Bucket> c = it.next();
			if (!c.getValue().consumed)
			{
				continue;
			}
			for (Bucket x : buckets.subMap(c.getKey() - XP_WINDOW_TICKS, true, c.getKey() + XP_WINDOW_TICKS, true).values())
			{
				if (x.xp)
				{
					return true;
				}
			}
		}
		return false;
	}

	private Map<Integer, Long> trackedOf(Map<Integer, Long> items, boolean includeInputs)
	{
		Map<Integer, Long> tracked = new HashMap<>();
		if (items != null)
		{
			for (Map.Entry<Integer, Long> e : items.entrySet())
			{
				Integer id = e.getKey();
				if (id != null && e.getValue() != null
					&& (recipe.getProducedItemIds().contains(id) || includeInputs && recipe.isTracked(id)))
				{
					tracked.merge(id, e.getValue(), Long::sum);
				}
			}
		}
		return tracked;
	}
}
