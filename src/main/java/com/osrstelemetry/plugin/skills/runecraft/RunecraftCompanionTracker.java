package com.osrstelemetry.plugin.skills.runecraft;

import com.osrstelemetry.plugin.skills.MethodTracker;
import com.osrstelemetry.plugin.skills.ProductionLedger;
import com.osrstelemetry.plugin.skills.SkillBanking;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.SkillCycleTracker;
import com.osrstelemetry.plugin.skills.SkillTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.runelite.api.gameval.ItemID;

/**
 * The Runecraft companion's brain (pure; client thread only), on the SAME
 * chassis: ProductionLedger (with consumed inputs), MethodTracker,
 * SkillBanking and the generic cycle tracker.
 *
 * <h3>A craft is causal</h3>
 * Per game tick: Runecraft XP, other skills' XP and the NET change of every
 * inventory / bank item. A tick settles once the ticks either side are
 * closed. A CRAFT is Runecraft XP together with runes gained on the SAME
 * tick: the runes gained are PRODUCED (exact, from
 * the inventory -- multipliers, Raiments, Scar extracts and failed binds are
 * simply what arrived), and every input that left the inventory on that same
 * tick is CONSUMED by exact identity (essence; catalysts such as the
 * elemental rune of a combination rune, extracts, splinters, talismans).
 * Nothing is produced or consumed while the bank or a deposit box is open,
 * nothing inside Guardians of the Rift's region (a structured activity of its
 * own), and a gain without Runecraft XP (a withdrawal, a pick-up) is never
 * production. Essence moving into or out of a pouch is never consumption: no
 * runes arrive with it.
 *
 * <h3>Method</h3>
 * From the craft itself: several rune types at once = Ourania; Blood / Soul
 * from dark essence fragments = the Arceuus loop; otherwise the rune names
 * its altar (or combination / sunfire / aether transformation). Confirmed by
 * the shared MethodTracker: one stray craft never replaces an established
 * method. Abyss travel is ROUTE context of the trip, never a method.
 *
 * <h3>Trips</h3>
 * A TRIP is bank -> craft(s) -> bank: it starts when the bank closes with
 * essence carried (loose or in pouches) and COUNTS when the bank opens again
 * after crafting that trip's method; its runes and essence are added to the
 * per-trip yields only then. A run joined mid-way (no observed start), a trip
 * that crafted several different methods, or a bank visit with no craft
 * never counts. The Arceuus loop has no bank trips: its CURRENT PHASE is
 * shown instead (mining dense essence, venerating, chiselling, binding), from
 * the items each step actually changes.
 *
 * <h3>Pouches</h3>
 * Exact contents come from the game's own pouch varbits; the current
 * capacity from its degradation state (RunecraftPouch). A pouch whose
 * degradation lowered what it holds is a warning ("Large pouch degraded --
 * holds 5 of 9"); no uses-left countdown is ever claimed.
 *
 * <h3>Binds</h3>
 * For a binding method (combination runes, aether) every craft whose output
 * cannot exceed one rune per essence proves its own outcome: essence used =
 * attempts, runes arrived = binds that held. Recorded as occurrences, never
 * as a theoretical success rate.
 */
public final class RunecraftCompanionTracker implements SkillTracker
{
	static final int WINDOW_TICKS = 1;
	static final String MINING = "MINING";
	static final String CRAFTING = "CRAFTING";

	/** The Arceuus loop's steps, from the items each one changes. */
	public enum Phase
	{
		MINING("Mining dense essence"),
		VENERATING("Venerating"),
		CHISELLING("Chiselling"),
		BINDING("Binding");

		private final String label;

