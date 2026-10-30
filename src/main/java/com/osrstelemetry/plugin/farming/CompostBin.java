package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.gameval.VarbitID;

/**
 * Compost bins (canonical §28.15, §28.16): which bins there are, what the game's farming varbit says each holds, and the one
 * next task for a bin the player included in runs. Pure.
 *
 * <p>Identity (verified 2026-10-22): each bin is one placed object whose definition is a multiloc on a farming transmit
 * varbit. Object ids, tiles and varbits from the game cache: osrs-dumps @6e5033af (Joshua-F, loc definitions:
 * {@code farming_compost_bin_1..7} on {@code farming_transmit_e} except bin 6 on {@code farming_transmit_d};
 * {@code farming_compost_bigbin_guild} on {@code farming_transmit_n}) and mejrs/data_osrs @6a3ca6f1 (object placements).
 * The bin is read only with its own area's patches (the transmit varbits describe that area there), never guessed while away,
 * and highlighted as that object id at that tile.
 *
 * <p>Values: the cache's multiloc table (which model and menu each value shows: "Close" only on a full bin, "Open" on a rotted
 * one, "Take" on finished compost), cross-checked against RuneLite's PatchImplementation COMPOST / BIG_COMPOST (master
 * @ e98399f931c6, BSD-2), which knew only some of the rotted values. OSRS Wiki "Compost Bin", "Ultracompost", "Compost
 * potion" (2026-10-22): 15 items (the Farming Guild's big bin 30); rotting takes 35 to 70 minutes after closing and the bin
 * cannot be opened before; 25 volcanic ash (50 in the big bin) on a bin of supercompost makes ultracompost; one dose of compost
 * potion on a bin of compost makes supercompost. The rotting time is used only to say a bin seen rotting more than 70 minutes
 * ago has rotted by now; nothing else is projected.
 */
public final class CompostBin
{
	/** A catalogued bin: read with the patches of {@link #anchorPatchId}'s area (same region, bounds and plane). */
	public static final class Def
	{
		private final String binId;
		private final String place;
		private final String anchorPatchId;
		private final int varbit;
		private final boolean big;
		private final int objectId;
		private final int x;
		private final int y;
		private final int plane;

		Def(String binId, String place, String anchorPatchId, int varbit, boolean big, int objectId, int x, int y, int plane)
		{
			this.binId = binId;
			this.place = place;
			this.anchorPatchId = anchorPatchId;
			this.varbit = varbit;
			this.big = big;
			this.objectId = objectId;
			this.x = x;
			this.y = y;
			this.plane = plane;
		}

		public String getBinId()
		{
			return binId;
		}

		public String getPlace()
		{
			return place;
		}

		public String getAnchorPatchId()
		{
			return anchorPatchId;
		}

		public int getVarbit()
		{
			return varbit;
		}

		public boolean isBig()
		{
			return big;
		}

		/** The placed object's id (the multiloc; the scene object carries it whatever the bin shows). */
		public int getObjectId()
		{
			return objectId;
		}

		public int getX()
		{
			return x;
		}

		public int getY()
		{
			return y;
		}

		public int getPlane()
		{
			return plane;
		}

		public int capacity()
		{
			return big ? 30 : 15;
		}

		/** Volcanic ash that turns this bin's supercompost into ultracompost. */
		public int ash()
		{
			return big ? 50 : 25;
		}
	}

	public static final List<Def> ALL = Collections.unmodifiableList(new ArrayList<Def>()
	{
		{
			add(new Def("bin.ardougne", "Ardougne", "herb.ardougne", VarbitID.FARMING_TRANSMIT_E, false, 7839, 2661, 3375, 0));
			add(new Def("bin.catherby", "Catherby", "herb.catherby", VarbitID.FARMING_TRANSMIT_E, false, 7837, 2804, 3464, 0));
			add(new Def("bin.civitas", "Civitas illa Fortis", "herb.civitas", VarbitID.FARMING_TRANSMIT_E, false, 50694, 1588, 3103, 0));
			add(new Def("bin.falador", "Falador", "herb.falador", VarbitID.FARMING_TRANSMIT_E, false, 7836, 3056, 3312, 0));
			add(new Def("bin.farming_guild", "Farming Guild", "herb.farming_guild", VarbitID.FARMING_TRANSMIT_N, true, 34631, 1272, 3729, 0));
			add(new Def("bin.hosidius", "Hosidius", "herb.hosidius", VarbitID.FARMING_TRANSMIT_E, false, 27112, 1730, 3558, 0));
			add(new Def("bin.morytania", "Port Phasmatys", "herb.morytania", VarbitID.FARMING_TRANSMIT_E, false, 7838, 3610, 3522, 0));
			add(new Def("bin.prifddinas", "Prifddinas", "allotment.prifddinas.n", VarbitID.FARMING_TRANSMIT_D, false, 34920, 3287, 6100, 0));
		}
	});

