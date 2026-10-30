package com.osrstelemetry.plugin.farming;

import java.util.HashMap;

/** Test-only: a finished run's History record, as read from an outcome file. */
public final class FarmingHistoryFixtures
{
	private FarmingHistoryFixtures()
	{
	}

	public static FarmingRunHistory.Record herbRun(long account, long start, long end)
	{
		return FarmingRunHistory.record(outcome(account, start, end), account, start - 1, end + 1);
	}

	/** The same run as an earlier Farming session that it continued past shows it: a reference, no counts. */
	public static FarmingRunHistory.Record referenceFromEarlierSession(long account, long start, long end)
	{
		long[] earlier = {start - 1, start + 1};
		long[] finishing = {start + 2, end + 1};
		FarmingRunHistory.Record r = FarmingRunHistory.record(outcome(account, start, end), account, earlier[0], earlier[1]);
		return FarmingRunHistory.placed(r, earlier[0], earlier[1], java.util.Arrays.asList(earlier, finishing));
	}

	private static FarmingRunStore.Outcome outcome(long account, long start, long end)
	{
		FarmingRunStore.Outcome o = new FarmingRunStore.Outcome();
		o.accountHash = account;
		o.intentId = "fr-" + start;
		o.runType = FarmingRunType.HERB;
		o.startedAt = start;
		o.finishedAt = end;
		o.result = "COMPLETE";
		o.total = 2;
		o.done = 2;
		o.replanted = 2;
		o.patches = new HashMap<>();
		FarmingRunStore.PatchOutcome p = new FarmingRunStore.PatchOutcome();
		p.detail = "REPLANTED";
		p.observedCrop = "RANARR";
		o.patches.put("herb.falador", p);
		return o;
	}
}
