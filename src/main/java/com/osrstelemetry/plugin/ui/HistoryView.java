package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.Session;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;

/**
 * The top-level component wired into {@code OsrsTelemetryPanel}'s own
 * Session/Loot/History tab row as the single "History" card -- owns its
 * OWN internal {@link CardLayout} switching between a recent-sessions
 * LIST card and a {@link HistoryDetailView} DETAIL card, so
 * {@code OsrsTelemetryPanel} itself never needs to know History has any
 * internal navigation at all (it only ever sees one component, exactly
 * like {@code CurrentSessionView}/{@code LootTrackerView}).
 *
 * ARCHITECTURE (matches CurrentSessionView's own established pattern):
 * this class has NO reference to {@code HistoryCoordinator} -- it is
 * handed already-built {@link HistoryEntry} summaries via {@link #render}
 * (called on every poll tick, same cadence and off-EDT-safe cheap read
 * as every other tab -- see {@code HistoryCoordinator#getRecentEntries()}'s
 * own javadoc) and reports "the player opened this session" purely via
 * the constructor-supplied {@code onEntryOpened} callback, exactly the
 * same opaque-Runnable/Consumer shape {@code CurrentSessionView}'s
 * "Re-evaluate Session" button already uses. {@code OsrsTelemetryPanel}
 * is the only class that actually calls
 * {@code HistoryCoordinator.loadDetail(...)}, and it does so entirely
 * off this class's own knowledge -- see that class's own wiring.
 *
 * LAZY DETAIL LOADING: clicking a row does NOT eagerly
 * pre-fetch anything -- it calls {@code onEntryOpened} once, which
 * triggers exactly one background {@code loadDetail()} call in
 * {@code OsrsTelemetryPanel}. {@link #showDetailLoading} is shown
 * immediately (so the click feels responsive even though the real
 * detail read is asynchronous), then {@link #showDetail}/
 * {@link #showDetailUnavailable} replaces it once that background read
 * completes and is marshalled back onto the EDT by the caller.
 *
 * RENDER-KEY SKIP (list card only -- mirrors LootTrackerView's own
 * mechanism): a poll tick whose entry set is identical to what is
 * already on screen (same session ids, same order) is a pure string
 * comparison, no Swing tree rebuild.
 *
 * NO OWN JScrollPane -- see CurrentSessionView/LootTrackerView's own
 * SCROLLING notes; RuneLite's outer sidebar scrollpane does all
 * scrolling for both the list and detail cards here too.
 *
 * VISUAL DIRECTION: the list card is a flat, dark
 * {@link SpyglassTheme#BACKGROUND_MAIN} background, matching
 * {@link OsrsTelemetryPanel}'s dark-bronze tab header above it. Row
 * chrome, text colors, and hover feedback all draw from the same
 * shared {@link SpyglassTheme} palette (a warm
 * {@link SpyglassTheme#SURFACE_CARD} card face, {@link SpyglassTheme#BORDER_SOFT}
 * border, {@link SpyglassTheme#TEXT_PRIMARY}/{@link SpyglassTheme#TEXT_SECONDARY}
 * text, lifting to {@link SpyglassTheme#SURFACE_RAISED}/{@link SpyglassTheme#BORDER_PRIMARY}
 * on hover).
 *
 * VERTICAL STRETCH: {@link #buildRow} deliberately does NOT declare an
 * unbounded {@code Short.MAX_VALUE} maximum height -- that pattern is
 * what causes a BoxLayout row to stretch once its CardLayout card
 * becomes the tallest of the three tabs (see LootTrackerView's own
 * {@code heightBoundedPanel} javadoc for the full root-cause writeup);
 * {@link #heightBoundedPanel} is the guard against it.
 */
final class HistoryView extends JPanel
{
	private static final String CARD_LIST = "list";
	private static final String CARD_DETAIL = "detail";

	private final Consumer<String> onEntryOpened;

	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final JPanel listContent = new JPanel();
	private final HistoryDetailView detailView;

	private String lastRenderKey;

