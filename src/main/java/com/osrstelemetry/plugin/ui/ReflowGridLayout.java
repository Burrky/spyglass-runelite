package com.osrstelemetry.plugin.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * A grid of equal cells that drops to fewer columns when its cells' text no longer fits (pass 30: larger text sizes).
 * Up to {@code maxColumns} columns; as many as fit the width each cell's preferred width needs; rows as needed. Cells keep
 * equal widths, like {@link java.awt.GridLayout}, so a run-type grid reads the same at Standard size.
 */
final class ReflowGridLayout implements LayoutManager
{
	private final int maxColumns;
	private final int hgap;
	private final int vgap;

	ReflowGridLayout(int maxColumns, int hgap, int vgap)
	{
		this.maxColumns = Math.max(1, maxColumns);
		this.hgap = hgap;
		this.vgap = vgap;
	}

	/** Columns at this inner width (all of them while the width is not known yet). */
	int columns(Container target, int width)
	{
		int visible = visibleCount(target);
		if (visible == 0)
		{
			return 1;
		}
		int cols = Math.min(maxColumns, visible);
		if (width <= 0)
		{
			return cols;
		}
		int cell = 0;
		for (Component c : target.getComponents())
		{
			if (c.isVisible())
			{
				cell = Math.max(cell, c.getPreferredSize().width);
			}
		}
		while (cols > 1 && cols * cell + (cols - 1) * hgap > width)
		{
			cols--;
		}
		return cols;
	}

	private static int visibleCount(Container target)
	{
		int n = 0;
		for (Component c : target.getComponents())
		{
			n += c.isVisible() ? 1 : 0;
		}
		return n;
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

	private int rowHeight(Container target)
	{
		int h = 0;
		for (Component c : target.getComponents())
		{
			if (c.isVisible())
			{
				h = Math.max(h, c.getPreferredSize().height);
			}
		}
		return h;
	}

	/** Columns the last preferred size assumed, per grid (a grid asked before it had a width assumes all of them). */
	private final java.util.Map<Container, Integer> assumed = new java.util.WeakHashMap<>();

	@Override
	public Dimension preferredLayoutSize(Container target)
	{
		synchronized (target.getTreeLock())
		{
			Insets in = target.getInsets();
			int n = visibleCount(target);
			int cols = columns(target, innerWidth(target));
			if (target.getWidth() <= 0)
			{
				assumed.putIfAbsent(target, cols); // asked before it has a width of its own: the first layout checks this
			}
			int rows = n == 0 ? 0 : (n + cols - 1) / cols;
			int cell = 0;
			for (Component c : target.getComponents())
			{
				if (c.isVisible())
				{
					cell = Math.max(cell, c.getPreferredSize().width);
				}
			}
			int h = rows == 0 ? 0 : rows * rowHeight(target) + (rows - 1) * vgap;
			return new Dimension(cols * cell + (cols - 1) * hgap + in.left + in.right, h + in.top + in.bottom);
		}
	}

	@Override
	public Dimension minimumLayoutSize(Container target)
	{
		Dimension d = preferredLayoutSize(target);
		return new Dimension(0, d.height);
	}

	@Override
	public void layoutContainer(Container target)
	{
		synchronized (target.getTreeLock())
		{
			Insets in = target.getInsets();
			int width = target.getWidth() - in.left - in.right;
			int cols = columns(target, width);
			Integer was = assumed.get(target);
			if (was != null && was != cols && width > 0)
			{
				// Its height was asked before it had this width (a first layout): the containers above cached it, so they lay
				// out again, once, now that the grid knows how many rows it really needs (pass 30).
				javax.swing.SwingUtilities.invokeLater(target::revalidate);
			}
			if (width > 0)
			{
				assumed.remove(target);
			}
			int cw = cols == 0 ? 0 : (width - (cols - 1) * hgap) / cols;
			int rh = rowHeight(target);
			int i = 0;
			for (Component c : target.getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}
				int r = i / cols;
				int col = i % cols;
				c.setBounds(in.left + col * (cw + hgap), in.top + r * (rh + vgap), cw, rh);
				i++;
			}
		}
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
	}
}
