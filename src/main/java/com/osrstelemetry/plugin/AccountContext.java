package com.osrstelemetry.plugin;

import javax.inject.Singleton;

/**
 * Detects account switches so collectors can clear per-account
 * in-memory baselines (skill XP windows, quest lastKnown snapshots,
 * Slayer task identity, bank debounce state) rather than silently
 * carrying account A's state into account B's telemetry.
 *
 * -1 is the sentinel for "no account observed yet". The first real
 * accountHash seen is a seed, not a switch — there is nothing to
 * reset on plugin startup, only on an actual A -> B transition.
 */
@Singleton
public class AccountContext
{
	private static final long NONE = -1L;

	private volatile long currentAccountHash = NONE;

	/**
	 * @return the previously-current accountHash (NONE if this is the
	 * first observation), so callers can flush anything that needs to
	 * be attributed to the OLD account before resetting.
	 */
	public synchronized long update(long observedAccountHash)
	{
		long previous = currentAccountHash;
		currentAccountHash = observedAccountHash;
		return previous;
	}

	public boolean isRealSwitch(long previousAccountHash, long newAccountHash)
	{
		return previousAccountHash != NONE && previousAccountHash != newAccountHash;
	}

	public long getCurrentAccountHash()
	{
		return currentAccountHash;
	}
}
