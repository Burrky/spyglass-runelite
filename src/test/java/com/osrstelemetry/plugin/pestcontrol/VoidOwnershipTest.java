package com.osrstelemetry.plugin.pestcontrol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Void ownership from the current inventory, worn equipment and bank only (bounded, current state). */
public class VoidOwnershipTest
{
	private static Map<Integer, Long> of(int id)
	{
		Map<Integer, Long> m = new HashMap<>();
		m.put(id, 1L);
		return m;
	}

	@Test
	public void union_ofInventoryEquipmentAndBank()
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.INVENTORY, of(ItemID.PEST_VOID_KNIGHT_TOP));
		o.update(VoidOwnership.Container.EQUIPMENT, of(ItemID.GAME_PEST_MELEE_HELM));
		assertFalse(o.getSnapshot().isBankKnown());
		o.update(VoidOwnership.Container.BANK, of(ItemID.PEST_VOID_KNIGHT_MACE));
		VoidOwnership.Snapshot s = o.getSnapshot();
		assertTrue(s.isBankKnown());
		assertTrue(s.owns(VoidPiece.TOP));
		assertTrue(s.owns(VoidPiece.MELEE_HELM));
		assertTrue(s.owns(VoidPiece.MACE));
	}

	@Test
	public void movingAPiece_isCurrentState_notHistory()
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.INVENTORY, of(ItemID.PEST_VOID_KNIGHT_GLOVES));
		o.update(VoidOwnership.Container.INVENTORY, Collections.emptyMap());
		assertFalse("dropped / sold since: no longer owned", o.getSnapshot().owns(VoidPiece.GLOVES));
		o.update(VoidOwnership.Container.EQUIPMENT, of(ItemID.PEST_VOID_KNIGHT_GLOVES));
		assertTrue("equipped", o.getSnapshot().owns(VoidPiece.GLOVES));
	}

	@Test
	public void reset_forgetsEverything_includingTheBank()
	{
		VoidOwnership o = new VoidOwnership();
		o.update(VoidOwnership.Container.BANK, of(ItemID.PEST_VOID_KNIGHT_ROBES));
		o.reset();
		assertFalse(o.getSnapshot().isBankKnown());
		assertFalse(o.getSnapshot().owns(VoidPiece.ROBE));
	}
}
