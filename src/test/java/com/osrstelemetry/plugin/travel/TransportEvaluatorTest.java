package com.osrstelemetry.plugin.travel;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.bank;
import static com.osrstelemetry.plugin.account.AccountFixtures.base;
import static com.osrstelemetry.plugin.account.AccountFixtures.group;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static com.osrstelemetry.plugin.account.AccountFixtures.spellbook;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountProfileGate;
import com.osrstelemetry.plugin.account.AccountReaders.Spellbook;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.account.EvidenceSource;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Availability over synthetic capability snapshots (canonical Phase F tests):
 * met / unmet / unknown requirements, carried vs banked vs elsewhere vs unseen,
 * charged / depleted / daily-limit / unobservable variants, spellbooks, rune
 * pouch and worn rune providers. Unknown never becomes unavailable or zero.
 */
public class TransportEvaluatorTest
{
	private static final long HOUR = 3_600_000L;
	private static final TransportCatalog CATALOG = TransportCatalogTest.CATALOG;

	private static TransportAvailability eval(String id, AccountCapability cap)
	{
		return eval(id, cap, EvidenceSource.NONE);
	}

	private static TransportAvailability eval(String id, AccountCapability cap, EvidenceSource evidence)
	{
		TransportDef t = CATALOG.get(id);
		assertTrue("catalogued: " + id, t != null);
		return TransportEvaluator.evaluate(t, cap, evidence, NOW);
	}

	private static String text(TransportAvailability a)
	{
		return have(a) + " | met " + a.getMet() + " | unmet " + a.getUnmet() + " | missing " + a.getMissing();
	}

	private static String have(TransportAvailability a)
	{
		return String.join(" | ", a.getHave());
	}

	private static EvidenceSource evidence(String key, Known k)
	{
		return new EvidenceSource()
		{
			@Override
			public Known get(String q)
			{
				return q.equals(key) ? k : Known.UNKNOWN;
			}

			@Override
			public String explain(String q)
			{
				return q.equals(key) ? "fixture says " + k : "no evidence";
			}
		};
	}

	// -- no owner ----------------------------------------------------------------------------

	@Test
	public void noOwner_isUnknownForEverything_neverReadyOrUnmet()
	{
		for (AccountProfileGate.Status s : AccountProfileGate.Status.values())
		{
			AccountCapability none = AccountCapability.none(s, NOW);
			for (TransportAvailability a : TransportEvaluator.evaluateAll(CATALOG, none, EvidenceSource.NONE, NOW))
			{
				assertEquals(a.getTransport().id, Availability.UNKNOWN, a.getState());
			}
		}
	}

	@Test
	public void aBuilderWithoutOwner_dropsEveryFact()
	{
		AccountCapability cap = AccountCapability.builder().status(AccountProfileGate.Status.STANDARD).inventory(items(ItemID.ARDY_CAPE_ELITE, 1))
			.level(Skill.MAGIC, 99).build();
		assertNull(cap.getOwner());
		assertNull(cap.getInventory());
		assertEquals(AccountProfileGate.Status.NOT_READY, cap.getStatus());
		assertEquals(Availability.UNKNOWN, eval("ardougne_cloak.farm", cap).getState());
	}

	// -- tablets: carried / bank / elsewhere / unseen ------------------------------------------

	@Test
	public void tablet_carried_isReady_banked_isInBank_groupStorage_isElsewhere()
	{
		assertEquals(Availability.READY, eval("tablet.varrock", base(1).inventory(items(ItemID.POH_TABLET_VARROCKTELEPORT, 3)).build()).getState());
		TransportAvailability banked = eval("tablet.varrock", base(1).bank(bank(2 * HOUR, ItemID.POH_TABLET_VARROCKTELEPORT, 40)).build());
		assertEquals(Availability.IN_BANK, banked.getState());
		assertTrue(have(banked), have(banked).contains("last seen in the bank 2h ago"));
		assertEquals(Availability.ELSEWHERE, eval("tablet.varrock",
			base(1).bank(bank(HOUR)).groupStorage(group(HOUR, ItemID.POH_TABLET_VARROCKTELEPORT, 5)).build()).getState());
	}

