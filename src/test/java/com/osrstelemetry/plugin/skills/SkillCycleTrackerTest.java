package com.osrstelemetry.plugin.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** The generic CYCLE bookkeeping (no "lap", "course" or "obstacle" anywhere in it). */
public class SkillCycleTrackerTest
{
	@Test
	public void aFullyObservedCycle_counts_withItsDuration()
	{
		SkillCycleTracker c = new SkillCycleTracker();
		c.start("m", 1_000L, 1, 5);
		assertTrue(c.isRunning());
		assertEquals(Long.valueOf(500L), c.elapsedMillis(1_500L));
		SkillCycleTracker.Completion done = c.complete("m", 61_000L);
		assertNotNull(done);
		assertEquals(60_000L, done.getDurationMillis());
		Map<String, SkillCycleStats> p = c.drain();
		assertEquals(1L, p.get("m").getCount());
		assertEquals(60_000L, p.get("m").getBestMillis());
		assertTrue(c.drain().isEmpty());
	}

	@Test
	public void anUnsynchronisedCycle_neverCounts_itOnlySynchronises()
	{
		SkillCycleTracker c = new SkillCycleTracker();
		c.joinUnsynced("m", 3, 5);
		assertFalse(c.isSynced());
		assertNull(c.elapsedMillis(5_000L));
		assertNull(c.complete("m", 9_000L));
		assertTrue(c.isSynced());
		assertFalse("waits for the next start", c.isRunning());
		assertTrue(c.getPending().isEmpty());
	}

	@Test
	public void aCompletionOfAnotherMethod_neverCountsThisCycle()
	{
		SkillCycleTracker c = new SkillCycleTracker();
		c.start("a", 0L, 1, 4);
		assertNull(c.complete("b", 50_000L));
		assertTrue(c.getPending().isEmpty());
	}

	@Test
	public void abandon_failures_andPerMethodStats()
	{
		SkillCycleTracker c = new SkillCycleTracker();
		c.start("a", 0L, 1, 4);
		c.fail("a");
		c.abandon();
		assertNull(c.complete("a", 10_000L));
		c.start("a", 20_000L, 1, 4);
		c.complete("a", 80_000L);
		c.start("a", 90_000L, 1, 4);
		c.complete("a", 140_000L);
		c.start("b", 200_000L, 1, 9);
		c.complete("b", 400_000L);
		Map<String, SkillCycleStats> p = c.drain();
		assertEquals(2L, p.get("a").getCount());
		assertEquals(Long.valueOf(55_000L), p.get("a").averageMillis());
		assertEquals(50_000L, p.get("a").getBestMillis());
		assertEquals(1L, p.get("a").getFailures());
		assertEquals("never blended", 1L, p.get("b").getCount());
	}

	@Test
	public void stats_mergeRules()
	{
		SkillCycleStats session = new SkillCycleStats(10, 600_000, 55_000, 1);
		SkillCycleStats flushed = new SkillCycleStats(11, 660_000, 54_000, 1);
		SkillCycleStats pending = new SkillCycleStats(1, 58_000, 58_000, 0);
		SkillCycleStats live = SkillCycleStats.ahead(session, flushed).plus(pending);
		assertEquals("never double counted", 12L, live.getCount());
		assertEquals(718_000L, live.getTotalMillis());
		assertEquals(54_000L, live.getBestMillis());
		assertEquals(1L, live.getFailures());
		assertNull(SkillCycleStats.NONE.averageMillis());
		assertTrue(SkillCycleStats.NONE.isEmpty());
	}
}
