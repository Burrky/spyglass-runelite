package com.osrstelemetry.plugin.session;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small, explicit, project-owned DATA describing the one thing
 * boss self-consumption of its own Slayer task needs that no other
 * class in this codebase already models:
 * which bosses ALSO count toward their own Slayer task assignment (a
 * boss kill that itself consumes that task's units, exactly like an
 * ordinary member of the task), and which NPC name(s) actually
 * constitute that boss for loot-source-name comparison purposes.
 *
 * ROOT CAUSE THIS EXISTS TO FIX: an earlier fix made
 * ActivitySignalClassifier.decideCombatBranch() let an
 * authoritative SLAYER_TASK_PROGRESS switch an ACTIVE, more-specific
 * BOSSING session away -- correct for a genuinely different NPC (a real
 * Gargoyle kill after leaving Grotesque Guardians), but WRONG for
 * Grotesque Guardians' OWN kills, which also decrement the Gargoyles
 * Slayer task. Both cases produce an IDENTICAL SLAYER_TASK_PROGRESS
 * event (same taskName, same shape) -- there is no way to tell them
 * apart from that one event alone. This class supplies the one piece of
 * missing, GENERAL evidence: Jagex's own boss-to-Slayer-task mapping is
 * a small, fixed, well-known table, not something that needs to be
 * inferred or guessed per account.
 *
 * DELIBERATELY DATA, NOT CODE: every consumer of this class
 * (ActivitySignalClassifier, SessionSignalBatchResolver) asks it a pure
 * yes/no question -- neither branches on a boss's name directly anywhere
 * else. Extend BOSS_OWN_TASK_NORMALIZED (and, if the boss also has a
 * multi-NPC encounter like Grotesque Guardians' Dawn/Dusk, BOSS_NPC_NAMES_NORMALIZED)
 * as more such bosses are confirmed -- Shellbane Gryphon/Gryphons is the
 * second confirmed entry (live-reproduced regression, identical shape to
 * Grotesque Guardians/Gargoyles); still-unconfirmed candidates like
 * Kurask/Kurasks, Kree'arra/Aviansies remain future additions -- rather
 * than adding a per-boss branch anywhere else in this codebase.
 *
 * BOSS_NPC_NAMES_NORMALIZED deliberately duplicates (in miniature) the
 * SHAPE of KnownBossRegistry's own NPC_NAME_ALIASES table (Dawn/Dusk ->
 * "Grotesque Guardians"), but is NOT the same table and is not shared
 * with it: KnownBossRegistry lives in the collectors package, is an
 * account-scoped @Singleton with per-account confirmed-boss state, and
 * solves a different problem (resolving an InteractingChanged NPC name
 * into a BOSS_ACTIVITY_CONTEXT candidate). This class is a pure,
 * dependency-free, per-JVM-static table answering a narrower, different
 * question (loot SOURCE NAME identity for self-consumption
 * corroboration) for the classifier/resolver pair, which must remain
 * free of any RuneLite/collectors dependency. If the two tables ever
 * diverge for the same boss, that is a real bug to fix by hand in both
 * places -- there are only ever a handful of entries in either.
 */
final class BossTaskAffinity
{
	private BossTaskAffinity()
	{
	}

	/**
	 * normalized boss display name -&gt; normalized Slayer task name that
	 * boss's OWN kills also count toward. Only bosses confirmed to have
	 * this property belong here -- most bosses have no Slayer-task
	 * affinity at all and simply return false/null below.
	 */
	private static final Map<String, String> BOSS_OWN_TASK_NORMALIZED;

	static
	{
		Map<String, String> ownTask = new HashMap<>();
		ownTask.put(normalize("Grotesque Guardians"), normalize("Gargoyles"));
		// Shellbane Gryphon canonical-identity churn:
		// IDENTICAL SHAPE to Grotesque Guardians/Gargoyles above: Shellbane
		// Gryphon's own kill also decrements the "Gryphons" Slayer task,
		// so an authoritative SLAYER_TASK_PROGRESS naming "Gryphons" while
		// ACTIVE BOSSING/Shellbane Gryphon is genuinely ambiguous (self-
		// consumption vs. a real, separate ordinary Gryphon) and must not
		// switch by itself -- see this class's own javadoc, and
		// ActivitySignalClassifier.classifySlayerTaskProgress()'s use of
		// isBossOwnTask() below. This is a DATA addition only, exactly the
		// "extend as more such bosses are confirmed" extension point this
		// class's own javadoc already invites -- no new code, no
		// Shellbane-specific branch anywhere else in the codebase. Single-
		// NPC encounter (unlike Grotesque Guardians' Dawn/Dusk), so no
		// corresponding BOSS_NPC_NAMES_NORMALIZED entry is needed --
		// isBossOwnNpcName()'s direct-equality fallback already handles it
		// correctly for BOSS_KILL/SERVER_NPC_LOOT corroboration.
		ownTask.put(normalize("Shellbane Gryphon"), normalize("Gryphons"));
		BOSS_OWN_TASK_NORMALIZED = Collections.unmodifiableMap(ownTask);
	}

