package com.osrstelemetry.plugin.session;

/**
 * Distinguishes the two
 * genuinely different meanings a COMPLETION_ONLY SignalClassification
 * can carry, so a future Recent Activity pass cannot accidentally
 * generate duplicate history (one finalized Slayer session PLUS a
 * redundant standalone "Slayer task completed" entry) merely because
 * an event's name contains the word "completed."
 *
 *   - STANDALONE_ACTIVITY: a genuinely discrete completion worth its
 *     own future Recent Activity entry -- RAID_COMPLETION,
 *     ACTIVITY_COMPLETION, QUEST_COMPLETED.
 *   - SESSION_ANNOTATION_ONLY: a completion that only marks progress
 *     on / ends an ALREADY-modeled, already-running session --
 *     SLAYER_TASK_COMPLETED. This must never, by itself, spawn a
 *     second standalone player-facing entry; it is metadata about the
 *     Slayer session that was (or still is) running.
 */
public enum CompletionNature
{
	STANDALONE_ACTIVITY,
	SESSION_ANNOTATION_ONLY
}
