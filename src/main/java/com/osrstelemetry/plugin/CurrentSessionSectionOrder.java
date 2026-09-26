package com.osrstelemetry.plugin;

/**
 * Player-facing display setting: the vertical order of Current Session's
 * XP (skill cards) and LOOT sections. Presentation only -- read live by
 * CurrentSessionView on every render; affects nothing else (not the Loot
 * tab, not History, not any telemetry/session logic).
 */
public enum CurrentSessionSectionOrder
{
	XP_FIRST("XP First"),
	LOOT_FIRST("Loot First");

	private final String label;

	CurrentSessionSectionOrder(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
