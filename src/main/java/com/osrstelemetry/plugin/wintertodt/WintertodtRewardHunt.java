package com.osrstelemetry.plugin.wintertodt;

import java.util.Locale;

/**
 * Reward Hunt math (pure): what a number of Reward Cart rewards is worth for
 * one unique, from the verified effective per-reward rate
 * ({@link WintertodtUnique#chancePerReward()}, which already folds in the
 * roll order). Rewards are rolled when the cart is SEARCHED, so every value
 * here is the chance those rewards represent once searched -- never a
 * statement that something was already rolled.
 * <p>
 * Cart chance uses the cart balance; session chance uses the rewards this
 * session earned. Neither ever uses the number of search clicks.
 */
public final class WintertodtRewardHunt
{
	private WintertodtRewardHunt()
	{
	}

	/**
	 * P(at least {@link WintertodtUnique#getNeeded()} successes in {@code rolls}
	 * rewards). One needed: exactly {@code 1 - (1 - p)^n}, computed as
	 * {@code -expm1(n * log1p(-p))} so it stays exact for tiny p and huge n.
	 * More needed (the four-piece outfit): the binomial upper tail.
	 */
	public static double chance(WintertodtUnique unique, long rolls)
	{
		return chance(unique.chancePerReward(), unique.getNeeded(), rolls);
	}

	static double chance(double p, int needed, long rolls)
	{
		if (rolls <= 0 || p <= 0 || rolls < needed)
		{
			return 0.0;
		}
		if (p >= 1)
		{
			return 1.0;
		}
		if (needed <= 1)
		{
			return -Math.expm1(rolls * Math.log1p(-p));
		}
		// pmf(0) = (1-p)^n, then pmf(j+1) = pmf(j) * (n-j)/(j+1) * p/(1-p).
		double ratio = p / (1.0 - p);
		double pmf = Math.exp(rolls * Math.log1p(-p));
		double below = 0.0;
		for (int j = 0; j < needed; j++)
		{
			below += pmf;
			pmf *= (rolls - j) / (double) (j + 1) * ratio;
		}
		if (below < 0.5)
		{
			return Math.max(0.0, Math.min(1.0, 1.0 - below));
		}
		// Small chance: sum the upper tail itself (1 - below would lose the digits).
		double tail = 0.0;
		for (long j = needed; j <= rolls; j++)
		{
			tail += pmf;
			if (pmf < tail * 1e-17 || pmf == 0.0)
			{
				break;
			}
			pmf *= (rolls - j) / (double) (j + 1) * ratio;
		}
		return Math.max(0.0, Math.min(1.0, tail));
	}

	/** "0%", "<0.01%", "0.18%", "12.3%", "99.9%", ">99.9%". */
	public static String percent(double chance)
	{
		if (chance <= 0)
		{
			return "0%";
		}
		double pct = chance * 100.0;
		if (pct < 0.01)
		{
			return "<0.01%";
		}
		if (pct < 10)
		{
			return String.format(Locale.ROOT, "%.2f%%", pct);
		}
		if (pct >= 99.95)
		{
			return ">99.9%";
		}
		return String.format(Locale.ROOT, "%.1f%%", pct);
	}

	/** "1 / 5,000 per reward" -- or "4 pieces • 1 / 152 each" for the outfit (the effective rate, roll order included). */
	public static String rateText(WintertodtUnique unique)
	{
		long oneIn = Math.round(1.0 / unique.chancePerReward());
		return unique.getNeeded() > 1
			? String.format(Locale.ROOT, "%d pieces • 1 / %,d each", unique.getNeeded(), oneIn)
			: String.format(Locale.ROOT, "1 / %,d per reward", oneIn);
	}
}
