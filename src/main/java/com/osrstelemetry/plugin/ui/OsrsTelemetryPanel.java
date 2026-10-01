package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.collectors.NpcInteractionTargetCollector;
import com.osrstelemetry.plugin.history.HistoryCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerCoordinator;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The top-level sidebar panel: a compact three-way tab row (Session /
 * Loot / History) over a {@link CardLayout} content area. Current
 * Session is the default/home view.
 *
 * NAVIGATION PATTERN: a plain {@code JPanel} of three flat, borderless
 * {@code JButton}s directly swapping a {@code CardLayout}'s visible
 * card -- the same "simple compact tab-like row of buttons" pattern
 * many real RuneLite multi-view sidebar plugins use, chosen over
 * {@code MaterialTabGroup} since plain Swing components carry no
 * dependency-version risk and are equally standard RuneLite-plugin
 * practice.
 *
 * LIVE UPDATES ARE EVENT-DRIVEN: the session runtime, the Loot Tracker,
 * History and the two price caches only signal "surface X changed" (plain
 * Runnables -- they know nothing about Swing). Every signal goes through
 * one {@link UiRefreshCoalescer}: the first queues ONE EDT pass, later
 * ones before it runs only union into the dirty set. That pass renders
 * the dirty surface if its tab is visible; a hidden tab just stays dirty
 * and renders the moment it is selected. Snapshots are always read fresh
 * at render time (cheap, non-blocking in-memory reads).
 *
 * The ~1 s {@code javax.swing.Timer} remains only for time-derived
 * presentation (elapsed duration, XP/hr and other rates) and as a safety
 * fallback, and it only ever touches the VISIBLE tab. It is not the path
 * by which new XP, loot, progress or state becomes visible.
 *
 * {@link #shutdown()} stops the timer, shuts the coalescer (a pass already
 * queued becomes a no-op) and detaches every listener this panel
 * installed -- it MUST be called from {@code OsrsTelemetryPlugin.shutDown()}.
 */
public final class OsrsTelemetryPanel extends PluginPanel
{
	private static final int POLL_INTERVAL_MILLIS = 1000;

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
	private final CurrentSessionView currentSessionView;
	private final LootTrackerView lootTrackerView;
	private final HistoryView historyView;
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final Timer pollTimer;
	private final UiRefreshCoalescer coalescer;
	/** Surfaces whose on-screen content may be older than the backend's (EDT-confined). */
	private final java.util.Set<UiSurface> staleSurfaces = java.util.EnumSet.noneOf(UiSurface.class);
	private volatile boolean shutdown;

