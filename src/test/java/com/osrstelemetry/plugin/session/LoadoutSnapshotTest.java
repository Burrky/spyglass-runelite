package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.osrstelemetry.plugin.model.StorageState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Pure,
 * RuneLite/Swing/Client-independent coverage of LoadoutSnapshot's
 * data-shape guarantees: defensive copying, unmodifiability, the
 * unavailable() factory, and the fixed inventory slot count. Plain
 * JUnit, no Mockito, matching this project's established test style
 * (see LootTrackerIndexTest).
 */
public class LoadoutSnapshotTest
{
	private static StorageState.StorageItem item(int slot, int itemId, String name, int quantity)
	{
		return new StorageState.StorageItem(slot, itemId, name, quantity, null);
	}

	private static StorageState.StorageItem emptySlot(int slot)
	{
		return new StorageState.StorageItem(slot, -1, null, 0, null);
	}

	private static List<StorageState.StorageItem> twentyEightEmptySlots()
	{
		List<StorageState.StorageItem> slots = new ArrayList<>(LoadoutSnapshot.INVENTORY_SIZE);
		for (int i = 0; i < LoadoutSnapshot.INVENTORY_SIZE; i++)
		{
			slots.add(emptySlot(i));
		}
		return slots;
	}

	@Test
	public void inventorySize_isTwentyEight()
	{
		assertEquals(28, LoadoutSnapshot.INVENTORY_SIZE);
	}

	@Test
	public void constructor_defensivelyCopiesInputLists_laterMutationOfSourceNotReflected()
	{
		List<StorageState.StorageItem> inventory = new ArrayList<>(twentyEightEmptySlots());
		List<StorageState.StorageItem> equipment = new ArrayList<>(Collections.singletonList(item(3, 1127, "Rune platebody", 1)));

		LoadoutSnapshot snapshot = new LoadoutSnapshot(inventory, equipment, "2026-01-01T10:00:00Z", LoadoutProvenance.PRE_START);

		// Mutate the caller's own lists after construction.
		inventory.clear();
		equipment.add(item(4, 1, "extra", 1));

		assertEquals("snapshot must be unaffected by later mutation of the source inventory list",
			28, snapshot.getInventory().size());
		assertEquals("snapshot must be unaffected by later mutation of the source equipment list",
			1, snapshot.getEquipment().size());
	}

	@Test
	public void getters_returnUnmodifiableLists()
	{
		LoadoutSnapshot snapshot = new LoadoutSnapshot(
			twentyEightEmptySlots(), Collections.emptyList(), "2026-01-01T10:00:00Z", LoadoutProvenance.PRE_START);

		try
		{
			snapshot.getInventory().add(item(0, 1, "x", 1));
			fail("inventory list returned by getInventory() must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// expected
		}

		try
		{
			snapshot.getEquipment().add(item(0, 1, "x", 1));
			fail("equipment list returned by getEquipment() must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// expected
		}
	}

	@Test
	public void unavailable_hasEmptyListsNeverNull_nullCapturedAt_unavailableProvenance()
	{
		LoadoutSnapshot snapshot = LoadoutSnapshot.unavailable();

		assertTrue(snapshot.getInventory().isEmpty());
		assertTrue(snapshot.getEquipment().isEmpty());
		assertNull(snapshot.getCapturedAt());
		assertEquals(LoadoutProvenance.UNAVAILABLE, snapshot.getProvenance());
	}

	@Test
	public void preservesSlotIndexAndEmptySlotConvention_itemIdNegativeOne()
	{
		List<StorageState.StorageItem> equipment = Arrays.asList(
			item(0, 1163, "Bronze full helm", 1),
			emptySlot(1),
			emptySlot(2)
		);

		LoadoutSnapshot snapshot = new LoadoutSnapshot(twentyEightEmptySlots(), equipment, "2026-01-01T10:00:00Z", LoadoutProvenance.FALLBACK_AT_OR_AFTER_START);

		assertEquals(3, snapshot.getEquipment().size());
		assertEquals(-1, snapshot.getEquipment().get(1).getItemId());
		assertEquals(1163, snapshot.getEquipment().get(0).getItemId());
		assertEquals(0, snapshot.getEquipment().get(0).getSlot());
	}
}
