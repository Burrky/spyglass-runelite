package com.osrstelemetry.plugin.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Travel Planner, cost-only form for preparation (canonical §14.2, Phase H):
 * for one destination tile, which evaluated transport to bring. No routing,
 * no walking, no collision: walking routes are Phase I.
 *
 * <h3>Rule</h3>
 * Eligible: READY, CHARGES_UNKNOWN, IN_BANK, ELSEWHERE (during preparation the
 * player can still fetch an IN_BANK / ELSEWHERE item: that is what preparing
 * is). Never DEPLETED, REQUIREMENTS_UNMET or UNKNOWN.
 * <ol>
 *   <li>Transports usable from anywhere (spells, tablets, teleport items) before
 *   network transports (fairy rings, spirit trees), whose access from the bank
 *   is not planned yet.</li>
 *   <li>The shortest walk from the arrival tile to the destination (Chebyshev
 *   tiles, the movement metric of an 8-directional tile grid; a different plane
 *   counts as far). Teleport durations are treated as equal: they are not
 *   measured yet.</li>
 *   <li>Ties: a better state (READY &gt; CHARGES_UNKNOWN &gt; IN_BANK &gt;
 *   ELSEWHERE), then one that consumes nothing (no tablet, no runes), then id.</li>
 * </ol>
 */
public final class TravelPlanner
{
	static final int OTHER_PLANE_TILES = 10_000;
	public static final Set<Availability> PREPARATION_ELIGIBLE =
		EnumSet.of(Availability.READY, Availability.CHARGES_UNKNOWN, Availability.IN_BANK, Availability.ELSEWHERE);

	private TravelPlanner()
	{
	}

	public static final class Choice
	{
		private final TransportAvailability chosen;
		private final int walkTiles;
		private final List<TransportAvailability> alternatives;

		Choice(TransportAvailability chosen, int walkTiles, List<TransportAvailability> alternatives)
		{
			this.chosen = chosen;
			this.walkTiles = walkTiles;
			this.alternatives = alternatives;
		}

		/** The chosen transport, or null when none is eligible. */
		public TransportAvailability getChosen()
		{
			return chosen;
		}

		/** Tiles from the arrival tile to the destination (estimate, no collision). */
		public int getWalkTiles()
		{
			return walkTiles;
		}

		/** Other eligible candidates, best first. */
		public List<TransportAvailability> getAlternatives()
		{
			return alternatives;
		}

		public boolean isNetwork()
		{
			return chosen != null && chosen.getTransport().network != null;
		}
	}

	public static int walkTiles(TransportDef.Point from, int x, int y, int plane)
	{
		if (from == null)
		{
			return OTHER_PLANE_TILES;
		}
		if (from.plane != plane)
		{
			return OTHER_PLANE_TILES;
		}
		return Math.max(Math.abs(from.x - x), Math.abs(from.y - y));
	}

	/** A fairy ring or spirit tree hop, counted as this many tiles of walking when journeys are compared. */
	static final int HOP_TILES = 10;

	/** The whole journey in tiles: the walk to a network entrance (if any), the hop, and the walk from the arrival. */
	public static int journeyTiles(TransportDef t, int x, int y, int plane)
	{
		int arrive = walkTiles(t.arrival, x, y, plane);
		return arrive >= OTHER_PLANE_TILES ? arrive : arrive + t.preWalkTiles() + (t.via != null ? HOP_TILES : 0);
	}

