package com.osrstelemetry.plugin.loottracker;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * ONE atomic {@code SERVER_NPC_LOOT} notification, exactly as it happened -- the
 * Loot Tracker's fundamental unit of truth. Deliberately preserves the
 * SAME atomic event boundary {@code EventPayloads.ServerNpcLoot}
 * itself carries (one record = one real {@code net.runelite.client.
 * events.ServerNpcLoot} notification, i.e. one RuneLite-Loot-Tracker-
 * equivalent "kill" -- see LootTrackerIndex's own javadoc for the full
 * honest-kill-count audit) -- a "grouped" view is always a DERIVED
 * aggregation over a list of these records, never a replacement
 * representation, so the individual-drop view (spec requirement) can
 * always reconstruct real event boundaries rather than fabricating
 * them.
 *
 * eventId is the durable-rebuild dedup key (see LootTrackerPersistence):
 * the SAME UUID TelemetryEvent/EventLedger already generates for every
 * durably-appended envelope. It is nullable ONLY for the narrow case of
 * a live-applied record arriving through TelemetryEventListener, which
 * (by that interface's own existing contract -- see its javadoc) does
 * not currently pass the envelope's eventId through to listeners.
 * LootTrackerCoordinator synthesizes a fresh, process-local, guaranteed-
 * unique id for that case (see its own javadoc for why this is still
 * exact-once-safe) rather than leaving eventId genuinely null, so in
 * practice every record this project ever constructs has a real,
 * non-null eventId -- null is only ever a theoretical/defensive state
 * for this class's own dedup logic to fail safe against (treated as
 * "never dedup-skip" -- see LootTrackerIndex.apply()).
 */
public final class LootTrackerRecord
{
	private final String eventId;
	private final String sourceName;
	private final Integer sourceId;
	private final Instant observedAt;
	private final List<LootTrackerItem> items;

	public LootTrackerRecord(String eventId, String sourceName, Integer sourceId, Instant observedAt, List<LootTrackerItem> items)
	{
		this.eventId = eventId;
		this.sourceName = sourceName;
		this.sourceId = sourceId;
		this.observedAt = observedAt;
		this.items = Collections.unmodifiableList(new java.util.ArrayList<>(items));
	}

	public String getEventId()
	{
		return eventId;
	}

	public String getSourceName()
	{
		return sourceName;
	}

	public Integer getSourceId()
	{
		return sourceId;
	}

	public Instant getObservedAt()
	{
		return observedAt;
	}

	/** Immutable -- the exact item stacks from one real drop notification, in original order. */
	public List<LootTrackerItem> getItems()
	{
		return items;
	}
}
