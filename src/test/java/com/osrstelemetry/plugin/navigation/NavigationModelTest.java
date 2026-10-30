package com.osrstelemetry.plugin.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import org.junit.Test;

/**
 * The navigation model checked against hand-built worlds and hand-worked answers (canonical §28.11), not against the
 * generated data: mixed requirements, search outcomes, unsupported data, one-way and locked transitions, pruning, the
 * Wilderness, progress along a route and stale results.
 */
public class NavigationModelTest
{
	/**
	 * Two tall walls (x=10 and x=20) with a gap only at the top (y=39). S (9,2) and G (21,2) sit at the bottom. Wall 1 has a
	 * 70 Agility shortcut at the bottom; wall 2 a door that needs Dragon Slayer I. Worked by hand (8-way moves; a diagonal
	 * needs both flanking steps, so each gap costs one extra step on each side; transitions two tiles): both gates 12; door
	 * only 78; neither 86; the shortcut and round the door 78 (2 + 37 + 2 + 37).
	 */
	private static SyntheticWorld twoWalls()
	{
		String[] rows = new String[40];
		for (int y = 39; y >= 0; y--)
		{
			StringBuilder b = new StringBuilder();
			for (int x = 0; x < 31; x++)
			{
				b.append((x == 10 || x == 20) && y < 39 ? '#' : '.');
			}
			rows[39 - y] = b.toString();
		}
		SyntheticWorld w = new SyntheticWorld(50, 50, rows);
		w.transition(9, 2, 11, 2, "Climb-over", "Wall", SyntheticWorld.req("70 Agility", null, null, null));
		w.transition(19, 2, 21, 2, "Open", "Door", SyntheticWorld.req(null, "Dragon Slayer I", null, null));
		return w;
	}

	private static List<String> steps(RouteTable.Walk w)
	{
		List<String> out = new java.util.ArrayList<>();
		w.getTransitions().forEach(t -> out.add(t.instruction()));
		return out;
	}

	@Test
	public void mixedRequirements_theAccountsBestRoute_isOneNoPrecomputedVariantHolds()
	{
		SyntheticWorld w = twoWalls();
		Pathfinder pf = w.pathfinder();
		int s = w.at(9, 2);
		RouteTable table = SyntheticWorld.table(pf, "stop:G", new int[]{w.at(21, 2)}, Collections.singletonMap("arr:S", s));
		List<RouteTable.Walk> v = table.variants("stop:G", "arr:S");
		// The three precomputed variants: both gates; the door only (no skill-gated step); neither.
		assertEquals(Arrays.asList("Climb over the wall", "Open the door"), steps(v.get(0)));
		assertEquals(Collections.singletonList("Open the door"), steps(v.get(1)));
		assertEquals(Collections.emptyList(), steps(v.get(2)));
		// 70 Agility, Dragon Slayer I not finished: the best usable route climbs the wall and goes round the door — none of them.
		AccountCapability agile = AccountFixtures.base(70).quest(Quest.DRAGON_SLAYER_I, QuestState.IN_PROGRESS).build();
		AccountRouter router = new AccountRouter(pf, table);
		AccountRouter.Answer a = router.walk("stop:G", "arr:S", router.planning(agile));
		assertEquals(NavOutcome.FOUND, a.getOutcome());
		assertTrue("proven best for this account", a.isBest());
		assertEquals(Collections.singletonList("Climb over the wall"), steps(a.getWalk()));
		assertEquals("hand-worked costs: both 12, door only 78, neither 86", Arrays.asList(12, 78, 86),
			Arrays.asList(v.get(0).getCost(), v.get(1).getCost(), v.get(2).getCost()));
		assertEquals("shortcut, then round the door", 78, a.getWalk().getCost());
		// Both met: the unrestricted best, straight from the data.
		AccountCapability both = AccountFixtures.base(70).quest(Quest.DRAGON_SLAYER_I, QuestState.FINISHED).build();
		assertEquals(v.get(0).getCost(), router.walk("stop:G", "arr:S", router.planning(both)).getWalk().getCost());
	}

