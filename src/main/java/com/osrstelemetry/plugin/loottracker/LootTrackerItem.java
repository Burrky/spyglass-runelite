package com.osrstelemetry.plugin.loottracker;

/**
 * One item stack within one atomic {@code SERVER_NPC_LOOT} drop record -- the Loot
 * Tracker's own equivalent of {@code EventPayloads.ServerNpcLootItem},
 * kept as a SEPARATE, package-independent type rather than reusing that
 * events-package payload class directly. This mirrors the existing
 * project convention of a durable telemetry payload (events package)
 * being distinct from the pure UI/domain shape derived from it (see
 * CurrentSessionSnapshot.LootEntry vs SessionAggregates.LootItemAggregate)
 * -- the Loot Tracker's own domain model must not take on a compile-time
 * dependency on the telemetry envelope shape, so a future change to one
 * never silently breaks the other.
 *
 * Immutable value type -- plain equals/hashCode by field, no Lombok
 * dependency needed for a type this small.
 */
public final class LootTrackerItem
{
	private final int itemId;
	private final String itemName;
	private final long quantity;

	public LootTrackerItem(int itemId, String itemName, long quantity)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.quantity = quantity;
	}

	public int getItemId()
	{
		return itemId;
	}

	public String getItemName()
	{
		return itemName;
	}

	public long getQuantity()
	{
		return quantity;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof LootTrackerItem))
		{
			return false;
		}
		LootTrackerItem other = (LootTrackerItem) o;
		return itemId == other.itemId
			&& quantity == other.quantity
			&& java.util.Objects.equals(itemName, other.itemName);
	}

	@Override
	public int hashCode()
	{
		return java.util.Objects.hash(itemId, itemName, quantity);
	}

	@Override
	public String toString()
	{
		return "LootTrackerItem{itemId=" + itemId + ", itemName=" + itemName + ", quantity=" + quantity + "}";
	}
}
