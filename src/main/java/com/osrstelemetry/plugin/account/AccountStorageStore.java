package com.osrstelemetry.plugin.account;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Profile-scoped storage evidence (bank, group storage, seed vault) for account
 * capability: {@code {accountHash}/profiles/standard/account/storages.json}.
 *
 * <h3>Why not the existing bank.json</h3>
 * The legacy {@code bank.json} / bank snapshots are keyed by account hash only
 * (ARCH-DEBT-PROFILE): a Seasonal or other non-standard profile's bank would be
 * indistinguishable from the main game's. Account capability therefore keeps
 * its own STANDARD-profile evidence, captured from the same native container.
 *
 * <h3>Ownership</h3>
 * Same discipline as Farming's stores: {@link #switchOwner} bumps a generation,
 * a load applies only to the owner and generation it was started for and only
 * if the file names that owner, and every write goes to the owner it belongs
 * to. Evidence observed before the owner's file has loaded is kept and wins
 * (it is newer). Thread-safe.
 *
 * <h3>Retention</h3>
 * Evidence is replaced only by a newer observation of the same storage. There
 * is no age-based expiry (D-F3, closed): its age is shown, never used to drop it.
 */
@Slf4j
public final class AccountStorageStore
{
	public static final int SCHEMA = 1;
	static final String FILE = "storages.json";

	/** Persisted {@code storages.json}. */
	static final class Document
	{
		int schema = SCHEMA;
		long accountHash;
		String profile;
		Stored bank;
		Stored groupStorage;
		Stored seedVault;

		boolean belongsTo(AccountOwner owner)
		{
			return schema == SCHEMA && accountHash == owner.getAccountHash() && owner.getProfileKey().equals(profile);
		}
	}

	static final class Stored
	{
		long observedAt;
		Map<Integer, Integer> items;
		Map<Integer, Integer> firstSlot;
		int[] tabCounts;

		StorageEvidence toEvidence(StorageEvidence.Kind kind)
		{
			return items == null || observedAt <= 0 ? null : new StorageEvidence(kind, items, observedAt, firstSlot, tabCounts);
		}

		static Stored of(StorageEvidence e)
		{
			if (e == null)
			{
				return null;
			}
			Stored s = new Stored();
			s.observedAt = e.getObservedAt();
			s.items = new LinkedHashMap<>(e.getItems());
			s.firstSlot = e.getFirstSlots().isEmpty() ? null : new LinkedHashMap<>(e.getFirstSlots());
			s.tabCounts = e.getTabCounts();
			return s;
		}
	}

	private final LocalStateStore stateStore;
	private final Function<AccountOwner, Filepath> dirs;
	private final Executor loadExecutor;

	private AccountOwner owner;
	private long generation;
	private boolean loaded;
	private StorageEvidence bank;
	private StorageEvidence groupStorage;
	private StorageEvidence seedVault;
	private boolean dirtyBeforeLoad;

	public AccountStorageStore(LocalStateStore stateStore, Function<AccountOwner, Filepath> dirs, Executor loadExecutor)
	{
		this.stateStore = stateStore;
		this.dirs = dirs;
		this.loadExecutor = loadExecutor;
	}

	/** Makes {@code next} the current owner (null = none). All in-memory evidence is dropped first. */
	public synchronized void switchOwner(AccountOwner next)
	{
		if (next == null ? owner == null : next.equals(owner))
		{
			return;
		}
		generation++;
		owner = next;
		loaded = false;
		bank = null;
		groupStorage = null;
		seedVault = null;
		dirtyBeforeLoad = false;
		if (next != null)
		{
			long loadGeneration = generation;
			Filepath dir = dirs.apply(next);
			loadExecutor.execute(() -> deliver(loadGeneration, next, stateStore.readIfExists(dir.joinSegment(FILE), Document.class)));
		}
	}

	synchronized void deliver(long loadGeneration, AccountOwner loadOwner, Document doc)
	{
		if (loadGeneration != generation || !loadOwner.equals(owner))
		{
			return;
		}
		loaded = true;
		if (doc != null && doc.belongsTo(loadOwner))
		{
			if (bank == null && doc.bank != null)
			{
				bank = doc.bank.toEvidence(StorageEvidence.Kind.BANK);
			}
			if (groupStorage == null && doc.groupStorage != null)
			{
				groupStorage = doc.groupStorage.toEvidence(StorageEvidence.Kind.GROUP_STORAGE);
			}
			if (seedVault == null && doc.seedVault != null)
			{
				seedVault = doc.seedVault.toEvidence(StorageEvidence.Kind.SEED_VAULT);
			}
		}
		else if (doc != null)
		{
			log.warn("Spyglass account: ignoring a storages file that does not belong to {}", loadOwner);
		}
		if (dirtyBeforeLoad)
		{
			dirtyBeforeLoad = false;
			write();
		}
	}

	/** Records what a storage held, for {@code expected} only; ignored when {@code expected} is not the current owner. */
	public synchronized void observe(AccountOwner expected, StorageEvidence evidence)
	{
		if (expected == null || !expected.equals(owner) || evidence == null)
		{
			return;
		}
		StorageEvidence previous = get(evidence.getKind());
		if (previous != null && previous.getItems().equals(evidence.getItems()) && previous.getFirstSlots().equals(evidence.getFirstSlots())
			&& java.util.Arrays.equals(previous.getTabCounts(), evidence.getTabCounts()))
		{
			return; // same contents and layout: keep the earlier observation time rather than rewriting
		}
		switch (evidence.getKind())
		{
			case BANK:
				bank = evidence;
				break;
			case GROUP_STORAGE:
				groupStorage = evidence;
				break;
			default:
				seedVault = evidence;
				break;
		}
		if (loaded)
		{
			write();
		}
		else
		{
			dirtyBeforeLoad = true;
		}
	}

	public synchronized AccountOwner getOwner()
	{
		return owner;
	}

	public synchronized boolean isLoaded()
	{
		return loaded;
	}

	/** The bank evidence for {@code expected}, or null (never another owner's). */
	public synchronized StorageEvidence bankFor(AccountOwner expected)
	{
		return expected != null && expected.equals(owner) ? bank : null;
	}

	public synchronized StorageEvidence groupStorageFor(AccountOwner expected)
	{
		return expected != null && expected.equals(owner) ? groupStorage : null;
	}

	public synchronized StorageEvidence seedVaultFor(AccountOwner expected)
	{
		return expected != null && expected.equals(owner) ? seedVault : null;
	}

	private StorageEvidence get(StorageEvidence.Kind kind)
	{
		switch (kind)
		{
			case BANK:
				return bank;
			case GROUP_STORAGE:
				return groupStorage;
			default:
				return seedVault;
		}
	}

	private void write()
	{
		if (owner == null || !loaded)
		{
			return; // never overwrite an owner's file before it has been read
		}
		Document doc = new Document();
		doc.accountHash = owner.getAccountHash();
		doc.profile = owner.getProfileKey();
		doc.bank = Stored.of(bank);
		doc.groupStorage = Stored.of(groupStorage);
		doc.seedVault = Stored.of(seedVault);
		stateStore.write(dirs.apply(owner).joinSegment(FILE), doc);
	}
}
