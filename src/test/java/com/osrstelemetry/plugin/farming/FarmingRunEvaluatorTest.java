package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingRunEvaluator.Detail;
import com.osrstelemetry.plugin.farming.FarmingRunEvaluator.Outcome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Patch completion (canonical §17) from baseline + current native knowledge only. */
public class FarmingRunEvaluatorTest
{
	private static final long MIN = 60_000L;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();

	private final Map<String, PatchKnowledge> known = new HashMap<>();

	private static int herb(FarmingProduce p, PatchCondition c, int stage)
	{
		return FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, p, c, stage);
	}

	private static final int EMPTY = 3;
	private static final int RANARR_READY = herb(FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
	private static final int RANARR_SEED = herb(FarmingProduce.RANARR, PatchCondition.GROWING, 0);
	private static final int SNAP_SEED = herb(FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 0);
	private static final int SNAP_1 = herb(FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 1);
	private static final int SNAP_2 = herb(FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 2);
	private static final int SNAP_DISEASED = herb(FarmingProduce.SNAPDRAGON, PatchCondition.DISEASED, -1);
	private static final int HERB_DEAD = herb(null, PatchCondition.DEAD, -1);

	private void see(String id, int raw, long at)
	{
		FarmingKnowledgeFixtures.see(known, id, raw, at);
	}

	private void change(String id, int from, int raw, long at)
	{
		FarmingKnowledgeFixtures.change(known, id, from, raw, at);
	}

	private void unchanged(String id, long at)
	{
		known.put(id, known.get(id).seenUnchanged(at));
	}

	/** An intent started at {@code start} with a baseline copied from current knowledge (what Start does). */
	private FarmingRunIntent intent(FarmingRunType type, long start, Map<String, FarmingProduce> planned)
	{
		Map<String, FarmingRunIntent.Baseline> baseline = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		for (Map.Entry<String, FarmingProduce> e : planned.entrySet())
		{
			baseline.put(e.getKey(), FarmingRunIntent.Baseline.of(patch(e.getKey()), known.get(e.getKey())));
			names.put(e.getKey(), e.getValue() == null ? FarmingRunIntent.HARVEST_ONLY : e.getValue().name());
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, FarmingRunIntent.newId(type, start), type, start, start, FarmingRunIntent.State.ACTIVE,
			null, null, new ArrayList<>(planned.keySet()), Collections.emptyList(), Collections.emptyList(), names,
			new FarmingRunIntent.Options("NONE", false), baseline);
	}

	private static Map<String, FarmingProduce> plan(Object... pairs)
	{
		Map<String, FarmingProduce> m = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			m.put((String) pairs[i], (FarmingProduce) pairs[i + 1]);
		}
		return m;
	}

	/** Crop sightings the tracker would have recorded at each check (sticky, like the live tracker). */
	private final Map<String, List<String>> evidence = new HashMap<>();

	private FarmingRunIntent tracked(FarmingRunIntent intent)
	{
		FarmingRunIntent t = FarmingRunEvaluator.withEvidence(intent.withCropSeen(evidence.getOrDefault(intent.getIntentId(), Collections.emptyList())),
			CATALOG, known);
		evidence.put(intent.getIntentId(), t.getCropSeenSinceStart());
		return t;
	}

	private FarmingRunEvaluator.Progress progress(FarmingRunIntent intent)
	{
		return FarmingRunEvaluator.evaluate(tracked(intent), CATALOG, known);
	}

	private FarmingRunEvaluator.PatchProgress eval(FarmingRunIntent intent, String id)
	{
		return progress(intent).patch(id);
	}

	private void expect(FarmingRunIntent intent, String id, Outcome outcome, Detail detail)
	{
		FarmingRunEvaluator.PatchProgress p = eval(intent, id);
		assertEquals(id + " outcome", outcome, p.getOutcome());
		assertEquals(id + " detail", detail, p.getDetail());
	}

	@Test
	public void aCropPlantedBeforeStart_isNeverAFreshPlanting()
	{
		change("herb.falador", EMPTY, SNAP_SEED, T0 - 30 * MIN); // planted (observed) before Start
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.SNAPDRAGON));
		expect(run, "herb.falador", Outcome.PENDING, Detail.NOT_VISITED);
		unchanged("herb.falador", T0 + 5 * MIN); // seen again after Start, unchanged
		expect(run, "herb.falador", Outcome.NOT_APPLICABLE, Detail.STILL_GROWING);
		change("herb.falador", SNAP_SEED, SNAP_1, T0 + 10 * MIN); // it grows a stage: still the old planting
		expect(run, "herb.falador", Outcome.NOT_APPLICABLE, Detail.STILL_GROWING);
	}

	@Test
	public void harvestClearAndReplant_areSeparateObservedSteps_andPartialHarvestNeverFinishes()
	{
		see("herb.falador", RANARR_READY, T0 - 60 * MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.RANARR));
		expect(run, "herb.falador", Outcome.PENDING, Detail.NOT_VISITED);
		unchanged("herb.falador", T0 + MIN);
		expect(run, "herb.falador", Outcome.PENDING, Detail.TO_HARVEST);
		change("herb.falador", RANARR_READY, RANARR_READY + 1, T0 + 2 * MIN); // one pick: fewer left, still harvestable
		expect(run, "herb.falador", Outcome.PENDING, Detail.TO_HARVEST);
		change("herb.falador", RANARR_READY + 1, EMPTY, T0 + 3 * MIN); // harvested out
		expect(run, "herb.falador", Outcome.IN_PROGRESS, Detail.AWAITING_PLANT);
		change("herb.falador", EMPTY, RANARR_SEED, T0 + 4 * MIN);
		FarmingRunEvaluator.PatchProgress done = eval(run, "herb.falador");
		assertEquals(Outcome.DONE, done.getOutcome());
		assertEquals(Detail.REPLANTED, done.getDetail());
		assertEquals(FarmingProduce.RANARR, done.getObservedCrop());
		assertFalse(done.isDifferentFromPlan());
	}

	@Test
	public void replantingSomethingElse_isRecordedAsSeen_andStillDone()
	{
		see("herb.falador", EMPTY, T0 - MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.RANARR));
		unchanged("herb.falador", T0 + MIN);
		expect(run, "herb.falador", Outcome.PENDING, Detail.TO_PLANT);
		change("herb.falador", EMPTY, SNAP_SEED, T0 + 2 * MIN);
		FarmingRunEvaluator.PatchProgress p = eval(run, "herb.falador");
		assertEquals(Detail.REPLANTED, p.getDetail());
		assertEquals("the crop actually observed, not the plan", FarmingProduce.SNAPDRAGON, p.getObservedCrop());
		assertEquals(FarmingProduce.RANARR, p.getPlanned());
		assertTrue(p.isDifferentFromPlan());
	}

	@Test
	public void harvestOnly_finishesWhenHarvestedOrCleared_andAnEmptyPatchHasNothingToDo()
	{
		see("herb.falador", RANARR_READY, T0 - MIN);
		see("herb.catherby", HERB_DEAD, T0 - MIN);
		see("herb.ardougne", EMPTY, T0 - MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", null, "herb.catherby", null, "herb.ardougne", null));
		change("herb.falador", RANARR_READY, EMPTY, T0 + MIN);
		expect(run, "herb.falador", Outcome.DONE, Detail.HARVESTED);
		unchanged("herb.catherby", T0 + 2 * MIN);
		expect(run, "herb.catherby", Outcome.PENDING, Detail.TO_CLEAR);
		change("herb.catherby", HERB_DEAD, EMPTY, T0 + 3 * MIN);
		expect(run, "herb.catherby", Outcome.DONE, Detail.CLEARED);
		unchanged("herb.ardougne", T0 + 4 * MIN);
		expect(run, "herb.ardougne", Outcome.NOT_APPLICABLE, Detail.NOTHING_TO_DO);
	}

	@Test
	public void missingOrUnreadableEvidence_neverCompletes()
	{
		see("herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.RANARR, "herb.catherby", FarmingProduce.RANARR));
		expect(run, "herb.catherby", Outcome.PENDING, Detail.NOT_VISITED); // NOT TRACKED: included, just not visited
		change("herb.falador", RANARR_READY, 220, T0 + MIN); // undecodable
		expect(run, "herb.falador", Outcome.PENDING, Detail.UNREADABLE);
		assertFalse(progress(run).isAllSettled());
	}

	@Test
	public void workBeforeStart_doesNotCount()
	{
		see("herb.falador", RANARR_READY, T0 - 20 * MIN);
		change("herb.falador", RANARR_READY, EMPTY, T0 - 15 * MIN);
		change("herb.falador", EMPTY, RANARR_SEED, T0 - 14 * MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.RANARR));
		expect(run, "herb.falador", Outcome.PENDING, Detail.NOT_VISITED);
		unchanged("herb.falador", T0 + MIN);
		expect(run, "herb.falador", Outcome.NOT_APPLICABLE, Detail.STILL_GROWING);
	}

	@Test
	public void aNewCropFirstSeenAfterStart_countsOnlyWhenItProvablyPostdatesStart()
	{
		see("herb.falador", RANARR_READY, T0 - 5 * MIN);
		see("herb.catherby", RANARR_READY, T0 - 5 * MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.SNAPDRAGON, "herb.catherby",
			FarmingProduce.SNAPDRAGON));
		// Catherby: next seen only 10 min after Start, already at stage 2: it may have been planted before Start.
		see("herb.catherby", SNAP_2, T0 + 10 * MIN);
		expect(run, "herb.catherby", Outcome.NOT_APPLICABLE, Detail.ALREADY_GROWING);
		// Falador: seen at stage 2 seventy minutes after Start: planted no earlier than T0+10m, so after Start.
		see("herb.falador", SNAP_2, T0 + 70 * MIN);
		expect(run, "herb.falador", Outcome.DONE, Detail.PLANTED_UNSEEN);
	}

	@Test
	public void aDiseasedCropCuredAfterStart_isDone()
	{
		see("herb.falador", SNAP_DISEASED, T0 - MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.SNAPDRAGON));
		unchanged("herb.falador", T0 + MIN);
		expect(run, "herb.falador", Outcome.PENDING, Detail.TO_CURE);
		change("herb.falador", SNAP_DISEASED, SNAP_2, T0 + 2 * MIN);
		expect(run, "herb.falador", Outcome.DONE, Detail.CURED);
	}

	@Test
	public void skip_isDistinctFromDone_andAnObservedCompletionOutranksIt()
	{
		see("herb.falador", RANARR_READY, T0 - MIN);
		see("herb.catherby", RANARR_READY, T0 - MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", null, "herb.catherby", null))
			.withSkipped("herb.falador", true).withSkipped("herb.catherby", true);
		FarmingRunEvaluator.Progress p = progress(run);
		assertEquals(Outcome.SKIPPED, p.patch("herb.falador").getOutcome());
		assertEquals("skipped is never counted as done", 0, p.getDone());
		assertTrue(p.isAllSettled());
		change("herb.catherby", RANARR_READY, EMPTY, T0 + MIN);
		assertEquals(Outcome.DONE, eval(run, "herb.catherby").getOutcome());
	}

	@Test
	public void trees_finishThroughTheirHealthCheck_unlessAReplacementIsPlanned()
	{
		int yewCheck = 45;
		int yewStanding = 46;
		see("tree.falador", yewCheck, T0 - MIN);
		see("tree.taverley", yewCheck, T0 - MIN);
		// Correction pass 2: harvest only (leave the tree) finishes at the health check; a replant needs the old tree out.
		FarmingRunIntent run = intent(FarmingRunType.TREE, T0, plan("tree.falador", null, "tree.taverley", FarmingProduce.MAGIC));
		unchanged("tree.falador", T0 + MIN);
		expect(run, "tree.falador", Outcome.PENDING, Detail.TO_CHECK_HEALTH);
		change("tree.falador", yewCheck, yewStanding, T0 + 2 * MIN);
		expect(run, "tree.falador", Outcome.DONE, Detail.HEALTH_CHECKED);
		change("tree.taverley", yewCheck, yewStanding, T0 + 3 * MIN);
		expect(run, "tree.taverley", Outcome.IN_PROGRESS, Detail.TO_REPLACE);
		assertTrue("the health check is recorded on the way", eval(run, "tree.taverley").isHealthChecked());
		int yewStump = 47;
		change("tree.taverley", yewStanding, yewStump, T0 + 4 * MIN);
		expect(run, "tree.taverley", Outcome.IN_PROGRESS, Detail.STUMP_TO_CLEAR); // chopping alone never clears the patch
		change("tree.taverley", yewStump, EMPTY, T0 + 5 * MIN);
		expect(run, "tree.taverley", Outcome.IN_PROGRESS, Detail.AWAITING_PLANT);
		int magicSeed = FarmingKnowledgeFixtures.raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.GROWING, 0);
		change("tree.taverley", EMPTY, magicSeed, T0 + 6 * MIN);
		expect(run, "tree.taverley", Outcome.DONE, Detail.REPLANTED);
	}

	@Test
	public void aStandingTree_withReplantPlanned_isNeverNothingToDo_andPaidRemovalNeedsNoStump()
	{
		// The live Falador report: "Nothing to do" next to "Now: READY" while the old tree still had to come out.
		int yewStanding = 46;
		see("tree.falador", yewStanding, T0 - MIN);
		Map<String, FarmingRunIntent.Baseline> baseline = new LinkedHashMap<>();
		baseline.put("tree.falador", FarmingRunIntent.Baseline.of(patch("tree.falador"), known.get("tree.falador")));
		Map<String, String> plans = new LinkedHashMap<>();
		plans.put("tree.falador", FarmingRunIntent.REPLANT_ANY);
		FarmingRunIntent run = new FarmingRunIntent(FarmingRunIntent.SCHEMA, FarmingRunIntent.newId(FarmingRunType.TREE, T0), FarmingRunType.TREE, T0, T0,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options("NONE", false), baseline);
		unchanged("tree.falador", T0 + MIN);
		expect(run, "tree.falador", Outcome.PENDING, Detail.TO_REPLACE);
		change("tree.falador", yewStanding, EMPTY, T0 + 2 * MIN); // the gardener removed it: no stump step needed
		expect(run, "tree.falador", Outcome.IN_PROGRESS, Detail.AWAITING_PLANT);
	}

	@Test
	public void fruit_partialPickingNeverFinishes_pickingItBareDoes()
	{
		int full = FarmingKnowledgeFixtures.raw(FarmingPatchType.FRUIT_TREE, FarmingProduce.APPLE, PatchCondition.HARVESTABLE, 6);
		int some = FarmingKnowledgeFixtures.raw(FarmingPatchType.FRUIT_TREE, FarmingProduce.APPLE, PatchCondition.HARVESTABLE, 3);
		int none = FarmingKnowledgeFixtures.raw(FarmingPatchType.FRUIT_TREE, FarmingProduce.APPLE, PatchCondition.HARVESTABLE, 0);
		see("fruit_tree.catherby", full, T0 - MIN);
		// Harvest only: partial picking never finishes; picking it bare does.
		FarmingRunIntent run = intent(FarmingRunType.FRUIT, T0, plan("fruit_tree.catherby", null));
		change("fruit_tree.catherby", full, some, T0 + MIN);
		expect(run, "fruit_tree.catherby", Outcome.IN_PROGRESS, Detail.TO_HARVEST);
		assertEquals(3, eval(run, "fruit_tree.catherby").getFruitLeft());
		change("fruit_tree.catherby", some, none, T0 + 2 * MIN);
		expect(run, "fruit_tree.catherby", Outcome.DONE, Detail.HARVESTED);
		// Replant: picked bare, the tree still has to come out.
		FarmingRunIntent replant = intent(FarmingRunType.FRUIT, T0, plan("fruit_tree.catherby", FarmingProduce.APPLE));
		expect(replant, "fruit_tree.catherby", Outcome.PENDING, Detail.TO_REPLACE);
	}

	@Test
	public void outOfOrderWork_givesTheSameResult_andCompletesTheRun()
	{
		List<String> ids = Arrays.asList("herb.falador", "herb.catherby", "herb.ardougne");
		for (String id : ids)
		{
			see(id, RANARR_READY, T0 - MIN);
		}
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.RANARR, "herb.catherby", FarmingProduce.RANARR,
			"herb.ardougne", FarmingProduce.RANARR));
		long t = T0;
		List<String> reversed = new ArrayList<>(ids);
		Collections.reverse(reversed);
		for (String id : reversed) // the player's own order, not the list's
		{
			change(id, RANARR_READY, EMPTY, t += MIN);
			assertFalse(progress(run).isAllSettled());
			change(id, EMPTY, RANARR_SEED, t += MIN);
		}
		FarmingRunEvaluator.Progress p = progress(run);
		assertTrue(p.isAllSettled());
		assertEquals(3, p.count(Detail.REPLANTED));
		assertEquals("remaining is empty", 0, p.remaining(Collections.emptySet()).size());
	}

	@Test
	public void remaining_putsThePatchesHereFirst_otherwiseTheIntentOrder()
	{
		for (String id : Arrays.asList("herb.falador", "herb.catherby", "herb.ardougne"))
		{
			see(id, RANARR_READY, T0 - MIN);
		}
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", null, "herb.catherby", null, "herb.ardougne", null));
		FarmingRunEvaluator.Progress p = progress(run);
		assertEquals("herb.falador", p.remaining(Collections.emptySet()).get(0).getPatchId());
		assertEquals("herb.ardougne", p.remaining(Collections.singleton("herb.ardougne")).get(0).getPatchId());
	}

	@Test
	public void protection_isRecordedOnlyFromTheGamesMessage_forThePlantedPatchAtThisStop_once()
	{
		// Event sequence: harvest, replant, then the gardener's "You pay the gardener ... to protect the patch." message.
		see("herb.falador", RANARR_READY, T0 - 60 * MIN);
		see("herb.catherby", RANARR_READY, T0 - 60 * MIN);
		FarmingRunIntent run = intent(FarmingRunType.HERB, T0, plan("herb.falador", FarmingProduce.RANARR, "herb.catherby", FarmingProduce.RANARR));
		java.util.Set<String> here = new java.util.HashSet<>(Collections.singletonList("herb.falador"));
		unchanged("herb.falador", T0 + MIN);
		assertEquals("nothing planted yet: a payment message names no patch", null,
			FarmingCaptureAdapter.protectionCandidate(tracked(run), CATALOG, known, Collections.emptyList(), here));
		change("herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		change("herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		FarmingRunIntent planted = tracked(run);
		assertEquals("herb.falador", FarmingCaptureAdapter.protectionCandidate(planted, CATALOG, known, Collections.emptyList(), here));
		assertEquals("not a patch elsewhere", null, FarmingCaptureAdapter.protectionCandidate(planted, CATALOG, known, Collections.emptyList(),
			new java.util.HashSet<>(Collections.singletonList("herb.catherby"))));
		FarmingRunIntent paid = planted.withProtected("herb.falador");
		assertTrue(paid.getProtectedPatchIds().contains("herb.falador"));
		assertEquals("paid once: a second message has nothing left here", null,
			FarmingCaptureAdapter.protectionCandidate(paid, CATALOG, known, Collections.emptyList(), here));
	}

	@Test
	public void treeLifecycle_checkHealth_removal_replant_protection_stayCurrentUntilAllEnabledWorkIsSeen()
	{
		// Live finding 2026-10-22 (Gnome Stronghold tree): after the health check, guidance moved on although removal,
		// replanting and protection were still to do.
		String id = "tree.gnome_stronghold";
		see(id, 60, T0 - 60 * MIN); // magic tree, check health
		Map<String, FarmingProduce> planned = new LinkedHashMap<>();
		planned.put(id, null);
		FarmingRunIntent run = intent(FarmingRunType.TREE, T0, planned);
		Map<String, String> names = new LinkedHashMap<>();
		names.put(id, FarmingRunIntent.REPLANT_ANY);
		run = new FarmingRunIntent(FarmingRunIntent.SCHEMA, run.getIntentId(), FarmingRunType.TREE, T0, T0, FarmingRunIntent.State.ACTIVE, null, null,
			run.getIncludedPatchIds(), Collections.emptyList(), Collections.emptyList(), names, new FarmingRunIntent.Options("NONE", true),
			Collections.singletonMap(id, run.baseline(id)));
		unchanged(id, T0 + MIN);
		expect(run, id, Outcome.PENDING, Detail.TO_CHECK_HEALTH);
		change(id, 60, 61, T0 + 2 * MIN); // checked: standing
		FarmingRunEvaluator.PatchProgress p = eval(run, id);
		assertFalse("a health check alone never finishes a patch that is replanted: " + p.getDetail(), p.getOutcome().isSettled());
		assertEquals(Detail.TO_REPLACE, p.getDetail());
		change(id, 61, EMPTY, T0 + 3 * MIN); // the gardener removed it
		expect(run, id, Outcome.IN_PROGRESS, Detail.AWAITING_PLANT);
		int sapling = FarmingKnowledgeFixtures.raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.GROWING, 0);
		change(id, EMPTY, sapling, T0 + 4 * MIN);
		FarmingRunEvaluator.PatchProgress planted = eval(run, id);
		assertTrue(planted.getDetail() == Detail.REPLANTED || planted.getDetail() == Detail.PLANTED_UNSEEN);
		FarmingPreferences prefs = FarmingPreferences.defaults().withProtect(FarmingRunType.TREE, true);
		assertTrue("protection is still to pay", FarmingWording.protectionPending(planted, tracked(run), prefs));
		assertFalse(FarmingWording.protectionPending(planted, tracked(run).withProtected(id), prefs));
	}

	@Test
	public void regrownBerries_pickedThisRun_areHarvested_evenWhenTheBaselineIsDaysOld()
	{
		// Live 2026-10-22 (Champions' Guild bush): the baseline (picked clean days earlier) said 0 left; the bush had regrown
		// to 4; four were picked during the run; the run said "nothing to do".
		String id = "bush.champions_guild";
		int none = FarmingKnowledgeFixtures.raw(FarmingPatchType.BUSH, FarmingProduce.WHITEBERRIES, PatchCondition.HARVESTABLE, 0);
		int four = FarmingKnowledgeFixtures.raw(FarmingPatchType.BUSH, FarmingProduce.WHITEBERRIES, PatchCondition.HARVESTABLE, 4);
		int three = FarmingKnowledgeFixtures.raw(FarmingPatchType.BUSH, FarmingProduce.WHITEBERRIES, PatchCondition.HARVESTABLE, 3);
		see(id, none, T0 - 4 * 24 * 60 * MIN);
		FarmingRunIntent run = intent(FarmingRunType.BUSH, T0, plan(id, null));
		see(id, four, T0 + MIN); // the first sight this run: regrown
		run = FarmingRunEvaluator.withEvidence(run, CATALOG, known); // as the run store does every tick
		assertEquals(Detail.TO_HARVEST, FarmingRunEvaluator.evaluate(run, CATALOG, known).patch(id).getDetail());
		change(id, four, three, T0 + 2 * MIN);
		run = FarmingRunEvaluator.withEvidence(run, CATALOG, known);
		change(id, three, none, T0 + 3 * MIN);
		run = FarmingRunEvaluator.withEvidence(run, CATALOG, known);
		FarmingRunEvaluator.PatchProgress p = FarmingRunEvaluator.evaluate(run, CATALOG, known).patch(id);
		assertEquals(Outcome.DONE, p.getOutcome());
		assertEquals(Detail.HARVESTED, p.getDetail());
		FarmingRunIntent saved = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(run), FarmingRunIntent.class);
		assertEquals("kept with the run (saved and loaded)", 4, saved.getStagePeak(id));
	}
}
