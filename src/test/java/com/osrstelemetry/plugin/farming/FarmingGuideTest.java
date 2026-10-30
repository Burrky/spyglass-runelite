package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Adaptive guidance: follows the stop the player is actually at, sticks to its choice, re-targets after a teleport. */
public class FarmingGuideTest
{
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final List<String> IDS = Arrays.asList("herb.falador", "herb.catherby", "herb.ardougne");

	private static FarmingRunIntent intent()
	{
		Map<String, String> plans = new LinkedHashMap<>();
		Map<String, FarmingRunIntent.Baseline> baseline = new LinkedHashMap<>();
		for (String id : IDS)
		{
			plans.put(id, FarmingRunIntent.REPLANT_ANY);
			baseline.put(id, FarmingRunIntent.Baseline.of(patch(id), null));
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-guide", FarmingRunType.HERB, NOW, NOW, FarmingRunIntent.State.ACTIVE, null, null,
			new ArrayList<>(IDS), Collections.emptyList(), Collections.emptyList(), plans, new FarmingRunIntent.Options(null, false), baseline);
	}

	private static List<FarmingPreparation.SitePlan> sites(AccountCapability cap)
	{
		return FarmingPreparation.plan(intent().withPreparing(true), CATALOG, cap, TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW),
			EvidenceSource.NONE).getSites();
	}

	private static FarmingRunView view(String... here)
	{
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), new LinkedHashMap<>(),
			Collections.emptyList(), intent(), new HashSet<>(Arrays.asList(here)));
		return FarmingModel.build(CATALOG, snap, NOW).getRun();
	}

	private static FarmingPreparation.SitePlan site(List<FarmingPreparation.SitePlan> sites, String area)
	{
		return sites.stream().filter(s -> s.getSite().getName().startsWith(area)).findFirst().orElseThrow(AssertionError::new);
	}

	private static FarmingGuide.Position at(FarmingPreparation.SitePlan s, int dx)
	{
		return new FarmingGuide.Position(s.getSite().getTile().x + dx, s.getSite().getTile().y, s.getSite().getTile().plane);
	}

	@Test
	public void atAPatch_theObjectiveIsTheWorkThere_inWhateverOrder()
	{
		List<FarmingPreparation.SitePlan> sites = sites(maxed().build());
		FarmingGuide.Objective o = FarmingGuide.resolve(view("herb.ardougne"), sites, at(site(sites, "Ardougne"), 0), null, "Falador · herb",
			FarmingPreferences.defaults());
		assertEquals(FarmingGuide.Kind.ACT, o.getKind());
		assertTrue(o.getStop(), o.getStop().startsWith("Ardougne"));
		assertTrue(o.getChecklist().toString(), o.getChecklist().stream().anyMatch(l -> l.startsWith("○ ")));
		assertEquals("one current action at the patch", FarmingAction.Stage.ACT, o.getAction().getStage());
		assertEquals(FarmingAction.Target.Kind.PATCH, o.getAction().getTarget().kind);
		assertNull("nothing to teleport with while acting", o.getTransport());
	}

	@Test
	public void nearAStop_itWalks_farAway_itNamesTheTeleport()
	{
		List<FarmingPreparation.SitePlan> sites = sites(maxed().inventory(com.osrstelemetry.plugin.account.AccountFixtures.items(8009, 5, 8010, 5, 8011, 5)).build());
		FarmingGuide.Objective near = FarmingGuide.resolve(view(), sites, at(site(sites, "Catherby"), 20), null, null, FarmingPreferences.defaults());
		assertEquals(FarmingGuide.Kind.TRAVEL, near.getKind());
		assertTrue(near.getStop().startsWith("Catherby"));
		assertTrue(near.getChecklist().get(0), near.getChecklist().get(0).startsWith("Walk there"));
		FarmingGuide.Objective far = FarmingGuide.resolve(view(), sites, new FarmingGuide.Position(3200, 3200, 0), null, null,
			FarmingPreferences.defaults());
		assertEquals(FarmingGuide.Kind.TRAVEL, far.getKind());
		assertNotNull("a teleport is suggested and outlined", far.getTransport());
		assertTrue(far.getChecklist().get(0), far.getChecklist().get(0).startsWith("Use: "));
	}

	@Test
	public void theChoiceSticks_untilThePlayerTeleportsSomewhereElse()
	{
		List<FarmingPreparation.SitePlan> sites = sites(maxed().build());
		FarmingPreparation.SitePlan cath = site(sites, "Catherby");
		FarmingPreparation.SitePlan ard = site(sites, "Ardougne");
		String previous = cath.getSite().getName();
		// Midway, slightly closer to Ardougne: no jump, so Catherby is kept.
		FarmingGuide.Position mid = new FarmingGuide.Position((cath.getSite().getTile().x + ard.getSite().getTile().x) / 2 - 3,
			(cath.getSite().getTile().y + ard.getSite().getTile().y) / 2, 0);
		FarmingGuide.Position step = new FarmingGuide.Position(mid.x + 1, mid.y, 0);
		FarmingGuide.Objective kept = FarmingGuide.resolve(view(), sites, mid, step, previous, FarmingPreferences.defaults());
		assertEquals(previous, kept.getStop());
		// A teleport to Ardougne (a jump of more than 30 tiles): re-targeted.
		FarmingGuide.Objective jumped = FarmingGuide.resolve(view(), sites, at(ard, 15), at(cath, 0), previous, FarmingPreferences.defaults());
		assertEquals(ard.getSite().getName(), jumped.getStop());
	}

	@Test
	public void atThePatch_theChecklistIsTheWork_neverInventoryOrBankSteps()
	{
		List<FarmingPreparation.SitePlan> sites = sites(maxed().build());
		FarmingGuide.Objective o = FarmingGuide.resolve(view("herb.catherby"), sites, at(site(sites, "Catherby"), 0), null, null,
			FarmingPreferences.defaults(), FarmingPatchCatalog.standard());
		assertEquals(FarmingGuide.Kind.ACT, o.getKind());
		for (String l : o.getChecklist())
		{
			// A lone patch needs no "Herb:" prefix; nothing about the bank or the inventory.
			assertTrue(l, (l.startsWith("○ ") || l.startsWith("✓ ")) && !l.startsWith("○ Herb:") && !l.toLowerCase().contains("bank"));
		}
	}

	private static FarmingRunView view(List<String> current, String... here)
	{
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), new LinkedHashMap<>(),
			Collections.emptyList(), intent(), new HashSet<>(Arrays.asList(here)), current);
		return FarmingModel.build(CATALOG, snap, NOW).getRun();
	}

	private static List<String> order(FarmingRunView run)
	{
		List<String> out = new ArrayList<>();
		run.getLines().forEach(l -> out.add(l.getPatchId()));
		return out;
	}

	@Test
	public void oneOrder_theStopTheGuideIsOnLeads_thenThePlannedOrder()
	{
		// Live finding 2026-10-21: the guide said Catherby (the player was there) while To do listed it last.
		List<FarmingPreparation.SitePlan> sites = sites(maxed().build());
		FarmingPreparation.SitePlan cath = site(sites, "Catherby");
		FarmingGuide.Objective o = FarmingGuide.resolve(view("herb.catherby"), sites, at(cath, 0), null, null, FarmingPreferences.defaults());
		assertEquals(cath.getSite().getName(), o.getStop());
		// Guidance publishes that stop; every surface built from the snapshot leads with it.
		FarmingRunView run = view(cath.getSite().getPatchIds(), "herb.catherby");
		assertEquals(Arrays.asList("herb.catherby", "herb.falador", "herb.ardougne"), order(run));
		assertTrue("highlighted", run.getLines().get(0).isCurrent());
		assertTrue(run.getLines().stream().skip(1).noneMatch(FarmingRunView.PatchLine::isCurrent));
		assertEquals("Catherby \u00b7 Herb", run.getNextTitle());
		// Travelling to it (not there yet): still first, the NEXT card names it.
		FarmingRunView travelling = view(cath.getSite().getPatchIds());
		assertEquals("herb.catherby", order(travelling).get(0));
		assertEquals("the place and patch type, travelling or there", "Catherby \u00b7 Herb", travelling.getNextTitle());
		// No current stop: the planned order, unchanged.
		assertEquals(IDS, order(view(Collections.emptyList())));
	}

	@Test
	public void aGroupedStop_keepsItsPatchesApart_inTheStopsActionOrder()
	{
		FarmingRunView run = view(Arrays.asList("herb.ardougne", "herb.falador"));
		assertEquals("each patch keeps its own line, in the stop's order, then the plan", Arrays.asList("herb.ardougne", "herb.falador", "herb.catherby"),
			order(run));
		assertTrue(run.getLines().get(0).isCurrent() && run.getLines().get(1).isCurrent() && !run.getLines().get(2).isCurrent());
	}
}
