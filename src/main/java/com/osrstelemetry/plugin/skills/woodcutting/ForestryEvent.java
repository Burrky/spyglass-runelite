package com.osrstelemetry.plugin.skills.woodcutting;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;

/**
 * The Forestry events (OSRS Wiki, Forestry; ids from RuneLite's native game
 * values -- never RuneLite's Woodcutting plugin), each recognised by the
 * NPCs / objects only that event spawns. An event is CONTEXT inside the
 * Woodcutting session: shown while it runs, counted once it ends if the
 * player took part, never a session of its own.
 *
 * {@code xpSkill}: the other skill whose XP the event itself hands out
 * (Struggling Sapling: Farming; Friendly Ent: Fletching; Beehive:
 * Construction; Poachers: Hunter; Pheasant Control: Thieving). While such an
 * event runs, that XP belongs to the Woodcutting session (ownership evidence
 * for the session engine).
 *
 * {@code counterCaption}: an event-specific count the player's own XP drops
 * prove one-for-one (Poachers: each disarmed trap gives Hunter XP; Pheasant
 * Control: each delivered egg gives Thieving XP); null where the wiki does
 * not tie that XP to one action (sapling mulch, ent pruning, beehive logs),
 * so nothing is guessed.
 */
public enum ForestryEvent
{
	RISING_ROOTS("Rising Roots", null, null, ids(), ids(ObjectID.GATHERING_EVENT_RISING_ROOTS, ObjectID.GATHERING_EVENT_RISING_ROOTS_SPECIAL)),
	STRUGGLING_SAPLING("Struggling Sapling", "FARMING", null,
		ids(NpcID.GATHERING_EVENT_SAPLING_NPC_HPBAR_1X1, NpcID.GATHERING_EVENT_SAPLING_NPC_HPBAR_2X2),
		ids(ObjectID.GATHERING_EVENT_SAPLING_1X1, ObjectID.GATHERING_EVENT_SAPLING_WITHERING_1X1, ObjectID.GATHERING_EVENT_SAPLING_SAVED_1X1,
			ObjectID.GATHERING_EVENT_SAPLING_2X2, ObjectID.GATHERING_EVENT_SAPLING_WITHERING_2X2, ObjectID.GATHERING_EVENT_SAPLING_SAVED_2X2,
			ObjectID.GATHERING_EVENT_SAPLING_3X3, ObjectID.GATHERING_EVENT_SAPLING_WITHERING_3X3, ObjectID.GATHERING_EVENT_SAPLING_SAVED_3X3,
			ObjectID.GATHERING_EVENT_SAPLING_4X4, ObjectID.GATHERING_EVENT_SAPLING_WITHERING_4X4, ObjectID.GATHERING_EVENT_SAPLING_SAVED_4X4,
			ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_1, ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_2,
			ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_3, ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4A,
			ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4B, ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4C,
			ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_5)),
	FLOWERING_BUSH("Flowering Bush", null, null,
		ids(NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL01, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL01,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL02, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL02,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL03, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL03,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL04, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL04,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL05, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL05,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL06, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL06,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL07, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL07,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_COL08, NpcID.GATHERING_EVENT_FLOWERING_TREE_BUSH_FRUITED_COL08,
			NpcID.GATHERING_EVENT_FLOWERING_TREE_BEES), ids()),
	LEPRECHAUN("Woodcutting Leprechaun", null, null, ids(NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN),
		ids(ObjectID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN_RAINBOW, ObjectID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN_CLOVER)),
	BEEHIVE("Beehive", "CONSTRUCTION", null,
		ids(NpcID.GATHERING_EVENT_BEES_BEEKEEPER, NpcID.GATHERING_EVENT_BEES_BEEBOX_1, NpcID.GATHERING_EVENT_BEES_BEEBOX_2,
			NpcID.GATHERING_EVENT_BEES_BEEBOX_3, NpcID.GATHERING_EVENT_BEES_BEEBOX_4),
		ids(ObjectID.GATHERING_EVENT_BEES_BEEHIVE01)),
	FRIENDLY_ENT("Friendly Ent", "FLETCHING", null,
		ids(NpcID.GATHERING_EVENT_ENTLINGS_NPC_01, NpcID.GATHERING_EVENT_ENTLINGS_NPC_01_PRUNED, NpcID.GATHERING_EVENT_ENT_STUMP_01,
			NpcID.GATHERING_EVENT_ENT_STUMP_02), ids()),
	POACHERS("Poachers", "HUNTER", "TRAPS",
		ids(NpcID.GATHERING_EVENT_POACHERS_TRAP, NpcID.GATHERING_EVENT_POACHERS_POACHER01_OUTDOORS,
			NpcID.GATHERING_EVENT_POACHERS_POACHER02_OUTDOORS, NpcID.GATHERING_EVENT_POACHERS_POACHER03_OUTDOORS,
			NpcID.GATHERING_EVENT_POACHERS_POACHER01_INDOORS, NpcID.GATHERING_EVENT_POACHERS_POACHER02_INDOORS,
			NpcID.GATHERING_EVENT_POACHERS_POACHER03_INDOORS, NpcID.GATHERING_EVENT_POACHERS_FOX_OUTDOORS,
			NpcID.GATHERING_EVENT_POACHERS_FOX_INDOORS), ids()),
	PHEASANT_CONTROL("Pheasant Control", "THIEVING", "EGGS",
		ids(NpcID.GATHERING_EVENT_PHEASANT_FORESTER, NpcID.GATHERING_EVENT_PHEASANT),
		ids(ObjectID.GATHERING_EVENT_PHEASANT_NEST01, ObjectID.GATHERING_EVENT_PHEASANT_NEST02)),
	ENCHANTMENT_RITUAL("Enchantment Ritual", null, null,
		ids(NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_DRYAD,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_1, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_2,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_3, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_4,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_1, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_2,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_3, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_4,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_1, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_2,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_3, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_4,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_1, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_2,
			NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_3, NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_4), ids());

