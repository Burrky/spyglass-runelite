package com.osrstelemetry.plugin.loottracker;

import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * LIVE UPDATES: {@link #onEvent} applies every {@code SERVER_NPC_LOOT}
 * event directly to the CURRENT index (the same one
 * {@code ensureAccountLoaded()} installed/is populating), with no file
 * IO of its own -- cheap enough to run synchronously on whatever thread
 * {@code EventLedger} calls listeners from (normally the RuneLite
 * client thread), matching every other lightweight listener in this
 * project.
 *
 * SYNTHETIC eventId FOR LIVE RECORDS: {@code TelemetryEventListener.onEvent()}
 * does not currently pass the durable envelope's own eventId through to
 * listeners (see that interface's javadoc) -- this class assigns a
 * fresh, process-local {@code UUID} per live record instead. This is
 * safe because {@code EventLedger} calls every listener exactly once
 * per real {@code append()} (never replayed, never retried in-process
 * -- see EventLedger's own javadoc), so a live record can never need
 * dedup against ANOTHER live record; the only place real eventId-based
 * dedup matters is the durable rebuild reading actual envelope eventIds
 * back off disk (see LootTrackerPersistence), which this class never
 * interferes with because rebuild-for-account-N always completes (or is
 * in early, exclusively-background-thread progress) before any live
 * event for account N could plausibly be produced by actual gameplay.
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

	private final LootTrackerPersistence persistence = new LootTrackerPersistence();

	private volatile LootTrackerIndex index = new LootTrackerIndex();
	private volatile long currentAccountHash = NO_ACCOUNT;
	private ExecutorService rebuildExecutor;

	@Inject
	public LootTrackerCoordinator()
	{
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
				executor.shutdownNow();
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			executor.shutdownNow();
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

		currentAccountHash = accountHash;
		LootTrackerIndex fresh = new LootTrackerIndex();
		index = fresh;

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
		});
	}

	@Override
	public void onEvent(long accountHash, EventType type, Object payload, Instant observedAt)
	{
		if (type != EventType.SERVER_NPC_LOOT || !(payload instanceof EventPayloads.ServerNpcLoot))
		{
			return;
		}

		EventPayloads.ServerNpcLoot loot = (EventPayloads.ServerNpcLoot) payload;
		List<LootTrackerItem> items = new ArrayList<>();
		if (loot.getItems() != null)
		{
			for (EventPayloads.ServerNpcLootItem item : loot.getItems())
			{
				items.add(new LootTrackerItem(item.getItemId(), item.getItemName(), item.getQuantity()));
			}
		}

		LootTrackerRecord record = new LootTrackerRecord(
			UUID.randomUUID().toString(), loot.getSourceName(), loot.getSourceId(), observedAt, items);
		index.apply(record);
	}

	/** The current account's live index -- see class javadoc for why this reference can change on an account switch. */
	public LootTrackerIndex getIndex()
	{
		return index;
	}
}
