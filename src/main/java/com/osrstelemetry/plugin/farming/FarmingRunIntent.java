package com.osrstelemetry.plugin.farming;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the player asked a farming run to accomplish (canonical §5): the run
 * type, the patches included and excluded, the planned crop per patch, the run
 * options, and a baseline of every included patch as Spyglass knew it at
 * Start. Immutable (Gson-friendly); every change returns a new intent.
 *
 * There is deliberately NO step number, current site or route cursor: the
 * next objective and every patch's progress are re-derived from the baseline
 * plus current native knowledge ({@link FarmingRunEvaluator}). The lifecycle
 * {@link State} is not a step.
 *
 * The baseline is a copy taken at Start; Start never writes patch knowledge,
 * and nothing here is ever written back into it.
 */
public final class FarmingRunIntent
{
	public static final int SCHEMA = 1;
	/** {@link #plannedCrops} value meaning "harvest only" (nothing replanted). */
	public static final String HARVEST_ONLY = "HARVEST_ONLY";
	/**
	 * {@link #plannedCrops} value meaning "replant with something suitable; the
	 * player decides what" (the default since the 2026-10-16 correction pass).
	 * Never a specific crop, so no planted crop is ever "different from plan".
	 */
	public static final String REPLANT_ANY = "REPLANT_ANY";

	public enum State
	{
		/** In progress during this login. */
		ACTIVE,
		/** Loaded after a logout, client close or restart: shown with Resume / End, never resumed silently. */
		PAUSED,
		/** Every included patch settled (done, skipped or not applicable), or ended early with something done; awaiting the player's Done. */
		COMPLETE
	}

	/** Why a run stopped before every patch settled. */
	public enum EndReason
	{
		/** The player pressed End run. */
		ENDED
	}

	/** What Spyglass knew about one included patch at Start (a copy, never a reference). */
	public static final class Baseline
	{
		public enum Truth
		{
			/** Seen before Start; the fields below are that observation. */
			OBSERVED,
			/** Never seen on this profile: NOT TRACKED, which is not "unavailable". */
			NEVER_SEEN
		}

		private final Truth truth;
		private final int raw;
		private final int decoderVersion;
		private final PatchCondition condition;
		private final FarmingProduce produce;
		private final int stage;
		private final long lastSeenAt;
		/** Identity of the crop cycle present at Start (its first-seen time), or null for none. */
		private final Long cycleFirstSeenAt;

		Baseline(Truth truth, int raw, int decoderVersion, PatchCondition condition, FarmingProduce produce, int stage, long lastSeenAt,
			Long cycleFirstSeenAt)
		{
			this.truth = truth;
			this.raw = raw;
			this.decoderVersion = decoderVersion;
			this.condition = condition;
			this.produce = produce;
			this.stage = stage;
			this.lastSeenAt = lastSeenAt;
			this.cycleFirstSeenAt = cycleFirstSeenAt;
		}

		static Baseline of(FarmingPatchDef patch, PatchKnowledge k)
		{
			if (k == null)
			{
				return new Baseline(Truth.NEVER_SEEN, -1, FarmingDecoder.VERSION, null, null, -1, 0L, null);
			}
			PatchReading r = FarmingDecoder.decode(patch.getType(), k.getLastRaw());
			CropCycle c = k.getCycle();
			return new Baseline(Truth.OBSERVED, k.getLastRaw(), k.getDecoderVersion(), k.isDecodeStale() ? PatchCondition.UNKNOWN : r.getCondition(),
				c != null ? c.getProduce() : r.getProduce(), r.getStage(), k.getLastSeenAt(), c == null ? null : c.getFirstSeenAt());
		}

		public Truth getTruth()
		{
			return truth;
		}

		public int getRaw()
		{
			return raw;
		}

		public int getDecoderVersion()
		{
			return decoderVersion;
		}

		/** Null when never seen. */
		public PatchCondition getCondition()
		{
			return condition;
		}

		public FarmingProduce getProduce()
		{
			return produce;
		}

		public int getStage()
		{
			return stage;
		}

		public long getLastSeenAt()
		{
			return lastSeenAt;
		}

