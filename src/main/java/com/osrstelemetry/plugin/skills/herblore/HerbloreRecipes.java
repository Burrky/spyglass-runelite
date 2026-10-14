package com.osrstelemetry.plugin.skills.herblore;

import com.osrstelemetry.plugin.skills.UnitFamily;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * Every Herblore recipe Spyglass recognises, as DATA (Skills Phase 6), and
 * every potion FAMILY: one family per potion whose dose variants (1 to 4, or
 * 1 to 2 for barbarian mixes) are the same resource counted in doses.
 * <p>
 * Item ids are RuneLite's own gameval constants (compile-checked). Recipe
 * quantities are only declared where they are exact and current; where they
 * are not known with certainty the recipe is still RECOGNISED (from its exact
 * output and consumed primary input) but claims no CAN MAKE and no savings.
 * Deferred recipes (multi-step quest / tea recipes whose intermediate items
 * cannot be read exactly) are listed in {@link #DEFERRED}.
 */
public final class HerbloreRecipes
{
	public static final String DOSE = "dose";
	public static final String DOSES = "doses";

	private static final Map<String, UnitFamily> FAMILIES = new LinkedHashMap<>();
	private static final Map<Integer, UnitFamily> FAMILY_BY_ITEM = new HashMap<>();
	private static final Map<Integer, String> NAMES = new HashMap<>();
	private static final Set<String> STORABLE = new LinkedHashSet<>();
	private static final Map<String, HerbloreRecipe> RECIPES = new LinkedHashMap<>();
	private static final Map<Integer, List<HerbloreRecipe>> BY_OUTPUT = new HashMap<>();
	private static final Set<Integer> INPUT_ITEMS = new LinkedHashSet<>();

	/** Recipes intentionally not tracked, with the exact reason (also in the Phase 6 report). */
	public static final Map<String, String> DEFERRED;

	/**
	 * Mastering Mixology's lab-only items (pastes, its potions, its special
	 * herb): carrying or changing any of them marks the activity's context.
	 */
	public static final Set<Integer> MIXOLOGY_ITEMS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		ItemID.MM_MOX_PASTE, ItemID.MM_AGA_PASTE, ItemID.MM_LYE_PASTE,
		ItemID.MM_POTION_MMM_UNFINISHED, ItemID.MM_POTION_MMA_UNFINISHED, ItemID.MM_POTION_MML_UNFINISHED,
		ItemID.MM_POTION_AAA_UNFINISHED, ItemID.MM_POTION_AAL_UNFINISHED, ItemID.MM_POTION_AAM_UNFINISHED,
		ItemID.MM_POTION_LLL_UNFINISHED, ItemID.MM_POTION_LLA_UNFINISHED, ItemID.MM_POTION_LLM_UNFINISHED,
		ItemID.MM_POTION_MAL_UNFINISHED,
		ItemID.MM_POTION_MMM_FINISHED, ItemID.MM_POTION_MMA_FINISHED, ItemID.MM_POTION_MML_FINISHED,
		ItemID.MM_POTION_AAA_FINISHED, ItemID.MM_POTION_AAL_FINISHED, ItemID.MM_POTION_AAM_FINISHED,
		ItemID.MM_POTION_LLL_FINISHED, ItemID.MM_POTION_LLA_FINISHED, ItemID.MM_POTION_LLM_FINISHED,
		ItemID.MM_POTION_MAL_FINISHED,
		ItemID.MM_LAB_SPECIAL_HERB)));

	/**
	 * Mixology's reward potion packs: OPENING one (it changes) is the
	 * activity's context -- its potions were never made -- but merely carrying
	 * one anywhere never is.
	 */
	public static final Set<Integer> MIXOLOGY_REWARD_PACKS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		ItemID.MM_POTION_PACK_LOW, ItemID.MM_POTION_PACK_MED, ItemID.MM_POTION_PACK_HIGH)));

	/** Amulets that can add a dose (amulet of chemistry; the alchemist's amulet, charged or not). */
	public static final Set<Integer> BONUS_AMULETS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		ItemID.AMULET_OF_CHEMISTRY, ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED, ItemID.AMULET_OF_CHEMISTRY_IMBUED_UNCHARGED)));

	/** The alchemist's amulet (its charges are a native counter). */
	public static final int ALCHEMISTS_AMULET = ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED;

	/** Prescription goggles (both looks). */
	public static final Set<Integer> GOGGLES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		ItemID.MM_ALCHEMIST_HAT, ItemID.MM_ALCHEMIST_HAT_ALT)));

	/** The reagent pouch (secondaries used from it never leave the inventory). */
	public static final Set<Integer> REAGENT_POUCH = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		ItemID.MM_SECONDARY_POUCH, ItemID.MM_SECONDARY_POUCH_OPEN)));

	private HerbloreRecipes()
	{
	}

	// ---- families --------------------------------------------------------------------------------

	private static UnitFamily dose(String key, String name, boolean storable, int d4, int d3, int d2, int d1)
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		m.put(d4, 4);
		m.put(d3, 3);
		m.put(d2, 2);
		m.put(d1, 1);
		UnitFamily f = new UnitFamily(key, name, m, 4, d4, DOSE, DOSES, "4-dose");
		register(f, storable);
		NAMES.put(d4, name + "(4)");
		NAMES.put(d3, name + "(3)");
		NAMES.put(d2, name + "(2)");
		NAMES.put(d1, name + "(1)");
		return f;
	}

	private static UnitFamily mixFamily(String key, String name, int d2, int d1)
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		m.put(d2, 2);
		m.put(d1, 1);
		UnitFamily f = new UnitFamily(key, name, m, 2, d2, DOSE, DOSES, "2-dose");
		register(f, true);
		NAMES.put(d2, name + "(2)");
		NAMES.put(d1, name + "(1)");
		return f;
	}

	/** An undosed item family of one member (Potion Storage counts it by quantity). */
	private static UnitFamily single(String key, String name, boolean storable, int itemId)
	{
		UnitFamily f = new UnitFamily(key, name, Collections.singletonMap(itemId, 1), 1, itemId, "potion", "potions", "potion");
		register(f, storable);
		NAMES.put(itemId, name);
		return f;
	}

	private static void register(UnitFamily f, boolean storable)
	{
		FAMILIES.put(f.getKey(), f);
		for (Integer item : f.getUnitsPerItem().keySet())
		{
			FAMILY_BY_ITEM.put(item, f);
		}
		if (storable)
		{
			STORABLE.add(f.getKey());
		}
	}

	private static int named(int itemId, String name)
	{
		NAMES.put(itemId, name);
		return itemId;
	}

	// Potion families (dose variants are one resource).
	static final UnitFamily ATTACK = dose("attack", "Attack potion", true, ItemID._4DOSE1ATTACK, ItemID._3DOSE1ATTACK, ItemID._2DOSE1ATTACK,
		ItemID._1DOSE1ATTACK);
	static final UnitFamily ANTIPOISON = dose("antipoison", "Antipoison", true, ItemID._4DOSEANTIPOISON, ItemID._3DOSEANTIPOISON,
		ItemID._2DOSEANTIPOISON, ItemID._1DOSEANTIPOISON);
	static final UnitFamily RELICYMS = dose("relicyms_balm", "Relicym's balm", true, ItemID.RELICYMS_BALM4, ItemID.RELICYMS_BALM3,
		ItemID.RELICYMS_BALM2, ItemID.RELICYMS_BALM1);
	static final UnitFamily STRENGTH = dose("strength", "Strength potion", true, ItemID.STRENGTH4, ItemID._3DOSE1STRENGTH,
		ItemID._2DOSE1STRENGTH, ItemID._1DOSE1STRENGTH);
	static final UnitFamily SERUM_207 = dose("serum_207", "Serum 207", true, ItemID.MORT_SERUM4, ItemID.MORT_SERUM3, ItemID.MORT_SERUM2,
		ItemID.MORT_SERUM1);
	static final UnitFamily COMPOST = dose("compost", "Compost potion", true, ItemID.SUPERCOMPOST_POTION_4, ItemID.SUPERCOMPOST_POTION_3,
		ItemID.SUPERCOMPOST_POTION_2, ItemID.SUPERCOMPOST_POTION_1);
	static final UnitFamily RESTORE = dose("restore", "Restore potion", true, ItemID._4DOSESTATRESTORE, ItemID._3DOSESTATRESTORE,
		ItemID._2DOSESTATRESTORE, ItemID._1DOSESTATRESTORE);
	static final UnitFamily ENERGY = dose("energy", "Energy potion", true, ItemID._4DOSE1ENERGY, ItemID._3DOSE1ENERGY, ItemID._2DOSE1ENERGY,
		ItemID._1DOSE1ENERGY);
	static final UnitFamily DEFENCE = dose("defence", "Defence potion", true, ItemID._4DOSE1DEFENSE, ItemID._3DOSE1DEFENSE,
		ItemID._2DOSE1DEFENSE, ItemID._1DOSE1DEFENSE);
	static final UnitFamily AGILITY = dose("agility", "Agility potion", true, ItemID._4DOSE1AGILITY, ItemID._3DOSE1AGILITY,
		ItemID._2DOSE1AGILITY, ItemID._1DOSE1AGILITY);
	static final UnitFamily COMBAT = dose("combat", "Combat potion", true, ItemID._4DOSECOMBAT, ItemID._3DOSECOMBAT, ItemID._2DOSECOMBAT,
		ItemID._1DOSECOMBAT);
	static final UnitFamily PRAYER = dose("prayer", "Prayer potion", true, ItemID._4DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE,
		ItemID._2DOSEPRAYERRESTORE, ItemID._1DOSEPRAYERRESTORE);
	static final UnitFamily SUPER_ATTACK = dose("super_attack", "Super attack", true, ItemID._4DOSE2ATTACK, ItemID._3DOSE2ATTACK,
		ItemID._2DOSE2ATTACK, ItemID._1DOSE2ATTACK);
	static final UnitFamily SUPERANTIPOISON = dose("superantipoison", "Superantipoison", true, ItemID._4DOSE2ANTIPOISON,
		ItemID._3DOSE2ANTIPOISON, ItemID._2DOSE2ANTIPOISON, ItemID._1DOSE2ANTIPOISON);
	static final UnitFamily FISHING = dose("fishing", "Fishing potion", true, ItemID._4DOSEFISHERSPOTION, ItemID._3DOSEFISHERSPOTION,
		ItemID._2DOSEFISHERSPOTION, ItemID._1DOSEFISHERSPOTION);
	static final UnitFamily SUPER_ENERGY = dose("super_energy", "Super energy", true, ItemID._4DOSE2ENERGY, ItemID._3DOSE2ENERGY,
		ItemID._2DOSE2ENERGY, ItemID._1DOSE2ENERGY);
	static final UnitFamily HUNTER = dose("hunter", "Hunter potion", true, ItemID._4DOSEHUNTING, ItemID._3DOSEHUNTING, ItemID._2DOSEHUNTING,
		ItemID._1DOSEHUNTING);
	static final UnitFamily GOADING = dose("goading", "Goading potion", true, ItemID._4DOSEGOADING, ItemID._3DOSEGOADING,
		ItemID._2DOSEGOADING, ItemID._1DOSEGOADING);
	static final UnitFamily SUPER_STRENGTH = dose("super_strength", "Super strength", true, ItemID._4DOSE2STRENGTH, ItemID._3DOSE2STRENGTH,
		ItemID._2DOSE2STRENGTH, ItemID._1DOSE2STRENGTH);
	static final UnitFamily MAGIC_ESSENCE = dose("magic_essence", "Magic essence", true, ItemID._4DOSEMAGICESS, ItemID._3DOSEMAGICESS,
		ItemID._2DOSEMAGICESS, ItemID._1DOSEMAGICESS);
	static final UnitFamily PRAYER_REGENERATION = dose("prayer_regeneration", "Prayer regeneration potion", true,
		ItemID._4DOSE1PRAYER_REGENERATION, ItemID._3DOSE1PRAYER_REGENERATION, ItemID._2DOSE1PRAYER_REGENERATION,
		ItemID._1DOSE1PRAYER_REGENERATION);
	static final UnitFamily SUPER_FISHING = dose("super_fishing", "Super fishing potion", true, ItemID._4DOSE2FISHERSPOTION,
		ItemID._3DOSE2FISHERSPOTION, ItemID._2DOSE2FISHERSPOTION, ItemID._1DOSE2FISHERSPOTION);
	static final UnitFamily SUPER_RESTORE = dose("super_restore", "Super restore", true, ItemID._4DOSE2RESTORE, ItemID._3DOSE2RESTORE,
		ItemID._2DOSE2RESTORE, ItemID._1DOSE2RESTORE);
	static final UnitFamily SANFEW = dose("sanfew_serum", "Sanfew serum", true, ItemID.SANFEW_SALVE_4_DOSE, ItemID.SANFEW_SALVE_3_DOSE,
		ItemID.SANFEW_SALVE_2_DOSE, ItemID.SANFEW_SALVE_1_DOSE);
	static final UnitFamily SUPER_DEFENCE = dose("super_defence", "Super defence", true, ItemID._4DOSE2DEFENSE, ItemID._3DOSE2DEFENSE,
		ItemID._2DOSE2DEFENSE, ItemID._1DOSE2DEFENSE);
	static final UnitFamily EXTREME_ENERGY = dose("extreme_energy", "Extreme energy potion", true, ItemID._4DOSE3ENERGY,
		ItemID._3DOSE3ENERGY, ItemID._2DOSE3ENERGY, ItemID._1DOSE3ENERGY);
	static final UnitFamily SUPER_HUNTER = dose("super_hunter", "Super hunter potion", true, ItemID._4DOSE2HUNTING, ItemID._3DOSE2HUNTING,
		ItemID._2DOSE2HUNTING, ItemID._1DOSE2HUNTING);
	static final UnitFamily ANTIDOTE_PLUS = dose("antidote_plus", "Antidote+", true, ItemID.ANTIDOTE_4, ItemID.ANTIDOTE_3, ItemID.ANTIDOTE_2,
		ItemID.ANTIDOTE_1);
	static final UnitFamily ANTIFIRE = dose("antifire", "Antifire potion", true, ItemID._4DOSE1ANTIDRAGON, ItemID._3DOSE1ANTIDRAGON,
		ItemID._2DOSE1ANTIDRAGON, ItemID._1DOSE1ANTIDRAGON);
	static final UnitFamily RANGING = dose("ranging", "Ranging potion", true, ItemID._4DOSERANGERSPOTION, ItemID._3DOSERANGERSPOTION,
		ItemID._2DOSERANGERSPOTION, ItemID._1DOSERANGERSPOTION);
	static final UnitFamily MAGIC = dose("magic", "Magic potion", true, ItemID._4DOSE1MAGIC, ItemID._3DOSE1MAGIC, ItemID._2DOSE1MAGIC,
		ItemID._1DOSE1MAGIC);
	static final UnitFamily STAMINA = dose("stamina", "Stamina potion", true, ItemID._4DOSESTAMINA, ItemID._3DOSESTAMINA,
		ItemID._2DOSESTAMINA, ItemID._1DOSESTAMINA);
	static final UnitFamily ZAMORAK_BREW = dose("zamorak_brew", "Zamorak brew", true, ItemID._4DOSEPOTIONOFZAMORAK,
		ItemID._3DOSEPOTIONOFZAMORAK, ItemID._2DOSEPOTIONOFZAMORAK, ItemID._1DOSEPOTIONOFZAMORAK);
	static final UnitFamily ANTIDOTE_PLUS_PLUS = dose("antidote_plus_plus", "Antidote++", true, ItemID.ANTIDOTE__4, ItemID.ANTIDOTE__3,
		ItemID.ANTIDOTE__2, ItemID.ANTIDOTE__1);
	static final UnitFamily BASTION = dose("bastion", "Bastion potion", true, ItemID._4DOSEBASTION, ItemID._3DOSEBASTION,
		ItemID._2DOSEBASTION, ItemID._1DOSEBASTION);
	static final UnitFamily BATTLEMAGE = dose("battlemage", "Battlemage potion", true, ItemID._4DOSEBATTLEMAGE, ItemID._3DOSEBATTLEMAGE,
		ItemID._2DOSEBATTLEMAGE, ItemID._1DOSEBATTLEMAGE);
	static final UnitFamily SARADOMIN_BREW = dose("saradomin_brew", "Saradomin brew", true, ItemID._4DOSEPOTIONOFSARADOMIN,
		ItemID._3DOSEPOTIONOFSARADOMIN, ItemID._2DOSEPOTIONOFSARADOMIN, ItemID._1DOSEPOTIONOFSARADOMIN);
	static final UnitFamily SURGE = dose("surge", "Surge potion", true, ItemID._4DOSESURGE, ItemID._3DOSESURGE, ItemID._2DOSESURGE,
		ItemID._1DOSESURGE);
	static final UnitFamily EXTENDED_ANTIFIRE = dose("extended_antifire", "Extended antifire", true, ItemID._4DOSE2ANTIDRAGON,
		ItemID._3DOSE2ANTIDRAGON, ItemID._2DOSE2ANTIDRAGON, ItemID._1DOSE2ANTIDRAGON);
	static final UnitFamily EXTENDED_STAMINA = dose("extended_stamina", "Extended stamina potion", true, ItemID._4DOSE2STAMINA,
		ItemID._3DOSE2STAMINA, ItemID._2DOSE2STAMINA, ItemID._1DOSE2STAMINA);
	static final UnitFamily ANCIENT_BREW = dose("ancient_brew", "Ancient brew", true, ItemID._4DOSEANCIENTBREW, ItemID._3DOSEANCIENTBREW,
		ItemID._2DOSEANCIENTBREW, ItemID._1DOSEANCIENTBREW);
	static final UnitFamily ANTI_VENOM = dose("anti_venom", "Anti-venom", true, ItemID.ANTIVENOM4, ItemID.ANTIVENOM3, ItemID.ANTIVENOM2,
		ItemID.ANTIVENOM1);
	static final UnitFamily MENAPHITE_REMEDY = dose("menaphite_remedy", "Menaphite remedy", true, ItemID._4DOSESTATRENEWAL,
		ItemID._3DOSESTATRENEWAL, ItemID._2DOSESTATRENEWAL, ItemID._1DOSESTATRENEWAL);
	static final UnitFamily ARMADYL_BREW = dose("armadyl_brew", "Armadyl brew", true, ItemID._4DOSEARMADYLBREW, ItemID._3DOSEARMADYLBREW,
		ItemID._2DOSEARMADYLBREW, ItemID._1DOSEARMADYLBREW);
	static final UnitFamily SUPER_COMBAT = dose("super_combat", "Super combat potion", true, ItemID._4DOSE2COMBAT, ItemID._3DOSE2COMBAT,
		ItemID._2DOSE2COMBAT, ItemID._1DOSE2COMBAT);
	static final UnitFamily FORGOTTEN_BREW = dose("forgotten_brew", "Forgotten brew", true, ItemID._4DOSEFORGOTTENBREW,
		ItemID._3DOSEFORGOTTENBREW, ItemID._2DOSEFORGOTTENBREW, ItemID._1DOSEFORGOTTENBREW);
	static final UnitFamily SUPER_ANTIFIRE = dose("super_antifire", "Super antifire potion", true, ItemID._4DOSE3ANTIDRAGON,
		ItemID._3DOSE3ANTIDRAGON, ItemID._2DOSE3ANTIDRAGON, ItemID._1DOSE3ANTIDRAGON);
	static final UnitFamily ANTI_VENOM_PLUS = dose("anti_venom_plus", "Anti-venom+", true, ItemID.ANTIVENOM_4, ItemID.ANTIVENOM_3,
		ItemID.ANTIVENOM_2, ItemID.ANTIVENOM_1);
	static final UnitFamily EXTENDED_ANTI_VENOM_PLUS = dose("extended_anti_venom_plus", "Extended anti-venom+", true,
		ItemID.EXTENDED_ANTIVENOM_4, ItemID.EXTENDED_ANTIVENOM_3, ItemID.EXTENDED_ANTIVENOM_2, ItemID.EXTENDED_ANTIVENOM_1);
	static final UnitFamily EXTENDED_SUPER_ANTIFIRE = dose("extended_super_antifire", "Extended super antifire", true,
		ItemID._4DOSE4ANTIDRAGON, ItemID._3DOSE4ANTIDRAGON, ItemID._2DOSE4ANTIDRAGON, ItemID._1DOSE4ANTIDRAGON);
	static final UnitFamily DIVINE_SUPER_ATTACK = dose("divine_super_attack", "Divine super attack potion", true,
		ItemID._4DOSEDIVINEATTACK, ItemID._3DOSEDIVINEATTACK, ItemID._2DOSEDIVINEATTACK, ItemID._1DOSEDIVINEATTACK);
	static final UnitFamily DIVINE_SUPER_STRENGTH = dose("divine_super_strength", "Divine super strength potion", true,
		ItemID._4DOSEDIVINESTRENGTH, ItemID._3DOSEDIVINESTRENGTH, ItemID._2DOSEDIVINESTRENGTH, ItemID._1DOSEDIVINESTRENGTH);
	static final UnitFamily DIVINE_SUPER_DEFENCE = dose("divine_super_defence", "Divine super defence potion", true,
		ItemID._4DOSEDIVINEDEFENCE, ItemID._3DOSEDIVINEDEFENCE, ItemID._2DOSEDIVINEDEFENCE, ItemID._1DOSEDIVINEDEFENCE);
	static final UnitFamily DIVINE_RANGING = dose("divine_ranging", "Divine ranging potion", true, ItemID._4DOSEDIVINERANGE,
		ItemID._3DOSEDIVINERANGE, ItemID._2DOSEDIVINERANGE, ItemID._1DOSEDIVINERANGE);
	static final UnitFamily DIVINE_MAGIC = dose("divine_magic", "Divine magic potion", true, ItemID._4DOSEDIVINEMAGIC,
		ItemID._3DOSEDIVINEMAGIC, ItemID._2DOSEDIVINEMAGIC, ItemID._1DOSEDIVINEMAGIC);
	static final UnitFamily DIVINE_BASTION = dose("divine_bastion", "Divine bastion potion", true, ItemID._4DOSEDIVINEBASTION,
		ItemID._3DOSEDIVINEBASTION, ItemID._2DOSEDIVINEBASTION, ItemID._1DOSEDIVINEBASTION);
	static final UnitFamily DIVINE_BATTLEMAGE = dose("divine_battlemage", "Divine battlemage potion", true, ItemID._4DOSEDIVINEBATTLEMAGE,
		ItemID._3DOSEDIVINEBATTLEMAGE, ItemID._2DOSEDIVINEBATTLEMAGE, ItemID._1DOSEDIVINEBATTLEMAGE);
	static final UnitFamily DIVINE_SUPER_COMBAT = dose("divine_super_combat", "Divine super combat potion", true, ItemID._4DOSEDIVINECOMBAT,
		ItemID._3DOSEDIVINECOMBAT, ItemID._2DOSEDIVINECOMBAT, ItemID._1DOSEDIVINECOMBAT);
	static final UnitFamily HAEMOSTATIC_DRESSING = dose("haemostatic_dressing", "Haemostatic dressing", true,
		ItemID._4DOSEHAEMOSTATICDRESSING, ItemID._3DOSEHAEMOSTATICDRESSING, ItemID._2DOSEHAEMOSTATICDRESSING,
		ItemID._1DOSEHAEMOSTATICDRESSING);
	static final UnitFamily GUTHIX_REST = dose("guthix_rest", "Guthix rest", false, ItemID.CUP_GUTHIX_REST_4, ItemID.CUP_GUTHIX_REST_3,
		ItemID.CUP_GUTHIX_REST_2, ItemID.CUP_GUTHIX_REST_1);

	// Unfinished potions (undosed; Potion Storage keeps most of them by quantity).
	static final UnitFamily GUAM_UNF = single("guam_unf", "Guam potion (unf)", true, ItemID.GUAMVIAL);
	static final UnitFamily MARRENTILL_UNF = single("marrentill_unf", "Marrentill potion (unf)", true, ItemID.MARRENTILLVIAL);
	static final UnitFamily TARROMIN_UNF = single("tarromin_unf", "Tarromin potion (unf)", true, ItemID.TARROMINVIAL);
	static final UnitFamily HARRALANDER_UNF = single("harralander_unf", "Harralander potion (unf)", true, ItemID.HARRALANDERVIAL);
	static final UnitFamily RANARR_UNF = single("ranarr_unf", "Ranarr potion (unf)", true, ItemID.RANARRVIAL);
	static final UnitFamily TOADFLAX_UNF = single("toadflax_unf", "Toadflax potion (unf)", true, ItemID.TOADFLAXVIAL);
	static final UnitFamily IRIT_UNF = single("irit_unf", "Irit potion (unf)", true, ItemID.IRITVIAL);
	static final UnitFamily AVANTOE_UNF = single("avantoe_unf", "Avantoe potion (unf)", true, ItemID.AVANTOEVIAL);
	static final UnitFamily KWUARM_UNF = single("kwuarm_unf", "Kwuarm potion (unf)", true, ItemID.KWUARMVIAL);
	static final UnitFamily HUASCA_UNF = single("huasca_unf", "Huasca potion (unf)", true, ItemID.HUASCAVIAL);
	static final UnitFamily SNAPDRAGON_UNF = single("snapdragon_unf", "Snapdragon potion (unf)", true, ItemID.SNAPDRAGONVIAL);
	static final UnitFamily CADANTINE_UNF = single("cadantine_unf", "Cadantine potion (unf)", true, ItemID.CADANTINEVIAL);
	static final UnitFamily CADANTINE_BLOOD_UNF = single("cadantine_blood_unf", "Cadantine blood potion (unf)", true,
		ItemID.CADANTINE_BLOODVIAL);
	static final UnitFamily LANTADYME_UNF = single("lantadyme_unf", "Lantadyme potion (unf)", true, ItemID.LANTADYMEVIAL);
	static final UnitFamily DWARF_WEED_UNF = single("dwarf_weed_unf", "Dwarf weed potion (unf)", true, ItemID.DWARFWEEDVIAL);
	static final UnitFamily TORSTOL_UNF = single("torstol_unf", "Torstol potion (unf)", true, ItemID.TORSTOLVIAL);
	static final UnitFamily ELKHORN_UNF = single("elkhorn_unf", "Elkhorn potion (unf)", true, ItemID.ELKHORNVIAL);
	static final UnitFamily PILLAR_UNF = single("pillar_unf", "Pillar potion (unf)", true, ItemID.PILLARVIAL);
	static final UnitFamily UMBRAL_UNF = single("umbral_unf", "Umbral potion (unf)", true, ItemID.UMBRALVIAL);
	static final UnitFamily ANTIDOTE_PLUS_UNF = single("antidote_plus_unf", "Antidote+ (unf)", false, ItemID.UNFINISHED_ANTIDOTE_);
	static final UnitFamily ANTIDOTE_PLUS_PLUS_UNF = single("antidote_plus_plus_unf", "Antidote++ (unf)", false, ItemID.UNFINISHED_ANTIDOTE__);
	static final UnitFamily WEAPON_POISON_PLUS_UNF = single("weapon_poison_plus_unf", "Weapon poison(+) (unf)", false,
		ItemID.UNFINISHED_WEAPON_POISON_);
	static final UnitFamily WEAPON_POISON_PLUS_PLUS_UNF = single("weapon_poison_plus_plus_unf", "Weapon poison(++) (unf)", false,
		ItemID.UNFINISHED_WEAPON_POISON__);
	static final UnitFamily HAEMOSTATIC_POULTICE = single("haemostatic_poultice", "Haemostatic poultice", false, ItemID.HAEMOSTATIC_POULTICE);

	// Weapon poisons: not drinkable, never dose-normalized (Potion Storage keeps them by quantity).
	static final UnitFamily WEAPON_POISON = single("weapon_poison", "Weapon poison", true, ItemID.WEAPON_POISON);
	static final UnitFamily WEAPON_POISON_PLUS = single("weapon_poison_plus", "Weapon poison(+)", true, ItemID.WEAPON_POISON_);
	static final UnitFamily WEAPON_POISON_PLUS_PLUS = single("weapon_poison_plus_plus", "Weapon poison(++)", true, ItemID.WEAPON_POISON__);

	// ---- recipe builders ------------------------------------------------------------------------

	private static HerbloreRecipe.Input input(HerbloreRecipe.Role role, String name, String caption, Integer perMix, Integer perDose, boolean savable,
		int... items)
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		for (int i : items)
		{
			m.put(i, 1);
			NAMES.putIfAbsent(i, name);
			INPUT_ITEMS.add(i);
		}
		return new HerbloreRecipe.Input(role, name, caption, m, false, perMix, perDose, savable);
	}

	private static HerbloreRecipe.Input dosedBase(String caption, UnitFamily family, int... onlyDoses)
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		Set<Integer> allowed = new LinkedHashSet<>();
		for (int d : onlyDoses)
		{
			allowed.add(d);
		}
		for (Map.Entry<Integer, Integer> e : family.getUnitsPerItem().entrySet())
		{
			if (allowed.isEmpty() || allowed.contains(e.getValue()))
			{
				m.put(e.getKey(), e.getValue());
				INPUT_ITEMS.add(e.getKey());
			}
		}
		return new HerbloreRecipe.Input(HerbloreRecipe.Role.PRIMARY, family.getName(), caption, m, true, 1, null, false);
	}

	/** A (4)-dose potion used whole as an extra input (super combat's three potions). */
	private static HerbloreRecipe.Input wholePotion(String caption, UnitFamily family)
	{
		int four = family.getCanonicalItemId();
		INPUT_ITEMS.add(four);
		return new HerbloreRecipe.Input(HerbloreRecipe.Role.SECONDARY, family.getName() + "(4)", caption,
			Collections.singletonMap(four, 1), false, 1, null, false);
	}

	private static void add(HerbloreRecipe r)
	{
		RECIPES.put(r.getKey(), r);
		for (Integer out : r.getOutputItems())
		{
			BY_OUTPUT.computeIfAbsent(out, k -> new ArrayList<>()).add(r);
		}
	}

	private static String key(String name)
	{
		return name.toLowerCase(java.util.Locale.ROOT).replace("'", "").replace("+", "_plus").replaceAll("[^a-z0-9_]+", "_")
			.replaceAll("_+", "_").replaceAll("^_|_$", "");
	}

	private static void clean(String herb, String shortName, int grimy, int cleanItem)
	{
		named(cleanItem, herb);
		UnitFamily out = new UnitFamily("herb_" + key(shortName), herb, Collections.singletonMap(cleanItem, 1), 1, cleanItem,
			"herb", "herbs", "herb");
		add(new HerbloreRecipe("clean_" + key(shortName), "Clean " + shortName, HerbloreProcess.CLEANING,
			Collections.singletonList(input(HerbloreRecipe.Role.PRIMARY, "Grimy " + herb.toLowerCase(java.util.Locale.ROOT), "GRIMY", 1, null,
				false, grimy)), null, Collections.singleton(cleanItem), herb, 1, true, false, false, true));
		FAMILY_BY_ITEM.putIfAbsent(cleanItem, out);
	}

	private static void unfinished(String herb, String herbCaption, int herbItem, UnitFamily unf, int vial, String vialName)
	{
		add(new HerbloreRecipe(unf.getKey(), unf.getName(), HerbloreProcess.UNFINISHED_POTION, Arrays.asList(
			input(HerbloreRecipe.Role.PRIMARY, herb, herbCaption, 1, null, false, herbItem),
			input(HerbloreRecipe.Role.VIAL, vialName, vialName.startsWith("Vial of water") ? "VIALS" : vialName.toUpperCase(java.util.Locale.ROOT),
				1, null, false, vial)),
			null, Collections.singleton(unf.getCanonicalItemId()), unf.getName(), 1, false, false, STORABLE.contains(unf.getKey()), true));
	}

	/** Unfinished potion (or another single item) + one secondary -> a fixed-dose potion. */
	private static void finished(UnitFamily out, int fixedDoses, UnitFamily unf, String secondary, int secondaryItem, boolean savable,
		boolean bonusEligible)
	{
		add(new HerbloreRecipe(out.getKey(), out.getName(), HerbloreProcess.FINISHED_POTION, Arrays.asList(
			input(HerbloreRecipe.Role.PRIMARY, unf.getName(), "UNFINISHED", 1, null, false, unf.getCanonicalItemId()),
			input(HerbloreRecipe.Role.SECONDARY, secondary, "SECONDARY", 1, null, savable, secondaryItem)),
			out, new LinkedHashSet<>(out.getUnitsPerItem().keySet()), null, fixedDoses, true, bonusEligible, STORABLE.contains(out.getKey()), true));
	}

	/** Base potion (any dose) + {@code perDose} of an ingredient per dose -> the same doses of the upgraded potion. */
	private static void upgrade(HerbloreProcess process, UnitFamily out, UnitFamily base, String baseCaption, String ingredient, int ingredientItem,
		Integer perDose, boolean savable)
	{
		add(new HerbloreRecipe(out.getKey(), out.getName(), process, Arrays.asList(
			dosedBase(baseCaption, base),
			input(HerbloreRecipe.Role.SECONDARY, ingredient, "SECONDARY", null, perDose, savable, ingredientItem)),
			out, new LinkedHashSet<>(out.getUnitsPerItem().keySet()), null, 0, true, true, STORABLE.contains(out.getKey()), perDose != null));
	}

	private static void poison(UnitFamily out, UnitFamily unf, String secondary, int secondaryItem)
	{
		add(new HerbloreRecipe(out.getKey(), out.getName(), HerbloreProcess.POISON, Arrays.asList(
			input(HerbloreRecipe.Role.PRIMARY, unf.getName(), "UNFINISHED", 1, null, false, unf.getCanonicalItemId()),
			input(HerbloreRecipe.Role.SECONDARY, secondary, "SECONDARY", 1, null, true, secondaryItem)),
			null, Collections.singleton(out.getCanonicalItemId()), out.getName(), 1, true, false, STORABLE.contains(out.getKey()), true));
	}

	private static void barbarianMix(String name, int mix2, int mix1, UnitFamily base)
	{
		UnitFamily out = mixFamily(key(name), name, mix2, mix1);
		add(new HerbloreRecipe(out.getKey(), name, HerbloreProcess.MIX, Arrays.asList(
			dosedBase(base.getName().toUpperCase(java.util.Locale.ROOT), base, 2),
			// Roe or caviar: which one each mix takes is not claimed here, so no CAN MAKE / savings.
			input(HerbloreRecipe.Role.SECONDARY, "Roe / caviar", "ROE / CAVIAR", null, null, false, ItemID.BRUT_ROE, ItemID.BRUT_CAVIAR)),
			out, new LinkedHashSet<>(out.getUnitsPerItem().keySet()), null, 2, true, false, true, false));
	}

	private static void tar(String name, int herb, String herbName, int tarItem)
	{
		named(tarItem, name);
		add(new HerbloreRecipe(key(name), name, HerbloreProcess.SPECIAL, Arrays.asList(
			input(HerbloreRecipe.Role.PRIMARY, herbName, "HERBS", 1, null, false, herb),
			input(HerbloreRecipe.Role.SECONDARY, "Swamp tar", "SWAMP TAR", 15, null, false, ItemID.SWAMP_TAR)),
			null, Collections.singleton(tarItem), name, 1, true, false, false, true));
	}

	static
	{
		String water = "Vial of water";
		int vial = named(ItemID.VIAL_WATER, water);

		// Cleaning (Herblore XP).
		clean("Guam leaf", "guam", ItemID.UNIDENTIFIED_GUAM, ItemID.GUAM_LEAF);
		clean("Marrentill", "marrentill", ItemID.UNIDENTIFIED_MARENTILL, ItemID.MARENTILL);
		clean("Tarromin", "tarromin", ItemID.UNIDENTIFIED_TARROMIN, ItemID.TARROMIN);
		clean("Harralander", "harralander", ItemID.UNIDENTIFIED_HARRALANDER, ItemID.HARRALANDER);
		clean("Ranarr weed", "ranarr", ItemID.UNIDENTIFIED_RANARR, ItemID.RANARR_WEED);
		clean("Toadflax", "toadflax", ItemID.UNIDENTIFIED_TOADFLAX, ItemID.TOADFLAX);
		clean("Irit leaf", "irit", ItemID.UNIDENTIFIED_IRIT, ItemID.IRIT_LEAF);
		clean("Avantoe", "avantoe", ItemID.UNIDENTIFIED_AVANTOE, ItemID.AVANTOE);
		clean("Kwuarm", "kwuarm", ItemID.UNIDENTIFIED_KWUARM, ItemID.KWUARM);
		clean("Huasca", "huasca", ItemID.UNIDENTIFIED_HUASCA, ItemID.HUASCA);
		clean("Snapdragon", "snapdragon", ItemID.UNIDENTIFIED_SNAPDRAGON, ItemID.SNAPDRAGON);
		clean("Cadantine", "cadantine", ItemID.UNIDENTIFIED_CADANTINE, ItemID.CADANTINE);
		clean("Lantadyme", "lantadyme", ItemID.UNIDENTIFIED_LANTADYME, ItemID.LANTADYME);
		clean("Dwarf weed", "dwarf weed", ItemID.UNIDENTIFIED_DWARF_WEED, ItemID.DWARF_WEED);
		clean("Torstol", "torstol", ItemID.UNIDENTIFIED_TORSTOL, ItemID.TORSTOL);

		// Unfinished potions (no Herblore XP in the game).
		unfinished("Guam leaf", "GUAM", ItemID.GUAM_LEAF, GUAM_UNF, vial, water);
		unfinished("Marrentill", "MARRENTILL", ItemID.MARENTILL, MARRENTILL_UNF, vial, water);
		unfinished("Tarromin", "TARROMIN", ItemID.TARROMIN, TARROMIN_UNF, vial, water);
		unfinished("Harralander", "HARRALANDER", ItemID.HARRALANDER, HARRALANDER_UNF, vial, water);
		unfinished("Ranarr weed", "RANARR", ItemID.RANARR_WEED, RANARR_UNF, vial, water);
		unfinished("Toadflax", "TOADFLAX", ItemID.TOADFLAX, TOADFLAX_UNF, vial, water);
		unfinished("Irit leaf", "IRIT", ItemID.IRIT_LEAF, IRIT_UNF, vial, water);
		unfinished("Avantoe", "AVANTOE", ItemID.AVANTOE, AVANTOE_UNF, vial, water);
		unfinished("Kwuarm", "KWUARM", ItemID.KWUARM, KWUARM_UNF, vial, water);
		unfinished("Huasca", "HUASCA", ItemID.HUASCA, HUASCA_UNF, vial, water);
		unfinished("Snapdragon", "SNAPDRAGON", ItemID.SNAPDRAGON, SNAPDRAGON_UNF, vial, water);
		unfinished("Cadantine", "CADANTINE", ItemID.CADANTINE, CADANTINE_UNF, vial, water);
		unfinished("Lantadyme", "LANTADYME", ItemID.LANTADYME, LANTADYME_UNF, vial, water);
		unfinished("Dwarf weed", "DWARF WEED", ItemID.DWARF_WEED, DWARF_WEED_UNF, vial, water);
		unfinished("Torstol", "TORSTOL", ItemID.TORSTOL, TORSTOL_UNF, vial, water);
		unfinished("Elkhorn coral", "CORAL", ItemID.CORAL_ELKHORN, ELKHORN_UNF, vial, water);
		unfinished("Pillar coral", "CORAL", ItemID.CORAL_PILLAR, PILLAR_UNF, vial, water);
		unfinished("Umbral coral", "CORAL", ItemID.CORAL_UMBRAL, UMBRAL_UNF, vial, water);
		unfinished("Cadantine", "CADANTINE", ItemID.CADANTINE, CADANTINE_BLOOD_UNF, named(ItemID.VIAL_BLOOD, "Vial of blood"), "Vial of blood");
		unfinished("Toadflax", "TOADFLAX", ItemID.TOADFLAX, ANTIDOTE_PLUS_UNF, named(ItemID.VIAL_COCONUT_MILK, "Coconut milk"), "Coconut milk");
		unfinished("Irit leaf", "IRIT", ItemID.IRIT_LEAF, ANTIDOTE_PLUS_PLUS_UNF, ItemID.VIAL_COCONUT_MILK, "Coconut milk");
		unfinished("Cactus spine", "CACTUS SPINE", ItemID.CACTUS_SPINE, WEAPON_POISON_PLUS_UNF, ItemID.VIAL_COCONUT_MILK, "Coconut milk");
		unfinished("Nightshade", "NIGHTSHADE", ItemID.NIGHTSHADE, WEAPON_POISON_PLUS_PLUS_UNF, ItemID.VIAL_COCONUT_MILK, "Coconut milk");

		// Finished potions: unfinished potion + secondary -> 3 doses (4 with a bonus dose).
		finished(ATTACK, 3, GUAM_UNF, "Eye of newt", ItemID.EYE_OF_NEWT, true, true);
		finished(ANTIPOISON, 3, MARRENTILL_UNF, "Unicorn horn dust", ItemID.UNICORN_HORN_DUST, true, true);
		finished(STRENGTH, 3, TARROMIN_UNF, "Limpwurt root", ItemID.LIMPWURT_ROOT, true, true);
		// The goggles never save ashes (serum 207).
		finished(SERUM_207, 3, TARROMIN_UNF, "Ashes", ItemID.ASHES, false, true);
		finished(COMPOST, 3, HARRALANDER_UNF, "Volcanic ash", ItemID.FOSSIL_VOLCANIC_ASH, true, true);
		finished(RESTORE, 3, HARRALANDER_UNF, "Red spiders' eggs", ItemID.RED_SPIDERS_EGGS, true, true);
		finished(ENERGY, 3, HARRALANDER_UNF, "Chocolate dust", ItemID.CHOCOLATE_DUST, true, true);
		finished(DEFENCE, 3, RANARR_UNF, "White berries", ItemID.WHITE_BERRIES, true, true);
		finished(AGILITY, 3, TOADFLAX_UNF, "Toad's legs", ItemID.TOADS_LEGS, true, true);
		finished(COMBAT, 3, HARRALANDER_UNF, "Goat horn dust", ItemID.GROUND_DESERT_GOAT_HORN, true, true);
		finished(PRAYER, 3, RANARR_UNF, "Snape grass", ItemID.SNAPE_GRASS, true, true);
		finished(SUPER_ATTACK, 3, IRIT_UNF, "Eye of newt", ItemID.EYE_OF_NEWT, true, true);
		finished(SUPERANTIPOISON, 3, IRIT_UNF, "Unicorn horn dust", ItemID.UNICORN_HORN_DUST, true, true);
		finished(FISHING, 3, AVANTOE_UNF, "Snape grass", ItemID.SNAPE_GRASS, true, true);
		finished(SUPER_ENERGY, 3, AVANTOE_UNF, "Mort myre fungus", ItemID.MORTMYREMUSHROOM, true, true);
		finished(HUNTER, 3, AVANTOE_UNF, "Kebbit teeth dust", ItemID.HUNTINGBEAST_SABRETEETH_DUST, true, true);
		finished(GOADING, 3, HARRALANDER_UNF, "Aldarium", ItemID.ALDARIUM, true, true);
		finished(SUPER_STRENGTH, 3, KWUARM_UNF, "Limpwurt root", ItemID.LIMPWURT_ROOT, true, true);
		finished(PRAYER_REGENERATION, 3, HUASCA_UNF, "Aldarium", ItemID.ALDARIUM, true, true);
		finished(SUPER_FISHING, 3, PILLAR_UNF, "Haddock eye", ItemID.HADDOCK_EYE, true, true);
		finished(SUPER_RESTORE, 3, SNAPDRAGON_UNF, "Red spiders' eggs", ItemID.RED_SPIDERS_EGGS, true, true);
		finished(SUPER_DEFENCE, 3, CADANTINE_UNF, "White berries", ItemID.WHITE_BERRIES, true, true);
		finished(SUPER_HUNTER, 3, PILLAR_UNF, "Crab paste", ItemID.CRAB_PASTE, true, true);
		finished(ANTIFIRE, 3, LANTADYME_UNF, "Dragon scale dust", ItemID.DRAGON_SCALE_DUST, true, true);
		finished(RANGING, 3, DWARF_WEED_UNF, "Wine of zamorak", ItemID.WINE_OF_ZAMORAK, true, true);
		finished(MAGIC, 3, LANTADYME_UNF, "Potato cactus", ItemID.CACTUS_POTATO, true, true);
		finished(ZAMORAK_BREW, 3, TORSTOL_UNF, "Jangerberries", ItemID.JANGERBERRIES, true, true);
		finished(BASTION, 3, CADANTINE_BLOOD_UNF, "Wine of zamorak", ItemID.WINE_OF_ZAMORAK, true, true);
		finished(BATTLEMAGE, 3, CADANTINE_BLOOD_UNF, "Potato cactus", ItemID.CACTUS_POTATO, true, true);
		finished(SARADOMIN_BREW, 3, TOADFLAX_UNF, "Crushed nest", ItemID.CRUSHED_BIRD_NEST, true, true);
		finished(SURGE, 3, TORSTOL_UNF, "Demonic tallow", ItemID.DEMONIC_TALLOW, true, true);
		finished(MENAPHITE_REMEDY, 3, DWARF_WEED_UNF, "Lily of the sands", ItemID.LILY_OF_THE_SANDS, true, true);
		finished(ARMADYL_BREW, 3, UMBRAL_UNF, "Rainbow crab paste", ItemID.RAINBOW_CRAB_PASTE, true, true);
		// Ancient brew's dose count per mix is not claimed here: never a bonus dose.
		finished(ANCIENT_BREW, 3, DWARF_WEED_UNF, "Nihil dust", ItemID.NIHIL_DUST, true, false);
		finished(HAEMOSTATIC_DRESSING, 3, HAEMOSTATIC_POULTICE, "Cotton yarn", ItemID.COTTON_YARN, true, true);
		// Always-4-dose potions: never a bonus dose.
		finished(ANTIDOTE_PLUS, 4, ANTIDOTE_PLUS_UNF, "Yew roots", ItemID.YEW_ROOTS, true, false);
		finished(ANTIDOTE_PLUS_PLUS, 4, ANTIDOTE_PLUS_PLUS_UNF, "Magic roots", ItemID.MAGIC_ROOTS, true, false);

		// Haemostatic poultice (an undosed half-product with its own Herblore XP).
		add(new HerbloreRecipe(HAEMOSTATIC_POULTICE.getKey(), HAEMOSTATIC_POULTICE.getName(), HerbloreProcess.SPECIAL, Arrays.asList(
			input(HerbloreRecipe.Role.PRIMARY, ELKHORN_UNF.getName(), "UNFINISHED", 1, null, false, ItemID.ELKHORNVIAL),
			input(HerbloreRecipe.Role.SECONDARY, "Squid paste", "SECONDARY", 1, null, true, ItemID.SQUID_PASTE)),
			null, Collections.singleton(ItemID.HAEMOSTATIC_POULTICE), HAEMOSTATIC_POULTICE.getName(), 1, true, false, false, true));

		// Super combat: torstol (or its unfinished potion) + the three 4-dose super potions -> 4 doses.
		add(new HerbloreRecipe(SUPER_COMBAT.getKey(), SUPER_COMBAT.getName(), HerbloreProcess.FINISHED_POTION, Arrays.asList(
			input(HerbloreRecipe.Role.PRIMARY, "Torstol", "TORSTOL", 1, null, true, ItemID.TORSTOL, ItemID.TORSTOLVIAL),
			wholePotion("SUPER ATTACK", SUPER_ATTACK),
			wholePotion("SUPER STRENGTH", SUPER_STRENGTH),
			wholePotion("SUPER DEFENCE", SUPER_DEFENCE)),
			SUPER_COMBAT, new LinkedHashSet<>(SUPER_COMBAT.getUnitsPerItem().keySet()), null, 4, true, false, true, true));
		// Always-4-dose upgrades whose exact base doses are not claimed here: recognised, no CAN MAKE.
		add(new HerbloreRecipe(SUPER_ANTIFIRE.getKey(), SUPER_ANTIFIRE.getName(), HerbloreProcess.POTION_UPGRADE, Arrays.asList(
			dosedBase("ANTIFIRE", ANTIFIRE),
			input(HerbloreRecipe.Role.SECONDARY, "Crushed superior dragon bones", "SECONDARY", null, null, true, ItemID.CRUSHED_DRAGON_BONES)),
			SUPER_ANTIFIRE, new LinkedHashSet<>(SUPER_ANTIFIRE.getUnitsPerItem().keySet()), null, 4, true, false, true, false));
		add(new HerbloreRecipe(ANTI_VENOM_PLUS.getKey(), ANTI_VENOM_PLUS.getName(), HerbloreProcess.POTION_UPGRADE, Arrays.asList(
			dosedBase("ANTI-VENOM", ANTI_VENOM),
			input(HerbloreRecipe.Role.SECONDARY, "Torstol", "TORSTOL", null, null, true, ItemID.TORSTOL)),
			ANTI_VENOM_PLUS, new LinkedHashSet<>(ANTI_VENOM_PLUS.getUnitsPerItem().keySet()), null, 4, true, false, true, false));

		// Upgrades / extensions / divine potions: doses preserved, an ingredient per dose.
		upgrade(HerbloreProcess.POTION_UPGRADE, STAMINA, SUPER_ENERGY, "SUPER ENERGY", "Amylase crystal", ItemID.AMYLASE, 1, true);
		upgrade(HerbloreProcess.POTION_UPGRADE, EXTREME_ENERGY, SUPER_ENERGY, "SUPER ENERGY", "Yellow fin", ItemID.YELLOW_FIN, null, true);
		upgrade(HerbloreProcess.POTION_UPGRADE, ANTI_VENOM, ANTIDOTE_PLUS_PLUS, "ANTIDOTE++", "Zulrah's scales", ItemID.SNAKEBOSS_SCALE, 5, true);
		upgrade(HerbloreProcess.POTION_UPGRADE, FORGOTTEN_BREW, ANCIENT_BREW, "ANCIENT BREW", "Ancient essence", ItemID.ANCIENT_ESSENCE, null, true);
		upgrade(HerbloreProcess.EXTENSION, EXTENDED_ANTIFIRE, ANTIFIRE, "ANTIFIRE", "Lava scale shard", ItemID.LAVA_SHARD, 1, true);
		upgrade(HerbloreProcess.EXTENSION, EXTENDED_SUPER_ANTIFIRE, SUPER_ANTIFIRE, "SUPER ANTIFIRE", "Lava scale shard", ItemID.LAVA_SHARD, 1,
			true);
		upgrade(HerbloreProcess.EXTENSION, EXTENDED_STAMINA, STAMINA, "STAMINA", "Marlin scales", ItemID.MARLIN_SCALES, 1, true);
		upgrade(HerbloreProcess.EXTENSION, EXTENDED_ANTI_VENOM_PLUS, ANTI_VENOM_PLUS, "ANTI-VENOM+", "Araxyte venom sack",
			ItemID.ARAXYTE_VENOM_SACK, null, true);
		String dust = "Crystal dust";
		// The goggles never save crystal dust.
		upgrade(HerbloreProcess.DIVINE, DIVINE_SUPER_ATTACK, SUPER_ATTACK, "SUPER ATTACK", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_SUPER_STRENGTH, SUPER_STRENGTH, "SUPER STRENGTH", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1,
			false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_SUPER_DEFENCE, SUPER_DEFENCE, "SUPER DEFENCE", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_RANGING, RANGING, "RANGING", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_MAGIC, MAGIC, "MAGIC", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_BASTION, BASTION, "BASTION", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_BATTLEMAGE, BATTLEMAGE, "BATTLEMAGE", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);
		upgrade(HerbloreProcess.DIVINE, DIVINE_SUPER_COMBAT, SUPER_COMBAT, "SUPER COMBAT", dust, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED, 1, false);

		// Weapon poisons.
		poison(WEAPON_POISON, KWUARM_UNF, "Dragon scale dust", ItemID.DRAGON_SCALE_DUST);
		poison(WEAPON_POISON_PLUS, WEAPON_POISON_PLUS_UNF, "Red spiders' eggs", ItemID.RED_SPIDERS_EGGS);
		poison(WEAPON_POISON_PLUS_PLUS, WEAPON_POISON_PLUS_PLUS_UNF, "Poison ivy berries", ItemID.POISONIVY_BERRIES);

		// Barbarian mixes: a 2-dose potion + roe or caviar -> a 2-dose mix.
		barbarianMix("Attack mix", ItemID.BRUTAL_2DOSE1ATTACK, ItemID.BRUTAL_1DOSE1ATTACK, ATTACK);
		barbarianMix("Antipoison mix", ItemID.BRUTAL_2DOSEANTIPOISON, ItemID.BRUTAL_1DOSEANTIPOISON, ANTIPOISON);
		barbarianMix("Relicym's mix", ItemID.BRUTAL_RELICYMS_BALM2, ItemID.BRUTAL_RELICYMS_BALM1, RELICYMS);
		barbarianMix("Strength mix", ItemID.BRUTAL_2DOSE1STRENGTH, ItemID.BRUTAL_1DOSE1STRENGTH, STRENGTH);
		barbarianMix("Combat mix", ItemID.BRUTAL_2DOSECOMBAT, ItemID.BRUTAL_1DOSECOMBAT, COMBAT);
		barbarianMix("Restore mix", ItemID.BRUTAL_2DOSESTATRESTORE, ItemID.BRUTAL_1DOSESTATRESTORE, RESTORE);
		barbarianMix("Energy mix", ItemID.BRUTAL_2DOSE1ENERGY, ItemID.BRUTAL_1DOSE1ENERGY, ENERGY);
		barbarianMix("Defence mix", ItemID.BRUTAL_2DOSE1DEFENSE, ItemID.BRUTAL_1DOSE1DEFENSE, DEFENCE);
		barbarianMix("Agility mix", ItemID.BRUTAL_2DOSE1AGILITY, ItemID.BRUTAL_1DOSE1AGILITY, AGILITY);
		barbarianMix("Prayer mix", ItemID.BRUTAL_2DOSEPRAYERRESTORE, ItemID.BRUTAL_1DOSEPRAYERRESTORE, PRAYER);
		barbarianMix("Superattack mix", ItemID.BRUTAL_2DOSE2ATTACK, ItemID.BRUTAL_1DOSE2ATTACK, SUPER_ATTACK);
		barbarianMix("Anti-poison supermix", ItemID.BRUTAL_2DOSE2ANTIPOISON, ItemID.BRUTAL_1DOSE2ANTIPOISON, SUPERANTIPOISON);
		barbarianMix("Fishing mix", ItemID.BRUTAL_2DOSEFISHERSPOTION, ItemID.BRUTAL_1DOSEFISHERSPOTION, FISHING);
		barbarianMix("Super energy mix", ItemID.BRUTAL_2DOSE2ENERGY, ItemID.BRUTAL_1DOSE2ENERGY, SUPER_ENERGY);
		barbarianMix("Hunting mix", ItemID.BRUTAL_2DOSE1HUNTING, ItemID.BRUTAL_1DOSE1HUNTING, HUNTER);
		barbarianMix("Super str. mix", ItemID.BRUTAL_2DOSE2STRENGTH, ItemID.BRUTAL_1DOSE2STRENGTH, SUPER_STRENGTH);
		barbarianMix("Magic essence mix", ItemID.BRUTAL_2DOSEMAGICESS, ItemID.BRUTAL_1DOSEMAGICESS, MAGIC_ESSENCE);
		barbarianMix("Super restore mix", ItemID.BRUTAL_2DOSE2RESTORE, ItemID.BRUTAL_1DOSE2RESTORE, SUPER_RESTORE);
		barbarianMix("Super def. mix", ItemID.BRUTAL_2DOSE2DEFENSE, ItemID.BRUTAL_1DOSE2DEFENSE, SUPER_DEFENCE);
		barbarianMix("Antidote+ mix", ItemID.BRUTAL_ANTIDOTE_2, ItemID.BRUTAL_ANTIDOTE_1, ANTIDOTE_PLUS);
		barbarianMix("Antifire mix", ItemID.BRUTAL_2DOSE1ANTIDRAGON, ItemID.BRUTAL_1DOSE1ANTIDRAGON, ANTIFIRE);
		barbarianMix("Ranging mix", ItemID.BRUTAL_2DOSERANGERSPOTION, ItemID.BRUTAL_1DOSERANGERSPOTION, RANGING);
		barbarianMix("Magic mix", ItemID.BRUTAL_2DOSE1MAGIC, ItemID.BRUTAL_1DOSE1MAGIC, MAGIC);
		barbarianMix("Zamorak mix", ItemID.BRUTAL_2DOSEPOTIONOFZAMORAK, ItemID.BRUTAL_1DOSEPOTIONOFZAMORAK, ZAMORAK_BREW);
		barbarianMix("Stamina mix", ItemID.BRUTAL_2DOSESTAMINA, ItemID.BRUTAL_1DOSESTAMINA, STAMINA);
		barbarianMix("Extended antifire mix", ItemID.BRUTAL_2DOSE2ANTIDRAGON, ItemID.BRUTAL_1DOSE2ANTIDRAGON, EXTENDED_ANTIFIRE);
		barbarianMix("Super antifire mix", ItemID.BRUTAL_2DOSE3ANTIDRAGON, ItemID.BRUTAL_1DOSE3ANTIDRAGON, SUPER_ANTIFIRE);
		barbarianMix("Extended super antifire mix", ItemID.BRUTAL_2DOSE4ANTIDRAGON, ItemID.BRUTAL_1DOSE4ANTIDRAGON, EXTENDED_SUPER_ANTIFIRE);
		barbarianMix("Ancient mix", ItemID.BRUTAL_2DOSEANCIENTBREW, ItemID.BRUTAL_1DOSEANCIENTBREW, ANCIENT_BREW);

		// Tars: herb + 15 swamp tar -> 15 tar (stackable, undosed).
		tar("Guam tar", ItemID.GUAM_LEAF, "Guam leaf", ItemID.SALAMANDER_TAR_GREEN);
		tar("Marrentill tar", ItemID.MARENTILL, "Marrentill", ItemID.SALAMANDER_TAR_ORANGE);
		tar("Tarromin tar", ItemID.TARROMIN, "Tarromin", ItemID.SALAMANDER_TAR_RED);
		tar("Harralander tar", ItemID.HARRALANDER, "Harralander", ItemID.SALAMANDER_TAR_BLACK);
		tar("Irit tar", ItemID.IRIT_LEAF, "Irit leaf", ItemID.SALAMANDER_TAR_MOUNTAIN);

		Map<String, String> deferred = new LinkedHashMap<>();
		deferred.put("Guthix rest", "Made in several steps through intermediate tea items Spyglass does not identify exactly; the final step's "
			+ "consumed input cannot be proven, so no mix is claimed (the family is still counted in doses)");
		deferred.put("Sanfew serum", "A multi-step mixture (several intermediate items); same reason as Guthix rest");
		deferred.put("Relicym's balm / Magic essence / Blamish oil", "Quest-locked recipes with quest-only unfinished potions; their "
			+ "families are counted in doses, the recipes are not tracked");
		deferred.put("Mastering Mixology", "A structured activity (pastes, its own potions, resin rewards): never generic recipe production");
		deferred.put("Crushing secondaries", "A pestle and mortar gives no Herblore XP and makes no potion: preparation, not production");
		deferred.put("Decanting / combining doses", "Moves doses between items without creating any: never production (doses unchanged)");
		DEFERRED = Collections.unmodifiableMap(deferred);
	}

	// ---- lookups ------------------------------------------------------------------------------------

	public static HerbloreRecipe recipe(String key)
	{
		return key == null ? null : RECIPES.get(key);
	}

	public static Collection<HerbloreRecipe> all()
	{
		return Collections.unmodifiableCollection(RECIPES.values());
	}

	/** Every recipe whose output includes {@code itemId} (usually one). */
	public static List<HerbloreRecipe> producing(int itemId)
	{
		List<HerbloreRecipe> r = BY_OUTPUT.get(itemId);
		return r == null ? Collections.<HerbloreRecipe>emptyList() : Collections.unmodifiableList(r);
	}

	/** The family an item belongs to (potion doses, an unfinished potion, a clean herb); null for anything else. */
	public static UnitFamily familyOf(int itemId)
	{
		return FAMILY_BY_ITEM.get(itemId);
	}

	public static UnitFamily family(String key)
	{
		return key == null ? null : FAMILIES.get(key);
	}

	public static Collection<UnitFamily> families()
	{
		return Collections.unmodifiableCollection(FAMILIES.values());
	}

	/** Whether the family can live in Potion Storage. */
	public static boolean storable(UnitFamily family)
	{
		return family != null && STORABLE.contains(family.getKey());
	}

	/** Natural name of any item this registry knows ("Super restore(3)", "Red spiders' eggs"); null otherwise. */
	public static String name(int itemId)
	{
		return NAMES.get(itemId);
	}

	/** Whether {@code itemId} is an input of some recipe. */
	public static boolean isInput(int itemId)
	{
		return INPUT_ITEMS.contains(itemId);
	}
}
