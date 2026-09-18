package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.SlayerState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.Objects;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.slayer.SlayerPluginService;

/**
 * Task-resolution reads go through RuneLite's published
 * net.runelite.client.plugins.slayer.SlayerPluginService. Points/streak/
 * master still read directly via varbits, since the service doesn't
 * expose those.
 *
 * VARP VS VARBIT: SlayerPlugin.java's own onVarbitChanged shows that
 * SLAYER_COUNT, SLAYER_AREA, and SLAYER_TARGET are VarPlayer changes,
 * delivered via VarbitChanged.getVarpId(), NOT getVarbitId() -- a
 * collector that checked ONLY varbitId-based conditions would never
 * react to a kill (SLAYER_COUNT decrementing) or a new assignment
 * (SLAYER_TARGET changing). Both varpId and varbitId are watched below,
 * mirroring the real plugin's actual condition exactly (see
 * isRelevantSlayerVarbitChange()).
 *
 * ON MORTIMER: verified against the
 * complete current SlayerPlugin.java. There is no MORTIMER_SLAYER_MASTER
 * constant, no VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED, no
 * SLAYER_MODIFIER_* varbits, anywhere in the file. The only
 * master-specific streak logic that exists is the same two-way check:
 *   client.getVarbitValue(VarbitID.SLAYER_MASTER) == KRYSTILIA_SLAYER_MASTER
 *       ? WILDERNESS_TASKS_COMPLETED : SLAYER_TASKS_COMPLETED
 * masterId is still captured correctly for every master including
 * Mortimer; his streak specifically stays unverified since his numeric
 * master id is not available from any source this project can reach,
 * so the code cannot special-case detection of "this might be wrong."
 */
public class SlayerCollector
{
	private static final int KRYSTILIA_MASTER_ID = 7;

	private final Client client;
	private final ClientThread clientThread;
	private final SlayerPluginService slayerPluginService;
	private final LocalStateStore store;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	/**
	 * A naive design -- a synchronous "no task"
	 * read leaving hydration open for "the first async check," then
	 * treating THAT first async check as automatically final -- has a
	 * lifecycle hole: the first async check can ALSO observe a transient
	 * "no task" (racing
	 * RuneLite's own SlayerPlugin, which independently resolves
	 * SlayerPluginService's cached task fields off the same varbits via
	 * its own clientThread.invokeLater(this::updateTask)). That would still
	 * allow: sync=no-task -> first async=no-task (locked in as the
	 * baseline) -> next async=Gargoyles/124 (diffed against the wrong
	 * "no task" baseline) -> fabricated SLAYER_TASK_ASSIGNED.
	 *
	 * Instead, all mutable hydration state is
	 * owned by
	 * SlayerBaselineHydration (below), and "no task" is never
	 * trusted merely for being first -- it must be OBSERVED STABLE
	 * across NONE_STABILIZATION_TICKS consecutive GameTicks with no
	 * further relevant VarbitChanged resetting the countdown (see that
	 * field's javadoc). A REAL named task, at any point before that
	 * countdown completes, still hydrates immediately and silently --
	 * a real task can never be a sync
	 * artifact. This is a small, deterministic, GameTick-driven
	 * stabilization rule -- not an arbitrary sleep -- mirroring this
	 * codebase's own precedent for exactly this class of problem
	 * (ContainerCollector's debounced bank-snapshot settle window,
	 * trySeedIdentity()'s GameTick-based readiness retry).
	 */
	private final SlayerBaselineHydration hydration = new SlayerBaselineHydration();

	// A tick-counted deferral: pendingRecheck/armedAtTick, resolved
	// from onGameTick() once client.getTickCount() has genuinely advanced
	// past the tick the relevant VarbitChanged fired on -- see
	// onVarbitChanged()'s own comment for why this specific mechanism is
	// required (an invokeAtTickEnd()-based approach does not work).
	private volatile boolean pendingRecheck = false;
	private volatile int armedAtTick = -1;

