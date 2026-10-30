package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleStats;
import com.osrstelemetry.plugin.skills.SkillExternalStock;
import com.osrstelemetry.plugin.skills.SkillGoal;
import com.osrstelemetry.plugin.skills.SkillGoalPlanner;
import com.osrstelemetry.plugin.skills.SkillProductionMerge;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.UnitFamily;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The skill companion's player-facing numbers, decided in one pure place
 * (generic: every word comes from the skill's {@link SkillCompanionDefinition}).
 * CurrentSessionView only lays these out; tests read them directly.
 *
 * <h3>Modular</h3>
 * Every section is optional and simply absent when it does not earn its
 * space: no METHOD while no reliable evidence names one, no CURRENT TRIP for
 * a method whose capacity is not a decision, no PRODUCTION before anything
 * was produced. There are no placeholder rows.
 *
 * <h3>Production wording</h3>
 * The MAIN cell counts only the method's main resources ("IRON MINED",
 * "AMETHYST", "GEMS MINED"), or "TOTAL" with a short per-resource line when
 * different main resources mix in one session. By-products (gems from the
 * gem table, calcified deposits) get their own compact line and are never
 * folded into a number the player would read as the main resource.
 */
final class SkillCompanionModel
{
	static final long MIN_RATE_MILLIS = SkillGoalPlanner.MIN_RATE_MILLIS;

	/** PRODUCTION: the main cell, its banked part, the method rate and the item sprites under it. */
	static final class Production
	{
		final String caption;
		final long total;
		final long banked;
		final String rateText;
		final String rateTooltip;
		/** The main resources as sprites + numbers (when they mix, or per-resource rows); null when the cell says it all. */
		SkillCompanionView.ItemGroup mainItems;
		/** Sprites + numbers after the main ones: by-products, other methods' output. */
		final List<SkillCompanionView.ItemGroup> itemGroups;
		/** The caption cell(s) earn their space (false when per-resource rows already say everything). */
		boolean showCells = true;
		/** BANKED is shown live (false for a skill whose items are made to be used, not gathered). */
		boolean showBanked = true;
		/** "&lt;count&gt; &lt;auto-transformed verb&gt;" (part of the total, never banked); null when none. */
		String autoTransformedLine;
		/** "&lt;count&gt; &lt;consumed phrase&gt;" (session-owned inputs used up); null when none. */
		String consumedLine;
		/** Non-primary inputs used up, as sprites + numbers; null when none. */
		SkillCompanionView.ItemGroup otherInputs;
		/** Method lines derived from occurrences ("Bound 412 / 448 (92%)"). */
		List<String> occurrenceLines = Collections.emptyList();
		/** Unit-family production (Phase 6): the main cell's text ("1,086 units"); null = the plain count. */
		String totalText;

		Production(String caption, long total, long banked, String rateText, String rateTooltip, List<SkillCompanionView.ItemGroup> itemGroups)
		{
			this.caption = caption;
			this.total = total;
			this.banked = banked;
			this.rateText = rateText;
			this.rateTooltip = rateTooltip;
			this.itemGroups = itemGroups;
		}
	}

	/** CURRENT TRIP. */
	static final class Trip
	{
		final int spaceLeft;
		final String estFull;
		final String estFullTooltip;
		final String storageLine;

		Trip(int spaceLeft, String estFull, String estFullTooltip, String storageLine)
		{
			this.spaceLeft = spaceLeft;
			this.estFull = estFull;
			this.estFullTooltip = estFullTooltip;
			this.storageLine = storageLine;
		}
	}

	/** TARGET (LEVEL or STOCKPILE). */
	static final class Target
	{
		final SkillGoal goal;
		/** STOCKPILE: the resource the target counts (resolved), or null when none can be offered. */
		final SkillResource resource;
		final List<SkillResource> choices;
		/** LEVEL: XP LEFT; STOCKPILE: BANKED (what the bank holds). Null = unknown. */
		final Long first;
		/** STOCKPILE: REMAINING. */
		final Long remaining;
		final Long etaMillis;
		final boolean reached;
		/**
		 * ADDED (Phase 6) -- set only when the stockpile is counted in a unit
		 * family or also lives in an account storage outside the bank (null
		 * otherwise: the plain IN BANK / REMAINING numbers are used).
		 */
		String firstCaption;
		String firstText;
		String firstTooltip;
		String remainingText;
		/** The target amount as the player set it ("1,000 × full"). */
		String quantityText;
		/** Where OWNED comes from ("Bank + <storage>"); null = bank only. */
		String ownershipLine;
		/** The ownership line is a warning (part of the stock is unknown). */
		boolean ownershipAlert;
		/** The target prompt's noun ("<resource> (as full) in your bank and <storage>"). */
		String promptNoun;
		/** OWNED counts what you carry and the ETA follows this session's own rate of it (Agility rewards). */
		boolean growsWithProduction;

		Target(SkillGoal goal, SkillResource resource, List<SkillResource> choices, Long first, Long remaining, Long etaMillis, boolean reached)
		{
			this.goal = goal;
			this.resource = resource;
			this.choices = choices;
			this.first = first;
			this.remaining = remaining;
			this.etaMillis = etaMillis;
			this.reached = reached;
		}
	}

	/** SESSION (Phase 6): the shown method's own successful actions and their rate (never blended across methods). */
	static final class ActionSession
	{
		final String caption;
		final long count;
		final String rateCaption;
		final String rateText;

		ActionSession(String caption, long count, String rateCaption, String rateText)
		{
			this.caption = caption;
			this.count = count;
			this.rateCaption = rateCaption;
			this.rateText = rateText;
		}
	}

	/** INPUTS USED (Phase 6): the definition's exact input cells, then compact lines (actual savings). */
	static final class Inputs
	{
		final List<SkillCompanionView.EventView.Field> fields;
		final List<String> lines;
		/** The inputs as sprites + exact counts (instead of cells); empty = none. */
		List<SkillCompanionView.ItemStat> items = Collections.emptyList();
		String title = "Inputs used";

		Inputs(List<SkillCompanionView.EventView.Field> fields, List<String> lines)
		{
			this.fields = fields;
			this.lines = lines;
		}
	}

	final String methodKey;
	final String methodLabel;
	final Trip trip;
	/** Not final: a method whose action count is its output takes the action rate here (actionRateInProduction). */
	Production production;
	final List<SkillCompanionView.ContextLine> context;
	final Target target;
	/** SUPPLIES: the current method's consumable (count + runway), or null when it uses none. */
	final Supply supply;
	/** CURRENT LAP (cycle-based skills): null when none is known for the shown method. */
	Cycle cycle;
	/** SESSION performance of the shown method's cycles (count, rate, avg, best); null for skills without cycles. */
	CycleSession cycleSession;
	/** REWARDS (skills whose items are secondary rewards): null when the method hands out none. */
	Rewards rewards;
	/** CURRENT FOCUS (the one resource node being worked); null when none. */
	Focus focus;
	/** A CONTEXTUAL EVENT running right now; null when none. */
	Event event;
	/** SECONDARY families with a section of their own; null when none was produced. */
	Secondary secondary;
	/** SESSION actions of the shown method (Phase 6); null when the skill / method records none. */
	ActionSession actions;
	/** INPUTS USED (Phase 6); null when the definition offers no input cells. */
	Inputs inputs;
	/** What the account holds for the method outside the inventory ("In bank"); null when none. */
	Event stock;