	HistoryView(SkillIconManager skillIconManager, ItemManager itemManager, ClientThread clientThread, OsrsTelemetryConfig config, Consumer<String> onEntryOpened)
	{
		this.onEntryOpened = onEntryOpened;

		setLayout(new BorderLayout());
		setBackground(SpyglassTheme.BACKGROUND_MAIN);

		listContent.setLayout(new BoxLayout(listContent, BoxLayout.Y_AXIS));
		listContent.setBackground(SpyglassTheme.BACKGROUND_MAIN);
		listContent.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		this.detailView = new HistoryDetailView(skillIconManager, itemManager, clientThread, config, () -> cardLayout.show(cards, CARD_LIST));

		cards.add(listContent, CARD_LIST);
		cards.add(detailView, CARD_DETAIL);
		add(cards, BorderLayout.CENTER);

		render(java.util.Collections.emptyList());
	}

	/** Called on every poll tick (see class javadoc) -- cheap, off-EDT-free, skips rebuilding when nothing changed. */
	void render(List<HistoryEntry> entries)
	{
		String key = computeRenderKey(entries);
		if (key.equals(lastRenderKey))
		{
			return;
		}
		lastRenderKey = key;

		listContent.removeAll();

		if (entries.isEmpty())
		{
			renderEmptyState();
		}
		else
		{
			for (HistoryEntry entry : entries)
			{
				listContent.add(buildRow(entry));
				listContent.add(Box.createVerticalStrut(6));
			}
		}

		listContent.revalidate();
		listContent.repaint();
	}

	/** Package-private, pure, testable -- see LootTrackerView.computeRenderKey()'s own javadoc for why this pattern exists. */
	static String computeRenderKey(List<HistoryEntry> entries)
	{
		StringBuilder key = new StringBuilder(entries.size() * 24);
		for (HistoryEntry entry : entries)
		{
			key.append(entry.getSessionId()).append('|').append(entry.getFinalizedAt()).append('\n');
		}
		return key.toString();
	}

	/** Immediately switches to the detail card showing a loading placeholder -- see class javadoc's LAZY DETAIL LOADING note. */
	void showDetailLoading()
	{
		detailView.showLoading();
		cardLayout.show(cards, CARD_DETAIL);
	}

	/** Called once OsrsTelemetryPanel's background loadDetail() call completes with a real Session. */
	void showDetail(Session session)
	{
		HistoryDetailModel model = HistoryDetailModel.from(session);
		if (model == null)
		{
			showDetailUnavailable();
			return;
		}
		detailView.render(model);
		cardLayout.show(cards, CARD_DETAIL);
	}

	/** Called when loadDetail() failed or returned a malformed/missing session -- see HistoryDetailModel.from()'s fail-open contract. */
	void showDetailUnavailable()
	{
		detailView.showUnavailable();
		cardLayout.show(cards, CARD_DETAIL);
	}

	private void renderEmptyState()
	{
		JLabel title = new JLabel("No completed sessions yet");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(SpyglassTheme.TEXT_PRIMARY);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel subtitle = new JLabel("<html><div style='text-align:center;width:180px;'>"
			+ "Finalized sessions from the last 10 days will appear here.</div></html>");
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(SpyglassTheme.TEXT_SECONDARY);
		subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

		listContent.add(Box.createVerticalStrut(24));
		listContent.add(title);
		listContent.add(Box.createVerticalStrut(6));
		listContent.add(subtitle);
	}

	/**
	 * See this class's own javadoc VERTICAL STRETCH note and
	 * LootTrackerView's {@code heightBoundedPanel()} for the full
	 * root-cause writeup. A plain {@code JPanel} whose maximum height always equals its own
	 * current preferred height, so a BoxLayout parent with more height
	 * to give away than this row actually needs can never stretch it.
	 *
	 * Package-private (not {@code private}) so {@code HistoryViewTest}
	 * can directly regression-test its dynamic-height behavior -- same
	 * convention {@code LootTrackerViewTest} already establishes for its
	 * own equivalent factory.
	 */
	static JPanel heightBoundedPanel(LayoutManager layout)
	{
		return new JPanel(layout)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
	}

