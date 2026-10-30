package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.bank;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.patch;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.RequirementSet;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportDef;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The 2026-10-21 scope correction (canonical §28.9): no harvest projection or bank planning, world fairy-ring and
 * spirit-tree journeys without a house facility, guidance to the patch, and History references without double counting.
 */
public class FarmingScopeCorrectionTest
{
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final long HOUR = 3_600_000L;
	private static final long MIN = 60_000L;

	private static FarmingRunIntent run(FarmingRunType type, Map<String, String> plans, String compost)
	{
		Map<String, FarmingRunIntent.Baseline> baseline = new LinkedHashMap<>();
		for (String id : plans.keySet())
		{
			baseline.put(id, FarmingRunIntent.Baseline.of(patch(id), null));
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-scope-" + type, type, NOW, NOW, FarmingRunIntent.State.ACTIVE, null, null,
			new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans, new FarmingRunIntent.Options(compost, false), baseline);
	}

	private static FarmingRunIntent mushroom()
	{
		return run(FarmingRunType.MUSHROOM, Collections.singletonMap("mushroom.canifis", FarmingRunIntent.HARVEST_ONLY), "NONE");
	}

	private static FarmingPreparation.Plan plan(FarmingRunIntent intent, AccountCapability cap, FarmingPreferences prefs)
	{
		return FarmingPreparation.plan(intent.withPreparing(true), CATALOG, cap, TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW),
			EvidenceSource.NONE, prefs, Collections.emptyMap());
	}

	private static ItemNeed need(FarmingPreparation.Plan p, String key)
	{
		return p.getRequest().getNeeds().stream().filter(n -> n.getKey().equals(key)).findFirst().orElse(null);
	}

	/** Ardougne cloak 1 and a dramen staff in the bank; no Lumbridge elite diary; no house facility known. */
	private static AccountCapability cloakAndStaff()
	{
		return maxed().diary("LUMBRIDGE_ELITE", false).bank(bank(HOUR, ItemID.DRAMEN_STAFF, 1, ItemID.ARDY_CAPE_EASY, 1)).build();
	}

	// -- 1. preparation keeps the run's supplies, and nothing about harvest space -------------------------------

	@Test
	public void preparation_keepsSeedsCompostToolsProtectionAndTravel_withNoYieldBankOrNotingSteps()
	{
		Map<String, String> plans = new LinkedHashMap<>();
		plans.put("allotment.falador.nw", "POTATO");
		plans.put("herb.ardougne", "RANARR");
		AccountCapability cap = maxed().inventory(items(ItemID.POTATO_SEED, 3)).bank(bank(HOUR, ItemID.DRAMEN_STAFF, 1, ItemID.ARDY_CAPE_ELITE, 1)).build();
		FarmingPreparation.Plan p = plan(run(FarmingRunType.ALLOTMENT, plans, "ULTRACOMPOST"), cap,
			FarmingPreferences.defaults().withProtect(FarmingRunType.ALLOTMENT, true));
		for (String key : new String[]{"seed.allotment.any", "seed.herb.any", "supply.compost." + ItemID.BUCKET_ULTRACOMPOST, "tool.dibber", "tool.rake", "tool.spade"})
		{
			assertNotNull("kept: " + key, need(p, key));
		}
		assertNotNull("the herb sack stays an optional reminder", need(p, "tool.herb_sack"));
		assertTrue("protection payment for the planted allotment: " + p.getRequest().getNeeds(),
			p.getRequest().getNeeds().stream().anyMatch(n -> "Protection".equals(n.getSection())));
		assertTrue("travel resources: the Ardougne cloak is a need", p.getRequest().getNeeds().stream().anyMatch(n -> n.getKey().startsWith("travel.")));
		for (ItemNeed n : p.getRequest().getNeeds())
		{
			String k = n.getKey().toLowerCase();
			assertFalse("no harvest, bank or noting planning: " + k, k.contains("bank") || k.contains("note") || k.contains("yield") || k.contains("harvest")
				|| k.contains("deposit"));
		}
		PreparationState state = PreparationEvaluator.evaluate(p.getRequest(), cap, NOW);
		FarmingPreparationView v = FarmingPreparationView.of(p, state, false, Collections.emptySet(), NOW);
		List<String> text = new ArrayList<>(v.getNotes());
		text.add(v.getSlots());
		for (FarmingPreparationView.SiteRow r : v.getSites())
		{
			text.add(r.getStatus());
			text.addAll(r.getDetails());
		}
		for (String t : text)
		{
			String l = t.toLowerCase();
			assertFalse("no planner text: " + t, l.contains("harvest") || l.contains("bank stop") || l.contains("deposit") || l.contains("noted")
				|| l.contains("note them") || l.contains("own way"));
		}
	}

	// -- 2. world fairy rings and spirit trees, no house needed -------------------------------------------------

	@Test
	public void worldFairyRing_cloakToTheTowerOfLifeRing_thenCKS_withoutAnyHouseFacility()
	{
		FarmingPreparation.Plan p = plan(mushroom(), cloakAndStaff(), FarmingPreferences.defaults());
		FarmingPreparation.SitePlan site = p.getSites().get(0);
		assertNotNull("a complete world journey is recommended", site.getTransport());
		TransportDef t = site.getTransport().getTransport();
		assertNotNull(t.id, t.via);
		assertEquals("ardougne_cloak.monastery", t.via.entry);
		assertEquals("BIS is in the unicorn pen (no other access): the Tower of Life ring", "ring.djp", t.via.node);
		assertEquals("fairy_ring.cks", t.via.hop);
		for (RequirementSet.EvidenceReq e : RequirementSet.list(t.requirements().evidence))
		{
			assertFalse("no house facility is required: " + e.key, e.key.startsWith("poh."));
		}
		assertNotNull("the staff is a need without the elite diary", need(p, "travel.fairy_staff"));
		AccountCapability elite = maxed().bank(bank(HOUR, ItemID.ARDY_CAPE_EASY, 1)).build();
		FarmingPreparation.Plan waived = plan(mushroom(), elite, FarmingPreferences.defaults());
		assertNotNull(waived.getSites().get(0).getTransport());
		assertNull("Lumbridge & Draynor elite: no staff", need(waived, "travel.fairy_staff"));
		AccountCapability nothing = maxed().diary("LUMBRIDGE_ELITE", false).bank(bank(HOUR, ItemID.DRAMEN_STAFF, 1)).build();
		assertNull("no way to reach any ring: nothing recommended", plan(mushroom(), nothing, FarmingPreferences.defaults()).getSites().get(0).getTransport());
	}

	@Test
	public void everyNetworkJourney_carriesTheEntranceTheRingAndTheDestinationRequirements()
	{
		Map<String, TransportCatalog.NetworkNode> nodes = new LinkedHashMap<>();
		for (TransportCatalog.NetworkNode n : TransportCatalog.nodes())
		{
			nodes.put(n.id, n);
		}
		int journeys = 0;
		boolean kourend = false;
		boolean grownTree = false;
		for (TransportDef t : TRANSPORTS.all())
		{
			if (t.via == null)
			{
				continue;
			}
			journeys++;
			TransportDef entry = TRANSPORTS.get(t.via.entry);
			TransportDef hop = TRANSPORTS.get(t.via.hop);
			TransportCatalog.NetworkNode node = nodes.get(t.via.node);
			assertNotNull(t.id, entry);
			assertNotNull(t.id, hop);
			assertNotNull(t.id, node);
			assertNull("the entrance is reached by a teleport, not by another hop: " + t.id, entry.network);
			assertFalse("the house is its own route family, not an entrance: " + t.id, entry.id.startsWith("poh.tab.fairy_ring") || entry.id.startsWith("poh.spell.fairy_ring"));
			assertEquals(t.id, hop.network, node.network);
			assertEquals("the arrival is the destination's", hop.arrival.x, t.arrival.x);
			assertNotNull("the walk to the entrance is route data: " + t.id, t.walk);
			assertEquals("the staff rule is the destination's", hop.wield == null, t.wield == null);
			List<String> quests = new ArrayList<>();
			RequirementSet.list(t.requirements().quests).forEach(q -> quests.add(q.quest + ":" + q.state));
			for (RequirementSet r : new RequirementSet[]{entry.requirements(), hop.requirements(), node.requirements == null ? new RequirementSet() : node.requirements})
			{
				for (RequirementSet.QuestReq q : RequirementSet.list(r.quests))
				{
					assertTrue(t.id + " keeps " + q.quest, quests.contains(q.quest + ":" + q.state));
				}
				for (RequirementSet.ProofReq pr : RequirementSet.list(r.proofs))
				{
					assertTrue(t.id + " keeps " + pr.name, RequirementSet.list(t.requirements().proofs).stream().anyMatch(x -> x.name.equals(pr.name)));
				}
				for (RequirementSet.EvidenceReq e : RequirementSet.list(r.evidence))
				{
					assertTrue(t.id + " keeps " + e.key, RequirementSet.list(t.requirements().evidence).stream().anyMatch(x -> x.key.equals(e.key)));
				}
			}
			kourend |= "ring.cir".equals(t.via.node) && RequirementSet.list(t.requirements().proofs).stream().anyMatch(x -> x.name.contains("Kourend"));
			grownTree |= "tree.port_sarim".equals(t.via.node) && RequirementSet.list(t.requirements().evidence).stream()
				.anyMatch(x -> x.key.startsWith(FarmingTravel.EVIDENCE_PREFIX));
		}
		assertTrue("world journeys exist: " + journeys, journeys > 100);
		assertTrue("entering at CIR needs Kourend visited", kourend);
		assertTrue("entering at a player-grown spirit tree needs that tree seen grown", grownTree);
	}

	// -- 3. guidance goes all the way to the patch -------------------------------------------------------------

	@Test
	public void guidance_followsTheWholeJourney_walkToTheRing_dial_thenThePatch()
	{
		List<FarmingPreparation.SitePlan> sites = plan(mushroom(), cloakAndStaff(), FarmingPreferences.defaults()).getSites();
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), new LinkedHashMap<>(),
			Collections.emptyList(), mushroom(), new HashSet<>());
		FarmingRunView view = FarmingModel.build(CATALOG, snap, NOW).getRun();
		TransportDef t = sites.get(0).getTransport().getTransport();

		FarmingGuide.Objective far = FarmingGuide.resolve(view, sites, new FarmingGuide.Position(3200, 3200, 0), null, null, FarmingPreferences.defaults(),
			CATALOG);
		assertEquals(FarmingGuide.Kind.TRAVEL, far.getKind());
		assertTrue(far.getChecklist().toString(), far.getChecklist().get(0).startsWith("Use: "));
		assertTrue("every step to the patch is listed: " + far.getChecklist(), far.getChecklist().stream().anyMatch(l -> l.contains("dial CKS")));

		FarmingGuide.Objective landed = FarmingGuide.resolve(view, sites, new FarmingGuide.Position(t.via.entryAt.x, t.via.entryAt.y, 0), null, null,
			FarmingPreferences.defaults(), CATALOG);
		assertTrue(landed.getChecklist().toString(), landed.getChecklist().get(0).startsWith("○ Walk to the Fairy ring DJP"));
		assertEquals("the ring is the target", t.via.nodeAt.x, landed.getTarget().x);

		FarmingGuide.Objective atRing = FarmingGuide.resolve(view, sites, new FarmingGuide.Position(t.via.nodeAt.x, t.via.nodeAt.y, 0), null, null,
			FarmingPreferences.defaults(), CATALOG);
		assertTrue(atRing.getChecklist().toString(), atRing.getChecklist().get(0).startsWith("○ Use the fairy ring: dial CKS"));

		FarmingSites.Tile patch = sites.get(0).getSite().getTile();
		FarmingSnapshot hereSnap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), new LinkedHashMap<>(),
			Collections.emptyList(), mushroom(), new HashSet<>(Collections.singletonList("mushroom.canifis")));
		FarmingGuide.Objective there = FarmingGuide.resolve(FarmingModel.build(CATALOG, hereSnap, NOW).getRun(), sites, new FarmingGuide.Position(patch.x, patch.y, patch.plane), null, null,
			FarmingPreferences.defaults(), CATALOG);
		assertEquals("beside the patch: the work itself", FarmingGuide.Kind.ACT, there.getKind());
		for (String l : there.getChecklist())
		{
			assertFalse(l, l.toLowerCase().contains("bank") || l.toLowerCase().contains("note"));
		}
	}

	@Test
	public void aRecommendedJourney_alwaysEndsBesideThePatch()
	{
		for (FarmingPatchDef p : CATALOG.allPatches())
		{
			FarmingSites.Tile tile = FarmingSites.tileOf(CATALOG, p.getPatchId());
			if (tile == null)
			{
				continue;
			}
			for (String id : FarmingTravel.forStop(CATALOG, p.getPatchId()))
			{
				TransportDef bare = TRANSPORTS.get(id);
				List<TransportDef> routes = new ArrayList<>();
				if (bare.network == null)
				{
					routes.add(bare);
				}
				else
				{
					TRANSPORTS.all().stream().filter(t -> t.via != null && id.equals(t.via.hop)).limit(3).forEach(routes::add);
				}
				for (TransportDef t : routes)
				{
					List<FarmingTravel.Leg> legs = FarmingTravel.journey(CATALOG, p.getPatchId(), t, tile);
					if (!FarmingTravel.legs(CATALOG, p.getPatchId(), id).isEmpty() || !FarmingTravel.legs(CATALOG, p.getPatchId(), t.id).isEmpty())
					{
						continue; // a catalogued journey (boat, dive, quetzal): checked in FarmingTravelTest
					}
					FarmingTravel.Leg last = legs.isEmpty() ? null : legs.get(legs.size() - 1);
					boolean walked = last != null && last.text.equals("Walk to the patch");
					int endX = walked ? last.x : t.arrival.x;
					int endY = walked ? last.y : t.arrival.y;
					int endPlane = walked ? last.plane : t.arrival.plane;
					int left = Math.max(Math.abs(endX - tile.x), Math.abs(endY - tile.y));
					boolean special = false;
					assertTrue(p.getPatchId() + " via " + t.id + ": ends " + left + " tiles from the patch",
						special || endPlane == tile.plane && left <= 20);
				}
			}
		}
	}

	// -- 4. History: one record, one primary placement, references elsewhere ------------------------------------

	@Test
	public void aRunAcrossTwoSessions_isRecordedOnce_andReferencedInTheOther()
	{
		long start = NOW - 60 * MIN;
		long end = NOW - 10 * MIN;
		FarmingRunHistory.Record r = FarmingHistoryFixtures.herbRun(1L, start, end);
		long[] first = {start - 5 * MIN, start + 20 * MIN};
		long[] second = {start + 25 * MIN, NOW};
		List<long[]> sessions = Arrays.asList(first, second);
		FarmingRunHistory.Record inFirst = FarmingRunHistory.placed(r, first[0], first[1], sessions);
		FarmingRunHistory.Record inSecond = FarmingRunHistory.placed(r, second[0], second[1], sessions);
		assertFalse("the first session only references it", inFirst.isPrimary());
		assertTrue("placed where it finished", inSecond.isPrimary());
		assertEquals("the same run", inFirst.getIntentId(), inSecond.getIntentId());
		int primaries = (inFirst.isPrimary() ? 1 : 0) + (inSecond.isPrimary() ? 1 : 0);
		assertEquals("never counted twice", 1, primaries);
		assertEquals(Collections.singletonMap("Ranarr", 1), inFirst.getPlanted());
	}

	/** Guidance follows a live route step by step: the next transition, then the walk; off the route it falls back. */
	@Test
	public void guidance_followsALiveRoute_throughAPlaneChange_andRecoversOffRoute()
	{
		FarmingRunIntent troll = run(FarmingRunType.HERB, Collections.singletonMap("herb.troll_stronghold", FarmingRunIntent.REPLANT_ANY), "NONE");
		AccountCapability cap = maxed().inventory(items(ItemID.STRONGHOLD_TELEPORT_BASALT, 3)).build();
		List<FarmingPreparation.SitePlan> sites = plan(troll, cap, FarmingPreferences.defaults()).getSites();
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), new LinkedHashMap<>(),
			Collections.emptyList(), troll, new HashSet<>());
		FarmingRunView view = FarmingModel.build(CATALOG, snap, NOW).getRun();
		String stop = sites.get(0).getSite().getName();
		com.osrstelemetry.plugin.navigation.RouteTable routes = com.osrstelemetry.plugin.navigation.RouteTable.bundled();
		int start = routes.start("arr:stony_basalt.troll_stronghold");
		com.osrstelemetry.plugin.navigation.LiveRoute live = com.osrstelemetry.plugin.navigation.RoutePlanner.plan(
			com.osrstelemetry.plugin.navigation.Pathfinder.bundled(), stop, start,
			Collections.singletonMap("stop:Troll Stronghold", new com.osrstelemetry.plugin.navigation.RoutePlanner.Goal(routes.goals("stop:Troll Stronghold"), 0)),
			com.osrstelemetry.plugin.navigation.NavAccess.UNGATED, 1000, com.osrstelemetry.plugin.navigation.RoutePlanner.MAX_NODES);
		com.osrstelemetry.plugin.navigation.NavPath path = live.getPath();
		int ladder = -1;
		for (int i = 1; i < path.size(); i++)
		{
			if (path.via(i) != null && path.via(i).instruction().equals("Climb up the troll ladder"))
			{
				ladder = i;
			}
		}
		assertTrue("the route climbs the ladder: " + path.transitions(), ladder > 0);
		FarmingGuide.Position at = pos(path.tile(0));
		FarmingGuide.Objective first = FarmingGuide.resolve(view, sites, at, null, null, FarmingPreferences.defaults(), CATALOG, live);
		assertEquals(FarmingGuide.Kind.TRAVEL, first.getKind());
		assertTrue(first.getChecklist().toString(), first.getChecklist().get(0).equals("○ " + path.transitions().get(0).instruction()));
		assertTrue("one step at a time, the rest as Then", first.getChecklist().stream().anyMatch(l -> l.equals("Then: Climb up the troll ladder")));
		assertEquals("the target is where that step starts", com.osrstelemetry.plugin.navigation.NavPoint.x(path.transitions().get(0).from), first.getTarget().x);
		int inside = -1;
		for (int i = 1; i < path.size() && inside < 0; i++)
		{
			inside = path.via(i) != null ? i : -1;
		}
		FarmingGuide.Objective in = FarmingGuide.resolve(view, sites, pos(path.tile(inside)), null, stop, FarmingPreferences.defaults(), CATALOG, live);
		assertEquals("observed inside the stronghold: the ladder is next", "○ Climb up the troll ladder", in.getChecklist().get(0));
		FarmingGuide.Objective onTop = FarmingGuide.resolve(view, sites, pos(path.tile(ladder)), null, stop, FarmingPreferences.defaults(), CATALOG, live);
		assertTrue(onTop.getChecklist().toString(), onTop.getChecklist().get(0).startsWith("○ Walk to the patch"));
		assertNotNull("the route is drawn", onTop.getRoute());
		FarmingGuide.Objective lost = FarmingGuide.resolve(view, sites, new FarmingGuide.Position(3222, 3218, 0), null, stop, FarmingPreferences.defaults(),
			CATALOG, live);
		assertNull("off the route: not followed (guidance asks for a new one)", lost.getRoute());
	}

	@Test
	public void hespori_isReachedThroughTheCave_toTheSideOfThePatch_andNothingAboutTheFight()
	{
		String patch = "hespori.farming_guild";
		FarmingRunIntent intent = run(FarmingRunType.HESPORI, Collections.singletonMap(patch, FarmingRunIntent.REPLANT_ANY), "NONE");
		AccountCapability cap = maxed().build();
		List<FarmingPreparation.SitePlan> sites = plan(intent, cap, FarmingPreferences.defaults()).getSites();
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, FarmingOwner.standard(FarmingFixtures.ACCOUNT), new LinkedHashMap<>(),
			Collections.emptyList(), intent, new HashSet<>());
		FarmingRunView view = FarmingModel.build(CATALOG, snap, NOW).getRun();
		String stop = sites.get(0).getSite().getName();
		String key = FarmingTravel.stopKey(CATALOG, patch);
		assertEquals("its own destination, not the guild's other patches", "stop:Farming Guild#hespori.farming_guild", key);
		com.osrstelemetry.plugin.navigation.RouteTable routes = com.osrstelemetry.plugin.navigation.RouteTable.bundled();
		int[] goals = routes.goals(key);
		assertTrue(goals.length > 0);
		for (int g : goals)
		{
			int dx = Math.abs(com.osrstelemetry.plugin.navigation.NavPoint.x(g) - 1247);
			int dy = Math.abs(com.osrstelemetry.plugin.navigation.NavPoint.y(g) - 10087);
			assertEquals("every goal is beside the 3x3 patch (one tile off its edge), on the cave floor", 2, Math.max(dx, dy));
			assertEquals(0, com.osrstelemetry.plugin.navigation.NavPoint.plane(g));
		}
		int start = routes.start("arr:skills_necklace.farming_guild");
		com.osrstelemetry.plugin.navigation.LiveRoute live = com.osrstelemetry.plugin.navigation.RoutePlanner.plan(
			com.osrstelemetry.plugin.navigation.Pathfinder.bundled(), stop, start,
			Collections.singletonMap(key, new com.osrstelemetry.plugin.navigation.RoutePlanner.Goal(goals, 0)),
			com.osrstelemetry.plugin.navigation.NavAccess.of(cap), 2000, com.osrstelemetry.plugin.navigation.RoutePlanner.MAX_NODES);
		assertEquals(com.osrstelemetry.plugin.navigation.NavOutcome.FOUND, live.getOutcome());
		com.osrstelemetry.plugin.navigation.NavPath path = live.getPath();
		int cave = -1;
		for (int i = 1; i < path.size(); i++)
		{
			if (path.via(i) != null && path.via(i).requirement.toString().contains("65 Farming"))
			{
				cave = i;
			}
		}
		assertTrue("through the cave entrance (65 Farming): " + path.transitions(), cave > 0);
		com.osrstelemetry.plugin.navigation.Transition enter = path.via(cave);
		assertEquals("Enter", enter.option);
		assertTrue("lands inside the chamber", enter.arrivedAt(path.tile(cave)));
		assertFalse("standing outside the cave is not inside", enter.arrivedAt(enter.from));
		int last = path.tile(path.size() - 1);
		assertTrue("ends beside the patch: " + com.osrstelemetry.plugin.navigation.NavPoint.text(last), contains(goals, last));
		assertTrue("a short walk inside, not a detour", path.size() - cave < 20);

		FarmingGuide.Objective first = FarmingGuide.resolve(view, sites, pos(path.tile(0)), null, null, FarmingPreferences.defaults(), CATALOG, live);
		assertEquals(FarmingGuide.Kind.TRAVEL, first.getKind());
		assertTrue(first.getChecklist().toString(), first.getChecklist().stream().anyMatch(l -> l.contains(enter.instruction())));
		FarmingGuide.Objective inside = FarmingGuide.resolve(view, sites, pos(path.tile(cave)), null, stop, FarmingPreferences.defaults(), CATALOG, live);
		assertTrue(inside.getChecklist().toString(), inside.getChecklist().get(0).startsWith("○ Walk to the patch"));
		FarmingGuide.Objective there = FarmingGuide.resolve(view, sites, pos(last), null, stop, FarmingPreferences.defaults(), CATALOG, live);
		for (FarmingGuide.Objective o : new FarmingGuide.Objective[]{first, inside, there})
		{
			assertTrue("reaching the patch only; the fight is the player's: " + o.getChecklist(), o.getChecklist().stream()
				.noneMatch(l -> l.matches("(?i).*(fight|attack|kill|combat|prayer|flower).*")));
		}

		// 64 Farming: the cave is closed to this account (a real lock, not missing data), while the walk itself exists.
		AccountCapability low = maxed().level(net.runelite.api.Skill.FARMING, 64).build();
		com.osrstelemetry.plugin.navigation.AccountRouter router = com.osrstelemetry.plugin.navigation.AccountRouter.bundled();
		assertEquals(com.osrstelemetry.plugin.navigation.NavOutcome.FOUND, routes.existence(key, "arr:skills_necklace.farming_guild"));
		assertEquals(com.osrstelemetry.plugin.navigation.NavOutcome.UNREACHABLE,
			router.walk(key, "arr:skills_necklace.farming_guild", router.planning(low)).getOutcome());
		assertEquals(com.osrstelemetry.plugin.navigation.NavOutcome.FOUND,
			router.walk(key, "arr:skills_necklace.farming_guild", router.planning(cap)).getOutcome());
	}

	@Test
	public void anglersRetreat_aCompleteJourneyWithTheRowboat_andAGuidanceGapIsNeverCalledLocked()
	{
		String patch = "hardwood.anglers_retreat";
		FarmingRunIntent intent = run(FarmingRunType.HARDWOOD, Collections.singletonMap(patch, FarmingRunIntent.REPLANT_ANY), "NONE");
		java.util.function.Function<AccountCapability.Builder, FarmingPreparation.SitePlan> site = b -> plan(intent,
			b.inventory(items(ItemID.POH_TABLET_ARDOUGNETELEPORT, 3, ItemID.COINS, 1000)).build(), FarmingPreferences.defaults()).getSites().get(0);

		// Rowboat built (the Sailing amenity): tablet, Captain Barnaby, Cabin Boy Colin, the rowboat, then the patch.
		FarmingPreparation.SitePlan built = site.apply(maxed().varbit(18355, 1));
		assertNotNull(built.getTransport());
		List<String> legs = new ArrayList<>();
		built.getLegs().forEach(l -> legs.add(l.toString()));
		assertTrue(legs.toString(), legs.stream().anyMatch(l -> l.startsWith("Travel with Cabin Boy Colin")));
		assertTrue(legs.toString(), legs.stream().anyMatch(l -> l.startsWith("Take the rowboat")));
		assertTrue(legs.toString(), legs.get(legs.size() - 1).startsWith("Walk to the patch"));
		assertTrue(built.getAccessUnmet().isEmpty());

		// Not built, or not seen yet: the patch is the account's (51 Sailing), only the guided way is closed. A gap in guidance,
		// not a locked patch; what would open the guided way is said, and "not seen" stays unknown.
		FarmingPreparation.SitePlan notBuilt = site.apply(maxed().varbit(18355, 0));
		assertNull(notBuilt.getTransport());
		assertEquals("no guided route to this patch yet", notBuilt.getNote());
		assertTrue(notBuilt.getAccessUnmet().isEmpty());
		assertEquals(Collections.singletonList("Ardougne teleport: the way from there needs the Anglers' Retreat rowboat built"), notBuilt.getClosedWays());
		FarmingPreparation.SitePlan unseen = site.apply(maxed());
		assertTrue(unseen.getClosedWays().toString(), unseen.getClosedWays().get(0).endsWith("rowboat built (not seen yet)"));
		// Built but The Corsair Curse not done: only the missing part is named, never what is already met.
		AccountCapability.Builder noQuest = maxed().varbit(18355, 1);
		noQuest.quest(net.runelite.api.Quest.THE_CORSAIR_CURSE, net.runelite.api.QuestState.IN_PROGRESS);
		FarmingPreparation.SitePlan quest = site.apply(noQuest);
		assertTrue(quest.getClosedWays().toString(), quest.getClosedWays().stream().allMatch(w -> w.endsWith("needs The Corsair Curse")));

		// Below 51 Sailing the patch itself is closed to the account: that is the access line, not travel.
		FarmingPreparation.SitePlan low = site.apply(maxed().level(net.runelite.api.Skill.SAILING, 50).varbit(18355, 1));
		assertFalse(low.getAccessUnmet().toString(), low.getAccessUnmet().isEmpty());
	}

	private static boolean contains(int[] goals, int packed)
	{
		for (int g : goals)
		{
			if (g == packed)
			{
				return true;
			}
		}
		return false;
	}

	private static FarmingGuide.Position pos(int packed)
	{
		return new FarmingGuide.Position(com.osrstelemetry.plugin.navigation.NavPoint.x(packed), com.osrstelemetry.plugin.navigation.NavPoint.y(packed),
			com.osrstelemetry.plugin.navigation.NavPoint.plane(packed));
	}
}