	/** CURRENT FOCUS: what it is, what it gave and for how long (never a remaining estimate). */
	static final class Focus
	{
		final String title;
		final String label;
		final String countCaption;
		final long count;
		final String timeCaption;
		final String timeText;
		/** A quiet line under the numbers ("Last tree: 4 logs"); null = none. */
		String note;
		String noteTooltip;

		Focus(String title, String label, String countCaption, long count, String timeCaption, String timeText)
		{
			this.title = title;
			this.label = label;
			this.countCaption = countCaption;
			this.count = count;
			this.timeCaption = timeCaption;
			this.timeText = timeText;
		}
	}

	/** CONTEXTUAL EVENT: title, name, time, its own fields and lines. */
	static final class Event
	{
		/** Section title override (e.g. "Current phase"); null = the definition's eventTitle(). */
		String title;
		final String name;
		final String timeText;
		final List<SkillCompanionView.EventView.Field> fields;
		final List<SkillCompanionView.ContextLine> lines;
		/** Sprites + numbers after the fields (none by default). */
		List<SkillCompanionView.ItemGroup> itemGroups = Collections.emptyList();

		Event(String name, String timeText, List<SkillCompanionView.EventView.Field> fields, List<SkillCompanionView.ContextLine> lines)
		{
			this.name = name;
			this.timeText = timeText;
			this.fields = fields;
			this.lines = lines;
		}
	}

	/** SECONDARY: count + rate cells per family, then compact exact lines. */
	static final class Secondary
	{
		static final class Cell
		{
			final String caption;
			final String value;
			final String tooltip;

			Cell(String caption, String value, String tooltip)
			{
				this.caption = caption;
				this.value = value;
				this.tooltip = tooltip;
			}
		}

		final List<Cell> cells;
		final List<String> lines;
		/** Families shown compactly: sprites + exact numbers, titled by the family. */
		final List<SkillCompanionView.ItemGroup> groups;

		Secondary(List<Cell> cells, List<String> lines, List<SkillCompanionView.ItemGroup> groups)
		{
			this.cells = cells;
			this.lines = lines;
			this.groups = groups;
		}
	}

	/** CURRENT LAP: route progress and the running lap's time. */
	static final class Cycle
	{
		final String progressCaption;
		final String progressText;
		final String timeCaption;
		final String timeText;
		final String timeTooltip;
		final boolean synced;

		Cycle(String progressCaption, String progressText, String timeCaption, String timeText, String timeTooltip, boolean synced)
		{
			this.progressCaption = progressCaption;
			this.progressText = progressText;
			this.timeCaption = timeCaption;
			this.timeText = timeText;
			this.timeTooltip = timeTooltip;
			this.synced = synced;
		}
	}

	/** SESSION: the shown method's own cycles (never blended across methods). */
	static final class CycleSession
	{
		final String countCaption;
		final long count;
		final String rateCaption;
		final String rateText;
		final String avgCaption;
		final String avgText;
		final String bestCaption;
		final String bestText;
		final long failures;

		CycleSession(String countCaption, long count, String rateCaption, String rateText, String avgCaption, String avgText,
			String bestCaption, String bestText, long failures)
		{
			this.countCaption = countCaption;
			this.count = count;
			this.rateCaption = rateCaption;
			this.rateText = rateText;
			this.avgCaption = avgCaption;
			this.avgText = avgText;
			this.bestCaption = bestCaption;
			this.bestText = bestText;
			this.failures = failures;
		}
	}

	/** REWARDS: the method's own reward, collected this session, and its observed rate. */
	static final class Rewards
	{
		final String caption;
		final long count;
		final String rateCaption;
		final String rateText;
		final String rateTooltip;

		Rewards(String caption, long count, String rateCaption, String rateText, String rateTooltip)
		{
			this.caption = caption;
			this.count = count;
			this.rateCaption = rateCaption;
			this.rateText = rateText;
			this.rateTooltip = rateTooltip;
		}
	}

	/** SUPPLIES. */
	static final class Supply
	{
		final String caption;
		final long count;
		final String runway;
		final String runwayTooltip;
		/** The supply item (its sprite); -1 = none known. */
		int itemId = -1;
		String name;

		Supply(String caption, long count, String runway, String runwayTooltip)
		{
			this.caption = caption;
			this.count = count;
			this.runway = runway;
			this.runwayTooltip = runwayTooltip;
		}
	}

	private SkillCompanionModel(String methodKey, String methodLabel, Trip trip, Production production,
		List<SkillCompanionView.ContextLine> context, Target target)
	{
		this.methodKey = methodKey;
		this.methodLabel = methodLabel;
		this.trip = trip;
		this.production = production;
		this.context = context;
		this.target = target;
		this.supply = null;
	}

	private SkillCompanionModel(SkillCompanionModel base, List<SkillCompanionView.ContextLine> context)
	{
		this.methodKey = base.methodKey;
		this.methodLabel = base.methodLabel;
		this.trip = base.trip;
		this.production = base.production;
		this.context = Collections.unmodifiableList(context);
		this.target = base.target;
		this.supply = base.supply;
		this.cycle = base.cycle;
		this.cycleSession = base.cycleSession;
		this.rewards = base.rewards;
		this.focus = base.focus;
		this.event = base.event;
		this.secondary = base.secondary;
		this.actions = base.actions;
		this.inputs = base.inputs;
		this.stock = base.stock;
	}

	private SkillCompanionModel(SkillCompanionModel base, Supply supply)
	{
		this.methodKey = base.methodKey;
		this.methodLabel = base.methodLabel;
		this.trip = base.trip;
		this.production = base.production;
		this.context = base.context;
		this.target = base.target;
		this.supply = supply;
		this.cycle = base.cycle;
		this.cycleSession = base.cycleSession;
		this.rewards = base.rewards;
		this.focus = base.focus;
		this.event = base.event;
		this.secondary = base.secondary;
		this.actions = base.actions;
		this.inputs = base.inputs;
		this.stock = base.stock;
	}

	static SkillCompanionModel build(CurrentSessionSnapshot snapshot, SkillCompanionDefinition def, SkillCompanionView view, SkillGoal goal,
		Integer skillXp, IntFunction<Long> bankQuantity)
	{
		return build(snapshot, def, view, goal, skillXp, bankQuantity, null);
	}

