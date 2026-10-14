package com.osrstelemetry.plugin.activity;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pest Control round lifecycle, HUD interpretation and commendation balance
 * (pure; client thread). The collector feeds it native inputs:
 * <ul>
 * <li>ROUND: entering the game island (region 10536) starts a round; leaving
 * it ends the round. A round is OBSERVED FROM START only when Spyglass saw
 * the player arrive from another region -- never on the first region seen
 * after login / plugin enable.</li>
 * <li>AVG GAME: a won round's duration = island entry to the (deduped) win
 * dialogue, in game ticks x 600 ms, only for rounds observed from start.</li>
 * <li>ROUND TIME: island entry (or first sight) to exit, win or loss: the
 * Commendations/hr denominator, so outpost waiting never counts.</li>
 * <li>HUD (native status overlay, interface 408): Void Knight health and
 * portals destroyed from its texts; ACTIVITY from the game's activity
 * varbit -- see the parse methods.</li>
 * <li>BALANCE: "You now have N Void Knight Commendation points!" (message
 * box, live-verified) and the lander overlay's points line; a win's award
 * advances a known balance until the game restates it.</li>
 * </ul>
 */
public final class PestControlGameTracker
{
	/** Game tick length (ms): round durations come from ticks, never the wall clock. */
	public static final long TICK_MILLIS = 600L;
	/** The Void Knight's hitpoints (OSRS Wiki, Void Knight (Pest Control)): the health percentage's 100%. */
	public static final int KNIGHT_MAX_HP = 200;
	public static final int PORTALS = 4;
	/** A win line arriving this soon after leaving the island still belongs to that round. */
	static final int LATE_WIN_TICKS = 10;

	private static final Pattern BALANCE_MESSAGE = Pattern.compile("You now have ([0-9,]+) Void Knight Commendation points?");
	private static final Pattern LAST_NUMBER = Pattern.compile("([0-9][0-9,]*)(?!.*[0-9])");

	private boolean inGame;
	private int entryTick;
	private boolean observedFromStart;
	private Instant startedAt;
	private Instant openedAt;
	private boolean won;
	private Integer exitTick;
	private Integer activityPercent;
	private Integer knightHealthPercent;
	private Integer portalsDestroyed;
	private Integer balance;

	public void reset()
	{
		inGame = false;
		won = false;
		exitTick = null;
		startedAt = null;
		openedAt = null;
		observedFromStart = false;
		clearHud();
		balance = null;
	}

	private void clearHud()
	{
		activityPercent = null;
		knightHealthPercent = null;
		portalsDestroyed = null;
	}

	/** The player arrived on the island. {@code observedEntry}: from another region (not the first sight after login). */
	public void onEnter(int tick, boolean observedEntry, Instant now)
	{
		inGame = true;
		entryTick = tick;
		observedFromStart = observedEntry;
		startedAt = observedEntry ? now : null;
		openedAt = now;
		won = false;
		exitTick = null;
		clearHud();
	}

	/** The player left the island: this round's in-area millis, or null when no round was open. */
	public Long onExit(int tick)
	{
		if (!inGame)
		{
			return null;
		}
		inGame = false;
		exitTick = tick;
		clearHud();
		long millis = (long) (tick - entryTick) * TICK_MILLIS;
		return millis > 0 ? millis : null;
	}

	/**
	 * A deduped win: the round's duration in whole seconds when it was
	 * observed from its start (and not already scored), else null.
	 */
	public Integer onWin(int tick)
	{
		boolean thisRound = inGame || (exitTick != null && tick - exitTick >= 0 && tick - exitTick <= LATE_WIN_TICKS);
		if (!thisRound || won || !observedFromStart)
		{
			won = won || thisRound;
			return null;
		}
		won = true;
		long seconds = (long) (tick - entryTick) * TICK_MILLIS / 1000L;
		return seconds > 0 ? (int) Math.min(Integer.MAX_VALUE, seconds) : null;
	}

	public boolean isInGame()
	{
		return inGame;
	}

