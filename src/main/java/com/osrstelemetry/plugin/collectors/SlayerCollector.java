package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.SlayerState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.Objects;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Spyglass's Slayer collector. ALL Slayer state is read natively -- no other
 * RuneLite plugin is involved (Spyglass must never require one):
 * task name / location / remaining / initial amount come from
 * {@link SlayerNativeTaskReader} (Slayer varps + the game cache's Slayer DB
 * tables); points, streak and master come straight from varbits.
 *
 * VARP VS VARBIT: SLAYER_COUNT, SLAYER_AREA, SLAYER_TARGET and
 * SLAYER_COUNT_ORIGINAL are VarPlayer changes, delivered via
 * VarbitChanged.getVarpId(), NOT getVarbitId() -- a collector that checked
 * ONLY varbitId-based conditions would never react to a kill (SLAYER_COUNT
 * decrementing) or a new assignment (SLAYER_TARGET changing). Both varpId
 * and varbitId are watched (see isRelevantSlayerVarbitChange()).
 *
 * STREAK SOURCE: the streak shown and used for cancellation detection is
 * the CURRENT master's own counter -- Krystilia's Wilderness streak, Mortimer's
 * separate streak (varp SLAYER_MORTIMER_TASKS_COMPLETED, verified against
 * RuneLite 1.13.1's SlayerPlugin.addCounter()), otherwise the normal streak.
 *
 * BRACELET EFFECTS: the game's own chat messages for a Bracelet of slaughter
 * ("prevents your slayer count from decreasing") or an Expeditious bracelet
 * ("helps you progress your slayer task faster") are recorded as
 * SLAYER_BRACELET_EFFECT events -- the exact wording RuneLite's own
 * ItemChargePlugin matches. A changed remaining count alone is never taken
 * as a bracelet proc.
 */
@Slf4j
public class SlayerCollector
{
	private static final int KRYSTILIA_MASTER_ID = 7;
	private static final int MORTIMER_MASTER_ID = 10;

	static final String BRACELET_SLAUGHTER = "SLAUGHTER";
	static final String BRACELET_EXPEDITIOUS = "EXPEDITIOUS";
	private static final java.util.regex.Pattern SLAUGHTER_ACTIVATE = java.util.regex.Pattern.compile(
		"Your bracelet of slaughter prevents your slayer count from decreasing\\. (?:It has (\\d{1,2}) charges? left\\.|It then crumbles to dust\\.|It then regenerates itself to full charge!)");
	private static final java.util.regex.Pattern EXPEDITIOUS_ACTIVATE = java.util.regex.Pattern.compile(
		"Your expeditious bracelet helps you progress your slayer (?:task )?faster\\. (?:It has (\\d{1,2}) charges? left\\.|It then crumbles to dust\\.|It then regenerates itself to full charge!)");

	private final Client client;
	private final ClientThread clientThread;
	private final SlayerNativeTaskReader nativeTaskReader;
	private final SlayerCommittedTask committedTask;
	private final LocalStateStore store;
	private final EventLedger eventLedger;

	/**
	 * A naive design -- a synchronous "no task"
	 * read leaving hydration open for "the first async check," then
	 * treating THAT first async check as automatically final -- has a
	 * lifecycle hole: the first async check can ALSO observe a transient
	 * "no task" (Slayer varps not yet synced from the server at login or
	 * mid-hop). That would still
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
		SlayerNativeTaskReader nativeTaskReader,
		SlayerCommittedTask committedTask,
		LocalStateStore store,
		EventLedger eventLedger)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.nativeTaskReader = nativeTaskReader;
		this.committedTask = committedTask;
		this.store = store;
		this.eventLedger = eventLedger;
	}

	public void resetForAccountSwitch()
	{
		hydration.reset();
		pendingRecheck = false;
		armedAtTick = -1;
		publishCommitted(null);
	}

	/** Mirrors the committed baseline into the shared SlayerCommittedTask (NPC_DEATH annotation). */
	private void publishCommitted(SlayerState state)
	{
		if (committedTask != null)
		{
			committedTask.set(state);
		}
	}

	/**
	 * Called from OsrsTelemetryPlugin.handleLogin().
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
	 * NOT exempt from this. The Slayer varps can transiently blank out
	 * mid-hop, and carrying an OLD
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
		SlayerState current = resolveCurrent();
		if (current == null)
		{
			// UNRESOLVED (cache not ready): unknown, not "no task" -- never
			// fed into hydration; the next relevant varp change rechecks.
			return;
		}
		writeState(current);

		if (!hydration.isHydrated() && hydration.observeBeforeHydration(current))
		{
			publishCommitted(current);
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
		// The coalesced recheck resolves FIRST (see onVarbitChanged()'s and
		// shouldResolvePendingRecheck()'s comments): it can only resolve once
		// a tick has passed since its relevant VarbitChanged, so every varp
		// that changed on that tick has been applied.
		boolean resolvedRecheck = false;
		if (shouldResolvePendingRecheck(pendingRecheck, armedAtTick, client.getTickCount()))
		{
			pendingRecheck = false;
			armedAtTick = -1;
			performCoalescedCheck();
			resolvedRecheck = true;
		}

		// "No task" stabilization: only an UNDISTURBED tick counts toward
		// confirming NONE. A tick that resolved a recheck, or that has a
		// relevant varp change still pending, is evidence something is
		// settling -- exactly SlayerBaselineHydration's documented rule.
		// (Advancing the countdown before the recheck, as this used to,
		// could confirm NONE on the very tick a hop/login resync arrived
		// and then diff the real task against it as a fabricated
		// SLAYER_TASK_ASSIGNED.) No-op once hydrated.
		if (!resolvedRecheck && !pendingRecheck && hydration.onGameTick())
		{
			writeState(hydration.getLastKnown());
			publishCommitted(hydration.getLastKnown());
		}
	}

	/**
	 * Pure decision function: a coalesced recheck armed on tick T resolves
	 * on the first onGameTick() of a STRICTLY LATER tick. By then every varp
	 * the server changed on tick T has been applied, so the native read sees
	 * a whole assignment change at once (target, area, original count and
	 * count arrive together), never a half-applied one that could look like
	 * an intermediate task. currentTick &gt; armedAtTick (rather than ==
	 * armedAtTick + 1) means a missed or delayed onGameTick() call never
	 * leaves this permanently stuck: the first later tick observed resolves
	 * it. This one-tick (~0.6s) resolution is the timing that passed live
	 * QA; it is kept deliberately by the native-source migration (originally
	 * it also waited out RuneLite's Slayer plugin, which is no longer
	 * involved).
	 */
	static boolean shouldResolvePendingRecheck(boolean pendingRecheck, int armedAtTick, int currentTick)
	{
		return pendingRecheck && currentTick > armedAtTick;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!isRelevantSlayerVarbitChange(event.getVarpId(), event.getVarbitId()))
		{
			return;
		}

		// Coalesce: record the tick this relevant change arrived on and
		// resolve on the next tick (see shouldResolvePendingRecheck()).
		// Several varps changing on one tick arm exactly one recheck.
		if (!pendingRecheck)
		{
			pendingRecheck = true;
			armedAtTick = client.getTickCount();
		}
	}

	/**
	 * Pure predicate (package-private, directly unit-testable with no
	 * RuneLite runtime): every varp/varbit the native task resolution
	 * (SlayerNativeTaskResolver, including the quantity modifier) and the
	 * points/streak/master reads depend on. Extracted from onVarbitChanged() so the
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
			|| varbitId == VarbitID.SLAYER_MODIFIER_ID
			|| varbitId == VarbitID.SLAYER_MODIFIER_VALUE
			|| varbitId == VarbitID.SLAYER_MODIFIER_NEGATIVE
			|| varbitId == VarbitID.SLAYER_POINTS
			|| varbitId == VarbitID.SLAYER_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED
			|| varpId == VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED
			|| varbitId == VarbitID.SLAYER_MASTER;
	}

	/**
	 * Test-only hooks (package-private, same convention as
	 * SessionRuntimeCoordinator's testProcessTick()/testEnqueueSignal()
	 * elsewhere in this project): let a test drive the pendingRecheck/
	 * armedAtTick state machine directly, without needing a real
	 * Client (this project's test suite deliberately never mocks it -- no
	 * Mockito dependency).
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
		if (current == null)
		{
			// UNRESOLVED: keep the previous baseline untouched (RuneLite's own
			// resolution does the same); a later varp change rechecks.
			return;
		}

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
			if (hydration.observeBeforeHydration(current))
			{
				publishCommitted(current);
			}
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
		publishCommitted(current);
		writeState(current);
	}

	/**
	 * Owns ALL mutable Slayer baseline-hydration state, extracted so
	 * SlayerCollectorTest can drive the exact stabilization rule
	 * directly with plain SlayerState inputs and simulated GameTicks --
	 * no Client/ClientThread mocking needed (this
	 * project has no Mockito dependency). SlayerCollector's
	 * captureInitialStateWithoutBaseline()/performCoalescedCheck()/
	 * onGameTick()/resetForAccountSwitch() all delegate
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
		 * beyond the varp-sync settling identified above, while staying short enough
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

		/** Test seam: sets a hydrated baseline directly, bypassing the
		 * stabilization window (used to model "already hydrated" before
		 * a world hop). */
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
			{
				EventPayloads.SlayerTaskCompleted completed = new EventPayloads.SlayerTaskCompleted(
					previous.getTaskName(), current.getStreak(), current.getStreakLabel(), current.getPoints());
				if (looksCancelled(previous, current))
				{
					completed.setCancelled(Boolean.TRUE);
				}
				eventLedger.append(client.getAccountHash(), EventType.SLAYER_TASK_COMPLETED, completed);
				break;
			}
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
	 * A task that disappears is not necessarily finished: cancelling or
	 * blocking it at a master clears it the same way. Only positive evidence
	 * marks a cancellation -- Slayer points went DOWN (a completion never
	 * costs points) while the completed-tasks streak for the same streak
	 * type did NOT go up (a completion always advances it). Anything less
	 * certain (e.g. the varbits settling on a later check) stays an
	 * ordinary completion, exactly as before.
	 */
	static boolean looksCancelled(SlayerState previous, SlayerState current)
	{
		return previous != null && current != null
			&& current.getPoints() < previous.getPoints()
			&& Objects.equals(previous.getStreakLabel(), current.getStreakLabel())
			&& current.getStreak() <= previous.getStreak();
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

	/**
	 * The streak belonging to `masterId`: Krystilia's Wilderness counter,
	 * Mortimer's separate counter, else the normal streak. Pure (tests).
	 */
	static void applyStreakSource(SlayerState state, int masterId, int normalStreak, int wildernessStreak, int mortimerStreak)
	{
		if (masterId == KRYSTILIA_MASTER_ID)
		{
			state.setStreak(wildernessStreak);
			state.setStreakLabel("WILDERNESS");
		}
		else if (masterId == MORTIMER_MASTER_ID)
		{
			state.setStreak(mortimerStreak);
			state.setStreakLabel("MORTIMER");
		}
		else
		{
			state.setStreak(normalStreak);
			state.setStreakLabel("NORMAL");
		}
		state.setStreakSourceVerified(true);
	}

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (event.getType() != net.runelite.api.ChatMessageType.GAMEMESSAGE
			&& event.getType() != net.runelite.api.ChatMessageType.SPAM)
		{
			return;
		}
		String effect = parseBraceletEffect(net.runelite.client.util.Text.removeTags(event.getMessage()));
		if (effect != null)
		{
			eventLedger.append(client.getAccountHash(), EventType.SLAYER_BRACELET_EFFECT, new EventPayloads.SlayerBraceletEffect(effect));
		}
	}

	/** SLAUGHTER / EXPEDITIOUS for the game's own bracelet activation message, else null. Pure (tests). */
	static String parseBraceletEffect(String message)
	{
		if (message == null)
		{
			return null;
		}
		if (SLAUGHTER_ACTIVATE.matcher(message).find())
		{
			return BRACELET_SLAUGHTER;
		}
		if (EXPEDITIOUS_ACTIVATE.matcher(message).find())
		{
			return BRACELET_EXPEDITIOUS;
		}
		return null;
	}

	private SlayerState resolveCurrent()
	{
		SlayerNativeTask nativeTask = nativeTaskReader.read();
		if (nativeTask.isUnresolved())
		{
			return null;
		}

		SlayerState state = new SlayerState();
		state.setAmountRemaining(Math.max(nativeTask.getRemaining(), 0));
		state.setNativeCountOriginal(nativeTask.getRaw().getCountOriginal());

		// Task identity (taskName/taskLocation) comes from the native
		// task's own resolved fields -- set only when it is ACTIVE; NO_TASK
		// (SLAYER_COUNT <= 0) leaves them null, which is the "no task"
		// signal evaluateSlayerTransition() keys on (see its javadoc).
		if (nativeTask.isActive())
		{
			state.setTaskName(nativeTask.getTaskName());
			state.setTaskLocation(nativeTask.getTaskLocation());
			state.setInitialAmount(nativeTask.getInitialAmount());
		}

		int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		state.setMasterId(masterId);
		state.setPoints(client.getVarbitValue(VarbitID.SLAYER_POINTS));

		applyStreakSource(state, masterId,
			client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED),
			client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED),
			client.getVarpValue(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED));

		SlayerNativeTaskResolver.RawVars raw = nativeTask.getRaw();
		state.setModifierId(raw.modifierId);
		state.setModifierValue(raw.modifierValue);
		state.setModifierNegative(raw.modifierNegative == 1);

		return preserveTrustworthyInitialAmount(state);
	}

	/**
	 * Live evidence: immediately after login
	 * hydration, telemetry showed a real, already-named task
	 * (amountRemaining=116, taskName="Gargoyles") with initialAmount=0;
	 * a later read of the SAME task showed initialAmount=137. Root
	 * cause: SLAYER_COUNT/SLAYER_TARGET (which drive amountRemaining and
	 * taskName) can sync client-side at login before the
	 * SLAYER_COUNT_ORIGINAL varp arrives from the server, so a read in
	 * that window legitimately sees initialAmount=0 -- a real "not yet
	 * populated" transient for that one field, not a lie about the task
	 * itself (taskName/amountRemaining are still correct at the same
	 * moment). The native reader re-reads the varp on every recheck, so
	 * the real value arrives with the next relevant change.
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
	 * the native read reports a real value for this task
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
