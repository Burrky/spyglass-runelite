package com.osrstelemetry.plugin.account;

/**
 * A three-valued fact. UNKNOWN is a first-class answer ("Spyglass has no
 * evidence"), never a polite NO: nothing may turn it into "unavailable" or zero.
 */
public enum Known
{
	YES,
	NO,
	UNKNOWN;

	public static Known of(boolean value)
	{
		return value ? YES : NO;
	}
}
