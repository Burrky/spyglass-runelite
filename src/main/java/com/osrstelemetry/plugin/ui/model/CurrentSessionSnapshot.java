package com.osrstelemetry.plugin.ui.model;

import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.SessionAggregates;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.session.SessionState;
import com.osrstelemetry.plugin.ui.CompletionMetricText;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Experience;

/**
 * ADDED (player-facing Current Session UI pass); REWRITTEN (Current
 * Session UI thread-safety hardening pass) to build from a
 * {@link SessionSnapshot} instead of a live
 * {@link com.osrstelemetry.plugin.session.Session}; EXTENDED (live
 * bug-fix pass, PART 6/7 -- Slayer "task remaining" display) with
 * slayerCurrentRemaining. A small, immutable, UI-shaped re-projection
 * of that ALREADY-immutable, already-defensively-copied runtime
 * snapshot — built fresh at read time by
 * {@link #from(SessionSnapshot, Instant)} and never shared/cached across
 * polls. Deliberately does NOT mirror Session's full persisted
 * structure: it carries only what the Current Session panel needs to
 * render, in already-display-shaped form (nonzero-only XP rows,
 * pre-summed loot rows, an already-computed "duration as of now"
 * figure) rather than making the Swing layer re-derive any of that
 * itself.
 *
 * ARCHITECTURE (per explicit instruction, revised in the thread-safety
 * hardening pass): telemetry/session runtime
 * (SessionLifecycleEngine/SessionRuntimeCoordinator) -&gt;
 * {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()}, which
 * captures a {@link SessionSnapshot} ATOMICALLY under the coordinator's
 * own monitor (see that method's and {@link SessionSnapshot}'s own
 * javadoc) -&gt; this class, a further, purely-cosmetic UI-shaped
 * re-projection of that already-safe, already-copied data (no further
 * defensive copying is needed at this layer — {@link SessionSnapshot}
 * already guarantees immutability) -&gt; the player-facing Swing panel.
 * The session package never depends on this ui package; this class
 * depends on {@link SessionSnapshot} only.
 *
 * PURITY: {@link #from(SessionSnapshot, Instant)} never mutates the
 * snapshot it is given (it only calls getters). Every collection
 * exposed by this class is its own new, unmodifiable copy — never a
 * live view over the input snapshot's own collections (which are
 * themselves already independent copies, per {@link SessionSnapshot}).
 *
 * DURATION SEMANTICS (see {@link #activeDurationMillis}'s own
 * javadoc): this is the one piece of real derivation this class does,
 * and it is deliberately kept here (pure, synchronous, testable with
 * synthetic Instants) rather than in Swing.
 *
 * SLAYER "TASK REMAINING" (live bug-fix pass, PART 6/7): slayerCurrentRemaining
 * is a plain pass-through of {@link SessionSnapshot#getLatestSlayerCurrentRemaining()}
 * -- the LATEST authoritative currentRemaining observed this session,
 * an absolute task-state number, deliberately kept separate from
 * slayerProgressDelta (an accumulated total, still exposed below for
 * future Recent Activity use). {@code CurrentSessionView} now renders
 * THIS field as the primary live Slayer number ("Task remaining N"),
 * not slayerProgressDelta.
 */
public final class CurrentSessionSnapshot
{
	/**
	 * THE shared rate floor: minimum ACTIVE duration before ANY per-hour
	 * figure is shown -- XP/hr, Slayer kills/hr, games/chests/hr, in
	 * Current Session and History alike.
	 *
	 * RELEASE CANDIDATE (XP spike feedback): was 60 seconds. One large
	 * one-time XP drop (a lamp, a quest, a big reward deposit) in a sample
	 * that short projects to an absurd XP/hr -- the math is right, the
	 * extrapolation is meaningless. Five minutes of active play lets an
	 * ordinary activity's own rhythm dominate. Display only: totals are
	 * always exact and nothing is smoothed, capped or discarded; the rate
	 * is simply not projected yet (the view says when it will be).
	 */
	public static final long MIN_DURATION_FOR_RATE_MILLIS = 5L * 60_000L;

	private static final CurrentSessionSnapshot EMPTY = new CurrentSessionSnapshot(
		false, null, null, null, null, 0L,
		Collections.emptyList(), null, null, Collections.emptyList(), null, null, null, null, null,
		Collections.<SlayerAssignmentView>emptyList(), null, null, 0, null, null, Collections.<ProducedItemView>emptyList());

	/**
	 * ADDED (Current Session UI facelift, PART 1/16). The core combat
	 * skills a COMBAT/BOSSING session's primary signifier is chosen
	 * from -- see {@link #getPrimarySkill()}. Deliberately excludes
	 * Hitpoints (gains XP under every combat style, so it would always
	 * dominate and mask which style is actually being trained) and
	 * Slayer (SLAYER sessions short-circuit to "SLAYER" directly, never
	 * reaching this set).
	 */
	private static final List<String> CORE_COMBAT_SKILLS =
		Collections.unmodifiableList(Arrays.asList("ATTACK", "STRENGTH", "DEFENCE", "RANGED", "MAGIC"));

	private final boolean present;
	private final String sessionId;
	private final String displayName;
	private final ActivityType activityType;
	private final SessionState state;
	private final long activeDurationMillis;
	private final List<XpEntry> xpEntries;
	private final ReliableCountView reliableCount;
	private final Integer slayerProgressDelta;
	private final List<LootEntry> lootEntries;
	private final Integer slayerCurrentRemaining;

	/**
	 * SMART SLAYER TASK STATE: the per-ASSIGNMENT slice of the session's
	 * Slayer task units / XP, used only by
	 * {@link #getEstimatedSlayerXpRemaining()} so the estimate for a
	 * newly-installed task is never blended with a previous task's
	 * XP-per-unit. Null baseline = the session has only had one
	 * assignment, so the session totals are the assignment's own.
	 */
	private final Integer slayerAssignmentBaselineProgressDelta;
	private final Long slayerAssignmentBaselineSlayerXp;

	/**
	 * Slayer assignment cards, current/newest first. Empty for non-Slayer
	 * sessions (their loot renders exactly as before) and for a Slayer
	 * session with no ledger.
	 */
	private final List<SlayerAssignmentView> slayerAssignments;

	/** SLAYER XP SCOPE = CURRENT TASK cards (see buildTaskXpEntries()); null = not applicable. */
	private final List<XpEntry> taskXpEntries;

	/**
	 * ADDED (Current Session UI facelift, PART 2/7). The attackable NPC
	 * the player is CURRENTLY understood to be targeting, as reported
	 * live by {@code NpcInteractionTargetCollector} at snapshot-build
	 * time (see {@link #from(SessionSnapshot, Instant, String, Integer)}).
	 * Deliberately NOT part of {@link SessionSnapshot}/SessionAggregates
	 * -- this is per-account live combat-target state, not session-scoped
	 * durable truth, and carries no lifecycle/classification meaning of
	 * its own. Null whenever no attackable NPC is currently targeted
	 * (SKILLING activities, or no combat NPC currently engaged).
	 */
	/** Structured-activity completion metric label ("Games"); null when not applicable. */
	private final String completionLabel;
	private final int completionCount;
	/** Stable key of the session identity (e.g. "barrows"); presentation lookups only. */
	private final String activityKey;
	/** Session-summary activity stats (a private copy), or null. */
	private final com.osrstelemetry.plugin.session.SessionAggregates.ActivitySessionStats activityStats;
	/** Items the activity produced this session (output, not loot), first-produced order. */
	private final List<ProducedItemView> producedItems;