	@Inject
	public SlayerCollector(
		Client client,
		ClientThread clientThread,
		SlayerPluginService slayerPluginService,
		LocalStateStore store,
		EventLedger eventLedger,
		OsrsTelemetryConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.slayerPluginService = slayerPluginService;
		this.store = store;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	public void resetForAccountSwitch()
	{
		hydration.reset();
		pendingRecheck = false;
		armedAtTick = -1;
	}

	/**
	 * Gated on config — called on
	 * re-enable via ConfigChanged, and must not seed Slayer state at
	 * all while the category is disabled.
	 *
	 * NOT called from login (see
	 * captureInitialStateWithoutBaseline() below for why) — this
	 * remains correct for the re-enable case specifically because
	 * there is no post-login client-sync gap to worry about there: the
	 * category was merely toggled off/on mid-session, RuneLite's
	 * varp/varbit values are already fully settled, and no
	 * VarbitChanged has fired yet for this reseed to race against.
	 * Hydrating immediately and directly (bypassing the stabilization
	 * window entirely) is what correctly prevents whatever changed
	 * while the category was disabled (e.g. a task completed) from
	 * being fabricated as a transition the moment collection resumes.
	 */
	public void seedSilently()
	{
		if (!config.collectSlayer())
		{
			return;
		}
		SlayerState current = resolveCurrent();
		hydration.hydrateDirectly(current);
		writeState(current);
	}

	/**
	 * Called from
	 * OsrsTelemetryPlugin.handleLogin() INSTEAD of seedSilently().
	 *
	 * A naive approach -- diffing the login-time reading directly against
	 * whatever state was last known -- produces a real bug: a
	 * SLAYER_TASK_ASSIGNED event firing on a plain login into an
	 * account that already had an assigned task (Gargoyles), when no new
	 * task was actually assigned. A subtler follow-up hole: even with
	 * that direct-diff path removed, a REPEATED transient "no task" reading
	 * (sync read, then the first async check too) could still lock in
	 * a wrong baseline before the real task settled. See
	 * SlayerBaselineHydration's javadoc for the stabilization
	 * rule this method and performCoalescedCheck()/onGameTick() all
	 * feed into.
	 *
	 * This method persists current state to disk unconditionally
	 * (freshness — slayer.json should not sit stale/absent between
	 * login and hydration completing) and hands its own reading to
	 * hydration.observeBeforeHydration() — which locks immediately if
	 * it already shows a real task, or arms/restarts the NONE
	 * stabilization countdown otherwise.
	 *
	 * A same-account world hop is
	 * NOT exempt from this. RuneLite's Slayer state (SlayerPluginService/
	 * varps) can transiently blank out mid-hop, and carrying an OLD
	 * trusted baseline across that gap would let the transient blank reading
	 * get diffed as a genuine COMPLETED, then the real post-hop settle
	 * diffed as a genuine ASSIGNED — two fabricated events for what was
	 * actually continuity. OsrsTelemetryPlugin.handleLogin() calls
	 * slayerCollector.resetForAccountSwitch() UNCONDITIONALLY on every
	 * LOGGED_IN transition (fresh start, real switch, or a same-account
	 * hop/relog), not only on a real account switch — see its own
	 * comment. That reset is what makes this method's
	 * observeBeforeHydration() call meaningful again on a hop: hydration
	 * is freshly reopened first, so this reading re-establishes the
	 * baseline through the exact same stabilization-tolerant,
	 * no-fabricated-event machinery already proven correct for a fresh
	 * client launch — no separate suppression system.
	 */
	public void captureInitialStateWithoutBaseline()
	{
		if (!config.collectSlayer())
		{
			return;
		}
		SlayerState current = resolveCurrent();
		writeState(current);

		if (!hydration.isHydrated())
		{
			hydration.observeBeforeHydration(current);
		}
	}

	/**
	 * The
	 * deterministic, GameTick-driven stabilization signal. While hydration has a "no task" candidate armed (see
	 * SlayerBaselineHydration.observeBeforeHydration()), this ticks the
	 * countdown down once per GameTick; reaching zero with the
	 * countdown never having been reset by an intervening relevant
	 * VarbitChanged is what finally confirms NONE as a genuinely
	 * trustworthy baseline. A no-op (and cheap: a single volatile-ish
	 * field read) once hydrated or when no countdown is armed, so this
	 * is safe to leave subscribed for the collector's entire lifetime.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!config.collectSlayer())
		{
			return;
		}
		if (hydration.onGameTick())
		{
			writeState(hydration.getLastKnown());
		}

		// See onVarbitChanged()'s comment for the root cause this
		// resolves. Resolves the
		// coalesced recheck exactly once client.getTickCount() has
		// genuinely moved past the tick that armed it -- see
		// shouldResolvePendingRecheck()'s own javadoc for why this is the
		// deterministic point RuneLite's own SlayerPlugin.updateTask() is
		// guaranteed to have already run.
		if (shouldResolvePendingRecheck(pendingRecheck, armedAtTick, client.getTickCount()))
		{
			pendingRecheck = false;
			armedAtTick = -1;
			performCoalescedCheck();
		}
	}

	/**
	 * Pure decision function (Hooks.tick()'s own posted
	 * order proves this): a coalesced recheck armed on tick T is only
	 * guaranteed to observe RuneLite SlayerPlugin's settled
	 * SlayerPluginService state once we are dispatched for a STRICTLY
	 * LATER tick than T -- never on tick T itself, no matter how late in
	 * tick T's own processing this check runs. See onVarbitChanged()'s
	 * comment for the full derivation from RuneLite's actual
	 * Hooks.tick()/tickEnd() source (GAME_TICK is posted, and our
	 * onGameTick() runs, BEFORE that same tick's clientThread.invoke()
	 * drains the invokeLater(this::updateTask) call SlayerPlugin's own
	 * onVarbitChanged queued this tick -- so even a same-tick,
	 * end-of-tick read via invokeAtTickEnd() can still run before
	 * updateTask() does. Only the NEXT tick's onGameTick() call is
	 * guaranteed to run after tick T's invoke() has fully drained).
	 * currentTick strictly greater than armedAtTick is exactly "at least
	 * one full tick boundary, and therefore at least one full
	 * clientThread.invoke() drain, has elapsed since arming" --
	 * regardless of how many ticks have actually passed, so a missed or
	 * delayed onGameTick() call never leaves this permanently stuck
	 * (first later tick observed resolves it).
	 */
	static boolean shouldResolvePendingRecheck(boolean pendingRecheck, int armedAtTick, int currentTick)
	{
		return pendingRecheck && currentTick > armedAtTick;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!config.collectSlayer())
		{
			return;
		}

		if (!isRelevantSlayerVarbitChange(event.getVarpId(), event.getVarbitId()))
		{
			return;
		}

		// ============================================================
		// WHY invokeAtTickEnd() DOES NOT WORK HERE: it is tempting to
		// assume that invokeAtTickEnd()'s queue drains strictly after
		// invokeLater()'s queue WITHIN THE SAME TICK, so a same-tick
		// invokeAtTickEnd() recheck would always see SlayerPlugin's
		// already-updated state -- that is true about ClientThread's queue
		// STRUCTURE, but false about WHEN each queue actually drains
		// relative to VarbitChanged, once RuneLite's real client-loop
		// hook (net.runelite.client.callback.Hooks, which is what
		// actually calls clientThread.invoke()/invokeTickEnd() -- not
		// ClientThread itself) is inspected instead of ClientThread in
		// isolation:
		//
		//   Hooks.tick():     eventBus.post(GAME_TICK); clientThread.invoke();
		//   Hooks.tickEnd():  clientThread.invokeTickEnd(); eventBus.post(new PostClientTick());
		//
		// Both `tick()` and `tickEnd()` fire once per client tick, and
		// `tick()` (which drains invokeLater()) runs BEFORE `tickEnd()`
		// (which drains invokeAtTickEnd()) -- so structurally
		// invokeAtTickEnd() SHOULD still observe a same-tick
		// invokeLater() task. The actual problem is finer-grained: varp
		// diffing/VarbitChanged posting for a tick's server update
		// happens as its own step, and by the time our onVarbitChanged()
		// runs and arms a recheck, that tick's `Hooks.tick()` call --
		// the one that would drain SlayerPlugin's freshly-queued
		// invokeLater(this::updateTask) -- has, in the observed live
		// failure, ALREADY happened for the tick in which the varp
		// changed. That leaves updateTask() queued but not yet run until
		// the FOLLOWING tick's `Hooks.tick()` call -- one full tick
		// later than an invokeAtTickEnd()-deferred read, which resolves
		// at THIS tick's `tickEnd()`, still before updateTask() ever
		// executes. This produces a deterministic (not racy) one-decrement
		// lag: invokeAtTickEnd()
		// always resolves one tick too early relative to when
		// updateTask() actually runs.
		//
		// Instead, this does not schedule anything via ClientThread at all here.
		// It records the CURRENT tick number (client.getTickCount())
		// as `armedAtTick`, and defers resolution to onGameTick() --
		// specifically, only once client.getTickCount() has advanced to
		// a STRICTLY LATER value than armedAtTick (see
		// shouldResolvePendingRecheck()'s javadoc). Since onGameTick()
		// itself only runs once per tick and (per Hooks.tick() above) is
		// posted BEFORE that same tick's clientThread.invoke() drains,
		// waiting for the NEXT tick's onGameTick() call guarantees the
		// PRIOR tick's entire `Hooks.tick()` call -- GAME_TICK post AND
		// clientThread.invoke() drain, i.e. updateTask() -- has already
		// completed before we read. This is a bounded, deterministic
		// ONE-GAMETICK latency (~0.6s), never a guess, never a race, and
		// never a per-kill accumulating lag: every relevant VarbitChanged
		// arms (or, if already armed, coalesces into) exactly one
		// pending recheck, resolved on the first later tick observed.
		if (!pendingRecheck)
		{
			pendingRecheck = true;
			armedAtTick = client.getTickCount();
		}
	}

