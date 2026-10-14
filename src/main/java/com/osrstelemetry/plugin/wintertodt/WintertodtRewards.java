package com.osrstelemetry.plugin.wintertodt;

/**
 * Current Wintertodt reward rules (OSRS Wiki "Wintertodt" and "Reward Cart",
 * read 2026-10-02; rework of 9 Oct 2024):
 * <ul>
 * <li>Subduing the Wintertodt with at least {@link #QUALIFY_POINTS} points
 * owes {@link #BASE_REWARDS} rewards from the Reward Cart, plus one per
 * further 500 points; below 500 nothing.</li>
 * <li>Point values: feeding a bruma root 10, bruma kindling 25, lighting or
 * repairing a brazier 25, healing a pyromancer (one rejuvenation dose) 75.
 * Chopping and fletching give none.</li>
 * <li>The cart holds at most {@link #CART_MAX} rewards.</li>
 * </ul>
 */
public final class WintertodtRewards
{
	public static final int QUALIFY_POINTS = 500;
	public static final int POINTS_PER_EXTRA_REWARD = 500;
	public static final int BASE_REWARDS = 2;
	public static final int CART_MAX = 8000;

	public static final int ROOT_POINTS = 10;
	public static final int KINDLING_POINTS = 25;
	public static final int LIGHT_POINTS = 25;
	public static final int REPAIR_POINTS = 25;
	public static final int HEAL_POINTS = 75;

	private WintertodtRewards()
	{
	}

	/** Rewards owed for a subdue with {@code points}: 0 below 500, else 2 + one per further full 500. */
	public static int rewardsFor(int points)
	{
		if (points < QUALIFY_POINTS)
		{
			return 0;
		}
		return BASE_REWARDS + (points - QUALIFY_POINTS) / POINTS_PER_EXTRA_REWARD;
	}

	/** Points still needed to qualify (0 once qualified). */
	public static int pointsToQualify(int points)
	{
		return Math.max(0, QUALIFY_POINTS - points);
	}

	/** Points still needed for the next reward beyond the current total (after qualifying). */
	public static int pointsToNextReward(int points)
	{
		if (points < QUALIFY_POINTS)
		{
			return QUALIFY_POINTS - points;
		}
		int next = QUALIFY_POINTS + ((points - QUALIFY_POINTS) / POINTS_PER_EXTRA_REWARD + 1) * POINTS_PER_EXTRA_REWARD;
		return next - points;
	}

	/** Points the carried roots and kindling are worth if fed as they are. */
	public static int inventoryPoints(int roots, int kindling)
	{
		return Math.max(0, roots) * ROOT_POINTS + Math.max(0, kindling) * KINDLING_POINTS;
	}

	/** Points if every carried root were fletched first (needs a knife). */
	public static int inventoryPointsIfFletched(int roots, int kindling)
	{
		return (Math.max(0, roots) + Math.max(0, kindling)) * KINDLING_POINTS;
	}
}
