package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.activity.RecognitionState;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.time.Instant;
import java.util.Collections;
import org.junit.Test;

/**
 * Generic structured-activity framework, end to end through the real
 * SessionRuntimeCoordinator (batching, classifier, lifecycle engine,
 * aggregates, persistence). Guardians of the Rift (single EXCLUSIVE region)
 * and Barrows (STRONG_CONTEXT surface) stand in for "any" activity: nothing
 * asserted here depends on which activity it is.
 */
public class StructuredActivityFrameworkTest extends StructuredActivityTestSupport
{
	// ------------------------------------------------------------------ 1-5: recognition

	@Test
	public void t01_locationArmsButDoesNotStart()
	{
		onTick(T0, enter(GOTR, GOTR_REGION, T0));
		for (int i = 1; i <= 20; i++)
		{
			coordinator.testProcessTick(++tick, T0.plusSeconds(i));
		}
		assertNull("location alone never creates a session", current());
		assertEquals(RecognitionState.ARMED, recognition());
	}

	@Test
	public void t02_leavingArmedLocationWithoutActivity_noSession()
	{
		onTick(T0, enter(GOTR, GOTR_REGION, T0));
		onTick(T0.plusSeconds(30), leave(T0.plusSeconds(30)));
		assertNull(current());
		assertEquals(RecognitionState.OUTSIDE, recognition());
		// Expected-skill XP after leaving is ordinary skilling, not the activity.
		onTick(T0.plusSeconds(40), xpAt("MINING", 50, T0.plusSeconds(40)));
		assertNotNull(current());
		assertEquals(ActivityType.SKILLING, current().getActivityIdentity().getActivityType());
	}

	@Test
	public void t03_firstCompatibleEvidenceActivates()
	{
		onTick(T0, enter(GOTR, GOTR_REGION, T0));
		onTick(T0.plusSeconds(5), xpAt("MINING", 50, T0.plusSeconds(5)));
		assertTrue("first compatible evidence starts the activity at once", isGotr(current()));
		assertEquals("Guardians of the Rift", current().getActivityIdentity().getDisplayName());
		assertEquals(SessionState.ACTIVE, current().getState());
		assertEquals(RecognitionState.ACTIVE, recognition());
		assertEquals(Long.valueOf(50), current().getAggregates().getXpGainedBySkill().get("MINING"));
	}

	@Test
	public void t04_distinctConfirmationsLock()
	{
		onTick(T0, enter(GOTR, GOTR_REGION, T0));
		onTick(T0.plusSeconds(5), xpAt("MINING", 50, T0.plusSeconds(5)));
		assertEquals(RecognitionState.ACTIVE, recognition());
		onTick(T0.plusSeconds(10), xpAt("MINING", 50, T0.plusSeconds(10)));
		assertEquals(RecognitionState.ACTIVE, recognition());
		onTick(T0.plusSeconds(15), xpAt("RUNECRAFT", 200, T0.plusSeconds(15)));
		assertEquals(RecognitionState.LOCKED, recognition());
	}

	@Test
	public void t05_sameTickDuplicatesDoNotOverCount()
	{
		onTick(T0, enter(GOTR, GOTR_REGION, T0));
		Instant t = T0.plusSeconds(5);
		onTick(t, xpAt("MINING", 50, t), xpAt("CRAFTING", 10, t), xpAt("RUNECRAFT", 90, t), gotrGame(10, t));
		assertEquals("four signals on one tick are one confirmation", 1, classifierContext().getStructuredActivity().getDistinctConfirmations());
		assertEquals(RecognitionState.ACTIVE, recognition());
		Instant t2 = t.plusSeconds(5);
		onTick(t2, xpAt("MINING", 50, t2), xpAt("MINING", 5, t2));
		assertEquals(RecognitionState.ACTIVE, recognition());
		assertEquals(2, classifierContext().getStructuredActivity().getDistinctConfirmations());
	}

	// ------------------------------------------------------------------ 6-9: ownership

