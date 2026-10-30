package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Farm-run step resolution (canonical §10, phase J; §28.9): the current
 * objective is a pure function of the run's observed progress, where the
 * player is, the stops still to do and the previous target. There is no
 * cursor: work done in any order simply removes stops; arriving at any stop
 * with work left makes it the stop to act at; teleporting somewhere other
 * than suggested just changes the next stop.
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>Nothing left (every patch done, skipped or not applicable): COMPLETE.</li>
 *   <li>At a stop with work left (its patches are in the loaded area): ACT
 *   there, with each patch's next action from live state.</li>
 *   <li>Otherwise TRAVEL to the best next stop from here, keeping the previous
 *   target unless it was finished or skipped, the player jumped (a teleport),
 *   or another stop is clearly cheaper (25% and 20 tiles). The step shown is
 *   the one the player is on in the stop's complete journey (the teleport;
 *   the walk to a fairy ring or spirit tree and the hop; a boat, a dive or a
 *   quetzal; the walk to the patch), from observed position.</li>
 * </ol>
 * Inventory space, noting and banking are the player's own business: the
 * guide never plans or prompts them. Advances only from native observations.
 * Never moves, teleports, banks or plants for the player.
 */
public final class FarmingGuide
{
	public enum Kind
	{
		NONE,
		TRAVEL,
		ACT,
		COMPLETE
	}

	/** One objective, immutable. */
	public static final class Objective
	{
		private final Kind kind;
		private final String stop;
		private final String title;
		private final List<String> checklist;
		private final FarmingSites.Tile target;
		private final TransportAvailability transport;
		private final com.osrstelemetry.plugin.navigation.LiveRoute route;
		private final int routeFrom;
		private final int routeTo;
		private FarmingAction action = FarmingAction.NONE;

		Objective(Kind kind, String stop, String title, List<String> checklist, FarmingSites.Tile target, TransportAvailability transport)
		{
			this(kind, stop, title, checklist, target, transport, null, -1, -1);
		}

		Objective withAction(FarmingAction a)
		{
			this.action = a == null ? FarmingAction.NONE : a;
			return this;
		}

		/** The one current action (canonical §28.13): every surface words it from here. */
		public FarmingAction getAction()
		{
			return action;
		}

		Objective(Kind kind, String stop, String title, List<String> checklist, FarmingSites.Tile target, TransportAvailability transport,
			com.osrstelemetry.plugin.navigation.LiveRoute route, int routeFrom, int routeTo)
		{
			this.kind = kind;
			this.stop = stop;
			this.title = title;
			this.checklist = Collections.unmodifiableList(checklist);
			this.target = target;
			this.transport = transport;
			this.route = route;
			this.routeFrom = routeFrom;
			this.routeTo = routeTo;
		}

		/** The live route being followed (walk from here), or null. */
		public com.osrstelemetry.plugin.navigation.LiveRoute getRoute()
		{
			return route;
		}

		/** The route tile index the player is at. */
		public int getRouteFrom()
		{
			return routeFrom;
		}

		/** The route tile index this step walks to (the next transition's start, or the end). */
		public int getRouteTo()
		{
			return routeTo;
		}

		public Kind getKind()
		{
			return kind;
		}

		/** The stop's name (null for NONE / COMPLETE). */
		public String getStop()
		{
			return stop;
		}

		/** Location and patch type: "Catherby · Herb & Allotment". */
		public String getTitle()
		{
			return title;
		}

		/** The useful next action(s): "○ Herb: Harvest", "Use: Camelot Teleport", "✓ Allotment (N): Planted". */
		public List<String> getChecklist()
		{
			return checklist;
		}

		/** Where the player is heading now (the next step's tile, or the stop's marker); null for none. */
		public FarmingSites.Tile getTarget()
		{
			return target;
		}

		/** The teleport to use now (its item is outlined), or null (on foot, mid-journey, or act here). */
		public TransportAvailability getTransport()
		{
			return transport;
		}

