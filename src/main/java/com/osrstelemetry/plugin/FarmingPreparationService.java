package com.osrstelemetry.plugin;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountCapabilityAdapter;
import com.osrstelemetry.plugin.farming.FarmingCaptureAdapter;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPreparation;
import com.osrstelemetry.plugin.farming.FarmingPreparationView;
import com.osrstelemetry.plugin.farming.FarmingRunIntent;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

/**
 * Plugin glue for Phase H (Bank Preparation, Tier 1): while a run's
 * preparation stage is open, re-evaluates its needs every tick from the
 * current account capability, feeds the run page and the in-bank highlight,
 * and closes the stage automatically when readiness is CONFIRMED and the
 * player closes the bank (D-F2). Advisory only: no click, withdrawal,
 * movement, typed text, script, menu action or widget change is ever made for
 * the player. Find is passive: {@link #focus} only records which item the
 * page asked about; the overlay draws it brighter, and the page explains where
 * it was last seen (reading the open bank's widgets, never changing them).
 */
@Slf4j
@Singleton
public class FarmingPreparationService
{
	private final Client client;
	private final AccountCapabilityAdapter account;
	private final FarmingCaptureAdapter farming;
	private final TravelCapabilityWiring travel;
	private final FarmingPreferenceStore preferences;
	private final FarmingPlanWorker planner;
	LongSupplier clock = System::currentTimeMillis;

	private volatile FarmingPreparationView view;
	private volatile Map<Integer, Long> highlights = Collections.emptyMap();
	private volatile Set<Integer> focus = Collections.emptySet();
	private volatile Set<Integer> filterItems = Collections.emptySet();
	private volatile boolean bankOpen;
	private final com.osrstelemetry.plugin.prep.SetOffDetector setOff = new com.osrstelemetry.plugin.prep.SetOffDetector();

	@Inject
	public FarmingPreparationService(Client client, AccountCapabilityAdapter account, FarmingCaptureAdapter farming, TravelCapabilityWiring travel,
		FarmingPreferenceStore preferences, FarmingPlanWorker planner)
	{
		this.planner = planner;
		this.preferences = preferences;
		this.client = client;
		this.account = account;
		this.farming = farming;
		this.travel = travel;
	}

	/** The open preparation's view (any thread), or null when no run is preparing. */
	public FarmingPreparationView view()
	{
		return view;
	}

	/** Item id -> still to withdraw, for the bank overlay (empty unless preparing with the bank open). */
	public Map<Integer, Long> highlights()
	{
		return bankOpen ? highlights : Collections.emptyMap();
	}

	/** Item ids the player asked to Find (drawn brighter by the overlay); empty for none. */
	public Set<Integer> focus()
	{
		return bankOpen && view != null ? focus : Collections.emptySet();
	}

	/**
	 * Every acceptable item id of a need not yet satisfied (required, recommended
	 * or optional): what the player-clicked bank filter ({@link BankRunFilter})
	 * shows. Empty when no run is preparing.
	 */
	public Set<Integer> filterItems()
	{
		return view != null ? filterItems : Collections.emptySet();
	}

	public void reset()
	{
		filterItems = Collections.emptySet();
		view = null;
		highlights = Collections.emptyMap();
		focus = Collections.emptySet();
		bankOpen = false;
		setOff.reset();
	}

