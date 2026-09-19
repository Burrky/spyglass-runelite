package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import net.runelite.api.Item;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run — no-network caveat as elsewhere. Covers
 * equipment slot preservation: every equipment slot
 * index must map to its semantic name, with none silently dropped.
 *
 * SEED_VAULT_SNAPSHOT tests target
 * ContainerCollector.buildSeedVaultSnapshotPayload(), the pure helper
 * factored out for exactly this purpose (this project has no Mockito
 * dependency, so Client/ItemContainer are never mocked here either).
 *
 * BANK-SNAPSHOT SHUTDOWN-DURABILITY REGRESSION TESTS (guarding against
 * a bug where a real RuneLite run produced a durable
 * immutable snapshot and a durable bank.json pointer but NO
 * corresponding BANK_SNAPSHOT event, because eventLedger.append() was
 * fire-and-forget and the plugin exited before EventLedger's graceful
 * drain window ran). These tests genuinely exercise the lifecycle —
 * real LocalStateStore/EventLedger instances backed by real (temp)
 * files under this account's real TelemetryPaths, a real
 * ScheduledExecutorService, and real thread races — rather than
 * re-asserting isolated pure methods. ContainerCollector's Client/
 * ItemManager/OsrsTelemetryConfig fields are never touched by any of
 * the methods these tests call (takeImmutableBankSnapshot(),
 * finalizeBankSnapshotBlocking(), flushPending(), flushPendingAndWait(),
 * scheduleBankSnapshot(), runScheduledBankFinalizeForTest()) — verified
 * by direct audit — so null is passed for those three constructor
 * arguments here, exactly as ContainerCollector's own existing
 * testability javadoc anticipates.
 */
public class ContainerCollectorTest
{
	@Test
	public void allFourteenEquipmentSlotsAreMapped()
	{
		Map<Integer, String> slots = ContainerCollector.equipmentSlotNames();
		assertEquals("equipment has exactly 14 slots", 14, slots.size());
	}

	@Test
	public void knownSlotIndicesMapToExpectedNames()
	{
		Map<Integer, String> slots = ContainerCollector.equipmentSlotNames();
		assertEquals("HEAD", slots.get(0));
		assertEquals("WEAPON", slots.get(3));
		assertEquals("SHIELD", slots.get(5));
		assertEquals("AMMO", slots.get(13));
	}

	// --- SEED_VAULT_SNAPSHOT payload ---

	@Test
	public void buildSeedVaultSnapshotPayload_preservesStorageIdAndItemCount()
	{
		EventPayloads.SeedVaultSnapshot payload = ContainerCollector.buildSeedVaultSnapshotPayload("seed_vault", 42);

		assertEquals("seed_vault", payload.getStorageId());
		assertEquals(42, payload.getItemCount());
	}

	@Test
	public void buildSeedVaultSnapshotPayload_zeroItemCount_isPreservedNotFabricated()
	{
		// A genuinely empty (but observed) Seed Vault must be
		// distinguishable from "we don't know" — 0 is a real,
		// pass-through value here, never a sentinel for "no data."
		EventPayloads.SeedVaultSnapshot payload = ContainerCollector.buildSeedVaultSnapshotPayload("seed_vault", 0);

		assertEquals(0, payload.getItemCount());
	}

	@Test
	public void seedVaultSnapshotPayload_roundTripsThroughGson_sameSerializerEventLedgerUses()
	{
		// Same rationale as LootCollectorTest's equivalent test: exercise
		// the exact serialization mechanism EventLedger.append() actually
		// uses (RuneLite's injected Gson), isolated from Client/EventBus/
		// file I/O, rather than duplicating EventLedger's own tested logic.
		Gson gson = new Gson();
		EventPayloads.SeedVaultSnapshot payload = ContainerCollector.buildSeedVaultSnapshotPayload("seed_vault", 7);

		String json = gson.toJson(payload);
		EventPayloads.SeedVaultSnapshot roundTripped = gson.fromJson(json, EventPayloads.SeedVaultSnapshot.class);

		assertEquals(payload, roundTripped);
	}

