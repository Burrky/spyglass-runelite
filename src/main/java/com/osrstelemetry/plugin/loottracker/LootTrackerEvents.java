package com.osrstelemetry.plugin.loottracker;

import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredRewardSource;
import com.osrstelemetry.plugin.events.EventPayloads;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONE conversion from a canonical loot telemetry event to a
 * {@link LootTrackerRecord}, shared by the live listener and the
 * events.jsonl rebuild so both produce identical records (same eventId,
 * same key, same items) and {@link LootTrackerIndex}'s eventId dedup can
 * collapse a live and a rebuilt copy of one event.
 */
final class LootTrackerEvents
{
	private LootTrackerEvents()
	{
	}

	static LootTrackerRecord fromServerNpcLoot(String eventId, EventPayloads.ServerNpcLoot loot, Instant observedAt)
	{
		// Older events recorded an independent reward as NPC loot ("Konar quo Maten" for the Brimstone chest): read back under
		// the reward's own source, so the Loot Tracker keeps one record per claim under the right name.
		StructuredRewardSource independent = StructuredActivityRegistry.defaults()
			.rewardForServerNpcLoot(loot.getSourceId() == null ? -1 : loot.getSourceId(), loot.getSourceName());
		if (independent != null)
		{
			List<LootTrackerItem> moved = new ArrayList<>();
			if (loot.getItems() != null)
			{
				for (EventPayloads.ServerNpcLootItem item : loot.getItems())
				{
					moved.add(new LootTrackerItem(item.getItemId(), item.getItemName(), item.getQuantity()));
				}
			}
			return moved.isEmpty() ? null : new LootTrackerRecord(eventId, independent.getDisplayName(), null, observedAt, moved,
				LootTrackerIndex.structuredSourceKey(independent.getKey()));
		}
		List<LootTrackerItem> items = new ArrayList<>();
		if (loot.getItems() != null)
		{
			for (EventPayloads.ServerNpcLootItem item : loot.getItems())
			{
				items.add(new LootTrackerItem(item.getItemId(), item.getItemName(), item.getQuantity()));
			}
		}
		return new LootTrackerRecord(eventId, loot.getSourceName(), loot.getSourceId(), observedAt, items);
	}

	/**
	 * A structured reward, under its canonical reward source. The source is
	 * the payload's canonical key or -- for an event written before keys
	 * existed -- a declared alias of its name; anything the registry does not
	 * declare is skipped (null), never guessed into some source.
	 */
	static LootTrackerRecord fromStructuredReward(String eventId, EventPayloads.StructuredReward reward, Instant observedAt)
	{
		StructuredRewardSource source = StructuredActivityRegistry.defaults()
			.rewardSource(reward.getSourceKey() != null ? reward.getSourceKey() : reward.getSourceName());
		if (source == null)
		{
			return null;
		}
		List<LootTrackerItem> items = new ArrayList<>();
		if (reward.getItems() != null)
		{
			for (EventPayloads.StructuredRewardItem item : reward.getItems())
			{
				items.add(new LootTrackerItem(item.getItemId(), item.getItemName(), item.getQuantity()));
			}
		}
		if (items.isEmpty())
		{
			return null;
		}
		return new LootTrackerRecord(eventId, source.getDisplayName(), null, observedAt, items,
			LootTrackerIndex.structuredSourceKey(source.getKey()));
	}
}
