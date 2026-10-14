package com.osrstelemetry.plugin.pestcontrol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * Pest Control Void Knight equipment, with the CURRENT reward-shop costs
 * (OSRS Wiki "Void Knights' Reward Options", "Void Knight equipment" and
 * each item's own page, checked 2026-10-02):
 * <ul>
 * <li>Void knight top 250, robe 250, gloves 150, mace 250, each helm 200
 * (42 Attack / Strength / Defence / Hitpoints / Ranged / Magic, 22 Prayer);</li>
 * <li>Elite void top / robe: 200 points each, upgraded FROM the normal top /
 * robe (which is consumed), needs the Hard Western Provinces Diary.</li>
 * </ul>
 * Ownership counts every variant of a piece the player can wear or get
 * back -- native gameval item ids only:
 * <ul>
 * <li>the base item;</li>
 * <li>Wilderness "broken" and trouver-locked "trouver" / "trouver broken" /
 * "trouver mangled" forms;</li>
 * <li>the "(or)" ornament-kit forms (gameval LEAGUE_3_VOID_*, the Void
 * ornament kit from the Leagues III reward shop, usable on main accounts --
 * live-verified 2026-10-02: "Elite void top (or)" 26469, "Elite void robe
 * (or)" 26471, "Void knight gloves (or)" 26467 and the three "(or)" helms
 * 26473 / 26475 / 26477 in Spyglass's own equipment / bank state) and
 * their trouver-locked forms (27000-27007).</li>
 * </ul>
 * Excluded: DUMMY_PEST_VOID_KNIGHT_TOP (10611, not obtainable), the
 * ornament kit item itself (26479), placeholders / noted ids.
 * <p>
 * SUPERSESSION: a piece SATISFIES its own requirement and every piece it is
 * upgraded from (an elite top satisfies "Void knight top") -- see
 * {@link #satisfies}.
 */
public enum VoidPiece
{
	TOP("Void knight top", "Top", 250, null,
		ItemID.PEST_VOID_KNIGHT_TOP, ItemID.PEST_VOID_KNIGHT_TOP_BROKEN, ItemID.PEST_VOID_KNIGHT_TOP_TROUVER,
		ItemID.PEST_VOID_KNIGHT_TOP_TROUVER_BROKEN, ItemID.PEST_VOID_KNIGHT_TOP_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_KNIGHT_TOP, ItemID.LEAGUE_3_VOID_KNIGHT_TOP_TROUVER),
	ROBE("Void knight robe", "Robe", 250, null,
		ItemID.PEST_VOID_KNIGHT_ROBES, ItemID.PEST_VOID_KNIGHT_ROBES_BROKEN, ItemID.PEST_VOID_KNIGHT_ROBES_TROUVER,
		ItemID.PEST_VOID_KNIGHT_ROBES_TROUVER_BROKEN, ItemID.PEST_VOID_KNIGHT_ROBES_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_KNIGHT_ROBES, ItemID.LEAGUE_3_VOID_KNIGHT_ROBES_TROUVER),
	GLOVES("Void knight gloves", "Gloves", 150, null,
		ItemID.PEST_VOID_KNIGHT_GLOVES, ItemID.PEST_VOID_KNIGHT_GLOVES_BROKEN, ItemID.PEST_VOID_KNIGHT_GLOVES_TROUVER,
		ItemID.PEST_VOID_KNIGHT_GLOVES_TROUVER_BROKEN, ItemID.PEST_VOID_KNIGHT_GLOVES_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_KNIGHT_GLOVES, ItemID.LEAGUE_3_VOID_KNIGHT_GLOVES_TROUVER),
	MELEE_HELM("Void melee helm", "Melee helm", 200, null,
		ItemID.GAME_PEST_MELEE_HELM, ItemID.GAME_PEST_MELEE_HELM_BROKEN, ItemID.GAME_PEST_MELEE_HELM_TROUVER,
		ItemID.GAME_PEST_MELEE_HELM_TROUVER_BROKEN, ItemID.GAME_PEST_MELEE_HELM_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_MELEE_HELM, ItemID.LEAGUE_3_VOID_MELEE_HELM_TROUVER),
	RANGER_HELM("Void ranger helm", "Ranger helm", 200, null,
		ItemID.GAME_PEST_ARCHER_HELM, ItemID.GAME_PEST_ARCHER_HELM_BROKEN, ItemID.GAME_PEST_ARCHER_HELM_TROUVER,
		ItemID.GAME_PEST_ARCHER_HELM_TROUVER_BROKEN, ItemID.GAME_PEST_ARCHER_HELM_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_RANGE_HELM, ItemID.LEAGUE_3_VOID_RANGE_HELM_TROUVER),
	MAGE_HELM("Void mage helm", "Mage helm", 200, null,
		ItemID.GAME_PEST_MAGE_HELM, ItemID.GAME_PEST_MAGE_HELM_BROKEN, ItemID.GAME_PEST_MAGE_HELM_TROUVER,
		ItemID.GAME_PEST_MAGE_HELM_TROUVER_BROKEN, ItemID.GAME_PEST_MAGE_HELM_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_MAGE_HELM, ItemID.LEAGUE_3_VOID_MAGE_HELM_TROUVER),
	MACE("Void knight mace", "Mace", 250, null,
		ItemID.PEST_VOID_KNIGHT_MACE, ItemID.PEST_VOID_KNIGHT_MACE_BROKEN, ItemID.PEST_VOID_KNIGHT_MACE_TROUVER),
	ELITE_TOP("Elite void top", "Elite top", 200, "TOP",
		ItemID.ELITE_VOID_KNIGHT_TOP, ItemID.ELITE_VOID_KNIGHT_TOP_BROKEN, ItemID.ELITE_VOID_KNIGHT_TOP_TROUVER,
		ItemID.ELITE_VOID_KNIGHT_TOP_TROUVER_BROKEN, ItemID.ELITE_VOID_KNIGHT_TOP_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_KNIGHT_TOP_ELITE, ItemID.LEAGUE_3_VOID_KNIGHT_TOP_ELITE_TROUVER),
	ELITE_ROBE("Elite void robe", "Elite robe", 200, "ROBE",
		ItemID.ELITE_VOID_KNIGHT_ROBES, ItemID.ELITE_VOID_KNIGHT_ROBES_BROKEN, ItemID.ELITE_VOID_KNIGHT_ROBES_TROUVER,
		ItemID.ELITE_VOID_KNIGHT_ROBES_TROUVER_BROKEN, ItemID.ELITE_VOID_KNIGHT_ROBES_TROUVER_MANGLED,
		ItemID.LEAGUE_3_VOID_KNIGHT_ROBES_ELITE, ItemID.LEAGUE_3_VOID_KNIGHT_ROBES_ELITE_TROUVER);

	private final String itemName;
	private final String shortName;
	/** Commendation points for THIS step (for an elite piece: the upgrade only). */
	private final int cost;
	private final String upgradesFrom;
	private final Set<Integer> itemIds;

	VoidPiece(String itemName, String shortName, int cost, String upgradesFrom, int... itemIds)
	{
		this.itemName = itemName;
		this.shortName = shortName;
		this.cost = cost;
		this.upgradesFrom = upgradesFrom;
		Set<Integer> ids = new LinkedHashSet<>();
		for (int id : itemIds)
		{
			ids.add(id);
		}
		this.itemIds = Collections.unmodifiableSet(ids);
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

	/** The standard piece an elite piece is upgraded from (consumed), or null. */
	public VoidPiece getUpgradesFrom()
	{
		return upgradesFrom == null ? null : VoidPiece.valueOf(upgradesFrom);
	}

	public Set<Integer> getItemIds()
	{
		return itemIds;
	}

	public boolean isElite()
	{
		return upgradesFrom != null;
	}

	/**
	 * Whether owning this piece fulfils {@code requirement}: the piece itself,
	 * or any piece it supersedes along its upgrade chain (an elite top
	 * fulfils "Void knight top"). Never the reverse, never across slots /
	 * helm styles.
	 */
	public boolean satisfies(VoidPiece requirement)
	{
		for (VoidPiece p = this; p != null; p = p.getUpgradesFrom())
		{
			if (p == requirement)
			{
				return true;
			}
		}
		return false;
	}

	/** Standard (non-elite) pieces, in the deterministic "next missing" order: body, legs, gloves, helms, mace. */
	public static final java.util.List<VoidPiece> STANDARD = Collections.unmodifiableList(Arrays.asList(
		TOP, ROBE, GLOVES, MELEE_HELM, RANGER_HELM, MAGE_HELM, MACE));

	/** The piece this item id is (any wearable variant), or null. */
	public static VoidPiece ofItem(int itemId)
	{
		for (VoidPiece p : values())
		{
			if (p.itemIds.contains(itemId))
			{
				return p;
			}
		}
		return null;
	}
}
