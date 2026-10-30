package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The current action follows the game's evidence on the next resolution, from the run's own state (canonical §28.14): no
 * new plan is needed (the plan made at the start of the run is reused unchanged here, as when a route search is pending).
 * A whole tree lifecycle with protection enabled: check health → pay for removal → plant → pay for protection → next stop.
 */
public class FarmingActionProgressionTest
{
	private static final long MIN = 60_000L;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final String TREE = "tree.gnome_stronghold";
	private static final String OTHER = "tree.varrock";
	private final Map<String, PatchKnowledge> known = new HashMap<>();

	private FarmingRunIntent intent;

	private FarmingRunView view(FarmingRunIntent run, long now)
	{
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
			new LinkedHashMap<>(known), Collections.emptyList(), FarmingRunEvaluator.withEvidence(run, CATALOG, known),
			new HashSet<>(Collections.singletonList(TREE)));
		return FarmingModel.build(CATALOG, snap, now).getRun();
	}

	@Test
	public void treeLifecycle_eachObservedStep_isTheNextActionAtOnce_andTheStopStaysCurrent()
	{
		FarmingKnowledgeFixtures.see(known, TREE, 60, T0 - 60 * MIN); // magic tree to check
		FarmingKnowledgeFixtures.see(known, OTHER, 60, T0 - 60 * MIN);
		Map<String, String> plans = new LinkedHashMap<>();
		Map<String, FarmingRunIntent.Baseline> base = new LinkedHashMap<>();
		for (String id : new String[]{TREE, OTHER})
		{
			plans.put(id, FarmingRunIntent.REPLANT_ANY);
			base.put(id, FarmingRunIntent.Baseline.of(patch(id), known.get(id)));
		}
		intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-tree", FarmingRunType.TREE, T0, T0, FarmingRunIntent.State.ACTIVE, null, null,
			new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans, new FarmingRunIntent.Options("NONE", true), base);
		AccountCapability cap = maxed().build();
		List<FarmingPreparation.SitePlan> sites = FarmingPreparation.plan(intent.withPreparing(true), CATALOG, cap,
			TransportEvaluator.evaluateAll(com.osrstelemetry.plugin.travel.TestTransports.CATALOG, cap, EvidenceSource.NONE, T0), EvidenceSource.NONE).getSites();
		FarmingPreparation.SitePlan stop = sites.stream().filter(s -> s.getSite().getPatchIds().contains(TREE)).findFirst().orElseThrow(AssertionError::new);
		FarmingGuide.Position at = new FarmingGuide.Position(stop.getSite().getTile().x + 2, stop.getSite().getTile().y, stop.getSite().getTile().plane);
		FarmingPreferences prefs = FarmingPreferences.defaults().withProtect(FarmingRunType.TREE, true).withTreeClearing(FarmingPreferences.TreeClearing.PAY);

		long worst = 0;
		String[] expected = {"Check health", "Pay for removal", "Plant sapling", "Pay for protection"};
		int sapling = FarmingKnowledgeFixtures.raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.GROWING, 0);
		int[][] steps = {{60, 60}, {60, 61}, {61, 3}, {3, sapling}};
		String stopName = null;
		for (int i = 0; i < steps.length; i++)
		{
			long now = T0 + (i + 1) * MIN;
			if (i == 0)
			{
				known.put(TREE, known.get(TREE).seenUnchanged(now));
			}
			else
			{
				FarmingKnowledgeFixtures.change(known, TREE, steps[i][0], steps[i][1], now);
			}
			long t0 = System.nanoTime();
			FarmingRunView run = view(intent, now);
			FarmingGuide.Objective o = FarmingGuide.resolve(run, sites, at, at, stopName, prefs, CATALOG, null, stopName);
			worst = Math.max(worst, System.nanoTime() - t0);
			stopName = o.getStop();
			assertEquals("step " + i, FarmingGuide.Kind.ACT, o.getKind());
			assertTrue(o.getStop(), stop.getSite().getName().equals(o.getStop()));
			assertEquals("step " + i, expected[i], o.getAction().getLabel());
		}
		// The game's "You pay the gardener ... to protect the patch." recorded: the stop is finished, the run moves on.
		intent = intent.withProtected(TREE);
		FarmingGuide.Objective after = FarmingGuide.resolve(view(intent, T0 + 6 * MIN), sites, at, at, stopName, prefs, CATALOG, null, stopName);
		assertTrue(after.getKind() + " " + after.getStop(), after.getKind() == FarmingGuide.Kind.TRAVEL && !stop.getSite().getName().equals(after.getStop()));
		System.out.println("RESULT action resolution per evidence change, worst " + worst / 1_000_000.0 + " ms");
		assertTrue("prompt: well inside one tick (" + worst / 1_000_000 + " ms)", worst < 300_000_000L);
	}
}
