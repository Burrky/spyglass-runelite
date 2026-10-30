package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The live production ledger of one skill companion: WHAT was produced
 * (TOTAL) kept separate from WHERE it went (inventory, specialized storage,
 * the bank, dropped). Pure; no RuneLite types; client thread only.
 *
 * <h3>TOTAL</h3>
 * {@link #produced} is called once per causally confirmed success (the
 * adapter decides causality) and is the only thing that ever adds to TOTAL.
 * Nothing downstream -- dropping, banking, moving into or out of storage,
 * using the item -- ever subtracts from it.
 *
 * <h3>BANKED</h3>
 * The ledger remembers how much of THIS session's production it believes is
 * still in the inventory and in specialized storage ("held" pools). A
 * positively observed deposit can bank at most what is held there: items
 * the player brought along, withdrew, or produced in an earlier session are
 * never claimed. Anything that leaves for an unknown place (drop, use, a
 * deposit Spyglass could not prove) is forgotten from the pool first --
 * so BANKED can only ever be under-, never over-counted.
 *
 * <h3>Auto-transformed</h3>
 * {@link #autoTransformed} is production a tool transformed the moment it
 * was produced (a tool that consumes the product the instant it is made).
 * It counts in TOTAL like any production and is ALSO remembered as
 * auto-transformed, but it is never held anywhere, so it can never bank.
 *
 * <h3>Pending</h3>
 * Produced / banked quantities accumulate as PENDING until the owner drains
 * them into one bounded telemetry update (never one event per item).
 */
public final class ProductionLedger
{
	public enum Destination
	{
		INVENTORY,
		STORAGE
	}

	/** One drained update: produced and banked since the previous drain (item id -> quantity). */
	public static final class Drain
	{
		private final Map<Integer, Long> produced;
		private final Map<Integer, Long> banked;
		private final Map<Integer, Long> autoTransformed;
		private Map<Integer, Long> consumed = Collections.emptyMap();

		/** Session-owned INPUTS a transformation used up since the previous drain (never production). */
		public Map<Integer, Long> getConsumed()
		{
			return consumed;
		}

		Drain(Map<Integer, Long> produced, Map<Integer, Long> banked, Map<Integer, Long> autoTransformed)
		{
			this.produced = Collections.unmodifiableMap(produced);
			this.banked = Collections.unmodifiableMap(banked);
			this.autoTransformed = Collections.unmodifiableMap(autoTransformed);
		}

		/** The part of {@link #getProduced()} a tool transformed at once (never held, never banked). */
		public Map<Integer, Long> getAutoTransformed()
		{
			return autoTransformed;
		}

		public Map<Integer, Long> getProduced()
		{
			return produced;
		}

		public Map<Integer, Long> getBanked()
		{
			return banked;
		}

		public boolean isEmpty()
		{
			return produced.isEmpty() && banked.isEmpty();
		}
	}

	private final Map<Integer, Long> heldInventory = new HashMap<>();
	private final Map<Integer, Long> heldStorage = new HashMap<>();
	private final Map<Integer, Long> pendingProduced = new LinkedHashMap<>();
	private final Map<Integer, Long> pendingBanked = new LinkedHashMap<>();
	private final Map<Integer, Long> pendingAutoTransformed = new LinkedHashMap<>();
	private final Map<Integer, Long> pendingConsumed = new LinkedHashMap<>();
	/** Confirmed successes recorded so far (monotonic, never reset): a new one is ownership evidence. */
	private long successes;

	/** How many confirmed successes this ledger has recorded (monotonic; compare two reads to see a new one). */
	public long successCount()
	{
		return successes;
	}

	/** One confirmed success's output (quantity > 0), landing in {@code destination}. */
	public void produced(int itemId, long quantity, Destination destination)
	{
		if (quantity <= 0)
		{
			return;
		}
		pendingProduced.merge(itemId, quantity, Long::sum);
		(destination == Destination.STORAGE ? heldStorage : heldInventory).merge(itemId, quantity, Long::sum);
		successes++;
	}

	/**
	 * One confirmed success's output that a tool transformed the moment it
	 * was produced (consumed by the tool itself): TOTAL production that is
	 * also auto-transformed, held nowhere, so never banked.
	 */
	public void autoTransformed(int itemId, long quantity)
	{
		if (quantity <= 0)
		{
			return;
		}
		pendingProduced.merge(itemId, quantity, Long::sum);
		pendingAutoTransformed.merge(itemId, quantity, Long::sum);
		successes++;
	}

	/**
	 * ADDED (Skills Phase 5). An INPUT a confirmed transformation used up
	 * (e.g. essence turned into runes): recorded as consumed, never as
	 * production, and it never touches TOTAL, BANKED or the held pools.
	 */
	public void consumed(int itemId, long quantity)
	{
		if (quantity > 0)
		{
			pendingConsumed.merge(itemId, quantity, Long::sum);
		}
	}

	public Map<Integer, Long> getPendingConsumed()
	{
		return Collections.unmodifiableMap(pendingConsumed);
	}

	/** Left the inventory for somewhere that is NOT a proven bank deposit (dropped, used, unknown). */
	public void inventoryRemoved(int itemId, long quantity)
	{
		take(heldInventory, itemId, quantity);
	}

	/** Left specialized storage for somewhere unknown. */
	public void storageRemoved(int itemId, long quantity)
	{
		take(heldStorage, itemId, quantity);
	}

	/** Moved from the inventory into specialized storage (e.g. "Fill"): still held, not banked. */
	public void inventoryToStorage(int itemId, long quantity)
	{
		long moved = take(heldInventory, itemId, quantity);
		if (moved > 0)
		{
			heldStorage.merge(itemId, moved, Long::sum);
		}
	}

	/** Moved from specialized storage into the inventory ("Empty" with no bank open). */
	public void storageToInventory(int itemId, long quantity)
	{
		long moved = take(heldStorage, itemId, quantity);
		if (moved > 0)
		{
			heldInventory.merge(itemId, moved, Long::sum);
		}
	}

	/** A proven deposit from the inventory; returns how much of it was THIS session's production (now BANKED). */
	public long bankedFromInventory(int itemId, long quantity)
	{
		return bank(heldInventory, itemId, quantity);
	}

	/** A proven deposit straight from specialized storage into the bank. */
	public long bankedFromStorage(int itemId, long quantity)
	{
		return bank(heldStorage, itemId, quantity);
	}

	private long bank(Map<Integer, Long> pool, int itemId, long quantity)
	{
		long banked = take(pool, itemId, quantity);
		if (banked > 0)
		{
			pendingBanked.merge(itemId, banked, Long::sum);
		}
		return banked;
	}

	private static long take(Map<Integer, Long> pool, int itemId, long quantity)
	{
		if (quantity <= 0)
		{
			return 0L;
		}
		long held = pool.getOrDefault(itemId, 0L);
		long taken = Math.min(held, quantity);
		if (taken <= 0)
		{
			return 0L;
		}
		if (held - taken == 0)
		{
			pool.remove(itemId);
		}
		else
		{
			pool.put(itemId, held - taken);
		}
		return taken;
	}

	public long heldInInventory(int itemId)
	{
		return heldInventory.getOrDefault(itemId, 0L);
	}

	public long heldInStorage(int itemId)
	{
		return heldStorage.getOrDefault(itemId, 0L);
	}

	/** Total of this session's production believed to be in specialized storage right now. */
	public long heldInStorageTotal()
	{
		long total = 0L;
		for (long v : heldStorage.values())
		{
			total += v;
		}
		return total;
	}

	public boolean hasPending()
	{
		return !pendingProduced.isEmpty() || !pendingBanked.isEmpty() || !pendingConsumed.isEmpty();
	}

	/** Pending (not yet drained) production, read-only. */
	public Map<Integer, Long> getPendingProduced()
	{
		return Collections.unmodifiableMap(pendingProduced);
	}

	public Map<Integer, Long> getPendingBanked()
	{
		return Collections.unmodifiableMap(pendingBanked);
	}

	public Map<Integer, Long> getPendingAutoTransformed()
	{
		return Collections.unmodifiableMap(pendingAutoTransformed);
	}

	/** Takes everything pending (and clears it). */
	public Drain drain()
	{
		Drain d = new Drain(new LinkedHashMap<>(pendingProduced), new LinkedHashMap<>(pendingBanked),
			new LinkedHashMap<>(pendingAutoTransformed));
		d.consumed = Collections.unmodifiableMap(new LinkedHashMap<>(pendingConsumed));
		pendingProduced.clear();
		pendingBanked.clear();
		pendingAutoTransformed.clear();
		pendingConsumed.clear();
		return d;
	}

	/** Pending is dropped (production that never found a session of its own). Held pools are kept. */
	public void discardPending()
	{
		pendingProduced.clear();
		pendingBanked.clear();
		pendingAutoTransformed.clear();
		pendingConsumed.clear();
	}

	/** Login / account switch / new session: nothing held is known any more. */
	public void reset()
	{
		heldInventory.clear();
		heldStorage.clear();
		pendingProduced.clear();
		pendingBanked.clear();
		pendingAutoTransformed.clear();
		pendingConsumed.clear();
	}

	/** A new session took over: what is still held came from the previous one and must never bank here. */
	public void forgetHeld()
	{
		heldInventory.clear();
		heldStorage.clear();
	}

	/** What is held right now ([inventory, storage] copies): the owner remembers it when it sends to a session. */
	public HeldMark markHeld()
	{
		return new HeldMark(new HashMap<>(heldInventory), new HashMap<>(heldStorage));
	}

	/**
	 * A new session took over AFTER some production that already belongs to
	 * it: forget only what was held when the previous session was last sent
	 * to (never more than is held now), so the new session keeps its own.
	 */
	public void forgetHeld(HeldMark previousSession)
	{
		if (previousSession == null)
		{
			forgetHeld();
			return;
		}
		previousSession.inventory.forEach((item, qty) -> take(heldInventory, item, qty));
		previousSession.storage.forEach((item, qty) -> take(heldStorage, item, qty));
	}

	/** Held pools at one moment (see {@link #markHeld()}). */
	public static final class HeldMark
	{
		private final Map<Integer, Long> inventory;
		private final Map<Integer, Long> storage;

		HeldMark(Map<Integer, Long> inventory, Map<Integer, Long> storage)
		{
			this.inventory = inventory;
			this.storage = storage;
		}
	}
}
