package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The Farming Planner's run intent (canonical §5, §24, §25): one owner's
 * (account + STANDARD profile) run, its lifecycle and its files. Thread-safe;
 * the adapter calls it on the client thread, the panel on the EDT.
 *
 * <h3>Ownership</h3>
 * Same discipline as {@link FarmingStore}: {@link #switchOwner} bumps a
 * generation; a load applies only to the owner and generation it was started
 * for, and the file must name that owner; every write goes to the owner it
 * belongs to. One owner's run is never visible, resumable or writable under
 * another owner.
 *
 * <h3>Files ({@code {accountHash}/profiles/standard/farming/})</h3>
 * <ul>
 *   <li>{@code run_intent.json}: the open or just-completed run, or no run
 *   ({@code intent: null}, rewritten rather than deleted so that it is ordered
 *   on the same single writer thread as the outcome written before it).</li>
 *   <li>{@code runs/{intentId}.json}: the one-time outcome record, written when
 *   a run completes or is ended with something done (canonical §20/§21).</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>Start → ACTIVE. Logout, client close, or a load after a restart or
 *   crash → PAUSED: never resumed silently; Resume returns it to ACTIVE.</li>
 *   <li>Every included patch settled → COMPLETE (outcome written), shown until
 *   the player presses Done. End run → COMPLETE "ended early" when something
 *   was done, otherwise straight back to no run.</li>
 *   <li>No age rule (D-F3 CLOSED, Josh, 2026-10-15): an unfinished run stays
 *   saved, unprompted, until it completes or the player ends it. There is no
 *   reminder, stale warning or automatic ending, and no replacement timeout.</li>
 * </ul>
 * Nothing here ever writes patch knowledge.
 */
@Slf4j
public final class FarmingRunStore
{
	static final String INTENT_FILE = "run_intent.json";
	static final String RUNS_DIR = "runs";

	/** Persisted {@code run_intent.json}. */
	static final class Document
	{
		int schema = FarmingRunIntent.SCHEMA;
		long accountHash;
		String profile;
		FarmingRunIntent intent;

		boolean belongsTo(FarmingOwner owner)
		{
			return accountHash == owner.getAccountHash() && owner.getProfileKey().equals(profile);
		}
	}

	/** Persisted {@code runs/{intentId}.json}: only facts that were observed (no yields, XP or profit). */
	static final class Outcome
	{
		int schema = FarmingRunIntent.SCHEMA;
		long accountHash;
		String profile;
		String intentId;
		FarmingRunType runType;
		long startedAt;
		Long finishedAt;
		String result;
		Map<String, PatchOutcome> patches;
		int total;
		int done;
		int replanted;
		int harvested;
		int skipped;
		int notApplicable;
		int unfinished;
		/** Every run type in a combined run (null in older files). */
		List<String> runTypes;
		/** Compost bins of the run (§28.17; absent in older files): worked during it (the game showed a change no rotting makes), skipped. */
		int binsWorked;
		int binsSkipped;
	}

	static final class PatchOutcome
	{
		String outcome;
		String detail;
		String planned;
		String observedCrop;
		boolean differsFromPlan;
	}

	/** Start Run's answer: the new intent, or why it could not start. */
	public static final class StartResult
	{
		private final FarmingRunIntent intent;
		private final List<String> problems;

		StartResult(FarmingRunIntent intent, List<String> problems)
		{
			this.intent = intent;
			this.problems = Collections.unmodifiableList(problems);
		}

		static StartResult refused(String problem)
		{
			return new StartResult(null, Collections.singletonList(problem));
		}

		public boolean isStarted()
		{
			return intent != null;
		}

		public FarmingRunIntent getIntent()
		{
			return intent;
		}

		public List<String> getProblems()
		{
			return problems;
		}
	}

	private final LocalStateStore stateStore;
	private final Function<FarmingOwner, Filepath> dirs;
	private final Executor loadExecutor;
	private final FarmingPatchCatalog catalog;

	private FarmingOwner owner;
	private long generation;
	private boolean loaded;
	private FarmingRunIntent intent;
	private String lastSignature;

	public FarmingRunStore(LocalStateStore stateStore, Function<FarmingOwner, Filepath> dirs, Executor loadExecutor, FarmingPatchCatalog catalog)
	{
		this.stateStore = stateStore;
		this.dirs = dirs;
		this.loadExecutor = loadExecutor;
		this.catalog = catalog;
	}

	// -- ownership ------------------------------------------------------------------

	/**
	 * Makes {@code next} the current owner (null = none). The previous owner's
	 * open run is saved as PAUSED to its own file first.
	 */
	public synchronized void switchOwner(FarmingOwner next, long now)
	{
		if (next == null ? owner == null : next.equals(owner))
		{
			return;
		}
		pause(now);
		generation++;
		owner = next;
		loaded = false;
		intent = null;
		ranBefore = null;
		lastSignature = null;
		if (next != null)
		{
			long loadGeneration = generation;
			Filepath dir = dirs.apply(next);
			loadExecutor.execute(() -> load(loadGeneration, next, dir));
		}
	}

	private void load(long loadGeneration, FarmingOwner loadOwner, Filepath dir)
	{
		try
		{
			dir.joinSegment(RUNS_DIR).createDirectories();
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Spyglass Farming: could not create the runs folder", e);
		}
		Document doc = stateStore.readIfExists(dir.joinSegment(INTENT_FILE), Document.class);
		boolean before = doc != null || hasOutcomeFiles(dir.joinSegment(RUNS_DIR));
		synchronized (this)
		{
			if (loadGeneration == generation)
			{
				ranBefore = before;
			}
		}
		deliver(loadGeneration, loadOwner, doc);
	}

	/** The current profile has started a Farming run before (a saved run or a run outcome); null until its files are read. */
	private volatile Boolean ranBefore;

	public Boolean getRanBefore()
	{
		return ranBefore;
	}

	private static boolean hasOutcomeFiles(Filepath runs)
	{
		if (!runs.exists())
		{
			return false;
		}
		try (java.util.stream.Stream<Filepath> walk = runs.walk(1))
		{
			return walk.anyMatch(f -> f.isFile() && f.getFileName().endsWith(".json"));
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
	}

	synchronized void deliver(long loadGeneration, FarmingOwner loadOwner, Document doc)
	{
		if (loadGeneration != generation || !loadOwner.equals(owner))
		{
			return;
		}
		loaded = true;
		if (intent != null)
		{
			return; // started while the load was in flight: the newer intent wins (it was written)
		}
		if (doc == null || !doc.belongsTo(loadOwner))
		{
			return;
		}
		FarmingRunIntent stored = doc.intent;
		if (stored == null || !stored.isWellFormed() || stored.getRunType() == null)
		{
			if (stored != null)
			{
				log.warn("Spyglass Farming: ignoring an unreadable run intent for {}", loadOwner);
			}
			return;
		}
		// Loaded = a new login, a restart or a crash: an ACTIVE run is never resumed silently.
		intent = stored.getState() == FarmingRunIntent.State.ACTIVE ? stored.withState(FarmingRunIntent.State.PAUSED) : stored;
		if (intent != stored)
		{
			write();
		}
	}

	public synchronized FarmingOwner getOwner()
	{
		return owner;
	}

	public synchronized boolean isLoaded()
	{
		return loaded;
	}

	/** The current owner's intent (open or awaiting Done), or null; only for {@code expected}. */
	public synchronized FarmingRunIntent intentFor(FarmingOwner expected)
	{
		return expected != null && expected.equals(owner) && loaded ? intent : null;
	}

	/** An ACTIVE or PAUSED run exists for {@code expected} (cheap: no copy). */
	public synchronized boolean hasOpenRun(FarmingOwner expected)
	{
		return isCurrentOpen(expected);
	}

	// -- player actions (EDT) ----------------------------------------------------------

	/**
	 * Start Run: validates, then captures the request plus a baseline of every
	 * included patch from {@code knowledge} (the owner's knowledge at this
	 * moment). Never replaces an open run, never writes knowledge.
	 */
	public synchronized StartResult start(FarmingOwner expected, FarmingRunRequest request, Map<String, PatchKnowledge> knowledge, long now)
	{
		List<String> problems = new ArrayList<>();
		if (expected == null || !expected.equals(owner))
		{
			problems.add("Log in on a standard world to start a run.");
		}
		else if (!loaded)
		{
			problems.add("Spyglass is still loading your patches. Try again in a moment.");
		}
		else if (intent != null && intent.isOpen())
		{
			problems.add("A " + intent.getTitle().toLowerCase() + " run is already in progress. Return to it, or end it first.");
		}
		if (problems.isEmpty())
		{
			problems.addAll(request.problems(catalog));
		}
		if (!problems.isEmpty())
		{
			return new StartResult(null, problems);
		}
		Map<String, FarmingRunIntent.Baseline> baseline = new LinkedHashMap<>();
		Map<String, String> planned = new LinkedHashMap<>();
		for (String id : request.getIncluded())
		{
			baseline.put(id, FarmingRunIntent.Baseline.of(catalog.patch(id), knowledge.get(id)));
			planned.put(id, request.plan(id));
		}
		Map<String, String> compostByType = new LinkedHashMap<>();
		Map<String, Boolean> protectionByType = new LinkedHashMap<>();
		for (FarmingRunType t : request.getRunTypes())
		{
			compostByType.put(t.name(), request.compostFor(t).name());
			protectionByType.put(t.name(), request.protectionFor(t));
		}
		FarmingRunIntent.Options options = new FarmingRunIntent.Options(request.getCompost().name(), request.isPayProtection(), compostByType,
			protectionByType, request.isUseBounty());
		if (request.getCompostByPatch() != null)
		{
			options = options.withCompostByPatch(request.getCompostByPatch());
		}
		String id = request.getRunTypes().size() > 1 ? FarmingRunIntent.newId(request.getRunType(), now).replaceAll("-[a-z_]+$", "-combined")
			: FarmingRunIntent.newId(request.getRunType(), now);
		intent = new FarmingRunIntent(FarmingRunIntent.SCHEMA, id, request.getRunType(), now, now,
			FarmingRunIntent.State.ACTIVE, null, null, request.getIncluded(), request.getExcluded(), Collections.emptyList(), planned,
			options, baseline).withRunTypes(request.getRunTypes()).withPreparing(true);
		if (request.getProtectTypes() != null)
		{
			intent = intent.withExtras(request.getBins(), request.getBinUpgrades(), request.getProtectTypes());
		}
		lastSignature = null;
		write();
		return new StartResult(intent, Collections.emptyList());
	}

	public synchronized void setSkipped(FarmingOwner expected, String patchId, boolean skip, long now)
	{
		if (!isCurrentOpen(expected) || !intent.getIncludedPatchIds().contains(patchId) || intent.isSkipped(patchId) == skip)
		{
			return;
		}
		intent = intent.withSkipped(patchId, skip).withProgress(now);
		recheck = true;
		write();
	}

	/** The compost bins as last tracked (for the run outcome's bin work). */
	private Map<String, CompostBin.Seen> lastBins = Collections.emptyMap();

	/** A skip changed what is left: the next tick re-checks completion even where no patch is read. */
	private boolean recheck;

	public synchronized boolean consumeRecheck()
	{
		boolean r = recheck;
		recheck = false;
		return r;
	}

	/** Skip (or un-skip) an included compost bin's work for this run (§28.16). */
	public synchronized void setBinSkipped(FarmingOwner expected, String binId, boolean skip, long now)
	{
		if (!isCurrentOpen(expected) || !intent.getBins().contains(binId) || intent.isBinSkipped(binId) == skip)
		{
			return;
		}
		intent = intent.withBinSkipped(binId, skip).withProgress(now);
		recheck = true;
		write();
	}

	/** Skip (or un-skip) the compost still due on a patch (§28.17). */
	public synchronized void setCompostSkipped(FarmingOwner expected, String patchId, boolean skip, long now)
	{
		if (!isCurrentOpen(expected) || !intent.getIncludedPatchIds().contains(patchId) || intent.isCompostSkipped(patchId) == skip)
		{
			return;
		}
		intent = intent.withCompostSkipped(patchId, skip).withProgress(now);
		recheck = true;
		write();
	}

	/** Skip (or un-skip) a protection payment still due on a planted patch (§28.16). */
	public synchronized void setProtectionSkipped(FarmingOwner expected, String patchId, boolean skip, long now)
	{
		if (!isCurrentOpen(expected) || !intent.getIncludedPatchIds().contains(patchId) || intent.isProtectionSkipped(patchId) == skip)
		{
			return;
		}
		intent = intent.withProtectionSkipped(patchId, skip).withProgress(now);
		recheck = true;
		write();
	}

	public synchronized void resume(FarmingOwner expected, long now)
	{
		if (isCurrentOpen(expected) && intent.getState() == FarmingRunIntent.State.PAUSED)
		{
			intent = intent.withState(FarmingRunIntent.State.ACTIVE).withProgress(now);
			write();
		}
	}

	/**
	 * End run: with something done, the run completes "ended early" (outcome
	 * kept); with nothing done, it is simply removed.
	 */
	public synchronized void end(FarmingOwner expected, Map<String, PatchKnowledge> knowledge, long now)
	{
		if (!isCurrentOpen(expected))
		{
			return;
		}
		FarmingRunEvaluator.Progress progress = FarmingRunEvaluator.evaluate(intent, catalog, knowledge);
		if (progress.getDone() == 0 && RunExtras.binsWorked(intent, lastBins) == 0)
		{
			intent = null;
			write();
			return;
		}
		finish(FarmingRunIntent.EndReason.ENDED, progress, now);
	}

	/**
	 * Go / Go anyway, or the automatic transition (D-F2): the preparation
	 * stage closes and the run page shows the patches. Tracking is unaffected.
	 */
	public synchronized void closePreparation(FarmingOwner expected)
	{
		if (isCurrentOpen(expected) && intent.isPreparing())
		{
			intent = intent.withPreparing(false);
			write();
		}
	}

	/**
	 * The suggested visiting order the preparation page computed, kept so the
	 * run page shows the same one. A suggestion only; progress never uses it.
	 * Written only when it changes.
	 */
	public synchronized void setSuggestedOrder(FarmingOwner expected, List<String> order)
	{
		if (isCurrentOpen(expected) && order != null && !order.isEmpty() && !order.equals(intent.getSuggestedOrder()))
		{
			intent = intent.withSuggestedOrder(order);
			write();
		}
	}

	/** A protection payment observed for this patch (the game's own message). Written once. */
	public synchronized void recordProtected(FarmingOwner expected, String patchId)
	{
		if (isCurrentOpen(expected) && patchId != null && !intent.getProtectedPatchIds().contains(patchId))
		{
			intent = intent.withProtected(patchId);
			recheck = true;
			write();
		}
	}

	/**
	 * Pass 31: the player changed a patch type's compost while this run is open. The run's choice for that type follows
	 * (preparation recalculates; guidance names it for patches still to plant); work already done keeps what the game showed.
	 */
	public synchronized void setCompost(FarmingOwner expected, FarmingPatchType type, FarmingPlanDraft.Compost choice)
	{
		if (!isCurrentOpen(expected) || type == null || choice == null || intent.getOptions() == null)
		{
			return;
		}
		if (choice.name().equals(intent.getOptions().compostFor(type)))
		{
			return;
		}
		intent = intent.withOptions(intent.getOptions().withCompostByPatch(java.util.Collections.singletonMap(type.name(), choice.name())));
		recheck = true;
		write();
	}

	/** The preparation option "wear an amulet of bounty" (persisted with the run, like the other options). */
	public synchronized void setUseBounty(FarmingOwner expected, boolean use)
	{
		if (isCurrentOpen(expected) && intent.getOptions() != null && intent.getOptions().isUseBounty() != use)
		{
			intent = intent.withOptions(intent.getOptions().withBounty(use));
			write();
		}
	}

	/** Done on the completion summary: the run is cleared (its outcome is already saved). */
	public synchronized void acknowledge(FarmingOwner expected)
	{
		if (expected != null && expected.equals(owner) && loaded && intent != null && intent.getState() == FarmingRunIntent.State.COMPLETE)
		{
			intent = null;
			write();
		}
	}

	// -- tracking (client thread) -----------------------------------------------------------

	/**
	 * Re-derives progress from current knowledge: records progress time when it
	 * changes, completes the run once every included patch has settled, and
	 * nothing else: no age rule exists. Cheap; safe to call every tick.
	 */
	public synchronized FarmingRunEvaluator.Progress track(FarmingOwner expected, Map<String, PatchKnowledge> knowledge, long now)
	{
		return track(expected, knowledge, Collections.emptyMap(), now);
	}

	/**
	 * As above, with the compost bins as last seen: the run completes when every patch has settled and no optional work is left
	 * (§28.16: a protection payment due, an included bin with a task now), so the last stop's payment or bin is never dropped.
	 */
	public synchronized FarmingRunEvaluator.Progress track(FarmingOwner expected, Map<String, PatchKnowledge> knowledge,
		Map<String, CompostBin.Seen> bins, long now)
	{
		return track(expected, knowledge, bins, Collections.emptyMap(), now);
	}

	/** As above, with the compost the game showed on patches: compost still due on a planting keeps the run open (§28.17). */
	public synchronized FarmingRunEvaluator.Progress track(FarmingOwner expected, Map<String, PatchKnowledge> knowledge,
		Map<String, CompostBin.Seen> bins, Map<String, PatchCompost> compost, long now)
	{
		if (!isCurrentOpen(expected))
		{
			return null;
		}
		FarmingRunIntent withEvidence = FarmingRunEvaluator.withEvidence(intent, catalog, knowledge);
		boolean evidenceChanged = withEvidence != intent;
		intent = withEvidence;
		FarmingRunEvaluator.Progress progress = FarmingRunEvaluator.evaluate(intent, catalog, knowledge);
		String signature = progress.signature();
		boolean progressed = lastSignature != null && !signature.equals(lastSignature);
		if (progressed)
		{
			intent = intent.withProgress(now);
		}
		if (intent.isPreparing() && progress.getDone() > 0)
		{
			intent = intent.withPreparing(false); // the player has clearly started the run (D-F2)
			evidenceChanged = true;
		}
		if (intent.getBinStart() == null && !intent.getBins().isEmpty() && bins != null)
		{
			Map<String, Integer> start = new LinkedHashMap<>();
			for (String b : intent.getBins())
			{
				CompostBin.Seen s = bins.get(b);
				if (s != null)
				{
					start.put(b, s.getRaw());
				}
			}
			intent = intent.withBinStart(start);
		}
		lastBins = bins == null ? Collections.emptyMap() : bins;
		boolean complete = RunExtras.complete(intent, progress, bins, knowledge, compost, now);
		if ((progressed || evidenceChanged) && !complete)
		{
			write();
		}
		lastSignature = signature;
		if (complete)
		{
			finish(null, progress, now);
		}
		return progress;
	}

	/** Logout or shutdown: an ACTIVE run is saved as PAUSED. */
	public synchronized void pause(long now)
	{
		if (owner != null && loaded && intent != null && intent.getState() == FarmingRunIntent.State.ACTIVE)
		{
			intent = intent.withState(FarmingRunIntent.State.PAUSED);
			write();
		}
	}

	// -- internals --------------------------------------------------------------------------

	private boolean isCurrentOpen(FarmingOwner expected)
	{
		return expected != null && expected.equals(owner) && loaded && intent != null && intent.isOpen();
	}

	private void finish(FarmingRunIntent.EndReason reason, FarmingRunEvaluator.Progress progress, long now)
	{
		intent = intent.finished(reason, now);
		Filepath dir = dirs.apply(owner);
		Outcome outcome = outcome(owner, intent, progress);
		outcome.binsWorked = RunExtras.binsWorked(intent, lastBins);
		outcome.binsSkipped = RunExtras.binsSkipped(intent);
		Document doc = document();
		// Outcome first, then the intent, on one writer task: a failed outcome write leaves the run as it was.
		stateStore.writeChained(dir.joinSegment(RUNS_DIR).joinSegment(intent.getIntentId() + ".json"), outcome, dir.joinSegment(INTENT_FILE), doc,
			() -> { });
	}

	static Outcome outcome(FarmingOwner owner, FarmingRunIntent intent, FarmingRunEvaluator.Progress progress)
	{
		Outcome o = new Outcome();
		o.accountHash = owner.getAccountHash();
		o.profile = owner.getProfileKey();
		o.intentId = intent.getIntentId();
		o.runType = intent.getRunType();
		o.startedAt = intent.getStartedAt();
		o.finishedAt = intent.getFinishedAt();
		o.result = intent.getEndReason() == null ? "COMPLETE" : intent.getEndReason().name();
		o.patches = new LinkedHashMap<>();
		for (FarmingRunEvaluator.PatchProgress p : progress.getPatches().values())
		{
			PatchOutcome po = new PatchOutcome();
			po.outcome = p.getOutcome().name();
			po.detail = p.getDetail().name();
			String plan = intent.getPlannedCrops().get(p.getPatchId());
			po.planned = p.getPlanned() != null ? p.getPlanned().name() : plan == null ? FarmingRunIntent.HARVEST_ONLY : plan;
			po.observedCrop = p.getObservedCrop() == null ? null : p.getObservedCrop().name();
			po.differsFromPlan = p.isDifferentFromPlan();
			o.patches.put(p.getPatchId(), po);
		}
		o.total = progress.getTotal();
		o.done = progress.getDone();
		o.replanted = progress.count(FarmingRunEvaluator.Detail.REPLANTED) + progress.count(FarmingRunEvaluator.Detail.PLANTED_UNSEEN);
		o.harvested = progress.count(FarmingRunEvaluator.Detail.HARVESTED);
		o.skipped = progress.count(FarmingRunEvaluator.Outcome.SKIPPED);
		o.notApplicable = progress.count(FarmingRunEvaluator.Outcome.NOT_APPLICABLE);
		o.unfinished = progress.getTotal() - progress.getSettled();
		List<String> types = new ArrayList<>();
		for (FarmingRunType t : intent.getRunTypes())
		{
			types.add(t.name());
		}
		o.runTypes = types;
		return o;
	}

	private Document document()
	{
		Document doc = new Document();
		doc.accountHash = owner.getAccountHash();
		doc.profile = owner.getProfileKey();
		doc.intent = intent;
		return doc;
	}

	private void write()
	{
		if (owner == null || !loaded)
		{
			return; // never overwrite an owner's file before it has been read
		}
		stateStore.write(dirs.apply(owner).joinSegment(INTENT_FILE), document());
	}
}
