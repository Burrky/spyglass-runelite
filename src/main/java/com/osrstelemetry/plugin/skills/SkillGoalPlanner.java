package com.osrstelemetry.plugin.skills;

import java.util.Locale;
import net.runelite.api.Experience;

/**
 * Pure goal arithmetic for skill companions. Exact OSRS XP table, observed
 * rates only: no default or assumed rate is ever substituted, and an ETA
 * is withheld (null) until the observation is long enough to mean
 * something.
 *
 * <ul>
 * <li>LEVEL: XP left = XP(target level) - current XP; ETA = XP left at the
 * session's observed XP/hr (which itself only exists after
 * {@link #MIN_RATE_MILLIS} of active time).</li>
 * <li>STOCKPILE: progress = what the account's BANK holds (Spyglass's own
 * bank observation -- never just this session's production); ETA = what
 * remains at the session's observed BANKED rate (resources reaching the bank
 * per active hour), and only after {@link #MIN_BANK_TRIPS} deposits of that
 * resource -- production/hr is never passed off as banked/hr.</li>
 * </ul>
 */
public final class SkillGoalPlanner
{
	public static final long MIN_RATE_MILLIS = 5L * 60_000L;
	public static final int MIN_BANK_TRIPS = 2;

	private SkillGoalPlanner()
	{
	}

	/** A level target's state. xpLeft / etaMillis null when not known. */
	public static final class LevelPlan
	{
		private final boolean reached;
		private final Long xpLeft;
		private final Long etaMillis;

		LevelPlan(boolean reached, Long xpLeft, Long etaMillis)
		{
			this.reached = reached;
			this.xpLeft = xpLeft;
			this.etaMillis = etaMillis;
		}

		public boolean isReached()
		{
			return reached;
		}

		public Long getXpLeft()
		{
			return xpLeft;
		}

		public Long getEtaMillis()
		{
			return etaMillis;
		}
	}

	/** {@code currentXp} null = unknown; {@code xpPerHour} null = no reliable rate yet. */
	public static LevelPlan level(int targetLevel, Integer currentXp, Long xpPerHour)
	{
		if (currentXp == null || currentXp < 0)
		{
			return new LevelPlan(false, null, null);
		}
		int target = Math.max(SkillGoal.MIN_LEVEL, Math.min(SkillGoal.MAX_LEVEL, targetLevel));
		long left = (long) Experience.getXpForLevel(target) - currentXp;
		if (left <= 0)
		{
			return new LevelPlan(true, 0L, 0L);
		}
		return new LevelPlan(false, left, etaMillis(left, xpPerHour == null ? null : xpPerHour.doubleValue()));
	}

	/**
	 * Banking observed for one resource this session: how much reached the
	 * bank, in how many separate deposits, and the session's active time at
	 * the latest of them (the rate's denominator: time up to the last
	 * deposit, so the walk to the next one never dilutes it).
	 */
	public static final class BankingSample
	{
		private final long quantity;
		private final int deposits;
		private final long activeMillisAtLastDeposit;

		public BankingSample(long quantity, int deposits, long activeMillisAtLastDeposit)
		{
			this.quantity = quantity;
			this.deposits = deposits;
			this.activeMillisAtLastDeposit = activeMillisAtLastDeposit;
		}

		public long getQuantity()
		{
			return quantity;
		}

		public int getDeposits()
		{
			return deposits;
		}

		public long getActiveMillisAtLastDeposit()
		{
			return activeMillisAtLastDeposit;
		}

		/** Banked per hour, or null while there are fewer than MIN_BANK_TRIPS deposits / too little time. */
		public Double perHour()
		{
			if (deposits < MIN_BANK_TRIPS || quantity <= 0 || activeMillisAtLastDeposit < MIN_RATE_MILLIS)
			{
				return null;
			}
			return quantity * 3_600_000d / activeMillisAtLastDeposit;
		}
	}

	/** A stockpile target's state. */
	public static final class StockpilePlan
	{
		private final Long stock;
		private final Long remaining;
		private final Long etaMillis;

		StockpilePlan(Long stock, Long remaining, Long etaMillis)
		{
			this.stock = stock;
			this.remaining = remaining;
			this.etaMillis = etaMillis;
		}

		/** What the bank holds (null = Spyglass has not seen the bank on this account). */
		public Long getStock()
		{
			return stock;
		}

		public Long getRemaining()
		{
			return remaining;
		}

		public Long getEtaMillis()
		{
			return etaMillis;
		}

		public boolean isReached()
		{
			return remaining != null && remaining == 0;
		}
	}

	public static StockpilePlan stockpile(int targetQuantity, Long bankStock, BankingSample banking)
	{
		if (bankStock == null)
		{
			return new StockpilePlan(null, null, null);
		}
		long remaining = Math.max(0L, targetQuantity - bankStock);
		if (remaining == 0)
		{
			return new StockpilePlan(bankStock, 0L, 0L);
		}
		return new StockpilePlan(bankStock, remaining, etaMillis(remaining, banking == null ? null : banking.perHour()));
	}

	private static Long etaMillis(long left, Double perHour)
	{
		if (perHour == null || perHour <= 0)
		{
			return null;
		}
		return (long) Math.ceil(left / perHour * 3_600_000d);
	}

	/** "~7h 23m" / "~42m" / "<1m"; null when unknown. Same shape as the Pest Control goal ETA. */
	public static String etaText(Long millis)
	{
		if (millis == null)
		{
			return null;
		}
		if (millis <= 0)
		{
			return "Reached";
		}
		long minutes = (millis + 59_999) / 60_000;
		long h = minutes / 60;
		long m = minutes % 60;
		if (h >= 100)
		{
			return String.format(Locale.ROOT, "~%dh", h);
		}
		return h == 0 ? "~" + m + "m" : String.format(Locale.ROOT, "~%dh %02dm", h, m);
	}
}
