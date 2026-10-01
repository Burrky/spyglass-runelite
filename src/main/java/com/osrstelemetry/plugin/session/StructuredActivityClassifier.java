package com.osrstelemetry.plugin.session;

import com.osrstelemetry.plugin.activity.LocationPolicy;
import com.osrstelemetry.plugin.activity.RecognitionState;
import com.osrstelemetry.plugin.activity.StructuredActivityDefinition;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredActivityTracker;
import com.osrstelemetry.plugin.activity.StructuredRewardSource;
import java.util.Collections;
import java.util.List;

/**
 * Generic structured-activity layer in front of ActivitySignalClassifier.
 * It only turns signals into ordinary SignalClassifications -- the existing
 * SessionLifecycleEngine still owns start / resume / suspend / interrupted
 * recovery / finalize / active duration. No activity is named here: every
 * activity-specific fact comes from its StructuredActivityDefinition.
 *
 * Returns null whenever a signal is not structured-activity business, so
 * the ordinary classifier decides exactly as before.
 *
 * Rules (see StructuredActivityTracker for ARMED/ACTIVE/LOCKED):
 *  - Location signals only arm/disarm. Never a session, never a confirmation.
 *  - In an armed location, compatible evidence (expected-skill XP outside a
 *    NON_EXCLUSIVE region, a subordinate boss kill, a completion, a declared
 *    reward) confirms the activity and is SPECIFIC START_OR_HEARTBEAT for the
 *    activity's identity -- the engine switches to / heartbeats it at once.
 *  - While the session IS the activity and the player is in its location,
 *    unexpected XP is incidental (metric-only) under EXCLUSIVE, or under
 *    STRONG_CONTEXT once LOCKED; a Slayer task's progress never steals it;
 *    subordinate boss context never switches it.
 *  - Outside the location nothing is reinforced: ordinary rules apply, so
 *    other activities take over through the normal evidence gate. Only the
 *    activity's own completions/rewards still land (metric-only) on its
 *    own session; they never land on any other session.
 */
final class StructuredActivityClassifier
{
	private final StructuredActivityRegistry registry;

	StructuredActivityClassifier(StructuredActivityRegistry registry)
	{
		this.registry = registry;
	}

	SignalClassification classify(SessionSignal signal, Session current, ClassifierContext context)
	{
		StructuredActivityTracker tracker = context.getStructuredActivity();
		if (signal.getKind() == SignalKind.STRUCTURED_ACTIVITY_LOCATION)
		{
			StructuredActivityDefinition def = registry.byKey(signal.getStructuredActivityKey());
			LocationPolicy policy = def == null || signal.getRegionId() == null ? null : def.policyFor(signal.getRegionId());
			tracker.onLocation(policy == null ? null : def, policy);
			return SignalClassification.ignore();
		}

		StructuredActivityDefinition armed = tracker.getDefinition();
		StructuredActivityDefinition owning = owningDefinition(current);
		if (armed == null && owning == null)
		{
			return null;
		}
		boolean inOwnActivity = armed != null && owning != null && armed.getKey().equals(owning.getKey());

		switch (signal.getKind())
		{
			case XP_CHANGE:
				return classifyXp(signal, current, tracker, armed, inOwnActivity);
			case STRUCTURED_LIVE_XP:
				return classifyLiveXp(signal, tracker, armed);
			case RAW_COMBAT_XP_OBSERVED:
				// The XP_CHANGE that follows carries the evidence; never let the
				// early per-NPC combat heuristic open a competing session.
				return armed != null && tracker.getPolicy() != LocationPolicy.NON_EXCLUSIVE && armed.isExpectedSkill(signal.getSkill())
					? SignalClassification.ignore()
					: null;
			case BOSS_KILL:
				return classifyBossKill(signal, tracker, armed, owning);
			case BOSS_ACTIVITY_CONTEXT:
				return armed != null && armed.isSubordinate(signal.getBossOrActivitySource()) ? SignalClassification.ignore() : null;
			case ACTIVITY_COMPLETION:
				return classifyNamedCompletion(signal, tracker, armed, owning);
			case STRUCTURED_ACTIVITY_COMPLETION:
				return classifyKeyedCompletion(signal, tracker, armed, owning);
			case STRUCTURED_REWARD:
				return classifyReward(signal, tracker, armed, owning);
			case STRUCTURED_ACTIVITY_SCORE:
			case STRUCTURED_ACTIVITY_CURRENCY:
			case STRUCTURED_ACTIVITY_PRODUCTION:
				return classifyStats(signal, owning);
			case SLAYER_TASK_PROGRESS:
				if (inOwnActivity && tracker.getState() != RecognitionState.ARMED)
				{
					// Keep the task state current, but a task never steals an
					// established structured activity.
					context.onSlayerTaskProgress(signal.getSlayerTaskName(), signal.getSlayerTaskLocation(), signal.getSlayerCurrentRemaining());
					return SignalClassification.ignore();
				}
				return null;
			case SERVER_NPC_LOOT:
				if (inOwnActivity && tracker.getState() != RecognitionState.ARMED
					&& signal.getLootDrops() != null && !signal.getLootDrops().isEmpty())
				{
					return SignalClassification.metricOnly(MetricUpdate.loot(signal.getBossOrActivitySource(), signal.getObservedAt(), signal.getLootDrops()));
				}
				return null;
			default:
				return null;
		}
	}

