package com.osrstelemetry.plugin.ui;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Placeholder-only view -- Recent Activity persistence/aggregation is
 * not yet implemented. No fake data, no fake controls: a single static
 * message, nothing else.
 */
final class RecentActivityView extends JPanel
{
	RecentActivityView()
	{
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel label = new JLabel("<html><div style='text-align:center;width:180px;'>"
			+ "Recent Activity is coming soon.</div></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setHorizontalAlignment(SwingConstants.CENTER);

		add(label, BorderLayout.CENTER);
	}
}
