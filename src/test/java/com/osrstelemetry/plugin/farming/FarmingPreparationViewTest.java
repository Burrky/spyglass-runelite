package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.bank;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.prep.Readiness;
import com.osrstelemetry.plugin.travel.Availability;
import com.osrstelemetry.plugin.travel.TransportAvailability;
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
 * Phase H refinement: readiness carries travel uncertainty, travel resources
 * are shared across the whole suggested run, required and optional are told
 * apart, counts are supply types with quantities on the rows, and Find only
 * explains.
 */
public class FarmingPreparationViewTest
{
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final long HOUR = 3_600_000L;

	private static FarmingRunIntent intent(String compost, Map<String, String> planned)
	{
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-view-herb", FarmingRunType.HERB, NOW, NOW, FarmingRunIntent.State.ACTIVE, null, null,
			new ArrayList<>(planned.keySet()), Collections.emptyList(), Collections.emptyList(), planned,
			new FarmingRunIntent.Options(compost, false), Collections.emptyMap()).withPreparing(true);
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

	private static FarmingPreparationView view(FarmingRunIntent intent, AccountCapability cap, boolean bankOpen)
	{
		FarmingPreparation.Plan p = plan(intent, cap);
		return FarmingPreparationView.of(p, PreparationEvaluator.evaluate(p.getRequest(), cap, NOW), bankOpen, null, NOW);
	}

	private static FarmingPreparationView.SiteRow site(FarmingPreparationView v, String name)
	{
		for (FarmingPreparationView.SiteRow s : v.getSites())
		{
			if (s.getSite().equals(name))
			{
				return s;
			}
		}
		throw new AssertionError("no site " + name);
	}

	private static FarmingPreparationView.NeedRow row(FarmingPreparationView v, String key)
	{
		for (FarmingPreparationView.NeedRow r : v.getNeeds())
		{
			if (r.getKey().equals(key))
			{
				return r;
			}
		}
		return null;
	}

	@Test
	public void unknownCharges_preventConfirmedReadiness_butTheyCanBeginExplicitly()
	{
		FarmingRunIntent run = intent("NONE", herbs(FarmingRunIntent.HARVEST_ONLY, "herb.ardougne"));
		// Ardougne cloak 2: carried (worn), but today's uses are not observable.
		FarmingPreparationView v = view(run, maxed().spellbookRaw(0).worn(items(ItemID.ARDY_CAPE_MEDIUM, 1)).build(), false);
		assertEquals("everything required is carried", 0, v.getToCollect());
		assertEquals(Readiness.UNVERIFIED, v.getReadiness());
		assertFalse("never reads as ready", v.isReady());
		assertEquals("Supplies ready · travel unverified", v.getHeadline());
		assertTrue(v.getSubline(), v.getSubline().contains("Ardougne \u00b7 herb (charges unknown)"));
		assertEquals("Begin with travel unverified", v.getBeginLabel());
		FarmingPreparationView.SiteRow ardy = site(v, "Ardougne \u00b7 herb");
		assertEquals("Charges unknown", ardy.getStatus());
		assertEquals(FarmingPreparationView.Tone.UNVERIFIED, ardy.getTone());
		assertTrue(ardy.isUnverified());
		assertTrue(ardy.getDetails().toString(), ardy.getDetails().toString().contains("can't be observed"));

		// Ardougne cloak 4: unlimited, so confirmed.
		FarmingPreparationView elite = view(run, maxed().spellbookRaw(0).worn(items(ItemID.ARDY_CAPE_ELITE, 1)).build(), false);
		assertEquals(Readiness.CONFIRMED, elite.getReadiness());
		assertEquals("✓ Ready to begin", elite.getHeadline());
		assertEquals("Begin run", elite.getBeginLabel());
		assertEquals("✓ Ready", site(elite, "Ardougne \u00b7 herb").getStatus());
	}

	@Test
	public void sharedRunes_coverEveryDestination_notEachAsIfAlone()
	{
		FarmingRunIntent run = intent("NONE", herbs(FarmingRunIntent.HARVEST_ONLY, "herb.falador", "herb.catherby"));
		// One law rune: enough for either spell on its own, not for both.
		AccountCapability one = maxed().spellbookRaw(0).inventory(items(ItemID.LAWRUNE, 1, ItemID.AIRRUNE, 100, ItemID.WATERRUNE, 100)).build();
		FarmingPreparation.Plan p = plan(run, one);
		int spells = 0;
		for (FarmingPreparation.SitePlan s : p.getSites())
		{
			assertNotNull(s.getSite().getName(), s.getTransport());
			if (s.getTransport().getTransport().type == TransportDef.Type.SPELL)
			{
				spells++;
				assertEquals("each spell alone looks castable", Availability.READY, s.getTransport().getState());
				assertTrue(s.getNeedKeys().contains("rune." + ItemID.LAWRUNE));
			}
		}
		assertEquals(2, spells);
		FarmingPreparationView v = FarmingPreparationView.of(p, PreparationEvaluator.evaluate(p.getRequest(), one, NOW), false, null, NOW);
		FarmingPreparationView.NeedRow law = row(v, "rune." + ItemID.LAWRUNE);
		assertEquals("×2", law.getQuantity());
		assertFalse("two casts need two law runes", law.isSatisfied());
		assertEquals(Readiness.MISSING, v.getReadiness());
		for (FarmingPreparationView.SiteRow s : v.getSites())
		{
			assertEquals("Bring it first", s.getStatus());
		}

		AccountCapability two = maxed().spellbookRaw(0).inventory(items(ItemID.LAWRUNE, 2, ItemID.AIRRUNE, 100, ItemID.WATERRUNE, 100)).build();
		assertEquals(Readiness.CONFIRMED, view(run, two, false).getReadiness());
	}

