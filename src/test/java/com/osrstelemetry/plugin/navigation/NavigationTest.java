package com.osrstelemetry.plugin.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * World navigation (canonical §28.10). Kinds of check, kept apart:
 * <ul>
 *   <li>data validation: the bundled map, transitions and route data load and agree;</li>
 *   <li>route search: the pathfinder over the real map finds, avoids or fails as it should, and every route it returns is
 *   traversable step by step (each step a legal move on the map or a transition), never just an endpoint;</li>
 *   <li>simulated transitions: requirements on every leg, the live route chosen against the teleport;</li>
 * </ul>
 * Live verification (the game itself) is a separate, human step.
 */
public class NavigationTest
{
	static
	{
		com.osrstelemetry.plugin.navigation.TestRoutes.install(); // the client's Gson in the plugin
	}

	private static final Pathfinder PF = Pathfinder.bundled();
	private static final RouteTable ROUTES = RouteTable.bundled();

	private static int p(int x, int y, int z)
	{
		return NavPoint.pack(x, y, z);
	}

	/** Every step of the route is a legal move on the collision map or the transition recorded for it. */
	static void assertTraversable(NavPath path)
	{
		for (int i = 1; i < path.size(); i++)
		{
			int a = path.tile(i - 1);
			int b = path.tile(i);
			Transition t = path.via(i);
			if (t != null)
			{
				assertEquals("transition starts where the walk is: " + t, t.from, a);
				assertEquals("and lands where the route continues: " + t, t.to, b);
				continue;
			}
			assertEquals("a walk stays on its plane", NavPoint.plane(a), NavPoint.plane(b));
			int dx = NavPoint.x(b) - NavPoint.x(a);
			int dy = NavPoint.y(b) - NavPoint.y(a);
			assertTrue("one tile per step: " + NavPoint.text(a) + " -> " + NavPoint.text(b), Math.abs(dx) <= 1 && Math.abs(dy) <= 1);
			assertTrue("the map allows the step " + NavPoint.text(a) + " -> " + NavPoint.text(b),
				PF.map().canStep(NavPoint.x(a), NavPoint.y(a), NavPoint.plane(a), dx, dy));
		}
	}

	// -- data validation ---------------------------------------------------------------------------------------

	@Test
	public void data_loads_andTheRouteTableMatchesTheTransitions()
	{
		assertTrue(PF.transitions().all().size() > 6000);
		long unresolved = PF.transitions().all().stream().filter(t -> !t.requirement.getUnresolved().isEmpty()).count();
		assertTrue("unparseable gates are few, and they stay closed: " + unresolved, unresolved <= 10);
		assertEquals(RouteTable.fingerprint(PF.transitions()), RouteTable.fingerprint(TransitionCatalog.bundled()));
		for (String d : ROUTES.destinations())
		{
			int[] goals = ROUTES.goals(d);
			assertTrue("a standable goal for " + d, goals.length > 0);
			for (int g : goals)
			{
				assertFalse(d + " goal " + NavPoint.text(g), PF.map().blocked(NavPoint.x(g), NavPoint.y(g), NavPoint.plane(g)));
			}
		}
		// The dive is Spyglass's own transition; its source is recorded in the data file.
		assertTrue(PF.transitions().all().stream().anyMatch(t -> t.instruction().equals("Dive from the rowboat")));
	}

	// -- route search -------------------------------------------------------------------------------------------

	@Test
	public void aLongWalk_acrossManyRegions_isTraversableStepByStep_andFast()
	{
		long t0 = System.currentTimeMillis();
		Pathfinder.Search s = PF.toNearest(new int[]{p(2964, 3378, 0)}, ROUTES.goals("stop:Catherby#allotment.catherby.n"), NavAccess.UNGATED, 5000,
			RoutePlanner.MAX_NODES);
		long ms = System.currentTimeMillis() - t0;
		assertEquals(Pathfinder.Status.FOUND, s.getStatus());
		NavPath path = s.path(s.getFound());
		assertTraversable(path);
		java.util.Set<Integer> regions = new java.util.HashSet<>();
		for (int i = 0; i < path.size(); i++)
		{
			regions.add(NavPoint.x(path.tile(i)) >> 6 << 8 | NavPoint.y(path.tile(i)) >> 6);
		}
		assertTrue("crosses scene-sized regions: " + regions.size(), regions.size() >= 4);
		assertTrue("Falador to Catherby is well beyond one scene: " + path.walkTiles(), path.walkTiles() > 104);
		assertTrue("bounded and quick: " + ms + " ms, " + s.explored() + " tiles", ms < 3000 && s.explored() < RoutePlanner.MAX_NODES);
	}

