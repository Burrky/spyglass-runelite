package com.osrstelemetry.plugin.prep;

import java.util.Collections;
import java.util.List;

/**
 * One need's evaluation. Facts only; the consumer's view turns them into
 * player text. Ownership (where it was last seen, and when) is kept apart from
 * carried readiness: an item last seen in the bank is not carried, and its
 * bank observation keeps its age instead of expiring.
 */
public final class NeedResult
{
	/** How a CARRIED need is carried. */
	public enum Via
	{
		INVENTORY,
		WORN,
		RUNE_POUCH,
		/** A worn rune provider (an elemental staff) supplies the rune. */
		STAFF
	}

	private final ItemNeed need;
	private final NeedStatus status;
	private final long carried;
	private final long banked;
	/** Bank tab 1-9, 0 = main tab, -1 = unknown or not in the bank. */
	private final int bankTab;
	/** The bank item id to highlight, or -1. */
	private final int bankItemId;
	private final String detail;
	private Via via;
	private long bankSeenAt = -1;
	private long elsewhereSeenAt = -1;
	private List<String> places = Collections.emptyList();
	private int displayItemId;
	private long withdrawItems;
	private long found;
	private boolean carriedNotWorn;

	NeedResult(ItemNeed need, NeedStatus status, long carried, long banked, int bankTab, int bankItemId, String detail)
	{
		this.need = need;
		this.status = status;
		this.carried = carried;
		this.banked = banked;
		this.bankTab = bankTab;
		this.bankItemId = bankItemId;
		this.detail = detail;
		this.displayItemId = need.getAcceptableItemIds()[0];
	}

	NeedResult via(Via v)
	{
		this.via = v;
		return this;
	}

	NeedResult bankSeenAt(long t)
	{
		this.bankSeenAt = t;
		return this;
	}

	NeedResult elsewhere(List<String> where, long seenAt)
	{
		this.places = Collections.unmodifiableList(where);
		this.elsewhereSeenAt = seenAt;
		return this;
	}

	NeedResult display(int itemId)
	{
		if (itemId > 0)
		{
			this.displayItemId = itemId;
		}
		return this;
	}

	NeedResult withdraw(long items)
	{
		this.withdrawItems = items;
		return this;
	}

	NeedResult found(long n)
	{
		this.found = n;
		return this;
	}

	public ItemNeed getNeed()
	{
		return need;
	}

	public NeedStatus getStatus()
	{
		return status;
	}

	/** How many are carried, in the need's units (a large value for an unlimited provider). */
	public long getCarried()
	{
		return carried;
	}

	public long getBanked()
	{
		return banked;
	}

	/** Still to collect, in the need's units: required minus carried (0 when carried). */
	public long getStillNeeded()
	{
		return Math.max(0, need.required() - Math.min(carried, need.required()));
	}

	/** Items to take out of the bank to cover what is still needed (IN_BANK only; otherwise 0). */
	public long getWithdrawItems()
	{
		return withdrawItems;
	}

	public int getBankTab()
	{
		return bankTab;
	}

	public int getBankItemId()
	{
		return bankItemId;
	}

	/** The item to picture: the variant carried, else the one last seen stored, else the first acceptable one. */
	public int getDisplayItemId()
	{
		return displayItemId;
	}

	/** CARRIED: how (inventory, worn, rune pouch, staff); otherwise null. */
	public Via getVia()
	{
		return via;
	}

	/** When the bank used for this result was observed, or -1 when it never was. */
	public long getBankSeenAt()
	{
		return bankSeenAt;
	}

	/** ELSEWHERE: the other storages that had it ("group storage", "seed vault"). */
	public List<String> getPlaces()
	{
		return places;
	}

	public long getElsewhereSeenAt()
	{
		return elsewhereSeenAt;
	}

	/** NOT_FOUND: how many units were found in total across the observed places. */
	public long getFound()
	{
		return found;
	}

	/** Compact diagnostic text: "carried", "bank · tab 2 (3h)", "not found · bank seen 3h ago". */
	public String getDetail()
	{
		return detail;
	}

	/** Covered: carried, or held in a store reachable where it is used ({@link NeedStatus#REACHABLE}). */
	public boolean isSatisfied()
	{
		return status == NeedStatus.CARRIED && !carriedNotWorn || status == NeedStatus.REACHABLE;
	}

	/** WORN_ONLY needs: carried in the inventory but not worn (its effect needs it equipped). */
	public boolean isCarriedNotWorn()
	{
		return carriedNotWorn;
	}

	NeedResult carriedNotWorn(boolean b)
	{
		this.carriedNotWorn = b;
		return this;
	}
}
