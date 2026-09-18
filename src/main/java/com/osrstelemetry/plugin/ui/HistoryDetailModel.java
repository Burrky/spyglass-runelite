package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.LoadoutSnapshot;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionAggregates;
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

	private HistoryDetailModel(String sessionId, ActivityIdentity activityIdentity, String startedAt, String finalizedAt,
		long accumulatedActiveDurationMillis, List<XpRow> xpRows, long totalXpGained, List<LootRow> lootRows,
		int lootDropCount, SessionAggregates.ReliableCount reliableCount, LoadoutSnapshot startingLoadout, LoadoutSnapshot endingLoadout)
	{
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

		Map<Integer, LootRow> loot = new LinkedHashMap<>();
		int lootDropCount = 0;
		if (aggregates != null && aggregates.getLootDrops() != null)
		{
			lootDropCount = aggregates.getLootDrops().size();
			for (SessionAggregates.LootDropGroup group : aggregates.getLootDrops())
			{
				if (group.getItems() == null)
				{
					continue;
				}
				for (SessionAggregates.LootItemAggregate item : group.getItems())
				{
					if (item.getItemId() == null)
					{
						continue;
					}
					LootRow existing = loot.get(item.getItemId());
					long newQuantity = (existing == null ? 0L : existing.getQuantity()) + item.getQuantity();
					loot.put(item.getItemId(), new LootRow(item.getItemId(), item.getItemName(), newQuantity));
				}
			}
		}
		List<LootRow> lootRows = new ArrayList<>(loot.values());

		LoadoutSnapshot starting = session.getStartingLoadout() == null ? LoadoutSnapshot.unavailable() : session.getStartingLoadout();
		LoadoutSnapshot ending = session.getEndingLoadout() == null ? LoadoutSnapshot.unavailable() : session.getEndingLoadout();

		return new HistoryDetailModel(
			session.getSessionId(),
			session.getActivityIdentity(),
			session.getStartedAt(),
			session.getFinalizedAt(),
			session.getAccumulatedActiveDurationMillis(),
			Collections.unmodifiableList(xpRows),
			totalXp,
			Collections.unmodifiableList(lootRows),
			lootDropCount,
			aggregates == null ? null : aggregates.getReliableCount(),
			starting,
			ending);
	}
}
