package com.osrstelemetry.plugin.skills.fishing;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.RateWindow;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.SkillStorage;
import com.osrstelemetry.plugin.skills.SkillTracker;
import com.osrstelemetry.plugin.skills.SupplyTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The Fishing companion's brain, built from the SAME chassis pieces as
 * Mining's (ProductionLedger, MethodTracker, SkillStorage, SkillBanking,
 * RateWindow, SupplyTracker) and the same per-tick causal model -- only the
 * evidence is Fishing's. Pure (no RuneLite Client); client thread only; fed
 * by FishingCompanionCollector and driven directly by tests.
 *
 * <h3>Production is causal</h3>
 * Every observation is booked into a per-game-tick bucket: Fishing XP, catch
 * lines ("You catch a lobster."), and the NET change of every inventory /
 * bank item. A bucket settles once the ticks either side are closed. An
 * inventory GAIN of a fish is production only when Fishing caused it --
 * Fishing XP or a catch line on that tick or an adjacent one. A withdrawal,
 * a pick-up or emptying the barrel into the inventory is never production,
 * and nothing is produced while the bank or a deposit box is open.
 * Quantities come only from the destination (the inventory delta, or ONE fish
 * per catch line that went into an open barrel); signals decide causality and
 * identity, never quantity.
 *
 * <h3>Fish barrel</h3>
 * A catch line whose fish did not reach the inventory (that tick or an
 * adjacent one) while an OPEN barrel that takes it is carried went straight
 * into the barrel: production of that exact fish, destination storage --
 * never banked until a deposit proves it. Barrel contents are never claimed
 * before they are known (emptied into the bank).
 *
 * <h3>Method</h3>
 * The TECHNIQUE whose catch it is (every catch belongs to exactly one
 * FishingMethod), confirmed by the shared MethodTracker; the spot + option
 * the player clicked is only a hint that lets a switch confirm on its first
 * catch. Fly fishing stays "Fly fishing" whether a trout or a salmon came.
 *
 * <h3>Supplies</h3>
 * The current method's consumable (bait, feathers, karambwanji, sandworms):
 * the live inventory count, and only decreases with Fishing evidence in the
 * same window count as USED -- never a deposit, a drop or a withdrawal. The
 * bank never counts. Runway only after enough was seen (SupplyTracker).
 */
public final class FishingCompanionTracker implements SkillTracker
{
	static final int WINDOW_TICKS = 1;
	public static final int INVENTORY_SLOTS = 28;
	/** The flying-fish warning shows this long after the line. */
	static final long FLYING_FISH_MILLIS = 10_000L;
	/** A "Fill" click on the barrel explains an inventory fish decrease for this many ticks. */
	static final int FILL_CLICK_TICKS = 3;

	private static final FishingCompanionDefinition DEF = FishingCompanionDefinition.INSTANCE;

	private static final class Bucket
	{
		long xp;
		final List<Integer> catches = new ArrayList<>();
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
		int slotDelta;
		long atMillis;
		/** Inventory fish gains a catch line may still claim (one catch never counts twice). */
		final Map<Integer, Long> unclaimed = new HashMap<>();
		boolean claimableReady;
		boolean settled;
	}

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

		@Override
		public int getItemId()
		{
			return itemId;
		}

		@Override
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
	private final SkillStorage barrel = FishingCompanionDefinition.createBarrel();
	private final SupplyTracker supply = new SupplyTracker();
	/** Room filled by catches (inventory slots or barrel places): 12 samples, at least 4 over 20 s; a 60 s pause starts over. */
	private final RateWindow fill = new RateWindow(12, 4, 20_000L, 60_000L);
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();

	private Map<Integer, Long> lastInventory;
	private int usedSlots = -1;
	private Map<Integer, Long> lastBank;
	private boolean bankOpen;
	private boolean depositBoxOpen;
	private int lastTick = Integer.MIN_VALUE;
	private int fillClickTick = Integer.MIN_VALUE;
	private int emptyClickTick = Integer.MIN_VALUE;
	private boolean barrelFull;
	private long flyingFishUntil = -1L;
	/** Monotonic time spent on a method (the same rule as MethodTracker: idle gaps and stalls never count). */
	private long activeMillis;
	private long lastStepAt = -1L;
	private long revision;
	/** Newest method-specific native evidence (a catch line, a spot click) not yet drained. */
	private SkillTracker.MethodEvidence methodEvidence;

