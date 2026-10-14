package com.osrstelemetry.plugin.activity;

import java.time.Instant;
import java.util.Objects;

/**
 * The Pest Control game in progress, as the game itself shows it (native
 * status overlay, interface 408 -- see PestControlGameTracker for each
 * field's source). Every field is null when the overlay does not expose
 * it; nothing is ever estimated. Exists only while the player is on the
 * game island.
 */
public final class PestControlGameState
{
	private final Integer activityPercent;
	private final Integer knightHealthPercent;
	private final Integer portalsDestroyed;
	private final Instant startedAt;
	private final Instant openedAt;

	public PestControlGameState(Integer activityPercent, Integer knightHealthPercent, Integer portalsDestroyed, Instant startedAt)
	{
		this(activityPercent, knightHealthPercent, portalsDestroyed, startedAt, startedAt);
	}

	public PestControlGameState(Integer activityPercent, Integer knightHealthPercent, Integer portalsDestroyed, Instant startedAt,
		Instant openedAt)
	{
		this.activityPercent = activityPercent;
		this.knightHealthPercent = knightHealthPercent;
		this.portalsDestroyed = portalsDestroyed;
		this.startedAt = startedAt;
		this.openedAt = openedAt;
	}

	/** The player's activity bar, 0-100 (rewards need it above 0); null = not shown. */
	public Integer getActivityPercent()
	{
		return activityPercent;
	}

	/** The Void Knight's health, 0-100% of its 200 hitpoints; null = not shown. */
	public Integer getKnightHealthPercent()
	{
		return knightHealthPercent;
	}

	/** Portals destroyed so far, 0-4; null = not shown. */
	public Integer getPortalsDestroyed()
	{
		return portalsDestroyed;
	}

	/** When Spyglass saw this game start (island entry); null when it joined mid-game (login / enable on the island). */
	public Instant getStartedAt()
	{
		return startedAt;
	}

	/**
	 * When Spyglass first saw the player on the island this round (entry,
	 * or the first sight after login): the live part of the round-time
	 * (Commendations/hr) denominator. Never null while a game exists.
	 */
	public Instant getOpenedAt()
	{
		return openedAt;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof PestControlGameState))
		{
			return false;
		}
		PestControlGameState s = (PestControlGameState) o;
		return Objects.equals(activityPercent, s.activityPercent) && Objects.equals(knightHealthPercent, s.knightHealthPercent)
			&& Objects.equals(portalsDestroyed, s.portalsDestroyed) && Objects.equals(startedAt, s.startedAt)
			&& Objects.equals(openedAt, s.openedAt);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(activityPercent, knightHealthPercent, portalsDestroyed, startedAt, openedAt);
	}

	@Override
	public String toString()
	{
		return "PestControlGameState{activity=" + activityPercent + "%, knight=" + knightHealthPercent + "%, portals="
			+ portalsDestroyed + ", startedAt=" + startedAt + "}";
	}
}
