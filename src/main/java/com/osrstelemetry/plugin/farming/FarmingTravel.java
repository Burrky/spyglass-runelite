package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.account.Known;
import com.osrstelemetry.plugin.navigation.NavAccess;
import com.osrstelemetry.plugin.navigation.NavPoint;
import com.osrstelemetry.plugin.navigation.RouteTable;
import com.osrstelemetry.plugin.navigation.Transition;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Farming supplies to the generic transport core (canonical Phase F, §28.10): which transports reach each stop, the
 * whole walk from where each one lands to the patch, and its domain evidence.
 *
 * <h3>Destinations</h3>
 * Data, not a curated list: every catalogue transport whose arrival has a walk to the stop in the generated route data
 * ({@link RouteTable}, searched over the world collision map with doors, stairs, ladders, shortcuts, boats and quetzals).
 * A stop's candidates are ordered by that walk; a missing pair means no walk exists. Which one is used depends on the
 * account: each walk lists the transitions on it, and a walk is usable only when its every transition's requirement is
 * met ({@link NavAccess}); otherwise the next variant (no skill-gated shortcuts, then no gated transitions) is used.
 *
 * <h3>Evidence: player-grown spirit trees</h3>
 * {@code farming.spirit_tree.<site>} answers from patch knowledge only, never
 * a timer or projection: GROWN when last seen → YES; EMPTY, WEEDS, DEAD or
 * DISEASED when last seen → NO; still GROWING, CHECK_HEALTH, a stale decode or
 * never observed → UNKNOWN. The tree only changes through observation; time
 * alone changes nothing (D-F3).
 */
public final class FarmingTravel
{
	public static final String EVIDENCE_PREFIX = "farming.spirit_tree.";

	/** One step of a journey: what to do, where, and where the player is while on it. */
	public static final class Leg
	{
		public final String text;
		public final int x;
		public final int y;
		public final int plane;
		/** The player is on this leg while within this many tiles of (areaX, areaY) on the plane (a later leg wins). */
		final int areaX;
		final int areaY;
		final int areaRadius;
		/** The transition this step is, or null (a walk). */
		public final Transition transition;

		Leg(String text, int x, int y, int plane, int areaX, int areaY, int areaRadius)
		{
			this(text, x, y, plane, areaX, areaY, areaRadius, null);
		}

		Leg(String text, int x, int y, int plane, int areaX, int areaY, int areaRadius, Transition transition)
		{
			this.text = text;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.areaX = areaX;
			this.areaY = areaY;
			this.areaRadius = areaRadius;
			this.transition = transition;
		}

		static Leg of(Transition t)
		{
			return new Leg(t.instruction(), NavPoint.x(t.from), NavPoint.y(t.from), NavPoint.plane(t.from), NavPoint.x(t.from), NavPoint.y(t.from), 6, t);
		}

		public boolean contains(int px, int py, int pplane)
		{
			return pplane == plane && Math.max(Math.abs(px - areaX), Math.abs(py - areaY)) <= areaRadius;
		}

		@Override
		public String toString()
		{
			return text + " @" + x + "," + y + "," + plane;
		}
	}

	private FarmingTravel()
	{
	}

	static RouteTable routes()
	{
		return RouteTable.bundled();
	}

	/** The route data key of a patch's stop. */
	private static final java.util.concurrent.ConcurrentHashMap<String, String> STOP_KEYS = new java.util.concurrent.ConcurrentHashMap<>();

	public static String stopKey(FarmingPatchCatalog catalog, String patchId)
	{
		return STOP_KEYS.computeIfAbsent(patchId, id -> "stop:" + FarmingSites.stopOf(catalog, id));
	}

	/** Where a transport lands, as a route data start: a journey through a fairy ring or spirit tree lands where its hop does. */
	public static String arrivalKey(TransportDef t)
	{
		return "arr:" + (t.via != null ? t.via.hop : t.id);
	}

	/**
	 * Every transport whose arrival has a walk to the patch's stop, cheapest walk first (then id). A fairy ring or spirit tree
	 * destination is listed by its hop id: preparation turns it into complete journeys. Empty only when no catalogue
	 * transport's arrival can walk to the stop at all.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String, List<String>> FOR_STOP = new java.util.concurrent.ConcurrentHashMap<>();

	public static List<String> forStop(FarmingPatchCatalog catalog, String patchId)
	{
		return forStopKey(stopKey(catalog, patchId));
	}

	/** As {@link #forStop} for any route destination by its key ("stop:…", e.g. a bird house stop). */
	public static List<String> forStopKey(String key)
	{
		return FOR_STOP.computeIfAbsent(key, k -> Collections.unmodifiableList(computeForStop(k)));
	}

