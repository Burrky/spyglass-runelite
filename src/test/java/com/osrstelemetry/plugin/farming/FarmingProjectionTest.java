package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Projection boundaries, calibration, stage evidence, precedence and retention. */
public class FarmingProjectionTest
{
	private static final long MIN = 60_000L;
	private static final long DAY = 24 * 60 * MIN;
	private static final FarmingPatchDef HERB = patch("herb.falador");
	private static final FarmingPatchDef TREE = patch("tree.taverley");

	private static long floorMin(long t)
	{
		return Math.floorDiv(t, MIN) * MIN;
	}

	private static PatchObservation baseline(FarmingPatchDef p, int raw, long at)
	{
		return new PatchObservation(PatchObservation.Kind.BASELINE, p, FarmingDecoder.decode(p.getType(), raw), null, at, 0L, 1);
	}

	private static PatchObservation change(FarmingPatchDef p, int fromRaw, int raw, long at)
	{
		return new PatchObservation(PatchObservation.Kind.CHANGE, p, FarmingDecoder.decode(p.getType(), raw),
			FarmingDecoder.decode(p.getType(), fromRaw), at, at - 600, 1);
	}

	private static PatchKnowledge plantedRanarr(long at)
	{
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(HERB, 3, at - 600));
		return CropCycleAttribution.apply(k, change(HERB, 3, 32, at));
	}

	private static PatchStatus project(FarmingPatchDef p, PatchKnowledge k, long now)
	{
		return FarmingProjection.project(p, k, FarmingCalibration.NONE, now);
	}

	// -- timing ------------------------------------------------------------------

	@Test
	public void withoutCalibration_aPlantedHerbIsTimedToTheLatestPossibleBoundary_neverEarly()
	{
		PatchKnowledge k = plantedRanarr(T0);
		PatchStatus growing = project(HERB, k, T0 + 30 * MIN);
		assertEquals(PatchStatus.Display.GROWING_TIMED, growing.getDisplay());
		// 4 growth ticks of 20 minutes; the first is somewhere in the 20 minutes after planting.
		assertEquals(Long.valueOf(floorMin(T0) + 80 * MIN), growing.getReadyAt());
		assertEquals(Long.valueOf(floorMin(T0) + 61 * MIN), growing.getReadyEarliest());
		assertEquals(PatchStatus.Provenance.PLANTED, growing.getProvenance());
		assertEquals(FarmingProduce.RANARR, growing.getProduce());

		PatchStatus atDeadline = project(HERB, k, growing.getReadyAt());
		assertEquals(PatchStatus.Display.READY, atDeadline.getDisplay());
		assertEquals(PatchStatus.ReadySource.ESTIMATED, atDeadline.getReadySource());
		assertEquals(PatchStatus.Display.GROWING_TIMED, project(HERB, k, growing.getReadyAt() - 1).getDisplay());
	}

	@Test
	public void ready_staysReady_untilNewEvidence_howeverLongAgo()
	{
		PatchKnowledge k = plantedRanarr(T0);
		PatchStatus later = project(HERB, k, T0 + 40 * DAY);
		assertEquals(PatchStatus.Display.READY, later.getDisplay());
		assertEquals(FarmingProduce.RANARR, later.getProduce());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
	}

	@Test
	public void witnessedGrowthTick_calibratesTheBoundaryExactly()
	{
		long seen = T0 + 7 * MIN + 12_345;
		CalibrationSample sample = new CalibrationSample("herb.falador", FarmingProduce.RANARR, 20,
			FarmingDecoder.decode(FarmingPatchType.HERB, 33), FarmingDecoder.decode(FarmingPatchType.HERB, 34), seen - 600, seen, 1);
		FarmingCalibration cal = FarmingCalibration.from(Collections.singletonList(sample));
		assertTrue(cal.isCalibrated());
		assertTrue(cal.isExactFor(20));
		assertFalse(cal.isExactFor(40));
		PatchKnowledge k = CropCycleAttribution.apply(plantedRanarr(T0), change(HERB, 33, 34, seen)); // stage 2 at the boundary
		k = k.seenUnchanged(seen + 5 * MIN);
		PatchStatus s = FarmingProjection.project(HERB, k, cal, seen + 6 * MIN);
		assertEquals("two more boundaries, 20 minutes apart, from the witnessed one", Long.valueOf(floorMin(seen) + 40 * MIN), s.getReadyAt());
		assertEquals(s.getReadyAt(), s.getReadyEarliest());
	}

	@Test
	public void partialPrecision_leavesAWindowOfTheMissingMinutes()
	{
		long seen = T0 + 3 * MIN;
		CalibrationSample sample = new CalibrationSample("herb.falador", FarmingProduce.RANARR, 20,
			FarmingDecoder.decode(FarmingPatchType.HERB, 33), FarmingDecoder.decode(FarmingPatchType.HERB, 34), seen - 600, seen, 1);
		FarmingCalibration cal = FarmingCalibration.from(Collections.singletonList(sample));
		long[] w = cal.nextBoundary(seen + 5 * MIN, 40);
		assertEquals("a 40-minute crop with the offset known mod 20", 20 * MIN, w[1] - w[0]);
		long[] none = FarmingCalibration.NONE.nextBoundary(seen, 40);
		assertEquals(39 * MIN, none[1] - none[0]);
	}

	@Test
	public void conflictingSamples_areCounted_notUsed()
	{
		long a = T0 + 2 * MIN;
		long b = T0 + 9 * MIN; // 7 minutes later: impossible for the same 20-minute grid
		CalibrationSample s1 = new CalibrationSample("herb.falador", FarmingProduce.RANARR, 20,
			FarmingDecoder.decode(FarmingPatchType.HERB, 33), FarmingDecoder.decode(FarmingPatchType.HERB, 34), a - 600, a, 1);
		CalibrationSample s2 = new CalibrationSample("herb.catherby", FarmingProduce.RANARR, 20,
			FarmingDecoder.decode(FarmingPatchType.HERB, 33), FarmingDecoder.decode(FarmingPatchType.HERB, 34), b - 600, b, 2);
		FarmingCalibration cal = FarmingCalibration.from(Arrays.asList(s1, s2));
		assertEquals(1, cal.getConflictCount());
		assertEquals("anchored on the most recent", FarmingCalibration.offsetFor(s2, 20), cal.getOffsetMinutes());
	}

	@Test
	public void stageFirstSeenAfterAGap_boundsTheEstimate_withoutBeingTreatedAsItsStart()
	{
		long firstSeen = T0;
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(HERB, 34, firstSeen)); // stage 2, met after a gap
		assertFalse(k.getCycle().isStageTransitionObserved());
		k = k.seenUnchanged(firstSeen + 15 * MIN);
		PatchStatus s = project(HERB, k, firstSeen + 17 * MIN);
		// Present by firstSeen, so its next boundary is at most 20 minutes after it, and after the last sighting.
		assertEquals(Long.valueOf(floorMin(firstSeen) + 40 * MIN), s.getReadyAt());
		assertTrue(s.getReadyEarliest() >= floorMin(firstSeen + 15 * MIN) + MIN + 20 * MIN);
		assertNull("no planting time is invented", k.getCycle().getPlantedAt());
		assertEquals(PatchStatus.Provenance.SEEN_GROWING, s.getProvenance());
	}

	@Test
	public void repeatedSameStageSightings_neverPushReadinessBack()
	{
		PatchKnowledge k = plantedRanarr(T0);
		Long first = project(HERB, k, T0 + MIN).getReadyAt();
		for (int m = 2; m <= 19; m += 3)
		{
			k = k.seenUnchanged(T0 + m * MIN);
			assertEquals("sighting at +" + m + "m", first, project(HERB, k, T0 + m * MIN).getReadyAt());
		}
		// Held past a whole growth tick: the timing model is contradicted, so no estimate at all, never a later one.
		PatchKnowledge held = k.seenUnchanged(T0 + 45 * MIN);
		PatchStatus s = project(HERB, held, T0 + 46 * MIN);
		assertEquals(PatchStatus.Display.GROWING, s.getDisplay());
		assertNull(s.getReadyAt());
		assertEquals("crop identity kept", FarmingProduce.RANARR, s.getProduce());
	}

	@Test
	public void aNewerGrowingObservation_beatsAnOldPlantingTime_whichIsKeptAsHistory()
	{
		PatchKnowledge k = plantedRanarr(T0);
		long back = T0 + 100 * MIN; // still only stage 1: growth was slower than the planting time implies
		k = CropCycleAttribution.apply(k, baseline(HERB, 33, back));
		PatchStatus s = project(HERB, k, back + 5 * MIN);
		assertEquals(PatchStatus.Display.GROWING_TIMED, s.getDisplay());
		assertTrue("estimated from the newer stage, not the planting", s.getReadyAt() > floorMin(back) + 40 * MIN);
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		assertEquals(PatchStatus.Provenance.PLANTED, s.getProvenance());
	}

	// -- precedence ----------------------------------------------------------------

	@Test
	public void knownDiseaseAndDeath_outrankAnyEstimate()
	{
		PatchKnowledge diseased = CropCycleAttribution.apply(plantedRanarr(T0), baseline(HERB, 141, T0 + 30 * MIN));
		PatchStatus d = project(HERB, diseased, T0 + 10 * DAY);
		assertEquals(PatchStatus.Display.DISEASED, d.getDisplay());
		assertEquals(FarmingProduce.RANARR, d.getProduce());
		assertEquals(PatchStatus.Provenance.LAST_SEEN_DISEASED, d.getProvenance());
		assertFalse(d.isReady());

		PatchKnowledge dead = CropCycleAttribution.apply(diseased, baseline(HERB, 171, T0 + 3 * 60 * MIN));
		PatchStatus x = project(HERB, dead, T0 + 10 * DAY);
		assertEquals(PatchStatus.Display.DEAD, x.getDisplay());
		assertEquals("identity from the cycle", FarmingProduce.RANARR, x.getProduce());
		assertFalse(x.isProduceUnknown());
	}

	@Test
	public void aDeadHerbOfUnknownKind_isDead_withTheKindUnknown()
	{
		PatchKnowledge ready = CropCycleAttribution.apply(plantedRanarr(T0), baseline(HERB, 36, T0 + 90 * MIN));
		PatchKnowledge dead = CropCycleAttribution.apply(ready, baseline(HERB, 171, T0 + 5 * DAY));
		PatchStatus s = project(HERB, dead, T0 + 6 * DAY);
		assertEquals(PatchStatus.Display.DEAD, s.getDisplay());
		assertNull(s.getProduce());
		assertTrue(s.isProduceUnknown());
	}

	@Test
	public void observedStates_clear_ready_checkHealth_grown_andUntimeableGrowth()
	{
		PatchStatus clear = project(HERB, CropCycleAttribution.apply(plantedRanarr(T0), change(HERB, 38, 3, T0 + 2 * DAY)), T0 + 3 * DAY);
		assertEquals(PatchStatus.Display.CLEAR, clear.getDisplay());
		assertNull(clear.getProduce());

		PatchStatus harvestable = project(HERB, CropCycleAttribution.apply(plantedRanarr(T0), baseline(HERB, 36, T0 + 2 * DAY)), T0 + 9 * DAY);
		assertEquals(PatchStatus.Display.READY, harvestable.getDisplay());
		assertEquals(PatchStatus.ReadySource.OBSERVED, harvestable.getReadySource());

		PatchStatus checkHealth = project(TREE, CropCycleAttribution.apply(null, baseline(TREE, 12, T0)), T0 + DAY);
		assertEquals(PatchStatus.Display.READY, checkHealth.getDisplay());
		assertEquals(PatchStatus.ReadySource.OBSERVED, checkHealth.getReadySource());

		FarmingPatchDef spirit = patch("spirit_tree.port_sarim");
		assertEquals(PatchStatus.Display.GROWN, project(spirit, CropCycleAttribution.apply(null, baseline(spirit, 20, T0)), T0 + DAY).getDisplay());

		FarmingPatchDef anima = patch("anima.farming_guild");
		PatchStatus a = project(anima, CropCycleAttribution.apply(null, baseline(anima, 9, T0)), T0 + 30 * DAY);
		assertEquals("no invented timer", PatchStatus.Display.GROWING, a.getDisplay());
		assertNull(a.getReadyAt());
	}

	@Test
	public void neverSeen_unknownAndStale_areHonest()
	{
		assertEquals(PatchStatus.Display.NOT_TRACKED, project(HERB, null, T0).getDisplay());
		assertFalse(project(HERB, null, T0).isTracked());

		PatchKnowledge unknown = CropCycleAttribution.apply(plantedRanarr(T0), baseline(HERB, 220, T0 + 10 * MIN));
		PatchStatus u = project(HERB, unknown, T0 + DAY);
		assertEquals("UNKNOWN is never CLEAR", PatchStatus.Display.CHECK_PATCH, u.getDisplay());
		assertEquals("last known crop kept for context", FarmingProduce.RANARR, u.getProduce());

		PatchKnowledge stale = plantedRanarr(T0).withDecoder(FarmingDecoder.VERSION, plantedRanarr(T0).getCycle(), true);
		assertEquals(PatchStatus.Display.CHECK_PATCH, project(HERB, stale, T0 + DAY).getDisplay());
	}

	@Test
	public void projecting_neverChangesKnowledge()
	{
		PatchKnowledge k = plantedRanarr(T0);
		CropCycle cycle = k.getCycle();
		long seen = k.getLastSeenAt();
		for (long now : new long[]{T0, T0 + 50 * MIN, T0 + 5 * DAY})
		{
			project(HERB, k, now);
		}
		assertSame(cycle, k.getCycle());
		assertEquals(seen, k.getLastSeenAt());
		assertEquals(seen, cycle.getLastSeenAt());
		assertEquals(PatchCondition.GROWING, cycle.getLastCondition());
	}

	@Test
	public void seenJustNow_whileStandingAtThePatch()
	{
		PatchKnowledge k = plantedRanarr(T0).seenUnchanged(T0 + 10 * MIN);
		assertEquals(PatchStatus.Provenance.SEEN_JUST_NOW, project(HERB, k, T0 + 10 * MIN + 30_000).getProvenance());
		assertEquals(PatchStatus.Provenance.PLANTED, project(HERB, k, T0 + 20 * MIN).getProvenance());
	}

	// -- text ----------------------------------------------------------------------

	@Test
	public void text_hasNoFalsePrecision()
	{
		assertEquals("~12m", FarmingText.remaining(12 * MIN));
		assertEquals("~12m", FarmingText.remaining(11 * MIN + 1));
		assertEquals("~1h 40m", FarmingText.remaining(100 * MIN));
		assertEquals("~1h 10m", FarmingText.remaining(61 * MIN));
		assertEquals("~1h", FarmingText.remaining(59 * MIN + 30_000));
		assertEquals("~1m", FarmingText.remaining(5_000));
		assertEquals("~4d 11h", FarmingText.remaining(4 * DAY + 10 * 60 * MIN + 5 * MIN));
		assertEquals("1h 34m", FarmingText.age(94 * MIN + 59_000));
		assertEquals("16d", FarmingText.age(16 * DAY + 5 * 60 * MIN));
		assertEquals("just now", FarmingText.age(30_000));
		PatchStatus s = project(HERB, plantedRanarr(T0), T0 + 94 * MIN);
		assertNotNull(FarmingText.provenance(s, T0 + 94 * MIN));
		assertEquals("Planted 1h 34m ago", FarmingText.provenance(s, T0 + 94 * MIN));
	}
}
