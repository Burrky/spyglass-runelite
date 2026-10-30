package com.osrstelemetry.plugin.prep;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountReaders;
import com.osrstelemetry.plugin.account.ItemLocator;
import com.osrstelemetry.plugin.account.StorageEvidence;
import com.osrstelemetry.plugin.travel.RuneSources;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a {@link PreparationRequest} against an {@link AccountCapability}
 * (canonical §8.3). Pure; recomputed whenever the capability changes (every
 * tick the inventory, equipment or bank changes).
 *
 * <ul>
 *   <li>Carried counts are live; bank, group storage and seed vault counts are
 *   as last seen, with their age. No age-based downgrade (D-F3): an old bank
 *   view is still evidence, shown with when it was seen.</li>
 *   <li>Runes ({@link ItemNeed.SatisfiedBy#INVENTORY_OR_RUNE_POUCH}) count the
 *   inventory, a carried rune pouch's contents, combination runes and worn
 *   rune providers ({@link RuneSources}).</li>
 *   <li>Not found anywhere observed is {@link NeedStatus#NOT_FOUND} only when
 *   the bank has been seen; before that it is {@link NeedStatus#UNKNOWN}.</li>
 * </ul>
 */
public final class PreparationEvaluator
{
	private PreparationEvaluator()
	{
	}

	public static PreparationState evaluate(PreparationRequest request, AccountCapability cap, long now)
	{
		List<NeedResult> results = new ArrayList<>();
		for (ItemNeed need : request.getNeeds())
		{
			results.add(evaluate(need, cap, now));
		}
		return new PreparationState(request, results);
	}

	/** Large enough to cover any quantity, small enough that sums never overflow. */
	static final long COVERS_ANY = Long.MAX_VALUE / 4;

	static NeedResult evaluate(ItemNeed need, AccountCapability cap, long now)
	{
		long required = need.required();
		if (cap == null || cap.getOwner() == null || cap.getInventory() == null || cap.getWorn() == null)
		{
			return new NeedResult(need, NeedStatus.UNKNOWN, 0, 0, -1, -1, "? inventory not read");
		}
		boolean rune = need.getSatisfiedBy() == ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH;
		long carried = rune ? carriedRunes(need, cap) : carriedItems(need, cap);
		int carriedItem = rune ? -1 : firstCarried(need, cap);
		if (carried >= required)
		{
			NeedResult.Via via = carriedVia(need, cap);
			return new NeedResult(need, NeedStatus.CARRIED, carried, 0, -1, -1, carriedText(via)).via(via).display(carriedItem);
		}
		boolean inInventoryOnly = need.getSatisfiedBy() == ItemNeed.SatisfiedBy.WORN_ONLY && count(need, cap.getInventory()) > 0;
		if (need.getReachableLabel() != null && carried + need.getReachableUnits() >= required)
		{
			return new NeedResult(need, NeedStatus.REACHABLE, carried, 0, -1, -1, "stored: " + need.getReachableLabel()).display(carriedItem);
		}
		if (inInventoryOnly)
		{
			// Carried but not worn: never satisfied (the effect needs it equipped), never sent to the bank either.
			return new NeedResult(need, NeedStatus.CARRIED, carried, 0, -1, -1, "carried, not worn").carriedNotWorn(true)
				.display(firstCarriedAnywhere(need, cap)).via(NeedResult.Via.INVENTORY);
		}
		if (rune && pouchCarried(cap) && cap.getRunePouch() == null)
		{
			return new NeedResult(need, NeedStatus.UNKNOWN, carried, 0, -1, -1, "? rune pouch not read").display(carriedItem);
		}
		String have = carried > 0 ? "have " + carried + " \u00b7 " : "";
		StorageEvidence bank = cap.getBank();
		if (bank == null)
		{
			return new NeedResult(need, NeedStatus.UNKNOWN, carried, 0, -1, -1, have + "? bank not seen yet").display(carriedItem);
		}
		long banked = rune ? bankedRunes(need, cap, bank) : count(need, bank.getItems());
		int bankItem = firstIn(need, bank);
		String bankAge = ItemLocator.age(now - bank.getObservedAt());
		// Part of it stored where it is used (the tool leprechaun): only the rest needs taking from the bank.
		long stored = need.getReachableLabel() != null ? Math.max(0, need.getReachableUnits()) : 0;
		if (carried + stored + banked >= required)
		{
			int tab = bankItem > 0 ? bank.tabOf(bankItem) : -1;
			String where = "bank" + (tab > 0 ? " \u00b7 tab " + tab : tab == 0 ? " \u00b7 main tab" : "") + " (" + bankAge + ")";
			return new NeedResult(need, NeedStatus.IN_BANK, carried, banked, tab, bankItem, have + where)
				.bankSeenAt(bank.getObservedAt()).display(carriedItem > 0 ? carriedItem : bankItem)
				.withdraw(withdrawItems(need, required - Math.min(carried + stored, required), bankItem, rune));
		}
		long elsewhere = 0;
		long elsewhereSeen = -1;
		int elsewhereItem = -1;
		List<String> places = new ArrayList<>();
		for (StorageEvidence e : new StorageEvidence[]{cap.getGroupStorage(), cap.getSeedVault()})
		{
			if (e != null)
			{
				long n = count(need, e.getItems());
				if (n > 0)
				{
					elsewhere += n;
					places.add(e.getKind() == StorageEvidence.Kind.GROUP_STORAGE ? "group storage" : "seed vault");
					elsewhereSeen = Math.max(elsewhereSeen, e.getObservedAt());
					if (elsewhereItem < 0)
					{
						elsewhereItem = firstIn(need, e);
					}
				}
			}
		}
		int shown = carriedItem > 0 ? carriedItem : bankItem > 0 ? bankItem : elsewhereItem;
		if (carried + banked + elsewhere >= required)
		{
			List<String> labels = new ArrayList<>();
			for (String place : places)
			{
				labels.add(place.equals("group storage") ? "group storage (shared)" : place);
			}
			return new NeedResult(need, NeedStatus.ELSEWHERE, carried, banked, -1, bankItem, have + String.join(" + ", labels))
				.bankSeenAt(bank.getObservedAt()).elsewhere(places, elsewhereSeen).display(shown);
		}
		long found = Math.min(carried + banked + elsewhere, COVERS_ANY);
		return new NeedResult(need, NeedStatus.NOT_FOUND, carried, banked, -1, bankItem,
			(found > 0 ? "only " + found + " found" : "not found") + " \u00b7 bank seen " + bankAge + " ago")
			.bankSeenAt(bank.getObservedAt()).found(found).display(shown);
	}

	/** Items to take out of the bank to cover {@code units} more (counted in the need's units). */
	private static long withdrawItems(ItemNeed need, long units, int bankItem, boolean rune)
	{
		if (units <= 0)
		{
			return 0;
		}
		if (rune || bankItem <= 0)
		{
			return units;
		}
		int per = need.unitsOf(bankItem);
		if (per == ItemNeed.COVERS_ANY)
		{
			return 1;
		}
		return per <= 1 ? units : (units + per - 1) / per;
	}

	private static long carriedItems(ItemNeed need, AccountCapability cap)
	{
		if (need.getSatisfiedBy() == ItemNeed.SatisfiedBy.WORN_ONLY)
		{
			return count(need, cap.getWorn());
		}
		long n = count(need, cap.getInventory());
		if (need.getSatisfiedBy() == ItemNeed.SatisfiedBy.INVENTORY_OR_WORN)
		{
			n += count(need, cap.getWorn());
		}
		return Math.min(n, COVERS_ANY);
	}

	private static int firstCarriedAnywhere(ItemNeed need, AccountCapability cap)
	{
		for (int id : need.getAcceptableItemIds())
		{
			if (ItemLocator.count(cap.getInventory(), id) > 0 || ItemLocator.count(cap.getWorn(), id) > 0)
			{
				return id;
			}
		}
		return -1;
	}

	private static int firstCarried(ItemNeed need, AccountCapability cap)
	{
		for (int id : need.getAcceptableItemIds())
		{
			boolean worn = ItemLocator.count(cap.getWorn(), id) > 0;
			if (need.getSatisfiedBy() == ItemNeed.SatisfiedBy.WORN_ONLY ? worn
				: ItemLocator.count(cap.getInventory(), id) > 0 || (need.getSatisfiedBy() == ItemNeed.SatisfiedBy.INVENTORY_OR_WORN && worn))
			{
				return id;
			}
		}
		return -1;
	}

	/** Units in a container: items × units per item; an item that covers any quantity covers it. */
	private static long count(ItemNeed need, Map<Integer, Integer> items)
	{
		long n = 0;
		for (int id : need.getAcceptableItemIds())
		{
			long c = ItemLocator.count(items, id);
			if (c <= 0)
			{
				continue;
			}
			int per = need.unitsOf(id);
			if (per == ItemNeed.COVERS_ANY)
			{
				return COVERS_ANY;
			}
			n += c * Math.max(0, per);
		}
		return Math.min(n, COVERS_ANY);
	}

	private static int firstIn(ItemNeed need, StorageEvidence e)
	{
		for (int id : need.getAcceptableItemIds())
		{
			if (e.count(id) > 0)
			{
				return id;
			}
		}
		return -1;
	}

	static boolean pouchCarried(AccountCapability cap)
	{
		for (int id : AccountReaders.RUNE_POUCH_ITEMS)
		{
			if (ItemLocator.count(cap.getInventory(), id) > 0)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean pouchIn(StorageEvidence e)
	{
		for (int id : AccountReaders.RUNE_POUCH_ITEMS)
		{
			if (e.count(id) > 0)
			{
				return true;
			}
		}
		return false;
	}

	private static long carriedRunes(ItemNeed need, AccountCapability cap)
	{
		Map<Integer, Integer> runes = new HashMap<>(cap.getInventory());
		if (pouchCarried(cap) && cap.getRunePouch() != null)
		{
			cap.getRunePouch().forEach((k, v) -> runes.merge(k, v, Integer::sum));
		}
		return RuneSources.supply(runes, cap.getWorn()).getOrDefault(need.getAcceptableItemIds()[0], 0L);
	}

	private static long bankedRunes(ItemNeed need, AccountCapability cap, StorageEvidence bank)
	{
		Map<Integer, Integer> runes = new HashMap<>(bank.getItems());
		if (!pouchCarried(cap) && pouchIn(bank) && cap.getRunePouch() != null)
		{
			cap.getRunePouch().forEach((k, v) -> runes.merge(k, v, Integer::sum));
		}
		return RuneSources.supply(runes, bank.getItems()).getOrDefault(need.getAcceptableItemIds()[0], 0L);
	}

	private static NeedResult.Via carriedVia(ItemNeed need, AccountCapability cap)
	{
		if (need.getSatisfiedBy() == ItemNeed.SatisfiedBy.INVENTORY_OR_RUNE_POUCH)
		{
			int base = need.getAcceptableItemIds()[0];
			for (Integer worn : cap.getWorn().keySet())
			{
				for (int r : RuneSources.providedBy(worn))
				{
					if (r == base)
					{
						return NeedResult.Via.STAFF;
					}
				}
			}
			if (ItemLocator.count(cap.getInventory(), base) >= need.required())
			{
				return NeedResult.Via.INVENTORY;
			}
			return pouchCarried(cap) ? NeedResult.Via.RUNE_POUCH : NeedResult.Via.INVENTORY;
		}
		if (need.getSatisfiedBy() == ItemNeed.SatisfiedBy.WORN_ONLY
			|| need.getSatisfiedBy() == ItemNeed.SatisfiedBy.INVENTORY_OR_WORN && count(need, cap.getInventory()) < need.required())
		{
			return NeedResult.Via.WORN;
		}
		return NeedResult.Via.INVENTORY;
	}

	private static String carriedText(NeedResult.Via via)
	{
		switch (via)
		{
			case WORN:
				return "worn";
			case RUNE_POUCH:
				return "pouch";
			case STAFF:
				return "staff";
			default:
				return "carried";
		}
	}
}
