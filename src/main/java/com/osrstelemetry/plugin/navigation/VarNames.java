package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain words for a walk's requirement (canonical §28.11), for the player: "66 Agility", "Twilight's Promise", "the quetzal
 * landing at Kastori built". Varbits and varps on Farming routes are named from the transport tables' own section notes;
 * any other is "an unlock Spyglass hasn't seen on this account". Never shows ids or operators.
 */
public final class VarNames
{
	private static final Map<String, String> NAMES = new HashMap<>();

	static
	{
		// Shortest Path quetzals.tsv / quetzal_whistle.tsv: "Destinations requiring platform to be built (VarPlayer 4182 bits)".
		NAMES.put("p4182&16384", "the quetzal landing at Kastori built");
		NAMES.put("p4182&256", "the quetzal landing at the Fortis Colosseum built");
		NAMES.put("p4182&128", "the quetzal landing at Outer Fortis built");
		NAMES.put("p4182&64", "the quetzal landing at the Colossal Wyrm Remains built");
		NAMES.put("p4182&32", "the quetzal landing at Cam Torum built");
		NAMES.put("p4182&2048", "the quetzal landing at the Salvager Overlook built");
		// boats.tsv "Angler's Retreat Boat" (Sailing rowboat amenity): OSRS Wiki "Anglers' Retreat": the rowboat south-west of
		// Corsair Cove "requires first having built the rowboat on Anglers' Retreat".
		NAMES.put("b18355=1", "the Anglers' Retreat rowboat built");
		// hot_air_balloons.tsv (Shortest Path) section names; OSRS Wiki "Balloon transport system": Entrana and Taverley come
		// with Enlightened Journey, the other four are unlocked by a first flight from Entrana.
		NAMES.put("b2867=2", "Enlightened Journey (the Entrana balloon)");
		NAMES.put("b2868=2", "Enlightened Journey (the Taverley balloon)");
		NAMES.put("b2871=1", "the Crafting Guild balloon route unlocked");
		NAMES.put("b2872=1", "the Varrock balloon route unlocked");
		NAMES.put("b2869=1", "the Castle Wars balloon route unlocked");
		NAMES.put("b2870=1", "the Grand Tree balloon route unlocked");
		// transports.tsv "Tree Gnome Village": following Elkoy through the maze needs Tree Gnome Village started.
		NAMES.put("p111>0", "Tree Gnome Village started");
	}

	private VarNames()
	{
	}

	/** The player's words for one unlock check, or null when Spyglass has no name for it. */
	public static String name(NavRequirement.VarCheck v)
	{
		return NAMES.get((v.varp ? "p" : "b") + v.id + v.op + v.value);
	}

	/**
	 * Only what keeps this account off the walk (canonical §28.11): skills below the level or not read, quests not finished,
	 * unlocks not set or not read. Items and restrictions are left out (they are things to bring or leave, not locks).
	 * Empty when nothing the account lacks is named (the caller then says nothing rather than listing what is already met).
	 */
	public static String blocking(NavRequirement r, com.osrstelemetry.plugin.account.AccountCapability cap)
	{
		List<String> parts = new ArrayList<>();
		for (NavRequirement.SkillLevel s : r.getSkills())
		{
			Integer lv = cap == null ? null : cap.level(s.skill);
			if (lv == null || lv < s.level)
			{
				parts.add(s.toString());
			}
		}
		for (net.runelite.api.Quest q : r.getQuests())
		{
			if (cap == null || cap.quest(q) != net.runelite.api.QuestState.FINISHED)
			{
				parts.add(q.getName());
			}
		}
		boolean unknown = false;
		for (NavRequirement.VarCheck v : r.getVars())
		{
			Integer actual = cap == null ? null : v.varp ? cap.varp(v.id) : cap.varbit(v.id);
			if (actual != null && v.test(actual))
			{
				continue;
			}
			String n = name(v);
			if (n != null)
			{
				parts.add(actual == null ? n + " (not seen yet)" : n);
			}
			else
			{
				unknown = true;
			}
		}
		if (unknown)
		{
			parts.add("an unlock Spyglass hasn't seen on this account");
		}
		if (!r.getUnresolved().isEmpty())
		{
			parts.add("something Spyglass can't check");
		}
		return String.join("; ", parts);
	}

	/** "66 Agility; Twilight's Promise; the quetzal landing at Kastori built" (empty for none). */
	public static String describe(NavRequirement r)
	{
		List<String> parts = new ArrayList<>();
		r.getSkills().forEach(s -> parts.add(s.toString()));
		r.getQuests().forEach(q -> parts.add(q.getName()));
		for (NavRequirement.ItemNeed i : r.getItems())
		{
			parts.add(i.optionQuantities.stream().allMatch(q -> q == 0) ? "certain items left behind" : "an item carried");
		}
		boolean unknown = false;
		for (NavRequirement.VarCheck v : r.getVars())
		{
			String n = NAMES.get((v.varp ? "p" : "b") + v.id + v.op + v.value);
			if (n != null)
			{
				parts.add(n);
			}
			else
			{
				unknown = true;
			}
		}
		if (unknown)
		{
			parts.add("an unlock Spyglass hasn't seen on this account");
		}
		r.getRestrictions().forEach(parts::add);
		if (!r.getUnresolved().isEmpty())
		{
			parts.add("something Spyglass can't check");
		}
		return String.join("; ", parts);
	}
}
