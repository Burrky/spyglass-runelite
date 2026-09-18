package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * NOTE: written, not run in this sandbox -- same no-JDK-in-this-cloud-sandbox
 * caveat documented across this project's other Java test files (see
 * SessionLifecycleEngineTest); Josh's own `.\gradlew.bat clean test`
 * run is what actually executes these.
 *
 * Narrow tests for ResolvingCache's
 * own resolve/pending/put contract -- deliberately synchronous
 * (single-threaded) since this class's own correctness (dedup,
 * pending-state transitions, safe-publication-by-map semantics) does
 * not require a real RuneLite ClientThread to exercise. Per the
 * explicit instruction not to build a fake-ClientThread/RuneLite
 * threading harness, {@link ItemValuationCache} (the thin
 * RuneLite-coupled wrapper around this class) is intentionally left
 * untested -- the same seam-testing rationale LootPricingTest's own
 * javadoc describes (this project has no Mockito dependency) applies
 * here: ResolvingCache is the pure, testable half; ItemValuationCache
 * is the thin untested RuneLite-coupled half.
 */
public class ResolvingCacheTest
{
	@Test
	public void getIfResolved_isNullBeforeAnyResolution()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();

		assertNull(cache.getIfResolved(42));
	}

	@Test
	public void put_makesValueVisibleViaGetIfResolved()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();

		cache.put(42, 100L);

		assertEquals(Long.valueOf(100L), cache.getIfResolved(42));
	}

	@Test
	public void resolveIfAbsent_invokesResolverOnFirstMiss()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();
		List<Integer> resolvedKeys = new ArrayList<>();

		boolean triggered = cache.resolveIfAbsent(42, resolvedKeys::add);

		assertTrue(triggered);
		assertEquals(Collections.singletonList(42), resolvedKeys);
	}

	// The core dedup guarantee ItemValuationCache relies on: while a
	// resolution for an item ID is in flight (its resolver has not yet
	// called put()), a render loop calling resolveIfAbsent every second
	// must never schedule a second ClientThread round-trip for it.
	@Test
	public void resolveIfAbsent_doesNotInvokeResolverTwiceForSamePendingKey()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();
		List<Integer> resolvedKeys = new ArrayList<>();

		boolean first = cache.resolveIfAbsent(42, resolvedKeys::add);
		boolean second = cache.resolveIfAbsent(42, resolvedKeys::add);

		assertTrue(first);
		assertFalse(second);
		assertEquals(1, resolvedKeys.size());
	}

	@Test
	public void resolveIfAbsent_doesNotInvokeResolverOnceAlreadyResolved()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();
		cache.put(42, 100L);
		List<Integer> resolvedKeys = new ArrayList<>();

		boolean triggered = cache.resolveIfAbsent(42, resolvedKeys::add);

		assertFalse(triggered);
		assertTrue(resolvedKeys.isEmpty());
	}

	// Mirrors ItemValuationCache's own failure-recovery behavior: a
	// resolution attempt that fails clears pending (rather than caching
	// a permanent value), so a later render is allowed to retry.
	@Test
	public void clearPending_allowsRetryAfterAFailedResolution()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();
		List<Integer> resolvedKeys = new ArrayList<>();

		cache.resolveIfAbsent(42, id ->
		{
			resolvedKeys.add(id);
			cache.clearPending(id);
		});

		boolean retried = cache.resolveIfAbsent(42, resolvedKeys::add);

		assertTrue(retried);
		assertEquals(2, resolvedKeys.size());
	}

	@Test
	public void put_clearsPendingSoALaterResolveIfAbsentIsANoOp()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();
		cache.resolveIfAbsent(42, id -> cache.put(id, 55L));

		List<Integer> resolvedKeys = new ArrayList<>();
		boolean triggeredAgain = cache.resolveIfAbsent(42, resolvedKeys::add);

		assertFalse(triggeredAgain);
		assertTrue(resolvedKeys.isEmpty());
		assertEquals(Long.valueOf(55L), cache.getIfResolved(42));
	}

	@Test
	public void distinctKeysAreIndependent()
	{
		ResolvingCache<Integer, Long> cache = new ResolvingCache<>();
		cache.put(1, 10L);
		cache.put(2, 20L);

		assertEquals(Long.valueOf(10L), cache.getIfResolved(1));
		assertEquals(Long.valueOf(20L), cache.getIfResolved(2));
	}
}
