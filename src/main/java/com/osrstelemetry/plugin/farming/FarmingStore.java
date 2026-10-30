package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.runelite.client.util.Filepath;

/**
 * One owner's Farming knowledge in memory, plus its profile-scoped files.
 * Not wired into the plugin yet: nothing creates player Farming files until a
 * later pass registers the live collector.
 *
 * <h3>Ownership</h3>
 * <ul>
 *   <li>Each {@link #switchOwner} bumps a generation. A load runs off-thread
 *   for the owner it was started for, and its result is applied only if that
 *   generation and owner are still current: a delayed load for account A can
 *   never replace account B's state. The file must also name the owner.</li>
 *   <li>Every write resolves its destination from the owner it belongs to and
 *   serializes an immutable snapshot at the moment it is queued
 *   ({@link LocalStateStore#write} serializes on the caller's thread), never
 *   from whichever account is current when the writer thread runs.</li>
 *   <li>A step whose owner is not the current owner is ignored.</li>
 * </ul>
 *
 * <h3>What is written, and when</h3>
 * <ul>
 *   <li>{@code observations.jsonl} (append-only): every CHANGE, and every
 *   BASELINE (the observer emits one per patch per observation sequence, so a
 *   new sequence always records where it started). Unchanged reads within a
 *   sequence are never logged.</li>
 *   <li>{@code patches.json}: rewritten after any CHANGE, or a BASELINE that
 *   differs from what is stored; freshness-only updates (unchanged reads, and a
 *   new sequence's BASELINE of the stored value) are batched to at most once
 *   per {@link #FRESHNESS_FLUSH_MILLIS}, or on {@link #flush}. Never before the
 *   owner's existing file has been loaded, so a partial in-memory view can't
 *   overwrite what was on disk.</li>
 *   <li>{@code calibration.json}: when new growth-tick samples arrive (bounded
 *   to {@link #MAX_CALIBRATION_SAMPLES}).</li>
 * </ul>
 */
public final class FarmingStore
{
	public static final long FRESHNESS_FLUSH_MILLIS = 5 * 60_000L;
	public static final int MAX_CALIBRATION_SAMPLES = 500;

	private final LocalStateStore stateStore;
	private final FarmingObservationLog observationLog;
	private final Function<FarmingOwner, Filepath> dirs;
	private final Executor loadExecutor;
	private final FarmingPatchCatalog catalog;

	private FarmingOwner owner;
	private long generation;
	private boolean loaded;
	private final Map<String, PatchKnowledge> patches = new TreeMap<>();
	private final List<CalibrationSample> samples = new ArrayList<>();
	private boolean patchesDirty;
	private boolean freshnessDirty;
	private boolean calibrationDirty;
	private final Map<String, PatchCompost> compost = new TreeMap<>();
	private boolean compostDirty;
	private final Map<String, CompostBin.Seen> bins = new TreeMap<>();
	/** A bin's unchanged value is re-confirmed in the file at most this often (its "seen" time), never every tick. */
	static final long BIN_CONFIRM_MS = 10 * 60_000L;
	private long lastPatchesWriteAt = Long.MIN_VALUE;

	public FarmingStore(LocalStateStore stateStore, FarmingObservationLog observationLog, Function<FarmingOwner, Filepath> dirs,
		Executor loadExecutor, FarmingPatchCatalog catalog)
	{
		this.stateStore = stateStore;
		this.observationLog = observationLog;
		this.dirs = dirs;
		this.loadExecutor = loadExecutor;
		this.catalog = catalog;
	}

	/** Production folder mapping: {@code {accountHash}/profiles/{profile}/farming/}. */
	public static Function<FarmingOwner, Filepath> profileScopedDirs()
	{
		return o -> TelemetryPaths.farmingDir(o.getAccountHash(), o.getProfileKey());
	}

	/**
	 * Makes {@code next} the current owner (null = none). Whatever the previous
	 * owner still had unwritten is written to the previous owner's own files
	 * first; then all in-memory state is dropped and the new owner's files are
	 * loaded in the background.
	 */
	public synchronized void switchOwner(FarmingOwner next, long now)
	{
		if (next == null ? owner == null : next.equals(owner))
		{
			return;
		}
		if (owner != null)
		{
			writeDirty(now, true);
		}
		generation++;
		owner = next;
		loaded = false;
		patches.clear();
		samples.clear();
		compost.clear();
		bins.clear();
		compostDirty = false;
		patchesDirty = false;
		freshnessDirty = false;
		calibrationDirty = false;
		lastPatchesWriteAt = Long.MIN_VALUE;
		if (next != null)
		{
			long loadGeneration = generation;
			Filepath dir = dirs.apply(next);
			loadExecutor.execute(() -> load(loadGeneration, next, dir));
		}
	}

