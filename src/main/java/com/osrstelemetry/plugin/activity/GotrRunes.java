package com.osrstelemetry.plugin.activity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Guardians of the Rift rune production recipe. Input: guardian essence
 * (gameval ItemID.GOTR_GUARDIAN_ESSENCE), which exists only inside the
 * minigame -- consuming it is itself proof of a GotR altar craft. Output:
 * every (unnoted) rune item id, not just the twelve GotR altar runes, so a
 * legitimately crafted rune is never dropped for being unexpected; the
 * essence-consumption + Runecraft-XP gate is what keeps everything else out.
 */
public final class GotrRunes
{
	private GotrRunes()
	{
	}

	public static final Set<Integer> RUNE_ITEM_IDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		ItemID.AIRRUNE, ItemID.WATERRUNE, ItemID.EARTHRUNE, ItemID.FIRERUNE,
		ItemID.MINDRUNE, ItemID.BODYRUNE, ItemID.COSMICRUNE, ItemID.CHAOSRUNE,
		ItemID.NATURERUNE, ItemID.LAWRUNE, ItemID.DEATHRUNE, ItemID.BLOODRUNE,
		ItemID.SOULRUNE, ItemID.WRATHRUNE, ItemID.ASTRALRUNE,
		ItemID.MISTRUNE, ItemID.DUSTRUNE, ItemID.MUDRUNE, ItemID.SMOKERUNE, ItemID.STEAMRUNE, ItemID.LAVARUNE,
		ItemID.SUNFIRERUNE, ItemID.AETHERRUNE)));

	public static final ProductionRecipe RECIPE = new ProductionRecipe(
		Collections.singleton(ItemID.GOTR_GUARDIAN_ESSENCE), RUNE_ITEM_IDS, "runecraft");

	/**
	 * Rune pouch / divine rune pouch slots (resolved RuneLite 1.13.1 gameval):
	 * slot i holds rune TYPE_i (an index into the game's RUNEPOUCH_RUNE enum)
	 * x QUANTITY_i. Six slots are defined; unused slots read 0.
	 */
	static final int[] RUNE_POUCH_TYPES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
	static final int[] RUNE_POUCH_QUANTITIES = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};

	public static boolean isRunePouchVarbit(int varbitId)
	{
		for (int i = 0; i < RUNE_POUCH_TYPES.length; i++)
		{
			if (RUNE_POUCH_TYPES[i] == varbitId || RUNE_POUCH_QUANTITIES[i] == varbitId)
			{
				return true;
			}
		}
		return false;
	}

	/** The pouch's runes (item id -> quantity) from its slot varbits; unknown slot types are skipped. */
	public static Map<Integer, Long> readRunePouch(IntUnaryOperator varbit, IntUnaryOperator typeToItem)
	{
		Map<Integer, Long> out = new HashMap<>();
		for (int i = 0; i < RUNE_POUCH_TYPES.length; i++)
		{
			int type = varbit.applyAsInt(RUNE_POUCH_TYPES[i]);
			int qty = varbit.applyAsInt(RUNE_POUCH_QUANTITIES[i]);
			if (type <= 0 || qty <= 0)
			{
				continue;
			}
			int item = typeToItem.applyAsInt(type);
			if (item > 0)
			{
				out.merge(item, (long) qty, Long::sum);
			}
		}
		return out;
	}
}
