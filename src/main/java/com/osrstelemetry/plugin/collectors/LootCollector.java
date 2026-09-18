package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * The event this collector emits was renamed LOOT_DROP ->
 * NPC_LOOT_ATTRIBUTED after a read-only audit of the exact RuneLite
 * source path that produces it, summarized here.
 *
 * SOURCE (unchanged): net.runelite.client.events.NpcLootReceived, the
 * same event RuneLite's own core Loot Tracker plugin subscribes to
 * (LootTrackerPlugin#onNpcLootReceived). Produced by
 * net.runelite.client.game.LootManager.
 *
 * WHAT THE AUDIT CONFIRMED (direct read of LootManager.java's current
 * source, not inferred): LootManager.onItemSpawned() adds EVERY
 * ItemSpawned observation to a per-scene-tile map unconditionally —
 * TileItem#getOwnership() (OWNERSHIP_SELF/OWNERSHIP_OTHER/
 * OWNERSHIP_GROUP/OWNERSHIP_NONE) and TileItem#isPrivate() are never
 * called anywhere in that file. When an NPC despawns,
 * getDropLocations(npc) computes the tile(s) its loot should appear
 * on (npc.getWorldArea() for ordinary NPCs; special-cased offsets for
 * bosses like Vorkath/Kraken), and getItemStacksFromAreas() then just
 * reads whatever TileItem(s) are sitting there — converted to
 * ItemStack by id+quantity only, with itemSpawns/killPoints cleared
 * every single onGameTick(). This is pure tile + same-tick spatial/
 * temporal correlation to a despawn — NOT ownership-verified. It can
 * genuinely include another visible player's drop that happens to
 * land on the same tile in the same tick, or an item the game itself
 * marks OWNERSHIP_OTHER/OWNERSHIP_GROUP/OWNERSHIP_NONE, since none of
 * that is ever read. It is also despawn-correlated, not strictly
 * death-correlated: for certain monsters that "die" with >0 HP
 * (gargoyles, rockslugs, lizards, zygomites — code comment: "these
 * monsters die with >0 hp, so we just look for coincident item spawn
 * with despawn"), attribution leans on despawn timing rather than a
 * true death signal at all.
 *
 * CONCLUSION: NpcLootReceived proves "RuneLite's LootManager
 * attributed an observed item stack to an NPC (by tile+tick
 * correlation to its despawn)." It does NOT prove the item was
 * actually awarded to or owned by the local player. "LOOT_DROP"
 * (and "LootDrop") read as "my loot" — that's the overclaim this
 * rename corrects. Nothing about the actual filtering/collection
 * logic below changed, only the event/payload name.
 *
 * ServerNpcLoot (net.runelite.client.events.ServerNpcLoot, "NPC loot
 * received from the in-game loot tracker") was investigated as a
 * possible stronger-ownership alternative and deliberately NOT
 * switched to: RuneLite's own core LootTrackerPlugin has to
 * explicitly filter pickpocket loot back OUT of it (a tick-based
 * `ignorePickpocketLoot` guard, comment: "server sends npc loot for
 * pickpockets, ignore it") — since pickpocketing only ever rewards
 * the local player, that filtering need is itself real evidence
 * ServerNpcLoot IS scoped to the local player's own loot-granting
 * events specifically (materially stronger ownership semantics than
 * NpcLootReceived). But it carries only NPCComposition (no specific
 * NPC instance/location data) and conflates a non-death loot
 * mechanic (pickpocketing) unless filtered — switching would trade
 * this event's tile/location and "any observed attribution" coverage
 * for a narrower, differently-shaped signal, not simply strengthen
 * it. Left as future work for a possible separate, genuinely
 * player-owned-loot event, rather than combining two semantically
 * different signals under one event type.
 *
 * PlayerLootReceived (PvP loot, e.g. Wilderness kills) exists as a
 * sibling event but is deliberately out of scope: this project targets
 * Group Ironman accounts, for whom PvP loot isn't a normal data
 * source, and adding it now would be scope creep.
 */
public class LootCollector
{
	private final Client client;
	private final ItemManager itemManager;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	@Inject
	public LootCollector(Client client, ItemManager itemManager, EventLedger eventLedger, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!config.collectLoot())
		{
			return;
		}

		NPC npc = event.getNpc();
		String sourceName = npc.getName();
		int sourceId = npc.getId();

		for (ItemStack itemStack : event.getItems())
		{
			String itemName = itemManager.getItemComposition(itemStack.getId()).getName();
			eventLedger.append(
				client.getAccountHash(),
				EventType.NPC_LOOT_ATTRIBUTED,
				buildPayload("NPC", sourceName, sourceId, itemStack.getId(), itemName, itemStack.getQuantity())
			);
		}
	}

	/**
	 * Pure, Client/EventLedger/RuneLite-API-independent core
	 * of the NPC_LOOT_ATTRIBUTED payload construction — factored out
	 * purely so LootCollectorTest can exercise it without mocking
	 * RuneLite's NPC/Client/ItemManager/NpcLootReceived types (this
	 * project has no Mockito dependency — see BankSnapshotBaselineTest's
	 * javadoc for the same constraint noted before). This is a pass-
	 * through, not a decision: every value already read off RuneLite's
	 * own objects (sourceType/sourceName/sourceId/itemId/itemName/
	 * quantity) is stored on the payload exactly as given, never
	 * defaulted, normalized, or fabricated when something upstream
	 * (e.g. npc.getName()) happens to be null — that's the specific
	 * "do not fabricate missing values" contract LootCollectorTest
	 * pins down.
	 *
	 * No behavior changed by this extraction: onNpcLootReceived() calls
	 * this with the exact same arguments it previously passed directly
	 * to `new EventPayloads.NpcLootAttributed(...)`.
	 */
	static EventPayloads.NpcLootAttributed buildPayload(
		String sourceType,
		String sourceName,
		int sourceId,
		int itemId,
		String itemName,
		int quantity)
	{
		return new EventPayloads.NpcLootAttributed(sourceType, sourceName, sourceId, itemId, itemName, quantity);
	}
}
