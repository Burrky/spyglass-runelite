package com.osrstelemetry.plugin.session;

import com.osrstelemetry.plugin.collectors.LoadoutArchive;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;

/**
 * The narrow runtime orchestration component: receives already-normalized
 * telemetry facts (via TelemetryEventListener, registered on
 * EventLedger), attaches account/tick/time context, converts them to
 * SessionSignal objects (via SessionEventMapper), batches same-tick
 * signals, resolves them (SessionSignalBatchResolver), updates
 * lifecycle (SessionLifecycleEngine), updates aggregates
 * (SessionAggregateUpdater), and persists the result
 * (SessionPersistence). Lives inside com.osrstelemetry.plugin.session
 * specifically because SessionSignalBatchResolver, its BatchResolution,
 * and MetricUpdate are package-private.
 *
 * THREADING MODEL: every method that touches currentAccountHash, engine,
 * classifierContext, or pendingSignals is `synchronized` on this
 * instance. This is a single, coherent ownership model -- "all
 * coordinator state mutation is serialized through one monitor" --
 * rather than field-by-field locking. It is required, not merely
 * defensive: onEvent() is NOT always called from the RuneLite client
 * thread (SkillsCollector's final XP-window flush can call
 * EventLedger.append() from shutDown() on the AWT thread -- see its own
 * audit note), onGameTick()/ensureAccountLoaded() run on the client
 * thread, and finalizeForClientShutdownBlocking() runs on RuneLite's
 * shared scheduledExecutor thread via the ClientShutdown hook below.
 * Without one shared monitor, `engine`/`classifierContext` -- plain
 * fields reassigned on one thread and read on another with no
 * synchronization at all -- would be a genuine, unguarded data race
 * (not just "stale value": no happens-before edge existed at all in the
 * pre-hardening version of this class). None of these methods block on
 * I/O while holding the monitor except finalizeForClientShutdownBlocking()'s
 * one bounded, synchronous persistCurrentAndWait() call, which happens
 * only once, at genuine client shutdown, and calls nothing that could
 * ever need this same monitor back -- no deadlock risk.
 *
 * DOES NOT RE-PARSE events.jsonl, DOES NOT DUPLICATE COLLECTOR TRUTH
 * LOGIC -- unchanged from the original design; see SessionEventMapper's
 * own javadoc.
 *
 * TICK-BATCH CLOSURE: the original version resolved every pending signal on every
 * GameTick, on the assumption that "GameTick fires after all of that
 * tick's other events." That assumption covers ordinary state-change
 * events (StatChanged, VarbitChanged, ActorDeath, NpcLootReceived,
 * ServerNpcLoot -- all confirmed posted before GameTick), but NOT
 * telemetry emitted from within ANOTHER subscriber's OWN onGameTick()
 * handler for the very same tick -- e.g. this plugin's own periodic
 * skillsCollector.closeXpWindowsIfDue()/questCollector.checkAndFlush()
 * calls, both invoked from OsrsTelemetryPlugin.onGameTick(). RuneLite's
 * EventBus does not guarantee this coordinator's onGameTick() runs
 * AFTER every other subscriber's onGameTick() for the same event, so a
 * same-tick XP_CHANGE emitted that way could have been enqueued into
 * pendingSignals AFTER this coordinator had already drained and
 * resolved that same tick -- silently deferring it into the WRONG
 * (next) tick's batch and reproducing exactly the "transient wrong
 * activity before the real one" bug the whole batching design exists
 * to prevent.
 *
 * FIX: a signal is only ever resolved once a STRICTLY LATER tick number
 * has actually been observed (see processTick()/isClosed()) -- never
 * merely because "a GameTick callback started." RuneLite only posts
 * GameTick for tick N+1 after every subscriber's handling of tick N
 * (including any telemetry emitted mid-handling) is completely done, so
 * observing tick N+1 is a genuine, structural proof that tick N is
 * closed -- no sleep, no subscriber-order assumption. This defers
 * ordinary per-tick resolution by at most one tick (~600ms), which is
 * an explicitly acceptable trade-off (no test in this project requires
 * zero-latency resolution). At real shutdown/ClientShutdown, nothing
 * more can ever arrive for any tick, so flushPendingAndShutdown()/
 * finalizeForClientShutdownBlocking() flush EVERYTHING unconditionally
 * via flushAllPendingSignals(), bypassing the closed-tick wait entirely
 * -- never lose the final pending batch merely because the client stops.
 */
@Slf4j
@Singleton
public final class SessionRuntimeCoordinator implements TelemetryEventListener
{
	private static final long NO_ACCOUNT = -1L;

	@Inject
	private Client client;

	@Inject
	private ScheduledExecutorService scheduledExecutor;

	private final SessionPersistence persistence;
	private final ActivitySignalClassifier classifier = new ActivitySignalClassifier();
	private List<SessionSignal> pendingSignals = new ArrayList<>();

	private long currentAccountHash = NO_ACCOUNT;
	private SessionLifecycleEngine engine = new SessionLifecycleEngine();
	private ClassifierContext classifierContext = new ClassifierContext();

	/**
	 * See {@link LiveXpTracker}'s own javadoc for the full
	 * contract. Owned here (never durable, never persisted) alongside
	 * `engine`/`classifierContext` -- reset on account switch (see
	 * ensureAccountLoaded()), fed by recordLiveXpDelta() (raw
	 * StatChanged, via SkillsCollector), reconciled inside
	 * applyMetrics() whenever a real durable XP metric lands, and read
	 * by getCurrentSessionSnapshot() under this same monitor.
	 */
	private final LiveXpTracker liveXpTracker = new LiveXpTracker();

