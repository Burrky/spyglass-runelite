package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Pass 31: compost is chosen per patch type (allotments and flowers apart), saved per profile, used by preparation and
 * guidance through the run's options, changeable while a run is open for its pending work, and never an observation.
 */
public class CompostPreferencesTest
{
	static final long T0 = 1_760_000_000_000L;

	static FarmingRunIntent run(List<String> ids, FarmingRunType type, FarmingRunIntent.Options options)
	{
		Map<String, String> plans = new LinkedHashMap<>();
		for (String id : ids)
		{
			plans.put(id, FarmingRunIntent.REPLANT_ANY);
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-c", type, T0, T0, FarmingRunIntent.State.ACTIVE, null, null, new java.util.ArrayList<>(ids),
			Collections.emptyList(), Collections.emptyList(), plans, options, Collections.emptyMap());
	}

	@Test
	public void savedPerPatchType_allotmentsAndFlowersApart_andSurvivesSaving()
	{
		FarmingPreferences p = FarmingPreferences.defaults();
		assertFalse(p.isCompostChosen());
		assertEquals(FarmingPlanDraft.Compost.NONE, p.compostFor(FarmingPatchType.HERB));
		FarmingPreferences q = p.withCompost(FarmingPatchType.HERB, FarmingPlanDraft.Compost.ULTRACOMPOST)
			.withCompost(FarmingPatchType.ALLOTMENT, FarmingPlanDraft.Compost.SUPERCOMPOST)
			.withCompost(FarmingPatchType.FLOWER, FarmingPlanDraft.Compost.FERTILE_SOIL);
		assertEquals(FarmingPlanDraft.Compost.SUPERCOMPOST, q.compostFor(FarmingPatchType.ALLOTMENT));
		assertEquals(FarmingPlanDraft.Compost.FERTILE_SOIL, q.compostFor(FarmingPatchType.FLOWER));
		assertNotEquals("a change is a change (the page re-reads it)", p, q);
		FarmingPreferences back = new Gson().fromJson(new Gson().toJson(q), FarmingPreferences.class);
		assertEquals(q, back);
		assertEquals(FarmingPlanDraft.Compost.ULTRACOMPOST, back.compostFor(FarmingPatchType.HERB));
		// An older profile (no field) reads as never chosen; an unknown name reads as none.
		FarmingPreferences old = new Gson().fromJson("{\"treeClearing\":\"PAY\"}", FarmingPreferences.class);
		assertFalse(old.isCompostChosen());
		FarmingPreferences odd = new Gson().fromJson("{\"compostByPatch\":{\"HERB\":\"MAGIC\"}}", FarmingPreferences.class);
		assertEquals(FarmingPlanDraft.Compost.NONE, odd.compostFor(FarmingPatchType.HERB));
	}

