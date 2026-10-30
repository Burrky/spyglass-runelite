package com.osrstelemetry.plugin.account;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * Decides, per tick, whether account capability may be collected and for whom
 * (same rules as Farming's gate, canonical §22): logged in, a real account
 * hash and world, the same (account, world, world types, profile) on
 * {@link #STABLE_TICKS} consecutive ticks, RuneLite's profile classification
 * STANDARD, and not a world type whose persistence is suspended. Anything else
 * yields no owner, so incomplete metadata can never produce capabilities.
 * Pure: the adapter supplies the client values.
 */
public final class AccountProfileGate
{
	public static final int STABLE_TICKS = 2;
	/** Same temporary guard as Farming: these worlds share the STANDARD profile type but are not the main game. */
	public static final Set<WorldType> SUSPENDED_WORLD_TYPES =
		Collections.unmodifiableSet(EnumSet.of(WorldType.TOURNAMENT_WORLD, WorldType.FRESH_START_WORLD));

	public enum Status
	{
		NOT_LOGGED_IN,
		NOT_READY,
		NOT_STANDARD,
		STANDARD
	}

	private String lastSignature;
	private int stableTicks;

	public Status evaluate(boolean loggedIn, long accountHash, int world, Set<WorldType> worldTypes, RuneScapeProfileType profile)
	{
		if (!loggedIn)
		{
			reset();
			return Status.NOT_LOGGED_IN;
		}
		if (accountHash == -1L || world <= 0 || profile == null || worldTypes == null)
		{
			reset();
			return Status.NOT_READY;
		}
		String signature = accountHash + "|" + world + "|" + new TreeSet<>(worldTypes) + "|" + profile;
		if (signature.equals(lastSignature))
		{
			stableTicks++;
		}
		else
		{
			lastSignature = signature;
			stableTicks = 1;
		}
		if (stableTicks < STABLE_TICKS)
		{
			return Status.NOT_READY;
		}
		for (WorldType t : worldTypes)
		{
			if (SUSPENDED_WORLD_TYPES.contains(t))
			{
				return Status.NOT_STANDARD;
			}
		}
		return profile == RuneScapeProfileType.STANDARD ? Status.STANDARD : Status.NOT_STANDARD;
	}

	public void reset()
	{
		lastSignature = null;
		stableTicks = 0;
	}
}
