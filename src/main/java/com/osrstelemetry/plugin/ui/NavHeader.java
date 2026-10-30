package com.osrstelemetry.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The panel's fixed header, outside the scrolling content so it never
 * scrolls away: a band holding the nav row
 * {@code [house][Session][Loot][History]} and, on a child page, a sub-header
 * breadcrumb such as {@code ‹ Home · Farming} or {@code ‹ History · Session}.
 *
 * The nav controls paint themselves (face, rim, lit edge, text) so every
 * state reads the same under RuneLite's look and feel: rest, hover, pressed
 * (seated 1 px), selected (raised, warm face, gold label) and keyboard focus
 * (bronze rim). The house is a peer destination, selected whenever the
 * section is Home (including inside Farming). Its icon is a pixel image drawn
 * here at class load, in pre-tinted copies, so it never depends on a font
 * glyph and nothing is recoloured per paint.
 *
 * Purely presentation: it reports clicks and is told the route to show; the
 * navigation rules live in {@link Navigator}.
 */
final class NavHeader extends JPanel
{
	static final String HOME_TOOLTIP = "Home";
	static final int HOUSE_CELL_WIDTH = 30;
	private static final int ROW_HEIGHT = 26;
	private static final int GAP = 3;

	private static final String[] HOUSE_MASK = {
		"......##......",
		".....####.....",
		"....######....",
		"...########...",
		"..##########..",
		".############.",
		"##############",
		"..##########..",
		"..##########..",
		"..####..####..",
		"..####..####..",
		"..####..####..",
		"..####..####..",
	};
	private static final BufferedImage HOUSE_IDLE = mask(HOUSE_MASK, SpyglassTheme.TEXT_SECONDARY);
	private static final BufferedImage HOUSE_HOVER = mask(HOUSE_MASK, SpyglassTheme.TEXT_PRIMARY);
	private static final BufferedImage HOUSE_SELECTED = mask(HOUSE_MASK, SpyglassTheme.ACCENT_GOLD);

	private static final String[] BACK_MASK = {
		"...##",
		"..##.",
		".##..",
		"##...",
		".##..",
		"..##.",
		"...##",
	};

	private final NavButton houseButton;
	private final NavButton sessionButton;
	private final NavButton lootButton;
	private final NavButton historyButton;
	private final JPanel subHeader;
	private final BackLink backLink;
	private final WrappingHtmlLabel subHeaderTitle;
	private final GearButton gearButton;
	private Runnable onGear;

	NavHeader(Consumer<NavRoute.Section> onSection, Runnable onUp)
	{
		super(new BorderLayout());
		setBackground(SpyglassTheme.NAV_BAND);

		houseButton = new NavButton(null, NavRoute.Section.HOME, onSection);
		houseButton.setToolTipText(HOME_TOOLTIP);
		houseButton.getAccessibleContext().setAccessibleName(HOME_TOOLTIP);
		sessionButton = new NavButton("Session", NavRoute.Section.SESSION, onSection);
		lootButton = new NavButton("Loot", NavRoute.Section.LOOT, onSection);
		historyButton = new NavButton("History", NavRoute.Section.HISTORY, onSection);

		// House fixed width, the three text tabs share the rest equally.
		JPanel tabs = new JPanel(new GridLayout(1, 3, GAP, 0));
		tabs.setOpaque(false);
		tabs.add(sessionButton);
		tabs.add(lootButton);
		tabs.add(historyButton);

		JPanel row = new JPanel(new BorderLayout(GAP, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.TILE_RIM),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));
		row.add(houseButton, BorderLayout.WEST);
		row.add(tabs, BorderLayout.CENTER);
		add(row, BorderLayout.NORTH);

