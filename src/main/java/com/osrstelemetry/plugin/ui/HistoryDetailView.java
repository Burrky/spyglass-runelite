package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.model.StorageState;
import com.osrstelemetry.plugin.session.ActivityIdentity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * reusing {@link LootPricing} exactly like {@code CurrentSessionView}),
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
	private static final int LOOT_ITEMS_PER_ROW = 5;
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
		content.removeAll();

		content.add(backRow());
		content.add(Box.createVerticalStrut(4));
		renderHeader(model);
		renderXpBreakdown(model);
		renderLootSection(model);
		renderLoadoutSection(model.getStartingLoadout());

		content.revalidate();
		content.repaint();
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
		ActivityIdentity identity = model.getActivityIdentity();
		String headline = identity == null || identity.getDisplayName() == null ? "Unknown activity" : identity.getDisplayName();
		JLabel headlineLabel = new JLabel(headline);
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

		StringBuilder secondary = new StringBuilder(formatDuration(model.getAccumulatedActiveDurationMillis())).append(" active");
		SessionAggregates.ReliableCount reliableCount = model.getReliableCount();
		if (reliableCount != null)
		{
			String label = reliableCount.getKind() == SessionAggregates.ReliableCountKind.KILLS ? "kills" : "completions";
			secondary.append("   •   ").append(reliableCount.getSessionOccurrences()).append(" ").append(label);
		}
		JLabel secondaryLabel = new JLabel(secondary.toString());
		secondaryLabel.setFont(FontManager.getRunescapeSmallFont());
		secondaryLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		secondaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(secondaryLabel);

		content.add(Box.createVerticalStrut(6));
		content.add(separator());
		content.add(Box.createVerticalStrut(6));
	}

	private void renderXpBreakdown(HistoryDetailModel model)
	{
		if (model.getXpRows().isEmpty())
		{
			return;
		}

		JLabel title = new JLabel("XP GAINED  •  " + formatNumber(model.getTotalXpGained()) + " total");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(SpyglassTheme.ACCENT_GOLD);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		title.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.ACCENT_GOLD),
			BorderFactory.createEmptyBorder(0, 0, 4, 0)));
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
		if (model.getLootRows().isEmpty())
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

		JPanel grid = new JPanel(new GridLayout(0, LOOT_ITEMS_PER_ROW, 4, 4));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));

		String modeLabel = mode == LootValuationMode.HIGH_ALCH ? "High Alch" : "Grand Exchange";
		for (LootPricing.ValuedLootRow row : rows)
		{
			grid.add(lootGridCell(row, modeLabel));
		}
		section.add(grid);

		content.add(section);
		content.add(Box.createVerticalStrut(6));
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
				text = "Captured before session start";
				color = SpyglassTheme.STATUS_SUCCESS;
				break;
			case FALLBACK_AT_OR_AFTER_START:
				text = "Earliest captured loadout (no pre-session observation)";
				color = SpyglassTheme.STATUS_SUSPENDED;
				break;
			case UNAVAILABLE:
			default:
				text = "Unavailable — no observation was captured";
				color = SpyglassTheme.TEXT_MUTED;
				break;
		}
		JLabel label = new JLabel(text);
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
