package com.osrstelemetry.plugin.skills.agility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import org.junit.Test;

/**
 * Agility semantics beyond "every course runs": Prifddinas portals, rewards
 * (marks, crystal shards, pyramid tops), course stability and switches,
 * mid-lap synchronisation, falls, timing, logout and the structured-activity
 * boundary.
 */
public class AgilityCompanionTrackerTest
{
	private static final AgilityCourse ARDY = AgilityCourses.byKey("ardougne");
	private static final AgilityCourse SEERS = AgilityCourses.byKey("seers");
	private static final AgilityCourse PRIF = AgilityCourses.byKey("prifddinas");
	private static final AgilityCourse PYRAMID = AgilityCourses.byKey("agility_pyramid");
	private static final AgilityCourse GNOME = AgilityCourses.byKey("gnome");

	private static int id(AgilityCourse c, int node)
	{
		return c.getNodes().get(node).getObjectIds().iterator().next();
	}

	private static void obstacles(AgilitySim s, AgilityCourse c, int from, int toExclusive)
	{
		for (int i = from; i < toExclusive; i++)
		{
			if (c.getNodes().get(i).isXpless())
			{
				s.clickOnly(id(c, i));
			}
			else
			{
				s.obstacle(id(c, i));
			}
		}
	}

	// ---- laps and timing ----------------------------------------------------------------------------

	@Test
	public void lapTime_runsFromTheStartObstacleClick_toTheEndTile()
	{
		AgilitySim s = new AgilitySim();
		long clickedAt = s.now;
		s.lap(ARDY);
		long finishedAt = s.now - 1_200; // finish(): two ticks after the end XP
		s.settle();
		SkillCycleStats st = s.laps.get("ardougne");
		assertEquals(1L, st.getCount());
		assertEquals(finishedAt - clickedAt, st.getBestMillis());
		assertEquals(st.getBestMillis(), (long) st.averageMillis());
	}

	@Test
	public void bestAndAverage_onlyFromValidLaps()
	{
		AgilitySim s = new AgilitySim();
		s.lap(ARDY);
		s.steps(20);
		s.lap(ARDY);
		s.settle();
		SkillCycleStats st = s.laps.get("ardougne");
		assertEquals(2L, st.getCount());
		assertTrue(st.getBestMillis() <= st.averageMillis());
	}

	@Test
	public void xpWithoutAnObstacleClick_isNeverProgress_orALap()
	{
		AgilitySim s = new AgilitySim();
		for (int i = 0; i < 10; i++)
		{
			s.t.onSkillXp(s.tick, 30, s.now);
			s.steps(3);
		}
		s.settle();
		assertNull(s.t.getMethodKey());
		assertNull(s.t.cycle(s.now));
		assertTrue(s.laps.isEmpty());
	}

	@Test
	public void aClickWithoutXp_isNeverASuccess()
	{
		AgilitySim s = new AgilitySim();
		s.clickOnly(id(ARDY, 0));
		s.steps(30);
		assertNull(s.t.cycle(s.now));
		assertNull(s.t.getMethodKey());
	}