	private void load(long loadGeneration, FarmingOwner loadOwner, Filepath dir)
	{
		FarmingDocuments.Patches stored = stateStore.readIfExists(dir.joinSegment(FarmingDocuments.PATCHES_FILE), FarmingDocuments.Patches.class);
		FarmingDocuments.Calibration storedCalibration = stateStore.readIfExists(dir.joinSegment(FarmingDocuments.CALIBRATION_FILE), FarmingDocuments.Calibration.class);
		FarmingDocuments.Compost storedCompost = stateStore.readIfExists(dir.joinSegment(FarmingDocuments.COMPOST_FILE), FarmingDocuments.Compost.class);
		deliverCompost(loadGeneration, loadOwner, storedCompost);
		deliver(loadGeneration, loadOwner, stored, storedCalibration);
	}

	synchronized void deliverCompost(long loadGeneration, FarmingOwner loadOwner, FarmingDocuments.Compost stored)
	{
		if (loadGeneration != generation || !loadOwner.equals(owner) || stored == null || !stored.belongsTo(loadOwner))
		{
			return;
		}
		if (stored.bins != null)
		{
			for (Map.Entry<String, CompostBin.Seen> e : stored.bins.entrySet())
			{
				if (e.getValue() != null && CompostBin.def(e.getKey()) != null && !bins.containsKey(e.getKey()))
				{
					bins.put(e.getKey(), e.getValue());
				}
			}
		}
		if (stored.patches == null)
		{
			return;
		}
		for (Map.Entry<String, PatchCompost> e : stored.patches.entrySet())
		{
			if (e.getValue() != null && e.getValue().getKind() != null && !compost.containsKey(e.getKey()))
			{
				compost.put(e.getKey(), e.getValue()); // anything seen while loading is newer
			}
		}
	}

	/** Compost the game showed on a patch of the current owner (a newer sighting replaces an older one). */
	public synchronized void recordCompost(FarmingOwner expected, String patchId, PatchCompost record, long now)
	{
		if (expected == null || !expected.equals(owner) || record == null || !record.supersedes(compost.get(patchId)))
		{
			return;
		}
		compost.put(patchId, record);
		compostDirty = true;
		writeDirty(now, false);
	}

	/**
	 * A compost bin of the current owner read now (settled, with its area's patches). A new value is saved at once; an
	 * unchanged one only re-confirms its time every {@link #BIN_CONFIRM_MS}. A value the table does not know is not kept.
	 */
	public synchronized void recordBin(FarmingOwner expected, String binId, int raw, long now)
	{
		CompostBin.Def d = CompostBin.def(binId);
		if (expected == null || !expected.equals(owner) || d == null || CompostBin.decode(raw, d.isBig()) == null)
		{
			return;
		}
		CompostBin.Seen old = bins.get(binId);
		if (old != null && old.getRaw() == raw && now - old.getAt() < BIN_CONFIRM_MS)
		{
			return;
		}
		bins.put(binId, new CompostBin.Seen(raw, old != null && old.getRaw() == raw ? old.getSince() : now, now));
		compostDirty = true;
		writeDirty(now, false);
	}

	synchronized void deliver(long loadGeneration, FarmingOwner loadOwner, FarmingDocuments.Patches stored, FarmingDocuments.Calibration storedCalibration)
	{
		if (loadGeneration != generation || !loadOwner.equals(owner))
		{
			return; // a stale load for an owner that is no longer current
		}
		if (stored != null && stored.belongsTo(loadOwner) && stored.patches != null)
		{
			for (Map.Entry<String, PatchKnowledge> e : stored.patches.entrySet())
			{
				// Anything observed while the load was in flight is newer: keep it.
				if (e.getValue() != null && !patches.containsKey(e.getKey()))
				{
					PatchKnowledge redecoded = CropCycleAttribution.redecode(e.getValue(), catalog.patch(e.getKey()));
					patches.put(e.getKey(), redecoded);
					if (redecoded != e.getValue())
					{
						patchesDirty = true;
					}
				}
			}
		}
		if (storedCalibration != null && storedCalibration.belongsTo(loadOwner) && storedCalibration.samples != null)
		{
			List<CalibrationSample> merged = new ArrayList<>(storedCalibration.samples);
			merged.addAll(samples);
			samples.clear();
			samples.addAll(merged);
			trimSamples();
		}
		loaded = true;
	}

