package com.osrstelemetry.plugin.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Arrival at a multi-object allotment (live 2026-10-22: "Walk to patch · 5 tiles" while harvesting it): the distance is to
 * any of the patch's objects, a patch change beside it is arrival, and moving along it while working never returns to travel.
 */
public class PatchArrivalTest
{
	/** An L-shaped allotment of two objects (scene tiles), as Falador's north-west one. */
	private static final List<int[]> ALLOTMENT = Arrays.asList(new int[]{10, 10, 14, 11}, new int[]{10, 12, 11, 17});

	@Test
	public void theDistanceIsToTheNearestTileOfAnyObject_neverTheCentre()
	{
		assertEquals(0, PatchArrival.distance(12, 10, ALLOTMENT));
		assertEquals("beside the long arm, far from the first object's corner", 1, PatchArrival.distance(12, 16, ALLOTMENT));
		assertEquals(5, PatchArrival.distance(5, 15, ALLOTMENT));
		assertEquals(-1, PatchArrival.distance(5, 5, Collections.emptyList()));
	}

	@Test
	public void harvestingAlongTheAllotment_staysArrived_andOnlyLeavingEndsIt()
	{
		boolean at = PatchArrival.at(false, 5, PatchArrival.distance(5, 15, ALLOTMENT), false);
		assertFalse("approaching", at);
		at = PatchArrival.at(at, 0, PatchArrival.distance(12, 16, ALLOTMENT), false);
		assertTrue("beside the far arm: arrived", at);
		at = PatchArrival.at(at, 2, PatchArrival.distance(13, 14, ALLOTMENT), false);
		assertTrue("moving along it while working (a short walk drawn) keeps it", at);
		at = PatchArrival.at(at, 4, PatchArrival.distance(17, 14, ALLOTMENT), false);
		assertFalse("3 tiles from every object: left", at);
	}

	@Test
	public void theGameShowingThePatchChange_besideIt_isArrival_aHoverIsNot()
	{
		assertTrue("harvested a crop 2 tiles away while a path still showed 5", PatchArrival.at(false, 5, 2, true));
		assertFalse("a change seen from afar is not the player's work here", PatchArrival.at(false, 5, 4, true));
		assertFalse("nothing changed (a hovered menu is no evidence): still walking", PatchArrival.at(false, 5, 2, false));
	}
}
