package com.osrstelemetry.plugin.farming;

// GENERATED from RuneLite's farming tables -- do not edit by hand; regenerate instead.
// Derived from net.runelite.client.plugins.timetracking.farming.Produce
// (RuneLite master @ e98399f931c6, BSD 2-Clause; Copyright (c) 2018 Abex; Copyright (c) 2018, NotFoxtrot).
// See THIRD_PARTY_NOTICES.md ("Farming patch tables and capture guards").

/**
 * Every produce the supported patch types can hold, with RuneLite's growth
 * constants: minutes per growth tick, growth stages (the last one is the
 * ready / check-health stage), regrow minutes and harvest stages. Names are
 * persisted (crop cycles), so an existing constant is never renamed.
 * {@code SCARECROW} is the scarecrow a flower patch can hold (RuneLite gives it
 * no patch type).
 */
public enum FarmingProduce
{
	WEEDS("Weeds", null, 5, 4, 0, 1),
	AVANTOE("Avantoe", FarmingPatchType.HERB, 20, 5, 0, 3),
	CADANTINE("Cadantine", FarmingPatchType.HERB, 20, 5, 0, 3),
	DWARF_WEED("Dwarf weed", FarmingPatchType.HERB, 20, 5, 0, 3),
	GOUTWEED("Goutweed", FarmingPatchType.HERB, 20, 5, 0, 2),
	GUAM("Guam", FarmingPatchType.HERB, 20, 5, 0, 3),
	HARRALANDER("Harralander", FarmingPatchType.HERB, 20, 5, 0, 3),
	HUASCA("Huasca", FarmingPatchType.HERB, 20, 5, 0, 3),
	IRIT("Irit", FarmingPatchType.HERB, 20, 5, 0, 3),
	KWUARM("Kwuarm", FarmingPatchType.HERB, 20, 5, 0, 3),
	LANTADYME("Lantadyme", FarmingPatchType.HERB, 20, 5, 0, 3),
	MARRENTILL("Marrentill", FarmingPatchType.HERB, 20, 5, 0, 3),
	RANARR("Ranarr", FarmingPatchType.HERB, 20, 5, 0, 3),
	SNAPDRAGON("Snapdragon", FarmingPatchType.HERB, 20, 5, 0, 3),
	TARROMIN("Tarromin", FarmingPatchType.HERB, 20, 5, 0, 3),
	TOADFLAX("Toadflax", FarmingPatchType.HERB, 20, 5, 0, 3),
	TORSTOL("Torstol", FarmingPatchType.HERB, 20, 5, 0, 3),
	CABBAGE("Cabbage", FarmingPatchType.ALLOTMENT, 10, 5, 0, 3),
	ONION("Onion", FarmingPatchType.ALLOTMENT, 10, 5, 0, 3),
	POTATO("Potato", FarmingPatchType.ALLOTMENT, 10, 5, 0, 3),
	SNAPE_GRASS("Snape grass", FarmingPatchType.ALLOTMENT, 10, 8, 0, 3),
	STRAWBERRY("Strawberry", FarmingPatchType.ALLOTMENT, 10, 7, 0, 3),
	SWEETCORN("Sweetcorn", FarmingPatchType.ALLOTMENT, 10, 7, 0, 3),
	TOMATO("Tomato", FarmingPatchType.ALLOTMENT, 10, 5, 0, 3),
	WATERMELON("Watermelon", FarmingPatchType.ALLOTMENT, 10, 9, 0, 3),
	MAGIC("Magic", FarmingPatchType.TREE, 40, 13, 0, 1),
	MAPLE("Maple", FarmingPatchType.TREE, 40, 9, 0, 1),
	OAK("Oak", FarmingPatchType.TREE, 40, 5, 0, 1),
	WILLOW("Willow", FarmingPatchType.TREE, 40, 7, 0, 1),
	YEW("Yew", FarmingPatchType.TREE, 40, 11, 0, 1),
	LIMPWURT("Limpwurt", FarmingPatchType.FLOWER, 5, 5, 0, 1),
	MARIGOLD("Marigold", FarmingPatchType.FLOWER, 5, 5, 0, 1),
	NASTURTIUM("Nasturtium", FarmingPatchType.FLOWER, 5, 5, 0, 1),
	ROSEMARY("Rosemary", FarmingPatchType.FLOWER, 5, 5, 0, 1),
	SCARECROW("Scarecrow", FarmingPatchType.FLOWER, 5, 4, 0, 1),
	WHITE_LILY("White lily", FarmingPatchType.FLOWER, 5, 5, 0, 1),
	WOAD("Woad", FarmingPatchType.FLOWER, 5, 5, 0, 1),
	CADAVABERRIES("Cadavaberry", FarmingPatchType.BUSH, 20, 7, 20, 5),
	DWELLBERRIES("Dwellberry", FarmingPatchType.BUSH, 20, 8, 20, 5),
	JANGERBERRIES("Jangerberry", FarmingPatchType.BUSH, 20, 9, 20, 5),
	POISON_IVY("Poison ivy", FarmingPatchType.BUSH, 20, 9, 20, 5),
	REDBERRIES("Redberry", FarmingPatchType.BUSH, 20, 6, 20, 5),
	WHITEBERRIES("Whiteberry", FarmingPatchType.BUSH, 20, 9, 20, 5),
	APPLE("Apple", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	BANANA("Banana", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	CURRY("Curry", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	DRAGONFRUIT("Dragonfruit", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	ORANGE("Orange", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	PALM("Palm", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	PAPAYA("Papaya", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	PINEAPPLE("Pineapple", FarmingPatchType.FRUIT_TREE, 160, 7, 45, 7),
	ASGARNIAN("Asgarnian", FarmingPatchType.HOPS, 10, 6, 0, 3),
	BARLEY("Barley", FarmingPatchType.HOPS, 10, 5, 0, 3),
	COTTON("Cotton", FarmingPatchType.HOPS, 20, 6, 0, 3),
	FLAX("Flax", FarmingPatchType.HOPS, 20, 4, 0, 3),
	HAMMERSTONE("Hammerstone", FarmingPatchType.HOPS, 10, 5, 0, 3),
	HEMP("Hemp", FarmingPatchType.HOPS, 20, 5, 0, 3),
	JUTE("Jute", FarmingPatchType.HOPS, 10, 6, 0, 3),
	KRANDORIAN("Krandorian", FarmingPatchType.HOPS, 10, 8, 0, 3),
	WILDBLOOD("Wildblood", FarmingPatchType.HOPS, 10, 9, 0, 3),
	YANILLIAN("Yanillian", FarmingPatchType.HOPS, 10, 7, 0, 3),
	CAMPHOR("Camphor", FarmingPatchType.HARDWOOD_TREE, 640, 9, 0, 1),
	IRONWOOD("Ironwood", FarmingPatchType.HARDWOOD_TREE, 640, 9, 0, 1),
	MAHOGANY("Mahogany", FarmingPatchType.HARDWOOD_TREE, 640, 9, 0, 1),
	ROSEWOOD("Rosewood", FarmingPatchType.HARDWOOD_TREE, 640, 10, 0, 1),
	TEAK("Teak", FarmingPatchType.HARDWOOD_TREE, 640, 8, 0, 1),
	REDWOOD("Redwood", FarmingPatchType.REDWOOD, 640, 11, 0, 1),
	SPIRIT_TREE("Spirit tree", FarmingPatchType.SPIRIT_TREE, 320, 13, 0, 1),
	ATTAS("Attas", FarmingPatchType.ANIMA, 640, 9, 0, 1),
	IASOR("Iasor", FarmingPatchType.ANIMA, 640, 9, 0, 1),
	KRONOS("Kronos", FarmingPatchType.ANIMA, 640, 9, 0, 1),
	BELLADONNA("Belladonna", FarmingPatchType.BELLADONNA, 80, 5, 0, 1),
	CACTUS("Cactus", FarmingPatchType.CACTUS, 80, 8, 20, 4),
	POTATO_CACTUS("Potato cactus", FarmingPatchType.CACTUS, 10, 8, 5, 7),
	SEAWEED("Seaweed", FarmingPatchType.SEAWEED, 10, 5, 0, 4),
	CALQUAT("Calquat", FarmingPatchType.CALQUAT, 160, 9, 0, 7),
	CELASTRUS("Celastrus", FarmingPatchType.CELASTRUS, 160, 6, 0, 4),
	CRYSTAL_TREE("Crystal tree", FarmingPatchType.CRYSTAL_TREE, 80, 7, 0, 1),
	MUSHROOM("Mushroom", FarmingPatchType.MUSHROOM, 40, 7, 0, 7),
	HESPORI("Hespori", FarmingPatchType.HESPORI, 640, 4, 0, 2);

	private final String displayName;
	/** Null for weeds, which grow on every patch type. */
	private final FarmingPatchType patchType;
	private final int tickMinutes;
	private final int stages;
	private final int regrowMinutes;
	private final int harvestStages;

	FarmingProduce(String displayName, FarmingPatchType patchType, int tickMinutes, int stages, int regrowMinutes, int harvestStages)
	{
		this.displayName = displayName;
		this.patchType = patchType;
		this.tickMinutes = tickMinutes;
		this.stages = stages;
		this.regrowMinutes = regrowMinutes;
		this.harvestStages = harvestStages;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public FarmingPatchType getPatchType()
	{
		return patchType;
	}

	/** Minutes between growth ticks while growing. */
	public int getTickMinutes()
	{
		return tickMinutes;
	}

	public int getStages()
	{
		return stages;
	}

	/** Minutes between regrowth ticks while harvestable (berries, fruit), 0 if it does not regrow. */
	public int getRegrowMinutes()
	{
		return regrowMinutes;
	}

	public int getHarvestStages()
	{
		return harvestStages;
	}

	/** The growth stage at which the crop is ready (harvestable, or waiting for its health check). */
	public int getReadyStage()
	{
		return stages - 1;
	}

	/** RuneLite's {@code isHealthCheckRequired()} for this produce's patch type. */
	public boolean requiresHealthCheck()
	{
		return patchType != null && patchType.isHealthCheckRequired();
	}

	/** Seed to fully grown: growth stages minus one, times the tick length. */
	public long fullGrowthMinutes()
	{
		return (long) Math.max(1, stages - 1) * tickMinutes;
	}
}
