package com.osrstelemetry.plugin.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * A ready activity on Home's Dailies &amp; Weeklies card (pass 31): its item at the native 36×32, what is left of a counted
 * claim in the corner as the game draws stack numbers, and a click (or Space / Enter) that opens the page at that activity.
 */
final class BadgeIcon extends JComponent
{
	private final HomeText.Badge badge;
	private final BufferedImage sprite;
	private boolean focused;
	private boolean hovered;

	BadgeIcon(HomeText.Badge badge, FarmingView.SpriteSource sprites, java.util.function.Consumer<String> onOpen)
	{
		this.badge = badge;
		setName("badge." + badge.key);
		setOpaque(false);
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setToolTipText(badge.label);
		getAccessibleContext().setAccessibleName(badge.label + ". Opens Dailies & Weeklies");
		this.sprite = sprites == null ? null : sprites.load(badge.itemId, () -> javax.swing.SwingUtilities.invokeLater(this::repaint));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				e.consume();
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (contains(e.getPoint()) && e.getButton() == MouseEvent.BUTTON1 && onOpen != null)
				{
					onOpen.accept(badge.key);
				}
				e.consume();
			}

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
				repaint();
			}
		});
		addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				focused = true;
				repaint();
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				focused = false;
				repaint();
			}
		});
		for (String key : new String[]{"SPACE", "ENTER"})
		{
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "badge.open");
		}
		getActionMap().put("badge.open", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (onOpen != null)
				{
					onOpen.accept(badge.key);
				}
			}
		});
	}

	HomeText.Badge getBadge()
	{
		return badge;
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(ItemIcon.WIDTH + 2, ItemIcon.HEIGHT + 2);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		if (hovered || focused)
		{
			g2.setColor(SpyglassTheme.TILE_FACE_HOVER);
			g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		}
		if (sprite != null)
		{
			g2.drawImage(sprite, 1, 1, null);
		}
		if (badge.count != null)
		{
			g2.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
			java.awt.FontMetrics fm = g2.getFontMetrics();
			int x = 1;
			int y = fm.getAscent();
			g2.setColor(java.awt.Color.BLACK);
			g2.drawString(badge.count, x + 1, y + 1);
			g2.setColor(new java.awt.Color(0xFF, 0xFF, 0x00));
			g2.drawString(badge.count, x, y);
		}
		if (focused)
		{
			g2.setColor(SpyglassTheme.ACCENT_GOLD);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
		}
		g2.dispose();
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
			};
		}
		return accessibleContext;
	}
}
