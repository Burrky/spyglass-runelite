package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Immutable snapshot of the CURRENT Barrows run's brothers, read straight
 * from the game's per-brother varbits (see {@link BarrowsBrother}). It is
 * the game's truth at the moment it was read: attaching mid-run shows the
 * brothers already defeated, and a new run shows 0 / 6 once the game resets
 * the varbits. Runtime-only presentation state: it never creates, resumes
 * or switches a session, and it is not persisted (a restart simply re-reads
 * the varbits).
 */
public final class BarrowsRunProgress
{
	private static final BarrowsRunProgress NONE_CLEARED = new BarrowsRunProgress(EnumSet.noneOf(BarrowsBrother.class));

	private final Set<BarrowsBrother> cleared;

	private BarrowsRunProgress(EnumSet<BarrowsBrother> cleared)
	{
		this.cleared = Collections.unmodifiableSet(cleared);
	}

	/** Reads all six varbits through {@code varbitValue} (varbit id -&gt; value). */
	public static BarrowsRunProgress read(IntUnaryOperator varbitValue)
	{
		EnumSet<BarrowsBrother> cleared = EnumSet.noneOf(BarrowsBrother.class);
		for (BarrowsBrother brother : BarrowsBrother.values())
		{
			if (varbitValue.applyAsInt(brother.getKilledVarbit()) > 0)
			{
				cleared.add(brother);
			}
		}
		return cleared.isEmpty() ? NONE_CLEARED : new BarrowsRunProgress(cleared);
	}

	public boolean isCleared(BarrowsBrother brother)
	{
		return cleared.contains(brother);
	}

	public int getClearedCount()
	{
		return cleared.size();
	}

	public static int total()
	{
		return BarrowsBrother.values().length;
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof BarrowsRunProgress && ((BarrowsRunProgress) o).cleared.equals(cleared);
	}

	@Override
	public int hashCode()
	{
		return cleared.hashCode();
	}

	@Override
	public String toString()
	{
		return "Brothers " + getClearedCount() + " / " + total() + " " + cleared;
	}
}
