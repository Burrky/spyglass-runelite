package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The run as the Farming page and the Home card show it (canonical §16, §20,
 * §24, wireframes H and I, without any travel): derived on the EDT from the
 * snapshot's intent and knowledge; pure and immutable.
 *
 * Wording describes only what Phase E does: the player carries out the run
 * and Spyglass ticks patches off from what it observes. There is no route,
 * teleport, supply list or READY TO GO.
 */
public final class FarmingRunView
{
	public enum Phase
	{
		/** Phase H: an active run whose preparation stage is open (supplies and travel to get). */
		PREPARING,
		ACTIVE,
		PAUSED,
		COMPLETE
	}

	/** One action a patch may need this run, and whether it is evidenced done. */
	public static final class Step
	{
		public enum State
		{
			/** Native evidence shows it done this run. */
			DONE,
			/** Still to do (or not visited yet). */
			TODO,
			/** Spyglass cannot observe it (compost): never shown as done. */
			UNKNOWN,
			/** Nothing to do (the patch was already empty). */
			NOT_NEEDED
		}

		private final String label;
		private final State state;

		Step(String label, State state)
		{
			this.label = label;
			this.state = state;
		}

		/** "Harvest", "Clear", "Compost", "Plant". */
		public String getLabel()
		{
			return label;
		}

		public State getState()
		{
			return state;
		}

		/** "✓ Harvest", "○ Plant", "? Compost", "– Harvest". */
		public String getText()
		{
			switch (state)
			{
				case DONE:
					return "\u2713 " + label;
				case UNKNOWN:
					return "? " + label;
				case NOT_NEEDED:
					return "\u2013 " + label;
				default:
					return "\u25cb " + label;
			}
		}
	}

	/** One included patch on the run page. */
	public static final class PatchLine
	{
		private final FarmingRunEvaluator.PatchProgress progress;
		private final FarmingModel.Row row;
		private final String runText;
		private final FarmingModel.Tone runTone;
		private final boolean here;
		/** The stop guidance is on now (canonical §28.12): led with and highlighted on every surface. */
		boolean current;
		private List<Step> steps = Collections.emptyList();
		private String condition = "";
		private String accessNote;
		/** The compost the game showed on this patch's current (or next) planting; null when none was seen. */
		private PatchCompost compost;

		/** Compost still due on this planting after it went in (§28.17), and whether the player skipped it. */
		private boolean compostDue;
		private boolean compostSkipped;

		public boolean isCompostDue()
		{
			return compostDue;
		}

		public boolean isCompostSkipped()
		{
			return compostSkipped;
		}

		/** Protection payment still due for this patch in this run (§28.16), and whether it was skipped. */
		private boolean protectionDue;
		private boolean protectionSkipped;

		public boolean isProtectionDue()
		{
			return protectionDue;
		}

		public boolean isProtectionSkipped()
		{
			return protectionSkipped;
		}

		/** The compost the game showed on this patch's planting (NONE: Inspect said untreated); null when not seen. */
		public PatchCompost getCompost()
		{
			return compost;
		}

		PatchLine(FarmingRunEvaluator.PatchProgress progress, FarmingModel.Row row, String runText, FarmingModel.Tone runTone, boolean here)
		{
			this.progress = progress;
			this.row = row;
			this.runText = runText;
			this.runTone = runTone;
			this.here = here;
			this.current = false;
		}

		/** Compact progress for the actions this patch needs (empty when nothing is to be done). */
		public List<Step> getSteps()
		{
			return steps;
		}

		/** The patch's current observed condition, kept apart from estimates ("Ready · seen just now", "Growing · ready in ~40m (estimate)"). */
		public String getCondition()
		{
			return condition;
		}

		/** Set when the patch is no longer confirmed usable on this account ("Locked: needs …"); null otherwise. */
		public String getAccessNote()
		{
			return accessNote;
		}

		public FarmingRunEvaluator.PatchProgress getProgress()
		{
			return progress;
		}

		/** The patch's live status row (location, observed crop, chip, provenance); null if no longer catalogued. */
		public FarmingModel.Row getRow()
		{
			return row;
		}

		public String getPatchId()
		{
			return progress.getPatchId();
		}

