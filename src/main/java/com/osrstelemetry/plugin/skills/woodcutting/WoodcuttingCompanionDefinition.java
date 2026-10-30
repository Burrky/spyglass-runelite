package com.osrstelemetry.plugin.skills.woodcutting;

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
 * Woodcutting's vocabulary for the generic skill companion -- the SAME
 * chassis as Mining / Fishing / Agility (ProductionLedger, MethodTracker,
 * SkillStorage, SkillBanking, RateWindow, SkillCompanionCollector, the goal
 * planner and the panel model). Nothing here is a second production system.
 *
 * <h3>Resources</h3>
 * <ul>
 * <li>PRIMARY: each tree's product (WoodcuttingMethod) -- logs; hollow-tree
 * bark; sulliuscep mushrooms, mort myre fungus and the rare cap.</li>
 * <li>SECONDARY, each family in a section of its own (never folded into the
 * log count): bird nests (every variant kept exactly), anima-infused bark
 * (Forestry's currency; stackable, stockpile-capable), leaves (Forestry
 * sapling / ent rewards, exact tier). Sulliuscep fossils are a by-product
 * line.</li>
 * </ul>
 *
 * <h3>Native messages (OSRS; also the patterns RuneLite's own Woodcutting
 * plugin reads -- studied, never used at runtime)</h3>
 * "You get some yew logs." / "You get some mushrooms." on a cut; "A bird's
 * nest falls out of the tree." when a nest drops to the ground; "You've
 * been awarded &lt;col=...&gt;12 Anima-infused bark&lt;/col&gt;." for Forestry bark.
 * An infernal axe's auto-burn prints no line of its own: it is proven from
 * Woodcutting + Firemaking XP on the same tick with no log reaching the
 * inventory while a charged infernal axe is carried.
 */
