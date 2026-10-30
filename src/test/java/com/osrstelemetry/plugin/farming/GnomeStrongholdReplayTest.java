package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import com.osrstelemetry.plugin.account.EvidenceSource;
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
 * Josh's Gnome Stronghold tree, 2026-10-22 13:28 UTC (run fr-20261022T132552Z-tree), replayed from his own observations
 * (observations.jsonl): last seen at Start growing (raw 49, 7.5 h earlier); at the patch 60 (check health), 61 (checked);
 * removal inside the gardener's dialogue (a modal: the next read is a baseline of 3); planted 48; protection paid in the next
 * dialogue. The guide was right at every step in both the current and the pass-24 code (replayed); what was wrong was where
 * the stop was: the tree took the Gnome Stronghold fruit tree's map marker (2472,3445, 45 tiles away), so the walk was drawn
 * to the fruit tree and the "Pay" gardener was looked for around the fruit tree (Bolongo, not Prissy Scilla). Canonical §28.16.
 */
public class GnomeStrongholdReplayTest
{
	private static final FarmingPatchCatalog C = FarmingPatchCatalog.standard();
	private static final String TREE = "tree.gnome_stronghold";
	private final Map<String, PatchKnowledge> known = new HashMap<>();

	private void obs(boolean change, int raw, int prev, long at, long seq)
	{
		FarmingPatchDef p = patch(TREE);
		PatchObservation o = change
			? new PatchObservation(PatchObservation.Kind.CHANGE, p, FarmingDecoder.decode(p.getType(), raw), FarmingDecoder.decode(p.getType(), prev), at, at - 600,
			seq)
			: new PatchObservation(PatchObservation.Kind.BASELINE, p, FarmingDecoder.decode(p.getType(), raw), null, at, 0L, seq);
		known.put(TREE, CropCycleAttribution.apply(known.get(TREE), o));
	}

