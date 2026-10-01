package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.LocationPolicy;
import com.osrstelemetry.plugin.activity.RecognitionState;
import com.osrstelemetry.plugin.activity.StructuredActivityDefinition;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredActivityTracker;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integrity audit: a count-less completion (Pest Control) reaching us twice
 * for one game is one game; recognition stops accumulating once LOCKED.
 */
public class StructuredCompletionDedupeTest
{
	private static final long ACCOUNT = 700_900_902L;
	private static final String PEST_WIN = "Congratulations! You managed to destroy all the portals! We've awarded you 4 Void Knight Commendation points.";

	private Path testRoot;
	private EventLedger ledger;
	private StructuredActivityCollector collector;
	private final List<EventType> events = new ArrayList<>();
	private int tickCount;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("structured-completion-dedupe-test");
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
					case "getTickCount":
						return tickCount;
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
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private int completions()
	{
		int n = 0;
		synchronized (events)
		{
			for (EventType t : events)
			{
				if (t == EventType.STRUCTURED_ACTIVITY_COMPLETION)
				{
					n++;
				}
			}
		}
		return n;
	}

	@Test
	public void sameCountlessCompletionTwiceForOneGame_isOneGame()
	{
		tickCount = 100;
		collector.onGameMessage(PEST_WIN); // game message
		collector.onGameMessage(PEST_WIN); // same text as a message box, same tick
		tickCount = 102;
		collector.onGameMessage(PEST_WIN); // still the same game
		assertEquals(1, completions());
	}

	@Test
	public void separateGames_eachCount()
	{
		tickCount = 100;
		collector.onGameMessage(PEST_WIN);
		tickCount = 400; // a whole later game
		collector.onGameMessage(PEST_WIN);
		assertEquals(2, completions());
	}

	@Test
	public void resetForgetsTheLastCountlessCompletion()
	{
		tickCount = 100;
		collector.onGameMessage(PEST_WIN);
		collector.resetForAccountSwitch();
		collector.onGameMessage(PEST_WIN);
		assertEquals(2, completions());
	}

	@Test
	public void lockedRecognition_stopsRememberingTicks()
	{
		StructuredActivityDefinition pest = StructuredActivityRegistry.defaults().byKey("pest_control");
		StructuredActivityTracker tracker = new StructuredActivityTracker();
		tracker.onLocation(pest, LocationPolicy.EXCLUSIVE);
		for (long tick = 1; tick <= 50; tick++)
		{
			tracker.confirm(tick);
		}
		assertEquals(RecognitionState.LOCKED, tracker.getState());
		assertEquals(StructuredActivityTracker.LOCK_DISTINCT_CONFIRMATIONS, tracker.getDistinctConfirmations());
		// Leaving and coming back still re-arms from scratch.
		tracker.onLocation(null, null);
		tracker.onLocation(pest, LocationPolicy.EXCLUSIVE);
		assertEquals(RecognitionState.ARMED, tracker.getState());
		assertEquals(0, tracker.getDistinctConfirmations());
	}
}
