package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.collectors.SlayerCollector.SlayerBaselineHydration;

import com.osrstelemetry.plugin.collectors.SlayerCollector.SlayerTransitionResult;
import com.osrstelemetry.plugin.collectors.SlayerCollector.SlayerTransitionResult.Kind;
import com.osrstelemetry.plugin.model.SlayerState;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-network caveat as elsewhere in this
 * test module (see plugin/README.md). Targets
 * SlayerCollector.evaluateSlayerTransition(), the pure Client/
 * EventLedger-independent core of the transition decision — factored
 * out for the same reason QuestCollector.justFinished() and
 * SkillsCollector.evaluateXpWindow()/evaluateLevelTransition() were:
 * unit-testable without mocking RuneLite's Client. SlayerState has no
 * Client dependency (plain @Data model), so it's built directly here,
 * per your "no new mocking dependencies" instruction.
 *
 * Task IDENTITY
 * (taskName/taskLocation) is explicitly separate from task
 * PROGRESS/METADATA (amountRemaining, initialAmount, points, streak,
 * streakLabel, streakSourceVerified, masterId). Every test below
 * exercises SlayerCollector.evaluateSlayerTransition() directly — the
 * exact function emitTransitionEvents() calls — never a separate
 * approximate copy of its decision logic.
 */
public class SlayerCollectorTest
{
	private static SlayerState task(String name, String location, int remaining)
	{
		SlayerState state = new SlayerState();
		state.setTaskName(name);
		state.setTaskLocation(location);
		state.setAmountRemaining(remaining);
		return state;
	}

	private static SlayerState noTask()
	{
		return task(null, null, 0);
	}

	// --- 1. null baseline + existing task -> silent baseline, no event ---

	@Test
	public void scenario1_nullBaselineWithExistingTask_silentBaseline()
	{
		// Required semantics: LOGIN -> existing Gargoyles task silently
		// becomes baseline -> NO SLAYER_TASK_ASSIGNED event. previous ==
		// null (the state right after captureInitialStateWithoutBaseline()
		// leaves lastKnown untouched) must never be diffed as "no task ->
		// Gargoyles."
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(null, current);
		assertEquals(Kind.NONE, result.kind);
	}

	@Test
	public void scenario1b_nullBaselineWithNoTask_silentBaseline()
	{
		// Same null-baseline guard for the "no task assigned at login"
		// case — must not fabricate ASSIGNED just because amountRemaining
		// transitions from "unobserved" to 0.
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(null, noTask());
		assertEquals(Kind.NONE, result.kind);
	}

	// --- 2. no task -> Gargoyles -> ASSIGNED ---

