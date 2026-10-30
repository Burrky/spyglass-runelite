package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable presentation model of Farming: every catalogued patch's
 * {@link PatchStatus}, a per-run-type summary for the overview and the run-type
 * buttons, preview rows, and the Home card summary. Built from a
 * {@link FarmingSnapshot} by {@link #build}; pure, so Home and the Farming page
 * never decode, project or count anything themselves.
 */
public final class FarmingModel
{
	/** Semantic text tones; the UI maps them to theme colours. */
	public enum Tone
	{
		GOLD,
		EMBER,
		PRIMARY,
		SECONDARY,
		MUTED,
		/** Done (a run patch observed complete). */
		SAGE,
		/** Ready (pass 32: Home's Farming card, as the overview grid). */
		GREEN,
		/** Dead (pass 32: Home's Farming card, as the overview grid). */
		RED
	}

	/** One preview row (canonical §3.2, fixed three-line shape). */
	public static final class Row
	{
		private final FarmingPatchDef patch;
		private final PatchStatus status;
		private final String location;
		private final String crop;
		private final String chip;
		private final Tone chipTone;
		private final String provenance;
		private final boolean companion;

		Row(FarmingPatchDef patch, PatchStatus status, String location, String crop, String chip, Tone chipTone, String provenance, boolean companion)
		{
			this.patch = patch;
			this.status = status;
			this.location = location;
			this.crop = crop;
			this.chip = chip;
			this.chipTone = chipTone;
			this.provenance = provenance;
			this.companion = companion;
		}

		public FarmingPatchDef getPatch()
		{
			return patch;
		}

		public String getPatchId()
		{
			return patch.getPatchId();
		}

		public PatchStatus getStatus()
		{
			return status;
		}

		/** "Falador (NW)", "Falador flower". */
		public String getLocation()
		{
			return location;
		}

		/** "Ranarr", "Unknown herb", "–". */
		public String getCrop()
		{
			return crop;
		}

		/** "READY", "~12m", "DISEASED", "CLEAR", "NOT TRACKED", ... */
		public String getChip()
		{
			return chip;
		}

		public Tone getChipTone()
		{
			return chipTone;
		}

		public String getProvenance()
		{
			return provenance;
		}

		/** A companion row (a flower patch in the Allotment preview). */
		public boolean isCompanion()
		{
			return companion;
		}
	}

	/** A patch the browser does not offer, and why ("Locked: needs Farming 65", "Not confirmed yet: …"). */
	public static final class Excluded
	{
		private final Row row;
		private final FarmingAccess.Access access;

		Excluded(Row row, FarmingAccess.Access access)
		{
			this.row = row;
			this.access = access;
		}

		public Row getRow()
		{
			return row;
		}

		public FarmingAccess.Access getAccess()
		{
			return access;
		}

		/** "Needs Morytania elite diary" / "Not confirmed yet: quest state not read yet". */
		public String getReason()
		{
			String why = String.join("; ", access.getReasons());
			return access.getState() == FarmingAccess.Access.State.LOCKED ? "Needs " + why : "Not confirmed yet" + (why.isEmpty() ? "" : ": " + why);
		}
	}

	/** One run type's counts. */
	public static final class TypeSummary
	{
		int total;
		int tracked;
		int ready;
		int readySeen;
		int readyEstimated;
		int growing;
		int attention;
		int dead;
		int clear;
		int grown;
		int check;
		Long soonestReadyAt;

		public int getTotal()
		{
			return total;
		}

		public int getTracked()
		{
			return tracked;
		}

		public int getReady()
		{
			return ready;
		}

		/** Observed ready (harvestable / check health). */
		public int getReadySeen()
		{
			return readySeen;
		}

		/** Estimated ready: the latest possible growth time has passed, but not seen ready. */
		public int getReadyEstimated()
		{
			return readyEstimated;
		}

		/** Couldn't be read when last seen ("check patch"). */
		public int getCheck()
		{
			return check;
		}

		public int getDead()
		{
			return dead;
		}

		public int getGrowing()
		{
			return growing;
		}

		public int getAttention()
		{
			return attention;
		}

		public int getClear()
		{
			return clear;
		}

		/** The soonest estimated ready time among growing patches, or null. */
		public Long getSoonestReadyAt()
		{
			return soonestReadyAt;
		}
	}

	/** The Home Farming card (canonical §1.5, rows 3-7; no runs exist yet). */
	public static final class HomeSummary
	{
		private final String headline;
		private final Tone headlineTone;
		private final List<String> facts;
		private final Tone rail;

		HomeSummary(String headline, Tone headlineTone, List<String> facts, Tone rail)
		{
			this.headline = headline;
			this.headlineTone = headlineTone;
			this.facts = Collections.unmodifiableList(facts);
			this.rail = rail;
		}

		public String getHeadline()
		{
			return headline;
		}

		public Tone getHeadlineTone()
		{
			return headlineTone;
		}

		/** Up to two short fact lines, the last possibly the muted "+ N not tracked". */
		public List<String> getFacts()
		{
			return facts;
		}

		/** GOLD, EMBER, or null (no rail). */
		public Tone getRail()
		{
			return rail;
		}
	}

	private final FarmingSnapshot.State state;
	private final FarmingOwner owner;
	private final long now;
	private final FarmingCalibration calibration;
	private final Map<String, PatchStatus> statuses;
	private final Map<FarmingRunType, TypeSummary> summaries;
	private final Map<FarmingRunType, List<Row>> rows;
	private final HomeSummary home;
	private final FarmingRunView run;
	private Map<FarmingRunType, List<Excluded>> excluded = Collections.emptyMap();
	private FarmingAccountFacts facts = FarmingAccountFacts.ALL_OPEN;

	private FarmingModel(FarmingSnapshot.State state, FarmingOwner owner, long now, FarmingCalibration calibration, Map<String, PatchStatus> statuses,
		Map<FarmingRunType, TypeSummary> summaries, Map<FarmingRunType, List<Row>> rows, HomeSummary home, FarmingRunView run)
	{
		this.run = run;
		this.state = state;
		this.owner = owner;
		this.now = now;
		this.calibration = calibration;
		this.statuses = statuses;
		this.summaries = summaries;
		this.rows = rows;
		this.home = home;
	}

	/** Every patch offered (tests and fixtures without account facts). */
	public static FarmingModel build(FarmingPatchCatalog catalog, FarmingSnapshot snapshot, long now)
	{
		return build(catalog, snapshot, now, FarmingAccountFacts.ALL_OPEN);
	}

	/**
	 * The model with the access rule applied: a gated patch is offered (rows,
	 * summaries, overview, Home, run selection) only when its access is
	 * confirmed; locked and unverified patches are listed apart with the
	 * reason. Saved runs and every patch's knowledge are untouched.
	 */
	public static FarmingModel build(FarmingPatchCatalog catalog, FarmingSnapshot snapshot, long now, FarmingAccountFacts facts)
	{
		FarmingAccountFacts f = facts == null ? FarmingAccountFacts.NONE : facts;
		Map<FarmingRunType, List<Excluded>> excluded = new EnumMap<>(FarmingRunType.class);
		boolean available = snapshot.getState() == FarmingSnapshot.State.AVAILABLE;
		FarmingCalibration calibration = available ? FarmingCalibration.from(snapshot.getSamples()) : FarmingCalibration.NONE;
		Map<String, PatchStatus> statuses = new LinkedHashMap<>();
		Map<FarmingRunType, TypeSummary> summaries = new EnumMap<>(FarmingRunType.class);
		Map<FarmingRunType, List<Row>> rows = new EnumMap<>(FarmingRunType.class);
		for (FarmingRunType r : FarmingRunType.values())
		{
			summaries.put(r, new TypeSummary());
			rows.put(r, new ArrayList<>());
		}
		List<FarmingPatchDef> patches = new ArrayList<>(catalog.allPatches());
		patches.sort(Comparator.comparing(FarmingPatchDef::getAreaName).thenComparing(p -> p.getType().ordinal()).thenComparing(FarmingPatchDef::getLabel));
		for (FarmingPatchDef p : patches)
		{
			PatchKnowledge k = available ? snapshot.getKnowledge().get(p.getPatchId()) : null;
			PatchStatus s = FarmingProjection.project(p, k, calibration, now);
			statuses.put(p.getPatchId(), s);
			FarmingRunType runType = FarmingRunType.of(p.getType());
			boolean companion = p.getType() != runType.getPrimary();
			FarmingAccess.Access access = f.getAccess().access(p.getPatchId());
			if (!access.isOpen())
			{
				excluded.computeIfAbsent(runType, x -> new ArrayList<>()).add(new Excluded(row(p, s, companion, now), access));
				continue;
			}
			rows.get(runType).add(row(p, s, companion, now));
			count(summaries.get(runType), s); // flowers count in "Allotments & Flowers" too
		}
		for (List<Row> list : rows.values())
		{
			// Observed patches first (by site), then the never-seen ones: a player's real patches lead.
			list.sort(Comparator.comparing((Row r) -> r.isCompanion()).thenComparing(r -> !r.getStatus().isTracked()));
		}
		Map<FarmingRunType, List<Row>> frozen = new EnumMap<>(FarmingRunType.class);
		Map<String, Row> byId = new LinkedHashMap<>();
		for (Map.Entry<FarmingRunType, List<Row>> e : rows.entrySet())
		{
			frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
			for (Row r : e.getValue())
			{
				byId.put(r.getPatchId(), r);
			}
		}
		for (List<Excluded> list : excluded.values())
		{
			for (Excluded e : list)
			{
				byId.putIfAbsent(e.getRow().getPatchId(), e.getRow()); // a saved run may still hold it: it keeps its line
			}
		}
		FarmingRunView run = available && snapshot.getRun() != null
			? FarmingRunView.build(snapshot.getRun(), catalog, snapshot, byId, now, f.getAccess(), f.isAccountRead()) : null;
		Map<String, PatchStatus> offered = new LinkedHashMap<>();
		for (List<Row> list : frozen.values())
		{
			for (Row r : list)
			{
				offered.put(r.getPatchId(), r.getStatus());
			}
		}
		HomeSummary home = run != null && run.getPhase() != FarmingRunView.Phase.COMPLETE ? runHome(run)
			: home(snapshot.getState(), catalog, offered, summaries, now);
		FarmingModel m = new FarmingModel(snapshot.getState(), snapshot.getOwner(), now, calibration, Collections.unmodifiableMap(statuses),
			Collections.unmodifiableMap(summaries), Collections.unmodifiableMap(frozen), home, run);
		m.excluded = Collections.unmodifiableMap(excluded);
		m.facts = f;
		m.anima = FarmingAnima.of(snapshot.getKnowledge(), catalog, now);
		m.bins = snapshot.getBins();
		m.compostLines = available ? compostLines(frozen, snapshot.getKnowledge(), snapshot.getCompost()) : Collections.emptyList();
		m.compostByPatch = available ? compostByPatch(frozen, snapshot.getKnowledge(), snapshot.getCompost()) : Collections.emptyMap();
		return m;
	}

	/**
	 * Compost on the planted patches of one run type, from the game's own words only (canonical §28.14): treated, by kind;
	 * Inspect's "not treated"; and planted patches the game has not said anything about (never shown as untreated).
	 */
	public static final class CompostLine
	{
		private final FarmingRunType type;
		private final Map<PatchCompost.Kind, Integer> treated;
		private final int untreated;
		private final int unread;

		CompostLine(FarmingRunType type, Map<PatchCompost.Kind, Integer> treated, int untreated, int unread)
		{
			this.type = type;
			this.treated = Collections.unmodifiableMap(treated);
			this.untreated = untreated;
			this.unread = unread;
		}

		public FarmingRunType getType()
		{
			return type;
		}

		/** Kind (compost, super, ultra) → planted patches the game showed treated with it. */
		public Map<PatchCompost.Kind, Integer> getTreated()
		{
			return treated;
		}

		public int getUntreated()
		{
			return untreated;
		}

		public int getUnread()
		{
			return unread;
		}
	}

	static List<CompostLine> compostLines(Map<FarmingRunType, List<Row>> rows, Map<String, PatchKnowledge> knowledge,
		Map<String, PatchCompost> compost)
	{
		List<CompostLine> out = new ArrayList<>();
		for (FarmingRunType t : FarmingRunType.values())
		{
			List<Row> list = rows.get(t);
			if (!t.isCrop() || list == null)
			{
				continue;
			}
			Map<PatchCompost.Kind, Integer> treated = new EnumMap<>(PatchCompost.Kind.class);
			int untreated = 0;
			int unread = 0;
			for (Row r : list)
			{
				PatchKnowledge k = knowledge == null ? null : knowledge.get(r.getPatchId());
				if (k == null || k.getCycle() == null)
				{
					continue; // nothing planted that Spyglass has seen
				}
				PatchCompost rec = compost == null ? null : compost.get(r.getPatchId());
				PatchCompost cur = rec == null ? null : rec.current(k);
				if (cur == null)
				{
					unread++;
				}
				else if (cur.getKind() == PatchCompost.Kind.NONE)
				{
					untreated++;
				}
				else
				{
					treated.merge(cur.getKind(), 1, Integer::sum);
				}
			}
			if (!treated.isEmpty() || untreated > 0 || unread > 0)
			{
				out.add(new CompostLine(t, treated, untreated, unread));
			}
		}
		return Collections.unmodifiableList(out);
	}

	private List<CompostLine> compostLines = Collections.emptyList();
	private Map<FarmingPatchType, CompostLine> compostByPatch = Collections.emptyMap();

	/**
	 * Pass 31: the same reading per patch type (allotments and flowers apart), for the compost choices' "Last observed" line;
	 * absent: nothing planted of that type that Spyglass has seen.
	 */
	public CompostLine compostObserved(FarmingPatchType type)
	{
		return compostByPatch.get(type);
	}

	static Map<FarmingPatchType, CompostLine> compostByPatch(Map<FarmingRunType, List<Row>> rows, Map<String, PatchKnowledge> knowledge,
		Map<String, PatchCompost> compost)
	{
		Map<FarmingPatchType, Map<PatchCompost.Kind, Integer>> treated = new EnumMap<>(FarmingPatchType.class);
		Map<FarmingPatchType, int[]> counts = new EnumMap<>(FarmingPatchType.class);
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (FarmingRunType t : FarmingRunType.values())
		{
			List<Row> list = rows.get(t);
			if (!t.isCrop() || list == null)
			{
				continue;
			}
			for (Row r : list)
			{
				if (!seen.add(r.getPatchId()))
				{
					continue;
				}
				PatchKnowledge k = knowledge == null ? null : knowledge.get(r.getPatchId());
				if (k == null || k.getCycle() == null)
				{
					continue;
				}
				FarmingPatchType pt = r.getPatch().getType();
				PatchCompost rec = compost == null ? null : compost.get(r.getPatchId());
				PatchCompost cur = rec == null ? null : rec.current(k);
				int[] c = counts.computeIfAbsent(pt, x -> new int[2]);
				treated.computeIfAbsent(pt, x -> new EnumMap<>(PatchCompost.Kind.class));
				if (cur == null)
				{
					c[1]++;
				}
				else if (cur.getKind() == PatchCompost.Kind.NONE)
				{
					c[0]++;
				}
				else
				{
					treated.get(pt).merge(cur.getKind(), 1, Integer::sum);
				}
			}
		}
		Map<FarmingPatchType, CompostLine> out = new EnumMap<>(FarmingPatchType.class);
		for (Map.Entry<FarmingPatchType, int[]> e : counts.entrySet())
		{
			out.put(e.getKey(), new CompostLine(FarmingRunType.of(e.getKey()), treated.get(e.getKey()), e.getValue()[0], e.getValue()[1]));
		}
		return Collections.unmodifiableMap(out);
	}

	/** Compost on planted patches, one line per run type with any (the compost overview, outside runs too). */
	public List<CompostLine> getCompostLines()
	{
		return compostLines;
	}

	private FarmingAnima anima;
	private Map<String, CompostBin.Seen> bins = Collections.emptyMap();

	/** The compost bins as last seen, by bin id (a bin never seen is absent). */
	public Map<String, CompostBin.Seen> getBins()
	{
		return bins;
	}

	/** The anima plant as last observed (never chosen in Spyglass). */
	public FarmingAnima getAnima()
	{
		return anima;
	}

	private static void count(TypeSummary t, PatchStatus s)
	{
		t.total++;
		if (!s.isTracked())
		{
			return;
		}
		t.tracked++;
		switch (s.getDisplay())
		{
			case READY:
				t.ready++;
				if (s.getReadySource() == PatchStatus.ReadySource.ESTIMATED)
				{
					t.readyEstimated++;
				}
				else
				{
					t.readySeen++;
				}
				break;
			case GROWING_TIMED:
				t.growing++;
				if (t.soonestReadyAt == null || s.getReadyAt() < t.soonestReadyAt)
				{
					t.soonestReadyAt = s.getReadyAt();
				}
				break;
			case GROWING:
				t.growing++;
				break;
			case DISEASED:
				t.attention++;
				break;
			case DEAD:
				t.attention++;
				t.dead++;
				break;
			case CLEAR:
				t.clear++;
				break;
			case GROWN:
				t.grown++;
				break;
			case CHECK_PATCH:
				t.check++;
				break;
			default:
				break;
		}
	}

	// -- rows ----------------------------------------------------------------------

	static Row row(FarmingPatchDef p, PatchStatus s, boolean companion, long now)
	{
		String location = location(p);
		String crop;
		if (s.getProduce() != null)
		{
			crop = s.getProduce().getDisplayName();
		}
		else if (s.isProduceUnknown())
		{
			crop = "Unknown " + p.getType().getDisplayName().toLowerCase();
		}
		else
		{
			crop = "–";
		}
		String chip;
		Tone tone;
		switch (s.getDisplay())
		{
			case READY:
				chip = "READY";
				tone = Tone.GOLD;
				break;
			case GROWING_TIMED:
				chip = FarmingText.remaining(s.getReadyAt() - now);
				tone = Tone.PRIMARY;
				break;
			case GROWING:
				chip = "GROWING";
				tone = Tone.SECONDARY;
				break;
			case GROWN:
				chip = "GROWN";
				tone = Tone.SECONDARY;
				break;
			case DISEASED:
				chip = "DISEASED";
				tone = Tone.EMBER;
				break;
			case DEAD:
				chip = "DEAD";
				tone = Tone.EMBER;
				break;
			case CLEAR:
				chip = "CLEAR";
				tone = Tone.SECONDARY;
				break;
			case CHECK_PATCH:
				chip = "CHECK PATCH?";
				tone = Tone.MUTED;
				break;
			case NOT_TRACKED:
			default:
				chip = "NOT TRACKED";
				tone = Tone.MUTED;
				break;
		}
		return new Row(p, s, location, crop, chip, tone, FarmingText.provenance(s, now), companion);
	}

	/** "Falador (NW)", "Fossil Island (East)", "Falador flower". */
	static String location(FarmingPatchDef p)
	{
		if (p.getType() == FarmingPatchType.FLOWER)
		{
			return p.getAreaName() + " flower";
		}
		String label = shortLabel(p.getLabel());
		return label.isEmpty() ? p.getAreaName() : p.getAreaName() + " (" + label + ")";
	}

	private static String shortLabel(String label)
	{
		switch (label)
		{
			case "North":
				return "N";
			case "South":
				return "S";
			case "North West":
				return "NW";
			case "South East":
				return "SE";
			case "North East":
				return "NE";
			case "South West":
				return "SW";
			default:
				return label;
		}
	}

	// -- overview --------------------------------------------------------------------

	/** "6 ready", "growing · ~3h 10m", "2 clear", ... for a tracked run type; null when untracked. */
	public String overviewLine(FarmingRunType type)
	{
		TypeSummary t = summaries.get(type);
		if (type == FarmingRunType.ANIMA && anima != null && anima.getState() != FarmingAnima.State.UNKNOWN)
		{
			return anima.text(now);
		}
		if (t.tracked == 0)
		{
			return null;
		}
		List<String> parts = new ArrayList<>();
		if (t.attention > 0)
		{
			parts.add(t.attention + (t.dead == t.attention ? " dead" : t.dead == 0 ? " diseased" : " need attention"));
		}
		if (t.ready > 0)
		{
			// Seen ready vs. only estimated: an estimate is never presented as an observation.
			parts.add(t.ready + " ready"); // seen vs. timed stays in the provenance (PatchStatus.ReadySource), not the player's words
		}
		if (!parts.isEmpty())
		{
			return String.join(" · ", parts);
		}
		if (t.soonestReadyAt != null)
		{
			return "growing · next " + FarmingText.remaining(t.soonestReadyAt - now);
		}
		if (t.growing > 0)
		{
			return "growing";
		}
		if (t.grown > 0)
		{
			return "grown";
		}
		if (t.clear > 0)
		{
			return t.clear + " clear";
		}
		return "check patch";
	}

	public Tone overviewTone(FarmingRunType type)
	{
		TypeSummary t = summaries.get(type);
		if (t.attention > 0)
		{
			return Tone.EMBER;
		}
		return t.ready > 0 ? Tone.GOLD : Tone.SECONDARY;
	}

	/** Run types with at least one observed patch, in button order. */
	public List<FarmingRunType> trackedTypes()
	{
		List<FarmingRunType> out = new ArrayList<>();
		for (FarmingRunType r : FarmingRunType.values())
		{
			if (r.isCrop() && summaries.get(r).tracked > 0)
			{
				out.add(r);
			}
		}
		return out;
	}

	/** Run types never observed, in button order. */
	public List<FarmingRunType> untrackedTypes()
	{
		List<FarmingRunType> out = new ArrayList<>();
		for (FarmingRunType r : FarmingRunType.values())
		{
			if (r.isCrop() && summaries.get(r).tracked == 0)
			{
				out.add(r);
			}
		}
		return out;
	}

	// -- home ------------------------------------------------------------------------

	/** Canonical §1.5 rows 1-2: an open run leads the Farming card. */
	private static HomeSummary runHome(FarmingRunView run)
	{
		String type = run.getRunTitle();
		String count = run.getProgress().getSettled() + " / " + run.getProgress().getTotal();
		List<String> facts = new ArrayList<>();
		if (run.getPhase() == FarmingRunView.Phase.PAUSED)
		{
			return new HomeSummary(type + " run paused · " + count, Tone.GOLD, facts, Tone.GOLD);
		}
		if (run.getPhase() == FarmingRunView.Phase.PREPARING)
		{
			facts.add("getting ready");
			return new HomeSummary(type + " run · preparing", Tone.GOLD, facts, Tone.GOLD);
		}
		String next = run.getNextTitle();
		if (next != null)
		{
			String place = next.contains(" \u00b7 ") ? next.substring(0, next.indexOf(" \u00b7 ")) : next;
			facts.add(run.isNextHere() ? "at " + place : "next: " + place);
		}
		return new HomeSummary(type + " run · " + count, Tone.GOLD, facts, Tone.GOLD);
	}

	private static HomeSummary home(FarmingSnapshot.State state, FarmingPatchCatalog catalog, Map<String, PatchStatus> statuses,
		Map<FarmingRunType, TypeSummary> summaries, long now)
	{
		switch (state)
		{
			case LOGGED_OUT:
				return new HomeSummary("Log in to see your patches", Tone.MUTED, Collections.emptyList(), null);
			case NOT_AVAILABLE_HERE:
				return new HomeSummary("Not on this world", Tone.MUTED, Collections.singletonList("Farming is tracked on standard worlds"), null);
			case LOADING:
				return new HomeSummary("Loading your patches", Tone.MUTED, Collections.emptyList(), null);
			case AVAILABLE:
			default:
				break;
		}
		Map<FarmingPatchType, int[]> byType = new EnumMap<>(FarmingPatchType.class);
		int attention = 0;
		int dead = 0;
		int tracked = 0;
		int growing = 0;
		PatchStatus soonest = null;
		FarmingPatchType soonestType = null;
		for (FarmingPatchDef p : catalog.allPatches())
		{
			PatchStatus s = statuses.get(p.getPatchId());
			if (s == null || !s.isTracked())
			{
				continue;
			}
			tracked++;
			int[] c = byType.computeIfAbsent(p.getType(), k -> new int[1]);
			if (s.needsAttention())
			{
				attention++;
				if (s.getDisplay() == PatchStatus.Display.DEAD)
				{
					dead++;
				}
			}
			if (s.isReady())
			{
				c[0]++;
			}
			if (s.getDisplay() == PatchStatus.Display.GROWING_TIMED || s.getDisplay() == PatchStatus.Display.GROWING)
			{
				growing++;
				if (s.getReadyAt() != null && (soonest == null || s.getReadyAt() < soonest.getReadyAt()))
				{
					soonest = s;
					soonestType = p.getType();
				}
			}
		}
		if (tracked == 0)
		{
			return new HomeSummary("Not tracked yet", Tone.MUTED, Collections.singletonList("Visit a patch to start"), null);
		}
		List<String> facts = new ArrayList<>();
		HomeSummary base;
		// Pass 32: the overview grid's priority (Ready, then Growing, then Dead; then diseased), with the count of that state.
		// Readiness is the same per-patch status the grid counts; untracked patches never count.
		int readyTotal = 0;
		List<String> ready = new ArrayList<>();
		for (Map.Entry<FarmingPatchType, int[]> e : byType.entrySet())
		{
			if (e.getValue()[0] > 0)
			{
				readyTotal += e.getValue()[0];
				ready.add(e.getValue()[0] + " " + noun(e.getKey(), e.getValue()[0]));
			}
		}
		if (readyTotal > 0)
		{
			String list = ready.size() <= 2 ? String.join(" · ", ready) : ready.get(0) + " · " + ready.get(1) + " +" + (ready.size() - 2);
			facts.add(list + " ready");
			base = new HomeSummary(readyTotal + " Ready", Tone.GREEN, facts, Tone.GREEN);
		}
		else if (growing > 0)
		{
			if (soonest != null)
			{
				String noun = noun(soonestType, 2);
				facts.add("Next: " + noun + " in " + FarmingText.remaining(soonest.getReadyAt() - now));
			}
			base = new HomeSummary(growing + " Growing", Tone.PRIMARY, facts, null);
		}
		else if (dead > 0)
		{
			facts.add(dead == 1 ? "1 patch last seen dead" : dead + " patches last seen dead");
			base = new HomeSummary(dead + " Dead", Tone.RED, facts, Tone.RED);
		}
		else if (attention > 0)
		{
			facts.add(attention == 1 ? "1 patch last seen diseased" : attention + " patches last seen diseased");
			base = new HomeSummary(attention + " Diseased", Tone.EMBER, facts, Tone.EMBER);
		}
		else
		{
			base = new HomeSummary("No crops growing", Tone.SECONDARY, facts, null);
		}
		int notTracked = 0;
		for (TypeSummary t : summaries.values())
		{
			if (t.tracked > 0)
			{
				notTracked += t.total - t.tracked;
			}
		}
		if (notTracked > 0 && base.getFacts().size() < 2)
		{
			List<String> withExtra = new ArrayList<>(base.getFacts());
			withExtra.add("+ " + notTracked + " not tracked");
			return new HomeSummary(base.getHeadline(), base.getHeadlineTone(), withExtra, base.getRail());
		}
		return base;
	}

	/** "herb" / "herbs", "fruit tree" / "fruit trees", "cactus" / "cacti". */
	static String noun(FarmingPatchType type, int count)
	{
		String one = type.getDisplayName().toLowerCase();
		if (count == 1)
		{
			return one;
		}
		switch (type)
		{
			case HOPS:
			case SEAWEED:
			case BELLADONNA:
			case CELASTRUS:
			case HESPORI:
				return one;
			case BUSH:
				return "bushes";
			case CACTUS:
				return "cacti";
			case ANIMA:
				return "anima plants";
			default:
				return one + "s";
		}
	}

	// -- accessors -------------------------------------------------------------------

	public FarmingSnapshot.State getState()
	{
		return state;
	}

	public FarmingOwner getOwner()
	{
		return owner;
	}

	public long getNow()
	{
		return now;
	}

	public FarmingCalibration getCalibration()
	{
		return calibration;
	}

	public PatchStatus status(String patchId)
	{
		return statuses.get(patchId);
	}

	public TypeSummary summary(FarmingRunType type)
	{
		return summaries.get(type);
	}

	/** The preview rows of a run type: its patches by site, companions (flowers) included. */
	public List<Row> rows(FarmingRunType type)
	{
		return rows.get(type);
	}

	/** The owner's run (open, or complete and awaiting Done), or null. */
	public FarmingRunView getRun()
	{
		return run;
	}

	public HomeSummary getHome()
	{
		return home;
	}

	/** Patches of a run type not offered (locked or not confirmed), with the reason. */
	public List<Excluded> excluded(FarmingRunType type)
	{
		return excluded.getOrDefault(type, Collections.emptyList());
	}

	public FarmingAccountFacts getFacts()
	{
		return facts;
	}
}
