package com.osrstelemetry.plugin.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * What a {@link Transition} needs (canonical §28.10), parsed from the Shortest Path TSV grammar (BSD-2; see
 * THIRD_PARTY_NOTICES.md): skills ({@code 70 Agility;50 Strength}), quests finished ({@code Dragon Slayer I;...}), items
 * ({@code A=1&B=2}, {@code A=1|B=1}; names from {@code item-variations.tsv}, or raw ids), varbits and varps
 * ({@code id=v}, {@code id>v}, {@code id<v}, {@code id&mask}; {@code ;} separated). Anything that cannot be parsed or mapped
 * (an unknown quest name or item name, an unlock token, a cooldown check) is kept as {@link #getUnresolved()} and makes the
 * transition unusable: Spyglass never assumes a gate is open.
 *
 * <p>Immutable. Value-equal requirements are {@link #equals equal}.
 */
public final class NavRequirement
{
	public static final NavRequirement NONE = new NavRequirement(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
		Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

	/** A skill level. */
	public static final class SkillLevel
	{
		public final Skill skill;
		public final int level;

		SkillLevel(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof SkillLevel && ((SkillLevel) o).skill == skill && ((SkillLevel) o).level == level;
		}

		@Override
		public int hashCode()
		{
			return skill.hashCode() * 31 + level;
		}

		@Override
		public String toString()
		{
			return level + " " + skill.getName();
		}
	}

	/** A variable check: varbit or varp {@code id op value}. */
	public static final class VarCheck
	{
		public final boolean varp;
		public final int id;
		public final char op;
		public final int value;

		VarCheck(boolean varp, int id, char op, int value)
		{
			this.varp = varp;
			this.id = id;
			this.op = op;
			this.value = value;
		}

		public boolean test(int actual)
		{
			switch (op)
			{
				case '=':
					return actual == value;
				case '>':
					return actual > value;
				case '<':
					return actual < value;
				case '&':
					return (actual & value) != 0;
				default:
					return false;
			}
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof VarCheck))
			{
				return false;
			}
			VarCheck v = (VarCheck) o;
			return v.varp == varp && v.id == id && v.op == op && v.value == value;
		}

		@Override
		public int hashCode()
		{
			return ((varp ? 1 : 0) * 31 + id) * 31 * 31 + op * 31 + value;
		}

		@Override
		public String toString()
		{
			return (varp ? "varp " : "varbit ") + id + op + value;
		}
	}

	/** One item requirement: any one of its options (ids with a quantity); quantity 0 means none of them may be carried or worn. */
	public static final class ItemNeed
	{
		public final List<int[]> optionIds;
		public final List<Integer> optionQuantities;
		public final String text;

		ItemNeed(List<int[]> optionIds, List<Integer> optionQuantities, String text)
		{
			this.optionIds = Collections.unmodifiableList(optionIds);
			this.optionQuantities = Collections.unmodifiableList(optionQuantities);
			this.text = text;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof ItemNeed && ((ItemNeed) o).text.equals(text);
		}

		@Override
		public int hashCode()
		{
			return text.hashCode();
		}

		@Override
		public String toString()
		{
			return text;
		}
	}

	private final List<SkillLevel> skills;
	private final List<Quest> quests;
	private final List<ItemNeed> items;
	private final List<VarCheck> vars;
	private final List<String> unresolved;
	/** Quest names as written in the data (for the report). */
	private final List<String> questNames;
	/** Rules Spyglass adds to a transition (canonical §28.11), e.g. {@link #ENTRANA}. */
	private final List<String> restrictions;

	/** The monks of Entrana search you: no weapons or armour (OSRS Wiki "Entrana"). */
	public static final String ENTRANA = "no weapons or armour";
	/** A balloon flight: the player may carry at most 40 kg (OSRS Wiki "Balloon transport system"; the log for the trip aside). */
	public static final String BALLOON_WEIGHT = "carry at most 40 kg";
	public static final int BALLOON_MAX_KG = 40;

	NavRequirement(List<SkillLevel> skills, List<Quest> quests, List<ItemNeed> items, List<VarCheck> vars, List<String> unresolved,
		List<String> questNames)
	{
		this(skills, quests, items, vars, unresolved, questNames, Collections.emptyList());
	}

	NavRequirement(List<SkillLevel> skills, List<Quest> quests, List<ItemNeed> items, List<VarCheck> vars, List<String> unresolved,
		List<String> questNames, List<String> restrictions)
	{
		this.restrictions = Collections.unmodifiableList(new ArrayList<>(restrictions));
		this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
		this.quests = Collections.unmodifiableList(new ArrayList<>(quests));
		this.items = Collections.unmodifiableList(new ArrayList<>(items));
		this.vars = Collections.unmodifiableList(new ArrayList<>(vars));
		this.unresolved = Collections.unmodifiableList(new ArrayList<>(unresolved));
		this.questNames = Collections.unmodifiableList(new ArrayList<>(questNames));
	}

	public List<SkillLevel> getSkills()
	{
		return skills;
	}

	public List<Quest> getQuests()
	{
		return quests;
	}

	public List<ItemNeed> getItems()
	{
		return items;
	}

	public List<VarCheck> getVars()
	{
		return vars;
	}

	/** Parts that could not be mapped; non-empty makes the requirement unmeetable. */
	public List<String> getUnresolved()
	{
		return unresolved;
	}

	public List<String> getRestrictions()
	{
		return restrictions;
	}

	public boolean isNone()
	{
		return skills.isEmpty() && quests.isEmpty() && items.isEmpty() && vars.isEmpty() && unresolved.isEmpty() && restrictions.isEmpty();
	}

	/** The same requirement without restrictions (planning: they are things to do before setting off). */
	public NavRequirement withoutRestrictions()
	{
		return restrictions.isEmpty() ? this : new NavRequirement(skills, quests, items, vars, unresolved, questNames, Collections.emptyList());
	}

	/** The same requirement with a restriction added. */
	NavRequirement withRestriction(String r)
	{
		List<String> rs = new ArrayList<>(restrictions);
		if (!rs.contains(r))
		{
			rs.add(r);
		}
		return new NavRequirement(skills, quests, items, vars, unresolved, questNames, rs);
	}

	/** The same requirement without its item needs. */
	public NavRequirement withoutItems()
	{
		if (items.isEmpty())
		{
			return this;
		}
		return new NavRequirement(skills, quests, Collections.emptyList(), vars, unresolved, questNames, restrictions);
	}

	/** Everything both need (a journey's requirement is every leg's). */
	public NavRequirement and(NavRequirement o)
	{
		if (o == null || o.isNone())
		{
			return this;
		}
		if (isNone())
		{
			return o;
		}
		return new NavRequirement(union(skills, o.skills), union(quests, o.quests), union(items, o.items), union(vars, o.vars),
			union(unresolved, o.unresolved), union(questNames, o.questNames), union(restrictions, o.restrictions));
	}

	private static <T> List<T> union(List<T> a, List<T> b)
	{
		Set<T> s = new LinkedHashSet<>(a);
		s.addAll(b);
		return new ArrayList<>(s);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof NavRequirement))
		{
			return false;
		}
		NavRequirement r = (NavRequirement) o;
		return new LinkedHashSet<>(skills).equals(new LinkedHashSet<>(r.skills)) && new LinkedHashSet<>(quests).equals(new LinkedHashSet<>(r.quests))
			&& new LinkedHashSet<>(items).equals(new LinkedHashSet<>(r.items)) && new LinkedHashSet<>(vars).equals(new LinkedHashSet<>(r.vars))
			&& new LinkedHashSet<>(unresolved).equals(new LinkedHashSet<>(r.unresolved)) && new LinkedHashSet<>(restrictions).equals(new LinkedHashSet<>(r.restrictions));
	}

	@Override
	public int hashCode()
	{
		return new LinkedHashSet<>(skills).hashCode() ^ new LinkedHashSet<>(quests).hashCode() ^ new LinkedHashSet<>(items).hashCode()
			^ new LinkedHashSet<>(vars).hashCode() ^ new LinkedHashSet<>(unresolved).hashCode();
	}

	/** "66 Agility; Troll Stronghold; varbit 111>0" (empty for none). */
	@Override
	public String toString()
	{
		List<String> parts = new ArrayList<>();
		skills.forEach(s -> parts.add(s.toString()));
		questNames.forEach(parts::add);
		items.forEach(i -> parts.add(i.toString()));
		vars.forEach(v -> parts.add(v.toString()));
		unresolved.forEach(u -> parts.add("unresolved " + u));
		parts.addAll(restrictions);
		return String.join("; ", parts);
	}

	// -- parsing ---------------------------------------------------------------------------------------------

	/** Shortest Path's token for logs kept in a balloon's log storage (an alternative to carrying the log). */
	static final String BALLOON_LOG_STORAGE = "UNLOCK_BALLOON_LOG_BASKET";

	static NavRequirement parse(String skillsCell, String questsCell, String itemsCell, String varbitsCell, String varpsCell,
		Map<String, int[]> itemNames)
	{
		List<SkillLevel> skills = new ArrayList<>();
		List<Quest> quests = new ArrayList<>();
		List<String> questNames = new ArrayList<>();
		List<ItemNeed> items = new ArrayList<>();
		List<VarCheck> vars = new ArrayList<>();
		List<String> unresolved = new ArrayList<>();
		for (String part : split(skillsCell, ";"))
		{
			String[] lv = part.trim().split(" ", 2);
			Skill skill = null;
			if (lv.length == 2)
			{
				for (Skill s : Skill.values())
				{
					if (s.getName().equalsIgnoreCase(lv[1].trim()))
					{
						skill = s;
					}
				}
			}
			try
			{
				if (skill == null)
				{
					unresolved.add("skill " + part);
				}
				else if (!"Max".equals(lv[0]))
				{
					skills.add(new SkillLevel(skill, Integer.parseInt(lv[0])));
				}
				else
				{
					unresolved.add("skill " + part);
				}
			}
			catch (NumberFormatException e)
			{
				unresolved.add("skill " + part);
			}
		}
		for (String name : split(questsCell, ";"))
		{
			Quest found = null;
			for (Quest q : Quest.values())
			{
				if (q.getName().equals(name.trim()))
				{
					found = q;
				}
			}
			if (found == null)
			{
				unresolved.add("quest " + name);
			}
			else
			{
				quests.add(found);
				questNames.add(name.trim());
			}
		}
		String normalized = itemsCell == null ? "" : itemsCell.replace(" ", "").replace("&&", "&").replace("||", "|").toUpperCase(java.util.Locale.ROOT);
		for (String and : split(normalized, "&"))
		{
			List<int[]> ids = new ArrayList<>();
			List<Integer> qty = new ArrayList<>();
			boolean ok = true;
			String[] ors = and.split("\\|");
			for (String or : ors)
			{
				if (or.startsWith(BALLOON_LOG_STORAGE + "=") && ors.length > 1)
				{
					// Logs left in a balloon's log storage: not readable, so only the carried log counts (stricter, never looser).
					continue;
				}
				String[] nq = or.split("=");
				if (nq.length != 2 || !nq[1].matches("\\d+"))
				{
					ok = false;
					break;
				}
				int[] resolved = itemNames.get(nq[0]);
				if (resolved == null && nq[0].matches("\\d+"))
				{
					resolved = new int[]{Integer.parseInt(nq[0])};
				}
				if (resolved == null || nq[0].startsWith("UNLOCK_"))
				{
					ok = false;
					break;
				}
				ids.add(resolved);
				qty.add(Integer.parseInt(nq[1]));
			}
			if (ok)
			{
				items.add(new ItemNeed(ids, qty, and));
			}
			else
			{
				unresolved.add("items " + and);
			}
		}
		for (int k = 0; k < 2; k++)
		{
			for (String part : split(k == 0 ? varbitsCell : varpsCell, ";"))
			{
				VarCheck v = null;
				for (char op : new char[]{'&', '=', '>', '<'})
				{
					int at = part.indexOf(op);
					if (at > 0)
					{
						try
						{
							v = new VarCheck(k == 1, Integer.parseInt(part.substring(0, at).trim()), op, Integer.parseInt(part.substring(at + 1).trim()));
						}
						catch (NumberFormatException e)
						{
							v = null;
						}
						break;
					}
				}
				if (v == null)
				{
					unresolved.add((k == 0 ? "varbit " : "varp ") + part);
				}
				else
				{
					vars.add(v);
				}
			}
		}
		if (skills.isEmpty() && quests.isEmpty() && items.isEmpty() && vars.isEmpty() && unresolved.isEmpty())
		{
			return NONE;
		}
		return new NavRequirement(skills, quests, items, vars, unresolved, questNames);
	}

	private static List<String> split(String cell, String delim)
	{
		List<String> out = new ArrayList<>();
		if (cell == null)
		{
			return out;
		}
		for (String s : cell.split(java.util.regex.Pattern.quote(delim)))
		{
			if (!s.trim().isEmpty())
			{
				out.add(s.trim());
			}
		}
		return out;
	}
}
