package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.model.StorageState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Singleton;

/**
 * A short, bounded, IN-MEMORY-ONLY ring of complete (inventory,
 * equipment) observation pairs, fed by {@link LoadoutArchiveCollector}
 * and read by {@link com.osrstelemetry.plugin.session.LoadoutResolver}
 * (owned by SessionPersistence) to resolve a session's starting/ending
 * loadout.
 *
 * WHY IN-MEMORY ONLY, NO DISK PERSISTENCE (deliberate scope decision):
 * resolution always happens close to the reference instant it is
 * resolving against --
 *   - starting loadout: resolved the first time a brand-new session
 *     is persisted (see SessionPersistence.persistCurrent()), which
 *     happens within about one tick (~600ms) of the session actually
 *     starting, while this archive still holds fresh, same-process
 *     observations;
 *   - ending loadout: resolved at finalization time, i.e. essentially
 *     "now" (see SessionPersistence.persistFinalized()).
 * A client restart occurring near a session boundary starts this
 * archive empty regardless of whether it were persisted to disk, so a
 * resolution landing in that gap correctly and honestly falls back to
 * FALLBACK_AT_OR_AFTER_START or UNAVAILABLE rather than needing disk
 * durability. Not persisting also avoids repeatedly re-serializing a
 * growing file on every inventory change (a performance consideration).
 *
 * ACCOUNT ISOLATION: entirely cleared on every real account switch via
 * {@link #resetForAccountSwitch()} -- see
 * OsrsTelemetryPlugin.handleLogin(). There is deliberately no
 * per-account keying inside this class; a full clear on switch is
 * simpler and exactly as correct, since nothing here is ever meant to
 * outlive one account's current login anyway.
 *
 * BOUNDED: pruned by both age (MAX_AGE) and count
 * (MAX_ENTRIES) on every {@link #record} call, so this can never grow
 * without bound even under rapid inventory churn (e.g. a Barrows run
 * or rapid bank withdrawals).
 *
 * THREAD SAFETY: every method synchronizes on a private lock. Reads
 * (latestStrictlyBefore/earliestAtOrAfter/latestAtOrBefore) are cheap
 * linear scans over a bounded (MAX_ENTRIES) list -- never worth a more
 * elaborate data structure at this size.
 */
@Singleton
public class LoadoutArchive
{
	/**
	 * Comfortably larger than the ~5 minute preferred pre-start
	 * lookback (see LoadoutResolver.PRE_START_LOOKBACK) so a session
	 * starting right at the edge of that lookback still finds real
	 * data, without keeping unbounded history.
	 */
	static final Duration MAX_AGE = Duration.ofMinutes(20);
	static final int MAX_ENTRIES = 150;

	private final Object lock = new Object();
	private final List<Entry> entries = new ArrayList<>();

	public static final class Entry
	{
		private final Instant observedAt;
		private final List<StorageState.StorageItem> inventory;
		private final List<StorageState.StorageItem> equipment;

		Entry(Instant observedAt, List<StorageState.StorageItem> inventory, List<StorageState.StorageItem> equipment)
		{
			this.observedAt = observedAt;
			this.inventory = Collections.unmodifiableList(new ArrayList<>(inventory));
			this.equipment = Collections.unmodifiableList(new ArrayList<>(equipment));
		}

		public Instant getObservedAt()
		{
			return observedAt;
		}

		public List<StorageState.StorageItem> getInventory()
		{
			return inventory;
		}

		public List<StorageState.StorageItem> getEquipment()
		{
			return equipment;
		}
	}

	/**
	 * Called by LoadoutArchiveCollector whenever a COMPLETE inventory
	 * and a COMPLETE equipment observation are available together --
	 * never a partial pair. Defensively copies both lists (see Entry's
	 * own constructor) so a caller's list can never be mutated out
	 * from under an already-recorded entry.
	 */
	public void record(Instant observedAt, List<StorageState.StorageItem> inventory, List<StorageState.StorageItem> equipment)
	{
		synchronized (lock)
		{
			entries.add(new Entry(observedAt, inventory, equipment));
			prune(observedAt);
		}
	}

	private void prune(Instant now)
	{
		Instant cutoff = now.minus(MAX_AGE);
		entries.removeIf(e -> e.observedAt.isBefore(cutoff));
		while (entries.size() > MAX_ENTRIES)
		{
			// Oldest-first insertion order (record() always appends) --
			// removing index 0 drops the single oldest entry.
			entries.remove(0);
		}
	}

	/** The most recent entry observed strictly before `instant`, or null if none exists. */
	public Entry latestStrictlyBefore(Instant instant)
	{
		synchronized (lock)
		{
			Entry best = null;
			for (Entry e : entries)
			{
				if (e.observedAt.isBefore(instant) && (best == null || e.observedAt.isAfter(best.observedAt)))
				{
					best = e;
				}
			}
			return best;
		}
	}

	/** The earliest entry observed at or after `instant`, or null if none exists. */
	public Entry earliestAtOrAfter(Instant instant)
	{
		synchronized (lock)
		{
			Entry best = null;
			for (Entry e : entries)
			{
				if (!e.observedAt.isBefore(instant) && (best == null || e.observedAt.isBefore(best.observedAt)))
				{
					best = e;
				}
			}
			return best;
		}
	}

	/** The most recent entry observed at or before `instant`, or null if none exists. */
	public Entry latestAtOrBefore(Instant instant)
	{
		synchronized (lock)
		{
			Entry best = null;
			for (Entry e : entries)
			{
				if (!e.observedAt.isAfter(instant) && (best == null || e.observedAt.isAfter(best.observedAt)))
				{
					best = e;
				}
			}
			return best;
		}
	}

	/** Cleared entirely on every real account switch; see OsrsTelemetryPlugin.handleLogin(). */
	public void resetForAccountSwitch()
	{
		synchronized (lock)
		{
			entries.clear();
		}
	}

	/** Test-only visibility hook -- number of entries currently held, after pruning. */
	int size()
	{
		synchronized (lock)
		{
			return entries.size();
		}
	}
}
