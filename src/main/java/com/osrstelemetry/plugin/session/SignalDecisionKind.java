package com.osrstelemetry.plugin.session;

/**
 * What
 * ActivitySignalClassifier decided a given SessionSignal means for
 * session LIFECYCLE (never for aggregates -- see MetricUpdate, applied
 * independently). Exactly the five kinds:
 *
 *   - START_OR_HEARTBEAT: feed the accompanying identity into
 *     SessionLifecycleEngine.onQualifyingActivity(). Covers "start a
 *     brand-new session," "same-identity heartbeat," AND "a real
 *     switch to a different, incompatible identity" -- the engine
 *     itself already distinguishes those cases by identity equality
 *     against its current session, so the classifier does not need a
 *     separate decision kind for each.
 *   - REFINE: feed the accompanying identity into
 *     SessionLifecycleEngine.refineIdentity() instead -- the
 *     classifier has determined this is the SAME continuous activity,
 *     now known more specifically.
 *   - METRIC_ONLY: apply the accompanying MetricUpdate (if any) to the
 *     current session's aggregates; never touch lifecycle at all.
 *   - COMPLETION_ONLY: a one-off completion -- never a continuous
 *     heartbeat, never starts/resumes/refines/finalizes anything by
 *     itself. See CompletionNature for the further, REQUIRED distinction between a genuinely
 *     standalone completion (raid/activity/quest) and a completion
 *     that only annotates an already-running session (Slayer task
 *     completed) -- code consuming COMPLETION_ONLY must branch on
 *     CompletionNature rather than assuming every COMPLETION_ONLY
 *     signal deserves its own standalone Recent Activity entry.
 *   - IGNORE_FOR_SESSION: not session-relevant at all (storage
 *     snapshots, NPC_DEATH, NPC_LOOT_ATTRIBUTED, an unassigned
 *     Slayer-task-assigned signal that only updates classifier
 *     context).
 */
public enum SignalDecisionKind
{
	START_OR_HEARTBEAT,
	REFINE,
	METRIC_ONLY,
	COMPLETION_ONLY,
	IGNORE_FOR_SESSION
}
