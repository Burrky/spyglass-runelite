package com.osrstelemetry.plugin.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The output of
 * ActivitySignalClassifier.classify() -- a lifecycle decision
 * (decisionKind + identity, identity non-null only for
 * START_OR_HEARTBEAT/REFINE; completionNature non-null only for
 * COMPLETION_ONLY) and an INDEPENDENT list of metric updates (almost
 * always zero or one; exactly two only for the boss-self-consumption
 * candidate's own confirm/abort classifications -- see
 * ActivitySignalClassifier.classifyBossKill()/classifyServerNpcLoot()),
 * returned together but meant to be APPLIED separately by the caller
 * (metrics and lifecycle decisions from a whole game-tick batch are
 * resolved together by SessionSignalBatchResolver, not here).
 *
 * getMetricUpdate() (the original, still-used-everywhere single-value
 * accessor) is completely unchanged in observable behavior for every
 * PRE-EXISTING call site: it returns the first (and, for every one of
 * those, ONLY) metric, or null when there are none. getMetricUpdates()
 * is the new accessor a caller must use to see every metric a
 * classification carries.
 *
 * EVIDENCE STRENGTH: also carries
 * an EvidenceStrength, non-null exactly when identity is non-null
 * (START_OR_HEARTBEAT/REFINE) -- see EvidenceStrength's own javadoc for
 * what this represents and why it is set HERE, at classification time,
 * from this signal's own provenance, rather than derived later from the
 * identity/ActivityType. SessionSignalBatchResolver and
 * SessionLifecycleEngine consume it as an opaque value; neither ever
 * re-derives it from a SignalKind or ActivityType.
 */
public final class SignalClassification
{
	private final SignalDecisionKind decisionKind;
	private final ActivityIdentity identity;
	private final List<MetricUpdate> metricUpdates;
	private final CompletionNature completionNature;
	private final EvidenceStrength evidenceStrength;

	private SignalClassification(SignalDecisionKind decisionKind, ActivityIdentity identity, List<MetricUpdate> metricUpdates, CompletionNature completionNature, EvidenceStrength evidenceStrength)
	{
		this.decisionKind = decisionKind;
		this.identity = identity;
		this.metricUpdates = metricUpdates == null ? Collections.<MetricUpdate>emptyList() : metricUpdates;
		this.completionNature = completionNature;
		this.evidenceStrength = evidenceStrength;
	}

	private static List<MetricUpdate> singleOrEmpty(MetricUpdate metricUpdate)
	{
		return metricUpdate == null ? Collections.<MetricUpdate>emptyList() : Collections.singletonList(metricUpdate);
	}

	static SignalClassification startOrHeartbeat(ActivityIdentity identity, MetricUpdate metricUpdate, EvidenceStrength evidenceStrength)
	{
		return new SignalClassification(SignalDecisionKind.START_OR_HEARTBEAT, identity, singleOrEmpty(metricUpdate), null, evidenceStrength);
	}

	/**
	 * CANDIDATE-METRIC BUFFERING: used ONLY by
	 * classifyBossKill()'s self-consumption-confirmed branch and
	 * classifyServerNpcLoot()'s real-switch-confirmed branch, to carry a
	 * previously-BUFFERED candidate metric (see ClassifierContext) ALONGSIDE
	 * this signal's own metric, both destined for the SAME resolved
	 * identity. `metricUpdates` is copied defensively and may be empty.
	 */
	static SignalClassification startOrHeartbeat(ActivityIdentity identity, List<MetricUpdate> metricUpdates, EvidenceStrength evidenceStrength)
	{
		return new SignalClassification(SignalDecisionKind.START_OR_HEARTBEAT, identity, new ArrayList<>(metricUpdates), null, evidenceStrength);
	}

	static SignalClassification refine(ActivityIdentity identity, MetricUpdate metricUpdate, EvidenceStrength evidenceStrength)
	{
		return new SignalClassification(SignalDecisionKind.REFINE, identity, singleOrEmpty(metricUpdate), null, evidenceStrength);
	}

	static SignalClassification refine(ActivityIdentity identity, List<MetricUpdate> metricUpdates, EvidenceStrength evidenceStrength)
	{
		return new SignalClassification(SignalDecisionKind.REFINE, identity, new ArrayList<>(metricUpdates), null, evidenceStrength);
	}

	static SignalClassification metricOnly(MetricUpdate metricUpdate)
	{
		return new SignalClassification(SignalDecisionKind.METRIC_ONLY, null, singleOrEmpty(metricUpdate), null, null);
	}

	static SignalClassification metricOnly(List<MetricUpdate> metricUpdates)
	{
		return new SignalClassification(SignalDecisionKind.METRIC_ONLY, null, new ArrayList<>(metricUpdates), null, null);
	}

	static SignalClassification completionOnly(CompletionNature nature, MetricUpdate metricUpdate)
	{
		return new SignalClassification(SignalDecisionKind.COMPLETION_ONLY, null, singleOrEmpty(metricUpdate), nature, null);
	}

	static SignalClassification ignore()
	{
		return new SignalClassification(SignalDecisionKind.IGNORE_FOR_SESSION, null, null, null, null);
	}

	public SignalDecisionKind getDecisionKind()
	{
		return decisionKind;
	}

	public ActivityIdentity getIdentity()
	{
		return identity;
	}

	public CompletionNature getCompletionNature()
	{
		return completionNature;
	}

	/** Non-null exactly when getIdentity() is non-null -- see class/EvidenceStrength javadoc. */
	public EvidenceStrength getEvidenceStrength()
	{
		return evidenceStrength;
	}

	/** The first metric, or null when this classification carries none -- unchanged behavior for every pre-existing (single-metric) call site. */
	MetricUpdate getMetricUpdate()
	{
		return metricUpdates.isEmpty() ? null : metricUpdates.get(0);
	}

	/** Every metric this classification carries -- never null, almost always 0 or 1 element; see class javadoc. */
	List<MetricUpdate> getMetricUpdates()
	{
		return metricUpdates;
	}
}