	private final String currentNpcName;
	private final Integer currentNpcId;

	private CurrentSessionSnapshot(
		boolean present,
		String sessionId,
		String displayName,
		ActivityType activityType,
		SessionState state,
		long activeDurationMillis,
		List<XpEntry> xpEntries,
		ReliableCountView reliableCount,
		Integer slayerProgressDelta,
		List<LootEntry> lootEntries,
		Integer slayerCurrentRemaining,
		String currentNpcName,
		Integer currentNpcId,
		Integer slayerAssignmentBaselineProgressDelta,
		Long slayerAssignmentBaselineSlayerXp,
		List<SlayerAssignmentView> slayerAssignments,
		List<XpEntry> taskXpEntries,
		String completionLabel,
		int completionCount,
		String activityKey,
		com.osrstelemetry.plugin.session.SessionAggregates.ActivitySessionStats activityStats,
		List<ProducedItemView> producedItems)
	{
		this.activityStats = activityStats;
		this.producedItems = producedItems;
		this.activityKey = activityKey;
		this.completionLabel = completionLabel;
		this.completionCount = completionCount;
		this.slayerAssignments = slayerAssignments;
		this.taskXpEntries = taskXpEntries;
		this.slayerAssignmentBaselineProgressDelta = slayerAssignmentBaselineProgressDelta;
		this.slayerAssignmentBaselineSlayerXp = slayerAssignmentBaselineSlayerXp;
		this.present = present;
		this.sessionId = sessionId;
		this.displayName = displayName;
		this.activityType = activityType;
		this.state = state;
		this.activeDurationMillis = activeDurationMillis;
		this.xpEntries = xpEntries;
		this.reliableCount = reliableCount;
		this.slayerProgressDelta = slayerProgressDelta;
		this.lootEntries = lootEntries;
		this.slayerCurrentRemaining = slayerCurrentRemaining;
		this.currentNpcName = currentNpcName;
		this.currentNpcId = currentNpcId;
	}

	/** The empty-state snapshot ("no active session"). Test A. */
	public static CurrentSessionSnapshot empty()
	{
		return EMPTY;
	}

	/**
	 * Builds a snapshot from a {@code sessionSnapshot} as of {@code now}.
	 * {@code sessionSnapshot} may be null or {@link SessionSnapshot#absent()}
	 * (no current session — returns {@link #empty()}). Never mutates
	 * {@code sessionSnapshot} (it is already fully immutable).
	 */
	public static CurrentSessionSnapshot from(SessionSnapshot sessionSnapshot, Instant now)
	{
		return from(sessionSnapshot, now, null, null);
	}

	/**
	 * ADDED (Current Session UI facelift). Same contract as
	 * {@link #from(SessionSnapshot, Instant)}, plus the NPC the player
	 * is currently understood to be targeting, as read live by the
	 * caller from {@code NpcInteractionTargetCollector} at the same
	 * moment (see {@code OsrsTelemetryPanel#refreshCurrentSession()} --
	 * the only production caller). Both null when no attackable NPC is
	 * currently targeted.
	 */
	public static CurrentSessionSnapshot from(SessionSnapshot sessionSnapshot, Instant now, String currentNpcName, Integer currentNpcId)
	{
		if (sessionSnapshot == null || !sessionSnapshot.isPresent())
		{
			return EMPTY;
		}

		long durationMillis = computeActiveDurationMillis(sessionSnapshot, now);
		List<XpEntry> xp = buildXpEntries(sessionSnapshot, durationMillis);
		ReliableCountView reliableCount = buildReliableCountView(sessionSnapshot);
		Integer slayerProgressDelta = sessionSnapshot.getSlayerProgressDelta();
		Integer slayerCurrentRemaining = sessionSnapshot.getLatestSlayerCurrentRemaining();
		List<LootEntry> loot = buildLootEntries(sessionSnapshot);

		CurrentSessionSnapshot built = new CurrentSessionSnapshot(
			true,
			sessionSnapshot.getSessionId(),
			sessionSnapshot.getActivityDisplayName(),
			sessionSnapshot.getActivityType(),
			sessionSnapshot.getState(),
			durationMillis,
			xp,
			reliableCount,
			slayerProgressDelta,
			loot,
			slayerCurrentRemaining,
			currentNpcName,
			currentNpcId,
			sessionSnapshot.getSlayerAssignmentBaselineProgressDelta(),
			sessionSnapshot.getSlayerAssignmentBaselineSlayerXp(),
			buildSlayerAssignmentViews(sessionSnapshot),
			buildTaskXpEntries(sessionSnapshot, durationMillis),
			CompletionMetricText.labelFor(sessionSnapshot.getActivityType(), sessionSnapshot.getActivityKey()),
			CompletionMetricText.countOf(sessionSnapshot.getActivityCompletions()),
			sessionSnapshot.getActivityKey(),
			sessionSnapshot.getActivityStats(),
			buildProducedItemViews(sessionSnapshot.getProducedItems()));
		built.slayerCurrentTask = buildSlayerCurrentTask(sessionSnapshot, durationMillis);
		built.skillStats = sessionSnapshot.getSkillStats();
		return built;
	}

	/** Skill companion stats (banked, method time) of this session, a private copy; null when none. */
	private com.osrstelemetry.plugin.session.SessionAggregates.SkillSessionStats skillStats;

	public com.osrstelemetry.plugin.session.SessionAggregates.SkillSessionStats getSkillStats()
	{
		return skillStats;
	}

	/** Smart Slayer CURRENT TASK facts; null for a non-Slayer / ledger-less session. Not part of equality. */
	private SlayerCurrentTaskView slayerCurrentTask;

	public SlayerCurrentTaskView getSlayerCurrentTask()
	{
		return slayerCurrentTask;
	}

	/** Active time the record owns: start..end, or start..now while it is unfinished. Null when untracked. */
	private static Long taskActiveMillis(SessionAggregates.SlayerAssignmentRecord r, long sessionDurationMillis)
	{
		if (r.getActiveDurationStartMillis() == null)
		{
			return null;
		}
		long end = r.getActiveDurationEndMillis() == null ? sessionDurationMillis : r.getActiveDurationEndMillis();
		return Math.max(0L, end - r.getActiveDurationStartMillis());
	}

	private static SlayerCurrentTaskView buildSlayerCurrentTask(SessionSnapshot snapshot, long sessionDurationMillis)
	{
		List<SessionAggregates.SlayerAssignmentRecord> ledger = slayerLedger(snapshot);
		if (ledger.isEmpty())
		{
			return null;
		}
		SessionAggregates.SlayerAssignmentRecord r = ledger.get(ledger.size() - 1);
		Integer remaining = r.getLatestRemaining();
		Long active = taskActiveMillis(r, sessionDurationMillis);
		// ACTUAL kills (owned encounter deaths, floored by the unit-derived kills) -- never task units.
		Integer kills = toInt(r.actualKills());
		SlayerCurrentTaskView view = new SlayerCurrentTaskView(r.getTaskName(), remaining,
			SlayerDashboard.progressPercent(remaining, SlayerDashboard.assignmentSize(r)),
			kills, active == null ? null : SlayerDashboard.perHour(kills, active),
			r.getSlaughterProcs(), r.getExpeditiousProcs(), r.getMasterId(),
			aggregateLootEntries(r.getItems()), SlayerDashboard.looksLikeTuraelBoosting(ledger));
		view.superiorsThisTask = r.superiorKills();
		Long session = null;
		for (SessionAggregates.SlayerAssignmentRecord a : ledger)
		{
			Long n = a.superiorKills();
			if (n != null)
			{
				session = (session == null ? 0L : session) + n;
			}
		}
		view.superiorsThisSession = session;
		return view;
	}

