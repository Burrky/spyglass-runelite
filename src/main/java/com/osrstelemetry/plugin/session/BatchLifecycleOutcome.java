package com.osrstelemetry.plugin.session;

/**
 * Typed
 * status for SessionSignalBatchResolver.BatchResolution so "nothing
 * happened" and "several incompatible things happened and we
 * deliberately refused to guess" are never both represented as a bare
 * null.
 *
 *   - RESOLVED: exactly one identity could be honestly determined for
 *     this batch (trivially, by elimination, by rank within the
 *     combat branch, or by exact current-session stickiness). A
 *     lifecycle classification and a deterministic timestamp are
 *     present.
 *   - NO_EVIDENCE: no signal in the batch carried a lifecycle decision
 *     at all (e.g. loot/metric-only signals only). Nothing to resolve
 *     -- not an ambiguity.
 *   - AMBIGUOUS: two or more incompatible, comparably-strong
 *     identities were proposed and neither rank nor current-session
 *     stickiness can honestly choose between them (e.g. two named
 *     Skilling identities from one tick with no running session, or
 *     two different bosses with no current session matching either).
 *     No lifecycle classification/timestamp is produced -- the batch's
 *     metrics are still returned (see BatchResolution), but no
 *     heartbeat is fabricated to attach them to.
 */
public enum BatchLifecycleOutcome
{
	RESOLVED,
	NO_EVIDENCE,
	AMBIGUOUS
}
