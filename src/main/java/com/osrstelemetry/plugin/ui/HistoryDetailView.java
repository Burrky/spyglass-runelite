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
		renderSkillBlock(model);
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
		String superiors = slayerSuperiorsText(model);
		if (superiors != null)
		{
			JLabel superiorsLabel = new WrappingHtmlLabel(superiors, false);
			superiorsLabel.setName(SLAYER_SUPERIORS_NAME);
			superiorsLabel.setFont(FontManager.getRunescapeSmallFont());
			superiorsLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
			superiorsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(superiorsLabel);
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
		if (CompletionMetricText.isPestControl(model.getActivityKey()))
		{
			addPestControlSessionRows(block, model);
			content.add(block);
			content.add(Box.createVerticalStrut(6));
			return;
		}
		if (CompletionMetricText.isWintertodt(model.getActivityKey()))
		{
			addWintertodtSessionRows(block, model);
			content.add(block);
			content.add(Box.createVerticalStrut(6));
			return;
		}

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

	/**
	 * PEST CONTROL session summary (no live game state and no goal -- those
	 * are transient / planning state):
	 * <pre>
	 * Games won 3                 Avg 2:04
	 * Commendations +24           31.0/hr
	 * </pre>
	 * Avg = entry-to-win time of won games observed from their start; the
	 * rate is per hour of game (island) time, never outpost waiting.
	 */
	private static void addPestControlSessionRows(JPanel block, HistoryDetailModel model)
	{
		int games = model.getCompletionCount();
		Long average = model.getAverageScore();
		int scored = model.getScoredGames();
		JPanel first = sessionRow(model.getCompletionLabel() + " " + formatNumber(games), SESSION_GAMES_NAME,
			average == null ? null : "Avg " + CompletionMetricText.gameTime(average), SESSION_AVG_NAME);
		if (average != null)
		{
			first.setToolTipText("Average game time, island entry to win (" + scored + " won game" + (scored == 1 ? "" : "s")
				+ " seen from the start" + (scored < games ? " of " + games : "") + ")");
		}
		block.add(first);
		long gained = model.getCurrencyGained(null);
		if (gained > 0)
		{
			Double rate = CompletionMetricText.commendationsPerHour(gained, model.getRoundMillis(), model.getRoundsTimed(), 0L,
				model.getAccumulatedActiveDurationMillis());
			JPanel row = sessionRow("Commendations " + CompletionMetricText.currencyText(gained), SESSION_CURRENCY_PREFIX + "single",
				rate == null ? null : String.format(java.util.Locale.ROOT, "%.1f/hr", rate), SESSION_RATE_NAME);
			row.setToolTipText("Void Knight Commendation points earned this session"
				+ (rate == null ? "" : "; per hour of game time (outpost waiting never counts)"));
			block.add(row);
		}
	}

	static final String WT_ROUND_ROW_NAME = "historyWtRounds";
	static final String WT_ACTIONS_NAME = "historyWtActions";
	static final String WT_PLAYSTYLE_ROW_NAME = "historyWtPlaystyle";
	static final String WT_DOWNTIME_NAME = "historyWtDowntime";

	/**
	 * WINTERTODT session summary (outcomes only -- no warmth / energy samples,
	 * no live state, no playstyle or goal):
	 * <pre>
	 * Subdues 6                    Avg 742 pts
	 * Points 4,452                 Rewards +12
	 * Avg round 3:41               9.6/hr
	 * Roots fed 120 • Kindling fed 64 • Lit 6 ...
	 * Downtime: Agility +540 XP (the arena Gap)
	 * </pre>
	 */
	private static void addWintertodtSessionRows(JPanel block, HistoryDetailModel model)
	{
		int subdues = model.getCompletionCount();
		Long avg = model.getAverageScore();
		block.add(sessionRow(model.getCompletionLabel() + " " + formatNumber(subdues), SESSION_GAMES_NAME,
			avg == null ? null : "Avg " + formatNumber(avg) + " pts", SESSION_AVG_NAME));
		long rewards = model.getCurrencyGained(com.osrstelemetry.plugin.collectors.StructuredActivityCollector.WINTERTODT_REWARDS);
		long total = model.getScoreTotal();
		if (total > 0 || rewards > 0)
		{
			block.add(sessionRow("Points " + formatNumber(total), SESSION_CURRENCY_PREFIX + "points",
				rewards > 0 ? "Rewards " + CompletionMetricText.currencyText(rewards) : null, SESSION_CURRENCY_PREFIX + "rewards"));
		}
		int complete = model.getCompleteRounds();
		Double rate = CompletionMetricText.perHour((int) Math.min(Integer.MAX_VALUE, rewards), model.getAccumulatedActiveDurationMillis());
		if (complete > 0 || rate != null)
		{
			JPanel row = sessionRow(complete > 0 ? "Avg round " + CompletionMetricText.gameTime(model.getCompleteRoundMillis() / complete / 1000L) : "Avg round -",
				WT_ROUND_ROW_NAME, rate == null ? null : String.format(java.util.Locale.ROOT, "%.1f/hr", rate), SESSION_RATE_NAME);
			row.setToolTipText("Average length of rounds seen from start to end; rewards per hour of active Wintertodt time");
			block.add(row);
		}
		String playstyle = model.getPlaystyle();
		if (playstyle != null && !playstyle.trim().isEmpty())
		{
			JPanel row = sessionRow("Playstyle " + playstyle, WT_PLAYSTYLE_ROW_NAME, null, null);
			row.setToolTipText("The Wintertodt playstyle in effect (Auto = never resolved this session)");
			block.add(row);
		}
		String actions = CompletionMetricText.wintertodtActions(model.getActionCounts());
		if (actions != null)
		{
			JLabel line = new WrappingHtmlLabel(actions, false);
			line.setName(WT_ACTIONS_NAME);
			line.setFont(FontManager.getRunescapeSmallFont());
			line.setForeground(SpyglassTheme.TEXT_SECONDARY);
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			block.add(line);
		}
		for (HistoryDetailModel.XpRow xp : model.getXpRows())
		{
			if ("agility".equalsIgnoreCase(xp.getSkill()) && xp.getXpGained() > 0)
			{
				JPanel row = sessionRow("Downtime: Agility +" + formatNumber(xp.getXpGained()) + " XP", WT_DOWNTIME_NAME, null, null);
				row.setToolTipText("Agility XP inside the arena (the Gap shortcut between rounds) stays part of Wintertodt");
				block.add(row);
			}
		}
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

	static final String SKILL_BLOCK_NAME = "historySkillBlock";
	static final String SKILL_PRODUCED_NAME = "historySkillProduced";
	static final String SKILL_PRODUCED_RATE_NAME = "historySkillProducedRate";
	static final String SKILL_BANKED_NAME = "historySkillBanked";
	static final String SKILL_BANKED_RATE_NAME = "historySkillBankedRate";
	static final String SKILL_XP_NAME = "historySkillXp";
	static final String SKILL_XP_RATE_NAME = "historySkillXpRate";
	static final String SKILL_METHOD_PREFIX = "historySkillMethod:";
	static final String SKILL_CYCLES_NAME = "historySkillCycles";
	static final String SKILL_CYCLES_RATE_NAME = "historySkillCyclesRate";
	static final String SKILL_CYCLE_AVG_NAME = "historySkillCycleAvg";
	static final String SKILL_CYCLE_BEST_NAME = "historySkillCycleBest";
	static final String SKILL_FAILURES_NAME = "historySkillFailures";
	static final String SKILL_REWARD_PREFIX = "historySkillReward:";
	static final String SKILL_METHODS_TITLE_NAME = "historySkillMethodsTitle";
	static final String SKILL_AUTO_TRANSFORMED_NAME = "historySkillAutoTransformed";
	static final String SKILL_CONSUMED_NAME = "historySkillConsumed";
	static final String SKILL_CYCLE_YIELD_PREFIX = "historySkillCycleYield:";
	static final String SKILL_OCCURRENCE_LINE_PREFIX = "historySkillOccurrenceLine:";
	static final String SKILL_CONSUMED_PREFIX = "historySkillConsumed:";
	static final String SKILL_SECONDARY_PREFIX = "historySkillSecondary:";
	static final String SKILL_OCCURRENCES_NAME = "historySkillOccurrences";
	static final String SKILL_OCCURRENCE_PREFIX = "historySkillOccurrence:";
	static final String SKILL_OWN_ROW_PREFIX = "historySkillRow:";

	/**
	 * SKILL COMPANION session summary (Mining) -- what was actually
	 * accomplished; per-resource counts are the produced grid below it:
	 * <pre>
	 * MINING
	 * Mined 4,821                     428/hr
	 * Banked 3,912 (81%)              347/hr
	 * Mining XP 168,900            15,000/hr
	 * Amethyst                        1:42:10
	 * Iron                              31:05
	 * </pre>
	 * Rates are per hour of the session's active time (logged-out time and
	 * long idles never count) and only once it reaches 5 minutes. Targets are
	 * planning state and never part of History.
	 */
	private void renderSkillBlock(HistoryDetailModel model)
	{
		com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill = model.getSkillDefinition();
		com.osrstelemetry.plugin.session.SessionAggregates.SkillSessionStats stats = model.getSkillStats();
		if (skill == null || stats == null)
		{
			return;
		}
		boolean primaryOnly = skill.historyCountsPrimaryOnly();
		long produced = primaryOnly ? primaryTotal(skill, model.getProducedItems()) : model.getProducedItemTotal();
		long banked = 0L;
		if (stats.getBanked() != null)
		{
			for (com.osrstelemetry.plugin.session.SessionAggregates.ProducedItemAggregate b : stats.getBanked())
			{
				if (!primaryOnly || isPrimary(skill, b.getItemId()))
				{
					banked += b.getQuantity();
				}
			}
		}
		long active = model.getAccumulatedActiveDurationMillis();
		boolean rated = active >= com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
		JPanel block = new JPanel();
		block.setName(SKILL_BLOCK_NAME);
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		block.setOpaque(false);
		block.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(sectionTitle(skill.getSkill().getName().toUpperCase(java.util.Locale.ROOT)));
		String verb = Character.toUpperCase(skill.producedVerb().charAt(0)) + skill.producedVerb().substring(1);
		boolean cyclesSkill = skill.cycleNoun(true) != null;
		java.util.Map<String, com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate> cycles =
			stats.getCycles() == null ? java.util.Collections.<String, com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate>emptyMap()
				: stats.getCycles();
		if (cyclesSkill)
		{
			addCycleRows(block, skill, cycles, stats.getMethodMillis(), stats.getOccurrences());
		}
		java.util.List<com.osrstelemetry.plugin.skills.SkillCompanionDefinition.HistoryRow> ownRows = skill.historyRows(
			com.osrstelemetry.plugin.history.HistoryEntry.skillTotals(model.getSessionAggregates(), active, skill));
		if (ownRows != null)
		{
			// Rendered below (the skill's own rows).
		}
		else if (skill.productionIsReward())
		{
			// Course REWARDS: each reward and its rate per hour of time on the courses that hand it out.
			for (com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView item : model.getProducedItems())
			{
				if (item.getItemId() == null || item.getQuantity() <= 0)
				{
					continue;
				}
				com.osrstelemetry.plugin.skills.SkillResource r = skill.resource(item.getItemId());
				long rewardMillis = 0L;
				if (stats.getMethodMillis() != null)
				{
					for (java.util.Map.Entry<String, Long> m : stats.getMethodMillis().entrySet())
					{
						for (com.osrstelemetry.plugin.skills.SkillResource o : skill.stockpileResources(m.getKey()))
						{
							if (o.getItemId() == item.getItemId() && m.getValue() != null)
							{
								rewardMillis += m.getValue();
								break;
							}
						}
					}
				}
				boolean rewardRated = rewardMillis >= com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
				block.add(sessionRow((r == null ? item.getItemName() : r.getShortName()) + " " + formatNumber(item.getQuantity()),
					SKILL_REWARD_PREFIX + item.getItemId(),
					rewardRated ? String.format(java.util.Locale.ROOT, "%.1f/hr", item.getQuantity() * 3_600_000d / rewardMillis) : null, null));
			}
		}
		else if (produced > 0)
		{
			JPanel row = sessionRow(verb + " " + formatNumber(produced), SKILL_PRODUCED_NAME,
				rated ? formatNumber(Math.round(produced * 3_600_000d / active)) + "/hr" : null, SKILL_PRODUCED_RATE_NAME);
			row.setToolTipText("Everything this session " + skill.producedVerb() + " (per resource below)");
			block.add(row);
			String share = " (" + Math.round(banked * 100d / produced) + "%)";
			JPanel bankedRow = sessionRow("Banked " + formatNumber(banked) + share, SKILL_BANKED_NAME,
				rated && banked > 0 ? formatNumber(Math.round(banked * 3_600_000d / active)) + "/hr" : null, SKILL_BANKED_RATE_NAME);
			bankedRow.setToolTipText("Of that, what Spyglass saw you deposit into your bank; drops and unproven deposits never count");
			block.add(bankedRow);
			long transformed = 0L;
			if (skill.autoTransformedVerb() != null && stats.getAutoTransformed() != null)
			{
				for (com.osrstelemetry.plugin.session.SessionAggregates.ProducedItemAggregate a : stats.getAutoTransformed())
				{
					transformed += a.getQuantity();
				}
			}
			if (transformed > 0)
			{
				String v = skill.autoTransformedVerb();
				JPanel autoRow = sessionRow(Character.toUpperCase(v.charAt(0)) + v.substring(1) + " " + formatNumber(transformed)
					+ " (" + Math.round(transformed * 100d / produced) + "%)", SKILL_AUTO_TRANSFORMED_NAME, null, null);
				autoRow.setToolTipText(skill.autoTransformedTooltip());
				block.add(autoRow);
			}
		}
		if (ownRows != null)
		{
			// Phase 6: the skill's own rows replace the generic produced / banked / consumed rows (each method's
			// own counts and rates, never one blended number); XP and per-method time stay generic below.
			for (int i = 0; i < ownRows.size(); i++)
			{
				com.osrstelemetry.plugin.skills.SkillCompanionDefinition.HistoryRow r = ownRows.get(i);
				JPanel row = sessionRow(r.getLabel(), SKILL_OWN_ROW_PREFIX + i, r.getValue(), null);
				row.setToolTipText(r.getTooltip());
				block.add(row);
			}
		}
		else
		{
			addSecondaryRows(block, skill, stats, model.getProducedItems(), active);
			addConsumedRows(block, skill, stats, active);
		}
		java.util.List<String> occLines = ownRows != null ? java.util.Collections.<String>emptyList()
			: skill.occurrenceLines(com.osrstelemetry.plugin.history.HistoryEntry.primaryMethod(stats), stats.getOccurrences());
		for (int i = 0; i < occLines.size(); i++)
		{
			block.add(sessionRow(occLines.get(i), SKILL_OCCURRENCE_LINE_PREFIX + i, null, null));
		}
		for (HistoryDetailModel.XpRow xp : model.getXpRows())
		{
			if (skill.getSkill().getName().equalsIgnoreCase(xp.getSkill()))
			{
				block.add(sessionRow(skill.getSkill().getName() + " XP " + formatNumber(xp.getXpGained()), SKILL_XP_NAME,
					rated ? formatNumber(Math.round(xp.getXpGained() * 3_600_000d / active)) + "/hr" : null, SKILL_XP_RATE_NAME));
			}
		}
		if (stats.getMethodMillis() != null && !stats.getMethodMillis().isEmpty())
		{
			java.util.List<java.util.Map.Entry<String, Long>> methods = new java.util.ArrayList<>(stats.getMethodMillis().entrySet());
			methods.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			for (java.util.Map.Entry<String, Long> m : methods)
			{
				String label = skill.methodLabel(m.getKey());
				if (label == null || m.getValue() == null || m.getValue() < 1000L)
				{
					continue;
				}
				com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate c = cycles.get(m.getKey());
				String right = formatDuration(m.getValue());
				if (skill.historyMethodProduction())
				{
					long methodProduced = 0L;
					for (com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView item : model.getProducedItems())
					{
						if (item.getItemId() != null && skill.methodProduces(m.getKey(), item.getItemId()))
						{
							methodProduced += item.getQuantity();
						}
					}
					if (methodProduced > 0)
					{
						right = formatNumber(methodProduced) + " \u2022 " + right;
					}
				}
				if (cyclesSkill && c != null && c.getCount() > 0)
				{
					right = right + " \u2022 " + formatNumber(c.getCount()) + " " + skill.cycleNoun(c.getCount() != 1);
				}
				JPanel row = sessionRow(label, SKILL_METHOD_PREFIX + m.getKey(), right, null);
				row.setToolTipText(cyclesSkill ? "Time on this " + skill.methodTitle().toLowerCase(java.util.Locale.ROOT) + " and its full "
					+ skill.cycleNoun(true) + " (each " + skill.methodTitle().toLowerCase(java.util.Locale.ROOT) + "'s own; never blended)"
					: "Time spent on this method (respawns, walking and banking count; long idles do not)");
				block.add(row);
			}
		}
		if (block.getComponentCount() <= 1)
		{
			return;
		}
		content.add(block);
		content.add(Box.createVerticalStrut(6));
	}

	/** Consumed inputs (Skills Phase 5): the total with its rate, then each exact input when there are several. */
	private void addConsumedRows(JPanel block, com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill,
		com.osrstelemetry.plugin.session.SessionAggregates.SkillSessionStats stats, long active)
	{
		if (skill.consumedPhrase() == null || stats.getConsumed() == null || stats.getConsumed().isEmpty())
		{
			return;
		}
		long total = 0L;
		boolean others = false;
		for (com.osrstelemetry.plugin.session.SessionAggregates.ProducedItemAggregate c : stats.getConsumed())
		{
			if (skill.isPrimaryInput(c.getItemId()))
			{
				total += c.getQuantity();
			}
			else
			{
				others = true;
			}
		}
		boolean rated = active >= com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
		String phrase = skill.consumedPhrase();
		if (total > 0)
		{
			block.add(sessionRow(Character.toUpperCase(phrase.charAt(0)) + phrase.substring(1) + " " + formatNumber(total), SKILL_CONSUMED_NAME,
				rated ? formatNumber(Math.round(total * 3_600_000d / active)) + "/hr" : null, null));
		}
		if (stats.getConsumed().size() > 1 || others)
		{
			for (com.osrstelemetry.plugin.session.SessionAggregates.ProducedItemAggregate c : stats.getConsumed())
			{
				block.add(sessionRow("  " + c.getItemName() + " " + formatNumber(c.getQuantity()), SKILL_CONSUMED_PREFIX + c.getItemId(), null, null));
			}
		}
	}

	private static boolean isPrimary(com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill, Integer itemId)
	{
		com.osrstelemetry.plugin.skills.SkillResource r = itemId == null ? null : skill.resource(itemId);
		return r != null && r.getRole() == com.osrstelemetry.plugin.skills.SkillResource.Role.PRIMARY;
	}

	private static long primaryTotal(com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill,
		java.util.List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> items)
	{
		long total = 0L;
		for (com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView item : items)
		{
			if (isPrimary(skill, item.getItemId()))
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	/**
	 * Secondary families with a section of their own (count, rate when
	 * meaningful) and contextual occurrences per kind. The exact variants are
	 * in the produced grid below.
	 */
	private void addSecondaryRows(JPanel block, com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill,
		com.osrstelemetry.plugin.session.SessionAggregates.SkillSessionStats stats,
		java.util.List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> items, long active)
	{
		boolean rated = active >= com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
		for (com.osrstelemetry.plugin.skills.SkillCompanionDefinition.Secondary sec : skill.secondaries())
		{
			long count = 0L;
			for (com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView item : items)
			{
				com.osrstelemetry.plugin.skills.SkillResource r = item.getItemId() == null ? null : skill.resource(item.getItemId());
				if (r != null && sec.getGroup().equals(r.getGroup()))
				{
					count += item.getQuantity();
				}
			}
			if (count <= 0)
			{
				continue;
			}
			String title = sec.isCompactLine() ? sec.getCaption() : skill.groupTitle(sec.getGroup());
			JPanel row = sessionRow(title + " " + formatNumber(count), SKILL_SECONDARY_PREFIX + sec.getGroup(),
				!sec.isCompactLine() && rated && count >= 2 ? String.format(java.util.Locale.ROOT, "%.1f/hr", count * 3_600_000d / active) : null,
				null);
			row.setToolTipText(sec.getTooltip());
			block.add(row);
		}
		if (skill.occurrencesTitle() == null || stats.getOccurrences() == null || stats.getOccurrences().isEmpty())
		{
			return;
		}
		long total = 0L;
		for (Long v : stats.getOccurrences().values())
		{
			total += v == null ? 0L : v;
		}
		if (total <= 0)
		{
			return;
		}
		block.add(sessionRow(skill.occurrencesTitle() + " " + formatNumber(total), SKILL_OCCURRENCES_NAME, null, null));
		java.util.List<java.util.Map.Entry<String, Long>> kinds = new java.util.ArrayList<>(stats.getOccurrences().entrySet());
		kinds.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (java.util.Map.Entry<String, Long> e : kinds)
		{
			String label = skill.occurrenceLabel(e.getKey());
			if (label != null && e.getValue() != null && e.getValue() > 0)
			{
				JPanel row = sessionRow("  " + label, SKILL_OCCURRENCE_PREFIX + e.getKey(), formatNumber(e.getValue()), null);
				block.add(row);
			}
		}
	}

	/**
	 * Cycle skills (Agility laps): the session's full cycles and their rate;
	 * AVG / BEST only when one method has cycles (lap times of different
	 * courses are never blended); reliably-evidenced failures.
	 */
	private void addCycleRows(JPanel block, com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill,
		java.util.Map<String, com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate> cycles, java.util.Map<String, Long> methodMillis,
		java.util.Map<String, Long> occurrences)
	{
		String onlyKey = null;
		long count = 0L;
		long millis = 0L;
		long failures = 0L;
		com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate only = null;
		int withCycles = 0;
		for (java.util.Map.Entry<String, com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate> e : cycles.entrySet())
		{
			com.osrstelemetry.plugin.session.SessionAggregates.CycleAggregate c = e.getValue();
			if (c == null)
			{
				continue;
			}
			failures += c.getFailures();
			if (c.getCount() > 0)
			{
				count += c.getCount();
				withCycles++;
				only = c;
				onlyKey = e.getKey();
				Long m = methodMillis == null ? null : methodMillis.get(e.getKey());
				millis += m == null ? 0L : m;
			}
		}
		if (count <= 0 && failures <= 0)
		{
			return;
		}
		String units = skill.cycleNoun(true);
		String unitsTitle = Character.toUpperCase(units.charAt(0)) + units.substring(1);
		boolean rated = millis >= com.osrstelemetry.plugin.skills.SkillGoalPlanner.MIN_RATE_MILLIS;
		if (count > 0)
		{
			JPanel row = sessionRow(unitsTitle + " " + formatNumber(count), SKILL_CYCLES_NAME,
				rated ? String.format(java.util.Locale.ROOT, "%.1f/hr", count * 3_600_000d / millis) : null, SKILL_CYCLES_RATE_NAME);
			row.setToolTipText("Full " + units + " (a " + skill.cycleNoun(false) + " joined part-way never counts), per hour of time on "
				+ skill.methodTitle().toLowerCase(java.util.Locale.ROOT) + "s that had them");
			block.add(row);
		}
		if (withCycles == 1 && only != null)
		{
			block.add(sessionRow("Avg " + skill.cycleNoun(false), SKILL_CYCLE_AVG_NAME,
				CompletionMetricText.gameTime(only.getTotalMillis() / only.getCount() / 1000), null));
			if (only.getBestMillis() > 0)
			{
				block.add(sessionRow("Best " + skill.cycleNoun(false), SKILL_CYCLE_BEST_NAME,
					CompletionMetricText.gameTime(only.getBestMillis() / 1000), null));
			}
			// Per-cycle yields of that one method's COUNTED cycles (never blended across methods).
			for (com.osrstelemetry.plugin.skills.SkillCompanionDefinition.CycleYield y : skill.cycleYields())
			{
				Long sum = occurrences == null ? null : occurrences.get(y.getKeyPrefix() + onlyKey);
				if (sum != null && only.getCount() > 0)
				{
					block.add(sessionRow(y.getHistoryLabel(), SKILL_CYCLE_YIELD_PREFIX + y.getKeyPrefix(),
						String.format(java.util.Locale.ROOT, "%,.1f", sum / (double) only.getCount()), null));
				}
			}
		}
		if (failures > 0 && skill.failureNoun(true) != null)
		{
			String f = skill.failureNoun(true);
			block.add(sessionRow(Character.toUpperCase(f.charAt(0)) + f.substring(1) + " " + formatNumber(failures), SKILL_FAILURES_NAME,
				null, null));
		}
	}

	/** RUNES CRAFTED (or any future produced-items list): header with total, then the compact item grid. */
	private void renderProducedItems(HistoryDetailModel model)
	{
		java.util.List<com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.ProducedItemView> items = model.getProducedItems();
		if (items.isEmpty())
		{
			return;
		}
		com.osrstelemetry.plugin.skills.SkillCompanionDefinition skill = model.getSkillDefinition();
		String title = com.osrstelemetry.plugin.activity.StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(model.getActivityKey())
			? "Runes crafted" : skill != null ? skill.producedTitle() : "Produced";
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
			long value = LootPricing.sumTotalValue(rows);
			section.add(SlayerTaskCards.card(HISTORY_TASK_CARD_NAME_PREFIX + ordinal, expanded,
				SlayerTaskCards.title(task.getTaskName(), task.getUnitsCompleted(), task.getEndReason()),
				SlayerTaskCards.tooltip(task.getTaskName(), task.getUnitsCompleted(), task.isBootstrapped(),
					task.getRemaining(), task.getEndReason(), historyTaskDetails(task)),
				SpyglassTheme.TEXT_PRIMARY, value, () -> toggleSlayerTask(ordinal),
				expanded ? SlayerTaskCards.details(task.getUnitsCompleted(), task.getKills(), value, task.getSuperiorsByNpc(),
					task.getKillsByNpc(), task.isBreakdownComplete()) : null,
				expanded ? taskCardContent(task, rows, modeLabel) : null,
				SlayerTaskCards.subLines(task.getKills(), task.getSuperiorsByNpc())));
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
	static final String SLAYER_SUPERIORS_NAME = "historySlayerSuperiors";

	/** "Superiors killed: 3 (Nechryarch 1 • Marble gargoyle 1 • Chasm crawler 1)"; null when none. */
	static String slayerSuperiorsText(HistoryDetailModel model)
	{
		long total = SlayerTaskCards.total(model.getSlayerSuperiors());
		if (total <= 0)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("Superiors killed: ").append(total).append(" (");
		boolean first = true;
		for (java.util.Map.Entry<String, Long> e : model.getSlayerSuperiors().entrySet())
		{
			if (e.getValue() == null || e.getValue() <= 0)
			{
				continue;
			}
			sb.append(first ? "" : " \u2022 ").append(e.getKey()).append(' ').append(e.getValue());
			first = false;
		}
		return sb.append(')').toString();
	}

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
