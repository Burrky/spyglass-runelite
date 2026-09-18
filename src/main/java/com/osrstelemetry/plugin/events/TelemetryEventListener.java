package com.osrstelemetry.plugin.events;

import java.time.Instant;

/**
 * A local, in-process observer of telemetry events as they are durably appended by
 * EventLedger -- NOT a second event bus, NOT a replacement for
 * anything that reads events.jsonl, and NOT itself aware of sessions,
 * RuneLite, or any Client API. EventLedger.append()/appendAndWait()
 * call every registered listener SYNCHRONOUSLY, on the SAME calling
 * thread that invoked append() (see EventLedger's own javadoc for
 * exactly where in the write path this happens), immediately after
 * the durable envelope has been built -- this preserves natural
 * per-thread occurrence ordering (session ordering matches
 * occurrence/tick order rather than executor completion races) for
 * free, since every session-relevant collector in this project already
 * appends from the RuneLite client thread (verified by audit).
 *
 * FAILURE ISOLATION: EventLedger wraps every listener call
 * in a try/catch and logs rather than propagates -- an exception here
 * can never corrupt, drop, or delay the durable telemetry write, which
 * has already been queued for disk before any listener runs. A
 * listener must not assume it can block the calling thread for long;
 * on the RuneLite client thread specifically, prefer doing only
 * cheap, in-memory work here (see SessionRuntimeCoordinator, the one
 * current implementation) and defer disk I/O to the existing async
 * LocalStateStore-backed persistence path.
 */
public interface TelemetryEventListener
{
	void onEvent(long accountHash, EventType type, Object payload, Instant observedAt);
}
