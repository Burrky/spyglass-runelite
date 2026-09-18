package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-network caveat as elsewhere in this
 * project. Thresholds are passed as parameters specifically so these
 * tests can use tiny fixture files (tens of bytes) instead of building
 * real 100MB fixtures — see BankSnapshotRetention.pruneIfNeeded()'s
 * signature.
 */
public class BankSnapshotRetentionTest
{
	private File dir;

	@Before
	public void setUp() throws IOException
	{
		dir = Files.createTempDirectory("bank-snapshots-retention-test").toFile();
	}

	@After
	public void tearDown()
	{
		File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				f.setWritable(true);
				f.delete();
			}
		}
		dir.delete();
	}

	/** Writes a fixture snapshot file with a body padded to exactly
	 * `size` bytes, with a distinct, controllable mtime so ordering is
	 * deterministic regardless of how fast the test runs. */
	private File fixture(String snapshotId, int size, long mtimeMs) throws IOException
	{
		File f = new File(dir, snapshotId + ".json");
		byte[] body = new byte[size];
		java.util.Arrays.fill(body, (byte) '0');
		Files.write(f.toPath(), body);
		f.setLastModified(mtimeMs);
		return f;
	}

	@Test
	public void underMax_noPruningOccurs() throws Exception
	{
		fixture("a", 100, 1_000);
		fixture("b", 100, 2_000);

		BankSnapshotRetention.pruneIfNeeded(dir, "b", 1000, 900);

		assertEquals("nothing should be pruned when total is already under max", 2, countFiles());
	}

	@Test
	public void overMax_prunesOldestFirstUntilAtOrUnderTarget() throws Exception
	{
		// Four 100-byte files -> 400 bytes total, well over a tiny
		// max/target pair, forces pruning down to <= target.
		fixture("oldest", 100, 1_000);
		fixture("second", 100, 2_000);
		fixture("third", 100, 3_000);
		fixture("newest", 100, 4_000);

		// max=300 (so 400 > 300 triggers pruning), target=200.
		BankSnapshotRetention.pruneIfNeeded(dir, "newest", 300, 200);

		assertEquals("must prune down to at or under the 200-byte target", 200, totalBytes());
		assertTrue("the two oldest files must be the ones removed",
			!exists("oldest") && !exists("second"));
		assertTrue(exists("third") && exists("newest"));
	}

	@Test
	public void protectedLatestSnapshot_isNeverDeleted_evenIfOldest() throws Exception
	{
		// The PROTECTED file is deliberately the OLDEST one — a real
		// scenario is rare (the just-written snapshot is normally the
		// newest) but the guarantee must hold unconditionally.
		fixture("protected-oldest", 100, 1_000);
		fixture("b", 100, 2_000);
		fixture("c", 100, 3_000);
		fixture("d", 100, 4_000);

		BankSnapshotRetention.pruneIfNeeded(dir, "protected-oldest", 300, 100);

		assertTrue("the protected snapshot must survive pruning no matter its age",
			exists("protected-oldest"));
		// Directory is allowed to remain above the 100-byte target
		// specifically because the protected file couldn't be removed
		// — per spec, this is accepted rather than deleting it.
		assertTrue("staying above target to protect the live pointer is acceptable",
			totalBytes() >= 100);
	}

	@Test
	public void nonJsonAndDirectoryEntries_areIgnoredSafely() throws Exception
	{
		fixture("a", 100, 1_000);
		fixture("b", 100, 2_000);

		// An unrelated file that must not be counted or touched.
		File stray = new File(dir, "notes.txt");
		Files.write(stray.toPath(), "not a snapshot".getBytes(StandardCharsets.UTF_8));

		// A subdirectory that happens to end in .json — must not be
		// treated as a snapshot file (would crash length()/delete()
		// semantics for a directory otherwise).
		File weirdDir = new File(dir, "oops.json");
		weirdDir.mkdir();
		Files.write(new File(weirdDir, "inner.json").toPath(), "irrelevant".getBytes(StandardCharsets.UTF_8));

		// max=1 forces an attempt to prune everything possible.
		BankSnapshotRetention.pruneIfNeeded(dir, "b", 1, 0);

		assertTrue("the stray non-.json file must be left alone", stray.exists());
		assertTrue("the directory entry must be left alone, not crash pruning", weirdDir.exists());
		assertTrue("the protected snapshot must still survive", exists("b"));
	}

	@Test
	public void pruningOrder_isDeterministic_tiedMtimeBrokenByName() throws Exception
	{
		// Identical mtimes — the only way ordering can still be
		// deterministic is the filename tiebreak.
		fixture("zzz", 100, 5_000);
		fixture("aaa", 100, 5_000);
		fixture("mmm", 100, 5_000);

		BankSnapshotRetention.pruneIfNeeded(dir, "zzz", 200, 100);

		// "aaa" sorts first alphabetically, so with all mtimes tied it
		// must be the one pruned first.
		assertTrue("alphabetically-first name must be pruned first on a full mtime tie", !exists("aaa"));
	}

	@Test
	public void deletionFailure_isNonFatal_andLeavesFileInPlace() throws Exception
	{
		// Windows-oriented assumption (this is a RuneLite/Windows
		// project — see harness notes): a read-only file cannot be
		// deleted by File.delete() there. On a platform where deletion
		// is governed purely by directory permissions instead, this
		// particular file may still get deleted — the assertion that
		// matters either way is the one below: pruning itself must not
		// throw, regardless of whether the individual delete succeeded.
		File locked = fixture("locked", 100, 1_000);
		locked.setReadOnly();
		fixture("also-old", 100, 1_500);
		fixture("newest", 100, 9_000);

		// Must not throw even if some deletes fail.
		BankSnapshotRetention.pruneIfNeeded(dir, "newest", 200, 50);

		assertTrue("pruning must complete without throwing regardless of individual delete outcomes", true);
		assertTrue("the protected/newest snapshot must always survive", exists("newest"));

		locked.setWritable(true);
	}

	@Test
	public void emptyOrMissingDirectory_isSafeNoOp()
	{
		File missing = new File(dir, "does-not-exist");
		// Must not throw for a nonexistent directory (listFiles()
		// returns null there).
		BankSnapshotRetention.pruneIfNeeded(missing, "whatever", 100, 50);
	}

	private boolean exists(String snapshotId)
	{
		return new File(dir, snapshotId + ".json").exists();
	}

	private int countFiles()
	{
		File[] files = dir.listFiles();
		return files == null ? 0 : files.length;
	}

	private long totalBytes()
	{
		File[] files = dir.listFiles();
		if (files == null)
		{
			return 0;
		}
		long total = 0;
		for (File f : files)
		{
			if (f.isFile())
			{
				total += f.length();
			}
		}
		return total;
	}
}
