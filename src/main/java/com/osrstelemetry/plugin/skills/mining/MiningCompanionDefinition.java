package com.osrstelemetry.plugin.skills.mining;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Mining's vocabulary for the generic skill companion: which items Mining
 * PRODUCES (with role and group), method names and wording, and the native
 * game messages the Mining tracker reads.
 *
 * <h3>Skill vs activity (classified by PLAYER LOOP, not "gives Mining XP")</h3>
 * <ul>
 * <li>NORMAL methods (Mining companion): rock -> resource -> inventory ->
 * bank or drop. Clay .. runite, blurite, lead, nickel, lovakite, amethyst,
 * granite, sandstone, Daeyalt essence.</li>
 * <li>SPECIALIZED methods (companion + method behaviour): Gem rocks (gems are
 * the product; gem bag / sack routing), Calcified rocks (stackable bone
 * shards, rare deposits, the water cycle).</li>
 * <li>Deferred here, each for a reason: Motherlode Mine (pay-dirt -> hopper
 * -> sack -> ores is a processing pipeline: a future method adapter with its
 * own sack state); shooting stars (stackable stardust, star tier / layer
 * from varbits -- a likely adapter, not first pass); Blast Mine, Volcanic
 * Mine, Zalcano (their own structured-activity loops: rounds, teams,
 * rewards); Camdozaal barronite and Wyrmscraig sunstone (crush / momentum
 * mechanics not audited yet).</li>
 * </ul>
 *
 * <h3>Native messages</h3>
 * "You just found a Sapphire!" (the mining gem table; confirmed by
 * RuneLite's own Motherlode plugin, which reads the same lines) carries a
 * gem's identity even when it never reaches the inventory. "You put it
 * straight into your open gem bag." (OSRS Wiki, Gem bag) says it went to
 * storage. The success lines "You manage to mine some iron." / "You manage
 * to quarry some granite." name the method. Calcified rocks: "Some cracks
 * around the cave begin to ooze water." (the water cycle, +15% success) and
 * the hop debuff "Your pickaxe keeps slipping on the rocks ...".
 */
public final class MiningCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "mining";
	public static final String GEM_GROUP = "gem";

	/** A gem that went straight into storage with no message naming it (identity unknown): counted, never guessed. */
	public static final int UNIDENTIFIED_GEM = -1;
	public static final String UNIDENTIFIED_GEM_NAME = "Uncut gem";

	public static final MiningCompanionDefinition INSTANCE = new MiningCompanionDefinition();

	private static final Map<Integer, SkillResource> RESOURCES = new LinkedHashMap<>();
	private static final Map<String, Integer> GEMS_BY_NAME = new LinkedHashMap<>();
	private static final Map<String, MiningMethod> METHOD_BY_NOUN = new LinkedHashMap<>();

	static
	{
		primary(ItemID.CLAY, "Clay", "Clay", false);
		primary(ItemID.SOFTCLAY, "Soft clay", "Soft clay", false);
		primary(ItemID.COPPER_ORE, "Copper ore", "Copper", false);
		primary(ItemID.TIN_ORE, "Tin ore", "Tin", false);
		primary(ItemID.IRON_ORE, "Iron ore", "Iron", false);
		primary(ItemID.SILVER_ORE, "Silver ore", "Silver", false);
		primary(ItemID.COAL, "Coal", "Coal", false);
		primary(ItemID.GOLD_ORE, "Gold ore", "Gold", false);
		primary(ItemID.MITHRIL_ORE, "Mithril ore", "Mithril", false);
		primary(ItemID.ADAMANTITE_ORE, "Adamantite ore", "Adamantite", false);
		primary(ItemID.RUNITE_ORE, "Runite ore", "Runite", false);
		primary(ItemID.BLURITE_ORE, "Blurite ore", "Blurite", false);
		primary(ItemID.LEAD_ORE, "Lead ore", "Lead", false);
		primary(ItemID.NICKEL_ORE, "Nickel ore", "Nickel", false);
		primary(ItemID.LOVAKITE_ORE, "Lovakite ore", "Lovakite", false);
		primary(ItemID.BLANKRUNE_DAEYALT, "Daeyalt essence", "Daeyalt", false);
		primary(ItemID.AMETHYST, "Amethyst", "Amethyst", false);
		primary(ItemID.ENAKH_GRANITE_TINY, "Granite (500g)", "Granite 500g", false);
		primary(ItemID.ENAKH_GRANITE_SMALL, "Granite (2kg)", "Granite 2kg", false);
		primary(ItemID.ENAKH_GRANITE_MEDIUM, "Granite (5kg)", "Granite 5kg", false);
		primary(ItemID.ENAKH_SANDSTONE_TINY, "Sandstone (1kg)", "Sandstone 1kg", false);
		primary(ItemID.ENAKH_SANDSTONE_SMALL, "Sandstone (2kg)", "Sandstone 2kg", false);
		primary(ItemID.ENAKH_SANDSTONE_MEDIUM, "Sandstone (5kg)", "Sandstone 5kg", false);
		primary(ItemID.ENAKH_SANDSTONE_LARGE, "Sandstone (10kg)", "Sandstone 10kg", false);
		primary(ItemID.BLESSED_BONE_SHARD, "Blessed bone shards", "Bone shards", true);
		RESOURCES.put(ItemID.CALCIFIED_DEPOSIT, new SkillResource(ItemID.CALCIFIED_DEPOSIT, "Calcified deposit", "Deposits",
			SkillResource.Role.SECONDARY, null, false));

		gem(ItemID.UNCUT_OPAL, "Uncut opal", "Opal");
		gem(ItemID.UNCUT_JADE, "Uncut jade", "Jade");
		gem(ItemID.UNCUT_RED_TOPAZ, "Uncut red topaz", "Red topaz");
		gem(ItemID.UNCUT_SAPPHIRE, "Uncut sapphire", "Sapphire");
		gem(ItemID.UNCUT_EMERALD, "Uncut emerald", "Emerald");
		gem(ItemID.UNCUT_RUBY, "Uncut ruby", "Ruby");
		gem(ItemID.UNCUT_DIAMOND, "Uncut diamond", "Diamond");
		gem(ItemID.UNCUT_DRAGONSTONE, "Uncut dragonstone", "Dragonstone");
		RESOURCES.put(UNIDENTIFIED_GEM, new SkillResource(UNIDENTIFIED_GEM, UNIDENTIFIED_GEM_NAME, "Unidentified",
			SkillResource.Role.SECONDARY, GEM_GROUP, false));

		METHOD_BY_NOUN.put("soft clay", MiningMethod.CLAY);
		METHOD_BY_NOUN.put("clay", MiningMethod.CLAY);
		METHOD_BY_NOUN.put("copper", MiningMethod.COPPER);
		METHOD_BY_NOUN.put("tin", MiningMethod.TIN);
		METHOD_BY_NOUN.put("iron", MiningMethod.IRON);
		METHOD_BY_NOUN.put("silver", MiningMethod.SILVER);
		METHOD_BY_NOUN.put("coal", MiningMethod.COAL);
		METHOD_BY_NOUN.put("gold", MiningMethod.GOLD);
		METHOD_BY_NOUN.put("mithril", MiningMethod.MITHRIL);
		METHOD_BY_NOUN.put("adamantite", MiningMethod.ADAMANTITE);
		METHOD_BY_NOUN.put("runite", MiningMethod.RUNITE);
		METHOD_BY_NOUN.put("blurite", MiningMethod.BLURITE);
		METHOD_BY_NOUN.put("lead", MiningMethod.LEAD);
		METHOD_BY_NOUN.put("nickel", MiningMethod.NICKEL);
		METHOD_BY_NOUN.put("lovakite", MiningMethod.LOVAKITE);
		METHOD_BY_NOUN.put("amethyst", MiningMethod.AMETHYST);
		METHOD_BY_NOUN.put("granite", MiningMethod.GRANITE);
		METHOD_BY_NOUN.put("sandstone", MiningMethod.SANDSTONE);
	}

	private static void primary(int itemId, String name, String shortName, boolean stackable)
	{
		RESOURCES.put(itemId, new SkillResource(itemId, name, shortName, SkillResource.Role.PRIMARY, null, stackable));
	}

	private static void gem(int itemId, String name, String shortName)
	{
		RESOURCES.put(itemId, new SkillResource(itemId, name, shortName, SkillResource.Role.SECONDARY, GEM_GROUP, false));
		GEMS_BY_NAME.put(shortName.toLowerCase(Locale.ROOT), itemId);
	}

	private MiningCompanionDefinition()
	{
	}

	// ---- native messages -----------------------------------------------------------------

	/** "You just found a Sapphire!" (gem table) / "You just mined an Opal!" (gem rocks, if worded so). */
	private static final Pattern GEM_FOUND = Pattern.compile("^You just (?:found|mined) (?:a|an) ([A-Za-z ]+?)!$");
	/** "You manage to mine some iron." / "You manage to quarry some granite." */
	private static final Pattern SUCCESS = Pattern.compile("^You manage to (?:mine|quarry) some ([A-Za-z ]+?)\\.$");
	static final String STORAGE_ROUTED_PREFIX = "You put it straight into your open gem ";
	static final String STORAGE_FILLED_PREFIX = "You add the gem";
	static final String WATER_STARTS = "Some cracks around the cave begin to ooze water.";
	static final String SLIPPING_PREFIX = "Your pickaxe keeps slipping on the rocks";

	/** The gem a "You just found / mined ..." line names, or null. */
	public static Integer gemFound(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = GEM_FOUND.matcher(message.trim());
		return m.matches() ? GEMS_BY_NAME.get(m.group(1).trim().toLowerCase(Locale.ROOT)) : null;
	}

	/** True for "You just mined a ...!" (a gem-rock success), false for the gem table's "You just found a ...!". */
	public static boolean isGemMinedLine(String message)
	{
		return message != null && message.trim().startsWith("You just mined ");
	}

	/** The method a success line names, or null. */
	public static MiningMethod successMethod(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = SUCCESS.matcher(message.trim());
		return m.matches() ? METHOD_BY_NOUN.get(m.group(1).trim().toLowerCase(Locale.ROOT)) : null;
	}

	public static boolean isStorageRouted(String message)
	{
		return message != null && message.startsWith(STORAGE_ROUTED_PREFIX);
	}

	public static boolean isStorageFilled(String message)
	{
		return message != null && message.startsWith(STORAGE_FILLED_PREFIX);
	}

	public static boolean isWaterStart(String message)
	{
		return WATER_STARTS.equals(message == null ? null : message.trim());
	}

	public static boolean isSlipping(String message)
	{
		return message != null && message.startsWith(SLIPPING_PREFIX);
	}

	public static boolean isGem(Integer itemId)
	{
		SkillResource r = itemId == null ? null : RESOURCES.get(itemId);
		return r != null && GEM_GROUP.equals(r.getGroup());
	}

	public static Integer gemByName(String shortName)
	{
		return shortName == null ? null : GEMS_BY_NAME.get(shortName.trim().toLowerCase(Locale.ROOT));
	}

	/** All gems, highest-value last (presentation order is value-ascending like the wiki table). */
	public static List<Integer> gems()
	{
		return new ArrayList<>(GEMS_BY_NAME.values());
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
		return Skill.MINING;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		MiningMethod m = MiningMethod.fromKey(methodKey);
		if (m != null)
		{
			return m.getLabel();
		}
		if (methodKey == null || methodKey.isEmpty())
		{
			return null;
		}
		// A key from a newer / older version: still readable.
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
		MiningMethod m = MiningMethod.fromKey(methodKey);
		return m != null && m.isTripMatters();
	}

	@Override
	public boolean groupIsPrimary(String methodKey, String group)
	{
		return GEM_GROUP.equals(group) && MiningMethod.GEM_ROCKS.getKey().equals(methodKey);
	}

	@Override
	public String productionCaption(String methodKey, List<SkillResource> mainResources)
	{
		if (mainResources == null || mainResources.isEmpty())
		{
			return "TOTAL";
		}
		if (GEM_GROUP.equals(mainResources.get(0).getGroup()))
		{
			return MiningMethod.GEM_ROCKS.getProductionCaption();
		}
		MiningMethod common = null;
		for (SkillResource r : mainResources)
		{
			MiningMethod m = MiningMethod.producing(r.getItemId());
			if (m == null || (common != null && common != m))
			{
				return "TOTAL";
			}
			common = m;
		}
		return common.getProductionCaption();
	}

	@Override
	public String rateNoun(String methodKey)
	{
		MiningMethod m = MiningMethod.fromKey(methodKey);
		return m == null ? null : m.getRateNoun();
	}

	@Override
	public String producedVerb()
	{
		return "mined";
	}

	@Override
	public String producedTitle()
	{
		return "Mined";
	}

	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		MiningMethod m = MiningMethod.fromKey(methodKey);
		List<SkillResource> out = new ArrayList<>();
		if (m == null)
		{
			return out;
		}
		if (m == MiningMethod.GEM_ROCKS)
		{
			for (Integer gem : GEMS_BY_NAME.values())
			{
				out.add(RESOURCES.get(gem));
			}
			return out;
		}
		for (int id : m.getPrimaryItems())
		{
			SkillResource r = RESOURCES.get(id);
			if (r != null)
			{
				out.add(r);
			}
		}
		if (m == MiningMethod.CALCIFIED)
		{
			out.add(RESOURCES.get(ItemID.CALCIFIED_DEPOSIT));
		}
		return out;
	}

	/** The gem bag line only when gems matter: gem rocks, or this session produced gems. */
	@Override
	public boolean storageRelevant(String methodKey, java.util.Set<Integer> producedItems)
	{
		if (groupIsPrimary(methodKey, GEM_GROUP))
		{
			return true;
		}
		for (Integer id : producedItems)
		{
			if (isGem(id))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean storageCatchesMain(String methodKey)
	{
		return groupIsPrimary(methodKey, GEM_GROUP);
	}

	@Override
	public String storageTooltip()
	{
		return "Gems your open container caught go there instead of your inventory. Its contents become known "
			+ "once it is emptied (Spyglass never guesses what was in it before)";
	}

	@Override
	public String stockpileEmptyHint()
	{
		return "Mine something first to pick what to stockpile";
	}

	/** Every resource (presentation / tests). */
	public static Map<Integer, SkillResource> resources()
	{
		return Collections.unmodifiableMap(RESOURCES);
	}
}