		/** "✓ Replanted · Ranarr", "Harvest, then plant Ranarr", "Still growing", "Skipped", ... */
		public String getRunText()
		{
			return runText;
		}

		public FarmingModel.Tone getRunTone()
		{
			return runTone;
		}

		/** The player is at this patch right now. */
		public boolean isHere()
		{
			return here;
		}

		/** Part of the stop guidance is on now. */
		public boolean isCurrent()
		{
			return current;
		}

		/** Skip is offered on patches that still need something done. */
		public boolean isSkippable()
		{
			FarmingRunEvaluator.Outcome o = progress.getOutcome();
			return o == FarmingRunEvaluator.Outcome.PENDING || o == FarmingRunEvaluator.Outcome.IN_PROGRESS;
		}

		public boolean isSkipped()
		{
			return progress.getOutcome() == FarmingRunEvaluator.Outcome.SKIPPED;
		}
	}

	private final FarmingRunIntent intent;
	private final FarmingRunEvaluator.Progress progress;
	private final Phase phase;
	private final List<PatchLine> lines;
	private String nextTitle;
	/** The next stop is where the player is now (the home card says "at", not "next"). */
	private boolean nextHere;
	/** The patches transmitting where the player is, and the compost bins as last seen (canonical §28.15). */
	private Set<String> herePatchIds = java.util.Collections.emptySet();
	private java.util.Map<String, CompostBin.Seen> bins = java.util.Collections.emptyMap();

	public Set<String> getHerePatchIds()
	{
		return herePatchIds;
	}

	public java.util.Map<String, CompostBin.Seen> getBins()
	{
		return bins;
	}

	private List<RunExtras.BinWork> binWork = java.util.Collections.emptyList();

	/** The compost bins this run took on, each with its task now (§28.16); empty for runs without bins. */
	public List<RunExtras.BinWork> getBinWork()
	{
		return binWork;
	}
	private List<String> nextLines;
	private final List<String> summary;
	private final long now;

	private FarmingRunView(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress, Phase phase, List<PatchLine> lines,
		String nextTitle, List<String> nextLines, List<String> summary, long now)
	{
		this.intent = intent;
		this.progress = progress;
		this.phase = phase;
		this.lines = Collections.unmodifiableList(lines);
		this.nextTitle = nextTitle;
		this.nextLines = Collections.unmodifiableList(nextLines);
		this.summary = Collections.unmodifiableList(summary);
		this.now = now;
	}

	static FarmingRunView build(FarmingRunIntent intent, FarmingPatchCatalog catalog, FarmingSnapshot snapshot, java.util.Map<String, FarmingModel.Row> rows,
		long now)
	{
		return build(intent, catalog, snapshot, rows, now, FarmingAccess.Resolver.NONE, false);
	}

