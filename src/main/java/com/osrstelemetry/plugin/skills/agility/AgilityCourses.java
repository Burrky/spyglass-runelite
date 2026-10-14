package com.osrstelemetry.plugin.skills.agility;

import com.osrstelemetry.plugin.skills.agility.AgilityCourse.Kind;
import com.osrstelemetry.plugin.skills.agility.AgilityCourse.Reward;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;

/**
 * Every ordinary Agility COURSE Spyglass supports, as data (see
 * {@link AgilityCourse}). Object ids are RuneLite's native game values
 * (gameval ObjectID); the obstacle sets, course regions and end tiles were
 * audited against RuneLite's own Agility plugin data (Obstacles#OBSTACLE_IDS,
 * Courses -- studied, never depended on: Spyglass does not need that plugin
 * enabled). Route order follows the courses as run (OSRS Wiki).
 *
 * <h3>Deliberately NOT here (see the Phase 3 report for each reason)</h3>
 * Hallowed Sepulchre and Brimhaven Agility Arena (structured activities with
 * their own loops), Werewolf Skullball (a minigame), Dorgesh-Kaan (a
 * request / delivery loop with no fixed lap end), the Barbarian Outpost
 * ADVANCED route and the Gnome Stronghold advanced branch (no native obstacle
 * data audited), Meiyerditch / Rellekka lighthouse / Necropolis (quest or area
 * routes RuneLite lists as obstacles, not courses), and every overworld
 * shortcut.
 */
public final class AgilityCourses
{
	/** Marks of Grace: picked up on the rooftop itself (no trigger object). */
	static final Reward MARKS = new Reward(ItemID.GRACE, "Mark of grace", "Marks of grace", "MARKS", "marks");
	/** Crystal shard: one per Prifddinas shortcut portal (OSRS Wiki, since 11 Sep 2024 100%), straight to the inventory. */
	static final Reward SHARDS = new Reward(ItemID.PRIF_CRYSTAL_SHARD, "Crystal shard", "Crystal shards", "CRYSTAL SHARDS", "shards",
		ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_1, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_2, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_3,
		ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_4, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_5, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_6);
	/** Pyramid top: taken from the climbing rocks at the top (separate from completing the lap). */
	static final Reward PYRAMID_TOPS = new Reward(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID, "Pyramid top", "Pyramid tops", "PYRAMID TOPS",
		"tops", ObjectID.AGILITY_PYRAMID_WALL_ROCKS);
	/** Termites: the Colossal Wyrm courses' own course currency. */
	static final Reward TERMITES = new Reward(ItemID.VARLAMORE_WYRM_AGILITY_TERMITE, "Termites", "Termites", "TERMITES", "termites");

	private static final Map<String, AgilityCourse> BY_KEY = new LinkedHashMap<>();

