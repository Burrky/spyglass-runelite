package com.osrstelemetry.plugin.skills;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * An observed rate from recent events only (generic): "how fast is this
 * filling up right now". Pure; client thread only.
 * <p>
 * Keeps the last {@link #capacity} samples (time, units). A gap longer than
 * {@link #restartGapMillis} (a bank run, a long idle) starts the window over,
 * so a trip's rate is never diluted by the walk between trips. No rate is
 * claimed until at least {@link #minSamples} samples span at least
 * {@link #minSpanMillis}: one unusually fast gain never extrapolates.
 */
public final class RateWindow
{
	private static final class Sample
	{
		final long at;
		final long units;

		Sample(long at, long units)
		{
			this.at = at;
			this.units = units;
		}
	}

	private final int capacity;
	private final int minSamples;
	private final long minSpanMillis;
	private final long restartGapMillis;
	private final Deque<Sample> samples = new ArrayDeque<>();

	public RateWindow(int capacity, int minSamples, long minSpanMillis, long restartGapMillis)
	{
		this.capacity = Math.max(2, capacity);
		this.minSamples = Math.max(2, minSamples);
		this.minSpanMillis = Math.max(1L, minSpanMillis);
		this.restartGapMillis = restartGapMillis;
	}

	public void record(long atMillis, long units)
	{
		if (units <= 0)
		{
			return;
		}
		Sample last = samples.peekLast();
		if (last != null && atMillis - last.at > restartGapMillis)
		{
			samples.clear();
		}
		samples.addLast(new Sample(atMillis, units));
		while (samples.size() > capacity)
		{
			samples.removeFirst();
		}
	}

	/**
	 * Units per millisecond, or null while the evidence is too thin -- or
	 * when the newest sample is older than the restart gap (the player
	 * stopped: the old rate no longer describes what is happening).
	 */
	public Double unitsPerMilli(long nowMillis)
	{
		long[] us = unitsAndSpan(nowMillis);
		return us == null ? null : us[0] / (double) us[1];
	}

	/** [units counted between samples, span ms], or null when no rate can be claimed. */
	private long[] unitsAndSpan(long nowMillis)
	{
		if (samples.size() < minSamples)
		{
			return null;
		}
		Sample first = samples.peekFirst();
		Sample last = samples.peekLast();
		long span = last.at - first.at;
		if (span < minSpanMillis || nowMillis - last.at > restartGapMillis)
		{
			return null;
		}
		long units = 0L;
		boolean skipFirst = true;
		for (Sample s : samples)
		{
			if (skipFirst)
			{
				// The first sample opens the window: units are counted between samples.
				skipFirst = false;
				continue;
			}
			units += s.units;
		}
		return units <= 0 ? null : new long[]{units, span};
	}

	/** Milliseconds until {@code remaining} more units at the observed rate (exact integer math); null when no rate is known yet. */
	public Long millisUntil(long remaining, long nowMillis)
	{
		if (remaining <= 0)
		{
			return 0L;
		}
		long[] us = unitsAndSpan(nowMillis);
		if (us == null)
		{
			return null;
		}
		return (remaining * us[1] + us[0] - 1) / us[0];
	}

	public int size()
	{
		return samples.size();
	}

	public void clear()
	{
		samples.clear();
	}
}