		/**
		 * An objective built by another run's guide (bird houses): the same shape, so guidance, the overlays and the box treat
		 * every run alike.
		 */
		public static Objective of(Kind kind, String stop, String title, List<String> checklist, FarmingSites.Tile target, TransportAvailability transport,
			com.osrstelemetry.plugin.navigation.LiveRoute route, int routeFrom, int routeTo, FarmingAction action)
		{
			return new Objective(kind, stop, title, checklist, target, transport, route, routeFrom, routeTo).withAction(action);
		}

		static Objective none()
		{
			return new Objective(Kind.NONE, null, null, Collections.emptyList(), null, null);
		}
	}

	/** Where the player is (world coordinates); null when not known. */
	public static final class Position
	{
		final int x;
		final int y;
		final int plane;
		/** Inside the player's house (a house route's next step is the facility there, not the house teleport). */
		final boolean inHouse;

		public Position(int x, int y, int plane)
		{
			this(x, y, plane, false);
		}

		public Position(int x, int y, int plane, boolean inHouse)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.inHouse = inHouse;
		}
	}

	/** Teleport-scale jump between two ticks: the player teleported (stickiness releases). */
	static final int JUMP_TILES = 30;
	/** The stop being worked stays current within this many tiles while it has work left. */
	static final int STAY_TILES = 20;
	/** Another stop takes the turn only when the player is this much nearer to it. */
	static final int SWITCH_TILES = 8;
	/** Within this, a stop is walked to rather than teleported to. */
	static final int WALK_TILES = 60;
	/** A teleport is worth about this many tiles of walking (for comparing journeys). */
	public static final int TELEPORT_COST = 15;
	/** A fairy ring or spirit tree hop, in tiles of walking. */
	public static final int HOP_COST = 10;

	private FarmingGuide()
	{
	}

	public static Objective resolve(FarmingRunView run, List<FarmingPreparation.SitePlan> sites, Position pos, Position lastPos, String previous,
		FarmingPreferences prefs)
	{
		return resolve(run, sites, pos, lastPos, previous, prefs, null);
	}

	/**
	 * @param run      the run as the page shows it (progress and the patches in the loaded area)
	 * @param sites    the run's stops with their chosen journeys, in suggested order
	 * @param pos      where the player is (null: unknown)
	 * @param lastPos  where the player was on the previous resolution (null: none)
	 * @param previous the previous objective's stop (null: none)
	 * @param prefs    preferences (wording for tree clearing and fruit)
	 * @param catalog  patch catalogue (journey steps); null: teleport and walk only
	 */
	public static Objective resolve(FarmingRunView run, List<FarmingPreparation.SitePlan> sites, Position pos, Position lastPos, String previous,
		FarmingPreferences prefs, FarmingPatchCatalog catalog)
	{
		return resolve(run, sites, pos, lastPos, previous, prefs, catalog, null);
	}

	/**
	 * As above, following a live route when one leads from here to the chosen stop (canonical §28.10): the step is the next
	 * transition on it (a door, stairs, a boat) or the walk to its end; a route ending at a fairy ring or spirit tree then
	 * names the hop and the steps after it. Without one (or off it), the stop's journey from the bank applies.
	 */
	public static Objective resolve(FarmingRunView run, List<FarmingPreparation.SitePlan> sites, Position pos, Position lastPos, String previous,
		FarmingPreferences prefs, FarmingPatchCatalog catalog, com.osrstelemetry.plugin.navigation.LiveRoute live)
	{
		return resolve(run, sites, pos, lastPos, previous, prefs, catalog, live, null);
	}

	/**
	 * As above; {@code actedAt}: the stop the previous objective was acting at (null: it was travel or nothing). Only a stop
	 * being worked keeps the turn while the player moves about it; a stop merely being travelled to does not.
	 */
	public static Objective resolve(FarmingRunView run, List<FarmingPreparation.SitePlan> sites, Position pos, Position lastPos, String previous,
		FarmingPreferences prefs, FarmingPatchCatalog catalog, com.osrstelemetry.plugin.navigation.LiveRoute live, String actedAt)
	{
		if (run == null || run.getPhase() != FarmingRunView.Phase.ACTIVE)
		{
			return Objective.none();
		}
		List<FarmingPreparation.SitePlan> remaining = new ArrayList<>();
		for (FarmingPreparation.SitePlan s : sites)
		{
			if (hasWork(run, s) || compostLeft(run, s) || protectionLeft(run, s, prefs) || binDue(run, s) != null)
			{
				remaining.add(s);
			}
		}
		if (remaining.isEmpty())
		{
			return new Objective(Kind.COMPLETE, null, "Run complete", Collections.singletonList("Every patch is done or skipped."), null, null)
				.withAction(new FarmingAction(FarmingAction.Stage.COMPLETE, "Run complete", "Every patch is done or skipped", null, null, -1));
		}
		// At a stop with work left.
		FarmingPreparation.SitePlan here = null;
		for (FarmingPreparation.SitePlan s : remaining)
		{
			if (isHere(run, s) && (here == null || pos != null && distance(pos, s) < distance(pos, here)))
			{
				here = s;
			}
		}
		// The stop being worked stays current while it has work left (a health check, a harvest or planting is not the end
		// of it), unless the player has clearly gone to another included stop: within STAY_TILES of it, it keeps the turn,
		// and another stop takes over only when the player stands nearer that one by SWITCH_TILES.
		FarmingPreparation.SitePlan kept = null;
		for (FarmingPreparation.SitePlan s : remaining)
		{
			if (actedAt != null && s.getSite().getName().equals(actedAt) && pos != null && s.getSite().getTile() != null
				&& distance(pos, s) <= STAY_TILES)
			{
				kept = s;
			}
		}
		if (kept != null && (here == null || here == kept || distance(pos, here) + SWITCH_TILES > distance(pos, kept)))
		{
			here = kept;
		}
		if (here != null)
		{
			return new Objective(Kind.ACT, here.getSite().getName(), title(run, here), checklist(run, here, prefs), actingTile(run, here), null)
				.withAction(patchAction(run, here, prefs));
		}
		FarmingPreparation.SitePlan next = choose(remaining, pos, lastPos, previous);
		Objective followed = follow(run, next, pos, live, prefs, catalog);
		if (followed != null)
		{
			return followed;
		}
		List<String> list = new ArrayList<>();
		TransportAvailability t = next.getTransport();
		long walk = pos == null || next.getSite().getTile() == null ? Long.MAX_VALUE : distance(pos, next);
		List<FarmingTravel.Leg> legs = t == null || catalog == null ? Collections.emptyList()
			: next.getLegs();
		// Part-way through the journey: the step the player is on (the last step whose area holds them).
		int on = -1;
		for (int i = 0; pos != null && i < legs.size(); i++)
		{
			if (legs.get(i).contains(pos.x, pos.y, pos.plane))
			{
				on = i;
			}
		}
		if (walk > WALK_TILES && on >= 0)
		{
			FarmingTravel.Leg leg = legs.get(on);
			list.add("○ " + leg.text);
			for (int i = on + 1; i < legs.size(); i++)
			{
				list.add("Then: " + legs.get(i).text);
			}
			list.addAll(checklist(run, next, prefs));
			return new Objective(Kind.TRAVEL, next.getSite().getName(), title(run, next), list, new FarmingSites.Tile(leg.x, leg.y, leg.plane), null)
				.withAction(legAction(leg, next, pos));
		}
		if (walk <= WALK_TILES)
		{
			list.add("Walk there · ~" + walk + " tiles");
		}
		else if (t == null)
		{
			list.add("No route to suggest from here");
		}
		else
		{
			list.add("Use: " + FarmingPreparation.itemOrName(t.getTransport()));
			for (FarmingTravel.Leg leg : legs)
			{
				list.add("Then: " + leg.text);
			}
		}
		list.addAll(checklist(run, next, prefs));
		FarmingAction act = walk <= WALK_TILES ? FarmingWording.walkToPatch(varbits(run, next), -1)
			: t == null ? new FarmingAction(FarmingAction.Stage.WALK, "Walk to " + next.getSite().getName(), "Walk to " + next.getSite().getName(), null,
			null, -1)
			: FarmingWording.teleport(t.getTransport(), null, pos != null && pos.inHouse);
		return new Objective(Kind.TRAVEL, next.getSite().getName(), title(run, next), list, next.getSite().getTile(), walk <= WALK_TILES ? null : t)
			.withAction(act);
	}

	/**
	 * The included compost bin at this stop with a task now, not skipped (§28.16), or null. A bin belongs to the stop of its
	 * anchor patch (the allotment site it stands at); a run with no crop patch there still visits it as its own stop.
	 */
	static RunExtras.BinWork binDue(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		if (run.getBinWork().isEmpty() || s.getSite().getPatchIds().isEmpty())
		{
			return null;
		}
		FarmingPatchCatalog catalog = FarmingPatchCatalog.standard();
		String stop = FarmingSites.stopOf(catalog, s.getSite().getPatchIds().get(0));
		for (RunExtras.BinWork b : run.getBinWork())
		{
			if (b.isDue() && FarmingSites.stopOf(catalog, b.def.getAnchorPatchId()).equals(stop))
			{
				return b;
			}
		}
		return null;
	}

	/** The bin's task as the current action: the bin object at its tile is the target (cache-verified id, §28.15). */
	static FarmingAction binAction(RunExtras.BinWork b)
	{
		String guide = b.task.label + (b.task.projected ? "; if it is still rotting, move on" : "") + " (optional: Skip it on the run page)";
		return new FarmingAction(FarmingAction.Stage.ACT, b.task.label, guide, "Compost bin · " + b.def.getPlace(),
			FarmingAction.Target.object(java.util.Collections.singleton(b.def.getObjectId()), "Compost Bin|Big Compost Bin", null, b.def.getX(),
				b.def.getY(), b.def.getPlane(), 2), -1);
	}

	/**
	 * Pass 32: never tells the player to cast a spell their spellbook does not have. When the objective's teleport needs a
	 * spellbook other than {@code currentBook} (its enum name; null: not read), the step becomes switching book first (the
	 * spell is not highlighted); the rest of the journey follows once they have. The plan replans on the change; this covers
	 * the moment between. Anything else is returned unchanged.
	 */
	public static Objective guardSpellbook(Objective o, String currentBook)
	{
		if (o == null || o.getKind() != Kind.TRAVEL || o.getTransport() == null
			|| !com.osrstelemetry.plugin.travel.TravelPlanner.needsSpellbookChange(o.getTransport().getTransport(), currentBook))
		{
			return o;
		}
		String need = FarmingPreparation.bookTitle(com.osrstelemetry.plugin.travel.TravelPlanner.spellbookOf(o.getTransport().getTransport()));
		String label = currentBook == null ? "Check you are on the " + need + " spellbook" : "Switch to the " + need + " spellbook first";
		String why = currentBook == null ? "your spellbook is not read yet" : "you are on " + FarmingPreparation.bookTitle(currentBook);
		List<String> list = new ArrayList<>();
		list.add("○ " + label + " · " + why);
		list.addAll(o.getChecklist());
		FarmingAction act = new FarmingAction(FarmingAction.Stage.TELEPORT, label, label + " (" + why + "), then: "
			+ FarmingPreparation.itemOrName(o.getTransport().getTransport()), null, null, -1);
		return new Objective(o.getKind(), o.getStop(), o.getTitle(), list, o.getTarget(), null, o.getRoute(), o.getRouteFrom(), o.getRouteTo())
			.withAction(act);
	}

	/** The action for a journey leg the player is on (no live route to follow). */
	static FarmingAction legAction(FarmingTravel.Leg leg, FarmingPreparation.SitePlan next, Position pos)
	{
		if (leg.transition != null)
		{
			int d = pos == null ? -1 : Math.max(Math.abs(pos.x - leg.x), Math.abs(pos.y - leg.y));
			return FarmingWording.transition(leg.transition, d);
		}
		TransportDef t = next.getTransport() == null ? null : next.getTransport().getTransport();
		if (leg.text.startsWith("Use the fairy ring") || leg.text.startsWith("Use the spirit tree"))
		{
			boolean ring = leg.text.startsWith("Use the fairy ring");
			String code = t != null && t.via != null ? t.via.code : leg.text.replaceFirst("^.*(dial|to) ", "");
			return FarmingWording.hop(ring, code, null, leg.x, leg.y, leg.plane);
		}
		if (leg.text.startsWith("Walk to the patch"))
		{
			return FarmingWording.walkToPatch(Collections.emptySet(), -1);
		}
		if (leg.text.startsWith("Walk to the "))
		{
			boolean ring = leg.text.toLowerCase(java.util.Locale.ROOT).contains("fairy ring");
			int d = pos == null ? -1 : Math.max(Math.abs(pos.x - leg.x), Math.abs(pos.y - leg.y));
			return FarmingWording.walkToEntrance(ring, d, leg.x, leg.y, leg.plane);
		}
		return new FarmingAction(FarmingAction.Stage.WALK, leg.text, leg.text, null, null, -1);
	}

	/** The current action at a stop: the first patch (stop order) with work left, else a protection payment still due. */
	static FarmingAction patchAction(FarmingRunView run, FarmingPreparation.SitePlan s, FarmingPreferences prefs)
	{
		List<FarmingRunView.PatchLine> lines = new ArrayList<>();
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && l.getProgress().getOutcome() != FarmingRunEvaluator.Outcome.SKIPPED)
			{
				lines.add(l);
			}
		}
		boolean several = lines.size() > 1;
		for (FarmingRunView.PatchLine l : lines)
		{
			if (!l.getProgress().getOutcome().isSettled())
			{
				FarmingSites.Tile at = gardenerAround(s, l);
				return FarmingWording.patch(l.getProgress(), run.getIntent(), prefs, false, several, l.getRow() == null ? null : patchLabel(l),
					at == null ? -1 : at.x, at == null ? -1 : at.y, at == null ? -1 : at.plane, compostSeen(l));
			}
		}
		for (FarmingRunView.PatchLine l : lines)
		{
			if (l.isCompostDue())
			{
				return FarmingWording.compostAfterPlanting(l.getProgress(), RunExtras.compostMethod(run.getIntent(), l.getProgress().getPatch()), several,
					l.getRow() == null ? null : patchLabel(l));
			}
		}
		for (FarmingRunView.PatchLine l : lines)
		{
			if (FarmingWording.protectionPending(l.getProgress(), run.getIntent(), prefs))
			{
				FarmingSites.Tile at = gardenerAround(s, l);
				return FarmingWording.patch(l.getProgress(), run.getIntent(), prefs, true, several, l.getRow() == null ? null : patchLabel(l),
					at == null ? -1 : at.x, at == null ? -1 : at.y, at == null ? -1 : at.plane);
			}
		}
		RunExtras.BinWork bin = binDue(run, s);
		if (bin != null)
		{
			return binAction(bin);
		}
		return new FarmingAction(FarmingAction.Stage.ACT, "Done here", "Done here", null, null, -1);
	}

	/** The marker of the patch being worked at this stop (its own type's), else the stop's tile. */
	static FarmingSites.Tile actingTile(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && (!l.getProgress().getOutcome().isSettled() || l.isCompostDue()))
			{
				return gardenerAround(s, l);
			}
		}
		RunExtras.BinWork bin = binDue(run, s);
		if (bin != null && !hasWork(run, s) && !compostLeft(run, s) && !protectionLeft(run, s, null))
		{
			return new FarmingSites.Tile(bin.def.getX(), bin.def.getY(), bin.def.getPlane());
		}
		return s.getSite().getTile();
	}

	/**
	 * Where that patch's gardener is looked for: the patch's own marker (its type's, §28.16), never another patch's at the same
	 * stop (the Farming Guild's tree gardener is 35 tiles from its allotments'); the stop's tile when the patch has none.
	 */
	static FarmingSites.Tile gardenerAround(FarmingPreparation.SitePlan s, FarmingRunView.PatchLine l)
	{
		FarmingSites.Tile own = FarmingSites.tileOf(FarmingPatchCatalog.standard(), l.getProgress().getPatchId());
		return own != null ? own : s.getSite().getTile();
	}

	/**
	 * The game showed compost on this patch's planting (its message or Inspect, §28.14): the compost step is done. Read from the
	 * record itself, never from the step's display label (pass 25 shortened that label to "Super", which this test missed, so
	 * the guide kept asking for supercompost after it was applied).
	 */
	static boolean compostSeen(FarmingRunView.PatchLine l)
	{
		return l.getCompost() != null && l.getCompost().getKind() != PatchCompost.Kind.NONE;
	}

	/** A stop whose planting still wants its compost (§28.17): the stop stays until the game shows it or it is skipped. */
	static boolean compostLeft(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && l.isCompostDue())
			{
				return true;
			}
		}
		return false;
	}

	/** A stop whose patches are all settled but whose protection is still to pay (the step stays until a payment is seen). */
	static boolean protectionLeft(FarmingRunView run, FarmingPreparation.SitePlan s, FarmingPreferences prefs)
	{
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && FarmingWording.protectionPending(l.getProgress(), run.getIntent(), prefs))
			{
				return true;
			}
		}
		return false;
	}

	/** The varbits of the stop's patches still to do (their objects are what "the patch" is). */
	static java.util.Set<Integer> varbits(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && l.getProgress().getPatch() != null && !l.getProgress().getOutcome().isSettled())
			{
				out.add(l.getProgress().getPatch().getVarbit());
			}
		}
		return out;
	}

	/** The step on a live route to {@code next}, or null when there is none to follow from here. */
	static Objective follow(FarmingRunView run, FarmingPreparation.SitePlan next, Position pos,
		com.osrstelemetry.plugin.navigation.LiveRoute live, FarmingPreferences prefs, FarmingPatchCatalog catalog)
	{
		if (live == null || pos == null || live.getPath() == null || !next.getSite().getName().equals(live.getTarget()))
		{
			return null;
		}
		com.osrstelemetry.plugin.navigation.NavPath path = live.getPath();
		int at = live.progress(com.osrstelemetry.plugin.navigation.NavPoint.pack(pos.x, pos.y, pos.plane), 4);
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
		String reached = live.getReached();
		TransportDef hop = next.getTransport() == null ? null : next.getTransport().getTransport();
		boolean toEntrance = reached != null && reached.startsWith("node:");
		int to;
		if (nextTransition >= 0)
		{
			to = nextTransition - 1;
			list.add("○ " + path.via(nextTransition).instruction());
			for (int i = nextTransition + 1; i < path.size(); i++)
			{
				if (path.via(i) != null)
				{
					list.add("Then: " + path.via(i).instruction());
				}
			}
		}
		else
		{
			to = path.size() - 1;
			int left = 0;
			for (int i = at + 1; i < path.size(); i++)
			{
				left++;
			}
			list.add("○ " + (toEntrance ? "Walk to the " + entranceName(hop, reached) : "Walk to the patch") + (left > 0 ? " · ~" + left + " tiles" : ""));
		}
		if (toEntrance && hop != null && hop.via != null)
		{
			if (nextTransition >= 0)
			{
				list.add("Then: Walk to the " + entranceName(hop, reached));
			}
			list.add("Then: " + ("FAIRY_RING".equals(hop.via.network) ? "Use the fairy ring: dial " + hop.via.code : "Use the spirit tree: travel to " + hop.via.code));
			// The steps after the hop, as planned for this account (the walk from the hop's arrival to the patch).
			boolean afterHop = false;
			for (FarmingTravel.Leg leg : next.getLegs())
			{
				if (afterHop)
				{
					list.add("Then: " + leg.text);
				}
				afterHop |= leg.text.startsWith("Use the fairy ring") || leg.text.startsWith("Use the spirit tree");
			}
		}
		// One clear step at a time: the step, at most two "Then" lines, and how many more.
		List<String> compact = new ArrayList<>();
		int then = 0;
		for (String l : list)
		{
			if (!l.startsWith("Then: ") || then++ < 2)
			{
				compact.add(l);
			}
		}
		if (then > 2)
		{
			compact.add("…and " + (then - 2) + " more step" + (then - 2 == 1 ? "" : "s"));
		}
		list = compact;
		list.addAll(checklist(run, next, prefs));
		int tile = path.tile(Math.max(at, to));
		FarmingSites.Tile target = new FarmingSites.Tile(com.osrstelemetry.plugin.navigation.NavPoint.x(tile), com.osrstelemetry.plugin.navigation.NavPoint.y(tile),
			com.osrstelemetry.plugin.navigation.NavPoint.plane(tile));
		// The current action: the next transition (walk to it, then take it), else the walk to the entrance or the patch, with
		// the route's own tiles still to go as the countdown.
		FarmingAction act;
		int tilesLeft = Math.max(0, Math.max(at, to) - at);
		if (nextTransition >= 0)
		{
			act = FarmingWording.transition(path.via(nextTransition), tilesLeft);
		}
		else if (toEntrance)
		{
			boolean ring = hop != null && hop.via != null ? "FAIRY_RING".equals(hop.via.network) : reached.startsWith("node:ring.");
			int end = path.tile(path.size() - 1);
			act = tilesLeft > 2 ? FarmingWording.walkToEntrance(ring, tilesLeft, com.osrstelemetry.plugin.navigation.NavPoint.x(end),
				com.osrstelemetry.plugin.navigation.NavPoint.y(end), com.osrstelemetry.plugin.navigation.NavPoint.plane(end))
				: FarmingWording.hop(ring, hop != null && hop.via != null ? hop.via.code : entranceName(hop, reached), null,
				com.osrstelemetry.plugin.navigation.NavPoint.x(end), com.osrstelemetry.plugin.navigation.NavPoint.y(end),
				com.osrstelemetry.plugin.navigation.NavPoint.plane(end));
		}
		else
		{
			act = FarmingWording.walkToPatch(varbits(run, next), tilesLeft);
		}
		return new Objective(Kind.TRAVEL, next.getSite().getName(), title(run, next), list, target, null, live, at, Math.max(at, to)).withAction(act);
	}

	private static String entranceName(TransportDef hop, String reached)
	{
		if (hop != null && hop.via != null && reached.equals("node:" + hop.via.node))
		{
			return hop.via.nodeName;
		}
		return reached.startsWith("node:ring.") ? "fairy ring " + reached.substring("node:ring.".length()).toUpperCase(java.util.Locale.ROOT)
			: "spirit tree";
	}

	static boolean hasWork(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && !l.getProgress().getOutcome().isSettled())
			{
				return true;
			}
		}
		return false;
	}

	static boolean isHere(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			// A bin's own stop has no run patch: the game transmitting its site's patches says the player is there.
			if (l != null ? l.isHere() : run.getHerePatchIds().contains(id))
			{
				return true;
			}
		}
		return false;
	}

	static FarmingRunView.PatchLine line(FarmingRunView run, String id)
	{
		for (FarmingRunView.PatchLine l : run.getLines())
		{
			if (l.getPatchId().equals(id))
			{
				return l;
			}
		}
		return null;
	}

	/**
	 * The next stop when the player is at none (canonical §28.12): the one guidance is already on, unless the player jumped
	 * (a teleport) and another stop is now clearly cheaper; otherwise the cheapest to start from here. The run page shows the
	 * stop chosen here first (published as the current stop), then the planned order, so the guide, the NEXT card and the
	 * To do list always agree.
	 */
	static FarmingPreparation.SitePlan choose(List<FarmingPreparation.SitePlan> remaining, Position pos, Position lastPos, String previous)
	{
		FarmingPreparation.SitePlan best = null;
		long bestCost = Long.MAX_VALUE;
		FarmingPreparation.SitePlan kept = null;
		for (FarmingPreparation.SitePlan s : remaining)
		{
			long c = cost(pos, s);
			if (c < bestCost)
			{
				bestCost = c;
				best = s;
			}
			if (s.getSite().getName().equals(previous))
			{
				kept = s;
			}
		}
		boolean jumped = pos != null && lastPos != null && (pos.plane != lastPos.plane
			|| Math.max(Math.abs(pos.x - lastPos.x), Math.abs(pos.y - lastPos.y)) > JUMP_TILES);
		if (kept != null && !jumped)
		{
			long keptCost = cost(pos, kept);
			if (keptCost <= bestCost * 5 / 4 || keptCost - bestCost < 20)
			{
				return kept;
			}
		}
		return best;
	}

	/** Cost to start this stop from here: walking if close, else its teleport (origin-independent) plus the walk from arrival. */
	static long cost(Position pos, FarmingPreparation.SitePlan s)
	{
		TransportDef d = s.getTransport() == null ? null : s.getTransport().getTransport();
		long viaTeleport = d == null ? 10_000 : TELEPORT_COST + Math.max(0, Math.min(s.getWalkTiles(), 500)) + d.preWalkTiles()
			+ (d.via != null ? HOP_COST : 0);
		if (pos == null || s.getSite().getTile() == null)
		{
			return viaTeleport;
		}
		return Math.min(distance(pos, s), viaTeleport);
	}

	static long distance(Position pos, FarmingPreparation.SitePlan s)
	{
		FarmingSites.Tile t = s.getSite().getTile();
		if (t == null)
		{
			return Long.MAX_VALUE / 4;
		}
		return Math.max(Math.abs(pos.x - t.x), Math.abs(pos.y - t.y)) + (pos.plane == t.plane ? 0 : 20);
	}

	/** "Catherby · Herb & Allotment": the stop and the patch types still to do there. */
	static String title(FarmingRunView run, FarmingPreparation.SitePlan s)
	{
		String name = s.getSite().getName();
		if (name.contains("·") && !(binDue(run, s) != null && !hasWork(run, s) && !protectionLeft(run, s, null)))
		{
			return name;
		}
		java.util.Set<String> types = new java.util.LinkedHashSet<>();
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l != null && !l.getProgress().getOutcome().isSettled() && l.getProgress().getPatch() != null)
			{
				types.add(l.getProgress().getPatch().getType().getDisplayName());
			}
		}
		if (types.isEmpty() && binDue(run, s) != null)
		{
			return (name.contains(" \u00b7 ") ? name.substring(0, name.indexOf(" \u00b7 ")) : name) + " \u00b7 Compost bin";
		}
		return types.isEmpty() ? name : name + " · " + String.join(" & ", types);
	}

	/** One line per patch at the stop: ✓ done or ○ the next action, worded for the player's clearing preference. */
	static List<String> checklist(FarmingRunView run, FarmingPreparation.SitePlan s, FarmingPreferences prefs)
	{
		List<String> out = new ArrayList<>();
		for (String id : s.getSite().getPatchIds())
		{
			FarmingRunView.PatchLine l = line(run, id);
			if (l == null)
			{
				continue;
			}
			FarmingRunEvaluator.PatchProgress p = l.getProgress();
			String who = l.getRow() == null ? id : patchLabel(l);
			if (p.getOutcome() == FarmingRunEvaluator.Outcome.SKIPPED)
			{
				continue;
			}
			boolean settled = p.getOutcome().isSettled();
			boolean compostLeft = settled && l.isCompostDue();
			boolean protect = settled && !compostLeft && FarmingWording.protectionPending(p, run.getIntent(), prefs);
			String text = compostLeft ? FarmingWording.compostAfterPlanting(p, RunExtras.compostMethod(run.getIntent(), p.getPatch()), false, null).getGuide()
				: protect ? FarmingWording.patch(p, run.getIntent(), prefs, true, false, null, -1, -1, -1).getGuide()
				: settled ? FarmingRunView.runText(p).replace("✓ ", "") : FarmingWording.patch(p, run.getIntent(), prefs, false, false, null, -1, -1, -1).getGuide();
			// The patch is named only where the stop has several (a lone patch needs no "Tree:" prefix).
			String named = s.getSite().getPatchIds().size() > 1 ? text + " (" + who + ")" : text;
			out.add((settled && !protect && !compostLeft ? "✓ " : "○ ") + named);
		}
		RunExtras.BinWork bin = binDue(run, s);
		if (bin != null)
		{
			out.add("○ " + bin.task.label + " (optional)");
		}
		return out;
	}

	static String patchLabel(FarmingRunView.PatchLine l)
	{
		FarmingPatchDef def = l.getProgress().getPatch();
		String type = def == null ? "Patch" : def.getType().getDisplayName();
		String loc = l.getRow().getLocation();
		int open = loc.lastIndexOf('(');
		return open >= 0 && loc.endsWith(")") ? type + " " + loc.substring(open) : type;
	}

	/** The next action for one patch, worded as the guide box words it (the one shared wording, canonical §28.13). */
	static String action(FarmingRunEvaluator.PatchProgress p, FarmingPreferences prefs)
	{
		return FarmingWording.patch(p, null, prefs, false, false, null, -1, -1, -1).getGuide();
	}
}
