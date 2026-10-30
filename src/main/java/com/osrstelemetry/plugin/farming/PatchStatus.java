package com.osrstelemetry.plugin.farming;

/**
 * What a patch shows right now: the result of {@link FarmingProjection}.
 * Immutable, computed at presentation time and never written back.
 */
public final class PatchStatus
{
	/** The single player-facing state (canonical §3.2 / §18.4). */
	public enum Display
	{
		/** Observed harvestable / check-health, or the estimated growth time has passed. */
		READY,
		/** Growing, with an estimate ({@link #getReadyAt()}). */
		GROWING_TIMED,
		/** Growing, but nothing defensible to time it by (e.g. anima). */
		GROWING,
		/** Fully grown with nothing to harvest (a checked spirit tree, a scarecrow). */
		GROWN,
		DISEASED,
		DEAD,
		/** Observed empty or weeds. */
		CLEAR,
		/** Never observed on this profile. */
		NOT_TRACKED,
		/** The latest reading could not be decoded: Spyglass refuses to guess. */
		CHECK_PATCH
	}

	/** Where a READY came from. Internal: never player text. */
	public enum ReadySource
	{
		OBSERVED,
		ESTIMATED
	}

	/** Which provenance line applies (canonical §3.2). */
	public enum Provenance
	{
		NONE,
		SEEN_JUST_NOW,
		PLANTED,
		SEEN_GROWING,
		LAST_SEEN,
		LAST_SEEN_DISEASED,
		LAST_SEEN_DEAD
	}

	private final Display display;
	private final ReadySource readySource;
	private final FarmingProduce produce;
	private final boolean produceUnknown;
	private final Long readyEarliest;
	private final Long readyAt;
	private final Provenance provenance;
	private final long provenanceAt;
	private final long lastSeenAt;

	PatchStatus(Display display, ReadySource readySource, FarmingProduce produce, boolean produceUnknown, Long readyEarliest, Long readyAt,
		Provenance provenance, long provenanceAt, long lastSeenAt)
	{
		this.display = display;
		this.readySource = readySource;
		this.produce = produce;
		this.produceUnknown = produceUnknown;
		this.readyEarliest = readyEarliest;
		this.readyAt = readyAt;
		this.provenance = provenance;
		this.provenanceAt = provenanceAt;
		this.lastSeenAt = lastSeenAt;
	}

	public Display getDisplay()
	{
		return display;
	}

	/** Null unless READY. */
	public ReadySource getReadySource()
	{
		return readySource;
	}

	/** The crop in the patch (or last known before an unreadable value); null when none or unknown. */
	public FarmingProduce getProduce()
	{
		return produce;
	}

	/** A crop is there but its kind is unknown (a dead herb seen after another crop). */
	public boolean isProduceUnknown()
	{
		return produceUnknown;
	}

	/** The earliest the crop can be ready (epoch ms); null when not estimated. */
	public Long getReadyEarliest()
	{
		return readyEarliest;
	}

	/** When the estimate turns READY (the latest possible ready time, epoch ms); null when not estimated. */
	public Long getReadyAt()
	{
		return readyAt;
	}

	public Provenance getProvenance()
	{
		return provenance;
	}

	public long getProvenanceAt()
	{
		return provenanceAt;
	}

	/** The last native observation of this patch (0 when never seen). */
	public long getLastSeenAt()
	{
		return lastSeenAt;
	}

	public boolean isReady()
	{
		return display == Display.READY;
	}

	public boolean needsAttention()
	{
		return display == Display.DISEASED || display == Display.DEAD;
	}

	public boolean isTracked()
	{
		return display != Display.NOT_TRACKED;
	}
}
