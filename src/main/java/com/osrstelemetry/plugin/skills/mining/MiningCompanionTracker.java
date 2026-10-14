package com.osrstelemetry.plugin.skills.mining;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.RateWindow;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.SkillStorage;
import com.osrstelemetry.plugin.skills.SkillTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Mining companion's brain: turns native Mining evidence into
 * CAUSAL production, BANKED production, the current METHOD and the
 * CURRENT TRIP. Pure (no RuneLite Client); client thread only; fed by
 * MiningCompanionCollector and driven directly by tests.
 *
 * <h3>Production is causal, never "the inventory went up"</h3>
 * Everything observed is booked into a per-game-tick bucket (the same
 * bounded-correlation idea as the GotR ProductionTracker): Mining XP gained,
 * the success line ("You manage to mine some iron."), gem lines ("You just
 * found a Ruby!"), "...straight into your open gem bag", and the NET change
 * of every inventory / bank item. A bucket is settled once the ticks either
 * side of it are closed, so the result never depends on the order the
 * client delivered XP, chat and container updates within a tick.
 * <p>
 * A bucket's inventory GAIN of a Mining resource is production only when
 * Mining caused it: Mining XP, a success line or a gem line on that tick or
 * an adjacent one. A gain with no Mining cause -- a withdrawal, a pick-up,
 * emptying a gem bag -- is never production. Nothing is produced while the
 * bank or a deposit box is open.
 * <p>
 * One success seen through several signals is counted ONCE: quantities come
 * only from the destination that received the item (the inventory delta, or
 * one storage-routed gem per gem line). Signals only decide causality and
 * identity, they never add quantities of their own.
 *
 * <h3>Gem storage</h3>
 * A gem line whose gem did not reach the inventory (that tick or an adjacent
 * one) while an OPEN gem bag / sack is carried went straight into it:
 * production of that exact gem, destination storage. A gem-rock success
 * (Mining XP) that put an unnamed gem into the bag is counted as one
 * {@link MiningCompanionDefinition#UNIDENTIFIED_GEM} -- counted, never
 * guessed. Emptying the bag into the inventory is never production;
 * emptying it into the bank banks what this session put there.
 *
 * <h3>Banked</h3>
 * With the bank open: an inventory decrease matched by a bank increase of
 * the same item on the same tick is a deposit; with a deposit box open every
 * inventory decrease is (the box has no other destination). Only this
 * session's still-held production can be banked (ProductionLedger). Any other
 * decrease -- a drop -- just forgets it. Withdrawals are ignored.
 */
public final class MiningCompanionTracker implements SkillTracker
{
	/** Evidence may sit on the same tick as the item change, or one tick either side. */
	static final int WINDOW_TICKS = 1;
	public static final int INVENTORY_SLOTS = 28;
	/** Water from the cracks lasts roughly 20-30 s (OSRS Wiki); shown for at most this long. */
	static final long WATER_MILLIS = 30_000L;

	private static final MiningCompanionDefinition DEF = MiningCompanionDefinition.INSTANCE;

	/** One tick's evidence. */
	private static final class Bucket
	{
		long xp;
		MiningMethod successMethod;
		final List<Integer> gemsFound = new ArrayList<>();
		int routedLines;
		boolean fillLine;
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
		int slotDelta;
		long atMillis;
		/** Inventory gem gains a gem line may still claim (so one gem is never counted twice). */
		final Map<Integer, Long> unclaimedGems = new HashMap<>();
		boolean claimableReady;
		boolean settled;
		/** A "You just mined a ...!" line (gem rocks) rather than the gem table's "You just found". */
		boolean gemMinedLine;
	}

	/** A proven deposit of this session's production (for the banked rate). */
	public static final class Deposit implements SkillTracker.Deposit
	{
		private final int itemId;
		private final long quantity;
		private final long atMillis;

		Deposit(int itemId, long quantity, long atMillis)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.atMillis = atMillis;
		}

		public int getItemId()
		{
			return itemId;
		}

		public long getQuantity()
		{
			return quantity;
		}

		public long getAtMillis()
		{
			return atMillis;
		}
	}

	private final MethodTracker methods = new MethodTracker();
	private final ProductionLedger ledger = new ProductionLedger();
	private final SkillStorage storage = GemStorage.create();
	/** Inventory slots filled by production: 12 samples, at least 4 over 20 s; a 60 s pause starts over. */
	private final RateWindow slotFill = new RateWindow(12, 4, 20_000L, 60_000L);
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();

	private Map<Integer, Long> lastInventory;
	private int usedSlots = -1;
	private Map<Integer, Long> lastBank;
	private boolean bankOpen;
	private boolean depositBoxOpen;
	private int lastTick = Integer.MIN_VALUE;
	private int region = -1;
	private long waterUntil = -1L;
	private Integer slippingRegion;
	/** Bumped whenever something the player can see changed (production, method, trip). */
	private long revision;

	// ---- inputs ---------------------------------------------------------------------------

	@Override
	public void onSkillXp(int tick, long delta, long nowMillis)
	{
		onMiningXp(tick, delta, nowMillis);
	}

	public void onMiningXp(int tick, long delta, long nowMillis)
	{
		if (delta > 0)
		{
			bucket(tick, nowMillis).xp += delta;
		}
	}

	public void onChat(int tick, long nowMillis, String message)
	{
		if (message == null)
		{
			return;
		}
		Integer gem = MiningCompanionDefinition.gemFound(message);
		if (gem != null)
		{
			Bucket b = bucket(tick, nowMillis);
			b.gemsFound.add(gem);
			b.gemMinedLine |= MiningCompanionDefinition.isGemMinedLine(message);
			return;
		}
		MiningMethod success = MiningCompanionDefinition.successMethod(message);
		if (success != null)
		{
			bucket(tick, nowMillis).successMethod = success;
			return;
		}
		if (MiningCompanionDefinition.isStorageRouted(message))
		{
			bucket(tick, nowMillis).routedLines++;
			return;
		}
		if (MiningCompanionDefinition.isStorageFilled(message))
		{
			bucket(tick, nowMillis).fillLine = true;
			return;
		}
		if (MiningCompanionDefinition.isWaterStart(message))
		{
			waterUntil = nowMillis + WATER_MILLIS;
			slippingRegion = null;
			revision++;
			return;
		}
		if (MiningCompanionDefinition.isSlipping(message))
		{
			// Only after a world hop: whatever water flowed on the old world is gone here.
			slippingRegion = region;
			waterUntil = -1L;
			revision++;
		}
	}

	/** The player clicked "Mine" on an object: names the method (a hint, never adopted alone). */
	public void onMineClick(long nowMillis, String target)
	{
		MiningMethod m = MiningMethod.forRockName(target);
		if (m != null)
		{
			methods.hint(m.getKey(), nowMillis);
		}
	}

	/** The inventory's full contents (item id -> quantity) and how many slots hold something. */
	public void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed)
	{
		storage.onInventory(items);
		if (lastInventory == null)
		{
			// Login / first sight: baseline only, never a change.
			lastInventory = new HashMap<>(items);
			usedSlots = slotsUsed;
			revision++;
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		diffInto(lastInventory, items, b.invNet);
		b.slotDelta += slotsUsed - usedSlots;
		b.bankOpen |= bankOpen;
		b.depositBoxOpen |= depositBoxOpen;
		lastInventory = new HashMap<>(items);
		if (slotsUsed != usedSlots)
		{
			revision++;
		}
		usedSlots = slotsUsed;
	}

	/** The bank's full contents (only while the bank is open). */
	public void onBank(int tick, long nowMillis, Map<Integer, Long> items)
	{
		if (lastBank == null)
		{
			lastBank = new HashMap<>(items);
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		diffInto(lastBank, items, b.bankNet);
		b.bankOpen = true;
		lastBank = new HashMap<>(items);
	}

	public void setBankOpen(boolean open)
	{
		bankOpen = open;
	}

	public void setDepositBoxOpen(boolean open)
	{
		depositBoxOpen = open;
	}

	/** Every game tick: method time, region, and settling every bucket whose neighbours are closed. */
	public void onTick(int tick, long nowMillis, int regionId)
	{
		if (regionId != region)
		{
			region = regionId;
			if (slippingRegion != null && slippingRegion != regionId)
			{
				// Leaving the mine clears the wet-pickaxe debuff (OSRS Wiki).
				slippingRegion = null;
				revision++;
			}
		}
		lastTick = tick;
		methods.step(nowMillis);
		settleUpTo(tick - WINDOW_TICKS - 1);
	}

	// ---- settlement -----------------------------------------------------------------------

	private Bucket bucket(int tick, long nowMillis)
	{
		Bucket b = buckets.get(tick);
		if (b == null)
		{
			b = new Bucket();
			buckets.put(tick, b);
		}
		b.atMillis = Math.max(b.atMillis, nowMillis);
		return b;
	}

	private static void diffInto(Map<Integer, Long> before, Map<Integer, Long> after, Map<Integer, Long> net)
	{
		SkillBanking.diffInto(before, after, net);
	}

	/** Settles every bucket at or before {@code lastClosed} (tests call it with a large tick to flush). */
	void settleUpTo(int lastClosed)
	{
		// Buckets up to one window past the last closed one are complete: their inventory gem
		// gains become claimable by gem lines (so one gem is never counted twice).
		for (Map.Entry<Integer, Bucket> e : buckets.entrySet())
		{
			if (e.getKey() > lastClosed + WINDOW_TICKS)
			{
				break;
			}
			Bucket b = e.getValue();
			if (!b.claimableReady)
			{
				b.claimableReady = true;
				if (!b.bankOpen && !b.depositBoxOpen)
				{
					for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
					{
						if (d.getValue() > 0 && MiningCompanionDefinition.isGem(d.getKey()))
						{
							b.unclaimedGems.put(d.getKey(), d.getValue());
						}
					}
				}
			}
		}
		List<Integer> done = new ArrayList<>();
		for (Map.Entry<Integer, Bucket> e : buckets.entrySet())
		{
			if (e.getKey() > lastClosed)
			{
				break;
			}
			if (!e.getValue().settled)
			{
				e.getValue().settled = true;
				settle(e.getKey(), e.getValue());
			}
			done.add(e.getKey());
		}
		// Settled buckets stay one more window as neighbours of the next ones, then go (bounded memory).
		for (Integer t : done)
		{
			if (t < lastClosed - 2 * WINDOW_TICKS)
			{
				buckets.remove(t);
			}
		}
	}

	private void settle(int tick, Bucket b)
	{
		long xpNear = 0L;
		MiningMethod successNear = null;
		boolean gemLineNear = false;
		boolean oreNear = false;
		boolean inventoryGemNear = false;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			xpNear += n.xp;
			gemLineNear |= !n.gemsFound.isEmpty();
			if (successNear == null)
			{
				successNear = n.successMethod;
			}
			if (!n.bankOpen && !n.depositBoxOpen)
			{
				for (Map.Entry<Integer, Long> d : n.invNet.entrySet())
				{
					if (d.getValue() > 0 && DEF.resource(d.getKey()) != null)
					{
						if (MiningCompanionDefinition.isGem(d.getKey()))
						{
							inventoryGemNear = true;
						}
						else
						{
							oreNear = true;
						}
					}
				}
			}
		}
		boolean causal = xpNear > 0 || successNear != null || gemLineNear || b.routedLines > 0;
		boolean banking = b.bankOpen || b.depositBoxOpen;
		boolean changed = false;

		// 1) Removals (and deposits) -- the shared banking rule (SkillBanking).
		if (banking)
		{
			// Nothing is mined with the bank open; gains here are withdrawals.
			if (SkillBanking.settleBankingTick(b.invNet, b.bankNet, b.depositBoxOpen, ledger, storage, MiningCompanionDefinition::isGem,
				(item, qty) -> deposits.add(new Deposit(item, qty, b.atMillis))))
			{
				revision++;
			}
			return;
		}
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long removed = -d.getValue();
			if (removed <= 0)
			{
				continue;
			}
			int item = d.getKey();
			if (MiningCompanionDefinition.isGem(item) && b.fillLine && storage.isHeld())
			{
				ledger.inventoryToStorage(item, removed);
				storage.filled((int) Math.min(Integer.MAX_VALUE, removed));
			}
			else
			{
				ledger.inventoryRemoved(item, removed);
			}
		}

		// 2) Gains.
		String productMethod = null;
		boolean producedToInventory = false;
		boolean sawOre = false;
		boolean sawGem = false;
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long gained = d.getValue();
			int item = d.getKey();
			if (gained <= 0 || DEF.resource(item) == null)
			{
				continue;
			}
			boolean gem = MiningCompanionDefinition.isGem(item);
			if (!causal)
			{
				if (gem && storage.isHeld())
				{
					// Emptied out of the gem bag into the inventory: a move, never production.
					ledger.storageToInventory(item, gained);
					storage.emptiedToInventory((int) Math.min(Integer.MAX_VALUE, gained));
					changed = true;
				}
				continue;
			}
			ledger.produced(item, gained, ProductionLedger.Destination.INVENTORY);
			producedToInventory = true;
			changed = true;
			if (gem)
			{
				sawGem = true;
			}
			else
			{
				sawOre = true;
				MiningMethod m = MiningMethod.producing(item);
				if (m != null && productMethod == null)
				{
					productMethod = m.getKey();
				}
			}
		}

		// 3) Gem lines whose gem never reached the inventory (this tick or an adjacent one) went
		// straight into an open gem bag / sack.
		int routed = 0;
		for (Integer gem : b.gemsFound)
		{
			if (claimInventoryGem(tick, gem))
			{
				continue;
			}
			if (storage.isCatching())
			{
				ledger.produced(gem, 1L, ProductionLedger.Destination.STORAGE);
				storage.routed(1);
				routed++;
				changed = true;
			}
		}
		if (storage.isCatching() && b.xp > 0 && !oreNear && !inventoryGemNear && !gemLineNear
			&& (b.routedLines > 0 || MiningMethod.GEM_ROCKS.getKey().equals(methods.getCurrent())))
		{
			// A gem-rock success (its Mining XP, no product anywhere near) with the gem bag open:
			// one gem went in and no line named it. Counted, never guessed.
			ledger.produced(MiningCompanionDefinition.UNIDENTIFIED_GEM, 1L, ProductionLedger.Destination.STORAGE);
			storage.routed(1);
			routed++;
			changed = true;
		}

		// 4) Method evidence, ONCE per real success (the bucket that received the product):
		// the success line first, then the product. A gem with Mining XP and no ore anywhere
		// near is a gem-rock success; gem-table gems come with NO XP of their own (the gem roll
		// replaces the ore roll), so near ore mining they always sit next to that ore.
		boolean produced = producedToInventory || routed > 0;
		if (produced)
		{
			String method = successNear != null ? successNear.getKey() : productMethod;
			if (method == null && !oreNear && (sawGem || routed > 0) && xpNear > 0)
			{
				method = MiningMethod.GEM_ROCKS.getKey();
			}
			String before = methods.getCurrent();
			methods.success(method, b.atMillis);
			changed |= !java.util.Objects.equals(before, methods.getCurrent());
		}
		else if (b.xp > 0 && !oreNear && !inventoryGemNear)
		{
			// Mining XP with nothing Spyglass can identify (a method it does not support yet):
			// still mining, so the session's method time keeps running -- no method is invented.
			methods.success(null, b.atMillis);
		}

		// 5) Trip fill rate: the slots this success used (a stackable after its first slot uses none).
		if (producedToInventory && b.slotDelta > 0)
		{
			slotFill.record(b.atMillis, b.slotDelta);
		}
		if (changed)
		{
			revision++;
		}
	}

	/** A gem line claims a matching inventory gem gain on its own tick or an adjacent one. */
	private boolean claimInventoryGem(int tick, int gem)
	{
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			long available = n.unclaimedGems.getOrDefault(gem, 0L);
			if (available > 0)
			{
				n.unclaimedGems.put(gem, available - 1);
				return true;
			}
		}
		return false;
	}

	// ---- outputs ---------------------------------------------------------------------------

	/** Whether the inventory has been observed since login / reset. */
	public boolean hasInventoryBaseline()
	{
		return lastInventory != null;
	}

	public ProductionLedger getLedger()
	{
		return ledger;
	}

	public MethodTracker getMethods()
	{
		return methods;
	}

	public SkillStorage getStorage()
	{
		return storage;
	}

	public String getMethodKey()
	{
		return methods.getCurrent();
	}

	public long getRevision()
	{
		return revision;
	}

	/** Proven deposits since the last call (cleared). */
	public List<Deposit> drainDeposits()
	{
		if (deposits.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Deposit> out = new ArrayList<>(deposits);
		deposits.clear();
		return out;
	}

	/** CURRENT TRIP as of now; null while the inventory has not been observed. */
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		if (lastInventory == null || usedSlots < 0)
		{
			return null;
		}
		int free = Math.max(0, INVENTORY_SLOTS - usedSlots);
		Long est = slotFill.millisUntil(free, nowMillis);
		SkillStorage.Kind kind = storage.getKind();
		return new SkillCompanionView.TripView(free, est, kind == null ? null : kind.getLabel(), storage.getKnownContents(),
			kind == null ? null : kind.getCapacity(), storage.getAddedThisTrip(), storage.isCatching());
	}

	/** One or two method context lines, only while they are true right now. */
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
		List<SkillCompanionView.ContextLine> out = new ArrayList<>();
		if (waterUntil > 0 && nowMillis < waterUntil)
		{
			out.add(new SkillCompanionView.ContextLine("Water flowing: +15% success", SkillCompanionView.ContextLine.Tone.ALERT,
				"Some cracks around the cave are oozing water: rocks there succeed 15% more often, for about 20-30 seconds"));
		}
		if (slippingRegion != null && slippingRegion == region)
		{
			out.add(new SkillCompanionView.ContextLine("Pickaxe slipping (world hop)", SkillCompanionView.ContextLine.Tone.ALERT,
				"Hopping worlds in this mine left your pickaxe wet. Leave the mine and come back, or wait for the next water cycle"));
		}
		return out;
	}

	/** Login / account switch / plugin re-enable: every baseline is unknown again. */
	public void reset()
	{
		methods.reset();
		ledger.reset();
		storage.reset();
		slotFill.clear();
		buckets.clear();
		deposits.clear();
		lastInventory = null;
		usedSlots = -1;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		lastTick = Integer.MIN_VALUE;
		waterUntil = -1L;
		slippingRegion = null;
		revision++;
	}

	/** Logout (same account may come back): containers re-baseline, nothing in flight survives. */
	public void onLogout()
	{
		buckets.clear();
		lastInventory = null;
		usedSlots = -1;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		storage.reset();
		slotFill.clear();
		waterUntil = -1L;
		slippingRegion = null;
		revision++;
	}

	/** Test hook: settles everything observed so far. */
	@Override
	public void settleAll()
	{
		settleUpTo(Integer.MAX_VALUE - 1);
	}

	int lastTick()
	{
		return lastTick;
	}

	/** For tests: a stable copy of what this session produced (pending only). */
	Map<Integer, Long> pendingProduced()
	{
		return new LinkedHashMap<>(ledger.getPendingProduced());
	}
}
