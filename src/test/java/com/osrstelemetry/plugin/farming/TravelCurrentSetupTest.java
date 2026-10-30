package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import com.osrstelemetry.plugin.account.AccountReaders;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Pass 32: the "Use current setup" travel choice. Live QA showed Harmony Island Teleport while the player was on another
 * spellbook; replaying that run's stop shows the planner never chose it (a wrong-book spell is REQUIREMENTS_UNMET): it was
 * offered under "Not usable now" with "bring a teleport listed below". Use current setup leaves such journeys out entirely.
 */
public class TravelCurrentSetupTest
{
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final long NOW = AccountFixtures.NOW;

	private static final FarmingRunIntent HARMONY = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-harmony", FarmingRunType.HERB, NOW, NOW,
		FarmingRunIntent.State.ACTIVE, null, null, Collections.singletonList("herb.harmony"), Collections.emptyList(), Collections.emptyList(),
		Collections.singletonMap("herb.harmony", FarmingRunIntent.REPLANT_ANY), new FarmingRunIntent.Options(null, false), Collections.emptyMap())
		.withPreparing(true);

	private static AccountCapability.Builder on(AccountReaders.Spellbook book)
	{
		return AccountFixtures.spellbook(maxed(), book);
	}

	private static FarmingPreparation.Plan plan(AccountCapability cap, FarmingPreferences.Travel travel, EvidenceSource ev)
	{
		List<TransportAvailability> av = TransportEvaluator.evaluateAll(TRANSPORTS, cap, ev, NOW);
		return FarmingPreparation.plan(HARMONY, CATALOG, cap, av, ev, FarmingPreferences.defaults().withTravel(travel), Collections.emptyMap());
	}

	private static FarmingPreparation.SitePlan site(AccountCapability cap, FarmingPreferences.Travel travel, EvidenceSource ev)
	{
		FarmingPreparation.Plan p = plan(cap, travel, ev);
		assertEquals(1, p.getSites().size());
		return p.getSites().get(0);
	}

	private static FarmingPreparationView.SiteRow row(AccountCapability cap, FarmingPreferences.Travel travel)
	{
		FarmingPreparation.Plan p = plan(cap, travel, EvidenceSource.NONE);
		return FarmingPreparationView.of(p, com.osrstelemetry.plugin.prep.PreparationEvaluator.evaluate(p.getRequest(), cap, NOW), false, null, NOW)
			.getSites().get(0);
	}

	private static String chosen(FarmingPreparation.SitePlan s)
	{
		return s.getTransport() == null ? null : s.getTransport().getTransport().id;
	}

	private static List<String> rejected(FarmingPreparation.SitePlan s)
	{
		return s.getRejected().stream().map(a -> a.getTransport().id).collect(Collectors.toList());
	}

	private static final java.util.Map<Integer, Integer> HARMONY_RUNES = items(ItemID.LAWRUNE, 10, ItemID.NATURERUNE, 10, ItemID.SOULRUNE, 10);

	@Test
	public void harmonyOnTheWrongBook_isNeverPlannedOrOffered_withUseCurrentSetup()
	{
		AccountCapability cap = on(AccountReaders.Spellbook.STANDARD).inventory(HARMONY_RUNES).build();
		FarmingPreparation.SitePlan fastest = site(cap, FarmingPreferences.Travel.FASTEST, EvidenceSource.NONE);
		assertFalse("Fastest never plans a spell on another book", "spell.harmony_island".equals(chosen(fastest)));
		assertTrue("Fastest names it, as a spellbook change", rejected(fastest).contains("spell.harmony_island"));
		FarmingPreparationView.SiteRow row = row(cap, FarmingPreferences.Travel.FASTEST);
		assertTrue(row.getDetails().toString(), row.getDetails().toString().contains("Harmony Island Teleport (needs a spellbook change: Arceuus spellbook (on Standard))"));

		FarmingPreparation.SitePlan current = site(cap, FarmingPreferences.Travel.CURRENT, EvidenceSource.NONE);
		assertNull("no journey: nothing silently falls back to the spell", chosen(current));
		assertFalse("never offered as something to bring", rejected(current).contains("spell.harmony_island"));
		assertTrue(current.isCurrentSetup());
		assertEquals("Harmony Island Teleport needs the Arceuus spellbook (you are on Standard)", current.getSetupLeftOut().get(0));
		// Intermediate legs count too: a journey whose first leg is that spell is left out as well.
		assertTrue(current.getSetupLeftOut().toString(), current.getSetupLeftOut().stream().allMatch(t -> t.contains("Arceuus spellbook")));
		FarmingPreparationView.SiteRow r = row(cap, FarmingPreferences.Travel.CURRENT);
		String details = r.getDetails().toString();
		assertFalse(details, details.contains("Not usable now: Harmony Island Teleport"));
		assertTrue(details, details.contains("Left out (Use current setup): Harmony Island Teleport needs the Arceuus spellbook (you are on Standard)."));
	}

	@Test
	public void useCurrentSetup_takesAnOwnedItemOrAConfirmedHouseRoute_withTheWithdrawalStillListed()
	{
		// A tablet in the bank: planned, and withdrawn in preparation as usual.
		AccountCapability banked = on(AccountReaders.Spellbook.LUNAR).inventory(HARMONY_RUNES)
			.bank(AccountFixtures.bank(60_000L, ItemID.TELETAB_HARMONY, 5)).build();
		FarmingPreparation.SitePlan s = site(banked, FarmingPreferences.Travel.CURRENT, EvidenceSource.NONE);
		assertEquals("tablet.harmony_island", chosen(s));
		assertEquals(com.osrstelemetry.plugin.travel.Availability.IN_BANK, s.getTransport().getState());
		assertFalse(s.getNeedKeys().isEmpty());

		// A confirmed house portal, way in by house tablet: a house route is a current-setup journey (no spell cast).
		EvidenceSource house = new EvidenceSource()
		{
			@Override
			public Known get(String key)
			{
				return "poh.portal.harmony_island".equals(key) ? Known.YES : Known.UNKNOWN;
			}

			@Override
			public String explain(String key)
			{
				return "test house";
			}
		};
		AccountCapability tab = on(AccountReaders.Spellbook.LUNAR).inventory(items(ItemID.POH_TABLET_TELEPORTTOHOUSE, 3)).build();
		FarmingPreparation.SitePlan h = site(tab, FarmingPreferences.Travel.CURRENT, house);
		assertEquals("poh.tab.portal.harmony_island", chosen(h));
		// The same portal by Teleport to House is a Standard spell: left out on Lunar.
		assertFalse(rejected(h).contains("poh.spell.portal.harmony_island"));
	}

	@Test
	public void replans_afterChangingSpellbookOrPreference()
	{
		AccountCapability standard = on(AccountReaders.Spellbook.STANDARD).inventory(HARMONY_RUNES).build();
		assertNull(chosen(site(standard, FarmingPreferences.Travel.CURRENT, EvidenceSource.NONE)));
		// Switched to Arceuus: the spell is the current setup now.
		AccountCapability arceuus = on(AccountReaders.Spellbook.ARCEUUS).inventory(HARMONY_RUNES).build();
		assertEquals("spell.harmony_island", chosen(site(arceuus, FarmingPreferences.Travel.CURRENT, EvidenceSource.NONE)));
		assertTrue(site(arceuus, FarmingPreferences.Travel.CURRENT, EvidenceSource.NONE).getSetupLeftOut().isEmpty());
		// And under the other choices too (every journey judged by its own requirements).
		assertEquals("spell.harmony_island", chosen(site(arceuus, FarmingPreferences.Travel.FASTEST, EvidenceSource.NONE)));
		// Back on Standard with Fastest: named as a spellbook change, never planned.
		assertTrue(rejected(site(standard, FarmingPreferences.Travel.FASTEST, EvidenceSource.NONE)).contains("spell.harmony_island"));
	}

	@Test
	public void liveGuidance_asksForTheSpellbookBeforeTheCast()
	{
		AccountCapability arceuus = on(AccountReaders.Spellbook.ARCEUUS).inventory(HARMONY_RUNES).build();
		TransportAvailability spell = site(arceuus, FarmingPreferences.Travel.CURRENT, EvidenceSource.NONE).getTransport();
		FarmingGuide.Objective travel = FarmingGuide.Objective.of(FarmingGuide.Kind.TRAVEL, "Harmony Island", "Harmony Island · Herb",
			Collections.singletonList("Use: Harmony Island Teleport"), null, spell, null, -1, -1, FarmingWording.teleport(spell.getTransport(), null, false));
		assertTrue("on Arceuus: unchanged", FarmingGuide.guardSpellbook(travel, "ARCEUUS") == travel);
		FarmingGuide.Objective guarded = FarmingGuide.guardSpellbook(travel, "STANDARD");
		assertEquals("Switch to the Arceuus spellbook first", guarded.getAction().getLabel());
		assertNull("the spell is not highlighted", guarded.getTransport());
		assertEquals(FarmingAction.Target.NONE, guarded.getAction().getTarget());
		assertEquals("○ Switch to the Arceuus spellbook first · you are on Standard", guarded.getChecklist().get(0));
		assertEquals("Check you are on the Arceuus spellbook", FarmingGuide.guardSpellbook(travel, null).getAction().getLabel());
	}

	@Test
	public void thePreference_persistsPerProfile_andOlderSavesKeepTheirChoice()
	{
		Gson gson = new Gson();
		FarmingPreferences cur = gson.fromJson(gson.toJson(FarmingPreferences.defaults().withTravel(FarmingPreferences.Travel.CURRENT)), FarmingPreferences.class);
		assertEquals(FarmingPreferences.Travel.CURRENT, cur.getTravel());
		assertEquals("Use current setup", cur.getTravel().getLabel());
		assertEquals(FarmingPreferences.Travel.HOUSE, gson.fromJson("{\"travel\":\"HOUSE\"}", FarmingPreferences.class).getTravel());
		assertEquals(FarmingPreferences.Travel.FASTEST, gson.fromJson("{}", FarmingPreferences.class).getTravel());
		assertEquals(Arrays.asList(FarmingPreferences.Travel.FASTEST, FarmingPreferences.Travel.HOUSE, FarmingPreferences.Travel.CURRENT),
			Arrays.asList(FarmingPreferences.Travel.values()));
		assertNotNull(cur);
	}
}
