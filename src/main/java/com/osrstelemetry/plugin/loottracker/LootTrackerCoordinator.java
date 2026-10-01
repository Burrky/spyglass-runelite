package com.osrstelemetry.plugin.loottracker;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The Loot Tracker's own top-level runtime coordinator -- registered as a
 * {@link TelemetryEventListener} exactly like {@code
 * SessionRuntimeCoordinator} (see OsrsTelemetryPlugin's own
 * startUp()/shutDown() wiring for the paired addListener()/
 * removeListener() this class requires too), but otherwise entirely
 * UNRELATED to it: this class has no session/lifecycle/classifier
 * dependency of any kind (the spec's "independence from session
 * lifecycle" requirement), and owns its own {@link LootTrackerIndex}
 * rather than sharing or reading from {@code SessionAggregates}.
 *
 * ACCOUNT LOADING (mirrors SessionRuntimeCoordinator.ensureAccountLoaded()'s
 * own idempotency contract -- see its javadoc): a same-account call
 * (fresh plugin start already loaded, or a world hop/relog) is a
 * deliberate no-op; a genuine account change installs a brand-new,
 * empty index immediately (so the panel never shows one account's loot
 * under another's identity, even briefly) and kicks off a background
 * durable rebuild (see below) to repopulate it from that account's own
 * events.jsonl.
 *
 * WHY THE REBUILD RUNS ON A BACKGROUND THREAD, NOT THE CALLER'S: unlike
 * {@code SessionPersistence.loadCurrent()} (one small JSON file),
 * {@link LootTrackerPersistence#rebuild} reads this account's ENTIRE
 * events.jsonl -- potentially every telemetry event this account has
 * ever produced, not just loot. {@code ensureAccountLoaded()} is called
 * from {@code OsrsTelemetryPlugin.handleLogin()} via
 * {@code ClientThread.invokeLater()} (see that method's own javadoc for
 * why Client-thread-marshalled login handling exists at all) -- running
 * a potentially large sequential file read there would block the
 * client thread, exactly the class of mistake
 * {@code TelemetryEventListener}'s own javadoc warns listeners against.
 * {@link LootTrackerIndex} is fully thread-safe (synchronized methods),
 * so installing the fresh (initially empty) index synchronously and
 * then populating it progressively from a background thread is safe:
 * an EDT poll mid-rebuild simply sees a partially-populated, still
 * internally-consistent snapshot, never a torn/corrupted one.
 *
 * LIVE UPDATES: {@link #onEvent} applies every canonical loot event --
 * {@code SERVER_NPC_LOOT} and {@code STRUCTURED_REWARD} (see
 * {@link LootTrackerEvents}) -- directly to the CURRENT index (the same one
 * {@code ensureAccountLoaded()} installed/is populating), with no file
 * IO of its own -- cheap enough to run synchronously on whatever thread
 * {@code EventLedger} calls listeners from (normally the RuneLite
 * client thread), matching every other lightweight listener in this
 * project.
 *
 * CANONICAL eventId END TO END: EventLedger passes each listener the
 * durable envelope's own eventId, and live records use it -- the same id
 * the rebuild reads back off disk -- so an event delivered live while an
 * account-load rebuild is also reading it from events.jsonl is applied
 * once ({@link LootTrackerIndex}'s eventId dedup). A synthetic UUID is
 * used only by a direct caller with no envelope id.
 */
@Slf4j
@Singleton
public final class LootTrackerCoordinator implements TelemetryEventListener
{
	/**
	 * Spec: "~365 day retention (display only, raw telemetry never
	 * deleted)". Bounds what {@link #ensureAccountLoaded} loads into the
	 * LIVE in-memory index -- see LootTrackerPersistence's own javadoc
	 * for why this never touches anything on disk.
	 */
	public static final int RETENTION_DAYS = 365;

	private static final long NO_ACCOUNT = -1L;

	private final LootTrackerPersistence persistence;

	private volatile LootTrackerIndex index = new LootTrackerIndex();
	private volatile long currentAccountHash = NO_ACCOUNT;
	private ExecutorService rebuildExecutor;

	@Inject
	public LootTrackerCoordinator(Gson gson)
	{
		this.persistence = new LootTrackerPersistence(gson);
	}

	/** Paired with {@link #shutdown()} -- same start()/shutdown() lifecycle convention as EventLedger/LocalStateStore. */
	public synchronized void start()
	{
		if (rebuildExecutor != null && !rebuildExecutor.isShutdown())
		{
			return;
		}
		rebuildExecutor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "osrs-telemetry-loot-tracker-rebuild");
			t.setDaemon(true);
			return t;
		});
	}

	public synchronized void shutdown()
	{
		ExecutorService executor = this.rebuildExecutor;
		if (executor == null)
		{
			return;
		}
		executor.shutdown();
		try
		{
			if (!executor.awaitTermination(2, TimeUnit.SECONDS))
			{
				log.warn("LootTrackerCoordinator rebuild executor did not drain within the graceful window; it will keep draining in the background rather than being forcibly interrupted");
			}
		}
		catch (InterruptedException e)
		{
			log.warn("Interrupted while awaiting graceful drain of the LootTrackerCoordinator rebuild executor; leaving it to keep draining in the background");
		}
	}

	/**
	 * Called from {@code OsrsTelemetryPlugin.handleLogin()} on every
	 * LOGGED_IN transition, exactly like
	 * {@code SessionRuntimeCoordinator.ensureAccountLoaded()} -- see
	 * class javadoc for the full idempotency/threading contract.
	 */
	public synchronized void ensureAccountLoaded(long accountHash)
	{
		if (accountHash == currentAccountHash)
		{
			return;
		}

		// Index first, then the account: a live event for the new account
		// that passes onEvent's account check can only see the new index.
		LootTrackerIndex fresh = new LootTrackerIndex();
		index = fresh;
		currentAccountHash = accountHash;

		ExecutorService executor = this.rebuildExecutor;
		if (executor == null || executor.isShutdown())
		{
			log.warn("ensureAccountLoaded() called while LootTrackerCoordinator is not started; Loot Tracker will remain empty for account {}", accountHash);
			return;
		}
		Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
		executor.submit(() ->
		{
			int applied = persistence.rebuild(accountHash, fresh, cutoff);
			log.debug("Loot Tracker rebuild for account {} applied {} record(s)", accountHash, applied);
			notifyChanged();
		});
		notifyChanged();
	}

	@Override
	public void onEvent(long accountHash, EventType type, Object payload, Instant observedAt)
	{
		onEvent(accountHash, type, payload, observedAt, null);
	}

	/**
	 * Live path. Records carry the durable envelope's own eventId (EventLedger
	 * passes it), so a live record and the same event read back by a
	 * concurrent account-load rebuild dedupe to one; a synthetic id is used
	 * only when a caller has no envelope id.
	 *
	 * Canonical inputs: SERVER_NPC_LOOT (NPC loot) and STRUCTURED_REWARD
	 * (structured activity reward claims). Both are raw telemetry, consumed
	 * independently of Current Session: a reward the session declines to own
	 * is still recorded here, exactly once.
	 */
	@Override
	public void onEvent(long accountHash, EventType type, Object payload, Instant observedAt, String eventId)
	{
		if (accountHash != currentAccountHash)
		{
			// Another account's event (an account switch in flight): it is in
			// that account's events.jsonl and is picked up by its own rebuild;
			// it must never land in this account's index.
			return;
		}
		String id = eventId != null ? eventId : UUID.randomUUID().toString();
		LootTrackerRecord record = null;
		if (type == EventType.SERVER_NPC_LOOT && payload instanceof EventPayloads.ServerNpcLoot)
		{
			record = LootTrackerEvents.fromServerNpcLoot(id, (EventPayloads.ServerNpcLoot) payload, observedAt);
		}
		else if (type == EventType.STRUCTURED_REWARD && payload instanceof EventPayloads.StructuredReward)
		{
			record = LootTrackerEvents.fromStructuredReward(id, (EventPayloads.StructuredReward) payload, observedAt);
		}
		if (record != null && index.apply(record))
		{
			notifyChanged();
		}
	}

	private volatile Runnable changeListener;

	/**
	 * Best-effort, downstream notification that the index changed (a live
	 * record applied, an account switch, a rebuild finished). Called on
	 * whichever thread made the change; must be cheap. The index never
	 * depends on a listener existing or succeeding.
	 */
	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener;
	}

	private void notifyChanged()
	{
		Runnable listener = changeListener;
		if (listener == null)
		{
			return;
		}
		try
		{
			listener.run();
		}
		catch (RuntimeException e)
		{
			log.debug("Loot Tracker change listener failed; the index is unaffected", e);
		}
	}

	/** The current account's live index -- see class javadoc for why this reference can change on an account switch. */
	public LootTrackerIndex getIndex()
	{
		return index;
	}
}
