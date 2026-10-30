package com.osrstelemetry.plugin.farming;

/**
 * Patch knowledge + crop data + calibration + now -> {@link PatchStatus}.
 * Pure functions: a projection never writes knowledge, never refreshes a
 * last-seen time, never invents a planting, never claims a health check and
 * never clears a known disease or death.
 *
 * <h3>Precedence (canonical §18.4, Phase B prep §7.1)</h3>
 * <ol>
 *   <li>Never observed: NOT_TRACKED.</li>
 *   <li>Latest reading undecodable (or a stale re-decode): CHECK_PATCH, with the
 *   last known crop kept for context.</li>
 *   <li>Observed DEAD / DISEASED: that condition, whatever time has passed.</li>
 *   <li>Observed empty / weeds: CLEAR.</li>
 *   <li>Observed harvestable or check-health: READY (OBSERVED).</li>
 *   <li>Observed GROWN: GROWN.</li>
 *   <li>Growing with a known crop and a timeable type: READY (ESTIMATED) once the
 *   latest possible ready time has passed, otherwise GROWING_TIMED.</li>
 *   <li>Anything else growing: GROWING (no invented timer).</li>
 * </ol>
 *
 * <h3>The estimate</h3>
 * Anchored on the LATEST native observation of the current stage
 * ({@code cycle.lastSeenAt}), never on an older planting time: a newer reading
 * that the crop is still growing always wins (the planting time is kept as
 * history and provenance only). The crop needs {@code readyStage - stage}
 * more growth boundaries; the first comes after the anchor, inside the
 * calibration window, and no later than one tick after the stage was first
 * seen (the stage was present by then, so it began no later). A stage first
 * seen after a gap therefore narrows the window without being treated as the
 * moment it began. READY uses the latest bound, so it is never early.
 *
 * <p>Repeated sightings of the same stage never push READY back: within one
 * growth tick the bound above holds the latest time still. A stage seen
 * unchanged for longer than a whole growth tick contradicts the timing model
 * (growth paused by disease, or a crop's data is off), so the patch is shown
 * as growing WITHOUT a timer rather than with an estimate that slides later at
 * every sighting.
 */
public final class FarmingProjection
{
	static final long SEEN_JUST_NOW_MILLIS = 90_000L;
	private static final long MINUTE = 60_000L;

	private FarmingProjection()
	{
	}

	public static PatchStatus project(FarmingPatchDef patch, PatchKnowledge k, FarmingCalibration calibration, long now)
	{
		if (k == null)
		{
			return new PatchStatus(PatchStatus.Display.NOT_TRACKED, null, null, false, null, null, PatchStatus.Provenance.NONE, 0L, 0L);
		}
		CropCycle cycle = k.getCycle();
		PatchReading latest = FarmingDecoder.decode(patch.getType(), k.getLastRaw());
		FarmingProduce produce = cycle != null ? cycle.getProduce() : latest.getProduce();
		if (produce == FarmingProduce.WEEDS)
		{
			produce = null;
		}
		if (k.isDecodeStale() || latest.getCondition() == PatchCondition.UNKNOWN)
		{
			return status(PatchStatus.Display.CHECK_PATCH, null, produce, false, null, null, k, cycle, now);
		}
		PatchCondition condition = latest.getCondition();
		switch (condition)
		{
			case DEAD:
				return status(PatchStatus.Display.DEAD, null, produce, produce == null, null, null, k, cycle, now);
			case DISEASED:
				return status(PatchStatus.Display.DISEASED, null, produce, produce == null, null, null, k, cycle, now);
			case EMPTY:
			case WEEDS:
				return status(PatchStatus.Display.CLEAR, null, null, false, null, null, k, null, now);
			case HARVESTABLE:
			case CHECK_HEALTH:
				return status(PatchStatus.Display.READY, PatchStatus.ReadySource.OBSERVED, produce, false, null, null, k, cycle, now);
			case GROWN:
				return status(PatchStatus.Display.GROWN, null, produce, false, null, null, k, cycle, now);
			case GROWING:
			default:
				break;
		}
		long[] window = cycle == null || produce == null ? null : readyWindow(patch.getType(), cycle, produce, calibration);
		if (window == null)
		{
			return status(PatchStatus.Display.GROWING, null, produce, false, null, null, k, cycle, now);
		}
		if (now >= window[1])
		{
			return status(PatchStatus.Display.READY, PatchStatus.ReadySource.ESTIMATED, produce, false, window[0], window[1], k, cycle, now);
		}
		return status(PatchStatus.Display.GROWING_TIMED, null, produce, false, window[0], window[1], k, cycle, now);
	}