	/** The occurrence key prefix in SKILL_PRODUCTION ("forestry:rising_roots"). */
	public static final String OCCURRENCE_PREFIX = "forestry:";

	private final String label;
	private final String xpSkill;
	private final String counterCaption;
	private final Set<Integer> npcs;
	private final Set<Integer> objects;

	ForestryEvent(String label, String xpSkill, String counterCaption, Set<Integer> npcs, Set<Integer> objects)
	{
		this.label = label;
		this.xpSkill = xpSkill;
		this.counterCaption = counterCaption;
		this.npcs = npcs;
		this.objects = objects;
	}

	private static Set<Integer> ids(Integer... ids)
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ids)));
	}

	public String getLabel()
	{
		return label;
	}

	/** Skill.name() of the other skill whose XP this event hands out, or null. */
	public String getXpSkill()
	{
		return xpSkill;
	}

	/** Caption of the per-action count the event's own XP proves, or null. */
	public String getCounterCaption()
	{
		return counterCaption;
	}

	public String occurrenceKey()
	{
		return OCCURRENCE_PREFIX + name().toLowerCase(Locale.ROOT);
	}

	public static ForestryEvent forNpc(int npcId)
	{
		for (ForestryEvent e : values())
		{
			if (e.npcs.contains(npcId))
			{
				return e;
			}
		}
		return null;
	}

	public static ForestryEvent forObject(int objectId)
	{
		for (ForestryEvent e : values())
		{
			if (e.objects.contains(objectId))
			{
				return e;
			}
		}
		return null;
	}

	public static ForestryEvent fromOccurrenceKey(String key)
	{
		if (key == null || !key.startsWith(OCCURRENCE_PREFIX))
		{
			return null;
		}
		String name = key.substring(OCCURRENCE_PREFIX.length()).toUpperCase(Locale.ROOT);
		for (ForestryEvent e : values())
		{
			if (e.name().equals(name))
			{
				return e;
			}
		}
		return null;
	}

	/** Every NPC id of every event (tests / audit). */
	Set<Integer> npcIds()
	{
		return npcs;
	}

	Set<Integer> objectIds()
	{
		return objects;
	}
}