	private JPanel buildRow(HistoryEntry entry)
	{
		JPanel row = heightBoundedPanel(new BorderLayout(6, 2));
		row.setOpaque(true);
		row.setBackground(SpyglassTheme.SURFACE_CARD);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(rowBorder(SpyglassTheme.BORDER_SOFT));
		row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		ActivityIdentity identity = entry.getActivityIdentity();
		String headline = identity == null || identity.getDisplayName() == null ? "Unknown activity" : identity.getDisplayName();

		JPanel textColumn = new JPanel();
		textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
		textColumn.setOpaque(false);

		JLabel headlineLabel = new JLabel(headline);
		headlineLabel.setFont(FontManager.getRunescapeBoldFont());
		headlineLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		headlineLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		textColumn.add(headlineLabel);

		StringBuilder secondary = new StringBuilder();
		String when = formatRelativeOrAbsolute(entry.getFinalizedAt());
		if (when != null)
		{
			secondary.append(when);
		}
		secondary.append("   •   ").append(formatDuration(entry.getAccumulatedActiveDurationMillis()));
		if (entry.getTotalXpGained() > 0)
		{
			secondary.append("   •   ").append(formatNumber(entry.getTotalXpGained())).append(" XP");
		}
		if (entry.getLootDropCount() > 0)
		{
			secondary.append("   •   ").append(entry.getLootDropCount()).append(entry.getLootDropCount() == 1 ? " drop" : " drops");
		}
		JLabel secondaryLabel = new JLabel(secondary.toString());
		secondaryLabel.setFont(FontManager.getRunescapeSmallFont());
		secondaryLabel.setForeground(SpyglassTheme.TEXT_SECONDARY);
		secondaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		textColumn.add(secondaryLabel);

		row.add(textColumn, BorderLayout.CENTER);

		JLabel chevron = new JLabel("›");
		chevron.setFont(FontManager.getRunescapeBoldFont());
		chevron.setForeground(SpyglassTheme.TEXT_MUTED);
		row.add(chevron, BorderLayout.EAST);

		String sessionId = entry.getSessionId();
		row.addMouseListener(new ClickListener(() ->
		{
			showDetailLoading();
			if (onEntryOpened != null)
			{
				onEntryOpened.accept(sessionId);
			}
		}));

		// Raised-surface hover feedback (background AND border lift
		// together) while the cursor is over a row, reverting to the
		// row's normal card colors on exit. A second, separate
		// MouseListener on the same row -- purely
		// visual, does not affect the click behavior registered above.
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(SpyglassTheme.SURFACE_RAISED);
				row.setBorder(rowBorder(SpyglassTheme.BORDER_PRIMARY));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(SpyglassTheme.SURFACE_CARD);
				row.setBorder(rowBorder(SpyglassTheme.BORDER_SOFT));
			}
		});

		return row;
	}

	/** Small shared helper -- the row card's compound border (colored line + inner padding), reused for both its normal and hover states (see {@link #buildRow}) so the two never drift out of sync with each other. */
	private static javax.swing.border.Border rowBorder(java.awt.Color lineColor)
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(lineColor),
			BorderFactory.createEmptyBorder(6, 8, 6, 8));
	}

	private static String formatNumber(long value)
	{
		return String.format("%,d", value);
	}

	private static String formatDuration(long millis)
	{
		long totalSeconds = millis / 1000L;
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		if (hours > 0)
		{
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%d:%02d", minutes, seconds);
	}

	/** Best-effort; returns null (never throws) on anything unparseable so a malformed timestamp never blanks out the row. */
	private static String formatRelativeOrAbsolute(String isoInstant)
	{
		if (isoInstant == null)
		{
			return null;
		}
		try
		{
			Instant instant = Instant.parse(isoInstant);
			return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withZone(ZoneId.systemDefault())
				.format(instant);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Mirrors LootTrackerView's own private ClickListener -- see that class's javadoc. */
	private static final class ClickListener extends MouseAdapter
	{
		private final Runnable action;

		ClickListener(Runnable action)
		{
			this.action = action;
		}

		@Override
		public void mouseClicked(MouseEvent e)
		{
			action.run();
		}
	}
}
