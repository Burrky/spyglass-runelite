package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingObserverStep.Status;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;
import org.junit.Test;

/** The pure observer: settling, continuity breaks, ownership, freshness and calibration eligibility. */
public class FarmingObserverTest
{
	private final FarmingObserver observer = new FarmingObserver(FarmingPatchCatalog.standard());
	private int tick = 100;

	private FarmingObserverStep step(FarmingTickInput in)
	{
		tick = in.getTick();
		return observer.onTick(in);
	}

	/** Feeds Falador ticks with fixed values until the first CAPTURED step, which it returns. */
	private FarmingObserverStep settleAtFalador(int herb)
	{
		for (int i = 0; i < 10; i++)
		{
			FarmingObserverStep s = step(falador(tick + 1, 3, 3, herb));
			if (s.getStatus() == Status.CAPTURED)
			{
				return s;
			}
		}
		throw new AssertionError("never settled");
	}

	private static PatchObservation only(FarmingObserverStep s, String patchId)
	{
		PatchObservation found = null;
		for (PatchObservation o : s.getObservations())
		{
			if (o.getPatch().getPatchId().equals(patchId))
			{
				found = o;
			}
		}
		return found;
	}

	@Test
	public void freshLogin_settlesBeforeTheFirstRead_whichIsABaseline()
	{
		FarmingObserverStep first = step(falador(1, 3, 3, 3));
		assertEquals("profile metadata needs a second identical tick", Status.PROFILE_NOT_READY, first.getStatus());
		assertEquals(Status.SETTLING, step(falador(2, 3, 3, 3)).getStatus());
		assertEquals(Status.SETTLING, step(falador(3, 3, 3, 3)).getStatus());
		FarmingObserverStep captured = step(falador(4, 3, 3, 3));
		assertEquals(Status.CAPTURED, captured.getStatus());
		assertEquals("two allotments, the flower patch and the herb", 4, captured.getObservations().size());
		for (PatchObservation o : captured.getObservations())
		{
			assertEquals(PatchObservation.Kind.BASELINE, o.getKind());
		}
	}

	@Test
	public void unchangedReads_areFreshnessOnly_neverObservations()
	{
		settleAtFalador(33);
		for (int i = 0; i < 50; i++)
		{
			FarmingObserverStep s = step(falador(tick + 1, 3, 3, 33));
			assertTrue(s.getObservations().isEmpty());
			assertEquals(4, s.getUnchangedPatchIds().size());
		}
	}

	@Test
	public void tickToTickChange_inOneSequence_isAChange()
	{
		settleAtFalador(3);
		FarmingObserverStep s = step(falador(tick + 1, 3, 3, 32));
		PatchObservation o = only(s, "herb.falador");
		assertEquals(PatchObservation.Kind.CHANGE, o.getKind());
		assertEquals(3, o.getPrevious().getRaw());
		assertEquals(32, o.getReading().getRaw());
		assertEquals(o.getObservedAt() - TICK_MS, o.getPreviousObservedAt());
	}

	@Test
	public void modal_breaksContinuity_andTheChangeDuringItIsOnlyABaseline()
	{
		settleAtFalador(3);
		FarmingTickInput modal = tick(tick + 1, FALADOR).modalOpen(true).build();
		assertEquals(Status.MODAL_OPEN, step(modal).getStatus());
		// Planted while the bank was open: the first settled read is a baseline, never a planting.
		assertEquals(Status.SETTLING, step(falador(tick + 1, 3, 3, 32)).getStatus());
		assertEquals(Status.SETTLING, step(falador(tick + 1, 3, 3, 32)).getStatus());
		FarmingObserverStep after = step(falador(tick + 1, 3, 3, 32));
		assertEquals(Status.CAPTURED, after.getStatus());
		PatchObservation o = only(after, "herb.falador");
		assertEquals(PatchObservation.Kind.BASELINE, o.getKind());
		assertTrue(after.getCalibrationSamples().isEmpty());
	}

	@Test
	public void changeWhileSettling_isNeverRetroactivelyEligible()
	{
		settleAtFalador(3);
		step(tick(tick + 1, FALADOR).modalOpen(true).build());
		// Value changes on the first settling tick, then holds.
		step(falador(tick + 1, 3, 3, 3));
		step(falador(tick + 1, 3, 3, 32));
		FarmingObserverStep captured = step(falador(tick + 1, 3, 3, 32));
		assertEquals(PatchObservation.Kind.BASELINE, only(captured, "herb.falador").getKind());
		FarmingObserverStep next = step(falador(tick + 1, 3, 3, 32));
		assertTrue("settling finishing does not invent the unseen change", next.getObservations().isEmpty());
	}

