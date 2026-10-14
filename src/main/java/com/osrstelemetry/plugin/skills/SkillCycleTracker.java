package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Generic CYCLE state of a skill companion (pure; client thread only): the
 * current cycle of the current method -- whether it is SYNCHRONISED (its
 * start was actually observed), when it started, how far it got -- plus the
 * completed cycles per method. The adapter (Agility: a course's route) says
 * WHEN a cycle starts, progresses, fails or completes; this class only keeps
 * the honest bookkeeping. It never names "lap", "course" or "obstacle".
 *
 * <h3>Validity</h3>
 * A completion counts only when the cycle's start was observed for the SAME
 * method and nothing invalidated it since (a logout, a method change, an
 * abandoned cycle). A completion of an unsynchronised cycle (joined half way,
 * plugin started mid-cycle) is never counted: it only synchronises, so the
 * NEXT fully observed cycle counts.
 *
 * <h3>Bounded telemetry</h3>
 * Completed cycles accumulate as PENDING per method (count, summed and best
 * duration, failures) and are drained into the companion's bounded update;
 * the session-scoped totals (since the companion bound to its session) feed
 * the live view between updates.
 */
public final class SkillCycleTracker
{
	/** One completed cycle, as returned by {@link #complete}. */
	public static final class Completion
	{
		private final String method;
		private final long durationMillis;

		Completion(String method, long durationMillis)
		{
			this.method = method;
			this.durationMillis = durationMillis;
		}

		public String getMethod()
		{
			return method;
		}

		public long getDurationMillis()
		{
			return durationMillis;
		}
	}

	private String method;
	private long startedAt = -1L;
	private boolean synced;
	private int progress;
	private Integer denominator;
	private final Map<String, SkillCycleStats> pending = new LinkedHashMap<>();
	private long revision;

	/**
	 * The cycle's START was observed for {@code methodKey} at {@code atMillis}:
	 * a new, synchronised cycle begins (an unfinished one is abandoned,
	 * never counted).
	 */
	public void start(String methodKey, long atMillis, int startProgress, Integer total)
	{
		method = methodKey;
		startedAt = atMillis;
		synced = true;
		progress = Math.max(0, startProgress);
		denominator = total;
		revision++;
	}

	/**
	 * The method is known but this cycle's start was NOT observed (joined
	 * mid-way): progress is shown, nothing will count until the next start.
	 */
	public void joinUnsynced(String methodKey, int currentProgress, Integer total)
	{
		if (!Objects.equals(methodKey, method) || synced)
		{
			startedAt = -1L;
		}
		method = methodKey;
		synced = false;
		progress = Math.max(0, currentProgress);
		denominator = total;
		revision++;
	}

	/** Route progress within the current cycle (never a completion). */
	public void progress(int value, Integer total)
	{
		if (value != progress || !Objects.equals(total, denominator))
		{
			progress = Math.max(0, value);
			denominator = total;
			revision++;
		}
	}

	/**
	 * The cycle reached its completion point for {@code methodKey}. Returns
	 * the counted completion, or null when the cycle was not fully observed
	 * (then it only synchronises). Either way the next cycle waits for its
	 * own start.
	 */
	public Completion complete(String methodKey, long atMillis)
	{
		Completion done = null;
		if (synced && startedAt >= 0 && Objects.equals(methodKey, method) && atMillis >= startedAt)
		{
			long d = atMillis - startedAt;
			done = new Completion(methodKey, d);
			pending.merge(methodKey, SkillCycleStats.NONE.withCycle(d), SkillCycleStats::plus);
		}
		method = methodKey;
		startedAt = -1L;
		synced = true;
		progress = 0;
		revision++;
		return done;
	}

	/** A reliably evidenced failure in {@code methodKey}; the cycle itself is abandoned (the adapter says so). */
	public void fail(String methodKey)
	{
		pending.merge(methodKey, SkillCycleStats.NONE.withFailure(), SkillCycleStats::plus);
		revision++;
	}

	/** The current cycle can no longer be completed validly (left the method, logout): nothing counts until a new start. */
	public void abandon()
	{
		if (startedAt >= 0 || synced)
		{
			startedAt = -1L;
			synced = false;
			progress = 0;
			revision++;
		}
	}

	/** Forget the current cycle and its method entirely (login, reset). */
	public void clearCurrent()
	{
		method = null;
		startedAt = -1L;
		synced = false;
		progress = 0;
		denominator = null;
		revision++;
	}

	public String getMethod()
	{
		return method;
	}

	/** Whether a counted cycle is running (its start was observed). */
	public boolean isRunning()
	{
		return synced && startedAt >= 0;
	}

	public boolean isSynced()
	{
		return synced;
	}

	public long getStartedAt()
	{
		return startedAt;
	}

	public int getProgress()
	{
		return progress;
	}

	public Integer getDenominator()
	{
		return denominator;
	}

	/** Elapsed time of the running cycle, or null when none is running. */
	public Long elapsedMillis(long nowMillis)
	{
		return isRunning() ? Math.max(0L, nowMillis - startedAt) : null;
	}

	public Map<String, SkillCycleStats> getPending()
	{
		return Collections.unmodifiableMap(pending);
	}

	public boolean hasPending()
	{
		return !pending.isEmpty();
	}

	/** Pending completions since the last drain, cleared. */
	public Map<String, SkillCycleStats> drain()
	{
		Map<String, SkillCycleStats> out = new LinkedHashMap<>(pending);
		pending.clear();
		return out;
	}

	/** Nothing claimed this skill's session: what was pending is dropped (never misattributed). */
	public void discardPending()
	{
		pending.clear();
	}

	public long getRevision()
	{
		return revision;
	}

	public void reset()
	{
		clearCurrent();
		pending.clear();
	}
}
