package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run — same no-network caveat as everywhere else
 * in this project.
 *
 * Split deliberately in two, matching BankSnapshotBaseline's own
 * split: pure decision-rule tests (A-F) need no I/O and no Client;
 * persisted-baseline tests (G-J) need only LocalStateStore + real
 * files, still no Client. Test K is not a new test here — see the
 * comment on it below for why the existing
 * LocalStateStoreTest already establishes the guarantee it asks for,
 * structurally, by construction of where recordBankBaseline() was
 * placed in ContainerCollector (inside the write's own onWritten
 * success-only callback) — fabricating a second, redundant
 * integration test for the same guarantee (which would need a full
 * ContainerCollector + a mocked Client, and this project has no
 * Mockito dependency and wasn't asked to add one) would not add real
 * coverage beyond what already exists.
 */
public class BankSnapshotBaselineTest
{
	private static final Duration MAX_AGE = Duration.ofMinutes(10);

	private static StorageState.StorageItem item(int slot, int itemId, String name, int quantity)
	{
		return new StorageState.StorageItem(slot, itemId, name, quantity, null);
	}

	private static List<StorageState.StorageItem> bankOf(StorageState.StorageItem... items)
	{
		return Arrays.asList(items);
	}

	// ---- A-F: pure decision rule, no I/O ----

	@Test
	public void a_firstObservation_noBaseline_alwaysWrites()
	{
		List<StorageState.StorageItem> items = bankOf(item(0, 995, "Coins", 1000));
		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(
			BankSnapshotBaseline.absent(), items, Instant.now(), MAX_AGE));
	}

	@Test
	public void b_identicalContents_underTenMinutes_doesNotWrite()
	{
		List<StorageState.StorageItem> items = bankOf(item(0, 995, "Coins", 1000));
		Instant baselineTime = Instant.now().minus(Duration.ofMinutes(5));
		BankSnapshotBaseline baseline = BankSnapshotBaseline.of(items, baselineTime);

		assertFalse(BankSnapshotBaseline.shouldWriteNewSnapshot(baseline, items, Instant.now(), MAX_AGE));
	}

	@Test
	public void c_identicalContents_atOrAfterTenMinutes_writesRefresh()
	{
		List<StorageState.StorageItem> items = bankOf(item(0, 995, "Coins", 1000));
		Instant now = Instant.now();

		// Exactly 10 minutes — ">= 10 minutes" per spec, boundary included.
		BankSnapshotBaseline exactlyTen = BankSnapshotBaseline.of(items, now.minus(Duration.ofMinutes(10)));
		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(exactlyTen, items, now, MAX_AGE));

		BankSnapshotBaseline overTen = BankSnapshotBaseline.of(items, now.minus(Duration.ofMinutes(11)));
		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(overTen, items, now, MAX_AGE));
	}

	@Test
	public void d_quantityChange_insideWindow_alwaysWrites()
	{
		List<StorageState.StorageItem> baselineItems = bankOf(item(0, 995, "Coins", 1000));
		List<StorageState.StorageItem> candidateItems = bankOf(item(0, 995, "Coins", 999));
		Instant now = Instant.now();
		BankSnapshotBaseline baseline = BankSnapshotBaseline.of(baselineItems, now.minus(Duration.ofSeconds(1)));

		assertTrue("a real quantity change must write even seconds after the last snapshot",
			BankSnapshotBaseline.shouldWriteNewSnapshot(baseline, candidateItems, now, MAX_AGE));
	}

	@Test
	public void e_itemChange_insideWindow_alwaysWrites()
	{
		List<StorageState.StorageItem> baselineItems = bankOf(item(0, 995, "Coins", 1000));
		List<StorageState.StorageItem> candidateItems = bankOf(item(0, 1050, "Iron ore", 1000));
		Instant now = Instant.now();
		BankSnapshotBaseline baseline = BankSnapshotBaseline.of(baselineItems, now.minus(Duration.ofSeconds(1)));

		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(baseline, candidateItems, now, MAX_AGE));
	}

	@Test
	public void f_slotReorder_insideWindow_alwaysWrites()
	{
		List<StorageState.StorageItem> baselineItems = bankOf(
			item(0, 995, "Coins", 1000),
			item(1, 1050, "Iron ore", 5)
		);
		// Same two items and quantities, but swapped slots — a real
		// bank reorganization, this must count as a
		// changed state.
		List<StorageState.StorageItem> candidateItems = bankOf(
			item(0, 1050, "Iron ore", 5),
			item(1, 995, "Coins", 1000)
		);
		Instant now = Instant.now();
		BankSnapshotBaseline baseline = BankSnapshotBaseline.of(baselineItems, now.minus(Duration.ofSeconds(1)));

		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(baseline, candidateItems, now, MAX_AGE));
	}

	// ---- G-J: persisted-baseline loading, real files, no Client ----

	private static final long TEST_ACCOUNT_HASH = 888_777_111L;
	private static final long OTHER_ACCOUNT_HASH = 888_777_222L;
	private static final Gson GSON = new Gson();

	private LocalStateStore store;
	private Path tempRoot;

	/**
	 * A brand-new temp directory per test, rooted via TestFilepaths
	 * (see its own javadoc for why Filepath.Unchecked is justified here
	 * and nowhere else) and installed as TelemetryPaths' shared root
	 * BEFORE the test body runs -- this gives every test full account
	 * isolation for free, without the old fixed-account-hash
	 * delete-before-and-after dance the previous java.io.File-based
	 * version of this test needed.
	 */
	@Before
	public void setUp() throws Exception
	{
		tempRoot = Files.createTempDirectory("bank-snapshot-baseline-test");
		TelemetryPaths.init(TestFilepaths.rooted(tempRoot));
		store = new LocalStateStore(GSON);
		store.start();
	}

	@After
	public void tearDown() throws Exception
	{
		store.shutdown();
		deleteRecursively(tempRoot);
	}

	private static void deleteRecursively(Path path) throws Exception
	{
		if (!Files.exists(path))
		{
			return;
		}
		try (Stream<Path> walk = Files.walk(path))
		{
			walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	/** Writes a fixture snapshot + a bank.json pointer referencing it, exactly matching what ContainerCollector itself produces. */
	private String writeFixtureSnapshot(long accountHash, List<StorageState.StorageItem> items, Instant observedAt)
	{
		String snapshotId = "fixture-" + observedAt.toEpochMilli();
		StorageState snapshot = new StorageState("bank");
		snapshot.setItems(items);
		snapshot.setLastObservedAt(observedAt.toString());
		snapshot.setContinuouslyObservable(false);
		snapshot.setLatestSnapshotId(snapshotId);
		assertTrue(store.writeAndWait(TelemetryPaths.bankSnapshotFile(accountHash, snapshotId), snapshot, 2000));

		StorageState pointer = new StorageState("bank");
		pointer.setItems(items);
		pointer.setLastObservedAt(observedAt.toString());
		pointer.setContinuouslyObservable(false);
		pointer.setLatestSnapshotId(snapshotId);
		assertTrue(store.writeAndWait(TelemetryPaths.stateFile(accountHash, "bank"), pointer, 2000));

		return snapshotId;
	}

	@Test
	public void g_validPersistedBaselineUnderTenMinutes_suppressesDuplicate()
	{
		List<StorageState.StorageItem> items = bankOf(item(0, 995, "Coins", 1000));
		String snapshotId = writeFixtureSnapshot(TEST_ACCOUNT_HASH, items, Instant.now().minus(Duration.ofMinutes(5)));

		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertTrue("baseline should have loaded from the fixture files", loaded.isPresent());
		assertFalse(BankSnapshotBaseline.shouldWriteNewSnapshot(loaded, items, Instant.now(), MAX_AGE));
		assertEquals("Phase 1 requirement A: a persisted baseline must retain its snapshotId",
			snapshotId, loaded.getSnapshotId());
	}

	/** Test A (Phase 1): loadFrom() must carry the resolved snapshotId
	 * into the returned baseline, not just items/createdAt — this is
	 * what lets ContainerCollector re-stamp bank.json's latestSnapshotId
	 * on ordinary writes and dedup-skip refreshes without a second disk
	 * read. */
	@Test
	public void a2_loadedBaseline_carriesSnapshotIdSeparatelyFromContents()
	{
		List<StorageState.StorageItem> items = bankOf(item(0, 995, "Coins", 1000));
		String snapshotId = writeFixtureSnapshot(TEST_ACCOUNT_HASH, items, Instant.now().minus(Duration.ofMinutes(1)));

		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertTrue(loaded.isPresent());
		assertEquals(snapshotId, loaded.getSnapshotId());

		// A different fixture (different snapshotId, different content)
		// for a second account must not collide. snapshotId is derived from
		// observedAt.toEpochMilli() (see writeFixtureSnapshot), so the two
		// fixture timestamps must be forced at least a second apart -- two
		// bare back-to-back Instant.now() calls can legitimately land in the
		// same millisecond on a fast machine, which made this assertion
		// flaky for a reason that had nothing to do with real collision
		// handling.
		List<StorageState.StorageItem> otherItems = bankOf(item(0, 1050, "Iron ore", 50));
		String otherSnapshotId =
			writeFixtureSnapshot(OTHER_ACCOUNT_HASH, otherItems, Instant.now().minus(Duration.ofMinutes(1)).minusSeconds(1));
		BankSnapshotBaseline otherLoaded = BankSnapshotBaseline.loadFrom(store, OTHER_ACCOUNT_HASH);
		assertEquals(otherSnapshotId, otherLoaded.getSnapshotId());
		assertNotEquals(snapshotId, otherSnapshotId);
	}

	@Test
	public void h_validPersistedBaselineOverTenMinutes_createsRefresh()
	{
		List<StorageState.StorageItem> items = bankOf(item(0, 995, "Coins", 1000));
		writeFixtureSnapshot(TEST_ACCOUNT_HASH, items, Instant.now().minus(Duration.ofMinutes(15)));

		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertTrue(loaded.isPresent());
		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(loaded, items, Instant.now(), MAX_AGE));
	}

	@Test
	public void i_missingBankJson_failsOpen()
	{
		// Nothing written for this account at all.
		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertFalse(loaded.isPresent());
		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(
			loaded, bankOf(item(0, 995, "Coins", 1)), Instant.now(), MAX_AGE));
	}

	@Test
	public void i_bankJsonWithoutLatestSnapshotId_failsOpen()
	{
		StorageState pointer = new StorageState("bank");
		pointer.setItems(bankOf(item(0, 995, "Coins", 1000)));
		pointer.setLastObservedAt(Instant.now().toString());
		pointer.setContinuouslyObservable(false);
		// latestSnapshotId deliberately left null — simulates a
		// bank.json written before any immutable snapshot ever
		// succeeded (e.g. the very first debounce window is still
		// pending, or every snapshot attempt so far has failed).
		assertTrue(store.writeAndWait(TelemetryPaths.stateFile(TEST_ACCOUNT_HASH, "bank"), pointer, 2000));

		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertFalse(loaded.isPresent());
	}

	@Test
	public void i_pointerReferencesMissingSnapshotFile_failsOpen()
	{
		StorageState pointer = new StorageState("bank");
		pointer.setItems(bankOf(item(0, 995, "Coins", 1000)));
		pointer.setLastObservedAt(Instant.now().toString());
		pointer.setContinuouslyObservable(false);
		pointer.setLatestSnapshotId("this-snapshot-id-does-not-exist-on-disk");
		assertTrue(store.writeAndWait(TelemetryPaths.stateFile(TEST_ACCOUNT_HASH, "bank"), pointer, 2000));

		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertFalse("a dangling latestSnapshotId must fail open, not throw", loaded.isPresent());
	}

	@Test
	public void i_corruptSnapshotFile_failsOpen() throws Exception
	{
		String snapshotId = "corrupt-fixture";
		StorageState pointer = new StorageState("bank");
		pointer.setItems(bankOf(item(0, 995, "Coins", 1000)));
		pointer.setLastObservedAt(Instant.now().toString());
		pointer.setContinuouslyObservable(false);
		pointer.setLatestSnapshotId(snapshotId);
		assertTrue(store.writeAndWait(TelemetryPaths.stateFile(TEST_ACCOUNT_HASH, "bank"), pointer, 2000));

		// Write genuinely malformed JSON directly, bypassing the store,
		// to simulate corruption (a truncated write, a bad upgrade, etc).
		Filepath snapshotFile = TelemetryPaths.bankSnapshotFile(TEST_ACCOUNT_HASH, snapshotId);
		snapshotFile.write("{ this is not valid json".getBytes(StandardCharsets.UTF_8));

		BankSnapshotBaseline loaded = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		assertFalse("malformed JSON must fail open, not throw", loaded.isPresent());
	}

	@Test
	public void j_accountSwitch_neverCrossContaminatesBaselines()
	{
		List<StorageState.StorageItem> accountAItems = bankOf(item(0, 995, "Coins", 1_000_000));
		writeFixtureSnapshot(TEST_ACCOUNT_HASH, accountAItems, Instant.now().minus(Duration.ofMinutes(2)));
		// OTHER_ACCOUNT_HASH deliberately has nothing written at all.

		BankSnapshotBaseline accountABaseline = BankSnapshotBaseline.loadFrom(store, TEST_ACCOUNT_HASH);
		BankSnapshotBaseline accountBBaseline = BankSnapshotBaseline.loadFrom(store, OTHER_ACCOUNT_HASH);

		assertTrue("account A has a real persisted baseline", accountABaseline.isPresent());
		assertFalse("account B must never see account A's baseline", accountBBaseline.isPresent());

		// And account B's first observation must always write, exactly
		// as if it were genuinely the first-ever observation — which,
		// from account B's perspective, it is.
		assertTrue(BankSnapshotBaseline.shouldWriteNewSnapshot(
			accountBBaseline, accountAItems, Instant.now(), MAX_AGE));
	}
}
