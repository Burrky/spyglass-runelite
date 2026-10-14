package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The CURRENT METHOD of one skill companion, with inertia, plus how long
 * each method was the one being trained (generic: methods are string keys).
 * Pure; client thread only.
 *
 * <h3>Stability</h3>
 * A method is adopted from the first success when none is known. A
 * DIFFERENT method replaces it only after {@link #SWITCH_CONFIRMATIONS}
 * consecutive successes of that method, or after one success the player's
 * own action also named (e.g. they clicked "Mine" on that rock: a
 * "hint"). One stray success, an unrelated inventory change or a supporting
 * XP drop never flips it; a success of the current method resets any
 * half-built switch.
 *
 * <h3>Method time</h3>
 * Time is attributed to the current method tick by tick while the player
 * is still training it (a success within {@link #IDLE_CUTOFF_MILLIS});
 * respawns, short walks and banking count, long idles do not. Each gap is
 * capped at {@link #MAX_STEP_MILLIS} so a stalled client never books
 * phantom time. Accumulated time is drained into bounded telemetry by the
 * owner, never written per tick.
 */
public final class MethodTracker
{
	public static final int SWITCH_CONFIRMATIONS = 2;
	/** A hint counts while it is this fresh. */
	public static final long HINT_FRESH_MILLIS = 15_000L;
	public static final long IDLE_CUTOFF_MILLIS = 3L * 60_000L;
	static final long MAX_STEP_MILLIS = 5_000L;

	private String current;
	private String candidate;
	private int candidateCount;
	private String hint;
	private long hintAt;
	private long lastSuccessAt = -1L;
	private long lastStepAt = -1L;
	private final Map<String, Long> pendingMillis = new LinkedHashMap<>();
	private final Map<String, Long> sessionMillis = new LinkedHashMap<>();
	private long transitions;

	/** The current method key, or null when no reliable evidence named one yet. */
	public String getCurrent()
	{
		return current;
	}

	/** The player's own action named a method (e.g. "Mine" on a specific rock). Never adopts by itself. */
	public void hint(String method, long nowMillis)
	{
		hint = method;
		hintAt = nowMillis;
	}

	/**
	 * One confirmed success attributable to {@code method} (null = the
	 * success named no method, e.g. a by-product only). Returns true when the
	 * current method changed.
	 */
	public boolean success(String method, long nowMillis)
	{
		lastSuccessAt = nowMillis;
		if (method == null)
		{
			return false;
		}
		if (current == null)
		{
			adopt(method);
			return true;
		}
		if (current.equals(method))
		{
			candidate = null;
			candidateCount = 0;
			return false;
		}
		if (method.equals(candidate))
		{
			candidateCount++;
		}
		else
		{
			candidate = method;
			candidateCount = 1;
		}
		boolean hinted = method.equals(hint) && nowMillis - hintAt <= HINT_FRESH_MILLIS;
		if (candidateCount >= SWITCH_CONFIRMATIONS || hinted)
		{
			adopt(method);
			return true;
		}
		return false;
	}

	private void adopt(String method)
	{
		if (current != null && !current.equals(method))
		{
			transitions++;
		}
		current = method;
		candidate = null;
		candidateCount = 0;
	}

	/** Called every tick: books the elapsed time to the current method while it is still being trained. */
	public void step(long nowMillis)
	{
		long previous = lastStepAt;
		lastStepAt = nowMillis;
		if (previous < 0 || current == null || lastSuccessAt < 0 || nowMillis - lastSuccessAt > IDLE_CUTOFF_MILLIS)
		{
			return;
		}
		long delta = Math.min(MAX_STEP_MILLIS, Math.max(0L, nowMillis - previous));
		if (delta > 0)
		{
			pendingMillis.merge(current, delta, Long::sum);
			sessionMillis.merge(current, delta, Long::sum);
		}
	}

	/** Method time accumulated since the last drain (method -> millis), and clears it. */
	public Map<String, Long> drainMillis()
	{
		Map<String, Long> out = new LinkedHashMap<>(pendingMillis);
		pendingMillis.clear();
		return out;
	}

	public Map<String, Long> getPendingMillis()
	{
		return Collections.unmodifiableMap(pendingMillis);
	}

	/** Method time this tracker saw for the bound session (live rates only; History reads the telemetry). */
	public Map<String, Long> getSessionMillis()
	{
		return Collections.unmodifiableMap(sessionMillis);
	}

	public long getTransitions()
	{
		return transitions;
	}

	public long getLastSuccessAt()
	{
		return lastSuccessAt;
	}

	/** New session: its own time from here (the current method itself is kept -- still what the player does). */
	public void newSession()
	{
		pendingMillis.clear();
		sessionMillis.clear();
		transitions = 0;
	}

	/** Login / account switch: nothing known. */
	public void reset()
	{
		current = null;
		candidate = null;
		candidateCount = 0;
		hint = null;
		hintAt = 0L;
		lastSuccessAt = -1L;
		lastStepAt = -1L;
		pendingMillis.clear();
		sessionMillis.clear();
		transitions = 0;
	}

	@Override
	public String toString()
	{
		return "MethodTracker{" + current + (candidate == null ? "" : " (candidate " + candidate + " x" + candidateCount + ")") + '}';
	}

	static boolean same(String a, String b)
	{
		return Objects.equals(a, b);
	}
}
