package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.session.LoadoutSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * A standalone, minimally-invasive collector feeding {@link LoadoutArchive}
 * -- see that class's own javadoc for why a short in-memory archive is
 * needed at all: ContainerCollector's own inventory.json/equipment.json
 * are CURRENT-STATE-ONLY overwritten pointers with no history, and its
 * inventory representation is deliberately sparse
 * (ContainerCollector.buildSparseItems()) rather than slot-exact -- the
 * right choice for its own purpose, but not for History's slot-exact
 * starting/ending loadout requirement.
 *
 * DELIBERATELY INDEPENDENT of ContainerCollector: this class touches
 * zero lines of that already-hardened file. It subscribes to the exact
 * same ItemContainerChanged event completely independently -- RuneLite's
 * EventBus supports any number of independent subscribers to the same
 * event type, so there is no coordination needed between the two, and
 * no risk of this collector affecting ContainerCollector's own
 * bank-snapshot/dedup/shutdown-durability machinery in any way.
 *
 * SLOT-EXACT, UNLIKE ContainerCollector.buildSparseItems(): builds a
 * COMPLETE {@link LoadoutSnapshot#INVENTORY_SIZE}-slot inventory list
 * (explicit empty-slot entries, itemId=-1) the same way
 * ContainerCollector.buildEquipmentItems() already does for equipment
 * -- see buildCompleteInventoryItems() below, the same technique
 * applied to inventory. buildCompleteEquipmentItems() below is a
 * deliberate re-implementation of that same equipment technique rather
 * than a shared call, since ContainerCollector's own method is
 * private on a file this collector deliberately does not touch.
 *
 * WHEN A PAIR IS RECORDED: inventory and equipment are both
 * continuously observable (see StorageState's own "continuouslyObservable"
 * contract), so on ANY change to either container this collector
 * re-reads BOTH current containers directly via
 * client.getItemContainer(...) -- the same technique
 * ContainerCollector.captureInitialContinuousState() already uses to
 * seed both independently of any specific change event -- and archives
 * them together as one complete pair. If either container is not yet
 * available (e.g. a change fires before the other has ever been
 * observed this login), nothing is recorded for that observation --
 * never a partial/incomplete pair, which would silently corrupt
 * LoadoutArchive's "every entry is complete" invariant.
 */
@Slf4j
public class LoadoutArchiveCollector
{
	private static final Map<Integer, String> EQUIPMENT_SLOT_NAMES = new HashMap<>();

	static
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			EQUIPMENT_SLOT_NAMES.put(slot.getSlotIdx(), slot.name());
		}
	}

	private final Client client;
	private final ItemManager itemManager;
	private final OsrsTelemetryConfig config;
	private final LoadoutArchive archive;

	@Inject
	public LoadoutArchiveCollector(Client client, ItemManager itemManager, OsrsTelemetryConfig config, LoadoutArchive archive)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
		this.archive = archive;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (containerId != InventoryID.INV && containerId != InventoryID.WORN)
		{
			return;
		}
		captureIfBothAvailable();
	}

	/**
	 * Called once on login (see OsrsTelemetryPlugin.handleLogin()) so
	 * the archive can start holding real data immediately -- the same
	 * "seed immediately, don't wait for an incidental event" reasoning
	 * ContainerCollector.captureInitialContinuousState() already
	 * follows for inventory.json/equipment.json.
	 */
	public void captureInitialState()
	{
		captureIfBothAvailable();
	}

	private void captureIfBothAvailable()
	{
		if (!config.collectInventoryEquipment())
		{
			return;
		}
		ItemContainer inventoryContainer = client.getItemContainer(InventoryID.INV);
		ItemContainer equipmentContainer = client.getItemContainer(InventoryID.WORN);
		if (inventoryContainer == null || equipmentContainer == null)
		{
			return;
		}
		List<StorageState.StorageItem> inventory = buildCompleteInventoryItems(inventoryContainer);
		List<StorageState.StorageItem> equipment = buildCompleteEquipmentItems(equipmentContainer);
		archive.record(Instant.now(), inventory, equipment);
	}

	/**
	 * Slot-exact -- see class javadoc. Mirrors
	 * ContainerCollector.buildEquipmentItems()'s empty-slot convention
	 * (itemId=-1, name=null, quantity=0), applied to inventory's fixed
	 * LoadoutSnapshot.INVENTORY_SIZE slots instead of the equipment
	 * slot table.
	 */
	private List<StorageState.StorageItem> buildCompleteInventoryItems(ItemContainer container)
	{
		List<StorageState.StorageItem> items = new ArrayList<>(LoadoutSnapshot.INVENTORY_SIZE);
		Item[] rawItems = container.getItems();
		for (int slot = 0; slot < LoadoutSnapshot.INVENTORY_SIZE; slot++)
		{
			Item item = slot < rawItems.length ? rawItems[slot] : null;
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				items.add(new StorageState.StorageItem(slot, -1, null, 0, null));
			}
			else
			{
				String name = itemManager.getItemComposition(item.getId()).getName();
				items.add(new StorageState.StorageItem(slot, item.getId(), name, item.getQuantity(), null));
			}
		}
		return items;
	}

	private List<StorageState.StorageItem> buildCompleteEquipmentItems(ItemContainer container)
	{
		List<StorageState.StorageItem> items = new ArrayList<>(EQUIPMENT_SLOT_NAMES.size());
		Item[] rawItems = container.getItems();
		for (int slot = 0; slot < EQUIPMENT_SLOT_NAMES.size(); slot++)
		{
			Item item = slot < rawItems.length ? rawItems[slot] : null;
			String equipmentSlotName = EQUIPMENT_SLOT_NAMES.get(slot);
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				items.add(new StorageState.StorageItem(slot, -1, null, 0, equipmentSlotName));
			}
			else
			{
				String name = itemManager.getItemComposition(item.getId()).getName();
				items.add(new StorageState.StorageItem(slot, item.getId(), name, item.getQuantity(), equipmentSlotName));
			}
		}
		return items;
	}

	/** See OsrsTelemetryPlugin.handleLogin(). */
	public void resetForAccountSwitch()
	{
		archive.resetForAccountSwitch();
	}
}
