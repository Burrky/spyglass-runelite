package com.osrstelemetry.plugin.hunter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A bird house run (the player's intent, as Farming's run intent): which spaces it includes, the bird house tier the player
 * chose for any house to build, what each space showed when it began, and what was skipped. Where the run is comes from the
 * spaces as read ({@link BirdhouseTraps}), never from a step counter: collecting, rebuilding and seeding in any order simply
 * moves each space on. A space is done when it is seen becoming seeded after the run began; a house seen being seeded before
 * the run began and still filling needs nothing this visit (settled, never recorded as done again); the run is complete when
 * every included space is done, filling or skipped. Readings arrive only from the island (and never from a scene still
 * loading), so travelling, a scene change or a restart leaves the run and its progress as they were. Saved per RuneScape profile (field names are the JSON); immutable.
 */
public final class BirdhouseRun
{
	public enum State
	{
		ACTIVE,
		COMPLETE,
		ENDED
	}

	/** The next thing a space needs, from what it shows now. */
	public enum Step
	{
		/** Not read on the island yet (since before the run): go there; what it needs shows on arrival. */
		CHECK,
		/** A house from before, full: empty it (or Reset: empty and rebuild in one). */
		EMPTY,
		/**
		 * A house seeded before Spyglass first saw it, with no reading of when: it may well be full (a house left from long ago)
		 * or still filling. Empty it; the game warns before emptying a house that is not full yet (pass 31).
		 */
		CHECK_FULL,
		/** Seeded before the run began (seen being seeded) and still filling: nothing to do here this visit. Settled. */
		FILLING,
		/** A bare space: build a bird house. */
		BUILD,
		/** A house with no seeds: add seeds. */
		SEED,
		/** Some seeds in: add the rest. */
		SEED_MORE,
		/** Seeded during this run. */
		DONE,
		/** Skipped by the player. */
		SKIPPED,
		/** A value Spyglass cannot read: nothing is guessed. */
		UNKNOWN
	}

	/** One space's place in the run now. */
	public static final class SpaceProgress
	{
		private final BirdhouseSpace space;
		private final Step step;
		private final BirdhouseTraps.Status status;

		SpaceProgress(BirdhouseSpace space, Step step, BirdhouseTraps.Status status)
		{
			this.space = space;
			this.step = step;
			this.status = status;
		}

		public BirdhouseSpace getSpace()
		{
			return space;
		}

		public Step getStep()
		{
			return step;
		}

		public BirdhouseTraps.Status getStatus()
		{
			return status;
		}

		/** Done, filling from before or skipped: nothing left here. */
		public boolean isSettled()
		{
			return step == Step.DONE || step == Step.SKIPPED || step == Step.FILLING;
		}
	}

	private String state = State.ACTIVE.name();
	/** Preparation is open (supplies first); guidance starts once it closes. */
	private boolean preparing = true;
	private List<String> spaces = new ArrayList<>();
	private String tier;
	private long startedAt;
	private Long finishedAt;
	/** Space → the value it showed when the run began (absent: not read yet then). */
	private Map<String, Integer> baseline = new TreeMap<>();
	private Set<String> skipped = new TreeSet<>();
	/** Space → when it was seen seeded during the run (kept so a finished run reads the same later). */
	private Map<String, Long> doneAt = new TreeMap<>();

