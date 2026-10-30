package com.osrstelemetry.plugin.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * A small raised push button in the Raised Tile look (same bevel as
 * {@link RunTypeButton}, but it never stays pressed): Start Run, Resume, End
 * run, Done. Mouse release inside or Space / Enter (press and release both on
 * it) activate it. A disabled button is flat and quiet and does nothing.
 * PRIMARY draws its label in bold gold.
 */
final class ActionButton extends JComponent
{
	static final int HEIGHT = 26;

	enum Style
	{
		PRIMARY,
		SECONDARY,
		/** Pass 32: the Discord invite: full width, Discord blue, white bold label, taller. */
		DISCORD
	}

	/** Discord's brand blue, and the darker blue it uses for hover / press (white text: 4.6:1 and 6.3:1). */
	static final Color DISCORD_BLUE = new Color(0x58, 0x65, 0xF2);
	static final Color DISCORD_BLUE_DARK = new Color(0x47, 0x52, 0xC4);

	private static java.awt.Font fontFor(Style style)
	{
		return style == Style.SECONDARY ? SpyglassFonts.small() : SpyglassFonts.bold();
	}

	private Style style;
	private String label;
	private Runnable onPress;
	private boolean hovered;
	private boolean down;
	private boolean keyArmed;
	/** Drawn left of the label, the pair centred (null: text only). */
	private java.awt.Image icon;

