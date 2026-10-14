package com.osrstelemetry.plugin.wintertodt;

import java.util.Objects;

/**
 * The player's Wintertodt goal (PLANNING state, never telemetry). Immutable.
 * Two honest families: a Firemaking level (deterministic XP, ETA from the
 * observed rate) and a Reward Cart unique (RNG: probability milestones only).
 */
public final class WintertodtGoal
{
	public enum Family
	{
		FIREMAKING("Firemaking level"),
		UNIQUE("Reward unique");

		private final String label;

		Family(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public static final WintertodtGoal DEFAULT = new WintertodtGoal(Family.FIREMAKING, 99, WintertodtUnique.PHOENIX);

	private final Family family;
	private final int firemakingTarget;
	private final WintertodtUnique unique;

	public WintertodtGoal(Family family, int firemakingTarget, WintertodtUnique unique)
	{
		this.family = family == null ? Family.FIREMAKING : family;
		this.firemakingTarget = Math.max(2, Math.min(99, firemakingTarget));
		this.unique = unique == null ? WintertodtUnique.PHOENIX : unique;
	}

	public Family getFamily()
	{
		return family;
	}

	public int getFiremakingTarget()
	{
		return firemakingTarget;
	}

	public WintertodtUnique getUnique()
	{
		return unique;
	}

	public WintertodtGoal withFamily(Family f)
	{
		return new WintertodtGoal(f, firemakingTarget, unique);
	}

	public WintertodtGoal withFiremakingTarget(int level)
	{
		return new WintertodtGoal(family, level, unique);
	}

	public WintertodtGoal withUnique(WintertodtUnique u)
	{
		return new WintertodtGoal(family, firemakingTarget, u);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof WintertodtGoal))
		{
			return false;
		}
		WintertodtGoal g = (WintertodtGoal) o;
		return family == g.family && firemakingTarget == g.firemakingTarget && unique == g.unique;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(family, firemakingTarget, unique);
	}
}
