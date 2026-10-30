package com.osrstelemetry.plugin.prep;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.bank;
import static com.osrstelemetry.plugin.account.AccountFixtures.base;
import static com.osrstelemetry.plugin.account.AccountFixtures.group;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountProfileGate;
import com.osrstelemetry.plugin.account.StorageEvidence;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Need statuses (canonical §8.3, Phase H): carried vs bank vs elsewhere vs not found vs unknown, with runes and pouch rules. */
public class PreparationEvaluatorTest
{
	private static final long HOUR = 3_600_000L;

	private static ItemNeed seeds(int n)
	{
		return new ItemNeed("seed.ranarr", "Ranarr seed", "Supplies", new int[]{ItemID.RANARR_SEED}, n, ItemNeed.Necessity.REQUIRED,
			ItemNeed.SatisfiedBy.INVENTORY_ONLY, "6 patches");
	}

	private static ItemNeed law(int n)
	{
		return new ItemNeed("rune.law", "Law rune", "Travel", new int[]{ItemID.LAWRUNE}, n, ItemNeed.Necessity.REQUIRED,
			ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH, "spells");
	}

	private static ItemNeed air(int n)
	{
		return new ItemNeed("rune.air", "Air rune", "Travel", new int[]{ItemID.AIRRUNE}, n, ItemNeed.Necessity.REQUIRED,
			ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH, "spells");
	}

	private static NeedResult one(ItemNeed need, AccountCapability cap)
	{
		return PreparationEvaluator.evaluate(new PreparationRequest("t", "t", Collections.singletonList(need)), cap, NOW).getResults().get(0);
	}

	@Test
	public void carried_bank_withTab_elsewhere_notFound_unknown()
	{
		assertEquals(NeedStatus.CARRIED, one(seeds(6), base(1).inventory(items(ItemID.RANARR_SEED, 6)).build()).getStatus());
		assertEquals(NeedStatus.UNKNOWN, one(seeds(6), base(1).build()).getStatus());
		assertTrue(one(seeds(6), base(1).build()).getDetail().contains("bank not seen yet"));
		StorageEvidence bank = new StorageEvidence(StorageEvidence.Kind.BANK, items(ItemID.RANARR_SEED, 40), NOW - 2 * HOUR,
			Collections.singletonMap(ItemID.RANARR_SEED, 5), new int[]{3, 4, 0, 0, 0, 0, 0, 0, 0});
		NeedResult inBank = one(seeds(6), base(1).inventory(items(ItemID.RANARR_SEED, 2)).bank(bank).build());
		assertEquals(NeedStatus.IN_BANK, inBank.getStatus());
		assertEquals(2, inBank.getBankTab());
		assertEquals(4, inBank.getStillNeeded());
		assertEquals("have 2 · bank · tab 2 (2h)", inBank.getDetail());
		NeedResult group = one(seeds(6), base(1).bank(bank(HOUR)).groupStorage(group(HOUR, ItemID.RANARR_SEED, 9)).build());
		assertEquals(NeedStatus.ELSEWHERE, group.getStatus());
		assertTrue(group.getDetail().contains("group storage (shared)"));
		NeedResult vault = one(seeds(6), base(1).bank(bank(HOUR)).seedVault(new StorageEvidence(StorageEvidence.Kind.SEED_VAULT,
			items(ItemID.RANARR_SEED, 6), NOW)).build());
		assertEquals(NeedStatus.ELSEWHERE, vault.getStatus());
		NeedResult none = one(seeds(6), base(1).inventory(items(ItemID.RANARR_SEED, 1)).bank(bank(3 * HOUR)).build());
		assertEquals(NeedStatus.NOT_FOUND, none.getStatus());
		assertEquals("only 1 found · bank seen 3h ago", none.getDetail());
	}