	public static boolean consumes(TransportDef t)
	{
		if (!TransportDef.list(t.runes).isEmpty())
		{
			return true;
		}
		for (TransportDef.ItemVariant v : TransportDef.list(t.items))
		{
			if (v.charges == TransportDef.Charges.CONSUMED)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The choice among {@code candidates} by whole-journey cost (canonical §28.11): {@code arrivalWalk} gives everything after
	 * the teleport in walking tiles (for a network journey the walk to the entrance, the hop and the walk from its arrival;
	 * otherwise the walk from the arrival), each leg this account's own usable walk, or a negative value when a leg has none
	 * (that candidate is left out). {@link Choice#getWalkTiles()} is that cost. Ties as {@link #chooseForPreparation(int, int, int, List)}.
	 */
	public static Choice chooseByJourney(java.util.function.ToIntFunction<TransportDef> arrivalWalk, List<TransportAvailability> candidates)
	{
		return chooseByJourney(arrivalWalk, candidates, false);
	}

	/**
	 * The spellbook a journey needs (its own spell, or the spell of any leg: a composed journey carries its legs'
	 * requirements), or null when it casts nothing.
	 */
	public static String spellbookOf(TransportDef t)
	{
		return t == null || t.requirements() == null ? null : t.requirements().spellbook;
	}

	/**
	 * Pass 32: whether using {@code t} needs a spellbook other than {@code currentBook} (its enum name; null: the current book
	 * is not known, so any spell journey would need checking first).
	 */
	public static boolean needsSpellbookChange(TransportDef t, String currentBook)
	{
		String b = spellbookOf(t);
		return b != null && !b.equals(currentBook);
	}

	/**
	 * Pass 32, "Use current setup": the candidates usable without changing spellbook (direct spells, tablets, items, portals
	 * and nexus destinations each judged by their own requirements). The removed ones are added to {@code left} when given.
	 */
	public static List<TransportAvailability> withoutSpellbookChange(List<TransportAvailability> candidates, String currentBook,
		List<TransportAvailability> left)
	{
		List<TransportAvailability> out = new ArrayList<>();
		for (TransportAvailability a : candidates)
		{
			if (needsSpellbookChange(a.getTransport(), currentBook))
			{
				if (left != null)
				{
					left.add(a);
				}
				continue;
			}
			out.add(a);
		}
		return out;
	}

	/** A house journey ({@code poh.*}: the way into the house, then the facility the game showed there). */
	public static boolean isHouse(TransportDef t)
	{
		return t != null && t.id != null && t.id.startsWith("poh.");
	}

	/**
	 * As above; {@code preferHouse} (the player's saved travel preference): the cheapest usable house journey wins whenever one
	 * exists (a confirmed facility, its destination, and the way into the house: a carried or banked tablet, the cape, or the
	 * spell with its spellbook and runes; every requirement as for any journey, so the preference never makes one usable). A
	 * carried item it beat (a ring of wealth, a necklace) is then only named as the other way. No usable house journey: the
	 * fastest usable journey, as always.
	 */
	public static Choice chooseByJourney(java.util.function.ToIntFunction<TransportDef> arrivalWalk, List<TransportAvailability> candidates,
		boolean preferHouse)
	{
		Map<TransportDef, Integer> cost = new java.util.IdentityHashMap<>();
		List<TransportAvailability> eligible = new ArrayList<>();
		for (TransportAvailability a : candidates)
		{
			if (!PREPARATION_ELIGIBLE.contains(a.getState()))
			{
				continue;
			}
			int w = arrivalWalk.applyAsInt(a.getTransport());
			if (w < 0)
			{
				continue;
			}
			cost.put(a.getTransport(), w);
			eligible.add(a);
		}
		Comparator<TransportAvailability> order = Comparator
			.comparingInt((TransportAvailability a) -> cost.get(a.getTransport()))
			.thenComparingInt(a -> a.getState().resourceRank())
			.thenComparingInt(a -> consumes(a.getTransport()) ? 1 : 0)
			.thenComparing(a -> a.getTransport().id);
		eligible.sort(order);
		if (eligible.isEmpty())
		{
			return new Choice(null, -1, new ArrayList<>());
		}
		// Alternatives worth naming: within twice the best journey (plus a short walk). The rest still exist; they lose.
		int bound = 2 * cost.get(eligible.get(0).getTransport()) + 50;
		if (preferHouse && !isHouse(eligible.get(0).getTransport()))
		{
			for (int i = 1; i < eligible.size(); i++)
			{
				TransportAvailability a = eligible.get(i);
				if (isHouse(a.getTransport()))
				{
					eligible.remove(i);
					eligible.add(0, a);
					break;
				}
			}
		}
		TransportAvailability best = eligible.get(0);
		List<TransportAvailability> alternatives = new ArrayList<>();
		for (TransportAvailability a : eligible.subList(1, eligible.size()))
		{
			if (cost.get(a.getTransport()) <= bound)
			{
				alternatives.add(a);
			}
		}
		return new Choice(best, cost.get(best.getTransport()), alternatives);
	}

	/** A whole journey in walking tiles: the walk to a network entrance (if any), the hop, and the walk from the arrival. */
	public static int journeyCost(TransportDef t, int arrivalWalk)
	{
		return arrivalWalk + t.preWalkTiles() + (t.via != null ? HOP_TILES : 0);
	}

	/** The cost-only choice among {@code candidates} for a destination tile. */
	public static Choice chooseForPreparation(int x, int y, int plane, List<TransportAvailability> candidates)
	{
		List<TransportAvailability> eligible = new ArrayList<>();
		for (TransportAvailability a : candidates)
		{
			if (PREPARATION_ELIGIBLE.contains(a.getState()))
			{
				eligible.add(a);
			}
		}
		Comparator<TransportAvailability> order = Comparator
			.comparingInt((TransportAvailability a) -> a.getTransport().network == null ? 0 : 1)
			.thenComparingInt(a -> journeyTiles(a.getTransport(), x, y, plane))
			.thenComparingInt(a -> a.getState().resourceRank())
			.thenComparingInt(a -> consumes(a.getTransport()) ? 1 : 0)
			.thenComparing(a -> a.getTransport().id);
		eligible.sort(order);
		if (eligible.isEmpty())
		{
			return new Choice(null, -1, new ArrayList<>());
		}
		TransportAvailability best = eligible.get(0);
		return new Choice(best, walkTiles(best.getTransport().arrival, x, y, plane), new ArrayList<>(eligible.subList(1, eligible.size())));
	}
}
