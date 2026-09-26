package com.osrstelemetry.plugin.ui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/**
 * A text label that word-wraps to the ACTUAL width of its parent
 * container, never to a hardcoded width.
 *
 * WHY THIS EXISTS: the previous empty-state pattern,
 * {@code <html><div style='width:180px'>}, is NOT 180 screen pixels.
 * Swing's HTML/CSS engine (without the W3C length-units client property)
 * scales CSS {@code px} by 1.3, so that label's preferred width was ~233px
 * -- wider than the ~201px a RuneLite sidebar actually offers inside the
 * panel's padding. The text was laid out at 233px while the label was
 * clamped to the sidebar width, so its right side was cut off, at any
 * monitor resolution.
 *
 * Here no CSS width is used at all: preferred width is the parent's
 * current inner width, and preferred height is the HTML view's own
 * preferred height when wrapped at exactly that width. Before the parent
 * has been laid out (width 0) a conservative fallback width is used; the
 * next revalidate (Current Session re-renders every poll tick) settles it.
 */
final class WrappingHtmlLabel extends JLabel
{
	/** Used only before the parent has a real width; comfortably inside any sidebar. */
	static final int FALLBACK_WIDTH = 160;

	WrappingHtmlLabel(String plainText, boolean centered)
	{
		super("<html><div style='text-align:" + (centered ? "center" : "left") + ";'>"
			+ escape(plainText) + "</div></html>");
	}

	int availableWidth()
	{
		Container parent = getParent();
		if (parent == null || parent.getWidth() <= 0)
		{
			return FALLBACK_WIDTH;
		}
		Insets in = parent.getInsets();
		return Math.max(1, parent.getWidth() - in.left - in.right);
	}

	@Override
	public Dimension getPreferredSize()
	{
		View view = (View) getClientProperty(BasicHTML.propertyKey);
		if (view == null)
		{
			return super.getPreferredSize();
		}
		Insets insets = getInsets();
		int width = availableWidth();
		int textWidth = Math.max(1, width - insets.left - insets.right);
		view.setSize(textWidth, 0);
		int height = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
		return new Dimension(width, height);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