	/**
	 * normalized boss display name -&gt; the normalized NPC name(s) that
	 * constitute an actual kill of that boss (for comparing against a
	 * SERVER_NPC_LOOT signal's own sourceName). A boss with no entry here
	 * falls back to direct name equality (see isBossOwnNpcName()) -- only
	 * multi-NPC encounters like Grotesque Guardians (Dawn AND Dusk) need
	 * an explicit entry.
	 */
	private static final Map<String, Set<String>> BOSS_NPC_NAMES_NORMALIZED;

	static
	{
		Map<String, Set<String>> npcNames = new HashMap<>();
		npcNames.put(normalize("Grotesque Guardians"), Collections.unmodifiableSet(new HashSet<>(
			Arrays.asList(normalize("Dawn"), normalize("Dusk"), normalize("Grotesque Guardians")))));
		BOSS_NPC_NAMES_NORMALIZED = Collections.unmodifiableMap(npcNames);
	}

	/**
	 * @return true if `bossActivityKey` (an already-normalized
	 * ActivityIdentity.getActivityKey() value from bossIdentity() -- see
	 * ActivitySignalClassifier) is a boss confirmed to consume its own
	 * Slayer task's units, AND `taskName` (raw, not yet normalized) names
	 * exactly that task.
	 */
	static boolean isBossOwnTask(String bossActivityKey, String taskName)
	{
		if (bossActivityKey == null || taskName == null)
		{
			return false;
		}
		String ownTask = BOSS_OWN_TASK_NORMALIZED.get(bossActivityKey);
		if (ownTask != null && ownTask.equals(normalize(taskName)))
		{
			return true;
		}
		// GENERIC BOSS-ON-MATCHING-TASK RULE (Kraken / Cave Kraken live
		// regression). The explicit table above only covers bosses whose
		// Slayer-task family data does NOT already name them (Grotesque
		// Guardians is catalogued under its Dawn/Dusk NPCs; Shellbane
		// Gryphon is not a "Gryphons" family member). Every other boss
		// that the existing, RuneLite-derived SlayerTaskFamilyRegistry
		// already lists as a member of the named task's family (Kraken
		// under "Cave kraken", Cerberus under "Hellhounds", Vorkath under
		// "Blue dragons", ...) is, by that same data, a boss whose own
		// kills consume that task -- so its SLAYER_TASK_PROGRESS is the
		// identical, ambiguous self-consumption event and must go through
		// the same suppress-and-disambiguate path, never an immediate
		// switch to the regular Slayer identity. No per-boss name branch:
		// one membership lookup against data this codebase already owns.
		// bossActivityKey is ActivitySignalClassifier.bossIdentity()'s own
		// trimmed/lowercased boss name -- the same normalization
		// belongsToTaskFamily() applies to its NPC-name argument.
		return SlayerTaskFamilyRegistry.belongsToTaskFamily(taskName, bossActivityKey);
	}

	/**
	 * @return true if `npcSourceName` (raw, not yet normalized -- e.g. a
	 * SERVER_NPC_LOOT signal's own sourceName) identifies the boss itself
	 * (`bossActivityKey`, an already-normalized activityKey) rather than
	 * some other, genuinely different NPC. Bosses with no explicit
	 * multi-NPC entry fall back to direct equality against the boss's own
	 * key -- correct for every single-NPC boss encounter.
	 */
	static boolean isBossOwnNpcName(String bossActivityKey, String npcSourceName)
	{
		if (bossActivityKey == null)
		{
			return false;
		}
		String normalizedSource = npcSourceName == null ? "" : normalize(npcSourceName);
		Set<String> knownNames = BOSS_NPC_NAMES_NORMALIZED.get(bossActivityKey);
		if (knownNames != null)
		{
			return knownNames.contains(normalizedSource);
		}
		return bossActivityKey.equals(normalizedSource);
	}

	private static String normalize(String s)
	{
		return s.trim().toLowerCase(Locale.ROOT);
	}
}
