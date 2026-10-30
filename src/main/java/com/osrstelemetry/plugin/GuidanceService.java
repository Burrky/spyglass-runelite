package com.osrstelemetry.plugin;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountCapabilityAdapter;
import com.osrstelemetry.plugin.farming.FarmingCaptureAdapter;
import com.osrstelemetry.plugin.farming.FarmingGuide;
import com.osrstelemetry.plugin.farming.FarmingModel;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPreparation;
import com.osrstelemetry.plugin.farming.FarmingRunIntent;
import com.osrstelemetry.plugin.farming.FarmingRunView;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.guidance.GuidanceModel;
import com.osrstelemetry.plugin.guidance.GuidanceOverlays;
import com.osrstelemetry.plugin.guidance.LocalWalker;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportDef;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * Farm-run guidance glue (canonical §9, phases I and J). On the client
 * thread, every game tick while a run is active (preparation closed), it
 * resolves the current objective ({@link FarmingGuide}) from the run's
 * observed progress, the player's position, the stops still to do with their
 * chosen transports and the live inventory plan, plans a walking path over
 * the loaded scene ({@link LocalWalker}) and publishes an immutable
 * {@link GuidanceModel} for the overlays and the box.
 *
 * <p>Paused (nothing drawn) while the bank is open or the game is loading;
 * off when logged out, when no run is active, or once the run is ended or
 * complete. Everything is display only: no movement, teleport, banking,
 * planting or input is ever performed.
 */
@Slf4j
@Singleton
public class GuidanceService
{
	private static final int REPLAN_TICKS = 5;
	/** With no usable teleport journey to compare with, a live walk may cost this much (tiles) before it's given up. */
	private static final int NO_JOURNEY_BOUND = 4000;
	/** With no input changed, the shared plan is refreshed this often, in ticks (30 s; evidence and charges age). */
	private static final int AVAILABILITY_TICKS = FarmingPreparationService.REFRESH_TICKS;
	private Object routeOwner;

	private final Client client;
	private final AccountCapabilityAdapter account;
	private final FarmingCaptureAdapter farming;
	private final TravelCapabilityWiring travel;
	private final FarmingPreferenceStore preferences;
	private final OverlayManager overlayManager;
	private final WorldMapPointManager worldMap;
	private final ItemManager itemManager;
	private final NavigationRuntime navigation;
	private final FarmingPlanWorker planner;
	private final PohObserver house;
	private final BirdhouseService birdhouses;
	private int[] lastBirdPos;
	private int lastRouteRequest = -100;
	/** The current action's targets in the scene (canonical §28.13). */
	private final com.osrstelemetry.plugin.guidance.GuidanceTargets targets = new com.osrstelemetry.plugin.guidance.GuidanceTargets();
	/** The stop the player has reached a patch at: entered within one tile of the patch, left only three tiles away (no flicker). */
	private String arrivedStop;

	private volatile GuidanceModel model = GuidanceModel.OFF;
	private GuidanceOverlays overlays;
	private int ticks;
	private FarmingGuide.Position lastPos;
	private String lastStop;
	/** The stop the last objective was acting at (null: travel or nothing): only that stop keeps the turn while worked. */
	private String lastActed;
	private WorldPoint pathTarget;
	private List<WorldPoint> path = Collections.emptyList();

	@Inject
	public GuidanceService(Client client, AccountCapabilityAdapter account, FarmingCaptureAdapter farming, TravelCapabilityWiring travel,
		FarmingPreferenceStore preferences, OverlayManager overlayManager, WorldMapPointManager worldMap, ItemManager itemManager,
		NavigationRuntime navigation, FarmingPlanWorker planner, PohObserver house, BirdhouseService birdhouses)
	{
		this.birdhouses = birdhouses;
		this.house = house;
		this.planner = planner;
		this.navigation = navigation;
		this.client = client;
		this.account = account;
		this.farming = farming;
		this.travel = travel;
		this.preferences = preferences;
		this.overlayManager = overlayManager;
		this.worldMap = worldMap;
		this.itemManager = itemManager;
	}

	/** The latest model (any thread). */
	public GuidanceModel model()
	{
		return model;
	}

	public void start()
	{
		model = GuidanceModel.OFF;
		overlays = new GuidanceOverlays(client, this::model, worldMap, itemManager::getItemOutline);
		overlays.addTo(overlayManager);
		navigation.start();
	}

	public void stop()
	{
		model = GuidanceModel.OFF;
		if (overlays != null)
		{
			overlays.removeFrom(overlayManager);
			overlays = null;
		}
		lastPos = null;
		lastStop = null;
		lastActed = null;
		arrivedStop = null;
		targets.clear();
		path = Collections.emptyList();
		navigation.stop();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		try
		{
			model = resolve();
		}
		catch (RuntimeException e)
		{
			log.debug("guidance skipped this tick", e);
			model = GuidanceModel.OFF;
		}
		if (!model.isActive())
		{
			targets.clear(); // nothing to point at: off, paused, ended, logged out
		}
		if (overlays != null)
		{
			overlays.sync(model);
		}
	}

