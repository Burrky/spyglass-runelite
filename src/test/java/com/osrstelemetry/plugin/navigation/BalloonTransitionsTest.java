package com.osrstelemetry.plugin.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * The hot air balloons (OSRS Wiki "Balloon transport system", 2026-10-22; Shortest Path hot_air_balloons.tsv): flights from
 * a basket to the other unlocked landings, each needing that landing's unlock, its Firemaking level and one log of its kind
 * carried (stored logs are not readable). Never a free teleport: the player walks to a basket first.
 */
public class BalloonTransitionsTest
{
	private static List<Transition> balloons()
	{
		return TransitionCatalog.bundled().all().stream().filter(t -> t.family == Transition.Family.BALLOON).collect(Collectors.toList());
	}

	@Test
	public void flights_needTheLandingsUnlock_level_andCarriedLog()
	{
		List<Transition> all = balloons();
		assertFalse(all.isEmpty());
		Transition toVarrock = all.stream().filter(t -> "Varrock".equals(t.info) && NavPoint.x(t.from) < 3000).findFirst().orElse(null);
		assertTrue(toVarrock != null);
		NavRequirement r = toVarrock.requirement;
		assertTrue("unresolved: " + r.getUnresolved(), r.getUnresolved().isEmpty());
		assertTrue(r.getSkills().stream().anyMatch(s -> s.skill == Skill.FIREMAKING && s.level == 40));
		assertTrue(r.getVars().stream().anyMatch(v -> v.id == 2872));
		assertEquals("one willow log, carried", 1, r.getItems().size());
		assertEquals(1519, r.getItems().get(0).optionIds.get(0)[0]);
		assertEquals("Fly the balloon to Varrock (1 willow log; at most 40 kg carried)", toVarrock.instruction());
		assertEquals("Balloon → Varrock", toVarrock.label());
		assertEquals("balloon", toVarrock.noun());
		assertTrue("the basket is what is used", toVarrock.objectId == 19128 || toVarrock.objectId == 19129);
	}

	@Test
	public void noFlightLandsWhereItLeaves_andEveryLandingIsReached()
	{
		List<Transition> all = balloons();
		for (Transition t : all)
		{
			assertTrue(t.toString(), NavPoint.distance(t.from, t.to) > 5);
		}
		for (String place : new String[]{"Entrana", "Taverley", "Crafting Guild", "Varrock", "Castle Wars", "Grand Tree"})
		{
			assertTrue(place, all.stream().anyMatch(t -> place.equals(t.info)));
		}
	}

	@Test
	public void theStoredLogAlternative_neverOpensAFlight_withoutTheLogCarried()
	{
		NavRequirement r = NavRequirement.parse(null, null, "1519=1|UNLOCK_BALLOON_LOG_BASKET=1", null, null, java.util.Collections.emptyMap());
		assertTrue(r.getUnresolved().isEmpty());
		assertEquals(1, r.getItems().size());
		NavRequirement other = NavRequirement.parse(null, null, "ECTO_TOKEN=25|UNLOCK_DRAGONTOOTH=1", null, null, java.util.Collections.emptyMap());
		assertFalse("other unreadable unlocks stay closed as before", other.getUnresolved().isEmpty());
	}

	@Test
	public void aBalloonFlight_needsTheGamesOwnWeight_atMost40kg()
	{
		assertTrue(NavAccess.restrictionMet(com.osrstelemetry.plugin.account.AccountFixtures.maxed().weight(40).build(), NavRequirement.BALLOON_WEIGHT));
		assertFalse("41 kg: the balloon will not take off", NavAccess.restrictionMet(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed().weight(41).build(), NavRequirement.BALLOON_WEIGHT));
		assertFalse("weight not read: never recommended", NavAccess.restrictionMet(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed().build(), NavRequirement.BALLOON_WEIGHT));
	}
}
