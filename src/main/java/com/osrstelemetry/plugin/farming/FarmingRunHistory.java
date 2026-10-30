package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * Finished farm runs for the History detail of a Farming session (canonical
 * §28.7, History). Reads only the run outcome files the run store writes once
 * when a run completes or is ended ({@code profiles/<profile>/farming/runs/}),
 * so a session's detail never changes after the fact and nothing is tracked
 * twice: XP and loot stay the session's own. A run has one primary session
 * (its full record) and every other Farming session it overlapped shows a
 * reference to it; one that started before or ended after the session says
 * so. Only the account's own files are read (the account directory), and a
 * record names its account so a mismatched file is skipped.
 */
public final class FarmingRunHistory
{
	public static final class Record
	{
		private final String intentId;
		private final String title;
		private final long startedAt;
		private final long finishedAt;
		private final boolean complete;
		private final int total;
		private final int done;
		private final int replanted;
		private final int harvested;
		private final int skipped;
		private final int unfinished;
		private final Map<String, Integer> planted;
		private final boolean startedBefore;
		private final boolean endedAfter;
		/** This session holds the run's one full record; false = a reference to the run recorded in another Farming session. */
		private final boolean primary;
		/** Compost bins of the run worked / skipped (§28.17; 0 in older files). */
		private int binsWorked;
		private int binsSkipped;

		Record(String intentId, String title, long startedAt, long finishedAt, boolean complete, int total, int done, int replanted, int harvested,
			int skipped, int unfinished, Map<String, Integer> planted, boolean startedBefore, boolean endedAfter)
		{
			this.intentId = intentId;
			this.title = title;
			this.startedAt = startedAt;
			this.finishedAt = finishedAt;
			this.complete = complete;
			this.total = total;
			this.done = done;
			this.replanted = replanted;
			this.harvested = harvested;
			this.skipped = skipped;
			this.unfinished = unfinished;
			this.planted = Collections.unmodifiableMap(planted);
			this.startedBefore = startedBefore;
			this.endedAfter = endedAfter;
			this.primary = true;
		}

		private Record(Record r, boolean primary)
		{
			this.intentId = r.intentId;
			this.title = r.title;
			this.startedAt = r.startedAt;
			this.finishedAt = r.finishedAt;
			this.complete = r.complete;
			this.total = r.total;
			this.done = r.done;
			this.replanted = r.replanted;
			this.harvested = r.harvested;
			this.skipped = r.skipped;
			this.unfinished = r.unfinished;
			this.planted = r.planted;
			this.startedBefore = r.startedBefore;
			this.endedAfter = r.endedAfter;
			this.primary = primary;
			this.binsWorked = r.binsWorked;
			this.binsSkipped = r.binsSkipped;
		}

		/**
		 * True for the one Farming session that holds the run's full record (outcome counts, crops planted); false for any other
		 * Farming session the run took part in, which shows only a reference to the same run, never its counts again.
		 */
		public boolean isPrimary()
		{
			return primary;
		}

		public String getIntentId()
		{
			return intentId;
		}

		/** "Herb + Tree run". */
		public String getTitle()
		{
			return title;
		}

		public long getStartedAt()
		{
			return startedAt;
		}

		public long getFinishedAt()
		{
			return finishedAt;
		}

		/** Every patch settled (true) or ended by the player (false). */
		public boolean isComplete()
		{
			return complete;
		}

		public int getTotal()
		{
			return total;
		}

		public int getDone()
		{
			return done;
		}

		public int getReplanted()
		{
			return replanted;
		}

		public int getHarvested()
		{
			return harvested;
		}

		public int getSkipped()
		{
			return skipped;
		}

		public int getUnfinished()
		{
			return unfinished;
		}

		/** Crop display name → patches planted with it (observed crops only). */
		public Map<String, Integer> getPlanted()
		{
			return planted;
		}

		public boolean isStartedBefore()
		{
			return startedBefore;
		}

		public boolean isEndedAfter()
		{
			return endedAfter;
		}

		public int getBinsWorked()
		{
			return binsWorked;
		}

		public int getBinsSkipped()
		{
			return binsSkipped;
		}

		Record withBins(int worked, int skipped)
		{
			this.binsWorked = worked;
			this.binsSkipped = skipped;
			return this;
		}

		/** "9 of 10 patches · 7 replanted · 1 skipped · 2 bins worked · ended early" (a bins-only run has no patch count). */
		public String summary()
		{
			List<String> parts = new ArrayList<>();
			if (total > 0)
			{
				parts.add(done + " of " + total + " patches");
			}
			if (binsWorked > 0)
			{
				parts.add(binsWorked + (binsWorked == 1 ? " bin worked" : " bins worked"));
			}
			if (binsSkipped > 0)
			{
				parts.add(binsSkipped + (binsSkipped == 1 ? " bin skipped" : " bins skipped"));
			}
			if (replanted > 0)
			{
				parts.add(replanted + " replanted");
			}
			if (skipped > 0)
			{
				parts.add(skipped + " skipped");
			}
			if (unfinished > 0)
			{
				parts.add(unfinished + " unfinished");
			}
			parts.add(complete ? "complete" : "ended early");
			return String.join(" · ", parts);
		}
	}

	private FarmingRunHistory()
	{
	}

