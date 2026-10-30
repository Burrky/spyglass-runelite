package com.osrstelemetry.plugin.farming;

/**
 * Applies one native observation to a patch's knowledge. Pure.
 *
 * Retention first: compatible stored crop information survives time away,
 * logouts, new observation sequences and any amount of elapsed time. Only a
 * native observation ends or replaces a cycle:
 *
 * <ul>
 *   <li><b>Planting observed</b> (same-sequence tick-to-tick CHANGE from EMPTY
 *   to GROWING stage 0): a new cycle with that exact {@code plantedAt}, also
 *   kept as the historical {@link PlantingEvent}. Any previous cycle is closed.</li>
 *   <li><b>Cleared</b> (EMPTY or WEEDS): the active cycle closes and is kept as
 *   {@link PatchKnowledge#getLastClosedCycle()}.</li>
 *   <li><b>Diseased or dead</b>: the same cycle, now in that condition, when
 *   it could be the same crop. A dead herb's produce is unknown in the varbit,
 *   so its identity comes from the cycle, but only while that cycle had not
 *   been seen mature (harvestable / check-health crops do not sicken or die).
 *   Disease and death are only ever what was observed, and only a later native
 *   observation replaces them: a cure (DISEASED to GROWING), or a new cycle.</li>
 *   <li><b>UNKNOWN</b> (undecodable): erases nothing; the cycle is kept as is.</li>
 *   <li><b>A crop</b>: the same cycle when it is the same produce and could
 *   follow what was last seen. A dead crop never continues: anything but dead
 *   after it is a new cycle, so the old death, planting time and stage evidence
 *   do not leak into the next crop. A mature crop never goes back to growing,
 *   diseased or dead, and a growing stage never goes backwards. Partial
 *   harvests stay HARVESTABLE in the same cycle. Anything else starts a
 *   FIRST_SEEN cycle (no planting time) and closes the old one.</li>
 * </ul>
 *
 * Stage evidence: a stage change seen as a same-sequence tick-to-tick CHANGE
 * is a "stage transition observed"; one met after a gap is only "stage first
 * seen at" (see {@link CropCycle}).
 *
 * Nothing here runs on a timer: a growth estimate passing never changes
 * knowledge, never refreshes last-seen times and never invents observations.
 */
final class CropCycleAttribution
{
	private CropCycleAttribution()
	{
	}

	static PatchKnowledge apply(PatchKnowledge prior, PatchObservation observation)
	{
		PatchReading reading = observation.getReading();
		long at = observation.getObservedAt();
		FarmingPatchDef patch = observation.getPatch();
		PlantingEvent history = prior == null ? null : prior.getLastObservedPlanting();
		ClosedCropCycle closed = prior == null ? null : prior.getLastClosedCycle();
		CropCycle priorCycle = prior == null ? null : prior.getCycle();
		PatchCondition condition = reading.getCondition();
		boolean witnessed = observation.isContinuousChange();
		CropCycle cycle;

		if (isObservedPlanting(observation))
		{
			if (priorCycle != null)
			{
				closed = new ClosedCropCycle(priorCycle, at, condition);
			}
			history = new PlantingEvent(at, reading.getProduce(), reading.getRaw());
			cycle = CropCycle.started(reading.getProduce(), CropCycle.Provenance.OBSERVED_PLANTING, reading, at, true);
		}
		else if (condition.isBare())
		{
			if (priorCycle != null)
			{
				closed = new ClosedCropCycle(priorCycle, at, condition);
			}
			cycle = null;
		}
		else if (condition == PatchCondition.UNKNOWN)
		{
			cycle = priorCycle;
		}
		else if (condition == PatchCondition.DEAD && reading.getProduce() == null)
		{
			// A dead herb: the varbit no longer says what it was. The cycle says so,
			// unless that cycle could not have died (seen mature, or already dead and
			// so since replaced): then the identity is unknown and no cycle is kept.
			if (priorCycle != null && !isMature(priorCycle.getLastCondition()) && priorCycle.getLastCondition() != PatchCondition.DEAD)
			{
				cycle = priorCycle.seen(reading, at, witnessed);
			}
			else if (priorCycle != null && priorCycle.getLastCondition() == PatchCondition.DEAD
				&& priorCycle.getLastRaw() == reading.getRaw())
			{
				cycle = priorCycle.seen(reading, at, witnessed); // the same dead patch, seen again
			}
			else
			{
				if (priorCycle != null)
				{
					closed = new ClosedCropCycle(priorCycle, at, condition);
				}
				cycle = null;
			}
		}
		else if (priorCycle != null && sameCycle(priorCycle, reading))
		{
			cycle = priorCycle.seen(reading, at, witnessed);
		}
		else
		{
			if (priorCycle != null)
			{
				closed = new ClosedCropCycle(priorCycle, at, condition);
			}
			cycle = CropCycle.started(reading.getProduce(), CropCycle.Provenance.FIRST_SEEN, reading, at, witnessed);
		}

		return new PatchKnowledge(patch.getPatchId(), patch.getOwningRegionId(), patch.getVarbit(), reading.getRaw(),
			reading.getDecoderVersion(), at, Math.max(at, prior == null ? 0L : prior.getLastSeenAt()), cycle, history, closed, false);
	}

