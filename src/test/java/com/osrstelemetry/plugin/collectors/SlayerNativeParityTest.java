package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.plugins.slayer.SlayerConfig;
import net.runelite.client.plugins.slayer.SlayerPlugin;
import net.runelite.client.plugins.slayer.SlayerPluginService;
import org.junit.Before;
import org.junit.Test;

/**
 * PARITY ORACLE for the native Slayer source migration.
 *
 * Drives RuneLite's REAL SlayerPlugin (the resolved 1.13.1 dependency) and
 * its real SlayerPluginService, and Spyglass's SlayerNativeTaskReader,
 * against the SAME fake game state (one java.lang.reflect.Proxy Client
 * serving varps, varbits and the Slayer cache tables), then asserts both
 * report the same task name, location, remaining and initial amount.
 *
 * TEST SCOPE ONLY: production Spyglass must never depend on the Slayer
 * plugin (see SlayerNativeIndependenceTest). Using the plugin here as an
 * oracle is what turns "old source == new source" into an executed check
 * against RuneLite's own code rather than against a transcription of it,
 * and keeps guarding against future RuneLite changes. Reflection is used
 * only to wire the plugin's injected fields without a Guice injector.
 */
public class SlayerNativeParityTest
{
	private static final int GARGOYLES_TARGET = 46;
	private static final int GARGOYLES_ROW = 9046;
	private static final int ABERRANT_TARGET = 41;
	private static final int ABERRANT_ROW = 9041;
	private static final int BOSS_ID_KRAKEN = 3;
	private static final int BOSS_SUBLIST_ROW = 9500;
	private static final int KRAKEN_ROW = 9300;
	private static final int AREA_TOWER = 12;
	private static final int AREA_TOWER_ROW = 9612;
	private static final int AREA_CATACOMBS = 30;
	private static final int AREA_CATACOMBS_ROW = 9630;

	/** Mutable fake game state served through the Client proxy. */
	static final class FakeGame
	{
		final Map<Integer, Integer> varps = new HashMap<>();
		final Map<Integer, Integer> varbits = new HashMap<>();
		final Map<String, List<Integer>> rowsByValue = new HashMap<>();
		final Map<String, Object[]> fields = new HashMap<>();
		int tick;
		long accountHash = 1234L;

		void row(int table, int column, Object value, int row)
		{
			rowsByValue.put(table + ":" + column + ":" + value, Collections.singletonList(row));
		}

		void field(int row, int column, Object value)
		{
			fields.put(row + ":" + column, new Object[]{value});
		}

		Client client()
		{
			return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (proxy, m, args) ->
			{
				switch (m.getName())
				{
					case "getVarpValue":
						return varps.getOrDefault((Integer) args[0], 0);
					case "getVarbitValue":
						return args.length == 1 ? varbits.getOrDefault((Integer) args[0], 0) : 0;
					case "getDBRowsByValue":
						return rowsByValue.getOrDefault(args[0] + ":" + args[1] + ":" + args[3], Collections.emptyList());
					case "getDBTableField":
						return fields.getOrDefault(args[0] + ":" + args[1], new Object[0]);
					case "getNpcs":
						return Collections.emptyList();
					case "isClientThread":
						return true;
					case "getTickCount":
						return tick;
					case "getAccountHash":
						return accountHash;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					case "toString":
						return "FakeClient";
					default:
						return defaultValue(m.getReturnType());
				}
			});
		}