	private static List<String> computeForStop(String key)
	{
		RouteTable r = routes();
		List<String> starts = r.startsFor(key);
		List<String> out = new ArrayList<>();
		Map<String, Integer> cost = new LinkedHashMap<>();
		for (String s : starts)
		{
			String id = s.substring(4);
			out.add(id);
			cost.put(id, r.variants(key, s).get(0).getCost());
		}
		out.sort(Comparator.comparingInt((String id) -> cost.get(id)).thenComparing(id -> id));
		return out;
	}

	/** Patch id → its stop's transports (developer diagnostics). */
	public static Map<String, List<String>> destinations()
	{
		Map<String, List<String>> out = new LinkedHashMap<>();
		FarmingPatchCatalog catalog = FarmingPatchCatalog.standard();
		for (FarmingPatchDef p : catalog.allPatches())
		{
			out.put(p.getPatchId(), forStop(catalog, p.getPatchId()));
		}
		return out;
	}

	/** A fairy ring or spirit tree destination: recommended only as part of a complete journey (an entrance reached first). */
	public static boolean isNetwork(String transportId)
	{
		return transportId.startsWith("fairy_ring.") || transportId.startsWith("spirit_tree.");
	}

	static com.osrstelemetry.plugin.navigation.AccountRouter router()
	{
		return com.osrstelemetry.plugin.navigation.AccountRouter.bundled();
	}

	/**
	 * The account's walk from where {@code t} lands to the patch's stop (canonical §28.11): the cheapest walk whose every step
	 * this account can use, with what is known about it (FOUND, UNREACHABLE, BUDGET_EXHAUSTED, DATA_UNAVAILABLE).
	 */
	public static com.osrstelemetry.plugin.navigation.AccountRouter.Answer route(FarmingPatchCatalog catalog, String patchId, TransportDef t,
		com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		return routeToStop(stopKey(catalog, patchId), t, key);
	}

	/** As {@link #route} for any route destination by its key. */
	public static com.osrstelemetry.plugin.navigation.AccountRouter.Answer routeToStop(String stopKey, TransportDef t,
		com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		return router().walk(stopKey, arrivalKey(t), key);
	}

	/** The account's walk (FOUND) from where {@code t} lands to the destination with this key, or null. */
	public static RouteTable.Walk walkToStop(String stopKey, TransportDef t, com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		com.osrstelemetry.plugin.navigation.AccountRouter.Answer a = routeToStop(stopKey, t, key);
		return a.getOutcome() == com.osrstelemetry.plugin.navigation.NavOutcome.FOUND ? a.getWalk() : null;
	}

	/** The account's walk (FOUND) from where {@code t} lands to the stop, or null. */
	public static RouteTable.Walk walk(FarmingPatchCatalog catalog, String patchId, TransportDef t, com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		com.osrstelemetry.plugin.navigation.AccountRouter.Answer a = route(catalog, patchId, t, key);
		return a.getOutcome() == com.osrstelemetry.plugin.navigation.NavOutcome.FOUND ? a.getWalk() : null;
	}

	/** As above for an access without a snapshot to key it by (computes its fingerprint: tests, one-off questions). */
	public static RouteTable.Walk walk(FarmingPatchCatalog catalog, String patchId, TransportDef t, NavAccess access)
	{
		return walk(catalog, patchId, t, router().key(access, null, "adhoc"));
	}

	/** The account's walk from the entry teleport to a journey's network entrance (FOUND), or null; null for a plain transport. */
	public static RouteTable.Walk entryWalk(TransportDef t, com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		if (t.walkTo == null)
		{
			return null;
		}
		com.osrstelemetry.plugin.navigation.AccountRouter.Answer a = router().walk(t.walkTo, t.walkFrom, key);
		return a.getOutcome() == com.osrstelemetry.plugin.navigation.NavOutcome.FOUND ? a.getWalk() : null;
	}

	/**
	 * Everything after the teleport, in walking tiles (transitions two tiles a tick): the walk to the entrance and the hop for
	 * a network journey, and the walk from the arrival to the stop. -1 when any leg has no usable walk for this account.
	 */
	public static int afterTeleport(FarmingPatchCatalog catalog, String patchId, TransportDef t, com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		return afterTeleportToStop(stopKey(catalog, patchId), t, key);
	}

