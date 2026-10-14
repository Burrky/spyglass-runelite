package com.osrstelemetry.plugin.activity;

import com.osrstelemetry.plugin.wintertodt.WintertodtRewards;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wintertodt round lifecycle, native HUD interpretation and corroborated
 * action counting (pure; client thread). The collector feeds it native
 * inputs only -- never RuneLite's optional Wintertodt plugin:
 * <ul>
 * <li>PHASE: in the arena (region 6462) with Jagex's status overlay
 * (interface 396) loaded, a ROUND is in progress while the respawn timer
 * varbit (WINT_TRANSMIT_RESPAWNDELAY) is 0 and the energy meter shows a
 * live value above 0; otherwise it is the INTERMISSION.</li>
 * <li>ROUND START / END: the phase changing. A round is observed from its
 * start only when Spyglass saw the intermission before it; it is COMPLETE
 * when it was also seen ending into the next intermission (Avg Round uses
 * complete rounds only). Every round's time -- partial too -- feeds the
 * round-time total.</li>
 * <li>ACTIONS: an inventory change is only counted as feeding when the
 * HUD's point counter rises by the matching amount within
 * {@link #CORROBORATION_TICKS} (root 10, kindling 25); lighting / repairing
 * come from the game's own chat lines; a healed pyromancer is a rejuvenation
 * dose used together with exactly +75 points. Chopping and fletching are
 * inventory transitions (roots up; roots down with kindling up).</li>
 * </ul>
 */
public final class WintertodtRoundTracker
{
	public static final long TICK_MILLIS = 600L;
	/** An inventory change and its point gain may land up to this many ticks apart. */
	static final int CORROBORATION_TICKS = 2;
	/** The current action shows this long after its last evidence (RuneLite's own idle timeout is 3 s). */
	static final int ACTION_TICKS = 5;
	/** A subdue line this soon after the round ended still belongs to it. */
	static final int LATE_SUBDUE_TICKS = 20;

	public static final String CHOPPED = "roots_chopped";
	public static final String FLETCHED = "kindling_fletched";
	public static final String ROOTS_FED = "roots_fed";
	public static final String KINDLING_FED = "kindling_fed";
	public static final String LIT = "braziers_lit";
	public static final String REPAIRED = "braziers_repaired";
	public static final String HEALED = "pyromancers_healed";

	private static final Pattern PERCENT = Pattern.compile("([0-9]{1,3})\\s*%");
	private static final Pattern LAST_NUMBER = Pattern.compile("([0-9][0-9,]*)(?!.*[0-9])");
	private static final Pattern CLOCK = Pattern.compile("([0-9]{1,2}):([0-5][0-9])");

	/** One finished round (or the part of it the player was there for). */
	public static final class RoundEnd
	{
		private final long millis;
		private final boolean complete;
		private final Integer points;
		private final Map<String, Integer> actions;

		RoundEnd(long millis, boolean complete, Integer points, Map<String, Integer> actions)
		{
			this.millis = millis;
			this.complete = complete;
			this.points = points;
			this.actions = actions;
		}

		public long getMillis()
		{
			return millis;
		}

		public boolean isComplete()
		{
			return complete;
		}

		public Integer getPoints()
		{
			return points;
		}

		/** Counted actions since the last flush (non-zero only), in a stable order. */
		public Map<String, Integer> getActions()
		{
			return actions;
		}
	}

	private WintertodtState.Phase phase;
	private boolean inRound;
	private int roundStartTick;
	private boolean roundObservedFromStart;
	private Instant roundStartedAt;
	private Instant roundOpenedAt;
	private Integer warmth;
	private Integer energy;
	private Integer points;
	private Integer countdown;
	private Integer lastRoundPoints;
	private Integer lastRoundEndTick;
	private boolean lastRoundScored;
	private Integer cartRewards;

	// inventory
	private int roots;
	private int kindling;
	private int doses;
	private boolean knife;
	private boolean inventoryKnown;

	// corroboration
	private int pendingRoots;
	private int pendingKindling;
	private int pendingDoses;
	private int pendingInventoryTick;
	private int pendingPoints;
	private int pendingPointsTick;
	private boolean pendingLight;
	private boolean pendingRepair;
	private int pendingChatTick;

	private WintertodtState.Action action;
	private int actionTick;
	private final Map<String, Integer> counts = new LinkedHashMap<>();

	public void reset()
	{
		phase = null;
		inRound = false;
		roundStartedAt = null;
		roundOpenedAt = null;
		warmth = null;
		energy = null;
		points = null;
		countdown = null;
		lastRoundPoints = null;
		lastRoundEndTick = null;
		lastRoundScored = false;
		cartRewards = null;
		inventoryKnown = false;
		roots = 0;
		kindling = 0;
		doses = 0;
		knife = false;
		clearPending();
		action = null;
		counts.clear();
	}

	private void clearPending()
	{
		pendingRoots = 0;
		pendingKindling = 0;
		pendingDoses = 0;
		pendingPoints = 0;
		pendingLight = false;
		pendingRepair = false;
	}

	/**
	 * One game tick of native state. Returns the round that ended on this
	 * tick, if any. {@code respawnDelay} is the raw WINT_TRANSMIT_RESPAWNDELAY
	 * varbit; {@code rewardPool} the raw WINT_REWARD_POOL varbit (diagnostic).
	 */
	public RoundEnd onTick(int tick, Instant now, boolean inArena, boolean hudLoaded, String warmthText, String energyText,
		String pointsText, int respawnDelay, int rewardPool)
	{
		if (!inArena)
		{
			return exit(tick);
		}
		warmth = hudLoaded ? percentOf(warmthText) : null;
		energy = hudLoaded ? percentOf(energyText) : null;
		Integer hudPoints = hudLoaded ? numberOf(pointsText) : null;
		countdown = countdownSeconds(respawnDelay, hudLoaded ? energyText : null);
		cartRewards = rewardPool >= 0 && rewardPool <= WintertodtRewards.CART_MAX ? rewardPool : null;

		boolean roundNow = hudLoaded && respawnDelay <= 0 && energy != null && energy > 0;
		RoundEnd ended = null;
		if (roundNow && !inRound)
		{
			// Seen from its start only if the intermission before it was seen.
			boolean observed = phase == WintertodtState.Phase.INTERMISSION;
			inRound = true;
			roundStartTick = tick;
			roundObservedFromStart = observed;
			roundStartedAt = observed ? now : null;
			roundOpenedAt = now;
			lastRoundScored = false;
			points = hudPoints;
		}
		else if (!roundNow && inRound && hudLoaded)
		{
			ended = endRound(tick, true);
		}
		if (hudLoaded)
		{
			phase = inRound ? WintertodtState.Phase.ROUND : WintertodtState.Phase.INTERMISSION;
		}
		if (inRound && hudPoints != null)
		{
			if (points != null && hudPoints > points)
			{
				pendingPoints += hudPoints - points;
				pendingPointsTick = tick;
			}
			points = hudPoints;
		}
		allocate(tick);
		expire(tick);
		return ended;
	}

	/** The player left the arena (or logged out / hopped): an open round ends here, partial. */
	public RoundEnd exit(int tick)
	{
		RoundEnd ended = inRound ? endRound(tick, false) : flushOnly();
		phase = null;
		warmth = null;
		energy = null;
		countdown = null;
		roundOpenedAt = null;
		roundStartedAt = null;
		points = null;
		action = null;
		clearPending();
		return ended;
	}

	private RoundEnd flushOnly()
	{
		Map<String, Integer> flushed = flushCounts();
		return flushed.isEmpty() ? null : new RoundEnd(0L, false, null, flushed);
	}

	private RoundEnd endRound(int tick, boolean intoIntermission)
	{
		inRound = false;
		long millis = Math.max(0L, (long) (tick - roundStartTick) * TICK_MILLIS);
		boolean complete = roundObservedFromStart && intoIntermission;
		lastRoundPoints = points;
		lastRoundEndTick = tick;
		RoundEnd end = new RoundEnd(millis, complete, points, flushCounts());
		roundStartedAt = null;
		roundOpenedAt = null;
		points = null;
		return end;
	}

	private Map<String, Integer> flushCounts()
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String key : new String[]{CHOPPED, FLETCHED, ROOTS_FED, KINDLING_FED, LIT, REPAIRED, HEALED})
		{
			Integer v = counts.get(key);
			if (v != null && v > 0)
			{
				out.put(key, v);
			}
		}
		counts.clear();
		return Collections.unmodifiableMap(out);
	}

	/**
	 * The points that a subdue line ("Your subdued Wintertodt count is: N")
	 * arriving now belongs to: the live round's, or the round that just ended.
	 * Returns null once that round was already scored, or when unknown.
	 */
	public Integer pointsForSubdue(int tick)
	{
		if (lastRoundScored)
		{
			return null;
		}
		Integer p = inRound ? points
			: lastRoundEndTick != null && tick - lastRoundEndTick >= 0 && tick - lastRoundEndTick <= LATE_SUBDUE_TICKS ? lastRoundPoints : null;
		if (p != null)
		{
			lastRoundScored = true;
		}
		return p;
	}

	/** Inventory contents now (roots, kindling, rejuvenation doses carried, a knife present). */
	public void onInventory(int tick, int rootsNow, int kindlingNow, int dosesNow, boolean knifeNow)
	{
		knife = knifeNow;
		if (!inventoryKnown)
		{
			// Baseline only: nothing before it was observed.
			inventoryKnown = true;
			roots = rootsNow;
			kindling = kindlingNow;
			doses = dosesNow;
			return;
		}
		int dRoots = rootsNow - roots;
		int dKindling = kindlingNow - kindling;
		int dDoses = dosesNow - doses;
		roots = rootsNow;
		kindling = kindlingNow;
		doses = dosesNow;
		if (phase == null)
		{
			return;
		}
		if (dKindling > 0 && dRoots < 0)
		{
			add(FLETCHED, dKindling);
			setAction(WintertodtState.Action.FLETCHING, tick);
		}
		else if (dRoots > 0 && dKindling == 0)
		{
			add(CHOPPED, dRoots);
			setAction(WintertodtState.Action.CHOPPING, tick);
		}
		else
		{
			if (dRoots < 0)
			{
				pendingRoots += -dRoots;
			}
			if (dKindling < 0)
			{
				pendingKindling += -dKindling;
			}
		}
		if (dDoses < 0)
		{
			pendingDoses += -dDoses;
		}
		if (dRoots < 0 || dKindling < 0 || dDoses < 0)
		{
			pendingInventoryTick = tick;
		}
		allocate(tick);
	}

	/** The game's own lines for lighting / fixing a brazier (RuneLite's plugin keys on the same text). */
	public void onChat(int tick, String message)
	{
		if (message == null || phase == null)
		{
			return;
		}
		if (message.startsWith("You light the brazier"))
		{
			pendingLight = true;
			pendingChatTick = tick;
		}
		else if (message.startsWith("You fix the brazier"))
		{
			pendingRepair = true;
			pendingChatTick = tick;
		}
		allocate(tick);
	}

	/** Matches point gains to their corroborating evidence; anything unmatched is never counted. */
	private void allocate(int tick)
	{
		if (pendingPoints <= 0)
		{
			return;
		}
		if (pendingLight && pendingPoints >= WintertodtRewards.LIGHT_POINTS)
		{
			pendingPoints -= WintertodtRewards.LIGHT_POINTS;
			pendingLight = false;
			add(LIT, 1);
			setAction(WintertodtState.Action.LIGHTING, tick);
		}
		if (pendingRepair && pendingPoints >= WintertodtRewards.REPAIR_POINTS)
		{
			pendingPoints -= WintertodtRewards.REPAIR_POINTS;
			pendingRepair = false;
			add(REPAIRED, 1);
			setAction(WintertodtState.Action.REPAIRING, tick);
		}
		if (pendingDoses > 0 && pendingPoints >= WintertodtRewards.HEAL_POINTS && pendingPoints % WintertodtRewards.HEAL_POINTS == 0
			&& pendingKindling == 0 && pendingRoots == 0)
		{
			int heals = Math.min(pendingDoses, pendingPoints / WintertodtRewards.HEAL_POINTS);
			pendingDoses -= heals;
			pendingPoints -= heals * WintertodtRewards.HEAL_POINTS;
			add(HEALED, heals);
			setAction(WintertodtState.Action.HEALING, tick);
		}
		if (pendingKindling > 0)
		{
			int fed = Math.min(pendingKindling, pendingPoints / WintertodtRewards.KINDLING_POINTS);
			if (fed > 0)
			{
				pendingKindling -= fed;
				pendingPoints -= fed * WintertodtRewards.KINDLING_POINTS;
				add(KINDLING_FED, fed);
				setAction(WintertodtState.Action.FEEDING_KINDLING, tick);
			}
		}
		if (pendingRoots > 0)
		{
			int fed = Math.min(pendingRoots, pendingPoints / WintertodtRewards.ROOT_POINTS);
			if (fed > 0)
			{
				pendingRoots -= fed;
				pendingPoints -= fed * WintertodtRewards.ROOT_POINTS;
				add(ROOTS_FED, fed);
				setAction(WintertodtState.Action.FEEDING_ROOTS, tick);
			}
		}
	}

	/** Uncorroborated evidence expires: a dropped root or a drunk dose is never a counted action. */
	private void expire(int tick)
	{
		if (tick - pendingInventoryTick > CORROBORATION_TICKS)
		{
			pendingRoots = 0;
			pendingKindling = 0;
			pendingDoses = 0;
		}
		if (tick - pendingPointsTick > CORROBORATION_TICKS)
		{
			pendingPoints = 0;
		}
		if (tick - pendingChatTick > CORROBORATION_TICKS)
		{
			pendingLight = false;
			pendingRepair = false;
		}
		if (action != null && tick - actionTick > ACTION_TICKS)
		{
			action = null;
		}
	}

	private void add(String key, int n)
	{
		counts.merge(key, n, Integer::sum);
	}

	private void setAction(WintertodtState.Action a, int tick)
	{
		action = a;
		actionTick = tick;
	}

	public boolean isInRound()
	{
		return inRound;
	}

	/** The live state, or null outside the arena / before the HUD was ever seen. */
	public WintertodtState state()
	{
		if (phase == null)
		{
			return null;
		}
		return new WintertodtState(phase, warmth, energy, inRound ? points : null,
			phase == WintertodtState.Phase.INTERMISSION ? countdown : null, action,
			inRound ? roundStartedAt : null, inRound ? roundOpenedAt : null, roots, kindling, knife, cartRewards,
			inRound ? counts : null);
	}

	// ---- native text interpretation (pure) -------------------------------------------

	/** "Warmth: 86%" / "Wintertodt's Energy: 45%" -> 0..100; anything else (or out of range) -> null. */
	public static Integer percentOf(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = PERCENT.matcher(text.replaceAll("<[^>]*>", ""));
		if (!m.find())
		{
			return null;
		}
		int v = Integer.parseInt(m.group(1));
		return v <= 100 ? v : null;
	}

	/** The last whole number in a text ("Points<br>1,234" -> 1234); null when none. */
	public static Integer numberOf(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = LAST_NUMBER.matcher(text.replaceAll("<[^>]*>", " "));
		if (!m.find())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Seconds to the next round: the respawn-delay varbit as RuneLite's own
	 * plugin reads it (value x 30 / 50), else a "m:ss" clock in the energy
	 * line; null when neither is present.
	 */
	public static Integer countdownSeconds(int respawnDelay, String energyText)
	{
		if (respawnDelay > 0)
		{
			return respawnDelay * 30 / 50;
		}
		if (energyText != null)
		{
			Matcher m = CLOCK.matcher(energyText.replaceAll("<[^>]*>", ""));
			if (m.find())
			{
				return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
			}
		}
		return null;
	}

	/** Diagnostics only: the counts not yet flushed. */
	Map<String, Integer> pendingCounts()
	{
		return Collections.unmodifiableMap(counts);
	}
}