	@Test
	public void accountSearch_inTheBackground_interimIsAUsableVariant_andAClosedRouterNeverSearchesOnTheCaller()
	{
		SyntheticWorld w = twoWalls();
		Pathfinder pf = w.pathfinder();
		RouteTable table = SyntheticWorld.table(pf, "stop:G", new int[]{w.at(21, 2)}, Collections.singletonMap("arr:S", w.at(9, 2)));
		AccountCapability agile = AccountFixtures.base(70).quest(Quest.DRAGON_SLAYER_I, QuestState.IN_PROGRESS).build();
		AccountRouter router = new AccountRouter(pf, table);
		List<Runnable> queued = new java.util.ArrayList<>();
		router.setExecutor(queued::add);
		long v0 = router.version();
		AccountRouter.Answer interim = router.walk("stop:G", "arr:S", router.planning(agile));
		assertEquals("meanwhile: the cheapest variant this account can use (neither gate)", NavOutcome.FOUND, interim.getOutcome());
		assertFalse("not claimed best", interim.isBest());
		assertEquals(86, interim.getWalk().getCost());
		router.walk("stop:G", "arr:S", router.planning(agile));
		assertEquals("one search queued, not one per ask", 1, queued.size());
		queued.get(0).run();
		assertTrue("plans redo when the queue drains", router.version() > v0);
		AccountRouter.Answer done = router.walk("stop:G", "arr:S", router.planning(agile));
		assertTrue(done.isBest());
		assertEquals(78, done.getWalk().getCost());

		// Shut down: the router refuses work; a late caller gets the interim answer and nothing runs on its thread.
		AccountRouter closed = new AccountRouter(pf, table);
		closed.setExecutor(r -> {
			throw new java.util.concurrent.RejectedExecutionException();
		});
		AccountRouter.Answer late = closed.walk("stop:G", "arr:S", closed.planning(agile));
		assertFalse(late.isBest());
		assertEquals(0, closed.searches());
	}

	@Test
	public void unknownRequirements_stayUnknown_andABlockedShortcutNeverHidesTheOrdinaryWalk()
	{
		SyntheticWorld w = twoWalls();
		Pathfinder pf = w.pathfinder();
		RouteTable table = SyntheticWorld.table(pf, "stop:G", new int[]{w.at(21, 2)}, Collections.singletonMap("arr:S", w.at(9, 2)));
		AccountRouter router = new AccountRouter(pf, table);
		// Levels and quests never read: neither gate is assumed open; the ordinary walk is still there.
		AccountCapability unread = AccountCapability.builder().owner(AccountFixtures.OWNER).build();
		AccountRouter.Answer a = router.walk("stop:G", "arr:S", router.planning(unread));
		assertEquals(NavOutcome.FOUND, a.getOutcome());
		assertTrue(steps(a.getWalk()).isEmpty());
		assertFalse(NavAccess.of(unread).allows(SyntheticWorld.req("70 Agility", null, null, null)));
		assertFalse(NavAccess.of(unread).allows(SyntheticWorld.req(null, "Dragon Slayer I", null, null)));
	}

	@Test
	public void searchOutcomes_areKeptApart()
	{
		SyntheticWorld w = new SyntheticWorld(50, 50,
			"..........###",
			"..........#.#",
			"..........###",
			".............");
		Pathfinder pf = w.pathfinder();
		// The tile inside the box is proven unreachable: everything reachable was explored.
		Pathfinder.Search closed = pf.toNearest(new int[]{w.at(0, 0)}, new int[]{w.at(11, 2)}, NavAccess.ANY, Integer.MAX_VALUE, 1_000_000);
		assertEquals(Pathfinder.Status.UNREACHABLE, closed.getStatus());
		assertTrue(closed.isExhaustive());
		// A node budget too small to tell: nothing proven.
		Pathfinder.Search budget = pf.toNearest(new int[]{w.at(0, 0)}, new int[]{w.at(12, 0)}, NavAccess.ANY, Integer.MAX_VALUE, 5);
		assertEquals(Pathfinder.Status.BUDGET_EXHAUSTED, budget.getStatus());
		assertFalse(budget.isExhaustive());
		// Reachable but dearer than the comparison: proven to lose.
		Map<String, RoutePlanner.Goal> goals = Collections.singletonMap("stop:x", new RoutePlanner.Goal(new int[]{w.at(12, 0)}, 0));
		LiveRoute dear = RoutePlanner.plan(pf, "x", w.at(0, 0), goals, NavAccess.ANY, 5, 1_000_000);
		assertEquals(NavOutcome.EXCEEDS_COMPARISON, dear.getOutcome());
		assertNull(dear.getPath());
		assertEquals(NavOutcome.FOUND, RoutePlanner.plan(pf, "x", w.at(0, 0), goals, NavAccess.ANY, 50, 1_000_000).getOutcome());
		LiveRoute unreachable = RoutePlanner.plan(pf, "x", w.at(0, 0), Collections.singletonMap("stop:x", new RoutePlanner.Goal(new int[]{w.at(11, 2)}, 0)),
			NavAccess.ANY, Integer.MAX_VALUE, 1_000_000);
		assertEquals(NavOutcome.UNREACHABLE, unreachable.getOutcome());
		assertEquals(NavOutcome.BUDGET_EXHAUSTED, RoutePlanner.plan(pf, "x", w.at(0, 0), goals, NavAccess.ANY, 50, 3).getOutcome());
		assertEquals("no goals: data unavailable", NavOutcome.DATA_UNAVAILABLE,
			RoutePlanner.plan(pf, "x", w.at(0, 0), Collections.singletonMap("stop:x", new RoutePlanner.Goal(new int[0], 0)), NavAccess.ANY, 50, 100).getOutcome());
		// Route data: a missing pair is only "unreachable" when the destination's searches were exhaustive.
		RouteTable table = SyntheticWorld.table(pf, "stop:box", new int[]{w.at(11, 2)}, Collections.singletonMap("arr:S", w.at(0, 0)));
		assertEquals(NavOutcome.UNREACHABLE, table.existence("stop:box", "arr:S"));
		assertEquals(NavOutcome.DATA_UNAVAILABLE, table.existence("stop:other", "arr:S"));
		assertEquals(NavOutcome.DATA_UNAVAILABLE, table.existence("stop:box", "arr:unknown"));
	}

