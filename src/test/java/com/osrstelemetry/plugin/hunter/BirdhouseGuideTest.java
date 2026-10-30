package com.osrstelemetry.plugin.hunter;

import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.MIN;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_BUILT;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.R_SEEDED;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.T0;
import static com.osrstelemetry.plugin.hunter.BirdhouseTrapsTest.all;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingAction;
import com.osrstelemetry.plugin.farming.FarmingGuide;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Pass 29: bird house guidance from the spaces as read and where the player is (no cursor). */
public class BirdhouseGuideTest
{
	static List<BirdhousePlan.StopPlan> stops(BirdhouseRun r)
	{
		List<BirdhousePlan.StopPlan> out = new ArrayList<>();
		for (Map.Entry<String, List<BirdhouseSpace>> e : r.byStop().entrySet())
		{
			out.add(new BirdhousePlan.StopPlan(e.getKey(), e.getValue(), null, -1, Collections.emptyList(), Collections.emptyList()));
		}
		return out;
	}

	static FarmingGuide.Objective at(BirdhouseRun r, BirdhouseTraps t, int x, int y, boolean island, long now)
	{
		return BirdhouseGuide.resolve(r, stops(r), t, new int[]{x, y, 0}, null, null, null, null, island, false, now);
	}

	@Test
	public void atAStop_actsOnTheNearestHouseLeft_withItsSpaceOutlined()
	{
		BirdhouseTraps t = BirdhouseRunTest.full();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.REDWOOD, t, T0).begun();
		FarmingGuide.Objective o = at(r, t, 3763, 3757, true, T0);
		assertEquals(FarmingGuide.Kind.ACT, o.getKind());
		assertEquals("verdant_valley", o.getStop());
		assertEquals("Empty bird house", o.getAction().getLabel());
		assertEquals(FarmingAction.Target.Kind.OBJECT, o.getAction().getTarget().kind);
		assertTrue(o.getAction().getTarget().ids.contains(BirdhouseSpace.VALLEY_SOUTH.getObjectId()));
		// Rebuilt: the next step there is the seeds.
		t = t.withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_BUILT), T0 + MIN);
		assertEquals("Add seeds", at(r, t, 3763, 3757, true, T0 + MIN).getAction().getLabel());
		assertTrue(at(r, t, 3763, 3757, true, T0 + MIN).getChecklist().toString().contains("Empty the bird house (Valley (NE))"));
	}

	@Test
	public void awayFromTheIsland_travels_andEverythingSet_completes()
	{
		BirdhouseTraps t = BirdhouseRunTest.full();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.of(BirdhouseSpace.MEADOW_NORTH), BirdhouseTier.REDWOOD, t, T0).begun();
		FarmingGuide.Objective o = at(r, t, 3200, 3200, false, T0);
		assertEquals(FarmingGuide.Kind.TRAVEL, o.getKind());
		assertEquals("meadow_north", o.getStop());
		FarmingGuide.Objective island = at(r, t, 3764, 3869, true, T0);
		assertEquals(FarmingGuide.Kind.TRAVEL, island.getKind());
		assertTrue(island.getChecklist().get(0), island.getChecklist().get(0).contains("Mushtree → Mushroom Meadow"));
		t = t.withReading(all(R_BUILT, R_SEEDED, R_SEEDED, R_SEEDED), T0 + MIN).withReading(all(R_SEEDED, R_SEEDED, R_SEEDED, R_SEEDED), T0 + 2 * MIN);
		assertEquals(FarmingGuide.Kind.COMPLETE, at(r, t, 3677, 3880, true, T0 + 2 * MIN).getKind());
	}

	@Test
	public void preparing_guidesNothing()
	{
		BirdhouseTraps t = BirdhouseRunTest.full();
		BirdhouseRun r = BirdhouseRun.start(EnumSet.allOf(BirdhouseSpace.class), BirdhouseTier.REDWOOD, t, T0);
		assertEquals(FarmingGuide.Kind.NONE, at(r, t, 3763, 3757, true, T0).getKind());
	}
}
