package com.osrstelemetry.plugin.activity;

import java.util.HashSet;
import java.util.Set;

/**
 * Transient ARMED / ACTIVE / LOCKED recognition for the structured activity
 * whose location the player is in. Owns no lifecycle: it only tells the
 * classifier how much inertia in-location evidence has earned.
 *
 *  - Location ARMS (and leaving drops back to OUTSIDE). Location is never a
 *    confirmation and never starts anything.
 *  - The first compatible gameplay evidence ACTIVATES.
 *  - {@link #LOCK_DISTINCT_CONFIRMATIONS} compatible confirmations on
 *    DISTINCT game ticks LOCK. Several signals on one tick (a multi-skill
 *    XP drop, a completion plus its XP) are one confirmation.
 *
 * One instance per account (it lives in the session ClassifierContext), so
 * a restart, relog or account switch starts OUTSIDE and must re-arm.
 */
public final class StructuredActivityTracker
{
	/** Activation plus two more distinct-tick confirmations. */
	public static final int LOCK_DISTINCT_CONFIRMATIONS = 3;

	private StructuredActivityDefinition definition;
	private LocationPolicy policy;
	private RecognitionState state = RecognitionState.OUTSIDE;
	private final Set<Long> confirmationTicks = new HashSet<>();

	/**
	 * A location observation. {@code def == null} means "not in any
	 * structured-activity location". Moving between regions of the SAME
	 * activity keeps its recognition (e.g. Tempoross game -> reward cove);
	 * entering a different activity, or re-entering after leaving, re-arms.
	 */
	public void onLocation(StructuredActivityDefinition def, LocationPolicy regionPolicy)
	{
		if (def == null)
		{
			clear();
			return;
		}
		boolean sameActivity = definition != null && definition.getKey().equals(def.getKey()) && state != RecognitionState.OUTSIDE;
		definition = def;
		policy = regionPolicy;
		if (!sameActivity)
		{
			state = RecognitionState.ARMED;
			confirmationTicks.clear();
		}
	}

	/**
	 * Records one compatible confirmation for the armed activity.
	 * {@code tickKey} identifies the game tick; repeats of the same key are
	 * one confirmation.
	 */
	public void confirm(long tickKey)
	{
		if (state == RecognitionState.OUTSIDE || state == RecognitionState.LOCKED)
		{
			// LOCKED is terminal until the location changes: no need to keep
			// remembering ticks for as long as the player stays.
			return;
		}
		confirmationTicks.add(tickKey);
		if (confirmationTicks.size() >= LOCK_DISTINCT_CONFIRMATIONS)
		{
			state = RecognitionState.LOCKED;
		}
		else
		{
			state = RecognitionState.ACTIVE;
		}
	}

	public void clear()
	{
		definition = null;
		policy = null;
		state = RecognitionState.OUTSIDE;
		confirmationTicks.clear();
	}

	/** The armed activity, or null when OUTSIDE. */
	public StructuredActivityDefinition getDefinition()
	{
		return state == RecognitionState.OUTSIDE ? null : definition;
	}

	public LocationPolicy getPolicy()
	{
		return state == RecognitionState.OUTSIDE ? null : policy;
	}

	public RecognitionState getState()
	{
		return state;
	}

	public int getDistinctConfirmations()
	{
		return confirmationTicks.size();
	}
}
