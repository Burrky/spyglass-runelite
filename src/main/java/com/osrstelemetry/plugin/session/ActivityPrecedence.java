package com.osrstelemetry.plugin.session;

/**
 * Specificity
 * precedence among ActivityType constants, used ONLY to decide
 * whether a later signal is allowed to REFINE (not switch) an
 * existing session's identity -- see ActivitySignalClassifier and
 * SessionLifecycleEngine.refineIdentity(). This ordering carries no
 * lifecycle meaning by itself: it never decides suspend/resume/
 * finalize, only whether "generic -> more specific" is a legal
 * refinement direction within the combat branch.
 *
 * BOSSING is more specific than SLAYER, which is more
 * specific than COMBAT (a Slayer task is fought with combat XP; a
 * boss kill is often, but not always, also part of a Slayer task or
 * plain combat). SKILLING is a deliberately separate branch with no
 * defined precedence relationship to the other three at all -- a
 * skilling identity is never compatible with a combat/slayer/bossing
 * identity in either direction, and code must never call
 * combatBranchRank(SKILLING).
 */
final class ActivityPrecedence
{
	private ActivityPrecedence()
	{
	}

	/**
	 * Higher number = more specific. Only meaningful for comparing two
	 * types within the combat branch (COMBAT/SLAYER/BOSSING).
	 */
	static int combatBranchRank(ActivityType type)
	{
		switch (type)
		{
			case COMBAT:
				return 0;
			case SLAYER:
				return 1;
			case BOSSING:
				return 2;
			default:
				throw new IllegalArgumentException("Not part of the combat specificity branch: " + type);
		}
	}

	static boolean isCombatBranch(ActivityType type)
	{
		return type == ActivityType.COMBAT || type == ActivityType.SLAYER || type == ActivityType.BOSSING;
	}

	/**
	 * True only when both types are in the combat branch and candidate
	 * strictly outranks current. SKILLING (or any future non-combat
	 * type) on either side always returns false -- it is never "more
	 * specific" or "less specific" than a combat-branch type, it is
	 * simply incomparable, which callers treat as a real switch.
	 */
	static boolean isMoreSpecific(ActivityType candidate, ActivityType current)
	{
		if (!isCombatBranch(candidate) || !isCombatBranch(current))
		{
			return false;
		}
		return combatBranchRank(candidate) > combatBranchRank(current);
	}
}