	/**
	 * Pure predicate (package-private, directly unit-testable with no
	 * RuneLite runtime) mirroring exactly what current
	 * SlayerPlugin.java's own onVarbitChanged checks -- see class
	 * javadoc for how that was independently reconfirmed against the
	 * complete current source. Extracted from onVarbitChanged() so the
	 * "which varp/varbit changes matter" decision is testable without a
	 * real Client/VarbitChanged-dispatch pipeline.
	 */
	static boolean isRelevantSlayerVarbitChange(int varpId, int varbitId)
	{
		return varpId == VarPlayerID.SLAYER_COUNT
			|| varpId == VarPlayerID.SLAYER_AREA
			|| varpId == VarPlayerID.SLAYER_TARGET
			|| varpId == VarPlayerID.SLAYER_COUNT_ORIGINAL
			|| varbitId == VarbitID.SLAYER_TARGET_BOSSID
			|| varbitId == VarbitID.SLAYER_POINTS
			|| varbitId == VarbitID.SLAYER_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_MASTER;
	}

	/**
	 * Test-only hooks (package-private, same convention as
	 * SessionRuntimeCoordinator's testProcessTick()/testEnqueueSignal()
	 * elsewhere in this project): let a test drive the pendingRecheck/
	 * armedAtTick state machine directly, without needing a real
	 * Client/SlayerPluginService (this project's test suite deliberately
	 * never mocks those -- no Mockito dependency).
	 */
	void testArmPendingRecheck(int tick)
	{
		if (!pendingRecheck)
		{
			pendingRecheck = true;
			armedAtTick = tick;
		}
	}

