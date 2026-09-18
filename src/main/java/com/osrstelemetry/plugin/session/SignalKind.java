package com.osrstelemetry.plugin.session;

/**
 * The closed
 * set of signal kinds ActivitySignalClassifier understands. This is a
 * classifier-owned vocabulary, deliberately decoupled from
 * com.osrstelemetry.plugin.telemetry.EventType -- a future RuneLite
 * router (not yet built) is what translates real EventBus/
 * EventType occurrences into SessionSignal instances carrying one of
 * these kinds. Nothing in the session package imports EventType.
 *
 * Public because a future router living outside this package is what
 * constructs SessionSignal instances and calls
 * ActivitySignalClassifier -- only the pure
 * classifier side is built here, not that router.
 *
 * Combat vs. non-combat XP_CHANGE is deliberately NOT split into two
 * kinds here -- the classifier itself enumerates real OSRS combat
 * skills to decide that from SessionSignal.getSkill(), so
 * only one XP_CHANGE kind is needed.
 */
public enum SignalKind
{
	XP_CHANGE,
	SLAYER_TASK_ASSIGNED,
	SLAYER_TASK_PROGRESS,
	SLAYER_TASK_COMPLETED,
	BOSS_KILL,
	BOSS_ACTIVITY_CONTEXT,
	RAID_COMPLETION,
	ACTIVITY_COMPLETION,
	QUEST_COMPLETED,
	NPC_DEATH,
	NPC_LOOT_ATTRIBUTED,
	SERVER_NPC_LOOT,
	COLLECTION_LOG_NEW_ITEM,
	COMBAT_ACHIEVEMENT_COMPLETED,
	STORAGE_SNAPSHOT,

	/**
	 * Non-authoritative,
	 * context-only evidence that the local player's RuneLite-observed
	 * interaction target changed to a specific, genuinely attackable
	 * NPC (source: net.runelite.api.events.InteractingChanged, filtered
	 * to the local player and to an NPC composition/actions combination
	 * that actually offers an "Attack" option -- see
	 * NpcInteractionTargetCollector). This NEVER by itself proposes a
	 * SignalDecisionKind.START_OR_HEARTBEAT/REFINE -- ActivitySignalClassifier
	 * always classifies it IGNORE_FOR_SESSION, exactly like
	 * SLAYER_TASK_ASSIGNED, and only ever uses it to update
	 * ClassifierContext's own short-lived "recent target" state (see
	 * that class's javadoc). A later, independent XP_CHANGE for a real
	 * combat skill is what actually proposes a named generic COMBAT
	 * identity from this context -- a click/interaction alone is never
	 * strong enough (EvidenceStrength.CONTEXTUAL) to establish or
	 * switch a session by itself: the player can misclick, bosses can
	 * have adds, and merely observing an interaction is not proof of a
	 * kill or even of genuine combat.
	 */
	NPC_INTERACTION_TARGET,

	/**
	 * Immediate, lifecycle-only, non-metric-carrying
	 * evidence that the local player's combat-skill XP was JUST observed
	 * to increase (source: net.runelite.api.events.StatChanged, filtered
	 * to a genuine combat skill -- Attack/Strength/Defence/Ranged/
	 * Hitpoints, NEVER Magic; see NpcInteractionTargetCollector's sibling
	 * SkillsCollector.onStatChanged() for the exact seed-then-diff
	 * detection). THE PROBLEM THIS SOLVES: XP_CHANGE is an AGGREGATED
	 * event, only emitted on SkillsCollector's own slow flush cadence
	 * (XP_FLUSH_INTERVAL_TICKS -- roughly 50 ticks / ~30 real seconds).
	 * ClassifierContext's recent-NPC-target watch (see
	 * NPC_INTERACTION_TARGET's own javadoc) is bounded to a much shorter
	 * ~20-tick/~12-second window -- so in real gameplay, the FIRST
	 * combat XP evidence to reach the classifier for a freshly-engaged
	 * NPC could easily already be ~30 seconds too late, silently losing
	 * the NPC name and falling back to plain generic COMBAT even though
	 * the player has been fighting continuously the whole time. This
	 * signal exists purely to close that gap: it reaches the classifier
	 * within a tick or two of the real StatChanged, in time to pair with
	 * a still-fresh recent-target watch and establish/heartbeat a NAMED
	 * generic COMBAT identity immediately (EvidenceStrength.ORDINARY --
	 * see ActivitySignalClassifier's own javadoc), well before the slow
	 * aggregated XP_CHANGE for the same fight ever arrives.
	 *
	 * NEVER AN XP METRIC (HARD RULE): this signal carries a skill NAME
	 * only (reusing SessionSignal's existing `skill` field) and, unlike
	 * XP_CHANGE, is built with NO xpDelta at all -- see
	 * SessionSignal.rawCombatXpObserved()'s own javadoc. It is therefore
	 * structurally impossible for this signal to ever produce an
	 * xp-kind MetricUpdate: ActivitySignalClassifier's classification for
	 * this kind always passes an EMPTY metrics list to decideCombatBranch(),
	 * so it can start/heartbeat/refine session IDENTITY but can never
	 * itself add a single point of XP to any session's aggregates.
	 * XP_CHANGE remains the SOLE source of accumulated XP metrics --
	 * completely unmodified by this signal's existence.
	 *
	 * ALSO WHAT FIXES SELF-PERPETUATION: this is now the ONLY signal kind
	 * whose classification is allowed to REFRESH (not merely read) the
	 * recent-target watch's freshness clock (see
	 * ClassifierContext.consumeRecentTargetNpcNameIfFresh(), called
	 * exclusively from this kind's classify() case). XP_CHANGE's own
	 * combat branch now only PEEKS at the watch (see
	 * ClassifierContext.peekRecentTargetNpcNameIfFresh()) -- it can still
	 * read a still-fresh watch, but can never itself extend the watch's
	 * life. Since this signal fires at real per-hit cadence (tightly
	 * coupled to actual, current combat activity) while XP_CHANGE fires
	 * on a slow, coarse aggregation timer that can span a genuine target
	 * change or a genuine disengagement, only the fast signal is trusted
	 * to prove "the player is still, right now, engaging that target."
	 * A stale watch that this signal itself stopped refreshing (because
	 * the player genuinely stopped fighting) can no longer be
	 * artificially kept alive merely because a slow, late XP_CHANGE
	 * happens to arrive and re-read it.
	 */
	RAW_COMBAT_XP_OBSERVED
}
