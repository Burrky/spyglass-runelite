package com.osrstelemetry.plugin;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountCapabilityAdapter;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.farming.FarmingCaptureAdapter;
import com.osrstelemetry.plugin.farming.FarmingPreferences;
import com.osrstelemetry.plugin.farming.FarmingRunIntent;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.hunter.BirdhouseChoice;
import com.osrstelemetry.plugin.hunter.BirdhousePlan;
import com.osrstelemetry.plugin.hunter.BirdhouseRun;
import com.osrstelemetry.plugin.hunter.BirdhouseSpace;
import com.osrstelemetry.plugin.hunter.BirdhouseTier;
import com.osrstelemetry.plugin.hunter.BirdhouseTraps;
import com.osrstelemetry.plugin.prep.NeedResult;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.prep.Readiness;
import com.osrstelemetry.plugin.prep.SetOffDetector;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Bird houses (Hunter): the spaces as the game shows them, their timers, and the bird house run (preparation → guidance →
 * completion, on Farming's shared pieces). Client thread for every game read; the UI reads the latest immutable state from
 * any thread. Saved per RuneScape profile (RuneLite's profile configuration: {@code birdhouseTraps}, {@code birdhouseRun})
 * only when something changed.
 *
 * <p>Reads: the four space variables, only while the player stands on Fossil Island's ground floor (where the game sends
 * them; RuneLite's Time Tracking reads them the same way). Nothing is clicked, built or seeded for the player.
 */
@Slf4j
@Singleton
public class BirdhouseService
{
	static final String GROUP = "osrstelemetry";
	static final String TRAPS_KEY = "birdhouseTraps";
	static final String RUN_KEY = "birdhouseRun";
	/** The player's spaces and tier for the next run (pass 30). */
	static final String CHOICE_KEY = "birdhouseChoice";

	/** Where the profile's state is kept: RuneLite's RuneScape-profile configuration (a map in tests). */
	interface ProfileStore
	{
		String get(String key);

		void set(String key, String json);

		void unset(String key);
	}
	/**
	 * Readings start this many ticks after arriving on the island or a scene load (pass 31): until the game has sent the spaces,
	 * they read as bare (live 2026-10-29: all four bare for one tick after a magic mushtree transfer, then the real houses).
	 */
	static final int SETTLE_TICKS = 3;
	/** RuneLite Time Tracking's record of each space ({@code value:epochSeconds}), the evidence that corrects a wrong timer. */
	static final String TIME_TRACKING_GROUP = "timetracking";

	/** Plans also refresh this often with no input changed (charges and evidence age), in ticks. */
	static final int REFRESH_TICKS = 50;

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;
	private final AccountCapabilityAdapter account;
	private final TravelCapabilityWiring travel;
	private final FarmingCaptureAdapter farming;
	private final FarmingPreferenceStore preferences;
	private final Notifier notifier;
	private final OsrsTelemetryConfig config;
	LongSupplier clock = System::currentTimeMillis;

	private volatile BirdhouseTraps traps = BirdhouseTraps.empty();
	private volatile BirdhouseRun run;
	private volatile BirdhouseChoice choice = BirdhouseChoice.empty();
	ProfileStore store;
	private volatile BirdhousePlan plan;
	private volatile PreparationState preparation;
	private volatile boolean bankOpen;
	private volatile Set<Integer> focus = Collections.emptySet();
	private volatile boolean onIsland;
	private final SetOffDetector setOff = new SetOffDetector();
	private int ticks;
	/** Ticks stood on the island since arriving or the last scene load (readings wait for {@link #SETTLE_TICKS}). */
	private int islandTicks;

	private volatile ExecutorService executor;
	private final AtomicLong generation = new AtomicLong();
	private volatile Object[] requested;

	@Inject
	public BirdhouseService(Client client, ConfigManager configManager, Gson gson, AccountCapabilityAdapter account, TravelCapabilityWiring travel,
		FarmingCaptureAdapter farming, FarmingPreferenceStore preferences, Notifier notifier, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
		this.account = account;
		this.travel = travel;
		this.farming = farming;
		this.preferences = preferences;
		this.notifier = notifier;
		this.config = config;
		this.store = new ProfileStore()
		{
			@Override
			public String get(String key)
			{
				return configManager.getRSProfileConfiguration(GROUP, key);
			}

			@Override
			public void set(String key, String json)
			{
				configManager.setRSProfileConfiguration(GROUP, key, json);
			}

			@Override
			public void unset(String key)
			{
				configManager.unsetRSProfileConfiguration(GROUP, key);
			}
		};
	}

	// -- state (any thread) ---------------------------------------------------------------------------------------------------

	public BirdhouseTraps traps()
	{
		return traps;
	}

	/** The current run (active, or finished and not yet dismissed), or null. */
	public BirdhouseRun run()
	{
		return run;
	}

	/** The spaces and tier the player chose for the next run (saved per profile). */
	public BirdhouseChoice choice()
	{
		return choice;
	}

	/** Saves the player's spaces and tier for the next run. */
	public synchronized void choose(BirdhouseChoice next)
	{
		if (next != null && !next.equals(choice))
		{
			choice = next;
			save(CHOICE_KEY, next);
		}
	}

	/** The run's latest plan (preparation and guidance), or null while it is on its way. */
	public BirdhousePlan plan()
	{
		return plan;
	}

	/** The preparation's evaluated needs while preparing, or null. */
	public PreparationState preparation()
	{
		return preparation;
	}

	/** A bank is open now (preparation reads it live). */
	public boolean isBankOpen()
	{
		return bankOpen;
	}

	/** The player stands on Fossil Island's ground floor (the spaces are read live). */
	public boolean isOnIsland()
	{
		return onIsland;
	}

	public boolean isFarmingRunActive()
	{
		FarmingSnapshot s = farming == null ? null : farming.uiSnapshot();
		FarmingRunIntent r = s == null ? null : s.getRun();
		return r != null && r.getState() == FarmingRunIntent.State.ACTIVE;
	}

	/** The account as last captured (levels and inventory for the run page). */
	public AccountCapability capability()
	{
		return account.capability();
	}

	public long now()
	{
		return clock.getAsLong();
	}

	// -- player actions (any thread) ------------------------------------------------------------------------------------------

	/**
	 * Start Run: a new run over these spaces (preparation first). Refused while a Farming run is in progress, and without a tier
	 * when a house must be built (the tier is chosen before Start, never inside preparation).
	 */
	public synchronized boolean start(Set<BirdhouseSpace> spaces, BirdhouseTier tier)
	{
		if (spaces == null || spaces.isEmpty() || isFarmingRunActive() || run != null && run.isActive()
			|| tier == null && BirdhousePlan.tierRequired(spaces, traps, now()))
		{
			return false;
		}
		setRun(BirdhouseRun.start(spaces, tier, traps, now()));
		plan = null;
		preparation = null;
		setOff.reset();
		return true;
	}

	public synchronized void setTier(BirdhouseTier tier)
	{
		if (run != null && run.isActive())
		{
			setRun(run.withTier(tier));
		}
	}

	public synchronized void skip(BirdhouseSpace space, boolean skip)
	{
		if (run != null && run.isActive())
		{
			setRun(run.withSkipped(space, skip).observe(traps, now()));
		}
	}

	/** Begin (preparation closes; guidance starts). */
	public synchronized void begin()
	{
		if (run != null && run.isPreparing())
		{
			setRun(run.begun());
			preparation = null;
		}
	}

	public synchronized void end()
	{
		if (run != null && run.isActive())
		{
			setRun(run.ended(now()));
		}
	}

	/** Done on a finished run: back to the timers. */
	public synchronized void dismiss()
	{
		if (run != null && !run.isActive())
		{
			setRun(null);
			plan = null;
		}
	}

	/** Find: remember which item ids the page asked about (the bank overlay draws them brighter). Nothing else. */
	public void focus(int[] ids)
	{
		Set<Integer> next = new HashSet<>();
		if (ids != null)
		{
			for (int id : ids)
			{
				next.add(id);
			}
		}
		focus = Collections.unmodifiableSet(next);
	}

	/** Item id → still to withdraw (the bank overlay), while preparing with the bank open. */
	public Map<Integer, Long> highlights()
	{
		PreparationState p = preparation;
		return bankOpen && p != null ? p.bankHighlights() : Collections.emptyMap();
	}

	public Set<Integer> focusItems()
	{
		return bankOpen && preparation != null ? focus : Collections.emptySet();
	}

	/** Every acceptable id of a need not yet satisfied (the player-clicked bank filter). */
	public Set<Integer> filterItems()
	{
		PreparationState p = preparation;
		if (p == null)
		{
			return Collections.emptySet();
		}
		Set<Integer> out = new HashSet<>();
		for (NeedResult r : p.getResults())
		{
			if (!r.isSatisfied())
			{
				for (int id : r.getNeed().getAcceptableItemIds())
				{
					out.add(id);
				}
			}
		}
		return out;
	}

	// -- lifecycle ------------------------------------------------------------------------------------------------------------

	public synchronized void start()
	{
		if (executor == null)
		{
			executor = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "spyglass-birdhouse-plan");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
	}

	public synchronized void stop()
	{
		generation.incrementAndGet();
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	public void load()
	{
		traps = read(TRAPS_KEY, BirdhouseTraps.class, BirdhouseTraps.empty());
		run = read(RUN_KEY, BirdhouseRun.class, null);
		choice = read(CHOICE_KEY, BirdhouseChoice.class, BirdhouseChoice.empty());
		plan = null;
		preparation = null;
		requested = null;
		generation.incrementAndGet();
		setOff.reset();
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged e)
	{
		if (e.getGameState() != GameState.LOGGED_IN)
		{
			sceneChanged(); // a scene load (a mushtree, a teleport, a hop) or a logout: the spaces are sent again after it
		}
	}

	/** A scene load or logout: readings wait for the game to send the spaces again. */
	void sceneChanged()
	{
		islandTicks = 0;
	}

	/** One tick on ({@code on}) or off the island: whether the spaces can be read now (settled after arriving or loading). */
	boolean settled(boolean on)
	{
		islandTicks = on ? islandTicks + 1 : 0;
		return on && islandTicks >= SETTLE_TICKS;
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			onIsland = false;
			sceneChanged();
			return;
		}
		long now = now();
		Player me = client.getLocalPlayer();
		WorldPoint wp = me == null ? null : me.getWorldLocation();
		onIsland = wp != null && BirdhouseSpace.onFossilIsland(wp.getRegionID(), wp.getPlane());
		if (settled(onIsland))
		{
			Map<BirdhouseSpace, Integer> values = new EnumMap<>(BirdhouseSpace.class);
			for (BirdhouseSpace s : BirdhouseSpace.values())
			{
				values.put(s, client.getVarpValue(s.getVarp()));
			}
			// Time Tracking's readings only when something may need them: the first settled reading here, or a change.
			Map<BirdhouseSpace, long[]> evidence = islandTicks == SETTLE_TICKS || !values.equals(traps.values()) ? timeTracking() : null;
			observe(values, evidence, now);
		}
		BirdhouseRun r = run;
		if (r != null && r.isActive())
		{
			BirdhouseRun next = r.observe(traps, now);
			if (next != r)
			{
				setRun(next);
			}
		}
		notifyIfReady(now);
		plan(now);
	}

	/** Applies one settled reading (and Time Tracking's evidence, when read) to the spaces; saves only a change. */
	void observe(Map<BirdhouseSpace, Integer> values, Map<BirdhouseSpace, long[]> evidence, long now)
	{
		BirdhouseTraps next = traps.withReading(values, evidence, now);
		if (next != traps)
		{
			traps = next;
			save(TRAPS_KEY, next);
		}
	}

	/** RuneLite Time Tracking's readings of the spaces (space → {value, epoch millis}); absent where it has none. */
	Map<BirdhouseSpace, long[]> timeTracking()
	{
		Map<BirdhouseSpace, long[]> out = new EnumMap<>(BirdhouseSpace.class);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			try
			{
				long[] v = parseTimeTracking(configManager == null ? null
					: configManager.getRSProfileConfiguration(TIME_TRACKING_GROUP, "birdhouse." + s.getVarp()));
				if (v != null)
				{
					out.put(s, v);
				}
			}
			catch (RuntimeException ex)
			{
				log.debug("time tracking reading not available", ex);
			}
		}
		return out;
	}

	/** {@code "21:1793246571"} → {21, 1793246571000}; null when absent or not that shape. */
	static long[] parseTimeTracking(String s)
	{
		if (s == null)
		{
			return null;
		}
		int i = s.indexOf(':');
		if (i <= 0)
		{
			return null;
		}
		try
		{
			return new long[]{Integer.parseInt(s.substring(0, i).trim()), Long.parseLong(s.substring(i + 1).trim()) * 1000L};
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	private void notifyIfReady(long now)
	{
		if (ticks % 5 != 0 || config == null || !config.recurringNotifications() || !traps.shouldNotify(now))
		{
			return;
		}
		BirdhouseTraps next = traps.withNotified(now);
		traps = next;
		save(TRAPS_KEY, next);
		if (notifier != null)
		{
			notifier.notify("Your bird houses are full.");
		}
	}

	/** Plans the active run off the client thread when its inputs change (and every so often), and evaluates preparation. */
	private void plan(long now)
	{
		ticks++;
		BirdhouseRun r = run;
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		bankOpen = items != null && !items.isHidden();
		if (r == null || !r.isActive() || travel.catalog() == null)
		{
			preparation = null;
			return;
		}
		AccountCapability cap = account.capability();
		FarmingPreferences prefs = preferences == null ? null : preferences.get();
		boolean preferHouse = prefs != null && prefs.getTravel() == FarmingPreferences.Travel.HOUSE;
		boolean currentSetup = prefs != null && prefs.getTravel() == FarmingPreferences.Travel.CURRENT;
		Object[] key = {cap, r, traps, preferHouse, currentSetup, travel.houseKnowledge(), com.osrstelemetry.plugin.navigation.AccountRouter.sharedVersion(),
			ticks / REFRESH_TICKS};
		if (!sameKey(key, requested))
		{
			requested = key;
			TransportCatalog catalog = travel.catalog();
			FarmingSnapshot snapshot = farming.uiSnapshot();
			EvidenceSource evidence = travel.evidence(cap.getOwner(), snapshot, now);
			BirdhouseTraps t = traps;
			long gen = generation.get();
			Runnable job = () -> {
				try
				{
					List<TransportAvailability> availability = TransportEvaluator.evaluateAll(catalog, cap, evidence, now);
					BirdhousePlan p = BirdhousePlan.plan(r, t, cap, availability, preferHouse, currentSetup, now);
					if (generation.get() == gen)
					{
						plan = p;
					}
				}
				catch (RuntimeException ex)
				{
					log.debug("bird house plan failed", ex);
				}
			};
			ExecutorService ex = executor;
			if (ex == null)
			{
				job.run();
			}
			else
			{
				try
				{
					ex.execute(job);
				}
				catch (java.util.concurrent.RejectedExecutionException rej)
				{
					requested = null;
				}
			}
		}
		BirdhousePlan p = plan;
		if (r.isPreparing() && p != null)
		{
			PreparationState s = PreparationEvaluator.evaluate(p.getRequest(), cap, now);
			preparation = s;
			Readiness readiness = p.isTierNeeded() || !p.getUnmet().isEmpty() ? Readiness.MISSING
				: s.readiness(p.getUnknown().isEmpty() && !p.getStops().isEmpty() && p.getStops().get(0).getTransport() != null ? 0 : 1);
			if (setOff.onTick(readiness, bankOpen))
			{
				begin();
			}
		}
		else
		{
			preparation = null;
		}
	}

	/** Readiness of the open preparation (null when not preparing or not planned yet). */
	public Readiness readiness()
	{
		BirdhousePlan p = plan;
		PreparationState s = preparation;
		if (p == null || s == null)
		{
			return null;
		}
		if (p.isTierNeeded() || !p.getUnmet().isEmpty())
		{
			return Readiness.MISSING;
		}
		return s.readiness(p.getUnknown().isEmpty() && !p.getStops().isEmpty() && p.getStops().get(0).getTransport() != null ? 0 : 1);
	}

	private static boolean sameKey(Object[] a, Object[] b)
	{
		if (b == null)
		{
			return false;
		}
		for (int i = 0; i < a.length; i++)
		{
			if (a[i] instanceof Boolean || a[i] instanceof Long || a[i] instanceof Integer ? !a[i].equals(b[i]) : a[i] != b[i])
			{
				return false;
			}
		}
		return true;
	}

	private void setRun(BirdhouseRun next)
	{
		run = next;
		if (next == null)
		{
			try
			{
				store.unset(RUN_KEY);
			}
			catch (RuntimeException ex)
			{
				log.debug("bird house run not cleared", ex);
			}
		}
		else
		{
			save(RUN_KEY, next);
		}
	}

	private <T> T read(String key, Class<T> type, T fallback)
	{
		try
		{
			String json = store.get(key);
			T v = json == null || json.isEmpty() ? null : gson.fromJson(json, type);
			return v == null ? fallback : v;
		}
		catch (RuntimeException ex)
		{
			log.debug("{} not readable", key, ex);
			return fallback;
		}
	}

	private void save(String key, Object value)
	{
		try
		{
			store.set(key, gson.toJson(value));
		}
		catch (RuntimeException ex)
		{
			log.debug("{} not saved (no profile yet)", key, ex);
		}
	}
}