	@Test
	public void tablet_notFound_isUnknownWithTheEvidenceGap_neverUnavailable()
	{
		TransportAvailability unseenBank = eval("tablet.varrock", base(1).build());
		assertEquals(Availability.UNKNOWN, unseenBank.getState());
		assertTrue(text(unseenBank), unseenBank.getMissing().toString().contains("bank not seen yet"));

		TransportAvailability seenBank = eval("tablet.varrock", base(1).bank(bank(3 * HOUR, ItemID.LAWRUNE, 10)).build());
		assertEquals(Availability.UNKNOWN, seenBank.getState());
		assertTrue(text(seenBank), seenBank.getMissing().toString().contains("may be held elsewhere"));

		TransportAvailability noInventory = eval("tablet.varrock", base(1).inventory(null).build());
		assertEquals(Availability.UNKNOWN, noInventory.getState());
		assertTrue(text(noInventory), noInventory.getMissing().toString().contains("inventory / equipment not read"));
	}

	@Test
	public void tablet_questLocked_isUnmet_evenWhenCarried_andUnreadQuestIsUnknown()
	{
		AccountCapability notStarted = base(1).quest(Quest.PLAGUE_CITY, QuestState.NOT_STARTED).inventory(items(ItemID.POH_TABLET_ARDOUGNETELEPORT, 5)).build();
		TransportAvailability a = eval("tablet.ardougne", notStarted);
		assertEquals(Availability.REQUIREMENTS_UNMET, a.getState());
		assertTrue(text(a), a.getUnmet().get(0).startsWith("Plague City not complete"));
		assertTrue("what was found is kept apart from the verdict", have(a).contains("carried (inventory)"));

		TransportAvailability unread = eval("tablet.ardougne", base(1).settled(false).inventory(items(ItemID.POH_TABLET_ARDOUGNETELEPORT, 5)).build());
		assertEquals(Availability.UNKNOWN, unread.getState());
		assertTrue(text(unread), unread.getMissing().get(0).contains("account data not confirmed loaded"));
		assertTrue(text(unread), unread.getMet().isEmpty() && unread.getUnmet().isEmpty());
	}

	// -- charged / depleted / daily-limit / unobservable -----------------------------------------

	@Test
	public void ardougneCloak_dailyLimitedVariants_areChargesUnknown_unlimitedVariantsReady()
	{
		assertEquals(Availability.CHARGES_UNKNOWN, eval("ardougne_cloak.farm", base(1).worn(items(ItemID.ARDY_CAPE_MEDIUM, 1)).build()).getState());
		assertEquals(Availability.CHARGES_UNKNOWN, eval("ardougne_cloak.farm", base(1).inventory(items(ItemID.ARDY_CAPE_HARD, 1)).build()).getState());
		assertEquals(Availability.READY, eval("ardougne_cloak.farm", base(1).worn(items(ItemID.ARDY_CAPE_ELITE, 1)).build()).getState());
		assertEquals(Availability.READY, eval("ardougne_cloak.farm", base(1).worn(items(ItemID.SKILLCAPE_MAX_ARDY, 1)).build()).getState());
		// Carried with unknown charges outranks an unlimited one left in the bank.
		TransportAvailability both = eval("ardougne_cloak.farm",
			base(1).worn(items(ItemID.ARDY_CAPE_MEDIUM, 1)).bank(bank(HOUR, ItemID.ARDY_CAPE_ELITE, 1)).build());
		assertEquals(Availability.CHARGES_UNKNOWN, both.getState());
		assertTrue(have(both), have(both).contains("today's uses are not observable"));
		// Cloak 1 has no farm teleport: owning it proves nothing.
		assertEquals(Availability.UNKNOWN, eval("ardougne_cloak.farm", base(1).worn(items(ItemID.ARDY_CAPE_EASY, 1)).build()).getState());
	}

