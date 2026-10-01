package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.BarrowsBrother;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The Barrows current run is the game's own brother varbits, mirrored for
 * presentation: read on login (so a run in progress is shown as it really
 * is), updated on each brother varbit change, cleared on logout / account
 * switch, and never written to the event log.
 */
public class BarrowsProgressCollectorTest
{
	private static final long ACCOUNT = 700_900_905L;

	private Path testRoot;
	private EventLedger ledger;
	private StructuredActivityCollector collector;
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final List<EventType> events = new ArrayList<>();
	private final AtomicInteger changes = new AtomicInteger();
	private GameState gameState = GameState.LOGGED_IN;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("barrows-progress-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		ledger = new EventLedger(new Gson());
		ledger.start();
		ledger.addListener((TelemetryEventListener) (account, type, payload, at) ->
		{
			synchronized (events)
			{
				events.add(type);
			}
		});
		Client client = (Client) java.lang.reflect.Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getAccountHash":
						return ACCOUNT;
					case "getGameState":
						return gameState;
					case "getVarbitValue":
						return varbits.getOrDefault((Integer) args[0], 0);
					default:
						Class<?> rt = method.getReturnType();
						if (rt == boolean.class)
						{
							return false;
						}
						if (rt.isPrimitive() && rt != void.class)
						{
							return rt == long.class ? (Object) 0L : (Object) 0;
						}
						return null;
				}
			});
		collector = new StructuredActivityCollector(client, id -> "item" + id, ledger, StructuredActivityRegistry.defaults());
		collector.setProgressListener(changes::incrementAndGet);
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private void varbit(BarrowsBrother brother, int value)
	{
		varbits.put(brother.getKilledVarbit(), value);
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(brother.getKilledVarbit());
		e.setValue(value);
		collector.onVarbitChanged(e);
	}

	@Test
	public void unknownUntilRead_thenMidRunAttachShowsTheRealBrothers()
	{
		assertNull(collector.getBarrowsProgress());
		varbits.put(BarrowsBrother.AHRIM.getKilledVarbit(), 1);
		varbits.put(BarrowsBrother.KARIL.getKilledVarbit(), 1);
		collector.onGameTick(new GameTick());
		assertNotNull(collector.getBarrowsProgress());
		assertEquals("mid-run: exactly what the game says, nothing fabricated", 2, collector.getBarrowsProgress().getClearedCount());
		assertEquals(1, changes.get());
		collector.onGameTick(new GameTick());
		assertEquals("no re-read churn once known", 1, changes.get());
	}

	@Test
	public void brotherKills_countUp_duplicatesDoNotDoubleCount_resetStartsANewRun()
	{
		collector.onGameTick(new GameTick());
		assertEquals(0, collector.getBarrowsProgress().getClearedCount());
		varbit(BarrowsBrother.DHAROK, 1);
		assertEquals(1, collector.getBarrowsProgress().getClearedCount());
		varbit(BarrowsBrother.DHAROK, 1); // same evidence again
		assertEquals(1, collector.getBarrowsProgress().getClearedCount());
		int changesAfterDuplicate = changes.get();
		for (BarrowsBrother b : BarrowsBrother.values())
		{
			varbit(b, 1);
		}
		assertEquals(6, collector.getBarrowsProgress().getClearedCount());
		assertEquals("one change per real transition", changesAfterDuplicate + 5, changes.get());
		for (BarrowsBrother b : BarrowsBrother.values())
		{
			varbit(b, 0); // the game resets the run after the chest
		}
		assertEquals(0, collector.getBarrowsProgress().getClearedCount());
	}

	@Test
	public void unrelatedVarbits_areIgnored()
	{
		collector.onGameTick(new GameTick());
		int before = changes.get();
		VarbitChanged e = new VarbitChanged();
		e.setVarbitId(463); // monster points: not a brother
		e.setValue(40);
		collector.onVarbitChanged(e);
		assertEquals(before, changes.get());
	}

	@Test
	public void logoutAndAccountSwitch_forgetTheRun_andNothingIsWrittenToTheLedger()
	{
		collector.onGameTick(new GameTick());
		varbit(BarrowsBrother.TORAG, 1);
		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		gameState = GameState.LOGIN_SCREEN;
		collector.onGameStateChanged(logout);
		assertNull(collector.getBarrowsProgress());
		collector.onGameTick(new GameTick());
		assertNull("not re-read while logged out", collector.getBarrowsProgress());

		gameState = GameState.LOGGED_IN;
		collector.onGameTick(new GameTick());
		assertEquals(1, collector.getBarrowsProgress().getClearedCount());
		collector.resetForAccountSwitch();
		assertNull(collector.getBarrowsProgress());
		synchronized (events)
		{
			assertEquals("presentation state only: no events", 0, events.size());
		}
	}

	@Test
	public void aThrowingListener_neverBreaksCollection()
	{
		collector.setProgressListener(() ->
		{
			throw new IllegalStateException("ui bug");
		});
		collector.onGameTick(new GameTick());
		varbit(BarrowsBrother.VERAC, 1);
		assertEquals(1, collector.getBarrowsProgress().getClearedCount());
	}
}