	@Test
	public void planeChanges_doorsAndShortcuts_andTheAccessibleAlternative()
	{
		// Troll Stronghold roof from the stony basalt: the 73 Agility rocks, or through the stronghold and up its ladder.
		List<RouteTable.Walk> troll = ROUTES.variants("stop:Troll Stronghold", "arr:stony_basalt.troll_stronghold");
		assertEquals("73 Agility", troll.get(0).getRequirement().toString());
		RouteTable.Walk plain = ROUTES.best("stop:Troll Stronghold", "arr:stony_basalt.troll_stronghold", NavAccess.UNGATED);
		assertNotNull("an ordinary walk exists", plain);
		Transition ladder = plain.getTransitions().get(plain.getTransitions().size() - 1);
		assertEquals("Climb up the troll ladder", ladder.instruction());
		assertTrue("changes plane", NavPoint.plane(ladder.from) != NavPoint.plane(ladder.to));
		// The same search run live: through the plane change, every step traversable.
		int start = ROUTES.start("arr:stony_basalt.troll_stronghold");
		Pathfinder.Search s = PF.toNearest(new int[]{start}, ROUTES.goals("stop:Troll Stronghold"), NavAccess.UNGATED, 5000, RoutePlanner.MAX_NODES);
		NavPath path = s.path(s.getFound());
		assertTraversable(path);
		assertEquals(plain.getCost(), path.cost());

		// A door on a route, then the same route with that door refused: the search avoids it or says there's no way.
		String[] found = findRouteWith("Open");
		assertNotNull("some route opens a door", found);
		RouteTable.Walk w = ROUTES.variants(found[0], found[1]).get(0);
		Transition door = w.getTransitions().stream().filter(t -> t.option != null && t.option.equals("Open")).findFirst().orElseThrow(AssertionError::new);
		Pathfinder.Search with = PF.toNearest(new int[]{ROUTES.start(found[1])}, ROUTES.goals(found[0]), NavAccess.ANY, 20000, 3_000_000);
		assertTraversable(with.path(with.getFound()));
		Pathfinder.Search without = PF.toNearest(new int[]{ROUTES.start(found[1])}, ROUTES.goals(found[0]), excluding(door), 20000, 3_000_000);
		if (without.getStatus() == Pathfinder.Status.FOUND)
		{
			NavPath alt = without.path(without.getFound());
			assertTraversable(alt);
			assertFalse("the refused door is not used", alt.transitions().contains(door));
			assertTrue(alt.cost() >= with.path(with.getFound()).cost());
		}
	}

	/** Access refusing one specific transition (by identity), allowing the rest. */
	private static NavAccess excluding(Transition door)
	{
		return new NavAccess()
		{
			@Override
			public boolean allows(NavRequirement r)
			{
				return r.getUnresolved().isEmpty();
			}

			@Override
			public boolean allowsTransition(Transition t)
			{
				return t != door && allows(t.requirement);
			}
		};
	}

	private static String[] findRouteWith(String option)
	{
		for (String d : ROUTES.destinations())
		{
			for (String s : ROUTES.startsFor(d))
			{
				for (Transition t : ROUTES.variants(d, s).get(0).getTransitions())
				{
					if (option.equals(t.option))
					{
						return new String[]{d, s};
					}
				}
			}
		}
		return null;
	}