	@Test
	public void xericsTalisman_chargedIsChargesUnknown_emptyIsDepleted_emptyPlusBankedChargedIsInBank()
	{
		assertEquals(Availability.CHARGES_UNKNOWN, eval("xerics_talisman.xerics_glade", base(1).inventory(items(ItemID.XERIC_TALISMAN, 1)).build()).getState());
		TransportAvailability empty = eval("xerics_talisman.xerics_glade", base(1).inventory(items(ItemID.XERIC_TALISMAN_EMPTY, 1)).build());
		assertEquals(Availability.DEPLETED, empty.getState());
		assertTrue(have(empty), have(empty).contains("recharge"));
		assertEquals(Availability.IN_BANK, eval("xerics_talisman.xerics_glade",
			base(1).inventory(items(ItemID.XERIC_TALISMAN_EMPTY, 1)).bank(bank(HOUR, ItemID.XERIC_TALISMAN, 1)).build()).getState());
	}

	@Test
	public void skillsNecklace_fixedChargesAreReady_uncharged_isDepleted()
	{
		TransportAvailability four = eval("skills_necklace.farming_guild", base(1).worn(items(ItemID.JEWL_NECKLACE_OF_SKILLS_4, 1)).build());
		assertEquals(Availability.READY, four.getState());
		assertTrue(have(four), have(four).contains("4 charges"));
		assertEquals(Availability.DEPLETED, eval("skills_necklace.farming_guild", base(1).worn(items(ItemID.JEWL_NECKLACE_OF_SKILLS, 1)).build()).getState());
	}

	@Test
	public void ectophial_ownershipDoesNotProveTheUnlock()
	{
		TransportAvailability a = eval("ectophial.ectofuntus", base(1).quest(Quest.GHOSTS_AHOY, QuestState.IN_PROGRESS).inventory(items(ItemID.ECTOPHIAL, 1)).build());
		assertEquals(Availability.REQUIREMENTS_UNMET, a.getState());
		assertEquals(Availability.READY, eval("ectophial.ectofuntus", maxed().inventory(items(ItemID.ECTOPHIAL, 1)).build()).getState());
		assertEquals(Availability.DEPLETED, eval("ectophial.ectofuntus", maxed().inventory(items(ItemID.ECTOPHIAL_EMPTY, 1)).build()).getState());
	}

	// -- spells: spellbook, level, runes, pouch, providers --------------------------------------

	@Test
	public void spells_useTheVerifiedSpellbookValue_andAnUnknownValueStaysUnknown()
	{
		java.util.Map<Integer, Integer> runes = items(ItemID.AIRRUNE, 100, ItemID.FIRERUNE, 100, ItemID.LAWRUNE, 100);
		assertEquals(Availability.READY, eval("spell.varrock", maxed().spellbookRaw(0).inventory(runes).build()).getState());
		assertEquals(Availability.REQUIREMENTS_UNMET, eval("spell.varrock", maxed().spellbookRaw(2).inventory(runes).build()).getState());
		TransportAvailability odd = eval("spell.varrock", maxed().spellbookRaw(7).inventory(runes).build());
		assertEquals(Availability.UNKNOWN, odd.getState());
		assertTrue(text(odd), odd.getMissing().toString().contains("value 7 is not a known book"));
		assertTrue("the carried runes are still reported, apart from the verdict", have(odd).contains("runes carried"));
		TransportAvailability unread = eval("spell.varrock", maxed().inventory(runes).build());
		assertEquals(Availability.UNKNOWN, unread.getState());
		assertTrue(text(unread), unread.getMissing().toString().contains("current spellbook not read yet"));
	}

	@Test
	public void spells_lowMagic_isUnmet_regardlessOfSpellbookUncertainty()
	{
		TransportAvailability a = eval("spell.camelot", base(20).spellbookRaw(0).build());
		assertEquals(Availability.REQUIREMENTS_UNMET, a.getState());
		assertTrue(text(a), a.getUnmet().get(0).startsWith("Magic 45 (have 20"));
	}