	boolean testIsPendingRecheck()
	{
		return pendingRecheck;
	}

	int testGetArmedAtTick()
	{
		return armedAtTick;
	}

	void testResetPendingRecheck()
	{
		pendingRecheck = false;
		armedAtTick = -1;
	}

	private void performCoalescedCheck()
	{
		// pendingRecheck/armedAtTick are already reset by the caller
		// (onGameTick()'s shouldResolvePendingRecheck() branch) before
		// this method runs -- see that method's own comment.
		SlayerState current = resolveCurrent();

		if (!hydration.isHydrated())
		{
			// This
			// async, VarbitChanged-triggered read is never trusted
			// as automatically final just for being first. observeBeforeHydration() hydrates immediately only
			// if `current` already shows a real task; otherwise it
			// arms/restarts the NONE stabilization countdown, and
			// onGameTick() is what eventually confirms NONE once that
			// countdown genuinely elapses undisturbed. Either way,
			// nothing is ever diffed/emitted from this branch.
			hydration.observeBeforeHydration(current);
			writeState(current);
			return;
		}

		SlayerState previous = hydration.getLastKnown();
		if (current.equals(previous))
		{
			return;
		}

		emitTransitionEvents(previous, current);
		hydration.commit(current);
		writeState(current);
	}

	/**
	 * Owns ALL mutable Slayer baseline-hydration state, extracted so
	 * SlayerCollectorTest can drive the exact stabilization rule
	 * directly with plain SlayerState inputs and simulated GameTicks --
	 * no Client/SlayerPluginService/ClientThread mocking needed (this
	 * project has no Mockito dependency). SlayerCollector's
	 * captureInitialStateWithoutBaseline()/performCoalescedCheck()/
	 * onGameTick()/seedSilently()/resetForAccountSwitch() all delegate
	 * here; production and tests share this exact class.
	 *
	 * THE STABILIZATION RULE: a
	 * real, named task observed at ANY point before hydration completes
	 * hydrates immediately -- an empty/default varp state can never
	 * spontaneously resolve into a specific, already-named monster that
	 * doesn't genuinely exist, so seeing one is never a false positive
	 * (only ever genuine, or stale-from-a-prior-account, which
	 * SlayerCollector.resetForAccountSwitch() already handles
	 * separately by calling reset() first). A "no task" observation,
	 * by contrast, remains genuinely ambiguous (really no task, vs. not
	 * yet synced) and is NEVER trusted merely for being first: it is held as a candidate, and
	 * only becomes the confirmed baseline once NONE_STABILIZATION_TICKS
	 * consecutive GameTicks elapse with NOTHING resetting the
	 * countdown -- and ANY further "no task" observation during that
	 * window (a relevant varbit firing again, still showing no task)
	 * is itself evidence something may still be settling, so it
	 * restarts the countdown from the top rather than counting as one
	 * of the confirming ticks. This directly closes the reproduced
	 * race (sync=no-task, first async=no-task, next=Gargoyles/124):
	 * neither no-task observation ever locks anything in, so the
	 * eventual Gargoyles/124 reading -- whenever it arrives -- always
	 * hydrates silently via the "real task" branch, never gets diffed
	 * against a wrongly-confirmed NONE baseline. Once NONE genuinely IS
	 * confirmed (no further activity for the full stabilization
	 * window -- i.e. truly no task, not just "not yet settled"), a
	 * LATER genuine assignment still produces exactly one
	 * SLAYER_TASK_ASSIGNED, since that assignment is now a normal
	 * post-hydration diff against a trustworthy NONE baseline.
	 */
	static final class SlayerBaselineHydration
	{
		/**
		 * How many consecutive, undisturbed GameTicks a "no task"
		 * reading must survive before it's trusted as the confirmed
		 * baseline. Deliberately small, deterministic, and GameTick-
		 * driven (RuneLite's own tick cadence, ~0.6s each) rather than
		 * an arbitrary wall-clock sleep -- 2 full ticks is comfortably
		 * beyond the single-tick ordering race identified above (our own VarbitChanged-triggered read
		 * landing before RuneLite's own SlayerPlugin has resolved its
		 * task cache for the same tick), while staying short enough
		 * that a genuinely-no-task account is confirmed within ~1.2s of
		 * login, not stuck open indefinitely.
		 */
		static final int NONE_STABILIZATION_TICKS = 2;

