package com.osrstelemetry.plugin.events;

import lombok.Data;

/**
 * eventId is client-generated (UUIDv4) and is the natural idempotency
 * key for anything that later consumes this stream (a retried write
 * keyed on the same eventId is a no-op, not a duplicate). Nothing about
 * idempotency needs to happen locally — this ledger is a single local
 * producer appending once per real event.
 */
@Data
public class TelemetryEvent
{
	private final String eventId;
	private final String accountHash;
	private final EventType eventType;
	private final String occurredAt;
	private final String sessionId;
	private final int schemaVersion = 1;
	private final Object payload;
}
