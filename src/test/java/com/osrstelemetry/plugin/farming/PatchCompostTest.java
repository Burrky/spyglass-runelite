package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Compost from the game's own words (canonical §28.14): the messages as they read in Josh's client log (2026-10-22), the
 * planting each belongs to, before or after planting, and the cycle that ends it.
 */
public class PatchCompostTest
{
	private static final long MIN = 60_000L;
	private final Map<String, PatchKnowledge> known = new HashMap<>();

	@Test
	public void theGamesMessages_giveTheKindAndHowItWasSeen()
	{
		PatchCompost.Message a = PatchCompost.parse("You treat the herb patch with supercompost.");
		assertEquals(PatchCompost.Kind.SUPERCOMPOST, a.kind);
		assertEquals(PatchCompost.Source.APPLIED, a.source);
		assertEquals("herb patch", a.patchNoun);
		assertEquals(PatchCompost.Kind.ULTRACOMPOST, PatchCompost.parse("You treat the herb patch with ultracompost.").kind);
		assertEquals(PatchCompost.Kind.COMPOST, PatchCompost.parse("You treat the allotment with compost.").kind);
		PatchCompost.Message fs = PatchCompost.parse("The herb patch has been treated with supercompost.");
		assertEquals(PatchCompost.Source.FERTILE_SOIL, fs.source);
		assertEquals(PatchCompost.Kind.ULTRACOMPOST, PatchCompost.parse("This flower patch has already been treated with ultracompost.").kind);
		PatchCompost.Message inspect = PatchCompost.parse(
			"This is a herb patch. The soil has been treated with ultracompost. The patch has ranarr weed growing in it and is at state 1/5.");
		assertEquals(PatchCompost.Source.INSPECTED, inspect.source);
		assertEquals(PatchCompost.Kind.ULTRACOMPOST, inspect.kind);
		PatchCompost.Message none = PatchCompost.parse("This is an allotment. The soil has not been treated. The patch is empty and weeded.");
		assertEquals("Inspect can confirm the absence", PatchCompost.Kind.NONE, none.kind);
		assertEquals("allotment", none.patchNoun);
		assertNull("plant cure is not compost", PatchCompost.parse("You treat the herb patch with the plant cure."));
		assertNull(PatchCompost.parse("You haven't got any ultracompost to store."));
	}

