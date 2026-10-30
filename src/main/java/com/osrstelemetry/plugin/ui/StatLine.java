package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * "Label ........ value" on one line when both fit the sidebar's real width;
 * otherwise the value moves to its own right-aligned line under the label
 * (restructured, never squeezed or clipped). Used by the Slayer dashboard,
 * where values such as a Konar location can be long at 150px.
 */
final class StatLine extends JPanel
{
	static final int GAP = 6;
	static final int FALLBACK_WIDTH = 160;

	private final JLabel label;
	private final JLabel value;

	StatLine(String labelText, String valueText, Color labelColor, Color valueColor, boolean boldValue)
	{
		super(null);
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		label = new JLabel(labelText);
		label.setFont(SpyglassFonts.small());
		label.setForeground(labelColor);
		value = new JLabel(valueText);
		value.setFont(boldValue ? SpyglassFonts.bold() : SpyglassFonts.small());
		value.setForeground(valueColor);
		value.setHorizontalAlignment(JLabel.RIGHT);
		add(label);
		add(value);
	}

	JLabel getLabel()
	{
		return label;
	}

	JLabel getValue()
	{
		return value;
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

	/** True when label and value cannot share one line at `width`. */
	boolean wraps(int width)
	{
		return label.getPreferredSize().width + GAP + value.getPreferredSize().width > width;
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension l = label.getPreferredSize();
		Dimension v = value.getPreferredSize();
		int width = availableWidth();
		int height = wraps(width) ? l.height + v.height : Math.max(l.height, v.height);
		return new Dimension(Math.min(width, Math.max(l.width, v.width)), height);
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
		boolean before = getWidth() > 0 && wraps(getWidth());
		super.setBounds(x, y, width, height);
		if (getWidth() > 0 && wraps(getWidth()) != before)
		{
			// Crossing the wrap threshold changes this line's height.
			revalidate();
		}
	}

	@Override
	public void doLayout()
	{
		int width = getWidth();
		Dimension l = label.getPreferredSize();
		Dimension v = value.getPreferredSize();
		if (wraps(width))
		{
			label.setBounds(0, 0, Math.min(width, l.width), l.height);
			int vw = Math.min(width, v.width);
			value.setBounds(width - vw, l.height, vw, v.height);
		}
		else
		{
			int h = Math.max(l.height, v.height);
			label.setBounds(0, (h - l.height) / 2, l.width, l.height);
			value.setBounds(width - v.width, (h - v.height) / 2, v.width, v.height);
		}
	}
}
