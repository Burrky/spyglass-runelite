package com.osrstelemetry.plugin.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The pure signal-to-decision classifier. Translates one SessionSignal
 * at a time (plus the current
 * Session, if any, and a per-account ClassifierContext) into a
 * SignalClassification. Never touches RuneLite, never subscribes to
 * anything, never reads Instant.now() -- every decision is a pure
 * function of its three arguments.
 *
 * BATCH RESOLUTION: this
 * classifier is intentionally stateless across calls -- it always
 * classifies ONE signal against whatever Session snapshot it is given,
 * never against other signals from the same real-world game action. A
 * future runtime coordinator MUST group same-tick signals (see
 * SessionSignalBatchResolver's gameTick key) and resolve them
 * together: when several of this classifier's independent outputs
 * disagree about identity (e.g. combat XP proposes generic COMBAT
 * while a same-tick SLAYER_TASK_PROGRESS proposes SLAYER), the
 * strongest, most specific COMPATIBLE identity must win regardless of
 * which signal happened to be classified first -- SessionSignalBatchResolver
 * implements that resolution deterministically and order-independently;
 * it replaces an earlier, incorrect "first classification
 * wins" policy.
 *
 * MULTI-SKILL XP: a different-skill
 * XP_CHANGE than the current SKILLING session's identity is still
 * classified here as a real switch, taken alone (e.g.
 * SKILLING/Woodcutting -> SKILLING/Mining) -- there is no per-skill
 * "incidental secondary XP" table here (no exhaustive multi-skill
 * mapping). What actually prevents Barbarian Fishing's incidental
 * Strength/Agility XP from fragmenting a session is
 * SessionSignalBatchResolver's same-tick stickiness rule, not this
 * method: those events share one real game tick, and the resolver
 * prefers whichever proposal matches the session's own current
 * identity when multiple proposals tie for a tick's "strongest"
 * identity. Secondary XP genuinely arriving on a DIFFERENT tick than
 * its primary skill's XP remains an open, unsolved edge case,
 * deliberately left undecided rather than guessed at.
 *
 * GG -&gt; Gargoyles activity-switch: decideCombatBranch()'s ACTIVE-session "never downgrade"
 * rule previously applied unconditionally to ANY less-specific
 * candidate identity, generic or specific alike -- correct for GENERIC
 * combat evidence (it carries no information about what is actually
 * being fought), but wrong for a lifecycle-carrying signal that
 * SPECIFICALLY identifies a genuinely different activity. Live
 * evidence: ACTIVE BOSSING/Grotesque Guardians, then repeated
 * SLAYER_TASK_PROGRESS events naming taskName "Gargoyles" from killing
 * ordinary Gargoyles (not the boss) never switched the session away
 * from Grotesque Guardians -- the boss session kept silently
 * accumulating normal Gargoyle loot and Slayer progress. Root cause:
 * classifySlayerTaskProgress()'s own proposed identity was relabeled
 * back to the stale BOSSING identity by decideCombatBranch()'s
 * lessSpecific branch before it ever reached SessionSignalBatchResolver,
 * so the resolver's own batch-level tie-break machinery never even saw
 * the real Gargoyles identity to weigh it.
 *
 * NARROWLY SCOPED, EXACTLY to classifySlayerTaskProgress()'s own call
 * into decideCombatBranch() (the new `fromAuthoritativeTaskProgress`
 * parameter, true only there): a SLAYER_TASK_PROGRESS event is direct,
 * per-kill telemetry about a specific task actually being worked on --
 * unlike combat-skill XP_CHANGE under a merely-tracked Slayer task
 * (classifyXpChange() above, `fromAuthoritativeTaskProgress` always
 * false there), which is inferred/incidental and must NOT gain this new
 * power (see
 * SessionRuntimeCoordinatorTest.zulrah_bossActivityContextEstablishesBossingSessionBeforeBossKill_sameSessionThroughout,
 * which relies on exactly that XP_CHANGE case still being protected).
 * When this flag is true and the new identity is less specific than an
 * ACTIVE current identity, the new identity is now proposed honestly
 * (a real switch) instead of being relabeled as the old one --
 * SessionSignalBatchResolver's own combat-branch rank tie-break
 * is still what correctly keeps a SAME-TICK
 * BOSS_KILL-plus-its-own-task-decrement resolved to the boss (BOSSING
 * outranks SLAYER when both appear in one batch), so a boss's own kill
 * -- which always also reports SLAYER_TASK_PROGRESS for whatever task it
 * counts toward -- is unaffected; only a SLAYER_TASK_PROGRESS arriving
 * with no corroborating same-tick BOSS_KILL now honestly proposes its
 * own specific identity.
 *
 * BOSS SELF-CONSUMPTION OF ITS OWN SLAYER TASK: the paragraph above assumed
 * a boss's own kill ALWAYS reports its task decrement in the SAME tick
 * as its BOSS_KILL/loot -- live evidence proved that false for Grotesque
 * Guardians specifically (the kill-count message and chest loot are
 * delayed several seconds behind the task decrement, behind a real
 * in-game chest-interaction step), so "no corroborating same-tick
 * BOSS_KILL" is NOT proof of a real switch when the current boss is
 * confirmed (see BossTaskAffinity) to consume its own named task.
 * classifySlayerTaskProgress() now suppresses the switch by default in
 * exactly that one case and defers to actual disambiguating evidence,
 * however many ticks later it arrives -- see that method's own javadoc,
 * ClassifierContext's pending boss-task candidate, classifyBossKill()/
 * classifyServerNpcLoot() (the confirm/abort halves), and
 * SessionSignalBatchResolver.applyBossSelfConsumptionCorroboration() (the
 * same-tick half) for the complete, corrected design. Every OTHER
 * SLAYER_TASK_PROGRESS case this paragraph originally described is
 * completely unaffected.
 *
 * CANDIDATE-METRIC BUFFERING (avoids an ambiguous kill's own
 * progress metric being stranded on the boss session after a later-confirmed
 * real switch): the paragraph above's own suppressed
 * SLAYER_TASK_PROGRESS metric is now BUFFERED in ClassifierContext,
 * never applied to any session while the candidate is pending -- see
 * ClassifierContext's own javadoc for the full lifecycle, and
 * classifyBossKill()/classifyServerNpcLoot()'s own comments for exactly
 * where a resolved candidate's buffered metric is finally applied
 * (self-consumption: the same, never-left BOSSING session; a real
 * switch: the brand-new Slayer session, immediately, not waiting for a
 * second kill). A candidate that never resolves (the boss session
 * itself ends for an unrelated reason) is flushed by
 * SessionRuntimeCoordinator.flushAbandonedBossTaskCandidateIfMatching()
 * rather than silently discarded.
 */
public final class ActivitySignalClassifier
{
	private static final Set<String> COMBAT_SKILLS = new HashSet<>();

	static
	{
		COMBAT_SKILLS.add("attack");
		COMBAT_SKILLS.add("strength");
		COMBAT_SKILLS.add("defence");
		COMBAT_SKILLS.add("ranged");
		COMBAT_SKILLS.add("magic");
		COMBAT_SKILLS.add("hitpoints");
	}

	public SignalClassification classify(SessionSignal signal, Session current, ClassifierContext context)
	{
		switch (signal.getKind())
		{
			case XP_CHANGE:
				return classifyXpChange(signal, current, context);
			case SLAYER_TASK_ASSIGNED:
				context.onSlayerTaskAssigned(signal.getSlayerTaskName(), signal.getSlayerTaskLocation());
				return SignalClassification.ignore();
			case NPC_INTERACTION_TARGET:
				// Context
				// only -- see SignalKind.NPC_INTERACTION_TARGET's own
				// javadoc and ClassifierContext.onNpcInteractionTarget().
				// Never itself a START_OR_HEARTBEAT/REFINE: an interaction
				// alone is not proof of combat (misclicks, bosses with
				// adds, bankers/shopkeepers already filtered out by the
				// collector before this signal is even built).
				context.onNpcInteractionTarget(signal.getNpcId(), signal.getNpcName(), signal.getGameTick(), signal.getObservedAt());
				return SignalClassification.ignore();
			case RAW_COMBAT_XP_OBSERVED:
				// See SignalKind.RAW_COMBAT_XP_OBSERVED's
				// own javadoc.
				return classifyRawCombatXpObserved(signal, current, context);
			case SLAYER_TASK_PROGRESS:
				return classifySlayerTaskProgress(signal, current, context);
			case SLAYER_TASK_COMPLETED:
				// Session-annotation only -- never its own standalone
				// Recent Activity entry by itself
				// -- see classifySlayerTaskCompleted()'s own javadoc.
				return classifySlayerTaskCompleted(signal, current, context);
			case BOSS_KILL:
				return classifyBossKill(signal, current, context);
			case BOSS_ACTIVITY_CONTEXT:
				return classifyBossActivityContext(signal, current);
			case RAID_COMPLETION:
			case ACTIVITY_COMPLETION:
			case QUEST_COMPLETED:
				// These ARE genuinely standalone completions.
				// Their own authoritative completion-count messages are
				// NOT attached to the current combat/slayer/skilling
				// session's aggregates here -- raids/activities have no
				// modeled ActivityIdentity of their own currently, so
				// there is no honest linkage to attach a session-scoped
				// count to; that is deferred to future work that
				// actually models raid/activity identity (see
				// SessionAggregates.ReliableCountKind.COMPLETIONS).
				return SignalClassification.completionOnly(CompletionNature.STANDALONE_ACTIVITY, null);
			case NPC_DEATH:
			case NPC_LOOT_ATTRIBUTED:
				return SignalClassification.ignore();
			case SERVER_NPC_LOOT:
				return classifyServerNpcLoot(signal, current, context);
			case COLLECTION_LOG_NEW_ITEM:
			case COMBAT_ACHIEVEMENT_COMPLETED:
				// No SessionAggregates field exists for these yet --
				// session-irrelevant for now
				// rather than inventing a speculative field.
				return SignalClassification.ignore();
			case STORAGE_SNAPSHOT:
				return SignalClassification.ignore();
			default:
				return SignalClassification.ignore();
		}
	}

	private SignalClassification classifyXpChange(SessionSignal signal, Session current, ClassifierContext context)
	{
		// DELAYED DURABLE-XP SESSION-OWNERSHIP: ROOT CAUSE: XP_CHANGE's own `observedAt` is this event's
		// FLUSH time (when SkillsCollector's slow, ~30s-aggregated
		// window finally closed) -- NOT when the underlying XP was
		// actually earned. Every branch below (Magic's unconditional
		// METRIC_ONLY, ordinary combat-skill evidence, Slayer XP,
		// ordinary skilling XP) ultimately lets its MetricUpdate ride
		// onto whichever session is CURRENT at the moment this signal is
		// classified -- correct when the window and the current session
		// genuinely overlap, but WRONG the instant a real session switch
		// (e.g. an authoritative BOSS_KILL, or a genuine new Slayer
		// task/target) has already happened strictly BETWEEN this
		// window's own start and its eventual flush: the XP was earned
		// under the OLD (by now already-finalized-and-persisted)
		// session, yet nothing previously stopped it from being credited
		// to the NEW one merely because it happened to be PROCESSED
		// after the switch. LIVE EVIDENCE: Zulrah -> Vorkath switch, then
		// a delayed RANGED XP_CHANGE for the Zulrah fight lands on
		// Vorkath; likewise Pyrefiends -> Banshees (new Slayer
		// assignment), then delayed Strength/Hitpoints/Magic/Slayer XP
		// from the finished Pyrefiend fight lands on Banshees.
		//
		// FIX: SessionEventMapper now threads EventPayloads.XpChange's
		// own windowStart through as signal.getXpWindowStart() -- the
		// STRONGEST already-existing timing information available (see
		// SessionSignal's own xpWindowStart javadoc), previously
		// discarded entirely. If this window's own start is BEFORE the
		// current session's own startedAt, the XP was unambiguously
		// earned before this exact session/identity even began -- it
		// cannot honestly belong to it. There is no live handle on the
		// OLD session left to reattach it to by this point (a real
		// switch FINALIZES and durably persists the old session
		// synchronously, at the moment of the switch -- see
		// SessionLifecycleEngine's own "real switch" branches -- so it is
		// already gone from memory long before a separately-batched,
		// later-arriving delayed XP_CHANGE like this one is ever
		// classified); rather than fabricate a new ownership-reassignment
		// mechanism for an already-immutable, already-persisted record
		// (out of scope for this fix), this is classified as a plain
		// IGNORE_FOR_SESSION: no metric anywhere, no identity effect, and
		// -- since SessionRuntimeCoordinator.applyMetrics() only ever
		// reconciles LiveXpTracker for a metric that actually gets
		// applied -- the new session's own live-pending XP is left
		// completely untouched by it (see LiveXpDisplayIntegrationTest's
		// own "delayed XP_CHANGE after a switch" tests).
		//
		// SCOPE: only guards against a window that predates the CURRENT
		// session's own startedAt. `current == null` (nothing to compare
		// against) and a null xpWindowStart (every pre-existing
		// caller/test, which never opted into this field) both leave
		// this guard a complete no-op -- every other branch below is
		// entirely unaffected, and the false-Slayer-promotion fix,
		// EvidenceStrength/T2-candidate semantics, and boss/Slayer
		// affinity logic are untouched.
		//
		// XP_CHANGE WINDOW/SESSION-BOUNDARY SPLIT: a window starting before the current
		// session's own startedAt is not always ENTIRELY stale -- the
		// aggregation window can SPAN the switch (open under the OLD
		// session, close/flush after the new one is already current),
		// in which case some of its delta was genuinely earned AFTER the
		// switch and legitimately belongs here. ClassifierContext holds
		// an exact per-skill "XP at session start" baseline, captured by
		// SessionRuntimeCoordinator at the precise instant this exact
		// session began (see ClassifierContext.noteSessionSwitchXpBaseline()'s
		// own javadoc). Given that baseline and this event's own absolute
		// newXp (signal.getXpNewValue()), the portion earned after the
		// switch is EXACTLY `newXp - xpAtSessionStart` -- never a
		// proportional/time-based guess (we deliberately do not know
		// when within the window the XP was earned, only the two exact
		// endpoints this comparison needs).
		if (current != null && signal.getXpWindowStart() != null
			&& signal.getXpWindowStart().isBefore(current.startedAtInstant()))
		{
			String spanningSkill = signal.getSkill();
			Long xpAtSessionStart = context.getXpAtSessionStart(current.getSessionId(), spanningSkill);
			if (xpAtSessionStart == null || signal.getXpNewValue() == null)
			{
				// No exact baseline available for this skill/session (an
				// older/synthetic caller that never opted into
				// xpNewValue, or this skill was never observed via a raw
				// StatChanged pulse before the switch happened) -- an
				// exact split is impossible without guessing, so this
				// conservatively falls back to the whole-window drop.
				// Rare in real play (RuneLite
				// fires StatChanged on every XP gain, so any skill that
				// actually gained XP under the old session would already
				// have seeded this baseline), but honestly acknowledged
				// rather than silently assumed away.
				return SignalClassification.ignore();
			}
			long postSwitchDelta = signal.getXpNewValue() - xpAtSessionStart;
			if (postSwitchDelta <= 0)
			{
				// The entire window's XP was earned at or before the
				// switch (case A: wholly stale) -- nothing here
				// legitimately belongs to the current session.
				return SignalClassification.ignore();
			}
			// Case B (spanning): only the EXACT post-switch portion is
			// credited, as a metric only -- this XP already occurred
			// under whatever identity the current session already has
			// (established by the real evidence that caused the switch
			// itself), so it never needs to re-propose/refine identity,
			// exactly like classifySlayerXp()'s own METRIC_ONLY pattern
			// for evidence that is real but not itself lifecycle-carrying.
			return SignalClassification.metricOnly(MetricUpdate.xp(spanningSkill, postSwitchDelta));
		}

		String skill = signal.getSkill();
		long delta = signal.getXpDelta() == null ? 0L : signal.getXpDelta();
		MetricUpdate metric = MetricUpdate.xp(skill, delta);

		if (isCombatSkill(skill))
		{
			// MAGIC XP IS LIFECYCLE-AMBIGUOUS, NOT LIFECYCLE PROOF.
			// Two live incidents established this:
			// first, a POH-teleport MAGIC +30 XP_CHANGE with a Slayer task
			// merely ASSIGNED fabricated a brand-new SLAYER/<task> session
			// (Magic never again computes
			// slayerIdentity() from tracked-task context alone). Second,
			// with NO current session at all, that same bare MAGIC +30
			// was still found to independently start a fresh generic
			// COMBAT session -- also false: a teleport, alchemy cast,
			// enchantment, or other utility spell is not proof of combat
			// either. MAGIC is the one COMBAT_SKILLS member with
			// substantial non-combat XP sources; ATTACK/STRENGTH/DEFENCE/
			// RANGED/HITPOINTS are audited and unaffected below -- they
			// remain reliably combat-sourced and keep the ordinary
			// slayerIdentity()/genericCombatIdentity() + decideCombatBranch()
			// path exactly as before.
			//
			// THE RULE: Magic XP by itself is a METRIC, never lifecycle
			// proof. It must never independently start COMBAT, start
			// SLAYER, resume a SUSPENDED COMBAT/SLAYER/BOSSING session, or
			// heartbeat/extend an ACTIVE session's duration purely because
			// Magic XP appeared -- whether or not a Slayer task is
			// tracked. So Magic XP is classified as METRIC_ONLY
			// unconditionally here: it is NEVER added to
			// SessionSignalBatchResolver's byIdentity map (only
			// START_OR_HEARTBEAT/REFINE classifications are), so it can
			// never itself win, extend, or resume a batch's lifecycle
			// outcome -- but its MetricUpdate is never null, so
			// SessionSignalBatchResolver still collects it into every
			// batch's metricUpdates unconditionally (its own
			// unconditional-metrics rule), and SessionRuntimeCoordinator's
			// existing RESOLVED/NO_EVIDENCE/AMBIGUOUS dispatch (completely
			// unchanged by this fix) already does exactly the right thing
			// with it in every case:
			//   - Same batch as trustworthy combat evidence (e.g. real
			//     HITPOINTS XP) that RESOLVES the batch to a winning
			//     identity: Magic's metric rides along in that same
			//     batch's metricUpdates and is credited to the newly-
			//     established/continued session exactly once, via
			//     engine.onQualifyingActivity()/refineIdentity()'s own
			//     metricsForCurrent handling.
			//   - No winning identity this batch (Magic is the ONLY
			//     evidence, or all evidence is otherwise ambiguous): a
			//     NO_EVIDENCE/AMBIGUOUS outcome, so no lifecycle transition
			//     happens at all -- the current session (if any) keeps its
			//     exact state, lastActiveAt untouched, ACTIVE stays ACTIVE
			//     and SUSPENDED stays SUSPENDED -- and the metric is
			//     credited directly to whatever session is ALREADY current
			//     (never fabricating one; silently dropped, exactly like
			//     classifySlayerXp()'s own no-session precedent, when
			//     there is no current session at all to attach it to).
			// No new code was needed in SessionSignalBatchResolver,
			// SessionLifecycleEngine, or SessionRuntimeCoordinator -- this
			// is the narrowest fix: purely how classifyXpChange()
			// classifies one skill.
			if (isMagicXpSkill(skill))
			{
				return SignalClassification.metricOnly(metric);
			}
			// GENERIC NPC RECOGNITION: when no fresh
			// recent NPC target is tracked, this falls back to the single
			// fixed genericCombatIdentity() ("Combat"). Otherwise it
			// prefers a NAMED generic identity for whatever attackable
			// NPC the player most recently, and still RECENTLY, interacted
			// with (see ClassifierContext.peekRecentTargetNpcNameIfFresh()
			// for the exact bounded/sliding-window rule) -- with ZERO
			// hand-maintained NPC name/ID table anywhere: the name comes
			// straight from RuneLite's own NPCComposition at interaction
			// time (see NpcInteractionTargetCollector), and
			// genericNpcCombatIdentity() below does nothing but normalize
			// it exactly the way bossIdentity()/slayerIdentity() already
			// normalize their own display names.
			//
			// FALSE SLAYER PROMOTION FROM ASSIGNMENT CONTEXT.
			// REMOVED: this branch used to prefer
			// slayerIdentity(context.getActiveSlayerTaskName(), ...)
			// whenever context.hasReliableActiveSlayerTask() was true --
			// i.e. whenever ANY Slayer task was merely ASSIGNED, however
			// long ago, regardless of what the player was actually
			// fighting. LIVE EVIDENCE: task assigned = Gargoyles, player
			// fighting a Desert Wolf then a Goat (correctly recognized as
			// COMBAT/Desert Wolf, then COMBAT/Goat, via genuine recent-NPC-
			// target evidence) -- ordinary combat XP_CHANGE for the Goat
			// fight nonetheless computed identity = SLAYER/Gargoyles here,
			// which decideCombatBranch() then REFINED into immediately
			// (generic-COMBAT -> SLAYER is an unconditional refine, no
			// candidate arm/confirm gate applies to a REFINE), silently
			// converting the whole session to a bogus SLAYER/Gargoyles
			// session -- with zero Gargoyles ever fought. A currently
			// assigned Slayer task is CONTEXT ONLY (see
			// ClassifierContext.hasReliableActiveSlayerTask()'s own
			// javadoc) -- it must never, by itself, promote ordinary
			// combat XP into that task's SLAYER identity. Only genuine,
			// specific Slayer lifecycle evidence -- SLAYER_TASK_PROGRESS,
			// handled entirely separately by classifySlayerTaskProgress()
			// below, completely unaffected by this fix -- may ever
			// establish/upgrade a SLAYER identity. This is exactly the
			// same "context/interaction alone is never proof" principle
			// NPC_INTERACTION_TARGET already enforces for generic NPC
			// recognition (see that SignalKind's own javadoc) -- an
			// assigned-but-not-yet-progressed Slayer task is no more
			// proof of current activity than a stale interaction target
			// is. context.hasReliableActiveSlayerTask()/getActiveSlayerTaskName()/
			// getActiveSlayerTaskLocation() are UNCHANGED and still
			// populated from SLAYER_TASK_ASSIGNED/SLAYER_TASK_PROGRESS --
			// this fix removes this method's own (sole) READ of them, not
			// the tracking itself; the active task remains available as
			// context for anything that legitimately needs it elsewhere
			// (e.g. SlayerCollector's own persisted current-task state and
			// NpcDeathCollector's activeSlayerTaskName annotation, both
			// entirely independent of ClassifierContext/this classifier).
			ActivityIdentity identity = genericCombatOrNpcIdentity(context, signal.getGameTick(), signal.getObservedAt());
			// EVIDENCE STRENGTH: ORDINARY
			// -- inferred combat XP, never itself proof of a specific
			// identity (see EvidenceStrength's own javadoc). This is the
			// SAME tier ordinary skilling XP gets below, deliberately: both
			// are provisional, non-authoritative telemetry. `false` here
			// (fromAuthoritativeTaskProgress) for the same reason as
			// always -- this is never a direct, per-event authoritative
			// claim; only classifySlayerTaskProgress() below passes `true`.
			return decideCombatBranch(identity, current, singleMetric(metric), false, EvidenceStrength.ORDINARY);
		}

		// Live evidence: a
		// delayed SLAYER XP_CHANGE (Slayer's own skill XP -- distinct
		// from SLAYER_TASK_PROGRESS, which is raw task-decrement
		// telemetry, never Slayer's XP skill) arrived after a
		// BOSS_KILL had already established a fresh BOSSING session,
		// and independently proposed SKILLING/slayer as a real switch
		// -- destroying the just-created BOSSING session. Root cause:
		// "slayer" is a real OSRS skill (it has its own XP), so it is
		// NOT in COMBAT_SKILLS above, and previously fell straight
		// into classifyNonCombatXp() exactly like Woodcutting or
		// Mining -- treating it as ordinary, identity-establishing
		// SKILLING lifecycle evidence. Slayer XP is frequently DELAYED
		// telemetry associated with combat/Slayer/bossing that has
		// already been correctly classified from stronger evidence
		// (SLAYER_TASK_PROGRESS, BOSS_KILL, combat XP) -- it must never
		// independently create or switch to SKILLING/slayer, and must
		// never be treated as equivalent to SLAYER_TASK_PROGRESS (task
		// identity is never inferred from it).
		if (isSlayerXpSkill(skill))
		{
			return classifySlayerXp(metric, current);
		}

		return classifyNonCombatXp(skill, metric, current);
	}

	/**
	 * Slayer XP is always a
	 * valid XP metric, but is NEVER sufficient lifecycle evidence on
	 * its own. If a combat-branch (COMBAT/SLAYER/BOSSING) session is
	 * already current -- ACTIVE or SUSPENDED, mirroring
	 * decideCombatBranch()'s own "SUSPENDED still counts" treatment of
	 * FINALIZED as the only non-current state -- the XP attaches to it
	 * as a metric only, never touching identity (e.g. an
	 * ACTIVE BOSSING/SLAYER session with delayed Slayer XP remains
	 * exactly as it was). With no compatible combat-branch session
	 * (none at all, a FINALIZED one, or a current SKILLING session),
	 * Slayer XP alone starts and switches nothing -- it is simply
	 * dropped for session purposes (SLAYER
	 * XP alone with no session does not create SKILLING/slayer).
	 */
	private SignalClassification classifySlayerXp(MetricUpdate metric, Session current)
	{
		if (current != null
			&& current.getState() != SessionState.FINALIZED
			&& ActivityPrecedence.isCombatBranch(current.getActivityIdentity().getActivityType()))
		{
			return SignalClassification.metricOnly(metric);
		}
		return SignalClassification.ignore();
	}

	/**
	 * "slayer" is Slayer's own OSRS skill (distinct from Attack/
	 * Strength/etc combat skills, and distinct from the raw
	 * SLAYER_TASK_PROGRESS/SLAYER_TASK_ASSIGNED/SLAYER_TASK_COMPLETED
	 * event types) -- deliberately its own small check rather than
	 * folded into COMBAT_SKILLS, since it must NOT go through
	 * decideCombatBranch()'s refine/switch machinery like a real
	 * combat skill would; see classifySlayerXp()'s own javadoc for the
	 * fix this exists for.
	 */
	static boolean isSlayerXpSkill(String skill)
	{
		return skill != null && "slayer".equals(skill.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Guards against utility Magic XP fabricating a specific Slayer
	 * session: "magic" is the one COMBAT_SKILLS member with substantial
	 * non-combat XP sources (teleports, alchemy, enchanting, other
	 * utility spellcasting) -- see classifyXpChange()'s own audit note
	 * for the live bug this exists to fix. Deliberately its own small
	 * check, mirroring isSlayerXpSkill()'s style, rather than a
	 * broader/implicit rule -- Attack/Strength/Defence/Ranged/Hitpoints
	 * are audited and unaffected: they remain reliable enough to be
	 * promoted straight to an assigned Slayer task's specific identity.
	 */
	static boolean isMagicXpSkill(String skill)
	{
		return skill != null && "magic".equals(skill.trim().toLowerCase(Locale.ROOT));
	}

	private SignalClassification classifyNonCombatXp(String skill, MetricUpdate metric, Session current)
	{
		// EVIDENCE STRENGTH: ordinary
		// skill XP is ORDINARY by definition -- consistent with
		// incidental (never itself-proof) evidence, and this is the exact
		// signal kind the live bug (one incidental AGILITY tick
		// finalizing an ACTIVE combat-branch session) was made of. This is
		// the ONE tag that actually alters observable behavior --
		// everywhere else, EvidenceStrength is
		// plumbing that reproduces the existing gate, not a new
		// gate. See SessionLifecycleEngine's own javadoc for how this tag
		// is consumed.
		ActivityIdentity identity = skillingIdentity(skill);
		if (current != null && current.getState() != SessionState.FINALIZED && identity.equals(current.getActivityIdentity()))
		{
			return SignalClassification.startOrHeartbeat(identity, metric, EvidenceStrength.ORDINARY);
		}
		// Different skill (or no current session): the earlier example
		// (Woodcutting -> Mining) is a real switch, not a refinement --
		// SKILLING has no internal specificity hierarchy. See this
		// class's javadoc for the same-tick-batching caveat that is
		// what actually protects incidental secondary XP from
		// fragmenting a session, not this method.
		return SignalClassification.startOrHeartbeat(identity, metric, EvidenceStrength.ORDINARY);
	}

	private SignalClassification classifySlayerTaskProgress(SessionSignal signal, Session current, ClassifierContext context)
	{
		// Threads this
		// event's own currentRemaining through to ClassifierContext so
		// classifySlayerTaskCompleted() below can later reconcile a
		// final task unit RuneLite never reports its own trailing
		// SLAYER_TASK_PROGRESS for -- see that method's own javadoc.
		context.onSlayerTaskProgress(signal.getSlayerTaskName(), signal.getSlayerTaskLocation(), signal.getSlayerCurrentRemaining());
		ActivityIdentity identity = slayerIdentity(signal.getSlayerTaskName(), signal.getSlayerTaskLocation());
		// signal.getSlayerUnitsConsumed() carries this ONE
		// event's own taskUnitsConsumed (see SessionSignal.slayerTaskProgress()'s
		// javadoc) -- passed straight through unmodified, never
		// re-derived from any absolute remaining/initial value here.
		// Slayer "task remaining" display: signal.getSlayerCurrentRemaining()
		// carries this same event's own authoritative currentRemaining
		// (nullable -- absent for older/synthetic callers), threaded
		// through into the same MetricUpdate so SessionAggregateUpdater
		// can retain the latest value for the player-facing display,
		// completely independent of the accumulated slayerProgressDelta
		// below.
		MetricUpdate metric = signal.getSlayerUnitsConsumed() == null
			? null
			: MetricUpdate.slayerProgress(signal.getSlayerUnitsConsumed(), signal.getSlayerCurrentRemaining());

		// BOSS SELF-CONSUMPTION OF ITS OWN SLAYER TASK -- a regression
		// introduced by the GG ->
		// Gargoyles fix below: that fix made ANY differently-named
		// SLAYER_TASK_PROGRESS switch an ACTIVE BOSSING session away
		// unconditionally -- correct for a genuinely different NPC (a
		// real Gargoyle kill after leaving Grotesque Guardians), but
		// WRONG for Grotesque Guardians' OWN kills, which ALSO decrement
		// the Gargoyles Slayer task (GG is a Slayer-task boss for
		// Gargoyles). Both cases produce an IDENTICAL event -- there is
		// no way to tell them apart from this one signal alone. See
		// BossTaskAffinity's own javadoc for the general (not
		// GG-specific) boss-owns-task data this relies on.
		//
		// SUSPENDED boss-own-task reactivation: live-persisted repro: a
		// SUSPENDED BOSSING/Shellbane Gryphon session, still well inside
		// its 30-minute resume window, received its own confirmed task's
		// SLAYER_TASK_PROGRESS ("Gryphons") and was immediately FINALIZED
		// -- creating the exact same zero-duration SLAYER bridge session
		// the ACTIVE-path fix above already eliminates, just crossing the
		// SUSPENDED lifecycle boundary instead. Root cause: this guard
		// previously required `current.getState() == ACTIVE` before ever
		// consulting BossTaskAffinity at all, so a SUSPENDED boss session
		// fell straight through to the unconditional-switch path at the
		// bottom of this method (`true` below), which decideCombatBranch()
		// then resolves via its own SUSPENDED "specific-vs-specific"
		// branch -- correctly treating GENUINELY different specific
		// evidence as a real switch while SUSPENDED (see that branch's
		// own "FOLLOW-UP FIX" comment), but with no way to know THIS
		// evidence was already-verified self-consumption, since it was
		// never given the chance to check.
		//
		// The fix widens this guard to ACTIVE OR SUSPENDED (both are
		// "still in progress" from BossTaskAffinity's point of view --
		// SessionRuntimeCoordinator/SessionLifecycleEngine never hand a
		// SUSPENDED session whose resume window has already expired to
		// the classifier at all; see decideCombatBranch()'s own
		// null/FINALIZED fast path and this class's expiredUnrelatedSession
		// test for that guarantee), and -- rather than routing through
		// decideCombatBranch()'s shared, STATE-DEPENDENT default -- returns
		// a heartbeat of the current boss identity directly. For ACTIVE
		// this is byte-for-byte the classification decideCombatBranch()
		// already produced here (its own lessSpecific/ACTIVE "never
		// downgrade" fallthrough, since fromAuthoritativeTaskProgress is
		// false and metrics is empty), so ACTIVE behavior is completely
		// unchanged. For SUSPENDED, this makes the already-verified
		// self-consumption case independent of decideCombatBranch()'s
		// SUSPENDED default entirely -- SessionLifecycleEngine's own
		// exact-identity-match resume path (onQualifyingActivity()'s
		// SUSPENDED branch: `identity.equals(current.getActivityIdentity())`)
		// is what actually reactivates the SUSPENDED session back to
		// ACTIVE, preserving its sessionId, startedAt, and every prior
		// aggregate untouched -- exactly like any other same-identity
		// resume, with zero new timers or confirmation-count logic.
		//
		// When the current ACTIVE or (still resumable) SUSPENDED session
		// is BOSSING for a boss confirmed to consume its own named task,
		// the switch is SUPPRESSED BY DEFAULT (`false` below -- i.e.
		// treated exactly like non-authoritative evidence, never-
		// downgrade) rather than applied immediately. This is
		// deliberately the narrowest correct fix: no bogus session is
		// ever created for the self-consumption case, so there is
		// nothing to reverse later. A ClassifierContext "pending boss
		// task candidate" is armed so the ambiguity can still be
		// resolved honestly once disambiguating evidence actually
		// arrives -- see ClassifierContext's own javadoc,
		// classifyBossKill() (aborts the candidate: a same-boss
		// authoritative kill confirms self-consumption -- its own
		// currentIdentity.equals(newIdentity) fast path in
		// decideCombatBranch() already fires unconditionally regardless
		// of ACTIVE/SUSPENDED),
		// classifyServerNpcLoot() (confirms the candidate: a different-NPC
		// loot proves a real switch), and
		// SessionSignalBatchResolver.applyBossSelfConsumptionCorroboration()
		// (the same-tick, order-independent half of this same fix).
		//
		// Every OTHER SLAYER_TASK_PROGRESS -- naming a task the current
		// boss is NOT confirmed to consume itself, or arriving with no
		// current ACTIVE/SUSPENDED BOSSING session at all -- is
		// completely unaffected and keeps the exact immediate-switch
		// behavior the prior fix established (`true` below, unchanged):
		// a SUSPENDED boss session receiving genuinely unrelated Slayer
		// progress, or a SUSPENDED unrelated boss receiving this boss's
		// task progress, still transitions normally.
		if (current != null
			&& (current.getState() == SessionState.ACTIVE || current.getState() == SessionState.SUSPENDED)
			&& current.getActivityIdentity().getActivityType() == ActivityType.BOSSING
			&& BossTaskAffinity.isBossOwnTask(current.getActivityIdentity().getActivityKey(), signal.getSlayerTaskName()))
		{
			// CANDIDATE-METRIC BUFFERING: `metric` is now
			// BUFFERED in the ClassifierContext candidate, never applied
			// here -- see ClassifierContext's own javadoc for the full
			// lifecycle. This heartbeat of the current boss identity
			// therefore carries NO metric of its own (empty list, not
			// `metric`); classifyBossKill()/classifyServerNpcLoot()/
			// applyBossSelfConsumptionCorroboration() are the only three
			// places this buffered metric is ever applied, exactly once,
			// to whichever session it turns out to genuinely belong to.
			context.armBossTaskCandidate(current.getActivityIdentity(), identity, metric);
			// EVIDENCE STRENGTH: SPECIFIC -- SLAYER_TASK_PROGRESS is direct,
			// per-kill telemetry naming a specific task, even though this
			// particular occurrence is being suppressed pending
			// disambiguation (see the comment above). Returned directly
			// (see the SUSPENDED reactivation note above) rather than via
			// decideCombatBranch(), so this one, already-disambiguated
			// case is never subject to that method's ACTIVE-vs-SUSPENDED
			// default asymmetry.
			return SignalClassification.startOrHeartbeat(current.getActivityIdentity(), Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
		}

		// GG -> Gargoyles activity-switch: `true`
		// here -- see this class's own javadoc for the full rationale.
		// SLAYER_TASK_PROGRESS is direct, per-kill telemetry naming a
		// specific task; it is allowed to switch an ACTIVE, more-specific
		// (BOSSING) session away, unlike inferred combat-XP-under-a-
		// tracked-task evidence (classifyXpChange() above, which always
		// passes `false`).
		// EVIDENCE STRENGTH: SPECIFIC -- direct, per-kill telemetry naming
		// this specific task, not itself a completion (that's BOSS_KILL's
		// own AUTHORITATIVE tier below).
		return decideCombatBranch(identity, current, singleMetric(metric), true, EvidenceStrength.SPECIFIC);
	}

	/**
	 * Reproducible SLAYER_TASK_COMPLETED remaining=1 bug. Live evidence:
	 * task progress counts down normally (e.g. 2 -&gt; 1), then RuneLite
	 * emits SLAYER_TASK_COMPLETED with NO final 1 -&gt; 0
	 * SLAYER_TASK_PROGRESS ever observed -- so, without this handling,
	 * latestSlayerCurrentRemaining would stay stuck at 1 forever and
	 * slayerProgressDelta would permanently miss the task's final unit.
	 *
	 * For a RELIABLY MATCHING current Slayer session/task only --
	 * `current` is non-FINALIZED, its own ActivityIdentity is SLAYER, it
	 * equals slayerIdentity(context's own currently-tracked task/
	 * location), AND this completion event's own taskName (raw, from the
	 * real RuneLite event -- see SessionSignal.slayerTaskCompleted()'s
	 * own javadoc; NOTE the real production event carries no
	 * taskLocation at all, which is exactly why this match is done by
	 * NAME against context's own tracked task rather than by comparing
	 * ActivityIdentity keys directly) names that SAME tracked task --
	 * this emits a MetricUpdate.slayerProgress() that:
	 *   - forces latestSlayerCurrentRemaining to exactly 0 (a task is
	 *     unconditionally fully done on SLAYER_TASK_COMPLETED), and
	 *   - adds context's own last-known currentRemaining (from the most
	 *     recent SLAYER_TASK_PROGRESS for this task, 0 when none was ever
	 *     observed) to slayerProgressDelta EXACTLY ONCE -- the exact
	 *     outstanding unit(s) no trailing PROGRESS event ever reported.
	 * If a normal PROGRESS event already reached 0 before this
	 * completion arrives, the last-known remaining is already 0, so this
	 * adds zero -- no double-counting.
	 *
	 * When the match is NOT reliable (no current session, a different
	 * task/location, a non-SLAYER or FINALIZED current identity -- e.g.
	 * an ACTIVE boss self-consuming its own task, see BossTaskAffinity)
	 * no metric is emitted at all: remaining/delta are left completely
	 * untouched rather than inventing a count for a session this event
	 * cannot be reliably shown to belong to. This never finalizes
	 * `current` itself -- ordinary lifecycle/suspend/resume logic is
	 * completely unchanged; final-kill loot/XP may still legitimately
	 * arrive after this event.
	 *
	 * context.onSlayerTaskCompleted() is called AFTER the match/metric
	 * computation above (never before) so this method can still read
	 * context's own pre-completion tracked task/location/remaining.
	 */
	private SignalClassification classifySlayerTaskCompleted(SessionSignal signal, Session current, ClassifierContext context)
	{
		MetricUpdate metric = null;
		if (current != null
			&& current.getState() != SessionState.FINALIZED
			&& current.getActivityIdentity().getActivityType() == ActivityType.SLAYER
			&& context.hasReliableActiveSlayerTask()
			&& current.getActivityIdentity().equals(slayerIdentity(context.getActiveSlayerTaskName(), context.getActiveSlayerTaskLocation()))
			&& normalizedSlayerTaskNamesEqual(signal.getSlayerTaskName(), context.getActiveSlayerTaskName()))
		{
			Integer lastKnownRemaining = context.getLastKnownSlayerCurrentRemaining();
			int outstandingUnits = lastKnownRemaining == null ? 0 : Math.max(0, lastKnownRemaining);
			metric = MetricUpdate.slayerProgress(outstandingUnits, 0);
		}
		context.onSlayerTaskCompleted();
		return SignalClassification.completionOnly(CompletionNature.SESSION_ANNOTATION_ONLY, metric);
	}

	private static boolean normalizedSlayerTaskNamesEqual(String a, String b)
	{
		if (a == null || b == null)
		{
			return false;
		}
		return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
	}

	private SignalClassification classifyBossKill(SessionSignal signal, Session current, ClassifierContext context)
	{
		ActivityIdentity identity = bossIdentity(signal.getBossOrActivitySource());
		// Boss self-consumption of its own Slayer task: a
		// same-boss authoritative BOSS_KILL is exactly the disconfirming
		// evidence that proves a pending boss-task candidate (see
		// ClassifierContext's own javadoc) was genuine self-consumption,
		// not a real different NPC -- abort it.
		//
		// CANDIDATE-METRIC BUFFERING: the candidate's own
		// buffered metric(s) (the ambiguous kill's Slayer progress delta
		// + currentRemaining, never applied anywhere while pending) are
		// retrieved BEFORE clearing and combined with this SAME kill's
		// own reliableCount metric -- both are destined for the SAME
		// session (this boss never left BOSSING, so this is a
		// delayed-but-correct application, never a retroactive
		// correction of anything already applied elsewhere).
		List<MetricUpdate> bufferedProgress = Collections.<MetricUpdate>emptyList();
		if (context.hasBossTaskCandidateFor(identity))
		{
			bufferedProgress = context.getPendingBossTaskCandidateMetrics();
			context.clearBossTaskCandidate();
		}
		MetricUpdate killMetric = signal.getReliableCount() == null
			? null
			: MetricUpdate.reliableCount(SessionAggregates.ReliableCountKind.KILLS, identity.getActivityKey(), signal.getReliableCount());
		// EVIDENCE STRENGTH: AUTHORITATIVE -- a hard completion signal for
		// exactly this identity. Always switches/refines immediately,
		// never gated by any candidate mechanism.
		return decideCombatBranch(identity, current, combineMetrics(bufferedProgress, killMetric), false, EvidenceStrength.AUTHORITATIVE);
	}

	/**
	 * The
	 * signal's ENTIRE session-lifecycle contribution: build the
	 * candidate boss identity via the SAME bossIdentity() canonicalization
	 * BOSS_KILL already uses (no duplicated normalization logic), then
	 * hand it to the SAME shared decideCombatBranch() refine-vs-switch-
	 * vs-ignore machinery XP_CHANGE, SLAYER_TASK_PROGRESS, and BOSS_KILL
	 * already go through -- no new session-lifecycle logic is introduced
	 * here or anywhere else. This is what lets an already-tracked SLAYER
	 * session correctly be outranked/refined into BOSSING by this signal
	 * exactly as it already is by a real BOSS_KILL (ActivityPrecedence:
	 * BOSSING rank 2 &gt; SLAYER rank 1).
	 *
	 * `metric` is passed as null UNCONDITIONALLY -- never derived from
	 * signal.getReliableCount() the way classifyBossKill() does, since
	 * SessionSignal.bossActivityContext() never sets reliableCount in
	 * the first place (see its own javadoc). This structurally
	 * guarantees this signal can never produce a
	 * MetricUpdate.reliableCount(...) instance, and therefore can never
	 * touch SessionAggregates' reliableCount -- not merely by
	 * convention, but because no code path from this method ever
	 * constructs one.
	 */
	private SignalClassification classifyBossActivityContext(SessionSignal signal, Session current)
	{
		ActivityIdentity identity = bossIdentity(signal.getBossOrActivitySource());
		// EVIDENCE STRENGTH: SPECIFIC -- direct, per-event telemetry that
		// specifically names this boss, but is not itself a completion
		// (see EvidenceStrength's own javadoc example, which is this
		// exact case).
		return decideCombatBranch(identity, current, Collections.<MetricUpdate>emptyList(), false, EvidenceStrength.SPECIFIC);
	}

	private SignalClassification classifyServerNpcLoot(SessionSignal signal, Session current, ClassifierContext context)
	{
		// Metric-only for an ALREADY-compatible ACTIVE session;
		// never starts/resumes/refines by itself. "Compatible" here
		// simply means "there is a currently ACTIVE session to attach
		// this loot to" -- SERVER_NPC_LOOT carries no ActivityIdentity
		// of its own to compare against.
		if (current == null || current.getState() != SessionState.ACTIVE)
		{
			return SignalClassification.ignore();
		}
		if (signal.getLootDrops() == null || signal.getLootDrops().isEmpty())
		{
			return SignalClassification.ignore();
		}

		// Boss self-consumption of its own Slayer task, combined with
		// CANDIDATE-METRIC BUFFERING: this
		// loot's own sourceName is exactly the disambiguating evidence a
		// pending boss-task candidate (see ClassifierContext's own
		// javadoc) has been waiting for, WHEN it is genuinely NOT the
		// boss itself (nor one of its known multi-NPC aliases -- see
		// BossTaskAffinity.isBossOwnNpcName()). That proves the earlier
		// suppressed SLAYER_TASK_PROGRESS came from a real, different NPC
		// -- confirm the candidate now: the switch happens here, anchored
		// at THIS loot's own observedAt. The earlier progress event's own
		// metric was never applied anywhere while pending (see
		// classifySlayerTaskProgress()) -- it is retrieved from the
		// candidate here and combined with this loot's own confirming
		// metric, both riding onto the brand-new Slayer session below.
		// This is what closes the former "stranded on the boss session"
		// limitation: the ambiguous kill's own progress now belongs to
		// the session it actually turned out to be.
		if (current.getActivityIdentity().getActivityType() == ActivityType.BOSSING
			&& context.hasBossTaskCandidateFor(current.getActivityIdentity())
			&& !BossTaskAffinity.isBossOwnNpcName(current.getActivityIdentity().getActivityKey(), signal.getBossOrActivitySource()))
		{
			// CANDIDATE-METRIC BUFFERING: the candidate's
			// own buffered metric(s) -- the ambiguous kill's Slayer
			// progress delta + currentRemaining, never applied anywhere
			// while pending -- are retrieved BEFORE clearing and combined
			// with THIS loot's own confirming metric, both destined for
			// the brand-new Slayer session: it now correctly shows
			// slayerProgressDelta/latestSlayerCurrentRemaining immediately
			// upon confirmation, never waiting for a second kill.
			ActivityIdentity candidate = context.getPendingBossTaskCandidateSlayerIdentity();
			List<MetricUpdate> bufferedProgress = context.getPendingBossTaskCandidateMetrics();
			context.clearBossTaskCandidate();
			MetricUpdate confirmingMetric = MetricUpdate.loot(signal.getBossOrActivitySource(), signal.getObservedAt(), signal.getLootDrops());
			// EVIDENCE STRENGTH: SPECIFIC -- this loot's own sourceName is
			// direct, per-kill corroboration of a genuinely different NPC,
			// confirming the real switch (not itself a completion signal
			// the way an authoritative BOSS_KILL/reliable count is).
			return SignalClassification.startOrHeartbeat(candidate, combineMetrics(bufferedProgress, confirmingMetric), EvidenceStrength.SPECIFIC);
		}

		MetricUpdate metric = MetricUpdate.loot(signal.getBossOrActivitySource(), signal.getObservedAt(), signal.getLootDrops());
		return SignalClassification.metricOnly(metric);
	}

	/**
	 * The shared refine-vs-switch-vs-ignore decision for any
	 * combat-branch (COMBAT/SLAYER/BOSSING) candidate identity against
	 * whatever session, if any, is currently in progress. Used by
	 * combat XP_CHANGE, SLAYER_TASK_PROGRESS, and BOSS_KILL alike --
	 * all three are combat-branch-qualifying signals.
	 *
	 * `fromAuthoritativeTaskProgress` (see this class's own javadoc for the
	 * full GG -&gt; Gargoyles activity-switch rationale): true ONLY when the caller is
	 * classifySlayerTaskProgress() -- i.e. this candidate identity comes
	 * directly from a SLAYER_TASK_PROGRESS event naming a specific task,
	 * not from combat XP under a merely-tracked task. Consulted ONLY in
	 * the ACTIVE + lessSpecific branch below; every other branch
	 * (moreSpecific, same-identity, SUSPENDED, incompatible-branch,
	 * same-rank-different-key) is completely unaffected by this
	 * parameter.
	 *
	 * `strength`: the calling
	 * signal's own EvidenceStrength, threaded straight through to every
	 * SignalClassification this method produces -- never re-derived or
	 * altered here from newIdentity/currentIdentity. See EvidenceStrength's
	 * own javadoc.
	 */
	private SignalClassification decideCombatBranch(ActivityIdentity newIdentity, Session current, List<MetricUpdate> metrics, boolean fromAuthoritativeTaskProgress, EvidenceStrength strength)
	{
		if (current == null || current.getState() == SessionState.FINALIZED)
		{
			return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
		}

		ActivityIdentity currentIdentity = current.getActivityIdentity();
		if (currentIdentity.equals(newIdentity))
		{
			return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
		}

		if (!ActivityPrecedence.isCombatBranch(currentIdentity.getActivityType())
			|| !ActivityPrecedence.isCombatBranch(newIdentity.getActivityType()))
		{
			// SKILLING (or any future non-combat type) on either side is
			// never compatible with the combat branch -- a real switch.
			return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
		}

		boolean currentIsGenericCombat = currentIdentity.getActivityType() == ActivityType.COMBAT;
		boolean moreSpecific = ActivityPrecedence.isMoreSpecific(newIdentity.getActivityType(), currentIdentity.getActivityType());
		boolean lessSpecific = ActivityPrecedence.isMoreSpecific(currentIdentity.getActivityType(), newIdentity.getActivityType());

		if (moreSpecific)
		{
			if (currentIsGenericCombat)
			{
				// generic COMBAT -> SLAYER or COMBAT -> BOSSING: refine.
				return SignalClassification.refine(newIdentity, metrics, strength);
			}
			// SLAYER -> BOSSING (or any other
			// more-specific jump from an already-specific identity) is
			// a REAL SWITCH, not a preserved heartbeat of the old
			// identity. There is no boss-to-Slayer-task compatibility
			// evidence available (no mapping database,
			// deliberately out of scope) -- an earlier "stay in
			// the Slayer session" choice was too permissive: it would
			// silently keep an unrelated Slayer session alive (and
			// attribute the boss kill's time to it) purely because a
			// Slayer task happened to still be tracked. The corrected,
			// conservative rule is: unknown compatibility means
			// switch, not merge -- finalize the old session and start a
			// fresh BOSSING one for the authoritative boss identity. A
			// future change with verified boss/task compatibility evidence
			// may choose to REFINE selected cases instead of switching.
			return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
		}

		if (lessSpecific)
		{
			// Magic XP / mixed-batch false resume. Live
			// evidence: two POH teleports' Magic XP (genericCombatIdentity()
			// -- no active Slayer task was tracked) arrived while a
			// SUSPENDED BOSSING/Zulrah session was still well inside its
			// resume window. Under an earlier, unconditional "never
			// downgrade -> treat as a heartbeat of
			// currentIdentity" rule, that generic evidence was relabeled AS
			// Zulrah itself before ever reaching SessionLifecycleEngine or
			// SessionSignalBatchResolver -- so the engine's own SUSPENDED
			// same-identity path saw what looked like genuine matching
			// evidence and resumed Zulrah unconditionally, with zero actual
			// proof Zulrah (rather than any other combat activity, or
			// nothing at all) was what the player was doing. Magic XP is
			// not trustworthy proof of combat by itself -- it can come from
			// teleporting, utility spellcasting, alchemy, enchanting, or
			// actual combat -- so it must never be strong enough to
			// resurrect a SPECIFIC suspended session on its own.
			//
			// NARROWLY SCOPED, EXACTLY to: current is SUSPENDED (an
			// ACTIVE session's own duration/heartbeat semantics --
			// "aggregate without changing identity" -- are completely
			// unaffected and unchanged below), AND newIdentity is the
			// GENERIC COMBAT identity specifically (never a real,
			// specifically-named identity -- a SLAYER_TASK_PROGRESS or
			// Slayer-task-context combat XP always proposes a genuine
			// slayerIdentity(), never genericCombatIdentity(), so this
			// carve-out never touches SLAYER_TASK_PROGRESS's own
			// "never downgrade an established BOSSING session" behavior --
			// that stays exactly as it was). Generic evidence against a
			// SUSPENDED session is reclassified as METRIC_ONLY -- a valid
			// XP contribution, but making no identity claim at all -- so it
			// can never independently resume/prove BOSSING, SLAYER, or any
			// other specific suspended activity; SessionSignalBatchResolver
			// then never sees it as a lifecycle candidate, closing the
			// mixed-batch loophole too (a same-tick specific SKILLING
			// signal resolves on its own, with nothing left to falsely tie
			// against via current-session stickiness). A currently-SUSPENDED
			// session's own aggregates still receive this metric directly
			// (SessionRuntimeCoordinator's existing NO_EVIDENCE/AMBIGUOUS
			// handling already credits every batch metric to whatever
			// session is already current) -- the XP is never lost, only its
			// false lifecycle claim is removed.
			if (current.getState() == SessionState.SUSPENDED)
			{
				if (newIdentity.getActivityType() == ActivityType.COMBAT)
				{
					// Generic combat evidence makes no identity claim at
					// all -- see the comment above.
					return SignalClassification.metricOnly(metrics);
				}

				// Suspended specific-vs-specific. The
				// carve-out above only covers GENERIC combat evidence.
				// newIdentity here is a genuinely SPECIFIC lower-rank
				// identity (e.g. a real slayerIdentity() from
				// SLAYER_TASK_PROGRESS, arriving while current is a
				// SUSPENDED higher-rank BOSSING identity) -- trustworthy,
				// named evidence for a DIFFERENT activity than the one
				// that is suspended. Relabeling it as currentIdentity
				// (the old unconditional behavior below) would silently
				// resurrect the wrong suspended session purely because of
				// ActivityPrecedence ranking, with zero evidence the
				// player ever returned to it. A suspended identity is
				// only resumable when evidence actually supports THAT
				// identity (see the exact-identity-match fast path
				// above) -- so specific different evidence must not be
				// pre-emptively relabeled here. Propose it honestly as
				// newIdentity instead; SessionLifecycleEngine's own
				// authoritative-different-identity-while-suspended logic
				// (isWeakEvidenceAgainstEstablishedSession() and its
				// caller) decides what happens next -- both SLAYER and
				// BOSSING are combat-branch, so this is never "weak"
				// evidence, and the suspended session is finalized while
				// the new specific identity starts cleanly, exactly like
				// any other authoritative different-identity evidence
				// against a suspended session.
				return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
			}

			// ACTIVE. GG -> Gargoyles activity-switch (see this
			// class's own javadoc): a SLAYER_TASK_PROGRESS
			// event (fromAuthoritativeTaskProgress == true) directly and
			// specifically identifies a genuinely different activity --
			// it must not be silently relabeled as the old, more-specific
			// identity merely because of ActivityPrecedence ranking. This
			// mirrors the SUSPENDED branch's own specific-vs-specific
			// carve-out above, but scoped by SIGNAL ORIGIN rather than by
			// ActivityType alone: combat XP inferred from a merely-tracked
			// Slayer task (fromAuthoritativeTaskProgress == false) is NOT
			// included here and keeps the exact "never downgrade" behavior
			// below (see classifyXpChange()'s own comment for why -- an
			// ACTIVE boss fight with an unrelated Slayer task tracked in
			// the background must not be destroyed by that fight's own
			// ordinary combat XP).
			if (fromAuthoritativeTaskProgress)
			{
				return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
			}

			// SLAYER TASK-FAMILY MEMBERSHIP. newIdentity here is
			// always ActivityType.COMBAT when currentIdentity is
			// ActivityType.SLAYER (COMBAT is the only type ranked below
			// SLAYER -- see ActivityPrecedence), i.e. ordinary combat XP or
			// RAW_COMBAT_XP_OBSERVED naming a specific (or unnamed generic
			// fallback) NPC. This is no longer unconditionally absorbed by
			// the "never downgrade" rule below -- see
			// SlayerTaskFamilyRegistry's own javadoc. A genuine ON-TASK
			// alternative/superior/boss-form NPC for the assigned task
			// (e.g. Flaming pyrelord for Pyrefiends), or the unnamed
			// genericCombatIdentity() fallback (no fresh recent NPC target
			// at all -- no evidence either way), still falls straight
			// through to "never downgrade" immediately below. A genuinely OFF-TASK, specifically-named
			// NPC is instead now honestly proposed as newIdentity, so
			// deliberate, SUSTAINED off-task combat can still escape the
			// Slayer session via the SAME EvidenceStrength.ORDINARY "weak
			// evidence" ACTIVE-candidate gate SessionLifecycleEngine
			// already applies to classifyNonCombatXp()'s SKILLING evidence
			// (see isWeakEvidenceAgainstEstablishedSession()) -- a single
			// incidental off-task tick (a cannon splash, a stray NPC
			// death, which never reaches this classifier as combat XP at
			// all) never switches anything by itself; only a SECOND,
			// coherent, confirming observation of the SAME off-task
			// identity does. Never consulted for a BOSSING currentIdentity
			// (e.g. Grotesque Guardians) -- that precedence remains
			// entirely governed by BossTaskAffinity, untouched here.
			if (currentIdentity.getActivityType() == ActivityType.SLAYER
				&& !isOnTaskFamilyOrUnknownNpc(currentIdentity, newIdentity))
			{
				return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
			}

			// Never downgrade: generic (or inferred-specific,
			// non-authoritative) later evidence continues the
			// already-more-specific compatible session instead of
			// erasing it. This concerns only LESS-specific evidence
			// arriving against an already-specific identity, never
			// more-specific evidence (see the moreSpecific branch above,
			// which is a real switch). Unchanged for an
			// ACTIVE session ("aggregate without changing
			// identity") for every case except the
			// fromAuthoritativeTaskProgress carve-out immediately above.
			// EVIDENCE STRENGTH: this classification continues
			// currentIdentity, not newIdentity -- `strength` still
			// describes THIS signal's own provenance and is carried
			// through unchanged; it is never re-derived from
			// currentIdentity/its ActivityType.
			return SignalClassification.startOrHeartbeat(currentIdentity, metrics, strength);
		}

		// Same specificity rank, different key (BOSSING/Zulrah vs
		// BOSSING/Vorkath, SLAYER/Gargoyles vs SLAYER/Abyssal demons):
		// always a real switch, never a refinement.
		return SignalClassification.startOrHeartbeat(newIdentity, metrics, strength);
	}

	/**
	 * SLAYER TASK-FAMILY MEMBERSHIP. True when `candidateIdentity`
	 * (always ActivityType.COMBAT -- see call site) carries no real NPC
	 * name at all (the unnamed genericCombatIdentity() fallback -- no
	 * fresh recent NPC target was tracked, so there is no evidence
	 * either way, on-task or off), OR its own NPC display name is a
	 * confirmed member of `slayerIdentity`'s own assigned task family
	 * per SlayerTaskFamilyRegistry.belongsToTaskFamily(). False only for
	 * a SPECIFICALLY-named NPC confirmed NOT to belong to that task.
	 */
	private static boolean isOnTaskFamilyOrUnknownNpc(ActivityIdentity slayerIdentity, ActivityIdentity candidateIdentity)
	{
		if (candidateIdentity.getActivityKey().equals(genericCombatIdentity().getActivityKey()))
		{
			return true;
		}
		return SlayerTaskFamilyRegistry.belongsToTaskFamily(slayerIdentity.getDisplayName(), candidateIdentity.getDisplayName());
	}

	private static List<MetricUpdate> singleMetric(MetricUpdate metric)
	{
		return metric == null ? Collections.<MetricUpdate>emptyList() : Collections.singletonList(metric);
	}

	/**
	 * CANDIDATE-METRIC BUFFERING: concatenates a
	 * previously-buffered candidate metric list (see ClassifierContext)
	 * with this signal's own metric (nullable), preserving order --
	 * buffered-first, this event's own metric last. Used by
	 * classifyBossKill() and classifyServerNpcLoot() to combine a
	 * resolved candidate's buffered progress metric(s) with the
	 * resolving signal's own metric, both destined for the same
	 * winning identity.
	 */
	private static List<MetricUpdate> combineMetrics(List<MetricUpdate> buffered, MetricUpdate ownMetric)
	{
		List<MetricUpdate> combined = new ArrayList<>(buffered);
		if (ownMetric != null)
		{
			combined.add(ownMetric);
		}
		return combined;
	}

	static boolean isCombatSkill(String skill)
	{
		return skill != null && COMBAT_SKILLS.contains(skill.trim().toLowerCase(Locale.ROOT));
	}

	static ActivityIdentity genericCombatIdentity()
	{
		// Deliberately broad, stable, never guessed from
		// NPC_DEATH/NPC_LOOT_ATTRIBUTED/nearby NPCs.
		return new ActivityIdentity(ActivityType.COMBAT, "combat", "Combat");
	}

	/**
	 * PEEKS rather than CONSUMES. The
	 * ONLY caller is classifyXpChange()'s combat branch, and ONLY when
	 * no reliable Slayer task is tracked (a tracked task always wins via
	 * slayerIdentity() -- this method is never even consulted then).
	 * Reads ClassifierContext's own short-lived recent-target watch (see
	 * its javadoc for the exact bounded rule) WITHOUT refreshing it --
	 * XP_CHANGE is SkillsCollector's own slow, aggregated signal (see
	 * ClassifierContext's TEMPORAL-DESIGN javadoc section)
	 * and must never itself be trusted to extend the watch's freshness,
	 * only RAW_COMBAT_XP_OBSERVED's own classification (see
	 * classifyRawCombatXpObserved() below) does that now. Falls back to
	 * the plain, unnamed genericCombatIdentity() when there is no fresh
	 * recent target.
	 */
	static ActivityIdentity genericCombatOrNpcIdentity(ClassifierContext context, Long currentTick, Instant currentObservedAt)
	{
		String recentTargetName = context.peekRecentTargetNpcNameIfFresh(currentTick, currentObservedAt);
		if (recentTargetName == null)
		{
			return genericCombatIdentity();
		}
		return genericNpcCombatIdentity(recentTargetName);
	}

	/**
	 * Handles SignalKind.RAW_COMBAT_XP_OBSERVED -- see
	 * that constant's own javadoc for the full rationale (closing the
	 * gap between a fresh interaction and XP_CHANGE's slow aggregated
	 * cadence).
	 *
	 * Defense-in-depth skill filter, even though the production
	 * collector (SkillsCollector.onStatChanged()) already only ever
	 * emits this kind for a genuine combat skill, never Magic: this
	 * method independently re-checks isCombatSkill()/isMagicXpSkill()
	 * against the SAME COMBAT_SKILLS set/exclusion classifyXpChange()
	 * itself uses, so a future collector regression could never smuggle
	 * an unreliable (e.g. Magic, or a non-combat) skill's raw pulse into
	 * proposing a session identity.
	 *
	 * Consumes (reads AND refreshes -- see
	 * ClassifierContext.consumeRecentTargetNpcNameIfFresh()) the
	 * recent-target watch. When nothing is fresh, this signal has
	 * nothing useful to contribute and is IGNORED outright -- it
	 * deliberately never falls back to the plain, unnamed
	 * genericCombatIdentity() the way classifyXpChange()'s combat branch
	 * does, so a raw pulse with no NPC context never floods the
	 * ACTIVE-candidate machinery with generic-COMBAT noise on every
	 * single hit; XP_CHANGE remains the one path that ever proposes the
	 * plain fallback identity.
	 *
	 * ALWAYS EvidenceStrength.ORDINARY and ALWAYS an EMPTY metrics list
	 * -- see SignalKind.RAW_COMBAT_XP_OBSERVED's own "NEVER AN XP
	 * METRIC" section: this is lifecycle-only evidence, structurally
	 * incapable of contributing to SessionAggregates.
	 */
	private SignalClassification classifyRawCombatXpObserved(SessionSignal signal, Session current, ClassifierContext context)
	{
		String skill = signal.getSkill();
		if (!isCombatSkill(skill) || isMagicXpSkill(skill))
		{
			return SignalClassification.ignore();
		}

		String recentTargetName = context.consumeRecentTargetNpcNameIfFresh(signal.getGameTick(), signal.getObservedAt());
		if (recentTargetName == null)
		{
			return SignalClassification.ignore();
		}

		ActivityIdentity identity = genericNpcCombatIdentity(recentTargetName);
		return decideCombatBranch(identity, current, Collections.<MetricUpdate>emptyList(), false, EvidenceStrength.ORDINARY);
	}

	/**
	 * Public entry point for
	 * SessionRuntimeCoordinator.reEvaluateCurrentSession() ONLY -- the
	 * player-triggered "Re-evaluate Session" button's backend hook. Reuses
	 * this class's own decideCombatBranch() precedence/refine/switch rules
	 * verbatim (never-downgrade-a-more-specific-ACTIVE-session via generic
	 * evidence, the SUSPENDED specific-vs-specific carve-out, the SLAYER
	 * task-family-membership handling -- every one of them completely
	 * unchanged) for a manually-observed, right-now NPC interaction
	 * target, with a CALLER-SUPPLIED EvidenceStrength instead of the fixed
	 * ORDINARY every other combat-branch caller uses. Threading a stronger
	 * strength through -- and ONLY that -- is what lets a manual
	 * re-evaluation bypass SessionLifecycleEngine's weak-evidence
	 * candidate/stickiness gate for this one observation (see
	 * EvidenceStrength's own javadoc: SPECIFIC "always switches/refines
	 * immediately"); decideCombatBranch()'s own branch selection never
	 * consults `strength` at all (see its own javadoc: "threaded straight
	 * through ... never re-derived or altered here"), so every existing
	 * identity-precedence protection applies exactly as it does for any
	 * other caller. This is a pure additive overload -- classifyXpChange(),
	 * classifySlayerTaskProgress(), classifyBossKill(), and
	 * classifyRawCombatXpObserved() are completely unaffected.
	 *
	 * Never itself resolves an NPC name to a boss or Slayer identity --
	 * exactly like classifyRawCombatXpObserved(), this always proposes a
	 * generic, name-keyed COMBAT identity (genericNpcCombatIdentity()) for
	 * whatever NPC name the caller supplies, with an always-empty metrics
	 * list (a manual re-evaluation never fabricates XP/loot -- see the
	 * coordinator's own javadoc). A currently-tracked Slayer task is never
	 * consulted here, matching the "Slayer assignment alone must never
	 * establish a Slayer session" rule exactly as classifyXpChange()'s own
	 * combat branch already enforces it (see that method's Magic XP
	 * comment) -- only genuine SLAYER_TASK_PROGRESS/BOSS_KILL/
	 * BOSS_ACTIVITY_CONTEXT evidence, processed through the ordinary
	 * signal pipeline exactly as before, may ever refine this into
	 * SLAYER/BOSSING.
	 */
	public SignalClassification classifyManualNpcTarget(String npcName, Session current, EvidenceStrength strength)
	{
		ActivityIdentity identity = genericNpcCombatIdentity(npcName);
		return decideCombatBranch(identity, current, Collections.<MetricUpdate>emptyList(), false, strength);
	}

	/**
	 * A stable,
	 * name-based generic COMBAT identity for a SPECIFIC attackable NPC
	 * (e.g. "Abyssal spectre", "Hill Giant") -- deliberately still
	 * ActivityType.COMBAT (rank 0 in ActivityPrecedence's combat-branch
	 * ordering), never a new ActivityType, so every existing
	 * SLAYER/BOSSING precedence, refinement, and "never downgrade an
	 * ACTIVE more-specific session" rule in decideCombatBranch() and
	 * SessionSignalBatchResolver already applies to it completely
	 * unchanged. Keyed by normalized NPC NAME only (mirroring
	 * bossIdentity()'s own name-based canonicalization) -- deliberately
	 * NOT by NPC id, so a boss/monster that legitimately changes runtime
	 * NPC id mid-fight (phase transitions, transformed forms) does not
	 * fragment one continuous generic-combat session into several. No
	 * hand-maintained NPC table is consulted here or anywhere in this
	 * method: npcName is trusted verbatim from whatever
	 * NpcInteractionTargetCollector already filtered to a genuinely
	 * attackable NPC.
	 */
	static ActivityIdentity genericNpcCombatIdentity(String npcName)
	{
		String key = npcName.trim().toLowerCase(Locale.ROOT);
		return new ActivityIdentity(ActivityType.COMBAT, key, npcName);
	}

	static ActivityIdentity slayerIdentity(String taskName, String taskLocation)
	{
		// Stable key from taskName + taskLocation ONLY -- never
		// amountRemaining/initialAmount/points/streak/masterId, which
		// are progress/metadata and must never split a session.
		String normalizedName = taskName == null ? "unknown" : taskName.trim().toLowerCase(Locale.ROOT);
		String key = (taskLocation == null || taskLocation.trim().isEmpty())
			? normalizedName
			: normalizedName + "@" + taskLocation.trim().toLowerCase(Locale.ROOT);
		String displayName = taskName == null ? "Slayer task" : taskName;
		return new ActivityIdentity(ActivityType.SLAYER, key, displayName);
	}

	static ActivityIdentity bossIdentity(String bossName)
	{
		// Authoritative source name only -- never current KC,
		// display formatting, or timestamp; the same boss across
		// consecutive authoritative kills always yields the same key.
		String key = bossName == null ? "unknown" : bossName.trim().toLowerCase(Locale.ROOT);
		String displayName = bossName == null ? "Boss" : bossName;
		return new ActivityIdentity(ActivityType.BOSSING, key, displayName);
	}

	static ActivityIdentity skillingIdentity(String skill)
	{
		// Small, stable per-skill identity, not an exhaustive
		// method database.
		String key = skill == null ? "unknown" : skill.trim().toLowerCase(Locale.ROOT);
		String displayName = skill == null ? "Skilling" : capitalize(skill);
		return new ActivityIdentity(ActivityType.SKILLING, key, displayName);
	}

	private static String capitalize(String s)
	{
		String trimmed = s.trim();
		if (trimmed.isEmpty())
		{
			return trimmed;
		}
		return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase(Locale.ROOT);
	}
}