		Phase(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private static final class Bucket
	{
		long xp;
		final Map<String, Long> otherXp = new HashMap<>();
		final Map<Integer, Long> invNet = new HashMap<>();
		final Map<Integer, Long> bankNet = new HashMap<>();
		boolean bankOpen;
		boolean depositBoxOpen;
		boolean excluded;
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
	private final SkillCycleTracker cycles = new SkillCycleTracker();
	private final TreeMap<Integer, Bucket> buckets = new TreeMap<>();
	private final List<Deposit> deposits = new ArrayList<>();
	private final Map<RunecraftPouch, Integer> pouchAmount = new EnumMap<>(RunecraftPouch.class);
	private final Map<RunecraftPouch, Integer> pouchDegradation = new EnumMap<>(RunecraftPouch.class);
	private final Map<String, Long> pendingOccurrences = new LinkedHashMap<>();
	private final Map<String, Long> contextEvidence = new LinkedHashMap<>();

	private Map<Integer, Long> lastInventory;
	private Map<Integer, Long> lastBank;
	private boolean bankOpen;
	private boolean depositBoxOpen;
	private int region = -1;
	private int runecraftLevel = -1;
	private boolean bindingNecklace;
	private long lastStep;
	/** The current trip: started when the bank closed with essence carried; -1 = none observed. */
	private long tripStartedAt = -1L;
	private String tripMethod;
	private boolean tripMixed;
	private boolean tripViaAbyss;
	private long tripRunes;
	private long tripEssence;
	private Phase phase;
	private long phaseAt = -1L;
	private long revision;

	// ---- inputs ---------------------------------------------------------------------------

	@Override
	public void onSkillXp(int tick, long delta, long nowMillis)
	{
		if (delta > 0)
		{
			Bucket b = bucket(tick, nowMillis);
			b.xp += delta;
			b.excluded |= region == RunecraftCompanionDefinition.GOTR_REGION;
		}
	}

	@Override
	public void onOtherSkillXp(String skillName, int tick, long delta, long nowMillis)
	{
		if (delta > 0 && skillName != null)
		{
			bucket(tick, nowMillis).otherXp.merge(skillName.toUpperCase(Locale.ROOT), delta, Long::sum);
		}
	}

	@Override
	public void onChat(int tick, long nowMillis, String message)
	{
	}

	/** A pouch's exact contents (its native varbit). */
	public void setPouchAmount(RunecraftPouch pouch, int amount)
	{
		Integer before = pouchAmount.put(pouch, Math.max(0, amount));
		if (!Objects.equals(before, pouchAmount.get(pouch)))
		{
			revision++;
		}
	}

	/** A pouch's degradation state (its native varp / varbit). */
	public void setPouchDegradation(RunecraftPouch pouch, int value)
	{
		Integer before = pouchDegradation.put(pouch, Math.max(0, value));
		if (!Objects.equals(before, pouchDegradation.get(pouch)))
		{
			revision++;
		}
	}

	/** The real (unboosted) Runecraft level: the colossal pouch's capacity scales with it. */
	public void setRunecraftLevel(int level)
	{
		if (level != runecraftLevel)
		{
			runecraftLevel = level;
			revision++;
		}
	}

	/** Whether a binding necklace is worn (combination / aether binds cannot fail while it is). */
	public void setBindingNecklace(boolean worn)
	{
		if (worn != bindingNecklace)
		{
			bindingNecklace = worn;
			revision++;
		}
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
		b.excluded |= region == RunecraftCompanionDefinition.GOTR_REGION;
		lastInventory = new HashMap<>(items);
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
		if (open == bankOpen)
		{
			return;
		}
		bankOpen = open;
		bankVisit(open);
	}

	@Override
	public void setDepositBoxOpen(boolean open)
	{
		depositBoxOpen = open;
	}

	/** Bank open: the trip that crafted ends (and counts); bank closed with essence carried: a new trip starts. */
	private void bankVisit(boolean opened)
	{
		// Anything already observed belongs to the trip that is ending.
		settleUpTo(Integer.MAX_VALUE - 1);
		if (opened)
		{
			if (tripStartedAt >= 0 && tripMethod != null && !tripMixed && tripRunes > 0)
			{
				cycles.start(tripMethod, tripStartedAt, 0, null);
				if (cycles.complete(tripMethod, lastStep) != null)
				{
					pendingOccurrences.merge(RunecraftCompanionDefinition.TRIP_RUNES + tripMethod, tripRunes, Long::sum);
					pendingOccurrences.merge(RunecraftCompanionDefinition.TRIP_ESSENCE + tripMethod, tripEssence, Long::sum);
				}
			}
			clearTrip();
			revision++;
			return;
		}
		if (essenceCarried() > 0 && region != RunecraftCompanionDefinition.GOTR_REGION)
		{
			clearTrip();
			tripStartedAt = lastStep;
			revision++;
		}
	}

	private void clearTrip()
	{
		tripStartedAt = -1L;
		tripMethod = null;
		tripMixed = false;
		tripViaAbyss = false;
		tripRunes = 0L;
		tripEssence = 0L;
	}

	@Override
	public void onTick(int tick, long nowMillis, int regionId)
	{
		lastStep = nowMillis;
		region = regionId;
		if (regionId == RunecraftCompanionDefinition.ABYSS_REGION && tripStartedAt >= 0 && !tripViaAbyss)
		{
			tripViaAbyss = true;
			revision++;
		}
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
			if (SkillBanking.settleBankingTick(b.invNet, b.bankNet, b.depositBoxOpen, ledger, null, i -> false,
				(item, qty) -> deposits.add(new Deposit(item, qty))))
			{
				revision++;
			}
			return;
		}
		long miningNear = 0L;
		long craftingNear = 0L;
		boolean excluded = b.excluded;
		for (int t = tick - WINDOW_TICKS; t <= tick + WINDOW_TICKS; t++)
		{
			Bucket n = buckets.get(t);
			if (n != null)
			{
				miningNear += n.otherXp.getOrDefault(MINING, 0L);
				craftingNear += n.otherXp.getOrDefault(CRAFTING, 0L);
				excluded |= n.excluded;
			}
		}
		if (excluded)
		{
			return;
		}

		Map<Integer, Long> runesGained = new LinkedHashMap<>();
		Map<Integer, Long> inputsUsed = new LinkedHashMap<>();
		for (Map.Entry<Integer, Long> d : b.invNet.entrySet())
		{
			int item = d.getKey();
			long delta = d.getValue();
			if (delta > 0 && RunecraftCompanionDefinition.isRune(item))
			{
				runesGained.put(item, delta);
			}
			else if (delta < 0)
			{
				if (RunecraftCompanionDefinition.isInput(item))
				{
					inputsUsed.put(item, -delta);
				}
				ledger.inventoryRemoved(item, -delta);
			}
		}

		// Runes and the Runecraft XP of a craft land on the SAME tick: an adjacent tick's XP never makes a
		// withdrawal or a pick-up next to a craft into production.
		if (b.xp <= 0 || runesGained.isEmpty())
		{
			arceuusStep(b, miningNear, craftingNear);
			return;
		}

		// A craft: runes produced, inputs consumed (essence and catalysts by exact identity).
		long runes = 0L;
		for (Map.Entry<Integer, Long> r : runesGained.entrySet())
		{
			ledger.produced(r.getKey(), r.getValue(), ProductionLedger.Destination.INVENTORY);
			runes += r.getValue();
		}
		long essence = 0L;
		for (Map.Entry<Integer, Long> u : inputsUsed.entrySet())
		{
			ledger.consumed(u.getKey(), u.getValue());
			if (RunecraftCompanionDefinition.isEssence(u.getKey()))
			{
				essence += u.getValue();
			}
		}
		RunecraftMethod method = methodOf(runesGained, inputsUsed.containsKey(ItemID.BIGBLANKRUNE));
		String before = methods.getCurrent();
		methods.success(method == null ? null : method.getKey(), b.atMillis);
		if (method != null && method.isBinding() && essence > 0 && runes <= essence && runesGained.size() == 1)
		{
			pendingOccurrences.merge(RunecraftCompanionDefinition.BIND_ATTEMPTS + method.getKey(), essence, Long::sum);
			pendingOccurrences.merge(RunecraftCompanionDefinition.BIND_SUCCESSES + method.getKey(), runes, Long::sum);
		}
		if (method != null && method.isArceuus())
		{
			phase(Phase.BINDING, b.atMillis);
		}
		if (tripStartedAt >= 0 && method != null)
		{
			if (tripMethod == null)
			{
				tripMethod = method.getKey();
			}
			else if (!tripMethod.equals(method.getKey()))
			{
				// An Ourania trip may hold a single-type craft; anything else mixing methods never counts.
				tripMixed |= !RunecraftMethod.OURANIA.getKey().equals(tripMethod);
			}
			tripRunes += runes;
			tripEssence += essence;
		}
		if (!Objects.equals(before, methods.getCurrent()))
		{
			revision++;
		}
		revision++;
	}

	/**
	 * The Arceuus loop's steps between binds, from the items each changes:
	 * dense essence blocks gained with Mining XP (mining), dense blocks turned
	 * into dark blocks (venerating), dark blocks turned into fragments with
	 * Crafting XP (chiselling). Mining / Crafting XP of these steps belongs to
	 * the Runecraft activity (context evidence for the session engine).
	 */
	private void arceuusStep(Bucket b, long miningNear, long craftingNear)
	{
		long dense = b.invNet.getOrDefault(ItemID.ARCEUUS_ESSENCE_BLOCK, 0L);
		long dark = b.invNet.getOrDefault(ItemID.ARCEUUS_ESSENCE_BLOCK_DARK, 0L);
		long fragments = b.invNet.getOrDefault(ItemID.BIGBLANKRUNE, 0L);
		if (dense > 0 && miningNear > 0)
		{
			phase(Phase.MINING, b.atMillis);
			contextEvidence.put(MINING, b.atMillis);
			contextEvidence.put(CRAFTING, b.atMillis);
		}
		else if (dark > 0 && dense < 0)
		{
			phase(Phase.VENERATING, b.atMillis);
		}
		else if (fragments > 0 && dark < 0 && craftingNear > 0)
		{
			phase(Phase.CHISELLING, b.atMillis);
			contextEvidence.put(CRAFTING, b.atMillis);
		}
	}

	private void phase(Phase p, long atMillis)
	{
		if (phase != p)
		{
			revision++;
		}
		phase = p;
		phaseAt = atMillis;
	}

	/** The method a craft's own output (and input) names. */
	static RunecraftMethod methodOf(Map<Integer, Long> runesGained, boolean fragments)
	{
		if (runesGained.size() > 1)
		{
			return RunecraftMethod.OURANIA;
		}
		int rune = runesGained.keySet().iterator().next();
		if (fragments && rune == ItemID.BLOODRUNE)
		{
			return RunecraftMethod.BLOOD_ARCEUUS;
		}
		if (fragments && rune == ItemID.SOULRUNE)
		{
			return RunecraftMethod.SOUL_ARCEUUS;
		}
		return RunecraftMethod.standardFor(rune);
	}

	// ---- outputs ---------------------------------------------------------------------------

	private boolean carries(RunecraftPouch p)
	{
		return lastInventory != null && (lastInventory.containsKey(p.getItemId())
			|| (p.getDegradedItemId() > 0 && lastInventory.containsKey(p.getDegradedItemId())));
	}

	long looseEssence()
	{
		long n = 0L;
		if (lastInventory != null)
		{
			for (Map.Entry<Integer, Long> e : lastInventory.entrySet())
			{
				if (RunecraftCompanionDefinition.isEssence(e.getKey()) && e.getKey() != ItemID.BIGBLANKRUNE)
				{
					n += e.getValue();
				}
			}
		}
		return n;
	}

	long pouchedEssence()
	{
		long n = 0L;
		for (RunecraftPouch p : RunecraftPouch.values())
		{
			if (carries(p))
			{
				n += pouchAmount.getOrDefault(p, 0);
			}
		}
		return n;
	}

	/** Usable essence now: loose in the inventory plus what carried pouches hold (exact, native). */
	long essenceCarried()
	{
		return looseEssence() + pouchedEssence();
	}

	int carriedPouches()
	{
		int n = 0;
		for (RunecraftPouch p : RunecraftPouch.values())
		{
			n += carries(p) ? 1 : 0;
		}
		return n;
	}

	int loadedPouches()
	{
		int n = 0;
		for (RunecraftPouch p : RunecraftPouch.values())
		{
			n += carries(p) && pouchAmount.getOrDefault(p, 0) > 0 ? 1 : 0;
		}
		return n;
	}

	public Phase getPhase()
	{
		return phase;
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
		return cycles;
	}

	@Override
	public String getMethodKey()
	{
		return methods.getCurrent();
	}

	@Override
	public long getRevision()
	{
		return revision + cycles.getRevision();
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

	/** Runes are stackable: there is no slot-based trip estimate. */
	@Override
	public SkillCompanionView.TripView trip(long nowMillis)
	{
		return null;
	}

	/**
	 * CURRENT TRIP (altar methods): usable essence and pouches, the trip's time
	 * when its start was observed, route and pouch / bind warnings. CURRENT
	 * PHASE for the Arceuus loop instead. Null before any method is known or
	 * inside Guardians of the Rift.
	 */
	@Override
	public SkillCompanionView.EventView event(long nowMillis)
	{
		RunecraftMethod m = RunecraftMethod.fromKey(methods.getCurrent());
		if (m == null || lastInventory == null || region == RunecraftCompanionDefinition.GOTR_REGION)
		{
			return null;
		}
		List<SkillCompanionView.EventView.Field> fields = new ArrayList<>();
		List<SkillCompanionView.ContextLine> lines = new ArrayList<>();
		if (m.isArceuus())
		{
			fields.add(new SkillCompanionView.EventView.Field("FRAGMENTS", fmt(lastInventory.getOrDefault(ItemID.BIGBLANKRUNE, 0L)),
				"Dark essence fragments you carry (the input of the next bind)"));
			fields.add(new SkillCompanionView.EventView.Field("DARK BLOCKS", fmt(lastInventory.getOrDefault(ItemID.ARCEUUS_ESSENCE_BLOCK_DARK, 0L)),
				"Dark essence blocks still to chisel"));
			if (lastInventory.getOrDefault(ItemID.ARCEUUS_ESSENCE_BLOCK, 0L) > 0)
			{
				lines.add(new SkillCompanionView.ContextLine(fmt(lastInventory.get(ItemID.ARCEUUS_ESSENCE_BLOCK)) + " dense blocks to venerate",
					SkillCompanionView.ContextLine.Tone.QUIET, null));
			}
			String name = phase == null ? null : phase.getLabel();
			return new SkillCompanionView.EventView("arceuus", name, phase == null ? 0L : Math.max(0L, nowMillis - phaseAt), fields, lines)
				.withTitle("Current phase");
		}
		long essence = essenceCarried();
		fields.add(new SkillCompanionView.EventView.Field("ESSENCE", fmt(essence),
			"Essence you can still craft this trip: loose in your inventory plus what your pouches hold (the game's own pouch state)"));
		int carried = carriedPouches();
		if (carried > 0)
		{
			fields.add(new SkillCompanionView.EventView.Field("POUCHES", loadedPouches() + " loaded",
				"Pouches holding essence right now, of the " + carried + " you carry"));
		}
		for (RunecraftPouch p : RunecraftPouch.values())
		{
			if (carries(p) && p.getDegradationVar() > 0)
			{
				int deg = pouchDegradation.getOrDefault(p, 0);
				int now = p.capacity(deg, runecraftLevel);
				int full = p.capacity(0, runecraftLevel);
				if (now < full)
				{
					lines.add(new SkillCompanionView.ContextLine(p.getLabel() + " degraded (holds " + now + " of " + full + ")",
						SkillCompanionView.ContextLine.Tone.ALERT, "Repair it (e.g. the Dark Mage via NPC Contact) to hold its full amount"));
				}
			}
		}
		if (m.isBinding() && !bindingNecklace)
		{
			lines.add(new SkillCompanionView.ContextLine("No binding necklace: binds can fail", SkillCompanionView.ContextLine.Tone.ALERT,
				"Without a binding necklace about half of these binds fail and their inputs are lost"));
		}
		if (tripViaAbyss)
		{
			lines.add(new SkillCompanionView.ContextLine("Route: Abyss", SkillCompanionView.ContextLine.Tone.QUIET,
				"This trip went through the Abyss (route only: the method is what you craft)"));
		}
		String name = tripStartedAt >= 0 ? "Since bank" : null;
		return new SkillCompanionView.EventView("trip", name, tripStartedAt >= 0 ? Math.max(0L, nowMillis - tripStartedAt) : 0L, fields, lines);
	}

	@Override
	public List<SkillCompanionView.ContextLine> context(long nowMillis)
	{
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
		cycles.reset();
		buckets.clear();
		deposits.clear();
		pouchAmount.clear();
		pouchDegradation.clear();
		pendingOccurrences.clear();
		contextEvidence.clear();
		lastInventory = null;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		region = -1;
		runecraftLevel = -1;
		bindingNecklace = false;
		clearTrip();
		phase = null;
		phaseAt = -1L;
		revision++;
	}

	@Override
	public void onLogout()
	{
		buckets.clear();
		lastInventory = null;
		lastBank = null;
		bankOpen = false;
		depositBoxOpen = false;
		contextEvidence.clear();
		clearTrip();
		cycles.abandon();
		phase = null;
		phaseAt = -1L;
		revision++;
	}

	@Override
	public void settleAll()
	{
		settleUpTo(Integer.MAX_VALUE - 1);
	}
}
