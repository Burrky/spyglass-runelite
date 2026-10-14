package com.osrstelemetry.plugin.skills.fishing;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.SkillStorage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Fishing's vocabulary for the generic skill companion (the SAME chassis as
 * Mining: ProductionLedger, MethodTracker, SkillStorage, SkillBanking,
 * SupplyTracker, SkillCompanionCollector, the goal planner and the panel
 * model). Nothing here is a second production system; it only says what
 * Fishing produces, how its methods are named, which native lines it reads
 * and which containers can catch its fish.
 *
 * <h3>Skill vs activity (classified by PLAYER LOOP, not "gives Fishing XP")</h3>
 * <ul>
 * <li>NORMAL methods (Fishing companion): spot -> fish -> inventory (or an open
 * fish barrel) -> bank or drop. Small net, bait, fly fishing, pike, lobster,
 * harpoon (tuna / swordfish), big net, shark, monkfish, karambwan, anglerfish,
 * dark crabs, barbarian fishing.</li>
 * <li>STACKABLE methods (no CURRENT TRIP: inventory space is never the limit):
 * karambwanji, minnows.</li>
 * <li>Deferred, each for a reason: eels (sacred, infernal, lava, cave, slimy:
 * their "production" is processed further or has an unusual output -- muddy
 * production semantics, a later pass); aerial fishing (the cormorant loop and
 * fish chunks: an adapter of its own); drift net fishing (Fossil Island's
 * net / shoal loop, rewards via the net); Camdozaal (tetra / cave eel feed a
 * shrine, not a bank); lantern harpooning / squid / Civitas park (new loops not
 * audited yet); Fishing Trawler and Tempoross (STRUCTURED ACTIVITIES with
 * their own rounds and reward pools: never a Fishing companion session --
 * their fish are not resources here, so a Tempoross harpoonfish can never be
 * counted as Fishing production).</li>
 * </ul>
 *
 * <h3>Native messages</h3>
 * Every catch prints "You catch a lobster." / "You catch some shrimps." /
 * "You catch an Anglerfish." / "You catch a shark!" -- the line names the
 * fish even when the fish went into an open fish barrel instead of the
 * inventory. Karambwanji / minnows can come several at a time ("You catch
 * 7 Karambwanji."); quantities always come from the destination, never from a
 * line. Flying fish at the minnow platform: "A flying fish jumps up and eats
 * some of your minnows!". A failed karambwan catch still uses bait
 * ("...karambwanji ... eaten"), which is SUPPLY evidence, never production.
 */
public final class FishingCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "fishing";

	public static final FishingCompanionDefinition INSTANCE = new FishingCompanionDefinition();

	private static final Map<Integer, SkillResource> RESOURCES = new LinkedHashMap<>();
	/** Lower-case fish names as catch lines spell them, LONGEST first (karambwanji before karambwan). */
	private static final Map<String, Integer> FISH_BY_NAME = new LinkedHashMap<>();
	/** Supply items' player-facing names (the SUPPLIES caption). */
	private static final Map<Integer, String> SUPPLY_NAMES = new LinkedHashMap<>();

	static
	{
		fish(ItemID.RAW_SHRIMP, "Raw shrimps", "Shrimps", false, "shrimps", "shrimp");
		fish(ItemID.RAW_ANCHOVIES, "Raw anchovies", "Anchovies", false, "anchovies");
		fish(ItemID.RAW_SARDINE, "Raw sardine", "Sardine", false, "sardine");
		fish(ItemID.RAW_HERRING, "Raw herring", "Herring", false, "herring");
		fish(ItemID.RAW_TROUT, "Raw trout", "Trout", false, "trout");
		fish(ItemID.RAW_SALMON, "Raw salmon", "Salmon", false, "salmon");
		fish(ItemID.RAW_PIKE, "Raw pike", "Pike", false, "pike");
		fish(ItemID.RAW_LOBSTER, "Raw lobster", "Lobster", false, "lobster");
		fish(ItemID.RAW_TUNA, "Raw tuna", "Tuna", false, "tuna");
		fish(ItemID.RAW_SWORDFISH, "Raw swordfish", "Swordfish", false, "swordfish");
		fish(ItemID.RAW_MACKEREL, "Raw mackerel", "Mackerel", false, "mackerel");
		fish(ItemID.RAW_COD, "Raw cod", "Cod", false, "cod");
		fish(ItemID.RAW_BASS, "Raw bass", "Bass", false, "bass");
		fish(ItemID.RAW_SHARK, "Raw shark", "Shark", false, "shark");
		fish(ItemID.RAW_MONKFISH, "Raw monkfish", "Monkfish", false, "monkfish");
		fish(ItemID.TBWT_RAW_KARAMBWAN, "Raw karambwan", "Karambwan", false, "karambwan");
		fish(ItemID.TBWT_RAW_KARAMBWANJI, "Raw karambwanji", "Karambwanji", true, "karambwanji");
		fish(ItemID.RAW_ANGLERFISH, "Raw anglerfish", "Anglerfish", false, "anglerfish");
		fish(ItemID.RAW_DARK_CRAB, "Raw dark crab", "Dark crab", false, "dark crab");
		fish(ItemID.MINNOW, "Minnow", "Minnows", true, "minnows", "minnow");
		fish(ItemID.BRUT_SPAWNING_TROUT, "Leaping trout", "Leaping trout", false, "leaping trout");
		fish(ItemID.BRUT_SPAWNING_SALMON, "Leaping salmon", "Leaping salmon", false, "leaping salmon");
		fish(ItemID.BRUT_STURGEON, "Leaping sturgeon", "Leaping sturgeon", false, "leaping sturgeon");

		SUPPLY_NAMES.put(ItemID.FISHING_BAIT, "Fishing bait");
		SUPPLY_NAMES.put(ItemID.FEATHER, "Feathers");
		SUPPLY_NAMES.put(ItemID.TBWT_RAW_KARAMBWANJI, "Karambwanji");
		SUPPLY_NAMES.put(ItemID.PISCARILIUS_SANDWORMS, "Sandworms");
		SUPPLY_NAMES.put(ItemID.WILDERNESS_FISHING_BAIT, "Dark fishing bait");
		SUPPLY_NAMES.put(ItemID.BRUT_FISH_CUTS, "Fish offcuts");

		// Longest names first so "leaping trout" wins over "trout", "karambwanji" over "karambwan".
		List<Map.Entry<String, Integer>> byLength = new ArrayList<>(FISH_BY_NAME.entrySet());
		byLength.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
		FISH_BY_NAME.clear();
		for (Map.Entry<String, Integer> e : byLength)
		{
			FISH_BY_NAME.put(e.getKey(), e.getValue());
		}
	}

	private static void fish(int itemId, String name, String shortName, boolean stackable, String... lineNames)
	{
		RESOURCES.put(itemId, new SkillResource(itemId, name, shortName, SkillResource.Role.PRIMARY, null, stackable));
		for (String n : lineNames)
		{
			FISH_BY_NAME.put(n, itemId);
		}
	}

	/**
	 * The fish barrel family (OSRS Wiki, Fish barrel / Fish sack barrel): an
	 * OPEN barrel catches raw fish straight from the spot; the fish barrel holds
	 * 28. The fish sack barrel's total is not verified here, so it never claims
	 * a capacity. Stackable catches (karambwanji, minnows) never go in.
	 */
	public static final List<SkillStorage.Kind> BARREL_KINDS = Collections.unmodifiableList(Arrays.asList(
		new SkillStorage.Kind("Fish barrel", ItemID.FISH_BARREL_CLOSED, ItemID.FISH_BARREL_OPEN, 28,
			FishingCompanionDefinition::barrelAccepts),
		new SkillStorage.Kind("Fish sack barrel", ItemID.FISH_SACK_BARREL_CLOSED, ItemID.FISH_SACK_BARREL_OPEN, null,
			FishingCompanionDefinition::barrelAccepts)));

	public static SkillStorage createBarrel()
	{
		return new SkillStorage(BARREL_KINDS);
	}

	/** Raw, non-stackable Fishing catches (what a barrel takes). */
	public static boolean barrelAccepts(int itemId)
	{
		SkillResource r = RESOURCES.get(itemId);
		return r != null && !r.isStackable();
	}

	private FishingCompanionDefinition()
	{
	}

	// ---- native messages -----------------------------------------------------------------

	/** "You catch a lobster." / "You catch some shrimps." / "You catch an Anglerfish." / "You catch a shark!" / "You catch 7 Karambwanji." */
	private static final Pattern CATCH = Pattern.compile("^You catch (?:a |an |some |\\d+ )?([A-Za-z' ]+?)[.!]$", Pattern.CASE_INSENSITIVE);
	static final String FLYING_FISH = "A flying fish jumps up and eats some of your minnows";

	/** The fish a catch line names, or null for any other line. */
	public static Integer caughtFish(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = CATCH.matcher(message.trim());
		if (!m.matches())
		{
			return null;
		}
		String name = m.group(1).trim().toLowerCase(Locale.ROOT);
		Integer exact = FISH_BY_NAME.get(name);
		if (exact != null)
		{
			return exact;
		}
		for (Map.Entry<String, Integer> e : FISH_BY_NAME.entrySet())
		{
			// "raw shark", "huge lobster": the fish's own name, longest first.
			if (name.endsWith(" " + e.getKey()))
			{
				return e.getValue();
			}
		}
		return null;
	}

	/** The barrel says it is full (text checked loosely: "...barrel is full" / "...barrel is too full"). */
	public static boolean isBarrelFull(String message)
	{
		if (message == null)
		{
			return false;
		}
		String m = message.toLowerCase(Locale.ROOT);
		return m.contains("barrel") && m.contains("full") && !m.startsWith("you catch");
	}

	public static boolean isFlyingFish(String message)
	{
		return message != null && message.trim().startsWith(FLYING_FISH);
	}

	/** A fishing catch of this skill (production), never a supply or anything else. */
	public static boolean isFish(Integer itemId)
	{
		return itemId != null && RESOURCES.containsKey(itemId);
	}

	/** The SUPPLIES caption for a supply item ("Feathers"), or null when it is not a Fishing supply. */
	public static String supplyName(int itemId)
	{
		return SUPPLY_NAMES.get(itemId);
	}

	// ---- SkillCompanionDefinition ----------------------------------------------------------

	@Override
	public String getSkillKey()
	{
		return SKILL_KEY;
	}

	@Override
	public Skill getSkill()
	{
		return Skill.FISHING;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
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
		return itemId == null ? null : RESOURCES.get(itemId);
	}

	@Override
	public boolean tripMatters(String methodKey)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
		return m != null && m.isTripMatters();
	}

	/** Fishing has no by-product families. */
	@Override
	public boolean groupIsPrimary(String methodKey, String group)
	{
		return false;
	}

	/**
	 * The method's own caption ("LOBSTERS", "KARAMBWAN", "FISH CAUGHT" for a
	 * multi-fish technique) when every main fish belongs to one method;
	 * "FISH CAUGHT" when several methods' fish mix in one session.
	 */
	@Override
	public String productionCaption(String methodKey, List<SkillResource> mainResources)
	{
		if (mainResources == null || mainResources.isEmpty())
		{
			return "TOTAL";
		}
		FishingMethod common = null;
		for (SkillResource r : mainResources)
		{
			FishingMethod m = FishingMethod.catching(r.getItemId());
			if (m == null || (common != null && common != m))
			{
				return "FISH CAUGHT";
			}
			common = m;
		}
		return common.getProductionCaption();
	}

	@Override
	public String rateNoun(String methodKey)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
		return m == null ? null : m.getRateNoun();
	}

	@Override
	public String producedVerb()
	{
		return "caught";
	}

	@Override
	public String producedTitle()
	{
		return "Caught";
	}

	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
		List<SkillResource> out = new ArrayList<>();
		if (m == null)
		{
			return out;
		}
		for (int id : m.getCatches())
		{
			SkillResource r = RESOURCES.get(id);
			if (r != null)
			{
				out.add(r);
			}
		}
		return out;
	}

	/** The barrel line whenever the method's catch can go in a barrel (the tracker only reports a barrel that is carried). */
	@Override
	public boolean storageRelevant(String methodKey, Set<Integer> producedItems)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
		if (m != null)
		{
			return barrelMethod(m);
		}
		for (Integer id : producedItems)
		{
			if (id != null && barrelAccepts(id))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean storageCatchesMain(String methodKey)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
		return m != null && barrelMethod(m);
	}

	private static boolean barrelMethod(FishingMethod m)
	{
		for (int id : m.getCatches())
		{
			if (barrelAccepts(id))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean methodProduces(String methodKey, int itemId)
	{
		FishingMethod m = FishingMethod.fromKey(methodKey);
		return m != null && FishingMethod.catching(itemId) == m;
	}

	/** Barbarian fishing: every catch also grants Agility and Strength XP. */
	@Override
	public boolean methodNeedsOwnershipEvidence(String methodKey)
	{
		return FishingMethod.BARBARIAN.getKey().equals(methodKey);
	}

	@Override
	public String storageTooltip()
	{
		return "Fish your open barrel caught go there instead of your inventory. Its contents become known "
			+ "once it is emptied into the bank (Spyglass never guesses what was in it before)";
	}

	@Override
	public String stockpileEmptyHint()
	{
		return "Catch something first to pick what to stockpile";
	}

	/** Every resource (presentation / tests). */
	public static Map<Integer, SkillResource> resources()
	{
		return Collections.unmodifiableMap(RESOURCES);
	}
}
