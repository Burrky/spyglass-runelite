package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * RUNES CRAFTED attribution: only a confirmed GotR altar craft (guardian
 * essence consumed + runes gained in one transition, in context, with
 * Runecraft XP on the same/next tick) produces runes, at exact quantities.
 */
public class ProductionTrackerTest
{
	private static final int ESS = ItemID.GOTR_GUARDIAN_ESSENCE;
	private static final int AIR = ItemID.AIRRUNE;
	private static final int COSMIC = ItemID.COSMICRUNE;
	private static final int NATURE = ItemID.NATURERUNE;
	private static final int DEATH = ItemID.DEATHRUNE;
	private static final int BLOOD = ItemID.BLOODRUNE;

	private ProductionTracker t;
	private long xp;

	@Before
	public void setUp()
	{
		t = new ProductionTracker(GotrRunes.RECIPE);
		xp = 1_000_000;
		t.onSkillXp(xp, 0); // login sync: baseline only
	}

	private static Map<Integer, Long> inv(long... idQty)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			if (idQty[i + 1] > 0)
			{
				m.put((int) idQty[i], idQty[i + 1]);
			}
		}
		return m;
	}

	/** Runecraft XP first, then the inventory transition (same tick). */
	private Map<Integer, Long> craft(Map<Integer, Long> after, int tick)
	{
		xp += 500;
		assertTrue(t.onSkillXp(xp, tick).isEmpty());
		return t.onInventory(after, tick, true);
	}

	@Test
	public void firstCraft_countsTheExactRuneGain()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		Map<Integer, Long> out = craft(inv(AIR, 300), 2);
		assertEquals(Long.valueOf(300), out.get(AIR));
		assertEquals(1, out.size());
	}

	@Test
	public void xpArrivingAfterTheInventory_onTheNextTick_stillConfirms()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		assertTrue(t.onInventory(inv(COSMIC, 40), 2, true).isEmpty());
		xp += 300;
		Map<Integer, Long> out = t.onSkillXp(xp, 3);
		assertEquals(Long.valueOf(40), out.get(COSMIC));
	}

	@Test
	public void withoutRunecraftXp_theCandidateExpires_neverCounted()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		assertTrue(t.onInventory(inv(COSMIC, 40), 2, true).isEmpty());
		t.onTick(3);
		t.onTick(4);
		xp += 300;
		assertTrue("too late to corroborate", t.onSkillXp(xp, 5).isEmpty());
	}

	@Test
	public void stackAlreadyHeld_onlyTheAddedAmountCounts()
	{
		t.onInventory(inv(ESS, 20, NATURE, 1_000), 1, true);
		Map<Integer, Long> out = craft(inv(NATURE, 1_046), 2);
		assertEquals(Long.valueOf(46), out.get(NATURE));
	}

	@Test
	public void repeatedCrafts_andMultipleRuneTypes_areSeparateExactAmounts()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		assertEquals(Long.valueOf(300), craft(inv(AIR, 300), 2).get(AIR));
		t.onInventory(inv(AIR, 300, ESS, 24), 4, true); // essence pouch emptied: no craft
		assertEquals(Long.valueOf(360), craft(inv(AIR, 660), 6).get(AIR));
		t.onInventory(inv(AIR, 660, ESS, 18), 8, true);
		Map<Integer, Long> both = craft(inv(AIR, 660, COSMIC, 18, NATURE, 9), 10);
		assertEquals(Long.valueOf(18), both.get(COSMIC));
		assertEquals(Long.valueOf(9), both.get(NATURE));
		assertEquals(2, both.size());
	}

	@Test
	public void runeGainWithoutEssenceConsumed_isNeverProduction()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		xp += 100;
		t.onSkillXp(xp, 2);
		assertTrue("reward / pick-up / withdrawal: no essence used",
			t.onInventory(inv(ESS, 20, NATURE, 50), 2, true).isEmpty());
	}

	@Test
	public void droppingOrSpendingRunesAfterwards_neverProducesOrSubtracts()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		craft(inv(AIR, 300), 2);
		assertTrue(t.onInventory(inv(AIR, 100), 4, true).isEmpty());
		assertTrue("picking the dropped runes back up is not production",
			t.onInventory(inv(AIR, 300), 6, true).isEmpty());
	}

	@Test
	public void essenceConsumedWithoutRunes_isNotProduction()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		xp += 50;
		t.onSkillXp(xp, 2);
		assertTrue(t.onInventory(inv(ESS, 10), 2, true).isEmpty());
	}

	@Test
	public void unrelatedItemsAndOtherConsumables_areIgnored()
	{
		t.onInventory(inv(ESS, 20, 995, 1_000), 1, true);
		xp += 50;
		t.onSkillXp(xp, 2);
		assertTrue(t.onInventory(inv(ESS, 20, 995, 5_000), 2, true).isEmpty());
	}

	@Test
	public void outsideTheGameContext_nothingCounts_butTheBaselineFollows()
	{
		t.onInventory(inv(ESS, 20), 1, false);
		xp += 100;
		t.onSkillXp(xp, 2);
		assertTrue(t.onInventory(inv(AIR, 200), 2, false).isEmpty());
		assertTrue("baseline moved: re-entering does not replay it", t.onInventory(inv(AIR, 200), 3, true).isEmpty());
	}

	@Test
	public void reset_loginOrAccountSwitch_firstInventoryAndXpAreBaselineOnly()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		t.reset();
		assertTrue(t.onInventory(inv(AIR, 300), 2, true).isEmpty());
		assertTrue("first XP after reset is a baseline, not a gain", t.onSkillXp(2_000_000, 2).isEmpty());
		t.onInventory(inv(AIR, 300, ESS, 10), 3, true);
		assertTrue("XP baseline was 2,000,000: no gain seen yet", t.onInventory(inv(AIR, 340), 4, true).isEmpty());
		assertEquals(Long.valueOf(40), t.onSkillXp(2_000_400, 4).get(AIR));
	}

	@Test
	public void twoTransitionsOnOneXpGain_bothCount_exactlyOnce()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		assertEquals(Long.valueOf(10), craft(inv(ESS, 10, AIR, 10), 2).get(AIR));
		assertEquals("a second craft on the same tick is still a craft (essence consumed)",
			Long.valueOf(10), t.onInventory(inv(AIR, 20), 2, true).get(AIR));
		assertTrue("nothing is emitted twice", t.onSkillXp(xp + 1, 2).isEmpty());
	}

	// ---- rapid crafting: craft -> essence-pouch refill -> craft ----------

	private long total(Map<Integer, Long> out, int id)
	{
		return out.getOrDefault(id, 0L);
	}

	@Test
	public void rapidCraft_refillBetween_xpAfterBoth_bothBatchesCount()
	{
		long sum = 0;
		t.onInventory(inv(ESS, 28), 1, true);
		sum += total(t.onInventory(inv(DEATH, 30), 10, true), DEATH);       // craft A, XP not seen yet
		sum += total(t.onInventory(inv(DEATH, 30, ESS, 20), 10, true), DEATH); // refill from the pouch
		sum += total(t.onInventory(inv(DEATH, 52), 11, true), DEATH);       // craft B
		xp += 1_000;
		sum += total(t.onSkillXp(xp, 11), DEATH);                            // one coalesced XP update
		assertEquals(52, sum);
	}

	@Test
	public void rapidCraft_bothXpUpdatesBeforeEitherInventory_bothBatchesCount()
	{
		long sum = 0;
		t.onInventory(inv(ESS, 28), 1, true);
		xp += 500;
		sum += total(t.onSkillXp(xp, 10), DEATH);
		xp += 400;
		sum += total(t.onSkillXp(xp, 11), DEATH);
		sum += total(t.onInventory(inv(DEATH, 30), 11, true), DEATH);
		sum += total(t.onInventory(inv(DEATH, 30, ESS, 20), 11, true), DEATH);
		sum += total(t.onInventory(inv(DEATH, 52), 12, true), DEATH);
		assertEquals("old one-XP-one-transition model counted only 30", 52, sum);
	}

	@Test
	public void threeRapidCrafts_differentRunes_exactTotals()
	{
		Map<Integer, Long> got = new HashMap<>();
		t.onInventory(inv(ESS, 28), 1, true);
		xp += 500;
		t.onSkillXp(xp, 10).forEach((k, v) -> got.merge(k, v, Long::sum));
		t.onInventory(inv(NATURE, 30), 10, true).forEach((k, v) -> got.merge(k, v, Long::sum));
		t.onInventory(inv(NATURE, 30, ESS, 26), 11, true).forEach((k, v) -> got.merge(k, v, Long::sum));
		t.onInventory(inv(NATURE, 58), 12, true).forEach((k, v) -> got.merge(k, v, Long::sum));
		t.onInventory(inv(NATURE, 58, ESS, 12), 13, true).forEach((k, v) -> got.merge(k, v, Long::sum));
		xp += 400;
		t.onSkillXp(xp, 13).forEach((k, v) -> got.merge(k, v, Long::sum));
		t.onTick(14);
		t.onInventory(inv(NATURE, 58, COSMIC, 13), 14, true).forEach((k, v) -> got.merge(k, v, Long::sum));
		xp += 200;
		t.onSkillXp(xp, 14).forEach((k, v) -> got.merge(k, v, Long::sum));
		assertEquals(Long.valueOf(58), got.get(NATURE));
		assertEquals(Long.valueOf(13), got.get(COSMIC));
		assertEquals(2, got.size());
	}

	@Test
	public void rewardRunesAfterACraft_outsideTheWindow_neverCount()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		assertEquals(Long.valueOf(40), craft(inv(COSMIC, 40), 2).get(COSMIC));
		t.onTick(5);
		xp += 10;
		t.onSkillXp(xp, 5);
		assertTrue("reward/pick-up runes later: no essence consumed nearby",
			t.onInventory(inv(COSMIC, 90, NATURE, 25), 6, true).isEmpty());
	}

	@Test
	public void correlationStateIsBounded()
	{
		t.onInventory(inv(ESS, 1_000), 1, true);
		for (int tick = 2; tick < 500; tick++)
		{
			t.onTick(tick);
			t.onInventory(inv(ESS, 1_000 - tick), tick, true);
		}
		assertTrue(t.heldTicks() <= 2 * ProductionTracker.XP_WINDOW_TICKS + 1);
	}

	// ---- rune pouch as a second destination ------------------------------

	@Test
	public void wholeCraftIntoTheRunePouch_countsExactly()
	{
		t.onInventory(inv(ESS, 28), 1, true);
		t.onSecondaryStore(inv(DEATH, 800), 1, true);
		xp += 500;
		assertTrue(t.onSkillXp(xp, 2).isEmpty());
		assertTrue("essence consumed, nothing landed in the inventory", t.onInventory(inv(), 2, true).isEmpty());
		assertEquals(Long.valueOf(56), t.onSecondaryStore(inv(DEATH, 856), 2, true).get(DEATH));
	}

	@Test
	public void craftSplitBetweenInventoryAndPouch_countsOnce_inEitherOrder()
	{
		t.onInventory(inv(ESS, 28), 1, true);
		t.onSecondaryStore(inv(DEATH, 800), 1, true);
		xp += 500;
		t.onSkillXp(xp, 2);
		long sum = total(t.onSecondaryStore(inv(DEATH, 860), 2, true), DEATH)
			+ total(t.onInventory(inv(DEATH, 40), 2, true), DEATH);
		assertEquals(100, sum);

		// Reverse order, XP last, pouch update on the adjacent tick.
		t.onInventory(inv(DEATH, 40, ESS, 28), 4, true);
		sum = total(t.onInventory(inv(DEATH, 80), 5, true), DEATH);
		sum += total(t.onSecondaryStore(inv(DEATH, 920), 6, true), DEATH);
		xp += 500;
		sum += total(t.onSkillXp(xp, 6), DEATH);
		assertEquals(100, sum);
	}

	@Test
	public void newRuneTypeIntoAnEmptyPouchSlot_counts()
	{
		t.onInventory(inv(ESS, 10), 1, true);
		t.onSecondaryStore(inv(DEATH, 800), 1, true);
		xp += 500;
		t.onSkillXp(xp, 2);
		t.onInventory(inv(), 2, true);
		Map<Integer, Long> out = t.onSecondaryStore(inv(DEATH, 800, BLOOD, 12), 2, true);
		assertEquals(Long.valueOf(12), out.get(BLOOD));
		assertEquals(1, out.size());
	}

	@Test
	public void rapidCraftsIntoThePouch_bothCount()
	{
		long sum = 0;
		t.onInventory(inv(ESS, 28), 1, true);
		t.onSecondaryStore(inv(DEATH, 800), 1, true);
		sum += total(t.onInventory(inv(), 10, true), DEATH);
		sum += total(t.onSecondaryStore(inv(DEATH, 830), 10, true), DEATH);
		xp += 500;
		sum += total(t.onSkillXp(xp, 10), DEATH);
		sum += total(t.onInventory(inv(ESS, 20), 11, true), DEATH);
		sum += total(t.onInventory(inv(), 12, true), DEATH);
		xp += 400;
		sum += total(t.onSkillXp(xp, 12), DEATH);
		sum += total(t.onSecondaryStore(inv(DEATH, 852), 12, true), DEATH);
		assertEquals(52, sum);
	}

	@Test
	public void movingRunesBetweenInventoryAndPouch_isNeverProduction()
	{
		t.onInventory(inv(ESS, 20, DEATH, 100), 1, true);
		t.onSecondaryStore(inv(DEATH, 800), 1, true);
		xp += 500;
		t.onSkillXp(xp, 2);
		t.onInventory(inv(ESS, 10, DEATH, 100), 2, true); // essence used (e.g. into an essence pouch)
		assertTrue("inventory -> pouch, same tick", t.onInventory(inv(ESS, 10), 2, true).isEmpty());
		assertTrue(t.onSecondaryStore(inv(DEATH, 900), 2, true).isEmpty());
		assertTrue("pouch -> inventory", t.onSecondaryStore(inv(DEATH, 850), 3, true).isEmpty());
		assertTrue(t.onInventory(inv(ESS, 10, DEATH, 50), 3, true).isEmpty());
	}

	@Test
	public void pouchGainWithoutAnyCraft_isNeverProduction()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		t.onSecondaryStore(inv(), 1, true);
		xp += 100;
		t.onSkillXp(xp, 2);
		assertTrue("reward/picked-up runes put into the pouch", t.onSecondaryStore(inv(NATURE, 50), 2, true).isEmpty());
	}

	@Test
	public void pouchBaseline_afterResetOrLogin_isNeverProduction()
	{
		t.onInventory(inv(ESS, 20), 1, true);
		t.reset();
		t.onSkillXp(xp, 2);
		t.onInventory(inv(ESS, 10), 2, true);
		assertTrue("first pouch snapshot is the baseline", t.onSecondaryStore(inv(DEATH, 900), 2, true).isEmpty());
		xp += 100;
		t.onSkillXp(xp, 3);
		assertTrue(t.onSecondaryStore(inv(DEATH, 900), 3, true).isEmpty());
	}

	@Test
	public void everyRuneTypeIsRecognised_essenceIsTheOnlyInput()
	{
		assertTrue(GotrRunes.RUNE_ITEM_IDS.contains(ItemID.BLOODRUNE));
		assertTrue(GotrRunes.RUNE_ITEM_IDS.contains(ItemID.DEATHRUNE));
		assertTrue(GotrRunes.RUNE_ITEM_IDS.contains(ItemID.LAWRUNE));
		assertTrue(GotrRunes.RUNE_ITEM_IDS.contains(ItemID.MISTRUNE));
		assertEquals(1, GotrRunes.RECIPE.getConsumedItemIds().size());
		assertTrue(GotrRunes.RECIPE.getConsumedItemIds().contains(ESS));
		assertEquals("runecraft", GotrRunes.RECIPE.getCorroboratingSkill());
	}
}
