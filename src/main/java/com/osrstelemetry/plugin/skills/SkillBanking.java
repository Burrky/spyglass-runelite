package com.osrstelemetry.plugin.skills;

import java.util.Map;
import java.util.function.IntPredicate;

/**
 * The BANKED half of every skill companion's per-tick settlement (generic;
 * shared by Mining and Fishing so the rule exists once):
 *
 * <ul>
 * <li>Bank open: an inventory decrease matched by a bank increase of the same
 * item on the same tick is a deposit of up to that much; the rest left for
 * somewhere unknown.</li>
 * <li>Deposit box open: every inventory decrease is a deposit (the box has
 * no other destination).</li>
 * <li>Bank open and the bank gained a STORABLE item the inventory did not
 * give: the open specialized storage was emptied into the bank (the whole
 * container goes, so it is now known to be empty).</li>
 * </ul>
 * Only this session's still-held production can ever be banked
 * (ProductionLedger); withdrawals change nothing here.
 */
public final class SkillBanking
{
	/** Receives each proven deposit of this session's production. */
	public interface DepositSink
	{
		void deposited(int itemId, long quantity);
	}

	private SkillBanking()
	{
	}

	/** Snapshot diff: {@code net} += after - before, per item (zero entries removed). */
	public static void diffInto(Map<Integer, Long> before, Map<Integer, Long> after, Map<Integer, Long> net)
	{
		for (Map.Entry<Integer, Long> e : after.entrySet())
		{
			long d = e.getValue() - before.getOrDefault(e.getKey(), 0L);
			if (d != 0)
			{
				net.merge(e.getKey(), d, Long::sum);
			}
		}
		for (Map.Entry<Integer, Long> e : before.entrySet())
		{
			if (!after.containsKey(e.getKey()) && e.getValue() != 0)
			{
				net.merge(e.getKey(), -e.getValue(), Long::sum);
			}
		}
		net.values().removeIf(v -> v == 0);
	}

	/**
	 * Settles one tick on which the bank or a deposit box was open. Returns
	 * true when anything the player can see changed (something banked, or the
	 * storage emptied).
	 */
	public static boolean settleBankingTick(Map<Integer, Long> invNet, Map<Integer, Long> bankNet, boolean depositBoxOpen,
		ProductionLedger ledger, SkillStorage storage, IntPredicate storable, DepositSink sink)
	{
		boolean changed = false;
		for (Map.Entry<Integer, Long> d : invNet.entrySet())
		{
			long removed = -d.getValue();
			if (removed <= 0)
			{
				continue;
			}
			int item = d.getKey();
			long proven = depositBoxOpen ? removed : Math.min(removed, Math.max(0L, bankNet.getOrDefault(item, 0L)));
			long banked = ledger.bankedFromInventory(item, proven);
			if (banked > 0)
			{
				sink.deposited(item, banked);
				changed = true;
			}
			ledger.inventoryRemoved(item, removed - proven);
		}
		if (storage != null && storage.isHeld())
		{
			boolean emptied = false;
			for (Map.Entry<Integer, Long> d : bankNet.entrySet())
			{
				if (d.getValue() <= 0 || !storable.test(d.getKey()))
				{
					continue;
				}
				long fromInventory = Math.max(0L, -invNet.getOrDefault(d.getKey(), 0L));
				long fromStorage = d.getValue() - fromInventory;
				if (fromStorage > 0)
				{
					emptied = true;
					long banked = ledger.bankedFromStorage(d.getKey(), fromStorage);
					if (banked > 0)
					{
						sink.deposited(d.getKey(), banked);
					}
				}
			}
			if (emptied)
			{
				storage.emptiedToBank();
				changed = true;
			}
		}
		return changed;
	}
}