	@Test
	public void t06_expectedXpDoesNotFragmentALockedActivity()
	{
		String id = lockedGotr(T0).getSessionId();
		// Several minutes of alternating support skills: one session throughout.
		for (int i = 0; i < 18; i++)
		{
			Instant t = T0.plusSeconds(40 + i * 10L);
			onTick(t, xpAt(i % 3 == 0 ? "MINING" : i % 3 == 1 ? "CRAFTING" : "RUNECRAFT", 25, t));
			assertEquals(id, current().getSessionId());
		}
		// Unexpected XP inside the EXCLUSIVE location is incidental, never a challenger.
		for (int i = 0; i < 12; i++)
		{
			Instant t = T0.plusSeconds(240 + i * 15L);
			onTick(t, xpAt("AGILITY", 12, t));
		}
		assertEquals(id, current().getSessionId());
		assertTrue(isGotr(current()));
		assertEquals("incidental XP is still credited to the activity", Long.valueOf(144),
			current().getAggregates().getXpGainedBySkill().get("AGILITY"));
	}

	@Test
	public void t06b_strongContext_unexpectedXpIsAChallengerUntilLocked() throws Exception
	{
		// ACTIVE (one confirmation): sustained unrelated XP can still take over.
		onTick(T0, enter(BARROWS, BARROWS_SURFACE, T0));
		onTick(T0.plusSeconds(5), xpAt("ATTACK", 40, T0.plusSeconds(5)));
		assertEquals(RecognitionState.ACTIVE, recognition());
		String activeId = current().getSessionId();
		for (int i = 1; i <= 12; i++)
		{
			Instant t = T0.plusSeconds(5 + 10L * i);
			onTick(t, xpAt("COOKING", 10, t));
		}
		assertNotEquals("STRONG_CONTEXT before LOCKED does not absorb a genuine challenger", activeId, current().getSessionId());

		// LOCKED: the same unrelated XP is incidental and credited to the activity.
		tearDownCoordinator();
		setUpCoordinator();
		onTick(T0, enter(BARROWS, BARROWS_SURFACE, T0));
		onTick(T0.plusSeconds(5), xpAt("ATTACK", 40, T0.plusSeconds(5)));
		onTick(T0.plusSeconds(15), xpAt("STRENGTH", 40, T0.plusSeconds(15)));
		onTick(T0.plusSeconds(25), xpAt("HITPOINTS", 15, T0.plusSeconds(25)));
		assertEquals(RecognitionState.LOCKED, recognition());
		String lockedId = current().getSessionId();
		for (int i = 1; i <= 12; i++)
		{
			Instant t = T0.plusSeconds(25 + 10L * i);
			onTick(t, xpAt("COOKING", 10, t));
		}
		assertEquals(lockedId, current().getSessionId());
		assertEquals(BARROWS, current().getActivityIdentity().getActivityKey());
		assertEquals(Long.valueOf(120), current().getAggregates().getXpGainedBySkill().get("COOKING"));
	}

	@Test
	public void t07_subordinateNpcDoesNotSwitchIdentity()
	{
		onTick(T0, enter(BARROWS, 14231, T0));
		onTick(T0.plusSeconds(5), xpAt("MAGIC", 60, T0.plusSeconds(5)));
		String id = current().getSessionId();
		onTick(T0.plusSeconds(6), SessionSignal.bossActivityContext(T0.plusSeconds(6), "Dharok the Wretched"),
			SessionSignal.npcInteractionTarget(T0.plusSeconds(6), 1673, "Dharok the Wretched"));
		onTick(T0.plusSeconds(8), xpAt("MAGIC", 60, T0.plusSeconds(8)), xpAt("HITPOINTS", 20, T0.plusSeconds(8)));
		SessionSignal raw = SessionSignal.rawCombatXpObserved(T0.plusSeconds(9), "HITPOINTS");
		onTick(T0.plusSeconds(9), raw);
		assertEquals(id, current().getSessionId());
		assertEquals(ActivityType.STRUCTURED_ACTIVITY, current().getActivityIdentity().getActivityType());
		assertEquals(BARROWS, current().getActivityIdentity().getActivityKey());
	}

