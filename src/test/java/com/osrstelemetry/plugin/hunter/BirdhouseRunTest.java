package com.osrstelemetry.plugin.hunter;

import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.MIN;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_BUILT;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_PART;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_SEEDED;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.T0;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.all;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.EnumSet;
import org.junit.Test;

/** Pass 29: a bird house run's lifecycle from the spaces as read (collect, rebuild, seed; partial, skipped, restarted). */
public class BirdhouseRunTest
{
	static BirdhouseTraps full()
	{
		// Four redwood houses seeded long ago (seen becoming seeded), full now.
		return BirdhouseTraps.empty().withReading(all(R_BUILT, R_BUILT, R_BUILT, R_BUILT), T0 - 120 * MIN)
			.withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 - 100 * MIN);
	}

	static BirdhouseRun.Step step(BirdhouseRun r, BirdhouseSpace s, BirdhouseTraps t, long now)
	{
		return r.progress(s, t, now).getStep();
	}

	@Test
	public void eachHouse_movesOnFromWhatItShows_empty_build_seed_done()
	{
		BirdhouseTraps t = full();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.REDWOOD, t, T0).begun();
		BirdhouseSpace v = BirdhouseSpace.VALLEY_NORTH;
		assertEquals(BirdhouseRun.Step.EMPTY, step(r, v, t, T0));
		t = t.withReading(all(R_SEEDED, R_SEEDED, 0, R_SEEDED), T0 + MIN);
		assertEquals(BirdhouseRun.Step.BUILD, step(r, v, t, T0 + MIN));
		t = t.withReading(all(R_SEEDED, R_SEEDED, R_BUILT, R_SEEDED), T0 + 2 * MIN);
		assertEquals(BirdhouseRun.Step.SEED, step(r, v, t, T0 + 2 * MIN));
		t = t.withReading(all(R_SEEDED, R_SEEDED, R_PART, R_SEEDED), T0 + 3 * MIN);
		assertEquals(BirdhouseRun.Step.SEED_MORE, step(r, v, t, T0 + 3 * MIN));
		t = t.withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 + 4 * MIN);
		assertEquals("seeded during the run", BirdhouseRun.Step.DONE, step(r, v, t, T0 + 4 * MIN));
		assertEquals("the other old houses still need emptying", BirdhouseRun.Step.EMPTY, step(r, BirdhouseSpace.VALLEY_SOUTH, t, T0 + 4 * MIN));
		// Its timer starts from that seeding.
		assertEquals(T0 + 54 * MIN, t.status(v, T0 + 4 * MIN).getReadyAt());
	}

	@Test
	public void reset_goesStraightToSeeding_andAHouseSeenSeededBeforeTheRun_needsNothing()
	{
		BirdhouseTraps t = BirdhouseTraps.empty().withReading(all(R_BUILT, R_BUILT, R_BUILT, R_BUILT), T0 - 30 * MIN)
			.withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 - 20 * MIN);
		BirdhouseRun r = BirdhouseRun.start(EnumSet.of(BirdhouseSpace.MEADOW_NORTH, BirdhouseSpace.MEADOW_SOUTH), BirdhouseTier.REDWOOD, t, T0).begun();
		// Pass 31: seen being seeded 20 minutes before the run: nothing to do this visit (never "empty it early").
		assertEquals(BirdhouseRun.Step.FILLING, step(r, BirdhouseSpace.MEADOW_NORTH, t, T0));
		assertTrue(r.progress(BirdhouseSpace.MEADOW_NORTH, t, T0).isSettled());
		t = t.withReading(all(R_BUILT, R_SEEDED, R_SEEDED, R_SEEDED), T0 + MIN); // Reset: emptied and rebuilt in one
		assertEquals(BirdhouseRun.Step.SEED, step(r, BirdhouseSpace.MEADOW_NORTH, t, T0 + MIN));
		// Full later in the run: it needs emptying again.
		assertEquals(BirdhouseRun.Step.EMPTY, step(r, BirdhouseSpace.MEADOW_SOUTH, t, T0 + 31 * MIN));
	}

	@Test
	public void completes_whenEveryHouseIsSetOrSkipped_andSurvivesARestart()
	{
		BirdhouseTraps t = full();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.of(BirdhouseSpace.VALLEY_NORTH, BirdhouseSpace.VALLEY_SOUTH), BirdhouseTier.REDWOOD, t, T0).begun();
		t = t.withReading(all(R_SEEDED, R_SEEDED, R_BUILT, R_SEEDED), T0 + MIN).withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 + 2 * MIN);
		r = r.observe(t, T0 + 2 * MIN);
		assertTrue("one left: a partial run stays active", r.isActive());
		// Restart: the run and the readings come back from their saved form.
		BirdhouseRun back = new Gson().fromJson(new Gson().toJson(r), BirdhouseRun.class);
		BirdhouseTraps tb = new Gson().fromJson(new Gson().toJson(t), BirdhouseTraps.class);
		assertEquals(BirdhouseRun.Step.DONE, step(back, BirdhouseSpace.VALLEY_NORTH, tb, T0 + 3 * MIN));
		assertEquals(BirdhouseRun.Step.EMPTY, step(back, BirdhouseSpace.VALLEY_SOUTH, tb, T0 + 3 * MIN));
		BirdhouseRun skipped = back.withSkipped(BirdhouseSpace.VALLEY_SOUTH, true).observe(tb, T0 + 4 * MIN);
		assertEquals(BirdhouseRun.State.COMPLETE, skipped.getState());
		assertEquals(1, skipped.counts(tb, T0 + 4 * MIN)[0]);
		assertEquals(1, skipped.counts(tb, T0 + 4 * MIN)[1]);
		// Later readings (the next collection) never change a finished run.
		BirdhouseTraps later = tb.withReading(all(R_SEEDED, R_SEEDED, 0, R_SEEDED), T0 + 200 * MIN);
		assertSame(skipped, skipped.observe(later, T0 + 200 * MIN));
		assertEquals(BirdhouseRun.Step.DONE, step(skipped, BirdhouseSpace.VALLEY_NORTH, later, T0 + 200 * MIN));
	}

	@Test
	public void aSpaceNotReadBeforeTheRun_isChecked_andAnOldHouseFirstSeenDuringIt_isEmptied()
	{
		BirdhouseTraps t = BirdhouseTraps.empty();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.of(BirdhouseSpace.MEADOW_SOUTH), null, t, T0).begun();
		assertEquals(BirdhouseRun.Step.CHECK, step(r, BirdhouseSpace.MEADOW_SOUTH, t, T0));
		t = t.withReading(all(0, R_SEEDED, 0, 0), T0 + MIN);
		assertEquals("first seen already seeded: a house from before", BirdhouseRun.Step.CHECK_FULL,
			step(r, BirdhouseSpace.MEADOW_SOUTH, t, T0 + MIN));
		assertFalse(r.observe(t, T0 + MIN).getState() == BirdhouseRun.State.COMPLETE);
	}
}