		public Long getCycleFirstSeenAt()
		{
			return cycleFirstSeenAt;
		}
	}

	/**
	 * Run options as chosen in the preview. {@code compost} / {@code payProtection}
	 * are the first run type's (files from before combined runs have only
	 * these); the per-type maps and the amulet of bounty choice are null in
	 * older files and then fall back to them.
	 */
	public static final class Options
	{
		private final String compost;
		private final boolean payProtection;
		private Map<String, String> compostByType;
		private Map<String, Boolean> protectionByType;
		private Boolean useBounty;
		/**
		 * Pass 31: the compost per patch type ({@link FarmingPatchType} name: allotments and flowers apart), from the player's
		 * saved choices; null in older files, which fall back to the per-run-type choice.
		 */
		private Map<String, String> compostByPatch;

		Options(String compost, boolean payProtection)
		{
			this.compost = compost;
			this.payProtection = payProtection;
		}

		Options(String compost, boolean payProtection, Map<String, String> compostByType, Map<String, Boolean> protectionByType, boolean useBounty)
		{
			this(compost, payProtection);
			this.compostByType = compostByType == null ? null : new LinkedHashMap<>(compostByType);
			this.protectionByType = protectionByType == null ? null : new LinkedHashMap<>(protectionByType);
			this.useBounty = useBounty;
		}

		public String getCompost()
		{
			return compost;
		}

		public boolean isPayProtection()
		{
			return payProtection;
		}

		/** The compost chosen for one patch type (falls back to its run type's, then the run's single choice). */
		public String compostFor(FarmingPatchType patch)
		{
			String c = compostByPatch == null || patch == null ? null : compostByPatch.get(patch.name());
			if (c != null)
			{
				return c;
			}
			FarmingRunType t = patch == null ? null : FarmingRunType.of(patch);
			return t == null ? compost : compostFor(t);
		}

		/** The per-patch-type choices (null: an older run). */
		public Map<String, String> getCompostByPatch()
		{
			return compostByPatch == null ? null : java.util.Collections.unmodifiableMap(compostByPatch);
		}

		/**
		 * The same options with the compost per patch type changed (preparation recalculates from it); only the types named
		 * change. Patches already worked keep what the game showed for them: the choice only ever guides what is still to do.
		 */
		Options withCompostByPatch(Map<String, String> changes)
		{
			Options o = new Options(compost, payProtection, compostByType, protectionByType, isUseBounty());
			o.compostByPatch = new LinkedHashMap<>(compostByPatch == null ? java.util.Collections.emptyMap() : compostByPatch);
			if (changes != null)
			{
				o.compostByPatch.putAll(changes);
			}
			return o;
		}

		/** The compost chosen for one run type's patches (falls back to the run's single choice). */
		public String compostFor(FarmingRunType type)
		{
			String c = compostByType == null ? null : compostByType.get(type.name());
			return c != null ? c : compost;
		}

		/** Whether the player chose to pay for protection on one run type's patches. A plan, never evidence it was paid. */
		public boolean protectionFor(FarmingRunType type)
		{
			Boolean b = protectionByType == null ? null : protectionByType.get(type.name());
			return b != null ? b : payProtection;
		}

		/** The player chose to wear an amulet of bounty for allotment planting (optional; never assumed). */
		public boolean isUseBounty()
		{
			return useBounty != null && useBounty;
		}

		Options withBounty(boolean use)
		{
			Options o = new Options(compost, payProtection, compostByType, protectionByType, use);
			o.compostByPatch = compostByPatch == null ? null : new LinkedHashMap<>(compostByPatch);
			return o;
		}
	}

