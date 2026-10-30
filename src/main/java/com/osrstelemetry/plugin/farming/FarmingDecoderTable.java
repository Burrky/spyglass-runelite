package com.osrstelemetry.plugin.farming;

// GENERATED from RuneLite's farming tables -- do not edit by hand; regenerate instead.
// Derived from net.runelite.client.plugins.timetracking.farming.PatchImplementation
// (RuneLite master @ e98399f931c6, BSD 2-Clause; Copyright (c) 2019 Abex).
// See THIRD_PARTY_NOTICES.md ("Farming patch tables and capture guards").

/**
 * Raw varbit value ranges for every supported patch type, as RuneLite decodes
 * them, with Spyglass refinements taken from RuneLite's own object-name
 * comments:
 * <ul>
 *   <li>A "Check-health" object (RuneLite: growing at the last stage) is its own
 *   condition, CHECK_HEALTH.</li>
 *   <li>A harvestable tree-like patch is STANDING ("Chop down") or a STUMP.</li>
 *   <li>GROWN: a fully grown spirit tree ("Travel"), and a scarecrow in a
 *   flower patch: nothing to harvest, nothing growing.</li>
 * </ul>
 * Weeds rows keep RuneLite's weed level; level 0 becomes EMPTY at decode time.
 * A dead herb has no known produce (RuneLite's ANYHERB). Values covered by no
 * row decode as UNKNOWN (for example herb 110-127 and 220, where RuneLite
 * returns null), never as empty.
 */
final class FarmingDecoderTable
{
	private FarmingDecoderTable()
	{
	}

	/** How a row turns a raw value into a stage number. */
	static final int OFFSET = 0;
	static final int REVERSE = 1;
	static final int CONST = 2;
	/** The produce's last growth stage (check-health). */
	static final int LAST = 3;

	static final class Row
	{
		final int lo;
		final int hi;
		final PatchCondition condition;
		final FarmingProduce produce;
		final int stageMode;
		final int stageParam;
		final PatchSubState subState;

		Row(int lo, int hi, PatchCondition condition, FarmingProduce produce, int stageMode, int stageParam, PatchSubState subState)
		{
			this.lo = lo;
			this.hi = hi;
			this.condition = condition;
			this.produce = produce;
			this.stageMode = stageMode;
			this.stageParam = stageParam;
			this.subState = subState;
		}
	}

	private static Row row(int lo, int hi, PatchCondition condition, FarmingProduce produce, int stageMode, int stageParam, PatchSubState subState)
	{
		return new Row(lo, hi, condition, produce, stageMode, stageParam, subState);
	}

	static Row[] rows(FarmingPatchType type)
	{
		switch (type)
		{
			case HERB:
				return HERB;
			case ALLOTMENT:
				return ALLOTMENT;
			case TREE:
				return TREE;
			case FLOWER:
				return FLOWER;
			case BUSH:
				return BUSH;
			case FRUIT_TREE:
				return FRUIT_TREE;
			case HOPS:
				return HOPS;
			case HARDWOOD_TREE:
				return HARDWOOD_TREE;
			case REDWOOD:
				return REDWOOD;
			case SPIRIT_TREE:
				return SPIRIT_TREE;
			case ANIMA:
				return ANIMA;
			case BELLADONNA:
				return BELLADONNA;
			case CACTUS:
				return CACTUS;
			case SEAWEED:
				return SEAWEED;
			case CALQUAT:
				return CALQUAT;
			case CELASTRUS:
				return CELASTRUS;
			case CRYSTAL_TREE:
				return CRYSTAL_TREE;
			case MUSHROOM:
				return MUSHROOM;
			case HESPORI:
				return HESPORI;
			default:
				return new Row[0];
		}
	}

