package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.model.SlayerLiveStatus;
import com.osrstelemetry.plugin.model.SlayerState;
import javax.inject.Singleton;

/**
 * The Slayer task Spyglass has most recently COMMITTED -- the exact
 * baseline SlayerCollector's SLAYER_TASK_* events were built from (set when
 * hydration completes and on every post-hydration commit, cleared on every
 * login/hop/account reset). Spyglass-owned state only; it never reads the
 * game itself.
 *
 * NpcDeathCollector annotates NPC_DEATH with this instead of reading live
 * varps at ActorDeath time: a live read could already see the final kill's
 * SLAYER_COUNT at 0 and drop the task annotation from that very kill, and it
 * would couple death events to mutable native state. Using the committed
 * baseline keeps NPC_DEATH consistent with the Slayer event stream.
 *
 * Shared singleton so the two Guice-injected collectors see one instance.
 * Written and read on the client thread; volatile for safe publication.
 */
@Singleton
public class SlayerCommittedTask
{
	private volatile String taskName;
	private volatile String taskLocation;
	private volatile int remaining;

	/** Records {@code state} as the committed baseline; null clears it. */
	/**
	 * Live native status (master, points, streak, location, modifier) from
	 * the same committed observation. Unlike the task fields it survives
	 * "no task" -- points and streak stay meaningful while awaiting a task --
	 * and is cleared only by {@code set(null)} (account switch / reset).
	 */
	private volatile SlayerLiveStatus status;

	public SlayerLiveStatus getStatus()
	{
		return status;
	}

	public void set(SlayerState state)
	{
		status = state == null ? null : SlayerLiveStatus.from(state);
		if (state == null || state.getTaskName() == null)
		{
			clear();
			return;
		}
		taskName = state.getTaskName();
		taskLocation = state.getTaskLocation();
		remaining = state.getAmountRemaining();
	}

	public void clear()
	{
		taskName = null;
		taskLocation = null;
		remaining = 0;
	}

	public String getTaskName()
	{
		return taskName;
	}

	public String getTaskLocation()
	{
		return taskLocation;
	}

	public int getRemaining()
	{
		return remaining;
	}
}