	static boolean isObservedPlanting(PatchObservation observation)
	{
		PatchReading now = observation.getReading();
		PatchReading before = observation.getPrevious();
		return observation.isContinuousChange() && before != null
			&& before.getCondition() == PatchCondition.EMPTY
			&& now.getCondition() == PatchCondition.GROWING && now.getStage() == 0 && now.getProduce() != null;
	}

	private static boolean sameCycle(CropCycle cycle, PatchReading reading)
	{
		return cycle.getProduce() == reading.getProduce() && !wentBackwards(cycle, reading);
	}

	/**
	 * Whether {@code reading} cannot be a later state of {@code cycle}:
	 * <ul>
	 *   <li>after DEAD, anything but DEAD (a dead crop is only ever cleared);</li>
	 *   <li>after a mature state (HARVESTABLE, CHECK_HEALTH, or GROWN), growing,
	 *   diseased or dead again, or check-health after harvestable / grown;</li>
	 *   <li>a growing stage earlier than the last growing stage.</li>
	 * </ul>
	 * Disease to growing is a cure and stays the same cycle.
	 */
	private static boolean wentBackwards(CropCycle cycle, PatchReading reading)
	{
		PatchCondition before = cycle.getLastCondition();
		PatchCondition now = reading.getCondition();
		if (before == PatchCondition.DEAD)
		{
			return now != PatchCondition.DEAD;
		}
		if (isMature(before))
		{
			return now == PatchCondition.GROWING || now == PatchCondition.DISEASED || now == PatchCondition.DEAD
				|| (now == PatchCondition.CHECK_HEALTH && before != PatchCondition.CHECK_HEALTH);
		}
		return now == PatchCondition.GROWING && before == PatchCondition.GROWING && reading.getStage() < cycle.getLastStage();
	}

	private static boolean isMature(PatchCondition condition)
	{
		return condition == PatchCondition.HARVESTABLE || condition == PatchCondition.CHECK_HEALTH || condition == PatchCondition.GROWN;
	}

	/**
	 * Re-derives a loaded patch with the current decoder when it was stored by
	 * another version, so the cycle never disagrees with what the stored raw
	 * value now decodes to.
	 * <ul>
	 *   <li>Undecodable now: erases nothing, marks the patch stale.</li>
	 *   <li>The cycle was last seen with this same raw value: its condition and
	 *   stage are corrected in place (times and evidence kept). If the new
	 *   decoder names another crop, or a cleared patch, the cycle no longer
	 *   holds and is dropped.</li>
	 *   <li>The raw value is newer than the cycle (the old decoder could not read
	 *   it): it is applied as a baseline at its own observation time, never as a
	 *   witnessed transition or a planting.</li>
	 * </ul>
	 */
	static PatchKnowledge redecode(PatchKnowledge stored, FarmingPatchDef patch)
	{
		if (stored.getDecoderVersion() == FarmingDecoder.VERSION || patch == null)
		{
			return stored;
		}
		PatchReading reading = FarmingDecoder.decode(patch.getType(), stored.getLastRaw());
		CropCycle cycle = stored.getCycle();
		if (reading.getCondition() == PatchCondition.UNKNOWN)
		{
			return stored.withDecoder(FarmingDecoder.VERSION, cycle, true);
		}
		if (cycle == null || cycle.getLastRaw() == stored.getLastRaw())
		{
			boolean sameCrop = cycle != null && !reading.getCondition().isBare()
				&& (reading.getProduce() == cycle.getProduce() || (reading.getProduce() == null && reading.getCondition() == PatchCondition.DEAD));
			return stored.withDecoder(FarmingDecoder.VERSION, sameCrop ? cycle.redecoded(reading) : null, false);
		}
		PatchKnowledge reapplied = apply(stored, new PatchObservation(PatchObservation.Kind.BASELINE, patch, reading, null,
			stored.getLastObservedAt(), 0L, 0L));
		return reapplied.withDecoder(FarmingDecoder.VERSION, reapplied.getCycle(), false);
	}
}
