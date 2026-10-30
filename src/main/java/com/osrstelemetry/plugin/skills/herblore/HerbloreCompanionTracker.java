package com.osrstelemetry.plugin.skills.herblore;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleTracker;
import com.osrstelemetry.plugin.skills.SkillTracker;
import com.osrstelemetry.plugin.skills.UnitFamily;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Herblore companion's brain (pure; client thread only), on the SAME
 * chassis as every skill companion: ProductionLedger (with consumed inputs),
 * MethodTracker (the RECIPE is the method), SkillBanking.
 *
 * <h3>A mix is causal</h3>
 * Per game tick: Herblore XP and the NET change of every inventory / bank
 * item (and, while the bank is open, of every Potion Storage family). A tick
 * settles once the ticks either side are closed. A MIX of recipe R is, on one
 * tick with the bank closed: R's output GAINED, R's primary input (the grimy
 * herb, the herb, the unfinished potion, the base potion) CONSUMED, and --
 * for every recipe except unfinished potions, which the game gives no XP for
 * -- Herblore XP on that tick or the one next to it. The XP amount never
 * identifies anything. A withdrawal, a deposit, a Potion Storage move, an
 * NPC or manual decant, combining doses, drinking: none of them gains a
 * recipe's output while consuming its primary input with Herblore XP, so
 * none is ever production.
 *
 * <h3>Doses</h3>
 * A potion recipe's output is counted in DOSES of its family (every variant
 * is the same resource): the doses produced are the family's net dose change
 * on the mix tick, so an amulet's bonus dose is simply what arrived.
 *
 * <h3>Effects, only when actually observed</h3>
 * <ul>
 * <li>Bonus doses: output doses beyond the recipe's base (3 per mix, or the
 * base potion's own doses) on a tick with a dose-adding amulet worn.</li>
 * <li>Saved secondaries: a known requirement minus what actually left the
 * inventory, with prescription goggles worn and no reagent pouch carried
 * (whose secondaries never leave the inventory).</li>
 * </ul>
 *
 * <h3>Mastering Mixology</h3>
 * A structured activity: while any of its lab-only items (pastes, its
 * potions, its special herb) is carried or changes, or one of its reward
 * potion packs is opened -- and for a minute after -- nothing here is a
 * recipe, a batch or production.
 */
public final class HerbloreCompanionTracker implements SkillTracker
{
	static final int WINDOW_TICKS = 1;
	/** Mixology context lasts this long after its items were last seen. */
	static final long MIXOLOGY_GRACE_MILLIS = 60_000L;
	/** A charge drop this close to an observed bonus dose is that bonus's. */
	static final long CHARGE_MATCH_MILLIS = 3_000L;

	static final String MIX = "mix:";
	static final String UNITS = "units:";
	static final String USED = "in:";
	static final String BONUS = "bonus:";
	static final String SAVED = "saved:";

	private static final class Bucket
	{
		long xp;
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		final Map<String, Long> storageNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
		boolean mixology;
		boolean amulet;
		boolean goggles;
		boolean pouch;
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

	private final MethodTracker methods = new MethodTracker();
	private final ProductionLedger ledger = new ProductionLedger();
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();
	private final Map<String, Long> pendingOccurrences = new LinkedHashMap<>();

	private Map<Integer, Long> lastInventory;
	private Map<Integer, Long> lastBank;
	/** Potion Storage, family key -> units, read live while the bank was open this login; null = not read. */
	private Map<String, Long> liveStorage;
	private Set<Integer> worn = Collections.emptySet();
	private boolean bankOpen;
	private boolean depositBoxOpen;
	private long mixologyUntil = Long.MIN_VALUE;
	private long lastStep;
	/** The alchemist's amulet's native charge counter (null = never read); validated once it moved with an observed bonus. */
	private Integer alchemistCharges;
	/** The counter's latest drop (amount and when), to match against an observed bonus dose. */
	private long lastChargeDrop;
	private long lastChargeDropAt = Long.MIN_VALUE;
	private boolean chargesValidated;
	private long revision;
	/** When the worn amulet of chemistry crumbled (the game's own line); Long.MIN_VALUE = not since it was last worn. */
	private long chemistryCrumbledAt = Long.MIN_VALUE;
	/** Herblore XP each recipe's single unit actually gave (mixes, or doses for dose-keeping recipes), from clean samples. */
	private final Map<String, XpSample> xpPerUnit = new HashMap<>();

