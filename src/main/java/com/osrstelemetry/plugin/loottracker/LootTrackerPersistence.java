package com.osrstelemetry.plugin.loottracker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Durable rebuild: reads this account's {@code events.jsonl} directly (the SAME
 * append-only file {@code EventLedger} writes -- see
 * TelemetryPaths.eventsFile()'s own javadoc), extracts every
 * {@code SERVER_NPC_LOOT} line, and applies each one to a
 * {@link LootTrackerIndex}. This is what lets the Loot Tracker survive
 * a client restart with its full history intact, entirely independent
 * of any session-state file (Phase 2's "independence from session
 * lifecycle" requirement) and without needing a second, competing
 * persisted-state format of its own -- events.jsonl already IS the
 * durable source of truth for every SERVER_NPC_LOOT this account has
 * ever produced.
 *
 * PERFORMANCE (spec requirement -- "no full events.jsonl reread per
 * Swing tick"): this rebuild is a ONE-TIME cost, run exactly once at
 * plugin startup (see LootTrackerCoordinator), never on any Swing
 * Timer tick. After rebuild completes, every live update flows through
 * {@link LootTrackerIndex#apply(LootTrackerRecord)} directly (O(1)
 * amortized, no file IO) and every panel read flows through
 * {@link LootTrackerIndex#snapshotSources()} (an in-memory copy, no
 * file IO) -- this class is never touched again until the next full
 * plugin restart.
 *
 * RETENTION (spec requirement -- "~365 day retention, display only,
 * raw telemetry never deleted"): {@code minObservedAt} bounds what this
 * rebuild loads into the LIVE in-memory index -- lines older than that
 * are simply not parsed into a LootTrackerRecord at all. This is a
 * memory-bounding decision for the live read-model only; events.jsonl
 * ITSELF is never read destructively (a plain sequential read, never a
 * rewrite) and nothing on disk is ever deleted, trimmed, or modified by
 * this class. A caller wanting a longer effective retention window
 * simply passes an earlier {@code minObservedAt} (or {@code null} for
 * "no bound, load everything") -- the bound lives entirely at the call
 * site, not hardcoded here.
 *
 * DEDUP: relies entirely on {@link LootTrackerIndex#apply}'s own
 * eventId-based dedup (see that method's javadoc) -- this class does no
 * deduplication of its own, so re-running a rebuild against the same
 * index is always safe (idempotent) by construction.
 *
 * FAILURE HANDLING: matches this project's established
 * {@code LocalStateStore.readIfExists()} fail-open discipline -- a
 * missing events.jsonl, an unreadable file, or an individual malformed
 * line never crashes plugin startup. A malformed/unparseable LINE is
 * skipped and logged (the rest of the file is still processed); a
 * missing file simply yields an empty index (a brand new account, or
 * telemetry collection never enabled yet).
 */
@Slf4j
public final class LootTrackerPersistence
{
	private final Gson gson;

	/**
	 * @param gson RuneLite's injected Gson (passed down from
	 * LootTrackerCoordinator's own constructor injection) -- this class
	 * is not itself Guice-managed, so it is not annotated {@code @Inject}.
	 */
	public LootTrackerPersistence(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Reads {@code events.jsonl} for {@code accountHash} line by line
	 * (streamed, never loading the whole file into memory at once --
	 * this file can grow to contain every telemetry event this account
	 * has ever produced, not just loot) and applies every qualifying
	 * {@code SERVER_NPC_LOOT} record to {@code index}.
	 *
	 * @param minObservedAt records observed strictly before this instant
	 * are not loaded (retention bound -- see class javadoc); pass null
	 * for no bound.
	 * @return the number of records newly applied (post-dedup) -- purely
	 * informational, for startup logging.
	 */
	public int rebuild(long accountHash, LootTrackerIndex index, Instant minObservedAt)
	{
		Filepath eventsFile = TelemetryPaths.eventsFile(accountHash);
		if (!eventsFile.exists())
		{
			return 0;
		}

		int applied = 0;
		int lineNumber = 0;
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(eventsFile.openInputStream(), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				lineNumber++;
				if (line.isEmpty())
				{
					continue;
				}
				LootTrackerRecord record = parseIfServerNpcLoot(line, lineNumber, minObservedAt);
				if (record != null && index.apply(record))
				{
					applied++;
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed reading events.jsonl for account {} during Loot Tracker rebuild; proceeding with whatever was applied so far", accountHash, e);
		}

		return applied;
	}

	/**
	 * Minimal envelope shape -- ONLY the fields this rebuild actually
	 * needs (eventId, eventType, occurredAt), plus the payload kept as a
	 * raw {@link JsonElement} so it is only deserialized into a concrete
	 * {@code EventPayloads.ServerNpcLoot} for lines that actually are
	 * one. Deliberately does NOT reuse {@code events.TelemetryEvent}
	 * itself as the read-side type: that class's {@code payload} field
	 * is typed {@code Object}, which Gson cannot deserialize back into a
	 * concrete payload class on its own (it would produce a raw
	 * {@code LinkedTreeMap}, not an {@code EventPayloads.ServerNpcLoot})
	 * -- reading a polymorphic envelope back is a fundamentally different
	 * operation from writing one, so a separate, read-side-only shape is
	 * the correct fix, not a shared type.
	 */
	private static final class RawEnvelope
	{
		String eventId;
		EventType eventType;
		String occurredAt;
		JsonElement payload;
	}

	private LootTrackerRecord parseIfServerNpcLoot(String line, int lineNumber, Instant minObservedAt)
	{
		RawEnvelope envelope;
		try
		{
			envelope = gson.fromJson(line, RawEnvelope.class);
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Skipping malformed events.jsonl line {} during Loot Tracker rebuild", lineNumber, e);
			return null;
		}

		if (envelope == null || envelope.eventType != EventType.SERVER_NPC_LOOT || envelope.payload == null)
		{
			return null;
		}

		Instant observedAt = parseInstantOrNull(envelope.occurredAt);
		if (observedAt != null && minObservedAt != null && observedAt.isBefore(minObservedAt))
		{
			return null;
		}

		EventPayloads.ServerNpcLoot payload;
		try
		{
			payload = gson.fromJson(envelope.payload, EventPayloads.ServerNpcLoot.class);
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Skipping events.jsonl line {} with malformed SERVER_NPC_LOOT payload during Loot Tracker rebuild", lineNumber, e);
			return null;
		}
		if (payload == null)
		{
			return null;
		}

		List<LootTrackerItem> items = new ArrayList<>();
		if (payload.getItems() != null)
		{
			for (EventPayloads.ServerNpcLootItem item : payload.getItems())
			{
				items.add(new LootTrackerItem(item.getItemId(), item.getItemName(), item.getQuantity()));
			}
		}

		return new LootTrackerRecord(envelope.eventId, payload.getSourceName(), payload.getSourceId(), observedAt, items);
	}

	private static Instant parseInstantOrNull(String occurredAt)
	{
		if (occurredAt == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(occurredAt);
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
	}
}