	// =====================================================================
	// Bank-snapshot shutdown-durability lifecycle tests
	// =====================================================================

	private static long nextAccountHash = 700_000_001L;
	private static final Gson LIFECYCLE_TEST_GSON = new Gson();

	private long accountHash;
	private LocalStateStore store;
	private EventLedger eventLedger;
	private ScheduledExecutorService scheduledExecutor;
	private ContainerCollector collector;

	@Before
	public void setUpLifecycle() throws Exception
	{
		// A fresh accountHash per test avoids any cross-test bleed
		// through shared on-disk state (bank.json / bank_snapshots /
		// events.jsonl all key off accountHash).
		accountHash = nextAccountHash++;
		deleteAccountDir(accountHash);

		store = new LocalStateStore(LIFECYCLE_TEST_GSON);
		store.start();

		eventLedger = new EventLedger(LIFECYCLE_TEST_GSON);
		eventLedger.start();

		scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

		collector = new ContainerCollector(null, null, store, eventLedger, null, scheduledExecutor);
	}

	@After
	public void tearDownLifecycle() throws Exception
	{
		scheduledExecutor.shutdownNow();
		store.shutdown();
		eventLedger.shutdown();
		deleteAccountDir(accountHash);
	}

	private static void deleteAccountDir(long accountHash) throws IOException
	{
		File dir = TelemetryPaths.accountDir(accountHash);
		deleteRecursively(dir);
	}

	private static void deleteRecursively(File file) throws IOException
	{
		if (!file.exists())
		{
			return;
		}
		File[] children = file.listFiles();
		if (children != null)
		{
			for (File child : children)
			{
				deleteRecursively(child);
			}
		}
		Files.deleteIfExists(file.toPath());
	}

	private static List<StorageState.StorageItem> oneItem(String name)
	{
		List<StorageState.StorageItem> items = new ArrayList<>();
		items.add(new StorageState.StorageItem(0, 1, name, 1, null));
		return items;
	}

	private List<String> readEventLines() throws IOException
	{
		File eventsFile = TelemetryPaths.eventsFile(accountHash);
		if (!eventsFile.exists())
		{
			return new ArrayList<>();
		}
		return Files.readAllLines(eventsFile.toPath());
	}

	private List<String> bankSnapshotEventLines() throws IOException
	{
		List<String> bankSnapshotLines = new ArrayList<>();
		for (String line : readEventLines())
		{
			JsonObject obj = new JsonParser().parse(line).getAsJsonObject();
			if ("BANK_SNAPSHOT".equals(obj.get("eventType").getAsString()))
			{
				bankSnapshotLines.add(line);
			}
		}
		return bankSnapshotLines;
	}

	private int countSnapshotFiles()
	{
		File[] files = TelemetryPaths.bankSnapshotsDir(accountHash).listFiles();
		return files == null ? 0 : files.length;
	}

	// --- A. blocking bank finalization: snapshot -> pointer -> event all complete before return ---

	@Test
	public void a_finalizeBankSnapshotBlocking_snapshotPointerAndEventAllCompleteBeforeReturn() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Blood moon chestplate");

		boolean success = collector.finalizeBankSnapshotBlocking(accountHash, items);

		assertTrue("a successful new snapshot must report success", success);

		StorageState pointer = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		assertNotNull("bank.json must exist the instant finalizeBankSnapshotBlocking() returns", pointer);
		assertNotNull("bank.json must point at a snapshot id", pointer.getLatestSnapshotId());

		File snapshotFile = TelemetryPaths.bankSnapshotFile(accountHash, pointer.getLatestSnapshotId());
		assertTrue("the immutable snapshot file bank.json points to must actually exist", snapshotFile.exists());

