package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, owner-safe copy of what the Farming UI may show, taken by
 * {@link FarmingCaptureAdapter#uiSnapshot()}. Knowledge is only ever the
 * CURRENT eligible STANDARD owner's, and only once that owner's files have
 * loaded; otherwise it is empty and {@link #getState()} says why.
 */
public final class FarmingSnapshot
{
	public enum State
	{
		/** Not logged in (or the plugin is not capturing yet). */
		LOGGED_OUT,
		/** Logged in on a profile or world type whose Farming data is not kept (STANDARD only). */
		NOT_AVAILABLE_HERE,
		/** The owner's saved knowledge is still loading. */
		LOADING,
		AVAILABLE
	}

	public static final FarmingSnapshot LOGGED_OUT = new FarmingSnapshot(State.LOGGED_OUT, null, Collections.emptyMap(), Collections.emptyList());

	private final State state;
	private final FarmingOwner owner;
	private final Map<String, PatchKnowledge> knowledge;
	private final List<CalibrationSample> samples;
	private final FarmingRunIntent run;
	private final Set<String> herePatchIds;
	private final List<String> currentPatchIds;
	/** Compost seen on patches (the game's own words), by patch id; empty when none was seen. */
	private Map<String, PatchCompost> compost = Collections.emptyMap();
	/** Compost bins as last seen, by bin id; a bin never seen is absent (never assumed empty). */
	private Map<String, CompostBin.Seen> bins = Collections.emptyMap();

	public FarmingSnapshot(State state, FarmingOwner owner, Map<String, PatchKnowledge> knowledge, List<CalibrationSample> samples)
	{
		this(state, owner, knowledge, samples, null, Collections.emptySet());
	}

	/**
	 * {@code run}: the owner's run intent (open, or completed and awaiting
	 * Done), or null. {@code herePatchIds}: the patches the player is at right
	 * now (empty when unknown). Both belong to {@code owner} only.
	 */
	public FarmingSnapshot(State state, FarmingOwner owner, Map<String, PatchKnowledge> knowledge, List<CalibrationSample> samples,
		FarmingRunIntent run, Set<String> herePatchIds)
	{
		this(state, owner, knowledge, samples, run, herePatchIds, Collections.emptyList());
	}

	/** As above, with the stop guidance is on now ({@code currentPatchIds}, in its action order; empty: none). */
	public FarmingSnapshot(State state, FarmingOwner owner, Map<String, PatchKnowledge> knowledge, List<CalibrationSample> samples,
		FarmingRunIntent run, Set<String> herePatchIds, List<String> currentPatchIds)
	{
		this.state = state;
		this.owner = owner;
		this.knowledge = Collections.unmodifiableMap(knowledge);
		this.samples = Collections.unmodifiableList(samples);
		this.run = run;
		this.herePatchIds = Collections.unmodifiableSet(herePatchIds);
		this.currentPatchIds = Collections.unmodifiableList(currentPatchIds);
	}

	/** This snapshot with the owner's run and location added (same owner, same knowledge). */
	FarmingSnapshot withRun(FarmingRunIntent intent, Set<String> here)
	{
		return new FarmingSnapshot(state, owner, knowledge, samples, intent, here).withCompost(compost).withBins(bins);
	}

	/** This snapshot with the owner's compost records. */
	public FarmingSnapshot withCompost(Map<String, PatchCompost> records)
	{
		FarmingSnapshot s = new FarmingSnapshot(state, owner, knowledge, samples, run, herePatchIds, currentPatchIds);
		s.compost = records == null ? Collections.emptyMap() : Collections.unmodifiableMap(new java.util.TreeMap<>(records));
		s.bins = bins;
		return s;
	}

	/** This snapshot with the owner's compost bin sightings. */
	public FarmingSnapshot withBins(Map<String, CompostBin.Seen> seen)
	{
		FarmingSnapshot s = new FarmingSnapshot(state, owner, knowledge, samples, run, herePatchIds, currentPatchIds);
		s.compost = compost;
		s.bins = seen == null ? Collections.emptyMap() : Collections.unmodifiableMap(new java.util.TreeMap<>(seen));
		return s;
	}

	public Map<String, CompostBin.Seen> getBins()
	{
		return bins;
	}

	/** The compost that applies to this patch's current (or next) planting, or null when none was seen. */
	public PatchCompost compostFor(String patchId)
	{
		PatchCompost r = compost.get(patchId);
		return r == null ? null : r.current(knowledge.get(patchId));
	}

	public Map<String, PatchCompost> getCompost()
	{
		return compost;
	}

	/** This snapshot with the owner's run, location and the current guidance stop added. */
	FarmingSnapshot withRun(FarmingRunIntent intent, Set<String> here, List<String> current)
	{
		return new FarmingSnapshot(state, owner, knowledge, samples, intent, here, current).withCompost(compost).withBins(bins);
	}

	/** The stop guidance is on now (patch ids in its action order), empty when none: every surface leads with it. */
	public List<String> getCurrentPatchIds()
	{
		return currentPatchIds;
	}

	public FarmingRunIntent getRun()
	{
		return run;
	}

	public Set<String> getHerePatchIds()
	{
		return herePatchIds;
	}

	public State getState()
	{
		return state;
	}

	/** Null unless {@link State#AVAILABLE} or {@link State#LOADING}. */
	public FarmingOwner getOwner()
	{
		return owner;
	}

	public Map<String, PatchKnowledge> getKnowledge()
	{
		return knowledge;
	}

	public List<CalibrationSample> getSamples()
	{
		return samples;
	}
}