public final class WoodcuttingCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "woodcutting";

	public static final WoodcuttingCompanionDefinition INSTANCE = new WoodcuttingCompanionDefinition();

	public static final String GROUP_NEST = "nest";
	public static final String GROUP_BARK = "bark";
	public static final String GROUP_LEAF = "leaf";
	public static final String GROUP_FOSSIL = "fossil";

	private static final Map<Integer, SkillResource> RESOURCES = new LinkedHashMap<>();
	/** Lower-case product names as cut lines spell them ("yew logs", "mushrooms"). */
	private static final Map<String, Integer> CUT_NAMES = new LinkedHashMap<>();

	/** Every item that is a charged, burning infernal axe (infernal axe, its (or) and Trailblazer Reloaded versions). */
	public static final Set<Integer> BURNING_AXES = Collections.unmodifiableSet(new java.util.HashSet<>(Arrays.asList(
		ItemID.INFERNAL_AXE, ItemID.TRAILBLAZER_AXE, ItemID.TRAILBLAZER_RELOADED_AXE)));

	static
	{
		primary(ItemID.LOGS, "Logs", "Logs", "logs");
		primary(ItemID.OAK_LOGS, "Oak logs", "Oak", "oak logs");
		primary(ItemID.WILLOW_LOGS, "Willow logs", "Willow", "willow logs");
		primary(ItemID.TEAK_LOGS, "Teak logs", "Teak", "teak logs");
		primary(ItemID.JUNIPER_LOGS, "Juniper logs", "Juniper", "juniper logs");
		primary(ItemID.MAPLE_LOGS, "Maple logs", "Maple", "maple logs");
		primary(ItemID.MAHOGANY_LOGS, "Mahogany logs", "Mahogany", "mahogany logs");
		primary(ItemID.ARCTIC_PINE_LOG, "Arctic pine logs", "Arctic pine", "arctic pine logs", "arctic pine log");
		primary(ItemID.ACHEY_TREE_LOGS, "Achey tree logs", "Achey", "achey tree logs", "achey logs");
		primary(ItemID.HOLLOW_BARK, "Bark", "Bark", "bark");
		primary(ItemID.YEW_LOGS, "Yew logs", "Yew", "yew logs");
		primary(ItemID.BLISTERWOOD_LOGS, "Blisterwood logs", "Blisterwood", "blisterwood logs");
		primary(ItemID.MAGIC_LOGS, "Magic logs", "Magic", "magic logs");
		primary(ItemID.REDWOOD_LOGS, "Redwood logs", "Redwood", "redwood logs");
		primary(ItemID.BITTERCAP_MUSHROOM, "Mushroom", "Mushrooms", "mushrooms", "mushroom");
		primary(ItemID.MORTMYREMUSHROOM, "Mort myre fungus", "Fungus", "mort myre fungus");
		primary(ItemID.FOSSIL_SULLIUSCEP_CAP, "Sulliuscep cap", "Sulliuscep cap", "sulliuscep cap");
		primary(ItemID.JATOBA_LOGS, "Jatoba logs", "Jatoba", "jatoba logs");
		primary(ItemID.CAMPHOR_LOGS, "Camphor logs", "Camphor", "camphor logs");
		primary(ItemID.IRONWOOD_LOGS, "Ironwood logs", "Ironwood", "ironwood logs");
		primary(ItemID.ROSEWOOD_LOGS, "Rosewood logs", "Rosewood", "rosewood logs");

		secondary(ItemID.BIRD_NEST_EGG_RED, "Bird nest (red egg)", "Red egg", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_EGG_GREEN, "Bird nest (green egg)", "Green egg", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_EGG_BLUE, "Bird nest (blue egg)", "Blue egg", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_SEEDS, "Bird nest (seeds)", "Seeds", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_SEEDS_JAN2019, "Bird nest (seeds)", "Seeds", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_CHEAPSEEDS, "Bird nest (seeds)", "Seeds", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_DECENTSEEDS, "Bird nest (wyson seeds)", "Wyson seeds", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_DECENTSEEDS_JAN2019, "Bird nest (wyson seeds)", "Wyson seeds", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_RING, "Bird nest (ring)", "Ring", GROUP_NEST, false);
		secondary(ItemID.BIRD_NEST_EMPTY, "Bird nest (empty)", "Empty", GROUP_NEST, false);
		secondary(ItemID.WC_CLUE_NEST_BEGINNER, "Clue nest (beginner)", "Clue (beginner)", GROUP_NEST, false);
		secondary(ItemID.WC_CLUE_NEST_EASY, "Clue nest (easy)", "Clue (easy)", GROUP_NEST, false);
		secondary(ItemID.WC_CLUE_NEST_MEDIUM, "Clue nest (medium)", "Clue (medium)", GROUP_NEST, false);
		secondary(ItemID.WC_CLUE_NEST_HARD, "Clue nest (hard)", "Clue (hard)", GROUP_NEST, false);
		secondary(ItemID.WC_CLUE_NEST_ELITE, "Clue nest (elite)", "Clue (elite)", GROUP_NEST, false);

		secondary(ItemID.FORESTRY_CURRENCY, "Anima-infused bark", "Bark", GROUP_BARK, true);

		secondary(ItemID.LEAVES, "Leaves", "Normal", GROUP_LEAF, true);
		secondary(ItemID.LEAVES_OAK, "Oak leaves", "Oak", GROUP_LEAF, true);
		secondary(ItemID.LEAVES_WILLOW, "Willow leaves", "Willow", GROUP_LEAF, true);
		secondary(ItemID.LEAVES_MAPLE, "Maple leaves", "Maple", GROUP_LEAF, true);
		secondary(ItemID.LEAVES_YEW, "Yew leaves", "Yew", GROUP_LEAF, true);
		secondary(ItemID.LEAVES_MAGIC, "Magic leaves", "Magic", GROUP_LEAF, true);

		secondary(ItemID.FOSSIL_NUMULITE, "Numulite", "Numulite", GROUP_FOSSIL, true);
		secondary(ItemID.FOSSIL_SMALL_UNID, "Unidentified small fossil", "Small", GROUP_FOSSIL, false);
		secondary(ItemID.FOSSIL_MEDIUM_UNID, "Unidentified medium fossil", "Medium", GROUP_FOSSIL, false);
		secondary(ItemID.FOSSIL_LARGE_UNID, "Unidentified large fossil", "Large", GROUP_FOSSIL, false);
		secondary(ItemID.FOSSIL_RARE_UNID, "Unidentified rare fossil", "Rare", GROUP_FOSSIL, false);
	}

	private static void primary(int itemId, String name, String shortName, String... cutNames)
	{
		RESOURCES.put(itemId, new SkillResource(itemId, name, shortName, SkillResource.Role.PRIMARY, null, false));
		for (String n : cutNames)
		{
			CUT_NAMES.put(n, itemId);
		}
	}

	private static void secondary(int itemId, String name, String shortName, String group, boolean stackable)
	{
		RESOURCES.put(itemId, new SkillResource(itemId, name, shortName, SkillResource.Role.SECONDARY, group, stackable));
	}

	/**
	 * Log baskets (OSRS Wiki, Log basket / Forestry basket): an OPEN basket
	 * catches logs straight from the tree; each holds 28 logs. Bark,
	 * mushrooms, nests and leaves never go in.
	 */
	public static final List<SkillStorage.Kind> BASKET_KINDS = Collections.unmodifiableList(Arrays.asList(
		new SkillStorage.Kind("Log basket", ItemID.LOG_BASKET_CLOSED, ItemID.LOG_BASKET_OPEN, 28,
			WoodcuttingCompanionDefinition::basketAccepts),
		new SkillStorage.Kind("Forestry basket", ItemID.FORESTRY_BASKET_CLOSED, ItemID.FORESTRY_BASKET_OPEN, 28,
			WoodcuttingCompanionDefinition::basketAccepts)));

	public static SkillStorage createBasket()
	{
		return new SkillStorage(BASKET_KINDS);
	}

	/** Logs (a primary product named "... logs"), never bark, mushrooms or anything secondary. */
	public static boolean basketAccepts(int itemId)
	{
		SkillResource r = RESOURCES.get(itemId);
		return r != null && r.getRole() == SkillResource.Role.PRIMARY && r.getName().toLowerCase(Locale.ROOT).contains("log");
	}

	private WoodcuttingCompanionDefinition()
	{
	}

	// ---- native messages -------------------------------------------------------------------------

	/** "You get some yew logs." / "You get some mushrooms." / "You get an arctic pine log." */
	private static final Pattern CUT = Pattern.compile("^You get (?:some |an |a )?([A-Za-z' -]+?)\\.$", Pattern.CASE_INSENSITIVE);
	private static final Pattern BARK_AWARD = Pattern.compile("You've been awarded (\\d[\\d,]*) Anima-infused bark", Pattern.CASE_INSENSITIVE);
	static final String NEST_FALLS = "A bird's nest falls out of the tree";

	/** The product a cut line names, or null for any other line. */
	public static Integer cutProduct(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = CUT.matcher(message.trim());
		if (!m.matches())
		{
			return null;
		}
		return CUT_NAMES.get(m.group(1).trim().toLowerCase(Locale.ROOT));
	}

	/** The bark quantity a Forestry award line states, or null. */
	public static Long barkAwarded(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = BARK_AWARD.matcher(message);
		return m.find() ? Long.parseLong(m.group(1).replace(",", "")) : null;
	}

	public static boolean isNestFalling(String message)
	{
		return message != null && message.trim().startsWith(NEST_FALLS);
	}

	public static boolean isPrimary(Integer itemId)
	{
		SkillResource r = itemId == null ? null : RESOURCES.get(itemId);
		return r != null && r.getRole() == SkillResource.Role.PRIMARY;
	}

	public static boolean inGroup(Integer itemId, String group)
	{
		SkillResource r = itemId == null ? null : RESOURCES.get(itemId);
		return r != null && group.equals(r.getGroup());
	}

	// ---- SkillCompanionDefinition ----------------------------------------------------------------

	@Override
	public String getSkillKey()
	{
		return SKILL_KEY;
	}

	@Override
	public Skill getSkill()
	{
		return Skill.WOODCUTTING;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
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

	/** Every tree fills the inventory (no stackable main product). */
	@Override
	public boolean tripMatters(String methodKey)
	{
		return WoodcuttingMethod.fromKey(methodKey) != null;
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
		WoodcuttingMethod common = null;
		for (SkillResource r : mainResources)
		{
			WoodcuttingMethod m = WoodcuttingMethod.producing(r.getItemId());
			if (m == null || (common != null && common != m))
			{
				return "LOGS CUT";
			}
			common = m;
		}
		return common.getProductionCaption();
	}

	@Override
	public String rateNoun(String methodKey)
	{
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
		if (m == null)
		{
			return null;
		}
		return m == WoodcuttingMethod.SULLIUSCEP ? "mushrooms" : m == WoodcuttingMethod.HOLLOW ? "bark" : "logs";
	}

	/** Each log its own sprite row: what this session cut and what of it reached the bank. */
	@Override
	public List<String> productionColumns(String methodKey)
	{
		return java.util.Arrays.asList("CUT", "BANKED");
	}

	@Override
	public String producedVerb()
	{
		return "cut";
	}

	@Override
	public String producedTitle()
	{
		return "Cut";
	}

	/** The tree's own products (logs first), then anima-infused bark (Forestry's currency is worth stockpiling too). */
	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		List<SkillResource> out = new ArrayList<>();
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
		if (m == null)
		{
			return out;
		}
		for (int id : m.getProducts())
		{
			SkillResource r = RESOURCES.get(id);
			if (r != null)
			{
				out.add(r);
			}
		}
		out.add(RESOURCES.get(ItemID.FORESTRY_CURRENCY));
		return out;
	}

	@Override
	public boolean storageRelevant(String methodKey, Set<Integer> producedItems)
	{
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
		if (m != null)
		{
			return basketAccepts(m.getMainProduct());
		}
		for (Integer id : producedItems)
		{
			if (id != null && basketAccepts(id))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean storageCatchesMain(String methodKey)
	{
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
		return m != null && basketAccepts(m.getMainProduct());
	}

	@Override
	public String storageTooltip()
	{
		return "Logs your open basket caught go there instead of your inventory. Its contents become known "
			+ "once it is emptied into the bank (Spyglass never guesses what was in it before)";
	}

	@Override
	public boolean methodProduces(String methodKey, int itemId)
	{
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
		return m != null && WoodcuttingMethod.producing(itemId) == m;
	}

	@Override
	public String methodTitle()
	{
		return "Tree";
	}

	@Override
	public String stockpileEmptyHint()
	{
		return "Cut something first to pick what to stockpile";
	}

	@Override
	public String autoTransformedVerb()
	{
		return "auto-burned";
	}

	@Override
	public String focusTitle()
	{
		return "Current tree";
	}

	@Override
	public String focusCountTooltip()
	{
		return "What this tree gave you so far (logs an infernal axe burned included)";
	}

	@Override
	public String focusTimeTooltip()
	{
		return "Since this tree's first log";
	}

	@Override
	public String eventTitle()
	{
		return "Forestry event";
	}

	@Override
	public String autoTransformedTooltip()
	{
		return "Part of the total: burned by your infernal axe as they were cut (never in your inventory, never banked)";
	}

	@Override
	public String focusCountCaption(String methodKey)
	{
		WoodcuttingMethod m = WoodcuttingMethod.fromKey(methodKey);
		return m == WoodcuttingMethod.HOLLOW ? "BARK" : "LOGS";
	}

	@Override
	public String focusTimeCaption()
	{
		return "TREE TIME";
	}

	private static final List<Secondary> SECONDARIES = Collections.unmodifiableList(Arrays.asList(
		new Secondary(GROUP_NEST, "NESTS", "NESTS / HR", false,
			"Bird nests that fell from your trees (or a Friendly Ent you helped) and you picked up -- every variant is kept in History"),
		new Secondary(GROUP_BARK, "BARK EARNED", "BARK / HR", false,
			"Anima-infused bark the game awarded you for Forestry events"),
		new Secondary(GROUP_LEAF, "Leaves", null, true, "Leaves from Forestry events (exact tier)")));

	@Override
	public List<Secondary> secondaries()
	{
		return SECONDARIES;
	}

	@Override
	public String occurrenceLabel(String key)
	{
		ForestryEvent e = ForestryEvent.fromOccurrenceKey(key);
		return e == null ? null : e.getLabel();
	}

	@Override
	public String occurrencesTitle()
	{
		return "Forestry events";
	}

	@Override
	public boolean historyCountsPrimaryOnly()
	{
		return true;
	}

	@Override
	public boolean historyMethodProduction()
	{
		return true;
	}

	@Override
	public String groupTitle(String group)
	{
		if (GROUP_LEAF.equals(group))
		{
			return "Leaves";
		}
		if (GROUP_BARK.equals(group))
		{
			return "Anima-infused bark";
		}
		return SkillCompanionDefinition.super.groupTitle(group);
	}

	/** Every resource (presentation / tests). */
	public static Map<Integer, SkillResource> resources()
	{
		return Collections.unmodifiableMap(RESOURCES);
	}
}
