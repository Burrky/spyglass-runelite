package com.osrstelemetry.plugin.home;

import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * The daily claims Spyglass tracks (canonical Home §11), each read from the game's own variables: the unlock (an achievement
 * diary tier, a quest) and the claim. A claim variable of 0 means "not claimed today"; the game clears it at the daily reset
 * (00:00 UTC). Two have several uses a day: Robin's bonemeal and slime (13 per Morytania diary tier from medium) and the
 * Nightmare Zone herb boxes (15 a day, 9,500 reward points each, not for ironmen). Pass 29: Elnock Inquisitor's impling jars,
 * bought through his dialogue: 10 a day at 2,000 coins each, restocked at 00:00 UTC (OSRS Wiki, Elnock Inquisitor); counted
 * by {@code ii_daily_jars_purchased} (varp {@code ii_player} bits 27–30; the same varp's {@code ii_elnex} is read by the
 * game's own scripts, so it is sent to the client). Distinct from trading caught implings to him.
 *
 * <p>Source of the variables and gates: RuneLite's Daily Task Indicator plugin (master, fetched 2026-10-28, BSD-2;
 * {@code DailyTasksPlugin}), re-implemented here on Spyglass's own reads: no other plugin's state is used. Variable ids are
 * RuneLite gameval 1.13.1 names.
 */
public enum DailyTask
{
	BATTLESTAVES("Battlestaves", "Zaff, Varrock", "Varrock easy diary", VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1, VarbitID.ZAFF_LAST_CLAIMED, 0, ItemID.BATTLESTAFF),
	ESSENCE("Pure essence", "Wizard Cromperty, East Ardougne", "Ardougne medium diary", VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, 1,
		VarbitID.ARDOUGNE_FREE_ESSENCE, 0, ItemID.BLANKRUNE_HIGH),
	RUNES("Random runes", "Lundail, Mage Arena bank", "Wilderness easy diary", VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, 1,
		VarbitID.LUNDAIL_LAST_CLAIMED, 0, ItemID.FIRERUNE),
	SAND("Buckets of sand", "Bert, Yanille", "The Hand in the Sand", VarbitID.HANDSAND_QUEST, 160, VarbitID.YANILLE_SAND_CLAIMED, 0, ItemID.BUCKET_SAND),
	FLAX("Flax to bowstrings", "Flax keeper, Seers' Village", "Kandarin easy diary", VarbitID.KANDARIN_DIARY_EASY_COMPLETE, 1,
		VarbitID.SEERS_FREE_FLAX, 0, ItemID.BOW_STRING),
	OGRE_ARROWS("Ogre arrows", "Rantz, Feldip Hills", "Western Provinces easy diary", VarbitID.WESTERN_DIARY_EASY_COMPLETE, 1,
		VarbitID.WESTERN_RANTZ_ARROWS, 0, ItemID.OGRE_ARROW),
	BONEMEAL("Bonemeal & slime", "Robin, Morytania", "Morytania medium diary", VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, 1,
		VarbitID.MORYTANIA_SLIME_CLAIMED, 13, ItemID.POT_BONEMEAL),
	DYNAMITE("Dynamite", "Thirus, Lovakengj", "Kourend & Kebos medium diary", VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, 1,
		VarbitID.KOUREND_FREE_DYNAMITE, 0, ItemID.LOVAKENGJ_DYNAMITE_FUSED),
	HERB_BOXES("Herb boxes", "Nightmare Zone rewards", "9,500 Nightmare Zone points; not for ironmen", -1, 0,
		VarbitID.NZONE_HERBBOXES_PURCHASED, 15, ItemID.NZONE_HERBBOX),
	IMPLING_JARS("Impling jars", "Elnock Inquisitor, Puro-Puro", "Members", -1, 0, VarbitID.II_DAILY_JARS_PURCHASED, 10, ItemID.II_IMPLING_JAR);

	/** Robin's uses per Morytania diary tier (medium, hard, elite). */
	public static final int BONEMEAL_PER_TIER = 13;
	public static final int HERB_BOX_COST = 9_500;

	private final String title;
	private final String where;
	private final String unlock;
	private final int gateVarbit;
	private final int gateMin;
	private final int claimVarbit;
	/** Uses a day for a counted claim (0: one claim a day). */
	private final int uses;
	/** The item the card and Home show for it (what it gives; pass 31). */
	private final int icon;

	DailyTask(String title, String where, String unlock, int gateVarbit, int gateMin, int claimVarbit, int uses, int icon)
	{
		this.icon = icon;
		this.title = title;
		this.where = where;
		this.unlock = unlock;
		this.gateVarbit = gateVarbit;
		this.gateMin = gateMin;
		this.claimVarbit = claimVarbit;
		this.uses = uses;
	}

	public String getTitle()
	{
		return title;
	}

	/** The item shown for it (what the claim gives). */
	public int getIconItem()
	{
		return icon;
	}

	/** Who and where ("Zaff, Varrock"). */
	public String getWhere()
	{
		return where;
	}

	/** What unlocks it ("Varrock easy diary"). */
	public String getUnlock()
	{
		return unlock;
	}

	/** The unlock's variable (-1: none; the herb boxes' rule is the ironman type, the jars need only a members' world). */
	public int getGateVarbit()
	{
		return gateVarbit;
	}

	public int getGateMin()
	{
		return gateMin;
	}

	public int getClaimVarbit()
	{
		return claimVarbit;
	}

	/** Several uses a day (the claim variable counts them). */
	public boolean isCounted()
	{
		return uses > 0;
	}

	public int getUsesPerTier()
	{
		return uses;
	}

	static DailyTask parse(String name)
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