	/** As {@link #afterTeleport} for any route destination by its key. */
	public static int afterTeleportToStop(String stopKey, TransportDef t, com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		RouteTable.Walk arrive = walkToStop(stopKey, t, key);
		if (arrive == null)
		{
			return -1;
		}
		if (t.walkTo == null)
		{
			return arrive.getCost();
		}
		RouteTable.Walk entry = entryWalk(t, key);
		return entry == null ? -1 : entry.getCost() + FarmingGuide.HOP_COST + arrive.getCost();
	}

	/** Whether any walk exists from where {@code t} lands to the stop (with any transitions); false only when proven none. */
	public static boolean walkExists(FarmingPatchCatalog catalog, String patchId, TransportDef t)
	{
		return routes().existence(stopKey(catalog, patchId), arrivalKey(t)) != com.osrstelemetry.plugin.navigation.NavOutcome.UNREACHABLE;
	}

	/**
	 * Whether the journey after the teleport is open to this account, planned from the bank: the account has a usable walk from
	 * the arrival (and to the entrance, for a network journey). Unknown stays closed.
	 */
	public static boolean routeUsable(FarmingPatchCatalog catalog, String patchId, TransportDef t, AccountCapability cap)
	{
		return cap != null && afterTeleport(catalog, patchId, t, router().planning(cap)) >= 0;
	}

	/** What the cheapest walk from {@code t}'s arrival needs ("66 Agility"), or null when it needs nothing (or none exists). */
	public static String routeLevel(FarmingPatchCatalog catalog, String patchId, TransportDef t)
	{
		List<RouteTable.Walk> v = routes().variants(stopKey(catalog, patchId), arrivalKey(t));
		if (v.isEmpty() || v.get(0).getRequirement().isNone())
		{
			return null;
		}
		return v.get(0).getRequirement().toString();
	}

	/** The steps of the cheapest walk (any transitions) from where transport {@code transportId} lands (its transitions). */
	public static List<Leg> legs(FarmingPatchCatalog catalog, String patchId, String transportId)
	{
		List<RouteTable.Walk> v = routes().variants(stopKey(catalog, patchId), "arr:" + transportId);
		List<Leg> out = new ArrayList<>();
		if (!v.isEmpty())
		{
			for (Transition t : v.get(0).getTransitions())
			{
				out.add(Leg.of(t));
			}
		}
		return out;
	}

	/** {@link #journey(FarmingPatchCatalog, String, TransportDef, FarmingSites.Tile, com.osrstelemetry.plugin.navigation.AccountRouter.Key)} with the cheapest walks. */
	public static List<Leg> journey(FarmingPatchCatalog catalog, String patchId, TransportDef t, FarmingSites.Tile stop)
	{
		return journey(catalog, patchId, t, stop, router().key(NavAccess.ANY, NavAccess.ANY, "any"));
	}

	/**
	 * Every step after using {@code t} until the player stands at the stop (canonical §28.11): for a journey through a fairy
	 * ring or spirit tree, the account's walk to the entrance with its transitions and the hop; then each transition of the
	 * account's walk from the arrival (doors, stairs, a boat, the dive) and the walk to the patch.
	 */
	public static List<Leg> journey(FarmingPatchCatalog catalog, String patchId, TransportDef t, FarmingSites.Tile stop,
		com.osrstelemetry.plugin.navigation.AccountRouter.Key key)
	{
		return journeyToStop(stopKey(catalog, patchId), t, stop, key, "Walk to the patch");
	}

