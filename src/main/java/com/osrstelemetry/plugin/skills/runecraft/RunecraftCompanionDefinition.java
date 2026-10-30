package com.osrstelemetry.plugin.skills.runecraft;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Runecraft's vocabulary for the generic skill companion -- the SAME chassis
 * as Mining / Fishing / Agility / Woodcutting -- and the first INPUT ->
 * TRANSFORMATION -> OUTPUT skill.
 *
 * <ul>
 * <li>OUTPUT: runes are PRODUCED, counted from what actually reached the
 * inventory -- level multipliers, Raiments of the Eye's extra runes, Scar
 * extracts' bonus runes and failed binds are all simply what arrived; no
 * multiplier table is ever applied.</li>
 * <li>INPUT: the generic consumed-input concept. ESSENCE (rune, pure,
 * daeyalt, dark essence fragments) is the primary input ("756 essence
 * used"); catalysts that also leave on a craft (the elemental rune of a
 * combination rune, the soul rune + aether catalyst of an aether rune, the
 * fire rune + sunfire splinter of a sunfire rune, the talisman of a
 * combination bind, Scar runic extracts) are recorded by exact name and never
 * counted as essence.</li>
 * </ul>
 *
 * Guardians of the Rift is a structured activity and never this companion:
 * its region is excluded natively and guardian essence is not an input here.
 */
