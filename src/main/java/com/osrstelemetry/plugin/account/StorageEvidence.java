package com.osrstelemetry.plugin.account;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What one storage (bank, group storage) held when Spyglass last saw it open
 * for this owner. It is evidence with an age, not live truth: the player may
 * have withdrawn or bought things since. There is no time-based downgrade (an
 * old bank view stays evidence until the next one replaces it); consumers show
 * its age instead. Absence of a StorageEvidence means "never seen", which is
 * never the same as "owns nothing".
 */
public final class StorageEvidence
{
	public enum Kind
	{
		/** The player's bank (InventoryID.BANK), captured whenever the game sends it. */
		BANK,
		/** Group storage (InventoryID.INV_GROUP_TEMP), captured while open; shared with the group. */
		GROUP_STORAGE,
		/** The Farming Guild seed vault (InventoryID.SEED_VAULT), captured while open. */
		SEED_VAULT
	}

	/** Bank tabs 1-9 (the main tab is 0), from the BANK_TAB_1..9 item counts the bank build script uses. */
	public static final int TABS = 9;

	private final Kind kind;
	private final Map<Integer, Integer> items;
	private final long observedAt;
	/** BANK only: item id -> first container slot holding it (empty when unknown). */
	private final Map<Integer, Integer> firstSlot;
	/** BANK only: item counts of tabs 1..9 when observed, or null when unknown. */
	private final int[] tabCounts;

	public StorageEvidence(Kind kind, Map<Integer, Integer> items, long observedAt)
	{
		this(kind, items, observedAt, null, null);
	}

	public StorageEvidence(Kind kind, Map<Integer, Integer> items, long observedAt, Map<Integer, Integer> firstSlot, int[] tabCounts)
	{
		this.kind = Objects.requireNonNull(kind);
		Map<Integer, Integer> copy = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			if (e.getKey() != null && e.getKey() > 0 && e.getValue() != null && e.getValue() > 0)
			{
				copy.merge(e.getKey(), e.getValue(), Integer::sum); // placeholders (quantity 0) are not owned items
			}
		}
		this.items = Collections.unmodifiableMap(copy);
		this.observedAt = observedAt;
		this.firstSlot = firstSlot == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(firstSlot));
		this.tabCounts = tabCounts == null || tabCounts.length != TABS ? null : tabCounts.clone();
	}

	/** The same observation (identity, or equal kind, time, items, slots and tabs). */
	public static boolean same(StorageEvidence a, StorageEvidence b)
	{
		if (a == b)
		{
			return true;
		}
		return a != null && b != null && a.kind == b.kind && a.observedAt == b.observedAt && a.items.equals(b.items)
			&& a.firstSlot.equals(b.firstSlot) && java.util.Arrays.equals(a.tabCounts, b.tabCounts);
	}

	/** The first slot the item occupied when observed, or -1. */
	public int slotOf(int itemId)
	{
		return firstSlot.getOrDefault(itemId, -1);
	}

	public Map<Integer, Integer> getFirstSlots()
	{
		return firstSlot;
	}

	/** Tab counts 1..9, or null. */
	public int[] getTabCounts()
	{
		return tabCounts == null ? null : tabCounts.clone();
	}

	/**
	 * The bank tab (1-9, or 0 for the main tab) an item was in when observed, or
	 * -1 when unknown. Tabs occupy consecutive slot ranges in order (tab 1 from
	 * slot 0, then tab 2, ...), and slots after the nine counts are the main tab:
	 * the layout the game's bank build script (BankMainBuild, as shipped in
	 * RuneLite master @ e98399f931c6) applies with varbits BANK_TAB_1..9.
	 */
	public int tabOf(int itemId)
	{
		int slot = slotOf(itemId);
		if (slot < 0 || tabCounts == null)
		{
			return -1;
		}
		int start = 0;
		for (int t = 0; t < TABS; t++)
		{
			if (tabCounts[t] < 0)
			{
				return -1;
			}
			if (slot < start + tabCounts[t])
			{
				return t + 1;
			}
			start += tabCounts[t];
		}
		return 0;
	}

	public Kind getKind()
	{
		return kind;
	}

	public Map<Integer, Integer> getItems()
	{
		return items;
	}

	public long getObservedAt()
	{
		return observedAt;
	}

	public int count(int itemId)
	{
		return items.getOrDefault(itemId, 0);
	}
}