	/**
	 * ADDED (Phase 6): {@code externalStock} = an account storage outside the
	 * bank for a stockpile resource (null = none, as for every earlier skill).
	 */
	static SkillCompanionModel build(CurrentSessionSnapshot snapshot, SkillCompanionDefinition def, SkillCompanionView view, SkillGoal goal,
		Integer skillXp, IntFunction<Long> bankQuantity, java.util.function.Function<SkillResource, SkillExternalStock> externalStock)
	{
		if (view == null)
		{
			view = SkillCompanionView.EMPTY;
		}
		boolean bound = SkillProductionMerge.bound(view, snapshot.getSessionId());
		SessionAggregates.SkillSessionStats stats = snapshot.getSkillStats();

		Map<Integer, Long> sessionProduced = new LinkedHashMap<>();
		Map<Integer, String> unnamed = new LinkedHashMap<>();
		for (CurrentSessionSnapshot.ProducedItemView p : snapshot.getProducedItems())
		{
			Integer id = p.getItemId() == null ? -1 : p.getItemId();
			sessionProduced.merge(id, p.getQuantity(), Long::sum);
			unnamed.put(id, p.getItemName());
		}
		Map<Integer, Long> sessionBanked = new LinkedHashMap<>();
		if (stats != null && stats.getBanked() != null)
		{
			for (SessionAggregates.ProducedItemAggregate b : stats.getBanked())
			{
				sessionBanked.merge(b.getItemId() == null ? -1 : b.getItemId(), b.getQuantity(), Long::sum);
			}
		}
		Map<String, Long> sessionMillis = stats == null || stats.getMethodMillis() == null
			? Collections.<String, Long>emptyMap() : stats.getMethodMillis();

		Map<Integer, Long> produced = SkillProductionMerge.merge(sessionProduced, view.getFlushedProduced(), view.getPendingProduced(), bound);
		Map<Integer, Long> banked = SkillProductionMerge.merge(sessionBanked, view.getFlushedBanked(), view.getPendingBanked(), bound);
		Map<String, Long> methodMillis = SkillProductionMerge.merge(sessionMillis, view.getFlushedMethodMillis(), view.getPendingMethodMillis(), bound);

		String method = view.getMethodKey() != null ? view.getMethodKey() : stats == null ? null : stats.getLastMethod();
		String label = def.methodLabel(method);

		Trip trip = buildTrip(def, view, method, produced);
		Production production = def.productionIsReward() ? null
			: buildProduction(def, method, produced, banked, methodMillis, snapshot.getActiveDurationMillis());
		production = withAutoTransformed(production, def, stats, view, bound);
		production = withConsumed(production, def, stats, view, bound);
		if (production != null)
		{
			List<String> occLines = def.liveOccurrenceLines(method, occurrences(stats, view, bound));
			if (!occLines.isEmpty())
			{
				production.occurrenceLines = occLines;
			}
		}
		Target target = buildTarget(def, goal, method, produced, skillXp, xpPerHour(snapshot, def), bankQuantity,
			bound ? view.getBanking() : Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap(), externalStock);
		if (def.targetGrowsWithProduction(method) && target.resource != null && target.goal.getFamily() != SkillGoal.Family.LEVEL)
		{
			target = carriedTarget(target, def, method, produced, bankQuantity, view.getCarried(), methodMillis);
		}
		SkillCompanionModel model = new SkillCompanionModel(method, label, trip, production, view.getContext(), target);
		addExtras(model, def, view, stats, method, produced, methodMillis, bound);
		addActionsAndInputs(model, def, view, stats, method, methodMillis, bound);
		SkillCompanionView.SupplyView sv = view.getSupply();
		if (sv == null)
		{
			return withCycles(model, def, view, stats, method, produced, methodMillis, bound);
		}
		String runway;
		String tip;
		if (sv.getCount() == 0)
		{
			runway = "Out";
			tip = "None left in your inventory: this method stops until you get more";
		}
		else if (sv.getRunwayMillis() == null)
		{
			runway = null;
			tip = "How long what you carry lasts at what this method actually uses -- appears once enough was really used "
				+ "(never assumed one per success, never a theoretical rate)";
		}
		else
		{
			runway = SkillGoalPlanner.etaText(sv.getRunwayMillis());
			tip = "How long what you carry lasts at what this method actually used so far (bank supplies don't count)";
		}
		Supply supply = new Supply(sv.getName().toUpperCase(java.util.Locale.ROOT), sv.getCount(), runway, tip);
		supply.itemId = sv.getItemId();
		supply.name = sv.getName();
		return withCycles(new SkillCompanionModel(model, supply),
			def, view, stats, method, produced, methodMillis, bound);
	}

	// ---- CYCLES (laps) and REWARDS --------------------------------------------------------------

	static final long MIN_REWARD_RATE_MILLIS = 10L * 60_000L;
	static final long MIN_REWARD_RATE_COUNT = 2L;

	private static SkillCompanionModel withCycles(SkillCompanionModel model, SkillCompanionDefinition def, SkillCompanionView view,
		SessionAggregates.SkillSessionStats stats, String method, Map<Integer, Long> produced, Map<String, Long> methodMillis, boolean bound)
	{
		if (def.productionIsReward())
		{
			model.rewards = buildRewards(def, method, produced, methodMillis);
		}
		String unit = def.cycleNoun(true);
		if (unit == null || method == null)
		{
			return model;
		}
		String unitUpper = unit.toUpperCase(Locale.ROOT);
		String singleUpper = def.cycleNoun(false).toUpperCase(Locale.ROOT);

		SkillCompanionView.CycleView c = view.getCycle();
		// CURRENT cycle only for a skill that names its route progress (a cycle skill without one has SESSION
		// cycles only; never a null caption).
		if (c != null && method.equals(c.getMethodKey()) && def.cycleProgressLabel(method) != null)
		{
			String progress = c.getDenominator() == null ? fmt(c.getProgress()) : fmt(c.getProgress()) + " / " + fmt(c.getDenominator());
			String time;
			String tip;
			if (c.getElapsedMillis() != null)
			{
				time = CompletionMetricText.gameTime(c.getElapsedMillis() / 1000);
				tip = "This " + def.cycleNoun(false) + " so far, from its first " + def.cycleProgressLabel(method).toLowerCase(Locale.ROOT);
			}
			else
			{
				time = null;
				tip = c.isSynced() ? "Starts at the first " + def.cycleProgressLabel(method).toLowerCase(Locale.ROOT)
					: "Joined part-way: this " + def.cycleNoun(false) + " is not counted -- the next full one is";
			}
			model.cycle = new Cycle(def.cycleProgressLabel(method), progress, singleUpper + " TIME", time, tip, c.isSynced());
		}

		SkillCycleStats session = cycleStats(stats, method);
		SkillCycleStats merged = bound
			? SkillCycleStats.ahead(session, view.getFlushedCycles().get(method)).plus(view.getPendingCycles().get(method))
			: session.plus(view.getPendingCycles().get(method));
		Long millis = methodMillis.get(method);
		String rate = null;
		if (merged.getCount() > 0 && millis != null && millis >= MIN_RATE_MILLIS)
		{
			rate = String.format(Locale.ROOT, "%.1f", merged.getCount() * 3_600_000d / millis);
		}
		Long avg = merged.averageMillis();
		String lastCaption = "BEST " + singleUpper;
		String lastText = merged.getBestMillis() <= 0 ? null : CompletionMetricText.gameTime(merged.getBestMillis() / 1000);
		if (!def.cycleYields().isEmpty())
		{
			// A per-cycle yield (RUNES / TRIP) over the COUNTED cycles only, in place of BEST.
			SkillCompanionDefinition.CycleYield y = def.cycleYields().get(0);
			Long sum = occurrences(stats, view, bound).get(y.getKeyPrefix() + method);
			lastCaption = y.getCaption();
			lastText = sum == null || merged.getCount() <= 0 ? null : String.format(Locale.ROOT, "%,.1f", sum / (double) merged.getCount());
		}
		model.cycleSession = new CycleSession(unitUpper, merged.getCount(), unitUpper + " / HR", rate, "AVG " + singleUpper,
			avg == null ? null : CompletionMetricText.gameTime(avg / 1000), lastCaption, lastText, merged.getFailures());
		if (merged.getFailures() > 0 && def.failureNoun(true) != null)
		{
			List<SkillCompanionView.ContextLine> ctx = new ArrayList<>(model.context);
			ctx.add(new SkillCompanionView.ContextLine(fmt(merged.getFailures()) + " " + def.failureNoun(merged.getFailures() != 1)
				+ " this session", SkillCompanionView.ContextLine.Tone.QUIET,
				def.failureTooltip()));
			return new SkillCompanionModel(model, ctx);
		}
		return model;
	}

