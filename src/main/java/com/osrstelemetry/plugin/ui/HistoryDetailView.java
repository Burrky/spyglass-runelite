package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.session.LoadoutProvenance;
import com.osrstelemetry.plugin.session.LoadoutSnapshot;
import com.osrstelemetry.plugin.session.SessionAggregates;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Renders ONE {@link HistoryDetailModel} -- a single finalized session's full
 * detail: header (activity, time range, duration) with a Back control,
 * XP breakdown, session-owned loot (real sprites, GE/HA valuation,
 * reusing {@link LootPricing} exactly like {@code CurrentSessionView};
 * split into per-task cards for a multi-task Slayer session),
 * starting inventory (a real 4x7 slot-exact grid), starting equipment
 * (an authentic OSRS equipment-tab layout), and provenance labeling for
 * it.
 *
 * ENDING LOADOUT IS DELIBERATELY NOT RENDERED: no heading, no
 * inventory/equipment grid, no leftover empty space below Starting
 * Loadout. {@link HistoryDetailModel#getEndingLoadout()} still exists
 * and {@code Session.endingLoadout} is still resolved/persisted
 * (backward compatibility with old session files is preserved either
 * way); this view simply never reads that getter.
 *
 * PALETTE / DENSITY: deliberately reuses the exact same color constants
 * and card chrome {@code CurrentSessionView}/{@code LootTrackerView}
 * already established (LOOT_SECTION_* hex values, {@link LootGridCell},
 * {@link ItemValuationCache}, {@link LootPricing}) rather than inventing
 * a fourth palette -- see those classes' own javadoc for why each of
 * these values was chosen. Small per-class duplication of a few trivial
 * static helpers (formatNumber/formatDuration/toTitleCase) mirrors the
 * same duplication already accepted between CurrentSessionView and
 * LootTrackerView in this codebase, rather than refactoring either of
 * those already-accepted files to share code with a brand-new one.
 *
 * NO OWN JScrollPane: exactly like CurrentSessionView/LootTrackerView,
 * this panel adds its content directly; RuneLite's own outer sidebar
 * scrollpane does all scrolling (see those classes' own SCROLLING
 * notes for why a nested scrollpane is a real, previously-hit bug in
 * this codebase).
 *
 * LEVEL PROGRESS IS DELIBERATELY OMITTED: see {@link HistoryDetailModel}'s
 * own javadoc -- a finalized session's aggregates only carry an XP
 * DELTA, never an absolute XP total, so there is no honest way to
 * compute a level or a progress bar here.
 *
 * HEADING CONTRAST: {@code XP GAINED} uses {@link SpyglassTheme#ACCENT_GOLD}
 * with a thin gold underline; {@code STARTING LOADOUT} uses
 * {@link SpyglassTheme#ACCENT_BRONZE} with a matching underline, giving
 * it a clearly distinct but still warm/cohesive hierarchy from XP
 * without matching Loot's own gold value color exactly. {@link #separator}
 * uses {@link SpyglassTheme#BORDER_SOFT}.
 */
final class HistoryDetailView extends JPanel
{
	private static final int INVENTORY_COLUMNS = 4;

	/**
	 * Authentic OSRS equipment-tab layout, by slot NAME (not raw index --
	 * see class javadoc on why index alone is not fully trustworthy in
	 * this sandbox). {@code null} entries are blank paperdoll space, not
	 * an equipment slot. Any real slot name observed in the data that is
	 * NOT one of these 11 is never dropped -- see
	 * {@link #buildEquipmentGrid} -- it is appended in an overflow row
	 * instead, so this layout guess can never silently hide real data.
	 */
	private static final String[][] EQUIPMENT_LAYOUT = {
		{null, "HEAD", null},
		{"CAPE", "AMULET", "AMMO"},
		{"WEAPON", "BODY", "SHIELD"},
		{null, "LEGS", null},
		{"GLOVES", "BOOTS", "RING"},
	};

	private final SkillIconManager skillIconManager;
	private final ItemManager itemManager;
	private final ItemValuationCache itemValuationCache;
	private final OsrsTelemetryConfig config;
	private final Runnable onBack;

	private final JPanel content = new JPanel();

	/**
	 * History-owned expand state for multi-task Slayer cards (never shared
	 * with Current Session's live ledger state). Every card starts
	 * collapsed each time a session is opened -- a long Slayer session
	 * reads as a compact task list first; a click re-renders the loaded
	 * model (no disk I/O), so state survives until the detail is left.
	 */
	private final Set<Integer> expandedTaskOrdinals = new HashSet<>();
	private HistoryDetailModel lastModel;
	/** Price-cache version and GE/HA mode the current detail was drawn with. */
	private long renderedValuationVersion = -1L;
	private com.osrstelemetry.plugin.LootValuationMode renderedValuationMode;

	/** Component name prefix for a History task card (tests locate cards by it). */
	static final String HISTORY_TASK_CARD_NAME_PREFIX = "historyTask:";
	/** Component name of the structured-activity completion metric line (tests). */
	static final String COMPLETION_METRIC_NAME = "historyCompletionMetric";
	/** Component name of the structured-activity session-stats line (tests). */
	static final String ACTIVITY_STATS_NAME = "historyActivityStats";

	HistoryDetailView(SkillIconManager skillIconManager, ItemManager itemManager, ClientThread clientThread, OsrsTelemetryConfig config, Runnable onBack)
	{
		this.skillIconManager = skillIconManager;
		this.itemManager = itemManager;
		this.itemValuationCache = new ItemValuationCache(itemManager, clientThread);
		this.config = config;
		this.onBack = onBack;

		setLayout(new BorderLayout());
		setBackground(SpyglassTheme.BACKGROUND_MAIN);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		add(content, BorderLayout.NORTH);
	}

	/** Renders an explicit "could not be loaded" state -- see {@code HistoryView}'s own loadDetail() failure path and HistoryDetailModel.from()'s fail-open contract. */
	void showUnavailable()
	{
		content.removeAll();
		content.add(backRow());
		content.add(Box.createVerticalStrut(16));
		JLabel label = new JLabel("This session could not be loaded.");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(SpyglassTheme.TEXT_SECONDARY);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(label);
		content.revalidate();
		content.repaint();
	}

	void showLoading()
	{
		expandedTaskOrdinals.clear();
		lastModel = null;
		content.removeAll();
		content.add(backRow());
		content.add(Box.createVerticalStrut(16));
		JLabel label = new JLabel("Loading...");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(SpyglassTheme.TEXT_SECONDARY);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(label);
		content.revalidate();
		content.repaint();
	}

	void render(HistoryDetailModel model)
	{
		if (lastModel != null && !lastModel.getSessionId().equals(model.getSessionId()))
		{
			expandedTaskOrdinals.clear();
		}
		lastModel = model;
		renderedValuationVersion = itemValuationCache.getVersion();
		renderedValuationMode = config.lootValuationMode();
		content.removeAll();

		content.add(backRow());
		content.add(Box.createVerticalStrut(4));
		renderHeader(model);
		renderSessionBlock(model);
		renderSlayerBlock(model);
		renderProducedItems(model);
		renderXpBreakdown(model);
		renderLootSection(model);
		renderLoadoutSection(model.getStartingLoadout());

		content.revalidate();
		content.repaint();
	}

	/** Redraws the loaded detail (no disk) only if prices or GE/HA changed since it was drawn. */
	void refreshValuesIfChanged()
	{
		if (lastModel != null
			&& (itemValuationCache.getVersion() != renderedValuationVersion || config.lootValuationMode() != renderedValuationMode))
		{
			render(lastModel);
		}
	}

	void setValuationListener(Runnable listener)
	{
		itemValuationCache.setOnResolved(listener);
	}

	private JPanel backRow()
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton back = new JButton("← Back");
		back.setFont(FontManager.getRunescapeSmallFont());
		back.setForeground(SpyglassTheme.TEXT_SECONDARY);
		back.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		back.setFocusPainted(false);
		back.setBorderPainted(false);
		back.setContentAreaFilled(false);
		back.setMargin(new java.awt.Insets(2, 0, 2, 0));
		back.addActionListener(e ->
		{
			if (onBack != null)
			{
				onBack.run();
			}
		});
		row.add(back, BorderLayout.WEST);
		return row;
	}

	private void renderHeader(HistoryDetailModel model)
	{
		// Wraps: the detail page always shows the full activity name.
		JLabel headlineLabel = new WrappingHtmlLabel(model.getTitle(), false);
		headlineLabel.setName(TITLE_NAME);
		headlineLabel.setFont(FontManager.getRunescapeBoldFont());
		headlineLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		headlineLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(headlineLabel);

		String finalizedText = formatAbsoluteTime(model.getFinalizedAt());
		JLabel timeLabel = new JLabel(finalizedText == null ? "" : finalizedText);
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(timeLabel);

		// Wraps between facts instead of clipping in a narrow sidebar.
		FactLine secondaryLabel = new FactLine(FontManager.getRunescapeSmallFont(), SpyglassTheme.TEXT_SECONDARY);
		secondaryLabel.setName(FACTS_NAME);
		secondaryLabel.fact(formatDuration(model.getAccumulatedActiveDurationMillis()) + " active");
		SessionAggregates.ReliableCount reliableCount = model.getReliableCount();
		if (reliableCount != null)
		{
			String label = reliableCount.getKind() == SessionAggregates.ReliableCountKind.KILLS ? "kills" : "completions";
			secondaryLabel.fact(formatNumber(reliableCount.getSessionOccurrences()) + " " + label);
		}
		secondaryLabel.setFont(FontManager.getRunescapeSmallFont());
		secondaryLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		secondaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(secondaryLabel);
		if (model.getCompletionLabel() != null && !model.hasSessionBlock())
		{
			FactLine completionLabel = CompletionMetricText.factLine(model.getCompletionLabel(),
				model.getCompletionCount(), model.getAccumulatedActiveDurationMillis());
			completionLabel.setName(COMPLETION_METRIC_NAME);
			content.add(completionLabel);
		}
		String statsText = model.hasSessionBlock() ? null : model.getActivityStatsText();
		if (statsText != null)
		{
			// Session summary only (permits / final points): no live game state is ever kept.
			JLabel statsLabel = new WrappingHtmlLabel(statsText, false);
			statsLabel.setName(ACTIVITY_STATS_NAME);
			statsLabel.setFont(FontManager.getRunescapeSmallFont());
			statsLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			statsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(statsLabel);
		}
		if (model.isMultiTaskSlayerSession())
		{
			// The whole-session metrics on this page cover every task listed here.
			JLabel tasksLabel = new WrappingHtmlLabel(slayerSummaryText(model), false);
			tasksLabel.setFont(FontManager.getRunescapeSmallFont());
			tasksLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			tasksLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(tasksLabel);
		}

		content.add(Box.createVerticalStrut(6));
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	static final String TITLE_NAME = "historyTitle";
	static final String FACTS_NAME = "historyFacts";
	static final String XP_TITLE_NAME = "historyXpTitle";
	static final String XP_TOTAL_NAME = "historyXpTotal";
	static final String SESSION_BLOCK_NAME = "historySessionBlock";
	static final String SESSION_GAMES_NAME = "historySessionGames";
	static final String SESSION_RATE_NAME = "historySessionRate";
	static final String SESSION_CURRENCY_PREFIX = "historySessionCurrency:";
	static final String SESSION_AVG_NAME = "historySessionAvg";
	static final String SESSION_BEST_NAME = "historySessionBest";
	static final String PRODUCED_ITEMS_NAME = "historyProducedItems";
	static final String PRODUCED_ITEMS_TOTAL_NAME = "historyProducedItemsTotal";

	/**
	 * SESSION (Tempoross / Guardians of the Rift): a dedicated, airy block
	 * instead of dense inline header text --
	 * <pre>
	 * SESSION
	 * Games 2                 7.5/hr
	 * Permits +8              (GotR: Elemental +11 / Catalytic +13)
	 * Avg 3,870              Best 3,940
	 * </pre>
	 * Each row only when it has a value. Session summary only: no live game
	 * state is ever kept in History.
	 */
	private void renderSessionBlock(HistoryDetailModel model)
	{
		if (!model.hasSessionBlock())
		{
			return;
		}
		JPanel block = new JPanel();
		block.setName(SESSION_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(sectionTitle("SESSION"));

		String label = model.getCompletionLabel();
		int count = model.getCompletionCount();
		Double rate = CompletionMetricText.perHour(count, model.getAccumulatedActiveDurationMillis());
		block.add(sessionRow(label + " " + formatNumber(count), SESSION_GAMES_NAME,
			rate == null ? null : String.format(java.util.Locale.ROOT, "%.1f/hr", rate), SESSION_RATE_NAME));

		String activityKey = model.getActivityKey();
		java.util.List<String> keys = CompletionMetricText.currencyKeysFor(activityKey);
		if (keys.isEmpty())
		{
			String currency = CompletionMetricText.currencyLabelFor(activityKey);
			long gained = model.getCurrencyGained(null);
			if (currency != null && gained > 0)
			{
				block.add(sessionRow(currency + " " + CompletionMetricText.currencyText(gained), SESSION_CURRENCY_PREFIX + "single", null, null));
			}
		}
		else
		{
			for (String key : keys)
			{
				long gained = model.getCurrencyGained(key);
				if (gained > 0)
				{
					block.add(sessionRow(CompletionMetricText.currencyLabelForKey(key) + " " + CompletionMetricText.currencyText(gained),
						SESSION_CURRENCY_PREFIX + key, null, null));
				}
			}
		}

		Long average = model.getAverageScore();
		Integer best = model.getBestScore();
		if (average != null && best != null)
		{
			JPanel row = sessionRow("Avg " + CompletionMetricText.pointsText(average), SESSION_AVG_NAME,
				"Best " + CompletionMetricText.pointsText(best), SESSION_BEST_NAME);
			int scored = model.getScoredGames();
			row.setToolTipText(CompletionMetricText.scoreNoun(activityKey) + " per completed game (" + scored + " fully observed game"
				+ (scored == 1 ? "" : "s") + (scored < count ? " of " + count : "") + ")");
			block.add(row);
		}
		content.add(block);
		content.add(Box.createVerticalStrut(6));
	}

	static final String SLAYER_BLOCK_NAME = "historySlayerBlock";
	static final String SLAYER_KILLS_NAME = "historySlayerKills";
	static final String SLAYER_KILL_RATE_NAME = "historySlayerKillRate";
	static final String SLAYER_SLAUGHTER_NAME = "historySlayerSlaughter";
	static final String SLAYER_EXPEDITIOUS_NAME = "historySlayerExpeditious";

	/**
	 * SLAYER (same SESSION-block language as Tempoross / GotR): what happened
	 * this Slayer session beyond the task cards -- observed kills and their
	 * rate, and native bracelet effects. Each row only when known; absent for
	 * older sessions. Live, transient values (remaining, points, streak) are
	 * never kept in History.
	 */
	private void renderSlayerBlock(HistoryDetailModel model)
	{
		if (!model.hasSlayerBlock())
		{
			return;
		}
		JPanel block = new JPanel();
		block.setName(SLAYER_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(sectionTitle("SLAYER"));
		Integer kills = model.getSlayerKills();
		if (kills != null)
		{
			Long rate = com.osrstelemetry.plugin.ui.model.SlayerDashboard.perHour(kills, model.getAccumulatedActiveDurationMillis());
			block.add(sessionRow("Kills " + formatNumber(kills), SLAYER_KILLS_NAME,
				rate == null ? null : formatNumber(rate) + "/hr", SLAYER_KILL_RATE_NAME));
		}
		int slaughter = model.getSlayerSlaughterProcs();
		int expeditious = model.getSlayerExpeditiousProcs();
		if (slaughter > 0 || expeditious > 0)
		{
			String s1 = "Slaughter " + formatNumber(slaughter);
			String e1 = "Expeditious " + formatNumber(expeditious);
			block.add(slaughter > 0
				? sessionRow(s1, SLAYER_SLAUGHTER_NAME, expeditious > 0 ? e1 : null, SLAYER_EXPEDITIOUS_NAME)
				: sessionRow(e1, SLAYER_EXPEDITIOUS_NAME, null, null));
		}
		content.add(block);
		content.add(Box.createVerticalStrut(6));
	}

	/** RUNES CRAFTED (or any future produced-items list): header with total, then the compact item grid. */
	private void renderProducedItems(HistoryDetailModel model)
	{
		java.util.List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> items = model.getProducedItems();
		if (items.isEmpty())
		{
			return;
		}
		String title = com.osrstelemetry.plugin.activity.StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(model.getActivityKey())
			? "Runes crafted" : "Produced";
		// Title alone (bold) fits the narrowest sidebar; the total sits on its
		// own small line rather than making one long title that clips at 150px.
		long total = model.getProducedItemTotal();
		JLabel header = sectionTitle(title.toUpperCase(java.util.Locale.ROOT));
		header.setName(PRODUCED_ITEMS_NAME);
		header.setToolTipText(String.format(java.util.Locale.ROOT, "%,d", total) + " produced this session");
		content.add(header);
		content.add(Box.createVerticalStrut(2));
		JLabel totalLabel = new JLabel(ProducedItemGrid.compactTotal(total) + " total");
		totalLabel.setName(PRODUCED_ITEMS_TOTAL_NAME);
		totalLabel.setFont(FontManager.getRunescapeSmallFont());
		totalLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		totalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(totalLabel);
		content.add(Box.createVerticalStrut(3));
		content.add(ProducedItemGrid.build(items, itemManager));
		content.add(Box.createVerticalStrut(8));
	}

	/** Same gold title + rule as "XP GAINED", so the History sections read as one family. */
	private static JLabel sectionTitle(String text)
	{
		JLabel title = new JLabel(text);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(SpyglassTheme.ACCENT_GOLD);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.ACCENT_GOLD),
			BorderFactory.createEmptyBorder(0, 0, 4, 0)));
		return title;
	}

	/** Left text and optional right text on one line that never clips. */
	private static JPanel sessionRow(String left, String leftName, String right, String rightName)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel l = new JLabel(left);
		l.setName(leftName);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SpyglassTheme.TEXT_PRIMARY);
		row.add(l, BorderLayout.WEST);
		if (right != null)
		{
			JLabel r = new JLabel(right);
			r.setName(rightName);
			r.setFont(FontManager.getRunescapeSmallFont());
			r.setForeground(SpyglassTheme.TEXT_SECONDARY);
			r.setHorizontalAlignment(JLabel.RIGHT);
			row.add(r, BorderLayout.EAST);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 2));
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		return row;
	}

	private void renderXpBreakdown(HistoryDetailModel model)
	{
		if (model.getXpRows().isEmpty())
		{
			return;
		}

		// Caption left, total right (like LOOT's header): never one long
		// line that clips in a narrow sidebar.
		JPanel title = new JPanel(new BorderLayout(6, 0));
		title.setOpaque(false);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.ACCENT_GOLD),
			BorderFactory.createEmptyBorder(0, 0, 4, 0)));
		JLabel caption = new JLabel("XP GAINED");
		caption.setName(XP_TITLE_NAME);
		caption.setFont(FontManager.getRunescapeBoldFont());
		caption.setForeground(SpyglassTheme.ACCENT_GOLD);
		title.add(caption, BorderLayout.WEST);
		JLabel total = new JLabel(formatNumber(model.getTotalXpGained()));
		total.setName(XP_TOTAL_NAME);
		total.setFont(FontManager.getRunescapeBoldFont());
		total.setForeground(SpyglassTheme.ACCENT_GOLD);
		total.setToolTipText(formatNumber(model.getTotalXpGained()) + " XP gained in total");
		title.add(total, BorderLayout.EAST);
		title.setMaximumSize(new Dimension(Integer.MAX_VALUE, title.getPreferredSize().height));
		content.add(title);

		for (HistoryDetailModel.XpRow row : model.getXpRows())
		{
			JPanel line = new JPanel(new BorderLayout(6, 0));
			line.setOpaque(false);
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

			JPanel west = new JPanel(new BorderLayout(4, 0));
			west.setOpaque(false);
			west.add(new JLabel(skillIcon(row.getSkill())), BorderLayout.WEST);
			JLabel nameLabel = new JLabel(toTitleCase(row.getSkill()));
			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			nameLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
			west.add(nameLabel, BorderLayout.CENTER);
			line.add(west, BorderLayout.WEST);

			JLabel xpLabel = new JLabel("+" + formatNumber(row.getXpGained()) + " XP");
			xpLabel.setFont(FontManager.getRunescapeSmallFont());
			xpLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			line.add(xpLabel, BorderLayout.EAST);

			content.add(line);
			content.add(Box.createVerticalStrut(2));
		}
		content.add(Box.createVerticalStrut(6));
	}

	private void renderLootSection(HistoryDetailModel model)
	{
		// A multi-task Slayer session always lists its tasks, even at 0 gp.
		if (model.getLootRows().isEmpty() && !model.isMultiTaskSlayerSession())
		{
			return;
		}

		LootValuationMode mode = config.lootValuationMode();
		List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(model.getLootRows(), itemId -> unitValue(itemId, mode));
		long totalValue = LootPricing.sumTotalValue(rows);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(true);
		section.setBackground(SpyglassTheme.SURFACE_CARD);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		section.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));

		JPanel headerRow = new JPanel(new BorderLayout());
		headerRow.setOpaque(false);
		headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel titleLabel = new JLabel("LOOT  ×" + model.getLootDropCount());
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		titleLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 5, 0));
		headerRow.add(titleLabel, BorderLayout.WEST);

		JLabel totalLabel = new JLabel(QuantityFormatter.quantityToStackSize(totalValue) + " gp");
		totalLabel.setFont(FontManager.getRunescapeBoldFont());
		totalLabel.setForeground(SpyglassTheme.ACCENT_GOLD);
		headerRow.add(totalLabel, BorderLayout.EAST);
		section.add(headerRow);
		section.add(Box.createVerticalStrut(6));

		String modeLabel = mode == LootValuationMode.HIGH_ALCH ? "High Alch" : "Grand Exchange";
		if (model.isMultiTaskSlayerSession())
		{
			renderSlayerTaskCards(section, model, mode, modeLabel);
		}
		else
		{
			section.add(lootGrid(rows, modeLabel));
		}

		content.add(section);
		content.add(Box.createVerticalStrut(6));
	}

	/** The shared 5-column loot grid (see LootGridCell.newGridLayout()). */
	private JPanel lootGrid(List<LootPricing.ValuedLootRow> rows, String modeLabel)
	{
		JPanel grid = new JPanel(LootGridCell.newGridLayout());
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		for (LootPricing.ValuedLootRow row : rows)
		{
			grid.add(lootGridCell(row, modeLabel));
		}
		return grid;
	}

	/**
	 * MULTI-TASK SLAYER SESSION: under the unchanged session-wide LOOT header
	 * (its total stays the canonical session value), one collapsible card per
	 * persisted assignment, oldest first -- History reads as the session's
	 * chronological progression (Current Session is newest-first because
	 * there the current task is what matters). Each card holds only that
	 * assignment's persisted loot, valued exactly like the session grid,
	 * plus a compact line of the XP tracked during it.
	 */
	private void renderSlayerTaskCards(JPanel section, HistoryDetailModel model, LootValuationMode mode, String modeLabel)
	{
		boolean first = true;
		for (HistoryDetailModel.SlayerTask task : model.getSlayerTasks())
		{
			if (!first)
			{
				section.add(Box.createVerticalStrut(4));
			}
			first = false;

			List<LootPricing.ValuedLootRow> rows = LootPricing.valueAndSort(task.getLootRows(), itemId -> unitValue(itemId, mode));
			int ordinal = task.getOrdinal();
			boolean expanded = expandedTaskOrdinals.contains(ordinal);
			section.add(SlayerTaskCards.card(HISTORY_TASK_CARD_NAME_PREFIX + ordinal, expanded,
				SlayerTaskCards.title(task.getTaskName(), task.getUnitsCompleted(), task.getEndReason()),
				SlayerTaskCards.tooltip(task.getTaskName(), task.getUnitsCompleted(), task.isBootstrapped(),
					task.getRemaining(), task.getEndReason(), historyTaskDetails(task)),
				SpyglassTheme.TEXT_PRIMARY, LootPricing.sumTotalValue(rows), () -> toggleSlayerTask(ordinal),
				expanded ? taskCardContent(task, rows, modeLabel) : null));
		}
	}

	/** Tooltip facts for a finished task: kills, bracelet effects, active time, master -- each only when known. */
	static List<String> historyTaskDetails(HistoryDetailModel.SlayerTask task)
	{
		List<String> lines = new java.util.ArrayList<>();
		if (task.getKills() != null)
		{
			lines.add(task.getKills() + (task.getKills() == 1 ? " kill" : " kills"));
		}
		if (task.getSlaughterProcs() != null && task.getSlaughterProcs() > 0)
		{
			lines.add("Slaughter " + task.getSlaughterProcs());
		}
		if (task.getExpeditiousProcs() != null && task.getExpeditiousProcs() > 0)
		{
			lines.add("Expeditious " + task.getExpeditiousProcs());
		}
		if (task.getActiveDurationMillis() != null)
		{
			lines.add(formatDuration(task.getActiveDurationMillis()) + " active");
		}
		String master = com.osrstelemetry.plugin.ui.model.SlayerDashboard.masterName(task.getMasterId());
		if (master != null)
		{
			lines.add(master);
		}
		return lines;
	}

	/** Expanded body: the assignment's loot grid and/or its XP line; null when it has neither. */
	private JPanel taskCardContent(HistoryDetailModel.SlayerTask task, List<LootPricing.ValuedLootRow> rows, String modeLabel)
	{
		if (rows.isEmpty() && task.getXpRows().isEmpty())
		{
			return null;
		}
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (!rows.isEmpty())
		{
			body.add(lootGrid(rows, modeLabel));
		}
		if (!task.getXpRows().isEmpty())
		{
			if (!rows.isEmpty())
			{
				body.add(Box.createVerticalStrut(4));
			}
			JLabel xp = new WrappingHtmlLabel(taskXpText(task), false);
			xp.setFont(FontManager.getRunescapeSmallFont());
			xp.setForeground(SpyglassTheme.TEXT_SECONDARY);
			xp.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(xp);
		}
		return body;
	}

	/** Package-private so tests can drive a card click exactly as the mouse listener does. */
	void toggleSlayerTask(int ordinal)
	{
		if (!expandedTaskOrdinals.remove(ordinal))
		{
			expandedTaskOrdinals.add(ordinal);
		}
		if (lastModel != null)
		{
			render(lastModel);
		}
	}

	/** "6 tasks  •  5 completed  •  1 skipped" -- only statuses the persisted records prove. */
	static String slayerSummaryText(HistoryDetailModel model)
	{
		StringBuilder sb = new StringBuilder(HistoryEntry.taskCountText(model.getSlayerTasks().size()));
		if (model.getCompletedTaskCount() > 0)
		{
			sb.append("  •  ").append(model.getCompletedTaskCount()).append(" completed");
		}
		if (model.getSkippedTaskCount() > 0)
		{
			sb.append("  •  ").append(model.getSkippedTaskCount()).append(" skipped");
		}
		return sb.toString();
	}

	/** "XP  +136 Ranged  •  +46 Hitpoints  •  +34 Slayer" (largest first). */
	static String taskXpText(HistoryDetailModel.SlayerTask task)
	{
		StringBuilder sb = new StringBuilder("XP ");
		boolean first = true;
		for (HistoryDetailModel.XpRow row : task.getXpRows())
		{
			sb.append(first ? " +" : "  •  +").append(formatNumber(row.getXpGained())).append(' ').append(toTitleCase(row.getSkill()));
			first = false;
		}
		return sb.toString();
	}

	/** Renders only the STARTING loadout -- see class javadoc's ENDING LOADOUT note. */
	private void renderLoadoutSection(LoadoutSnapshot loadout)
	{
		JLabel titleLabel = new JLabel("STARTING LOADOUT");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(SpyglassTheme.ACCENT_BRONZE);
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleLabel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(4, 0, 0, 0),
			BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.ACCENT_BRONZE),
				BorderFactory.createEmptyBorder(0, 0, 4, 0))));
		content.add(titleLabel);

		content.add(provenanceLabel(loadout.getProvenance()));
		content.add(Box.createVerticalStrut(4));

		if (loadout.getProvenance() == LoadoutProvenance.UNAVAILABLE)
		{
			return;
		}

		JLabel inventoryLabel = new JLabel("Inventory");
		inventoryLabel.setFont(FontManager.getRunescapeSmallFont());
		inventoryLabel.setForeground(SpyglassTheme.TEXT_MUTED);
		inventoryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(inventoryLabel);
		content.add(Box.createVerticalStrut(3));
		content.add(buildInventoryGrid(loadout));
		content.add(Box.createVerticalStrut(6));

		JLabel equipmentLabel = new JLabel("Equipment");
		equipmentLabel.setFont(FontManager.getRunescapeSmallFont());
		equipmentLabel.setForeground(SpyglassTheme.TEXT_MUTED);
		equipmentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(equipmentLabel);
		content.add(Box.createVerticalStrut(3));
		content.add(buildEquipmentGrid(loadout));
		content.add(Box.createVerticalStrut(6));
	}

	private JLabel provenanceLabel(LoadoutProvenance provenance)
	{
		String text;
		Color color;
		switch (provenance)
		{
			case PRE_START:
				text = "Captured before the session started";
				color = SpyglassTheme.STATUS_SUCCESS;
				break;
			case FALLBACK_AT_OR_AFTER_START:
				text = "Captured shortly after the session started";
				color = SpyglassTheme.STATUS_SUSPENDED;
				break;
			case UNAVAILABLE:
			default:
				text = "Not captured for this session";
				color = SpyglassTheme.TEXT_MUTED;
				break;
		}
		JLabel label = new WrappingHtmlLabel(text, false);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel buildInventoryGrid(LoadoutSnapshot loadout)
	{
		List<StorageState.StorageItem> items = new ArrayList<>(loadout.getInventory());
		items.sort((a, b) -> Integer.compare(a.getSlot(), b.getSlot()));

		JPanel grid = new JPanel(new GridLayout(0, INVENTORY_COLUMNS, 3, 3));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		for (StorageState.StorageItem item : items)
		{
			grid.add(loadoutCell(item));
		}
		return grid;
	}

	/**
	 * Returns {@code layoutGrid} (the paperdoll-shaped slot grid)
	 * directly for the common case; an extra BoxLayout wrapper is built
	 * only on the rare path where an unknown equipment slot needs an
	 * overflow row appended below it.
	 */
	private JPanel buildEquipmentGrid(LoadoutSnapshot loadout)
	{
		Map<String, StorageState.StorageItem> bySlotName = new LinkedHashMap<>();
		List<StorageState.StorageItem> overflow = new ArrayList<>();
		for (StorageState.StorageItem item : loadout.getEquipment())
		{
			String slotName = item.getEquipmentSlot();
			if (slotName != null && isKnownEquipmentSlot(slotName))
			{
				bySlotName.put(slotName, item);
			}
			else
			{
				overflow.add(item);
			}
		}
		overflow.sort((a, b) -> Integer.compare(a.getSlot(), b.getSlot()));

		JPanel layoutGrid = new JPanel(new GridLayout(EQUIPMENT_LAYOUT.length, 3, 3, 3));
		layoutGrid.setOpaque(false);
		layoutGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
		layoutGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		for (String[] row : EQUIPMENT_LAYOUT)
		{
			for (String slotName : row)
			{
				if (slotName == null)
				{
					layoutGrid.add(blankCell());
				}
				else
				{
					StorageState.StorageItem item = bySlotName.get(slotName);
					layoutGrid.add(item == null ? blankCell() : loadoutCell(item));
				}
			}
		}

		if (overflow.isEmpty())
		{
			return layoutGrid;
		}

		JPanel outer = new JPanel();
		outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
		outer.setOpaque(false);
		outer.setAlignmentX(Component.LEFT_ALIGNMENT);
		outer.add(layoutGrid);
		outer.add(Box.createVerticalStrut(3));
		JPanel overflowGrid = new JPanel(new GridLayout(0, 3, 3, 3));
		overflowGrid.setOpaque(false);
		overflowGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
		overflowGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		for (StorageState.StorageItem item : overflow)
		{
			overflowGrid.add(loadoutCell(item));
		}
		outer.add(overflowGrid);
		return outer;
	}

	private static boolean isKnownEquipmentSlot(String slotName)
	{
		for (String[] row : EQUIPMENT_LAYOUT)
		{
			for (String candidate : row)
			{
				if (slotName.equals(candidate))
				{
					return true;
				}
			}
		}
		return false;
	}

	private JPanel blankCell()
	{
		JPanel filler = new JPanel();
		filler.setOpaque(false);
		filler.setPreferredSize(new Dimension(40, 40));
		return filler;
	}

	private LootGridCell loadoutCell(StorageState.StorageItem item)
	{
		AsyncBufferedImage icon = null;
		if (item.getItemId() > 0 && item.getQuantity() > 0)
		{
			try
			{
				icon = itemManager.getImage(item.getItemId(), LootGridCell.clampToInt(item.getQuantity()), false);
			}
			catch (RuntimeException e)
			{
				// Defensive only -- an unresolvable item id must never take the panel down.
			}
		}
		String name = item.getName() == null ? "Empty" : item.getName();
		String tooltip = "<html><b>" + escapeHtml(name) + "</b>"
			+ (item.getQuantity() > 1 ? "<br>Qty: " + formatNumber(item.getQuantity()) : "") + "</html>";
		return new LootGridCell(icon, item.getQuantity(), tooltip);
	}

	private LootGridCell lootGridCell(LootPricing.ValuedLootRow row, String modeLabel)
	{
		AsyncBufferedImage icon = null;
		if (row.getItemId() != null)
		{
			try
			{
				icon = itemManager.getImage(row.getItemId(), LootGridCell.clampToInt(row.getQuantity()), false);
			}
			catch (RuntimeException e)
			{
				// Defensive only.
			}
		}
		String tooltip = LootPricing.buildTooltipHtml(row.getItemName(), row.getQuantity(), modeLabel, row.getUnitValue(), row.getTotalValue(), null);
		return new LootGridCell(icon, row.getQuantity(), tooltip);
	}

	private long unitValue(int itemId, LootValuationMode mode)
	{
		if (mode == LootValuationMode.HIGH_ALCH)
		{
			return itemValuationCache.getHighAlchPrice(itemId);
		}
		return itemValuationCache.getGePrice(itemId);
	}

	private ImageIcon skillIcon(String skillName)
	{
		try
		{
			return new ImageIcon(skillIconManager.getSkillImage(Skill.valueOf(skillName)));
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			return new ImageIcon();
		}
	}

	private JSeparator separator()
	{
		JSeparator separator = new JSeparator();
		separator.setForeground(SpyglassTheme.BORDER_SOFT);
		separator.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		separator.setAlignmentX(Component.LEFT_ALIGNMENT);
		return separator;
	}

	private static String toTitleCase(String enumName)
	{
		if (enumName == null || enumName.isEmpty())
		{
			return "";
		}
		String[] parts = enumName.split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts)
		{
			if (part.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(part.substring(0, 1).toUpperCase()).append(part.substring(1).toLowerCase());
		}
		return sb.toString();
	}

	private static String formatNumber(long value)
	{
		return String.format("%,d", value);
	}

	private static String formatDuration(long millis)
	{
		long totalSeconds = millis / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		if (hours > 0)
		{
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Best-effort local-time formatting of an ISO-8601 instant string; returns null (never throws) on anything unparseable so a malformed timestamp never blanks out the whole header. */
	private static String formatAbsoluteTime(String isoInstant)
	{
		if (isoInstant == null)
		{
			return null;
		}
		try
		{
			Instant instant = Instant.parse(isoInstant);
			return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withZone(ZoneId.systemDefault())
				.format(instant);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}
}