	@Test
	public void requirementData_unsupportedOrMalformed_neverOpensAGate()
	{
		Map<String, int[]> names = new HashMap<>();
		names.put("COINS", new int[]{995});
		names.put("HEADSLOT", new int[]{4514, 4516});
		for (String[] cell : new String[][]{
			{"skills", "Max Agility"}, {"skills", "1000 Total level"}, {"skills", "70Agility"}, {"skills", "70 Agilty"},
			{"quests", "Not A Quest"}, {"items", "UNLOCK_DRAGONTOOTH=1"}, {"items", "NOT_AN_ITEM=1"}, {"items", "COINS=x"}, {"items", "COINS"},
			{"varbits", "892@30"}, {"varbits", "abc=1"}, {"varbits", "12"}, {"varps", "4182~1"}})
		{
			NavRequirement r = NavRequirement.parse("skills".equals(cell[0]) ? cell[1] : null, "quests".equals(cell[0]) ? cell[1] : null,
				"items".equals(cell[0]) ? cell[1] : null, "varbits".equals(cell[0]) ? cell[1] : null, "varps".equals(cell[0]) ? cell[1] : null, names);
			assertFalse(cell[1] + " is unresolved", r.getUnresolved().isEmpty());
			assertFalse(cell[1] + " never allowed", NavAccess.ANY.allows(r));
			assertFalse(cell[1] + " never allowed for a maxed account", NavAccess.of(AccountFixtures.maxed().build()).allows(r));
		}
		NavRequirement parsed = NavRequirement.parse("70 Agility;50 Strength", "Dragon Slayer I", "COINS=30|995=40", "4182&16384;111>0", "517<2",
			names);
		assertTrue(parsed.getUnresolved().isEmpty());
		assertEquals(2, parsed.getSkills().size());
		assertEquals(3, parsed.getVars().size());
		assertTrue(parsed.getVars().get(2).varp);
		// "=0": none of those may be carried or worn (the Castle Wars hoods): met only when we can see they aren't.
		NavRequirement noHood = NavRequirement.parse(null, null, "HEADSLOT=0", null, null, names);
		assertTrue(noHood.getUnresolved().isEmpty());
		Map<Integer, Integer> hood = new HashMap<>();
		hood.put(4514, 1);
		assertTrue(NavAccess.of(AccountFixtures.base(1).build()).allows(noHood));
		assertFalse(NavAccess.of(AccountFixtures.base(1).worn(hood).build()).allows(noHood));
		assertFalse("containers not read: unknown, not met", NavAccess.of(AccountCapability.builder().owner(AccountFixtures.OWNER).build()).allows(noHood));
	}

