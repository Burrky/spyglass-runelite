package com.osrstelemetry.plugin.skills.herblore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.UnitFamily;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The recipe DATA (no class per potion): one primary input each, outputs map
 * back to their recipe, potion families counted in doses with every variant,
 * weapon poisons / herbs / unfinished potions never dose-normalized, the
 * fixed-vs-preserved dose rule, Potion Storage applicability, the deferred
 * list, and the Mixology / equipment item sets.
 */
public class HerbloreRecipesTest
{
	@Test
	public void everyRecipe_hasAUniqueKey_oneOutput_andExactlyOnePrimaryInput()
	{
		Set<String> keys = new HashSet<>();
		assertTrue(HerbloreRecipes.all().size() > 120);
		for (HerbloreRecipe r : HerbloreRecipes.all())
		{
			assertTrue(r.getKey(), keys.add(r.getKey()));
			assertFalse(r.getKey(), r.getOutputItems().isEmpty());
			int primaries = 0;
			for (HerbloreRecipe.Input in : r.getInputs())
			{
				primaries += in.getRole() == HerbloreRecipe.Role.PRIMARY ? 1 : 0;
				assertNotNull(r.getKey(), in.getName());
				for (Integer item : in.getItems().keySet())
				{
					assertTrue(r.getKey() + " input " + item, HerbloreRecipes.isInput(item));
					assertNotNull(r.getKey() + " input name " + item, HerbloreRecipes.name(item));
				}
			}
			assertEquals(r.getKey(), 1, primaries);
			for (Integer out : r.getOutputItems())
			{
				assertTrue(HerbloreRecipes.producing(out).contains(r));
				assertNotNull(r.getKey() + " output name", HerbloreRecipes.name(out));
			}
			assertEquals(r, HerbloreRecipes.recipe(r.getKey()));
		}
	}

	@Test
	public void potionFamilies_haveEveryDoseVariant_countedInDoses()
	{
		UnitFamily sr = HerbloreRecipes.recipe("super_restore").getOutputFamily();
		assertEquals(4, sr.getFullUnits());
		assertEquals(4, sr.unitsOf(ItemID._4DOSE2RESTORE));
		assertEquals(3, sr.unitsOf(ItemID._3DOSE2RESTORE));
		assertEquals(2, sr.unitsOf(ItemID._2DOSE2RESTORE));
		assertEquals(1, sr.unitsOf(ItemID._1DOSE2RESTORE));
		assertEquals("Super restore(3)", HerbloreRecipes.name(ItemID._3DOSE2RESTORE));
		assertEquals(sr, HerbloreRecipes.familyOf(ItemID._1DOSE2RESTORE));
		UnitFamily mix = HerbloreRecipes.recipe("prayer_mix").getOutputFamily();
		assertEquals("barbarian mixes hold 2 doses", 2, mix.getFullUnits());
	}

	@Test
	public void herbsUnfinishedPotionsPoisonsAndTars_areNeverDoseNormalized()
	{
		assertFalse(HerbloreRecipes.recipe("clean_ranarr").isDoseNormalized());
		assertFalse(HerbloreRecipes.recipe("ranarr_unf").isDoseNormalized());
		assertFalse(HerbloreRecipes.recipe("weapon_poison_plus_plus").isDoseNormalized());
		assertFalse(HerbloreRecipes.recipe("guam_tar").isDoseNormalized());
		assertNull(HerbloreCompanionDefinition.INSTANCE.unitFamily(HerbloreCompanionDefinition.INSTANCE.resource(ItemID.WEAPON_POISON__)));
		assertNull(HerbloreCompanionDefinition.INSTANCE.unitFamily(HerbloreCompanionDefinition.INSTANCE.resource(ItemID.RANARRVIAL)));
		SkillResource sr = HerbloreCompanionDefinition.INSTANCE.resource(ItemID._3DOSE2RESTORE);
		assertNotNull(HerbloreCompanionDefinition.INSTANCE.unitFamily(sr));
		assertTrue("tar is stackable", HerbloreCompanionDefinition.INSTANCE.resource(ItemID.SALAMANDER_TAR_GREEN).isStackable());
	}