	@Test
	public void scenario2_noTaskToGargoyles_assigned()
	{
		SlayerState previous = noTask();
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.ASSIGNED, result.kind);
	}

	// --- 3. Gargoyles 138 -> 137 -> PROGRESS only, delta=-1, consumed=1 ---

	@Test
	public void scenario3_gargoyles138to137_progressOnly()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		SlayerState current = task("Gargoyles", "Wilderness", 137);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.PROGRESS, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
		assertEquals(138, result.previousRemaining);
		assertEquals(137, result.currentRemaining);
		assertEquals(-1, result.remainingDelta);
		assertEquals(1, result.taskUnitsConsumed);
	}

	// --- 4. Gargoyles 138 -> 136 -> one PROGRESS, delta=-2, consumed=2 ---

	@Test
	public void scenario4_gargoyles138to136_progressOnly()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		SlayerState current = task("Gargoyles", "Wilderness", 136);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.PROGRESS, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
		assertEquals(-2, result.remainingDelta);
		assertEquals(2, result.taskUnitsConsumed);
	}

	// --- 5. Gargoyles 138 -> 138 -> NONE ---

	@Test
	public void scenario5_gargoyles138to138_none()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.NONE, result.kind);
	}

	// --- 6/7/8/9. same task/count, only progress-metadata fields change -> NONE, never ASSIGNED ---

	@Test
	public void scenario6_sameTask_pointsChangeOnly_none()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		previous.setPoints(500);
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		current.setPoints(505);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.NONE, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
	}

	@Test
	public void scenario7_sameTask_streakChangeOnly_none()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		previous.setStreak(40);
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		current.setStreak(41);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.NONE, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
	}

	@Test
	public void scenario8_sameTask_initialAmountChangeOnly_none()
	{
		// initialAmount changing with everything else fixed shouldn't
		// occur in real play, but identity/progress classification must
		// not be sensitive to it either way — it is explicitly
		// progress/metadata, not identity.
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		previous.setInitialAmount(140);
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		current.setInitialAmount(150);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.NONE, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
	}

	@Test
	public void scenario9_sameTaskAndCount_unrelatedMetadataChanges_none()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		previous.setMasterId(7);
		previous.setStreakLabel("WILDERNESS");
		previous.setStreakSourceVerified(true);
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		current.setMasterId(9);
		current.setStreakLabel("NORMAL");
		current.setStreakSourceVerified(false);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.NONE, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
	}

	// --- 10. Gargoyles -> no task -> existing COMPLETED behavior ---

	@Test
	public void scenario10_gargoylesToNoTask_completed()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 1);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, noTask());
		assertEquals(Kind.COMPLETED, result.kind);
	}

	// --- 11. Gargoyles -> different task -> ASSIGNED, never PROGRESS ---

	@Test
	public void scenario11_gargoylesToDifferentTask_assignedNotProgress()
	{
		// "Gargoyles 1 remaining -> Nechryaels 160 remaining is NOT +159
		// Slayer progress. It is a task lifecycle change." — even though
		// both previous and current have a nonzero remaining amount
		// (e.g. the old task's last kill also completed it and a new one
		// was handed out in the same settle window), a changed identity
		// must always win over any remaining-count arithmetic.
		SlayerState previous = task("Gargoyles", "Wilderness", 1);
		SlayerState current = task("Nechryaels", "Slayer Tower", 160);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.ASSIGNED, result.kind);
		assertNotEquals(Kind.PROGRESS, result.kind);
	}

	// --- 12. same task, remaining count increasing -> PROGRESS, positive delta, never ASSIGNED ---

	@Test
	public void scenario12_sameTaskRemainingIncreasing_progressPositiveDelta()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 137);
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.PROGRESS, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
		assertEquals(1, result.remainingDelta);
		assertEquals(0, result.taskUnitsConsumed);
	}

	// --- 13. same task identity, very large decrement -> PROGRESS, never ASSIGNED ---

	@Test
	public void scenario13_sameTaskLargeDecrement_progress()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 138);
		SlayerState current = task("Gargoyles", "Wilderness", 120);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.PROGRESS, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
		assertEquals(-18, result.remainingDelta);
		assertEquals(18, result.taskUnitsConsumed);
	}

	// --- 14. same task identity, only amountRemaining changes across several values -> never ASSIGNED ---

	@Test
	public void scenario14_sameTaskIdentity_onlyAmountRemainingVaries_neverAssigned()
	{
		int[][] transitions = {
			{138, 137}, {200, 1}, {50, 50}, {1, 2}, {999, 1}, {5, 999},
		};
		for (int[] transition : transitions)
		{
			SlayerState previous = task("Gargoyles", "Wilderness", transition[0]);
			SlayerState current = task("Gargoyles", "Wilderness", transition[1]);
			SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
			assertNotEquals(
				"remaining " + transition[0] + " -> " + transition[1] + " must never be ASSIGNED",
				Kind.ASSIGNED, result.kind);
		}
	}

	// --- Explicit regression invariant for the confirmed live bug ---

	@Test
	public void regressionInvariant_identicalTaskIdentityWithOnlyAmountRemainingChange_neverAssigned()
	{
		// THE primary regression boundary: for identical taskName +
		// taskLocation, changing only amountRemaining must NEVER return
		// SLAYER_TASK_ASSIGNED, in either direction, across a wide range
		// of magnitudes including the exact values from the live bug
		// report (138 -> 137) and the original pass-#2 regression
		// (129 -> 128).
		String name = "Gargoyles";
		String location = "Wilderness";
		int[][] transitions = {
			{129, 128}, {138, 137}, {138, 136}, {138, 120}, {137, 138},
			{1, 0}, {0, 1}, {1000, 1}, {1, 1000},
		};
		for (int[] transition : transitions)
		{
			SlayerState previous = task(name, location, transition[0]);
			SlayerState current = task(name, location, transition[1]);
			SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
			assertNotEquals(
				"identical identity, remaining " + transition[0] + " -> " + transition[1]
					+ " must never fabricate SLAYER_TASK_ASSIGNED",
				Kind.ASSIGNED, result.kind);
		}
	}

	// --- Pre-existing behavior, re-pinned against the new result type ---

	@Test
	public void normalDecrementDoesNotFabricateAnAssignment()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 129);
		SlayerState current = task("Gargoyles", "Wilderness", 128);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.PROGRESS, result.kind);
	}

	@Test
	public void noChangeAtAllEmitsNothing()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 129);
		SlayerState current = task("Gargoyles", "Wilderness", 129);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.NONE, result.kind);
	}

	// =====================================================================
	// Baseline-hydration lifecycle tests (letters A-I), including an
	// explicit regression test for the
	// reproduced stabilization-hole race. Root cause:
	// captureInitialStateWithoutBaseline()'s own synchronous login-time
	// read was discarded for baseline purposes entirely, leaving
	// hydration to whichever async, VarbitChanged-triggered
	// performCoalescedCheck() fired first -- which could itself race
	// RuneLite's own SlayerPlugin and transiently observe "no task"
	// before the real task settled. A confirmed follow-up hole:
	// treating that first async "no task" reading as automatically
	// final still allowed sync=no-task, first-async=no-task,
	// next=Gargoyles/124 to fabricate ASSIGNED. These tests exercise
	// the pre-existing pure evaluateSlayerTransition() contract
	// (unchanged, still the ultimate authority once hydrated) AND
	// SlayerBaselineHydration -- the extracted, Client-independent
	// state machine captureInitialStateWithoutBaseline()/
	// performCoalescedCheck()/onGameTick() all actually delegate to in
	// production -- per this project's "no Mockito" convention of
	// testing the exact core, not an approximation.
	// =====================================================================

	// --- A. startup first observation has existing task -> state written, NO ASSIGNED ---

	@Test
	public void baselineA_startupFirstObservationHasExistingTask_noAssignedEvent()
	{
		SlayerState current = task("Gargoyles", "Wilderness", 124);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(null, current);
		assertEquals("an existing task discovered on first observation must never fabricate ASSIGNED", Kind.NONE, result.kind);

		// THE actual fix: a synchronous read that already shows a real
		// task hydrates immediately -- a real task can never be a sync
		// artifact -- so hydration closes right there instead of
		// depending on a later, race-prone async read.
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		boolean hydratedNow = hydration.observeBeforeHydration(current);
		assertTrue("a real task observed before hydration must lock immediately", hydratedNow);
		assertTrue(hydration.isHydrated());
		assertEquals("Gargoyles", hydration.getLastKnown().getTaskName());
	}

	// --- B. startup first observation has no task -> baseline established, no event ---

	@Test
	public void baselineB_startupFirstObservationHasNoTask_baselineEstablishedNoEvent()
	{
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(null, noTask());
		assertEquals(Kind.NONE, result.kind);

		// A "no task" observation must NOT lock hydration immediately
		// -- this is exactly the confirmed hole above. It only arms the
		// stabilization countdown.
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		boolean hydratedNow = hydration.observeBeforeHydration(noTask());
		assertFalse("a no-task observation must never lock hydration immediately", hydratedNow);
		assertFalse(hydration.isHydrated());

		// It DOES eventually become the trusted baseline once the
		// stabilization window elapses undisturbed (see baselineJ for
		// the full tick-by-tick confirmation).
		for (int i = 0; i < SlayerBaselineHydration.NONE_STABILIZATION_TICKS; i++)
		{
			hydration.onGameTick();
		}
		assertTrue("no task, left undisturbed for the full stabilization window, must eventually confirm", hydration.isHydrated());
		assertNull(hydration.getLastKnown().getTaskName());
	}

	// --- C. after baseline NONE, genuine new task appears -> exactly one ASSIGNED ---

	@Test
	public void baselineC_afterNoneBaseline_genuineNewTaskAppears_exactlyOneAssigned()
	{
		SlayerState previous = noTask();
		SlayerState current = task("Gargoyles", "Wilderness", 138);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.ASSIGNED, result.kind);
	}

	// --- D. after baseline task A, task B appears -> exactly one ASSIGNED for B ---

	@Test
	public void baselineD_afterTaskABaseline_taskBAppears_exactlyOneAssignedForB()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 1);
		SlayerState current = task("Nechryaels", "Slayer Tower", 160);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.ASSIGNED, result.kind);
		assertEquals("Nechryaels", current.getTaskName());
	}

	// --- E. same identity amount decrement -> PROGRESS, no ASSIGNED ---

	@Test
	public void baselineE_sameIdentityAmountDecrement_progressNotAssigned()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 124);
		SlayerState current = task("Gargoyles", "Wilderness", 123);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertEquals(Kind.PROGRESS, result.kind);
		assertNotEquals(Kind.ASSIGNED, result.kind);
	}

	// --- F. same identity 0 -> 1 -> no ASSIGNED (do not regress the 0->1 bug) ---

	@Test
	public void baselineF_sameIdentityZeroToOne_neverAssigned()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 0);
		SlayerState current = task("Gargoyles", "Wilderness", 1);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertNotEquals("identical task identity crossing 0 -> 1 must never fabricate ASSIGNED", Kind.ASSIGNED, result.kind);
		assertEquals(Kind.PROGRESS, result.kind);
	}

	// --- G. task disappears after established baseline -> COMPLETED ---

	@Test
	public void baselineG_taskDisappearsAfterEstablishedBaseline_completed()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 1);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, noTask());
		assertEquals(Kind.COMPLETED, result.kind);
	}

	// --- H. account switch to an account already on a task -> silent hydration, no fabricated assignment ---

	@Test
	public void baselineH_accountSwitchToAccountAlreadyOnTask_silentHydrationNoFabrication()
	{
		// resetForAccountSwitch() calls hydration.reset() before the new
		// account's first observation -- a fresh SlayerBaselineHydration
		// models exactly that. A real task observed right away (as here)
		// locks immediately, closing the race window for the new
		// account exactly as it does for a plain login.
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		SlayerState newAccountCurrent = task("Aberrant spectres", "Slayer Tower", 87);

		SlayerTransitionResult proxy = SlayerCollector.evaluateSlayerTransition(null, newAccountCurrent);
		assertEquals("switching into an account already on a task must never fabricate ASSIGNED", Kind.NONE, proxy.kind);

		boolean hydratedNow = hydration.observeBeforeHydration(newAccountCurrent);
		assertTrue(hydratedNow);
		assertEquals("Aberrant spectres", hydration.getLastKnown().getTaskName());
	}

	// --- I. disable/re-enable while already on a task -> no fabricated assignment ---

	@Test
	public void baselineI_disableReenableWhileAlreadyOnTask_noFabricatedAssignment()
	{
		// seedSilently() (the re-enable path) calls
		// hydration.hydrateDirectly() -- bypasses the stabilization
		// window entirely and sets the baseline directly, with no call
		// to evaluateSlayerTransition()/emitTransitionEvents() at all.
		// By construction, no event can be fabricated for the task that
		// was already running while the category was disabled.
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		SlayerState reseeded = task("Gargoyles", "Wilderness", 124);
		hydration.hydrateDirectly(reseeded);

		assertTrue(hydration.isHydrated());
		assertEquals("Gargoyles", hydration.getLastKnown().getTaskName());

		// The very next observation (identical state) must diff to
		// NONE, confirming the reseeded baseline is not immediately
		// stale/mismatched against the real current state.
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(hydration.getLastKnown(), reseeded);
		assertEquals(Kind.NONE, result.kind);
	}

	// --- J: THE reproduced race, explicitly -- sync NONE,
	// first async NONE, later settled Gargoyles/124 -> Gargoyles becomes
	// current persisted state, NO SLAYER_TASK_ASSIGNED ---

	@Test
	public void baselineJ_reproducedRace_syncNone_asyncNone_thenSettledTask_noFabricatedAssignment()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();

		// 1. Synchronous login-time read: no task (transient/unsettled).
		boolean lockedAfterSync = hydration.observeBeforeHydration(noTask());
		assertFalse(lockedAfterSync);
		assertFalse(hydration.isHydrated());

		// 2. First async, VarbitChanged-triggered check: STILL no task
		// (the exact reproduced race -- the original fix wrongly treated
		// this as automatically final).
		boolean lockedAfterFirstAsync = hydration.observeBeforeHydration(noTask());
		assertFalse(lockedAfterFirstAsync);
		assertFalse("a second consecutive no-task observation must still not lock hydration", hydration.isHydrated());

		// 3. Later, settled observation: the real, already-existing
		// task. Must hydrate SILENTLY -- there is structurally no event
		// to fabricate here, since observeBeforeHydration() never calls
		// evaluateSlayerTransition()/emitTransitionEvents() at all.
		boolean lockedAfterSettle = hydration.observeBeforeHydration(task("Gargoyles", "Wilderness", 124));
		assertTrue("the real task must hydrate immediately once observed, however many no-task reads preceded it", lockedAfterSettle);
		assertTrue(hydration.isHydrated());
		assertEquals("Gargoyles becomes the current persisted/baseline state", "Gargoyles", hydration.getLastKnown().getTaskName());
		assertEquals(124, hydration.getLastKnown().getAmountRemaining());
	}

	// --- K: sync NONE, settled NONE baseline, later
	// genuinely assigned Gargoyles -> exactly one SLAYER_TASK_ASSIGNED ---

	@Test
	public void baselineK_settledNoneBaseline_thenGenuineAssignment_exactlyOneAssigned()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();

		// 1. Synchronous login-time read: no task.
		hydration.observeBeforeHydration(noTask());
		assertFalse(hydration.isHydrated());

		// 2. Left completely undisturbed for the full stabilization
		// window (no further relevant VarbitChanged at all -- a
		// genuinely no-task account) -- NONE must be confirmed.
		boolean confirmedOnFinalTick = false;
		for (int i = 0; i < SlayerBaselineHydration.NONE_STABILIZATION_TICKS; i++)
		{
			boolean confirmedThisTick = hydration.onGameTick();
			if (i < SlayerBaselineHydration.NONE_STABILIZATION_TICKS - 1)
			{
				assertFalse("must not confirm before the full stabilization window elapses", confirmedThisTick);
			}
			else
			{
				confirmedOnFinalTick = confirmedThisTick;
			}
		}
		assertTrue("NONE must be confirmed exactly on the tick the stabilization window completes", confirmedOnFinalTick);
		assertTrue(hydration.isHydrated());
		assertNull(hydration.getLastKnown().getTaskName());

		// 3. A LATER, genuine assignment -- now a normal post-hydration
		// diff against a trustworthy, confirmed NONE baseline -- must
		// still produce exactly one SLAYER_TASK_ASSIGNED.
		SlayerState assigned = task("Gargoyles", "Wilderness", 124);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(hydration.getLastKnown(), assigned);
		assertEquals(Kind.ASSIGNED, result.kind);
	}

	// --- L: a no-task observation arriving mid-countdown restarts the
	// stabilization window rather than counting toward it, and does not
	// itself prematurely confirm NONE ---

	@Test
	public void baselineL_repeatedNoTaskObservationsDuringCountdown_restartTheWindow()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();

		hydration.observeBeforeHydration(noTask());
		hydration.onGameTick(); // one tick closer to confirming...
		assertFalse(hydration.isHydrated());

		// ...but another relevant varbit fires, still showing no task --
		// this must restart the countdown, not count as progress toward
		// confirmation.
		hydration.observeBeforeHydration(noTask());

		// One tick less than the FULL window (post-restart) must still
		// not be enough to confirm.
		for (int i = 0; i < SlayerBaselineHydration.NONE_STABILIZATION_TICKS - 1; i++)
		{
			assertFalse(hydration.onGameTick());
		}
		assertFalse("the restarted window must not have already elapsed", hydration.isHydrated());

		// The final tick of the restarted window does confirm it.
		assertTrue(hydration.onGameTick());
		assertTrue(hydration.isHydrated());
	}

	// =====================================================================
	// World-hop observation-window tests (letters A-H) --
	// guarding against staying on the exact same
	// Gargoyles/124 task across a single same-account world hop (no
	// completion, no new task, no interaction at all) produced a
	// fabricated SLAYER_TASK_COMPLETED immediately followed by a
	// fabricated SLAYER_TASK_ASSIGNED for the same task. Root cause:
	// OsrsTelemetryPlugin.handleLogin() only called
	// slayerCollector.resetForAccountSwitch() inside its
	// accountContext.isRealSwitch() branch -- a world hop fires the
	// exact same GameStateChanged(LOGGED_IN)/handleLogin() path but is
	// NOT a "real switch" (accountHash is unchanged), so hydration was
	// left untouched across the hop, carrying the OLD trusted baseline
	// through a period where RuneLite's Slayer state can transiently
	// blank out. Fixed by calling
	// slayerCollector.resetForAccountSwitch() UNCONDITIONALLY on every
	// login-type transition (see OsrsTelemetryPlugin.handleLogin()'s
	// own comment) -- reusing the exact same SlayerBaselineHydration
	// machinery already proven correct for a fresh client launch,
	// rather than a second suppression system. These tests model that
	// exact sequence -- reset() (the hop's re-opened window) followed
	// by the observations RuneLite would deliver across and after it --
	// against SlayerBaselineHydration directly, the same Client-
	// independent core the orchestration change delegates to.
	// =====================================================================

	// --- A. hydrated Gargoyles -> hop gap -> transient NONE -> Gargoyles/124: no COMPLETED, no ASSIGNED ---

	@Test
	public void worldHopA_hydratedTask_hopGap_transientNone_thenSameTask_noCompletedNoAssigned()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		// Before the hop: already hydrated on Gargoyles/124.
		hydration.hydrateDirectly(task("Gargoyles", "Wilderness", 124));
		assertTrue(hydration.isHydrated());

		// The hop itself: OsrsTelemetryPlugin.handleLogin() now calls
		// resetForAccountSwitch() unconditionally, re-opening a fresh
		// hydration window -- modeled directly here.
		hydration.reset();
		assertFalse(hydration.isHydrated());

		// Mid-hop transient read: Slayer state blanks out momentarily.
		boolean hydratedOnTransientNone = hydration.observeBeforeHydration(noTask());
		assertFalse("a transient no-task reading during the hop gap must never lock in as COMPLETED", hydratedOnTransientNone);
		assertFalse(hydration.isHydrated());

		// Post-hop settle: the same task is still there.
		boolean hydratedOnSettle = hydration.observeBeforeHydration(task("Gargoyles", "Wilderness", 124));
		assertTrue(hydratedOnSettle);
		assertTrue(hydration.isHydrated());
		assertEquals("Gargoyles", hydration.getLastKnown().getTaskName());
		assertEquals(124, hydration.getLastKnown().getAmountRemaining());
		// No COMPLETED, no ASSIGNED: observeBeforeHydration() never
		// calls evaluateSlayerTransition()/emits an event on either
		// call above -- there is structurally nothing to fabricate.
	}

	// --- B. hop gap -> first post-hop read NONE -> later Gargoyles/124 before stabilization: no events ---

	@Test
	public void worldHopB_hopGap_firstPostHopReadNone_thenSameTaskBeforeStabilization_noEvents()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		hydration.hydrateDirectly(task("Gargoyles", "Wilderness", 124));

		hydration.reset();
		// First post-hop async check: still no task (the exact
		// round-2 race, now recurring at a hop instead of at login).
		assertFalse(hydration.observeBeforeHydration(noTask()));
		assertFalse(hydration.isHydrated());

		// Real settle arrives before the NONE stabilization window
		// would ever have elapsed.
		assertTrue(hydration.observeBeforeHydration(task("Gargoyles", "Wilderness", 124)));
		assertTrue(hydration.isHydrated());
		assertEquals("Gargoyles", hydration.getLastKnown().getTaskName());
	}

	// --- C. hop gap -> Gargoyles/124 immediately (no transient NONE at all): no events ---

	@Test
	public void worldHopC_hopGap_sameTaskImmediately_noEvents()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		hydration.hydrateDirectly(task("Gargoyles", "Wilderness", 124));

		hydration.reset();
		boolean hydratedNow = hydration.observeBeforeHydration(task("Gargoyles", "Wilderness", 124));
		assertTrue(hydratedNow);
		assertTrue(hydration.isHydrated());
		assertEquals(124, hydration.getLastKnown().getAmountRemaining());
	}

	// --- D. world hop while genuinely having no task -> rehydrate NONE after stabilization, no events ---

	@Test
	public void worldHopD_genuinelyNoTaskAcrossHop_rehydratesNoneAfterStabilization_noEvents()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		hydration.hydrateDirectly(noTask());

		hydration.reset();
		assertFalse(hydration.observeBeforeHydration(noTask()));
		assertFalse(hydration.isHydrated());

		boolean confirmed = false;
		for (int i = 0; i < SlayerBaselineHydration.NONE_STABILIZATION_TICKS; i++)
		{
			confirmed = hydration.onGameTick();
		}
		assertTrue("genuinely no task, left undisturbed across the hop, must eventually re-confirm NONE", confirmed);
		assertTrue(hydration.isHydrated());
		assertNull(hydration.getLastKnown().getTaskName());
	}

	// --- E. after post-hop NONE hydration completes, a genuine new assignment -> exactly one ASSIGNED ---

	@Test
	public void worldHopE_postHopNoneHydrationComplete_thenGenuineAssignment_exactlyOneAssigned()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		hydration.reset();
		hydration.observeBeforeHydration(noTask());
		for (int i = 0; i < SlayerBaselineHydration.NONE_STABILIZATION_TICKS; i++)
		{
			hydration.onGameTick();
		}
		assertTrue(hydration.isHydrated());

		// Requirement 5: a trustworthy NONE baseline must NOT globally
		// suppress a real subsequent assignment during stable gameplay.
		SlayerState assigned = task("Gargoyles", "Wilderness", 138);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(hydration.getLastKnown(), assigned);
		assertEquals(Kind.ASSIGNED, result.kind);
	}

	// --- F. after post-hop Gargoyles hydration completes, a genuine completion during stable gameplay -> exactly one COMPLETED ---

	@Test
	public void worldHopF_postHopTaskHydrationComplete_thenGenuineCompletion_exactlyOneCompleted()
	{
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		hydration.reset();
		hydration.observeBeforeHydration(task("Gargoyles", "Wilderness", 1));
		assertTrue(hydration.isHydrated());

		// Requirement 4: hydration must NOT globally suppress a genuine
		// observed completion during stable gameplay after the hop.
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(hydration.getLastKnown(), noTask());
		assertEquals(Kind.COMPLETED, result.kind);
	}

	// --- G. existing startup empty->empty->task race regression remains passing ---

	@Test
	public void worldHopG_startupEmptyEmptyTaskRaceRegression_stillPasses()
	{
		// Re-asserts exactly baselineJ's scenario -- the world-hop fix
		// only changed WHEN hydration is reset (an additional
		// unconditional reset on every login/hop in
		// OsrsTelemetryPlugin), not the hydration decision rule itself
		// (SlayerBaselineHydration.observeBeforeHydration()/onGameTick()
		// are untouched here), so the original startup race fix
		// must still hold.
		SlayerBaselineHydration hydration = new SlayerBaselineHydration();
		assertFalse(hydration.observeBeforeHydration(noTask()));
		assertFalse(hydration.observeBeforeHydration(noTask()));
		assertTrue(hydration.observeBeforeHydration(task("Gargoyles", "Wilderness", 124)));
		assertTrue(hydration.isHydrated());
		assertEquals("Gargoyles", hydration.getLastKnown().getTaskName());
	}

	// --- H. existing same-task 0->1 regression remains passing ---

	@Test
	public void worldHopH_sameTaskZeroToOneRegression_stillPasses()
	{
		SlayerState previous = task("Gargoyles", "Wilderness", 0);
		SlayerState current = task("Gargoyles", "Wilderness", 1);
		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(previous, current);
		assertNotEquals(Kind.ASSIGNED, result.kind);
		assertEquals(Kind.PROGRESS, result.kind);
	}

	// =====================================================================
	// A retest showed the invokeAtTickEnd() fix above was
	// insufficient: telemetry was STILL exactly one decrement
	// behind after switching from invokeLater() to invokeAtTickEnd().
	//
	// Root cause (confirmed against RuneLite's own current
	// net.runelite.client.callback.Hooks source, which is what actually
	// calls clientThread.invoke()/invokeTickEnd() -- not ClientThread in
	// isolation):
	//   Hooks.tick():    eventBus.post(GAME_TICK); clientThread.invoke();
	//   Hooks.tickEnd(): clientThread.invokeTickEnd(); eventBus.post(new PostClientTick());
	// Both fire once per client tick, tick() (which drains invokeLater())
	// before tickEnd() (which drains invokeAtTickEnd()) -- so
	// invokeAtTickEnd() SHOULD see a same-tick invokeLater() task. The
	// actual problem: by the time our onVarbitChanged() arms a recheck,
	// that SAME tick's Hooks.tick() call (the one that would drain
	// SlayerPlugin's freshly-queued invokeLater(this::updateTask)) had,
	// in the observed live failure, already run for that tick -- so
	// updateTask() doesn't actually execute until the FOLLOWING tick's
	// Hooks.tick() call, one full tick later than an invokeAtTickEnd()
	// read resolves.
	//
	// FIX: no more ClientThread scheduling for this at all. Every
	// relevant VarbitChanged now just arms a pendingRecheck flag and
	// records client.getTickCount() as armedAtTick; onGameTick() is what
	// actually resolves it, but ONLY once client.getTickCount() has
	// advanced to a STRICTLY LATER tick than armedAtTick --
	// shouldResolvePendingRecheck() is the pure decision function that
	// enforces this, and is what proves the bounded "one GameTick, never
	// one KILL" latency requirement below without needing a real
	// Client/ClientThread at all.
	// =====================================================================

	// Test A: exactly the same relevant-varbit detection as before,
	// still pure and directly testable -- unaffected by the scheduling
	// mechanism change (isRelevantSlayerVarbitChange() replaces the
	// inline check onVarbitChanged() used to do itself).
	@Test
	public void liveBugA_relevantSlayerVarbitsAreDetectedCorrectly()
	{
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(VarPlayerID.SLAYER_COUNT, -1));
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(VarPlayerID.SLAYER_AREA, -1));
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(VarPlayerID.SLAYER_TARGET, -1));
		assertTrue(SlayerCollector.isRelevantSlayerVarbitChange(VarPlayerID.SLAYER_COUNT_ORIGINAL, -1));
		assertFalse("an unrelated varp/varbit change must never arm a recheck",
			SlayerCollector.isRelevantSlayerVarbitChange(-1, -1));
	}

	// Test (live retest core proof): resolving on the SAME tick a
	// recheck was armed on is exactly the bug the live retest caught --
	// must never be considered due.
	@Test
	public void liveRetest_sameTickAsArming_isNeverDue()
	{
		assertFalse(
			"resolving on the same tick that armed the recheck is exactly what made the "
				+ "invokeAtTickEnd() fix still read SlayerPluginService before updateTask() had run",
			SlayerCollector.shouldResolvePendingRecheck(true, 500, 500));
	}

	// Test (live retest core proof): the very next tick IS guaranteed
	// due -- this is the bounded "one GameTick, never longer, never a
	// per-kill accumulating lag" latency this requires.
	@Test
	public void liveRetest_nextTickAfterArming_isDue()
	{
		assertTrue(SlayerCollector.shouldResolvePendingRecheck(true, 500, 501));
	}

	// Test: several ticks later (a missed/delayed onGameTick call is
	// never fatal -- the very first later tick observed resolves it).
	@Test
	public void liveRetest_severalTicksAfterArming_isStillDue()
	{
		assertTrue(SlayerCollector.shouldResolvePendingRecheck(true, 500, 503));
	}

	// Test: nothing pending is never due, regardless of tick numbers.
	@Test
	public void liveRetest_notPending_isNeverDue()
	{
		assertFalse(SlayerCollector.shouldResolvePendingRecheck(false, 500, 999));
	}

	// Test B (paired with scenario3 above, which already proves
	// 118->117 diffs correctly once observed): once shouldResolvePendingRecheck()
	// guarantees we only ever read on a strictly-later tick, each kill's
	// transition is diffed correctly and independently -- no lag concept
	// survives once resolution genuinely happens after updateTask() has
	// run. Re-run scenario3's exact live-reported numbers here explicitly
	// as the value-level counterpart to the scheduling-level proof above.
	@Test
	public void liveBugB_reportedSequence_118to117then117to116_diffsCorrectlyForEachKillInTurn()
	{
		SlayerState at118 = task("Gargoyles", "Wilderness", 118);
		SlayerState at117 = task("Gargoyles", "Wilderness", 117);
		SlayerState at116 = task("Gargoyles", "Wilderness", 116);

		SlayerTransitionResult first = SlayerCollector.evaluateSlayerTransition(at118, at117);
		assertEquals(Kind.PROGRESS, first.kind);
		assertEquals(118, first.previousRemaining);
		assertEquals(117, first.currentRemaining);
		assertEquals(1, first.taskUnitsConsumed);

		SlayerTransitionResult second = SlayerCollector.evaluateSlayerTransition(at117, at116);
		assertEquals(Kind.PROGRESS, second.kind);
		assertEquals(117, second.previousRemaining);
		assertEquals(116, second.currentRemaining);
		assertEquals(1, second.taskUnitsConsumed);
	}

	private static SlayerCollector collectorWithNullDependencies()
	{
		// testArmPendingRecheck()/testIsPendingRecheck()/testGetArmedAtTick()/
		// testResetPendingRecheck() never dereference client/clientThread/
		// slayerPluginService/store/eventLedger -- see this section's own
		// javadoc above -- so null is safe here (same established
		// precedent as the rest of this test class; no Mockito
		// dependency).
		return new SlayerCollector(null, null, null, null, null, new OsrsTelemetryConfig()
		{
		});
	}

	// Test C: rapid sequential relevant changes within the SAME arming
	// window coalesce into exactly one pending recheck (armedAtTick is
	// NOT pushed later by the second/third change); a change observed
	// AFTER the previous recheck has actually resolved (simulated here
	// via testResetPendingRecheck(), mirroring onGameTick()'s own
	// resolution) arms its own fresh, independent recheck -- never
	// collapsed together, never silently dropped.
	@Test
	public void liveBugC_rapidSequentialVarbitChanges_coalesceWithinAWindowButArmAgainAfterEachRecheckResolves()
	{
		SlayerCollector collector = collectorWithNullDependencies();

		collector.testArmPendingRecheck(500);
		collector.testArmPendingRecheck(500); // same tick, second relevant change -- must coalesce
		collector.testArmPendingRecheck(500); // third relevant change, still same tick -- must coalesce

		assertTrue(collector.testIsPendingRecheck());
		assertEquals("several relevant varbits changing together as one real update must coalesce "
			+ "into exactly one pending recheck, armed at the FIRST change's tick",
			500, collector.testGetArmedAtTick());

		// Simulate onGameTick() resolving this recheck on tick 501 (the
		// next tick after arming -- exactly what shouldResolvePendingRecheck()
		// requires) and performCoalescedCheck() having run.
		collector.testResetPendingRecheck();
		assertFalse(collector.testIsPendingRecheck());

		// A change observed after the previous recheck has resolved must
		// arm its own fresh recheck at ITS OWN tick, never reuse the
		// stale prior armedAtTick.
		collector.testArmPendingRecheck(510);
		assertTrue(collector.testIsPendingRecheck());
		assertEquals("a change observed after the previous recheck has resolved must arm its own "
			+ "fresh recheck -- never silently dropped, never merged into a stale prior batch",
			510, collector.testGetArmedAtTick());
	}

	// =====================================================================
	// TASK 5 -- initialAmount=0 transient-hydration coverage.
	//
	// Live evidence: immediately after login hydration, telemetry showed
	// a real, already-named task (amountRemaining=116, taskName=
	// "Gargoyles") with initialAmount=0; a LATER read of the SAME task
	// showed initialAmount=137. Root cause: initialAmount (like
	// taskName) is only ever committed by RuneLite's own SlayerPlugin.
	// setTask(), read at that moment from the SLAYER_COUNT_ORIGINAL
	// varp -- if SLAYER_COUNT/SLAYER_TARGET have already synced but
	// SLAYER_COUNT_ORIGINAL genuinely has not, that first setTask() call
	// legitimately captures initialAmount=0. SlayerCollector.
	// preserveTrustworthyInitialAmount() (private, exercised here only
	// indirectly via evaluateSlayerTransition()'s downstream behavior --
	// its own logic is simple enough, and Client/SlayerPluginService-free
	// enough, to also state directly) must never treat that 0 as
	// authoritative once a real value has already been observed for the
	// SAME task identity, and must never fabricate one that was never
	// actually observed.
	// =====================================================================

	// Test G1: a same-task, same-remaining-count initialAmount
	// correction (0 -> a real value) must never itself be classified as
	// a PROGRESS transition -- evaluateSlayerTransition() only looks at
	// amountRemaining/task identity, exactly as SlayerCollector.
	// performCoalescedCheck() relies on to silently absorb this kind of
	// correction into writeState() without emitting a spurious
	// SLAYER_TASK_PROGRESS event.
	@Test
	public void liveTaskE_initialAmountOnlyCorrection_isNeverClassifiedAsProgress()
	{
		SlayerState beforeCorrection = task("Gargoyles", "Wilderness", 116);
		beforeCorrection.setInitialAmount(0);
		SlayerState afterCorrection = task("Gargoyles", "Wilderness", 116);
		afterCorrection.setInitialAmount(137);

		SlayerTransitionResult result = SlayerCollector.evaluateSlayerTransition(beforeCorrection, afterCorrection);
		assertEquals("an initialAmount-only correction must never be reported as task progress "
			+ "(evaluateSlayerTransition() is deliberately blind to initialAmount)",
			Kind.NONE, result.kind);
	}

	// Test G2: the two SlayerState instances from Test G1 are NOT equal
	// (differ in initialAmount) -- confirming performCoalescedCheck()'s
	// `current.equals(previous)` early-return does NOT swallow this
	// correction; writeState() still runs (via the normal non-NONE-early
	// -return path in performCoalescedCheck()) even though no event is
	// emitted, so slayer.json's initialAmount still gets corrected.
	@Test
	public void liveTaskE_initialAmountOnlyCorrection_isNotEqualToBaseline_soStillGetsWritten()
	{
		SlayerState beforeCorrection = task("Gargoyles", "Wilderness", 116);
		beforeCorrection.setInitialAmount(0);
		SlayerState afterCorrection = task("Gargoyles", "Wilderness", 116);
		afterCorrection.setInitialAmount(137);

		assertNotEquals("an initialAmount correction must be a real, detectable state change so "
			+ "performCoalescedCheck() still calls writeState() for it",
			beforeCorrection, afterCorrection);
	}
}
