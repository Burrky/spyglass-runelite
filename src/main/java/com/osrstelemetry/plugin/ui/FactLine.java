package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A row of short facts ("Sep 20, 11:00 AM  •  3:28:40") built from PLAIN
 * labels that wrap BETWEEN facts at the actual width -- never inside a fact,
 * never clipped, no tiny text, and no line ever starts or ends with a bullet.
 *
 * WHY NOT A WRAPPING HTML LABEL: an HTML label costs ~0.6 ms to build (Swing
 * parses the HTML into a view tree) against ~0.01 ms for a plain label.
 * History builds two fact lines per row -- as HTML, 300 rows meant a ~400 ms
 * stall on the EDT every time the list rebuilt (measured); as FactLines it is
 * ~20 ms, as cheap as the old single-line rows. Current Session's header
 * (re-rendered every second) and History's detail header use it too, so all
 * fact lines in Spyglass look and wrap the same way.
 */
final class FactLine extends JPanel
{
	/** The bullet between two facts on the same line. */
	static final String SEPARATOR = "  •  ";

	private final Font font;
	private final Color color;
	/** Each fact is one or more glued segments (e.g. "3:28:40" + muted " (frozen)"). */
	private final List<List<JLabel>> facts = new ArrayList<>();
	private final List<JLabel> separators = new ArrayList<>();

	FactLine(Font font, Color color)
	{
		super(null);
		this.font = font;
		this.color = color;
		setOpaque(false);
		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	/** Starts a new fact. */
	FactLine fact(String text)
	{
		return fact(text, color);
	}

	FactLine fact(String text, Color segmentColor)
	{
		if (!facts.isEmpty())
		{
			JLabel sep = label(SEPARATOR, color);
			separators.add(sep);
			add(sep);
		}
		List<JLabel> segments = new ArrayList<>();
		facts.add(segments);
		return glue(text, segmentColor, segments);
	}

	/** Appends to the current fact with no separator and no wrap point. */
	FactLine append(String text, Color segmentColor)
	{
		if (facts.isEmpty())
		{
			return fact(text, segmentColor);
		}
		return glue(text, segmentColor, facts.get(facts.size() - 1));
	}

	boolean isEmpty()
	{
		return facts.isEmpty();
	}

	/** The text a player reads, on one line (tests, tooltips). */
	String text()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < facts.size(); i++)
		{
			if (i > 0)
			{
				sb.append(SEPARATOR);
			}
			for (JLabel segment : facts.get(i))
			{
				sb.append(segment.getText());
			}
		}
		return sb.toString();
	}

	private FactLine glue(String text, Color segmentColor, List<JLabel> segments)
	{
		JLabel segment = label(text, segmentColor);
		segments.add(segment);
		add(segment);
		return this;
	}

	private JLabel label(String text, Color c)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(c);
		return l;
	}

	private int availableWidth()
	{
		Container parent = getParent();
		if (parent == null || parent.getWidth() <= 0)
		{
			return WrappingHtmlLabel.FALLBACK_WIDTH;
		}
		Insets in = parent.getInsets();
		return Math.max(1, parent.getWidth() - in.left - in.right);
	}

	private static int widthOf(List<JLabel> segments)
	{
		int w = 0;
		for (JLabel s : segments)
		{
			w += s.getPreferredSize().width;
		}
		return w;
	}

	private int lineHeight()
	{
		int h = 0;
		for (Component c : getComponents())
		{
			h = Math.max(h, c.getPreferredSize().height);
		}
		return h;
	}

	/** Lays the facts out at `width` (or only counts lines when !place); returns the line count. */
	private int flow(int width, boolean place, int left, int top)
	{
		if (facts.isEmpty())
		{
			return 0;
		}
		int lineHeight = lineHeight();
		int x = 0;
		int line = 0;
		for (int i = 0; i < facts.size(); i++)
		{
			List<JLabel> segments = facts.get(i);
			int factWidth = widthOf(segments);
			JLabel sep = i == 0 ? null : separators.get(i - 1);
			int sepWidth = sep == null ? 0 : sep.getPreferredSize().width;
			boolean wrap = i > 0 && x + sepWidth + factWidth > width;
			if (wrap)
			{
				line++;
				x = 0;
				if (place)
				{
					sep.setBounds(0, 0, 0, 0);
				}
			}
			else if (sep != null)
			{
				if (place)
				{
					sep.setBounds(left + x, top + line * lineHeight, sepWidth, lineHeight);
				}
				x += sepWidth;
			}
			for (JLabel s : segments)
			{
				int w = s.getPreferredSize().width;
				if (place)
				{
					// A single fact wider than the whole line still never spills
					// past the edge: its last segment ellipsizes in place.
					s.setBounds(left + x, top + line * lineHeight, Math.max(0, Math.min(w, width - x)), lineHeight);
				}
				x += w;
			}
		}
		return line + 1;
	}

	@Override
	public Dimension getPreferredSize()
	{
		Insets in = getInsets();
		int width = availableWidth();
		int lines = flow(Math.max(1, width - in.left - in.right), false, 0, 0);
		return new Dimension(width, lines * lineHeight() + in.top + in.bottom);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(0, getPreferredSize().height);
	}

	@Override
	public void doLayout()
	{
		Insets in = getInsets();
		flow(Math.max(1, getWidth() - in.left - in.right), true, in.left, in.top);
	}
}
