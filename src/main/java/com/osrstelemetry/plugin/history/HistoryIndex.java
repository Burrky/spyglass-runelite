package com.osrstelemetry.plugin.history;

import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * History's discovery/index (spec Part R): a lightweight, in-memory cache
 * of {@link HistoryEntry} summaries, one per finalized session, kept
 * current by comparing a cheap directory listing of sessions/ against
 * sessionIds already known -- NEVER a full per-tick re-parse of every
 * session file.
 *
 * ADAPTED FROM, NOT COPIED FROM, LootTrackerIndex/LootTrackerPersistence:
 * that pair's rebuild logic streams one append-only events.jsonl file
 * line-by-line. History's canonical source is instead a DIRECTORY of
 * many small immutable per-session JSON files (spec Part B), so
 * "rebuild" here means "list the directory, diff against what's
 * already indexed, parse only the files that are genuinely new."
 * Every already-indexed file is immutable (a finalized session record
 * is never rewritten -- see SessionPersistence's own javadoc) and is
 * therefore NEVER re-parsed once indexed -- the one part this DOES
 * share with LootTrackerIndex's own design: once accepted, a record
 * is never re-read from disk.
 *
 * OFF THE EDT (spec Part T/U): refresh() does real file IO (a
 * directory listing plus, for each newly-discovered session, one
 * SessionPersistence.loadFinalized() read) and must never be called
 * from the Swing EDT -- see HistoryCoordinator, which owns the
 * background thread this runs on.
 *
 * BACKWARD COMPATIBILITY / ERROR HANDLING (spec Parts X/Y): a single
 * malformed or partial session file (any I/O or parse failure --
 * SessionPersistence.loadFinalized() already fails open, returning
 * null -- or a file that parses but is missing required fields, per
 * HistoryEntry.from()'s own null-return contract) is logged and
 * skipped, never thrown, and never crashes the rest of the refresh --
 * every other file continues to be indexed normally, and a skipped
 * file is retried on the NEXT refresh() call (it is never marked
 * "seen" unless HistoryEntry.from() actually returned a real entry),
 * so a transiently-locked file (e.g. mid-write) self-heals on a later
 * poll rather than being permanently skipped.
 *
 * ACCOUNT ISOLATION (spec Part S): one HistoryIndex instance is
 * strictly single-account for its own in-memory cache's lifetime --
 * HistoryCoordinator constructs a fresh one on every real account
 * switch rather than clearing this one in place, so there is no
 * "leftover key from the old account" class of bug to guard against
 * here at all.
 */
@Slf4j
public final class HistoryIndex
{
	private final Map<String, HistoryEntry> entries = new ConcurrentHashMap<>();

	/**
	 * Lists sessions/ (cheap: filesystem metadata only), parses any
	 * sessionId not already in `entries`, and returns a filtered,
	 * newest-first snapshot within `window` of `now`. Safe to call
	 * repeatedly (e.g. every poll while the History tab is visible) --
	 * the cost of a call where nothing changed is one directory
	 * listing plus a HashMap containsKey() per existing file, no
	 * parsing at all.
	 */
	public List<HistoryEntry> refresh(long accountHash, SessionPersistence persistence, Duration window, Instant now)
	{
		File[] files = TelemetryPaths.sessionsDir(accountHash).listFiles((dir, name) -> name.endsWith(".json"));
		if (files != null)
		{
			for (File file : files)
			{
				String sessionId = stripJsonExtension(file.getName());
				if (sessionId == null || entries.containsKey(sessionId))
				{
					continue;
				}
				Session session = persistence.loadFinalized(accountHash, sessionId);
				HistoryEntry entry = HistoryEntry.from(session);
				if (entry == null)
				{
					log.warn("Skipping unparseable/incomplete session file for History: {}", file.getName());
					continue;
				}
				entries.put(sessionId, entry);
			}
		}
		return snapshot(window, now);
	}

	private static String stripJsonExtension(String filename)
	{
		if (!filename.endsWith(".json"))
		{
			return null;
		}
		return filename.substring(0, filename.length() - ".json".length());
	}

	private List<HistoryEntry> snapshot(Duration window, Instant now)
	{
		Instant cutoff = now.minus(window);
		List<HistoryEntry> result = new ArrayList<>();
		for (HistoryEntry entry : entries.values())
		{
			Instant finalizedAt = parseInstantOrNull(entry.getFinalizedAt());
			// A file whose finalizedAt somehow fails to parse is kept
			// (never silently dropped from the list) rather than
			// excluded by an unparseable date -- see HistoryEntry.from(),
			// which already guarantees finalizedAt is non-null for any
			// entry that made it into `entries` at all; this null-check
			// is a pure defensive fallback.
			if (finalizedAt != null && finalizedAt.isBefore(cutoff))
			{
				continue;
			}
			result.add(entry);
		}
		result.sort(Comparator.comparing(HistoryEntry::getFinalizedAt, Comparator.nullsLast(Comparator.reverseOrder())));
		return result;
	}

	private static Instant parseInstantOrNull(String value)
	{
		if (value == null)
		{
			return null;
		}
		try
		{
			return Instant.parse(value);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** Test/diagnostic visibility -- how many sessions are currently indexed in memory (the ~10 day window filter is NOT applied here; this is the raw indexed count). */
	int indexedCount()
	{
		return entries.size();
	}
}
