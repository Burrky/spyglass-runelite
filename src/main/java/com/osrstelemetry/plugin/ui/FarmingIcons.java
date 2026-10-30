package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.farming.FarmingModel;
import com.osrstelemetry.plugin.farming.FarmingPatchType;
import com.osrstelemetry.plugin.farming.FarmingProduce;
import com.osrstelemetry.plugin.farming.PatchStatus;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Which genuine game item sprite a patch card shows (canonical §3.2, "Patch
 * card icon"). Every id here was checked against the rendered item image, not
 * just its constant name.
 *
 * <ul>
 *   <li><b>Crop known</b>: an item that clearly is that crop, normally its
 *   produce (the clean Snapdragon herb, not the seed; Yew logs; Cooking apple).
 *   Where the produce is not a sensible picture the crop's own item is used
 *   (Spirit tree, Hespori seed, the anima plants).</li>
 *   <li><b>No crop identity</b> (clear, never tracked, or a diseased/dead crop
 *   whose identity was never seen): one fixed sprite per patch type. Sapling
 *   types use their type's potted sapling (identical art across that type's
 *   crops, so it never suggests a particular tree); seed types use the
 *   container or tool that type is known by. Clear and not tracked share it
 *   and are told apart by their labels.</li>
 * </ul>
 *
 * The icon follows OBSERVED crop identity only ({@link PatchStatus#getProduce()},
 * which keeps retained knowledge through unreadable readings and becomes empty
 * on an observed clear). A planned replacement never reaches this class.
 * No location, teleport or route item is ever used.
 */
final class FarmingIcons
{
	private static final Map<FarmingProduce, Integer> CROP = new EnumMap<>(FarmingProduce.class);
	private static final Map<FarmingPatchType, Integer> PATCH = new EnumMap<>(FarmingPatchType.class);

	static
	{
		// Herbs: the clean herb.
		CROP.put(FarmingProduce.GUAM, ItemID.GUAM_LEAF);
		CROP.put(FarmingProduce.MARRENTILL, ItemID.MARENTILL);
		CROP.put(FarmingProduce.TARROMIN, ItemID.TARROMIN);
		CROP.put(FarmingProduce.HARRALANDER, ItemID.HARRALANDER);
		CROP.put(FarmingProduce.RANARR, ItemID.RANARR_WEED);
		CROP.put(FarmingProduce.TOADFLAX, ItemID.TOADFLAX);
		CROP.put(FarmingProduce.IRIT, ItemID.IRIT_LEAF);
		CROP.put(FarmingProduce.AVANTOE, ItemID.AVANTOE);
		CROP.put(FarmingProduce.KWUARM, ItemID.KWUARM);
		CROP.put(FarmingProduce.HUASCA, ItemID.HUASCA);
		CROP.put(FarmingProduce.SNAPDRAGON, ItemID.SNAPDRAGON);
		CROP.put(FarmingProduce.CADANTINE, ItemID.CADANTINE);
		CROP.put(FarmingProduce.LANTADYME, ItemID.LANTADYME);
		CROP.put(FarmingProduce.DWARF_WEED, ItemID.DWARF_WEED);
		CROP.put(FarmingProduce.TORSTOL, ItemID.TORSTOL);
		CROP.put(FarmingProduce.GOUTWEED, ItemID.EADGAR_GOUTWEED_HERB);
		// Allotments.
		CROP.put(FarmingProduce.POTATO, ItemID.POTATO);
		CROP.put(FarmingProduce.ONION, ItemID.ONION);
		CROP.put(FarmingProduce.CABBAGE, ItemID.CABBAGE);
		CROP.put(FarmingProduce.TOMATO, ItemID.TOMATO);
		CROP.put(FarmingProduce.SWEETCORN, ItemID.SWEETCORN);
		CROP.put(FarmingProduce.STRAWBERRY, ItemID.STRAWBERRY);
		CROP.put(FarmingProduce.WATERMELON, ItemID.WATERMELON);
		CROP.put(FarmingProduce.SNAPE_GRASS, ItemID.SNAPE_GRASS);
		// Flowers.
		CROP.put(FarmingProduce.MARIGOLD, ItemID.MARIGOLD);
		CROP.put(FarmingProduce.ROSEMARY, ItemID.ROSEMARY);
		CROP.put(FarmingProduce.NASTURTIUM, ItemID.NASTURTIUM);
		CROP.put(FarmingProduce.WOAD, ItemID.WOADLEAF);
		CROP.put(FarmingProduce.LIMPWURT, ItemID.LIMPWURT_ROOT);
		CROP.put(FarmingProduce.WHITE_LILY, ItemID.WHITELILLY);
		CROP.put(FarmingProduce.SCARECROW, ItemID.SCARECROW_COMPLETE);
		// Bushes.
		CROP.put(FarmingProduce.REDBERRIES, ItemID.REDBERRIES);
		CROP.put(FarmingProduce.CADAVABERRIES, ItemID.CADAVABERRIES);
		CROP.put(FarmingProduce.DWELLBERRIES, ItemID.DWELLBERRIES);
		CROP.put(FarmingProduce.JANGERBERRIES, ItemID.JANGERBERRIES);
		CROP.put(FarmingProduce.WHITEBERRIES, ItemID.WHITE_BERRIES);
		CROP.put(FarmingProduce.POISON_IVY, ItemID.POISONIVY_BERRIES);
		// Hops.
		CROP.put(FarmingProduce.BARLEY, ItemID.BARLEY);
		CROP.put(FarmingProduce.HAMMERSTONE, ItemID.HAMMERSTONE_HOPS);
		CROP.put(FarmingProduce.ASGARNIAN, ItemID.ASGARNIAN_HOPS);
		CROP.put(FarmingProduce.JUTE, ItemID.JUTE_FIBRE);
		CROP.put(FarmingProduce.YANILLIAN, ItemID.YANILLIAN_HOPS);
		CROP.put(FarmingProduce.FLAX, ItemID.FLAX);
		CROP.put(FarmingProduce.KRANDORIAN, ItemID.KRANDORIAN_HOPS);
		CROP.put(FarmingProduce.WILDBLOOD, ItemID.WILDBLOOD_HOPS);
		CROP.put(FarmingProduce.HEMP, ItemID.HEMP);
		CROP.put(FarmingProduce.COTTON, ItemID.COTTON_BOLL);
		// Trees: the logs.
		CROP.put(FarmingProduce.OAK, ItemID.OAK_LOGS);
		CROP.put(FarmingProduce.WILLOW, ItemID.WILLOW_LOGS);
		CROP.put(FarmingProduce.MAPLE, ItemID.MAPLE_LOGS);
		CROP.put(FarmingProduce.YEW, ItemID.YEW_LOGS);
		CROP.put(FarmingProduce.MAGIC, ItemID.MAGIC_LOGS);
		CROP.put(FarmingProduce.TEAK, ItemID.TEAK_LOGS);
		CROP.put(FarmingProduce.MAHOGANY, ItemID.MAHOGANY_LOGS);
		CROP.put(FarmingProduce.CAMPHOR, ItemID.CAMPHOR_LOGS);
		CROP.put(FarmingProduce.IRONWOOD, ItemID.IRONWOOD_LOGS);
		CROP.put(FarmingProduce.ROSEWOOD, ItemID.ROSEWOOD_LOGS);
		CROP.put(FarmingProduce.REDWOOD, ItemID.REDWOOD_LOGS);
		// Fruit trees: the fruit.
		CROP.put(FarmingProduce.APPLE, ItemID.COOKING_APPLE);
		CROP.put(FarmingProduce.BANANA, ItemID.BANANA);
		CROP.put(FarmingProduce.ORANGE, ItemID.ORANGE);
		CROP.put(FarmingProduce.CURRY, ItemID.CURRY_LEAF);
		CROP.put(FarmingProduce.PINEAPPLE, ItemID.PINEAPPLE);
		CROP.put(FarmingProduce.PAPAYA, ItemID.PAPAYA);
		CROP.put(FarmingProduce.PALM, ItemID.COCONUT);
		CROP.put(FarmingProduce.DRAGONFRUIT, ItemID.DRAGONFRUIT);
		// Special types.
		CROP.put(FarmingProduce.CACTUS, ItemID.CACTUS_SPINE);
		CROP.put(FarmingProduce.POTATO_CACTUS, ItemID.CACTUS_POTATO);
		CROP.put(FarmingProduce.ATTAS, ItemID.ANIMA_ATTAS);
		CROP.put(FarmingProduce.IASOR, ItemID.ANIMA_IASOR);
		CROP.put(FarmingProduce.KRONOS, ItemID.ANIMA_KRONOS);
		CROP.put(FarmingProduce.SEAWEED, ItemID.GIANT_SEAWEED);
		CROP.put(FarmingProduce.MUSHROOM, ItemID.BITTERCAP_MUSHROOM);
		CROP.put(FarmingProduce.BELLADONNA, ItemID.NIGHTSHADE);
		CROP.put(FarmingProduce.CALQUAT, ItemID.CALQUAT_FRUIT);
		CROP.put(FarmingProduce.SPIRIT_TREE, ItemID.SPIRIT_TREE_DUMMY);
		CROP.put(FarmingProduce.CELASTRUS, ItemID.CELASTRUS_WOOD);
		CROP.put(FarmingProduce.CRYSTAL_TREE, ItemID.PRIF_CRYSTAL_SHARD);
		CROP.put(FarmingProduce.HESPORI, ItemID.HESPORI_SEED);

		// Patch types with no crop identity on show.
		PATCH.put(FarmingPatchType.HERB, ItemID.SLAYER_HERB_SACK);
		PATCH.put(FarmingPatchType.ALLOTMENT, ItemID.SACK_EMPTY);
		PATCH.put(FarmingPatchType.FLOWER, ItemID.FLOWERS_WATERFALL_QUEST);
		PATCH.put(FarmingPatchType.BUSH, ItemID.BASKET_EMPTY);
		PATCH.put(FarmingPatchType.HOPS, ItemID.BEER_GLASS);
		PATCH.put(FarmingPatchType.TREE, ItemID.PLANTPOT_OAK_SAPLING);
		PATCH.put(FarmingPatchType.FRUIT_TREE, ItemID.PLANTPOT_APPLE_SAPLING);
		PATCH.put(FarmingPatchType.HARDWOOD_TREE, ItemID.PLANTPOT_TEAK_SAPLING);
		PATCH.put(FarmingPatchType.REDWOOD, ItemID.PLANTPOT_REDWOOD_TREE_SAPLING);
		PATCH.put(FarmingPatchType.SPIRIT_TREE, ItemID.PLANTPOT_SPIRIT_TREE_SAPLING);
		PATCH.put(FarmingPatchType.CALQUAT, ItemID.PLANTPOT_CALQUAT_SAPLING);
		PATCH.put(FarmingPatchType.CELASTRUS, ItemID.PLANTPOT_CELASTRUS_TREE_SAPLING);
		PATCH.put(FarmingPatchType.CRYSTAL_TREE, ItemID.PLANTPOT_CRYSTAL_TREE_SAPLING);
		PATCH.put(FarmingPatchType.ANIMA, ItemID.DIBBER);
		PATCH.put(FarmingPatchType.BELLADONNA, ItemID.DIBBER);
		PATCH.put(FarmingPatchType.CACTUS, ItemID.DIBBER);
		PATCH.put(FarmingPatchType.SEAWEED, ItemID.DIBBER);
		PATCH.put(FarmingPatchType.MUSHROOM, ItemID.DIBBER);
		PATCH.put(FarmingPatchType.HESPORI, ItemID.DIBBER);
	}

	private FarmingIcons()
	{
	}

	/** The crop's item, or -1 for weeds / no crop. */
	static int cropItem(FarmingProduce produce)
	{
		Integer id = produce == null ? null : CROP.get(produce);
		return id == null ? -1 : id;
	}

	/** The fixed sprite for a patch type showing no crop identity. */
	static int patchItem(FarmingPatchType type)
	{
		Integer id = PATCH.get(type);
		return id == null ? ItemID.DIBBER : id;
	}

	/** The sprite a card shows: the observed crop when its identity is known, else the patch-type sprite. */
	static int itemFor(FarmingModel.Row row)
	{
		int crop = cropItem(row.getStatus().getProduce());
		return crop >= 0 ? crop : patchItem(row.getPatch().getType());
	}

	/** True when the card's sprite stands for a known crop (not the patch-type fallback). */
	static boolean showsCrop(FarmingModel.Row row)
	{
		return cropItem(row.getStatus().getProduce()) >= 0;
	}

	/**
	 * The sprite's tooltip and accessible name: the full crop name; otherwise
	 * what the fallback stands for ("Herb patch, clear", "Unknown herb").
	 */
	static String label(FarmingModel.Row row)
	{
		PatchStatus s = row.getStatus();
		String type = row.getPatch().getType().getDisplayName();
		if (showsCrop(row))
		{
			return s.getDisplay() == PatchStatus.Display.CHECK_PATCH
				? row.getCrop() + " (last known)"
				: row.getCrop();
		}
		if (s.isProduceUnknown())
		{
			return row.getCrop();
		}
		switch (s.getDisplay())
		{
			case CLEAR:
				return type + " patch, clear";
			case NOT_TRACKED:
				return type + " patch, not tracked yet";
			default:
				return type + " patch";
		}
	}
}