	@Test
	public void spellbookDifferences_whenTheMappingIsVerified()
	{
		Map<Integer, Integer> runes = items(ItemID.WATERRUNE, 50, ItemID.ASTRALRUNE, 20, ItemID.LAWRUNE, 20, ItemID.AIRRUNE, 50);
		AccountCapability lunar = spellbook(maxed(), Spellbook.LUNAR).inventory(runes).build();
		AccountCapability standard = spellbook(maxed(), Spellbook.STANDARD).inventory(runes).build();
		assertEquals(Availability.READY, eval("spell.catherby", lunar).getState());
		assertEquals(Availability.REQUIREMENTS_UNMET, eval("spell.catherby", standard).getState());
		assertTrue(eval("spell.catherby", standard).getUnmet().get(0).contains("on Standard"));
		assertEquals(Availability.READY, eval("spell.camelot", standard).getState());
		assertEquals(Availability.REQUIREMENTS_UNMET, eval("spell.camelot", lunar).getState());
	}

	@Test
	public void spells_wornStaffSuppliesElement_staffInInventoryDoesNot()
	{
		AccountCapability worn = spellbook(maxed(), Spellbook.STANDARD).worn(items(ItemID.AIR_BATTLESTAFF, 1)).inventory(items(ItemID.LAWRUNE, 1)).build();
		assertEquals(Availability.READY, eval("spell.camelot", worn).getState());
		AccountCapability carried = spellbook(maxed(), Spellbook.STANDARD).inventory(items(ItemID.AIR_BATTLESTAFF, 1, ItemID.LAWRUNE, 1)).build();
		TransportAvailability a = eval("spell.camelot", carried);
		assertEquals(Availability.UNKNOWN, a.getState());
		assertTrue(text(a), a.getMissing().get(0).contains("missing 5 air"));
	}

	@Test
	public void spells_chargedTomeSupplies_unchargedTomeDoesNot_combinationStaffCoversBoth()
	{
		AccountCapability charged = spellbook(maxed(), Spellbook.STANDARD).worn(items(ItemID.TOME_OF_FIRE, 1)).inventory(items(ItemID.LAWRUNE, 2)).build();
		assertEquals(Availability.READY, eval("spell.trollheim", charged).getState());
		AccountCapability empty = spellbook(maxed(), Spellbook.STANDARD).worn(items(ItemID.TOME_OF_FIRE_UNCHARGED, 1)).inventory(items(ItemID.LAWRUNE, 2))
			.bank(bank(HOUR)).build();
		assertEquals(Availability.UNKNOWN, eval("spell.trollheim", empty).getState());
		AccountCapability lava = spellbook(maxed(), Spellbook.STANDARD).worn(items(ItemID.MYSTIC_LAVA_STAFF, 1)).inventory(items(ItemID.LAWRUNE, 2)).build();
		assertEquals("lava staff: earth + fire", Availability.READY, eval("spell.civitas", lava).getState());
	}

	@Test
	public void spells_combinationRunesCount_forBothElements()
	{
		AccountCapability cap = spellbook(maxed(), Spellbook.STANDARD).inventory(items(ItemID.STEAMRUNE, 1, ItemID.LAWRUNE, 2)).build();
		assertEquals(Availability.READY, eval("spell.kourend_castle", cap).getState());
	}

