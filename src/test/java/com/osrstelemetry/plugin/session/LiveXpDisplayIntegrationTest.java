package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Full-pipeline coverage proving SessionRuntimeCoordinator.
 * recordLiveXpDelta() -&gt; LiveXpTracker -&gt; getCurrentSessionSnapshot() -&gt;
 * CurrentSessionSnapshot.from() actually closes the player-visible
 * latency gap end to end, and that the reconciliation with the real,
 * durable XP_CHANGE path (via testEnqueueSignal()/testProcessTick(),
 * exactly like GenericNpcCombatRecognitionTest's own pipeline tests)
 * never double-counts. Deliberately kept in its own file, mirroring
 * GenericNpcCombatRecognitionTest's own precedent, so these
 * changes are reviewable independently of the large, already-green
 * SessionRuntimeCoordinatorTest suite.
 *
 * No mocking anywhere (this project has no Mockito dependency) -- every
 * test drives the real SessionRuntimeCoordinator/SessionLifecycleEngine/
 * CurrentSessionSnapshot classes directly.
 */
public class LiveXpDisplayIntegrationTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
	private static final long ACCOUNT_LIVE_XP_TEST = 701_100_200L;
	private static final long ACCOUNT_LIVE_XP_TEST_B = 701_100_201L;

	private LocalStateStore store;
	private SessionRuntimeCoordinator coordinator;

	@Before
	public void setUp() throws Exception
	{
		deleteAccountDir(ACCOUNT_LIVE_XP_TEST);
		deleteAccountDir(ACCOUNT_LIVE_XP_TEST_B);
		store = new LocalStateStore();
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		deleteAccountDir(ACCOUNT_LIVE_XP_TEST);
		deleteAccountDir(ACCOUNT_LIVE_XP_TEST_B);
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

	// 1: a live XP delta updates the Current Session snapshot
	// immediately, before any XP_CHANGE has ever been processed.
	@Test
	public void liveXpDelta_showsImmediatelyInSnapshot_beforeXpChangeExists()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 601, "Goat").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusMillis(600));

		// A COMBAT/Goat session now exists (established by the raw
		// lifecycle path) with zero durable XP yet -- exactly the
		// scenario the live XP layer exists to improve on.
		assertEquals(ActivitySignalClassifier.genericNpcCombatIdentity("Goat"), coordinator.testCurrentSession().getActivityIdentity());
		assertTrue(coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().isEmpty());

		coordinator.recordLiveXpDelta("STRENGTH", 40L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(70));
		assertEquals(1, snapshot.getXpEntries().size());
		assertEquals("STRENGTH", snapshot.getXpEntries().get(0).getSkill());
		assertEquals(40L, snapshot.getXpEntries().get(0).getXpGained());
	}

	// 2: multiple live gains for the same skill accumulate correctly in the display.
	@Test
	public void multipleLiveGains_accumulateInTheDisplayedTotal()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 601, "Goat").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusMillis(600));

		coordinator.recordLiveXpDelta("STRENGTH", 40L);
		coordinator.recordLiveXpDelta("STRENGTH", 15L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(2));
		assertEquals(55L, snapshot.getXpEntries().get(0).getXpGained());
	}

	// 3: a later real XP_CHANGE covering the same underlying gains does
	// NOT double the displayed total -- the T0/T1/T2 worked example
	// (canonical 0 -> +40 live shows 40 -> XP_CHANGE flushes +40,
	// canonical becomes 40, display MUST remain 40, never 80).
	@Test
	public void laterXpChange_reconciles_neverDoublesTheDisplayedTotal()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 601, "Goat").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusMillis(600));

		coordinator.recordLiveXpDelta("STRENGTH", 40L);
		CurrentSessionSnapshot beforeFlush = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(2));
		assertEquals(40L, beforeFlush.getXpEntries().get(0).getXpGained());

		// The same +40 now arrives via the real, durable XP_CHANGE path.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(30), "STRENGTH", 40).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(30));

		assertEquals(Long.valueOf(40L), coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().get("STRENGTH"));

		CurrentSessionSnapshot afterFlush = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(31));
		assertEquals("the displayed total must remain 40, never jump to 80", 40L, afterFlush.getXpEntries().get(0).getXpGained());
	}

	// 4/5: works identically for a non-combat skill (Woodcutting) and a
	// combat skill (Strength) in the same session's own snapshot.
	@Test
	public void liveXp_worksForNonCombatAndCombatSkillsTogether()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 601, "Goat").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusMillis(600));

		coordinator.recordLiveXpDelta("STRENGTH", 40L);
		coordinator.recordLiveXpDelta("WOODCUTTING", 25L);

		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(2));
		assertEquals(2, snapshot.getXpEntries().size());
		long woodcutting = snapshot.getXpEntries().stream()
			.filter(e -> e.getSkill().equals("WOODCUTTING")).findFirst().get().getXpGained();
		long strength = snapshot.getXpEntries().stream()
			.filter(e -> e.getSkill().equals("STRENGTH")).findFirst().get().getXpGained();
		assertEquals(25L, woodcutting);
		assertEquals(40L, strength);
	}

	// 6: a genuine session switch must not carry the old session's
	// pending live XP into the new one's own display.
	@Test
	public void sessionSwitch_doesNotLeakOldSessionsLiveXpIntoTheNewOne()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);

		// Establish a first session (via the existing, unrelated boss-kill
		// lifecycle path -- Woodcutting has no dedicated lifecycle signal
		// in this codebase, but a genuine session-identity switch is a
		// session-identity switch regardless of activity type: this
		// exercises the SAME sessionId-keyed discard rule LiveXpTracker
		// implements -- see LiveXpTrackerTest for the isolated,
		// lower-level proof of the exact mechanism) and give it +100 live XP.
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 5).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String firstSessionId = coordinator.testCurrentSession().getSessionId();
		coordinator.recordLiveXpDelta("RANGED", 100L);
		assertEquals(100L, CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(2))
			.getXpEntries().get(0).getXpGained());

		// A genuinely different boss kill starts a brand-new session (a
		// real switch, not a heartbeat) -- its own live XP must start
		// from zero, never inheriting the old session's +100.
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0.plusSeconds(10), "Vorkath", 3).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(10));
		String secondSessionId = coordinator.testCurrentSession().getSessionId();

		assertFalse("this must be a genuinely different session for this test to be meaningful",
			firstSessionId.equals(secondSessionId));

		CurrentSessionSnapshot afterSwitch = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(11));
		assertTrue("the new session must never display the old session's own live-pending XP",
			afterSwitch.getXpEntries().isEmpty());
	}

	// 7: a delayed XP_CHANGE for the OLD (abandoned) session, arriving
	// after a switch, must not cause the NEW current session's own live
	// display to double-count or otherwise disturb it.
	@Test
	public void delayedXpChangeAfterSwitch_doesNotDisturbTheNewSessionsLiveDisplay()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 5).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.recordLiveXpDelta("RANGED", 100L);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0.plusSeconds(10), "Vorkath", 3).withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(10));
		coordinator.recordLiveXpDelta("MAGIC", 60L);

		CurrentSessionSnapshot beforeDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(11));
		assertEquals(60L, beforeDelayed.getXpEntries().get(0).getXpGained());

		// A stray, very-delayed XP_CHANGE for the OLD Zulrah fight lands
		// now, well after the switch to Vorkath. Its windowStart (T0) is
		// when SkillsCollector actually opened this XP-collection window
		// for the Zulrah fight -- well before Vorkath's own startedAt
		// (T0+10) -- which is exactly the timing information production
		// code (SessionEventMapper, fed by EventPayloads.XpChange's own
		// windowStart) will now actually supply. This is what the new
		// ownership guard in ActivitySignalClassifier.classifyXpChange()
		// keys off of to correctly ignore this stale, misattributed event.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(40), "RANGED", 100, T0).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(40));

		CurrentSessionSnapshot afterDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(41));
		// The current (Vorkath) session's own live Magic XP must be
		// completely unaffected by the old session's own delayed flush.
		assertEquals(1, afterDelayed.getXpEntries().size());
		assertEquals("MAGIC", afterDelayed.getXpEntries().get(0).getSkill());
		assertEquals(60L, afterDelayed.getXpEntries().get(0).getXpGained());
	}

	// 9: an account switch resets the live layer -- no fake live XP
	// carries over into a freshly-loaded account.
	@Test
	public void accountSwitch_resetsLiveXp_noFakeLiveXpCarriesOver()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 5).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.recordLiveXpDelta("RANGED", 100L);
		assertEquals(100L, CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(1))
			.getXpEntries().get(0).getXpGained());

		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST_B);
		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Vorkath", 1).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);

		CurrentSessionSnapshot freshAccount = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(1));
		assertTrue("a freshly-loaded account must never inherit another account's live-pending XP",
			freshAccount.getXpEntries().isEmpty());
	}

	// 10: XP/hr uses the immediate (durable + live) total -- see
	// CurrentSessionSnapshotTest.xpPerHour_reflectsTheImmediateLiveTotal_notJustDurable()
	// for the focused, lower-level proof (this full-pipeline file
	// intentionally does not re-derive an hour of real accumulated
	// active duration through raw game-tick pulses, which would risk
	// crossing SessionLifecycleEngine's own SUSPEND_TIMEOUT and testing
	// the wrong thing).

	// Delayed durable-XP
	// session-ownership fix. A delayed XP_CHANGE whose own Slayer
	// task's session has already been left behind by a genuine
	// SLAYER_TASK_PROGRESS-driven task switch (the Pyrefiends ->
	// Banshees real-world scenario from the bug report: finish
	// Pyrefiends, accept/start a new Banshees task, kill zero
	// Banshees, THEN the old Pyrefiend fight's own delayed XP_CHANGE
	// finally arrives) must never be credited to the new Banshees
	// session -- neither its durable aggregates nor its own live
	// display, which must remain exactly what recordLiveXpDelta()
	// established for Banshees alone.
	@Test
	public void delayedXpAfterSlayerTaskSwitch_doesNotContaminateTheNewTasksSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);

		// Establish a SLAYER/Pyrefiends session (same-tick combat XP +
		// SLAYER_TASK_PROGRESS resolves directly to SLAYER, exactly like
		// SessionRuntimeCoordinatorTest.slayerProgressAndCombatXpSameTick_resolvesDirectlyToSlayer()).
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0, "STRENGTH", 50L).withGameTick(1L));
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0, "Pyrefiends", "Catacombs of Kourend", 50).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		String pyrefiendsSessionId = coordinator.testCurrentSession().getSessionId();
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// A genuinely different task name is direct, SPECIFIC evidence
		// that immediately switches to a brand-new SLAYER/Banshees
		// session -- see classifySlayerTaskProgress()'s own javadoc.
		coordinator.testEnqueueSignal(SessionSignal.slayerTaskProgress(T0.plusSeconds(10), "Banshees", "Slayer Tower", 1).withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(10));
		String bansheesSessionId = coordinator.testCurrentSession().getSessionId();
		assertFalse("this must be a genuinely different session for this test to be meaningful",
			pyrefiendsSessionId.equals(bansheesSessionId));
		assertEquals(ActivityType.SLAYER, coordinator.testCurrentSession().getActivityIdentity().getActivityType());

		// Zero Banshees killed yet -- only its own live XP (Hitpoints
		// from some other incidental source) has been recorded so far.
		coordinator.recordLiveXpDelta("HITPOINTS", 20L);
		CurrentSessionSnapshot beforeDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(11));
		assertEquals(1, beforeDelayed.getXpEntries().size());
		assertEquals("HITPOINTS", beforeDelayed.getXpEntries().get(0).getSkill());
		assertEquals(20L, beforeDelayed.getXpEntries().get(0).getXpGained());

		// The completed Pyrefiend fight's own delayed XP_CHANGE finally
		// arrives, well after the switch to Banshees. Its windowStart
		// (T0) is when that XP was actually earned -- during the
		// Pyrefiends fight, before Banshees's own startedAt (T0+10).
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(40), "STRENGTH", 50, T0).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(40));

		// Banshees's own durable aggregates must never have gained this
		// stale Strength XP.
		assertTrue("the new Slayer task's session must never absorb the old task's own delayed XP",
			coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().isEmpty());

		// Banshees's own live display must remain exactly as it was --
		// only its own Hitpoints gain, never the old Pyrefiends fight's
		// Strength.
		CurrentSessionSnapshot afterDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(41));
		assertEquals(1, afterDelayed.getXpEntries().size());
		assertEquals("HITPOINTS", afterDelayed.getXpEntries().get(0).getSkill());
		assertEquals(20L, afterDelayed.getXpEntries().get(0).getXpGained());
	}

	// Now that SessionEventMapper always
	// threads a real windowStart through for every ordinary XP_CHANGE
	// (previously discarded; see SessionSignal's own xpWindowStart
	// javadoc), a normal, GENUINELY same-session XP_CHANGE -- one whose
	// windowStart falls AFTER (not before) the current session's own
	// startedAt -- must still reconcile exactly as before: the new
	// ownership guard in ActivitySignalClassifier.classifyXpChange() must
	// never suppress legitimate, in-session XP just because it now
	// happens to carry a non-null windowStart.
	// XP_CHANGE window/session-boundary
	// split fix. The general case the guard above was too coarse for:
	// an XP_CHANGE window that SPANS the switch (opens under Zulrah,
	// closes/flushes after Vorkath is already current) must credit
	// EXACTLY the portion earned after the switch to Vorkath -- never
	// the pre-switch portion, never a proportional guess -- and that
	// credit must reconcile Vorkath's own live-pending RANGED exactly
	// once (never causing it to later disappear or double). Also proves
	// a SUBSEQUANT, wholly-normal (non-spanning) flush for the same
	// skill still reconciles correctly afterward.
	@Test
	public void spanningXpChangeWindow_creditsOnlyThePostSwitchPortion_andReconcilesLiveXpExactlyOnce()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 5).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		// RANGED was already at 1000 XP during the Zulrah fight -- this
		// is exactly the absolute value SessionRuntimeCoordinator will
		// snapshot as Vorkath's own "XP at session start" baseline the
		// moment the switch below happens.
		coordinator.noteAbsoluteXp("RANGED", 1000L);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0.plusSeconds(10), "Vorkath", 3).withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(10));

		// +25 RANGED genuinely earned under Vorkath, via the live layer.
		coordinator.recordLiveXpDelta("RANGED", 25L);
		coordinator.noteAbsoluteXp("RANGED", 1025L);

		CurrentSessionSnapshot beforeDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(11));
		assertEquals(1, beforeDelayed.getXpEntries().size());
		assertEquals(25L, beforeDelayed.getXpEntries().get(0).getXpGained());

		// The delayed flush's own window spans the switch: it opened at
		// T0 (during Zulrah, previousXp=900 implied by delta=125) and
		// didn't close until well after Vorkath took over, ending at the
		// true current absolute value of 1025. Of that window's own
		// reported delta (125 = 1025 - 900), only 25 (1025 - the 1000
		// baseline captured at the switch) was actually earned under
		// Vorkath -- the other 100 belongs to the already-finalized
		// Zulrah session and must never reach Vorkath.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(40), "RANGED", 125, T0, 1025L).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(40));

		assertEquals("only the exact post-switch portion may ever reach Vorkath's own durable aggregates",
			Long.valueOf(25L), coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().get("RANGED"));

		CurrentSessionSnapshot afterDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(41));
		assertEquals(1, afterDelayed.getXpEntries().size());
		assertEquals("the durable credit must reconcile the live-pending amount exactly once, never leaving it to double and never disappearing",
			25L, afterDelayed.getXpEntries().get(0).getXpGained());

		// A SUBSEQUENT, wholly-normal (non-spanning -- windowStart is
		// now after Vorkath's own startedAt) flush for more RANGED XP
		// earned entirely within Vorkath must still reconcile exactly
		// once, completely unaffected by the earlier spanning-window fix.
		coordinator.recordLiveXpDelta("RANGED", 10L);
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(70), "RANGED", 10, T0.plusSeconds(41), 1035L).withGameTick(4L));
		coordinator.testProcessTick(5L, T0.plusSeconds(70));

		assertEquals(Long.valueOf(35L), coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().get("RANGED"));
		CurrentSessionSnapshot afterSecondFlush = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(71));
		assertEquals(35L, afterSecondFlush.getXpEntries().get(0).getXpGained());
	}

	// Case A (wholly stale), proven this
	// time WITH an exact baseline present (not merely the "no baseline
	// available" fallback) -- the entire window's XP was earned at or
	// before the switch (newXp == the switch's own baseline), so the
	// post-switch portion is exactly zero and nothing may be credited to
	// Vorkath.
	@Test
	public void spanningXpChangeWindow_whollyStaleEvenWithExactBaseline_creditsNothingToTheNewSession()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0, "Zulrah", 5).withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.noteAbsoluteXp("RANGED", 1000L);

		coordinator.testEnqueueSignal(SessionSignal.bossKill(T0.plusSeconds(10), "Vorkath", 3).withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusSeconds(10));

		// No RANGED gain at all under Vorkath -- the delayed window's own
		// newXp (1000) exactly equals the baseline captured at the
		// switch, so the post-switch portion is zero.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(40), "RANGED", 100, T0, 1000L).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(40));

		assertTrue("a wholly-stale spanning window must credit nothing to the new session, even with an exact baseline",
			coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().isEmpty());
		CurrentSessionSnapshot afterDelayed = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(41));
		assertTrue(afterDelayed.getXpEntries().isEmpty());
	}

	@Test
	public void sameSessionXpChange_withPopulatedWindowStart_stillReconcilesNormally()
	{
		coordinator.ensureAccountLoaded(ACCOUNT_LIVE_XP_TEST);
		coordinator.testEnqueueSignal(SessionSignal.npcInteractionTarget(T0, 601, "Goat").withGameTick(1L));
		coordinator.testProcessTick(2L, T0);
		coordinator.testEnqueueSignal(SessionSignal.rawCombatXpObserved(T0.plusMillis(600), "Strength").withGameTick(2L));
		coordinator.testProcessTick(3L, T0.plusMillis(600));

		coordinator.recordLiveXpDelta("STRENGTH", 40L);
		CurrentSessionSnapshot beforeFlush = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(2));
		assertEquals(40L, beforeFlush.getXpEntries().get(0).getXpGained());

		// The same +40 arrives via the real, durable XP_CHANGE path, this
		// time WITH a windowStart -- one that (correctly, for a
		// genuinely same-session gain) falls after this session's own
		// startedAt (T0), so the new ownership guard must not fire.
		coordinator.testEnqueueSignal(SessionSignal.xpChange(T0.plusSeconds(30), "STRENGTH", 40, T0.plusSeconds(1)).withGameTick(3L));
		coordinator.testProcessTick(4L, T0.plusSeconds(30));

		assertEquals(Long.valueOf(40L), coordinator.testCurrentSession().getAggregates().getXpGainedBySkill().get("STRENGTH"));

		CurrentSessionSnapshot afterFlush = CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), T0.plusSeconds(31));
		assertEquals("a genuinely same-session windowStart must never trigger the new ownership guard",
			40L, afterFlush.getXpEntries().get(0).getXpGained());
	}
}
