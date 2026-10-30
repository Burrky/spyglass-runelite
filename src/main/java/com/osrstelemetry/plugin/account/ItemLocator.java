package com.osrstelemetry.plugin.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where an item was seen for one account: carried (worn, inventory: live) or
 * in a storage (bank, group storage, seed vault: as last seen, with its age).
 * Generic and pure. "Not found" only ever means "not in the places observed",
 * never "not owned".
 */
public final class ItemLocator
{
	public enum Place
	{
		WORN(true, "worn"),
		INVENTORY(true, "inventory"),
		BANK(false, "bank"),
		GROUP_STORAGE(false, "group storage"),
		SEED_VAULT(false, "seed vault");

		private final boolean carried;
		private final String label;

		Place(boolean carried, String label)
		{
			this.carried = carried;
			this.label = label;
		}

		public boolean isCarried()
		{
			return carried;
		}

		public String getLabel()
		{
			return label;
		}
	}

	public static final class Found
	{
		private final Place place;
		private final int quantity;
		/** Storage observation time; 0 for carried places (live). */
		private final long observedAt;

		Found(Place place, int quantity, long observedAt)
		{
			this.place = place;
			this.quantity = quantity;
			this.observedAt = observedAt;
		}

		public Place getPlace()
		{
			return place;
		}

		public int getQuantity()
		{
			return quantity;
		}

		public long getObservedAt()
		{
			return observedAt;
		}
	}

	private ItemLocator()
	{
	}

	/** Every place the item was seen, carried places first, then storages. */
	public static List<Found> find(AccountCapability cap, int itemId)
	{
		List<Found> out = new ArrayList<>();
		add(out, Place.WORN, cap.getWorn(), itemId, 0L);
		add(out, Place.INVENTORY, cap.getInventory(), itemId, 0L);
		storage(out, Place.BANK, cap.getBank(), itemId);
		storage(out, Place.GROUP_STORAGE, cap.getGroupStorage(), itemId);
		storage(out, Place.SEED_VAULT, cap.getSeedVault(), itemId);
		return out;
	}

	/** The best (first) place the item was seen, or null. */
	public static Found best(AccountCapability cap, int itemId)
	{
		List<Found> all = find(cap, itemId);
		return all.isEmpty() ? null : all.get(0);
	}

	public static int count(Map<Integer, Integer> items, int itemId)
	{
		return items == null ? 0 : Math.max(0, items.getOrDefault(itemId, 0));
	}

	private static void add(List<Found> out, Place place, Map<Integer, Integer> items, int itemId, long at)
	{
		int n = count(items, itemId);
		if (n > 0)
		{
			out.add(new Found(place, n, at));
		}
	}

	private static void storage(List<Found> out, Place place, StorageEvidence e, int itemId)
	{
		if (e != null && e.count(itemId) > 0)
		{
			out.add(new Found(place, e.count(itemId), e.getObservedAt()));
		}
	}

	/** Why an item was not found, most specific gap first. Never "not owned". */
	public static String notFound(AccountCapability cap, long now, String what)
	{
		if (cap.getInventory() == null || cap.getWorn() == null)
		{
			return what + ": inventory / equipment not read yet";
		}
		StorageEvidence bank = cap.getBank();
		if (bank == null)
		{
			return what + ": not carried; bank not seen yet for this account";
		}
		return what + ": not carried, and not in the bank when last seen (" + age(now - bank.getObservedAt()) + " ago)"
			+ (cap.getGroupStorage() == null ? "" : " or group storage") + "; may be held elsewhere";
	}

	public static String age(long millis)
	{
		long m = Math.max(0, millis) / 60_000L;
		if (m < 1)
		{
			return "<1m";
		}
		if (m < 120)
		{
			return m + "m";
		}
		long h = m / 60;
		return h < 48 ? h + "h" : h / 24 + "d";
	}

	/** "carried (worn)" / "last seen in the bank 3h ago". */
	public static String where(Found f, long now)
	{
		if (f.place.carried)
		{
			return "carried (" + f.place.label + ")";
		}
		return "last seen in " + (f.place == Place.GROUP_STORAGE ? "shared group storage" : "the " + f.place.label) + " " + age(now - f.observedAt) + " ago";
	}
}