	@Test
	public void oneWayAndLockedTransitions_stayThatWay()
	{
		SyntheticWorld w = new SyntheticWorld(50, 50,
			".....#.....",
			".....#.....",
			".....#.....");
		w.transition(4, 1, 6, 1, "Jump-down", "Ledge", NavRequirement.NONE);
		w.transition(6, 0, 4, 0, "Open", "Gate", SyntheticWorld.req(null, null, "1542=1", null)); // a key, carried
		Pathfinder pf = w.pathfinder();
		assertEquals(Pathfinder.Status.FOUND, pf.toNearest(new int[]{w.at(0, 1)}, new int[]{w.at(10, 1)}, NavAccess.UNGATED, 1000, 10_000).getStatus());
		// Back west: only the locked gate; without the key nothing — the jump is one-way.
		AccountCapability noKey = AccountFixtures.base(1).build();
		Pathfinder.Search back = pf.toNearest(new int[]{w.at(10, 1)}, new int[]{w.at(0, 1)}, NavAccess.of(noKey), 1000, 10_000);
		assertEquals(Pathfinder.Status.UNREACHABLE, back.getStatus());
		Map<Integer, Integer> key = new HashMap<>();
		key.put(1542, 1);
		Pathfinder.Search withKey = pf.toNearest(new int[]{w.at(10, 1)}, new int[]{w.at(0, 1)}, NavAccess.of(AccountFixtures.base(1).inventory(key).build()),
			1000, 10_000);
		assertEquals(Pathfinder.Status.FOUND, withKey.getStatus());
		assertEquals("Open the gate", withKey.path(withKey.getFound()).transitions().get(0).instruction());
	}

	@Test
	public void theRealData_hasNoInventedReverseTransitions()
	{
		TransitionCatalog c = TransitionCatalog.bundled();
		Transition jump = c.all().stream().filter(t -> "Jump-down".equals(t.option)).findFirst().orElseThrow(AssertionError::new);
		boolean reverse = c.from(jump.to).stream().anyMatch(t -> t.to == jump.from && t.objectId == jump.objectId);
		assertFalse("a Jump-down has no catalogued way back up the same object: " + jump, reverse);
		Transition monk = c.all().stream().filter(t -> "Monk of Entrana".equals(t.target) && NavPoint.x(t.to) < 2900).findFirst()
			.orElseThrow(AssertionError::new);
		assertTrue(monk.requirement.getRestrictions().contains(NavRequirement.ENTRANA));
		assertTrue(monk.instruction().endsWith("(no weapons or armour)"));
		Transition back = c.all().stream().filter(t -> "Monk of Entrana".equals(t.target) && NavPoint.x(t.to) > 2900).findFirst()
			.orElseThrow(AssertionError::new);
		assertTrue("the way back isn't searched", back.requirement.getRestrictions().isEmpty());
	}

	@Test
	public void entrana_weaponsOrArmourWorn_closeTheBoat_liveButNotWhenPlanning()
	{
		NavRequirement r = NavRequirement.NONE.withRestriction(NavRequirement.ENTRANA);
		AccountCapability bare = AccountFixtures.base(1).wornSlots(new HashSet<>(Arrays.asList(1, 2, 12))).build(); // cape, amulet, ring
		AccountCapability armed = AccountFixtures.base(1).wornSlots(new HashSet<>(Arrays.asList(3))).build(); // a weapon
		AccountCapability unread = AccountFixtures.base(1).build();
		assertTrue(NavAccess.of(bare).allows(r));
		assertFalse(NavAccess.of(armed).allows(r));
		assertFalse("not read: not assumed", NavAccess.of(unread).allows(r));
		assertTrue("planning: something to do before setting off", NavAccess.planning(armed).allows(r));
	}

	@Test
	public void wilderness_neverEntered_byWalkingOrByATransition()
	{
		// Region 50_55: y 3520-3583; the Wilderness starts at y 3525 here. A wall forces any walk through y >= 3525.
		SyntheticWorld w = new SyntheticWorld(50, 55,
			"...........",
			"...........",
			"...........",
			"...........",
			"...........",
			"...........",
			".....#.....",
			".....#.....",
			".....#.....",
			".....#.....",
			".....#.....");
		w.transition(4, 0, 4, 8, "Pull", "Lever", NavRequirement.NONE); // into the Wilderness
		Pathfinder pf = w.pathfinder();
		assertTrue(Wilderness.contains(w.at(4, 8)));
		assertFalse(Wilderness.contains(w.at(4, 0)));
		Pathfinder.Search s = pf.toNearest(new int[]{w.at(0, 0)}, new int[]{w.at(10, 0)}, NavAccess.ANY, 1000, 100_000);
		assertEquals("only through the Wilderness: no route", Pathfinder.Status.UNREACHABLE, s.getStatus());
		// Standing in it already, the player may walk out.
		Pathfinder.Search out = pf.toNearest(new int[]{w.at(0, 9)}, new int[]{w.at(10, 0)}, NavAccess.ANY, 1000, 100_000);
		assertEquals(Pathfinder.Status.FOUND, out.getStatus());
	}

