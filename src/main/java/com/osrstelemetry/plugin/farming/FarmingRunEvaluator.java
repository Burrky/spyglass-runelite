package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Patch completion for a run (canonical §17): a pure function of the saved
 * intent (baseline and planned crops) and CURRENT native patch knowledge.
 * No step cursor, position or route is involved, so progress stays coherent
 * whatever order the player works in, and across logouts and restarts: the
 * same inputs always give the same answer.
 *
 * <h3>Evidence</h3>
 * Only native observations count, and only ones made after Start
 * ({@code lastSeenAt >= startedAt}). A projection (an estimated READY) never
 * completes anything. An unreadable value (decoder gap or stale decode) is
 * never completion.
 *
 * <h3>Rules (per included patch, first match wins)</h3>
 * <ol>
 *   <li>Not seen since Start: <b>PENDING</b> (not visited).</li>
 *   <li>Unreadable now: <b>PENDING</b> (check the patch).</li>
 *   <li>A crop cycle that began after Start:
 *     <ul>
 *       <li>its planting was observed after Start: <b>DONE · replanted</b>
 *       (the crop actually seen is recorded; a different crop than planned is
 *       flagged, still done);</li>
 *       <li>planting not seen, but the evidence bounds it after Start (the
 *       patch was seen without it after Start, or its stage is too young to
 *       predate Start): <b>DONE · planted (not seen planting)</b>;</li>
 *       <li>otherwise it may have been planted before Start: <b>NOT
 *       APPLICABLE · already growing</b>, never a fabricated new planting.</li>
 *     </ul></li>
 *   <li>Bare (empty / weeds):
 *     <ul>
 *       <li>cleared after Start and harvest only: <b>DONE · harvested</b> (or
 *       <b>cleared</b> when what was removed was dead / diseased / unripe);</li>
 *       <li>cleared after Start with a crop planned: <b>IN PROGRESS</b>
 *       (harvested, not replanted yet);</li>
 *       <li>bare since before Start: harvest only → <b>NOT APPLICABLE ·
 *       nothing to harvest</b>; planned → <b>PENDING · plant</b>.</li>
 *     </ul></li>
 *   <li>A crop present since before Start, by its condition now:
 *     <ul>
 *       <li>growing, and it was diseased at Start: <b>DONE · cured</b>;
 *       otherwise <b>NOT APPLICABLE · still growing</b>;</li>
 *       <li>a health-check type that was CHECK_HEALTH at Start and is now
 *       checked: <b>DONE · health checked</b> (unless a different crop is
 *       planned: then it is in progress until replaced);</li>
 *       <li>a regrowing crop (fruit, berries, cactus) picked down to nothing
 *       since Start: <b>DONE · harvested</b>; partly picked stays pending;</li>
 *       <li>fully grown with nothing to do (a checked tree with no planned
 *       replacement, a grown spirit tree or scarecrow): <b>NOT
 *       APPLICABLE</b>;</li>
 *       <li>anything else (harvestable, diseased, dead, check-health): <b>PENDING</b>
 *       with the action still needed. A partly harvested patch is still
 *       harvestable, so it never finishes early.</li>
 *     </ul></li>
 * </ol>
 * An explicit Skip turns a patch that is not DONE into <b>SKIPPED</b>; it is
 * never counted as done, and an observed completion outranks it.
 */
public final class FarmingRunEvaluator
{
	public enum Outcome
	{
		PENDING,
		IN_PROGRESS,
		DONE,
		NOT_APPLICABLE,
		SKIPPED;

		/** Settled: counts toward "n / N" and toward run completion. */
		public boolean isSettled()
		{
			return this == DONE || this == NOT_APPLICABLE || this == SKIPPED;
		}
	}

	public enum Detail
	{
		NOT_VISITED,
		UNREADABLE,
		TO_HARVEST,
		TO_PLANT,
		TO_CLEAR,
		TO_CURE,
		TO_CHECK_HEALTH,
		TO_REPLACE,
		/** A tree chopped during the run: its stump still has to be dug up before planting. */
		STUMP_TO_CLEAR,
		AWAITING_PLANT,
		REPLANTED,
		PLANTED_UNSEEN,
		HARVESTED,
		CLEARED,
		CURED,
		HEALTH_CHECKED,
		STILL_GROWING,
		ALREADY_GROWING,
		NOTHING_TO_DO,
		SKIPPED,
		NOT_CATALOGUED
	}

