package com.osrstelemetry.plugin;

/**
 * Player-facing Current Session display setting: which XP the per-skill XP
 * cards show while Current Session represents a Slayer session. Display
 * scope only -- canonical session XP, History, telemetry and Loot Tracker
 * are never affected.
 */
public enum SlayerXpScope
{
	/** Whole Slayer session (every assignment it has spanned); the original behavior. */
	ALL_TASKS("All tasks"),
	/** Only XP Spyglass tracked during the current assignment instance. */
	CURRENT_TASK("Current task");

	private final String label;

	SlayerXpScope(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
