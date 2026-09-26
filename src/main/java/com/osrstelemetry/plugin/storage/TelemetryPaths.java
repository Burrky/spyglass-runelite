package com.osrstelemetry.plugin.storage;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * All telemetry files live under this plugin's own RuneLite-managed
 * plugin-data directory, keyed by accountHash (not display name) so a
 * rename never orphans history:
 *
 *   .runelite/plugin-data/{internalName}/{accountHash}/
 *
 * MIGRATION (Plugin Hub maintainer review -- Filepath API): this used to
 * be a plain java.io.File tree rooted directly at
 * .runelite/osrs-telemetry/{accountHash}/ (built off RuneLite.RUNELITE_DIR).
 * RuneLite's Filepath model replaces that with a per-plugin root obtained
 * from Plugin.getPluginDirectory() -- see OsrsTelemetryPlugin.startUp(),
 * which calls init() below exactly once, before anything else touches
 * storage, with the Filepath that method returns.
 *
 * EXISTING USER DATA IS NOT LOST: @PluginDescriptor.legacyDataDirectory
 * on OsrsTelemetryPlugin is set to "osrs-telemetry" (the exact old root
 * directory name). RuneLite's own Plugin.getPluginDirectory() implementation
 * already contains a one-time automatic migration for exactly this
 * situation: the first time it is called after this update, if the NEW
 * plugin-data directory does not exist yet AND the OLD
 * .runelite/osrs-telemetry directory does exist, RuneLite itself moves
 * (renames) the entire old directory -- every accountHash subfolder and
 * everything under it -- into the new location before returning it. No
 * custom migration code was written here; this class and
 * OsrsTelemetryPlugin only need to point at the new root and declare the
 * old one's name for RuneLite's own core mechanism to find.
 *
 * ROOT LIFECYCLE: init() must be called exactly once (repeat calls with
 * the same value are harmless) before any other method on this class is
 * used -- from a real Plugin's startUp() in production, or from a
 * throwaway Filepath rooted at a JVM temp directory in tests (see
 * TestFilepaths). Every method below throws IllegalStateException if
 * called before init().
 */
@Slf4j
public final class TelemetryPaths
{
	private TelemetryPaths()
	{
	}

	private static volatile Filepath root;

	/**
	 * Called exactly once from OsrsTelemetryPlugin.startUp(), with the
	 * Filepath its own getPluginDirectory() returns -- never touched
	 * again for the lifetime of the client process. A plugin
	 * disable/re-enable cycle calls startUp() again, but
	 * getPluginDirectory() resolves to the exact same location every
	 * time (it depends only on RuneLite's own static PLUGIN_DATA
	 * directory and this plugin's fixed internalName), so re-calling
	 * init() on every startUp() is harmless and deliberately not
	 * guarded against.
	 */
	public static void init(Filepath pluginDirectory)
	{
		root = pluginDirectory;
	}

	private static Filepath root()
	{
		Filepath r = root;
		if (r == null)
		{
			throw new IllegalStateException("TelemetryPaths.init() must be called before any storage path is resolved.");
		}
		return r;
	}

	public static Filepath accountDir(long accountHash)
	{
		Filepath dir = root().joinSegment(Long.toString(accountHash));
		createDirectoriesQuietly(dir);
		return dir;
	}

	public static Filepath stateFile(long accountHash, String category)
	{
		return accountDir(accountHash).joinSegment(category + ".json");
	}

	public static Filepath collectionLogDir(long accountHash)
	{
		Filepath dir = accountDir(accountHash).joinSegment("collection_log");
		createDirectoriesQuietly(dir);
		return dir;
	}

	public static Filepath activitiesDir(long accountHash)
	{
		Filepath dir = accountDir(accountHash).joinSegment("activities");
		createDirectoriesQuietly(dir);
		return dir;
	}

	/**
	 * ADDED (Filepath migration audit): closes the one call site
	 * (ActivityKillCountCollector) that previously built this exact path
	 * itself via {@code activitiesDir(...).joinSegment(activityId + ".json")}
	 * instead of going through TelemetryPaths like every sibling file
	 * accessor here -- see the migration's file-I/O audit list.
	 */
	public static Filepath activityFile(long accountHash, String activityId)
	{
		return activitiesDir(accountHash).joinSegment(activityId + ".json");
	}

	/**
	 * Immutable historical bank snapshot -- never overwritten. The
	 * current-state bank.json (via stateFile) is a mutable pointer
	 * that references the latest snapshotId; this file, once written,
	 * is never touched again.
	 */
	public static Filepath bankSnapshotFile(long accountHash, String snapshotId)
	{
		return bankSnapshotsDir(accountHash).joinSegment(snapshotId + ".json");
	}

	/** The directory itself, for BankSnapshotRetention to list/prune. */
	public static Filepath bankSnapshotsDir(long accountHash)
	{
		Filepath dir = accountDir(accountHash).joinSegment("bank_snapshots");
		createDirectoriesQuietly(dir);
		return dir;
	}

	/**
	 * Single append-only file per account. Not split by day/session --
	 * at this data volume there is no need for log rotation.
	 */
	public static Filepath eventsFile(long accountHash)
	{
		return accountDir(accountHash).joinSegment("events.jsonl");
	}

	/**
	 * Mutable pointer to whatever session is currently in progress
	 * (ACTIVE or SUSPENDED) for this account. Overwritten on every
	 * lifecycle transition; never itself an append-only or historical
	 * record.
	 */
	public static Filepath sessionStateFile(long accountHash)
	{
		return accountDir(accountHash).joinSegment("session_state.json");
	}

	/**
	 * Directory of immutable, one-per-finalized-session records -- same
	 * role bank_snapshots/ plays for bank history.
	 */
	public static Filepath sessionsDir(long accountHash)
	{
		Filepath dir = accountDir(accountHash).joinSegment("sessions");
		createDirectoriesQuietly(dir);
		return dir;
	}

	public static Filepath sessionFile(long accountHash, String sessionId)
	{
		return sessionsDir(accountHash).joinSegment(sessionId + ".json");
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A): the ONE
	 * additive, account-scoped persistence record for the interrupted/
	 * resumable prior session, if any -- see InterruptedCandidateRecord's
	 * own javadoc. Deliberately a small SEPARATE file rather than a
	 * change to session_state.json's own shape (a different plugin
	 * version, or an account that predates this feature, simply has no
	 * such file -- SessionPersistence treats that identically to "no
	 * candidate parked," never an error).
	 */
	public static Filepath interruptedCandidateFile(long accountHash)
	{
		return accountDir(accountHash).joinSegment("interrupted_candidate.json");
	}

	/**
	 * Mirrors the old File-based "mkdirs() if missing" convention --
	 * Filepath.createDirectories() is idempotent exactly like mkdirs()
	 * (a no-op success if the directory already exists), the one
	 * difference being that it can throw IOException where mkdirs()
	 * returned a boolean nobody checked. Logged and swallowed here
	 * rather than propagated: a directory that fails to create will
	 * surface anyway, immediately and more specifically, the moment a
	 * caller tries to write into it (see LocalStateStore's own warn
	 * logging on write failure) -- this is purely defensive, not a new
	 * failure mode.
	 */
	private static void createDirectoriesQuietly(Filepath dir)
	{
		try
		{
			dir.createDirectories();
		}
		catch (IOException e)
		{
			log.warn("Failed creating telemetry directory {}", dir, e);
		}
	}
}