	@Test
	public void wornCountsOnlyWhereAllowed()
	{
		ItemNeed cloak = new ItemNeed("travel.cloak", "Ardougne cloak", "Travel", new int[]{ItemID.ARDY_CAPE_MEDIUM, ItemID.ARDY_CAPE_ELITE}, 0,
			ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_OR_WORN, "");
		assertEquals(NeedStatus.CARRIED, one(cloak, base(1).worn(items(ItemID.ARDY_CAPE_ELITE, 1)).build()).getStatus());
		assertEquals("worn", one(cloak, base(1).worn(items(ItemID.ARDY_CAPE_ELITE, 1)).build()).getDetail());
		ItemNeed tablet = new ItemNeed("travel.tab", "Varrock teleport", "Travel", new int[]{ItemID.POH_TABLET_VARROCKTELEPORT}, 1,
			ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_ONLY, "");
		assertEquals(NeedStatus.UNKNOWN, one(tablet, base(1).worn(items(ItemID.POH_TABLET_VARROCKTELEPORT, 1)).build()).getStatus());
	}

	@Test
	public void runes_countPouchOnlyWhenCarried_staffAndCombinationRunes()
	{
		AccountCapability pouch = base(1).inventory(items(ItemID.BH_RUNE_POUCH, 1)).runePouch(items(ItemID.LAWRUNE, 10)).build();
		NeedResult r = one(law(4), pouch);
		assertEquals(NeedStatus.CARRIED, r.getStatus());
		assertEquals("pouch", r.getDetail());
		AccountCapability unread = base(1).inventory(items(ItemID.BH_RUNE_POUCH, 1)).build();
		assertEquals(NeedStatus.UNKNOWN, one(law(4), unread).getStatus());
		AccountCapability banked = base(1).runePouch(items(ItemID.LAWRUNE, 10)).bank(bank(HOUR, ItemID.DIVINE_RUNE_POUCH, 1)).build();
		assertEquals("the pouch's contents are where the pouch is", NeedStatus.IN_BANK, one(law(4), banked).getStatus());
		assertEquals(NeedStatus.CARRIED, one(air(5), base(1).worn(items(ItemID.STAFF_OF_AIR, 1)).build()).getStatus());
		assertEquals("staff", one(air(5), base(1).worn(items(ItemID.STAFF_OF_AIR, 1)).build()).getDetail());
		assertEquals(NeedStatus.CARRIED, one(air(5), base(1).inventory(items(ItemID.MISTRUNE, 5)).build()).getStatus());
		assertEquals("a staff in the bank is fetchable", NeedStatus.IN_BANK, one(air(5), base(1).bank(bank(HOUR, ItemID.AIR_BATTLESTAFF, 1)).build()).getStatus());
	}

	@Test
	public void readyToGo_needsEveryRequiredNeedCarried_recommendedNeverBlocks_andHighlightsAreBankOnly()
	{
		ItemNeed rake = new ItemNeed("tool.rake", "Rake", "Tools", new int[]{ItemID.RAKE}, 0, ItemNeed.Necessity.RECOMMENDED,
			ItemNeed.SatisfiedBy.INVENTORY_ONLY, "");
		PreparationRequest req = new PreparationRequest("t", "t", Arrays.asList(seeds(6), rake));
		AccountCapability carried = base(1).inventory(items(ItemID.RANARR_SEED, 6)).bank(bank(HOUR, ItemID.RAKE, 1)).build();
		PreparationState s = PreparationEvaluator.evaluate(req, carried, NOW);
		assertTrue(s.isReadyToGo());
		assertEquals(Collections.singletonMap(ItemID.RAKE, 1L), s.bankHighlights());
		PreparationState notYet = PreparationEvaluator.evaluate(req, base(1).bank(bank(HOUR, ItemID.RANARR_SEED, 9)).build(), NOW);
		assertFalse(notYet.isReadyToGo());
		assertEquals(1, notYet.requiredLeft());
		Map<Integer, Long> h = notYet.bankHighlights();
		assertEquals(Long.valueOf(6), h.get(ItemID.RANARR_SEED));
	}