	private static SkillCycleStats cycleStats(SessionAggregates.SkillSessionStats stats, String method)
	{
		if (stats == null || stats.getCycles() == null || method == null)
		{
			return SkillCycleStats.NONE;
		}
		SessionAggregates.CycleAggregate a = stats.getCycles().get(method);
		return a == null ? SkillCycleStats.NONE : new SkillCycleStats(a.getCount(), a.getTotalMillis(), a.getBestMillis(), a.getFailures());
	}

	/** The method's own reward: collected this session, per hour of time on every method that hands it out. */
	private static Rewards buildRewards(SkillCompanionDefinition def, String method, Map<Integer, Long> produced, Map<String, Long> methodMillis)
	{
		List<SkillResource> rewards = method == null ? Collections.<SkillResource>emptyList() : def.stockpileResources(method);
		if (rewards.isEmpty())
		{
			return null;
		}
		SkillResource r = rewards.get(0);
		long count = produced.getOrDefault(r.getItemId(), 0L);
		long millis = 0L;
		for (Map.Entry<String, Long> e : methodMillis.entrySet())
		{
			for (SkillResource other : def.stockpileResources(e.getKey()))
			{
				if (other.getItemId() == r.getItemId() && e.getValue() != null)
				{
					millis += e.getValue();
					break;
				}
			}
		}
		String noun = def.rateNoun(method);
		String rate = count >= MIN_REWARD_RATE_COUNT && millis >= MIN_REWARD_RATE_MILLIS
			? String.format(Locale.ROOT, "%.1f", count * 3_600_000d / millis) : null;
		return new Rewards(def.productionCaption(method, Collections.singletonList(r)), count,
			(noun == null ? "" : noun.toUpperCase(Locale.ROOT)) + " / HR", rate,
			"Collected per hour of time on " + def.rewardSourcesNoun() + " that hand out " + r.getName().toLowerCase(Locale.ROOT)
				+ " (shown after " + MIN_REWARD_RATE_COUNT + " and 10 minutes; never a theoretical rate)");
	}

	private static Long xpPerHour(CurrentSessionSnapshot snapshot, SkillCompanionDefinition def)
	{
		for (CurrentSessionSnapshot.XpEntry e : snapshot.getXpEntries())
		{
			if (def.getSkill().name().equalsIgnoreCase(e.getSkill()))
			{
				return e.getXpPerHour();
			}
		}
		return null;
	}

	// ---- CURRENT TRIP ------------------------------------------------------------------------

	private static Trip buildTrip(SkillCompanionDefinition def, SkillCompanionView view, String method, Map<Integer, Long> produced)
	{
		SkillCompanionView.TripView t = view.getTrip();
		if (t == null || t.getSpaceLeft() == null || !def.tripMatters(method))
		{
			return null;
		}
		String est = t.getEstFullMillis() == null ? null : CompletionMetricText.gameTime(Math.max(0L, (t.getEstFullMillis() + 999) / 1000));
		if (t.getSpaceLeft() == 0)
		{
			est = "Full";
		}
		else if (est != null)
		{
			est = "~" + est;
		}
		String tooltip;
		if (t.getSpaceLeft() == 0)
		{
			tooltip = "Your inventory is full";
		}
		else if (t.getEstFullMillis() != null && t.isEstIncludesStorage())
		{
			tooltip = "Until your inventory and your open " + lower(t.getStorageName()) + " are both full, at this trip's observed rate";
		}
		else if (t.getEstFullMillis() != null)
		{
			tooltip = "Until your inventory is full at this trip's observed rate (the slots your last successes actually used)";
		}
		else if (t.isStorageCatching() && def.storageCatchesMain(method))
		{
			tooltip = "Your open " + lower(t.getStorageName()) + " is catching the " + def.rateNoun(method)
				+ ", so the inventory is not filling";
		}
		else
		{
			tooltip = "Appears after a few successes this trip (no rate is ever assumed)";
		}

		String storage = null;
		if (t.getStorageName() != null && def.storageRelevant(method, produced.keySet()))
		{
			if (t.getStorageUsed() != null)
			{
				storage = t.getStorageName() + " " + fmt(t.getStorageUsed())
					+ (t.getStorageCapacity() == null ? "" : " / " + fmt(t.getStorageCapacity()));
			}
			else if (t.getStorageAddedThisTrip() > 0)
			{
				storage = t.getStorageName() + " +" + fmt(t.getStorageAddedThisTrip()) + " this trip";
			}
		}
		return new Trip(t.getSpaceLeft(), est, tooltip, storage);
	}

	// ---- PRODUCTION --------------------------------------------------------------------------

