package com.osrstelemetry.plugin.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The small, explicit, account-specific transient state
 * ActivitySignalClassifier needs beyond the current Session itself --
 * today, "is there a reliable active Slayer task, and what is it" plus
 * (boss self-consumption of its own Slayer task) a single pending
 * "boss task candidate" watch. Deliberately:
 *
 *   - NOT a static/global -- one instance per account, owned by
 *     whatever runtime coordinator wires the classifier to real
 *     events. Two accounts must never share one
 *     ClassifierContext instance.
 *   - NOT backed by SlayerState.json or any other on-disk file, and
 *     NEVER reads Python/account_state.json -- this is purely
 *     in-memory classifier working state, populated only from the
 *     SessionSignal stream the classifier itself already processes.
 *   - Reset()-able so a future account switch can cleanly discard one
 *     account's classifier context and start a fresh one for another,
 *     exactly as SessionLifecycleEngine/SessionPersistence already do
 *     per account.
 *
 * PENDING BOSS TASK CANDIDATE (boss
 * self-consumption of its own Slayer task; extended by the
 * CANDIDATE-METRIC BUFFERING section below -- see
 * BossTaskAffinity and ActivitySignalClassifier.classifySlayerTaskProgress()'s
 * own javadoc for the full rationale): armed when a SLAYER_TASK_PROGRESS
 * names the currently-ACTIVE boss's OWN known Slayer task -- an event
 * that could EITHER be that boss's own kill (self-consumption -- the
 * session must stay BOSSING) OR a genuinely different, ordinary NPC for
 * that same task (a real switch to SLAYER). Both produce an identical
 * event, so neither can be told apart from that one signal alone; the
 * disambiguating evidence (a same-boss BOSS_KILL confirming
 * self-consumption, or a different-NPC SERVER_NPC_LOOT confirming a
 * real switch) arrives separately, sometimes several seconds and one or
 * more game ticks later -- see ActivitySignalClassifier.classifyBossKill()/
 * classifyServerNpcLoot() for the two ways this is resolved, and
 * SessionSignalBatchResolver.applyBossSelfConsumptionCorroboration() for
 * the SAME-tick, order-independent half of the same fix.
 *
 * CANDIDATE-METRIC BUFFERING ("ambiguous kill's own
 * progress metric stranded on the boss session after a later-confirmed
 * real switch"): the originating SLAYER_TASK_PROGRESS event's own
 * MetricUpdate (the Slayer progress delta + currentRemaining) is now
 * held HERE, in pendingBossTaskCandidateMetrics, rather than being
 * applied to any session at the moment it arrives. It is applied
 * EXACTLY ONCE, by whichever caller resolves the candidate:
 *   - self-consumption confirmed (classifyBossKill()): combined with
 *     that same-boss kill's own metric and applied to the session that
 *     never left BOSSING -- a delayed-but-correct application, never a
 *     retroactive correction of anything.
 *   - a real switch confirmed (classifyServerNpcLoot() /
 *     applyBossSelfConsumptionCorroboration()): combined with the
 *     confirming loot's own metric and applied to the brand-new Slayer
 *     session -- so that session's slayerProgressDelta/latestSlayerCurrentRemaining
 *     are correct immediately upon confirmation, never waiting for a
 *     second kill.
 *   - never resolved (the boss session ends for an unrelated reason --
 *     times out to SUSPENDED, or the plugin/account switches -- before
 *     either half above arrives): SessionRuntimeCoordinator commits
 *     these buffered metrics, exactly once, to whatever session is
 *     legitimately current at that point (see its own
 *     flushAbandonedBossTaskCandidateIfMatching()), mirroring
 *     SessionLifecycleEngine's own pendingCandidateMetrics precedent for
 *     its unrelated (SUSPENDED weak-evidence) candidate mechanism. This
 *     is a plain List, not a single value, for the same reason: a
 *     second own-task progress event can legitimately arrive before the
 *     first is resolved (kill-message delay is exactly what this whole
 *     fix is about) -- armBossTaskCandidate() APPENDS to this list for
 *     the SAME boss rather than replacing it, so no earlier kill's own
 *     metric is ever silently dropped.
 *
 * Holding only ONE candidate WATCH at a time (the identity pair is
 * armed/replaced, never a list) is intentional: a boss is fought one
 * kill at a time, and each kill's own progress event is independently
 * resolved (confirmed or aborted) well before the next one in ordinary
 * play -- but its buffered METRICS (plural) accumulate for as long as
 * the watch stays open, so consecutive own-task-progress events before
 * resolution are never lost (see above).
 */
public final class ClassifierContext
{
	private String activeSlayerTaskName;
	private String activeSlayerTaskLocation;

	/**
	 * Fixes a reproducible SLAYER_TASK_COMPLETED remaining=1 bug. The most
	 * recently observed authoritative currentRemaining for
	 * activeSlayerTaskName/activeSlayerTaskLocation above (from the
	 * latest SLAYER_TASK_PROGRESS that carried one -- see
	 * onSlayerTaskProgress() below), or null when no PROGRESS event for
	 * the currently-tracked task has ever carried a currentRemaining.
	 * Consulted ONLY by ActivitySignalClassifier.classifySlayerTaskCompleted()
	 * to reconcile the exact outstanding task unit(s) no trailing
	 * PROGRESS event ever reported -- see that method's own javadoc for
	 * the full rationale. Cleared (never carried across tasks) whenever
	 * the tracked task itself changes (a fresh SLAYER_TASK_ASSIGNED) or
	 * completes (onSlayerTaskCompleted()).
	 */
	private Integer lastKnownSlayerCurrentRemaining;

	/**
	 * The exact ABSOLUTE XP value of every skill at the
	 * precise moment the CURRENTLY-current session (xpBoundaryOwnerSessionId)
	 * itself began -- i.e. a per-skill "session start baseline," captured
	 * ONCE by SessionRuntimeCoordinator (see its own
	 * noteSessionSwitchXpBaseline() call site) at the exact instant a
	 * real session switch is detected, from whatever absolute XP values
	 * it has most recently observed via SkillsCollector's raw
	 * StatChanged stream (the SAME underlying observations LiveXpTracker's
	 * own pending deltas are built from -- see SessionRuntimeCoordinator's
	 * own lastKnownAbsoluteXpBySkill javadoc).
	 *
	 * WHY THIS EXISTS: an XP_CHANGE's own aggregation window can SPAN a
	 * real session switch (window opens under the OLD session, the
	 * switch happens mid-window, the window doesn't close/flush until
	 * AFTER it) -- see classifyXpChange()'s own javadoc for the full
	 * boundary-ownership rule this baseline makes possible. Given this
	 * baseline (xp at the moment THIS session started) and the flushed
	 * event's own absolute newXp (SessionSignal#getXpNewValue()), the
	 * portion of that window's delta earned AFTER the switch is exactly
	 * `newXp - xpAtSessionStart` -- never a proportional/time-based
	 * guess, and never assigning any pre-switch XP to this session.
	 *
	 * SINGLE-OWNER MODEL (mirrors LiveXpTracker's own ownerSessionId
	 * discipline): holds a baseline for exactly ONE session id at a
	 * time -- the most recent session a real switch established. A
	 * refineIdentity() re-key (the SAME continuous session, same id)
	 * never touches this; only a genuine new-session-id switch replaces
	 * it (see SessionRuntimeCoordinator's own call site). Never
	 * persisted, never durable -- purely in-memory classifier working
	 * state, reset() on account switch exactly like every other field on
	 * this class.
	 */
	private String xpBoundaryOwnerSessionId;
	private final Map<String, Long> xpAtSessionStartBySkill = new HashMap<>();

	private ActivityIdentity pendingBossTaskCandidateBossIdentity;
	private ActivityIdentity pendingBossTaskCandidateSlayerIdentity;
	private final List<MetricUpdate> pendingBossTaskCandidateMetrics = new ArrayList<>();

	/**
	 * A small, bounded,
	 * SLIDING-WINDOW "what attackable NPC did the local player most
	 * recently interact with" watch, populated only from
	 * SignalKind.NPC_INTERACTION_TARGET (see that constant's own
	 * javadoc) -- never itself lifecycle-carrying, never persisted
	 * (same "purely in-memory working state" discipline as
	 * activeSlayerTaskName/activeSlayerTaskLocation above).
	 *
	 * WHY SLIDING, NOT A FIXED TTL FROM THE ORIGINAL CLICK: a genuinely
	 * continuous fight against one stationary NPC target does not
	 * necessarily re-fire InteractingChanged at all (RuneLite only
	 * posts it when Actor#getInteracting() actually CHANGES) -- a fixed
	 * TTL anchored at the original interaction would then silently
	 * expire mid-fight even though the player never disengaged. Every
	 * combat-skill RAW_COMBAT_XP_OBSERVED event that actually CONSUMES
	 * this context (see consumeRecentTargetNpcNameIfFresh() below,
	 * called only from ActivitySignalClassifier's RAW_COMBAT_XP_OBSERVED
	 * branch) refreshes recentTargetLastSeenTick/At to that event's own
	 * tick/instant -- exactly the same sliding-window idea
	 * SessionLifecycleEngine's own lastActiveAt/SUSPEND_TIMEOUT already
	 * uses for session activity, just applied to this much smaller,
	 * short-lived piece of context.
	 *
	 * WHY XP_CHANGE NO LONGER REFRESHES THIS WATCH: XP_CHANGE is SkillsCollector's own AGGREGATED event,
	 * only emitted on its slow flush cadence (XP_FLUSH_INTERVAL_TICKS --
	 * ~50 ticks / ~30 real seconds), which can easily span a genuine
	 * target change or a genuine disengagement. Letting a signal on that
	 * coarse a cadence REFRESH this watch's freshness allowed a stale
	 * NPC identity to self-perpetuate for cycle after cycle even once
	 * the player had genuinely stopped fighting it -- a real bug. Only
	 * RAW_COMBAT_XP_OBSERVED (immediate, per-hit-cadence, tightly
	 * coupled to real, current combat activity -- see its own javadoc)
	 * is trusted to refresh this watch now; XP_CHANGE's own combat
	 * branch (ActivitySignalClassifier.classifyXpChange()) instead calls
	 * peekRecentTargetNpcNameIfFresh() below, a READ-ONLY twin that can
	 * still see a still-fresh watch (typically kept fresh by
	 * RAW_COMBAT_XP_OBSERVED pulses arriving throughout the same fight)
	 * but can never itself extend its life.
	 *
	 * THE BOUNDED RULE (documented here, exercised in
	 * ClassifierContextTest / ActivitySignalClassifierTest): valid for
	 * RECENT_TARGET_MAX_AGE_TICKS (20 game ticks, ~12 seconds at
	 * 0.6s/tick) since it was last established/refreshed, compared via
	 * gameTick WHENEVER both the stored watch and the querying signal
	 * carry one (the normal, real-runtime case -- SessionRuntimeCoordinator
	 * tags every real signal with client.getTickCount()). When EITHER
	 * side lacks a gameTick (a synthetic/test signal, or a real signal
	 * built off the client thread -- see SessionRuntimeCoordinator.safeTickCount()),
	 * the same ~12-second bound (RECENT_TARGET_MAX_AGE) is applied to
	 * wall-clock observedAt instead. This is generous enough to survive
	 * a normal weapon's attack-speed gap between hits (even a slow ~6-7
	 * tick weapon) and any brief tick or two where RuneLite reports no
	 * interacting target between individual attacks, while still being
	 * short enough that combat XP arriving long after the player
	 * genuinely moved on is never mislabeled with a stale NPC name.
	 * Deliberately NOT a probabilistic/decaying score -- a single hard
	 * bound, checked the same way every time.
	 */
	static final long RECENT_TARGET_MAX_AGE_TICKS = 20L;
	static final Duration RECENT_TARGET_MAX_AGE = Duration.ofSeconds(12);

	private Integer recentTargetNpcId;
	private String recentTargetNpcName;
	private Long recentTargetLastSeenTick;
	private Instant recentTargetLastSeenAt;

	/**
	 * CONTEXT ONLY. Tracks the account's currently-assigned/
	 * currently-progressing Slayer task purely as background context --
	 * it is NOT, by itself, evidence of what the player is presently
	 * doing. The bug this fixes: ActivitySignalClassifier's
	 * classifyXpChange() used to read this state directly and promote
	 * ANY ordinary combat XP into that task's own SLAYER identity
	 * whenever a task was merely assigned -- so a player who had
	 * Gargoyles assigned, then fought an unrelated Desert Wolf and Goat,
	 * had their session silently hijacked into SLAYER/Gargoyles with zero
	 * Gargoyles ever fought. That read has been REMOVED (see
	 * classifyXpChange()'s own javadoc); this state is no longer
	 * consulted by ActivitySignalClassifier at all. It remains here,
	 * updated exactly as before, purely because it is harmless,
	 * genuinely transient context that some future consumer of
	 * ClassifierContext could legitimately want -- the account's own
	 * "currently assigned task" is ALSO independently tracked outside
	 * this classifier entirely (SlayerCollector's own persisted state
	 * backs the Slayer panel UI, and NpcDeathCollector annotates
	 * NPC_DEATH payloads with it) -- those paths are untouched by this
	 * fix and remain the source of truth for "what task is assigned"
	 * wherever that context is legitimately needed. The ONLY path that
	 * may ever establish or upgrade a SLAYER identity is genuine,
	 * specific Slayer lifecycle evidence -- SLAYER_TASK_PROGRESS, via
	 * classifySlayerTaskProgress() -- never mere assignment/tracking.
	 */
	public void onSlayerTaskAssigned(String taskName, String taskLocation)
	{
		this.activeSlayerTaskName = taskName;
		this.activeSlayerTaskLocation = taskLocation;
		// A fresh assignment is a DIFFERENT task -- any
		// remaining tracked for whatever task preceded it must never
		// leak into this new task's own completion reconciliation.
		this.lastKnownSlayerCurrentRemaining = null;
	}

	/**
	 * `currentRemaining` (nullable -- absent for older/synthetic
	 * callers) is this SAME progress event's own authoritative
	 * currentRemaining, retained here exactly like
	 * SessionAggregates.latestSlayerCurrentRemaining is at the
	 * aggregate layer -- OVERWRITTEN with the latest value, never
	 * summed. A null currentRemaining leaves whatever was already
	 * tracked untouched (never reset to null just because a later
	 * event happened not to carry one).
	 */
	public void onSlayerTaskProgress(String taskName, String taskLocation, Integer currentRemaining)
	{
		this.activeSlayerTaskName = taskName;
		this.activeSlayerTaskLocation = taskLocation;
		if (currentRemaining != null)
		{
			this.lastKnownSlayerCurrentRemaining = currentRemaining;
		}
	}

	/** The task is done -- clear the tracked context. */
	public void onSlayerTaskCompleted()
	{
		this.activeSlayerTaskName = null;
		this.activeSlayerTaskLocation = null;
		this.lastKnownSlayerCurrentRemaining = null;
	}

	/**
	 * See onSlayerTaskAssigned()'s own javadoc: CONTEXT ONLY, no longer
	 * read anywhere in ActivitySignalClassifier. Retained/updated for any future legitimate
	 * consumer; never a substitute for genuine SLAYER_TASK_PROGRESS
	 * evidence.
	 */
	public boolean hasReliableActiveSlayerTask()
	{
		return activeSlayerTaskName != null;
	}

	/** See hasReliableActiveSlayerTask()'s own javadoc. */
	public String getActiveSlayerTaskName()
	{
		return activeSlayerTaskName;
	}

	/** See hasReliableActiveSlayerTask()'s own javadoc. */
	public String getActiveSlayerTaskLocation()
	{
		return activeSlayerTaskLocation;
	}

	/**
	 * See lastKnownSlayerCurrentRemaining's own field javadoc. Null
	 * when no PROGRESS event for the currently-tracked task has ever
	 * carried a currentRemaining.
	 */
	public Integer getLastKnownSlayerCurrentRemaining()
	{
		return lastKnownSlayerCurrentRemaining;
	}

	/**
	 * Called ONLY from
	 * SessionRuntimeCoordinator, exactly once, at the precise moment it
	 * detects a genuine session-identity switch (a brand-new session id
	 * becoming current) -- never for a refineIdentity() re-key of the
	 * SAME session. Replaces whatever baseline was previously held
	 * (single-owner model -- see this class's own xpBoundaryOwnerSessionId
	 * javadoc) with a defensive copy of `xpBySkill`, owned by
	 * `sessionId` from this point on.
	 */
	void noteSessionSwitchXpBaseline(String sessionId, Map<String, Long> xpBySkill)
	{
		this.xpBoundaryOwnerSessionId = sessionId;
		this.xpAtSessionStartBySkill.clear();
		if (xpBySkill != null)
		{
			this.xpAtSessionStartBySkill.putAll(xpBySkill);
		}
	}

	/**
	 * The exact absolute XP value `skill` had at the moment `sessionId`
	 * itself began -- see this class's own xpBoundaryOwnerSessionId
	 * javadoc -- or null when `sessionId` is not the currently tracked
	 * boundary owner, or no baseline was ever captured for that exact
	 * skill (e.g. it was never observed via a raw StatChanged pulse
	 * before the switch -- see classifyXpChange()'s own javadoc for how
	 * this honest "no exact baseline" case is handled).
	 */
	Long getXpAtSessionStart(String sessionId, String skill)
	{
		if (sessionId == null || !sessionId.equals(xpBoundaryOwnerSessionId))
		{
			return null;
		}
		return xpAtSessionStartBySkill.get(skill);
	}

	/**
	 * Arms (or, for the SAME boss identity, extends) the pending boss
	 * task candidate -- see class javadoc. Called only from
	 * classifySlayerTaskProgress() when the current ACTIVE boss's own
	 * known Slayer task is named by a fresh SLAYER_TASK_PROGRESS event.
	 * `metric` (nullable -- the originating event's own MetricUpdate) is
	 * BUFFERED here, never applied to any session by this call; see
	 * class javadoc's CANDIDATE-METRIC BUFFERING section. A second call
	 * for the SAME bossIdentity APPENDS its metric to the existing
	 * buffer rather than replacing it, so an earlier still-unresolved
	 * kill's own metric is never dropped by a later one.
	 */
	void armBossTaskCandidate(ActivityIdentity bossIdentity, ActivityIdentity candidateSlayerIdentity, MetricUpdate metric)
	{
		this.pendingBossTaskCandidateBossIdentity = bossIdentity;
		this.pendingBossTaskCandidateSlayerIdentity = candidateSlayerIdentity;
		if (metric != null)
		{
			this.pendingBossTaskCandidateMetrics.add(metric);
		}
	}

	/** Clears the pending candidate (identity watch AND its buffered metrics) -- called on confirm (real switch), abort (self-consumption), an explicit forced-resolution flush, or reset(). */
	void clearBossTaskCandidate()
	{
		this.pendingBossTaskCandidateBossIdentity = null;
		this.pendingBossTaskCandidateSlayerIdentity = null;
		this.pendingBossTaskCandidateMetrics.clear();
	}

	/** True only when a candidate is pending for EXACTLY this boss identity. */
	boolean hasBossTaskCandidateFor(ActivityIdentity bossIdentity)
	{
		return pendingBossTaskCandidateBossIdentity != null && pendingBossTaskCandidateBossIdentity.equals(bossIdentity);
	}

	/** The candidate Slayer identity a pending watch would switch to if confirmed, or null if none is pending. */
	ActivityIdentity getPendingBossTaskCandidateSlayerIdentity()
	{
		return pendingBossTaskCandidateSlayerIdentity;
	}

	/**
	 * The buffered MetricUpdate(s) -- possibly from more than one
	 * still-unresolved own-task-progress event -- a pending candidate is
	 * holding, as a defensive copy (never null, empty when nothing is
	 * pending). See class javadoc's CANDIDATE-METRIC BUFFERING section
	 * for exactly when/where these are ultimately applied.
	 */
	List<MetricUpdate> getPendingBossTaskCandidateMetrics()
	{
		return new ArrayList<>(pendingBossTaskCandidateMetrics);
	}

	/**
	 * Called only from
	 * ActivitySignalClassifier.classify()'s NPC_INTERACTION_TARGET
	 * branch. ARMS (a fresh target) or REPLACES (a different target) the
	 * watch, always refreshing the "last seen" tick/instant to THIS
	 * observation's own -- a genuinely new interaction target is itself
	 * evidence the player is right now engaging it. npcName is expected
	 * non-null in practice (the production collector never emits this
	 * signal for a null-named target -- see NpcInteractionTargetCollector),
	 * but this method does not itself validate that; it simply stores
	 * whatever it is given, mirroring onSlayerTaskAssigned()/
	 * onSlayerTaskProgress()'s own "trust the caller" discipline.
	 */
	void onNpcInteractionTarget(Integer npcId, String npcName, Long gameTick, Instant observedAt)
	{
		this.recentTargetNpcId = npcId;
		this.recentTargetNpcName = npcName;
		this.recentTargetLastSeenTick = gameTick;
		this.recentTargetLastSeenAt = observedAt;
	}

	/**
	 * Called only from
	 * ActivitySignalClassifier.classifyXpChange()'s combat branch, for
	 * the one and only signal kind allowed to actually CONSUME (read
	 * AND refresh) this context -- see the field javadoc above for why
	 * this is a sliding window rather than a fixed TTL. Returns the
	 * tracked NPC's display name when the watch is still fresh as of
	 * (currentTick, currentObservedAt) -- refreshing its own last-seen
	 * tick/instant to these as a side effect -- or null when nothing is
	 * tracked, or the tracked watch has aged past RECENT_TARGET_MAX_AGE_TICKS/
	 * RECENT_TARGET_MAX_AGE. A null return leaves the stale watch's
	 * fields untouched (never actively cleared here) -- a fresh
	 * InteractingChanged for a genuinely new target will overwrite them
	 * via onNpcInteractionTarget() regardless, and there is no other
	 * reader of this state that a stale value could mislead in the
	 * meantime.
	 */
	String consumeRecentTargetNpcNameIfFresh(Long currentTick, Instant currentObservedAt)
	{
		if (recentTargetNpcName == null || !isRecentTargetFresh(currentTick, currentObservedAt))
		{
			return null;
		}
		if (currentTick != null)
		{
			this.recentTargetLastSeenTick = currentTick;
		}
		if (currentObservedAt != null)
		{
			this.recentTargetLastSeenAt = currentObservedAt;
		}
		return recentTargetNpcName;
	}

	/**
	 * Called ONLY from
	 * ActivitySignalClassifier.classifyXpChange()'s combat branch (via
	 * genericCombatOrNpcIdentity()) -- the READ-ONLY twin of
	 * consumeRecentTargetNpcNameIfFresh() above. Same exact freshness
	 * rule (see isRecentTargetFresh()), but never mutates
	 * recentTargetLastSeenTick/At as a side effect: the slow, aggregated
	 * XP_CHANGE cadence must be able to SEE a still-fresh watch (kept
	 * fresh by real-time RAW_COMBAT_XP_OBSERVED pulses) without itself
	 * being trusted to EXTEND that freshness -- see the class javadoc's
	 * TEMPORAL-DESIGN FOLLOW-UP FIX section for the full rationale (this
	 * is what stops a stale NPC identity from self-perpetuating merely
	 * because delayed XP_CHANGE events keep arriving after the player
	 * has genuinely stopped fighting).
	 */
	String peekRecentTargetNpcNameIfFresh(Long currentTick, Instant currentObservedAt)
	{
		if (recentTargetNpcName == null || !isRecentTargetFresh(currentTick, currentObservedAt))
		{
			return null;
		}
		return recentTargetNpcName;
	}

	/**
	 * See consumeRecentTargetNpcNameIfFresh()'s and the RECENT_TARGET_MAX_AGE*
	 * field javadoc for the exact bounded rule. Prefers a tick-based
	 * comparison whenever BOTH the stored watch and the query carry a
	 * gameTick (the normal real-runtime case); falls back to a
	 * wall-clock Duration comparison otherwise. An out-of-order query
	 * (currentTick/currentObservedAt earlier than what is already
	 * stored) is conservatively treated as NOT fresh, mirroring
	 * SessionLifecycleEngine's own out-of-order handling elsewhere in
	 * this package.
	 */
	private boolean isRecentTargetFresh(Long currentTick, Instant currentObservedAt)
	{
		if (recentTargetLastSeenTick != null && currentTick != null)
		{
			long ageTicks = currentTick - recentTargetLastSeenTick;
			return ageTicks >= 0 && ageTicks <= RECENT_TARGET_MAX_AGE_TICKS;
		}
		if (recentTargetLastSeenAt == null || currentObservedAt == null)
		{
			return false;
		}
		Duration age = Duration.between(recentTargetLastSeenAt, currentObservedAt);
		return !age.isNegative() && age.compareTo(RECENT_TARGET_MAX_AGE) <= 0;
	}

	/**
	 * The NPC id last
	 * associated with the current recent-target watch, retained purely
	 * as metadata (see EventType.NPC_INTERACTION_TARGET's own javadoc --
	 * ordinary session identity never requires this) -- null when
	 * nothing is currently tracked. Not freshness-gated: callers that
	 * need to know whether the watch is still valid should go through
	 * consumeRecentTargetNpcNameIfFresh() instead.
	 */
	Integer getRecentTargetNpcId()
	{
		return recentTargetNpcId;
	}

	/** Explicit reset for a future account switch. */
	public void reset()
	{
		this.activeSlayerTaskName = null;
		this.activeSlayerTaskLocation = null;
		this.lastKnownSlayerCurrentRemaining = null;
		clearBossTaskCandidate();
		this.recentTargetNpcId = null;
		this.recentTargetNpcName = null;
		this.recentTargetLastSeenTick = null;
		this.recentTargetLastSeenAt = null;
		this.xpBoundaryOwnerSessionId = null;
		this.xpAtSessionStartBySkill.clear();
	}
}