		subHeader = new JPanel(new BorderLayout(4, 0));
		subHeader.setBackground(SpyglassTheme.NAV_SUBHEADER);
		subHeader.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, SpyglassTheme.TILE_RIM),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		// "‹ Home  ·  Dailies & Weeklies": the title wraps under itself at a large text size rather than running off the edge.
		JPanel trail = new JPanel(new BorderLayout(0, 0));
		trail.setOpaque(false);
		backLink = new BackLink(onUp);
		JPanel backCell = new JPanel(new BorderLayout());
		backCell.setOpaque(false);
		backCell.add(backLink, BorderLayout.NORTH);
		trail.add(backCell, BorderLayout.WEST);
		JLabel dot = new JLabel("  ·  ");
		dot.setFont(SpyglassFonts.regular());
		dot.setForeground(SpyglassTheme.TEXT_SECONDARY);
		JPanel dotCell = new JPanel(new BorderLayout());
		dotCell.setOpaque(false);
		dotCell.add(dot, BorderLayout.NORTH);
		subHeaderTitle = new WrappingHtmlLabel(" ", false);
		subHeaderTitle.setFont(SpyglassFonts.regular());
		subHeaderTitle.setForeground(SpyglassTheme.TEXT_PRIMARY);
		JPanel titleCell = new JPanel(new BorderLayout());
		titleCell.setOpaque(false);
		titleCell.add(dotCell, BorderLayout.WEST);
		titleCell.add(subHeaderTitle, BorderLayout.CENTER);
		trail.add(titleCell, BorderLayout.CENTER);
		subHeader.add(trail, BorderLayout.CENTER);
		gearButton = new GearButton(() ->
		{
			if (onGear != null)
			{
				onGear.run();
			}
		});
		gearButton.setVisible(false);
		subHeader.add(gearButton, BorderLayout.EAST);
		subHeader.setVisible(false);
		add(subHeader, BorderLayout.CENTER);

		showRoute(NavRoute.HOME);
	}

	/** The page title a child route's sub-header shows, or null when it has no sub-header. */
	static String subHeaderTitle(NavRoute route)
	{
		if (route == null || route.isRoot())
		{
			return null;
		}
		if (route.getSection() == NavRoute.Section.HOME)
		{
			switch (route.getChild())
			{
				case NavRoute.FARMING:
					return "Farming";
				case NavRoute.FARMING_SETTINGS:
					return "Farming settings";
				case NavRoute.KINGDOM:
					return "Kingdom";
				case NavRoute.DAILIES:
					return "Dailies & Weeklies";
				case NavRoute.BIRDHOUSES:
					return "Bird houses";
				case NavRoute.APPEARANCE:
					return "Text size & contrast";
				default:
					return null;
			}
		}
		if (route.getSection() == NavRoute.Section.HISTORY)
		{
			return "Session";
		}
		return null;
	}

	/** The parent a child route's "‹" goes up to. */
	static String parentLabel(NavRoute route)
	{
		if (route != null && route.getSection() == NavRoute.Section.HOME && NavRoute.FARMING_SETTINGS.equals(route.getChild()))
		{
			return "Farming";
		}
		return route != null && route.getSection() == NavRoute.Section.HISTORY ? "History" : "Home";
	}

	/** Farming's own pages carry the settings gear at the right of the sub-header (selected while settings are open). */
	static boolean showsGear(NavRoute route)
	{
		return route != null && route.getSection() == NavRoute.Section.HOME
			&& (NavRoute.FARMING.equals(route.getChild()) || NavRoute.FARMING_SETTINGS.equals(route.getChild()));
	}

	/** What the Farming settings gear does (open settings, or close them when open). */
	void setOnGear(Runnable onGear)
	{
		this.onGear = onGear;
	}

	JButton gearButton()
	{
		return gearButton;
	}

	void showRoute(NavRoute route)
	{
		boolean gear = showsGear(route);
		gearButton.setVisible(gear);
		gearButton.setSelected(gear && NavRoute.FARMING_SETTINGS.equals(route.getChild()));
		NavRoute.Section section = route.getSection();
		houseButton.setSelected(section == NavRoute.Section.HOME);
		sessionButton.setSelected(section == NavRoute.Section.SESSION);
		lootButton.setSelected(section == NavRoute.Section.LOOT);
		historyButton.setSelected(section == NavRoute.Section.HISTORY);

		String title = subHeaderTitle(route);
		boolean visible = title != null;
		if (visible)
		{
			subHeaderTitle.setPlainText(title);
			backLink.setParentLabel(parentLabel(route));
		}
		if (subHeader.isVisible() != visible)
		{
			subHeader.setVisible(visible);
			revalidate();
			repaint();
		}
		else if (visible)
		{
			subHeader.revalidate();
			subHeader.repaint();
		}
	}

	boolean isSubHeaderVisible()
	{
		return subHeader.isVisible();
	}

	/** Text rendered with the desktop's own text hints, exactly as a Swing label would be. */
	private static void applyTextHints(Graphics2D g)
	{
		Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
		if (hints instanceof Map)
		{
			g.addRenderingHints((Map<?, ?>) hints);
		}
		else
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		}
	}

	/** One nav control. Painted entirely here; the look and feel only supplies events. */
	private static final class NavButton extends JButton
	{
		private final NavRoute.Section section;
		private boolean navSelected;

		NavButton(String text, NavRoute.Section section, Consumer<NavRoute.Section> onSection)
		{
			super(text);
			this.section = section;
			setFont(SpyglassFonts.regular());
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setMargin(new Insets(0, 0, 0, 0));
			setBorder(BorderFactory.createEmptyBorder());
			addActionListener(e -> onSection.accept(section));
		}

		/** The row's height follows the text size (pass 30); the house cell keeps its width. */
		@Override
		public Dimension getPreferredSize()
		{
			int h = Math.max(ROW_HEIGHT, getFontMetrics(getFont()).getHeight() + 8);
			return new Dimension(section == NavRoute.Section.HOME ? HOUSE_CELL_WIDTH : 10, h);
		}

		@Override
		public void setSelected(boolean selected)
		{
			if (navSelected != selected)
			{
				navSelected = selected;
				repaint();
			}
		}

		@Override
		public boolean isSelected()
		{
			return navSelected;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				ButtonModel m = getModel();
				boolean pressed = m.isArmed() && m.isPressed();
				boolean hover = m.isRollover();
				int w = getWidth();
				int h = getHeight();
				int shift = pressed ? 1 : 0;

				Color face = pressed ? SpyglassTheme.NAV_FACE_PRESSED
					: navSelected ? SpyglassTheme.NAV_FACE_SELECTED
					: hover ? SpyglassTheme.NAV_FACE_HOVER : SpyglassTheme.NAV_FACE;
				g.setColor(face);
				g.fillRect(1, 1, w - 2, h - 2);
				g.setColor(isFocusOwner() ? SpyglassTheme.ACCENT_BRONZE
					: navSelected ? SpyglassTheme.BORDER_PRIMARY : SpyglassTheme.TILE_RIM);
				g.drawRect(0, 0, w - 1, h - 1);
				if (!pressed)
				{
					// Lit upper edge: brighter on the selected (raised) control.
					g.setColor(navSelected ? SpyglassTheme.TILE_EDGE_HIGHLIGHT_HOVER
						: hover ? SpyglassTheme.TILE_EDGE_HIGHLIGHT : SpyglassTheme.TILE_EDGE_SHADE);
					g.fillRect(1, 1, w - 2, 1);
				}

				if (section == NavRoute.Section.HOME)
				{
					BufferedImage icon = navSelected ? HOUSE_SELECTED : hover ? HOUSE_HOVER : HOUSE_IDLE;
					int x = (w - icon.getWidth()) / 2 + shift;
					int y = (h - icon.getHeight()) / 2 + shift;
					g.drawImage(icon, x, y, null);
					return;
				}
				applyTextHints(g);
				// The tab's name at the text size when it fits its third of the row; otherwise the largest size that does (the
				// three tabs share a fixed width, and a name is never cut short).
				java.awt.Font font = getFont();
				while (font.getSize2D() > 12f && g.getFontMetrics(font).stringWidth(getText()) > w - 4)
				{
					font = font.deriveFont(font.getSize2D() - 1f);
				}
				g.setFont(font);
				g.setColor(navSelected ? SpyglassTheme.ACCENT_GOLD : hover ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY);
				FontMetrics fm = g.getFontMetrics();
				String text = getText();
				int textWidth = fm.stringWidth(text);
				int x = (w - textWidth) / 2 + shift;
				int y = (h - fm.getHeight()) / 2 + fm.getAscent() + shift;
				g.drawString(text, x, y);
			}
			finally
			{
				g.dispose();
			}
		}
	}

	/** "‹ Home": a pixel chevron plus the parent's name; cream on hover, bronze rim when focused. */
	private static final class BackLink extends JButton
	{
		private static final int CHEVRON_GAP = 5;

		BackLink(Runnable onUp)
		{
			super("Home");
			setFont(SpyglassFonts.regular());
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setBorder(BorderFactory.createEmptyBorder());
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addActionListener(e -> onUp.run());
			setParentLabel(getText());
		}

		void setParentLabel(String label)
		{
			setText(label);
			getAccessibleContext().setAccessibleName("Back to " + label);
			revalidate();
		}

		/** Sized by its label at the current text size (pass 30: a size change never cuts it short). */
		@Override
		public Dimension getPreferredSize()
		{
			FontMetrics fm = getFontMetrics(getFont());
			return new Dimension(BACK_MASK[0].length() + CHEVRON_GAP + fm.stringWidth(getText()) + 4, fm.getHeight() + 2);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				ButtonModel m = getModel();
				boolean hover = m.isRollover() || (m.isArmed() && m.isPressed());
				Color color = hover ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY;
				FontMetrics fm = g.getFontMetrics(getFont());
				int h = getHeight();
				int cy = (h - BACK_MASK.length) / 2;
				g.setColor(color);
				for (int y = 0; y < BACK_MASK.length; y++)
				{
					for (int x = 0; x < BACK_MASK[y].length(); x++)
					{
						if (BACK_MASK[y].charAt(x) == '#')
						{
							g.fillRect(2 + x, cy + y, 1, 1);
						}
					}
				}
				applyTextHints(g);
				g.setFont(getFont());
				g.drawString(getText(), 2 + BACK_MASK[0].length() + CHEVRON_GAP, (h - fm.getHeight()) / 2 + fm.getAscent());
				if (isFocusOwner())
				{
					g.setColor(SpyglassTheme.ACCENT_BRONZE);
					g.drawRect(0, 0, getWidth() - 1, h - 1);
				}
			}
			finally
			{
				g.dispose();
			}
		}
	}

	private static final String[] GEAR_MASK = {
		".....###.....",
		"..#..###..#..",
		".###########.",
		"..#########..",
		"..###...###..",
		"####.....####",
		"####.....####",
		"####.....####",
		"..###...###..",
		"..#########..",
		".###########.",
		"..#..###..#..",
		".....###.....",
	};
	private static final BufferedImage GEAR_IDLE = mask(GEAR_MASK, SpyglassTheme.TEXT_SECONDARY);
	private static final BufferedImage GEAR_HOVER = mask(GEAR_MASK, SpyglassTheme.TEXT_PRIMARY);
	private static final BufferedImage GEAR_SELECTED = mask(GEAR_MASK, SpyglassTheme.ACCENT_GOLD);
	static final String GEAR_TOOLTIP = "Farming settings";

	/** The Farming settings gear: a pixel gear (no font glyph), cream on hover, gold while settings are open, bronze focus rim. */
	private static final class GearButton extends JButton
	{
		private boolean selected;

		GearButton(Runnable onPress)
		{
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setBorder(BorderFactory.createEmptyBorder());
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(GEAR_MASK[0].length() + 8, GEAR_MASK.length + 4));
			addActionListener(e -> onPress.run());
			setSelected(false);
		}

		@Override
		public void setSelected(boolean selected)
		{
			this.selected = selected;
			setToolTipText(selected ? "Close Farming settings" : GEAR_TOOLTIP);
			getAccessibleContext().setAccessibleName(selected ? "Close Farming settings" : "Open Farming settings");
			repaint();
		}

		@Override
		public boolean isSelected()
		{
			return selected;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				ButtonModel m = getModel();
				boolean hover = m.isRollover() || (m.isArmed() && m.isPressed());
				BufferedImage img = selected ? GEAR_SELECTED : hover ? GEAR_HOVER : GEAR_IDLE;
				int x = (getWidth() - img.getWidth()) / 2;
				int y = (getHeight() - img.getHeight()) / 2 + (m.isArmed() && m.isPressed() ? 1 : 0);
				g.drawImage(img, x, y, null);
				if (isFocusOwner())
				{
					g.setColor(SpyglassTheme.ACCENT_BRONZE);
					g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
				}
			}
			finally
			{
				g.dispose();
			}
		}
	}

	private static BufferedImage mask(String[] rows, Color color)
	{
		BufferedImage image = new BufferedImage(rows[0].length(), rows.length, BufferedImage.TYPE_INT_ARGB);
		int rgb = color.getRGB();
		for (int y = 0; y < rows.length; y++)
		{
			for (int x = 0; x < rows[y].length(); x++)
			{
				if (rows[y].charAt(x) == '#')
				{
					image.setRGB(x, y, rgb);
				}
			}
		}
		return image;
	}
}
