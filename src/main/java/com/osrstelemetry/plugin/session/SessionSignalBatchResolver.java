package com.osrstelemetry.plugin.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class never lets alphabetical order decide gameplay semantics:
 * an earlier version fell back to an alphabetical activityKey
 * comparison whenever two same-rank identities tied with no
 * current-session match -- that made "SKILLING/agility wins over
 * SKILLING/fishing" an accident of string sort order rather than real
 * evidence. A genuine tie that current-session stickiness cannot break
 * is now reported as {@link BatchLifecycleOutcome#AMBIGUOUS}, not
 * silently resolved.
 *
 * GROUPING: {@link #groupIntoBatches} groups by {@code gameTick} when
 * present, falling back to exact {@code observedAt} equality only for
 * signals with no tick.
 *
 * RESOLUTION: {@link #resolve} classifies every signal in
 * a batch independently against the same pre-batch `current` snapshot,
 * keeps every metric unconditionally, then resolves the batch's
 * lifecycle-carrying classifications via {@link #resolveOutcome}:
 *
 *   1. If every lifecycle-carrying classification proposes the SAME
 *      identity, that identity is trivially RESOLVED.
 *   2. Otherwise, the single generic COMBAT identity (if present) is
 *      dropped whenever ANY more specific evidence exists in the same
 *      batch -- generic COMBAT carries no specific information and
 *      never wins against a named Skilling/Slayer/Bossing identity.
 *   3. If exactly one specific identity remains, it is RESOLVED.
 *   4. If the remaining specific identities are ALL within the combat
 *      branch (SLAYER/BOSSING -- this is the one place a numeric rank
 *      is used, because BOSSING > SLAYER > COMBAT is an explicitly
 *      approved product decision, not an arbitrary tie-break), the
 *      highest-ranked group wins outright if it has exactly one member
 *      -- this is what lets an authoritative BOSS_KILL switch a Slayer
 *      session without consulting stickiness at all (stickiness must
 *      never swallow a real switch).
 *      A tie WITHIN that top-ranked group (two different bosses, or
 *      two different Slayer tasks) falls through to step 5.
 *   5. Any remaining tie -- same-rank combat-branch conflicts, two or
 *      more distinct Skilling identities, or a mix of Skilling and
 *      combat-branch evidence with no rank relationship between them
 *      (SKILLING is never compared to SLAYER/BOSSING by number) -- is
 *      resolved ONLY by an EXACT match against the session's own
 *      pre-batch current identity ("current-session stickiness" /
 *      "current-session exact match").
 *      If no candidate matches current, the batch is AMBIGUOUS: no
 *      identity is chosen, no lifecycle heartbeat is issued, but every
 *      metric in the batch is still returned.
 *
 * TIMESTAMP: for a RESOLVED batch, the lifecycle timestamp is
 * the earliest observedAt among ONLY the signals whose classification
 * actually matches the winning identity -- not every lifecycle
 * candidate in the whole batch -- so a rejected/ambiguous alternative
 * can never pull the winning identity's timestamp earlier or later. An
 * AMBIGUOUS or NO_EVIDENCE batch produces no timestamp and must never
 * advance active duration.
 *
 * LOOT-OWNERSHIP-AT-A-SLAYER-TASK-PROGRESS-DRIVEN-SWITCH: the existing
 * LOOT-WHILE-ACTIVE OWNERSHIP below (unconditionally routing a METRIC_ONLY
 * SERVER_NPC_LOOT to the pre-batch `current` session) was built for,
 * and remains completely correct for, evidence UNRELATED to what
 * produced the loot (e.g. incidental Prayer XP, or an authoritative
 * BOSS_KILL for a wholly different boss) landing in the same tick as
 * that loot -- see SessionRuntimeCoordinatorTest's own
 * lootWhileActive_sameTickDifferentActivitySwitch_lootStaysOnFinalizedOldSession_notNewSession
 * and slayerXpWhileActive_sameTickDifferentBossSwitch_slayerXpStaysOnFinalizedOldSession_notNewSession,
 * both UNCHANGED and unaffected by this fix. But live evidence proved a
 * genuine SECOND case that unconditional rule gets wrong: ACTIVE
 * BOSSING/Grotesque Guardians, with a normal Gargoyle kill's own
 * SLAYER_TASK_PROGRESS/Gargoyles (now honestly proposing a real switch,
 * per ActivitySignalClassifier's own fix) landing in the SAME
 * tick as that SAME kill's own SERVER_NPC_LOOT/Gargoyle. That loot did
 * NOT come from the old Grotesque Guardians activity at all -- it came
 * from the very kill whose SLAYER_TASK_PROGRESS just won this batch --
 * so routing it to the stale pre-batch `current` (the old GG session)
 * would misattribute it exactly as badly as the original loot-ownership
 * bug this class already fixed once, just in the opposite direction.
 *
 * NARROW FIX: a METRIC_ONLY SERVER_NPC_LOOT signal is diverted to
 * `preBatchCurrentMetricUpdates` UNLESS this SAME batch also contains a
 * SLAYER_TASK_PROGRESS signal whose own classified identity IS this
 * batch's winning identity, AND that winning identity genuinely differs
 * from the pre-batch `current` session's identity (a real switch, not a
 * same-identity heartbeat or refinement). In that one case, the loot is
 * left in the ordinary `metrics` list instead, so it rides with the
 * batch's winning identity into the brand-new session -- exactly like
 * the existing SAME-TICK LOOT ATOMICITY promotion further below, which
 * already does this for loot classified IGNORE_FOR_SESSION (no
 * pre-existing ACTIVE session at all). This is deliberately scoped to
 * SLAYER_TASK_PROGRESS specifically, never to XP_CHANGE/BOSS_KILL-driven
 * switches: SLAYER_TASK_PROGRESS is the one signal kind that is
 * DIRECT, per-kill telemetry (the same real-world kill that produced
 * this same-tick loot), whereas the loot-stays-with-old-session tests
 * above hinge on evidence (Prayer XP, an unrelated boss's BOSS_KILL)
 * that carries no such same-kill correlation with the loot at all.
 */
final class SessionSignalBatchResolver
{
	private SessionSignalBatchResolver()
	{
	}

	static List<List<SessionSignal>> groupIntoBatches(List<SessionSignal> signals)
	{
		Map<Object, List<SessionSignal>> batches = new LinkedHashMap<>();
		for (SessionSignal s : signals)
		{
			Object key = s.getGameTick() != null ? s.getGameTick() : s.getObservedAt();
			batches.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
		}
		return new ArrayList<>(batches.values());
	}

	static BatchResolution resolve(List<SessionSignal> batch, Session current, ActivitySignalClassifier classifier, ClassifierContext context)
	{
		List<SignalClassification> classifications = new ArrayList<>(batch.size());
		for (SessionSignal signal : batch)
		{
			classifications.add(classifier.classify(signal, current, context));
		}

		// Boss self-consumption of its own Slayer task -- the
		// INTRA-BATCH, order-independent half; see
		// applyBossSelfConsumptionCorroboration()'s own javadoc and
		// ActivitySignalClassifier.classifySlayerTaskProgress()'s
		// suppression branch for the cross-tick half). Must run BEFORE
		// byIdentity is built below, since it can rewrite a
		// SLAYER_TASK_PROGRESS classification in place.
		applyBossSelfConsumptionCorroboration(batch, classifications, current, context);

		// Group lifecycle-carrying classifications by their resulting
		// identity, remembering every originating signal's observedAt
		// for the per-identity earliest-timestamp rule. Moved ahead of
		// the metrics-classification loop below so that loop can consult
		// this batch's winning identity when deciding SERVER_NPC_LOOT
		// ownership.
		Map<ActivityIdentity, List<SignalClassification>> byIdentity = new LinkedHashMap<>();
		Map<ActivityIdentity, List<Instant>> timestampsByIdentity = new LinkedHashMap<>();
		for (int i = 0; i < batch.size(); i++)
		{
			SignalClassification c = classifications.get(i);
			if (c.getDecisionKind() == SignalDecisionKind.START_OR_HEARTBEAT || c.getDecisionKind() == SignalDecisionKind.REFINE)
			{
				byIdentity.computeIfAbsent(c.getIdentity(), k -> new ArrayList<>()).add(c);
				timestampsByIdentity.computeIfAbsent(c.getIdentity(), k -> new ArrayList<>()).add(batch.get(i).getObservedAt());
			}
		}

		ActivityIdentity winner = byIdentity.isEmpty() ? null : resolveOutcome(byIdentity.keySet(), current);

		// LOOT-OWNERSHIP-AT-A-SLAYER-TASK-PROGRESS-DRIVEN-SWITCH (see class
		// javadoc for the full rationale). True only when this batch
		// RESOLVED to a winning
		// identity that (a) genuinely differs from the pre-batch
		// `current` session's own identity (a real switch, not a
		// heartbeat/refinement of the same session) and (b) is the
		// identity a SLAYER_TASK_PROGRESS signal in THIS SAME batch
		// itself proposed -- i.e. the switch is directly, per-kill
		// attributable to a SLAYER_TASK_PROGRESS event, not to unrelated
		// same-tick evidence (Prayer XP, a different boss's BOSS_KILL).
		// Originally scoped to SLAYER_TASK_PROGRESS only; further
		// evidence proved the identical misattribution also happens
		// when an authoritative
		// BOSS_KILL (not SLAYER_TASK_PROGRESS) is the same-tick evidence
		// that drives the real switch -- e.g. a BOSS_KILL for a NEW boss
		// arriving the same tick as that same kill's own loot, while
		// `current` is still some OTHER, unrelated boss/activity. Both
		// signal kinds are direct, per-kill telemetry naming the specific
		// thing that was just killed, so both qualify as "same real-world
		// kill" correlation for loot ownership -- see class javadoc.
		boolean sameKillEvidenceDrivesRealSwitch = false;
		boolean isRealSwitchThisBatch = winner != null && (current == null || !winner.equals(current.getActivityIdentity()));
		Instant switchTimestamp = isRealSwitchThisBatch ? Collections.min(timestampsByIdentity.get(winner)) : null;
		if (isRealSwitchThisBatch)
		{
			for (int i = 0; i < batch.size(); i++)
			{
				SessionSignal signal = batch.get(i);
				SignalClassification c = classifications.get(i);
				if ((signal.getKind() == SignalKind.SLAYER_TASK_PROGRESS || signal.getKind() == SignalKind.BOSS_KILL)
					&& (c.getDecisionKind() == SignalDecisionKind.START_OR_HEARTBEAT || c.getDecisionKind() == SignalDecisionKind.REFINE)
					&& winner.equals(c.getIdentity()))
				{
					sameKillEvidenceDrivesRealSwitch = true;
					break;
				}
			}
		}

		// LOOT-WHILE-ACTIVE OWNERSHIP: a SERVER_NPC_LOOT signal classified
		// METRIC_ONLY means ActivitySignalClassifier.classifyServerNpcLoot()
		// found the pre-batch `current` session genuinely ACTIVE at
		// classification time -- that loot belongs to THAT session, full
		// stop, UNLESS this exact batch's own SLAYER_TASK_PROGRESS
		// evidence is what drives a real switch away from it this same
		// tick (the carve-out immediately above -- see this class's own
		// javadoc). Before the original fix, an
		// unconditionally-diverted-nowhere loot metric was dumped into
		// the same undifferentiated `metrics` list as every other
		// same-tick metric, which SessionRuntimeCoordinator then applies
		// entirely to whichever identity THIS BATCH resolves to. When
		// this same tick's OTHER, UNRELATED evidence also proposes a
		// different, competing identity (e.g. incidental Prayer/Magic XP,
		// or any other same-tick activity switch) and that competing
		// identity wins the batch -- or, while `current` is SUSPENDED,
		// is later confirmed via the weak-evidence-candidate mechanism in
		// SessionLifecycleEngine -- `current` is finalized/replaced and
		// this loot rode along onto the brand-new, unrelated session
		// instead of the session it actually came from. Kept out of
		// `metrics` (which rides with whatever identity this batch
		// resolves to) and returned separately as preBatchCurrentMetrics
		// so the caller can apply it directly to the pre-batch `current`
		// session, unconditionally, regardless of what this batch's
		// lifecycle outcome turns out to be. The SAME-TICK LOOT ATOMICITY
		// promotion below (for loot classified IGNORE_FOR_SESSION because
		// `current` was NOT already ACTIVE) is untouched by this fix --
		// that loot has no established pre-batch session to belong to in
		// the first place, so riding with the batch's winning identity
		// remains correct for it.
		//
		// SLAYER-XP-WHILE-ACTIVE OWNERSHIP (same class as the loot fix
		// above): a SLAYER-skill
		// XP_CHANGE classified METRIC_ONLY by
		// ActivitySignalClassifier.classifySlayerXp() means that method
		// found the pre-batch `current` session genuinely combat-branch
		// (COMBAT/SLAYER/BOSSING) and non-FINALIZED -- see its own
		// javadoc. Exactly like loot, when `current` is additionally
		// ACTIVE (not merely SUSPENDED) at classification time, this XP
		// is real telemetry that demonstrably occurred as part of the
		// SAME encounter `current` is already tracking -- e.g. delayed
		// Slayer XP from a task kill arriving in the same tick as an
		// authoritative same-tick switch to a DIFFERENT combat-branch
		// identity (a different boss's BOSS_KILL, a higher-rank Slayer
		// task). Before this fix that XP rode in the same undifferentiated
		// `metrics` list as every other same-tick metric, which
		// SessionRuntimeCoordinator applies entirely to whichever identity
		// this batch resolves to -- SessionLifecycleEngine.onQualifyingActivity()'s
		// ACTIVE "different activity" branch finalizes `current`
		// and applies the WHOLE batch's metrics to the brand-new session,
		// so this Slayer XP would land on the new, unrelated session
		// instead of the finalized session it actually came from.
		// Deliberately NOT extended by the SLAYER_TASK_PROGRESS carve-out
		// above -- Slayer-skill XP is
		// explicitly documented (ActivitySignalClassifier.classifySlayerXp())
		// as frequently DELAYED telemetry, so, unlike SERVER_NPC_LOOT, it
		// carries no reliable same-kill correlation with whatever
		// SLAYER_TASK_PROGRESS also happens to land in this same batch;
		// it keeps its unconditional pre-batch-current routing exactly as
		// it already was.
		//
		// Scoped EXACTLY to `current.getState() == ACTIVE`, mirroring
		// classifyServerNpcLoot()'s own ACTIVE-only rule, and deliberately
		// NOT extended to the SUSPENDED case: classifySlayerXp() also
		// returns METRIC_ONLY while `current` is SUSPENDED, but that case
		// is already handled correctly by SessionLifecycleEngine's own
		// weak-evidence-candidate buffering (pendingCandidateMetrics) --
		// the SAME mechanism already proven correct for Magic XP's own
		// SUSPENDED-mixed-batch scenario (see SessionRuntimeCoordinatorTest's
		// liveBug_mixedMagicAndWoodcuttingWhileSuspendedZulrah_... and
		// liveBug_confirmedWoodcuttingCandidateAfterMixedBatch_... /
		// liveBug_mixedBatchThenAuthoritativeSameZulrahEvidence_... tests).
		// Diverting a SUSPENDED-current Slayer XP metric into
		// preBatchCurrentMetrics as well would bypass that candidate
		// buffer and commit it to the still-SUSPENDED session immediately
		// -- before it is known whether the candidate is confirmed (in
		// which case, exactly like Magic, it should move to the NEW
		// session) or abandoned (in which case the existing contextual-
		// metrics path already commits it correctly to the old session).
		// So only the ACTIVE case is diverted here; the SUSPENDED case is
		// left exactly as it already was, riding in `metrics` into the
		// existing, already-correct candidate machinery.
		List<MetricUpdate> metrics = new ArrayList<>();
		List<MetricUpdate> preBatchCurrentMetrics = new ArrayList<>();
		boolean currentIsActive = current != null && current.getState() == SessionState.ACTIVE;
		for (int i = 0; i < batch.size(); i++)
		{
			SignalClassification c = classifications.get(i);
			// CANDIDATE-METRIC BUFFERING: a classification can now carry
			// MORE THAN ONE MetricUpdate (a resolved boss-
			// task candidate's own buffered metric(s) combined with the
			// resolving signal's own metric -- see
			// ActivitySignalClassifier.classifyBossKill()/classifyServerNpcLoot()).
			// Every metric in this list came from the SAME signal/
			// classification, so the SAME ownership decision below
			// applies to all of them together.
			List<MetricUpdate> classificationMetrics = c.getMetricUpdates();
			if (classificationMetrics.isEmpty())
			{
				continue;
			}
			SessionSignal signal = batch.get(i);
			// POST-FINALIZATION METRIC: a metric whose OWN observedAt is
			// strictly AFTER the instant this
			// batch's real switch will finalize `current` at can never
			// honestly belong to that about-to-be-finalized session --
			// its own persisted finalizedAt would then be BEFORE some of
			// its own contents' timestamps, which must never be possible
			// (a finalized session's contents must never include
			// telemetry observed after its own finalization). Such a
			// metric rides with the winning/new identity instead via the
			// ordinary `metrics` list below, never preBatchCurrentMetrics.
			boolean observedAfterThisBatchsSwitch = isRealSwitchThisBatch && signal.getObservedAt().isAfter(switchTimestamp);
			boolean isPreBatchCurrentOwnedLoot = signal.getKind() == SignalKind.SERVER_NPC_LOOT
				&& c.getDecisionKind() == SignalDecisionKind.METRIC_ONLY
				&& !sameKillEvidenceDrivesRealSwitch
				&& !observedAfterThisBatchsSwitch;
			boolean isPreBatchCurrentOwnedSlayerXp = currentIsActive
				&& signal.getKind() == SignalKind.XP_CHANGE
				&& c.getDecisionKind() == SignalDecisionKind.METRIC_ONLY
				&& ActivitySignalClassifier.isSlayerXpSkill(signal.getSkill())
				&& !observedAfterThisBatchsSwitch;
			if (isPreBatchCurrentOwnedLoot || isPreBatchCurrentOwnedSlayerXp)
			{
				preBatchCurrentMetrics.addAll(classificationMetrics);
			}
			else
			{
				metrics.addAll(classificationMetrics);
			}
		}

		if (byIdentity.isEmpty())
		{
			return new BatchResolution(BatchLifecycleOutcome.NO_EVIDENCE, null, null, metrics, preBatchCurrentMetrics, null);
		}

		if (winner == null)
		{
			return new BatchResolution(BatchLifecycleOutcome.AMBIGUOUS, null, null, metrics, preBatchCurrentMetrics, null);
		}

		// SAME-TICK LOOT ATOMICITY: ActivitySignalClassifier.classifyServerNpcLoot()
		// classifies each SERVER_NPC_LOOT signal against the PRE-batch
		// `current` alone (unchanged -- ActivitySignalClassifierTest's own
		// serverNpcLootWithNoActiveSessionIsIgnored/...IsMetricOnly... tests
		// establish that a signal evaluated IN ISOLATION with no current
		// ACTIVE session correctly ignores loot, and that per-signal
		// contract is untouched here). But a batch is not "in isolation":
		// when THIS SAME tick's OTHER evidence (e.g. an authoritative
		// BOSS_KILL) is what creates or continues the very session this
		// batch just RESOLVED to, loot observed in that same tick
		// genuinely belongs to it -- exactly the same same-tick atomicity
		// already relied on for XP/Slayer/Boss evidence sharing one
		// batch. Promoted ONLY for a batch that actually RESOLVED to a
		// winning identity (an AMBIGUOUS or NO_EVIDENCE batch never gets
		// this treatment -- SERVER_NPC_LOOT still never independently
		// creates or resurrects a session by itself; see
		// SessionRuntimeCoordinatorTest's own
		// serverNpcLootAlone_neverIndependentlyCreatesASession, and this
		// class's own ambiguousBatchRetainsAllMetrics regression, both
		// unaffected since neither reaches this point), and only for a
		// signal the classifier actually dropped for exactly this reason
		// (IGNORE_FOR_SESSION) -- a signal already classified METRIC_ONLY
		// (a pre-existing ACTIVE session) already has its metric in
		// `metrics` or `preBatchCurrentMetrics` from the loop above and is
		// deliberately skipped here to avoid double-counting the same
		// drop.
		for (int i = 0; i < batch.size(); i++)
		{
			SessionSignal signal = batch.get(i);
			SignalClassification c = classifications.get(i);
			if (signal.getKind() == SignalKind.SERVER_NPC_LOOT
				&& c.getDecisionKind() == SignalDecisionKind.IGNORE_FOR_SESSION
				&& signal.getLootDrops() != null && !signal.getLootDrops().isEmpty())
			{
				metrics.add(MetricUpdate.loot(signal.getBossOrActivitySource(), signal.getObservedAt(), signal.getLootDrops()));
			}
		}

		SignalClassification winningClassification = byIdentity.get(winner).get(0);
		Instant winningTimestamp = isRealSwitchThisBatch ? switchTimestamp : Collections.min(timestampsByIdentity.get(winner));
		// EVIDENCE STRENGTH: the batch's own winning identity can be
		// independently proposed by
		// MORE THAN ONE signal in the same tick (e.g. an ordinary XP tick
		// alongside an authoritative BOSS_KILL for the same boss) -- take
		// the STRONGEST of those, never just winningClassification's own
		// (winningClassification is picked as byIdentity.get(winner).get(0)
		// purely for its timestamp/identity, with no ordering guarantee on
		// strength). This is the one place a batch's evidence strength is
		// derived -- from the actual classifications that won, never from
		// the winning ActivityIdentity/ActivityType itself.
		EvidenceStrength winningStrength = strongestStrength(byIdentity.get(winner));
		return new BatchResolution(BatchLifecycleOutcome.RESOLVED, winningClassification, winningTimestamp, metrics, preBatchCurrentMetrics, winningStrength);
	}

	/**
	 * EVIDENCE STRENGTH: the strongest (most-authoritative)
	 * EvidenceStrength among a set of classifications
	 * that all agreed on the same winning identity. AUTHORITATIVE >
	 * SPECIFIC > ORDINARY > CONTEXTUAL -- see EvidenceStrength's own
	 * javadoc. Every classification passed in is START_OR_HEARTBEAT/REFINE
	 * (the only kinds present in byIdentity), so every one of them carries
	 * a non-null EvidenceStrength; this method never sees an empty list,
	 * since it is only ever called with byIdentity.get(winner).
	 */
	private static EvidenceStrength strongestStrength(List<SignalClassification> winningClassifications)
	{
		EvidenceStrength strongest = EvidenceStrength.CONTEXTUAL;
		for (SignalClassification c : winningClassifications)
		{
			if (c.getEvidenceStrength().ordinal() < strongest.ordinal())
			{
				strongest = c.getEvidenceStrength();
			}
		}
		return strongest;
	}

	/**
	 * Boss self-consumption of its own Slayer task -- the INTRA-BATCH,
	 * order-independent half; see ClassifierContext's own javadoc for
	 * the cross-tick half. ActivitySignalClassifier
	 * suppresses (by default) a SLAYER_TASK_PROGRESS naming the pre-batch
	 * ACTIVE boss's OWN known Slayer task (see BossTaskAffinity) -- it
	 * proposes a heartbeat of the boss identity, not a switch, because
	 * the boss's own kill can itself consume that task's units. That
	 * default is reversed here, ORDER-INDEPENDENTLY (this does not care
	 * whether the progress or the loot was classified first within the
	 * batch), whenever this SAME batch also carries a SERVER_NPC_LOOT for
	 * a genuinely different NPC -- not the boss itself, and not one of
	 * its own known aliases (see BossTaskAffinity.isBossOwnNpcName()) --
	 * since that is real, same-kill proof the progress did NOT come from
	 * the boss. The rewritten classification proposes the real Slayer
	 * identity exactly as classifySlayerTaskProgress() would have without
	 * the suppression, carrying the SAME MetricUpdate it already computed
	 * (the progress delta/currentRemaining is never recomputed here). The
	 * pending ClassifierContext candidate is cleared, since this batch's
	 * own evidence already resolves the ambiguity -- there is nothing
	 * left to await.
	 *
	 * SUSPENDED boss-own-task reactivation: the pre-batch boss session
	 * may now be SUSPENDED (not just ACTIVE) here too, exactly mirroring
	 * ActivitySignalClassifier.classifySlayerTaskProgress()'s own ACTIVE-or-
	 * SUSPENDED widening -- a SUSPENDED boss's own-task progress is
	 * suppressed by that method the same way an ACTIVE one is, so this
	 * same-tick, order-independent confirmation half must recognize and
	 * reverse that suppression for SUSPENDED too, or a genuinely different,
	 * same-tick confirming NPC loot would be silently swallowed instead of
	 * correctly triggering the real switch. The rewritten classification's
	 * own EvidenceStrength (SPECIFIC) already causes SessionLifecycleEngine
	 * to switch immediately regardless of ACTIVE/SUSPENDED (see that
	 * class's own "AUTHORITATIVE/SPECIFIC evidence ... never gated, ACTIVE
	 * or SUSPENDED" rule) -- no new confirmation-count/timer logic needed
	 * here.
	 */
	private static void applyBossSelfConsumptionCorroboration(List<SessionSignal> batch, List<SignalClassification> classifications, Session preBatchCurrent, ClassifierContext context)
	{
		if (preBatchCurrent == null
			|| (preBatchCurrent.getState() != SessionState.ACTIVE && preBatchCurrent.getState() != SessionState.SUSPENDED)
			|| preBatchCurrent.getActivityIdentity().getActivityType() != ActivityType.BOSSING)
		{
			return;
		}

		String bossKey = preBatchCurrent.getActivityIdentity().getActivityKey();

		boolean corroboratingRealNpcLootInBatch = false;
		for (SessionSignal signal : batch)
		{
			if (signal.getKind() == SignalKind.SERVER_NPC_LOOT
				&& signal.getLootDrops() != null && !signal.getLootDrops().isEmpty()
				&& !BossTaskAffinity.isBossOwnNpcName(bossKey, signal.getBossOrActivitySource()))
			{
				corroboratingRealNpcLootInBatch = true;
				break;
			}
		}

		if (!corroboratingRealNpcLootInBatch)
		{
			return;
		}

		// CANDIDATE-METRIC BUFFERING: the candidate's own buffered
		// metric(s) live in `context`, not in `suppressed`'s own
		// classification (classifySlayerTaskProgress() no longer attaches
		// the progress metric to its own suppressed classification -- see
		// ClassifierContext's own javadoc). Snapshotted ONCE, before the
		// loop below, since context.clearBossTaskCandidate() at the end
		// of this method would otherwise empty it out from under a
		// second matching signal (there is normally only ever one, but
		// this stays correct even if not). Attached to only the FIRST
		// matching SLAYER_TASK_PROGRESS in this batch -- defensively
		// avoids ever duplicating the same buffered metric across more
		// than one rewritten classification.
		List<MetricUpdate> bufferedProgress = context.getPendingBossTaskCandidateMetrics();
		for (int i = 0; i < batch.size(); i++)
		{
			SessionSignal signal = batch.get(i);
			if (signal.getKind() != SignalKind.SLAYER_TASK_PROGRESS
				|| !BossTaskAffinity.isBossOwnTask(bossKey, signal.getSlayerTaskName()))
			{
				continue;
			}
			ActivityIdentity candidate = ActivitySignalClassifier.slayerIdentity(signal.getSlayerTaskName(), signal.getSlayerTaskLocation());
			// EVIDENCE STRENGTH: this corroboration only ever fires when
			// a same-tick, genuinely
			// different-NPC SERVER_NPC_LOOT is in the batch (see the
			// corroboratingRealNpcLootInBatch check above) -- that is
			// direct, per-kill proof of this exact Slayer identity, so
			// SPECIFIC (not merely ORDINARY) is the honest tag, matching
			// classifyServerNpcLoot()'s own real-switch-confirmed branch.
			classifications.set(i, SignalClassification.startOrHeartbeat(candidate, bufferedProgress, EvidenceStrength.SPECIFIC));
			bufferedProgress = Collections.emptyList();
		}

		context.clearBossTaskCandidate();
	}

	/**
	 * Returns the single identity this batch honestly resolves to, or
	 * null if the batch is genuinely ambiguous. See class javadoc for
	 * the full step-by-step rule -- no lexicographic comparison
	 * anywhere in this method.
	 */
	private static ActivityIdentity resolveOutcome(java.util.Set<ActivityIdentity> distinctIdentities, Session current)
	{
		if (distinctIdentities.size() == 1)
		{
			return distinctIdentities.iterator().next();
		}

		List<ActivityIdentity> specific = new ArrayList<>();
		ActivityIdentity genericCombat = ActivitySignalClassifier.genericCombatIdentity();
		for (ActivityIdentity id : distinctIdentities)
		{
			if (!id.equals(genericCombat))
			{
				specific.add(id);
			}
		}

		if (specific.size() == 1)
		{
			return specific.get(0);
		}
		// specific.size() >= 2 here (it cannot be 0: that would mean
		// every distinct identity equalled genericCombat, impossible
		// once distinctIdentities.size() > 1, since genericCombat is a
		// single identity).

		List<ActivityIdentity> combatBranch = new ArrayList<>();
		List<ActivityIdentity> skilling = new ArrayList<>();
		for (ActivityIdentity id : specific)
		{
			if (ActivityPrecedence.isCombatBranch(id.getActivityType()))
			{
				combatBranch.add(id);
			}
			else
			{
				skilling.add(id);
			}
		}

		if (!combatBranch.isEmpty() && skilling.isEmpty())
		{
			int maxRank = -1;
			for (ActivityIdentity id : combatBranch)
			{
				maxRank = Math.max(maxRank, ActivityPrecedence.combatBranchRank(id.getActivityType()));
			}
			List<ActivityIdentity> top = new ArrayList<>();
			for (ActivityIdentity id : combatBranch)
			{
				if (ActivityPrecedence.combatBranchRank(id.getActivityType()) == maxRank)
				{
					top.add(id);
				}
			}
			if (top.size() == 1)
			{
				// A strictly higher-ranked identity wins outright --
				// this is what lets an authoritative BOSS_KILL switch a
				// Slayer session even when the OLD identity matches
				// `current`: stickiness is never consulted here
				// (stickiness must never swallow a real switch).
				return top.get(0);
			}
			return stickyMatchOrNull(top, current);
		}

		if (!skilling.isEmpty() && combatBranch.isEmpty())
		{
			// Two or more distinct named Skilling identities with no
			// rank relationship between them -- only an exact
			// current-session match can honestly choose.
			return stickyMatchOrNull(skilling, current);
		}

		// Mixed Skilling + combat-branch evidence in one batch, or any
		// other combination not covered above: there is no proven
		// comparison rule between these branches, so only an exact
		// current-session match can resolve it.
		return stickyMatchOrNull(specific, current);
	}

	private static ActivityIdentity stickyMatchOrNull(List<ActivityIdentity> candidates, Session current)
	{
		if (current == null)
		{
			return null;
		}
		ActivityIdentity currentIdentity = current.getActivityIdentity();
		for (ActivityIdentity id : candidates)
		{
			if (id.equals(currentIdentity))
			{
				return id;
			}
		}
		return null;
	}

	/**
	 * The result of resolving one batch. `outcome` says which of the
	 * three states this is; `lifecycleClassification`/
	 * `lifecycleTimestamp` are non-null only when `outcome` is
	 * RESOLVED. `metricUpdates` is always populated regardless of
	 * outcome -- ambiguity in the lifecycle decision never discards a
	 * valid metric.
	 */
	static final class BatchResolution
	{
		private final BatchLifecycleOutcome outcome;
		private final SignalClassification lifecycleClassification;
		private final Instant lifecycleTimestamp;
		private final List<MetricUpdate> metricUpdates;
		private final List<MetricUpdate> preBatchCurrentMetricUpdates;
		private final EvidenceStrength lifecycleEvidenceStrength;

		private BatchResolution(BatchLifecycleOutcome outcome, SignalClassification lifecycleClassification, Instant lifecycleTimestamp, List<MetricUpdate> metricUpdates, List<MetricUpdate> preBatchCurrentMetricUpdates, EvidenceStrength lifecycleEvidenceStrength)
		{
			this.outcome = outcome;
			this.lifecycleClassification = lifecycleClassification;
			this.lifecycleTimestamp = lifecycleTimestamp;
			this.metricUpdates = metricUpdates;
			this.preBatchCurrentMetricUpdates = preBatchCurrentMetricUpdates;
			this.lifecycleEvidenceStrength = lifecycleEvidenceStrength;
		}

		BatchLifecycleOutcome getOutcome()
		{
			return outcome;
		}

		SignalClassification getLifecycleClassification()
		{
			return lifecycleClassification;
		}

		Instant getLifecycleTimestamp()
		{
			return lifecycleTimestamp;
		}

		List<MetricUpdate> getMetricUpdates()
		{
			return metricUpdates;
		}

		/**
		 * LOOT-WHILE-ACTIVE OWNERSHIP (extended by SLAYER-XP-WHILE-ACTIVE
		 * OWNERSHIP, and narrowed by the SLAYER_TASK_PROGRESS-driven-
		 * real-switch carve-out -- see class javadoc): metrics that
		 * belong to the pre-batch
		 * `current` session specifically, regardless of what this
		 * batch's own lifecycle outcome is -- currently either a
		 * SERVER_NPC_LOOT metric classified METRIC_ONLY against `current`
		 * (any state) UNLESS this same batch's own SLAYER_TASK_PROGRESS
		 * evidence is what drives a real switch away from it, or a
		 * SLAYER-skill XP_CHANGE classified METRIC_ONLY against an
		 * ACTIVE `current` specifically. Never null (empty when there is
		 * nothing of this kind in the batch). See resolve()'s own
		 * in-method comments for the full rationale on each.
		 */
		List<MetricUpdate> getPreBatchCurrentMetricUpdates()
		{
			return preBatchCurrentMetricUpdates;
		}

		/**
		 * EVIDENCE STRENGTH: the strongest EvidenceStrength among this
		 * batch's winning-identity
		 * classifications -- non-null exactly when outcome is RESOLVED
		 * (null for AMBIGUOUS/NO_EVIDENCE, which propose no identity at
		 * all). SessionRuntimeCoordinator threads this into
		 * SessionLifecycleEngine's evidence-aware onQualifyingActivity()
		 * overload; see that engine's own javadoc for how it is consumed.
		 */
		EvidenceStrength getLifecycleEvidenceStrength()
		{
			return lifecycleEvidenceStrength;
		}
	}
}
