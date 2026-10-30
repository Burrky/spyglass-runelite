package com.osrstelemetry.plugin.farming;

/**
 * One raw patch value the observer recorded on a settled tick. Immutable.
 *
 * <ul>
 *   <li>{@link Kind#BASELINE}: the first settled read of this patch since the
 *   last continuity break. It says what the patch is NOW; it proves nothing
 *   about when it changed, so it can never establish a planting time or an
 *   exact growth tick.</li>
 *   <li>{@link Kind#CHANGE}: the value differs from the read on the
 *   immediately preceding settled tick of the same sequence, so the change
 *   happened between those two ticks.</li>
 * </ul>
 */
public final class PatchObservation
{
	public enum Kind
	{
		BASELINE,
		CHANGE
	}

	private final Kind kind;
	private final FarmingPatchDef patch;
	private final PatchReading reading;
	/** CHANGE only: the reading on the previous tick. */
	private final PatchReading previous;
	private final long observedAt;
	/** CHANGE only: when the previous reading was taken (one tick earlier). */
	private final long previousObservedAt;
	private final long sequence;

	PatchObservation(Kind kind, FarmingPatchDef patch, PatchReading reading, PatchReading previous, long observedAt, long previousObservedAt, long sequence)
	{
		this.kind = kind;
		this.patch = patch;
		this.reading = reading;
		this.previous = previous;
		this.observedAt = observedAt;
		this.previousObservedAt = previousObservedAt;
		this.sequence = sequence;
	}

	public Kind getKind()
	{
		return kind;
	}

	public FarmingPatchDef getPatch()
	{
		return patch;
	}

	public PatchReading getReading()
	{
		return reading;
	}

	public PatchReading getPrevious()
	{
		return previous;
	}

	public long getObservedAt()
	{
		return observedAt;
	}

	public long getPreviousObservedAt()
	{
		return previousObservedAt;
	}

	public long getSequence()
	{
		return sequence;
	}

	/** A same-sequence, tick-to-tick change: the only kind that can prove planting or a growth tick. */
	public boolean isContinuousChange()
	{
		return kind == Kind.CHANGE;
	}

	@Override
	public String toString()
	{
		return kind + " " + patch.getPatchId() + " " + (previous == null ? "" : previous.getRaw() + "->") + reading.getRaw() + " seq" + sequence;
	}
}