	/**
	 * Runs this Farming session (epoch ms window [from, to]) took part in, oldest first. There is one record per run (its
	 * outcome file); exactly one session is its primary placement, so nothing is counted twice: the Farming session whose
	 * window contains the run's finish, otherwise the overlapping Farming session that started last before it finished.
	 * Every other overlapping Farming session gets the same record marked {@link Record#isPrimary() not primary}, shown as a
	 * reference without counts. {@code farmingSessions} are every known Farming session window ({from, to}, to =
	 * Long.MAX_VALUE while open) of the account, including this one; empty → overlap only. Background thread: reads files.
	 */
	public static List<Record> find(LocalStateStore store, Filepath accountDir, long accountHash, long from, long to, List<long[]> farmingSessions)
	{
		List<Record> out = new ArrayList<>();
		for (Record r : find(store, accountDir, accountHash, from, to))
		{
			out.add(placed(r, from, to, farmingSessions));
		}
		return out;
	}

	/** The record as this session shows it: the full record when the session owns the run, otherwise a reference to it. */
	static Record placed(Record r, long from, long to, List<long[]> farmingSessions)
	{
		return owns(r.getStartedAt(), r.getFinishedAt(), from, to, farmingSessions) ? r : new Record(r, false);
	}

	/** Whether the session [from, to] is the one a run [start, end] is attributed to (see {@link #find(LocalStateStore, Filepath, long, long, long, List)}). */
	static boolean owns(long start, long end, long from, long to, List<long[]> sessions)
	{
		if (start >= to || end <= from)
		{
			return false;
		}
		long[] owner = null;
		for (long[] w : sessions)
		{
			if (w[0] <= end && end <= w[1])
			{
				owner = w;
			}
		}
		if (owner == null)
		{
			for (long[] w : sessions)
			{
				boolean overlaps = start < w[1] && end > w[0];
				if (overlaps && w[0] <= end && (owner == null || w[0] > owner[0]))
				{
					owner = w;
				}
			}
		}
		return owner == null || owner[0] == from && owner[1] == to;
	}

	/** Runs of this account whose time overlaps [from, to] (epoch ms), oldest first. Background thread: reads files. */
	public static List<Record> find(LocalStateStore store, Filepath accountDir, long accountHash, long from, long to)
	{
		List<Record> out = new ArrayList<>();
		for (Filepath runs : runDirs(accountDir))
		{
			for (Filepath f : jsonFiles(runs))
			{
				FarmingRunStore.Outcome o = store.readIfExists(f, FarmingRunStore.Outcome.class);
				Record r = o == null ? null : record(o, accountHash, from, to);
				if (r != null)
				{
					out.add(r);
				}
			}
		}
		out.sort(Comparator.comparingLong(Record::getStartedAt));
		return out;
	}

	static Record record(FarmingRunStore.Outcome o, long accountHash, long from, long to)
	{
		if (o.accountHash != accountHash || o.intentId == null || o.finishedAt == null || o.startedAt >= to || o.finishedAt <= from)
		{
			return null;
		}
		List<String> names = new ArrayList<>();
		if (o.runTypes != null && !o.runTypes.isEmpty())
		{
			for (String t : o.runTypes)
			{
				try
				{
					FarmingRunType type = FarmingRunType.valueOf(t);
					names.add(type.isCrop() || o.runTypes.size() == 1 ? type.getTitle() : "bins");
				}
				catch (IllegalArgumentException e)
				{
					// an unknown future type: skip its name
				}
			}
		}
		else if (o.runType != null)
		{
			names.add(o.runType.getTitle());
		}
		Map<String, Integer> planted = new LinkedHashMap<>();
		if (o.patches != null)
		{
			for (FarmingRunStore.PatchOutcome p : o.patches.values())
			{
				if (p.observedCrop != null && ("REPLANTED".equals(p.detail) || "PLANTED_UNSEEN".equals(p.detail)))
				{
					try
					{
						planted.merge(FarmingProduce.valueOf(p.observedCrop).getDisplayName(), 1, Integer::sum);
					}
					catch (IllegalArgumentException e)
					{
						// unknown crop name in a newer file
					}
				}
			}
		}
		return new Record(o.intentId, String.join(" + ", names) + " run", o.startedAt, o.finishedAt, "COMPLETE".equals(o.result), o.total, o.done,
			o.replanted, o.harvested, o.skipped, o.unfinished, planted, o.startedAt < from,
			o.finishedAt > to).withBins(o.binsWorked, o.binsSkipped);
	}

	private static List<Filepath> runDirs(Filepath accountDir)
	{
		List<Filepath> out = new ArrayList<>();
		Filepath profiles = accountDir.joinSegment("profiles");
		if (!profiles.exists())
		{
			return out;
		}
		try (Stream<Filepath> walk = profiles.walk(1))
		{
			for (Filepath p : walk.filter(f -> !f.isFile() && !f.equals(profiles)).collect(Collectors.toList()))
			{
				Filepath runs = p.joinSegment("farming").joinSegment(FarmingRunStore.RUNS_DIR);
				if (runs.exists())
				{
					out.add(runs);
				}
			}
		}
		catch (IOException e)
		{
			return out;
		}
		return out;
	}

	private static List<Filepath> jsonFiles(Filepath dir)
	{
		try (Stream<Filepath> walk = dir.walk(1))
		{
			return walk.filter(Filepath::isFile).filter(f -> f.getFileName().endsWith(".json")).collect(Collectors.toList());
		}
		catch (IOException e)
		{
			return Collections.emptyList();
		}
	}
}