	private static Production buildProduction(SkillCompanionDefinition def, String method, Map<Integer, Long> produced,
		Map<Integer, Long> banked, Map<String, Long> methodMillis, long sessionActiveMillis)
	{
		if (produced.isEmpty())
		{
			return null;
		}
		List<SkillResource> main = new ArrayList<>();
		Map<String, List<SkillResource>> byGroup = new LinkedHashMap<>();
		List<SkillResource> other = new ArrayList<>();
		java.util.Set<String> ownSections = new java.util.HashSet<>();
		for (SkillCompanionDefinition.Secondary sec : def.secondaries())
		{
			ownSections.add(sec.getGroup());
		}
		boolean followsMethod = def.productionFollowsMethod() && method != null;
		List<SkillResource> earlier = new ArrayList<>();
		for (Integer id : produced.keySet())
		{
			SkillResource r = def.resource(id);
			if (r == null || (r.getGroup() != null && ownSections.contains(r.getGroup())))
			{
				continue;
			}
			if (followsMethod && !def.methodProduces(method, id))
			{
				// Another method's output this session: its own compact line, never blended into this one.
				earlier.add(r);
				continue;
			}
			boolean groupMain = r.getGroup() != null && def.groupIsPrimary(method, r.getGroup());
			if (groupMain || (r.getRole() == SkillResource.Role.PRIMARY && r.getGroup() == null))
			{
				main.add(r);
			}
			else if (r.getGroup() != null)
			{
				byGroup.computeIfAbsent(r.getGroup(), k -> new ArrayList<>()).add(r);
			}
			else
			{
				other.add(r);
			}
		}
		sortByQuantity(main, produced);

		List<String> columns = def.productionColumns(method);
		boolean rows = columns != null && !columns.isEmpty();
		boolean showBanked = def.productionShowsBanked(method);
		List<SkillCompanionView.ItemGroup> groups = new ArrayList<>();
		String caption = null;
		long total = 0L;
		long bankedMain = 0L;
		SkillCompanionView.ItemGroup mainItems = null;
		if (!main.isEmpty())
		{
			for (SkillResource r : main)
			{
				total += produced.getOrDefault(r.getItemId(), 0L);
				bankedMain += banked.getOrDefault(r.getItemId(), 0L);
			}
			boolean grouped = main.get(0).getGroup() != null;
			caption = def.productionCaption(method, main);
			if (rows || main.size() > 1 || grouped || def.productionSprites(method))
			{
				// Every main resource as its own sprite + exact number (never shares, never "+N more").
				mainItems = new SkillCompanionView.ItemGroup(main.size() > 1 ? def.productionItemsTitle(method) : null,
					rows ? columns : null, stats(def, main, produced, banked, rows));
			}
		}

		for (Map.Entry<String, List<SkillResource>> g : byGroup.entrySet())
		{
			sortByQuantity(g.getValue(), produced);
			groups.add(new SkillCompanionView.ItemGroup(def.groupTitle(g.getKey()), rows ? columns : null,
				stats(def, g.getValue(), produced, banked, rows)));
		}
		if (!other.isEmpty())
		{
			sortByQuantity(other, produced);
			groups.add(new SkillCompanionView.ItemGroup("By-products", rows ? columns : null, stats(def, other, produced, banked, rows)));
		}
		if (!earlier.isEmpty())
		{
			sortByQuantity(earlier, produced);
			List<SkillCompanionView.ItemStat> also = stats(def, earlier, produced, banked, rows);
			if (!also.isEmpty())
			{
				groups.add(new SkillCompanionView.ItemGroup("Also made", rows ? columns : null, also));
			}
		}
		UnitFamily family = commonFamily(def, main);
		if (family != null)
		{
			Production p = unitProduction(def, method, family, main, produced, banked, methodMillis, caption, groups);
			p.showBanked = showBanked;
			return p;
		}

		String rateText = null;
		String rateTooltip = null;
		if (!main.isEmpty())
		{
			// The current method's own rate when the main cell is that method's product; otherwise
			// (different main resources mixed) everything per hour of the whole session.
			Long millis = method == null ? null : methodMillis.get(method);
			boolean single = main.size() == 1 || def.groupIsPrimary(method, main.get(0).getGroup());
			if (!single && method != null)
			{
				// One multi-output method (fly fishing: trout AND salmon) is still that method's own rate.
				single = true;
				for (SkillResource r : main)
				{
					single &= def.methodProduces(method, r.getItemId());
				}
			}
			String noun = def.rateNoun(method);
			if (single && millis != null && millis >= MIN_RATE_MILLIS)
			{
				long perHour = Math.round(total * 3_600_000d / millis);
				rateText = fmt(perHour) + (noun == null ? "" : " " + noun) + "/hr";
				rateTooltip = "Per hour of " + def.methodLabel(method) + " time this session (respawns, walking and banking count; "
					+ "long idles and logged-out time never do)";
			}
			else if (!single && sessionActiveMillis >= MIN_RATE_MILLIS)
			{
				long perHour = Math.round(total * 3_600_000d / sessionActiveMillis);
				rateText = fmt(perHour) + "/hr";
				rateTooltip = "Every main resource per hour of active session time (logged-out time never counts)";
			}
		}
		if (caption == null && groups.isEmpty())
		{
			return null;
		}
		boolean showCells = caption != null && (!rows || main.size() > 1) && !def.productionSprites(method);
		if (!def.productionRateLine())
		{
			rateText = null;
			rateTooltip = null;
		}
		Production p = new Production(caption, total, bankedMain, rateText, rateTooltip, groups);
		p.showBanked = showBanked;
		// Per-resource rows already carry one resource's produced and banked numbers; the blended cell only when resources mix.
		p.showCells = showCells;
		p.mainItems = mainItems;
		return p;
	}

	/** The one unit family every main resource belongs to; null when any is item-counted or families differ. */
	static UnitFamily commonFamily(SkillCompanionDefinition def, List<SkillResource> main)
	{
		UnitFamily family = null;
		for (SkillResource r : main)
		{
			UnitFamily f = def.unitFamily(r);
			if (f == null || (family != null && !family.getKey().equals(f.getKey())))
			{
				return null;
			}
			family = f;
		}
		return family;
	}

	/**
	 * PRODUCTION of one unit family (Phase 6): counted in units ("1,086
	 * units"), banked in units, and the method's own units per hour -- a mix
	 * of variants is one family, never a "TOTAL" of unrelated items. The
	 * family's one-unit sprite carries the number (never a vial-by-vial
	 * breakdown in prose).
	 */
	private static Production unitProduction(SkillCompanionDefinition def, String method, UnitFamily family, List<SkillResource> main,
		Map<Integer, Long> produced, Map<Integer, Long> banked, Map<String, Long> methodMillis, String caption,
		List<SkillCompanionView.ItemGroup> groups)
	{
		long units = 0L;
		long bankedUnits = 0L;
		for (SkillResource r : main)
		{
			units += produced.getOrDefault(r.getItemId(), 0L) * family.unitsOf(r.getItemId());
			bankedUnits += banked.getOrDefault(r.getItemId(), 0L) * family.unitsOf(r.getItemId());
		}
		String rateText = null;
		String rateTooltip = null;
		Long millis = method == null ? null : methodMillis.get(method);
		if (millis != null && millis >= MIN_RATE_MILLIS)
		{
			rateText = fmt(Math.round(units * 3_600_000d / millis)) + " " + family.unitPlural() + "/hr";
			rateTooltip = "Per hour of " + def.methodLabel(method) + " time this session (banking counts; long idles and logged-out time never do)";
		}
		Production p = new Production(caption, units, bankedUnits, rateText, rateTooltip, groups);
		p.totalText = family.unitsText(units);
		// The variants' own sprites would only re-split one family: the family's one-unit sprite carries the number instead.
		p.mainItems = new SkillCompanionView.ItemGroup(null, null, Collections.singletonList(SkillCompanionView.ItemStat.line(
			oneUnitItem(family), family.getName(), family.unitsText(units),
			family.getName() + ": " + family.unitsText(units) + (units >= family.getFullUnits() ? " (" + family.equivalentText(units) + ")" : ""))));
		p.showCells = false;
		return p;
	}

	/** The family member that holds exactly one unit (its smallest variant); the canonical item when none does. */
	static int oneUnitItem(UnitFamily family)
	{
		for (Map.Entry<Integer, Integer> e : family.getUnitsPerItem().entrySet())
		{
			if (e.getValue() == 1)
			{
				return e.getKey();
			}
		}
		return family.getCanonicalItemId();
	}

	/**
	 * Sprites + exact numbers for resources (in the given order): a unit
	 * family is ONE stat of its one-unit sprite counted in units; rows add
	 * BANKED. Unnamed ids (-1) are kept by name with no sprite.
	 */
	static List<SkillCompanionView.ItemStat> stats(SkillCompanionDefinition def, List<SkillResource> resources, Map<Integer, Long> produced,
		Map<Integer, Long> banked, boolean rows)
	{
		Map<String, long[]> familyUnits = new LinkedHashMap<>();
		Map<String, UnitFamily> families = new LinkedHashMap<>();
		List<SkillCompanionView.ItemStat> out = new ArrayList<>();
		List<Object> order = new ArrayList<>();
		for (SkillResource r : resources)
		{
			long q = produced.getOrDefault(r.getItemId(), 0L);
			if (q <= 0)
			{
				continue;
			}
			UnitFamily f = def.unitFamily(r);
			if (f != null)
			{
				long[] u = familyUnits.get(f.getKey());
				if (u == null)
				{
					u = new long[2];
					familyUnits.put(f.getKey(), u);
					families.put(f.getKey(), f);
					order.add(f);
				}
				u[0] += q * f.unitsOf(r.getItemId());
				u[1] += banked.getOrDefault(r.getItemId(), 0L) * f.unitsOf(r.getItemId());
				continue;
			}
			order.add(r);
		}
		for (Object o : order)
		{
			if (o instanceof UnitFamily)
			{
				UnitFamily f = (UnitFamily) o;
				long[] u = familyUnits.get(f.getKey());
				out.add(stat(oneUnitItem(f), f.getName(), u[0], u[1], rows, f.getName() + ": " + f.unitsText(u[0])
					+ (rows ? " • " + f.unitsText(u[1]) + " banked" : "")));
				continue;
			}
			SkillResource r = (SkillResource) o;
			long q = produced.getOrDefault(r.getItemId(), 0L);
			long b = banked.getOrDefault(r.getItemId(), 0L);
			out.add(stat(r.getItemId(), r.getName(), q, b, rows, r.getName() + ": " + fmt(q) + (rows ? " • " + fmt(b) + " banked" : "")));
		}
		return out;
	}

