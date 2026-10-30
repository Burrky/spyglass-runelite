package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.bank;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.NeedStatus;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/** Farming's preparation derivation (Phase H): supplies, tools, travel choice and needs, access, notes. */
public class FarmingPreparationTest
{
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final long HOUR = 3_600_000L;

	private static FarmingRunIntent intent(String compost, boolean protection, Map<String, String> planned, String... skipped)
	{
		List<String> included = new java.util.ArrayList<>(planned.keySet());
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-herb", FarmingRunType.HERB, NOW, NOW, FarmingRunIntent.State.ACTIVE, null, null,
			included, Collections.emptyList(), Arrays.asList(skipped), planned, new FarmingRunIntent.Options(compost, protection),
			Collections.emptyMap()).withPreparing(true);
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
		return FarmingPreparation.plan(intent, CATALOG, cap, TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW), EvidenceSource.NONE);
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

	@Test
	public void seeds_compost_andTools_followThePlanAndUnlocks()
	{
		FarmingRunIntent run = intent("ULTRACOMPOST", false, herbs("RANARR", "herb.falador", "herb.catherby", "herb.ardougne"));
		FarmingPreparation.Plan p = plan(run, maxed().spellbookRaw(0).build());
		// Correction pass 2: a generic category, never a crop: any herb seed the level allows.
		ItemNeed seeds = need(p, "seed.herb.any");
		assertEquals(3, seeds.getQuantity());
		assertEquals("Herb seeds", seeds.getLabel());
		assertTrue(seeds.accepts(ItemID.RANARR_SEED) && seeds.accepts(ItemID.TORSTOL_SEED));
		assertEquals(3, need(p, "supply.compost." + ItemID.BUCKET_ULTRACOMPOST).getQuantity());
		assertEquals("Ultracompost", need(p, "supply.compost." + ItemID.BUCKET_ULTRACOMPOST).getLabel());
		assertNotNull("unlock not read: the dibber stays", need(p, "tool.dibber"));
		assertNotNull(need(p, "tool.rake"));
		assertEquals(ItemNeed.Necessity.RECOMMENDED, need(p, "tool.rake").getNecessity());

		AccountCapability unlocked = maxed().spellbookRaw(0).varbit(VarbitID.BRUT_FARMING_PLANTING, 3).varbit(VarbitID.FARMING_BLOCKWEEDS, 2).build();
		FarmingPreparation.Plan q = plan(run, unlocked);
		assertNull("Barbarian planting: no dibber", need(q, "tool.dibber"));
		assertNull("weeds blocked: no rake", need(q, "tool.rake"));
		assertNotNull(need(q, "tool.spade"));
	}

	@Test
	public void allotmentsTakeThreeSeeds_andUnlistedCropsAreNotedNotGuessed()
	{
		Map<String, String> planned = new LinkedHashMap<>();
		planned.put("allotment.falador.nw", "POTATO");
		planned.put("allotment.falador.se", "POTATO");
		planned.put("herb.catherby", "GOUTWEED");
		planned.put("herb.ardougne", FarmingRunIntent.HARVEST_ONLY);
		FarmingPreparation.Plan none = plan(intent("COMPOST", true, planned), maxed().build());
		assertEquals("3 per allotment, whatever the crop", 6, need(none, "seed.allotment.any").getQuantity());
		assertEquals(1, need(none, "seed.herb.any").getQuantity());
		assertEquals("three planted patches; harvest-only takes none", 3, need(none, "supply.compost." + ItemID.BUCKET_COMPOST).getQuantity());
		assertNull("no seeds carried: no payment is guessed", need(none, "payment." + ItemID.BUCKET_COMPOST));
		assertTrue(none.getNotes().toString(), none.getNotes().toString().contains("the payment is listed once your allotment seeds are in your inventory"));
		// Potato seeds carried: potatoes are protected with 2 buckets of compost per patch; the run also composts 3 patches
		// with the same item, so the payment row asks for the total (carried buckets are never counted twice over).
		FarmingPreparation.Plan p = plan(intent("COMPOST", true, planned), maxed().inventory(items(ItemID.POTATO_SEED, 6)).build());
		ItemNeed pay = need(p, "payment." + ItemID.BUCKET_COMPOST);
		assertEquals(2 * 2 + 3, pay.getQuantity());
		assertTrue("noted payment is accepted", pay.accepts(ItemID.Cert.BUCKET_COMPOST));
		assertEquals(ItemNeed.Necessity.RECOMMENDED, pay.getNecessity());
	}

	@Test
	public void travel_choosesPerSite_andBecomesNeeds()
	{
		AccountCapability cap = maxed().spellbookRaw(0).worn(items(ItemID.ARDY_CAPE_MEDIUM, 1))
			.bank(bank(HOUR, ItemID.POH_TABLET_FALADORTELEPORT, 20, ItemID.LAWRUNE, 500, ItemID.AIRRUNE, 5000)).build();
		FarmingRunIntent run = intent("NONE", false, herbs(FarmingRunIntent.HARVEST_ONLY, "herb.falador", "herb.ardougne", "herb.catherby"));
		FarmingPreparation.Plan p = plan(run, cap);
		Map<String, String> how = new LinkedHashMap<>();
		for (FarmingPreparation.SitePlan s : p.getSites())
		{
			how.put(s.getSite().getName(), s.getTransport() == null ? null : s.getTransport().getTransport().id);
		}
		// Stops are named with their patch type where an area has more than one stop.
		assertEquals(how.toString(), "ardougne_cloak.farm", how.get("Ardougne \u00b7 herb"));
		assertEquals("Camelot: runes in the bank are fetchable", "spell.camelot", how.get("Catherby \u00b7 herb"));
		assertNotNull(how.get("Falador \u00b7 herb"));
		ItemNeed cloak = need(p, "travel.ardougne_cloak.farm");
		assertNotNull(cloak);
		assertTrue(cloak.accepts(ItemID.ARDY_CAPE_ELITE));
		assertEquals(ItemNeed.SatisfiedBy.INVENTORY_OR_WORN, cloak.getSatisfiedBy());
		assertNotNull("spell runes are aggregated", need(p, "rune." + ItemID.LAWRUNE));
		PreparationState state = PreparationEvaluator.evaluate(p.getRequest(), cap, NOW);
		for (com.osrstelemetry.plugin.prep.NeedResult r : state.getResults())
		{
			if (r.getNeed().getKey().equals("travel.ardougne_cloak.farm"))
			{
				assertEquals(NeedStatus.CARRIED, r.getStatus());
			}
		}
		assertNull("harvest only: no seeds, no compost, no dibber", need(p, "tool.dibber"));
	}

	@Test
	public void aFairyRing_isRecommendedOnlyByWayOfAnObservedHouseRing_andItsStaffIsANeed_unlessTheEliteDiaryWaivesIt()
	{
		AccountCapability noElite = maxed().diary("LUMBRIDGE_ELITE", false).bank(bank(HOUR, ItemID.DRAMEN_STAFF, 1, ItemID.POH_TABLET_TELEPORTTOHOUSE, 5))
			.build();
		FarmingRunIntent mush = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-m", FarmingRunType.MUSHROOM, NOW, NOW,
			FarmingRunIntent.State.ACTIVE, null, null, Collections.singletonList("mushroom.canifis"), Collections.emptyList(), Collections.emptyList(),
			Collections.singletonMap("mushroom.canifis", FarmingRunIntent.HARVEST_ONLY), new FarmingRunIntent.Options("NONE", false), Collections.emptyMap());
		assertNull("no way to the network is known: no fairy ring recommendation", plan(mush, noElite).getSites().get(0).getTransport());
		com.osrstelemetry.plugin.account.EvidenceSource houseRing = new com.osrstelemetry.plugin.account.EvidenceSource()
		{
			@Override
			public com.osrstelemetry.plugin.account.Known get(String key)
			{
				return "poh.fairy_ring".equals(key) ? com.osrstelemetry.plugin.account.Known.YES : com.osrstelemetry.plugin.account.Known.UNKNOWN;
			}

			@Override
			public String explain(String key)
			{
				return "test";
			}
		};
		java.util.function.Function<AccountCapability, FarmingPreparation.Plan> withRing = cap -> FarmingPreparation.plan(mush, CATALOG, cap,
			TransportEvaluator.evaluateAll(TRANSPORTS, cap, houseRing, NOW), houseRing);
		FarmingPreparation.Plan p = withRing.apply(noElite);
		assertEquals("poh.tab.fairy_ring.cks", p.getSites().get(0).getTransport().getTransport().id);
		assertNotNull(need(p, "travel.fairy_staff"));
		assertNull(need(withRing.apply(maxed().bank(bank(HOUR, ItemID.POH_TABLET_TELEPORTTOHOUSE, 5)).build()), "travel.fairy_staff"));
	}

	@Test
	public void patchAccess_unmetIsWarned_missingIsExplained_neverAssumed()
	{
		FarmingRunIntent run = intent("NONE", false, herbs(FarmingRunIntent.HARVEST_ONLY, "herb.weiss", "herb.troll_stronghold", "herb.farming_guild"));
		AccountCapability cap = maxed().quest(Quest.MAKING_FRIENDS_WITH_MY_ARM, QuestState.IN_PROGRESS).build();
		FarmingPreparation.Plan p = plan(run, cap);
		assertTrue(p.getAccessUnmet().toString(), p.getAccessUnmet().toString().contains("Weiss: needs Making Friends with My Arm not complete"));
		AccountCapability unread = com.osrstelemetry.plugin.account.AccountFixtures.base(99).settled(false).build();
		FarmingPreparation.Plan q = plan(run, unread);
		assertTrue(q.getAccessUnmet().isEmpty());
		assertTrue(q.getAccessUnknown().toString(), q.getAccessUnknown().toString().contains("Troll Stronghold: access not confirmed"));
	}

	@Test
	public void skippedPatches_needNothing_andOnlyTheSuggestedOrderIsStored() throws Exception
	{
		FarmingRunIntent run = intent("ULTRACOMPOST", false, herbs("RANARR", "herb.falador", "herb.catherby"), "herb.catherby");
		FarmingPreparation.Plan p = plan(run, maxed().build());
		assertEquals(1, need(p, "seed.herb.any").getQuantity());
		assertEquals(1, p.getSites().size());
		for (Field f : FarmingRunIntent.class.getDeclaredFields())
		{
			// Correction pass: the one suggested order is saved so the run page shows the same order after Begin and a
			// restart. It is a suggestion only: no cursor, "next" or route is ever stored.
			assertFalse("no cursor / next / route in the intent: " + f.getName(),
				f.getName().toLowerCase().matches(".*(cursor|next|current|route).*") || (f.getName().toLowerCase().contains("order")
					&& !f.getName().equals("suggestedOrder")));
		}
	}

	@Test
	public void everyHerbPatch_hasASiteTile_inItsOwnRegions_andEverySeedNameResolves() throws Exception
	{
		for (FarmingPatchDef def : CATALOG.allPatches())
		{
			if (def.getType() == FarmingPatchType.HERB || def.getType() != FarmingPatchType.HESPORI && FarmingTravel.destinations().containsKey(def.getPatchId()))
			{
				FarmingSites.Tile t = FarmingSites.tileOf(CATALOG, def.getPatchId());
				assertNotNull(def.getPatchId(), t);
				int region = FarmingSites.region(t.x, t.y);
				boolean inside = false;
				for (int r : CATALOG.regionsOf(def.getPatchId()))
				{
					inside |= r == region;
				}
				assertTrue(def.getPatchId(), inside);
			}
		}
		for (FarmingSeeds.Seed s : FarmingSeeds.all().values())
		{
			assertEquals(s.getGameval(), ItemID.class.getField(s.getGameval()).getInt(null), s.getItemId());
		}
	}

	@Test
	public void treeClearing_paidNeedsCoins_manualNeedsAnAxe_neverBoth()
	{
		FarmingRunIntent trees = intent(null, false, herbs(FarmingRunIntent.REPLANT_ANY, "tree.falador", "tree.varrock"));
		AccountCapability cap = maxed().build();
		java.util.function.Function<FarmingPreferences, FarmingPreparation.Plan> planWith = prefs -> FarmingPreparation.plan(trees, CATALOG, cap,
			TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW), EvidenceSource.NONE, prefs, Collections.emptyMap());
		FarmingPreparation.Plan paid = planWith.apply(FarmingPreferences.defaults());
		assertNotNull(need(paid, "payment.removal_coins"));
		assertEquals("200 per tree not seen yet (could be standing)", 400, need(paid, "payment.removal_coins").getQuantity());
		assertNull(need(paid, "tool.axe"));
		FarmingPreparation.Plan manual = planWith.apply(FarmingPreferences.defaults().withTreeClearing(FarmingPreferences.TreeClearing.CHOP));
		assertNotNull(need(manual, "tool.axe"));
		assertNull(need(manual, "payment.removal_coins"));
		assertNotNull("the stump is dug up", need(manual, "tool.spade"));
		assertNull("saplings only: no dibber", need(manual, "tool.dibber"));
	}

	@Test
	public void allotmentsAndFlowers_areIndependent_inACombinedRun()
	{
		FarmingRunIntent both = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-af", FarmingRunType.ALLOTMENT, NOW, NOW,
			FarmingRunIntent.State.ACTIVE, null, null, Arrays.asList("allotment.falador.nw", "allotment.falador.se", "flower.falador", "herb.falador"),
			Collections.emptyList(), Collections.emptyList(), herbs(FarmingRunIntent.REPLANT_ANY, "allotment.falador.nw", "allotment.falador.se",
				"flower.falador", "herb.falador"), new FarmingRunIntent.Options(null, false), Collections.emptyMap()).withPreparing(true);
		FarmingPreparation.Plan p = plan(both, maxed().build());
		assertNotNull(need(p, "seed.allotment.any"));
		assertEquals("3 seeds per allotment patch", 6, need(p, "seed.allotment.any").getQuantity());
		assertNotNull(need(p, "seed.flower.any"));
		assertEquals(1, need(p, "seed.flower.any").getQuantity());
		assertNotNull(need(p, "seed.herb.any"));
		long falador = p.getSites().stream().filter(s -> s.getSite().getName().startsWith("Falador")).count();
		assertTrue("nearby patches share stops: " + falador, falador >= 1 && falador <= 2);
	}

	@Test
	public void spiritTreeProtection_isThreeItems_fromTheSaplingCarried()
	{
		FarmingRunIntent spirit = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-st", FarmingRunType.SPIRIT, NOW, NOW, FarmingRunIntent.State.ACTIVE,
			null, null, Collections.singletonList("spirit_tree.port_sarim"), Collections.emptyList(), Collections.emptyList(),
			Collections.singletonMap("spirit_tree.port_sarim", FarmingRunIntent.REPLANT_ANY), new FarmingRunIntent.Options(null, false),
			Collections.emptyMap()).withPreparing(true);
		AccountCapability cap = maxed().inventory(items(ItemID.PLANTPOT_SPIRIT_TREE_SAPLING, 1)).build();
		FarmingPreparation.Plan p = FarmingPreparation.plan(spirit, CATALOG, cap, TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW),
			EvidenceSource.NONE, FarmingPreferences.defaults().withProtect(FarmingRunType.SPIRIT, true), Collections.emptyMap());
		assertEquals(5, need(p, "payment." + ItemID.MM_MONKEY_NUTS).getQuantity());
		assertEquals(1, need(p, "payment." + ItemID.MM_MONKEY_BAR).getQuantity());
		assertEquals(1, need(p, "payment." + ItemID.LUNAR_GROUNDTOOTH).getQuantity());
		assertEquals("dig up the tree with a spade", FarmingPreferences.manualClearing(FarmingPatchType.SPIRIT_TREE));
		assertEquals("cut the tree down, then clear it with a spade", FarmingPreferences.manualClearing(FarmingPatchType.CELASTRUS));
		assertEquals("wiki-verified 2026-10-22: a crystal tree is chopped down with an axe", "chop the tree down with an axe",
			FarmingPreferences.manualClearing(FarmingPatchType.CRYSTAL_TREE));
		// Wiki (Farming, 2026-10-22): regular, fruit and hardwood trees can be removed by paying a gardener 200 coins; a redwood
		// only by paying Alexandra 2,000; the others are cleared by hand.
		assertTrue(FarmingPreferences.paidRemovalOffered(FarmingPatchType.HARDWOOD_TREE));
		assertTrue(FarmingPreferences.paidRemovalOffered(FarmingPatchType.TREE));
		assertTrue(FarmingPreferences.paidRemovalOffered(FarmingPatchType.FRUIT_TREE));
		for (FarmingPatchType t : new FarmingPatchType[]{FarmingPatchType.CALQUAT, FarmingPatchType.SPIRIT_TREE, FarmingPatchType.CELASTRUS,
			FarmingPatchType.CRYSTAL_TREE, FarmingPatchType.REDWOOD})
		{
			assertFalse(t.name(), FarmingPreferences.paidRemovalOffered(t));
		}
		assertEquals(200, FarmingPreferences.removalCoins(FarmingPatchType.HARDWOOD_TREE));
		assertEquals(2000, FarmingPreferences.removalCoins(FarmingPatchType.REDWOOD));
		assertEquals("a redwood is only ever removed by paying", FarmingPreferences.TreeClearing.PAY,
			FarmingPreferences.defaults().clearingFor(FarmingPatchType.REDWOOD));
	}

	private static FarmingPreparation.Plan hardwood(AccountCapability cap, String... ids)
	{
		Map<String, String> planned = new LinkedHashMap<>();
		for (String id : ids)
		{
			planned.put(id, FarmingRunIntent.REPLANT_ANY);
		}
		FarmingRunIntent run = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-test-hw", FarmingRunType.HARDWOOD, NOW, NOW,
			FarmingRunIntent.State.ACTIVE, null, null, Arrays.asList(ids), Collections.emptyList(), Collections.emptyList(), planned,
			new FarmingRunIntent.Options(null, false), Collections.emptyMap()).withPreparing(true);
		return FarmingPreparation.plan(run, CATALOG, cap, TransportEvaluator.evaluateAll(TRANSPORTS, cap, EvidenceSource.NONE, NOW), EvidenceSource.NONE,
			FarmingPreferences.defaults().withProtect(FarmingRunType.HARDWOOD, true), Collections.emptyMap());
	}

	@Test
	public void hardwoodProtection_isPaymentRows_likeMagicTrees_fromTheSaplingsCarried()
	{
		// Live finding 2026-10-22: hardwood protection was only a footer note. One teak and one mahogany for two patches:
		// each sapling is planted somewhere, so both payments are certain.
		FarmingPreparation.Plan p = hardwood(maxed().inventory(items(ItemID.PLANTPOT_TEAK_SAPLING, 1, ItemID.PLANTPOT_MAHOGANY_SAPLING, 1)).build(),
			"hardwood.fossil_island.east", "hardwood.fossil_island.middle");
		assertEquals(15, need(p, "payment." + ItemID.LIMPWURT_ROOT).getQuantity());
		assertEquals(25, need(p, "payment." + ItemID.YANILLIAN_HOPS).getQuantity());
	}

	@Test
	public void mixedSaplings_moreThanPatches_showTheCertainPart_neverAnInventedSplit()
	{
		// Two teak and two mahogany for three patches: at least one of each is planted (3 - 2), so one payment of each is
		// certain; which sapling goes where is the player's choice in game, so nothing more is assumed.
		FarmingPreparation.Plan p = hardwood(maxed().inventory(items(ItemID.PLANTPOT_TEAK_SAPLING, 2, ItemID.PLANTPOT_MAHOGANY_SAPLING, 2)).build(),
			"hardwood.fossil_island.east", "hardwood.fossil_island.middle", "hardwood.fossil_island.west");
		assertEquals(15, need(p, "payment." + ItemID.LIMPWURT_ROOT).getQuantity());
		assertEquals(25, need(p, "payment." + ItemID.YANILLIAN_HOPS).getQuantity());
		assertTrue(p.getNotes().toString(), p.getNotes().stream().anyMatch(n -> n.contains("which you plant where")));
	}
}