public final class RunecraftCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "runecraft";
	public static final RunecraftCompanionDefinition INSTANCE = new RunecraftCompanionDefinition();
	/** Guardians of the Rift's region (StructuredActivityCatalog): never generic Runecraft. */
	public static final int GOTR_REGION = 14484;
	/** The Abyss (RuneLite's own Runecraft plugin uses the same region for its rifts). */
	public static final int ABYSS_REGION = 12107;

	/** Occurrence keys (per method): summed over COUNTED trips only, and bind attempts / successes. */
	public static final String TRIP_RUNES = "trip_runes:";
	public static final String TRIP_ESSENCE = "trip_essence:";
	public static final String BIND_ATTEMPTS = "bind_attempts:";
	public static final String BIND_SUCCESSES = "bind_bound:";

	private static final Map<Integer, SkillResource> RUNES = new LinkedHashMap<>();
	private static final Map<Integer, String> ESSENCE = new LinkedHashMap<>();
	private static final Map<Integer, String> OTHER_INPUTS = new LinkedHashMap<>();

	static
	{
		for (RunecraftMethod m : RunecraftMethod.values())
		{
			if (m.getRune() > 0 && !RUNES.containsKey(m.getRune()))
			{
				String caption = m.getProductionCaption().toLowerCase(Locale.ROOT); // "nature runes"
				String single = caption.substring(0, caption.length() - 1);
				String first = single.substring(0, single.indexOf(' '));
				RUNES.put(m.getRune(), new SkillResource(m.getRune(), Character.toUpperCase(single.charAt(0)) + single.substring(1),
					Character.toUpperCase(first.charAt(0)) + first.substring(1), SkillResource.Role.PRIMARY, null, true));
			}
		}
		ESSENCE.put(ItemID.BLANKRUNE, "Rune essence");
		ESSENCE.put(ItemID.BLANKRUNE_HIGH, "Pure essence");
		ESSENCE.put(ItemID.BLANKRUNE_DAEYALT, "Daeyalt essence");
		ESSENCE.put(ItemID.BIGBLANKRUNE, "Dark essence fragments");

		OTHER_INPUTS.put(ItemID.SCAR_EXTRACT_WARPED, "Warped extract");
		OTHER_INPUTS.put(ItemID.SCAR_EXTRACT_TWISTED, "Twisted extract");
		OTHER_INPUTS.put(ItemID.SCAR_EXTRACT_MANGLED, "Mangled extract");
		OTHER_INPUTS.put(ItemID.SCAR_EXTRACT_SCARRED, "Scarred extract");
		OTHER_INPUTS.put(ItemID.SUNFIRESPLINTER, "Sunfire splinter");
		OTHER_INPUTS.put(ItemID.COSMIC_SOUL_CATALYST, "Aether catalyst");
		OTHER_INPUTS.put(ItemID.AIR_TALISMAN, "Air talisman");
		OTHER_INPUTS.put(ItemID.WATER_TALISMAN, "Water talisman");
		OTHER_INPUTS.put(ItemID.EARTH_TALISMAN, "Earth talisman");
		OTHER_INPUTS.put(ItemID.FIRE_TALISMAN, "Fire talisman");
		// Runes used up by a craft are catalysts (never essence, never "production lost").
		for (SkillResource r : RUNES.values())
		{
			OTHER_INPUTS.put(r.getItemId(), r.getName());
		}
	}

	private RunecraftCompanionDefinition()
	{
	}

	public static boolean isRune(Integer itemId)
	{
		return itemId != null && RUNES.containsKey(itemId);
	}

	/** Essence of any kind a craft binds (never guardian essence: that is GotR's). */
	public static boolean isEssence(Integer itemId)
	{
		return itemId != null && ESSENCE.containsKey(itemId);
	}

	/** Anything a craft may use up: essence or a catalyst / extract / talisman / rune. */
	public static boolean isInput(Integer itemId)
	{
		return itemId != null && (ESSENCE.containsKey(itemId) || OTHER_INPUTS.containsKey(itemId));
	}

	public static Map<Integer, SkillResource> runes()
	{
		return Collections.unmodifiableMap(RUNES);
	}

	@Override
	public String getSkillKey()
	{
		return SKILL_KEY;
	}

	@Override
	public Skill getSkill()
	{
		return Skill.RUNECRAFT;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		RunecraftMethod m = RunecraftMethod.fromKey(methodKey);
		if (m != null)
		{
			return m.getLabel();
		}
		if (methodKey == null || methodKey.isEmpty())
		{
			return null;
		}
		String s = methodKey.replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Override
	public SkillResource resource(Integer itemId)
	{
		return itemId == null ? null : RUNES.get(itemId);
	}

	/** Runes are stackable: the inventory never fills with output, so there is no slot-based trip estimate. */
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
		if (mainResources == null || mainResources.isEmpty())
		{
			return "TOTAL";
		}
		if (mainResources.size() > 1)
		{
			return "TOTAL RUNES";
		}
		RunecraftMethod m = RunecraftMethod.standardFor(mainResources.get(0).getItemId());
		return m == null ? "RUNES" : m.getProductionCaption();
	}

	@Override
	public String rateNoun(String methodKey)
	{
		return RunecraftMethod.fromKey(methodKey) == null ? null : "runes";
	}

	@Override
	public String producedVerb()
	{
		return "crafted";
	}

	@Override
	public String producedTitle()
	{
		return "Crafted";
	}

	/**
	 * The method's own rune (a combination method: its combination rune).
	 * Ourania offers none of its own: the runes the session actually crafted
	 * are offered instead, each a reliable banked resource.
	 */
	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		List<SkillResource> out = new ArrayList<>();
		RunecraftMethod m = RunecraftMethod.fromKey(methodKey);
		if (m != null && !m.isMixed())
		{
			out.add(RUNES.get(m.getRune()));
		}
		return out;
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
		RunecraftMethod m = RunecraftMethod.fromKey(methodKey);
		return m != null && isRune(itemId) && (m.isMixed() || m.getRune() == itemId);
	}

	@Override
	public String cycleNoun(boolean plural)
	{
		return plural ? "trips" : "trip";
	}

	@Override
	public String stockpileEmptyHint()
	{
		return "Craft something first to pick what to stockpile";
	}

	@Override
	public String inputName(Integer itemId)
	{
		if (itemId == null)
		{
			return null;
		}
		String e = ESSENCE.get(itemId);
		return e != null ? e : OTHER_INPUTS.get(itemId);
	}

	@Override
	public boolean isPrimaryInput(Integer itemId)
	{
		return isEssence(itemId);
	}

	@Override
	public String consumedPhrase()
	{
		return "essence used";
	}

	@Override
	public String consumedTooltip()
	{
		return "Inputs your crafting used up this session (only what Spyglass saw become output)";
	}

	@Override
	public String otherInputsTooltip()
	{
		return "Other inputs your crafting used up (catalysts, extracts...): never counted as essence";
	}

	@Override
	public boolean historyShowsProducedWithCycles()
	{
		return true;
	}

	@Override
	public boolean historyMethodProduction()
	{
		return true;
	}

	@Override
	public String eventTitle()
	{
		return "Current trip";
	}

	private static final List<CycleYield> YIELDS = Collections.unmodifiableList(Arrays.asList(
		new CycleYield(TRIP_RUNES, "RUNES / TRIP", "Runes / trip"),
		new CycleYield(TRIP_ESSENCE, "ESSENCE / TRIP", "Essence / trip")));

	@Override
	public List<CycleYield> cycleYields()
	{
		return YIELDS;
	}

	/** A binding method: how many binds held, from the crafts where input and output prove it ("Bound 412 / 448 (92%)"). */
	@Override
	public List<String> occurrenceLines(String methodKey, Map<String, Long> occurrences)
	{
		RunecraftMethod m = RunecraftMethod.fromKey(methodKey);
		if (m == null || !m.isBinding() || occurrences == null)
		{
			return Collections.emptyList();
		}
		long attempts = occurrences.getOrDefault(BIND_ATTEMPTS + m.getKey(), 0L);
		long bound = occurrences.getOrDefault(BIND_SUCCESSES + m.getKey(), 0L);
		if (attempts <= 0)
		{
			return Collections.emptyList();
		}
		return Collections.singletonList(String.format(Locale.ROOT, "Bound %,d / %,d (%d%%)", bound, attempts,
			Math.round(bound * 100d / attempts)));
	}
}
