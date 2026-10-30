package com.osrstelemetry.plugin.account;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/** Synthetic capability snapshots for availability tests (no client). */
public final class AccountFixtures
{
	public static final long ACCOUNT = 4242L;
	public static final AccountOwner OWNER = AccountOwner.standard(ACCOUNT);
	public static final long NOW = 1_800_000_000_000L;

	private AccountFixtures()
	{
	}

	/** Owner, STANDARD, settled, every skill at {@code level}, empty inventory and equipment read, nothing else known. */
	public static AccountCapability.Builder base(int level)
	{
		AccountCapability.Builder b = AccountCapability.builder().owner(OWNER).status(AccountProfileGate.Status.STANDARD).settled(true).membersWorld(Known.YES).capturedAt(NOW)
			.inventory(new HashMap<>()).worn(new HashMap<>());
		for (Skill s : Skill.values())
		{
			if (!"OVERALL".equals(s.name()))
			{
				b.level(s, level);
			}
		}
		return b;
	}

	/** Every quest finished and every readable diary complete (spellbook still unread). */
	public static AccountCapability.Builder maxed()
	{
		AccountCapability.Builder b = base(99);
		for (Quest q : Quest.values())
		{
			b.quest(q, QuestState.FINISHED);
		}
		for (String key : AccountReaders.readableDiaries())
		{
			b.diary(key, true);
		}
		return b;
	}

	/** As if the spellbook varbit mapping had been confirmed live (fixture only). */
	public static AccountCapability.Builder spellbook(AccountCapability.Builder b, AccountReaders.Spellbook book)
	{
		return b.spellbookRaw(book.ordinal()).spellbookMappingVerified(true);
	}

	public static Map<Integer, Integer> items(int... kv)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			out.put(kv[i], kv[i + 1]);
		}
		return out;
	}

	public static StorageEvidence bank(long ageMillis, int... kv)
	{
		return new StorageEvidence(StorageEvidence.Kind.BANK, items(kv), NOW - ageMillis);
	}

	public static StorageEvidence group(long ageMillis, int... kv)
	{
		return new StorageEvidence(StorageEvidence.Kind.GROUP_STORAGE, items(kv), NOW - ageMillis);
	}
}