	@Test
	public void t08_leavingRemovesLocationReinforcement()
	{
		String id = lockedGotr(T0).getSessionId();
		Instant out = T0.plusSeconds(60);
		onTick(out, leave(out));
		assertEquals(RecognitionState.OUTSIDE, recognition());
		// The first ordinary Mining observation outside is only a challenger...
		onTick(out.plusSeconds(10), xpAt("MINING", 30, out.plusSeconds(10)));
		assertEquals(id, current().getSessionId());
		// ...and sustained Mining elsewhere takes over through the normal gate.
		for (int i = 2; i <= 12; i++)
		{
			Instant t = out.plusSeconds(10L * i);
			onTick(t, xpAt("MINING", 30, t));
		}
		assertNotEquals("outside the location nothing reinforces the activity", id, current().getSessionId());
		assertEquals(ActivityType.SKILLING, current().getActivityIdentity().getActivityType());
	}

	@Test
	public void t09_genuineUnrelatedActivityCanTakeOver()
	{
		String id = lockedGotr(T0).getSessionId();
		// Inside the location: an authoritative, unrelated boss kill still wins.
		onTick(T0.plusSeconds(40), SessionSignal.bossKill(T0.plusSeconds(40), "Zulrah", 10));
		assertNotEquals(id, current().getSessionId());
		assertEquals(ActivityType.BOSSING, current().getActivityIdentity().getActivityType());
	}

	// ------------------------------------------------------------------ 10-12: recovery / restart

	@Test
	public void t10_interruptedRecovery_activityThenBriefSlayerThenActivity_resumesOriginal()
	{
		String gotrId = lockedGotr(T0).getSessionId();
		Instant out = T0.plusSeconds(60);
		onTick(out, leave(out));
		onTick(out.plusSeconds(10), SessionSignal.slayerTaskProgress(out.plusSeconds(10), NECH, NECH_LOC, 1, 99));
		assertEquals(ActivityType.SLAYER, current().getActivityIdentity().getActivityType());
		assertEquals(gotrId, coordinator.testInterruptedCandidate().getSessionId());

		Instant back = out.plusSeconds(90);
		onTick(back, enter(GOTR, GOTR_REGION, back));
		onTick(back.plusSeconds(5), xpAt("MINING", 40, back.plusSeconds(5)));
		assertEquals("A -> brief B -> A resumes the ORIGINAL activity session", gotrId, current().getSessionId());
	}

	@Test
	public void t10b_slayerThenBriefActivityThenSlayer_resumesSlayer()
	{
		onTick(T0, SessionSignal.slayerTaskAssigned(T0, NECH, NECH_LOC, 100));
		onTick(T0.plusSeconds(10), SessionSignal.slayerTaskProgress(T0.plusSeconds(10), NECH, NECH_LOC, 1, 99));
		String slayerId = current().getSessionId();
		Instant in = T0.plusSeconds(30);
		onTick(in, enter(GOTR, GOTR_REGION, in));
		onTick(in.plusSeconds(5), xpAt("MINING", 40, in.plusSeconds(5)));
		assertTrue(isGotr(current()));
		onTick(in.plusSeconds(40), leave(in.plusSeconds(40)));
		onTick(in.plusSeconds(60), SessionSignal.slayerTaskProgress(in.plusSeconds(60), NECH, NECH_LOC, 1, 98));
		assertEquals(slayerId, current().getSessionId());
	}

	@Test
	public void t11_restartReArmsSafely()
	{
		Instant base = Instant.now().minusSeconds(240);
		String id = lockedGotr(base).getSessionId();
		restartPlugin();
		assertEquals("restart starts OUTSIDE", RecognitionState.OUTSIDE, recognition());
		Session restored = current();
		assertNotNull(restored);
		long activeBefore = restored.getAccumulatedActiveDurationMillis();
		SessionState stateBefore = restored.getState();

		Instant after = base.plusSeconds(90);
		onTick(after, enter(GOTR, GOTR_REGION, after));
		assertEquals(RecognitionState.ARMED, recognition());
		assertEquals("re-arming alone changes nothing", stateBefore, current().getState());
		assertEquals(activeBefore, current().getAccumulatedActiveDurationMillis());

		onTick(after.plusSeconds(5), xpAt("MINING", 30, after.plusSeconds(5)));
		assertEquals("real evidence resumes the same activity session", id, current().getSessionId());
		assertEquals(SessionState.ACTIVE, current().getState());
	}

