package com.osrstelemetry.plugin.wintertodt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.activity.WintertodtRoundTracker;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Current (post-rework) Wintertodt rules: reward thresholds, point values,
 * inventory potential, unique probabilities and their milestones, tools,
 * conservative playstyle inference and the goal model (the Reward Hunt
 * math itself is WintertodtRewardHuntTest's).
 */
public class WintertodtRulesTest
{
	// ---- rewards / thresholds ----------------------------------------------------------

	@Test
	public void rewards_twoAt500_plusOnePerFurther500_noneBelow()
	{
		assertEquals(0, WintertodtRewards.rewardsFor(0));
		assertEquals(0, WintertodtRewards.rewardsFor(499));
		assertEquals(2, WintertodtRewards.rewardsFor(500));
		assertEquals(2, WintertodtRewards.rewardsFor(999));
		assertEquals(3, WintertodtRewards.rewardsFor(1000));
		assertEquals(4, WintertodtRewards.rewardsFor(1500));
	}

	@Test
	public void nextDecision_qualifyThenNextReward()
	{
		assertEquals(142, WintertodtRewards.pointsToQualify(358));
		assertEquals(0, WintertodtRewards.pointsToQualify(600));
		assertEquals(142, WintertodtRewards.pointsToNextReward(358));
		assertEquals(400, WintertodtRewards.pointsToNextReward(600));
		assertEquals(500, WintertodtRewards.pointsToNextReward(1000));
	}

	@Test
	public void inventoryPotential_rootsTenKindlingTwentyFive()
	{
		assertEquals(0, WintertodtRewards.inventoryPoints(0, 0));
		assertEquals(250, WintertodtRewards.inventoryPoints(25, 0));
		assertEquals(325, WintertodtRewards.inventoryPoints(20, 5));
		assertEquals("fletched first", 625, WintertodtRewards.inventoryPointsIfFletched(20, 5));
		assertEquals(0, WintertodtRewards.inventoryPoints(-3, -1));
	}

	// ---- uniques ---------------------------------------------------------------------------

	@Test
	public void uniqueChance_isRateTimesEarlierMisses()
	{
		assertEquals(1.0 / 5000, WintertodtUnique.PHOENIX.chancePerReward(), 1e-12);
		double tome = (1 - 1.0 / 5000) * (1 - 1.0 / 10000) / 1000;
		assertEquals(tome, WintertodtUnique.TOME_OF_FIRE.chancePerReward(), 1e-12);
		assertTrue(WintertodtUnique.BURNT_PAGE.chancePerReward() < 1.0 / 45);
		assertEquals(WintertodtUnique.PYROMANCER_PIECE.chancePerReward(), WintertodtUnique.PYROMANCER_OUTFIT.chancePerReward(), 0);
	}

	@Test
	public void milestones_50and90_forOneDrop()
	{
		// ln(0.5)/ln(1-1/5000) = 3465.4 -> 3466; ln(0.1)/ln(1-1/5000) = 11511.8 -> 11512
		assertEquals(3466, WintertodtUnique.PHOENIX.rewardsForChance(0.5));
		assertEquals(11512, WintertodtUnique.PHOENIX.rewardsForChance(0.9));
		assertTrue(WintertodtUnique.PHOENIX.rewardsForChance(0.9) > WintertodtUnique.PHOENIX.rewardsForChance(0.5));
	}

	@Test
	public void milestones_forTheFourPieceOutfit_areLater_thanForOnePiece()
	{
		long one = WintertodtUnique.PYROMANCER_PIECE.rewardsForChance(0.5);
		long four = WintertodtUnique.PYROMANCER_OUTFIT.rewardsForChance(0.5);
		assertTrue(four > 3 * one);
		// Binomial check: 4 successes at p ~ 1/150 have ~50% by ~550 rewards (median of NegBin(4, p) ~ (4 - 1/3)/p).
		assertTrue(four > 500 && four < 600);
		assertEquals(1, WintertodtUnique.rewardsForChance(1.0, 1, 0.5));
	}

	// ---- tools ---------------------------------------------------------------------------------

	@Test
	public void tools_byTheGamesOwnNames()
	{
		assertEquals(WintertodtTools.AXE, WintertodtTools.of("Dragon axe"));
		assertEquals(WintertodtTools.AXE, WintertodtTools.of("Infernal axe"));
		assertEquals(WintertodtTools.AXE, WintertodtTools.of("Dragon felling axe"));
		assertNull(WintertodtTools.of("Dragon pickaxe"));
		assertNull(WintertodtTools.of("Dragon battleaxe"));
		assertEquals(WintertodtTools.KNIFE, WintertodtTools.of("Fletching knife"));
		assertEquals(WintertodtTools.HAMMER, WintertodtTools.of("Imcando hammer"));
		assertEquals(WintertodtTools.TINDERBOX, WintertodtTools.of("Bruma torch"));
		assertEquals(WintertodtTools.TINDERBOX, WintertodtTools.of("Tinderbox"));
		Map<Integer, Long> items = new HashMap<>();
		items.put(1, 1L);
		items.put(2, 1L);
		items.put(3, 0L);
		assertEquals(2, WintertodtTools.in(items, id -> id == 1 ? "Rune axe" : id == 2 ? "Knife" : "Hammer").size());
	}

	// ---- playstyle inference (Method + Focus) ----------------------------------------------------

	private static Map<String, Long> actions(long roots, long kindling, long heals)
	{
		return actions(roots, kindling, 0, heals);
	}

	private static Map<String, Long> actions(long roots, long kindling, long repairs, long heals)
	{
		Map<String, Long> m = new HashMap<>();
		m.put(WintertodtRoundTracker.ROOTS_FED, roots);
		m.put(WintertodtRoundTracker.KINDLING_FED, kindling);
		m.put(WintertodtRoundTracker.REPAIRED, repairs);
		m.put(WintertodtRoundTracker.HEALED, heals);
		return m;
	}

	private static final long MASS_ROUND = 248_000L;
	private static final long SOLO_ROUND = 15 * 60_000L;

	@Test
	public void inferMethod_massAndSolo_fromCompleteRoundLength()
	{
		assertEquals(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.inferMethod(3, 3, 3 * MASS_ROUND));
		assertEquals(WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.inferMethod(3, 3, 3 * SOLO_ROUND));
	}

	@Test
	public void inferMethod_needsAboutThreeMeaningfulRounds_andStaysAutoWhenAmbiguous()
	{
		assertNull(WintertodtPlaystyle.inferMethod(0, 0, 0));
		assertNull("two rounds", WintertodtPlaystyle.inferMethod(2, 2, 2 * MASS_ROUND));
		assertNull("rounds joined part-way are not meaningful", WintertodtPlaystyle.inferMethod(5, 1, MASS_ROUND));
		assertNull("8-minute rounds sit between mass and solo", WintertodtPlaystyle.inferMethod(3, 3, 3 * 8 * 60_000L));
	}

	@Test
	public void inferFocus_xpAndPoints_fromTheFeedMix()
	{
		assertEquals(WintertodtPlaystyle.Focus.XP, WintertodtPlaystyle.inferFocus(3, 3, actions(150, 10, 0)));
		assertEquals(WintertodtPlaystyle.Focus.POINTS, WintertodtPlaystyle.inferFocus(3, 3, actions(20, 110, 0)));
		assertEquals("kindling-leaning plus support every round", WintertodtPlaystyle.Focus.POINTS,
			WintertodtPlaystyle.inferFocus(3, 3, actions(60, 50, 4, 3)));
	}

	@Test
	public void inferFocus_thinOrMixed_staysAuto_oneFletchRepairOrHealForcesNothing()
	{
		assertNull("one round", WintertodtPlaystyle.inferFocus(1, 1, actions(0, 1, 0)));
		assertNull("too few feeds", WintertodtPlaystyle.inferFocus(3, 3, actions(20, 10, 0)));
		assertNull("an even mix is ambiguous", WintertodtPlaystyle.inferFocus(3, 3, actions(60, 50, 0)));
		assertNull("one repair + one heal on a mixed feed", WintertodtPlaystyle.inferFocus(3, 3, actions(60, 50, 1, 1)));
		assertEquals("one fletched kindling among roots is still XP", WintertodtPlaystyle.Focus.XP,
			WintertodtPlaystyle.inferFocus(3, 3, actions(150, 1, 1, 1)));
		assertEquals("one repair never makes a mass round Solo", WintertodtPlaystyle.Method.MASS,
			WintertodtPlaystyle.inferMethod(3, 3, 3 * MASS_ROUND));
	}

	@Test
	public void inference_allFourCombos()
	{
		assertEquals("Mass • XP", combo(3 * MASS_ROUND, actions(150, 10, 0)));
		assertEquals("Mass • Points", combo(3 * MASS_ROUND, actions(20, 110, 0)));
		assertEquals("Solo • XP", combo(3 * SOLO_ROUND, actions(400, 20, 0)));
		assertEquals("Solo • Points", combo(3 * SOLO_ROUND, actions(100, 300, 6, 5)));
		assertEquals("only the method is known: stays Auto", "Auto", combo(3 * MASS_ROUND, actions(60, 50, 0)));
		assertEquals("only the focus is known: stays Auto", "Auto", combo(3 * 8 * 60_000L, actions(20, 110, 0)));
	}

	private static String combo(long millis, Map<String, Long> actions)
	{
		WintertodtPlaystyle.Method m = WintertodtPlaystyle.inferMethod(3, 3, millis);
		WintertodtPlaystyle.Focus f = WintertodtPlaystyle.inferFocus(3, 3, actions);
		return m == null || f == null ? WintertodtPlaystyle.label(WintertodtPlaystyle.Method.AUTO, null) : WintertodtPlaystyle.label(m, f);
	}

	@Test
	public void migration_allSixLegacyValues()
	{
		assertMigrated("MASS_XP", WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.XP);
		assertMigrated("MASS_REWARDS", WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.POINTS);
		assertMigrated("SOLO_XP", WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.XP);
		assertMigrated("SOLO_REWARDS", WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.POINTS);
		assertMigrated("AUTO", WintertodtPlaystyle.Method.AUTO, null);
		assertMigrated("WORLD_HOP_XP", WintertodtPlaystyle.Method.AUTO, WintertodtPlaystyle.Focus.XP);
		assertMigrated("SOMETHING_ELSE", WintertodtPlaystyle.Method.AUTO, null);
	}

	private static void assertMigrated(String legacy, WintertodtPlaystyle.Method method, WintertodtPlaystyle.Focus focus)
	{
		Object[] m = WintertodtPlaystyle.migrate(legacy);
		assertEquals(legacy, method, m[0]);
		assertEquals(legacy, focus, m[1]);
	}

	@Test
	public void labels()
	{
		assertEquals("Mass • Points", WintertodtPlaystyle.label(WintertodtPlaystyle.Method.MASS, WintertodtPlaystyle.Focus.POINTS));
		assertEquals("Solo • XP", WintertodtPlaystyle.label(WintertodtPlaystyle.Method.SOLO, WintertodtPlaystyle.Focus.XP));
		assertEquals("Auto", WintertodtPlaystyle.label(WintertodtPlaystyle.Method.AUTO, WintertodtPlaystyle.Focus.XP));
	}

	// ---- goal (planning state; its unique is the camp's Reward Hunt target) ----------------

	@Test
	public void goal_isClampedAndImmutable()
	{
		WintertodtGoal g = WintertodtGoal.DEFAULT.withFiremakingTarget(150);
		assertEquals(99, g.getFiremakingTarget());
		assertEquals(2, g.withFiremakingTarget(-4).getFiremakingTarget());
		assertEquals(WintertodtGoal.Family.FIREMAKING, WintertodtGoal.DEFAULT.getFamily());
	}
}