	/**
	 * The most recently observed ABSOLUTE XP value for every
	 * skill, fed continuously by SkillsCollector's own raw StatChanged
	 * stream (see noteAbsoluteXp()'s own javadoc) -- the SAME underlying
	 * observations LiveXpTracker's own pending deltas are built from,
	 * just kept as running absolute totals here instead of pending
	 * deltas. NOT session-scoped by itself (it is simply "the latest
	 * known XP per skill, full stop") -- it only becomes session-boundary
	 * data at the exact moment a real switch is detected, when its
	 * current contents are snapshotted into ClassifierContext (see
	 * resolveAndApplyBatches()'s own call to
	 * classifierContext.noteSessionSwitchXpBaseline()). Reset on account
	 * switch (see ensureAccountLoaded()) so a brand-new account never
	 * inherits another account's absolute XP figures.
	 */
	private final Map<String, Long> lastKnownAbsoluteXpBySkill = new HashMap<>();

	// Live in SessionRuntimeCoordinatorTest.java (a real,
	// extensive existing test suite -- device-tree inspection found
	// two call sites, `new SessionRuntimeCoordinator(store)`) --
	// changing this signature outright would have broken that whole
	// file's compilation. Kept exactly as-is, no longer @Inject
	// (Guice only ever uses ONE constructor; the new two-arg one below
	// is what production wiring now uses), delegating to a null
	// archive -- LoadoutResolver treats that as "always unavailable"
	// rather than throwing (see its own javadoc), so every existing
	// test using this constructor keeps compiling and behaving exactly
	// as before.
	public SessionRuntimeCoordinator(LocalStateStore store)
	{
		this(store, null);
	}

	// Purely mechanical constructor-injection wiring so
	// SessionPersistence's starting/ending loadout resolution (see its
	// own javadoc) has real archive data to resolve against in
	// production. One new constructor parameter threaded straight
	// through to SessionPersistence's own (already backward-compatible)
	// two-arg constructor. No lifecycle/classifier/hysteresis/Slayer
	// logic anywhere in this class is touched; every synchronized
	// method, every field below, and the entire threading model
	// described in this class's own javadoc are unchanged.
	@Inject
	public SessionRuntimeCoordinator(LocalStateStore store, LoadoutArchive loadoutArchive)
	{
		this.persistence = new SessionPersistence(store, loadoutArchive);
	}

	/**
	 * Rehydration entry point. Called
	 * from OsrsTelemetryPlugin.handleLogin() on every LOGGED_IN
	 * transition; a same-account call (fresh start already loaded, or a
	 * world hop/relog) is a deliberate no-op (see class javadoc).
	 *
	 * ACCOUNT-SWITCH PENDING BATCH: a real switch away from account A now FLUSHES (not
	 * silently discards) anything still pending for A first -- that
	 * telemetry genuinely already occurred under A before the switch was
	 * observed, so finishing A's own already-occurred batch and
	 * persisting A's resulting state is the honest, no-fabrication
	 * choice, and it can never be applied to B's session (B's engine/
	 * context/pendingSignals do not exist yet at the point this runs).
	 */
	public synchronized void ensureAccountLoaded(long accountHash)
	{
		if (accountHash == currentAccountHash)
		{
			return;
		}

		if (currentAccountHash != NO_ACCOUNT)
		{
			flushAllPendingSignals();
			// CASE C (never-resolved boss-task candidate) -- account
			// switch: a pending self-consumption candidate that never got
			// to confirm/abort before this switch must not be silently
			// discarded by the fresh ClassifierContext below -- commit
			// its buffered metric(s) once, as a normal contextual metric,
			// to whatever session is legitimately current for the OLD
			// account right now (see flushAbandonedBossTaskCandidateIfMatching()'s
			// own javadoc).
			flushAbandonedBossTaskCandidateIfMatching(engine.getCurrentSession());
			persistence.persistCurrent(currentAccountHash, engine.getCurrentSession());
		}
		else
		{
			pendingSignals = new ArrayList<>();
		}

		currentAccountHash = accountHash;
		classifierContext = new ClassifierContext();
		// A brand-new account must never inherit
		// a stale live-XP owner/pending amount left over from whichever
		// account was previously loaded -- see LiveXpTracker.reset()'s
		// own javadoc.
		liveXpTracker.reset();
		// A brand-new account must never inherit
		// another account's absolute XP figures as a stale session-
		// boundary baseline -- see lastKnownAbsoluteXpBySkill's own
		// javadoc.
		lastKnownAbsoluteXpBySkill.clear();

		Session persisted = persistence.loadCurrent(accountHash);
		SessionLifecycleEngine.RehydrationResult rehydrated = SessionLifecycleEngine.rehydrate(persisted, Instant.now());
		engine = rehydrated.getEngine();

		if (rehydrated.getFinalizedDuringRehydration() != null)
		{
			persistence.persistFinalized(accountHash, rehydrated.getFinalizedDuringRehydration(), engine.getCurrentSession());
		}
		else
		{
			persistence.persistCurrent(accountHash, engine.getCurrentSession());
		}
	}

	/**
	 * TelemetryEventListener: called synchronously by EventLedger from
	 * inside append()/appendAndWait(), AFTER the durable write has
	 * already been accepted (see EventLedger's own ordering javadoc) --
	 * on whatever thread called append() (see class javadoc's THREADING
	 * MODEL). Deliberately cheap and in-memory only: builds a
	 * SessionSignal (or drops the event, if session-irrelevant) and
	 * queues it. No disk I/O, no lifecycle mutation happens here.
	 */
	@Override
	public synchronized void onEvent(long accountHash, EventType type, Object payload, Instant observedAt)
	{
		if (accountHash != currentAccountHash)
		{
			// A stray event for an account this coordinator is no longer
			// (or not yet) tracking -- e.g. a late off-thread flush racing
			// an account switch. Conservatively dropped rather than
			// misattributed.
			return;
		}

		SessionSignal signal = SessionEventMapper.map(type, payload, observedAt);
		if (signal == null)
		{
			return;
		}

		Long tick = safeTickCount();
		if (tick != null)
		{
			signal = signal.withGameTick(tick);
		}

		pendingSignals.add(signal);
	}