	@Test
	public void processes_fixedDosesVersusPreservedDoses_andXp()
	{
		HerbloreRecipe sr = HerbloreRecipes.recipe("super_restore");
		assertEquals(HerbloreProcess.FINISHED_POTION, sr.getProcess());
		assertEquals(3, sr.getFixedDoses());
		assertTrue(sr.isBonusEligible());
		assertEquals(4, HerbloreRecipes.recipe("super_combat").getFixedDoses());
		assertFalse(HerbloreRecipes.recipe("super_combat").isBonusEligible());
		assertTrue(HerbloreRecipes.recipe("stamina").preservesDoses());
		assertEquals(HerbloreProcess.EXTENSION, HerbloreRecipes.recipe("extended_antifire").getProcess());
		assertEquals(HerbloreProcess.DIVINE, HerbloreRecipes.recipe("divine_magic").getProcess());
		assertEquals(HerbloreProcess.POISON, HerbloreRecipes.recipe("weapon_poison").getProcess());
		assertEquals(HerbloreProcess.MIX, HerbloreRecipes.recipe("attack_mix").getProcess());
		assertFalse("unfinished potions give no XP", HerbloreRecipes.recipe("ranarr_unf").isXpRequired());
		assertTrue(HerbloreRecipes.recipe("clean_ranarr").isXpRequired());
	}

	@Test
	public void currentEraRecipes_areRecognised()
	{
		for (String key : new String[]{"surge", "armadyl_brew", "super_fishing", "super_hunter", "prayer_regeneration", "goading",
			"extended_stamina", "extreme_energy", "haemostatic_dressing", "haemostatic_poultice", "menaphite_remedy", "forgotten_brew",
			"extended_anti_venom_plus", "huasca_unf", "pillar_unf", "elkhorn_unf", "umbral_unf", "clean_huasca"})
		{
			assertNotNull(key, HerbloreRecipes.recipe(key));
		}
		assertFalse("uncertain quantities claim no CAN MAKE", HerbloreRecipes.recipe("extreme_energy").isBatchReliable());
		assertFalse(HerbloreRecipes.recipe("attack_mix").isBatchReliable());
		assertTrue(HerbloreRecipes.recipe("stamina").isBatchReliable());
	}

	@Test
	public void potionStorage_appliesToPotionsAndMostUnfinished_notHerbsTarsOrTea()
	{
		assertTrue(HerbloreRecipes.storable(HerbloreRecipes.familyOf(ItemID._4DOSE2RESTORE)));
		assertTrue(HerbloreRecipes.storable(HerbloreRecipes.familyOf(ItemID.RANARRVIAL)));
		assertTrue(HerbloreRecipes.storable(HerbloreRecipes.familyOf(ItemID.WEAPON_POISON)));
		assertFalse(HerbloreRecipes.storable(HerbloreRecipes.familyOf(ItemID.RANARR_WEED)));
		assertFalse(HerbloreRecipes.storable(HerbloreRecipes.familyOf(ItemID.CUP_GUTHIX_REST_3)));
		assertFalse(HerbloreRecipes.storable(HerbloreRecipes.familyOf(ItemID.SALAMANDER_TAR_GREEN)));
	}

	@Test
	public void deferredAndBoundarySets_areDeclared()
	{
		assertTrue(HerbloreRecipes.DEFERRED.containsKey("Guthix rest"));
		assertTrue(HerbloreRecipes.DEFERRED.containsKey("Mastering Mixology"));
		assertTrue(HerbloreRecipes.MIXOLOGY_ITEMS.contains(ItemID.MM_MOX_PASTE));
		assertTrue(HerbloreRecipes.MIXOLOGY_REWARD_PACKS.contains(ItemID.MM_POTION_PACK_HIGH));
		assertTrue(HerbloreRecipes.BONUS_AMULETS.contains(ItemID.AMULET_OF_CHEMISTRY));
		assertTrue(HerbloreRecipes.GOGGLES.contains(ItemID.MM_ALCHEMIST_HAT));
		for (Integer mm : HerbloreRecipes.MIXOLOGY_ITEMS)
		{
			assertTrue("no Mixology item is ever a recipe output", HerbloreRecipes.producing(mm).isEmpty());
		}
	}
}