	/** One included patch's progress. Immutable. */
	public static final class PatchProgress
	{
		private final String patchId;
		private final FarmingPatchDef patch;
		private final Outcome outcome;
		private final Detail detail;
		private final FarmingProduce planned;
		/** The crop actually seen (now), never the plan. */
		private final FarmingProduce observedCrop;
		private final boolean differsFromPlan;
		private boolean plantPlanned;

		PatchProgress(String patchId, FarmingPatchDef patch, Outcome outcome, Detail detail, FarmingProduce planned, FarmingProduce observedCrop,
			boolean differsFromPlan)
		{
			this.patchId = patchId;
			this.patch = patch;
			this.outcome = outcome;
			this.detail = detail;
			this.planned = planned;
			this.observedCrop = observedCrop;
			this.differsFromPlan = differsFromPlan;
			this.plantPlanned = planned != null;
		}

		PatchProgress planting(boolean plants)
		{
			this.plantPlanned = plants;
			return this;
		}

		private boolean healthCheckDue;
		private boolean healthChecked;
		private int fruitLeft = -1;
		private boolean fruitPicked;

		/** Tree facts observed for this patch (all from native patch state). */
		PatchProgress tree(boolean checkDue, boolean checked, int fruit, boolean picked)
		{
			this.healthCheckDue = checkDue;
			this.healthChecked = checked;
			this.fruitLeft = fruit;
			this.fruitPicked = picked;
			return this;
		}

		/** The tree needed (or needs) a health check in this run. */
		public boolean isHealthCheckDue()
		{
			return healthCheckDue;
		}

		/** Its health check was observed during the run. */
		public boolean isHealthChecked()
		{
			return healthChecked;
		}

		/** Fruit still on a standing fruit tree (-1: not a fruit tree, or not standing). */
		public int getFruitLeft()
		{
			return fruitLeft;
		}

		/** Fruit was observed picked during the run. */
		public boolean isFruitPicked()
		{
			return fruitPicked;
		}

		/** The plan is to plant here (an explicit crop, or "anything suitable"); false for harvest only. */
		public boolean isPlantPlanned()
		{
			return plantPlanned;
		}

		public String getPatchId()
		{
			return patchId;
		}

		/** Null only when the patch is no longer catalogued. */
		public FarmingPatchDef getPatch()
		{
			return patch;
		}

		public Outcome getOutcome()
		{
			return outcome;
		}

		public Detail getDetail()
		{
			return detail;
		}

		public FarmingProduce getPlanned()
		{
			return planned;
		}

		public FarmingProduce getObservedCrop()
		{
			return observedCrop;
		}

		/** Replanted with something other than the crop the player explicitly chose (recorded, still done; never for "anything suitable"). */
		public boolean isDifferentFromPlan()
		{
			return differsFromPlan;
		}
	}

	/** The whole run, derived. Immutable. */
	public static final class Progress
	{
		private final Map<String, PatchProgress> patches;
		private final int settled;
		private final int done;

		Progress(Map<String, PatchProgress> patches)
		{
			this.patches = Collections.unmodifiableMap(patches);
			int s = 0;
			int d = 0;
			for (PatchProgress p : patches.values())
			{
				s += p.getOutcome().isSettled() ? 1 : 0;
				d += p.getOutcome() == Outcome.DONE ? 1 : 0;
			}
			this.settled = s;
			this.done = d;
		}

		/** In the intent's order. */
		public Map<String, PatchProgress> getPatches()
		{
			return patches;
		}

		public PatchProgress patch(String id)
		{
			return patches.get(id);
		}

		public int getTotal()
		{
			return patches.size();
		}

		/** DONE, SKIPPED and NOT APPLICABLE. */
		public int getSettled()
		{
			return settled;
		}

		public int getDone()
		{
			return done;
		}

		public boolean isAllSettled()
		{
			return !patches.isEmpty() && settled == patches.size();
		}

