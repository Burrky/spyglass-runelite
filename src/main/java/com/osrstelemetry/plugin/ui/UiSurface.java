package com.osrstelemetry.plugin.ui;

/** The panel's independently refreshable surfaces (one per page). */
enum UiSurface
{
	/** Home dashboard: the Farming card's summary refreshes; the other cards are shells. */
	HOME,
	/** Home → Farming: Browse / Preview from the Farming model. */
	FARMING,
	/** Home → Kingdom. */
	KINGDOM,
	/** Home → Dailies. */
	DAILIES,
	BIRDHOUSES,
	/** Home → Text size &amp; contrast. */
	APPEARANCE,
	CURRENT_SESSION,
	LOOT_TRACKER,
	HISTORY
}