	@Test
	public void sharedItem_acrossTwoTransports_isOneNeedCountingCharges()
	{
		TransportDef a = necklaceTeleport("test.necklace.a");
		TransportDef b = necklaceTeleport("test.necklace.b");
		AccountCapability cap = maxed().inventory(items(ItemID.JEWL_NECKLACE_OF_SKILLS_1, 1)).build();
		TransportAvailability ta = TransportEvaluator.evaluate(a, cap, EvidenceSource.NONE, NOW);
		TransportAvailability tb = TransportEvaluator.evaluate(b, cap, EvidenceSource.NONE, NOW);
		assertEquals("alone, a necklace(1) looks enough for each", Availability.READY, ta.getState());
		assertEquals(Availability.READY, tb.getState());
		List<ItemNeed> needs = new ArrayList<>();
		Map<String, List<String>> keys = FarmingPreparation.travelNeeds(Arrays.asList(ta, tb), cap, needs);
		assertEquals("one shared need, not two", 1, needs.size());
		assertEquals(keys.get("test.necklace.a"), keys.get("test.necklace.b"));
		ItemNeed n = needs.get(0);
		assertEquals(2, n.getQuantity());
		assertEquals("2 charges", n.quantityText());
		PreparationState s = PreparationEvaluator.evaluate(new com.osrstelemetry.plugin.prep.PreparationRequest("t", "t", needs), cap, NOW);
		assertFalse("one charge carried, two uses planned", s.getResults().get(0).isSatisfied());
		AccountCapability more = maxed().inventory(items(ItemID.JEWL_NECKLACE_OF_SKILLS_4, 1)).build();
		assertTrue(PreparationEvaluator.evaluate(new com.osrstelemetry.plugin.prep.PreparationRequest("t", "t", needs), more, NOW).getResults().get(0)
			.isSatisfied());
	}

	private static TransportDef necklaceTeleport(String id)
	{
		TransportDef t = new TransportDef();
		t.id = id;
		t.name = "Skills necklace: " + id;
		t.type = TransportDef.Type.ITEM_TELEPORT;
		t.arrival = new TransportDef.Point();
		t.items = new ArrayList<>();
		int[][] v = {{ItemID.JEWL_NECKLACE_OF_SKILLS_4, 4}, {ItemID.JEWL_NECKLACE_OF_SKILLS_1, 1}};
		for (int[] x : v)
		{
			TransportDef.ItemVariant iv = new TransportDef.ItemVariant();
			iv.id = x[0];
			iv.gameval = "JEWL_" + x[0];
			iv.charges = TransportDef.Charges.FIXED;
			iv.count = x[1];
			t.items.add(iv);
		}
		t.provenance = new TransportDef.Provenance();
		t.provenance.verified = "2026-10-15";
		return t;
	}

	@Test
	public void requiredAndOptional_areToldApart_andCountsAreSupplyTypes()
	{
		FarmingRunIntent run = intent("ULTRACOMPOST", herbs("RANARR", "herb.falador", "herb.catherby", "herb.ardougne"));
		AccountCapability cap = maxed().spellbookRaw(0).worn(items(ItemID.ARDY_CAPE_ELITE, 1))
			.bank(bank(3 * HOUR, ItemID.RANARR_SEED, 40, ItemID.BUCKET_ULTRACOMPOST, 20, ItemID.DIBBER, 1, ItemID.RAKE, 1, ItemID.LAWRUNE, 300,
				ItemID.AIRRUNE, 3000, ItemID.WATERRUNE, 3000))
			.build();
		FarmingPreparationView v = view(run, cap, false);
		assertTrue(v.getHeadline(), v.getHeadline().endsWith(" supply types to collect"));
		assertFalse("never 'items' as if counting quantities", v.getHeadline().contains("items"));
		FarmingPreparationView.NeedRow seeds = row(v, "seed.herb.any");
		assertEquals("×3", seeds.getQuantity());
		assertEquals("Not carried · in your bank", seeds.getStatus());
		assertEquals("ownership kept apart, with its age (no expiry)", "Seen 3h ago", seeds.getSupport());
		FarmingPreparationView.NeedRow rake = row(v, "tool.rake");
		assertFalse(rake.isRequired());
		assertEquals(FarmingPreparationView.Tone.INFO, rake.getTone());
		FarmingPreparationView.NeedRow dibber = row(v, "tool.dibber");
		assertTrue(dibber.isRequired());
		assertEquals("a reusable tool: one, whatever the patch count", "", dibber.getQuantity());
		int required = 0;
		for (FarmingPreparationView.NeedRow r : v.getNeeds())
		{
			if (r.isRequired() && !r.isSatisfied())
			{
				required++;
			}
		}
		assertEquals(required + " supply types to collect", v.getHeadline());
		// Correction pass: recommended tools sit with what is still needed (never counted in the headline); the
		// optional group holds only true extras, and the summary no longer repeats them.
		assertFalse(v.getSubline().contains("Optional"));
		assertEquals(FarmingPreparationView.Group.NEEDED, rake.getGroup());
		for (FarmingPreparationView.NeedRow r : v.rows(FarmingPreparationView.Group.OPTIONAL))
		{
			assertFalse("optional rows are never required: " + r.getKey(), r.isRequired());
		}
	}

