package com.osrstelemetry.plugin.activity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, client-thread bookkeeping behind Tempoross's session-summary
 * evidence, fed only with the game's own native state (the points and
 * reward-permit varbits, whether the game HUD interface is loaded, the
 * location, and the kill-count game message). No RuneLite plugin, overlay
 * or UI is involved, and nothing here touches session state: the collector
 * turns the results into Spyglass events.
 *
 * FINAL POINTS. There is no authoritative "game started" signal, so each
 * game's provenance is decided from what was actually observed:
 *  - the HUD interface appearing (or the points varbit resetting) with
 *    points at 0 marks the game OBSERVED FROM START;
 *  - first seeing a game with points already above 0 (plugin enabled /
 *    login mid-game) marks it PARTIAL.
 * On "Your Tempoross kill count is: N" (success only -- a failed game has
 * no such message) the game's final points are the last observed value
 * (points only ever rise within a game; the varbit may already have reset
 * by then, so the larger of the two is used). Only an observed-from-start
 * game with a known, positive final value is trusted for Avg/Best; the
 * completion itself still counts as a game either way. Duplicate kill
 * counts are ignored.
 *
 * REWARD PERMITS. TEMPOROSS_REWARDPERMITS is the player's current permit
 * balance at the reward pool: it rises when a game awards permits and
 * falls when they are spent. "Earned" is therefore the sum of positive
 * changes observed inside the Tempoross location -- a spend only moves the
 * baseline down. The first value seen (after login / re-enable / account
 * switch, and on each entry to the location) is only a baseline, and the
 * first SETTLE_TICKS ticks after a reset only re-baseline, so the login
 * sync of an existing balance is never mistaken for a gain. Undercounting
 * a gain in that window is possible; overcounting is not.
 */
public final class TemporossGameTracker
{
	/** Ticks after a reset during which permit reads only set the baseline. */
	public static final int SETTLE_TICKS = 5;

	private static final Pattern KILL_COUNT = Pattern.compile("Your Tempoross kill count is: ([\\d,]+)");

	/** Last observed points of the game in view (null = no game observed). */
	private Integer lastPoints;
	private boolean observedFromStart;
	private boolean hudWasLoaded;
	private Integer lastScoredKillCount;

	private Integer permitBaseline;
	private int settleTicksLeft = SETTLE_TICKS;

	/** Login / logout / hop / plugin re-enable / account switch: forget everything observed. */
	public void reset()
	{
		clearGame();
		hudWasLoaded = false;
		permitBaseline = null;
		settleTicksLeft = SETTLE_TICKS;
		lastScoredKillCount = null;
	}

	private void clearGame()
	{
		lastPoints = null;
		observedFromStart = false;
	}

	/**
	 * One game tick while logged in.
	 *
	 * @param inTempoross the player is in a Tempoross region (arena or reward cove)
	 * @param hudLoaded   the Tempoross game HUD interface is loaded (a game is in progress)
	 * @param points      TEMPOROSS_CURRENT_POINTS
	 * @param permits     TEMPOROSS_REWARDPERMITS
	 * @return permits gained this tick (&gt; 0), or 0
	 */
	public int onTick(boolean inTempoross, boolean hudLoaded, int points, int permits)
	{
		if (!inTempoross)
		{
			clearGame();
			hudWasLoaded = false;
			permitBaseline = null;
			settleTicksLeft = Math.max(0, settleTicksLeft - 1);
			return 0;
		}
		observePoints(hudLoaded, points);
		return observePermits(permits);
	}

	private void observePoints(boolean hudLoaded, int points)
	{
		if (!hudLoaded)
		{
			// Keep the last value: the kill-count message can arrive after
			// the HUD closes. The next game's HUD starts a fresh observation.
			hudWasLoaded = false;
			return;
		}
		if (!hudWasLoaded || lastPoints == null)
		{
			hudWasLoaded = true;
			lastPoints = points;
			observedFromStart = points == 0;
			return;
		}
		if (points == 0 && lastPoints > 0)
		{
			// The game reset its points: a new game seen from its start.
			lastPoints = 0;
			observedFromStart = true;
			return;
		}
		if (points < lastPoints)
		{
			// Never expected within one game: treat as a game we did not see start.
			lastPoints = points;
			observedFromStart = false;
			return;
		}
		lastPoints = points;
	}

	private int observePermits(int permits)
	{
		if (settleTicksLeft > 0)
		{
			settleTicksLeft--;
			permitBaseline = permits;
			return 0;
		}
		if (permitBaseline == null)
		{
			permitBaseline = permits;
			return 0;
		}
		int gained = permits - permitBaseline;
		permitBaseline = permits;
		return Math.max(0, gained);
	}

	/**
	 * A game message. Returns the completed game's result when it is the
	 * Tempoross kill-count message for a new kill count, else null.
	 *
	 * @param currentPoints TEMPOROSS_CURRENT_POINTS at the message
	 */
	public Result onGameMessage(String message, int currentPoints)
	{
		Integer killCount = killCountOf(message);
		if (killCount == null)
		{
			return null;
		}
		if (lastScoredKillCount != null && killCount <= lastScoredKillCount)
		{
			return null;
		}
		lastScoredKillCount = killCount;
		int best = Math.max(lastPoints == null ? 0 : lastPoints, currentPoints);
		Integer finalPoints = best > 0 ? best : null;
		Result result = new Result(killCount, finalPoints, observedFromStart && finalPoints != null);
		// The next game must prove its own start.
		clearGame();
		return result;
	}

	static Integer killCountOf(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = KILL_COUNT.matcher(message);
		if (!m.find())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** One successful game: its kill count, final points (null = unknown), and whether they are trusted. */
	public static final class Result
	{
		private final int killCount;
		private final Integer points;
		private final boolean observedFromStart;

		Result(int killCount, Integer points, boolean observedFromStart)
		{
			this.killCount = killCount;
			this.points = points;
			this.observedFromStart = observedFromStart;
		}

		public int getKillCount()
		{
			return killCount;
		}

		public Integer getPoints()
		{
			return points;
		}

		public boolean isObservedFromStart()
		{
			return observedFromStart;
		}
	}
}