	private final int schema;
	private final String intentId;
	private final FarmingRunType runType;
	private final long startedAt;
	private final long lastProgressAt;
	private final State state;
	private final EndReason endReason;
	/** When the run reached COMPLETE (settled or ended), else null. */
	private final Long finishedAt;
	private final List<String> includedPatchIds;
	private final List<String> excludedPatchIds;
	private final List<String> skippedPatchIds;
	private final Map<String, String> plannedCrops;
	private final Options options;
	private final Map<String, Baseline> baseline;
	/**
	 * Evidence, not progress: included patches natively seen holding a crop
	 * after Start. A later bare reading of such a patch is a clear made during
	 * the run (needed where no crop cycle records it, e.g. a dead herb whose
	 * identity was never known). Only ever grows.
	 */
	private final List<String> cropSeenSinceStart;
	/**
	 * Phase H: the run's preparation stage is open (supplies and travel to get
	 * before setting off). Set at Start, closed by Go / Go anyway, by the first
	 * observed patch progress, or when the run finishes. Null (files from before
	 * Phase H) means closed. A presentation stage only: tracking never waits for it.
	 */
	private Boolean preparing;
	/** Combined runs: every run type in the run (null in older files: just {@link #runType}). */
	private List<String> runTypes;
	/**
	 * The suggested visiting order (patch ids) the preparation page showed,
	 * kept so the run page shows the same order. A suggestion only: progress
	 * never depends on it. Null until preparation has computed one.
	 */
	private List<String> suggestedOrder;
	/**
	 * Patches whose protection payment was observed this run ("You pay the gardener ... to protect the patch."), never a
	 * preference or a click. Null in older files: none.
	 */
	private List<String> protectedPatchIds;
	/**
	 * Per patch, the most fruit/berries/cacti left that the run has seen since Start (a regrowing crop): picking is then
	 * counted from what the run itself saw, never from a baseline read days earlier (live finding 2026-10-22, Champions'
	 * Guild bush: regrown since the last visit, four picked, reported "nothing to do").
	 */
	private Map<String, Integer> stagePeak;
	/**
	 * Optional work the run took on at Start (canonical §28.16), null in older files (none): the compost bins chosen for runs
	 * then, those skipped since, the run types whose protection the player pays for, and protection payments skipped. A run
	 * completes only when none of this is still to do (a bin with a task now, a payment due), so the last stop's bin or payment
	 * is never dropped; each can be skipped, so none can hold the run.
	 */
	private List<String> bins;
	private List<String> skippedBins;
	private List<String> protectTypes;
	private List<String> skippedProtection;
	/** Patches whose compost (the run's chosen method) the player skipped (§28.17). */
	private List<String> skippedCompost;
	/** The compost bins as first read in this run (raw values), to tell bin work done in it from rotting on its own. */
	private Map<String, Integer> binStart;
	/** The upgrade chosen for each bin at Start ({@link CompostBin.Upgrade} names); null: none. */
	private Map<String, String> binUpgrades;
	FarmingRunIntent(int schema, String intentId, FarmingRunType runType, long startedAt, long lastProgressAt, State state, EndReason endReason,
		Long finishedAt, List<String> includedPatchIds, List<String> excludedPatchIds, List<String> skippedPatchIds, Map<String, String> plannedCrops,
		Options options, Map<String, Baseline> baseline)
	{
		this(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt, includedPatchIds, excludedPatchIds, skippedPatchIds,
			plannedCrops, options, baseline, Collections.emptyList());
	}

	FarmingRunIntent(int schema, String intentId, FarmingRunType runType, long startedAt, long lastProgressAt, State state, EndReason endReason,
		Long finishedAt, List<String> includedPatchIds, List<String> excludedPatchIds, List<String> skippedPatchIds, Map<String, String> plannedCrops,
		Options options, Map<String, Baseline> baseline, List<String> cropSeenSinceStart)
	{
		this.cropSeenSinceStart = Collections.unmodifiableList(new ArrayList<>(cropSeenSinceStart));
		this.schema = schema;
		this.intentId = intentId;
		this.runType = runType;
		this.startedAt = startedAt;
		this.lastProgressAt = lastProgressAt;
		this.state = state;
		this.endReason = endReason;
		this.finishedAt = finishedAt;
		this.includedPatchIds = Collections.unmodifiableList(new ArrayList<>(includedPatchIds));
		this.excludedPatchIds = Collections.unmodifiableList(new ArrayList<>(excludedPatchIds));
		this.skippedPatchIds = Collections.unmodifiableList(new ArrayList<>(skippedPatchIds));
		this.plannedCrops = Collections.unmodifiableMap(new LinkedHashMap<>(plannedCrops));
		this.options = options;
		this.baseline = Collections.unmodifiableMap(new LinkedHashMap<>(baseline));
	}