	/** The native HUD values for this tick (already interpreted; null = not shown). */
	public void onHud(Integer activity, Integer knightHealth, Integer portals)
	{
		if (!inGame)
		{
			return;
		}
		activityPercent = activity;
		knightHealthPercent = knightHealth;
		portalsDestroyed = portals;
	}

	/** The current game, or null when not on the island. */
	public PestControlGameState state()
	{
		return inGame ? new PestControlGameState(activityPercent, knightHealthPercent, portalsDestroyed, startedAt, openedAt) : null;
	}

	// ---- balance ------------------------------------------------------------

	/** A game message; returns the stated balance when it is the balance line, else null. */
	public Integer onBalanceMessage(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = BALANCE_MESSAGE.matcher(message);
		if (!m.find())
		{
			return null;
		}
		Integer value = parse(m.group(1));
		if (value != null)
		{
			balance = value;
		}
		return value;
	}

	/** The lander overlay's points line ("... points: N"); returns the balance when readable. */
	public Integer onLanderPoints(String text)
	{
		if (text == null || !text.toLowerCase(java.util.Locale.ROOT).contains("point"))
		{
			return null;
		}
		Matcher m = LAST_NUMBER.matcher(text);
		Integer value = m.find() ? parse(m.group(1)) : null;
		if (value != null)
		{
			balance = value;
		}
		return value;
	}

	/** A win's award: a known balance advances (the game restates it shortly after). */
	public Integer onAward(int amount)
	{
		if (balance != null && amount > 0)
		{
			balance = balance + amount;
		}
		return balance;
	}

	/** Seed from the last balance remembered for this account (planning state). */
	public void seedBalance(Integer remembered)
	{
		if (balance == null && remembered != null && remembered >= 0)
		{
			balance = remembered;
		}
	}

	public Integer getBalance()
	{
		return balance;
	}

	/** The reward shop was opened: a purchase may change the balance without the game restating it. */
	public void forgetBalance()
	{
		balance = null;
	}

	/** Whether this text is the portal-health "0" RuneLite's own overlay treats as a destroyed portal. */
	public static boolean isPortalDead(String hitpointsText)
	{
		return hitpointsText != null && hitpointsText.replaceAll("<[^>]*>", "").trim().equals("0");
	}

	// ---- HUD interpretation (pure) ---------------------------------------------

	/**
	 * The activity meter's full value: Jagex's own activity varbit
	 * (VarbitID.PEST_ACTIVITY_COUNTER, 5662) runs 0..50 -- it starts each
	 * game at 25, caps at 50, and once it reaches 0 it stays there for the
	 * rest of the game (OSRS Wiki, RuneScape:Varbit/5662; live log
	 * 2026-10-02: 291 readings, all within 18..50, 45 of them pinned at 50).
	 */
	public static final int ACTIVITY_MAX = 50;

	/**
	 * The activity meter as the HUD shows it, 0-100%, from the activity
	 * varbit (value / 50). Null when the value is outside the varbit's
	 * range (never a guessed percentage).
	 */
	public static Integer activityPercent(int counter)
	{
		if (counter < 0 || counter > ACTIVITY_MAX)
		{
			return null;
		}
		return (int) Math.round(counter * 100.0 / ACTIVITY_MAX);
	}

	/**
	 * The knight-health text: a bare hitpoint number ("184") becomes a
	 * percentage of {@link #KNIGHT_MAX_HP}; a text that already carries
	 * "%" is used as-is. Anything else is unknown.
	 */
	public static Integer knightPercent(String text)
	{
		if (text == null)
		{
			return null;
		}
		String t = text.replaceAll("<[^>]*>", "").trim();
		if (t.matches("[0-9]{1,3}%"))
		{
			return Math.min(100, Integer.parseInt(t.substring(0, t.length() - 1)));
		}
		if (t.matches("[0-9]{1,4}"))
		{
			int hp = Integer.parseInt(t);
			return (int) Math.max(0, Math.min(100, Math.round(hp * 100.0 / KNIGHT_MAX_HP)));
		}
		return null;
	}

	private static Integer parse(String digits)
	{
		try
		{
			return Integer.parseInt(digits.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
