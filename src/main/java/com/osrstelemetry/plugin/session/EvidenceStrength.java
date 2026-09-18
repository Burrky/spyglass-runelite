package com.osrstelemetry.plugin.session;

/**
 * How strongly a single signal's
 * classification proves that its proposed ActivityIdentity is genuinely
 * what the player is doing right now -- set explicitly by
 * ActivitySignalClassifier, at the point of classification, from that
 * signal's own PROVENANCE (which SignalKind it is, and which of that
 * kind's own classify*() branches produced this particular
 * SignalClassification). Never inferred later from ActivityIdentity or
 * ActivityType -- see SessionLifecycleEngine's own javadoc for why that
 * inference is exactly the bug this class fixes.
 *
 * WHY NOT ActivityType: the SAME ActivityIdentity can legitimately be
 * established by evidence of very different strengths at different
 * moments -- e.g. BOSSING/zulrah from a mere BOSS_ACTIVITY_CONTEXT
 * (SPECIFIC: direct, but not itself a completion) versus BOSSING/zulrah
 * from an authoritative BOSS_KILL (AUTHORITATIVE). A model that derives
 * strength from the resulting identity's ActivityType alone cannot tell
 * those two apart, since both produce the identical ActivityIdentity.
 * Tagging strength at the classification itself, instead, means it is
 * always available at the one place that actually knows how the
 * identity was arrived at.
 *
 * EXTENSIBILITY (generic NPC recognition, not yet built): a
 * future classifier branch for a plain NPC interaction (walking up to /
 * interacting with an as-yet-unconfirmed NPC, with no kill or loot yet)
 * can tag its own classification ORDINARY, exactly like today's ordinary
 * skill XP; a later corroborating NPC_DEATH or SERVER_NPC_LOOT for that
 * same NPC can tag its own classification SPECIFIC or AUTHORITATIVE.
 * Nothing downstream of SignalClassification -- SessionSignalBatchResolver,
 * SessionRuntimeCoordinator, SessionLifecycleEngine -- ever branches on a
 * SignalKind or ActivityType to decide evidentiary strength; they only
 * ever consume this enum. Adding a new evidence source is therefore
 * always just "tag it with the right value here," never a redesign of
 * this type or of how the engine consumes it.
 *
 * Only meaningful for a classification that actually proposes an
 * identity (SignalDecisionKind.START_OR_HEARTBEAT / REFINE);
 * classifications that carry no identity (METRIC_ONLY / COMPLETION_ONLY /
 * IGNORE_FOR_SESSION) have no evidence strength to speak of.
 */
public enum EvidenceStrength
{
	/**
	 * A hard completion/authority signal for exactly this identity -- e.g.
	 * BOSS_KILL. Always switches/refines immediately; never gated by any
	 * provisional-candidate mechanism, whether the established session is
	 * ACTIVE or SUSPENDED (handoff Case D).
	 */
	AUTHORITATIVE,

	/**
	 * Direct, per-event telemetry that specifically names this identity
	 * but is not itself a completion -- e.g. BOSS_ACTIVITY_CONTEXT, an
	 * authoritative SLAYER_TASK_PROGRESS, loot that corroborates a
	 * different NPC. Always switches/refines immediately, exactly like
	 * AUTHORITATIVE (handoff Case D) -- SPECIFIC and AUTHORITATIVE are
	 * distinguished for provenance/reporting clarity, not because the
	 * engine currently treats them differently.
	 */
	SPECIFIC,

	/**
	 * Evidence that is consistent with this identity but is not itself
	 * proof of it -- e.g. ordinary skill XP, combat XP merely inferred
	 * from a tracked-but-uncorroborated Slayer task. May be held
	 * provisional against an already-established session with stronger
	 * evidence before it is allowed to switch/start anything -- see
	 * SessionLifecycleEngine's candidate mechanism.
	 */
	ORDINARY,

	/**
	 * Evidence that makes no identity claim at all (Magic XP, generic
	 * combat XP against a SUSPENDED specific session, etc). Never
	 * produces a START_OR_HEARTBEAT/REFINE classification, so this value
	 * is never actually attached to one in practice -- listed only for
	 * completeness of the concept described in this class's javadoc.
	 */
	CONTEXTUAL
}
