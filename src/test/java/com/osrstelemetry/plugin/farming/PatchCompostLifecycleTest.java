package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * How long compost lasts on a patch (canonical §28.16), per family. OSRS Wiki "Farming" (2026-10-22): compost "only works for
 * one set of seeds. Once the crops are harvested, another bucket of compost must be applied"; treating a patch "before (or
 * immediately after) planting" covers the crop; Fertile Soil "at any point during the plant's growth". So a record lives for
 * one planting: kept through growth, health checks, picking and regrowth, partial harvests and the stump of a chopped tree;
 * ended when that planting leaves the patch (harvested out, dug up or paid away, dead and cleared) or another crop is seen.
 * (RuneLite's tracker also forgets compost at a harvestable state: a display choice, not the game's rule.)
 */
public class PatchCompostLifecycleTest
{
	private static final long MIN = 60_000L;
	private final Map<String, PatchKnowledge> known = new HashMap<>();
	private long t = T0;

	private int raw(FarmingPatchType type, FarmingProduce produce, PatchCondition c, int stage)
	{
		return FarmingKnowledgeFixtures.raw(type, produce, c, stage);
	}

	private void go(String id, int from, int to)
	{
		t += MIN;
		FarmingKnowledgeFixtures.change(known, id, from, to, t);
	}

	private PatchCompost apply(String id, String message)
	{
		t += MIN;
		return PatchCompost.of(PatchCompost.parse(message), t, known.get(id), patch(id));
	}

	@Test
	public void herb_beforePlanting_coversTheCrop_throughPartialHarvests_andEndsWhenHarvestedOut()
	{
		String id = "herb.falador";
		int seed = raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.GROWING, 0);
		int ready = raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
		FarmingKnowledgeFixtures.see(known, id, 3, t);
		PatchCompost c = apply(id, "You treat the herb patch with ultracompost.");
		go(id, 3, seed);
		go(id, seed, ready);
		assertNotNull("grown, lives left", c.current(known.get(id)));
		go(id, ready, ready); // a pick: still harvestable
		assertNotNull("partial harvest", c.current(known.get(id)));
		go(id, ready, 3);
		assertNull("harvested out: that set of seeds is gone", c.current(known.get(id)));
	}

	@Test
	public void bush_keepsItsCompost_throughPickingAndRegrowth_untilDugUp()
	{
		String id = "bush.rimmington";
		int growing = raw(FarmingPatchType.BUSH, FarmingProduce.REDBERRIES, PatchCondition.GROWING, 2);
		int four = raw(FarmingPatchType.BUSH, FarmingProduce.REDBERRIES, PatchCondition.HARVESTABLE, 4);
		int none = raw(FarmingPatchType.BUSH, FarmingProduce.REDBERRIES, PatchCondition.HARVESTABLE, 0);
		FarmingKnowledgeFixtures.see(known, id, growing, t);
		PatchCompost c = apply(id, "You treat the bush patch with supercompost.");
		go(id, growing, four);
		go(id, four, none); // all picked
		go(id, none, four); // regrown
		assertNotNull("picking and regrowth are the same planting", c.current(known.get(id)));
		go(id, four, 3); // dug up
		assertNull(c.current(known.get(id)));
	}

	@Test
	public void tree_keepsItThroughTheHealthCheckAndTheStump_untilRemoved()
	{
		String id = "tree.varrock";
		int growing = raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.GROWING, 5);
		int check = raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.CHECK_HEALTH, -1);
		FarmingKnowledgeFixtures.see(known, id, growing, t);
		PatchCompost c = apply(id, "The tree patch has been treated with supercompost."); // Fertile Soil at any point of growth
		assertEquals(PatchCompost.Source.FERTILE_SOIL, c.getSource());
		go(id, growing, check);
		go(id, check, 61); // checked
		go(id, 61, 62); // chopped: the stump
		assertNotNull(c.current(known.get(id)));
		go(id, 62, 3); // the gardener removed it
		assertNull(c.current(known.get(id)));
	}

	@Test
	public void aDeadCrop_endsItsCompostWhenCleared_andAnotherCropIsNeverCovered()
	{
		String id = "allotment.falador.nw";
		int growing = raw(FarmingPatchType.ALLOTMENT, FarmingProduce.POTATO, PatchCondition.GROWING, 1);
		int dead = raw(FarmingPatchType.ALLOTMENT, FarmingProduce.POTATO, PatchCondition.DEAD, -1);
		FarmingKnowledgeFixtures.see(known, id, growing, t);
		PatchCompost c = apply(id, "You treat the allotment with compost.");
		go(id, growing, dead);
		assertNotNull("dead, not yet cleared: the same planting", c.current(known.get(id)));
		go(id, dead, 3);
		assertNull(c.current(known.get(id)));

		// Seen after a gap with another crop in it: a new cycle; the old record does not carry over.
		String other = "allotment.falador.se";
		FarmingKnowledgeFixtures.see(known, other, growing, t);
		PatchCompost old = apply(other, "You treat the allotment with compost.");
		t += 60 * MIN;
		FarmingKnowledgeFixtures.see(known, other, raw(FarmingPatchType.ALLOTMENT, FarmingProduce.ONION, PatchCondition.GROWING, 0), t);
		assertNull(old.current(known.get(other)));
	}

	@Test
	public void inspect_confirmsAbsence_andALaterApplicationReplacesIt()
	{
		String id = "herb.catherby";
		FarmingKnowledgeFixtures.see(known, id, 3, t);
		PatchCompost none = apply(id, "This is a herb patch. The soil has not been treated. The patch is empty and weeded.");
		assertEquals("confirmed absence, not 'not seen'", PatchCompost.Kind.NONE, none.getKind());
		PatchCompost applied = apply(id, "You treat the herb patch with supercompost.");
		assertEquals(true, applied.supersedes(none));
	}

	// -- which patch a message belongs to ------------------------------------------------------------------------------

	private static FarmingCaptureAdapter.PendingCompost pending(String id, int x1, int y1, int x2, int y2)
	{
		return new FarmingCaptureAdapter.PendingCompost(id, x1, y1, x2, y2, 0, T0 + 30_000);
	}

	@Test
	public void aDelayedMessage_staysWithThePatchThePlayerStandsAt_notANewlyClickedNeighbour()
	{
		FarmingPatchCatalog c = FarmingPatchCatalog.standard();
		List<FarmingCaptureAdapter.PendingCompost> p = new ArrayList<>();
		p.add(pending("allotment.falador.nw", 3050, 3307, 3054, 3312)); // composted first: the player is here
		p.add(pending("allotment.falador.se", 3055, 3303, 3059, 3308)); // clicked next, before the first message arrived
		WorldPoint atNw = new WorldPoint(3053, 3313, 0);
		assertEquals("credited to the one the player stands beside", 0, FarmingCaptureAdapter.choose(p, atNw, "allotment", c));
		assertEquals("beside both, nearer the second: the second", 1, FarmingCaptureAdapter.choose(p, new WorldPoint(3055, 3308, 0), "allotment", c));
		assertEquals("beside both at the same distance: the earlier action", 0, FarmingCaptureAdapter.choose(p, new WorldPoint(3055, 3309, 0), "allotment", c));
		assertEquals("away from both: nobody's", -1, FarmingCaptureAdapter.choose(p, new WorldPoint(3070, 3309, 0), "allotment", c));
		assertEquals("a herb message never credits an allotment", -1, FarmingCaptureAdapter.choose(p, atNw, "herb patch", c));
	}
}
