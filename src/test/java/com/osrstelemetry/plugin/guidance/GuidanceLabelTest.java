package com.osrstelemetry.plugin.guidance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * The label under the player and the guide line are the one current action's own wording (canonical §28.13): written for
 * their place, never cut from the guide line, and never a later step.
 */
public class GuidanceLabelTest
{
	private static GuidanceModel active(String... lines)
	{
		return new GuidanceModel(GuidanceModel.State.ACTIVE, "Catherby · Herb", Arrays.asList(lines), "0/5 done", null, Collections.emptyList(), null,
			"ACT");
	}

	@Test
	public void theLabelAndGuide_areTheActionsOwnWording_notTheChecklist()
	{
		GuidanceModel m = active("○ Travel with Monk of Entrana to Entrana (no weapons or armour)", "Then: Walk to the patch")
			.withAction("Monk of Entrana → Entrana", "Travel with Monk of Entrana to Entrana (no weapons or armour)", null);
		assertEquals("written for its place, not cut", "Monk of Entrana → Entrana", m.getPlayerLabel());
		assertEquals("Travel with Monk of Entrana to Entrana (no weapons or armour)", m.getGuide());
		assertNull("no action: no label (nothing is inferred from the lines)", active("○ Herb: Plant").getPlayerLabel());
	}

	@Test
	public void nothingShows_whenGuidanceIsOffOrPaused()
	{
		assertNull(GuidanceModel.OFF.getPlayerLabel());
		assertNull(new GuidanceModel(GuidanceModel.State.PAUSED, null, Arrays.asList("○ Herb: Plant"), null, null, Collections.emptyList(), null, null)
			.withAction("Plant seeds", "Plant seeds", null).getPlayerLabel());
		assertEquals(0, GuidanceModel.OFF.getObjects().size() + GuidanceModel.OFF.getNpcs().size());
	}
}