	@Test
	public void runePouch_countsOnlyWhereThePouchIs()
	{
		Map<Integer, Integer> pouch = items(ItemID.LAWRUNE, 50, ItemID.AIRRUNE, 500, ItemID.WATERRUNE, 500);
		AccountCapability carried = spellbook(maxed(), Spellbook.STANDARD).inventory(items(ItemID.DIVINE_RUNE_POUCH, 1)).runePouch(pouch).build();
		assertEquals(Availability.READY, eval("spell.falador", carried).getState());

		AccountCapability banked = spellbook(maxed(), Spellbook.STANDARD).runePouch(pouch).bank(bank(HOUR, ItemID.BH_RUNE_POUCH, 1)).build();
		TransportAvailability b = eval("spell.falador", banked);
		assertEquals(Availability.IN_BANK, b.getState());
		assertTrue(have(b), have(b).contains("the rune pouch was in the bank"));

		AccountCapability nowhere = spellbook(maxed(), Spellbook.STANDARD).runePouch(pouch).build();
		assertEquals("pouch varbits alone prove nothing about what is carried", Availability.UNKNOWN, eval("spell.falador", nowhere).getState());

		AccountCapability unread = spellbook(maxed(), Spellbook.STANDARD).inventory(items(ItemID.BH_RUNE_POUCH, 1)).build();
		TransportAvailability u = eval("spell.falador", unread);
		assertEquals(Availability.UNKNOWN, u.getState());
		assertTrue(text(u), u.getMissing().toString().contains("contents are not read"));
	}

	@Test
	public void spells_runesPartlyCarried_restInBank_isInBank_withWhatToFetch()
	{
		AccountCapability cap = spellbook(maxed(), Spellbook.STANDARD).inventory(items(ItemID.AIRRUNE, 3, ItemID.WATERRUNE, 1))
			.bank(bank(HOUR, ItemID.LAWRUNE, 400)).build();
		TransportAvailability a = eval("spell.falador", cap);
		assertEquals(Availability.IN_BANK, a.getState());
		assertTrue(have(a), have(a).contains("needs 1 law from the bank"));
	}

	// -- fairy rings ------------------------------------------------------------------------------

	@Test
	public void fairyRing_staffWorn_ready_staffBanked_inBank_eliteDiaryWaivesTheStaff()
	{
		assertEquals(Availability.READY, eval("fairy_ring.cks", maxed().diary("LUMBRIDGE_ELITE", false).worn(items(ItemID.DRAMEN_STAFF, 1)).build()).getState());
		assertEquals(Availability.IN_BANK, eval("fairy_ring.cks",
			maxed().diary("LUMBRIDGE_ELITE", false).bank(bank(HOUR, ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF, 1)).build()).getState());
		TransportAvailability waived = eval("fairy_ring.cks", maxed().build());
		assertEquals(Availability.READY, waived.getState());
		assertTrue(have(waived), have(waived).contains("not needed"));
	}

	@Test
	public void fairyRing_partialQuestInProgress_isUnknown_notStarted_isUnmet()
	{
		AccountCapability inProgress = maxed().quest(Quest.FAIRYTALE_II__CURE_A_QUEEN, QuestState.IN_PROGRESS).build();
		TransportAvailability a = eval("fairy_ring.alq", inProgress);
		assertEquals(Availability.UNKNOWN, a.getState());
		assertTrue(text(a), a.getMissing().get(0).contains("not observable"));
		AccountCapability notStarted = maxed().quest(Quest.FAIRYTALE_II__CURE_A_QUEEN, QuestState.NOT_STARTED).build();
		assertEquals(Availability.REQUIREMENTS_UNMET, eval("fairy_ring.alq", notStarted).getState());
	}

	@Test
	public void fairyRing_noStaffSeen_andDiaryUnread_isUnknownNamingBoth()
	{
		AccountCapability cap = base(99).quest(Quest.FAIRYTALE_I__GROWING_PAINS, QuestState.FINISHED).quest(Quest.FAIRYTALE_II__CURE_A_QUEEN, QuestState.FINISHED)
			.build();
		TransportAvailability a = eval("fairy_ring.cks", cap);
		assertEquals(Availability.UNKNOWN, a.getState());
		assertTrue(text(a), text(a).contains("Lumbridge elite diary"));
		assertTrue(text(a), text(a).contains("bank not seen yet"));
	}