	public static Def def(String binId)
	{
		for (Def d : ALL)
		{
			if (d.binId.equals(binId))
			{
				return d;
			}
		}
		return null;
	}

	/** The bins read with these patches (the area's context), in catalogue order. */
	public static List<Def> withPatches(java.util.Collection<String> patchIds)
	{
		List<Def> out = new ArrayList<>();
		for (Def d : ALL)
		{
			if (patchIds.contains(d.anchorPatchId))
			{
				out.add(d);
			}
		}
		return out;
	}

	public enum Phase
	{
		/** Nothing in it. */
		EMPTY,
		/** Being filled, open. */
		FILLING,
		/** Closed, rotting. */
		ROTTING,
		/** Closed and rotted: open it. */
		ROTTED,
		/** Open, finished compost to take. */
		READY
	}

	public enum Contents
	{
		NONE(""),
		COMPOST("Compost"),
		SUPERCOMPOST("Supercompost"),
		ULTRACOMPOST("Ultracompost"),
		ROTTEN_TOMATOES("Rotten tomatoes");

		private final String label;

		Contents(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	/** What a varbit value says (count: items or buckets in it; 0 when the game does not say, while rotting). */
	public static final class State
	{
		public final Phase phase;
		public final Contents contents;
		public final int count;

		State(Phase phase, Contents contents, int count)
		{
			this.phase = phase;
			this.contents = contents;
			this.count = count;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof State && ((State) o).phase == phase && ((State) o).contents == contents && ((State) o).count == count;
		}

		@Override
		public int hashCode()
		{
			return phase.hashCode() * 31 * 31 + contents.hashCode() * 31 + count;
		}

		@Override
		public String toString()
		{
			return phase + " " + contents + " " + count;
		}
	}

	/** The state a bin's varbit value shows, or null for a value the table does not know (never guessed). */
	public static State decode(int value, boolean big)
	{
		return big ? decodeBig(value) : decodeSmall(value);
	}

	private static State decodeSmall(int v)
	{
		if (v == 0)
		{
			return new State(Phase.EMPTY, Contents.NONE, 0);
		}
		if (v >= 1 && v <= 15)
		{
			return new State(Phase.FILLING, Contents.COMPOST, v);
		}
		if (v >= 16 && v <= 30)
		{
			return new State(Phase.READY, Contents.COMPOST, v - 15);
		}
		if (v == 31 || v == 32 || v >= 64 && v <= 79)
		{
			return new State(Phase.ROTTING, Contents.COMPOST, 0);
		}
		if (v >= 33 && v <= 47)
		{
			return new State(Phase.FILLING, Contents.SUPERCOMPOST, v - 32);
		}
		if (v >= 48 && v <= 62)
		{
			return new State(Phase.READY, Contents.SUPERCOMPOST, v - 47);
		}
		if (v >= 80 && v <= 94)
		{
			return new State(Phase.ROTTED, Contents.COMPOST, v - 79);
		}
		if (v == 95 || v == 96 || v >= 97 && v <= 111)
		{
			return new State(Phase.ROTTING, Contents.SUPERCOMPOST, 0);
		}
		if (v >= 112 && v <= 126)
		{
			return new State(Phase.ROTTED, Contents.SUPERCOMPOST, v - 111);
		}
		if (v >= 129 && v <= 143)
		{
			return new State(Phase.FILLING, Contents.ROTTEN_TOMATOES, v - 128);
		}
		if (v >= 144 && v <= 158)
		{
			return new State(Phase.READY, Contents.ROTTEN_TOMATOES, v - 143);
		}
		if (v == 159 || v == 160 || v >= 193 && v <= 207)
		{
			return new State(Phase.ROTTING, Contents.ROTTEN_TOMATOES, 0);
		}
		if (v >= 176 && v <= 190)
		{
			return new State(Phase.READY, Contents.ULTRACOMPOST, v - 175);
		}
		if (v >= 208 && v <= 222)
		{
			return new State(Phase.ROTTED, Contents.ROTTEN_TOMATOES, v - 207);
		}
		return null;
	}

	private static State decodeBig(int v)
	{
		if (v == 0)
		{
			return new State(Phase.EMPTY, Contents.NONE, 0);
		}
		if (v >= 1 && v <= 15)
		{
			return new State(Phase.FILLING, Contents.COMPOST, v);
		}
		if (v >= 16 && v <= 30)
		{
			return new State(Phase.READY, Contents.COMPOST, v - 15);
		}
		if (v == 31 || v == 32 || v == 127 || v == 128)
		{
			return new State(Phase.ROTTING, Contents.COMPOST, 0);
		}
		if (v >= 33 && v <= 47)
		{
			return new State(Phase.FILLING, Contents.SUPERCOMPOST, v - 32);
		}
		if (v >= 48 && v <= 62)
		{
			return new State(Phase.READY, Contents.SUPERCOMPOST, v - 47);
		}
		if (v >= 63 && v <= 77)
		{
			return new State(Phase.FILLING, Contents.COMPOST, 15 + v - 62);
		}
		if (v >= 78 && v <= 92)
		{
			return new State(Phase.READY, Contents.COMPOST, 15 + v - 77);
		}
		if (v == 93 || v == 94)
		{
			return new State(Phase.ROTTED, Contents.COMPOST, 0);
		}
		if (v >= 95 && v <= 98)
		{
			return new State(Phase.ROTTING, Contents.SUPERCOMPOST, 0);
		}
		if (v == 99 || v == 126)
		{
			return new State(Phase.ROTTED, Contents.SUPERCOMPOST, 0);
		}
		if (v >= 100 && v <= 114)
		{
			return new State(Phase.READY, Contents.SUPERCOMPOST, 15 + v - 99);
		}
		if (v == 124 || v == 125 || v == 159 || v == 160)
		{
			return new State(Phase.ROTTING, Contents.ROTTEN_TOMATOES, 0);
		}
		if (v >= 129 && v <= 143)
		{
			return new State(Phase.FILLING, Contents.ROTTEN_TOMATOES, v - 128);
		}
		if (v >= 144 && v <= 158)
		{
			return new State(Phase.READY, Contents.ROTTEN_TOMATOES, v - 143);
		}
		if (v >= 161 && v <= 175)
		{
			return new State(Phase.FILLING, Contents.SUPERCOMPOST, 15 + v - 160);
		}
		if (v >= 176 && v <= 205)
		{
			return new State(Phase.READY, Contents.ULTRACOMPOST, v - 175);
		}
		if (v == 206 || v == 222)
		{
			return new State(Phase.ROTTED, Contents.ROTTEN_TOMATOES, 0);
		}
		if (v >= 207 && v <= 221)
		{
			return new State(Phase.READY, Contents.ROTTEN_TOMATOES, 15 + v - 206);
		}
		if (v >= 223 && v <= 237)
		{
			return new State(Phase.FILLING, Contents.ROTTEN_TOMATOES, 15 + v - 222);
		}
		return null; // 115-123 show an empty bin in the cache with no menu: meaning unknown, never guessed
	}

	/** A bin as last seen: the raw value, when it took that value (first seen so), and when it was last confirmed. Saved. */
	public static final class Seen
	{
		private int raw;
		private long since;
		private long at;

		Seen()
		{
		}

		Seen(int raw, long since, long at)
		{
			this.raw = raw;
			this.since = since;
			this.at = at;
		}

		public int getRaw()
		{
			return raw;
		}

		/** When this value was first seen (a new value restarts it). */
		public long getSince()
		{
			return since;
		}

		/** When this value was last confirmed. */
		public long getAt()
		{
			return at;
		}

		public State state(Def d)
		{
			return d == null ? null : decode(raw, d.isBig());
		}
	}

	/** What the player chose to make of a bin's compost (Farming settings), never chosen by Spyglass. */
	public enum Upgrade
	{
		/** Take it as it rotted. */
		NONE,
		/** A dose of compost potion on finished compost: supercompost. */
		SUPER,
		/** Up to ultracompost: the potion on compost, then volcanic ash on supercompost. */
		ULTRA
	}

	/** One thing to do at a bin. */
	public enum TaskKind
	{
		CLOSE,
		OPEN,
		POTION,
		ASH,
		TAKE
	}

	/** The next task at a bin, with what it needs carried. */
	public static final class Task
	{
		public final TaskKind kind;
		public final String label;
		/** Rotted by now from the time it was seen rotting (not yet seen rotted). */
		public final boolean projected;
		public final int buckets;
		public final int ash;
		public final int potionDoses;

		Task(TaskKind kind, String label, boolean projected, int buckets, int ash, int potionDoses)
		{
			this.kind = kind;
			this.label = label;
			this.projected = projected;
			this.buckets = buckets;
			this.ash = ash;
			this.potionDoses = potionDoses;
		}
	}

	/** Rotting ends at most this long after closing (OSRS Wiki: 35 to 70 minutes). */
	public static final long ROT_MAX_MILLIS = 70 * 60_000L;

	/**
	 * The one next task for a bin as last seen, or null when there is none: close a full bin, open a rotted one (or one seen
	 * rotting more than {@link #ROT_MAX_MILLIS} ago, said as probable), apply the chosen upgrade, take the finished compost.
	 * An empty or part-filled bin waits for the player's own compostables (Spyglass never picks them); a bin still rotting is
	 * never waited for. Take needs one empty bucket per bucket of compost (rotten tomatoes are taken by hand).
	 */
	public static Task task(Def d, Seen seen, Upgrade upgrade, long now)
	{
		State s = seen == null ? null : seen.state(d);
		if (d == null || s == null)
		{
			return null;
		}
		Upgrade up = upgrade == null ? Upgrade.NONE : upgrade;
		switch (s.phase)
		{
			case FILLING:
				return s.count >= d.capacity() ? new Task(TaskKind.CLOSE, "Close the compost bin", false, 0, 0, 0) : null;
			case ROTTING:
				if (now - seen.getSince() < ROT_MAX_MILLIS)
				{
					return null;
				}
				return new Task(TaskKind.OPEN, "Open the compost bin (rotted by now)", true, 0, 0, 0);
			case ROTTED:
				return new Task(TaskKind.OPEN, "Open the compost bin", false, 0, 0, 0);
			case READY:
				if (s.contents == Contents.COMPOST && up != Upgrade.NONE)
				{
					return new Task(TaskKind.POTION, "Use a compost potion on the bin", false, 0, 0, 1);
				}
				if (s.contents == Contents.SUPERCOMPOST && up == Upgrade.ULTRA)
				{
					return new Task(TaskKind.ASH, "Add " + d.ash() + " volcanic ash to the bin", false, 0, d.ash(), 0);
				}
				if (s.contents == Contents.ROTTEN_TOMATOES)
				{
					return new Task(TaskKind.TAKE, "Take the rotten tomatoes", false, 0, 0, 0);
				}
				return new Task(TaskKind.TAKE, "Take " + s.contents.getLabel().toLowerCase(Locale.ROOT) + " from the bin · " + s.count, false,
					s.count, 0, 0);
			default:
				return null;
		}
	}

	/**
	 * What the rest of this bin's work needs carried from here (the next task and those that follow from it): a potion dose and
	 * ash for the chosen upgrade, and a bucket per bucket of compost. A rotted bin's count is the cache's per-count value (none
	 * for the big bin's rotted states); a bin seen only rotting has no known count.
	 */
	public static int[] supplies(Def d, Seen seen, Upgrade upgrade, long now)
	{
		Task t = task(d, seen, upgrade, now);
		State s = seen == null ? null : seen.state(d);
		if (t == null || s == null || t.kind == TaskKind.CLOSE)
		{
			return new int[]{0, 0, 0};
		}
		Upgrade up = upgrade == null ? Upgrade.NONE : upgrade;
		Contents c = s.contents;
		int buckets = s.phase == Phase.READY || s.phase == Phase.ROTTED ? s.count : 0;
		if (c == Contents.ROTTEN_TOMATOES)
		{
			return new int[]{0, 0, 0};
		}
		int potion = c == Contents.COMPOST && up != Upgrade.NONE ? 1 : 0;
		int ash = up == Upgrade.ULTRA && (c == Contents.COMPOST || c == Contents.SUPERCOMPOST) ? d.ash() : 0;
		if (s.phase == Phase.READY && c == Contents.SUPERCOMPOST && up != Upgrade.ULTRA || s.phase == Phase.READY && c == Contents.ULTRACOMPOST)
		{
			potion = 0;
		}
		if (s.phase == Phase.READY && c == Contents.ULTRACOMPOST)
		{
			ash = 0;
		}
		return new int[]{buckets, ash, potion};
	}

	/** "Supercompost · 15 ready", "Supercompost rotting", "Compost · 7 of 15 in", "Empty" (the overview's line). */
	public static String describe(Def d, State s)
	{
		if (s == null)
		{
			return "Not recognised";
		}
		switch (s.phase)
		{
			case EMPTY:
				return "Empty";
			case FILLING:
				return s.contents.getLabel() + " · " + s.count + " of " + d.capacity() + " in";
			case ROTTING:
				return s.contents.getLabel() + " rotting";
			case ROTTED:
				return s.contents.getLabel() + " rotted · open it";
			case READY:
			default:
				return s.contents.getLabel() + " · " + s.count + " ready";
		}
	}

	private CompostBin()
	{
	}
}
