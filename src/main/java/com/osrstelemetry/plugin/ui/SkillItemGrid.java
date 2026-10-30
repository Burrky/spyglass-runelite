package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.skills.SkillCompanionView;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Item + number, the way the game shows it: the item's own sprite (always
 * {@link ItemManager#getImage(int, int, boolean)}, never a placeholder or an
 * emoji) with its number. Three shapes, all width-adaptive -- every item is
 * always shown (no "+N more", no percentages, no horizontal scroll):
 * <ul>
 * <li>TILES -- one number painted on each sprite (inventory style); the
 * column count follows the real width.</li>
 * <li>ROWS -- a sprite, then one right-aligned value per named column
 * (MINED | BANKED) under a small caption header.</li>
 * <li>LINES -- a sprite with a short text beside it ("54 loaded").</li>
 * </ul>
 * An unknown value is a quiet dash, never zero. Every cell carries the exact
 * name and numbers as its tooltip.
 */
final class SkillItemGrid extends JPanel
{
	static final String CELL_PREFIX = "skillItem:";
	static final int TILE_W = 36;
	/** Gap between a ROWS / LINES sprite and the frame's left edge. */
	static final int SPRITE_INSET = 3;
	static final int TILE_H = 32;
	static final int GAP = 2;
	static final int ROW_H = 32;
	static final int HEADER_H = 13;
	static final int FALLBACK_WIDTH = 200;

	enum Shape
	{
		TILES,
		ROWS,
		LINES
	}

	/** One painted cell (tests read what it shows through {@link #texts()}). */
	static final class Cell extends JPanel
	{
		private final AsyncBufferedImage icon;
		private final List<String> texts;
		private final Shape shape;

		Cell(AsyncBufferedImage icon, List<String> texts, Shape shape, String tooltip)
		{
			this.icon = icon;
			this.texts = texts;
			this.shape = shape;
			setOpaque(false);
			setToolTipText(tooltip);
			if (icon != null)
			{
				icon.onLoaded(this::repaint);
			}
		}

		/** What this cell shows, in column order ("-" for unknown); LINES: the one text. */
		List<String> texts()
		{
			return texts;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			// Pass 28: the stack number sits on the sprite's own top-left corner
			// (inventory style) even when a tile is wider than the sprite, and a
			// row's sprite keeps a small inset from the frame.
			int spriteX = shape == Shape.TILES ? spriteLeft(getWidth(), icon == null ? TILE_W : icon.getWidth()) : SPRITE_INSET;
			if (icon != null)
			{
				int x = shape == Shape.TILES ? spriteX : SPRITE_INSET + (TILE_W - icon.getWidth()) / 2;
				g.drawImage(icon, x, (getHeight() - icon.getHeight()) / 2, null);
			}
			g.setFont(SpyglassFonts.small());
			FontMetrics fm = g.getFontMetrics();
			if (shape == Shape.TILES)
			{
				String t = texts.isEmpty() ? "-" : texts.get(0);
				outlined(g, t, spriteX + 1, fm.getAscent() - 1, stackColor(t));
				return;
			}
			int baseline = (getHeight() + fm.getAscent()) / 2 - 1;
			if (shape == Shape.LINES)
			{
				String t = texts.isEmpty() ? "" : texts.get(0);
				g.setColor(SpyglassTheme.TEXT_PRIMARY);
				g.drawString(fit(t, fm, getWidth() - TILE_W - SPRITE_INSET - 4), TILE_W + SPRITE_INSET + 2, baseline);
				return;
			}
			int cols = texts.size();
			int span = Math.max(1, getWidth() - TILE_W);
			for (int c = 0; c < cols; c++)
			{
				String t = texts.get(c);
				int right = TILE_W + span * (c + 1) / cols - 4;
				g.setColor("-".equals(t) ? SpyglassTheme.TEXT_MUTED : c == 0 ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY);
				g.drawString(t, right - fm.stringWidth(t), baseline);
			}
		}

