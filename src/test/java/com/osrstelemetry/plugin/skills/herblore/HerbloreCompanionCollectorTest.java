package com.osrstelemetry.plugin.skills.herblore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.PotionStorageState;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillExternalStock;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The Herblore companion through the SAME shared collector: real RuneLite
 * events in (Herblore StatChanged, inventory, bank, worn equipment, the
 * game's own Potion Storage scripts), one bounded SKILL_PRODUCTION out --
 * exact outputs, exact consumed inputs by name, per-recipe occurrences --
 * bound only to SKILLING/herblore; Potion Storage known / unknown / not
 * applicable, refreshed into the account's existing snapshot and used from
 * it on a later login without reopening anything.
 */
public class HerbloreCompanionCollectorTest
{
	private static final long ACCOUNT = 6_262L;
	private static final Gson GSON = new Gson();
	private static final int SR_ENUM = 9_001;
	private static final int PRAYER_ENUM = 9_002;
	private static final int SNAP_UNF_ENUM = 9_101;

	private final List<Object[]> events = new ArrayList<>();
	private int tick = 100;
	private long now = 1_900_000_000_000L;
	private int herbXp = 9_000_000;
	private Session current;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private final Map<Integer, Long> bank = new LinkedHashMap<>();
	private final Map<Integer, Long> worn = new LinkedHashMap<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	/** Potion Storage enum id -> doses the game's script reports. */
	private final Map<Integer, Integer> storedDoses = new HashMap<>();
	private final Map<String, Object> files = new HashMap<>();
	private int lastScriptArg;
	private int lastScriptId;
	private int scriptRuns;
	private HerbloreCompanionCollector hc;
	private LocalStateStore store;

	private Path root;

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(root).deleteRecursively();
	}

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("herblore-collector-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
		EventLedger ledger = new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload, accountHash});
			}
		};
		store = new LocalStateStore(GSON)
		{
			@Override
			public void write(Filepath target, Object document, Runnable onWritten)
			{
				files.put(target.toString(), document);
			}

			@Override
			public <T> T readIfExists(Filepath file, Class<T> type)
			{
				Object o = files.get(file.toString());
				return type.isInstance(o) ? type.cast(o) : null;
			}
		};
		varbits.put(VarbitID.MM_POTION_STORAGE_UNLOCKED, 1);
		hc = newCollector();
		hc.onStatChanged(new StatChanged(Skill.HERBLORE, herbXp, 90, 90));
		inv.put(ItemID.SNAPDRAGONVIAL, 14L);
		inv.put(ItemID.RED_SPIDERS_EGGS, 14L);
		push();
		ticks(2);
	}

	private HerbloreCompanionCollector newCollector()
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getAccountHash":
					return ACCOUNT;
				case "getTickCount":
					return tick;
				case "getGameState":
					return GameState.LOGGED_IN;
				case "getSkillExperience":
				case "getRealSkillLevel":
					return 0;
				case "getVarbitValue":
					return varbits.getOrDefault((Integer) a[0], 0);
				case "getVarpValue":
					return 0;
				case "getItemContainer":
					int id = (Integer) a[0];
					return id == InventoryID.INV ? container(inv) : id == InventoryID.WORN ? container(worn) : null;
				case "getEnum":
					return enumOf((Integer) a[0]);
				case "runScript":
					Object[] args = (Object[]) a[0];
					lastScriptId = (Integer) args[0];
					lastScriptArg = (Integer) args[1];
					scriptRuns++;
					return null;
				case "getIntStack":
					return new int[]{lastScriptId == ScriptID.POTIONSTORE_DOSES ? storedDoses.getOrDefault(lastScriptArg, 0) : 4};
				default:
					return null;
			}
		});
		HerbloreCompanionCollector c = new HerbloreCompanionCollector(client, new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload, accountHash});
			}
		}, () -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), store);
		c.setClock(() -> now);
		c.startUp();
		return c;
	}

	/** The game's Potion Storage enums: one list of potion enums, one of unfinished potions; each potion enum maps doses -> item. */
	private static EnumComposition enumOf(int id)
	{
		Map<Integer, Integer> values = new HashMap<>();
		int[] list = null;
		if (id == EnumID.POTIONSTORE_POTIONS)
		{
			list = new int[]{SR_ENUM, PRAYER_ENUM};
		}
		else if (id == EnumID.POTIONSTORE_UNFINISHED_POTIONS)
		{
			list = new int[]{SNAP_UNF_ENUM};
		}
		else if (id == SR_ENUM)
		{
			values.put(1, ItemID._1DOSE2RESTORE);
			values.put(2, ItemID._2DOSE2RESTORE);
			values.put(3, ItemID._3DOSE2RESTORE);
			values.put(4, ItemID._4DOSE2RESTORE);
		}
		else if (id == PRAYER_ENUM)
		{
			values.put(1, ItemID._1DOSEPRAYERRESTORE);
			values.put(4, ItemID._4DOSEPRAYERRESTORE);
		}
		else if (id == SNAP_UNF_ENUM)
		{
			values.put(1, ItemID.SNAPDRAGONVIAL);
		}
		final int[] vals = list == null ? new int[0] : list;
		return (EnumComposition) Proxy.newProxyInstance(EnumComposition.class.getClassLoader(), new Class<?>[]{EnumComposition.class},
			(p, m, a) ->
			{
				switch (m.getName())
				{
					case "getIntVals":
						return vals;
					case "getIntValue":
						return values.getOrDefault((Integer) a[0], -1);
					case "size":
						return vals.length;
					default:
						return null;
				}
			});
	}

	private static Session session(String id, String type, String key)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'X'},'state':'ACTIVE','startedAt':'2026-10-13T16:00:00Z','lastActiveAt':'2026-10-13T16:00:00Z',"
			+ "'accumulatedActiveDurationMillis':600000,'aggregates':{'xpGainedBySkill':{},'lootDrops':[]}}").replace('\'', '"'), Session.class);
	}

	private static ItemContainer container(Map<Integer, Long> items)
	{
		List<Item> list = new ArrayList<>();
		items.forEach((id, q) -> list.add(new Item(id, (int) (long) q)));
		Item[] arr = list.toArray(new Item[0]);
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
			(p, m, a) -> "getItems".equals(m.getName()) ? arr : null);
	}

	private void push()
	{
		inv.values().removeIf(v -> v <= 0);
		hc.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(inv)));
	}

	private void pushBank()
	{
		hc.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, container(bank)));
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			now += 600;
			hc.onHerbloreTick(new GameTick());
			hc.onGameTick(new GameTick());
		}
	}

	private void mixRestores(int n, int dosesEach)
	{
		for (int i = 0; i < n; i++)
		{
			herbXp += 142;
			hc.onStatChanged(new StatChanged(Skill.HERBLORE, herbXp, 90, 90));
			inv.merge(ItemID.SNAPDRAGONVIAL, -1L, Long::sum);
			inv.merge(ItemID.RED_SPIDERS_EGGS, -1L, Long::sum);
			inv.merge(dosesEach == 4 ? ItemID._4DOSE2RESTORE : ItemID._3DOSE2RESTORE, 1L, Long::sum);
			push();
			ticks(2);
		}
		ticks(3);
	}

	private void openBank()
	{
		WidgetLoaded e = new WidgetLoaded();
		e.setGroupId(InterfaceID.BANKMAIN);
		hc.onWidgetLoaded(e);
		hc.onHerbloreWidget(e);
		pushBank();
		ticks(1);
	}

	private void closeBank()
	{
		WidgetClosed e = new WidgetClosed(InterfaceID.BANKMAIN, 0, true);
		hc.onWidgetClosed(e);
		ticks(1);
	}

	private List<EventPayloads.SkillProduction> updates()
	{
		List<EventPayloads.SkillProduction> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == EventType.SKILL_PRODUCTION && "HERBLORE".equals(((EventPayloads.SkillProduction) e[1]).getSkill()))
			{
				out.add((EventPayloads.SkillProduction) e[1]);
			}
		}
		return out;
	}

	private static long qty(List<EventPayloads.ProducedItem> items, int id)
	{
		long q = 0;
		if (items != null)
		{
			for (EventPayloads.ProducedItem i : items)
			{
				if (i.getItemId() == id)
				{
					q += i.getQuantity();
				}
			}
		}
		return q;
	}

	private static SkillResource superRestore()
	{
		return HerbloreCompanionDefinition.INSTANCE.resource(ItemID._4DOSE2RESTORE);
	}

	@Test
	public void mixes_boundedTelemetry_exactOutputsInputsByName_andPerRecipeOccurrences()
	{
		current = session("s-h", "SKILLING", "herblore");
		mixRestores(5, 3);
		ticks(60);
		List<EventPayloads.SkillProduction> u = updates();
		assertTrue("bounded: " + u.size(), u.size() >= 1 && u.size() <= 2);
		long made = 0;
		long eggs = 0;
		long mixes = 0;
		for (EventPayloads.SkillProduction p : u)
		{
			made += qty(p.getProduced(), ItemID._3DOSE2RESTORE);
			eggs += qty(p.getConsumed(), ItemID.RED_SPIDERS_EGGS);
			assertEquals("inputs are never production", 0L, qty(p.getProduced(), ItemID.SNAPDRAGONVIAL));
			if (p.getOccurrences() != null)
			{
				mixes += p.getOccurrences().getOrDefault("mix:super_restore", 0L);
			}
		}
		assertEquals(5L, made);
		assertEquals(5L, eggs);
		assertEquals(5L, mixes);
		assertEquals("super_restore", u.get(0).getMethod());
		assertEquals("Super restore(3)", u.get(0).getProduced().get(0).getItemName());
		boolean namedEggs = false;
		for (EventPayloads.ProducedItem c : u.get(0).getConsumed())
		{
			namedEggs |= "Red spiders' eggs".equals(c.getItemName());
		}
		assertTrue(namedEggs);
	}

	@Test
	public void productionWithNoHerbloreSession_isDropped_neverMisattributed()
	{
		current = session("s-x", "SKILLING", "fishing");
		mixRestores(2, 3);
		now += 4L * 60_000L;
		ticks(60);
		assertTrue(updates().isEmpty());
	}

	@Test
	public void potionStorage_unknownBeforeSeen_neverZero_thenReadNativelyOnBankOpen_withoutTheTab()
	{
		SkillExternalStock before = hc.externalStock(superRestore());
		assertTrue(before.applies());
		assertFalse("unknown, never zero", before.isKnown());
		storedDoses.put(SR_ENUM, 1_200);
		storedDoses.put(PRAYER_ENUM, 40);
		storedDoses.put(SNAP_UNF_ENUM, 75);
		openBank();
		SkillExternalStock after = hc.externalStock(superRestore());
		assertTrue(after.isKnown());
		assertEquals(Long.valueOf(1_200), after.getQuantity());
		assertEquals(Long.valueOf(75),
			hc.externalStock(HerbloreCompanionDefinition.INSTANCE.resource(ItemID.SNAPDRAGONVIAL)).getQuantity());
		assertEquals("herbs never live in Potion Storage", SkillExternalStock.NOT_APPLICABLE,
			hc.externalStock(HerbloreCompanionDefinition.INSTANCE.resource(ItemID.SNAPDRAGON)));
		assertTrue(scriptRuns > 0);
	}

	@Test
	public void potionStorage_varChangeWhileBankOpen_readsAgain_closedBank_neverReads()
	{
		storedDoses.put(SR_ENUM, 100);
		openBank();
		storedDoses.put(SR_ENUM, 140);
		VarbitChanged v = new VarbitChanged();
		v.setVarpId(VarPlayerID.POTIONSTORE_SUPER_RESTORE_POTION);
		v.setVarbitId(-1);
		hc.onHerbloreVarbit(v);
		ticks(1);
		assertEquals(Long.valueOf(140), hc.externalStock(superRestore()).getQuantity());
		closeBank();
		int runs = scriptRuns;
		storedDoses.put(SR_ENUM, 0);
		hc.onHerbloreVarbit(v);
		ticks(3);
		assertEquals("no read with the bank closed", runs, scriptRuns);
		assertEquals(Long.valueOf(140), hc.externalStock(superRestore()).getQuantity());
		assertTrue(HerbloreCompanionCollector.isPotionStoreVar(VarPlayerID.POTIONSTORE_ARMADYLBREW_POTION));
		assertFalse(HerbloreCompanionCollector.isPotionStoreVar(VarPlayerID.POTIONSTORE_BASE_VAR_1 - 1));
	}

	@Test
	public void potionStorage_snapshotIsSaved_andUsedOnALaterLogin_withoutReopening()
	{
		storedDoses.put(SR_ENUM, 880);
		openBank();
		PotionStorageState saved = null;
		for (Object o : files.values())
		{
			if (o instanceof PotionStorageState)
			{
				saved = (PotionStorageState) o;
			}
		}
		assertNotNull("the existing Potion Storage snapshot was refreshed", saved);
		assertEquals(880, saved.getItems().get(0).getTotalDoses());
		assertNotNull(saved.getLastObservedAt());
		// A later login: a fresh collector reads the saved snapshot, never zero.
		HerbloreCompanionCollector later = newCollector();
		hc = later;
		ticks(1);
		SkillExternalStock s = later.externalStock(superRestore());
		assertTrue(s.isKnown());
		assertEquals(Long.valueOf(880), s.getQuantity());
		assertNotNull("dated: a saved snapshot", s.getObservedAt());
	}

	@Test
	public void noPotionStorageOnTheAccount_bankAloneIsTheStock()
	{
		varbits.put(VarbitID.MM_POTION_STORAGE_UNLOCKED, 0);
		ticks(1);
		assertEquals(SkillExternalStock.NOT_APPLICABLE, hc.externalStock(superRestore()));
	}

	@Test
	public void storageDeposit_ofTheSessionsPotions_isBanked_andFeedsTheStockpileRate()
	{
		current = session("s-h", "SKILLING", "herblore");
		mixRestores(4, 3);
		storedDoses.put(SR_ENUM, 100);
		openBank();
		inv.remove(ItemID._3DOSE2RESTORE);
		push();
		storedDoses.put(SR_ENUM, 112);
		VarbitChanged v = new VarbitChanged();
		v.setVarpId(VarPlayerID.POTIONSTORE_SUPER_RESTORE_POTION);
		v.setVarbitId(-1);
		hc.onHerbloreVarbit(v);
		ticks(6);
		long banked = 0;
		for (EventPayloads.SkillProduction p : updates())
		{
			banked += qty(p.getBanked(), ItemID._3DOSE2RESTORE);
		}
		assertEquals(4L, banked);
		assertNotNull(hc.getView().getBanking().get(ItemID._3DOSE2RESTORE));
	}

	@Test
	public void masteringMixology_insideAHerbloreSession_sendsNoRecipeProduction()
	{
		current = session("s-h", "SKILLING", "herblore");
		inv.put(ItemID.MM_LYE_PASTE, 100L);
		inv.put(ItemID.RANARR_WEED, 20L);
		push();
		ticks(2);
		for (int i = 0; i < 5; i++)
		{
			// The refiner: herbs in, Herblore XP, paste out.
			herbXp += 6;
			hc.onStatChanged(new StatChanged(Skill.HERBLORE, herbXp, 90, 90));
			inv.merge(ItemID.RANARR_WEED, -1L, Long::sum);
			inv.merge(ItemID.MM_LYE_PASTE, 20L, Long::sum);
			push();
			ticks(2);
		}
		// A reward potion pack opened: 3-dose potions that were never made here.
		inv.put(ItemID._3DOSE2RESTORE, 4L);
		push();
		ticks(60);
		for (EventPayloads.SkillProduction p : updates())
		{
			assertTrue("no production from Mixology", p.getProduced() == null || p.getProduced().isEmpty());
			assertTrue(p.getOccurrences() == null || p.getOccurrences().isEmpty());
		}
		assertNull(hc.getView().getEvent());
		assertFalse(hc.getView().getContext().isEmpty());
	}

	@Test
	public void wornAmulet_isReadFromEquipment_bonusDoseRecorded()
	{
		current = session("s-h", "SKILLING", "herblore");
		worn.put(ItemID.AMULET_OF_CHEMISTRY, 1L);
		ticks(1);
		mixRestores(1, 4);
		mixRestores(1, 3);
		ticks(60);
		long bonus = 0;
		for (EventPayloads.SkillProduction p : updates())
		{
			if (p.getOccurrences() != null)
			{
				bonus += p.getOccurrences().getOrDefault("bonus:super_restore", 0L);
			}
		}
		assertEquals(1L, bonus);
		assertNull(hc.herblore().validatedAlchemistCharges());
	}
}