	@Test
	public void t12_locationAloneAfterLogin_fabricatesNothing()
	{
		restartPlugin();
		for (int i = 0; i < 10; i++)
		{
			Instant t = T0.plusSeconds(i * 10L);
			onTick(t, enter(GOTR, GOTR_REGION, t));
		}
		assertNull(current());
		assertNull(coordinator.testInterruptedCandidate());
	}

	// ------------------------------------------------------------------ 13-17: metric / rounds / history

	@Test
	public void t13_completionMetricIncrements()
	{
		lockedGotr(T0);
		onTick(T0.plusSeconds(40), gotrGame(100, T0.plusSeconds(40)));
		onTick(T0.plusSeconds(50), gotrGame(101, T0.plusSeconds(50)));
		SessionAggregates.ActivityCompletionCount c = current().getAggregates().getActivityCompletions();
		assertEquals(GOTR, c.getActivityKey());
		assertEquals(2, c.getSessionCompletions());
		assertEquals(Integer.valueOf(101), c.getLatestAuthoritativeValue());
	}

	@Test
	public void t13b_completionForAnotherActivity_orOutside_isNeverFabricated()
	{
		onTick(T0, gotrGame(5, T0));
		assertNull("a completion with no location and no session creates nothing", current());
		lockedGotr(T0.plusSeconds(10));
		onTick(T0.plusSeconds(60), SessionSignal.structuredActivityCompletion(T0.plusSeconds(60), "pest_control", null));
		assertNull(current().getAggregates().getActivityCompletions());
	}

	@Test
	public void t14_multipleRoundsStayOneSession()
	{
		String id = lockedGotr(T0).getSessionId();
		Instant t = T0.plusSeconds(40);
		for (int game = 1; game <= 4; game++)
		{
			// Lobby wait between games stays well inside the suspend timeout.
			for (int i = 0; i < 6; i++)
			{
				t = t.plusSeconds(20);
				onTick(t, xpAt(i % 2 == 0 ? "MINING" : "RUNECRAFT", 40, t));
			}
			t = t.plusSeconds(5);
			onTick(t, gotrGame(200 + game, t));
			t = t.plusSeconds(90);
		}
		assertEquals(id, current().getSessionId());
		assertEquals(4, current().getAggregates().getActivityCompletions().getSessionCompletions());
	}

