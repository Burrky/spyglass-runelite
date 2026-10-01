package com.osrstelemetry.plugin.activity;

/**
 * Guardians of the Rift final-energy bookkeeping (pure; client thread only).
 *
 * SCORE = this player's total energy (elemental + catalytic, varbits 13686 /
 * 13685) in a WON game -- the game's own contribution measure (it gates the
 * end-of-game XP and converts to reward points). Captured when "Amount of
 * Rifts you have closed: N" arrives, which the game prints only for a closed
 * (won) rift: final = the larger of the last value observed during the game
 * and the value at the message, so it survives the varbits resetting first.
 *
 * PROVENANCE (same rule as Tempoross): a game is FULLY OBSERVED only if its
 * energy was seen at 0 after the previous game ended -- i.e. Spyglass watched
 * it from before anything was earned. A game first seen with energy already
 * above 0 (login / re-enable mid-game) is PARTIAL: it still counts as a game
 * (the completion count is authoritative) but never enters Avg/Best.
 *
 * FAILURE: "The Great Guardian was defeated" ends the game with no rifts
 * closed message, so no score is ever produced; the game's tracking is
 * dropped so nothing it earned can leak into the next one.
 *
 * WIN (live-verified 2026-10-01, in this order on one tick): "The Great
 * Guardian successfully closed the rift!", "Amount of rifts you have closed:
 * N.", "Elemental energy attuned: E. Catalytic energy attuned: C.", "Total
 * elemental energy: E. Total catalytic energy: C.". The count line is the
 * primary, deduplicated success evidence; the "successfully closed" line
 * corroborates it and stands in for it if it is ever missing.
 */
public final class GotrGameTracker
{
	static final String FAILED_MESSAGE = "The Great Guardian was defeated";
	static final String WON_MESSAGE = "The Great Guardian successfully closed the rift";
	/**
	 * Live-verified 2026-10-01: a closed rift the player did not contribute
	 * enough to is followed by this line and NO "Amount of rifts" count --
	 * the game does not credit it, so neither does Spyglass.
	 */
	static final String NO_CONTRIBUTION_MESSAGE = "You did not contribute enough to attune";

	/** Energy was observed at 0 since the last game ended (the current game is watched from its start). */
	private boolean zeroSeen;
	/** Highest total energy observed since that zero. */
	private int gameMax;
	/** Last observed total (detects a drop = the varbits reset for a new game). */
	private Integer lastTotal;

	public void reset()
	{
		zeroSeen = false;
		gameMax = 0;
		lastTotal = null;
	}

	/** One tick of the game's own energy varbits while the GotR HUD is loaded; otherwise call nothing. */
	public void onEnergy(int elemental, int catalytic)
	{
		if (elemental < 0 || catalytic < 0)
		{
			return;
		}
		int total = elemental + catalytic;
		if (total == 0)
		{
			zeroSeen = true;
			gameMax = 0;
		}
		else if (lastTotal != null && total < lastTotal)
		{
			// Dropped without passing 0 on a tick we saw: a new game we did
			// not watch from its start.
			zeroSeen = false;
			gameMax = total;
		}
		else
		{
			gameMax = Math.max(gameMax, total);
		}
		lastTotal = total;
	}

	/** True for the game's own failure message (tags already removed). */
	public static boolean isFailure(String message)
	{
		return message != null && message.contains(FAILED_MESSAGE);
	}

	/** True for the game's own success message (tags removed; any "@mes_..@" prefix ignored). */
	public static boolean isWin(String message)
	{
		return message != null && message.contains(WON_MESSAGE);
	}

	/** True when the rift closed but the game did not credit this player (no count, no points). */
	public static boolean isNoContribution(String message)
	{
		return message != null && message.contains(NO_CONTRIBUTION_MESSAGE);
	}

	/** A lost game: nothing it earned is ever scored, and the next game must prove its own start. */
	public void onFailure()
	{
		zeroSeen = false;
		gameMax = 0;
	}

	/**
	 * A won game ("Amount of Rifts you have closed: N", already deduplicated
	 * by the caller). Returns its final energy and provenance, then ends the
	 * game's tracking.
	 */
	public Result onCompletion(Integer riftsClosed, int elementalNow, int catalyticNow)
	{
		int now = elementalNow < 0 || catalyticNow < 0 ? 0 : elementalNow + catalyticNow;
		int finalEnergy = Math.max(gameMax, now);
		Result r = new Result(riftsClosed, finalEnergy > 0 ? finalEnergy : null, zeroSeen && finalEnergy > 0);
		zeroSeen = false;
		gameMax = 0;
		return r;
	}

	public static final class Result
	{
		private final Integer riftsClosed;
		private final Integer totalEnergy;
		private final boolean observedFromStart;

		Result(Integer riftsClosed, Integer totalEnergy, boolean observedFromStart)
		{
			this.riftsClosed = riftsClosed;
			this.totalEnergy = totalEnergy;
			this.observedFromStart = observedFromStart;
		}

		/** The game's own rifts-closed count, or null when only the "successfully closed" line was seen. */
		public Integer getRiftsClosed()
		{
			return riftsClosed;
		}

		/** Final total energy, or null when unknown / 0. */
		public Integer getTotalEnergy()
		{
			return totalEnergy;
		}

		public boolean isObservedFromStart()
		{
			return observedFromStart;
		}
	}
}