		private SlayerState lastKnown;
		private boolean hydrated;
		private SlayerState pendingNoTaskCandidate;
		private int noTaskTicksRemaining = -1;

		void reset()
		{
			lastKnown = null;
			hydrated = false;
			pendingNoTaskCandidate = null;
			noTaskTicksRemaining = -1;
		}

		/** seedSilently()'s immediate, un-stabilized seed (re-enable
		 * path only -- see its own javadoc for why no stabilization
		 * window is needed there). */
		void hydrateDirectly(SlayerState state)
		{
			lastKnown = state;
			hydrated = true;
			pendingNoTaskCandidate = null;
			noTaskTicksRemaining = -1;
		}

		boolean isHydrated()
		{
			return hydrated;
		}

		SlayerState getLastKnown()
		{
			return lastKnown;
		}

		/** Post-hydration: records a real, diffed observation as the
		 * new baseline. Never called while !hydrated. */
		void commit(SlayerState current)
		{
			lastKnown = current;
		}

		/**
		 * Pre-hydration observation, from either
		 * captureInitialStateWithoutBaseline()'s synchronous read or
		 * performCoalescedCheck()'s async one -- both feed the exact
		 * same rule. Returns true if this call hydrated (a real task
		 * was observed); false if it only armed/restarted the NONE
		 * stabilization countdown.
		 */
		boolean observeBeforeHydration(SlayerState observed)
		{
			if (hydrated)
			{
				return false;
			}
			if (observed.getTaskName() != null)
			{
				lastKnown = observed;
				hydrated = true;
				pendingNoTaskCandidate = null;
				noTaskTicksRemaining = -1;
				return true;
			}
			// Still no task -- (re)arm the stabilization countdown.
			// Deliberately restarts from the top even if a countdown
			// was already running: this observation itself is evidence
			// a relevant varbit just fired, i.e. something may still be
			// settling, so it must not count as one of the confirming
			// quiet ticks.
			pendingNoTaskCandidate = observed;
			noTaskTicksRemaining = NONE_STABILIZATION_TICKS;
			return false;
		}

