package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.ItemManager;

/**
 * CONFIRMED CONSTANTS: InventoryID.INV / .WORN / .BANK / .SEED_VAULT
 * are all directly confirmed against a complete fetch of the current
 * net.runelite.api.gameval.InventoryID source — `INV = 93`,
 * `WORN = 94`, `BANK = 95`, `SEED_VAULT = 626` all exist verbatim with
 * those exact names (also independently confirmed:
 * `INV_GROUP_TEMP = 659`, already relied on elsewhere in this file;
 * and, unrelated to this collector, `GRAVESTONE = 525` /
 * `DEATH_PERMANENT = 636`, the Death Storage candidates this project
 * has NOT built a producer for).
 * This is a direct source read, the same evidence tier as every other
 * confirmed constant in this file.
 *
 * GROUP IRONMAN STORAGE: InventoryID.INV_GROUP_TEMP is directly
 * present in a complete fetch of the real current BankPlugin.java —
 * `getWidgetContainerPrices(InterfaceID.SharedBank.ITEMS,
 * InventoryID.INV_GROUP_TEMP)` inside its GROUP_IRONMAN_STORAGE_BUILD
 * handler.
 *
 * BANK SNAPSHOT POLICY: snapshots are taken "after stable changes" (a
 * ~3s debounced quiet period) WHILE the bank is being used, but are
 * additionally force-finalized immediately on three events, so a
 * valid pending snapshot is never silently lost:
 *   1. the bank interface closing (WidgetClosed for
 *      InterfaceID.BANKMAIN — see BANK_WIDGET_GROUP_ID javadoc below
 *      for the confidence level in that specific constant);
 *   2. an account switch (resetForAccountSwitch());
 *   3. plugin shutdown (flushPendingAndWait(), called from the
 *      plugin's shutDown() and actually waited on).
 * In all three cases the debounce timer is cancelled and the most
 * recently observed bank contents are persisted right away instead of
 * being dropped.
 *
 * ORDERING: the immutable snapshot file must be durably and
 * SUCCESSFULLY written before the bank.json pointer update, which
 * must itself succeed before the BANK_SNAPSHOT event is appended.
 * LocalStateStore.write()'s onWritten callback only fires when the
 * write actually succeeded (see its javadoc), so a BANK_SNAPSHOT event
 * can no longer reference a file that failed to reach disk.
 *
 * TERMINOLOGY: snapshotId is a random UUID, nothing more — it is NOT
 * content-addressed (not derived from a hash of the snapshot's
 * contents). A random UUID is a perfectly fine unique identifier for
 * this purpose; it just isn't the thing "content-addressed" means.
 *
 * DEDUPLICATION: runtime testing on a real client found that simply
 * OPENING the bank — with zero
 * actual content change — reliably produces a new immutable snapshot,
 * because RuneLite re-fires ItemContainerChanged for the bank on
 * every open (the bank's ItemContainer is only populated while the
 * interface is open, so each open requires a fresh re-delivery of
 * contents from the server, and nothing in RuneLite's event bridge
 * suppresses that re-delivery just because it happens to match what
 * was there last time). This was never an intended behavior — see
 * BankSnapshotBaseline for the fix: a new snapshot is now written
 * only when there's no valid baseline, the contents genuinely changed,
 * or the last real snapshot has aged past a 10-minute freshness
 * window. The persisted baseline (not just an in-memory one) is
 * seeded from bank.json's own latestSnapshotId on first need each
 * session, so restarting RuneLite shortly after a real snapshot
 * doesn't produce an immediate duplicate.
 *
 * LATEST-SNAPSHOT-ID INVARIANT: writeContainer()'s
 * immediate per-observation write ("Path A" below) used to build a
 * bank StorageState with no latestSnapshotId at all, which Gson then
 * serialized with the field simply absent — clobbering whatever
 * pointer bank.json previously held every single time the bank fired
 * ItemContainerChanged, including every dedup-skipped observation.
 * Before dedup existed this was harmless because the debounced
 * finalize path (takeImmutableBankSnapshot/finalizeBankSnapshotBlocking,
 * "Path B") ran again shortly after and repaired the pointer; once
 * dedup could make Path B return early without writing anything, Path
 * A's clobbered null became the final on-disk state. Fixed two ways,
 * belt-and-suspenders:
 *   1. Path A now stamps latestSnapshotId from the in-memory baseline
 *      (loaded on first need for this account-session) on every
 *      ordinary write, so it never regresses to null once a real
 *      snapshot exists.
 *   2. Path B, when it decides to SKIP writing a new immutable
 *      snapshot (dedup), now explicitly refreshes bank.json's pointer
 *      (current items + fresh lastObservedAt + preserved snapshotId)
 *      instead of silently doing nothing — durably (writeAndWait) on
 *      the blocking shutdown path specifically, so a deduped
 *      observation immediately followed by process exit can't lose
 *      the pointer refresh to an in-flight async write.
 * Neither path ever touches the in-memory baseline's createdAt/items
 * except inside recordBankBaseline(), which only ever runs after a
 * NEW immutable snapshot is confirmed durably written — see its
 * javadoc and BankSnapshotBaseline's for why that's the one thing
 * that must never happen on a refresh-only write.
 */
@Slf4j
public class ContainerCollector
{
	private static final Map<Integer, String> STORAGE_IDS = new HashMap<>();
	private static final Map<Integer, Boolean> CONTINUOUS = new HashMap<>();
	private static final long BANK_SNAPSHOT_DEBOUNCE_MS = 3000;
	private static final Duration BANK_SNAPSHOT_MAX_AGE = Duration.ofMinutes(10);

	/**
	 * Bound used for both
	 * LocalStateStore.writeAndWait() calls and the new
	 * EventLedger.appendAndWait() call in the durable finalization
	 * chain (finalizeBankSnapshotBlocking(), and now also the "tail" of
	 * the ordinary async chain -- see takeImmutableBankSnapshot()). Same
	 * 2s bound already used for the pre-existing writeAndWait() calls;
	 * kept as one named constant instead of three repeated literals.
	 */
	private static final long DURABLE_WRITE_TIMEOUT_MS = 2000;

	/**
	 * Bound for
	 * flushPendingAndWait() waiting on an ALREADY in-flight async
	 * finalization (one that WidgetClosed/the scheduled debounce
	 * started before shutdown began) to reach its terminal state. See
	 * inFlightBankFinalization's javadoc for the race this covers.
	 */
	private static final long IN_FLIGHT_FINALIZATION_WAIT_MS = 5000;