	/** A scene change, a hop or a logout: nothing loaded before it may stay highlighted (the next tick resolves again). */
	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged e)
	{
		if (e.getGameState() != GameState.LOGGED_IN)
		{
			targets.clear();
			arrivedStop = null;
			model = GuidanceModel.OFF;
		}
	}

	@Subscribe
	public void onNpcDespawned(net.runelite.api.events.NpcDespawned e)
	{
		forget(e.getNpc());
	}

	@Subscribe
	public void onGameObjectDespawned(net.runelite.api.events.GameObjectDespawned e)
	{
		forget(e.getGameObject());
	}

	@Subscribe
	public void onWallObjectDespawned(net.runelite.api.events.WallObjectDespawned e)
	{
		forget(e.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(net.runelite.api.events.DecorativeObjectDespawned e)
	{
		forget(e.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(net.runelite.api.events.GroundObjectDespawned e)
	{
		forget(e.getGroundObject());
	}

	private void forget(Object gone)
	{
		if (targets.drop(gone))
		{
			model = model.withHighlights(targets.objects(), targets.npcs()).withFootprints(gone instanceof net.runelite.api.GameObject
				? java.util.Collections.emptyList() : model.getFootprints(), model.isQuiet());
		}
	}

	private GuidanceModel resolve()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			lastPos = null;
			return GuidanceModel.OFF;
		}
		FarmingSnapshot snapshot = farming.uiSnapshot();
		FarmingRunIntent intent = snapshot.getRun();
		if (snapshot.getState() != FarmingSnapshot.State.AVAILABLE || intent == null || intent.isPreparing()
			|| intent.getState() != FarmingRunIntent.State.ACTIVE || travel.catalog() == null)
		{
			lastStop = null;
		lastActed = null;
			navigation.reset();
			if (snapshot.getState() == FarmingSnapshot.State.AVAILABLE)
			{
				farming.setRunCurrentStop(null, null); // no active run: no current stop on any surface
			}
			GuidanceModel bird = intent != null && intent.getState() == FarmingRunIntent.State.ACTIVE ? null : resolveBirdhouses();
			return bird != null ? bird : GuidanceModel.OFF;
		}
		Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bank != null && !bank.isHidden())
		{
			return new GuidanceModel(GuidanceModel.State.PAUSED, null, Collections.emptyList(), null, null, Collections.emptyList(), null, null);
		}
		long now = System.currentTimeMillis();
		AccountCapability cap = account.capability();
		// Another account or profile: nothing from the previous one may be followed or drawn.
		Object owner = snapshot.getOwner();
		if (owner == null ? routeOwner != null : !owner.equals(routeOwner))
		{
			navigation.reset();
			routeOwner = owner;
		}
		FarmingPatchCatalog catalog = FarmingPatchCatalog.standard();
		com.osrstelemetry.plugin.farming.FarmingPreferences prefs = preferences.get();
		// The plan (thousands of journeys evaluated) is the run's one shared plan, computed off the client thread by the plan
		// worker (canonical §28.12): asked for when its inputs change and every few ticks; the latest for this run is used.
		long routes = com.osrstelemetry.plugin.navigation.AccountRouter.sharedVersion();
		FarmingPlanWorker.Key key = new FarmingPlanWorker.Key(cap, intent, prefs, travel.houseKnowledge(), snapshot.getKnowledge(), routes,
			ticks++ / AVAILABILITY_TICKS).withBins(snapshot.getBins());
		planner.request(key, travel.catalog(), catalog, () -> travel.evidence(cap.getOwner(), snapshot, now), now, snapshot.getKnowledge());
		FarmingPlanWorker.Result result = planner.latest();
		if (result == null || !result.fits(cap, intent))
		{
			return GuidanceModel.OFF; // the first plan for this run is on its way (normally ready from preparation)
		}
		FarmingPreparation.Plan plan = result.getPlan();
		List<FarmingPreparation.SitePlan> sites = new ArrayList<>(plan.getSites());
		List<String> order = intent.getSuggestedOrder();
		if (!order.isEmpty())
		{
			sites.sort(Comparator.comparingInt(s -> {
				int i = order.indexOf(s.getSite().getPatchIds().get(0));
				return i < 0 ? Integer.MAX_VALUE : i;
			}));
		}
		FarmingRunView run = FarmingModel.build(catalog, snapshot, now, travel.farmingFacts()).getRun();
		List<FarmingPreparation.SitePlan> remaining = new ArrayList<>();
		for (FarmingPreparation.SitePlan s : sites)
		{
			for (String id : s.getSite().getPatchIds())
			{
				FarmingRunView.PatchLine l = line(run, id);
				if (l != null && !l.getProgress().getOutcome().isSettled())
				{
					remaining.add(s);
					break;
				}
			}
		}
		Player me = client.getLocalPlayer();
		WorldPoint wp = me == null ? null : me.getWorldLocation();
		FarmingGuide.Position pos = wp == null ? null : new FarmingGuide.Position(wp.getX(), wp.getY(), wp.getPlane(), house.isInHouse());
		FarmingGuide.Objective o = FarmingGuide.guardSpellbook(FarmingGuide.resolve(run, sites, pos, lastPos, lastStop, prefs, catalog, navigation.latest(),
			lastActed), currentBook(cap));
		requestRoute(o, sites, wp, cap, catalog);
		lastPos = pos;
		lastStop = o.getStop();
		lastActed = o.getKind() == FarmingGuide.Kind.ACT ? o.getStop() : null;
		// The one current target (canonical §28.12): the run page and NEXT card lead with the stop the guide is on.
		List<String> current = Collections.emptyList();
		for (FarmingPreparation.SitePlan s : sites)
		{
			if (o.getStop() != null && s.getSite().getName().equals(o.getStop()))
			{
				current = s.getSite().getPatchIds();
			}
		}
		farming.setRunCurrentStop(intent, current);
		if (o.getKind() == FarmingGuide.Kind.NONE)
		{
			return GuidanceModel.OFF;
		}
		WorldPoint target = o.getTarget() == null ? null : new WorldPoint(o.getTarget().x, o.getTarget().y, o.getTarget().plane);
		List<com.osrstelemetry.plugin.farming.FarmingPatchDef> left = patchesLeft(run, sites, o.getStop(), catalog);
		com.osrstelemetry.plugin.farming.FarmingAction act = o.getAction();
		boolean atPatch = act.getTarget().kind == com.osrstelemetry.plugin.farming.FarmingAction.Target.Kind.PATCH;
		List<WorldPoint> walk = walkPath(wp, target, o, o.getKind() == FarmingGuide.Kind.ACT && !atPatch ? Collections.emptyList() : left);
		wpNow = wp;
		act = current(o, act, atPatch, walk, left);
		targets.update(client, act.getTarget(), wp, ticks);
		int[] outline = outline(o.getTransport());
		String progress = run.getCountText() + " · " + run.getElapsedText();
		return new GuidanceModel(GuidanceModel.State.ACTIVE, o.getTitle(), o.getChecklist(), progress, target, walk, outline, o.getKind().name())
			.withAction(act.getLabel(), act.getGuide(), act.getTooltip())
			.withHighlights(targets.objects(), targets.npcs())
			.withFootprints(targets.footprints(), o.getStop() != null && o.getStop().equals(arrivedStop))
			.withSpell(act.getSpellWidget());
	}

	/**
	 * A bird house run's guidance (a Farming run, when one is active, has the guidance instead): the same objective shape,
	 * live routes, walk countdowns, outlines and box as a Farming run, from the bird house guide. Null when no bird house run
	 * is being guided.
	 */
	private GuidanceModel resolveBirdhouses()
	{
		com.osrstelemetry.plugin.hunter.BirdhouseRun r = birdhouses == null ? null : birdhouses.run();
		if (r == null || !r.isActive() || r.isPreparing() || travel.catalog() == null)
		{
			lastBirdPos = null;
			return null;
		}
		Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bank != null && !bank.isHidden())
		{
			return new GuidanceModel(GuidanceModel.State.PAUSED, null, Collections.emptyList(), null, null, Collections.emptyList(), null, null);
		}
		com.osrstelemetry.plugin.hunter.BirdhousePlan plan = birdhouses.plan();
		if (plan == null)
		{
			return GuidanceModel.OFF;
		}
		long now = birdhouses.now();
		AccountCapability cap = account.capability();
		Object owner = cap.getOwner();
		if (owner == null ? routeOwner != null : !owner.equals(routeOwner))
		{
			navigation.reset();
			routeOwner = owner;
		}
		ticks++;
		Player me = client.getLocalPlayer();
		WorldPoint wp = me == null ? null : me.getWorldLocation();
		int[] pos = wp == null ? null : new int[]{wp.getX(), wp.getY(), wp.getPlane()};
		com.osrstelemetry.plugin.hunter.BirdhouseTraps traps = birdhouses.traps();
		FarmingGuide.Objective o = com.osrstelemetry.plugin.hunter.BirdhouseGuide.resolve(r, plan.getStops(), traps, pos, lastBirdPos, lastStop, lastActed,
			navigation.latest(), birdhouses.isOnIsland(), house.isInHouse(), now);
		o = FarmingGuide.guardSpellbook(o, currentBook(cap));
		requestBirdhouseRoute(o, plan, wp, cap);
		lastBirdPos = pos;
		lastStop = o.getStop();
		lastActed = o.getKind() == FarmingGuide.Kind.ACT ? o.getStop() : null;
		if (o.getKind() == FarmingGuide.Kind.NONE)
		{
			return GuidanceModel.OFF;
		}
		WorldPoint target = o.getTarget() == null ? null : new WorldPoint(o.getTarget().x, o.getTarget().y, o.getTarget().plane);
		List<WorldPoint> walk = walkPath(wp, target, o, Collections.emptyList());
		wpNow = wp;
		com.osrstelemetry.plugin.farming.FarmingAction act = current(o, o.getAction(), false, walk, Collections.emptyList());
		targets.update(client, act.getTarget(), wp, ticks);
		int[] c = r.counts(traps, now);
		String progress = c[0] + " of " + (c[2] - c[1]) + " set · " + com.osrstelemetry.plugin.farming.FarmingText.age(Math.max(0, now - r.getStartedAt()));
		return new GuidanceModel(GuidanceModel.State.ACTIVE, o.getTitle(), o.getChecklist(), progress, target, walk, outline(o.getTransport()), o.getKind().name())
			.withAction(act.getLabel(), act.getGuide(), act.getTooltip())
			.withHighlights(targets.objects(), targets.npcs())
			.withFootprints(targets.footprints(), false);
	}

	/** As {@link #requestRoute} for a bird house stop: the stop's route goals, bounded by its journey (on the island: by walking). */
	private void requestBirdhouseRoute(FarmingGuide.Objective o, com.osrstelemetry.plugin.hunter.BirdhousePlan plan, WorldPoint wp, AccountCapability cap)
	{
		if (o.getKind() != FarmingGuide.Kind.TRAVEL || wp == null || o.getStop() == null || ticks - lastRouteRequest < 3 || navigation.isBusy())
		{
			return;
		}
		String title = com.osrstelemetry.plugin.hunter.BirdhouseSpace.stopTitle(o.getStop());
		int origin = com.osrstelemetry.plugin.navigation.NavPoint.pack(wp.getX(), wp.getY(), wp.getPlane());
		com.osrstelemetry.plugin.navigation.LiveRoute l = navigation.latest();
		boolean need = l == null || !title.equals(l.getTarget())
			|| (l.getPath() != null ? l.progress(origin, 4) < 0
			: l.getOutcome() == com.osrstelemetry.plugin.navigation.NavOutcome.DATA_UNAVAILABLE
			|| com.osrstelemetry.plugin.navigation.NavPoint.distance(l.getOrigin(), origin) > 12);
		if (!need)
		{
			return;
		}
		String stopKey = com.osrstelemetry.plugin.hunter.BirdhouseSpace.stopKey(o.getStop());
		com.osrstelemetry.plugin.navigation.RouteTable table = com.osrstelemetry.plugin.navigation.RouteTable.bundled();
		java.util.Map<String, com.osrstelemetry.plugin.navigation.RoutePlanner.Goal> goals = new java.util.LinkedHashMap<>();
		goals.put(stopKey, new com.osrstelemetry.plugin.navigation.RoutePlanner.Goal(table.goals(stopKey), 0));
		int maxCost = NO_JOURNEY_BOUND;
		com.osrstelemetry.plugin.navigation.AccountRouter.Key key = com.osrstelemetry.plugin.navigation.AccountRouter.bundled().live(cap);
		for (com.osrstelemetry.plugin.hunter.BirdhousePlan.StopPlan s : plan.getStops())
		{
			if (s.getStop().equals(o.getStop()) && s.getTransport() != null && !birdhouses.isOnIsland())
			{
				int after = com.osrstelemetry.plugin.farming.FarmingTravel.afterTeleportToStop(stopKey, s.getTransport().getTransport(), key);
				maxCost = after < 0 ? NO_JOURNEY_BOUND : FarmingGuide.TELEPORT_COST + after;
			}
		}
		if (navigation.request(title, origin, goals, com.osrstelemetry.plugin.navigation.NavAccess.of(cap), maxCost))
		{
			lastRouteRequest = ticks;
		}
	}

	/**
	 * The current action as the player stands (canonical §28.13): at a stop, the patch action only once the player is beside
	 * the patch (entered within one tile of the walk's end, left only three tiles away, so the text does not flicker at the
	 * edge), otherwise the walk to it; on a walking leg, the countdown of the walk still to go along the drawn path when the
	 * route gave none. Gardener steps (protection, removal) are taken where the player stands at the stop.
	 */
	/** Each patch varbit's value last tick: a change beside the patch is the game showing the player working it. */
	private final java.util.Map<Integer, Integer> varbitSeen = new java.util.HashMap<>();
	private WorldPoint wpNow;

	/** Whether one of these patches changed this tick while the player stands within 2 tiles of the stop's patch objects. */
	private boolean patchTouched(List<com.osrstelemetry.plugin.farming.FarmingPatchDef> left, int near)
	{
		boolean changed = false;
		for (com.osrstelemetry.plugin.farming.FarmingPatchDef d : left)
		{
			int v = client.getVarbitValue(d.getVarbit());
			Integer before = varbitSeen.put(d.getVarbit(), v);
			changed |= before != null && before != v;
		}
		return changed;
	}

	private com.osrstelemetry.plugin.farming.FarmingAction current(FarmingGuide.Objective o, com.osrstelemetry.plugin.farming.FarmingAction act,
		boolean atPatch, List<WorldPoint> walk, List<com.osrstelemetry.plugin.farming.FarmingPatchDef> left)
	{
		if (o.getKind() == FarmingGuide.Kind.ACT && atPatch)
		{
			int tiles = walk.size();
			int near = footprintDistance(wpNow);
			boolean touched = patchTouched(left, near);
			// Arrival (PatchArrival): beside any of the stop's patch objects, or the game showing the patch change while the
			// player is next to it (harvesting along an allotment); left only 3 tiles from every one of them.
			arrivedStop = com.osrstelemetry.plugin.guidance.PatchArrival.at(o.getStop().equals(arrivedStop), tiles, near, touched) ? o.getStop() : null;
			if (o.getStop().equals(arrivedStop))
			{
				return act;
			}
			java.util.Set<Integer> varbits = new java.util.LinkedHashSet<>();
			java.util.Set<Integer> regions = new java.util.LinkedHashSet<>();
			for (com.osrstelemetry.plugin.farming.FarmingPatchDef d : left)
			{
				varbits.add(d.getVarbit());
				regions.add(d.getOwningRegionId());
			}
			return com.osrstelemetry.plugin.farming.FarmingWording.walkToPatch(varbits, regions, tiles);
		}
		if (o.getKind() != FarmingGuide.Kind.ACT)
		{
			arrivedStop = null;
		}
		if (act.getStage() == com.osrstelemetry.plugin.farming.FarmingAction.Stage.WALK && act.getTilesLeft() < 0 && !walk.isEmpty())
		{
			return act.withTiles(walk.size());
		}
		return act;
	}

	/**
	 * Asks for a live route when the objective is to travel and there is none to follow from here (canonical §28.10): a new
	 * stop, the player off the route, or a teleport since the last search. Goals: the stop, and the entrances of the chosen
	 * journey's network (with the hop and the walk after it still to come). Bounded by the chosen journey's cost, so a
	 * longer walk loses to the teleport rather than being searched without end. At most one search runs; never more often
	 * than every few ticks; all off the client thread.
	 */
	private void requestRoute(FarmingGuide.Objective o, List<FarmingPreparation.SitePlan> sites, WorldPoint wp, AccountCapability cap,
		FarmingPatchCatalog catalog)
	{
		if (o.getKind() != FarmingGuide.Kind.TRAVEL || wp == null || o.getStop() == null || ticks - lastRouteRequest < 3 || navigation.isBusy())
		{
			return;
		}
		int origin = com.osrstelemetry.plugin.navigation.NavPoint.pack(wp.getX(), wp.getY(), wp.getPlane());
		com.osrstelemetry.plugin.navigation.LiveRoute l = navigation.latest();
		// Ask again when: nothing to follow for this stop; the player left the route; or, after a no-walk answer, the player
		// moved somewhere new (a teleport). A proven "walking loses" or "no walk" is not asked again from the same place.
		boolean need = l == null || !o.getStop().equals(l.getTarget())
			|| (l.getPath() != null ? l.progress(origin, 4) < 0
			: l.getOutcome() == com.osrstelemetry.plugin.navigation.NavOutcome.DATA_UNAVAILABLE
			|| com.osrstelemetry.plugin.navigation.NavPoint.distance(l.getOrigin(), origin) > 12);
		if (!need)
		{
			return;
		}
		FarmingPreparation.SitePlan site = null;
		for (FarmingPreparation.SitePlan s : sites)
		{
			if (s.getSite().getName().equals(o.getStop()))
			{
				site = s;
			}
		}
		if (site == null)
		{
			return;
		}
		com.osrstelemetry.plugin.navigation.RouteTable table = com.osrstelemetry.plugin.navigation.RouteTable.bundled();
		String patchId = site.getSite().getPatchIds().get(0);
		java.util.Map<String, com.osrstelemetry.plugin.navigation.RoutePlanner.Goal> goals = new java.util.LinkedHashMap<>();
		String stopKey = com.osrstelemetry.plugin.farming.FarmingTravel.stopKey(catalog, patchId);
		goals.put(stopKey, new com.osrstelemetry.plugin.navigation.RoutePlanner.Goal(table.goals(stopKey), 0));
		// Comparison bound: the whole chosen journey from here (the teleport, then every leg as this account walks it), in the
		// same units as the search (walking tiles; transitions two tiles a tick). No journey: a long bound (walking is all).
		int maxCost = NO_JOURNEY_BOUND;
		com.osrstelemetry.plugin.navigation.NavAccess access = com.osrstelemetry.plugin.navigation.NavAccess.of(cap);
		com.osrstelemetry.plugin.navigation.AccountRouter.Key key = com.osrstelemetry.plugin.navigation.AccountRouter.bundled().live(cap);
		TransportAvailability chosen = site.getTransport();
		if (chosen != null)
		{
			TransportDef d = chosen.getTransport();
			int after = com.osrstelemetry.plugin.farming.FarmingTravel.afterTeleport(catalog, patchId, d, key);
			maxCost = after < 0 ? NO_JOURNEY_BOUND : FarmingGuide.TELEPORT_COST + after;
			if (d.via != null)
			{
				com.osrstelemetry.plugin.navigation.RouteTable.Walk arrive = com.osrstelemetry.plugin.farming.FarmingTravel.walk(catalog, patchId, d, key);
				int remaining = FarmingGuide.HOP_COST + (arrive == null ? Math.max(0, site.getWalkTiles()) : arrive.getCost());
				for (com.osrstelemetry.plugin.travel.TransportCatalog.NetworkNode n : com.osrstelemetry.plugin.travel.TransportCatalog.nodes())
				{
					if (d.via.network.equals(n.network) && n.id.equals(d.via.node))
					{
						goals.put("node:" + n.id, new com.osrstelemetry.plugin.navigation.RoutePlanner.Goal(table.goals("node:" + n.id), remaining));
					}
				}
			}
		}
		if (navigation.request(o.getStop(), origin, goals, access, maxCost))
		{
			lastRouteRequest = ticks;
		}
	}

	/** The run's unsettled patches at the objective's stop (their varbits identify the patch objects in the scene). */
	private static List<com.osrstelemetry.plugin.farming.FarmingPatchDef> patchesLeft(FarmingRunView run, List<FarmingPreparation.SitePlan> sites, String stop,
		FarmingPatchCatalog catalog)
	{
		List<com.osrstelemetry.plugin.farming.FarmingPatchDef> out = new ArrayList<>();
		for (FarmingPreparation.SitePlan s : sites)
		{
			if (!s.getSite().getName().equals(stop))
			{
				continue;
			}
			for (String id : s.getSite().getPatchIds())
			{
				FarmingRunView.PatchLine l = line(run, id);
				if (l != null && !l.getProgress().getOutcome().isSettled() && catalog.patch(id) != null)
				{
					out.add(catalog.patch(id));
				}
			}
		}
		return out;
	}

	/** Scene facts read every few ticks: closed doors (opened on the way) and the patch objects by their varbit. */
	private int sceneTick = -1;
	private List<LocalWalker.Tile> doors = Collections.emptyList();
	/** Patch objects by varbit: each object's footprint (scene x1, y1, x2, y2) and its region (an allotment is several). */
	private java.util.Map<Integer, List<int[]>> patchObjects = Collections.emptyMap();

	private void readScene(WorldView view)
	{
		if (sceneTick >= 0 && ticks - sceneTick < REPLAN_TICKS)
		{
			return;
		}
		sceneTick = ticks;
		List<LocalWalker.Tile> d = new ArrayList<>();
		java.util.Map<Integer, List<int[]>> p = new java.util.HashMap<>();
		net.runelite.api.Scene scene = view.getScene();
		int plane = view.getPlane();
		net.runelite.api.Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			doors = d;
			patchObjects = p;
			return;
		}
		for (net.runelite.api.Tile[] col : tiles[plane])
		{
			for (net.runelite.api.Tile t : col)
			{
				if (t == null)
				{
					continue;
				}
				net.runelite.api.WallObject wall = t.getWallObject();
				if (wall != null && opens(wall.getId()))
				{
					d.add(new LocalWalker.Tile(t.getSceneLocation().getX(), t.getSceneLocation().getY()));
				}
				for (net.runelite.api.GameObject g : t.getGameObjects())
				{
					if (g == null)
					{
						continue;
					}
					net.runelite.api.ObjectComposition c = client.getObjectDefinition(g.getId());
					if (c != null && c.getVarbitId() > 0 && g.getSceneMinLocation() != null)
					{
						int[] fp = {g.getSceneMinLocation().getX(), g.getSceneMinLocation().getY(), g.getSceneMaxLocation().getX(),
							g.getSceneMaxLocation().getY(), g.getWorldLocation() == null ? -1 : g.getWorldLocation().getRegionID()};
						List<int[]> l = p.computeIfAbsent(c.getVarbitId(), k -> new ArrayList<>(2));
						boolean dup = false;
						for (int[] e : l)
						{
							dup |= java.util.Arrays.equals(e, fp);
						}
						if (!dup)
						{
							l.add(fp);
						}
					}
					else if (c != null && opens(g.getId()))
					{
						d.add(new LocalWalker.Tile(t.getSceneLocation().getX(), t.getSceneLocation().getY()));
					}
				}
			}
		}
		doors = d;
		patchObjects = p;
	}

	private boolean opens(int objectId)
	{
		net.runelite.api.ObjectComposition c = client.getObjectDefinition(objectId);
		if (c == null || c.getActions() == null)
		{
			return false;
		}
		for (String a : c.getActions())
		{
			if ("Open".equals(a))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The walk within the loaded scene, doors treated as openable: to the side of the nearest unfinished patch object at the
	 * stop when it is in the scene (never its blocked centre), otherwise towards the target. Empty when the player is already
	 * beside the patch, when travelling by teleport, or when the target is off this plane or far outside the scene.
	 */
	/** The stop's patch footprints last read (scene coordinates) and the scene base they were read in (x, y, plane). */
	private List<int[]> lastBoxes = Collections.emptyList();
	private int[] lastBase;

	/** The loaded footprints of these patches: their varbit in their own region (the same varbit elsewhere is another patch). */
	private List<int[]> footprints(List<com.osrstelemetry.plugin.farming.FarmingPatchDef> left)
	{
		List<int[]> out = new ArrayList<>();
		for (com.osrstelemetry.plugin.farming.FarmingPatchDef def : left)
		{
			List<int[]> all = patchObjects.getOrDefault(def.getVarbit(), Collections.emptyList());
			List<int[]> own = new ArrayList<>();
			for (int[] b : all)
			{
				if (b[4] == def.getOwningRegionId())
				{
					own.add(b);
				}
			}
			out.addAll(own.isEmpty() ? all : own);
		}
		return out;
	}

	/** Tiles from the player to the nearest of the stop's patch footprints (Chebyshev, to the rectangle), or -1 when unknown. */
	private int footprintDistance(WorldPoint p)
	{
		if (p == null || lastBase == null || lastBoxes.isEmpty() || p.getPlane() != lastBase[2])
		{
			return -1;
		}
		return com.osrstelemetry.plugin.guidance.PatchArrival.distance(p.getX() - lastBase[0], p.getY() - lastBase[1], lastBoxes);
	}

	private List<WorldPoint> walkPath(WorldPoint from, WorldPoint target, FarmingGuide.Objective o, List<com.osrstelemetry.plugin.farming.FarmingPatchDef> left)
	{
		if (from != null && o.getRoute() != null && o.getRoute().getPath() != null)
		{
			List<WorldPoint> routed = routeWalk(from, o);
			if (routed != null)
			{
				return routed;
			}
		}
		if (o.getKind() == FarmingGuide.Kind.ACT && left.isEmpty()
			&& o.getAction().getTarget().kind != com.osrstelemetry.plugin.farming.FarmingAction.Target.Kind.OBJECT)
		{
			// At the stop, a gardener step (removal, protection): the gardener is outlined; no walk is drawn towards the
			// stop's map marker (it pointed the player away from the patch, live 2026-10-22, Gnome Stronghold).
			pathTarget = null;
			path = Collections.emptyList();
			return path;
		}
		if (from == null || target == null || o.getKind() == FarmingGuide.Kind.COMPLETE || o.getTransport() != null || from.getPlane() != target.getPlane()
			|| from.distanceTo2D(target) > 120)
		{
			pathTarget = null;
			path = Collections.emptyList();
			return path;
		}
		boolean onPath = !path.isEmpty() && minDistance(from, path) <= 3;
		if (target.equals(pathTarget) && onPath && ticks % REPLAN_TICKS != 0)
		{
			return trim(from, path);
		}
		WorldView view = client.getTopLevelWorldView();
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		if (maps == null || from.getPlane() >= maps.length || maps[from.getPlane()] == null)
		{
			return Collections.emptyList();
		}
		readScene(view);
		int baseX = view.getBaseX();
		int baseY = view.getBaseY();
		int[][] flags = LocalWalker.withOpenDoors(maps[from.getPlane()].getFlags(), doors);
		LocalWalker.Tile start = new LocalWalker.Tile(from.getX() - baseX, from.getY() - baseY);
		List<LocalWalker.Tile> tiles = null;
		List<int[]> boxes = footprints(left);
		lastBoxes = boxes;
		lastBase = new int[]{baseX, baseY, from.getPlane()};
		if ((o.getKind() == FarmingGuide.Kind.ACT || o.getKind() == FarmingGuide.Kind.TRAVEL) && !boxes.isEmpty())
		{
			// Any interaction position around any of the patches' objects (an allotment is several): beside one is arrival;
			// otherwise the shortest walk to the side of one of them, never to its blocked centre.
			for (int[] b : boxes)
			{
				if (LocalWalker.beside(start, new LocalWalker.Tile(b[0], b[1]), new LocalWalker.Tile(b[2], b[3])))
				{
					pathTarget = target;
					path = Collections.emptyList();
					return path; // arrived: nothing left to walk
				}
			}
			for (int[] b : boxes)
			{
				List<LocalWalker.Tile> t = LocalWalker.pathToObject(flags, start, new LocalWalker.Tile(b[0], b[1]), new LocalWalker.Tile(b[2], b[3]));
				if (!t.isEmpty() && (tiles == null || t.size() < tiles.size()))
				{
					tiles = t;
				}
			}
		}
		if (tiles == null || tiles.isEmpty())
		{
			tiles = LocalWalker.path(flags, start, new LocalWalker.Tile(target.getX() - baseX, target.getY() - baseY),
				o.getKind() == FarmingGuide.Kind.ACT ? 6 : 3);
		}
		List<WorldPoint> out = new ArrayList<>();
		for (LocalWalker.Tile t : tiles)
		{
			out.add(new WorldPoint(t.x + baseX, t.y + baseY, from.getPlane()));
		}
		pathTarget = target;
		path = out;
		return out;
	}

	/**
	 * The part of a live route to draw (canonical §28.10): from the player to this step's end (the next transition's start, or
	 * the route's end), only what lies in the loaded scene on the player's plane, refined over the live collision map (doors
	 * openable) towards the farthest such tile. If the live map disagrees, the route's own tiles are drawn: they follow the
	 * world collision map, never a straight line. Null near the end of the route: the patch-object walk takes over.
	 */
	private List<WorldPoint> routeWalk(WorldPoint from, FarmingGuide.Objective o)
	{
		com.osrstelemetry.plugin.navigation.NavPath p = o.getRoute().getPath();
		int a = Math.max(0, o.getRouteFrom());
		int b = Math.min(p.size() - 1, o.getRouteTo());
		boolean last = b == p.size() - 1;
		if (last && b - a <= 25)
		{
			return null;
		}
		WorldView view = client.getTopLevelWorldView();
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		int baseX = view == null ? 0 : view.getBaseX();
		int baseY = view == null ? 0 : view.getBaseY();
		List<WorldPoint> world = new ArrayList<>();
		for (int i = a; i <= b; i++)
		{
			int t = p.tile(i);
			int x = com.osrstelemetry.plugin.navigation.NavPoint.x(t);
			int y = com.osrstelemetry.plugin.navigation.NavPoint.y(t);
			int z = com.osrstelemetry.plugin.navigation.NavPoint.plane(t);
			if (z != from.getPlane() || x < baseX + 1 || y < baseY + 1 || x >= baseX + 103 || y >= baseY + 103)
			{
				if (!world.isEmpty())
				{
					break; // leaves the loaded scene: guide to the last loaded waypoint; the rest loads as the player walks
				}
				continue;
			}
			world.add(new WorldPoint(x, y, z));
		}
		if (world.isEmpty() || maps == null || from.getPlane() >= maps.length || maps[from.getPlane()] == null)
		{
			return world;
		}
		readScene(view);
		int[][] flags = LocalWalker.withOpenDoors(maps[from.getPlane()].getFlags(), doors);
		WorldPoint end = world.get(world.size() - 1);
		List<LocalWalker.Tile> local = LocalWalker.path(flags, new LocalWalker.Tile(from.getX() - baseX, from.getY() - baseY),
			new LocalWalker.Tile(end.getX() - baseX, end.getY() - baseY), 0);
		if (local == null || local.isEmpty())
		{
			return world;
		}
		List<WorldPoint> out = new ArrayList<>();
		for (LocalWalker.Tile t : local)
		{
			out.add(new WorldPoint(t.x + baseX, t.y + baseY, from.getPlane()));
		}
		return out;
	}

	private static int minDistance(WorldPoint p, List<WorldPoint> path)
	{
		int best = Integer.MAX_VALUE;
		for (WorldPoint w : path)
		{
			best = Math.min(best, w.distanceTo2D(p));
		}
		return best;
	}

	/** The path from the tile nearest the player onwards. */
	private static List<WorldPoint> trim(WorldPoint p, List<WorldPoint> path)
	{
		int at = 0;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < path.size(); i++)
		{
			int d = path.get(i).distanceTo2D(p);
			if (d < best)
			{
				best = d;
				at = i;
			}
		}
		return path.subList(Math.min(path.size(), at + 1), path.size());
	}

	private static int[] outline(TransportAvailability t)
	{
		if (t == null)
		{
			return new int[0];
		}
		List<Integer> ids = new ArrayList<>();
		for (TransportDef.ItemVariant v : TransportDef.list(t.getTransport().items))
		{
			if (v.charges != TransportDef.Charges.DEPLETED)
			{
				ids.add(v.id);
			}
		}
		for (TransportDef.RuneCost r : TransportDef.list(t.getTransport().runes))
		{
			ids.add(r.id);
		}
		int[] out = new int[ids.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = ids.get(i);
		}
		return out;
	}

	private static FarmingRunView.PatchLine line(FarmingRunView run, String id)
	{
		if (run == null)
		{
			return null;
		}
		for (FarmingRunView.PatchLine l : run.getLines())
		{
			if (l.getPatchId().equals(id))
			{
				return l;
			}
		}
		return null;
	}

	/** The current spellbook's name, or null when not read (pass 32: guidance never asks for a spell on another book). */
	private static String currentBook(com.osrstelemetry.plugin.account.AccountCapability cap)
	{
		return cap == null || cap.spellbook() == null ? null : cap.spellbook().name();
	}
}
