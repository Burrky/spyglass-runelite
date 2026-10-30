package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Compost bins (canonical §28.15, §28.16): the cache's value table, the cache-verified identity of each bin, the one next task
 * (never a filler choice, never a wait for rotting), the player's upgrades (compost potion, volcanic ash), runs that visit a
 * chosen bin with no crop patch there, a last stop's bin that keeps the run open until done or skipped, and the supplies.
 */
public class CompostBinTest
{
	private static final long MIN = 60_000L;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final CompostBin.Def FALADOR = CompostBin.def("bin.falador");
	private static final CompostBin.Def GUILD = CompostBin.def("bin.farming_guild");

	private static CompostBin.Seen seen(int raw, long since)
	{
		return new CompostBin.Seen(raw, since, since);
	}

	// -- the game's values (osrs-dumps @6e5033af loc multilocs; RuneLite PatchImplementation) ---------------------------

	@Test
	public void theCachesValueTable_includingRottedBinsOfAnyCount()
	{
		assertEquals(CompostBin.Phase.EMPTY, CompostBin.decode(0, false).phase);
		assertEquals(7, CompostBin.decode(7, false).count);
		assertEquals(CompostBin.Phase.FILLING, CompostBin.decode(7, false).phase);
		assertEquals(CompostBin.Phase.READY, CompostBin.decode(16, false).phase);
		assertEquals("48 is one bucket left", 1, CompostBin.decode(48, false).count);
		assertEquals(CompostBin.Phase.ROTTING, CompostBin.decode(31, false).phase);
		assertEquals("closed (compost_bin_closed)", CompostBin.Phase.ROTTING, CompostBin.decode(70, false).phase);
		CompostBin.State rotted = CompostBin.decode(85, false);
		assertEquals("compost_bin_compost_ready 80-94: rotted, open it", CompostBin.Phase.ROTTED, rotted.phase);
		assertEquals(6, rotted.count);
		assertEquals(15, CompostBin.decode(94, false).count);
		assertEquals(CompostBin.Contents.SUPERCOMPOST, CompostBin.decode(112, false).contents);
		assertEquals(CompostBin.Phase.ROTTED, CompostBin.decode(126, false).phase);
		assertEquals(CompostBin.Phase.ROTTED, CompostBin.decode(210, false).phase);
		assertEquals(CompostBin.Contents.ROTTEN_TOMATOES, CompostBin.decode(210, false).contents);
		assertEquals(CompostBin.Contents.ULTRACOMPOST, CompostBin.decode(190, false).contents);
		assertNull("not in the table: never guessed", CompostBin.decode(240, false));
		assertEquals(16, CompostBin.decode(63, true).count);
		assertEquals(CompostBin.Phase.ROTTED, CompostBin.decode(94, true).phase);
		assertEquals(CompostBin.Phase.ROTTING, CompostBin.decode(97, true).phase);
		assertEquals(CompostBin.Phase.ROTTED, CompostBin.decode(99, true).phase);
		assertEquals(30, CompostBin.decode(205, true).count);
		assertNull("115-123 show an empty bin with no menu: unknown", CompostBin.decode(118, true));
		// Live, 2026-10-22: Josh's Port Phasmatys bin read 126 = supercompost rotted.
		assertEquals("Supercompost rotted · open it", CompostBin.describe(CompostBin.def("bin.morytania"), CompostBin.decode(126, false)));
	}

