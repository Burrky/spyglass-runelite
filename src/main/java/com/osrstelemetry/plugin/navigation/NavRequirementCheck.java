package com.osrstelemetry.plugin.navigation;

import com.osrstelemetry.plugin.account.AccountCapability;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.QuestState;

/** A walk's requirement against one account: what is met, unmet, or not known yet (never assumed). Immutable. */
public final class NavRequirementCheck
{
	private final List<String> met;
	private final List<String> unmet;
	private final List<String> missing;

	private NavRequirementCheck(List<String> met, List<String> unmet, List<String> missing)
	{
		this.met = Collections.unmodifiableList(met);
		this.unmet = Collections.unmodifiableList(unmet);
		this.missing = Collections.unmodifiableList(missing);
	}

	public List<String> getMet()
	{
		return met;
	}

	public List<String> getUnmet()
	{
		return unmet;
	}

	public List<String> getMissing()
	{
		return missing;
	}

	public static NavRequirementCheck check(NavRequirement r, AccountCapability cap)
	{
		List<String> met = new ArrayList<>();
		List<String> unmet = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String u : r.getUnresolved())
		{
			unmet.add("the walk needs something Spyglass can't check (" + u + ")");
		}
		for (NavRequirement.SkillLevel s : r.getSkills())
		{
			Integer lv = cap.level(s.skill);
			String text = "the walk needs " + s.level + " " + s.skill.getName();
			if (lv == null)
			{
				missing.add(text + " (level not read yet)");
			}
			else if (lv < s.level)
			{
				unmet.add(text);
			}
			else
			{
				met.add(text);
			}
		}
		for (net.runelite.api.Quest q : r.getQuests())
		{
			QuestState st = cap.quest(q);
			String text = "the walk needs " + q.getName();
			if (st == null)
			{
				missing.add(text + " (quest not read yet)");
			}
			else if (st != QuestState.FINISHED)
			{
				unmet.add(text);
			}
			else
			{
				met.add(text);
			}
		}
		for (NavRequirement.VarCheck v : r.getVars())
		{
			Integer actual = v.varp ? cap.varp(v.id) : cap.varbit(v.id);
			String named = VarNames.name(v);
			String text = named != null ? "the walk needs " + named : "the walk needs an unlock Spyglass hasn't seen on this account";
			if (actual == null)
			{
				missing.add(named != null ? text + " (not read yet)" : text);
			}
			else if (!v.test(actual))
			{
				unmet.add(text);
			}
			else
			{
				met.add(text);
			}
		}
		for (NavRequirement.ItemNeed i : r.getItems())
		{
			// An item a walk needs is something to bring, not a lock: preparation lists the journey, guidance checks it live.
			met.add((NavAccess.carried(cap, i) ? "carried for the walk: " : "bring for the walk: ") + i);
		}
		return new NavRequirementCheck(met, unmet, missing);
	}
}
