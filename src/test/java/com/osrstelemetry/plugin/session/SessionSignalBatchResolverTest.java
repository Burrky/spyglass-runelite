package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Tests the new
 * BatchLifecycleOutcome-typed result, without any
 * expectation of lexicographic tie-breaking. Covers batch resolution,
 * gameTick grouping, order-independence via full permutation checks,
 * the Barbarian Fishing regression family, true skill switches, the
 * corrected SLAYER->BOSSING switch at batch scope, metric preservation
 * under ambiguity, and the exact-boundary runtime-ordering contract.
 */
public class SessionSignalBatchResolverTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final ActivitySignalClassifier classifier = new ActivitySignalClassifier();

	private Session sessionOf(ActivityIdentity identity, Instant lastActiveAt)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(identity, lastActiveAt);
		return engine.getCurrentSession();
	}

	private static <T> List<List<T>> permutations(List<T> items)
	{
		List<List<T>> result = new ArrayList<>();
		permute(new ArrayList<>(items), 0, result);
		return result;
	}

	private static <T> void permute(List<T> items, int k, List<List<T>> result)
	{
		if (k == items.size())
		{
			result.add(new ArrayList<>(items));
			return;
		}
		for (int i = k; i < items.size(); i++)
		{
			java.util.Collections.swap(items, k, i);
			permute(items, k + 1, result);
			java.util.Collections.swap(items, k, i);
		}
	}

	// ===== Barbarian Fishing regression family =====

	// Test 1: no current session, Fishing + Agility XP in one tick must NOT resolve lexicographically (i.e.
	// must NOT silently pick Agility just because it sorts first) -- it must be reported AMBIGUOUS.
	@Test
	public void noCurrentFishingAndAgilityIsAmbiguousNotLexicographic()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.xpChange(T0, "Agility", 10).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
			assertNull(r.getLifecycleClassification());
			assertNull(r.getLifecycleTimestamp());
		}
	}

	// Test 2: no current session, Fishing + Agility + incidental Strength XP in one tick is still AMBIGUOUS
	// between Fishing and Agility (generic combat is eliminated, but the two named skills remain unresolved).
	@Test
	public void noCurrentFishingAgilityStrengthIsAmbiguousNotLexicographic()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.xpChange(T0, "Agility", 10).withGameTick(1),
			SessionSignal.xpChange(T0, "Strength", 8).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
			assertEquals(3, r.getMetricUpdates().size());
		}
	}

	// Test 3: an existing Fishing session stays Fishing when the same-tick batch also carries incidental
	// Agility and Strength XP -- and every one of those XP metrics still survives.
	@Test
	public void existingFishingSessionStaysFishingWithSecondaryXp()
	{
		Session current = sessionOf(ActivitySignalClassifier.skillingIdentity("Fishing"), T0);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0.plusSeconds(4), "Fishing", 20).withGameTick(2),
			SessionSignal.xpChange(T0.plusSeconds(4), "Agility", 10).withGameTick(2),
			SessionSignal.xpChange(T0.plusSeconds(4), "Strength", 8).withGameTick(2)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, current, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(current.getActivityIdentity(), r.getLifecycleClassification().getIdentity());
			assertEquals(3, r.getMetricUpdates().size());
		}
	}

	// Test 4: an existing Agility session is NOT arbitrarily replaced by Fishing merely because Fishing XP is
	// present in the same tick -- the same conservative stickiness rule applies regardless of which skill.
	@Test
	public void existingAgilitySessionIsNotReplacedByFishing()
	{
		Session current = sessionOf(ActivitySignalClassifier.skillingIdentity("Agility"), T0);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0.plusSeconds(4), "Fishing", 20).withGameTick(2),
			SessionSignal.xpChange(T0.plusSeconds(4), "Agility", 10).withGameTick(2),
			SessionSignal.xpChange(T0.plusSeconds(4), "Strength", 8).withGameTick(2)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(current.getActivityIdentity(), r.getLifecycleClassification().getIdentity());
	}

	// Test 5: an ambiguous first tick starts no session at all; unambiguous Fishing evidence on a later tick
	// starts SKILLING/fishing normally -- no generic/placeholder identity was ever fabricated in between.
	@Test
	public void laterUnambiguousFishingEvidenceStartsNormallyAfterAnAmbiguousTick()
	{
		List<SessionSignal> ambiguousTick = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.xpChange(T0, "Agility", 10).withGameTick(1)
		);
		SessionSignalBatchResolver.BatchResolution first = SessionSignalBatchResolver.resolve(ambiguousTick, null, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, first.getOutcome());

		List<SessionSignal> laterTick = Arrays.asList(SessionSignal.xpChange(T0.plusSeconds(3), "Fishing", 20).withGameTick(2));
		SessionSignalBatchResolver.BatchResolution second = SessionSignalBatchResolver.resolve(laterTick, null, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, second.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Fishing"), second.getLifecycleClassification().getIdentity());
	}

	// ===== True skill switches =====

	// Test 6: a Woodcutting session followed by a separate tick containing ONLY Mining XP is a true switch.
	@Test
	public void woodcuttingToMiningOnSeparateTickIsRealSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.skillingIdentity("Woodcutting"), T0);
		List<SessionSignal> batch = Arrays.asList(SessionSignal.xpChange(T0.plusSeconds(10), "Mining", 25).withGameTick(9));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Mining"), r.getLifecycleClassification().getIdentity());
	}

	// Test 7: a Mining session followed by a separate tick containing ONLY Smithing XP is a true switch.
	@Test
	public void miningToSmithingOnSeparateTickIsRealSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.skillingIdentity("Mining"), T0);
		List<SessionSignal> batch = Arrays.asList(SessionSignal.xpChange(T0.plusSeconds(10), "Smithing", 25).withGameTick(9));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Smithing"), r.getLifecycleClassification().getIdentity());
	}

	// Test 8: a Fishing session followed by a separate tick with ONLY Agility XP is a true switch to Agility.
	@Test
	public void fishingToAgilityOnSeparateTickIsRealSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.skillingIdentity("Fishing"), T0);
		List<SessionSignal> batch = Arrays.asList(SessionSignal.xpChange(T0.plusSeconds(10), "Agility", 25).withGameTick(9));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Agility"), r.getLifecycleClassification().getIdentity());
	}

	// ===== Same-rank combat conflicts =====

	// Test 9: two different, same-rank bosses in one batch with no current session never resolve alphabetically -- AMBIGUOUS.
	@Test
	public void twoDifferentBossesNoCurrentSessionIsAmbiguousNotAlphabetical()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1),
			SessionSignal.bossKill(T0, "Vorkath", 1).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
			assertEquals(2, r.getMetricUpdates().size());
		}
	}

	// Test 10: an exact current-session boss match resolves the same conflict in favor of the active boss.
	@Test
	public void currentExactBossMatchResolvesSameRankConflict()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.bossKill(T0.plusSeconds(5), "Zulrah", 6).withGameTick(1),
			SessionSignal.bossKill(T0.plusSeconds(5), "Vorkath", 1).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, current, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(current.getActivityIdentity(), r.getLifecycleClassification().getIdentity());
		}
	}

	// Test 11: two different, same-rank Slayer identities in one batch with no current session never resolve alphabetically -- AMBIGUOUS.
	@Test
	public void twoDifferentSlayerTasksNoCurrentSessionIsAmbiguousNotAlphabetical()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 129).withGameTick(1),
			SessionSignal.slayerTaskProgress(T0, "Abyssal demons", "Slayer Tower", 50).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
		}
	}

	// ===== Cross-branch ambiguity =====

	// Test 12: mixed Skilling + combat-branch evidence in one batch with no current session and no rank
	// relationship between the branches is AMBIGUOUS, never resolved by a global numeric rank.
	@Test
	public void mixedSkillingAndSlayerEvidenceNoCurrentSessionIsAmbiguous()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 129).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
		}
	}

	// ===== Metric behavior under ambiguity =====

	// Test 13: an ambiguous batch retains every valid metric (all permutations return the identical count).
	@Test
	public void ambiguousBatchRetainsAllMetrics()
	{
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2444, "Zulrah's scales", 1000));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.xpChange(T0, "Agility", 10).withGameTick(1),
			SessionSignal.serverNpcLoot(T0, "Zulrah", drops).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
			// Fishing XP + Agility XP metrics always present; loot is metric-only and requires an ACTIVE
			// session to attach to (there is none here), so only the 2 XP metrics survive -- never fabricated.
			assertEquals(2, r.getMetricUpdates().size());
		}
	}

	// Test 14: an ambiguous batch produces no lifecycle classification/timestamp -- active duration is never advanced from it.
	@Test
	public void ambiguousBatchProducesNoLifecycleClassificationOrTimestamp()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.xpChange(T0, "Agility", 10).withGameTick(1)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, null, classifier, new ClassifierContext());
		assertNull(r.getLifecycleClassification());
		assertNull(r.getLifecycleTimestamp());
	}

	// Test 15: a metric-only batch (no lifecycle-carrying signal at all) is NO_EVIDENCE, distinct from AMBIGUOUS.
	// LOOT-WHILE-ACTIVE OWNERSHIP: this loot is classified METRIC_ONLY because `current`
	// is genuinely ACTIVE, so it now surfaces via
	// getPreBatchCurrentMetricUpdates() (belongs unconditionally to that
	// pre-batch session) rather than getMetricUpdates() (which rides
	// with whichever identity a batch resolves to) -- see
	// SessionSignalBatchResolver.resolve()'s own comment. getMetricUpdates()
	// itself is correctly empty here: there is no OTHER same-tick metric
	// in this batch.
	@Test
	public void metricOnlyBatchIsNoEvidenceNotAmbiguous()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2444, "Zulrah's scales", 1000));
		List<SessionSignal> batch = Arrays.asList(SessionSignal.serverNpcLoot(T0.plusSeconds(5), "Zulrah", drops).withGameTick(1));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.NO_EVIDENCE, r.getOutcome());
		assertEquals(0, r.getMetricUpdates().size());
		assertEquals(1, r.getPreBatchCurrentMetricUpdates().size());
	}

	// SLAYER-XP-WHILE-ACTIVE OWNERSHIP (same class as the loot case
	// above): a SLAYER-skill
	// XP_CHANGE classified METRIC_ONLY against an ACTIVE combat-branch
	// `current` must be split into preBatchCurrentMetricUpdates, not the
	// ordinary metrics list that rides with whatever identity this batch
	// resolves to -- see resolve()'s own in-method comment.
	@Test
	public void slayerXpMetricOnlyAgainstActiveCurrent_isSplitIntoPreBatchCurrentMetrics()
	{
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), T0);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0.plusSeconds(5), "SLAYER", 113).withGameTick(1),
			SessionSignal.bossKill(T0.plusSeconds(5), "Vorkath", 1).withGameTick(1)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());

		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.bossIdentity("Vorkath"), r.getLifecycleClassification().getIdentity());
		assertEquals("only the boss-kill reliable count should ride with the winning identity's own metrics",
			1, r.getMetricUpdates().size());
		assertEquals("the Slayer XP earned during the still-ACTIVE Gargoyles session must be split out "
			+ "for the pre-batch current session, not swept onto the newly-resolved Vorkath identity",
			1, r.getPreBatchCurrentMetricUpdates().size());
		assertEquals(Long.valueOf(113L), r.getPreBatchCurrentMetricUpdates().get(0).getXpDelta());
	}

	// The SUSPENDED counterpart must NOT be split: SessionLifecycleEngine's
	// own weak-evidence-candidate buffering is what correctly owns a
	// SUSPENDED current's metrics (the same mechanism already proven
	// correct for Magic XP's SUSPENDED mixed-batch scenario) -- diverting
	// it here would bypass that buffer and commit it to the SUSPENDED
	// session before it is known whether the candidate is confirmed or
	// abandoned.
	@Test
	public void slayerXpMetricOnlyAgainstSuspendedCurrent_isNotSplit_ridesInOrdinaryMetrics()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		engine.onQualifyingActivity(gargoyles, T0);
		Instant afterSuspend = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1);
		engine.advanceTime(afterSuspend);
		Session suspended = engine.getCurrentSession();
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		Instant t1 = afterSuspend.plusSeconds(60);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(t1, "SLAYER", 113).withGameTick(1),
			SessionSignal.xpChange(t1, "WOODCUTTING", 200).withGameTick(1)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, suspended, classifier, new ClassifierContext());

		assertEquals(0, r.getPreBatchCurrentMetricUpdates().size());
		assertEquals("a SUSPENDED current's Slayer XP metric rides in the ordinary metrics list so it "
			+ "reaches SessionLifecycleEngine's own candidate-buffering machinery unchanged",
			2, r.getMetricUpdates().size());
	}

	// ===== Regression: prior corrections must still hold =====

	// Test 16: combat XP + Slayer progress in one tick still resolves to SLAYER regardless of order.
	@Test
	public void combatXpAndSlayerProgressStillResolvesToSlayer()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Hitpoints", 15).withGameTick(100),
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 129).withGameTick(100)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			ClassifierContext freshContext = new ClassifierContext();
			freshContext.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, freshContext);
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), r.getLifecycleClassification().getIdentity());
		}
	}

	// Test 17: combat XP + BOSS_KILL in one tick still resolves to BOSSING regardless of order.
	@Test
	public void combatXpAndBossKillStillResolvesToBossing()
	{
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Magic", 30).withGameTick(200),
			SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(200)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), r.getLifecycleClassification().getIdentity());
		}
	}

	// Test 18: an existing SLAYER session still switches to BOSSING for an unrelated authoritative boss kill --
	// stickiness must never swallow this real switch, even when other same-tick evidence still names the old Slayer task.
	@Test
	public void slayerSessionStillSwitchesToUnrelatedBossKillViaBatch()
	{
		ActivityIdentity slayer = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session current = sessionOf(slayer, T0);
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0.plusSeconds(5), "Hitpoints", 4).withGameTick(9),
			SessionSignal.bossKill(T0.plusSeconds(5), "Zulrah", 1).withGameTick(9)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			ClassifierContext freshContext = new ClassifierContext();
			freshContext.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, current, classifier, freshContext);
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), r.getLifecycleClassification().getIdentity());
		}
	}

	// Test 19: a more-specific current BOSSING identity remains protected from a same-tick generic combat XP signal.
	@Test
	public void moreSpecificCurrentBossingProtectedFromGenericCombatXp()
	{
		ActivityIdentity boss = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session current = sessionOf(boss, T0);
		List<SessionSignal> batch = Arrays.asList(SessionSignal.xpChange(T0.plusSeconds(5), "Strength", 20).withGameTick(9));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(boss, r.getLifecycleClassification().getIdentity());
	}

	// Test 20: signals with the same gameTick but slightly different observedAt Instants still batch together.
	@Test
	public void sameGameTickDifferentInstantsStillBatchTogether()
	{
		List<SessionSignal> signals = Arrays.asList(
			SessionSignal.xpChange(T0, "Attack", 10).withGameTick(42),
			SessionSignal.xpChange(T0.plusMillis(120), "Hitpoints", 4).withGameTick(42)
		);
		List<List<SessionSignal>> batches = SessionSignalBatchResolver.groupIntoBatches(signals);
		assertEquals(1, batches.size());
		assertEquals(2, batches.get(0).size());
	}

	// Test 21: signals with different gameTicks never batch together.
	@Test
	public void differentGameTicksDoNotBatch()
	{
		List<SessionSignal> signals = Arrays.asList(
			SessionSignal.xpChange(T0, "Attack", 10).withGameTick(1),
			SessionSignal.xpChange(T0, "Hitpoints", 4).withGameTick(2)
		);
		List<List<SessionSignal>> batches = SessionSignalBatchResolver.groupIntoBatches(signals);
		assertEquals(2, batches.size());
	}

	// Test 22: a signal carrying no gameTick falls back to exact observedAt equality as its batch key.
	@Test
	public void signalsWithNoGameTickFallBackToExactInstantEquality()
	{
		List<SessionSignal> signals = Arrays.asList(
			SessionSignal.xpChange(T0, "Attack", 10),
			SessionSignal.xpChange(T0, "Hitpoints", 4),
			SessionSignal.xpChange(T0.plusMillis(1), "Strength", 2)
		);
		List<List<SessionSignal>> batches = SessionSignalBatchResolver.groupIntoBatches(signals);
		assertEquals(2, batches.size());
		assertTrue(batches.get(0).size() == 2 || batches.get(1).size() == 2);
	}

	// Test 23: lifecycle advances exactly once for a resolved batch, regardless of how many signals it contains.
	@Test
	public void lifecycleAdvancesOnceForResolvedBatch()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ActivitySignalClassifier.genericCombatIdentity(), T0);

		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0.plusSeconds(30), "Attack", 40).withGameTick(5),
			SessionSignal.xpChange(T0.plusSeconds(30), "Hitpoints", 15).withGameTick(5)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, engine.getCurrentSession(), classifier, new ClassifierContext());
		engine.onQualifyingActivity(r.getLifecycleClassification().getIdentity(), r.getLifecycleTimestamp());

		assertEquals(30_000L, engine.getCurrentSession().getAccumulatedActiveDurationMillis());
	}

	// Test 24: the exact resume-window boundary event-first contract still holds through a resolved batch.
	@Test
	public void resolvedBatchAtExactResumeBoundaryStillResumesViaRefine()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		Instant expiry = engine.getCurrentSession().resumeWindowExpiresAtInstant();

		List<SessionSignal> batch = Arrays.asList(SessionSignal.bossKill(expiry, "Zulrah", 1).withGameTick(1));
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, engine.getCurrentSession(), classifier, new ClassifierContext());

		engine.onQualifyingActivity(r.getLifecycleClassification().getIdentity(), r.getLifecycleTimestamp());
		assertEquals(SessionState.ACTIVE, engine.getCurrentSession().getState());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), engine.getCurrentSession().getActivityIdentity());
	}

	// Test 25: full-permutation check across three same-tick signals (Hitpoints XP, Attack XP, Slayer progress) --
	// every ordering resolves to the identical identity and metric count.
	@Test
	public void threeSignalBatchIsOrderIndependentAcrossAllPermutations()
	{
		List<SessionSignal> all = Arrays.asList(
			SessionSignal.xpChange(T0, "Hitpoints", 15).withGameTick(300),
			SessionSignal.xpChange(T0.plusMillis(50), "Attack", 40).withGameTick(300),
			SessionSignal.slayerTaskProgress(T0.plusMillis(20), "Gargoyles", "Catacombs of Kourend", 129).withGameTick(300)
		);
		ActivityIdentity expected = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		for (List<SessionSignal> batch : permutations(all))
		{
			ClassifierContext context = new ClassifierContext();
			context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, null, classifier, context);
			assertEquals(expected, r.getLifecycleClassification().getIdentity());
			assertEquals(3, r.getMetricUpdates().size());
		}
	}

	// Test 26: two independent Session/ClassifierContext pairs (simulating two accounts) never contaminate each other's batch resolution.
	// Test 26 (CORRECTED -- live bug fix, false SLAYER promotion from
	// assignment context): two independent Session/ClassifierContext
	// pairs (simulating two accounts) never contaminate each other's
	// batch resolution. A merely-assigned Slayer task on accountA is
	// CONTEXT ONLY and must not promote accountA's ordinary combat XP
	// into a SLAYER identity (see ActivitySignalClassifierTest's
	// slayerTaskAssignedAloneDoesNotPromoteLaterCombatXpToSlayerIdentity).
	// Isolation is instead proven via recent-NPC-target state: accountA
	// has a fresh recent target of "Goat" and accountB has none, so
	// accountA's combat XP must resolve to named COMBAT/Goat while
	// accountB's resolves to plain unnamed generic COMBAT -- proving the
	// two ClassifierContext instances do not leak recent-target state
	// into each other.
	@Test
	public void separateSessionContextPairsDoNotContaminateEachOther()
	{
		ClassifierContext accountA = new ClassifierContext();
		accountA.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 5, "Goat").withGameTick(1L), null, accountA);
		ClassifierContext accountB = new ClassifierContext();

		List<SessionSignal> batch = Arrays.asList(SessionSignal.xpChange(T0.plusMillis(600), "Attack", 10).withGameTick(2));
		SessionSignalBatchResolver.BatchResolution forA = SessionSignalBatchResolver.resolve(batch, null, classifier, accountA);
		SessionSignalBatchResolver.BatchResolution forB = SessionSignalBatchResolver.resolve(batch, null, classifier, accountB);

		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), forA.getLifecycleClassification().getIdentity());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), forB.getLifecycleClassification().getIdentity());
		assertNotEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), forA.getLifecycleClassification().getIdentity());
	}

	// Test 27: grouping an empty signal list is a safe no-op.
	@Test
	public void groupingEmptySignalListYieldsNoBatches()
	{
		assertEquals(0, SessionSignalBatchResolver.groupIntoBatches(new ArrayList<>()).size());
	}

	// Test 28 (final correction pass, failure 2 regression): a same-tick
	// BOSS_KILL + combat XP + NPC_DEATH + SERVER_NPC_LOOT batch with NO
	// pre-batch session (current == null) must still RESOLVE to BOSSING
	// AND retain the loot metric -- the loot's own per-signal
	// classification correctly says IGNORE_FOR_SESSION in isolation
	// (there is no ACTIVE session yet when it alone is classified), but
	// the batch as a whole creates one via BOSS_KILL this same tick, so
	// the loot genuinely belongs to it. Regardless of permutation order.
	@Test
	public void bossKillXpNpcDeathAndLootSameTickWithNoPriorSession_resolvesToBossingWithLootRetained()
	{
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2, "Zulrah's scales", 100));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Strength", 50).withGameTick(1),
			SessionSignal.bossKill(T0, "Zulrah", 1).withGameTick(1),
			SessionSignal.npcDeath(T0).withGameTick(1),
			SessionSignal.serverNpcLoot(T0, "Zulrah", drops).withGameTick(1)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, null, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), r.getLifecycleClassification().getIdentity());

			boolean sawLootMetric = false;
			for (int i = 0; i < r.getMetricUpdates().size(); i++)
			{
				if (r.getMetricUpdates().get(i).getLootDrops() != null)
				{
					sawLootMetric = true;
				}
			}
			assertTrue("the same-tick loot must not be dropped merely because no session existed before this batch",
				sawLootMetric);
		}
	}

	// Test 29 (final correction pass, failure 2 regression -- negative
	// counterpart): the SAME loot signal, with NO accompanying
	// lifecycle-creating evidence in its batch (AMBIGUOUS outcome, per
	// the already-established ambiguousBatchRetainsAllMetrics test),
	// must still NOT retain the loot metric -- the atomicity fix must
	// never apply outside a genuinely RESOLVED batch.
	@Test
	public void lootWithNoResolvedLifecycleEvidenceInBatch_staysDropped()
	{
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2, "Zulrah's scales", 100));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0, "Fishing", 20).withGameTick(1),
			SessionSignal.xpChange(T0, "Agility", 10).withGameTick(1),
			SessionSignal.serverNpcLoot(T0, "Zulrah", drops).withGameTick(1)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, null, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.AMBIGUOUS, r.getOutcome());
		assertEquals("an ambiguous batch must never retroactively attach loot either -- only 2 XP metrics survive",
			2, r.getMetricUpdates().size());
	}

	// ===== Gargoyle loot ownership at the GG ->
	// Gargoyles boundary =====
	//
	// Test 30: same-tick SLAYER_TASK_PROGRESS(Gargoyles) + a Gargoyle
	// SERVER_NPC_LOOT drop, while `current` is ACTIVE BOSSING/Grotesque
	// Guardians. Before this fix's slayerTaskProgressDrivesRealSwitch
	// carve-out, isPreBatchCurrentOwnedLoot's existing mechanism (built
	// for the Zulrah/Prayer and Gargoyles/Vorkath same-tick-switch tests
	// above) would have swept this loot into getPreBatchCurrentMetricUpdates()
	// -- i.e. misattributed the very first Gargoyle drop to the
	// FINALIZING GG session -- exactly mirroring the live report's Dusk
	// loot boundary. The fix must resolve the loot onto the NEW winning
	// Gargoyles identity's own metrics (getMetricUpdates()), not the
	// pre-batch current's.
	@Test
	public void gargoyleLootSameTickAsAuthoritativeTaskProgressSwitch_attachesToNewGargoylesIdentity_notOldGG()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(1, "Big bones", 1));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(30), "Gargoyles", "Catacombs of Kourend", 1, 98).withGameTick(50),
			SessionSignal.serverNpcLoot(T0.plusSeconds(30), "Gargoyle", drops).withGameTick(50)
		);
		for (List<SessionSignal> perm : permutations(batch))
		{
			SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(perm, current, classifier, new ClassifierContext());
			assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
			assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"),
				r.getLifecycleClassification().getIdentity());

			assertEquals("the Gargoyle loot must ride with the newly-resolved Gargoyles identity's own metrics, "
				+ "not be swept back onto the finalizing GG session via preBatchCurrentMetricUpdates",
				0, r.getPreBatchCurrentMetricUpdates().size());

			boolean sawLootMetric = false;
			for (int i = 0; i < r.getMetricUpdates().size(); i++)
			{
				if (r.getMetricUpdates().get(i).getLootDrops() != null)
				{
					sawLootMetric = true;
				}
			}
			assertTrue("the Gargoyle loot must survive on the new session's own metrics", sawLootMetric);
		}
	}

	// Test 31: negative-space regression guard -- this fix's carve-out
	// must NOT disturb the two pre-existing preBatchCurrentMetricUpdates
	// mechanisms it shares code with: an ordinary loot-while-ACTIVE same-
	// tick switch to an UNRELATED activity (no SLAYER_TASK_PROGRESS
	// driving the switch at all) must still route through
	// preBatchCurrentMetricUpdates exactly as metricOnlyBatchIsNoEvidenceNotAmbiguous
	// and slayerXpMetricOnlyAgainstActiveCurrent_isSplitIntoPreBatchCurrentMetrics
	// already establish above.
	@Test
	public void slayerTaskProgressCarveOut_doesNotAffectUnrelatedSameTickLootSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2444, "Zulrah's scales", 1000));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(T0.plusSeconds(5), "Prayer", 50).withGameTick(9),
			SessionSignal.serverNpcLoot(T0.plusSeconds(5), "Zulrah", drops).withGameTick(9)
		);
		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Prayer"), r.getLifecycleClassification().getIdentity());
		assertEquals("no SLAYER_TASK_PROGRESS drove this switch, so the pre-existing preBatchCurrentMetricUpdates "
			+ "mechanism must still own the Zulrah loot exactly as before this fix",
			1, r.getPreBatchCurrentMetricUpdates().size());
	}

	// =====================================================================
	// Boss self-consumption of its own Slayer task -- the
	// INTRA-BATCH, order-independent half at the resolver's own scope.
	// See BossTaskAffinity / ActivitySignalClassifier.classifySlayerTaskProgress()
	// / applyBossSelfConsumptionCorroboration() javadoc for the full
	// rationale.
	// =====================================================================

	// Test 32: no corroboration anywhere in the batch -- a boss's own
	// known-task progress alone must stay a heartbeat of the boss, never
	// fabricate a bogus Slayer session.
	@Test
	public void bossSelfConsumption_progressAlone_noCorroborationInBatch_staysBossNoSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Gargoyles", "Catacombs of Kourend", 1, 98).withGameTick(2)
		);

		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());

		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals("no bogus Gargoyles session -- the batch resolves to a heartbeat of the SAME boss identity",
			ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), r.getLifecycleClassification().getIdentity());
	}

	// Test 33: same-tick corroborating loot for a genuinely different NPC
	// (not the boss, not one of its aliases) -- progress classified
	// BEFORE the loot in the batch's own signal order.
	@Test
	public void bossSelfConsumption_sameTickCorroboratingLoot_progressBeforeLoot_confirmsRealSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(1, "Big bones", 1));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Gargoyles", "Catacombs of Kourend", 1, 98).withGameTick(2),
			SessionSignal.serverNpcLoot(T0.plusSeconds(5), "Gargoyle", drops).withGameTick(2)
		);

		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());

		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals("a genuinely different NPC's same-tick loot reverses the default suppression",
			gargoyles, r.getLifecycleClassification().getIdentity());
		assertEquals("the Gargoyle loot must ride with the new Slayer session, never the outgoing boss session",
			0, r.getPreBatchCurrentMetricUpdates().size());
	}

	// Test 34: the SAME scenario as Test 33, but with the loot classified
	// BEFORE the progress in the batch's own signal order -- proves the
	// corroboration check is genuinely order-independent (it does not
	// rely on the progress having already armed a candidate before the
	// loot is classified).
	@Test
	public void bossSelfConsumption_sameTickCorroboratingLoot_lootBeforeProgress_orderIndependent()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(1, "Big bones", 1));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.serverNpcLoot(T0.plusSeconds(5), "Gargoyle", drops).withGameTick(2),
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Gargoyles", "Catacombs of Kourend", 1, 98).withGameTick(2)
		);

		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());

		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals("order within the batch must not matter -- the same real switch must be resolved",
			gargoyles, r.getLifecycleClassification().getIdentity());
	}

	// Test 35: same-tick loot whose sourceName IS the boss itself (Dusk,
	// a known Grotesque Guardians alias) -- this is NOT corroboration; it
	// must stay suppressed (self-consumption remains the honest default).
	@Test
	public void bossSelfConsumption_sameTickBossOwnAliasLoot_doesNotCorroborate_staysBoss()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(1, "Granite dust", 90));
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Gargoyles", "Catacombs of Kourend", 1, 90).withGameTick(2),
			SessionSignal.serverNpcLoot(T0.plusSeconds(5), "Dusk", drops).withGameTick(2)
		);

		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());

		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals("the boss's own NPC loot (Dusk) is not proof of a different NPC -- stays suppressed",
			ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), r.getLifecycleClassification().getIdentity());
	}

	// =====================================================================
	// POST-FINALIZATION METRIC handling (surfaced by the
	// same loot-ownership audit): a metric observed strictly AFTER the
	// instant a real switch will finalize `current` at can never honestly
	// belong to that about-to-be-finalized session. Isolated here from
	// the sameKillEvidenceDrivesRealSwitch fix above by using evidence
	// (Prayer XP) that does NOT itself drive the switch via that
	// mechanism, so only the observedAt-ordering guard is exercised.
	// =====================================================================

	// Test 36: same tick, but the loot's own observedAt is LATER than the
	// switch's own timestamp -- the loot must ride with the NEW session,
	// never the old one about to be finalized at the earlier instant.
	@Test
	public void postFinalizationMetricFix_lateObservedAtLootInSameTick_ridesWithNewIdentity_notOldFinalizingSession()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2444, "Zulrah's scales", 100));
		Instant switchAt = T0.plusSeconds(10);
		Instant lootObservedAt = switchAt.plusSeconds(5);
		List<SessionSignal> batch = Arrays.asList(
			SessionSignal.xpChange(switchAt, "Prayer", 50).withGameTick(9),
			SessionSignal.serverNpcLoot(lootObservedAt, "Zulrah", drops).withGameTick(9)
		);

		SessionSignalBatchResolver.BatchResolution r = SessionSignalBatchResolver.resolve(batch, current, classifier, new ClassifierContext());

		assertEquals(BatchLifecycleOutcome.RESOLVED, r.getOutcome());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Prayer"), r.getLifecycleClassification().getIdentity());
		assertEquals("a metric timestamped AFTER the finalizing instant must never be routed to the "
			+ "about-to-be-finalized old session -- it would leave a persisted finalized session "
			+ "containing telemetry observed after its own finalizedAt",
			0, r.getPreBatchCurrentMetricUpdates().size());

		boolean sawLootMetric = false;
		for (int i = 0; i < r.getMetricUpdates().size(); i++)
		{
			if (r.getMetricUpdates().get(i).getLootDrops() != null)
			{
				sawLootMetric = true;
			}
		}
		assertTrue("the late-observed loot must still be credited, just to the new session's own metrics",
			sawLootMetric);
	}
}