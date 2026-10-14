package com.osrstelemetry.plugin.skills;

/**
 * Completed CYCLES of one skill method (generic: Agility laps today; a
 * Runecraft run or a Hunter catch could use it later): how many fully
 * observed cycles completed, their summed and best duration, and how many
 * reliably-evidenced failures happened. Immutable.
 *
 * Only VALID, fully observed cycles are ever counted (see
 * {@link SkillCycleTracker}); a partial or unsynchronised cycle never is.
 */
public final class SkillCycleStats
{
	public static final SkillCycleStats NONE = new SkillCycleStats(0L, 0L, 0L, 0L);

	private final long count;
	private final long totalMillis;
	/** Best (shortest) valid cycle; 0 = none yet. */
	private final long bestMillis;
	private final long failures;

	public SkillCycleStats(long count, long totalMillis, long bestMillis, long failures)
	{
		this.count = Math.max(0L, count);
		this.totalMillis = Math.max(0L, totalMillis);
		this.bestMillis = Math.max(0L, bestMillis);
		this.failures = Math.max(0L, failures);
	}

	public long getCount()
	{
		return count;
	}

	public long getTotalMillis()
	{
		return totalMillis;
	}

	/** 0 when no valid cycle completed yet. */
	public long getBestMillis()
	{
		return bestMillis;
	}

	public long getFailures()
	{
		return failures;
	}

	/** Average valid cycle, or null with none. */
	public Long averageMillis()
	{
		return count <= 0 ? null : totalMillis / count;
	}

	public boolean isEmpty()
	{
		return count == 0 && failures == 0;
	}

	public SkillCycleStats withCycle(long durationMillis)
	{
		long d = Math.max(0L, durationMillis);
		return new SkillCycleStats(count + 1, totalMillis + d, bestOf(bestMillis, d), failures);
	}

	public SkillCycleStats withFailure()
	{
		return new SkillCycleStats(count, totalMillis, bestMillis, failures + 1);
	}

	/** Two disjoint parts of the same thing (session + pending): counts add, best is the better one. */
	public SkillCycleStats plus(SkillCycleStats other)
	{
		if (other == null)
		{
			return this;
		}
		return new SkillCycleStats(count + other.count, totalMillis + other.totalMillis, bestOf(bestMillis, other.bestMillis),
			failures + other.failures);
	}

	/**
	 * Two views of the SAME numbers that may be one hand-off apart (the durable
	 * session vs what the companion already sent): the larger count wins as a
	 * whole, so the totals stay consistent with each other.
	 */
	public static SkillCycleStats ahead(SkillCycleStats a, SkillCycleStats b)
	{
		if (a == null)
		{
			return b == null ? NONE : b;
		}
		if (b == null)
		{
			return a;
		}
		SkillCycleStats lead = b.count > a.count || (b.count == a.count && b.failures > a.failures) ? b : a;
		return new SkillCycleStats(lead.count, lead.totalMillis, bestOf(a.bestMillis, b.bestMillis), Math.max(a.failures, b.failures));
	}

	static long bestOf(long a, long b)
	{
		if (a <= 0)
		{
			return b;
		}
		if (b <= 0)
		{
			return a;
		}
		return Math.min(a, b);
	}

	@Override
	public String toString()
	{
		return "SkillCycleStats{" + count + " in " + totalMillis + "ms, best " + bestMillis + ", failures " + failures + '}';
	}
}
