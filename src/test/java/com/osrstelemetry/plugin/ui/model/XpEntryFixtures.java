package com.osrstelemetry.plugin.ui.model;

/** Test fixtures: skill card entries in any state (the constructors are package-private). */
public final class XpEntryFixtures
{
	private XpEntryFixtures()
	{
	}

	/** An entry at {@code level} with {@code left} XP to the next level ({@code level} 99: max). */
	public static CurrentSessionSnapshot.XpEntry entry(long gained, Long perHour, Long warmup, int level, Long left)
	{
		CurrentSessionSnapshot.LevelProgress lp = level >= 99 ? new CurrentSessionSnapshot.LevelProgress(99, true, null, null)
			: new CurrentSessionSnapshot.LevelProgress(level, false, 40, left);
		return new CurrentSessionSnapshot.XpEntry("WOODCUTTING", gained, perHour, lp, warmup);
	}
}
