package com.osrstelemetry.plugin.skills.herblore;

import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.model.PotionStorageState;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionCollector;
import com.osrstelemetry.plugin.skills.SkillExternalStock;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.UnitFamily;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * The Herblore companion's native wiring (Skills Phase 6): the SAME shared
 * {@link SkillCompanionCollector} (binding to SKILLING/herblore only, bounded
 * telemetry -- exact outputs, exact consumed inputs, per-recipe mixes / doses /
 * actual savings and bonus doses --, live view, bank view) with Herblore's
 * tracker, plus its own native evidence, all event-driven:
 * <ul>
 * <li>worn equipment (a dose-adding amulet, prescription goggles), and the
 * alchemist's amulet's own charge counter (varbit), read once per tick;</li>
 * <li>POTION STORAGE: while the bank is open (it is a bank interface), the
 * doses of every stored potion are read with the game's own scripts
 * (POTIONSTORE_DOSES / POTIONSTORE_WITHDRAW_DOSES over its potion enums) --
 * the same native read RuneLite's bank tags use, without opening the potion
 * tab -- when the bank opens and whenever one of its vars changes. Each read
 * refreshes the account's existing Potion Storage snapshot (the storage
 * system's own state file, same schema), which is also what OWNED uses
 * before the bank is opened on a later login (never shown as zero when it
 * was never seen).</li>
 * </ul>
 * Independent of RuneLite's own plugins; no networking.
 */
@Slf4j
@Singleton
public class HerbloreCompanionCollector extends SkillCompanionCollector
{
	static final String STORAGE_CATEGORY = "potion_storage";

	private final LocalStateStore stateStore;
	private boolean storageDirty;
	private long savedStorageAccount = Long.MIN_VALUE;
	private String lastWrittenSignature;

	/** Potion Storage, family key -> units: live this login, else the saved snapshot; null = never seen. */
	private volatile Map<String, Long> storageUnits;
	/** When the shown storage was observed (ISO-8601); null = live this login. */
	private volatile String storageObservedAt;
	/** Whether the account has Potion Storage at all (native varbit); null = not read yet. */
	private volatile Boolean storageUnlocked;

	@Inject
	public HerbloreCompanionCollector(Client client, EventLedger eventLedger, SessionRuntimeCoordinator coordinator, LocalStateStore store)
	{
		this(client, eventLedger, coordinator::getCurrentSessionSnapshot, store);
	}

	HerbloreCompanionCollector(Client client, EventLedger eventLedger, Supplier<SessionSnapshot> currentSession, LocalStateStore store)
	{
		super(client, eventLedger, currentSession, store, HerbloreCompanionDefinition.INSTANCE, new HerbloreCompanionTracker());
		this.stateStore = store;
	}

	@Override
	protected void setClock(LongSupplier clock)
	{
		super.setClock(clock);
	}

	HerbloreCompanionTracker herblore()
	{
		return (HerbloreCompanionTracker) tracker;
	}

	@Override
	public void resetForAccountSwitch()
	{
		storageUnits = null;
		storageObservedAt = null;
		storageUnlocked = null;
		savedStorageAccount = Long.MIN_VALUE;
		lastWrittenSignature = null;
		storageDirty = false;
		super.resetForAccountSwitch();
	}

	/**
	 * Potion Storage for a stockpile resource: NOT_APPLICABLE when the item
	 * cannot be stored there or the account has no Potion Storage; KNOWN (live
	 * this login, else the saved snapshot with its date); UNKNOWN when it was
	 * never seen -- never zero.
	 */
	@Override
	public SkillExternalStock externalStock(SkillResource resource)
	{
		UnitFamily family = resource == null ? null : HerbloreRecipes.familyOf(resource.getItemId());
		if (family == null || !HerbloreRecipes.storable(family))
		{
			return SkillExternalStock.NOT_APPLICABLE;
		}
		Map<String, Long> units = storageUnits;
		if (units == null)
		{
			return Boolean.FALSE.equals(storageUnlocked) ? SkillExternalStock.NOT_APPLICABLE
				: SkillExternalStock.unknown(HerbloreCompanionDefinition.STORAGE_NAME);
		}
		return SkillExternalStock.known(HerbloreCompanionDefinition.STORAGE_NAME, units.getOrDefault(family.getKey(), 0L), storageObservedAt);
	}

