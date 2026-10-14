package com.osrstelemetry.plugin.wintertodt;

/**
 * Reward Cart uniques, in the order the game rolls them per reward (OSRS
 * Wiki "Reward Cart", 2026-10-02): "a drop will be rolled for until the roll
 * results in a success" -- the first success wins, so a later item's real
 * chance per reward is its rate times the chance every earlier roll missed.
 * <p>
 * Duplicate rules (same page): owning 3 warm gloves turns that drop into a
 * magic seed; owning 3 bruma torches (bank / inventory / POH) into torstol
 * seeds; a pyromancer drop is the piece the player has least of (ties:
 * garb, hood, robe, boots). The Phoenix rate rises at 200M Firemaking XP
 * (not modelled: the base rate is the conservative one).
 */
public enum WintertodtUnique
{
	PHOENIX("Phoenix", 5000, 1),
	DRAGON_AXE("Dragon axe", 10000, 1),
	TOME_OF_FIRE("Tome of fire", 1000, 1),
	WARM_GLOVES("Warm gloves", 150, 1),
	BRUMA_TORCH("Bruma torch", 150, 1),
	PYROMANCER_PIECE("Pyromancer piece", 150, 1),
	PYROMANCER_OUTFIT("Pyromancer outfit (4)", 150, 4),
	BURNT_PAGE("Burnt page", 45, 1);

	private final String label;
	private final int oneIn;
	/** Successes needed (the outfit needs four pieces: each drop is a piece you lack). */
	private final int needed;

	WintertodtUnique(String label, int oneIn, int needed)
	{
		this.label = label;
		this.oneIn = oneIn;
		this.needed = needed;
	}

	public String getLabel()
	{
		return label;
	}

	public int getOneIn()
	{
		return oneIn;
	}

	public int getNeeded()
	{
		return needed;
	}

	@Override
	public String toString()
	{
		return label;
	}

	/** The rolled table in order (the outfit shares the pyromancer roll). */
	private static final WintertodtUnique[] ROLL_ORDER = {PHOENIX, DRAGON_AXE, TOME_OF_FIRE, WARM_GLOVES, BRUMA_TORCH, PYROMANCER_PIECE, BURNT_PAGE};

	/** This item's real chance per reward: its rate times the chance every earlier roll in the table missed. */
	public double chancePerReward()
	{
		WintertodtUnique roll = this == PYROMANCER_OUTFIT ? PYROMANCER_PIECE : this;
		double missedEarlier = 1.0;
		for (WintertodtUnique u : ROLL_ORDER)
		{
			if (u == roll)
			{
				return missedEarlier / u.oneIn;
			}
			missedEarlier *= 1.0 - 1.0 / u.oneIn;
		}
		throw new IllegalStateException(name());
	}

	/**
	 * The fewest rewards after which the goal has at least {@code probability}
	 * chance of being done ({@link #getNeeded()} successes). A probability
	 * milestone, never a promise.
	 */
	public long rewardsForChance(double probability)
	{
		return rewardsForChance(chancePerReward(), needed, probability);
	}

	/** Smallest n with P(Binomial(n, p) >= k) >= q. */
	static long rewardsForChance(double p, int k, double q)
	{
		if (p <= 0 || q <= 0)
		{
			return 0;
		}
		if (p >= 1)
		{
			return k;
		}
		if (k == 1)
		{
			return (long) Math.ceil(Math.log(1.0 - q) / Math.log(1.0 - p));
		}
		// pmf[j] = P(X = j) for j < k, updated one trial at a time.
		double[] pmf = new double[k];
		pmf[0] = 1.0;
		for (long n = 1; n < 10_000_000L; n++)
		{
			for (int j = k - 1; j >= 1; j--)
			{
				pmf[j] = pmf[j] * (1 - p) + pmf[j - 1] * p;
			}
			pmf[0] *= 1 - p;
			double below = 0;
			for (double v : pmf)
			{
				below += v;
			}
			if (1.0 - below >= q)
			{
				return n;
			}
		}
		return -1;
	}
}
