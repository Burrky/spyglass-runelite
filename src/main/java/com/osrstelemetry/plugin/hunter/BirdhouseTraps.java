package com.osrstelemetry.plugin.hunter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What Spyglass has seen of the account's four bird house spaces, and the timers that follow from it. Saved per RuneScape
 * profile (field names are the JSON); immutable.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>A space is read only on Fossil Island's ground floor (the game sends the spaces there; RuneLite's Time Tracking reads
 *   them the same way). A space never read is {@link Status.Kind#UNSEEN}: never ready, never empty.</li>
 *   <li>A house is timed from the moment it was seen becoming seeded from a house read without (all) its seeds, or from a
 *   different seeded house (rebuilt unseen): it fills in {@link BirdhouseState#FILL_MILLIS}. A bare space never becomes
 *   seeded in one step in the game (a house is built first), so a space read bare and then seeded is the game sending the
 *   spaces after a scene load, never a seeding: no timer starts (pass 31; live 2026-10-29, a year-old house read as freshly
 *   seeded after a magic mushtree transfer). A house first seen already seeded was seeded at that moment or earlier: it is
 *   ready no later than 50 minutes after it was first seen, and says so ("by").</li>
 *   <li>A bare reading that replaced a house, followed by that same house again, was the same scene-load gap: the house is
 *   restored as it was (its timer and first-seen time kept).</li>
 *   <li>RuneLite's Time Tracking records when it saw each space take its value ({@code timetracking.birdhouse.<varp>},
 *   {@code value:epochSeconds}). When it saw the same seeded value earlier than Spyglass's timer allows, the house was seeded
 *   by then: Spyglass drops its own timer and times the house from that reading as an upper bound. Never a planting time
 *   invented, only the earlier of two real readings.</li>
 *   <li>Nothing changes without a reading: a restart, a logout or time away keeps every timer as it was.</li>
 *   <li>As RuneLite does, a reading in which more than two seen houses drop to a bare space at once is ignored (the game has
 *   not sent the spaces yet after arriving).</li>
 * </ul>
 */
public final class BirdhouseTraps
{
	/** One space as last read. */
	public static final class Trap
	{
		/** The space's value (see {@link BirdhouseState}). */
		int value;
		/** When this value was first read. */
		long since;
		/** When it was last read (the island). */
		long seenAt;
		/** Seeded: when the house was seen becoming seeded; null when first seen already seeded (see {@link #since}). */
		Long armedAt;
		/** A bare reading: the house it replaced (restored when that house is read again straight after); null otherwise. */
		Trap before;

		Trap()
		{
		}

		Trap(int value, long since, long seenAt, Long armedAt)
		{
			this.value = value;
			this.since = since;
			this.seenAt = seenAt;
			this.armedAt = armedAt;
		}

		public BirdhouseState state()
		{
			return BirdhouseState.of(value);
		}

		public long getSince()
		{
			return since;
		}

		public long getSeenAt()
		{
			return seenAt;
		}

		public Long getArmedAt()
		{
			return armedAt;
		}
	}

	/** A space's state now. */
	public static final class Status
	{
		public enum Kind
		{
			/** Never read on the island. */
			UNSEEN,
			/** A bare space. */
			SPACE,
			/** Built, no seeds. */
			BUILT,
			/** Some seeds, not yet full. */
			PARTLY_SEEDED,
			/** Seeded and filling. */
			CATCHING,
			/** Seeded at least 50 minutes ago: ready to empty. */
			READY,
			/** A value outside the known table. */
			UNKNOWN
		}

		private final BirdhouseSpace space;
		private final Kind kind;
		private final BirdhouseTier tier;
		/** When it is (or was) full: exact from the seeding, or the latest it can be ({@link #isUpperBound}); 0 when not seeded. */
		private final long readyAt;
		private final boolean upperBound;
		private final long seenAt;

		Status(BirdhouseSpace space, Kind kind, BirdhouseTier tier, long readyAt, boolean upperBound, long seenAt)
		{
			this.space = space;
			this.kind = kind;
			this.tier = tier;
			this.readyAt = readyAt;
			this.upperBound = upperBound;
			this.seenAt = seenAt;
		}

		public BirdhouseSpace getSpace()
		{
			return space;
		}

		public Kind getKind()
		{
			return kind;
		}

		public BirdhouseTier getTier()
		{
			return tier;
		}

		public long getReadyAt()
		{
			return readyAt;
		}

		/** The house was first seen already seeded: {@link #getReadyAt} is the latest it fills, not the exact time. */
		public boolean isUpperBound()
		{
			return upperBound;
		}

		/** When the space was last read (0: never). */
		public long getSeenAt()
		{
			return seenAt;
		}

		/** Seeded (filling or full). */
		public boolean isSet()
		{
			return kind == Kind.CATCHING || kind == Kind.READY;
		}
	}

	/** Space name → last reading. */
	private Map<String, Trap> traps = new TreeMap<>();
	/** The ready time last notified (dedupes the optional notification); 0 none. */
	private long notifiedReadyAt;

	public static BirdhouseTraps empty()
	{
		return new BirdhouseTraps();
	}

	private Map<String, Trap> traps()
	{
		return traps == null ? Collections.emptyMap() : traps;
	}

	private BirdhouseTraps copy()
	{
		BirdhouseTraps t = new BirdhouseTraps();
		t.traps = new TreeMap<>();
		for (Map.Entry<String, Trap> e : traps().entrySet())
		{
			Trap o = e.getValue();
			Trap c = new Trap(o.value, o.since, o.seenAt, o.armedAt);
			c.before = o.before == null ? null : new Trap(o.before.value, o.before.since, o.before.seenAt, o.before.armedAt);
			t.traps.put(e.getKey(), c);
		}
		t.notifiedReadyAt = notifiedReadyAt;
		return t;
	}

	public Trap trap(BirdhouseSpace s)
	{
		return traps().get(s.name());
	}

	/**
	 * This state with a reading on Fossil Island applied ({@code values}: each space's variable now). The same instance when
	 * nothing changed in a way worth saving (the last-read time is refreshed at most once a minute).
	 */
	public BirdhouseTraps withReading(Map<BirdhouseSpace, Integer> values, long now)
	{
		return withReading(values, null, now);
	}

	/**
	 * As {@link #withReading(Map, long)}, with RuneLite Time Tracking's own readings ({@code evidence}: space → {value, epoch
	 * millis it was first seen with that value}; null or absent: none) correcting a timer they contradict.
	 */
	public BirdhouseTraps withReading(Map<BirdhouseSpace, Integer> values, Map<BirdhouseSpace, long[]> evidence, long now)
	{
		int dropped = 0;
		for (Map.Entry<BirdhouseSpace, Integer> e : values.entrySet())
		{
			Trap old = trap(e.getKey());
			if (old != null && old.value > 0 && e.getValue() != null && e.getValue() <= 0)
			{
				dropped++;
			}
		}
		if (dropped > 2)
		{
			return this; // the spaces have not been sent yet after arriving (RuneLite's guard)
		}
		boolean changed = false;
		BirdhouseTraps next = copy();
		for (Map.Entry<BirdhouseSpace, Integer> e : values.entrySet())
		{
			if (e.getValue() == null)
			{
				continue;
			}
			int v = e.getValue();
			Trap old = trap(e.getKey());
			if (old == null || old.value != v)
			{
				next.traps.put(e.getKey().name(), read(old, v, now));
				changed = true;
			}
			else if (now - old.seenAt >= 60_000L)
			{
				next.traps.get(e.getKey().name()).seenAt = now;
				changed = true;
			}
		}
		if (evidence != null)
		{
			for (Map.Entry<BirdhouseSpace, long[]> e : evidence.entrySet())
			{
				changed |= next.corrected(e.getKey(), e.getValue(), now);
			}
		}
		return changed ? next : this;
	}

	/** The trap for a value read now that differs from {@code old} (null: never read). */
	static Trap read(Trap old, int v, long now)
	{
		BirdhouseState s = BirdhouseState.of(v);
		if (old != null && old.value == 0 && old.before != null && old.before.value == v && s.getStage() == BirdhouseState.Stage.SEEDED)
		{
			// Bare, then the same seeded house: the game had not sent the space yet (a house is never seeded straight from a
			// bare space). The house as it was, timer and all.
			return new Trap(v, old.before.since, now, old.before.armedAt);
		}
		Trap t = new Trap(v, now, now, armed(old, s) ? Long.valueOf(now) : null);
		if (v == 0 && old != null && old.value != 0)
		{
			Trap kept = old.value > 0 ? old : old.before;
			t.before = kept == null ? null : new Trap(kept.value, kept.since, kept.seenAt, kept.armedAt);
		}
		return t;
	}

	/** Whether reading {@code now} after {@code old} is the house being seeded (its timer starts). */
	static boolean armed(Trap old, BirdhouseState now)
	{
		if (now.getStage() != BirdhouseState.Stage.SEEDED || old == null)
		{
			return false;
		}
		BirdhouseState.Stage was = BirdhouseState.of(old.value).getStage();
		return was == BirdhouseState.Stage.BUILT || was == BirdhouseState.Stage.PARTLY_SEEDED
			|| was == BirdhouseState.Stage.SEEDED && old.value != now.getValue();
	}

	/**
	 * Applies one Time Tracking reading ({value, epoch millis}) to this (a copy being built): when it saw the same seeded value
	 * earlier than this house's timer or first sighting, the house was seeded by then. Whether anything changed.
	 */
	private boolean corrected(BirdhouseSpace space, long[] ev, long now)
	{
		Trap t = traps.get(space.name());
		if (ev == null || ev.length < 2 || t == null || t.value != ev[0] || ev[1] <= 0 || ev[1] > now
			|| BirdhouseState.of(t.value).getStage() != BirdhouseState.Stage.SEEDED)
		{
			return false;
		}
		long seen = ev[1];
		long margin = 2 * 60_000L; // the two readers see a change a tick or a few apart; minutes apart is a contradiction
		if (t.armedAt != null)
		{
			if (seen < t.armedAt - margin)
			{
				t.armedAt = null;
				t.since = seen;
				return true;
			}
			return false;
		}
		if (seen < t.since - margin)
		{
			t.since = seen;
			return true;
		}
		return false;
	}

	/** Every space's state now, in space order. */
	public List<Status> statuses(long now)
	{
		List<Status> out = new ArrayList<>();
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			out.add(status(s, now));
		}
		return out;
	}

	public Status status(BirdhouseSpace s, long now)
	{
		Trap t = trap(s);
		if (t == null)
		{
			return new Status(s, Status.Kind.UNSEEN, null, 0, false, 0);
		}
		BirdhouseState st = t.state();
		switch (st.getStage())
		{
			case SPACE:
				return new Status(s, Status.Kind.SPACE, null, 0, false, t.seenAt);
			case BUILT:
				return new Status(s, Status.Kind.BUILT, st.getTier(), 0, false, t.seenAt);
			case PARTLY_SEEDED:
				return new Status(s, Status.Kind.PARTLY_SEEDED, st.getTier(), 0, false, t.seenAt);
			case SEEDED:
				boolean bound = t.armedAt == null;
				long ready = (bound ? t.since : t.armedAt) + BirdhouseState.FILL_MILLIS;
				return new Status(s, now >= ready ? Status.Kind.READY : Status.Kind.CATCHING, st.getTier(), ready, bound, t.seenAt);
			default:
				return new Status(s, Status.Kind.UNKNOWN, null, 0, false, t.seenAt);
		}
	}

	/** The overall picture for Home and the Timers row. */
	public static final class Summary
	{
		public final int ready;
		public final int catching;
		public final int needsSetup;
		public final int unseen;
		/** Seeded before Spyglass first saw them, no reading of when: possibly full already (not in {@link #catching}). */
		public final int maybeFull;
		/** When the last filling house is full (0: none filling). */
		public final long allReadyAt;
		/** When the first filling house is full (0: none filling). */
		public final long nextReadyAt;

		Summary(int ready, int catching, int needsSetup, int unseen, int maybeFull, long allReadyAt, long nextReadyAt)
		{
			this.maybeFull = maybeFull;
			this.ready = ready;
			this.catching = catching;
			this.needsSetup = needsSetup;
			this.unseen = unseen;
			this.allReadyAt = allReadyAt;
			this.nextReadyAt = nextReadyAt;
		}
	}

	public Summary summary(long now)
	{
		int ready = 0;
		int catching = 0;
		int setup = 0;
		int unseen = 0;
		int maybe = 0;
		long all = 0;
		long next = 0;
		for (Status s : statuses(now))
		{
			switch (s.getKind())
			{
				case READY:
					ready++;
					break;
				case CATCHING:
					if (s.isUpperBound())
					{
						maybe++; // its "full by" is only the latest it can be; it is never counted as filling for a time
						break;
					}
					catching++;
					all = Math.max(all, s.getReadyAt());
					next = next == 0 ? s.getReadyAt() : Math.min(next, s.getReadyAt());
					break;
				case SPACE:
				case BUILT:
				case PARTLY_SEEDED:
					setup++;
					break;
				default:
					unseen++;
			}
		}
		return new Summary(ready, catching, setup, unseen, maybe, all, next);
	}

	/** Whether every seen house filled since the last notification (the optional notification fires once per filling). */
	public boolean shouldNotify(long now)
	{
		Summary s = summary(now);
		if (s.ready == 0 || s.catching > 0)
		{
			return false;
		}
		long readyAt = 0;
		for (Status st : statuses(now))
		{
			if (st.getKind() == Status.Kind.READY)
			{
				readyAt = Math.max(readyAt, st.getReadyAt());
			}
		}
		return readyAt > notifiedReadyAt;
	}

	public BirdhouseTraps withNotified(long now)
	{
		BirdhouseTraps t = copy();
		long readyAt = 0;
		for (Status st : statuses(now))
		{
			if (st.getKind() == Status.Kind.READY)
			{
				readyAt = Math.max(readyAt, st.getReadyAt());
			}
		}
		t.notifiedReadyAt = readyAt;
		return t;
	}

	/** The values last read, for a run's baseline (spaces never read are absent). */
	public Map<BirdhouseSpace, Integer> values()
	{
		Map<BirdhouseSpace, Integer> out = new EnumMap<>(BirdhouseSpace.class);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			Trap t = trap(s);
			if (t != null)
			{
				out.put(s, t.value);
			}
		}
		return out;
	}
}
