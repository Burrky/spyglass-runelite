package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Deduplication baseline for immutable bank snapshots. Deliberately
 * split into two independent pieces:
 *
 * 1. shouldWriteNewSnapshot() — pure, no I/O, no Client — the actual
 *    decision rule, unit-testable in isolation.
 * 2. loadFrom() — the only piece that touches disk, isolated so it can
 *    fail open cleanly and be tested with just a LocalStateStore
 *    (no RuneLite Client needed either, since it only needs the raw
 *    accountHash long, not a live game session).
 *
 * "Absent" (items == null, createdAt == null) represents both cases
 * where no valid baseline exists: never observed before, AND a failed
 * or corrupt load. Both must behave identically — always write a new
 * snapshot rather than risk suppressing a real one — which is why
 * there's a single isPresent() check rather than separate flags for
 * "never loaded" vs "load failed".
 *
 * The baseline also carries the snapshotId of the immutable file it
 * was built from -- an earlier version of this class tracked only
 * items/createdAt, so nothing in memory remembered which immutable
 * snapshot bank.json's pointer
 * should keep referencing between real snapshots. ContainerCollector
 * uses getSnapshotId() to re-stamp bank.json's latestSnapshotId on
 * every ordinary write (Path A) and on dedup-skip refreshes (Path B),
 * instead of leaving the field null (and therefore omitted by Gson)
 * whenever an immutable snapshot isn't being written on that exact
 * observation.
 */
final class BankSnapshotBaseline
{
	private final List<StorageState.StorageItem> items;
	private final Instant createdAt;
	private final String snapshotId;

	private BankSnapshotBaseline(List<StorageState.StorageItem> items, Instant createdAt, String snapshotId)
	{
		this.items = items;
		this.createdAt = createdAt;
		this.snapshotId = snapshotId;
	}

	static BankSnapshotBaseline absent()
	{
		return new BankSnapshotBaseline(null, null, null);
	}

	/**
	 * Convenience overload for tests/call sites that don't care about
	 * snapshotId tracking (pure decision-rule tests A-F). snapshotId is
	 * left null in that case — fine, since shouldWriteNewSnapshot()
	 * never reads it.
	 */
	static BankSnapshotBaseline of(List<StorageState.StorageItem> items, Instant createdAt)
	{
		return of(items, createdAt, null);
	}

	static BankSnapshotBaseline of(List<StorageState.StorageItem> items, Instant createdAt, String snapshotId)
	{
		return new BankSnapshotBaseline(items, createdAt, snapshotId);
	}

	boolean isPresent()
	{
		return items != null && createdAt != null;
	}

	/**
	 * The immutable snapshot id this baseline was built from — the
	 * value bank.json's latestSnapshotId should keep pointing at until
	 * a NEW immutable snapshot is successfully written and replaces it.
	 * Null when isPresent() is false; callers must check isPresent()
	 * (or tolerate null) before relying on this.
	 */
	String getSnapshotId()
	{
		return snapshotId;
	}

	/** Package-private test hook — production code has no legitimate
	 * reason to read this back (the whole point of the pure decision
	 * rule is that callers pass "now" in and get a boolean out), but
	 * tests need it to assert the createdAt clock genuinely didn't
	 * advance across a run of refresh-only observations. */
	Instant getCreatedAt()
	{
		return createdAt;
	}

	/**
	 * Loads the persisted baseline for one account: bank.json's
	 * latestSnapshotId, then that snapshot file's items/lastObservedAt.
	 * Fails open (returns absent()) on literally anything unexpected —
	 * missing pointer, missing/unset latestSnapshotId, missing
	 * snapshot file, missing/malformed fields, a timestamp that
	 * doesn't parse. A failed load must never be distinguishable from
	 * "never observed before" in terms of behavior — both mean "write
	 * a fresh snapshot," per the project's existing "unknown is not
	 * zero" principle applied here to "unknown baseline is not a
	 * matching baseline."
	 */
	static BankSnapshotBaseline loadFrom(LocalStateStore store, long accountHash)
	{
		try
		{
			StorageState pointer = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
			if (pointer == null || pointer.getLatestSnapshotId() == null)
			{
				return absent();
			}

			StorageState snapshot = store.readIfExists(
				TelemetryPaths.bankSnapshotFile(accountHash, pointer.getLatestSnapshotId()), StorageState.class);
			if (snapshot == null || snapshot.getItems() == null || snapshot.getLastObservedAt() == null)
			{
				return absent();
			}

			return of(snapshot.getItems(), Instant.parse(snapshot.getLastObservedAt()), pointer.getLatestSnapshotId());
		}
		catch (Exception e)
		{
			// Instant.parse can throw on a malformed timestamp; treat
			// exactly like every other failure mode here — fail open.
			return absent();
		}
	}

	/**
	 * Pure decision rule — no I/O, no Client, fully unit-testable.
	 *
	 *   shouldWrite = no valid baseline
	 *              OR bank contents changed
	 *              OR baseline is >= maxAge old
	 *
	 * Content equality is plain List.equals() on StorageItem, which
	 * (being @Data) already compares slot/itemId/name/quantity/
	 * equipmentSlot per element — reordering changes which item sits
	 * at which slot, which List.equals() correctly detects without
	 * needing any custom comparison logic.
	 *
	 * baseline.createdAt means "timestamp of the most recent
	 * SUCCESSFULLY WRITTEN immutable snapshot" — NOT "timestamp of the
	 * most recent bank observation." Callers must only ever advance it
	 * via a successful snapshot write (see ContainerCollector's
	 * recordBankBaseline(), called exclusively from a write's
	 * onWritten/writeAndWait-success callback), never from an ordinary
	 * refresh-only observation. This method has no way to enforce that
	 * itself — it only ever reads baseline.createdAt — so the guarantee
	 * lives entirely in when/where the caller constructs a new
	 * baseline.
	 */
	static boolean shouldWriteNewSnapshot(
		BankSnapshotBaseline baseline,
		List<StorageState.StorageItem> candidateItems,
		Instant now,
		Duration maxAge)
	{
		if (!baseline.isPresent())
		{
			return true;
		}
		if (!baseline.items.equals(candidateItems))
		{
			return true;
		}
		return Duration.between(baseline.createdAt, now).compareTo(maxAge) >= 0;
	}
}