	private static SkillCompanionView.ItemStat stat(int itemId, String name, long produced, long banked, boolean rows, String tooltip)
	{
		List<Long> values = new ArrayList<>();
		values.add(produced);
		if (rows)
		{
			values.add(banked);
		}
		return new SkillCompanionView.ItemStat(itemId, name, values, null, tooltip);
	}

	private static void sortByQuantity(List<SkillResource> list, Map<Integer, Long> produced)
	{
		list.sort((a, b) -> Long.compare(produced.getOrDefault(b.getItemId(), 0L), produced.getOrDefault(a.getItemId(), 0L)));
	}

	// ---- ADDED (Skills Phase 4): auto-transformed, focus, event, secondary ------------

	/** Production with its auto-transformed part; unchanged when the skill has none. */
	private static Production withAutoTransformed(Production production, SkillCompanionDefinition def,
		SessionAggregates.SkillSessionStats stats, SkillCompanionView view, boolean bound)
	{
		if (production == null || def.autoTransformedVerb() == null)
		{
			return production;
		}
		Map<Integer, Long> session = new LinkedHashMap<>();
		if (stats != null && stats.getAutoTransformed() != null)
		{
			for (SessionAggregates.ProducedItemAggregate a : stats.getAutoTransformed())
			{
				session.merge(a.getItemId() == null ? -1 : a.getItemId(), a.getQuantity(), Long::sum);
			}
		}
		Map<Integer, Long> merged = SkillProductionMerge.merge(session, view.getFlushedAutoTransformed(), view.getPendingAutoTransformed(), bound);
		long total = 0L;
		for (long v : merged.values())
		{
			total += v;
		}
		if (total <= 0)
		{
			return production;
		}
		Production p = copy(production);
		p.autoTransformedLine = fmt(total) + " " + def.autoTransformedVerb();
		return p;
	}

	private static Production copy(Production production)
	{
		Production p = new Production(production.caption, production.total, production.banked, production.rateText, production.rateTooltip,
			production.itemGroups);
		p.showCells = production.showCells;
		p.showBanked = production.showBanked;
		p.mainItems = production.mainItems;
		p.autoTransformedLine = production.autoTransformedLine;
		p.consumedLine = production.consumedLine;
		p.otherInputs = production.otherInputs;
		p.occurrenceLines = production.occurrenceLines;
		p.totalText = production.totalText;
		return p;
	}

	/** The session's contextual occurrences: persisted + sent + pending (the shared merge rule). */
	static Map<String, Long> occurrences(SessionAggregates.SkillSessionStats stats, SkillCompanionView view, boolean bound)
	{
		Map<String, Long> session = stats == null || stats.getOccurrences() == null ? Collections.<String, Long>emptyMap() : stats.getOccurrences();
		return SkillProductionMerge.merge(session, view.getFlushedOccurrences(), view.getPendingOccurrences(), bound);
	}

