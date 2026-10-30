package com.osrstelemetry.plugin.hunter;

import com.osrstelemetry.plugin.farming.FarmingAction;
import com.osrstelemetry.plugin.farming.FarmingGuide;
import com.osrstelemetry.plugin.farming.FarmingPreparation;
import com.osrstelemetry.plugin.farming.FarmingSites;
import com.osrstelemetry.plugin.farming.FarmingTravel;
import com.osrstelemetry.plugin.farming.FarmingWording;
import com.osrstelemetry.plugin.navigation.LiveRoute;
import com.osrstelemetry.plugin.navigation.NavPath;
import com.osrstelemetry.plugin.navigation.NavPoint;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bird house run step resolution: Farming's guide rules on bird house spaces (the current objective is a pure function of
 * the spaces as read, where the player is and the stops left; no cursor). At a stop with work left: ACT on the nearest
 * unsettled space there (empty, build, seed), its space object outlined. Otherwise TRAVEL to the next stop: a live route
 * when one leads there (ladders, the magic mushtree), else the stop's journey (the teleport to Fossil Island and its legs),
 * or on foot when close. Nothing is ever done for the player.
 */
public final class BirdhouseGuide
{
	/** Within this many tiles of a stop's space (ground floor), the player is at that stop. */
	static final int HERE_TILES = 12;
	/** The stop being worked keeps the turn within this many tiles. */
	static final int STAY_TILES = 20;
	static final int WALK_TILES = 60;

	private BirdhouseGuide()
	{
	}