	@Test
	public void missingTicks_loadingHopOrReconnect_breakContinuity()
	{
		settleAtFalador(3);
		tick += 3; // ticks the observer never saw
		for (int i = 0; i < 2; i++)
		{
			assertEquals(Status.SETTLING, step(falador(tick + 1, 3, 3, 32)).getStatus());
		}
		FarmingObserverStep s = step(falador(tick + 1, 3, 3, 32));
		assertEquals(PatchObservation.Kind.BASELINE, only(s, "herb.falador").getKind());
	}

	@Test
	public void logout_breaksContinuity()
	{
		settleAtFalador(3);
		assertEquals(Status.NOT_LOGGED_IN, step(tick(tick + 1, FALADOR).loggedIn(false).build()).getStatus());
		FarmingObserverStep s = settleAtFalador(32);
		assertEquals(PatchObservation.Kind.BASELINE, only(s, "herb.falador").getKind());
		assertFalse("a relog of the same account is not an owner change", s.isOwnerChanged());
	}

	@Test
	public void movingToADifferentContext_insideTheSameRegion_breaksContinuity()
	{
		settleAtFalador(3);
		// The south edge of the same region is Port Sarim's spirit tree: a different context.
		assertEquals(Status.SETTLING, step(tick(tick + 1, PORT_SARIM_EDGE).transmit(A, 3).transmit(D, 32).build()).getStatus());
		FarmingObserverStep back = settleAtFalador(32);
		assertEquals(PatchObservation.Kind.BASELINE, only(back, "herb.falador").getKind());
	}