	static FarmingRunView build(FarmingRunIntent intent, FarmingPatchCatalog catalog, FarmingSnapshot snapshot, java.util.Map<String, FarmingModel.Row> rows,
		long now, FarmingAccess.Resolver access, boolean checkAccess)
	{
		FarmingRunEvaluator.Progress progress = FarmingRunEvaluator.evaluate(intent, catalog, snapshot.getKnowledge());
		Phase phase = intent.getState() == FarmingRunIntent.State.COMPLETE ? Phase.COMPLETE
			: intent.getState() == FarmingRunIntent.State.PAUSED ? Phase.PAUSED : intent.isPreparing() ? Phase.PREPARING : Phase.ACTIVE;
		Set<String> here = snapshot.getHerePatchIds();
		List<String> current = snapshot.getCurrentPatchIds();
		List<FarmingRunEvaluator.PatchProgress> ordered = runOrder(intent, progress, current);
		List<PatchLine> lines = new ArrayList<>();
		for (FarmingRunEvaluator.PatchProgress p : ordered)
		{
			FarmingModel.Tone tone = tone(p);
			FarmingModel.Row row = rows.get(p.getPatchId());
			PatchLine line = new PatchLine(p, row, runText(p), tone, here.contains(p.getPatchId()));
			line.current = current.contains(p.getPatchId()) && !p.getOutcome().isSettled();
			// The compost of the planting this run makes, never the previous crop's (§28.17).
			line.compost = snapshot == null ? null : RunExtras.compostForRun(intent, p.getPatchId(), snapshot.getKnowledge(), snapshot.getCompost());
			line.compostSkipped = intent.isCompostSkipped(p.getPatchId());
			boolean plantedNow = p.getDetail() == FarmingRunEvaluator.Detail.REPLANTED || p.getDetail() == FarmingRunEvaluator.Detail.PLANTED_UNSEEN;
			line.compostDue = intent.getProtectTypes() != null && plantedNow && RunExtras.compostDue(intent, p, line.compost);
			line.protectionDue = intent.getProtectTypes() != null && FarmingWording.protectionPending(p, intent, null);
			line.protectionSkipped = intent.isProtectionSkipped(p.getPatchId()) && p.getOutcome().isSettled();
			line.steps = steps(intent, p, line.compost);
			line.condition = row == null ? "" : FarmingText.condition(row.getStatus(), now);
			if (checkAccess && !p.getOutcome().isSettled())
			{
				FarmingAccess.Access a = access.access(p.getPatchId());
				if (a.getState() == FarmingAccess.Access.State.LOCKED)
				{
					line.accessNote = "Locked: needs " + String.join("; ", a.getReasons());
				}
				else if (a.getState() == FarmingAccess.Access.State.UNVERIFIED)
				{
					line.accessNote = "Access not confirmed yet";
				}
			}
			lines.add(line);
		}
		String nextTitle = null;
		boolean nextHere = false;
		List<String> nextLines = new ArrayList<>();
		List<FarmingRunEvaluator.PatchProgress> remaining = new ArrayList<>();
		for (FarmingRunEvaluator.PatchProgress p : ordered)
		{
			if (!p.getOutcome().isSettled())
			{
				remaining.add(p);
			}
		}
		// The patches here first, then the suggested order (never a route: any order counts).
		List<FarmingRunEvaluator.PatchProgress> atHere = new ArrayList<>();
		for (FarmingRunEvaluator.PatchProgress p : remaining)
		{
			if (here.contains(p.getPatchId()))
			{
				atHere.add(p);
			}
		}
		List<FarmingRunEvaluator.PatchProgress> atCurrent = new ArrayList<>();
		for (FarmingRunEvaluator.PatchProgress p : remaining)
		{
			if (current.contains(p.getPatchId()))
			{
				atCurrent.add(p);
			}
		}
		if (!atCurrent.isEmpty() && !atCurrent.equals(atHere))
		{
			FarmingRunEvaluator.PatchProgress first = atCurrent.get(0);
			nextTitle = stopTitle(first, atCurrent);
			for (FarmingRunEvaluator.PatchProgress p : atCurrent)
			{
				FarmingModel.Row row = rows.get(p.getPatchId());
				nextLines.add(runText(p) + (atCurrent.size() > 1 && row != null ? " (" + shortLabel(row) + ")" : ""));
			}
		}
		else if (!atHere.isEmpty())
		{
			FarmingRunEvaluator.PatchProgress first = atHere.get(0);
			nextTitle = stopTitle(first, atHere);
			nextHere = true;
			for (FarmingRunEvaluator.PatchProgress p : atHere)
			{
				FarmingModel.Row row = rows.get(p.getPatchId());
				nextLines.add(runText(p) + (atHere.size() > 1 && row != null ? " (" + shortLabel(row) + ")" : ""));
			}
		}
		else if (!remaining.isEmpty())
		{
			FarmingRunEvaluator.PatchProgress first = remaining.get(0);
			nextTitle = stopTitle(first, java.util.Collections.singletonList(first));
			nextLines.add(runText(first));
		}
		FarmingRunView v = new FarmingRunView(intent, progress, phase, lines, nextTitle, nextLines, summary(intent, progress), now);
		v.nextHere = nextHere;
		v.herePatchIds = here;
		v.bins = snapshot.getBins();
		v.binWork = RunExtras.bins(intent, snapshot.getBins(), now);
		if (remaining.isEmpty())
		{
			for (PatchLine l : lines)
			{
				if (l.compostDue)
				{
					// Planted before the compost: the compost is next (§28.17).
					v.nextTitle = stopTitle(l.getProgress(), java.util.Collections.singletonList(l.getProgress()));
					v.nextLines = java.util.Collections.singletonList(FarmingWording.compostAfterPlanting(l.getProgress(),
						RunExtras.compostMethod(intent, l.getProgress().getPatch()), false, null).getGuide());
					return v;
				}
			}
			// Every patch done: a bin with a task (or a payment) is what is next (§28.16), never "everything is done".
			for (RunExtras.BinWork b : v.binWork)
			{
				if (b.isDue())
				{
					v.nextTitle = b.def.getPlace() + " \u00b7 Compost bin";
					v.nextLines = java.util.Collections.singletonList(b.task.label + " (optional)");
					break;
				}
			}
		}
		return v;
	}

