package com.osrstelemetry.plugin;

/**
 * Guardians of the Rift: what Current Session's rift advice optimises for.
 * Interpretation only -- never changes any game or session data. Chosen in the
 * panel itself (Points · XP); remembered as a hidden config value.
 */
public enum GotrStrategy
{
	/** Even reward points, so searches are not wasted. */
	POINTS("Points"),
	/** Runecraft experience. */
	XP("XP");

	private final String label;

	GotrStrategy(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