		public int count(Outcome outcome)
		{
			int n = 0;
			for (PatchProgress p : patches.values())
			{
				n += p.getOutcome() == outcome ? 1 : 0;
			}
			return n;
		}

		public int count(Detail detail)
		{
			int n = 0;
			for (PatchProgress p : patches.values())
			{
				n += p.getDetail() == detail ? 1 : 0;
			}
			return n;
		}

		/** A stable fingerprint of every patch's outcome and detail (changes exactly when progress changes). */
		public String signature()
		{
			StringBuilder b = new StringBuilder();
			for (PatchProgress p : patches.values())
			{
				b.append(p.getPatchId()).append(':').append(p.getOutcome()).append('/').append(p.getDetail()).append(';');
			}
			return b.toString();
		}

		/**
		 * Remaining (unsettled) patches, those at {@code here} first, then the
		 * intent's order: "next remaining", never a route or a planned order.
		 */
		public List<PatchProgress> remaining(Set<String> here)
		{
			List<PatchProgress> first = new ArrayList<>();
			List<PatchProgress> rest = new ArrayList<>();
			for (PatchProgress p : patches.values())
			{
				if (!p.getOutcome().isSettled())
				{
					(here != null && here.contains(p.getPatchId()) ? first : rest).add(p);
				}
			}
			first.addAll(rest);
			return first;
		}
	}

	private static final long MINUTE = 60_000L;

	private FarmingRunEvaluator()
	{
	}

	public static Progress evaluate(FarmingRunIntent intent, FarmingPatchCatalog catalog, Map<String, PatchKnowledge> knowledge)
	{
		Map<String, PatchProgress> out = new LinkedHashMap<>();
		for (String id : intent.getIncludedPatchIds())
		{
			out.put(id, patch(intent, catalog.patch(id), id, knowledge.get(id)));
		}
		return new Progress(out);
	}

	static PatchProgress patch(FarmingRunIntent intent, FarmingPatchDef def, String id, PatchKnowledge k)
	{
		FarmingProduce planned = intent.plannedCrop(id);
		boolean plants = intent.plansToPlant(id);
		if (def == null)
		{
			return new PatchProgress(id, null, Outcome.NOT_APPLICABLE, Detail.NOT_CATALOGUED, planned, null, false).planting(plants);
		}
		PatchProgress p = derive(intent, def, id, k, planned, plants).planting(plants);
		if (p.getOutcome() != Outcome.DONE && intent.isSkipped(id))
		{
			return new PatchProgress(id, def, Outcome.SKIPPED, Detail.SKIPPED, planned, p.getObservedCrop(), false).planting(plants);
		}
		return p;
	}