	/**
	 * The one run order every surface shows (canonical §28.12): the stop guidance is on now first (its patches in the stop's
	 * action order), then the planned order. Guidance chooses the current stop from the same planned order, so the page, the
	 * NEXT card and the on-screen guide never disagree, and nothing else reorders the list.
	 */
	static List<FarmingRunEvaluator.PatchProgress> runOrder(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress, List<String> current)
	{
		List<FarmingRunEvaluator.PatchProgress> planned = suggestedOrder(intent, progress);
		if (current == null || current.isEmpty())
		{
			return planned;
		}
		List<FarmingRunEvaluator.PatchProgress> out = new ArrayList<>();
		for (String id : current)
		{
			FarmingRunEvaluator.PatchProgress p = progress.patch(id);
			if (p != null && !out.contains(p))
			{
				out.add(p);
			}
		}
		for (FarmingRunEvaluator.PatchProgress p : planned)
		{
			if (!out.contains(p))
			{
				out.add(p);
			}
		}
		return out;
	}

	/** Lines in the suggested order the preparation page showed (any patch it did not order follows in run order). */
	static List<FarmingRunEvaluator.PatchProgress> suggestedOrder(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress)
	{
		List<FarmingRunEvaluator.PatchProgress> out = new ArrayList<>();
		for (String id : intent.getSuggestedOrder())
		{
			FarmingRunEvaluator.PatchProgress p = progress.patch(id);
			if (p != null && !out.contains(p))
			{
				out.add(p);
			}
		}
		for (FarmingRunEvaluator.PatchProgress p : progress.getPatches().values())
		{
			if (!out.contains(p))
			{
				out.add(p);
			}
		}
		return out;
	}

	/**
	 * The actions a patch needs this run, each marked only from evidence:
	 * harvest / clear (the patch observed bare after holding a crop this run),
	 * compost (never observed: unknown when chosen, never "done"), plant (a new
	 * crop observed). Planting alone never proves compost.
	 */
	static List<Step> steps(FarmingRunIntent intent, FarmingRunEvaluator.PatchProgress p)
	{
		return steps(intent, p, null);
	}

