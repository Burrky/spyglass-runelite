package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project. Josh's own
 * `.\gradlew.bat clean test` run is what actually executes these.
 *
 * Slayer task-unit/physical-kill semantics regression coverage (see
 * SessionAggregates#getSlayerProgressDelta()'s own javadoc and
 * MetricUpdate#slayerProgress()'s own javadoc): every
 * MetricUpdate.slayerProgress() call carries ONE event's own
 * taskUnitsConsumed — the observed decrease in the assignment's
 * authoritative remaining-count value — which is a TASK-UNIT delta,
 * never a physical NPC-kill count, and must be SUMMED, never
 * overwritten, into SessionAggregates.slayerProgressDelta. Directly
 * against the real, pure, production SessionAggregateUpdater.apply() —
 * no mocking.
 */
public class SessionAggregateUpdaterTest
{
	@Test
	public void slayerProgress_singleEventOfTwoUnits_accumulatesExactlyTwoTaskUnits()
	{
		SessionAggregates aggregates = new SessionAggregates();

		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(2, 38));

		assertEquals("taskUnitsConsumed=2 for one event (e.g. an expeditious-bracelet-style proc on a "
			+ "single physical kill) must accumulate to exactly 2 task units, never 1",
			Integer.valueOf(2), aggregates.getSlayerProgressDelta());
		assertEquals("the honestly-named accessor must return the same value as the raw getter",
			Integer.valueOf(2), aggregates.getSlayerTaskUnitsConsumed());
	}

	@Test
	public void slayerProgress_multipleEvents_sumsTaskUnitsAcrossTheWholeSession()
	{
		SessionAggregates aggregates = new SessionAggregates();

		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(1, 39));
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(2, 37));
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(0, 37));

		// 1 + 2 + 0 = 3 task units total. The zero-unit event (e.g. a
		// bracelet-of-slaughter-style save, a physical kill that
		// consumed no task unit at all) contributes nothing -- it must
		// never subtract from, or reset, the running total.
		assertEquals(Integer.valueOf(3), aggregates.getSlayerProgressDelta());
	}

	@Test
	public void slayerProgress_currentRemainingIsOverwrittenNeverSummed_independentOfTaskUnitTotal()
	{
		SessionAggregates aggregates = new SessionAggregates();

		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(2, 38));
		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(1, 37));

		assertEquals("slayerProgressDelta accumulates (2 + 1 = 3 task units)",
			Integer.valueOf(3), aggregates.getSlayerProgressDelta());
		assertEquals("latestSlayerCurrentRemaining is the LATEST authoritative absolute value only, "
			+ "never summed/accumulated -- the authoritative task-remaining figure this project must "
			+ "keep exactly as-is",
			Integer.valueOf(37), aggregates.getLatestSlayerCurrentRemaining());
	}

	@Test
	public void slayerProgress_neverPopulatesReliableCount_taskUnitsAreNotAPhysicalKillSignal()
	{
		SessionAggregates aggregates = new SessionAggregates();

		SessionAggregateUpdater.apply(aggregates, MetricUpdate.slayerProgress(2, 38));

		assertNull("Slayer task-unit progress must never populate reliableCount -- that field is "
			+ "reserved for a genuinely authoritative physical-kill/completion signal (e.g. BOSS_KILL), "
			+ "and this pathway must never manufacture one from task units",
			aggregates.getReliableCount());
	}
}