	/** Applies one observer step for the current owner (anything else is ignored). */
	public synchronized void apply(FarmingObserverStep step)
	{
		FarmingOwner stepOwner = step.getOwner();
		if (stepOwner == null || !stepOwner.equals(owner))
		{
			return;
		}
		for (PatchObservation observation : step.getObservations())
		{
			String id = observation.getPatch().getPatchId();
			PatchKnowledge known = patches.get(id);
			patches.put(id, CropCycleAttribution.apply(known, observation));
			if (isRepeatBaseline(known, observation))
			{
				freshnessDirty = true; // logged, but nothing about the patch changed
			}
			else
			{
				patchesDirty = true;
			}
		}
		for (String id : step.getUnchangedPatchIds())
		{
			PatchKnowledge known = patches.get(id);
			if (known != null)
			{
				patches.put(id, known.seenUnchanged(step.getObservedAt()));
				freshnessDirty = true;
			}
		}
		if (!step.getCalibrationSamples().isEmpty())
		{
			samples.addAll(step.getCalibrationSamples());
			trimSamples();
			calibrationDirty = true;
		}
		if (!step.getObservations().isEmpty())
		{
			observationLog.append(dirs.apply(owner).joinSegment(FarmingDocuments.OBSERVATIONS_FILE), step.getObservations());
		}
		writeDirty(step.getObservedAt(), false);
	}

	/**
	 * A new sequence's BASELINE of the value already stored (same raw value and
	 * decoder): it is still logged, but only advances freshness, so a scene load
	 * or relog at a patch does not rewrite {@code patches.json} at once.
	 */
	private static boolean isRepeatBaseline(PatchKnowledge known, PatchObservation observation)
	{
		return observation.getKind() == PatchObservation.Kind.BASELINE && known != null && !known.isDecodeStale()
			&& known.getLastRaw() == observation.getReading().getRaw()
			&& known.getDecoderVersion() == observation.getReading().getDecoderVersion();
	}

	/** Anything not yet queued for writing (for diagnostics). */
	public synchronized boolean hasPendingWrites()
	{
		return patchesDirty || freshnessDirty || calibrationDirty || compostDirty;
	}

	/** Writes everything still dirty (e.g. on logout), if the owner's files have been loaded. */
	public synchronized void flush(long now)
	{
		writeDirty(now, true);
	}

	/** Prunes the current owner's observation log on the log's own serial executor. */
	public synchronized void pruneObservationLog()
	{
		if (owner != null)
		{
			observationLog.prune(dirs.apply(owner).joinSegment(FarmingDocuments.OBSERVATIONS_FILE));
		}
	}

	public synchronized FarmingOwner getOwner()
	{
		return owner;
	}

	public synchronized boolean isLoaded()
	{
		return loaded;
	}

	/** An immutable copy of the current owner's knowledge. */
	public synchronized Map<String, PatchKnowledge> snapshot()
	{
		return Collections.unmodifiableMap(new TreeMap<>(patches));
	}

	/**
	 * The knowledge and calibration samples of {@code expected}, copied
	 * atomically, or null when the store holds another owner or has not loaded
	 * this one yet: a caller can never receive one account's data under another
	 * account's name.
	 */
	public synchronized FarmingSnapshot snapshotFor(FarmingOwner expected)
	{
		if (expected == null || !expected.equals(owner) || !loaded)
		{
			return null;
		}
		return new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, owner, new TreeMap<>(patches), new ArrayList<>(samples)).withCompost(compost).withBins(bins);
	}

	public synchronized List<CalibrationSample> calibrationSamples()
	{
		return Collections.unmodifiableList(new ArrayList<>(samples));
	}

	private void writeDirty(long now, boolean force)
	{
		if (owner == null || !loaded)
		{
			return; // never overwrite an owner's file before it has been read
		}
		Filepath dir = dirs.apply(owner);
		boolean freshnessDue = freshnessDirty && (force || lastPatchesWriteAt == Long.MIN_VALUE || now - lastPatchesWriteAt >= FRESHNESS_FLUSH_MILLIS);
		if (patchesDirty || freshnessDue)
		{
			stateStore.write(dir.joinSegment(FarmingDocuments.PATCHES_FILE), FarmingDocuments.Patches.of(owner, patches));
			patchesDirty = false;
			freshnessDirty = false;
			lastPatchesWriteAt = now;
		}
		if (calibrationDirty)
		{
			stateStore.write(dir.joinSegment(FarmingDocuments.CALIBRATION_FILE), FarmingDocuments.Calibration.of(owner, samples));
			calibrationDirty = false;
		}
		if (compostDirty)
		{
			stateStore.write(dir.joinSegment(FarmingDocuments.COMPOST_FILE), FarmingDocuments.Compost.of(owner, compost, bins));
			compostDirty = false;
		}
	}

	private void trimSamples()
	{
		while (samples.size() > MAX_CALIBRATION_SAMPLES)
		{
			samples.remove(0);
		}
	}
}
