package com.osrstelemetry.plugin.collectors;

import java.util.Objects;

/**
 * Pure resolution of the current Slayer assignment from raw native state.
 *
 * This reproduces how RuneLite 1.13.1's own Slayer plugin derives its task
 * fields (SlayerPlugin.updateTask(), verified against tag
 * runelite-parent-1.13.1), but as Spyglass-owned code reading only
 * runelite-api state -- Spyglass must not depend on another plugin:
 *
 * <ul>
 * <li>remaining = varp SLAYER_COUNT; &lt;= 0 means no task.</li>
 * <li>task name = varp SLAYER_TARGET -&gt; SlayerTask row (COL_ID) -&gt;
 * COL_NAME_UPPERCASE. Boss tasks (target 98) instead go through varbit
 * SLAYER_TARGET_BOSSID -&gt; SlayerTaskSublist (COL_TASK_SUBTABLE_ID) -&gt;
 * COL_TASK -&gt; SlayerTask row.</li>
 * <li>location = varp SLAYER_AREA; 0 means no location, otherwise SlayerArea
 * (COL_AREA_ID) -&gt; COL_AREA_NAME_IN_HELPER. Same lookup RuneLite uses, so
 * the strings -- and Spyglass's task@location keys -- are identical.</li>
 * <li>initial amount = varp SLAYER_COUNT_ORIGINAL, plus/minus varbit
 * SLAYER_MODIFIER_VALUE (sign from SLAYER_MODIFIER_NEGATIVE) when varbit
 * SLAYER_MODIFIER_ID == 2.</li>
 * <li>any lookup with no row -&gt; UNRESOLVED (RuneLite returns early and keeps
 * its previous state; Spyglass's caller does the same).</li>
 * </ul>
 *
 * One deliberate difference: RuneLite only refreshes its initial amount
 * when the task name/location changes, so a SLAYER_COUNT_ORIGINAL that
 * syncs late at login stays stale in RuneLite. This resolver is stateless
 * and always reflects the current varps.
 */
final class SlayerNativeTaskResolver
{
	/** SLAYER_TARGET value meaning "boss task" (RuneLite: [proc,helper_slayer_current_assignment]). */
	static final int BOSS_TASK_TARGET = 98;

	/** SLAYER_MODIFIER_ID value whose modifier adjusts the original task quantity. */
	static final int QUANTITY_MODIFIER_ID = 2;

	private SlayerNativeTaskResolver()
	{
	}

	static SlayerNativeTask resolve(RawVars raw, SlayerTaskLookup lookup)
	{
		if (raw.count <= 0)
		{
			return SlayerNativeTask.noTask(raw);
		}

		Integer taskRow = raw.target == BOSS_TASK_TARGET
			? lookup.taskRowForBossId(raw.bossId)
			: lookup.taskRowForTaskId(raw.target);
		if (taskRow == null)
		{
			return SlayerNativeTask.unresolved(raw);
		}

		String taskName = lookup.taskName(taskRow);
		if (taskName == null || taskName.isEmpty())
		{
			return SlayerNativeTask.unresolved(raw);
		}

		String location = null;
		if (raw.area > 0)
		{
			location = lookup.areaName(raw.area);
			if (location == null)
			{
				return SlayerNativeTask.unresolved(raw);
			}
		}

		return SlayerNativeTask.active(taskName, location, raw.count, initialAmountOf(raw), raw);
	}

	static int initialAmountOf(RawVars raw)
	{
		int initial = raw.countOriginal;
		if (raw.modifierId == QUANTITY_MODIFIER_ID)
		{
			initial += raw.modifierNegative == 1 ? -raw.modifierValue : raw.modifierValue;
		}
		return initial;
	}

	/** The raw native values one resolution reads. */
	public static final class RawVars
	{
		final int count;
		final int target;
		final int bossId;
		final int area;
		final int countOriginal;
		final int modifierId;
		final int modifierValue;
		final int modifierNegative;

		RawVars(int count, int target, int bossId, int area, int countOriginal,
			int modifierId, int modifierValue, int modifierNegative)
		{
			this.count = count;
			this.target = target;
			this.bossId = bossId;
			this.area = area;
			this.countOriginal = countOriginal;
			this.modifierId = modifierId;
			this.modifierValue = modifierValue;
			this.modifierNegative = modifierNegative;
		}

		public int getCountOriginal()
		{
			return countOriginal;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof RawVars))
			{
				return false;
			}
			RawVars r = (RawVars) o;
			return count == r.count && target == r.target && bossId == r.bossId && area == r.area
				&& countOriginal == r.countOriginal && modifierId == r.modifierId
				&& modifierValue == r.modifierValue && modifierNegative == r.modifierNegative;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(count, target, bossId, area, countOriginal, modifierId, modifierValue, modifierNegative);
		}

		@Override
		public String toString()
		{
			return "count=" + count + " target=" + target + " boss=" + bossId + " area=" + area
				+ " original=" + countOriginal + " mod=" + modifierId + "/" + modifierValue + "/" + modifierNegative;
		}
	}
}
