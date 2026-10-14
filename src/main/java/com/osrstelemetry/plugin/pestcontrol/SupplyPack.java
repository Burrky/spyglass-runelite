package com.osrstelemetry.plugin.pestcontrol;

/**
 * Pest Control reward-shop resource packs (OSRS Wiki "Void Knights' Reward
 * Options" and "Seed pack (Pest Control)", checked 2026-10-02):
 * <ul>
 * <li>Herb pack: 30 points, 25 Herblore. Always all six grimy herbs
 * (harralander 2-4, ranarr 2-3, toadflax 1-4, irit 2-4, avantoe 1-4,
 * kwuarm 1-5) -- the AMOUNTS are random with no published distribution,
 * so Spyglass never states an expected yield.</li>
 * <li>Seed pack: 15 points, 25 Farming. Three weighted rolls -- random,
 * no yield is stated.</li>
 * <li>Mineral pack: 15 points, 25 Mining. FIXED contents: 25 noted coal
 * and 18 noted iron ore.</li>
 * </ul>
 */
public enum SupplyPack
{
	HERB("Herb pack", "Herb", 30, 0, 0),
	SEED("Seed pack", "Seed", 15, 0, 0),
	MINERAL("Mineral pack", "Mineral", 15, 25, 18);

	private final String itemName;
	private final String shortName;
	private final int cost;
	private final int coalPerPack;
	private final int ironOrePerPack;

	SupplyPack(String itemName, String shortName, int cost, int coalPerPack, int ironOrePerPack)
	{
		this.itemName = itemName;
		this.shortName = shortName;
		this.cost = cost;
		this.coalPerPack = coalPerPack;
		this.ironOrePerPack = ironOrePerPack;
	}

	public String getItemName()
	{
		return itemName;
	}

	public String getShortName()
	{
		return shortName;
	}

	public int getCost()
	{
		return cost;
	}

	/** True only when the pack's contents are fixed (exact output can be stated). */
	public boolean hasFixedContents()
	{
		return coalPerPack > 0 || ironOrePerPack > 0;
	}

	public int getCoalPerPack()
	{
		return coalPerPack;
	}

	public int getIronOrePerPack()
	{
		return ironOrePerPack;
	}

	@Override
	public String toString()
	{
		return itemName;
	}
}
