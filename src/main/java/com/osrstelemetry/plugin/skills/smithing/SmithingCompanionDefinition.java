package com.osrstelemetry.plugin.skills.smithing;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Smithing's vocabulary for the generic skill companion (Skills Phase 7): the
 * RECIPE is the method (a bar by its process -- furnace, Superheat, Blast
 * Furnace -- or a smithed product); SESSION counts the recipe's own output
 * units (bars, items, dart tips, cannonballs...) per hour of its own time;
 * PRODUCTION is the actual output with what reached the bank; INPUTS USED is
 * exactly what left. No economy figures.
 */
public final class SmithingCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "smithing";
	public static final SmithingCompanionDefinition INSTANCE = new SmithingCompanionDefinition();

	private final Map<Integer, SkillResource> resources = new LinkedHashMap<>();

	private SmithingCompanionDefinition()
	{
		for (SmithingRecipe r : SmithingRecipes.all())
		{
			// The first recipe of an output names it (bars: the furnace recipe, "Steel bars").
			resources.putIfAbsent(r.getOutputItem(), new SkillResource(r.getOutputItem(), r.getOutputName(), r.getName(),
				SkillResource.Role.PRIMARY, null, r.getOutputPerAction() > 1));
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
		return Skill.SMITHING;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
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

	/** Longest caption that still fits one narrow cell (the full name is the RECIPE row right above). */
	static final int MAX_CAPTION = 22;

	@Override
	public String productionCaption(String methodKey, List<SkillResource> mainResources)
	{
		if (mainResources.isEmpty())
		{
			return "MADE";
		}
		String c = mainResources.get(0).getShortName().toUpperCase(Locale.ROOT);
		return c.length() > MAX_CAPTION ? "MADE" : c;
	}

	@Override
	public String rateNoun(String methodKey)
	{
		return null;
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

	/** The current recipe's own output only (never a list of every product). */
	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
		SkillResource res = r == null ? null : resource(r.getOutputItem());
		return res == null ? Collections.<SkillResource>emptyList() : Collections.singletonList(res);
	}

	@Override
	public String stockpileEmptyHint()
	{
		return "Make something first to pick what to stockpile";
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
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
		return r != null && r.getOutputItem() == itemId;
	}

	@Override
	public boolean productionFollowsMethod()
	{
		return true;
	}

	/** The production's own rate line is never the blended item rate: the recipe's own count per hour replaces it. */
	@Override
	public boolean productionRateLine()
	{
		return false;
	}

	/** PRODUCTION: the recipe's output as its sprite with the amount (one number, shown once). */
	@Override
	public boolean productionSprites(String methodKey)
	{
		return true;
	}

	/** Smithing makes items to be used: BANKED stays in History, targets and ETAs, never the live panel. */
	@Override
	public boolean productionShowsBanked(String methodKey)
	{
		return false;
	}

	/** One recorded action = one unit made: only the recipe's rate is added (BARS / HR as "312 bars/hr"), never the total twice. */
	@Override
	public boolean actionRateInProduction(String methodKey)
	{
		return true;
	}

	/**
	 * Smelting and smithing make one item every few ticks without a break:
	 * two minutes of the recipe already give a steady rate. The Blast Furnace
	 * pays out in collections a minute or more apart, so it keeps the shared
	 * five minutes (a two-minute window there would swing with each pickup).
	 */
	@Override
	public long actionRateMinMillis(String methodKey)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
		return r != null && r.getProcess() != SmithingProcess.BLAST_FURNACE ? 2L * 60_000L : com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
	}

	@Override
	public String inputName(Integer itemId)
	{
		return itemId == null || !SmithingRecipes.isInput(itemId) ? null : SmithingRecipes.name(itemId);
	}

	@Override
	public String actionKeyPrefix()
	{
		return SmithingCompanionTracker.MADE;
	}

	/** BARS / ITEMS / DART TIPS / CANNONBALLS ...: the recipe's own output units (never "one XP drop = one item"). */
	@Override
	public String actionCaption(String methodKey)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
		return r == null ? null : r.getUnitCaption();
	}

	/** USED: exactly what the current recipe used this session (ores, coal, bars, parts, catalysts), as sprites + counts. */
	@Override
	public List<SkillCompanionView.ItemStat> inputItems(String methodKey, Map<String, Long> occurrences)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
		if (r == null)
		{
			return Collections.emptyList();
		}
		List<SkillCompanionView.ItemStat> items = new ArrayList<>();
		for (SmithingRecipe.Input in : r.getInputs())
		{
			long n = used(occurrences, r, in.getItemId());
			if (n > 0)
			{
				items.add(SkillCompanionView.ItemStat.of(in.getItemId(), in.getName(), n, in.getName() + ": " + fmt(n)
					+ " used by this recipe this session"
					+ (in.isCoal() ? " (from your inventory; coal a coal bag supplied directly is not visible)" : "")
					+ (r.getProcess() == SmithingProcess.BLAST_FURNACE ? " -- settled when its bars were collected" : "")));
			}
		}
		long catalysts = used(occurrences, r, SmithingRecipes.CATALYST);
		if (catalysts > 0)
		{
			items.add(SkillCompanionView.ItemStat.of(SmithingRecipes.CATALYST, "Smithing catalyst", catalysts,
				"Smithing catalysts: " + fmt(catalysts) + " used (each halved one bar's coal)"));
		}
		return items;
	}

	@Override
	public String inputsTitle(String methodKey)
	{
		return "Used";
	}

	private static long used(Map<String, Long> occurrences, SmithingRecipe r, int item)
	{
		Long v = occurrences == null ? null : occurrences.get(SmithingCompanionTracker.USED + r.getKey() + ":" + item);
		return v == null ? 0L : v;
	}

	/** "3 iron ore failed to smelt" (seen: the game's line and the ore gone) -- never an expected rate. */
	@Override
	public List<String> inputLines(String methodKey, Map<String, Long> occurrences)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methodKey);
		Long failed = r == null || occurrences == null ? null : occurrences.get(SmithingCompanionTracker.FAIL + r.getKey());
		if (failed == null || failed <= 0)
		{
			return Collections.emptyList();
		}
		return Collections.singletonList(fmt(failed) + " iron ore failed to smelt");
	}

	// ---- History ---------------------------------------------------------------------------

	/** The recipes of a session, longest first (time), then by output. */
	private static List<SmithingRecipe> recipesOf(SessionTotals t)
	{
		List<SmithingRecipe> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : t.getOccurrences().entrySet())
		{
			if (e.getKey().startsWith(SmithingCompanionTracker.MADE) && e.getValue() != null && e.getValue() > 0)
			{
				SmithingRecipe r = SmithingRecipes.recipe(e.getKey().substring(SmithingCompanionTracker.MADE.length()));
				if (r != null && !out.contains(r))
				{
					out.add(r);
				}
			}
		}
		out.sort((a, b) ->
		{
			int byTime = Long.compare(t.getMethodMillis().getOrDefault(b.getKey(), 0L), t.getMethodMillis().getOrDefault(a.getKey(), 0L));
			return byTime != 0 ? byTime : Long.compare(made(t, b), made(t, a));
		});
		return out;
	}

	private static long made(SessionTotals t, SmithingRecipe r)
	{
		Long v = t.getOccurrences().get(SmithingCompanionTracker.MADE + r.getKey());
		return v == null ? 0L : v;
	}

	/** "Steel bars • 412 smelted" / "Mithril platebody • 28 smithed" / "Steel cannonballs • 2,144 made" (XP follows, generic). */
	@Override
	public List<String> historyRowFacts(SessionTotals totals)
	{
		List<SmithingRecipe> recipes = recipesOf(totals);
		if (recipes.isEmpty())
		{
			return null;
		}
		SmithingRecipe main = recipes.get(0);
		List<String> facts = new ArrayList<>();
		facts.add(main.getName());
		facts.add(fmt(made(totals, main)) + " " + main.getProcess().getVerb());
		if (recipes.size() > 1)
		{
			facts.add("+" + (recipes.size() - 1) + (recipes.size() == 2 ? " recipe" : " recipes"));
		}
		return facts;
	}

	/**
	 * Per recipe, never blended: its output (and per hour of its own time),
	 * banked (and per hour), its exact inputs and observed failures. XP and
	 * each recipe's time follow (generic).
	 */
	@Override
	public List<HistoryRow> historyRows(SessionTotals totals)
	{
		List<SmithingRecipe> recipes = recipesOf(totals);
		if (recipes.isEmpty())
		{
			return null;
		}
		List<HistoryRow> rows = new ArrayList<>();
		java.util.Set<Integer> bankedShown = new java.util.HashSet<>();
		for (SmithingRecipe r : recipes)
		{
			long made = made(totals, r);
			Long millis = totals.getMethodMillis().get(r.getKey());
			boolean rated = millis != null && millis >= SkillGoalPlanner.MIN_RATE_MILLIS;
			String unit = r.getUnitCaption().toLowerCase(Locale.ROOT);
			rows.add(new HistoryRow(r.getName(), fmt(made) + " " + r.getProcess().getVerb()
				+ (rated ? " • " + fmt(Math.round(made * 3_600_000d / millis)) + "/hr" : ""),
				"This recipe's own output (" + unit + "), per hour of its own time"));
			long banked = totals.getBanked().getOrDefault(r.getOutputItem(), 0L);
			if (banked > 0 && bankedShown.add(r.getOutputItem()))
			{
				boolean shared = recipes.stream().filter(o -> o.getOutputItem() == r.getOutputItem()).count() > 1;
				// Banking is per item: when two recipes made the same item (furnace and Blast Furnace steel), it is shown
				// once, for the item, never split by a guess (and without a per-recipe rate).
				rows.add(new HistoryRow(shared ? "  Banked (all " + r.getOutputName().toLowerCase(Locale.ROOT) + "s)" : "  Banked",
					fmt(banked) + (rated && !shared ? " • " + fmt(Math.round(banked * 3_600_000d / millis)) + "/hr" : ""),
					"Of that, what Spyglass saw you deposit into your bank"));
			}
			for (SmithingRecipe.Input in : r.getInputs())
			{
				long used = used(totals.getOccurrences(), r, in.getItemId());
				if (used > 0)
				{
					rows.add(new HistoryRow("  " + in.getName(), fmt(used), "Actually used (exact)"));
				}
			}
			long catalysts = used(totals.getOccurrences(), r, SmithingRecipes.CATALYST);
			if (catalysts > 0)
			{
				rows.add(new HistoryRow("  Smithing catalysts", fmt(catalysts), "Actually used (exact)"));
			}
			Long failed = totals.getOccurrences().get(SmithingCompanionTracker.FAIL + r.getKey());
			if (failed != null && failed > 0)
			{
				rows.add(new HistoryRow("  Too impure", fmt(failed), "Iron ore the furnace rejected (seen, never an expected rate)"));
			}
		}
		return rows;
	}

	private static String fmt(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}
}