	private SignalClassification classifyXp(SessionSignal signal, Session current, StructuredActivityTracker tracker,
		StructuredActivityDefinition armed, boolean inOwnActivity)
	{
		if (armed == null || ActivitySignalClassifier.spansSessionStart(signal, current))
		{
			return null;
		}
		MetricUpdate metric = ActivitySignalClassifier.xpMetric(signal);
		LocationPolicy policy = tracker.getPolicy();
		if (policy != LocationPolicy.NON_EXCLUSIVE && armed.isExpectedSkill(signal.getSkill()))
		{
			return evidence(armed, tracker, signal, Collections.singletonList(metric));
		}
		if (inOwnActivity && absorbsIncidentalXp(policy, tracker.getState()))
		{
			return SignalClassification.metricOnly(metric);
		}
		return null;
	}

	/**
	 * LIVE XP (classification only, no metric): an expected-skill gain in
	 * the armed location makes the activity current right away. It
	 * activates recognition (ARMED -&gt; ACTIVE) but never advances it
	 * further: locking still needs the same durable-window / completion
	 * evidence as before, so incidental-XP absorption is unchanged.
	 */
	private SignalClassification classifyLiveXp(SessionSignal signal, StructuredActivityTracker tracker, StructuredActivityDefinition armed)
	{
		if (armed == null || tracker.getPolicy() == LocationPolicy.NON_EXCLUSIVE || !armed.isExpectedSkill(signal.getSkill()))
		{
			return SignalClassification.ignore();
		}
		if (tracker.getState() == RecognitionState.ARMED)
		{
			tracker.confirm(tickKey(signal));
		}
		return SignalClassification.startOrHeartbeat(ActivitySignalClassifier.structuredActivityIdentity(armed),
			Collections.<MetricUpdate>emptyList(), EvidenceStrength.SPECIFIC);
	}

	private SignalClassification classifyBossKill(SessionSignal signal, StructuredActivityTracker tracker,
		StructuredActivityDefinition armed, StructuredActivityDefinition owning)
	{
		String boss = signal.getBossOrActivitySource();
		if (armed != null && armed.isCompletionBoss(boss))
		{
			return completion(armed, tracker, signal, signal.getReliableCount());
		}
		if (armed != null && armed.isSubordinate(boss))
		{
			return evidence(armed, tracker, signal, Collections.<MetricUpdate>emptyList());
		}
		if (armed == null && owning != null && owning.isCompletionBoss(boss))
		{
			return completionMetricOnly(owning, signal.getReliableCount());
		}
		return null;
	}

	private SignalClassification classifyNamedCompletion(SessionSignal signal, StructuredActivityTracker tracker,
		StructuredActivityDefinition armed, StructuredActivityDefinition owning)
	{
		String name = signal.getBossOrActivitySource();
		if (armed != null && armed.isCompletionCount(name))
		{
			return completion(armed, tracker, signal, signal.getReliableCount());
		}
		if (owning != null && owning.isCompletionCount(name))
		{
			return completionMetricOnly(owning, signal.getReliableCount());
		}
		return null;
	}

	private SignalClassification classifyKeyedCompletion(SessionSignal signal, StructuredActivityTracker tracker,
		StructuredActivityDefinition armed, StructuredActivityDefinition owning)
	{
		String key = signal.getStructuredActivityKey();
		if (armed != null && armed.getKey().equals(key))
		{
			return completion(armed, tracker, signal, signal.getReliableCount());
		}
		if (owning != null && owning.getKey().equals(key))
		{
			return completionMetricOnly(owning, signal.getReliableCount());
		}
		// Not this location's activity and not the session's: never fabricate.
		return SignalClassification.ignore();
	}

