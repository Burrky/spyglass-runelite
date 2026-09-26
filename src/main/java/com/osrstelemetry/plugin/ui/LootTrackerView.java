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
import net.runelite.client.ui.FontManager;
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
 * STATELESS RENDER, STATEFUL PREFERENCES: like CurrentSessionView,
 * {@link #render} is called on every poll tick (~1s, via
 * OsrsTelemetryPanel's Timer) with a freshly-built snapshot -- a
 * favorite/hide/collapse/reset click mutates {@link LootTrackerPreferences}
 * directly (across every raw source key the clicked card represents --
 * see {@link #forEachRawKey}) and takes visible effect on the NEXT tick
 * (or immediately, via the click handler's own {@code renderFromLastSnapshot()}
 * call). This view holds only ephemeral, UI-only state of its own (which
 * sub-tab is active, the live search text, the grouped/individual
 * toggle, and how many individual records have been "loaded" per source
 * -- see {@link #individualRevealCountBySourceKey}) -- none of it
 * durable, all of it reset to its default on plugin re-enable, which is
 * correct: none of it is a player preference worth persisting across
 * restarts (unlike favorites/hidden/collapsed/reset-watermark, which ARE
 * persisted, via LootTrackerPreferences).
 *
 * RENDER-KEY SKIP: despite being called every tick, render() does not
 * unconditionally rebuild the Swing tree every time. It first computes a
 * cheap presentation fingerprint (see {@link #computeRenderKey}) and
 * compares it against whatever fingerprint the currently-visible tree
 * was last built from ({@link #lastRenderKey}) -- an unchanged tick (no
 * new drop, no preference change, no resolved price, no local toggle, no
 * Load-more click) is a pure string comparison with NO
 * {@code removeAll()}/rebuild/revalidate/repaint at all. See
 * {@link ItemValuationCache#getVersion()} for how a price that resolves
 * asynchronously still correctly triggers a real rebuild the next tick
 * rather than being silently skipped forever, and see
 * {@link #computeRenderKey}'s own javadoc for why the key ALWAYS
 * reflects the true player-account-wide total even while a narrower
 * tab/search is active on screen.
 *
 * BOUNDED INDIVIDUAL-MODE RENDERING: see
 * {@link #MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED}'s own javadoc -- a
 * single hard ceiling on the TOTAL number of individual-drop records
 * rendered across every source card in one pass, not just a per-source
 * one, with a small "Load more" control per source (see
 * {@link #individualRevealCountBySourceKey}) so the player can still
 * reach older drops without the tree ever exceeding the bound.
 *
 * SCROLL POSITION: a real rebuild (the key actually changed) captures the
 * viewport's current scroll offset immediately before {@code removeAll()}
 * and restores it (clamped to the new content's bounds) immediately after
 * -- including when a source card REORDERS due to new activity (see
 * {@code LootTrackerSourceOrdering}): the player's place in the list is
 * preserved, the list is never force-scrolled back to the top just
 * because something moved. {@link #restoreScrollPosition} does not own a
 * {@code JViewport} directly -- it locates RuneLite's outer
 * {@code JScrollPane} dynamically (see {@link #findEnclosingScrollPane})
 * at the moment of a real rebuild and saves/restores THAT viewport's
 * position. Defensively null-safe so a hierarchy where no ancestor
 * {@code JScrollPane} is found (not yet added to a parent, e.g. during
 * construction, or a future RuneLite change) simply skips restoration
 * rather than failing.
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
	 * BOTH Grouped ({@link #buildGroupedGrid}) and Individual
	 * ({@link #buildIndividualDropsPanel}) views.
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
	 * The one true ceiling: across an entire render pass, the SUM of
	 * individual-drop records actually turned into Swing components (a
	 * timestamp label + an item grid each) never exceeds this number, no
	 * matter how many sources are expanded or how large any one source's
	 * own history is. Sources are visited in the SAME most-recent-first
	 * order the cards are displayed in (see
	 * {@code LootTrackerSourceOrdering}), so when the budget runs out it
	 * is always the LEAST recently active visible sources that render
	 * fewer records first -- never an arbitrary cut. {@link #capRecords}
	 * still does the actual list-slicing (most recent records within
	 * whatever count this budget allows); {@link #DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE}
	 * and {@link #individualRevealCountBySourceKey} are the per-source
	 * "how many would I like to see" request this budget is applied
	 * against.
	 */
	static final int MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED = 500;

	/** Initial per-source reveal count in Individual mode, before any "Load more" click -- small and cheap by default; see {@link #MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED}'s own javadoc for the total bound this is weighed against. */
	static final int DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE = 50;

	/** How many additional records one "Load more" click reveals for a single source. */
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
	private final JLabel globalTotalHeader = new JLabel();
	private final JLabel totalsFooter = new JLabel();

	private boolean showFavoritesOnly = false;
	private boolean individualView = false;
	private LootTrackerSnapshot lastSnapshot = LootTrackerSnapshot.empty();

	/**
	 * Ephemeral, UI-only "how many individual records has the player
	 * asked to see" per canonical source key -- see class javadoc's BOUNDED
	 * INDIVIDUAL-MODE RENDERING note. Absent from this map means
	 * {@link #DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE}. Never persisted
	 * (mirrors {@link #showFavoritesOnly}/{@link #individualView}'s own
	 * "UI-only, resets on plugin re-enable" treatment) and never cleared
	 * on a Grouped&lt;-&gt;Individual toggle -- a player's "Load more"
	 * clicks stay expanded if they flip back to Individual later in the
	 * same panel session.
	 */
	private final Map<String, Integer> individualRevealCountBySourceKey = new HashMap<>();

	/**
	 * The presentation fingerprint (see {@link #computeRenderKey}) of
	 * whatever is CURRENTLY on screen -- null until the first real
	 * render. render()
	 * recomputes a fresh key on every call (poll tick OR a local toggle)
	 * and, when it is {@code equals()} to this field, returns immediately
	 * WITHOUT touching the Swing tree at all -- no removeAll(), no card
	 * rebuild, no flash, no EDT cost beyond building the (cheap, string-
	 * only) key itself. This is what stops an unchanged snapshot from
	 * producing a full teardown/rebuild every single second.
	 */
	private String lastRenderKey;

	/**
	 * A plain {@code JPanel} whose maximum height always equals its OWN
	 * current preferred height (width left unbounded, so it can still
	 * stretch to the card's full available width). This is the standard,
	 * minimal {@code BoxLayout.Y_AXIS} idiom for "let this child be
	 * exactly as tall as its content, and no taller."
	 *
	 * WHY THIS EXISTS: every card-shaped panel in this class
	 * ({@code section} in {@link #buildSourceCard}, {@code wrapper} in
	 * {@link #buildHeaderRow}, and the item {@code grid} in both
	 * {@link #buildGroupedGrid} and {@link #buildIndividualDropsPanel})
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
		globalTotalHeader.setFont(FontManager.getRunescapeBoldFont());
		globalTotalHeader.setForeground(SpyglassTheme.ACCENT_GOLD);
		globalTotalHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
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
		searchField.setFont(FontManager.getRunescapeSmallFont());
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
		viewModeButton.setFont(FontManager.getRunescapeSmallFont());
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

		JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		optionsRow.setOpaque(false);
		showHiddenCheckbox = new JCheckBox("Show hidden");
		showHiddenCheckbox.setFont(FontManager.getRunescapeSmallFont());
		showHiddenCheckbox.setForeground(SpyglassTheme.TEXT_SECONDARY);
		showHiddenCheckbox.setOpaque(false);
		showHiddenCheckbox.setFocusPainted(false);
		showHiddenCheckbox.addActionListener(e ->
		{
			preferences.setShowHidden(showHiddenCheckbox.isSelected());
			renderFromLastSnapshot();
		});
		optionsRow.add(showHiddenCheckbox);
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

		totalsFooter.setFont(FontManager.getRunescapeBoldFont());
		totalsFooter.setForeground(SpyglassTheme.ACCENT_GOLD);
		totalsFooter.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
		add(totalsFooter, BorderLayout.SOUTH);

		updateSubNavStyling();
		updateViewModeButtonText();
		render(LootTrackerSnapshot.empty());
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
		button.setFont(FontManager.getRunescapeSmallFont());
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
	 * Called by OsrsTelemetryPanel on every poll tick, and internally
	 * whenever a purely-local UI control (search text, sub-tab, view-mode
	 * toggle, Load-more click) changes without waiting for the next tick.
	 * Rebuilds the card list from {@code snapshot} ONLY when something
	 * about the visible presentation actually changed -- see class
	 * javadoc's RENDER-KEY SKIP note.
	 */
	void render(LootTrackerSnapshot snapshot)
	{
		lastSnapshot = snapshot;
		showHiddenCheckbox.setSelected(snapshot.isShowHidden());
		updateViewModeButtonText();

		String searchText = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		List<LootTrackerSnapshot.SourceEntry> sources = showFavoritesOnly ? snapshot.getFavoriteSources() : snapshot.getAllSources();
		List<LootTrackerSnapshot.SourceEntry> allSources = snapshot.getAllSources();

		String key = computeRenderKey(sources, allSources, showFavoritesOnly, individualView, searchText, snapshot.isShowHidden(), itemValuationCache.getVersion(), individualRevealCountBySourceKey);
		if (key.equals(lastRenderKey))
		{
			// Nothing visible changed since the tree currently on screen
			// was built -- see class javadoc's RENDER-KEY SKIP note. The
			// totals footer and global total header are derived purely
			// from `sources`/`allSources`/valuation, all already covered
			// by the key, so both are already correct and need no
			// separate refresh here either.
			return;
		}
		lastRenderKey = key;

		// See class javadoc's SCROLL POSITION note -- sourced from
		// RuneLite's outer scrollpane now, not one this class owns.
		JScrollPane enclosingScrollPane = findEnclosingScrollPane();
		java.awt.Point scrollPosition = enclosingScrollPane == null ? null : enclosingScrollPane.getViewport().getViewPosition();

		content.removeAll();

		LootValuationMode mode = config.lootValuationMode();
		String modeLabel = mode == LootValuationMode.HIGH_ALCH ? "High Alch" : "Grand Exchange";

		// ALWAYS the full account-wide total, unconditionally over
		// `allSources` -- never narrowed by search/Favorites-tab, and
		// always including hidden items' value (hidden loot still counts
		// toward every total).
		long globalTotal = 0L;
		for (LootTrackerSnapshot.SourceEntry source : allSources)
		{
			List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(source.getItemTotals(), itemId -> unitValue(itemId, mode));
			globalTotal += LootPricing.sumTotalValue(rows);
		}
		globalTotalHeader.setText("Total Loot: " + QuantityFormatter.quantityToStackSize(globalTotal) + " gp");

		// See MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED's own javadoc -- reset
		// once per real render pass, then consumed (decremented) as each
		// source card is built below, in the SAME most-recent-first order
		// they are displayed in.
		int[] individualRenderBudget = {MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED};

		long grandTotal = 0L;
		int rendered = 0;
		for (LootTrackerSnapshot.SourceEntry source : sources)
		{
			// Totals footer reflects the FULL current tab (never
			// search-narrowed -- see class javadoc), including hidden
			// items' value (spec: hidden loot still counts toward every
			// total).
			List<LootPricing.ValuedLootRow> fullRows = LootPricing.valueAndSort(source.getItemTotals(), itemId -> unitValue(itemId, mode));
			long sourceTotal = LootPricing.sumTotalValue(fullRows);
			grandTotal += sourceTotal;

			if (!matchesSearch(source, searchText))
			{
				continue;
			}

			content.add(buildSourceCard(source, searchText, mode, modeLabel, sourceTotal, individualRenderBudget));
			content.add(Box.createVerticalStrut(6));
			rendered++;
		}

		if (rendered == 0)
		{
			String message = sources.isEmpty()
				? (showFavoritesOnly ? "No favorited sources yet." : "No loot recorded yet.")
				: "No items match your search.";
			content.add(emptyStateLabel(message));
		}

		totalsFooter.setText((showFavoritesOnly ? "Favorites total: " : "Total: ") + QuantityFormatter.quantityToStackSize(grandTotal) + " gp");

		content.revalidate();
		content.repaint();

		// See class javadoc's SCROLL POSITION note -- restored AFTER
		// revalidate() so content.getPreferredSize() below reflects the
		// just-rebuilt tree, not the torn-down one.
		restoreScrollPosition(enclosingScrollPane, scrollPosition);
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
		Map<String, Integer> individualRevealCounts)
	{
		StringBuilder key = new StringBuilder(256);
		key.append(favoritesOnly).append('|').append(individualView).append('|').append(showHidden).append('|').append(valuationVersion).append('|').append(searchText).append('\n');
		int[] budget = {MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED};
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
			if (individualView)
			{
				List<LootTrackerSnapshot.RecordEntry> allRecords = source.getRecords();
				int requested = individualRevealCounts == null
					? DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE
					: individualRevealCounts.getOrDefault(source.getSourceKey(), DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE);
				int allowed = allowedIndividualCount(requested, allRecords.size(), budget[0]);
				List<LootTrackerSnapshot.RecordEntry> visible = capRecords(allRecords, allowed);
				budget[0] -= visible.size();

				key.append('|');
				for (LootTrackerSnapshot.RecordEntry record : visible)
				{
					key.append(record.getObservedAt()).append('[');
					for (LootTrackerSnapshot.ItemEntry item : record.getItems())
					{
						key.append(item.getItemIdValue()).append(':').append(item.getQuantity()).append(':').append(item.isHidden()).append(',');
					}
					key.append(']');
				}
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

	/**
	 * Pure and package-private static for direct unit testing: how many of a
	 * source's individual records may actually be rendered/fingerprinted
	 * right now, given how many the player has asked to see
	 * ({@code requested}), how many really exist
	 * ({@code totalAvailable}), and how much of the shared total budget
	 * remains ({@code remainingBudget}) -- never more than any one of the
	 * three, never negative.
	 */
	static int allowedIndividualCount(int requested, int totalAvailable, int remainingBudget)
	{
		int want = Math.min(requested, totalAvailable);
		return Math.max(0, Math.min(want, remainingBudget));
	}

	/**
	 * Pure and package-private static for direct unit testing.
	 * {@code records} is expected in observation order (oldest first --
	 * see {@code LootTrackerSource}'s own javadoc); returns the LAST
	 * (most recent) {@code max} of them, still in oldest-first order,
	 * unchanged (same list, not a copy) when already within the cap.
	 */
	static List<LootTrackerSnapshot.RecordEntry> capRecords(List<LootTrackerSnapshot.RecordEntry> records, int max)
	{
		if (records.size() <= max)
		{
			return records;
		}
		return records.subList(records.size() - max, records.size());
	}

	/**
	 * {@code records} (typically capRecords()'s own output) is
	 * oldest-first, matching LootTrackerSource.getRecords()'s own
	 * observation-order contract; this returns a NEW list with that same
	 * set of records reversed to newest-first, the order the Individual
	 * view must actually render them in (newest record at the top, oldest
	 * currently-revealed record at the bottom). Never mutates its input --
	 * capRecords() can return the original backing list unmodified when
	 * already within the cap, and that list must not be reordered out
	 * from under it.
	 */
	static List<LootTrackerSnapshot.RecordEntry> newestFirst(List<LootTrackerSnapshot.RecordEntry> records)
	{
		List<LootTrackerSnapshot.RecordEntry> reversed = new ArrayList<>(records);
		Collections.reverse(reversed);
		return reversed;
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
	 * A manually-collapsed source card temporarily expands while an
	 * active search matches an item inside it, so the match is not
	 * hidden. The user's PERSISTED collapse preference
	 * ({@link LootTrackerSnapshot.SourceEntry#isCollapsed()}, backed by
	 * {@link LootTrackerPreferences}) is never written or read
	 * differently by this method -- it is a pure, package-private static
	 * function of the two inputs, called only to decide what THIS render
	 * pass shows. A source only ever reaches {@link #buildSourceCard}
	 * while {@code searchText} is non-empty because it passed
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
		return source.isCollapsed() && searchText.isEmpty();
	}

	static JLabel emptyStateLabel(String message)
	{
		// Wraps to the ACTUAL available width -- see WrappingHtmlLabel's
		// javadoc (the old fixed CSS width:180px was ~233px in Swing and clipped).
		JLabel label = new WrappingHtmlLabel(message, true);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(SpyglassTheme.TEXT_SECONDARY);
		label.setHorizontalAlignment(JLabel.CENTER);
		label.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/**
	 * One source card: header (favorite star, truncated name, kill count,
	 * collapse toggle, reset button, total-value/GP-per-kill subtitle --
	 * see {@link #buildHeaderRow}), then either the grouped item grid
	 * (default) or the individual-drop list, per {@link #individualView}
	 * -- both filtered by {@code searchText} at the ITEM level only
	 * (spec: never merges/hides a whole card except when literally
	 * nothing in it matches -- see {@link #matchesSearch}, which already
	 * excludes a fully-non-matching card before this method is called).
	 */
	private JPanel buildSourceCard(LootTrackerSnapshot.SourceEntry source, String searchText, LootValuationMode mode, String modeLabel, long sourceTotal, int[] individualRenderBudget)
	{
		JPanel section = heightBoundedPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(true);
		section.setBackground(SpyglassTheme.SURFACE_CARD);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		// See buildResetContextMenu's own javadoc -- attached to the whole
		// card so it works from the header OR the body below it.
		section.setComponentPopupMenu(buildResetContextMenu(source));

		// Every card, in both view modes, always shows its header first.
		section.add(buildHeaderRow(source, sourceTotal, searchText));

		if (effectivelyCollapsed(source, searchText))
		{
			return section;
		}

		section.add(Box.createVerticalStrut(6));

		if (individualView)
		{
			section.add(buildIndividualDropsPanel(source, searchText, mode, modeLabel, individualRenderBudget));
		}
		else
		{
			section.add(buildGroupedGrid(source, searchText, mode, modeLabel));
		}

		return section;
	}

	private JPanel buildHeaderRow(LootTrackerSnapshot.SourceEntry source, long sourceTotal, String searchText)
	{
		JPanel wrapper = heightBoundedPanel();
		wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
		wrapper.setOpaque(false);
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);

		wrapper.add(buildHeaderTopRow(source, searchText));

		// Only shown where a reliable count exists (killCount > 0, always
		// true for any source with records -- see LootTrackerSource's own
		// honest-kill-count javadoc); hidden items still contribute to
		// sourceTotal, so GP/kill here is honest too.
		if (source.getKillCount() > 0)
		{
			JLabel subtitle = new JLabel(buildHeaderSubtitleText(sourceTotal, source.getKillCount()));
			subtitle.setFont(FontManager.getRunescapeSmallFont());
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
		long perKill = killCount > 0 ? totalValue / killCount : 0L;
		return QuantityFormatter.quantityToStackSize(totalValue) + " total · " + QuantityFormatter.quantityToStackSize(perKill) + "/kill";
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

	private JPanel buildHeaderTopRow(LootTrackerSnapshot.SourceEntry source, String searchText)
	{
		JPanel headerRow = new JPanel(new BorderLayout(4, 0));
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		leftGroup.setOpaque(false);

		JLabel star = new JLabel(source.isFavorite() ? "★" : "☆");
		star.setFont(FontManager.getRunescapeBoldFont());
		star.setForeground(source.isFavorite() ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_SECONDARY);
		star.setToolTipText(source.isFavorite() ? "Unfavorite" : "Favorite");
		star.addMouseListener(new ClickListener(() ->
		{
			forEachRawKey(source, rawKey -> preferences.setFavorite(rawKey, !source.isFavorite()));
			renderFromLastSnapshot();
		}));
		leftGroup.add(star);

		JLabel title = new JLabel(buildSourceTitleText(source.getSourceName(), source.getKillCount()));
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(SpyglassTheme.TEXT_PRIMARY);
		title.setToolTipText(source.getSourceName());
		title.addMouseListener(new ClickListener(() ->
		{
			forEachRawKey(source, rawKey -> preferences.setCollapsed(rawKey, !source.isCollapsed()));
			renderFromLastSnapshot();
		}));
		leftGroup.add(title);

		JLabel collapseIndicator = new JLabel(effectivelyCollapsed(source, searchText) ? "▸" : "▾");
		collapseIndicator.setFont(FontManager.getRunescapeSmallFont());
		collapseIndicator.setForeground(SpyglassTheme.TEXT_SECONDARY);
		leftGroup.add(collapseIndicator);

		headerRow.add(leftGroup, BorderLayout.WEST);

		return headerRow;
	}

	/**
	 * Builds the right-click context menu for one source card -- currently
	 * a single "Reset loot" item (the per-card reset control is
	 * deliberately a right-click menu, not an always-visible header
	 * button). See {@link #forEachRawKey} and
	 * {@link LootTrackerPreferences#resetSource} for why every raw source
	 * key a merged card represents must be reset together, why unrelated
	 * sources are untouched, and why this only ever resets DISPLAYED
	 * counters -- raw telemetry history is never deleted). Attached via
	 * {@link JComponent#setComponentPopupMenu} on the whole card
	 * ({@link #buildSourceCard}'s {@code section}), which Swing shows for
	 * a right-click anywhere on that card -- the header row, the kill
	 * count/value subtitle, or the item grid/drop list below it -- since
	 * none of those descendant components set a popup menu of their own
	 * and so all inherit this one by default.
	 */
	private JPopupMenu buildResetContextMenu(LootTrackerSnapshot.SourceEntry source)
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem resetItem = new JMenuItem("Reset loot");
		resetItem.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Reset kills and loot totals for " + source.getSourceName() + "?\nThis only resets the DISPLAYED counters -- your recorded history is never deleted.",
				"Confirm reset", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				Instant now = Instant.now();
				forEachRawKey(source, rawKey -> preferences.resetSource(rawKey, now));
				renderFromLastSnapshot();
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

	private JPanel buildGroupedGrid(LootTrackerSnapshot.SourceEntry source, String searchText, LootValuationMode mode, String modeLabel)
	{
		List<LootTrackerSnapshot.ItemEntry> visible = visibleItems(source.getItemTotals(), searchText);
		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(visible, itemId -> unitValue(itemId, mode));

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

	private JPanel buildIndividualDropsPanel(LootTrackerSnapshot.SourceEntry source, String searchText, LootValuationMode mode, String modeLabel, int[] individualRenderBudget)
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.setAlignmentX(Component.LEFT_ALIGNMENT);

		List<LootTrackerSnapshot.RecordEntry> allRecords = source.getRecords();
		int requested = individualRevealCountBySourceKey.getOrDefault(source.getSourceKey(), DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE);
		int allowed = allowedIndividualCount(requested, allRecords.size(), individualRenderBudget[0]);
		List<LootTrackerSnapshot.RecordEntry> visibleRecords = capRecords(allRecords, allowed);
		individualRenderBudget[0] -= visibleRecords.size();

		// source.getRecords() is stored in plain OBSERVATION order
		// (oldest first, see its own javadoc); capRecords() takes the
		// tail (the most recent `allowed` records) but returns that tail
		// in the SAME oldest-first internal order. Required display
		// shape:
		//   newest record
		//   ...
		//   oldest currently-revealed record
		//   Showing most recent X of N drops
		//   [Load more]
		// Achieved by walking visibleRecords newest-to-oldest (see
		// newestFirst(), a small pure/testable helper -- same convention
		// as capRecords()/allowedIndividualCount() elsewhere in this
		// class) and rendering the truncation note + Load More button
		// AFTER the records loop. This is DISPLAY order only --
		// capRecords() itself, the budget accounting immediately above,
		// and which records are selected as "visible" are unrelated, so a
		// Load More click still extends the SAME tail further back in
		// history (the newest-first records already on screen keep their
		// same relative order; only newer/older entries are added at
		// their correct end) -- no duplication, no incorrect reordering
		// of already-visible records, and each record's own item grid
		// stays exactly as atomic (one SERVER_NPC_LOOT boundary per
		// timestamp row).
		for (LootTrackerSnapshot.RecordEntry record : newestFirst(visibleRecords))
		{
			List<LootTrackerSnapshot.ItemEntry> visible = visibleItems(record.getItems(), searchText);
			if (visible.isEmpty())
			{
				continue;
			}

			JLabel timestamp = new JLabel(record.getObservedAt() == null ? "Unknown time" : record.getObservedAt().toString());
			timestamp.setFont(FontManager.getRunescapeSmallFont());
			timestamp.setForeground(SpyglassTheme.TEXT_SECONDARY);
			timestamp.setAlignmentX(Component.LEFT_ALIGNMENT);
			column.add(timestamp);

			List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(visible, itemId -> unitValue(itemId, mode));
			JPanel grid = heightBoundedPanel();
			grid.setLayout(LootGridCell.newGridLayout());
			grid.setOpaque(false);
			grid.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (LootPricing.ValuedLootRow row : rows)
			{
				grid.add(lootGridCell(row, modeLabel, source, findHidden(source, row.getItemId())));
			}
			column.add(grid);
			column.add(Box.createVerticalStrut(4));
		}

		if (visibleRecords.size() < allRecords.size())
		{
			// See MAX_TOTAL_INDIVIDUAL_RECORDS_RENDERED/DEFAULT_INDIVIDUAL_REVEAL_PER_SOURCE's
			// own javadoc -- never silently pretend older drops don't
			// exist, just don't pay to render every one of them; "Load
			// more" lets the player reach them incrementally. Rendered
			// AFTER the records themselves -- see this method's own
			// javadoc note above for why.
			JLabel truncationNote = new JLabel("Showing most recent " + visibleRecords.size() + " of " + allRecords.size() + " drops");
			truncationNote.setFont(FontManager.getRunescapeSmallFont());
			truncationNote.setForeground(SpyglassTheme.TEXT_SECONDARY);
			truncationNote.setAlignmentX(Component.LEFT_ALIGNMENT);
			column.add(truncationNote);

			JButton loadMore = new JButton("Load more");
			loadMore.setFont(FontManager.getRunescapeSmallFont());
			loadMore.setFocusPainted(false);
			loadMore.setForeground(SpyglassTheme.TEXT_PRIMARY);
			loadMore.setBackground(SpyglassTheme.BACKGROUND_PANEL);
			loadMore.setBorder(BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT));
			loadMore.setAlignmentX(Component.LEFT_ALIGNMENT);
			loadMore.addActionListener(e ->
			{
				individualRevealCountBySourceKey.merge(source.getSourceKey(), INDIVIDUAL_REVEAL_INCREMENT, Integer::sum);
				renderFromLastSnapshot();
			});
			column.add(loadMore);
			column.add(Box.createVerticalStrut(4));
		}
		return column;
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
						renderFromLastSnapshot();
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
