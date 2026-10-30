package com.osrstelemetry.plugin.hunter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.farming.FarmingGuide;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Pass 31: the live bird house run of 2026-10-29 (RuneLite client log), replayed. Four yew houses left seeded about a year
 * earlier (value 21); a magic run over all four. On the magic mushtree transfer the game sent the spaces as bare for one
 * tick, then the old houses: Spyglass read "bare → seeded" as four fresh seedings (timers from that moment), marked every
 * space done and completed the run (it vanished back to the overview). The second run then treated the old Mushroom Meadow
 * house as "Still filling · ~47 minutes" ("Empty it now (its birds are lost)"), treated the freshly reseeded Verdant Valley
 * houses as needing work (the player skipped them), and sent the player 124 tiles on foot past the mushtree.
 */
public class BirdhouseLiveRegressionTest
{
	static final long MIN = 60_000L;
	/** 2026-10-29 00:02:51 EDT, the first reading after the mushtree transfer. */
	static final long T = 1_793_246_571_565L;
	/** A year before: when RuneLite's Time Tracking saw the yew houses seeded. */
	static final long YEAR_AGO = T - 365L * 24 * 60 * MIN;
	static final int YEW_SEEDED = 21;
	static final int MAGIC_BUILT = 22;
	static final int MAGIC_SEEDED = 24;

	static Map<BirdhouseSpace, Integer> v(int mn, int ms, int vn, int vs)
	{
		return BirdhouseTrapsTest.all(mn, ms, vn, vs);
	}

	/** Time Tracking's record of the four spaces (value, when it saw it). */
	static Map<BirdhouseSpace, long[]> tt(long[] mn, long[] ms, long[] vn, long[] vs)
	{
		Map<BirdhouseSpace, long[]> m = new EnumMap<>(BirdhouseSpace.class);
		m.put(BirdhouseSpace.MEADOW_NORTH, mn);
		m.put(BirdhouseSpace.MEADOW_SOUTH, ms);
		m.put(BirdhouseSpace.VALLEY_NORTH, vn);
		m.put(BirdhouseSpace.VALLEY_SOUTH, vs);
		return m;
	}

	static long[] at(int value, long when)
	{
		return new long[]{value, when};
	}

	static Map<BirdhouseSpace, long[]> oldYews()
	{
		return tt(at(YEW_SEEDED, YEAR_AGO), at(YEW_SEEDED, YEAR_AGO), at(YEW_SEEDED, YEAR_AGO), at(YEW_SEEDED, YEAR_AGO));
	}

	static List<BirdhousePlan.StopPlan> stops(BirdhouseRun r)
	{
		return BirdhouseGuideTest.stops(r);
	}

	@Test
	public void theMushtreeSceneLoad_neverStartsTimers_neverCompletesTheRun_andTheOldHousesStayActionable()
	{
		BirdhouseTraps traps = BirdhouseTraps.empty();
		BirdhouseRun run = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.MAGIC, traps, T - 39 * 1000L).begun();

