package com.osrstelemetry.plugin.activity;

import java.util.Objects;

/**
 * The player's current Guardians of the Rift REWARD POINT balances (what the
 * Rewards Guardian spends), as last stated by the game itself: the end-of-game
 * "Total elemental energy: E. Total catalytic energy: C." line or the Rewards
 * Guardian "Check" answer, minus one of each per search since. Live only --
 * never persisted, never carried across logout, hop, re-enable or account
 * switch (the player could have spent points elsewhere). Distinct from
 * current-game ENERGY and from points EARNED this session.
 */
public final class GotrRewardWallet
{
	private final int elemental;
	private final int catalytic;

	private GotrRewardWallet(int elemental, int catalytic)
	{
		this.elemental = elemental;
		this.catalytic = catalytic;
	}

	/** Null unless BOTH balances are known. */
	public static GotrRewardWallet of(Integer elemental, Integer catalytic)
	{
		if (elemental == null || catalytic == null || elemental < 0 || catalytic < 0)
		{
			return null;
		}
		return new GotrRewardWallet(elemental, catalytic);
	}

	public int getElemental()
	{
		return elemental;
	}

	public int getCatalytic()
	{
		return catalytic;
	}

	/** Usable Rewards Guardian searches: each costs one point of each type. */
	public int searches()
	{
		return Math.min(elemental, catalytic);
	}

	/** The side limiting searches, or null when balanced. */
	public GotrAltar.Type limitingSide()
	{
		if (elemental == catalytic)
		{
			return null;
		}
		return elemental < catalytic ? GotrAltar.Type.ELEMENTAL : GotrAltar.Type.CATALYTIC;
	}

	/** How many points the limiting side is behind (0 when balanced). */
	public int imbalance()
	{
		return Math.abs(elemental - catalytic);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof GotrRewardWallet))
		{
			return false;
		}
		GotrRewardWallet w = (GotrRewardWallet) o;
		return w.elemental == elemental && w.catalytic == catalytic;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(elemental, catalytic);
	}

	@Override
	public String toString()
	{
		return "GotrRewardWallet{elemental=" + elemental + ", catalytic=" + catalytic + "}";
	}
}
