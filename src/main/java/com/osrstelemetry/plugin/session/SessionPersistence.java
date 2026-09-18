package com.osrstelemetry.plugin.session;

import com.osrstelemetry.plugin.collectors.LoadoutArchive;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;

/**
 * I/O
 * orchestration around SessionLifecycleEngine, built entirely on the
 * existing LocalStateStore ("do not redesign LocalStateStore
 * unless genuinely necessary"; it wasn't). Every method takes an
 * explicit accountHash and reads/writes only that account's directory
 * via TelemetryPaths, so callers get account isolation for free —
 * there is no shared/static state anywhere in this class.
 *
 * WRITE ORDERING (reusing the lesson already established by
 * Bank snapshot persistence — see LocalStateStore's own javadoc and
 * ContainerCollector's bank-snapshot-then-pointer-update chaining):
 * persistFinalized() writes the immutable sessions/{sessionId}.json
 * record FIRST, and only in that write's onWritten success callback
 * does it go on to overwrite session_state.json. If the immutable
 * write fails, onWritten never runs, so session_state.json is left
 * completely untouched — still holding whatever was last durably
 * written for this account (typically the same session in its
 * pre-finalization SUSPENDED shape). That is never "the only copy of
 * the session" silently destroyed: the finalized attempt simply didn't
 * happen, and a later rehydrate() of the still-intact session_state.json
 * will naturally re-evaluate (and, if warranted, re-attempt
 * finalization of) the same session against a fresh `now`.
 */
public final class SessionPersistence
{
	private final LocalStateStore store;
	private final LoadoutResolver resolver;

	/**
	 * Preserved
	 * for backward compatibility with every existing caller/test that
	 * constructs a SessionPersistence with just a store -- delegates
	 * to the two-arg constructor below with a null archive, which
	 * LoadoutResolver treats as "always resolve to unavailable" (see
	 * its own javadoc) rather than throwing. Production wiring
	 * (SessionRuntimeCoordinator) uses the two-arg constructor so
	 * loadout resolution actually has real data to work with.
	 */
	public SessionPersistence(LocalStateStore store)
	{
		this(store, null);
	}

	/**
	 * `loadoutArchive` may be null (see the single-arg constructor
	 * above) -- LoadoutResolver is null-safe by construction.
	 */
	public SessionPersistence(LocalStateStore store, LoadoutArchive loadoutArchive)
	{
		this.store = store;
		this.resolver = new LoadoutResolver(loadoutArchive);
	}

	/**
	 * Best-effort load of the current (non-finalized) session for this
	 * account. Fails open (returns null) on a missing file, malformed
	 * JSON, or a file whose content is the literal JSON `null` written
	 * by persistCurrent(null) below — all three mean the same thing:
	 * "no current session," never distinguished from each other, per
	 * this project's established fail-open convention (see
	 * BankSnapshotBaseline.loadFrom()/LocalStateStore.readIfExists()).
	 */
	public Session loadCurrent(long accountHash)
	{
		return store.readIfExists(TelemetryPaths.sessionStateFile(accountHash), Session.class);
	}

	/**
	 * Best-effort load of one specific finalized session by id, for
	 * History's detail view -- same fail-open contract as
	 * loadCurrent() above (null on a missing file, malformed JSON, or
	 * any other read failure; never throws). This is a pure read of
	 * the immutable sessions/{sessionId}.json record persistFinalized()
	 * already writes -- no new file, no new write path, and it is
	 * safe to call from any thread (see LocalStateStore.readIfExists()'s
	 * own javadoc), though a caller doing this in bulk (History's
	 * discovery/index) is still responsible for keeping
	 * that bulk work off the Swing EDT.
	 */
	public Session loadFinalized(long accountHash, String sessionId)
	{
		return store.readIfExists(TelemetryPaths.sessionFile(accountHash, sessionId), Session.class);
	}