	/**
	 * @param run      the active run (begun)
	 * @param stops    the plan's stops with their journeys, in suggested order
	 * @param traps    the spaces as read
	 * @param posXyz   where the player is (x, y, plane; null: unknown)
	 * @param lastXyz  where the player was on the last resolution
	 * @param previous the stop the last objective was for (null: none)
	 * @param actedAt  the stop the last objective was acting at (null: none)
	 * @param live     a live route being followed (its target is a stop's title), or null
	 * @param onIsland the player is on Fossil Island's ground floor now
	 */
	public static FarmingGuide.Objective resolve(BirdhouseRun run, List<BirdhousePlan.StopPlan> stops, BirdhouseTraps traps,
		int[] posXyz, int[] lastXyz, String previous, String actedAt, LiveRoute live, boolean onIsland, boolean inHouse, long now)
	{
		if (run == null || !run.isActive() || run.isPreparing())
		{
			return FarmingGuide.Objective.of(FarmingGuide.Kind.NONE, null, null, Collections.emptyList(), null, null, null, -1, -1, FarmingAction.NONE);
		}
		List<BirdhousePlan.StopPlan> remaining = new ArrayList<>();
		for (BirdhousePlan.StopPlan s : stops)
		{
			if (hasWork(run, s, traps, now))
			{
				remaining.add(s);
			}
		}
		// Stops the plan has not caught up with yet (a skip undone, a reading changed): still work, without a journey.
		for (String stop : run.stops())
		{
			boolean listed = false;
			for (BirdhousePlan.StopPlan s : stops)
			{
				listed |= s.getStop().equals(stop);
			}
			if (!listed)
			{
				BirdhousePlan.StopPlan bare = new BirdhousePlan.StopPlan(stop, run.byStop().get(stop), null, -1, Collections.emptyList(), Collections.emptyList());
				if (hasWork(run, bare, traps, now))
				{
					remaining.add(bare);
				}
			}
		}
		if (remaining.isEmpty())
		{
			return FarmingGuide.Objective.of(FarmingGuide.Kind.COMPLETE, null, "Run complete", Collections.singletonList("Every bird house is set or skipped."),
				null, null, null, -1, -1, new FarmingAction(FarmingAction.Stage.COMPLETE, "Run complete", "Every bird house is set or skipped", null, null, -1));
		}
		BirdhousePlan.StopPlan here = null;
		for (BirdhousePlan.StopPlan s : remaining)
		{
			int d = distance(posXyz, s);
			if (d <= HERE_TILES && (here == null || d < distance(posXyz, here)))
			{
				here = s;
			}
			if (actedAt != null && s.getStop().equals(actedAt) && d <= STAY_TILES && (here == null || here == s))
			{
				here = s;
			}
		}
		if (here != null)
		{
			BirdhouseRun.SpaceProgress p = next(run, here, traps, posXyz, now);
			BirdhouseSpace sp = p.getSpace();
			return FarmingGuide.Objective.of(FarmingGuide.Kind.ACT, here.getStop(), title(here), checklist(run, here, traps, now),
				new FarmingSites.Tile(sp.getX(), sp.getY(), sp.getPlane()), null, null, -1, -1, action(p, run.getTier(), here.getSpaces().size() > 1, now));
		}
		BirdhousePlan.StopPlan next = choose(remaining, posXyz, lastXyz, previous, onIsland);
		FarmingGuide.Objective followed = follow(run, next, traps, posXyz, live, now);
		if (followed != null)
		{
			return followed;
		}
		List<String> list = new ArrayList<>();
		int walk = distance(posXyz, next);
		TransportAvailability t = next.getTransport();
		List<FarmingTravel.Leg> legs = t == null ? Collections.emptyList() : next.getLegs();
		int on = -1;
		for (int i = 0; posXyz != null && i < legs.size(); i++)
		{
			if (legs.get(i).contains(posXyz[0], posXyz[1], posXyz[2]))
			{
				on = i;
			}
		}
		FarmingSites.Tile tile = next.getTile();
		if (!onIsland && walk > WALK_TILES && on >= 0)
		{
			FarmingTravel.Leg leg = legs.get(on);
			list.add("○ " + leg.text);
			for (int i = on + 1; i < legs.size(); i++)
			{
				list.add("Then: " + leg(legs.get(i)));
			}
			list.addAll(checklist(run, next, traps, now));
			FarmingAction act = leg.transition != null ? FarmingWording.transition(leg.transition, posXyz == null ? -1
				: Math.max(Math.abs(posXyz[0] - leg.x), Math.abs(posXyz[1] - leg.y))) : walkTo(-1);
			return FarmingGuide.Objective.of(FarmingGuide.Kind.TRAVEL, next.getStop(), title(next), list, new FarmingSites.Tile(leg.x, leg.y, leg.plane), null, null,
				-1, -1, act);
		}
		FarmingAction act;
		TransportAvailability use = null;
		com.osrstelemetry.plugin.navigation.Transition via = onIsland ? viaMushtree(posXyz, next) : null;
		if (via != null)
		{
			// No live route yet: the mushtree is clearly the shorter way (pass 31; live 2026-10-29, a 124-tile walk drawn from
			// beside the Verdant Valley mushtree). To the tree, across, then the house; the route takes over once it arrives.
			list.add("○ " + via.label());
			list.add("Then: walk to the bird house");
			list.addAll(checklist(run, next, traps, now));
			int toTree = Math.max(Math.abs(posXyz[0] - NavPoint.x(via.from)), Math.abs(posXyz[1] - NavPoint.y(via.from)));
			return FarmingGuide.Objective.of(FarmingGuide.Kind.TRAVEL, next.getStop(), title(next), list,
				new FarmingSites.Tile(NavPoint.x(via.from), NavPoint.y(via.from), NavPoint.plane(via.from)), null, null, -1, -1,
				FarmingWording.transition(via, toTree));
		}
		if (walk <= WALK_TILES)
		{
			list.add("Walk there · ~" + walk + " tiles");
			act = walkTo(walk);
		}
		else if (onIsland)
		{
			list.add("Walk there on the island (the magic mushtree links the meadow and the valley)");
			act = walkTo(-1);
		}
		else if (t == null)
		{
			list.add("No journey to Fossil Island to suggest from here");
			act = new FarmingAction(FarmingAction.Stage.WALK, "Go to Fossil Island", "Go to Fossil Island", null, null, -1);
		}
		else
		{
			list.add("Use: " + FarmingPreparation.itemOrName(t.getTransport()));
			for (FarmingTravel.Leg leg : legs)
			{
				list.add("Then: " + leg(leg));
			}
			act = FarmingWording.teleport(t.getTransport(), null, inHouse);
			use = t;
		}
		list.addAll(checklist(run, next, traps, now));
		return FarmingGuide.Objective.of(FarmingGuide.Kind.TRAVEL, next.getStop(), title(next), list, tile, use, null, -1, -1, act);
	}

	private static String leg(FarmingTravel.Leg l)
	{
		return l.text;
	}