	@Test
	public void t15_activeDurationExcludesSuspendedTime()
	{
		String id = lockedGotr(T0).getSessionId();
		Instant last = T0.plusSeconds(30);
		// Idle in the lobby past the suspend timeout.
		coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1));
		assertEquals(SessionState.SUSPENDED, current().getState());
		Instant back = last.plusSeconds(20 * 60);
		onTick(back, xpAt("MINING", 40, back));
		assertEquals(id, current().getSessionId());
		assertEquals(SessionState.ACTIVE, current().getState());
		long active = SessionRuntimeCoordinator.activeDurationAt(current(), back);
		assertTrue("20 idle minutes are not active time (was " + active + "ms)",
			active < SessionLifecycleEngine.SUSPEND_TIMEOUT.toMillis() + 60_000L);
	}

	@Test
	public void t16_finalizedHistoryPreservesCompletionMetric()
	{
		Instant base = Instant.now().minusSeconds(3 * 3600);
		Session live = lockedGotr(base);
		onTick(base.plusSeconds(40), gotrGame(300, base.plusSeconds(40)));
		onTick(base.plusSeconds(200), gotrGame(301, base.plusSeconds(200)));
		onTick(base.plusSeconds(210), xpAt("RUNECRAFT", 100, base.plusSeconds(210)));
		Instant last = base.plusSeconds(210);
		coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT).plusSeconds(1));
		coordinator.testProcessTick(++tick, last.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT)
			.plus(SessionLifecycleEngine.RESUME_WINDOW).plusSeconds(5));
		assertNull(current());
		store.shutdown();
		store = new LocalStateStore(GSON);
		store.start();
		Session loaded = new SessionPersistence(store).loadFinalized(ACCOUNT, live.getSessionId());
		assertNotNull(loaded);
		assertEquals(ActivityType.STRUCTURED_ACTIVITY, loaded.getActivityIdentity().getActivityType());
		assertEquals(2, loaded.getAggregates().getActivityCompletions().getSessionCompletions());
		assertEquals(Integer.valueOf(301), loaded.getAggregates().getActivityCompletions().getLatestAuthoritativeValue());
		HistoryEntry entry = HistoryEntry.from(loaded);
		assertEquals(Integer.valueOf(2), entry.getActivityCompletionCount());
		assertEquals("Guardians of the Rift", entry.getActivityIdentity().getDisplayName());
	}

	@Test
	public void t17_oldJsonLoads_withoutAnyStructuredFields()
	{
		SessionAggregates old = GSON.fromJson("{\"xpGainedBySkill\":{\"MINING\":5},\"lootDrops\":[]}", SessionAggregates.class);
		assertNull(old.getActivityCompletions());
		Session legacy = GSON.fromJson("{\"sessionId\":\"legacy\",\"activityIdentity\":{\"activityType\":\"SKILLING\","
			+ "\"activityKey\":\"mining\",\"displayName\":\"Mining\"},\"state\":\"FINALIZED\","
			+ "\"startedAt\":\"2026-01-01T10:00:00Z\",\"lastActiveAt\":\"2026-01-01T10:30:00Z\","
			+ "\"finalizedAt\":\"2026-01-01T11:00:00Z\",\"accumulatedActiveDurationMillis\":1800000,"
			+ "\"aggregates\":{\"xpGainedBySkill\":{\"MINING\":5000}}}", Session.class);
		HistoryEntry entry = HistoryEntry.from(legacy);
		assertNotNull(entry);
		assertNull("non-structured sessions carry no completion metric", entry.getActivityCompletionCount());
		assertNull(SessionSnapshot.capture(legacy).getActivityCompletions());
		// Mapper: the generic events round-trip through the normal event mapper.
		assertEquals(SignalKind.STRUCTURED_ACTIVITY_LOCATION, SessionEventMapper.map(
			com.osrstelemetry.plugin.events.EventType.STRUCTURED_ACTIVITY_STATE,
			new com.osrstelemetry.plugin.events.EventPayloads.StructuredActivityState(GOTR, "EXCLUSIVE", GOTR_REGION), T0).getKind());
		assertEquals(Integer.valueOf(7), SessionEventMapper.map(
			com.osrstelemetry.plugin.events.EventType.ACTIVITY_COMPLETION,
			new com.osrstelemetry.plugin.events.EventPayloads.ActivityCompletion("barrows_chest", "Barrows chest", 7, "AUTHORITATIVE"), T0)
			.getReliableCount());
		SessionSignal reward = SessionEventMapper.map(com.osrstelemetry.plugin.events.EventType.STRUCTURED_REWARD,
			new com.osrstelemetry.plugin.events.EventPayloads.StructuredReward(GOTR, "guardians_of_the_rift", "Guardians of the Rift",
				Collections.singletonList(new com.osrstelemetry.plugin.events.EventPayloads.StructuredRewardItem(565, "Blood rune", 40))), T0);
		assertEquals(SignalKind.STRUCTURED_REWARD, reward.getKind());
		assertEquals(GOTR, reward.getStructuredActivityKey());
		assertEquals("canonical reward-source key", "guardians_of_the_rift", reward.getBossOrActivitySource());
		// An event from the earlier build (no sourceKey, RuneLite's event name) canonicalizes via its alias.
		SessionSignal legacyReward = SessionEventMapper.map(com.osrstelemetry.plugin.events.EventType.STRUCTURED_REWARD,
			GSON.fromJson("{\"activityKey\":\"tempoross\",\"sourceName\":\"Reward pool (Tempoross)\",\"items\":[]}",
				com.osrstelemetry.plugin.events.EventPayloads.StructuredReward.class), T0);
		assertEquals("tempoross_reward_pool", legacyReward.getBossOrActivitySource());
	}
}
