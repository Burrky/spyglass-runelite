package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.A;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.ACCOUNT;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.B;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.C;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.D;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.OTHER_ACCOUNT;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HashTable;
import net.runelite.api.Player;
import net.runelite.api.WidgetNode;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The live adapter against a proxy {@link Client}: eligibility before reads,
 * immediate lifecycle interruption, ownership, startup file safety, enabling
 * while logged in, shutdown / re-enable, and the developer diagnostic.
 */
public class FarmingCaptureAdapterTest
{
	private static final Gson GSON = new Gson();
	private static final WorldPoint FALADOR = new WorldPoint(3055, 3307, 0);
	private static final WorldPoint NOWHERE = new WorldPoint(3100, 3500, 0);
	private static final Set<Integer> FALADOR_VARBITS = new HashSet<>(Arrays.asList(A, B, C, D));

	/** Mutable fake client state behind a {@link Client} proxy. */
	private static final class Fake
	{
		GameState state = GameState.LOGGED_IN;
		int tick = 100;
		long account = ACCOUNT;
		int world = 330;
		EnumSet<WorldType> types = EnumSet.of(WorldType.MEMBERS);
		boolean welcome;
		boolean modal;
		boolean instance;
		boolean player = true;
		WorldPoint location = FALADOR;
		final Map<Integer, Integer> varbits = new HashMap<>();
		final List<Integer> reads = new ArrayList<>();
		final List<String> chat = new ArrayList<>();
		int tickCountCalls;
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getGameState":
					return state;
				case "getTickCount":
					tickCountCalls++;
					return tick;
				case "getAccountHash":
					return account;
				case "getWorld":
					return world;
				case "getWorldType":
					return EnumSet.copyOf(types.isEmpty() ? EnumSet.noneOf(WorldType.class) : types);
				case "getWidget":
					return welcome ? proxy(Widget.class, (wp, wm, wa) -> wm.getName().equals("isHidden") ? false : dflt(wm.getReturnType())) : null;
				case "getComponentTable":
					List<WidgetNode> nodes = modal
						? Collections.singletonList(proxy(WidgetNode.class, (np, nm, na) -> nm.getName().equals("getModalMode") ? WidgetModalMode.MODAL_NOCLICKTHROUGH : dflt(nm.getReturnType())))
						: Collections.emptyList();
					return proxy(HashTable.class, (hp, hm, ha) -> hm.getName().equals("iterator") ? nodes.iterator() : dflt(hm.getReturnType()));
				case "getTopLevelWorldView":
					return proxy(WorldView.class, (vp, vm, va) -> vm.getName().equals("isInstance") ? instance : dflt(vm.getReturnType()));
				case "getLocalPlayer":
					return player ? proxy(Player.class, (pp, pm, pa) -> pm.getName().equals("getWorldLocation") ? location : dflt(pm.getReturnType())) : null;
				case "getVarbitValue":
					reads.add((Integer) a[0]);
					return varbits.getOrDefault((Integer) a[0], 0);
				case "addChatMessage":
					chat.add((String) a[2]);
					return null;
				default:
					return dflt(m.getReturnType());
			}
		});

		void falador(int herb)
		{
			varbits.put(A, 3);
			varbits.put(B, 3);
			varbits.put(C, 3);
			varbits.put(D, herb);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
	}

	private static Object dflt(Class<?> t)
	{
		if (t == boolean.class)
		{
			return false;
		}
		if (t == int.class || t == short.class || t == byte.class || t == char.class)
		{
			return t == char.class ? (Object) (char) 0 : (Object) 0;
		}
		if (t == long.class)
		{
			return 0L;
		}
		if (t == float.class)
		{
			return 0f;
		}
		if (t == double.class)
		{
			return 0d;
		}
		return null;
	}

	private Path root;
	private Filepath rootPath;
	private LocalStateStore stateStore;
	private Fake fake;
	private FarmingCaptureAdapter adapter;

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("farming-adapter");
		rootPath = TestFilepaths.rooted(root);
		stateStore = new LocalStateStore(GSON);
		stateStore.start();
		fake = new Fake();
		fake.falador(33);
		adapter = newAdapter(true);
	}

	private FarmingCaptureAdapter newAdapter(boolean developerMode)
	{
		FarmingCaptureAdapter a = new FarmingCaptureAdapter(fake.client, GSON, stateStore, developerMode);
		a.dirs = owner ->
		{
			Filepath dir = rootPath.joinSegment(Long.toString(owner.getAccountHash())).joinSegment("profiles")
				.joinSegment(owner.getProfileKey()).joinSegment("farming");
			try
			{
				dir.createDirectories();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
			return dir;
		};
		a.clock = () -> T0 + fake.tick * 600L;
		return a;
	}

	@After
	public void tearDown() throws Exception
	{
		adapter.shutDown();
		stateStore.shutdown();
		rootPath.deleteRecursively();
	}

	private FarmingObserverStep.Status tick()
	{
		fake.tick++;
		adapter.onGameTick(new GameTick());
		return adapter.live().lastStatus;
	}

	private void event(GameState state)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		adapter.onGameStateChanged(e);
	}

	/** Ticks until the first CAPTURED tick, asserting no farming read happened before it. */
	private void tickUntilCaptured()
	{
		for (int i = 0; i < 10; i++)
		{
			fake.reads.clear();
			if (tick() == FarmingObserverStep.Status.CAPTURED)
			{
				return;
			}
			assertTrue("no farming read before capture", fake.reads.isEmpty());
		}
		throw new AssertionError("never captured");
	}

	private void awaitLoaded() throws InterruptedException
	{
		for (int i = 0; i < 200 && !adapter.live().store.isLoaded(); i++)
		{
			Thread.sleep(10);
		}
		assertTrue("the owner's files loaded", adapter.live().store.isLoaded());
	}

	private List<Path> files() throws Exception
	{
		try (Stream<Path> s = Files.walk(root))
		{
			return s.filter(Files::isRegularFile).collect(Collectors.toList());
		}
	}

	// -- eligibility before reads -------------------------------------------------

	@Test
	public void blockedAndUnsettledTicks_readNoFarmingVarbit_andEligibleTicksReadOnlyThePlannedOnes()
	{
		adapter.start();
		Runnable[] blockers = {
			() -> fake.state = GameState.LOGIN_SCREEN,
			() -> fake.account = -1L,
			() -> fake.types = EnumSet.of(WorldType.SEASONAL),
			() -> fake.types = EnumSet.of(WorldType.TOURNAMENT_WORLD),
			() -> fake.types = EnumSet.of(WorldType.FRESH_START_WORLD),
			() -> fake.modal = true,
			() -> fake.welcome = true,
			() -> fake.player = false,
			() -> fake.instance = true,
			() -> fake.location = NOWHERE,
		};
		for (Runnable block : blockers)
		{
			Fake clean = new Fake();
			fake.state = clean.state;
			fake.account = clean.account;
			fake.types = clean.types;
			fake.modal = fake.welcome = fake.instance = false;
			fake.player = true;
			fake.location = FALADOR;
			block.run();
			fake.reads.clear();
			for (int i = 0; i < 8; i++)
			{
				assertFalse(tick() == FarmingObserverStep.Status.CAPTURED);
			}
			assertTrue("blocked ticks read nothing", fake.reads.isEmpty());
		}
		fake.state = GameState.LOGGED_IN;
		fake.account = ACCOUNT;
		fake.types = EnumSet.of(WorldType.MEMBERS);
		fake.modal = fake.welcome = fake.instance = false;
		fake.player = true;
		fake.location = FALADOR;
		tickUntilCaptured();
		Set<Integer> withBin = new HashSet<>(FALADOR_VARBITS);
		withBin.add(net.runelite.api.gameval.VarbitID.FARMING_TRANSMIT_E); // Falador's compost bin, read with its patches (canonical §28.15)
		assertEquals("exactly the Falador context's varbits and its compost bin", withBin, new HashSet<>(fake.reads));
		assertEquals(5, fake.reads.size());
	}

	@Test
	public void theAreasCompostBin_isKeptWithThePatches_onlyFromASettledRead() throws Exception
	{
		adapter.start();
		fake.state = GameState.LOGGED_IN;
		fake.account = ACCOUNT;
		fake.types = EnumSet.of(WorldType.MEMBERS);
		fake.modal = fake.welcome = fake.instance = false;
		fake.player = true;
		fake.location = FALADOR;
		fake.falador(3);
		fake.varbits.put(net.runelite.api.gameval.VarbitID.FARMING_TRANSMIT_E, 62); // 15 buckets of supercompost to take
		tickUntilCaptured();
		awaitLoaded();
		tick();
		FarmingSnapshot snap = adapter.live().store.snapshotFor(FarmingOwner.standard(ACCOUNT));
		assertEquals(62, snap.getBins().get("bin.falador").getRaw());
		assertEquals(1, snap.getBins().size());
	}

	@Test
	public void tournamentAndFreshStart_areSuspended_withAnExplicitReason()
	{
		adapter.start();
		fake.types = EnumSet.of(WorldType.MEMBERS, WorldType.TOURNAMENT_WORLD);
		for (int i = 0; i < 6; i++)
		{
			tick();
		}
		assertEquals(FarmingObserverStep.Status.WORLD_TYPE_SUSPENDED, adapter.live().lastStatus);
		assertTrue(fake.reads.isEmpty());
		assertTrue(adapter.diagnosticLines().get(1).contains("TOURNAMENT_WORLD"));
		assertNull("no owner is established there", adapter.live().store.getOwner());
	}

	// -- lifecycle -----------------------------------------------------------------

	@Test
	public void lifecycleEvents_interruptImmediately_andTheNextConsecutiveTickMustSettleAndBaselineAgain() throws Exception
	{
		adapter.start();
		for (GameState state : new GameState[]{GameState.LOGIN_SCREEN, GameState.HOPPING, GameState.LOADING, GameState.CONNECTION_LOST})
		{
			fake.falador(33);
			tickUntilCaptured();
			tick(); // captured again: same sequence
			int before = adapter.live().interruptions;
			event(state); // no tick in between
			assertEquals("interrupted at once", before + 1, adapter.live().interruptions);
			fake.falador(34); // one growth stage on, during the interruption
			fake.reads.clear();
			assertFalse(tick() == FarmingObserverStep.Status.CAPTURED); // numerically consecutive tick
			assertTrue(state + ": nothing read straight after the interruption", fake.reads.isEmpty());
			tickUntilCaptured();
			PatchObservation herb = adapter.live().lastObservations.get("herb.falador");
			assertEquals(state + ": a fresh baseline", PatchObservation.Kind.BASELINE, herb.getKind());
			assertEquals(34, herb.getReading().getRaw());
		}
		assertTrue("no growth sample across any interruption", adapter.live().store.calibrationSamples().isEmpty());
		awaitLoaded();
		PatchKnowledge k = adapter.live().store.snapshot().get("herb.falador");
		assertFalse("stage first seen, never a witnessed transition", k.getCycle().isStageTransitionObserved());
	}

	@Test
	public void aWitnessedGrowthTick_isAChangeAndASample_butATickGapIsNot() throws Exception
	{
		adapter.start();
		tickUntilCaptured();
		awaitLoaded();
		fake.falador(34);
		tick();
		assertEquals(PatchObservation.Kind.CHANGE, adapter.live().lastObservations.get("herb.falador").getKind());
		assertEquals(1, adapter.live().store.calibrationSamples().size());
		fake.tick += 5; // ticks never seen
		fake.falador(35);
		tickUntilCaptured();
		assertEquals(PatchObservation.Kind.BASELINE, adapter.live().lastObservations.get("herb.falador").getKind());
		assertEquals("still only the witnessed one", 1, adapter.live().store.calibrationSamples().size());
	}

	@Test
	public void accountSwitch_reachesTheStore_andDiagnosticsNeverShowTheOtherAccount() throws Exception
	{
		adapter.start();
		tickUntilCaptured();
		awaitLoaded();
		assertEquals(FarmingOwner.standard(ACCOUNT), adapter.live().store.getOwner());
		assertTrue(String.join("\n", adapter.diagnosticLines()).contains("patch herb.falador: raw 33"));

		event(GameState.LOGIN_SCREEN);
		fake.state = GameState.LOGIN_SCREEN;
		tick();
		assertTrue("logged out: knowledge hidden", String.join("\n", adapter.diagnosticLines()).contains("knowledge: hidden"));

		fake.state = GameState.LOGGED_IN;
		fake.account = OTHER_ACCOUNT;
		fake.falador(40);
		tick();
		assertTrue("B before its owner is established: A stays hidden", String.join("\n", adapter.diagnosticLines()).contains("knowledge: hidden"));
		tickUntilCaptured();
		assertEquals(FarmingOwner.standard(OTHER_ACCOUNT), adapter.live().store.getOwner());
		awaitLoaded();
		String dump = String.join("\n", adapter.diagnosticLines());
		assertTrue(dump.contains("raw 40"));
		assertFalse(dump.contains("raw 33"));
		adapter.shutDown();
		stateStore.shutdown();
		assertTrue("A's knowledge went to A's own folder", Files.exists(root.resolve(Long.toString(ACCOUNT)).resolve("profiles/standard/farming/patches.json")));
		assertTrue(Files.exists(root.resolve(Long.toString(OTHER_ACCOUNT)).resolve("profiles/standard/farming/patches.json")));
		stateStore.start();
	}

	@Test
	public void startup_touchesNoPlayerFile_untilAnEligibleOwnerIsEstablished() throws Exception
	{
		adapter.start();
		fake.state = GameState.LOGIN_SCREEN;
		tick();
		fake.state = GameState.LOGGED_IN;
		fake.types = EnumSet.of(WorldType.SEASONAL);
		for (int i = 0; i < 5; i++)
		{
			tick();
		}
		assertTrue("nothing written or created yet: " + files(), files().isEmpty() && !Files.exists(root.resolve(Long.toString(ACCOUNT))));
		fake.types = EnumSet.of(WorldType.MEMBERS);
		tickUntilCaptured();
		awaitLoaded();
		assertTrue(Files.isDirectory(root.resolve(Long.toString(ACCOUNT)).resolve("profiles/standard/farming")));
	}

	@Test
	public void enablingWhileAlreadyLoggedIn_capturesFromTheNextTicks_withoutALoginEvent() throws Exception
	{
		fake.state = GameState.LOGGED_IN;
		adapter.start(); // no GameStateChanged(LOGGED_IN) follows
		tickUntilCaptured();
		awaitLoaded();
		assertNotNull(adapter.live().store.snapshot().get("herb.falador"));
	}

	@Test
	public void shutdown_stopsInput_andReEnableStartsClean_withoutDuplicateSubscriptions() throws Exception
	{
		EventBus bus = new EventBus();
		adapter.start();
		bus.register(adapter);
		FarmingCaptureAdapter.Live first = adapter.live();
		bus.unregister(adapter);
		adapter.shutDown();
		assertTrue(first.loadExecutor.isShutdown());
		assertTrue(first.logExecutor.isTerminated());
		int calls = fake.tickCountCalls;
		adapter.onGameTick(new GameTick()); // a late tick after shutdown
		assertEquals("ignored", calls, fake.tickCountCalls);
		adapter.shutDown(); // idempotent

		adapter.start();
		adapter.start(); // idempotent
		bus.register(adapter);
		assertNotSame(first, adapter.live());
		calls = fake.tickCountCalls;
		fake.tick++;
		bus.post(new GameTick());
		assertEquals("one delivery per tick after re-enable", calls + 1, fake.tickCountCalls);
		bus.unregister(adapter);
	}

	@Test
	public void logout_flushesTheCurrentOwnersPendingState() throws Exception
	{
		adapter.start();
		tickUntilCaptured();
		awaitLoaded();
		fake.reads.clear();
		for (int i = 0; i < 3; i++)
		{
			tick(); // unchanged reads: freshness only, batched
		}
		assertTrue(adapter.live().store.hasPendingWrites());
		event(GameState.LOGIN_SCREEN);
		assertFalse("logout queued the write", adapter.live().store.hasPendingWrites());
	}

	// -- diagnostics ---------------------------------------------------------------

	@Test
	public void diagnosticCommand_isDeveloperModeOnly_andDescribesTheCapture() throws Exception
	{
		FarmingCaptureAdapter normal = newAdapter(false);
		normal.start();
		normal.onCommandExecuted(new CommandExecuted(FarmingCaptureAdapter.COMMAND, new String[0]));
		assertTrue("not in normal mode", fake.chat.isEmpty());
		normal.shutDown();

		adapter.start();
		tickUntilCaptured();
		awaitLoaded();
		fake.falador(34);
		tick();
		adapter.onCommandExecuted(new CommandExecuted(FarmingCaptureAdapter.COMMAND, new String[0]));
		assertEquals(1, fake.chat.size());
		assertTrue(fake.chat.get(0).contains("CAPTURED"));
		String dump = String.join("\n", adapter.diagnosticLines());
		assertTrue(dump.contains("adapter: active"));
		assertTrue(dump.contains("patches here [allotment.falador.nw, allotment.falador.se, herb.falador]")
			|| dump.contains("herb.falador"));
		assertTrue(dump.contains("patch herb.falador: raw 34 = GROWING RANARR stage 2"));
		assertTrue(dump.contains("last CHANGE"));
		assertTrue(dump.contains("transition observed"));
		assertTrue(dump.contains("calibration samples: 1"));
		assertTrue(dump.contains("files: "));
	}

	// -- UI snapshot ---------------------------------------------------------------

	@Test
	public void uiSnapshot_isOnlyEverTheCurrentEligibleOwnersLoadedKnowledge() throws Exception
	{
		adapter.start();
		assertEquals(FarmingSnapshot.State.LOGGED_OUT, adapter.uiSnapshot().getState());
		tickUntilCaptured();
		awaitLoaded();
		FarmingSnapshot a = adapter.uiSnapshot();
		assertEquals(FarmingSnapshot.State.AVAILABLE, a.getState());
		assertEquals(FarmingOwner.standard(ACCOUNT), a.getOwner());
		assertNotNull(a.getKnowledge().get("herb.falador"));

		event(GameState.HOPPING); // a hop's transient not-ready ticks keep the page steady
		tick();
		assertEquals(FarmingSnapshot.State.AVAILABLE, adapter.uiSnapshot().getState());

		fake.types = EnumSet.of(WorldType.SEASONAL);
		for (int i = 0; i < 4; i++)
		{
			tick();
		}
		FarmingSnapshot seasonal = adapter.uiSnapshot();
		assertEquals(FarmingSnapshot.State.NOT_AVAILABLE_HERE, seasonal.getState());
		assertTrue(seasonal.getKnowledge().isEmpty());

		fake.types = EnumSet.of(WorldType.MEMBERS);
		tickUntilCaptured();
		event(GameState.LOGIN_SCREEN);
		assertEquals("logout hides it at once", FarmingSnapshot.State.LOGGED_OUT, adapter.uiSnapshot().getState());

		fake.account = OTHER_ACCOUNT;
		fake.falador(40);
		for (int i = 0; i < 10; i++)
		{
			tick();
			FarmingSnapshot during = adapter.uiSnapshot();
			if (during.getOwner() != null)
			{
				assertEquals("never A's data under B", FarmingOwner.standard(OTHER_ACCOUNT), during.getOwner());
			}
			PatchKnowledge herb = during.getKnowledge().get("herb.falador");
			assertTrue(herb == null || herb.getLastRaw() == 40);
		}
		assertNull("the store refuses another owner's request", adapter.live().store.snapshotFor(FarmingOwner.standard(ACCOUNT)));
	}

	// -- runs (Phase E) ----------------------------------------------------------------

	private void awaitRunsLoaded() throws InterruptedException
	{
		for (int i = 0; i < 200 && !adapter.live().runs.isLoaded(); i++)
		{
			Thread.sleep(10);
		}
		assertTrue(adapter.live().runs.isLoaded());
	}

	private void drainWrites()
	{
		stateStore.writeAndWait(rootPath.joinSegment("barrier.json"), Collections.singletonMap("b", 1), 10_000);
	}

	@Test
	public void aRunStartedFromLiveKnowledge_isTrackedFromCaptures_pausesOnLogout_andSurvivesARestart() throws Exception
	{
		adapter.start();
		fake.falador(36); // a harvestable ranarr at Falador
		tickUntilCaptured();
		awaitLoaded();
		awaitRunsLoaded();
		tick();
		FarmingSnapshot ui = adapter.uiSnapshot();
		assertEquals(FarmingSnapshot.State.AVAILABLE, ui.getState());
		assertTrue("the patches here are known to the UI", ui.getHerePatchIds().contains("herb.falador"));
		assertNull(ui.getRun());
		Map<String, FarmingProduce> planned = new java.util.LinkedHashMap<>();
		planned.put("herb.falador", FarmingProduce.RANARR);
		planned.put("herb.catherby", FarmingProduce.RANARR);
		FarmingRunStore.StartResult r = adapter.startRun(new FarmingRunRequest(FarmingRunType.HERB, Arrays.asList("herb.falador", "herb.catherby"),
			Collections.emptyList(), planned, null, false));
		assertTrue(r.getProblems().toString(), r.isStarted());

		fake.falador(3); // harvested out
		tick();
		tick();
		assertEquals(FarmingRunEvaluator.Detail.AWAITING_PLANT, FarmingRunEvaluator.evaluate(adapter.uiSnapshot().getRun(),
			FarmingPatchCatalog.standard(), adapter.uiSnapshot().getKnowledge()).patch("herb.falador").getDetail());

		// Logout mid-run: saved as PAUSED; nothing about the run is shown while logged out.
		fake.state = GameState.LOGIN_SCREEN;
		event(GameState.LOGIN_SCREEN);
		drainWrites();
		assertNull(adapter.uiSnapshot().getRun());
		assertEquals(FarmingRunIntent.State.PAUSED, adapter.live().runs.intentFor(FarmingOwner.standard(ACCOUNT)).getState());

		// Back in: still paused (never resumed silently); the replant is still tracked.
		fake.state = GameState.LOGGED_IN;
		event(GameState.LOGGED_IN);
		fake.falador(32); // ranarr seed: replanted while the run is paused
		tickUntilCaptured();
		tick();
		FarmingRunIntent run = adapter.uiSnapshot().getRun();
		assertEquals(FarmingRunIntent.State.PAUSED, run.getState());
		assertEquals(FarmingRunEvaluator.Outcome.DONE, FarmingRunEvaluator.evaluate(run, FarmingPatchCatalog.standard(),
			adapter.uiSnapshot().getKnowledge()).patch("herb.falador").getOutcome());
		adapter.resumeRun();
		assertEquals(FarmingRunIntent.State.ACTIVE, adapter.uiSnapshot().getRun().getState());

		// Client restart: a new adapter loads the same run, paused.
		adapter.shutDown();
		drainWrites();
		adapter = newAdapter(true);
		adapter.start();
		tickUntilCaptured();
		awaitLoaded();
		awaitRunsLoaded();
		FarmingRunIntent reloaded = adapter.uiSnapshot().getRun();
		assertEquals(run.getIntentId(), reloaded.getIntentId());
		assertEquals(FarmingRunIntent.State.PAUSED, reloaded.getState());
	}

	@Test
	public void anotherAccount_neverSeesTheRun() throws Exception
	{
		adapter.start();
		tickUntilCaptured();
		awaitLoaded();
		awaitRunsLoaded();
		assertTrue(adapter.startRun(new FarmingRunRequest(FarmingRunType.HERB, Collections.singletonList("herb.falador"), Collections.emptyList(),
			Collections.singletonMap("herb.falador", FarmingProduce.RANARR), null, false)).isStarted());
		fake.state = GameState.LOGIN_SCREEN;
		event(GameState.LOGIN_SCREEN);
		fake.state = GameState.LOGGED_IN;
		fake.account = OTHER_ACCOUNT;
		event(GameState.LOGGED_IN);
		tickUntilCaptured();
		awaitLoaded();
		awaitRunsLoaded();
		tick();
		assertEquals(FarmingOwner.standard(OTHER_ACCOUNT), adapter.uiSnapshot().getOwner());
		assertNull("the other account's run is invisible", adapter.uiSnapshot().getRun());
		adapter.resumeRun();
		adapter.endRun();
		assertNull(adapter.uiSnapshot().getRun());
	}
}
