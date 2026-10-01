package com.osrstelemetry.plugin.session;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.After;
import org.junit.Before;

/**
 * Shared fixture for the Smart Slayer coordinator tests: a real
 * SessionRuntimeCoordinator on a real LocalStateStore rooted in a fresh
 * temp directory, driven one closed game tick per {@link #feed} call.
 */
abstract class SlayerCoordinatorTestSupport
{
	protected static final long ACCOUNT = 700_900_300L;
	protected static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	protected static final Gson GSON = new Gson();
	protected static final String NECH = "Nechryael";
	protected static final String NECH_LOC = "Catacombs of Kourend";
	protected static final String KALPHITES = "Kalphites";
	protected static final String KALPHITE_LOC = "Kalphite Lair";

	protected LocalStateStore store;
	protected SessionRuntimeCoordinator coordinator;
	protected Path testRoot;
	protected long tick;

	@Before
	public void setUpCoordinator() throws Exception
	{
		testRoot = Files.createTempDirectory("smart-slayer-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		store = new LocalStateStore(GSON);
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
		coordinator.ensureAccountLoaded(ACCOUNT);
		tick = 1L;
	}

	@After
	public void tearDownCoordinator() throws Exception
	{
		if (store != null)
		{
			store.shutdown();
		}
		if (testRoot != null)
		{
			TestFilepaths.rooted(testRoot).deleteRecursively();
		}
	}

	/** Simulates a plugin restart on the same storage: fresh store + coordinator, same account. */
	protected void restartPlugin()
	{
		store.shutdown();
		store = new LocalStateStore(GSON);
		store.start();
		coordinator = new SessionRuntimeCoordinator(store);
		coordinator.ensureAccountLoaded(ACCOUNT);
	}

	/** One signal as its own closed game tick. */
	protected void feed(SessionSignal signal, Instant at)
	{
		coordinator.testEnqueueSignal(signal.withGameTick(tick));
		coordinator.testProcessTick(++tick, at);
	}

	/** XP window [windowStart, windowStart+30s], delivered at the window's end. */
	protected void xp(String skill, long delta, Instant windowStart)
	{
		Instant end = windowStart.plusSeconds(30);
		feed(SessionSignal.xpChange(end, skill, delta, windowStart), end);
	}

	protected Session current()
	{
		return coordinator.testCurrentSession();
	}

	protected CurrentSessionSnapshot ui(Instant now)
	{
		return CurrentSessionSnapshot.from(coordinator.getCurrentSessionSnapshot(), now);
	}
}