	/** As {@link #journey} for any route destination by its key; {@code lastWalk}: the words of the final walk ("Walk to the bird house"). */
	public static List<Leg> journeyToStop(String stopKey, TransportDef t, FarmingSites.Tile stop, com.osrstelemetry.plugin.navigation.AccountRouter.Key key,
		String lastWalk)
	{
		List<Leg> out = new ArrayList<>();
		if (t == null)
		{
			return out;
		}
		TransportDef.Via v = t.via;
		if (v != null && v.nodeAt != null)
		{
			boolean ring = "FAIRY_RING".equals(v.network);
			RouteTable.Walk entry = entryWalk(t, key);
			if (entry != null)
			{
				for (Transition tr : entry.getTransitions())
				{
					out.add(Leg.of(tr));
				}
			}
			int ax = v.entryAt == null ? v.nodeAt.x : v.entryAt.x;
			int ay = v.entryAt == null ? v.nodeAt.y : v.entryAt.y;
			out.add(new Leg("Walk to the " + v.nodeName.replaceFirst(" \\(.*\\)$", "") + " (" + v.nodeName.replaceFirst("^[^(]*\\(", "").replace(")", "") + ")",
				v.nodeAt.x, v.nodeAt.y, v.nodeAt.plane, ax, ay, Math.max(20, t.preWalkTiles() + 15)));
			out.add(new Leg(ring ? "Use the fairy ring: dial " + v.code : "Use the spirit tree: travel to " + v.code, v.nodeAt.x, v.nodeAt.y, v.nodeAt.plane,
				v.nodeAt.x, v.nodeAt.y, 8));
		}
		RouteTable.Walk w = walkToStop(stopKey, t, key);
		if (w == null)
		{
			return out;
		}
		int lastX = t.arrival == null ? -1 : t.arrival.x;
		int lastY = t.arrival == null ? -1 : t.arrival.y;
		int lastPlane = t.arrival == null ? -1 : t.arrival.plane;
		for (Transition tr : w.getTransitions())
		{
			out.add(Leg.of(tr));
			lastX = NavPoint.x(tr.to);
			lastY = NavPoint.y(tr.to);
			lastPlane = NavPoint.plane(tr.to);
		}
		if (stop != null && (lastPlane != stop.plane || Math.max(Math.abs(lastX - stop.x), Math.abs(lastY - stop.y)) > 10))
		{
			out.add(new Leg(lastWalk, stop.x, stop.y, stop.plane, lastX, lastY, 30));
		}
		return out;
	}

	/** Where the stop's routes come from (the route data's searches; see TRANSPORT_COVERAGE.md). */
	public static String auditSource(String patchId)
	{
		return "navigation/routes.json: world collision map + transitions (Shortest Path data, BSD-2), RuneLite farming markers";
	}

	/**
	 * Evidence from one owner's patch knowledge. The caller must pass knowledge
	 * belonging to the account being evaluated (the plugin glue checks the owner);
	 * {@code knowledge} null → every key UNKNOWN.
	 */
	public static EvidenceSource evidence(Map<String, PatchKnowledge> knowledge, long now)
	{
		return new EvidenceSource()
		{
			@Override
			public Known get(String key)
			{
				return answer(key).known;
			}

			@Override
			public String explain(String key)
			{
				return answer(key).why;
			}

			private Answer answer(String key)
			{
				if (key != null && key.startsWith(FarmingAccess.PLANTED_HERE_PREFIX))
				{
					PatchKnowledge k = knowledge == null ? null : knowledge.get(key.substring(FarmingAccess.PLANTED_HERE_PREFIX.length()));
					boolean planted = k != null && (k.getCycle() != null || k.getLastClosedCycle() != null || k.getLastObservedPlanting() != null);
					// Proof only: never "no" (an empty patch says nothing about access).
					return planted ? new Answer(Known.YES, "a crop was seen in this patch") : new Answer(Known.UNKNOWN, "no crop seen in this patch yet");
				}
				if (key == null || !key.startsWith(EVIDENCE_PREFIX))
				{
					return new Answer(Known.UNKNOWN, "not a Farming evidence key");
				}
				if (knowledge == null)
				{
					return new Answer(Known.UNKNOWN, "no Farming knowledge for this account");
				}
				String patchId = "spirit_tree." + key.substring(EVIDENCE_PREFIX.length());
				return spiritTree(knowledge.get(patchId), now);
			}
		};
	}

	static final class Answer
	{
		final Known known;
		final String why;

		Answer(Known known, String why)
		{
			this.known = known;
			this.why = why;
		}
	}

	static Answer spiritTree(PatchKnowledge k, long now)
	{
		if (k == null || k.getLastSeenAt() <= 0)
		{
			return new Answer(Known.UNKNOWN, "patch never observed");
		}
		if (k.isDecodeStale())
		{
			return new Answer(Known.UNKNOWN, "last reading needs re-observation");
		}
		PatchReading r = FarmingDecoder.decode(FarmingPatchType.SPIRIT_TREE, k.getLastRaw());
		String age = FarmingText.age(now - k.getLastSeenAt());
		String seen = "when last seen " + (age.equals("just now") ? age : age + " ago");
		PatchCondition c = r == null ? null : r.getCondition();
		if (c == null)
		{
			return new Answer(Known.UNKNOWN, "last reading not decodable");
		}
		switch (c)
		{
			case GROWN:
				return new Answer(Known.YES, "grown " + seen);
			case EMPTY:
			case WEEDS:
			case DEAD:
			case DISEASED:
				return new Answer(Known.NO, c.name().toLowerCase(java.util.Locale.ROOT) + " " + seen);
			default:
				return new Answer(Known.UNKNOWN, c.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " " + seen + "; not known to be usable");
		}
	}
}
