package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The compact item grid for PRODUCED items (e.g. GotR RUNES CRAFTED), shared
 * by Current Session and History. It reuses LootGridCell -- whose API is a
 * plain (sprite, quantity, tooltip) cell -- so produced items look exactly
 * like Spyglass's other item grids (5 columns, quantity overlay, no
 * horizontal scrolling) while carrying no loot semantics: no price, no
 * value, and never labelled loot.
 */
final class ProducedItemGrid
{
	static final String GRID_NAME = "producedItemGrid";

	private ProducedItemGrid()
	{
	}

	static JPanel build(List<CurrentSessionSnapshot.ProducedItemView> items, ItemManager itemManager)
	{
		JPanel grid = new JPanel(LootGridCell.newGridLayout());
		grid.setName(GRID_NAME);
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		for (CurrentSessionSnapshot.ProducedItemView item : items)
		{
			grid.add(cell(item, itemManager));
		}
		return grid;
	}

	static LootGridCell cell(CurrentSessionSnapshot.ProducedItemView item, ItemManager itemManager)
	{
		AsyncBufferedImage icon = null;
		if (itemManager != null && item.getItemId() != null)
		{
			try
			{
				// stackable=false: LootGridCell paints the only quantity overlay.
				icon = itemManager.getImage(item.getItemId(), LootGridCell.clampToInt(item.getQuantity()), false);
			}
			catch (RuntimeException e)
			{
				// An unresolvable id renders without a sprite, never a fake one.
			}
		}
		return new LootGridCell(icon, item.getQuantity(), tooltip(item));
	}

	/** Exact name and full quantity ("Air rune: 1,240"), whatever the compact overlay shows. */
	static String tooltip(CurrentSessionSnapshot.ProducedItemView item)
	{
		String name = item.getItemName() == null ? "Item " + item.getItemId() : item.getItemName();
		return "<html>" + escape(name) + ": " + String.format(Locale.ROOT, "%,d", item.getQuantity()) + "</html>";
	}

	/** Exact with separators below one million ("14,274"), compact above ("1.2M"); tooltips carry the exact value. */
	static String compactTotal(long total)
	{
		if (total < 1_000_000L)
		{
			return String.format(Locale.ROOT, "%,d", total);
		}
		return total < 1_000_000_000L
			? String.format(Locale.ROOT, "%.1fM", total / 1_000_000.0)
			: String.format(Locale.ROOT, "%.1fB", total / 1_000_000_000.0);
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