	/**
	 * Plans also refresh this often with no input changed (evidence and charges age), in ticks (30 s); off the client thread.
	 * Every input change (supplies, the run, preferences, the house, route results) asks at once.
	 */
	static final int REFRESH_TICKS = 50;
	private int ticks;

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Widget items = client.getGameState() == GameState.LOGGED_IN ? client.getWidget(InterfaceID.Bankmain.ITEMS) : null;
		boolean open = isShown(items);
		bankOpen = open;
		FarmingSnapshot snapshot = farming.uiSnapshot();
		FarmingRunIntent intent = snapshot.getRun();
		if (snapshot.getState() != FarmingSnapshot.State.AVAILABLE || intent == null || !intent.isPreparing()
			|| intent.getState() != FarmingRunIntent.State.ACTIVE || travel.catalog() == null)
		{
			view = null;
			highlights = Collections.emptyMap();
			focus = Collections.emptySet();
			filterItems = Collections.emptySet();
			setOff.reset();
			return;
		}
		long now = clock.getAsLong();
		AccountCapability cap = account.capability();
		// The plan (thousands of complete journeys evaluated) is computed off the client thread (canonical §28.12): asked for
		// when the account snapshot, the run, the preferences, the house or the account's routes change, and every few ticks
		// (evidence and charges age). Until it arrives, the latest plan for this account and run is used.
		com.osrstelemetry.plugin.farming.FarmingPreferences prefs = preferences == null ? null : preferences.get();
		long routes = com.osrstelemetry.plugin.navigation.AccountRouter.sharedVersion();
		Object house = travel.houseKnowledge();
		FarmingPlanWorker.Key key = new FarmingPlanWorker.Key(cap, intent, prefs, house, snapshot.getKnowledge(), routes, ticks++ / REFRESH_TICKS).withBins(snapshot.getBins());
		planner.request(key, travel.catalog(), FarmingPatchCatalog.standard(), () -> travel.evidence(cap.getOwner(), snapshot, now), now,
			snapshot.getKnowledge());
		FarmingPlanWorker.Result result = planner.latest();
		if (result == null || !result.fits(cap, intent))
		{
			view = null; // "Getting ready": the first plan for this run is on its way
			highlights = Collections.emptyMap();
			filterItems = Collections.emptySet();
			return;
		}
		FarmingPreparation.Plan plan = result.getPlan();
		PreparationState state = PreparationEvaluator.evaluate(plan.getRequest(), cap, now);
		farming.setRunSuggestedOrder(plan.patchOrder()); // the run page and guidance use the same suggested order (written only when it changes)
		FarmingPreparationView next = FarmingPreparationView.of(plan, state, open, open ? visibleItems(items) : null, now);
		view = next;
		highlights = state.bankHighlights();
		filterItems = filterItems(state);
		// D-F2: readiness CONFIRMED (not just supplies carried) and the bank just closed: the player is setting off.
		if (setOff.onTick(next.getReadiness(), open))
		{
			farming.closeRunPreparation();
		}
	}

	/**
	 * "Find" (any thread): remembers which item ids the page asked about, so
	 * the bank overlay outlines them brighter. Nothing else: no client call,
	 * no typed text, no script, no menu action, no tab change. Null or empty
	 * clears it.
	 */
	public void focus(int[] itemIds)
	{
		Set<Integer> next = new HashSet<>();
		if (itemIds != null)
		{
			for (int id : itemIds)
			{
				next.add(id);
			}
		}
		focus = Collections.unmodifiableSet(next);
	}

	static Set<Integer> filterItems(PreparationState state)
	{
		Set<Integer> out = new HashSet<>();
		for (com.osrstelemetry.plugin.prep.NeedResult r : state.getResults())
		{
			if (!r.isSatisfied())
			{
				for (int id : r.getNeed().getAcceptableItemIds())
				{
					out.add(id);
				}
			}
		}
		return Collections.unmodifiableSet(out);
	}

	/** Item ids the open bank view shows (read only; the bank is never changed). */
	static Set<Integer> visibleItems(Widget items)
	{
		Set<Integer> out = new HashSet<>();
		Widget[] children = items == null ? null : items.getChildren();
		if (children == null)
		{
			return out;
		}
		for (Widget w : children)
		{
			if (w != null && !w.isHidden() && w.getItemId() > 0 && w.getItemQuantity() > 0)
			{
				out.add(w.getItemId());
			}
		}
		return out;
	}

	private static boolean isShown(Widget w)
	{
		return w != null && !w.isHidden();
	}
}