	@Test
	public void theTreeIsItsOwnStop_atItsOwnMarker_andEveryStepStaysThere_untilProtectionIsPaid()
	{
		assertEquals("the tree's own marker (RuneLite FarmingPatchLocation TREE)", "2434,3418,0", FarmingSites.tileOf(C, TREE).toString());
		assertEquals("2472,3445,0", FarmingSites.tileOf(C, "fruit_tree.gnome_stronghold").toString());
		assertFalse("38 tiles apart: two stops", FarmingSites.stopOf(C, TREE).equals(FarmingSites.stopOf(C, "fruit_tree.gnome_stronghold")));

		long start = 1792675552000L;
		obs(false, 48, 0, 1792647868474L, 34);
		obs(false, 49, 0, 1792648753555L, 80);
		Map<String, String> plans = new LinkedHashMap<>();
		Map<String, FarmingRunIntent.Baseline> base = new LinkedHashMap<>();
		plans.put(TREE, FarmingRunIntent.REPLANT_ANY);
		base.put(TREE, FarmingRunIntent.Baseline.of(patch(TREE), known.get(TREE)));
		plans.put("tree.varrock", FarmingRunIntent.REPLANT_ANY);
		FarmingRunIntent intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-20261022T132552Z-tree", FarmingRunType.TREE, start, start,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options("NONE", true), base).withExtras(Collections.emptyList(), null, Collections.singletonList("TREE"));
		AccountCapability cap = AccountFixtures.maxed().build();
		List<FarmingPreparation.SitePlan> sites = FarmingPreparation.plan(intent.withPreparing(true), C, cap,
			TransportEvaluator.evaluateAll(com.osrstelemetry.plugin.travel.TestTransports.CATALOG, cap, EvidenceSource.NONE, start), EvidenceSource.NONE).getSites();
		FarmingPreparation.SitePlan stop = sites.stream().filter(s -> s.getSite().getPatchIds().contains(TREE)).findFirst().orElseThrow(AssertionError::new);
		assertEquals("Gnome Stronghold · tree", stop.getSite().getName());
		// Standing at the tree, by Prissy Scilla (about 2436,3415).
		FarmingGuide.Position at = new FarmingGuide.Position(2436, 3416, 0);
		FarmingPreferences prefs = FarmingPreferences.defaults();
		Object[][] steps = {
			{false, 60, 0, 1792675748729L, 108L, "Check health", FarmingAction.Target.Kind.PATCH},
			{true, 61, 60, 1792675763110L, 108L, "Pay for removal", FarmingAction.Target.Kind.NPC},
			{false, 3, 0, 1792675768510L, 109L, "Plant sapling", FarmingAction.Target.Kind.PATCH},
			{true, 48, 3, 1792675771508L, 109L, "Pay for protection", FarmingAction.Target.Kind.NPC},
		};
		String acted = null;
		com.osrstelemetry.plugin.navigation.LiveRoute pending = pendingRouteElsewhere();
		for (Object[] s : steps)
		{
			obs((Boolean) s[0], (Integer) s[1], (Integer) s[2], (Long) s[3], (Long) s[4]);
			FarmingRunIntent ev = FarmingRunEvaluator.withEvidence(intent, C, known);
			FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
				new LinkedHashMap<>(known), Collections.emptyList(), ev, new HashSet<>(Arrays.asList(TREE)));
			FarmingRunView run = FarmingModel.build(C, snap, (Long) s[3]).getRun();
			// A route search for elsewhere that finished meanwhile never replaces the patch's action.
			FarmingGuide.Objective o = FarmingGuide.resolve(run, sites, at, at, acted, prefs, C, pending, acted);
			acted = o.getStop();
			assertEquals("raw " + s[1], FarmingGuide.Kind.ACT, o.getKind());
			assertEquals(stop.getSite().getName(), o.getStop());
			assertEquals("raw " + s[1], s[5], o.getAction().getLabel());
			assertEquals(s[6], o.getAction().getTarget().kind);
			if (o.getAction().getTarget().kind == FarmingAction.Target.Kind.NPC)
			{
				assertEquals("the gardener looked for at the tree, not the fruit tree", 2434, o.getAction().getTarget().x);
				assertEquals(3418, o.getAction().getTarget().y);
			}
			assertEquals("the walk target is the tree", 2434, o.getTarget().x);
			RunExtras.protectionDue(ev, FarmingRunEvaluator.evaluate(ev, C, known));
			intent = ev;
		}
		FarmingRunEvaluator.Progress p = FarmingRunEvaluator.evaluate(intent, C, known);
		assertEquals("protection is still due after planting", Collections.singletonList(TREE), RunExtras.protectionDue(intent, p));
		intent = intent.withProtected(TREE);
		assertTrue("paid: nothing due", RunExtras.protectionDue(intent, FarmingRunEvaluator.evaluate(intent, C, known)).isEmpty());
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT),
			new LinkedHashMap<>(known), Collections.emptyList(), intent, new HashSet<>(Arrays.asList(TREE)));
		FarmingGuide.Objective next = FarmingGuide.resolve(FarmingModel.build(C, snap, 1792675790000L).getRun(), sites, at, at, acted, prefs, C, null, acted);
		assertEquals("then on to Varrock", FarmingGuide.Kind.TRAVEL, next.getKind());
	}

	private static com.osrstelemetry.plugin.navigation.LiveRoute pendingRouteElsewhere()
	{
		com.osrstelemetry.plugin.navigation.RouteTable routes = com.osrstelemetry.plugin.navigation.RouteTable.bundled();
		int start = routes.start("arr:spell.varrock");
		String key = "stop:" + FarmingSites.stopOf(C, "tree.varrock");
		com.osrstelemetry.plugin.navigation.LiveRoute live = com.osrstelemetry.plugin.navigation.RoutePlanner.plan(
			com.osrstelemetry.plugin.navigation.Pathfinder.bundled(), FarmingSites.stopOf(C, "tree.varrock"), start,
			Collections.singletonMap(key, new com.osrstelemetry.plugin.navigation.RoutePlanner.Goal(routes.goals(key), 0)),
			com.osrstelemetry.plugin.navigation.NavAccess.UNGATED, 1000, com.osrstelemetry.plugin.navigation.RoutePlanner.MAX_NODES);
		assertNotNull(live);
		return live;
	}
}
