package com.osrstelemetry.plugin.farming;

/**
 * What a decoded patch value says. Data vocabulary, never player wording.
 *
 * UNKNOWN is a value the decoder has no row for (new content or a decoder
 * gap). It is never EMPTY: an unknown value says nothing about the patch.
 */
public enum PatchCondition
{
	/** Raked clear (weed level 0). */
	EMPTY,
	/** Weeds present (level 1-3). */
	WEEDS,
	GROWING,
	/** A tree waiting for its health check (RuneLite reports this as growing at the last stage). */
	CHECK_HEALTH,
	HARVESTABLE,
	/** Fully grown with nothing to harvest: a checked spirit tree, or a scarecrow in a flower patch. */
	GROWN,
	DISEASED,
	DEAD,
	UNKNOWN;

	/** A crop is in the patch (its produce may still be unknown, e.g. a dead herb). */
	public boolean holdsCrop()
	{
		return this == GROWING || this == CHECK_HEALTH || this == HARVESTABLE || this == GROWN || this == DISEASED || this == DEAD;
	}

	/** Nothing planted: empty or weedy. */
	public boolean isBare()
	{
		return this == EMPTY || this == WEEDS;
	}
}
