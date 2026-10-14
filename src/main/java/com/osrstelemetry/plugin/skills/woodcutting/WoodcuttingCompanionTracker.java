package com.osrstelemetry.plugin.skills.woodcutting;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.RateWindow;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillStorage;
import com.osrstelemetry.plugin.skills.SkillTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The Woodcutting companion's brain, built from the SAME chassis pieces as
 * Mining's and Fishing's (ProductionLedger, MethodTracker, SkillStorage,
 * SkillBanking, RateWindow) and the same per-tick causal model -- only the
 * evidence is Woodcutting's. Pure (no RuneLite Client); client thread only;
 * fed by WoodcuttingCompanionCollector and driven directly by tests.
 *
 * <h3>Production is causal</h3>
 * Every observation is booked into a per-game-tick bucket: Woodcutting XP,
 * Firemaking XP, cut lines ("You get some yew logs."), Forestry bark awards
 * and the NET change of every inventory / bank item. A bucket settles once
 * the ticks either side are closed. A log reaching the inventory is
 * production only when Woodcutting caused it (Woodcutting XP or a cut line on
 * that tick or an adjacent one); a withdrawal, a pick-up or emptying the log
 * basket is never production, and nothing is produced while the bank or a
 * deposit box is open. Quantities come from the destination; signals decide
 * causality and identity only.
 *
 * <h3>Infernal axe (auto-burned)</h3>
 * A log an infernal axe burned as it was cut is PRODUCED and AUTO-BURNED --
 * never BANKED. It is booked only from its own causal evidence: Woodcutting
 * XP AND Firemaking XP on the SAME tick, no log reaching the inventory on
 * that tick, while a charged infernal axe (inventory or worn) is carried.
 * One burn per such tick; its identity is the log the cut line named, else
 * the tree being cut, else the current tree kind. A log merely not appearing
 * never implies a burn. While the axe is carried and the tree is one it can
 * burn, the companion tells the session engine that this Firemaking XP is
 * Woodcutting's (context evidence), so it never steals the session; manual
 * Firemaking afterwards owns its XP normally.
 *
 * <h3>Log basket</h3>
 * A cut line whose log did not reach the inventory (that tick or an adjacent
 * one), with no burn on that tick, while an OPEN basket that takes it is
 * carried, went into the basket: production of that log, destination
 * storage -- never banked until a deposit proves it.
 *
 * <h3>Secondary resources (never part of the log count)</h3>
 * <ul>
 * <li>Bird nests: "A bird's nest falls out of the tree." puts a nest on the
 * ground; picking a nest up within {@link #NEST_PICKUP_MILLIS} counts that
 * exact nest, one per fallen nest (a Friendly Ent's end reward counts the
 * same way). A nest picked up without either never counts.</li>
 * <li>Anima-infused bark: an inventory gain of bark with the game's award
 * line on that tick or an adjacent one.</li>
 * <li>Leaves: a gain while a Struggling Sapling / Friendly Ent event runs or
 * just ended (their rewards).</li>
 * <li>Sulliuscep fossils: a gain caused by Woodcutting XP.</li>
 * </ul>
 *
 * <h3>Current tree</h3>
 * The tree the player clicked "Chop down" on (object id + tile): the logs it
 * gave and the time since its first one, until it falls (its object
 * despawns) or another tree is clicked. Never a remaining / depletion
 * estimate.
 */
public final class WoodcuttingCompanionTracker implements SkillTracker
{
	static final int WINDOW_TICKS = 1;
	public static final int INVENTORY_SLOTS = 28;
	/** A "Fill" / "Empty" click on the basket explains an inventory log change for this many ticks. */
	static final int CLICK_TICKS = 3;
	/** A click on the Woodcutting Leprechaun explains log deposits for this many ticks (his banking dialog). */
	static final int LEPRECHAUN_TICKS = 20;
	/** A fallen nest picked up within this long counts. */
	static final long NEST_PICKUP_MILLIS = 120_000L;
	/** The CURRENT TREE section stays this long after its last log. */
	static final long FOCUS_IDLE_MILLIS = 60_000L;
	/** "Tree felled" shows this long. */
	static final long FELLED_LINE_MILLIS = 20_000L;
	static final String FIREMAKING = "FIREMAKING";

	private static final WoodcuttingCompanionDefinition DEF = WoodcuttingCompanionDefinition.INSTANCE;

	private static final class Bucket
	{
		long wcXp;
		long fmXp;
		long bark;
		final List<Integer> cuts = new ArrayList<>();
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
		boolean infernal;
		int slotDelta;
		long atMillis;
		/** Inventory log gains a cut line may still claim (one cut never counts twice). */
		final Map<Integer, Long> unclaimed = new HashMap<>();
		boolean claimableReady;
		boolean settled;
	}

	public static final class Deposit implements SkillTracker.Deposit
	{
		private final int itemId;
		private final long quantity;

		Deposit(int itemId, long quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
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
	}

	/** The tree being cut. */
	private static final class Tree
	{
		final WoodcuttingMethod method;
		final String label;
		final int objectId;
		final long tile;
		final long clickedAt;
		long produced;
		long firstAt = -1L;
		long lastAt = -1L;

		Tree(WoodcuttingMethod method, String label, int objectId, long tile, long clickedAt)
		{
			this.method = method;
			this.label = label;
			this.objectId = objectId;
			this.tile = tile;
			this.clickedAt = clickedAt;
		}
	}

	private final MethodTracker methods = new MethodTracker();
	private final ProductionLedger ledger = new ProductionLedger();
	private final SkillStorage basket = WoodcuttingCompanionDefinition.createBasket();
	private final ForestryContext forestry = new ForestryContext();
	/** Inventory room filled by cuts (slots or basket places): 12 samples, at least 4 over 20 s; a 60 s pause starts over. */
	private final RateWindow fill = new RateWindow(12, 4, 20_000L, 60_000L);
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();
	/** When each fallen nest fell (oldest first): picking one up claims one. */
	private final List<Long> fallenNests = new ArrayList<>();
	private final Map<String, Long> contextEvidence = new LinkedHashMap<>();

	private Map<Integer, Long> lastInventory;
	private int usedSlots = -1;
	private Map<Integer, Long> lastBank;
	private boolean bankOpen;
	private boolean depositBoxOpen;
	private boolean infernalCarried;
	private int lastTick = Integer.MIN_VALUE;
	private int fillClickTick = Integer.MIN_VALUE;
	private int emptyClickTick = Integer.MIN_VALUE;
	private int leprechaunClickTick = Integer.MIN_VALUE;
	private int dropClickTick = Integer.MIN_VALUE;
	private int takeClickTick = Integer.MIN_VALUE;
	private boolean entNestClaimed;
	private Tree tree;
	private String felledText;
	private long felledAt = -1L;
	private long revision;
	private long lastForestryRevision = -1L;

	// ---- inputs ---------------------------------------------------------------------------

	@Override
	public void onSkillXp(int tick, long delta, long nowMillis)
	{
		if (delta > 0)
		{
			Bucket b = bucket(tick, nowMillis);
			b.wcXp += delta;
			b.infernal |= infernalCarried;
		}
	}

	@Override
	public void onOtherSkillXp(String skillName, int tick, long delta, long nowMillis)
	{
		if (delta <= 0 || skillName == null)
		{
			return;
		}
		if (FIREMAKING.equalsIgnoreCase(skillName))
		{
			Bucket b = bucket(tick, nowMillis);
			b.fmXp += delta;
			b.infernal |= infernalCarried;
		}
		forestry.otherSkillXp(skillName, nowMillis);
	}

	@Override
	public void onChat(int tick, long nowMillis, String message)
	{
		if (message == null)
		{
			return;
		}
		Integer cut = WoodcuttingCompanionDefinition.cutProduct(message);
		if (cut != null)
		{
			bucket(tick, nowMillis).cuts.add(cut);
			return;
		}
		if (WoodcuttingCompanionDefinition.isNestFalling(message))
		{
			fallenNests.add(nowMillis);
			revision++;
			return;
		}
		Long bark = WoodcuttingCompanionDefinition.barkAwarded(message);
		if (bark != null)
		{
			bucket(tick, nowMillis).bark += Math.max(1L, bark);
			forestry.barkAwarded(bark, nowMillis);
			return;
		}
		forestry.onChat(message, nowMillis);
	}

	/**
	 * The player clicked "Chop down" (or "Chop" / "Cut") on a tree: a method
	 * HINT (never adopted alone) and the new CURRENT TREE. {@code tile} is any
	 * stable key of the object's location.
	 */
	public void onTreeClick(long nowMillis, String treeName, int objectId, long tile)
	{
		WoodcuttingMethod m = WoodcuttingMethod.forTreeName(treeName);
		if (m == null)
		{
			return;
		}
		methods.hint(m.getKey(), nowMillis);
		if (tree == null || tree.tile != tile || tree.objectId != objectId)
		{
			tree = new Tree(m, cleanName(treeName, m), objectId, tile, nowMillis);
			revision++;
		}
	}

	private static String cleanName(String name, WoodcuttingMethod m)
	{
		String n = name == null ? "" : name.trim();
		return n.isEmpty() ? m.getLabel() : Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	/** A game object despawned: the current tree fell when it was that tree. */
	public void onObjectDespawned(int objectId, long tile, long nowMillis)
	{
		forestry.objectDespawned(objectId, nowMillis);
		if (tree != null && tree.objectId == objectId && tree.tile == tile)
		{
			if (tree.produced > 0 && tree.firstAt >= 0)
			{
				felledText = tree.label + " fell: " + String.format(java.util.Locale.ROOT, "%,d", tree.produced) + " "
					+ DEF.focusCountCaption(tree.method.getKey()).toLowerCase(java.util.Locale.ROOT)
					+ (tree.lastAt > tree.firstAt ? " in " + clock((tree.lastAt - tree.firstAt) / 1000) : "");
				felledAt = nowMillis;
			}
			tree = null;
			revision++;
		}
	}

	public void onObjectSpawned(int objectId, long nowMillis)
	{
		forestry.objectSpawned(objectId, nowMillis);
	}

	public void onNpcSpawned(int npcId, long nowMillis)
	{
		forestry.npcSpawned(npcId, nowMillis);
	}

	public void onNpcDespawned(int npcId, long nowMillis)
	{
		forestry.npcDespawned(npcId, nowMillis);
	}

	/** The scene is reloading (region change): Forestry entities will spawn again if they are still there. */
	public void onSceneLoading(long nowMillis)
	{
		forestry.sceneReloading(nowMillis);
		// Scene tiles change with the scene: the tree is no longer identifiable (never reported as fallen).
		if (tree != null)
		{
			tree = null;
			revision++;
		}
	}

	/** Whether a charged infernal axe is carried right now (inventory or worn). */
	public void setInfernalCarried(boolean carried)
	{
		infernalCarried = carried;
	}

	public void onBasketFill(int tick)
	{
		fillClickTick = tick;
	}

	public void onBasketEmpty(int tick)
	{
		emptyClickTick = tick;
	}

	/** Any option on the Woodcutting Leprechaun: his free banking may follow. */
	public void onLeprechaunClick(int tick)
	{
		leprechaunClickTick = tick;
	}

	/** "Drop" on an inventory item: a log leaving now is never a leprechaun deposit. */
	public void onDropClick(int tick)
	{
		dropClickTick = tick;
	}

	/** "Take" on a ground item: leaves picked up now are not an event reward. */
	public void onTakeClick(int tick)
	{
		takeClickTick = tick;
	}

	@Override
	public void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed)
	{
		basket.onInventory(items);
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
		usedSlots = slotsUsed;
		revision++;
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
		settleUpTo(tick - WINDOW_TICKS - 1);
		forestry.step(nowMillis);
		for (String skill : forestry.contextSkills(nowMillis))
		{
			contextEvidence.put(skill, nowMillis);
		}
		for (Iterator<Long> it = fallenNests.iterator(); it.hasNext(); )
		{
			if (nowMillis - it.next() > NEST_PICKUP_MILLIS)
			{
				it.remove();
			}
		}
		if (forestry.getRevision() != lastForestryRevision)
		{
			lastForestryRevision = forestry.getRevision();
			revision++;
		}
		if (!forestry.entNestWindow(nowMillis))
		{
			entNestClaimed = false;
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
						if (d.getValue() > 0 && WoodcuttingCompanionDefinition.isPrimary(d.getKey()))
						{
							b.unclaimed.put(d.getKey(), d.getValue());
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
		long wcNear = 0L;
		boolean cutNear = false;
		boolean barkNear = false;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			wcNear += n.wcXp;
			cutNear |= !n.cuts.isEmpty();
			barkNear |= n.bark > 0;
		}
		boolean causal = wcNear > 0 || cutNear;
		boolean changed = false;

		// 1) Banking: the shared rule. The basket empties into the bank as a whole.
		if (b.bankOpen || b.depositBoxOpen)
		{
			if (SkillBanking.settleBankingTick(b.invNet, b.bankNet, b.depositBoxOpen, ledger, basket,
				WoodcuttingCompanionDefinition::basketAccepts, (item, qty) -> deposits.add(new Deposit(item, qty))))
			{
				revision++;
			}
			return;
		}

		// 2) Removals: a basket fill, the leprechaun's free banking, anything else forgets held production.
		boolean filling = basket.isHeld() && recent(fillClickTick, tick, CLICK_TICKS);
		boolean emptying = basket.isHeld() && recent(emptyClickTick, tick, CLICK_TICKS);
		boolean leprechaun = recent(leprechaunClickTick, tick, LEPRECHAUN_TICKS) && !recent(dropClickTick, tick, CLICK_TICKS);
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long removed = -d.getValue();
			if (removed <= 0)
			{
				continue;
			}
			int item = d.getKey();
			if (filling && WoodcuttingCompanionDefinition.basketAccepts(item))
			{
				ledger.inventoryToStorage(item, removed);
				basket.filled((int) Math.min(Integer.MAX_VALUE, removed));
				changed = true;
				continue;
			}
			if (leprechaun && WoodcuttingCompanionDefinition.basketAccepts(item))
			{
				// The Woodcutting Leprechaun banks logs for free: a proven deposit of up to what this session still holds.
				long banked = ledger.bankedFromInventory(item, removed);
				if (banked > 0)
				{
					deposits.add(new Deposit(item, banked));
					changed = true;
				}
				ledger.inventoryRemoved(item, removed - banked);
				continue;
			}
			ledger.inventoryRemoved(item, removed);
		}

		// 3) Gains.
		String productMethod = null;
		boolean primaryToInventory = false;
		boolean primaryGainThisTick = false;
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			long gained = d.getValue();
			int item = d.getKey();
			if (gained <= 0)
			{
				continue;
			}
			if (WoodcuttingCompanionDefinition.isPrimary(item))
			{
				primaryGainThisTick = true;
				if (causal && emptying && WoodcuttingCompanionDefinition.basketAccepts(item))
				{
					// Emptying the basket while still cutting: only what a cut line names is new.
					long cut = Math.min(gained, cutLinesNear(tick, item));
					long moved = gained - cut;
					if (moved > 0)
					{
						ledger.storageToInventory(item, moved);
						basket.emptiedToInventory((int) Math.min(Integer.MAX_VALUE, moved));
						changed = true;
					}
					gained = cut;
					if (gained <= 0)
					{
						continue;
					}
				}
				if (!causal)
				{
					if (basket.isHeld() && WoodcuttingCompanionDefinition.basketAccepts(item))
					{
						// Emptied out of the basket into the inventory: a move, never production.
						ledger.storageToInventory(item, gained);
						basket.emptiedToInventory((int) Math.min(Integer.MAX_VALUE, gained));
						changed = true;
					}
					continue;
				}
				ledger.produced(item, gained, ProductionLedger.Destination.INVENTORY);
				primaryToInventory = true;
				changed = true;
				treeProduced(item, gained, b.atMillis);
				WoodcuttingMethod m = WoodcuttingMethod.producing(item);
				if (m != null && productMethod == null)
				{
					productMethod = m.getKey();
				}
				continue;
			}
			if (WoodcuttingCompanionDefinition.inGroup(item, WoodcuttingCompanionDefinition.GROUP_NEST))
			{
				long claimed = claimNests(gained, b.atMillis);
				if (claimed > 0)
				{
					ledger.produced(item, claimed, ProductionLedger.Destination.INVENTORY);
					changed = true;
				}
				continue;
			}
			if (WoodcuttingCompanionDefinition.inGroup(item, WoodcuttingCompanionDefinition.GROUP_BARK))
			{
				if (barkNear)
				{
					ledger.produced(item, gained, ProductionLedger.Destination.INVENTORY);
					changed = true;
				}
				continue;
			}
			if (WoodcuttingCompanionDefinition.inGroup(item, WoodcuttingCompanionDefinition.GROUP_LEAF))
			{
				if (forestry.leafRewardWindow(b.atMillis) && !recent(takeClickTick, tick, CLICK_TICKS))
				{
					ledger.produced(item, gained, ProductionLedger.Destination.INVENTORY);
					changed = true;
				}
				continue;
			}
			if (WoodcuttingCompanionDefinition.inGroup(item, WoodcuttingCompanionDefinition.GROUP_FOSSIL) && wcNear > 0)
			{
				ledger.produced(item, gained, ProductionLedger.Destination.INVENTORY);
				changed = true;
			}
		}

		// 4) Cut lines whose log never reached the inventory: burned by an infernal axe (proven on this
		//    tick), or caught by an open basket.
		int burns = b.wcXp > 0 && b.fmXp > 0 && b.infernal && !primaryGainThisTick ? 1 : 0;
		long burned = 0L;
		int routed = 0;
		for (Integer log : b.cuts)
		{
			if (claimInventoryCut(tick, log))
			{
				continue;
			}
			if (burns > 0)
			{
				ledger.autoTransformed(log, 1L);
				burns--;
				burned++;
				treeProduced(log, 1L, b.atMillis);
				productMethod = productMethod != null ? productMethod : methodOf(log);
				changed = true;
				continue;
			}
			if (basket.isCatching(log) && !basketFull())
			{
				ledger.produced(log, 1L, ProductionLedger.Destination.STORAGE);
				basket.routed(1);
				routed++;
				treeProduced(log, 1L, b.atMillis);
				productMethod = productMethod != null ? productMethod : methodOf(log);
				changed = true;
			}
		}
		if (burns > 0)
		{
			// No line named it: the tree being cut, else the current tree kind (never a guess beyond that).
			Integer log = burnIdentity();
			if (log != null)
			{
				ledger.autoTransformed(log, 1L);
				burned++;
				treeProduced(log, 1L, b.atMillis);
				productMethod = productMethod != null ? productMethod : methodOf(log);
				changed = true;
			}
		}

		// 5) Method evidence, once per real cut.
		if (primaryToInventory || routed > 0 || burned > 0)
		{
			String before = methods.getCurrent();
			methods.success(productMethod, b.atMillis);
			changed |= !Objects.equals(before, methods.getCurrent());
		}
		else if (b.wcXp > 0)
		{
			// Woodcutting XP with nothing Spyglass identifies (Forestry roots, a deferred tree): still cutting, no tree invented.
			methods.success(null, b.atMillis);
		}

		// 6) Ownership: Firemaking XP while a charged infernal axe burns this tree's logs is Woodcutting's.
		if (b.infernal && (b.wcXp > 0 || burned > 0) && infernalTree())
		{
			contextEvidence.put(FIREMAKING, b.atMillis);
		}

		// 7) Trip fill rate: inventory slots this cut used + basket places (a burned log uses none).
		long filled = (primaryToInventory ? Math.max(0, b.slotDelta) : 0) + routed;
		if (filled > 0 && !emptying && !filling)
		{
			fill.record(b.atMillis, filled);
		}
		if (changed)
		{
			revision++;
		}
	}

	/** Whether the tree being cut (or the current tree kind) is one an infernal axe burns. */
	private boolean infernalTree()
	{
		WoodcuttingMethod m = tree != null ? tree.method : WoodcuttingMethod.fromKey(methods.getCurrent());
		return m == null || m.isInfernalBurns();
	}

	private Integer burnIdentity()
	{
		WoodcuttingMethod m = tree != null ? tree.method : WoodcuttingMethod.fromKey(methods.getCurrent());
		return m != null && m.isInfernalBurns() ? m.getMainProduct() : null;
	}

	private static String methodOf(int item)
	{
		WoodcuttingMethod m = WoodcuttingMethod.producing(item);
		return m == null ? null : m.getKey();
	}

	private boolean basketFull()
	{
		SkillStorage.Kind kind = basket.getKind();
		Integer known = basket.getKnownContents();
		return kind != null && kind.getCapacity() != null && known != null && known >= kind.getCapacity();
	}

	private void treeProduced(int item, long quantity, long atMillis)
	{
		if (tree == null || WoodcuttingMethod.producing(item) != tree.method)
		{
			return;
		}
		tree.produced += quantity;
		if (tree.firstAt < 0)
		{
			tree.firstAt = atMillis;
		}
		tree.lastAt = Math.max(tree.lastAt, atMillis);
	}

	/** Claims up to {@code quantity} fallen nests (oldest first), or the Friendly Ent's one end nest. */
	private long claimNests(long quantity, long atMillis)
	{
		long claimed = 0L;
		for (Iterator<Long> it = fallenNests.iterator(); it.hasNext() && claimed < quantity; )
		{
			long fell = it.next();
			if (fell <= atMillis + 1_200L && atMillis - fell <= NEST_PICKUP_MILLIS)
			{
				it.remove();
				claimed++;
			}
		}
		if (claimed < quantity && !entNestClaimed && forestry.entNestWindow(atMillis))
		{
			entNestClaimed = true;
			claimed++;
		}
		return claimed;
	}

	/** A click on {@code clickTick} explains changes up to {@code ticks} later (never before it). */
	private static boolean recent(int clickTick, int tick, int ticks)
	{
		return clickTick != Integer.MIN_VALUE && tick >= clickTick && (long) tick - clickTick <= ticks;
	}

	private long cutLinesNear(int tick, int item)
	{
		long n = 0L;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket b = buckets.get(t);
			if (b != null)
			{
				for (Integer c : b.cuts)
				{
					if (c == item)
					{
						n++;
					}
				}
			}
		}
		return n;
	}

	private boolean claimInventoryCut(int tick, int item)
	{
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			long available = n.unclaimed.getOrDefault(item, 0L);
			if (available > 0)
			{
				n.unclaimed.put(item, available - 1);
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

	public SkillStorage getBasket()
	{
		return basket;
	}

	ForestryContext forestry()
	{
		return forestry;
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

	@Override
	public Map<String, Long> drainContextEvidence()
	{
		if (contextEvidence.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, Long> out = new LinkedHashMap<>(contextEvidence);
		contextEvidence.clear();
		return out;
	}

	@Override
	public Map<String, Long> drainOccurrences()
	{
		return forestry.drainOccurrences();
	}

	@Override
	public Map<String, Long> getPendingOccurrences()
	{
		return forestry.getPendingOccurrences();
	}

	@Override
	public void discardPendingOccurrences()
	{
		forestry.discardPendingOccurrences();
	}

	/**
	 * CURRENT TRIP: free slots, and EST. FULL from the observed rate at which
	 * cuts actually USED inventory room (a log an infernal axe burned uses
	 * none, so it never shortens the estimate). With an open basket catching,
	 * the trip ends when both are full -- known only once the basket's
	 * contents are known; until then no estimate is claimed.
	 */
	@Override
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		if (lastInventory == null || usedSlots < 0)
		{
			return null;
		}
		int free = Math.max(0, INVENTORY_SLOTS - usedSlots);
		SkillStorage.Kind kind = basket.getKind();
		boolean catching = basket.isCatching() && !basketFull();
		Long est;
		boolean includesBasket = false;
		if (!catching)
		{
			est = fill.millisUntil(free, nowMillis);
		}
		else
		{
			Integer room = basket.getKnownRoom();
			if (room == null)
			{
				est = null;
			}
			else
			{
				est = fill.millisUntil((long) free + room, nowMillis);
				includesBasket = room > 0 && est != null;
			}
		}
		SkillCompanionView.TripView t = new SkillCompanionView.TripView(free, est, kind == null ? null : kind.getLabel(),
			basket.getKnownContents(), kind == null ? null : kind.getCapacity(), basket.getAddedThisTrip(), catching);
		return includesBasket ? t.withEstIncludingStorage() : t;
	}

	@Override
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
		List<SkillCompanionView.ContextLine> out = new ArrayList<>();
		if (basketFull() && basket.isHeld())
		{
			out.add(new SkillCompanionView.ContextLine(basket.getKind().getLabel() + " full", SkillCompanionView.ContextLine.Tone.ALERT,
				"Your basket is full: new logs go to your inventory until you empty it"));
		}
		if (felledText != null && felledAt >= 0 && nowMillis - felledAt <= FELLED_LINE_MILLIS)
		{
			out.add(new SkillCompanionView.ContextLine(felledText, SkillCompanionView.ContextLine.Tone.QUIET,
				"The tree you were cutting fell (its own logs, from your first to your last)"));
		}
		return out;
	}

	/** CURRENT TREE: shown once the clicked tree gave something, while it still does. */
	@Override
	public SkillCompanionView.FocusView focus(long nowMillis)
	{
		Tree t = tree;
		if (t == null || !t.method.hasFocus() || t.firstAt < 0 || nowMillis - t.lastAt > FOCUS_IDLE_MILLIS)
		{
			return null;
		}
		return new SkillCompanionView.FocusView(t.label, t.produced, Math.max(0L, nowMillis - t.firstAt));
	}

	@Override
	public SkillCompanionView.EventView event(long nowMillis)
	{
		return forestry.view(nowMillis);
	}

	@Override
	public void reset()
	{
		methods.reset();
		ledger.reset();
		basket.reset();
		forestry.reset();
		fill.clear();
		buckets.clear();
		deposits.clear();
		fallenNests.clear();
		contextEvidence.clear();
		lastInventory = null;
		usedSlots = -1;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		infernalCarried = false;
		lastTick = Integer.MIN_VALUE;
		fillClickTick = Integer.MIN_VALUE;
		emptyClickTick = Integer.MIN_VALUE;
		leprechaunClickTick = Integer.MIN_VALUE;
		dropClickTick = Integer.MIN_VALUE;
		takeClickTick = Integer.MIN_VALUE;
		entNestClaimed = false;
		tree = null;
		felledText = null;
		felledAt = -1L;
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
		infernalCarried = false;
		basket.reset();
		forestry.onLogout();
		fill.clear();
		fallenNests.clear();
		contextEvidence.clear();
		fillClickTick = Integer.MIN_VALUE;
		emptyClickTick = Integer.MIN_VALUE;
		leprechaunClickTick = Integer.MIN_VALUE;
		dropClickTick = Integer.MIN_VALUE;
		takeClickTick = Integer.MIN_VALUE;
		tree = null;
		felledText = null;
		felledAt = -1L;
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

	static String clock(long seconds)
	{
		long s = Math.max(0L, seconds);
		return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
			: String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}
}