	/** A new run over these spaces, prepared first. */
	public static BirdhouseRun start(Set<BirdhouseSpace> spaces, BirdhouseTier tier, BirdhouseTraps traps, long now)
	{
		BirdhouseRun r = new BirdhouseRun();
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			if (spaces.contains(s))
			{
				r.spaces.add(s.name());
				BirdhouseTraps.Trap t = traps == null ? null : traps.trap(s);
				if (t != null)
				{
					r.baseline.put(s.name(), t.value);
				}
			}
		}
		r.tier = tier == null ? null : tier.name();
		r.startedAt = now;
		return r;
	}

	private BirdhouseRun copy()
	{
		BirdhouseRun r = new BirdhouseRun();
		r.state = state;
		r.preparing = preparing;
		r.spaces = new ArrayList<>(spaces());
		r.tier = tier;
		r.startedAt = startedAt;
		r.finishedAt = finishedAt;
		r.baseline = new TreeMap<>(baseline == null ? Collections.emptyMap() : baseline);
		r.skipped = new TreeSet<>(skipped == null ? Collections.emptySet() : skipped);
		r.doneAt = new TreeMap<>(doneAt == null ? Collections.emptyMap() : doneAt);
		return r;
	}

	private List<String> spaces()
	{
		return spaces == null ? Collections.emptyList() : spaces;
	}

	public State getState()
	{
		try
		{
			return state == null ? State.ENDED : State.valueOf(state);
		}
		catch (IllegalArgumentException e)
		{
			return State.ENDED;
		}
	}

	public boolean isActive()
	{
		return getState() == State.ACTIVE;
	}

	public boolean isPreparing()
	{
		return isActive() && preparing;
	}

	public long getStartedAt()
	{
		return startedAt;
	}

	public Long getFinishedAt()
	{
		return finishedAt;
	}

	public BirdhouseTier getTier()
	{
		return BirdhouseTier.parse(tier);
	}

	/** The run's spaces, space order. */
	public List<BirdhouseSpace> getSpaces()
	{
		List<BirdhouseSpace> out = new ArrayList<>();
		for (String n : spaces())
		{
			BirdhouseSpace s = BirdhouseSpace.parse(n);
			if (s != null)
			{
				out.add(s);
			}
		}
		return out;
	}

	public boolean isSkipped(BirdhouseSpace s)
	{
		return skipped != null && skipped.contains(s.name());
	}

	public Integer baseline(BirdhouseSpace s)
	{
		return baseline == null ? null : baseline.get(s.name());
	}

	public BirdhouseRun withTier(BirdhouseTier t)
	{
		BirdhouseRun r = copy();
		r.tier = t == null ? null : t.name();
		return r;
	}

	public BirdhouseRun withSkipped(BirdhouseSpace s, boolean skip)
	{
		BirdhouseRun r = copy();
		if (skip)
		{
			r.skipped.add(s.name());
		}
		else
		{
			r.skipped.remove(s.name());
		}
		return r;
	}

	/** Preparation closed (Begin, or set off with everything confirmed): guidance starts. */
	public BirdhouseRun begun()
	{
		BirdhouseRun r = copy();
		r.preparing = false;
		return r;
	}

	public BirdhouseRun ended(long now)
	{
		BirdhouseRun r = copy();
		r.state = State.ENDED.name();
		r.finishedAt = now;
		return r;
	}

	/** Each space's next step from what it shows now. */
	public List<SpaceProgress> progress(BirdhouseTraps traps, long now)
	{
		List<SpaceProgress> out = new ArrayList<>();
		for (BirdhouseSpace s : getSpaces())
		{
			out.add(progress(s, traps, now));
		}
		return out;
	}

	public SpaceProgress progress(BirdhouseSpace s, BirdhouseTraps traps, long now)
	{
		BirdhouseTraps.Status st = traps == null ? null : traps.status(s, now);
		if (st == null)
		{
			st = BirdhouseTraps.empty().status(s, now);
		}
		if (doneAt != null && doneAt.containsKey(s.name()))
		{
			return new SpaceProgress(s, Step.DONE, st);
		}
		if (isSkipped(s))
		{
			return new SpaceProgress(s, Step.SKIPPED, st);
		}
		BirdhouseTraps.Trap t = traps == null ? null : traps.trap(s);
		switch (st.getKind())
		{
			case UNSEEN:
				return new SpaceProgress(s, Step.CHECK, st);
			case SPACE:
				return new SpaceProgress(s, Step.BUILD, st);
			case BUILT:
				return new SpaceProgress(s, Step.SEED, st);
			case PARTLY_SEEDED:
				return new SpaceProgress(s, Step.SEED_MORE, st);
			case CATCHING:
			case READY:
				if (t != null && t.armedAt != null && t.armedAt >= startedAt)
				{
					return new SpaceProgress(s, Step.DONE, st);
				}
				if (st.getKind() == BirdhouseTraps.Status.Kind.READY)
				{
					return new SpaceProgress(s, Step.EMPTY, st);
				}
				// Still filling: timed from a seeding Spyglass saw (before the run), nothing to do this visit; or seeded before
				// Spyglass ever saw it, so it may well be full already (no timer is invented for it).
				return new SpaceProgress(s, st.isUpperBound() ? Step.CHECK_FULL : Step.FILLING, st);
			default:
				return new SpaceProgress(s, Step.UNKNOWN, st);
		}
	}

	/**
	 * This run with the latest readings applied: spaces seen set during the run are recorded, and the run completes when every
	 * space is done or skipped. The same instance when nothing changed.
	 */
	public BirdhouseRun observe(BirdhouseTraps traps, long now)
	{
		if (!isActive())
		{
			return this;
		}
		BirdhouseRun r = null;
		boolean all = true;
		for (BirdhouseSpace s : getSpaces())
		{
			SpaceProgress p = progress(s, traps, now);
			if (p.getStep() == Step.DONE && (doneAt == null || !doneAt.containsKey(s.name())))
			{
				if (r == null)
				{
					r = copy();
				}
				BirdhouseTraps.Trap t = traps.trap(s);
				r.doneAt.put(s.name(), t != null && t.armedAt != null ? t.armedAt : now);
			}
			all &= p.isSettled();
		}
		if (all && !getSpaces().isEmpty())
		{
			if (r == null)
			{
				r = copy();
			}
			r.state = State.COMPLETE.name();
			r.preparing = false;
			r.finishedAt = now;
		}
		return r == null ? this : r;
	}

	/** Set (seeded this run, or filling from before) and skipped counts, and the total. */
	public int[] counts(BirdhouseTraps traps, long now)
	{
		int done = 0;
		int skip = 0;
		for (SpaceProgress p : progress(traps, now))
		{
			done += p.getStep() == Step.DONE || p.getStep() == Step.FILLING ? 1 : 0;
			skip += p.getStep() == Step.SKIPPED ? 1 : 0;
		}
		return new int[]{done, skip, getSpaces().size()};
	}

	/** The run's stops (spaces grouped), in space order. */
	public List<String> stops()
	{
		Set<String> out = new LinkedHashSet<>();
		for (BirdhouseSpace s : getSpaces())
		{
			out.add(s.getStop());
		}
		return new ArrayList<>(out);
	}

	/** Spaces by stop. */
	public Map<String, List<BirdhouseSpace>> byStop()
	{
		Map<String, List<BirdhouseSpace>> out = new java.util.LinkedHashMap<>();
		for (BirdhouseSpace s : getSpaces())
		{
			out.computeIfAbsent(s.getStop(), k -> new ArrayList<>()).add(s);
		}
		return out;
	}

	/** The selection as a set (for preparation and tests). */
	public Set<BirdhouseSpace> spaceSet()
	{
		Set<BirdhouseSpace> out = java.util.EnumSet.noneOf(BirdhouseSpace.class);
		out.addAll(getSpaces());
		return out;
	}

	static Map<BirdhouseSpace, Integer> emptyValues()
	{
		return new EnumMap<>(BirdhouseSpace.class);
	}
}
