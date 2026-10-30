package com.osrstelemetry.plugin.skills.herblore;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.UnitFamily;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Herblore's vocabulary for the generic skill companion (Skills Phase 6):
 * the RECIPE is the method; potions are counted in DOSES of their family;
 * herb cleaning, unfinished potions and finished potions each get only the
 * sections that mean something for them.
 */
public final class HerbloreCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "herblore";
	public static final HerbloreCompanionDefinition INSTANCE = new HerbloreCompanionDefinition();
	public static final String STORAGE_NAME = "Potion Storage";

	private final Map<Integer, SkillResource> resources = new LinkedHashMap<>();
	/** The process that makes each output item (the first recipe wins), for {@link #producedGroup}. */
	private final Map<Integer, HerbloreProcess> outputProcess = new java.util.HashMap<>();

	private HerbloreCompanionDefinition()
	{
		for (HerbloreRecipe r : HerbloreRecipes.all())
		{
			for (Integer out : r.getOutputItems())
			{
				outputProcess.putIfAbsent(out, r.getProcess());
				String name = HerbloreRecipes.name(out);
				String shortName = r.getOutputName();
				resources.putIfAbsent(out, new SkillResource(out, name == null ? shortName : name, shortName, SkillResource.Role.PRIMARY, null,
					r.getProcess() == HerbloreProcess.SPECIAL && !r.isDoseNormalized() && r.getInputs().size() > 1
						&& r.getInputs().get(1).getPerMix() != null && r.getInputs().get(1).getPerMix() > 1));
			}
		}
	}

	@Override
	public String getSkillKey()
	{
		return SKILL_KEY;
	}

	@Override
	public Skill getSkill()
	{
		return Skill.HERBLORE;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		return r == null ? null : r.getName();
	}

	@Override
	public String methodTitle()
	{
		return "Recipe";
	}

	@Override
	public SkillResource resource(Integer itemId)
	{
		return itemId == null ? null : resources.get(itemId);
	}

	@Override
	public boolean tripMatters(String methodKey)
	{
		return false;
	}

	@Override
	public boolean groupIsPrimary(String methodKey, String group)
	{
		return false;
	}

	@Override
	public String productionCaption(String methodKey, List<SkillResource> mainResources)
	{
		if (mainResources.isEmpty())
		{
			return "PRODUCED";
		}
		return caption(mainResources.get(0).getShortName());
	}

	/** Longest caption that still fits one narrow cell (the full name is the RECIPE row right above). */
	static final int MAX_CAPTION = 22;

	/** "SUPER RESTORE"; long names lose a trailing "potion" ("DIVINE SUPER COMBAT"), then fall back to "MADE". */
	static String caption(String name)
	{
		String c = name.toUpperCase(Locale.ROOT);
		if (c.length() > MAX_CAPTION && c.endsWith(" POTION"))
		{
			c = c.substring(0, c.length() - " POTION".length());
		}
		return c.length() > MAX_CAPTION ? "MADE" : c;
	}

	@Override
	public String rateNoun(String methodKey)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		if (r == null)
		{
			return null;
		}
		switch (r.getProcess())
		{
			case CLEANING:
				return "herbs";
			case UNFINISHED_POTION:
			case POISON:
				return "potions";
			default:
				return r.isDoseNormalized() ? HerbloreRecipes.DOSES : null;
		}
	}

	/** Pass 28: cleaned herbs and unfinished potions never share the potions grid. */
	@Override
	public String producedGroup(int itemId)
	{
		HerbloreProcess p = outputProcess.get(itemId);
		if (p == HerbloreProcess.CLEANING)
		{
			return "Herbs cleaned";
		}
		if (p == HerbloreProcess.UNFINISHED_POTION)
		{
			return "Unfinished potions";
		}
		return "Potions";
	}

	@Override
	public String producedVerb()
	{
		return "made";
	}

	@Override
	public String producedTitle()
	{
		return "Made";
	}

	/**
	 * The current recipe's own output only (never a list of every potion): its
	 * potion family as one resource (OWNED in doses over every variant, plus
	 * Potion Storage), or the plain item (a clean herb, an unfinished potion).
	 */
	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		if (r == null)
		{
			return Collections.emptyList();
		}
		int item = r.getOutputFamily() != null ? r.getOutputFamily().getCanonicalItemId() : r.getOutputItems().iterator().next();
		SkillResource res = resource(item);
		return res == null ? Collections.<SkillResource>emptyList() : Collections.singletonList(res);
	}

	@Override
	public boolean storageRelevant(String methodKey, Set<Integer> producedItems)
	{
		return false;
	}

	@Override
	public boolean storageCatchesMain(String methodKey)
	{
		return false;
	}

	@Override
	public boolean methodProduces(String methodKey, int itemId)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		return r != null && r.produces(itemId);
	}

	@Override
	public boolean productionFollowsMethod()
	{
		return true;
	}

	@Override
	public String bankedTooltip()
	{
		return "Of that, what Spyglass saw you put into your bank or " + STORAGE_NAME + " (counted in doses for potions; drops and unproven "
			+ "deposits never count)";
	}

	/** Potion families with more than one dose variant are counted in doses; everything else in items. */
	@Override
	public UnitFamily unitFamily(SkillResource resource)
	{
		if (resource == null)
		{
			return null;
		}
		UnitFamily f = HerbloreRecipes.familyOf(resource.getItemId());
		return f != null && f.getFullUnits() > 1 ? f : null;
	}

	/** Exact name of a recipe input (consumed telemetry is sent by name, never by guess). */
	@Override
	public String inputName(Integer itemId)
	{
		return itemId == null || !HerbloreRecipes.isInput(itemId) ? null : HerbloreRecipes.name(itemId);
	}

	@Override
	public String actionKeyPrefix()
	{
		return HerbloreCompanionTracker.MIX;
	}

	/**
	 * POTIONS CREATED: every finished potion of the whole session, whatever
	 * recipe made it (30 super attacks then 20 prayer potions = 50 -- a recipe
	 * switch never resets it); HERBS CLEANED while cleaning. Unfinished
	 * potions are not potions created (their count is the production).
	 */
	@Override
	public String actionCaption(String methodKey)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		if (r == null)
		{
			return null;
		}
		return r.getProcess() == HerbloreProcess.CLEANING ? "HERBS CLEANED" : "POTIONS CREATED";
	}

	@Override
	public String actionRateCaption(String methodKey)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		return r != null && r.getProcess() == HerbloreProcess.CLEANING ? "HERBS / HR" : "POTIONS / HR";
	}

	/** Every recipe of the session in the same family of actions as the shown one: cleaning, or making finished potions. */
	@Override
	public Set<String> actionMethods(String methodKey, Set<String> sessionMethods)
	{
		HerbloreRecipe shown = HerbloreRecipes.recipe(methodKey);
		boolean cleaning = shown != null && shown.getProcess() == HerbloreProcess.CLEANING;
		Set<String> out = new java.util.LinkedHashSet<>();
		for (String key : sessionMethods)
		{
			HerbloreRecipe r = HerbloreRecipes.recipe(key);
			if (r == null || r.getProcess() == HerbloreProcess.UNFINISHED_POTION)
			{
				continue;
			}
			if ((r.getProcess() == HerbloreProcess.CLEANING) == cleaning)
			{
				out.add(key);
			}
		}
		return out;
	}

	/** Herblore keeps the account's supplies in IN BANK (the tracker's stock view): no session input cells. */
	@Override
	public List<SkillCompanionView.EventView.Field> inputFields(String methodKey, Map<Integer, Long> consumed, Map<String, Long> occurrences)
	{
		return Collections.emptyList();
	}

	@Override
	public List<String> inputLines(String methodKey, Map<String, Long> occurrences)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		Long saved = r == null ? null : occurrences.get(HerbloreCompanionTracker.SAVED + r.getKey());
		if (saved == null || saved <= 0)
		{
			return Collections.emptyList();
		}
		return Collections.singletonList(fmt(saved) + (saved == 1 ? " secondary saved" : " secondaries saved"));
	}

	/** Live: only secondaries prescription goggles saved (doses are the production's own number, never "+N bonus doses" prose). */
	@Override
	public List<String> liveOccurrenceLines(String methodKey, Map<String, Long> occurrences)
	{
		return occurrences == null ? Collections.<String>emptyList() : inputLines(methodKey, occurrences);
	}

	/** Herblore's target is a level: potions are made to be used, not stockpiled here. */
	@Override
	public boolean offersResourceTarget()
	{
		return false;
	}

	/** "+36 bonus doses" (History) -- only what an amulet was seen to add (never an expected rate). */
	@Override
	public List<String> occurrenceLines(String methodKey, Map<String, Long> occurrences)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methodKey);
		Long bonus = r == null || occurrences == null ? null : occurrences.get(HerbloreCompanionTracker.BONUS + r.getKey());
		if (bonus == null || bonus <= 0)
		{
			return Collections.emptyList();
		}
		return Collections.singletonList("+" + fmt(bonus) + (bonus == 1 ? " bonus dose" : " bonus doses"));
	}

	// ---- History ---------------------------------------------------------------------------

	/** The recipes of a session, longest first (time), then by mixes. */
	private static List<HerbloreRecipe> recipesOf(SessionTotals t)
	{
		List<HerbloreRecipe> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : t.getOccurrences().entrySet())
		{
			if (e.getKey().startsWith(HerbloreCompanionTracker.MIX) && e.getValue() != null && e.getValue() > 0)
			{
				HerbloreRecipe r = HerbloreRecipes.recipe(e.getKey().substring(HerbloreCompanionTracker.MIX.length()));
				if (r != null && !out.contains(r))
				{
					out.add(r);
				}
			}
		}
		out.sort((a, b) ->
		{
			int byTime = Long.compare(t.getMethodMillis().getOrDefault(b.getKey(), 0L), t.getMethodMillis().getOrDefault(a.getKey(), 0L));
			return byTime != 0 ? byTime : Long.compare(occ(t, HerbloreCompanionTracker.MIX, b), occ(t, HerbloreCompanionTracker.MIX, a));
		});
		return out;
	}

	private static long occ(SessionTotals t, String prefix, HerbloreRecipe r)
	{
		Long v = t.getOccurrences().get(prefix + r.getKey());
		return v == null ? 0L : v;
	}

	/** "Super restore • 358 mixes • 1,086 doses" / "Ranarr weed • 482 cleaned" (XP follows, generic). */
	@Override
	public List<String> historyRowFacts(SessionTotals totals)
	{
		List<HerbloreRecipe> recipes = recipesOf(totals);
		if (recipes.isEmpty())
		{
			return null;
		}
		HerbloreRecipe main = recipes.get(0);
		List<String> facts = new ArrayList<>();
		long mixes = occ(totals, HerbloreCompanionTracker.MIX, main);
		long units = occ(totals, HerbloreCompanionTracker.UNITS, main);
		switch (main.getProcess())
		{
			case CLEANING:
				facts.add(main.getOutputName());
				facts.add(fmt(mixes) + " cleaned");
				break;
			case UNFINISHED_POTION:
				facts.add(main.getName());
				facts.add(fmt(units) + " made");
				break;
			default:
				facts.add(main.getName());
				facts.add(fmt(mixes) + (mixes == 1 ? " mix" : " mixes"));
				if (main.isDoseNormalized())
				{
					facts.add(main.getOutputFamily().unitsText(units));
				}
				else if (units != mixes)
				{
					facts.add(fmt(units) + " made");
				}
		}
		if (recipes.size() > 1)
		{
			facts.add("+" + (recipes.size() - 1) + (recipes.size() == 2 ? " recipe" : " recipes"));
		}
		return facts;
	}

	/**
	 * Per recipe, never blended: mixes (and per hour of that recipe's own
	 * time), output (doses for potions), banked, exact inputs, actual saved
	 * secondaries and bonus doses. XP and each recipe's time follow (generic).
	 */
	@Override
	public List<HistoryRow> historyRows(SessionTotals totals)
	{
		List<HerbloreRecipe> recipes = recipesOf(totals);
		if (recipes.isEmpty())
		{
			return null;
		}
		List<HistoryRow> rows = new ArrayList<>();
		for (HerbloreRecipe r : recipes)
		{
			long mixes = occ(totals, HerbloreCompanionTracker.MIX, r);
			long units = occ(totals, HerbloreCompanionTracker.UNITS, r);
			Long millis = totals.getMethodMillis().get(r.getKey());
			boolean rated = millis != null && millis >= com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
			String actions;
			switch (r.getProcess())
			{
				case CLEANING:
					actions = fmt(mixes) + " cleaned";
					break;
				case UNFINISHED_POTION:
					actions = fmt(units) + " made";
					break;
				default:
					actions = fmt(mixes) + (mixes == 1 ? " mix" : " mixes");
			}
			rows.add(new HistoryRow(r.getName(), actions + (rated ? " • " + fmt(Math.round(
				(r.getProcess() == HerbloreProcess.UNFINISHED_POTION ? units : mixes) * 3_600_000d / millis)) + "/hr" : ""),
				"This recipe's own successful operations (per hour of its own time)"));
			if (r.isDoseNormalized())
			{
				UnitFamily f = r.getOutputFamily();
				rows.add(new HistoryRow("  Made", f.unitsText(units) + (rated ? " • " + fmt(Math.round(units * 3_600_000d / millis)) + "/hr" : ""),
					"Doses of " + f.getName() + " this recipe produced (every variant; bonus doses included)"));
				long banked = 0L;
				for (Map.Entry<Integer, Long> b : totals.getBanked().entrySet())
				{
					if (r.produces(b.getKey()))
					{
						banked += b.getValue() * f.unitsOf(b.getKey());
					}
				}
				if (banked > 0)
				{
					rows.add(new HistoryRow("  Banked / stored", f.unitsText(banked),
						"Of that, what Spyglass saw reach your bank or " + STORAGE_NAME));
				}
			}
			else if (r.getProcess() != HerbloreProcess.UNFINISHED_POTION && units != mixes)
			{
				rows.add(new HistoryRow("  Made", fmt(units) + " " + r.getOutputName(), null));
			}
			for (HerbloreRecipe.Input in : r.getInputs())
			{
				long used = 0L;
				for (Map.Entry<Integer, Integer> item : in.getItems().entrySet())
				{
					Long u = totals.getOccurrences().get(HerbloreCompanionTracker.USED + r.getKey() + ":" + item.getKey());
					used += u == null ? 0L : u * (in.isDosed() ? item.getValue() : 1);
				}
				if (used > 0)
				{
					rows.add(new HistoryRow("  " + in.getName(), fmt(used) + (in.isDosed() ? " doses" : ""), "Actually used (exact)"));
				}
			}
			long saved = occ(totals, HerbloreCompanionTracker.SAVED, r);
			if (saved > 0)
			{
				rows.add(new HistoryRow("  Secondaries saved", fmt(saved), "Seen saved by your prescription goggles (never an expected rate)"));
			}
			long bonus = occ(totals, HerbloreCompanionTracker.BONUS, r);
			if (bonus > 0)
			{
				rows.add(new HistoryRow("  Bonus doses", "+" + fmt(bonus), "Seen added by your amulet (never an expected rate)"));
			}
		}
		return rows;
	}

	private static String fmt(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}
}
