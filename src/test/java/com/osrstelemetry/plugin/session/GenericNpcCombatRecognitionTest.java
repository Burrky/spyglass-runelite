package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run -- same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project (no RuneLite client jar,
 * no network access available in this environment). Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Targets the new
 * generic-NPC-recognition extension end to end: ClassifierContext's
 * recent-target sliding window, ActivitySignalClassifier's
 * genericCombatOrNpcIdentity()/genericNpcCombatIdentity(), the
 * SessionLifecycleEngine ACTIVE-candidate weak-evidence gate extension
 * (isWeakEvidenceAgainstEstablishedSession()), and a couple of
 * full-pipeline SessionRuntimeCoordinator tests proving two DIFFERENT
 * arbitrary NPC names flow through the exact same code path with
 * nothing NPC-specific anywhere in production code. Deliberately kept
 * in its own file rather than appended to ActivitySignalClassifierTest/
 * SessionLifecycleEngineTest/SessionRuntimeCoordinatorTest, so these
 * changes are reviewable independently of those large,
 * already-green suites.
 *
 * No mocking anywhere (this project has no Mockito dependency) -- every
 * test drives real production classes directly.
 *
 * A timing-related follow-up (kept in this same file -- these are
 * still exactly the generic-NPC-recognition feature's
 * own tests, just closing a timing gap in it): covers
 * SignalKind.RAW_COMBAT_XP_OBSERVED, ClassifierContext's split between
 * consumeRecentTargetNpcNameIfFresh() (refreshing; now used only by
 * that new kind) and peekRecentTargetNpcNameIfFresh() (read-only; now
 * used by XP_CHANGE's combat branch), and SkillsCollector's immediate
 * raw-pulse detection. See RAW_COMBAT_XP_OBSERVED's own javadoc for the
 * two problems this closes: (1) XP_CHANGE's slow, aggregated ~30s flush
 * cadence could easily miss the ~12s recent-target window entirely,
 * losing the NPC name on the very first real combat evidence; (2) that
 * same slow cadence, if allowed to REFRESH the window on every read,
 * could let a stale NPC identity self-perpetuate long after the player
 * genuinely stopped fighting it.
 */
public class GenericNpcCombatRecognitionTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final ActivitySignalClassifier classifier = new ActivitySignalClassifier();

	private Session activeSessionOf(ActivityIdentity identity, Instant startedAt)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(identity, startedAt);
		return engine.getCurrentSession();
	}

	/**
	 * TEMPORAL-DESIGN FOLLOW-UP FIX. Mirrors
	 * SessionRuntimeCoordinator.applyMetrics()'s own routing exactly (it
	 * is private there, so tests that drive SessionLifecycleEngine
	 * directly -- bypassing the coordinator -- must replicate the same
	 * two-part application a LifecycleResult documents: metricsForCurrent
	 * onto getCurrent()'s aggregates, and any abandoned candidate's
	 * contextualMetrics onto contextualMetricsTarget's aggregates).
	 */
	private void applyLifecycleMetrics(LifecycleResult result)
	{
		if (result.getCurrent() != null)
		{
			for (MetricUpdate metric : result.getMetricsForCurrent())
			{
				SessionAggregateUpdater.apply(result.getCurrent().getAggregates(), metric);
			}
		}
		if (result.getContextualMetricsTarget() != null)
		{
			for (MetricUpdate metric : result.getContextualMetrics())
			{
				SessionAggregateUpdater.apply(result.getContextualMetricsTarget().getAggregates(), metric);
			}
		}
	}

	// ----------------------------------------------------------------
	// 1 & 2: arbitrary attackable NPCs become named COMBAT identities
	// through the SAME generic path -- nothing NPC-specific anywhere.
	// ----------------------------------------------------------------

	@Test
	public void abyssalSpectre_recentTargetPlusCombatXp_becomesNamedGenericCombat()
	{
		ClassifierContext context = new ClassifierContext();

		SignalClassification contextResult = classifier.classify(
			SessionSignal.npcInteractionTarget(T0, 1234, "Abyssal spectre").withGameTick(10L), null, context);
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, contextResult.getDecisionKind());
		assertNull(contextResult.getIdentity());

		SignalClassification combatResult = classifier.classify(
			SessionSignal.xpChange(T0.plusMillis(600), "Strength", 40).withGameTick(11L), null, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, combatResult.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), combatResult.getIdentity());
		assertEquals(ActivityType.COMBAT, combatResult.getIdentity().getActivityType());
		assertEquals("Abyssal spectre", combatResult.getIdentity().getDisplayName());
		assertEquals(EvidenceStrength.ORDINARY, combatResult.getEvidenceStrength());
	}

	@Test
	public void hillGiant_sameGenericPath_noSpecialCasingRequired()
	{
		// Identical scenario to the Abyssal spectre test above, with a
		// completely different, arbitrary NPC name -- proves the path is
		// genuinely data-driven, not a hidden per-NPC branch.
		ClassifierContext context = new ClassifierContext();

		classifier.classify(SessionSignal.npcInteractionTarget(T0, 55, "Hill Giant").withGameTick(4L), null, context);
		SignalClassification combatResult = classifier.classify(
			SessionSignal.xpChange(T0.plusMillis(600), "Attack", 20).withGameTick(6L), null, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, combatResult.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Hill Giant"), combatResult.getIdentity());
		assertEquals(EvidenceStrength.ORDINARY, combatResult.getEvidenceStrength());
	}

	// ----------------------------------------------------------------
	// 3: no recent-target context at all (e.g. the collector never
	// emitted one because the interaction target wasn't genuinely
	// attackable) -- combat XP still falls back to plain generic
	// combat, exactly as before this feature.
	// ----------------------------------------------------------------

	@Test
	public void noRecentTargetContext_combatXpFallsBackToPlainGenericCombat()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "Strength", 40), null, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), result.getIdentity());
	}

	// ----------------------------------------------------------------
	// 4: interaction context alone never switches an established
	// Slayer/Boss session -- it is always IGNORE_FOR_SESSION.
	// ----------------------------------------------------------------

	@Test
	public void interactionAlone_neverProposesAnIdentity_regardlessOfCurrentSession()
	{
		Session activeBoss = activeSessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.npcInteractionTarget(T0.plusSeconds(5), 99, "Zygomite").withGameTick(8L),
			activeBoss, new ClassifierContext());

		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// ----------------------------------------------------------------
	// 5: ACTIVE generic combat A + one weak candidate for B does not
	// thrash -- it must go through the existing ACTIVE ORDINARY-evidence
	// candidate mechanism (arm on the first blip, confirm only on a
	// second, coherent observation of the SAME candidate, anchored at
	// the CONFIRMING observation's own instant, T2).
	// ----------------------------------------------------------------

	@Test
	public void singleWeakOffTargetCombatXp_doesNotThrashEstablishedGenericCombatSession()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity spectre = ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre");
		ActivityIdentity bloodveld = ActivitySignalClassifier.genericNpcCombatIdentity("Bloodveld");

		engine.onQualifyingActivity(spectre, T0, Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertEquals(spectre, engine.getCurrentSession().getActivityIdentity());

		LifecycleResult afterOneBlip = engine.onQualifyingActivity(
			bloodveld, T0.plusSeconds(3), Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// A single blip only arms a candidate -- the established session
		// is untouched, nothing finalized, nothing switched.
		assertNull(afterOneBlip.getFinalized());
		assertEquals(spectre, engine.getCurrentSession().getActivityIdentity());
		assertEquals(spectre, afterOneBlip.getCurrent().getActivityIdentity());
	}

	@Test
	public void secondCoherentBlip_confirmsTheSwitch_anchoredAtConfirmingInstant()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity spectre = ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre");
		ActivityIdentity bloodveld = ActivitySignalClassifier.genericNpcCombatIdentity("Bloodveld");

		engine.onQualifyingActivity(spectre, T0, Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		engine.onQualifyingActivity(bloodveld, T0.plusSeconds(3), Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		// EVIDENCE-WEIGHTED HYSTERESIS's extra peak/decay inertia does
		// NOT apply to a combat-branch candidate (generic-NPC
		// recognition keeps its own, separately-tuned, flat requirement
		// -- see SessionLifecycleEngine.activeRequiredConfirmations()'s
		// own javadoc), so this confirms at the second observation
		// exactly as before this feature.
		Instant confirmingInstant = T0.plusSeconds(6);
		LifecycleResult confirmed = engine.onQualifyingActivity(
			bloodveld, confirmingInstant, Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertEquals(bloodveld, engine.getCurrentSession().getActivityIdentity());
		assertEquals(bloodveld, confirmed.getCurrent().getActivityIdentity());
		assertEquals(spectre, confirmed.getFinalized().getActivityIdentity());
		// T2-anchored: the old session finalizes at the CONFIRMING
		// observation's own instant, never the first blip's T1, and
		// never fabricates any activity time beyond what was proven.
		assertEquals(confirmingInstant.toString(), confirmed.getFinalized().getFinalizedAt());
		assertEquals(confirmingInstant.toString(), confirmed.getCurrent().getStartedAt());
	}

	@Test
	public void unrelatedThirdCandidate_replacesThePendingOne_neverAccumulatesAcrossDifferentNpcs()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity spectre = ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre");
		ActivityIdentity bloodveld = ActivitySignalClassifier.genericNpcCombatIdentity("Bloodveld");
		ActivityIdentity nechryael = ActivitySignalClassifier.genericNpcCombatIdentity("Nechryael");

		engine.onQualifyingActivity(spectre, T0, Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		engine.onQualifyingActivity(bloodveld, T0.plusSeconds(3), Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		// A DIFFERENT weak candidate replaces the pending one instead of
		// confirming it -- the established session is still untouched.
		engine.onQualifyingActivity(nechryael, T0.plusSeconds(5), Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);

		assertEquals(spectre, engine.getCurrentSession().getActivityIdentity());

		// Repeating BLOODVELD now does not confirm anything either -- it
		// was replaced, so this is itself only a fresh single blip.
		engine.onQualifyingActivity(bloodveld, T0.plusSeconds(7), Collections.<MetricUpdate>emptyList(), EvidenceStrength.ORDINARY);
		assertEquals(spectre, engine.getCurrentSession().getActivityIdentity());
	}

	// ----------------------------------------------------------------
	// 6: known boss context still overrides generic combat immediately.
	// ----------------------------------------------------------------

	@Test
	public void bossActivityContext_overridesEstablishedGenericCombat_immediately()
	{
		Session activeGenericCombat = activeSessionOf(ActivitySignalClassifier.genericNpcCombatIdentity("Fire giant"), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.bossActivityContext(T0.plusSeconds(2), "Zulrah"), activeGenericCombat, new ClassifierContext());

		// generic COMBAT -> BOSSING is a REFINE (more specific, same
		// continuous activity) -- unchanged by this feature:
		// decideCombatBranch()'s currentIsGenericCombat check is
		// ActivityType-based, not key-based, so a NAMED generic combat
		// identity refines into a boss exactly like the old, unnamed one
		// always did.
		assertEquals(SignalDecisionKind.REFINE, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), result.getIdentity());
		assertEquals(EvidenceStrength.SPECIFIC, result.getEvidenceStrength());
	}

	// ----------------------------------------------------------------
	// 7: generic NPC recognition never downgrades an established boss
	// or Slayer session (adds/minions safety), and Slayer task progress
	// still upgrades/establishes the SLAYER identity as before.
	// ----------------------------------------------------------------

	@Test
	public void recentAddTargetCombatXp_doesNotDowngradeEstablishedBossSession()
	{
		Session activeBoss = activeSessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0.plusSeconds(1), 1, "Zygomite").withGameTick(2L), activeBoss, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(2), "Strength", 10).withGameTick(3L), activeBoss, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(activeBoss.getActivityIdentity(), result.getIdentity());
	}

	@Test
	public void recentDifferentNpcCombatXp_proposesGenericCombatIdentity_lifecycleCandidateGateProtectsEstablishedSlayerSession()
	{
		Session activeSlayer = activeSessionOf(
			ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), T0);
		ClassifierContext context = new ClassifierContext();
		// No active Slayer task is tracked in THIS context (a fresh
		// ClassifierContext -- the production runtime would have one,
		// but this isolates the generic-combat-vs-established-Slayer
		// precedence question specifically), and a recent target for an
		// unrelated ordinary NPC is armed.
		classifier.classify(SessionSignal.npcInteractionTarget(T0.plusSeconds(1), 2, "Cow").withGameTick(2L), activeSlayer, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(2), "Attack", 10).withGameTick(3L), activeSlayer, context);

		// "Cow" is
		// confirmed NOT a Gargoyles task-family member (see
		// SlayerTaskFamilyRegistry), so the classifier now honestly
		// proposes the real generic COMBAT identity at ORDINARY
		// strength instead of silently preserving the established
		// Slayer identity -- this is the classifier's new contract, not
		// a regression. The established Slayer session is still
		// protected from a single incidental off-task observation like
		// this one, but by SessionLifecycleEngine's existing ACTIVE
		// weak-evidence candidate gate (isWeakEvidenceAgainstEstablishedSession()),
		// not by the classifier silently absorbing the evidence. See
		// SessionRuntimeCoordinatorTest.offTaskCombatWhileActiveSlayer_singleIncidentalObservation_doesNotSwitch_secondConfirmingObservationSwitchesToGenericCombat
		// for the full end-to-end proof that one such observation does
		// not switch the session and a second, confirming one does.
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Cow"), result.getIdentity());
		assertNotEquals(activeSlayer.getActivityIdentity(), result.getIdentity());
		assertEquals(EvidenceStrength.ORDINARY, result.getEvidenceStrength());
	}

	@Test
	public void slayerTaskProgress_stillEstablishesSlayerIdentity_genericRecognitionUnrelated()
	{
		ClassifierContext context = new ClassifierContext();
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 1), null, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
		assertEquals(EvidenceStrength.SPECIFIC, result.getEvidenceStrength());
	}

	// ----------------------------------------------------------------
	// 9: NPC_DEATH never becomes player-kill credit / reliableCount
	// authority, whatever the current session's identity.
	// ----------------------------------------------------------------

	@Test
	public void npcDeath_neverBecomesKillCreditOrIdentityEvidence()
	{
		Session activeGenericCombat = activeSessionOf(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), T0);

		SignalClassification result = classifier.classify(SessionSignal.npcDeath(T0.plusSeconds(1)), activeGenericCombat, new ClassifierContext());

		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertTrue(result.getMetricUpdates().isEmpty());
	}

	// ----------------------------------------------------------------
	// 10: no-loot combat still establishes/maintains the named session
	// -- SERVER_NPC_LOOT is never required.
	// ----------------------------------------------------------------

	@Test
	public void noLootCombat_stillEstablishesNamedGenericCombatSession()
	{
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 77, "Nechryael").withGameTick(1L), null, context);

		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(2), "Hitpoints", 12).withGameTick(4L), null, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Nechryael"), result.getIdentity());
	}

	// ----------------------------------------------------------------
	// 11 & 12: recent-target expiry -- the exact bounded/sliding rule.
	// ----------------------------------------------------------------

	@Test
	public void expiredRecentTarget_tickBased_isNotUsedForLaterCombatXp()
	{
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(10L), null, context);

		// 21 ticks later -- one tick past RECENT_TARGET_MAX_AGE_TICKS (20).
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(30), "Strength", 10).withGameTick(31L), null, context);

		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), result.getIdentity());
	}

	@Test
	public void freshRecentTarget_tickBased_stillWithinBound_isUsed()
	{
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(10L), null, context);

		// Exactly 20 ticks later -- still within RECENT_TARGET_MAX_AGE_TICKS.
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(12), "Strength", 10).withGameTick(30L), null, context);

		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), result.getIdentity());
	}

	@Test
	public void expiredRecentTarget_wallClockFallback_whenNoTickAvailable_isNotUsed()
	{
		ClassifierContext context = new ClassifierContext();
		// No gameTick attached anywhere in this test -- forces the
		// wall-clock fallback bound (RECENT_TARGET_MAX_AGE, 12s).
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre"), null, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(13), "Strength", 10), null, context);

		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), result.getIdentity());
	}

	@Test
	public void briefNormalGapsBetweenAttacks_doNotEraseUsefulRecentTargetContext()
	{
		// A sustained fight: combat evidence arrives every ~10 ticks
		// (well within the bound), repeatedly, for far longer in total
		// than RECENT_TARGET_MAX_AGE_TICKS from the ORIGINAL interaction
		// -- the sliding window (each consuming read refreshes the
		// clock) is what keeps this alive, not a fixed TTL from T0.
		//
		// TEMPORAL-DESIGN FOLLOW-UP FIX: this used to feed repeated
		// xpChange signals directly to model that dense cadence. In real
		// production XP_CHANGE never actually arrives that densely (see
		// RAW_COMBAT_XP_OBSERVED's own javadoc -- it is SkillsCollector's
		// own slow, ~30s-aggregated event) -- and, as of this fix,
		// XP_CHANGE's combat branch only PEEKS at the watch and can no
		// longer refresh it at all (see
		// ClassifierContext.peekRecentTargetNpcNameIfFresh()), precisely
		// to stop a stale identity self-perpetuating from that slow
		// cadence. RAW_COMBAT_XP_OBSERVED is now the signal that
		// genuinely fires at this dense, real per-hit cadence and is the
		// one actually responsible for this guarantee in production, so
		// this test now exercises it directly instead.
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(0L), null, context);

		SignalClassification hit1 = classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(6), "Strength").withGameTick(10L), null, context);
		SignalClassification hit2 = classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(12), "Strength").withGameTick(20L), null, context);
		SignalClassification hit3 = classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(18), "Strength").withGameTick(30L), null, context);
		SignalClassification hit4 = classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(24), "Strength").withGameTick(40L), null, context);

		ActivityIdentity spectre = ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre");
		assertEquals(spectre, hit1.getIdentity());
		assertEquals(spectre, hit2.getIdentity());
		assertEquals(spectre, hit3.getIdentity());
		assertEquals(spectre, hit4.getIdentity());
		// Item 5 (temporal-design follow-up fix): still NO XP metric from
		// any of these -- lifecycle-only, exactly as designed.
		assertTrue(hit1.getMetricUpdates().isEmpty());
		assertTrue(hit4.getMetricUpdates().isEmpty());
	}

	// ----------------------------------------------------------------
	// TEMPORAL-DESIGN FOLLOW-UP FIX: closing the gap between a fresh
	// interaction and XP_CHANGE's slow, aggregated flush cadence.
	// ----------------------------------------------------------------

	@Test
	public void immediateRawCombatEvidence_establishesNamedIdentityRightAfterInteraction()
	{
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1234, "Abyssal spectre").withGameTick(10L), null, context);

		SignalClassification result = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(11L), null, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), result.getIdentity());
		assertEquals(EvidenceStrength.ORDINARY, result.getEvidenceStrength());
		// Item 5 (HARD RULE): lifecycle-only, never an XP metric.
		assertTrue(result.getMetricUpdates().isEmpty());
	}

	@Test
	public void namedCombatSession_establishedBeforeAnyDelayedXpChangeEverArrives()
	{
		// Models the real cadence gap directly: SkillsCollector's own
		// XP_CHANGE would not arrive for ~50 ticks / ~30s in production
		// (see OsrsTelemetryPlugin.XP_FLUSH_INTERVAL_TICKS) -- this test
		// asserts the named session already exists barely a tick after
		// the interaction, using ONLY the immediate raw signal, with no
		// xpChange signal anywhere in this test at all.
		ClassifierContext context = new ClassifierContext();
		SessionLifecycleEngine engine = new SessionLifecycleEngine();

		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(1L), null, context);
		SignalClassification raw = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(2L), null, context);
		applyLifecycleMetrics(engine.onQualifyingActivity(raw.getIdentity(), T0.plusMillis(600), raw.getMetricUpdates(), raw.getEvidenceStrength()));

		assertEquals(SessionState.ACTIVE, engine.getCurrentSession().getState());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), engine.getCurrentSession().getActivityIdentity());
	}

	@Test
	public void delayedXpChange_addsAggregatedXpMetricExactlyOnce_whileFightContinues()
	{
		ClassifierContext context = new ClassifierContext();
		SessionLifecycleEngine engine = new SessionLifecycleEngine();

		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(0L), null, context);

		SignalClassification raw1 = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(1L), null, context);
		applyLifecycleMetrics(engine.onQualifyingActivity(raw1.getIdentity(), T0.plusMillis(600), raw1.getMetricUpdates(), raw1.getEvidenceStrength()));

		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), engine.getCurrentSession().getActivityIdentity());
		assertTrue(engine.getCurrentSession().getAggregates().getXpGainedBySkill().isEmpty());

		// The fight continues -- further raw pulses (real cadence is
		// much faster than this; a few representative ticks suffice
		// here) keep the recent-target watch fresh all the way up to
		// the moment the slow, aggregated XP_CHANGE for this same fight
		// finally arrives (~50 ticks / ~30s later in real production).
		classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(9), "Strength").withGameTick(15L), engine.getCurrentSession(), context);
		classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(17), "Strength").withGameTick(29L), engine.getCurrentSession(), context);
		classifier.classify(SessionSignal.rawCombatXpObserved(T0.plusSeconds(26), "Strength").withGameTick(43L), engine.getCurrentSession(), context);

		SignalClassification delayed = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(30), "Strength", 187).withGameTick(50L), engine.getCurrentSession(), context);
		applyLifecycleMetrics(engine.onQualifyingActivity(delayed.getIdentity(), T0.plusSeconds(30), delayed.getMetricUpdates(), delayed.getEvidenceStrength()));

		// Same named identity throughout (a heartbeat, never a switch --
		// the watch was still fresh via peekRecentTargetNpcNameIfFresh()),
		// and the aggregated XP delta is applied EXACTLY ONCE.
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), engine.getCurrentSession().getActivityIdentity());
		assertEquals(1, engine.getCurrentSession().getAggregates().getXpGainedBySkill().size());
		assertEquals(Long.valueOf(187L), engine.getCurrentSession().getAggregates().getXpGainedBySkill().get("Strength"));
	}

	@Test
	public void multipleRawPulsesAcrossSkills_neverContributeXp_onlyXpChangeDoes_noDoubleCounting()
	{
		ClassifierContext context = new ClassifierContext();
		SessionLifecycleEngine engine = new SessionLifecycleEngine();

		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Hill Giant").withGameTick(0L), null, context);

		SignalClassification raw = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Attack").withGameTick(1L), null, context);
		applyLifecycleMetrics(engine.onQualifyingActivity(raw.getIdentity(), T0.plusMillis(600), raw.getMetricUpdates(), raw.getEvidenceStrength()));

		// Several more raw pulses for DIFFERENT combat skills -- a single
		// melee hit commonly grants Attack/Strength/Defence/Hitpoints XP
		// together -- none of them carry a metric.
		for (String skill : new String[] {"Strength", "Defence", "Hitpoints", "Attack"})
		{
			SignalClassification pulse = classifier.classify(
				SessionSignal.rawCombatXpObserved(T0.plusSeconds(1), skill).withGameTick(2L), engine.getCurrentSession(), context);
			assertTrue(pulse.getMetricUpdates().isEmpty());
		}

		assertTrue(engine.getCurrentSession().getAggregates().getXpGainedBySkill().isEmpty());

		// Only the eventual aggregated XP_CHANGE events actually add
		// metrics -- one per skill, exactly once each.
		SignalClassification xpAttack = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(2), "Attack", 40).withGameTick(3L), engine.getCurrentSession(), context);
		applyLifecycleMetrics(engine.onQualifyingActivity(xpAttack.getIdentity(), T0.plusSeconds(2), xpAttack.getMetricUpdates(), xpAttack.getEvidenceStrength()));

		SignalClassification xpStrength = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(2), "Strength", 40).withGameTick(3L), engine.getCurrentSession(), context);
		applyLifecycleMetrics(engine.onQualifyingActivity(xpStrength.getIdentity(), T0.plusSeconds(2), xpStrength.getMetricUpdates(), xpStrength.getEvidenceStrength()));

		assertEquals(Long.valueOf(40L), engine.getCurrentSession().getAggregates().getXpGainedBySkill().get("Attack"));
		assertEquals(Long.valueOf(40L), engine.getCurrentSession().getAggregates().getXpGainedBySkill().get("Strength"));
		assertEquals(2, engine.getCurrentSession().getAggregates().getXpGainedBySkill().size());
	}

	@Test
	public void repeatedDelayedXpChange_cannotKeepAnAbandonedNpcTargetAlive()
	{
		// The player genuinely stops fighting Abyssal spectre right
		// after the fast raw path establishes it -- no further
		// InteractingChanged, no further raw pulses. Two SLOW, delayed
		// XP_CHANGE events (the ~30s real cadence) then arrive in
		// succession for some trailing/incidental Strength XP. Because
		// XP_CHANGE only PEEKS (never refreshes) the recent-target
		// watch, by the time either arrives the watch has genuinely
		// expired -- so neither can keep asserting "Abyssal spectre"
		// merely because it keeps re-reading a watch nothing is
		// refreshing anymore. The existing ACTIVE weak-evidence
		// candidate gate still applies (one blip only arms a candidate),
		// so it takes the SECOND delayed XP_CHANGE to actually confirm
		// the honest fallback.
		ClassifierContext context = new ClassifierContext();
		SessionLifecycleEngine engine = new SessionLifecycleEngine();

		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(0L), null, context);
		SignalClassification raw = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(1L), null, context);
		applyLifecycleMetrics(engine.onQualifyingActivity(raw.getIdentity(), T0.plusMillis(600), raw.getMetricUpdates(), raw.getEvidenceStrength()));
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), engine.getCurrentSession().getActivityIdentity());

		// First delayed XP_CHANGE, tick 50 (well past the watch's last
		// refresh at tick 1 + RECENT_TARGET_MAX_AGE_TICKS) -- the peek
		// finds nothing fresh, so this honestly falls back to plain
		// generic combat. Same-rank-different-key ORDINARY evidence
		// against an ACTIVE session only ARMS a candidate.
		SignalClassification delayed1 = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(30), "Strength", 5).withGameTick(50L), engine.getCurrentSession(), context);
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), delayed1.getIdentity());
		applyLifecycleMetrics(engine.onQualifyingActivity(delayed1.getIdentity(), T0.plusSeconds(30), delayed1.getMetricUpdates(), delayed1.getEvidenceStrength()));
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Abyssal spectre"), engine.getCurrentSession().getActivityIdentity());

		// A SECOND delayed XP_CHANGE, ~30s later still -- confirms the
		// candidate. The abandoned NPC identity was NOT kept alive
		// merely because delayed XP_CHANGE kept arriving.
		// EVIDENCE-WEIGHTED HYSTERESIS's extra peak/decay inertia does
		// NOT apply here -- genericCombatIdentity() is combat-branch, so
		// this keeps the flat, unconditional requirement (see
		// SessionLifecycleEngine.activeRequiredConfirmations()'s own
		// javadoc) and confirms exactly as before this feature.
		SignalClassification delayed2 = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(60), "Strength", 5).withGameTick(100L), engine.getCurrentSession(), context);
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), delayed2.getIdentity());
		applyLifecycleMetrics(engine.onQualifyingActivity(delayed2.getIdentity(), T0.plusSeconds(60), delayed2.getMetricUpdates(), delayed2.getEvidenceStrength()));

		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), engine.getCurrentSession().getActivityIdentity());
	}

	@Test
	public void rawCombatXpObserved_ignoresMagicAndNonCombatSkills_defenseInDepth()
	{
		// SkillsCollector.RAW_COMBAT_SKILLS already excludes Magic and
		// every non-combat skill at the source; this pins
		// ActivitySignalClassifier.classifyRawCombatXpObserved()'s own
		// independent re-check as a second, defense-in-depth guarantee.
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 1, "Abyssal spectre").withGameTick(0L), null, context);

		SignalClassification magic = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Magic").withGameTick(1L), null, context);
		SignalClassification woodcutting = classifier.classify(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Woodcutting").withGameTick(1L), null, context);

		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, magic.getDecisionKind());
		assertNull(magic.getIdentity());
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, woodcutting.getDecisionKind());
		assertNull(woodcutting.getIdentity());
	}

	// ----------------------------------------------------------------
	// SessionEventMapper: the new EventType maps verbatim, same
	// discipline as every other mapped kind.
	// ----------------------------------------------------------------

	@Test
	public void sessionEventMapper_npcInteractionTarget_mapsVerbatim()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.NPC_INTERACTION_TARGET, new EventPayloads.NpcInteractionTarget(4321, "Bloodveld"), T0);

		assertEquals(SignalKind.NPC_INTERACTION_TARGET, signal.getKind());
		assertEquals(Integer.valueOf(4321), signal.getNpcId());
		assertEquals("Bloodveld", signal.getNpcName());
	}

	@Test
	public void sessionEventMapper_rawCombatXpObserved_mapsVerbatim_noXpValueAnywhere()
	{
		SessionSignal signal = SessionEventMapper.map(
			EventType.RAW_COMBAT_XP_OBSERVED, new EventPayloads.RawCombatXpObserved("STRENGTH"), T0);

		assertEquals(SignalKind.RAW_COMBAT_XP_OBSERVED, signal.getKind());
		assertEquals("STRENGTH", signal.getSkill());
		// HARD RULE: structurally no XP delta anywhere on this signal.
		assertNull(signal.getXpDelta());
	}

	// ----------------------------------------------------------------
	// Full pipeline (SessionRuntimeCoordinator): two DIFFERENT arbitrary
	// NPC names flow through the IDENTICAL generic path end to end, with
	// no hand-maintained NPC registry anywhere in production code.
	// ----------------------------------------------------------------

	private static final long ACCOUNT_NPC_TEST = 700_900_100L;

	private LocalStateStore store;
	private SessionRuntimeCoordinator coordinator;

	@Before
	public void setUpCoordinator() throws Exception
	{
		deleteAccountDir(ACCOUNT_NPC_TEST);
		store = new LocalStateStore();
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
	}

	@After
	public void tearDownCoordinator() throws Exception
	{
		store.shutdown();
		deleteAccountDir(ACCOUNT_NPC_TEST);
	}

	private void deleteAccountDir(long accountHash) throws Exception
	{
		File dir = TelemetryPaths.accountDir(accountHash);
		if (dir.exists())
		{
			File sessionsDir = new File(dir, "sessions");
			if (sessionsDir.exists())
			{
				File[] sessionFiles = sessionsDir.listFiles();
				if (sessionFiles != null)
				{
					for (File f : sessionFiles)
					{
						Files.deleteIfExists(f.toPath());
					}
				}
				Files.deleteIfExists(sessionsDir.toPath());
			}
			File[] files = dir.listFiles();
			if (files != null)
			{
				for (File f : files)
				{
					Files.deleteIfExists(f.toPath());
				}
			}
			Files.deleteIfExists(dir.toPath());
		}
	}

	@Test
	public void pipeline_arbitraryNpc_zygomite_establishesNamedCombatSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_NPC_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 501, "Zygomite").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(1), "Strength", 20).withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(1));

		Session current = coordinator.testCurrentSession();
		assertEquals(ActivityType.COMBAT, current.getActivityIdentity().getActivityType());
		assertEquals("zygomite", current.getActivityIdentity().getActivityKey());
		assertEquals("Zygomite", current.getActivityIdentity().getDisplayName());
	}

	@Test
	public void pipeline_differentArbitraryNpc_mutatedBloodveld_establishesNamedCombatSession_sameGenericPath()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_NPC_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 502, "Mutated Bloodveld").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(1), "Ranged", 20).withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(1));

		Session current = coordinator.testCurrentSession();
		assertEquals(ActivityType.COMBAT, current.getActivityIdentity().getActivityType());
		assertEquals("mutated bloodveld", current.getActivityIdentity().getActivityKey());
		assertEquals("Mutated Bloodveld", current.getActivityIdentity().getDisplayName());
	}

	// ----------------------------------------------------------------
	// HARD SLAYER RULE regression. A currently assigned
	// Slayer task is CONTEXT ONLY. Reproduces the exact live bug --
	// task "Gargoyles" assigned at login, fighting Desert Wolf then Goat
	// (both correctly named generic COMBAT), with NO
	// SLAYER_TASK_PROGRESS ever occurring -- and proves the session
	// never gets silently promoted to SLAYER/Gargoyles. Also proves
	// NPC_DEATH/SERVER_NPC_LOOT stay observational/ownership-only, and
	// that genuine SLAYER_TASK_PROGRESS evidence still correctly
	// upgrades the session via the existing SPECIFIC path, after which
	// it resists downgrade from further weak generic combat evidence.
	// ----------------------------------------------------------------

	@Test
	public void pipeline_hardSlayerRule_assignedTaskContextNeverPromotesOrdinaryCombatToSlayer()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_NPC_TEST);

		// 1: seed an active assigned Slayer task -- Gargoyles, amount 27
		// -- exactly like the live SLAYER_TASK_ASSIGNED at login. This
		// is CONTEXT ONLY; it must never by itself produce a SLAYER
		// identity.
		coordinator.testEnqueueSignal(
			SessionSignal.slayerTaskAssigned(T0, "Gargoyles", "Catacombs of Kourend", 27).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		// 2 & 3: interact with Desert Wolf, then genuine raw combat XP
		// establishes named generic COMBAT/Desert Wolf -- correct, as
		// live-observed.
		coordinator.testEnqueueSignal(
			SessionSignal.npcInteractionTarget(T0.plusSeconds(1), 601, "Desert Wolf").withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(1));
		coordinator.testEnqueueSignal(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(1600), "Strength").withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusMillis(1600));

		Session afterWolf = coordinator.testCurrentSession();
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Desert Wolf"), afterWolf.getActivityIdentity());

		// 4 & 5: interact with Goat, then repeated raw combat XP
		// establishes/confirms named generic COMBAT/Goat -- correct, as
		// live-observed. Switching between two named generic-combat
		// identities goes through the existing ACTIVE ORDINARY-evidence
		// candidate gate (arm on the first blip, confirm on the second).
		coordinator.testEnqueueSignal(
			SessionSignal.npcInteractionTarget(T0.plusSeconds(2), 602, "Goat").withGameTick(4L));
		coordinator.testProcessTick(5L, T0.plusSeconds(2));

		coordinator.testEnqueueSignal(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(2600), "Strength").withGameTick(5L));
		coordinator.testProcessTick(6L, T0.plusMillis(2600));
		// First blip only ARMS a candidate -- Desert Wolf is untouched.
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Desert Wolf"), coordinator.testCurrentSession().getActivityIdentity());

		coordinator.testEnqueueSignal(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(3600), "Strength").withGameTick(6L));
		coordinator.testProcessTick(7L, T0.plusMillis(3600));
		// Second coherent blip CONFIRMS the switch to Goat.
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), coordinator.testCurrentSession().getActivityIdentity());

		// 6: continue repeated Goat combat evidence -- both the fast raw
		// path AND the slow, aggregated XP_CHANGE path (the exact live
		// bug's own mechanism -- classifyXpChange()'s combat branch).
		// Expected: session remains COMBAT/Goat. SLAYER/Gargoyles must
		// NOT appear merely because Gargoyles is assigned.
		coordinator.testEnqueueSignal(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(4600), "Strength").withGameTick(7L));
		coordinator.testProcessTick(8L, T0.plusMillis(4600));
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), coordinator.testCurrentSession().getActivityIdentity());

		coordinator.testEnqueueSignal(
			SessionSignal.xpChange(T0.plusMillis(5600), "Strength", 40).withGameTick(8L));
		coordinator.testProcessTick(9L, T0.plusMillis(5600));
		Session afterOrdinaryCombatXp = coordinator.testCurrentSession();
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), afterOrdinaryCombatXp.getActivityIdentity());
		assertNotEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), afterOrdinaryCombatXp.getActivityIdentity());

		// 7: NPC_DEATH is observational/corroborating ONLY -- never
		// player kill credit, never Slayer progress, never authority
		// over the current session's identity, regardless of any
		// activeSlayerTaskName the live collector layer separately
		// annotates its own payload with (that annotation never reaches
		// this classifier -- see SessionSignal.npcDeath()'s own
		// parameterless factory and ActivitySignalClassifier's
		// NPC_DEATH case, which is an unconditional ignore()).
		coordinator.testEnqueueSignal(SessionSignal.npcDeath(T0.plusSeconds(6)).withGameTick(9L));
		coordinator.testProcessTick(10L, T0.plusSeconds(6));
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), coordinator.testCurrentSession().getActivityIdentity());

		// 8: SERVER_NPC_LOOT/Goat remains associated with the current
		// generic combat session per existing ownership semantics --
		// metric-only, no identity change.
		coordinator.testEnqueueSignal(SessionSignal.serverNpcLoot(T0.plusMillis(6100), "Goat",
			Collections.singletonList(new SessionSignal.LootDrop(526, "Bones", 1))).withGameTick(10L));
		coordinator.testProcessTick(11L, T0.plusMillis(6100));
		Session afterLoot = coordinator.testCurrentSession();
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), afterLoot.getActivityIdentity());
		assertEquals(1, afterLoot.getAggregates().getLootDrops().size());
		assertEquals("Goat", afterLoot.getAggregates().getLootDrops().get(0).getSourceName());

		// 9: genuine SLAYER_TASK_PROGRESS/Gargoyles now arrives -- THIS
		// is the existing, authoritative, SPECIFIC-evidence path that is
		// actually allowed to establish SLAYER/Gargoyles.
		coordinator.testEnqueueSignal(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(7), "Gargoyles", "Catacombs of Kourend", 1, 26).withGameTick(11L));
		coordinator.testProcessTick(12L, T0.plusSeconds(7));
		Session afterProgress = coordinator.testCurrentSession();
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), afterProgress.getActivityIdentity());

		// 10: once genuinely established, weaker generic combat evidence
		// (another raw pulse against the same recent Goat target) must
		// NOT downgrade the established SLAYER/Gargoyles session.
		coordinator.testEnqueueSignal(
			SessionSignal.rawCombatXpObserved(T0.plusMillis(7600), "Strength").withGameTick(12L));
		coordinator.testProcessTick(13L, T0.plusMillis(7600));
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), coordinator.testCurrentSession().getActivityIdentity());
	}
}
