package com.osrstelemetry.plugin.activity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardians of the Rift reward points EARNED this session (pure; client
 * thread only).
 *
 * The game keeps two separate reward-point balances -- elemental and
 * catalytic -- that it never exposes as a varbit. They are only visible as:
 * <ul>
 * <li>the end-of-game game message "Total elemental energy: E. Total
 * catalytic energy: C." -- both balances AFTER that game's award;</li>
 * <li>the Rewards Guardian's "Check" answer "You have C catalytic energy and
 * E elemental energy..." -- both balances now;</li>
 * <li>each Rewards Guardian search ("You found some loot: ..."), which costs
 * one point of each.</li>
 * </ul>
 * (Message texts corroborated across independent Plugin Hub plugins; used
 * here as evidence of the game's text only -- Spyglass depends on none.)
 *
 * POINTS EARNED PER GAME (live-verified 2026-10-01): the game states them
 * itself, right before the totals: "Elemental energy attuned: E. Catalytic
 * energy attuned: C." (2 / 3 in the verified game, whose energy was 207 /
 * 283 -- energy/100 with the remainder rounded by chance). When that line
 * was seen, it IS the game's award -- no baseline needed, so the first game
 * of a session is counted exactly. Only without it does earned fall back to
 * positive changes of the observed balance, like Tempoross permits:
 * <ul>
 * <li>An unknown balance is only BASELINED by the first observation (login,
 * re-enable, account switch): a pre-session balance is never counted, and
 * the first game of a session whose balance was never seen before it is
 * undercounted, never overcounted.</li>
 * <li>A search moves the baseline down by one each (spending never reduces
 * "earned").</li>
 * <li>Each currency is tracked independently.</li>
 * </ul>
 */
public final class GotrRewardPointTracker
{
	static final Pattern TOTALS = Pattern.compile(
		"Total elemental energy:\\s*([\\d,]+)\\.?\\s*Total catalytic energy:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);
	static final Pattern ATTUNED = Pattern.compile(
		"Elemental energy attuned:\\s*([\\d,]+)\\.?\\s*Catalytic energy attuned:\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);
	static final Pattern CHECK = Pattern.compile(
		"You have ([\\d,]+) catalytic energy and ([\\d,]+) elemental energy", Pattern.CASE_INSENSITIVE);

	private Integer elemental;
	private Integer catalytic;
	/** This game's award from the "attuned" line, waiting for the totals line that follows it. */
	private Integer attunedElemental;
	private Integer attunedCatalytic;

	public void reset()
	{
		elemental = null;
		catalytic = null;
		attunedElemental = null;
		attunedCatalytic = null;
	}

	/** The reward wallet when both balances are known, else null. */
	public GotrRewardWallet wallet()
	{
		return GotrRewardWallet.of(elemental, catalytic);
	}

	/** End-of-game totals message (tags removed); null when the message is not one. */
	public Gains onGameMessage(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher attuned = ATTUNED.matcher(message);
		if (attuned.find())
		{
			attunedElemental = parse(attuned.group(1));
			attunedCatalytic = parse(attuned.group(2));
			return null;
		}
		Matcher m = TOTALS.matcher(message);
		if (!m.find())
		{
			return null;
		}
		int e = parse(m.group(1));
		int c = parse(m.group(2));
		Gains gains = attunedElemental != null
			? new Gains(attunedElemental, attunedCatalytic, e, c)
			: new Gains(gained(elemental, e), gained(catalytic, c), e, c);
		attunedElemental = null;
		attunedCatalytic = null;
		elemental = e;
		catalytic = c;
		return gains;
	}

	/** Rewards Guardian "Check" text (chat or message box); baseline only. True when it matched. */
	public boolean onCheckText(String text)
	{
		if (text == null)
		{
			return false;
		}
		Matcher m = CHECK.matcher(text.replace("<br>", " "));
		if (!m.find())
		{
			return false;
		}
		catalytic = parse(m.group(1));
		elemental = parse(m.group(2));
		return true;
	}

	/** One Rewards Guardian search: one point of each spent (baseline only). */
	public void onSearch()
	{
		elemental = elemental == null ? null : Math.max(0, elemental - 1);
		catalytic = catalytic == null ? null : Math.max(0, catalytic - 1);
	}

	Integer getElementalBalance()
	{
		return elemental;
	}

	Integer getCatalyticBalance()
	{
		return catalytic;
	}

	private static int gained(Integer known, int now)
	{
		return known == null ? 0 : Math.max(0, now - known);
	}

	private static int parse(String digits)
	{
		try
		{
			return Integer.parseInt(digits.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	public static final class Gains
	{
		private final int elementalGained;
		private final int catalyticGained;
		private final int elementalBalance;
		private final int catalyticBalance;

		Gains(int elementalGained, int catalyticGained, int elementalBalance, int catalyticBalance)
		{
			this.elementalGained = elementalGained;
			this.catalyticGained = catalyticGained;
			this.elementalBalance = elementalBalance;
			this.catalyticBalance = catalyticBalance;
		}

		public int getElementalGained()
		{
			return elementalGained;
		}

		public int getCatalyticGained()
		{
			return catalyticGained;
		}

		public int getElementalBalance()
		{
			return elementalBalance;
		}

		public int getCatalyticBalance()
		{
			return catalyticBalance;
		}
	}
}
