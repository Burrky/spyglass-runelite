package com.osrstelemetry.plugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/**
 * Pass 31: the spaces are read only once the game has sent them after arriving on the island or a scene load (live
 * 2026-10-29: bare for one tick after a magic mushtree transfer), and Time Tracking's record is parsed as it stores it.
 */
public class BirdhouseServiceReadingTest
{
	@Test
	public void readingsWaitForTheSceneToSettle_afterArrivingAndAfterEverySceneLoad()
	{
		BirdhouseService s = new BirdhouseService(null, null, new Gson(), null, null, null, null, null, null);
		for (int i = 1; i < BirdhouseService.SETTLE_TICKS; i++)
		{
			assertFalse("tick " + i + " after arriving", s.settled(true));
		}
		assertTrue(s.settled(true));
		assertTrue(s.settled(true));
		s.sceneChanged(); // the mushtree's scene load
		assertFalse(s.settled(true));
		assertFalse(s.settled(false));
		assertFalse("leaving the island starts the count again", s.settled(true));
	}

	@Test
	public void timeTrackingRecord_isValueColonEpochSeconds()
	{
		assertArrayEquals(new long[]{24, 1_793_246_586_000L}, BirdhouseService.parseTimeTracking("24:1793246586"));
		assertNull(BirdhouseService.parseTimeTracking(null));
		assertNull(BirdhouseService.parseTimeTracking("garbage"));
		assertNull(BirdhouseService.parseTimeTracking(":12"));
	}
}
