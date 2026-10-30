package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.base;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.farming.FarmingRunEvaluator.Detail;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.NeedResult;
import com.osrstelemetry.plugin.prep.NeedStatus;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * Regression tests for the Farming correction pass after the first live test
 * (2026-10-16): observed READY versus already growing, actual versus planned
 * crops and generic herb seeds, compost uncertainty, unlock filtering and an
 * incomplete login, equipment variants and the amulet of bounty, the tool
 * leprechaun, and the Farming contract.
 */
public class FarmingCorrectionPassTest
{
	private static final long MIN = 60_000L;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final int EMPTY = 3;

	private static int herb(FarmingProduce p, PatchCondition c, int stage)
	{
		return FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, p, c, stage);
	}

	private static final int RANARR_READY = herb(FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
	private static final int RANARR_2 = herb(FarmingProduce.RANARR, PatchCondition.GROWING, 2);
	private static final int RANARR_3 = herb(FarmingProduce.RANARR, PatchCondition.GROWING, 3);
	private static final int SNAP_SEED = herb(FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 0);

	private final Map<String, PatchKnowledge> known = new HashMap<>();
	private final Map<String, List<String>> evidence = new HashMap<>();

	private FarmingRunIntent run(long start, String compost, Map<String, String> plans)
	{
		Map<String, FarmingRunIntent.Baseline> baseline = new LinkedHashMap<>();
		for (String id : plans.keySet())
		{
			baseline.put(id, FarmingRunIntent.Baseline.of(patch(id), known.get(id)));
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, FarmingRunIntent.newId(FarmingRunType.HERB, start), FarmingRunType.HERB, start, start,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options(compost, false), baseline);
	}

	private FarmingRunEvaluator.PatchProgress eval(FarmingRunIntent intent, String id)
	{
		FarmingRunIntent t = FarmingRunEvaluator.withEvidence(intent.withCropSeen(evidence.getOrDefault(intent.getIntentId(), Collections.emptyList())),
			CATALOG, known);
		evidence.put(intent.getIntentId(), t.getCropSeenSinceStart());
		return FarmingRunEvaluator.evaluate(t, CATALOG, known).patch(id);
	}

	private static Map<String, String> one(String id, String plan)
	{
		Map<String, String> m = new LinkedHashMap<>();
		m.put(id, plan);
		return m;
	}

	private static String steps(FarmingRunIntent intent, FarmingRunEvaluator.PatchProgress p)
	{
		List<String> out = new ArrayList<>();
		for (FarmingRunView.Step s : FarmingRunView.steps(intent, p))
		{
			out.add(s.getText());
		}
		return String.join(" ", out);
	}

	// -- 1. observed READY is ready, never "already growing" ------------------------------------

	@Test
	public void aPatchSeenReadyDuringTheRun_isReady_andHarvestThenReplantUpdateProgress()
	{
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_2, T0 - 80 * MIN); // planted before the run
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_2, RANARR_3, T0 - 20 * MIN);
		FarmingRunIntent intent = run(T0, "NONE", one("herb.falador", FarmingRunIntent.REPLANT_ANY));
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_3, RANARR_READY, T0 + 2 * MIN); // the player arrives: READY
		FarmingRunEvaluator.PatchProgress ready = eval(intent, "herb.falador");
		assertEquals("seen ready: to harvest, never 'already growing'", Detail.TO_HARVEST, ready.getDetail());
		assertEquals("one next action; the step marks carry the rest", "Harvest", FarmingRunView.runText(ready));

		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 3 * MIN);
		FarmingRunEvaluator.PatchProgress harvested = eval(intent, "herb.falador");
		assertEquals(Detail.AWAITING_PLANT, harvested.getDetail());
		assertEquals("no crop is invented for the player", "Plant", FarmingRunView.runText(harvested));
		assertEquals("✓ Harvest ○ Plant", steps(intent, harvested));

		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, SNAP_SEED, T0 + 4 * MIN);
		FarmingRunEvaluator.PatchProgress planted = eval(intent, "herb.falador");
		assertEquals(FarmingRunEvaluator.Outcome.DONE, planted.getOutcome());
		assertEquals("the crop actually planted is recorded", FarmingProduce.SNAPDRAGON, planted.getObservedCrop());
		assertEquals("✓ Planted · Snapdragon", FarmingRunView.runText(planted));
		assertFalse(FarmingRunView.runText(planted).contains("planned"));
	}

	@Test
	public void aPatchStillGrowing_isAlreadyGrowing_onlyWhileItIsGrowing()
	{
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_2, T0 - 30 * MIN);
		FarmingRunIntent intent = run(T0, "NONE", one("herb.falador", FarmingRunIntent.REPLANT_ANY));
		known.put("herb.falador", known.get("herb.falador").seenUnchanged(T0 + MIN));
		FarmingRunEvaluator.PatchProgress p = eval(intent, "herb.falador");
		assertTrue(p.getDetail().toString(), p.getDetail() == Detail.STILL_GROWING || p.getDetail() == Detail.ALREADY_GROWING);
		assertTrue("growing patches show no steps", FarmingRunView.steps(intent, p).isEmpty());
	}

	// -- 2. compost is never proven; empty patches have nothing to harvest ---------------------------

	@Test
	public void compostStaysUnknown_evenAfterPlanting_andAnEmptyPatchIsNeverHarvested()
	{
		FarmingKnowledgeFixtures.see(known, "herb.catherby", EMPTY, T0 - MIN);
		FarmingRunIntent intent = run(T0, "ULTRACOMPOST", one("herb.catherby", "RANARR"));
		FarmingKnowledgeFixtures.see(known, "herb.catherby", EMPTY, T0 + MIN);
		FarmingRunEvaluator.PatchProgress toPlant = eval(intent, "herb.catherby");
		assertEquals("the step names the chosen compost", "– Harvest ○ Ultra ○ Plant", steps(intent, toPlant));
		FarmingKnowledgeFixtures.change(known, "herb.catherby", EMPTY, herb(FarmingProduce.RANARR, PatchCondition.GROWING, 0), T0 + 2 * MIN);
		FarmingRunEvaluator.PatchProgress planted = eval(intent, "herb.catherby");
		assertEquals("planting never proves compost: still to do, after the plant (§28.17); there was nothing to harvest", "– Harvest ✓ Plant ○ Ultra",
			steps(intent, planted));
	}

	// -- 3. generic herb seeds and suitability -------------------------------------------------------

	@Test
	public void anyHerbSeed_isOneNeed_acceptingEveryHerbTheLevelAllows()
	{
		FarmingRunIntent intent = run(NOW, "NONE", herbs(FarmingRunIntent.REPLANT_ANY, "herb.falador", "herb.catherby"));
		AccountCapability low = base(40).build();
		FarmingPreparation.Plan p = plan(intent, low);
		ItemNeed any = need(p, "seed.herb.any");
		assertNotNull(any);
		assertEquals(2, any.getQuantity());
		assertEquals("Herb seeds", any.getLabel());
		assertTrue("level 40 can plant Ranarr (32)", any.accepts(ItemID.RANARR_SEED));
		assertFalse("…not Snapdragon (62)", any.accepts(ItemID.SNAPDRAGON_SEED));
		assertFalse("…not Torstol (85)", any.accepts(ItemID.TORSTOL_SEED));
		assertNull("no specific herb is ever chosen for the player", need(p, "seed." + ItemID.SNAPDRAGON_SEED));
		AccountCapability carrying = base(40).inventory(items(ItemID.GUAM_SEED, 1, ItemID.TARROMIN_SEED, 1)).build();
		PreparationState state = PreparationEvaluator.evaluate(plan(intent, carrying).getRequest(), carrying, NOW);
		assertEquals("mixed herb seeds cover the count together", NeedStatus.CARRIED, result(state, "seed.herb.any").getStatus());
	}

	@Test
	public void theDraft_neverPicksACropFromWhatWasSeen_andKeepsChoicesApart()
	{
		FarmingPlanDraft draft = new FarmingPlanDraft();
		assertEquals("Replant", FarmingPlanDraft.label(FarmingRunIntent.REPLANT_ANY, FarmingPatchType.HERB));
		assertEquals("Replant", FarmingPlanDraft.label(FarmingRunIntent.REPLANT_ANY, FarmingPatchType.ALLOTMENT));
		assertEquals("Harvest only", FarmingPlanDraft.label(FarmingRunIntent.HARVEST_ONLY, FarmingPatchType.HERB));
		assertEquals("Snapdragon", FarmingPlanDraft.label("SNAPDRAGON", FarmingPatchType.HERB));
		draft.setTypePlan(FarmingRunType.HERB, "POTATO"); // a crop that can't go in a herb patch is never applied
		draft.setUseBounty(true);
		draft.clear();
		assertNull(draft.getTypePlan(FarmingRunType.HERB));
		assertFalse("an account change clears every choice", draft.isUseBounty());
	}

	// -- 4. unlock rules ---------------------------------------------------------------------------

	@Test
	public void gatedPatches_needEvidence_lockedAndUnverifiedAreKeptApart()
	{
		AccountCapability noBrainRobbery = maxed().quest(Quest.THE_GREAT_BRAIN_ROBBERY, QuestState.NOT_STARTED).build();
		FarmingAccess.Resolver r = FarmingAccess.resolver(noBrainRobbery, EvidenceSource.NONE);
		assertEquals("Josh's case: Harmony is locked", FarmingAccess.Access.State.LOCKED, r.access("herb.harmony").getState());
		assertEquals(FarmingAccess.Access.State.LOCKED, r.access("allotment.harmony").getState());
		assertTrue("ungated patches need no evidence", r.access("herb.falador").isOpen());

		FarmingAccess.Resolver unread = FarmingAccess.resolver(null, EvidenceSource.NONE);
		assertEquals("an incomplete login is unverified, never locked", FarmingAccess.Access.State.UNVERIFIED, unread.access("herb.harmony").getState());
		assertTrue(unread.access("herb.catherby").isOpen());

		AccountCapability low = base(60).build();
		FarmingAccess.Resolver guild = FarmingAccess.resolver(low, EvidenceSource.NONE);
		assertTrue("45: beginner tier", guild.access("allotment.farming_guild.n").isOpen());
		assertEquals("65 needed", FarmingAccess.Access.State.LOCKED, guild.access("herb.farming_guild").getState());

		// Weiss: the Fire of Nourishment can't be read, so it stays unverified until a crop is seen there.
		assertEquals(FarmingAccess.Access.State.UNVERIFIED, FarmingAccess.resolver(maxed().build(), EvidenceSource.NONE).access("herb.weiss").getState());
		EvidenceSource plantedThere = evidenceYes(FarmingAccess.PLANTED_HERE_PREFIX + "herb.weiss");
		assertTrue(FarmingAccess.resolver(maxed().build(), plantedThere).access("herb.weiss").isOpen());
		// …but a crop seen there never overrides an evidenced unmet requirement.
		AccountCapability noArm = maxed().quest(Quest.MAKING_FRIENDS_WITH_MY_ARM, QuestState.NOT_STARTED).build();
		assertEquals(FarmingAccess.Access.State.LOCKED, FarmingAccess.resolver(noArm, plantedThere).access("herb.weiss").getState());
	}

	@Test
	public void everyGatedPatch_isInTheCatalog()
	{
		for (String id : FarmingAccess.all().keySet())
		{
			assertNotNull("gated patch id is real: " + id, CATALOG.patch(id));
			assertNotNull(FarmingAccess.sourceOf(id));
		}
	}

	@Test
	public void theModel_offersOnlyOpenPatches_andListsTheRestSeparately()
	{
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), known,
			Collections.emptyList());
		AccountCapability cap = maxed().quest(Quest.THE_GREAT_BRAIN_ROBBERY, QuestState.NOT_STARTED).build();
		FarmingModel m = FarmingModel.build(CATALOG, snap, T0, FarmingAccountFacts.of(cap, EvidenceSource.NONE));
		for (FarmingModel.Row row : m.rows(FarmingRunType.HERB))
		{
			assertFalse("Harmony never appears in a herb run it can't be planted in", row.getPatchId().equals("herb.harmony"));
		}
		boolean listed = false;
		for (FarmingModel.Excluded e : m.excluded(FarmingRunType.HERB))
		{
			listed |= e.getRow().getPatchId().equals("herb.harmony") && e.getAccess().getState() == FarmingAccess.Access.State.LOCKED;
		}
		assertTrue("kept in the collapsed locked section", listed);
		FarmingModel unread = FarmingModel.build(CATALOG, snap, T0, FarmingAccountFacts.NONE);
		for (FarmingModel.Excluded e : unread.excluded(FarmingRunType.HERB))
		{
			assertEquals("not read yet: unverified, not locked", FarmingAccess.Access.State.UNVERIFIED, e.getAccess().getState());
		}
	}

	// -- 5. equipment, storage and the amulet of bounty ---------------------------------------------------

	@Test
	public void theToolLeprechaun_isDecodedLikeTheGame_andUnreadIsUnknown()
	{
		AccountCapability.Builder b = maxed();
		for (int v : FarmingAccountFacts.varbits())
		{
			b.varbit(v, 0);
		}
		b.varbit(VarbitID.FARMING_TOOLS_SPADE, 1).varbit(VarbitID.FARMING_TOOLS_EXTRASPADES, 1) // 1 + 2×1
			.varbit(VarbitID.FARMING_TOOLS_SECATEURS, 1).varbit(VarbitID.FARMING_TOOLS_FAIRYSECATEURS, 1)
			.varbit(VarbitID.FARMING_TOOLS_COMPOST, 10).varbit(VarbitID.FARMING_TOOLS_EXTRACOMPOST, 1) // 10 + 256
			.varbit(VarbitID.FARMING_TOOLS_BUCKETS, 3).varbit(VarbitID.FARMING_TOOLS_EXTRABUCKETS, 1).varbit(VarbitID.FARMING_TOOLS_EXTRA2BUCKETS, 1)
			.varbit(VarbitID.FARMING_TOOLS_ULTRACOMPOST, 7).varbit(VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE, 2);
		FarmingAccountFacts.ToolStore s = FarmingAccountFacts.toolStore(b.build());
		assertNotNull(s);
		assertEquals(3, s.count(ItemID.SPADE));
		assertEquals("magic secateurs stored", 1, s.count(ItemID.FAIRY_ENCHANTED_SECATEURS));
		assertEquals(0, s.count(ItemID.SECATEURS));
		assertEquals(266, s.count(ItemID.BUCKET_COMPOST));
		assertEquals(3 + 32 + 256, s.count(ItemID.BUCKET_EMPTY));
		assertEquals(7, s.count(ItemID.BUCKET_ULTRACOMPOST));
		assertEquals("filled bottomless bucket", 2, s.getBottomless());
		assertNull("one varbit unread: unknown, never 'none stored'", FarmingAccountFacts.toolStore(maxed().build()));
	}

	@Test
	public void storedTools_areReachable_neverAskedTwice_andUnreadStorageIsNotLacking()
	{
		AccountCapability.Builder b = maxed();
		for (int v : FarmingAccountFacts.varbits())
		{
			b.varbit(v, 0);
		}
		AccountCapability cap = b.varbit(VarbitID.FARMING_TOOLS_SPADE, 1).varbit(VarbitID.FARMING_TOOLS_DIBBER, 1).varbit(VarbitID.FARMING_TOOLS_RAKE, 1)
			.varbit(VarbitID.FARMING_TOOLS_ULTRACOMPOST, 5).build();
		FarmingRunIntent intent = run(NOW, "ULTRACOMPOST", herbs("RANARR", "herb.falador", "herb.catherby"));
		FarmingPreparation.Plan p = plan(intent, cap);
		PreparationState state = PreparationEvaluator.evaluate(p.getRequest(), cap, NOW);
		assertEquals(NeedStatus.REACHABLE, result(state, "tool.spade").getStatus());
		assertTrue(result(state, "tool.spade").isSatisfied());
		assertEquals("5 stored cover 2 patches", NeedStatus.REACHABLE, result(state, "supply.compost." + ItemID.BUCKET_ULTRACOMPOST).getStatus());
		int spades = 0;
		for (ItemNeed n : p.getRequest().getNeeds())
		{
			spades += n.accepts(ItemID.SPADE) ? 1 : 0;
		}
		assertEquals("a reusable tool is listed once", 1, spades);

		FarmingPreparation.Plan unread = plan(intent, maxed().build());
		PreparationState u = PreparationEvaluator.evaluate(unread.getRequest(), maxed().build(), NOW);
		assertFalse("storage not read: never claimed reachable", result(u, "tool.spade").getStatus() == NeedStatus.REACHABLE);
	}

	@Test
	public void magicSecateurs_andHerbSack_areOptional_neverAShortage()
	{
		FarmingRunIntent intent = run(NOW, "NONE", herbs("RANARR", "herb.falador"));
		AccountCapability cap = maxed().inventory(items(ItemID.RANARR_SEED, 1, ItemID.DIBBER, 1, ItemID.SPADE, 1, ItemID.RAKE, 1)).build();
		FarmingPreparation.Plan p = plan(intent, cap);
		assertEquals(ItemNeed.Necessity.RECOMMENDED, need(p, "tool.magic_secateurs").getNecessity());
		assertEquals(ItemNeed.Necessity.RECOMMENDED, need(p, "tool.herb_sack").getNecessity());
		assertTrue("any herb sack variant", need(p, "tool.herb_sack").accepts(ItemID.SLAYER_HERB_SACK_OPEN));
		PreparationState state = PreparationEvaluator.evaluate(p.getRequest(), cap, NOW);
		FarmingPreparationView v = FarmingPreparationView.of(p, state, false);
		assertEquals("only the travel can still be missing; extras never count", 0, countRequiredSupplies(v));
		for (FarmingPreparationView.NeedRow r : v.rows(FarmingPreparationView.Group.OPTIONAL))
		{
			assertFalse(r.isRequired());
		}
		AccountCapability worn = maxed().worn(items(ItemID.FAIRY_ENCHANTED_SECATEURS, 1)).build();
		assertTrue("worn magic secateurs count", result(PreparationEvaluator.evaluate(p.getRequest(), worn, NOW), "tool.magic_secateurs").isSatisfied());
	}

	@Test
	public void bounty_isAllotmentsOnly_wornOnly_optional_andNeverCutsTheSeedCount()
	{
		Map<String, String> plans = new LinkedHashMap<>();
		plans.put("allotment.falador.nw", "POTATO");
		plans.put("herb.falador", "RANARR");
		FarmingRunIntent intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-mixed", FarmingRunType.ALLOTMENT, NOW, NOW,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options("NONE", false).withBounty(true), Collections.emptyMap()).withPreparing(true);
		FarmingPreparation.Plan p = plan(intent, maxed().build());
		ItemNeed bounty = need(p, "tool.amulet_of_bounty");
		assertNotNull(bounty);
		assertEquals(ItemNeed.SatisfiedBy.WORN_ONLY, bounty.getSatisfiedBy());
		assertEquals("neck", bounty.getWornSlot());
		assertEquals(ItemNeed.Necessity.RECOMMENDED, bounty.getNecessity());
		assertEquals("three per allotment, bounty or not", 3, need(p, "seed.allotment.any").getQuantity());
		assertTrue(FarmingPreparation.bountyApplies(FarmingRunType.ALLOTMENT));
		assertFalse(FarmingPreparation.bountyApplies(FarmingRunType.HERB));

		AccountCapability inBag = maxed().inventory(items(ItemID.AMULET_OF_BOUNTY, 1)).build();
		NeedResult carried = result(PreparationEvaluator.evaluate(p.getRequest(), inBag, NOW), "tool.amulet_of_bounty");
		assertTrue("carried but not worn", carried.isCarriedNotWorn());
		assertFalse(carried.isSatisfied());
		AccountCapability worn = maxed().worn(items(ItemID.AMULET_OF_BOUNTY, 1)).build();
		assertTrue(result(PreparationEvaluator.evaluate(p.getRequest(), worn, NOW), "tool.amulet_of_bounty").isSatisfied());

		FarmingRunIntent off = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-mixed", FarmingRunType.ALLOTMENT, NOW, NOW,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options("NONE", false), Collections.emptyMap()).withPreparing(true);
		assertNull("only when selected", need(plan(off, maxed().build()), "tool.amulet_of_bounty"));
	}

	@Test
	public void slotEstimate_isTheSuppliesOnly_noHarvestOrTripPlanning()
	{
		String fits = FarmingPreparationView.slotText(12);
		assertTrue(fits, fits.contains("about 12 of 28"));
		String over = FarmingPreparationView.slotText(40);
		assertTrue(over, over.contains("more than one inventory"));
		for (String s : new String[]{fits, over})
		{
			String l = s.toLowerCase();
			assertFalse("the player manages harvest space, noting and banking: " + s,
				l.contains("harvest") || l.contains("bank") || l.contains("note") || l.contains("trip"));
		}
		assertEquals("unknown: said nothing rather than guessing", "", FarmingPreparationView.slotText(-1));
	}

	// -- 6. the Farming contract ------------------------------------------------------------------------

	@Test
	public void theContract_isReadFromTheGame_andNeverGuessed()
	{
		assertEquals(FarmingAccountFacts.Contract.State.NOT_READ, FarmingAccountFacts.contract(maxed().build()).getState());
		FarmingAccountFacts.Contract none = FarmingAccountFacts.contract(maxed().varbit(VarbitID.FARMGUILD_CONTRACT_TYPE, 0)
			.varbit(VarbitID.FARMGUILD_CONTRACT_COMPLETE, 0).build());
		assertEquals(FarmingAccountFacts.Contract.State.NONE, none.getState());
		FarmingAccountFacts.Contract snap = FarmingAccountFacts.contract(maxed().varbit(VarbitID.FARMGUILD_CONTRACT_TYPE, 29)
			.varbit(VarbitID.FARMGUILD_CONTRACT_COMPLETE, 0).build());
		assertEquals(FarmingAccountFacts.Contract.State.ACTIVE, snap.getState());
		assertEquals(FarmingProduce.SNAPDRAGON, snap.getCrop());
		assertEquals(FarmingAccountFacts.Contract.State.COMPLETE, FarmingAccountFacts.contract(maxed().varbit(VarbitID.FARMGUILD_CONTRACT_TYPE, 29)
			.varbit(VarbitID.FARMGUILD_CONTRACT_COMPLETE, 1).build()).getState());
		assertEquals(FarmingAccountFacts.Contract.State.UNRECOGNISED, FarmingAccountFacts.contract(maxed().varbit(VarbitID.FARMGUILD_CONTRACT_TYPE, 99)
			.varbit(VarbitID.FARMGUILD_CONTRACT_COMPLETE, 0).build()).getState());
		assertEquals("logged out: nothing at all", FarmingAccountFacts.NONE, FarmingAccountFacts.of(null, EvidenceSource.NONE));
	}

	@Test
	public void skillLevel_isUsedOnlyWhenRead()
	{
		AccountCapability cap = base(50).level(Skill.FARMING, 50).build();
		assertEquals(Integer.valueOf(50), FarmingAccountFacts.of(cap, EvidenceSource.NONE).getFarmingLevel());
		List<FarmingProduce> fit = FarmingSeeds.herbsPlantableAt(50);
		assertTrue(fit.contains(FarmingProduce.RANARR));
		assertFalse(fit.contains(FarmingProduce.SNAPDRAGON));
		assertTrue("level unknown: every herb is acceptable", FarmingSeeds.herbsPlantableAt(null).contains(FarmingProduce.TORSTOL));
	}

	// -- helpers -------------------------------------------------------------------------------------

	private static int countRequiredSupplies(FarmingPreparationView v)
	{
		int n = 0;
		for (FarmingPreparationView.NeedRow r : v.rows(FarmingPreparationView.Group.NEEDED))
		{
			n += r.isRequired() && !"Travel".equals(r.getSection()) ? 1 : 0;
		}
		return n;
	}

	private static Map<String, String> herbs(String crop, String... ids)
	{
		Map<String, String> m = new LinkedHashMap<>();
		for (String id : ids)
		{
			m.put(id, crop);
		}
		return m;
	}

	private static FarmingPreparation.Plan plan(FarmingRunIntent intent, AccountCapability cap)
	{
		return FarmingPreparation.plan(intent.withPreparing(true), CATALOG, cap, TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW),
			EvidenceSource.NONE);
	}

	private static ItemNeed need(FarmingPreparation.Plan p, String key)
	{
		for (ItemNeed n : p.getRequest().getNeeds())
		{
			if (n.getKey().equals(key))
			{
				return n;
			}
		}
		return null;
	}

	private static NeedResult result(PreparationState state, String key)
	{
		NeedResult r = state.result(key);
		assertNotNull(key, r);
		return r;
	}

	private static EvidenceSource evidenceYes(String yesKey)
	{
		return new EvidenceSource()
		{
			@Override
			public Known get(String key)
			{
				return key.equals(yesKey) ? Known.YES : Known.UNKNOWN;
			}

			@Override
			public String explain(String key)
			{
				return "test";
			}
		};
	}

	@SuppressWarnings("unused")
	private static List<String> list(String... s)
	{
		return Arrays.asList(s);
	}
}
