package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Observed planting, retention across gaps, and when a native observation ends or replaces a cycle. */
public class CropCycleAttributionTest
{
	private static final long MIN = 60_000L;
	private static final FarmingPatchDef HERB = patch("herb.falador");
	private static final FarmingPatchDef TREE = patch("tree.taverley");

	private static PatchObservation baseline(FarmingPatchDef p, int raw, long at)
	{
		return new PatchObservation(PatchObservation.Kind.BASELINE, p, FarmingDecoder.decode(p.getType(), raw), null, at, 0L, 1);
	}

	private static PatchObservation change(FarmingPatchDef p, int fromRaw, int raw, long at)
	{
		return new PatchObservation(PatchObservation.Kind.CHANGE, p, FarmingDecoder.decode(p.getType(), raw),
			FarmingDecoder.decode(p.getType(), fromRaw), at, at - 600, 1);
	}

	private static PatchKnowledge planted(long at)
	{
		// Empty patch (3), then ranarr seed (32) on the very next tick.
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(HERB, 3, at - 600));
		return CropCycleAttribution.apply(k, change(HERB, 3, 32, at));
	}

	@Test
	public void observedPlanting_givesAnExactPlantedAt_andHistory()
	{
		PatchKnowledge k = planted(T0);
		assertEquals(CropCycle.Provenance.OBSERVED_PLANTING, k.getCycle().getProvenance());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		assertEquals(FarmingProduce.RANARR, k.getLastObservedPlanting().getProduce());
	}

	@Test
	public void sameCrop_continuingThroughCompatibleGrowth_afterAGap_keepsPlantedAt()
	{
		PatchKnowledge k = planted(T0);
		k = CropCycleAttribution.apply(k, baseline(HERB, 34, T0 + 45 * MIN)); // stage 2, 45 min later
		assertEquals(CropCycle.Provenance.OBSERVED_PLANTING, k.getCycle().getProvenance());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		k = CropCycleAttribution.apply(k, baseline(HERB, 36, T0 + 79 * MIN)); // ready
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
	}

	@Test
	public void differentCrop_afterAGap_isFirstSeen_historyKept()
	{
		PatchKnowledge k = planted(T0);
		k = CropCycleAttribution.apply(k, baseline(HERB, 40, T0 + 30 * MIN)); // toadflax growing
		assertEquals(CropCycle.Provenance.FIRST_SEEN, k.getCycle().getProvenance());
		assertNull(k.getCycle().getPlantedAt());
		assertEquals(T0 + 30 * MIN, k.getCycle().getFirstSeenAt());
		assertEquals("the old planting is still a fact", T0, k.getLastObservedPlanting().getAt());
	}

	@Test
	public void matureCrop_returningAtAnEarlyStage_isANewCycle()
	{
		PatchKnowledge k = planted(T0);
		k = CropCycleAttribution.apply(k, baseline(HERB, 37, T0 + 70 * MIN)); // harvestable
		k = CropCycleAttribution.apply(k, baseline(HERB, 33, T0 + 75 * MIN)); // ranarr again, but stage 1
		assertEquals(CropCycle.Provenance.FIRST_SEEN, k.getCycle().getProvenance());
		assertNull(k.getCycle().getPlantedAt());
	}

	@Test
	public void earlierGrowingStage_afterAGap_isANewCycle()
	{
		PatchKnowledge k = planted(T0);
		k = CropCycleAttribution.apply(k, baseline(HERB, 35, T0 + 60 * MIN));
		k = CropCycleAttribution.apply(k, baseline(HERB, 33, T0 + 65 * MIN));
		assertNull(k.getCycle().getPlantedAt());
	}

	@Test
	public void timeAway_neverErasesACompatibleCrop()
	{
		PatchKnowledge k = planted(T0);
		long weekLater = T0 + 7L * 24 * 60 * MIN;
		k = CropCycleAttribution.apply(k, baseline(HERB, 32, weekLater)); // same raw value, a week on
		assertEquals(CropCycle.Provenance.OBSERVED_PLANTING, k.getCycle().getProvenance());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		k = CropCycleAttribution.apply(k, baseline(HERB, 37, weekLater + 30 * MIN));
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
	}

	@Test
	public void cleared_closesTheCycle_andKeepsItAsHistory()
	{
		for (int raw : new int[] {3, 0})
		{
			PatchKnowledge k = CropCycleAttribution.apply(planted(T0), baseline(HERB, raw, T0 + 10 * MIN));
			assertNull("raw " + raw, k.getCycle());
			assertEquals(FarmingProduce.RANARR, k.getLastClosedCycle().getCycle().getProduce());
			assertEquals(Long.valueOf(T0), k.getLastClosedCycle().getCycle().getPlantedAt());
			assertEquals(T0, k.getLastObservedPlanting().getAt());
		}
	}

	@Test
	public void deadHerb_keepsTheCropIdentity_fromTheCycle()
	{
		PatchKnowledge k = CropCycleAttribution.apply(planted(T0), baseline(HERB, 141, T0 + 30 * MIN)); // diseased
		assertEquals(PatchCondition.DISEASED, k.getCycle().getLastCondition());
		k = CropCycleAttribution.apply(k, baseline(HERB, 171, T0 + 90 * MIN)); // dead: varbit says "any herb"
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		assertEquals(FarmingProduce.RANARR, k.getCycle().getProduce());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
	}

	@Test
	public void deadTree_keepsItsCycle_inTheDeadCondition()
	{
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(TREE, 9, T0));
		k = CropCycleAttribution.apply(k, baseline(TREE, 138, T0 + 60 * MIN));
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		assertEquals(FarmingProduce.OAK, k.getCycle().getProduce());
	}

	@Test
	public void unknownReading_erasesNothing()
	{
		PatchKnowledge k = CropCycleAttribution.apply(planted(T0), baseline(HERB, 220, T0 + 10 * MIN));
		assertEquals(220, k.getLastRaw());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		assertEquals(PatchCondition.GROWING, k.getCycle().getLastCondition());
	}

	@Test
	public void stillGrowing_onReturn_updatesTheGrowthBasis()
	{
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(HERB, 33, T0)); // stage 1, first seen
		assertEquals(T0, k.getCycle().getStageFirstSeenAt());
		assertFalse("met mid-growth: the transition into stage 1 was not seen", k.getCycle().isStageTransitionObserved());
		k = CropCycleAttribution.apply(k, baseline(HERB, 34, T0 + 40 * MIN)); // a later stage
		assertEquals(T0 + 40 * MIN, k.getCycle().getStageFirstSeenAt());
		assertEquals(2, k.getCycle().getLastStage());
		assertNull(k.getCycle().getPlantedAt());
	}

	// -- stage evidence: "stage transition observed" vs "stage first seen at" -------

	@Test
	public void continuousStageChange_isAnObservedTransition()
	{
		PatchKnowledge k = planted(T0);
		assertTrue("planting itself was witnessed", k.getCycle().isStageTransitionObserved());
		k = CropCycleAttribution.apply(k, change(HERB, 32, 33, T0 + 20 * MIN));
		assertTrue(k.getCycle().isStageTransitionObserved());
		assertEquals(T0 + 20 * MIN, k.getCycle().getStageFirstSeenAt());
		assertEquals(1, k.getCycle().getLastStage());
	}

	@Test
	public void stageMetAfterAGap_isOnlyFirstSeen_andKeepsThePlanting()
	{
		PatchKnowledge k = CropCycleAttribution.apply(planted(T0), change(HERB, 32, 33, T0 + 20 * MIN)); // stage 1, witnessed
		long back = T0 + 55 * MIN;
		k = CropCycleAttribution.apply(k, baseline(HERB, 34, back)); // player was away; stage 2 on return
		assertEquals(2, k.getCycle().getLastStage());
		assertEquals("stage 2 first seen at the return", back, k.getCycle().getStageFirstSeenAt());
		assertFalse("but the change into it was not witnessed", k.getCycle().isStageTransitionObserved());
		assertEquals("the compatible planting record survives the gap", Long.valueOf(T0), k.getCycle().getPlantedAt());
		assertEquals(CropCycle.Provenance.OBSERVED_PLANTING, k.getCycle().getProvenance());
	}

	@Test
	public void seeingTheSameStageAgain_neverMovesItsEvidence()
	{
		PatchKnowledge k = CropCycleAttribution.apply(planted(T0), change(HERB, 32, 33, T0 + 20 * MIN));
		k = CropCycleAttribution.apply(k, baseline(HERB, 33, T0 + 30 * MIN)); // new sequence, same stage
		k = k.seenUnchanged(T0 + 35 * MIN); // unchanged reads
		k = CropCycleAttribution.apply(k, baseline(HERB, 33, T0 + 39 * MIN));
		assertEquals(T0 + 20 * MIN, k.getCycle().getStageFirstSeenAt());
		assertTrue("still the witnessed transition", k.getCycle().isStageTransitionObserved());
		assertEquals(T0 + 39 * MIN, k.getCycle().getLastSeenAt());

		PatchKnowledge gap = CropCycleAttribution.apply(null, baseline(HERB, 34, T0));
		gap = CropCycleAttribution.apply(gap, baseline(HERB, 34, T0 + 9 * MIN));
		gap = gap.seenUnchanged(T0 + 10 * MIN);
		assertEquals(T0, gap.getCycle().getStageFirstSeenAt());
		assertFalse(gap.getCycle().isStageTransitionObserved());
	}

	// -- observed disease and death outrank readiness -------------------------------

	@Test
	public void deadRanarr_staysDeadPastItsGrowthDeadline_thenClearAndReplantStartClean()
	{
		// 1. Ranarr planting observed. 2. Its estimated growth time (80 min) passes unobserved.
		PatchKnowledge k = planted(T0);
		long back = T0 + 3 * 60 * MIN;
		// 3. A reliable observation: dead. 4. Ranarr identity kept, condition DEAD.
		k = CropCycleAttribution.apply(k, baseline(HERB, 171, back));
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		assertEquals(FarmingProduce.RANARR, k.getCycle().getProduce());
		assertFalse(k.getCycle().isObservedHarvestable());
		// 5. More time, freshness reads, and later sightings of the same dead patch: still DEAD.
		k = k.seenUnchanged(back + 60 * MIN);
		k = CropCycleAttribution.apply(k, baseline(HERB, 171, back + 2L * 24 * 60 * MIN));
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		assertEquals(back, k.getCycle().getStageFirstSeenAt());
		// 6. Cleared: no cycle (CLEAR); the dead ranarr is history.
		long cleared = back + 2L * 24 * 60 * MIN + 600;
		k = CropCycleAttribution.apply(k, change(HERB, 171, 3, cleared));
		assertNull(k.getCycle());
		assertEquals(PatchCondition.DEAD, k.getLastClosedCycle().getCycle().getLastCondition());
		// 7. Replant observed: a new, healthy cycle with its own planting.
		k = CropCycleAttribution.apply(k, change(HERB, 3, 32, cleared + 600));
		assertEquals(PatchCondition.GROWING, k.getCycle().getLastCondition());
		assertEquals(Long.valueOf(cleared + 600), k.getCycle().getPlantedAt());
		assertEquals(cleared + 600, k.getCycle().getFirstSeenAt());
	}

	@Test
	public void sameDeadHerb_freshBaselineAfterAGap_keepsItsIdentityAndCycle()
	{
		PatchKnowledge k = planted(T0);
		k = CropCycleAttribution.apply(k, baseline(HERB, 171, T0 + 3 * 60 * MIN)); // dead, identity from the cycle
		k = CropCycleAttribution.apply(k, baseline(HERB, 171, T0 + 30 * 60 * MIN)); // modal / hop / logout, then the same dead patch
		assertEquals(FarmingProduce.RANARR, k.getCycle().getProduce());
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		assertEquals(CropCycle.Provenance.OBSERVED_PLANTING, k.getCycle().getProvenance());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		assertEquals(T0, k.getCycle().getFirstSeenAt());
		assertEquals(T0 + 3 * 60 * MIN, k.getCycle().getStageFirstSeenAt());
		assertEquals(T0 + 30 * 60 * MIN, k.getCycle().getLastSeenAt());
		assertNull("nothing was closed", k.getLastClosedCycle());
	}

	@Test
	public void diseasedCrop_staysDiseasedWithTime_untilACureIsSeen()
	{
		PatchKnowledge k = CropCycleAttribution.apply(planted(T0), baseline(HERB, 141, T0 + 30 * MIN));
		long days = 3L * 24 * 60 * MIN;
		k = k.seenUnchanged(T0 + days);
		k = CropCycleAttribution.apply(k, baseline(HERB, 141, T0 + days + MIN));
		assertEquals("time never cures", PatchCondition.DISEASED, k.getCycle().getLastCondition());
		assertFalse(k.getCycle().isObservedHarvestable());
		assertEquals(T0 + 30 * MIN, k.getCycle().getStageFirstSeenAt());
		k = CropCycleAttribution.apply(k, change(HERB, 141, 34, T0 + days + 2 * MIN)); // a cure, seen
		assertEquals(PatchCondition.GROWING, k.getCycle().getLastCondition());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
	}

	@Test
	public void deadCrop_neverContinues_theSameSeedSeenGrowingAfterAGap_isANewCycle()
	{
		PatchKnowledge k = CropCycleAttribution.apply(planted(T0), baseline(HERB, 171, T0 + 3 * 60 * MIN));
		k = CropCycleAttribution.apply(k, baseline(HERB, 33, T0 + 5 * 60 * MIN)); // ranarr growing again
		assertEquals(CropCycle.Provenance.FIRST_SEEN, k.getCycle().getProvenance());
		assertNull("the old planting time does not leak", k.getCycle().getPlantedAt());
		assertEquals(PatchCondition.GROWING, k.getCycle().getLastCondition());
		assertFalse(k.getCycle().isStageTransitionObserved());
		assertEquals(PatchCondition.DEAD, k.getLastClosedCycle().getCycle().getLastCondition());

		PatchKnowledge tree = CropCycleAttribution.apply(null, baseline(TREE, 9, T0));
		tree = CropCycleAttribution.apply(tree, baseline(TREE, 138, T0 + 60 * MIN)); // dead oak
		tree = CropCycleAttribution.apply(tree, baseline(TREE, 9, T0 + 9 * 60 * MIN)); // oak growing again
		assertEquals(PatchCondition.GROWING, tree.getCycle().getLastCondition());
		assertEquals(T0 + 9 * 60 * MIN, tree.getCycle().getFirstSeenAt());
	}

	@Test
	public void redecode_correctsACycleThatDisagreesWithItsOwnRawValue()
	{
		// Stored by an older decoder that read raw 141 as "growing": the current one says diseased.
		CropCycle old = new CropCycle(FarmingProduce.RANARR, CropCycle.Provenance.OBSERVED_PLANTING, T0, T0, T0 + 30 * MIN,
			PatchCondition.GROWING, 2, 141, T0 + 30 * MIN, false);
		PatchKnowledge stored = new PatchKnowledge(HERB.getPatchId(), HERB.getOwningRegionId(), HERB.getVarbit(), 141, 0,
			T0 + 30 * MIN, T0 + 30 * MIN, old, null, null, false);
		PatchKnowledge k = CropCycleAttribution.redecode(stored, HERB);
		assertEquals(FarmingDecoder.VERSION, k.getDecoderVersion());
		assertEquals(PatchCondition.DISEASED, k.getCycle().getLastCondition());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		assertEquals(T0 + 30 * MIN, k.getCycle().getStageFirstSeenAt());
	}

	@Test
	public void cropSeenMature_cannotLaterBeThatCropDiseasedOrDead()
	{
		PatchKnowledge ready = CropCycleAttribution.apply(planted(T0), baseline(HERB, 36, T0 + 90 * MIN));
		PatchKnowledge dead = CropCycleAttribution.apply(ready, baseline(HERB, 171, T0 + 5 * 60 * MIN));
		assertNull("a dead herb after a seen-ready ranarr is some later, unknown herb", dead.getCycle());
		assertEquals(FarmingProduce.RANARR, dead.getLastClosedCycle().getCycle().getProduce());
		assertEquals(171, dead.getLastRaw());

		PatchKnowledge diseased = CropCycleAttribution.apply(ready, baseline(HERB, 141, T0 + 5 * 60 * MIN));
		assertEquals(PatchCondition.DISEASED, diseased.getCycle().getLastCondition());
		assertNull("a new cycle: not the planted one", diseased.getCycle().getPlantedAt());
	}

	/** Josh's locked player flow (2026-10-14), at the knowledge level. */
	@Test
	public void lockedPlayerFlow_plantLeaveReadyPartialHarvestClearReplant()
	{
		// 1. Planting observed.
		PatchKnowledge k = planted(T0);
		assertEquals(FarmingProduce.RANARR, k.getCycle().getProduce());
		// 2-3. Player leaves; time passes. Nothing is applied: knowledge is untouched by time.
		long back = T0 + 3 * 60 * MIN;
		assertEquals(T0, k.getLastSeenAt());
		// 4. Return: harvestable observed.
		k = CropCycleAttribution.apply(k, baseline(HERB, 36, back));
		assertTrue(k.getCycle().isObservedHarvestable());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		// 5. Partial harvest: still harvestable, same cycle.
		k = CropCycleAttribution.apply(k, change(HERB, 36, 37, back + 600));
		assertTrue(k.getCycle().isObservedHarvestable());
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		// 6. Picked clean, then raked: the cycle closes, history kept.
		k = CropCycleAttribution.apply(k, change(HERB, 38, 3, back + 1200));
		assertNull(k.getCycle());
		assertEquals(Long.valueOf(T0), k.getLastClosedCycle().getCycle().getPlantedAt());
		// 7. Replant observed: a new cycle and planting time.
		k = CropCycleAttribution.apply(k, change(HERB, 3, 39, back + 1800)); // toadflax seed
		assertEquals(FarmingProduce.TOADFLAX, k.getCycle().getProduce());
		assertEquals(Long.valueOf(back + 1800), k.getCycle().getPlantedAt());
		assertEquals(back + 1800, k.getLastObservedPlanting().getAt());
	}

	@Test
	public void cropFirstMetMidGrowth_hasNoPlantingTime()
	{
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(HERB, 34, T0));
		assertEquals(CropCycle.Provenance.FIRST_SEEN, k.getCycle().getProvenance());
		assertNull(k.getCycle().getPlantedAt());
		assertNull(k.getLastObservedPlanting());
	}

	@Test
	public void plantingAcrossAGap_isNotAnObservedPlanting()
	{
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(HERB, 3, T0));
		k = CropCycleAttribution.apply(k, baseline(HERB, 32, T0 + 2 * MIN)); // stage 0, but seen only after a gap
		assertEquals(CropCycle.Provenance.FIRST_SEEN, k.getCycle().getProvenance());
		assertNull(k.getCycle().getPlantedAt());
		assertNull(k.getLastObservedPlanting());
	}

	@Test
	public void diseaseAndCure_withinTheTimeBound_stayTheSameCycle()
	{
		PatchKnowledge k = planted(T0);
		k = CropCycleAttribution.apply(k, baseline(HERB, 141, T0 + 30 * MIN)); // diseased ranarr
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
		k = CropCycleAttribution.apply(k, baseline(HERB, 34, T0 + 35 * MIN)); // cured
		assertEquals(Long.valueOf(T0), k.getCycle().getPlantedAt());
	}

	@Test
	public void treeStumpRegrowing_isTheSameCycle()
	{
		PatchKnowledge k = CropCycleAttribution.apply(null, baseline(TREE, 13, T0)); // standing oak
		k = CropCycleAttribution.apply(k, change(TREE, 13, 14, T0 + 600)); // chopped
		k = CropCycleAttribution.apply(k, baseline(TREE, 13, T0 + 30 * MIN)); // regrown
		assertEquals(T0, k.getCycle().getFirstSeenAt());
	}
}
