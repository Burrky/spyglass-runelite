package com.osrstelemetry.plugin.session;

import java.util.Collections;
import java.util.List;

/**
 * The return value of every SessionLifecycleEngine
 * transition method. Two original, both-nullable pieces of
 * information:
 *
 *   - current: the session now considered "in progress" (ACTIVE or
 *     SUSPENDED), or null if there is none (e.g. right after a
 *     timeout-finalization with no new activity to replace it).
 *   - finalized: a session that was JUST transitioned to FINALIZED as
 *     a side effect of this call, or null if nothing finalized. This
 *     is the exact, and only, signal a caller needs to know "write an
 *     immutable sessions/{sessionId}.json record now" (see
 *     SessionPersistence.persistFinalized()) — a Session is never
 *     returned here more than once, since the engine always replaces
 *     its internal `current` reference with a different object before
 *     returning.
 *
 * PLUS three fields that
 * exist ONLY because a weak-evidence candidate can leave a calling
 * signal's own metrics in a genuinely undecided state -- see
 * SessionLifecycleEngine's own candidate-lifecycle javadoc for the
 * full design:
 *
 *   - metricsForCurrent: metrics the caller should apply to
 *     getCurrent()'s aggregates right now (never null -- empty when
 *     there is nothing to apply, e.g. this call only armed or
 *     replaced a pending candidate, buffering its metrics instead of
 *     applying them anywhere yet).
 *   - contextualMetricsTarget / contextualMetrics: an ABANDONED
 *     candidate's previously-buffered metrics, committed EXACTLY ONCE
 *     to whichever session was the established one BEFORE this call
 *     (contextualMetricsTarget is that same Session object -- it is
 *     always either the just-returned getCurrent() unchanged, or the
 *     just-returned getFinalized(), never a third object) -- as plain
 *     aggregate contributions, with no lifecycle side effects
 *     whatsoever (SessionAggregateUpdater, the only thing a caller
 *     ever applies these through, never touches state/lastActiveAt/
 *     activityIdentity/duration). contextualMetricsTarget is null,
 *     and contextualMetrics empty, whenever there was no abandoned
 *     candidate to commit.
 *
 * PLUS one field from the interrupted-activity-resume feature --
 * see {@link #getAdditionalFinalized()}'s own javadoc: a second,
 * independently-finalized session that can legitimately co-occur
 * with {@link #getFinalized()} in the same call, since the one
 * parked interrupted-prior-session candidate runs on its own
 * lifecycle clock, separate from `current`'s.
 *
 * Deliberately a plain immutable value rather than reusing Session
 * itself with booleans bolted on — keeping "what to treat as
 * current", "what to durably persist as finalized", and "which
 * metrics go where" as clearly separate questions a caller must
 * handle independently.
 */
public final class LifecycleResult
{
	private final Session current;
	private final Session finalized;
	private final List<MetricUpdate> metricsForCurrent;
	private final Session contextualMetricsTarget;
	private final List<MetricUpdate> contextualMetrics;
	private final Session additionalFinalized;

	private LifecycleResult(Session current, Session finalized, List<MetricUpdate> metricsForCurrent,
		Session contextualMetricsTarget, List<MetricUpdate> contextualMetrics, Session additionalFinalized)
	{
		this.current = current;
		this.finalized = finalized;
		this.metricsForCurrent = metricsForCurrent == null ? Collections.emptyList() : metricsForCurrent;
		this.contextualMetricsTarget = contextualMetricsTarget;
		this.contextualMetrics = contextualMetrics == null ? Collections.emptyList() : contextualMetrics;
		this.additionalFinalized = additionalFinalized;
	}

	/** No metrics involved at all (e.g. advanceTime()'s ordinary, no-candidate path). */
	static LifecycleResult of(Session current, Session finalized)
	{
		return new LifecycleResult(current, finalized, null, null, null, null);
	}

	/** This call's own metrics apply directly to the returned `current` -- no candidate involved. */
	static LifecycleResult withMetrics(Session current, Session finalized, List<MetricUpdate> metricsForCurrent)
	{
		return new LifecycleResult(current, finalized, metricsForCurrent, null, null, null);
	}

	/** Full form: both this call's own metrics AND an abandoned candidate's committed-once metrics. */
	static LifecycleResult withMetricsAndContextual(Session current, Session finalized,
		List<MetricUpdate> metricsForCurrent, Session contextualMetricsTarget, List<MetricUpdate> contextualMetrics)
	{
		return new LifecycleResult(current, finalized, metricsForCurrent, contextualMetricsTarget, contextualMetrics, null);
	}

	public Session getCurrent()
	{
		return current;
	}

	public Session getFinalized()
	{
		return finalized;
	}

	List<MetricUpdate> getMetricsForCurrent()
	{
		return metricsForCurrent;
	}

	Session getContextualMetricsTarget()
	{
		return contextualMetricsTarget;
	}

	List<MetricUpdate> getContextualMetrics()
	{
		return contextualMetrics;
	}

	/**
	 * ADDED (interrupted-activity-resume feature). A SECOND session
	 * finalized as a side effect of this SAME call, independent of
	 * {@link #getFinalized()} -- exists only because the one parked
	 * interrupted-prior-session candidate (see SessionLifecycleEngine's
	 * own "INTERRUPTED-RESUME CANDIDATE" section) has its own
	 * independent lifecycle clock from `current`'s, so a single call
	 * can legitimately finalize BOTH `current`'s own session (via the
	 * ordinary ACTIVE/SUSPENDED machinery this class already documented)
	 * AND the parked candidate (its own resume window independently
	 * expiring, or a second real switch permanently displacing it) in
	 * the same instant. Never the same object as {@link #getFinalized()}
	 * (there are only ever two distinct finalizable sessions in play at
	 * once: `current`'s own, and the one parked candidate's). Null in
	 * every case where nothing about the parked candidate changed this
	 * call.
	 */
	public Session getAdditionalFinalized()
	{
		return additionalFinalized;
	}

	/**
	 * Attaches a second, independently-finalized session (see
	 * {@link #getAdditionalFinalized()}) to an already-built result.
	 * `additionalFinalized == null` is a safe no-op (returns `this`
	 * unchanged) so every call site can use this unconditionally.
	 */
	LifecycleResult withAdditionalFinalized(Session additionalFinalized)
	{
		if (additionalFinalized == null)
		{
			return this;
		}
		return new LifecycleResult(current, finalized, metricsForCurrent, contextualMetricsTarget, contextualMetrics, additionalFinalized);
	}

	/**
	 * Attaches contextual metrics (see {@link #getContextualMetricsTarget()})
	 * to an already-built result that did not otherwise need to carry
	 * any -- used by the interrupted-resume real-switch path, whose
	 * contextual-metrics target may be the session that was JUST parked
	 * as the interrupted candidate rather than {@link #getFinalized()}
	 * itself (a legitimate third possibility this class's original
	 * contract predates -- {@code contextualMetricsTarget} is always
	 * some Session object this same call is returning a live reference
	 * to, in whichever capacity: current, finalized, additionalFinalized,
	 * or the newly-parked candidate).
	 */
	LifecycleResult withContextualMetrics(Session contextualMetricsTarget, List<MetricUpdate> contextualMetrics)
	{
		return new LifecycleResult(current, finalized, metricsForCurrent, contextualMetricsTarget, contextualMetrics, additionalFinalized);
	}
}
