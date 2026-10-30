package com.osrstelemetry.plugin.hunter;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.farming.FarmingPreparation;
import com.osrstelemetry.plugin.farming.FarmingSites;
import com.osrstelemetry.plugin.farming.FarmingTravel;
import com.osrstelemetry.plugin.navigation.AccountRouter;
import com.osrstelemetry.plugin.prep.ItemNeed;
import com.osrstelemetry.plugin.prep.PreparationRequest;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TravelPlanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * A bird house run's preparation (the same contract as Farming's: a flat list of needs for Bank Preparation, and each stop
 * with the journey Spyglass would take). Pure; recomputed from the run, the spaces as read and the account.
 *
 * <h3>Supplies, from what each space shows</h3>
 * <ul>
 *   <li>A house to build on every space that is bare, holds a house from before (emptying it returns its clockwork) or has
 *   not been read yet (planned as a full reset; it may need less). A house only short of seeds needs none.</li>
 *   <li>Houses: bird houses of the chosen tier, or its logs (one each) — one row accepting both; whatever is carried counts.
 *   Logs need a hammer and a chisel and, for a space with no house to empty first, a clockwork (four clockworks keep four
 *   houses going: each emptied house gives its own back).</li>
 *   <li>Seeds: 10 low level or 5 high level per house (high level count as two), for every house to build or still unseeded.
 *   A partly seeded house is counted in full: how many it already holds is not sent to the client.</li>
 *   <li>Travel: the journey to the run's first stop (the others are reached on the island: on foot or by magic mushtree).</li>
 * </ul>
 * The tier is the player's choice, never picked for them; levels are checked (Hunter to place, Crafting to make from logs).
 */
public final class BirdhousePlan
{
	public static final String CONSUMER = "birdhouses";
	public static final String NEED_HOUSES = "birdhouse.houses";
	public static final String NEED_CLOCKWORK = "birdhouse.clockwork";
	public static final String NEED_HAMMER = "birdhouse.hammer";
	public static final String NEED_CHISEL = "birdhouse.chisel";
	public static final String NEED_SEEDS = "birdhouse.seeds";

	/** One stop of the run with its journey. */
	public static final class StopPlan
	{
		private final String stop;
		private final List<BirdhouseSpace> spaces;
		private final TransportAvailability transport;
		private final int walkTiles;
		private final List<FarmingTravel.Leg> legs;
		private final List<TransportAvailability> alternatives;
		/** The preparation needs the journey relies on (first stop only), and whether its charges can't be read. */
		private List<String> needKeys = Collections.emptyList();
		private boolean chargeUncertain;

		StopPlan(String stop, List<BirdhouseSpace> spaces, TransportAvailability transport, int walkTiles, List<FarmingTravel.Leg> legs,
			List<TransportAvailability> alternatives)
		{
			this.stop = stop;
			this.spaces = Collections.unmodifiableList(spaces);
			this.transport = transport;
			this.walkTiles = walkTiles;
			this.legs = Collections.unmodifiableList(legs);
			this.alternatives = Collections.unmodifiableList(alternatives);
		}

		public String getStop()
		{
			return stop;
		}

		public String getTitle()
		{
			return BirdhouseSpace.stopTitle(stop);
		}

		public List<BirdhouseSpace> getSpaces()
		{
			return spaces;
		}

		/** The journey from anywhere (a teleport), or null when none is usable. */
		public TransportAvailability getTransport()
		{
			return transport;
		}

		public int getWalkTiles()
		{
			return walkTiles;
		}

		public List<FarmingTravel.Leg> getLegs()
		{
			return legs;
		}

		public List<TransportAvailability> getAlternatives()
		{
			return alternatives;
		}

		public FarmingSites.Tile getTile()
		{
			BirdhouseSpace a = spaces.get(0);
			return new FarmingSites.Tile(a.getX(), a.getY(), a.getPlane());
		}

		public String getStopKey()
		{
			return BirdhouseSpace.stopKey(stop);
		}

		public List<String> getNeedKeys()
		{
			return needKeys;
		}

		public boolean isChargeUncertain()
		{
			return chargeUncertain;
		}
	}

	private final PreparationRequest request;
	private final List<StopPlan> stops;
	private final int housesToBuild;
	private final int housesCarried;
	private final int clockworks;
	private final int seedUnits;
	private final boolean tierNeeded;
	private final List<String> unmet;
	private final List<String> unknown;
	private final List<String> notes;