	@Test
	public void bankOpen_asksToWithdraw_andFindOnlyExplains()
	{
		FarmingRunIntent run = intent("ULTRACOMPOST", herbs("RANARR", "herb.falador"));
		AccountCapability cap = maxed().spellbookRaw(0).inventory(items(ItemID.RANARR_SEED, 1))
			.bank(com.osrstelemetry.plugin.account.AccountFixtures.bank(0, ItemID.RANARR_SEED, 40)).build();
		FarmingPreparation.Plan p = plan(run, cap);
		PreparationState state = PreparationEvaluator.evaluate(p.getRequest(), cap, NOW);
		FarmingPreparationView open = FarmingPreparationView.of(p, state, true, new HashSet<>(Collections.singletonList(ItemID.RANARR_SEED)), NOW);
		FarmingPreparationView.NeedRow seeds = row(open, "seed.herb.any");
		assertTrue("one carried already: the row asks for what is left", seeds.isSatisfied() || seeds.getStatus().startsWith("Withdraw"));
		FarmingPreparationView elsewhere = FarmingPreparationView.of(p, state, true, Collections.emptySet(), NOW);
		FarmingPreparationView.NeedRow compost = row(elsewhere, "supply.compost." + ItemID.BUCKET_ULTRACOMPOST);
		assertTrue(compost.getLocate(), compost.getLocate().startsWith("Spyglass can't locate it"));
		assertTrue(compost.isFindable());
	}

	@Test
	public void aClosedGuidedWay_isAGapInGuidance_notATeleportToBring()
	{
		// Anglers' Retreat, 51 Sailing, rowboat not built: the tablet works, the guided way on does not. Not "bring a teleport",
		// not locked; the row names what would open the guided way.
		FarmingRunIntent run = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-view-hardwood", FarmingRunType.HARDWOOD, NOW, NOW,
			FarmingRunIntent.State.ACTIVE, null, null, Collections.singletonList("hardwood.anglers_retreat"), Collections.emptyList(),
			Collections.emptyList(), Collections.singletonMap("hardwood.anglers_retreat", FarmingRunIntent.REPLANT_ANY),
			new FarmingRunIntent.Options("NONE", false), Collections.emptyMap()).withPreparing(true);
		AccountCapability cap = maxed().varbit(18355, 0).inventory(items(ItemID.POH_TABLET_ARDOUGNETELEPORT, 3, ItemID.COINS, 1000)).build();
		FarmingPreparationView.SiteRow row = view(run, cap, false).getSites().get(0);
		assertEquals("No guided route", row.getTransport());
		assertEquals("Still in the run: no guided route yet", row.getStatus());
		assertTrue(row.getDetails().toString(), row.getDetails().contains(
			"Quicker if open: Ardougne teleport: the way from there needs the Anglers' Retreat rowboat built."));
		assertTrue(row.getDetails().toString(), row.getDetails().stream().noneMatch(d -> d.toLowerCase(java.util.Locale.ROOT).contains("locked")));
		// Built: a guided journey, row by row.
		FarmingPreparationView.SiteRow built = view(run, maxed().varbit(18355, 1)
			.inventory(items(ItemID.POH_TABLET_ARDOUGNETELEPORT, 3, ItemID.COINS, 1000)).build(), false).getSites().get(0);
		assertTrue(built.getDetails().toString(), built.getDetails().contains("Then: Take the rowboat."));
	}

	@Test
	public void noUsableTransport_keepsThePatch_andSaysSoPlainly()
	{
		// Logged-in account knowledge, but no bank seen and nothing carried: every transport for Weiss is unknown or locked.
		FarmingRunIntent run = intent("NONE", herbs(FarmingRunIntent.HARVEST_ONLY, "herb.weiss"));
		FarmingPreparationView v = view(run, maxed().spellbookRaw(0).build(), false);
		FarmingPreparationView.SiteRow weiss = v.getSites().get(0);
		assertEquals("No usable transport known", weiss.getTransport());
		assertTrue(weiss.getStatus(), weiss.getStatus().startsWith("Still in the run: bring a teleport listed below"));
		assertEquals(FarmingPreparationView.Tone.WARN, weiss.getTone());
		assertFalse("the collapsed row never lists the rejected alternatives", weiss.getStatus().contains("Not usable"));
		assertFalse(v.isReady());
	}
}