	/** True for types whose growth ends in a ready state Spyglass can time (anima never becomes harvestable). */
	static boolean isTimeable(FarmingPatchType type)
	{
		return type != FarmingPatchType.ANIMA;
	}

	/**
	 * {earliest, latest} ready time for a growing cycle, or null when it cannot
	 * be timed defensibly. Uses only the cycle's native evidence.
	 */
	static long[] readyWindow(FarmingPatchType type, CropCycle cycle, FarmingProduce produce, FarmingCalibration calibration)
	{
		int tick = produce.getTickMinutes();
		int remaining = produce.getReadyStage() - cycle.getLastStage();
		if (!isTimeable(type) || tick <= 0 || cycle.getLastCondition() != PatchCondition.GROWING || cycle.getLastStage() < 0 || remaining <= 0)
		{
			return null;
		}
		long anchor = cycle.getLastSeenAt();
		if (anchor - cycle.getStageFirstSeenAt() > (long) tick * MINUTE + MINUTE)
		{
			return null; // held longer than a growth tick: no defensible timer
		}
		long[] next = calibration.nextBoundary(anchor, tick);
		long earliest = next[0];
		long latest = next[1];
		// The stage was present by stageFirstSeenAt, so it began no later; its next boundary is within one tick of that.
		long bound = Math.floorDiv(cycle.getStageFirstSeenAt(), MINUTE) * MINUTE + tick * MINUTE;
		if (bound >= earliest && bound < latest)
		{
			latest = bound;
		}
		long rest = (long) (remaining - 1) * tick * MINUTE;
		return new long[] {earliest + rest, latest + rest};
	}

	private static PatchStatus status(PatchStatus.Display display, PatchStatus.ReadySource source, FarmingProduce produce, boolean produceUnknown,
		Long earliest, Long readyAt, PatchKnowledge k, CropCycle cycle, long now)
	{
		long lastSeen = k.getLastSeenAt();
		PatchStatus.Provenance provenance;
		long at;
		if (now - lastSeen < SEEN_JUST_NOW_MILLIS)
		{
			provenance = PatchStatus.Provenance.SEEN_JUST_NOW;
			at = lastSeen;
		}
		else if (display == PatchStatus.Display.DISEASED)
		{
			provenance = PatchStatus.Provenance.LAST_SEEN_DISEASED;
			at = lastSeen;
		}
		else if (display == PatchStatus.Display.DEAD)
		{
			provenance = PatchStatus.Provenance.LAST_SEEN_DEAD;
			at = lastSeen;
		}
		else if (cycle != null && cycle.getPlantedAt() != null && display != PatchStatus.Display.CHECK_PATCH)
		{
			provenance = PatchStatus.Provenance.PLANTED;
			at = cycle.getPlantedAt();
		}
		else if (cycle != null && display == PatchStatus.Display.GROWING_TIMED || cycle != null && display == PatchStatus.Display.GROWING)
		{
			provenance = PatchStatus.Provenance.SEEN_GROWING;
			at = cycle.getFirstSeenAt();
		}
		else
		{
			provenance = PatchStatus.Provenance.LAST_SEEN;
			at = lastSeen;
		}
		return new PatchStatus(display, source, produce, produceUnknown, earliest, readyAt, provenance, at, lastSeen);
	}
}