	@Test
	public void kourendUnlock_isProvenByEvidence_neverAssumed_andNeverUnmetWithoutProof()
	{
		for (String id : new String[]{"fairy_ring.cir", "fairy_ring.akr"})
		{
			assertEquals(id, Availability.READY, eval(id, maxed().build()).getState());
			assertTrue(text(eval(id, maxed().build())), eval(id, maxed().build()).getMet().toString().contains("Visited Great Kourend (proven by Client of Kourend)"));
			AccountCapability noProof = maxed().quest(Quest.CLIENT_OF_KOUREND, QuestState.NOT_STARTED).diary("KOUREND_EASY", false).build();
			TransportAvailability a = eval(id, noProof);
			assertEquals(id + " " + text(a), Availability.UNKNOWN, a.getState());
			assertTrue(text(a), a.getMissing().get(0).startsWith("Visited Great Kourend: not observable directly"));
			assertTrue("absence of proof is not an unmet requirement", a.getUnmet().isEmpty());
			AccountCapability diaryProof = maxed().quest(Quest.CLIENT_OF_KOUREND, QuestState.NOT_STARTED).build();
			assertEquals(Availability.READY, eval(id, diaryProof).getState());
		}
		AccountCapability capeNoProof = maxed().quest(Quest.CLIENT_OF_KOUREND, QuestState.IN_PROGRESS).diary("KOUREND_EASY", false)
			.worn(items(ItemID.SKILLCAPE_FARMING, 1)).build();
		assertEquals("the cape is carried but the unlock is unknown: never READY", Availability.UNKNOWN, eval("farming_cape.farming_guild", capeNoProof).getState());
	}

	@Test
	public void varlamoreRings_needChildrenOfTheSun_andTheRegulusTripProof()
	{
		assertEquals(Availability.READY, eval("fairy_ring.ais", maxed().build()).getState());
		assertEquals(Availability.REQUIREMENTS_UNMET, eval("fairy_ring.ajp", maxed().quest(Quest.CHILDREN_OF_THE_SUN, QuestState.IN_PROGRESS).build()).getState());
		TransportAvailability noTrip = eval("fairy_ring.ckq", maxed().quest(Quest.TWILIGHTS_PROMISE, QuestState.NOT_STARTED).build());
		assertEquals(Availability.UNKNOWN, noTrip.getState());
		assertTrue(text(noTrip), noTrip.getMissing().get(0).contains("Twilight's Promise started"));
	}