	private JButton sessionTabButton;
	private JButton lootTabButton;
	private JButton historyTabButton;
	// Tracked so a tab button's
	// MouseAdapter hover handler (see tabButton()) can tell, on mouseExited,
	// whether to revert to the selected or unselected style -- see
	// styleTabButton(). Set explicitly in the constructor (not here) to
	// avoid a forward reference to the CARD_SESSION constant declared
	// further down this class.
	private String activeCard;

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
		this.sessionRuntimeCoordinator = sessionRuntimeCoordinator;
		this.lootTrackerCoordinator = lootTrackerCoordinator;
		this.lootTrackerPreferences = lootTrackerPreferences;
		this.historyCoordinator = historyCoordinator;
		this.npcInteractionTargetCollector = npcInteractionTargetCollector;
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
				SwingUtilities.invokeLater(() -> showHistoryDetail(session))));

		setLayout(new BorderLayout());

		add(buildTabRow(), BorderLayout.NORTH);

		cards.add(currentSessionView, CARD_SESSION);
		cards.add(lootTrackerView, CARD_LOOT);
		cards.add(historyView, CARD_HISTORY);
		add(cards, BorderLayout.CENTER);

		coalescer = new UiRefreshCoalescer(SwingUtilities::invokeLater, this::onDirty);
		// Every surface starts dirty: each renders the first time it is shown.
		staleSurfaces.addAll(java.util.EnumSet.allOf(UiSurface.class));

		selectTab(CARD_SESSION);

		// Backend change signals (any thread) -> one coalesced EDT pass.
		sessionRuntimeCoordinator.setCurrentSessionListener(() -> coalescer.markDirty(UiSurface.CURRENT_SESSION));
		lootTrackerCoordinator.setChangeListener(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));
		historyCoordinator.setChangeListener(() -> coalescer.markDirty(UiSurface.HISTORY));
		currentSessionView.setValuationListener(() -> coalescer.markDirty(UiSurface.CURRENT_SESSION));
		lootTrackerView.setValuationListener(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));
		lootTrackerView.setRefreshRequester(() -> coalescer.markDirty(UiSurface.LOOT_TRACKER));
		historyView.setValuationListener(() -> coalescer.markDirty(UiSurface.HISTORY));

		// Time-derived presentation + safety fallback, visible tab only.
		pollTimer = new Timer(POLL_INTERVAL_MILLIS, e -> renderSurface(visibleSurface()));
		pollTimer.setRepeats(true);
		pollTimer.start();
	}

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
	}

	private UiSurface visibleSurface()
	{
		if (CARD_LOOT.equals(activeCard))
		{
			return UiSurface.LOOT_TRACKER;
		}
		return CARD_HISTORY.equals(activeCard) ? UiSurface.HISTORY : UiSurface.CURRENT_SESSION;
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
	private void showHistoryDetail(Session session)
	{
		historyView.showDetail(session);
	}

	/**
	 * A plain flat {@link SpyglassTheme#BACKGROUND_PANEL} bar with a thin
	 * {@link SpyglassTheme#BORDER_SOFT} line along the bottom edge --
	 * same compact height, same Session/Loot/History button row, no
	 * gradient/scroll treatment of any kind (avoid gradients that make
	 * the header louder than the content). Applies
	 * identically regardless of which tab is active, so switching tabs
	 * stays visually cohesive -- see {@link #styleTabButton} for the
	 * dark-bronze selected/unselected/hover button colors.
	 */
	private JPanel buildTabRow()
	{
		// Player-facing order/labels: Session | Loot | History.
		JPanel row = new JPanel(new GridLayout(1, 3, 2, 0));
		row.setBackground(SpyglassTheme.BACKGROUND_PANEL);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(4, 4, 3, 4)));

		sessionTabButton = tabButton("Session", CARD_SESSION);
		lootTabButton = tabButton("Loot", CARD_LOOT);
		historyTabButton = tabButton("History", CARD_HISTORY);

		row.add(sessionTabButton);
		row.add(lootTabButton);
		row.add(historyTabButton);
		return row;
	}

	private JButton tabButton(String text, String cardName)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		// Each tab paints a thin border (see styleTabButton()) rather
		// than none at all.
		button.setBorderPainted(true);
		button.setOpaque(true);
		button.setMargin(new java.awt.Insets(2, 2, 2, 2));
		button.addActionListener(e -> selectTab(cardName));
		// Restrained hover feedback. Purely visual: does not affect the
		// click behavior above, and
		// mouseExited always re-derives the correct resting style from
		// whichever tab is actually active (see styleTabButton()) rather
		// than assuming this button's own prior state.
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!cardName.equals(activeCard))
				{
					button.setBackground(SpyglassTheme.SURFACE_RAISED);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				styleTabButton(button, cardName.equals(activeCard));
			}
		});
		return button;
	}

	private void selectTab(String cardName)
	{
		activeCard = cardName;
		UiSurface surface = visibleSurface();
		if (surface == UiSurface.HISTORY && historyCoordinator != null)
		{
			// Opening History re-scans in the background; a changed list
			// arrives through the History change listener.
			historyCoordinator.requestRefresh();
		}
		if (staleSurfaces.contains(surface))
		{
			renderSurface(surface);
		}
		cardLayout.show(cards, cardName);
		styleTabButton(sessionTabButton, CARD_SESSION.equals(cardName));
		styleTabButton(lootTabButton, CARD_LOOT.equals(cardName));
		styleTabButton(historyTabButton, CARD_HISTORY.equals(cardName));
	}

	/**
	 * INACTIVE: {@link SpyglassTheme#BACKGROUND_PANEL} fill,
	 * {@link SpyglassTheme#TEXT_SECONDARY} text, a subtle
	 * {@link SpyglassTheme#BORDER_SOFT} border. SELECTED: a slightly
	 * brighter {@link SpyglassTheme#SURFACE_RAISED} fill, strong
	 * {@link SpyglassTheme#ACCENT_GOLD} text, a stronger
	 * {@link SpyglassTheme#BORDER_PRIMARY} border -- unmistakably
	 * selected, and never a fully-gold fill (gold is reserved for text
	 * emphasis, never the default).
	 */
	private void styleTabButton(JButton button, boolean selected)
	{
		if (button == null)
		{
			return;
		}
		button.setBackground(selected ? SpyglassTheme.SURFACE_RAISED : SpyglassTheme.BACKGROUND_PANEL);
		button.setForeground(selected ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_SECONDARY);
		button.setBorder(BorderFactory.createLineBorder(selected ? SpyglassTheme.BORDER_PRIMARY : SpyglassTheme.BORDER_SOFT));
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
	 * Stops the timer, the coalescer and every backend listener this panel
	 * installed. Must be called from {@code OsrsTelemetryPlugin.shutDown()}
	 * -- see class javadoc.
	 */
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
}
