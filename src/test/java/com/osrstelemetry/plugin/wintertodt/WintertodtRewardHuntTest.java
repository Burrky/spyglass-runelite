package com.osrstelemetry.plugin.wintertodt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reward Hunt probability: exact cumulative chances from the verified
 * effective per-reward rates, for every supported target -- zero, one, many
 * and huge roll counts, the four-piece outfit, and the display text.
 */
public class WintertodtRewardHuntTest
{
	/** Independent reference: 1 - sum_{j<k} C(n,j) p^j (1-p)^(n-j) with plain pow. */
	private static double reference(double p, int k, int n)
	{
		double below = 0;
		double c = 1;
		for (int j = 0; j < k; j++)
		{
			below += c * Math.pow(p, j) * Math.pow(1 - p, n - j);
			c = c * (n - j) / (j + 1);
		}
		return 1 - below;
	}

	@Test
	public void zeroRolls_isZero_forEveryTarget()
	{
		for (WintertodtUnique u : WintertodtUnique.values())
		{
			assertEquals(u.name(), 0.0, WintertodtRewardHunt.chance(u, 0), 0);
			assertEquals(u.name(), 0.0, WintertodtRewardHunt.chance(u, -5), 0);
			assertEquals("0%", WintertodtRewardHunt.percent(WintertodtRewardHunt.chance(u, 0)));
		}
	}

	@Test
	public void oneRoll_isExactlyTheEffectivePerRewardRate()
	{
		for (WintertodtUnique u : WintertodtUnique.values())
		{
			double expected = u.getNeeded() == 1 ? u.chancePerReward() : 0.0;
			assertEquals(u.name(), expected, WintertodtRewardHunt.chance(u, 1), 1e-18);
		}
		assertEquals(1.0 / 5000, WintertodtRewardHunt.chance(WintertodtUnique.PHOENIX, 1), 1e-18);
	}

	@Test
	public void manyRolls_areTheExactCumulativeChance()
	{
		for (WintertodtUnique u : WintertodtUnique.values())
		{
			if (u.getNeeded() != 1)
			{
				continue;
			}
			double p = u.chancePerReward();
			for (int n : new int[]{2, 9, 40, 209, 1000, 8000})
			{
				double exact = 1 - Math.pow(1 - p, n);
				assertEquals(u.name() + " x" + n, exact, WintertodtRewardHunt.chance(u, n), exact * 1e-12);
			}
		}
		// The worked example: Phoenix, +9 session rewards: 1 - (4999/5000)^9.
		double nine = WintertodtRewardHunt.chance(WintertodtUnique.PHOENIX, 9);
		assertEquals(1 - Math.pow(4999.0 / 5000, 9), nine, 1e-15);
		assertEquals("0.18%", WintertodtRewardHunt.percent(nine));
	}

	@Test
	public void agreesWithTheInternalMilestones()
	{
		long n50 = WintertodtUnique.PHOENIX.rewardsForChance(0.5);
		assertTrue(WintertodtRewardHunt.chance(WintertodtUnique.PHOENIX, n50) >= 0.5);
		assertTrue(WintertodtRewardHunt.chance(WintertodtUnique.PHOENIX, n50 - 1) < 0.5);
		long outfit50 = WintertodtUnique.PYROMANCER_OUTFIT.rewardsForChance(0.5);
		assertTrue(WintertodtRewardHunt.chance(WintertodtUnique.PYROMANCER_OUTFIT, outfit50) >= 0.5);
		assertTrue(WintertodtRewardHunt.chance(WintertodtUnique.PYROMANCER_OUTFIT, outfit50 - 1) < 0.5);
	}

	@Test
	public void largeRollCounts_stayStableAndMonotonic()
	{
		for (WintertodtUnique u : WintertodtUnique.values())
		{
			double previous = 0;
			for (long n : new long[]{1, 10, 100, 1_000, 8_000, 100_000, 10_000_000L, 2_000_000_000L})
			{
				double c = WintertodtRewardHunt.chance(u, n);
				assertTrue(u + " x" + n + " in [0,1]: " + c, c >= 0 && c <= 1 && !Double.isNaN(c));
				assertTrue(u + " x" + n + " monotonic", c >= previous);
				previous = c;
			}
			assertEquals(u.name(), 1.0, previous, 1e-12);
		}
	}

	@Test
	public void outfit_needsFourPieces_binomialTail()
	{
		double p = WintertodtUnique.PYROMANCER_OUTFIT.chancePerReward();
		assertEquals(0.0, WintertodtRewardHunt.chance(WintertodtUnique.PYROMANCER_OUTFIT, 3), 0);
		assertEquals(Math.pow(p, 4), WintertodtRewardHunt.chance(WintertodtUnique.PYROMANCER_OUTFIT, 4), 1e-20);
		for (int n : new int[]{9, 42, 209, 600, 2000})
		{
			double ref = reference(p, 4, n);
			assertEquals("outfit x" + n, ref, WintertodtRewardHunt.chance(WintertodtUnique.PYROMANCER_OUTFIT, n), Math.max(ref * 1e-9, 1e-15));
		}
	}

	@Test
	public void cartAndSessionCounts_areIndependentInputs()
	{
		// Cart 209 (200 old + 9 this session) vs session +9: different counts, different chances.
		double cart = WintertodtRewardHunt.chance(WintertodtUnique.PHOENIX, 209);
		double session = WintertodtRewardHunt.chance(WintertodtUnique.PHOENIX, 9);
		assertEquals(1 - Math.pow(4999.0 / 5000, 209), cart, 1e-12 * cart);
		assertTrue(cart > session);
	}

	@Test
	public void percentAndRateText()
	{
		assertEquals("0%", WintertodtRewardHunt.percent(0));
		assertEquals("<0.01%", WintertodtRewardHunt.percent(0.00005));
		assertEquals("0.18%", WintertodtRewardHunt.percent(0.0017986));
		assertEquals("4.10%", WintertodtRewardHunt.percent(0.041));
		assertEquals("79.8%", WintertodtRewardHunt.percent(0.7981));
		assertEquals(">99.9%", WintertodtRewardHunt.percent(0.99996));
		assertEquals("1 / 5,000 per reward", WintertodtRewardHunt.rateText(WintertodtUnique.PHOENIX));
		assertEquals("1 / 10,002 per reward", WintertodtRewardHunt.rateText(WintertodtUnique.DRAGON_AXE));
		assertTrue(WintertodtRewardHunt.rateText(WintertodtUnique.PYROMANCER_OUTFIT).startsWith("4 pieces • 1 / "));
		assertTrue(WintertodtRewardHunt.rateText(WintertodtUnique.PYROMANCER_OUTFIT).endsWith(" each"));
	}
}
