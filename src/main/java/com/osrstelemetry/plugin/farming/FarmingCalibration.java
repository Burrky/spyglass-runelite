package com.osrstelemetry.plugin.farming;

import java.util.List;

/**
 * The account's farm-tick timing, learned only from Spyglass's own
 * {@link CalibrationSample}s (a growth tick witnessed tick-to-tick). Pure and
 * immutable. Never reads RuneLite's Time Tracking configuration.
 *
 * <h3>Model (RuneLite FarmingTracker, BSD 2-Clause)</h3>
 * Crops advance on account-wide growth boundaries: a crop whose tick length is
 * {@code T} minutes advances at the whole minutes {@code m} where
 * {@code (m + offset) % T == 0}. A witnessed growth tick of a {@code T}-minute
 * crop pins the offset modulo {@code T} (its "precision"). All Farming tick
 * lengths are 5 x 2^k minutes, so a precision {@code P} either covers a tick
 * length (P &gt;= T: the boundary is exact) or divides it (P &lt; T: the boundary
 * is one of T / P candidates, P minutes apart).
 *
 * Without any sample the precision is 1 minute: the next boundary is
 * anywhere in the following T minutes. Projections then use the LATEST
 * candidate, so an estimate is never early.
 *
 * Samples are combined by anchoring on the most recent sample of the longest
 * tick length; samples that disagree with it are counted, not used.
 */
public final class FarmingCalibration
{
	public static final FarmingCalibration NONE = new FarmingCalibration(0, 1, 0, 0);

	private static final long MINUTE = 60_000L;

	private final int offsetMinutes;
	private final int precisionMinutes;
	private final int samples;
	private final int conflicts;

	FarmingCalibration(int offsetMinutes, int precisionMinutes, int samples, int conflicts)
	{
		this.offsetMinutes = offsetMinutes;
		this.precisionMinutes = precisionMinutes;
		this.samples = samples;
		this.conflicts = conflicts;
	}

	public static FarmingCalibration from(List<CalibrationSample> samples)
	{
		CalibrationSample anchor = null;
		for (CalibrationSample s : samples)
		{
			if (s.getTickMinutes() <= 0)
			{
				continue;
			}
			if (anchor == null || s.getTickMinutes() > anchor.getTickMinutes()
				|| (s.getTickMinutes() == anchor.getTickMinutes() && s.getObservedAt() > anchor.getObservedAt()))
			{
				anchor = s;
			}
		}
		if (anchor == null)
		{
			return NONE;
		}
		int precision = anchor.getTickMinutes();
		int offset = offsetFor(anchor, precision);
		int conflicts = 0;
		int used = 0;
		for (CalibrationSample s : samples)
		{
			if (s.getTickMinutes() <= 0)
			{
				continue;
			}
			int t = s.getTickMinutes();
			int common = Math.min(t, precision);
			if (Math.floorMod(offsetFor(s, t) - offset, common) != 0)
			{
				conflicts++;
			}
			else
			{
				used++;
			}
		}
		return new FarmingCalibration(offset, precision, used, conflicts);
	}

	/** The offset (mod {@code tickMinutes}) a witnessed boundary implies: the boundary is the observed minute. */
	static int offsetFor(CalibrationSample s, int tickMinutes)
	{
		long boundaryMinute = Math.floorDiv(s.getObservedAt(), MINUTE);
		return (int) Math.floorMod(-boundaryMinute, (long) tickMinutes);
	}

	public int getOffsetMinutes()
	{
		return offsetMinutes;
	}

	/** Minutes the offset is known to modulo; 1 when uncalibrated. */
	public int getPrecisionMinutes()
	{
		return precisionMinutes;
	}

	public boolean isCalibrated()
	{
		return precisionMinutes > 1;
	}

	public int getSampleCount()
	{
		return samples;
	}

	public int getConflictCount()
	{
		return conflicts;
	}

	/** True when the next boundary of a {@code tickMinutes} crop is known exactly. */
	public boolean isExactFor(int tickMinutes)
	{
		return precisionMinutes >= tickMinutes;
	}

	/**
	 * The earliest and latest possible time (epoch ms, whole minutes) of the
	 * first growth boundary strictly after {@code afterMillis}, for a crop of
	 * {@code tickMinutes}.
	 */
	public long[] nextBoundary(long afterMillis, int tickMinutes)
	{
		int grid = Math.min(precisionMinutes, tickMinutes);
		long afterMinute = Math.floorDiv(afterMillis, MINUTE);
		long m = afterMinute + 1;
		long shift = Math.floorMod(m + offsetMinutes, (long) grid);
		long earliest = shift == 0 ? m : m + (grid - shift);
		long latest = earliest + (tickMinutes - grid);
		return new long[] {earliest * MINUTE, latest * MINUTE};
	}

	@Override
	public String toString()
	{
		return isCalibrated() ? "offset " + offsetMinutes + " mod " + precisionMinutes + " (" + samples + " samples, " + conflicts + " conflicts)"
			: "uncalibrated";
	}
}
