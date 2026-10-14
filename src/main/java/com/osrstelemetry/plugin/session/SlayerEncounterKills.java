package com.osrstelemetry.plugin.session;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.NpcID;

/**
 * ACTUAL-KILL roles of an owned NPC death during a Slayer task (pure data).
 * <p>
 * TASK UNITS (what advances / completes the Slayer task, decided only by the
 * game's own task count) and ACTUAL KILLS (every causally owned NPC death of
 * the Slayer encounter) are different things: a Nechryael task of 170 can
 * take 170 Nechryael, 64 Death spawn and a Nechryarch -- 235 actual kills.
 * An owned death counts here only when its NPC is, by EXACT identity:
 * <ul>
 * <li>{@link Role#SUPERIOR}: one of the game's superior Slayer creatures
 * (the gameval {@code SUPERIOR_*} NPC ids -- never inferred from level,
 * hitpoints, loot or name similarity). Superiors only ever appear while
 * their own task is being killed;</li>
 * <li>{@link Role#SUBORDINATE}: an NPC the task's monster (or its superior)
 * summons -- Death spawn and the Nechryarch's chaotic death spawn for
 * Nechryael. An actual kill that never steals the task's identity and is
 * never assumed to use a task unit;</li>
 * <li>{@link Role#TASK}: one of the task's own targets (the same family
 * table loot attribution and task affinity already use).</li>
 * </ul>
 * Anything else (a random NPC that died nearby) is never a kill here.
 */
public final class SlayerEncounterKills
{
	public enum Role
	{
		TASK,
		SUBORDINATE,
		SUPERIOR
	}

	/** Every superior Slayer creature in the gameval Spyglass builds against (main-game ids). */
	private static final Set<Integer> SUPERIOR_IDS;
	/** Summoned / subordinate NPC id -> its base Slayer task (normalized). */
	private static final Map<Integer, String> SUBORDINATE_IDS;
	/** Subordinates by exact name -> base task (an id the game may vary). */
	private static final Map<String, String> SUBORDINATE_NAMES;

	static
	{
		Set<Integer> s = new HashSet<>();
		for (int id : new int[]{
			NpcID.SUPERIOR_CRAWLING_HAND, NpcID.SUPERIOR_CAVE_CRAWLER, NpcID.SUPERIOR_CAVE_CRAWLER_ICE, NpcID.SUPERIOR_BANSHEE,
			NpcID.SUPERIOR_KOUREND_BANSHEE, NpcID.SUPERIOR_ROCKSLUG, NpcID.SUPERIOR_COCKATRICE, NpcID.SUPERIOR_PYREFIEND,
			NpcID.SUPERIOR_PYRELORD, NpcID.SUPERIOR_BASILISK, NpcID.SUPERIOR_BASILISK_KNIGHT, NpcID.SUPERIOR_INFERNAL_MAGE,
			NpcID.SUPERIOR_BLOODVELD, NpcID.SUPERIOR_KOUREND_BLOODVELD, NpcID.SUPERIOR_JELLY, NpcID.SUPERIOR_KOUREND_JELLY,
			NpcID.SUPERIOR_CHILLED_JELLY, NpcID.SUPERIOR_CAVE_HORROR, NpcID.SUPERIOR_ABBERANT_SPECTRE, NpcID.SUPERIOR_KOUREND_SPECTRE,
			NpcID.SUPERIOR_DUSTDEVIL, NpcID.SUPERIOR_KURASK, NpcID.SUPERIOR_SMOKE_DEVIL, NpcID.SUPERIOR_GARGOYLE,
			NpcID.SUPERIOR_GARGOYLE_DEAD, NpcID.SUPERIOR_DARK_BEAST, NpcID.SUPERIOR_ABYSSAL_DEMON, NpcID.SUPERIOR_NECHRYAEL,
			NpcID.SUPERIOR_TUROTH, NpcID.SUPERIOR_WYRM_DARK, NpcID.SUPERIOR_WYRM_LIGHT, NpcID.SUPERIOR_DRAKE,
			NpcID.SUPERIOR_DRAKE_DEATH, NpcID.SUPERIOR_HYDRA, NpcID.WILD_CAVE_SUPERIOR, NpcID.SUPERIOR_WARPED_TERRORBIRD,
			NpcID.SUPERIOR_WARPED_TORTOISE, NpcID.SUPERIOR_ARAXYTE, NpcID.SUPERIOR_CUSTODIAN, NpcID.SUPERIOR_GRYPHON,
			NpcID.SUPERIOR_AQUANITE, NpcID.SUPERIOR_AQUANITE_NOLURE, NpcID.SUPERIOR_LAVA_STRYKEWYRM, NpcID.SUPERIOR_VENATOR})
		{
			s.add(id);
		}
		SUPERIOR_IDS = Collections.unmodifiableSet(s);

		String nechryael = normalize("Nechryael");
		Map<Integer, String> sub = new HashMap<>();
		sub.put(NpcID.SLAYER_NECHRYAEL_SPAWN, nechryael);
		sub.put(NpcID.SUPERIOR_NECHRYAEL_MELEE_SPAWN, nechryael);
		sub.put(NpcID.SUPERIOR_NECHRYAEL_RANGED_SPAWN, nechryael);
		sub.put(NpcID.SUPERIOR_NECHRYAEL_MAGIC_SPAWN, nechryael);
		SUBORDINATE_IDS = Collections.unmodifiableMap(sub);
		SUBORDINATE_NAMES = Collections.singletonMap(normalize("Death spawn"), nechryael);
	}

	private SlayerEncounterKills()
	{
	}

	/**
	 * The role of an owned death of NPC {@code npcId} / {@code npcName} while
	 * {@code taskName} is the active Slayer task; null when it is not part of
	 * this task's encounter at all.
	 */
	public static Role role(String taskName, Integer npcId, String npcName)
	{
		if (taskName == null)
		{
			return null;
		}
		String task = normalize(taskName);
		if (npcId != null && SUPERIOR_IDS.contains(npcId))
		{
			return Role.SUPERIOR;
		}
		if (npcId != null && task.equals(SUBORDINATE_IDS.get(npcId)))
		{
			return Role.SUBORDINATE;
		}
		if (npcName == null || npcName.trim().isEmpty())
		{
			return null;
		}
		if (task.equals(SUBORDINATE_NAMES.get(normalize(npcName))))
		{
			return Role.SUBORDINATE;
		}
		return SlayerTaskFamilyRegistry.lootSourceCompatibility(taskName, npcName) == SlayerTaskFamilyRegistry.LootSourceCompatibility.MATCH
			? Role.TASK : null;
	}

	/** Whether {@code npcId} is (exactly) one of the game's superior Slayer creatures. */
	public static boolean isSuperior(Integer npcId)
	{
		return npcId != null && SUPERIOR_IDS.contains(npcId);
	}

	/** How many superior creatures the table knows (tests / report). */
	static int superiorCount()
	{
		return SUPERIOR_IDS.size();
	}

	private static String normalize(String s)
	{
		return s.trim().toLowerCase(Locale.ROOT);
	}
}
