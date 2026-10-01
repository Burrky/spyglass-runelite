package com.osrstelemetry.plugin.activity;

/**
 * Recognition state of the structured activity the player is standing in.
 * Transient (never persisted): after a restart or relog the location
 * simply re-arms and real evidence has to activate it again.
 */
public enum RecognitionState
{
	/** Not in any registered structured-activity location. */
	OUTSIDE,
	/** In the location; nothing has happened yet. Never starts a session. */
	ARMED,
	/** First compatible gameplay evidence observed in the location. */
	ACTIVE,
	/** Enough distinct-tick confirmations for strong inertia. */
	LOCKED
}
