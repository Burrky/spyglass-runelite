package com.osrstelemetry.plugin.account;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * Generic account knowledge for one owner at one moment (canonical §12.1):
 * what the account can be shown to have, and nothing else. Immutable; rebuilt
 * by {@link AccountCapabilityAdapter} each tick. Not Farming-owned.
 *
 * <h3>Unknown is explicit</h3>
 * Every input distinguishes "read and found X" from "not read":
 * <ul>
 *   <li>{@code levels} empty → skills not yet loaded; {@link #level} null.</li>
 *   <li>a quest absent from {@code quests} → not read (before the login settle,
 *   or not of interest); {@link #quest} null.</li>
 *   <li>a diary absent → no verified source or not yet read; {@link #diary} UNKNOWN.</li>
 *   <li>{@code spellbookRaw} null → not read; a raw value is never turned into a
 *   book unless it is one of the four verified values ({@link AccountReaders#spellbookFromVarbit}).</li>
 *   <li>{@code inventory}, {@code worn}, {@code runePouch} null → not read
 *   (logged out, container not sent yet); an empty map → read, holds nothing.</li>
 *   <li>{@code bank}, {@code groupStorage} null → never seen for this owner,
 *   which says nothing about what the account owns.</li>
 * </ul>
 * A capability with no owner ({@link #none}) carries only its gate status:
 * logout or incomplete metadata never produce facts.
 */
public final class AccountCapability
{
	private final AccountOwner owner;
	private final AccountProfileGate.Status status;
	private final boolean settled;
	private final Known membersWorld;
	private final Map<Skill, Integer> levels;
	private final Map<Quest, QuestState> quests;
	private final Map<String, Boolean> diaries;
	private final Integer spellbookRaw;
	private final Map<Integer, Integer> varbits;
	private final Map<Integer, Integer> varps;
	private final java.util.Set<Integer> wornSlots;
	/** The player's carried weight in kg as the game shows it (worn weight-reducing items counted), or null when not read. */
	private final Integer weight;
	private final boolean spellbookMappingVerified;
	private final Map<Integer, Integer> inventory;
	private final Integer inventorySlotsUsed;
	private final Map<Integer, Integer> worn;
	private final Map<Integer, Integer> runePouch;
	private final ChargeKnowledge charges;
	private final StorageEvidence bank;
	private final StorageEvidence groupStorage;
	private final StorageEvidence seedVault;
	private final long capturedAt;

	private AccountCapability(Builder b)
	{
		this.owner = b.owner;
		this.status = b.status;
		this.settled = b.settled;
		this.membersWorld = b.membersWorld;
		this.levels = Collections.unmodifiableMap(b.levels.isEmpty() ? new EnumMap<>(Skill.class) : new EnumMap<>(b.levels));
		this.quests = Collections.unmodifiableMap(b.quests.isEmpty() ? new EnumMap<>(Quest.class) : new EnumMap<>(b.quests));
		this.diaries = Collections.unmodifiableMap(new LinkedHashMap<>(b.diaries));
		this.spellbookRaw = b.spellbookRaw;
		this.varbits = Collections.unmodifiableMap(new LinkedHashMap<>(b.varbits));
		this.varps = Collections.unmodifiableMap(new LinkedHashMap<>(b.varps));
		this.wornSlots = b.wornSlots == null ? null : Collections.unmodifiableSet(new java.util.TreeSet<>(b.wornSlots));
		this.weight = b.weight;
		this.spellbookMappingVerified = b.spellbookMappingVerified;
		this.inventory = copy(b.inventory);
		this.inventorySlotsUsed = b.inventorySlotsUsed;
		this.worn = copy(b.worn);
		this.runePouch = copy(b.runePouch);
		this.charges = b.charges == null ? ChargeKnowledge.EMPTY : b.charges;
		this.bank = b.bank;
		this.groupStorage = b.groupStorage;
		this.seedVault = b.seedVault;
		this.capturedAt = b.capturedAt;
	}

	private static Map<Integer, Integer> copy(Map<Integer, Integer> m)
	{
		return m == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(m));
	}

	/**
	 * Same facts as {@code o} (everything but the capture time). The adapter keeps publishing the same snapshot while
	 * nothing changes, so consumers can memoise on identity: a fresh object every tick made every consumer recompute
	 * every tick (live finding 2026-10-21: preparation re-evaluated the whole travel catalogue each tick).
	 */
	public boolean sameContent(AccountCapability o)
	{
		return o != null && Objects.equals(owner, o.owner) && status == o.status && settled == o.settled && membersWorld == o.membersWorld
			&& levels.equals(o.levels) && quests.equals(o.quests) && diaries.equals(o.diaries) && Objects.equals(spellbookRaw, o.spellbookRaw)
			&& varbits.equals(o.varbits) && varps.equals(o.varps) && Objects.equals(wornSlots, o.wornSlots) && Objects.equals(weight, o.weight)
			&& spellbookMappingVerified == o.spellbookMappingVerified && Objects.equals(inventory, o.inventory)
			&& Objects.equals(inventorySlotsUsed, o.inventorySlotsUsed) && Objects.equals(worn, o.worn) && Objects.equals(runePouch, o.runePouch) && charges.equals(o.charges)
			&& StorageEvidence.same(bank, o.bank) && StorageEvidence.same(groupStorage, o.groupStorage) && StorageEvidence.same(seedVault, o.seedVault);
	}

	/** No owner: nothing about any account is known. */
	public static AccountCapability none(AccountProfileGate.Status status, long now)
	{
		return builder().status(status).capturedAt(now).build();
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public AccountOwner getOwner()
	{
		return owner;
	}

	public AccountProfileGate.Status getStatus()
	{
		return status;
	}

	/**
	 * True once the account's server variables are evidenced as loaded for this
	 * login (see {@link AccountCapabilityAdapter}): quest, diary, spellbook and
	 * rune pouch facts are read only then. A delay alone never sets it.
	 */
	public boolean isSettled()
	{
		return settled;
	}

	/** Whether the current world is a members world (from the world type); UNKNOWN without an owner. */
	public Known getMembersWorld()
	{
		return membersWorld;
	}

	public long getCapturedAt()
	{
		return capturedAt;
	}

	/** Real (unboosted) level, or null when skills are not loaded. */
	public Integer level(Skill skill)
	{
		return levels.get(skill);
	}

	public Map<Skill, Integer> getLevels()
	{
		return levels;
	}

	/** The quest's state, or null when it has not been read. */
	public QuestState quest(Quest quest)
	{
		return quests.get(quest);
	}

	public Map<Quest, QuestState> getQuests()
	{
		return quests;
	}

	public Known diary(String key)
	{
		Boolean complete = diaries.get(key);
		return complete == null ? Known.UNKNOWN : Known.of(complete);
	}

	public Map<String, Boolean> getDiaries()
	{
		return diaries;
	}

	/**
	 * A domain-registered varbit's value (e.g. Farming's unlock varbits), or
	 * null when not read (not registered, or account data not yet loaded).
	 */
	public Integer varbit(int varbitId)
	{
		return varbits.get(varbitId);
	}

	/** Occupied equipment slot indices (EquipmentInventorySlot order), or null when not read. */
	/** Carried weight in kg (null: not read). */
	public Integer getWeight()
	{
		return weight;
	}

	public java.util.Set<Integer> getWornSlots()
	{
		return wornSlots;
	}

	/** A domain-registered varp's value (e.g. navigation's unlock checks), or null when not read. */
	public Integer varp(int varpId)
	{
		return varps.get(varpId);
	}

	public Integer getSpellbookRaw()
	{
		return spellbookRaw;
	}

	/** The current spellbook, or null when unread or when the varbit mapping is not verified. */
	public AccountReaders.Spellbook spellbook()
	{
		if (spellbookRaw == null || !spellbookMappingVerified)
		{
			return null;
		}
		return AccountReaders.spellbookFromVarbit(spellbookRaw);
	}

	public Map<Integer, Integer> getInventory()
	{
		return inventory;
	}

	/** Inventory slots holding an item, read from the container itself (null: not read). */
	public Integer getInventorySlotsUsed()
	{
		return inventorySlotsUsed;
	}

	public Map<Integer, Integer> getWorn()
	{
		return worn;
	}

	/** The rune pouch's contents (item → quantity), or null when not read. Says nothing about where the pouch is. */
	/** Observed charges and daily uses of teleport items (never assumed; empty when nothing was observed). */
	public ChargeKnowledge getCharges()
	{
		return charges;
	}

	public Map<Integer, Integer> getRunePouch()
	{
		return runePouch;
	}

	public StorageEvidence getBank()
	{
		return bank;
	}

	public StorageEvidence getGroupStorage()
	{
		return groupStorage;
	}

	public StorageEvidence getSeedVault()
	{
		return seedVault;
	}

	public static final class Builder
	{
		private AccountOwner owner;
		private AccountProfileGate.Status status = AccountProfileGate.Status.NOT_LOGGED_IN;
		private boolean settled;
		private Known membersWorld = Known.UNKNOWN;
		private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		private final Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
		private final Map<String, Boolean> diaries = new LinkedHashMap<>();
		private Integer spellbookRaw;
		private final Map<Integer, Integer> varbits = new LinkedHashMap<>();
		private final Map<Integer, Integer> varps = new LinkedHashMap<>();
		private java.util.Set<Integer> wornSlots;
		private Integer weight;

		public Builder weight(Integer kg)
		{
			this.weight = kg;
			return this;
		}
		private boolean spellbookMappingVerified = AccountReaders.SPELLBOOK_MAPPING_VERIFIED;
		private Map<Integer, Integer> inventory;
		private Integer inventorySlotsUsed;
		private Map<Integer, Integer> worn;
		private Map<Integer, Integer> runePouch;
		private StorageEvidence bank;
		private StorageEvidence groupStorage;
		private StorageEvidence seedVault;
		private long capturedAt;

		public Builder owner(AccountOwner owner)
		{
			this.owner = owner;
			return this;
		}

		public Builder status(AccountProfileGate.Status status)
		{
			this.status = status;
			return this;
		}

		public Builder settled(boolean settled)
		{
			this.settled = settled;
			return this;
		}

		public Builder membersWorld(Known members)
		{
			this.membersWorld = members == null ? Known.UNKNOWN : members;
			return this;
		}

		public Builder level(Skill skill, int level)
		{
			levels.put(skill, level);
			return this;
		}

		public Builder levels(Map<Skill, Integer> all)
		{
			levels.putAll(all);
			return this;
		}

		public Builder quest(Quest quest, QuestState state)
		{
			quests.put(quest, state);
			return this;
		}

		public Builder quests(Map<Quest, QuestState> all)
		{
			quests.putAll(all);
			return this;
		}

		public Builder diary(String key, boolean complete)
		{
			diaries.put(key, complete);
			return this;
		}

		public Builder diaries(Map<String, Boolean> all)
		{
			diaries.putAll(all);
			return this;
		}

		public Builder varbit(int id, int value)
		{
			varbits.put(id, value);
			return this;
		}

		public Builder wornSlots(java.util.Set<Integer> slots)
		{
			this.wornSlots = slots;
			return this;
		}

		public Builder varp(int id, int value)
		{
			varps.put(id, value);
			return this;
		}

		public Builder spellbookRaw(Integer raw)
		{
			this.spellbookRaw = raw;
			return this;
		}

		/**
		 * Fixture seam: lets tests evaluate spellbook differences as if the
		 * mapping were verified. Production always uses
		 * {@link AccountReaders#SPELLBOOK_MAPPING_VERIFIED}.
		 */
		Builder spellbookMappingVerified(boolean verified)
		{
			this.spellbookMappingVerified = verified;
			return this;
		}

		public Builder inventory(Map<Integer, Integer> items)
		{
			this.inventory = items;
			return this;
		}

		public Builder inventorySlotsUsed(Integer slots)
		{
			this.inventorySlotsUsed = slots;
			return this;
		}

		public Builder worn(Map<Integer, Integer> items)
		{
			this.worn = items;
			return this;
		}

		private ChargeKnowledge charges;

		public Builder charges(ChargeKnowledge charges)
		{
			this.charges = charges;
			return this;
		}

		public Builder runePouch(Map<Integer, Integer> contents)
		{
			this.runePouch = contents;
			return this;
		}

		public Builder bank(StorageEvidence bank)
		{
			this.bank = bank;
			return this;
		}

		public Builder groupStorage(StorageEvidence group)
		{
			this.groupStorage = group;
			return this;
		}

		public Builder seedVault(StorageEvidence vault)
		{
			this.seedVault = vault;
			return this;
		}

		public Builder capturedAt(long at)
		{
			this.capturedAt = at;
			return this;
		}

		public AccountCapability build()
		{
			if (owner == null)
			{
				// Without an owner there is no account to know anything about: drop every fact.
				Builder empty = new Builder();
				empty.status = status == AccountProfileGate.Status.STANDARD ? AccountProfileGate.Status.NOT_READY : status;
				empty.capturedAt = capturedAt;
				return new AccountCapability(empty);
			}
			return new AccountCapability(this);
		}
	}
}