	private SignalClassification classifyReward(SessionSignal signal, StructuredActivityTracker tracker,
		StructuredActivityDefinition armed, StructuredActivityDefinition owning)
	{
		// Ownership follows the reward SOURCE: only a declared source of the
		// matching activity can be owned -- an unknown or mismatched source is
		// never credited just because some structured session is active.
		StructuredRewardSource source = registry.rewardSource(signal.getBossOrActivitySource());
		if (source == null || !source.getActivityKey().equals(signal.getStructuredActivityKey())
			|| signal.getLootDrops() == null || signal.getLootDrops().isEmpty())
		{
			return SignalClassification.ignore();
		}
		String key = source.getActivityKey();
		MetricUpdate loot = MetricUpdate.loot(source.getDisplayName(), signal.getObservedAt(), signal.getLootDrops());
		boolean armedMatches = armed != null && armed.getKey().equals(key);
		if (owning != null && owning.getKey().equals(key))
		{
			return armedMatches
				? evidence(armed, tracker, signal, Collections.singletonList(loot))
				: SignalClassification.metricOnly(loot);
		}
		if (armedMatches && tracker.getPolicy() != LocationPolicy.NON_EXCLUSIVE)
		{
			return evidence(armed, tracker, signal, Collections.singletonList(loot));
		}
		// A reward for an activity that is not the current session never
		// lands anywhere else (in particular never in a parked Slayer session).
		return SignalClassification.ignore();
	}

	/**
	 * Session-summary stats land ONLY on the activity's own (non-finalized)
	 * session -- never evidence, never a new session, never another
	 * session's metric (SessionRuntimeCoordinator re-checks the target).
	 */
	private static SignalClassification classifyStats(SessionSignal signal, StructuredActivityDefinition owning)
	{
		String key = signal.getStructuredActivityKey();
		if (owning == null || key == null || !owning.getKey().equals(key))
		{
			return SignalClassification.ignore();
		}
		if (signal.getKind() == SignalKind.STRUCTURED_ACTIVITY_PRODUCTION)
		{
			List<SessionSignal.LootDrop> produced = signal.getProducedItems();
			return produced == null || produced.isEmpty()
				? SignalClassification.ignore()
				: SignalClassification.metricOnly(MetricUpdate.activityProduction(key, produced));
		}
		Long amount = signal.getActivityAmount();
		if (signal.getKind() == SignalKind.STRUCTURED_ACTIVITY_CURRENCY)
		{
			return amount == null || amount <= 0
				? SignalClassification.ignore()
				: SignalClassification.metricOnly(MetricUpdate.activityCurrency(key, signal.getActivityCurrencyKey(), amount));
		}
		Integer score = amount == null ? null : (int) Math.min(Integer.MAX_VALUE, amount);
		return SignalClassification.metricOnly(
			MetricUpdate.activityScore(key, signal.getReliableCount(), score, signal.isActivityAmountTrusted()));
	}

	private SignalClassification completion(StructuredActivityDefinition def, StructuredActivityTracker tracker,
		SessionSignal signal, Integer authoritativeCount)
	{
		List<MetricUpdate> metrics = def.hasCompletionSource()
			? Collections.singletonList(MetricUpdate.activityCompletion(def.getKey(), authoritativeCount))
			: Collections.<MetricUpdate>emptyList();
		return evidence(def, tracker, signal, metrics);
	}

	private static SignalClassification completionMetricOnly(StructuredActivityDefinition def, Integer authoritativeCount)
	{
		return def.hasCompletionSource()
			? SignalClassification.metricOnly(MetricUpdate.activityCompletion(def.getKey(), authoritativeCount))
			: SignalClassification.ignore();
	}

	private static SignalClassification evidence(StructuredActivityDefinition def, StructuredActivityTracker tracker,
		SessionSignal signal, List<MetricUpdate> metrics)
	{
		tracker.confirm(tickKey(signal));
		return SignalClassification.startOrHeartbeat(ActivitySignalClassifier.structuredActivityIdentity(def), metrics, EvidenceStrength.SPECIFIC);
	}

	private static boolean absorbsIncidentalXp(LocationPolicy policy, RecognitionState state)
	{
		return policy == LocationPolicy.EXCLUSIVE
			|| (policy == LocationPolicy.STRONG_CONTEXT && state == RecognitionState.LOCKED);
	}

	private StructuredActivityDefinition owningDefinition(Session current)
	{
		if (current == null || current.getState() == SessionState.FINALIZED
			|| current.getActivityIdentity().getActivityType() != ActivityType.STRUCTURED_ACTIVITY)
		{
			return null;
		}
		return registry.byKey(current.getActivityIdentity().getActivityKey());
	}

	/** Same game tick = same confirmation; tick-less signals fall back to a 600 ms bucket. */
	static long tickKey(SessionSignal signal)
	{
		return signal.getGameTick() != null ? signal.getGameTick() : -1L - signal.getObservedAt().toEpochMilli() / 600L;
	}
}
