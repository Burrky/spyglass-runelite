package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.session.SlayerAssignmentEndReason;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Stateless building blocks for one Slayer assignment card, shared by
 * Current Session's live task ledger and History's per-task loot cards so
 * both say the same thing the same way ("Task xN", "· Skipped", tooltip
 * detail). Each view owns its own expand/collapse state and passes the
 * toggle action in -- nothing here couples live and historical views.
 */
final class SlayerTaskCards
{
	private SlayerTaskCards()
	{
	}

	static String taskName(String taskName)
	{
		return taskName == null ? "Slayer task" : taskName;
	}

	/**
	 * "Task name xN" plus " · Skipped" for REPLACED_EARLY only (COMPLETED and
	 * unfinished assignments carry no badge). xN is omitted when genuinely
	 * unknown (legacy record).
	 */
	static String title(String taskName, Integer unitsCompleted, SlayerAssignmentEndReason endReason)
	{
		StringBuilder sb = new StringBuilder(taskName(taskName));
		if (unitsCompleted != null)
		{
			sb.append(" x").append(unitsCompleted);
		}
		if (endReason == SlayerAssignmentEndReason.REPLACED_EARLY)
		{
			sb.append(" · Skipped");
		}
		return sb.toString();
	}

	/**
	 * Detail kept out of the narrow header: "17 / 75 completed" (the total
	 * only for a fully observed assignment), remaining, end state, then any
	 * view-specific `extraLines`. Only persisted, authoritative values.
	 */
	static String tooltip(String taskName, Integer unitsCompleted, boolean bootstrapped,
		Integer remaining, SlayerAssignmentEndReason endReason, List<String> extraLines)
	{
		// The assignment's original size is never shown: bracelets make kills
		// and task units diverge, so only units done and units remaining are.
		StringBuilder sb = new StringBuilder("<html>").append(escapeHtml(taskName(taskName)));
		if (unitsCompleted != null)
		{
			sb.append("<br>").append(unitsCompleted);
			sb.append(" completed");
			if (bootstrapped)
			{
				sb.append(" (tracked since Spyglass joined mid-task)");
			}
		}
		if (remaining != null)
		{
			sb.append("<br>").append(remaining).append(" remaining");
		}
		if (endReason == SlayerAssignmentEndReason.REPLACED_EARLY)
		{
			sb.append("<br>Skipped");
		}
		else if (endReason == SlayerAssignmentEndReason.COMPLETED)
		{
			sb.append("<br>Completed");
		}
		if (extraLines != null)
		{
			for (String line : extraLines)
			{
				sb.append("<br>").append(escapeHtml(line));
			}
		}
		return sb.append("</html>").toString();
	}

	/**
	 * One collapsible card: "[chevron] title ... value gp" header (click
	 * anywhere on it to toggle), then `expandedContent` below it when
	 * non-null. Sidebar-safe: the title may shrink to 0 and is truncated
	 * with "..." by the label itself, so the value never leaves the card.
	 */
	static JPanel card(String name, boolean expanded, String title, String tooltip, Color titleColor,
		long valueGp, Runnable onToggle, JPanel expandedContent)
	{
		JPanel card = new JPanel();
		card.setName(name);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setOpaque(true);
		card.setBackground(SpyglassTheme.BACKGROUND_PANEL);
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SpyglassTheme.BORDER_SOFT),
			BorderFactory.createEmptyBorder(3, 5, 3, 5)));

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel titleLabel = new JLabel((expanded ? "▾ " : "▸ ") + title);
		titleLabel.setFont(FontManager.getRunescapeSmallFont());
		titleLabel.setForeground(titleColor);
		titleLabel.setToolTipText(tooltip);
		titleLabel.setMinimumSize(new Dimension(0, titleLabel.getPreferredSize().height));
		header.add(titleLabel, BorderLayout.CENTER);
		JLabel value = new JLabel(QuantityFormatter.quantityToStackSize(valueGp) + " gp");
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(SpyglassTheme.ACCENT_GOLD);
		header.add(value, BorderLayout.EAST);
		MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onToggle.run();
			}
		};
		header.addMouseListener(toggle);
		// The title carries a tooltip (which registers its own mouse listener
		// and so would swallow clicks) -- give it the toggle too.
		titleLabel.addMouseListener(toggle);
		value.addMouseListener(toggle);
		card.add(header);

		if (expandedContent != null)
		{
			card.add(Box.createVerticalStrut(4));
			card.add(expandedContent);
		}
		return card;
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
