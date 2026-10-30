package com.osrstelemetry.plugin.home;

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
 * The player's daily checklist (canonical Home §11): which {@link DailyTask}s they chose to track, and what the game last
 * showed for each. Immutable; saved per RuneScape profile as JSON (field names are the format).
 *
 * <p>Rules: the game clears each claim at the daily reset, 00:00 UTC. A claim read on an earlier UTC day than today has been
 * reset since, so it is available again (said as "new day", from the reset rule, until the game is read again). A claim's day
 * is the day its value was established: read at login, or seen changing; a value that only stays the same across midnight
 * while logged in keeps its old day (RuneLite's own indicator also treats a reset passing while logged in as available).
 * Nothing read yet is "not read", never "to do".
 */
public final class Dailies
{
	public static final long DAY_MILLIS = 86_400_000L;

	public enum State
	{
		/** The game has not shown this account's state for it yet. */
		NOT_READ,
		/** Not unlocked on this account (or not for its account type). */
		LOCKED,
		/** Unlocked, but needs something first (herb boxes: reward points). */
		NEEDS,
		/** Claimable now (all uses, or some left). */
		AVAILABLE,
		/** Claimed today (every use). */
		DONE
	}

	/** One task's state now. */
	public static final class Status
	{
		private final DailyTask task;
		private final State state;
		private final int remaining;
		private final int uses;
		private final boolean newDay;
		private final String note;

		Status(DailyTask task, State state, int remaining, int uses, boolean newDay, String note)
		{
			this.task = task;
			this.state = state;
			this.remaining = remaining;
			this.uses = uses;
			this.newDay = newDay;
			this.note = note;
		}

		public DailyTask getTask()
		{
			return task;
		}

		public State getState()
		{
			return state;
		}

		/** Uses left today (counted tasks; -1 when not known). */
		public int getRemaining()
		{
			return remaining;
		}

		/** Uses a day for a counted task (0 for a single claim, -1 when not known). */
		public int getUses()
		{
			return uses;
		}

		/** Available because the daily reset has passed since the last read (not read again yet). */
		public boolean isNewDay()
		{
			return newDay;
		}

		/** A short reason for LOCKED / NEEDS / NOT_READ, or null. */
		public String getNote()
		{
			return note;
		}
	}

	/** Field names are the saved JSON. */
	private Set<String> tracked;
	/** Task name → {claim value, UTC day it was established}. */
	private Map<String, long[]> claims = new TreeMap<>();
	/** Task name → its unlock variable's value as last read. */
	private Map<String, Integer> gates = new TreeMap<>();
	/** Morytania diary hard and elite (Robin's uses), as last read. */
	private Integer morytaniaHard;
	private Integer morytaniaElite;
	private Integer ironman;
	private Integer nmzPoints;

	public static Dailies empty()
	{
		return new Dailies();
	}

	private Dailies copy()
	{
		Dailies d = new Dailies();
		d.tracked = tracked == null ? null : new TreeSet<>(tracked);
		d.claims = new TreeMap<>();
		for (Map.Entry<String, long[]> e : claims().entrySet())
		{
			d.claims.put(e.getKey(), e.getValue().clone());
		}
		d.gates = new TreeMap<>(gates == null ? Collections.emptyMap() : gates);
		d.morytaniaHard = morytaniaHard;
		d.morytaniaElite = morytaniaElite;
		d.ironman = ironman;
		d.nmzPoints = nmzPoints;
		return d;
	}

	private Map<String, long[]> claims()
	{
		return claims == null ? Collections.emptyMap() : claims;
	}

	/** The player has chosen what to track (an empty choice counts). */
	public boolean isChosen()
	{
		return tracked != null;
	}

	public boolean isTracked(DailyTask t)
	{
		return tracked != null && tracked.contains(t.name());
	}

	public Dailies withTracked(DailyTask t, boolean on)
	{
		Dailies d = copy();
		if (d.tracked == null)
		{
			d.tracked = new TreeSet<>();
		}
		if (on)
		{
			d.tracked.add(t.name());
		}
		else
		{
			d.tracked.remove(t.name());
		}
		return d;
	}

	/** What the game shows now (client thread reads; {@code login}: the first read of a login, which re-establishes every claim's day). */
	public static final class Read
	{
		final Map<DailyTask, Integer> claims = new EnumMap<>(DailyTask.class);
		final Map<DailyTask, Integer> gates = new EnumMap<>(DailyTask.class);
		int morytaniaHard;
		int morytaniaElite;
		int ironman;
		int nmzPoints;

		public Read claim(DailyTask t, int value)
		{
			claims.put(t, value);
			return this;
		}

		public Read gate(DailyTask t, int value)
		{
			gates.put(t, value);
			return this;
		}

		public Read morytania(int hard, int elite)
		{
			morytaniaHard = hard;
			morytaniaElite = elite;
			return this;
		}

		public Read account(int ironman, int nmzPoints)
		{
			this.ironman = ironman;
			this.nmzPoints = nmzPoints;
			return this;
		}
	}

	/** This state with a read applied: the same instance when nothing changed (no save needed). */
	public Dailies with(Read r, boolean login, long now)
	{
		long today = Math.floorDiv(now, DAY_MILLIS);
		boolean changed = false;
		Dailies d = copy();
		for (Map.Entry<DailyTask, Integer> e : r.claims.entrySet())
		{
			long[] old = d.claims.get(e.getKey().name());
			if (old == null || old[0] != e.getValue() || login && old[1] != today)
			{
				d.claims.put(e.getKey().name(), new long[]{e.getValue(), today});
				changed = true;
			}
		}
		for (Map.Entry<DailyTask, Integer> e : r.gates.entrySet())
		{
			if (!e.getValue().equals(d.gates.get(e.getKey().name())))
			{
				d.gates.put(e.getKey().name(), e.getValue());
				changed = true;
			}
		}
		changed |= !Integer.valueOf(r.morytaniaHard).equals(d.morytaniaHard) || !Integer.valueOf(r.morytaniaElite).equals(d.morytaniaElite)
			|| !Integer.valueOf(r.ironman).equals(d.ironman) || !Integer.valueOf(r.nmzPoints).equals(d.nmzPoints);
		d.morytaniaHard = r.morytaniaHard;
		d.morytaniaElite = r.morytaniaElite;
		d.ironman = r.ironman;
		d.nmzPoints = r.nmzPoints;
		return changed ? d : this;
	}

	/** Every supported task's state now, catalogue order. */
	public List<Status> statuses(long now)
	{
		List<Status> out = new ArrayList<>();
		for (DailyTask t : DailyTask.values())
		{
			out.add(status(t, now));
		}
		return out;
	}

	public Status status(DailyTask t, long now)
	{
		long today = Math.floorDiv(now, DAY_MILLIS);
		long[] claim = claims().get(t.name());
		if (claim == null || ironman == null)
		{
			return new Status(t, State.NOT_READ, -1, t.isCounted() ? -1 : 0, false, "Log in to read it");
		}
		// Unlocks.
		if (t == DailyTask.HERB_BOXES)
		{
			if (ironman != 0)
			{
				return new Status(t, State.LOCKED, -1, 0, false, "Not for ironmen");
			}
		}
		else if (t.getGateVarbit() >= 0)
		{
			Integer gate = gates == null ? null : gates.get(t.name());
			if (gate == null)
			{
				return new Status(t, State.NOT_READ, -1, t.isCounted() ? -1 : 0, false, "Log in to read it");
			}
			if (gate < t.getGateMin() || t == DailyTask.SAND && ironman == 2)
			{
				return new Status(t, State.LOCKED, -1, 0, false, t == DailyTask.SAND && ironman == 2 ? "Not for ultimate ironmen" : "Needs " + t.getUnlock());
			}
		}
		boolean reset = claim[1] < today;
		int uses = 0;
		if (t == DailyTask.BONEMEAL)
		{
			uses = DailyTask.BONEMEAL_PER_TIER * (1 + (isOne(morytaniaHard) ? 1 + (isOne(morytaniaElite) ? 1 : 0) : 0));
		}
		else if (t == DailyTask.HERB_BOXES || t == DailyTask.IMPLING_JARS)
		{
			uses = t.getUsesPerTier();
		}
		if (uses > 0)
		{
			int left = reset ? uses : Math.max(0, uses - (int) claim[0]);
			if (left == 0)
			{
				return new Status(t, State.DONE, 0, uses, false, null);
			}
			if (t == DailyTask.HERB_BOXES && nmzPoints != null && nmzPoints < DailyTask.HERB_BOX_COST)
			{
				return new Status(t, State.NEEDS, left, uses, reset, "Needs 9,500 Nightmare Zone points");
			}
			return new Status(t, State.AVAILABLE, left, uses, reset, null);
		}
		if (reset || claim[0] == 0)
		{
			return new Status(t, State.AVAILABLE, 1, 0, reset, null);
		}
		return new Status(t, State.DONE, 0, 0, false, null);
	}

	private static boolean isOne(Integer v)
	{
		return v != null && v == 1;
	}

	/** Tracked tasks with something to claim now (Home's count). */
	public int availableCount(long now)
	{
		int n = 0;
		for (DailyTask t : trackedTasks())
		{
			n += status(t, now).getState() == State.AVAILABLE ? 1 : 0;
		}
		return n;
	}

	public List<DailyTask> trackedTasks()
	{
		List<DailyTask> out = new ArrayList<>();
		for (DailyTask t : DailyTask.values())
		{
			if (isTracked(t))
			{
				out.add(t);
			}
		}
		return out;
	}

	/** Milliseconds until the next daily reset (00:00 UTC). */
	public static long untilReset(long now)
	{
		return (Math.floorDiv(now, DAY_MILLIS) + 1) * DAY_MILLIS - now;
	}

	Set<String> trackedNames()
	{
		return tracked == null ? Collections.emptySet() : new LinkedHashSet<>(tracked);
	}
}
