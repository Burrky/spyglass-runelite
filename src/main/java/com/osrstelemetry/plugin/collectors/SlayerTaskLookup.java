package com.osrstelemetry.plugin.collectors;

/**
 * The three game-cache lookups needed to turn Slayer varps into names,
 * split out so {@link SlayerNativeTaskResolver} stays pure and testable
 * with a map-backed fake (this project has no Mockito). The production
 * implementation is {@link SlayerNativeTaskReader.ClientSlayerTaskLookup}.
 *
 * Every method returns null when the cache has no matching row; the
 * resolver treats that as UNRESOLVED, never as "no task".
 */
interface SlayerTaskLookup
{
	/** Row of the SlayerTask table whose COL_ID equals {@code taskId}, or null. */
	Integer taskRowForTaskId(int taskId);

	/**
	 * For a boss task (SLAYER_TARGET == {@link SlayerNativeTaskResolver#BOSS_TASK_TARGET}):
	 * the SlayerTask row referenced (COL_TASK) by the SlayerTaskSublist row whose
	 * COL_TASK_SUBTABLE_ID equals {@code bossId}, or null.
	 */
	Integer taskRowForBossId(int bossId);

	/** COL_NAME_UPPERCASE of a SlayerTask row, or null. */
	String taskName(int taskRow);

	/** COL_AREA_NAME_IN_HELPER of the SlayerArea row whose COL_AREA_ID equals {@code areaId}, or null. */
	String areaName(int areaId);
}