	/** "fr-20261014T174103Z-herb": unique per start, safe as a file name on every OS. */
	static String newId(FarmingRunType type, long startedAt)
	{
		return "fr-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(startedAt))
			+ "-" + type.name().toLowerCase();
	}

	public String getIntentId()
	{
		return intentId;
	}

	public FarmingRunType getRunType()
	{
		return runType;
	}

	/** Every run type in this run, in the order chosen (one for an ordinary run). */
	public List<FarmingRunType> getRunTypes()
	{
		List<FarmingRunType> out = new ArrayList<>();
		if (runTypes != null)
		{
			for (String name : runTypes)
			{
				FarmingRunType t = FarmingRunType.parse(name);
				if (t != null && !out.contains(t))
				{
					out.add(t);
				}
			}
		}
		if (out.isEmpty())
		{
			out.add(runType);
		}
		return out;
	}

	public boolean isCombined()
	{
		return getRunTypes().size() > 1;
	}

	/** "Herb", "Herb + Tree", "Combined (4 types)". */
	public String getTitle()
	{
		List<FarmingRunType> types = getRunTypes();
		if (types.size() == 1)
		{
			return types.get(0).getTitle();
		}
		if (types.size() <= 3)
		{
			List<String> names = new ArrayList<>();
			for (FarmingRunType t : types)
			{
				names.add(t.isCrop() ? t.getTitle() : "bins"); // "Herb + bins": the run's title fits the sidebar
			}
			return String.join(" + ", names);
		}
		return "Combined (" + types.size() + " types)";
	}

	/** The suggested order (patch ids), or an empty list when none has been computed. */
	public List<String> getSuggestedOrder()
	{
		return suggestedOrder == null ? Collections.emptyList() : Collections.unmodifiableList(suggestedOrder);
	}

	public long getStartedAt()
	{
		return startedAt;
	}

	/** The last time a patch settled (or the run started). Informational only: no age rule uses it (D-F3). */
	public long getLastProgressAt()
	{
		return lastProgressAt;
	}

	public State getState()
	{
		return state;
	}

	public EndReason getEndReason()
	{
		return endReason;
	}

	public Long getFinishedAt()
	{
		return finishedAt;
	}

	public List<String> getIncludedPatchIds()
	{
		return includedPatchIds;
	}

	public List<String> getExcludedPatchIds()
	{
		return excludedPatchIds;
	}

	public List<String> getSkippedPatchIds()
	{
		return skippedPatchIds;
	}

	public boolean isSkipped(String patchId)
	{
		return skippedPatchIds.contains(patchId);
	}

	/**
	 * Whether the plan is to plant something here once it is clear: a specific
	 * crop, or {@link #REPLANT_ANY}. False for harvest only.
	 */
	public boolean plansToPlant(String patchId)
	{
		String name = plannedCrops.get(patchId);
		return REPLANT_ANY.equals(name) || plannedCrop(patchId) != null;
	}

