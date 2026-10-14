package com.osrstelemetry.plugin.activity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The Wintertodt as Spyglass can see it right now (presentation only, never
 * persisted). Every value is null when the game does not show it; nothing
 * is estimated. Exists only while the player is in the Wintertodt arena.
 */
public final class WintertodtState
{
	public enum Phase
	{
		/** A round is in progress (the energy meter is up and the respawn timer is 0). */
		ROUND,
		/** Between rounds (the respawn timer runs, or the energy meter is not showing a live value). */
		INTERMISSION
	}

	/** What the player is doing, from corroborated evidence only (never a guess). */
	public enum Action
	{
		CHOPPING("Chopping roots"),
		FLETCHING("Fletching kindling"),
		FEEDING_ROOTS("Feeding roots"),
		FEEDING_KINDLING("Feeding kindling"),
		LIGHTING("Lighting brazier"),
		REPAIRING("Repairing brazier"),
		HEALING("Healing pyromancer");

		private final String label;

		Action(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private final Phase phase;
	private final Integer warmthPercent;
	private final Integer energyPercent;
	private final Integer points;
	private final Integer countdownSeconds;
	private final Action action;
	private final Instant roundStartedAt;
	private final Instant roundOpenedAt;
	private final int roots;
	private final int kindling;
	private final boolean knife;
	private final Integer cartRewards;
	private final Map<String, Integer> roundActions;

	public WintertodtState(Phase phase, Integer warmthPercent, Integer energyPercent, Integer points, Integer countdownSeconds,
		Action action, Instant roundStartedAt, Instant roundOpenedAt, int roots, int kindling, boolean knife, Integer cartRewards)
	{
		this(phase, warmthPercent, energyPercent, points, countdownSeconds, action, roundStartedAt, roundOpenedAt, roots, kindling,
			knife, cartRewards, null);
	}

	/**
	 * @param roundActions this round's corroborated action counts so far (the
	 * round tracker's own keys, e.g. kindling_fed / braziers_repaired /
	 * pyromancers_healed); null = none
	 */
	public WintertodtState(Phase phase, Integer warmthPercent, Integer energyPercent, Integer points, Integer countdownSeconds,
		Action action, Instant roundStartedAt, Instant roundOpenedAt, int roots, int kindling, boolean knife, Integer cartRewards,
		Map<String, Integer> roundActions)
	{
		this.roundActions = roundActions == null || roundActions.isEmpty() ? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(roundActions));
		this.phase = phase;
		this.warmthPercent = warmthPercent;
		this.energyPercent = energyPercent;
		this.points = points;
		this.countdownSeconds = countdownSeconds;
		this.action = action;
		this.roundStartedAt = roundStartedAt;
		this.roundOpenedAt = roundOpenedAt;
		this.roots = roots;
		this.kindling = kindling;
		this.knife = knife;
		this.cartRewards = cartRewards;
	}

	public Phase getPhase()
	{
		return phase;
	}

	/** The player's Warmth, 0-100, from the game's own HUD; null = not shown. */
	public Integer getWarmthPercent()
	{
		return warmthPercent;
	}

	/** The Wintertodt's energy, 0-100, from the game's own HUD; null = not shown. */
	public Integer getEnergyPercent()
	{
		return energyPercent;
	}

	/** This round's points, from the game's own HUD; null = not shown. */
	public Integer getPoints()
	{
		return points;
	}

	/** Seconds until the next round (the game's respawn timer); null outside an intermission or unknown. */
	public Integer getCountdownSeconds()
	{
		return countdownSeconds;
	}

	/** The current action, or null when it cannot be told confidently. */
	public Action getAction()
	{
		return action;
	}

	/** When Spyglass saw this round start; null when it joined part-way (or between rounds). */
	public Instant getRoundStartedAt()
	{
		return roundStartedAt;
	}

	/** When Spyglass first saw this round (start, or the first sight of it); null between rounds. */
	public Instant getRoundOpenedAt()
	{
		return roundOpenedAt;
	}

	public int getRoots()
	{
		return roots;
	}

	public int getKindling()
	{
		return kindling;
	}

	public boolean hasKnife()
	{
		return knife;
	}

	/**
	 * Rewards waiting in the Reward Cart per the game's reward-pool varbit
	 * (WINT_REWARD_POOL; live-verified 2026-10-02: 0 -> 3 -> 6 -> 9 matching the
	 * game's "You're now owed N rewards"). A balance, never session-earned rewards.
	 */
	public Integer getCartRewards()
	{
		return cartRewards;
	}

	/** This round's corroborated action counts so far (presentation only; flushed into the session at round end). */
	public Map<String, Integer> getRoundActions()
	{
		return roundActions;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof WintertodtState))
		{
			return false;
		}
		WintertodtState s = (WintertodtState) o;
		return phase == s.phase && Objects.equals(warmthPercent, s.warmthPercent) && Objects.equals(energyPercent, s.energyPercent)
			&& Objects.equals(points, s.points) && Objects.equals(countdownSeconds, s.countdownSeconds) && action == s.action
			&& Objects.equals(roundStartedAt, s.roundStartedAt) && Objects.equals(roundOpenedAt, s.roundOpenedAt)
			&& roots == s.roots && kindling == s.kindling && knife == s.knife && Objects.equals(cartRewards, s.cartRewards)
			&& roundActions.equals(s.roundActions);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(phase, warmthPercent, energyPercent, points, countdownSeconds, action, roundStartedAt, roundOpenedAt,
			roots, kindling, knife, cartRewards, roundActions);
	}

	@Override
	public String toString()
	{
		return "WintertodtState{" + phase + " warmth=" + warmthPercent + " energy=" + energyPercent + " points=" + points
			+ " countdown=" + countdownSeconds + " action=" + action + " roots=" + roots + " kindling=" + kindling + "}";
	}
}