	@Test
	public void entrancePruning_keepsRoutesThatDifferInRequirements()
	{
		java.util.Set<String> none = new java.util.TreeSet<>();
		java.util.Set<String> kourend = new java.util.TreeSet<>(Collections.singletonList("proof Visited Great Kourend"));
		// {node, walk, cheapest, plain (no gated step), signature}
		Object[] near = {"near", null, 20, 20, kourend};
		Object[] far = {"far", null, 40, 40, none};
		List<Object[]> both = Arrays.asList(near, far);
		assertFalse("nearer but needs Kourend: kept", com.osrstelemetry.plugin.travel.TransportCatalogAccess.dominated(near, both));
		assertFalse("farther but needs nothing: kept", com.osrstelemetry.plugin.travel.TransportCatalogAccess.dominated(far, both));
		Object[] gatedOnly = {"gated", null, 10, Integer.MAX_VALUE, none};
		Object[] open = {"open", null, 30, 30, none};
		List<Object[]> pair = Arrays.asList(gatedOnly, open);
		assertFalse("a shortcut-only entrance does not hide one with an ordinary walk", com.osrstelemetry.plugin.travel.TransportCatalogAccess.dominated(open, pair));
		assertFalse("and is itself kept (its walk may be usable)", com.osrstelemetry.plugin.travel.TransportCatalogAccess.dominated(gatedOnly, pair));
		Object[] worse = {"worse", null, 50, 50, none};
		assertTrue("same needs, always dearer: dropped", com.osrstelemetry.plugin.travel.TransportCatalogAccess.dominated(worse, Arrays.asList(open, worse)));
	}

	@Test
	public void progress_isMonotonic_andATransitionCountsOnlyOnObservedArrival()
	{
		// A U-shaped route: out along y=0, through a ladder-like transition at the far end, back along y=4 above the start.
		SyntheticWorld w = new SyntheticWorld(50, 50,
			"...........",
			"###########",
			"###########",
			"###########",
			"...........");
		w.transition(10, 0, 10, 4, "Climb-up", "Ladder", NavRequirement.NONE);
		Pathfinder pf = w.pathfinder();
		LiveRoute r = RoutePlanner.plan(pf, "x", w.at(0, 0), Collections.singletonMap("stop:x", new RoutePlanner.Goal(new int[]{w.at(0, 4)}, 0)),
			NavAccess.ANY, 1000, 10_000);
		assertNotNull(r.getPath());
		// Standing at the start: index 0. The goal end of the route is 4 tiles north (within slack) but beyond the ladder:
		// never matched before the ladder is taken.
		assertEquals(0, r.progress(w.at(0, 0), 4));
		int beforeLadder = r.progress(w.at(9, 0), 4);
		assertTrue(beforeLadder > 0 && r.getPath().via(beforeLadder) == null);
		int after = r.progress(w.at(10, 4), 4);
		assertTrue("observed at the ladder's arrival: through it", after > beforeLadder);
		assertEquals("a big jump elsewhere: off the route", -1, r.progress(NavPoint.pack(3300, 3300, 0), 4));
		// The dive's arrival is an area, specific to the underwater region and plane.
		Transition dive = TransitionCatalog.bundled().all().stream().filter(t -> t.instruction().startsWith("Dive")).findFirst()
			.orElseThrow(AssertionError::new);
		assertTrue(dive.arrivedAt(NavPoint.pack(3740, 10260, 0)));
		assertFalse("same x/y range, other plane", dive.arrivedAt(NavPoint.pack(3740, 10260, 1)));
		assertFalse("the surface above", dive.arrivedAt(NavPoint.pack(3740, 3900, 0)));
		assertFalse("the next region", dive.arrivedAt(NavPoint.pack(3780, 10260, 0)));
	}

	@Test
	public void staleResults_afterAResetOrTeleport_areNeverPublished() throws Exception
	{
		com.osrstelemetry.plugin.NavigationRuntimeProbe probe = new com.osrstelemetry.plugin.NavigationRuntimeProbe();
		assertTrue(probe.staleResultDropped());
	}

	static Map<String, Integer> one(String k, int v)
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}
}