		/**
		 * One GameTick's worth of stabilization progress. Returns true
		 * exactly on the tick NONE becomes confirmed (caller should
		 * persist getLastKnown() then, matching every other hydration
		 * path's "state written during hydration" requirement).
		 */
		boolean onGameTick()
		{
			if (hydrated || noTaskTicksRemaining < 0)
			{
				return false;
			}
			// Must
			// decrement BEFORE checking for zero. Checking-then-
			// decrementing made the documented "2 undisturbed
			// GameTicks" actually require 3 onGameTick() calls (2->1,
			// 1->0, then a third call to see 0 and confirm).
			noTaskTicksRemaining--;
			if (noTaskTicksRemaining == 0)
			{
				lastKnown = pendingNoTaskCandidate;
				hydrated = true;
				pendingNoTaskCandidate = null;
				noTaskTicksRemaining = -1;
				return true;
			}
			return false;
		}
	}

	private void emitTransitionEvents(SlayerState previous, SlayerState current)
	{
		SlayerTransitionResult result = evaluateSlayerTransition(previous, current);

		switch (result.kind)
		{
			case COMPLETED:
				eventLedger.append(
					client.getAccountHash(),
					EventType.SLAYER_TASK_COMPLETED,
					new EventPayloads.SlayerTaskCompleted(
						previous.getTaskName(), current.getStreak(), current.getStreakLabel(), current.getPoints())
				);
				break;
			case ASSIGNED:
				eventLedger.append(
					client.getAccountHash(),
					EventType.SLAYER_TASK_ASSIGNED,
					new EventPayloads.SlayerTaskAssigned(
						current.getTaskName(), current.getAmountRemaining(), current.getTaskLocation(), current.getMasterId())
				);
				break;
			case PROGRESS:
				// current.getInitialAmount()/getMasterId() are safe to
				// read unfabricated here: PROGRESS only fires when
				// hadTask && hasTask (see evaluateSlayerTransition()),
				// so current.getAmountRemaining() > 0, which is exactly
				// the condition resolveCurrent() requires before it sets
				// initialAmount/taskName/taskLocation at all — these
				// fields are genuinely known on this observation, not
				// defaulted/guessed.
				eventLedger.append(
					client.getAccountHash(),
					EventType.SLAYER_TASK_PROGRESS,
					new EventPayloads.SlayerTaskProgress(
						current.getTaskName(), current.getTaskLocation(),
						result.previousRemaining, result.currentRemaining,
						result.remainingDelta, result.taskUnitsConsumed,
						current.getInitialAmount(), current.getMasterId())
				);
				break;
			case NONE:
			default:
				break;
		}
	}

