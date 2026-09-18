package com.osrstelemetry.plugin.collectors;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Bounds disk usage for one account's bank_snapshots/ directory.
 *
 * EVENT/FILE CONSISTENCY CONTRACT ("Option A" chosen — the simplest of
 * the considered options):
 *
 *   Historical BANK_SNAPSHOT events in events.jsonl may reference an
 *   immutable snapshot file that retention has since pruned. This
 *   project does not emit a tombstone/pruned record (Option B) or
 *   maintain a separate retained/pruned manifest (Option C).
 *
 *   WHY: events.jsonl is already explicitly documented (see
 *   EventPayloads.BankSnapshot's javadoc) as "a pointer only... the
 *   actual delta calculation is a backend job... against the
 *   corresponding bank.json snapshot" — i.e. downstream consumption of
 *   a BANK_SNAPSHOT event was never specified to require every
 *   historical snapshot file to still exist forever. A downstream
 *   consumer reading an old BANK_SNAPSHOT event whose snapshotId no
 *   longer resolves to a file under bank_snapshots/ should treat that
 *   specific historical snapshot as pruned/unavailable — exactly the
 *   same shape of "fail open on missing data" this project already
 *   uses everywhere else (see BankSnapshotBaseline.loadFrom()'s own
 *   fail-open contract for the CURRENT pointer). Recovering full
 *   historical fidelity forever would require either unbounded
 *   storage (the thing Phase 3 exists to bound) or a schema/event
 *   redesign (tombstones or a manifest) that Phase 3 was told to
 *   avoid unless a clean minimal option didn't exist. One does:
 *   "missing means pruned" needs no new event type, no new file
 *   format, and no change to any existing reader's happy path.
 *
 * WHAT IS NEVER PRUNED: the immutable snapshot currently referenced by
 * bank.json's latestSnapshotId — pruning it would make the CURRENT
 * pointer itself dangling, which is a materially different (and worse)
 * failure than an old historical event referencing a pruned file. If
 * protecting that one file means the directory stays slightly above
 * the 90MB target, that is accepted (see pruneIfNeeded() javadoc) —
 * never deleting the live pointer's target takes priority over hitting
 * the target exactly.
 */
@Slf4j
final class BankSnapshotRetention
{
	private BankSnapshotRetention()
	{
	}

	static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;
	static final long DEFAULT_TARGET_BYTES = 90L * 1024 * 1024;

	/**
	 * Prunes oldest-first from snapshotsDir until total size is at or
	 * under targetBytes, but ONLY if the current total exceeds
	 * maxBytes in the first place (the 100MB/90MB gap is deliberate
	 * hysteresis — see class javadoc — so a directory sitting at 91MB
	 * doesn't get pruned every single time a new snapshot nudges it
	 * up, only once it actually crosses 100MB).
	 *
	 * Must be called ONLY after the caller has confirmed a new
	 * immutable snapshot was successfully and durably written — never
	 * speculatively, and never before that write's success is known
	 * (see ContainerCollector call sites). protectedSnapshotId (the
	 * CURRENT bank.json.latestSnapshotId — normally the snapshot that
	 * was just written) is never deleted, even if every other file is
	 * gone and the directory is still above targetBytes as a result.
	 *
	 * Ordering source: File.lastModified() — these are immutable
	 * snapshot files, written exactly once and never modified again,
	 * so mtime is a reliable proxy for "when was this snapshot taken"
	 * without needing to parse each file's JSON body (which would also
	 * make pruning itself fail-prone on a corrupt file). Ties (rare —
	 * millisecond-resolution writes) are broken by filename for fully
	 * deterministic ordering.
	 *
	 * Safety: only files matching *.json under snapshotsDir count
	 * toward size or are eligible for deletion — anything else
	 * (unexpected files, subdirectories, partial .tmp files left by an
	 * interrupted write elsewhere) is silently ignored, never counted,
	 * never deleted, never crashes pruning. Each individual delete
	 * failure is caught, logged, and skipped — pruning continues with
	 * the next file rather than aborting, and a deletion failure never
	 * invalidates the snapshot that was just successfully written.
	 */
	static void pruneIfNeeded(File snapshotsDir, String protectedSnapshotId, long maxBytes, long targetBytes)
	{
		try
		{
			File[] listed = snapshotsDir.listFiles();
			if (listed == null || listed.length == 0)
			{
				return;
			}

			List<File> snapshotFiles = new ArrayList<>();
			long total = 0;
			for (File f : listed)
			{
				if (f == null || f.isDirectory() || !f.getName().endsWith(".json"))
				{
					// Malformed/unexpected entries (subdirectories, a
					// stray .tmp from an interrupted write elsewhere,
					// OS metadata files, etc.) are simply not part of
					// the accounting.
					continue;
				}
				long length = f.length();
				total += length;
				snapshotFiles.add(f);
			}

			if (total <= maxBytes)
			{
				return;
			}

			snapshotFiles.sort(Comparator
				.comparingLong(File::lastModified)
				.thenComparing(File::getName));

			for (File f : snapshotFiles)
			{
				if (total <= targetBytes)
				{
					break;
				}
				String snapshotId = stripJsonExtension(f.getName());
				if (snapshotId.equals(protectedSnapshotId))
				{
					// Never delete the file bank.json currently points
					// at — accept staying above target instead (see
					// class javadoc).
					continue;
				}

				long length = f.length();
				boolean deleted;
				try
				{
					deleted = f.delete();
				}
				catch (SecurityException e)
				{
					deleted = false;
					log.warn("Failed deleting old bank snapshot {} during retention pruning", f, e);
				}

				if (deleted)
				{
					total -= length;
				}
				else
				{
					log.warn("Could not delete old bank snapshot {} during retention pruning; leaving it in place", f);
				}
			}
		}
		catch (Exception e)
		{
			// Retention pruning must never be able to take down a
			// caller that just successfully wrote a new snapshot —
			// fail safe/non-fatal on literally anything unexpected.
			log.warn("Bank snapshot retention pruning failed unexpectedly for {}; leaving files as-is", snapshotsDir, e);
		}
	}

	private static String stripJsonExtension(String fileName)
	{
		return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - ".json".length()) : fileName;
	}
}