	/** The amulet of chemistry's last-charge line. */
	static final java.util.regex.Pattern CHEMISTRY_CRUMBLED = java.util.regex.Pattern.compile("(?i)amulet of chemistry.*crumbles");
	/** The crumbled-amulet warning stays at most this long (it also clears once a dose-adding amulet is worn again). */
	static final long CHEMISTRY_WARNING_MILLIS = 15 * 60_000L;

	/**
	 * Herblore XP per unit of one recipe, only from ticks where that recipe
	 * alone produced and its XP arrived on the same tick. Trusted once two
	 * samples agree within 10% (a shared or split XP drop never agrees).
	 */
	static final class XpSample
	{
		long xp;
		long units;
		int samples;
		double min = Double.MAX_VALUE;
		double max;

		void add(long xpGained, long unitsMade)
		{
			double per = xpGained / (double) unitsMade;
			xp += xpGained;
			units += unitsMade;
			samples++;
			min = Math.min(min, per);
			max = Math.max(max, per);
		}

		/** Null until trusted. */
		Double perUnit()
		{
			return samples >= 2 && units > 0 && max <= min * 1.10 ? xp / (double) units : null;
		}
	}

	// ---- inputs -------------------------------------------------------------------------------

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
		if (message != null && CHEMISTRY_CRUMBLED.matcher(message).find())
		{
			chemistryCrumbledAt = nowMillis;
			revision++;
		}
	}

	/** Every item worn right now (ids). */
	public void setWorn(Set<Integer> items)
	{
		Set<Integer> next = items == null ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(new HashSet<>(items));
		if (!next.equals(worn))
		{
			worn = next;
			if (containsAny(worn, HerbloreRecipes.BONUS_AMULETS))
			{
				// A dose-adding amulet is worn again: the crumbled warning is over.
				chemistryCrumbledAt = Long.MIN_VALUE;
			}
			revision++;
		}
	}

	/** The alchemist's amulet's charge counter (its native varbit). */
	public void setAlchemistCharges(int charges, long nowMillis)
	{
		Integer before = alchemistCharges;
		alchemistCharges = Math.max(0, charges);
		if (before != null && alchemistCharges < before)
		{
			lastChargeDrop = before - alchemistCharges;
			lastChargeDropAt = nowMillis;
		}
		if (!Objects.equals(before, alchemistCharges))
		{
			revision++;
		}
	}