	static final Row[] HERB = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.GROWING, FarmingProduce.GUAM, OFFSET, 4, PatchSubState.NONE),
		row(8, 10, PatchCondition.HARVESTABLE, FarmingProduce.GUAM, REVERSE, 10, PatchSubState.NONE),
		row(11, 14, PatchCondition.GROWING, FarmingProduce.MARRENTILL, OFFSET, 11, PatchSubState.NONE),
		row(15, 17, PatchCondition.HARVESTABLE, FarmingProduce.MARRENTILL, REVERSE, 17, PatchSubState.NONE),
		row(18, 21, PatchCondition.GROWING, FarmingProduce.TARROMIN, OFFSET, 18, PatchSubState.NONE),
		row(22, 24, PatchCondition.HARVESTABLE, FarmingProduce.TARROMIN, REVERSE, 24, PatchSubState.NONE),
		row(25, 28, PatchCondition.GROWING, FarmingProduce.HARRALANDER, OFFSET, 25, PatchSubState.NONE),
		row(29, 31, PatchCondition.HARVESTABLE, FarmingProduce.HARRALANDER, REVERSE, 31, PatchSubState.NONE),
		row(32, 35, PatchCondition.GROWING, FarmingProduce.RANARR, OFFSET, 32, PatchSubState.NONE),
		row(36, 38, PatchCondition.HARVESTABLE, FarmingProduce.RANARR, REVERSE, 38, PatchSubState.NONE),
		row(39, 42, PatchCondition.GROWING, FarmingProduce.TOADFLAX, OFFSET, 39, PatchSubState.NONE),
		row(43, 45, PatchCondition.HARVESTABLE, FarmingProduce.TOADFLAX, REVERSE, 45, PatchSubState.NONE),
		row(46, 49, PatchCondition.GROWING, FarmingProduce.IRIT, OFFSET, 46, PatchSubState.NONE),
		row(50, 52, PatchCondition.HARVESTABLE, FarmingProduce.IRIT, REVERSE, 52, PatchSubState.NONE),
		row(53, 56, PatchCondition.GROWING, FarmingProduce.AVANTOE, OFFSET, 53, PatchSubState.NONE),
		row(57, 59, PatchCondition.HARVESTABLE, FarmingProduce.AVANTOE, REVERSE, 59, PatchSubState.NONE),
		row(60, 63, PatchCondition.GROWING, FarmingProduce.HUASCA, OFFSET, 60, PatchSubState.NONE),
		row(64, 66, PatchCondition.HARVESTABLE, FarmingProduce.HUASCA, REVERSE, 66, PatchSubState.NONE),
		row(67, 67, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(68, 71, PatchCondition.GROWING, FarmingProduce.KWUARM, OFFSET, 68, PatchSubState.NONE),
		row(72, 74, PatchCondition.HARVESTABLE, FarmingProduce.KWUARM, REVERSE, 74, PatchSubState.NONE),
		row(75, 78, PatchCondition.GROWING, FarmingProduce.SNAPDRAGON, OFFSET, 75, PatchSubState.NONE),
		row(79, 81, PatchCondition.HARVESTABLE, FarmingProduce.SNAPDRAGON, REVERSE, 81, PatchSubState.NONE),
		row(82, 85, PatchCondition.GROWING, FarmingProduce.CADANTINE, OFFSET, 82, PatchSubState.NONE),
		row(86, 88, PatchCondition.HARVESTABLE, FarmingProduce.CADANTINE, REVERSE, 88, PatchSubState.NONE),
		row(89, 92, PatchCondition.GROWING, FarmingProduce.LANTADYME, OFFSET, 89, PatchSubState.NONE),
		row(93, 95, PatchCondition.HARVESTABLE, FarmingProduce.LANTADYME, REVERSE, 95, PatchSubState.NONE),
		row(96, 99, PatchCondition.GROWING, FarmingProduce.DWARF_WEED, OFFSET, 96, PatchSubState.NONE),
		row(100, 102, PatchCondition.HARVESTABLE, FarmingProduce.DWARF_WEED, REVERSE, 102, PatchSubState.NONE),
		row(103, 106, PatchCondition.GROWING, FarmingProduce.TORSTOL, OFFSET, 103, PatchSubState.NONE),
		row(107, 109, PatchCondition.HARVESTABLE, FarmingProduce.TORSTOL, REVERSE, 109, PatchSubState.NONE),
		row(128, 130, PatchCondition.DISEASED, FarmingProduce.GUAM, OFFSET, 127, PatchSubState.NONE),
		row(131, 133, PatchCondition.DISEASED, FarmingProduce.MARRENTILL, OFFSET, 130, PatchSubState.NONE),
		row(134, 136, PatchCondition.DISEASED, FarmingProduce.TARROMIN, OFFSET, 133, PatchSubState.NONE),
		row(137, 139, PatchCondition.DISEASED, FarmingProduce.HARRALANDER, OFFSET, 136, PatchSubState.NONE),
		row(140, 142, PatchCondition.DISEASED, FarmingProduce.RANARR, OFFSET, 139, PatchSubState.NONE),
		row(143, 145, PatchCondition.DISEASED, FarmingProduce.TOADFLAX, OFFSET, 142, PatchSubState.NONE),
		row(146, 148, PatchCondition.DISEASED, FarmingProduce.IRIT, OFFSET, 145, PatchSubState.NONE),
		row(149, 151, PatchCondition.DISEASED, FarmingProduce.AVANTOE, OFFSET, 148, PatchSubState.NONE),
		row(152, 154, PatchCondition.DISEASED, FarmingProduce.KWUARM, OFFSET, 151, PatchSubState.NONE),
		row(155, 157, PatchCondition.DISEASED, FarmingProduce.SNAPDRAGON, OFFSET, 154, PatchSubState.NONE),
		row(158, 160, PatchCondition.DISEASED, FarmingProduce.CADANTINE, OFFSET, 157, PatchSubState.NONE),
		row(161, 163, PatchCondition.DISEASED, FarmingProduce.LANTADYME, OFFSET, 160, PatchSubState.NONE),
		row(164, 166, PatchCondition.DISEASED, FarmingProduce.DWARF_WEED, OFFSET, 163, PatchSubState.NONE),
		row(167, 169, PatchCondition.DISEASED, FarmingProduce.TORSTOL, OFFSET, 166, PatchSubState.NONE),
		row(170, 172, PatchCondition.DEAD, null, OFFSET, 169, PatchSubState.NONE),
		row(173, 175, PatchCondition.DISEASED, FarmingProduce.HUASCA, OFFSET, 172, PatchSubState.NONE),
		row(176, 191, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(192, 195, PatchCondition.GROWING, FarmingProduce.GOUTWEED, OFFSET, 192, PatchSubState.NONE),
		row(196, 197, PatchCondition.HARVESTABLE, FarmingProduce.GOUTWEED, REVERSE, 197, PatchSubState.NONE),
		row(198, 200, PatchCondition.DISEASED, FarmingProduce.GOUTWEED, OFFSET, 197, PatchSubState.NONE),
		row(201, 203, PatchCondition.DEAD, FarmingProduce.GOUTWEED, OFFSET, 200, PatchSubState.NONE),
		row(204, 219, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(221, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] ALLOTMENT = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 5, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(6, 9, PatchCondition.GROWING, FarmingProduce.POTATO, OFFSET, 6, PatchSubState.NONE),
		row(10, 12, PatchCondition.HARVESTABLE, FarmingProduce.POTATO, OFFSET, 10, PatchSubState.NONE),
		row(13, 16, PatchCondition.GROWING, FarmingProduce.ONION, OFFSET, 13, PatchSubState.NONE),
		row(17, 19, PatchCondition.HARVESTABLE, FarmingProduce.ONION, OFFSET, 17, PatchSubState.NONE),
		row(20, 23, PatchCondition.GROWING, FarmingProduce.CABBAGE, OFFSET, 20, PatchSubState.NONE),
		row(24, 26, PatchCondition.HARVESTABLE, FarmingProduce.CABBAGE, OFFSET, 24, PatchSubState.NONE),
		row(27, 30, PatchCondition.GROWING, FarmingProduce.TOMATO, OFFSET, 27, PatchSubState.NONE),
		row(31, 33, PatchCondition.HARVESTABLE, FarmingProduce.TOMATO, OFFSET, 31, PatchSubState.NONE),
		row(34, 39, PatchCondition.GROWING, FarmingProduce.SWEETCORN, OFFSET, 34, PatchSubState.NONE),
		row(40, 42, PatchCondition.HARVESTABLE, FarmingProduce.SWEETCORN, OFFSET, 40, PatchSubState.NONE),
		row(43, 48, PatchCondition.GROWING, FarmingProduce.STRAWBERRY, OFFSET, 43, PatchSubState.NONE),
		row(49, 51, PatchCondition.HARVESTABLE, FarmingProduce.STRAWBERRY, OFFSET, 49, PatchSubState.NONE),
		row(52, 59, PatchCondition.GROWING, FarmingProduce.WATERMELON, OFFSET, 52, PatchSubState.NONE),
		row(60, 62, PatchCondition.HARVESTABLE, FarmingProduce.WATERMELON, OFFSET, 60, PatchSubState.NONE),
		row(63, 69, PatchCondition.GROWING, FarmingProduce.SNAPE_GRASS, OFFSET, 63, PatchSubState.NONE),
		row(70, 73, PatchCondition.GROWING, FarmingProduce.POTATO, OFFSET, 70, PatchSubState.NONE),
		row(74, 76, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(77, 80, PatchCondition.GROWING, FarmingProduce.ONION, OFFSET, 77, PatchSubState.NONE),
		row(81, 83, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(84, 87, PatchCondition.GROWING, FarmingProduce.CABBAGE, OFFSET, 84, PatchSubState.NONE),
		row(88, 90, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(91, 94, PatchCondition.GROWING, FarmingProduce.TOMATO, OFFSET, 91, PatchSubState.NONE),
		row(95, 97, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(98, 103, PatchCondition.GROWING, FarmingProduce.SWEETCORN, OFFSET, 98, PatchSubState.NONE),
		row(104, 106, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(107, 112, PatchCondition.GROWING, FarmingProduce.STRAWBERRY, OFFSET, 107, PatchSubState.NONE),
		row(113, 115, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(116, 123, PatchCondition.GROWING, FarmingProduce.WATERMELON, OFFSET, 116, PatchSubState.NONE),
		row(124, 127, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(128, 134, PatchCondition.GROWING, FarmingProduce.SNAPE_GRASS, OFFSET, 128, PatchSubState.NONE),
		row(135, 137, PatchCondition.DISEASED, FarmingProduce.POTATO, OFFSET, 134, PatchSubState.NONE),
		row(138, 140, PatchCondition.HARVESTABLE, FarmingProduce.SNAPE_GRASS, OFFSET, 138, PatchSubState.NONE),
		row(141, 141, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(142, 144, PatchCondition.DISEASED, FarmingProduce.ONION, OFFSET, 141, PatchSubState.NONE),
		row(145, 148, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(149, 151, PatchCondition.DISEASED, FarmingProduce.CABBAGE, OFFSET, 148, PatchSubState.NONE),
		row(152, 155, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(156, 158, PatchCondition.DISEASED, FarmingProduce.TOMATO, OFFSET, 155, PatchSubState.NONE),
		row(159, 162, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(163, 167, PatchCondition.DISEASED, FarmingProduce.SWEETCORN, OFFSET, 162, PatchSubState.NONE),
		row(168, 171, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(172, 176, PatchCondition.DISEASED, FarmingProduce.STRAWBERRY, OFFSET, 171, PatchSubState.NONE),
		row(177, 180, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(181, 187, PatchCondition.DISEASED, FarmingProduce.WATERMELON, OFFSET, 180, PatchSubState.NONE),
		row(188, 192, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(193, 195, PatchCondition.DEAD, FarmingProduce.SNAPE_GRASS, OFFSET, 192, PatchSubState.NONE),
		row(196, 198, PatchCondition.DISEASED, FarmingProduce.SNAPE_GRASS, OFFSET, 195, PatchSubState.NONE),
		row(199, 201, PatchCondition.DEAD, FarmingProduce.POTATO, OFFSET, 198, PatchSubState.NONE),
		row(202, 204, PatchCondition.DISEASED, FarmingProduce.SNAPE_GRASS, OFFSET, 198, PatchSubState.NONE),
		row(205, 205, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(206, 208, PatchCondition.DEAD, FarmingProduce.ONION, OFFSET, 205, PatchSubState.NONE),
		row(209, 211, PatchCondition.DEAD, FarmingProduce.SNAPE_GRASS, OFFSET, 205, PatchSubState.NONE),
		row(212, 212, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(213, 215, PatchCondition.DEAD, FarmingProduce.CABBAGE, OFFSET, 212, PatchSubState.NONE),
		row(216, 219, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(220, 222, PatchCondition.DEAD, FarmingProduce.TOMATO, OFFSET, 219, PatchSubState.NONE),
		row(223, 226, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(227, 231, PatchCondition.DEAD, FarmingProduce.SWEETCORN, OFFSET, 226, PatchSubState.NONE),
		row(232, 235, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(236, 240, PatchCondition.DEAD, FarmingProduce.STRAWBERRY, OFFSET, 235, PatchSubState.NONE),
		row(241, 244, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(245, 251, PatchCondition.DEAD, FarmingProduce.WATERMELON, OFFSET, 244, PatchSubState.NONE),
		row(252, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] TREE = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 11, PatchCondition.GROWING, FarmingProduce.OAK, OFFSET, 8, PatchSubState.NONE),
		row(12, 12, PatchCondition.CHECK_HEALTH, FarmingProduce.OAK, LAST, 0, PatchSubState.NONE),
		row(13, 13, PatchCondition.HARVESTABLE, FarmingProduce.OAK, CONST, 0, PatchSubState.STANDING),
		row(14, 14, PatchCondition.HARVESTABLE, FarmingProduce.OAK, CONST, 0, PatchSubState.STUMP),
		row(15, 20, PatchCondition.GROWING, FarmingProduce.WILLOW, OFFSET, 15, PatchSubState.NONE),
		row(21, 21, PatchCondition.CHECK_HEALTH, FarmingProduce.WILLOW, LAST, 0, PatchSubState.NONE),
		row(22, 22, PatchCondition.HARVESTABLE, FarmingProduce.WILLOW, CONST, 0, PatchSubState.STANDING),
		row(23, 23, PatchCondition.HARVESTABLE, FarmingProduce.WILLOW, CONST, 0, PatchSubState.STUMP),
		row(24, 31, PatchCondition.GROWING, FarmingProduce.MAPLE, OFFSET, 24, PatchSubState.NONE),
		row(32, 32, PatchCondition.CHECK_HEALTH, FarmingProduce.MAPLE, LAST, 0, PatchSubState.NONE),
		row(33, 33, PatchCondition.HARVESTABLE, FarmingProduce.MAPLE, CONST, 0, PatchSubState.STANDING),
		row(34, 34, PatchCondition.HARVESTABLE, FarmingProduce.MAPLE, CONST, 0, PatchSubState.STUMP),
		row(35, 44, PatchCondition.GROWING, FarmingProduce.YEW, OFFSET, 35, PatchSubState.NONE),
		row(45, 45, PatchCondition.CHECK_HEALTH, FarmingProduce.YEW, LAST, 0, PatchSubState.NONE),
		row(46, 46, PatchCondition.HARVESTABLE, FarmingProduce.YEW, CONST, 0, PatchSubState.STANDING),
		row(47, 47, PatchCondition.HARVESTABLE, FarmingProduce.YEW, CONST, 0, PatchSubState.STUMP),
		row(48, 59, PatchCondition.GROWING, FarmingProduce.MAGIC, OFFSET, 48, PatchSubState.NONE),
		row(60, 60, PatchCondition.CHECK_HEALTH, FarmingProduce.MAGIC, LAST, 0, PatchSubState.NONE),
		row(61, 61, PatchCondition.HARVESTABLE, FarmingProduce.MAGIC, CONST, 0, PatchSubState.STANDING),
		row(62, 62, PatchCondition.HARVESTABLE, FarmingProduce.MAGIC, CONST, 0, PatchSubState.STUMP),
		row(63, 72, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(73, 75, PatchCondition.DISEASED, FarmingProduce.OAK, OFFSET, 72, PatchSubState.NONE),
		row(77, 77, PatchCondition.DISEASED, FarmingProduce.OAK, CONST, 4, PatchSubState.NONE),
		row(78, 79, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(80, 84, PatchCondition.DISEASED, FarmingProduce.WILLOW, OFFSET, 79, PatchSubState.NONE),
		row(86, 86, PatchCondition.DISEASED, FarmingProduce.WILLOW, CONST, 6, PatchSubState.NONE),
		row(87, 88, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(89, 95, PatchCondition.DISEASED, FarmingProduce.MAPLE, OFFSET, 88, PatchSubState.NONE),
		row(97, 97, PatchCondition.DISEASED, FarmingProduce.MAPLE, CONST, 8, PatchSubState.NONE),
		row(98, 99, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(100, 108, PatchCondition.DISEASED, FarmingProduce.YEW, OFFSET, 99, PatchSubState.NONE),
		row(110, 110, PatchCondition.DISEASED, FarmingProduce.YEW, CONST, 10, PatchSubState.NONE),
		row(111, 112, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(113, 123, PatchCondition.DISEASED, FarmingProduce.MAGIC, OFFSET, 112, PatchSubState.NONE),
		row(125, 125, PatchCondition.DISEASED, FarmingProduce.MAGIC, CONST, 12, PatchSubState.NONE),
		row(126, 136, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(137, 139, PatchCondition.DEAD, FarmingProduce.OAK, OFFSET, 136, PatchSubState.NONE),
		row(141, 141, PatchCondition.DEAD, FarmingProduce.OAK, CONST, 4, PatchSubState.NONE),
		row(142, 143, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(144, 148, PatchCondition.DEAD, FarmingProduce.WILLOW, OFFSET, 143, PatchSubState.NONE),
		row(150, 150, PatchCondition.DEAD, FarmingProduce.WILLOW, CONST, 6, PatchSubState.NONE),
		row(151, 152, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(153, 159, PatchCondition.DEAD, FarmingProduce.MAPLE, OFFSET, 152, PatchSubState.NONE),
		row(161, 161, PatchCondition.DEAD, FarmingProduce.MAPLE, CONST, 8, PatchSubState.NONE),
		row(162, 163, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(164, 172, PatchCondition.DEAD, FarmingProduce.YEW, OFFSET, 163, PatchSubState.NONE),
		row(174, 174, PatchCondition.DEAD, FarmingProduce.YEW, CONST, 10, PatchSubState.NONE),
		row(175, 176, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(177, 187, PatchCondition.DEAD, FarmingProduce.MAGIC, OFFSET, 176, PatchSubState.NONE),
		row(189, 189, PatchCondition.DEAD, FarmingProduce.MAGIC, CONST, 12, PatchSubState.NONE),
		row(190, 191, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(192, 197, PatchCondition.HARVESTABLE, FarmingProduce.WILLOW, CONST, 0, PatchSubState.STANDING),
		row(198, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] FLOWER = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 11, PatchCondition.GROWING, FarmingProduce.MARIGOLD, OFFSET, 8, PatchSubState.NONE),
		row(12, 12, PatchCondition.HARVESTABLE, FarmingProduce.MARIGOLD, CONST, 0, PatchSubState.NONE),
		row(13, 16, PatchCondition.GROWING, FarmingProduce.ROSEMARY, OFFSET, 13, PatchSubState.NONE),
		row(17, 17, PatchCondition.HARVESTABLE, FarmingProduce.ROSEMARY, CONST, 0, PatchSubState.NONE),
		row(18, 21, PatchCondition.GROWING, FarmingProduce.NASTURTIUM, OFFSET, 18, PatchSubState.NONE),
		row(22, 22, PatchCondition.HARVESTABLE, FarmingProduce.NASTURTIUM, CONST, 0, PatchSubState.NONE),
		row(23, 26, PatchCondition.GROWING, FarmingProduce.WOAD, OFFSET, 23, PatchSubState.NONE),
		row(27, 27, PatchCondition.HARVESTABLE, FarmingProduce.WOAD, CONST, 0, PatchSubState.NONE),
		row(28, 31, PatchCondition.GROWING, FarmingProduce.LIMPWURT, OFFSET, 28, PatchSubState.NONE),
		row(32, 32, PatchCondition.HARVESTABLE, FarmingProduce.LIMPWURT, CONST, 0, PatchSubState.NONE),
		row(33, 35, PatchCondition.GROWN, FarmingProduce.SCARECROW, REVERSE, 35, PatchSubState.NONE),
		row(36, 36, PatchCondition.GROWN, FarmingProduce.SCARECROW, CONST, 0, PatchSubState.NONE),
		row(37, 40, PatchCondition.GROWING, FarmingProduce.WHITE_LILY, OFFSET, 37, PatchSubState.NONE),
		row(41, 41, PatchCondition.HARVESTABLE, FarmingProduce.WHITE_LILY, CONST, 0, PatchSubState.NONE),
		row(42, 71, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(72, 75, PatchCondition.GROWING, FarmingProduce.MARIGOLD, OFFSET, 72, PatchSubState.NONE),
		row(76, 76, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(77, 80, PatchCondition.GROWING, FarmingProduce.ROSEMARY, OFFSET, 77, PatchSubState.NONE),
		row(81, 81, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(82, 85, PatchCondition.GROWING, FarmingProduce.NASTURTIUM, OFFSET, 82, PatchSubState.NONE),
		row(86, 86, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(87, 90, PatchCondition.GROWING, FarmingProduce.WOAD, OFFSET, 87, PatchSubState.NONE),
		row(91, 91, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(92, 95, PatchCondition.GROWING, FarmingProduce.LIMPWURT, OFFSET, 92, PatchSubState.NONE),
		row(96, 100, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(101, 104, PatchCondition.GROWING, FarmingProduce.WHITE_LILY, OFFSET, 101, PatchSubState.NONE),
		row(105, 136, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(137, 139, PatchCondition.DISEASED, FarmingProduce.MARIGOLD, OFFSET, 136, PatchSubState.NONE),
		row(140, 141, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(142, 144, PatchCondition.DISEASED, FarmingProduce.ROSEMARY, OFFSET, 141, PatchSubState.NONE),
		row(145, 146, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(147, 149, PatchCondition.DISEASED, FarmingProduce.NASTURTIUM, OFFSET, 146, PatchSubState.NONE),
		row(150, 151, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(152, 154, PatchCondition.DISEASED, FarmingProduce.WOAD, OFFSET, 151, PatchSubState.NONE),
		row(155, 156, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(157, 159, PatchCondition.DISEASED, FarmingProduce.LIMPWURT, OFFSET, 156, PatchSubState.NONE),
		row(160, 165, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(166, 168, PatchCondition.DISEASED, FarmingProduce.WHITE_LILY, OFFSET, 165, PatchSubState.NONE),
		row(169, 200, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(201, 204, PatchCondition.DEAD, FarmingProduce.MARIGOLD, OFFSET, 200, PatchSubState.NONE),
		row(205, 205, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(206, 209, PatchCondition.DEAD, FarmingProduce.ROSEMARY, OFFSET, 205, PatchSubState.NONE),
		row(210, 210, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(211, 214, PatchCondition.DEAD, FarmingProduce.NASTURTIUM, OFFSET, 210, PatchSubState.NONE),
		row(215, 215, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(216, 219, PatchCondition.DEAD, FarmingProduce.WOAD, OFFSET, 215, PatchSubState.NONE),
		row(220, 220, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(221, 224, PatchCondition.DEAD, FarmingProduce.LIMPWURT, OFFSET, 220, PatchSubState.NONE),
		row(225, 229, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(230, 233, PatchCondition.DEAD, FarmingProduce.WHITE_LILY, OFFSET, 229, PatchSubState.NONE),
		row(234, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] BUSH = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 4, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(5, 9, PatchCondition.GROWING, FarmingProduce.REDBERRIES, OFFSET, 5, PatchSubState.NONE),
		row(10, 14, PatchCondition.HARVESTABLE, FarmingProduce.REDBERRIES, OFFSET, 10, PatchSubState.NONE),
		row(15, 20, PatchCondition.GROWING, FarmingProduce.CADAVABERRIES, OFFSET, 15, PatchSubState.NONE),
		row(21, 25, PatchCondition.HARVESTABLE, FarmingProduce.CADAVABERRIES, OFFSET, 21, PatchSubState.NONE),
		row(26, 32, PatchCondition.GROWING, FarmingProduce.DWELLBERRIES, OFFSET, 26, PatchSubState.NONE),
		row(33, 37, PatchCondition.HARVESTABLE, FarmingProduce.DWELLBERRIES, OFFSET, 33, PatchSubState.NONE),
		row(38, 45, PatchCondition.GROWING, FarmingProduce.JANGERBERRIES, OFFSET, 38, PatchSubState.NONE),
		row(46, 50, PatchCondition.HARVESTABLE, FarmingProduce.JANGERBERRIES, OFFSET, 46, PatchSubState.NONE),
		row(51, 58, PatchCondition.GROWING, FarmingProduce.WHITEBERRIES, OFFSET, 51, PatchSubState.NONE),
		row(59, 63, PatchCondition.HARVESTABLE, FarmingProduce.WHITEBERRIES, OFFSET, 59, PatchSubState.NONE),
		row(64, 69, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(70, 74, PatchCondition.DISEASED, FarmingProduce.REDBERRIES, OFFSET, 69, PatchSubState.NONE),
		row(75, 79, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(80, 85, PatchCondition.DISEASED, FarmingProduce.CADAVABERRIES, OFFSET, 79, PatchSubState.NONE),
		row(86, 90, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(91, 97, PatchCondition.DISEASED, FarmingProduce.DWELLBERRIES, OFFSET, 90, PatchSubState.NONE),
		row(98, 102, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(103, 110, PatchCondition.DISEASED, FarmingProduce.JANGERBERRIES, OFFSET, 102, PatchSubState.NONE),
		row(111, 115, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(116, 123, PatchCondition.DISEASED, FarmingProduce.WHITEBERRIES, OFFSET, 115, PatchSubState.NONE),
		row(124, 133, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(134, 138, PatchCondition.DEAD, FarmingProduce.REDBERRIES, OFFSET, 133, PatchSubState.NONE),
		row(139, 143, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(144, 149, PatchCondition.DEAD, FarmingProduce.CADAVABERRIES, OFFSET, 143, PatchSubState.NONE),
		row(150, 154, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(155, 161, PatchCondition.DEAD, FarmingProduce.DWELLBERRIES, OFFSET, 154, PatchSubState.NONE),
		row(162, 166, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(167, 174, PatchCondition.DEAD, FarmingProduce.JANGERBERRIES, OFFSET, 166, PatchSubState.NONE),
		row(175, 179, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(180, 187, PatchCondition.DEAD, FarmingProduce.WHITEBERRIES, OFFSET, 179, PatchSubState.NONE),
		row(188, 196, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(197, 204, PatchCondition.GROWING, FarmingProduce.POISON_IVY, OFFSET, 197, PatchSubState.NONE),
		row(205, 209, PatchCondition.HARVESTABLE, FarmingProduce.POISON_IVY, OFFSET, 205, PatchSubState.NONE),
		row(210, 216, PatchCondition.DISEASED, FarmingProduce.POISON_IVY, OFFSET, 209, PatchSubState.NONE),
		row(217, 224, PatchCondition.DEAD, FarmingProduce.POISON_IVY, OFFSET, 216, PatchSubState.NONE),
		row(225, 225, PatchCondition.DISEASED, FarmingProduce.POISON_IVY, CONST, 8, PatchSubState.NONE),
		row(226, 249, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(250, 250, PatchCondition.CHECK_HEALTH, FarmingProduce.REDBERRIES, LAST, 0, PatchSubState.NONE),
		row(251, 251, PatchCondition.CHECK_HEALTH, FarmingProduce.CADAVABERRIES, LAST, 0, PatchSubState.NONE),
		row(252, 252, PatchCondition.CHECK_HEALTH, FarmingProduce.DWELLBERRIES, LAST, 0, PatchSubState.NONE),
		row(253, 253, PatchCondition.CHECK_HEALTH, FarmingProduce.JANGERBERRIES, LAST, 0, PatchSubState.NONE),
		row(254, 254, PatchCondition.CHECK_HEALTH, FarmingProduce.WHITEBERRIES, LAST, 0, PatchSubState.NONE),
		row(255, 255, PatchCondition.CHECK_HEALTH, FarmingProduce.POISON_IVY, LAST, 0, PatchSubState.NONE),
	};

	static final Row[] FRUIT_TREE = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 13, PatchCondition.GROWING, FarmingProduce.APPLE, OFFSET, 8, PatchSubState.NONE),
		row(14, 20, PatchCondition.HARVESTABLE, FarmingProduce.APPLE, OFFSET, 14, PatchSubState.STANDING),
		row(21, 26, PatchCondition.DISEASED, FarmingProduce.APPLE, OFFSET, 20, PatchSubState.NONE),
		row(27, 32, PatchCondition.DEAD, FarmingProduce.APPLE, OFFSET, 26, PatchSubState.NONE),
		row(33, 33, PatchCondition.HARVESTABLE, FarmingProduce.APPLE, CONST, 0, PatchSubState.STUMP),
		row(34, 34, PatchCondition.CHECK_HEALTH, FarmingProduce.APPLE, LAST, 0, PatchSubState.NONE),
		row(35, 40, PatchCondition.GROWING, FarmingProduce.BANANA, OFFSET, 35, PatchSubState.NONE),
		row(41, 47, PatchCondition.HARVESTABLE, FarmingProduce.BANANA, OFFSET, 41, PatchSubState.STANDING),
		row(48, 53, PatchCondition.DISEASED, FarmingProduce.BANANA, OFFSET, 47, PatchSubState.NONE),
		row(54, 59, PatchCondition.DEAD, FarmingProduce.BANANA, OFFSET, 53, PatchSubState.NONE),
		row(60, 60, PatchCondition.HARVESTABLE, FarmingProduce.BANANA, CONST, 0, PatchSubState.STUMP),
		row(61, 61, PatchCondition.CHECK_HEALTH, FarmingProduce.BANANA, LAST, 0, PatchSubState.NONE),
		row(62, 71, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(72, 77, PatchCondition.GROWING, FarmingProduce.ORANGE, OFFSET, 72, PatchSubState.NONE),
		row(78, 84, PatchCondition.HARVESTABLE, FarmingProduce.ORANGE, OFFSET, 78, PatchSubState.STANDING),
		row(85, 89, PatchCondition.DISEASED, FarmingProduce.ORANGE, OFFSET, 84, PatchSubState.NONE),
		row(90, 90, PatchCondition.DISEASED, FarmingProduce.ORANGE, CONST, 6, PatchSubState.NONE),
		row(91, 96, PatchCondition.DEAD, FarmingProduce.ORANGE, OFFSET, 90, PatchSubState.NONE),
		row(97, 97, PatchCondition.HARVESTABLE, FarmingProduce.ORANGE, CONST, 0, PatchSubState.STUMP),
		row(98, 98, PatchCondition.CHECK_HEALTH, FarmingProduce.ORANGE, LAST, 0, PatchSubState.NONE),
		row(99, 104, PatchCondition.GROWING, FarmingProduce.CURRY, OFFSET, 99, PatchSubState.NONE),
		row(105, 111, PatchCondition.HARVESTABLE, FarmingProduce.CURRY, OFFSET, 105, PatchSubState.STANDING),
		row(112, 117, PatchCondition.DISEASED, FarmingProduce.CURRY, OFFSET, 111, PatchSubState.NONE),
		row(118, 123, PatchCondition.DEAD, FarmingProduce.CURRY, OFFSET, 117, PatchSubState.NONE),
		row(124, 124, PatchCondition.HARVESTABLE, FarmingProduce.CURRY, CONST, 0, PatchSubState.STUMP),
		row(125, 125, PatchCondition.CHECK_HEALTH, FarmingProduce.CURRY, LAST, 0, PatchSubState.NONE),
		row(126, 135, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(136, 141, PatchCondition.GROWING, FarmingProduce.PINEAPPLE, OFFSET, 136, PatchSubState.NONE),
		row(142, 148, PatchCondition.HARVESTABLE, FarmingProduce.PINEAPPLE, OFFSET, 142, PatchSubState.STANDING),
		row(149, 154, PatchCondition.DISEASED, FarmingProduce.PINEAPPLE, OFFSET, 148, PatchSubState.NONE),
		row(155, 160, PatchCondition.DEAD, FarmingProduce.PINEAPPLE, OFFSET, 154, PatchSubState.NONE),
		row(161, 161, PatchCondition.HARVESTABLE, FarmingProduce.PINEAPPLE, CONST, 0, PatchSubState.STUMP),
		row(162, 162, PatchCondition.CHECK_HEALTH, FarmingProduce.PINEAPPLE, LAST, 0, PatchSubState.NONE),
		row(163, 168, PatchCondition.GROWING, FarmingProduce.PAPAYA, OFFSET, 163, PatchSubState.NONE),
		row(169, 175, PatchCondition.HARVESTABLE, FarmingProduce.PAPAYA, OFFSET, 169, PatchSubState.STANDING),
		row(176, 181, PatchCondition.DISEASED, FarmingProduce.PAPAYA, OFFSET, 175, PatchSubState.NONE),
		row(182, 187, PatchCondition.DEAD, FarmingProduce.PAPAYA, OFFSET, 181, PatchSubState.NONE),
		row(188, 188, PatchCondition.HARVESTABLE, FarmingProduce.PAPAYA, CONST, 0, PatchSubState.STUMP),
		row(189, 189, PatchCondition.CHECK_HEALTH, FarmingProduce.PAPAYA, LAST, 0, PatchSubState.NONE),
		row(190, 199, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(200, 205, PatchCondition.GROWING, FarmingProduce.PALM, OFFSET, 200, PatchSubState.NONE),
		row(206, 212, PatchCondition.HARVESTABLE, FarmingProduce.PALM, OFFSET, 206, PatchSubState.STANDING),
		row(213, 218, PatchCondition.DISEASED, FarmingProduce.PALM, OFFSET, 212, PatchSubState.NONE),
		row(219, 224, PatchCondition.DEAD, FarmingProduce.PALM, OFFSET, 218, PatchSubState.NONE),
		row(225, 225, PatchCondition.HARVESTABLE, FarmingProduce.PALM, CONST, 0, PatchSubState.STUMP),
		row(226, 226, PatchCondition.CHECK_HEALTH, FarmingProduce.PALM, LAST, 0, PatchSubState.NONE),
		row(227, 232, PatchCondition.GROWING, FarmingProduce.DRAGONFRUIT, OFFSET, 227, PatchSubState.NONE),
		row(233, 239, PatchCondition.HARVESTABLE, FarmingProduce.DRAGONFRUIT, OFFSET, 233, PatchSubState.STANDING),
		row(240, 245, PatchCondition.DISEASED, FarmingProduce.DRAGONFRUIT, OFFSET, 239, PatchSubState.NONE),
		row(246, 251, PatchCondition.DEAD, FarmingProduce.DRAGONFRUIT, OFFSET, 245, PatchSubState.NONE),
		row(252, 252, PatchCondition.HARVESTABLE, FarmingProduce.DRAGONFRUIT, CONST, 0, PatchSubState.STUMP),
		row(253, 253, PatchCondition.CHECK_HEALTH, FarmingProduce.DRAGONFRUIT, LAST, 0, PatchSubState.NONE),
		row(254, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] HOPS = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.GROWING, FarmingProduce.HAMMERSTONE, OFFSET, 4, PatchSubState.NONE),
		row(8, 10, PatchCondition.HARVESTABLE, FarmingProduce.HAMMERSTONE, REVERSE, 10, PatchSubState.NONE),
		row(11, 13, PatchCondition.DISEASED, FarmingProduce.HAMMERSTONE, OFFSET, 10, PatchSubState.NONE),
		row(14, 18, PatchCondition.GROWING, FarmingProduce.ASGARNIAN, OFFSET, 14, PatchSubState.NONE),
		row(19, 21, PatchCondition.HARVESTABLE, FarmingProduce.ASGARNIAN, REVERSE, 21, PatchSubState.NONE),
		row(22, 25, PatchCondition.DISEASED, FarmingProduce.ASGARNIAN, OFFSET, 21, PatchSubState.NONE),
		row(26, 31, PatchCondition.GROWING, FarmingProduce.YANILLIAN, OFFSET, 26, PatchSubState.NONE),
		row(32, 34, PatchCondition.HARVESTABLE, FarmingProduce.YANILLIAN, REVERSE, 34, PatchSubState.NONE),
		row(35, 39, PatchCondition.DISEASED, FarmingProduce.YANILLIAN, OFFSET, 34, PatchSubState.NONE),
		row(40, 46, PatchCondition.GROWING, FarmingProduce.KRANDORIAN, OFFSET, 40, PatchSubState.NONE),
		row(47, 49, PatchCondition.HARVESTABLE, FarmingProduce.KRANDORIAN, REVERSE, 49, PatchSubState.NONE),
		row(50, 55, PatchCondition.DISEASED, FarmingProduce.KRANDORIAN, OFFSET, 49, PatchSubState.NONE),
		row(56, 63, PatchCondition.GROWING, FarmingProduce.WILDBLOOD, OFFSET, 56, PatchSubState.NONE),
		row(64, 66, PatchCondition.HARVESTABLE, FarmingProduce.WILDBLOOD, REVERSE, 66, PatchSubState.NONE),
		row(67, 73, PatchCondition.DISEASED, FarmingProduce.WILDBLOOD, OFFSET, 66, PatchSubState.NONE),
		row(74, 77, PatchCondition.GROWING, FarmingProduce.BARLEY, OFFSET, 74, PatchSubState.NONE),
		row(78, 80, PatchCondition.HARVESTABLE, FarmingProduce.BARLEY, REVERSE, 80, PatchSubState.NONE),
		row(81, 83, PatchCondition.DISEASED, FarmingProduce.BARLEY, OFFSET, 80, PatchSubState.NONE),
		row(84, 88, PatchCondition.GROWING, FarmingProduce.JUTE, OFFSET, 84, PatchSubState.NONE),
		row(89, 91, PatchCondition.HARVESTABLE, FarmingProduce.JUTE, REVERSE, 91, PatchSubState.NONE),
		row(92, 95, PatchCondition.DISEASED, FarmingProduce.JUTE, OFFSET, 91, PatchSubState.NONE),
		row(96, 98, PatchCondition.GROWING, FarmingProduce.FLAX, OFFSET, 96, PatchSubState.NONE),
		row(99, 101, PatchCondition.HARVESTABLE, FarmingProduce.FLAX, REVERSE, 101, PatchSubState.NONE),
		row(102, 103, PatchCondition.DISEASED, FarmingProduce.FLAX, OFFSET, 101, PatchSubState.NONE),
		row(104, 107, PatchCondition.GROWING, FarmingProduce.HEMP, OFFSET, 104, PatchSubState.NONE),
		row(108, 110, PatchCondition.HARVESTABLE, FarmingProduce.HEMP, REVERSE, 110, PatchSubState.NONE),
		row(111, 113, PatchCondition.DISEASED, FarmingProduce.HEMP, OFFSET, 110, PatchSubState.NONE),
		row(114, 118, PatchCondition.GROWING, FarmingProduce.COTTON, OFFSET, 114, PatchSubState.NONE),
		row(119, 121, PatchCondition.HARVESTABLE, FarmingProduce.COTTON, REVERSE, 121, PatchSubState.NONE),
		row(122, 125, PatchCondition.DISEASED, FarmingProduce.COTTON, OFFSET, 121, PatchSubState.NONE),
		row(126, 131, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(132, 135, PatchCondition.GROWING, FarmingProduce.HAMMERSTONE, OFFSET, 132, PatchSubState.NONE),
		row(136, 138, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(139, 141, PatchCondition.DEAD, FarmingProduce.HAMMERSTONE, OFFSET, 138, PatchSubState.NONE),
		row(142, 146, PatchCondition.GROWING, FarmingProduce.ASGARNIAN, OFFSET, 142, PatchSubState.NONE),
		row(147, 149, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(150, 153, PatchCondition.DEAD, FarmingProduce.ASGARNIAN, OFFSET, 149, PatchSubState.NONE),
		row(154, 159, PatchCondition.GROWING, FarmingProduce.YANILLIAN, OFFSET, 154, PatchSubState.NONE),
		row(160, 162, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(163, 167, PatchCondition.DEAD, FarmingProduce.YANILLIAN, OFFSET, 162, PatchSubState.NONE),
		row(168, 174, PatchCondition.GROWING, FarmingProduce.KRANDORIAN, OFFSET, 168, PatchSubState.NONE),
		row(175, 177, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(178, 183, PatchCondition.DEAD, FarmingProduce.KRANDORIAN, OFFSET, 177, PatchSubState.NONE),
		row(184, 191, PatchCondition.GROWING, FarmingProduce.WILDBLOOD, OFFSET, 184, PatchSubState.NONE),
		row(192, 194, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(195, 201, PatchCondition.DEAD, FarmingProduce.WILDBLOOD, OFFSET, 194, PatchSubState.NONE),
		row(202, 205, PatchCondition.GROWING, FarmingProduce.BARLEY, OFFSET, 202, PatchSubState.NONE),
		row(206, 208, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(209, 211, PatchCondition.DEAD, FarmingProduce.BARLEY, OFFSET, 208, PatchSubState.NONE),
		row(212, 216, PatchCondition.GROWING, FarmingProduce.JUTE, OFFSET, 212, PatchSubState.NONE),
		row(217, 219, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(220, 223, PatchCondition.DEAD, FarmingProduce.JUTE, OFFSET, 219, PatchSubState.NONE),
		row(224, 226, PatchCondition.GROWING, FarmingProduce.FLAX, OFFSET, 224, PatchSubState.NONE),
		row(227, 229, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(230, 231, PatchCondition.DEAD, FarmingProduce.FLAX, OFFSET, 229, PatchSubState.NONE),
		row(232, 235, PatchCondition.GROWING, FarmingProduce.HEMP, OFFSET, 232, PatchSubState.NONE),
		row(236, 238, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(239, 240, PatchCondition.DEAD, FarmingProduce.HEMP, OFFSET, 238, PatchSubState.NONE),
		row(241, 241, PatchCondition.DEAD, FarmingProduce.HEMP, CONST, 3, PatchSubState.NONE),
		row(242, 246, PatchCondition.GROWING, FarmingProduce.COTTON, OFFSET, 242, PatchSubState.NONE),
		row(247, 249, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(250, 253, PatchCondition.DEAD, FarmingProduce.COTTON, OFFSET, 249, PatchSubState.NONE),
		row(254, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] HARDWOOD_TREE = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 14, PatchCondition.GROWING, FarmingProduce.TEAK, OFFSET, 8, PatchSubState.NONE),
		row(15, 15, PatchCondition.CHECK_HEALTH, FarmingProduce.TEAK, LAST, 0, PatchSubState.NONE),
		row(16, 16, PatchCondition.HARVESTABLE, FarmingProduce.TEAK, CONST, 0, PatchSubState.STANDING),
		row(17, 17, PatchCondition.HARVESTABLE, FarmingProduce.TEAK, CONST, 0, PatchSubState.STUMP),
		row(18, 23, PatchCondition.DISEASED, FarmingProduce.TEAK, OFFSET, 17, PatchSubState.NONE),
		row(24, 29, PatchCondition.DEAD, FarmingProduce.TEAK, OFFSET, 23, PatchSubState.NONE),
		row(30, 37, PatchCondition.GROWING, FarmingProduce.MAHOGANY, OFFSET, 30, PatchSubState.NONE),
		row(38, 38, PatchCondition.CHECK_HEALTH, FarmingProduce.MAHOGANY, LAST, 0, PatchSubState.NONE),
		row(39, 39, PatchCondition.HARVESTABLE, FarmingProduce.MAHOGANY, CONST, 0, PatchSubState.STANDING),
		row(40, 40, PatchCondition.HARVESTABLE, FarmingProduce.MAHOGANY, CONST, 0, PatchSubState.STUMP),
		row(41, 47, PatchCondition.DISEASED, FarmingProduce.MAHOGANY, OFFSET, 40, PatchSubState.NONE),
		row(48, 54, PatchCondition.DEAD, FarmingProduce.MAHOGANY, OFFSET, 47, PatchSubState.NONE),
		row(55, 62, PatchCondition.GROWING, FarmingProduce.CAMPHOR, OFFSET, 55, PatchSubState.NONE),
		row(63, 63, PatchCondition.CHECK_HEALTH, FarmingProduce.CAMPHOR, LAST, 0, PatchSubState.NONE),
		row(64, 64, PatchCondition.HARVESTABLE, FarmingProduce.CAMPHOR, CONST, 0, PatchSubState.STANDING),
		row(65, 65, PatchCondition.HARVESTABLE, FarmingProduce.CAMPHOR, CONST, 0, PatchSubState.STUMP),
		row(66, 72, PatchCondition.DISEASED, FarmingProduce.CAMPHOR, OFFSET, 65, PatchSubState.NONE),
		row(73, 79, PatchCondition.DEAD, FarmingProduce.CAMPHOR, OFFSET, 72, PatchSubState.NONE),
		row(80, 87, PatchCondition.GROWING, FarmingProduce.IRONWOOD, OFFSET, 80, PatchSubState.NONE),
		row(88, 88, PatchCondition.CHECK_HEALTH, FarmingProduce.IRONWOOD, LAST, 0, PatchSubState.NONE),
		row(89, 89, PatchCondition.HARVESTABLE, FarmingProduce.IRONWOOD, CONST, 0, PatchSubState.STANDING),
		row(90, 90, PatchCondition.HARVESTABLE, FarmingProduce.IRONWOOD, CONST, 0, PatchSubState.STUMP),
		row(91, 97, PatchCondition.DISEASED, FarmingProduce.IRONWOOD, OFFSET, 90, PatchSubState.NONE),
		row(98, 104, PatchCondition.DEAD, FarmingProduce.IRONWOOD, OFFSET, 97, PatchSubState.NONE),
		row(105, 113, PatchCondition.GROWING, FarmingProduce.ROSEWOOD, OFFSET, 105, PatchSubState.NONE),
		row(114, 114, PatchCondition.CHECK_HEALTH, FarmingProduce.ROSEWOOD, LAST, 0, PatchSubState.NONE),
		row(115, 115, PatchCondition.HARVESTABLE, FarmingProduce.ROSEWOOD, CONST, 0, PatchSubState.STANDING),
		row(116, 116, PatchCondition.HARVESTABLE, FarmingProduce.ROSEWOOD, CONST, 0, PatchSubState.STUMP),
		row(117, 124, PatchCondition.DISEASED, FarmingProduce.ROSEWOOD, OFFSET, 116, PatchSubState.NONE),
		row(125, 132, PatchCondition.DEAD, FarmingProduce.ROSEWOOD, OFFSET, 124, PatchSubState.NONE),
		row(133, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] REDWOOD = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 17, PatchCondition.GROWING, FarmingProduce.REDWOOD, OFFSET, 8, PatchSubState.NONE),
		row(18, 18, PatchCondition.HARVESTABLE, FarmingProduce.REDWOOD, CONST, 0, PatchSubState.NONE),
		row(19, 27, PatchCondition.DISEASED, FarmingProduce.REDWOOD, OFFSET, 18, PatchSubState.NONE),
		row(28, 36, PatchCondition.DEAD, FarmingProduce.REDWOOD, OFFSET, 27, PatchSubState.NONE),
		row(37, 37, PatchCondition.CHECK_HEALTH, FarmingProduce.REDWOOD, LAST, 0, PatchSubState.NONE),
		row(41, 55, PatchCondition.HARVESTABLE, FarmingProduce.REDWOOD, CONST, 0, PatchSubState.NONE),
	};

	static final Row[] SPIRIT_TREE = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 19, PatchCondition.GROWING, FarmingProduce.SPIRIT_TREE, OFFSET, 8, PatchSubState.NONE),
		row(20, 20, PatchCondition.GROWN, FarmingProduce.SPIRIT_TREE, CONST, 12, PatchSubState.NONE),
		row(21, 31, PatchCondition.DISEASED, FarmingProduce.SPIRIT_TREE, OFFSET, 20, PatchSubState.NONE),
		row(32, 43, PatchCondition.DEAD, FarmingProduce.SPIRIT_TREE, OFFSET, 31, PatchSubState.NONE),
		row(44, 44, PatchCondition.CHECK_HEALTH, FarmingProduce.SPIRIT_TREE, LAST, 0, PatchSubState.NONE),
		row(45, 63, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] ANIMA = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 16, PatchCondition.GROWING, FarmingProduce.ATTAS, OFFSET, 8, PatchSubState.NONE),
		row(17, 25, PatchCondition.GROWING, FarmingProduce.IASOR, OFFSET, 17, PatchSubState.NONE),
		row(26, 34, PatchCondition.GROWING, FarmingProduce.KRONOS, OFFSET, 26, PatchSubState.NONE),
		row(35, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] BELLADONNA = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.GROWING, FarmingProduce.BELLADONNA, OFFSET, 4, PatchSubState.NONE),
		row(8, 8, PatchCondition.HARVESTABLE, FarmingProduce.BELLADONNA, CONST, 0, PatchSubState.NONE),
		row(9, 11, PatchCondition.DISEASED, FarmingProduce.BELLADONNA, OFFSET, 8, PatchSubState.NONE),
		row(12, 14, PatchCondition.DEAD, FarmingProduce.BELLADONNA, OFFSET, 11, PatchSubState.NONE),
		row(15, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] CACTUS = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 14, PatchCondition.GROWING, FarmingProduce.CACTUS, OFFSET, 8, PatchSubState.NONE),
		row(15, 18, PatchCondition.HARVESTABLE, FarmingProduce.CACTUS, OFFSET, 15, PatchSubState.NONE),
		row(19, 24, PatchCondition.DISEASED, FarmingProduce.CACTUS, OFFSET, 18, PatchSubState.NONE),
		row(25, 30, PatchCondition.DEAD, FarmingProduce.CACTUS, OFFSET, 24, PatchSubState.NONE),
		row(31, 31, PatchCondition.CHECK_HEALTH, FarmingProduce.CACTUS, LAST, 0, PatchSubState.NONE),
		row(32, 38, PatchCondition.GROWING, FarmingProduce.POTATO_CACTUS, OFFSET, 32, PatchSubState.NONE),
		row(39, 45, PatchCondition.HARVESTABLE, FarmingProduce.POTATO_CACTUS, OFFSET, 39, PatchSubState.NONE),
		row(46, 51, PatchCondition.DISEASED, FarmingProduce.POTATO_CACTUS, OFFSET, 45, PatchSubState.NONE),
		row(52, 57, PatchCondition.DEAD, FarmingProduce.POTATO_CACTUS, OFFSET, 51, PatchSubState.NONE),
		row(58, 58, PatchCondition.CHECK_HEALTH, FarmingProduce.POTATO_CACTUS, LAST, 0, PatchSubState.NONE),
		row(59, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] SEAWEED = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.GROWING, FarmingProduce.SEAWEED, OFFSET, 4, PatchSubState.NONE),
		row(8, 10, PatchCondition.HARVESTABLE, FarmingProduce.SEAWEED, OFFSET, 8, PatchSubState.NONE),
		row(11, 13, PatchCondition.DISEASED, FarmingProduce.SEAWEED, OFFSET, 10, PatchSubState.NONE),
		row(14, 16, PatchCondition.DEAD, FarmingProduce.SEAWEED, OFFSET, 13, PatchSubState.NONE),
		row(17, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] CALQUAT = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 11, PatchCondition.GROWING, FarmingProduce.CALQUAT, OFFSET, 4, PatchSubState.NONE),
		row(12, 18, PatchCondition.HARVESTABLE, FarmingProduce.CALQUAT, OFFSET, 12, PatchSubState.NONE),
		row(19, 25, PatchCondition.DISEASED, FarmingProduce.CALQUAT, OFFSET, 18, PatchSubState.NONE),
		row(26, 33, PatchCondition.DEAD, FarmingProduce.CALQUAT, OFFSET, 25, PatchSubState.NONE),
		row(34, 34, PatchCondition.CHECK_HEALTH, FarmingProduce.CALQUAT, LAST, 0, PatchSubState.NONE),
		row(35, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] CELASTRUS = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 7, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
		row(8, 12, PatchCondition.GROWING, FarmingProduce.CELASTRUS, OFFSET, 8, PatchSubState.NONE),
		row(13, 13, PatchCondition.CHECK_HEALTH, FarmingProduce.CELASTRUS, LAST, 0, PatchSubState.NONE),
		row(14, 16, PatchCondition.HARVESTABLE, FarmingProduce.CELASTRUS, OFFSET, 14, PatchSubState.NONE),
		row(17, 17, PatchCondition.HARVESTABLE, FarmingProduce.CELASTRUS, CONST, 0, PatchSubState.STANDING),
		row(18, 22, PatchCondition.DISEASED, FarmingProduce.CELASTRUS, OFFSET, 17, PatchSubState.NONE),
		row(23, 27, PatchCondition.DEAD, FarmingProduce.CELASTRUS, OFFSET, 22, PatchSubState.NONE),
		row(28, 28, PatchCondition.HARVESTABLE, FarmingProduce.CELASTRUS, CONST, 0, PatchSubState.STUMP),
		row(29, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] CRYSTAL_TREE = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(8, 13, PatchCondition.GROWING, FarmingProduce.CRYSTAL_TREE, OFFSET, 8, PatchSubState.NONE),
		row(14, 14, PatchCondition.CHECK_HEALTH, FarmingProduce.CRYSTAL_TREE, LAST, 0, PatchSubState.NONE),
		row(15, 15, PatchCondition.HARVESTABLE, FarmingProduce.CRYSTAL_TREE, CONST, 0, PatchSubState.STANDING),
	};

	static final Row[] MUSHROOM = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 9, PatchCondition.GROWING, FarmingProduce.MUSHROOM, OFFSET, 4, PatchSubState.NONE),
		row(10, 15, PatchCondition.HARVESTABLE, FarmingProduce.MUSHROOM, OFFSET, 10, PatchSubState.NONE),
		row(16, 20, PatchCondition.DISEASED, FarmingProduce.MUSHROOM, OFFSET, 15, PatchSubState.NONE),
		row(21, 25, PatchCondition.DEAD, FarmingProduce.MUSHROOM, OFFSET, 20, PatchSubState.NONE),
		row(26, 255, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};

	static final Row[] HESPORI = {
		row(0, 3, PatchCondition.WEEDS, null, REVERSE, 3, PatchSubState.NONE),
		row(4, 6, PatchCondition.GROWING, FarmingProduce.HESPORI, OFFSET, 4, PatchSubState.NONE),
		row(7, 8, PatchCondition.HARVESTABLE, FarmingProduce.HESPORI, OFFSET, 7, PatchSubState.NONE),
		row(9, 9, PatchCondition.WEEDS, null, CONST, 3, PatchSubState.NONE),
	};
}
