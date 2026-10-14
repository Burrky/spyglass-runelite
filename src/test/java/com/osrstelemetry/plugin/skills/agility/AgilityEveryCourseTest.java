package com.osrstelemetry.plugin.skills.agility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * EVERY supported course, data-driven: its definition is valid, a full lap
 * is detected (course identity, route progress, exactly one lap, the right
 * display name and reward type), and a lap joined part-way never counts.
 */
@RunWith(Parameterized.class)
public class AgilityEveryCourseTest
{
	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> courses()
	{
		List<Object[]> out = new ArrayList<>();
		for (AgilityCourse c : AgilityCourses.all())
		{
			out.add(new Object[]{c.getKey(), c});
		}
		return out;
	}

	private final AgilityCourse course;

	public AgilityEveryCourseTest(String key, AgilityCourse course)
	{
		this.course = course;
	}

	// ---- definition validity ------------------------------------------------------------------------

	@Test
	public void definitionIsValid()
	{
		assertTrue(course.getKey().matches("[a-z_]+"));
		assertFalse(course.getName().trim().isEmpty());
		assertTrue("a route", course.getNodes().size() >= 2);
		for (AgilityCourse.Node n : course.getNodes())
		{
			assertFalse(n.getLabel().isEmpty());
			assertFalse("node " + n.getLabel() + " has objects", n.getObjectIds().isEmpty());
			for (int id : n.getObjectIds())
			{
				assertTrue("a real object id", id > 0);
			}
		}
		assertFalse("the start obstacle is never zero-XP (a lap must be able to start)", course.startNode().isXpless());
		assertTrue("a lap can complete: an end tile or a completion varbit",
			!course.getEndTiles().isEmpty() || course.getCompletionVarbit() != null);
		for (AgilityCourse.Tile t : course.getEndTiles())
		{
			assertTrue("end tile is on a declared course region", course.getRegions().isEmpty() || course.getRegions().contains(t.regionId()));
		}
		if (course.getReward() != null)
		{
			assertNotNull("reward is a known resource", AgilityCompanionDefinition.INSTANCE.resource(course.getReward().getItemId()));
			assertFalse(course.getReward().getCaption().isEmpty());
			assertEquals(course.getReward().getRateNoun(), AgilityCompanionDefinition.INSTANCE.rateNoun(course.getKey()));
		}
		assertEquals(course, AgilityCourses.byKey(course.getKey()));
		assertEquals(course.getName(), AgilityCompanionDefinition.INSTANCE.methodLabel(course.getKey()));
	}

	@Test
	public void startObstacle_belongsOnlyToCoursesThatShareItsStart()
	{
		for (int id : course.startNode().getObjectIds())
		{
			for (AgilityCourse other : AgilityCourses.withObject(id))
			{
				assertTrue(other.getKey() + " shares " + course.getKey() + "'s start only as its own start",
					other == course || other.startNode().accepts(id));
			}
		}
	}

	// ---- a full lap -------------------------------------------------------------------------------------

	@Test
	public void aFullLap_isDetected_andCountsExactlyOnce()
	{
		AgilitySim s = new AgilitySim();
		s.lap(course);
		s.settle();
		assertEquals("one lap of " + course.getKey(), 1L, s.laps(course));
		assertEquals("the course is identified", course.getKey(), s.t.getMethodKey());
		assertNotNull(s.t.getLedger());
		assertTrue("the lap has a duration", s.laps.get(course.getKey()).getBestMillis() > 0);
		// The second lap counts too, and the course never churns.
		s.lap(course);
		s.settle();
		assertEquals(2L, s.laps(course));
		assertEquals(0L, s.t.getMethods().getTransitions());
		assertEquals("no other course got a lap", 1, s.laps.size());
	}

	@Test
	public void routeProgress_countsUpDuringTheLap()
	{
		AgilitySim s = new AgilitySim();
		List<AgilityCourse.Node> nodes = course.getNodes();
		s.plane = course.getStartPlane() != null ? course.getStartPlane() : 1;
		s.at(s.x, s.y, s.plane);
		s.obstacle(nodes.get(0).getObjectIds().iterator().next());
		if (course.getCompletionVarbit() != null)
		{
			s.t.onVarbit(course.getCompletionVarbit(), 1, s.now);
		}
		s.at(s.x, s.y, 1);
		// The next obstacle that is this course's own (a shared one, e.g. Shayzien's rope climb, says nothing yet).
		int j = 1;
		while (nodes.get(j).isXpless() || (course.getCompletionVarbit() == null
			&& AgilityCourses.withObject(nodes.get(j).getObjectIds().iterator().next()).size() > 1))
		{
			j++;
		}
		int id1 = nodes.get(j).getObjectIds().iterator().next();
		s.obstacle(id1);
		if (course.getCompletionVarbit() != null)
		{
			s.t.onVarbit(course.getCompletionVarbit(), 2, s.now);
		}
		SkillCompanionView.CycleView v = s.t.cycle(s.now);
		assertNotNull(v);
		assertEquals(course.getKey(), v.getMethodKey());
		assertTrue("synchronised: its start was seen", v.isSynced());
		assertTrue("progress " + v.getProgress(), v.getProgress() >= 2);
		assertEquals(course.denominator(), v.getDenominator());
		assertNotNull("the lap timer runs", v.getElapsedMillis());
	}

	@Test
	public void aLapJoinedPartWay_neverCounts_theNextFullOneDoes()
	{
		AgilitySim s = new AgilitySim();
		List<AgilityCourse.Node> nodes = course.getNodes();
		// Start in the middle of the route (as after a login or plugin start there).
		int from = nodes.size() / 2;
		if (nodes.get(from).isXpless())
		{
			from++;
		}
		// A course-unique interior obstacle (a shared one could be another course's).
		while (from < nodes.size() - 1 && AgilityCourses.withObject(nodes.get(from).getObjectIds().iterator().next()).size() > 1)
		{
			from++;
		}
		for (int i = from; i < nodes.size() - (course.getCompletionVarbit() != null ? 0 : 1); i++)
		{
			if (!nodes.get(i).isXpless())
			{
				s.obstacle(nodes.get(i).getObjectIds().iterator().next());
			}
		}
		if (course.getCompletionVarbit() != null)
		{
			s.t.onVarbit(course.getCompletionVarbit(), course.getCompletionValue(), s.now);
		}
		else
		{
			s.finish(course, nodes.get(nodes.size() - 1).getObjectIds().iterator().next());
		}
		s.settle();
		assertEquals("a partial route never fabricates a lap", 0L, s.laps(course));
		s.lap(course);
		s.settle();
		assertEquals(1L, s.laps(course));
	}

	@Test
	public void rewardType_matchesTheCourse()
	{
		AgilityCourse.Reward r = course.getReward();
		Set<String> captions = new HashSet<>();
		captions.add("MARKS");
		captions.add("CRYSTAL SHARDS");
		captions.add("PYRAMID TOPS");
		captions.add("TERMITES");
		if (r == null)
		{
			assertTrue("no reward: no reward target", AgilityCompanionDefinition.INSTANCE.stockpileResources(course.getKey()).isEmpty());
			assertNull(AgilityCompanionDefinition.INSTANCE.rateNoun(course.getKey()));
			return;
		}
		assertTrue(captions.contains(r.getCaption()));
		if (course.getKind() == AgilityCourse.Kind.ROOFTOP)
		{
			assertEquals("every rooftop hands out marks", "MARKS", r.getCaption());
		}
		assertEquals(1, AgilityCompanionDefinition.INSTANCE.stockpileResources(course.getKey()).size());
	}
}
