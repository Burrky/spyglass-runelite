package com.osrstelemetry.plugin.ui;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Pass 28: one compact "▸ Details" / "▾ Details" line that shows or hides a
 * secondary section (History detail: a skill's per-resource rows, the
 * starting loadout). Click, Space or Enter toggles; it takes keyboard focus
 * and shows it with a brighter colour and an underline-free border.
 */
final class DisclosureToggle extends JLabel
{
	private final String text;
	private final Runnable onToggle;
	private boolean expanded;

	DisclosureToggle(String text, boolean expanded, Runnable onToggle)
	{
		this.text = text;
		this.onToggle = onToggle;
		setFont(SpyglassFonts.small());
		setFocusable(true);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		setExpanded(expanded);
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					toggle();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				setForeground(SpyglassTheme.TEXT_PRIMARY);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				paintState();
			}
		});
		for (int key : new int[]{KeyEvent.VK_SPACE, KeyEvent.VK_ENTER})
		{
			getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "disclosure-toggle");
		}
		getActionMap().put("disclosure-toggle", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				toggle();
			}
		});
		addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				paintState();
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				paintState();
			}
		});
	}

	boolean isExpanded()
	{
		return expanded;
	}

	void setExpanded(boolean expanded)
	{
		this.expanded = expanded;
		setText((expanded ? "▾ " : "▸ ") + text);
		getAccessibleContext().setAccessibleName(text + (expanded ? ", expanded" : ", collapsed"));
		paintState();
	}

	/** The click / Space / Enter action. */
	void toggle()
	{
		setExpanded(!expanded);
		if (onToggle != null)
		{
			onToggle.run();
		}
	}

	private void paintState()
	{
		setForeground(isFocusOwner() ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY);
	}
}
