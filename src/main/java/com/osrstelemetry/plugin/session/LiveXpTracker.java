package com.osrstelemetry.plugin.session;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A small, purely in-memory, NON-DURABLE ledger of "XP
 * gained since the last XP_CHANGE flush for this skill," scoped to
 * whichever session currently owns it. This exists SOLELY to close the
 * player-visible latency gap between a real XP gain (immediate,
 * per-skill, via raw StatChanged) and SkillsCollector's own slow,
 * aggregated XP_CHANGE flush (~30s cadence, unchanged by this class --
 * see SkillsCollector.closeXpWindowsIfDue()) actually reaching the
 * session's durable {@link SessionAggregates#getXpGainedBySkill()}.
 *
 * HARD RULE: this class NEVER becomes durable, NEVER writes to
 * SessionAggregates, NEVER is persisted, and NEVER causes a second XP
 * MetricUpdate to be applied anywhere. It is consulted ONLY when
 * building a read-only {@link SessionSnapshot} for the player-facing
 * panel (see {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()}).
 * The one and only durable/canonical XP accounting path remains exactly
 * as it was: raw StatChanged -&gt; SkillsCollector's own
 * windowBaselineXp/windowStart -&gt; XP_CHANGE event (~30s cadence) -&gt;
 * SessionEventMapper -&gt; classifyXpChange() -&gt; MetricUpdate.xp() -&gt;
 * SessionAggregateUpdater.apply() -&gt; SessionAggregates.xpGainedBySkill.
 * This class is never in that chain.
 *
 * OWNERSHIP MODEL (see recordDelta()'s own javadoc for the exact rule):
 * this tracker holds pending XP for exactly ONE "owner" session id at a
 * time -- whichever session was current the last time a delta was
 * recorded. The moment a delta arrives for a DIFFERENT session id (a
 * genuine session switch -- the old session finalized/abandoned, a new
 * one now current), every previously-tracked pending amount is
 * discarded before tracking restarts fresh for the new owner. This is
 * deliberately simpler than a multi-session map: the old session's own
 * truth is never at risk (its real XP still reaches its own durable
 * aggregates via the ordinary XP_CHANGE path, on its own schedule,
 * completely independent of this class), and nothing durable is ever
 * lost by discarding a stale LIVE-ONLY figure that is no longer even
 * being displayed (only the CURRENT session's snapshot is ever built).
 * A SUSPENDED session that later RESUMES keeps the SAME sessionId
 * throughout (see SessionLifecycleEngine), so an ordinary suspend/
 * resume never triggers this discard -- only a genuine identity switch
 * (a new/different session becoming current) does.
 *
 * RECONCILIATION (see reconcile()'s own javadoc): the moment a REAL
 * XP_CHANGE-derived MetricUpdate is applied to this tracker's current
 * owner session for some skill, that durable application now fully
 * represents every raw gain up to that point -- so the live, "not yet
 * represented" portion for that exact (session, skill) pair resets to
 * zero. This is what guarantees the exact-once DISPLAY total the task
 * requires: T0 canonical=100; T1 a live +40 gain makes the panel show
 * 140 (100 durable + 40 live-pending); T2 XP_CHANGE flushes the same
 * +40 into the durable aggregate (canonical becomes 140) AND this
 * tracker's pending amount for that skill resets to 0 in the SAME
 * reconcile() call -- so the panel's own displayed total (140 durable +
 * 0 pending) never jumps to 180. See
 * CurrentSessionSnapshotTest/SessionSnapshotTest for the exact assertions.
 */
final class LiveXpTracker
{
	private String ownerSessionId;
	private final Map<String, Long> pendingXpBySkill = new HashMap<>();

	/**
	 * Records an immediate, live XP gain of {@code delta} for
	 * {@code skill}, attributed to {@code sessionId}.
	 *
	 * <p>{@code sessionId == null} means there is no current session at
	 * all to attach this gain to -- dropped, never buffered
	 * speculatively for some future session (there is no honest
	 * ownership to assign it to yet; see class javadoc).
	 *
	 * <p>{@code delta <= 0} is always ignored -- this layer must never
	 * fabricate positive live XP from a decrease, a reset, or a
	 * first-observation reseed (those are the caller's -- SkillsCollector's
	 * own evaluateLiveXpDelta() -- responsibility to have already
	 * filtered out before calling this method at all; this is a second,
	 * defense-in-depth guarantee).
	 *
	 * <p>A {@code sessionId} different from the currently tracked owner
	 * is treated as a genuine session switch: every previously pending
	 * amount is discarded (see class javadoc's OWNERSHIP MODEL) before
	 * this delta is recorded as the new owner's first entry.
	 */
	void recordDelta(String sessionId, String skill, long delta)
	{
		if (sessionId == null || skill == null || delta <= 0)
		{
			return;
		}
		if (!sessionId.equals(ownerSessionId))
		{
			pendingXpBySkill.clear();
			ownerSessionId = sessionId;
		}
		Long existing = pendingXpBySkill.get(skill);
		pendingXpBySkill.put(skill, (existing == null ? 0L : existing) + delta);
	}

	/**
	 * Called once a real, durable XP metric for {@code skill} has just
	 * been applied to {@code sessionId}'s own canonical aggregates
	 * (see {@link SessionRuntimeCoordinator}'s applyMetrics()). Resets
	 * the live pending amount for that exact (session, skill) pair to
	 * zero -- see class javadoc's RECONCILIATION section for why this
	 * is what guarantees an exact-once display total.
	 *
	 * <p>A no-op whenever {@code sessionId} is not the currently
	 * tracked owner: that XP metric belongs to some OTHER session's own
	 * reconciliation (an abandoned/finalized session's delayed XP_CHANGE
	 * finally arriving after this tracker has already moved on to a new
	 * current owner) -- never this tracker's live view of the session
	 * that is actually current right now.
	 */
	void reconcile(String sessionId, String skill)
	{
		if (sessionId == null || skill == null || !sessionId.equals(ownerSessionId))
		{
			return;
		}
		pendingXpBySkill.remove(skill);
	}

	/**
	 * The live pending XP for {@code skill} under {@code sessionId} --
	 * {@code 0} whenever {@code sessionId} is not the currently tracked
	 * owner, or no live delta has been recorded for that skill since
	 * the last reconcile()/session switch.
	 */
	long getPending(String sessionId, String skill)
	{
		if (sessionId == null || !sessionId.equals(ownerSessionId))
		{
			return 0L;
		}
		Long pending = pendingXpBySkill.get(skill);
		return pending == null ? 0L : pending;
	}

	/**
	 * Every skill with a nonzero live pending amount currently tracked
	 * for {@code sessionId} -- an unmodifiable, defensively-copied
	 * snapshot (never a live view over this tracker's own internal
	 * map). Empty whenever {@code sessionId} is not the currently
	 * tracked owner.
	 */
	Map<String, Long> getPendingForSession(String sessionId)
	{
		if (sessionId == null || !sessionId.equals(ownerSessionId))
		{
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(new HashMap<>(pendingXpBySkill));
	}

	/**
	 * Clears all tracked state -- called on account switch (see
	 * {@link SessionRuntimeCoordinator#ensureAccountLoaded(long)}), so
	 * a brand-new account never inherits a stale owner/pending amount
	 * left over from the previous account.
	 */
	void reset()
	{
		ownerSessionId = null;
		pendingXpBySkill.clear();
	}
}
