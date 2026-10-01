package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.LoadoutSnapshot;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SlayerAssignmentEndReason;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * The pure, Swing-free transform from one finalized {@link Session} (as
 * read back by {@code HistoryCoordinator.loadDetail()}) into the
 * display-ready shape {@link HistoryDetailView} actually renders -- the
 * same runtime-&gt;snapshot-&gt;view split {@code CurrentSessionSnapshot}/
 * {@code LootTrackerSnapshot} already establish for the other two tabs.
 * Package-private, living beside {@link HistoryDetailView}.
 *
 * DELIBERATELY DOES NOT COMPUTE LEVEL-PROGRESS BARS THE WAY
 * {@code CurrentSessionSnapshot.XpEntry} DOES: a live session knows the
 * player's CURRENT absolute XP (via {@code LiveXpTracker}), so it can
 * compute "63% to level 97". A finalized History session's
 * {@link SessionAggregates#getXpGainedBySkill()} only ever stored a
 * DELTA (XP gained while this session was active), never an absolute
 * total -- there is no absolute XP value on disk to compute a level or
 * a progress percentage from. Fabricating one would violate this
 * project's consistent "never invent what the data does not actually
 * support" rule (see e.g. {@code LoadoutProvenance}'s own honest-labeling
 * design, or {@code CurrentSessionView}'s NPC-imagery section). XP
 * breakdown here is therefore skill + XP gained only, sorted by XP
 * gained descending -- still real skill icons, still real numbers,
 * just no invented level/progress figure.
 *
 * LOOT: every {@link SessionAggregates.LootDropGroup}'s items are
 * flattened and summed by item id across the WHOLE session (a
 * "session-owned loot" total, not per-drop-group rows) -- the same
 * total-stack-value-driven presentation {@code CurrentSessionView}'s own
 * loot section already uses via {@link LootPricing}, reused here
 * unchanged. {@link #getLootDropCount()} mirrors
 * {@code HistoryEntry.getLootDropCount()}'s own definition (number of
 * drop GROUPS/events observed, not summed item count).
 */
final class HistoryDetailModel
{
	@Getter
	static final class XpRow
	{
		private final String skill;
		private final long xpGained;

		XpRow(String skill, long xpGained)
		{
			this.skill = skill;
			this.xpGained = xpGained;
		}
	}

	@Getter
	static final class LootRow implements LootPricing.Priceable
	{
		private final Integer itemId;
		private final String itemName;
		private final long quantity;

		LootRow(Integer itemId, String itemName, long quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/**
	 * One persisted Slayer assignment INSTANCE of a multi-task session, for
	 * one History card. Every value comes straight from the persisted
	 * SlayerAssignmentRecord -- nothing is inferred from loot, names or
	 * other sessions.
	 */
	@Getter
	static final class SlayerTask
	{
		private final int ordinal;
		private final String taskName;
		/** "xN"; null = unknown (legacy record). See SlayerAssignmentRecord#observedUnitsCompleted. */
		private final Integer unitsCompleted;
		private final Integer initialAmount;
		private final Integer remaining;
		private final boolean bootstrapped;
		/** Null = not authoritatively ended (legacy, or the session ended mid-task): no status is shown. */
		private final SlayerAssignmentEndReason endReason;
		/** This assignment's own loot partition, same aggregation rule as {@link #getLootRows()}. */
		private final List<LootRow> lootRows;
		/** XP Spyglass tracked while this assignment was current (&gt; 0 only); empty when untracked. */
		private final List<XpRow> xpRows;
		/**
		 * Task-local ACTIVE duration, only when both boundaries were
		 * observed on the session's active clock (never for a bootstrapped
		 * record, whose start is when tracking began, not the task); null =
		 * unknown, not shown.
		 */
		private final Long activeDurationMillis;
		/** Observed kills (SlayerAssignmentRecord#observedKills); null = not tracked for this record. */
		private Integer kills;
		/** Master varbit recorded for the assignment; null = unknown. */
		private Integer masterId;
		/** Native bracelet activations; null = not tracked (older record). */
		private Integer slaughterProcs;
		private Integer expeditiousProcs;

		SlayerTask(int ordinal, String taskName, Integer unitsCompleted, Integer initialAmount, Integer remaining,
			boolean bootstrapped, SlayerAssignmentEndReason endReason, List<LootRow> lootRows, List<XpRow> xpRows,
			Long activeDurationMillis)
		{
			this.ordinal = ordinal;
			this.taskName = taskName;
			this.unitsCompleted = unitsCompleted;
			this.initialAmount = initialAmount;
			this.remaining = remaining;
			this.bootstrapped = bootstrapped;
			this.endReason = endReason;
			this.lootRows = lootRows;
			this.xpRows = xpRows;
			this.activeDurationMillis = activeDurationMillis;
		}
	}

	private final String sessionId;
	private final ActivityIdentity activityIdentity;
	private final String startedAt;
	private final String finalizedAt;
	private final long accumulatedActiveDurationMillis;
	private final List<XpRow> xpRows;
	private final long totalXpGained;
	private final List<LootRow> lootRows;
	private final int lootDropCount;
	private final SessionAggregates.ReliableCount reliableCount;
	private final LoadoutSnapshot startingLoadout;
	private final LoadoutSnapshot endingLoadout;
	/** Chronological (oldest first). Empty unless this is a multi-task Slayer session. */
	private final List<SlayerTask> slayerTasks;
	private final SessionAggregates.ActivityCompletionCount activityCompletions;
	private final SessionAggregates.ActivitySessionStats activityStats;
	/** Items the activity produced (e.g. GotR runes crafted); output, never loot. Empty for older sessions. */
	private final List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> producedItems;

	private HistoryDetailModel(String sessionId, ActivityIdentity activityIdentity, String startedAt, String finalizedAt,
		long accumulatedActiveDurationMillis, List<XpRow> xpRows, long totalXpGained, List<LootRow> lootRows,
		int lootDropCount, SessionAggregates.ReliableCount reliableCount, LoadoutSnapshot startingLoadout, LoadoutSnapshot endingLoadout,
		List<SlayerTask> slayerTasks, SessionAggregates.ActivityCompletionCount activityCompletions,
		SessionAggregates.ActivitySessionStats activityStats,
		List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> producedItems)
	{
		this.activityStats = activityStats;
		this.producedItems = producedItems;
		this.activityCompletions = activityCompletions;
		this.sessionId = sessionId;
		this.activityIdentity = activityIdentity;
		this.startedAt = startedAt;
		this.finalizedAt = finalizedAt;
		this.accumulatedActiveDurationMillis = accumulatedActiveDurationMillis;
		this.xpRows = xpRows;
		this.totalXpGained = totalXpGained;
		this.lootRows = lootRows;
		this.lootDropCount = lootDropCount;
		this.reliableCount = reliableCount;
		this.startingLoadout = startingLoadout;
		this.endingLoadout = endingLoadout;
		this.slayerTasks = slayerTasks;
	}

	String getSessionId()
	{
		return sessionId;
	}

	ActivityIdentity getActivityIdentity()
	{
		return activityIdentity;
	}

	String getStartedAt()
	{
		return startedAt;
	}

	String getFinalizedAt()
	{
		return finalizedAt;
	}

	long getAccumulatedActiveDurationMillis()
	{
		return accumulatedActiveDurationMillis;
	}

	List<XpRow> getXpRows()
	{
		return xpRows;
	}

	long getTotalXpGained()
	{
		return totalXpGained;
	}

	List<LootRow> getLootRows()
	{
		return lootRows;
	}

	int getLootDropCount()
	{
		return lootDropCount;
	}

	/** Structured-activity completion label ("Games"), or null when this session has no such metric. */
	String getCompletionLabel()
	{
		return activityIdentity == null ? null
			: CompletionMetricText.labelFor(activityIdentity.getActivityType(), activityIdentity.getActivityKey());
	}

	int getCompletionCount()
	{
		return CompletionMetricText.countOf(activityCompletions);
	}

	/** "Permits +11   •   Avg pts 4,920   •   Best pts 5,670", or null when the session has none of these. */
	String getActivityStatsText()
	{
		return activityIdentity == null ? null : CompletionMetricText.statsSummary(activityIdentity.getActivityKey(), activityStats);
	}

	String getActivityKey()
	{
		return activityIdentity == null ? null : activityIdentity.getActivityKey();
	}

	/** Tempoross / GotR: a dedicated SESSION block replaces the inline header lines (Barrows keeps its header). */
	boolean hasSessionBlock()
	{
		return getCompletionLabel() != null && CompletionMetricText.hasHistorySessionBlock(getActivityKey());
	}

	/** Earned this session: one keyed currency (GotR), or the single unkeyed currency (Tempoross) when key is null. */
	long getCurrencyGained(String currencyKey)
	{
		if (activityStats == null)
		{
			return 0L;
		}
		if (currencyKey == null)
		{
			return activityStats.getCurrencyGained();
		}
		java.util.Map<String, Long> byKey = activityStats.getCurrencyGainedByKey();
		Long v = byKey == null ? null : byKey.get(currencyKey);
		return v == null ? 0L : v;
	}

	Long getAverageScore()
	{
		return activityStats == null ? null : activityStats.averageScore();
	}

	Integer getBestScore()
	{
		return activityStats == null ? null : activityStats.getBestScore();
	}

	int getScoredGames()
	{
		return activityStats == null ? 0 : activityStats.getScoredCompletions();
	}

	List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> getProducedItems()
	{
		return producedItems;
	}

	long getProducedItemTotal()
	{
		long total = 0L;
		for (com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView item : producedItems)
		{
			total += item.getQuantity();
		}
		return total;
	}

	SessionAggregates.ReliableCount getReliableCount()
	{
		return reliableCount;
	}

	/** Never null -- callers must check {@code getProvenance() == UNAVAILABLE} rather than null-check this. See LoadoutSnapshot#unavailable(). */
	LoadoutSnapshot getStartingLoadout()
	{
		return startingLoadout;
	}

	/** Never null -- see {@link #getStartingLoadout()}. */
	LoadoutSnapshot getEndingLoadout()
	{
		return endingLoadout;
	}

	/** See {@link HistoryEntry#displayTitle}: "Slayer Session" for a multi-task session. */
	String getTitle()
	{
		return HistoryEntry.displayTitle(activityIdentity, slayerTasks.size());
	}

	/** Chronological assignment cards; empty unless this is a multi-task Slayer session. */
	List<SlayerTask> getSlayerTasks()
	{
		return slayerTasks;
	}

	/**
	 * SLAYER session totals from the persisted assignment records. Kills
	 * only when EVERY record tracked them (an older record never turns its
	 * task units into a kill claim); bracelet totals only from records that
	 * tracked them. All null/0 for older sessions -- nothing is fabricated.
	 */
	private Integer slayerKills;
	private int slayerSlaughterProcs;
	private int slayerExpeditiousProcs;

	private void applySlayerTotals(Session session)
	{
		if (session.getActivityIdentity().getActivityType() != com.osrstelemetry.plugin.session.ActivityType.SLAYER
			|| session.getAggregates() == null || session.getAggregates().getSlayerAssignments() == null
			|| session.getAggregates().getSlayerAssignments().isEmpty())
		{
			return;
		}
		int kills = 0;
		boolean allTracked = true;
		for (SessionAggregates.SlayerAssignmentRecord r : session.getAggregates().getSlayerAssignments())
		{
			if (r == null)
			{
				continue;
			}
			Integer k = r.observedKills();
			if (k == null)
			{
				allTracked = false;
			}
			else
			{
				kills += k;
			}
			slayerSlaughterProcs += r.getSlaughterProcs() == null ? 0 : r.getSlaughterProcs();
			slayerExpeditiousProcs += r.getExpeditiousProcs() == null ? 0 : r.getExpeditiousProcs();
		}
		slayerKills = allTracked ? kills : null;
	}

	Integer getSlayerKills()
	{
		return slayerKills;
	}

	int getSlayerSlaughterProcs()
	{
		return slayerSlaughterProcs;
	}

	int getSlayerExpeditiousProcs()
	{
		return slayerExpeditiousProcs;
	}

	/** A Slayer block exists only when this session tracked kills or saw a bracelet effect. */
	boolean hasSlayerBlock()
	{
		return slayerKills != null || slayerSlaughterProcs > 0 || slayerExpeditiousProcs > 0;
	}

	boolean isMultiTaskSlayerSession()
	{
		return !slayerTasks.isEmpty();
	}

	/** Assignments that authoritatively COMPLETED. */
	int getCompletedTaskCount()
	{
		return countEndReason(SlayerAssignmentEndReason.COMPLETED);
	}

	/** Assignments that were REPLACED_EARLY ("Skipped"). Never inferred from remaining alone. */
	int getSkippedTaskCount()
	{
		return countEndReason(SlayerAssignmentEndReason.REPLACED_EARLY);
	}

	private int countEndReason(SlayerAssignmentEndReason reason)
	{
		int n = 0;
		for (SlayerTask task : slayerTasks)
		{
			if (task.getEndReason() == reason)
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * Fail-open, same contract as {@code HistoryEntry.from()}: returns
	 * null for a null or structurally-incomplete session rather
	 * than throwing, so a single malformed/unexpected finalized session
	 * file can never crash the History detail view -- the caller (see
	 * {@code HistoryDetailView#showUnavailable()}) renders an explicit
	 * "could not be loaded" state instead.
	 */
	static HistoryDetailModel from(Session session)
	{
		if (session == null || session.getSessionId() == null || session.getActivityIdentity() == null)
		{
			return null;
		}

		SessionAggregates aggregates = session.getAggregates();

		List<XpRow> xpRows = new ArrayList<>();
		long totalXp = 0L;
		if (aggregates != null && aggregates.getXpGainedBySkill() != null)
		{
			for (Map.Entry<String, Long> entry : aggregates.getXpGainedBySkill().entrySet())
			{
				long xp = entry.getValue() == null ? 0L : entry.getValue();
				if (xp <= 0L)
				{
					continue;
				}
				xpRows.add(new XpRow(entry.getKey(), xp));
				totalXp += xp;
			}
		}
		xpRows.sort(Comparator.comparingLong(XpRow::getXpGained).reversed());

		int lootDropCount = 0;
		List<SessionAggregates.LootItemAggregate> sessionItems = new ArrayList<>();
		if (aggregates != null && aggregates.getLootDrops() != null)
		{
			lootDropCount = aggregates.getLootDrops().size();
			for (SessionAggregates.LootDropGroup group : aggregates.getLootDrops())
			{
				if (group.getItems() != null)
				{
					sessionItems.addAll(group.getItems());
				}
			}
		}
		List<LootRow> lootRows = lootRowsFrom(sessionItems);

		LoadoutSnapshot starting = session.getStartingLoadout() == null ? LoadoutSnapshot.unavailable() : session.getStartingLoadout();
		LoadoutSnapshot ending = session.getEndingLoadout() == null ? LoadoutSnapshot.unavailable() : session.getEndingLoadout();

		HistoryDetailModel model = new HistoryDetailModel(
			session.getSessionId(),
			session.getActivityIdentity(),
			session.getStartedAt(),
			session.getFinalizedAt(),
			session.getAccumulatedActiveDurationMillis(),
			Collections.unmodifiableList(xpRows),
			totalXp,
			lootRows,
			lootDropCount,
			aggregates == null ? null : aggregates.getReliableCount(),
			starting,
			ending,
			slayerTasksFrom(session),
			aggregates == null ? null : aggregates.getActivityCompletions(),
			aggregates == null ? null : aggregates.getActivityStats(),
			com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.buildProducedItemViews(
				aggregates == null ? null : aggregates.getProducedItems()));
		model.applySlayerTotals(session);
		return model;
	}

	/**
	 * Summed by item ID (items without an ID are skipped), in first-seen
	 * order -- the one rule for both the session grid and every task card,
	 * so the cards visibly add up to the session loot.
	 */
	private static List<LootRow> lootRowsFrom(List<SessionAggregates.LootItemAggregate> items)
	{
		Map<Integer, LootRow> loot = new LinkedHashMap<>();
		for (SessionAggregates.LootItemAggregate item : items)
		{
			if (item == null || item.getItemId() == null)
			{
				continue;
			}
			LootRow existing = loot.get(item.getItemId());
			long newQuantity = (existing == null ? 0L : existing.getQuantity()) + item.getQuantity();
			loot.put(item.getItemId(), new LootRow(item.getItemId(), item.getItemName(), newQuantity));
		}
		return Collections.unmodifiableList(new ArrayList<>(loot.values()));
	}

	/**
	 * Chronological task cards for a Slayer session with MORE THAN ONE
	 * persisted assignment record; empty otherwise (single-task sessions and
	 * legacy files without a ledger keep the original History layout).
	 */
	private static List<SlayerTask> slayerTasksFrom(Session session)
	{
		if (HistoryEntry.slayerAssignmentCount(session) <= 1)
		{
			return Collections.emptyList();
		}
		List<SlayerTask> tasks = new ArrayList<>();
		for (SessionAggregates.SlayerAssignmentRecord record : session.getAggregates().getSlayerAssignments())
		{
			if (record == null)
			{
				continue;
			}
			List<XpRow> xpRows = new ArrayList<>();
			if (record.getXpGainedBySkill() != null)
			{
				for (Map.Entry<String, Long> e : record.getXpGainedBySkill().entrySet())
				{
					if (e.getValue() != null && e.getValue() > 0L)
					{
						xpRows.add(new XpRow(e.getKey(), e.getValue()));
					}
				}
				xpRows.sort(Comparator.comparingLong(XpRow::getXpGained).reversed());
			}
			Long duration = null;
			if (!record.isBootstrapped() && record.getActiveDurationStartMillis() != null && record.getActiveDurationEndMillis() != null
				&& record.getActiveDurationEndMillis() >= record.getActiveDurationStartMillis())
			{
				duration = record.getActiveDurationEndMillis() - record.getActiveDurationStartMillis();
			}
			SlayerTask task = new SlayerTask(record.getOrdinal(), record.getTaskName(), record.observedUnitsCompleted(),
				record.getInitialAmount(), record.getLatestRemaining(), record.isBootstrapped(), record.getEndReason(),
				lootRowsFrom(record.getItems() == null ? Collections.<SessionAggregates.LootItemAggregate>emptyList() : record.getItems()),
				Collections.unmodifiableList(xpRows), duration);
			task.kills = record.observedKills();
			task.masterId = record.getMasterId();
			task.slaughterProcs = record.getSlaughterProcs();
			task.expeditiousProcs = record.getExpeditiousProcs();
			tasks.add(task);
		}
		return Collections.unmodifiableList(tasks);
	}
}
