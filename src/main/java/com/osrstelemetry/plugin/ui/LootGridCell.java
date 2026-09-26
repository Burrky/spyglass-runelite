package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * A shared top-level {@code ui}-package class so LootTrackerView can
 * render item cells IDENTICALLY to Current Session's own loot grid
 * instead of duplicating this widget a second time.
 *
 * A single fixed-size loot slot: the authentic item sprite (always
 * {@link ItemManager#getImage(int)} via the caller -- never a
 * placeholder/fake icon) centered in a bank/inventory-style bordered
 * cell, with the stack quantity painted directly on top, and a hover
 * tooltip carrying the full item/value detail.
 */
final class LootGridCell extends JPanel
{
	private static final int CELL_SIZE = 40;

	/**
	 * SHARED LOOT-GRID GEOMETRY -- the one column count/gap every loot
	 * item grid (Current Session's LOOT card and the Loot tab's Grouped
	 * AND Individual source cards) builds its {@link GridLayout} from, via
	 * {@link #newGridLayout()}, so the two surfaces can never drift apart
	 * again. {@code GridLayout} divides the ACTUAL available width evenly
	 * among its columns -- CELL_SIZE is only each cell's preferred size,
	 * not a floor -- so at the normal sidebar width a 5-column grid
	 * renders ~33px cells (exactly what Current Session has always
	 * shown), and a narrower/wider container shrinks/grows the cells
	 * rather than clipping or scrolling horizontally.
	 */
	static final int GRID_COLUMNS = 5;

	/** Horizontal and vertical gap between cells, in pixels. */
	static final int GRID_GAP = 4;

	/** A fresh layout per grid (LayoutManagers are not shared between containers). */
	static GridLayout newGridLayout()
	{
		return new GridLayout(0, GRID_COLUMNS, GRID_GAP, GRID_GAP);
	}

	private final AsyncBufferedImage icon;
	private final String quantityText;
	private final Color quantityColor;

	LootGridCell(AsyncBufferedImage icon, long quantity, String tooltip)
	{
		this.icon = icon;
		this.quantityText = quantity > 1 ? QuantityFormatter.quantityToStackSize(quantity) : null;
		this.quantityColor = stackColor(quantity);
		setPreferredSize(new Dimension(CELL_SIZE, CELL_SIZE));
		// Background/border use SpyglassTheme's shared chrome tokens
		// rather than local color literals, so this cell never drifts
		// from the rest of the plugin's palette. The item sprite and the
		// per-stack-size quantity color below (see stackColor()) are
		// semantic/authentic-OSRS colors, not chrome, and stay local.
		setBackground(SpyglassTheme.BACKGROUND_PANEL);
		setBorder(BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT));
		setToolTipText(tooltip);
		if (icon != null)
		{
			// The sprite loads asynchronously -- repaint once RuneLite
			// finishes decoding it, exactly like AsyncBufferedImage's own
			// addTo(JLabel) convenience does internally.
			icon.onLoaded(this::repaint);
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (icon != null)
		{
			int x = (getWidth() - icon.getWidth()) / 2;
			int y = (getHeight() - icon.getHeight()) / 2;
			g.drawImage(icon, x, y, null);
		}
		if (quantityText != null)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			g.setColor(Color.BLACK);
			// Four-offset outline (not one) -- legible over any sprite,
			// even a bright/busy backdrop that would wash out a single
			// shadow offset.
			g.drawString(quantityText, 2, 11);
			g.drawString(quantityText, 4, 11);
			g.drawString(quantityText, 2, 13);
			g.drawString(quantityText, 4, 13);
			g.setColor(quantityColor);
			g.drawString(quantityText, 3, 12);
		}
	}

	private static Color stackColor(long quantity)
	{
		if (quantity >= 10_000_000L)
		{
			return new Color(80, 220, 90);
		}
		if (quantity >= 100_000L)
		{
			return new Color(255, 193, 37);
		}
		return new Color(255, 224, 130);
	}

	/** Shared clamp -- ItemManager#getImage()'s quantity argument is an int; a loot total can exceed Integer.MAX_VALUE in extreme cases. */
	static int clampToInt(long value)
	{
		return (int) Math.max(1, Math.min(value, Integer.MAX_VALUE));
	}
}