		// The log: all four bare, then 132 ms later the four old yew houses.
		traps = traps.withReading(v(0, 0, 0, 0), oldYews(), T);
		run = run.observe(traps, T);
		traps = traps.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, YEW_SEEDED), oldYews(), T + 132);
		run = run.observe(traps, T + 132);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			assertNull("seeing an existing house never starts a timer: " + s, traps.trap(s).getArmedAt());
			assertEquals("Time Tracking saw it seeded a year ago: full", BirdhouseTraps.Status.Kind.READY, traps.status(s, T + 132).getKind());
			assertEquals("an old full house: empty it (then build, then seeds)", BirdhouseRun.Step.EMPTY, run.progress(s, traps, T + 132).getStep());
		}
		assertTrue("the run is still in progress", run.isActive());
		assertEquals(0, run.counts(traps, T + 132)[0]);

		// The same scene load without Time Tracking: still no timer; the house may be full, and the guide says to empty it
		// (the game warns before emptying a house that isn't full), never "still filling, its birds are lost".
		BirdhouseTraps blind = BirdhouseTraps.empty().withReading(v(0, 0, 0, 0), T).withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, YEW_SEEDED), T + 132);
		BirdhouseRun blindRun = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.MAGIC, BirdhouseTraps.empty(), T - 39 * 1000L).begun()
			.observe(blind, T + 132);
		assertTrue(blindRun.isActive());
		BirdhouseRun.SpaceProgress p = blindRun.progress(BirdhouseSpace.MEADOW_NORTH, blind, T + 2 * MIN);
		assertEquals(BirdhouseRun.Step.CHECK_FULL, p.getStep());
		assertNull(blind.trap(BirdhouseSpace.MEADOW_NORTH).getArmedAt());
		FarmingGuide.Objective o = BirdhouseGuide.resolve(blindRun, stops(blindRun), blind, new int[]{3677, 3880, 0}, null, null, null, null, true,
			false, T + 2 * MIN);
		assertEquals(FarmingGuide.Kind.ACT, o.getKind());
		assertEquals("Empty bird house", o.getAction().getLabel());
		assertFalse(o.getAction().getGuide(), o.getAction().getGuide().contains("birds are lost"));
		assertFalse(o.getChecklist().toString(), o.getChecklist().toString().contains("Still filling"));
	}

	@Test
	public void eachHouseIsWorkedOnItsOwn_andATransientBareReadingRestoresTheHouse_withItsTimer()
	{
		BirdhouseTraps traps = BirdhouseTraps.empty().withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, YEW_SEEDED), oldYews(), T);
		BirdhouseRun run = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.MAGIC, traps, T).begun();

		// Valley SW (varp 1629): emptied, built, seeded — as the log: 00:02:57 bare, 00:03:01 magic built, 00:03:06 seeded.
		traps = traps.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, 0), T + 6_000);
		assertEquals(BirdhouseRun.Step.BUILD, run.progress(BirdhouseSpace.VALLEY_SOUTH, traps, T + 6_000).getStep());
		traps = traps.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, MAGIC_BUILT), T + 10_000);
		traps = traps.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, MAGIC_SEEDED), T + 15_000);
		run = run.observe(traps, T + 15_000);
		assertEquals(Long.valueOf(T + 15_000), traps.trap(BirdhouseSpace.VALLEY_SOUTH).getArmedAt());
		assertEquals(BirdhouseRun.Step.DONE, run.progress(BirdhouseSpace.VALLEY_SOUTH, traps, T + 15_000).getStep());
		for (BirdhouseSpace other : EnumSet.of(BirdhouseSpace.MEADOW_NORTH, BirdhouseSpace.MEADOW_SOUTH, BirdhouseSpace.VALLEY_NORTH))
		{
			assertEquals("completing one space never completes another: " + other, BirdhouseRun.Step.EMPTY, run.progress(other, traps, T + 15_000).getStep());
			assertNull(traps.trap(other).getArmedAt());
		}

		// A scene load that sends two spaces bare for a tick (below RuneLite's three-space guard), then the same houses:
		// restored as they were — the old meadow houses keep their age, the new valley house its timer.
		BirdhouseTraps gap = traps.withReading(v(0, YEW_SEEDED, YEW_SEEDED, 0), T + 20_000);
		BirdhouseTraps back = gap.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, MAGIC_SEEDED), T + 20_600);
		assertEquals(Long.valueOf(T + 15_000), back.trap(BirdhouseSpace.VALLEY_SOUTH).getArmedAt());
		assertNull(back.trap(BirdhouseSpace.MEADOW_NORTH).getArmedAt());
		assertEquals(BirdhouseTraps.Status.Kind.READY, back.status(BirdhouseSpace.MEADOW_NORTH, T + 20_600).getKind());
		assertEquals(BirdhouseRun.Step.DONE, run.observe(back, T + 20_600).progress(BirdhouseSpace.VALLEY_SOUTH, back, T + 20_600).getStep());
		assertTrue(run.observe(back, T + 20_600).isActive());
		// Four spaces bare at once is ignored outright (RuneLite's guard).
		assertSame(back, back.withReading(v(0, 0, 0, 0), T + 21_000));
	}

	@Test
	public void freshlyResetValleyHouses_plusOldFullMeadowHouses_acrossTheMushtree_aRestart_andASecondRun()
	{
		// The valley houses were reseeded (seen) at 00:03:06 and 00:03:46; the meadow houses are the year-old yews.
		BirdhouseTraps traps = BirdhouseTraps.empty().withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, YEW_SEEDED), oldYews(), T);
		traps = traps.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, 0), T + 6_000)
			.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, MAGIC_BUILT), T + 10_000)
			.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, MAGIC_SEEDED), T + 15_000)
			.withReading(v(YEW_SEEDED, YEW_SEEDED, 0, MAGIC_SEEDED), T + 51_000)
			.withReading(v(YEW_SEEDED, YEW_SEEDED, MAGIC_BUILT, MAGIC_SEEDED), T + 54_000)
			.withReading(v(YEW_SEEDED, YEW_SEEDED, MAGIC_SEEDED, MAGIC_SEEDED), T + 55_000);

		// The second run starts after that work (00:03:56).
		long start = T + 64_000;
		BirdhouseRun run = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.MAGIC, traps, start).begun();
		run = run.observe(traps, start);
		assertEquals("seeded before the run, still filling: nothing to do", BirdhouseRun.Step.FILLING,
			run.progress(BirdhouseSpace.VALLEY_NORTH, traps, start).getStep());
		assertEquals(BirdhouseRun.Step.FILLING, run.progress(BirdhouseSpace.VALLEY_SOUTH, traps, start).getStep());
		assertEquals("the old meadow house is full: it needs the run", BirdhouseRun.Step.EMPTY, run.progress(BirdhouseSpace.MEADOW_NORTH, traps, start).getStep());
		assertEquals(2, run.counts(traps, start)[0]);
		assertEquals("no completion is recorded for work before the run", 0, doneAt(run).size());

		// Beside the valley houses (a tile the map blocks), the guide goes to the meadow by the mushtree, not 124 tiles on foot.
		FarmingGuide.Objective o = BirdhouseGuide.resolve(run, stops(run), traps, new int[]{3761, 3757, 0}, null, null, null, null, true, false, start);
		assertEquals(FarmingGuide.Kind.TRAVEL, o.getKind());
		assertEquals("meadow_north", o.getStop());
		assertEquals("○ Mushtree → Mushroom Meadow", o.getChecklist().get(0));
		assertEquals("the target is the valley's mushtree", 3757, o.getTarget().x);

		// The transfer: a scene load (all bare for a tick: ignored), a restart (saved forms), then the meadow.
		traps = traps.withReading(v(0, 0, 0, 0), start + 30_000);
		run = run.observe(traps, start + 30_000);
		Gson gson = new Gson();
		run = gson.fromJson(gson.toJson(run), BirdhouseRun.class);
		traps = gson.fromJson(gson.toJson(traps), BirdhouseTraps.class);
		assertTrue("travel and a restart keep the run", run.isActive());
		assertEquals(BirdhouseRun.Step.FILLING, run.progress(BirdhouseSpace.VALLEY_SOUTH, traps, start + 40_000).getStep());
		assertEquals(Long.valueOf(T + 15_000), traps.trap(BirdhouseSpace.VALLEY_SOUTH).getArmedAt());
		assertEquals(BirdhouseRun.Step.EMPTY, run.progress(BirdhouseSpace.MEADOW_NORTH, traps, start + 40_000).getStep());

		// At the meadow: the north house, then the south one (00:06:48–00:07:37).
		long m = start + 170_000;
		traps = traps.withReading(v(0, YEW_SEEDED, MAGIC_SEEDED, MAGIC_SEEDED), m)
			.withReading(v(MAGIC_BUILT, YEW_SEEDED, MAGIC_SEEDED, MAGIC_SEEDED), m + 4_000)
			.withReading(v(MAGIC_SEEDED, YEW_SEEDED, MAGIC_SEEDED, MAGIC_SEEDED), m + 6_000);
		run = run.observe(traps, m + 6_000);
		assertEquals(BirdhouseRun.Step.DONE, run.progress(BirdhouseSpace.MEADOW_NORTH, traps, m + 6_000).getStep());
		assertEquals(BirdhouseRun.Step.EMPTY, run.progress(BirdhouseSpace.MEADOW_SOUTH, traps, m + 6_000).getStep());
		assertTrue(run.isActive());
		traps = traps.withReading(v(MAGIC_SEEDED, 0, MAGIC_SEEDED, MAGIC_SEEDED), m + 43_000)
			.withReading(v(MAGIC_SEEDED, MAGIC_BUILT, MAGIC_SEEDED, MAGIC_SEEDED), m + 47_000)
			.withReading(v(MAGIC_SEEDED, MAGIC_SEEDED, MAGIC_SEEDED, MAGIC_SEEDED), m + 49_000);
		run = run.observe(traps, m + 49_000);
		assertEquals("legitimately complete", BirdhouseRun.State.COMPLETE, run.getState());
		assertEquals(4, run.counts(traps, m + 49_000)[0]);
		assertEquals("one completion record per house done in this run", 2, doneAt(run).size());
	}

	@Test
	public void timeTracking_correctsATimerSavedFromTheOldBug_butNeverInventsOne()
	{
		// Saved by the old reading: bare then seeded set a timer at T (the bug), while Time Tracking saw that house a year ago.
		BirdhouseTraps.Trap wrong = new BirdhouseTraps.Trap(YEW_SEEDED, T, T, T);
		BirdhouseTraps saved = new Gson().fromJson("{\"traps\":{\"MEADOW_NORTH\":{\"value\":21,\"since\":" + T + ",\"seenAt\":" + T + ",\"armedAt\":" + T
			+ "}},\"notifiedReadyAt\":0}", BirdhouseTraps.class);
		assertNotNull(wrong);
		assertEquals(BirdhouseTraps.Status.Kind.CATCHING, saved.status(BirdhouseSpace.MEADOW_NORTH, T + 3 * MIN).getKind());
		BirdhouseTraps fixed = saved.withReading(v(YEW_SEEDED, YEW_SEEDED, YEW_SEEDED, YEW_SEEDED), oldYews(), T + 3 * MIN);
		assertNull(fixed.trap(BirdhouseSpace.MEADOW_NORTH).getArmedAt());
		assertEquals(BirdhouseTraps.Status.Kind.READY, fixed.status(BirdhouseSpace.MEADOW_NORTH, T + 3 * MIN).getKind());
		// A genuine seeding Time Tracking saw at the same moment (or later) is kept.
		BirdhouseTraps genuine = BirdhouseTraps.empty().withReading(v(MAGIC_BUILT, 0, 0, 0), T).withReading(v(MAGIC_SEEDED, 0, 0, 0), T + 2_000);
		BirdhouseTraps kept = genuine.withReading(v(MAGIC_SEEDED, 0, 0, 0), tt(at(MAGIC_SEEDED, T + 2_000), null, null, null), T + 3_000);
		assertEquals(Long.valueOf(T + 2_000), kept.trap(BirdhouseSpace.MEADOW_NORTH).getArmedAt());
		// A different value in Time Tracking says nothing about this house.
		BirdhouseTraps other = genuine.withReading(v(MAGIC_SEEDED, 0, 0, 0), tt(at(YEW_SEEDED, YEAR_AGO), null, null, null), T + 3_000);
		assertEquals(Long.valueOf(T + 2_000), other.trap(BirdhouseSpace.MEADOW_NORTH).getArmedAt());
	}

	@SuppressWarnings("unchecked")
	static Map<String, Long> doneAt(BirdhouseRun r)
	{
		try
		{
			java.lang.reflect.Field f = BirdhouseRun.class.getDeclaredField("doneAt");
			f.setAccessible(true);
			Map<String, Long> m = (Map<String, Long>) f.get(r);
			return m == null ? Collections.emptyMap() : m;
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	static List<String> names(List<BirdhouseRun.SpaceProgress> ps)
	{
		List<String> out = new ArrayList<>();
		ps.forEach(p -> out.add(p.getSpace() + ":" + p.getStep()));
		return out;
	}
}