	@Test
	public void everyStop_endsOnAStandableTile_beside_itsMarker()
	{
		for (String d : ROUTES.destinations())
		{
			if (!d.startsWith("stop:"))
			{
				continue;
			}
			String best = null;
			int cost = Integer.MAX_VALUE;
			for (String s : ROUTES.startsFor(d))
			{
				int c = ROUTES.variants(d, s).get(0).getCost();
				if (c < cost)
				{
					cost = c;
					best = s;
				}
			}
			assertNotNull("a route to " + d, best);
			Pathfinder.Search s = PF.toNearest(new int[]{ROUTES.start(best)}, ROUTES.goals(d), NavAccess.ANY, cost + 5, 3_000_000);
			assertEquals(d + " from " + best, Pathfinder.Status.FOUND, s.getStatus());
			NavPath path = s.path(s.getFound());
			assertTraversable(path);
			assertEquals("the live search agrees with the route data for " + d, cost, path.cost());
			for (int i = 0; i < path.size(); i++)
			{
				assertFalse(d + " never through the Wilderness: " + NavPoint.text(path.tile(i)), Wilderness.contains(path.tile(i)));
			}
		}
	}

	// -- requirements on every leg -------------------------------------------------------------------------------

	@Test
	public void requirementsOnEveryLeg_decideTheVariant_andUnknownNeverOpensAGate()
	{
		AccountCapability low = AccountFixtures.base(40).build();
		AccountCapability high = AccountFixtures.maxed().build();
		assertEquals("66 Agility", ROUTES.best("stop:Taverley", "arr:spell.falador", NavAccess.of(high)).getRequirement().toString());
		assertTrue(ROUTES.best("stop:Taverley", "arr:spell.falador", NavAccess.of(low)).getRequirement().isNone());
		// Kastori: the quetzal landing needs the quest and the built landing (a varp); unread → the achilka boat instead.
		RouteTable.Walk k = ROUTES.best("stop:Kastori", "arr:quetzal_whistle.auburnvale", NavAccess.of(high));
		assertTrue(k.getTransitions().toString(), k.getTransitions().stream().anyMatch(t -> t.instruction().contains("achilka")));
		AccountCapability built = AccountFixtures.maxed().varp(4182, 16384).build();
		RouteTable.Walk q = ROUTES.best("stop:Kastori", "arr:quetzal_whistle.auburnvale", NavAccess.of(built));
		assertEquals("Take the renu to Kastori", q.getTransitions().get(0).instruction());
		// Anglers' Retreat: only by the rowboat the Sailing unlock opens; not read → no walk for this account.
		assertNull(ROUTES.best("stop:Anglers' Retreat", "arr:spirit_tree.brimhaven", NavAccess.of(high)));
		AccountCapability rowboat = AccountFixtures.maxed().varbit(18355, 1).build();
		assertNotNull(ROUTES.best("stop:Anglers' Retreat", "arr:spirit_tree.brimhaven", NavAccess.planning(rowboat)));
		// A world network journey carries its walk's requirement into the transport's evaluation.
		TransportCatalog catalog = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
		// (one whose every walk to its entrance needs a skill: an account below it has no way there).
		TransportDef gated = catalog.all().stream().filter(t -> t.walkTo != null && ROUTES.variants(t.walkTo, t.walkFrom).stream()
			.allMatch(w -> !w.getRequirement().getSkills().isEmpty())).findFirst().orElseThrow(AssertionError::new);
		// Every recorded variant needs skills this account lacks, yet the account's own search finds a walk (a 1-Agility step the
		// "no skill-gated" variant leaves out) that waits only on an unlock not read yet: unknown, never closed, never met.
		AccountCapability one = low1();
		com.osrstelemetry.plugin.travel.TransportAvailability a = com.osrstelemetry.plugin.travel.TransportEvaluator.evaluate(gated, one,
			com.osrstelemetry.plugin.account.EvidenceSource.NONE, AccountFixtures.NOW);
		assertTrue(a.getMissing().toString(), a.getMissing().stream().anyMatch(u -> u.startsWith("the walk needs")));
		assertTrue(a.getMet().toString(), a.getMet().stream().noneMatch(u -> u.startsWith("the walk")));
		assertTrue(a.getMissing().toString(), a.getMissing().stream().noneMatch(u -> u.contains("varp") || u.contains(">")));
		// Read the unlock as short: no way even then, so closed, with the cheapest walk's reasons.
		AccountCapability.Builder sb = AccountFixtures.base(1);
		for (net.runelite.api.Quest quest : net.runelite.api.Quest.values())
		{
			sb.quest(quest, net.runelite.api.QuestState.FINISHED);
		}
		ROUTES.variants(gated.walkTo, gated.walkFrom).get(0).getRequirement().getVars().forEach(v -> {
			if (v.varp)
			{
				sb.varp(v.id, 0);
			}
			else
			{
				sb.varbit(v.id, 0);
			}
		});
		com.osrstelemetry.plugin.travel.TransportAvailability closed = com.osrstelemetry.plugin.travel.TransportEvaluator.evaluate(gated,
			sb.build(), com.osrstelemetry.plugin.account.EvidenceSource.NONE, AccountFixtures.NOW);
		assertTrue(closed.getUnmet().toString(), closed.getUnmet().stream().anyMatch(u -> u.startsWith("the walk needs")));
	}