	/**
	 * WidgetClosed.getGroupId() for the bank interface.
	 *
	 * CONFIRMED: `InterfaceID.BANKMAIN` -- a direct fetch of the current
	 * net.runelite.api.gameval.InterfaceID source shows
	 * `public static final int BANKMAIN = 12;` verbatim, a top-level
	 * int constant, the correct type to compare against
	 * `WidgetClosed#getGroupId()` (also int). Its value (12) matches
	 * the numeric literal this project had already independently
	 * confirmed via a real merged Plugin Hub plugin's changelog
	 * ("WidgetClosed for group 12") — two independent sources agree.
	 */
	private static final int BANK_WIDGET_GROUP_ID = InterfaceID.BANKMAIN;

	static
	{
		STORAGE_IDS.put(InventoryID.INV, "inventory");
		CONTINUOUS.put(InventoryID.INV, true);

		STORAGE_IDS.put(InventoryID.WORN, "equipment");
		CONTINUOUS.put(InventoryID.WORN, true);

		STORAGE_IDS.put(InventoryID.BANK, "bank");
		CONTINUOUS.put(InventoryID.BANK, false);

		STORAGE_IDS.put(InventoryID.SEED_VAULT, "seed_vault");
		CONTINUOUS.put(InventoryID.SEED_VAULT, false);

		STORAGE_IDS.put(InventoryID.INV_GROUP_TEMP, "group_storage");
		CONTINUOUS.put(InventoryID.INV_GROUP_TEMP, false);
		// INV_PLAYER_TEMP (player's own inventory mirrored while the
		// group storage interface is open) is deliberately not mapped
		// here — it duplicates data the continuous "inventory" entry
		// already covers, just re-surfaced under a different container
		// id while that interface happens to be open. Scope decision,
		// not an oversight.
	}

	private static final Map<Integer, String> EQUIPMENT_SLOT_NAMES = new HashMap<>();

