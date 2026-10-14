package com.osrstelemetry.plugin.pestcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * Pest Control XP rewards (OSRS Wiki, Void Knight Commendation points --
 * experience): XP per point = floor(level^2 / 600) x N (35 melee / HP, 32
 * Ranged / Magic, 18 Prayer); 10 points at once +1%, 100 at once +10%.
 * Costs are an exact purchase-by-purchase simulation (the level is
 * re-evaluated before every purchase), never a closed-form estimate.
 */
public class PestControlXpTest
{
	@Test
	public void eligibleSkills_areExactlyTheSeven()
	{
		assertEquals(7, PestControlXp.SKILLS.size());
		assertTrue(PestControlXp.isEligible(Skill.PRAYER));
		assertFalse(PestControlXp.isEligible(Skill.MINING));
		assertFalse(PestControlXp.isEligible(Skill.SLAYER));
		assertFalse(PestControlXp.isEligible(null));
	}

	@Test
	public void xpPerPoint_matchesTheWikiFormula()
	{
		assertEquals(560, PestControlXp.xpPerPoint(Skill.DEFENCE, 99));
		assertEquals(560, PestControlXp.xpPerPoint(Skill.HITPOINTS, 99));
		assertEquals(512, PestControlXp.xpPerPoint(Skill.RANGED, 99));
		assertEquals(512, PestControlXp.xpPerPoint(Skill.MAGIC, 99));
		assertEquals(288, PestControlXp.xpPerPoint(Skill.PRAYER, 99));
		assertEquals(35, PestControlXp.xpPerPoint(Skill.ATTACK, 25));
		assertEquals(144, PestControlXp.xpPerPoint(Skill.PRAYER, 70));
		assertEquals("floor(34^2/600) = 1", 35, PestControlXp.xpPerPoint(Skill.STRENGTH, 34));
		assertEquals("floor(35^2/600) = 2", 70, PestControlXp.xpPerPoint(Skill.STRENGTH, 35));
	}

	@Test
	public void bulkBonuses_onePercentForTen_tenPercentForHundred()
	{
		assertEquals(560, PestControlXp.purchaseXp(Skill.DEFENCE, 99, 1));
		assertEquals(5656, PestControlXp.purchaseXp(Skill.DEFENCE, 99, 10));
		assertEquals(61600, PestControlXp.purchaseXp(Skill.DEFENCE, 99, 100));
		assertEquals("rounded down", 353, PestControlXp.purchaseXp(Skill.ATTACK, 25, 10));
	}

	@Test
	public void level98to99Defence_costsExactly2000Points()
	{
		// 1,228,825 XP at 560/point: 19 x 100 then the cheapest finish is one more 100.
		PestControlXp.Cost c = PestControlXp.costToLevel(Skill.DEFENCE, Experience.getXpForLevel(98), 99);
		assertNull(c.getProblem());
		assertEquals(2000L, c.getPoints());
	}

	@Test
	public void aTenPointPurchase_beatsElevenSingles_thanksToItsBonus()
	{
		int xp = Experience.getXpForLevel(25);
		// 352 XP at 35/point: 11 singles, or one 10-point purchase worth 353.
		assertEquals(10L, PestControlXp.cost(Skill.ATTACK, xp, xp + 352).getPoints());
		assertEquals(1L, PestControlXp.cost(Skill.ATTACK, xp, xp + 1).getPoints());
	}

	@Test
	public void levelUps_duringTheGrind_areSimulated_notAveraged()
	{
		// 25 -> 40 Attack: the rate doubles at 35; a fixed level-25 rate would need 764.
		PestControlXp.Cost c = PestControlXp.costToLevel(Skill.ATTACK, Experience.getXpForLevel(25), 40);
		assertEquals(589L, c.getPoints());
		assertEquals(3984L, PestControlXp.costToLevel(Skill.RANGED, Experience.getXpForLevel(70), 80).getPoints());
	}

	@Test
	public void wholeGrind_prayer25to99_isExact_andFast()
	{
		long start = System.nanoTime();
		PestControlXp.Cost c = PestControlXp.costToLevel(Skill.PRAYER, Experience.getXpForLevel(25), 99);
		long ms = (System.nanoTime() - start) / 1_000_000L;
		assertEquals(56349L, c.getPoints());
		assertTrue("one render's worth of work (" + ms + " ms)", ms < 250);
	}

	@Test
	public void problems_areNamed_neverACost()
	{
		assertEquals(PestControlXp.Problem.INELIGIBLE_SKILL, PestControlXp.costToLevel(Skill.MINING, 100_000, 60).getProblem());
		assertEquals(PestControlXp.Problem.BELOW_MINIMUM_LEVEL,
			PestControlXp.costToLevel(Skill.ATTACK, Experience.getXpForLevel(25) - 1, 30).getProblem());
		assertEquals(PestControlXp.Problem.TARGET_TOO_HIGH, PestControlXp.costToLevel(Skill.ATTACK, 100_000, 100).getProblem());
		PestControlXp.Cost done = PestControlXp.costToLevel(Skill.ATTACK, Experience.getXpForLevel(80), 75);
		assertEquals(PestControlXp.Problem.ALREADY_REACHED, done.getProblem());
		assertEquals(0L, done.getPoints());
	}
}