	/** Production with its consumed inputs ("756 essence used"); unchanged when the skill consumes nothing. */
	private static Production withConsumed(Production production, SkillCompanionDefinition def, SessionAggregates.SkillSessionStats stats,
		SkillCompanionView view, boolean bound)
	{
		if (production == null || def.consumedPhrase() == null)
		{
			return production;
		}
		Map<Integer, Long> session = new LinkedHashMap<>();
		if (stats != null && stats.getConsumed() != null)
		{
			for (SessionAggregates.ProducedItemAggregate a : stats.getConsumed())
			{
				session.merge(a.getItemId() == null ? -1 : a.getItemId(), a.getQuantity(), Long::sum);
			}
		}
		Map<Integer, Long> merged = SkillProductionMerge.merge(session, view.getFlushedConsumed(), view.getPendingConsumed(), bound);
		long total = 0L;
		List<SkillCompanionView.ItemStat> others = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : merged.entrySet())
		{
			if (def.isPrimaryInput(e.getKey()))
			{
				total += e.getValue();
			}
			else if (e.getValue() > 0 && def.inputName(e.getKey()) != null)
			{
				others.add(SkillCompanionView.ItemStat.of(e.getKey(), def.inputName(e.getKey()), e.getValue(),
					def.inputName(e.getKey()) + ": " + fmt(e.getValue()) + " used"));
			}
		}
		if (total <= 0 && others.isEmpty())
		{
			return production;
		}
		others.sort((a, b) -> Long.compare(b.getValues().get(0), a.getValues().get(0)));
		Production p = copy(production);
		p.consumedLine = total > 0 ? fmt(total) + " " + def.consumedPhrase() : null;
		p.otherInputs = others.isEmpty() ? null : new SkillCompanionView.ItemGroup("Also used", null, others);
		return p;
	}

	private static void addExtras(SkillCompanionModel model, SkillCompanionDefinition def, SkillCompanionView view,
		SessionAggregates.SkillSessionStats stats, String method, Map<Integer, Long> produced, Map<String, Long> methodMillis, boolean bound)
	{
		SkillCompanionView.FocusView f = view.getFocus();
		if (f != null && def.focusTitle() != null)
		{
			model.focus = new Focus(def.focusTitle(), f.getLabel(), def.focusCountCaption(method), f.getProduced(), def.focusTimeCaption(),
				f.getElapsedMillis() == null ? null : CompletionMetricText.gameTime(f.getElapsedMillis() / 1000));
			model.focus.note = f.getNote();
			model.focus.noteTooltip = f.getNoteTooltip();
		}
		SkillCompanionView.EventView e = view.getEvent();
		if (e != null)
		{
			model.event = new Event(e.getName(), CompletionMetricText.gameTime(e.getElapsedMillis() / 1000), e.getFields(), e.getLines());
			model.event.title = e.getTitle();
			model.event.itemGroups = e.getItemGroups();
		}
		SkillCompanionView.EventView st = view.getStock();
		if (st != null)
		{
			model.stock = new Event(st.getName(), null, st.getFields(), st.getLines());
			model.stock.title = st.getTitle();
			model.stock.itemGroups = st.getItemGroups();
		}
		if (def.secondaries().isEmpty())
		{
			return;
		}
		long totalMillis = 0L;
		for (Long v : methodMillis.values())
		{
			totalMillis += v == null ? 0L : v;
		}
		List<Secondary.Cell> cells = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		List<SkillCompanionView.ItemGroup> groups = new ArrayList<>();
		for (SkillCompanionDefinition.Secondary sec : def.secondaries())
		{
			List<SkillResource> family = new ArrayList<>();
			long count = 0L;
			for (Map.Entry<Integer, Long> p : produced.entrySet())
			{
				SkillResource r = def.resource(p.getKey());
				if (r != null && sec.getGroup().equals(r.getGroup()) && p.getValue() > 0)
				{
					family.add(r);
					count += p.getValue();
				}
			}
			if (count <= 0)
			{
				continue;
			}
			if (sec.isCompactLine())
			{
				sortByQuantity(family, produced);
				groups.add(new SkillCompanionView.ItemGroup(sec.getCaption(), null,
					stats(def, family, produced, Collections.<Integer, Long>emptyMap(), false)));
				continue;
			}
			String rate = null;
			if (count >= MIN_REWARD_RATE_COUNT && totalMillis >= MIN_REWARD_RATE_MILLIS)
			{
				double perHour = count * 3_600_000d / totalMillis;
				rate = perHour >= 100 ? fmt(Math.round(perHour)) : String.format(Locale.ROOT, "%.1f", perHour);
			}
			cells.add(new Secondary.Cell(sec.getCaption(), fmt(count), sec.getTooltip()));
			cells.add(new Secondary.Cell(sec.getRateCaption(), rate,
				"Per hour of " + def.getSkill().getName() + " time this session (shown after " + MIN_REWARD_RATE_COUNT
					+ " and 10 minutes; never a theoretical rate)"));
		}
		if (def.occurrencesTitle() != null)
		{
			Map<String, Long> session = stats == null || stats.getOccurrences() == null ? Collections.<String, Long>emptyMap()
				: stats.getOccurrences();
			Map<String, Long> occ = SkillProductionMerge.merge(session, view.getFlushedOccurrences(), view.getPendingOccurrences(), bound);
			long n = 0L;
			for (long v : occ.values())
			{
				n += v;
			}
			if (n > 0)
			{
				lines.add(def.occurrencesTitle() + ": " + fmt(n));
			}
		}
		if (!cells.isEmpty() || !lines.isEmpty() || !groups.isEmpty())
		{
			model.secondary = new Secondary(cells, lines, groups);
		}
	}

	/** SESSION actions and INPUTS USED (Phase 6); both absent for every skill whose definition offers neither. */
	private static void addActionsAndInputs(SkillCompanionModel model, SkillCompanionDefinition def, SkillCompanionView view,
		SessionAggregates.SkillSessionStats stats, String method, Map<String, Long> methodMillis, boolean bound)
	{
		if (method == null)
		{
			return;
		}
		Map<String, Long> occ = null;
		String prefix = def.actionKeyPrefix();
		String caption = def.actionCaption(method);
		if (prefix != null && caption != null)
		{
			occ = occurrences(stats, view, bound);
			// The definition decides which methods the count spans (by default the shown method alone).
			java.util.Set<String> sessionMethods = new java.util.LinkedHashSet<>();
			for (String k : occ.keySet())
			{
				if (k.startsWith(prefix))
				{
					sessionMethods.add(k.substring(prefix.length()));
				}
			}
			long n = 0L;
			long millisSum = 0L;
			boolean millisKnown = false;
			for (String m : def.actionMethods(method, sessionMethods))
			{
				Long c = occ.get(prefix + m);
				if (c != null && c > 0)
				{
					n += c;
					Long ms = methodMillis.get(m);
					if (ms != null)
					{
						millisSum += ms;
						millisKnown = true;
					}
				}
			}
			if (n > 0)
			{
				String rate = millisKnown && millisSum >= def.actionRateMinMillis(method) ? fmt(Math.round(n * 3_600_000d / millisSum)) : null;
				String rateCaption = def.actionRateCaption(method);
				model.actions = new ActionSession(caption, n, rateCaption != null ? rateCaption : caption + " / HR", rate);
			}
		}
		Map<Integer, Long> session = new LinkedHashMap<>();
		if (stats != null && stats.getConsumed() != null)
		{
			for (SessionAggregates.ProducedItemAggregate a : stats.getConsumed())
			{
				session.merge(a.getItemId() == null ? -1 : a.getItemId(), a.getQuantity(), Long::sum);
			}
		}
		Map<Integer, Long> consumed = SkillProductionMerge.merge(session, view.getFlushedConsumed(), view.getPendingConsumed(), bound);
		if (occ == null)
		{
			occ = occurrences(stats, view, bound);
		}
		List<SkillCompanionView.EventView.Field> fields = def.inputFields(method, consumed, occ);
		List<SkillCompanionView.ItemStat> items = def.inputItems(method, occ);
		if (!fields.isEmpty() || !items.isEmpty())
		{
			model.inputs = new Inputs(fields, def.inputLines(method, occ));
			model.inputs.items = items;
			model.inputs.title = def.inputsTitle(method);
		}
		if (def.actionRateInProduction(method) && model.actions != null && model.production != null)
		{
			// One action = one item made: the production sprite already shows the total, so only the rate is added (never twice).
			Production p = new Production(model.production.caption, model.production.total, model.production.banked,
				model.actions.rateText == null ? null : model.actions.rateText + " " + model.actions.caption.toLowerCase(Locale.ROOT) + "/hr",
				"Per hour of time on this " + def.methodTitle().toLowerCase(Locale.ROOT) + " (banking counts, long idles never do)",
				model.production.itemGroups);
			p.showCells = model.production.showCells;
			p.showBanked = model.production.showBanked;
			p.mainItems = model.production.mainItems;
			p.autoTransformedLine = model.production.autoTransformedLine;
			p.consumedLine = model.production.consumedLine;
			p.otherInputs = model.production.otherInputs;
			p.occurrenceLines = model.production.occurrenceLines;
			p.totalText = model.production.totalText;
			model.production = p;
			model.actions = null;
		}
	}

	// ---- TARGET --------------------------------------------------------------------------------

	private static Target buildTarget(SkillCompanionDefinition def, SkillGoal goal, String method, Map<Integer, Long> produced,
		Integer skillXp, Long xpPerHour, IntFunction<Long> bankQuantity, Map<Integer, SkillGoalPlanner.BankingSample> banking,
		java.util.function.Function<SkillResource, SkillExternalStock> externalStock)
	{
		SkillGoal g = goal == null ? SkillGoal.DEFAULT : goal;
		if (g.getFamily() != SkillGoal.Family.LEVEL && !def.offersResourceTarget())
		{
			// A saved resource target of a skill that no longer offers one reads as its Level target (the saved level is kept).
			g = g.withFamily(SkillGoal.Family.LEVEL);
		}
		if (g.getFamily() == SkillGoal.Family.LEVEL)
		{
			SkillGoalPlanner.LevelPlan plan = SkillGoalPlanner.level(g.getTargetLevel(), skillXp, xpPerHour);
			return new Target(g, null, Collections.<SkillResource>emptyList(), plan.getXpLeft(), null, plan.getEtaMillis(), plan.isReached());
		}
		List<SkillResource> choices = new ArrayList<>(def.stockpileResources(method));
		if (choices.isEmpty())
		{
			// No method yet: what this session produced is still a fair choice.
			for (Integer id : produced.keySet())
			{
				SkillResource r = def.resource(id);
				if (r != null && id >= 0)
				{
					choices.add(r);
				}
			}
		}
		SkillResource resource = g.getResourceItemId() == null ? null : def.resource(g.getResourceItemId());
		if (resource != null && !contains(choices, resource))
		{
			choices.add(0, resource);
		}
		if (resource == null && !choices.isEmpty())
		{
			resource = choices.get(0);
		}
		if (resource == null)
		{
			return new Target(g, null, choices, null, null, null, false);
		}
		UnitFamily family = def.unitFamily(resource);
		SkillExternalStock ext = externalStock == null ? SkillExternalStock.NOT_APPLICABLE : externalStock.apply(resource);
		if (ext == null)
		{
			ext = SkillExternalStock.NOT_APPLICABLE;
		}
		if (family != null || ext.applies())
		{
			return ownedTarget(g, resource, choices, family, ext, bankQuantity, banking);
		}
		Long stock = bankQuantity == null ? null : bankQuantity.apply(resource.getItemId());
		SkillGoalPlanner.StockpilePlan plan = SkillGoalPlanner.stockpile(g.getTargetQuantity(), stock, banking.get(resource.getItemId()));
		return new Target(g, resource, choices, plan.getStock(), plan.getRemaining(), plan.getEtaMillis(), plan.isReached());
	}

	/**
	 * STOCKPILE of a unit family and / or a resource that also lives in an
	 * account storage outside the bank (Phase 6). OWNED = the bank's units
	 * (every variant, Spyglass's own bank view) + the storage's units -- two
	 * different places, so never the same physical item twice; the inventory is
	 * never added (a saved bank may already hold it). An UNKNOWN storage is
	 * never zero: OWNED becomes a lower bound, REMAINING an upper bound, and no
	 * ETA is claimed. The ETA uses the units that actually reached the bank or
	 * the storage this session (after the shared minimum deposits and time);
	 * production per hour is never used instead.
	 */
	private static Target ownedTarget(SkillGoal g, SkillResource resource, List<SkillResource> choices, UnitFamily family, SkillExternalStock ext,
		IntFunction<Long> bankQuantity, Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		Map<Integer, Integer> members = family != null ? family.getUnitsPerItem()
			: Collections.singletonMap(resource.getItemId(), 1);
		int full = family != null ? family.getFullUnits() : 1;
		Long bankUnits = 0L;
		long sampleUnits = 0L;
		int sampleDeposits = 0;
		long sampleAt = 0L;
		for (Map.Entry<Integer, Integer> m : members.entrySet())
		{
			Long q = bankQuantity == null ? null : bankQuantity.apply(m.getKey());
			if (q == null)
			{
				bankUnits = null;
			}
			else if (bankUnits != null)
			{
				bankUnits += q * m.getValue();
			}
			SkillGoalPlanner.BankingSample b = banking.get(m.getKey());
			if (b != null)
			{
				sampleUnits += b.getQuantity() * m.getValue();
				sampleDeposits = Math.max(sampleDeposits, b.getDeposits());
				sampleAt = Math.max(sampleAt, b.getActiveMillisAtLastDeposit());
			}
		}
		long targetUnits = (long) g.getTargetQuantity() * full;
		boolean storageUnknown = ext.applies() && !ext.isKnown();
		Long owned = bankUnits == null ? null : bankUnits + (ext.isKnown() ? ext.getQuantity() : 0L);
		Long remaining = owned == null ? null : Math.max(0L, targetUnits - owned);
		boolean reached = remaining != null && remaining == 0;
		Long eta = null;
		if (remaining != null && !storageUnknown && !reached)
		{
			Double perHour = new SkillGoalPlanner.BankingSample(sampleUnits, sampleDeposits, sampleAt).perHour();
			eta = perHour == null || perHour <= 0 ? null : (long) Math.ceil(remaining / perHour * 3_600_000d);
		}
		else if (reached)
		{
			eta = 0L;
		}
		Target t = new Target(g, resource, choices, owned, remaining, eta, reached);
		t.firstCaption = ext.applies() ? "OWNED" : "IN BANK";
		String where = ext.applies() ? "your bank and " + ext.getStorageName() : "your bank";
		if (owned != null)
		{
			String bound = storageUnknown ? "\u2265 " : "";
			long ownedFull = family != null ? family.fullItems(owned) : owned;
			t.firstText = bound + compact(family != null ? family.fullText(ownedFull) : fmt(ownedFull), ownedFull);
			t.firstTooltip = "What " + where + " hold" + (ext.applies() ? "" : "s") + " now"
				+ (family != null ? " (" + family.unitsText(owned) + " over every variant)" : "")
				+ (storageUnknown ? " -- " + ext.getStorageName() + " not seen yet, so this is a lower bound" : "");
			long remainingFull = family != null ? (remaining + full - 1) / full : remaining;
			t.remainingText = reached ? "Done" : (storageUnknown ? "\u2264 " : "")
				+ compact(family != null ? family.fullText(remainingFull) : fmt(remainingFull), remainingFull);
		}
		else
		{
			t.firstTooltip = "Open your bank once so Spyglass can see what you already have";
		}
		t.quantityText = family != null ? family.fullText(g.getTargetQuantity()) : fmt(g.getTargetQuantity());
		if (ext.applies())
		{
			t.ownershipLine = storageUnknown ? ext.getStorageName() + " not seen yet: open your bank" : "Bank + " + ext.getStorageName();
			t.ownershipAlert = storageUnknown;
		}
		t.promptNoun = resource.getShortName() + (family != null ? " (as " + family.getFullLabel() + ")" : "") + " in " + where;
		return t;
	}

	/**
	 * A resource target that grows while training (Agility's marks of grace,
	 * crystal shards, termites): OWNED = what you carry + what the bank holds
	 * (each known, or OWNED is unknown -- never zero), REMAINING, and an ETA
	 * from this session's observed rate of that resource on this method only
	 * (after 5 minutes and at least two of it; never a wiki rate).
	 */
	private static Target carriedTarget(Target base, SkillCompanionDefinition def, String method, Map<Integer, Long> produced,
		IntFunction<Long> bankQuantity, Map<Integer, Long> carried, Map<String, Long> methodMillis)
	{
		SkillGoal g = base.goal;
		SkillResource resource = base.resource;
		Long bank = bankQuantity == null ? null : bankQuantity.apply(resource.getItemId());
		Long inv = carried == null ? null : carried.getOrDefault(resource.getItemId(), 0L);
		Long owned = bank == null || inv == null ? null : bank + inv;
		Long remaining = owned == null ? null : Math.max(0L, g.getTargetQuantity() - owned);
		boolean reached = remaining != null && remaining == 0;
		Long eta = reached ? Long.valueOf(0L) : null;
		long earned = produced.getOrDefault(resource.getItemId(), 0L);
		Long millis = method == null ? null : methodMillis.get(method);
		if (!reached && remaining != null && earned >= 2 && millis != null && millis >= MIN_RATE_MILLIS)
		{
			eta = (long) Math.ceil(remaining * (double) millis / earned);
		}
		Target t = new Target(g, resource, base.choices, owned, remaining, eta, reached);
		t.firstCaption = "OWNED";
		t.firstText = owned == null ? null : fmt(owned);
		t.firstTooltip = owned == null
			? (bank == null ? "Open your bank once so Spyglass can see what you already have" : "Your inventory has not been read yet")
			: fmt(inv) + " carried + " + fmt(bank) + " in your bank";
		t.remainingText = owned == null ? null : reached ? "Done" : fmt(remaining);
		t.quantityText = fmt(g.getTargetQuantity());
		t.promptNoun = resource.getName().toLowerCase(Locale.ROOT) + " (inventory and bank)";
		t.growsWithProduction = true;
		return t;
	}

	/** Longest value text a narrow target cell carries with its unit; past that the bare number (the unit is on the target line). */
	static final int MAX_VALUE_TEXT = 14;

	private static String compact(String withUnit, long number)
	{
		return withUnit.length() > MAX_VALUE_TEXT ? fmt(number) : withUnit;
	}

	private static boolean contains(List<SkillResource> list, SkillResource r)
	{
		for (SkillResource x : list)
		{
			if (x.getItemId() == r.getItemId())
			{
				return true;
			}
		}
		return false;
	}

	static String fmt(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}

	private static String lower(String s)
	{
		return s == null ? "storage" : s.toLowerCase(Locale.ROOT);
	}
}
