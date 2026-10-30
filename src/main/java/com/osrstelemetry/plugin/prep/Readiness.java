package com.osrstelemetry.plugin.prep;

/**
 * Whether a preparation can be called ready. Only {@link #CONFIRMED} is
 * "ready": supplies carried is not enough while something the plan relies on
 * cannot be confirmed (a transport's charges, a patch's access, no usable
 * transport known). The player can always proceed explicitly.
 */
public enum Readiness
{
	/** Every required need is carried and nothing the plan relies on is uncertain. */
	CONFIRMED,
	/** Every required need is carried, but something the plan relies on is not confirmed. */
	UNVERIFIED,
	/** At least one required need is not carried (it may also have uncertainties). */
	MISSING
}
