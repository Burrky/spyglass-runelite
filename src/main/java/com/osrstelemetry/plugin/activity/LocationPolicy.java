package com.osrstelemetry.plugin.activity;

/**
 * How much a structured activity's location says about what the player is
 * doing there. Location only ever ARMS an activity (see
 * {@link RecognitionState}); these policies decide which in-location
 * evidence may activate it and how unexpected XP is treated once it is
 * established.
 */
public enum LocationPolicy
{
	/**
	 * The location exists only for this activity (a minigame arena, a boss
	 * instance). Any expected-skill XP activates; while the session is this
	 * activity, unexpected XP in the location is incidental (metric-only).
	 */
	EXCLUSIVE,

	/**
	 * Dominated by the activity but other content can plausibly happen
	 * there. Expected-skill XP activates; unexpected XP is only absorbed as
	 * incidental once the activity is LOCKED.
	 */
	STRONG_CONTEXT,

	/**
	 * Shared space (a lobby, a reward area, a camp). XP never activates and
	 * is never absorbed; only explicit activity completions or declared
	 * reward events count.
	 */
	NON_EXCLUSIVE
}