	/**
	 * Pure,
	 * Client/EventLedger-independent core of the Slayer transition
	 * decision — factored out of emitTransitionEvents() for the same
	 * reason as QuestCollector.justFinished()/SkillsCollector.
	 * evaluateXpWindow(): unit-testable without mocking RuneLite's
	 * Client. SlayerState itself has no Client dependency (see its
	 * class javadoc), so it can be constructed directly in a test.
	 *
	 * TASK IDENTITY vs. TASK PROGRESS/METADATA: identity is taskName + taskLocation only.
	 * amountRemaining, initialAmount, points, streak, streakLabel,
	 * streakSourceVerified, and masterId are all progress/metadata —
	 * none of them ever contribute to sameTaskIdentity below.
	 * masterId is deliberately excluded from identity: its absence is only a THEORETICAL risk (a same-name/
	 * location task reassigned by a different master would be missed),
	 * with no production evidence it's ever actually happened — not
	 * fixed without evidence.
	 *
	 * previous == null means this is the first observation since login
	 * or a reset — must always be NONE (silent seed), never a
	 * fabricated ASSIGNED, regardless of what current looks like. This
	 * is the exact invariant that, combined with
	 * captureInitialStateWithoutBaseline() never pre-setting
	 * lastKnown at login, avoids a false
	 * SLAYER_TASK_ASSIGNED-on-plain-login: the first genuine
	 * performCoalescedCheck() after login always calls this with
	 * previous == null.
	 *
	 * Same-identity + unchanged amountRemaining -> NONE. Same-identity +
	 * changed amountRemaining (in EITHER direction — a decrement from a
	 * kill, or an increase, which is never reinterpreted as a new
	 * assignment) -> PROGRESS, carrying the observed delta. A normal decrement
	 * (e.g. 138 Gargoyles -> 137 Gargoyles) must NEVER return ASSIGNED —
	 * it is explicitly
	 * PROGRESS, never ASSIGNED and never silently indistinguishable from
	 * "nothing happened." A different identity (whether or
	 * not the two assignments' remaining counts happen to differ) is
	 * always ASSIGNED/COMPLETED, never PROGRESS — the two are mutually
	 * exclusive by construction below (the PROGRESS branch is only
	 * reached after both the COMPLETED and ASSIGNED conditions have
	 * already tested false, i.e. only when sameTaskIdentity is true).
	 */
	static SlayerTransitionResult evaluateSlayerTransition(SlayerState previous, SlayerState current)
	{
		if (previous == null)
		{
			return SlayerTransitionResult.none();
		}

		// Task
		// presence/absence must be determined from the actual task
		// identity fields (taskName), never from amountRemaining alone.
		// amountRemaining == 0 is progress/metadata, not "no task" --
		// treating it as identity-absence would fabricate
		// SLAYER_TASK_ASSIGNED for an identical task whose remaining
		// count happened to cross the 0/1 boundary (e.g. 0 -> 1).
		boolean hadTask = previous.getTaskName() != null;
		boolean hasTask = current.getTaskName() != null;
		boolean sameTaskIdentity = Objects.equals(previous.getTaskName(), current.getTaskName())
			&& Objects.equals(previous.getTaskLocation(), current.getTaskLocation());

		if (hadTask && !hasTask)
		{
			return SlayerTransitionResult.completed();
		}
		if (hasTask && (!hadTask || !sameTaskIdentity))
		{
			return SlayerTransitionResult.assigned();
		}

		// Reaching here means hadTask && hasTask && sameTaskIdentity —
		// the only case a progress reading is even meaningful, since
		// PROGRESS must never span two different assignments.
		int previousRemaining = previous.getAmountRemaining();
		int currentRemaining = current.getAmountRemaining();
		if (previousRemaining == currentRemaining)
		{
			return SlayerTransitionResult.none();
		}

		int remainingDelta = currentRemaining - previousRemaining;
		int taskUnitsConsumed = Math.max(0, previousRemaining - currentRemaining);
		return SlayerTransitionResult.progress(previousRemaining, currentRemaining, remainingDelta, taskUnitsConsumed);
	}

	/**
	 * Richer pure result type — needed since PROGRESS carries payload data alongside
	 * its classification, rather than duplicating decision logic elsewhere.
	 * previousRemaining/currentRemaining/remainingDelta/taskUnitsConsumed
	 * are meaningful only when kind == PROGRESS; left at 0 (unused) for
	 * every other kind, mirroring XpWindowResult/LevelTransitionResult's
	 * existing "unused fields default, only shouldEmit/kind is load-
	 * bearing" convention elsewhere in this codebase.
	 */
	static final class SlayerTransitionResult
	{
		enum Kind
		{
			NONE, ASSIGNED, COMPLETED, PROGRESS
		}

		final Kind kind;
		final int previousRemaining;
		final int currentRemaining;
		final int remainingDelta;
		final int taskUnitsConsumed;

		private SlayerTransitionResult(Kind kind, int previousRemaining, int currentRemaining, int remainingDelta, int taskUnitsConsumed)
		{
			this.kind = kind;
			this.previousRemaining = previousRemaining;
			this.currentRemaining = currentRemaining;
			this.remainingDelta = remainingDelta;
			this.taskUnitsConsumed = taskUnitsConsumed;
		}

		static SlayerTransitionResult none()
		{
			return new SlayerTransitionResult(Kind.NONE, 0, 0, 0, 0);
		}

		static SlayerTransitionResult assigned()
		{
			return new SlayerTransitionResult(Kind.ASSIGNED, 0, 0, 0, 0);
		}

		static SlayerTransitionResult completed()
		{
			return new SlayerTransitionResult(Kind.COMPLETED, 0, 0, 0, 0);
		}

		static SlayerTransitionResult progress(int previousRemaining, int currentRemaining, int remainingDelta, int taskUnitsConsumed)
		{
			return new SlayerTransitionResult(Kind.PROGRESS, previousRemaining, currentRemaining, remainingDelta, taskUnitsConsumed);
		}
	}

