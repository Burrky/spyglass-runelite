package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.events.EventType;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-network caveat as everywhere else
 * in this project. These specifically target the pure static methods
 * extracted from ActivityKillCountCollector, which need no RuneLite
 * Client and so are the one part of that collector genuinely
 * testable in isolation.
 */
public class ActivityKillCountCollectorTest
{
	@Test
	public void verifiedRaidsClassifyAsRaidCompletion()
	{
		assertEquals(EventType.RAID_COMPLETION,
			ActivityKillCountCollector.classifyCompletion(ActivityKillCountCollector.slugify("Chambers of Xeric")));
		assertEquals(EventType.RAID_COMPLETION,
			ActivityKillCountCollector.classifyCompletion(ActivityKillCountCollector.slugify("Theatre of Blood")));
		assertEquals(EventType.RAID_COMPLETION,
			ActivityKillCountCollector.classifyCompletion(ActivityKillCountCollector.slugify("Tombs of Amascut")));
	}

	@Test
	public void unverifiedActivitiesClassifyAsGenericCompletionNotRaid()
	{
		// This is the exact bug the review flagged: a "completion
		// count" message alone must not become RAID_COMPLETION just
		// because it matched the same regex pattern as a real raid.
		assertEquals(EventType.ACTIVITY_COMPLETION,
			ActivityKillCountCollector.classifyCompletion(ActivityKillCountCollector.slugify("Corrupted Gauntlet")));
		assertEquals(EventType.ACTIVITY_COMPLETION,
			ActivityKillCountCollector.classifyCompletion(ActivityKillCountCollector.slugify("Barbarian Assault")));
		assertEquals(EventType.ACTIVITY_COMPLETION,
			ActivityKillCountCollector.classifyCompletion(ActivityKillCountCollector.slugify("Zulrah")));
	}

	@Test
	public void slugifyIsStableAndIdempotentAcrossDuplicateMessages()
	{
		// Simulates the same completion message being observed twice
		// (e.g. two kills in a row) — must slugify identically both
		// times so the activity is updated in place, not duplicated.
		String first = ActivityKillCountCollector.slugify("Tombs of Amascut");
		String second = ActivityKillCountCollector.slugify("Tombs of Amascut");
		assertEquals(first, second);
		assertEquals("tombs_of_amascut", first);
	}

	@Test
	public void slugifyNormalizesPunctuationAndCase()
	{
		assertEquals("chambers_of_xeric", ActivityKillCountCollector.slugify("Chambers of Xeric"));
		assertEquals("theatre_of_blood", ActivityKillCountCollector.slugify("  Theatre of Blood  "));
	}

	@Test
	public void firstObservationOfAnActivityAlwaysAdvances()
	{
		assertTrue(ActivityKillCountCollector.isAdvancing(null, 1));
	}

	@Test
	public void higherCountAdvancesLowerOrEqualDoesNot()
	{
		assertTrue("a strictly higher count is real progress", ActivityKillCountCollector.isAdvancing(42, 43));
		assertFalse("the exact same count observed again is a duplicate, not progress",
			ActivityKillCountCollector.isAdvancing(42, 42));
		assertFalse("a lower count is stale/out-of-order, not progress",
			ActivityKillCountCollector.isAdvancing(42, 41));
	}
}
