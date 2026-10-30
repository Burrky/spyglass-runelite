package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.account.RequirementCheck;
import com.osrstelemetry.plugin.account.RequirementEvaluator;
import com.osrstelemetry.plugin.account.RequirementSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Farming's access requirements for every gated patch, judged by the generic
 * {@code RequirementEvaluator}, and the rule the browser, overview and run
 * selection apply (Farming correction pass, 2026-10-16):
 * <ul>
 *   <li>Ungated patch: shown. No unlock evidence is invented for it.</li>
 *   <li>Gated, every requirement evidenced as met: shown.</li>
 *   <li>Gated, a requirement evidenced as NOT met: excluded ({@link Access.State#LOCKED}).</li>
 *   <li>Gated, anything not evidenced: excluded until confirmed
 *   ({@link Access.State#UNVERIFIED}), kept apart from LOCKED.</li>
 * </ul>
 * One native observation also confirms access: a crop Spyglass has seen in
 * the patch itself (it was planted there, so the patch was usable; quests,
 * diaries and levels never go back). It never overrides an evidenced unmet
 * requirement.
 *
 * <h3>Sources (checked 2026-10-16)</h3>
 * <ul>
 *   <li>Farming Guild tiers, OSRS Wiki "Farming Guild": 45 Farming (cactus,
 *   allotments, flower, bush), 65 (herb, tree, Hespori, anima), 85 (fruit tree,
 *   spirit tree, celastrus, redwood).</li>
 *   <li>Harmony Island, OSRS Wiki: the island needs The Great Brain Robbery
 *   started; its herb patch needs the Morytania elite diary.</li>
 *   <li>Quest Helper (Zoinkwiz/quest-helper master @ 5f79193, BSD-2):
 *   {@code HerbRun} (Troll Stronghold My Arm's Big Adventure; Weiss Making
 *   Friends with My Arm; Port Phasmatys Priest in Peril; Civitas Children of
 *   the Sun) and {@code TreeRun} (Lletya Mourning's End Part I; Fossil Island
 *   Bone Voyage; Varlamore Children of the Sun; Avium Savannah The Ribbiting
 *   Tale of a Lily Pad Labour Dispute; Anglers' Retreat 51 Sailing; Great
 *   Conch Troubled Tortugans).</li>
 *   <li>Weiss, OSRS Wiki "Farming patch": the Fire of Nourishment must be
 *   built. The game exposes no reliable flag for it, so it is proven only by a
 *   crop seen in the Weiss patch.</li>
 *   <li>Miscellania (Etceteria), OSRS Wiki: The Fremennik Trials completed.</li>
 *   <li>Morytania (Canifis mushroom, Port Phasmatys): Priest in Peril.</li>
 *   <li>Prifddinas: Song of the Elves (entry to the city).</li>
 * </ul>
 * Ungated (no requirement known to Spyglass): Falador, Catherby, Ardougne,
 * Hosidius, Lumbridge, Varrock, Taverley, Gnome Stronghold, Tree Gnome
 * Village, Brimhaven, Tai Bwo Wannai, Entrana, Seers' Village, Yanille,
 * Rimmington, Champions' Guild, Draynor Manor, Al Kharid, Port Sarim.
 */
public final class FarmingAccess
{
	/** Evidence key prefix: a crop Spyglass has seen in this patch (proof the patch is usable). */
	public static final String PLANTED_HERE_PREFIX = "farming.planted_here.";

	private static final Map<String, RequirementSet> ACCESS = new LinkedHashMap<>();
	private static final Map<String, String> SOURCE = new LinkedHashMap<>();

	static
	{
		// Farming Guild tiers.
		for (String id : new String[]{"cactus.farming_guild", "allotment.farming_guild.n", "allotment.farming_guild.s", "flower.farming_guild",
			"bush.farming_guild"})
		{
			put(id, skill("FARMING", 45), "Farming Guild beginner tier");
		}
		for (String id : new String[]{"herb.farming_guild", "tree.farming_guild", "hespori.farming_guild", "anima.farming_guild"})
		{
			put(id, skill("FARMING", 65), "Farming Guild intermediate tier");
		}
		for (String id : new String[]{"fruit_tree.farming_guild", "spirit_tree.farming_guild", "celastrus.farming_guild", "redwood.farming_guild"})
		{
			put(id, skill("FARMING", 85), "Farming Guild advanced tier");
		}
		// Harmony Island.
		RequirementSet harmonyHerb = quest("THE_GREAT_BRAIN_ROBBERY", "STARTED");
		harmonyHerb.diaries = Collections.singletonList("MORYTANIA_ELITE");
		put("herb.harmony", harmonyHerb, "Harmony Island herb patch");
		put("allotment.harmony", quest("THE_GREAT_BRAIN_ROBBERY", "STARTED"), "Harmony Island");
		// Trollheim.
		put("herb.troll_stronghold", quest("MY_ARMS_BIG_ADVENTURE", "FINISHED"), "Troll Stronghold");
		RequirementSet weiss = quest("MAKING_FRIENDS_WITH_MY_ARM", "FINISHED");
		RequirementSet.EvidenceReq fire = new RequirementSet.EvidenceReq();
		fire.key = PLANTED_HERE_PREFIX + "herb.weiss";
		fire.name = "Fire of Nourishment can't be read; confirmed once a crop is seen there";
		weiss.evidence = Collections.singletonList(fire);
		put("herb.weiss", weiss, "Weiss");
		// Morytania.
		for (String id : new String[]{"herb.morytania", "allotment.morytania.nw", "allotment.morytania.se", "flower.morytania", "mushroom.canifis"})
		{
			put(id, quest("PRIEST_IN_PERIL", "FINISHED"), "Morytania");
		}
		// Prifddinas.
		for (String id : new String[]{"allotment.prifddinas.n", "allotment.prifddinas.s", "flower.prifddinas", "crystal_tree.prifddinas"})
		{
			put(id, quest("SONG_OF_THE_ELVES", "FINISHED"), "Prifddinas");
		}
		put("fruit_tree.lletya", quest("MOURNINGS_END_PART_I", "FINISHED"), "Lletya");
		for (String id : new String[]{"hardwood.fossil_island.east", "hardwood.fossil_island.middle", "hardwood.fossil_island.west",
			"seaweed.fossil_island.n", "seaweed.fossil_island.s"})
		{
			put(id, quest("BONE_VOYAGE", "FINISHED"), "Fossil Island");
		}
		for (String id : new String[]{"bush.etceteria", "spirit_tree.etceteria"})
		{
			put(id, quest("THE_FREMENNIK_TRIALS", "FINISHED"), "Miscellania");
		}
		// Varlamore.
		for (String id : new String[]{"herb.civitas", "allotment.civitas.n", "allotment.civitas.s", "flower.civitas", "calquat.kastori",
			"fruit_tree.kastori", "flower.kastori", "tree.auburnvale", "belladonna.auburnvale", "hops.aldarin"})
		{
			put(id, quest("CHILDREN_OF_THE_SUN", "FINISHED"), "Varlamore");
		}
		put("hardwood.avium_savannah", quest("THE_RIBBITING_TALE_OF_A_LILY_PAD_LABOUR_DISPUTE", "FINISHED"), "Avium Savannah");
		put("hardwood.anglers_retreat", skill("SAILING", 51), "Anglers' Retreat");
		put("calquat.great_conch", quest("TROUBLED_TORTUGANS", "FINISHED"), "Great Conch");
	}

	/** Whether a patch can be offered, and why not. */
	public static final class Access
	{
		public enum State
		{
			/** No known requirement, or every requirement evidenced as met. */
			OPEN,
			/** A requirement evidenced as not met. */
			LOCKED,
			/** Not evidenced either way yet (account data not read, or not readable). */
			UNVERIFIED
		}

		private final State state;
		private final List<String> reasons;

		Access(State state, List<String> reasons)
		{
			this.state = state;
			this.reasons = Collections.unmodifiableList(reasons);
		}

		public State getState()
		{
			return state;
		}

		public boolean isOpen()
		{
			return state == State.OPEN;
		}

		/** LOCKED: what is not met; UNVERIFIED: what Spyglass could not confirm. Empty when OPEN. */
		public List<String> getReasons()
		{
			return reasons;
		}

		static final Access OPEN = new Access(State.OPEN, Collections.emptyList());
	}

	/** Judges access for patches from one account's evidence. Pure. */
	public interface Resolver
	{
		Access access(String patchId);

		/** No account evidence at all (tests, logged out): gated patches stay unverified. */
		Resolver NONE = patchId -> resolve(patchId, null, EvidenceSource.NONE);
	}

	private FarmingAccess()
	{
	}

	/** The sourced access requirement for a patch, or null when none is known. */
	public static RequirementSet forPatch(String patchId)
	{
		return ACCESS.get(patchId);
	}

	/** Where a patch's requirement comes from ("Farming Guild intermediate tier"), or null. */
	public static String sourceOf(String patchId)
	{
		return SOURCE.get(patchId);
	}

	public static Map<String, RequirementSet> all()
	{
		return Collections.unmodifiableMap(ACCESS);
	}

	public static Resolver resolver(AccountCapability cap, EvidenceSource evidence)
	{
		EvidenceSource ev = evidence == null ? EvidenceSource.NONE : evidence;
		return patchId -> resolve(patchId, cap, ev);
	}

	static Access resolve(String patchId, AccountCapability cap, EvidenceSource evidence)
	{
		RequirementSet req = ACCESS.get(patchId);
		if (req == null)
		{
			return Access.OPEN;
		}
		if (cap == null)
		{
			return new Access(Access.State.UNVERIFIED, Collections.singletonList("account details not read yet"));
		}
		RequirementCheck check = RequirementEvaluator.check(req, cap, evidence);
		Known overall = check.overall();
		if (overall == Known.YES)
		{
			return Access.OPEN;
		}
		if (overall == Known.NO)
		{
			return new Access(Access.State.LOCKED, new ArrayList<>(check.getUnmet()));
		}
		// Not evidenced: a crop seen in the patch itself proves it was usable.
		if (evidence.get(PLANTED_HERE_PREFIX + patchId) == Known.YES)
		{
			return Access.OPEN;
		}
		return new Access(Access.State.UNVERIFIED, new ArrayList<>(check.getMissing()));
	}

	private static void put(String patchId, RequirementSet req, String source)
	{
		req.members = Boolean.FALSE; // browsing and planning do not depend on the current world's type
		ACCESS.put(patchId, req);
		SOURCE.put(patchId, source);
	}

	private static RequirementSet skill(String skill, int level)
	{
		RequirementSet r = new RequirementSet();
		r.skills = Collections.singletonList(RequirementSet.SkillReq.of(skill, level));
		return r;
	}

	private static RequirementSet quest(String quest, String state)
	{
		RequirementSet r = new RequirementSet();
		r.quests = new ArrayList<>(Arrays.asList(RequirementSet.QuestReq.of(quest, state)));
		return r;
	}
}