	private Long safeTickCount()
	{
		try
		{
			if (client.isClientThread())
			{
				return (long) client.getTickCount();
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Could not read game tick while ingesting a session signal", e);
		}
		return null;
	}

	/**
	 * The deterministic per-tick flush
	 * point. Reads the CURRENT tick from the client thread (this method
	 * is only ever invoked from onGameTick(), always on the client
	 * thread), then: (1) resolves and applies every pending signal
	 * belonging to a now-PROVABLY-CLOSED tick (strictly less than the
	 * current one -- see class javadoc's TICK-BATCH CLOSURE section);
	 * (2) advances the timeout clock. Step (1) always runs before step
	 * (2) -- events before a timeout boundary are applied before
	 * finalization, for every tick that is actually closed as of this
	 * call.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		processTick(client.getTickCount(), Instant.now());
	}

	synchronized void processTick(long currentTick, Instant now)
	{
		if (currentAccountHash == NO_ACCOUNT)
		{
			return;
		}

		// TIMEOUT WATERMARK + SKIP-ADVANCE-AFTER-RESOLVE: two DIFFERENT
		// concerns addressing the same underlying principle -- EVENT-TIME ORDERING, NOT
		// PROCESSING-TIME ORDERING must govern a suspend/resume/finalize
		// decision whenever real qualifying evidence is already known.
		//
		// (a) flushClosedBatches() resolves every signal from a tick
		// that is now provably closed. If any of those batches actually
		// applied a real lifecycle transition (RESOLVED -- see
		// resolveAndApplyBatches()'s own javadoc), that transition
		// already used the BATCH's own event-time timestamp (via
		// SessionLifecycleEngine.onQualifyingActivity()/refineIdentity(),
		// which have their own correct, event-time-driven suspend/
		// resume/finalize logic built in). Calling advanceTime() again
		// immediately afterward, in this SAME call, using this call's
		// raw wall-clock `now`, would re-evaluate suspend/finalize using
		// PROCESSING time instead -- and if this tick's own processing
		// happened to run late (a slow tick, a delayed batch), that
		// stale `now` could immediately re-suspend/re-finalize a session
		// that the batch itself had just correctly resumed/extended from
		// its own true occurrence time. So when a real transition was
		// just applied, advanceTime() is deliberately NOT called here
		// -- any further idle-time evaluation is deferred to the
		// NEXT processTick() call, whose own `now` will by then be
		// genuinely current (at most one more tick, ~600ms, later under
		// ordinary play -- see class javadoc's TICK-BATCH CLOSURE
		// section for the same accepted latency trade-off).
		//
		// (b) When nothing was just resolved, timeoutWatermark() still
		// guards the ordinary advanceTime() call below against a
		// DIFFERENT case: a signal already sitting pending for the
		// CURRENT (still-open) tick that hasn't closed yet -- see its
		// own javadoc.
		boolean lifecycleTransitionJustApplied = flushClosedBatches(currentTick);
		if (lifecycleTransitionJustApplied)
		{
			return;
		}

		Instant watermark = timeoutWatermark(currentTick, now);

		Session before = engine.getCurrentSession();
		SessionState beforeState = before == null ? null : before.getState();

		LifecycleResult timeoutResult = engine.advanceTime(watermark);

		// Unconfirmed-candidate metrics duplicated across
		// sessions, Case 4: a pending weak-evidence candidate whose original
		// session's resume window simply expired here still had real,
		// never-confirmed metrics buffered against it -- commit them once,
		// as contextual metrics, to whichever session is finalizing as a
		// result of this same call, BEFORE that session is persisted, so
		// the immutable finalized record actually contains them.
		if (timeoutResult.getContextualMetricsTarget() != null)
		{
			applyMetrics(timeoutResult.getContextualMetricsTarget(), timeoutResult.getContextualMetrics());
		}

		// CASE C (never-resolved boss-task candidate) -- pure idle
		// timeout: a pending self-consumption candidate (an entirely
		// different, classifier-layer mechanism from the weak-evidence
		// candidate just above) is armed only while its boss session is ACTIVE. If
		// that session times out here (ACTIVE -> SUSPENDED, or, on a
		// later call, an already-SUSPENDED session's resume window
		// expiring to FINALIZED) with STILL no same-boss BOSS_KILL or
		// confirming loot ever having arrived, the candidate's buffered
		// metric(s) must not simply evaporate -- commit them once to
		// whichever of `beforeState`'s own session identities is still
		// legitimately current after this call (see
		// flushAbandonedBossTaskCandidateIfMatching()'s own javadoc),
		// BEFORE that session is persisted/finalized below.
		if (timeoutResult.getFinalized() != null)
		{
			flushAbandonedBossTaskCandidateIfMatching(timeoutResult.getFinalized());
		}
		else if (beforeState == SessionState.ACTIVE && timeoutResult.getCurrent() != null
			&& timeoutResult.getCurrent().getState() == SessionState.SUSPENDED)
		{
			flushAbandonedBossTaskCandidateIfMatching(timeoutResult.getCurrent());
		}

		if (timeoutResult.getFinalized() != null)
		{
			persistence.persistFinalized(currentAccountHash, timeoutResult.getFinalized(), timeoutResult.getCurrent());
		}
		else if (timeoutResult.getCurrent() != null && timeoutResult.getCurrent().getState() != beforeState)
		{
			persistence.persistCurrent(currentAccountHash, timeoutResult.getCurrent());
		}
	}

	/**
	 * Caps `now` at the earliest observedAt among signals still pending
	 * for the CURRENT, not-yet-closed tick (gameTick == currentTick) --
	 * i.e. signals that are already known to have occurred, are already
	 * enqueued, and will be resolved the instant this tick closes (the
	 * very next processTick() call), but have not been applied yet.
	 * Intentionally scoped to gameTick == currentTick only, NOT to
	 * every pending signal: an untagged (off-client-thread) signal is a
	 * separate, pre-existing, documented case (see class javadoc's
	 * OFF-CLIENT-THREAD note) that is deliberately left pending until an
	 * unconditional flush (shutdown/account-switch) rather than ordinary
	 * tick closure, and folding it into this watermark would let one
	 * stray off-thread signal freeze timeout advancement indefinitely
	 * during otherwise-idle ordinary play -- a strictly worse outcome
	 * than the bug being fixed here. A signal tagged with an OLDER tick
	 * (< currentTick) is excluded too: flushClosedBatches() above has
	 * already resolved and applied it before this method is ever called,
	 * so it can no longer affect the watermark.
	 *
	 * STRICT INEQUALITY, NOT EQUALITY: the cap is the pending signal's own observedAt
	 * MINUS ONE NANOSECOND, not the observedAt itself. SessionLifecycleEngine
	 * deliberately treats "exactly at" a suspend/resume-expiry boundary
	 * as belonging to a REAL qualifying event arriving at that instant
	 * (onQualifyingActivity()'s inclusive !isAfter(resumeExpiry) check --
	 * see its own class javadoc's EXACT-BOUNDARY ASYMMETRY note), while
	 * advanceTime() alone reaching that same instant with no accompanying
	 * activity treats it as already-expired (its own inclusive
	 * !isBefore(expiry) check). If this watermark were allowed to equal
	 * the pending signal's observedAt exactly, advanceTime() would apply
	 * the "no accompanying activity" boundary rule at the very instant
	 * real, already-known, still-pending activity actually occurred --
	 * finalizing/suspending a session ahead of the qualifying event that
	 * was about to legitimately resume or extend it. Backing off by one
	 * nanosecond keeps advanceTime() strictly on the "before" side of
	 * that instant, leaving the exact-boundary decision entirely to the
	 * eventual onQualifyingActivity()/refineIdentity() call once the
	 * tick actually closes -- which is the only place that decision is
	 * allowed to be made from event-time.
	 *
	 * When nothing is pending for the current tick, returns `now`
	 * unchanged -- an idle session with no open activity times out on
	 * ordinary wall-clock time exactly as before this fix.
	 */
	private Instant timeoutWatermark(long currentTick, Instant now)
	{
		Instant watermark = now;
		for (SessionSignal signal : pendingSignals)
		{
			Long tick = signal.getGameTick();
			if (tick != null && tick == currentTick)
			{
				Instant cap = signal.getObservedAt().minusNanos(1);
				if (cap.isBefore(watermark))
				{
					watermark = cap;
				}
			}
		}
		return watermark;
	}

	/**
	 * Splits off and resolves every pending signal belonging to a tick
	 * strictly before currentTick -- i.e. a tick RuneLite has already
	 * fully finished dispatching, including every subscriber's own
	 * onGameTick()-driven telemetry for it (see class javadoc). Signals
	 * tagged with currentTick itself (still open -- this very GameTick
	 * dispatch may not be done yet) and any signal with no tick at all
	 * (the rare off-thread case -- see safeTickCount()) are left pending
	 * for a later call; an untagged signal is conservatively treated the
	 * same as "not yet provably closed" rather than flushed immediately,
	 * so it still gets at least one full tick to be joined by anything
	 * else from the same real game action before being resolved.
	 *
	 * @return true if any batch resolved here actually applied a real
	 * lifecycle transition (a RESOLVED outcome -- see
	 * resolveAndApplyBatches()'s own return-value javadoc). processTick()
	 * uses this to decide whether it is safe to also run advanceTime()
	 * in the same call -- see its own javadoc for why it must not.
	 */
	private boolean flushClosedBatches(long currentTick)
	{
		List<SessionSignal> closed = new ArrayList<>();
		List<SessionSignal> stillOpen = new ArrayList<>();
		for (SessionSignal signal : pendingSignals)
		{
			Long tick = signal.getGameTick();
			if (tick != null && tick < currentTick)
			{
				closed.add(signal);
			}
			else
			{
				stillOpen.add(signal);
			}
		}
		pendingSignals = stillOpen;
		return resolveAndApplyBatches(closed);
	}

	/**
	 * Unconditional flush of everything pending, regardless of tick
	 * closure -- used only when nothing more can possibly arrive for any
	 * tick (plugin disable, real account switch, client shutdown). See
	 * class javadoc's TICK-BATCH CLOSURE section for why the ordinary
	 * per-GameTick path (flushClosedBatches()) must NOT do this. Return
	 * value intentionally ignored by every caller of this method (none
	 * of them ever call advanceTime() afterward).
	 */
	private boolean flushAllPendingSignals()
	{
		List<SessionSignal> toProcess = pendingSignals;
		pendingSignals = new ArrayList<>();
		return resolveAndApplyBatches(toProcess);
	}

	/**
	 * @return true if at least one batch in `toProcess` had a RESOLVED
	 * outcome -- i.e. actually called engine.onQualifyingActivity()/
	 * refineIdentity() and mutated the session's lifecycle state (ACTIVE/
	 * SUSPENDED/FINALIZED, lastActiveAt, or activityIdentity). false for
	 * an empty input, or when every batch was NO_EVIDENCE/AMBIGUOUS
	 * (metrics may still have been applied to an already-current
	 * session in that case, but no lifecycle transition occurred).
	 */
	private boolean resolveAndApplyBatches(List<SessionSignal> toProcess)
	{
		if (toProcess.isEmpty())
		{
			return false;
		}

		boolean anyLifecycleTransitionApplied = false;

		List<List<SessionSignal>> batches = SessionSignalBatchResolver.groupIntoBatches(toProcess);
		for (List<SessionSignal> batch : batches)
		{
			Session preBatchCurrent = engine.getCurrentSession();
			SessionSignalBatchResolver.BatchResolution resolution =
				SessionSignalBatchResolver.resolve(batch, preBatchCurrent, classifier, classifierContext);

			// LOOT-WHILE-ACTIVE OWNERSHIP: applied directly to the pre-batch
			// `current` session, unconditionally, BEFORE any lifecycle
			// transition below -- see SessionSignalBatchResolver.resolve()'s
			// own comment for the full rationale. Whatever preBatchCurrent
			// goes on to become in this same call (unchanged `current`,
			// re-keyed via refineIdentity(), or finalized by a same-tick
			// switch) already carries this loot once this mutates it, so
			// it is never lost and never rides onto an unrelated new
			// session purely because that session happened to win this
			// batch's lifecycle decision.
			if (preBatchCurrent != null && !resolution.getPreBatchCurrentMetricUpdates().isEmpty())
			{
				applyMetrics(preBatchCurrent, resolution.getPreBatchCurrentMetricUpdates());
			}

			if (resolution.getOutcome() == BatchLifecycleOutcome.RESOLVED)
			{
				SignalClassification winning = resolution.getLifecycleClassification();
				// EVIDENCE STRENGTH: only
				// onQualifyingActivity() ever gates a transition behind the
				// weak-evidence candidate mechanism (see
				// SessionLifecycleEngine's own javadoc) -- refineIdentity()
				// never gates (it re-keys the SAME continuous activity, not
				// a competing one), so it has no strength parameter to
				// receive.
				LifecycleResult result = winning.getDecisionKind() == SignalDecisionKind.REFINE
					? engine.refineIdentity(winning.getIdentity(), resolution.getLifecycleTimestamp(), resolution.getMetricUpdates())
					: engine.onQualifyingActivity(winning.getIdentity(), resolution.getLifecycleTimestamp(), resolution.getMetricUpdates(), resolution.getLifecycleEvidenceStrength());
				anyLifecycleTransitionApplied = true;

				// A GENUINE session-identity switch
				// -- result.getCurrent() is a different session id than
				// whatever was current immediately before this batch
				// (never true for refineIdentity(), which always re-keys
				// the SAME continuous session/id) -- captures an exact
				// per-skill absolute-XP baseline for the brand-new
				// session, from whatever this coordinator has most
				// recently observed via SkillsCollector's raw StatChanged
				// stream. This is what lets a LATER XP_CHANGE, whose own
				// aggregation window spans this exact switch, be split
				// exactly into its pre-switch and post-switch portions --
				// see ClassifierContext.noteSessionSwitchXpBaseline()'s
				// own javadoc and classifyXpChange()'s boundary-ownership
				// rule for the full mechanism this baseline enables.
				if (result.getCurrent() != null
					&& (preBatchCurrent == null || !preBatchCurrent.getSessionId().equals(result.getCurrent().getSessionId())))
				{
					classifierContext.noteSessionSwitchXpBaseline(result.getCurrent().getSessionId(), lastKnownAbsoluteXpBySkill);
				}

				// CASE C (never-resolved boss-task candidate) -- an
				// UNRELATED real switch (a different boss's BOSS_KILL, a
				// SKILLING switch, any other authoritative evidence that
				// has nothing to do with this candidate) just finalized
				// the boss session a self-consumption candidate is still
				// pending for, without ever going through
				// classifyBossKill()/classifyServerNpcLoot()'s own
				// confirm/abort branches (those already clear the
				// candidate themselves -- this is a pure no-op whenever
				// they already ran). The candidate's buffered metric(s)
				// belong to real activity that genuinely occurred during
				// THAT now-finalizing session, so they are committed
				// there, once, BEFORE it is persisted -- never onto the
				// new, unrelated session this batch just started.
				if (result.getFinalized() != null)
				{
					flushAbandonedBossTaskCandidateIfMatching(result.getFinalized());
				}

				// Unconfirmed-candidate metrics duplicated
				// across sessions: this batch's own metrics no longer go
				// unconditionally to result.getCurrent() -- while a
				// weak-evidence candidate is merely being armed/replaced,
				// result.getCurrent() is still the OLD established
				// session, not any kind of holding area for this batch.
				// The engine now tells us exactly where this batch's own
				// metrics belong (metricsForCurrent -- empty when they
				// were only buffered as a pending candidate) and,
				// separately, whether an ABANDONED candidate's previously-
				// buffered metrics must be committed once, as contextual
				// metrics, to whichever session they actually occurred
				// during (contextualMetricsTarget/contextualMetrics --
				// see LifecycleResult's own javadoc). Applying both here,
				// to their own correct targets, is what makes every real
				// metric land exactly once.
				applyMetrics(result.getCurrent(), result.getMetricsForCurrent());
				if (result.getContextualMetricsTarget() != null)
				{
					applyMetrics(result.getContextualMetricsTarget(), result.getContextualMetrics());
				}

				if (result.getFinalized() != null)
				{
					// Immutable finalized record written before the
					// pointer is cleared/replaced -- SessionPersistence's
					// own existing write-then-callback ordering guarantee
					// (unchanged finalization ordering). The contextual-metrics apply
					// above already ran before this write whenever its
					// target was this same finalized session, so the
					// committed-once contextual metrics are included in
					// the immutable record.
					persistence.persistFinalized(currentAccountHash, result.getFinalized(), result.getCurrent());
				}
				else
				{
					persistence.persistCurrent(currentAccountHash, result.getCurrent());
				}
			}
			else
			{
				// NO_EVIDENCE or AMBIGUOUS: no lifecycle transition, but
				// every metric in the batch is still credited to whatever
				// session is ALREADY current -- never fabricated a place
				// to put it if there is none. preBatchCurrentMetricUpdates
				// (if any) were already applied to this exact same session
				// object above, so persistence just needs to fire whenever
				// EITHER list was non-empty.
				Session target = engine.getCurrentSession();
				boolean hasOrdinaryMetrics = !resolution.getMetricUpdates().isEmpty();
				boolean hasPreBatchCurrentMetrics = preBatchCurrent != null && !resolution.getPreBatchCurrentMetricUpdates().isEmpty();
				if (target != null && hasOrdinaryMetrics)
				{
					applyMetrics(target, resolution.getMetricUpdates());
				}
				if (target != null && (hasOrdinaryMetrics || hasPreBatchCurrentMetrics))
				{
					persistence.persistCurrent(currentAccountHash, target);
				}
			}
		}

		return anyLifecycleTransitionApplied;
	}

	private void applyMetrics(Session target, List<MetricUpdate> metrics)
	{
		if (target == null)
		{
			return;
		}
		for (MetricUpdate metric : metrics)
		{
			SessionAggregateUpdater.apply(target.getAggregates(), metric);
			// This durable application, the
			// instant it happens, now fully represents every raw StatChanged
			// gain for this (session, skill) pair up to this point -- so
			// the live, not-yet-represented portion resets to zero here,
			// in the SAME call that just made it durable. See
			// LiveXpTracker.reconcile()'s own javadoc for why this is
			// what guarantees an exact-once DISPLAY total (never double-
			// counts once XP_CHANGE catches up). A no-op for any metric
			// that isn't an XP metric (getXpSkill() null), and a no-op
			// whenever `target` isn't this tracker's current live-XP
			// owner (an abandoned/finalized session's own delayed
			// XP_CHANGE landing here via the contextual-metrics path --
			// never this tracker's live view of whatever is current now).
			if (metric.getXpSkill() != null)
			{
				liveXpTracker.reconcile(target.getSessionId(), metric.getXpSkill());
			}
		}
	}

	/**
	 * CASE C (never-resolved boss-task candidate) -- the deterministic
	 * fallback DESIRED RESOLUTION C asks for: a pending self-consumption
	 * candidate (see ClassifierContext's own javadoc) that never got to
	 * confirm (a same-boss BOSS_KILL) or abort (a real-switch-confirming
	 * loot) before its own boss session stops being current for ANY
	 * OTHER reason -- a pure idle timeout suspending/finalizing it, an
	 * unrelated authoritative switch finalizing it, or an account switch
	 * -- must not silently lose that candidate's buffered metric(s).
	 *
	 * `target` is always one of: the SAME session object the candidate
	 * was armed against (now merely SUSPENDED, still "legitimately
	 * current"), or that same session having just been FINALIZED this
	 * same call (about to be persisted as an immutable record). Never a
	 * brand-new, unrelated session -- this is exactly what "commit to
	 * whatever session is legitimately current at that point, never to a
	 * session that's already been finalized and persisted with
	 * different, already-locked-in contents" means: the flush always
	 * happens strictly BEFORE the persist/finalize call that makes
	 * `target` immutable, so there is never any retroactive mutation of
	 * an already-durable record -- mirroring exactly how
	 * SessionLifecycleEngine's own unrelated SUSPENDED
	 * weak-evidence candidate mechanism commits its own abandoned
	 * candidate's pendingCandidateMetrics.
	 *
	 * A no-op whenever `target` is null (nothing to attach to) or
	 * classifierContext holds no candidate for EXACTLY `target`'s own
	 * activity identity -- in particular, whenever
	 * classifyBossKill()/classifyServerNpcLoot() already resolved
	 * (confirmed or aborted) the candidate themselves during
	 * classification, this is already a no-op by the time it is called
	 * (the candidate is already cleared), so it never double-applies
	 * anything.
	 */
	private void flushAbandonedBossTaskCandidateIfMatching(Session target)
	{
		if (target == null || !classifierContext.hasBossTaskCandidateFor(target.getActivityIdentity()))
		{
			return;
		}
		List<MetricUpdate> buffered = classifierContext.getPendingBossTaskCandidateMetrics();
		classifierContext.clearBossTaskCandidate();
		applyMetrics(target, buffered);
	}

	/**
	 * Called from OsrsTelemetryPlugin.shutDown() -- see the fixed
	 * ordering note there: this MUST
	 * run, with the EventLedger listener still registered, AFTER every
	 * collector flush able to emit final telemetry (Bank, XP window).
	 * Flushes unconditionally (flushAllPendingSignals(), not the
	 * closed-tick-only path -- there is no further GameTick coming) and
	 * persists current state exactly as it is, WITHOUT calling
	 * advanceTime(): disabling the plugin is not itself a real
	 * inactivity gap.
	 */
	public synchronized void flushPendingAndShutdown()
	{
		if (currentAccountHash == NO_ACCOUNT)
		{
			return;
		}
		flushAllPendingSignals();
		persistence.persistCurrent(currentAccountHash, engine.getCurrentSession());
	}

	/**
	 * Mirrors
	 * ContainerCollector's own already-proven ClientShutdown durability
	 * pattern. Entirely separate from ContainerCollector's own hook --
	 * does not touch Bank's path.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		event.waitFor(beginClientShutdownFinalization());
	}

	CompletableFuture<Void> beginClientShutdownFinalization()
	{
		return CompletableFuture.runAsync(this::finalizeForClientShutdownBlocking, scheduledExecutor);
	}

	/**
	 * Runs on RuneLite's shared scheduledExecutor thread, NOT the client
	 * thread -- this is exactly why every field this touches is guarded
	 * by this instance's monitor (see class javadoc's THREADING MODEL).
	 * The single persistCurrentAndWait() call blocks (bounded, 5s) while
	 * holding that monitor; nothing it calls can ever need the monitor
	 * back, so this cannot deadlock against onGameTick()/onEvent() --
	 * they simply wait if they happen to be called during this window.
	 */
	synchronized void finalizeForClientShutdownBlocking()
	{
		if (currentAccountHash == NO_ACCOUNT)
		{
			return;
		}
		flushAllPendingSignals();
		persistence.persistCurrentAndWait(currentAccountHash, engine.getCurrentSession(), 5000);
	}

	// =====================================================================
	// Test-only hooks (package-private; same "no Mockito, drive the real
	// production object directly" convention as every other test in this
	// package).
	// =====================================================================

	/** Bypasses onEvent()'s account filter and Client-dependent gameTick
	 * tagging -- tests build already-tick-tagged SessionSignal instances
	 * directly via its public static factories + withGameTick(). */
	synchronized void testEnqueueSignal(SessionSignal signal)
	{
		pendingSignals.add(signal);
	}

	/** Drives the same closed-tick-flush-then-advance-time pipeline
	 * onGameTick() does, without requiring a real GameTick event or
	 * Client. `currentTick` plays the role of client.getTickCount() --
	 * pass a value strictly greater than any pending signal's own
	 * gameTick to make it eligible for this call to resolve. */
	void testProcessTick(long currentTick, Instant now)
	{
		processTick(currentTick, now);
	}

	/**
	 * See SessionSnapshot's
	 * own javadoc for the exact defect this replaces. The panel's ONLY
	 * read path into runtime session state: builds and returns a fully
	 * immutable, defensively-copied {@link SessionSnapshot} of whatever
	 * session is currently ACTIVE/SUSPENDED (or {@link SessionSnapshot#absent()}
	 * if none), with the copy itself happening INSIDE this synchronized
	 * method -- i.e. under the exact same monitor every runtime-mutating
	 * method in this class (onEvent()/processTick()/ensureAccountLoaded()/
	 * finalizeForClientShutdownBlocking()) already holds while mutating
	 * this same Session/its aggregates. That is what makes the copy
	 * atomic with respect to concurrent runtime mutation: no client-
	 * thread/scheduledExecutor-thread mutation can be interleaved with
	 * {@link SessionSnapshot#capture(Session)}'s field-by-field reads,
	 * because both would require this same monitor.
	 *
	 * This deliberately supersedes the earlier {@code getCurrentSession()},
	 * which handed the UI the SAME live, mutable {@link Session} object
	 * the engine keeps mutating after the synchronized call returned --
	 * a genuine data race the moment the EDT read more than one field off
	 * it (see SessionSnapshot's javadoc for the full explanation). Once
	 * this method returns, the caller holds zero references to any live
	 * mutable Session/SessionAggregates/collection this coordinator owns.
	 *
	 * @return an immutable snapshot of the current session, or
	 * {@link SessionSnapshot#absent()} if none. Never reflects a
	 * FINALIZED session (see {@link SessionLifecycleEngine#getCurrentSession()}).
	 */
	public synchronized SessionSnapshot getCurrentSessionSnapshot()
	{
		Session current = engine.getCurrentSession();
		String sessionId = current == null ? null : current.getSessionId();
		// Threads a
		// defensive copy of lastKnownAbsoluteXpBySkill through the same
		// synchronized capture call as the live-pending-XP map above --
		// see SessionSnapshot#getAbsoluteXpBySkill()'s own javadoc for why
		// this is the one extra piece of state the Current Session panel's
		// level/progress display needs and nothing else already carries.
		return SessionSnapshot.capture(
			current,
			liveXpTracker.getPendingForSession(sessionId),
			new HashMap<>(lastKnownAbsoluteXpBySkill));
	}

	/**
	 * The separate live-XP notification path raw
	 * StatChanged feeds (see SkillsCollector.onStatChanged()'s own
	 * evaluateLiveXpDelta()) -- deliberately NOT routed through
	 * EventLedger/TelemetryEventListener/SessionSignal/the classifier at
	 * all: this is purely cosmetic, in-memory, non-durable bookkeeping
	 * for the player-facing panel, never lifecycle evidence and never a
	 * second XP MetricUpdate. `skillName` is attributed to whichever
	 * session is current AT THE MOMENT this is called (see
	 * LiveXpTracker.recordDelta()'s own javadoc for the exact ownership/
	 * session-switch rule) -- silently dropped if there is no current
	 * session yet to attach it to. `delta` must already be a genuine,
	 * positive gain (SkillsCollector's own evaluateLiveXpDelta() is
	 * responsible for filtering out first-observation reseeds and any
	 * decrease/reset before ever calling this); this method also
	 * independently ignores delta &lt;= 0 as defense in depth (see
	 * LiveXpTracker.recordDelta()).
	 */
	public synchronized void recordLiveXpDelta(String skillName, long delta)
	{
		Session current = engine.getCurrentSession();
		String sessionId = current == null ? null : current.getSessionId();
		liveXpTracker.recordDelta(sessionId, skillName, delta);
	}

	/**
	 * Called by SkillsCollector.onStatChanged() for EVERY
	 * skill on every raw StatChanged observation -- deliberately
	 * UNCONDITIONALLY, unlike recordLiveXpDelta() above (which is gated
	 * on a positive live delta): this is not a display concern, it is
	 * "the latest known absolute XP for this skill, full stop," and even
	 * a skill's very first (seed-only, zero-delta) observation is a
	 * genuine, exact absolute baseline worth keeping. See
	 * lastKnownAbsoluteXpBySkill's own javadoc for how this feeds the
	 * exact session-boundary split; a no-op for a null skill name.
	 */
	public synchronized void noteAbsoluteXp(String skillName, long absoluteXp)
	{
		if (skillName == null)
		{
			return;
		}
		lastKnownAbsoluteXpBySkill.put(skillName, absoluteXp);
	}

	/**
	 * The single backend hook
	 * behind the Current Session header's "Re-evaluate Session" button --
	 * "look at what I am doing RIGHT NOW and classify it again," never
	 * "forget all session rules." Called from CurrentSessionView's button
	 * handler via a non-blocking ClientThread.invokeLater() dispatch (see
	 * OsrsTelemetryPanel's own wiring) -- never directly on the EDT.
	 *
	 * EVIDENCE SOURCE: `currentNpcName`/`currentNpcId` are expected to be
	 * read by the caller, on the calling thread, from
	 * NpcInteractionTargetCollector.getCurrentNpcName()/getCurrentNpcId()
	 * immediately before this call (that collector's own javadoc documents
	 * both as safe off the client thread) -- the genuine, live, right-now
	 * interaction target, never a replayed/stale signal. `now` is expected
	 * to be Instant.now() at the same moment.
	 *
	 * INSUFFICIENT EVIDENCE -&gt; SAFE NO-OP: no current attackable NPC
	 * target at all (`currentNpcName` null/blank), or no account loaded
	 * yet, leaves the current session (if any) completely untouched -- no
	 * identity is ever invented or guessed. Likewise, whenever
	 * ActivitySignalClassifier.classifyManualNpcTarget() itself determines
	 * this observation makes no safe identity claim right now (its own
	 * METRIC_ONLY carve-out for generic combat evidence against a
	 * SUSPENDED specific session -- see decideCombatBranch()'s own
	 * javadoc), this is honored exactly like the ordinary pipeline does:
	 * no lifecycle mutation.
	 *
	 * REUSES EXISTING RULES, NEVER A COMPETING CLASSIFIER: identity
	 * construction and the refine-vs-switch-vs-ignore decision are both
	 * delegated entirely to ActivitySignalClassifier.classifyManualNpcTarget()
	 * (see its own javadoc) -- this method only threads the result through
	 * SessionLifecycleEngine exactly the way resolveAndApplyBatches() does
	 * for an ordinary RESOLVED batch (same applyMetrics()/
	 * flushAbandonedBossTaskCandidateIfMatching()/persistence calls). The
	 * ONLY thing manual about this call is EvidenceStrength.SPECIFIC,
	 * which is what lets it bypass SessionLifecycleEngine's ORDINARY-only
	 * weak-evidence candidate/stickiness gate for this one observation
	 * (see EvidenceStrength's own javadoc) -- every other safety rule
	 * (never fabricate SLAYER from bare task assignment; never downgrade a
	 * more-specific ACTIVE session via generic evidence; Slayer
	 * task-family membership; boss/task affinity) remains fully enforced,
	 * completely unchanged. Using SPECIFIC strength also means any
	 * still-pending weak-evidence candidate (stale candidate-activity
	 * state) is unconditionally ABANDONED by this call exactly as any
	 * other authoritative/specific arrival would abandon it -- its
	 * buffered metrics are safely, honestly committed once as contextual
	 * metrics to whichever session they actually occurred during (see
	 * SessionLifecycleEngine's own "AUTHORITATIVE/SPECIFIC evidence ...
	 * immediate switch" branches), never lost and never duplicated. This
	 * call also unconditionally refreshes the recent-NPC-target watch (see
	 * ClassifierContext.onNpcInteractionTarget()) to this exact
	 * observation, clearing any staleness there too.
	 *
	 * NEVER REWRITES HISTORY: this call always carries an EMPTY metrics
	 * list -- it can start/refine/switch session IDENTITY, but can never
	 * itself add a single point of XP/loot to any session's aggregates.
	 * Combined with SessionLifecycleEngine's own transition rules (a real
	 * switch finalizes the OLD session exactly as it stood and starts a
	 * brand-new one from `now` forward; an already-FINALIZED session is
	 * never touched by any later call), this can only ever affect the
	 * session current AT `now`, going forward -- never earlier XP, loot,
	 * duration, or an already-finalized record.
	 */
	public synchronized void reEvaluateCurrentSession(String currentNpcName, Integer currentNpcId, Instant now)
	{
		if (currentAccountHash == NO_ACCOUNT)
		{
			return;
		}
		if (currentNpcName == null || currentNpcName.trim().isEmpty())
		{
			// No current live combat-target evidence -- never invent an
			// activity. Safe no-op.
			return;
		}

		Session before = engine.getCurrentSession();
		SignalClassification classification = classifier.classifyManualNpcTarget(currentNpcName, before, EvidenceStrength.SPECIFIC);

		if (classification.getDecisionKind() != SignalDecisionKind.START_OR_HEARTBEAT
			&& classification.getDecisionKind() != SignalDecisionKind.REFINE)
		{
			// e.g. METRIC_ONLY (generic combat evidence against a
			// SUSPENDED specific session) -- no safe identity claim right
			// now. Honor that exactly like the ordinary pipeline does:
			// nothing invented, nothing mutated. This call never carries
			// metrics of its own, so there is nothing to apply here even
			// for METRIC_ONLY.
			return;
		}

		SessionState beforeState = before == null ? null : before.getState();
		LifecycleResult result = classification.getDecisionKind() == SignalDecisionKind.REFINE
			? engine.refineIdentity(classification.getIdentity(), now, Collections.<MetricUpdate>emptyList())
			: engine.onQualifyingActivity(classification.getIdentity(), now, Collections.<MetricUpdate>emptyList(), classification.getEvidenceStrength());

		if (result.getCurrent() != null
			&& (before == null || !before.getSessionId().equals(result.getCurrent().getSessionId())))
		{
			classifierContext.noteSessionSwitchXpBaseline(result.getCurrent().getSessionId(), lastKnownAbsoluteXpBySkill);
		}

		// Same metric-routing discipline as resolveAndApplyBatches()'s own
		// RESOLVED branch: metricsForCurrent is always empty for this call
		// (see method javadoc's NEVER REWRITES HISTORY section), but an
		// abandoned candidate's own previously-buffered, real metrics
		// (contextualMetricsTarget/contextualMetrics) must still be
		// committed exactly once, never dropped.
		applyMetrics(result.getCurrent(), result.getMetricsForCurrent());
		if (result.getContextualMetricsTarget() != null)
		{
			applyMetrics(result.getContextualMetricsTarget(), result.getContextualMetrics());
		}

		if (result.getFinalized() != null)
		{
			flushAbandonedBossTaskCandidateIfMatching(result.getFinalized());
			persistence.persistFinalized(currentAccountHash, result.getFinalized(), result.getCurrent());
		}
		else if (result.getCurrent() != null && result.getCurrent().getState() != beforeState)
		{
			persistence.persistCurrent(currentAccountHash, result.getCurrent());
		}

		classifierContext.onNpcInteractionTarget(currentNpcId, currentNpcName, safeTickCount(), now);
	}

	synchronized Session testCurrentSession()
	{
		return engine.getCurrentSession();
	}

	synchronized long testPendingSignalCount()
	{
		return pendingSignals.size();
	}
}