	static FarmingAction walkTo(int tiles)
	{
		return FarmingWording.walk("bird house", FarmingAction.Target.NONE, tiles);
	}

	/** The step on a live route to {@code next} (ladders, the mushtree, the walk), or null when there is none to follow. */
	static FarmingGuide.Objective follow(BirdhouseRun run, BirdhousePlan.StopPlan next, BirdhouseTraps traps, int[] pos, LiveRoute live, long now)
	{
		if (live == null || pos == null || live.getPath() == null || !next.getTitle().equals(live.getTarget()))
		{
			return null;
		}
		NavPath path = live.getPath();
		int at = live.progress(NavPoint.pack(pos[0], pos[1], pos[2]), 4);
		if (at < 0)
		{
			return null;
		}
		List<String> list = new ArrayList<>();
		int nextTransition = -1;
		for (int i = at + 1; i < path.size(); i++)
		{
			if (path.via(i) != null)
			{
				nextTransition = i;
				break;
			}
		}
		int to;
		int then = 0;
		if (nextTransition >= 0)
		{
			to = nextTransition - 1;
			list.add("○ " + path.via(nextTransition).instruction());
			for (int i = nextTransition + 1; i < path.size(); i++)
			{
				if (path.via(i) != null && then++ < 2)
				{
					list.add("Then: " + path.via(i).instruction());
				}
			}
		}
		else
		{
			to = path.size() - 1;
			list.add("○ Walk to the bird house · ~" + Math.max(0, to - at) + " tiles");
		}
		list.addAll(checklist(run, next, traps, now));
		int tile = path.tile(Math.max(at, to));
		int tilesLeft = Math.max(0, Math.max(at, to) - at);
		FarmingAction act = nextTransition >= 0 ? FarmingWording.transition(path.via(nextTransition), tilesLeft) : walkTo(tilesLeft);
		return FarmingGuide.Objective.of(FarmingGuide.Kind.TRAVEL, next.getStop(), title(next), list,
			new FarmingSites.Tile(NavPoint.x(tile), NavPoint.y(tile), NavPoint.plane(tile)), null, live, at, Math.max(at, to), act);
	}

	/** The next stop: the one already on unless the player jumped and another is clearly cheaper; else the cheapest from here. */
	static BirdhousePlan.StopPlan choose(List<BirdhousePlan.StopPlan> remaining, int[] pos, int[] last, String previous, boolean onIsland)
	{
		BirdhousePlan.StopPlan best = null;
		long bestCost = Long.MAX_VALUE;
		BirdhousePlan.StopPlan kept = null;
		for (BirdhousePlan.StopPlan s : remaining)
		{
			long c = cost(pos, s, onIsland);
			if (c < bestCost)
			{
				bestCost = c;
				best = s;
			}
			if (s.getStop().equals(previous))
			{
				kept = s;
			}
		}
		boolean jumped = pos != null && last != null && (pos[2] != last[2] || Math.max(Math.abs(pos[0] - last[0]), Math.abs(pos[1] - last[1])) > 30);
		if (kept != null && !jumped)
		{
			long k = cost(pos, kept, onIsland);
			if (k <= bestCost * 5 / 4 || k - bestCost < 20)
			{
				return kept;
			}
		}
		return best;
	}

	/**
	 * On the island: the shorter of the walk and the magic mushtree journey (both walking legs and the transfer; pass 31: a
	 * straight-line walk alone sent the player 124 tiles past the mushtree); elsewhere: the stop's journey.
	 */
	static long cost(int[] pos, BirdhousePlan.StopPlan s, boolean onIsland)
	{
		long journey = s.getTransport() == null || s.getWalkTiles() < 0 ? 10_000 : FarmingGuide.TELEPORT_COST + s.getWalkTiles();
		if (pos == null)
		{
			return journey;
		}
		long d = distance(pos, s);
		return onIsland ? Math.min(d, mushtreeCost(pos, s)) : Math.min(d, journey);
	}

	/** The magic mushtree transfers (the shared transition catalogue's network), loaded once. */
	private static volatile List<com.osrstelemetry.plugin.navigation.Transition> mushtrees;

