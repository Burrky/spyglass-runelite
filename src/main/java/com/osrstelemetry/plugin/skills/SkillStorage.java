package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * A SPECIALIZED STORAGE container a skill's production can go straight into
 * (generic): Mining's gem bag family, Fishing's fish barrel. Which container
 * the inventory holds, whether it is OPEN (an open one catches production
 * before it reaches the inventory), what it accepts, and -- only once
 * Spyglass has actually seen it -- how full it is. Pure; client thread only.
 *
 * <h3>Honest contents</h3>
 * None of these containers is exposed natively (no varbit / item container
 * in the API), so contents become KNOWN only when an event proves them:
 * emptying the whole container into the bank (all zero). Until then only
 * what this trip routed into it is known ("+12 this trip") -- never a fake
 * "12 / 28" that really means "12 added this session". Contents are
 * ephemeral (never persisted) and reset at login / when the container
 * changes.
 *
 * <h3>Never the bank</h3>
 * Being in storage is not BANKED. The ProductionLedger keeps what this
 * session put in storage; only a proven deposit from it banks anything.
 */
public final class SkillStorage
{
	/** One kind of container, as the adapter defines it. */
	public static final class Kind
	{
		private final String label;
		private final int closedId;
		private final int openId;
		private final Integer capacity;
		private final IntPredicate accepts;

		/**
		 * @param capacity total items it holds; null = not verified (no "/ cap" is ever shown).
		 * @param accepts which produced items it takes (null = any of the skill's storable resources).
		 */
		public Kind(String label, int closedId, int openId, Integer capacity, IntPredicate accepts)
		{
			this.label = Objects.requireNonNull(label, "label");
			this.closedId = closedId;
			this.openId = openId;
			this.capacity = capacity;
			this.accepts = accepts;
		}

		public String getLabel()
		{
			return label;
		}

		public Integer getCapacity()
		{
			return capacity;
		}

		public int getClosedId()
		{
			return closedId;
		}

		public int getOpenId()
		{
			return openId;
		}

		/** Whether this container takes the item at all (production it cannot store always reaches the inventory). */
		public boolean accepts(int itemId)
		{
			return accepts == null || accepts.test(itemId);
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private final List<Kind> kinds;
	private Kind kind;
	private boolean open;
	/** Known contents total, or null while unknown. */
	private Integer known;
	/** What this trip routed into it (since it was last emptied / since login). */
	private int addedThisTrip;

	public SkillStorage(List<Kind> kinds)
	{
		this.kinds = Collections.unmodifiableList(kinds);
	}

	/** Re-reads which container the inventory holds (an open one wins: it is the one catching). */
	public void onInventory(Map<Integer, Long> inventory)
	{
		Kind found = null;
		boolean foundOpen = false;
		for (Kind k : kinds)
		{
			boolean hasOpen = inventory.getOrDefault(k.openId, 0L) > 0;
			boolean hasClosed = inventory.getOrDefault(k.closedId, 0L) > 0;
			if ((hasOpen || hasClosed) && (found == null || (hasOpen && !foundOpen)))
			{
				found = k;
				foundOpen = hasOpen;
			}
		}
		if (found != kind)
		{
			known = null;
			addedThisTrip = 0;
		}
		kind = found;
		open = foundOpen;
	}

	public Kind getKind()
	{
		return kind;
	}

	/** Carried AND open: production goes here first. */
	public boolean isCatching()
	{
		return kind != null && open;
	}

	/** Carried and open and takes this item. */
	public boolean isCatching(int itemId)
	{
		return isCatching() && kind.accepts(itemId);
	}

	public boolean isHeld()
	{
		return kind != null;
	}

	/** Whether an item id is one of this skill's containers (so it is never mistaken for anything else). */
	public boolean isContainerItem(int itemId)
	{
		for (Kind k : kinds)
		{
			if (k.openId == itemId || k.closedId == itemId)
			{
				return true;
			}
		}
		return false;
	}

	public void routed(int items)
	{
		addedThisTrip += items;
		if (known != null)
		{
			known += items;
		}
	}

	/** Moved in from the inventory ("Fill"). */
	public void filled(int items)
	{
		if (known != null)
		{
			known += items;
		}
	}

	/** Moved out to the inventory ("Empty" without the bank). */
	public void emptiedToInventory(int items)
	{
		addedThisTrip = 0;
		if (known != null)
		{
			known = Math.max(0, known - items);
		}
	}

	/** Emptied into the bank: the whole container goes, so it is now known to be empty. */
	public void emptiedToBank()
	{
		known = 0;
		addedThisTrip = 0;
	}

	public Integer getKnownContents()
	{
		return known;
	}

	/** Free room when both the contents and the capacity are known; otherwise null (never guessed). */
	public Integer getKnownRoom()
	{
		return known == null || kind == null || kind.capacity == null ? null : Math.max(0, kind.capacity - known);
	}

	public int getAddedThisTrip()
	{
		return addedThisTrip;
	}

	public void reset()
	{
		kind = null;
		open = false;
		known = null;
		addedThisTrip = 0;
	}
}