	static
	{
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			EQUIPMENT_SLOT_NAMES.put(slot.getSlotIdx(), slot.name());
		}
	}

	/** Exposed package-private purely so the slot-index -> semantic-name
	 * mapping is unit-testable without instantiating the collector or
	 * a Client — see ContainerCollectorTest. */
	static Map<Integer, String> equipmentSlotNames()
	{
		return EQUIPMENT_SLOT_NAMES;
	}

	private final Client client;
	private final ItemManager itemManager;
	private final LocalStateStore store;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;
	private final ScheduledExecutorService scheduledExecutor;

	/**
	 * Race avoidance between the scheduled debounce finalization and a
	 * concurrent WidgetClosed/shutdown/account-switch flush: all
	 * reads/clears of the three
	 * pending* fields below now happen only while holding this lock,
	 * and each consumer (the scheduled task, flushPending(),
	 * flushPendingAndWait()) checks pendingSnapshotItems == null under
	 * the lock before proceeding. Previously the scheduled task closed
	 * over its own local accountHash/items captured at schedule time
	 * and ran regardless of whether a concurrent flush had *also*
	 * decided to finalize the same pending observation (cancel(false)
	 * does not stop a task that has already started running) — a
	 * narrow window where both paths could call
	 * takeImmutableBankSnapshot() for the identical observation,
	 * risking two immutable snapshot files (and two BANK_SNAPSHOT
	 * events) for what should be one. Now exactly one caller ever wins
	 * the race; the other sees pendingSnapshotItems already null and
	 * is a no-op.
	 */
	private final Object pendingLock = new Object();
	private volatile ScheduledFuture<?> pendingBankSnapshot;
	private volatile long pendingSnapshotAccountHash;
	private volatile List<StorageState.StorageItem> pendingSnapshotItems;

	/**
	 * Without this, a real RuneLite run could produce a durable immutable
	 * snapshot and a durable bank.json pointer update but NO
	 * BANK_SNAPSHOT event, because eventLedger.append() is
	 * fire-and-forget and the plugin could exit before EventLedger's
	 * graceful drain window ran.
	 *
	 * Tracks the terminal outcome of whichever bank finalization
	 * (async, via WidgetClosed/the scheduled debounce, OR the blocking
	 * shutdown path) most recently CONSUMED pendingSnapshotItems --
	 * i.e. was actually accepted to run, not merely scheduled. Always
	 * non-null after the first bank observation this account-session;
	 * seeded to an already-completed future so an idle collector never
	 * makes a caller wait on nothing.
	 *
	 * WHY THIS EXISTS: flushPendingAndWait() (called from plugin
	 * shutDown()) previously only ever looked at pendingBankSnapshot/
	 * pendingSnapshotItems. But WidgetClosed -> flushPending() and the
	 * scheduled debounce -> runScheduledBankFinalize() can CONSUME and
	 * null those fields (accepting the observation for finalization)
	 * and then continue an asynchronous snapshot -> pointer -> event
	 * chain entirely outside flushPendingAndWait()'s view. If shutdown
	 * happens to land in that window -- which is exactly what closing
	 * RuneLite can trigger, since it can close the bank widget
	 * immediately before shutDown() runs -- flushPendingAndWait() used
	 * to see "nothing pending" and return immediately, while the
	 * already-in-flight chain was still running and could still be
	 * torn down mid-chain by the store.shutdown()/eventLedger.shutdown()
	 * that follows.
	 *
	 * The field is written ATOMICALLY together with consuming
	 * pendingSnapshotItems (same synchronized(pendingLock) block, in
	 * flushPending()/runScheduledBankFinalize()) specifically so there
	 * is never a window where "pendingSnapshotItems is null" and "the
	 * in-flight future for that same observation is not yet visible"
	 * are both true at once -- that gap is exactly what would let
	 * flushPendingAndWait() falsely conclude there is nothing to wait
	 * for. flushPendingAndWait() reads it (also under pendingLock) only
	 * when it finds nothing left to consume itself, and then blocks
	 * (bounded by IN_FLIGHT_FINALIZATION_WAIT_MS) on whatever
	 * finalization is already running instead of starting a second,
	 * duplicate one.
	 *
	 * KNOWN LIMITATION (documented, not silently papered over): the
	 * async success path completes this future only after
	 * eventLedger.appendAndWait() returns (success or failure) -- but
	 * if the FIRST step (the immutable snapshot's own store.write())
	 * fails, LocalStateStore.write()'s onWritten callback never runs at
	 * all, so nothing on that path ever completes this future.
	 * flushPendingAndWait()'s bounded wait is what keeps that scenario
	 * safe (it times out and returns rather than hanging or fabricating
	 * a duplicate finalization) rather than a completion guarantee for
	 * that specific failure shape.
	 */
	private volatile CompletableFuture<Boolean> inFlightBankFinalization = CompletableFuture.completedFuture(true);

	/**
	 * null = not yet loaded/attempted this account-session. Never
	 * re-attempted after the first try (whether it found a real
	 * baseline or came back absent()) — a successful new snapshot
	 * write updates this directly, in-memory, without ever needing a
	 * second disk read.
	 */
	private volatile BankSnapshotBaseline bankBaseline;

	/**
	 * Guards against cross-account contamination of the in-memory dedup
	 * baseline: bumped every time resetForAccountSwitch() runs. A
	 * pending async snapshot write
	 * started under the OLD account (e.g. via flushPending() inside
	 * resetForAccountSwitch() itself) can still be in flight when the
	 * NEW account's first observation already reloads bankBaseline.
	 * Without this guard, the OLD write's onWritten callback landing
	 * late would call recordBankBaseline() and silently overwrite the
	 * NEW account's freshly-loaded baseline with the OLD account's
	 * items/snapshotId — a real cross-account bleed into the shared
	 * in-memory field, even though the on-disk files themselves stay
	 * correctly isolated per accountHash. The OLD account's on-disk
	 * bank.json/bank_snapshots/events.jsonl still get written
	 * correctly either way; only the in-memory cache write is skipped
	 * when its captured epoch is stale.
	 */
	private volatile long accountEpoch = 0;

	@Inject
	public ContainerCollector(
		Client client,
		ItemManager itemManager,
		LocalStateStore store,
		EventLedger eventLedger,
		OsrsTelemetryConfig config,
		ScheduledExecutorService scheduledExecutor)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.store = store;
		this.eventLedger = eventLedger;
		this.config = config;
		this.scheduledExecutor = scheduledExecutor;
	}

	/**
	 * Called on account switch. Per the revised policy above, a
	 * pending bank snapshot for the OLD account is now force-finalized
	 * immediately rather than dropped — done FIRST, while the OLD
	 * account's baseline is still in effect, before that baseline is
	 * cleared below. Never compare one account's bank contents against
	 * another's.
	 */
	public void resetForAccountSwitch()
	{
		flushPending();
		bankBaseline = null;
		accountEpoch++;
	}

	/**
	 * Called from account switch and WidgetClosed — async finalize is
	 * correct here since the client keeps running afterward.
	 */
	public void flushPending()
	{
		long accountHash;
		List<StorageState.StorageItem> items;
		CompletableFuture<Boolean> future;
		synchronized (pendingLock)
		{
			ScheduledFuture<?> pending = pendingBankSnapshot;
			if (pending == null)
			{
				return;
			}
			pending.cancel(false);
			pendingBankSnapshot = null;
			if (pendingSnapshotItems == null)
			{
				// Already consumed by a concurrent scheduled-task
				// firing or another flush — nothing left to do.
				return;
			}
			items = pendingSnapshotItems;
			accountHash = pendingSnapshotAccountHash;
			pendingSnapshotItems = null;
			// Install the
			// in-flight future ATOMICALLY with consuming the pending
			// fields (same lock, same block) — see
			// inFlightBankFinalization's javadoc for why this exact
			// placement is what closes the shutdown race.
			future = new CompletableFuture<>();
			inFlightBankFinalization = future;
		}
		takeImmutableBankSnapshot(accountHash, items, future);
	}

	/**
	 * Called from the plugin's
	 * shutDown() specifically, BEFORE store.shutdown()/eventLedger.shutdown()
	 * — blocks until the snapshot -> pointer -> event chain has
	 * actually completed (or failed and stopped early), rather than
	 * just being enqueued. See finalizeBankSnapshotBlocking() javadoc
	 * for why this needs a different (blocking) implementation than
	 * the async flushPending() used everywhere else.
	 */
	public void flushPendingAndWait()
	{
		long accountHash;
		List<StorageState.StorageItem> items;
		CompletableFuture<Boolean> future = null;
		CompletableFuture<Boolean> inFlight = null;
		synchronized (pendingLock)
		{
			ScheduledFuture<?> pending = pendingBankSnapshot;
			if (pending != null)
			{
				pending.cancel(false);
				pendingBankSnapshot = null;
			}
			if (pendingSnapshotItems != null)
			{
				items = pendingSnapshotItems;
				accountHash = pendingSnapshotAccountHash;
				pendingSnapshotItems = null;
				// This call itself
				// becomes the one accepted finalization for this
				// observation. Installing the future HERE, inside the
				// SAME synchronized block that consumes
				// pendingSnapshotItems, is required now that
				// flushPendingAndWait() has a second real caller
				// (onClientShutdown()'s async task, alongside Plugin.
				// shutDown()) that can run concurrently with this one —
				// the previous version consumed pendingSnapshotItems and
				// installed inFlightBankFinalization in two SEPARATE
				// synchronized blocks, leaving a window where a second
				// concurrent flushPendingAndWait() call could acquire
				// the lock in between, see pendingSnapshotItems already
				// null, and read a STALE (previous, already-completed)
				// inFlightBankFinalization instead of this call's fresh
				// one — missing the actual in-flight work entirely.
				// Merging both into one critical section closes that
				// window, matching the exact discipline flushPending()/
				// runScheduledBankFinalize() already use.
				future = new CompletableFuture<>();
				inFlightBankFinalization = future;
			}
			else
			{
				items = null;
				accountHash = 0L;
				// The pending* fields being empty does NOT
				// mean nothing is happening. A concurrent WidgetClosed
				// -> flushPending() or the scheduled debounce ->
				// runScheduledBankFinalize() may have ALREADY consumed
				// pendingSnapshotItems (accepting it for finalization)
				// and be running its async snapshot -> pointer -> event
				// chain right now, entirely outside these pending*
				// fields. inFlightBankFinalization is written under
				// this SAME lock, atomically with that consumption (see
				// flushPending()/runScheduledBankFinalize()), so reading
				// it here — still inside this synchronized block — can
				// never race a concurrent consumer into missing it: by
				// the time pendingSnapshotItems is visibly null here,
				// inFlightBankFinalization has already been updated to
				// reflect whatever finalization made it null.
				inFlight = inFlightBankFinalization;
			}
		}

		if (items != null)
		{
			boolean success = finalizeBankSnapshotBlocking(accountHash, items);
			future.complete(success);
			return;
		}

		if (inFlight == null)
		{
			return;
		}

		// Wait for the
		// ALREADY in-flight finalization to reach a terminal state
		// instead of returning because there is nothing left in the
		// pending* fields. Bounded — never hangs shutdown forever, and
		// never starts a second finalization for the same observation
		// (see IN_FLIGHT_FINALIZATION_WAIT_MS/inFlightBankFinalization
		// javadoc for the documented limitation on the snapshot-write-
		// failure shape specifically).
		try
		{
			inFlight.get(IN_FLIGHT_FINALIZATION_WAIT_MS, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			log.warn("Interrupted waiting for an already-in-flight bank finalization during shutdown");
		}
		catch (ExecutionException | TimeoutException e)
		{
			log.warn("Timed out or failed waiting for an already-in-flight bank finalization during shutdown", e);
		}
	}

	/**
	 * Force-finalizes the pending
	 * snapshot the moment the bank interface closes, instead of always
	 * waiting out the debounce window even after the player is done.
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == BANK_WIDGET_GROUP_ID)
		{
			flushPending();
		}
	}

	/**
	 * A normal RuneLite window close does NOT reliably invoke this
	 * plugin's shutDown() at all. shutDown() only runs through
	 * PluginManager.stopPlugin(), which fires when a plugin is
	 * explicitly stopped/disabled -- a distinct lifecycle from the
	 * client application exiting. RuneLite's actual, documented hook
	 * for "must complete before process exit" on a normal close is
	 * net.runelite.client.events.ClientShutdown, posted on the same
	 * EventBus this collector is already registered on (see
	 * OsrsTelemetryPlugin.startUp()'s eventBus.register(containerCollector)
	 * -- unregistered only on an explicit plugin disable, never on a
	 * normal application exit, so this subscriber is reliably present
	 * to receive ClientShutdown). ClientShutdown.waitFor(Future<?>) is
	 * exactly the mechanism RuneLite exposes for a component to make
	 * the shutdown sequence actually wait for it before
	 * client.stopNow()/System.exit(0). (ClientUI.shutdownClient() bounds
	 * that wait with its own waitForAllConsumers(Duration.ofSeconds(10)).)
	 *
	 * EXECUTOR CHOICE: runs flushPendingAndWait() via
	 * the ALREADY-INJECTED scheduledExecutor (RuneLite's own shared
	 * scheduled pool, already used for the debounce timer), never on
	 * LocalStateStore's or EventLedger's own dedicated single-writer
	 * executors. flushPendingAndWait() -> finalizeBankSnapshotBlocking()
	 * blocks on LocalStateStore.writeAndWait()/EventLedger.appendAndWait()
	 * calls that are themselves submitted TO those single-threaded
	 * executors; running flushPendingAndWait() ON one of those same
	 * executors would deadlock it against itself (the one worker
	 * thread would be blocked waiting on a task it must itself dequeue
	 * and run next). scheduledExecutor is a separate pool from both,
	 * so no such cycle exists.
	 *
	 * REUSE, NOT A PARALLEL SYSTEM: calls the exact
	 * same flushPendingAndWait() Plugin.shutDown() already calls --
	 * same pendingLock/inFlightBankFinalization state -- so whichever
	 * of ClientShutdown or an explicit plugin disable actually runs
	 * (or, in principle, both) can only ever produce one finalization
	 * for one accepted pending bank observation; see
	 * flushPendingAndWait()'s own javadoc for the atomicity fix that
	 * makes that hold even if both are ever invoked concurrently.
	 *
	 * DELIBERATELY DOES NOT touch store/eventLedger lifecycle: no
	 * store.shutdown()/eventLedger.shutdown() call
	 * here. Both writer threads are daemon threads (see their own
	 * start() javadoc) -- the JVM exiting behind ClientShutdown's wait
	 * terminates them either way, and by the time our future completes
	 * their queued work for THIS finalization is already durably done.
	 * Calling shutdown() on either here would risk tearing down a
	 * shared executor out from under any other still-in-flight,
	 * unrelated write this plugin's other collectors may have queued
	 * at the exact same moment -- not this method's concern to police.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		event.waitFor(beginClientShutdownFinalization());
	}

	/**
	 * Package-private (not private) so tests can exercise exactly the
	 * async submission onClientShutdown() performs -- same
	 * scheduledExecutor, same flushPendingAndWait() call -- without
	 * needing to construct RuneLite's ClientShutdown class directly.
	 * Production and tests share this
	 * exact method; it is not a test-only approximation of the real
	 * behavior.
	 */
	CompletableFuture<Void> beginClientShutdownFinalization()
	{
		return CompletableFuture.runAsync(this::flushPendingAndWait, scheduledExecutor);
	}

	/**
	 * Called once on login/plugin enable (see plugin's
	 * captureInitialState()) to seed inventory/equipment immediately
	 * rather than waiting for the next incidental ItemContainerChanged
	 * — those are continuously-observable containers, so there is no
	 * reason telemetry should lag behind what RuneLite already knows
	 * the instant the plugin starts observing. Gated on config — called
	 * both on login and on re-enable via ConfigChanged, and must not
	 * seed inventory/equipment state at all while the category is
	 * disabled.
	 */
	public void captureInitialContinuousState()
	{
		if (!config.collectInventoryEquipment())
		{
			return;
		}
		captureIfPresent(InventoryID.INV);
		captureIfPresent(InventoryID.WORN);
	}

	private void captureIfPresent(int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container != null)
		{
			writeContainer(containerId, container);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();
		if (!STORAGE_IDS.containsKey(containerId))
		{
			return;
		}
		if (!categoryEnabled(containerId))
		{
			return;
		}
		writeContainer(containerId, event.getItemContainer());
	}

	private boolean categoryEnabled(int containerId)
	{
		if (containerId == InventoryID.INV || containerId == InventoryID.WORN)
		{
			return config.collectInventoryEquipment();
		}
		if (containerId == InventoryID.BANK)
		{
			return config.collectBank();
		}
		if (containerId == InventoryID.SEED_VAULT)
		{
			return config.collectSeedVault();
		}
		if (containerId == InventoryID.INV_GROUP_TEMP)
		{
			return config.collectGroupStorage();
		}
		return true;
	}

	private void writeContainer(int containerId, ItemContainer container)
	{
		String storageId = STORAGE_IDS.get(containerId);
		boolean isEquipment = containerId == InventoryID.WORN;
		boolean isBank = containerId == InventoryID.BANK;
		boolean continuous = Boolean.TRUE.equals(CONTINUOUS.get(containerId));

		List<StorageState.StorageItem> items;
		if (isEquipment)
		{
			items = buildEquipmentItems(container);
		}
		else if (isBank)
		{
			// Bank PLACEHOLDER slots must never be recorded as owned
			// items. Deliberately NOT buildSparseItems() -- see
			// buildBankItems()/buildBankItemsCore() javadoc for why
			// this is bank-only.
			items = buildBankItems(container);
		}
		else
		{
			items = buildSparseItems(container);
		}

		StorageState state = new StorageState(storageId);
		state.setItems(items);
		state.setLastObservedAt(Instant.now().toString());
		state.setContinuouslyObservable(continuous);

		if ("bank".equals(storageId))
		{
			long accountHash = client.getAccountHash();

			// Stamps the currently-known latestSnapshotId
			// (from the in-memory baseline, lazily seeded from disk on
			// first need this account-session) onto this immediate
			// write, so it never regresses to "field absent" just
			// because this particular observation isn't the one that
			// happens to finalize an immutable snapshot. See class
			// javadoc's LATEST-SNAPSHOT-ID INVARIANT section.
			ensureBankBaselineLoaded(accountHash);
			if (bankBaseline != null && bankBaseline.isPresent())
			{
				state.setLatestSnapshotId(bankBaseline.getSnapshotId());
			}

			// The debounced immutable-snapshot path (below) updates
			// bank.json a second time once it settles, adding/
			// refreshing latestSnapshotId — this immediate write is
			// what keeps lastObservedAt/items current in the meantime.
			scheduleBankSnapshot(accountHash, items);
		}
		else if ("seed_vault".equals(storageId))
		{
			// Unlike Bank, Seed
			// Vault gets no debounce/immutable-snapshot machinery —
			// that exists for Bank specifically for its
			// historical-snapshot fallback path, which was never
			// requested for Seed Vault. This fires directly off the
			// same ItemContainerChanged delivery that just produced
			// `items` above — see EventType.SEED_VAULT_SNAPSHOT's
			// javadoc for why that's a sufficient "safe to emit"
			// signal (same guarantee Bank/Group Storage already rely
			// on for this exact container-read pattern).
			eventLedger.append(
				client.getAccountHash(),
				EventType.SEED_VAULT_SNAPSHOT,
				buildSeedVaultSnapshotPayload(storageId, items.size())
			);
		}

		store.write(TelemetryPaths.stateFile(client.getAccountHash(), storageId), state);
	}

	/**
	 * Pure, Client/EventLedger-independent core of the
	 * SEED_VAULT_SNAPSHOT payload — factored out purely so
	 * ContainerCollectorTest can exercise it without mocking Client/
	 * ItemContainer (this project has no Mockito dependency), same
	 * pattern as LootCollector.buildPayload()/NpcDeathCollector.buildPayload().
	 * Pass-through only: itemCount is whatever the caller already
	 * computed from the real observed container, never fabricated or
	 * defaulted here.
	 */
	static EventPayloads.SeedVaultSnapshot buildSeedVaultSnapshotPayload(String storageId, int itemCount)
	{
		return new EventPayloads.SeedVaultSnapshot(storageId, itemCount);
	}

	private List<StorageState.StorageItem> buildEquipmentItems(ItemContainer container)
	{
		List<StorageState.StorageItem> items = new ArrayList<>(EQUIPMENT_SLOT_NAMES.size());
		Item[] rawItems = container.getItems();
		for (int slot = 0; slot < EQUIPMENT_SLOT_NAMES.size(); slot++)
		{
			Item item = slot < rawItems.length ? rawItems[slot] : null;
			String equipmentSlotName = EQUIPMENT_SLOT_NAMES.get(slot);
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				// Explicit empty-slot entry, not an omission — see
				// class/model javadoc: for a fully continuously
				// observable container, "nothing in this slot" is
				// itself a fact worth stating, not something a
				// consumer should have to infer from absence.
				items.add(new StorageState.StorageItem(slot, -1, null, 0, equipmentSlotName));
			}
			else
			{
				String name = itemManager.getItemComposition(item.getId()).getName();
				items.add(new StorageState.StorageItem(slot, item.getId(), name, item.getQuantity(), equipmentSlotName));
			}
		}
		return items;
	}

	private List<StorageState.StorageItem> buildSparseItems(ItemContainer container)
	{
		List<StorageState.StorageItem> items = new ArrayList<>();
		Item[] rawItems = container.getItems();
		for (int slot = 0; slot < rawItems.length; slot++)
		{
			Item item = rawItems[slot];
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			String name = itemManager.getItemComposition(item.getId()).getName();
			items.add(new StorageState.StorageItem(slot, item.getId(), name, item.getQuantity(), null));
		}
		return items;
	}

	/**
	 * A real
	 * RuneLite run showed that after withdrawing the LAST copy of an
	 * item while leaving a bank placeholder behind, the bank
	 * ItemContainer slot did NOT settle at quantity 0 (which
	 * buildSparseItems()'s existing `item.getQuantity() <= 0` filter
	 * would already have excluded). Instead RuneLite left the slot
	 * occupied by a DIFFERENT, placeholder-form itemId (e.g. real
	 * "Grubby key" = 23499 -> placeholder "Grubby key" = 23501)
	 * reporting quantity 1 -- indistinguishable from a genuinely owned
	 * item by id/quantity alone. The same shape was independently
	 * observed with Onion seed (an alternate/placeholder item id versus
	 * the real owned item id).
	 *
	 * AUTHORITATIVE MECHANISM: RuneLite's own item
	 * metadata is used, never a hand-maintained id list.
	 * ItemComposition.getPlaceholderTemplateId() returns the
	 * placeholder-template composition id when -- and only when -- the
	 * itemId being queried IS ITSELF a placeholder-form item; it
	 * returns -1 for every ordinary, really-owned item id. This is the
	 * same mechanism RuneLite's own bank-related plugins (e.g. bank tag
	 * layouts) rely on to recognize a bank placeholder, and unlike the
	 * widget-group-id constants elsewhere in this file it has been
	 * stable across RuneLite/OSRS item-definition changes for years.
	 *
	 * Deliberately BANK-ONLY: buildSparseItems()
	 * (inventory / seed vault / group storage) and buildEquipmentItems()
	 * are untouched by this fix. A placeholder-form itemId is a bank
	 * container concept; filtering it out of every other container's
	 * item list would be an unrequested, unjustified behavior change
	 * there.
	 */
	private List<StorageState.StorageItem> buildBankItems(ItemContainer container)
	{
		return buildBankItemsCore(
			container.getItems(),
			itemId -> itemManager.getItemComposition(itemId).getPlaceholderTemplateId() != -1,
			itemId -> itemManager.getItemComposition(itemId).getName());
	}

	/**
	 * Pure core of buildBankItems() -- factored out (same pattern as
	 * buildSeedVaultSnapshotPayload()) purely so ContainerCollectorTest
	 * can exercise the real filtering/slot logic with plain
	 * java.util.function fakes instead of needing to mock ItemManager/
	 * ItemComposition (this project has no Mockito dependency).
	 * Production and tests share this exact method -- it is not a
	 * test-only approximation of the real filtering behavior.
	 *
	 * The bank-placeholder definition is applied
	 * exactly here: a placeholder slot is EXCLUDED entirely (never
	 * emitted as a zero-quantity or otherwise-marked entry) -- it must
	 * not appear in canonical owned-item lists at all. This is also
	 * what satisfies dedup baseline normalization with
	 * no separate normalization step: a real-item -> placeholder
	 * transition simply drops the item out of this list, exactly like
	 * withdrawing it from a non-placeholder slot would, and every
	 * downstream consumer of `items` -- bank.json, the immutable
	 * snapshot, and BankSnapshotBaseline.shouldWriteNewSnapshot()'s
	 * plain List.equals() -- all read this exact already-normalized
	 * list.
	 */
	static List<StorageState.StorageItem> buildBankItemsCore(
		Item[] rawItems,
		IntPredicate isPlaceholder,
		IntFunction<String> nameLookup)
	{
		List<StorageState.StorageItem> items = new ArrayList<>();
		for (int slot = 0; slot < rawItems.length; slot++)
		{
			Item item = rawItems[slot];
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			if (isPlaceholder.test(item.getId()))
			{
				// The player owns quantity ZERO of the corresponding
				// real item here, whatever RuneLite's raw
				// Item.getQuantity() happens to report for the
				// placeholder-form itemId currently occupying this slot
				// -- never recorded as an owned bank item.
				continue;
			}
			items.add(new StorageState.StorageItem(slot, item.getId(), nameLookup.apply(item.getId()), item.getQuantity(), null));
		}
		return items;
	}

	/** Package-private for testability — see takeImmutableBankSnapshot(). */
	void scheduleBankSnapshot(long accountHash, List<StorageState.StorageItem> items)
	{
		synchronized (pendingLock)
		{
			ScheduledFuture<?> previous = pendingBankSnapshot;
			if (previous != null)
			{
				previous.cancel(false);
			}
			pendingSnapshotAccountHash = accountHash;
			pendingSnapshotItems = items;
			// The scheduled task does not close over
			// accountHash/items directly — it re-reads (and clears) the
			// pending* fields itself, under pendingLock, via
			// runScheduledBankFinalize(). That's what makes it a no-op
			// when a concurrent flushPending()/flushPendingAndWait()
			// already consumed this exact pending observation, instead
			// of both paths independently finalizing the same snapshot.
			pendingBankSnapshot = scheduledExecutor.schedule(
				this::runScheduledBankFinalize,
				BANK_SNAPSHOT_DEBOUNCE_MS, TimeUnit.MILLISECONDS
			);
		}
	}

	private void runScheduledBankFinalize()
	{
		long accountHash;
		List<StorageState.StorageItem> items;
		CompletableFuture<Boolean> future;
		synchronized (pendingLock)
		{
			if (pendingSnapshotItems == null)
			{
				// Already consumed by a concurrent flush — nothing to do.
				return;
			}
			items = pendingSnapshotItems;
			accountHash = pendingSnapshotAccountHash;
			pendingSnapshotItems = null;
			pendingBankSnapshot = null;
			// See flushPending()'s identical addition and
			// inFlightBankFinalization's javadoc.
			future = new CompletableFuture<>();
			inFlightBankFinalization = future;
		}
		takeImmutableBankSnapshot(accountHash, items, future);
	}

	/** Package-private (not private) purely so ContainerCollectorTest can
	 * exercise the bank-snapshot decision/write logic directly without
	 * a mocked Client — see class javadoc's LATEST-SNAPSHOT-ID INVARIANT
	 * section and ContainerCollectorTest for why that's safe (this method and
	 * everything it calls never touches client/itemManager/config).
	 * Convenience overload for callers (tests, and any future call
	 * site) that don't need to track this specific finalization's
	 * in-flight/terminal state themselves. */
	void takeImmutableBankSnapshot(long accountHash, List<StorageState.StorageItem> items)
	{
		takeImmutableBankSnapshot(accountHash, items, new CompletableFuture<>());
	}

	/**
	 * Takes the
	 * CompletableFuture the caller already installed as
	 * inFlightBankFinalization (atomically, at the moment it consumed
	 * pendingSnapshotItems — see flushPending()/runScheduledBankFinalize())
	 * and completes it at every terminal exit point of this method, so
	 * flushPendingAndWait() can find and wait on this exact
	 * finalization if shutdown lands while it's still running. See
	 * inFlightBankFinalization's javadoc for the one documented gap
	 * (a silent async snapshot-write failure never completes this
	 * future — bounded by the waiter's own timeout instead).
	 *
	 * The tail of the success path calls
	 * eventLedger.appendAndWait() rather than a fire-and-forget
	 * append(). This runs on LocalStateStore's own dedicated writer
	 * thread (the pointer write's onWritten callback), never on the
	 * client thread and never on RuneLite's shared scheduledExecutor
	 * (which only ever runs the initial, near-instantaneous call into
	 * this method before the debounce delay — by the time this nested
	 * callback runs, that thread is long since freed). Blocking that
	 * private writer thread for up to DURABLE_WRITE_TIMEOUT_MS is an
	 * internal cost to this plugin alone, never shared with RuneLite
	 * or any other plugin. This is what makes the earlier
	 * "just needs to be submitted before eventLedger.shutdown()" claim
	 * unnecessary for this call site: the event is confirmed durable
	 * (or definitively failed) before takeImmutableBankSnapshot()'s own
	 * chain considers itself finished, regardless of whether
	 * eventLedger.shutdown()'s graceful drain ever gets a chance to run
	 * at all.
	 */
	private void takeImmutableBankSnapshot(long accountHash, List<StorageState.StorageItem> items, CompletableFuture<Boolean> finalizationFuture)
	{
		Instant now = Instant.now();
		if (!shouldWriteNewBankSnapshot(accountHash, items, now))
		{
			// A skipped finalization must
			// still refresh bank.json's items/lastObservedAt while
			// explicitly preserving the previously successful
			// snapshotId — never leave the pointer clobbered by
			// whatever Path A last wrote (already correct as of the
			// LATEST-SNAPSHOT-ID fix above, but this is the authoritative
			// place for the refresh, and it's cheap/idempotent to do it
			// here too).
			refreshBankPointerOnly(accountHash, items, now, false);
			// No new snapshot/pointer/event chain was started — nothing
			// further for a racing shutdown to wait on.
			finalizationFuture.complete(true);
			return;
		}

		String nowIso = now.toString();
		String snapshotId = UUID.randomUUID().toString();
		// Captured
		// now, checked again once the async write actually lands — see
		// accountEpoch's javadoc. This account's own on-disk files below
		// are written unconditionally either way; only the shared
		// in-memory bankBaseline write is skipped if stale.
		long epochAtStart = accountEpoch;

		StorageState snapshot = new StorageState("bank");
		snapshot.setItems(items);
		snapshot.setLastObservedAt(nowIso);
		snapshot.setContinuouslyObservable(false);
		snapshot.setLatestSnapshotId(snapshotId);
		File snapshotFile = TelemetryPaths.bankSnapshotFile(accountHash, snapshotId);

		// ORDERING: nested
		// onWritten callbacks, each of which only fires on confirmed
		// success (see LocalStateStore's own javadoc) — the pointer
		// write only happens after the snapshot durably succeeds, and
		// the event only appends after the POINTER write also durably
		// succeeds. A BANK_SNAPSHOT event can no longer reference a
		// file that failed to persist at either step.
		//
		// DEDUP INVARIANT: the in-memory baseline is updated HERE,
		// inside the snapshot's own onWritten (success-only) callback
		// — never before this point. A failed snapshot write must
		// never let a later identical observation be wrongly
		// suppressed, and this placement guarantees that structurally:
		// if writeNow() fails, onWritten never runs, so
		// recordBankBaseline() never runs either.
		//
		// KNOWN LIMITATION: if this snapshot write fails, onWritten
		// never runs, so finalizationFuture is never completed here —
		// see inFlightBankFinalization's javadoc for why that's still
		// safe (bounded wait on the reader side) rather than silently
		// papered over.
		store.write(snapshotFile, snapshot, () ->
		{
			if (epochAtStart == accountEpoch)
			{
				recordBankBaseline(items, now, snapshotId);
			}

			// Only ever runs after the snapshot write above is
			// confirmed successful (we're inside its onWritten
			// callback), protects the snapshot we just wrote, and is
			// itself fail-safe/non-fatal — see BankSnapshotRetention
			// javadoc.
			BankSnapshotRetention.pruneIfNeeded(
				TelemetryPaths.bankSnapshotsDir(accountHash),
				snapshotId,
				BankSnapshotRetention.DEFAULT_MAX_BYTES,
				BankSnapshotRetention.DEFAULT_TARGET_BYTES);

			StorageState pointer = new StorageState("bank");
			pointer.setItems(items);
			pointer.setLastObservedAt(nowIso);
			pointer.setContinuouslyObservable(false);
			pointer.setLatestSnapshotId(snapshotId);

			store.write(TelemetryPaths.stateFile(accountHash, "bank"), pointer, () ->
			{
				// Was
				// eventLedger.append() (fire-and-forget) — see method
				// javadoc for why appendAndWait() here, on this
				// dedicated background thread, is required for
				// shutdown durability.
				boolean appended = eventLedger.appendAndWait(
					accountHash,
					EventType.BANK_SNAPSHOT,
					new EventPayloads.BankSnapshot("bank", snapshotId, items.size()),
					DURABLE_WRITE_TIMEOUT_MS
				);
				if (!appended)
				{
					log.warn("BANK_SNAPSHOT event failed to durably append for account {} snapshot {}", accountHash, snapshotId);
				}
				finalizationFuture.complete(appended);
			});
		});
	}

	/**
	 * Blocking counterpart used ONLY from the plugin's shutDown(),
	 * before store.shutdown()/eventLedger.shutdown() run. The async path above is correct for
	 * every other case (WidgetClosed, account switch) because the
	 * client keeps running afterward and there's no risk of the
	 * executors being torn down mid-chain. At shutdown specifically,
	 * that risk is real: LocalStateStore.write()'s async callback
	 * chain could still be queued when store.shutdown() runs. This
	 * method performs the same snapshot -> pointer -> event chain but
	 * blocks the calling thread (the plugin's own shutDown(), not the
	 * client thread — acceptable here for the same reason blocking is
	 * fine in any other shutdown hook) on each step via
	 * LocalStateStore.writeAndWait(), so by the time this method
	 * returns, shutdown can safely proceed.
	 *
	 * Returns boolean rather than treating a merely-SUBMITTED
	 * eventLedger.append() as sufficient: the plugin's shutDown() calls
	 * this method, then store.shutdown(), then eventLedger.shutdown();
	 * if eventLedger.shutdown()'s graceful drain window does not get to
	 * run the queued append, a fully-written immutable snapshot and a
	 * fully-updated bank.json pointer could be left on disk with NO
	 * corresponding BANK_SNAPSHOT event. Calling
	 * eventLedger.appendAndWait() removes the dependency on that drain
	 * window entirely for this call site: by the time this method
	 * returns, the event has either reached disk or definitively
	 * failed/timed out -- there is no third, "maybe" outcome.
	 *
	 * @return true if this observation reached a fully durable
	 * terminal state (either the dedup/refresh-only outcome durably
	 * completed, or a brand-new snapshot + pointer + BANK_SNAPSHOT
	 * event all durably completed in that order); false if any step
	 * failed or timed out, in which case no later step in the chain
	 * was attempted (a failed snapshot
	 * write means no pointer/event, a failed pointer write means no
	 * event). Package-private for testability — see takeImmutableBankSnapshot().
	 */
	boolean finalizeBankSnapshotBlocking(long accountHash, List<StorageState.StorageItem> items)
	{
		Instant now = Instant.now();
		if (!shouldWriteNewBankSnapshot(accountHash, items, now))
		{
			// Unlike the async skip path,
			// this one MUST be durable before this method returns —
			// shutDown() proceeds to store.shutdown() right after this
			// call, and a merely-queued async write racing that drain
			// window is exactly the "lost on exit" failure mode this
			// avoids. writeAndWait() blocks until the pointer
			// refresh has actually reached disk (or definitively
			// failed) before we return.
			return refreshBankPointerOnly(accountHash, items, now, true);
		}

		String nowIso = now.toString();
		String snapshotId = UUID.randomUUID().toString();

		StorageState snapshot = new StorageState("bank");
		snapshot.setItems(items);
		snapshot.setLastObservedAt(nowIso);
		snapshot.setContinuouslyObservable(false);
		snapshot.setLatestSnapshotId(snapshotId);
		File snapshotFile = TelemetryPaths.bankSnapshotFile(accountHash, snapshotId);

		if (!store.writeAndWait(snapshotFile, snapshot, DURABLE_WRITE_TIMEOUT_MS))
		{
			// INVARIANT: failed snapshot write => no pointer, no event.
			return false;
		}
		// Same dedup invariant as the async path above: only recorded
		// after writeAndWait() confirmed success.
		recordBankBaseline(items, now, snapshotId);

		// Same retention call as the async path — cheap
		// (directory listing + a handful of deletes at most), safe to
		// run synchronously on the shutdown thread here.
		BankSnapshotRetention.pruneIfNeeded(
			TelemetryPaths.bankSnapshotsDir(accountHash),
			snapshotId,
			BankSnapshotRetention.DEFAULT_MAX_BYTES,
			BankSnapshotRetention.DEFAULT_TARGET_BYTES);

		StorageState pointer = new StorageState("bank");
		pointer.setItems(items);
		pointer.setLastObservedAt(nowIso);
		pointer.setContinuouslyObservable(false);
		pointer.setLatestSnapshotId(snapshotId);

		if (!store.writeAndWait(TelemetryPaths.stateFile(accountHash, "bank"), pointer, DURABLE_WRITE_TIMEOUT_MS))
		{
			// INVARIANT: failed pointer write => no event.
			return false;
		}

		// appendAndWait() rather than fire-and-forget append(), since a
		// merely-submitted event with no confirmation that
		// eventLedger.shutdown()'s graceful drain actually ran it is not
		// good enough here. appendAndWait() blocks this thread
		// (already off the client thread — this method only ever runs
		// from the plugin's own shutDown()) until the event is
		// confirmed durable or definitively fails/times out, so this
		// method never reports success without it.
		boolean appended = eventLedger.appendAndWait(
			accountHash,
			EventType.BANK_SNAPSHOT,
			new EventPayloads.BankSnapshot("bank", snapshotId, items.size()),
			DURABLE_WRITE_TIMEOUT_MS
		);
		if (!appended)
		{
			log.warn("BANK_SNAPSHOT event failed to durably append during shutdown for account {} snapshot {}", accountHash, snapshotId);
		}
		return appended;
	}

	/**
	 * Writes a bank.json pointer that
	 * carries current candidate items + a fresh lastObservedAt, but
	 * deliberately does NOT create an immutable snapshot, a new UUID,
	 * or a BANK_SNAPSHOT event, and does NOT touch the in-memory
	 * baseline (bankBaseline stays exactly as it was — see
	 * BankSnapshotBaseline's javadoc on why createdAt must only ever
	 * advance from a real new snapshot). latestSnapshotId is stamped
	 * from the CURRENT in-memory baseline (the previously successful
	 * snapshot, if any) so the pointer keeps referencing the same
	 * immutable file dedup is suppressing a duplicate of.
	 *
	 * blocking=true uses LocalStateStore.writeAndWait() (see
	 * finalizeBankSnapshotBlocking()'s javadoc for why the shutdown
	 * path specifically needs this rather than the fire-and-forget
	 * write() every other call site uses). blocking=true (the shutdown
	 * path) returns writeAndWait()'s own success/failure; blocking=false
	 * (the ordinary async path) returns true once the write is submitted
	 * -- that path was never durability-critical to begin with (it
	 * never produces a BANK_SNAPSHOT event), so "submitted" remains an
	 * accurate, honest answer for it.
	 *
	 * Package-private for testability — see takeImmutableBankSnapshot().
	 */
	boolean refreshBankPointerOnly(long accountHash, List<StorageState.StorageItem> items, Instant now, boolean blocking)
	{
		String snapshotId = (bankBaseline != null && bankBaseline.isPresent()) ? bankBaseline.getSnapshotId() : null;

		StorageState pointer = new StorageState("bank");
		pointer.setItems(items);
		pointer.setLastObservedAt(now.toString());
		pointer.setContinuouslyObservable(false);
		pointer.setLatestSnapshotId(snapshotId);

		File target = TelemetryPaths.stateFile(accountHash, "bank");
		if (blocking)
		{
			return store.writeAndWait(target, pointer, DURABLE_WRITE_TIMEOUT_MS);
		}
		store.write(target, pointer);
		return true;
	}

	/**
	 * Lazily seeds bankBaseline from persisted state on first need
	 * each account-session (see BankSnapshotBaseline.loadFrom()'s
	 * fail-open guarantee). Idempotent — a second call within the same
	 * account-session is a no-op, since bankBaseline is only ever
	 * cleared by resetForAccountSwitch().
	 *
	 * NOTE ON THREADING: this can run on the client thread — the two
	 * paths where that happens are (a) the very first bank
	 * ItemContainerChanged of a session (writeContainer() now calls
	 * this directly, PHASE 1 fix) and (b) the first-ever bank
	 * finalization of a session landing via WidgetClosed ->
	 * flushPending() (a fast bank-close within the 3s debounce). Every
	 * other path either already runs on a background thread or hits
	 * the already-loaded in-memory baseline with no I/O at all. This is
	 * a small, one-time, bounded cost consistent with what
	 * SlayerCollector/PotionStorageCollector already do synchronously
	 * on the client thread elsewhere in this codebase — not offloaded
	 * further, to keep this change the smallest safe version.
	 */
	/** Package-private for testability — see takeImmutableBankSnapshot(). */
	void ensureBankBaselineLoaded(long accountHash)
	{
		if (bankBaseline == null)
		{
			bankBaseline = BankSnapshotBaseline.loadFrom(store, accountHash);
		}
	}

	private boolean shouldWriteNewBankSnapshot(long accountHash, List<StorageState.StorageItem> items, Instant now)
	{
		ensureBankBaselineLoaded(accountHash);
		return BankSnapshotBaseline.shouldWriteNewSnapshot(bankBaseline, items, now, BANK_SNAPSHOT_MAX_AGE);
	}

	private void recordBankBaseline(List<StorageState.StorageItem> items, Instant createdAt, String snapshotId)
	{
		bankBaseline = BankSnapshotBaseline.of(items, createdAt, snapshotId);
	}

	/** Package-private test hook: current in-memory baseline, or null if
	 * never loaded this account-session. */
	BankSnapshotBaseline currentBankBaselineForTest()
	{
		return bankBaseline;
	}

	/** Package-private test hook: force-seed the in-memory baseline
	 * directly, bypassing disk I/O, for tests that need a known
	 * starting baseline without writing fixture files first. */
	void setBankBaselineForTest(BankSnapshotBaseline baseline)
	{
		this.bankBaseline = baseline;
	}

	/** Package-private test hook (bank-snapshot shutdown-durability
	 * pass): invokes exactly the scheduled-debounce consumption logic
	 * (runScheduledBankFinalize()) without waiting out the real
	 * BANK_SNAPSHOT_DEBOUNCE_MS delay — lets tests simulate "the
	 * debounce timer just fired" deterministically and race it against
	 * flushPendingAndWait() on another thread. */
	void runScheduledBankFinalizeForTest()
	{
		runScheduledBankFinalize();
	}

	/** Package-private test hook: current in-flight bank finalization
	 * future, or an already-completed one if nothing is in flight —
	 * lets tests assert on completion without needing to know internal
	 * timing (see inFlightBankFinalization's javadoc). */
	CompletableFuture<Boolean> inFlightBankFinalizationForTest()
	{
		return inFlightBankFinalization;
	}
}
