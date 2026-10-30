package com.osrstelemetry.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The shared frame of Home's detail pages (Kingdom, Dailies): the recessed canvas, gold-headed tiles, and a rebuild only
 * when the page's text changes (the panel polls every few seconds; nothing is rebuilt for an unchanged page).
 */
abstract class HomeDetailPage extends JPanel
{
	private String signature;

	HomeDetailPage()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(SpyglassTheme.TILE_CANVAS);
		setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
	}

	/** The page's text as a key: equal keys, nothing to rebuild. */
	abstract String signature(long now);

	abstract void build(long now);

	final void render(long now)
	{
		String sig = signature(now);
		if (sig.equals(signature))
		{
			return;
		}
		signature = sig;
		java.awt.Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		String focusName = focus != null && focus.getName() != null && javax.swing.SwingUtilities.isDescendingFrom(focus, this) ? focus.getName() : null;
		removeAll();
		build(now);
		revalidate();
		repaint();
		if (focusName != null)
		{
			Component c = find(this, focusName);
			if (c != null)
			{
				c.requestFocusInWindow();
			}
		}
	}

	/** Forces the next render to rebuild (a change made on this page). */
	final void invalidateSignature()
	{
		signature = null;
	}

	private static Component find(java.awt.Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
			if (c instanceof java.awt.Container)
			{
				Component f = find((java.awt.Container) c, name);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	/** A tile with a gold heading (tooltip: what it means) and its rows. */
	final JComponent card(String title, String tip, List<? extends JComponent> rows, Color rail)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(rail);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel head = new WrappingHtmlLabel(title, false);
		head.setFont(SpyglassFonts.smallBold());
		head.setForeground(SpyglassTheme.ACCENT_GOLD);
		head.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (tip != null)
		{
			head.setToolTipText(tip);
		}
		body.add(head);
		for (JComponent r : rows)
		{
			r.setAlignmentX(Component.LEFT_ALIGNMENT);
			// A row's height follows its width (wrapping lines, a value dropping under its label): never fixed at build time,
			// when the width is not known yet (pass 29: a fixed height cut the last line of a wrapped sentence).
			body.add(r instanceof JPanel && !(r instanceof FitRow) ? growing(r) : r);
		}
		tile.add(body, BorderLayout.CENTER);
		return growing(tile);
	}

	/** Full width, never taller than it needs (wrapping text included). */
	static JComponent growing(JComponent c)
	{
		JPanel p = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setOpaque(false);
		p.add(c, BorderLayout.CENTER);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	final void gap()
	{
		add(Box.createVerticalStrut(8));
	}

	static JLabel text(String s, Color c, boolean bold)
	{
		JLabel l = new JLabel(s);
		l.setFont(bold ? SpyglassFonts.regular() : SpyglassFonts.small());
		l.setForeground(c);
		return l;
	}

	/** A sentence that may wrap. */
	static JComponent sentence(String s, Color c)
	{
		WrappingHtmlLabel l = new WrappingHtmlLabel(s, false);
		l.setFont(SpyglassFonts.small());
		l.setForeground(c);
		return l;
	}

	/** "Label ........ value" on one line (value right-aligned). */
	static JPanel pair(String label, String value, Color valueColor)
	{
		// Pass 29: both on one line when they fit, else the value under the label (never cut short at a narrow sidebar).
		WrappingHtmlLabel l = new WrappingHtmlLabel(label, false); // a long label wraps rather than being cut short (larger text)
		l.setFont(SpyglassFonts.small());
		l.setForeground(SpyglassTheme.TEXT_SECONDARY);
		return new FitRow(l, text(value, valueColor, false));
	}
}