	// ---- inputs ---------------------------------------------------------------------------

	@Override
	public void onSkillXp(int tick, long delta, long nowMillis)
	{
		if (delta > 0)
		{
			bucket(tick, nowMillis).xp += delta;
		}
	}

	@Override
	public void onChat(int tick, long nowMillis, String message)
	{
		if (message == null)
		{
			return;
		}
		Integer fish = FishingCompanionDefinition.caughtFish(message);
		if (fish != null)
		{
			bucket(tick, nowMillis).catches.add(fish);
			FishingMethod m = FishingMethod.catching(fish);
			if (m != null)
			{
				// The game named the fish: the technique is proven (a barbarian leaping fish is only ever barbarian fishing).
				methodEvidence = new SkillTracker.MethodEvidence(m.getKey(), nowMillis);
			}
			return;
		}
		if (FishingCompanionDefinition.isFlyingFish(message))
		{
			flyingFishUntil = nowMillis + FLYING_FISH_MILLIS;
			revision++;
			return;
		}
		if (FishingCompanionDefinition.isBarrelFull(message) && barrel.isHeld())
		{
			if (!barrelFull)
			{
				barrelFull = true;
				revision++;
			}
		}
	}

	/**
	 * The player clicked a fishing option on a spot (RuneLite FishingSpot
	 * constant name + option): a method HINT only, never adopted alone.
	 */
	public void onSpotClick(long nowMillis, String spotName, String option)
	{
		FishingMethod m = FishingMethod.forSpot(spotName, option);
		if (m != null)
		{
			methods.hint(m.getKey(), nowMillis);
			methodEvidence = new SkillTracker.MethodEvidence(m.getKey(), nowMillis);
		}
	}

	@Override
	public SkillTracker.MethodEvidence drainMethodEvidence()
	{
		SkillTracker.MethodEvidence e = methodEvidence;
		methodEvidence = null;
		return e;
	}

	/** "Fill" on the barrel: the next inventory fish decrease went into it. */
	public void onBarrelFill(int tick)
	{
		fillClickTick = tick;
	}

	/** "Empty" on the barrel (away from the bank): the fish it puts in the inventory are a move, never a catch. */
	public void onBarrelEmpty(int tick)
	{
		emptyClickTick = tick;
	}

