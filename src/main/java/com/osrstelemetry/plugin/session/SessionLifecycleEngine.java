package com.osrstelemetry.plugin.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Includes
 * refineIdentity() so every transition method also threads a batch's
 * List<MetricUpdate> through to the correct Session -- see
 * LifecycleResult's own javadoc for the exact metricsForCurrent /
 * contextualMetricsTarget / contextualMetrics contract this method
 * now returns. The pure, deterministic ACTIVE -> SUSPENDED ->
 * FINALIZED lifecycle engine.
 *
 * DELIBERATELY OUT OF SCOPE FOR THIS CLASS: this engine never subscribes to any RuneLite event,
 * never inspects EventType/XP_CHANGE/SERVER_NPC_LOOT/NPC_DEATH/
 * SLAYER_TASK_PROGRESS/BOSS_KILL, and never decides what activity is
 * happening. It receives an already-classified ActivityIdentity, a
 * timestamp, and that
 * signal batch's own MetricUpdates -- nothing else -- from
 * ActivitySignalClassifier / SessionSignalBatchResolver via a runtime
 * coordinator this class does not build.  Every method here is driven
 * purely by the Instant arguments passed in; there is no internal
 * wall-clock read (no Instant.now() anywhere in this class), which is
 * what makes it exhaustively unit-testable with synthetic timestamps.
 *
 * THRESHOLDS: SUSPEND_TIMEOUT = 5 minutes of no qualifying activity
 * for the current ACTIVE session; RESUME_WINDOW = 30 minutes after
 * suspension during which a qualifying heartbeat for the SAME
 * activity resumes the same session instead of starting a new one.
 * Both are the exact values approved in the player-facing UI design.
 *
 * ACTIVE-DURATION MODEL: accumulatedActiveDurationMillis
 * only ever grows by Duration.between(session.lastActiveAt, newTimestamp)
 * at the moment a SAME-activity qualifying heartbeat arrives while the
 * session is (or is about to remain) ACTIVE. It is NEVER computed as
 * (now - startedAt), and idle/suspended/offline gaps are never added
 * to it -- see accumulate() below, and suspend()/resume(), neither of
 * which ever touches this field.
 *
 * DETERMINISTIC THRESHOLD TIMESTAMPS: suspendedAt is
 * always exactly lastActiveAt + SUSPEND_TIMEOUT, and a timeout-driven
 * finalizedAt is always exactly resumeWindowExpiresAt -- never
 * whatever "now" a late scheduler/check happened to pass in. This is
 * what makes historical session timestamps stable no matter how late
 * advanceTime()/onQualifyingActivity() are actually invoked in
 * practice.
 *
 * EXACT-BOUNDARY ASYMMETRY (documented deliberately, not a bug): a
 * genuine qualifying activity for the SAME identity arriving exactly
 * AT resumeWindowExpiresAt still resumes the session ("before
 * or at the approved resume-window boundary"), while advanceTime()
 * alone reaching that same instant with no accompanying activity
 * finalizes it ("now >= resumeWindowExpiresAt"). Real activity
 * at an instant takes precedence over the mere absence of activity at
 * that same instant; see onQualifyingActivity()'s use of
 * !isAfter(resumeExpiry) vs. advanceTime()'s use of !isBefore(expiry).
 * refineIdentity() below deliberately follows the exact same
 * !isAfter(resumeExpiry) boundary rule for its own SUSPENDED case, so
 * a refinement-carrying signal at the exact boundary is treated
 * identically to any other genuine same-identity event there ("events observed at
 * timestamp T are processed before timeout advancement for T").
 *
 * OUT-OF-ORDER INPUT: any onQualifyingActivity() call
 * whose observedAt is strictly before the current session's
 * lastActiveAt is rejected outright -- no mutation, no exception,
 * current session returned unchanged, and that rejected batch's own metrics
 * are likewise dropped, never applied anywhere -- an out-of-order
 * batch is treated as entirely unobserved, exactly as it always was
 * for identity/timestamp purposes. This is the literal case of
 * "timestamps older than lastActiveAt" and is sufficient
 * to guarantee accumulatedActiveDurationMillis can never go negative
 * (every elapsed-time computation in this class only ever runs after
 * that guard has already passed). advanceTime() needs no equivalent
 * guard: it never mutates lastActiveAt, and its two threshold
 * comparisons are naturally no-ops if `now` hasn't reached them yet,
 * so calling it with an earlier-than-expected `now` is already a safe
 * no-op by construction. Duplicate timestamps (observedAt ==
 * lastActiveAt) are accepted and add exactly zero elapsed time --
 * idempotent, not a special case. refineIdentity() applies this exact
 * same out-of-order guard.
 *
 * SCOPE NOTE: monotonicity is enforced relative to lastActiveAt only.
 * This engine does not attempt to fully reorder arbitrarily
 * interleaved timestamps from multiple out-of-order sources -- callers
 * are expected to feed observedAt values that are monotonic per their
 * own real event source (RuneLite's own tick-ordered event bus), and
 * this engine's job is to reject genuinely-impossible ordering, not to
 * reconstruct a true order from scrambled input.
 *
 * PROVISIONAL-METRIC OWNERSHIP (guards against unconfirmed candidate
 * metrics duplicated across sessions): the weak-evidence
 * candidate mechanism below (isWeakEvidenceAgainstEstablishedSession())
 * can leave a signal batch's own identity undecided for a real amount
 * of session-time -- the batch merely ARMS or REPLACES a pending
 * candidate rather than being immediately and permanently attributed
 * to whatever session is "current" at that moment. Before this fix,
 * callers applied every batch's metrics to getCurrent() unconditionally,
 * which is correct for every other transition but wrong here: while a
 * candidate is pending, getCurrent() is still the OLD established
 * session, so the candidate batch's own metrics were being permanently
 * misattributed there the instant they arrived -- and then, if the
 * candidate went on to be confirmed, the SAME metrics reappeared
 * (correctly) on the new session too, producing exact duplication
 * (the live-reported "WOODCUTTING=525 in both sessions").
 *
 * The fix keeps a THIRD field, pendingCandidateMetrics, directly next
 * to the existing pendingCandidateIdentity/pendingCandidateFirstSeenAt
 * pair (same lifetime, same reset points -- see clearPendingCandidate())
 * rather than introducing any second, unrelated buffering framework.
 * Every transition method now returns a LifecycleResult carrying
 * metricsForCurrent (this call's own batch, wherever it now definitely
 * belongs) and, when an candidate is ABANDONED as a side effect of this
 * call (confirmed by a different candidate, the original session
 * resuming, an authoritative switch arriving, or the resume window
 * simply expiring), contextualMetricsTarget/contextualMetrics: that
 * abandoned candidate's buffered metrics, committed EXACTLY ONCE to
 * whichever session was established before this call, as plain
 * additive aggregate contributions with zero lifecycle side effects
 * (see SessionAggregateUpdater -- the only thing these are ever routed
 * through). A CONFIRMED candidate's buffered metrics are combined with
 * this batch's own metrics and returned as metricsForCurrent targeting
 * the brand-new session instead -- the old session receives nothing.
 * Ordinary ACTIVE-session transitions and combat-branch/authoritative
 * SUSPENDED switches never touch pendingCandidateMetrics at all beyond
 * defensively flushing it (it is always already empty there in
 * practice), so this added complexity is fully scoped to the existing
 * weak-evidence gate, per invariant "ordinary ACTIVE sessions should
 * not gain this candidate complexity."
 *
 * EVIDENCE-STRENGTH-AWARE WEAK-EVIDENCE GATE, GENERALIZED TO ACTIVE
 * (corrects the very
 * invariant the paragraph above states): live evidence proved that
 * invariant itself was the bug -- a single incidental ORDINARY-strength
 * observation (AGILITY +6 XP) immediately finalized a genuinely ACTIVE,
 * well-within-suspend-timeout SLAYER/Gargoyles session, 34-38 seconds
 * into it. isWeakEvidenceAgainstEstablishedSession() is now ALSO
 * evaluated on the ACTIVE branch (see onQualifyingActivity()'s own
 * javadoc), gated by strength as well as identity type: only
 * EvidenceStrength.ORDINARY evidence against a combat-branch established
 * session is ever weak; AUTHORITATIVE/SPECIFIC evidence (a real
 * BOSS_KILL, an authoritative SLAYER_TASK_PROGRESS, a BOSS_ACTIVITY_CONTEXT)
 * always switches/refines immediately, ACTIVE or SUSPENDED alike. A SEPARATE candidate trio
 * (activeCandidateIdentity/activeCandidateFirstSeenAt/activeCandidateMetrics,
 * declared just below the SUSPENDED trio) backs the ACTIVE gate --
 * entirely distinct fields, never shared with the SUSPENDED ones -- and,
 * per an explicit design correction, anchors a CONFIRMED switch at the
 * CONFIRMING observation's own instant (T2), never the candidate's
 * first-seen instant (T1): the established session was genuinely still
 * ACTIVE for the whole T1..T2 gap, so finalizing/starting anything at T1
 * would fabricate a boundary the evidence never actually proved. This is
 * the one place this class's ACTIVE and SUSPENDED candidate mechanisms
 * deliberately behave DIFFERENTLY -- see onQualifyingActivity()'s own
 * javadoc for the full rationale, and the ACTIVE-CANDIDATE field javadoc
 * for why T1 is still recorded but never used to anchor anything.
 *
 * EVIDENCE-WEIGHTED HYSTERESIS (fixes "session ownership/hysteresis is too sticky,
 * equally so for ACTIVE and SUSPENDED"). Live evidence: a SUSPENDED
 * BOSSING/Zulrah session survived 4 wolves, a camel, 3 goats, AND an
 * entire inventory of Crafting before finally yielding; separately, an
 * ACTIVE SKILLING/Crafting session took close to two minutes to yield
 * to sustained Cooking. Root cause: isWeakEvidenceAgainstEstablishedSession()'s
 * candidate mechanism previously required EXACTLY the same thing to
 * confirm a switch -- a second observation of the same candidate
 * identity -- for BOTH branches, with no distinction between "the
 * established session is SUSPENDED (not currently running at all)" and
 * "the established session is ACTIVE (genuinely ongoing right now)",
 * and no way for a long-unreinforced ACTIVE session to ever become
 * easier to displace than a freshly-started one.
 *
 * THE FIX, GENERALIZED (never special-cased to any activity): the
 * SUSPENDED candidate keeps its existing, unchanged, already-tested
 * fixed requirement -- SUSPENDED_REQUIRED_CONFIRMATIONS (2): a single
 * weak observation only arms a candidate, a second COHERENT (same
 * identity) observation confirms it. This is deliberately untouched --
 * see this class's own regression test coverage that preserves it.
 *
 * The ACTIVE candidate now requires a REQUIRED CONFIRMATION COUNT that
 * is evaluated FRESH at every candidate observation's own instant, via
 * activeRequiredConfirmations(): it starts at
 * ACTIVE_PEAK_REQUIRED_CONFIRMATIONS (3 -- one MORE than SUSPENDED ever
 * requires, real "meaningful inertia" for a confidently-current
 * session) at the instant of the established session's own last
 * reinforcing evidence (lastActiveAt), and decays LINEARLY down to
 * ACTIVE_FLOOR_REQUIRED_CONFIRMATIONS -- exactly
 * SUSPENDED_REQUIRED_CONFIRMATIONS (2), never lower -- as elapsed time
 * since that reinforcement grows, fully decayed by
 * ACTIVE_INERTIA_DECAY_PERIOD (derived as a fraction of the EXISTING
 * SUSPEND_TIMEOUT constant, not a new independent time value). This
 * peak/decay treatment is scoped to a non-combat-branch (SKILLING)
 * candidate identity ONLY.
 *
 * DOWNRANKING COMBAT CHALLENGE: a generic COMBAT candidate challenging
 * an established SLAYER/BOSSING session needs a flat, non-decaying
 * ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS (3) -- conservative because
 * current telemetry cannot distinguish an encounter's own subordinate
 * NPCs from a genuinely unrelated off-task NPC. Same-rank COMBAT-vs-
 * COMBAT and SKILLING candidates are unaffected.
 *
 * DOWNRANKING COMBAT CHALLENGE, SUSTAINED-DURATION REQUIREMENT (generalized
 * evidence-precedence/ownership fix -- live QA: an ACTIVE SLAYER/Nechryael
 * session was demoted to generic COMBAT/"Greater Nechryael" within ~3.5s
 * of its own authoritative SLAYER_TASK_PROGRESS reinforcement; separately,
 * an ACTIVE SLAYER/Kalphites session -- fought with NO subordinate/minion
 * actor involved at all, proving this is not a parent/subordinate-specific
 * gap -- repeatedly churned SLAYER -&gt; generic COMBAT -&gt; SLAYER across one
 * single uninterrupted task, because "Kalphite Worker"/"Kalphite Soldier"/
 * etc. are the task's own on-task monster names yet are not literally the
 * task's singular-stripped display name, so every one of their ordinary
 * combat-XP ticks is honestly proposed as an off-task ORDINARY candidate).
 * A flat OBSERVATION COUNT alone cannot tell a genuinely sustained,
 * deliberate off-task switch apart from a brief, incidental run of
 * same-named evidence: during continuous combat, RAW_COMBAT_XP_OBSERVED
 * fires on nearly every hit, so ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS (3)
 * coherent observations of the identical candidate name can land within a
 * handful of real seconds -- long before the established session's own
 * next authoritative reinforcement would naturally arrive and reset it.
 * A downranking candidate must therefore ALSO span real elapsed wall-clock
 * time -- ACTIVE_DOWNRANK_SUSTAINED_DURATION -- between its own first-seen
 * observation and its confirming one, on top of (never instead of) the
 * existing observation-count floor. This is evidence-name-agnostic: it
 * never inspects an NPC name, never consults SlayerTaskFamilyRegistry, and
 * never special-cases Death Spawn, Kalphite Worker, or any other single
 * identity -- it generalizes to any future parent/subordinate case (boss
 * adds, Slayer summons/minions, multi-actor encounters) and to any current
 * family-registry coverage gap alike, because both failure modes share the
 * exact same shape: a challenger identity that keeps naturally recurring
 * WHILE the established activity is still genuinely ongoing. A session that
 * keeps receiving its OWN reinforcing evidence (a family-recognized combat
 * tick, or an authoritative SLAYER_TASK_PROGRESS/BOSS_ACTIVITY_CONTEXT for
 * the SAME activity) resets this candidate entirely via the pre-existing
 * same-identity heartbeat path, long before either bar is reached in
 * realistic play -- so a task that is genuinely still being worked stays
 * one continuous session, while genuinely sustained, deliberate off-task
 * combat (requirement: "eventually allowed to leave Slayer") still confirms
 * once it has actually run long enough to prove itself. See
 * ACTIVE_DOWNRANK_SUSTAINED_DURATION's own field javadoc for the exact
 * value and derivation.
 *
 * This is deliberately NOT a fixed time lock: nothing here ever
 * switches a session by elapsed time alone. The decay only ever LOWERS
 * the bar a challenger's own repeated, coherent evidence must clear --
 * confirmation still requires actual matching observations, exactly as
 * before. Concretely: (a) a single incidental observation NEVER
 * confirms against an ACTIVE session, at any elapsed time, because the
 * floor (2) is still above a lone observation's count (1) --
 * "survives one incidental action" holds unconditionally; (b) a
 * session that keeps receiving its OWN reinforcing evidence keeps
 * lastActiveAt current, so its inertia never decays below peak --
 * "matching evidence reinforces current focus"; (c) once reinforcement
 * stops, a coherent challenger's SECOND observation, which would not
 * yet have been enough against a freshly-reinforced session, becomes
 * sufficient once enough time has passed for the bar to decay down to
 * it -- "lack of reinforcing current evidence lowers the effective
 * switch barrier." SUSPENDED's own resistance never varies (it is not
 * "running" either way, so there is nothing for it to decay from), so
 * a freshly-reinforced ACTIVE session always requires strictly more
 * evidence than SUSPENDED, and a fully-decayed ACTIVE session requires
 * no less than SUSPENDED -- "SUSPENDED requires materially less
 * challenger evidence than ACTIVE" holds by construction.
 *
 * Every other invariant this class already documents is unchanged:
 * PRIMARY/SUPPORT/CHALLENGER/SUBORDINATE evidence-strength
 * tiering (isWeakEvidenceAgainstEstablishedSession() itself is
 * untouched -- only how MANY coherent CHALLENGER
 * observations are required once that method has already said the
 * evidence is weak changes), classify-before-attribute (this class still never
 * inspects an EventType/SignalKind -- it only ever consumes an
 * already-classified ActivityIdentity/EvidenceStrength), and a
 * SUSPENDED session's inability to receive unrelated metrics before
 * reactivating as its own identity (completely untouched -- this
 * never changes what a SUSPENDED session's own aggregates receive,
 * only how quickly a genuinely different, sustained activity replaces
 * it).
 */
public final class SessionLifecycleEngine
{
	public static final Duration SUSPEND_TIMEOUT = Duration.ofMinutes(5);
	public static final Duration RESUME_WINDOW = Duration.ofMinutes(30);

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A). How long a
	 * genuinely displaced ACTIVE session (see the INTERRUPTED-RESUME
	 * CANDIDATE trio below) stays resumable after a real switch away
	 * from it, before it permanently finalizes instead. Deliberately
	 * an ALIAS for the existing SUSPEND_TIMEOUT constant, per explicit
	 * instruction -- not a new, independently-tuned magic duration.
	 */
	public static final Duration INTERRUPTED_RESUME_WINDOW = SUSPEND_TIMEOUT;

	/**
	 * EVIDENCE-WEIGHTED HYSTERESIS (see class javadoc). SUSPENDED's own
	 * required-confirmation count -- fixed, unconditional: a SUSPENDED
	 * session is not currently running, so there is no notion of it
	 * "staying fresh" for its resistance to decay from; it is always
	 * exactly this weak. The SUSPENDED branch of onQualifyingActivity()
	 * does not read this constant directly (its arm-then-confirm-on-a-
	 * second-matching-observation code already implements exactly this
	 * count, unchanged since the original fix) -- it exists here so the
	 * comparison against ACTIVE's own requirement is documented in one
	 * place.
	 */
	private static final int SUSPENDED_REQUIRED_CONFIRMATIONS = 2;

	/**
	 * EVIDENCE-WEIGHTED HYSTERESIS (see class javadoc). ACTIVE's peak
	 * required-confirmation count, in effect the instant the
	 * established session's own last reinforcing evidence was observed
	 * (elapsed == 0). One MORE than SUSPENDED ever requires -- this is
	 * what gives a confidently-current ACTIVE session genuine inertia a
	 * SUSPENDED one never has.
	 */
	private static final int ACTIVE_PEAK_REQUIRED_CONFIRMATIONS = 3;

	/**
	 * EVIDENCE-WEIGHTED HYSTERESIS (see class javadoc). ACTIVE's floor --
	 * deliberately set to EXACTLY SUSPENDED_REQUIRED_CONFIRMATIONS, never
	 * lower: however long an ACTIVE session goes without its own
	 * reinforcing evidence, its ownership claim only ever decays down to
	 * parity with a SUSPENDED session's, never below it.
	 */
	private static final int ACTIVE_FLOOR_REQUIRED_CONFIRMATIONS = SUSPENDED_REQUIRED_CONFIRMATIONS;

	/**
	 * EVIDENCE-WEIGHTED HYSTERESIS (see class javadoc). How long it takes
	 * ACTIVE's required-confirmation count to decay from its peak down
	 * to its floor, once the established session stops receiving its own
	 * reinforcing evidence. Deliberately derived as a FRACTION of the
	 * EXISTING SUSPEND_TIMEOUT constant rather than a new, independent
	 * magic time value -- chosen well inside that window (one fifth of
	 * it) so a genuinely stale ACTIVE session's extra inertia is already
	 * gone long before it would suspend on its own account anyway; by
	 * the time SUSPEND_TIMEOUT itself is reached the session suspends
	 * regardless (see onQualifyingActivity()'s own suspend-threshold
	 * branch), so decay never needs to run any slower than this to be
	 * meaningful. This is a continuous WEIGHTING factor, never a trigger
	 * by itself -- see class javadoc's "not a fixed time lock" note.
	 */
	private static final Duration ACTIVE_INERTIA_DECAY_PERIOD = SUSPEND_TIMEOUT.dividedBy(5);

	/**
	 * Required confirmations for a generic COMBAT candidate downranking
	 * an established SLAYER/BOSSING session -- flat, with no time-based
	 * decay. Conservative because current telemetry cannot distinguish an
	 * encounter's own subordinate NPCs (e.g. a Death Spawn) from a
	 * genuinely unrelated off-task NPC; applies uniformly to both.
	 */
	private static final int ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS = 3;

	/**
	 * DOWNRANKING COMBAT CHALLENGE, SUSTAINED-DURATION REQUIREMENT (see class
	 * javadoc). The minimum real elapsed wall-clock span, between a
	 * downranking candidate's own first-seen observation and its would-be
	 * confirming one, required before it may confirm -- on top of (never
	 * instead of) ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS. Deliberately
	 * derived as the SAME already-vetted fraction of SUSPEND_TIMEOUT this
	 * class already uses for ACTIVE_INERTIA_DECAY_PERIOD, rather than a new,
	 * independent magic duration -- comfortably longer than the few-second
	 * bursts a recurring subordinate/family-registry-gap NPC produces during
	 * continuous combat, and comfortably shorter than SUSPEND_TIMEOUT itself.
	 */
	private static final Duration ACTIVE_DOWNRANK_SUSTAINED_DURATION = ACTIVE_INERTIA_DECAY_PERIOD;

	private Session current;

	/**
	 * Suspended-session
	 * candidate confirmation. See onQualifyingActivity()'s SUSPENDED
	 * branch for the full design note. Deliberately transient,
	 * in-memory-only state -- never persisted (matches
	 * ClassifierContext's own "purely in-memory working state"
	 * precedent) -- because it is only ever a short-lived guard against
	 * a single isolated weak signal, not a durable fact about the
	 * session itself; a plugin/account restart simply re-observes
	 * whatever evidence comes next with no candidate armed, which is
	 * always safe (worst case: one more weak signal is needed before a
	 * genuine switch is confirmed again).
	 *
	 * pendingCandidateMetrics: the
	 * MetricUpdates carried by the batch(es) that armed/replaced the
	 * current pending candidate, buffered here -- not yet applied to
	 * any session -- until the candidate is either confirmed (they
	 * move to the new session, combined with the confirming batch's
	 * own metrics) or abandoned (they are committed once, as contextual
	 * metrics, to whatever session was established at the moment of
	 * abandonment). Same lifetime as the two fields above: always
	 * cleared together by clearPendingCandidate().
	 */
	private ActivityIdentity pendingCandidateIdentity;
	private Instant pendingCandidateFirstSeenAt;
	private final List<MetricUpdate> pendingCandidateMetrics = new ArrayList<>();

	/**
	 * ACTIVE-EVIDENCE CANDIDATE --
	 * generalizes the trio above to the ACTIVE branch; see class
	 * javadoc's "EVIDENCE-STRENGTH-AWARE WEAK-EVIDENCE GATE, GENERALIZED
	 * TO ACTIVE" section). Same shape, same "purely in-memory, never
	 * persisted" lifetime discipline as pendingCandidate* -- but entirely
	 * SEPARATE fields, because an ACTIVE established session and a
	 * SUSPENDED one confirm/abandon on different clocks (SUSPEND_TIMEOUT
	 * vs. RESUME_WINDOW) and, per an explicit design correction, anchor a
	 * CONFIRMED switch differently:
	 *
	 *   - SUSPENDED (pendingCandidateFirstSeenAt): anchors at the
	 *     candidate's own first-seen instant (T1), because the
	 *     established session was already not running then -- no
	 *     activity time is fabricated by dating the switch to when the
	 *     new activity actually began.
	 *   - ACTIVE (activeCandidateFirstSeenAt): the established session
	 *     genuinely WAS still running for the whole T1..T2 gap, so
	 *     anchoring at T1 here would instead fabricate a boundary the
	 *     evidence never proved. The established session remains ACTIVE
	 *     through T2, finalizes AT T2, and the new session starts AT T2 --
	 *     see onQualifyingActivity()'s own CONFIRM branch.
	 *     activeCandidateFirstSeenAt is still recorded, for parity with
	 *     the SUSPENDED trio and for future diagnostics, but is
	 *     DELIBERATELY NEVER read to anchor a finalize/start timestamp.
	 *
	 * Invariant, enforced at every ACTIVE->SUSPENDED transition (the
	 * suspend-threshold branch of onQualifyingActivity()/refineIdentity()/
	 * advanceTime()): this trio is ALWAYS flushed (its buffered metrics
	 * committed once as contextual metrics, never silently dropped)
	 * before the session is allowed to become SUSPENDED, so it is always
	 * empty by the time current.getState() == SUSPENDED -- the SUSPENDED
	 * gate never needs to know this trio exists.
	 */
	private ActivityIdentity activeCandidateIdentity;
	private Instant activeCandidateFirstSeenAt;
	private final List<MetricUpdate> activeCandidateMetrics = new ArrayList<>();

	/**
	 * EVIDENCE-WEIGHTED HYSTERESIS (see class javadoc). How many
	 * observations of the CURRENT activeCandidateIdentity have been seen
	 * so far (always exactly 1 the instant a candidate arms/replaces --
	 * that observation IS the first). Compared against
	 * activeRequiredConfirmations() -- evaluated fresh, from the
	 * established session's OWN current staleness, at every matching
	 * observation -- to decide whether THIS observation finally confirms
	 * the switch. Reset to 0 by clearActiveCandidate(), exactly like the
	 * rest of this trio.
	 */
	private int activeCandidateObservationCount;

	/**
	 * INTERRUPTED-RESUME CANDIDATE (A -&gt; brief B -&gt; A). At most ONE
	 * interrupted-but-resumable PRIOR session at any time -- no stack,
	 * no tree. Populated only by a REAL switch away from a genuinely
	 * ACTIVE session (see applyRealSwitch()); never by a SUSPENDED
	 * session's own real switch (that keeps its existing, completely
	 * separate finalize-immediately behavior -- see applyRealSwitch()'s
	 * own javadoc for why this is scoped to the ACTIVE branch only).
	 *
	 * This is a FULL Session object -- the exact same one that was
	 * `current` immediately before the switch, carrying its own
	 * sessionId/startedAt/aggregates untouched -- never finalized at
	 * park time. It stops being "current" (getCurrentSession() no
	 * longer returns it) but is not yet immutable History either; see
	 * expireInterruptedCandidateIfNeeded()/tryResumeInterruptedCandidate()/
	 * applyRealSwitch() for the three ways it is ultimately resolved:
	 * resumed (the same identity returns within the window), expired
	 * (the window elapses with nothing checking in), or displaced (a
	 * SECOND real switch happens while this one is still parked -- the
	 * one-candidate-only rule finalizes this one and the new outgoing
	 * session becomes the sole candidate instead).
	 *
	 * Deliberately in-memory fields here, exactly like the two
	 * candidate trios above -- SessionPersistence/SessionRuntimeCoordinator
	 * own the SEPARATE, additive interrupted_candidate.json record that
	 * makes this survive a restart; this engine itself has no I/O and
	 * no wall-clock reads, matching every other field in this class.
	 */
	private Session interruptedCandidate;

	/** The exact instant `interruptedCandidate` stopped being current (T1). */
	private Instant interruptedCandidateInterruptedAt;

	/** interruptedCandidateInterruptedAt + INTERRUPTED_RESUME_WINDOW -- the deterministic eligibility boundary. */
	private Instant interruptedCandidateExpiresAt;

	public SessionLifecycleEngine()
	{
		this.current = null;
	}

	private SessionLifecycleEngine(Session current)
	{
		this.current = current;
	}

	/** Current in-progress session (ACTIVE or SUSPENDED), or null if none. Never FINALIZED. */
	public Session getCurrentSession()
	{
		return current;
	}

	/**
	 * The one interrupted/resumable prior session, if any -- see the
	 * field's own javadoc. Null whenever nothing is parked. Read-only:
	 * external code (persistence, tests) may inspect this, but only
	 * SessionLifecycleEngine's own methods ever transition it.
	 */
	public Session getInterruptedCandidate()
	{
		return interruptedCandidate;
	}

	/** The interrupted candidate's own T1 (interruption instant), or null if none is parked. */
	public Instant getInterruptedCandidateInterruptedAt()
	{
		return interruptedCandidateInterruptedAt;
	}

	/** The interrupted candidate's eligibility-expiry instant (T1 + INTERRUPTED_RESUME_WINDOW), or null if none is parked. */
	public Instant getInterruptedCandidateExpiresAt()
	{
		return interruptedCandidateExpiresAt;
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A). Unconditionally
	 * resolves the ONE parked interrupted candidate, if any, by
	 * finalizing it now, AT ITS OWN ORIGINAL INTERRUPTION INSTANT --
	 * never at the current wall-clock time. For
	 * SessionRuntimeCoordinator's account-switch and orderly-shutdown
	 * paths only: "no candidate may disappear silently" -- both call
	 * this unconditionally so a still-parked candidate is never
	 * dropped, never silently carried over into a different account's
	 * engine, and never attributed to a new account. Idempotent: returns
	 * null on every call after the first (the fields are cleared here).
	 * Never touches `current` at all.
	 */
	public Session flushInterruptedCandidateForAccountBoundary()
	{
		if (interruptedCandidate == null)
		{
			return null;
		}
		Session finalized = finalizeSession(interruptedCandidate, interruptedCandidateInterruptedAt);
		clearInterruptedCandidate();
		return finalized;
	}

	/**
	 * The single entry point for "a qualifying activity happened," with
	 * no accompanying metrics. Delegates to the 3-arg overload with an
	 * empty metric batch -- kept for callers/tests that only care about
	 * lifecycle transitions.
	 */
	public LifecycleResult onQualifyingActivity(ActivityIdentity identity, Instant observedAt)
	{
		return onQualifyingActivity(identity, observedAt, Collections.<MetricUpdate>emptyList());
	}

	/**
	 * LEGACY 3-arg ENTRY POINT --
	 * RETAINED for every pre-existing caller/test with no evidence
	 * strength to supply). Delegates to the 4-arg overload with
	 * EvidenceStrength.ORDINARY -- the WEAKEST tier, deliberately: the
	 * conservative default that makes the weak-evidence gate below apply
	 * to a legacy call exactly as it now applies to a real,
	 * classifier-tagged ORDINARY signal, rather than silently bypassing
	 * it. Real runtime callers (SessionRuntimeCoordinator) always use the
	 * 4-arg overload with the batch's own, classifier-derived strength.
	 */
	public LifecycleResult onQualifyingActivity(ActivityIdentity identity, Instant observedAt, List<MetricUpdate> metrics)
	{
		return onQualifyingActivity(identity, observedAt, metrics, EvidenceStrength.ORDINARY);
	}

	/**
	 * Takes `strength` -- see
	 * EvidenceStrength's own javadoc. The real entry point for "a
	 * qualifying activity happened," carrying that signal batch's own
	 * MetricUpdates (possibly empty) and its EvidenceStrength. See class
	 * javadoc for the full transition table, the
	 * PROVISIONAL-METRIC OWNERSHIP section for how `metrics` is routed
	 * via the returned LifecycleResult, and the "EVIDENCE-STRENGTH-AWARE
	 * WEAK-EVIDENCE GATE, GENERALIZED TO ACTIVE" section.
	 */
	public LifecycleResult onQualifyingActivity(ActivityIdentity identity, Instant observedAt, List<MetricUpdate> metrics, EvidenceStrength strength)
	{
		return onQualifyingActivity(identity, observedAt, metrics, strength, true);
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A). Identical
	 * to the 4-arg overload, except `allowInterruptedResume` controls
	 * whether a genuine ACTIVE-branch real switch (decided exactly as
	 * before, by the existing, untouched hysteresis/classifier rules)
	 * is allowed to park the outgoing session as the ONE resumable
	 * interrupted candidate (see applyRealSwitch()) rather than
	 * finalizing it immediately, and whether a return to that parked
	 * candidate is allowed to resume it. Pass false ONLY from the
	 * manual "Re-evaluate Session" path -- a manual identity correction
	 * must never manufacture a hidden resumable prior session/History
	 * entry; every other caller (SessionRuntimeCoordinator's ordinary
	 * signal processing) always uses true, via the 4-arg overload
	 * above.
	 *
	 * Also runs the same background interrupted-candidate-expiry
	 * housekeeping every other timestamped entry point performs (see
	 * expireInterruptedCandidateIfNeeded()) -- unconditionally, before
	 * the real lifecycle decision below, so a candidate whose window
	 * has already quietly elapsed is never left dangling and never
	 * influences this call's own switch/park/resume decision.
	 */
	public LifecycleResult onQualifyingActivity(ActivityIdentity identity, Instant observedAt, List<MetricUpdate> metrics, EvidenceStrength strength, boolean allowInterruptedResume)
	{
		Session expiredInterruptedCandidate = expireInterruptedCandidateIfNeeded(observedAt);
		LifecycleResult result = onQualifyingActivityCore(identity, observedAt, metrics, strength, allowInterruptedResume);
		return result.withAdditionalFinalized(expiredInterruptedCandidate);
	}

	private LifecycleResult onQualifyingActivityCore(ActivityIdentity identity, Instant observedAt, List<MetricUpdate> metrics, EvidenceStrength strength, boolean allowInterruptedResume)
	{
		if (current == null)
		{
			current = newSession(identity, observedAt);
			return LifecycleResult.withMetrics(current, null, metrics);
		}

		Instant lastActiveAt = current.lastActiveAtInstant();
		if (observedAt.isBefore(lastActiveAt))
		{
			// Out-of-order — reject entirely, no mutation, no metrics
			// applied anywhere. See class javadoc.
			return LifecycleResult.of(current, null);
		}

		// Populated only when THIS call's own ACTIVE->SUSPENDED threshold
		// transition (the suspend-threshold-reached branch just below)
		// abandons a still-pending ACTIVE candidate -- carried forward so
		// whichever SUSPENDED branch below ultimately returns includes
		// it. Empty in every other case (including when current was
		// already SUSPENDED at the start of this call, since the
		// ACTIVE-CANDIDATE trio is always already empty by then -- see
		// its own field javadoc).
		List<MetricUpdate> carriedFromActiveCandidate = Collections.emptyList();

		if (current.getState() == SessionState.ACTIVE)
		{
			Instant suspendThreshold = lastActiveAt.plus(SUSPEND_TIMEOUT);
			if (observedAt.isBefore(suspendThreshold))
			{
				// Still within the active window — no suspend needed.
				if (identity.equals(current.getActivityIdentity()))
				{
					// The established session's OWN identity continues.
					// Any pending ACTIVE candidate never got its
					// confirming second observation -- ABANDONED here,
					// exactly like the SUSPENDED branch's own
					// original-identity-resumes case below: its buffered
					// metrics genuinely occurred during this same
					// session, so they are committed once as CONTEXTUAL
					// metrics, with no other lifecycle side effect beyond
					// the ordinary accumulate() this event already causes.
					List<MetricUpdate> abandoned = new ArrayList<>(activeCandidateMetrics);
					clearActiveCandidate();
					accumulate(current, observedAt);
					if (abandoned.isEmpty())
					{
						return LifecycleResult.withMetrics(current, null, metrics);
					}
					return LifecycleResult.withMetricsAndContextual(current, null, metrics, current, abandoned);
				}

				// A
				// different identity while ACTIVE. See class javadoc's
				// "EVIDENCE-STRENGTH-AWARE WEAK-EVIDENCE GATE, GENERALIZED
				// TO ACTIVE" for why this is now gated exactly like the
				// SUSPENDED branch, instead of always switching
				// immediately.
				if (isWeakEvidenceAgainstEstablishedSession(identity, current, strength))
				{
					if (activeCandidateIdentity != null && activeCandidateIdentity.equals(identity))
					{
						// SAME candidate identity repeating -- one more
						// coherent observation toward confirmation.
						// EVIDENCE-WEIGHTED HYSTERESIS (see class javadoc):
						// whether THIS observation is enough to confirm now
						// depends on how much inertia the established
						// session still has AT THIS EXACT INSTANT, evaluated
						// fresh from its own lastActiveAt -- never cached
						// from when the candidate first armed, so a
						// candidate that has been patiently repeating
						// against an increasingly-stale session can cross
						// the bar the moment decay brings it down far
						// enough, without needing yet another observation.
						activeCandidateObservationCount++;
						activeCandidateMetrics.addAll(metrics);

						double required = activeRequiredConfirmations(lastActiveAt, observedAt,
							current.getActivityIdentity().getActivityType(), identity);
						// SUSTAINED-DURATION REQUIREMENT (see class javadoc's
						// "DOWNRANKING COMBAT CHALLENGE, SUSTAINED-DURATION
						// REQUIREMENT" and ACTIVE_DOWNRANK_SUSTAINED_DURATION's own
						// field javadoc): for a downranking combat challenge ONLY, an
						// observation count alone is not enough -- the candidate's own
						// evidence must also genuinely SPAN real wall-clock time since
						// its first-seen observation. Name-agnostic: never inspects
						// candidateIdentity's own key/displayName, only its
						// ActivityType versus the established session's.
						boolean downrankingChallenge = isDownrankingCombatChallenge(
							current.getActivityIdentity().getActivityType(), identity.getActivityType());
						boolean sustainedLongEnough = !downrankingChallenge
							|| !Duration.between(activeCandidateFirstSeenAt, observedAt)
								.minus(ACTIVE_DOWNRANK_SUSTAINED_DURATION).isNegative();
						if (activeCandidateObservationCount < required || !sustainedLongEnough)
						{
							// Not yet enough repetition, and/or not yet sustained
							// for long enough, to overcome the established session's
							// current inertia -- stay armed, this observation's
							// metrics are buffered too (not yet applied anywhere),
							// established session left completely untouched (no
							// accumulate() call: this is not ITS OWN reinforcing
							// evidence).
							return LifecycleResult.of(current, null);
						}

						// CONFIRM -- anchored at T2 (this observation's own
						// timestamp), NEVER at the candidate's first-seen
						// T1. The established session was genuinely ACTIVE
						// for the whole T1..T2 gap, so it finalizes AT T2
						// and the new session starts AT T2 -- see the
						// ACTIVE-CANDIDATE field javadoc and class
						// javadoc's T2-anchoring note. Every candidate
						// observation's buffered metrics combine onto the
						// new session, exactly once; the old session gets
						// none of them.
						List<MetricUpdate> combined = new ArrayList<>(activeCandidateMetrics);
						clearActiveCandidate();
						return applyRealSwitch(identity, observedAt, combined, Collections.<MetricUpdate>emptyList(), allowInterruptedResume);
					}

					// ARM a fresh candidate, or REPLACE a different one --
					// same arm/replace discipline as the SUSPENDED branch
					// below, resetting the observation count to 1 (this IS
					// its first observation). A replaced candidate's own
					// buffered metrics are abandoned, committed once as
					// contextual metrics to the still-ACTIVE established
					// session.
					List<MetricUpdate> abandoned = (activeCandidateIdentity != null && !activeCandidateIdentity.equals(identity))
						? new ArrayList<>(activeCandidateMetrics)
						: Collections.<MetricUpdate>emptyList();
					activeCandidateIdentity = identity;
					activeCandidateFirstSeenAt = observedAt;
					activeCandidateObservationCount = 1;
					activeCandidateMetrics.clear();
					activeCandidateMetrics.addAll(metrics);
					if (abandoned.isEmpty())
					{
						return LifecycleResult.of(current, null);
					}
					return LifecycleResult.withMetricsAndContextual(current, null, Collections.<MetricUpdate>emptyList(), current, abandoned);
				}

				// AUTHORITATIVE/SPECIFIC evidence, or a candidate outside
				// the narrow weak-evidence scope: immediate switch --
				// never gated, ACTIVE or SUSPENDED. Any pending
				// ACTIVE candidate is abandoned by this stronger, unrelated
				// arrival -- committed once, as contextual metrics, to the
				// OLD session that is about to finalize.
				List<MetricUpdate> abandonedByRealSwitch = new ArrayList<>(activeCandidateMetrics);
				clearActiveCandidate();
				return applyRealSwitch(identity, observedAt, metrics, abandonedByRealSwitch, allowInterruptedResume);
			}
			else
			{
				// The gap has reached/passed the suspend threshold as of
				// this event's own timestamp — suspend first, at the
				// deterministic threshold, then fall through to the
				// SUSPENDED handling below using the same observedAt. Any
				// pending ACTIVE candidate never got its confirming second
				// observation before this real idle gap -- abandoned here
				// (carried into whichever SUSPENDED branch below
				// ultimately returns), exactly like the SUSPENDED
				// candidate's own resume-window-expiry abandonment.
				suspend(current, suspendThreshold);
				carriedFromActiveCandidate = new ArrayList<>(activeCandidateMetrics);
				clearActiveCandidate();
			}
		}

		// current.getState() == SessionState.SUSPENDED here (either it
		// already was, or the ACTIVE branch above just suspended it).
		Instant resumeExpiry = current.resumeWindowExpiresAtInstant();
		if (!observedAt.isAfter(resumeExpiry))
		{
			// At or before the resume-window boundary.
			if (identity.equals(current.getActivityIdentity()))
			{
				// The ORIGINAL session's own identity resumes. Any
				// pending candidate never got confirmed -- it is
				// ABANDONED here: its buffered metrics genuinely
				// occurred during this broader session, so they are
				// committed once as CONTEXTUAL metrics on the resumed
				// session itself (Case 2 / invariant 3) -- no lifecycle
				// side effect beyond the resume() this event already
				// causes on its own merits. Merged with
				// carriedFromActiveCandidate (almost always empty here --
				// see that variable's own comment).
				List<MetricUpdate> abandoned = new ArrayList<>(carriedFromActiveCandidate);
				abandoned.addAll(pendingCandidateMetrics);
				clearPendingCandidate();
				resume(current, observedAt);
				if (abandoned.isEmpty())
				{
					return LifecycleResult.withMetrics(current, null, metrics);
				}
				return LifecycleResult.withMetricsAndContextual(current, null, metrics, current, abandoned);
			}

			// ============================================================
			// Live evidence: a
			// single incidental AGILITY +3 XP_CHANGE, observed while a
			// genuinely resumable BOSSING/Grotesque Guardians session was
			// SUSPENDED (well inside its 30-minute resume window),
			// immediately finalized that session -- destroying it before
			// the player's actual return to the same boss (a same-boss
			// BOSS_KILL) ever arrived. An earlier rule ("different
			// activity while SUSPENDED -> finalize immediately") treated
			// every differing identity as equally authoritative; live
			// testing proved that is too eager for WEAK, generic evidence
			// against an ESTABLISHED SPECIFIC (combat-branch: COMBAT/
			// SLAYER/BOSSING) session.
			//
			// NARROW FIX -- weak-evidence candidate confirmation, scoped
			// EXACTLY to: current session's identity is combat-branch,
			// AND the new identity is NOT combat-branch (i.e. SKILLING).
			// This is deliberately narrow, not "BOSSING always beats
			// SKILLING": authoritative combat-branch evidence for a
			// DIFFERENT combat-branch identity (a different boss's
			// BOSS_KILL, a different Slayer task's SLAYER_TASK_PROGRESS)
			// is NEVER weak by this definition and falls through to the
			// existing immediate-switch behavior below unchanged --
			// ActivitySignalClassifier's own
			// decideCombatBranch() already keeps LESS-specific/incompatible
			// combat-branch evidence (generic COMBAT XP, a same-task
			// SLAYER_TASK_PROGRESS) from ever proposing a switch at all, so
			// this method never even sees those as a differing identity.
			//
			// CONFIRMATION RULE: the FIRST weak-evidence identity observed
			// while SUSPENDED only arms a pending candidate -- the
			// suspended session itself is left completely untouched (still
			// SUSPENDED, still resumable for its own identity), and its
			// batch's metrics are only BUFFERED, not applied to the old
			// session. Only a
			// SECOND, COHERENT observation of the SAME candidate identity
			// (repeated evidence, not a single blip) confirms it as a
			// genuine new activity: the old session finalizes and the new
			// one starts, both anchored at the candidate's OWN first-seen
			// instant (when the new activity actually began), never the
			// confirming event's later timestamp -- so no activity time is
			// fabricated for either session, and the two batches' combined
			// metrics move to the new session as one exact-once
			// contribution; the old session receives none of them (Case
			// 1). A different candidate identity arriving before
			// confirmation REPLACES (does not accumulate with) the
			// pending one: "coherent" means the same candidate repeating,
			// not any two arbitrary weak signals -- the replaced
			// candidate's own buffered metrics are committed once, as
			// contextual metrics, to the still-established old session
			// (Case 3), since they belong to real activity that happened
			// during it and were never confirmed as anything else. If the
			// ORIGINAL session's own identity is observed again first (the
			// case above this comment), the candidate is dropped and the
			// original session resumes -- this
			// is what keeps a suspended session resumable rather than
			// permanently poisoned by one earlier weak blip.
			if (isWeakEvidenceAgainstEstablishedSession(identity, current, strength))
			{
				if (pendingCandidateIdentity != null && pendingCandidateIdentity.equals(identity))
				{
					// CONFIRM: combine the buffered first-observation
					// metrics with this confirming batch's own metrics --
					// together they move to the new session, exactly
					// once, and the old session gets none of them.
					// carriedFromActiveCandidate cannot be non-empty here:
					// it is only ever populated when THIS SAME call just
					// suspended `current` from ACTIVE, in which case
					// pendingCandidateIdentity is necessarily still null
					// (freshly suspended), so this confirm branch is
					// unreachable in that same call.
					Instant candidateStartedAt = pendingCandidateFirstSeenAt;
					List<MetricUpdate> combined = new ArrayList<>(pendingCandidateMetrics);
					combined.addAll(metrics);
					clearPendingCandidate();
					Session finalized = finalizeSession(current, candidateStartedAt);
					// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): this
					// confirming observation is the SUSPENDED-branch's own
					// two-observation weak-evidence confirmation of a real
					// switch -- reached whenever B rehydrates SUSPENDED (always
					// true immediately post-restart) and ORDINARY evidence for
					// A arrives. Exactly like the other two already-confirmed-
					// switch call sites above, check whether the confirmed
					// identity matches the ONE parked interrupted candidate
					// before minting a brand-new session -- anchored at
					// candidateStartedAt (the candidate's own first-seen
					// instant), never the later confirming event's timestamp,
					// so no fabricated eligibility window is granted. This does
					// NOT change whether/when this weak-evidence arm arms or
					// confirms -- that decision above is completely untouched;
					// this only changes what happens to an ALREADY-confirmed
					// real switch.
					Session resumedFromInterrupted = tryResumeInterruptedCandidate(identity, candidateStartedAt);
					// A -> B -> C invariant (see
					// displaceInterruptedCandidateIfPresent()'s own javadoc): a
					// CONFIRMED switch to an identity that does NOT match the
					// parked candidate permanently kills that candidate now, rather
					// than leaving it dangling for time-based expiry to eventually
					// clean up.
					Session displacedInterruptedCandidate = resumedFromInterrupted != null
						? null : displaceInterruptedCandidateIfPresent();
					current = resumedFromInterrupted != null ? resumedFromInterrupted : newSession(identity, candidateStartedAt);
					return LifecycleResult.withMetrics(current, finalized, combined).withAdditionalFinalized(displacedInterruptedCandidate);
				}

				// ARM a fresh candidate, or REPLACE a different one. A
				// replaced candidate's own buffered metrics are abandoned
				// here -- committed once, as contextual metrics, to the
				// still-established old session (Case 3) -- while this
				// batch's metrics simply become the new pending
				// candidate's own buffer (not applied anywhere yet).
				// Merged with carriedFromActiveCandidate (almost always
				// empty here -- see that variable's own comment).
				List<MetricUpdate> abandoned = new ArrayList<>(carriedFromActiveCandidate);
				if (pendingCandidateIdentity != null && !pendingCandidateIdentity.equals(identity))
				{
					abandoned.addAll(pendingCandidateMetrics);
				}
				pendingCandidateIdentity = identity;
				pendingCandidateFirstSeenAt = observedAt;
				pendingCandidateMetrics.clear();
				pendingCandidateMetrics.addAll(metrics);
				if (abandoned.isEmpty())
				{
					return LifecycleResult.of(current, null);
				}
				return LifecycleResult.withMetricsAndContextual(current, null, Collections.<MetricUpdate>emptyList(), current, abandoned);
			}

			// Authoritative different-identity
			// evidence while SUSPENDED -- immediate switch, no
			// confirmation needed. Any pending candidate is abandoned by
			// this authoritative arrival (Case 5): its buffered metrics
			// are committed once, as contextual metrics, to the OLD
			// session that is about to finalize (they occurred during
			// it, and must not contaminate the new authoritative
			// activity), while this batch's own metrics go to the new
			// session as usual. Merged with carriedFromActiveCandidate
			// (almost always empty here -- see that variable's own
			// comment).
			List<MetricUpdate> abandoned = new ArrayList<>(carriedFromActiveCandidate);
			abandoned.addAll(pendingCandidateMetrics);
			clearPendingCandidate();
			Session finalized = finalizeSession(current, observedAt);
			// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): see
			// tryResumeInterruptedCandidate()'s own javadoc -- a genuine
			// return to a still-parked, still-eligible A must resume it
			// here too, not only from the ACTIVE branch, so a restart
			// (which always leaves `current` SUSPENDED, never ACTIVE)
			// never strands an otherwise-still-valid candidate.
			Session resumedFromInterrupted = tryResumeInterruptedCandidate(identity, observedAt);
			// A -> B -> C invariant -- see
			// displaceInterruptedCandidateIfPresent()'s own javadoc.
			Session displacedInterruptedCandidate = resumedFromInterrupted != null
				? null : displaceInterruptedCandidateIfPresent();
			current = resumedFromInterrupted != null ? resumedFromInterrupted : newSession(identity, observedAt);
			if (abandoned.isEmpty())
			{
				return LifecycleResult.withMetrics(current, finalized, metrics).withAdditionalFinalized(displacedInterruptedCandidate);
			}
			return LifecycleResult.withMetricsAndContextual(current, finalized, metrics, finalized, abandoned)
				.withAdditionalFinalized(displacedInterruptedCandidate);
		}
		else
		{
			// Resume window has strictly elapsed as of this event's own
			// timestamp — finalize at the deterministic expiry
			// instant, then start a fresh session for whatever activity
			// this event carries (even if it's nominally the "same"
			// identity: the old session already ended, this is a new one).
			// Any pending candidate is abandoned here too: its buffered
			// metrics are committed once, as contextual metrics, to the
			// old (now-finalizing) session. Merged with
			// carriedFromActiveCandidate (reachable here: a signal can
			// arrive more than SUSPEND_TIMEOUT + RESUME_WINDOW after the
			// established session's own lastActiveAt, crossing both
			// thresholds in this same call).
			List<MetricUpdate> abandoned = new ArrayList<>(carriedFromActiveCandidate);
			abandoned.addAll(pendingCandidateMetrics);
			clearPendingCandidate();
			Session finalized = finalizeSession(current, resumeExpiry);
			// INTERRUPTED-ACTIVITY RESUME (A -> brief B -> A): same
			// resume-check as the immediate-switch branch just above --
			// see tryResumeInterruptedCandidate()'s own javadoc.
			Session resumedFromInterrupted = tryResumeInterruptedCandidate(identity, observedAt);
			// A -> B -> C invariant -- see
			// displaceInterruptedCandidateIfPresent()'s own javadoc.
			Session displacedInterruptedCandidate = resumedFromInterrupted != null
				? null : displaceInterruptedCandidateIfPresent();
			current = resumedFromInterrupted != null ? resumedFromInterrupted : newSession(identity, observedAt);
			if (abandoned.isEmpty())
			{
				return LifecycleResult.withMetrics(current, finalized, metrics).withAdditionalFinalized(displacedInterruptedCandidate);
			}
			return LifecycleResult.withMetricsAndContextual(current, finalized, metrics, finalized, abandoned)
				.withAdditionalFinalized(displacedInterruptedCandidate);
		}
	}

	/**
	 * Takes `strength`; generalized to cover a non-combat-branch
	 * (SKILLING) established session too -- see this method's own
	 * "GENERALIZED TO SKILLING" section below. True only when `strength`
	 * is exactly EvidenceStrength.ORDINARY (AUTHORITATIVE/SPECIFIC
	 * evidence is never
	 * weak, for any established identity type, and switches immediately)
	 * AND the specific combination of establishedIdentity/candidateIdentity
	 * is one live testing showed is weak enough to need confirmation
	 * before it may finalize a still-resumable session, ACTIVE or
	 * SUSPENDED alike (see class javadoc's "EVIDENCE-STRENGTH-AWARE
	 * WEAK-EVIDENCE GATE, GENERALIZED TO ACTIVE").
	 */
	private static boolean isWeakEvidenceAgainstEstablishedSession(ActivityIdentity candidateIdentity, Session current, EvidenceStrength strength)
	{
		if (strength != EvidenceStrength.ORDINARY)
		{
			return false;
		}

		ActivityType currentType = current.getActivityIdentity().getActivityType();
		if (ActivityPrecedence.isCombatBranch(currentType))
		{
			if (!ActivityPrecedence.isCombatBranch(candidateIdentity.getActivityType()))
			{
				// Original rule: ORDINARY
				// evidence for a non-combat-branch identity (SKILLING)
				// against an established combat-branch session is always
				// weak.
				return true;
			}
			// Same-rank
			// COMBAT-vs-COMBAT candidates (a different NPC's own generic
			// combat identity, e.g. established COMBAT/abyssal spectre vs a
			// single blip of COMBAT/bloodveld) are ALSO weak -- the exact
			// same "one incidental observation must not switch/finalize an
			// established session" reasoning as the non-combat-branch case
			// above, now needed for the first time because
			// ActivitySignalClassifier.genericNpcCombatIdentity() gives
			// generic COMBAT identities distinct per-NPC keys instead of one
			// single fixed "combat" key. Without this, decideCombatBranch()'s
			// existing "same specificity rank, different key -> always a real
			// switch" rule (correct and unchanged for genuine SLAYER-vs-SLAYER/
			// BOSSING-vs-BOSSING task/boss changes, which only ever arrive as
			// SPECIFIC/AUTHORITATIVE evidence in practice) would thrash an
			// established generic-combat session on every stray off-target
			// XP tick. SLAYER/BOSSING never reach this branch as ORDINARY
			// same-rank-different-key evidence in practice -- decideCombatBranch()'s
			// own lessSpecific "never downgrade" handling absorbs any ORDINARY
			// evidence for a DIFFERENT, less-specific identity before it is
			// ever proposed to this engine as a differing identity at all --
			// so this is scoped, in effect, to COMBAT/COMBAT only, and never
			// weakens a real Slayer-task or boss switch.
			return candidateIdentity.getActivityType() == ActivityType.COMBAT;
		}

		// GENERALIZED TO SKILLING. A brief, incidental Fletching XP tick (or Woodcutting, or
		// any other secondary skill) observed during an established
		// Hunter session immediately switched the session away -- because
		// ActivitySignalClassifier.classifyNonCombatXp() has no internal
		// specificity hierarchy at all (SKILLING has none -- see
		// ActivityPrecedence's own javadoc), EVERY differently-keyed
		// SKILLING proposal was an unconditional "real switch," and this
		// gate previously only ever applied when the ESTABLISHED session
		// was combat-branch -- a SKILLING-established session had zero
		// hysteresis protection of any kind. THE RULE, GENERALIZED (per
		// explicit instruction: this must not be a per-skill exception
		// table -- "Hunter can't be interrupted by Fletching" is a
		// regression CASE of this rule, not the rule itself): ANY
		// ORDINARY-strength differing identity -- another named SKILLING
		// activity, or combat-branch evidence incidentally caught mid-
		// skilling -- against an established SKILLING session now needs
		// the SAME second, coherent confirming observation the
		// combat-branch branch above has always required, via the exact
		// same arm/confirm/abandon-on-return candidate machinery
		// (isWeakEvidenceAgainstEstablishedSession() is the only place
		// that decides "weak" -- onQualifyingActivity()'s candidate
		// mechanics themselves are completely unchanged and already
		// type-agnostic). A single incidental tick never switches
		// anything; the established activity's own evidence returning
		// first (the ordinary "identity.equals(current)" fast path)
		// still abandons the candidate and resumes the established
		// session untouched, exactly as for combat-branch. Only genuinely
		// SUSTAINED evidence for the new activity -- a second, separate
		// observation of the SAME candidate identity -- confirms a real
		// transition, matching the invariant that a skill "should become
		// PRIMARY only when evidence shows a genuine sustained activity
		// transition."
		return true;
	}

	private void clearActiveCandidate()
	{
		activeCandidateIdentity = null;
		activeCandidateFirstSeenAt = null;
		activeCandidateObservationCount = 0;
		activeCandidateMetrics.clear();
	}

	private void clearInterruptedCandidate()
	{
		interruptedCandidate = null;
		interruptedCandidateInterruptedAt = null;
		interruptedCandidateExpiresAt = null;
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A). A narrower,
	 * RESUME-ONLY check -- used exclusively by the SUSPENDED-branch's own
	 * two PRE-EXISTING immediate-switch call sites below (never by
	 * anything that could PARK a new candidate): "Do NOT create a second
	 * interrupted-candidate mechanism when the outgoing session was
	 * already SUSPENDED ... preserve the existing SUSPENDED / 30-minute
	 * resume behavior as-is" (see class javadoc) -- this method never
	 * parks, never displaces, never touches interruptedCandidate at all
	 * except to resume or leave it completely untouched.
	 *
	 * WHY THIS EXISTS: the ONE parked interrupted candidate must remain
	 * resumable even when `current` (B) itself has independently gone
	 * SUSPENDED in the interim -- most commonly via the pre-existing,
	 * unconditional conservative rehydrate() reconciliation, which
	 * ALWAYS marks a restored persisted-ACTIVE session SUSPENDED
	 * regardless of true elapsed wall time (see rehydrate()'s own
	 * javadoc) -- so a restart occurring while B is still current, still
	 * well inside A's own 5-minute window, would otherwise make A
	 * unreachable the instant B is evaluated as SUSPENDED, purely
	 * because of B's own unrelated state. Without this, "A -&gt; brief B
	 * -&gt; A" would silently stop working across a restart, contrary to
	 * the "must survive restart safely" requirement. Deliberately NOT
	 * wired into the SUSPENDED branch's OWN separate weak-evidence
	 * arm/confirm mechanism (pendingCandidateIdentity et al.) -- that
	 * mechanism decides whether SUSPENDED's OWN identity should switch at
	 * all, a decision this feature must never influence; this check only
	 * ever runs at a point where that decision has ALREADY been made
	 * (immediate/authoritative switch, or the resume window already
	 * expired), exactly mirroring where applyRealSwitch() itself is only
	 * ever reached from the ACTIVE branch's own already-decided call
	 * sites.
	 *
	 * Returns the resumed Session (interruptedCandidate itself, mutated
	 * in place via the existing resume() helper -- same sessionId, same
	 * startedAt, zero active duration added for the interruption gap, for
	 * free) when `identity` matches the ONE parked candidate's own
	 * ActivityIdentity and `observedAt` is still at-or-before its
	 * eligibility boundary; null otherwise, in which case
	 * interruptedCandidate is left completely untouched and the caller's
	 * own pre-existing behavior (start a brand-new session) applies
	 * exactly as it always has.
	 */
	private Session tryResumeInterruptedCandidate(ActivityIdentity identity, Instant observedAt)
	{
		if (interruptedCandidate == null
			|| !interruptedCandidate.getActivityIdentity().equals(identity)
			|| observedAt.isAfter(interruptedCandidateExpiresAt))
		{
			return null;
		}
		Session resumed = interruptedCandidate;
		clearInterruptedCandidate();
		resume(resumed, observedAt);
		return resumed;
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; B -&gt; C invariant, generalized
	 * to a SUSPENDED outgoing B). "A parked + a genuine real switch to a
	 * non-A identity C = A is dead permanently" -- true regardless of
	 * whether the outgoing session B that C is superseding is itself
	 * ACTIVE or SUSPENDED at the moment of that second real switch. The
	 * ACTIVE-branch case is applyRealSwitch()'s own PARK-branch
	 * displacement (which now calls this same helper, rather than
	 * duplicating the logic). This is the SUSPENDED-branch equivalent:
	 * every SUSPENDED-branch call site that has just decided on a real
	 * switch to an identity NOT matching the parked candidate (checked
	 * immediately beforehand via tryResumeInterruptedCandidate() --
	 * never both consulted for the same decision) must permanently
	 * resolve/clear that now-superseded candidate here, rather than
	 * leaving it dangling until its own time-based expiry -- otherwise
	 * "A -&gt; B -&gt; restart -&gt; C -&gt; A" could resurrect A across two
	 * genuine activity changes, breaking the one-interruption model.
	 *
	 * Unconditionally finalizes and clears whatever candidate is
	 * currently parked, at its OWN original interruption instant --
	 * never at the confirming/switching event's own timestamp, so no
	 * active time is ever fabricated for it. Returns the finalized
	 * Session, or null when nothing was parked (the overwhelmingly
	 * common case, and always safe to call unconditionally).
	 *
	 * Deliberately NEVER parks/creates anything of its own -- pure
	 * disposal of an already-parked, now-superseded candidate. Does not
	 * itself decide whether the current call's own outgoing B should be
	 * parked (SUSPENDED outgoing sessions are never parked -- see
	 * applyRealSwitch()'s own javadoc and this class's "OUTGOING
	 * SUSPENDED SESSION" scope note).
	 */
	private Session displaceInterruptedCandidateIfPresent()
	{
		if (interruptedCandidate == null)
		{
			return null;
		}
		Session displaced = finalizeSession(interruptedCandidate, interruptedCandidateInterruptedAt);
		clearInterruptedCandidate();
		return displaced;
	}

	/**
	 * INTERRUPTED-RESUME housekeeping, called at the top of every
	 * public, timestamped entry point (onQualifyingActivity(),
	 * refineIdentity(), advanceTime()) -- see each call site's own
	 * comment. If the ONE parked interrupted candidate's eligibility
	 * window has strictly elapsed as of `asOf`, it permanently
	 * finalizes here, AT ITS OWN ORIGINAL INTERRUPTION INSTANT
	 * (interruptedCandidateInterruptedAt, never `asOf`) -- a delayed
	 * expiry decision must never fabricate extra active minutes onto
	 * the candidate by finalizing it at the later moment this happened
	 * to run. Returns the finalized Session (for the caller to report
	 * via LifecycleResult.withAdditionalFinalized()), or null when
	 * nothing was parked or the parked candidate is still within its
	 * window.
	 *
	 * Deliberately unconditional -- this housekeeping runs regardless
	 * of whether the CALLING entry point itself is allowed to create or
	 * resume interrupted-resume state (see allowInterruptedResume on
	 * onQualifyingActivity()'s 5-arg overload): an already-parked,
	 * already-decided candidate finalizing on schedule is ordinary
	 * lifecycle completion, not the creation of new resumable state, so
	 * it is never gated behind that flag.
	 */
	private Session expireInterruptedCandidateIfNeeded(Instant asOf)
	{
		if (interruptedCandidate == null)
		{
			return null;
		}
		if (asOf.isAfter(interruptedCandidateExpiresAt))
		{
			Session finalized = finalizeSession(interruptedCandidate, interruptedCandidateInterruptedAt);
			clearInterruptedCandidate();
			return finalized;
		}
		return null;
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A) -- the
	 * single helper both ACTIVE-branch "real switch" sites in
	 * onQualifyingActivityCore() delegate to once they (via the
	 * existing, completely untouched
	 * isWeakEvidenceAgainstEstablishedSession()/
	 * activeRequiredConfirmations() hysteresis) have already decided a
	 * genuine switch away from the established ACTIVE `current` session
	 * is happening. This method never itself decides WHETHER a switch
	 * is real -- only what happens to the outgoing session once one is.
	 *
	 * `metricsForNew` is this triggering event's own metrics -- applied
	 * to whichever session ends up "current" afterward (a freshly-
	 * started brand-new session, or a resumed parked candidate).
	 * `contextualForOld` is any earlier-buffered, now-abandoned
	 * candidate metrics that genuinely occurred during the OUTGOING
	 * session's own lifetime -- applied once, as contextual metrics, to
	 * whichever Session object the outgoing session ends up being (the
	 * same object reference whether it is parked, resumed-away-from-
	 * and-finalized, or immediately finalized), exactly like this
	 * engine's pre-existing contextual-metrics contract elsewhere.
	 *
	 * Three outcomes, in priority order:
	 *
	 * 1. RESUME -- `identity` matches the ONE parked interrupted
	 *    candidate's own ActivityIdentity (activityType+activityKey,
	 *    ActivityIdentity's existing equality contract -- see its own
	 *    javadoc) and `observedAt` is still at-or-before that
	 *    candidate's eligibility boundary, and allowInterruptedResume
	 *    is true: the outgoing session finalizes normally (this IS a
	 *    real, decided switch away from it) and the parked candidate
	 *    resumes as `current`, reusing the existing resume() helper
	 *    verbatim -- same sessionId, same startedAt, and (because
	 *    resume() sets lastActiveAt directly rather than calling
	 *    accumulate()) the entire interruption gap contributes zero
	 *    active duration, for free.
	 *
	 * 2. PARK -- not a resume, but allowInterruptedResume is true: the
	 *    outgoing session is parked as the new interrupted candidate
	 *    instead of being finalized. Enforces the "at most ONE
	 *    resumable candidate" rule first: a DIFFERENT candidate already
	 *    parked is displaced -- permanently finalized now, at ITS OWN
	 *    original interruption instant, and reported via
	 *    LifecycleResult.getAdditionalFinalized() (never the primary
	 *    `finalized` slot, which this outcome leaves null since the
	 *    outgoing session itself was not finalized, only parked).
	 *
	 * 3. LEGACY IMMEDIATE FINALIZE -- allowInterruptedResume is false
	 *    (the manual Re-evaluate Session path -- see
	 *    onQualifyingActivity()'s 5-arg overload javadoc): the
	 *    pre-existing behavior, byte-for-byte -- the outgoing session
	 *    finalizes immediately and unconditionally, exactly as this
	 *    engine did before this feature existed. Never touches
	 *    interruptedCandidate at all, so a manual correction can never
	 *    manufacture a hidden resumable prior session.
	 */
	private LifecycleResult applyRealSwitch(ActivityIdentity identity, Instant observedAt, List<MetricUpdate> metricsForNew, List<MetricUpdate> contextualForOld, boolean allowInterruptedResume)
	{
		Session outgoing = current;

		boolean resumesParkedCandidate = allowInterruptedResume
			&& interruptedCandidate != null
			&& interruptedCandidate.getActivityIdentity().equals(identity)
			&& !observedAt.isAfter(interruptedCandidateExpiresAt);

		if (resumesParkedCandidate)
		{
			Session resumed = interruptedCandidate;
			clearInterruptedCandidate();
			Session finalizedOutgoing = finalizeSession(outgoing, observedAt);
			resume(resumed, observedAt);
			current = resumed;
			if (contextualForOld.isEmpty())
			{
				return LifecycleResult.withMetrics(current, finalizedOutgoing, metricsForNew);
			}
			return LifecycleResult.withMetricsAndContextual(current, finalizedOutgoing, metricsForNew, finalizedOutgoing, contextualForOld);
		}

		if (!allowInterruptedResume)
		{
			Session finalizedOutgoing = finalizeSession(outgoing, observedAt);
			current = newSession(identity, observedAt);
			if (contextualForOld.isEmpty())
			{
				return LifecycleResult.withMetrics(current, finalizedOutgoing, metricsForNew);
			}
			return LifecycleResult.withMetricsAndContextual(current, finalizedOutgoing, metricsForNew, finalizedOutgoing, contextualForOld);
		}

		Session displacedCandidate = displaceInterruptedCandidateIfPresent();

		interruptedCandidate = outgoing;
		interruptedCandidateInterruptedAt = observedAt;
		interruptedCandidateExpiresAt = observedAt.plus(INTERRUPTED_RESUME_WINDOW);
		current = newSession(identity, observedAt);

		LifecycleResult result = contextualForOld.isEmpty()
			? LifecycleResult.withMetrics(current, null, metricsForNew)
			: LifecycleResult.withMetricsAndContextual(current, null, metricsForNew, outgoing, contextualForOld);
		return result.withAdditionalFinalized(displacedCandidate);
	}

	/**
	 * How many observations of the SAME candidate identity ACTIVE
	 * requires to confirm a switch. A downranking combat-branch candidate
	 * (see isDownrankingCombatChallenge()) uses the flat
	 * ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS; any other combat-branch
	 * candidate uses the flat ACTIVE_FLOOR_REQUIRED_CONFIRMATIONS. A
	 * SKILLING candidate decays linearly from
	 * ACTIVE_PEAK_REQUIRED_CONFIRMATIONS at lastActiveAt down to
	 * ACTIVE_FLOOR_REQUIRED_CONFIRMATIONS over
	 * ACTIVE_INERTIA_DECAY_PERIOD. Returns a real number since the caller
	 * compares an integer count against it with {@code >=}.
	 */
	private static double activeRequiredConfirmations(Instant lastActiveAt, Instant observedAt, ActivityType establishedType, ActivityIdentity candidateIdentity)
	{
		ActivityType candidateType = candidateIdentity.getActivityType();
		if (ActivityPrecedence.isCombatBranch(candidateType))
		{
			if (isDownrankingCombatChallenge(establishedType, candidateType))
			{
				return ACTIVE_DOWNRANK_REQUIRED_CONFIRMATIONS;
			}
			return ACTIVE_FLOOR_REQUIRED_CONFIRMATIONS;
		}

		long elapsedMillis = Duration.between(lastActiveAt, observedAt).toMillis();
		long decayPeriodMillis = ACTIVE_INERTIA_DECAY_PERIOD.toMillis();
		double decayFraction = decayPeriodMillis <= 0 ? 1.0 : Math.min(1.0, (double) elapsedMillis / decayPeriodMillis);
		return ACTIVE_PEAK_REQUIRED_CONFIRMATIONS
			- (ACTIVE_PEAK_REQUIRED_CONFIRMATIONS - ACTIVE_FLOOR_REQUIRED_CONFIRMATIONS) * decayFraction;
	}

	/**
	 * True when a generic COMBAT candidate is challenging an established
	 * SLAYER or BOSSING session.
	 */
	private static boolean isDownrankingCombatChallenge(ActivityType establishedType, ActivityType candidateType)
	{
		return candidateType == ActivityType.COMBAT
			&& (establishedType == ActivityType.SLAYER || establishedType == ActivityType.BOSSING);
	}


	private void clearPendingCandidate()
	{
		pendingCandidateIdentity = null;
		pendingCandidateFirstSeenAt = null;
		pendingCandidateMetrics.clear();
	}

	/**
	 * Changes
	 * an ACTIVE or SUSPENDED session's ActivityIdentity to a new,
	 * classifier-approved identity for the SAME continuous activity, with
	 * no accompanying metrics. Delegates to the 3-arg overload.
	 */
	public LifecycleResult refineIdentity(ActivityIdentity newIdentity, Instant observedAt)
	{
		return refineIdentity(newIdentity, observedAt, Collections.<MetricUpdate>emptyList());
	}

	/**
	 * Also carries this signal
	 * batch's own MetricUpdates. Changes an ACTIVE or SUSPENDED
	 * session's ActivityIdentity to a new, classifier-approved identity
	 * for the SAME continuous activity, WITHOUT: changing sessionId,
	 * changing startedAt, resetting accumulatedActiveDurationMillis,
	 * fabricating elapsed activity time, finalizing the session, or
	 * losing aggregates. This method does NOT itself decide whether a
	 * given refinement is legal -- that decision belongs entirely to
	 * ActivitySignalClassifier's own compatibility rules; this
	 * method trusts its caller exactly as onQualifyingActivity() trusts
	 * its caller for the identity it is given. ActivityIdentity itself
	 * remains immutable — this method replaces Session's reference to
	 * it wholesale, it never mutates an existing ActivityIdentity
	 * instance.
	 *
	 * Deliberately reuses the SAME-identity path's own accumulate/
	 * suspend/resume machinery: after re-keying the session's
	 * activityIdentity, observedAt is treated exactly as a
	 * same-identity qualifying heartbeat against the NEW identity —
	 * same out-of-order rejection, same 5-minute suspend threshold, and
	 * the same "before or at the resume-window boundary resumes"
	 * asymmetry as onQualifyingActivity(). This is what "refine, not
	 * replace" means operationally: never a full switch (no finalize,
	 * no new sessionId), but still real activity for duration/suspend/
	 * resume purposes, exactly like any other qualifying heartbeat --
	 * including, when it resumes a SUSPENDED session, abandoning any
	 * still-pending candidate exactly as a same-identity resume
	 * via onQualifyingActivity() does: its buffered metrics are
	 * committed once, as contextual metrics, to the resumed session.
	 *
	 * A SUSPENDED session whose resume window has already strictly
	 * elapsed as of observedAt is left untouched (no mutation at all,
	 * `metrics` dropped) — refinement must never resurrect an otherwise-
	 * expired session, which would fabricate activity time across a gap
	 * this engine already treats as a real absence. The caller should
	 * route that case through onQualifyingActivity() instead, which will
	 * correctly finalize the expired session and start a new one.
	 */
	public LifecycleResult refineIdentity(ActivityIdentity newIdentity, Instant observedAt, List<MetricUpdate> metrics)
	{
		Session expiredInterruptedCandidate = expireInterruptedCandidateIfNeeded(observedAt);
		LifecycleResult result = refineIdentityCore(newIdentity, observedAt, metrics);
		return result.withAdditionalFinalized(expiredInterruptedCandidate);
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A): the actual
	 * refinement logic, unchanged from before this feature existed --
	 * refineIdentity() never switches identity, never finalizes, never
	 * touches interruptedCandidate itself; the public wrapper above
	 * only adds the same background candidate-expiry housekeeping every
	 * other timestamped entry point performs.
	 */
	private LifecycleResult refineIdentityCore(ActivityIdentity newIdentity, Instant observedAt, List<MetricUpdate> metrics)
	{
		if (current == null || current.getState() == SessionState.FINALIZED)
		{
			// Nothing to refine — no-op. A caller with no current
			// session should use onQualifyingActivity() to start one.
			return LifecycleResult.of(current, null);
		}

		Instant lastActiveAt = current.lastActiveAtInstant();
		if (observedAt.isBefore(lastActiveAt))
		{
			// Out-of-order — reject entirely, no mutation (same rule as
			// onQualifyingActivity()).
			return LifecycleResult.of(current, null);
		}

		if (current.getState() == SessionState.SUSPENDED)
		{
			Instant resumeExpiry = current.resumeWindowExpiresAtInstant();
			if (observedAt.isAfter(resumeExpiry))
			{
				// Resume window has already elapsed — never resurrect via
				// refinement. See method javadoc.
				return LifecycleResult.of(current, null);
			}
			List<MetricUpdate> abandoned = new ArrayList<>(pendingCandidateMetrics);
			clearPendingCandidate();
			current.setActivityIdentity(newIdentity);
			resume(current, observedAt);
			if (abandoned.isEmpty())
			{
				return LifecycleResult.withMetrics(current, null, metrics);
			}
			return LifecycleResult.withMetricsAndContextual(current, null, metrics, current, abandoned);
		}

		// ACTIVE: re-key the identity, then accumulate exactly as a
		// normal same-identity heartbeat would — including a
		// same-instant suspend if observedAt has itself already reached
		// the 5-minute threshold. Refinement never bypasses the
		// ordinary suspend rule. Refinement itself never arms or resolves
		// the ACTIVE-CANDIDATE trio (it only ever refines the CURRENT
		// identity's own specificity, never proposes a competing one --
		// that decision belongs entirely to onQualifyingActivity()), but
		// a still-pending ACTIVE candidate, armed by an EARLIER,
		// unrelated call, must not be silently abandoned by this
		// refinement crossing the suspend threshold -- flushed here
		// exactly like onQualifyingActivity()'s own suspend-threshold
		// branch, so its buffered metrics are never lost.
		Instant suspendThreshold = lastActiveAt.plus(SUSPEND_TIMEOUT);
		current.setActivityIdentity(newIdentity);
		if (observedAt.isBefore(suspendThreshold))
		{
			accumulate(current, observedAt);
			return LifecycleResult.withMetrics(current, null, metrics);
		}
		suspend(current, suspendThreshold);
		List<MetricUpdate> abandonedFromActiveCandidate = new ArrayList<>(activeCandidateMetrics);
		clearActiveCandidate();
		if (abandonedFromActiveCandidate.isEmpty())
		{
			return LifecycleResult.withMetrics(current, null, metrics);
		}
		return LifecycleResult.withMetricsAndContextual(current, null, metrics, current, abandonedFromActiveCandidate);
	}

	/**
	 * The scheduler-only entry point: "time has passed, no new
	 * activity is being reported right now." Only ever suspends or
	 * finalizes — never starts, resumes, or accumulates duration. Never
	 * carries metricsForCurrent (no signal arrived), but a SUSPENDED
	 * session that times out here still needs to flush any pending
	 * candidate's buffered metrics -- see the SUSPENDED branch below.
	 * An ACTIVE session that
	 * suspends here also needs to flush any still-pending ACTIVE
	 * candidate (see the ACTIVE-CANDIDATE field javadoc) -- its buffered
	 * metrics are committed once, as contextual metrics, to the
	 * now-SUSPENDED session they genuinely occurred during, never lost
	 * and never silently carried forward into whatever the SUSPENDED
	 * candidate mechanism does next.
	 */
	public LifecycleResult advanceTime(Instant now)
	{
		Session expiredInterruptedCandidate = expireInterruptedCandidateIfNeeded(now);
		LifecycleResult result = advanceTimeCore(now);
		return result.withAdditionalFinalized(expiredInterruptedCandidate);
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A): the actual
	 * idle-timeout logic for `current`, unchanged from before this
	 * feature existed. The public wrapper above only adds the same
	 * background candidate-expiry housekeeping every other timestamped
	 * entry point performs -- so a parked candidate finalizes on
	 * schedule even on a tick where `current` itself does nothing at
	 * all.
	 */
	private LifecycleResult advanceTimeCore(Instant now)
	{
		if (current == null)
		{
			return LifecycleResult.of(null, null);
		}

		// Populated only when the ACTIVE branch just below suspends
		// `current` THIS call while an ACTIVE candidate was still
		// pending. Merged into whichever branch below ultimately returns.
		List<MetricUpdate> abandonedFromActiveCandidate = Collections.emptyList();

		if (current.getState() == SessionState.ACTIVE)
		{
			Instant suspendThreshold = current.lastActiveAtInstant().plus(SUSPEND_TIMEOUT);
			if (!now.isBefore(suspendThreshold))
			{
				suspend(current, suspendThreshold);
				abandonedFromActiveCandidate = new ArrayList<>(activeCandidateMetrics);
				clearActiveCandidate();
			}
			else
			{
				return LifecycleResult.of(current, null);
			}
		}

		if (current.getState() == SessionState.SUSPENDED)
		{
			Instant expiry = current.resumeWindowExpiresAtInstant();
			if (!now.isBefore(expiry))
			{
				// A pending candidate that never got confirmed
				// before the ORIGINAL session's own resume window simply
				// ran out -- neither it nor its buffered metrics ever
				// become "current" here (the timeout path starts nothing
				// new, current stays null until real activity is observed
				// again). Per Case 4 / invariant 3, those buffered
				// metrics are still real and must not be lost: they are
				// committed once, as contextual metrics, to the session
				// that is finalizing right now -- never fabricated into a
				// new session of their own. Merged with
				// abandonedFromActiveCandidate (reachable here: a
				// scheduler tick more than SUSPEND_TIMEOUT + RESUME_WINDOW
				// past lastActiveAt crosses both thresholds in one call).
				List<MetricUpdate> abandoned = new ArrayList<>(abandonedFromActiveCandidate);
				abandoned.addAll(pendingCandidateMetrics);
				clearPendingCandidate();
				Session finalized = finalizeSession(current, expiry);
				current = null;
				if (abandoned.isEmpty())
				{
					return LifecycleResult.of(null, finalized);
				}
				return LifecycleResult.withMetricsAndContextual(null, finalized, Collections.<MetricUpdate>emptyList(), finalized, abandoned);
			}
		}

		if (!abandonedFromActiveCandidate.isEmpty())
		{
			// The session suspended THIS call (pure idle timeout, no
			// resume-window expiry yet) and had a still-pending ACTIVE
			// candidate that never confirmed before the gap -- commit its
			// buffered metrics once, as contextual metrics, to the
			// now-SUSPENDED session it genuinely occurred during.
			return LifecycleResult.withMetricsAndContextual(current, null, Collections.<MetricUpdate>emptyList(), current, abandonedFromActiveCandidate);
		}

		return LifecycleResult.of(current, null);
	}

	/**
	 * Rehydration entry point — call once at plugin/account
	 * startup with whatever was loaded from session_state.json (null if
	 * none) and the actual current wall-clock Instant. See class-level
	 * RehydrationResult javadoc for the exact conservative rules
	 * applied to a persisted ACTIVE session. Delegates to the 5-arg
	 * overload below with no persisted interrupted candidate -- kept
	 * for every pre-existing caller/test.
	 */
	public static RehydrationResult rehydrate(Session persisted, Instant now)
	{
		return rehydrate(persisted, now, null, null, null);
	}

	/**
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A): also takes
	 * whatever was separately loaded from the additive interrupted-
	 * candidate persistence record (all three null when none exists --
	 * absence of that file is fully backward compatible with the 2-arg
	 * overload above). The persisted candidate and the persisted
	 * `current` session are reconciled completely independently of one
	 * another -- see class/feature javadoc: they have independent
	 * lifecycle clocks, so one expiring never mutates the other.
	 *
	 * If the candidate's own eligibility window has already elapsed as
	 * of `now`, it finalizes here, exactly once, AT ITS OWN ORIGINAL
	 * INTERRUPTION INSTANT (persistedInterruptedCandidateInterruptedAt,
	 * never `now`) -- reported via
	 * RehydrationResult#getFinalizedInterruptedCandidateDuringRehydration().
	 * Otherwise it is restored onto the returned engine as the ONE
	 * resumable prior candidate, unchanged.
	 */
	public static RehydrationResult rehydrate(Session persisted, Instant now,
		Session persistedInterruptedCandidate, Instant persistedInterruptedCandidateInterruptedAt, Instant persistedInterruptedCandidateExpiresAt)
	{
		Session finalizedDuringRehydration = null;
		SessionLifecycleEngine engine;

		if (persisted == null)
		{
			engine = new SessionLifecycleEngine();
		}
		else
		{
			if (persisted.getState() == SessionState.ACTIVE)
			{
				// Conservative reconciliation: the plugin cannot prove the
				// user remained active while RuneLite was closed, so a
				// persisted ACTIVE session is treated exactly as though the
				// ordinary 5-minute idle timeout had already elapsed at its
				// last known activity — deterministically, from lastActiveAt
				// alone, never from `now`, and never adding any offline gap
				// to accumulatedActiveDurationMillis (suspend() never
				// touches that field).
				Instant suspendThreshold = persisted.lastActiveAtInstant().plus(SUSPEND_TIMEOUT);
				suspend(persisted, suspendThreshold);
			}

			if (persisted.getState() == SessionState.SUSPENDED)
			{
				Instant expiry = persisted.resumeWindowExpiresAtInstant();
				if (!now.isBefore(expiry))
				{
					finalizedDuringRehydration = finalizeSession(persisted, expiry);
					engine = new SessionLifecycleEngine();
				}
				else
				{
					engine = new SessionLifecycleEngine(persisted);
				}
			}
			else
			{
				// persisted.getState() == FINALIZED: should not normally
				// occur in session_state.json (a finalized session must
				// already have been cleared from it), but handled
				// conservatively: it was already written to its own
				// immutable record by whatever process finalized it, so
				// it is not re-finalized or resurrected as "current" here.
				engine = new SessionLifecycleEngine();
			}
		}

		Session finalizedInterruptedCandidateDuringRehydration = null;
		if (persistedInterruptedCandidate != null)
		{
			if (persistedInterruptedCandidateExpiresAt != null && now.isAfter(persistedInterruptedCandidateExpiresAt))
			{
				finalizedInterruptedCandidateDuringRehydration =
					finalizeSession(persistedInterruptedCandidate, persistedInterruptedCandidateInterruptedAt);
			}
			else
			{
				engine.interruptedCandidate = persistedInterruptedCandidate;
				engine.interruptedCandidateInterruptedAt = persistedInterruptedCandidateInterruptedAt;
				engine.interruptedCandidateExpiresAt = persistedInterruptedCandidateExpiresAt;
			}
		}

		return new RehydrationResult(engine, finalizedDuringRehydration, finalizedInterruptedCandidateDuringRehydration);
	}

	private static Session newSession(ActivityIdentity identity, Instant at)
	{
		Session s = new Session();
		s.setSessionId(UUID.randomUUID().toString());
		s.setActivityIdentity(identity);
		s.setState(SessionState.ACTIVE);
		s.setStartedAt(at.toString());
		s.setLastActiveAt(at.toString());
		s.setAccumulatedActiveDurationMillis(0L);
		s.setAggregates(new SessionAggregates());
		return s;
	}

	private static void accumulate(Session s, Instant newLastActiveAt)
	{
		long elapsedMillis = Duration.between(s.lastActiveAtInstant(), newLastActiveAt).toMillis();
		s.setAccumulatedActiveDurationMillis(s.getAccumulatedActiveDurationMillis() + elapsedMillis);
		s.setLastActiveAt(newLastActiveAt.toString());
	}

	private static void suspend(Session s, Instant suspendedAt)
	{
		s.setState(SessionState.SUSPENDED);
		s.setSuspendedAt(suspendedAt.toString());
		s.setResumeWindowExpiresAt(suspendedAt.plus(RESUME_WINDOW).toString());
	}

	private static void resume(Session s, Instant resumedAt)
	{
		s.setState(SessionState.ACTIVE);
		s.setLastActiveAt(resumedAt.toString());
		s.setSuspendedAt(null);
		s.setResumeWindowExpiresAt(null);
	}

	private static Session finalizeSession(Session s, Instant finalizedAt)
	{
		s.setState(SessionState.FINALIZED);
		s.setFinalizedAt(finalizedAt.toString());
		return s;
	}

	/**
	 * Result of {@link #rehydrate(Session, Instant, Session, Instant, Instant)}:
	 * the engine to continue using, plus a session that had to be
	 * immediately finalized as part of rehydration (a persisted
	 * SUSPENDED session whose resume window had already expired, or
	 * null if rehydration produced no such finalization), plus --
	 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A) -- a
	 * separate, independent slot for the persisted interrupted
	 * candidate's own possible rehydration-time finalization (its
	 * eligibility window had already elapsed by `now`), or null if it
	 * either didn't exist or is still eligible and was restored onto
	 * `engine` instead. These two finalization slots are deliberately
	 * separate and never conflated: the two sessions have completely
	 * independent lifecycle clocks (see class/feature javadoc), and a
	 * restart can finalize either one, both, or neither.
	 */
	public static final class RehydrationResult
	{
		private final SessionLifecycleEngine engine;
		private final Session finalizedDuringRehydration;
		private final Session finalizedInterruptedCandidateDuringRehydration;

		private RehydrationResult(SessionLifecycleEngine engine, Session finalizedDuringRehydration, Session finalizedInterruptedCandidateDuringRehydration)
		{
			this.engine = engine;
			this.finalizedDuringRehydration = finalizedDuringRehydration;
			this.finalizedInterruptedCandidateDuringRehydration = finalizedInterruptedCandidateDuringRehydration;
		}

		public SessionLifecycleEngine getEngine()
		{
			return engine;
		}

		public Session getFinalizedDuringRehydration()
		{
			return finalizedDuringRehydration;
		}

		/** INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A): see class javadoc above. */
		public Session getFinalizedInterruptedCandidateDuringRehydration()
		{
			return finalizedInterruptedCandidateDuringRehydration;
		}
	}
}