	@Test
	public void jumpingStraightToAnotherPatchContext_mustSettleAgain()
	{
		settleAtFalador(3);
		// A teleport straight from the Falador allotments to the Falador tree patch: no empty tick between.
		assertEquals(Status.SETTLING, step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 9).build()).getStatus());
		assertEquals(Status.SETTLING, step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 9).build()).getStatus());
		assertEquals(Status.CAPTURED, step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 9).build()).getStatus());
	}

	@Test
	public void crossingIntoAnAliasRegion_resettles_likeRuneLitesRegionCrossingSkip()
	{
		// Catherby 11062 and its alias 11317 (ground floor) transmit the same patches, but RuneLite
		// never reads on a tick whose previous tick was in another map region.
		for (int i = 0; i < 4; i++)
		{
			step(tick(tick + 1, CATHERBY).transmit(A, 3).transmit(B, 3).transmit(D, 3).build());
		}
		assertEquals(Status.SETTLING, step(tick(tick + 1, CATHERBY_ALIAS_GROUND).transmit(A, 3).transmit(B, 3).transmit(D, 32).build()).getStatus());
		step(tick(tick + 1, CATHERBY_ALIAS_GROUND).transmit(A, 3).transmit(B, 3).transmit(D, 32).build());
		FarmingObserverStep s = step(tick(tick + 1, CATHERBY_ALIAS_GROUND).transmit(A, 3).transmit(B, 3).transmit(D, 32).build());
		assertEquals(PatchObservation.Kind.BASELINE, only(s, "herb.catherby").getKind());
	}

	@Test
	public void planeChange_atCatherby_breaksContinuity()
	{
		for (int i = 0; i < 4; i++)
		{
			step(tick(tick + 1, CATHERBY_ALIAS_GROUND).transmit(A, 3).transmit(B, 3).transmit(D, 3).build());
		}
		// Upstairs, the same region transmits Catherby's fruit tree instead: a different context.
		assertEquals(Status.SETTLING, step(tick(tick + 1, CATHERBY_ALIAS_UPSTAIRS).transmit(A, 32).build()).getStatus());
		FarmingObserverStep down = null;
		for (int i = 0; i < 3; i++)
		{
			down = step(tick(tick + 1, CATHERBY_ALIAS_GROUND).transmit(A, 3).transmit(B, 3).transmit(D, 32).build());
		}
		assertEquals(Status.CAPTURED, down.getStatus());
		assertEquals(PatchObservation.Kind.BASELINE, only(down, "herb.catherby").getKind());
	}

	@Test
	public void aliasRegion_isCapturedForItsOwningPatch()
	{
		FarmingObserverStep s = null;
		for (int i = 0; i < 4; i++)
		{
			s = step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 9).build());
		}
		assertEquals(Status.CAPTURED, s.getStatus());
		PatchObservation o = only(s, "tree.falador");
		assertEquals(11828, o.getPatch().getOwningRegionId());
		assertEquals(FarmingProduce.OAK, o.getReading().getProduce());
	}

	@Test
	public void missingVarbit_breaksOnlyThatPatch()
	{
		settleAtFalador(3);
		FarmingObserverStep gap = step(tick(tick + 1, FALADOR).transmit(A, 3).transmit(B, 3).build());
		assertEquals(Status.CAPTURED, gap.getStatus());
		FarmingObserverStep next = step(falador(tick + 1, 6, 3, 32));
		assertEquals(PatchObservation.Kind.BASELINE, only(next, "herb.falador").getKind());
		assertEquals("the allotment's chain was unbroken", PatchObservation.Kind.CHANGE, only(next, "allotment.falador.nw").getKind());
	}

	@Test
	public void accountSwitch_discardsEvidence_andIsReported()
	{
		settleAtFalador(3);
		FarmingObserverStep switched = null;
		for (int i = 0; i < 6; i++)
		{
			switched = step(tick(tick + 1, FALADOR).accountHash(OTHER_ACCOUNT).transmit(A, 3).transmit(B, 3).transmit(D, 32).build());
			if (switched.getStatus() == Status.CAPTURED)
			{
				break;
			}
		}
		assertEquals(FarmingOwner.standard(OTHER_ACCOUNT), switched.getOwner());
		assertEquals(PatchObservation.Kind.BASELINE, only(switched, "herb.falador").getKind());
		assertTrue(observer.getOwner().equals(FarmingOwner.standard(OTHER_ACCOUNT)));
	}

	@Test
	public void ownerChanged_isFlaggedOnTheFirstStepOfTheNewOwner()
	{
		settleAtFalador(3);
		step(tick(tick + 1, FALADOR).accountHash(OTHER_ACCOUNT).build()); // metadata changed: not ready yet
		FarmingObserverStep first = step(tick(tick + 1, FALADOR).accountHash(OTHER_ACCOUNT).build());
		assertTrue(first.isOwnerChanged());
		assertEquals(FarmingOwner.standard(OTHER_ACCOUNT), first.getOwner());
	}

	@Test
	public void nonStandardProfile_capturesNothing_andCannotContinueBack()
	{
		settleAtFalador(3);
		for (int i = 0; i < 4; i++)
		{
			FarmingObserverStep s = step(tick(tick + 1, FALADOR).profileType(RuneScapeProfileType.DEMONIC_PACTS_LEAGUE)
				.worldTypes(EnumSet.of(WorldType.SEASONAL)).transmit(D, 32).build());
			assertTrue(s.getObservations().isEmpty());
			assertEquals(null, s.getOwner());
		}
		FarmingObserverStep back = settleAtFalador(32);
		assertEquals(PatchObservation.Kind.BASELINE, only(back, "herb.falador").getKind());
	}

	@Test
	public void ordinaryWorld_withNoSpecialFlags_isCapturedNormally()
	{
		FarmingObserverStep s = null;
		for (int i = 0; i < 4; i++)
		{
			s = step(tick(tick + 1, FALADOR).worldTypes(EnumSet.noneOf(WorldType.class)).transmit(A, 3).transmit(B, 3).transmit(D, 3).build());
		}
		assertEquals(Status.CAPTURED, s.getStatus());
	}

	@Test
	public void welcomeScreen_and_instances_areNotCaptured()
	{
		settleAtFalador(3);
		assertEquals(Status.WELCOME_SCREEN, step(tick(tick + 1, FALADOR).welcomeScreenVisible(true).build()).getStatus());
		assertEquals(Status.INSTANCED, step(tick(tick + 1, FALADOR).instanced(true).build()).getStatus());
		assertEquals("both broke the sequence", Status.SETTLING, step(falador(tick + 1, 3, 3, 3)).getStatus());
	}

	// -- eligibility before live reads ------------------------------------------

	/** The adapter asks first, reads only what it is told, then hands the values over. */
	private FarmingObserverStep planned(FarmingTickInput.Builder builder, java.util.Map<Integer, Integer> live)
	{
		FarmingTickInput withoutValues = builder.build();
		List<Integer> plan = observer.varbitsToRead(withoutValues);
		for (int varbit : plan)
		{
			builder.transmit(varbit, live.get(varbit));
		}
		FarmingObserverStep s = step(builder.build());
		assertEquals("plan and capture agree at tick " + s.getObservedAt(), !plan.isEmpty(), s.getStatus() == Status.CAPTURED);
		return s;
	}

	@Test
	public void readPlan_isEmptyExactlyWhenTheTickWouldNotCount_andChangesNothing()
	{
		java.util.Map<Integer, Integer> live = new java.util.HashMap<>();
		live.put(A, 3);
		live.put(B, 3);
		live.put(C, 3);
		live.put(D, 32);
		// Asking repeatedly has no side effects.
		FarmingTickInput first = tick(1, FALADOR).build();
		assertTrue(observer.varbitsToRead(first).isEmpty());
		assertTrue(observer.varbitsToRead(first).isEmpty());
		int captured = 0;
		FarmingTickInput.Builder[] script = {
			tick(1, FALADOR), tick(2, FALADOR), tick(3, FALADOR), tick(4, FALADOR), tick(5, FALADOR),
			tick(6, FALADOR).modalOpen(true), tick(7, FALADOR), tick(8, FALADOR), tick(9, FALADOR),
			tick(10, FALADOR).welcomeScreenVisible(true), tick(11, FALADOR), tick(12, FALADOR), tick(13, FALADOR),
			tick(15, FALADOR), tick(16, FALADOR), tick(17, FALADOR),
			tick(18, PORT_SARIM_EDGE), tick(19, FALADOR), tick(20, FALADOR), tick(21, FALADOR),
			tick(22, FALADOR).accountHash(OTHER_ACCOUNT), tick(23, FALADOR).accountHash(OTHER_ACCOUNT),
			tick(24, FALADOR).accountHash(OTHER_ACCOUNT), tick(25, FALADOR).accountHash(OTHER_ACCOUNT),
			tick(26, FALADOR).accountHash(OTHER_ACCOUNT), tick(27, FALADOR).instanced(true),
			tick(28, FALADOR).loggedIn(false), tick(29, FALADOR).profileType(RuneScapeProfileType.BETA),
		};
		for (FarmingTickInput.Builder b : script)
		{
			if (planned(b, live).getStatus() == Status.CAPTURED)
			{
				captured++;
			}
		}
		assertTrue(captured >= 5);
	}

	@Test
	public void readPlan_namesExactlyTheResolvedPatchesVarbits()
	{
		settleAtFalador(3);
		List<Integer> plan = observer.varbitsToRead(tick(tick + 1, FALADOR).build());
		assertEquals(java.util.Arrays.asList(A, B, C, D), plan);
		List<Integer> treePlan = observer.varbitsToRead(tick(tick + 1, FALADOR_TREE_ALIAS).build());
		assertTrue("a different context must settle first", treePlan.isEmpty());
	}

	// -- calibration ----------------------------------------------------------

	private List<CalibrationSample> samplesFor(int fromHerb, int toHerb)
	{
		settleAtFalador(fromHerb);
		return step(falador(tick + 1, 3, 3, toHerb)).getCalibrationSamples();
	}

	@Test
	public void growthStage_tickToTick_isACalibrationSample()
	{
		List<CalibrationSample> samples = samplesFor(32, 33);
		assertEquals(1, samples.size());
		CalibrationSample s = samples.get(0);
		assertEquals(FarmingProduce.RANARR, s.getProduce());
		assertEquals(20, s.getTickMinutes());
		assertEquals(s.getObservedAt() - TICK_MS, s.getPreviousObservedAt());
	}

	@Test
	public void herbGrowingToHarvestable_isASample()
	{
		assertEquals(1, samplesFor(35, 36).size());
	}

	@Test
	public void planting_skippedStages_andWeeds_areNotSamples()
	{
		assertTrue("planting is a player action", samplesFor(3, 32).isEmpty());
		assertTrue("two stages at once is not one growth tick", new FarmingObserverTest().samplesFor(32, 34).isEmpty());
		assertTrue("weeds", new FarmingObserverTest().samplesFor(0, 1).isEmpty());
	}

	@Test
	public void tree_growingToCheckHealth_isASample_butCheckingHealthIsNot()
	{
		for (int i = 0; i < 4; i++)
		{
			step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 11).build());
		}
		assertEquals(1, step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 12).build()).getCalibrationSamples().size());
		assertTrue(step(tick(tick + 1, FALADOR_TREE_ALIAS).transmit(A, 13).build()).getCalibrationSamples().isEmpty());
	}

	@Test
	public void stageMetAfterAGap_isABaseline_withNoCalibrationSample_andKeepsThePlanting()
	{
		PatchKnowledge k = null;
		settleAtFalador(3);
		FarmingObserverStep planting = step(falador(tick + 1, 3, 3, 32));
		long plantedAt = planting.getObservedAt();
		k = applyAll(k, planting);
		FarmingObserverStep grew = step(falador(tick + 1, 3, 3, 33)); // stage 1, witnessed
		assertEquals("a witnessed growth tick is a sample", 1, grew.getCalibrationSamples().size());
		k = applyAll(k, grew);
		tick += 50; // the player leaves; ranarr grows one stage unseen (33 -> 34 would be a growth tick)
		PatchObservation returned = resettle(34);
		assertEquals(PatchObservation.Kind.BASELINE, returned.getKind());
		k = CropCycleAttribution.apply(k, returned);
		assertEquals(2, k.getCycle().getLastStage());
		assertFalse("stage first seen, not a transition observed", k.getCycle().isStageTransitionObserved());
		assertEquals(Long.valueOf(plantedAt), k.getCycle().getPlantedAt());
	}

	// -- explicit lifecycle interruption -------------------------------------------

	@Test
	public void interruption_thenANumericallyConsecutiveTick_mustSettleAgain_withAFreshBaseline()
	{
		settleAtFalador(33);
		step(falador(tick + 1, 3, 3, 33)); // captured, unchanged
		FarmingOwner owner = observer.getOwner();
		observer.interrupt(); // e.g. GameStateChanged(HOPPING), before any further tick
		observer.interrupt(); // repeated calls are safe
		assertEquals("the owner is kept", owner, observer.getOwner());
		// The very next tick number, with the herb one growth stage on (33 -> 34).
		assertTrue("nothing to read straight after an interruption", observer.varbitsToRead(falador(tick + 1, 3, 3, 34)).isEmpty());
		FarmingObserverStep first = step(falador(tick + 1, 3, 3, 34));
		assertEquals("profile readiness is re-established, not carried over", Status.PROFILE_NOT_READY, first.getStatus());
		assertTrue(first.getObservations().isEmpty());
		assertTrue(first.getCalibrationSamples().isEmpty());
		assertFalse("same owner after the interruption", first.isOwnerChanged());
		PatchObservation o = resettle(34);
		assertEquals("a fresh baseline, not a change across the interruption", PatchObservation.Kind.BASELINE, o.getKind());
	}

	@Test
	public void sameDeadRanarr_seenAgainAfterALogout_staysThatDeadRanarr()
	{
		PatchKnowledge k = null;
		settleAtFalador(3);
		FarmingObserverStep planting = step(falador(tick + 1, 3, 3, 32));
		long plantedAt = planting.getObservedAt();
		k = applyAll(k, planting);
		tick += 500; // away: it sickens and dies unseen
		PatchObservation dead = resettle(171);
		k = CropCycleAttribution.apply(k, dead);
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		observer.interrupt(); // logout
		tick += 1;
		PatchObservation again = resettle(171); // log back in: a fresh baseline of the same dead patch
		assertEquals(PatchObservation.Kind.BASELINE, again.getKind());
		k = CropCycleAttribution.apply(k, again);
		assertEquals(FarmingProduce.RANARR, k.getCycle().getProduce());
		assertEquals(PatchCondition.DEAD, k.getCycle().getLastCondition());
		assertEquals(CropCycle.Provenance.OBSERVED_PLANTING, k.getCycle().getProvenance());
		assertEquals(Long.valueOf(plantedAt), k.getCycle().getPlantedAt());
		assertEquals(plantedAt, k.getCycle().getFirstSeenAt());
		assertEquals(dead.getObservedAt(), k.getCycle().getStageFirstSeenAt());
		assertEquals(plantedAt, k.getLastObservedPlanting().getAt());
	}

	private PatchKnowledge applyAll(PatchKnowledge k, FarmingObserverStep s)
	{
		for (PatchObservation o : s.getObservations())
		{
			if (o.getPatch().getPatchId().equals("herb.falador"))
			{
				k = CropCycleAttribution.apply(k, o);
			}
		}
		return k;
	}

	/** Feeds Falador ticks until captured, asserting no sample on the way; returns the herb's observation. */
	private PatchObservation resettle(int herb)
	{
		for (int i = 0; i < 10; i++)
		{
			FarmingObserverStep s = step(falador(tick + 1, 3, 3, herb));
			assertTrue("no exact growth sample across a break", s.getCalibrationSamples().isEmpty());
			if (s.getStatus() == Status.CAPTURED)
			{
				return only(s, "herb.falador");
			}
		}
		throw new AssertionError("never captured");
	}

	@Test
	public void herbDiseasedToDead_isNotASample()
	{
		// RuneLite: a diseased state has no growth tick rate, and a dead herb's produce is unknown.
		assertTrue(samplesFor(140, 170).isEmpty());
	}
}
