package com.osrstelemetry.plugin.prep;

import java.util.Arrays;
import java.util.Objects;

/**
 * One thing a consumer needs the player to carry (canonical §8.2). A flat list
 * of these is the whole contract: "any of these ids" covers variants, and two
 * different things are two needs. Not a requirement grammar.
 */
public final class ItemNeed
{
	public enum Necessity
	{
		REQUIRED,
		RECOMMENDED
	}

	public enum SatisfiedBy
	{
		INVENTORY_ONLY,
		INVENTORY_OR_WORN,
		/** A rune: inventory, a carried rune pouch, combination runes and worn rune providers all count. */
		INVENTORY_OR_RUNE_POUCH,
		/** Only worn counts (its effect needs it equipped); carried in the inventory is reported, not satisfied. */
		WORN_ONLY
	}

	private final String key;
	private final String label;
	private final String section;
	private final int[] acceptableItemIds;
	private final int quantity;
	private final Necessity necessity;
	private final SatisfiedBy satisfiedBy;
	private final String reason;
	private String searchText;
	/** Per acceptable id, how many units one item provides (charges); null = 1 each. {@link #COVERS_ANY} = one item covers any quantity. */
	private int[] units;
	private String unitNoun;
	private String reachableLabel;
	private long reachableUnits;
	private boolean stackable = true;
	private String wornSlot;

	/** Units value: one such item covers any quantity (unlimited, or charges Spyglass cannot observe). */
	public static final int COVERS_ANY = -1;

	/**
	 * @param section    a display group ("Supplies", "Tools", "Travel")
	 * @param quantity   how many; 0 = at least one, quantity irrelevant (tools, unlimited teleports)
	 * @param acceptableItemIds any of these satisfies the need, best first
	 */
	public ItemNeed(String key, String label, String section, int[] acceptableItemIds, int quantity, Necessity necessity, SatisfiedBy satisfiedBy,
		String reason)
	{
		this.key = Objects.requireNonNull(key);
		this.label = Objects.requireNonNull(label);
		this.section = Objects.requireNonNull(section);
		this.acceptableItemIds = acceptableItemIds.clone();
		this.quantity = Math.max(0, quantity);
		this.necessity = Objects.requireNonNull(necessity);
		this.satisfiedBy = Objects.requireNonNull(satisfiedBy);
		this.reason = reason == null ? "" : reason;
		if (acceptableItemIds.length == 0)
		{
			throw new IllegalArgumentException("A need names at least one item: " + key);
		}
	}

	public String getKey()
	{
		return key;
	}

	public String getLabel()
	{
		return label;
	}

	public String getSection()
	{
		return section;
	}

	public int[] getAcceptableItemIds()
	{
		return acceptableItemIds.clone();
	}

	public boolean accepts(int itemId)
	{
		for (int id : acceptableItemIds)
		{
			if (id == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/** At least this many (1 when the quantity is irrelevant). */
	public int required()
	{
		return Math.max(1, quantity);
	}

	public int getQuantity()
	{
		return quantity;
	}

	public Necessity getNecessity()
	{
		return necessity;
	}

	public SatisfiedBy getSatisfiedBy()
	{
		return satisfiedBy;
	}

	public String getReason()
	{
		return reason;
	}

	/** A short plain name for the item, used in explanations (defaults to the label). */
	public String getSearchText()
	{
		return searchText == null ? label : searchText;
	}

	public ItemNeed withSearchText(String text)
	{
		this.searchText = text;
		return this;
	}

	/**
	 * Counts this need in units rather than items: {@code perItem[i]} is how
	 * many units one of {@code acceptableItemIds[i]} provides (a necklace(4)
	 * gives 4 charges), or {@link #COVERS_ANY}. The quantity is then in units,
	 * shared by every use in the plan: two teleports need two charges, not two
	 * necklaces each assumed to be fresh.
	 */
	public ItemNeed withUnits(int[] perItem, String noun)
	{
		if (perItem.length != acceptableItemIds.length)
		{
			throw new IllegalArgumentException("units must match the acceptable ids: " + key);
		}
		this.units = perItem.clone();
		this.unitNoun = noun;
		return this;
	}

	/** Units one item of {@code itemId} provides (1 unless {@link #withUnits}); {@link #COVERS_ANY} covers any quantity. */
	public int unitsOf(int itemId)
	{
		if (units == null)
		{
			return 1;
		}
		for (int i = 0; i < acceptableItemIds.length; i++)
		{
			if (acceptableItemIds[i] == itemId)
			{
				return units[i];
			}
		}
		return 0;
	}

	public boolean isCountedInUnits()
	{
		return units != null;
	}

	/**
	 * A store the player can take this from where it is used (Farming: the tool
	 * leprechaun beside the patches), holding {@code units}. Covers the need
	 * without carrying it, and is reported as such, never as carried.
	 */
	public ItemNeed withReachableStore(String label, long units)
	{
		this.reachableLabel = label;
		this.reachableUnits = Math.max(0, units);
		return this;
	}

	public String getReachableLabel()
	{
		return reachableLabel;
	}

	public long getReachableUnits()
	{
		return reachableLabel == null ? 0 : reachableUnits;
	}

	/** Each item takes its own inventory slot (saplings, compost buckets, tools); stackables take one in all. */
	public ItemNeed unstackable()
	{
		this.stackable = false;
		return this;
	}

	public boolean isStackable()
	{
		return stackable;
	}

	/** The equipment slot this must be worn in ("neck"), for slot-conflict checks. */
	public ItemNeed wornIn(String slot)
	{
		this.wornSlot = slot;
		return this;
	}

	public String getWornSlot()
	{
		return wornSlot;
	}

	/** The quantity as players read it: "×6", "2 charges", or "" when any one will do. */
	public String quantityText()
	{
		if (quantity == 0)
		{
			return "";
		}
		if (units != null && unitNoun != null)
		{
			return quantity + " " + unitNoun + (quantity == 1 ? "" : "s");
		}
		return quantity > 1 ? "\u00d7" + quantity : "";
	}

	@Override
	public String toString()
	{
		return key + Arrays.toString(acceptableItemIds) + "x" + quantity;
	}
}