		void setTask(int count, int target, int bossId, int area, int original)
		{
			varps.put(VarPlayerID.SLAYER_COUNT, count);
			varps.put(VarPlayerID.SLAYER_TARGET, target);
			varbits.put(VarbitID.SLAYER_TARGET_BOSSID, bossId);
			varps.put(VarPlayerID.SLAYER_AREA, area);
			varps.put(VarPlayerID.SLAYER_COUNT_ORIGINAL, original);
		}
	}

	static Object defaultValue(Class<?> type)
	{
		if (type == boolean.class)
		{
			return false;
		}
		if (type == int.class || type == short.class || type == byte.class || type == char.class)
		{
			return type == char.class ? (Object) '\0' : (Object) 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == double.class)
		{
			return 0d;
		}
		return null;
	}

	/** RuneLite's real Slayer plugin + its published service, wired without Guice. */
	static final class RuneLiteSlayer
	{
		final SlayerPlugin plugin = new SlayerPlugin();
		final SlayerPluginService service;
		private final Method updateTask;
		private final Field loginFlag;

		RuneLiteSlayer(Client client) throws Exception
		{
			set(plugin, "client", client);
			set(plugin, "config", Proxy.newProxyInstance(SlayerConfig.class.getClassLoader(), new Class<?>[]{SlayerConfig.class},
				(p, m, a) -> defaultValue(m.getReturnType())));
			// No injected Client => RS-profile config writes are a logged no-op.
			set(plugin, "configManager", allocate(ConfigManager.class));
			NpcOverlayService overlays = allocate(NpcOverlayService.class);
			ClientThread clientThread = new ClientThread();
			set(clientThread, "client", client);
			set(overlays, "client", client);
			set(overlays, "clientThread", clientThread);
			set(overlays, "highlightFunctions", new ArrayList<>());
			set(overlays, "highlightedNpcs", new HashMap<>());
			set(plugin, "npcOverlayService", overlays);

			Class<?> implClass = Class.forName("net.runelite.client.plugins.slayer.SlayerPluginServiceImpl");
			Constructor<?> ctor = implClass.getDeclaredConstructor(SlayerPlugin.class);
			ctor.setAccessible(true);
			service = (SlayerPluginService) ctor.newInstance(plugin);

			updateTask = SlayerPlugin.class.getDeclaredMethod("updateTask");
			updateTask.setAccessible(true);
			loginFlag = SlayerPlugin.class.getDeclaredField("loginFlag");
			loginFlag.setAccessible(true);
		}

		/** RuneLite's login/startUp sync path (loginFlag set), then the game tick that clears it. */
		void loginSync() throws Exception
		{
			loginFlag.setBoolean(plugin, true);
			updateTask.invoke(plugin);
			loginFlag.setBoolean(plugin, false);
		}

		/** RuneLite's VarbitChanged -> invokeLater(updateTask) path. */
		void varbitUpdate() throws Exception
		{
			updateTask.invoke(plugin);
		}

		/** Exactly how SlayerCollector.resolveCurrent() reads the service. */
		String taskName()
		{
			String name = service.getTask();
			return name == null || name.isEmpty() ? null : name;
		}

		int remaining()
		{
			return Math.max(service.getRemainingAmount(), 0);
		}

		private static void set(Object target, String name, Object value) throws Exception
		{
			Class<?> c = target.getClass();
			while (c != null)
			{
				try
				{
					Field f = c.getDeclaredField(name);
					f.setAccessible(true);
					f.set(target, value);
					return;
				}
				catch (NoSuchFieldException e)
				{
					c = c.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		}

		@SuppressWarnings("unchecked")
		private static <T> T allocate(Class<T> type) throws Exception
		{
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field f = unsafeClass.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			Object unsafe = f.get(null);
			return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
		}
	}

	private FakeGame game;
	private Client client;
	private RuneLiteSlayer runelite;
	private SlayerNativeTaskReader reader;

	@Before
	public void setUp() throws Exception
	{
		game = new FakeGame();
		game.row(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, GARGOYLES_TARGET, GARGOYLES_ROW);
		game.field(GARGOYLES_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Gargoyles");
		game.row(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, ABERRANT_TARGET, ABERRANT_ROW);
		game.field(ABERRANT_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Aberrant spectres");
		game.row(DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, BOSS_ID_KRAKEN, BOSS_SUBLIST_ROW);
		game.field(BOSS_SUBLIST_ROW, DBTableID.SlayerTaskSublist.COL_TASK, KRAKEN_ROW);
		game.field(KRAKEN_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, "Kraken");
		game.row(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, AREA_TOWER, AREA_TOWER_ROW);
		game.field(AREA_TOWER_ROW, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, "Slayer Tower");
		game.row(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, AREA_CATACOMBS, AREA_CATACOMBS_ROW);
		game.field(AREA_CATACOMBS_ROW, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, "the Catacombs of Kourend");
		client = game.client();
		runelite = new RuneLiteSlayer(client);
		reader = new SlayerNativeTaskReader(client);
	}

	/** Null when the service-derived fields match the native task, else which field differs. */
	static String parityMismatch(String serviceName, String serviceLocation, int serviceRemaining, int serviceInitial,
		SlayerNativeTask nativeTask)
	{
		if (nativeTask.isUnresolved())
		{
			return "native unresolved";
		}
		String nativeName = nativeTask.isActive() ? nativeTask.getTaskName() : null;
		if (!java.util.Objects.equals(serviceName, nativeName))
		{
			return "task name";
		}
		if (serviceName == null)
		{
			return null;
		}
		if (!java.util.Objects.equals(serviceLocation, nativeTask.getTaskLocation()))
		{
			return "location";
		}
		if (serviceRemaining != nativeTask.getRemaining())
		{
			return "remaining";
		}
		if (serviceInitial != nativeTask.getInitialAmount())
		{
			return "initial amount";
		}
		return null;
	}

	private void assertParity(String step)
	{
		SlayerNativeTask nativeTask = reader.read();
		assertNull(step + ": service=" + runelite.taskName() + "/" + runelite.service.getTaskLocation() + "/"
				+ runelite.remaining() + "/" + runelite.service.getInitialAmount() + " native=" + nativeTask,
			parityMismatch(runelite.taskName(), runelite.service.getTaskLocation(),
				runelite.remaining(), runelite.taskName() == null ? 0 : runelite.service.getInitialAmount(), nativeTask));
	}

	@Test
	public void loginAlreadyOnTask_noLocation()
	{
		run(() ->
		{
			game.setTask(137, GARGOYLES_TARGET, 0, 0, 180);
			runelite.loginSync();
			assertParity("login on task");
			assertEquals("Gargoyles", reader.read().getTaskName());
		});
	}

	@Test
	public void loginAwaitingTask_bothReportNoTask()
	{
		run(() ->
		{
			game.setTask(0, 0, 0, 0, 0);
			runelite.loginSync();
			assertParity("login awaiting task");
			assertNull(reader.read().getTaskName());
		});
	}

	@Test
	public void locationTask_sameAreaStringAsRuneLite()
	{
		run(() ->
		{
			game.setTask(90, ABERRANT_TARGET, 0, AREA_CATACOMBS, 120);
			runelite.loginSync();
			assertParity("location task");
			assertEquals("the Catacombs of Kourend", reader.read().getTaskLocation());
		});
	}

	@Test
	public void ordinaryAndMultiUnitDecrements_thenFinalKill_thenCompletion()
	{
		run(() ->
		{
			game.setTask(5, GARGOYLES_TARGET, 0, AREA_TOWER, 180);
			runelite.loginSync();
			assertParity("start");
			int[] counts = {4, 2, 1, 0};
			for (int c : counts)
			{
				game.varps.put(VarPlayerID.SLAYER_COUNT, c);
				runelite.varbitUpdate();
				assertParity("remaining " + c);
			}
			assertEquals(SlayerNativeTask.Status.NO_TASK, reader.read().getStatus());
			// Duplicate / same-tick repeat of the completed state stays parity.
			runelite.varbitUpdate();
			assertParity("duplicate zero");
		});
	}

	@Test
	public void directReplacementAtoB_andRapidNextAssignmentAfterCompletion()
	{
		run(() ->
		{
			game.setTask(40, GARGOYLES_TARGET, 0, AREA_TOWER, 180);
			runelite.loginSync();
			game.setTask(150, ABERRANT_TARGET, 0, AREA_CATACOMBS, 150);
			runelite.varbitUpdate();
			assertParity("direct A->B");
			game.varps.put(VarPlayerID.SLAYER_COUNT, 0);
			runelite.varbitUpdate();
			game.setTask(170, GARGOYLES_TARGET, 0, 0, 170);
			runelite.varbitUpdate();
			assertParity("rapid next assignment");
		});
	}

	@Test
	public void bossTask_resolvesThroughBossSublistOnBothSides()
	{
		run(() ->
		{
			game.setTask(8, SlayerNativeTaskResolver.BOSS_TASK_TARGET, BOSS_ID_KRAKEN, 0, 8);
			runelite.loginSync();
			assertParity("boss task");
			assertEquals("Kraken", reader.read().getTaskName());
			game.varps.put(VarPlayerID.SLAYER_COUNT, 7);
			runelite.varbitUpdate();
			assertParity("boss kill");
		});
	}

	@Test
	public void quantityModifier_appliedIdentically()
	{
		run(() ->
		{
			game.setTask(100, GARGOYLES_TARGET, 0, 0, 150);
			game.varbits.put(VarbitID.SLAYER_MODIFIER_ID, 2);
			game.varbits.put(VarbitID.SLAYER_MODIFIER_VALUE, 30);
			game.varbits.put(VarbitID.SLAYER_MODIFIER_NEGATIVE, 1);
			runelite.loginSync();
			assertParity("negative modifier");
			assertEquals(120, reader.read().getInitialAmount());
		});
	}

	@Test
	public void missingCacheRow_runeliteKeepsPreviousState_nativeIsUnresolvedNotNoTask()
	{
		run(() ->
		{
			game.setTask(30, GARGOYLES_TARGET, 0, 0, 180);
			runelite.loginSync();
			game.setTask(25, 999, 0, 0, 25);
			runelite.varbitUpdate();
			assertEquals("RuneLite kept its previous task", "Gargoyles", runelite.taskName());
			assertTrue(reader.read().isUnresolved());
		});
	}

	/**
	 * The one documented divergence: RuneLite refreshes initialAmount only
	 * when the task name/location changes (or on a login sync). If
	 * SLAYER_COUNT_ORIGINAL arrives after the task was first resolved,
	 * RuneLite keeps the stale 0 while the native reader reports the real
	 * value -- the exact login transient SlayerCollector's
	 * preserveTrustworthyInitialAmount() was written around.
	 */
	@Test
	public void lateOriginalCount_nativeIsCurrent_runeliteStaysStale()
	{
		run(() ->
		{
			game.setTask(116, GARGOYLES_TARGET, 0, 0, 0);
			runelite.loginSync();
			assertParity("before original syncs");
			game.varps.put(VarPlayerID.SLAYER_COUNT_ORIGINAL, 137);
			runelite.varbitUpdate();
			assertEquals("RuneLite stays stale", 0, runelite.service.getInitialAmount());
			assertEquals("native reflects the varp", 137, reader.read().getInitialAmount());
			assertEquals("initial amount", parityMismatch(runelite.taskName(),
				runelite.service.getTaskLocation(), runelite.remaining(), runelite.service.getInitialAmount(), reader.read()));
		});
	}

	@Test
	public void accountSwitch_newAccountWithoutTask_bothClear()
	{
		run(() ->
		{
			game.setTask(60, GARGOYLES_TARGET, 0, AREA_TOWER, 180);
			runelite.loginSync();
			game.setTask(0, 0, 0, 0, 0);
			runelite.loginSync();
			assertParity("switched to account with no task");
		});
	}

	private interface Step
	{
		void run() throws Exception;
	}

	private static void run(Step step)
	{
		try
		{
			step.run();
		}
		catch (RuntimeException | Error e)
		{
			throw e;
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			throw new AssertionError(e.getCause());
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}
}
