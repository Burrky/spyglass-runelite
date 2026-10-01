package com.osrstelemetry.plugin.model;

/**
 * Immutable, presentation-only view of the account's live native Slayer
 * state as last committed by SlayerCollector: master, points, the current
 * master's own streak, the current task's location and the raw task
 * modifier. Read on the Swing EDT; never persisted, never session evidence.
 */
public final class SlayerLiveStatus
{
	private final int masterId;
	private final int points;
	private final int streak;
	private final String streakLabel;
	private final String taskName;
	private final String taskLocation;
	private final int modifierId;
	private final int modifierValue;
	private final boolean modifierNegative;

	public SlayerLiveStatus(int masterId, int points, int streak, String streakLabel, String taskName, String taskLocation,
		int modifierId, int modifierValue, boolean modifierNegative)
	{
		this.masterId = masterId;
		this.points = points;
		this.streak = streak;
		this.streakLabel = streakLabel;
		this.taskName = taskName;
		this.taskLocation = taskLocation;
		this.modifierId = modifierId;
		this.modifierValue = modifierValue;
		this.modifierNegative = modifierNegative;
	}

	public static SlayerLiveStatus from(SlayerState state)
	{
		return new SlayerLiveStatus(state.getMasterId(), state.getPoints(), state.getStreak(), state.getStreakLabel(),
			state.getTaskName(), state.getTaskLocation(),
			state.getModifierId() == null ? 0 : state.getModifierId(),
			state.getModifierValue() == null ? 0 : state.getModifierValue(),
			Boolean.TRUE.equals(state.getModifierNegative()));
	}

	public int getMasterId()
	{
		return masterId;
	}

	public int getPoints()
	{
		return points;
	}

	public int getStreak()
	{
		return streak;
	}

	/** NORMAL, WILDERNESS or MORTIMER -- whose streak {@link #getStreak()} is. */
	public String getStreakLabel()
	{
		return streakLabel;
	}

	/** The live task's name, or null when no task is assigned. */
	public String getTaskName()
	{
		return taskName;
	}

	/** The live task's assigned area (Konar), or null. */
	public String getTaskLocation()
	{
		return taskLocation;
	}

	public int getModifierId()
	{
		return modifierId;
	}

	public int getModifierValue()
	{
		return modifierValue;
	}

	public boolean isModifierNegative()
	{
		return modifierNegative;
	}
}