	@Test
	public void aUsefulEntrance_moreThanSixtyTilesFromItsTeleport_isKept()
	{
		TransportCatalog catalog = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
		TransportDef far = catalog.all().stream().filter(t -> t.walk != null && t.walk.getWalkTiles() > 60).findFirst().orElse(null);
		assertNotNull("no distance cut-off: walks over 60 tiles are journeys too", far);
		// ...and an entrance that cannot be walked to at all is not: BIS is the closed unicorn pen.
		assertTrue(catalog.all().stream().noneMatch(t -> t.via != null && t.via.node.equals("ring.bis")));
	}

	// -- live routes ---------------------------------------------------------------------------------------------

	@Test
	public void liveRoute_walksToAnEntranceFromHere_whenThatBeatsTheTeleport_andNotWhenItDoesnt()
	{
		Map<String, RoutePlanner.Goal> goals = new LinkedHashMap<>();
		goals.put("stop:Canifis", new RoutePlanner.Goal(ROUTES.goals("stop:Canifis"), 0));
		goals.put("node:ring.djp", new RoutePlanner.Goal(ROUTES.goals("node:ring.djp"), 10 + 5));
		// Standing in East Ardougne's market: walk to the Tower of Life ring, dial CKS.
		LiveRoute near = RoutePlanner.plan(PF, "Canifis", ROUTES.start("arr:spell.ardougne"), goals, NavAccess.UNGATED, 200, RoutePlanner.MAX_NODES);
		assertEquals(NavOutcome.FOUND, near.getOutcome());
		assertEquals("node:ring.djp", near.getReached());
		assertTraversable(near.getPath());
		// Far away (Lumbridge): walking anywhere costs more than the teleport journey allows (teleport instead).
		LiveRoute far = RoutePlanner.plan(PF, "Canifis", p(3222, 3218, 0), goals, NavAccess.UNGATED, 60, RoutePlanner.MAX_NODES);
		assertNull(far.getPath());
		assertEquals(NavOutcome.EXCEEDS_COMPARISON, far.getOutcome());
		// Off the route (a teleport elsewhere, or a detour): no progress, so guidance asks again.
		assertTrue(near.progress(near.getPath().tile(3), 4) >= 0);
		assertEquals(-1, near.progress(p(3222, 3218, 0), 4));
		// Underwater is reached only by diving: nothing walks there from the surface.
		LiveRoute sea = RoutePlanner.plan(PF, "sea", p(3725, 3808, 0), Collections.singletonMap("stop:x",
			new RoutePlanner.Goal(ROUTES.goals("stop:Fossil Island (underwater)"), 0)), NavAccess.UNGATED, 5000, RoutePlanner.MAX_NODES);
		assertTrue(sea.getPath().transitions().toString(), sea.getPath().transitions().stream().anyMatch(t -> t.instruction().startsWith("Dive")));
		List<String> steps = new ArrayList<>();
		sea.getPath().transitions().forEach(t -> steps.add(t.instruction()));
		assertTraversable(sea.getPath());
	}

	/** Every quest finished, every skill 1: walks gated by skills only are closed. */
	private static AccountCapability low1()
	{
		AccountCapability.Builder b = AccountFixtures.base(1);
		for (net.runelite.api.Quest q : net.runelite.api.Quest.values())
		{
			b.quest(q, net.runelite.api.QuestState.FINISHED);
		}
		return b.build();
	}
}