		/** The text, cut with an ellipsis only if the narrowest sidebar cannot hold it (the tooltip has it whole). */
		static String fit(String text, FontMetrics fm, int width)
		{
			if (fm.stringWidth(text) <= width)
			{
				return text;
			}
			String t = text;
			while (t.length() > 1 && fm.stringWidth(t + "...") > width)
			{
				t = t.substring(0, t.length() - 1);
			}
			return t + "...";
		}

		private static void outlined(Graphics g, String text, int x, int y, Color color)
		{
			g.setColor(Color.BLACK);
			g.drawString(text, x - 1, y);
			g.drawString(text, x + 1, y);
			g.drawString(text, x, y - 1);
			g.drawString(text, x, y + 1);
			g.setColor(color);
			g.drawString(text, x, y);
		}

		/** The game's own stack colours: yellow, white from 100K, green from 10M; an unknown is muted. */
		private static Color stackColor(String text)
		{
			if ("-".equals(text))
			{
				return SpyglassTheme.TEXT_MUTED;
			}
			return text.endsWith("M") ? new Color(0, 255, 128) : text.endsWith("K") ? Color.WHITE : new Color(255, 255, 0);
		}
	}

	private final List<Cell> cells = new ArrayList<>();
	private final List<String> captions;
	private final Shape shape;