	private BirdhousePlan(PreparationRequest request, List<StopPlan> stops, int housesToBuild, int housesCarried, int clockworks, int seedUnits,
		boolean tierNeeded, List<String> unmet, List<String> unknown, List<String> notes)
	{
		this.request = request;
		this.stops = Collections.unmodifiableList(stops);
		this.housesToBuild = housesToBuild;
		this.housesCarried = housesCarried;
		this.clockworks = clockworks;
		this.seedUnits = seedUnits;
		this.tierNeeded = tierNeeded;
		this.unmet = Collections.unmodifiableList(unmet);
		this.unknown = Collections.unmodifiableList(unknown);
		this.notes = Collections.unmodifiableList(notes);
	}

	public PreparationRequest getRequest()
	{
		return request;
	}

	/** The run's stops in suggested order (the first is reached by the journey; the rest on the island). */
	public List<StopPlan> getStops()
	{
		return stops;
	}

	public int getHousesToBuild()
	{
		return housesToBuild;
	}

	/** Bird houses of the chosen tier carried now (they need no logs, clockwork or tools). */
	public int getHousesCarried()
	{
		return housesCarried;
	}

	public int getClockworks()
	{
		return clockworks;
	}

	public int getSeedUnits()
	{
		return seedUnits;
	}

	/** A house must be built and no tier is chosen yet: the player chooses before beginning. */
	public boolean isTierNeeded()
	{
		return tierNeeded;
	}

	/** Requirements known unmet ("Hunter 89 for redwood bird houses (you have 80)"). */
	public List<String> getUnmet()
	{
		return unmet;
	}

	/** Requirements not read yet. */
	public List<String> getUnknown()
	{
		return unknown;
	}

	public List<String> getNotes()
	{
		return notes;
	}

	/**
	 * Whether a run over these spaces needs a bird house tier before it starts (pass 30: chosen before Start, never inside
	 * preparation): any space that ends with a house to build — bare, emptied first, or not read yet.
	 */
	public static boolean tierRequired(java.util.Set<BirdhouseSpace> spaces, BirdhouseTraps traps, long now)
	{
		if (spaces == null || spaces.isEmpty())
		{
			return false;
		}
		BirdhouseRun r = BirdhouseRun.start(spaces, null, traps, now);
		for (BirdhouseRun.SpaceProgress p : r.progress(traps, now))
		{
			if (!p.isSettled() && needsHouse(p.getStep()))
			{
				return true;
			}
		}
		return false;
	}

	/** Houses to build: bare, from before (emptied first) or not read yet. */
	static boolean needsHouse(BirdhouseRun.Step step)
	{
		return step == BirdhouseRun.Step.BUILD || step == BirdhouseRun.Step.EMPTY || step == BirdhouseRun.Step.CHECK_FULL
			|| step == BirdhouseRun.Step.CHECK;
	}

	/** Spaces whose house must come with no house to empty there first (bare, or not read). */
	static boolean bare(BirdhouseRun.Step step)
	{
		return step == BirdhouseRun.Step.BUILD || step == BirdhouseRun.Step.CHECK;
	}

	static boolean needsSeeds(BirdhouseRun.Step step)
	{
		return needsHouse(step) || step == BirdhouseRun.Step.SEED || step == BirdhouseRun.Step.SEED_MORE;
	}

	/**
	 * @param availability every catalogue transport evaluated for this account (null or empty: no journeys)
	 * @param preferHouse  the player's travel preference (Farming settings: Prefer my house)
	 */
	public static BirdhousePlan plan(BirdhouseRun run, BirdhouseTraps traps, AccountCapability cap, List<TransportAvailability> availability,
		boolean preferHouse, long now)
	{
		return plan(run, traps, cap, availability, preferHouse, false, now);
	}