	@Subscribe
	public void onHerbloreTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		long now = clock.getAsLong();
		ItemContainer wornContainer = client.getItemContainer(InventoryID.WORN);
		Set<Integer> worn = new HashSet<>();
		if (wornContainer != null)
		{
			for (Item item : wornContainer.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					worn.add(item.getId());
				}
			}
		}
		herblore().setWorn(worn);
		if (worn.contains(HerbloreRecipes.ALCHEMISTS_AMULET))
		{
			herblore().setAlchemistCharges(client.getVarbitValue(VarbitID.CHARGES_ALCHEMISTS_AMULET_QUANTITY), now);
		}
		boolean unlocked = client.getVarbitValue(VarbitID.MM_POTION_STORAGE_UNLOCKED) > 0;
		storageUnlocked = unlocked;
		ensureSavedStorage();
		if (unlocked && storageDirty && herblore().isBankOpen())
		{
			storageDirty = false;
			readStorage(client.getTickCount(), now);
		}
	}

	@Subscribe
	public void onHerbloreVarbit(VarbitChanged event)
	{
		int varp = event.getVarpId();
		if (isPotionStoreVar(varp))
		{
			storageDirty = true;
		}
	}

	@Subscribe
	public void onHerbloreWidget(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			// Read once the bank has been built (next game tick), never from inside its build script.
			storageDirty = true;
		}
	}

	/** The varps Potion Storage keeps its doses and settings in (any change -> read again while the bank is open). */
	static boolean isPotionStoreVar(int varp)
	{
		return (varp >= VarPlayerID.POTIONSTORE_BASE_VAR_1 && varp <= VarPlayerID.POTIONSTORE_TORSTOLVIAL)
			|| (varp >= VarPlayerID.POTIONSTORE_BLIGHTED_2RESTORE_POTION && varp <= VarPlayerID.POTIONSTORE_CADANTINE_BLOODVIAL)
			|| varp == VarPlayerID.POTIONSTORE_HUASCAVIAL || varp == VarPlayerID.POTIONSTORE_SURGE_POTION
			|| (varp >= VarPlayerID.POTIONSTORE_ARMADYLBREW_POTION && varp <= VarPlayerID.POTIONSTORE_HAEMOSTATIC_POULTICE);
	}

	/**
	 * Every stored potion's doses (quantity for unfinished potions) via the
	 * game's own scripts, mapped to Spyglass's families; the account's Potion
	 * Storage snapshot is refreshed when it changed.
	 */
	void readStorage(int tick, long now)
	{
		Map<String, Long> units = new LinkedHashMap<>();
		List<PotionStorageState.PotionStorageItem> items = new ArrayList<>();
		try
		{
			readEnum(client.getEnum(EnumID.POTIONSTORE_POTIONS), 4, units, items);
			readEnum(client.getEnum(EnumID.POTIONSTORE_UNFINISHED_POTIONS), 1, units, items);
		}
		catch (RuntimeException e)
		{
			log.debug("Herblore companion could not read Potion Storage", e);
			return;
		}
		herblore().onStorage(tick, now, units);
		storageUnits = Collections.unmodifiableMap(units);
		storageObservedAt = null;
		persist(items);
	}

	private void readEnum(EnumComposition list, int maxDoses, Map<String, Long> units, List<PotionStorageState.PotionStorageItem> items)
	{
		if (list == null)
		{
			return;
		}
		for (int potionEnumId : list.getIntVals())
		{
			EnumComposition potion = client.getEnum(potionEnumId);
			if (potion == null)
			{
				continue;
			}
			client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
			int doses = client.getIntStack()[0];
			if (doses <= 0)
			{
				continue;
			}
			client.runScript(ScriptID.POTIONSTORE_WITHDRAW_DOSES, potionEnumId);
			int withdraw = client.getIntStack()[0];
			UnitFamily family = null;
			for (int d = 1; d <= maxDoses && family == null; d++)
			{
				int id = potion.getIntValue(d);
				if (id > 0)
				{
					family = HerbloreRecipes.familyOf(id);
				}
			}
			if (family != null)
			{
				units.merge(family.getKey(), (long) doses, Long::sum);
			}
			int shown = potion.getIntValue(withdraw >= 1 && withdraw <= maxDoses ? withdraw : maxDoses == 1 ? 1 : 4);
			if (shown > 0)
			{
				items.add(new PotionStorageState.PotionStorageItem(shown, itemName(shown), doses, withdraw >= 1 && withdraw <= 4 ? withdraw : 4));
			}
		}
	}

	private String itemName(int itemId)
	{
		String known = HerbloreRecipes.name(itemId);
		if (known != null)
		{
			return known;
		}
		try
		{
			ItemComposition c = client.getItemDefinition(itemId);
			return c == null ? "" : c.getName();
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	/** Refreshes the existing Potion Storage snapshot (same file and schema as the storage system's own capture) when it changed. */
	private void persist(List<PotionStorageState.PotionStorageItem> items)
	{
		StringBuilder sig = new StringBuilder();
		for (PotionStorageState.PotionStorageItem i : items)
		{
			sig.append(i.getItemId()).append(':').append(i.getTotalDoses()).append(';');
		}
		if (sig.toString().equals(lastWrittenSignature))
		{
			return;
		}
		lastWrittenSignature = sig.toString();
		try
		{
			long account = client.getAccountHash();
			if (account == -1L)
			{
				return;
			}
			PotionStorageState state = new PotionStorageState();
			state.setItems(items);
			state.setLastObservedAt(Instant.ofEpochMilli(clock.getAsLong()).toString());
			stateStore.write(TelemetryPaths.stateFile(account, STORAGE_CATEGORY), state);
		}
		catch (RuntimeException e)
		{
			log.debug("Herblore companion could not save the Potion Storage snapshot", e);
		}
	}

	/** The account's saved Potion Storage snapshot, read once per account (before the bank is opened this login). */
	private void ensureSavedStorage()
	{
		long account;
		try
		{
			account = client.getAccountHash();
		}
		catch (RuntimeException e)
		{
			return;
		}
		if (account == -1L || account == savedStorageAccount)
		{
			return;
		}
		savedStorageAccount = account;
		if (herblore().hasLiveStorage())
		{
			return;
		}
		Map<String, Long> saved = savedStorageUnits(stateStore, account);
		if (saved != null)
		{
			storageUnits = saved;
			storageObservedAt = savedObservedAt(stateStore, account);
		}
	}

	/** Family key -> units from the saved snapshot; null when none was ever saved for this account. */
	static Map<String, Long> savedStorageUnits(LocalStateStore store, long account)
	{
		try
		{
			PotionStorageState state = store.readIfExists(TelemetryPaths.stateFile(account, STORAGE_CATEGORY), PotionStorageState.class);
			if (state == null || state.getItems() == null)
			{
				return null;
			}
			Map<String, Long> units = new LinkedHashMap<>();
			for (PotionStorageState.PotionStorageItem item : state.getItems())
			{
				UnitFamily f = item == null ? null : HerbloreRecipes.familyOf(item.getItemId());
				if (f != null && item.getTotalDoses() > 0)
				{
					units.merge(f.getKey(), (long) item.getTotalDoses(), Long::sum);
				}
			}
			return Collections.unmodifiableMap(units);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String savedObservedAt(LocalStateStore store, long account)
	{
		try
		{
			PotionStorageState state = store.readIfExists(TelemetryPaths.stateFile(account, STORAGE_CATEGORY), PotionStorageState.class);
			return state == null ? null : state.getLastObservedAt();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
}