	private static Integer toInt(Long v)
	{
		return v == null ? null : (int) Math.min(Integer.MAX_VALUE, v);
	}

	/**
	 * The CURRENT TASK section's facts for the current assignment instance.
	 * Never carries the assignment's size: progress is a percentage only.
	 */
	public static final class SlayerCurrentTaskView
	{
		private final String taskName;
		private final Integer remaining;
		private final Integer progressPercent;
		private final Integer kills;
		private final Long killsPerHour;
		private final Integer slaughterProcs;
		private final Integer expeditiousProcs;
		private final Integer masterId;
		private final List<LootEntry> lootEntries;
		private final boolean turaelBoosting;
		/** Superior creatures killed on this task / in the whole Slayer session; null = not tracked. */
		private Long superiorsThisTask;
		private Long superiorsThisSession;

		public Long getSuperiorsThisTask()
		{
			return superiorsThisTask;
		}

		public Long getSuperiorsThisSession()
		{
			return superiorsThisSession;
		}

		SlayerCurrentTaskView(String taskName, Integer remaining, Integer progressPercent, Integer kills, Long killsPerHour,
			Integer slaughterProcs, Integer expeditiousProcs, Integer masterId, List<LootEntry> lootEntries, boolean turaelBoosting)
		{
			this.taskName = taskName;
			this.remaining = remaining;
			this.progressPercent = progressPercent;
			this.kills = kills;
			this.killsPerHour = killsPerHour;
			this.slaughterProcs = slaughterProcs;
			this.expeditiousProcs = expeditiousProcs;
			this.masterId = masterId;
			this.lootEntries = lootEntries;
			this.turaelBoosting = turaelBoosting;
		}

		public String getTaskName()
		{
			return taskName;
		}

		/** Native remaining task units; 0 = complete / awaiting a new task; null = not yet observed. */
		public Integer getRemaining()
		{
			return remaining;
		}

		public boolean isComplete()
		{
			return remaining != null && remaining == 0;
		}

		public Integer getProgressPercent()
		{
			return progressPercent;
		}

		/** ACTUAL kills (see SlayerAssignmentRecord#actualKills -- never task units); null when not known. */
		public Integer getKills()
		{
			return kills;
		}

		public Long getKillsPerHour()
		{
			return killsPerHour;
		}

		public Integer getSlaughterProcs()
		{
			return slaughterProcs;
		}

		public Integer getExpeditiousProcs()
		{
			return expeditiousProcs;
		}

		/** Master varbit recorded for this assignment, or null. */
		public Integer getMasterId()
		{
			return masterId;
		}

		/** This assignment's own canonical loot (the same partition its Task History card shows). */
		public List<LootEntry> getLootEntries()
		{
			return lootEntries;
		}

		/** See SlayerDashboard#looksLikeTuraelBoosting. */
		public boolean isTuraelBoosting()
		{
			return turaelBoosting;
		}
	}