	static List<com.osrstelemetry.plugin.navigation.Transition> mushtrees()
	{
		List<com.osrstelemetry.plugin.navigation.Transition> m = mushtrees;
		if (m == null)
		{
			m = new ArrayList<>();
			try
			{
				for (com.osrstelemetry.plugin.navigation.Transition t : com.osrstelemetry.plugin.navigation.TransitionCatalog.bundled().all())
				{
					if (t.family == com.osrstelemetry.plugin.navigation.Transition.Family.MAGIC_MUSHTREE && t.requirement.isNone())
					{
						m.add(t);
					}
				}
			}
			catch (RuntimeException ex)
			{
				m = new ArrayList<>(); // no catalogue: walking only
			}
			mushtrees = m;
		}
		return m;
	}

	/** Tiles from a packed tile to a stop's nearest space (another plane: 20 more). */
	static int distance(int packed, BirdhousePlan.StopPlan s)
	{
		return distance(new int[]{NavPoint.x(packed), NavPoint.y(packed), NavPoint.plane(packed)}, s);
	}

	/** Walk to a mushtree, the transfer, then the walk to the stop: the cheapest such journey (very large: none). */
	static long mushtreeCost(int[] pos, BirdhousePlan.StopPlan s)
	{
		com.osrstelemetry.plugin.navigation.Transition t = bestMushtree(pos, s);
		return t == null ? Long.MAX_VALUE / 4 : mushtreeCost(pos, s, t);
	}

	private static long mushtreeCost(int[] pos, BirdhousePlan.StopPlan s, com.osrstelemetry.plugin.navigation.Transition t)
	{
		int[] from = {NavPoint.x(t.from), NavPoint.y(t.from), NavPoint.plane(t.from)};
		long toTree = Math.max(Math.abs(pos[0] - from[0]), Math.abs(pos[1] - from[1])) + (pos[2] == from[2] ? 0 : 20);
		return toTree + t.cost() + distance(t.to, s);
	}

	private static com.osrstelemetry.plugin.navigation.Transition bestMushtree(int[] pos, BirdhousePlan.StopPlan s)
	{
		if (pos == null)
		{
			return null;
		}
		com.osrstelemetry.plugin.navigation.Transition best = null;
		long bestCost = Long.MAX_VALUE;
		for (com.osrstelemetry.plugin.navigation.Transition t : mushtrees())
		{
			long c = mushtreeCost(pos, s, t);
			if (c < bestCost)
			{
				bestCost = c;
				best = t;
			}
		}
		return best;
	}

	/** The mushtree transfer to take towards {@code s} when it is clearly shorter than walking; null to walk. */
	static com.osrstelemetry.plugin.navigation.Transition viaMushtree(int[] pos, BirdhousePlan.StopPlan s)
	{
		com.osrstelemetry.plugin.navigation.Transition t = bestMushtree(pos, s);
		return t != null && mushtreeCost(pos, s, t) + 8 < distance(pos, s) ? t : null;
	}

	static int distance(int[] pos, BirdhousePlan.StopPlan s)
	{
		if (pos == null)
		{
			return Integer.MAX_VALUE / 4;
		}
		int best = Integer.MAX_VALUE / 4;
		for (BirdhouseSpace sp : s.getSpaces())
		{
			best = Math.min(best, Math.max(Math.abs(pos[0] - sp.getX()), Math.abs(pos[1] - sp.getY())) + (pos[2] == sp.getPlane() ? 0 : 20));
		}
		return best;
	}

	static boolean hasWork(BirdhouseRun run, BirdhousePlan.StopPlan s, BirdhouseTraps traps, long now)
	{
		for (BirdhouseSpace sp : s.getSpaces())
		{
			if (!run.progress(sp, traps, now).isSettled())
			{
				return true;
			}
		}
		return false;
	}

	/** The unsettled space at the stop nearest the player. */
	static BirdhouseRun.SpaceProgress next(BirdhouseRun run, BirdhousePlan.StopPlan s, BirdhouseTraps traps, int[] pos, long now)
	{
		BirdhouseRun.SpaceProgress best = null;
		int bestD = Integer.MAX_VALUE;
		for (BirdhouseSpace sp : s.getSpaces())
		{
			BirdhouseRun.SpaceProgress p = run.progress(sp, traps, now);
			if (p.isSettled())
			{
				continue;
			}
			int d = pos == null ? 0 : Math.max(Math.abs(pos[0] - sp.getX()), Math.abs(pos[1] - sp.getY()));
			if (best == null || d < bestD)
			{
				best = p;
				bestD = d;
			}
		}
		return best;
	}

