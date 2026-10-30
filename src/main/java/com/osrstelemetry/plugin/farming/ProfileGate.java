package com.osrstelemetry.plugin.farming;

import java.util.Objects;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * Two separate questions, answered separately:
 *
 * <ol>
 *   <li><b>Ready?</b> Has the account / world metadata settled enough to trust?
 *   Logged in, a real account hash, a real world number, and the same
 *   (account, world, world types, profile) on {@link #STABLE_TICKS} consecutive
 *   ticks. An empty world-type set is ordinary for a normal world and is never
 *   on its own taken as "ready" or as "not a normal world".</li>
 *   <li><b>Which profile?</b> RuneLite's own classification
 *   ({@code RuneScapeProfileType.getCurrent(client)}). Its STANDARD result is a
 *   fallback ("no special predicate matched"), which is why it is only
 *   consulted once the metadata is ready.</li>
 * </ol>
 *
 * Persistence stays STANDARD-only (locked, canonical §22). Further exclusions
 * (Tournament, Fresh Start ...) are NOT applied here: they are an open,
 * documented question (PHASE_B_IMPLEMENTATION_PREP §4), not a silent policy.
 */
final class ProfileGate
{
	static final int STABLE_TICKS = 2;

	enum Status
	{
		NOT_LOGGED_IN,
		/** Metadata not yet trustworthy (missing, or still changing). */
		NOT_READY,
		/** Ready, and RuneLite classifies the world as a non-STANDARD profile. */
		UNSUPPORTED_PROFILE,
		/** Ready and STANDARD: capture may proceed (subject to the observer's other guards). */
		STANDARD
	}

	private String lastSignature;
	private int stableTicks;

	Status evaluate(FarmingTickInput in)
	{
		if (!in.isLoggedIn())
		{
			reset();
			return Status.NOT_LOGGED_IN;
		}
		if (!hasMetadata(in))
		{
			reset();
			return Status.NOT_READY;
		}
		String signature = signature(in);
		if (signature.equals(lastSignature))
		{
			stableTicks++;
		}
		else
		{
			lastSignature = signature;
			stableTicks = 1;
		}
		return classify(in, stableTicks);
	}

	/** What {@link #evaluate} would return for {@code in}, without changing any state. */
	Status peek(FarmingTickInput in)
	{
		if (!in.isLoggedIn())
		{
			return Status.NOT_LOGGED_IN;
		}
		if (!hasMetadata(in))
		{
			return Status.NOT_READY;
		}
		return classify(in, signature(in).equals(lastSignature) ? stableTicks + 1 : 1);
	}

	private static boolean hasMetadata(FarmingTickInput in)
	{
		return in.getAccountHash() != -1L && in.getWorld() > 0 && in.getProfileType() != null;
	}

	private static Status classify(FarmingTickInput in, int stable)
	{
		if (stable < STABLE_TICKS)
		{
			return Status.NOT_READY;
		}
		return in.getProfileType() == RuneScapeProfileType.STANDARD ? Status.STANDARD : Status.UNSUPPORTED_PROFILE;
	}

	void reset()
	{
		lastSignature = null;
		stableTicks = 0;
	}

	private static String signature(FarmingTickInput in)
	{
		return in.getAccountHash() + "|" + in.getWorld() + "|" + Objects.toString(new java.util.TreeSet<>(in.getWorldTypes())) + "|" + in.getProfileType();
	}
}
