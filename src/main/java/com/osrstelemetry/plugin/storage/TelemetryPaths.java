package com.osrstelemetry.plugin.storage;

import java.io.File;
import net.runelite.client.RuneLite;

/**
 * All telemetry files live under
 *   ~/.runelite/osrs-telemetry/{accountHash}/
 * keyed by accountHash (not display name) so a rename never orphans
 * history. RuneLite.RUNELITE_DIR is the standard base every other
 * plugin's local storage uses (e.g. Character-Export, the various loot
 * loggers) — reusing it rather than inventing a new base directory.
 */
public class TelemetryPaths
{
	private static final String ROOT_DIR_NAME = "osrs-telemetry";

	public static File accountDir(long accountHash)
	{
		File dir = new File(new File(RuneLite.RUNELITE_DIR, ROOT_DIR_NAME), Long.toString(accountHash));
		if (!dir.exists())
		{
			dir.mkdirs();
		}
		return dir;
	}

	public static File stateFile(long accountHash, String category)
	{
		return new File(accountDir(accountHash), category + ".json");
	}

	public static File collectionLogDir(long accountHash)
	{
		File dir = new File(accountDir(accountHash), "collection_log");
		if (!dir.exists())
		{
			dir.mkdirs();
		}
		return dir;
	}

	public static File activitiesDir(long accountHash)
	{
		File dir = new File(accountDir(accountHash), "activities");
		if (!dir.exists())
		{
			dir.mkdirs();
		}
		return dir;
	}

	/**
	 * Immutable historical bank snapshot — never overwritten. The
	 * current-state bank.json (via stateFile) is a mutable pointer
	 * that references the latest snapshotId; this file, once written,
	 * is never touched again. This is what makes a later delta between
	 * two points in time possible: two immutable files to diff,
	 * instead of one file that was always just overwritten in place.
	 */
	public static File bankSnapshotFile(long accountHash, String snapshotId)
	{
		return new File(bankSnapshotsDir(accountHash), snapshotId + ".json");
	}

	/**
	 * The directory itself, for BankSnapshotRetention to list/prune.
	 * Split out of
	 * bankSnapshotFile() (which still just delegates here) rather than
	 * having retention reconstruct the "bank_snapshots" path itself —
	 * one place defines this directory's location.
	 */
	public static File bankSnapshotsDir(long accountHash)
	{
		File dir = new File(accountDir(accountHash), "bank_snapshots");
		if (!dir.exists())
		{
			dir.mkdirs();
		}
		return dir;
	}

	/**
	 * Single append-only file per account. Not split by day/session —
	 * at this data volume (one account's lifetime of events) there is
	 * no need for log rotation, and a single file is simpler for the
	 * upload cursor in Step 7 to reason about.
	 */
	public static File eventsFile(long accountHash)
	{
		return new File(accountDir(accountHash), "events.jsonl");
	}

	/**
	 * Mutable pointer to whatever session is currently in progress (ACTIVE or
	 * SUSPENDED) for this account — same "current-state pointer" role
	 * bank.json plays for bank snapshots. Overwritten on every
	 * lifecycle transition; never itself an append-only or historical
	 * record. See SessionPersistence for the exact write ordering that
	 * keeps this in sync with sessions/{sessionId}.json.
	 */
	public static File sessionStateFile(long accountHash)
	{
		return new File(accountDir(accountHash), "session_state.json");
	}

	/**
	 * Directory of immutable, one-per-finalized-session records — same role
	 * bank_snapshots/ plays for bank history: once written, a file here
	 * is never mutated into a different historical result (see
	 * SessionPersistence.persistFinalized()).
	 */
	public static File sessionsDir(long accountHash)
	{
		File dir = new File(accountDir(accountHash), "sessions");
		if (!dir.exists())
		{
			dir.mkdirs();
		}
		return dir;
	}

	public static File sessionFile(long accountHash, String sessionId)
	{
		return new File(sessionsDir(accountHash), sessionId + ".json");
	}
}
