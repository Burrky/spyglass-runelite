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
		return card(name, expanded, title, tooltip, titleColor, valueGp, onToggle, null, expandedContent, java.util.Collections.<String>emptyList());
	}

	/** Component name prefix of a card's compact sub-lines ("235 actual kills", "1 superior"). */
	static final String SUB_LINE_PREFIX = "slayerTaskSubLine:";
	static final String DETAIL_TASK_GRID = "slayerTaskDetailTask";
	static final String DETAIL_LOOT_GRID = "slayerTaskDetailLoot";
	static final String DETAIL_SUPERIOR_PREFIX = "slayerTaskSuperior:";
	static final String DETAIL_BREAKDOWN_PREFIX = "slayerTaskBreakdown:";
	static final String DETAIL_BREAKDOWN_TOTAL = "slayerTaskBreakdownTotal";
	static final String TASK_COUNT_CELL = "TASK COUNT";
	static final String ACTUAL_KILLS_CELL = "ACTUAL KILLS";
	static final String LOOT_TOTAL_CELL = "TOTAL";
	static final String LOOT_PER_KILL_CELL = "LOOT / KILL";

	/**
	 * The card's compact lines under its "Task xN ... gp" header: the ACTUAL
	 * kills (every NPC killed during the task, subordinates included -- the
	 * header's xN is the task count), then superiors when any. Each only when
	 * known; an older record without them shows nothing new (never a 0).
	 */
	static List<String> subLines(Integer actualKills, java.util.Map<String, Long> superiors)
	{
		List<String> lines = new java.util.ArrayList<>();
		if (actualKills != null)
		{
			lines.add(String.format(java.util.Locale.ROOT, "%,d", actualKills) + (actualKills == 1 ? " actual kill" : " actual kills"));
		}
		long sup = total(superiors);
		if (sup > 0)
		{
			lines.add(String.format(java.util.Locale.ROOT, "%,d", sup) + (sup == 1 ? " superior" : " superiors"));
		}
		return lines;
	}

	static long total(java.util.Map<String, Long> counts)
	{
		long n = 0L;
		if (counts != null)
		{
			for (Long v : counts.values())
			{
				n += v == null ? 0L : v;
			}
		}
		return n;
	}

	/**
	 * The expanded card's facts, before its loot: TASK (TASK COUNT | ACTUAL
	 * KILLS), LOOT (TOTAL | LOOT / KILL -- per ACTUAL kill, never per task
	 * unit), SUPERIORS by exact creature, and the KILL BREAKDOWN by exact NPC
	 * (only when it accounts for every actual kill). Each part only when known;
	 * null when there is nothing to show.
	 */
	static JPanel details(Integer taskCount, Integer actualKills, long lootGp, java.util.Map<String, Long> superiors,
		java.util.Map<String, Long> breakdown, boolean breakdownComplete)
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (taskCount != null || actualKills != null)
		{
			body.add(caption("Task"));
			SlayerStatGrid task = new SlayerStatGrid();
			task.setName(DETAIL_TASK_GRID);
			task.setMaxColumns(2);
			task.addCell(TASK_COUNT_CELL, taskCount == null ? null : fmt(taskCount), SpyglassTheme.TEXT_PRIMARY,
				"What this task asked for, as the game counts it (what moved the task forward)");
			task.addCell(ACTUAL_KILLS_CELL, actualKills == null ? null : fmt(actualKills), SpyglassTheme.TEXT_PRIMARY,
				"Every NPC you actually killed during this task -- summoned NPCs and superiors included");
			body.add(task);
		}
		if (lootGp > 0)
		{
			body.add(Box.createVerticalStrut(4));
			body.add(caption("Loot"));
			SlayerStatGrid loot = new SlayerStatGrid();
			loot.setName(DETAIL_LOOT_GRID);
			loot.setMaxColumns(2);
			loot.addCell(LOOT_TOTAL_CELL, QuantityFormatter.quantityToStackSize(lootGp), SpyglassTheme.ACCENT_GOLD, "This task's loot");
			Long perKill = actualKills == null || actualKills <= 0 ? null : Math.round(lootGp / (double) actualKills);
			loot.addCell(LOOT_PER_KILL_CELL, perKill == null ? null : fmt(perKill) + " gp", SpyglassTheme.ACCENT_GOLD,
				"Per ACTUAL kill (every NPC killed), not per task count");
			body.add(loot);
		}
		if (total(superiors) > 0)
		{
			body.add(Box.createVerticalStrut(4));
			body.add(caption("Superiors"));
			for (java.util.Map.Entry<String, Long> e : sorted(superiors))
			{
				body.add(row(e.getKey(), fmt(e.getValue()), DETAIL_SUPERIOR_PREFIX + e.getKey()));
			}
		}
		if (breakdownComplete && breakdown != null && breakdown.size() > 1)
		{
			body.add(Box.createVerticalStrut(4));
			body.add(caption("Kill breakdown"));
			for (java.util.Map.Entry<String, Long> e : sorted(breakdown))
			{
				body.add(row(e.getKey(), fmt(e.getValue()), DETAIL_BREAKDOWN_PREFIX + e.getKey()));
			}
			body.add(row("Total", fmt(total(breakdown)), DETAIL_BREAKDOWN_TOTAL));
		}
		return body.getComponentCount() == 0 ? null : body;
	}

	private static List<java.util.Map.Entry<String, Long>> sorted(java.util.Map<String, Long> counts)
	{
		List<java.util.Map.Entry<String, Long>> list = new java.util.ArrayList<>(counts.entrySet());
		list.sort((a, b) -> Long.compare(b.getValue() == null ? 0L : b.getValue(), a.getValue() == null ? 0L : a.getValue()));
		list.removeIf(e -> e.getValue() == null || e.getValue() <= 0);
		return list;
	}

	private static JLabel caption(String text)
	{
		JLabel l = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
		l.setFont(SpyglassFonts.small());
		l.setForeground(SpyglassTheme.TEXT_MUTED);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** "name ........ value": the name may shrink (truncated by the label), the value never leaves the row. */
	private static JPanel row(String label, String value, String name)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setName(name);
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel l = new JLabel(label);
		l.setFont(SpyglassFonts.small());
		l.setForeground(SpyglassTheme.TEXT_SECONDARY);
		l.setMinimumSize(new Dimension(0, l.getPreferredSize().height));
		l.setToolTipText(label);
		row.add(l, BorderLayout.CENTER);
		JLabel v = new JLabel(value);
		v.setFont(SpyglassFonts.small());
		v.setForeground(SpyglassTheme.TEXT_PRIMARY);
		row.add(v, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static String fmt(long v)
	{
		return String.format(java.util.Locale.ROOT, "%,d", v);
	}

	/**
	 * {@link #card} with compact sub-lines under the header (see {@link #subLines}) and, when expanded, a
	 * lead section ({@link #details}) before the content (each a direct child of the card).
	 */
	static JPanel card(String name, boolean expanded, String title, String tooltip, Color titleColor,
		long valueGp, Runnable onToggle, JPanel expandedLead, JPanel expandedContent, List<String> subLines)
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
		titleLabel.setFont(SpyglassFonts.small());
		titleLabel.setForeground(titleColor);
		titleLabel.setToolTipText(tooltip);
		titleLabel.setMinimumSize(new Dimension(0, titleLabel.getPreferredSize().height));
		header.add(titleLabel, BorderLayout.CENTER);
		JLabel value = new JLabel(QuantityFormatter.quantityToStackSize(valueGp) + " gp");
		value.setFont(SpyglassFonts.small());
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
		for (int i = 0; i < subLines.size(); i++)
		{
			JLabel line = new JLabel(subLines.get(i));
			line.setName(SUB_LINE_PREFIX + i);
			line.setFont(SpyglassFonts.small());
			line.setForeground(SpyglassTheme.TEXT_SECONDARY);
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			line.setMinimumSize(new Dimension(0, line.getPreferredSize().height));
			line.addMouseListener(toggle);
			// Indented under the title text (past the chevron).
			line.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
			card.add(line);
		}

		if (expandedLead != null)
		{
			card.add(Box.createVerticalStrut(4));
			card.add(expandedLead);
		}
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
