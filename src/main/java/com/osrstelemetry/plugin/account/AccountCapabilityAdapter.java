package com.osrstelemetry.plugin.account;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
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
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Filepath;

/**
 * The live adapter that observes generic account capability (canonical §12.1)
 * and publishes an immutable {@link AccountCapability} each tick. Registered by
 * the plugin; handlers run on the client thread. Reads only native client
 * state: no other plugin, no PluginMessage, no external config, no network.
 *
 * <h3>Ownership (never fabricate, never leak)</h3>
 * {@link AccountProfileGate} decides the owner each tick. Any tick that is not
 * STANDARD publishes {@link AccountCapability#none}. A different owner resets
 * every in-memory fact and switches the profile-scoped storage store, whose
 * loads and writes are owner- and generation-checked. Logout drops the owner.
 *
 * <h3>Account data readiness (evidence, not a delay)</h3>
 * Quest, diary, spellbook and rune pouch facts come from server variables that
 * the server sends after login. They are read only once that delivery is
 * evidenced for this login:
 * <ul>
 *   <li>a {@link VarbitChanged} received while LOGGED_IN after the login (the
 *   server's variable sync has arrived), or</li>
 *   <li>the plugin was enabled while already logged in (no login was observed:
 *   the variables were synced earlier; RuneLite's own plugins read varbits at
 *   start-up in that case, e.g. ItemChargePlugin).</li>
 * </ul>
 * World hops and logins clear the evidence. Quest states, which come from a
 * client script over those variables, are accepted only when two reads on
 * different ticks agree (a single read is never trusted), and are re-read every
 * {@link #QUEST_POLL_TICKS} the same way. No number of ticks alone makes
 * anything known.
 *
 * <h3>Containers</h3>
 * Inventory and equipment every STANDARD tick (null = not sent = unknown).
 * Bank (with each item's first slot and the BANK_TAB_1..9 counts), group
 * storage and seed vault on {@link ItemContainerChanged} for the current owner.
 */
@Slf4j
@Singleton
public class AccountCapabilityAdapter
{
	public static final int QUEST_POLL_TICKS = 100;

	static final int[] BANK_TAB_VARBITS = {VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4, VarbitID.BANK_TAB_5,
		VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9};

	private final Client client;
	private final LocalStateStore stateStore;

	// Test seams.
	Function<AccountOwner, Filepath> dirs = o -> TelemetryPaths.accountCapabilityDir(o.getAccountHash(), o.getProfileKey());
	LongSupplier clock = System::currentTimeMillis;

	private final AccountProfileGate gate = new AccountProfileGate();
	private volatile Set<Quest> questsOfInterest = Collections.emptySet();
	private volatile Set<Integer> varbitsOfInterest = Collections.emptySet();
	/** Observed teleport charges and daily uses for the current profile (ChargeObserver); never assumed. */
	private volatile java.util.function.Supplier<ChargeKnowledge> charges = () -> ChargeKnowledge.EMPTY;
	private volatile Set<Integer> varpsOfInterest = Collections.emptySet();
	private volatile AccountCapability current = AccountCapability.none(AccountProfileGate.Status.NOT_LOGGED_IN, 0);

	// Client-thread state.
	private ExecutorService loadExecutor;
	private AccountStorageStore storages;
	private AccountOwner owner;
	private AccountProfileGate.Status status = AccountProfileGate.Status.NOT_LOGGED_IN;
	/** A login (or hop) was observed since start: varp delivery must then be evidenced afresh. */
	private boolean loginObserved;
	/** The server's variable sync for the current login is evidenced. */
	private boolean varpsReady;
	private final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
	private Map<Quest, QuestState> pendingQuestRead;
	private int pendingQuestTick;
	private int lastQuestAccept = Integer.MIN_VALUE;

	@Inject
	public AccountCapabilityAdapter(Client client, LocalStateStore stateStore)
	{
		this.client = client;
		this.stateStore = stateStore;
	}

