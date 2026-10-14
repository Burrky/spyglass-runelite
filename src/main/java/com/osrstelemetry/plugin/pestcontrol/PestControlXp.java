package com.osrstelemetry.plugin.pestcontrol;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Experience;
import net.runelite.api.Skill;

/**
 * Commendation-point cost of a Pest Control XP target.
 *
 * MECHANICS (OSRS Wiki "Void Knights' Reward Options", checked 2026-10-02):
 * <ul>
 * <li>Eligible: Attack, Strength, Defence, Hitpoints, Ranged, Magic, Prayer
 * -- each only at level 25 or higher.</li>
 * <li>XP per point = floor(level^2 / 600) x N, at the skill's CURRENT level;
 * N = 35 (Attack / Strength / Defence / Hitpoints), 32 (Ranged / Magic),
 * 18 (Prayer).</li>
 * <li>Points are spent 1, 10 or 100 at a time; 10 at once gives +1% XP,
 * 100 at once +10%.</li>
 * </ul>
 * ASSUMPTIONS (not stated by the wiki; flagged for verification): a
 * purchase's XP uses the level at the moment of that purchase (a level
 * gained inside a 10/100-point purchase does not change it), and the bonus
 * is applied to the batch total, rounded down.
 *
 * ALGORITHM: simulate purchases from the exact current XP. Each step
 * recomputes the level from the XP reached so far and buys 100 points
 * while a 100-point purchase cannot overshoot the target; near the target
 * it picks the cheapest way to finish at the current rate (one 100-point
 * purchase, 10-point purchases plus singles, or singles) and buys only the
 * first purchase of that plan before re-evaluating -- so a level reached
 * on the way is always used. The result is the total points spent.
 */
public final class PestControlXp
{
	private PestControlXp()
	{
	}

	public static final int MIN_LEVEL = 25;
	public static final int MAX_XP = Experience.MAX_SKILL_XP;

	/** The eligible skills in shop order. */
	public static final List<Skill> SKILLS = Collections.unmodifiableList(Arrays.asList(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS, Skill.RANGED, Skill.MAGIC, Skill.PRAYER));

	public static boolean isEligible(Skill skill)
	{
		return skill != null && SKILLS.contains(skill);
	}

	static int multiplier(Skill skill)
	{
		switch (skill)
		{
			case ATTACK:
			case STRENGTH:
			case DEFENCE:
			case HITPOINTS:
				return 35;
			case RANGED:
			case MAGIC:
				return 32;
			case PRAYER:
				return 18;
			default:
				throw new IllegalArgumentException("Not a Pest Control XP skill: " + skill);
		}
	}

	/** XP for ONE point at {@code level}. */
	public static int xpPerPoint(Skill skill, int level)
	{
		return (level * level / 600) * multiplier(skill);
	}

	/** XP of one purchase of {@code points} (1, 10 or 100) at {@code level}, bonus included, rounded down. */
	public static long purchaseXp(Skill skill, int level, int points)
	{
		long base = (long) xpPerPoint(skill, level) * points;
		switch (points)
		{
			case 1:
				return base;
			case 10:
				return base * 101 / 100;
			case 100:
				return base * 110 / 100;
			default:
				throw new IllegalArgumentException("Purchases are 1, 10 or 100 points");
		}
	}

	/** Why a target cannot be costed, or null when it can. */
	public enum Problem
	{
		INELIGIBLE_SKILL,
		BELOW_MINIMUM_LEVEL,
		TARGET_TOO_HIGH,
		ALREADY_REACHED
	}

	public static final class Cost
	{
		private final Problem problem;
		private final long points;

		private Cost(Problem problem, long points)
		{
			this.problem = problem;
			this.points = points;
		}

		/** Null when the target is costable (ALREADY_REACHED costs 0). */
		public Problem getProblem()
		{
			return problem;
		}

		public long getPoints()
		{
			return points;
		}
	}

	/** Points needed to take {@code skill} from {@code currentXp} to {@code targetXp}. */
	public static Cost cost(Skill skill, long currentXp, long targetXp)
	{
		if (!isEligible(skill))
		{
			return new Cost(Problem.INELIGIBLE_SKILL, 0);
		}
		if (targetXp > MAX_XP)
		{
			return new Cost(Problem.TARGET_TOO_HIGH, 0);
		}
		if (targetXp <= currentXp)
		{
			return new Cost(Problem.ALREADY_REACHED, 0);
		}
		if (Experience.getLevelForXp((int) Math.min(Integer.MAX_VALUE, currentXp)) < MIN_LEVEL)
		{
			return new Cost(Problem.BELOW_MINIMUM_LEVEL, 0);
		}
		long xp = currentXp;
		long points = 0;
		while (xp < targetXp)
		{
			int level = Experience.getLevelForXp((int) xp);
			long remaining = targetXp - xp;
			long g1 = purchaseXp(skill, level, 1);
			long g10 = purchaseXp(skill, level, 10);
			long g100 = purchaseXp(skill, level, 100);
			int buy;
			if (g100 <= remaining)
			{
				buy = 100;
			}
			else
			{
				buy = firstPurchaseOfCheapestFinish(remaining, g1, g10);
			}
			xp += purchaseXp(skill, level, buy);
			points += buy;
		}
		return new Cost(null, points);
	}

	/** Cost of the target LEVEL (its first XP), from exact current XP. */
	public static Cost costToLevel(Skill skill, long currentXp, int targetLevel)
	{
		if (targetLevel > Experience.MAX_REAL_LEVEL)
		{
			return new Cost(Problem.TARGET_TOO_HIGH, 0);
		}
		return cost(skill, currentXp, Experience.getXpForLevel(Math.max(1, targetLevel)));
	}

	/**
	 * At a fixed rate, the cheapest finish for {@code remaining} XP is one of:
	 * a single 100-point purchase (100 points), tens then singles, or all
	 * tens. Returns the first purchase of the cheapest (ties prefer smaller
	 * purchases: they can still benefit from a level-up).
	 */
	static int firstPurchaseOfCheapestFinish(long remaining, long g1, long g10)
	{
		long tens = remaining / g10;
		long rest = remaining - tens * g10;
		long ones = rest == 0 ? 0 : (rest + g1 - 1) / g1;
		long tensThenOnes = tens * 10 + Math.min(ones, 10);
		if (tensThenOnes >= 100)
		{
			return 100;
		}
		if (tens > 0 || ones >= 10)
		{
			return 10;
		}
		return 1;
	}
}
