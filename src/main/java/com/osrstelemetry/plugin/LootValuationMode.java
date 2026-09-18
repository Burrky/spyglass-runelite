package com.osrstelemetry.plugin;

/**
 * ADDED (Current Session UI facelift, PART 10). Which price source the
 * Current Session loot section both displays and sorts by. See
 * {@link OsrsTelemetryConfig#lootValuationMode()}. Deliberately just
 * these two options, per the task's explicit "do not add unnecessary
 * valuation settings in this pass" instruction.
 */
public enum LootValuationMode
{
	GRAND_EXCHANGE("Grand Exchange"),
	HIGH_ALCH("High Alch");

	private final String label;

	LootValuationMode(String label)
	{
		this.label = label;
	}

	/** RuneLite's config UI renders enum dropdown options via toString(). */
	@Override
	public String toString()
	{
		return label;
	}
}
