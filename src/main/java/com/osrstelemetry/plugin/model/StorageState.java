package com.osrstelemetry.plugin.model;

import java.util.List;
import lombok.Data;

/**
 * One document per storage container. storageId is a free string
 * ("bank", "inventory", "equipment", "seed_vault", "group_storage",
 * ...) so a newly-discovered container type never requires a schema
 * change.
 *
 * Equipment items always include slot position and a semantic slot
 * name, and empty equipment slots are
 * included explicitly (itemId -1) rather than filtered out — an
 * equipment container is fully continuously observable, so "slot 3 is
 * absent from this list" and "slot 3 is confirmed empty" must not be
 * collapsed into the same representation. Inventory items also carry
 * their slot index (cheap to keep, useful for reconstructing layout)
 * but empty inventory slots are still omitted, since an inventory slot
 * being empty isn't an identity the way an equipment slot is.
 *
 * lastObservedAt is mandatory and load-bearing: for containers that
 * only populate while their interface is open (bank, seed vault,
 * group storage), a stale lastObservedAt is the ONLY thing telling a
 * caller this data is not live. Never omit it, never treat an old
 * snapshot as current.
 */
@Data
public class StorageState
{
	@Data
	public static class StorageItem
	{
		private final int slot;
		private final int itemId;
		private final String name;
		private final int quantity;
		/** Only set for the equipment container (e.g. "WEAPON", "HEAD"). Null everywhere else. */
		private final String equipmentSlot;
	}

	private final String storageId;
	private List<StorageItem> items;
	private String lastObservedAt;

	/**
	 * Set only for storageId="bank": the id of the immutable snapshot
	 * file (bank_snapshots/{latestSnapshotId}.json) that corresponds
	 * to this observation, once the debounce window has settled. May
	 * lag slightly behind lastObservedAt/items — see ContainerCollector.
	 */
	private String latestSnapshotId;

	/**
	 * True only for containers RuneLite can see continuously
	 * (inventory, equipment). False for anything gated behind opening
	 * an interface (bank, seed vault, group storage). Lets the read
	 * API distinguish "live" from "as of last look" without the
	 * caller needing to know per-storageId semantics.
	 */
	private boolean continuouslyObservable;
}