	/** Produced items with a positive quantity, in first-produced order. */
	public static List<ProducedItemView> buildProducedItemViews(List<SessionAggregates.ProducedItemAggregate> items)
	{
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}
		List<ProducedItemView> out = new ArrayList<>();
		for (SessionAggregates.ProducedItemAggregate item : items)
		{
			if (item != null && item.getQuantity() > 0)
			{
				out.add(new ProducedItemView(item.getItemId(), item.getItemName(), item.getQuantity()));
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** Items this session's activity produced (e.g. GotR runes crafted); empty when none. Never loot. */
	public List<ProducedItemView> getProducedItems()
	{
		return producedItems;
	}

	/** Sum of every produced item's quantity (0 when none). */
	public long getProducedItemTotal()
	{
		long total = 0L;
		for (ProducedItemView item : producedItems)
		{
			total += item.getQuantity();
		}
		return total;
	}

	/** Earned this session for one currency key; falls back to the single-currency total when no breakdown exists. */
	public long getActivityCurrencyGained(String currencyKey)
	{
		if (activityStats == null)
		{
			return 0L;
		}
		java.util.Map<String, Long> byKey = activityStats.getCurrencyGainedByKey();
		if (byKey == null)
		{
			return 0L;
		}
		Long v = byKey.get(currencyKey);
		return v == null ? 0L : v;
	}

	/** One produced item: id (may be null), name, exact cumulative quantity. */
	public static final class ProducedItemView
	{
		private final Integer itemId;
		private final String itemName;
		private final long quantity;

		public ProducedItemView(Integer itemId, String itemName, long quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}

		public Integer getItemId()
		{
			return itemId;
		}

		public String getItemName()
		{
			return itemName;
		}

		public long getQuantity()
		{
			return quantity;
		}
	}

	/** Native reward currency gained this session (Tempoross permits); 0 when none / not applicable. */
	public long getActivityCurrencyGained()
	{
		return activityStats == null ? 0L : activityStats.getCurrencyGained();
	}

	/** In-game round time this session (Pest Control: island time only, so lobby waiting never counts). */
	public long getRoundMillis()
	{
		return activityStats == null ? 0L : activityStats.getRoundMillis();
	}

	/** Rounds whose time is in {@link #getRoundMillis()}. */
	public int getRoundsTimed()
	{
		return activityStats == null ? 0 : activityStats.getRoundsTimed();
	}

	/** Completed games with a trusted final score (the Avg/Best population). */
	public int getScoredGames()
	{
		return activityStats == null ? 0 : activityStats.getScoredCompletions();
	}

	/** Mean final score of those games, or null when there are none. */
	public Long getAverageScore()
	{
		return activityStats == null ? null : activityStats.averageScore();
	}

	/** Best final score of those games, or null when there are none. */
	public Integer getBestScore()
	{
		return activityStats == null ? null : activityStats.getBestScore();
	}

	/** Sum of the trusted final scores (Wintertodt: points of subdued rounds). */
	public long getScoreTotal()
	{
		return activityStats == null ? 0L : activityStats.getScoreTotal();
	}

	/** Rounds seen from start to end this session, and their total time (Avg Round). */
	public int getCompleteRounds()
	{
		return activityStats == null ? 0 : activityStats.getCompleteRounds();
	}

	public long getCompleteRoundMillis()
	{
		return activityStats == null ? 0L : activityStats.getCompleteRoundMillis();
	}

	/** Counted actions this session (action key -> total); empty when none. */
	public java.util.Map<String, Long> getActionCounts()
	{
		return activityStats == null || activityStats.getActionCounts() == null
			? java.util.Collections.<String, Long>emptyMap() : activityStats.getActionCounts();
	}

	/**
	 * DURATION SEMANTICS: reuses exactly the fields
	 * {@code SessionLifecycleEngine} itself uses for active-duration
	 * accounting — never {@code now - startedAt} (see
	 * {@code Session#accumulatedActiveDurationMillis}'s own javadoc for
	 * why that would be wrong).
	 *
	 * {@code accumulatedActiveDurationMillis} only ever grows at the
	 * moment a qualifying heartbeat for the SAME activity actually
	 * arrives (see {@code SessionLifecycleEngine.accumulate()}) — so
	 * while a session is genuinely ACTIVE right now, the real elapsed
	 * time since its own {@code lastActiveAt} has not yet been folded
	 * into that field (it lags by construction until the next
	 * heartbeat). Showing only the lagging accumulated figure while
	 * ACTIVE would make the displayed duration visibly freeze between
	 * heartbeats even though the player is actively playing — so for a
	 * live, ACTIVE session this method adds the elapsed time since
	 * {@code lastActiveAt} on top of the accumulated figure, exactly
	 * mirroring what the engine itself will fold in at the very next
	 * heartbeat.
	 *
	 * That added live elapsed time is clamped to
	 * {@code SessionLifecycleEngine.SUSPEND_TIMEOUT} (reused directly,
	 * never a duplicated constant): the engine itself would never
	 * attribute more than that much continued activity to a single gap
	 * before suspending, so this display never counts further than the
	 * engine's own rule would eventually credit once its own
	 * suspend/advanceTime machinery actually catches up (which can lag
	 * a live UI poll by up to one GameTick under ordinary play — see
	 * SessionRuntimeCoordinator's own TICK-BATCH CLOSURE javadoc).
	 *
	 * A SUSPENDED (or FINALIZED) session's duration is exactly
	 * {@code accumulatedActiveDurationMillis} with nothing added — the
	 * time since suspension is genuinely idle and must never count
	 * (Test C).
	 */
	private static long computeActiveDurationMillis(SessionSnapshot session, Instant now)
	{
		long accumulated = session.getAccumulatedActiveDurationMillis();
		if (session.getState() != SessionState.ACTIVE)
		{
			return accumulated;
		}

		String lastActiveAt = session.getLastActiveAt();
		if (lastActiveAt == null || now == null)
		{
			return accumulated;
		}

		Instant lastActive = Instant.parse(lastActiveAt);
		long liveElapsedMillis = Duration.between(lastActive, now).toMillis();
		if (liveElapsedMillis < 0)
		{
			// Defensive only (out-of-order `now`) — never subtract.
			liveElapsedMillis = 0L;
		}
		long suspendTimeoutMillis = com.osrstelemetry.plugin.session.SessionLifecycleEngine.SUSPEND_TIMEOUT.toMillis();
		if (liveElapsedMillis > suspendTimeoutMillis)
		{
			liveElapsedMillis = suspendTimeoutMillis;
		}
		return accumulated + liveElapsedMillis;
	}

	/**
	 * XP RENDERING: nonzero skills only (Test D), sorted by XP gained
	 * descending — the simplest, most immediately useful ordering
	 * (highest-impact skill first) — with skill name ascending as a
	 * deterministic tie-break. XP/hr is included only when
	 * {@code durationMillis >= MIN_DURATION_FOR_RATE_MILLIS} (Test I);
	 * otherwise the row's rate is left null and the panel omits it
	 * rather than showing a misleadingly huge extrapolated number.
	 *
	 * LIVE-DEBUG PASS, PART 2 (immediate Current Session XP display):
	 * each row's displayed {@code xp} is now the CANONICAL durable
	 * amount from {@code aggregates.getXpGainedBySkill()} PLUS whatever
	 * live, not-yet-durably-flushed amount
	 * {@code aggregates.getLivePendingXpBySkill()} currently holds for
	 * that same skill (see {@code LiveXpTracker}'s own javadoc for why
	 * this sum is exact-once: the live portion is reset to zero the
	 * instant a real XP_CHANGE flush durably represents it). A skill
	 * with ONLY a live pending amount (no durable XP at all yet -- the
	 * very first gain this session, before XP_CHANGE has ever flushed)
	 * still gets its own row immediately, from the union of both maps'
	 * keys -- not merely the durable map's keys. XP/hr below is derived
	 * from this SAME combined total, so it too reflects the live amount
	 * immediately rather than waiting for a durable flush.
	 */
	private static List<XpEntry> buildXpEntries(SessionSnapshot aggregates, long durationMillis)
	{
		if (aggregates == null || aggregates.getXpGainedBySkill() == null)
		{
			return Collections.emptyList();
		}
		return xpEntriesFrom(aggregates.getXpGainedBySkill(), aggregates.getLivePendingXpBySkill(),
			aggregates.getAbsoluteXpBySkill(), durationMillis);
	}

	/**
	 * SLAYER XP SCOPE = CURRENT TASK: cards from the current assignment
	 * record's own XP slice, rated over its own ACTIVE time (the session's
	 * active-duration clock from the assignment's start to its end, or now
	 * while unfinished -- so suspended and parked time are excluded exactly
	 * as for the session). Live pending XP counts only while unfinished.
	 * Only skills with &gt; 0 XP in THIS assignment get a card, so a new
	 * assignment yields an EMPTY list (the view then drops the XP section
	 * and its gap); a completed one keeps its frozen cards until the next
	 * begins. Levels/progress bars stay the player's real absolute values.
	 *
	 * Null when not applicable (non-Slayer, no ledger, or a current record
	 * with no XP tracking): callers then show the session-wide cards.
	 */
	private static List<XpEntry> buildTaskXpEntries(SessionSnapshot snapshot, long sessionDurationMillis)
	{
		List<SessionAggregates.SlayerAssignmentRecord> ledger = slayerLedger(snapshot);
		if (ledger.isEmpty())
		{
			return null;
		}
		SessionAggregates.SlayerAssignmentRecord current = ledger.get(ledger.size() - 1);
		if (current.getXpGainedBySkill() == null || current.getActiveDurationStartMillis() == null)
		{
			return null;
		}
		boolean unfinished = current.getActiveDurationEndMillis() == null;
		long end = unfinished ? sessionDurationMillis : current.getActiveDurationEndMillis();
		long taskDuration = Math.max(0L, end - current.getActiveDurationStartMillis());
		return xpEntriesFrom(current.getXpGainedBySkill(),
			unfinished ? snapshot.getLivePendingXpBySkill() : Collections.<String, Long>emptyMap(),
			snapshot.getAbsoluteXpBySkill(), taskDuration);
	}

	/** One card per skill with non-zero XP (durable + live pending). */
	private static List<XpEntry> xpEntriesFrom(Map<String, Long> durableBySkill, Map<String, Long> livePendingBySkill,
		Map<String, Long> absoluteXpBySkill, long durationMillis)
	{
		Set<String> skills = new LinkedHashSet<>(durableBySkill.keySet());
		if (livePendingBySkill != null)
		{
			skills.addAll(livePendingBySkill.keySet());
		}

		List<XpEntry> entries = new ArrayList<>();
		for (String skill : skills)
		{
			// ADDED (Current Session UI facelift, PART 5/16): Skill.OVERALL
			// is a synthetic RuneLite "skill" (the account total) that can
			// arrive via StatChanged like any real skill -- it must never
			// get its own Current Session card.
			if ("OVERALL".equals(skill))
			{
				continue;
			}

			Long durable = durableBySkill.get(skill);
			Long livePending = livePendingBySkill == null ? null : livePendingBySkill.get(skill);
			long xp = (durable == null ? 0L : durable) + (livePending == null ? 0L : livePending);
			if (xp == 0L)
			{
				continue;
			}
			Long xpPerHour = null;
			Long rateWarmupRemaining = null;
			if (durationMillis >= MIN_DURATION_FOR_RATE_MILLIS)
			{
				xpPerHour = Math.round(xp * 3_600_000.0 / durationMillis);
			}
			else
			{
				rateWarmupRemaining = MIN_DURATION_FOR_RATE_MILLIS - Math.max(0L, durationMillis);
			}
			Long absoluteXp = absoluteXpBySkill == null
				? null
				: absoluteXpBySkill.get(skill);
			LevelProgress levelProgress = absoluteXp == null ? null : computeLevelProgress(absoluteXp);
			entries.add(new XpEntry(skill, xp, xpPerHour, levelProgress, rateWarmupRemaining));
		}

		entries.sort((a, b) ->
		{
			int byXp = Long.compare(b.getXpGained(), a.getXpGained());
			if (byXp != 0)
			{
				return byXp;
			}
			return a.getSkill().compareTo(b.getSkill());
		});

		return Collections.unmodifiableList(entries);
	}

	/**
	 * ADDED (Current Session XP level-progress pass). Derives real
	 * account-level progression from one raw absolute XP value, using
	 * RuneLite's OWN canonical XP table ({@link Experience}) rather than
	 * a hand-maintained one -- {@link Experience#getLevelForXp(int)} /
	 * {@link Experience#getXpForLevel(int)} are exactly the values the
	 * real game client itself derives level-up/progress-bar state from.
	 *
	 * REAL LEVELS ONLY (per explicit instruction): {@code getLevelForXp}
	 * returns a VIRTUAL level up to {@link Experience#MAX_VIRT_LEVEL}
	 * (126) once xp exceeds the level-99 threshold -- this method
	 * deliberately clamps to {@link Experience#MAX_REAL_LEVEL} (99) and
	 * switches to the MAX state there, never computing or displaying a
	 * virtual level or "progress toward 100+".
	 *
	 * {@code absoluteXp} is cast to {@code int} -- safe because
	 * {@link Experience#MAX_SKILL_XP} (200,000,000) already fits
	 * comfortably within the int range Experience's own API is defined
	 * in terms of.
	 */
	private static LevelProgress computeLevelProgress(long absoluteXp)
	{
		int xp = (int) Math.min(absoluteXp, Experience.MAX_SKILL_XP);
		int level = Math.min(Experience.getLevelForXp(xp), Experience.MAX_REAL_LEVEL);

		if (level >= Experience.MAX_REAL_LEVEL)
		{
			return new LevelProgress(Experience.MAX_REAL_LEVEL, true, null, null);
		}

		int xpForCurrentLevel = Experience.getXpForLevel(level);
		int xpForNextLevel = Experience.getXpForLevel(level + 1);
		int xpIntoLevel = xp - xpForCurrentLevel;
		int xpSpanOfLevel = xpForNextLevel - xpForCurrentLevel;
		int progressPercent = xpSpanOfLevel <= 0 ? 0 : (int) (100L * xpIntoLevel / xpSpanOfLevel);
		long xpRemaining = xpForNextLevel - xp;

		return new LevelProgress(level, false, progressPercent, xpRemaining);
	}

	/**
	 * RELIABLE-COUNT RENDERING: only rendered at all when
	 * {@code SessionAggregates.reliableCount} is non-null (Tests E/F) —
	 * per that field's own javadoc, this is already the honest
	 * "no row until a qualifying authoritative signal has been
	 * observed" policy; this method just carries it through. Both
	 * numbers the model actually tracks are surfaced (sessionOccurrences
	 * and, when present, authoritativeCurrentValue) — no third number
	 * (e.g. a synthesized "before this session" baseline) is invented,
	 * since the model does not track one.
	 */
	private static ReliableCountView buildReliableCountView(SessionSnapshot aggregates)
	{
		if (aggregates == null || aggregates.getReliableCount() == null)
		{
			return null;
		}
		SessionAggregates.ReliableCount rc = aggregates.getReliableCount();
		return new ReliableCountView(rc.getKind(), rc.getSessionOccurrences(), rc.getAuthoritativeCurrentValue());
	}

	/**
	 * LOOT RENDERING: a presentation-only transformation — atomic
	 * {@code LootDropGroup}s (one per observed loot notification) are
	 * summed here by item across every group in the session, purely to
	 * build this snapshot's own new list. The underlying
	 * {@code SessionAggregates.lootDrops} list/its groups are only ever
	 * read (getters), never mutated, added to, or replaced (Test H).
	 * Items are keyed by itemId when present, falling back to itemName
	 * (an item without a resolved id is still summed correctly rather
	 * than creating one row per drop). Sorted by summed quantity
	 * descending, item name ascending as a tie-break, for a stable,
	 * deterministic display order.
	 */
	private static List<LootEntry> buildLootEntries(SessionSnapshot aggregates)
	{
		if (aggregates == null || aggregates.getLootDrops() == null || aggregates.getLootDrops().isEmpty())
		{
			return Collections.emptyList();
		}

		List<SessionAggregates.LootItemAggregate> all = new ArrayList<>();
		for (SessionAggregates.LootDropGroup group : aggregates.getLootDrops())
		{
			if (group != null && group.getItems() != null)
			{
				all.addAll(group.getItems());
			}
		}
		return aggregateLootEntries(all);
	}

	/**
	 * The one item-aggregation/ordering rule for Current Session loot --
	 * shared by the session-wide grid and every Slayer assignment card
	 * (item ID when known, else name; quantity desc, then name).
	 */
	private static List<LootEntry> aggregateLootEntries(Iterable<SessionAggregates.LootItemAggregate> items)
	{
		Map<String, LootAccumulator> byKey = new LinkedHashMap<>();
		for (SessionAggregates.LootItemAggregate item : items)
		{
			if (item == null)
			{
				continue;
			}
			String key = item.getItemId() != null ? ("id:" + item.getItemId()) : ("name:" + item.getItemName());
			LootAccumulator acc = byKey.get(key);
			if (acc == null)
			{
				acc = new LootAccumulator(item.getItemId(), item.getItemName());
				byKey.put(key, acc);
			}
			acc.quantity += item.getQuantity();
		}

		List<LootEntry> entries = new ArrayList<>();
		for (LootAccumulator acc : byKey.values())
		{
			entries.add(new LootEntry(acc.itemId, acc.itemName, acc.quantity));
		}

		entries.sort((a, b) ->
		{
			int byQty = Long.compare(b.getQuantity(), a.getQuantity());
			if (byQty != 0)
			{
				return byQty;
			}
			String nameA = a.getItemName() == null ? "" : a.getItemName();
			String nameB = b.getItemName() == null ? "" : b.getItemName();
			return nameA.compareTo(nameB);
		});

		return Collections.unmodifiableList(entries);
	}

	/** The Slayer assignment ledger (oldest first), or empty for a non-Slayer session. Never null. */
	private static List<SessionAggregates.SlayerAssignmentRecord> slayerLedger(SessionSnapshot snapshot)
	{
		return snapshot.getActivityType() == ActivityType.SLAYER
			? snapshot.getSlayerAssignments()
			: Collections.<SessionAggregates.SlayerAssignmentRecord>emptyList();
	}

	private static List<SlayerAssignmentView> buildSlayerAssignmentViews(SessionSnapshot snapshot)
	{
		List<SessionAggregates.SlayerAssignmentRecord> ledger = slayerLedger(snapshot);
		List<SlayerAssignmentView> views = new ArrayList<>();
		for (int i = ledger.size() - 1; i >= 0; i--)
		{
			SessionAggregates.SlayerAssignmentRecord a = ledger.get(i);
			SlayerAssignmentView view = new SlayerAssignmentView(a.getOrdinal(), a.getTaskName(), a.getLatestRemaining(),
				i == ledger.size() - 1, aggregateLootEntries(a.getItems()),
				a.observedUnitsCompleted(), a.getInitialAmount(), a.getEndReason(), a.isBootstrapped());
			view.kills = toInt(a.actualKills());
			view.killsByNpc = a.getKillsByNpc();
			view.superiorsByNpc = a.getSuperiorsByNpc();
			view.breakdownComplete = a.breakdownComplete();
			view.masterId = a.getMasterId();
			view.activeMillis = a.getActiveDurationStartMillis() != null && a.getActiveDurationEndMillis() != null
				? Math.max(0L, a.getActiveDurationEndMillis() - a.getActiveDurationStartMillis()) : null;
			views.add(view);
		}
		return Collections.unmodifiableList(views);
	}

	/** Slayer assignment cards, newest/current first. Empty for non-Slayer sessions. */
	public List<SlayerAssignmentView> getSlayerAssignments()
	{
		return slayerAssignments;
	}

	/**
	 * One Slayer assignment INSTANCE, for one collapsible card.
	 * {@code ordinal} is the instance's stable, per-session identity
	 * (distinct even for two consecutive assignments of the same task).
	 */
	public static final class SlayerAssignmentView
	{
		private final int ordinal;
		private final String taskName;
		private final Integer remaining;
		private final boolean current;
		private final List<LootEntry> lootEntries;
		private final Integer unitsCompleted;
		private final Integer initialAmount;
		private final com.osrstelemetry.plugin.session.SlayerAssignmentEndReason endReason;
		private final boolean bootstrapped;
		private Integer kills;
		private Integer masterId;
		private Long activeMillis;
		private Map<String, Long> killsByNpc;
		private Map<String, Long> superiorsByNpc;
		private boolean breakdownComplete;

		/** ACTUAL kills for this instance (never task units); null when not known (see SlayerAssignmentRecord#actualKills). */
		public Integer getKills()
		{
			return kills;
		}

		/** Owned encounter deaths by exact NPC; null = not tracked (older record). */
		public Map<String, Long> getKillsByNpc()
		{
			return killsByNpc;
		}

		/** Superior creatures by exact NPC; null = not tracked. */
		public Map<String, Long> getSuperiorsByNpc()
		{
			return superiorsByNpc;
		}

		/** The per-NPC breakdown accounts for every actual kill. */
		public boolean isBreakdownComplete()
		{
			return breakdownComplete;
		}

		public Integer getMasterId()
		{
			return masterId;
		}

		/** Active task time of a FINISHED instance; null while current or untracked. */
		public Long getActiveMillis()
		{
			return activeMillis;
		}

		SlayerAssignmentView(int ordinal, String taskName, Integer remaining, boolean current, List<LootEntry> lootEntries,
			Integer unitsCompleted, Integer initialAmount, com.osrstelemetry.plugin.session.SlayerAssignmentEndReason endReason, boolean bootstrapped)
		{
			this.ordinal = ordinal;
			this.taskName = taskName;
			this.remaining = remaining;
			this.current = current;
			this.lootEntries = lootEntries;
			this.unitsCompleted = unitsCompleted;
			this.initialAmount = initialAmount;
			this.endReason = endReason;
			this.bootstrapped = bootstrapped;
		}

		/** Task units completed during this instance ("xN"); null = unknown (legacy record). Never negative. */
		public Integer getUnitsCompleted()
		{
			return unitsCompleted;
		}

		/** Authoritative assigned amount when observed; null when bootstrapped mid-task. */
		public Integer getInitialAmount()
		{
			return initialAmount;
		}

		/** Null while current/in progress or when not authoritatively known. */
		public com.osrstelemetry.plugin.session.SlayerAssignmentEndReason getEndReason()
		{
			return endReason;
		}

		/** True: tracking began mid-task, so xN counts only from Spyglass's own observation. */
		public boolean isBootstrapped()
		{
			return bootstrapped;
		}

		/** True only for REPLACED_EARLY (shown to players as "Skipped"). */
		public boolean isSkipped()
		{
			return endReason == com.osrstelemetry.plugin.session.SlayerAssignmentEndReason.REPLACED_EARLY;
		}

		public int getOrdinal()
		{
			return ordinal;
		}

		public String getTaskName()
		{
			return taskName;
		}

		/** Latest authoritative remaining (0 = completed); null when unknown. */
		public Integer getRemaining()
		{
			return remaining;
		}

		/** True for the session's current (latest) assignment. */
		public boolean isCurrent()
		{
			return current;
		}

		/** Unmodifiable, same aggregation/ordering as {@link #getLootEntries()} of the session. */
		public List<LootEntry> getLootEntries()
		{
			return lootEntries;
		}
	}

	private static final class LootAccumulator
	{
		private final Integer itemId;
		private final String itemName;
		private long quantity;

		private LootAccumulator(Integer itemId, String itemName)
		{
			this.itemId = itemId;
			this.itemName = itemName;
		}
	}

	public boolean isPresent()
	{
		return present;
	}

	public String getSessionId()
	{
		return sessionId;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public ActivityType getActivityType()
	{
		return activityType;
	}

	public SessionState getState()
	{
		return state;
	}

	public long getActiveDurationMillis()
	{
		return activeDurationMillis;
	}

	public List<XpEntry> getXpEntries()
	{
		return xpEntries;
	}

	/**
	 * The XP cards to display under the given Slayer XP scope: the
	 * current-assignment cards for a Slayer session in CURRENT_TASK scope
	 * when trustworthy task XP exists, otherwise (ALL_TASKS, any
	 * non-Slayer session, or no task XP tracking) the unchanged
	 * session-wide {@link #getXpEntries()}.
	 */
	/** True when a Current Task XP scope exists (Slayer session whose current assignment tracks its own XP). */
	public boolean hasTaskXpScope()
	{
		return taskXpEntries != null;
	}

	public List<XpEntry> getXpEntries(com.osrstelemetry.plugin.SlayerXpScope scope)
	{
		if (scope == com.osrstelemetry.plugin.SlayerXpScope.CURRENT_TASK && taskXpEntries != null)
		{
			return taskXpEntries;
		}
		return xpEntries;
	}

	/** The session identity's stable activity key (e.g. a structured activity's catalog key); may be null. */
	public String getActivityKey()
	{
		return activityKey;
	}

	/**
	 * PRIMARY TITLE (presentation only, from the already-decided session
	 * identity): a structured activity or a boss IS what the player is
	 * doing, so its name owns the title -- the XP it happens to produce
	 * (Hitpoints, Magic ...) belongs in the XP section. Slayer keeps its
	 * task-oriented "Slayer" title; generic combat and skilling keep the
	 * skill-led title (the trained skill is the point of those sessions).
	 */
	public String getHeadline()
	{
		if (isActivityHeadline())
		{
			return displayName == null ? "Unknown activity" : displayName;
		}
		String primarySkill = getPrimarySkill();
		if (primarySkill != null)
		{
			return toTitleCase(primarySkill);
		}
		return displayName == null ? "Unknown activity" : displayName;
	}

	/** True when the title is the activity itself (structured activity or boss), never a skill. */
	public boolean isActivityHeadline()
	{
		return activityType == ActivityType.STRUCTURED_ACTIVITY || activityType == ActivityType.BOSSING;
	}

	private static String toTitleCase(String upper)
	{
		if (upper.isEmpty())
		{
			return upper;
		}
		return upper.charAt(0) + upper.substring(1).toLowerCase(java.util.Locale.ROOT);
	}

	/** Structured-activity completion label ("Games"), or null when the session has no such metric. */
	public String getCompletionLabel()
	{
		return completionLabel;
	}

	/** Structured-activity completions this session (meaningful only with a non-null label). */
	public int getCompletionCount()
	{
		return completionCount;
	}

	public ReliableCountView getReliableCount()
	{
		return reliableCount;
	}

	public Integer getSlayerProgressDelta()
	{
		return slayerProgressDelta;
	}

	/**
	 * ADDED (live bug-fix pass, PART 6/7 -- Slayer "task remaining"
	 * display). The LATEST authoritative currentRemaining observed this
	 * session (an absolute task-state number), or null when none has
	 * been observed yet. This -- not {@link #getSlayerProgressDelta()}
	 * -- is what {@code CurrentSessionView} now renders as the primary
	 * live Slayer number.
	 */
	public Integer getSlayerCurrentRemaining()
	{
		return slayerCurrentRemaining;
	}

	/**
	 * ADDED (Slayer task-complete presentation pass). True only when this
	 * session has genuinely observed the assigned Slayer task reach
	 * exactly zero remaining ({@link #getSlayerCurrentRemaining()} == 0)
	 * -- never null, never negative (this field only ever holds an
	 * authoritative currentRemaining or null; see
	 * {@code SessionAggregates#latestSlayerCurrentRemaining}'s own
	 * javadoc). {@code CurrentSessionView} uses this -- not a raw
	 * {@code == 0} check duplicated in the Swing layer -- to decide
	 * whether to render the compact "Task complete / Grab a new task"
	 * state instead of the numeric remaining-count row. Deliberately
	 * does not affect session lifecycle/state in any way -- a completed
	 * task does not finalize or otherwise change the session; loot/XP
	 * arriving afterward keeps belonging to this same session exactly as
	 * before this pass.
	 */
	public boolean isSlayerTaskComplete()
	{
		return slayerCurrentRemaining != null && slayerCurrentRemaining == 0;
	}

	/**
	 * REMOVED (Slayer task-unit/physical-kill semantics audit): this
	 * snapshot no longer exposes a Slayer "kills/hr" figure.
	 * {@link #getSlayerProgressDelta()} is a Slayer TASK-UNIT total, not
	 * a physical NPC-kill count -- game mechanics can make one physical
	 * kill consume more or fewer than one task unit (e.g. an
	 * expeditious-bracelet-style proc, or a bracelet-of-slaughter-style
	 * save), so a rate derived from it is not a "kills/hr" figure and
	 * must not be labeled or rendered as one. No reliable, independent
	 * physical-player-kill signal exists for an ordinary Slayer
	 * session (see NpcDeathCollector's own javadoc for why NPC_DEATH
	 * cannot be used for this), so the honest choice is to omit the
	 * figure entirely rather than mislabel task units as kills or
	 * fabricate a substitute. {@link #getSlayerProgressDelta()} itself
	 * remains available for task-unit-based calculations (e.g.
	 * {@link #getEstimatedSlayerXpRemaining()}), which stay valid since
	 * both their numerator and denominator are consistently task units.
	 */

	/**
	 * ADDED (Spyglass Phase 1C). Minimum Slayer task units observed
	 * before {@link #getEstimatedSlayerXpRemaining()} will produce a
	 * value -- a single task unit's XP delta could be a boundary
	 * artifact (e.g. XP that landed just before this session's own
	 * first qualifying evidence arrived), so the average is only
	 * trusted once it is drawn from more than one independent sample.
	 * Named for task units, not kills: {@link #getSlayerProgressDelta()}
	 * is a task-unit total, never a physical NPC-kill count -- see that
	 * field's own javadoc.
	 */
	public static final int MIN_SLAYER_TASK_UNITS_FOR_XP_ESTIMATE = 2;

	/**
	 * ADDED (Spyglass Phase 1C). Estimated Slayer XP remaining on the
	 * current task, computed ENTIRELY from THIS session's own
	 * already-observed data: (Slayer XP gained this session / Slayer
	 * task units consumed this session -- the same
	 * {@link #getSlayerProgressDelta()} counter, a TASK-UNIT total,
	 * never a physical NPC-kill count) multiplied by
	 * {@link #getSlayerCurrentRemaining()}. Both the denominator and the
	 * multiplied remaining value are task units, so this calculation
	 * stays correct even when a physical kill consumes more or fewer
	 * than one task unit (e.g. an expeditious-bracelet-style proc or a
	 * bracelet-of-slaughter-style save) -- it naturally reflects the
	 * observed XP-per-task-unit rate rather than assuming
	 * XP-per-physical-kill. Never a hardcoded/Wiki per-monster XP
	 * table -- this project has no such table and must never invent
	 * one; a task with genuinely variable XP per task unit simply
	 * yields a rougher estimate, which is exactly why the panel presents
	 * this as an approximation ("~", not an exact figure). Null (render
	 * nothing) whenever there are fewer than
	 * {@link #MIN_SLAYER_TASK_UNITS_FOR_XP_ESTIMATE} task units
	 * consumed, no remaining count (including an already-complete task,
	 * which is exactly {@code remaining == 0}), or no Slayer XP has
	 * actually been observed yet this session.
	 */
	public Long getEstimatedSlayerXpRemaining()
	{
		// Per-ASSIGNMENT units (see slayerAssignmentBaselineProgressDelta):
		// identical to slayerProgressDelta for a single-assignment session.
		Integer assignmentUnits = slayerProgressDelta == null ? null
			: slayerProgressDelta - (slayerAssignmentBaselineProgressDelta == null ? 0 : slayerAssignmentBaselineProgressDelta);
		if (assignmentUnits == null || assignmentUnits < MIN_SLAYER_TASK_UNITS_FOR_XP_ESTIMATE
			|| slayerCurrentRemaining == null || slayerCurrentRemaining <= 0)
		{
			return null;
		}

		long slayerXpGained = 0L;
		for (XpEntry entry : xpEntries)
		{
			if ("SLAYER".equals(entry.getSkill()))
			{
				slayerXpGained = entry.getXpGained();
				break;
			}
		}
		slayerXpGained -= slayerAssignmentBaselineSlayerXp == null ? 0L : slayerAssignmentBaselineSlayerXp;
		if (slayerXpGained <= 0L)
		{
			return null;
		}

		double averageXpPerTaskUnit = (double) slayerXpGained / assignmentUnits;
		long estimatedRemaining = Math.round(averageXpPerTaskUnit * slayerCurrentRemaining);
		return estimatedRemaining <= 0L ? null : estimatedRemaining;
	}

	/**
	 * ADDED (Spyglass Phase 1D); CORRECTED (Slayer task-unit/physical-
	 * kill semantics audit): the reliable session PHYSICAL-KILL count
	 * the Current Session loot header ("LOOT &#215;36") should show, or
	 * null when nothing reliable is available -- never a fabricated
	 * "0" and never a Slayer task-unit total standing in for a kill
	 * count. Slayer task units ({@link #getSlayerProgressDelta()}) are
	 * deliberately NOT used here: game mechanics can make one physical
	 * kill consume more or fewer than one task unit (see that field's
	 * own javadoc), so a task-unit total is not a physical-kill count
	 * and must never be rendered as "&#215;N". Returns the existing
	 * reliable-count mechanism (currently BOSS_KILL-sourced -- see
	 * {@code SessionAggregates.ReliableCount}'s own javadoc) when one is
	 * available for this session, and null otherwise -- an ordinary
	 * Slayer session with no independently-authoritative kill count
	 * renders a plain "LOOT" header, never a fabricated or mislabeled
	 * count. Introduces no new counter of its own.
	 */
	public Integer getLootHeaderKillCount()
	{
		if (reliableCount != null && reliableCount.getSessionOccurrences() > 0)
		{
			return reliableCount.getSessionOccurrences();
		}
		return null;
	}

	public List<LootEntry> getLootEntries()
	{
		return lootEntries;
	}

	/** See {@link #currentNpcName}'s own javadoc. */
	public String getCurrentNpcName()
	{
		return currentNpcName;
	}

	/** See {@link #currentNpcName}'s own javadoc (same target's NPC id). */
	public Integer getCurrentNpcId()
	{
		return currentNpcId;
	}

	/**
	 * ADDED (Current Session UI facelift, PART 1/16). Which single skill
	 * should headline the activity header as the session's primary
	 * signifier. Derived purely from data this snapshot already carries
	 * (activityType + xpEntries, already sorted by XP gained descending)
	 * -- no new session/classifier state, no hardcoded boss/activity
	 * name table.
	 *
	 * SLAYER: always "SLAYER", regardless of which combat skills are
	 * actually gaining XP underneath -- the task assignment is the
	 * player-facing point of a Slayer session.
	 *
	 * COMBAT/BOSSING: the highest-XP entry among {@link #CORE_COMBAT_SKILLS}
	 * that is actually gaining XP this session -- deliberately excluding
	 * Hitpoints (see that constant's own javadoc for why). Falls back to
	 * the single highest-XP entry of any kind if no core combat skill has
	 * session XP yet (e.g. the very first moments of a fresh BOSSING
	 * session) -- never fabricates a skill that isn't in xpEntries.
	 *
	 * SKILLING (and any other/null activityType): the single highest-XP
	 * entry, since a skilling session ordinarily trains exactly one
	 * skill.
	 *
	 * Null only when xpEntries is empty and this is not a SLAYER session
	 * -- {@code CurrentSessionView} omits the header's skill-icon slot
	 * entirely in that case, per the task's empty-state discipline.
	 */
	public String getPrimarySkill()
	{
		if (activityType == ActivityType.SLAYER)
		{
			return "SLAYER";
		}

		if (xpEntries.isEmpty())
		{
			return null;
		}

		if (activityType == ActivityType.COMBAT || activityType == ActivityType.BOSSING)
		{
			for (XpEntry entry : xpEntries)
			{
				if (CORE_COMBAT_SKILLS.contains(entry.getSkill()))
				{
					return entry.getSkill();
				}
			}
		}

		return xpEntries.get(0).getSkill();
	}

	/** One nonzero-XP skill row, with an optional (threshold-gated) hourly rate. */
	public static final class XpEntry
	{
		private final String skill;
		private final long xpGained;
		private final Long xpPerHour;
		private final LevelProgress levelProgress;
		private final Long rateWarmupRemainingMillis;

		XpEntry(String skill, long xpGained, Long xpPerHour, LevelProgress levelProgress)
		{
			this(skill, xpGained, xpPerHour, levelProgress, null);
		}

		XpEntry(String skill, long xpGained, Long xpPerHour, LevelProgress levelProgress, Long rateWarmupRemainingMillis)
		{
			this.skill = skill;
			this.xpGained = xpGained;
			this.xpPerHour = xpPerHour;
			this.levelProgress = levelProgress;
			this.rateWarmupRemainingMillis = rateWarmupRemainingMillis;
		}

		/** Active time still needed before XP/hr is shown; null once it is (or not applicable). */
		public Long getRateWarmupRemainingMillis()
		{
			return rateWarmupRemainingMillis;
		}

		public String getSkill()
		{
			return skill;
		}

		public long getXpGained()
		{
			return xpGained;
		}

		/** Null when the session's active duration is below {@link #MIN_DURATION_FOR_RATE_MILLIS}. */
		public Long getXpPerHour()
		{
			return xpPerHour;
		}

		/**
		 * ADDED (Current Session XP level-progress pass). Null only when no
		 * absolute XP has ever been observed this session for this skill
		 * (see {@link CurrentSessionSnapshot#buildXpEntries} -- in practice
		 * this cannot happen for a skill that already has nonzero session
		 * XP, since gaining XP requires at least one StatChanged
		 * observation, and every such observation unconditionally feeds
		 * {@code SessionRuntimeCoordinator.noteAbsoluteXp()} -- this is
		 * simply defense in depth for an otherwise-impossible gap).
		 */
		public LevelProgress getLevelProgress()
		{
			return levelProgress;
		}
	}

	/** The one reliable-count row, when a qualifying authoritative signal has been observed this session. */
	public static final class ReliableCountView
	{
		private final SessionAggregates.ReliableCountKind kind;
		private final int sessionOccurrences;
		private final Integer authoritativeCurrentValue;

		ReliableCountView(SessionAggregates.ReliableCountKind kind, int sessionOccurrences, Integer authoritativeCurrentValue)
		{
			this.kind = kind;
			this.sessionOccurrences = sessionOccurrences;
			this.authoritativeCurrentValue = authoritativeCurrentValue;
		}

		public SessionAggregates.ReliableCountKind getKind()
		{
			return kind;
		}

		/** How many of these occurred DURING this session — the only count safe to label a session statistic. */
		public int getSessionOccurrences()
		{
			return sessionOccurrences;
		}

		/** The raw account-wide absolute value last observed, if the source event carried one. May be null. */
		public Integer getAuthoritativeCurrentValue()
		{
			return authoritativeCurrentValue;
		}
	}

	/**
	 * One display-aggregated loot row — quantities of the same item
	 * summed across every atomic loot group this session.
	 *
	 * IMPLEMENTS LootPricing.Priceable (Spyglass Phase 2): lets
	 * {@code LootPricing.valueAndSort}/its callers treat this and the
	 * persistent Loot Tracker's own {@code LootTrackerSnapshot.ItemEntry}
	 * identically — see that interface's own javadoc for why this is the
	 * one shared valuation/sort implementation rather than a duplicated
	 * one.
	 */
	public static final class LootEntry implements com.osrstelemetry.plugin.ui.LootPricing.Priceable
	{
		private final Integer itemId;
		private final String itemName;
		private final long quantity;

		LootEntry(Integer itemId, String itemName, long quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}

		public Integer getItemId()
		{
			return itemId;
		}

		public String getItemName()
		{
			return itemName;
		}

		public long getQuantity()
		{
			return quantity;
		}
	}

	/**
	 * ADDED (Current Session XP level-progress pass). Compact,
	 * read-only account-level progression for one skill, derived from
	 * that skill's real absolute XP via RuneLite's own {@link Experience}
	 * table (see {@link #computeLevelProgress(long)}). Never a durable
	 * session aggregate -- purely a UI-shaped derivation recomputed
	 * fresh every {@link #from(SessionSnapshot, Instant)} call, exactly
	 * like every other field on this class.
	 */
	public static final class LevelProgress
	{
		private final int currentLevel;
		private final boolean maxLevel;
		private final Integer progressPercent;
		private final Long xpRemainingToNextLevel;

		LevelProgress(int currentLevel, boolean maxLevel, Integer progressPercent, Long xpRemainingToNextLevel)
		{
			this.currentLevel = currentLevel;
			this.maxLevel = maxLevel;
			this.progressPercent = progressPercent;
			this.xpRemainingToNextLevel = xpRemainingToNextLevel;
		}

		/** The real (never virtual) current level, 1-99. */
		public int getCurrentLevel()
		{
			return currentLevel;
		}

		/** True at real level 99 -- {@link #getProgressPercent()}/{@link #getXpRemainingToNextLevel()} are null whenever this is true. */
		public boolean isMaxLevel()
		{
			return maxLevel;
		}

		/** 0-100, progress toward {@link #getCurrentLevel()} + 1. Null when {@link #isMaxLevel()}. */
		public Integer getProgressPercent()
		{
			return progressPercent;
		}

		/** Exact XP still needed to reach {@link #getCurrentLevel()} + 1. Null when {@link #isMaxLevel()}. */
		public Long getXpRemainingToNextLevel()
		{
			return xpRemainingToNextLevel;
		}
	}
}