	private static PatchProgress derive(FarmingRunIntent intent, FarmingPatchDef def, String id, PatchKnowledge k, FarmingProduce planned,
		boolean plants)
	{
		long start = intent.getStartedAt();
		FarmingRunIntent.Baseline base = intent.baseline(id);
		CropCycle cycle = k == null ? null : k.getCycle();
		FarmingProduce seen = cycle == null ? null : cycle.getProduce();
		if (k == null || k.getLastSeenAt() < start)
		{
			FarmingProduce known = cycle != null ? seen : base == null ? null : base.getProduce();
			return of(id, def, Outcome.PENDING, Detail.NOT_VISITED, planned, known);
		}
		PatchReading now = FarmingDecoder.decode(def.getType(), k.getLastRaw());
		if (k.isDecodeStale() || now.getCondition() == PatchCondition.UNKNOWN)
		{
			return of(id, def, Outcome.PENDING, Detail.UNREADABLE, planned, seen);
		}
		ClosedCropCycle closed = k.getLastClosedCycle();
		boolean closedBareSinceStart = closed != null && closed.getClosedBy() != null && closed.getClosedBy().isBare() && closed.getClosedAt() >= start;
		// A clear during the run: a cycle seen cleared after Start, or (no cycle to record it, e.g. a dead herb
		// of unknown kind) a crop the run itself saw after Start that is now gone.
		boolean clearedSinceStart = closedBareSinceStart || intent.wasCropSeenSinceStart(id) && now.getCondition().isBare();
		Long baseCycle = base == null ? null : base.getCycleFirstSeenAt();

		// 3. A crop cycle that began after Start.
		if (cycle != null && cycle.getFirstSeenAt() >= start && (baseCycle == null || cycle.getFirstSeenAt() != baseCycle))
		{
			boolean differs = planned != null && seen != null && planned != seen;
			if (cycle.getProvenance() == CropCycle.Provenance.OBSERVED_PLANTING && cycle.getPlantedAt() != null && cycle.getPlantedAt() >= start)
			{
				return new PatchProgress(id, def, Outcome.DONE, Detail.REPLANTED, planned, seen, differs);
			}
			if (plantedAfter(cycle, base, closed) >= start)
			{
				return new PatchProgress(id, def, Outcome.DONE, Detail.PLANTED_UNSEEN, planned, seen, differs);
			}
			if (now.getCondition() == PatchCondition.GROWING)
			{
				return of(id, def, Outcome.NOT_APPLICABLE, Detail.ALREADY_GROWING, planned, seen);
			}
			// Planted before Start (or unknown) and now ready, diseased, dead or checkable: judged by what is observed
			// now (rule 5), never as "already growing".
		}

		// 4. Bare.
		if (now.getCondition().isBare() || cycle == null && !now.getCondition().holdsCrop())
		{
			if (clearedSinceStart)
			{
				if (plants)
				{
					return of(id, def, Outcome.IN_PROGRESS, Detail.AWAITING_PLANT, planned, null);
				}
				PatchCondition removed = closedBareSinceStart && closed.getCycle() != null ? closed.getCycle().getLastCondition()
					: base == null ? null : base.getCondition();
				return of(id, def, Outcome.DONE, removed == PatchCondition.HARVESTABLE ? Detail.HARVESTED : Detail.CLEARED, planned, null);
			}
			return !plants
				? of(id, def, Outcome.NOT_APPLICABLE, Detail.NOTHING_TO_DO, null, null)
				: of(id, def, Outcome.PENDING, Detail.TO_PLANT, planned, null);
		}

		// 5. A crop present since before Start (its produce may be unknown, e.g. a dead herb).
		PatchCondition baseCondition = base == null ? null : base.getCondition();
		FarmingProduce crop = seen != null ? seen : now.getProduce();
		boolean replace = planned != null && crop != null && planned != crop;
		switch (now.getCondition())
		{
			case GROWING:
				if (baseCondition == PatchCondition.DISEASED)
				{
					return of(id, def, Outcome.DONE, Detail.CURED, planned, crop);
				}
				return of(id, def, Outcome.NOT_APPLICABLE, Detail.STILL_GROWING, planned, crop);
			case DISEASED:
				return of(id, def, Outcome.PENDING, Detail.TO_CURE, planned, crop);
			case DEAD:
				return of(id, def, Outcome.PENDING, Detail.TO_CLEAR, planned, crop);
			case CHECK_HEALTH:
				return of(id, def, Outcome.PENDING, Detail.TO_CHECK_HEALTH, planned, crop).tree(true, false, -1, false);
			case GROWN:
				if (replace)
				{
					return of(id, def, Outcome.PENDING, Detail.TO_REPLACE, planned, crop);
				}
				// A spirit tree checked during the run is done; a grown one left alone has nothing to do.
				return baseCondition == PatchCondition.CHECK_HEALTH ? of(id, def, Outcome.DONE, Detail.HEALTH_CHECKED, planned, crop)
					: of(id, def, Outcome.NOT_APPLICABLE, Detail.NOTHING_TO_DO, planned, crop);
			case HARVESTABLE:
			default:
				if (FarmingPreferences.needsClearing(def.getType()) && def.getType() != FarmingPatchType.SPIRIT_TREE)
				{
					return tree(def, id, base, baseCondition, now, planned, crop, plants, intent.getStagePeak(id));
				}
				return harvestable(def, id, base, baseCondition, now, planned, crop, replace, intent.getStagePeak(id));
		}
	}

