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
 * LIVE UPDATES: a single {@code javax.swing.Timer} (NOT a raw
 * {@code java.util.Timer}/thread) polls roughly once per second, on
 * the EDT (a Swing {@code Timer}'s
 * listener always runs there), and rebuilds a fresh
 * {@link CurrentSessionSnapshot} from whatever
 * {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()} returns at
 * that instant. The read itself is cheap (a handful of getter calls
 * into a synchronized accessor -- see that method's own javadoc) and
 * never touches the RuneLite client thread; nothing here blocks it.
 * {@link #shutdown()} stops this timer -- it MUST be called from
 * {@code OsrsTelemetryPlugin.shutDown()} so a disabled/re-enabled
 * plugin never accumulates a second live timer.
 */
public final class OsrsTelemetryPanel extends PluginPanel
{
	private static final int POLL_INTERVAL_MILLIS = 1000;

	private final SessionRuntimeCoordinator sessionRuntimeCoordinator;
	// Read fresh on every poll tick, same cadence as the Current Session
	// snapshot -- see LootTrackerCoordinator's own javadoc for why its index
	// reference can change (a background rebuild populating it
	// progressively, or an account switch installing a fresh one).
	private final LootTrackerCoordinator lootTrackerCoordinator;
	private final LootTrackerPreferences lootTrackerPreferences;
	// The live source of "which
	// attackable NPC is the player currently targeting" -- read fresh on
	// every poll tick, same cadence as the session snapshot itself. See
	// NpcInteractionTargetCollector#getCurrentNpcName()/getCurrentNpcId().
	private final NpcInteractionTargetCollector npcInteractionTargetCollector;
	// Read fresh on every poll tick for the cheap recent-entries list (see
	// HistoryCoordinator#getRecentEntries()'s own "never blocks" contract)
	// and invoked directly (off the poll cadence) for a lazy detail load
	// the instant a History row is actually clicked -- see historyView's
	// onEntryOpened callback below.
	private final HistoryCoordinator historyCoordinator;
	private final CurrentSessionView currentSessionView;
	private final LootTrackerView lootTrackerView;
	private final HistoryView historyView;
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final Timer pollTimer;

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
		// The "Re-evaluate
		// Session" button's ENTIRE backend action, built here (where both
		// sessionRuntimeCoordinator and npcInteractionTargetCollector are
		// already available) and handed to CurrentSessionView as a single
		// opaque Runnable -- see that class's own onReEvaluateRequested
		// field javadoc for why it must never see either reference
		// directly. Reads the live current NPC target at the moment the
		// button is actually processed (inside the ClientThread.invokeLater()
		// dispatch, not before) -- getCurrentNpcName()/getCurrentNpcId()
		// are documented safe off the client thread, so reading them here
		// is fine too, but reading them at the point of use keeps this as
		// close to "look at what I am doing RIGHT NOW" as possible. The
		// invokeLater() call itself returns immediately -- this Runnable
		// never blocks whatever thread invokes it (the EDT, via
		// CurrentSessionView's button ActionListener).
		Runnable reEvaluateAction = () -> clientThread.invokeLater(() ->
			sessionRuntimeCoordinator.reEvaluateCurrentSession(
				npcInteractionTargetCollector.getCurrentNpcName(),
				npcInteractionTargetCollector.getCurrentNpcId(),
				Instant.now()));
		this.currentSessionView = new CurrentSessionView(skillIconManager, itemManager, clientThread, config, reEvaluateAction);
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

		selectTab(CARD_SESSION);

		pollTimer = new Timer(POLL_INTERVAL_MILLIS, e -> refreshCurrentSession());
		pollTimer.setRepeats(true);
		pollTimer.start();

		// Populate immediately rather than waiting for the first tick.
		refreshCurrentSession();
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

	private void refreshCurrentSession()
	{
		// This runs on the EDT (Timer listener), never the RuneLite
		// client thread -- see class javadoc's LIVE UPDATES note. The
		// current NPC target is read fresh here too, same cadence as
		// everything else -- see
		// npcInteractionTargetCollector's own field javadoc.
		CurrentSessionSnapshot snapshot = CurrentSessionSnapshot.from(
			sessionRuntimeCoordinator.getCurrentSessionSnapshot(),
			Instant.now(),
			npcInteractionTargetCollector.getCurrentNpcName(),
			npcInteractionTargetCollector.getCurrentNpcId());
		currentSessionView.render(snapshot);

		// Same cadence, same EDT -- LootTrackerCoordinator#getIndex()/
		// LootTrackerPreferences are both cheap, non-blocking, in-memory
		// reads (see their own class javadocs), same as
		// sessionRuntimeCoordinator.getCurrentSessionSnapshot() above.
		LootTrackerSnapshot lootTrackerSnapshot = LootTrackerSnapshot.from(lootTrackerCoordinator.getIndex(), lootTrackerPreferences);
		lootTrackerView.render(lootTrackerSnapshot);

		// Same cadence, same EDT -- HistoryCoordinator#getRecentEntries() never
		// blocks (returns a cached snapshot and kicks off its own background
		// refresh -- see that method's own javadoc), so this is exactly as
		// cheap as the two reads above it. historyView.render() itself
		// additionally skips any Swing rebuild when the entry set is
		// unchanged from what's already on screen (its own render-key
		// mechanism), so a steady-state tick with no newly-finalized session
		// costs one list comparison, nothing more.
		historyView.render(historyCoordinator.getRecentEntries());
	}

	/**
	 * Stops the live-update timer. Must be called from
	 * {@code OsrsTelemetryPlugin.shutDown()} -- see class javadoc.
	 */
	public void shutdown()
	{
		if (pollTimer.isRunning())
		{
			pollTimer.stop();
		}
	}
}
