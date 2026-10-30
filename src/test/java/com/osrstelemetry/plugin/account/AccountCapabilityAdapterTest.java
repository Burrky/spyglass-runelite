package com.osrstelemetry.plugin.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The live account adapter against a proxy {@link Client}: logout and
 * incomplete metadata publish nothing, the login settle gates varbit / quest
 * facts, and one account's evidence never appears for another.
 */
public class AccountCapabilityAdapterTest
{
	private static final Gson GSON = new Gson();

	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h));
	}

	private static Object dflt(Class<?> t)
	{
		if (t == boolean.class)
		{
			return false;
		}
		if (t == int.class)
		{
			return 0;
		}
		if (t == long.class)
		{
			return 0L;
		}
		return null;
	}

	static ItemContainer container(Map<Integer, Integer> items)
	{
		List<Item> list = new ArrayList<>();
		items.forEach((id, q) -> list.add(new Item(id, q)));
		Item[] arr = list.toArray(new Item[0]);
		return proxy(ItemContainer.class, (p, m, a) -> m.getName().equals("getItems") ? arr : dflt(m.getReturnType()));
	}

	/** Mutable fake client. */
	static final class Fake
	{
		GameState state = GameState.LOGGED_IN;
		long account = 77L;
		int world = 330;
		EnumSet<WorldType> types = EnumSet.of(WorldType.MEMBERS);
		int tick = 1000;
		int hitpoints = 50;
		int magic = 70;
		final Map<Integer, Integer> varbits = new HashMap<>();
		Map<Integer, Integer> inventory = new HashMap<>();
		Map<Integer, Integer> worn = new HashMap<>();
		int questStack = 2;
		int questReads;
		final Client client = proxy(Client.class, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getGameState":
					return state;
				case "getAccountHash":
					return account;
				case "getWorld":
					return world;
				case "getWorldType":
					return EnumSet.copyOf(types);
				case "getTickCount":
					return tick;
				case "getRealSkillLevel":
					Skill s = (Skill) a[0];
					return s == Skill.HITPOINTS ? hitpoints : s == Skill.MAGIC ? magic : 1;
				case "getVarbitValue":
					return varbits.getOrDefault((Integer) a[0], 0);
				case "getItemContainer":
					int id = (Integer) a[0];
					Map<Integer, Integer> c = id == InventoryID.INV ? inventory : id == InventoryID.WORN ? worn : null;
					return c == null ? null : container(c);
				case "getEnum":
					return proxy(EnumComposition.class, (ep, em, ea) -> em.getName().equals("getIntValue") ? ((Integer) ea[0] == 14 ? ItemID.LAWRUNE : -1)
						: dflt(em.getReturnType()));
				case "runScript":
					questReads++;
					return null;
				case "getIntStack":
					return new int[]{questStack};
				default:
					return dflt(m.getReturnType());
			}
		});
	}

	private Fake fake;
	private LocalStateStore stateStore;
	private Filepath rootPath;
	private AccountCapabilityAdapter adapter;

	@Before
	public void setUp() throws Exception
	{
		fake = new Fake();
		rootPath = TestFilepaths.rooted(Files.createTempDirectory("account-adapter"));
		stateStore = new LocalStateStore(GSON);
		stateStore.start();
		adapter = new AccountCapabilityAdapter(fake.client, stateStore);
		adapter.dirs = o ->
		{
			Filepath dir = rootPath.joinSegment(Long.toString(o.getAccountHash())).joinSegment(o.getProfileKey());
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
		adapter.clock = () -> 1_000_000L;
		adapter.setQuestsOfInterest(EnumSet.of(Quest.PLAGUE_CITY, Quest.GHOSTS_AHOY));
		adapter.start();
	}

	@After
	public void tearDown() throws Exception
	{
		adapter.shutDown();
		stateStore.shutdown();
		rootPath.deleteRecursively();
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			fake.tick++;
			adapter.onGameTick(new GameTick());
		}
	}

	private void awaitLoaded() throws InterruptedException
	{
		for (int i = 0; i < 200 && !adapter.storages().isLoaded(); i++)
		{
			Thread.sleep(10);
		}
		assertTrue(adapter.storages().isLoaded());
	}

	private void openBank(int... kv)
	{
		adapter.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, container(AccountFixtures.items(kv))));
		stateStore.writeAndWait(rootPath.joinSegment("barrier.json"), Collections.singletonMap("b", 1), 10_000);
	}

	@Test
	public void logout_andIncompleteMetadata_publishNoCapability()
	{
		fake.state = GameState.LOGIN_SCREEN;
		ticks(60);
		assertNull(adapter.capability().getOwner());
		assertEquals(AccountProfileGate.Status.NOT_LOGGED_IN, adapter.capability().getStatus());

		fake.state = GameState.LOGGED_IN;
		fake.account = -1L;
		ticks(60);
		assertNull(adapter.capability().getOwner());
		assertNull(adapter.capability().getInventory());
		fake.account = 77L;
		fake.world = 0;
		ticks(5);
		assertNull(adapter.capability().getOwner());
	}

	@Test
	public void nonStandardWorlds_publishNothing_evenForAKnownAccount()
	{
		ticks(3);
		assertNotNull(adapter.capability().getOwner());
		fake.types = EnumSet.of(WorldType.SEASONAL);
		ticks(5);
		assertNull(adapter.capability().getOwner());
		assertEquals(AccountProfileGate.Status.NOT_STANDARD, adapter.capability().getStatus());
		openBank(ItemID.LAWRUNE, 5);
		fake.types = EnumSet.of(WorldType.MEMBERS);
		ticks(3);
		assertNull("a seasonal bank is never recorded as the standard bank", adapter.capability().getBank());
	}

	private void login()
	{
		adapter.onGameStateChanged(gameState(GameState.LOGGING_IN));
		fake.state = GameState.LOGGED_IN;
		adapter.onGameStateChanged(gameState(GameState.LOGGED_IN));
	}

	private void varbitSync()
	{
		adapter.onVarbitChanged(new VarbitChanged());
	}

	@Test
	public void afterALogin_noAmountOfTicksAlone_makesVarbitOrQuestFactsKnown()
	{
		fake.varbits.put(VarbitID.SPELLBOOK, 2);
		fake.varbits.put(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1);
		fake.varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 14);
		fake.varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 300);
		fake.inventory.put(ItemID.BH_RUNE_POUCH, 1);
		login();
		ticks(500);
		AccountCapability early = adapter.capability();
		assertNotNull("the owner and containers are known", early.getOwner());
		assertFalse(early.isSettled());
		assertEquals(Integer.valueOf(70), early.level(Skill.MAGIC));
		assertNull(early.getSpellbookRaw());
		assertNull(early.getRunePouch());
		assertNull(early.quest(Quest.PLAGUE_CITY));
		assertEquals(Known.UNKNOWN, early.diary("ARDOUGNE_ELITE"));
		assertEquals("no quest script runs before the variables are evidenced", 0, fake.questReads);
		assertEquals(1, (int) early.getInventory().get(ItemID.BH_RUNE_POUCH));
	}

	@Test
	public void theServersVariableSync_isTheReadinessEvidence_andQuestsNeedTwoAgreeingReads()
	{
		fake.varbits.put(VarbitID.SPELLBOOK, 2);
		fake.varbits.put(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1);
		fake.varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 14);
		fake.varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 300);
		login();
		ticks(3);
		varbitSync();
		ticks(1);
		AccountCapability first = adapter.capability();
		assertTrue(first.isSettled());
		assertEquals(AccountReaders.Spellbook.LUNAR, first.spellbook());
		assertEquals(300, (int) first.getRunePouch().get(ItemID.LAWRUNE));
		assertEquals(Known.YES, first.diary("ARDOUGNE_ELITE"));
		assertNull("one quest read is never trusted", first.quest(Quest.PLAGUE_CITY));
		ticks(1);
		assertEquals(QuestState.FINISHED, adapter.capability().quest(Quest.PLAGUE_CITY));
		assertNull("only the catalogue's quests are read", adapter.capability().quest(Quest.DRAGON_SLAYER_I));
		int reads = fake.questReads;
		ticks(5);
		assertEquals("quests are polled, not read every tick", reads, fake.questReads);
	}

	@Test
	public void disagreeingQuestReads_stayUnknown_untilTwoAgree()
	{
		login();
		ticks(2);
		varbitSync();
		fake.questStack = 0; // in progress
		ticks(1);
		fake.questStack = 2; // finished: a different answer
		ticks(1);
		assertNull(adapter.capability().quest(Quest.PLAGUE_CITY));
		ticks(1);
		assertEquals(QuestState.FINISHED, adapter.capability().quest(Quest.PLAGUE_CITY));
	}

	@Test
	public void enablingWhileAlreadyLoggedIn_readsAtOnce_butAHopRequiresFreshEvidence()
	{
		ticks(3);
		assertTrue("no login was observed: the variables were synced before Spyglass started", adapter.capability().isSettled());
		adapter.onGameStateChanged(gameState(GameState.HOPPING));
		fake.world = 331;
		ticks(4);
		assertFalse(adapter.capability().isSettled());
		varbitSync();
		ticks(1);
		assertTrue(adapter.capability().isSettled());
	}

	@Test
	public void statsNotArrived_meansLevelsUnknown_notLevelOne()
	{
		fake.hitpoints = 0;
		ticks(5);
		assertNull(adapter.capability().level(Skill.MAGIC));
	}

	@Test
	public void oneAccountsEvidence_neverAppearsForAnother_andLogoutDropsEverything() throws Exception
	{
		ticks(3);
		awaitLoaded();
		openBank(ItemID.ARDY_CAPE_ELITE, 1);
		ticks(1);
		assertEquals(1, adapter.capability().getBank().count(ItemID.ARDY_CAPE_ELITE));
		assertEquals(QuestState.FINISHED, adapter.capability().quest(Quest.PLAGUE_CITY));

		adapter.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		assertNull(adapter.capability().getOwner());
		fake.state = GameState.LOGIN_SCREEN;
		ticks(2);
		assertNull(adapter.capability().getOwner());

		fake.state = GameState.LOGGED_IN;
		fake.account = 88L;
		fake.inventory = new HashMap<>();
		ticks(3);
		awaitLoaded();
		ticks(1);
		AccountCapability b = adapter.capability();
		assertEquals(AccountOwner.standard(88L), b.getOwner());
		assertNull("account B has never had its bank seen", b.getBank());
		assertNull("account A's quests do not carry over", b.quest(Quest.PLAGUE_CITY));
		assertFalse(b.isSettled());

		fake.state = GameState.LOGGED_IN;
		fake.account = 77L;
		ticks(3);
		awaitLoaded();
		ticks(1);
		assertEquals("A's own evidence returns for A", 1, adapter.capability().getBank().count(ItemID.ARDY_CAPE_ELITE));
	}

	@Test
	public void hopping_publishesNothing_untilStableAgain()
	{
		ticks(3);
		adapter.onGameStateChanged(gameState(GameState.HOPPING));
		assertNull(adapter.capability().getOwner());
		fake.world = 331;
		ticks(1);
		assertNull(adapter.capability().getOwner());
		ticks(1);
		assertNotNull(adapter.capability().getOwner());
	}

	@Test
	public void groupStorage_isRecordedAsShared_andOtherContainersAreIgnored() throws Exception
	{
		ticks(3);
		awaitLoaded();
		adapter.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV_GROUP_TEMP, container(AccountFixtures.items(ItemID.LAWRUNE, 7))));
		adapter.onItemContainerChanged(new ItemContainerChanged(InventoryID.SEED_VAULT, container(AccountFixtures.items(ItemID.LAWRUNE, 9))));
		ticks(1);
		assertEquals(7, adapter.capability().getGroupStorage().count(ItemID.LAWRUNE));
		assertNull(adapter.capability().getBank());
	}

	@Test
	public void bankLayout_andSeedVault_areRecorded_forTheTabHint() throws Exception
	{
		ticks(3);
		awaitLoaded();
		fake.varbits.put(VarbitID.BANK_TAB_1, 2);
		fake.varbits.put(VarbitID.BANK_TAB_2, 1);
		Item[] slots = {new Item(ItemID.LAWRUNE, 100), new Item(ItemID.RANARR_SEED, 0), new Item(ItemID.SPADE, 1), new Item(ItemID.RANARR_SEED, 9)};
		ItemContainer bank = proxy(ItemContainer.class, (p, m, a) -> m.getName().equals("getItems") ? slots : dflt(m.getReturnType()));
		adapter.onItemContainerChanged(new ItemContainerChanged(InventoryID.BANK, bank));
		adapter.onItemContainerChanged(new ItemContainerChanged(InventoryID.SEED_VAULT, container(AccountFixtures.items(ItemID.TOADFLAX_SEED, 4))));
		ticks(1);
		StorageEvidence e = adapter.capability().getBank();
		assertEquals("law runes: slot 0, tab 1", 1, e.tabOf(ItemID.LAWRUNE));
		assertEquals("spade: slot 2, tab 2", 2, e.tabOf(ItemID.SPADE));
		assertEquals("ranarr seeds: slot 3 (after a placeholder), main tab", 0, e.tabOf(ItemID.RANARR_SEED));
		assertEquals(-1, e.tabOf(ItemID.TORSTOL_SEED));
		assertEquals(4, adapter.capability().getSeedVault().count(ItemID.TOADFLAX_SEED));
	}

	private static GameStateChanged gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return e;
	}
}
