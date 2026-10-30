package com.osrstelemetry.plugin.hunter;

import net.runelite.api.gameval.ItemID;

/**
 * Bird house tiers (OSRS Wiki, "Bird house trapping" and each house's item page, read 2026-10-28): made with one log of the
 * tier and one clockwork, a hammer and a chisel, at the Crafting level; placed on a space at the Hunter level. The space
 * variable counts tiers in this order (RuneLite {@code BirdHouse}, BSD-2; the cache's multiloc table agrees).
 */
public enum BirdhouseTier
{
	REGULAR("Bird house", "Regular", ItemID.BIRDHOUSE_NORMAL, ItemID.LOGS, "Logs", 5, 5),
	OAK("Oak bird house", "Oak", ItemID.BIRDHOUSE_OAK, ItemID.OAK_LOGS, "Oak logs", 14, 15),
	WILLOW("Willow bird house", "Willow", ItemID.BIRDHOUSE_WILLOW, ItemID.WILLOW_LOGS, "Willow logs", 24, 25),
	TEAK("Teak bird house", "Teak", ItemID.BIRDHOUSE_TEAK, ItemID.TEAK_LOGS, "Teak logs", 34, 35),
	MAPLE("Maple bird house", "Maple", ItemID.BIRDHOUSE_MAPLE, ItemID.MAPLE_LOGS, "Maple logs", 44, 45),
	MAHOGANY("Mahogany bird house", "Mahogany", ItemID.BIRDHOUSE_MAHOGANY, ItemID.MAHOGANY_LOGS, "Mahogany logs", 49, 50),
	YEW("Yew bird house", "Yew", ItemID.BIRDHOUSE_YEW, ItemID.YEW_LOGS, "Yew logs", 59, 60),
	MAGIC("Magic bird house", "Magic", ItemID.BIRDHOUSE_MAGIC, ItemID.MAGIC_LOGS, "Magic logs", 74, 75),
	REDWOOD("Redwood bird house", "Redwood", ItemID.BIRDHOUSE_REDWOOD, ItemID.REDWOOD_LOGS, "Redwood logs", 89, 90);

	private final String title;
	private final String shortTitle;
	private final int houseItem;
	private final int logItem;
	private final String logTitle;
	private final int hunter;
	private final int crafting;

	BirdhouseTier(String title, String shortTitle, int houseItem, int logItem, String logTitle, int hunter, int crafting)
	{
		this.title = title;
		this.shortTitle = shortTitle;
		this.houseItem = houseItem;
		this.logItem = logItem;
		this.logTitle = logTitle;
		this.hunter = hunter;
		this.crafting = crafting;
	}

	public String getTitle()
	{
		return title;
	}

	public String getShortTitle()
	{
		return shortTitle;
	}

	public int getHouseItem()
	{
		return houseItem;
	}

	public int getLogItem()
	{
		return logItem;
	}

	public String getLogTitle()
	{
		return logTitle;
	}

	/** Hunter level to place it on a space. */
	public int getHunter()
	{
		return hunter;
	}

	/** Crafting level to make it. */
	public int getCrafting()
	{
		return crafting;
	}

	public static BirdhouseTier forHouseItem(int itemId)
	{
		for (BirdhouseTier t : values())
		{
			if (t.houseItem == itemId)
			{
				return t;
			}
		}
		return null;
	}

	public static BirdhouseTier parse(String name)
	{
		try
		{
			return name == null ? null : valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