	@Override
	public void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed)
	{
		barrel.onInventory(items);
		supply.onInventory(items);
		if (!barrel.isHeld() && barrelFull)
		{
			barrelFull = false;
		}
		if (lastInventory == null)
		{
			lastInventory = new HashMap<>(items);
			usedSlots = slotsUsed;
			revision++;
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		SkillBanking.diffInto(lastInventory, items, b.invNet);
		b.slotDelta += slotsUsed - usedSlots;
		b.bankOpen |= bankOpen;
		b.depositBoxOpen |= depositBoxOpen;
		lastInventory = new HashMap<>(items);
		// Supplies count is visible (and a withdrawal shows at once).
		revision++;
		usedSlots = slotsUsed;
	}

	@Override
	public void onBank(int tick, long nowMillis, Map<Integer, Long> items)
	{
		if (lastBank == null)
		{
			lastBank = new HashMap<>(items);
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		SkillBanking.diffInto(lastBank, items, b.bankNet);
		b.bankOpen = true;
		lastBank = new HashMap<>(items);
	}

	@Override
	public void setBankOpen(boolean open)
	{
		bankOpen = open;
	}

	@Override
	public void setDepositBoxOpen(boolean open)
	{
		depositBoxOpen = open;
	}

	@Override
	public void onTick(int tick, long nowMillis, int regionId)
	{
		lastTick = tick;
		methods.step(nowMillis);
		stepActive(nowMillis);
		settleUpTo(tick - WINDOW_TICKS - 1);
		scopeSupply();
	}

	private void stepActive(long nowMillis)
	{
		long previous = lastStepAt;
		lastStepAt = nowMillis;
		long lastSuccess = methods.getLastSuccessAt();
		if (previous < 0 || methods.getCurrent() == null || lastSuccess < 0 || nowMillis - lastSuccess > MethodTracker.IDLE_CUTOFF_MILLIS)
		{
			return;
		}
		activeMillis += Math.min(5_000L, Math.max(0L, nowMillis - previous));
	}

	private void scopeSupply()
	{
		FishingMethod m = FishingMethod.fromKey(methods.getCurrent());
		supply.scope(m == null ? null : m.getKey(), m == null ? null : m.getSupplies(), activeMillis);
		if (lastInventory != null)
		{
			supply.onInventory(lastInventory);
		}
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

	void settleUpTo(int lastClosed)
	{
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
						if (d.getValue() > 0 && FishingCompanionDefinition.isFish(d.getKey()))
						{
							// A stackable catch (karambwanji: several per line) is ONE catch event.
							SkillResource r = DEF.resource(d.getKey());
							b.unclaimed.put(d.getKey(), r.isStackable() ? 1L : d.getValue());
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
		boolean catchNear = false;
		boolean fishGainNear = false;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			xpNear += n.xp;
			catchNear |= !n.catches.isEmpty();
			if (!n.bankOpen && !n.depositBoxOpen)
			{
				for (Map.Entry<Integer, Long> d : n.invNet.entrySet())
				{
					fishGainNear |= d.getValue() > 0 && FishingCompanionDefinition.isFish(d.getKey());
				}
			}
		}
		boolean causal = xpNear > 0 || catchNear;
		boolean changed = false;

		// 1) Banking: the shared rule. The barrel empties into the bank as a whole.
		if (b.bankOpen || b.depositBoxOpen)
		{
			if (SkillBanking.settleBankingTick(b.invNet, b.bankNet, b.depositBoxOpen, ledger, barrel,
				FishingCompanionDefinition::barrelAccepts, (item, qty) -> deposits.add(new Deposit(item, qty, b.atMillis))))
			{
				revision++;
			}
			if (barrelFull && barrel.getKnownContents() != null && barrel.getKnownContents() == 0)
			{
				barrelFull = false;
				revision++;
			}
			return;
		}

		Map<Integer, Long> supplyRemoved = new HashMap<>();
		// 2) Removals: a barrel fill, supplies the catch used, anything else forgets held production.
		boolean filling = barrel.isHeld() && recent(fillClickTick, tick);
		boolean emptying = barrel.isHeld() && recent(emptyClickTick, tick);
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long removed = -d.getValue();
			if (removed <= 0)
			{
				continue;
			}
			int item = d.getKey();
			if (filling && FishingCompanionDefinition.barrelAccepts(item))
			{
				ledger.inventoryToStorage(item, removed);
				barrel.filled((int) Math.min(Integer.MAX_VALUE, removed));
				changed = true;
				continue;
			}
			if (causal)
			{
				supplyRemoved.merge(item, removed, Long::sum);
			}
			ledger.inventoryRemoved(item, removed);
		}

		// 3) Gains.
		String productMethod = null;
		boolean producedToInventory = false;
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long gained = d.getValue();
			int item = d.getKey();
			if (gained <= 0 || !FishingCompanionDefinition.isFish(item))
			{
				continue;
			}
			if (causal && emptying && FishingCompanionDefinition.barrelAccepts(item))
			{
				// Emptying the barrel while still catching: only what a catch line names is new.
				long caught = Math.min(gained, catchLinesNear(tick, item));
				long moved = gained - caught;
				if (moved > 0)
				{
					ledger.storageToInventory(item, moved);
					barrel.emptiedToInventory((int) Math.min(Integer.MAX_VALUE, moved));
					barrelFull = false;
					changed = true;
				}
				gained = caught;
				if (gained <= 0)
				{
					continue;
				}
			}
			if (!causal)
			{
				if (barrel.isHeld() && FishingCompanionDefinition.barrelAccepts(item))
				{
					// Emptied out of the barrel into the inventory: a move, never production.
					ledger.storageToInventory(item, gained);
					barrel.emptiedToInventory((int) Math.min(Integer.MAX_VALUE, gained));
					if (barrelFull)
					{
						barrelFull = false;
					}
					changed = true;
				}
				continue;
			}
			ledger.produced(item, gained, ProductionLedger.Destination.INVENTORY);
			producedToInventory = true;
			changed = true;
			FishingMethod m = FishingMethod.catching(item);
			if (m != null && productMethod == null)
			{
				productMethod = m.getKey();
			}
		}

		// 4) Catch lines whose fish never reached the inventory went into an open barrel.
		int routed = 0;
		for (Integer fish : b.catches)
		{
			if (claimInventoryCatch(tick, fish))
			{
				continue;
			}
			if (barrel.isCatching(fish) && !barrelFull)
			{
				ledger.produced(fish, 1L, ProductionLedger.Destination.STORAGE);
				barrel.routed(1);
				routed++;
				changed = true;
				if (productMethod == null)
				{
					FishingMethod m = FishingMethod.catching(fish);
					productMethod = m == null ? null : m.getKey();
				}
			}
		}

		// 5) Method evidence, once per real catch.
		if (producedToInventory || routed > 0)
		{
			String before = methods.getCurrent();
			methods.success(productMethod, b.atMillis);
			changed |= !Objects.equals(before, methods.getCurrent());
		}
		else if (b.xp > 0 && !fishGainNear && !catchNear)
		{
			// Fishing XP with nothing Spyglass identifies (a deferred method): still fishing, no method invented.
			methods.success(null, b.atMillis);
		}

		// 6) Supplies that disappeared with Fishing evidence: used by the (now current) method.
		if (!supplyRemoved.isEmpty())
		{
			scopeSupply();
			for (Map.Entry<Integer, Long> e : supplyRemoved.entrySet())
			{
				if (supply.isSupply(e.getKey()))
				{
					supply.consumed(e.getValue());
					changed = true;
				}
			}
		}

		// 7) Trip fill rate: inventory slots this catch used + barrel places.
		long filled = (producedToInventory ? Math.max(0, b.slotDelta) : 0) + routed;
		if (filled > 0 && !emptying && !filling)
		{
			fill.record(b.atMillis, filled);
		}
		if (changed)
		{
			revision++;
		}
	}

	/** A click on {@code clickTick} explains changes up to {@link #FILL_CLICK_TICKS} later (never before it). */
	private static boolean recent(int clickTick, int tick)
	{
		return clickTick != Integer.MIN_VALUE && tick >= clickTick && (long) tick - clickTick <= FILL_CLICK_TICKS;
	}

	private long catchLinesNear(int tick, int fish)
	{
		long n = 0L;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket b = buckets.get(t);
			if (b != null)
			{
				for (Integer c : b.catches)
				{
					if (c == fish)
					{
						n++;
					}
				}
			}
		}
		return n;
	}

	private boolean claimInventoryCatch(int tick, int fish)
	{
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			long available = n.unclaimed.getOrDefault(fish, 0L);
			if (available > 0)
			{
				n.unclaimed.put(fish, available - 1);
				return true;
			}
		}
		return false;
	}

	// ---- outputs ---------------------------------------------------------------------------

	@Override
	public boolean hasInventoryBaseline()
	{
		return lastInventory != null;
	}

	@Override
	public ProductionLedger getLedger()
	{
		return ledger;
	}

	@Override
	public MethodTracker getMethods()
	{
		return methods;
	}

	public SkillStorage getBarrel()
	{
		return barrel;
	}

	public SupplyTracker getSupply()
	{
		return supply;
	}

	@Override
	public String getMethodKey()
	{
		return methods.getCurrent();
	}

	@Override
	public long getRevision()
	{
		return revision;
	}

	@Override
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

	/**
	 * CURRENT TRIP: free slots, and EST. FULL from the observed fill rate.
	 * With an open barrel catching, the trip ends when BOTH are full -- known
	 * only once the barrel's contents are known; until then no estimate is
	 * claimed. A full barrel no longer counts.
	 */
	@Override
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		if (lastInventory == null || usedSlots < 0)
		{
			return null;
		}
		int free = Math.max(0, INVENTORY_SLOTS - usedSlots);
		SkillStorage.Kind kind = barrel.getKind();
		boolean catching = barrel.isCatching() && !barrelFull;
		Long est;
		boolean includesBarrel = false;
		if (!catching)
		{
			est = fill.millisUntil(free, nowMillis);
		}
		else
		{
			Integer room = barrel.getKnownRoom();
			if (room == null)
			{
				est = null;
			}
			else
			{
				est = fill.millisUntil((long) free + room, nowMillis);
				includesBarrel = room > 0 && est != null;
			}
		}
		Integer used = barrelFull && kind != null && kind.getCapacity() != null ? kind.getCapacity() : barrel.getKnownContents();
		SkillCompanionView.TripView t = new SkillCompanionView.TripView(free, est, kind == null ? null : kind.getLabel(), used,
			kind == null ? null : kind.getCapacity(), barrel.getAddedThisTrip(), catching);
		return includesBarrel ? t.withEstIncludingStorage() : t;
	}

	@Override
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
		List<SkillCompanionView.ContextLine> out = new ArrayList<>();
		if (barrelFull && barrel.isHeld())
		{
			out.add(new SkillCompanionView.ContextLine(barrel.getKind().getLabel() + " full", SkillCompanionView.ContextLine.Tone.ALERT,
				"Your barrel is full: new catches go to your inventory until you empty it"));
		}
		if (flyingFishUntil > 0 && nowMillis < flyingFishUntil)
		{
			out.add(new SkillCompanionView.ContextLine("Flying fish eating your minnows", SkillCompanionView.ContextLine.Tone.ALERT,
				"A flying fish is at your spot: move to another one to stop losing minnows"));
		}
		return out;
	}

	/** SUPPLIES for the current method; null when it uses none or the inventory is not known yet. */
	@Override
	public SkillCompanionView.SupplyView supply(long nowMillis)
	{
		FishingMethod m = FishingMethod.fromKey(methods.getCurrent());
		if (m == null || m.getSupplies().isEmpty() || !supply.hasSupply() || supply.getCount() < 0 || lastInventory == null)
		{
			return null;
		}
		return new SkillCompanionView.SupplyView(supplyCaption(m), supply.getCount(), supply.runwayMillis(activeMillis));
	}

	/** The supply the player actually carries most of (barbarian: feathers, bait or offcuts); the method's first one otherwise. */
	private String supplyCaption(FishingMethod m)
	{
		Integer best = null;
		long bestCount = 0L;
		for (Integer id : m.getSupplies())
		{
			long c = lastInventory.getOrDefault(id, 0L);
			if (best == null || c > bestCount)
			{
				best = id;
				bestCount = c;
			}
		}
		String name = best == null ? null : FishingCompanionDefinition.supplyName(best);
		return name == null ? "Supplies" : name;
	}

	@Override
	public void reset()
	{
		methods.reset();
		ledger.reset();
		barrel.reset();
		supply.reset();
		fill.clear();
		buckets.clear();
		deposits.clear();
		lastInventory = null;
		usedSlots = -1;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		lastTick = Integer.MIN_VALUE;
		fillClickTick = Integer.MIN_VALUE;
		emptyClickTick = Integer.MIN_VALUE;
		barrelFull = false;
		flyingFishUntil = -1L;
		activeMillis = 0L;
		lastStepAt = -1L;
		methodEvidence = null;
		revision++;
	}

	@Override
	public void onLogout()
	{
		buckets.clear();
		lastInventory = null;
		usedSlots = -1;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		barrel.reset();
		fill.clear();
		fillClickTick = Integer.MIN_VALUE;
		emptyClickTick = Integer.MIN_VALUE;
		barrelFull = false;
		flyingFishUntil = -1L;
		lastStepAt = -1L;
		methodEvidence = null;
		revision++;
	}

	@Override
	public void settleAll()
	{
		settleUpTo(Integer.MAX_VALUE - 1);
	}

	int lastTick()
	{
		return lastTick;
	}

	Map<Integer, Long> pendingProduced()
	{
		return new LinkedHashMap<>(ledger.getPendingProduced());
	}
}
