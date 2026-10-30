package com.osrstelemetry.plugin.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class AccountReadersTest
{
	@Test
	public void runePouch_decodesSlots_skipsEmptyAndUnmapped_andMergesDuplicates()
	{
		Map<Integer, Integer> v = new HashMap<>();
		v.put(VarbitID.RUNE_POUCH_TYPE_1, 14);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_1, 200);
		v.put(VarbitID.RUNE_POUCH_TYPE_2, 2);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_2, 0);
		v.put(VarbitID.RUNE_POUCH_TYPE_3, 99);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_3, 5);
		v.put(VarbitID.RUNE_POUCH_TYPE_6, 14);
		v.put(VarbitID.RUNE_POUCH_QUANTITY_6, 50);
		Map<Integer, Integer> out = AccountReaders.runePouch(id -> v.getOrDefault(id, 0), type -> type == 14 ? ItemID.LAWRUNE : type == 2 ? ItemID.WATERRUNE : -1);
		assertEquals(1, out.size());
		assertEquals(250, (int) out.get(ItemID.LAWRUNE));
	}

	@Test
	public void runePouchItems_areTheFourRealPouches()
	{
		assertTrue(AccountReaders.isRunePouch(ItemID.BH_RUNE_POUCH));
		assertTrue(AccountReaders.isRunePouch(ItemID.DIVINE_RUNE_POUCH_TROUVER));
		assertFalse(AccountReaders.isRunePouch(ItemID.LAWRUNE));
		assertEquals(4, AccountReaders.RUNE_POUCH_ITEMS.length);
	}

	@Test
	public void spellbookMapping_isTheSourceVerifiedOne_andOutOfRangeIsUnknown()
	{
		assertTrue(AccountReaders.SPELLBOOK_MAPPING_VERIFIED);
		assertEquals(AccountReaders.Spellbook.STANDARD, AccountReaders.spellbookFromVarbit(0));
		assertEquals(AccountReaders.Spellbook.ANCIENT, AccountReaders.spellbookFromVarbit(1));
		assertEquals(AccountReaders.Spellbook.LUNAR, AccountReaders.spellbookFromVarbit(2));
		assertEquals(AccountReaders.Spellbook.ARCEUUS, AccountReaders.spellbookFromVarbit(3));
		assertNull(AccountReaders.spellbookFromVarbit(4));
		assertNull(AccountReaders.spellbookFromVarbit(-1));
		assertEquals(AccountReaders.Spellbook.LUNAR, AccountFixtures.base(1).spellbookRaw(2).build().spellbook());
		assertNull(AccountFixtures.base(1).spellbookRaw(9).build().spellbook());
		assertNull("unread stays unread", AccountFixtures.base(1).build().spellbook());
	}

	@Test
	public void diaries_readOneAsComplete_andKaramjaLowTiersAreProofOnly()
	{
		Map<String, Boolean> d = AccountReaders.diaries(id -> id == VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE ? 1 : id == VarbitID.FALADOR_DIARY_EASY_COMPLETE ? 2 : 0);
		assertTrue(d.get("ARDOUGNE_ELITE"));
		assertFalse("only 1 means complete", d.get("FALADOR_EASY"));
		assertNull("an unclaimed Karamja reward proves nothing", d.get("KARAMJA_EASY"));
		assertFalse("Karamja elite has a real completion varbit", d.get("KARAMJA_ELITE"));
		Map<String, Boolean> claimed = AccountReaders.diaries(id -> id == VarbitID.ATJUN_MED_REWARD ? 1 : 0);
		assertTrue(claimed.get("KARAMJA_MEDIUM"));
		assertTrue(AccountReaders.isProofOnly("KARAMJA_HARD"));
		assertFalse(AccountReaders.isProofOnly("KARAMJA_ELITE"));
		assertEquals(48, AccountReaders.readableDiaries().size());
		assertEquals(Known.UNKNOWN, AccountFixtures.base(1).build().diary("ARDOUGNE_ELITE"));
	}
}
