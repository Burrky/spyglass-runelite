package com.osrstelemetry.plugin.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.JPanel;

/**
 * Two pieces on one line (one at the left, one at the right) when both fit at the width the row is given; otherwise the right
 * piece drops below the left, left-aligned. Nothing is ever cut short or overlapped at a narrow sidebar or a large font scale.
 */
final class FitRow extends JPanel
{
	private static final int GAP = 6;
	private final Component west;
	private final Component east;

	FitRow(Component west, Component east)
	{
		super(null);
		setOpaque(false);
		this.west = west;
		this.east = east;
		add(west);
		if (east != null)
		{
			add(east);
		}
	}

	private int available()
	{
		Container p = getParent();
		int w = getWidth() > 0 ? getWidth() : p == null ? 0 : p.getWidth() - p.getInsets().left - p.getInsets().right;
		Insets in = getInsets();
		return Math.max(0, w - in.left - in.right);
	}

	/** The west piece's one-line width (a wrapping line reports the whole row as its preferred width). */
	private int westWidth()
	{
		return west instanceof WrappingHtmlLabel ? ((WrappingHtmlLabel) west).naturalWidth() : west.getPreferredSize().width;
	}

	private boolean fits(int width)
	{
		return east == null || width <= 0 || westWidth() + GAP + east.getPreferredSize().width <= width;
	}

	/** Both pieces share a line at the current width (tests). */
	boolean isOneLine()
	{
		return fits(available());
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension w = new Dimension(westWidth(), west.getPreferredSize().height);
		Dimension e = east == null ? new Dimension(0, 0) : east.getPreferredSize();
		Insets in = getInsets();
		boolean one = fits(available());
		if (getWidth() <= 0 && measuredOneLine == null)
		{
			measuredOneLine = one; // asked before it had a width of its own: an assumption the first layout checks
		}
		int width = one ? w.width + (east == null ? 0 : GAP + e.width) : Math.max(w.width, e.width);
		int height = one ? Math.max(w.height, e.height) : w.height + e.height;
		return new Dimension(width + in.left + in.right, height + in.top + in.bottom);
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

	/** What the last preferred size assumed (one line or two); null before it was asked. */
	private Boolean measuredOneLine;

	@Override
	public void doLayout()
	{
		Insets in = getInsets();
		int width = getWidth() - in.left - in.right;
		if (east != null && width > 0 && measuredOneLine != null)
		{
			// Its height was first asked before it had its width and assumed an arrangement that does not hold at this width:
			// the containers above cached that height, so they lay out again, once (pass 30: larger text sizes).
			boolean assumed = measuredOneLine;
			measuredOneLine = null;
			if (assumed != fits(width))
			{
				javax.swing.SwingUtilities.invokeLater(this::revalidate);
			}
		}
		Dimension w = new Dimension(westWidth(), west.getPreferredSize().height);
		if (east == null)
		{
			west.setBounds(in.left, in.top, Math.min(w.width, width), w.height);
			return;
		}
		Dimension e = east.getPreferredSize();
		if (fits(width))
		{
			int h = Math.max(w.height, e.height);
			west.setBounds(in.left, in.top + (h - w.height) / 2, w.width, w.height);
			east.setBounds(in.left + width - e.width, in.top + (h - e.height) / 2, e.width, e.height);
		}
		else
		{
			west.setBounds(in.left, in.top, Math.min(w.width, width), w.height);
			east.setBounds(in.left, in.top + w.height, Math.min(e.width, width), e.height);
		}
	}
}