	@Test
	public void theRequestCarriesTheChoices_andPreparationAndGuidanceUseThemPerPatchType()
	{
		FarmingPreferences prefs = FarmingPreferences.defaults().withCompost(FarmingPatchType.ALLOTMENT, FarmingPlanDraft.Compost.ULTRACOMPOST)
			.withCompost(FarmingPatchType.FLOWER, FarmingPlanDraft.Compost.FERTILE_SOIL);
		FarmingRunRequest r = new FarmingRunRequest(Collections.singletonList(FarmingRunType.ALLOTMENT), Collections.emptyList(), Collections.emptyList(),
			Collections.emptyMap(), new LinkedHashMap<>(), new LinkedHashMap<>(), false).withExtras(prefs);
		assertEquals("ULTRACOMPOST", r.getCompostByPatch().get("ALLOTMENT"));
		assertEquals("FERTILE_SOIL", r.getCompostByPatch().get("FLOWER"));
		assertEquals(FarmingPlanDraft.Compost.ULTRACOMPOST, r.compostFor(FarmingRunType.ALLOTMENT));

		FarmingRunIntent.Options o = new FarmingRunIntent.Options("ULTRACOMPOST", false).withCompostByPatch(r.getCompostByPatch());
		List<String> ids = Arrays.asList("allotment.farming_guild.n", "flower.farming_guild");
		FarmingRunIntent run = run(ids, FarmingRunType.ALLOTMENT, o);
		FarmingPatchCatalog cat = FarmingPatchCatalog.standard();
		assertEquals("ULTRACOMPOST", RunExtras.compostMethod(run, cat.patch("allotment.farming_guild.n")));
		assertEquals("FERTILE_SOIL", RunExtras.compostMethod(run, cat.patch("flower.farming_guild")));
		// Fertile Soil is cast on the flower patch only: one cast's runes, not two.
		List<com.osrstelemetry.plugin.prep.ItemNeed> needs = new java.util.ArrayList<>();
		List<String> notes = new java.util.ArrayList<>();
		com.osrstelemetry.plugin.account.AccountCapability.Builder lunar = com.osrstelemetry.plugin.account.AccountFixtures.spellbook(
			com.osrstelemetry.plugin.account.AccountFixtures.maxed(), com.osrstelemetry.plugin.account.AccountReaders.Spellbook.LUNAR);
		FarmingPreparation.fertileSoil(run, cat, ids, lunar.build(), needs, notes);
		int earth = 0;
		for (com.osrstelemetry.plugin.prep.ItemNeed n : needs)
		{
			earth += n.getKey().equals("rune." + net.runelite.api.gameval.ItemID.EARTHRUNE) ? n.getQuantity() : 0;
		}
		assertEquals(15, earth);
		// An older run (no per-patch choices) keeps its per-type choice for both.
		FarmingRunIntent older = run(ids, FarmingRunType.ALLOTMENT, new FarmingRunIntent.Options("SUPERCOMPOST", false));
		assertEquals("SUPERCOMPOST", RunExtras.compostMethod(older, cat.patch("flower.farming_guild")));
		assertNull(older.getOptions().getCompostByPatch());
	}

	@Test
	public void changingAChoiceDuringARun_changesOnlyThatType_andTheBountyChoiceKeepsIt()
	{
		FarmingRunIntent.Options o = new FarmingRunIntent.Options("NONE", false).withCompostByPatch(Collections.singletonMap("HERB", "COMPOST"));
		FarmingRunIntent.Options changed = o.withCompostByPatch(Collections.singletonMap("FLOWER", "ULTRACOMPOST"));
		assertEquals("COMPOST", changed.compostFor(FarmingPatchType.HERB));
		assertEquals("ULTRACOMPOST", changed.compostFor(FarmingPatchType.FLOWER));
		assertEquals("NONE", changed.compostFor(FarmingPatchType.TREE));
		assertEquals("ULTRACOMPOST", changed.withBounty(true).compostFor(FarmingPatchType.FLOWER));
	}

	@Test
	public void aChoiceIsNeverAnObservation()
	{
		// The compost the game showed lives in the snapshot's PatchCompost records; preferences are a separate object that the
		// model never reads for "Last observed".
		Map<String, PatchKnowledge> known = new java.util.HashMap<>();
		FarmingKnowledgeFixtures.see(known, "herb.falador", FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR,
			PatchCondition.GROWING, 0), T0);
		FarmingModel m = FarmingModel.build(FarmingPatchCatalog.standard(), new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE,
			FarmingOwner.standard(1L), known, Collections.emptyList()), T0 + 1000);
		FarmingModel.CompostLine before = m.compostObserved(FarmingPatchType.HERB);
		assertTrue(before.getTreated().isEmpty());
		assertEquals(1, before.getUnread());
		FarmingPreferences.defaults().withCompost(FarmingPatchType.HERB, FarmingPlanDraft.Compost.ULTRACOMPOST);
		FarmingModel again = FarmingModel.build(FarmingPatchCatalog.standard(), new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE,
			FarmingOwner.standard(1L), known, Collections.emptyList()), T0 + 1000);
		assertTrue(again.compostObserved(FarmingPatchType.HERB).getTreated().isEmpty());
	}
}
