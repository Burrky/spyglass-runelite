package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * What a planned crop needs, for crops with sourced data only: the seed or
 * sapling and how many per patch, the herb planting level (for "any herb
 * seed" plans), and the gardener's protection payment. A crop that is not
 * listed has no requirement Spyglass can state; the preparation says so and
 * never guesses an item.
 *
 * <h3>Provenance</h3>
 * Item ids: {@code net.runelite.api.gameval.ItemID} (RuneLite 1.13.1; the test
 * resolves each gameval name). Checked 2026-10-15 / 2026-10-16:
 * <ul>
 *   <li>Seeds per patch, OSRS Wiki: allotment 3; herb, flower, bush (Redberry
 *   seed: 1), seaweed spore 1; hops "×" column of the Seeds page (barley,
 *   hammerstone, asgarnian, yanillian, krandorian, wildblood 4; jute, flax,
 *   hemp, cotton 3). Trees, fruit trees, hardwood, calquat, celastrus,
 *   redwood, spirit and crystal trees are planted as one sapling.</li>
 *   <li>Herb planting levels, OSRS Wiki Seeds (herb table, Guam 9 to Torstol 85).</li>
 *   <li>Protection payments: Quest Helper {@code FarmingUtils} (Zoinkwiz/
 *   quest-helper master @ 5f79193, BSD-2) for tree, fruit tree, hardwood and
 *   calquat saplings, and the OSRS Wiki for allotments (Farming; Seeds; the
 *   Sweetcorn, Strawberry, Watermelon and Snape grass seed pages), hops,
 *   bushes and trees (Seeds). Quest Helper lists ironwood as
 *   {@code ItemID.CURRY}; the wiki (Ironwood seed) says curry leaves, which
 *   is used here. Payments may be noted (OSRS Wiki, Farming).</li>
 * </ul>
 * Goutweed (a tuber) and the scarecrow are not listed. Herbs and flowers
 * cannot be protected (OSRS Wiki, Farming).
 */
public final class FarmingSeeds
{
	public static final class Seed
	{
		private final int itemId;
		private final String gameval;
		private final int perPatch;
		private final boolean sapling;

		Seed(int itemId, String gameval, int perPatch, boolean sapling)
		{
			this.itemId = itemId;
			this.gameval = gameval;
			this.perPatch = perPatch;
			this.sapling = sapling;
		}

		public int getItemId()
		{
			return itemId;
		}

		public String getGameval()
		{
			return gameval;
		}

		public int getPerPatch()
		{
			return perPatch;
		}

		/** A potted sapling: one per patch, and it does not stack in the inventory. */
		public boolean isSapling()
		{
			return sapling;
		}
	}

	/** A gardener's protection payment. */
	public static final class Payment
	{
		private final int itemId;
		private final String gameval;
		private final String label;
		private final int quantity;

		/** Further items the same payment needs (a spirit tree takes three different items). */
		private final List<Payment> also = new ArrayList<>();

		/** The further items paid together with this one (empty for single-item payments). */
		public List<Payment> getAlso()
		{
			return Collections.unmodifiableList(also);
		}

		Payment(int itemId, String gameval, String label, int quantity)
		{
			this.itemId = itemId;
			this.gameval = gameval;
			this.label = label;
			this.quantity = quantity;
		}

		public int getItemId()
		{
			return itemId;
		}

		public String getGameval()
		{
			return gameval;
		}

		/** "Basket of tomatoes", "Compost". */
		public String getLabel()
		{
			return label;
		}

		public int getQuantity()
		{
			return quantity;
		}
	}

	private static final Map<FarmingProduce, Seed> SEEDS = new EnumMap<>(FarmingProduce.class);
	private static final Map<FarmingProduce, Integer> HERB_LEVEL = new EnumMap<>(FarmingProduce.class);
	private static final Map<FarmingProduce, Payment> PAYMENTS = new EnumMap<>(FarmingProduce.class);

	static
	{
		herb(FarmingProduce.GUAM, ItemID.GUAM_SEED, "GUAM_SEED", 9);
		herb(FarmingProduce.MARRENTILL, ItemID.MARRENTILL_SEED, "MARRENTILL_SEED", 14);
		herb(FarmingProduce.TARROMIN, ItemID.TARROMIN_SEED, "TARROMIN_SEED", 19);
		herb(FarmingProduce.HARRALANDER, ItemID.HARRALANDER_SEED, "HARRALANDER_SEED", 26);
		herb(FarmingProduce.RANARR, ItemID.RANARR_SEED, "RANARR_SEED", 32);
		herb(FarmingProduce.TOADFLAX, ItemID.TOADFLAX_SEED, "TOADFLAX_SEED", 38);
		herb(FarmingProduce.IRIT, ItemID.IRIT_SEED, "IRIT_SEED", 44);
		herb(FarmingProduce.AVANTOE, ItemID.AVANTOE_SEED, "AVANTOE_SEED", 50);
		herb(FarmingProduce.KWUARM, ItemID.KWUARM_SEED, "KWUARM_SEED", 56);
		herb(FarmingProduce.HUASCA, ItemID.HUASCA_SEED, "HUASCA_SEED", 65);
		herb(FarmingProduce.SNAPDRAGON, ItemID.SNAPDRAGON_SEED, "SNAPDRAGON_SEED", 62);
		herb(FarmingProduce.CADANTINE, ItemID.CADANTINE_SEED, "CADANTINE_SEED", 67);
		herb(FarmingProduce.LANTADYME, ItemID.LANTADYME_SEED, "LANTADYME_SEED", 73);
		herb(FarmingProduce.DWARF_WEED, ItemID.DWARF_WEED_SEED, "DWARF_WEED_SEED", 79);
		herb(FarmingProduce.TORSTOL, ItemID.TORSTOL_SEED, "TORSTOL_SEED", 85);
		seed(FarmingProduce.POTATO, ItemID.POTATO_SEED, "POTATO_SEED", 3);
		seed(FarmingProduce.ONION, ItemID.ONION_SEED, "ONION_SEED", 3);
		seed(FarmingProduce.CABBAGE, ItemID.CABBAGE_SEED, "CABBAGE_SEED", 3);
		seed(FarmingProduce.TOMATO, ItemID.TOMATO_SEED, "TOMATO_SEED", 3);
		seed(FarmingProduce.SWEETCORN, ItemID.SWEETCORN_SEED, "SWEETCORN_SEED", 3);
		seed(FarmingProduce.STRAWBERRY, ItemID.STRAWBERRY_SEED, "STRAWBERRY_SEED", 3);
		seed(FarmingProduce.WATERMELON, ItemID.WATERMELON_SEED, "WATERMELON_SEED", 3);
		seed(FarmingProduce.SNAPE_GRASS, ItemID.SNAPE_GRASS_SEED, "SNAPE_GRASS_SEED", 3);
		seed(FarmingProduce.MARIGOLD, ItemID.MARIGOLD_SEED, "MARIGOLD_SEED", 1);
		seed(FarmingProduce.ROSEMARY, ItemID.ROSEMARY_SEED, "ROSEMARY_SEED", 1);
		seed(FarmingProduce.NASTURTIUM, ItemID.NASTURTIUM_SEED, "NASTURTIUM_SEED", 1);
		seed(FarmingProduce.WOAD, ItemID.WOAD_SEED, "WOAD_SEED", 1);
		seed(FarmingProduce.LIMPWURT, ItemID.LIMPWURT_SEED, "LIMPWURT_SEED", 1);
		seed(FarmingProduce.WHITE_LILY, ItemID.WHITE_LILY_SEED, "WHITE_LILY_SEED", 1);
		seed(FarmingProduce.REDBERRIES, ItemID.REDBERRY_BUSH_SEED, "REDBERRY_BUSH_SEED", 1);
		seed(FarmingProduce.CADAVABERRIES, ItemID.CADAVABERRY_BUSH_SEED, "CADAVABERRY_BUSH_SEED", 1);
		seed(FarmingProduce.DWELLBERRIES, ItemID.DWELLBERRY_BUSH_SEED, "DWELLBERRY_BUSH_SEED", 1);
		seed(FarmingProduce.JANGERBERRIES, ItemID.JANGERBERRY_BUSH_SEED, "JANGERBERRY_BUSH_SEED", 1);
		seed(FarmingProduce.WHITEBERRIES, ItemID.WHITEBERRY_BUSH_SEED, "WHITEBERRY_BUSH_SEED", 1);
		seed(FarmingProduce.POISON_IVY, ItemID.POISONIVY_BUSH_SEED, "POISONIVY_BUSH_SEED", 1);
		seed(FarmingProduce.BARLEY, ItemID.BARLEY_SEED, "BARLEY_SEED", 4);
		seed(FarmingProduce.HAMMERSTONE, ItemID.HAMMERSTONE_HOP_SEED, "HAMMERSTONE_HOP_SEED", 4);
		seed(FarmingProduce.ASGARNIAN, ItemID.ASGARNIAN_HOP_SEED, "ASGARNIAN_HOP_SEED", 4);
		seed(FarmingProduce.JUTE, ItemID.JUTE_SEED, "JUTE_SEED", 3);
		seed(FarmingProduce.YANILLIAN, ItemID.YANILLIAN_HOP_SEED, "YANILLIAN_HOP_SEED", 4);
		seed(FarmingProduce.FLAX, ItemID.FLAX_SEED, "FLAX_SEED", 3);
		seed(FarmingProduce.KRANDORIAN, ItemID.KRANDORIAN_HOP_SEED, "KRANDORIAN_HOP_SEED", 4);
		seed(FarmingProduce.WILDBLOOD, ItemID.WILDBLOOD_HOP_SEED, "WILDBLOOD_HOP_SEED", 4);
		seed(FarmingProduce.HEMP, ItemID.HEMP_SEED, "HEMP_SEED", 3);
		seed(FarmingProduce.COTTON, ItemID.COTTON_SEED, "COTTON_SEED", 3);
		seed(FarmingProduce.CACTUS, ItemID.CACTUS_SEED, "CACTUS_SEED", 1);
		seed(FarmingProduce.POTATO_CACTUS, ItemID.POTATO_CACTUS_SEED, "POTATO_CACTUS_SEED", 1);
		seed(FarmingProduce.BELLADONNA, ItemID.BELLADONNA_SEED, "BELLADONNA_SEED", 1);
		seed(FarmingProduce.MUSHROOM, ItemID.MUSHROOM_SEED, "MUSHROOM_SEED", 1);
		seed(FarmingProduce.SEAWEED, ItemID.SEAWEED_SEED, "SEAWEED_SEED", 1);
		seed(FarmingProduce.HESPORI, ItemID.HESPORI_SEED, "HESPORI_SEED", 1);
		seed(FarmingProduce.ATTAS, ItemID.ATTAS_SEED, "ATTAS_SEED", 1);
		seed(FarmingProduce.IASOR, ItemID.IASOR_SEED, "IASOR_SEED", 1);
		seed(FarmingProduce.KRONOS, ItemID.KRONOS_SEED, "KRONOS_SEED", 1);
		sapling(FarmingProduce.OAK, ItemID.PLANTPOT_OAK_SAPLING, "PLANTPOT_OAK_SAPLING");
		sapling(FarmingProduce.WILLOW, ItemID.PLANTPOT_WILLOW_SAPLING, "PLANTPOT_WILLOW_SAPLING");
		sapling(FarmingProduce.MAPLE, ItemID.PLANTPOT_MAPLE_SAPLING, "PLANTPOT_MAPLE_SAPLING");
		sapling(FarmingProduce.YEW, ItemID.PLANTPOT_YEW_SAPLING, "PLANTPOT_YEW_SAPLING");
		sapling(FarmingProduce.MAGIC, ItemID.PLANTPOT_MAGIC_TREE_SAPLING, "PLANTPOT_MAGIC_TREE_SAPLING");
		sapling(FarmingProduce.APPLE, ItemID.PLANTPOT_APPLE_SAPLING, "PLANTPOT_APPLE_SAPLING");
		sapling(FarmingProduce.BANANA, ItemID.PLANTPOT_BANANA_SAPLING, "PLANTPOT_BANANA_SAPLING");
		sapling(FarmingProduce.ORANGE, ItemID.PLANTPOT_ORANGE_SAPLING, "PLANTPOT_ORANGE_SAPLING");
		sapling(FarmingProduce.CURRY, ItemID.PLANTPOT_CURRY_SAPLING, "PLANTPOT_CURRY_SAPLING");
		sapling(FarmingProduce.PINEAPPLE, ItemID.PLANTPOT_PINEAPPLE_SAPLING, "PLANTPOT_PINEAPPLE_SAPLING");
		sapling(FarmingProduce.PAPAYA, ItemID.PLANTPOT_PAPAYA_SAPLING, "PLANTPOT_PAPAYA_SAPLING");
		sapling(FarmingProduce.PALM, ItemID.PLANTPOT_PALM_SAPLING, "PLANTPOT_PALM_SAPLING");
		sapling(FarmingProduce.DRAGONFRUIT, ItemID.PLANTPOT_DRAGONFRUIT_SAPLING, "PLANTPOT_DRAGONFRUIT_SAPLING");
		sapling(FarmingProduce.TEAK, ItemID.PLANTPOT_TEAK_SAPLING, "PLANTPOT_TEAK_SAPLING");
		sapling(FarmingProduce.MAHOGANY, ItemID.PLANTPOT_MAHOGANY_SAPLING, "PLANTPOT_MAHOGANY_SAPLING");
		sapling(FarmingProduce.CAMPHOR, ItemID.PLANTPOT_CAMPHOR_SAPLING, "PLANTPOT_CAMPHOR_SAPLING");
		sapling(FarmingProduce.IRONWOOD, ItemID.PLANTPOT_IRONWOOD_SAPLING, "PLANTPOT_IRONWOOD_SAPLING");
		sapling(FarmingProduce.ROSEWOOD, ItemID.PLANTPOT_ROSEWOOD_SAPLING, "PLANTPOT_ROSEWOOD_SAPLING");
		sapling(FarmingProduce.CALQUAT, ItemID.PLANTPOT_CALQUAT_SAPLING, "PLANTPOT_CALQUAT_SAPLING");
		sapling(FarmingProduce.CELASTRUS, ItemID.PLANTPOT_CELASTRUS_TREE_SAPLING, "PLANTPOT_CELASTRUS_TREE_SAPLING");
		sapling(FarmingProduce.REDWOOD, ItemID.PLANTPOT_REDWOOD_TREE_SAPLING, "PLANTPOT_REDWOOD_TREE_SAPLING");
		sapling(FarmingProduce.SPIRIT_TREE, ItemID.PLANTPOT_SPIRIT_TREE_SAPLING, "PLANTPOT_SPIRIT_TREE_SAPLING");
		sapling(FarmingProduce.CRYSTAL_TREE, ItemID.PLANTPOT_CRYSTAL_TREE_SAPLING, "PLANTPOT_CRYSTAL_TREE_SAPLING");

		// Protection payments.
		pay(FarmingProduce.POTATO, ItemID.BUCKET_COMPOST, "BUCKET_COMPOST", "Compost", 2);
		pay(FarmingProduce.ONION, ItemID.SACK_POTATO_10, "SACK_POTATO_10", "Sack of potatoes", 1);
		pay(FarmingProduce.CABBAGE, ItemID.SACK_ONION_10, "SACK_ONION_10", "Sack of onions", 1);
		pay(FarmingProduce.TOMATO, ItemID.SACK_CABBAGE_10, "SACK_CABBAGE_10", "Sack of cabbages", 2);
		pay(FarmingProduce.SWEETCORN, ItemID.JUTE_FIBRE, "JUTE_FIBRE", "Jute fibre", 10);
		pay(FarmingProduce.STRAWBERRY, ItemID.BASKET_APPLE_5, "BASKET_APPLE_5", "Basket of apples", 1);
		pay(FarmingProduce.WATERMELON, ItemID.CURRY_LEAF, "CURRY_LEAF", "Curry leaf", 10);
		pay(FarmingProduce.SNAPE_GRASS, ItemID.JANGERBERRIES, "JANGERBERRIES", "Jangerberries", 5);
		pay(FarmingProduce.BARLEY, ItemID.BUCKET_COMPOST, "BUCKET_COMPOST", "Compost", 3);
		pay(FarmingProduce.HAMMERSTONE, ItemID.MARIGOLD, "MARIGOLD", "Marigolds", 1);
		pay(FarmingProduce.ASGARNIAN, ItemID.SACK_ONION_10, "SACK_ONION_10", "Sack of onions", 1);
		pay(FarmingProduce.JUTE, ItemID.BARLEY_MALT, "BARLEY_MALT", "Barley malt", 6);
		pay(FarmingProduce.YANILLIAN, ItemID.BASKET_TOMATO_5, "BASKET_TOMATO_5", "Basket of tomatoes", 1);
		pay(FarmingProduce.FLAX, ItemID.GRAIN, "GRAIN", "Grain", 6);
		pay(FarmingProduce.KRANDORIAN, ItemID.SACK_CABBAGE_10, "SACK_CABBAGE_10", "Sack of cabbages", 3);
		pay(FarmingProduce.WILDBLOOD, ItemID.NASTURTIUM, "NASTURTIUM", "Nasturtiums", 1);
		pay(FarmingProduce.HEMP, ItemID.FLAX, "FLAX", "Flax", 6);
		pay(FarmingProduce.COTTON, ItemID.HEMP, "HEMP", "Hemp", 6);
		pay(FarmingProduce.REDBERRIES, ItemID.SACK_CABBAGE_10, "SACK_CABBAGE_10", "Sack of cabbages", 4);
		pay(FarmingProduce.CADAVABERRIES, ItemID.BASKET_TOMATO_5, "BASKET_TOMATO_5", "Basket of tomatoes", 3);
		pay(FarmingProduce.DWELLBERRIES, ItemID.BASKET_STRAWBERRY_5, "BASKET_STRAWBERRY_5", "Basket of strawberries", 3);
		pay(FarmingProduce.JANGERBERRIES, ItemID.WATERMELON, "WATERMELON", "Watermelon", 6);
		pay(FarmingProduce.WHITEBERRIES, ItemID.BITTERCAP_MUSHROOM, "BITTERCAP_MUSHROOM", "Bittercap mushroom", 8);
		pay(FarmingProduce.OAK, ItemID.BASKET_TOMATO_5, "BASKET_TOMATO_5", "Basket of tomatoes", 1);
		pay(FarmingProduce.WILLOW, ItemID.BASKET_APPLE_5, "BASKET_APPLE_5", "Basket of apples", 1);
		pay(FarmingProduce.MAPLE, ItemID.BASKET_ORANGE_5, "BASKET_ORANGE_5", "Basket of oranges", 1);
		pay(FarmingProduce.YEW, ItemID.CACTUS_SPINE, "CACTUS_SPINE", "Cactus spine", 10);
		pay(FarmingProduce.MAGIC, ItemID.COCONUT, "COCONUT", "Coconut", 25);
		pay(FarmingProduce.APPLE, ItemID.SWEETCORN, "SWEETCORN", "Sweetcorn", 9);
		pay(FarmingProduce.BANANA, ItemID.BASKET_APPLE_5, "BASKET_APPLE_5", "Basket of apples", 4);
		pay(FarmingProduce.ORANGE, ItemID.BASKET_STRAWBERRY_5, "BASKET_STRAWBERRY_5", "Basket of strawberries", 3);
		pay(FarmingProduce.CURRY, ItemID.BASKET_BANANA_5, "BASKET_BANANA_5", "Basket of bananas", 5);
		pay(FarmingProduce.PINEAPPLE, ItemID.WATERMELON, "WATERMELON", "Watermelon", 10);
		pay(FarmingProduce.PAPAYA, ItemID.PINEAPPLE, "PINEAPPLE", "Pineapple", 10);
		pay(FarmingProduce.PALM, ItemID.PAPAYA, "PAPAYA", "Papaya fruit", 15);
		pay(FarmingProduce.DRAGONFRUIT, ItemID.COCONUT, "COCONUT", "Coconut", 15);
		pay(FarmingProduce.TEAK, ItemID.LIMPWURT_ROOT, "LIMPWURT_ROOT", "Limpwurt root", 15);
		pay(FarmingProduce.MAHOGANY, ItemID.YANILLIAN_HOPS, "YANILLIAN_HOPS", "Yanillian hops", 25);
		pay(FarmingProduce.CAMPHOR, ItemID.WHITE_BERRIES, "WHITE_BERRIES", "White berries", 10);
		pay(FarmingProduce.IRONWOOD, ItemID.CURRY_LEAF, "CURRY_LEAF", "Curry leaf", 10);
		pay(FarmingProduce.ROSEWOOD, ItemID.DRAGONFRUIT, "DRAGONFRUIT", "Dragonfruit", 8);
		pay(FarmingProduce.CALQUAT, ItemID.POISONIVY_BERRIES, "POISONIVY_BERRIES", "Poison ivy berries", 8);
		// OSRS Wiki special patches table (checked 2026-10-21). A spirit tree takes three items at once
		// (OSRS Wiki Spirit tree patch: 5 monkey nuts, 1 monkey bar, 1 ground suqah tooth).
		pay(FarmingProduce.SPIRIT_TREE, ItemID.MM_MONKEY_NUTS, "MM_MONKEY_NUTS", "Monkey nuts", 5);
		PAYMENTS.get(FarmingProduce.SPIRIT_TREE).also.add(new Payment(ItemID.MM_MONKEY_BAR, "MM_MONKEY_BAR", "Monkey bar", 1));
		PAYMENTS.get(FarmingProduce.SPIRIT_TREE).also.add(new Payment(ItemID.LUNAR_GROUNDTOOTH, "LUNAR_GROUNDTOOTH", "Ground suqah tooth", 1));
		pay(FarmingProduce.CACTUS, ItemID.CADAVABERRIES, "CADAVABERRIES", "Cadava berries", 6);
		pay(FarmingProduce.POTATO_CACTUS, ItemID.SNAPE_GRASS, "SNAPE_GRASS", "Snape grass", 8);
		pay(FarmingProduce.CELASTRUS, ItemID.CACTUS_POTATO, "CACTUS_POTATO", "Potato cactus", 8);
		pay(FarmingProduce.REDWOOD, ItemID.DRAGONFRUIT, "DRAGONFRUIT", "Dragonfruit", 6);
	}

	/**
	 * Planting levels, OSRS Wiki "Seed Table" (checked 2026-10-21), plus the
	 * hardwood levels from the wiki's special-patches table. Flax, hemp and
	 * cotton (Sailing-era hops) are not in that table: their level is unknown,
	 * so they are never filtered out.
	 */
	private static final Map<FarmingProduce, Integer> LEVEL = new EnumMap<>(FarmingProduce.class);

	static
	{
		Object[][] levels = {
			{FarmingProduce.POTATO, 1}, {FarmingProduce.ONION, 5}, {FarmingProduce.CABBAGE, 7}, {FarmingProduce.TOMATO, 12},
			{FarmingProduce.SWEETCORN, 20}, {FarmingProduce.STRAWBERRY, 31}, {FarmingProduce.WATERMELON, 47}, {FarmingProduce.SNAPE_GRASS, 61},
			{FarmingProduce.MARIGOLD, 2}, {FarmingProduce.ROSEMARY, 11}, {FarmingProduce.NASTURTIUM, 24}, {FarmingProduce.WOAD, 25},
			{FarmingProduce.LIMPWURT, 26}, {FarmingProduce.WHITE_LILY, 58},
			{FarmingProduce.BARLEY, 3}, {FarmingProduce.HAMMERSTONE, 4}, {FarmingProduce.ASGARNIAN, 8}, {FarmingProduce.JUTE, 13},
			{FarmingProduce.YANILLIAN, 16}, {FarmingProduce.KRANDORIAN, 21}, {FarmingProduce.WILDBLOOD, 28},
			{FarmingProduce.REDBERRIES, 10}, {FarmingProduce.CADAVABERRIES, 22}, {FarmingProduce.DWELLBERRIES, 36}, {FarmingProduce.JANGERBERRIES, 48},
			{FarmingProduce.WHITEBERRIES, 59}, {FarmingProduce.POISON_IVY, 70},
			{FarmingProduce.OAK, 15}, {FarmingProduce.WILLOW, 30}, {FarmingProduce.MAPLE, 45}, {FarmingProduce.YEW, 60}, {FarmingProduce.MAGIC, 75},
			{FarmingProduce.APPLE, 27}, {FarmingProduce.BANANA, 33}, {FarmingProduce.ORANGE, 39}, {FarmingProduce.CURRY, 42},
			{FarmingProduce.PINEAPPLE, 51}, {FarmingProduce.PAPAYA, 57}, {FarmingProduce.PALM, 68}, {FarmingProduce.DRAGONFRUIT, 81},
			{FarmingProduce.TEAK, 35}, {FarmingProduce.MAHOGANY, 55}, {FarmingProduce.CAMPHOR, 66}, {FarmingProduce.IRONWOOD, 80},
			{FarmingProduce.ROSEWOOD, 92},
			{FarmingProduce.SEAWEED, 23}, {FarmingProduce.MUSHROOM, 53}, {FarmingProduce.BELLADONNA, 63}, {FarmingProduce.HESPORI, 65},
			{FarmingProduce.ATTAS, 76}, {FarmingProduce.IASOR, 76}, {FarmingProduce.KRONOS, 76}, {FarmingProduce.CALQUAT, 72},
			{FarmingProduce.CRYSTAL_TREE, 74}, {FarmingProduce.SPIRIT_TREE, 83}, {FarmingProduce.CELASTRUS, 85}, {FarmingProduce.REDWOOD, 90},
			{FarmingProduce.CACTUS, 55}, {FarmingProduce.POTATO_CACTUS, 64},
		};
		for (Object[] row : levels)
		{
			LEVEL.put((FarmingProduce) row[0], (Integer) row[1]);
		}
		LEVEL.putAll(HERB_LEVEL);
	}

	private FarmingSeeds()
	{
	}

	/** The crop's planting level, or null when it is not sourced (never guessed). */
	public static Integer level(FarmingProduce produce)
	{
		return produce == null ? null : LEVEL.get(produce);
	}

	/**
	 * Crops with seed data that a patch type takes and that {@code farmingLevel}
	 * can plant (all of them when the level is unknown; crops whose level is not
	 * sourced always), highest level first. Any of them satisfies the patch
	 * type's generic planting need: the player decides in game.
	 */
	public static List<FarmingProduce> plantable(FarmingPatchType type, Integer farmingLevel)
	{
		List<FarmingProduce> out = new ArrayList<>();
		for (Map.Entry<FarmingProduce, Seed> e : SEEDS.entrySet())
		{
			FarmingProduce p = e.getKey();
			if (p.getPatchType() != type)
			{
				continue;
			}
			Integer need = LEVEL.get(p);
			if (farmingLevel == null || need == null || need <= farmingLevel)
			{
				out.add(p);
			}
		}
		out.sort((a, b) -> Integer.compare(LEVEL.getOrDefault(b, 0), LEVEL.getOrDefault(a, 0)));
		return out;
	}

	/** Most seeds (or saplings) any listed crop of the type takes per patch (hops: 4, but 3 for jute and the Sailing hops). */
	public static int maxPerPatch(FarmingPatchType type)
	{
		int max = 0;
		for (Map.Entry<FarmingProduce, Seed> e : SEEDS.entrySet())
		{
			if (e.getKey().getPatchType() == type)
			{
				max = Math.max(max, e.getValue().getPerPatch());
			}
		}
		return max;
	}

	/** Fewest per patch for the type (equal to {@link #maxPerPatch} unless crops differ). */
	public static int minPerPatch(FarmingPatchType type)
	{
		int min = Integer.MAX_VALUE;
		for (Map.Entry<FarmingProduce, Seed> e : SEEDS.entrySet())
		{
			if (e.getKey().getPatchType() == type)
			{
				min = Math.min(min, e.getValue().getPerPatch());
			}
		}
		return min == Integer.MAX_VALUE ? 0 : min;
	}

	/** True when the type is planted with saplings (unstackable, one per patch). */
	public static boolean saplings(FarmingPatchType type)
	{
		for (Map.Entry<FarmingProduce, Seed> e : SEEDS.entrySet())
		{
			if (e.getKey().getPatchType() == type)
			{
				return e.getValue().isSapling();
			}
		}
		return false;
	}

	/** "Herb seeds", "Tree saplings", "Mushroom spores": the planting category for a patch type. */
	public static String category(FarmingPatchType type)
	{
		switch (type)
		{
			case HERB:
				return "Herb seeds";
			case ALLOTMENT:
				return "Allotment seeds";
			case FLOWER:
				return "Flower seeds";
			case HOPS:
				return "Hops seeds";
			case BUSH:
				return "Bush seeds";
			case TREE:
				return "Tree saplings";
			case FRUIT_TREE:
				return "Fruit tree saplings";
			case HARDWOOD_TREE:
				return "Hardwood saplings";
			case SEAWEED:
				return "Seaweed spores";
			case MUSHROOM:
				return "Mushroom spores";
			case CACTUS:
				return "Cactus seeds";
			case ANIMA:
				return "Anima seed";
			case HESPORI:
				return "Hespori seed";
			case BELLADONNA:
				return "Belladonna seeds";
			default:
				return type.getDisplayName() + " sapling";
		}
	}

	/** The crop a carried item plants, or null. */
	public static FarmingProduce cropOf(int itemId)
	{
		for (Map.Entry<FarmingProduce, Seed> e : SEEDS.entrySet())
		{
			if (e.getValue().getItemId() == itemId)
			{
				return e.getKey();
			}
		}
		return null;
	}

	private static void herb(FarmingProduce p, int id, String name, int level)
	{
		SEEDS.put(p, new Seed(id, name, 1, false));
		HERB_LEVEL.put(p, level);
	}

	private static void seed(FarmingProduce p, int id, String name, int perPatch)
	{
		SEEDS.put(p, new Seed(id, name, perPatch, false));
	}

	private static void sapling(FarmingProduce p, int id, String name)
	{
		SEEDS.put(p, new Seed(id, name, 1, true));
	}

	private static void pay(FarmingProduce p, int id, String gameval, String label, int qty)
	{
		PAYMENTS.put(p, new Payment(id, gameval, label, qty));
	}

	/** The seed or sapling for a planned crop, or null when Spyglass has no sourced data for it. */
	public static Seed forCrop(FarmingProduce produce)
	{
		return produce == null ? null : SEEDS.get(produce);
	}

	/** The protection payment for a crop, or null (cannot be protected, or not sourced). */
	public static Payment paymentFor(FarmingProduce produce)
	{
		return produce == null ? null : PAYMENTS.get(produce);
	}

	/** The herb's planting level, or null for anything else. */
	public static Integer herbLevel(FarmingProduce produce)
	{
		return produce == null ? null : HERB_LEVEL.get(produce);
	}

	/**
	 * Herb seeds an account can plant at {@code farmingLevel} (every herb seed
	 * when the level is unknown), best first: any of them satisfies a "suitable
	 * herb seed" need, so the player decides what to plant.
	 */
	public static List<FarmingProduce> herbsPlantableAt(Integer farmingLevel)
	{
		List<FarmingProduce> out = new ArrayList<>();
		for (Map.Entry<FarmingProduce, Integer> e : HERB_LEVEL.entrySet())
		{
			if (farmingLevel == null || e.getValue() <= farmingLevel)
			{
				out.add(e.getKey());
			}
		}
		out.sort((a, b) -> HERB_LEVEL.get(b) - HERB_LEVEL.get(a));
		return out;
	}

	public static Map<FarmingProduce, Seed> all()
	{
		return Collections.unmodifiableMap(SEEDS);
	}

	public static Map<FarmingProduce, Payment> payments()
	{
		return Collections.unmodifiableMap(PAYMENTS);
	}
}
