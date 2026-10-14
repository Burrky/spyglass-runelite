package com.osrstelemetry.plugin.pestcontrol;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which Void pieces the player owns RIGHT NOW, from the latest inventory,
 * worn equipment and bank contents (bounded, in memory -- never a scan of
 * historical snapshots). Void equipment is untradeable and cannot go in
 * group storage, so those three containers are the whole picture. The
 * bank is only known once it has been seen (opened this login, or seeded
 * from Spyglass's own latest bank.json at login).
 *
 * Thread-safe: each update publishes a new immutable snapshot.
 */
public final class VoidOwnership
{
	public enum Container
	{
		INVENTORY,
		EQUIPMENT,
		BANK
	}

	/** An immutable view: owned pieces and whether the bank contributed. */
	public static final class Snapshot
	{
		private final Set<VoidPiece> owned;
		private final boolean bankKnown;

		Snapshot(Set<VoidPiece> owned, boolean bankKnown)
		{
			this.owned = owned;
			this.bankKnown = bankKnown;
		}

		public Set<VoidPiece> getOwned()
		{
			return owned;
		}

		public boolean isBankKnown()
		{
			return bankKnown;
		}

		/** Exactly this piece (any of its item variants) is owned. */
		public boolean owns(VoidPiece piece)
		{
			return owned.contains(piece);
		}

		/** Some owned piece fulfils this requirement (itself, or a piece that supersedes it -- see VoidPiece#satisfies). */
		public boolean satisfies(VoidPiece requirement)
		{
			for (VoidPiece p : owned)
			{
				if (p.satisfies(requirement))
				{
					return true;
				}
			}
			return false;
		}
	}

	private Set<VoidPiece> inventory = EnumSet.noneOf(VoidPiece.class);
	private Set<VoidPiece> equipment = EnumSet.noneOf(VoidPiece.class);
	private Set<VoidPiece> bank;
	private volatile Snapshot snapshot = new Snapshot(Collections.emptySet(), false);

	/** One container's current contents (item id -> quantity); null clears it. */
	public synchronized void update(Container container, Map<Integer, Long> items)
	{
		Set<VoidPiece> pieces = piecesIn(items);
		switch (container)
		{
			case INVENTORY:
				inventory = pieces;
				break;
			case EQUIPMENT:
				equipment = pieces;
				break;
			case BANK:
				bank = items == null ? null : pieces;
				break;
			default:
				break;
		}
		publish();
	}

	/** Login / account switch: everything unknown again (the bank is re-seeded by the caller if known). */
	public synchronized void reset()
	{
		inventory = EnumSet.noneOf(VoidPiece.class);
		equipment = EnumSet.noneOf(VoidPiece.class);
		bank = null;
		publish();
	}

	public Snapshot getSnapshot()
	{
		return snapshot;
	}

	private void publish()
	{
		Set<VoidPiece> all = EnumSet.noneOf(VoidPiece.class);
		all.addAll(inventory);
		all.addAll(equipment);
		if (bank != null)
		{
			all.addAll(bank);
		}
		snapshot = new Snapshot(Collections.unmodifiableSet(all), bank != null);
	}

	/**
	 * The bank as Spyglass itself last saved it (this account's bank.json),
	 * as item id -> quantity; null when there is no readable copy. One small
	 * read, at login only (planning seed until the bank is opened).
	 */
	public static Map<Integer, Long> savedBankItems(com.osrstelemetry.plugin.storage.LocalStateStore store, long accountHash)
	{
		try
		{
			com.osrstelemetry.plugin.model.StorageState bank = store.readIfExists(
				com.osrstelemetry.plugin.storage.TelemetryPaths.stateFile(accountHash, "bank"),
				com.osrstelemetry.plugin.model.StorageState.class);
			if (bank == null || bank.getItems() == null)
			{
				return null;
			}
			Map<Integer, Long> items = new java.util.HashMap<>();
			for (com.osrstelemetry.plugin.model.StorageState.StorageItem item : bank.getItems())
			{
				if (item != null && item.getItemId() > 0 && item.getQuantity() > 0)
				{
					items.merge(item.getItemId(), (long) item.getQuantity(), Long::sum);
				}
			}
			return items;
		}
		catch (RuntimeException e)
		{
			// Planning only: an unreadable file just leaves the bank unknown.
			return null;
		}
	}

	static Set<VoidPiece> piecesIn(Map<Integer, Long> items)
	{
		Set<VoidPiece> pieces = EnumSet.noneOf(VoidPiece.class);
		if (items != null)
		{
			for (Map.Entry<Integer, Long> e : items.entrySet())
			{
				VoidPiece p = e.getKey() == null ? null : VoidPiece.ofItem(e.getKey());
				if (p != null && e.getValue() != null && e.getValue() > 0)
				{
					pieces.add(p);
				}
			}
		}
		return pieces;
	}
}
