package com.osrstelemetry.plugin.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.util.ArrayList;
import java.util.List;

/**
 * A grid of cards whose text wraps (pass 31: activity cards, Farming's category cards). Columns: as many as fit (up to
 * {@code maxColumns}) when every card gets at least its comfortable width ({@link Comfortable}, or its preferred width); at
 * larger text sizes that width grows and the grid drops to fewer columns. Rows take the height their tallest card needs at
 * the column width (each card is measured at that width, so wrapped lines are never cut).
 *
 * <p>Measured once per width (cached until the grid is invalidated). A grid asked for its height before it had a width
 * re-asks once, after its first layout at the real width; never more (no layout loops).
 */
final class CardGridLayout implements LayoutManager2
{
	/** A card that knows the width its content reads comfortably at (names on one or two lines). */
	interface Comfortable
	{
		int comfortableWidth();
	}

	private final int maxColumns;
	private final int hgap;
	private final int vgap;
	private int cachedWidth = -1;
	private int[] cachedRows;
	private int cachedColumns;
	private int revalidatedFor = -1;

	CardGridLayout(int maxColumns, int hgap, int vgap)
	{
		this.maxColumns = Math.max(1, maxColumns);
		this.hgap = hgap;
		this.vgap = vgap;
	}

	private static List<Component> visible(Container target)
	{
		List<Component> out = new ArrayList<>();
		for (Component c : target.getComponents())
		{
			if (c.isVisible())
			{
				out.add(c);
			}
		}
		return out;
	}

	/** Columns at this inner width. */
	int columns(Container target, int width)
	{
		List<Component> cards = visible(target);
		if (cards.isEmpty())
		{
			return 1;
		}
		int need = 0;
		for (Component c : cards)
		{
			need = Math.max(need, c instanceof Comfortable ? ((Comfortable) c).comfortableWidth() : c.getMinimumSize().width);
		}
		int cols = Math.min(maxColumns, cards.size());
		while (cols > 1 && cols * need + (cols - 1) * hgap > width)
		{
			cols--;
		}
		return cols;
	}

	private static int innerWidth(Container target)
	{
		Insets in = target.getInsets();
		int w = target.getWidth();
		if (w <= 0 && target.getParent() != null)
		{
			Container p = target.getParent();
			w = p.getWidth() - p.getInsets().left - p.getInsets().right;
		}
		return w <= 0 ? 0 : w - in.left - in.right;
	}

	/** Each row's height at this width (cached). */
	private int[] rows(Container target, int width)
	{
		if (width == cachedWidth && cachedRows != null)
		{
			return cachedRows;
		}
		List<Component> cards = visible(target);
		int cols = columns(target, width);
		int cw = width <= 0 ? 0 : Math.max(1, (width - (cols - 1) * hgap) / cols);
		int n = cards.size();
		int[] rows = new int[n == 0 ? 0 : (n + cols - 1) / cols];
		for (int i = 0; i < n; i++)
		{
			Component c = cards.get(i);
			int h;
			if (cw > 0)
			{
				// Measured at the column width: the card lays its text out at that width, then reports its height.
				c.setSize(cw, Math.max(1, c.getHeight()));
				if (c instanceof Container)
				{
					((Container) c).doLayout();
					layoutTree((Container) c);
				}
				h = c.getPreferredSize().height;
			}
			else
			{
				h = c.getPreferredSize().height;
			}
			rows[i / cols] = Math.max(rows[i / cols], h);
		}
		cachedWidth = width;
		cachedRows = rows;
		cachedColumns = cols;
		return rows;
	}

	private static void layoutTree(Container c)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				((Container) k).doLayout();
				layoutTree((Container) k);
			}
		}
	}

	private int total(int[] rows)
	{
		int h = 0;
		for (int r : rows)
		{
			h += r;
		}
		return h + Math.max(0, rows.length - 1) * vgap;
	}

	@Override
	public Dimension preferredLayoutSize(Container target)
	{
		synchronized (target.getTreeLock())
		{
			Insets in = target.getInsets();
			int width = innerWidth(target);
			int[] rows = rows(target, width);
			return new Dimension(Math.max(0, width) + in.left + in.right, total(rows) + in.top + in.bottom);
		}
	}

	@Override
	public Dimension minimumLayoutSize(Container target)
	{
		Dimension d = preferredLayoutSize(target);
		return new Dimension(0, d.height);
	}

	@Override
	public Dimension maximumLayoutSize(Container target)
	{
		return new Dimension(Integer.MAX_VALUE, preferredLayoutSize(target).height);
	}

	@Override
	public void layoutContainer(Container target)
	{
		synchronized (target.getTreeLock())
		{
			Insets in = target.getInsets();
			int width = target.getWidth() - in.left - in.right;
			if (width <= 0)
			{
				return;
			}
			int[] rows = rows(target, width);
			int cols = cachedColumns;
			int cw = Math.max(1, (width - (cols - 1) * hgap) / cols);
			List<Component> cards = visible(target);
			int y = in.top;
			for (int r = 0; r < rows.length; r++)
			{
				for (int c = 0; c < cols; c++)
				{
					int i = r * cols + c;
					if (i < cards.size())
					{
						cards.get(i).setBounds(in.left + c * (cw + hgap), y, cw, rows[r]);
					}
				}
				y += rows[r] + vgap;
			}
			int needed = total(rows) + in.top + in.bottom;
			if (needed != target.getHeight() && revalidatedFor != width)
			{
				// Its height was asked before it had this width: the containers above lay out once more with the real one.
				revalidatedFor = width;
				javax.swing.SwingUtilities.invokeLater(target::revalidate);
			}
		}
	}

	@Override
	public void invalidateLayout(Container target)
	{
		cachedWidth = -1;
		cachedRows = null;
	}

	@Override
	public void addLayoutComponent(Component comp, Object constraints)
	{
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
	}

	@Override
	public float getLayoutAlignmentX(Container target)
	{
		return 0f;
	}

	@Override
	public float getLayoutAlignmentY(Container target)
	{
		return 0f;
	}
}