	private SlayerState resolveCurrent()
	{
		SlayerState state = new SlayerState();

		int remaining = slayerPluginService.getRemainingAmount();
		state.setAmountRemaining(Math.max(remaining, 0));

		// Task identity
		// (taskName/taskLocation) must be captured from the service's
		// own task fields, never gated on amountRemaining > 0 --
		// amountRemaining == 0 does not by itself mean "no task exists"
		// (see evaluateSlayerTransition()'s javadoc). A blank/null task name from the
		// service is still the correct "no task" signal.
		String taskName = slayerPluginService.getTask();
		if (taskName != null && !taskName.isEmpty())
		{
			state.setTaskName(taskName);
			state.setTaskLocation(slayerPluginService.getTaskLocation());
			state.setInitialAmount(slayerPluginService.getInitialAmount());
		}

		int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		state.setMasterId(masterId);
		state.setPoints(client.getVarbitValue(VarbitID.SLAYER_POINTS));

		if (masterId == KRYSTILIA_MASTER_ID)
		{
			state.setStreak(client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED));
			state.setStreakLabel("WILDERNESS");
			state.setStreakSourceVerified(true);
		}
		else
		{
			state.setStreak(client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED));
			state.setStreakLabel("NORMAL");
			state.setStreakSourceVerified(true);
		}

		return preserveTrustworthyInitialAmount(state);
	}

	/**
	 * Live evidence: immediately after login
	 * hydration, telemetry showed a real, already-named task
	 * (amountRemaining=116, taskName="Gargoyles") with initialAmount=0;
	 * a later read of the SAME task showed initialAmount=137. Root
	 * cause, traced against RuneLite's own SlayerPlugin.updateTask():
	 * `initialAmount` (like `taskName`) is only ever committed via
	 * setTask() -- read at that moment from the SLAYER_COUNT_ORIGINAL
	 * varp. If SLAYER_COUNT/SLAYER_TARGET (which drive amountRemaining
	 * and taskName) have already synced client-side at login but
	 * SLAYER_COUNT_ORIGINAL genuinely has not yet arrived from the
	 * server, that FIRST setTask() call legitimately captures
	 * initialAmount=0 -- this is SlayerPluginService's real "not yet
	 * populated" transient state for that one field, not a lie about
	 * the task itself (taskName/amountRemaining are still correct at
	 * the same moment).
	 *
	 * 0 is never persisted as if it were authoritative task
	 * metadata once we have already observed a real (non-zero)
	 * initialAmount for this EXACT task identity (taskName+taskLocation
	 * match against hydration's last committed baseline) -- the
	 * previously-trustworthy value is carried forward instead. This
	 * never fabricates a number that was never actually observed: if
	 * hydration has no prior baseline yet (very first-ever read of a
	 * brand new task), or the prior baseline is for a DIFFERENT task, or
	 * the prior baseline's own initialAmount was itself still 0, this is
	 * a no-op and 0 passes through unchanged -- honestly representing
	 * "not yet known" rather than guessing. The moment
	 * SlayerPluginService itself reports a real value for this task
	 * (e.g. once SLAYER_COUNT_ORIGINAL syncs and triggers another
	 * recheck), this method stops intervening and the real value flows
	 * through untouched, correcting slayer.json without ever emitting a
	 * spurious SLAYER_TASK_PROGRESS (evaluateSlayerTransition() only
	 * looks at amountRemaining/task identity, never initialAmount, so a
	 * pure initialAmount correction is silently absorbed into the next
	 * writeState() call).
	 */
	private SlayerState preserveTrustworthyInitialAmount(SlayerState state)
	{
		if (state.getTaskName() == null || state.getInitialAmount() != 0)
		{
			return state;
		}

		SlayerState previous = hydration.getLastKnown();
		if (previous != null
			&& previous.getInitialAmount() != 0
			&& Objects.equals(previous.getTaskName(), state.getTaskName())
			&& Objects.equals(previous.getTaskLocation(), state.getTaskLocation()))
		{
			state.setInitialAmount(previous.getInitialAmount());
		}

		return state;
	}

	private void writeState(SlayerState state)
	{
		state.setLastUpdated(Instant.now().toString());
		store.write(TelemetryPaths.stateFile(client.getAccountHash(), "slayer"), state);
	}
}