	@Test
	public void eachBinIsItsOwnObject_atItsOwnTile_onItsAreasVarbit()
	{
		assertEquals(7836, FALADOR.getObjectId());
		assertEquals(3056, FALADOR.getX());
		assertEquals(3312, FALADOR.getY());
		assertEquals(34631, GUILD.getObjectId());
		assertTrue(GUILD.isBig());
		assertEquals(net.runelite.api.gameval.VarbitID.FARMING_TRANSMIT_N, GUILD.getVarbit());
		assertEquals(net.runelite.api.gameval.VarbitID.FARMING_TRANSMIT_D, CompostBin.def("bin.prifddinas").getVarbit());
		for (CompostBin.Def d : CompostBin.ALL)
		{
			FarmingPatchDef anchor = CATALOG.patch(d.getAnchorPatchId());
			assertNotNull(d.getBinId(), anchor);
			// The bin stands inside its anchor's area: the varbits read there describe it.
			assertFalse(d.getBinId(), CATALOG.resolve(new FarmingLocation(d.getX(), d.getY(), d.getPlane())).isEmpty());
			assertTrue(d.getBinId(), CATALOG.resolve(new FarmingLocation(d.getX(), d.getY(), d.getPlane())).getPatches().contains(anchor));
		}
		assertEquals(Collections.singletonList(FALADOR), CompostBin.withPatches(Arrays.asList("allotment.falador.nw", "herb.falador")));
		assertTrue(CompostBin.withPatches(Collections.singletonList("spirit_tree.port_sarim")).isEmpty());
	}

	// -- tasks -------------------------------------------------------------------------------------------------------

	@Test
	public void theNextTask_neverAFiller_neverAWait()
	{
		long now = T0;
		assertNull("empty: the player's compostables", CompostBin.task(FALADOR, seen(0, now), CompostBin.Upgrade.NONE, now));
		assertNull("part filled", CompostBin.task(FALADOR, seen(7, now), CompostBin.Upgrade.NONE, now));
		assertEquals(CompostBin.TaskKind.CLOSE, CompostBin.task(FALADOR, seen(15, now), CompostBin.Upgrade.NONE, now).kind);
		assertNull("15 of the big bin's 30", CompostBin.task(GUILD, seen(15, now), CompostBin.Upgrade.NONE, now));
		assertNull("closed 20 minutes ago: still rotting, never waited for", CompostBin.task(FALADOR, seen(95, now - 20 * MIN), CompostBin.Upgrade.NONE,
			now));
		CompostBin.Task probably = CompostBin.task(FALADOR, seen(95, now - 71 * MIN), CompostBin.Upgrade.NONE, now);
		assertEquals("seen rotting over 70 minutes ago: rotted by now (OSRS Wiki 35-70 min)", CompostBin.TaskKind.OPEN, probably.kind);
		assertTrue(probably.projected);
		assertEquals(CompostBin.TaskKind.OPEN, CompostBin.task(FALADOR, seen(126, now), CompostBin.Upgrade.NONE, now).kind);
		CompostBin.Task take = CompostBin.task(FALADOR, seen(62, now), CompostBin.Upgrade.NONE, now);
		assertEquals("Take supercompost from the bin · 15", take.label);
		assertEquals(15, take.buckets);
		assertEquals("rotten tomatoes are taken by hand", 0, CompostBin.task(FALADOR, seen(150, now), CompostBin.Upgrade.NONE, now).buckets);
	}

	@Test
	public void upgrades_areThePlayersChoice_andEachStepIsTheGamesState()
	{
		long now = T0;
		assertEquals(CompostBin.TaskKind.POTION, CompostBin.task(FALADOR, seen(30, now), CompostBin.Upgrade.SUPER, now).kind);
		assertEquals("no upgrade chosen: take it", CompostBin.TaskKind.TAKE, CompostBin.task(FALADOR, seen(30, now), CompostBin.Upgrade.NONE, now).kind);
		assertEquals("supercompost wanted and there: take it", CompostBin.TaskKind.TAKE,
			CompostBin.task(FALADOR, seen(62, now), CompostBin.Upgrade.SUPER, now).kind);
		CompostBin.Task ash = CompostBin.task(FALADOR, seen(62, now), CompostBin.Upgrade.ULTRA, now);
		assertEquals(CompostBin.TaskKind.ASH, ash.kind);
		assertEquals(25, ash.ash);
		assertEquals("the big bin takes 50", 50, CompostBin.task(GUILD, seen(62, now), CompostBin.Upgrade.ULTRA, now).ash);
		assertEquals("done: the bin shows ultracompost (176-190), the step is over", CompostBin.TaskKind.TAKE,
			CompostBin.task(FALADOR, seen(190, now), CompostBin.Upgrade.ULTRA, now).kind);
		// Supplies for what is left from here: compost up to ultra = a potion dose, 25 ash and 15 buckets.
		assertTrue(Arrays.equals(new int[]{15, 25, 1}, CompostBin.supplies(FALADOR, seen(30, now), CompostBin.Upgrade.ULTRA, now)));
		assertTrue(Arrays.equals(new int[]{15, 25, 0}, CompostBin.supplies(FALADOR, seen(62, now), CompostBin.Upgrade.ULTRA, now)));
		assertTrue(Arrays.equals(new int[]{15, 0, 0}, CompostBin.supplies(FALADOR, seen(190, now), CompostBin.Upgrade.ULTRA, now)));
		assertTrue("rotted, count from the cache", Arrays.equals(new int[]{6, 0, 0}, CompostBin.supplies(FALADOR, seen(85, now), CompostBin.Upgrade.NONE,
			now)));
		assertTrue("rotting: nothing to carry yet", Arrays.equals(new int[]{0, 0, 0}, CompostBin.supplies(FALADOR, seen(95, now), CompostBin.Upgrade.NONE,
			now)));
	}

