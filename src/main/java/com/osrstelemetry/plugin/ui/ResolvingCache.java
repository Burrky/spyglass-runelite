package com.osrstelemetry.plugin.ui;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A generic, RuneLite-free thread-safe cache that separates "read an
 * already-resolved value" (safe from any thread, including the Swing
 * EDT) from "resolve a missing value" (expected to run exactly once
 * per key, typically on a privileged thread such as RuneLite's client
 * thread).
 *
 * This class has no knowledge of ItemManager, prices, or Swing -- it
 * is the pure/testable half. {@link
 * ItemValuationCache} is the thin RuneLite-coupled half that wires
 * this cache up to ItemManager and ClientThread.
 *
 * Safe publication between the resolving thread and reading threads
 * is provided by {@link ConcurrentHashMap} (a put on one thread is
 * guaranteed visible to a get on another thread with no external
 * locking needed).
 */
final class ResolvingCache<K, V>
{
	private final Map<K, V> resolved = new ConcurrentHashMap<>();
	private final Set<K> pending = ConcurrentHashMap.newKeySet();

	/**
	 * Returns the resolved value for {@code key}, or {@code null} if
	 * it has not been resolved yet. Safe to call from any thread.
	 */
	V getIfResolved(K key)
	{
		return resolved.get(key);
	}

	/**
	 * If {@code key} is not yet resolved and is not already pending
	 * resolution, marks it pending and invokes {@code resolver} for
	 * it exactly once. Returns {@code true} if this call triggered
	 * the resolver, {@code false} if a value was already resolved or
	 * a resolution was already in flight for this key.
	 *
	 * {@code resolver} is expected to eventually call {@link #put}
	 * for {@code key} (typically asynchronously, e.g. after a
	 * ClientThread round-trip) or {@link #clearPending} if resolution
	 * failed and a later call should be allowed to retry.
	 */
	boolean resolveIfAbsent(K key, Consumer<K> resolver)
	{
		if (resolved.containsKey(key))
		{
			return false;
		}

		if (!pending.add(key))
		{
			return false;
		}

		resolver.accept(key);
		return true;
	}

	/**
	 * Stores the resolved value for {@code key} and clears its
	 * pending state. Safe to call from any thread.
	 */
	void put(K key, V value)
	{
		resolved.put(key, value);
		pending.remove(key);
	}

	/**
	 * Clears {@code key}'s pending state without resolving it, so a
	 * later {@link #resolveIfAbsent} call is allowed to retry. Used
	 * when a resolution attempt fails.
	 */
	void clearPending(K key)
	{
		pending.remove(key);
	}
}
