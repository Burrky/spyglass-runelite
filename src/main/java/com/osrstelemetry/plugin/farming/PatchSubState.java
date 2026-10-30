package com.osrstelemetry.plugin.farming;

/** Distinctions inside a condition that RuneLite's own PatchState collapses. */
public enum PatchSubState
{
	NONE,
	/** A harvestable tree still standing ("Chop down"). */
	STANDING,
	/** A chopped tree's stump ("Clear"). */
	STUMP
}
