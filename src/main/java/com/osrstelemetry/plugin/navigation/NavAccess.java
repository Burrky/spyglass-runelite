package com.osrstelemetry.plugin.navigation;

import com.osrstelemetry.plugin.account.AccountCapability;
import java.util.Map;
import net.runelite.api.QuestState;

/** Which transitions the search may use (canonical §28.10). Implementations are immutable. */
public interface NavAccess
{
	boolean allows(NavRequirement r);

	/** Every transition whose requirement could be parsed (route data generation: requirements are recorded, not checked). */
	NavAccess ANY = r -> r.getUnresolved().isEmpty();

	/** Only transitions with no requirement at all. */
	NavAccess UNGATED = NavRequirement::isNone;

	/**
	 * This account, as observed: skills at level, quests finished, the varbits and varps read, items carried (inventory or
	 * worn). Anything unread or unresolved is not allowed (never assumed).
	 */
	static NavAccess of(AccountCapability cap)
	{
		return r -> {
			if (!r.getUnresolved().isEmpty())
			{
				return false;
			}
			for (NavRequirement.SkillLevel s : r.getSkills())
			{
				Integer lv = cap.level(s.skill);
				if (lv == null || lv < s.level)
				{
					return false;
				}
			}
			for (net.runelite.api.Quest q : r.getQuests())
			{
				if (cap.quest(q) != QuestState.FINISHED)
				{
					return false;
				}
			}
			for (NavRequirement.VarCheck v : r.getVars())
			{
				Integer actual = v.varp ? cap.varp(v.id) : cap.varbit(v.id);
				if (actual == null || !v.test(actual))
				{
					return false;
				}
			}
			for (NavRequirement.ItemNeed need : r.getItems())
			{
				if (!carried(cap, need))
				{
					return false;
				}
			}
			for (String restriction : r.getRestrictions())
			{
				if (!restrictionMet(cap, restriction))
				{
					return false;
				}
			}
			return true;
		};
	}

	/**
	 * Planning from the bank: as {@link #of} for skills, quests and unlocks, while items a walk needs (a rope, coins for a
	 * boat) count as things to bring rather than as a lock.
	 */
	static NavAccess planning(AccountCapability cap)
	{
		NavAccess live = of(cap);
		return r -> live.allows(r.withoutItems().withoutRestrictions());
	}

	/**
	 * What the account could use once everything unread is read (canonical §28.11): as {@link #planning}, except that a
	 * skill, quest or unlock not read yet is allowed. Never used to guide; only to tell "closed" (no way even then) from
	 * "unknown" (a way exists that depends on something unread). Unresolved requirement data stays closed.
	 */
	static NavAccess possible(AccountCapability cap)
	{
		return r -> {
			if (!r.getUnresolved().isEmpty())
			{
				return false;
			}
			for (NavRequirement.SkillLevel s : r.getSkills())
			{
				Integer lv = cap.level(s.skill);
				if (lv != null && lv < s.level)
				{
					return false;
				}
			}
			for (net.runelite.api.Quest q : r.getQuests())
			{
				QuestState st = cap.quest(q);
				if (st != null && st != QuestState.FINISHED)
				{
					return false;
				}
			}
			for (NavRequirement.VarCheck v : r.getVars())
			{
				Integer actual = v.varp ? cap.varp(v.id) : cap.varbit(v.id);
				if (actual != null && !v.test(actual))
				{
					return false;
				}
			}
			return true;
		};
	}

	/** Whether the need is met by what is carried or worn now; a quantity of 0 needs none carried. Unread containers: not met. */
	static boolean carried(AccountCapability cap, NavRequirement.ItemNeed need)
	{
		if (cap.getInventory() == null || cap.getWorn() == null)
		{
			return false;
		}
		for (int i = 0; i < need.optionIds.size(); i++)
		{
			int have = 0;
			for (int id : need.optionIds.get(i))
			{
				have += count(cap.getInventory(), id) + count(cap.getWorn(), id);
			}
			int q = need.optionQuantities.get(i);
			if (q == 0 ? have == 0 : have >= q)
			{
				return true;
			}
		}
		return false;
	}

	/** Equipment slots the monks of Entrana refuse: head, weapon, body, shield, legs, hands, feet. */
	int[] ENTRANA_SLOTS = {0, 3, 4, 5, 7, 9, 10};

	/** A restriction checked live: Entrana's from the worn slots (unread: not met). */
	static boolean restrictionMet(AccountCapability cap, String restriction)
	{
		if (NavRequirement.BALLOON_WEIGHT.equals(restriction))
		{
			// The game's own weight (worn weight-reducing clothing already counted); unread: not taken.
			Integer kg = cap.getWeight();
			return kg != null && kg <= NavRequirement.BALLOON_MAX_KG;
		}
		if (NavRequirement.ENTRANA.equals(restriction))
		{
			java.util.Set<Integer> slots = cap.getWornSlots();
			if (slots == null)
			{
				return false;
			}
			for (int s : ENTRANA_SLOTS)
			{
				if (slots.contains(s))
				{
					return false;
				}
			}
			return true;
		}
		return false;
	}

	static int count(Map<Integer, Integer> m, int id)
	{
		Integer n = m == null ? null : m.get(id);
		return n == null ? 0 : n;
	}

	/** Whether a search may take this transition (by default: whether its requirement is allowed). */
	default boolean allowsTransition(Transition t)
	{
		return allows(t.requirement);
	}

	/** Both must allow it. */
	default NavAccess and(NavAccess other)
	{
		return r -> allows(r) && other.allows(r);
	}
}
