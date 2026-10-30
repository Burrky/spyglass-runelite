package com.osrstelemetry.plugin.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountFixtures;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Pass 31 audit of the shared router's magic mushtree network (Shortest Path's {@code magic_mushtrees.tsv}): every landing
 * reaches every other, the instructions name the destination ("Mushtree → Mushroom Meadow"), and a live route starts even
 * where the player stands on a tile the bundled map blocks (beside the Verdant Valley bird houses, live 2026-10-29).
 */
public class MushtreeRoutingTest
{
	static List<Transition> mushtrees()
	{
		List<Transition> out = new java.util.ArrayList<>();
		for (Transition t : TransitionCatalog.bundled().all())
		{
			if (t.family == Transition.Family.MAGIC_MUSHTREE)
			{
				out.add(t);
			}
		}
		return out;
	}

	@Test
	public void theNetwork_linksEveryLanding_withNoRequirement_andNamesTheDestination()
	{
		List<Transition> all = mushtrees();
		// Four trees, two departure tiles each, three destinations each (never its own landing).
		assertEquals(24, all.size());
		java.util.Set<String> labels = new java.util.TreeSet<>();
		for (Transition t : all)
		{
			assertTrue(t.requirement.isNone());
			labels.add(t.label());
			assertFalse(t.label(), t.label().matches(".*\\d.*"));
		}
		assertEquals("[Mushtree → House on the Hill, Mushtree → Mushroom Meadow, Mushtree → Sticky Swamp, Mushtree → Verdant Valley]", labels.toString());
		Transition meadow = all.stream().filter(t -> t.label().endsWith("Mushroom Meadow")).findFirst().get();
		assertEquals("Take the magic mushtree to Mushroom Meadow (option 4)", meadow.instruction());
	}

	@Test
	public void fromBesideTheValleyHouses_theLiveRouteTakesTheMushtree_evenFromABlockedTile()
	{
		RouteTable table = RouteTable.bundled();
		Pathfinder pf = Pathfinder.bundled();
		NavAccess access = NavAccess.of(AccountFixtures.base(99).build());
		// 3763,3757: beside Verdant Valley's south-west house; the bundled map blocks it (the player stands there in game).
		for (int[] o : new int[][]{{3763, 3757}, {3761, 3757}})
		{
			Map<String, RoutePlanner.Goal> goals = Collections.singletonMap("m", new RoutePlanner.Goal(table.goals("stop:birdhouse.meadow_north"), 0));
			LiveRoute r = RoutePlanner.plan(pf, "Mushroom Meadow (North)", NavPoint.pack(o[0], o[1], 0), goals, access, 2000, RoutePlanner.MAX_NODES);
			assertEquals(java.util.Arrays.toString(o), NavOutcome.FOUND, r.getOutcome());
			NavPath p = r.getPath();
			assertNotNull(p);
			Transition via = null;
			for (int i = 0; i < p.size(); i++)
			{
				via = via == null ? p.via(i) : via;
			}
			assertNotNull("the route uses the mushtree", via);
			assertEquals("Mushtree → Mushroom Meadow", via.label());
			assertTrue("far shorter than the 124-tile walk: " + p.size(), p.size() < 40);
			assertTrue("the route starts where the player stands", r.progress(NavPoint.pack(o[0], o[1], 0), 4) >= 0);
		}
	}
}