	/**
	 * With the compost the game showed on this patch's planting ({@code compost}, null: none seen): "✓ Supercompost" once
	 * seen applied; "○ Compost" when the game said the soil is untreated; "? Compost" otherwise (not seen either way).
	 * The compost step never holds a patch open: planting finishes it (canonical §28.14).
	 */
	static List<Step> steps(FarmingRunIntent intent, FarmingRunEvaluator.PatchProgress p, PatchCompost compostArg)
	{
		List<Step> out = new ArrayList<>();
		FarmingRunEvaluator.Detail d = p.getDetail();
		FarmingPatchDef def = p.getPatch();
		boolean treeType = def != null && FarmingPreferences.needsClearing(def.getType()) && def.getType() != FarmingPatchType.SPIRIT_TREE;
		switch (d)
		{
			case STILL_GROWING:
			case ALREADY_GROWING:
			case NOTHING_TO_DO:
			case NOT_CATALOGUED:
			case SKIPPED:
			case CURED:
			case TO_CURE:
			case NOT_VISITED:
			case UNREADABLE:
				return out;
			case HEALTH_CHECKED:
				out.add(new Step("Check health", Step.State.DONE));
				return out;
			case TO_CHECK_HEALTH:
				out.add(new Step("Check health", Step.State.TODO));
				if (!treeType || !p.isPlantPlanned())
				{
					return out;
				}
				break;
			default:
				break;
		}
		boolean planted = d == FarmingRunEvaluator.Detail.REPLANTED || d == FarmingRunEvaluator.Detail.PLANTED_UNSEEN;
		boolean bareNow = d == FarmingRunEvaluator.Detail.HARVESTED || d == FarmingRunEvaluator.Detail.CLEARED
			|| d == FarmingRunEvaluator.Detail.AWAITING_PLANT || planted;
		FarmingRunIntent.Baseline base = intent.baseline(p.getPatchId());
		boolean emptyAtStart = base != null && (base.getCondition() == PatchCondition.EMPTY || base.getCondition() == PatchCondition.WEEDS);
		if (treeType)
		{
			// Health checked / fruit picked / cleared / planted: outcomes, never the click or animation that might produce them.
			boolean checkDue = p.isHealthCheckDue() || base != null && base.getCondition() == PatchCondition.CHECK_HEALTH;
			if (checkDue && d != FarmingRunEvaluator.Detail.TO_CHECK_HEALTH)
			{
				out.add(new Step("Check health", p.isHealthChecked() ? Step.State.DONE : bareNow ? Step.State.UNKNOWN : Step.State.TODO));
			}
			if (p.getFruitLeft() > 0 || p.isFruitPicked())
			{
				out.add(new Step("Pick fruit", p.getFruitLeft() > 0 ? Step.State.TODO : Step.State.DONE));
			}
			if (!p.isPlantPlanned())
			{
				return out;
			}
			out.add(new Step("Clear", emptyAtStart && !bareNow ? Step.State.NOT_NEEDED : emptyAtStart ? Step.State.NOT_NEEDED
				: bareNow ? Step.State.DONE : Step.State.TODO));
		}
		else
		{
			boolean wasEmpty = d == FarmingRunEvaluator.Detail.TO_PLANT || emptyAtStart && d != FarmingRunEvaluator.Detail.TO_CLEAR
				&& d != FarmingRunEvaluator.Detail.TO_HARVEST && d != FarmingRunEvaluator.Detail.TO_REPLACE;
			String first = d == FarmingRunEvaluator.Detail.TO_CLEAR || d == FarmingRunEvaluator.Detail.CLEARED || d == FarmingRunEvaluator.Detail.TO_REPLACE
				? "Clear" : "Harvest";
			out.add(new Step(first, wasEmpty ? Step.State.NOT_NEEDED : bareNow ? Step.State.DONE : Step.State.TODO));
		}
		if (p.isPlantPlanned())
		{
			String compost = RunExtras.compostMethod(intent, def);
			PatchCompost compostSeen = compostArg;
			if (compostSeen != null && compostSeen.getKind() != PatchCompost.Kind.NONE)
			{
				out.add(new Step(compostSeen.getKind().getStepLabel(), Step.State.DONE));
			}
			Step compostTodo = null;
			if ((compostSeen == null || compostSeen.getKind() == PatchCompost.Kind.NONE) && compost != null)
			{
				// Chosen for this run and not yet shown by the game on this planting: to do, before or after planting (§28.17).
				String label = "Compost";
				try
				{
					label = PatchCompost.Kind.valueOf(compost).getStepLabel(); // "Super", "Ultra": short enough for the step line
				}
				catch (IllegalArgumentException e)
				{
					// Fertile Soil and other methods: "Compost"
				}
				compostTodo = new Step(label, intent.isCompostSkipped(p.getPatchId()) ? Step.State.NOT_NEEDED : Step.State.TODO);
				if (!planted)
				{
					out.add(compostTodo);
				}
			}
			out.add(new Step("Plant", planted ? Step.State.DONE : Step.State.TODO));
			if (compostTodo != null && planted)
			{
				out.add(compostTodo); // planted first: the compost follows it
			}
		}
		return out;
	}

	/** The next stop is where the player is (the home card says "at"). */
	public boolean isNextHere()
	{
		return nextHere;
	}