	/** As above; {@code currentSetup} (pass 32, Use current setup): no journey that needs another spellbook. */
	public static BirdhousePlan plan(BirdhouseRun run, BirdhouseTraps traps, AccountCapability cap, List<TransportAvailability> availability,
		boolean preferHouse, boolean currentSetup, long now)
	{
		int build = 0;
		int bare = 0;
		int seeds = 0;
		int early = 0;
		List<String> notes = new ArrayList<>();
		for (BirdhouseRun.SpaceProgress p : run.progress(traps, now))
		{
			if (p.isSettled())
			{
				continue;
			}
			BirdhouseRun.Step step = p.getStep();
			build += needsHouse(step) ? 1 : 0;
			bare += bare(step) ? 1 : 0;
			seeds += needsSeeds(step) ? BirdhouseSeeds.UNITS_PER_HOUSE : 0;
			if (step == BirdhouseRun.Step.CHECK)
			{
				addOnce(notes, "Spaces not read yet are planned as a full reset; you may need less.");
			}
			early += step == BirdhouseRun.Step.CHECK_FULL ? 1 : 0;
		}
		if (early > 0)
		{
			notes.add(early == 1 ? "One house was seeded before Spyglass saw it and may already be full: supplies cover rebuilding it."
				: early + " houses were seeded before Spyglass saw them and may already be full: supplies cover rebuilding them.");
		}
		BirdhouseTier tier = run.getTier();
		Map<Integer, Integer> inv = cap == null || cap.getInventory() == null ? Collections.emptyMap() : cap.getInventory();
		int carried = tier == null ? 0 : inv.getOrDefault(tier.getHouseItem(), 0);
		int fromLogs = Math.max(0, build - carried);
		int clockworks = Math.max(0, Math.min(fromLogs, bare - Math.min(carried, bare)));
		List<ItemNeed> needs = new ArrayList<>();
		List<String> unmet = new ArrayList<>();
		List<String> unknown = new ArrayList<>();
		boolean tierNeeded = build > 0 && tier == null;
		if (build > 0 && tier != null)
		{
			needs.add(new ItemNeed(NEED_HOUSES, tier.getShortTitle() + " bird houses or logs", "Supplies", new int[]{tier.getHouseItem(), tier.getLogItem()},
				build, ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_ONLY,
				"One per house to build: a " + tier.getTitle().toLowerCase(java.util.Locale.ROOT) + ", or " + tier.getLogTitle().toLowerCase(java.util.Locale.ROOT)
					+ " to make one there").withSearchText(tier.getShortTitle()));
			if (fromLogs > 0)
			{
				if (clockworks > 0)
				{
					needs.add(new ItemNeed(NEED_CLOCKWORK, "Clockwork", "Supplies", new int[]{ItemID.POH_CLOCKWORK_MECHANISM}, clockworks,
						ItemNeed.Necessity.REQUIRED, ItemNeed.SatisfiedBy.INVENTORY_ONLY,
						"For a house made where no house is emptied first (an emptied house gives its clockwork back)"));
				}
				needs.add(new ItemNeed(NEED_HAMMER, "Hammer", "Tools", new int[]{ItemID.HAMMER, ItemID.IMCANDO_HAMMER}, 0, ItemNeed.Necessity.REQUIRED,
					ItemNeed.SatisfiedBy.INVENTORY_OR_WORN, "To make a bird house from logs"));
				needs.add(new ItemNeed(NEED_CHISEL, "Chisel", "Tools", new int[]{ItemID.CHISEL}, 0, ItemNeed.Necessity.REQUIRED,
					ItemNeed.SatisfiedBy.INVENTORY_ONLY, "To make a bird house from logs"));
				level(cap, Skill.CRAFTING, tier.getCrafting(), "to make " + tier.getShortTitle().toLowerCase(java.util.Locale.ROOT) + " bird houses", unmet, unknown);
			}
			level(cap, Skill.HUNTER, tier.getHunter(), "to place " + tier.getShortTitle().toLowerCase(java.util.Locale.ROOT) + " bird houses", unmet, unknown);
		}
		if (seeds > 0)
		{
			needs.add(new ItemNeed(NEED_SEEDS, "Seeds", "Supplies", BirdhouseSeeds.ids(), seeds, ItemNeed.Necessity.REQUIRED,
				ItemNeed.SatisfiedBy.INVENTORY_ONLY, "10 a house (a high level seed counts as two: 5 fill a house); any mix of hop, herb, flower, bush"
				+ " and allotment seeds").withUnits(BirdhouseSeeds.unitsPerId(), "seed").withSearchText("seed"));
		}

		// Journeys: each stop's best journey from anywhere; only the first stop's is a supply (the rest are on the island).
		Map<String, TransportAvailability> byId = new LinkedHashMap<>();
		Map<String, List<TransportAvailability>> byHop = new LinkedHashMap<>();
		for (TransportAvailability a : availability == null ? Collections.<TransportAvailability>emptyList() : availability)
		{
			byId.put(a.getTransport().id, a);
			if (a.getTransport().via != null)
			{
				byHop.computeIfAbsent(a.getTransport().via.hop, k -> new ArrayList<>()).add(a);
			}
		}
		AccountRouter.Key key = AccountRouter.bundled().planning(cap);
		List<StopPlan> plans = new ArrayList<>();
		for (Map.Entry<String, List<BirdhouseSpace>> e : run.byStop().entrySet())
		{
			boolean work = false;
			for (BirdhouseSpace s : e.getValue())
			{
				work |= !run.progress(s, traps, now).isSettled();
			}
			if (!work)
			{
				continue;
			}
			String stopKey = BirdhouseSpace.stopKey(e.getKey());
			List<TransportAvailability> candidates = new ArrayList<>();
			for (String tid : FarmingTravel.forStopKey(stopKey))
			{
				List<TransportAvailability> options = FarmingTravel.isNetwork(tid) ? byHop.getOrDefault(tid, Collections.emptyList())
					: byId.containsKey(tid) ? Collections.singletonList(byId.get(tid)) : Collections.emptyList();
				for (TransportAvailability a : options)
				{
					if (!candidates.contains(a))
					{
						candidates.add(a);
					}
				}
			}
			if (currentSetup)
			{
				candidates = TravelPlanner.withoutSpellbookChange(candidates, cap == null || cap.spellbook() == null ? null : cap.spellbook().name(), null);
			}
			TravelPlanner.Choice choice = TravelPlanner.chooseByJourney(d -> FarmingTravel.afterTeleportToStop(stopKey, d, key), candidates, preferHouse);
			BirdhouseSpace anchor = e.getValue().get(0);
			FarmingSites.Tile tile = new FarmingSites.Tile(anchor.getX(), anchor.getY(), anchor.getPlane());
			List<FarmingTravel.Leg> legs = choice.getChosen() == null ? Collections.emptyList()
				: FarmingTravel.journeyToStop(stopKey, choice.getChosen().getTransport(), tile, key, "Walk to the bird house");
			plans.add(new StopPlan(e.getKey(), e.getValue(), choice.getChosen(), choice.getWalkTiles(), legs, choice.getAlternatives()));
		}
		plans = order(plans);
		if (!plans.isEmpty() && plans.get(0).getTransport() != null)
		{
			StopPlan first = plans.get(0);
			Map<String, List<String>> keys = FarmingPreparation.travelNeeds(Collections.singletonList(first.getTransport()), cap, needs);
			first.needKeys = Collections.unmodifiableList(new ArrayList<>(keys.getOrDefault(first.getTransport().getTransport().id,
				Collections.emptyList())));
			first.chargeUncertain = !FarmingPreparation.chargesSeen(first.getTransport(), cap);
		}
		else if (!plans.isEmpty())
		{
			notes.add("No usable journey to Fossil Island is known for this account yet.");
		}
		String title = "Bird house run";
		return new BirdhousePlan(new PreparationRequest(CONSUMER, title, needs), plans, build, carried, clockworks, seeds, tierNeeded, unmet, unknown,
			notes);
	}

	/**
	 * Suggested order: by each stop's whole journey from the island's arrival, cheapest first (route data: Verdant Valley,
	 * then the north meadow by the magic mushtree, then the walk south to the other meadow space). Stops with no usable
	 * journey last.
	 */
	static List<StopPlan> order(List<StopPlan> plans)
	{
		List<StopPlan> out = new ArrayList<>(plans);
		out.sort(java.util.Comparator.comparingInt(BirdhousePlan::cost).thenComparing(StopPlan::getStop));
		return out;
	}

	private static int cost(StopPlan p)
	{
		return p.getTransport() == null || p.getWalkTiles() < 0 ? Integer.MAX_VALUE : p.getWalkTiles();
	}

	private static void level(AccountCapability cap, Skill skill, int need, String why, List<String> unmet, List<String> unknown)
	{
		Integer have = cap == null ? null : cap.level(skill);
		if (have == null)
		{
			unknown.add(skill.getName() + " " + need + " " + why + " (level not read yet)");
		}
		else if (have < need)
		{
			unmet.add(skill.getName() + " " + need + " " + why + " (you have " + have + ")");
		}
	}

	private static void addOnce(List<String> l, String s)
	{
		if (!l.contains(s))
		{
			l.add(s);
		}
	}
}
