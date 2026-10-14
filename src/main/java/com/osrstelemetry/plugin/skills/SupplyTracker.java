package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A skill method's CONSUMABLE (generic): what is usable right now, and how
 * fast the method actually uses it -- the RUNWAY ("how long until I run
 * out at what I really use"). Pure; client thread only.
 *
 * <ul>
 * <li>COUNT: the supply items in the inventory now (what can be used
 * without banking; the bank never counts). A withdrawal raises it at once,
 * a deposit lowers it -- it is a live read, not a ledger.</li>
 * <li>CONSUMPTION: only a decrease the adapter attributes to the skill's own
 * success (bait used on a catch) -- never a deposit, a drop or a use for
 * something else. Never assumed 1 per success: what actually disappeared is
 * what counts (a save-the-bait effect simply shows as a slower rate).</li>
 * <li>RATE: consumed units / time on THIS method since it became current
 * (the method's own bounded training time). Scoped per method: a method
 * change starts over.</li>
 * <li>RUNWAY: count / rate -- withheld (null) until at least
 * {@link #MIN_CONSUMED} units over {@link #MIN_MILLIS} of method time were
 * seen.</li>
 * </ul>
 * Supplies are input state, never production, and never telemetry.
 */
public final class SupplyTracker
{
	public static final long MIN_CONSUMED = 10L;
	public static final long MIN_MILLIS = 3L * 60_000L;

	private Set<Integer> items = Collections.emptySet();
	private String method;
	private long count = -1L;
	private long consumed;
	private long methodMillisAtStart = -1L;

	/**
	 * The current method and the supply items it uses (empty = it uses none).
	 * A different method (or item set) starts the rate over.
	 */
	public void scope(String methodKey, Set<Integer> supplyItems, long methodMillisNow)
	{
		Set<Integer> s = supplyItems == null ? Collections.<Integer>emptySet() : supplyItems;
		if (java.util.Objects.equals(methodKey, method) && s.equals(items))
		{
			if (methodMillisAtStart < 0)
			{
				methodMillisAtStart = methodMillisNow;
			}
			return;
		}
		method = methodKey;
		items = Collections.unmodifiableSet(new LinkedHashSet<>(s));
		consumed = 0L;
		methodMillisAtStart = methodMillisNow;
	}

	public boolean isSupply(int itemId)
	{
		return items.contains(itemId);
	}

	public boolean hasSupply()
	{
		return !items.isEmpty();
	}

	public Set<Integer> getItems()
	{
		return items;
	}

	/** The live usable count, from the inventory. */
	public void onInventory(Map<Integer, Long> inventory)
	{
		long c = 0L;
		for (Integer id : items)
		{
			c += inventory.getOrDefault(id, 0L);
		}
		count = c;
	}

	/** Units the method's own success used up. */
	public void consumed(long units)
	{
		if (units > 0)
		{
			consumed += units;
		}
	}

	/** Usable now; -1 before the inventory was seen. */
	public long getCount()
	{
		return count;
	}

	public long getConsumed()
	{
		return consumed;
	}

	/** Until the supply runs out at the observed rate; null while the observation is too thin. */
	public Long runwayMillis(long methodMillisNow)
	{
		if (items.isEmpty() || count < 0 || methodMillisAtStart < 0)
		{
			return null;
		}
		long millis = methodMillisNow - methodMillisAtStart;
		if (consumed < MIN_CONSUMED || millis < MIN_MILLIS)
		{
			return null;
		}
		if (count == 0)
		{
			return 0L;
		}
		return (long) Math.ceil(count * (double) millis / consumed);
	}

	public void reset()
	{
		items = Collections.emptySet();
		method = null;
		count = -1L;
		consumed = 0L;
		methodMillisAtStart = -1L;
	}
}
