package com.osrstelemetry.plugin.account;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Pure decoders for the native account signals Phase F reads: rune pouch,
 * spellbook and achievement-diary completion. The adapter supplies varbit
 * values; nothing here touches the client.
 *
 * <h3>Provenance</h3>
 * <ul>
 *   <li>Rune pouch: {@code RUNE_POUCH_TYPE_n} / {@code RUNE_POUCH_QUANTITY_n}
 *   (n = 1..6) with the type mapped to an item through the game's
 *   {@code RUNEPOUCH_RUNE} enum, exactly as RuneLite's own rune pouch overlay
 *   does (RuneLite master @ e98399f931c6, BSD-2). Promoted from Spyglass's
 *   GotR / Runecraft readers.</li>
 *   <li>Diaries: the {@code *_DIARY_*_COMPLETE} varbits read as 1 = complete,
 *   as RuneLite's Daily Task Indicators plugin does (same revision, BSD-2).
 *   Karamja easy / medium / hard have no such varbit in gameval 1.13.1; for
 *   them the reward-claimed varbits {@code ATJUN_EASY/MED/HARD_REWARD} are read
 *   as Quest Helper does for every diary ({@code QuestVarbits}, master @
 *   5f79193, BSD-2), but one way only: 1 proves completion, anything else
 *   leaves the tier unknown (a completed diary may be unclaimed).</li>
 *   <li>Spellbook: {@code VarbitID.SPELLBOOK} (4070, gameval 1.13.1). Value
 *   mapping 0 = standard, 1 = ancient, 2 = lunar, 3 = arceuus, from two
 *   independent sources: Quest Helper's {@code requirements.util.Spellbook}
 *   enum (NORMAL 0, ANCIENT 1, LUNAR 2, ARCEUUS 3; Zoinkwiz/quest-helper master
 *   @ 5f79193, BSD-2), which its spellbook requirements compare against this
 *   varbit, and the game's own spellbook redraw script as shipped in RuneLite
 *   ({@code MagicSpellbookRedraw.rs2asm}, master @ e98399f931c6), which indexes
 *   the spellbook enum with this varbit and lays out exactly three non-default
 *   books for values 1, 2 and 3. Any other value is unknown, never a guessed
 *   book.</li>
 * </ul>
 */
public final class AccountReaders
{
	private AccountReaders()
	{
	}

	// -- rune pouch -------------------------------------------------------------------