	@Override
	public void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed)
	{
		boolean mixology = containsAny(items.keySet(), HerbloreRecipes.MIXOLOGY_ITEMS);
		if (lastInventory == null)
		{
			lastInventory = new HashMap<>(items);
			if (mixology)
			{
				mixologyUntil = nowMillis + MIXOLOGY_GRACE_MILLIS;
			}
			revision++;
			return;
		}
		Bucket b = bucket(tick, nowMillis);
		SkillBanking.diffInto(lastInventory, items, b.invNet);
		mixology |= containsAny(b.invNet.keySet(), HerbloreRecipes.MIXOLOGY_ITEMS)
			|| containsAny(b.invNet.keySet(), HerbloreRecipes.MIXOLOGY_REWARD_PACKS);
		if (mixology)
		{
			mixologyUntil = nowMillis + MIXOLOGY_GRACE_MILLIS;
		}
		b.mixology |= mixology || nowMillis < mixologyUntil;
		b.bankOpen |= bankOpen;
		b.depositBoxOpen |= depositBoxOpen;
		b.amulet |= containsAny(worn, HerbloreRecipes.BONUS_AMULETS);
		b.goggles |= containsAny(worn, HerbloreRecipes.GOGGLES);
		b.pouch |= containsAny(items.keySet(), HerbloreRecipes.REAGENT_POUCH) || containsAny(lastInventory.keySet(), HerbloreRecipes.REAGENT_POUCH);
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

	/**
	 * Potion Storage contents read live (family key -> doses, or quantity for
	 * undosed potions), only while the bank is open. Its change on a tick is
	 * what proves a deposit into it.
	 */
	public void onStorage(int tick, long nowMillis, Map<String, Long> units)
	{
		Map<String, Long> next = new HashMap<>(units);
		if (liveStorage != null)
		{
			Bucket b = bucket(tick, nowMillis);
			for (Map.Entry<String, Long> e : next.entrySet())
			{
				long d = e.getValue() - liveStorage.getOrDefault(e.getKey(), 0L);
				if (d != 0)
				{
					b.storageNet.merge(e.getKey(), d, Long::sum);
				}
			}
			for (Map.Entry<String, Long> e : liveStorage.entrySet())
			{
				if (!next.containsKey(e.getKey()) && e.getValue() != 0)
				{
					b.storageNet.merge(e.getKey(), -e.getValue(), Long::sum);
				}
			}
			b.bankOpen = true;
		}
		if (!next.equals(liveStorage))
		{
			revision++;
		}
		liveStorage = next;
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

	public boolean isBankOpen()
	{
		return bankOpen;
	}

	@Override
	public void onTick(int tick, long nowMillis, int regionId)
	{
		lastStep = nowMillis;
		methods.step(nowMillis);
		settleUpTo(tick - WINDOW_TICKS - 1);
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
		if (b.bankOpen || b.depositBoxOpen)
		{
			settleBanking(tick, b);
			return;
		}
		if (b.mixology)
		{
			// Mastering Mixology: its own activity -- nothing here is a recipe, a batch or production.
			forgetRemoved(b.invNet);
			return;
		}
		long xpNear = 0L;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n != null)
			{
				xpNear += n.xp;
				if (n.mixology)
				{
					forgetRemoved(b.invNet);
					return;
				}
			}
		}
		Set<Integer> claimed = new HashSet<>();
		HerbloreRecipe only = null;
		long onlyUnits = 0L;
		int mixed = 0;
		for (HerbloreRecipe r : candidates(b.invNet))
		{
			long units = mix(r, b, xpNear, claimed);
			if (units > 0)
			{
				mixed++;
				only = r;
				onlyUnits = units;
			}
		}
		if (mixed == 1 && b.xp > 0)
		{
			// One recipe alone, its XP on the same tick: a clean sample of what one unit is worth.
			xpPerUnit.computeIfAbsent(only.getKey(), k -> new XpSample()).add(b.xp, onlyUnits);
		}
		forgetRemoved(b.invNet);
	}

	/** Recipes whose output was gained this tick (each once, in a stable order). */
	private static List<HerbloreRecipe> candidates(Map<Integer, Long> net)
	{
		Set<HerbloreRecipe> out = new LinkedHashSet<>();
		for (Map.Entry<Integer, Long> d : net.entrySet())
		{
			if (d.getValue() > 0)
			{
				out.addAll(HerbloreRecipes.producing(d.getKey()));
			}
		}
		return new ArrayList<>(out);
	}

	/**
	 * One recipe's mixes on this tick, when (and only when) the causal evidence
	 * holds. Returns the units made (mixes, or the base potion's doses for a
	 * dose-keeping recipe); 0 = none.
	 */
	private long mix(HerbloreRecipe r, Bucket b, long xpNear, Set<Integer> claimed)
	{
		Map<Integer, Long> net = b.invNet;
		HerbloreRecipe.Input primary = r.primary();
		long primaryItems = 0L;
		long primaryUnits = 0L;
		for (Map.Entry<Integer, Integer> e : primary.getItems().entrySet())
		{
			long d = net.getOrDefault(e.getKey(), 0L);
			if (d < 0 && !claimed.contains(e.getKey()))
			{
				primaryItems += -d;
				primaryUnits += -d * (primary.isDosed() ? e.getValue() : 1);
			}
		}
		if (primaryItems <= 0 || (r.isXpRequired() && xpNear <= 0))
		{
			return 0L;
		}
		long gainedItems = 0L;
		long netUnits = 0L;
		for (Integer out : r.getOutputItems())
		{
			long d = net.getOrDefault(out, 0L);
			if (d > 0)
			{
				gainedItems += d;
			}
			netUnits += d * r.outputUnits(out);
		}
		if (gainedItems <= 0 || netUnits <= 0)
		{
			return 0L;
		}
		long mixes = Math.min(primaryItems, gainedItems);

		// Output (exact items), consumed inputs (exact items, actual amounts).
		for (Integer out : r.getOutputItems())
		{
			long d = net.getOrDefault(out, 0L);
			if (d > 0)
			{
				ledger.produced(out, d, ProductionLedger.Destination.INVENTORY);
			}
		}
		Map<HerbloreRecipe.Input, Long> usedByInput = new LinkedHashMap<>();
		for (HerbloreRecipe.Input in : r.getInputs())
		{
			long used = 0L;
			for (Integer item : in.getItems().keySet())
			{
				long d = net.getOrDefault(item, 0L);
				if (d < 0 && !claimed.contains(item))
				{
					ledger.consumed(item, -d);
					pendingOccurrences.merge(USED + r.getKey() + ":" + item, -d, Long::sum);
					used += -d;
					claimed.add(item);
				}
			}
			usedByInput.put(in, used);
		}
		String before = methods.getCurrent();
		methods.success(r.getKey(), b.atMillis);
		pendingOccurrences.merge(MIX + r.getKey(), mixes, Long::sum);
		pendingOccurrences.merge(UNITS + r.getKey(), netUnits, Long::sum);

		// A bonus dose: more doses than the recipe's base, with a dose-adding amulet worn.
		if (r.isBonusEligible() && b.amulet && r.isDoseNormalized())
		{
			long base = r.preservesDoses() ? primaryUnits : mixes * r.getFixedDoses();
			long bonus = netUnits - base;
			if (bonus > 0)
			{
				pendingOccurrences.merge(BONUS + r.getKey(), bonus, Long::sum);
				noteBonusForCharges(bonus, b.atMillis);
			}
		}
		// Saved secondaries: the known requirement minus what actually left, goggles worn, no reagent pouch.
		if (b.goggles && !b.pouch)
		{
			long saved = 0L;
			for (Map.Entry<HerbloreRecipe.Input, Long> u : usedByInput.entrySet())
			{
				HerbloreRecipe.Input in = u.getKey();
				if (in.getRole() != HerbloreRecipe.Role.SECONDARY || !in.isSavable())
				{
					continue;
				}
				long expected = in.getPerMix() != null ? mixes * in.getPerMix() : primaryUnits * in.getPerDose();
				if (expected > u.getValue())
				{
					saved += expected - u.getValue();
				}
			}
			if (saved > 0)
			{
				pendingOccurrences.merge(SAVED + r.getKey(), saved, Long::sum);
			}
		}
		if (!Objects.equals(before, methods.getCurrent()))
		{
			revision++;
		}
		revision++;
		return r.preservesDoses() ? primaryUnits : mixes;
	}

	/**
	 * The amulet's native counter is trusted (shown) only once it dropped by
	 * exactly the bonus doses Spyglass saw, within a few ticks of them -- never
	 * on the counter's say-so alone.
	 */
	private void noteBonusForCharges(long bonus, long atMillis)
	{
		if (!chargesValidated && alchemistCharges != null && worn.contains(HerbloreRecipes.ALCHEMISTS_AMULET)
			&& lastChargeDropAt != Long.MIN_VALUE && Math.abs(atMillis - lastChargeDropAt) <= CHARGE_MATCH_MILLIS && lastChargeDrop == bonus)
		{
			chargesValidated = true;
			revision++;
		}
	}

	/**
	 * Bank / deposit box open: deposits into the bank AND into Potion Storage.
	 * A Potion Storage deposit is proven by the storage's own dose increase of
	 * that family on the same tick or the one next to it (it is read on the
	 * game tick); each dose of increase proves at most one deposit.
	 */
	private void settleBanking(int tick, Bucket b)
	{
		Map<Integer, Long> inv = new HashMap<>(b.invNet);
		boolean changed = false;
		Set<String> families = new LinkedHashSet<>();
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n != null)
			{
				families.addAll(n.storageNet.keySet());
			}
		}
		for (String key : families)
		{
			UnitFamily f = HerbloreRecipes.family(key);
			long budget = 0L;
			for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
			{
				Bucket n = buckets.get(t);
				budget += n == null ? 0L : Math.max(0L, n.storageNet.getOrDefault(key, 0L));
			}
			if (f == null || budget <= 0)
			{
				continue;
			}
			long startBudget = budget;
			for (Map.Entry<Integer, Integer> m : f.getUnitsPerItem().entrySet())
			{
				long d = inv.getOrDefault(m.getKey(), 0L);
				if (d >= 0 || budget < m.getValue())
				{
					continue;
				}
				long removed = -d;
				long toBank = Math.min(removed, Math.max(0L, b.bankNet.getOrDefault(m.getKey(), 0L)));
				long unexplained = removed - toBank;
				long stored = Math.min(unexplained, budget / m.getValue());
				if (stored <= 0)
				{
					continue;
				}
				long banked = ledger.bankedFromInventory(m.getKey(), stored);
				if (banked > 0)
				{
					deposits.add(new Deposit(m.getKey(), banked));
					changed = true;
				}
				inv.merge(m.getKey(), stored, Long::sum);
				budget -= stored * m.getValue();
			}
			consumeStorageBudget(tick, key, startBudget - budget);
		}
		inv.values().removeIf(v -> v == 0);
		if (SkillBanking.settleBankingTick(inv, b.bankNet, b.depositBoxOpen, ledger, null, i -> false,
			(item, qty) -> deposits.add(new Deposit(item, qty))) || changed)
		{
			revision++;
		}
	}

	/** Spends {@code used} doses of a family's storage increase (this tick first, then its neighbours): never proves two deposits. */
	private void consumeStorageBudget(int tick, String key, long used)
	{
		long left = used;
		int[] order = {tick, tick - WINDOW_TICKS, tick + WINDOW_TICKS};
		for (int t : order)
		{
			Bucket n = buckets.get(t);
			if (n == null || left <= 0)
			{
				continue;
			}
			long have = Math.max(0L, n.storageNet.getOrDefault(key, 0L));
			long take = Math.min(have, left);
			if (take > 0)
			{
				n.storageNet.put(key, have - take);
				left -= take;
			}
		}
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

	private static boolean containsAny(Set<Integer> items, Set<Integer> any)
	{
		for (Integer i : any)
		{
			if (items.contains(i))
			{
				return true;
			}
		}
		return false;
	}

	// ---- state reads --------------------------------------------------------------------------

	/** Mastering Mixology context right now. */
	public boolean inMixology(long nowMillis)
	{
		return nowMillis < mixologyUntil || (lastInventory != null && containsAny(lastInventory.keySet(), HerbloreRecipes.MIXOLOGY_ITEMS));
	}

	/** Potion Storage units of a family as read live this login; null = not read this login. */
	public Long liveStorageUnits(String familyKey)
	{
		return liveStorage == null ? null : liveStorage.getOrDefault(familyKey, 0L);
	}

	public boolean hasLiveStorage()
	{
		return liveStorage != null;
	}

	/** The alchemist's amulet's charges, only once the native counter was validated against an observed bonus dose. */
	public Integer validatedAlchemistCharges()
	{
		return chargesValidated && worn.contains(HerbloreRecipes.ALCHEMISTS_AMULET) ? alchemistCharges : null;
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

	/** Potions are not a slot-capacity decision: no trip estimate. */
	@Override
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		return null;
	}

	// ---- CURRENT BATCH --------------------------------------------------------------------------

	/**
	 * CURRENT BATCH: what the current recipe can still make from what you
	 * carry (the limiting input decides), each input's count, and -- clearly
	 * labelled, only when the bank was seen this login -- what the whole
	 * account could make. Absent for herb cleaning, before a recipe is known,
	 * and inside Mastering Mixology.
	 */
	@Override
	public SkillCompanionView.EventView event(long nowMillis)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methods.getCurrent());
		if (r == null || lastInventory == null || r.getProcess() == HerbloreProcess.CLEANING || inMixology(nowMillis))
		{
			return null;
		}
		boolean pouch = containsAny(lastInventory.keySet(), HerbloreRecipes.REAGENT_POUCH);
		List<SkillCompanionView.EventView.Field> fields = new ArrayList<>();
		List<SkillCompanionView.ContextLine> lines = new ArrayList<>();
		for (HerbloreRecipe.Input in : r.getInputs())
		{
			long have = in.available(lastInventory);
			String caption = in.isDosed() ? in.getCaption() + " DOSES" : in.getCaption();
			fields.add(new SkillCompanionView.EventView.Field(caption, fmt(have),
				in.getName() + " you carry" + (in.isDosed() ? " (doses over every variant)" : "")
					+ (in.getRole() == HerbloreRecipe.Role.SECONDARY && pouch ? " -- your reagent pouch's are not counted" : "")));
		}
		Batch batch = r.isBatchReliable() ? canMake(r, lastInventory) : null;
		if (batch != null)
		{
			boolean atLeast = pouch && batch.secondaryLimited;
			String value = (atLeast ? "≥ " : "") + fmt(batch.amount) + (r.preservesDoses() ? " doses" : "");
			int limiting = -1;
			for (int i = 0; i < fields.size(); i++)
			{
				if (fields.get(i).getValue().equals(value))
				{
					limiting = i;
					break;
				}
			}
			if (limiting >= 0 && batch.amount > 0)
			{
				// Pass 28: CAN MAKE would repeat the limiting input's own count
				// (one of each per mix): that input says it instead.
				SkillCompanionView.EventView.Field f = fields.get(limiting);
				fields.set(limiting, new SkillCompanionView.EventView.Field(f.getCaption(), f.getValue(),
					f.getTooltip() + ". This one runs out first: you can make " + fmt(batch.amount) + " more"));
			}
			else
			{
				fields.add(new SkillCompanionView.EventView.Field("CAN MAKE", value, r.preservesDoses()
					? "Doses of " + r.getName() + " what you carry can still become (the limiting ingredient decides)"
					: "Mixes what you carry can still make (the limiting ingredient decides)"));
			}
		}
		if (pouch)
		{
			lines.add(new SkillCompanionView.ContextLine("Reagent pouch: its secondaries aren't counted", SkillCompanionView.ContextLine.Tone.QUIET,
				"Secondaries your open reagent pouch holds are used without leaving the inventory, so Spyglass cannot count them"));
		}
		Integer charges = validatedAlchemistCharges();
		if (charges != null && r.isBonusEligible())
		{
			lines.add(new SkillCompanionView.ContextLine(charges == 0 ? "Alchemist's amulet: no charges left"
				: "Alchemist's amulet: " + fmt(charges) + (charges == 1 ? " charge" : " charges"),
				charges == 0 ? SkillCompanionView.ContextLine.Tone.ALERT : SkillCompanionView.ContextLine.Tone.QUIET,
				"The amulet's own charge counter (the game's), shown once it moved with a bonus dose Spyglass saw"));
		}
		return new SkillCompanionView.EventView("batch", null, 0L, fields, lines).withTitle("Current batch");
	}

	/** CAN MAKE: mixes (or doses, for recipes that keep the base potion's doses) and whether a secondary is the limit. */
	static final class Batch
	{
		final long amount;
		final boolean secondaryLimited;

		Batch(long amount, boolean secondaryLimited)
		{
			this.amount = amount;
			this.secondaryLimited = secondaryLimited;
		}
	}

	static Batch canMake(HerbloreRecipe r, Map<Integer, Long> available)
	{
		long best = Long.MAX_VALUE;
		Map<HerbloreRecipe.Input, Long> cans = new LinkedHashMap<>();
		long baseUnits = r.primary().available(available);
		for (HerbloreRecipe.Input in : r.getInputs())
		{
			long have = in.available(available);
			long can;
			if (r.preservesDoses())
			{
				// In doses: the base potion's doses, and each per-dose ingredient's doses' worth.
				if (in.getRole() == HerbloreRecipe.Role.PRIMARY)
				{
					can = baseUnits;
				}
				else if (in.getPerDose() != null && in.getPerDose() > 0)
				{
					can = have / in.getPerDose();
				}
				else
				{
					return null;
				}
			}
			else
			{
				if (in.getPerMix() == null || in.getPerMix() <= 0)
				{
					return null;
				}
				can = have / in.getPerMix();
			}
			cans.put(in, can);
			best = Math.min(best, can);
		}
		if (best == Long.MAX_VALUE)
		{
			return null;
		}
		boolean secondaryLimited = false;
		for (Map.Entry<HerbloreRecipe.Input, Long> c : cans.entrySet())
		{
			secondaryLimited |= c.getKey().getRole() == HerbloreRecipe.Role.SECONDARY && c.getValue() == best;
		}
		return new Batch(Math.max(0L, best), secondaryLimited);
	}

	/**
	 * What the bank holds as seen THIS login (never a saved snapshot), plus
	 * Potion Storage when it was read this login (as units of each family's
	 * 1-unit member); null when the bank was not seen this login. Never the
	 * inventory: what you carry is CURRENT BATCH's.
	 */
	Map<Integer, Long> bankHoldings()
	{
		if (lastBank == null)
		{
			return null;
		}
		Map<Integer, Long> all = new HashMap<>(lastBank);
		if (liveStorage != null)
		{
			for (Map.Entry<String, Long> s : liveStorage.entrySet())
			{
				UnitFamily f = HerbloreRecipes.family(s.getKey());
				if (f != null && s.getValue() > 0)
				{
					int one = smallestMember(f);
					if (one > 0)
					{
						all.merge(one, s.getValue() / Math.max(1, f.unitsOf(one)), Long::sum);
					}
				}
			}
		}
		return all;
	}

	/** What the current recipe's own inputs in the bank (and Potion Storage) can make; null when unknown. */
	Batch bankCanMake(HerbloreRecipe r)
	{
		Map<Integer, Long> held = bankHoldings();
		return held == null || r == null || !r.isBatchReliable() ? null : canMake(r, held);
	}

	/** Herblore XP one unit of {@code r} gave this session (two agreeing samples); null = not known yet. */
	Double xpPerUnit(HerbloreRecipe r)
	{
		XpSample sample = r == null ? null : xpPerUnit.get(r.getKey());
		return sample == null ? null : sample.perUnit();
	}

	/**
	 * IN BANK (planning only, never part of the session): each input of the
	 * current recipe as its sprite with what the bank and Potion Storage hold
	 * ("-" until the bank is seen this login -- never zero), then what that
	 * can make (the scarcest input decides) and the XP it would give at the
	 * XP each one actually gave this session. Absent for herb cleaning, before
	 * a recipe is known and inside Mastering Mixology.
	 */
	@Override
	public SkillCompanionView.EventView stock(long nowMillis)
	{
		HerbloreRecipe r = HerbloreRecipes.recipe(methods.getCurrent());
		if (r == null || r.getProcess() == HerbloreProcess.CLEANING || inMixology(nowMillis))
		{
			return null;
		}
		Map<Integer, Long> held = bankHoldings();
		// A potion input can also sit in Potion Storage: until it was read this login the count is only a floor.
		boolean floor = false;
		List<SkillCompanionView.ItemStat> items = new ArrayList<>();
		for (HerbloreRecipe.Input in : r.getInputs())
		{
			boolean partial = in.isDosed() && liveStorage == null;
			floor |= partial && held != null;
			String count = held == null ? "\u2014" : (partial ? "\u2265 " : "") + fmt(in.available(held)) + (in.isDosed() ? " doses" : "");
			items.add(SkillCompanionView.ItemStat.line(spriteOf(in), in.getName(), in.getCaption() + "  " + count,
				in.getName() + (held == null ? ": open your bank to count it"
					: ": " + count + " in your bank" + (in.isDosed() ? (partial ? " (Potion Storage not read yet)" : " and Potion Storage") : ""))));
		}
		List<SkillCompanionView.ContextLine> lines = new ArrayList<>();
		if (held == null)
		{
			lines.add(new SkillCompanionView.ContextLine("Open your bank to count your supplies", SkillCompanionView.ContextLine.Tone.QUIET,
				"Spyglass counts what your bank holds only once it was seen this login"));
		}
		else
		{
			Batch b = bankCanMake(r);
			if (b != null)
			{
				String unit = r.preservesDoses() ? " doses" : "";
				lines.add(new SkillCompanionView.ContextLine("Bank can make: " + (floor ? "\u2265 " : "") + fmt(b.amount) + unit,
					SkillCompanionView.ContextLine.Tone.NORMAL,
					"What your bank's " + r.getName() + " ingredients can still make -- the scarcest one decides (what you carry is not included)"));
				Double perUnit = xpPerUnit(r);
				if (perUnit != null && b.amount > 0)
				{
					lines.add(new SkillCompanionView.ContextLine("Potential XP: " + (floor ? "\u2265 " : "") + fmt(Math.round(b.amount * perUnit)),
						SkillCompanionView.ContextLine.Tone.NORMAL,
						"That many " + (r.preservesDoses() ? "doses" : "potions") + " at the Herblore XP each one actually gave you this session"));
				}
			}
		}
		return new SkillCompanionView.EventView("bank", null, 0L, Collections.<SkillCompanionView.EventView.Field>emptyList(), lines)
			.withTitle("In bank")
			.withItemGroups(Collections.singletonList(new SkillCompanionView.ItemGroup(null, null, items)));
	}

	/** The sprite for an input: a potion's 1-dose variant, else its first item. */
	private static int spriteOf(HerbloreRecipe.Input in)
	{
		int best = -1;
		int bestDoses = Integer.MAX_VALUE;
		for (Map.Entry<Integer, Integer> e : in.getItems().entrySet())
		{
			int doses = in.isDosed() ? e.getValue() : 0;
			if (best < 0 || doses < bestDoses)
			{
				best = e.getKey();
				bestDoses = doses;
			}
		}
		return best;
	}

	/** The family's 1-unit member (for dose families the 1-dose item), else its only member. */
	private static int smallestMember(UnitFamily f)
	{
		int best = -1;
		int bestUnits = Integer.MAX_VALUE;
		for (Map.Entry<Integer, Integer> m : f.getUnitsPerItem().entrySet())
		{
			if (m.getValue() < bestUnits)
			{
				best = m.getKey();
				bestUnits = m.getValue();
			}
		}
		return best;
	}

	@Override
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
		if (inMixology(nowMillis))
		{
			return Collections.singletonList(new SkillCompanionView.ContextLine("Mastering Mixology: its own activity",
				SkillCompanionView.ContextLine.Tone.QUIET,
				"Mixology's pastes, potions and rewards are never counted as recipes, batches or stockpile here"));
		}
		HerbloreRecipe r = HerbloreRecipes.recipe(methods.getCurrent());
		if (chemistryCrumbledAt != Long.MIN_VALUE && nowMillis - chemistryCrumbledAt <= CHEMISTRY_WARNING_MILLIS
			&& r != null && r.isBonusEligible() && !containsAny(worn, HerbloreRecipes.BONUS_AMULETS))
		{
			return Collections.singletonList(new SkillCompanionView.ContextLine("Amulet of chemistry crumbled", SkillCompanionView.ContextLine.Tone.ALERT,
				"Your amulet of chemistry used its last charge (the game said so): no bonus doses until you wear another"));
		}
		return Collections.emptyList();
	}

	private static String fmt(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}

	@Override
	public void reset()
	{
		methods.reset();
		ledger.reset();
		buckets.clear();
		deposits.clear();
		pendingOccurrences.clear();
		lastInventory = null;
		lastBank = null;
		liveStorage = null;
		worn = Collections.emptySet();
		bankOpen = false;
		depositBoxOpen = false;
		mixologyUntil = Long.MIN_VALUE;
		alchemistCharges = null;
		lastChargeDrop = 0L;
		lastChargeDropAt = Long.MIN_VALUE;
		chargesValidated = false;
		chemistryCrumbledAt = Long.MIN_VALUE;
		xpPerUnit.clear();
		revision++;
	}

	@Override
	public void onLogout()
	{
		buckets.clear();
		lastInventory = null;
		lastBank = null;
		liveStorage = null;
		bankOpen = false;
		depositBoxOpen = false;
		mixologyUntil = Long.MIN_VALUE;
		revision++;
	}

	@Override
	public void settleAll()
	{
		settleUpTo(Integer.MAX_VALUE - 1);
	}
}