	static
	{
		// ---- standard / advanced courses -------------------------------------------------------------
		add(AgilityCourse.builder("gnome", "Gnome Stronghold", Kind.STANDARD).regions(9781)
			.end(2484, 3437, 0).end(2487, 3437, 0)
			.node("Log balance", ObjectID.GNOME_LOG_BALANCE1)
			.node("Obstacle net", ObjectID.OBSTICAL_NET2)
			.node("Tree branch", ObjectID.CLIMBING_TREE)
			.node("Balancing rope", ObjectID.BALANCING_ROPE)
			.node("Tree branch", ObjectID.CLIMBING_BRANCH)
			.node("Obstacle net", ObjectID.OBSTICAL_NET3)
			.node("Obstacle pipe", ObjectID.OBSTICAL_PIPE3_1, ObjectID.OBSTICAL_PIPE3_2)
			.build());
		add(AgilityCourse.builder("shayzien_basic", "Shayzien Basic", Kind.STANDARD).regions(6200)
			.end(1554, 3640, 0)
			.node("Ladder", ObjectID.SHAYZIEN_AGILITY_BOTH_START_LADDER)
			.node("Monkey bars", ObjectID.SHAYZIEN_AGILITY_LOW_BAR_CLIMB)
			.node("Tightrope", ObjectID.SHAYZIEN_AGILITY_LOW_ROPE_WALK_1)
			.node("Tightrope", ObjectID.SHAYZIEN_AGILITY_LOW_ROPE_WALK_2)
			.node("Rope climb", ObjectID.SHAYZIEN_AGILITY_BOTH_ROPE_CLIMB)
			.node("Tightrope", ObjectID.SHAYZIEN_AGILITY_BOTH_ROPE_WALK)
			.node("Jump", ObjectID.SHAYZIEN_AGILITY_LOW_END_JUMP)
			.build());
		add(AgilityCourse.builder("barbarian", "Barbarian Outpost", Kind.ADVANCED).regions(10039)
			.end(2543, 3553, 0)
			.node("Rope swing", ObjectID.OBSTICAL_ROPESWING1)
			.node("Log balance", ObjectID.BARBARIAN_LOG_BALANCE1)
			.node("Obstacle net", ObjectID.AGILITY_OBSTICAL_NET_BARBARIAN)
			.node("Balancing ledge", ObjectID.BALANCING_LEDGE1)
			.xplessNode("Ladder", ObjectID.BARBARIAN_LADDERTOP_NORIM)
			.node("Crumbling wall", ObjectID.CASTLECRUMBLY1)
			.node("Crumbling wall", ObjectID.CASTLECRUMBLY1)
			.node("Crumbling wall", ObjectID.CASTLECRUMBLY1)
			.build());
		add(AgilityCourse.builder("ape_atoll", "Ape Atoll", Kind.ADVANCED).regions(11050)
			.end(2770, 2747, 0)
			.node("Stepping stone", ObjectID._100_ILM_STEPPING_STONE)
			.node("Tropical tree", ObjectID._100_ILM_CLIMBABLE_TREE)
			.node("Monkeybars", ObjectID._100_ILM_MONKEYBARS_START)
			.node("Skull slope", ObjectID._100_ILM_CLIFF_CLIMB_1)
			.node("Rope", ObjectID._100_ILM_ROPE_SWING)
			.node("Tropical tree", ObjectID._100_ILM_AGILITY_TREE_BASE)
			.build());
		add(AgilityCourse.builder("shayzien_advanced", "Shayzien Advanced", Kind.ADVANCED).regions(5944)
			.end(1522, 3625, 0)
			.node("Ladder", ObjectID.SHAYZIEN_AGILITY_BOTH_START_LADDER)
			.node("Rope climb", ObjectID.SHAYZIEN_AGILITY_BOTH_ROPE_CLIMB)
			.node("Tightrope", ObjectID.SHAYZIEN_AGILITY_BOTH_ROPE_WALK)
			.node("Bar", ObjectID.SHAYZIEN_AGILITY_UP_SWING_JUMP_1)
			.node("Jump", ObjectID.SHAYZIEN_AGILITY_UP_JUMP_PLATFORM_1)
			.node("Jump", ObjectID.SHAYZIEN_AGILITY_UP_JUMP_PLATFORM_2)
			.node("Bar", ObjectID.SHAYZIEN_AGILITY_UP_SWING_JUMP_2)
			.node("Jump", ObjectID.SHAYZIEN_AGILITY_UP_END_JUMP)
			.build());
		add(AgilityCourse.builder("wilderness", "Wilderness", Kind.ADVANCED).regions(11837)
			.end(2993, 3933, 0).end(2994, 3933, 0).end(2995, 3933, 0)
			.node("Obstacle pipe", ObjectID.OBSTICAL_PIPE2)
			.node("Ropeswing", ObjectID.OBSTICAL_ROPESWING2)
			.node("Stepping stone", ObjectID.STEPPINGSTONE1)
			.node("Log balance", ObjectID.WILDERNESS_LOG_BALANCE1)
			.node("Rocks", ObjectID.WILDCLIMBINGROCK)
			.build());
		add(AgilityCourse.builder("werewolf", "Werewolf", Kind.ADVANCED).regions(14234)
			.end(3528, 9873, 0)
			.node("Stepping stone", ObjectID.WEREWOLF_STEPING_STONE)
			.node("Hurdle", ObjectID.WEREWOLF_HURDLE_MID, ObjectID.WEREWOLF_HURDLE_END, ObjectID.WEREWOLF_HURDLE_END_MIRROR)
			.node("Hurdle", ObjectID.WEREWOLF_HURDLE_MID, ObjectID.WEREWOLF_HURDLE_END, ObjectID.WEREWOLF_HURDLE_END_MIRROR)
			.node("Hurdle", ObjectID.WEREWOLF_HURDLE_MID, ObjectID.WEREWOLF_HURDLE_END, ObjectID.WEREWOLF_HURDLE_END_MIRROR)
			.node("Pipe", ObjectID.WAA_PIPE)
			.node("Skull slope", ObjectID._100_ILM_CLIFF_CLIMB_1)
			.node("Deathslide", ObjectID.WEREWOLF_SLIDE_CENTER, ObjectID.WEREWOLF_SLIDE_SIDE, ObjectID.WEREWOLF_SLIDE_SIDE_MIRROR)
			.build());
		add(AgilityCourse.builder("penguin", "Penguin", Kind.SPECIAL).regions(10559).countOnly()
			.end(2652, 4039, 1)
			.node("Stepping stone", ObjectID.PENG_AGILITY_CRUSHCOURSE_STEPSTONE01)
			.node("Jumping stones", ObjectID.PENG_JUMP_STONE_CLICKZONE_01, ObjectID.PENG_JUMP_STONE_CLICKZONE_02,
				ObjectID.PENG_JUMP_STONE_CLICKZONE_03, ObjectID.PENG_JUMP_STONE_CLICKZONE_04, ObjectID.PENG_JUMP_STONE_CLICKZONE_05,
				ObjectID.PENG_JUMP_STONE_CLICKZONE_06, ObjectID.PENG_JUMP_STONE_CLICKZONE_07)
			.node("Icicles", ObjectID.PENG_ICICLEPILLAR_CLICKZONE)
			.node("Ice", ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS01, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS02,
				ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS03, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS04, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS05,
				ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS06, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS07, ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS08,
				ObjectID.PENG_AGILITY_SLIPPERY_GLITTERS09)
			.node("Gate", ObjectID.PENG_AGILITY_FENCING_DOOR)
			.build());
		add(AgilityCourse.builder("agility_pyramid", "Agility Pyramid", Kind.SPECIAL).regions(13356, 12105).countOnly()
			.end(3364, 2830, 0)
			.startPlane(0)
			.node("Stairs", ObjectID.AGILITY_PYRAMID_STEPS1)
			.node("Low wall", ObjectID.AGILITY_PYRAMID_LOW_WALL)
			.node("Ledge", ObjectID.AGILITY_PYRAMID_LEDGE_HOTSPOT, ObjectID.AGILITY_PYRAMID_LEDGEBALANCE_START_SOUTH_HOTSPOT,
				ObjectID.AGILITY_PYRAMID_LEDGEBALANCE_START_WEST_HOTSPOT)
			.node("Plank", ObjectID.AGILITY_PYRAMID_PLANK_START)
			.node("Gap", ObjectID.AGILITY_PYRAMID_WALLHANG_START_SOUTH_HOTSPOT, ObjectID.AGILITY_PYRAMID_WALLHANG_START_WEST_HOTSPOT,
				ObjectID.AGILITY_PYRAMID_WALLHANG_START_HOTSPOT_GROUND, ObjectID.AGILITY_PYRAMID_JUMP_HOTSPOT)
			.node("Climbing rocks", ObjectID.AGILITY_PYRAMID_WALL_ROCKS)
			.node("Doorway", ObjectID.AGILITY_PYRAMID_DOOR_HOTSPOT)
			.reward(PYRAMID_TOPS)
			.build());

		// ---- rooftops -----------------------------------------------------------------------------------
		add(AgilityCourse.builder("draynor", "Draynor Village Rooftop", Kind.ROOFTOP).regions(12338).planeDropFalls()
			.end(3103, 3261, 0)
			.node("Rough wall", ObjectID.ROOFTOPS_DRAYNOR_WALLCLIMB)
			.node("Tightrope", ObjectID.ROOFTOPS_DRAYNOR_TIGHTROPE_1)
			.node("Tightrope", ObjectID.ROOFTOPS_DRAYNOR_TIGHTROPE_2)
			.node("Narrow wall", ObjectID.ROOFTOPS_DRAYNOR_WALLCROSSING)
			.node("Wall", ObjectID.ROOFTOPS_DRAYNOR_WALLSCRAMBLE)
			.node("Gap", ObjectID.ROOFTOPS_DRAYNOR_LEAPDOWN)
			.node("Crate", ObjectID.ROOFTOPS_DRAYNOR_CRATE, ObjectID.FARMING_STYLE)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("al_kharid", "Al Kharid Rooftop", Kind.ROOFTOP).regions(13105).planeDropFalls()
			.end(3299, 3194, 0)
			.node("Rough wall", ObjectID.ROOFTOPS_KHARID_WALLCLIMB)
			.node("Tightrope", ObjectID.ROOFTOPS_KHARID_TIGHTROPE_1)
			.node("Cable", ObjectID.ROOFTOPS_KHARID_ROPE_SWING)
			.node("Zip line", ObjectID.ROOFTOPS_KHARID_SLIDE_SIDE)
			.node("Tropical tree", ObjectID.ROOFTOPS_KHARID_BAMBOO_TREE_TOP)
			.node("Roof top beams", ObjectID.ROOFTOPS_KHARID_WALLCLIMB_2)
			.node("Tightrope", ObjectID.ROOFTOPS_KHARID_TIGHTROPE_4)
			.node("Gap", ObjectID.ROOFTOPS_KHARID_LEAPDOWN)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("varrock", "Varrock Rooftop", Kind.ROOFTOP).regions(12853).planeDropFalls()
			.end(3236, 3417, 0)
			.node("Rough wall", ObjectID.ROOFTOPS_VARROCK_WALLCLIMB)
			.node("Clothes line", ObjectID.ROOFTOPS_VARROCK_CLOTHESLINE)
			.node("Gap", ObjectID.ROOFTOPS_VARROCK_LEAPTORUINS)
			.node("Wall", ObjectID.ROOFTOPS_VARROCK_WALLSWING)
			.node("Gap", ObjectID.ROOFTOPS_VARROCK_WALLSCRAMBLE)
			.node("Gap", ObjectID.ROOFTOPS_VARROCK_LEAPTOBALCONY)
			.node("Gap", ObjectID.ROOFTOPS_VARROCK_LEAPDOWN)
			.node("Ledge", ObjectID.ROOFTOPS_VARROCK_STEPUPROOF)
			.node("Edge", ObjectID.ROOFTOPS_VARROCK_FINISH)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("canifis", "Canifis Rooftop", Kind.ROOFTOP).regions(13878).planeDropFalls()
			.end(3510, 3485, 0)
			.node("Tall tree", ObjectID.ROOFTOPS_CANIFIS_START_TREE)
			.node("Gap", ObjectID.ROOFTOPS_CANIFIS_JUMP)
			.node("Gap", ObjectID.ROOFTOPS_CANIFIS_JUMP_2)
			.node("Gap", ObjectID.ROOFTOPS_CANIFIS_JUMP_5)
			.node("Gap", ObjectID.ROOFTOPS_CANIFIS_JUMP_3)
			.node("Pole-vault", ObjectID.ROOFTOPS_CANIFIS_POLEVAULT)
			.node("Gap", ObjectID.ROOFTOPS_CANIFIS_JUMP_4)
			.node("Gap", ObjectID.ROOFTOPS_CANIFIS_LEAPDOWN)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("falador", "Falador Rooftop", Kind.ROOFTOP).regions(12084).planeDropFalls()
			.end(3029, 3332, 0).end(3029, 3333, 0).end(3029, 3334, 0).end(3029, 3335, 0)
			.node("Rough wall", ObjectID.ROOFTOPS_FALADOR_WALLCLIMB)
			.node("Tightrope", ObjectID.ROOFTOPS_FALADOR_TIGHTROPE_1)
			.node("Hand holds", ObjectID.ROOFTOPS_FALADOR_HANDHOLDS_START)
			.node("Gap", ObjectID.ROOFTOPS_FALADOR_GAP_1)
			.node("Gap", ObjectID.ROOFTOPS_FALADOR_GAP_2)
			.node("Tightrope", ObjectID.ROOFTOPS_FALADOR_TIGHTROPE_2)
			.node("Tightrope", ObjectID.ROOFTOPS_FALADOR_TIGHTROPE_3)
			.node("Gap", ObjectID.ROOFTOPS_FALADOR_GAP_3)
			.node("Ledge", ObjectID.ROOFTOPS_FALADOR_LEDGE_1)
			.node("Ledge", ObjectID.ROOFTOPS_FALADOR_LEDGE_2)
			.node("Ledge", ObjectID.ROOFTOPS_FALADOR_LEDGE_3A, ObjectID.ROOFTOPS_FALADOR_LEDGE_3B)
			.node("Ledge", ObjectID.ROOFTOPS_FALADOR_LEDGE_4)
			.node("Edge", ObjectID.ROOFTOPS_FALADOR_EDGE)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("seers", "Seers' Village Rooftop", Kind.ROOFTOP).regions(10806).planeDropFalls()
			.end(2704, 3464, 0)
			.node("Wall", ObjectID.ROOFTOPS_SEERS_WALLCLIMB)
			.node("Gap", ObjectID.ROOFTOPS_SEERS_JUMP)
			.node("Tightrope", ObjectID.ROOFTOPS_SEERS_TIGHTROPE)
			.node("Gap", ObjectID.ROOFTOPS_SEERS_JUMP_1)
			.node("Gap", ObjectID.ROOFTOPS_SEERS_JUMP_2)
			.node("Edge", ObjectID.ROOFTOPS_SEERS_LEAPDOWN)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("pollnivneach", "Pollnivneach Rooftop", Kind.ROOFTOP).regions(13358).planeDropFalls()
			.end(3363, 2998, 0)
			.node("Basket", ObjectID.ROOFTOPS_POLLNIVNEACH_BASKET)
			.node("Market stall", ObjectID.ROOFTOPS_POLLNIVNEACH_MARKETSTALL)
			.node("Banner", ObjectID.ROOFTOPS_POLLNIVNEACH_HANGINGBANNER)
			.node("Gap", ObjectID.ROOFTOPS_POLLNIVNEACH_GAP)
			.node("Tree", ObjectID.ROOFTOPS_POLLNIVNEACH_TREE)
			.node("Rough wall", ObjectID.ROOFTOPS_POLLNIVNEACH_WALLCLIMB)
			.node("Monkeybars", ObjectID.ROOFTOPS_POLLNIVNEACH_MONKEYBARS_START)
			.node("Tree", ObjectID.ROOFTOPS_POLLNIVNEACH_TREETOP)
			.node("Drying line", ObjectID.ROOFTOPS_POLLNIVNEACH_LINE)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("rellekka", "Rellekka Rooftop", Kind.ROOFTOP).regions(10553).planeDropFalls()
			.end(2653, 3676, 0)
			.node("Rough wall", ObjectID.ROOFTOPS_RELLEKKA_WALLCLIMB)
			.node("Gap", ObjectID.ROOFTOPS_RELLEKKA_GAP_1)
			.node("Tightrope", ObjectID.ROOFTOPS_RELLEKKA_TIGHTROPE_1)
			.node("Gap", ObjectID.ROOFTOPS_RELLEKKA_GAP_2)
			.node("Gap", ObjectID.ROOFTOPS_RELLEKKA_GAP_3)
			.node("Tightrope", ObjectID.ROOFTOPS_RELLEKKA_TIGHTROPE_3)
			.node("Pile of fish", ObjectID.ROOFTOPS_RELLEKKA_DROPOFF)
			.reward(MARKS)
			.build());
		add(AgilityCourse.builder("ardougne", "Ardougne Rooftop", Kind.ROOFTOP).regions(10547).planeDropFalls()
			.end(2668, 3297, 0)
			.node("Wooden beams", ObjectID.ROOFTOPS_ARDY_WALLCLIMB)
			.node("Gap", ObjectID.ROOFTOPS_ARDY_JUMP)
			.node("Plank", ObjectID.ROOFTOPS_ARDY_PLANK)
			.node("Gap", ObjectID.ROOFTOPS_ARDY_JUMP_2)
			.node("Gap", ObjectID.ROOFTOPS_ARDY_JUMP_3)
			.node("Steep roof", ObjectID.ROOFTOPS_ARDY_WALLCROSSING)
			.node("Gap", ObjectID.ROOFTOPS_ARDY_JUMP_4)
			.reward(MARKS)
			.build());

		// ---- special normal courses -------------------------------------------------------------------
		add(AgilityCourse.builder("prifddinas", "Prifddinas Agility Course", Kind.SPECIAL).regions(12895)
			.end(3240, 6109, 0)
			.node("Ladder", ObjectID.PRIF_AGILITY_START_LADDER)
			.node("Tightrope", ObjectID.PRIF_AGILITY_TIGHTROPE_START1)
			.node("Chimney", ObjectID.PRIF_AGILITY_CHIMNEY_JUMP)
			.node("Roof edge", ObjectID.PRIF_AGILITY_ROOF_JUMP)
			.node("Dark hole", ObjectID.PRIF_AGILITY_DARK_HOLE_ACTIVE)
			.xplessNode("Ladder", ObjectID.PRIF_AGILITY_TREE_LADDER_LONG, ObjectID.PRIF_AGILITY_TREE_LADDER_SHORT)
			.node("Rope bridge", ObjectID.PRIF_AGILITY_ROPE_BRIDGE1)
			.node("Tightrope", ObjectID.PRIF_AGILITY_TIGHTROPE1)
			.node("Rope bridge", ObjectID.PRIF_AGILITY_ROPE_BRIDGE2)
			.node("Tightrope", ObjectID.PRIF_AGILITY_TIGHTROPE2)
			.node("Tightrope", ObjectID.PRIF_AGILITY_TIGHTROPE3)
			.node("Dark hole", ObjectID.PRIF_AGILITY_DARK_HOLE_END)
			.shortcuts(ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_1, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_2,
				ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_3, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_4,
				ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_5, ObjectID.PRIF_AGILITY_SHORTCUT_PORTAL_6)
			.reward(SHARDS)
			.build());
		add(AgilityCourse.builder("colossal_wyrm_basic", "Colossal Wyrm (Basic)", Kind.SPECIAL)
			.completionVarbit(VarbitID.VARLAMORE_WYRM_AGILITY_BASIC_PROGRESS, 6)
			.node("Ladder", ObjectID.VARLAMORE_WYRM_AGILITY_START_LADDER_TRIGGER)
			.node("Tightrope", ObjectID.VARLAMORE_WYRM_AGILITY_BALANCE_1_TRIGGER)
			.node("Tightrope", ObjectID.VARLAMORE_WYRM_AGILITY_BASIC_BALANCE_1_TRIGGER)
			.node("Monkey bars", ObjectID.VARLAMORE_WYRM_AGILITY_BASIC_MONKEYBARS_1_TRIGGER)
			.node("Ladder", ObjectID.VARLAMORE_WYRM_AGILITY_BASIC_LADDER_1_TRIGGER)
			.node("Zip line", ObjectID.VARLAMORE_WYRM_AGILITY_END_ZIPLINE_TRIGGER)
			.reward(TERMITES)
			.build());
		add(AgilityCourse.builder("colossal_wyrm_advanced", "Colossal Wyrm (Advanced)", Kind.SPECIAL)
			.completionVarbit(VarbitID.VARLAMORE_WYRM_AGILITY_ADVANCED_PROGRESS, 6)
			.node("Ladder", ObjectID.VARLAMORE_WYRM_AGILITY_START_LADDER_TRIGGER)
			.node("Tightrope", ObjectID.VARLAMORE_WYRM_AGILITY_BALANCE_1_TRIGGER)
			.node("Ladder", ObjectID.VARLAMORE_WYRM_AGILITY_ADVANCED_LADDER_1_TRIGGER)
			.node("Rope", ObjectID.VARLAMORE_WYRM_AGILITY_ADVANCED_JUMP_1_TRIGGER)
			.node("Tightrope", ObjectID.VARLAMORE_WYRM_AGILITY_ADVANCED_BALANCE_1_TRIGGER)
			.node("Zip line", ObjectID.VARLAMORE_WYRM_AGILITY_END_ZIPLINE_TRIGGER)
			.reward(TERMITES)
			.build());
	}