	static final int[] RUNE_POUCH_TYPES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
	static final int[] RUNE_POUCH_QUANTITIES = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};

	/** Rune pouch items whose contents the pouch varbits describe (LMS / Bounty Hunter replacement copies excluded). */
	public static final int[] RUNE_POUCH_ITEMS = {ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH,
		ItemID.DIVINE_RUNE_POUCH_TROUVER};

	/** The pouch's runes (item id -> quantity); slots whose type does not map to an item are skipped. */
	public static Map<Integer, Integer> runePouch(IntUnaryOperator varbit, IntUnaryOperator typeToItem)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
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
				out.merge(item, qty, Integer::sum);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	public static boolean isRunePouch(int itemId)
	{
		for (int id : RUNE_POUCH_ITEMS)
		{
			if (id == itemId)
			{
				return true;
			}
		}
		return false;
	}

	// -- spellbook ----------------------------------------------------------------------

	public enum Spellbook
	{
		STANDARD,
		ANCIENT,
		LUNAR,
		ARCEUUS
	}

	/** Source-verified 2026-10-15 (class note). Any value outside 0-3 stays unknown. */
	public static final boolean SPELLBOOK_MAPPING_VERIFIED = true;

	/** 0 standard, 1 ancient, 2 lunar, 3 arceuus; anything else null (unknown). */
	public static Spellbook spellbookFromVarbit(int value)
	{
		switch (value)
		{
			case 0:
				return Spellbook.STANDARD;
			case 1:
				return Spellbook.ANCIENT;
			case 2:
				return Spellbook.LUNAR;
			case 3:
				return Spellbook.ARCEUUS;
			default:
				return null;
		}
	}

	// -- diaries -------------------------------------------------------------------------

	public enum DiaryRegion
	{
		ARDOUGNE, DESERT, FALADOR, FREMENNIK, KANDARIN, KARAMJA, KOUREND, LUMBRIDGE, MORYTANIA, VARROCK, WESTERN, WILDERNESS
	}

	public enum DiaryTier
	{
		EASY, MEDIUM, HARD, ELITE
	}

	/** "ARDOUGNE_ELITE" style key. */
	public static String diaryKey(DiaryRegion region, DiaryTier tier)
	{
		return region.name() + "_" + tier.name();
	}

	private static final Map<String, Integer> DIARY_VARBITS = new LinkedHashMap<>();
	/** Tiers whose signal proves completion only when set (1); otherwise the tier stays unknown. */
	private static final java.util.Set<String> PROOF_ONLY = new java.util.HashSet<>();

	static
	{
		put(DiaryRegion.ARDOUGNE, VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.DESERT, VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE,
			VarbitID.DESERT_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.FALADOR, VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE,
			VarbitID.FALADOR_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.FREMENNIK, VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.KANDARIN, VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE,
			VarbitID.KANDARIN_DIARY_ELITE_COMPLETE);
		DIARY_VARBITS.put(diaryKey(DiaryRegion.KARAMJA, DiaryTier.ELITE), VarbitID.KARAMJA_DIARY_ELITE_COMPLETE);
		// Karamja easy / medium / hard: no *_DIARY_*_COMPLETE varbit exists; the reward-claimed
		// varbits prove completion one way only (claimed => complete; unclaimed proves nothing).
		DIARY_VARBITS.put(diaryKey(DiaryRegion.KARAMJA, DiaryTier.EASY), VarbitID.ATJUN_EASY_REWARD);
		DIARY_VARBITS.put(diaryKey(DiaryRegion.KARAMJA, DiaryTier.MEDIUM), VarbitID.ATJUN_MED_REWARD);
		DIARY_VARBITS.put(diaryKey(DiaryRegion.KARAMJA, DiaryTier.HARD), VarbitID.ATJUN_HARD_REWARD);
		PROOF_ONLY.add(diaryKey(DiaryRegion.KARAMJA, DiaryTier.EASY));
		PROOF_ONLY.add(diaryKey(DiaryRegion.KARAMJA, DiaryTier.MEDIUM));
		PROOF_ONLY.add(diaryKey(DiaryRegion.KARAMJA, DiaryTier.HARD));
		put(DiaryRegion.KOUREND, VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE,
			VarbitID.KOUREND_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.LUMBRIDGE, VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.MORYTANIA, VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.VARROCK, VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE,
			VarbitID.VARROCK_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.WESTERN, VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE,
			VarbitID.WESTERN_DIARY_ELITE_COMPLETE);
		put(DiaryRegion.WILDERNESS, VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE);
	}

	private static void put(DiaryRegion region, int easy, int medium, int hard, int elite)
	{
		DIARY_VARBITS.put(diaryKey(region, DiaryTier.EASY), easy);
		DIARY_VARBITS.put(diaryKey(region, DiaryTier.MEDIUM), medium);
		DIARY_VARBITS.put(diaryKey(region, DiaryTier.HARD), hard);
		DIARY_VARBITS.put(diaryKey(region, DiaryTier.ELITE), elite);
	}

	/** The completion varbit for a diary tier, or null when Spyglass has no verified source for it. */
	public static Integer diaryVarbit(String key)
	{
		return DIARY_VARBITS.get(key);
	}

	/**
	 * Every readable diary tier: key -> complete (varbit == 1). A proof-only tier
	 * (Karamja easy / medium / hard) appears only when proven complete; otherwise
	 * it is absent, i.e. unknown.
	 */
	public static Map<String, Boolean> diaries(IntUnaryOperator varbit)
	{
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : DIARY_VARBITS.entrySet())
		{
			boolean complete = varbit.applyAsInt(e.getValue()) == 1;
			if (complete || !PROOF_ONLY.contains(e.getKey()))
			{
				out.put(e.getKey(), complete);
			}
		}
		return Collections.unmodifiableMap(out);
	}

	public static boolean isProofOnly(String key)
	{
		return PROOF_ONLY.contains(key);
	}

	/** All diary keys Spyglass can read. */
	public static java.util.Set<String> readableDiaries()
	{
		return Collections.unmodifiableSet(DIARY_VARBITS.keySet());
	}
}
