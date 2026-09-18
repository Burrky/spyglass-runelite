package com.osrstelemetry.plugin.session;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Pure, deterministic tests for ActivitySignalClassifier,
 * SessionSignal, and ClassifierContext, covering the SLAYER->BOSSING
 * switch correction, CompletionNature, and the ReliableCount
 * authoritative-value/session-occurrences split -- no RuneLite, no scheduler,
 * no JDK toolchain dependency beyond plain JUnit. Batch/permutation
 * tests live in SessionSignalBatchResolverTest.
 */
public class ActivitySignalClassifierTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private final ActivitySignalClassifier classifier = new ActivitySignalClassifier();

	private Session sessionOf(ActivityIdentity identity, Instant lastActiveAt)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(identity, lastActiveAt);
		return engine.getCurrentSession();
	}

	/**
	 * Test helper (Magic XP / mixed-batch false resume). Builds a
	 * SUSPENDED session for `identity` -- unlike sessionOf() above (which
	 * only ever produces ACTIVE, since SessionLifecycleEngine.
	 * onQualifyingActivity() always starts ACTIVE), this advances time past
	 * SUSPEND_TIMEOUT so the classifier is exercised against the exact
	 * live-reported state: a specific activity SUSPENDED, well inside its
	 * resume window.
	 */
	private Session suspendedSessionOf(ActivityIdentity identity, Instant startedAt)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(identity, startedAt);
		engine.advanceTime(startedAt.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		return engine.getCurrentSession();
	}

	// Test 1: combat XP with no reliable Slayer context classifies as generic COMBAT.
	@Test
	public void combatXpWithNoSlayerContextIsGenericCombat()
	{
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0, "Strength", 100), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), result.getIdentity());
	}

	// Test 2 (CORRECTED -- live bug fix, false SLAYER promotion from
	// assignment context): a merely-ASSIGNED Slayer task is CONTEXT
	// ONLY -- ordinary combat XP with NO recent NPC target and no
	// genuine SLAYER_TASK_PROGRESS must fall back to plain generic
	// COMBAT, exactly as if no task were tracked at all. This used to
	// assert the OPPOSITE (slayerIdentity() from assignment context
	// alone) -- that was the live-reported bug itself (see
	// classifyXpChange()'s own javadoc): a player with Gargoyles
	// assigned who fights something else entirely must never have that
	// combat silently read as Gargoyles activity.
	@Test
	public void combatXpWithOnlyAssignedSlayerContext_isPlainGenericCombat_notSlayerIdentity()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0, "Attack", 50), null, context);
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), result.getIdentity());
	}

	// Test 2b (live bug fix): the HARD SLAYER RULE's own worked example
	// -- assigned task = Gargoyles, recent target = Goat, ordinary combat
	// XP -- must classify as COMBAT/Goat, never SLAYER/Gargoyles.
	@Test
	public void combatXpWithAssignedSlayerContextAndRecentDifferentTarget_isNamedGenericCombat_notSlayerIdentity()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		classifier.classify(SessionSignal.npcInteractionTarget(T0, 5, "Goat").withGameTick(1L), null, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusMillis(600), "Attack", 50).withGameTick(2L), null, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), result.getIdentity());
		assertNotEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
	}

	// Test 3: repeated combat XP for the same Slayer task is a same-identity heartbeat, not a new session decision.
	@Test
	public void repeatedCombatXpSameSlayerTaskIsHeartbeat()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), T0);
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(10), "Strength", 40), current, context);
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(current.getActivityIdentity(), result.getIdentity());
	}

	// Test 4: Slayer identity uses taskName+taskLocation only -- amountRemaining never affects it.
	@Test
	public void slayerIdentityIgnoresAmountRemaining()
	{
		ActivityIdentity a = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		ActivityIdentity b = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		assertEquals(a, b);
	}

	// Test 5: Slayer identity differs when taskLocation differs for the same taskName.
	@Test
	public void slayerIdentityDiffersByLocation()
	{
		ActivityIdentity a = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		ActivityIdentity b = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Slayer Tower");
		assertNotEquals(a, b);
	}

	// Test 6: SLAYER_TASK_ASSIGNED never starts a session by itself.
	@Test
	public void slayerTaskAssignedNeverStartsSession()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskAssigned(T0, "Gargoyles", "Catacombs of Kourend", 130), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// Test 7 (CORRECTED -- live bug fix, false SLAYER promotion from
	// assignment context): SLAYER_TASK_ASSIGNED merely updates the
	// account's tracked "currently assigned task" CONTEXT. It must NOT,
	// by itself, cause a later ordinary combat XP signal (with no fresh
	// recent NPC target) to classify as that task's SLAYER identity.
	// Genuine SLAYER identity requires actual SLAYER_TASK_PROGRESS
	// evidence (see slayerTaskProgressWithNoCurrentSessionStarts below),
	// not merely having a task assigned.
	@Test
	public void slayerTaskAssignedAloneDoesNotPromoteLaterCombatXpToSlayerIdentity()
	{
		ClassifierContext context = new ClassifierContext();
		classifier.classify(SessionSignal.slayerTaskAssigned(T0, "Gargoyles", "Catacombs of Kourend", 130), null, context);
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(5), "Attack", 30), null, context);
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), result.getIdentity());
		assertNotEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
	}

	// Test 8: SLAYER_TASK_PROGRESS with no current session creates a SLAYER identity.
	@Test
	public void slayerTaskProgressWithNoCurrentSessionStarts()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 129), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
	}

	// Test 9: SLAYER_TASK_PROGRESS's metric carries amountRemaining, never a kill count.
	@Test
	public void slayerTaskProgressMetricIsProgressNotKillCount()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 129), null, new ClassifierContext());
		SessionAggregates aggregates = new SessionAggregates();
		SessionAggregateUpdater.apply(aggregates, result.getMetricUpdate());
		assertEquals(Integer.valueOf(129), aggregates.getSlayerProgressDelta());
		assertNull(aggregates.getReliableCount());
	}

	// Test 10: SLAYER_TASK_COMPLETED is completion-only, session-annotation-only (never a standalone entry), never carries an identity, and clears context.
	@Test
	public void slayerTaskCompletedIsSessionAnnotationOnlyAndClearsContext()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		SignalClassification completion = classifier.classify(
			SessionSignal.slayerTaskCompleted(T0, "Gargoyles", "Catacombs of Kourend"), null, context);
		assertEquals(SignalDecisionKind.COMPLETION_ONLY, completion.getDecisionKind());
		assertEquals(CompletionNature.SESSION_ANNOTATION_ONLY, completion.getCompletionNature());
		assertNull(completion.getIdentity());
		assertFalse(context.hasReliableActiveSlayerTask());

		SignalClassification afterward = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(1), "Strength", 10), null, context);
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), afterward.getIdentity());
	}

	// Test 11: BOSS_KILL with no current session starts a BOSSING identity.
	@Test
	public void bossKillWithNoCurrentSessionStarts()
	{
		SignalClassification result = classifier.classify(SessionSignal.bossKill(T0, "Zulrah", 5), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), result.getIdentity());
	}

	// Test 12: the same boss's identity is stable across consecutive kill-count increments.
	@Test
	public void bossIdentityStableAcrossKcIncrements()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		SignalClassification first = classifier.classify(SessionSignal.bossKill(T0.plusSeconds(60), "Zulrah", 5), current, new ClassifierContext());
		assertEquals(current.getActivityIdentity(), first.getIdentity());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, first.getDecisionKind());
	}

	// Test 13: a different boss than the current BOSSING session is a real switch, not a refinement.
	@Test
	public void differentBossIsRealSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		SignalClassification result = classifier.classify(SessionSignal.bossKill(T0.plusSeconds(60), "Vorkath", 1), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Vorkath"), result.getIdentity());
		assertNotEquals(current.getActivityIdentity(), result.getIdentity());
	}

	// Test 14: generic COMBAT -> BOSS_KILL refines the session (does not switch).
	@Test
	public void genericCombatRefinesToBossing()
	{
		Session current = sessionOf(ActivitySignalClassifier.genericCombatIdentity(), T0);
		SignalClassification result = classifier.classify(SessionSignal.bossKill(T0.plusSeconds(60), "Zulrah", 1), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.REFINE, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), result.getIdentity());
	}

	// Test 15: generic COMBAT -> Slayer task progress refines the session.
	@Test
	public void genericCombatRefinesToSlayer()
	{
		Session current = sessionOf(ActivitySignalClassifier.genericCombatIdentity(), T0);
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(60), "Gargoyles", "Catacombs of Kourend", 129), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.REFINE, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
	}

	// Test 16: SLAYER -> an unrelated authoritative BOSS_KILL is a REAL SWITCH, not a
	// switch-free heartbeat of the old Slayer identity -- an active Slayer task must never keep an
	// incompatible boss session silently folded into it.
	@Test
	public void slayerToUnrelatedBossKillIsRealSwitch()
	{
		ActivityIdentity slayer = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session current = sessionOf(slayer, T0);
		SignalClassification result = classifier.classify(SessionSignal.bossKill(T0.plusSeconds(60), "Zulrah", 1), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), result.getIdentity());
		assertNotEquals(slayer, result.getIdentity());
	}

	// Test 17: BOSSING never downgrades to generic COMBAT from a later combat XP signal.
	@Test
	public void bossingNeverDowngradesToGenericCombat()
	{
		ActivityIdentity boss = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session current = sessionOf(boss, T0);
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(10), "Strength", 30), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(boss, result.getIdentity());
	}

	// Test 18: SLAYER never downgrades to generic COMBAT from a later combat XP signal once the task context is gone.
	@Test
	public void slayerNeverDowngradesToGenericCombat()
	{
		ActivityIdentity slayer = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session current = sessionOf(slayer, T0);
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(10), "Strength", 30), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(slayer, result.getIdentity());
	}

	// Test 19: two different bosses never produce an equal ActivityIdentity.
	@Test
	public void differentBossesAreNeverEqual()
	{
		assertNotEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), ActivitySignalClassifier.bossIdentity("Vorkath"));
	}

	// Test 20: non-combat XP for the same skill as the current Skilling session is a heartbeat.
	@Test
	public void sameSkillNonCombatXpIsHeartbeat()
	{
		ActivityIdentity woodcutting = ActivitySignalClassifier.skillingIdentity("Woodcutting");
		Session current = sessionOf(woodcutting, T0);
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(10), "Woodcutting", 25), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(woodcutting, result.getIdentity());
	}

	// Test 21: non-combat XP for a different skill, taken alone (different tick), is a real switch (the Woodcutting -> Mining example).
	@Test
	public void differentSkillNonCombatXpIsRealSwitch()
	{
		Session current = sessionOf(ActivitySignalClassifier.skillingIdentity("Woodcutting"), T0);
		SignalClassification result = classifier.classify(SessionSignal.xpChange(T0.plusSeconds(10), "Mining", 25), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.skillingIdentity("Mining"), result.getIdentity());
		assertNotEquals(current.getActivityIdentity(), result.getIdentity());
	}

	// Test 22: NPC_DEATH and NPC_LOOT_ATTRIBUTED are always ignored for session purposes.
	@Test
	public void npcDeathAndLootAttributedAreIgnored()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, classifier.classify(SessionSignal.npcDeath(T0.plusSeconds(1)), current, new ClassifierContext()).getDecisionKind());
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, classifier.classify(SessionSignal.npcLootAttributed(T0.plusSeconds(1)), current, new ClassifierContext()).getDecisionKind());
	}

	// Test 23: SERVER_NPC_LOOT with no current ACTIVE session never starts one.
	@Test
	public void serverNpcLootWithNoActiveSessionIsIgnored()
	{
		List<SessionSignal.LootDrop> drops = Arrays.asList(new SessionSignal.LootDrop(2444, "Zulrah's scales", 1000));
		SignalClassification result = classifier.classify(SessionSignal.serverNpcLoot(T0, "Zulrah", drops), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, result.getDecisionKind());
	}

	// Test 24: SERVER_NPC_LOOT with a current ACTIVE session is metric-only and preserves the atomic drop grouping and item ids.
	@Test
	public void serverNpcLootIsMetricOnlyAndPreservesDropGrouping()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		List<SessionSignal.LootDrop> drops = Arrays.asList(
			new SessionSignal.LootDrop(2444, "Zulrah's scales", 1000),
			new SessionSignal.LootDrop(12922, "Magic fang", 1));
		SignalClassification result = classifier.classify(SessionSignal.serverNpcLoot(T0.plusSeconds(5), "Zulrah", drops), current, new ClassifierContext());
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());

		SessionAggregateUpdater.apply(current.getAggregates(), result.getMetricUpdate());
		assertEquals(1, current.getAggregates().getLootDrops().size());
		SessionAggregates.LootDropGroup group = current.getAggregates().getLootDrops().get(0);
		assertEquals("Zulrah", group.getSourceName());
		assertEquals(2, group.getItems().size());
		assertEquals(Integer.valueOf(2444), group.getItems().get(0).getItemId());
		assertEquals(1000L, group.getItems().get(0).getQuantity());
	}

	// Test 25: RAID_COMPLETION / ACTIVITY_COMPLETION / QUEST_COMPLETED are all completion-only, standalone-activity-natured, and never carry an identity.
	@Test
	public void completionSignalsAreStandaloneCompletionOnly()
	{
		SignalClassification raid = classifier.classify(SessionSignal.raidCompletion(T0, "Theatre of Blood"), null, new ClassifierContext());
		SignalClassification activity = classifier.classify(SessionSignal.activityCompletion(T0, "Barbarian Assault"), null, new ClassifierContext());
		SignalClassification quest = classifier.classify(SessionSignal.questCompleted(T0, "Dragon Slayer II"), null, new ClassifierContext());
		for (SignalClassification c : Arrays.asList(raid, activity, quest))
		{
			assertEquals(SignalDecisionKind.COMPLETION_ONLY, c.getDecisionKind());
			assertEquals(CompletionNature.STANDALONE_ACTIVITY, c.getCompletionNature());
			assertNull(c.getIdentity());
		}
	}

	// Test 26: storage snapshots, collection log items, and combat achievements are never session activity.
	@Test
	public void storageAndCollectionAndAchievementSignalsAreIgnored()
	{
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, classifier.classify(SessionSignal.storageSnapshot(T0), null, new ClassifierContext()).getDecisionKind());
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, classifier.classify(SessionSignal.collectionLogNewItem(T0, "Magic fang"), null, new ClassifierContext()).getDecisionKind());
		assertEquals(SignalDecisionKind.IGNORE_FOR_SESSION, classifier.classify(SessionSignal.combatAchievementCompleted(T0, "Zulrah novice"), null, new ClassifierContext()).getDecisionKind());
	}

	// Test 27: SessionAggregateUpdater sums repeated XP metrics for the same skill instead of overwriting.
	@Test
	public void aggregateUpdaterSumsRepeatedXpForSameSkill()
	{
		SessionAggregates aggregates = new SessionAggregates();
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.xp("Attack", 40));
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.xp("Attack", 15));
		assertEquals(Long.valueOf(55L), aggregates.getXpGainedBySkill().get("Attack"));
	}

	// Test 28: two independent ClassifierContext instances never share Slayer-task state (account isolation).
	@Test
	public void classifierContextsAreIndependent()
	{
		ClassifierContext accountA = new ClassifierContext();
		ClassifierContext accountB = new ClassifierContext();
		accountA.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		assertTrue(accountA.hasReliableActiveSlayerTask());
		assertFalse(accountB.hasReliableActiveSlayerTask());
	}

	// Test 29: ClassifierContext.reset() clears active Slayer task state (for a future account switch).
	@Test
	public void classifierContextResetClearsState()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		context.reset();
		assertFalse(context.hasReliableActiveSlayerTask());
	}

	// Test 30: the FIRST authoritative BOSS_KILL for a boss within a session honestly counts as
	// exactly one session occurrence, while the authoritative (account-wide) value is preserved unchanged.
	@Test
	public void firstBossKillInSessionCountsAsOneSessionOccurrence()
	{
		SessionAggregates aggregates = new SessionAggregates();
		SignalClassification first = classifier.classify(SessionSignal.bossKill(T0, "Zulrah", 812), null, new ClassifierContext());
		SessionAggregateUpdater.apply(aggregates, first.getMetricUpdate());
		SessionAggregates.ReliableCount rc = aggregates.getReliableCount();
		assertEquals(SessionAggregates.ReliableCountKind.KILLS, rc.getKind());
		assertEquals(Integer.valueOf(812), rc.getAuthoritativeCurrentValue());
		assertEquals(1, rc.getSessionOccurrences());
	}

	// Test 31: repeated boss kills increment the session count by the REAL observed delta, and the
	// authoritative account-wide KC is never conflated with or displayed as the session's own kill total.
	@Test
	public void repeatedBossKillsIncrementSessionCountByRealDeltaNotAccountKc()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);
		SessionAggregates aggregates = current.getAggregates();
		SignalClassification k1 = classifier.classify(SessionSignal.bossKill(T0, "Zulrah", 812), null, new ClassifierContext());
		SessionAggregateUpdater.apply(aggregates, k1.getMetricUpdate());
		SignalClassification k2 = classifier.classify(SessionSignal.bossKill(T0.plusSeconds(120), "Zulrah", 813), current, new ClassifierContext());
		SessionAggregateUpdater.apply(aggregates, k2.getMetricUpdate());
		SignalClassification k3 = classifier.classify(SessionSignal.bossKill(T0.plusSeconds(240), "Zulrah", 816), current, new ClassifierContext());
		SessionAggregateUpdater.apply(aggregates, k3.getMetricUpdate());

		assertEquals(Integer.valueOf(816), aggregates.getReliableCount().getAuthoritativeCurrentValue());
		// 1 (first) + 1 (812->813) + 3 (813->816, real observed jump preserved) = 5, never "816".
		assertEquals(5, aggregates.getReliableCount().getSessionOccurrences());
	}

	// Test 32: a duplicate/non-advancing authoritative observation never subtracts from what was already credited.
	@Test
	public void nonAdvancingReliableCountObservationNeverDecreasesSessionOccurrences()
	{
		SessionAggregates aggregates = new SessionAggregates();
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.reliableCount(SessionAggregates.ReliableCountKind.KILLS, "zulrah", 812));
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.reliableCount(SessionAggregates.ReliableCountKind.KILLS, "zulrah", 812));
		assertEquals(1, aggregates.getReliableCount().getSessionOccurrences());
		assertEquals(Integer.valueOf(812), aggregates.getReliableCount().getAuthoritativeCurrentValue());
	}

	// Test 33: combat-skill detection is case-insensitive and whitespace-tolerant.
	@Test
	public void combatSkillDetectionIsCaseInsensitive()
	{
		assertTrue(ActivitySignalClassifier.isCombatSkill(" Hitpoints "));
		assertTrue(ActivitySignalClassifier.isCombatSkill("RANGED"));
		assertFalse(ActivitySignalClassifier.isCombatSkill("Woodcutting"));
	}

	// ---------------------------------------------------------------
	// A live session_state.json regression: slayerProgressDelta
	// was observed holding an ABSOLUTE remaining count instead of the
	// accumulated sum of taskUnitsConsumed. Tests 34-38 below are the
	// required-coverage list for the fix.
	// ---------------------------------------------------------------

	private int applySlayerProgressAndGetDelta(SessionAggregates aggregates, int unitsConsumed)
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", unitsConsumed), null, new ClassifierContext());
		SessionAggregateUpdater.apply(aggregates, result.getMetricUpdate());
		return aggregates.getSlayerProgressDelta();
	}

	// Test 34: a single progress event consuming 1 task unit credits the aggregate exactly +1.
	@Test
	public void slayerProgress_oneEventConsumingOne_creditsAggregateByOne()
	{
		SessionAggregates aggregates = new SessionAggregates();
		int delta = applySlayerProgressAndGetDelta(aggregates, 1);
		assertEquals(1, delta);
	}

	// Test 35: five separate progress events each consuming 1 unit accumulate to 5 --
	// this is the live bug's exact reported shape (five 118..123-range 1-unit
	// SLAYER_TASK_PROGRESS events must sum to 5, never collapse to the last
	// event's absolute remaining value).
	@Test
	public void slayerProgress_fiveEventsEachConsumingOne_accumulateToFive()
	{
		SessionAggregates aggregates = new SessionAggregates();
		int delta = 0;
		for (int i = 0; i < 5; i++)
		{
			delta = applySlayerProgressAndGetDelta(aggregates, 1);
		}
		assertEquals(5, delta);
		assertEquals(Integer.valueOf(5), aggregates.getSlayerProgressDelta());
	}

	// Test 36: a legitimate taskUnitsConsumed > 1 (a reconciled missed observation)
	// is added in full, not clamped to 1 -- and further events keep accumulating
	// on top of it.
	@Test
	public void slayerProgress_taskUnitsConsumedGreaterThanOne_addsInFullAndKeepsAccumulating()
	{
		SessionAggregates aggregates = new SessionAggregates();
		applySlayerProgressAndGetDelta(aggregates, 2);
		assertEquals(Integer.valueOf(2), aggregates.getSlayerProgressDelta());

		int delta = applySlayerProgressAndGetDelta(aggregates, 1);
		assertEquals(3, delta);
	}

	// Test 37: Slayer classification with combat XP in the same batch still
	// resolves to SLAYER (regression guard for the requirement that the Bug 2
	// fix must not disturb lifecycle/identity classification -- see also
	// SessionSignalBatchResolverTest.combatXpAndSlayerProgressStillResolvesToSlayer,
	// which is unaffected by the rename since it uses positional constructor args).
	@Test
	public void slayerProgress_withCombatXpSameBatch_stillClassifiesAsSlayerIdentity()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 1), null, context);
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
	}

	// Test 38: no generic kill semantics introduced -- applying several Slayer
	// progress events never populates ReliableCount (the generic
	// KC/kill-count aggregate path), only slayerProgressDelta.
	@Test
	public void slayerProgress_neverPopulatesReliableCount_noGenericKillSemantics()
	{
		SessionAggregates aggregates = new SessionAggregates();
		for (int i = 0; i < 3; i++)
		{
			applySlayerProgressAndGetDelta(aggregates, 1);
		}
		assertEquals(Integer.valueOf(3), aggregates.getSlayerProgressDelta());
		assertNull("Slayer task progress must never be routed through the generic ReliableCount/kill-count path",
			aggregates.getReliableCount());
	}

	// ---------------------------------------------------------------
	// currentRemaining (the latest authoritative
	// "task units remaining" value from EventPayloads.SlayerTaskProgress)
	// is stored with OVERWRITE semantics in a NEW aggregate,
	// latestSlayerCurrentRemaining, entirely separate from
	// slayerProgressDelta's SUM semantics above. Tests H/I/J below are
	// the required-coverage list for this addition.
	// ---------------------------------------------------------------

	private void applySlayerProgress(SessionAggregates aggregates, int unitsConsumed, Integer currentRemaining)
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", unitsConsumed, currentRemaining),
			null, new ClassifierContext());
		SessionAggregateUpdater.apply(aggregates, result.getMetricUpdate());
	}

	// H. currentRemaining is stored verbatim from a single progress event.
	@Test
	public void currentRemaining_singleEvent_isStoredVerbatim()
	{
		SessionAggregates aggregates = new SessionAggregates();
		applySlayerProgress(aggregates, 1, 98);
		assertEquals(Integer.valueOf(98), aggregates.getLatestSlayerCurrentRemaining());
	}

	// I. currentRemaining OVERWRITES on each subsequent event (never sums,
	// never clamps) -- reflecting the latest authoritative value only, in
	// direct contrast to slayerProgressDelta's SUM semantics (tests 34-36
	// above).
	@Test
	public void currentRemaining_multipleEvents_overwritesToLatestValue_neverSums()
	{
		SessionAggregates aggregates = new SessionAggregates();
		applySlayerProgress(aggregates, 1, 99);
		applySlayerProgress(aggregates, 1, 98);
		applySlayerProgress(aggregates, 1, 97);
		assertEquals("currentRemaining must reflect only the latest authoritative value, not accumulate",
			Integer.valueOf(97), aggregates.getLatestSlayerCurrentRemaining());
	}

	// J. slayerProgressDelta keeps accumulating (SUM semantics) exactly as
	// before, unaffected by and independent of currentRemaining's
	// OVERWRITE semantics -- both aggregates are maintained side by side
	// from the same events.
	@Test
	public void currentRemaining_doesNotDisturbSlayerProgressDeltaAccumulation()
	{
		SessionAggregates aggregates = new SessionAggregates();
		applySlayerProgress(aggregates, 1, 99);
		applySlayerProgress(aggregates, 1, 98);
		applySlayerProgress(aggregates, 1, 97);
		assertEquals(Integer.valueOf(3), aggregates.getSlayerProgressDelta());
		assertEquals(Integer.valueOf(97), aggregates.getLatestSlayerCurrentRemaining());
	}

	// =====================================================================
	// Slayer's own skill XP
	// (XP_CHANGE, skill="SLAYER") is NOT generic SKILLING lifecycle
	// evidence, and is NOT SLAYER_TASK_PROGRESS. Live evidence: a delayed
	// SLAYER XP_CHANGE arriving after a fresh BOSSING session had already
	// been established from an authoritative BOSS_KILL independently
	// proposed SKILLING/slayer, destroying the BOSSING session.
	// =====================================================================

	// Case A: SLAYER XP alone with no current session does not create
	// SKILLING/slayer.
	@Test
	public void slayerXp_aloneWithNoSession_doesNotCreateSkillingSlayer()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "SLAYER", 113), null, new ClassifierContext());

		assertEquals("Slayer XP alone must never be sufficient lifecycle evidence to start any session",
			SignalDecisionKind.IGNORE_FOR_SESSION, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// Case B: delayed SLAYER XP while an ACTIVE BOSSING session is current
	// does not switch away from BOSSING -- attaches as a metric only.
	@Test
	public void slayerXp_whileBossing_attachesMetricOnly_neverSwitchesToSkillingSlayer()
	{
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(5), "SLAYER", 113), current, new ClassifierContext());

		assertEquals("Slayer XP must never propose a lifecycle switch while a combat-branch session "
			+ "is current -- it must attach as a metric only",
			SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertNotNull(result.getMetricUpdate());
	}

	// Case C: delayed SLAYER XP while an ACTIVE SLAYER session is current
	// attaches XP but never alters identity.
	@Test
	public void slayerXp_whileSlayerSession_attachesXpButNeverAltersIdentity()
	{
		ActivityIdentity slayer = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session current = sessionOf(slayer, T0);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(5), "SLAYER", 113), current, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull("METRIC_ONLY must never carry an identity -- the session's own identity is untouched",
			result.getIdentity());
		SessionAggregates aggregates = new SessionAggregates();
		SessionAggregateUpdater.apply(aggregates, result.getMetricUpdate());
		assertEquals(Long.valueOf(113L), aggregates.getXpGainedBySkill().get("SLAYER"));
	}

	// Case D: ordinary noncombat skills (e.g. Woodcutting) still create
	// SKILLING normally -- Slayer's special-casing must not leak into
	// every other skill.
	@Test
	public void woodcuttingXp_stillCreatesSkillingNormally()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "WOODCUTTING", 25), null, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.skillingIdentity("WOODCUTTING"), result.getIdentity());
	}

	// Regression guard: SLAYER XP must never be treated as equivalent to
	// SLAYER_TASK_PROGRESS -- it carries no task name/location and must
	// never be used to infer or refine task identity.
	@Test
	public void slayerXp_isNeverTreatedAsTaskProgress_carriesNoTaskIdentityInference()
	{
		Session current = sessionOf(ActivitySignalClassifier.genericCombatIdentity(), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(5), "SLAYER", 113), current, new ClassifierContext());

		// A generic COMBAT session is still combat-branch, so this stays
		// METRIC_ONLY (attaches to the existing combat session) rather
		// than IGNORE_FOR_SESSION -- but critically, no SLAYER identity
		// is ever proposed here, unlike a real SLAYER_TASK_PROGRESS
		// signal would.
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// =====================================================================
	// A regression test for the exact reported live sequence, driven
	// end-to-end through the real classifier + SessionLifecycleEngine +
	// SessionAggregateUpdater (mirroring exactly what
	// SessionRuntimeCoordinator.resolveAndApplyBatches() does with each
	// signal's classification, one event at a time -- no tick-batching
	// timing complexity, since every event here lands on its own,
	// already-resolved observation):
	//
	//   SUSPENDED GG -> AGILITY XP +3 -> combat XP -> SLAYER_TASK_PROGRESS
	//   Gargoyles -> BOSS_KILL Grotesque Guardians -> delayed SLAYER XP
	//
	// Expected: the SAME original GG sessionId throughout, ACTIVE,
	// BOSSING/Grotesque Guardians, boss sessionOccurrences incremented,
	// Slayer XP retained as an XP metric, no SKILLING/slayer or
	// SKILLING/agility session ever created, and NO session churn at all
	// (the original session is never finalized anywhere in this trace).
	// =====================================================================
	@Test
	public void liveRetest_suspendedGG_incidentalAgility_thenRealReturn_sameSessionThroughout_noChurn()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		ClassifierContext context = new ClassifierContext();
		List<Session> everFinalized = new ArrayList<>();

		ActivityIdentity gg = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		LifecycleResult start = engine.onQualifyingActivity(gg, T0);
		String originalSessionId = start.getCurrent().getSessionId();

		// The 5-minute suspension itself (unchanged, out of scope for
		// this fix) -- suspend, well inside the 30-minute resume window.
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		LifecycleResult suspendResult = engine.advanceTime(suspendedAt);
		assertEquals(SessionState.SUSPENDED, suspendResult.getCurrent().getState());

		// Incidental AGILITY +3 XP while returning to the encounter.
		Instant agilityAt = suspendedAt.plusSeconds(97); // matches the live report's ~1.5 minute gap
		SignalClassification agility = classifier.classify(
			SessionSignal.xpChange(agilityAt, "AGILITY", 3), engine.getCurrentSession(), context);
		LifecycleResult afterAgility = agility.getDecisionKind() == SignalDecisionKind.REFINE
			? engine.refineIdentity(agility.getIdentity(), agilityAt)
			: engine.onQualifyingActivity(agility.getIdentity(), agilityAt);
		if (afterAgility.getFinalized() != null)
		{
			everFinalized.add(afterAgility.getFinalized());
		}

		assertTrue("A single incidental Agility XP signal must never finalize the still-"
			+ "resumable boss session", everFinalized.isEmpty());
		assertEquals(SessionState.SUSPENDED, afterAgility.getCurrent().getState());
		assertEquals(originalSessionId, afterAgility.getCurrent().getSessionId());
		assertEquals(gg, afterAgility.getCurrent().getActivityIdentity());

		// Combat XP as the fight begins -- with no active Slayer task
		// tracked yet, this is genericCombatIdentity() (a MAGIC/melee/
		// ranged/etc XP swing with no other corroboration). Generic combat
		// evidence must never, by itself, resume a
		// SPECIFIC SUSPENDED session (see decideCombatBranch()'s own
		// javadoc) -- so this is now METRIC_ONLY, applied directly to the
		// still-SUSPENDED GG session's aggregates, with no identity claim
		// and no resume.
		Instant combatXpAt = agilityAt.plusSeconds(8);
		SignalClassification combat = classifier.classify(
			SessionSignal.xpChange(combatXpAt, "STRENGTH", 40), engine.getCurrentSession(), context);

		assertEquals("Generic combat XP against a SUSPENDED specific session must never "
			+ "be strong enough to prove/resume it by itself", SignalDecisionKind.METRIC_ONLY, combat.getDecisionKind());
		assertNull(combat.getIdentity());
		SessionAggregateUpdater.apply(engine.getCurrentSession().getAggregates(), combat.getMetricUpdate());

		assertEquals("the suspended GG session must remain untouched -- still SUSPENDED, not resumed "
			+ "by generic combat evidence alone", SessionState.SUSPENDED, engine.getCurrentSession().getState());
		assertEquals(originalSessionId, engine.getCurrentSession().getSessionId());
		assertEquals(gg, engine.getCurrentSession().getActivityIdentity());
		assertTrue(everFinalized.isEmpty());

		// BOSS_KILL Grotesque Guardians KC 76 (authoritative, same boss --
		// FOLLOW-UP FIX reordering note: this exact-identity match is what
		// actually resumes GG in this trace now. While GG was still
		// SUSPENDED, only evidence that genuinely matches the suspended
		// identity itself may resume it -- see decideCombatBranch()'s
		// exact-identity-match fast path, which this fix never touches).
		Instant bossKillAt = combatXpAt.plusSeconds(4);
		SignalClassification kill = classifier.classify(
			SessionSignal.bossKill(bossKillAt, "Grotesque Guardians", 76), engine.getCurrentSession(), context);
		LifecycleResult afterKill = kill.getDecisionKind() == SignalDecisionKind.REFINE
			? engine.refineIdentity(kill.getIdentity(), bossKillAt)
			: engine.onQualifyingActivity(kill.getIdentity(), bossKillAt);
		if (afterKill.getFinalized() != null)
		{
			everFinalized.add(afterKill.getFinalized());
		}
		SessionAggregateUpdater.apply(afterKill.getCurrent().getAggregates(), kill.getMetricUpdate());

		assertTrue("a same-identity BOSS_KILL must legitimately resume the still-suspended GG session",
			everFinalized.isEmpty());
		assertEquals(SessionState.ACTIVE, afterKill.getCurrent().getState());
		assertEquals(originalSessionId, afterKill.getCurrent().getSessionId());
		assertEquals(gg, afterKill.getCurrent().getActivityIdentity());
		assertEquals("the authoritative BOSS_KILL must credit the SAME session's boss occurrences",
			1, afterKill.getCurrent().getAggregates().getReliableCount().getSessionOccurrences());

		// Trace intentionally ends here -- everything up to and including
		// the same-identity BOSS_KILL resume above is unaffected by the
		// GG -> Gargoyles activity-switch fix
		// and remains exactly as this regression always asserted: no
		// churn, same sessionId, ACTIVE BOSSING/Grotesque Guardians, boss
		// occurrences credited to the SAME session.
		//
		// A further
		// "SLAYER_TASK_PROGRESS Gargoyles 111 -> 110 arrives against the
		// already-ACTIVE GG session and stays GG/BOSSING" step, plus a
		// trailing delayed-SLAYER-XP step and final-state assertions built
		// on top of it, used to continue here, and was removed. That SLAYER_TASK_PROGRESS
		// assertion encoded EXACTLY the live bug this fix addresses -- see
		// liveBugFix_activeGrotesqueGuardians_specificGargoylesTaskProgress_switchesToSlayerGargoyles
		// below, which reproduces and proves the fix for that exact
		// transition instead. Retaining a stale "stays GG" assertion here
		// would just re-assert the defect Josh reported live; it is
		// deliberately removed rather than fixed in place, since this
		// method's own purpose (suspended-candidate + same-
		// identity-resume regression coverage) is already fully proven by
		// the steps above.
		Session finalState = engine.getCurrentSession();
		assertTrue("no session churn through the same-identity resume: the original GG session must "
			+ "never have been finalized anywhere in this trace", everFinalized.isEmpty());
		assertEquals(originalSessionId, finalState.getSessionId());
		assertEquals(SessionState.ACTIVE, finalState.getState());
		assertEquals(gg, finalState.getActivityIdentity());
		assertEquals(ActivityType.BOSSING, finalState.getActivityIdentity().getActivityType());
	}
	// =====================================================================
	// LIVE BUG: MAGIC XP SEMANTICS + MIXED-BATCH RESOLUTION AGAINST A
	// SUSPENDED SPECIFIC SESSION. See decideCombatBranch()'s own javadoc
	// for the full root-cause note. Magic XP (or any combat-skill XP with
	// no active Slayer task tracked) is genericCombatIdentity() -- it can
	// come from teleporting, utility spellcasting, alchemy, enchanting, or
	// actual combat, so it must never, by itself, be strong enough to
	// prove or resume a SPECIFIC SUSPENDED activity.
	// =====================================================================

	// 1. No current session + Magic XP alone: fixed below
	// (previously "invariant A" -- generic COMBAT was
	// allowed to start fresh from bare Magic XP with no existing session.
	// Live evidence proved that assumption itself false: a POH teleport's
	// MAGIC +30, with genuinely no combat happening, must not create a
	// player-facing COMBAT session either). Magic XP is now METRIC_ONLY
	// unconditionally -- see classifyXpChange()'s own audit note -- so
	// with no current session at all, the metric is valid but has
	// nowhere to attach and no lifecycle claim is made at all.
	@Test
	public void magicXpAlone_noCurrentSession_noLongerStartsAnySession_correctedByFinalFix()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "MAGIC", 30), null, new ClassifierContext());
		assertEquals("bare Magic XP is never lifecycle proof -- not even for the generic COMBAT "
			+ "identity -- so it must never independently start a session",
			SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertNotNull("the XP itself is still a valid metric, even though it establishes nothing",
			result.getMetricUpdate());
	}

	// 2. SUSPENDED BOSSING/Zulrah + Magic XP alone must not prove/resume
	// Zulrah -- the live bug's exact single-signal case.
	@Test
	public void magicXpAlone_suspendedBossingZulrah_mustNotProveOrResumeZulrah()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session suspended = suspendedSessionOf(zulrah, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 60), suspended, new ClassifierContext());

		assertEquals("generic combat evidence against a SUSPENDED specific session must never claim "
			+ "an identity of its own", SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertNotNull("the XP itself must still be a valid, retained metric",
			result.getMetricUpdate());
	}

	// 3. SUSPENDED SLAYER/Gargoyles + generic combat evidence alone must
	// not prove/resume Gargoyles either -- the rule is generic, not
	// special-cased to BOSSING or Zulrah.
	@Test
	public void genericCombatEvidenceAlone_suspendedSlayerGargoyles_mustNotProveOrResumeGargoyles()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session suspended = suspendedSessionOf(gargoyles, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		// No active Slayer task tracked in this fresh context -- ordinary
		// melee XP with no corroboration, exactly like the live Magic case.
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "STRENGTH", 40), suspended, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// 4. SUSPENDED generic COMBAT + Magic XP alone: fixed below
	// -- previously Magic XP was allowed to resume a
	// SUSPENDED generic-COMBAT session on the reasoning that it was an
	// "exact identity match, not a false specific claim." The live report
	// established a stricter rule: Magic XP by itself must never resume
	// ANY suspended combat-branch session, generic or specific, since a
	// teleport/alchemy/enchant is not proof the player resumed fighting.
	// Non-Magic combat-skill XP (e.g. Strength) still resumes a SUSPENDED
	// generic-COMBAT session normally -- see
	// combatXpWithNoSlayerContextIsGenericCombat/genericCombatRefinesToBossing
	// and this file's other unaffected combat-skill tests -- this
	// correction is Magic-specific.
	@Test
	public void magicXpAlone_suspendedGenericCombat_noLongerIndependentlyResumes_correctedByFinalFix()
	{
		ActivityIdentity generic = ActivitySignalClassifier.genericCombatIdentity();
		Session suspended = suspendedSessionOf(generic, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 30), suspended, new ClassifierContext());

		assertEquals("Magic XP alone must never resume a SUSPENDED session, even a generic-COMBAT "
			+ "one it would otherwise exactly match", SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// 8. ACTIVE BOSSING + ordinary combat XP: remains BOSSING, XP still
	// aggregates -- completely unaffected by the fix (ACTIVE sessions never
	// touch the new SUSPENDED-only carve-out). Uses STRENGTH (not MAGIC --
	// the fix below changed Magic's own ACTIVE-session
	// behavior, so Magic is no longer a suitable stand-in for "ordinary
	// combat XP" here; see magicXpAlone_activeBossingZulrah_... below for
	// Magic's own, now-different, ACTIVE-session behavior).
	@Test
	public void activeBossing_ordinaryCombatXp_remainsBossing_xpStillAggregates()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session active = sessionOf(zulrah, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "STRENGTH", 30), active, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(zulrah, result.getIdentity());
		SessionAggregateUpdater.apply(active.getAggregates(), result.getMetricUpdate());
		assertEquals(Long.valueOf(30L), active.getAggregates().getXpGainedBySkill().get("STRENGTH"));
	}

	// 8b. ACTIVE BOSSING/Zulrah + Magic XP alone must
	// NOT independently heartbeat -- the classification itself is
	// METRIC_ONLY (no identity), but the metric is still valid and, when
	// applied by the caller exactly as SessionRuntimeCoordinator's own
	// NO_EVIDENCE dispatch does (credit to whatever is already current,
	// no lifecycle call), lands on the ACTIVE session's aggregates
	// without touching its lastActiveAt/duration.
	@Test
	public void magicXpAlone_activeBossingZulrah_metricOnlyNoIndependentHeartbeat()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session active = sessionOf(zulrah, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "MAGIC", 30), active, new ClassifierContext());

		assertEquals("Magic XP alone must never act as an independent heartbeat, even against an "
			+ "ACTIVE compatible session", SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		SessionAggregateUpdater.apply(active.getAggregates(), result.getMetricUpdate());
		assertEquals("the metric is still credited, exactly once",
			Long.valueOf(30L), active.getAggregates().getXpGainedBySkill().get("MAGIC"));
		assertEquals("identity/state are untouched by a METRIC_ONLY classification",
			zulrah, active.getActivityIdentity());
		assertEquals(SessionState.ACTIVE, active.getState());
	}

	// 9. A genuine magic-combat scenario WITH independently corroborating
	// combat evidence: Magic XP is not lost -- once real evidence (an
	// authoritative BOSS_KILL for the SAME suspended boss) legitimately
	// resumes the session, subsequently-arriving Magic XP still attaches
	// as a metric (as METRIC_ONLY,
	// never as its own independent heartbeat -- the session's own
	// identity/lastActiveAt were already established by the BOSS_KILL,
	// Magic just adds its XP on top).
	@Test
	public void magicXpWithIndependentCombatCorroboration_isNotLost()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(zulrah, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());

		// Independently trustworthy corroboration: a real BOSS_KILL for
		// the SAME boss -- authoritative same-identity evidence, resumes
		// Zulrah legitimately (unaffected by the fix, which never touches
		// the equals-identity branch).
		Instant killAt = T0.plusSeconds(9999);
		SignalClassification kill = classifier.classify(
			SessionSignal.bossKill(killAt, "Zulrah", 5), engine.getCurrentSession(), new ClassifierContext());
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, kill.getDecisionKind());
		LifecycleResult afterKill = engine.onQualifyingActivity(kill.getIdentity(), killAt);
		assertEquals(SessionState.ACTIVE, afterKill.getCurrent().getState());

		// Magic XP now arrives against the legitimately-ACTIVE Zulrah
		// session -- must still be credited, but METRIC_ONLY: it never
		// independently re-proves/re-heartbeats Zulrah on its own.
		Instant magicAt = killAt.plusSeconds(3);
		SignalClassification magic = classifier.classify(
			SessionSignal.xpChange(magicAt, "MAGIC", 30), engine.getCurrentSession(), new ClassifierContext());
		assertEquals(SignalDecisionKind.METRIC_ONLY, magic.getDecisionKind());
		assertNull(magic.getIdentity());
		SessionAggregateUpdater.apply(engine.getCurrentSession().getAggregates(), magic.getMetricUpdate());

		assertEquals("Magic XP must not be lost once independently corroborated",
			Long.valueOf(30L), engine.getCurrentSession().getAggregates().getXpGainedBySkill().get("MAGIC"));
		assertEquals(zulrah, engine.getCurrentSession().getActivityIdentity());
	}

	// =====================================================================
	// FOLLOW-UP FIX -- SUSPENDED SPECIFIC ACTIVITY MUST NOT SWALLOW A
	// DIFFERENT SPECIFIC ACTIVITY. The prior fix (immediately above) only
	// scoped its SUSPENDED carve-out to GENERIC combat evidence
	// (genericCombatIdentity()). A genuinely SPECIFIC, different,
	// lower-rank identity (e.g. a real slayerIdentity() from
	// SLAYER_TASK_PROGRESS, arriving against a SUSPENDED higher-rank
	// BOSSING identity) was still silently relabeled AS the suspended
	// identity by decideCombatBranch()'s old unconditional
	// "never downgrade" fallthrough -- purely because of
	// ActivityPrecedence ranking, with zero evidence the player ever
	// returned to the suspended activity. Cases A-G below match the
	// audit's own lettered case list exactly. No Zulrah/Gargoyles-named
	// special-casing exists in production: the fix is purely
	// type/state-based, these names are used here only for realism and
	// continuity with the live report.
	// =====================================================================

	// A. SUSPENDED BOSSING/Zulrah + generic COMBAT evidence: remains
	// SUSPENDED, no resume (already covered above by
	// magicXpAlone_suspendedBossingZulrah_mustNotProveOrResumeZulrah;
	// restated here under the case-letter naming for this audit's own
	// regression coverage).
	@Test
	public void caseA_suspendedBossingZulrah_genericCombat_remainsSuspendedNoResume()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session suspended = suspendedSessionOf(zulrah, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 60), suspended, new ClassifierContext());

		assertEquals("generic combat evidence must never claim an identity of its own against a "
			+ "SUSPENDED specific session", SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// B. SUSPENDED BOSSING/Zulrah + specific SLAYER/Gargoyles evidence:
	// the Gargoyles evidence must remain SLAYER/Gargoyles -- it must NOT
	// be relabeled as Zulrah. Normal different-activity transition logic
	// (SessionLifecycleEngine's authoritative-switch machinery) then
	// applies from there.
	@Test
	public void caseB_suspendedBossingZulrah_specificSlayerGargoyles_mustNotBeRelabeledAsZulrah()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session suspended = suspendedSessionOf(zulrah, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(9999), "Gargoyles", "Catacombs of Kourend", 1),
			suspended, new ClassifierContext());

		assertEquals("trustworthy specific evidence for a DIFFERENT activity must be honestly "
			+ "proposed as itself, never silently relabeled as the suspended Zulrah identity",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(gargoyles, result.getIdentity());
		assertNotEquals(zulrah, result.getIdentity());

		// Feeding this through the real engine confirms the end-to-end
		// consequence: an authoritative different-identity switch, not a
		// false resume of Zulrah.
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(zulrah, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		LifecycleResult switched = engine.onQualifyingActivity(gargoyles, T0.plusSeconds(9999));
		assertEquals("the suspended Zulrah session must finalize, never silently absorb the "
			+ "Gargoyles evidence as a Zulrah resume", SessionState.FINALIZED, switched.getFinalized().getState());
		assertEquals(zulrah, switched.getFinalized().getActivityIdentity());
		assertEquals(gargoyles, switched.getCurrent().getActivityIdentity());
	}

	// C. SUSPENDED SLAYER/Gargoyles + generic COMBAT evidence: remains
	// SUSPENDED, must not prove Gargoyles either -- the rule is generic,
	// not special-cased to BOSSING (already covered above by
	// genericCombatEvidenceAlone_suspendedSlayerGargoyles_mustNotProveOrResumeGargoyles;
	// restated here under the case-letter naming).
	@Test
	public void caseC_suspendedSlayerGargoyles_genericCombat_remainsSuspendedNoResume()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session suspended = suspendedSessionOf(gargoyles, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "STRENGTH", 40), suspended, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// D. SUSPENDED SLAYER/Gargoyles + specific BOSSING/Grotesque Guardians
	// evidence: Grotesque Guardians wins immediately as the trustworthy
	// different specific activity; the old Gargoyles session finalizes
	// per normal lifecycle semantics. This direction is the moreSpecific
	// branch (BOSSING outranks SLAYER), already correctly implemented
	// before this audit -- this test adds confirming regression coverage
	// for the exact case-letter the audit called out, proving no
	// production change was needed here.
	@Test
	public void caseD_suspendedSlayerGargoyles_specificBossingGrotesqueGuardians_grotesqueGuardiansWinsImmediately()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session suspended = suspendedSessionOf(gargoyles, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		ActivityIdentity grotesqueGuardians = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		SignalClassification result = classifier.classify(
			SessionSignal.bossKill(T0.plusSeconds(9999), "Grotesque Guardians", 1), suspended, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(grotesqueGuardians, result.getIdentity());

		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(gargoyles, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		LifecycleResult switched = engine.onQualifyingActivity(grotesqueGuardians, T0.plusSeconds(9999));
		assertEquals(SessionState.FINALIZED, switched.getFinalized().getState());
		assertEquals(gargoyles, switched.getFinalized().getActivityIdentity());
		assertEquals(grotesqueGuardians, switched.getCurrent().getActivityIdentity());
	}

	// E. ACTIVE BOSSING/Zulrah + generic COMBAT evidence: existing
	// no-downgrade/current-specificity behavior remains -- unaffected by
	// this fix, which only ever touches the SUSPENDED branch. Uses
	// STRENGTH (not MAGIC -- a LATER fix changed Magic's own ACTIVE-
	// session behavior to METRIC_ONLY unconditionally; see
	// magicXpAlone_activeBossingZulrah_metricOnlyNoIndependentHeartbeat
	// above for that. STRENGTH still exercises exactly the generic-
	// combat-evidence-vs-ACTIVE-specific-identity behavior this test was
	// written to cover).
	@Test
	public void caseE_activeBossingZulrah_genericCombat_noDowngradeBehaviorUnchanged()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session active = sessionOf(zulrah, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "STRENGTH", 30), active, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(zulrah, result.getIdentity());
	}

	// F. ACTIVE SLAYER/Gargoyles + generic COMBAT evidence: existing
	// no-downgrade/current-specificity behavior remains -- unaffected by
	// this fix.
	@Test
	public void caseF_activeSlayerGargoyles_genericCombat_noDowngradeBehaviorUnchanged()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session active = sessionOf(gargoyles, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "STRENGTH", 40), active, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(gargoyles, result.getIdentity());
	}

	// G. ACTIVE BOSSING/Zulrah
	// + a SLAYER_TASK_PROGRESS naming a specifically-different activity
	// (Gargoyles) is EXACTLY the live-reported defect (GG -> Gargoyles)
	// generalized to a different boss. This case previously asserted the
	// old "ACTIVE never downgrades, even for authoritative
	// SLAYER_TASK_PROGRESS naming a different specific activity" behavior;
	// that assertion WAS the bug. AuthoritativeTaskProgress evidence now
	// switches even an ACTIVE session, because SLAYER_TASK_PROGRESS names
	// its task explicitly and is never a generic/incidental signal --
	// unlike generic COMBAT XP (see cases E/F above, still no-downgrade)
	// or XP_CHANGE-derived specific identities (see
	// zulrah_bossActivityContextEstablishesBossingSessionBeforeBossKill_sameSessionThroughout
	// in SessionRuntimeCoordinatorTest, still no-downgrade -- this fix is
	// scoped to SLAYER_TASK_PROGRESS only via the fromAuthoritativeTaskProgress
	// flag, not to "any specific identity").
	@Test
	public void caseG_activeBossingZulrah_specificSlayerGargoyles_authoritativeTaskProgressNowSwitches()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session active = sessionOf(zulrah, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(10), "Gargoyles", "Catacombs of Kourend", 1),
			active, new ClassifierContext());

		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		assertEquals("authoritative SLAYER_TASK_PROGRESS naming a different activity now switches even an ACTIVE session",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(gargoyles, result.getIdentity());
		assertNotEquals(zulrah, result.getIdentity());
	}

	// A. SUPERSEDED (live bug-fix pass -- boss self-consumption of its
	// own Slayer task): this test formerly asserted that ANY
	// SLAYER_TASK_PROGRESS naming a different activity switches an ACTIVE
	// BOSSING session immediately and unconditionally. That was itself
	// discovered to be a NEW regression: Grotesque Guardians' OWN kills
	// ALSO decrement the Gargoyles Slayer task (GG is a Slayer-task boss
	// for Gargoyles), producing an IDENTICAL SLAYER_TASK_PROGRESS event to
	// a real Gargoyle kill -- live evidence showed the old unconditional
	// rule fabricated a bogus zero-duration SLAYER/Gargoyles session for
	// GG's own kill. See BossTaskAffinity/ClassifierContext/this class's
	// classifySlayerTaskProgress() javadoc for the full corrected design.
	// This test now proves the CORRECTED behavior: with NO corroborating
	// evidence at all (no context arming from a prior call, no batch-level
	// loot), a lone SLAYER_TASK_PROGRESS naming the current boss's OWN
	// known task must NOT switch -- it stays BOSSING, suppressed by
	// default, with the metric still attached. See
	// liveBugFix_gargoylesTaskProgress_confirmedByNonBossLoot_switchesToSlayerGargoyles
	// and liveBugFix_gargoylesTaskProgress_thenSameBossKill_abortsCandidate_staysBossing
	// below for the confirm/abort halves of this same fix, and
	// caseG_activeBossingZulrah_specificSlayerGargoyles_authoritativeTaskProgressNowSwitches
	// above for proof this suppression is scoped EXACTLY to a confirmed
	// boss-owns-task pair (Zulrah has no such affinity for Gargoyles, so
	// it still switches immediately, completely unaffected by this fix).
	@Test
	public void liveBugFixCorrected_activeGrotesqueGuardians_ownTaskProgressAlone_suppressedStaysBossingPendingCorroboration()
	{
		ActivityIdentity gg = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		Session active = sessionOf(gg, T0);
		assertEquals(SessionState.ACTIVE, active.getState());
		assertEquals(ActivityType.BOSSING, active.getActivityIdentity().getActivityType());

		ClassifierContext context = new ClassifierContext();
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(30), "Gargoyles", "Catacombs of Kourend", 1, 98),
			active, context);

		assertEquals("a boss's own known-task progress, with no corroborating evidence either way, "
			+ "must never fabricate a bogus Slayer session -- it stays a heartbeat of the boss",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(gg, result.getIdentity());
		assertEquals(ActivityType.BOSSING, result.getIdentity().getActivityType());
		// CANDIDATE-METRIC BUFFERING follow-up fix: the progress metric is
		// no longer attached to THIS classification while suppressed --
		// it is BUFFERED in the ClassifierContext candidate instead (see
		// ClassifierContext's own javadoc), applied exactly once by
		// whichever caller eventually resolves the candidate. Applying it
		// here, immediately, to the boss heartbeat is exactly the
		// apply-then-possibly-retroactively-move behavior this follow-up
		// fix replaces.
		assertNull("the boss heartbeat itself must carry no metric while the candidate is pending "
			+ "-- it is buffered, not applied", result.getMetricUpdate());
		assertEquals("the progress metric (delta + currentRemaining) must be buffered on the "
			+ "pending candidate instead", 1, context.getPendingBossTaskCandidateMetrics().size());
	}

	// A2. The CONFIRM half: a pending boss-task candidate (armed by the
	// call above) is confirmed by a SERVER_NPC_LOOT whose sourceName is
	// genuinely NOT the boss itself (nor one of its known aliases) --
	// this is real proof a different NPC produced the progress, so the
	// switch now honestly happens, driven by classifyServerNpcLoot()
	// itself (the cross-tick half of this fix; see
	// SessionSignalBatchResolver.applyBossSelfConsumptionCorroboration()
	// for the same-tick, order-independent half).
	@Test
	public void liveBugFix_gargoylesTaskProgress_confirmedByNonBossLoot_switchesToSlayerGargoyles()
	{
		ActivityIdentity gg = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		Session active = sessionOf(gg, T0);
		ClassifierContext context = new ClassifierContext();

		SignalClassification progress = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(30), "Gargoyles", "Catacombs of Kourend", 1, 98),
			active, context);
		assertEquals(gg, progress.getIdentity());

		SignalClassification loot = classifier.classify(
			SessionSignal.serverNpcLoot(T0.plusSeconds(31), "Gargoyle",
				java.util.Collections.singletonList(new SessionSignal.LootDrop(1, "Big bones", 1))),
			active, context);

		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		assertEquals("a genuinely different NPC's loot confirms the pending candidate as a real switch",
			SignalDecisionKind.START_OR_HEARTBEAT, loot.getDecisionKind());
		assertEquals(gargoyles, loot.getIdentity());
		assertNotEquals(gg, loot.getIdentity());
		assertNotNull("the confirming loot's own metric rides with the newly-confirmed identity",
			loot.getMetricUpdate());
		// CANDIDATE-METRIC BUFFERING follow-up fix / REGRESSION 3+4: the
		// candidate's own buffered progress metric (slayerProgress
		// delta=1, currentRemaining=98) must ALSO ride with this same
		// classification, alongside the loot's own metric -- both
		// destined for the brand-new Gargoyles session, so it shows the
		// correct progressDelta/currentRemaining immediately upon
		// confirmation.
		assertEquals("both the buffered progress metric AND the confirming loot's own metric must "
			+ "be carried by this one classification", 2, loot.getMetricUpdates().size());
	}

	// A3. The ABORT half: a pending boss-task candidate is aborted by a
	// same-boss authoritative BOSS_KILL -- proof the progress WAS
	// self-consumption. No switch was ever made, so this is simply
	// clearing the watch; the BOSS_KILL itself is an ordinary heartbeat
	// of the SAME, already-current boss identity.
	@Test
	public void liveBugFix_gargoylesTaskProgress_thenSameBossKill_abortsCandidate_staysBossing()
	{
		ActivityIdentity gg = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		Session active = sessionOf(gg, T0);
		ClassifierContext context = new ClassifierContext();

		classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(30), "Gargoyles", "Catacombs of Kourend", 1, 98),
			active, context);

		SignalClassification kill = classifier.classify(
			SessionSignal.bossKill(T0.plusSeconds(34), "Grotesque Guardians", 89), active, context);

		// CANDIDATE-METRIC BUFFERING follow-up fix / REGRESSION 1: the
		// buffered progress metric (never applied while pending) and this
		// SAME kill's own reliableCount metric are both carried by this
		// one classification, both destined for the SAME, never-left
		// BOSSING session -- a delayed-but-correct application, applied
		// exactly once.
		assertEquals("both the buffered progress metric AND this kill's own reliableCount metric "
			+ "must be carried by this one classification", 2, kill.getMetricUpdates().size());

		assertEquals("the same-boss BOSS_KILL confirms self-consumption and aborts the candidate -- "
			+ "still just a heartbeat of the already-current boss identity",
			SignalDecisionKind.START_OR_HEARTBEAT, kill.getDecisionKind());
		assertEquals(gg, kill.getIdentity());

		// Proof the candidate was genuinely cleared, not merely
		// coincidentally not consulted: a LATER, unrelated non-boss loot
		// must no longer be able to "confirm" anything -- with no pending
		// candidate, it is just an ordinary ACTIVE-session loot metric.
		SignalClassification laterLoot = classifier.classify(
			SessionSignal.serverNpcLoot(T0.plusSeconds(40), "Gargoyle",
				java.util.Collections.singletonList(new SessionSignal.LootDrop(1, "Big bones", 1))),
			active, context);
		assertEquals(SignalDecisionKind.METRIC_ONLY, laterLoot.getDecisionKind());
		assertNull("the aborted candidate must never resurface and switch identity later",
			laterLoot.getIdentity());
	}

	// B. Explicit negative-space coverage: ACTIVE
	// BOSSING/Grotesque Guardians + a GENERIC combat XP gain (no
	// SLAYER_TASK_PROGRESS, no other specific evidence) must NOT
	// downgrade/switch away from Grotesque Guardians. This is the case
	// the task explicitly warns against "fixing" -- a general rule that
	// any weak/incidental XP can downgrade an ACTIVE boss session would
	// break this. Only authoritative SLAYER_TASK_PROGRESS (case A above)
	// switches; ordinary combat XP under the same boss never does.
	@Test
	public void liveBugFixNegativeSpace_activeGrotesqueGuardians_genericCombatXp_doesNotDowngrade()
	{
		ActivityIdentity gg = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		Session active = sessionOf(gg, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "STRENGTH", 40), active, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals("generic combat XP must never downgrade an ACTIVE boss session -- only "
			+ "authoritative SLAYER_TASK_PROGRESS naming a different activity does (case A)",
			gg, result.getIdentity());
	}

	// =====================================================================
	// CURRENT SESSION HYSTERESIS PASS -- SHELLBANE GRYPHON CANONICAL-IDENTITY
	// CHURN FIX. Live-reproduced regression, IDENTICAL SHAPE to the Grotesque
	// Guardians/Gargoyles self-consumption fix above (cases A/A2/A3, case G,
	// and the negative-space case immediately above): Shellbane Gryphon's
	// own kill also decrements the "Gryphons" Slayer task, so before this
	// pass's BossTaskAffinity data addition, an ACTIVE BOSSING/Shellbane
	// Gryphon session receiving SLAYER_TASK_PROGRESS/Gryphons was
	// unconditionally switched away into a brand-new, zero-duration
	// SLAYER/Gryphons "bridge" session -- exactly the GG/Gargoyles bug this
	// same machinery already fixed, just for a second boss. These tests
	// mirror the GG trio above line-for-line, substituting Shellbane
	// Gryphon/Gryphons, proving the SAME already-tested mechanism (no new
	// code) now also covers this boss. See BossTaskAffinity's own javadoc
	// for the one-line data addition this fix made.
	// =====================================================================

	// 1. An ACTIVE Shellbane Gryphon boss session receiving its own known
	// task's progress, with no corroborating evidence either way, must NOT
	// switch primary identity -- it stays a heartbeat of the boss, exactly
	// like the GG case above.
	@Test
	public void activeShellbaneBoss_matchingGryphonsProgress_doesNotSwitchPrimary()
	{
		ActivityIdentity shellbane = ActivitySignalClassifier.bossIdentity("Shellbane Gryphon");
		Session active = sessionOf(shellbane, T0);
		assertEquals(SessionState.ACTIVE, active.getState());
		assertEquals(ActivityType.BOSSING, active.getActivityIdentity().getActivityType());

		ClassifierContext context = new ClassifierContext();
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(30), "Gryphons", "Karuulm Slayer Dungeon", 1, 28),
			active, context);

		assertEquals("Shellbane Gryphon's own known-task progress, with no corroborating evidence "
			+ "either way, must never fabricate a bogus zero-duration SLAYER/Gryphons session -- it "
			+ "stays a heartbeat of the boss, exactly the same suppression the Grotesque Guardians "
			+ "case above already proves",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(shellbane, result.getIdentity());
		assertEquals(ActivityType.BOSSING, result.getIdentity().getActivityType());
		assertNull("the boss heartbeat itself must carry no metric while the candidate is pending "
			+ "-- it is buffered, not applied", result.getMetricUpdate());
		assertEquals("the progress metric (delta + currentRemaining) must be buffered on the "
			+ "pending candidate instead", 1, context.getPendingBossTaskCandidateMetrics().size());
	}

	// 2. The ENRICH half: a pending boss-task candidate (armed by matching
	// Slayer progress) is confirmed by a SAME-boss BOSS_KILL -- proof the
	// progress WAS self-consumption. The buffered progress metric AND the
	// kill's own reliableCount metric both land on the SAME, never-left
	// BOSSING session: Slayer progress ENRICHES the existing boss session
	// rather than replacing its identity.
	@Test
	public void matchingSlayerProgress_enrichesExistingBossSession()
	{
		ActivityIdentity shellbane = ActivitySignalClassifier.bossIdentity("Shellbane Gryphon");
		Session active = sessionOf(shellbane, T0);
		ClassifierContext context = new ClassifierContext();

		classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(30), "Gryphons", "Karuulm Slayer Dungeon", 1, 28),
			active, context);

		SignalClassification kill = classifier.classify(
			SessionSignal.bossKill(T0.plusSeconds(32), "Shellbane Gryphon", 12), active, context);

		assertEquals("the same-boss BOSS_KILL confirms self-consumption and aborts the candidate -- "
			+ "still just a heartbeat of the already-current boss identity, enriched with the "
			+ "buffered Slayer progress rather than replaced by it",
			SignalDecisionKind.START_OR_HEARTBEAT, kill.getDecisionKind());
		assertEquals(shellbane, kill.getIdentity());
		assertEquals("both the buffered Slayer progress metric AND this kill's own reliableCount "
			+ "metric must be carried by this one classification, both destined for the SAME "
			+ "BOSSING session -- Slayer evidence enriches the boss session, it never owns it",
			2, kill.getMetricUpdates().size());
	}

	// 3. Negative space -- an UNRELATED task naming a task Shellbane Gryphon
	// has no affinity for must NOT be suppressed/merged: it switches away
	// normally, proving this fix is scoped exactly to the confirmed
	// boss-owns-task pair (Shellbane/Gryphons), not to "any Slayer progress
	// while any boss is active."
	@Test
	public void unrelatedSlayerProgress_doesNotMergeIntoUnrelatedBossSession()
	{
		ActivityIdentity shellbane = ActivitySignalClassifier.bossIdentity("Shellbane Gryphon");
		Session active = sessionOf(shellbane, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(10), "Aberrant spectres", "Slayer Tower", 1),
			active, new ClassifierContext());

		ActivityIdentity aberrant = ActivitySignalClassifier.slayerIdentity("Aberrant spectres", "Slayer Tower");
		assertEquals("authoritative SLAYER_TASK_PROGRESS naming a task Shellbane Gryphon has no "
			+ "affinity for is genuinely unrelated evidence and must switch normally, exactly like "
			+ "caseG (Zulrah/Gargoyles) above -- this fix must never merge unrelated Slayer context "
			+ "into an unrelated boss session",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(aberrant, result.getIdentity());
		assertNotEquals(shellbane, result.getIdentity());
	}

	// 4. Regression safety -- ordinary, non-boss Slayer task progress (no
	// current session at all) must classify as normal SLAYER exactly as
	// before this fix; the BossTaskAffinity data addition must not affect
	// the non-boss path at all.
	@Test
	public void nonBossSlayerActivity_gryphonsProgress_stillClassifiesAsSlayerNormally()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gryphons", "Karuulm Slayer Dungeon", 1),
			null, new ClassifierContext());

		ActivityIdentity gryphons = ActivitySignalClassifier.slayerIdentity("Gryphons", "Karuulm Slayer Dungeon");
		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals("with no current BOSSING session to be ambiguous against, Gryphons task "
			+ "progress must classify as ordinary SLAYER exactly as before this fix",
			gryphons, result.getIdentity());
	}

	// 5. Regression safety -- generic COMBAT XP under an ACTIVE generic
	// combat session still refines into SLAYER upon matching task progress,
	// exactly as before this fix; the Shellbane Gryphon data addition must
	// not affect the ordinary combat-to-Slayer refinement path.
	@Test
	public void genericCombatSession_gryphonsTaskProgress_stillRefinesToSlayer()
	{
		ActivityIdentity genericCombat = ActivitySignalClassifier.genericCombatIdentity();
		Session active = sessionOf(genericCombat, T0);
		assertEquals(ActivityType.COMBAT, active.getActivityIdentity().getActivityType());

		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Gryphons", "Karuulm Slayer Dungeon", 1),
			active, new ClassifierContext());

		ActivityIdentity gryphons = ActivitySignalClassifier.slayerIdentity("Gryphons", "Karuulm Slayer Dungeon");
		assertEquals("generic COMBAT refining into a specific Slayer task upon matching task "
			+ "progress must behave exactly as before this fix -- unaffected by the Shellbane "
			+ "Gryphon/Gryphons self-consumption data addition",
			SignalDecisionKind.REFINE, result.getDecisionKind());
		assertEquals(gryphons, result.getIdentity());
	}

	// =====================================================================
	// CURRENT SESSION HYSTERESIS PASS -- SUSPENDED BOSS-OWN-TASK
	// REACTIVATION FIX. Live-PERSISTED repro: a SUSPENDED BOSSING/Shellbane
	// Gryphon session, still well inside its 30-minute resume window,
	// received its own known task's SLAYER_TASK_PROGRESS ("Gryphons") and
	// was immediately FINALIZED -- the exact same zero-duration SLAYER
	// bridge bug the ACTIVE-path fix above already eliminates, crossing
	// the SUSPENDED lifecycle boundary instead. Root cause: the ACTIVE/
	// SUSPENDED self-consumption guard in classifySlayerTaskProgress()
	// previously required `current.getState() == ACTIVE` before ever
	// consulting BossTaskAffinity -- a SUSPENDED boss session never got
	// the chance to prove self-consumption at all, and fell through to
	// the unconditional-switch path instead. See BossTaskAffinity's own
	// javadoc and this class's own classifySlayerTaskProgress() javadoc
	// for the fix: the SAME generalized boss-own-task mechanism (no new
	// code, no Shellbane-specific branch) now applies uniformly whether
	// the boss is ACTIVE or SUSPENDED.
	// =====================================================================

	// 1. A SUSPENDED Shellbane Gryphon session's own known-task progress
	// must never finalize it -- exactly the SUSPENDED counterpart of
	// activeShellbaneBoss_matchingGryphonsProgress_doesNotSwitchPrimary
	// above.
	@Test
	public void suspendedShellbane_matchingGryphonsProgress_doesNotFinalizeBoss()
	{
		ActivityIdentity shellbane = ActivitySignalClassifier.bossIdentity("Shellbane Gryphon");
		Session suspended = suspendedSessionOf(shellbane, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		ClassifierContext context = new ClassifierContext();
		Instant progressAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(30);
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1, 19),
			suspended, context);

		assertEquals("a SUSPENDED Shellbane Gryphon session's own known-task progress must never "
			+ "finalize it -- exactly like the ACTIVE case, it stays a heartbeat of the boss "
			+ "identity, never a genuine different-activity switch",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(shellbane, result.getIdentity());
		assertEquals(ActivityType.BOSSING, result.getIdentity().getActivityType());
		assertNull("the SUSPENDED boss heartbeat carries no metric of its own -- buffered on the "
			+ "pending candidate instead, exactly like the ACTIVE case",
			result.getMetricUpdate());
		assertEquals("the progress metric must be buffered on the pending candidate",
			1, context.getPendingBossTaskCandidateMetrics().size());
	}

	// 2. Negative space: a task Shellbane Gryphon has no affinity for is
	// genuine different-activity evidence and must still transition
	// normally while SUSPENDED -- this fix must never broadly weaken
	// SUSPENDED session ownership.
	@Test
	public void suspendedShellbane_unrelatedSlayerProgress_stillTransitionsNormally()
	{
		ActivityIdentity shellbane = ActivitySignalClassifier.bossIdentity("Shellbane Gryphon");
		Session suspended = suspendedSessionOf(shellbane, T0);

		Instant progressAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(30);
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(progressAt, "Aberrant spectres", "Slayer Tower", 1),
			suspended, new ClassifierContext());

		ActivityIdentity aberrant = ActivitySignalClassifier.slayerIdentity("Aberrant spectres", "Slayer Tower");
		assertEquals("a task Shellbane Gryphon has no affinity for is genuinely different-activity "
			+ "evidence and must still transition normally while SUSPENDED, unaffected by this fix",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(aberrant, result.getIdentity());
		assertNotEquals(shellbane, result.getIdentity());
	}

	// 3. Negative space: an unrelated SUSPENDED boss (no BossTaskAffinity
	// entry for Gryphons at all) receiving Gryphons progress must not
	// merge -- normal different-activity transition rules apply.
	@Test
	public void suspendedUnrelatedBoss_gryphonsProgress_doesNotMerge()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session suspended = suspendedSessionOf(zulrah, T0);

		Instant progressAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(30);
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(progressAt, "Gryphons", "Karuulm Slayer Dungeon", 1),
			suspended, new ClassifierContext());

		ActivityIdentity gryphons = ActivitySignalClassifier.slayerIdentity("Gryphons", "Karuulm Slayer Dungeon");
		assertEquals("Zulrah has no Gryphons affinity -- unrelated Slayer progress against an "
			+ "unrelated SUSPENDED boss must transition normally, never silently merge",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(gryphons, result.getIdentity());
		assertNotEquals(zulrah, result.getIdentity());
	}

	// 4. Generalization proof: the SAME rule (no Shellbane-specific
	// branch) also covers Grotesque Guardians/Gargoyles while SUSPENDED --
	// this is a data-driven consequence of BossTaskAffinity's existing
	// table, not a second implementation.
	@Test
	public void suspendedGrotesqueGuardians_matchingGargoylesProgress_usesSameGeneralRule()
	{
		ActivityIdentity gg = ActivitySignalClassifier.bossIdentity("Grotesque Guardians");
		Session suspended = suspendedSessionOf(gg, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		ClassifierContext context = new ClassifierContext();
		Instant progressAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(30);
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(progressAt, "Gargoyles", "Catacombs of Kourend", 1, 89),
			suspended, context);

		assertEquals("the SAME generalized boss-own-task rule -- not a Shellbane-specific branch -- "
			+ "must also cover Grotesque Guardians/Gargoyles while SUSPENDED",
			SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(gg, result.getIdentity());
		assertEquals(1, context.getPendingBossTaskCandidateMetrics().size());
	}

	// =====================================================================
	// UTILITY MAGIC XP FABRICATES A SPECIFIC SLAYER
	// SESSION WHEN A SLAYER TASK IS ASSIGNED. Live evidence: a single POH-
	// teleport MAGIC +30 XP_CHANGE, with a Slayer task merely ASSIGNED (no
	// SLAYER_TASK_PROGRESS/BOSS_KILL/other combat telemetry in the
	// window) and no compatible current session, was promoted straight
	// into a brand-new SLAYER/Gargoyles session. Root cause: MAGIC is a
	// COMBAT_SKILLS member with substantial non-combat XP sources
	// (teleports, alchemy, enchanting, other utility spellcasting), so
	// merely having a task tracked in ClassifierContext is not, by
	// itself, trustworthy proof a given Magic XP gain belongs to that
	// task. Fix: Magic XP always classifies as genericCombatIdentity(),
	// regardless of any tracked Slayer task -- see classifyXpChange()'s
	// own audit note. Cases A-G below match the live report's own
	// lettered TEST REQUIREMENTS list exactly. No spell/task/boss names
	// or XP amounts are special-cased in production -- the fix is purely
	// skill-identity-based (isMagicXpSkill()), the same style as the
	// pre-existing isSlayerXpSkill() check; these names are used here
	// only for realism and continuity with the live report.
	// =====================================================================

	// A. No current session, assigned Slayer task = Gargoyles, MAGIC +30
	// only, no corroborating Slayer/combat evidence: must NOT create
	// SLAYER/Gargoyles. (Consistent with the pre-existing, deliberately-
	// tested invariant that bare qualifying combat-skill XP with no
	// current session at all may still start the GENERIC COMBAT
	// identity -- see magicXpAlone_noCurrentSession_stillStartsGenericCombat_unaffectedByTheFix
	// and caseG below -- this fix's guarantee is specifically that no
	// SPECIFIC identity is ever fabricated from Magic alone.)
	@Test
	public void caseA_noCurrentSession_assignedSlayerTask_magicXpAlone_doesNotCreateSlayerSession()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "MAGIC", 30), null, context);

		// Per the fix above: Magic XP is now
		// METRIC_ONLY unconditionally, so it no longer even falls back to
		// genericCombatIdentity() -- it never fabricates SLAYER/Gargoyles
		// (this test's original point) NOR generic COMBAT (a stricter
		// guarantee established afterward).
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull("an assigned Slayer task must never, by itself, promote bare Magic XP into "
			+ "that task's specific SLAYER identity -- nor any identity at all",
			result.getIdentity());
	}

	// B. Expired unrelated session (modeled as no current session -- the
	// exact live-reported timing: the prior Woodcutting session's resume
	// window had already expired by the time this event was classified,
	// which decideCombatBranch()'s own null/FINALIZED fast path treats
	// identically to no session at all), assigned Slayer task =
	// Gargoyles, MAGIC +30 only: must NOT create SLAYER/Gargoyles. This
	// is the exact live bug's own reproduction.
	@Test
	public void caseB_expiredUnrelatedSession_assignedSlayerTask_magicXpAlone_doesNotCreateSlayerGargoyles()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 30), null, context);

		// Per the fix above: METRIC_ONLY unconditionally
		// -- no SLAYER/Gargoyles, and (stricter than this test originally
		// required) no generic COMBAT either.
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNotEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"),
			result.getIdentity());
		assertNull(result.getIdentity());
	}

	// C. SUSPENDED unrelated session (Woodcutting/SKILLING), assigned
	// Slayer task = Gargoyles, MAGIC +30 only: must not fabricate a
	// resume/start of SLAYER/Gargoyles.
	@Test
	public void caseC_suspendedUnrelatedSkillingSession_assignedSlayerTask_magicXpAlone_doesNotFabricateGargoylesResume()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		Session suspendedWoodcutting = suspendedSessionOf(
			new ActivityIdentity(ActivityType.SKILLING, "woodcutting", "Woodcutting"), T0);
		assertEquals(SessionState.SUSPENDED, suspendedWoodcutting.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 30), suspendedWoodcutting, context);

		// Per the fix above: METRIC_ONLY unconditionally,
		// so it no longer even switches away from the unrelated SKILLING
		// session -- the Woodcutting session stays exactly as it was, and
		// the Magic metric is simply credited to whatever is current
		// (SessionRuntimeCoordinator's own NO_EVIDENCE dispatch).
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNotEquals("an unrelated SUSPENDED SKILLING session must never be treated as fabricated "
			+ "proof of Gargoyles activity", ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"),
			result.getIdentity());
		assertNull(result.getIdentity());
	}

	// D. ACTIVE SLAYER/Gargoyles, MAGIC +30 metric-only utility XP:
	// identity remains Gargoyles, XP retained exactly once, Magic alone
	// does not independently re-prove/re-heartbeat the identity from
	// scratch -- it simply continues the already-established session via
	// the existing, unchanged "never downgrade an ACTIVE specific
	// identity" behavior.
	@Test
	public void caseD_activeSlayerGargoyles_magicUtilityXp_identityRemainsGargoylesXpRetainedOnce()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session active = sessionOf(gargoyles, T0);
		assertEquals(SessionState.ACTIVE, active.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "MAGIC", 30), active, context);

		// Per the fix above: METRIC_ONLY unconditionally
		// -- Magic no longer independently heartbeats even a matching
		// ACTIVE session. The session's own identity (untouched by a
		// METRIC_ONLY classification) still stays Gargoyles, and the XP
		// is still credited exactly once via direct aggregate application
		// (mirroring SessionRuntimeCoordinator's own NO_EVIDENCE dispatch).
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		SessionAggregateUpdater.apply(active.getAggregates(), result.getMetricUpdate());
		assertEquals(Long.valueOf(30L), active.getAggregates().getXpGainedBySkill().get("MAGIC"));
		assertEquals(SessionState.ACTIVE, active.getState());
		assertEquals(gargoyles, active.getActivityIdentity());
	}

	// E. SLAYER_TASK_PROGRESS/Gargoyles: still establishes/resumes
	// SLAYER/Gargoyles normally -- completely untouched by this fix
	// (different production code path, classifySlayerTaskProgress()).
	@Test
	public void caseE_slayerTaskProgressGargoyles_stillEstablishesSlayerGargoylesNormally()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.slayerTaskProgress(T0, "Gargoyles", "Catacombs of Kourend", 129), null, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), result.getIdentity());
	}

	// F. Legitimate established SLAYER session + Magic combat XP: Magic
	// XP must not be lost -- it aggregates to the established session
	// exactly as any other combat-branch XP would.
	@Test
	public void caseF_legitimateEstablishedSlayerSession_magicCombatXp_notLost()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(gargoyles, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		assertEquals(SessionState.SUSPENDED, engine.getCurrentSession().getState());

		// Independently trustworthy corroboration resumes the session
		// legitimately (exact-identity match, unaffected by this fix).
		Instant progressAt = T0.plusSeconds(9999);
		SignalClassification progress = classifier.classify(
			SessionSignal.slayerTaskProgress(progressAt, "Gargoyles", "Catacombs of Kourend", 1),
			engine.getCurrentSession(), new ClassifierContext());
		LifecycleResult afterProgress = engine.onQualifyingActivity(progress.getIdentity(), progressAt);
		assertEquals(SessionState.ACTIVE, afterProgress.getCurrent().getState());

		// Per the fix above: METRIC_ONLY unconditionally
		// -- Magic never independently re-heartbeats, even against a
		// legitimately-established ACTIVE Slayer session; the XP is still
		// retained via direct aggregate application.
		Instant magicAt = progressAt.plusSeconds(3);
		SignalClassification magic = classifier.classify(
			SessionSignal.xpChange(magicAt, "MAGIC", 30), engine.getCurrentSession(), new ClassifierContext());
		assertEquals(SignalDecisionKind.METRIC_ONLY, magic.getDecisionKind());
		assertNull(magic.getIdentity());
		SessionAggregateUpdater.apply(engine.getCurrentSession().getAggregates(), magic.getMetricUpdate());

		assertEquals("Magic XP must not be lost once the Slayer session is legitimately established",
			Long.valueOf(30L), engine.getCurrentSession().getAggregates().getXpGainedBySkill().get("MAGIC"));
		assertEquals(gargoyles, engine.getCurrentSession().getActivityIdentity());
	}

	// G. No assigned Slayer task, MAGIC XP alone, no current session:
	// intentionally changed by the fix below. This test
	// previously re-confirmed "invariant A" -- that bare qualifying
	// combat-skill XP with NO current session at all (Magic included)
	// still starts the GENERIC COMBAT identity -- a deliberate design
	// choice, preserved through the immediately
	// prior task specifically because that task was scoped to the
	// Slayer-task-context problem only. A later live incident proved
	// invariant A itself semantically wrong FOR MAGIC SPECIFICALLY: a
	// POH teleport's bare MAGIC +30, with genuinely no combat occurring,
	// must not create a player-facing COMBAT session either -- Magic XP
	// is a metric, never lifecycle proof, full stop, with or without a
	// tracked Slayer task. The old assumption
	// this test encoded is exactly what that fix corrects. Non-Magic
	// combat skills (Attack/Strength/Defence/Ranged/Hitpoints) are
	// unaffected and still start generic COMBAT with no session -- see
	// combatXpWithNoSlayerContextIsGenericCombat above.
	@Test
	public void caseG_noAssignedSlayerTask_magicXpAlone_noCurrentSession_noLongerStartsCombat_correctedByFinalFix()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "MAGIC", 30), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// =====================================================================
	// MAGIC XP IS A METRIC, NEVER LIFECYCLE PROOF.
	// Live evidence: with NO current session at all, a bare POH-teleport
	// MAGIC +30 XP_CHANGE (already fixed previously to never
	// fabricate a SPECIFIC Slayer identity from tracked-task context) was
	// STILL found to independently start a fresh generic COMBAT session --
	// itself false, since a teleport/alchemy/enchant/utility spell is not
	// proof of combat. Fix: MAGIC XP now classifies as METRIC_ONLY
	// unconditionally (see classifyXpChange()'s own audit note) -- it can
	// never itself start COMBAT, start SLAYER, resume a SUSPENDED
	// COMBAT/SLAYER/BOSSING session, or heartbeat/extend an ACTIVE
	// session's duration, whether or not a Slayer task is tracked. It
	// remains a valid metric throughout: credited to an already-current
	// session directly (SessionRuntimeCoordinator's existing NO_EVIDENCE
	// dispatch), or riding along in a same-batch RESOLVED outcome when
	// independently-trustworthy combat evidence (e.g. real HITPOINTS XP)
	// establishes the session. Cases A-I below match the live report's
	// own lettered TEST REQUIREMENTS list. No spell/task/boss names or XP
	// amounts are special-cased in production; ATTACK/STRENGTH/DEFENCE/
	// RANGED/HITPOINTS are unaffected and unchanged throughout.
	// =====================================================================

	// A. No current session, MAGIC +30 only: no COMBAT session, no SLAYER
	// session, nothing fabricated. (Direct end-to-end coverage below via
	// SessionRuntimeCoordinatorTest; this is the classifier-level proof.)
	@Test
	public void finalFix_caseA_noCurrentSession_magicXpOnly_noSessionFabricated()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "MAGIC", 30), null, new ClassifierContext());
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertNotNull(result.getMetricUpdate());
	}

	// B. Assigned Slayer task = Gargoyles, no current session, MAGIC +30
	// only: no SLAYER/Gargoyles, no generic COMBAT either.
	@Test
	public void finalFix_caseB_assignedSlayerTask_noCurrentSession_magicXpOnly_noSessionFabricated()
	{
		ClassifierContext context = new ClassifierContext();
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0, "MAGIC", 30), null, context);

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// C. SUSPENDED BOSSING/Zulrah, MAGIC +30 only: remains SUSPENDED, no
	// resume, lastActiveAt unchanged (end-to-end, via the real engine).
	@Test
	public void finalFix_caseC_suspendedBossingZulrah_magicXpOnly_remainsSuspendedLastActiveAtUnchanged()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(zulrah, T0);
		engine.advanceTime(T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT));
		Session suspended = engine.getCurrentSession();
		assertEquals(SessionState.SUSPENDED, suspended.getState());
		java.time.Instant lastActiveAtBefore = suspended.lastActiveAtInstant();

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 30), suspended, new ClassifierContext());
		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());

		// METRIC_ONLY: the caller applies the metric directly, never
		// calling onQualifyingActivity()/refineIdentity() -- so the
		// session's own lifecycle fields are byte-for-byte untouched.
		SessionAggregateUpdater.apply(suspended.getAggregates(), result.getMetricUpdate());
		assertEquals(SessionState.SUSPENDED, suspended.getState());
		assertEquals("lastActiveAt must never move from a METRIC_ONLY classification",
			lastActiveAtBefore, suspended.lastActiveAtInstant());
		assertEquals(zulrah, suspended.getActivityIdentity());
	}

	// D. SUSPENDED SLAYER/Gargoyles, MAGIC +30 only: remains SUSPENDED, no
	// resume.
	@Test
	public void finalFix_caseD_suspendedSlayerGargoyles_magicXpOnly_remainsSuspendedNoResume()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session suspended = suspendedSessionOf(gargoyles, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 30), suspended, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// E. SUSPENDED generic COMBAT, MAGIC +30 only: remains SUSPENDED --
	// Magic alone does not prove combat resumed, even against the
	// generic identity it would otherwise exactly match (see
	// magicXpAlone_suspendedGenericCombat_noLongerIndependentlyResumes_correctedByFinalFix
	// above for the classifier-level proof of this same case).
	@Test
	public void finalFix_caseE_suspendedGenericCombat_magicXpOnly_remainsSuspended()
	{
		ActivityIdentity generic = ActivitySignalClassifier.genericCombatIdentity();
		Session suspended = suspendedSessionOf(generic, T0);
		assertEquals(SessionState.SUSPENDED, suspended.getState());

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(9999), "MAGIC", 30), suspended, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
	}

	// F. ACTIVE BOSSING/Zulrah, MAGIC +30 only: identity remains Zulrah,
	// Magic XP credited exactly once, no independent heartbeat (see
	// magicXpAlone_activeBossingZulrah_metricOnlyNoIndependentHeartbeat
	// above for the fuller version of this same case).
	@Test
	public void finalFix_caseF_activeBossingZulrah_magicXpOnly_identityRemainsNoHeartbeat()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		Session active = sessionOf(zulrah, T0);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "MAGIC", 30), active, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertEquals(zulrah, active.getActivityIdentity());
	}

	// G. ACTIVE SLAYER/Gargoyles, MAGIC +30 only: identity remains
	// Gargoyles, XP retained exactly once, no independent heartbeat (see
	// caseD_activeSlayerGargoyles_magicUtilityXp_identityRemainsGargoylesXpRetainedOnce
	// above for the fuller version of this same case).
	@Test
	public void finalFix_caseG_activeSlayerGargoyles_magicXpOnly_identityRemainsNoHeartbeat()
	{
		ActivityIdentity gargoyles = ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend");
		Session active = sessionOf(gargoyles, T0);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(10), "MAGIC", 30), active, new ClassifierContext());

		assertEquals(SignalDecisionKind.METRIC_ONLY, result.getDecisionKind());
		assertNull(result.getIdentity());
		assertEquals(gargoyles, active.getActivityIdentity());
	}

	// H. No current session, MAGIC XP + HITPOINTS XP (trustworthy combat
	// evidence) in the SAME resolved batch: the combat lifecycle is
	// established by HITPOINTS, and Magic's metric rides along in the
	// same batch, credited to the resulting session exactly once. This
	// exercises the real SessionSignalBatchResolver + SessionLifecycleEngine
	// path (not just the classifier in isolation), since it is
	// specifically a same-batch, cross-signal interaction.
	@Test
	public void finalFix_caseH_noCurrentSession_magicPlusHitpointsSameBatch_combatEstablishedMagicCreditedOnce()
	{
		SessionSignal hitpoints = SessionSignal.xpChange(T0, "HITPOINTS", 12).withGameTick(1L);
		SessionSignal magic = SessionSignal.xpChange(T0, "MAGIC", 30).withGameTick(1L);
		java.util.List<SessionSignal> batch = java.util.Arrays.asList(hitpoints, magic);

		SessionSignalBatchResolver.BatchResolution resolution =
			SessionSignalBatchResolver.resolve(batch, null, classifier, new ClassifierContext());

		assertEquals(BatchLifecycleOutcome.RESOLVED, resolution.getOutcome());
		assertEquals(ActivitySignalClassifier.genericCombatIdentity(), resolution.getLifecycleClassification().getIdentity());
		assertEquals("Magic's metric must ride along in the same batch that establishes the session",
			2, resolution.getMetricUpdates().size());

		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		LifecycleResult result = engine.onQualifyingActivity(
			resolution.getLifecycleClassification().getIdentity(), resolution.getLifecycleTimestamp(), resolution.getMetricUpdates());
		for (MetricUpdate metric : result.getMetricsForCurrent())
		{
			SessionAggregateUpdater.apply(result.getCurrent().getAggregates(), metric);
		}

		assertEquals(Long.valueOf(12L), result.getCurrent().getAggregates().getXpGainedBySkill().get("HITPOINTS"));
		assertEquals("Magic XP must be credited to the newly-established session exactly once",
			Long.valueOf(30L), result.getCurrent().getAggregates().getXpGainedBySkill().get("MAGIC"));
	}

	// I. Established BOSSING/SLAYER/COMBAT + legitimate Magic combat
	// corroborated by qualifying evidence in the same batch: normal
	// session remains, all XP retained, no metric loss or duplication.
	@Test
	public void finalFix_caseI_establishedBossing_magicPlusHitpointsSameBatch_allXpRetainedNoDuplication()
	{
		ActivityIdentity zulrah = ActivitySignalClassifier.bossIdentity("Zulrah");
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(zulrah, T0);
		assertEquals(SessionState.ACTIVE, engine.getCurrentSession().getState());

		Instant batchAt = T0.plusSeconds(10);
		SessionSignal hitpoints = SessionSignal.xpChange(batchAt, "HITPOINTS", 8).withGameTick(2L);
		SessionSignal magic = SessionSignal.xpChange(batchAt, "MAGIC", 30).withGameTick(2L);
		java.util.List<SessionSignal> batch = java.util.Arrays.asList(hitpoints, magic);

		SessionSignalBatchResolver.BatchResolution resolution =
			SessionSignalBatchResolver.resolve(batch, engine.getCurrentSession(), classifier, new ClassifierContext());

		assertEquals(BatchLifecycleOutcome.RESOLVED, resolution.getOutcome());
		assertEquals(zulrah, resolution.getLifecycleClassification().getIdentity());
		assertEquals(2, resolution.getMetricUpdates().size());

		LifecycleResult result = engine.onQualifyingActivity(
			resolution.getLifecycleClassification().getIdentity(), resolution.getLifecycleTimestamp(), resolution.getMetricUpdates());
		for (MetricUpdate metric : result.getMetricsForCurrent())
		{
			SessionAggregateUpdater.apply(result.getCurrent().getAggregates(), metric);
		}

		assertEquals(zulrah, result.getCurrent().getActivityIdentity());
		assertEquals(SessionState.ACTIVE, result.getCurrent().getState());
		assertEquals(Long.valueOf(8L), result.getCurrent().getAggregates().getXpGainedBySkill().get("HITPOINTS"));
		assertEquals("no metric loss or duplication",
			Long.valueOf(30L), result.getCurrent().getAggregates().getXpGainedBySkill().get("MAGIC"));
	}

	// =====================================================================
	// Generic first-boss encounter context (BOSS_ACTIVITY_CONTEXT)
	// =====================================================================

	// No current session + boss activity context for a previously-
	// confirmed boss -> starts BOSSING/<boss> via decideCombatBranch(),
	// with reliableCount unaffected/null (this signal never carries one).
	@Test
	public void bossActivityContext_noCurrentSession_startsBossingSession_withNoReliableCount()
	{
		SignalClassification result = classifier.classify(
			SessionSignal.bossActivityContext(T0, "Zulrah"), null, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), result.getIdentity());
		assertNull("BOSS_ACTIVITY_CONTEXT must never itself carry a MetricUpdate/reliableCount",
			result.getMetricUpdate());
	}

	// Active SLAYER/<task> session + boss activity context for a
	// different, more-specific BOSSING identity -> per
	// decideCombatBranch()'s existing combat-branch-rank rule (BOSSING
	// rank 2 > SLAYER rank 1), this is a real switch to BOSSING/<boss>,
	// exactly as a real BOSS_KILL would already produce -- zero new
	// resolver code, same shared machinery.
	@Test
	public void bossActivityContext_activeSlayerSession_outranksAndSwitchesToBossing()
	{
		Session slayerSession = sessionOf(ActivitySignalClassifier.slayerIdentity("Gargoyles", "Catacombs of Kourend"), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.bossActivityContext(T0.plusSeconds(5), "Zulrah"), slayerSession, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Zulrah"), result.getIdentity());
		assertNotEquals(slayerSession.getActivityIdentity(), result.getIdentity());
		assertNull(result.getMetricUpdate());
	}

	// Already-active same-boss BOSSING session + repeated boss activity
	// context -> same-identity heartbeat, never regresses reliableCount
	// (no MetricUpdate is ever produced by this signal in the first
	// place, so there is nothing that could regress it).
	@Test
	public void bossActivityContext_alreadyActiveSameBoss_isHeartbeatNotRegression()
	{
		Session bossingSession = sessionOf(ActivitySignalClassifier.bossIdentity("Zulrah"), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.bossActivityContext(T0.plusSeconds(5), "Zulrah"), bossingSession, new ClassifierContext());

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(bossingSession.getActivityIdentity(), result.getIdentity());
		assertNull(result.getMetricUpdate());
	}

	// A generic COMBAT session refined into BOSSING by boss activity
	// context -- mirrors the existing COMBAT -> SLAYER/BOSSING refine
	// path classifyBossKill() already exercises via decideCombatBranch().
	@Test
	public void bossActivityContext_genericCombatSession_refinesIntoBossing()
	{
		Session combatSession = sessionOf(ActivitySignalClassifier.genericCombatIdentity(), T0);

		SignalClassification result = classifier.classify(
			SessionSignal.bossActivityContext(T0.plusSeconds(5), "Vorkath"), combatSession, new ClassifierContext());

		assertEquals(SignalDecisionKind.REFINE, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.bossIdentity("Vorkath"), result.getIdentity());
		assertNull(result.getMetricUpdate());
	}

	// Dawn/Dusk both funnel through the SAME bossIdentity() canonicalization
	// BOSS_KILL uses -- no duplicated normalization logic. Verified here at
	// the classifier level: the SessionSignal's bossName is already the
	// canonical "Grotesque Guardians" (KnownBossRegistry's job, exercised
	// separately in KnownBossRegistryTest) by the time it reaches this
	// classifier, exactly as if a real BOSS_KILL had reported it.
	@Test
	public void bossActivityContext_grotesqueGuardiansCanonicalName_matchesBossKillIdentity()
	{
		SignalClassification fromContext = classifier.classify(
			SessionSignal.bossActivityContext(T0, "Grotesque Guardians"), null, new ClassifierContext());
		SignalClassification fromKill = classifier.classify(
			SessionSignal.bossKill(T0, "Grotesque Guardians", 1), null, new ClassifierContext());

		assertEquals(fromKill.getIdentity(), fromContext.getIdentity());
	}

	// ==================================================================
	// SLAYER TASK-FAMILY MEMBERSHIP.
	// ==================================================================

	// Requirement 2/4: a genuine on-task ALTERNATIVE NPC (Flaming
	// pyrelord, Pyrefiends' own boss-form alternative per RuneLite's own
	// Task data -- see SlayerTaskFamilyRegistry) must not break an
	// established ACTIVE Slayer session, for the same reason
	// repeatedCombatXpSameSlayerTaskIsHeartbeat() above proves for the
	// unnamed generic-combat fallback.
	@Test
	public void onTaskAlternativeNpc_flamingPyrelord_doesNotBreakActiveSlayerSession()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Pyrefiends", null), T0);
		classifier.classify(SessionSignal.npcInteractionTarget(T0.plusSeconds(5), 50, "Flaming pyrelord").withGameTick(1L), current, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(6), "Attack", 40).withGameTick(1L), current, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(current.getActivityIdentity(), result.getIdentity());
		assertEquals(ActivitySignalClassifier.slayerIdentity("Pyrefiends", null), result.getIdentity());
	}

	// Requirement 1: the task's own literal singular form (RuneLite's
	// own "Pyrefiends" -> "Pyrefiend" target-name fallback) is likewise
	// on-task and must not break the session.
	@Test
	public void onTaskSingularFormNpc_pyrefiend_doesNotBreakActiveSlayerSession()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Pyrefiends", null), T0);
		classifier.classify(SessionSignal.npcInteractionTarget(T0.plusSeconds(5), 51, "Pyrefiend").withGameTick(1L), current, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(6), "Strength", 40).withGameTick(1L), current, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(current.getActivityIdentity(), result.getIdentity());
	}

	// Requirement 3/5 (classifier-level half -- the full end-to-end
	// escape-via-candidate-confirmation pipeline is exercised in
	// SessionRuntimeCoordinatorTest.offTaskCombatWhileActiveSlayer_...).
	// A genuinely OFF-TASK, specifically-named NPC is no longer silently
	// absorbed into the current Slayer identity -- it is now honestly
	// proposed as its own COMBAT identity, tagged ORDINARY (weak)
	// evidence, so SessionLifecycleEngine's existing ACTIVE-candidate
	// gate (unchanged here) can decide whether repeated evidence
	// eventually confirms a real switch.
	@Test
	public void offTaskNpc_isHonestlyProposedAsCandidate_notSilentlyAbsorbed()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Pyrefiends", null), T0);
		classifier.classify(SessionSignal.npcInteractionTarget(T0.plusSeconds(5), 60, "Goat").withGameTick(1L), current, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(6), "Attack", 40).withGameTick(1L), current, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), result.getIdentity());
		assertNotEquals(current.getActivityIdentity(), result.getIdentity());
		assertEquals(EvidenceStrength.ORDINARY, result.getEvidenceStrength());
	}

	// Requirement 7: Dawn/Dusk being registered Gargoyles task-family
	// members must never flatten an already-established
	// BOSSING/Grotesque-Guardians session back into SLAYER/Gargoyles --
	// this gate is scoped EXACTLY to a SLAYER current identity (see
	// decideCombatBranch()'s own comment) and is never even consulted
	// when current is BOSSING; BossTaskAffinity remains the sole
	// mechanism governing boss precedence here, completely untouched.
	@Test
	public void gargoylesDawnDuskFamilyMembership_doesNotRegressGrotesqueGuardiansBossPrecedence()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		classifier.classify(SessionSignal.npcInteractionTarget(T0.plusSeconds(5), 70, "Dawn").withGameTick(1L), current, context);

		SignalClassification result = classifier.classify(
			SessionSignal.xpChange(T0.plusSeconds(6), "Attack", 40).withGameTick(1L), current, context);

		assertEquals(SignalDecisionKind.START_OR_HEARTBEAT, result.getDecisionKind());
		assertEquals(current.getActivityIdentity(), result.getIdentity());
		assertEquals(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), result.getIdentity());
	}

	// ==================================================================
	// SLAYER TASK-COMPLETION-AGGREGATION --
	// a reproducible SLAYER_TASK_COMPLETED remaining=1 bug.
	// ==================================================================

	// Requirement 8: progress 2 -> 1, then completion (with no
	// intervening 1 -> 0 PROGRESS event, exactly as observed live)
	// produces remaining=0 and the correct total accumulated units.
	@Test
	public void slayerTaskCompletedAfterRemainingOne_reconcilesFinalUnitAndZeroesRemaining()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Pyrefiends", null), T0);

		SignalClassification progress = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Pyrefiends", null, 1, 1), current, context);
		SessionAggregateUpdater.apply(current.getAggregates(), progress.getMetricUpdate());
		assertEquals(Integer.valueOf(1), current.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(1), current.getAggregates().getLatestSlayerCurrentRemaining());

		SignalClassification completion = classifier.classify(
			SessionSignal.slayerTaskCompleted(T0.plusSeconds(10), "Pyrefiends", null), current, context);
		assertEquals(SignalDecisionKind.COMPLETION_ONLY, completion.getDecisionKind());
		SessionAggregateUpdater.apply(current.getAggregates(), completion.getMetricUpdate());

		assertEquals("the final, never-separately-reported task unit must be accounted for exactly once",
			Integer.valueOf(2), current.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(0), current.getAggregates().getLatestSlayerCurrentRemaining());
	}

	// Requirement 9: a normal progress event that already reached 0
	// must never be double-counted by the subsequent completion event.
	@Test
	public void slayerTaskCompletedAfterRemainingAlreadyZero_doesNotDoubleCount()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.slayerIdentity("Pyrefiends", null), T0);

		SignalClassification progress = classifier.classify(
			SessionSignal.slayerTaskProgress(T0.plusSeconds(5), "Pyrefiends", null, 1, 0), current, context);
		SessionAggregateUpdater.apply(current.getAggregates(), progress.getMetricUpdate());
		assertEquals(Integer.valueOf(0), current.getAggregates().getLatestSlayerCurrentRemaining());

		SignalClassification completion = classifier.classify(
			SessionSignal.slayerTaskCompleted(T0.plusSeconds(10), "Pyrefiends", null), current, context);
		SessionAggregateUpdater.apply(current.getAggregates(), completion.getMetricUpdate());

		assertEquals("completion must add ZERO once a normal progress event already reached 0 -- no double-counting",
			Integer.valueOf(1), current.getAggregates().getSlayerProgressDelta());
		assertEquals(Integer.valueOf(0), current.getAggregates().getLatestSlayerCurrentRemaining());
	}

	// Reliability bound: a SLAYER_TASK_COMPLETED that does not reliably
	// match the current session/task (here: current is BOSSING,
	// mirroring a boss self-consuming its own Slayer task -- see
	// BossTaskAffinity) must never force remaining/delta onto a session
	// it cannot be reliably shown to belong to.
	@Test
	public void slayerTaskCompletedWithNoReliableMatchingSession_emitsNoMetric()
	{
		ClassifierContext context = new ClassifierContext();
		Session current = sessionOf(ActivitySignalClassifier.bossIdentity("Grotesque Guardians"), T0);
		context.onSlayerTaskAssigned("Gargoyles", "Catacombs of Kourend");

		SignalClassification completion = classifier.classify(
			SessionSignal.slayerTaskCompleted(T0.plusSeconds(10), "Gargoyles", null), current, context);

		assertNull(completion.getMetricUpdate());
	}
}