	@Test
	public void anUnverifiedEntry_neverExceedsUnknown()
	{
		TransportDef t = CATALOG.get("spirit_tree.gnome_stronghold");
		TransportDef copy = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(t), TransportDef.class);
		copy.provenance.verified = null;
		TransportAvailability a = TransportEvaluator.evaluate(copy, maxed().build(), EvidenceSource.NONE, NOW);
		assertEquals(Availability.UNKNOWN, a.getState());
		assertTrue(text(a), a.getMissing().get(0).startsWith("catalogue entry not verified"));
	}

	@Test
	public void freeToPlayWorld_blocksMembersTransports_butNotFreeSpells()
	{
		java.util.Map<Integer, Integer> runes = items(ItemID.AIRRUNE, 100, ItemID.FIRERUNE, 100, ItemID.LAWRUNE, 100);
		AccountCapability f2p = spellbook(maxed(), Spellbook.STANDARD).membersWorld(Known.NO).inventory(runes).build();
		assertEquals(Availability.READY, eval("spell.varrock", f2p).getState());
		TransportAvailability camelot = eval("spell.camelot", f2p);
		assertEquals(Availability.REQUIREMENTS_UNMET, camelot.getState());
		assertTrue(text(camelot), camelot.getUnmet().get(0).startsWith("Members world"));
		assertEquals(Availability.UNKNOWN, eval("spell.camelot", spellbook(maxed(), Spellbook.STANDARD).membersWorld(Known.UNKNOWN).inventory(runes).build()).getState());
	}

	@Test
	public void depleted_describesTheEvidencedVariant_andNeverClaimsTheAccountLacksAUsableOne()
	{
		TransportAvailability a = eval("xerics_talisman.xerics_glade", base(1).inventory(items(ItemID.XERIC_TALISMAN_EMPTY, 1)).build());
		assertEquals(Availability.DEPLETED, a.getState());
		assertTrue(have(a), have(a).contains("depleted variant: XERIC_TALISMAN_EMPTY x1 carried (inventory)"));
		assertTrue(have(a), have(a).contains("a usable one may be held elsewhere"));
		TransportAvailability both = eval("xerics_talisman.xerics_glade", base(1).inventory(items(ItemID.XERIC_TALISMAN_EMPTY, 1))
			.bank(bank(HOUR, ItemID.XERIC_TALISMAN, 1)).build());
		assertEquals(Availability.IN_BANK, both.getState());
		assertTrue(have(both), have(both).contains("XERIC_TALISMAN x1 last seen in the bank 1h ago") || have(both).contains("XERIC_TALISMAN x1 last seen in the bank 60m ago"));
		assertTrue(have(both), have(both).contains("also a depleted variant"));
	}

	// -- spirit trees: domain evidence -----------------------------------------------------------

	@Test
	public void playerGrownSpiritTree_followsDomainEvidence()
	{
		AccountCapability cap = maxed().build();
		String key = "farming.spirit_tree.port_sarim";
		assertEquals(Availability.READY, eval("spirit_tree.port_sarim", cap, evidence(key, Known.YES)).getState());
		assertEquals(Availability.REQUIREMENTS_UNMET, eval("spirit_tree.port_sarim", cap, evidence(key, Known.NO)).getState());
		assertEquals(Availability.UNKNOWN, eval("spirit_tree.port_sarim", cap, EvidenceSource.NONE).getState());
		assertEquals("fixed trees need no evidence", Availability.READY, eval("spirit_tree.gnome_stronghold", cap).getState());
		assertEquals(Availability.REQUIREMENTS_UNMET,
			eval("spirit_tree.farming_guild", base(84).quest(Quest.TREE_GNOME_VILLAGE, QuestState.FINISHED).build(),
				evidence("farming.spirit_tree.farming_guild", Known.YES)).getState());
	}

	// -- nothing fabricated -----------------------------------------------------------------------

	@Test
	public void anUnseenBank_neverMakesAnythingUnmet_andNothingIsReportedAsZero()
	{
		AccountCapability cap = maxed().build(); // every requirement met that can be read; inventory empty, bank never seen
		for (TransportAvailability a : TransportEvaluator.evaluateAll(CATALOG, cap, EvidenceSource.NONE, NOW))
		{
			assertNotEquals(a.getTransport().id + ": " + text(a), Availability.REQUIREMENTS_UNMET, a.getState());
			assertTrue(a.getTransport().id, !text(a).matches("(?is).*\\b(not owned|owns nothing|unavailable|0 owned)\\b.*"));
		}
	}

	@Test
	public void diagnostics_explainEveryResult()
	{
		AccountCapability cap = maxed().spellbookRaw(0).worn(items(ItemID.ARDY_CAPE_MEDIUM, 1)).bank(bank(HOUR, ItemID.XERIC_TALISMAN, 1)).build();
		java.util.List<TransportAvailability> results = TransportEvaluator.evaluateAll(CATALOG, cap, EvidenceSource.NONE, NOW);
		java.util.List<String> lines = TransportDiagnostics.availabilityLines(results);
		assertTrue(lines.size() > results.size() * 2);
		assertTrue(String.join("\n", TransportDiagnostics.capabilityLines(cap, NOW)), String.join("\n", TransportDiagnostics.capabilityLines(cap, NOW))
			.contains("spellbook: STANDARD (value 0)"));
		assertTrue(TransportDiagnostics.summary(results).startsWith(results.size() + " transports"));
		assertTrue(TransportDiagnostics.capabilityLines(AccountCapability.none(AccountProfileGate.Status.NOT_LOGGED_IN, NOW), NOW).get(0).contains("nothing is assumed"));
	}
}
