package com.osrstelemetry.plugin.navigation;

/**
 * What a route question established (canonical §28.11). Only {@link #FOUND} carries a route; the others are kept apart
 * because they lead to different fallbacks, and none of them is shown to the player as such.
 */
public enum NavOutcome
{
	/** A usable route. */
	FOUND,
	/** Proven: no route exists in the modelled graph with this access (the search explored everything reachable). */
	UNREACHABLE,
	/** The search stopped at its node budget: a route may exist; nothing is proven. */
	BUDGET_EXHAUSTED,
	/** Proven: every route costs more than the journey it was compared with (walking loses to the teleport). */
	EXCEEDS_COMPARISON,
	/** The data needed is not there (not generated, not loaded yet, or still being computed for this account). */
	DATA_UNAVAILABLE
}
