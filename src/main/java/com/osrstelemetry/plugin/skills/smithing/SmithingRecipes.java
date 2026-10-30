package com.osrstelemetry.plugin.skills.smithing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Every supported Smithing recipe (current 2026 OSRS, Sailing included), as
 * data. Bars by process (furnace, Superheat Item, Blast Furnace), the anvil
 * table generated per metal tier, cannonball moulds, chainshot / incendiary
 * combinations, keel parts and the dragon forge. Ids are gameval constants
 * (compile-checked); ratios from the OSRS Wiki.
 *
 * <p>Deferred: barbarian spears / hastas (logs at the barbarian anvil),
 * bronze wire and bullseye lantern frames, quest-only bars (elemental,
 * perfect gold), dragon platebody / kiteshield assembly, Giants' Foundry
 * (a structured activity).
 */
public final class SmithingRecipes
{
	private SmithingRecipes()
	{
	}

	public static final String SMELT = "smelt_";
	public static final String SUPERHEAT = "superheat_";
	public static final String BLAST = "bf_";

	public static final int COAL = ItemID.COAL;
	public static final int IRON_ORE = ItemID.IRON_ORE;
	public static final int CATALYST = ItemID.SMITHING_CATALYST;
	public static final int COAL_BAG = ItemID.COAL_BAG;
	public static final int COAL_BAG_OPEN = ItemID.COAL_BAG_OPEN;
	public static final int AMMO_MOULD = ItemID.AMMO_MOULD;
	public static final int DOUBLE_AMMO_MOULD = ItemID.DOUBLE_AMMO_MOULD;
	public static final int RING_OF_FORGING = ItemID.RING_OF_FORGING;
	public static final int GOLDSMITH_GAUNTLETS = ItemID.GAUNTLETS_OF_GOLDSMITHING;
	/** Gloves that let Blast Furnace bars be taken hot. */
	public static final Set<Integer> COOLING_GLOVES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ItemID.ICE_GLOVES,
		ItemID.SMITHING_UNIFORM_GLOVES_ICE)));

	/** One smeltable bar: its ores (furnace ratio), coal at a normal furnace, and its Blast Furnace dispenser varbit (0 = not at the BF). */
	public static final class Bar
	{
		private final String key;
		private final String metal;
		private final int barItem;
		private final String barName;
		private final int[] ores;
		private final int[] oreQty;
		private final int coal;
		private final int bfBarVarbit;

		Bar(String key, String metal, int barItem, String barName, int[] ores, int[] oreQty, int coal, int bfBarVarbit)
		{
			this.key = key;
			this.metal = metal;
			this.barItem = barItem;
			this.barName = barName;
			this.ores = ores;
			this.oreQty = oreQty;
			this.coal = coal;
			this.bfBarVarbit = bfBarVarbit;
		}

		public String getKey()
		{
			return key;
		}

		public int getBarItem()
		{
			return barItem;
		}

		public String getBarName()
		{
			return barName;
		}

		/** Coal per bar at a normal furnace (0 = none). */
		public int getCoal()
		{
			return coal;
		}

		/** The dispenser's varbit for this bar; 0 when the Blast Furnace does not make it. */
		public int getBfBarVarbit()
		{
			return bfBarVarbit;
		}

		public int[] getOres()
		{
			return ores.clone();
		}
	}

	/** One Blast Furnace furnace-contents varbit and the item it counts. */
	public static final class FurnaceVar
	{
		private final int varbit;
		private final int itemId;

		FurnaceVar(int varbit, int itemId)
		{
			this.varbit = varbit;
			this.itemId = itemId;
		}

		public int getVarbit()
		{
			return varbit;
		}

		public int getItemId()
		{
			return itemId;
		}
	}

	private static final List<Bar> BARS = new ArrayList<>();
	private static final List<FurnaceVar> FURNACE_ORES = new ArrayList<>();
	private static final Map<String, SmithingRecipe> BY_KEY = new LinkedHashMap<>();
	private static final Map<Integer, List<SmithingRecipe>> BY_OUTPUT = new HashMap<>();
	private static final Map<Integer, String> NAMES = new HashMap<>();
	private static final Set<Integer> INPUTS = new HashSet<>();
	private static final Map<Integer, Bar> BAR_BY_ITEM = new HashMap<>();
	private static final Map<Integer, Bar> BAR_BY_VARBIT = new HashMap<>();

	/** Anvil product types in the order of each tier's id list: suffix, bars, items per action, SESSION caption. */
	private static final Object[][] PRODUCTS = {
		{"dagger", 1, 1, "ITEMS"}, {"axe", 1, 1, "ITEMS"}, {"mace", 1, 1, "ITEMS"}, {"med helm", 1, 1, "ITEMS"}, {"sword", 1, 1, "ITEMS"},
		{"limbs", 1, 1, "ITEMS"}, {"bolts (unf)", 1, 10, "BOLTS"}, {"dart tips", 1, 10, "DART TIPS"}, {"nails", 1, 15, "NAILS"},
		{"arrowtips", 1, 15, "ARROWTIPS"}, {"javelin heads", 1, 5, "JAVELIN HEADS"}, {"knives", 1, 5, "KNIVES"},
		{"scimitar", 2, 1, "ITEMS"}, {"longsword", 2, 1, "ITEMS"}, {"full helm", 2, 1, "ITEMS"}, {"sq shield", 2, 1, "ITEMS"},
		{"claws", 2, 1, "ITEMS"}, {"warhammer", 3, 1, "ITEMS"}, {"battleaxe", 3, 1, "ITEMS"}, {"chainbody", 3, 1, "ITEMS"},
		{"kiteshield", 3, 1, "ITEMS"}, {"2h sword", 3, 1, "ITEMS"}, {"platelegs", 3, 1, "ITEMS"}, {"plateskirt", 3, 1, "ITEMS"},
		{"platebody", 5, 1, "ITEMS"}, {"keel parts", 5, 1, "ITEMS"},
	};

	static
	{
		name(ItemID.COPPER_ORE, "Copper ore");
		name(ItemID.TIN_ORE, "Tin ore");
		name(ItemID.IRON_ORE, "Iron ore");
		name(ItemID.SILVER_ORE, "Silver ore");
		name(ItemID.GOLD_ORE, "Gold ore");
		name(ItemID.MITHRIL_ORE, "Mithril ore");
		name(ItemID.ADAMANTITE_ORE, "Adamantite ore");
		name(ItemID.RUNITE_ORE, "Runite ore");
		name(ItemID.BLURITE_ORE, "Blurite ore");
		name(ItemID.LOVAKITE_ORE, "Lovakite ore");
		name(ItemID.LEAD_ORE, "Lead ore");
		name(ItemID.NICKEL_ORE, "Nickel ore");
		name(COAL, "Coal");
		name(CATALYST, "Smithing catalyst");

		bar("bronze", "Bronze", ItemID.BRONZE_BAR, "Bronze bar", new int[]{ItemID.COPPER_ORE, ItemID.TIN_ORE}, new int[]{1, 1}, 0,
			VarbitID.BLAST_FURNACE_BRONZE_BARS);
		bar("blurite", "Blurite", ItemID.BLURITE_BAR, "Blurite bar", new int[]{ItemID.BLURITE_ORE}, new int[]{1}, 0, 0);
		bar("iron", "Iron", ItemID.IRON_BAR, "Iron bar", new int[]{ItemID.IRON_ORE}, new int[]{1}, 0, VarbitID.BLAST_FURNACE_IRON_BARS);
		bar("silver", "Silver", ItemID.SILVER_BAR, "Silver bar", new int[]{ItemID.SILVER_ORE}, new int[]{1}, 0,
			VarbitID.BLAST_FURNACE_SILVER_BARS);
		bar("lead", "Lead", ItemID.LEAD_BAR, "Lead bar", new int[]{ItemID.LEAD_ORE}, new int[]{2}, 0, VarbitID.BLAST_FURNACE_LEAD_BARS);
		bar("steel", "Steel", ItemID.STEEL_BAR, "Steel bar", new int[]{ItemID.IRON_ORE}, new int[]{1}, 2, VarbitID.BLAST_FURNACE_STEEL_BARS);
		bar("gold", "Gold", ItemID.GOLD_BAR, "Gold bar", new int[]{ItemID.GOLD_ORE}, new int[]{1}, 0, VarbitID.BLAST_FURNACE_GOLD_BARS);
		bar("lovakite", "Lovakite", ItemID.LOVAKITE_BAR, "Lovakite bar", new int[]{ItemID.LOVAKITE_ORE}, new int[]{1}, 2, 0);
		bar("mithril", "Mithril", ItemID.MITHRIL_BAR, "Mithril bar", new int[]{ItemID.MITHRIL_ORE}, new int[]{1}, 4,
			VarbitID.BLAST_FURNACE_MITHRIL_BARS);
		bar("adamantite", "Adamantite", ItemID.ADAMANTITE_BAR, "Adamantite bar", new int[]{ItemID.ADAMANTITE_ORE}, new int[]{1}, 6,
			VarbitID.BLAST_FURNACE_ADAMANTITE_BARS);
		bar("cupronickel", "Cupronickel", ItemID.CUPRONICKEL_BAR, "Cupronickel bar", new int[]{ItemID.NICKEL_ORE, ItemID.COPPER_ORE},
			new int[]{1, 2}, 0, VarbitID.BLAST_FURNACE_CUPRONICKEL_BARS);
		bar("runite", "Runite", ItemID.RUNITE_BAR, "Runite bar", new int[]{ItemID.RUNITE_ORE}, new int[]{1}, 8,
			VarbitID.BLAST_FURNACE_RUNITE_BARS);

		furnace(VarbitID.BLAST_FURNACE_COAL, COAL);
		furnace(VarbitID.BLAST_FURNACE_COPPER_ORE, ItemID.COPPER_ORE);
		furnace(VarbitID.BLAST_FURNACE_TIN_ORE, ItemID.TIN_ORE);
		furnace(VarbitID.BLAST_FURNACE_IRON_ORE, ItemID.IRON_ORE);
		furnace(VarbitID.BLAST_FURNACE_MITHRIL_ORE, ItemID.MITHRIL_ORE);
		furnace(VarbitID.BLAST_FURNACE_ADAMANTITE_ORE, ItemID.ADAMANTITE_ORE);
		furnace(VarbitID.BLAST_FURNACE_RUNITE_ORE, ItemID.RUNITE_ORE);
		furnace(VarbitID.BLAST_FURNACE_SILVER_ORE, ItemID.SILVER_ORE);
		furnace(VarbitID.BLAST_FURNACE_GOLD_ORE, ItemID.GOLD_ORE);
		furnace(VarbitID.BLAST_FURNACE_LEAD_ORE, ItemID.LEAD_ORE);
		furnace(VarbitID.BLAST_FURNACE_NICKEL_ORE, ItemID.NICKEL_ORE);

		// ---- anvil, per metal tier ---------------------------------------------------------------------
		tier("bronze", "Bronze", ItemID.BRONZE_BAR, "Bronze bar", new int[]{
			ItemID.BRONZE_DAGGER, ItemID.BRONZE_AXE, ItemID.BRONZE_MACE, ItemID.BRONZE_MED_HELM, ItemID.BRONZE_SWORD,
			ItemID.XBOWS_CROSSBOW_LIMBS_BRONZE, ItemID.XBOWS_CROSSBOW_BOLTS_BRONZE_UNFEATHERED, ItemID.BRONZE_DART_TIP, ItemID.NAILS_BRONZE,
			ItemID.BRONZE_ARROWHEADS, ItemID.BRONZE_JAVELIN_HEAD, ItemID.BRONZE_KNIFE, ItemID.BRONZE_SCIMITAR, ItemID.BRONZE_LONGSWORD,
			ItemID.BRONZE_FULL_HELM, ItemID.BRONZE_SQ_SHIELD, ItemID.BRONZE_CLAWS, ItemID.BRONZE_WARHAMMER, ItemID.BRONZE_BATTLEAXE,
			ItemID.BRONZE_CHAINBODY, ItemID.BRONZE_KITESHIELD, ItemID.BRONZE_2H_SWORD, ItemID.BRONZE_PLATELEGS, ItemID.BRONZE_PLATESKIRT,
			ItemID.BRONZE_PLATEBODY, ItemID.SAILING_BOAT_KEEL_PART_BRONZE}, "Bronze limbs", "Bronze bolts (unf)");
		tier("iron", "Iron", ItemID.IRON_BAR, "Iron bar", new int[]{
			ItemID.IRON_DAGGER, ItemID.IRON_AXE, ItemID.IRON_MACE, ItemID.IRON_MED_HELM, ItemID.IRON_SWORD,
			ItemID.XBOWS_CROSSBOW_LIMBS_IRON, ItemID.XBOWS_CROSSBOW_BOLTS_IRON_UNFEATHERED, ItemID.IRON_DART_TIP, ItemID.NAILS_IRON,
			ItemID.IRON_ARROWHEADS, ItemID.IRON_JAVELIN_HEAD, ItemID.IRON_KNIFE, ItemID.IRON_SCIMITAR, ItemID.IRON_LONGSWORD,
			ItemID.IRON_FULL_HELM, ItemID.IRON_SQ_SHIELD, ItemID.IRON_CLAWS, ItemID.IRON_WARHAMMER, ItemID.IRON_BATTLEAXE,
			ItemID.IRON_CHAINBODY, ItemID.IRON_KITESHIELD, ItemID.IRON_2H_SWORD, ItemID.IRON_PLATELEGS, ItemID.IRON_PLATESKIRT,
			ItemID.IRON_PLATEBODY, ItemID.SAILING_BOAT_KEEL_PART_IRON}, "Iron limbs", "Iron bolts (unf)");
		tier("steel", "Steel", ItemID.STEEL_BAR, "Steel bar", new int[]{
			ItemID.STEEL_DAGGER, ItemID.STEEL_AXE, ItemID.STEEL_MACE, ItemID.STEEL_MED_HELM, ItemID.STEEL_SWORD,
			ItemID.XBOWS_CROSSBOW_LIMBS_STEEL, ItemID.XBOWS_CROSSBOW_BOLTS_STEEL_UNFEATHERED, ItemID.STEEL_DART_TIP, ItemID.NAILS,
			ItemID.STEEL_ARROWHEADS, ItemID.STEEL_JAVELIN_HEAD, ItemID.STEEL_KNIFE, ItemID.STEEL_SCIMITAR, ItemID.STEEL_LONGSWORD,
			ItemID.STEEL_FULL_HELM, ItemID.STEEL_SQ_SHIELD, ItemID.STEEL_CLAWS, ItemID.STEEL_WARHAMMER, ItemID.STEEL_BATTLEAXE,
			ItemID.STEEL_CHAINBODY, ItemID.STEEL_KITESHIELD, ItemID.STEEL_2H_SWORD, ItemID.STEEL_PLATELEGS, ItemID.STEEL_PLATESKIRT,
			ItemID.STEEL_PLATEBODY, ItemID.SAILING_BOAT_KEEL_PART_STEEL}, "Steel limbs", "Steel bolts (unf)");
		tier("mithril", "Mithril", ItemID.MITHRIL_BAR, "Mithril bar", new int[]{
			ItemID.MITHRIL_DAGGER, ItemID.MITHRIL_AXE, ItemID.MITHRIL_MACE, ItemID.MITHRIL_MED_HELM, ItemID.MITHRIL_SWORD,
			ItemID.XBOWS_CROSSBOW_LIMBS_MITHRIL, ItemID.XBOWS_CROSSBOW_BOLTS_MITHRIL_UNFEATHERED, ItemID.MITHRIL_DART_TIP,
			ItemID.NAILS_MITHRIL, ItemID.MITHRIL_ARROWHEADS, ItemID.MITHRIL_JAVELIN_HEAD, ItemID.MITHRIL_KNIFE, ItemID.MITHRIL_SCIMITAR,
			ItemID.MITHRIL_LONGSWORD, ItemID.MITHRIL_FULL_HELM, ItemID.MITHRIL_SQ_SHIELD, ItemID.MITHRIL_CLAWS, ItemID.MITHRIL_WARHAMMER,
			ItemID.MITHRIL_BATTLEAXE, ItemID.MITHRIL_CHAINBODY, ItemID.MITHRIL_KITESHIELD, ItemID.MITHRIL_2H_SWORD,
			ItemID.MITHRIL_PLATELEGS, ItemID.MITHRIL_PLATESKIRT, ItemID.MITHRIL_PLATEBODY, ItemID.SAILING_BOAT_KEEL_PART_MITHRIL},
			"Mithril limbs", "Mithril bolts (unf)");
		tier("adamant", "Adamant", ItemID.ADAMANTITE_BAR, "Adamantite bar", new int[]{
			ItemID.ADAMANT_DAGGER, ItemID.ADAMANT_AXE, ItemID.ADAMANT_MACE, ItemID.ADAMANT_MED_HELM, ItemID.ADAMANT_SWORD,
			ItemID.XBOWS_CROSSBOW_LIMBS_ADAMANTITE, ItemID.XBOWS_CROSSBOW_BOLTS_ADAMANTITE_UNFEATHERED, ItemID.ADAMANT_DART_TIP,
			ItemID.NAILS_ADAMANT, ItemID.ADAMANT_ARROWHEADS, ItemID.ADAMANT_JAVELIN_HEAD, ItemID.ADAMANT_KNIFE, ItemID.ADAMANT_SCIMITAR,
			ItemID.ADAMANT_LONGSWORD, ItemID.ADAMANT_FULL_HELM, ItemID.ADAMANT_SQ_SHIELD, ItemID.ADAMANT_CLAWS, ItemID.ADAMNT_WARHAMMER,
			ItemID.ADAMANT_BATTLEAXE, ItemID.ADAMANT_CHAINBODY, ItemID.ADAMANT_KITESHIELD, ItemID.ADAMANT_2H_SWORD,
			ItemID.ADAMANT_PLATELEGS, ItemID.ADAMANT_PLATESKIRT, ItemID.ADAMANT_PLATEBODY, ItemID.SAILING_BOAT_KEEL_PART_ADAMANT},
			"Adamantite limbs", "Adamant bolts (unf)");
		tier("rune", "Rune", ItemID.RUNITE_BAR, "Runite bar", new int[]{
			ItemID.RUNE_DAGGER, ItemID.RUNE_AXE, ItemID.RUNE_MACE, ItemID.RUNE_MED_HELM, ItemID.RUNE_SWORD,
			ItemID.XBOWS_CROSSBOW_LIMBS_RUNITE, ItemID.XBOWS_CROSSBOW_BOLTS_RUNITE_UNFEATHERED, ItemID.RUNE_DART_TIP, ItemID.NAILS_RUNE,
			ItemID.RUNE_ARROWHEADS, ItemID.RUNE_JAVELIN_HEAD, ItemID.RUNE_KNIFE, ItemID.RUNE_SCIMITAR, ItemID.RUNE_LONGSWORD,
			ItemID.RUNE_FULL_HELM, ItemID.RUNE_SQ_SHIELD, ItemID.RUNE_CLAWS, ItemID.RUNE_WARHAMMER, ItemID.RUNE_BATTLEAXE,
			ItemID.RUNE_CHAINBODY, ItemID.RUNE_KITESHIELD, ItemID.RUNE_2H_SWORD, ItemID.RUNE_PLATELEGS, ItemID.RUNE_PLATESKIRT,
			ItemID.RUNE_PLATEBODY, ItemID.SAILING_BOAT_KEEL_PART_RUNE}, "Runite limbs", "Runite bolts (unf)");

		// Per-metal specials (one bar each).
		anvil("iron_spit", "Iron spit", ItemID.IRON_BAR, "Iron bar", 1, ItemID.SPIT_IRON, "Iron spit", 1, "ITEMS", "iron");
		anvil("oil_lantern_frame", "Oil lantern frame", ItemID.IRON_BAR, "Iron bar", 1, ItemID.OIL_LANTERN_FRAME, "Oil lantern frame", 1, "ITEMS",
			"iron");
		anvil("steel_studs", "Steel studs", ItemID.STEEL_BAR, "Steel bar", 1, ItemID.STUDS, "Steel studs", 1, "ITEMS", "steel");
		anvil("chain", "Chain", ItemID.STEEL_BAR, "Steel bar", 1, ItemID.CHAIN, "Chain", 1, "CHAINS", "steel");
		anvil("mithril_grapple_tip", "Mithril grapple tip", ItemID.MITHRIL_BAR, "Mithril bar", 1, ItemID.XBOWS_GRAPPLE_TIP_MITHRIL,
			"Mithril grapple tip", 1, "ITEMS", "mithril");

		// ---- cannonballs (ammo mould / double ammo mould: 4 per bar either way) -------------------------------
		mould("bronze", "Bronze", ItemID.BRONZE_BAR, "Bronze bar", ItemID.BRONZE_CANNONBALL);
		mould("iron", "Iron", ItemID.IRON_BAR, "Iron bar", ItemID.IRON_CANNONBALL);
		mould("steel", "Steel", ItemID.STEEL_BAR, "Steel bar", ItemID.MCANNONBALL);
		mould("mithril", "Mithril", ItemID.MITHRIL_BAR, "Mithril bar", ItemID.MITHRIL_CANNONBALL);
		mould("adamant", "Adamant", ItemID.ADAMANTITE_BAR, "Adamantite bar", ItemID.ADAMANT_CANNONBALL);
		mould("rune", "Rune", ItemID.RUNITE_BAR, "Runite bar", ItemID.RUNE_CANNONBALL);

		// ---- chainshot (2 cannonballs + 1 chain -> 2) and incendiary (2 cannonballs + 10 rubium splinters -> 2) ----
		name(ItemID.CHAIN, "Chain");
		name(ItemID.RUBIUM_SPLINTERS, "Rubium splinters");
		combine("bronze", "Bronze", ItemID.BRONZE_CANNONBALL, ItemID.BRONZE_CHAINSHOT_CANNONBALL, ItemID.BRONZE_INCENDIARY_CANNONBALL);
		combine("iron", "Iron", ItemID.IRON_CANNONBALL, ItemID.IRON_CHAINSHOT_CANNONBALL, ItemID.IRON_INCENDIARY_CANNONBALL);
		combine("steel", "Steel", ItemID.MCANNONBALL, ItemID.STEEL_CHAINSHOT_CANNONBALL, ItemID.STEEL_INCENDIARY_CANNONBALL);
		combine("mithril", "Mithril", ItemID.MITHRIL_CANNONBALL, ItemID.MITHRIL_CHAINSHOT_CANNONBALL, ItemID.MITHRIL_INCENDIARY_CANNONBALL);
		combine("adamant", "Adamant", ItemID.ADAMANT_CANNONBALL, ItemID.ADAMANT_CHAINSHOT_CANNONBALL, ItemID.ADAMANT_INCENDIARY_CANNONBALL);
		combine("rune", "Rune", ItemID.RUNE_CANNONBALL, ItemID.RUNE_CHAINSHOT_CANNONBALL, ItemID.RUNE_INCENDIARY_CANNONBALL);

		// ---- keel parts (Sailing): 5 keel parts -> 1 large; the dragon forge -----------------------------------
		large("bronze", "Bronze", ItemID.SAILING_BOAT_KEEL_PART_BRONZE, ItemID.SAILING_BOAT_LARGE_KEEL_PART_BRONZE);
		large("iron", "Iron", ItemID.SAILING_BOAT_KEEL_PART_IRON, ItemID.SAILING_BOAT_LARGE_KEEL_PART_IRON);
		large("steel", "Steel", ItemID.SAILING_BOAT_KEEL_PART_STEEL, ItemID.SAILING_BOAT_LARGE_KEEL_PART_STEEL);
		large("mithril", "Mithril", ItemID.SAILING_BOAT_KEEL_PART_MITHRIL, ItemID.SAILING_BOAT_LARGE_KEEL_PART_MITHRIL);
		large("adamant", "Adamant", ItemID.SAILING_BOAT_KEEL_PART_ADAMANT, ItemID.SAILING_BOAT_LARGE_KEEL_PART_ADAMANT);
		large("rune", "Rune", ItemID.SAILING_BOAT_KEEL_PART_RUNE, ItemID.SAILING_BOAT_LARGE_KEEL_PART_RUNE);
		name(ItemID.DRAGON_SHEET, "Dragon metal sheet");
		add(new SmithingRecipe("dragon_nails", "Dragon nails", SmithingProcess.FORGE,
			Collections.singletonList(new SmithingRecipe.Input(ItemID.DRAGON_SHEET, "Dragon metal sheet", "SHEETS", 1, true, false)),
			ItemID.NAILS_DRAGON, "Dragon nails", 15, "NAILS", "dragon"));
		add(new SmithingRecipe("dragon_keel", "Dragon keel parts", SmithingProcess.FORGE,
			Collections.singletonList(new SmithingRecipe.Input(ItemID.DRAGON_SHEET, "Dragon metal sheet", "SHEETS", 2, true, false)),
			ItemID.SAILING_BOAT_KEEL_PART_DRAGON, "Dragon keel parts", 1, "ITEMS", "dragon"));
		large("dragon", "Dragon", ItemID.SAILING_BOAT_KEEL_PART_DRAGON, ItemID.SAILING_BOAT_LARGE_KEEL_PART_DRAGON);
	}

	// ---- builders -------------------------------------------------------------------------------------------

	private static void name(int item, String name)
	{
		NAMES.putIfAbsent(item, name);
	}

	private static void add(SmithingRecipe r)
	{
		if (BY_KEY.put(r.getKey(), r) != null)
		{
			throw new IllegalStateException("duplicate Smithing recipe " + r.getKey());
		}
		BY_OUTPUT.computeIfAbsent(r.getOutputItem(), k -> new ArrayList<>()).add(r);
		name(r.getOutputItem(), r.getOutputName());
		for (SmithingRecipe.Input in : r.getInputs())
		{
			INPUTS.add(in.getItemId());
			name(in.getItemId(), in.getName());
		}
	}

	private static String upper(String s)
	{
		return s.toUpperCase(Locale.ROOT);
	}

	private static void bar(String key, String metal, int barItem, String barName, int[] ores, int[] oreQty, int coal, int bfVarbit)
	{
		Bar b = new Bar(key, metal, barItem, barName, ores, oreQty, coal, bfVarbit);
		BARS.add(b);
		BAR_BY_ITEM.put(barItem, b);
		if (bfVarbit > 0)
		{
			BAR_BY_VARBIT.put(bfVarbit, b);
		}
		name(barItem, barName);
		String plural = metal + " bars";
		add(new SmithingRecipe(SMELT + key, plural, SmithingProcess.SMELTING, barInputs(ores, oreQty, coal), barItem, barName, 1, "BARS", key));
		add(new SmithingRecipe(SUPERHEAT + key, plural + " (Superheat)", SmithingProcess.SUPERHEAT, barInputs(ores, oreQty, coal), barItem,
			barName, 1, "BARS", key));
		if (bfVarbit > 0)
		{
			add(new SmithingRecipe(BLAST + key, plural + " (BF)", SmithingProcess.BLAST_FURNACE, barInputs(ores, oreQty, coal / 2), barItem,
				barName, 1, "BARS", key));
		}
	}

	private static List<SmithingRecipe.Input> barInputs(int[] ores, int[] oreQty, int coal)
	{
		List<SmithingRecipe.Input> inputs = new ArrayList<>();
		for (int i = 0; i < ores.length; i++)
		{
			String n = NAMES.get(ores[i]);
			inputs.add(new SmithingRecipe.Input(ores[i], n, upper(n), oreQty[i], i == 0, false));
		}
		if (coal > 0)
		{
			inputs.add(new SmithingRecipe.Input(COAL, "Coal", "COAL", coal, false, true));
		}
		return inputs;
	}

	private static void furnace(int varbit, int item)
	{
		FURNACE_ORES.add(new FurnaceVar(varbit, item));
	}


	/** Singular item names where the plural recipe name differs. */
	private static String single(String metal, String suffix)
	{
		switch (suffix)
		{
			case "dart tips":
				return metal + " dart tip";
			case "javelin heads":
				return metal + " javelin heads";
			case "knives":
				return metal + " knife";
			default:
				return metal + " " + suffix;
		}
	}

	private static void tier(String tier, String metal, int bar, String barName, int[] products, String limbsName, String boltsName)
	{
		if (products.length != PRODUCTS.length)
		{
			throw new IllegalStateException("tier " + tier + " has " + products.length + " products");
		}
		for (int i = 0; i < PRODUCTS.length; i++)
		{
			String suffix = (String) PRODUCTS[i][0];
			String name;
			String item;
			if ("limbs".equals(suffix))
			{
				name = limbsName;
				item = limbsName;
			}
			else if ("bolts (unf)".equals(suffix))
			{
				name = boltsName;
				item = boltsName;
			}
			else
			{
				name = metal + " " + suffix;
				item = single(metal, suffix);
			}
			anvil(tier + "_" + suffix.replace(' ', '_').replace("(", "").replace(")", ""), name, bar, barName, (Integer) PRODUCTS[i][1],
				products[i], item, (Integer) PRODUCTS[i][2], (String) PRODUCTS[i][3], tier);
		}
	}

	private static void anvil(String key, String name, int bar, String barName, int bars, int output, String outputName, int perAction,
		String unitCaption, String tier)
	{
		add(new SmithingRecipe(key, name, SmithingProcess.ANVIL,
			Collections.singletonList(new SmithingRecipe.Input(bar, barName, upper(barName) + "S", bars, true, false)),
			output, outputName, perAction, unitCaption, tier));
	}

	private static void mould(String tier, String metal, int bar, String barName, int cannonball)
	{
		add(new SmithingRecipe("cannonball_" + tier, metal + " cannonballs", SmithingProcess.MOULD,
			Collections.singletonList(new SmithingRecipe.Input(bar, barName, upper(barName) + "S", 1, true, false)),
			cannonball, metal + " cannonball", 4, "CANNONBALLS", tier));
	}

	private static void combine(String tier, String metal, int cannonball, int chainshot, int incendiary)
	{
		String ballName = NAMES.get(cannonball);
		add(new SmithingRecipe("chainshot_" + tier, metal + " chainshot", SmithingProcess.COMBINE, Arrays.asList(
			new SmithingRecipe.Input(cannonball, ballName, "CANNONBALLS", 2, true, false),
			new SmithingRecipe.Input(ItemID.CHAIN, "Chain", "CHAINS", 1, false, false)),
			chainshot, metal + " chainshot", 2, "CHAINSHOT", tier));
		add(new SmithingRecipe("incendiary_" + tier, metal + " incendiary shot", SmithingProcess.COMBINE, Arrays.asList(
			new SmithingRecipe.Input(cannonball, ballName, "CANNONBALLS", 2, true, false),
			new SmithingRecipe.Input(ItemID.RUBIUM_SPLINTERS, "Rubium splinters", "RUBIUM", 10, false, false)),
			incendiary, metal + " incendiary shot", 2, "INCENDIARY", tier));
	}

	private static void large(String tier, String metal, int keel, int largeKeel)
	{
		String keelName = metal + " keel parts";
		add(new SmithingRecipe("large_keel_" + tier, "Large " + metal.toLowerCase(Locale.ROOT) + " keel parts", SmithingProcess.ANVIL,
			Collections.singletonList(new SmithingRecipe.Input(keel, keelName, "KEEL PARTS", 5, true, false)),
			largeKeel, "Large " + metal.toLowerCase(Locale.ROOT) + " keel parts", 1, "ITEMS", tier));
	}

	// ---- lookups ------------------------------------------------------------------------------------------

	public static SmithingRecipe recipe(String key)
	{
		return key == null ? null : BY_KEY.get(key);
	}

	public static List<SmithingRecipe> all()
	{
		return Collections.unmodifiableList(new ArrayList<>(BY_KEY.values()));
	}

	/** Every recipe (every process) whose output is {@code itemId}. */
	public static List<SmithingRecipe> producing(int itemId)
	{
		List<SmithingRecipe> r = BY_OUTPUT.get(itemId);
		return r == null ? Collections.<SmithingRecipe>emptyList() : Collections.unmodifiableList(r);
	}

	/** The recipe of {@code process} that makes {@code itemId}, or null. */
	public static SmithingRecipe producing(int itemId, SmithingProcess process)
	{
		for (SmithingRecipe r : producing(itemId))
		{
			if (r.getProcess() == process)
			{
				return r;
			}
		}
		return null;
	}

	public static boolean isOutput(int itemId)
	{
		return BY_OUTPUT.containsKey(itemId);
	}

	public static boolean isInput(int itemId)
	{
		return INPUTS.contains(itemId) || itemId == CATALYST;
	}

	/** The natural name of any recipe item (input or output); null for anything else. */
	public static String name(int itemId)
	{
		return NAMES.get(itemId);
	}

	public static List<Bar> bars()
	{
		return Collections.unmodifiableList(BARS);
	}

	public static Bar barOf(int barItem)
	{
		return BAR_BY_ITEM.get(barItem);
	}

	/** The bar whose Blast Furnace dispenser varbit this is, or null. */
	public static Bar barForDispenserVarbit(int varbit)
	{
		return BAR_BY_VARBIT.get(varbit);
	}

	/** Blast Furnace furnace-contents varbits (ores and coal). */
	public static List<FurnaceVar> furnaceOres()
	{
		return Collections.unmodifiableList(FURNACE_ORES);
	}
}