	/**
	 * A tree-like patch (regular, fruit, hardwood, redwood, calquat, celastrus,
	 * crystal) standing or as a stump. Replanting needs the old tree out: chopped
	 * and dug up, or removed by the gardener; chopping alone (a stump) is never
	 * a clear. Only the observed outcome counts: health checked (a check-health
	 * tree seen standing), cleared (seen bare: handled by the bare rule), planted.
	 * Fruit still on a fruit tree is picked first (the stage counts it).
	 */
	private static PatchProgress tree(FarmingPatchDef def, String id, FarmingRunIntent.Baseline base, PatchCondition baseCondition, PatchReading now,
		FarmingProduce planned, FarmingProduce crop, boolean plants, int peakSinceStart)
	{
		boolean checked = baseCondition == PatchCondition.CHECK_HEALTH;
		boolean stump = now.getSubState() == PatchSubState.STUMP;
		boolean fruit = !stump && crop != null && crop.getRegrowMinutes() > 0;
		boolean picked = fruit && (baseCondition == PatchCondition.HARVESTABLE && base != null && base.getStage() > now.getStage()
			|| peakSinceStart > now.getStage());
		PatchSubState baseSub = base == null || base.getTruth() != FarmingRunIntent.Baseline.Truth.OBSERVED || base.getCondition() != PatchCondition.HARVESTABLE
			? null : FarmingDecoder.decode(def.getType(), base.getRaw()).getSubState();
		// Chopped during the run: a stump now where a standing (or check-health) tree stood at Start.
		boolean chopped = stump && (baseSub == PatchSubState.STANDING || baseCondition == PatchCondition.CHECK_HEALTH);
		if (!plants)
		{
			if (fruit && now.getStage() > 0)
			{
				return of(id, def, picked ? Outcome.IN_PROGRESS : Outcome.PENDING, Detail.TO_HARVEST, planned, crop).tree(checked, checked, now.getStage(),
					picked);
			}
			if (fruit && picked)
			{
				return of(id, def, Outcome.DONE, Detail.HARVESTED, planned, crop);
			}
			if (checked)
			{
				return of(id, def, Outcome.DONE, Detail.HEALTH_CHECKED, planned, crop);
			}
			if (chopped)
			{
				return of(id, def, Outcome.DONE, Detail.HARVESTED, planned, crop);
			}
			return of(id, def, Outcome.NOT_APPLICABLE, Detail.NOTHING_TO_DO, planned, crop);
		}
		boolean progressed = checked || picked || chopped;
		int fruitLeft = fruit ? now.getStage() : -1;
		if (stump)
		{
			return of(id, def, Outcome.IN_PROGRESS, Detail.STUMP_TO_CLEAR, planned, crop).tree(checked, checked, fruitLeft, picked);
		}
		return of(id, def, progressed ? Outcome.IN_PROGRESS : Outcome.PENDING, Detail.TO_REPLACE, planned, crop).tree(checked, checked, fruitLeft, picked);
	}

	private static PatchProgress harvestable(FarmingPatchDef def, String id, FarmingRunIntent.Baseline base, PatchCondition baseCondition,
		PatchReading now, FarmingProduce planned, FarmingProduce crop, boolean replace, int peakSinceStart)
	{
		boolean checkedSinceStart = def.getType().isHealthCheckRequired() && baseCondition == PatchCondition.CHECK_HEALTH;
		if (checkedSinceStart)
		{
			return replace ? of(id, def, Outcome.IN_PROGRESS, Detail.TO_REPLACE, planned, crop)
				: of(id, def, Outcome.DONE, Detail.HEALTH_CHECKED, planned, crop);
		}
		boolean regrows = crop != null && crop.getRegrowMinutes() > 0;
		if (regrows && now.getSubState() != PatchSubState.STUMP)
		{
			// Fruit / berries / cactus: the stage counts what is left to pick.
			if (now.getStage() == 0)
			{
				boolean pickedSinceStart = baseCondition == PatchCondition.HARVESTABLE && base.getStage() > 0 || peakSinceStart > 0;
				if (replace)
				{
					return of(id, def, pickedSinceStart ? Outcome.IN_PROGRESS : Outcome.PENDING, Detail.TO_REPLACE, planned, crop);
				}
				return pickedSinceStart ? of(id, def, Outcome.DONE, Detail.HARVESTED, planned, crop)
					: of(id, def, Outcome.NOT_APPLICABLE, Detail.NOTHING_TO_DO, planned, crop);
			}
			return of(id, def, Outcome.PENDING, Detail.TO_HARVEST, planned, crop);
		}
		if (now.getSubState() == PatchSubState.STANDING || now.getSubState() == PatchSubState.STUMP)
		{
			// A checked tree (or its stump): nothing to do unless a crop is planned.
			return planned == null ? of(id, def, Outcome.NOT_APPLICABLE, Detail.NOTHING_TO_DO, null, crop)
				: of(id, def, Outcome.PENDING, Detail.TO_REPLACE, planned, crop);
		}
		return of(id, def, Outcome.PENDING, Detail.TO_HARVEST, planned, crop);
	}