	/**
	 * {@code captions} non-empty = ROWS (one value per caption); otherwise
	 * LINES when the items carry a text, else TILES. {@code itemManager} null
	 * renders the numbers without sprites (tests).
	 */
	SkillItemGrid(String name, List<String> captions, List<SkillCompanionView.ItemStat> items, ItemManager itemManager)
	{
		super(null);
		setName(name);
		setOpaque(true);
		setBackground(SpyglassTheme.BACKGROUND_PANEL);
		setBorder(BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT));
		setAlignmentX(LEFT_ALIGNMENT);
		this.captions = captions == null ? Collections.<String>emptyList() : captions;
		boolean lines = !items.isEmpty() && items.get(0).getText() != null;
		this.shape = !this.captions.isEmpty() ? Shape.ROWS : lines ? Shape.LINES : Shape.TILES;
		for (SkillCompanionView.ItemStat e : items)
		{
			List<String> texts = new ArrayList<>();
			if (shape == Shape.LINES)
			{
				texts.add(e.getText() == null ? "" : e.getText());
			}
			else
			{
				for (Long v : e.getValues())
				{
					texts.add(v == null ? "-" : shape == Shape.TILES ? stack(v) : exact(v));
				}
			}
			Cell cell = new Cell(sprite(itemManager, e), texts, shape, tooltip(e));
			cell.setName(CELL_PREFIX + e.getItemId());
			cells.add(cell);
			add(cell);
		}
	}

	private static String tooltip(SkillCompanionView.ItemStat e)
	{
		if (e.getTooltip() != null)
		{
			return e.getTooltip();
		}
		if (e.getText() != null)
		{
			return e.getName() + ": " + e.getText();
		}
		Long v = e.getValues().isEmpty() ? null : e.getValues().get(0);
		return e.getName() + ": " + (v == null ? "unknown" : exact(v));
	}

	private static AsyncBufferedImage sprite(ItemManager itemManager, SkillCompanionView.ItemStat e)
	{
		if (itemManager == null || e.getItemId() <= 0)
		{
			return null;
		}
		try
		{
			Long q = e.getValues().isEmpty() ? null : e.getValues().get(0);
			// stackable=false: the grid paints the only number (the quantity still picks coin-pile art).
			return itemManager.getImage(e.getItemId(), LootGridCell.clampToInt(q == null ? 1 : q), false);
		}
		catch (RuntimeException ex)
		{
			// An unresolvable id renders without a sprite, never a fake one.
			return null;
		}
	}

	/** The game's own stack text: exact up to 99,999, then K below 10M, then M (the tooltip keeps the exact number). */
	static String stack(long v)
	{
		if (v < 100_000L)
		{
			return String.format(Locale.ROOT, "%,d", v);
		}
		return v < 10_000_000L ? (v / 1_000L) + "K" : (v / 1_000_000L) + "M";
	}

	static String exact(long v)
	{
		return String.format(Locale.ROOT, "%,d", v);
	}

	Shape shape()
	{
		return shape;
	}

	List<Cell> cells()
	{
		return cells;
	}

	/** Captions + every cell, for tests: "MINED|BANKED;440:1,103|742;453:94|0". */
	String describe()
	{
		StringBuilder sb = new StringBuilder(String.join("|", captions));
		for (Cell c : cells)
		{
			sb.append(';').append(c.getName().substring(CELL_PREFIX.length())).append(':').append(String.join("|", c.texts()));
		}
		return sb.toString();
	}

	private int availableWidth()
	{
		if (getWidth() > 0)
		{
			return getWidth();
		}
		Container parent = getParent();
		if (parent == null || parent.getWidth() <= 0)
		{
			return FALLBACK_WIDTH;
		}
		Insets in = parent.getInsets();
		return Math.max(1, parent.getWidth() - in.left - in.right);
	}

	int columnsFor(int width)
	{
		if (shape != Shape.TILES)
		{
			return 1;
		}
		Insets in = getInsets();
		int inner = Math.max(1, width - in.left - in.right - GAP);
		return Math.max(1, Math.min(Math.max(1, cells.size()), inner / (TILE_W + GAP)));
	}

	private int headerHeight()
	{
		return shape == Shape.ROWS ? HEADER_H : 0;
	}

	@Override
	public Dimension getPreferredSize()
	{
		Insets in = getInsets();
		int width = availableWidth();
		int cols = columnsFor(width);
		int rows = (cells.size() + cols - 1) / cols;
		int h = shape == Shape.TILES ? rows * (TILE_H + GAP) + GAP : headerHeight() + rows * ROW_H;
		return new Dimension(width, h + in.top + in.bottom);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, getPreferredSize().height);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	public void setBounds(int x, int y, int width, int height)
	{
		int before = getWidth() > 0 ? columnsFor(getWidth()) : -1;
		super.setBounds(x, y, width, height);
		if (getWidth() > 0 && columnsFor(getWidth()) != before)
		{
			revalidate();
		}
	}

	/** Left edge of a sprite centred in a tile (never left of the tile). */
	static int spriteLeft(int tileWidth, int spriteWidth)
	{
		return Math.max(0, (tileWidth - spriteWidth) / 2);
	}

	@Override
	public void doLayout()
	{
		Insets in = getInsets();
		int inner = Math.max(1, getWidth() - in.left - in.right);
		if (shape == Shape.TILES)
		{
			int cols = columnsFor(getWidth());
			int tileW = Math.max(1, (inner - GAP) / cols - GAP);
			for (int i = 0; i < cells.size(); i++)
			{
				int col = i % cols;
				int row = i / cols;
				cells.get(i).setBounds(in.left + GAP + col * (tileW + GAP), in.top + GAP + row * (TILE_H + GAP), tileW, TILE_H);
			}
			return;
		}
		for (int i = 0; i < cells.size(); i++)
		{
			cells.get(i).setBounds(in.left, in.top + headerHeight() + i * ROW_H, inner, ROW_H);
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Insets in = getInsets();
		g.setFont(SpyglassFonts.small());
		FontMetrics fm = g.getFontMetrics();
		if (shape == Shape.ROWS)
		{
			int inner = Math.max(1, getWidth() - in.left - in.right);
			int span = Math.max(1, inner - TILE_W);
			g.setColor(SpyglassTheme.TEXT_MUTED);
			for (int c = 0; c < captions.size(); c++)
			{
				String t = captions.get(c);
				int right = in.left + TILE_W + span * (c + 1) / captions.size() - 4;
				g.drawString(t, right - fm.stringWidth(t), in.top + fm.getAscent() + 1);
			}
		}
		if (shape != Shape.TILES)
		{
			g.setColor(SpyglassTheme.BORDER_SOFT);
			for (int r = 1; r < cells.size(); r++)
			{
				int y = in.top + headerHeight() + r * ROW_H;
				g.drawLine(in.left + 4, y, getWidth() - in.right - 5, y);
			}
		}
	}
}