	@Test
	public void noOwner_orNoInventory_isUnknown_neverNotFound()
	{
		assertEquals(NeedStatus.UNKNOWN, one(seeds(1), AccountCapability.none(AccountProfileGate.Status.NOT_LOGGED_IN, NOW)).getStatus());
		assertEquals(NeedStatus.UNKNOWN, one(seeds(1), base(1).inventory(null).bank(bank(HOUR)).build()).getStatus());
	}

	@Test
	public void bankTab_followsTheBankBuildLayout()
	{
		java.util.Map<Integer, Integer> slots = new java.util.HashMap<>();
		slots.put(1, 0);
		slots.put(2, 2);
		slots.put(3, 6);
		slots.put(4, 7);
		StorageEvidence e = new StorageEvidence(StorageEvidence.Kind.BANK, items(1, 1, 2, 1, 3, 1, 4, 1), NOW, slots, new int[]{2, 0, 5, 0, 0, 0, 0, 0, 0});
		assertEquals(1, e.tabOf(1));
		assertEquals("an empty tab 2 is skipped", 3, e.tabOf(2));
		assertEquals(3, e.tabOf(3));
		assertEquals("after the nine counts: the main tab", 0, e.tabOf(4));
		assertEquals(-1, new StorageEvidence(StorageEvidence.Kind.BANK, items(1, 1), NOW).tabOf(1));
	}

	@Test
	public void setOff_onlyWhenConfirmedAndTheBankCloses()
	{
		SetOffDetector d = new SetOffDetector();
		assertFalse(d.onTick(Readiness.MISSING, true));
		assertFalse("bank closed without being ready", d.onTick(Readiness.MISSING, false));
		assertFalse(d.onTick(Readiness.CONFIRMED, false));
		assertFalse("ready but the bank was never open", d.onTick(Readiness.CONFIRMED, false));
		assertFalse(d.onTick(Readiness.CONFIRMED, true));
		assertTrue(d.onTick(Readiness.CONFIRMED, false));
		assertFalse("only once", d.onTick(Readiness.CONFIRMED, false));
		assertFalse(d.onTick(Readiness.CONFIRMED, true));
		assertFalse(d.onTick(Readiness.MISSING, true));
		assertFalse("not ready any more when it closed", d.onTick(Readiness.MISSING, false));
	}

	@Test
	public void setOff_neverTreatsUnverifiedTravelAsReady()
	{
		SetOffDetector d = new SetOffDetector();
		assertFalse(d.onTick(Readiness.UNVERIFIED, true));
		assertFalse("supplies carried but charges unknown: closing the bank does not close the stage", d.onTick(Readiness.UNVERIFIED, false));
		assertFalse(d.onTick(Readiness.UNVERIFIED, true));
		assertFalse(d.onTick(Readiness.CONFIRMED, false));
	}

	@Test
	public void readiness_suppliesCarriedIsNotEnoughWhileSomethingIsUnverified()
	{
		PreparationState carried = PreparationEvaluator.evaluate(new PreparationRequest("t", "t", Collections.singletonList(seeds(6))),
			base(1).inventory(items(ItemID.RANARR_SEED, 6)).build(), NOW);
		assertEquals(Readiness.CONFIRMED, carried.readiness(0));
		assertEquals(Readiness.UNVERIFIED, carried.readiness(1));
		PreparationState missing = PreparationEvaluator.evaluate(new PreparationRequest("t", "t", Collections.singletonList(seeds(6))),
			base(1).inventory(items(ItemID.RANARR_SEED, 2)).build(), NOW);
		assertEquals(Readiness.MISSING, missing.readiness(0));
		assertEquals(Readiness.MISSING, missing.readiness(2));
	}

