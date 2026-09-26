package com.osrstelemetry.plugin.session;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * INTERRUPTED-ACTIVITY RESUME (A -&gt; brief B -&gt; A). The additive,
 * account-scoped persistence record for the ONE interrupted/resumable
 * prior session, if any -- see SessionLifecycleEngine's own
 * interruptedCandidate field javadoc for the full feature. Written and
 * read exclusively by SessionPersistence, via
 * TelemetryPaths.interruptedCandidateFile(accountHash), as a small
 * SEPARATE file rather than any change to session_state.json's own
 * shape or the finalized sessions/{sessionId}.json document format --
 * both of those remain byte-for-byte exactly as they were before this
 * feature existed. Absence of this file (a missing/malformed/`null`
 * read -- see LocalStateStore.readIfExists()'s existing fail-open
 * contract, reused here unchanged) means "nothing parked," which is
 * both the correct steady-state answer for every account and the
 * correct answer for an account that predates this feature entirely --
 * full backward compatibility, no migration.
 *
 * Deliberately a plain additive DTO, not a change to Session itself:
 * `session` below is the parked candidate's own full, unmodified
 * Session document (same sessionId/startedAt/aggregates/state it had
 * the instant it was parked -- still logically ACTIVE, never given a
 * new SessionState value; see SessionState's own closed-3-value
 * javadoc for why no 4th "PARKED" state was added anywhere in this
 * project). `interruptedAt`/`expiresAt` mirror
 * SessionLifecycleEngine's own in-memory
 * interruptedCandidateInterruptedAt/interruptedCandidateExpiresAt
 * fields exactly, so a restart can reconstruct identical in-memory
 * state via SessionLifecycleEngine.rehydrate()'s 5-arg overload.
 *
 * TIMESTAMP CONVENTION: same nullable ISO-8601 String fields as
 * Session (see its own javadoc) -- Gson-friendly, no custom
 * TypeAdapter required.
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@EqualsAndHashCode
@NoArgsConstructor
public class InterruptedCandidateRecord
{
	private Session session;
	private String interruptedAt;
	private String expiresAt;

	InterruptedCandidateRecord(Session session, Instant interruptedAt, Instant expiresAt)
	{
		this.session = session;
		this.interruptedAt = interruptedAt == null ? null : interruptedAt.toString();
		this.expiresAt = expiresAt == null ? null : expiresAt.toString();
	}

	Instant interruptedAtInstant()
	{
		return interruptedAt == null ? null : Instant.parse(interruptedAt);
	}

	Instant expiresAtInstant()
	{
		return expiresAt == null ? null : Instant.parse(expiresAt);
	}
}
