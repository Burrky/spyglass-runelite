package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The Farming run-type button (canonical §2.2): a small Raised Tile that,
 * once selected, STAYS pressed in (inverted bevel, gold label, seated 1 px)
 * until another one is chosen. Pressing the selected one again does nothing.
 * A 5 px gold pip in the top-right says "something of this type is ready".
 *
 * Custom-painted (not an L&amp;F toggle button, which RuneLite's look and feel
 * flattens). Activation fires on release inside, like {@link RaisedTile}.
 */
final class RunTypeButton extends JComponent
{
	static final int HEIGHT = 24;
	private static final int PIP = 5;

	private String label;
	private final Runnable onPress;
	private boolean selected;
	private boolean pip;
	private boolean hovered;
	private boolean down;

	RunTypeButton(String label, Runnable onPress)
	{
		this.label = label;
		this.onPress = onPress;
		setOpaque(true);
		setBackground(SpyglassTheme.TILE_CANVAS);
		setFont(SpyglassFonts.small());
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hovered = true;
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hovered = false;
				down = false;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					down = true;
					repaint();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				boolean wasDown = down;
				down = false;
				repaint();
				if (wasDown && SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint()))
				{
					onPress.run();
				}
			}
		};
		addMouseListener(mouse);
		// Keyboard (pass 30): Tab reaches it, Space / Enter presses it, a bronze rim shows focus.
		setFocusable(true);
		for (String key : new String[]{"SPACE", "ENTER"})
		{
			getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("released " + key), "runtype-press");
		}
		getActionMap().put("runtype-press", new javax.swing.AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				onPress.run();
			}
		});
		addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				repaint();
			}

			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				repaint();
			}
		});
	}

	@Override
	public javax.accessibility.AccessibleContext getAccessibleContext()
	{
		if (accessibleContext == null)
		{
			accessibleContext = new AccessibleJComponent()
			{
				@Override
				public javax.accessibility.AccessibleRole getAccessibleRole()
				{
					return javax.accessibility.AccessibleRole.TOGGLE_BUTTON;
				}

				@Override
				public javax.accessibility.AccessibleStateSet getAccessibleStateSet()
				{
					javax.accessibility.AccessibleStateSet set = super.getAccessibleStateSet();
					if (selected)
					{
						set.add(javax.accessibility.AccessibleState.SELECTED);
					}
					return set;
				}
			};
			accessibleContext.setAccessibleName(label);
		}
		return accessibleContext;
	}

	/** Pass 32: Farming's navigation size (larger text and padding: Overview, Combine and the categories). */
	private boolean large;
	/** Pass 32: when selected, a gold face (a mode that is on), not only a seated one. */
	private boolean emphasis;

	RunTypeButton large()
	{
		large = true;
		setFont(SpyglassFonts.regular());
		return this;
	}

	RunTypeButton emphasis()
	{
		emphasis = true;
		return this;
	}

	boolean isLarge()
	{
		return large;
	}

	void setSelected(boolean selected)
	{
		if (this.selected != selected)
		{
			this.selected = selected;
			repaint();
		}
	}

	boolean isSelected()
	{
		return selected;
	}

	void setPip(boolean pip)
	{
		if (this.pip != pip)
		{
			this.pip = pip;
			repaint();
		}
	}

	boolean hasPip()
	{
		return pip;
	}

	void setLabel(String label)
	{
		if (!label.equals(this.label))
		{
			this.label = label;
			repaint();
		}
	}

	String getLabel()
	{
		return label;
	}

	/** Sized by its text at the current text size (pass 30): its grid drops a column rather than cutting the label. */
	@Override
	public Dimension getPreferredSize()
	{
		if (isPreferredSizeSet())
		{
			return super.getPreferredSize();
		}
		java.awt.FontMetrics fm = getFontMetrics(SpyglassFonts.bold());
		if (large)
		{
			// Pass 32: wraps onto more lines rather than cutting a long label short (Combine at Extra large).
			int pipW = pip ? PIP + 2 : 0;
			int avail = getWidth() > 0 ? getWidth() : getParent() == null ? 0 : getParent().getWidth() - getParent().getInsets().left - getParent().getInsets().right;
			int lines = avail > 0 ? ActionButton.lines(label, fm, avail - 10 - pipW).size() : 1;
			int width = fm.stringWidth(label) + 20 + pipW;
			return new Dimension(Math.max(48, avail > 0 && lines > 1 ? Math.min(width, avail) : width), Math.max(SpyglassFonts.px(32), lines * fm.getHeight() + 14));
		}
		return new Dimension(Math.max(40, fm.stringWidth(label) + 12 + (pip ? PIP + 2 : 0)), Math.max(HEIGHT, fm.getHeight() + 8));
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(20, getPreferredSize().height);
	}

	/** Programmatic press (tests). */
	void press()
	{
		onPress.run();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int w = getWidth();
		int h = getHeight();
		g.setColor(getBackground());
		g.fillRect(0, 0, w, h);
		boolean seated = selected || down;
		int fw = w - 1;
		int fh = h - 1;
		int o = seated ? 1 : 0;
		if (!seated)
		{
			// 1 px drop shadow, bottom and right.
			g.setColor(SpyglassTheme.TILE_SHADOW_NEAR);
			g.fillRect(1, fh, fw, 1);
			g.fillRect(fw, 1, 1, fh);
		}
		Color face = selected && emphasis ? SpyglassTheme.MODE_ON_FACE : selected ? SpyglassTheme.TILE_FACE_PRESSED : down ? SpyglassTheme.TILE_FACE_PRESSED
			: hovered ? SpyglassTheme.TILE_FACE_HOVER : SpyglassTheme.TILE_FACE;
		g.setColor(face);
		g.fillRect(o, o, fw - o, fh - o);
		g.setColor(isFocusOwner() ? SpyglassTheme.ACCENT_BRONZE : selected ? SpyglassTheme.BORDER_PRIMARY : SpyglassTheme.TILE_RIM);
		g.drawRect(o, o, fw - 1 - o, fh - 1 - o);
		if (seated)
		{
			// Inverted bevel: shade along the inner top and left edge.
			g.setColor(SpyglassTheme.TILE_SHADOW_NEAR);
			g.fillRect(o + 1, o + 1, fw - 2 - o, 1);
			g.fillRect(o + 1, o + 1, 1, fh - 2 - o);
		}
		else
		{
			g.setColor(hovered ? SpyglassTheme.TILE_EDGE_HIGHLIGHT_HOVER : SpyglassTheme.TILE_EDGE_HIGHLIGHT);
			g.fillRect(1, 1, fw - 2, 1);
			g.setColor(SpyglassTheme.TILE_EDGE_SHADE);
			g.fillRect(1, fh - 2, fw - 2, 1);
		}
		if (pip)
		{
			g.setColor(SpyglassTheme.ACCENT_GOLD);
			g.fillRect(fw - PIP - 2 + o, o + 3, PIP, PIP);
		}
		g.setFont(selected ? SpyglassFonts.bold() : getFont());
		FontMetrics fm = g.getFontMetrics();
		java.util.List<String> lines = large ? ActionButton.lines(label, fm, fw - 10 - (pip ? PIP + 2 : 0))
			: java.util.Collections.singletonList(fit(label, fm, fw - 6 - (pip ? PIP + 2 : 0)));
		Color fg = selected && emphasis ? SpyglassTheme.TEXT_PRIMARY : selected ? SpyglassTheme.ACCENT_GOLD
			: hovered || large ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY;
		int top = o + (fh - fm.getHeight() * lines.size()) / 2;
		for (int i = 0; i < lines.size(); i++)
		{
			String text = large ? fit(lines.get(i), fm, fw - 6 - (pip ? PIP + 2 : 0)) : lines.get(i);
			int tx = o + (fw - fm.stringWidth(text)) / 2;
			int ty = top + i * fm.getHeight() + fm.getAscent();
			g.setColor(SpyglassTheme.TILE_SHADOW_NEAR);
			g.drawString(text, tx + 1, ty + 1);
			g.setColor(fg);
			g.drawString(text, tx, ty);
		}
		if (selected && emphasis)
		{
			g.setColor(SpyglassTheme.ACCENT_GOLD);
			g.drawRect(o + 1, o + 1, fw - 3 - o, fh - 3 - o);
		}
	}

	private static String fit(String text, FontMetrics fm, int width)
	{
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		String s = text;
		while (s.length() > 1 && fm.stringWidth(s + ".") > width)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + ".";
	}
}