	/** The NEXT card's title: the place and, when the stop's patches share one, the patch type ("Catherby · Herb"). */
	static String stopTitle(FarmingRunEvaluator.PatchProgress first, List<FarmingRunEvaluator.PatchProgress> stop)
	{
		if (first.getPatch() == null)
		{
			return first.getPatchId();
		}
		java.util.Set<FarmingPatchType> types = new java.util.HashSet<>();
		for (FarmingRunEvaluator.PatchProgress p : stop)
		{
			if (p.getPatch() != null)
			{
				types.add(p.getPatch().getType());
			}
		}
		return first.getPatch().getAreaName() + (types.size() == 1 ? " \u00b7 " + first.getPatch().getType().getDisplayName() : "");
	}

	/** "NW", "flower", "herb": distinguishes patches that share a site. */
	private static String shortLabel(FarmingModel.Row row)
	{
		String location = row.getLocation();
		int open = location.lastIndexOf('(');
		if (open >= 0 && location.endsWith(")"))
		{
			return location.substring(open + 1, location.length() - 1);
		}
		return row.getPatch().getType().getDisplayName();
	}

	/**
	 * The run status line for one patch: what was observed done, or the one
	 * next action (the step marks carry the rest, so no "harvest, then plant").
	 * Planned crops never appear as what is there.
	 */
	static String runText(FarmingRunEvaluator.PatchProgress p)
	{
		String seen = p.getObservedCrop() == null ? null : p.getObservedCrop().getDisplayName();
		boolean tree = p.getPatch() != null && FarmingPreferences.needsClearing(p.getPatch().getType());
		switch (p.getDetail())
		{
			case REPLANTED:
			case PLANTED_UNSEEN:
				// The crop actually seen is what is recorded. A plan is never set against it.
				return "\u2713 Planted" + (seen == null ? "" : " · " + seen);
			case HARVESTED:
				return "✓ Harvested";
			case CLEARED:
				return "✓ Cleared";
			case CURED:
				return "✓ Cured";
			case HEALTH_CHECKED:
				return "✓ Health checked";
			case STILL_GROWING:
				return "Still growing";
			case ALREADY_GROWING:
				return "Already growing (not this run)";
			case NOTHING_TO_DO:
				return "Nothing to do";
			case SKIPPED:
				return "Skipped";
			case NOT_CATALOGUED:
				return "No longer tracked";
			case AWAITING_PLANT:
			case TO_PLANT:
				return "Plant";
			case TO_HARVEST:
				return p.getFruitLeft() > 0 ? "Pick fruit (" + p.getFruitLeft() + " left)" : "Harvest";
			case TO_CLEAR:
				return "Clear the dead plant";
			case TO_CURE:
				return "Cure it (or clear it)";
			case TO_CHECK_HEALTH:
				return "Check health";
			case TO_REPLACE:
				if (tree && p.getFruitLeft() > 0)
				{
					return "Pick fruit (" + p.getFruitLeft() + " left), then remove the tree";
				}
				return tree ? "Remove the tree" : "Clear the patch";
			case STUMP_TO_CLEAR:
				return "Dig up the stump";
			case UNREADABLE:
				return "Couldn't read the patch · check it";
			case NOT_VISITED:
			default:
				return "Not visited yet";
		}
	}

	static FarmingModel.Tone tone(FarmingRunEvaluator.PatchProgress p)
	{
		switch (p.getOutcome())
		{
			case DONE:
				return FarmingModel.Tone.SAGE;
			case IN_PROGRESS:
				return FarmingModel.Tone.GOLD;
			case PENDING:
				return p.getDetail() == FarmingRunEvaluator.Detail.TO_CURE || p.getDetail() == FarmingRunEvaluator.Detail.TO_CLEAR
					? FarmingModel.Tone.EMBER : FarmingModel.Tone.PRIMARY;
			case NOT_APPLICABLE:
			case SKIPPED:
			default:
				return FarmingModel.Tone.MUTED;
		}
	}