	/** Creates the load executor and storage store. Touches no file and no client API. Idempotent. */
	public synchronized void start()
	{
		if (storages != null)
		{
			return;
		}
		loadExecutor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "spyglass-account-load");
			t.setDaemon(true);
			return t;
		});
		ExecutorService ex = loadExecutor;
		Executor dropAfterShutdown = task ->
		{
			try
			{
				ex.execute(task);
			}
			catch (RejectedExecutionException e)
			{
				log.debug("Spyglass account: load dropped after shutdown");
			}
		};
		Map<AccountOwner, Filepath> cache = Collections.synchronizedMap(new HashMap<>());
		Function<AccountOwner, Filepath> seam = dirs;
		storages = new AccountStorageStore(stateStore, o -> cache.computeIfAbsent(o, seam), dropAfterShutdown);
		loginObserved = false;
		varpsReady = false;
	}

	/** After unregistering, before LocalStateStore shutdown. Idempotent. */
	public synchronized void shutDown()
	{
		if (storages == null)
		{
			return;
		}
		storages.switchOwner(null);
		storages = null;
		loadExecutor.shutdownNow();
		loadExecutor = null;
		resetOwner(null);
		current = AccountCapability.none(AccountProfileGate.Status.NOT_LOGGED_IN, clock.getAsLong());
	}

	/** The quests to read (the transport catalogue's and domains'); anything else is never read. */
	public void setQuestsOfInterest(Set<Quest> quests)
	{
		this.questsOfInterest = quests == null || quests.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(quests));
	}

	/** Where observed charges come from (the charge observer, per RuneScape profile). */
	public void setChargeSource(java.util.function.Supplier<ChargeKnowledge> source)
	{
		this.charges = source == null ? () -> ChargeKnowledge.EMPTY : source;
	}

	/** Domain varbits to read once account data is loaded (e.g. Farming unlocks); nothing else is read. */
	public void setVarbitsOfInterest(Set<Integer> varbits)
	{
		this.varbitsOfInterest = varbits == null ? Collections.emptySet() : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(varbits));
	}

	/** Domain varps to read once account data is loaded (navigation unlock checks); nothing else is read. */
	public void setVarpsOfInterest(Set<Integer> varps)
	{
		this.varpsOfInterest = varps == null ? Collections.emptySet() : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(varps));
	}

	/** The latest capability (any thread). Never null; ownerless when nothing may be known. */
	public AccountCapability capability()
	{
		return current;
	}

	// -- events (client thread) --------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		AccountStorageStore store = storages;
		if (store == null)
		{
			return;
		}
		long now = clock.getAsLong();
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		status = loggedIn
			? gate.evaluate(true, client.getAccountHash(), client.getWorld(), client.getWorldType(), RuneScapeProfileType.getCurrent(client))
			: gate.evaluate(false, -1L, 0, null, null);
		if (status == AccountProfileGate.Status.NOT_LOGGED_IN)
		{
			resetOwner(null);
			store.switchOwner(null);
			current = AccountCapability.none(status, now);
			return;
		}
		if (!loginObserved)
		{
			varpsReady = true; // enabled while already logged in: the variables were synced before we started
		}
		if (status != AccountProfileGate.Status.STANDARD)
		{
			// Hopping, settling, or a non-standard profile / world: publish nothing for anyone.
			current = AccountCapability.none(status, now);
			return;
		}
		AccountOwner next = AccountOwner.standard(client.getAccountHash());
		if (!next.equals(owner))
		{
			resetOwner(next);
			store.switchOwner(next);
		}
		AccountCapability read = read(next, store, now);
		if (!read.sameContent(current))
		{
			current = read; // a new snapshot only when something changed: consumers memoise on identity
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState s = event.getGameState();
		switch (s)
		{
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			{
				AccountStorageStore store = storages;
				resetOwner(null);
				gate.reset();
				if (store != null)
				{
					store.switchOwner(null);
				}
				loginObserved = true;
				varpsReady = false;
				current = AccountCapability.none(AccountProfileGate.Status.NOT_LOGGED_IN, clock.getAsLong());
				break;
			}
			case LOGGING_IN:
			case HOPPING:
				loginObserved = true;
				varpsReady = false;
				current = AccountCapability.none(AccountProfileGate.Status.NOT_READY, clock.getAsLong());
				break;
			case CONNECTION_LOST:
				current = AccountCapability.none(AccountProfileGate.Status.NOT_READY, clock.getAsLong());
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			varpsReady = true; // the server's variable sync for this login has arrived
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		AccountStorageStore store = storages;
		int id = event.getContainerId();
		if (store == null || owner == null || status != AccountProfileGate.Status.STANDARD)
		{
			return;
		}
		StorageEvidence.Kind kind = id == InventoryID.BANK ? StorageEvidence.Kind.BANK
			: id == InventoryID.INV_GROUP_TEMP ? StorageEvidence.Kind.GROUP_STORAGE
			: id == InventoryID.SEED_VAULT ? StorageEvidence.Kind.SEED_VAULT : null;
		if (kind == null)
		{
			return;
		}
		ItemContainer container = event.getItemContainer();
		Map<Integer, Integer> items = items(container);
		if (items == null)
		{
			return;
		}
		Map<Integer, Integer> slots = null;
		int[] tabs = null;
		if (kind == StorageEvidence.Kind.BANK)
		{
			slots = firstSlots(container);
			tabs = new int[BANK_TAB_VARBITS.length];
			for (int i = 0; i < tabs.length; i++)
			{
				tabs[i] = client.getVarbitValue(BANK_TAB_VARBITS[i]);
			}
		}
		store.observe(owner, new StorageEvidence(kind, items, clock.getAsLong(), slots, tabs));
	}

	// -- reading -------------------------------------------------------------------------

	private void resetOwner(AccountOwner next)
	{
		owner = next;
		quests.clear();
		pendingQuestRead = null;
		lastQuestAccept = Integer.MIN_VALUE;
	}

	boolean isVarpsReady()
	{
		return varpsReady;
	}

	private AccountCapability read(AccountOwner who, AccountStorageStore store, long now)
	{
		AccountCapability.Builder b = AccountCapability.builder().owner(who).status(AccountProfileGate.Status.STANDARD).capturedAt(now);
		Set<WorldType> types = client.getWorldType();
		b.membersWorld(types == null ? Known.UNKNOWN : Known.of(types.contains(WorldType.MEMBERS)));
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			if (!"OVERALL".equals(skill.name()))
			{
				levels.put(skill, client.getRealSkillLevel(skill));
			}
		}
		if (levels.getOrDefault(Skill.HITPOINTS, 0) >= 10)
		{
			b.levels(levels); // Hitpoints below 10 means stats have not arrived yet
		}
		b.inventory(items(client.getItemContainer(InventoryID.INV)));
		b.inventorySlotsUsed(slotsUsed(client.getItemContainer(InventoryID.INV)));
		b.worn(items(client.getItemContainer(InventoryID.WORN)));
		b.wornSlots(slots(client.getItemContainer(InventoryID.WORN)));
		b.weight(client.getWeight());
		b.settled(varpsReady);
		if (varpsReady)
		{
			b.spellbookRaw(client.getVarbitValue(VarbitID.SPELLBOOK));
			b.diaries(AccountReaders.diaries(client::getVarbitValue));
			EnumComposition pouchEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
			if (pouchEnum != null)
			{
				b.runePouch(AccountReaders.runePouch(client::getVarbitValue, pouchEnum::getIntValue));
			}
			for (Integer id : varbitsOfInterest)
			{
				b.varbit(id, client.getVarbitValue(id));
			}
			for (Integer id : varpsOfInterest)
			{
				b.varp(id, client.getVarpValue(id));
			}
			readQuests(client.getTickCount());
			b.quests(quests);
		}
		b.bank(store.bankFor(who));
		b.groupStorage(store.groupStorageFor(who));
		b.seedVault(store.seedVaultFor(who));
		ChargeKnowledge k = charges.get();
		b.charges(k == null ? ChargeKnowledge.EMPTY : k);
		return b.build();
	}

	/** Two reads on different ticks must agree before quest states are accepted. */
	private void readQuests(int tick)
	{
		if (questsOfInterest.isEmpty())
		{
			return;
		}
		boolean due = quests.isEmpty() || tick - lastQuestAccept >= QUEST_POLL_TICKS;
		if (!due)
		{
			return;
		}
		Map<Quest, QuestState> read = new EnumMap<>(Quest.class);
		for (Quest q : questsOfInterest)
		{
			read.put(q, q.getState(client));
		}
		if (pendingQuestRead != null && pendingQuestTick != tick && pendingQuestRead.equals(read))
		{
			quests.clear();
			quests.putAll(read);
			lastQuestAccept = tick;
			pendingQuestRead = null;
		}
		else
		{
			pendingQuestRead = read;
			pendingQuestTick = tick;
		}
	}

	/** Item id → quantity, placeholders and empty slots dropped; null when the container has not been sent. */
	/** Occupied slots (null when the container was not sent). */
	static Integer slotsUsed(ItemContainer container)
	{
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		int n = 0;
		if (items != null)
		{
			for (Item item : items)
			{
				if (item != null && item.getId() > 0 && item.getQuantity() > 0)
				{
					n++;
				}
			}
		}
		return n;
	}

	static Map<Integer, Integer> items(ItemContainer container)
	{
		if (container == null)
		{
			return null;
		}
		Map<Integer, Integer> out = new LinkedHashMap<>();
		Item[] items = container.getItems();
		if (items == null)
		{
			return out;
		}
		for (Item item : items)
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				out.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return out;
	}

	/** Item id → the first slot index it occupies (placeholders count as slots but are not recorded). */
	static Map<Integer, Integer> firstSlots(ItemContainer container)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		Item[] items = container == null ? null : container.getItems();
		if (items == null)
		{
			return out;
		}
		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				out.putIfAbsent(item.getId(), i);
			}
		}
		return out;
	}

	// -- test access -----------------------------------------------------------------------

	AccountStorageStore storages()
	{
		return storages;
	}

	/** Occupied slot indices of a container (equipment: EquipmentInventorySlot order); null when not loaded. */
	static java.util.Set<Integer> slots(net.runelite.api.ItemContainer c)
	{
		if (c == null)
		{
			return null;
		}
		java.util.Set<Integer> out = new java.util.TreeSet<>();
		net.runelite.api.Item[] items = c.getItems();
		for (int i = 0; i < items.length; i++)
		{
			if (items[i] != null && items[i].getId() > 0)
			{
				out.add(i);
			}
		}
		return out;
	}
}
