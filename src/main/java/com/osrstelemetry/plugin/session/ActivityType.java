package com.osrstelemetry.plugin.session;

/**
 * Deliberately
 * small, broad, launch-level categories only — per instruction, this
 * enum must NOT grow one constant per skill/boss/raid/minigame/farm
 * run. That finer-grained identity (which skill, which boss, which
 * task location) belongs on ActivityIdentity's activityKey/displayName
 * instead, produced later by a not-yet-built classifier/router
 * (EventType-to-ActivityIdentity mapping is out of scope here).
 * Adding a new kind of specific content should never
 * require a new ActivityType constant — only a new activityKey value
 * under one of these four.
 */
public enum ActivityType
{
	COMBAT,
	SLAYER,
	BOSSING,
	SKILLING,
	/**
	 * A registered structured activity (minigame, activity boss, repeatable
	 * arena) -- one broad category; which activity lives on the activityKey
	 * (see activity.StructuredActivityRegistry). Not part of the combat
	 * precedence branch: incomparable with every other type.
	 */
	STRUCTURED_ACTIVITY
}