	@Test
	public void participationAndUpgradesAreChosen_andSaved_neverImpliedBySeeing()
	{
		Gson gson = new Gson();
		FarmingPreferences p = FarmingPreferences.defaults();
		assertTrue(p.getRunBins().isEmpty());
		FarmingPreferences on = p.withRunBin("bin.falador", true).withRunBin("bin.nowhere", true).withBinUpgrade("bin.falador", CompostBin.Upgrade.ULTRA);
		assertEquals(Collections.singleton("bin.falador"), on.getRunBins());
		FarmingPreferences back = gson.fromJson(gson.toJson(on), FarmingPreferences.class);
		assertEquals(on, back);
		assertEquals(CompostBin.Upgrade.ULTRA, back.binUpgrade("bin.falador"));
		FarmingPreferences old = gson.fromJson("{\"treeClearing\":\"PAY\"}", FarmingPreferences.class);
		assertTrue("preferences saved before bins: none included", old.getRunBins().isEmpty());
		assertEquals(CompostBin.Upgrade.NONE, old.binUpgrade("bin.falador"));
	}

	// -- in a run ----------------------------------------------------------------------------------------------------

	private final Map<String, PatchKnowledge> known = new HashMap<>();

	private FarmingRunIntent run(FarmingRunType type, List<String> ids, int ready, List<String> bins, Map<String, String> upgrades)
	{
		Map<String, String> plans = new LinkedHashMap<>();
		Map<String, FarmingRunIntent.Baseline> base = new LinkedHashMap<>();
		for (String id : ids)
		{
			FarmingKnowledgeFixtures.see(known, id, ready, T0 - 60 * MIN);
			plans.put(id, FarmingRunIntent.REPLANT_ANY);
			base.put(id, FarmingRunIntent.Baseline.of(patch(id), known.get(id)));
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-bin", type, T0, T0, FarmingRunIntent.State.ACTIVE, null, null,
			new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans, new FarmingRunIntent.Options("NONE", false), base)
			.withExtras(bins, upgrades, Collections.emptyList());
	}

