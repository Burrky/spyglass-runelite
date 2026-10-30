package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.loottracker.LootTrackerPreferences;
import com.osrstelemetry.plugin.ui.model.LootTrackerSnapshot;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * The Loot Tracker feature: one card per CANONICAL player-facing source
 * identity (see {@code LootTrackerSnapshot}'s own CANONICAL PLAYER-FACING
 * IDENTITY javadoc), All/Favorites sub-navigation (not a fourth main tab
 * -- both live inside this one "Loot" card), a live case-insensitive item
 * search that filters WITHIN each existing card and never merges
 * different sources together, per-source favorite/collapse/hide-item/
 * reset controls, a grouped-vs-individual-drop view toggle, an
 * always-visible global total-value header, and a per-tab totals footer
 * -- all built from {@link LootTrackerSnapshot} (the merge of
 * {@code LootTrackerIndex} telemetry and {@code LootTrackerPreferences}
 * UI state; see that class's own javadoc).
 *
 * VALUATION/SORT: reuses {@link LootPricing} -- the SAME shared
 * valuation/sort implementation Current Session's own loot section
 * uses (see LootPricing.Priceable's javadoc) -- and the SAME
 * {@link LootGridCell} item-cell widget, so this view's cards look and
 * behave identically to Current Session's loot grid. TOTAL STACK VALUE
 * descending, GE/High Alch per {@code config.lootValuationMode()},
 * exactly like Current Session.
 *
 * THREADING: {@link ItemValuationCache} resolves real prices off the
 * EDT via {@link ClientThread}, exactly like CurrentSessionView's own
 * (see that class's own javadoc) -- this view never calls
 * {@code ItemManager} pricing methods directly.
 *
 * RETAINED CARDS, LOCAL INTERACTION: one {@code SourceCard} per displayed
 * source is kept between renders. {@link #render} (called by
 * OsrsTelemetryPanel when the Loot Tracker is dirty and visible, or as the
 * visible tab's fallback tick) reconciles those cards against a fresh
 * snapshot: a header is rebuilt only when its own data changed, a body
 * only when its prepared content changed, a moved card is re-inserted
 * rather than rebuilt, and nothing else is touched -- there is no
 * whole-tab {@code removeAll()}. A collapsed card owns only its header
 * (no hidden body subtree). Collapsing/opening ONE card, Collapse All /
 * Open All and "Load more" update only the affected cards directly on the
 * EDT (one revalidate/repaint, one preference write) without waiting for,
 * or triggering, a full render. Favorite / hide-item / reset / show-hidden
 * write {@link LootTrackerPreferences} and request one coalesced refresh
 * (see {@link #setRefreshRequester}). View-only state (sub-tab, search,
 * grouped/individual, per-source "Load more" counts, the current search's
 * explicit toggles) is ephemeral; favorites/hidden/collapsed/reset
 * watermarks are persisted via LootTrackerPreferences.
 *
 * RENDER-KEY SKIP: render() first compares a cheap presentation
 * fingerprint (see {@link #computeRenderKey}) with the one on screen
 * ({@link #lastRenderKey}); an unchanged call does no Swing work at all.
 * A resolved price bumps {@link ItemValuationCache#getVersion()}, which
 * changes the fingerprint, but only cards whose values actually changed
 * are then rebuilt.

 * GROUPED vs INDIVIDUAL: Grouped = one retained card per source with its
 * aggregated item totals ("what have I gotten from each source?").
 * Individual = ONE global chronological stream of loot records across all
 * displayed sources, newest first, each record headed by its own source
 * ("what actually dropped, in the order it happened?") -- see
 * {@link #individualStream} / reconcileIndividual().
 *
 * BOUNDED INDIVIDUAL-MODE RENDERING: see
 * {@link #MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED}'s own javadoc -- one hard
 * ceiling on the stream's materialized records, with a single "Load more"
 * at the end of the stream (see {@link #individualRevealCount}) so older
 * drops stay reachable without the tree ever exceeding the bound.
 *
 * SCROLL POSITION: a reconcile that changes the card list captures the
 * viewport's scroll offset first and restores it (clamped) afterwards --
 * including when a card REORDERS due to new activity (see
 * {@code LootTrackerSourceOrdering}); the list is never force-scrolled to
 * the top because something moved. Local card toggles keep the viewport
 * as it is. {@link #restoreScrollPosition} locates RuneLite's outer
 * {@code JScrollPane} dynamically (see {@link #findEnclosingScrollPane})
 * and skips restoration when there is none.
 *
 * WIDTH: {@link #content} is a plain panel added at
 * {@link BorderLayout#CENTER}, which stretches its component to exactly
 * the container's available width regardless of any child's own
 * preferred width -- this is what keeps a long source name or a wide row
 * from silently growing the whole tracker wider than the sidebar. Long
 * source names are additionally truncated at display time (see
 * {@link #truncateSourceName}) so a very long name never crowds out the
 * collapse indicator next to it in the header row. The per-card Reset
 * control lives on a right-click context menu (see
 * {@link #buildResetContextMenu}), not in the header.
 *
 * SCROLLING: this view owns NO {@code JScrollPane} of its own -- unlike
 * an earlier design, {@link #content} is a plain {@code JPanel} (no
 * {@code JScrollPane}, no {@code Scrollable} width-tracking override, no
 * {@code MouseWheelListener} of any kind anywhere in this class) added
 * directly at {@link BorderLayout#CENTER}, between the always-visible
 * {@code toolbar} ({@link BorderLayout#NORTH}) and {@link #totalsFooter}
 * ({@link BorderLayout#SOUTH}). RuneLite's own outer sidebar scrollpane
 * (see {@link OsrsTelemetryPanel} / {@code PluginPanel}) -- the SAME one
 * {@link CurrentSessionView} relies on for its own content -- is the
 * only scrollpane between the player's cursor and any card/cell in this
 * view. A nested scrollpane here (this view wrapping its own
 * {@code JScrollPane} around {@link #content}, itself nested inside
 * RuneLite's outer one) is a documented source of unreliable
 * mouse-wheel delivery in Swing and must not be reintroduced; layering a
 * {@code MouseWheelListener} on top of a nested scrollpane does not
 * reliably fix that class of bug either. This also means there is no
 * width to budget for this view's own inner scrollbar -- see
 * {@link #LOOT_ITEMS_PER_ROW}'s own javadoc for the column-count
 * arithmetic, which accounts only for RuneLite's outer scrollbar.
 *
 * VERTICAL CARD STRETCH: see {@link #heightBoundedPanel}'s own javadoc
 * for the full root-cause analysis and fix -- in short, {@code CardLayout}
 * sizes every tab card to the tallest card's own preferred size (Session/
 * Loot/History share one {@code CardLayout} in {@link OsrsTelemetryPanel}),
 * so any card/header/grid in this class that declares itself willing to
 * grow to fill leftover vertical space ends up stretched to match
 * whichever tab is tallest. Fixed locally, in this class only --
 * {@code CardLayout}'s own cross-card sizing behavior is unchanged.
 */
final class LootTrackerView extends JPanel
{
	/**
	 * Item-grid column count -- deliberately the SAME shared value
	 * Current Session's LOOT card uses ({@link LootGridCell#GRID_COLUMNS};
	 * both build their grids via {@link LootGridCell#newGridLayout()}), for
	 * BOTH Grouped and Individual bodies (see {@link #buildBody}).
	 *
	 * HISTORY: this was once cut from 5 to 3 on a width-budget argument
	 * that treated each cell's 40px PREFERRED size as a hard minimum
	 * ({@code 44n - 4} px must fit ~185px). That clipping was real at the
	 * time, but its actual cause was this view's own inner
	 * {@code JScrollPane}, whose viewport laid content out at its
	 * preferred width. That inner scroll pane has since been removed (see
	 * the class javadoc's SCROLLING note) -- this view now sits under
	 * RuneLite's outer, width-tracking sidebar scroll pane exactly like
	 * CurrentSessionView -- and {@code GridLayout} divides the actual
	 * available width evenly among its columns, so 5 columns render
	 * ~33px cells at normal sidebar width with no clipping and no
	 * horizontal scroll, identical to Current Session. Package-private
	 * so {@code LootTrackerViewTest} can pin it to the shared value.
	 */
	static final int LOOT_ITEMS_PER_ROW = LootGridCell.GRID_COLUMNS;

	/**
	 * The one true ceiling: the Individual view never turns more than this
	 * many loot records into Swing components, however large the retained
	 * history is. INDIVIDUAL is ONE global chronological stream (newest
	 * first) across every displayed source -- see {@link #individualStream}
	 * -- so the bound applies to that stream: the newest
	 * {@link #DEFAULT_INDIVIDUAL_REVEAL} records first, each "Load more"
	 * extending the same stream by {@link #INDIVIDUAL_REVEAL_INCREMENT}
	 * older records, never past this cap.
	 */
	static final int MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED = 500;

	/** Records of the global Individual stream shown before any "Load more" click. */
	static final int DEFAULT_INDIVIDUAL_REVEAL = 50;

	/** How many older records one "Load more" click adds to the global stream. */
	static final int INDIVIDUAL_REVEAL_INCREMENT = 50;

	/** Long source names are truncated at this length (see {@link #truncateSourceName}) so they can never crowd the collapse indicator out of the fixed card width -- see class javadoc's WIDTH note. */
	private static final int MAX_SOURCE_NAME_DISPLAY_CHARS = 20;

	private final ItemManager itemManager;
	private final ItemValuationCache itemValuationCache;
	private final OsrsTelemetryConfig config;
	private final LootTrackerPreferences preferences;

	private final JPanel content = new JPanel();
	private final JButton allTabButton;
	private final JButton favoritesTabButton;
	private final JButton viewModeButton;
	private final JTextField searchField;
	private final JCheckBox showHiddenCheckbox;
	/** Full toolbar width, own preferred height (see the toolbar's alignment note). */
	private final JLabel globalTotalHeader = new JLabel()
	{
		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	};
	private final JLabel totalsFooter = new JLabel();

	private boolean showFavoritesOnly = false;
	private boolean individualView = false;
	private LootTrackerSnapshot lastSnapshot = LootTrackerSnapshot.empty();

	/**
	 * Ephemeral, UI-only length of the global Individual stream the player
	 * has asked to see (grown by "Load more", capped at
	 * {@link #MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED}). Never persisted and
	 * never reset by a Grouped&lt;-&gt;Individual toggle.
	 */
	private int individualRevealCount = DEFAULT_INDIVIDUAL_REVEAL;

	/** The Individual stream's own container and its retained record cards (see reconcileIndividual()). */
	private final JPanel individualStreamPanel = heightBoundedPanel();
	private final Map<String, RecordCard> recordCardsByKey = new HashMap<>();
	private JPanel individualFooter;
	private String individualFooterKey;
	/** Records the Individual stream currently materializes (0 in Grouped). */
	private int individualRecordsMaterialized;

	/**
	 * The presentation fingerprint (see {@link #computeRenderKey}) of
	 * whatever is CURRENTLY on screen -- null until the first real
	 * render. render() recomputes a fresh key on every call and, when it
	 * is {@code equals()} to this field, returns immediately WITHOUT
	 * touching the Swing tree at all.
	 */
	private String lastRenderKey;

	/** Retained cards by entry source key, and the displayed ones in display order (see reconcile()). */
	private final Map<String, SourceCard> cardsBySourceKey = new java.util.LinkedHashMap<>();
	private List<SourceCard> displayedCards = Collections.emptyList();

	/**
	 * Cards the player explicitly collapsed/opened during the CURRENT search
	 * (so the search's temporary expansion no longer applies to them).
	 * Cleared when the search is cleared; never persisted.
	 */
	private final java.util.Set<String> searchOverrideKeys = new java.util.TreeSet<>();

	/** Collapse state for a view built without preferences (tests only). */
	private final Map<String, Boolean> localCollapsedWithoutPreferences = new HashMap<>();

	private JLabel emptyStateLabel;
	private String emptyStateMessage;
	/** ONE state-aware bulk control; see {@link #updateBulkToggle()}. */
	private JButton bulkToggleButton;
	static final String BULK_COLLAPSE_TEXT = "▸ All";
	static final String BULK_OPEN_TEXT = "▾ All";
	private volatile Runnable refreshRequester;

	/**
	 * A plain {@code JPanel} whose maximum height always equals its OWN
	 * current preferred height (width left unbounded, so it can still
	 * stretch to the card's full available width). This is the standard,
	 * minimal {@code BoxLayout.Y_AXIS} idiom for "let this child be
	 * exactly as tall as its content, and no taller."
	 *
	 * WHY THIS EXISTS: every card-shaped panel in this class
	 * (the retained card and its shell, {@code wrapper} in
	 * {@link #buildHeaderRow}, the Individual column and every item
	 * {@code grid} built by {@link #buildBody})
	 * uses this factory instead of an unbounded
	 * {@code setMaximumSize(new Dimension(Integer.MAX_VALUE,
	 * Short.MAX_VALUE))} -- a literal "I am willing to grow to fill ANY
	 * leftover vertical space my BoxLayout parent has to give away."
	 * That matters because Session/Loot/History share one
	 * {@code CardLayout} in {@code OsrsTelemetryPanel}, and
	 * {@code CardLayout}'s own {@code preferredLayoutSize()} (the max
	 * preferred size across ALL of its cards, not just whichever is
	 * showing) hands every card -- including Loot's, whenever IT is the
	 * one showing -- however much height the tallest tab (History) needs.
	 * An unbounded {@code Short.MAX_VALUE} maximum height on a card/
	 * header/grid would let {@link BoxLayout} divide that leftover height
	 * among them, producing huge empty vertical blocks whether a card is
	 * collapsed or expanded (a collapsed card is still just
	 * {@code section} + {@code wrapper}).
	 *
	 * Using this factory everywhere those four panels would otherwise
	 * call {@code setMaximumSize(..., Short.MAX_VALUE)} means none of
	 * them are eligible for that leftover space -- {@code BoxLayout}
	 * leaves it as blank space below the last card instead of dividing
	 * it among the cards, exactly the desired "stack at natural
	 * preferred heights, unused space stays below content" behavior.
	 * {@code getPreferredSize()} is re-evaluated by Swing on every call
	 * (never cached here), so this tracks a card's real preferred
	 * height correctly across collapse/expand, view-mode toggles, and
	 * ordinary content changes -- no hardcoded height anywhere, and no
	 * separate collapsed/expanded height constants.
	 *
	 * Package-private (not {@code private}) so {@code LootTrackerViewTest}
	 * can directly regression-test its dynamic-height behavior -- same
	 * convention as {@link #LOOT_ITEMS_PER_ROW}/{@link #effectivelyCollapsed}
	 * elsewhere in this class.
	 */
	static JPanel heightBoundedPanel()
	{
		return new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
	}

	LootTrackerView(ItemManager itemManager, ClientThread clientThread, OsrsTelemetryConfig config, LootTrackerPreferences preferences)
	{
		this.itemManager = itemManager;
		this.itemValuationCache = new ItemValuationCache(itemManager, clientThread);
		this.config = config;
		this.preferences = preferences;

		setLayout(new BorderLayout());
		setBackground(SpyglassTheme.BACKGROUND_MAIN);

		JPanel toolbar = new JPanel();
		toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
		toolbar.setOpaque(false);
		toolbar.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));

		// ALWAYS the full tracked total -- see class javadoc/computeRenderKey's
		// own note on why this never goes stale on the Favorites tab or
		// while searching. Placed above All/Favorites.
		globalTotalHeader.setFont(SpyglassFonts.bold());
		globalTotalHeader.setForeground(SpyglassTheme.ACCENT_GOLD);
		// Same X alignment as every other toolbar row, stretched to the full
		// row: BoxLayout places a LEFT-aligned child among CENTER-aligned
		// siblings on the shared axis and gives it only the width to the
		// right of it (~60%), which clipped "Total Loot: 64.0M gp" to
		// "Total Loot: 64.0...". The text itself stays left-aligned.
		globalTotalHeader.setAlignmentX(Component.CENTER_ALIGNMENT);
		globalTotalHeader.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 2));
		toolbar.add(globalTotalHeader);

		JPanel subNavRow = new JPanel(new GridLayout(1, 2, 2, 0));
		subNavRow.setOpaque(false);
		allTabButton = subTabButton("All");
		favoritesTabButton = subTabButton("Favorites");
		allTabButton.addActionListener(e -> setShowFavoritesOnly(false));
		favoritesTabButton.addActionListener(e -> setShowFavoritesOnly(true));
		subNavRow.add(allTabButton);
		subNavRow.add(favoritesTabButton);
		toolbar.add(subNavRow);
		toolbar.add(Box.createVerticalStrut(4));

		JPanel searchRow = new JPanel(new BorderLayout(4, 0));
		searchRow.setOpaque(false);
		searchField = new JTextField();
		searchField.setFont(SpyglassFonts.small());
		searchField.setToolTipText("Search loot by item name");
		searchField.setBackground(SpyglassTheme.BACKGROUND_PANEL);
		searchField.setForeground(SpyglassTheme.TEXT_PRIMARY);
		searchField.setCaretColor(SpyglassTheme.TEXT_PRIMARY);
		searchField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(2, 4, 2, 4)));
		searchField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> renderFromLastSnapshot()));
		searchRow.add(searchField, BorderLayout.CENTER);

		viewModeButton = new JButton();
		viewModeButton.setFont(SpyglassFonts.small());
		viewModeButton.setFocusPainted(false);
		viewModeButton.setForeground(SpyglassTheme.TEXT_PRIMARY);
		viewModeButton.setBackground(SpyglassTheme.BACKGROUND_PANEL);
		viewModeButton.setBorder(BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT));
		viewModeButton.addActionListener(e ->
		{
			individualView = !individualView;
			renderFromLastSnapshot();
		});
		searchRow.add(viewModeButton, BorderLayout.EAST);
		toolbar.add(searchRow);
		toolbar.add(Box.createVerticalStrut(4));

		JPanel optionsRow = new JPanel(new BorderLayout(4, 0));
		optionsRow.setOpaque(false);
		showHiddenCheckbox = new JCheckBox("Show hidden");
		showHiddenCheckbox.setFont(SpyglassFonts.small());
		showHiddenCheckbox.setForeground(SpyglassTheme.TEXT_SECONDARY);
		showHiddenCheckbox.setOpaque(false);
		showHiddenCheckbox.setFocusPainted(false);
		showHiddenCheckbox.addActionListener(e ->
		{
			preferences.setShowHidden(showHiddenCheckbox.isSelected());
			requestRefresh();
		});
		optionsRow.add(showHiddenCheckbox, BorderLayout.WEST);

		// Bulk collapse / open: ONE compact state-aware glyph button on the
		// existing options row, matching the cards' own ▸ collapsed / ▾ open
		// indicators. It acts on the DISPLAYED cards; see updateBulkToggle().
		JPanel bulkControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		bulkControls.setOpaque(false);
		bulkToggleButton = bulkButton(BULK_COLLAPSE_TEXT, "Collapse all shown sources");
		bulkToggleButton.addActionListener(e -> setAllCollapsed(!allDisplayedManuallyCollapsed()));
		bulkControls.add(bulkToggleButton);
		optionsRow.add(bulkControls, BorderLayout.EAST);
		toolbar.add(optionsRow);

		add(toolbar, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		content.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));

		// See class javadoc's SCROLLING note -- content is added directly,
		// with no JScrollPane of its own, exactly like CurrentSessionView's
		// own content; RuneLite's outer sidebar scrollpane (the same one
		// Session already relies on) is what scrolls it.
		add(content, BorderLayout.CENTER);

		totalsFooter.setFont(SpyglassFonts.bold());
		totalsFooter.setForeground(SpyglassTheme.ACCENT_GOLD);
		totalsFooter.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
		add(totalsFooter, BorderLayout.SOUTH);

		updateSubNavStyling();
		updateViewModeButtonText();
		render(LootTrackerSnapshot.empty());
	}

	private static JButton bulkButton(String text, String tooltip)
	{
		JButton button = new JButton(text);
		button.setFont(SpyglassFonts.small());
		button.setFocusPainted(false);
		button.setForeground(SpyglassTheme.TEXT_SECONDARY);
		button.setBackground(SpyglassTheme.BACKGROUND_PANEL);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(1, 4, 1, 4)));
		button.setToolTipText(tooltip);
		return button;
	}

	private void setShowFavoritesOnly(boolean favoritesOnly)
	{
		this.showFavoritesOnly = favoritesOnly;
		updateSubNavStyling();
		renderFromLastSnapshot();
	}

	private void updateSubNavStyling()
	{
		styleSubTabButton(allTabButton, !showFavoritesOnly);
		styleSubTabButton(favoritesTabButton, showFavoritesOnly);
	}

	private void updateViewModeButtonText()
	{
		viewModeButton.setText(individualView ? "Individual" : "Grouped");
		viewModeButton.setToolTipText(individualView
			? "Showing individual drops -- click for grouped totals"
			: "Showing grouped totals -- click for individual drops");
	}

	private JButton subTabButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(SpyglassFonts.small());
		button.setFocusPainted(false);
		// Paints a thin border -- see styleSubTabButton().
		button.setBorderPainted(true);
		button.setOpaque(true);
		button.setMargin(new java.awt.Insets(2, 2, 2, 2));
		return button;
	}

	/**
	 * Dark-bronze colors in place of RuneLite's default gray chrome.
	 * Deliberately NOT gold text when selected -- gold is reserved for
	 * totals/headings/highlighted values, which the shared Session|Loot|
	 * History tab row already covers (see
	 * {@code OsrsTelemetryPanel#styleTabButton}); this All/Favorites
	 * sub-nav instead lifts to {@link SpyglassTheme#SURFACE_RAISED} with
	 * a stronger {@link SpyglassTheme#BORDER_PRIMARY} border and plain
	 * {@link SpyglassTheme#TEXT_PRIMARY} text when selected.
	 */
	private void styleSubTabButton(JButton button, boolean selected)
	{
		button.setBackground(selected ? SpyglassTheme.SURFACE_RAISED : SpyglassTheme.BACKGROUND_PANEL);
		button.setForeground(selected ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY);
		button.setBorder(BorderFactory.createLineBorder(selected ? SpyglassTheme.BORDER_PRIMARY : SpyglassTheme.BORDER_SOFT));
	}

	private void renderFromLastSnapshot()
	{
		render(lastSnapshot);
	}

	/**
	 * Called by OsrsTelemetryPanel with a fresh snapshot whenever the Loot
	 * Tracker is dirty and visible (or as the visible tab's low-frequency
	 * fallback), and internally when a view-wide control changes (search
	 * text, sub-tab, grouped/individual). A fingerprint identical to the one
	 * on screen does nothing at all (see class javadoc's RENDER-KEY SKIP);
	 * otherwise {@link #reconcile} updates the RETAINED source cards: only a
	 * card whose header data changed gets a new header, only an expanded
	 * card whose body data changed gets a new body, and cards are moved
	 * rather than rebuilt when ordering changes. There is no whole-tab
	 * removeAll().
	 */
	void render(LootTrackerSnapshot snapshot)
	{
		lastSnapshot = snapshot;
		showHiddenCheckbox.setSelected(snapshot.isShowHidden());
		updateViewModeButtonText();

		String searchText = currentSearchText();
		if (searchText.isEmpty())
		{
			// Temporary search expansion ends with the search; manual state stays.
			searchOverrideKeys.clear();
		}
		List<LootTrackerSnapshot.SourceEntry> sources = showFavoritesOnly ? snapshot.getFavoriteSources() : snapshot.getAllSources();
		List<LootTrackerSnapshot.SourceEntry> allSources = snapshot.getAllSources();

		String key = computeRenderKey(sources, allSources, showFavoritesOnly, individualView, searchText, snapshot.isShowHidden(),
			itemValuationCache.getVersion(), individualRevealCount)
			// GE/HA changes every value, total and sort without changing the
			// data or the price-cache version, so it is part of the key.
			+ "|valuation:" + config.lootValuationMode()
			+ "|overrides:" + searchOverrideKeys;
		if (key.equals(lastRenderKey))
		{
			return;
		}
		lastRenderKey = key;
		reconcile(sources, allSources, searchText);
	}

	private String currentSearchText()
	{
		return searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
	}

	/** Asks the owner for a fresh snapshot (preferences changed); falls back to the next fallback render. */
	private void requestRefresh()
	{
		Runnable requester = refreshRequester;
		if (requester != null)
		{
			requester.run();
		}
	}

	/** Wired by OsrsTelemetryPanel: requests one coalesced Loot refresh with a fresh snapshot. */
	void setRefreshRequester(Runnable requester)
	{
		this.refreshRequester = requester;
	}

	/** Resolved prices request a coalesced refresh instead of waiting for the next timer tick. */
	void setValuationListener(Runnable listener)
	{
		itemValuationCache.setOnResolved(listener);
	}

	/**
	 * Brings the retained card list in line with {@code sources} (already
	 * the active sub-tab's list, most recent first). Pure data preparation
	 * (valuation, sorting, record windows, fingerprints) happens per card
	 * first; Swing is touched only where a fingerprint actually changed,
	 * followed by at most one revalidate/repaint for the whole pass.
	 */
	private void reconcile(List<LootTrackerSnapshot.SourceEntry> sources, List<LootTrackerSnapshot.SourceEntry> allSources, String searchText)
	{
		JScrollPane enclosingScrollPane = findEnclosingScrollPane();
		java.awt.Point scrollPosition = enclosingScrollPane == null ? null : enclosingScrollPane.getViewport().getViewPosition();

		LootValuationMode mode = config.lootValuationMode();
		String modeLabel = modeLabel(mode);

		// ALWAYS the full account-wide total, unconditionally over
		// `allSources` -- never narrowed by search/Favorites-tab, and
		// always including hidden items' value (hidden loot still counts
		// toward every total).
		long globalTotal = 0L;
		for (LootTrackerSnapshot.SourceEntry source : allSources)
		{
			globalTotal += LootPricing.sumTotalValue(LootPricing.valueAndSort(source.getItemTotals(), itemId -> unitValue(itemId, mode)));
		}
		globalTotalHeader.setText("Total Loot: " + QuantityFormatter.quantityToStackSize(globalTotal) + " gp");
		globalTotalHeader.setToolTipText(QuantityFormatter.formatNumber(globalTotal) + " gp (" + modeLabel + ")");

		if (individualView)
		{
			reconcileIndividual(sources, searchText, mode, modeLabel, enclosingScrollPane, scrollPosition);
			return;
		}
		boolean structural = false;
		if (individualStreamPanel.getParent() == content)
		{
			// Leaving Individual: its stream leaves; the retained source cards return below.
			content.remove(individualStreamPanel);
			individualRecordsMaterialized = 0;
			structural = true;
		}

		long grandTotal = 0L;
		List<SourceCard> desired = new ArrayList<>();
		for (LootTrackerSnapshot.SourceEntry source : sources)
		{
			// Totals footer reflects the FULL current tab (never
			// search-narrowed), including hidden items' value.
			long sourceTotal = LootPricing.sumTotalValue(LootPricing.valueAndSort(source.getItemTotals(), itemId -> unitValue(itemId, mode)));
			grandTotal += sourceTotal;
			if (!matchesSearch(source, searchText))
			{
				continue;
			}
			SourceCard card = cardsBySourceKey.get(source.getSourceKey());
			if (card == null)
			{
				card = new SourceCard(source.getSourceKey());
				cardsBySourceKey.put(source.getSourceKey(), card);
			}
			boolean collapsed = isEffectivelyCollapsed(source, searchText);
			structural |= applyCard(card, source, sourceTotal, collapsed, searchText, mode, modeLabel);
			desired.add(card);
		}
		displayedCards = desired;

		// Cards no longer displayed leave; only they are removed.
		java.util.Set<SourceCard> keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		keep.addAll(desired);
		for (java.util.Iterator<SourceCard> it = cardsBySourceKey.values().iterator(); it.hasNext(); )
		{
			SourceCard card = it.next();
			if (!keep.contains(card))
			{
				content.remove(card.shell);
				it.remove();
				structural = true;
			}
		}

		structural |= syncEmptyState(desired.isEmpty(), sources.isEmpty());

		// Order: a card that moved is re-inserted at its new index (only that
		// card); everything already in place is untouched.
		for (int i = 0; i < desired.size(); i++)
		{
			JPanel shell = desired.get(i).shell;
			if (i >= content.getComponentCount() || content.getComponent(i) != shell)
			{
				content.add(shell, i);
				structural = true;
			}
		}

		totalsFooter.setText((showFavoritesOnly ? "Favorites total: " : "Total: ") + QuantityFormatter.quantityToStackSize(grandTotal) + " gp");
		updateBulkToggle();

		if (structural)
		{
			content.revalidate();
			content.repaint();
			restoreScrollPosition(enclosingScrollPane, scrollPosition);
		}
	}

	/** Shows/hides the one empty-state label; true when the content changed. */
	private boolean syncEmptyState(boolean nothingShown, boolean noSources)
	{
		if (nothingShown)
		{
			String message = noSources
				? (showFavoritesOnly ? EMPTY_FAVORITES : EMPTY_NO_LOOT)
				: EMPTY_NO_MATCH;
			if (emptyStateLabel == null || !message.equals(emptyStateMessage))
			{
				if (emptyStateLabel != null)
				{
					content.remove(emptyStateLabel);
				}
				emptyStateLabel = emptyStateLabel(message);
				emptyStateMessage = message;
				content.add(emptyStateLabel);
				return true;
			}
			return false;
		}
		if (emptyStateLabel != null)
		{
			content.remove(emptyStateLabel);
			emptyStateLabel = null;
			emptyStateMessage = null;
			return true;
		}
		return false;
	}

	private static String modeLabel(LootValuationMode mode)
	{
		return mode == LootValuationMode.HIGH_ALCH ? "High Alch" : "Grand Exchange";
	}

	// ------------------------------------------------------------------ INDIVIDUAL

	/**
	 * INDIVIDUAL: ONE global chronological stream of loot records across every
	 * displayed source (the active sub-tab, narrowed to sources the search
	 * matches), newest first -- event order wins over source grouping. Each
	 * record is its own small card headed by its source, so the player reads
	 * "Kraken, Cave kraken, Kraken, Gargoyle ..." exactly as it happened.
	 * Record cards are RETAINED by record identity and rebuilt only when what
	 * they show changes; the retained Grouped source cards are only detached
	 * (state kept) while this view is up. Bounded: at most
	 * {@link #individualRevealCount} (&lt;= the hard cap) records exist.
	 */
	private void reconcileIndividual(List<LootTrackerSnapshot.SourceEntry> sources, String searchText, LootValuationMode mode,
		String modeLabel, JScrollPane enclosingScrollPane, java.awt.Point scrollPosition)
	{
		boolean structural = false;
		for (SourceCard card : cardsBySourceKey.values())
		{
			if (card.shell.getParent() == content)
			{
				content.remove(card.shell);
				structural = true;
			}
		}
		displayedCards = Collections.emptyList();
		if (individualStreamPanel.getParent() != content)
		{
			individualStreamPanel.setLayout(new BoxLayout(individualStreamPanel, BoxLayout.Y_AXIS));
			individualStreamPanel.setOpaque(false);
			individualStreamPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(individualStreamPanel, 0);
			structural = true;
		}

		long grandTotal = 0L;
		List<LootTrackerSnapshot.SourceEntry> matching = new ArrayList<>();
		for (LootTrackerSnapshot.SourceEntry source : sources)
		{
			grandTotal += LootPricing.sumTotalValue(LootPricing.valueAndSort(source.getItemTotals(), itemId -> unitValue(itemId, mode)));
			if (matchesSearch(source, searchText))
			{
				matching.add(source);
			}
		}

		IndividualWindow window = individualStream(matching, individualRevealCount);
		List<RecordCard> desired = new ArrayList<>(window.records.size());
		java.util.Set<String> keep = new java.util.HashSet<>();
		for (StreamRecord record : window.records)
		{
			List<LootTrackerSnapshot.ItemEntry> visible = visibleItems(record.record.getItems(), searchText);
			if (visible.isEmpty())
			{
				continue;
			}
			List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(visible, itemId -> unitValue(itemId, mode));
			StringBuilder fingerprint = new StringBuilder(64).append(modeLabel).append('|')
				.append(record.source.getSourceName()).append('|');
			appendRowsKey(fingerprint, record.source, rows);
			RecordCard card = recordCardsByKey.get(record.key);
			if (card == null)
			{
				card = new RecordCard();
				recordCardsByKey.put(record.key, card);
			}
			if (!fingerprint.toString().equals(card.fingerprint))
			{
				card.rebuild(record, rows, modeLabel);
				card.fingerprint = fingerprint.toString();
				structural = true;
			}
			desired.add(card);
			keep.add(record.key);
		}
		for (java.util.Iterator<Map.Entry<String, RecordCard>> it = recordCardsByKey.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<String, RecordCard> e = it.next();
			if (!keep.contains(e.getKey()))
			{
				individualStreamPanel.remove(e.getValue().shell);
				it.remove();
				structural = true;
			}
		}
		for (int i = 0; i < desired.size(); i++)
		{
			JPanel shell = desired.get(i).shell;
			if (i >= individualStreamPanel.getComponentCount() || individualStreamPanel.getComponent(i) != shell)
			{
				individualStreamPanel.add(shell, i);
				structural = true;
			}
		}
		individualRecordsMaterialized = desired.size();

		String footerKey = window.records.size() < window.totalAvailable
			&& window.records.size() < MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED
			? window.records.size() + "/" + window.totalAvailable : null;
		if (footerKey == null ? individualFooterKey != null : !footerKey.equals(individualFooterKey))
		{
			if (individualFooter != null)
			{
				individualStreamPanel.remove(individualFooter);
			}
			individualFooter = footerKey == null ? null : buildIndividualFooter(window.records.size(), window.totalAvailable);
			individualFooterKey = footerKey;
			structural = true;
		}
		if (individualFooter != null && individualStreamPanel.getComponent(individualStreamPanel.getComponentCount() - 1) != individualFooter)
		{
			individualStreamPanel.remove(individualFooter);
			individualStreamPanel.add(individualFooter);
			structural = true;
		}

		structural |= syncEmptyState(desired.isEmpty(), sources.isEmpty());
		totalsFooter.setText((showFavoritesOnly ? "Favorites total: " : "Total: ") + QuantityFormatter.quantityToStackSize(grandTotal) + " gp");
		updateBulkToggle();
		if (structural)
		{
			individualStreamPanel.revalidate();
			content.revalidate();
			content.repaint();
			restoreScrollPosition(enclosingScrollPane, scrollPosition);
		}
	}

	private JPanel buildIndividualFooter(int shown, int total)
	{
		JPanel footer = heightBoundedPanel();
		footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
		footer.setOpaque(false);
		footer.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel note = new JLabel("Showing most recent " + shown + " of " + total + " drops");
		note.setFont(SpyglassFonts.small());
		note.setForeground(SpyglassTheme.TEXT_SECONDARY);
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		footer.add(note);
		JButton loadMore = new JButton("Load more");
		loadMore.setFont(SpyglassFonts.small());
		loadMore.setFocusPainted(false);
		loadMore.setForeground(SpyglassTheme.TEXT_PRIMARY);
		loadMore.setBackground(SpyglassTheme.BACKGROUND_PANEL);
		loadMore.setBorder(BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT));
		loadMore.setAlignmentX(Component.LEFT_ALIGNMENT);
		loadMore.addActionListener(e ->
		{
			// Extends the SAME stream with older records; newer ones keep their place.
			individualRevealCount = Math.min(MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED, individualRevealCount + INDIVIDUAL_REVEAL_INCREMENT);
			renderFromLastSnapshot();
		});
		footer.add(loadMore);
		footer.add(Box.createVerticalStrut(4));
		return footer;
	}

	/** Records the Individual stream currently materializes (tests). */
	int individualRecordsMaterialized()
	{
		return individualRecordsMaterialized;
	}

	/** One record of the global Individual stream: the source that produced it, the record, and a stable identity. */
	static final class StreamRecord
	{
		final LootTrackerSnapshot.SourceEntry source;
		final LootTrackerSnapshot.RecordEntry record;
		final String key;

		StreamRecord(LootTrackerSnapshot.SourceEntry source, LootTrackerSnapshot.RecordEntry record, String key)
		{
			this.source = source;
			this.record = record;
			this.key = key;
		}

		LootTrackerSnapshot.SourceEntry getSource()
		{
			return source;
		}

		LootTrackerSnapshot.RecordEntry getRecord()
		{
			return record;
		}
	}

	/** The newest window of the global stream plus how many records exist in total. */
	static final class IndividualWindow
	{
		final List<StreamRecord> records;
		final int totalAvailable;

		IndividualWindow(List<StreamRecord> records, int totalAvailable)
		{
			this.records = records;
			this.totalAvailable = totalAvailable;
		}

		List<StreamRecord> getRecords()
		{
			return records;
		}

		int getTotalAvailable()
		{
			return totalAvailable;
		}
	}

	/**
	 * Pure and package-private static for direct unit testing: the newest
	 * {@code limit} records (never more than the hard cap) across
	 * {@code sources}, newest first, by each record's own observedAt -- a
	 * k-way merge from each source's newest end (records are stored
	 * oldest-first), so the cost is O(limit log sources), never a sort of
	 * the whole retained history. Ties keep source display order, then the
	 * source's own order. Each record keeps its source; its key
	 * (sourceKey + time + ordinal among same-time records of that source)
	 * is stable as newer records arrive. Unknown times sort oldest.
	 */
	static IndividualWindow individualStream(List<LootTrackerSnapshot.SourceEntry> sources, int limit)
	{
		int cap = Math.max(0, Math.min(limit, MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED));
		int total = 0;
		final int[] cursor = new int[sources.size()];
		java.util.PriorityQueue<Integer> heads = new java.util.PriorityQueue<>((a, b) ->
		{
			Instant ta = sources.get(a).getRecords().get(cursor[a]).getObservedAt();
			Instant tb = sources.get(b).getRecords().get(cursor[b]).getObservedAt();
			long ma = ta == null ? Long.MIN_VALUE : ta.toEpochMilli();
			long mb = tb == null ? Long.MIN_VALUE : tb.toEpochMilli();
			if (ma != mb)
			{
				return Long.compare(mb, ma);
			}
			if (ta != null && tb != null && ta.getNano() != tb.getNano())
			{
				return Integer.compare(tb.getNano(), ta.getNano());
			}
			return Integer.compare(a, b);
		});
		for (int i = 0; i < sources.size(); i++)
		{
			int size = sources.get(i).getRecords().size();
			total += size;
			cursor[i] = size - 1;
			if (size > 0)
			{
				heads.add(i);
			}
		}
		List<StreamRecord> out = new ArrayList<>(Math.min(cap, total));
		Map<String, Integer> sameTimeOrdinal = new HashMap<>();
		while (out.size() < cap && !heads.isEmpty())
		{
			int i = heads.poll();
			LootTrackerSnapshot.SourceEntry source = sources.get(i);
			LootTrackerSnapshot.RecordEntry record = source.getRecords().get(cursor[i]);
			String base = source.getSourceKey() + "@" + record.getObservedAt();
			int ordinal = sameTimeOrdinal.merge(base, 1, Integer::sum) - 1;
			out.add(new StreamRecord(source, record, base + "#" + ordinal));
			cursor[i]--;
			if (cursor[i] >= 0)
			{
				heads.add(i);
			}
		}
		return new IndividualWindow(out, total);
	}

	static final String RECORD_CARD_NAME = "individualRecord";
	static final String RECORD_SOURCE_NAME = "individualRecordSource";

	private static final java.time.format.DateTimeFormatter RECORD_TIME =
		java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm:ss", Locale.ROOT).withZone(java.time.ZoneId.systemDefault());

	/** Tooltip/secondary text for a record's time ("Unknown time" when absent). Never the headline. */
	static String recordTimeText(Instant observedAt)
	{
		return observedAt == null ? "Unknown time" : RECORD_TIME.format(observedAt);
	}

	/**
	 * One retained Individual record card: "Source ........ value" over that
	 * record's item grid. The source is the headline; the time is in the
	 * tooltip. Right-click keeps the per-source Reset action.
	 */
	private final class RecordCard
	{
		final JPanel shell = heightBoundedPanel();
		final JPanel card = heightBoundedPanel();
		String fingerprint;
		LootTrackerSnapshot.SourceEntry source;

		RecordCard()
		{
			shell.setLayout(new BoxLayout(shell, BoxLayout.Y_AXIS));
			shell.setOpaque(false);
			shell.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.setName(RECORD_CARD_NAME);
			card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
			card.setOpaque(true);
			card.setBackground(SpyglassTheme.SURFACE_CARD);
			card.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
				BorderFactory.createEmptyBorder(4, 7, 4, 7)));
			card.setComponentPopupMenu(buildResetContextMenu(() -> source));
			shell.add(card);
			shell.add(Box.createVerticalStrut(4));
		}

		void rebuild(StreamRecord record, List<LootPricing.ValuedLootRow> rows, String modeLabel)
		{
			source = record.source;
			card.removeAll();
			JPanel header = new JPanel(new BorderLayout(4, 0));
			header.setOpaque(false);
			header.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel title = new WrappingHtmlLabel(record.source.getSourceName(), false);
			title.setName(RECORD_SOURCE_NAME);
			title.setFont(SpyglassFonts.bold());
			title.setForeground(SpyglassTheme.TEXT_PRIMARY);
			title.setToolTipText(record.source.getSourceName() + " -- " + recordTimeText(record.record.getObservedAt()));
			header.add(FarmingRunPage.cell(title), BorderLayout.CENTER);
			JLabel value = new JLabel(QuantityFormatter.quantityToStackSize(LootPricing.sumTotalValue(rows)) + " gp");
			value.setFont(SpyglassFonts.small());
			value.setForeground(SpyglassTheme.ACCENT_GOLD);
			header.add(value, BorderLayout.EAST);
			card.add(HomeDetailPage.growing(header)); // its height follows the wrapped name
			card.add(Box.createVerticalStrut(3));
			card.add(gridOf(rows, modeLabel, record.source));
		}
	}

	/**
	 * Makes one retained card show {@code source}: a new header only when
	 * its header data changed, the body removed when collapsed, and a new
	 * body only when the prepared body's fingerprint changed. Returns true
	 * when any Swing child changed.
	 */
	private boolean applyCard(SourceCard card, LootTrackerSnapshot.SourceEntry source, long sourceTotal, boolean collapsed,
		String searchText, LootValuationMode mode, String modeLabel)
	{
		card.entry = source;
		card.sourceTotal = sourceTotal;
		boolean changed = false;

		String headerKey = source.isFavorite() + "|" + source.getSourceName() + "|" + source.getKillCount() + "|" + sourceTotal
			+ "|" + source.isStructuredReward();
		if (!headerKey.equals(card.headerKey))
		{
			if (card.header != null)
			{
				card.card.remove(card.header);
			}
			card.header = buildHeaderRow(card, source, sourceTotal);
			card.card.add(card.header, 0);
			card.headerKey = headerKey;
			changed = true;
		}
		String indicator = collapsed ? "▸" : "▾";
		if (!indicator.equals(card.indicator.getText()))
		{
			card.indicator.setText(indicator);
		}

		if (collapsed)
		{
			// A collapsed card owns only its header: no hidden body subtree.
			if (card.body != null)
			{
				card.card.remove(card.bodyGap);
				card.card.remove(card.body);
				card.body = null;
				card.bodyKey = null;
				changed = true;
			}
			return changed;
		}

		BodyModel model = prepareBody(source, searchText, mode);
		if (!model.key.equals(card.bodyKey))
		{
			if (card.body != null)
			{
				card.card.remove(card.body);
			}
			else
			{
				card.card.add(card.bodyGap);
			}
			card.body = buildBody(card, model, modeLabel);
			card.card.add(card.body);
			card.bodyKey = model.key;
			changed = true;
		}
		return changed;
	}

	/** Header toggle: flips ONE card's manual collapse state and updates only that card. */
	private void toggleCard(SourceCard card)
	{
		String searchText = currentSearchText();
		boolean collapse = !isEffectivelyCollapsed(card.entry, searchText);
		setManualCollapsed(java.util.Collections.singletonList(card), collapse, searchText);
		applyLocalCollapse(java.util.Collections.singletonList(card), searchText);
	}

	/**
	 * Collapse All / Open All: every currently DISPLAYED card (after the
	 * active sub-tab and search filter), as one batch -- one preference
	 * write, one pass over the cards, one revalidate/repaint. Filtered-out
	 * sources are deliberately left alone: the bulk controls act on what
	 * the player can see. Open All in Individual mode allocates the shared
	 * record budget across the displayed cards in display order, exactly
	 * like a full render, so it never materializes more than the hard cap.
	 */
	void setAllCollapsed(boolean collapse)
	{
		if (displayedCards.isEmpty())
		{
			return;
		}
		String searchText = currentSearchText();
		setManualCollapsed(displayedCards, collapse, searchText);
		applyLocalCollapse(displayedCards, searchText);
	}

	/**
	 * The MANUAL collapse state: persisted, one logical preference write for
	 * however many raw source keys the cards represent. While a search is
	 * active the cards are also recorded as explicitly toggled, so the
	 * search's temporary expansion no longer overrides them; the overrides
	 * end with the search.
	 */
	private void setManualCollapsed(List<SourceCard> cards, boolean collapse, String searchText)
	{
		List<String> rawKeys = new ArrayList<>();
		for (SourceCard card : cards)
		{
			rawKeys.addAll(card.entry.getRawSourceKeys());
			if (!searchText.isEmpty())
			{
				searchOverrideKeys.add(card.key);
			}
		}
		if (preferences != null)
		{
			preferences.setCollapsed(rawKeys, collapse);
		}
		else
		{
			for (SourceCard card : cards)
			{
				localCollapsedWithoutPreferences.put(card.key, collapse);
			}
		}
	}

	/**
	 * Re-applies the effective collapse state to {@code cards} only (Grouped
	 * source cards; Individual has no per-source cards). One
	 * revalidate/repaint for the whole batch.
	 */
	private void applyLocalCollapse(List<SourceCard> cards, String searchText)
	{
		LootValuationMode mode = config.lootValuationMode();
		String modeLabel = modeLabel(mode);
		boolean changed = false;
		for (SourceCard card : cards)
		{
			boolean collapsed = isEffectivelyCollapsed(card.entry, searchText);
			changed |= applyCard(card, card.entry, card.sourceTotal, collapsed, searchText, mode, modeLabel);
		}
		if (changed)
		{
			content.revalidate();
			content.repaint();
		}
		updateBulkToggle();
	}

	/**
	 * SMART BULK CONTROL. Decided from the MANUAL collapse state of the
	 * DISPLAYED cards (never the search's temporary expansion): when every
	 * displayed card is manually collapsed the button offers Open All
	 * ("▾ All"); otherwise -- all open or mixed -- it offers Collapse All
	 * ("▸ All"). It flips the moment a bulk or single toggle changes that.
	 */
	boolean allDisplayedManuallyCollapsed()
	{
		if (displayedCards.isEmpty())
		{
			return false;
		}
		for (SourceCard card : displayedCards)
		{
			if (!manuallyCollapsed(card.entry))
			{
				return false;
			}
		}
		return true;
	}

	private void updateBulkToggle()
	{
		if (bulkToggleButton == null)
		{
			return;
		}
		boolean openAll = allDisplayedManuallyCollapsed();
		String text = openAll ? BULK_OPEN_TEXT : BULK_COLLAPSE_TEXT;
		if (!text.equals(bulkToggleButton.getText()))
		{
			bulkToggleButton.setText(text);
			bulkToggleButton.setToolTipText(openAll ? "Open all shown sources" : "Collapse all shown sources");
		}
		bulkToggleButton.setEnabled(!displayedCards.isEmpty());
	}

	private boolean manuallyCollapsed(LootTrackerSnapshot.SourceEntry source)
	{
		if (preferences == null)
		{
			Boolean local = localCollapsedWithoutPreferences.get(source.getSourceKey());
			return local != null ? local : source.isCollapsed();
		}
		for (String rawKey : source.getRawSourceKeys())
		{
			if (preferences.isCollapsed(rawKey))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Manual state, except that an active search temporarily expands a
	 * collapsed card (it is only displayed because an item in it matches) --
	 * unless the player explicitly collapsed/opened that card during this
	 * search. Never writes a preference.
	 */
	boolean isEffectivelyCollapsed(LootTrackerSnapshot.SourceEntry source, String searchText)
	{
		return effectivelyCollapsed(manuallyCollapsed(source), searchOverrideKeys.contains(source.getSourceKey()), searchText);
	}

	/**
	 * See class javadoc's SCROLL POSITION note. Walks up from this view to
	 * find RuneLite's own outer sidebar {@code JScrollPane} -- this class
	 * no longer owns one of its own (see class javadoc's SCROLLING note).
	 * Returns {@code null} (rather than throwing) when none is found yet
	 * -- e.g. during construction, before this view has been added to its
	 * parent hierarchy -- so callers can degrade gracefully exactly like
	 * the pre-existing {@code previous == null} handling in
	 * {@link #restoreScrollPosition} already did.
	 */
	private JScrollPane findEnclosingScrollPane()
	{
		return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
	}

	private void restoreScrollPosition(JScrollPane enclosingScrollPane, java.awt.Point previous)
	{
		if (previous == null || enclosingScrollPane == null)
		{
			return;
		}
		java.awt.Dimension viewSize = enclosingScrollPane.getViewport().getView().getPreferredSize();
		java.awt.Dimension extent = enclosingScrollPane.getViewport().getExtentSize();
		int maxX = Math.max(0, viewSize.width - extent.width);
		int maxY = Math.max(0, viewSize.height - extent.height);
		int x = Math.max(0, Math.min(previous.x, maxX));
		int y = Math.max(0, Math.min(previous.y, maxY));
		enclosingScrollPane.getViewport().setViewPosition(new java.awt.Point(x, y));
	}

	/**
	 * Pure, Swing-free presentation fingerprint -- package-private
	 * static specifically so it is directly unit-testable (no
	 * JPanel/JScrollPane construction needed) against plain
	 * {@link LootTrackerSnapshot.SourceEntry} instances. Two calls with
	 * equivalent visible content produce equal strings; anything a player
	 * could actually SEE differently (source order, a new/changed item, a
	 * favorite/collapsed/hidden flag, the active sub-tab/search text/view
	 * mode, a price that just finished resolving via
	 * {@code valuationVersion}, a Load-more click via
	 * {@code individualRevealCounts}, or a change to the GLOBAL total even
	 * while a narrower tab/search is on screen -- see the
	 * {@code allSourcesForTotal} parameter) produces a different one.
	 * Individual-mode records are capped exactly like the real render
	 * pass (same {@link #MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED} total
	 * budget, consumed in the same source order), so fingerprinting cost
	 * never scales with any source's full historical record count, and
	 * the key can never disagree with what {@link #render} actually
	 * draws.
	 *
	 * WHY {@code allSourcesForTotal} IS SEPARATE FROM {@code sources}:
	 * {@code sources} is already tab-filtered (Favorites narrows it) --
	 * the global total header must reflect the FULL account total
	 * regardless of which tab is active, so a change to a non-favorited
	 * source while sitting on the Favorites tab must still change this
	 * key (forcing the header to refresh) even though that source isn't
	 * itself rendered as a card right now.
	 */
	static String computeRenderKey(
		List<LootTrackerSnapshot.SourceEntry> sources,
		List<LootTrackerSnapshot.SourceEntry> allSourcesForTotal,
		boolean favoritesOnly,
		boolean individualView,
		String searchText,
		boolean showHidden,
		long valuationVersion,
		int individualRevealCount)
	{
		StringBuilder key = new StringBuilder(256);
		key.append(favoritesOnly).append('|').append(individualView).append('|').append(showHidden).append('|').append(valuationVersion).append('|').append(searchText).append('\n');
		for (LootTrackerSnapshot.SourceEntry source : sources)
		{
			key.append(source.getSourceKey()).append(';')
				.append(source.isFavorite()).append(';')
				.append(source.isCollapsed()).append(';')
				.append(source.getKillCount()).append(';')
				.append(source.getLastObservedAt()).append(';');
			for (LootTrackerSnapshot.ItemEntry item : source.getItemTotals())
			{
				key.append(item.getItemIdValue()).append(':').append(item.getQuantity()).append(':').append(item.isHidden()).append(',');
			}
			key.append('\n');
		}
		if (individualView)
		{
			// Exactly the stream reconcileIndividual() draws: the same search-
			// matching sources, the same bounded newest-first window.
			List<LootTrackerSnapshot.SourceEntry> matching = new ArrayList<>();
			for (LootTrackerSnapshot.SourceEntry source : sources)
			{
				if (matchesSearch(source, searchText))
				{
					matching.add(source);
				}
			}
			IndividualWindow window = individualStream(matching, individualRevealCount);
			key.append("~stream~").append(window.totalAvailable).append('/').append(individualRevealCount).append('\n');
			for (StreamRecord record : window.records)
			{
				key.append(record.key).append('[');
				for (LootTrackerSnapshot.ItemEntry item : record.record.getItems())
				{
					key.append(item.getItemIdValue()).append(':').append(item.getQuantity()).append(':').append(item.isHidden()).append(',');
				}
				key.append(']');
			}
			key.append('\n');
		}

		// See method javadoc's WHY allSourcesForTotal IS SEPARATE note.
		key.append("~total~");
		for (LootTrackerSnapshot.SourceEntry source : allSourcesForTotal)
		{
			key.append(source.getSourceKey()).append(':');
			for (LootTrackerSnapshot.ItemEntry item : source.getItemTotals())
			{
				key.append(item.getItemIdValue()).append(':').append(item.getQuantity()).append(',');
			}
			key.append(';');
		}
		return key.toString();
	}

	private static boolean matchesSearch(LootTrackerSnapshot.SourceEntry source, String searchText)
	{
		if (searchText.isEmpty())
		{
			return true;
		}
		for (LootTrackerSnapshot.ItemEntry item : source.getItemTotals())
		{
			String name = item.getItemName();
			if (name != null && name.toLowerCase(Locale.ROOT).contains(searchText))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The rule {@link #isEffectivelyCollapsed} applies to a card the player
	 * has NOT explicitly toggled during the current search: a
	 * manually-collapsed source card temporarily expands while an active
	 * search matches an item inside it, so the match is not hidden. The
	 * user's PERSISTED collapse preference
	 * ({@link LootTrackerSnapshot.SourceEntry#isCollapsed()}, backed by
	 * {@link LootTrackerPreferences}) is never written by this rule. A
	 * source is only displayed while {@code searchText} is non-empty
	 * because it passed
	 * {@link #matchesSearch}, which (for non-empty search text) can only
	 * return true via an item-level name match -- so "collapsed AND search
	 * is active" here always means "collapsed card with a matching item
	 * inside it". Clearing the search field (searchText becomes empty
	 * again) makes this collapse again instantly, since nothing here
	 * mutates the stored preference -- the card simply reverts to its
	 * persisted state on the very next render pass.
	 */
	static boolean effectivelyCollapsed(LootTrackerSnapshot.SourceEntry source, String searchText)
	{
		return effectivelyCollapsed(source.isCollapsed(), false, searchText);
	}

	/**
	 * The one collapse rule, shared by production ({@link #isEffectivelyCollapsed})
	 * and the snapshot-level overload above: manual state with no search;
	 * during a search only a card the player explicitly collapsed during
	 * this search stays collapsed.
	 */
	static boolean effectivelyCollapsed(boolean manuallyCollapsed, boolean explicitlyToggledDuringSearch, String searchText)
	{
		return searchText.isEmpty() ? manuallyCollapsed : manuallyCollapsed && explicitlyToggledDuringSearch;
	}

	/** No tracked loot at all (fresh install, or nothing in the retention window). */
	static final String EMPTY_NO_LOOT = "No loot tracked yet. Drops from supported activities appear here automatically.";
	static final String EMPTY_FAVORITES = "No favorited sources yet. Star a source to pin it here.";
	static final String EMPTY_NO_MATCH = "No items match your search.";

	static JLabel emptyStateLabel(String message)
	{
		// Wraps to the ACTUAL available width -- see WrappingHtmlLabel's
		// javadoc (the old fixed CSS width:180px was ~233px in Swing and clipped).
		JLabel label = new WrappingHtmlLabel(message, true);
		label.setFont(SpyglassFonts.small());
		label.setForeground(SpyglassTheme.TEXT_SECONDARY);
		label.setHorizontalAlignment(JLabel.CENTER);
		label.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}


	/**
	 * One retained source card. The shell (card + gap) is what the content
	 * list holds; the header is rebuilt only when its data changes; the body
	 * exists only while the card is expanded and is rebuilt only when its
	 * prepared data changes.
	 */
	private final class SourceCard
	{
		final String key;
		final JPanel shell = heightBoundedPanel();
		final JPanel card = heightBoundedPanel();
		final JLabel indicator = new JLabel();
		final Component bodyGap = Box.createVerticalStrut(6);
		JPanel header;
		String headerKey;
		JPanel body;
		String bodyKey;
		LootTrackerSnapshot.SourceEntry entry;
		long sourceTotal;

		SourceCard(String key)
		{
			this.key = key;
			shell.setLayout(new BoxLayout(shell, BoxLayout.Y_AXIS));
			shell.setOpaque(false);
			shell.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
			card.setOpaque(true);
			card.setBackground(SpyglassTheme.SURFACE_CARD);
			card.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
				BorderFactory.createEmptyBorder(5, 7, 5, 7)));
			card.setComponentPopupMenu(buildResetContextMenu(() -> entry));
			indicator.setFont(SpyglassFonts.small());
			indicator.setForeground(SpyglassTheme.TEXT_SECONDARY);
			indicator.addMouseListener(new ClickListener(() -> toggleCard(this)));
			shell.add(card);
			shell.add(Box.createVerticalStrut(6));
		}
	}

	/** Prepared (pure data) Grouped body content for one expanded source card, plus its fingerprint. */
	private static final class BodyModel
	{
		final String key;
		final List<LootPricing.ValuedLootRow> groupedRows;

		BodyModel(String key, List<LootPricing.ValuedLootRow> groupedRows)
		{
			this.key = key;
			this.groupedRows = groupedRows;
		}
	}

	/**
	 * Values and sorts one expanded GROUPED card's item totals -- no Swing --
	 * filtered by search and the hidden-item setting. The fingerprint covers
	 * everything the built grid shows, so an unchanged card is never rebuilt.
	 */
	private BodyModel prepareBody(LootTrackerSnapshot.SourceEntry source, String searchText, LootValuationMode mode)
	{
		StringBuilder key = new StringBuilder(128).append(mode).append('|').append(lastSnapshot.isShowHidden()).append('|').append(searchText).append('|');
		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(visibleItems(source.getItemTotals(), searchText), itemId -> unitValue(itemId, mode));
		key.append('G');
		appendRowsKey(key, source, rows);
		return new BodyModel(key.toString(), rows);
	}

	private static void appendRowsKey(StringBuilder key, LootTrackerSnapshot.SourceEntry source, List<LootPricing.ValuedLootRow> rows)
	{
		for (LootPricing.ValuedLootRow row : rows)
		{
			key.append(row.getItemId()).append(':').append(row.getQuantity()).append(':').append(row.getUnitValue())
				.append(':').append(findHidden(source, row.getItemId())).append(',');
		}
	}

	/** Swing for a prepared Grouped body (EDT only). */
	private JPanel buildBody(SourceCard card, BodyModel model, String modeLabel)
	{
		return gridOf(model.groupedRows, modeLabel, card.entry);
	}

	private JPanel gridOf(List<LootPricing.ValuedLootRow> rows, String modeLabel, LootTrackerSnapshot.SourceEntry source)
	{
		JPanel grid = heightBoundedPanel();
		grid.setLayout(LootGridCell.newGridLayout());
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (LootPricing.ValuedLootRow row : rows)
		{
			grid.add(lootGridCell(row, modeLabel, source, findHidden(source, row.getItemId())));
		}
		return grid;
	}

	private JPanel buildHeaderRow(SourceCard card, LootTrackerSnapshot.SourceEntry source, long sourceTotal)
	{
		JPanel wrapper = heightBoundedPanel();
		wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
		wrapper.setOpaque(false);
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrapper.add(buildHeaderTopRow(card, source));

		// Only shown where a reliable count exists (killCount > 0, always
		// true for any source with records); hidden items still contribute
		// to sourceTotal, so GP/kill here is honest too.
		if (source.getKillCount() > 0)
		{
			JLabel subtitle = new JLabel(buildHeaderSubtitleText(sourceTotal, source.getKillCount(), source.isStructuredReward()));
			subtitle.setFont(SpyglassFonts.small());
			subtitle.setForeground(SpyglassTheme.TEXT_SECONDARY);
			subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
			subtitle.setBorder(BorderFactory.createEmptyBorder(1, 4, 0, 0));
			wrapper.add(subtitle);
		}
		return wrapper;
	}

	/** Pure and package-private static for direct unit testing -- e.g. "1.84m total · 6.1k/kill". */
	static String buildHeaderSubtitleText(long totalValue, int killCount)
	{
		return buildHeaderSubtitleText(totalValue, killCount, false);
	}

	/** A structured reward source counts reward claims, not kills: "/reward" instead of "/kill". */
	static String buildHeaderSubtitleText(long totalValue, int killCount, boolean structuredReward)
	{
		long perKill = killCount > 0 ? totalValue / killCount : 0L;
		return QuantityFormatter.quantityToStackSize(totalValue) + " total · " + QuantityFormatter.quantityToStackSize(perKill)
			+ (structuredReward ? "/reward" : "/kill");
	}

	/**
	 * Long OSRS NPC/activity names are truncated with an ellipsis so the
	 * header row's title label can never grow wide enough to crowd the
	 * collapse indicator out of the card's fixed width (see class
	 * javadoc's WIDTH note) -- the full name is still available via the
	 * title label's tooltip. Pure and package-private static for direct
	 * unit testing. Null becomes "Unknown".
	 */
	static String truncateSourceName(String sourceName)
	{
		if (sourceName == null)
		{
			return "Unknown";
		}
		if (sourceName.length() <= MAX_SOURCE_NAME_DISPLAY_CHARS)
		{
			return sourceName;
		}
		return sourceName.substring(0, MAX_SOURCE_NAME_DISPLAY_CHARS - 1) + "…";
	}

	/**
	 * The card header's primary label -- e.g. {@code "Zulrah  ×43"}.
	 * Only appends the kill count
	 * when {@code killCount > 0}: {@link LootTrackerSource#getKillCount()}'s
	 * own javadoc documents it as the one RELIABLE count (real
	 * {@code SERVER_NPC_LOOT} notifications, never derived from
	 * NPC_DEATH), but a source can still legitimately reach this view
	 * with zero currently-visible records after a reset watermark (see
	 * {@code LootTrackerSource#filteredAfter}) -- appending "×0"
	 * unconditionally would read as a fabricated, misleadingly precise
	 * count rather than "no reliable count right now." Pure and
	 * package-private static for direct unit testing.
	 */
	static String buildSourceTitleText(String sourceName, int killCount)
	{
		String truncated = truncateSourceName(sourceName);
		return killCount > 0 ? truncated + "  ×" + killCount : truncated;
	}

	/**
	 * HEADER LAYOUT PRIORITY: star (fixed) | source name (flexible -- the
	 * ONLY part allowed to shrink; it ellipsizes itself) | kill count |
	 * collapse caret (always reserved). A FlowLayout here let a long name
	 * plus a big count push the caret out of the card entirely.
	 */
	private JPanel buildHeaderTopRow(SourceCard card, LootTrackerSnapshot.SourceEntry source)
	{
		JPanel headerRow = new JPanel(new BorderLayout(4, 0));
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel leftGroup = new JPanel(new BorderLayout(0, 0));
		leftGroup.setOpaque(false);

		JLabel star = new JLabel(source.isFavorite() ? "★" : "☆");
		star.setFont(SpyglassFonts.bold());
		star.setForeground(source.isFavorite() ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_SECONDARY);
		star.setToolTipText(source.isFavorite() ? "Unfavorite" : "Favorite");
		star.addMouseListener(new ClickListener(() ->
		{
			boolean favorite = !card.entry.isFavorite();
			if (preferences != null)
			{
				preferences.setFavorite(card.entry.getRawSourceKeys(), favorite);
			}
			// Favorites membership changes the list: one coalesced refresh.
			requestRefresh();
		}));
		star.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		JPanel starCell = new JPanel(new BorderLayout());
		starCell.setOpaque(false);
		starCell.add(star, BorderLayout.NORTH); // beside the name's first line when the name wraps
		leftGroup.add(starCell, BorderLayout.WEST);

		// The whole name, wrapping at its own cell's width (pass 30: never cut short at any text size; the tooltip repeats it).
		JLabel title = new WrappingHtmlLabel(source.getSourceName(), false);
		title.setName(SOURCE_TITLE_NAME);
		title.setFont(SpyglassFonts.bold());
		title.setForeground(SpyglassTheme.TEXT_PRIMARY);
		title.setToolTipText(source.getSourceName());
		title.addMouseListener(new ClickListener(() -> toggleCard(card)));
		leftGroup.add(FarmingRunPage.cell(title), BorderLayout.CENTER);

		JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		trailing.setOpaque(false);
		if (source.getKillCount() > 0)
		{
			JLabel count = new JLabel("×" + String.format("%,d", source.getKillCount()));
			count.setName(SOURCE_COUNT_NAME);
			count.setFont(SpyglassFonts.bold());
			count.setForeground(SpyglassTheme.TEXT_PRIMARY);
			count.addMouseListener(new ClickListener(() -> toggleCard(card)));
			trailing.add(count);
		}
		trailing.add(card.indicator);

		headerRow.add(leftGroup, BorderLayout.CENTER);
		headerRow.add(trailing, BorderLayout.EAST);
		return headerRow;
	}

	static final String SOURCE_TITLE_NAME = "sourceTitle";
	static final String SOURCE_COUNT_NAME = "sourceCount";

	private JPopupMenu buildResetContextMenu(java.util.function.Supplier<LootTrackerSnapshot.SourceEntry> sourceOf)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem resetItem = new JMenuItem("Reset loot");
		resetItem.addActionListener(e ->
		{
			LootTrackerSnapshot.SourceEntry source = sourceOf.get();
			if (source == null)
			{
				return;
			}
			int choice = JOptionPane.showConfirmDialog(this,
				"Reset kills and loot totals for " + source.getSourceName() + "?\nThis only resets the DISPLAYED counters -- your recorded history is never deleted.",
				"Confirm reset", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				Instant now = Instant.now();
				forEachRawKey(source, rawKey -> preferences.resetSource(rawKey, now));
				requestRefresh();
			}
		});
		menu.add(resetItem);
		return menu;
	}

	/**
	 * Applies {@code action} to EVERY raw {@link LootTrackerPreferences} key a
	 * canonical, player-facing {@code source} card represents -- see
	 * {@code LootTrackerSnapshot.SourceEntry#getRawSourceKeys()}'s own
	 * javadoc for why a favorite/hide/collapse/reset action on a merged
	 * card must be written across its whole raw-key group rather than
	 * any single one of them.
	 */
	private static void forEachRawKey(LootTrackerSnapshot.SourceEntry source, java.util.function.Consumer<String> action)
	{
		for (String rawKey : source.getRawSourceKeys())
		{
			action.accept(rawKey);
		}
	}



	private List<LootTrackerSnapshot.ItemEntry> visibleItems(List<LootTrackerSnapshot.ItemEntry> items, String searchText)
	{
		List<LootTrackerSnapshot.ItemEntry> visible = new ArrayList<>();
		for (LootTrackerSnapshot.ItemEntry item : items)
		{
			if (item.isHidden() && !lastSnapshot.isShowHidden())
			{
				continue;
			}
			if (!searchText.isEmpty() && (item.getItemName() == null || !item.getItemName().toLowerCase(Locale.ROOT).contains(searchText)))
			{
				continue;
			}
			visible.add(item);
		}
		return visible;
	}

	private static boolean findHidden(LootTrackerSnapshot.SourceEntry source, Integer itemId)
	{
		if (itemId == null)
		{
			return false;
		}
		for (LootTrackerSnapshot.ItemEntry item : source.getItemTotals())
		{
			if (item.getItemId().equals(itemId))
			{
				return item.isHidden();
			}
		}
		return false;
	}

	private LootGridCell lootGridCell(LootPricing.ValuedLootRow row, String modeLabel, LootTrackerSnapshot.SourceEntry source, boolean hidden)
	{
		net.runelite.client.util.AsyncBufferedImage icon = null;
		if (row.getItemId() != null)
		{
			try
			{
				icon = itemManager.getImage(row.getItemId(), LootGridCell.clampToInt(row.getQuantity()), false);
			}
			catch (RuntimeException e)
			{
				// Defensive only -- an unresolvable item id must never take the panel down.
			}
		}

		String hiddenNote = hidden ? "(Hidden -- right-click to unhide)" : null;
		String tooltip = LootPricing.buildTooltipHtml(row.getItemName(), row.getQuantity(), modeLabel, row.getUnitValue(), row.getTotalValue(), hiddenNote);

		LootGridCell cell = new LootGridCell(icon, row.getQuantity(), tooltip);
		if (hidden)
		{
			cell.setBorder(BorderFactory.createLineBorder(SpyglassTheme.ACCENT_GOLD));
		}
		if (row.getItemId() != null)
		{
			int itemId = row.getItemId();
			boolean currentlyHidden = hidden;
			cell.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					maybeShowMenu(e);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					maybeShowMenu(e);
				}

				private void maybeShowMenu(MouseEvent e)
				{
					if (!e.isPopupTrigger())
					{
						return;
					}
					JPopupMenu menu = new JPopupMenu();
					JMenuItem toggleHidden = new JMenuItem(currentlyHidden ? "Unhide item" : "Hide item");
					toggleHidden.addActionListener(ev ->
					{
						forEachRawKey(source, rawKey -> preferences.setItemHidden(rawKey, itemId, !currentlyHidden));
						requestRefresh();
					});
					menu.add(toggleHidden);
					menu.show(e.getComponent(), e.getX(), e.getY());
				}
			});
		}
		return cell;
	}

	private long unitValue(int itemId, LootValuationMode mode)
	{
		if (mode == LootValuationMode.HIGH_ALCH)
		{
			return itemValuationCache.getHighAlchPrice(itemId);
		}
		return itemValuationCache.getGePrice(itemId);
	}

	/** Small shared helper: run {@code action} on a plain mouse click (press+release without drag), used for the favorite star/title-collapse click targets above -- deliberately not a JButton, to keep those labels visually inline with the rest of the header row. */
	private static final class ClickListener extends MouseAdapter
	{
		private final Runnable action;

		ClickListener(Runnable action)
		{
			this.action = action;
		}

		@Override
		public void mouseClicked(MouseEvent e)
		{
			action.run();
		}
	}

	/** Minimal DocumentListener adapter -- this view only ever needs "something changed, re-render," never which kind of edit happened. */
	private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener
	{
		private final Runnable onChange;

		SimpleDocumentListener(Runnable onChange)
		{
			this.onChange = onChange;
		}

		@Override
		public void insertUpdate(javax.swing.event.DocumentEvent e)
		{
			onChange.run();
		}

		@Override
		public void removeUpdate(javax.swing.event.DocumentEvent e)
		{
			onChange.run();
		}

		@Override
		public void changedUpdate(javax.swing.event.DocumentEvent e)
		{
			onChange.run();
		}
	}
}
