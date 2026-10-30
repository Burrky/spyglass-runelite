package com.osrstelemetry.plugin.ui;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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

/**
 * A small checkbox drawn in Spyglass's colours, sized with the text (pass 31: activity cards' "track this" and the Farming
 * grid's "include in the combined run"). A click, Space or Enter toggles it and calls {@code onToggle} with the new state; it
 * never does anything else (selection, not completion). Focusable, with a check box's accessible role and state.
 */
final class TrackBox extends JComponent
{
	private boolean selected;
	private final java.util.function.Consumer<Boolean> onToggle;
	private boolean focused;

	TrackBox(boolean selected, String accessibleName, java.util.function.Consumer<Boolean> onToggle)
	{
		this.selected = selected;
		this.onToggle = onToggle;
		setOpaque(false);
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		getAccessibleContext().setAccessibleName(accessibleName);
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (isEnabled() && contains(e.getPoint()) && e.getButton() == MouseEvent.BUTTON1)
				{
					toggle();
				}
				e.consume();
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				e.consume(); // never the card's own press
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
			getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "trackbox.toggle");
		}
		getActionMap().put("trackbox.toggle", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (isEnabled())
				{
					toggle();
				}
			}
		});
	}

	boolean isSelected()
	{
		return selected;
	}

	/** As a click: flips the state and reports it. */
	void toggle()
	{
		selected = !selected;
		repaint();
		if (onToggle != null)
		{
			onToggle.accept(selected);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		int s = SpyglassFonts.px(16);
		return new Dimension(s, s);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return getPreferredSize();
	}

	@Override
	public Dimension getMaximumSize()
	{
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int s = Math.min(getWidth(), getHeight()) - 2;
		int x = (getWidth() - s) / 2;
		int y = (getHeight() - s) / 2;
		g2.setColor(SpyglassTheme.TILE_CANVAS);
		g2.fillRoundRect(x, y, s, s, 4, 4);
		g2.setColor(!isEnabled() ? SpyglassTheme.TEXT_MUTED : selected ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.TEXT_SECONDARY);
		g2.setStroke(new BasicStroke(focused ? 2f : 1.2f));
		g2.drawRoundRect(x, y, s, s, 4, 4);
		if (selected)
		{
			g2.setStroke(new BasicStroke(Math.max(2f, s / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(SpyglassTheme.ACCENT_SAGE);
			g2.drawPolyline(new int[]{x + s / 4, x + s * 5 / 12, x + s * 3 / 4}, new int[]{y + s / 2, y + s * 7 / 10, y + s * 3 / 10}, 3);
		}
		if (focused)
		{
			g2.setColor(SpyglassTheme.ACCENT_GOLD);
			g2.setStroke(new BasicStroke(1f));
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 5, 5);
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
					return AccessibleRole.CHECK_BOX;
				}

				@Override
				public AccessibleStateSet getAccessibleStateSet()
				{
					AccessibleStateSet set = super.getAccessibleStateSet();
					if (selected)
					{
						set.add(AccessibleState.CHECKED);
					}
					return set;
				}
			};
		}
		return accessibleContext;
	}
}