	@Test
	public void requiredVersusOptional_onlyRequiredBlocks_andOptionalIsListed()
	{
		ItemNeed rake = new ItemNeed("tool.rake", "Rake", "Tools", new int[]{ItemID.RAKE}, 0, ItemNeed.Necessity.RECOMMENDED,
			ItemNeed.SatisfiedBy.INVENTORY_ONLY, "weeds");
		PreparationState s = PreparationEvaluator.evaluate(new PreparationRequest("t", "t", Arrays.asList(seeds(6), rake)),
			base(1).inventory(items(ItemID.RANARR_SEED, 6)).build(), NOW);
		assertTrue(s.isReadyToGo());
		assertEquals(Readiness.CONFIRMED, s.readiness(0));
		assertEquals(1, s.optionalNotCarried().size());
		assertEquals("tool.rake", s.optionalNotCarried().get(0).getNeed().getKey());
	}

	@Test
	public void sharedCharges_areCountedAcrossVariants_notPerUse()
	{
		// Two teleports on one necklace: a necklace(1) covers one use, not two.
		ItemNeed necklace = new ItemNeed("travel.skills", "Skills necklace", "Travel",
			new int[]{ItemID.JEWL_NECKLACE_OF_SKILLS_4, ItemID.JEWL_NECKLACE_OF_SKILLS_1}, 2, ItemNeed.Necessity.REQUIRED,
			ItemNeed.SatisfiedBy.INVENTORY_OR_WORN, "two sites").withUnits(new int[]{4, 1}, "charge");
		assertEquals("2 charges", necklace.quantityText());
		NeedResult one = one(necklace, base(1).inventory(items(ItemID.JEWL_NECKLACE_OF_SKILLS_1, 1)).bank(bank(HOUR)).build());
		assertEquals(NeedStatus.NOT_FOUND, one.getStatus());
		assertEquals(1, one.getStillNeeded());
		assertEquals(NeedStatus.CARRIED, one(necklace, base(1).inventory(items(ItemID.JEWL_NECKLACE_OF_SKILLS_1, 2)).build()).getStatus());
		assertEquals(NeedStatus.CARRIED, one(necklace, base(1).worn(items(ItemID.JEWL_NECKLACE_OF_SKILLS_4, 1)).build()).getStatus());
		// In the bank as necklace(1)s: withdraw two items for two charges; as a necklace(4): one.
		NeedResult banked = one(necklace, base(1).bank(bank(HOUR, ItemID.JEWL_NECKLACE_OF_SKILLS_1, 5)).build());
		assertEquals(NeedStatus.IN_BANK, banked.getStatus());
		assertEquals(2, banked.getWithdrawItems());
		NeedResult banked4 = one(necklace, base(1).bank(bank(HOUR, ItemID.JEWL_NECKLACE_OF_SKILLS_4, 1)).build());
		assertEquals(1, banked4.getWithdrawItems());
		assertEquals("the item pictured is the one that was seen", ItemID.JEWL_NECKLACE_OF_SKILLS_4, banked4.getDisplayItemId());
	}

	@Test
	public void unobservableCharges_coverTheQuantity_butOnlyAsCarried()
	{
		ItemNeed talisman = new ItemNeed("travel.xeric", "Xeric's talisman", "Travel", new int[]{ItemID.XERIC_TALISMAN}, 2,
			ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_ONLY, "two uses").withUnits(new int[]{ItemNeed.COVERS_ANY}, "charge");
		NeedResult r = one(talisman, base(1).inventory(items(ItemID.XERIC_TALISMAN, 1)).build());
		assertEquals("carrying it is all the evaluator can check; the consumer flags the charges as unverified", NeedStatus.CARRIED, r.getStatus());
	}

	@Test
	public void ownershipStaysApartFromCarried_andOldEvidenceKeepsItsAge()
	{
		StorageEvidence old = new StorageEvidence(StorageEvidence.Kind.BANK, items(ItemID.RANARR_SEED, 40), NOW - 30L * 24 * HOUR);
		NeedResult r = one(seeds(6), base(1).bank(old).build());
		assertEquals("a month-old bank view is still evidence", NeedStatus.IN_BANK, r.getStatus());
		assertFalse(r.isSatisfied());
		assertEquals(NOW - 30L * 24 * HOUR, r.getBankSeenAt());
		assertEquals(6, r.getWithdrawItems());
	}
}
