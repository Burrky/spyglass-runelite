package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Proves
 * schema backward-compatibility ("extend finalized-session schema
 * conservatively/backward-compatibly") end to end:
 *   1. an OLD sessions/{id}.json (no
 *      startingLoadout/endingLoadout field at all) still deserializes
 *      cleanly, with every pre-existing field intact and both loadout
 *      fields simply null -- never a parse failure, never a
 *      fabricated/default loadout;
 *   2. a NEW session with a real starting+ending loadout attached
 *      round-trips losslessly, both via plain Gson and via the real
 *      SessionPersistence.persistFinalized()/loadFinalized() path
 *      against the real LocalStateStore/TelemetryPaths (matching
 *      SessionPersistenceTest's own established real-file, no-Client,
 *      two-account style);
 *   3. loadFinalized() fails open (returns null, never throws) on a
 *      malformed/partial file, the same contract loadCurrent() already
 *      has, applied to the one new read method here.
 *
 * NOTE: written, not run — same no-JDK-in-this-cloud-sandbox caveat as
 * every other Java test file in this project (see SessionPersistenceTest).
 */
public class SessionLoadoutBackwardCompatibilityTest
{
	private static final long TEST_ACCOUNT_HASH = 888_777_444L;
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	private static final Gson GSON = new Gson();

	private LocalStateStore store;
	private SessionPersistence persistence;

	@Before
	public void setUp()
	{
		deleteAccountDir(TEST_ACCOUNT_HASH);
		store = new LocalStateStore();
		store.start();
		persistence = new SessionPersistence(store);
	}

	@After
	public void tearDown()
	{
		store.shutdown();
		deleteAccountDir(TEST_ACCOUNT_HASH);
	}

	private static void deleteAccountDir(long accountHash)
	{
		deleteRecursively(TelemetryPaths.accountDir(accountHash));
	}

	private static void deleteRecursively(File dir)
	{
		File[] files = dir.listFiles();
		if (files != null)
		{
			for (File f : files)
			{
				if (f.isDirectory())
				{
					deleteRecursively(f);
				}
				else
				{
					f.delete();
				}
			}
		}
	}

	private static void awaitAsyncChain() throws InterruptedException
	{
		Thread.sleep(300);
	}

	// --- 1: old-shape file (no loadout fields at all) ---

	/**
	 * Hand-authored, exactly matching the old field set --
	 * deliberately does NOT include startingLoadout/endingLoadout, the
	 * two fields added here. This is what every
	 * sessions/{id}.json written by the plugin previously
	 * actually looks like on disk today.
	 */
	private static final String OLD_SHAPE_FINALIZED_JSON = "{"
		+ "\"sessionId\":\"old-session-1\","
		+ "\"activityIdentity\":{\"activityType\":\"SLAYER\",\"activityKey\":\"gargoyles:catacombs\",\"displayName\":\"Gargoyles\"},"
		+ "\"state\":\"FINALIZED\","
		+ "\"startedAt\":\"2026-01-01T10:00:00Z\","
		+ "\"lastActiveAt\":\"2026-01-01T10:09:00Z\","
		+ "\"finalizedAt\":\"2026-01-01T10:39:00Z\","
		+ "\"accumulatedActiveDurationMillis\":540000,"
		+ "\"aggregates\":{\"xpGainedBySkill\":{\"SLAYER\":812},\"lootDrops\":[],\"slayerProgressDelta\":6}"
		+ "}";

	@Test
	public void oldShapeJson_deserializesCleanly_loadoutFieldsNullEverythingElseIntact()
	{
		Session reloaded = GSON.fromJson(OLD_SHAPE_FINALIZED_JSON, Session.class);

		assertNotNull(reloaded);
		assertEquals("old-session-1", reloaded.getSessionId());
		assertEquals(SessionState.FINALIZED, reloaded.getState());
		assertEquals(GARGOYLES, reloaded.getActivityIdentity());
		assertEquals("2026-01-01T10:00:00Z", reloaded.getStartedAt());
		assertEquals("2026-01-01T10:39:00Z", reloaded.getFinalizedAt());
		assertEquals(540000L, reloaded.getAccumulatedActiveDurationMillis());
		assertEquals(Long.valueOf(812L), reloaded.getAggregates().getXpGainedBySkill().get("SLAYER"));
		assertEquals(Integer.valueOf(6), reloaded.getAggregates().getSlayerProgressDelta());

		assertNull("an old session file has no starting loadout -- must deserialize as null, never a fabricated/default snapshot",
			reloaded.getStartingLoadout());
		assertNull("an old session file has no ending loadout -- must deserialize as null, never a fabricated/default snapshot",
			reloaded.getEndingLoadout());
	}

	@Test
	public void oldShapeFile_onRealDisk_loadsThroughSessionPersistence() throws Exception
	{
		File sessionFile = TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, "old-session-1");
		Files.write(sessionFile.toPath(), OLD_SHAPE_FINALIZED_JSON.getBytes(StandardCharsets.UTF_8));

		Session reloaded = persistence.loadFinalized(TEST_ACCOUNT_HASH, "old-session-1");

		assertNotNull("a real pre-Checkpoint-1 file on disk must still load through the new loadFinalized() method", reloaded);
		assertEquals(SessionState.FINALIZED, reloaded.getState());
		assertNull(reloaded.getStartingLoadout());
		assertNull(reloaded.getEndingLoadout());
	}

	// --- 2: new session with a real loadout round-trips losslessly ---

	private static List<StorageState.StorageItem> sampleInventory()
	{
		List<StorageState.StorageItem> items = new ArrayList<>(LoadoutSnapshot.INVENTORY_SIZE);
		items.add(new StorageState.StorageItem(0, 995, "Coins", 12_345, null));
		items.add(new StorageState.StorageItem(1, 1359, "Swordfish", 5, null));
		for (int slot = 2; slot < LoadoutSnapshot.INVENTORY_SIZE; slot++)
		{
			items.add(new StorageState.StorageItem(slot, -1, null, 0, null));
		}
		return items;
	}

	private static List<StorageState.StorageItem> sampleEquipment()
	{
		return Arrays.asList(
			new StorageState.StorageItem(0, 1163, "Bronze full helm", 1, "HEAD"),
			new StorageState.StorageItem(3, -1, null, 0, "WEAPON"),
			new StorageState.StorageItem(4, 1129, "Bronze platebody", 1, "BODY")
		);
	}

	private Session buildFinalizedSessionWithLoadout()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		Session finalized = engine.advanceTime(expiry).getFinalized();

		LoadoutSnapshot starting = new LoadoutSnapshot(
			sampleInventory(), sampleEquipment(), T0.minusSeconds(30).toString(), LoadoutProvenance.PRE_START);
		LoadoutSnapshot ending = new LoadoutSnapshot(
			sampleInventory(), sampleEquipment(), expiry.plusSeconds(5).toString(), LoadoutProvenance.FALLBACK_AT_OR_AFTER_START);

		finalized.setStartingLoadout(starting);
		finalized.setEndingLoadout(ending);
		return finalized;
	}

	@Test
	public void newSessionWithLoadout_plainGsonRoundTrip_preservesEverything()
	{
		Session original = buildFinalizedSessionWithLoadout();

		Session roundTripped = GSON.fromJson(GSON.toJson(original), Session.class);

		assertEquals(original, roundTripped);
		assertNotNull(roundTripped.getStartingLoadout());
		assertNotNull(roundTripped.getEndingLoadout());
		assertEquals(LoadoutProvenance.PRE_START, roundTripped.getStartingLoadout().getProvenance());
		assertEquals(LoadoutProvenance.FALLBACK_AT_OR_AFTER_START, roundTripped.getEndingLoadout().getProvenance());
		assertEquals(28, roundTripped.getStartingLoadout().getInventory().size());
		assertEquals(995, roundTripped.getStartingLoadout().getInventory().get(0).getItemId());
		assertEquals(-1, roundTripped.getStartingLoadout().getInventory().get(2).getItemId());
		assertEquals(3, roundTripped.getStartingLoadout().getEquipment().size());
		assertEquals("HEAD", roundTripped.getStartingLoadout().getEquipment().get(0).getEquipmentSlot());
	}

	@Test
	public void newSessionWithLoadout_realPersistFinalizedThenLoadFinalized_preservesLoadout() throws Exception
	{
		Session original = buildFinalizedSessionWithLoadout();

		persistence.persistFinalized(TEST_ACCOUNT_HASH, original, null);
		awaitAsyncChain();

		Session reloaded = persistence.loadFinalized(TEST_ACCOUNT_HASH, original.getSessionId());

		assertNotNull(reloaded);
		assertEquals(original.getSessionId(), reloaded.getSessionId());
		assertNotNull("starting loadout must survive a real write-then-read cycle", reloaded.getStartingLoadout());
		assertEquals(LoadoutProvenance.PRE_START, reloaded.getStartingLoadout().getProvenance());
		assertEquals(28, reloaded.getStartingLoadout().getInventory().size());
		assertEquals(1359, reloaded.getStartingLoadout().getInventory().get(1).getItemId());
		assertNotNull("ending loadout must survive a real write-then-read cycle", reloaded.getEndingLoadout());
		assertEquals(LoadoutProvenance.FALLBACK_AT_OR_AFTER_START, reloaded.getEndingLoadout().getProvenance());
	}

	@Test
	public void sessionWithUnavailableLoadout_roundTripsAsExplicitUnavailable_neverNullAfterBeingSet()
	{
		Session original = buildFinalizedSessionWithLoadout();
		original.setEndingLoadout(LoadoutSnapshot.unavailable());

		Session roundTripped = GSON.fromJson(GSON.toJson(original), Session.class);

		assertNotNull("an explicitly-set unavailable() loadout is a real value, not the same as an old file's null",
			roundTripped.getEndingLoadout());
		assertEquals(LoadoutProvenance.UNAVAILABLE, roundTripped.getEndingLoadout().getProvenance());
		assertTrue(roundTripped.getEndingLoadout().getInventory().isEmpty());
		assertTrue(roundTripped.getEndingLoadout().getEquipment().isEmpty());
	}

	// --- 3: loadFinalized() fails open on a malformed/partial file ---

	@Test
	public void loadFinalized_malformedFile_returnsNullRatherThanThrowing() throws Exception
	{
		File sessionFile = TelemetryPaths.sessionFile(TEST_ACCOUNT_HASH, "corrupt-session");
		Files.write(sessionFile.toPath(), "{ this is not valid json".getBytes(StandardCharsets.UTF_8));

		Session reloaded = persistence.loadFinalized(TEST_ACCOUNT_HASH, "corrupt-session");

		assertNull("a malformed session file must fail open (null), never throw and never crash a caller iterating many sessions",
			reloaded);
	}

	@Test
	public void loadFinalized_missingFile_returnsNull()
	{
		assertNull(persistence.loadFinalized(TEST_ACCOUNT_HASH, "does-not-exist"));
	}

	@Test
	public void differentAccounts_neverSeeEachOthersLoadoutData() throws Exception
	{
		long otherAccountHash = 888_777_555L;
		deleteAccountDir(otherAccountHash);
		try
		{
			Session original = buildFinalizedSessionWithLoadout();
			persistence.persistFinalized(TEST_ACCOUNT_HASH, original, null);
			awaitAsyncChain();

			Session crossAccountRead = persistence.loadFinalized(otherAccountHash, original.getSessionId());
			assertNull("a finalized session (and its loadout) must never be visible under a different account's hash",
				crossAccountRead);
		}
		finally
		{
			deleteAccountDir(otherAccountHash);
		}
	}
}
