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

	private String plain;

	WrappingHtmlLabel(String plainText, boolean centered)
	{
		super(html(plainText, centered));
		this.plain = plainText;
		// Text from the top: the label centres its text on the painted view's last measured height, which is stale until it has
		// painted once at the current width, so a centred wrapped sentence was drawn too low and its last line cut (pass 29).
		setVerticalAlignment(TOP);
	}

	/** The text as given (no markup). */
	String plainText()
	{
		return plain;
	}

	/** Replaces the text (plain, escaped); a no-op when unchanged, so per-second updates cost nothing. */
	void setPlainText(String plainText)
	{
		plain = plainText;
		String html = html(plainText, false);
		if (!html.equals(super.getText()))
		{
			setText(html);
		}
	}

	private static String html(String plainText, boolean centered)
	{
		return "<html><div style='text-align:" + (centered ? "center" : "left") + ";'>" + escape(plainText) + "</div></html>";
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
		// Measured on a view of its own: resizing the painted view here (to the parent's width) while painting resizes it
		// back (to the label's own, narrower width beside an icon) made each pass repaint and re-lay out the other, an
		// endless loop that kept the EDT busy (found 2026-10-22 on the preparation page).
		View m = measure();
		m.setSize(textWidth, 0);
		int height = (int) Math.ceil(m.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
		return new Dimension(width, height);
	}

	private View measureView;
	private String measureText;
	private java.awt.Font measureFont;

	/** A separate HTML view of the same text and font, used only to measure (never painted). */
	private View measure()
	{
		String text = super.getText();
		if (measureView == null || !java.util.Objects.equals(text, measureText) || !java.util.Objects.equals(getFont(), measureFont))
		{
			measureView = BasicHTML.createHTMLView(this, text == null ? "" : text);
			measureText = text;
			measureFont = getFont();
		}
		return measureView;
	}

	/** Its width on one line (unwrapped): a row beside something else checks this to decide whether both fit. */
	int naturalWidth()
	{
		String text = super.getText();
		if (naturalText == null || !naturalText.equals(text) || !java.util.Objects.equals(getFont(), naturalFont))
		{
			// A view of its own that is never given a width (a sized view reports that width as its preferred span).
			View v = BasicHTML.createHTMLView(this, text == null ? "" : text);
			natural = (int) Math.ceil(v.getPreferredSpan(View.X_AXIS));
			naturalText = text;
			naturalFont = getFont();
		}
		Insets in = getInsets();
		return natural + in.left + in.right;
	}

	private int natural;
	private String naturalText;
	private java.awt.Font naturalFont;

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