	@Test
	public void beforePlanting_itBelongsToTheNextPlanting_andTheHarvestEndsIt()
	{
		String id = "herb.falador";
		int empty = 3;
		int seed = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.GROWING, 0);
		int ready = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
		FarmingKnowledgeFixtures.see(known, id, empty, T0);
		PatchCompost c = PatchCompost.of(PatchCompost.parse("You treat the herb patch with supercompost."), T0 + MIN, known.get(id), patch(id));
		assertNull("applied to a bare patch: the next planting's", c.getCycleFirstSeenAt());
		assertNotNull(c.current(known.get(id)));
		FarmingKnowledgeFixtures.change(known, id, empty, seed, T0 + 2 * MIN);
		assertNotNull("kept when the crop goes in", c.current(known.get(id)));
		FarmingKnowledgeFixtures.change(known, id, seed, ready, T0 + 60 * MIN);
		assertNotNull(c.current(known.get(id)));
		FarmingKnowledgeFixtures.change(known, id, ready, empty, T0 + 61 * MIN);
		assertNull("harvested out: that planting has gone", c.current(known.get(id)));
	}

	@Test
	public void afterPlanting_itBelongsToThatCrop_onlyUntilTheCycleChanges()
	{
		String id = "herb.catherby";
		int seed = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.GROWING, 0);
		int dead = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, null, PatchCondition.DEAD, -1);
		FarmingKnowledgeFixtures.see(known, id, 3, T0);
		FarmingKnowledgeFixtures.change(known, id, 3, seed, T0 + MIN);
		PatchCompost c = PatchCompost.of(PatchCompost.parse("You treat the herb patch with ultracompost."), T0 + 2 * MIN, known.get(id), patch(id));
		assertNotNull(c.getCycleFirstSeenAt());
		assertEquals(PatchCompost.Kind.ULTRACOMPOST, c.current(known.get(id)).getKind());
		FarmingKnowledgeFixtures.change(known, id, seed, dead, T0 + 30 * MIN);
		FarmingKnowledgeFixtures.change(known, id, dead, 3, T0 + 31 * MIN); // cleared
		assertNull(c.current(known.get(id)));
	}

	@Test
	public void theRunCard_showsWhatWasSeen_neverAGuess()
	{
		FarmingRunIntent intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-c", FarmingRunType.HERB, T0, T0, FarmingRunIntent.State.ACTIVE, null,
			null, java.util.Collections.singletonList("herb.falador"), java.util.Collections.emptyList(), java.util.Collections.emptyList(),
			java.util.Collections.singletonMap("herb.falador", FarmingRunIntent.REPLANT_ANY), new FarmingRunIntent.Options("SUPERCOMPOST", false),
			java.util.Collections.emptyMap());
		FarmingRunEvaluator.PatchProgress p = new FarmingRunEvaluator.PatchProgress("herb.falador", patch("herb.falador"), FarmingRunEvaluator.Outcome.PENDING,
			FarmingRunEvaluator.Detail.TO_PLANT, null, null, false).planting(true);
		assertEquals("chosen for the run and not shown yet: to do (§28.17)", FarmingRunView.Step.State.TODO, compostStep(FarmingRunView.steps(intent, p, null)).getState());
		PatchCompost seen = new PatchCompost(PatchCompost.Kind.SUPERCOMPOST, PatchCompost.Source.APPLIED, T0, null);
		FarmingRunView.Step done = compostStep(FarmingRunView.steps(intent, p, seen));
		assertEquals(FarmingRunView.Step.State.DONE, done.getState());
		assertEquals("Super", done.getLabel());
		PatchCompost untreated = new PatchCompost(PatchCompost.Kind.NONE, PatchCompost.Source.INSPECTED, T0, null);
		assertEquals("Inspect said untreated: to do", FarmingRunView.Step.State.TODO, compostStep(FarmingRunView.steps(intent, p, untreated)).getState());

		FarmingAction before = FarmingWording.patch(p, intent, FarmingPreferences.defaults(), false, false, null, 0, 0, 0, false);
		assertEquals("Apply supercompost", before.getLabel());
		FarmingAction after = FarmingWording.patch(p, intent, FarmingPreferences.defaults(), false, false, null, 0, 0, 0, true);
		assertEquals("then planting, once the game showed the compost", "Plant seeds", after.getLabel());
	}

	private static FarmingRunView.Step compostStep(List<FarmingRunView.Step> steps)
	{
		for (FarmingRunView.Step s : steps)
		{
			if (s.getLabel().toLowerCase().contains("compost") || s.getLabel().equals("Super") || s.getLabel().equals("Ultra"))
			{
				return s;
			}
		}
		throw new AssertionError(steps.toString());
	}

	@Test
	public void theMessageMustFitThePatchTheClickChose()
	{
		assertTrue(FarmingCaptureAdapter.names("herb patch", patch("herb.falador")));
		assertTrue(FarmingCaptureAdapter.names("allotment", patch("allotment.falador.nw")));
		assertTrue(FarmingCaptureAdapter.names("calquat tree patch", patch("calquat.kastori")));
		assertFalse("a herb message never credits the allotment", FarmingCaptureAdapter.names("herb patch", patch("allotment.falador.nw")));
		FarmingCaptureAdapter.PendingCompost pc = new FarmingCaptureAdapter.PendingCompost("herb.falador", 3058, 3311, 3059, 3312, 0, T0);
		assertTrue(pc.beside(new net.runelite.api.coords.WorldPoint(3060, 3311, 0)));
		assertFalse("not beside it: not credited", pc.beside(new net.runelite.api.coords.WorldPoint(3063, 3311, 0)));
	}

	@Test
	public void fertileSoil_sharesRunesWithTravel_needsNoBuckets_andNamesWhatIsMissing()
	{
		java.util.List<String> ids = java.util.Arrays.asList("herb.falador", "herb.catherby");
		java.util.Map<String, String> plans = new java.util.LinkedHashMap<>();
		for (String id : ids)
		{
			plans.put(id, FarmingRunIntent.REPLANT_ANY);
		}
		FarmingRunIntent run = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-fs", FarmingRunType.HERB, T0, T0, FarmingRunIntent.State.ACTIVE, null, null,
			new java.util.ArrayList<>(ids), java.util.Collections.emptyList(), java.util.Collections.emptyList(), plans,
			new FarmingRunIntent.Options("FERTILE_SOIL", false), java.util.Collections.emptyMap());
		// A travel spell already needs an earth rune: one shared total, from the inventory or the rune pouch.
		java.util.List<com.osrstelemetry.plugin.prep.ItemNeed> needs = new java.util.ArrayList<>();
		FarmingPreparation.addRunes(needs, net.runelite.api.gameval.ItemID.EARTHRUNE, 1, "Earth rune", "Falador Teleport");
		java.util.List<String> notes = new java.util.ArrayList<>();
		com.osrstelemetry.plugin.account.AccountCapability.Builder lunarB = com.osrstelemetry.plugin.account.AccountFixtures.spellbook(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed(), com.osrstelemetry.plugin.account.AccountReaders.Spellbook.LUNAR);
		FarmingPreparation.fertileSoil(run, FarmingPatchCatalog.standard(), ids, lunarB.varbit(net.runelite.api.gameval.VarbitID.FOSSIL_MINE_UNLOCKED_ULTRAFERTILESOIL, 1)
			.build(), needs, notes);
		int earth = 0;
		com.osrstelemetry.plugin.prep.ItemNeed ash = null;
		for (com.osrstelemetry.plugin.prep.ItemNeed n : needs)
		{
			earth += n.getKey().equals("rune." + net.runelite.api.gameval.ItemID.EARTHRUNE) ? n.getQuantity() : 0;
			ash = n.getKey().equals("supply.volcanic_ash") ? n : ash;
			assertFalse("no compost buckets as well", n.getKey().startsWith("supply.compost") || n.getKey().startsWith("supply.super"));
		}
		assertEquals("1 for travel + 15 a cast × 2", 31, earth);
		assertNotNull("the upgrade is unlocked (varbit 5960): ash is offered", ash);
		assertEquals(4, ash.getQuantity());
		assertEquals("optional: without it the spell gives supercompost", com.osrstelemetry.plugin.prep.ItemNeed.Necessity.RECOMMENDED, ash.getNecessity());
		assertTrue(notes.toString(), notes.isEmpty());

		java.util.List<com.osrstelemetry.plugin.prep.ItemNeed> noUpgrade = new java.util.ArrayList<>();
		java.util.List<String> noUpgradeNotes = new java.util.ArrayList<>();
		FarmingPreparation.fertileSoil(run, FarmingPatchCatalog.standard(), ids, com.osrstelemetry.plugin.account.AccountFixtures.spellbook(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed(), com.osrstelemetry.plugin.account.AccountReaders.Spellbook.LUNAR)
			.varbit(net.runelite.api.gameval.VarbitID.FOSSIL_MINE_UNLOCKED_ULTRAFERTILESOIL, 0).build(), noUpgrade, noUpgradeNotes);
		assertTrue("not unlocked: no ash, never implied", noUpgrade.stream().noneMatch(n -> n.getKey().equals("supply.volcanic_ash")));
		assertTrue(noUpgradeNotes.toString(), noUpgradeNotes.isEmpty());

		java.util.List<com.osrstelemetry.plugin.prep.ItemNeed> unread = new java.util.ArrayList<>();
		java.util.List<String> unreadNotes = new java.util.ArrayList<>();
		FarmingPreparation.fertileSoil(run, FarmingPatchCatalog.standard(), ids, com.osrstelemetry.plugin.account.AccountFixtures.spellbook(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed(), com.osrstelemetry.plugin.account.AccountReaders.Spellbook.LUNAR).build(), unread,
			unreadNotes);
		assertTrue("unlock not read: no ash and no claim of the upgraded effect", unread.stream().noneMatch(n -> n.getKey().equals("supply.volcanic_ash")));
		assertEquals(java.util.Collections.singletonList("Fertile Soil gives supercompost; whether this account has its ultracompost upgrade is not read yet."),
			unreadNotes);

		java.util.List<String> standardNotes = new java.util.ArrayList<>();
		com.osrstelemetry.plugin.account.AccountCapability standard = com.osrstelemetry.plugin.account.AccountFixtures.spellbook(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed(), com.osrstelemetry.plugin.account.AccountReaders.Spellbook.STANDARD)
			.varbit(net.runelite.api.gameval.VarbitID.FOSSIL_MINE_UNLOCKED_ULTRAFERTILESOIL, 0).build();
		FarmingPreparation.fertileSoil(run, FarmingPatchCatalog.standard(), ids, standard, new java.util.ArrayList<>(), standardNotes);
		assertEquals("shown, never silently switched", java.util.Collections.singletonList("Fertile Soil (2 casts) needs the Lunar spellbook."), standardNotes);
	}

	@Test
	public void theGuide_stopsAskingForCompost_onceTheGameShowedIt_whateverTheStepIsCalled()
	{
		// Live 2026-10-22 (pass 25): the step read "Super" and the guide looked for "compost" in it, so it kept asking.
		String id = "herb.falador";
		FarmingKnowledgeFixtures.see(known, id, 3, T0);
		FarmingRunIntent intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-c2", FarmingRunType.HERB, T0, T0, FarmingRunIntent.State.ACTIVE, null,
			null, java.util.Collections.singletonList(id), java.util.Collections.emptyList(), java.util.Collections.emptyList(),
			java.util.Collections.singletonMap(id, FarmingRunIntent.REPLANT_ANY), new FarmingRunIntent.Options("SUPERCOMPOST", false),
			java.util.Collections.singletonMap(id, FarmingRunIntent.Baseline.of(patch(id), known.get(id))));
		for (PatchCompost.Kind kind : new PatchCompost.Kind[]{PatchCompost.Kind.COMPOST, PatchCompost.Kind.SUPERCOMPOST, PatchCompost.Kind.ULTRACOMPOST})
		{
			FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
				new java.util.LinkedHashMap<>(known), java.util.Collections.emptyList(), intent, java.util.Collections.singleton(id))
				.withCompost(java.util.Collections.singletonMap(id, new PatchCompost(kind, PatchCompost.Source.APPLIED, T0 + MIN, null)));
			FarmingRunView.PatchLine line = FarmingModel.build(FarmingPatchCatalog.standard(), snap, T0 + 2 * MIN).getRun().getLines().get(0);
			assertTrue(kind.name(), FarmingGuide.compostSeen(line));
		}
		FarmingSnapshot none = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
			new java.util.LinkedHashMap<>(known), java.util.Collections.emptyList(), intent, java.util.Collections.singleton(id))
			.withCompost(java.util.Collections.singletonMap(id, new PatchCompost(PatchCompost.Kind.NONE, PatchCompost.Source.INSPECTED, T0 + MIN, null)));
		assertFalse("Inspect said untreated: still to do", FarmingGuide.compostSeen(
			FarmingModel.build(FarmingPatchCatalog.standard(), none, T0 + 2 * MIN).getRun().getLines().get(0)));
	}

	@Test
	public void plantedBeforeTheCompost_theGuideAsksForTheCompost_atThatPatch_beforeMovingOn()
	{
		String id = "herb.falador";
		int ready = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
		int seed = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.GROWING, 0);
		FarmingKnowledgeFixtures.see(known, id, ready, T0 - MIN);
		FarmingKnowledgeFixtures.see(known, "herb.catherby", ready, T0 - MIN);
		java.util.Map<String, String> plans = new java.util.LinkedHashMap<>();
		plans.put(id, FarmingRunIntent.REPLANT_ANY);
		plans.put("herb.catherby", FarmingRunIntent.REPLANT_ANY);
		java.util.Map<String, FarmingRunIntent.Baseline> base = new java.util.LinkedHashMap<>();
		base.put(id, FarmingRunIntent.Baseline.of(patch(id), known.get(id)));
		base.put("herb.catherby", FarmingRunIntent.Baseline.of(patch("herb.catherby"), known.get("herb.catherby")));
		FarmingRunIntent intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-c3", FarmingRunType.HERB, T0, T0, FarmingRunIntent.State.ACTIVE, null,
			null, new java.util.ArrayList<>(plans.keySet()), java.util.Collections.emptyList(), java.util.Collections.emptyList(), plans,
			new FarmingRunIntent.Options("ULTRACOMPOST", false), base).withExtras(java.util.Collections.emptyList(), null, java.util.Collections.emptyList());
		FarmingKnowledgeFixtures.change(known, id, ready, 3, T0 + MIN);
		FarmingKnowledgeFixtures.change(known, id, 3, seed, T0 + 2 * MIN);
		com.osrstelemetry.plugin.account.AccountCapability cap = com.osrstelemetry.plugin.account.AccountFixtures.maxed().build();
		java.util.List<FarmingPreparation.SitePlan> sites = FarmingPreparation.plan(intent.withPreparing(true), FarmingPatchCatalog.standard(), cap,
			com.osrstelemetry.plugin.travel.TransportEvaluator.evaluateAll(com.osrstelemetry.plugin.travel.TestTransports.CATALOG, cap,
				com.osrstelemetry.plugin.account.EvidenceSource.NONE, T0), com.osrstelemetry.plugin.account.EvidenceSource.NONE).getSites();
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
			new java.util.LinkedHashMap<>(known), java.util.Collections.emptyList(), FarmingRunEvaluator.withEvidence(intent, FarmingPatchCatalog.standard(), known),
			java.util.Collections.singleton(id));
		FarmingRunView run = FarmingModel.build(FarmingPatchCatalog.standard(), snap, T0 + 3 * MIN).getRun();
		FarmingGuide.Position at = new FarmingGuide.Position(3056, 3308, 0);
		FarmingGuide.Objective o = FarmingGuide.resolve(run, sites, at, at, "Falador", FarmingPreferences.defaults(), FarmingPatchCatalog.standard(), null,
			"Falador");
		assertEquals(FarmingGuide.Kind.ACT, o.getKind());
		assertEquals("Apply ultracompost", o.getAction().getLabel());
		assertEquals("the planted patch", FarmingAction.Target.Kind.PATCH, o.getAction().getTarget().kind);
	}
}