	static String title(BirdhousePlan.StopPlan s)
	{
		return s.getTitle() + " · Bird house" + (s.getSpaces().size() > 1 ? "s" : "");
	}

	/** One line per space at the stop: ✓ set / skipped, or ○ its next step. */
	static List<String> checklist(BirdhouseRun run, BirdhousePlan.StopPlan s, BirdhouseTraps traps, long now)
	{
		List<String> out = new ArrayList<>();
		for (BirdhouseSpace sp : s.getSpaces())
		{
			BirdhouseRun.SpaceProgress p = run.progress(sp, traps, now);
			if (p.getStep() == BirdhouseRun.Step.SKIPPED)
			{
				continue;
			}
			String text = p.getStep() == BirdhouseRun.Step.DONE ? "Set and seeded" : stepText(p, run.getTier(), now);
			out.add((p.isSettled() ? "✓ " : "○ ") + (s.getSpaces().size() > 1 ? text + " (" + sp.getShortTitle() + ")" : text));
		}
		return out;
	}

	/** The step in words, as every surface shows it. */
	public static String stepText(BirdhouseRun.SpaceProgress p, BirdhouseTier tier, long now)
	{
		switch (p.getStep())
		{
			case CHECK:
				return "Check the bird house";
			case EMPTY:
				return "Empty the bird house";
			case CHECK_FULL:
				return "Empty the bird house if it's full";
			case FILLING:
				return "Seeded already · full in " + minutes(p.getStatus().getReadyAt() - now) + " min";
			case BUILD:
				return "Build " + (tier == null ? "a bird house" : article(tier) + " " + tier.getTitle().toLowerCase(Locale.ROOT));
			case SEED:
				return "Add seeds";
			case SEED_MORE:
				return "Add the rest of the seeds";
			case DONE:
				return "Set and seeded";
			case SKIPPED:
				return "Skipped";
			default:
				return "Check the bird house";
		}
	}

	static String article(BirdhouseTier t)
	{
		return t == BirdhouseTier.OAK ? "an" : "a";
	}

	static long minutes(long millis)
	{
		return Math.max(1, (millis + 59_999) / 60_000);
	}

	/** The action at a space: its object (the space, in any of its states) is the target. */
	static FarmingAction action(BirdhouseRun.SpaceProgress p, BirdhouseTier tier, boolean several, long now)
	{
		BirdhouseSpace s = p.getSpace();
		FarmingAction.Target target = FarmingAction.Target.object(Collections.singleton(s.getObjectId()), null, null, s.getX(), s.getY(), s.getPlane(), 3);
		String where = several ? " (" + s.getShortTitle() + ")" : "";
		String label;
		String guide;
		String tip = s.getTitle();
		switch (p.getStep())
		{
			case EMPTY:
				label = "Empty bird house";
				guide = "Empty the bird house" + where + ", or Reset it to empty and rebuild in one go";
				break;
			case CHECK_FULL:
				label = "Empty bird house";
				guide = "Empty the bird house" + where + ". It was seeded before Spyglass saw it, so it may well be full; the game warns you"
					+ " before emptying one that isn't, and you can keep it";
				break;
			case BUILD:
				label = "Build bird house";
				guide = "Build " + (tier == null ? "a bird house" : article(tier) + " " + tier.getTitle().toLowerCase(Locale.ROOT)) + " on the space" + where;
				tip = s.getTitle() + (tier == null ? "" : " · made from " + tier.getLogTitle().toLowerCase(Locale.ROOT) + " and a clockwork with a hammer"
					+ " and chisel, if you carry logs");
				break;
			case SEED:
				label = "Add seeds";
				guide = "Add seeds to the bird house" + where + ": 10, or 5 high level";
				break;
			case SEED_MORE:
				label = "Add more seeds";
				guide = "Add seeds until the bird house" + where + " is full";
				break;
			default:
				label = "Check bird house";
				guide = "Look at the bird house space" + where + ": Spyglass reads it here";
		}
		return new FarmingAction(FarmingAction.Stage.ACT, label, guide, tip, target, -1);
	}
}