	/**
	 * Persist the current in-progress session as session_state.json.
	 * Pass null to represent "no current session" — this writes the
	 * literal JSON `null` rather than deleting the file (LocalStateStore
	 * has no delete primitive, and this project prefers not to add one
	 * just for this), which loadCurrent() above already treats
	 * identically to an absent file.
	 */
	public void persistCurrent(long accountHash, Session current)
	{
		attachStartingLoadoutIfNeeded(current);
		store.write(TelemetryPaths.sessionStateFile(accountHash), current);
	}

	/**
	 * Resolves and attaches a session's starting
	 * loadout AT MOST ONCE per Session object: a no-op the instant
	 * startingLoadout is already non-null. Since this runs on every
	 * ordinary persistCurrent() call, and a brand-new Session is
	 * persisted within about one tick of actually starting (see
	 * SessionRuntimeCoordinator's own tick-batch-closure javadoc), the
	 * FIRST call this method ever sees for a given session happens
	 * while LoadoutArchive still holds fresh, close-to-startedAt
	 * observations -- satisfying the "resolve close to session start"
	 * requirement without any hook into SessionRuntimeCoordinator/
	 * SessionLifecycleEngine's own establishment logic.
	 *
	 * This is also exactly what ensures that identity
	 * refinement within the same sessionId never replaces the
	 * starting loadout: refineIdentity()-style updates never produce
	 * a different sessionId or a different Session object's
	 * startingLoadout field going from non-null back to being
	 * re-resolved -- once set, this guard skips it forever.
	 */
	private void attachStartingLoadoutIfNeeded(Session session)
	{
		if (session == null || session.getStartingLoadout() != null)
		{
			return;
		}
		session.setStartingLoadout(resolver.resolveStarting(session.startedAtInstant()));
	}

	/**
	 * Resolved once, at finalization time -- the natural
	 * "session end" instant -- against whatever LoadoutArchive still
	 * holds. Optional by design: resolveEnding() always returns a
	 * value (falling back to LoadoutSnapshot.unavailable() rather than
	 * null), so this can never block or fail finalization.
	 */
	private void attachEndingLoadoutIfNeeded(Session session)
	{
		if (session == null || session.getEndingLoadout() != null)
		{
			return;
		}
		session.setEndingLoadout(resolver.resolveEnding(session.finalizedAtInstant()));
	}

	/**
	 * Durably persist a just-finalized session as its own immutable,
	 * never-again-mutated record, and only once that succeeds, update
	 * session_state.json to reflect whatever LifecycleResult.getCurrent()
	 * returned alongside it (may be null, may be a freshly-started
	 * replacement session for a different activity) — see class javadoc
	 * for the full ordering rationale. Callers should pass exactly the
	 * (finalized, current) pair from one LifecycleResult.
	 */
	public void persistFinalized(long accountHash, Session finalized, Session newCurrent)
	{
		// Fallback safety net (should ordinarily already be non-null via
		// persistCurrent() above) plus the ending-loadout resolution
		// that only ever makes sense exactly here, at finalization time.
		attachStartingLoadoutIfNeeded(finalized);
		attachEndingLoadoutIfNeeded(finalized);
		store.write(
			TelemetryPaths.sessionFile(accountHash, finalized.getSessionId()),
			finalized,
			() -> persistCurrent(accountHash, newCurrent)
		);
	}

	/**
	 * Synchronous counterpart to persistCurrent(),
	 * for the one caller that cannot tolerate the ordinary fire-and-
	 * forget write's timing -- SessionRuntimeCoordinator's ClientShutdown
	 * handler, mirroring the exact same durability reasoning already
	 * established for ContainerCollector's Bank snapshot shutdown path
	 * (see EventLedger.appendAndWait()'s javadoc for the same pattern
	 * applied to the event ledger). Not used anywhere in ordinary
	 * per-tick persistence -- see persistCurrent() above for that.
	 *
	 * @return true only if this write was confirmed durable before
	 * timeoutMs elapsed.
	 */
	public boolean persistCurrentAndWait(long accountHash, Session current, long timeoutMs)
	{
		attachStartingLoadoutIfNeeded(current);
		return store.writeAndWait(TelemetryPaths.sessionStateFile(accountHash), current, timeoutMs);
	}
}