	/**
	 * The earliest time an unobserved new cycle can have been planted: after
	 * the patch was last seen without it (the baseline sighting, or a clear
	 * seen after Start), and no earlier than its first-seen stage allows.
	 */
	static long plantedAfter(CropCycle cycle, FarmingRunIntent.Baseline base, ClosedCropCycle closed)
	{
		long bound = Long.MIN_VALUE;
		if (base != null && base.getTruth() == FarmingRunIntent.Baseline.Truth.OBSERVED
			&& (base.getCycleFirstSeenAt() == null || base.getCycleFirstSeenAt() != cycle.getFirstSeenAt()))
		{
			bound = Math.max(bound, base.getLastSeenAt());
		}
		if (closed != null && closed.getClosedBy() != null && closed.getClosedBy().isBare() && closed.getClosedAt() <= cycle.getFirstSeenAt())
		{
			bound = Math.max(bound, closed.getClosedAt());
		}
		FarmingProduce p = cycle.getProduce();
		if (p != null)
		{
			// Seen at stage s => at most s + 1 growth ticks old when first seen (the
			// current stage is the most it can have been at first sight).
			bound = Math.max(bound, cycle.getFirstSeenAt() - (long) (cycle.getLastStage() + 1) * p.getTickMinutes() * MINUTE);
		}
		return bound;
	}

	/**
	 * The intent with today's crop sightings added ({@link FarmingRunIntent#wasCropSeenSinceStart}):
	 * every included patch seen after Start holding a crop. Returns the same
	 * instance when nothing new was seen.
	 */
	public static FarmingRunIntent withEvidence(FarmingRunIntent intent, FarmingPatchCatalog catalog, Map<String, PatchKnowledge> knowledge)
	{
		List<String> add = new ArrayList<>();
		for (String id : intent.getIncludedPatchIds())
		{
			PatchKnowledge k = knowledge.get(id);
			FarmingPatchDef def = catalog.patch(id);
			if (k == null || def == null || k.getLastSeenAt() < intent.getStartedAt() || k.isDecodeStale() || intent.wasCropSeenSinceStart(id))
			{
				continue;
			}
			if (FarmingDecoder.decode(def.getType(), k.getLastRaw()).getCondition().holdsCrop())
			{
				add.add(id);
			}
		}
		FarmingRunIntent out = add.isEmpty() ? intent : intent.withCropSeen(add);
		// What is left on a regrowing crop, at its most since Start (picking is counted from the run's own sightings).
		Map<String, Integer> peaks = new java.util.LinkedHashMap<>();
		for (String id : intent.getIncludedPatchIds())
		{
			PatchKnowledge k = knowledge.get(id);
			FarmingPatchDef def = catalog.patch(id);
			if (k == null || def == null || k.getLastSeenAt() < intent.getStartedAt() || k.isDecodeStale())
			{
				continue;
			}
			PatchReading r = FarmingDecoder.decode(def.getType(), k.getLastRaw());
			FarmingProduce p = r.getProduce();
			if (r.getCondition() == PatchCondition.HARVESTABLE && p != null && p.getRegrowMinutes() > 0 && r.getSubState() != PatchSubState.STUMP
				&& r.getStage() > out.getStagePeak(id))
			{
				peaks.put(id, r.getStage());
			}
		}
		return peaks.isEmpty() ? out : out.withStagePeaks(peaks);
	}

	private static PatchProgress of(String id, FarmingPatchDef def, Outcome outcome, Detail detail, FarmingProduce planned, FarmingProduce crop)
	{
		return new PatchProgress(id, def, outcome, detail, planned, crop, false);
	}
}