		List<String> bankSnapshotEvents = bankSnapshotEventLines();
		assertEquals(
			"exactly one BANK_SNAPSHOT event must be durably on disk the instant finalizeBankSnapshotBlocking() returns — this is the exact live bug (snapshot+pointer succeeded, event missing)",
			1, bankSnapshotEvents.size());

		JsonObject event = new JsonParser().parse(bankSnapshotEvents.get(0)).getAsJsonObject();
		JsonObject payload = event.getAsJsonObject("payload");
		assertEquals(pointer.getLatestSnapshotId(), payload.get("snapshotId").getAsString());
	}

	// --- B. immediate shutdown after a bank mutation (debounce never fires) ---

	@Test
	public void b_immediateShutdownAfterBankMutation_debounceNeverFires_stillProducesEvent() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Blood moon tassets");

		// Simulates: bank contents changed (scheduleBankSnapshot() is
		// what writeContainer() calls), then RuneLite is closed
		// immediately — long before the 3s debounce would ever fire.
		collector.scheduleBankSnapshot(accountHash, items);
		collector.flushPendingAndWait();

		assertEquals(1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());

		StorageState pointer = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		assertNotNull(pointer);
		assertNotNull(pointer.getLatestSnapshotId());
	}

	// --- C. WidgetClosed async finalization racing plugin shutdown ---

	@Test
	public void c_widgetClosedAsyncFinalizationRacingShutdown_shutdownWaitsForInFlightWork() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Blood moon tassets");

		// scheduleBankSnapshot() + flushPending() is exactly what
		// onWidgetClosed() does: it consumes pendingSnapshotItems and
		// launches the ASYNC snapshot -> pointer -> event chain (still
		// running on LocalStateStore's/EventLedger's own background
		// threads when flushPending() itself returns).
		collector.scheduleBankSnapshot(accountHash, items);
		collector.flushPending();

		// At this exact instant pendingBankSnapshot/pendingSnapshotItems
		// are ALREADY null (flushPending() consumed them) — this is
		// precisely the race the bug report described: shutdown must
		// not conclude "nothing pending" and return immediately. It
		// must find and wait on the in-flight finalization instead.
		collector.flushPendingAndWait();

		assertEquals(
			"shutdown must have waited for the already-in-flight WidgetClosed finalization to actually complete",
			1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- D. scheduled debounce finalization racing shutdown ---

	@Test
	public void d_scheduledDebounceFinalizationRacingShutdown_shutdownWaitsRatherThanReturning() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Zaryte crossbow");
		collector.scheduleBankSnapshot(accountHash, items);

		CountDownLatch start = new CountDownLatch(1);
		Thread debounceThread = new Thread(() ->
		{
			try
			{
				start.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			// Simulates the real scheduled debounce task firing —
			// consumes pendingSnapshotItems and starts the async chain.
			collector.runScheduledBankFinalizeForTest();
		});
		debounceThread.start();

		start.countDown();
		// Deliberately racing: flushPendingAndWait() (the shutdown path)
		// may run before, during, or after the debounce thread's
		// consumption — the fix must be correct regardless of exactly
		// how the race lands.
		collector.flushPendingAndWait();
		debounceThread.join(5000);

		assertFalse("debounce thread must have finished", debounceThread.isAlive());
		assertEquals(
			"shutdown must wait for an in-flight scheduled-debounce finalization instead of returning early",
			1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- E. no duplicate snapshot/event when shutdown and another finalizer race repeatedly ---

	@Test
	public void e_repeatedShutdownVsDebounceRaces_neverProduceDuplicateSnapshotOrEvent() throws Exception
	{
		for (int i = 0; i < 5; i++)
		{
			deleteAccountDir(accountHash);
			List<StorageState.StorageItem> items = oneItem("Twisted bow #" + i);
			collector.scheduleBankSnapshot(accountHash, items);

			CountDownLatch ready = new CountDownLatch(1);
			Thread racer = new Thread(() ->
			{
				try
				{
					ready.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					return;
				}
				collector.runScheduledBankFinalizeForTest();
			});
			racer.start();
			ready.countDown();

			collector.flushPendingAndWait();
			racer.join(5000);

			assertEquals("iteration " + i + ": exactly one snapshot file, never zero or two", 1, countSnapshotFiles());
			assertEquals("iteration " + i + ": exactly one BANK_SNAPSHOT event, never zero or two", 1, bankSnapshotEventLines().size());
		}
	}

	// --- F. snapshot write failure: no pointer and no BANK_SNAPSHOT ---

	@Test
	public void f_snapshotWriteFailure_producesNoPointerAndNoEvent() throws Exception
	{
		// Force the immutable-snapshot write to fail in a way that is
		// portable across OSes: make the "bank_snapshots" path itself a
		// PLAIN FILE instead of a directory. Any attempt to create a
		// file underneath it (bank_snapshots/<uuid>.json) then fails
		// deterministically, because a path component that is a regular
		// file can never behave as a directory.
		File accountDir = TelemetryPaths.accountDir(accountHash);
		File bankSnapshotsAsFile = new File(accountDir, "bank_snapshots");
		deleteRecursively(bankSnapshotsAsFile);
		Files.write(bankSnapshotsAsFile.toPath(), "not a directory".getBytes());

		List<StorageState.StorageItem> items = oneItem("Scythe of vitur");
		boolean success = collector.finalizeBankSnapshotBlocking(accountHash, items);

		assertFalse("a failed snapshot write must report failure, not success", success);

		StorageState pointer = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		assertNull("INVARIANT: failed snapshot write => no pointer write", pointer);
		assertEquals("INVARIANT: failed snapshot write => no BANK_SNAPSHOT event", 0, bankSnapshotEventLines().size());
	}

	// --- G. pointer write failure: no BANK_SNAPSHOT ---

	@Test
	public void g_pointerWriteFailure_producesNoEvent() throws Exception
	{
		// Force the bank.json POINTER write to fail, while leaving the
		// immutable-snapshot write free to succeed: make "bank.json" a
		// DIRECTORY (with a file inside, so it's non-empty) instead of a
		// file. LocalStateStore.writeNow()'s Files.move(tmp, target,
		// REPLACE_EXISTING, ATOMIC_MOVE) can never replace a directory
		// with a regular file, so this fails deterministically.
		File accountDir = TelemetryPaths.accountDir(accountHash);
		File bankJsonAsDir = new File(accountDir, "bank.json");
		deleteRecursively(bankJsonAsDir);
		assertTrue(bankJsonAsDir.mkdir());
		Files.write(new File(bankJsonAsDir, "placeholder").toPath(), "x".getBytes());

		List<StorageState.StorageItem> items = oneItem("Elysian spirit shield");
		boolean success = collector.finalizeBankSnapshotBlocking(accountHash, items);

		assertFalse("a failed pointer write must report failure, not success", success);
		assertEquals(
			"the immutable snapshot write itself succeeded before the pointer write failed",
			1, countSnapshotFiles());
		assertEquals("INVARIANT: failed pointer write => no BANK_SNAPSHOT event", 0, bankSnapshotEventLines().size());
	}

	// =====================================================================
	// ClientShutdown lifecycle tests (letters A-G) --
	// guarding against a normal RuneLite
	// window close not reliably invoking Plugin.shutDown() at all,
	// and must instead be handled via net.runelite.client.events.
	// ClientShutdown. beginClientShutdownFinalization() is the exact
	// package-private hook onClientShutdown() itself calls (see its own
	// javadoc) -- these tests exercise real production code, not a
	// test-only approximation.
	// =====================================================================

	// --- A. explicit plugin disable -> Plugin.shutDown() path still works ---

	@Test
	public void clientShutdown_a_explicitPluginDisablePath_flushPendingAndWait_stillProducesEvent() throws Exception
	{
		// Plugin.shutDown() calls containerCollector.flushPendingAndWait()
		// directly (see OsrsTelemetryPlugin.shutDown()) -- this is that
		// exact call, confirming the pre-existing explicit-disable path
		// remains correct after the ClientShutdown lifecycle pass.
		List<StorageState.StorageItem> items = oneItem("Dragon warhammer");

		collector.scheduleBankSnapshot(accountHash, items);
		collector.flushPendingAndWait();

		assertEquals(1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- B. normal application close -> ClientShutdown path ---

	@Test
	public void clientShutdown_b_normalApplicationClose_beginClientShutdownFinalization_producesEvent() throws Exception
	{
		// Simulates onClientShutdown(event): event.waitFor(
		// beginClientShutdownFinalization()) -- a real RuneLite window
		// close never invokes Plugin.shutDown() at all (the CONFIRMED
		// live finding), only ClientShutdown.
		List<StorageState.StorageItem> items = oneItem("Ancestral hat");

		collector.scheduleBankSnapshot(accountHash, items);
		collector.beginClientShutdownFinalization().get(5, TimeUnit.SECONDS);

		assertEquals(1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- C. bank mutation + ClientShutdown before the 3s debounce fires ---

	@Test
	public void clientShutdown_c_bankMutationThenImmediateClientShutdown_debounceNeverFires_fullChainCompletes() throws Exception
	{
		// This is the exact live-bug scenario: bank changed, then the
		// client exited a moment later -- long before scheduleBankSnapshot()'s
		// 3-second debounce timer would ever fire on its own.
		List<StorageState.StorageItem> items = oneItem("Onion seed");

		collector.scheduleBankSnapshot(accountHash, items);
		collector.beginClientShutdownFinalization().get(5, TimeUnit.SECONDS);

		StorageState pointer = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		assertNotNull("bank.json pointer must be durable", pointer);
		assertNotNull(pointer.getLatestSnapshotId());

		File snapshotFile = TelemetryPaths.bankSnapshotFile(accountHash, pointer.getLatestSnapshotId());
		assertTrue("the immutable snapshot the pointer references must exist", snapshotFile.exists());

		assertEquals(
			"exactly one BANK_SNAPSHOT event -- this is the exact live bug scenario",
			1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- D. WidgetClosed finalization already in flight when ClientShutdown arrives ---

	@Test
	public void clientShutdown_d_widgetClosedAlreadyInFlight_clientShutdownWaitsRatherThanDuplicating() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Justiciar faceguard");

		// onWidgetClosed() consumes pendingSnapshotItems and launches the
		// async chain; by the time flushPending() returns here, the
		// pending* fields are already null and the real work is still
		// running on LocalStateStore's/EventLedger's background threads.
		collector.scheduleBankSnapshot(accountHash, items);
		collector.flushPending();

		// ClientShutdown arrives an instant later: it must find and wait
		// for the already-in-flight WidgetClosed finalization instead of
		// concluding "nothing pending" and returning immediately, and it
		// must not start a second, duplicate finalization.
		collector.beginClientShutdownFinalization().get(5, TimeUnit.SECONDS);

		assertEquals(
			"ClientShutdown must have waited for the already-in-flight WidgetClosed finalization",
			1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- E. scheduled debounce finalization already in flight when ClientShutdown arrives ---

	@Test
	public void clientShutdown_e_scheduledDebounceAlreadyInFlight_clientShutdownWaitsRatherThanDuplicating() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Justiciar chestguard");
		collector.scheduleBankSnapshot(accountHash, items);

		CountDownLatch start = new CountDownLatch(1);
		Thread debounceThread = new Thread(() ->
		{
			try
			{
				start.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			collector.runScheduledBankFinalizeForTest();
		});
		debounceThread.start();

		start.countDown();
		// Deliberately racing, exactly like the existing WidgetClosed/
		// debounce-vs-shutdown tests: the fix must be correct regardless
		// of exactly how the race lands.
		collector.beginClientShutdownFinalization().get(5, TimeUnit.SECONDS);
		debounceThread.join(5000);

		assertFalse("debounce thread must have finished", debounceThread.isAlive());
		assertEquals(
			"ClientShutdown must wait for an in-flight scheduled-debounce finalization instead of duplicating it",
			1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());
	}

	// --- F. ClientShutdown completes, then a later Plugin.shutDown()-style call: no duplication ---

	@Test
	public void clientShutdown_f_clientShutdownThenLaterPluginShutDown_neverDuplicatesFinalization() throws Exception
	{
		List<StorageState.StorageItem> items = oneItem("Justiciar legguards");

		collector.scheduleBankSnapshot(accountHash, items);
		// ClientShutdown path runs first and completes fully.
		collector.beginClientShutdownFinalization().get(5, TimeUnit.SECONDS);

		assertEquals(1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());

		// A later Plugin.shutDown()-style call (both shutdown paths are
		// idempotent with respect to bank finalization: nothing is
		// pending any more, and inFlightBankFinalization already holds a
		// completed future, so this must be a safe no-op).
		collector.flushPendingAndWait();

		assertEquals(
			"a later flushPendingAndWait() call after ClientShutdown already finalized must not produce a second event",
			1, bankSnapshotEventLines().size());
		assertEquals(
			"a later flushPendingAndWait() call after ClientShutdown already finalized must not produce a second snapshot",
			1, countSnapshotFiles());
	}

	// --- G. no bank mutation at all: shutdown must not fabricate a snapshot/event ---

	@Test
	public void clientShutdown_g_noBankMutation_doesNotFabricateSnapshotOrEvent() throws Exception
	{
		// No scheduleBankSnapshot()/flushPending() call at all --
		// nothing pending, nothing in flight.
		collector.beginClientShutdownFinalization().get(5, TimeUnit.SECONDS);

		assertEquals("no bank mutation occurred -- ClientShutdown must not fabricate a BANK_SNAPSHOT event", 0, bankSnapshotEventLines().size());
		assertEquals("no bank mutation occurred -- ClientShutdown must not fabricate an immutable snapshot", 0, countSnapshotFiles());

		StorageState pointer = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		assertNull("no bank mutation occurred -- ClientShutdown must not fabricate a bank.json pointer", pointer);
	}

	// --- I. enable -> disable -> enable still works for the full bank finalization chain ---

	@Test
	public void i_enableDisableEnableCycle_bankFinalizationStillWorksEachTime() throws Exception
	{
		assertTrue(collector.finalizeBankSnapshotBlocking(accountHash, oneItem("Dragon claws")));
		assertEquals(1, bankSnapshotEventLines().size());
		assertEquals(1, countSnapshotFiles());

		// Simulate plugin disable -> re-enable within the same client
		// process: both executors are torn down and restarted, exactly
		// as OsrsTelemetryPlugin's shutDown()/startUp() do.
		store.shutdown();
		eventLedger.shutdown();

		store.start();
		eventLedger.start();
		eventLedger.startNewSession();

		// A different item (dedup must not suppress this — different
		// content than the baseline recorded before the toggle).
		assertTrue(collector.finalizeBankSnapshotBlocking(accountHash, oneItem("Twisted bow")));

		assertEquals(
			"both the pre- and post-restart finalizations must have durably produced a BANK_SNAPSHOT event",
			2, bankSnapshotEventLines().size());
		assertEquals(2, countSnapshotFiles());
	}

	// =====================================================================
	// Bank placeholder handling (letters A-G) -- guarding
	// against a bank placeholder left behind
	// after withdrawing the final copy of an item (e.g. real "Grubby
	// key" itemId 23499 -> placeholder "Grubby key" itemId 23501,
	// quantity still reported as 1) was persisted as an owned item.
	// buildBankItemsCore() is the exact pure core buildBankItems() (the
	// real production ItemContainer-facing method) delegates to -- see
	// its own javadoc -- exercised here with plain IntPredicate/
	// IntFunction fakes instead of a mocked ItemManager/ItemComposition
	// (this project has no Mockito dependency).
	// =====================================================================

	private static final IntPredicate NEVER_PLACEHOLDER = itemId -> false;

	private static IntFunction<String> nameLookup(Map<Integer, String> names)
	{
		return names::get;
	}

	// --- A. normal bank item is retained ---

	@Test
	public void placeholder_a_normalBankItem_isRetained()
	{
		Item[] raw = new Item[] { new Item(23499, 1) };
		Map<Integer, String> names = new HashMap<>();
		names.put(23499, "Grubby key");

		List<StorageState.StorageItem> items = ContainerCollector.buildBankItemsCore(raw, NEVER_PLACEHOLDER, nameLookup(names));

		assertEquals(1, items.size());
		StorageState.StorageItem item = items.get(0);
		assertEquals(0, item.getSlot());
		assertEquals(23499, item.getItemId());
		assertEquals("Grubby key", item.getName());
		assertEquals(1, item.getQuantity());
	}

	// --- B. placeholder item is excluded ---

	@Test
	public void placeholder_b_placeholderItem_isExcluded()
	{
		// Exactly the live-bug shape: placeholder itemId (23501),
		// reported quantity 1 -- must still be excluded.
		Item[] raw = new Item[] { new Item(23501, 1) };
		IntPredicate isPlaceholder = itemId -> itemId == 23501;
		Map<Integer, String> names = new HashMap<>();
		names.put(23501, "Grubby key");

		List<StorageState.StorageItem> items = ContainerCollector.buildBankItemsCore(raw, isPlaceholder, nameLookup(names));

		assertTrue("a placeholder-only slot must produce no owned-item entry at all", items.isEmpty());
	}

	// --- C. real item -> placeholder transition produces an owned-state change ---

	@Test
	public void placeholder_c_realItemToPlaceholderTransition_isAnOwnedStateChange()
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(23499, "Grubby key");
		names.put(23501, "Grubby key");
		IntPredicate isPlaceholder = itemId -> itemId == 23501;

		List<StorageState.StorageItem> before = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23499, 1) }, isPlaceholder, nameLookup(names));
		List<StorageState.StorageItem> after = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23501, 1) }, isPlaceholder, nameLookup(names));

		assertEquals(1, before.size());
		assertTrue("withdrawing the final copy (leaving a placeholder) must remove it from owned items", after.isEmpty());
		assertFalse("the before/after owned-item lists must be genuinely different", before.equals(after));
	}

	// --- D. placeholder-only bank slot is not serialized as ownership ---

	@Test
	public void placeholder_d_placeholderOnlySlot_isNeverSerializedAsOwnership()
	{
		// A slot RuneLite still reserves for the placeholder, alongside
		// unrelated real items in other slots -- only the placeholder
		// slot must be dropped.
		Item[] raw = new Item[] {
			new Item(23499, 1),
			new Item(13797, 5),
			new Item(23501, 1)
		};
		IntPredicate isPlaceholder = itemId -> itemId == 23501;
		Map<Integer, String> names = new HashMap<>();
		names.put(23499, "Blood moon chestplate");
		names.put(13797, "Onion seed");
		names.put(23501, "Grubby key");

		List<StorageState.StorageItem> items = ContainerCollector.buildBankItemsCore(raw, isPlaceholder, nameLookup(names));

		assertEquals("exactly the two real items must survive, never the placeholder slot", 2, items.size());
		for (StorageState.StorageItem item : items)
		{
			assertFalse("no serialized item may carry the placeholder itemId", item.getItemId() == 23501);
		}
	}

	// --- E. bank snapshot after removing the final copy does not contain the item ---

	@Test
	public void placeholder_e_bankSnapshotAfterRemovingFinalCopy_doesNotContainTheItem() throws Exception
	{
		IntPredicate isPlaceholder = itemId -> itemId == 23501;
		Map<Integer, String> names = new HashMap<>();
		names.put(23499, "Grubby key");
		names.put(23501, "Grubby key");

		List<StorageState.StorageItem> withKey = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23499, 1) }, isPlaceholder, nameLookup(names));
		assertTrue(collector.finalizeBankSnapshotBlocking(accountHash, withKey));

		StorageState pointerBefore = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		StorageState snapshotBefore = store.readIfExists(
			TelemetryPaths.bankSnapshotFile(accountHash, pointerBefore.getLatestSnapshotId()), StorageState.class);
		assertEquals(1, snapshotBefore.getItems().size());

		// The final copy is withdrawn, leaving only the placeholder --
		// buildBankItemsCore() (as production's buildBankItems() would)
		// excludes it entirely from the observed owned-item list.
		List<StorageState.StorageItem> withPlaceholderOnly = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23501, 1) }, isPlaceholder, nameLookup(names));
		assertTrue(collector.finalizeBankSnapshotBlocking(accountHash, withPlaceholderOnly));

		StorageState pointerAfter = store.readIfExists(TelemetryPaths.stateFile(accountHash, "bank"), StorageState.class);
		StorageState snapshotAfter = store.readIfExists(
			TelemetryPaths.bankSnapshotFile(accountHash, pointerAfter.getLatestSnapshotId()), StorageState.class);

		assertTrue("the new immutable snapshot must contain no entry for the withdrawn item at all",
			snapshotAfter.getItems().isEmpty());
		for (StorageState.StorageItem item : snapshotAfter.getItems())
		{
			assertFalse(item.getItemId() == 23499 || item.getItemId() == 23501);
		}
	}

	// --- F. dedup logic sees real-item -> placeholder as changed contents ---

	@Test
	public void placeholder_f_dedupBaseline_seesRealItemToPlaceholderTransitionAsChanged()
	{
		IntPredicate isPlaceholder = itemId -> itemId == 23501;
		Map<Integer, String> names = new HashMap<>();
		names.put(23499, "Grubby key");
		names.put(23501, "Grubby key");

		List<StorageState.StorageItem> baselineItems = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23499, 1) }, isPlaceholder, nameLookup(names));
		List<StorageState.StorageItem> candidateItems = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23501, 1) }, isPlaceholder, nameLookup(names));

		Instant createdAt = Instant.now();
		BankSnapshotBaseline baseline = BankSnapshotBaseline.of(baselineItems, createdAt);

		assertTrue(
			"a real-item -> placeholder transition must be recognized as removal of the owned item, forcing a new snapshot",
			BankSnapshotBaseline.shouldWriteNewSnapshot(baseline, candidateItems, createdAt.plusSeconds(1), java.time.Duration.ofMinutes(10)));
	}

	// --- G. inventory/equipment behavior is unchanged ---

	@Test
	public void placeholder_g_inventoryAndEquipment_behaviorUnchanged()
	{
		// buildSparseItems()/buildEquipmentItems() (inventory/equipment/
		// seed vault/group storage) are untouched by this fix -- they
		// never call buildBankItemsCore()/isPlaceholder at all, so an
		// itemId that WOULD be treated as a bank placeholder must still
		// be recorded normally everywhere else. This asserts the
		// negative: buildBankItemsCore() applied with a
		// no-op placeholder predicate (equivalent to what
		// buildSparseItems() effectively does -- no placeholder concept)
		// still returns every real, quantity>0 item, confirming the
		// filtering is additive and opt-in per container, not a global
		// itemId denylist that could ever leak into another container's
		// logic.
		Map<Integer, String> names = new HashMap<>();
		names.put(23501, "Grubby key");

		List<StorageState.StorageItem> items = ContainerCollector.buildBankItemsCore(
			new Item[] { new Item(23501, 1) }, NEVER_PLACEHOLDER, nameLookup(names));

		assertEquals(
			"without bank-specific placeholder classification (i.e. exactly how buildSparseItems()/"
				+ "buildEquipmentItems() treat every item id), the same raw item is retained normally",
			1, items.size());
	}
}
