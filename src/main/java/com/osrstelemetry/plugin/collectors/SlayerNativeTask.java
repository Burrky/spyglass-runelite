package com.osrstelemetry.plugin.collectors;

import java.util.Objects;

/**
 * Spyglass-owned, immutable snapshot of the current Slayer assignment,
 * resolved purely from native client state (varps, varbits, and the game
 * cache's Slayer DB tables) by {@link SlayerNativeTaskResolver}. Nothing
 * here comes from another RuneLite plugin.
 *
 * STATUS:
 * <ul>
 * <li>ACTIVE -- a task with remaining &gt; 0 that fully resolved.</li>
 * <li>NO_TASK -- SLAYER_COUNT &lt;= 0. Both completion and cancellation
 * clear the count; the two are told apart elsewhere (SlayerCollector's
 * points/streak evidence), never here.</li>
 * <li>UNRESOLVED -- remaining &gt; 0 but a cache lookup found no row (e.g. the
 * cache is not loaded yet). This is "unknown", NOT "no task": callers keep
 * whatever they already knew, exactly like RuneLite's own resolution, which
 * returns early without touching its state in this case.</li>
 * </ul>
 *
 * The raw varp/varbit values are kept alongside the resolved fields for
 * diagnostics and as future identity evidence (e.g. whether
 * SLAYER_COUNT_ORIGINAL changes on a direct same-name reassignment). They
 * play no part in identity today.
 */
public final class SlayerNativeTask
{
	public enum Status
	{
		ACTIVE, NO_TASK, UNRESOLVED
	}

	private final Status status;
	private final String taskName;
	private final String taskLocation;
	private final int remaining;
	private final int initialAmount;
	private final SlayerNativeTaskResolver.RawVars raw;

	private SlayerNativeTask(Status status, String taskName, String taskLocation, int remaining, int initialAmount,
		SlayerNativeTaskResolver.RawVars raw)
	{
		this.status = status;
		this.taskName = taskName;
		this.taskLocation = taskLocation;
		this.remaining = remaining;
		this.initialAmount = initialAmount;
		this.raw = raw;
	}

	static SlayerNativeTask active(String taskName, String taskLocation, int remaining, int initialAmount,
		SlayerNativeTaskResolver.RawVars raw)
	{
		return new SlayerNativeTask(Status.ACTIVE, taskName, taskLocation, remaining, initialAmount, raw);
	}

	static SlayerNativeTask noTask(SlayerNativeTaskResolver.RawVars raw)
	{
		return new SlayerNativeTask(Status.NO_TASK, null, null, 0, 0, raw);
	}

	static SlayerNativeTask unresolved(SlayerNativeTaskResolver.RawVars raw)
	{
		return new SlayerNativeTask(Status.UNRESOLVED, null, null, 0, 0, raw);
	}

	public Status getStatus()
	{
		return status;
	}

	public boolean isActive()
	{
		return status == Status.ACTIVE;
	}

	public boolean isUnresolved()
	{
		return status == Status.UNRESOLVED;
	}

	/** Display name from the cache's SlayerTask table; null unless ACTIVE. */
	public String getTaskName()
	{
		return taskName;
	}

	/** Area name from the cache's SlayerArea table; null when the task has no area or is not ACTIVE. */
	public String getTaskLocation()
	{
		return taskLocation;
	}

	/** SLAYER_COUNT; 0 unless ACTIVE. */
	public int getRemaining()
	{
		return remaining;
	}

	/**
	 * SLAYER_COUNT_ORIGINAL adjusted by the active quantity modifier, exactly
	 * as RuneLite derives it; 0 unless ACTIVE. May legitimately read 0 for a
	 * real task while that varp is still syncing at login -- see
	 * SlayerCollector.preserveTrustworthyInitialAmount().
	 */
	public int getInitialAmount()
	{
		return initialAmount;
	}

	public SlayerNativeTaskResolver.RawVars getRaw()
	{
		return raw;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof SlayerNativeTask))
		{
			return false;
		}
		SlayerNativeTask that = (SlayerNativeTask) o;
		return remaining == that.remaining
			&& initialAmount == that.initialAmount
			&& status == that.status
			&& Objects.equals(taskName, that.taskName)
			&& Objects.equals(taskLocation, that.taskLocation)
			&& Objects.equals(raw, that.raw);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(status, taskName, taskLocation, remaining, initialAmount, raw);
	}

	@Override
	public String toString()
	{
		return "SlayerNativeTask{" + status + ", " + taskName + " @ " + taskLocation
			+ ", remaining=" + remaining + ", initial=" + initialAmount + ", raw=" + raw + "}";
	}
}