	/** Completion facts: only what was observed (no yields, XP or profit). */
	static List<String> summary(FarmingRunIntent intent, FarmingRunEvaluator.Progress progress)
	{
		List<String> out = new ArrayList<>();
		int replanted = progress.count(FarmingRunEvaluator.Detail.REPLANTED) + progress.count(FarmingRunEvaluator.Detail.PLANTED_UNSEEN);
		int harvested = progress.count(FarmingRunEvaluator.Detail.HARVESTED);
		int cleared = progress.count(FarmingRunEvaluator.Detail.CLEARED);
		int cured = progress.count(FarmingRunEvaluator.Detail.CURED);
		int checked = progress.count(FarmingRunEvaluator.Detail.HEALTH_CHECKED);
		if (replanted > 0)
		{
			out.add(replanted + (replanted == 1 ? " patch replanted" : " patches replanted"));
		}
		if (harvested > 0)
		{
			out.add(harvested + " harvested, not replanted");
		}
		if (cleared > 0)
		{
			out.add(cleared + " cleared");
		}
		if (cured > 0)
		{
			out.add(cured + " cured");
		}
		if (checked > 0)
		{
			out.add(checked + " health checked");
		}
		int growing = progress.count(FarmingRunEvaluator.Detail.STILL_GROWING) + progress.count(FarmingRunEvaluator.Detail.ALREADY_GROWING);
		int skipped = progress.count(FarmingRunEvaluator.Outcome.SKIPPED);
		int nothing = progress.count(FarmingRunEvaluator.Detail.NOTHING_TO_DO) + progress.count(FarmingRunEvaluator.Detail.NOT_CATALOGUED);
		List<String> rest = new ArrayList<>();
		if (growing > 0)
		{
			rest.add(growing + " still growing");
		}
		if (nothing > 0)
		{
			rest.add(nothing + " nothing to do");
		}
		if (skipped > 0)
		{
			rest.add(skipped + " skipped");
		}
		if (!rest.isEmpty())
		{
			out.add(String.join(" · ", rest));
		}
		int unfinished = progress.getTotal() - progress.getSettled();
		if (unfinished > 0)
		{
			out.add(unfinished + " not finished");
		}
		return out;
	}

	long getNow()
	{
		return now;
	}

	public FarmingRunIntent getIntent()
	{
		return intent;
	}

	public FarmingRunEvaluator.Progress getProgress()
	{
		return progress;
	}

	public Phase getPhase()
	{
		return phase;
	}

	public FarmingRunType getRunType()
	{
		return intent.getRunType();
	}

	/** "Herb", "Herb + Tree", "Combined". */
	public String getRunTitle()
	{
		return intent.getTitle();
	}

	public List<PatchLine> getLines()
	{
		return lines;
	}

	/** "At Falador" / "Next remaining · Catherby", or null when nothing remains. */
	public String getNextTitle()
	{
		return nextTitle;
	}

	public List<String> getNextLines()
	{
		return nextLines;
	}

	/** Completion facts (observed only). */
	public List<String> getSummary()
	{
		return summary;
	}

	/** "HERB RUN", "HERB RUN COMPLETE", "HERB RUN ENDED EARLY". */
	public String getTitle()
	{
		String base = intent.getTitle().toUpperCase() + " RUN";
		if (phase != Phase.COMPLETE)
		{
			return base;
		}
		return intent.getEndReason() == null ? base + " COMPLETE" : base + " ENDED EARLY";
	}

	/** "3 / 9 done". */
	public String getCountText()
	{
		if (progress.getTotal() == 0)
		{
			int due = 0;
			for (RunExtras.BinWork b : getBinWork())
			{
				due += b.isDue() ? 1 : 0;
			}
			return due == 0 ? "bins done" : due + (due == 1 ? " bin to do" : " bins to do");
		}
		int open = 0;
		for (PatchLine l : lines)
		{
			open += l.isCompostDue() && l.getProgress() != null && l.getProgress().getOutcome() == FarmingRunEvaluator.Outcome.DONE ? 1 : 0;
		}
		return Math.max(0, progress.getSettled() - open) + " / " + progress.getTotal() + " done"; // compost still due: not done yet
	}

	/** Wall time since Start (or until it finished): "12m", "1h 4m". */
	public String getElapsedText()
	{
		long end = intent.getFinishedAt() != null ? intent.getFinishedAt() : now;
		return FarmingText.age(Math.max(0, end - intent.getStartedAt()));
	}

}