	/** The player's explicit crop choice, or null for harvest only, "anything suitable" or an unknown saved name. */
	public FarmingProduce plannedCrop(String patchId)
	{
		String name = plannedCrops.get(patchId);
		if (name == null || HARVEST_ONLY.equals(name) || REPLANT_ANY.equals(name))
		{
			return null;
		}
		try
		{
			return FarmingProduce.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	public Map<String, String> getPlannedCrops()
	{
		return plannedCrops;
	}

	public Options getOptions()
	{
		return options;
	}

	public Baseline baseline(String patchId)
	{
		return baseline.get(patchId);
	}

	public Map<String, Baseline> getBaseline()
	{
		return baseline;
	}

	public boolean isOpen()
	{
		return state == State.ACTIVE || state == State.PAUSED;
	}

	// -- transitions (each returns a new intent) -------------------------------------

	FarmingRunIntent withState(State next)
	{
		return carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, next, endReason, finishedAt, includedPatchIds,
			excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
	}

	FarmingRunIntent withProgress(long at)
	{
		return carry(new FarmingRunIntent(schema, intentId, runType, startedAt, Math.max(lastProgressAt, at), state, endReason, finishedAt, includedPatchIds,
			excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
	}

	FarmingRunIntent withSkipped(String patchId, boolean skip)
	{
		Set<String> skipped = new LinkedHashSet<>(skippedPatchIds);
		if (skip)
		{
			skipped.add(patchId);
		}
		else
		{
			skipped.remove(patchId);
		}
		return carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt, includedPatchIds,
			excludedPatchIds, new ArrayList<>(skipped), plannedCrops, options, baseline, cropSeenSinceStart));
	}

	/** Whether {@code patchId} was seen holding a crop after Start (evidence for a clear). */
	public boolean wasCropSeenSinceStart(String patchId)
	{
		return cropSeenSinceStart != null && cropSeenSinceStart.contains(patchId);
	}

	List<String> getCropSeenSinceStart()
	{
		return cropSeenSinceStart == null ? Collections.emptyList() : cropSeenSinceStart;
	}

	FarmingRunIntent withCropSeen(List<String> patchIds)
	{
		Set<String> seen = new LinkedHashSet<>(cropSeenSinceStart == null ? Collections.emptyList() : cropSeenSinceStart);
		seen.addAll(patchIds);
		return carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt, includedPatchIds,
			excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, new ArrayList<>(seen)));
	}