	private FarmingRunIntent herbRun(List<String> bins)
	{
		return run(FarmingRunType.HERB, Arrays.asList("herb.falador", "herb.catherby"),
			FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1), bins, null);
	}

	private FarmingRunView view(FarmingRunIntent run, Map<String, CompostBin.Seen> bins, long now, String... here)
	{
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
			new LinkedHashMap<>(known), Collections.emptyList(), FarmingRunEvaluator.withEvidence(run, CATALOG, known),
			new HashSet<>(Arrays.asList(here))).withBins(bins);
		return FarmingModel.build(CATALOG, snap, now).getRun();
	}

	private static List<TransportAvailability> availability(AccountCapability cap)
	{
		return TransportEvaluator.evaluateAll(com.osrstelemetry.plugin.travel.TestTransports.CATALOG, cap, EvidenceSource.NONE, T0);
	}

	private static FarmingGuide.Position beside(List<FarmingPreparation.SitePlan> sites, String patchId, int dx)
	{
		FarmingPreparation.SitePlan s = sites.stream().filter(x -> x.getSite().getPatchIds().contains(patchId)).findFirst().orElseThrow(AssertionError::new);
		return new FarmingGuide.Position(s.getSite().getTile().x + dx, s.getSite().getTile().y, s.getSite().getTile().plane);
	}

	private void replant(String id, long at)
	{
		int ready = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
		int seed = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.GROWING, 0);
		FarmingKnowledgeFixtures.change(known, id, ready, 3, at);
		FarmingKnowledgeFixtures.change(known, id, 3, seed, at + MIN);
	}

	@Test
	public void atACropStop_theBinComesAfterThePatches_andTheLastStopsBinKeepsTheRunUntilDoneOrSkipped()
	{
		FarmingRunIntent run = herbRun(Collections.singletonList("bin.falador"));
		AccountCapability cap = maxed().build();
		Map<String, CompostBin.Seen> bins = new HashMap<>(Collections.singletonMap("bin.falador", seen(62, T0 - 300 * MIN)));
		List<FarmingPreparation.SitePlan> sites = FarmingPreparation.plan(run.withPreparing(true), CATALOG, cap, availability(cap), EvidenceSource.NONE,
			FarmingPreferences.defaults(), known, bins, T0).getSites();
		assertEquals("no extra stop: the bin is at a crop stop", 2, sites.size());
		FarmingGuide.Position at = beside(sites, "herb.falador", 2);

		FarmingGuide.Objective first = FarmingGuide.resolve(view(run, bins, T0, "herb.falador"), sites, at, at, null, FarmingPreferences.defaults(), CATALOG);
		assertFalse("the herb first: " + first.getAction().getLabel(), first.getAction().getLabel().contains("bin"));

		replant("herb.catherby", T0 + MIN);
		replant("herb.falador", T0 + 3 * MIN);
		long now = T0 + 5 * MIN;
		FarmingRunIntent tracked = FarmingRunEvaluator.withEvidence(run, CATALOG, known);
		FarmingRunEvaluator.Progress progress = FarmingRunEvaluator.evaluate(tracked, CATALOG, known);
		assertTrue("every crop patch is done", progress.isAllSettled());
		assertFalse("but the run is not complete: the last stop's bin is still to do", RunExtras.complete(tracked, progress, bins, now));
		FarmingRunView v = view(run, bins, now, "herb.falador");
		FarmingGuide.Objective bin = FarmingGuide.resolve(v, sites, at, at, "Falador", FarmingPreferences.defaults(), CATALOG, null, "Falador");
		assertEquals(FarmingGuide.Kind.ACT, bin.getKind());
		assertEquals("Take supercompost from the bin · 15", bin.getAction().getLabel());
		assertEquals("the bin object itself (cache id)", Collections.singleton(7836), bin.getAction().getTarget().ids);
		assertEquals(3056, bin.getAction().getTarget().x);
		assertEquals("Falador · Compost bin", bin.getTitle());

		// Taken: the bin is empty, nothing is left, the run completes.
		bins.put("bin.falador", seen(0, now + MIN));
		assertTrue(RunExtras.complete(tracked, progress, bins, now + MIN));
		// Or skipped: the run completes as well.
		bins.put("bin.falador", seen(62, now));
		assertTrue("Skip: the run is never held", RunExtras.complete(tracked.withBinSkipped("bin.falador", true), progress, bins, now));
		assertEquals(FarmingGuide.Kind.COMPLETE, FarmingGuide.resolve(view(tracked.withBinSkipped("bin.falador", true), bins, now, "herb.falador"), sites,
			at, at, "Falador", FarmingPreferences.defaults(), CATALOG, null, "Falador").getKind());
	}

	@Test
	public void aChosenBinWithNoCropPatchThere_isAStopOfItsOwn()
	{
		int ready = FarmingKnowledgeFixtures.raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.HARVESTABLE, -1);
		FarmingRunIntent run = run(FarmingRunType.TREE, Collections.singletonList("tree.varrock"), ready, Collections.singletonList("bin.falador"), null);
		AccountCapability cap = maxed().build();
		Map<String, CompostBin.Seen> bins = Collections.singletonMap("bin.falador", seen(126, T0 - 300 * MIN));
		FarmingPreparation.Plan plan = FarmingPreparation.plan(run.withPreparing(true), CATALOG, cap, availability(cap), EvidenceSource.NONE,
			FarmingPreferences.defaults(), known, bins, T0);
		FarmingPreparation.SitePlan binStop = plan.getSites().stream().filter(s -> s.getSite().getPatchIds().contains("herb.falador")).findFirst()
			.orElseThrow(() -> new AssertionError("no stop for the Falador bin"));
		assertTrue("planned like any stop", plan.patchOrder().contains("herb.falador") || plan.getSites().indexOf(binStop) >= 0);
		FarmingGuide.Position at = new FarmingGuide.Position(3058, 3312, 0);
		FarmingGuide.Objective o = FarmingGuide.resolve(view(run, bins, T0, "herb.falador"), plan.getSites(), at, at, null, FarmingPreferences.defaults(),
			CATALOG);
		assertEquals("at the bin's site", FarmingGuide.Kind.ACT, o.getKind());
		assertEquals("Open the compost bin", o.getAction().getLabel());
		assertEquals("walk to the bin, not the herb patch", 3056, o.getTarget().x);
		// Rotting and not long closed: no task, no stop, nothing to wait for.
		Map<String, CompostBin.Seen> rotting = Collections.singletonMap("bin.falador", seen(95, T0 - 10 * MIN));
		assertTrue(FarmingPreparation.plan(run.withPreparing(true), CATALOG, cap, availability(cap), EvidenceSource.NONE, FarmingPreferences.defaults(),
			known, rotting, T0).getSites().stream().noneMatch(s -> s.getSite().getPatchIds().contains("herb.falador")));
	}

	@Test
	public void supplies_forWhatCanBeWorkedOut_optional_andAshCountedOnceWithFertileSoil()
	{
		Map<String, String> upgrades = new HashMap<>();
		upgrades.put("bin.falador", "ULTRA");
		upgrades.put("bin.catherby", "SUPER");
		FarmingRunIntent run = run(FarmingRunType.HERB, Arrays.asList("herb.falador", "herb.catherby"),
			FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1),
			Arrays.asList("bin.falador", "bin.catherby", "bin.ardougne"), upgrades);
		AccountCapability cap = maxed().build();
		Map<String, CompostBin.Seen> bins = new HashMap<>();
		bins.put("bin.falador", seen(62, T0)); // 15 supercompost → ash 25, buckets 15
		bins.put("bin.catherby", seen(30, T0)); // 15 compost → a potion dose, buckets 15
		bins.put("bin.ardougne", seen(7, T0)); // part filled: nothing
		FarmingPreparation.Plan plan = FarmingPreparation.plan(run.withPreparing(true), CATALOG, cap, availability(cap), EvidenceSource.NONE,
			FarmingPreferences.defaults(), known, bins, T0);
		assertEquals(30, need(plan, "supply.bin_buckets").getQuantity());
		assertEquals(ItemNeed.Necessity.RECOMMENDED, need(plan, "supply.bin_buckets").getNecessity());
		assertEquals(1, need(plan, "supply.compost_potion").getQuantity());
		assertEquals(25, need(plan, "supply.volcanic_ash").getQuantity());
		assertNull("never a filler for the part-filled bin", need(plan, "supply.compostables"));
		// Fertile Soil's ash and the bin's share one row (the same ash in the inventory is counted once).
		List<ItemNeed> needs = new ArrayList<>();
		FarmingPreparation.addAsh(needs, 4, "Fertile Soil");
		FarmingPreparation.addAsh(needs, 25, "bin");
		assertEquals(1, needs.size());
		assertEquals(29, needs.get(0).getQuantity());
	}

	private static ItemNeed need(FarmingPreparation.Plan plan, String key)
	{
		for (ItemNeed n : plan.getRequest().getNeeds())
		{
			if (n.getKey().equals(key))
			{
				return n;
			}
		}
		return null;
	}
}
