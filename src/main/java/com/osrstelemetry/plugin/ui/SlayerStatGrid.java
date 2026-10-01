package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/**
 * The Smart Slayer six-stat dashboard: KILLS / KILLS/HR / LOOT/KILL over
 * POINTS / STREAK / NEXT BONUS. One quiet shared surface with hairline
 * separators -- never six bordered boxes -- each cell a small muted caption
 * over a bold value, centered.
 *
 * RESPONSIVE: 3 columns x 2 rows whenever the columns' natural widths fit
 * the real sidebar width (spare space shared evenly); otherwise 2 x 3, and
 * 1 x 6 only as a last resort for extreme values at the narrowest width --
 * same six cells, same row-major order. Text is never shrunk.
 */
final class SlayerStatGrid extends JPanel
{
	static final int PAD_X = 4;
	static final int PAD_Y = 3;
	static final int FALLBACK_WIDTH = 200;
	static final String CELL_PREFIX = "slayerCell:";
	static final String VALUE_PREFIX = "slayerCellValue:";

	private final List<JPanel> cells = new ArrayList<>();
	/** Widest layout allowed (3 for the six-stat dashboards; 2 for a 2 x 2 grid). */
	private int maxColumns = 3;

	SlayerStatGrid()
	{
		super(null);
		setName("slayerStatGrid");
		setOpaque(true);
		setBackground(SpyglassTheme.BACKGROUND_PANEL);
		setAlignmentX(LEFT_ALIGNMENT);
		setBorder(BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT));
	}

	/** Caps the column count (default 3); the grid still steps down to fewer columns when narrow. */
	void setMaxColumns(int maxColumns)
	{
		this.maxColumns = Math.max(1, maxColumns);
	}

	/** A cell whose lines are stacked and centered (caption over value, or any short lines). */
	private static JPanel stackedCell()
	{
		return new JPanel(null)
		{
			@Override
			public void doLayout()
			{
				int total = 0;
				for (Component c : getComponents())
				{
					total += c.getPreferredSize().height;
				}
				int y = Math.max(0, (getHeight() - total) / 2);
				for (Component c : getComponents())
				{
					Dimension d = c.getPreferredSize();
					c.setBounds((getWidth() - d.width) / 2, y, d.width, d.height);
					y += d.height;
				}
			}

			@Override
			public Dimension getPreferredSize()
			{
				int w = 0;
				int h = 0;
				for (Component c : getComponents())
				{
					Dimension d = c.getPreferredSize();
					w = Math.max(w, d.width);
					h += d.height;
				}
				return new Dimension(w + 2 * PAD_X, h + 2 * PAD_Y);
			}
		};
	}

	/** Adds one cell of arbitrary stacked lines (e.g. altar / mark / reason), named CELL_PREFIX + key. */
	JPanel addLinesCell(String key, String tooltip, JLabel... lines)
	{
		JPanel cell = stackedCell();
		cell.setName(CELL_PREFIX + key);
		cell.setOpaque(false);
		for (JLabel line : lines)
		{
			if (line != null)
			{
				if (tooltip != null)
				{
					line.setToolTipText(tooltip);
				}
				cell.add(line);
			}
		}
		if (tooltip != null)
		{
			cell.setToolTipText(tooltip);
		}
		cells.add(cell);
		add(cell);
		return cell;
	}

	/** Adds one cell; `value` null renders a quiet dash (the cell itself always exists). */
	JLabel addCell(String caption, String value, Color valueColor, String tooltip)
	{
		JPanel cell = stackedCell();
		cell.setName(CELL_PREFIX + caption);
		cell.setOpaque(false);
		JLabel captionLabel = new JLabel(caption);
		captionLabel.setFont(FontManager.getRunescapeSmallFont());
		captionLabel.setForeground(SpyglassTheme.TEXT_MUTED);
		JLabel valueLabel = new JLabel(value == null ? "-" : value);
		valueLabel.setName(VALUE_PREFIX + caption);
		valueLabel.setFont(FontManager.getRunescapeBoldFont());
		valueLabel.setForeground(value == null ? SpyglassTheme.TEXT_MUTED : valueColor);
		cell.add(captionLabel);
		cell.add(valueLabel);
		if (tooltip != null)
		{
			cell.setToolTipText(tooltip);
			valueLabel.setToolTipText(tooltip);
		}
		cells.add(cell);
		add(cell);
		return valueLabel;
	}

	int availableWidth()
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

	private int cellHeight()
	{
		int h = 0;
		for (JPanel cell : cells)
		{
			h = Math.max(h, cell.getPreferredSize().height);
		}
		return h;
	}

	/** Natural width of each column when the cells flow row-major into `cols` columns. */
	private int[] naturalColumnWidths(int cols)
	{
		int[] widths = new int[cols];
		for (int i = 0; i < cells.size(); i++)
		{
			widths[i % cols] = Math.max(widths[i % cols], cells.get(i).getPreferredSize().width);
		}
		return widths;
	}

	private static int sum(int[] values)
	{
		int total = 0;
		for (int v : values)
		{
			total += v;
		}
		return total;
	}

	/**
	 * 3 when the three columns' natural widths fit `width` (inside the
	 * border), else 2, and 1 only as a last resort for extreme values in the
	 * narrowest sidebar -- text is never shrunk instead.
	 */
	int columnsFor(int width)
	{
		Insets in = getInsets();
		int inner = width - in.left - in.right;
		for (int cols = Math.min(maxColumns, Math.max(1, cells.size())); cols > 1; cols--)
		{
			if (sum(naturalColumnWidths(cols)) <= inner)
			{
				return cols;
			}
		}
		return 1;
	}

	int columns()
	{
		return columnsFor(availableWidth());
	}

	/** Column x-boundaries for `width`: natural widths plus an even share of the spare space. */
	private int[] columnEdges(int width)
	{
		Insets in = getInsets();
		int cols = columnsFor(width);
		int[] natural = naturalColumnWidths(cols);
		int spare = Math.max(0, width - in.left - in.right - sum(natural));
		int[] edges = new int[cols + 1];
		edges[0] = in.left;
		for (int c = 0; c < cols; c++)
		{
			edges[c + 1] = edges[c] + natural[c] + spare * (c + 1) / cols - spare * c / cols;
		}
		return edges;
	}

	@Override
	public Dimension getPreferredSize()
	{
		Insets in = getInsets();
		int width = availableWidth();
		int cols = columnsFor(width);
		int rows = (cells.size() + cols - 1) / cols;
		return new Dimension(Math.min(width, sum(naturalColumnWidths(cols)) + in.left + in.right),
			rows * cellHeight() + in.top + in.bottom);
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

	@Override
	public void doLayout()
	{
		Insets in = getInsets();
		int[] edges = columnEdges(getWidth());
		int cols = edges.length - 1;
		int h = cellHeight();
		for (int i = 0; i < cells.size(); i++)
		{
			int col = i % cols;
			int row = i / cols;
			cells.get(i).setBounds(edges[col], in.top + row * h, edges[col + 1] - edges[col], h);
		}
	}

	/** Hairline separators between cells -- territory without boxes. */
	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Insets in = getInsets();
		int[] edges = columnEdges(getWidth());
		int cols = edges.length - 1;
		int rows = (cells.size() + cols - 1) / cols;
		int h = cellHeight();
		g.setColor(SpyglassTheme.BORDER_SOFT);
		for (int c = 1; c < cols; c++)
		{
			g.drawLine(edges[c], in.top + 4, edges[c], in.top + rows * h - 4);
		}
		for (int r = 1; r < rows; r++)
		{
			int y = in.top + r * h;
			g.drawLine(in.left + 6, y, getWidth() - in.right - 7, y);
		}
	}
}
