package com.osrstelemetry.plugin.session;

/**
 * Exactly the
 * three states approved in the player-facing UI blueprint's session
 * lifecycle design — no additional states, no
 * "PAUSED"/"IDLE" states invented here. Transitions between these
 * three are owned entirely by SessionLifecycleEngine; nothing outside
 * the session package should construct a transition path that engine
 * doesn't itself produce (see Session's package-private setters).
 */
public enum SessionState
{
	ACTIVE,
	SUSPENDED,
	FINALIZED
}
