package com.osrstelemetry.plugin.skills.runecraft;

import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The Runecraft essence pouches and their NATIVE state (RuneLite game values;
 * the same client scripts RuneLite's own Runecraft plugin reads -- studied,
 * never used at runtime):
 * <ul>
 * <li>exact contents: one varbit per pouch kind ({@code amountVarbit});</li>
 * <li>degradation: a varp (varbit for the colossal pouch) whose value maps,
 * through the game's own script tables (3670 / 3671 / 3695 / 4592), to the
 * pouch's CURRENT capacity -- so "holds 5 of 9" is exact, never a guess;</li>
 * <li>the colossal pouch's capacity also scales with the real Runecraft level
 * (8 / 16 / 27 / 40 at below 50 / 50 / 75 / 85).</li>
 * </ul>
 * No uses-left countdown is ever claimed: the game randomises wear per
 * essence (0-3 durability), so only the current capacity is knowable.
 */
public enum RunecraftPouch
{
	SMALL("Small pouch", ItemID.RCU_POUCH_SMALL, -1, VarbitID.SMALL_ESSENCE_POUCH, -1, false, new int[]{}, 3),
	MEDIUM("Medium pouch", ItemID.RCU_POUCH_MEDIUM, ItemID.RCU_POUCH_MEDIUM_DEGRADE, VarbitID.MEDIUM_ESSENCE_POUCH,
		VarPlayerID.RCU_POUCH_DEGRADATION_MED, false, new int[]{800, 0, 400, 3}, 6),
	LARGE("Large pouch", ItemID.RCU_POUCH_LARGE, ItemID.RCU_POUCH_LARGE_DEGRADE, VarbitID.LARGE_ESSENCE_POUCH,
		VarPlayerID.RCU_POUCH_DEGRADATION_LARGE, false, new int[]{1000, 0, 800, 3, 600, 5, 400, 7}, 9),
	GIANT("Giant pouch", ItemID.RCU_POUCH_GIANT, ItemID.RCU_POUCH_GIANT_DEGRADE, VarbitID.GIANT_ESSENCE_POUCH,
		VarPlayerID.RCU_POUCH_DEGRADATION_GIANT, false, new int[]{1200, 0, 1000, 3, 800, 5, 600, 6, 400, 7, 300, 8, 200, 9}, 12),
	COLOSSAL("Colossal pouch", ItemID.RCU_POUCH_COLOSSAL, ItemID.RCU_POUCH_COLOSSAL_DEGRADE, VarbitID.COLOSSAL_ESSENCE_POUCH,
		VarbitID.RCU_POUCH_DEGRADATION_COLOSSAL, true, new int[]{1020, 0, 1015, 5, 995, 10, 950, 15, 870, 20, 745, 25, 565, 30, 320, 35}, 40);

	private final String label;
	private final int itemId;
	private final int degradedItemId;
	private final int amountVarbit;
	private final int degradationVar;
	private final boolean degradationIsVarbit;
	private final int[] degradationLevels;
	private final int maxFill;

	RunecraftPouch(String label, int itemId, int degradedItemId, int amountVarbit, int degradationVar, boolean degradationIsVarbit,
		int[] degradationLevels, int maxFill)
	{
		this.label = label;
		this.itemId = itemId;
		this.degradedItemId = degradedItemId;
		this.amountVarbit = amountVarbit;
		this.degradationVar = degradationVar;
		this.degradationIsVarbit = degradationIsVarbit;
		this.degradationLevels = degradationLevels;
		this.maxFill = maxFill;
	}

	public String getLabel()
	{
		return label;
	}

	public int getItemId()
	{
		return itemId;
	}

	/** -1 when the pouch never degrades (small). */
	public int getDegradedItemId()
	{
		return degradedItemId;
	}

	public int getAmountVarbit()
	{
		return amountVarbit;
	}

	/** Varp id (varbit id for the colossal pouch) of its degradation; -1 for the small pouch. */
	public int getDegradationVar()
	{
		return degradationVar;
	}

	public boolean isDegradationVarbit()
	{
		return degradationIsVarbit;
	}

	public int getMaxFill()
	{
		return maxFill;
	}

	/**
	 * What the pouch holds at most right now (the game's own tables): its
	 * degradation reduces it; the colossal pouch also scales with the real
	 * Runecraft level (unknown level = the unscaled table).
	 */
	public int capacity(int degradation, int runecraftLevel)
	{
		int limit = maxFill;
		for (int i = 0; i < degradationLevels.length; i += 2)
		{
			if (degradation >= degradationLevels[i])
			{
				limit = degradationLevels[i + 1];
				break;
			}
		}
		if (this == COLOSSAL && limit > 0 && runecraftLevel > 0)
		{
			int scaledMax = runecraftLevel >= 85 ? 40 : runecraftLevel >= 75 ? 27 : runecraftLevel >= 50 ? 16 : 8;
			limit = Math.max(1, (limit * scaledMax) / 40);
		}
		return limit;
	}

	/** Whether this pouch's degradation lowered what it holds (the game's tables, not a countdown). */
	public boolean isDegraded(int degradation)
	{
		return capacity(degradation, 99) < capacity(0, 99);
	}

	public static RunecraftPouch forItem(int itemId)
	{
		for (RunecraftPouch p : values())
		{
			if (p.itemId == itemId || (p.degradedItemId > 0 && p.degradedItemId == itemId))
			{
				return p;
			}
		}
		return null;
	}

	public static RunecraftPouch forAmountVarbit(int varbitId)
	{
		for (RunecraftPouch p : values())
		{
			if (p.amountVarbit == varbitId)
			{
				return p;
			}
		}
		return null;
	}

	public static RunecraftPouch forDegradationVarp(int varpId)
	{
		for (RunecraftPouch p : values())
		{
			if (!p.degradationIsVarbit && p.degradationVar == varpId)
			{
				return p;
			}
		}
		return null;
	}

	public static RunecraftPouch forDegradationVarbit(int varbitId)
	{
		for (RunecraftPouch p : values())
		{
			if (p.degradationIsVarbit && p.degradationVar == varbitId)
			{
				return p;
			}
		}
		return null;
	}
}