	@Test
	public void endTileXp_afterJoiningMidLap_onlySynchronises()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 4, 6);
		SkillCompanionView.CycleView v = s.t.cycle(s.now);
		assertEquals("ardougne", v.getMethodKey());
		assertFalse("joined part-way", v.isSynced());
		assertNull("no lap timer for a lap whose start was not seen", v.getElapsedMillis());
		assertEquals(Integer.valueOf(7), v.getDenominator());
		assertEquals(6, v.getProgress());
		s.finish(ARDY, id(ARDY, 6));
		s.settle();
		assertEquals(0L, s.laps(ARDY));
		assertTrue("synchronised now", s.t.cycle(s.now).isSynced());
		s.lap(ARDY);
		s.settle();
		assertEquals(1L, s.laps(ARDY));
	}

	@Test
	public void logout_dropsTheLapInProgress_neverALapAcrossIt()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 4);
		s.t.onLogout();
		s.steps(1_000);
		s.finish(ARDY, id(ARDY, 6));
		s.settle();
		assertEquals("no 10-minute lap spanning a logout", 0L, s.laps(ARDY));
		assertEquals("the course itself is kept", "ardougne", s.t.getMethodKey());
	}

	@Test
	public void leavingTheCourseMidLap_abandonsItAfterTenMinutes()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 3);
		assertNotNull(s.t.cycle(s.now).getElapsedMillis());
		s.steps(1_100);
		assertNull(s.t.cycle(s.now).getElapsedMillis());
		s.finish(ARDY, id(ARDY, 6));
		s.settle();
		assertEquals(0L, s.laps(ARDY));
	}

	@Test
	public void restartingAtTheStart_beginsANewLap_theOldNeverCounts()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 3);
		s.lap(ARDY);
		s.settle();
		assertEquals(1L, s.laps(ARDY));
	}

	// ---- course identity --------------------------------------------------------------------------------

	@Test
	public void oneStrayObstacleOfAnotherCourse_neverChurnsTheCourse()
	{
		AgilitySim s = new AgilitySim();
		s.lap(ARDY);
		s.obstacle(id(SEERS, 2));
		assertEquals("ardougne", s.t.getMethodKey());
		s.lap(ARDY);
		s.settle();
		assertEquals(2L, s.laps(ARDY));
		assertEquals(0L, s.laps(SEERS));
	}

	@Test
	public void switchingCourses_keepsPerCourseLaps()
	{
		AgilitySim s = new AgilitySim();
		s.lap(SEERS);
		s.lap(SEERS);
		s.steps(50);
		s.lap(ARDY);
		s.settle();
		assertEquals("ardougne", s.t.getMethodKey());
		assertEquals(2L, s.laps(SEERS));
		assertEquals(1L, s.laps(ARDY));
		assertTrue("Ardougne's laps never inherit Seers' timings",
			s.laps.get("ardougne").getBestMillis() != s.laps.get("seers").getBestMillis() || s.laps.get("ardougne").getCount() == 1);
		assertEquals(1L, s.t.getMethods().getTransitions());
	}

	@Test
	public void sepulchreAndArenaObjects_areNeverCourses()
	{
		AgilitySim s = new AgilitySim();
		for (int id : new int[]{ObjectID.HALLOWED_FLOOR_2_STEPPINGSTONE, ObjectID.HALLOWED_PATH_END_GATE, ObjectID.AGILITYARENA_PLANK,
			ObjectID.AGILITYARENA_ROPESWING, ObjectID.AGILITYARENA_HANDHOLDS})
		{
			assertTrue(AgilityCourses.withObject(id).isEmpty());
			s.obstacle(id);
		}
		s.settle();
		assertNull("structured agility content never becomes a course", s.t.getMethodKey());
		assertNull(s.t.cycle(s.now));
		assertTrue(s.laps.isEmpty());
	}

	// ---- Prifddinas ----------------------------------------------------------------------------------

	@Test
	public void prifddinas_normalRoute_isOneLap_noShards()
	{
		AgilitySim s = new AgilitySim();
		s.lap(PRIF);
		s.settle();
		assertEquals(1L, s.laps(PRIF));
		assertEquals(0L, s.produced(ItemID.PRIF_CRYSTAL_SHARD));
	}

	@Test
	public void prifddinas_portalRoute_isAValidLap_creditsTheSkippedObstacle_andItsShard()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, PRIF, 0, 3);
		assertEquals(3, s.t.cycle(s.now).getProgress());
		// Portal: clicked, 82 XP, a crystal shard straight into the inventory; the next obstacle (roof edge) is skipped.
		s.t.onObjectClick(s.tick, s.now, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_2);
		s.steps(3);
		s.t.onSkillXp(s.tick, 82, s.now);
		s.gain(ItemID.PRIF_CRYSTAL_SHARD, 1);
		assertEquals("the portal passes the skipped obstacle", 4, s.t.cycle(s.now).getProgress());
		assertTrue("timing continues", s.t.cycle(s.now).getElapsedMillis() > 0);
		obstacles(s, PRIF, 4, 11);
		assertEquals(11, s.t.cycle(s.now).getProgress());
		s.finish(PRIF, id(PRIF, 11));
		s.settle();
		assertEquals("exactly one lap", 1L, s.laps(PRIF));
		assertEquals(0L, s.laps.get("prifddinas").getFailures());
		assertEquals("the portal's shard", 1L, s.produced(ItemID.PRIF_CRYSTAL_SHARD));
	}

	/** Live QA: 4 -> 6 jumps and "10/12 while effectively complete" on Prifddinas, from clicking ahead. */
	@Test
	public void prifddinas_clickingAhead_andAMisclick_neverSkipOrJump_lastObstacleIsElevenOfTwelve()
	{
		AgilitySim s = new AgilitySim();
		s.t.onObjectClick(s.tick, s.now, id(PRIF, 0));
		s.steps(3);
		int previous = 0;
		for (int i = 0; i < 11; i++)
		{
			if (i == 6)
			{
				// A misclick on an obstacle far ahead: waits for its own XP, never credited now.
				s.t.onObjectClick(s.tick, s.now, id(PRIF, 9));
			}
			s.t.onObjectClick(s.tick, s.now, id(PRIF, i + 1));
			s.steps(1);
			if (!PRIF.getNodes().get(i).isXpless())
			{
				s.t.onSkillXp(s.tick, 40, s.now);
			}
			s.steps(2);
			int progress = s.t.cycle(s.now).getProgress();
			assertTrue("never a jump of two except the clicked-ahead ladder: " + previous + " -> " + progress,
				progress - previous <= (i + 1 < 12 && PRIF.getNodes().get(i + 1).isXpless() ? 2 : 1));
			assertTrue("never ahead of the obstacles really passed", progress <= i + 2);
			previous = progress;
		}
		assertEquals("the last obstacle still to go: 11 of 12", 11, s.t.cycle(s.now).getProgress());
		assertEquals(Integer.valueOf(12), s.t.cycle(s.now).getDenominator());
		AgilityCourse.Tile end = PRIF.getEndTiles().get(0);
		s.at(end.getX(), end.getY(), end.getPlane());
		s.t.onSkillXp(s.tick, 100, s.now);
		s.steps(2);
		s.settle();
		assertEquals(1L, s.laps(PRIF));
	}

	@Test
	public void carried_isTheRewardsInTheInventory_unknownBeforeItIsRead()
	{
		AgilityCompanionTracker fresh = new AgilityCompanionTracker();
		assertNull("never read: unknown, not zero", fresh.carried());
		AgilitySim s = new AgilitySim();
		s.inv.put(ItemID.GRACE, 14L);
		s.inv.put(ItemID.LOBSTER, 6L);
		s.t.onInventory(s.tick, s.now, new java.util.HashMap<>(s.inv), 2);
		assertEquals(java.util.Collections.singletonMap(ItemID.GRACE, 14L), s.t.carried());
	}

	@Test
	public void worldHopMidLap_neverALapAcrossIt_theNextFullLapCounts()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, SEERS, 0, 3);
		s.t.onLogout();
		obstacles(s, SEERS, 3, SEERS.getNodes().size() - 1);
		s.finish(SEERS, id(SEERS, SEERS.getNodes().size() - 1));
		s.settle();
		assertEquals("the hop broke that lap: never counted", 0L, s.laps(SEERS));
		s.lap(SEERS);
		s.settle();
		assertEquals(1L, s.laps(SEERS));
	}

	@Test
	public void prifddinas_shardWithoutAPortal_isNeverCounted()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, PRIF, 0, 3);
		s.gain(ItemID.PRIF_CRYSTAL_SHARD, 3);
		s.withdraw(ItemID.PRIF_CRYSTAL_SHARD, 50);
		s.settle();
		assertEquals(0L, s.produced(ItemID.PRIF_CRYSTAL_SHARD));
	}

	@Test
	public void prifddinas_neverHandsOutMarks()
	{
		assertEquals("CRYSTAL SHARDS", PRIF.getReward().getCaption());
		AgilitySim s = new AgilitySim();
		obstacles(s, PRIF, 0, 3);
		s.gain(ItemID.GRACE, 1);
		s.settle();
		assertEquals(0L, s.produced(ItemID.GRACE));
	}

	// ---- rooftop marks ----------------------------------------------------------------------------------

	@Test
	public void mark_pickedUpOnTheRooftop_countsOnce()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 3);
		s.gain(ItemID.GRACE, 1);
		s.finish(ARDY, id(ARDY, 6));
		s.settle();
		assertEquals(1L, s.produced(ItemID.GRACE));
	}

	@Test
	public void marks_withdrawnOrCarriedIn_orFarFromACourse_neverCount()
	{
		AgilitySim s = new AgilitySim();
		s.gain(ItemID.GRACE, 40);
		s.withdraw(ItemID.GRACE, 100);
		s.settle();
		assertEquals("no course evidence: not a course reward", 0L, s.produced(ItemID.GRACE));
		s.lap(ARDY);
		s.withdraw(ItemID.GRACE, 10);
		s.steps(400);
		s.gain(ItemID.GRACE, 1);
		s.settle();
		assertEquals("long after the course, a mark is not this course's", 0L, s.produced(ItemID.GRACE));
	}

	@Test
	public void marks_deposited_bankForTheRewardTarget()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 3);
		s.gain(ItemID.GRACE, 1);
		s.gain(ItemID.GRACE, 1);
		s.deposit(ItemID.GRACE, 2);
		s.settle();
		assertEquals(2L, s.produced(ItemID.GRACE));
		assertEquals(2L, s.banked.getOrDefault(ItemID.GRACE, 0L).longValue());
	}

	@Test
	public void aCourseWithoutAReward_neverCountsMarks()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, GNOME, 0, 3);
		s.gain(ItemID.GRACE, 1);
		s.settle();
		assertEquals(0L, s.produced(ItemID.GRACE));
	}

	// ---- Agility Pyramid -------------------------------------------------------------------------------

	@Test
	public void pyramid_lapAndTop_areSeparateFacts()
	{
		AgilitySim s = new AgilitySim();
		s.lap(PYRAMID);
		s.settle();
		assertEquals(1L, s.laps(PYRAMID));
		assertEquals("a lap never implies the top", 0L, s.produced(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID));

		s.at(3364, 2840, 0);
		s.obstacle(ObjectID.AGILITY_PYRAMID_STEPS1);
		s.at(3364, 2840, 2);
		s.obstacle(ObjectID.AGILITY_PYRAMID_LOW_WALL);
		s.clickOnly(ObjectID.AGILITY_PYRAMID_WALL_ROCKS);
		s.gain(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID, 1);
		s.finish(PYRAMID, ObjectID.AGILITY_PYRAMID_DOOR_HOTSPOT);
		s.settle();
		assertEquals(2L, s.laps(PYRAMID));
		assertEquals("the top, from the climbing rocks", 1L, s.produced(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID));
	}

	@Test
	public void pyramid_progressCountsObstacles_withNoInventedTotal()
	{
		AgilitySim s = new AgilitySim();
		s.at(3364, 2840, 0);
		s.obstacle(ObjectID.AGILITY_PYRAMID_STEPS1);
		s.at(3364, 2840, 1);
		s.obstacle(ObjectID.AGILITY_PYRAMID_LOW_WALL);
		s.obstacle(ObjectID.AGILITY_PYRAMID_LEDGE_HOTSPOT);
		s.obstacle(ObjectID.AGILITY_PYRAMID_LOW_WALL);
		s.obstacle(ObjectID.AGILITY_PYRAMID_STEPS1);
		SkillCompanionView.CycleView v = s.t.cycle(s.now);
		assertNull(v.getDenominator());
		assertEquals(5, v.getProgress());
		assertTrue("stairs above the ground floor never restart the lap", v.getElapsedMillis() > 2_000);
	}

	@Test
	public void pyramid_topWithoutTheRocks_isNotCounted()
	{
		AgilitySim s = new AgilitySim();
		s.at(3364, 2840, 0);
		s.obstacle(ObjectID.AGILITY_PYRAMID_STEPS1);
		s.gain(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID, 1);
		s.settle();
		assertEquals(0L, s.produced(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID));
	}

	// ---- falls ---------------------------------------------------------------------------------------

	@Test
	public void rooftopFall_dropToTheGroundWithDamage_countsOnce_andTheLapRestarts()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 3);
		s.at(2660, 3300, 0);
		s.t.onLocalDamage(s.tick);
		s.steps(2);
		s.t.onLocalDamage(s.tick);
		assertNull("off the roofs: no lap timer until the start obstacle", s.t.cycle(s.now).getElapsedMillis());
		s.lap(ARDY);
		s.settle();
		assertEquals(1L, s.laps.get("ardougne").getFailures());
		assertEquals("the retried lap counts", 1L, s.laps(ARDY));
	}

	@Test
	public void damageAlone_orADropAlone_isNeverAFall()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, ARDY, 0, 3);
		s.t.onLocalDamage(s.tick);
		s.steps(10);
		s.at(2660, 3300, 0);
		s.steps(10);
		s.at(2660, 3300, 1);
		s.finish(ARDY, id(ARDY, 6));
		s.settle();
		assertEquals(0L, s.laps.get("ardougne").getFailures());
	}

	@Test
	public void nonRooftopCourses_neverInventFalls()
	{
		AgilitySim s = new AgilitySim();
		obstacles(s, PRIF, 0, 3);
		s.at(3250, 6100, 0);
		s.t.onLocalDamage(s.tick);
		s.lap(PRIF);
		s.settle();
		assertEquals(0L, s.laps.get("prifddinas").getFailures());
	}

	@Test
	public void theFinalLeapDown_isNeverAFall()
	{
		AgilitySim s = new AgilitySim();
		s.lap(SEERS);
		s.settle();
		assertEquals(0L, s.laps.get("seers").getFailures());
		assertEquals(1L, s.laps(SEERS));
	}

	// ---- views -----------------------------------------------------------------------------------------

	@Test
	public void noTrip_noSupplies_forAgility()
	{
		AgilitySim s = new AgilitySim();
		s.lap(ARDY);
		assertNull(s.t.trip(s.now));
		assertNull(s.t.supply(s.now));
		assertFalse(AgilityCompanionDefinition.INSTANCE.tripMatters("ardougne"));
	}
}
