package com.osrstelemetry.plugin.skills.smithing;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleTracker;
import com.osrstelemetry.plugin.skills.SkillTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Smithing companion's brain (pure; client thread only), on the SAME
 * chassis as every skill companion: ProductionLedger (with consumed inputs),
 * MethodTracker (the RECIPE is the method), SkillBanking.
 *
 * <h3>Actual output is the authority</h3>
 * Session production is always the output that actually arrived; inputs are
 * what actually left. Recipe ratios are used only for CURRENT BATCH / ACCOUNT
 * POTENTIAL (planning) and to MATCH a delayed input (a mould's bar) -- never
 * to invent output or consumption. The XP amount never identifies anything.
 *
 * <h3>What proves an action (per settled tick, bank closed)</h3>
 * <ul>
 * <li>SMELTING: a bar GAINED + its primary ore CONSUMED + Smithing XP on that
 * tick or the one next to it, and no Magic XP there.</li>
 * <li>SUPERHEAT: the same, WITH Magic XP there (the session stays Smithing's:
 * Magic XP is never lifecycle evidence).</li>
 * <li>ANVIL / FORGE / COMBINE: the product GAINED + its primary input
 * CONSUMED + Smithing XP (±1 tick).</li>
 * <li>MOULD: cannonballs GAINED + Smithing XP, matched to the bars poured in
 * the preceding {@link #MOULD_WINDOW_TICKS} ticks (the pour comes first).</li>
 * <li>BLAST FURNACE: ore on the belt is NOT production. A smelt (the
 * dispenser's bar varbit up, the furnace's ore varbits down) only records a
 * PENDING batch with its exact inputs. Production is the bars actually
 * COLLECTED: the bar gained in the inventory while the dispenser's varbit
 * fell (±1 tick); the collected bars settle the pending inputs first-in
 * first-out (bars smelted before Spyglass saw them collect without inputs).</li>
 * <li>Iron that is "too impure": the game's line + the ore gone + no bar ->
 * a failure (the ore is a consumed input; no bar is ever invented).</li>
 * </ul>
 * A withdrawal, deposit, drop, rearrangement, coal bag fill / empty or a
 * Giants' Foundry material change never matches any of these.
 *
 * <h3>Coal bag</h3>
 * An INPUT container with no native state: its contents become known only
 * from its own Check line, then follow Fill / Empty. Moving coal in or out is
 * never consumption; a coal smelt that may have drawn on the bag makes its
 * contents unknown again (never guessed).
 *
 * <h3>Giants' Foundry</h3>
 * Its region is a structured activity: nothing there is a recipe, a batch,
 * production or a pending input.
 */
public final class SmithingCompanionTracker implements SkillTracker
{
	static final int WINDOW_TICKS = 1;
	/** A mould's bar is poured this many ticks (at most) before its cannonballs arrive (an action is 8 ticks). */
	static final int MOULD_WINDOW_TICKS = 12;
	/** A coal bag Fill / Empty click explains a coal change this many ticks later at most. */
	static final int COAL_BAG_CLICK_TICKS = 2;
	static final int FOUNDRY_REGION = 13491;
	/** Pending Blast Furnace batches kept per bar (the dispenser holds at most 28 bars). */
	static final int MAX_PENDING_BATCHES = 64;

	static final String MADE = "made:";
	static final String USED = "in:";
	static final String FAIL = "fail:";

	static final Pattern IMPURE = Pattern.compile("(?i)too impure");
	static final Pattern BAG_CONTAINS = Pattern.compile("(?i)coal bag (?:now |still )?contains (one|[0-9,]+) pieces? of coal");
	static final Pattern BAG_EMPTY = Pattern.compile("(?i)coal bag is (?:now )?empty");

	private static final class Bucket
	{
		long xp;
		long magicXp;
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		/** Blast Furnace furnace contents (ore / coal item -> varbit delta). */
		final Map<Integer, Long> furnaceNet = new HashMap<>();
		/** Blast Furnace dispenser (bar item -> varbit delta). */
		final Map<Integer, Long> dispenserNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
		boolean foundry;
		int impure;
		Long bagCheck;
		long atMillis;
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

	/** Bars the Blast Furnace smelted (seen), with the exact furnace inputs that left for them. */
	private static final class Batch
	{
		long bars;
		final Map<Integer, Long> inputs = new LinkedHashMap<>();
	}

	/** A bar that left the inventory (unclaimed) while a mould was carried: a pour, until its cannonballs arrive. */
	private static final class Pour
	{
		final int tick;
		final int item;
		long qty;

		Pour(int tick, int item, long qty)
		{
			this.tick = tick;
			this.item = item;
			this.qty = qty;
		}
	}

	private final MethodTracker methods = new MethodTracker();
	private final ProductionLedger ledger = new ProductionLedger();
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();
	private final Map<String, Long> pendingOccurrences = new LinkedHashMap<>();
	private final Map<Integer, Deque<Batch>> pendingBatches = new HashMap<>();
	private final Deque<Pour> pours = new ArrayDeque<>();
	/** Smithing XP of recent ticks (mould matching reaches further back than the settled buckets). */
	private final TreeMap<Integer, Long> xpHistory = new TreeMap<>();
	/** Blast Furnace varbits last seen (varbit -> value); a first sighting is a baseline, never a change. */
	private final Map<Integer, Integer> furnaceVars = new HashMap<>();

	private Map<Integer, Long> lastInventory;
	private Map<Integer, Long> lastBank;
	private Set<Integer> worn = Collections.emptySet();
	private boolean bankOpen;
	private boolean depositBoxOpen;
	private boolean inFoundry;
	private boolean barsHot;
	/** The latest load of each ore onto the belt (ore item -> {tick, quantity}): one trip's ore, for "Trips before coal". */
	private final Map<Integer, long[]> oreLoads = new HashMap<>();
	/** Known coal bag contents; null = unknown (never shown as zero). */
	private Long coalBag;
	private int bagFillUntil = Integer.MIN_VALUE;
	private int bagEmptyUntil = Integer.MIN_VALUE;
	private int lastTick = Integer.MIN_VALUE;
	private long revision;

	// ---- inputs -------------------------------------------------------------------------------

	@Override
	public void onSkillXp(int tick, long delta, long nowMillis)
	{
		if (delta > 0)
		{
			bucket(tick, nowMillis).xp += delta;
			xpHistory.merge(tick, delta, Long::sum);
		}
	}

	@Override
	public void onOtherSkillXp(String skillName, int tick, long delta, long nowMillis)
	{
		if (delta > 0 && "MAGIC".equalsIgnoreCase(skillName))
		{
			bucket(tick, nowMillis).magicXp += delta;
		}
	}

	@Override
	public void onChat(int tick, long nowMillis, String message)
	{
		if (message == null)
		{
			return;
		}
		if (IMPURE.matcher(message).find())
		{
			bucket(tick, nowMillis).impure++;
			return;
		}
		Matcher m = BAG_CONTAINS.matcher(message);
		if (m.find())
		{
			String n = m.group(1);
			bucket(tick, nowMillis).bagCheck = "one".equalsIgnoreCase(n) ? 1L : Long.parseLong(n.replace(",", ""));
		}
		else if (BAG_EMPTY.matcher(message).find())
		{
			bucket(tick, nowMillis).bagCheck = 0L;
		}
	}

	/** A coal bag menu option was clicked ("Fill" / "Empty"); the coal change that follows is storage movement. */
	public void onCoalBagOption(int tick, String option)
	{
		if ("fill".equalsIgnoreCase(option))
		{
			bagFillUntil = tick + COAL_BAG_CLICK_TICKS;
		}
		else if ("empty".equalsIgnoreCase(option))
		{
			bagEmptyUntil = tick + COAL_BAG_CLICK_TICKS;
		}
	}

	/** Every item worn right now (ids). */
	public void setWorn(Set<Integer> items)
	{
		Set<Integer> next = items == null ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(new HashSet<>(items));
		if (!next.equals(worn))
		{
			worn = next;
			revision++;
		}
	}

	/** One Blast Furnace varbit (furnace contents, dispenser bars, hot bars) as the client holds it now. */
	public void onFurnaceVar(int tick, long nowMillis, int varbit, int value)
	{
		if (varbit == net.runelite.api.gameval.VarbitID.BLAST_FURNACE_BARS_HOT)
		{
			boolean hot = value > 0;
			if (hot != barsHot)
			{
				barsHot = hot;
				revision++;
			}
			return;
		}
		Integer before = furnaceVars.put(varbit, value);
		if (before == null || before == value)
		{
			if (before == null)
			{
				revision++;
			}
			return;
		}
		long d = (long) value - before;
		SmithingRecipes.Bar bar = SmithingRecipes.barForDispenserVarbit(varbit);
		if (bar != null)
		{
			bucket(tick, nowMillis).dispenserNet.merge(bar.getBarItem(), d, Long::sum);
		}
		else
		{
			for (SmithingRecipes.FurnaceVar f : SmithingRecipes.furnaceOres())
			{
				if (f.getVarbit() == varbit)
				{
					if (d > 0 && f.getItemId() != SmithingRecipes.COAL)
					{
						// One trip's ore arrives on one tick (a second varbit step on the same tick adds to it).
						long[] load = oreLoads.get(f.getItemId());
						oreLoads.put(f.getItemId(), load != null && load[0] == tick ? new long[]{tick, load[1] + d} : new long[]{tick, d});
					}
					bucket(tick, nowMillis).furnaceNet.merge(f.getItemId(), d, Long::sum);
					break;
				}
			}
		}
		revision++;
	}

	@Override
	public void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed)
	{
		if (lastInventory == null)
		{
			lastInventory = new HashMap<>(items);
			revision++;
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		SkillBanking.diffInto(lastInventory, items, b.invNet);
		b.bankOpen |= bankOpen;
		b.depositBoxOpen |= depositBoxOpen;
		b.foundry |= inFoundry;
		lastInventory = new HashMap<>(items);
		revision++;
	}

	@Override
	public void onBank(int tick, long nowMillis, Map<Integer, Long> items)
	{
		if (lastBank == null)
		{
			lastBank = new HashMap<>(items);
			revision++;
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		SkillBanking.diffInto(lastBank, items, b.bankNet);
		b.bankOpen = true;
		lastBank = new HashMap<>(items);
		revision++;
	}

	@Override
	public void setBankOpen(boolean open)
	{
		if (open != bankOpen)
		{
			bankOpen = open;
			revision++;
		}
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
		boolean foundry = regionId == FOUNDRY_REGION;
		if (foundry != inFoundry)
		{
			inFoundry = foundry;
			revision++;
		}
		if (foundry)
		{
			bucket(tick, nowMillis).foundry = true;
		}
		methods.step(nowMillis);
		settleUpTo(tick - WINDOW_TICKS - 1);
		while (!xpHistory.isEmpty() && xpHistory.firstKey() < tick - 3 * MOULD_WINDOW_TICKS)
		{
			xpHistory.pollFirstEntry();
		}
	}

	// ---- settlement ---------------------------------------------------------------------------

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
		if (b.bagCheck != null && !Objects.equals(coalBag, b.bagCheck))
		{
			coalBag = b.bagCheck;
			revision++;
		}
		settleCoalBag(tick, b);
		// A smelt in the furnace is native state, whatever interface is open (the Blast Furnace has a bank chest).
		recordBlastFurnaceSmelts(tick, b);
		if (b.foundry || near(tick, n -> n.foundry))
		{
			// Giants' Foundry: its own structured activity -- nothing here is Smithing production or input.
			forgetRemoved(b.invNet);
			return;
		}
		if (b.bankOpen || b.depositBoxOpen)
		{
			if (SkillBanking.settleBankingTick(b.invNet, b.bankNet, b.depositBoxOpen, ledger, null, i -> false,
				(item, qty) -> deposits.add(new Deposit(item, qty))))
			{
				revision++;
			}
			return;
		}
		Map<Integer, Long> net = new HashMap<>(b.invNet);
		collectBlastFurnace(tick, b, net);
		long xpNear = 0L;
		long magicNear = 0L;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n != null)
			{
				xpNear += n.xp;
				magicNear += n.magicXp;
			}
		}
		for (Map.Entry<Integer, Long> d : new ArrayList<>(net.entrySet()))
		{
			if (d.getValue() > 0 && SmithingRecipes.isOutput(d.getKey()))
			{
				make(tick, b, d.getKey(), net, xpNear, magicNear);
			}
		}
		if (b.impure > 0)
		{
			fail(b, net);
		}
		rememberPours(tick, net);
		forgetRemoved(net);
	}

	private interface BucketTest
	{
		boolean test(Bucket b);
	}

	private boolean near(int tick, BucketTest test)
	{
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n != null && test.test(n))
			{
				return true;
			}
		}
		return false;
	}

	/** Coal moved by the coal bag's own Fill / Empty: storage movement (contents followed only while known). */
	private void settleCoalBag(int tick, Bucket b)
	{
		if (coalBag == null)
		{
			return;
		}
		long inv = b.invNet.getOrDefault(SmithingRecipes.COAL, 0L);
		long bank = b.bankNet.getOrDefault(SmithingRecipes.COAL, 0L);
		if (tick <= bagFillUntil && (inv < 0 || (b.bankOpen && bank < 0 && inv == 0)))
		{
			coalBag += inv < 0 ? -inv : -bank;
			revision++;
		}
		else if (tick <= bagEmptyUntil && inv > 0)
		{
			long left = coalBag - inv;
			coalBag = left < 0 ? null : left;
			revision++;
		}
	}

	/** Dispenser bars up + furnace ores down: a PENDING batch with its exact inputs (never production). */
	private void recordBlastFurnaceSmelts(int tick, Bucket b)
	{
		List<SmithingRecipes.Bar> smelted = new ArrayList<>();
		long coalNeed = 0L;
		for (Map.Entry<Integer, Long> d : b.dispenserNet.entrySet())
		{
			SmithingRecipes.Bar bar = SmithingRecipes.barOf(d.getKey());
			if (d.getValue() > 0 && bar != null)
			{
				smelted.add(bar);
				coalNeed += d.getValue() * (bar.getCoal() / 2);
			}
		}
		if (smelted.isEmpty())
		{
			return;
		}
		// Ore / coal that left the furnace on this tick or the ones next to it (each spent once).
		Map<Integer, Long> left = new HashMap<>();
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n == null)
			{
				continue;
			}
			for (Map.Entry<Integer, Long> f : n.furnaceNet.entrySet())
			{
				if (f.getValue() < 0)
				{
					left.merge(f.getKey(), -f.getValue(), Long::sum);
				}
			}
		}
		long coalLeft = left.getOrDefault(SmithingRecipes.COAL, 0L);
		for (SmithingRecipes.Bar bar : smelted)
		{
			long bars = b.dispenserNet.get(bar.getBarItem());
			Batch batch = new Batch();
			batch.bars = bars;
			for (int ore : bar.getOres())
			{
				long have = left.getOrDefault(ore, 0L);
				if (have > 0)
				{
					SmithingRecipe r = SmithingRecipes.producing(bar.getBarItem(), SmithingProcess.BLAST_FURNACE);
					SmithingRecipe.Input in = r == null ? null : r.input(ore);
					long want = in == null ? have : bars * in.getPerAction();
					long take = Math.min(have, want);
					batch.inputs.merge(ore, take, Long::sum);
					left.put(ore, have - take);
					spendFurnace(tick, ore, take);
				}
			}
			if (bar.getCoal() > 0 && coalLeft > 0 && coalNeed > 0)
			{
				long share = smelted.size() == 1 ? coalLeft : coalLeft * (bars * (bar.getCoal() / 2)) / coalNeed;
				if (share > 0)
				{
					batch.inputs.merge(SmithingRecipes.COAL, share, Long::sum);
					spendFurnace(tick, SmithingRecipes.COAL, share);
				}
			}
			Deque<Batch> q = pendingBatches.computeIfAbsent(bar.getBarItem(), k -> new ArrayDeque<>());
			q.addLast(batch);
			while (q.size() > MAX_PENDING_BATCHES)
			{
				q.pollFirst();
			}
		}
		b.dispenserNet.replaceAll((k, v) -> v > 0 ? 0L : v);
		revision++;
	}

	private void spendFurnace(int tick, int item, long amount)
	{
		long left = amount;
		int[] order = {tick, tick - WINDOW_TICKS, tick + WINDOW_TICKS};
		for (int t : order)
		{
			Bucket n = buckets.get(t);
			if (n == null || left <= 0)
			{
				continue;
			}
			long have = Math.max(0L, -n.furnaceNet.getOrDefault(item, 0L));
			long take = Math.min(have, left);
			if (take > 0)
			{
				n.furnaceNet.put(item, -(have - take));
				left -= take;
			}
		}
	}

	/** Bars gained while the dispenser emptied (±1 tick): the only Blast Furnace production. */
	private void collectBlastFurnace(int tick, Bucket b, Map<Integer, Long> net)
	{
		for (Map.Entry<Integer, Long> d : new ArrayList<>(net.entrySet()))
		{
			SmithingRecipes.Bar bar = SmithingRecipes.barOf(d.getKey());
			if (d.getValue() <= 0 || bar == null || bar.getBfBarVarbit() <= 0)
			{
				continue;
			}
			long budget = 0L;
			for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
			{
				Bucket n = buckets.get(t);
				budget += n == null ? 0L : Math.max(0L, -n.dispenserNet.getOrDefault(bar.getBarItem(), 0L));
			}
			long collected = Math.min(d.getValue(), budget);
			if (collected <= 0)
			{
				continue;
			}
			spendDispenser(tick, bar.getBarItem(), collected);
			SmithingRecipe r = SmithingRecipes.producing(bar.getBarItem(), SmithingProcess.BLAST_FURNACE);
			ledger.produced(bar.getBarItem(), collected, ProductionLedger.Destination.INVENTORY);
			net.merge(bar.getBarItem(), -collected, Long::sum);
			settleBatches(r, bar.getBarItem(), collected);
			success(r, collected, b.atMillis);
		}
	}

	private void spendDispenser(int tick, int barItem, long amount)
	{
		long left = amount;
		int[] order = {tick, tick - WINDOW_TICKS, tick + WINDOW_TICKS};
		for (int t : order)
		{
			Bucket n = buckets.get(t);
			if (n == null || left <= 0)
			{
				continue;
			}
			long have = Math.max(0L, -n.dispenserNet.getOrDefault(barItem, 0L));
			long take = Math.min(have, left);
			if (take > 0)
			{
				n.dispenserNet.put(barItem, -(have - take));
				left -= take;
			}
		}
	}

	/** Collected bars settle the pending inputs first-in first-out (a partly collected batch: its share, rounded down). */
	private void settleBatches(SmithingRecipe r, int barItem, long collected)
	{
		Deque<Batch> q = pendingBatches.get(barItem);
		long left = collected;
		while (q != null && !q.isEmpty() && left > 0)
		{
			Batch batch = q.peekFirst();
			long take = Math.min(left, batch.bars);
			for (Iterator<Map.Entry<Integer, Long>> it = batch.inputs.entrySet().iterator(); it.hasNext(); )
			{
				Map.Entry<Integer, Long> in = it.next();
				long used = take == batch.bars ? in.getValue() : in.getValue() * take / batch.bars;
				if (used > 0)
				{
					consume(r, in.getKey(), used);
					in.setValue(in.getValue() - used);
				}
			}
			batch.bars -= take;
			left -= take;
			if (batch.bars <= 0)
			{
				q.pollFirst();
			}
		}
	}

	/** One output item gained this tick: the action that made it, when (and only when) its causal evidence holds. */
	private void make(int tick, Bucket b, int outputItem, Map<Integer, Long> net, long xpNear, long magicNear)
	{
		long gained = net.getOrDefault(outputItem, 0L);
		if (gained <= 0)
		{
			return;
		}
		SmithingRecipe r = null;
		for (SmithingRecipe c : SmithingRecipes.producing(outputItem))
		{
			if (c.getProcess() == SmithingProcess.BLAST_FURNACE)
			{
				continue;
			}
			if (c.getProcess().makesBars() && (c.getProcess() == SmithingProcess.SUPERHEAT) != (magicNear > 0))
			{
				continue;
			}
			r = c;
			break;
		}
		if (r == null)
		{
			return;
		}
		if (r.getProcess() == SmithingProcess.MOULD)
		{
			pour(tick, b, r, gained, net);
			return;
		}
		SmithingRecipe.Input primary = r.primary();
		long primaryUsed = -net.getOrDefault(primary.getItemId(), 0L);
		if (primaryUsed <= 0 || xpNear <= 0)
		{
			return;
		}
		ledger.produced(outputItem, gained, ProductionLedger.Destination.INVENTORY);
		net.put(outputItem, 0L);
		long coalFromInventory = 0L;
		for (SmithingRecipe.Input in : r.getInputs())
		{
			long used = -net.getOrDefault(in.getItemId(), 0L);
			if (used > 0)
			{
				consume(r, in.getItemId(), used);
				net.put(in.getItemId(), 0L);
				if (in.isCoal())
				{
					coalFromInventory = used;
				}
			}
		}
		long catalysts = -net.getOrDefault(SmithingRecipes.CATALYST, 0L);
		if (catalysts > 0 && r.usesCoal())
		{
			consume(r, SmithingRecipes.CATALYST, catalysts);
			net.put(SmithingRecipes.CATALYST, 0L);
		}
		if (r.usesCoal() && coalBag != null && carriesCoalBag())
		{
			SmithingRecipe.Input coal = r.input(SmithingRecipes.COAL);
			long bars = gained / Math.max(1, r.getOutputPerAction());
			long half = Math.min(bars, catalysts);
			long need = half * (coal.getPerAction() / 2) + (bars - half) * coal.getPerAction();
			if (coalFromInventory < need)
			{
				// The bag may have supplied the rest: its contents are no longer known (never guessed).
				coalBag = null;
			}
		}
		success(r, gained, b.atMillis);
	}

	/** Cannonballs: matched to bars poured within the mould window, with Smithing XP inside it. */
	private void pour(int tick, Bucket b, SmithingRecipe r, long gained, Map<Integer, Long> net)
	{
		int bar = r.primary().getItemId();
		long barsWanted = (gained + r.getOutputPerAction() - 1) / r.getOutputPerAction();
		// This tick's own unclaimed removal first (a pour and an arrival can share a tick), then earlier pours.
		long now = -net.getOrDefault(bar, 0L);
		long matched = 0L;
		int earliest = tick;
		if (now > 0)
		{
			long take = Math.min(now, barsWanted);
			matched += take;
			net.merge(bar, take, Long::sum);
		}
		for (Iterator<Pour> it = pours.iterator(); it.hasNext() && matched < barsWanted; )
		{
			Pour p = it.next();
			if (p.item != bar || p.tick < tick - MOULD_WINDOW_TICKS)
			{
				continue;
			}
			long take = Math.min(p.qty, barsWanted - matched);
			p.qty -= take;
			matched += take;
			earliest = Math.min(earliest, p.tick);
			if (p.qty <= 0)
			{
				it.remove();
			}
		}
		if (matched <= 0)
		{
			return;
		}
		long xp = 0L;
		for (Map.Entry<Integer, Long> e : xpHistory.subMap(earliest - WINDOW_TICKS, true, tick + WINDOW_TICKS, true).entrySet())
		{
			xp += e.getValue();
		}
		if (xp <= 0)
		{
			return;
		}
		ledger.produced(r.getOutputItem(), gained, ProductionLedger.Destination.INVENTORY);
		net.put(r.getOutputItem(), 0L);
		consume(r, bar, matched);
		success(r, gained, b.atMillis);
	}

	/** Bars that left (unclaimed, bank closed) while a mould is carried wait for their cannonballs. */
	private void rememberPours(int tick, Map<Integer, Long> net)
	{
		while (!pours.isEmpty() && pours.peekFirst().tick < tick - MOULD_WINDOW_TICKS)
		{
			pours.pollFirst();
		}
		if (lastInventory == null || !(lastInventory.containsKey(SmithingRecipes.AMMO_MOULD)
			|| lastInventory.containsKey(SmithingRecipes.DOUBLE_AMMO_MOULD)))
		{
			return;
		}
		for (Map.Entry<Integer, Long> d : net.entrySet())
		{
			if (d.getValue() < 0 && SmithingRecipes.barOf(d.getKey()) != null)
			{
				pours.addLast(new Pour(tick, d.getKey(), -d.getValue()));
			}
		}
	}

	/** "Too impure": the iron ore that left with the game's line and no bar is a failed smelt (no bar invented). */
	private void fail(Bucket b, Map<Integer, Long> net)
	{
		long ore = -net.getOrDefault(SmithingRecipes.IRON_ORE, 0L);
		long failed = Math.min(b.impure, ore);
		if (failed <= 0)
		{
			return;
		}
		SmithingRecipe r = SmithingRecipes.recipe(SmithingRecipes.SMELT + "iron");
		consume(r, SmithingRecipes.IRON_ORE, failed);
		net.merge(SmithingRecipes.IRON_ORE, failed, Long::sum);
		pendingOccurrences.merge(FAIL + r.getKey(), failed, Long::sum);
		revision++;
	}

	private void consume(SmithingRecipe r, int item, long qty)
	{
		ledger.consumed(item, qty);
		pendingOccurrences.merge(USED + r.getKey() + ":" + item, qty, Long::sum);
	}

	private void success(SmithingRecipe r, long units, long atMillis)
	{
		String before = methods.getCurrent();
		methods.success(r.getKey(), atMillis);
		pendingOccurrences.merge(MADE + r.getKey(), units, Long::sum);
		if (!Objects.equals(before, methods.getCurrent()))
		{
			revision++;
		}
		revision++;
	}

	private void forgetRemoved(Map<Integer, Long> net)
	{
		for (Map.Entry<Integer, Long> d : net.entrySet())
		{
			if (d.getValue() < 0)
			{
				ledger.inventoryRemoved(d.getKey(), -d.getValue());
			}
		}
	}

	// ---- state reads --------------------------------------------------------------------------

	boolean carriesCoalBag()
	{
		return lastInventory != null && (lastInventory.containsKey(SmithingRecipes.COAL_BAG) || lastInventory.containsKey(SmithingRecipes.COAL_BAG_OPEN));
	}

	/** Known coal bag contents; null when unknown. */
	public Long getCoalBag()
	{
		return coalBag;
	}

	public boolean isInFoundry()
	{
		return inFoundry;
	}

	/** A Blast Furnace varbit's current value; null before it was seen. */
	Integer furnaceVar(int varbit)
	{
		return furnaceVars.get(varbit);
	}

	/** Bars smelted and waiting in the dispenser that Spyglass saw being smelted (with known inputs). */
	long pendingBars(int barItem)
	{
		Deque<Batch> q = pendingBatches.get(barItem);
		long n = 0L;
		if (q != null)
		{
			for (Batch b : q)
			{
				n += b.bars;
			}
		}
		return n;
	}

	Map<Integer, Long> inventory()
	{
		return lastInventory == null ? Collections.<Integer, Long>emptyMap() : Collections.unmodifiableMap(lastInventory);
	}

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

	@Override
	public SkillCycleTracker getCycles()
	{
		return null;
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
	public Map<String, Long> drainOccurrences()
	{
		if (pendingOccurrences.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, Long> out = new LinkedHashMap<>(pendingOccurrences);
		pendingOccurrences.clear();
		return out;
	}

	@Override
	public Map<String, Long> getPendingOccurrences()
	{
		return Collections.unmodifiableMap(pendingOccurrences);
	}

	@Override
	public void discardPendingOccurrences()
	{
		pendingOccurrences.clear();
	}

	/** Smithing has no slot-capacity decision: no trip estimate. */
	@Override
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		return null;
	}

	// ---- BLAST FURNACE / IN BANK / CONTEXT ------------------------------------------------------

	/**
	 * Only the Blast Furnace has a panel of its own (what is loaded and ready):
	 * smelting at a furnace, smithing at an anvil, cannonballs and Superheat
	 * show no generic batch -- PRODUCTION, USED and IN BANK say it. Absent
	 * before a recipe is known and inside Giants' Foundry.
	 */
	@Override
	public SkillCompanionView.EventView event(long nowMillis)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methods.getCurrent());
		if (r == null || lastInventory == null || inFoundry || r.getProcess() != SmithingProcess.BLAST_FURNACE)
		{
			return null;
		}
		return blastFurnace(r);
	}

	/**
	 * BLAST FURNACE, from the game's own counters: the coal loaded and the
	 * trips it still covers (only once a trip's ore load was seen), the ore
	 * loaded, and bars ready only while there are some. Never a zero cell for
	 * an empty furnace, never a theoretical XP rate. Gold (no coal) is simply
	 * its ore and bars.
	 */
	private SkillCompanionView.EventView blastFurnace(SmithingRecipe r)
	{
		SmithingRecipes.Bar bar = SmithingRecipes.barOf(r.getOutputItem());
		List<SkillCompanionView.ItemStat> items = new ArrayList<>();
		List<SkillCompanionView.ContextLine> lines = new ArrayList<>();
		Integer ore = null;
		Integer coal = null;
		for (SmithingRecipes.FurnaceVar f : SmithingRecipes.furnaceOres())
		{
			if (f.getItemId() == r.primary().getItemId())
			{
				ore = furnaceVars.get(f.getVarbit());
			}
			else if (f.getItemId() == SmithingRecipes.COAL)
			{
				coal = furnaceVars.get(f.getVarbit());
			}
		}
		Integer ready = bar == null ? null : furnaceVars.get(bar.getBfBarVarbit());
		if (r.usesCoal() && coal != null && coal > 0)
		{
			items.add(SkillCompanionView.ItemStat.line(SmithingRecipes.COAL, "Coal", fmt(coal) + " loaded", "Coal in the furnace: " + fmt(coal)
				+ " (the game's own count)"));
		}
		if (ore != null && ore > 0)
		{
			items.add(SkillCompanionView.ItemStat.line(r.primary().getItemId(), r.primary().getName(), fmt(ore) + " loaded",
				r.primary().getName() + " on the belt / in the furnace: " + fmt(ore) + " (the game's own count)"));
		}
		if (ready != null && ready > 0 && bar != null)
		{
			items.add(SkillCompanionView.ItemStat.line(bar.getBarItem(), bar.getBarName(), fmt(ready) + " ready",
				bar.getBarName() + ": " + fmt(ready) + " waiting in the dispenser (counted once you collect them)"));
		}
		if (r.usesCoal() && bar != null && coal != null)
		{
			Long trips = tripsBeforeCoal(r, bar, coal);
			if (trips != null)
			{
				lines.add(new SkillCompanionView.ContextLine("Trips before coal: " + fmt(trips),
					trips == 0 ? SkillCompanionView.ContextLine.Tone.ALERT : SkillCompanionView.ContextLine.Tone.NORMAL,
					"Trips of " + fmt(oreLoadOf(r)) + " " + r.primary().getName().toLowerCase(Locale.ROOT)
						+ " (your last load) the loaded coal still covers at the Blast Furnace's coal per bar"));
			}
		}
		if (barsHot && ready != null && ready > 0 && Collections.disjoint(worn, SmithingRecipes.COOLING_GLOVES))
		{
			lines.add(new SkillCompanionView.ContextLine("Bars are hot: ice gloves or a bucket of water", SkillCompanionView.ContextLine.Tone.ALERT,
				"Hot bars can only be taken with ice gloves (or smiths' gloves (i)) worn, or after cooling them"));
		}
		if (r.usesCoal() && carriesCoalBag())
		{
			lines.add(coalBagLine());
		}
		if (items.isEmpty() && lines.isEmpty())
		{
			return null;
		}
		return new SkillCompanionView.EventView("blast_furnace", null, 0L, Collections.<SkillCompanionView.EventView.Field>emptyList(), lines)
			.withTitle("Blast Furnace")
			.withItemGroups(Collections.singletonList(new SkillCompanionView.ItemGroup(null, null, items)));
	}

	/** The ore of the last trip loaded (null until one was seen). */
	private Long oreLoadOf(SmithingRecipe r)
	{
		long[] load = oreLoads.get(r.primary().getItemId());
		return load == null || load[1] <= 0 ? null : load[1];
	}

	/** Whole trips of the last ore load the loaded coal still covers (the Blast Furnace halves coal); null until a load was seen. */
	Long tripsBeforeCoal(SmithingRecipe r, SmithingRecipes.Bar bar, int coalLoaded)
	{
		Long load = oreLoadOf(r);
		long perBar = bar.getCoal() / 2;
		if (load == null || perBar <= 0)
		{
			return null;
		}
		return (long) coalLoaded / (perBar * load);
	}

	private SkillCompanionView.ContextLine coalBagLine()
	{
		return new SkillCompanionView.ContextLine(coalBag == null ? "Coal bag: not checked" : "Coal bag: " + fmt(coalBag) + " coal",
			SkillCompanionView.ContextLine.Tone.QUIET, coalBag == null
			? "Check the bag once and Spyglass follows its fills and empties (it is never guessed)"
			: "From the bag's own Check, followed through its fills and empties");
	}

	/**
	 * IN BANK (planning only): the current recipe's inputs as sprites with
	 * what the bank holds (as seen THIS login; a dash until then, never zero)
	 * and "Can make from bank: N" -- the scarcest input decides, at the
	 * recipe's own amounts (the Blast Furnace's halved coal included). What
	 * you carry is not added. Absent before a recipe is known and inside
	 * Giants' Foundry.
	 */
	@Override
	public SkillCompanionView.EventView stock(long nowMillis)
	{
		SmithingRecipe r = SmithingRecipes.recipe(methods.getCurrent());
		if (r == null || inFoundry)
		{
			return null;
		}
		List<SkillCompanionView.ItemStat> items = new ArrayList<>();
		for (SmithingRecipe.Input in : r.getInputs())
		{
			Long have = lastBank == null ? null : lastBank.getOrDefault(in.getItemId(), 0L);
			items.add(SkillCompanionView.ItemStat.of(in.getItemId(), in.getName(), have, in.getName() + (have == null
				? ": open your bank to count it" : ": " + fmt(have) + " in your bank")));
		}
		List<SkillCompanionView.ContextLine> lines = new ArrayList<>();
		if (lastBank == null)
		{
			lines.add(new SkillCompanionView.ContextLine("Open your bank to count your supplies", SkillCompanionView.ContextLine.Tone.QUIET,
				"Spyglass counts what your bank holds only once it was seen this login"));
		}
		else
		{
			long catalysts = r.usesCoal() ? lastBank.getOrDefault(SmithingRecipes.CATALYST, 0L) : 0L;
			long can = r.canMake(lastBank, catalysts, 0L);
			lines.add(new SkillCompanionView.ContextLine("Can make from bank: " + fmt(can) + " " + r.getUnitCaption().toLowerCase(Locale.ROOT),
				SkillCompanionView.ContextLine.Tone.NORMAL,
				"What your bank's " + r.getName().toLowerCase(Locale.ROOT) + " inputs can still make -- the scarcest one decides (what you carry "
					+ "is not included)"));
		}
		return new SkillCompanionView.EventView("bank", null, 0L, Collections.<SkillCompanionView.EventView.Field>emptyList(), lines)
			.withTitle("In bank")
			.withItemGroups(Collections.singletonList(new SkillCompanionView.ItemGroup(null, null, items)));
	}

	/** Whether smelting iron right now may fail: at a normal furnace, iron ore carried, no ring of forging worn. */
	boolean ironMayFail()
	{
		SmithingRecipe r = SmithingRecipes.recipe(methods.getCurrent());
		return r != null && SmithingRecipes.SMELT.concat("iron").equals(r.getKey()) && lastInventory != null
			&& lastInventory.getOrDefault(SmithingRecipes.IRON_ORE, 0L) > 0 && !worn.contains(SmithingRecipes.RING_OF_FORGING);
	}

	@Override
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
		if (inFoundry)
		{
			return Collections.singletonList(new SkillCompanionView.ContextLine("Giants' Foundry: its own activity",
				SkillCompanionView.ContextLine.Tone.QUIET, "Bars and swords inside the Foundry are never counted as Smithing recipes or production here"));
		}
		List<SkillCompanionView.ContextLine> out = new ArrayList<>();
		if (ironMayFail())
		{
			// Live: gone the moment the ring is worn, the iron is used up, or the recipe changes.
			String tip = "At a normal furnace about half of your iron ore fails to smelt without a ring of forging worn";
			out.add(new SkillCompanionView.ContextLine("No Ring of Forging equipped", SkillCompanionView.ContextLine.Tone.DANGER, tip));
			out.add(new SkillCompanionView.ContextLine("Iron may fail to smelt", SkillCompanionView.ContextLine.Tone.DANGER, tip));
		}
		SmithingRecipe r = SmithingRecipes.recipe(methods.getCurrent());
		if (r != null && r.usesCoal() && r.getProcess() != SmithingProcess.BLAST_FURNACE && carriesCoalBag())
		{
			out.add(coalBagLine());
		}
		return out;
	}

	private static String fmt(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}

	// ---- lifecycle ----------------------------------------------------------------------------

	@Override
	public void reset()
	{
		methods.reset();
		ledger.reset();
		buckets.clear();
		deposits.clear();
		pendingOccurrences.clear();
		pendingBatches.clear();
		pours.clear();
		xpHistory.clear();
		furnaceVars.clear();
		oreLoads.clear();
		lastInventory = null;
		lastBank = null;
		worn = Collections.emptySet();
		bankOpen = false;
		depositBoxOpen = false;
		inFoundry = false;
		barsHot = false;
		coalBag = null;
		bagFillUntil = Integer.MIN_VALUE;
		bagEmptyUntil = Integer.MIN_VALUE;
		revision++;
	}

	@Override
	public void onLogout()
	{
		buckets.clear();
		pours.clear();
		xpHistory.clear();
		// Varbits are re-sent at login: a fresh baseline, never a change. Pending batches keep their inputs
		// (the dispenser keeps its bars across a relog).
		furnaceVars.clear();
		lastInventory = null;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		inFoundry = false;
		coalBag = null;
		revision++;
	}

	@Override
	public void settleAll()
	{
		settleUpTo(Integer.MAX_VALUE - 1);
	}
}
