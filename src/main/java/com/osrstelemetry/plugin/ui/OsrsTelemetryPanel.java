package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.collectors.NpcInteractionTargetCollector;
import com.osrstelemetry.plugin.history.HistoryCoordinator;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.loottracker.LootTrackerCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The top-level sidebar panel: a FIXED {@link NavHeader}
 * ({@code [house][Session][Loot][History]}, plus a sub-header on a child page
 * that has one) over ONE owned {@link JScrollPane} holding the pages.
 *
 * STRUCTURE (Home research §2): constructed with {@code PluginPanel(false)},
 * so RuneLite does not wrap this panel in its own scroll pane and the header
 * can stay outside the scrolling content. The scroll pane replicates
 * RuneLite's own wrap exactly -- the pages sit at NORTH of a BorderLayout
 * panel inside a vertical-only {@code JScrollPane}, the page container keeps
 * RuneLite's 225 px preferred width with the same 6 px gutter (213 px of
 * content), and it is NOT {@code Scrollable} -- so width, wrapping and
 * mouse-wheel speed are what the views were built against. There is still
 * exactly one scroll pane: no view adds its own, and LootTrackerView's
 * {@code findEnclosingScrollPane()} finds this one.
 *
 * PAGES: Home, Farming (Home's child), Current Session, Loot, History (which
 * owns its own list/detail switch). Only the visible page counts towards the
 * scroll height (see {@link VisibleCardLayout}), so a short page never scrolls
 * into the blank height of a taller hidden one.
 *
 * NAVIGATION: the rules are {@link Navigator}'s; the route is a
 * {@link NavRoute}. The current route is kept across sidebar close/reopen (the
 * panel is not rebuilt) and persisted through a hidden config value (see
 * {@link #attachNavigationStore}) so a restart restores it. No saved route is
 * Home; login and elapsed time never move it. Each route's scroll position is
 * remembered for the return trip; the house always lands at the top.
 *
 * LIVE UPDATES ARE EVENT-DRIVEN: the session runtime, the Loot Tracker,
 * History and the two price caches only signal "surface X changed" (plain
 * Runnables -- they know nothing about Swing). Every signal goes through
 * one {@link UiRefreshCoalescer}: the first queues ONE EDT pass, later
 * ones before it runs only union into the dirty set. That pass renders
 * the dirty surface if its page is visible; a hidden page just stays dirty
 * and renders the moment it is shown. Snapshots are always read fresh
 * at render time (cheap, non-blocking in-memory reads). Home and Farming are
 * static shells in this phase and never re-render.
 *
 * The ~1 s {@code javax.swing.Timer} remains only for time-derived
 * presentation (elapsed duration, XP/hr and other rates) and as a safety
 * fallback, and it only ever touches the VISIBLE page. It is not the path
 * by which new XP, loot, progress or state becomes visible.
 *
 * {@link #shutdown()} stops the timer, shuts the coalescer (a pass already
 * queued becomes a no-op) and detaches every listener this panel
 * installed -- it MUST be called from {@code OsrsTelemetryPlugin.shutDown()}.
 */
public final class OsrsTelemetryPanel extends PluginPanel
{
	private static final int POLL_INTERVAL_MILLIS = 1000;
	/** Remembered scroll positions (most recent routes only). */
	private static final int SCROLL_MEMORY = 8;

	private final SessionRuntimeCoordinator sessionRuntimeCoordinator;
	// Read fresh at every Loot render -- see LootTrackerCoordinator's own javadoc for why its index
	// reference can change (a background rebuild populating it
	// progressively, or an account switch installing a fresh one).
	private final LootTrackerCoordinator lootTrackerCoordinator;
	private final LootTrackerPreferences lootTrackerPreferences;
	// The live source of "which
	// attackable NPC is the player currently targeting" -- read fresh at
	// every Current Session render, like the session snapshot itself. See
	// NpcInteractionTargetCollector#getCurrentNpcName()/getCurrentNpcId().
	private final NpcInteractionTargetCollector npcInteractionTargetCollector;
	// Read at every History render for the cheap cached recent-entries list
	// (see HistoryCoordinator#getRecentEntries()'s "never blocks" contract)
	// and invoked directly for a lazy detail load
	// the instant a History row is actually clicked -- see historyView's
	// onEntryOpened callback below.
	private final HistoryCoordinator historyCoordinator;
	private final HomeView homeView;
	private final FarmingView farmingView;
	private final KingdomView kingdomView = new KingdomView();
	private final DailiesView dailiesView = new DailiesView();
	private final BirdhouseView birdhouseView = new BirdhouseView();
	private HomeSource homeSource = HomeSource.NONE;
	/** The adapter's owner-safe snapshot; logged-out until attached. */
	private Supplier<com.osrstelemetry.plugin.farming.FarmingSnapshot> farmingSource = () -> com.osrstelemetry.plugin.farming.FarmingSnapshot.LOGGED_OUT;
	/** Wall clock for Farming estimates (a seam for tests and renders). */
	java.util.function.LongSupplier farmingClock = System::currentTimeMillis;
	private final CurrentSessionView currentSessionView;
	private final LootTrackerView lootTrackerView;
	private final HistoryView historyView;
	private final NavHeader navHeader;
	private final Navigator navigator = new Navigator();
	private final CardLayout cardLayout = new VisibleCardLayout();
	private final JPanel cards = new PageContainer(cardLayout);
	private final JScrollPane scrollPane;
	private final JPanel scrollRoot;
	private final Map<String, Integer> scrollPositions = new LinkedHashMap<String, Integer>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest)
		{
			return size() > SCROLL_MEMORY;
		}
	};
	private final Timer pollTimer;
	private final UiRefreshCoalescer coalescer;
	/** Surfaces whose on-screen content may be older than the backend's (EDT-confined). */
	private final java.util.Set<UiSurface> staleSurfaces = java.util.EnumSet.noneOf(UiSurface.class);
	private volatile boolean shutdown;

	/** Persists the route (hidden config value); null until attached. */
	private Consumer<String> routeWriter;
	private String lastWrittenRoute;

	private static final String CARD_HOME = "home";
	private static final String CARD_FARMING = "farming";
	private static final String CARD_KINGDOM = "kingdom";
	private static final String CARD_DAILIES = "dailies";
	private static final String CARD_BIRDHOUSES = "birdhouses";
	private static final String CARD_APPEARANCE = "appearance";
	private final AppearanceView appearanceView = new AppearanceView();
	private Appearance.Store appearanceStore;
	private static final String CARD_SESSION = "session";
	private static final String CARD_LOOT = "loot";
	private static final String CARD_HISTORY = "history";

	public OsrsTelemetryPanel(
		SessionRuntimeCoordinator sessionRuntimeCoordinator,
		LootTrackerCoordinator lootTrackerCoordinator,
		LootTrackerPreferences lootTrackerPreferences,
		HistoryCoordinator historyCoordinator,
		SkillIconManager skillIconManager,
		ItemManager itemManager,
		ClientThread clientThread,
		NpcInteractionTargetCollector npcInteractionTargetCollector,
		OsrsTelemetryConfig config)
	{
		// Not wrapped: this panel owns its scroll pane so the header can stay fixed.
		super(false);
		this.sessionRuntimeCoordinator = sessionRuntimeCoordinator;
		this.lootTrackerCoordinator = lootTrackerCoordinator;
		this.lootTrackerPreferences = lootTrackerPreferences;
		this.historyCoordinator = historyCoordinator;
		this.npcInteractionTargetCollector = npcInteractionTargetCollector;
		this.homeView = new HomeView(itemManager, () -> navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.FARMING),
			() -> navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.KINGDOM),
			() -> navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.DAILIES),
			() -> navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.BIRDHOUSES));
		this.farmingView = new FarmingView(itemManager);
		this.currentSessionView = new CurrentSessionView(skillIconManager, itemManager, clientThread, config);
		// First-use welcome only when the account is loaded and genuinely has
		// nothing tracked yet (cheap reads: cached History list, index size).
		this.currentSessionView.setTrackedDataSupplier(() -> historyCoordinator == null
			|| !historyCoordinator.isInitialScanComplete()
			|| !historyCoordinator.getRecentEntries().isEmpty()
			|| (lootTrackerCoordinator != null && !lootTrackerCoordinator.getIndex().isEmpty()));
		this.lootTrackerView = new LootTrackerView(itemManager, clientThread, config, lootTrackerPreferences);
		// See HistoryView's own class javadoc for why it needs no direct
		// HistoryCoordinator reference -- onEntryOpened here is the ENTIRE
		// bridge: a lazy, off-EDT loadDetail() call whose result is
		// marshalled back onto the EDT (SwingUtilities.invokeLater()) before
		// touching historyView's Swing state, the same obligation every
		// other background-thread callback in this codebase already
		// carries (see HistoryCoordinator.loadDetail()'s own javadoc).
		this.historyView = new HistoryView(skillIconManager, itemManager, clientThread, config, sessionId ->
			historyCoordinator.loadDetail(sessionId, session ->
			{
				java.util.List<com.osrstelemetry.plugin.farming.FarmingRunHistory.Record> runs = farmingRunsFor(session); // still off the EDT
				SwingUtilities.invokeLater(() -> showHistoryDetail(session, runs));
			}));
		this.historyView.setDetailListener(this::onHistoryDetailChanged);
		this.navHeader = new NavHeader(this::onSectionClicked, this::onUp);
		this.navHeader.setOnGear(this::onFarmingGear);
		farmingView.attachCloseSettings(this::closeFarmingSettings);
		farmingView.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "closeSettings");
		farmingView.getActionMap().put("closeSettings", new javax.swing.AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				closeFarmingSettings();
			}
		});

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(navHeader, BorderLayout.NORTH);

		// RuneLite's own gutter, minus the top (the header sits directly above).
		cards.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cards.setBorder(BorderFactory.createEmptyBorder(0, BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET));
		cards.add(homeView, CARD_HOME);
		cards.add(farmingView, CARD_FARMING);
		cards.add(kingdomView, CARD_KINGDOM);
		cards.add(dailiesView, CARD_DAILIES);
		cards.add(birdhouseView, CARD_BIRDHOUSES);
		cards.add(appearanceView, CARD_APPEARANCE);
		homeView.setOnAppearance(() -> navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.APPEARANCE));
		appearanceView.attach(() -> appearanceStore == null ? Appearance.current() : appearanceStore.read(), this::chooseAppearance);
		birdhouseView.setSprites(itemManager == null ? null : (itemId, onLoaded) ->
		{
			net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId);
			image.onLoaded(onLoaded);
			return image;
		});
		homeView.setOnOpenDaily(key ->
		{
			navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.DAILIES);
			dailiesView.focus(key);
		});
		dailiesView.setSprites(itemManager == null ? null : (itemId, onLoaded) ->
		{
			net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId);
			image.onLoaded(onLoaded);
			return image;
		});
		kingdomView.setStacks(itemManager == null ? null : (itemId, quantity, onLoaded) ->
		{
			net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId, quantity, false);
			image.onLoaded(onLoaded);
			return image;
		});
		cards.add(currentSessionView, CARD_SESSION);
		cards.add(lootTrackerView, CARD_LOOT);
		cards.add(historyView, CARD_HISTORY);

		scrollRoot = new JPanel(new BorderLayout());
		scrollRoot.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollRoot.add(cards, BorderLayout.NORTH);
		scrollPane = new JScrollPane(scrollRoot);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(scrollPane, BorderLayout.CENTER);

		coalescer = new UiRefreshCoalescer(SwingUtilities::invokeLater, this::onDirty);
		// Every surface starts dirty: each renders the first time it is shown.
		staleSurfaces.addAll(java.util.EnumSet.allOf(UiSurface.class));

		// No saved route yet: Home (attachNavigationStore() may restore one).
		showRoute(null, navigator.current(), true);

		// Backend change signals (any thread) -> one coalesced EDT pass.
		sessionRuntimeCoordinator.setCurrentSessionListener(() -> coalescer.markDirty(UiSurface.CURRENT_SESSION));
		lootTrackerCoordinator.setChangeListener(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));
		historyCoordinator.setChangeListener(() -> coalescer.markDirty(UiSurface.HISTORY));
		currentSessionView.setValuationListener(() -> coalescer.markDirty(UiSurface.CURRENT_SESSION));
		lootTrackerView.setValuationListener(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));
		lootTrackerView.setRefreshRequester(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));
		historyView.setValuationListener(() -> coalescer.markDirty(UiSurface.HISTORY));

		// Time-derived presentation + safety fallback, visible page only.
		pollTimer = new Timer(POLL_INTERVAL_MILLIS, e -> renderSurface(visibleSurface()));
		pollTimer.setRepeats(true);
		pollTimer.start();
	}

	/**
	 * Restores the persisted route and persists every later change. The value
	 * is navigation preference only (not per account, not run state). Call
	 * once, on the EDT, right after construction. A missing or unreadable value
	 * is Home; a recognised section with an invalid child is that section's root.
	 */
	public void attachNavigationStore(Supplier<String> reader, Consumer<String> writer)
	{
		String saved = null;
		try
		{
			saved = reader == null ? null : reader.get();
		}
		catch (RuntimeException e)
		{
			// Unreadable preference: same as none.
		}
		lastWrittenRoute = saved;
		routeWriter = writer;
		NavRoute previous = navigator.current();
		showRoute(previous, navigator.restore(NavRoute.parse(saved)), true);
	}

	/**
	 * Farming's data source (the capture adapter's owner-safe snapshot) and the
	 * hidden preview-selection preference. Call once, on the EDT.
	 */
	public void attachFarming(Supplier<com.osrstelemetry.plugin.farming.FarmingSnapshot> source, Supplier<String> previewReader,
		Consumer<String> previewWriter)
	{
		farmingSource = source == null ? () -> com.osrstelemetry.plugin.farming.FarmingSnapshot.LOGGED_OUT : source;
		farmingView.attachSelectionStore(previewReader, previewWriter);
		coalescer.markDirty(UiSurface.HOME);
		coalescer.markDirty(UiSurface.FARMING);
	}

	/**
	 * Connects Start Run and the run page to the Farming planner. Without
	 * this, Start explains that Farming isn't connected.
	 */
	public void attachFarmingRuns(com.osrstelemetry.plugin.farming.FarmingRunControls controls)
	{
		if (controls == null)
		{
			farmingView.attachRunActions(null);
			return;
		}
		farmingView.attachRunActions(new FarmingView.RunActions()
		{
			@Override
			public com.osrstelemetry.plugin.farming.FarmingRunStore.StartResult start(com.osrstelemetry.plugin.farming.FarmingRunRequest request)
			{
				return controls.startRun(request);
			}

			@Override
			public void skip(String patchId, boolean skip)
			{
				controls.setSkipped(patchId, skip);
			}

			@Override
			public void skipBin(String binId, boolean skip)
			{
				controls.setBinSkipped(binId, skip);
			}

			@Override
			public void skipProtection(String patchId, boolean skip)
			{
				controls.setProtectionSkipped(patchId, skip);
			}

			@Override
			public void skipCompost(String patchId, boolean skip)
			{
				controls.setCompostSkipped(patchId, skip);
			}

			@Override
			public void resume()
			{
				controls.resumeRun();
			}

			@Override
			public void end()
			{
				controls.endRun();
			}

			@Override
			public void acknowledge()
			{
				controls.acknowledgeRun();
			}

			@Override
			public void refresh()
			{
				renderFarmingNow();
			}

			@Override
			public void closePreparation()
			{
				controls.closeRunPreparation();
			}

			@Override
			public void setBounty(boolean use)
			{
				controls.setRunBounty(use);
			}

			@Override
			public void setCompost(com.osrstelemetry.plugin.farming.FarmingPatchType type, com.osrstelemetry.plugin.farming.FarmingPlanDraft.Compost choice)
			{
				controls.setRunCompost(type, choice);
			}
		});
	}

	/** Guidance (phases I/J): the live objective the run page's next card shows. */
	public void attachGuidance(java.util.function.Supplier<com.osrstelemetry.plugin.guidance.GuidanceModel> source)
	{
		farmingView.attachGuidance(source);
		birdhouseView.attachGuidance(source);
	}

	/** Pass 31: the compost bins' pictures, drawn from the game's models on the client thread. */
	public void attachBinPictures(CompostBinPictures pictures)
	{
		farmingView.setBinPictures(pictures);
	}

	/** Farming correction pass 2: the profile's Farming preferences (read on every render; written on the player's change). */
	public void attachFarmingPreferences(java.util.function.Supplier<com.osrstelemetry.plugin.farming.FarmingPreferences> reader,
		java.util.function.Consumer<com.osrstelemetry.plugin.farming.FarmingPreferences> writer)
	{
		farmingView.attachPreferences(reader, writer);
	}

	/** Farming correction pass: the player-clicked bank filter's state (0 closed / 1 available / 2 active) and toggle. */
	public void attachBankFilter(java.util.function.IntSupplier state, Runnable toggle)
	{
		farmingView.attachBankFilter(state, toggle);
	}

	/** Phase H: the run preparation's live view, and where Find's focus goes (the passive bank outline). */
	public void attachFarmingPreparation(java.util.function.Supplier<com.osrstelemetry.plugin.farming.FarmingPreparationView> source,
		java.util.function.Consumer<int[]> findFocus)
	{
		farmingView.attachPreparation(source, findFocus);
	}

	/** Kingdom and Dailies on Home: a failure to read leaves the cards as they were. */
	private void renderHomeCards()
	{
		try
		{
			long now = farmingClock.getAsLong();
			com.osrstelemetry.plugin.hunter.BirdhouseTraps traps = birdhouseSource.traps();
			com.osrstelemetry.plugin.hunter.BirdhouseRun run = birdhouseSource.run();
			homeView.renderHome(HomeText.kingdomCard(homeSource.kingdom(), homeSource.onIslands(), now),
				HomeText.recurringCard(homeSource.dailies(), homeSource.recurring(), homeSource.loggedIn(), now),
				HomeText.birdhouseCard(traps.summary(now), run, run == null ? null : run.counts(traps, now), now));
		}
		catch (RuntimeException e)
		{
			// keep what is shown
		}
	}

	/** Kingdom's and Dailies' state (the home observer). Call once, on the EDT. */
	public void attachHome(com.osrstelemetry.plugin.home.HomeObserver observer)
	{
		attachHomeSource(observer == null ? HomeSource.NONE : new HomeSource()
		{
			@Override
			public com.osrstelemetry.plugin.home.Dailies dailies()
			{
				return observer.dailies();
			}

			@Override
			public com.osrstelemetry.plugin.home.Kingdom kingdom()
			{
				return observer.kingdom();
			}

			@Override
			public boolean onIslands()
			{
				return observer.isOnIslands();
			}

			@Override
			public boolean loggedIn()
			{
				return observer.isLoggedIn();
			}

			@Override
			public void setTracked(com.osrstelemetry.plugin.home.DailyTask task, boolean on)
			{
				observer.setTracked(task, on);
				coalescer.markDirty(UiSurface.HOME);
			}

			@Override
			public com.osrstelemetry.plugin.home.Recurring recurring()
			{
				return observer.recurring();
			}

			@Override
			public void setShown(com.osrstelemetry.plugin.home.Recurring.Entry entry, boolean on)
			{
				observer.setShown(entry, on);
				coalescer.markDirty(UiSurface.HOME);
			}

			@Override
			public com.osrstelemetry.plugin.account.ChargeKnowledge charges()
			{
				com.osrstelemetry.plugin.account.ChargeKnowledge k = chargeSource.get();
				return k == null ? com.osrstelemetry.plugin.account.ChargeKnowledge.EMPTY : k;
			}
		});
	}

	/** The bird house service (timers and the run page); null-safe. Call once, on the EDT. */
	public void attachBirdhouses(com.osrstelemetry.plugin.BirdhouseService service)
	{
		birdhouseSource = service == null ? BirdhouseSource.NONE : BirdhouseSource.of(service);
		birdhouseView.attach(birdhouseSource);
		coalescer.markDirty(UiSurface.HOME);
	}

	/**
	 * The sidebar's text size and contrast, kept in the plugin's configuration. Applies what is stored now (the roles were
	 * already set before the panel was built; this re-styles if they differ). EDT.
	 */
	public void attachAppearance(Appearance.Store store)
	{
		appearanceStore = store;
		if (store != null)
		{
			applyAppearance(store.read());
		}
		appearanceView.invalidateSignature();
	}

	/** Sets the text roles before the panel is built (so it is built at the player's size and contrast). */
	public static void applyAppearanceRoles(com.osrstelemetry.plugin.SidebarTextSize size, com.osrstelemetry.plugin.SidebarTextContrast contrast)
	{
		Appearance.applyRoles(new Appearance(size, contrast));
	}

	/** The text size and contrast as configuration values (RuneLite's settings and the Home page write the same keys). */
	public void attachAppearanceConfig(java.util.function.Supplier<com.osrstelemetry.plugin.SidebarTextSize> size,
		java.util.function.Supplier<com.osrstelemetry.plugin.SidebarTextContrast> contrast,
		java.util.function.BiConsumer<com.osrstelemetry.plugin.SidebarTextSize, com.osrstelemetry.plugin.SidebarTextContrast> write)
	{
		attachAppearance(new Appearance.Store()
		{
			@Override
			public Appearance read()
			{
				return new Appearance(size.get(), contrast.get());
			}

			@Override
			public void write(Appearance a)
			{
				write.accept(a.getSize(), a.getContrast());
			}
		});
	}

	/** The settings changed (RuneLite's config panel, or this panel's own page): re-apply. EDT. */
	public void appearanceConfigChanged()
	{
		if (appearanceStore != null)
		{
			applyAppearance(appearanceStore.read());
			appearanceView.invalidateSignature();
			coalescer.markDirty(UiSurface.APPEARANCE);
		}
	}

	/** The player chose on the Text size &amp; contrast page: saved, and applied at once (the live preview). */
	private void chooseAppearance(Appearance a)
	{
		if (appearanceStore != null)
		{
			appearanceStore.write(a);
		}
		applyAppearance(a);
	}

	/**
	 * Brings every page to this text size and contrast (EDT): the shared roles, the components on screen, and every view's
	 * cache, so each page rebuilds with the new sizes on its next render. Nothing happens when it is already applied.
	 */
	public void applyAppearance(Appearance a)
	{
		if (a == null || !Appearance.apply(this, a))
		{
			return;
		}
		kingdomView.invalidateSignature();
		dailiesView.invalidateSignature();
		birdhouseView.invalidateSignature();
		appearanceView.invalidateSignature();
		farmingView.invalidateStructure();
		for (UiSurface s : UiSurface.values())
		{
			coalescer.markDirty(s);
		}
		revalidate();
		repaint();
	}

	/** The charge observations (daily uses). */
	public void attachCharges(Supplier<com.osrstelemetry.plugin.account.ChargeKnowledge> charges)
	{
		chargeSource = charges == null ? () -> null : charges;
	}

	private BirdhouseSource birdhouseSource = BirdhouseSource.NONE;
	private Supplier<com.osrstelemetry.plugin.account.ChargeKnowledge> chargeSource = () -> null;

	/** The Farming tutorial's progress and the storage evidence it credits ({bank, seed vault, house} seen). */
	public void attachFarmingTutorial(Supplier<com.osrstelemetry.plugin.farming.FarmingTutorial> reader,
		Consumer<com.osrstelemetry.plugin.farming.FarmingTutorial> writer, Supplier<boolean[]> storage)
	{
		attachFarmingTutorial(reader, writer, storage, () -> Boolean.FALSE);
	}

	/** As above; {@code ranBefore}: the profile has started a Farming run before (null while unknown). */
	public void attachFarmingTutorial(Supplier<com.osrstelemetry.plugin.farming.FarmingTutorial> reader,
		Consumer<com.osrstelemetry.plugin.farming.FarmingTutorial> writer, Supplier<boolean[]> storage, Supplier<Boolean> ranBefore)
	{
		farmingView.attachTutorial(reader, writer, storage, ranBefore);
		coalescer.markDirty(UiSurface.FARMING);
	}

	void attachHomeSource(HomeSource source)
	{
		homeSource = source == null ? HomeSource.NONE : source;
		kingdomView.attach(homeSource);
		dailiesView.attach(homeSource);
		dailiesView.setClock(() -> farmingClock.getAsLong());
		birdhouseView.setClock(() -> farmingClock.getAsLong());
		coalescer.markDirty(UiSurface.HOME);
		coalescer.markDirty(UiSurface.KINGDOM);
		coalescer.markDirty(UiSurface.DAILIES);
	}

	HomeView homeView()
	{
		return homeView;
	}

	/** Re-renders Home / Farming from fresh state at once (after a run action). */
	void renderFarmingNow()
	{
		UiSurface surface = visibleSurface();
		if (surface == UiSurface.FARMING || surface == UiSurface.HOME)
		{
			renderSurface(surface);
		}
		else
		{
			coalescer.markDirty(UiSurface.HOME);
			coalescer.markDirty(UiSurface.FARMING);
		}
	}

	private com.osrstelemetry.plugin.farming.FarmingModel farmingModel()
	{
		com.osrstelemetry.plugin.farming.FarmingSnapshot snapshot;
		try
		{
			snapshot = farmingSource.get();
		}
		catch (RuntimeException e)
		{
			snapshot = com.osrstelemetry.plugin.farming.FarmingSnapshot.LOGGED_OUT;
		}
		com.osrstelemetry.plugin.farming.FarmingAccountFacts facts;
		try
		{
			facts = farmingFacts == null ? com.osrstelemetry.plugin.farming.FarmingAccountFacts.ALL_OPEN : farmingFacts.get();
		}
		catch (RuntimeException e)
		{
			facts = com.osrstelemetry.plugin.farming.FarmingAccountFacts.NONE;
		}
		return com.osrstelemetry.plugin.farming.FarmingModel.build(com.osrstelemetry.plugin.farming.FarmingPatchCatalog.standard(), snapshot,
			farmingClock.getAsLong(), facts);
	}

	private Supplier<com.osrstelemetry.plugin.farming.FarmingAccountFacts> farmingFacts;

	/** Farming's account facts (patch access, contract, tool leprechaun); without them every patch is offered (tests). */
	public void attachFarmingFacts(Supplier<com.osrstelemetry.plugin.farming.FarmingAccountFacts> facts)
	{
		this.farmingFacts = facts;
	}

	/** The Farming page (tests). */
	FarmingView farmingView()
	{
		return farmingView;
	}

	/** The route on screen (tests, diagnostics). */
	NavRoute currentRoute()
	{
		return navigator.current();
	}

	// -- navigation ------------------------------------------------------------

	private interface ChildAction
	{
		NavRoute apply(NavRoute.Section section, String child);
	}

	private void navigate(ChildAction action, NavRoute.Section section, String child)
	{
		NavRoute previous = navigator.current();
		showRoute(previous, action.apply(section, child), false);
	}

	/** Nav row click (house included). */
	private void onSectionClicked(NavRoute.Section section)
	{
		NavRoute previous = navigator.current();
		NavRoute next = navigator.selectSection(section);
		if (section == NavRoute.Section.HISTORY && historyCoordinator != null)
		{
			// Opening History re-scans in the background; a changed list
			// arrives through the History change listener.
			historyCoordinator.requestRefresh();
		}
		showRoute(previous, next, section == NavRoute.Section.HOME);
	}

	/** The Farming settings gear: open the settings page, or (open) back to Farming. */
	private void onFarmingGear()
	{
		if (NavRoute.FARMING_SETTINGS.equals(navigator.current().getChild()))
		{
			onUp();
			return;
		}
		navigate(navigator::openChild, NavRoute.Section.HOME, NavRoute.FARMING_SETTINGS);
	}

	/** Esc on Farming settings, and the page's own way back (tests). */
	void closeFarmingSettings()
	{
		if (NavRoute.FARMING_SETTINGS.equals(navigator.current().getChild()))
		{
			onUp();
		}
	}

	NavHeader navHeader()
	{
		return navHeader;
	}

	/** "‹ Home" in the sub-header: up to the section root. */
	private void onUp()
	{
		NavRoute previous = navigator.current();
		showRoute(previous, navigator.toParent(), false);
	}

	/** HistoryView opened (id) or closed (null) a detail on its own: keep the route in step. */
	private void onHistoryDetailChanged(String sessionId)
	{
		NavRoute previous = navigator.current();
		if (sessionId != null)
		{
			if (!NavRoute.of(NavRoute.Section.HISTORY, sessionId).equals(previous))
			{
				showRoute(previous, navigator.openChild(NavRoute.Section.HISTORY, sessionId), false);
			}
			return;
		}
		NavRoute next = navigator.childClosed(NavRoute.Section.HISTORY);
		if (!next.equals(previous))
		{
			showRoute(previous, next, false);
		}
	}

	/** EDT: puts {@code next} on screen, keeps History's detail in step, restores scroll and persists. */
	private void showRoute(NavRoute previous, NavRoute next, boolean scrollToTop)
	{
		if (previous != null)
		{
			scrollPositions.put(previous.serialize(), scrollPane.getVerticalScrollBar().getValue());
		}
		UiSurface surface = surfaceFor(next);
		if (staleSurfaces.contains(surface))
		{
			renderSurface(surface);
		}
		farmingView.setSettingsOpen(NavRoute.FARMING_SETTINGS.equals(next.getChild()) && next.getSection() == NavRoute.Section.HOME);
		cardLayout.show(cards, cardFor(next));
		applyPageBackground(next);
		navHeader.showRoute(next);
		syncHistoryDetail();
		NavRoute shown = navigator.current();
		restoreScroll(shown, scrollToTop);
		persist(shown);
	}

	/**
	 * Makes History's list/detail match the route while History is on screen.
	 * A saved detail is shown only once the current account's History has been
	 * scanned and contains it (never another account's); before that it is
	 * kept and the list is shown; after that, an unknown id falls back to the list.
	 */
	private void syncHistoryDetail()
	{
		NavRoute route = navigator.current();
		if (route.getSection() != NavRoute.Section.HISTORY)
		{
			return;
		}
		String wanted = route.getChild();
		String open = historyView.getOpenDetailSessionId();
		if (wanted == null)
		{
			historyView.closeDetail();
			return;
		}
		if (wanted.equals(open))
		{
			return;
		}
		Navigator.HistoryDetailDecision decision = Navigator.decideHistoryDetail(wanted,
			historyCoordinator != null && historyCoordinator.isInitialScanComplete(),
			sessionIds(historyCoordinator == null ? null : historyCoordinator.getRecentEntries()));
		switch (decision)
		{
			case OPEN:
				historyView.openDetail(wanted);
				break;
			case DROP:
				navigator.childClosed(NavRoute.Section.HISTORY);
				historyView.closeDetail();
				navHeader.showRoute(navigator.current());
				persist(navigator.current());
				break;
			case WAIT:
			default:
				break;
		}
	}

	private static List<String> sessionIds(List<HistoryEntry> entries)
	{
		List<String> ids = new ArrayList<>();
		if (entries != null)
		{
			for (HistoryEntry entry : entries)
			{
				ids.add(entry.getSessionId());
			}
		}
		return ids;
	}

	/**
	 * Home and Farming sit on the recessed tile canvas edge to edge; every
	 * other page keeps RuneLite's own gutter colour exactly as before.
	 */
	private void applyPageBackground(NavRoute route)
	{
		java.awt.Color background = route.getSection() == NavRoute.Section.HOME
			? SpyglassTheme.TILE_CANVAS : ColorScheme.DARK_GRAY_COLOR;
		if (!background.equals(cards.getBackground()))
		{
			cards.setBackground(background);
			scrollRoot.setBackground(background);
			scrollPane.getViewport().setBackground(background);
		}
	}

	private void restoreScroll(NavRoute route, boolean toTop)
	{
		Integer saved = toTop ? null : scrollPositions.get(route.serialize());
		int target = saved == null ? 0 : saved;
		scrollPane.validate();
		scrollPane.getVerticalScrollBar().setValue(target);
		if (target > 0)
		{
			// Pages that settle their height over a second layout pass (wrapping labels) get one more try.
			SwingUtilities.invokeLater(() ->
			{
				if (!shutdown && route.equals(navigator.current()))
				{
					scrollPane.getVerticalScrollBar().setValue(target);
				}
			});
		}
	}

	private void persist(NavRoute route)
	{
		String value = route.serialize();
		if (routeWriter == null || value.equals(lastWrittenRoute))
		{
			return;
		}
		lastWrittenRoute = value;
		try
		{
			routeWriter.accept(value);
		}
		catch (RuntimeException e)
		{
			// Navigation memory is best-effort; never break the panel over it.
		}
	}

	private static String cardFor(NavRoute route)
	{
		switch (route.getSection())
		{
			case SESSION:
				return CARD_SESSION;
			case LOOT:
				return CARD_LOOT;
			case HISTORY:
				return CARD_HISTORY;
			case HOME:
			default:
				if (NavRoute.KINGDOM.equals(route.getChild()))
				{
					return CARD_KINGDOM;
				}
				if (NavRoute.DAILIES.equals(route.getChild()))
				{
					return CARD_DAILIES;
				}
				if (NavRoute.BIRDHOUSES.equals(route.getChild()))
				{
					return CARD_BIRDHOUSES;
				}
				if (NavRoute.APPEARANCE.equals(route.getChild()))
				{
					return CARD_APPEARANCE;
				}
				return NavRoute.FARMING.equals(route.getChild()) || NavRoute.FARMING_SETTINGS.equals(route.getChild()) ? CARD_FARMING : CARD_HOME;
		}
	}

	private static UiSurface surfaceFor(NavRoute route)
	{
		switch (route.getSection())
		{
			case SESSION:
				return UiSurface.CURRENT_SESSION;
			case LOOT:
				return UiSurface.LOOT_TRACKER;
			case HISTORY:
				return UiSurface.HISTORY;
			case HOME:
			default:
				if (NavRoute.KINGDOM.equals(route.getChild()))
				{
					return UiSurface.KINGDOM;
				}
				if (NavRoute.DAILIES.equals(route.getChild()))
				{
					return UiSurface.DAILIES;
				}
				if (NavRoute.BIRDHOUSES.equals(route.getChild()))
				{
					return UiSurface.BIRDHOUSES;
				}
				if (NavRoute.APPEARANCE.equals(route.getChild()))
				{
					return UiSurface.APPEARANCE;
				}
				return NavRoute.FARMING.equals(route.getChild()) || NavRoute.FARMING_SETTINGS.equals(route.getChild()) ? UiSurface.FARMING : UiSurface.HOME;
		}
	}

	// -- refresh ---------------------------------------------------------------

	/** EDT: one coalesced pass. Visible dirty surfaces render now; hidden ones stay stale until shown. */
	private void onDirty(java.util.Set<UiSurface> dirty)
	{
		if (shutdown)
		{
			return;
		}
		staleSurfaces.addAll(dirty);
		UiSurface visible = visibleSurface();
		if (staleSurfaces.contains(visible))
		{
			renderSurface(visible);
		}
		if (visible == UiSurface.HISTORY && dirty.contains(UiSurface.HISTORY))
		{
			// A saved detail waiting for this account's History may be decidable now.
			syncHistoryDetail();
		}
	}

	private UiSurface visibleSurface()
	{
		return surfaceFor(navigator.current());
	}

	/** Renders one surface from the newest in-memory state (EDT; no disk, no client thread). */
	private void renderSurface(UiSurface surface)
	{
		staleSurfaces.remove(surface);
		switch (surface)
		{
			case CURRENT_SESSION:
				renderCurrentSession();
				break;
			case LOOT_TRACKER:
				lootTrackerView.render(LootTrackerSnapshot.from(lootTrackerCoordinator.getIndex(), lootTrackerPreferences));
				break;
			case HISTORY:
				// Cached entries only; HistoryView skips an unchanged list itself.
				historyView.render(historyCoordinator.getRecentEntries());
				historyView.refreshDetailValues();
				break;
			case HOME:
				homeView.renderFarming(farmingModel().getHome());
				renderHomeCards();
				break;
			case KINGDOM:
				kingdomView.render(farmingClock.getAsLong());
				break;
			case DAILIES:
				dailiesView.render(farmingClock.getAsLong());
				break;
			case BIRDHOUSES:
				birdhouseView.render(farmingClock.getAsLong());
				break;
			case APPEARANCE:
				appearanceView.render(farmingClock.getAsLong());
				break;
			case FARMING:
				// Cheap (one projection per catalogued patch); the view only rebuilds when its structure changes.
				farmingView.render(farmingModel());
				break;
			default:
				break;
		}
	}

	/**
	 * Routes the History detail-load callback through this instance
	 * method instead of letting the constructor's lambda capture the
	 * {@code historyView} field directly inside that field's own
	 * initializer expression above. javac's definite-assignment
	 * analysis forbids reading a field from within a lambda that is
	 * part of that same field's own initializer -- even though the
	 * lambda itself only ever runs long after construction has
	 * finished (it fires from an async {@code HistoryCoordinator}
	 * callback, marshalled back onto the EDT). This method is never
	 * invoked until after the constructor returns, so {@code
	 * historyView} is always assigned by the time it runs.
	 */
	private void showHistoryDetail(Session session, java.util.List<com.osrstelemetry.plugin.farming.FarmingRunHistory.Record> runs)
	{
		historyView.showDetail(session, runs);
	}

	private volatile java.util.function.Function<Session, java.util.List<com.osrstelemetry.plugin.farming.FarmingRunHistory.Record>> farmingHistory;

	/** Plugin glue: how a Farming session finds its finished runs (account-scoped file reads, background thread). */
	public void attachFarmingHistory(java.util.function.Function<Session, java.util.List<com.osrstelemetry.plugin.farming.FarmingRunHistory.Record>> f)
	{
		farmingHistory = f;
	}

	private java.util.List<com.osrstelemetry.plugin.farming.FarmingRunHistory.Record> farmingRunsFor(Session session)
	{
		java.util.function.Function<Session, java.util.List<com.osrstelemetry.plugin.farming.FarmingRunHistory.Record>> f = farmingHistory;
		if (f == null || !HistoryDetailModel.isFarming(session))
		{
			return java.util.Collections.emptyList();
		}
		try
		{
			return f.apply(session);
		}
		catch (RuntimeException e)
		{
			return java.util.Collections.emptyList();
		}
	}

	private void renderCurrentSession()
	{
		// EDT only, never the RuneLite client thread. The current NPC target
		// is read fresh here too (see npcInteractionTargetCollector).
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			sessionRuntimeCoordinator.getCurrentSessionSnapshot(),
			Instant.now(),
			npcInteractionTargetCollector.getCurrentNpcName(),
			npcInteractionTargetCollector.getCurrentNpcId());
		currentSessionView.render(snapshot);
	}

	/**
	 * Live activity progress for Current Session (e.g. the current Barrows
	 * run). {@code source} is read at render time; call
	 * {@link #onActivityProgressChanged()} when it changes.
	 */
	public void attachBarrowsProgress(java.util.function.Supplier<com.osrstelemetry.plugin.activity.BarrowsRunProgress> source)
	{
		currentSessionView.setBarrowsProgressSupplier(source);
	}

	/** Live Tempoross game for Current Session (HUD + points varbit); same refresh path as Barrows. */
	public void attachTemporossGame(java.util.function.Supplier<com.osrstelemetry.plugin.activity.TemporossGameState> source)
	{
		currentSessionView.setTemporossGameSupplier(source);
	}

	/** Wintertodt companion: live round, tools, Firemaking / Agility XP, playstyle and goal (planning state). */
	public void attachWintertodt(com.osrstelemetry.plugin.wintertodt.WintertodtPanelSource source)
	{
		currentSessionView.setWintertodtSource(source);
	}

	/** Skill companion (first: Mining): live method / production / trip, the target (planning state) and the bank view. */
	public void attachSkillCompanion(com.osrstelemetry.plugin.skills.SkillCompanionPanelSource source)
	{
		currentSessionView.setSkillCompanionSource(source);
	}

	/** Pest Control dashboard: current game, balance, Void ownership, reward-skill XP and the goal (planning state). */
	public void attachPestControl(com.osrstelemetry.plugin.pestcontrol.PestControlPanelSource source)
	{
		currentSessionView.setPestControlSource(source);
	}

	/** Live Guardians of the Rift game for Current Session (HUD script power + energy varbits); same refresh path. */
	public void attachGotrGame(java.util.function.Supplier<com.osrstelemetry.plugin.activity.GotrGameState> source)
	{
		currentSessionView.setGotrGameSupplier(source);
	}

	/** Live Guardians of the Rift reward wallet (stated by the game; null = unknown). */
	public void attachGotrWallet(java.util.function.Supplier<com.osrstelemetry.plugin.activity.GotrRewardWallet> source)
	{
		currentSessionView.setGotrWalletSupplier(source);
	}

	/** Reads / remembers the in-panel GotR Points · XP choice (its own config key; not a Settings item). */
	public void attachGotrStrategy(java.util.function.Supplier<com.osrstelemetry.plugin.GotrStrategy> reader,
		java.util.function.Consumer<com.osrstelemetry.plugin.GotrStrategy> writer)
	{
		currentSessionView.setGotrStrategyReader(reader);
		currentSessionView.setGotrStrategyWriter(writer);
	}

	/** Where the skill cards' second metric (XP remaining or time to level) is saved. */
	public void attachXpMetric(java.util.function.Supplier<String> reader, java.util.function.Consumer<String> writer)
	{
		currentSessionView.setXpMetricStore(reader, writer);
	}

	/** Live native Slayer status for the Current Session dashboard (read at render time). */
	public void attachSlayerStatus(java.util.function.Supplier<com.osrstelemetry.plugin.model.SlayerLiveStatus> source)
	{
		currentSessionView.setSlayerStatusSupplier(source);
	}

	/** Persists the in-session Current Task / All Tasks XP choice (the same config item Settings shows). */
	public void attachSlayerXpScopeWriter(java.util.function.Consumer<com.osrstelemetry.plugin.SlayerXpScope> writer)
	{
		currentSessionView.setXpScopeWriter(writer);
	}

	/** Any thread: the attached activity progress changed -- one coalesced Current Session refresh. */
	public void onActivityProgressChanged()
	{
		coalescer.markDirty(UiSurface.CURRENT_SESSION);
	}

	/**
	 * Stops the timer, the coalescer and every backend listener this panel
	 * installed. Must be called from {@code OsrsTelemetryPlugin.shutDown()}
	 * -- see class javadoc.
	 */
	public void shutdown()
	{
		shutdown = true;
		if (pollTimer.isRunning())
		{
			pollTimer.stop();
		}
		coalescer.shutdown();
		// Backend coordinators outlive this panel: never leave them holding it.
		sessionRuntimeCoordinator.setCurrentSessionListener(null);
		lootTrackerCoordinator.setChangeListener(null);
		historyCoordinator.setChangeListener(null);
	}

	/**
	 * A {@link CardLayout} whose size is the VISIBLE page's, not the largest
	 * page's: switching to a short page (Home) never leaves the scroll range
	 * of a long hidden one (History) behind it.
	 */
	static final class VisibleCardLayout extends CardLayout
	{
		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			synchronized (parent.getTreeLock())
			{
				Insets in = parent.getInsets();
				for (Component c : parent.getComponents())
				{
					if (c.isVisible())
					{
						Dimension d = c.getPreferredSize();
						return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
					}
				}
				return new Dimension(in.left + in.right, in.top + in.bottom);
			}
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return preferredLayoutSize(parent);
		}
	}

	/**
	 * The scrolled page container: RuneLite's own panel width contract (225 px
	 * preferred, i.e. 213 px of content inside the 6 px gutter), exactly what
	 * {@link PluginPanel#getPreferredSize()} gives a wrapped panel.
	 */
	private static final class PageContainer extends JPanel
	{
		PageContainer(CardLayout layout)
		{
			super(layout);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(PANEL_WIDTH, super.getPreferredSize().height);
		}
	}
}
