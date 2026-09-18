package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.collectors.LoadoutArchive;
import com.osrstelemetry.plugin.model.StorageState;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Covers the
 * target-time (startedAt - 30s) redesign of resolveStarting(). Pure,
 * RuneLite/Client-independent coverage of LoadoutResolver's
 * PRE_START / FALLBACK_AT_OR_AFTER_START / UNAVAILABLE resolution
 * ladder, against a real (in-memory)
 * LoadoutArchive. Plain JUnit, no Mockito.
 */
public class LoadoutResolverTest
{
	private static final Instant STARTED_AT = Instant.parse("2026-01-01T10:00:00Z");

	private static List<StorageState.StorageItem> oneItem(int itemId)
	{
		return Collections.singletonList(new StorageState.StorageItem(0, itemId, "item-" + itemId, 1, null));
	}

	// --- null-safety (backward-compat constructor path) ---

	@Test
	public void nullArchive_resolveStarting_alwaysUnavailable()
	{
		LoadoutResolver resolver = new LoadoutResolver(null);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.UNAVAILABLE, result.getProvenance());
		assertTrue(result.getInventory().isEmpty());
	}

	@Test
	public void nullArchive_resolveEnding_alwaysUnavailable()
	{
		LoadoutResolver resolver = new LoadoutResolver(null);

		LoadoutSnapshot result = resolver.resolveEnding(STARTED_AT);

		assertEquals(LoadoutProvenance.UNAVAILABLE, result.getProvenance());
	}

	@Test
	public void nullReferenceInstant_alwaysUnavailable_evenWithRealArchive()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minusSeconds(30), oneItem(1), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		assertEquals(LoadoutProvenance.UNAVAILABLE, resolver.resolveStarting(null).getProvenance());
		assertEquals(LoadoutProvenance.UNAVAILABLE, resolver.resolveEnding(null).getProvenance());
	}

	// --- resolveStarting: PRE_START ---

	@Test
	public void resolveStarting_observationExactlyAtTarget_producesPreStart()
	{
		// target = STARTED_AT - 30s (LoadoutResolver.STARTING_LOADOUT_TARGET_OFFSET).
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minusSeconds(30), oneItem(995), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.PRE_START, result.getProvenance());
		assertEquals(995, result.getInventory().get(0).getItemId());
		assertEquals(STARTED_AT.minusSeconds(30).toString(), result.getCapturedAt());
	}

	@Test
	public void resolveStarting_prefersLatestStateAtOrBeforeTarget_notAnEarlierOne()
	{
		// Both observations are at-or-before target (STARTED_AT - 30s):
		// the resolver must pick the LATER of the two (closest to
		// target), not the earliest.
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minusSeconds(120), oneItem(1), Collections.emptyList());
		archive.record(STARTED_AT.minusSeconds(45), oneItem(2), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.PRE_START, result.getProvenance());
		assertEquals(2, result.getInventory().get(0).getItemId());
	}

	@Test
	public void resolveStarting_targetAnchoredNotStartedAtAnchored_observationAfterTargetButBeforeStartIsIgnoredForPreStart()
	{
		// A state change between target (STARTED_AT - 30s) and
		// startedAt itself must NOT be treated as the pre-target state
		// -- resolution is anchored to target, not startedAt. The
		// correct PRE_START pick is the state actually in effect AT
		// target (item 1, recorded at -60s), even though item 2 (at
		// -10s, after target but before startedAt) is "more recent."
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minusSeconds(60), oneItem(1), Collections.emptyList());
		archive.record(STARTED_AT.minusSeconds(10), oneItem(2), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.PRE_START, result.getProvenance());
		assertEquals("the state in effect AT the -30s target (item 1), not a later in-session change (item 2)",
			1, result.getInventory().get(0).getItemId());
	}

	@Test
	public void resolveStarting_stateHistory_oldButStillCurrentObservationCountsAsStartingLoadout()
	{
		// Worked example from spec section 3: gear equipped at T-2min,
		// nothing changes afterward, session begins at T (target =
		// T-30s). The T-2min observation is still the state in effect
		// at T-30s and must be used -- loadout snapshots are treated as
		// state history, not point samples; no observation needs to
		// land exactly on the target instant.
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minus(java.time.Duration.ofMinutes(2)), oneItem(1337), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.PRE_START, result.getProvenance());
		assertEquals(1337, result.getInventory().get(0).getItemId());
		assertEquals(STARTED_AT.minus(java.time.Duration.ofMinutes(2)).toString(), result.getCapturedAt());
	}

	@Test
	public void resolveStarting_preStartObservationOutsideLookback_fallsBackInstead()
	{
		// Well outside PRE_START_LOOKBACK of target (STARTED_AT - 30s).
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minus(LoadoutResolver.PRE_START_LOOKBACK).minusSeconds(60), oneItem(1), Collections.emptyList());
		archive.record(STARTED_AT.plusSeconds(5), oneItem(2), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals("a state older than the lookback window (relative to target) must not be trusted as PRE_START",
			LoadoutProvenance.FALLBACK_AT_OR_AFTER_START, result.getProvenance());
		assertEquals(2, result.getInventory().get(0).getItemId());
	}

	@Test
	public void resolveStarting_observationExactlyAtLookbackBoundaryFromTarget_stillCountsAsPreStart()
	{
		// Boundary is relative to target (STARTED_AT - 30s), not
		// startedAt itself: this observation sits exactly
		// PRE_START_LOOKBACK before the TARGET instant.
		LoadoutArchive archive = new LoadoutArchive();
		Instant target = STARTED_AT.minusSeconds(30);
		archive.record(target.minus(LoadoutResolver.PRE_START_LOOKBACK), oneItem(1), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.PRE_START, result.getProvenance());
	}

	// --- resolveStarting: FALLBACK_AT_OR_AFTER_START ---

	@Test
	public void resolveStarting_noPreStartObservationAtAll_fallsBackToEarliestAtOrAfter()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT, oneItem(1), Collections.emptyList());
		archive.record(STARTED_AT.plusSeconds(30), oneItem(2), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.FALLBACK_AT_OR_AFTER_START, result.getProvenance());
		assertEquals("must pick the EARLIEST at-or-after observation, not a later one",
			1, result.getInventory().get(0).getItemId());
	}

	// --- resolveStarting: UNAVAILABLE ---

	@Test
	public void resolveStarting_emptyArchive_unavailable()
	{
		LoadoutResolver resolver = new LoadoutResolver(new LoadoutArchive());

		LoadoutSnapshot result = resolver.resolveStarting(STARTED_AT);

		assertEquals(LoadoutProvenance.UNAVAILABLE, result.getProvenance());
		assertNull(result.getCapturedAt());
		assertTrue(result.getInventory().isEmpty());
		assertTrue(result.getEquipment().isEmpty());
	}

	// --- resolveEnding ---

	@Test
	public void resolveEnding_observationAtOrBeforeReference_used()
	{
		LoadoutArchive archive = new LoadoutArchive();
		Instant finalizedAt = STARTED_AT.plusSeconds(600);
		archive.record(finalizedAt.minusSeconds(20), oneItem(4151), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveEnding(finalizedAt);

		assertEquals(LoadoutProvenance.FALLBACK_AT_OR_AFTER_START, result.getProvenance());
		assertEquals(4151, result.getInventory().get(0).getItemId());
	}

	@Test
	public void resolveEnding_noObservationAtAll_unavailable()
	{
		LoadoutResolver resolver = new LoadoutResolver(new LoadoutArchive());

		LoadoutSnapshot result = resolver.resolveEnding(STARTED_AT);

		assertEquals(LoadoutProvenance.UNAVAILABLE, result.getProvenance());
	}

	@Test
	public void resolveEnding_neverProducesPreStart()
	{
		LoadoutArchive archive = new LoadoutArchive();
		archive.record(STARTED_AT.minusSeconds(10), oneItem(1), Collections.emptyList());
		LoadoutResolver resolver = new LoadoutResolver(archive);

		LoadoutSnapshot result = resolver.resolveEnding(STARTED_AT);

		assertEquals("ending-loadout resolution must never claim PRE_START -- see LoadoutProvenance's own javadoc",
			LoadoutProvenance.FALLBACK_AT_OR_AFTER_START, result.getProvenance());
	}
}