	FarmingRunIntent finished(EndReason reason, long at)
	{
		FarmingRunIntent next = new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, State.COMPLETE, reason, at, includedPatchIds,
			excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart);
		next.runTypes = runTypes;
		next.suggestedOrder = suggestedOrder;
		next.protectedPatchIds = protectedPatchIds;
		next.stagePeak = stagePeak;
		next.bins = bins;
		next.skippedBins = skippedBins;
		next.protectTypes = protectTypes;
		next.skippedProtection = skippedProtection;
		next.binUpgrades = binUpgrades;
		next.skippedCompost = skippedCompost;
		next.binStart = binStart;
		return next;
	}

	/** The compost bins this run took on at Start (empty for older runs). */
	public List<String> getBins()
	{
		return bins == null ? Collections.emptyList() : Collections.unmodifiableList(bins);
	}

	public boolean isBinSkipped(String binId)
	{
		return skippedBins != null && skippedBins.contains(binId);
	}

	/** The run types whose protection is paid in this run, or null for runs from before §28.16 (the preferences then decide). */
	public List<String> getProtectTypes()
	{
		return protectTypes == null ? null : Collections.unmodifiableList(protectTypes);
	}

	public boolean isProtectionSkipped(String patchId)
	{
		return skippedProtection != null && skippedProtection.contains(patchId);
	}

	FarmingRunIntent withExtras(List<String> binIds, Map<String, String> upgrades, List<String> protect)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		next.bins = binIds == null ? null : new ArrayList<>(binIds);
		next.binUpgrades = upgrades == null ? null : new LinkedHashMap<>(upgrades);
		next.protectTypes = protect == null ? null : new ArrayList<>(protect);
		return next;
	}

	/** The upgrade chosen for a bin at Start. */
	public CompostBin.Upgrade binUpgrade(String binId)
	{
		String u = binUpgrades == null ? null : binUpgrades.get(binId);
		try
		{
			return u == null ? CompostBin.Upgrade.NONE : CompostBin.Upgrade.valueOf(u);
		}
		catch (IllegalArgumentException e)
		{
			return CompostBin.Upgrade.NONE;
		}
	}

	FarmingRunIntent withBinSkipped(String binId, boolean skip)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		List<String> l = new ArrayList<>(skippedBins == null ? Collections.emptyList() : skippedBins);
		l.remove(binId);
		if (skip)
		{
			l.add(binId);
		}
		next.skippedBins = l;
		return next;
	}

	public boolean isCompostSkipped(String patchId)
	{
		return skippedCompost != null && skippedCompost.contains(patchId);
	}

	FarmingRunIntent withCompostSkipped(String patchId, boolean skip)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		List<String> l = new ArrayList<>(skippedCompost == null ? Collections.emptyList() : skippedCompost);
		l.remove(patchId);
		if (skip)
		{
			l.add(patchId);
		}
		next.skippedCompost = l;
		return next;
	}

	/** The raw value each of the run's bins had when the run first read it; null until then. */
	public Map<String, Integer> getBinStart()
	{
		return binStart == null ? null : Collections.unmodifiableMap(binStart);
	}

	FarmingRunIntent withBinStart(Map<String, Integer> start)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		next.binStart = new LinkedHashMap<>(start);
		return next;
	}

	FarmingRunIntent withProtectionSkipped(String patchId, boolean skip)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		List<String> l = new ArrayList<>(skippedProtection == null ? Collections.emptyList() : skippedProtection);
		l.remove(patchId);
		if (skip)
		{
			l.add(patchId);
		}
		next.skippedProtection = l;
		return next;
	}

	/** Patches whose protection payment was observed this run. */
	public List<String> getProtectedPatchIds()
	{
		return protectedPatchIds == null ? Collections.emptyList() : Collections.unmodifiableList(protectedPatchIds);
	}

	FarmingRunIntent withProtected(String patchId)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		List<String> ids = new ArrayList<>(getProtectedPatchIds());
		if (!ids.contains(patchId))
		{
			ids.add(patchId);
		}
		next.protectedPatchIds = ids;
		return next;
	}

	/** Whether the preparation stage is open (Phase H). */
	public boolean isPreparing()
	{
		return preparing != null && preparing && state != State.COMPLETE;
	}

	FarmingRunIntent withPreparing(boolean open)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		next.preparing = open;
		return next;
	}

	FarmingRunIntent withSuggestedOrder(List<String> order)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		next.suggestedOrder = order == null ? null : new ArrayList<>(order);
		return next;
	}

	FarmingRunIntent withRunTypes(List<FarmingRunType> types)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		List<String> names = new ArrayList<>();
		for (FarmingRunType t : types)
		{
			names.add(t.name());
		}
		next.runTypes = names;
		return next;
	}

	FarmingRunIntent withOptions(Options next)
	{
		return carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt, includedPatchIds,
			excludedPatchIds, skippedPatchIds, plannedCrops, next, baseline, cropSeenSinceStart));
	}

	private FarmingRunIntent carry(FarmingRunIntent next)
	{
		next.preparing = preparing;
		next.runTypes = runTypes;
		next.suggestedOrder = suggestedOrder;
		next.protectedPatchIds = protectedPatchIds;
		next.stagePeak = stagePeak;
		next.bins = bins;
		next.skippedBins = skippedBins;
		next.protectTypes = protectTypes;
		next.skippedProtection = skippedProtection;
		next.binUpgrades = binUpgrades;
		next.skippedCompost = skippedCompost;
		next.binStart = binStart;
		return next;
	}

	/** The most left on a regrowing crop the run has seen since Start, or -1. */
	public int getStagePeak(String patchId)
	{
		Integer v = stagePeak == null ? null : stagePeak.get(patchId);
		return v == null ? -1 : v;
	}

	FarmingRunIntent withStagePeaks(Map<String, Integer> peaks)
	{
		FarmingRunIntent next = carry(new FarmingRunIntent(schema, intentId, runType, startedAt, lastProgressAt, state, endReason, finishedAt,
			includedPatchIds, excludedPatchIds, skippedPatchIds, plannedCrops, options, baseline, cropSeenSinceStart));
		Map<String, Integer> m = new java.util.LinkedHashMap<>(stagePeak == null ? Collections.emptyMap() : stagePeak);
		m.putAll(peaks);
		next.stagePeak = m;
		return next;
	}

	/** Structural validity of a loaded intent (a damaged file is ignored, never half-used). */
	boolean isWellFormed()
	{
		return schema == SCHEMA && intentId != null && runType != null && state != null && includedPatchIds != null
			&& (!includedPatchIds.isEmpty() || bins != null && !bins.isEmpty()) // a compost-bin run has bins and no patches (§28.17)
			&& excludedPatchIds != null && skippedPatchIds != null && plannedCrops != null && baseline != null;
	}
}