	private static void add(AgilityCourse c)
	{
		if (BY_KEY.put(c.getKey(), c) != null)
		{
			throw new IllegalStateException("Duplicate course " + c.getKey());
		}
	}

	private AgilityCourses()
	{
	}

	public static List<AgilityCourse> all()
	{
		return Collections.unmodifiableList(new ArrayList<>(BY_KEY.values()));
	}

	public static AgilityCourse byKey(String key)
	{
		return key == null ? null : BY_KEY.get(key);
	}

	/** Every course this object is part of (a node or a shortcut); usually one, a few for shared starts. */
	public static List<AgilityCourse> withObject(int objectId)
	{
		List<AgilityCourse> out = new ArrayList<>();
		for (AgilityCourse c : BY_KEY.values())
		{
			if (c.objectIds().contains(objectId))
			{
				out.add(c);
			}
		}
		return out;
	}

	/** Courses whose lap completes on this tile. */
	public static List<AgilityCourse> endingAt(int x, int y, int plane)
	{
		List<AgilityCourse> out = new ArrayList<>();
		for (AgilityCourse c : BY_KEY.values())
		{
			if (c.isEndTile(x, y, plane))
			{
				out.add(c);
			}
		}
		return out;
	}

	/** The course whose completion varbit this is, or null. */
	public static AgilityCourse completingOnVarbit(int varbitId)
	{
		for (AgilityCourse c : BY_KEY.values())
		{
			if (c.getCompletionVarbit() != null && c.getCompletionVarbit() == varbitId)
			{
				return c;
			}
		}
		return null;
	}

	/** Which course hands out this reward item, or null (several courses may share one, e.g. marks). */
	public static boolean isRewardItem(int itemId)
	{
		for (AgilityCourse c : BY_KEY.values())
		{
			if (c.getReward() != null && c.getReward().getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}
}