	ActionButton(String label, Style style, Runnable onPress)
	{
		this.label = label;
		this.style = style;
		this.onPress = onPress;
		setOpaque(true);
		setBackground(SpyglassTheme.TILE_CANVAS);
		setFont(fontFor(style));
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
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
				if (SwingUtilities.isLeftMouseButton(e) && isEnabled())
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
					press();
				}
			}
		});
		for (String key : new String[]{"SPACE", "ENTER"})
		{
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("pressed " + key), "action-press-" + key);
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("released " + key), "action-release-" + key);
			getActionMap().put("action-press-" + key, new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					keyArmed = isEnabled();
					down = keyArmed;
					repaint();
				}
			});
			getActionMap().put("action-release-" + key, new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					boolean armed = keyArmed;
					keyArmed = false;
					down = false;
					repaint();
					if (armed)
					{
						press();
					}
				}
			});
		}
		addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				repaint();
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				keyArmed = false;
				down = false;
				repaint();
			}
		});
	}

	/** Activates (tests, and the mouse / key handlers); a disabled button does nothing. */
	void press()
	{
		if (isEnabled() && onPress != null)
		{
			onPress.run();
		}
	}

	void setAction(Runnable onPress)
	{
		this.onPress = onPress;
	}

	void setLabel(String label)
	{
		if (!label.equals(this.label))
		{
			this.label = label;
			if (accessibleContext != null)
			{
				accessibleContext.setAccessibleName(label);
			}
			repaint();
		}
	}

	/** The icon's drawn size: as given, or (Discord) matched to the text's height so it grows with the text size. */
	private Dimension iconSize(FontMetrics fm)
	{
		if (icon == null)
		{
			return new Dimension(0, 0);
		}
		int w = icon.getWidth(null);
		int h = icon.getHeight(null);
		if (style == Style.DISCORD && w > 0 && h > 0)
		{
			int dh = fm.getHeight() + 2;
			return new Dimension(Math.max(1, dh * w / h), dh);
		}
		return new Dimension(w, h);
	}

	void setIcon(java.awt.Image icon)
	{
		this.icon = icon;
		repaint();
	}

	String getLabel()
	{
		return label;
	}

	/** Changes the emphasis in place (a page whose main action changes meaning keeps the same, focused button). */
	void setStyle(Style style)
	{
		if (style != this.style)
		{
			this.style = style;
			setFont(fontFor(style));
			repaint();
		}
	}

	Style getStyle()
	{
		return style;
	}

	@Override
	public void setEnabled(boolean enabled)
	{
		if (enabled != isEnabled())
		{
			super.setEnabled(enabled);
			setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			repaint();
		}
	}

	/**
	 * Its height follows the text size (pass 30): at least {@link #HEIGHT}, taller for larger text, and two lines when the label
	 * doesn't fit the width it is given (never cut short).
	 */
	@Override
	public Dimension getPreferredSize()
	{
		if (isPreferredSizeSet())
		{
			return super.getPreferredSize();
		}
		FontMetrics fm = getFontMetrics(getFont());
		int iw = icon == null ? 0 : iconSize(fm).width + 6;
		int width = Math.max(80, fm.stringWidth(label) + iw + 16);
		int avail = availableWidth();
		int lines = avail > 0 ? lines(label, fm, avail - 12 - iw).size() : 1;
		if (style == Style.DISCORD)
		{
			// Full width, comfortably tall: two lines when a large text size needs them.
			return new Dimension(avail > 0 ? avail : width, Math.max(SpyglassFonts.px(36), lines * fm.getHeight() + 18));
		}
		return new Dimension(avail > 0 ? Math.min(width, avail) : width, Math.max(HEIGHT, lines * fm.getHeight() + 10));
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(30, getPreferredSize().height);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	private int availableWidth()
	{
		if (getWidth() > 0)
		{
			return getWidth();
		}
		java.awt.Container p = getParent();
		return p == null ? 0 : p.getWidth() - p.getInsets().left - p.getInsets().right;
	}

	/** The label in lines that fit {@code width}, split at spaces (one line when it fits or can't be split). */
	static java.util.List<String> lines(String text, FontMetrics fm, int width)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		if (width <= 0 || fm.stringWidth(text) <= width || text.indexOf(' ') < 0)
		{
			out.add(text);
			return out;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String next = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && fm.stringWidth(next) > width)
			{
				out.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(next);
			}
		}
		out.add(line.toString());
		return out;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int w = getWidth();
		int h = getHeight();
		g.setColor(getBackground());
		g.fillRect(0, 0, w, h);
		boolean enabled = isEnabled();
		boolean seated = down && enabled;
		int fw = w - 1;
		int fh = h - 1;
		int o = seated ? 1 : 0;
		if (!seated)
		{
			g.setColor(SpyglassTheme.TILE_SHADOW_NEAR);
			g.fillRect(1, fh, fw, 1);
			g.fillRect(fw, 1, 1, fh);
		}
		boolean discord = style == Style.DISCORD;
		Color face = discord ? (seated || hovered || !enabled ? DISCORD_BLUE_DARK : DISCORD_BLUE)
			: !enabled ? SpyglassTheme.TILE_FACE_PRESSED : seated ? SpyglassTheme.TILE_FACE_PRESSED
			: hovered ? SpyglassTheme.TILE_FACE_HOVER : SpyglassTheme.TILE_FACE;
		g.setColor(face);
		g.fillRect(o, o, fw - o, fh - o);
		boolean focus = isFocusOwner() && enabled;
		if (discord)
		{
			// Keyboard focus: a white ring inside the edge (two pixels), unmistakable on the blue.
			g.setColor(focus ? Color.WHITE : DISCORD_BLUE_DARK);
			g.drawRect(o, o, fw - 1 - o, fh - 1 - o);
			if (focus)
			{
				g.drawRect(o + 2, o + 2, fw - 5 - o, fh - 5 - o);
			}
		}
		else
		{
			g.setColor(focus ? SpyglassTheme.ACCENT_BRONZE : SpyglassTheme.TILE_RIM);
			g.drawRect(o, o, fw - 1 - o, fh - 1 - o);
		}
		if (enabled && !seated && !discord)
		{
			g.setColor(hovered ? SpyglassTheme.TILE_EDGE_HIGHLIGHT_HOVER : SpyglassTheme.TILE_EDGE_HIGHLIGHT);
			g.fillRect(1, 1, fw - 2, 1);
			g.setColor(SpyglassTheme.TILE_EDGE_SHADE);
			g.fillRect(1, fh - 2, fw - 2, 1);
		}
		g.setFont(getFont());
		FontMetrics fm = g.getFontMetrics();
		Dimension is = iconSize(fm);
		int iw = icon == null ? 0 : is.width + 6;
		java.util.List<String> lines = lines(label, fm, fw - 12 - iw);
		int lh = fm.getHeight();
		int top = o + (fh - lh * lines.size()) / 2;
		Color fg = discord ? Color.WHITE : !enabled ? SpyglassTheme.TEXT_MUTED : style == Style.PRIMARY ? SpyglassTheme.ACCENT_GOLD
			: hovered ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY;
		for (int i = 0; i < lines.size(); i++)
		{
			String text = lines.get(i);
			int tx = o + (fw - fm.stringWidth(text) - iw) / 2 + iw;
			int ty = top + i * lh + fm.getAscent();
			if (icon != null && i == 0)
			{
				((java.awt.Graphics2D) g).setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.drawImage(icon, tx - iw, ty - fm.getAscent() + (lh - is.height) / 2, is.width, is.height, null);
			}
			g.setColor(discord ? DISCORD_BLUE_DARK.darker() : SpyglassTheme.TILE_SHADOW_NEAR);
			g.drawString(text, tx + 1, ty + 1);
			g.setColor(fg);
			g.drawString(text, tx, ty);
		}
	}

	@Override
	public AccessibleContext getAccessibleContext()
	{
		if (accessibleContext == null)
		{
			accessibleContext = new AccessibleJComponent()
			{
				@Override
				public AccessibleRole getAccessibleRole()
				{
					return AccessibleRole.PUSH_BUTTON;
				}

				@Override
				public AccessibleStateSet getAccessibleStateSet()
				{
					AccessibleStateSet set = super.getAccessibleStateSet();
					if (!isEnabled())
					{
						set.remove(AccessibleState.ENABLED);
					}
					return set;
				}
			};
			accessibleContext.setAccessibleName(label);
		}
		return accessibleContext;
	}
}
