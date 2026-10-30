package com.osrstelemetry.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * The Home dashboard: Raised Tiles floating on a recessed canvas, in a fixed order -- Farming, Bird houses, Kingdom,
 * Dailies &amp; Weeklies, Notes, PvP -- never reordered by urgency.
 *
 * Each card shares one construction: a real item sprite sitting on the face
 * at the left, a title, and one quiet line saying what the card is for.
 *
 * Farming shows the Farming-owned {@code HomeSummary} (headline, up to two
 * facts, a gold or ember rail); Bird houses, Kingdom and Dailies &amp; Weeklies show what needs attention
 * from {@link HomeText} (the game's own readings; a calculated value with its reading's age).
 * Farming, Bird houses, Kingdom and Dailies &amp; Weeklies each open their page; Notes and PvP are status tiles (PvP: coming soon).
 * Under the stack, the Spyglass Discord invite (RuneLite's own link handling).
 *
 * Sprites (each checked against the actual game sprite, 36×32):
 * Farming = Ranarr weed (257), Bird houses = Bird house (21512), Kingdom = Royal crown (12397),
 * Dailies = Explorer's ring 4 (13128), Notes = Scroll (7968).
 *
 * Only the Farming card's text changes after construction ({@link #renderFarming}). The sprites hide below {@link #ICON_MIN_WIDTH} so the text keeps
 * its room.
 */
final class HomeView extends JPanel
{
	/** Below this content width the sprite is dropped. */
	static final int ICON_MIN_WIDTH = 190;
	/** Canvas margin around the stack, on top of the panel's own gutter. */
	private static final int MARGIN = 2;
	/** Space between cards; the 2 px shadow sits inside it. */
	private static final int CARD_GAP = 8;
	private static final int ICON_TEXT_GAP = 6;

	static final int FARMING_ITEM = ItemID.RANARR_WEED;
	static final int BIRDHOUSE_ITEM = ItemID.BIRDHOUSE_NORMAL;
	static final int KINGDOM_ITEM = ItemID.ROYAL_CROWN;
	static final int DAILIES_ITEM = ItemID.LUMBRIDGE_RING_ELITE;
	static final int NOTES_ITEM = ItemID.ROYAL_OFFICIAL_SCROLL;
	/** Mysterious emblem (Bounty Hunter): PvP's placeholder tile. */
	static final int PVP_ITEM = ItemID.BH_EMBLEM;

	static final String FARMING_TITLE = "Farming";
	static final String BIRDHOUSE_TITLE = "Bird houses";
	static final String KINGDOM_TITLE = "Kingdom";
	static final String DAILIES_TITLE = "Dailies & Weeklies";
	static final String NOTES_TITLE = "Notes";
	static final String PVP_TITLE = "PvP";
	/** A placeholder only: nothing about PvP is tracked yet. */
	static final String PVP_PURPOSE = "Coming soon";

	static final String FARMING_PURPOSE = "Patch timers & farm runs";
	static final String KINGDOM_PURPOSE = "Coffer & approval";
	static final String BIRDHOUSE_PURPOSE = "Fossil Island timers & runs";
	static final String DAILIES_PURPOSE = "Daily claims & weekly activities";
	static final String NOTES_PURPOSE = "Your own reminders";
	/** The approved, permanent Spyglass Discord invite. */
	static final String DISCORD_INVITE = "https://discord.gg/tgdNjt6Xjp";
	static final String DISCORD_LABEL = "Join Spyglass Discord";

	private final List<Component> icons = new ArrayList<>();
	private boolean iconsShown = true;
	private final RaisedTile farmingCard;
	// Every line on a card wraps (pass 30: larger text sizes): nothing is cut short at the sidebar's width.
	private final JLabel farmingHeadline = new WrappingHtmlLabel(FARMING_PURPOSE, false);
	private final JLabel[] farmingFacts = {new WrappingHtmlLabel(" ", false), new WrappingHtmlLabel(" ", false)};
	private final RaisedTile birdhouseCard;
	private final JLabel birdhouseHeadline = new WrappingHtmlLabel(BIRDHOUSE_PURPOSE, false);
	private final JLabel[] birdhouseFacts = {new WrappingHtmlLabel(" ", false), new WrappingHtmlLabel(" ", false)};
	private final RaisedTile kingdomCard;
	private final JLabel kingdomHeadline = new WrappingHtmlLabel(KINGDOM_PURPOSE, false);
	private final JLabel[] kingdomFacts = {new WrappingHtmlLabel(" ", false), new WrappingHtmlLabel(" ", false)};
	private final RaisedTile dailiesCard;
	private final JLabel dailiesHeadline = new WrappingHtmlLabel(DAILIES_PURPOSE, false);
	private final JLabel[] dailiesFacts = {new WrappingHtmlLabel(" ", false), new WrappingHtmlLabel(" ", false)};
	/** The ready activities' icons on the Dailies &amp; Weeklies card (pass 31). */
	private final JPanel dailiesBadges = new JPanel(new ReflowGridLayout(8, 2, 2));
	private List<HomeText.Badge> shownBadges = new ArrayList<>();
	private java.util.function.Consumer<String> onOpenDaily = key -> { };
	private FarmingView.SpriteSource badgeSprites;
	private final ActionButton discordButton;
	private final ActionButton appearanceButton;
	private Runnable onAppearance = () -> { };
	static final String APPEARANCE_LABEL = "Text size & contrast";
	/** Opens a URL (RuneLite's LinkBrowser; a recorder in tests). */
	java.util.function.Consumer<String> browser = net.runelite.client.util.LinkBrowser::browse;

	HomeView(ItemManager itemManager, Runnable onOpenFarming)
	{
		this(itemManager, onOpenFarming, null, null);
	}

	HomeView(ItemManager itemManager, Runnable onOpenFarming, Runnable onOpenKingdom, Runnable onOpenDailies)
	{
		this(itemManager, onOpenFarming, onOpenKingdom, onOpenDailies, null);
	}

	HomeView(ItemManager itemManager, Runnable onOpenFarming, Runnable onOpenKingdom, Runnable onOpenDailies, Runnable onOpenBirdhouses)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(SpyglassTheme.TILE_CANVAS);
		setBorder(BorderFactory.createEmptyBorder(8, MARGIN, 8, MARGIN));

		farmingHeadline.setFont(SpyglassFonts.small());
		farmingHeadline.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
		for (JLabel fact : farmingFacts)
		{
			fact.setFont(SpyglassFonts.small());
			fact.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
			fact.setVisible(false);
		}
		farmingCard = tile(itemManager, FARMING_ITEM, FARMING_TITLE, onOpenFarming, farmingHeadline, farmingFacts[0], farmingFacts[1]);
		icons.add(((BorderLayout) farmingCard.getLayout()).getLayoutComponent(BorderLayout.WEST));
		add(farmingCard);
		add(Box.createVerticalStrut(CARD_GAP));
		for (JLabel l : new JLabel[]{birdhouseHeadline, birdhouseFacts[0], birdhouseFacts[1], kingdomHeadline, kingdomFacts[0], kingdomFacts[1],
			dailiesHeadline, dailiesFacts[0], dailiesFacts[1]})
		{
			l.setFont(SpyglassFonts.small());
			l.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
		}
		for (JLabel l : new JLabel[]{birdhouseFacts[0], birdhouseFacts[1], kingdomFacts[0], kingdomFacts[1], dailiesFacts[0], dailiesFacts[1]})
		{
			l.setVisible(false);
		}
		// Bird houses: its own card, always there (never behind a choice elsewhere), opening the bird house page directly.
		birdhouseCard = tile(itemManager, BIRDHOUSE_ITEM, BIRDHOUSE_TITLE, onOpenBirdhouses, birdhouseHeadline, birdhouseFacts[0], birdhouseFacts[1]);
		icons.add(((BorderLayout) birdhouseCard.getLayout()).getLayoutComponent(BorderLayout.WEST));
		add(birdhouseCard);
		add(Box.createVerticalStrut(CARD_GAP));
		kingdomCard = tile(itemManager, KINGDOM_ITEM, KINGDOM_TITLE, onOpenKingdom, kingdomHeadline, kingdomFacts[0], kingdomFacts[1]);
		icons.add(((BorderLayout) kingdomCard.getLayout()).getLayoutComponent(BorderLayout.WEST));
		add(kingdomCard);
		add(Box.createVerticalStrut(CARD_GAP));
		dailiesBadges.setOpaque(false);
		dailiesBadges.setVisible(false);
		dailiesBadges.setName("home.dailies.badges");
		badgeSprites = itemManager == null ? null : (itemId, onLoaded) ->
		{
			net.runelite.client.util.AsyncBufferedImage image = itemManager.getImage(itemId);
			image.onLoaded(onLoaded);
			return image;
		};
		dailiesCard = tile(itemManager, DAILIES_ITEM, DAILIES_TITLE, onOpenDailies, dailiesHeadline, dailiesFacts[0], dailiesFacts[1], dailiesBadges);
		icons.add(((BorderLayout) dailiesCard.getLayout()).getLayoutComponent(BorderLayout.WEST));
		add(dailiesCard);
		add(Box.createVerticalStrut(CARD_GAP));
		add(card(itemManager, NOTES_ITEM, NOTES_TITLE, NOTES_PURPOSE, null));
		add(Box.createVerticalStrut(CARD_GAP));
		add(card(itemManager, PVP_ITEM, PVP_TITLE, PVP_PURPOSE, null));
		add(Box.createVerticalStrut(12));

		discordButton = new ActionButton(DISCORD_LABEL, ActionButton.Style.DISCORD, () ->
		{
			try
			{
				browser.accept(DISCORD_INVITE);
			}
			catch (RuntimeException e)
			{
				// no browser available: nothing else to do
			}
		});
		discordButton.setIcon(discordIcon());
		discordButton.setToolTipText("Opens the Spyglass Discord invite in your browser");
		discordButton.getAccessibleContext().setAccessibleName(DISCORD_LABEL + " (opens your browser)");
		discordButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(discordButton);
		add(Box.createVerticalStrut(6));
		appearanceButton = new ActionButton(APPEARANCE_LABEL, ActionButton.Style.SECONDARY, () -> onAppearance.run());
		appearanceButton.setName("home.appearance");
		appearanceButton.setToolTipText("Text size and contrast for every Spyglass page, with a live preview");
		appearanceButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(appearanceButton);

		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				updateIconVisibility();
			}
		});
	}

	/**
	 * The Farming card's at-a-glance status, from the Farming-owned model (canonical
	 * §1.5): Home never decodes, projects or counts anything itself.
	 */
	void renderFarming(com.osrstelemetry.plugin.farming.FarmingModel.HomeSummary summary)
	{
		boolean changed = setIfChanged(farmingHeadline, summary.getHeadline());
		Color headlineColor = summary.getHeadlineTone() == com.osrstelemetry.plugin.farming.FarmingModel.Tone.MUTED
			? SpyglassTheme.TILE_TEXT_QUIET : FarmingView.color(summary.getHeadlineTone());
		farmingHeadline.setForeground(headlineColor);
		boolean bold = summary.getHeadlineTone() == com.osrstelemetry.plugin.farming.FarmingModel.Tone.GOLD
			|| summary.getHeadlineTone() == com.osrstelemetry.plugin.farming.FarmingModel.Tone.EMBER
			|| summary.getHeadlineTone() == com.osrstelemetry.plugin.farming.FarmingModel.Tone.GREEN
			|| summary.getHeadlineTone() == com.osrstelemetry.plugin.farming.FarmingModel.Tone.RED;
		farmingHeadline.setFont(bold ? SpyglassFonts.bold() : SpyglassFonts.small());
		List<String> facts = summary.getFacts();
		for (int i = 0; i < farmingFacts.length; i++)
		{
			String text = i < facts.size() ? facts.get(i) : null;
			if (text == null)
			{
				changed |= farmingFacts[i].isVisible();
				farmingFacts[i].setVisible(false);
				continue;
			}
			changed |= !farmingFacts[i].isVisible();
			farmingFacts[i].setVisible(true);
			changed |= setIfChanged(farmingFacts[i], text);
			boolean notTracked = text.startsWith("+ ");
			farmingFacts[i].setForeground(notTracked ? SpyglassTheme.TEXT_MUTED : SpyglassTheme.TEXT_SECONDARY);
		}
		Color rail = summary.getRail() == null ? null : FarmingView.color(summary.getRail());
		farmingCard.setRail(rail);
		if (changed)
		{
			revalidate();
			repaint();
		}
	}

	/** Kingdom and Dailies from the home state (EDT). */
	void renderHome(HomeText.Card kingdom, HomeText.Card dailies)
	{
		renderHome(kingdom, dailies, null);
	}

	/** Kingdom, Dailies &amp; Weeklies and Bird houses (null: left as it is) from their state (EDT). */
	void renderHome(HomeText.Card kingdom, HomeText.Card dailies, HomeText.Card birdhouses)
	{
		boolean changed = apply(kingdomCard, kingdomHeadline, kingdomFacts, kingdom);
		changed |= apply(dailiesCard, dailiesHeadline, dailiesFacts, dailies);
		changed |= badges(dailies.badges);
		if (birdhouses != null)
		{
			changed |= apply(birdhouseCard, birdhouseHeadline, birdhouseFacts, birdhouses);
		}
		if (changed)
		{
			revalidate();
			repaint();
		}
	}

	/** Opens Dailies &amp; Weeklies at an activity (a Home icon's click). */
	void setOnOpenDaily(java.util.function.Consumer<String> open)
	{
		onOpenDaily = open == null ? key -> { } : open;
	}

	/** For the harness: item sprites from elsewhere than the client. */
	void setBadgeSprites(FarmingView.SpriteSource sprites)
	{
		badgeSprites = sprites;
		shownBadges = new ArrayList<>();
	}

	/** The ready activities' icons, rebuilt only when the list changes. */
	private boolean badges(List<HomeText.Badge> next)
	{
		List<String> was = new ArrayList<>();
		shownBadges.forEach(b -> was.add(b.toString()));
		List<String> now = new ArrayList<>();
		next.forEach(b -> now.add(b.toString()));
		if (was.equals(now) && dailiesBadges.getComponentCount() == next.size())
		{
			return false;
		}
		shownBadges = new ArrayList<>(next);
		dailiesBadges.removeAll();
		for (HomeText.Badge b : next)
		{
			dailiesBadges.add(new BadgeIcon(b, badgeSprites, key -> onOpenDaily.accept(key)));
		}
		dailiesBadges.setVisible(!next.isEmpty());
		dailiesBadges.revalidate();
		return true;
	}

	/** The icons on the Dailies &amp; Weeklies card (tests). */
	List<HomeText.Badge> badges()
	{
		return new ArrayList<>(shownBadges);
	}

	JPanel badgePanel()
	{
		return dailiesBadges;
	}

	private static boolean apply(RaisedTile card, JLabel headline, JLabel[] facts, HomeText.Card c)
	{
		boolean changed = setIfChanged(headline, c.headline);
		headline.setForeground(c.tone == null ? SpyglassTheme.TILE_TEXT_QUIET : c.tone);
		headline.setFont(c.tone == null ? SpyglassFonts.small() : SpyglassFonts.bold());
		for (int i = 0; i < facts.length; i++)
		{
			String text = i < c.facts.size() ? c.facts.get(i) : null;
			if (text == null)
			{
				changed |= facts[i].isVisible();
				facts[i].setVisible(false);
				continue;
			}
			changed |= !facts[i].isVisible();
			facts[i].setVisible(true);
			changed |= setIfChanged(facts[i], text);
			boolean attention = c.rail == SpyglassTheme.FARM_EMBER && i == 0;
			facts[i].setForeground(attention ? SpyglassTheme.FARM_EMBER : SpyglassTheme.TEXT_SECONDARY);
		}
		card.setRail(c.rail);
		if (!java.util.Objects.equals(c.tip, card.getToolTipText()))
		{
			card.setToolTipText(c.tip); // supporting detail on demand (how a value was worked out); never essential
		}
		return changed;
	}

	/** Kingdom's (1), Dailies &amp; Weeklies' (2) and Bird houses' (3) lines (tests). */
	List<String> lines(int card)
	{
		JLabel head = card == 1 ? kingdomHeadline : card == 3 ? birdhouseHeadline : dailiesHeadline;
		JLabel[] facts = card == 1 ? kingdomFacts : card == 3 ? birdhouseFacts : dailiesFacts;
		List<String> out = new ArrayList<>();
		out.add(plain(head));
		for (JLabel f : facts)
		{
			if (f.isVisible())
			{
				out.add(plain(f));
			}
		}
		return out;
	}

	JLabel farmingHeadline()
	{
		return farmingHeadline;
	}

	RaisedTile farmingCard()
	{
		return farmingCard;
	}

	ActionButton discordButton()
	{
		return discordButton;
	}

	ActionButton appearanceButton()
	{
		return appearanceButton;
	}

	void setOnAppearance(Runnable r)
	{
		this.onAppearance = r == null ? () -> { } : r;
	}

	/** RuneLite's own Discord mark (the Info panel's, white on transparent), scaled to the button; absent: text only. */
	private static java.awt.Image discordIcon()
	{
		try (java.io.InputStream in = HomeView.class.getResourceAsStream("/net/runelite/client/plugins/info/discord_icon.png"))
		{
			if (in == null)
			{
				return null;
			}
			java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
			if (img == null)
			{
				return null;
			}
			int w = 48; // pass 32: drawn at the text's height by the button (grows with the text size)
			int h = Math.max(1, w * img.getHeight() / Math.max(1, img.getWidth()));
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = out.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.drawImage(img, 0, 0, w, h, null);
			g.dispose();
			return out;
		}
		catch (java.io.IOException | RuntimeException e)
		{
			return null;
		}
	}

	/** The Farming card's lines, top to bottom (tests). */
	List<String> farmingLines()
	{
		List<String> out = new ArrayList<>();
		out.add(plain(farmingHeadline));
		for (JLabel fact : farmingFacts)
		{
			if (fact.isVisible())
			{
				out.add(plain(fact));
			}
		}
		return out;
	}

	private static boolean setIfChanged(JLabel label, String text)
	{
		if (text.equals(plain(label)))
		{
			return false;
		}
		if (label instanceof WrappingHtmlLabel)
		{
			((WrappingHtmlLabel) label).setPlainText(text);
		}
		else
		{
			label.setText(text);
		}
		return true;
	}

	static String plain(JLabel l)
	{
		return l instanceof WrappingHtmlLabel ? ((WrappingHtmlLabel) l).plainText() : l.getText();
	}

	/** Cards top to bottom (tests). */
	List<RaisedTile> cards()
	{
		List<RaisedTile> out = new ArrayList<>();
		for (Component c : getComponents())
		{
			if (c instanceof RaisedTile)
			{
				out.add((RaisedTile) c);
			}
		}
		return out;
	}

	private void updateIconVisibility()
	{
		boolean show = getWidth() >= ICON_MIN_WIDTH;
		if (show == iconsShown)
		{
			return;
		}
		iconsShown = show;
		for (Component icon : icons)
		{
			icon.setVisible(show);
		}
		SwingUtilities.invokeLater(() ->
		{
			revalidate();
			repaint();
		});
	}

	private RaisedTile card(ItemManager itemManager, int itemId, String title, String purpose, Runnable onActivate)
	{
		RaisedTile tile = tile(itemManager, itemId, title, onActivate, quiet(purpose));
		icons.add(((BorderLayout) tile.getLayout()).getLayoutComponent(BorderLayout.WEST));
		return tile;
	}

	/**
	 * One tile: [sprite] title / body lines [chevron when it navigates], with
	 * the sprite, the text block and the chevron all centred vertically.
	 * Shared with the Farming child page so both read as one family.
	 */
	static RaisedTile tile(ItemManager itemManager, int itemId, String title, Runnable onActivate, JComponent... body)
	{
		RaisedTile tile = new RaisedTile(onActivate);

		JPanel iconColumn = new JPanel(new BorderLayout());
		iconColumn.setOpaque(false);
		iconColumn.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, ICON_TEXT_GAP));
		iconColumn.add(new ItemIcon(itemManager, itemId), BorderLayout.CENTER);
		tile.add(iconColumn, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		text.add(Box.createVerticalGlue());
		JLabel titleLabel = new WrappingHtmlLabel(title, false);
		titleLabel.setFont(SpyglassFonts.bold());
		titleLabel.setForeground(SpyglassTheme.TEXT_PRIMARY);
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(titleLabel);
		for (JComponent part : body)
		{
			text.add(Box.createVerticalStrut(1));
			part.setAlignmentX(Component.LEFT_ALIGNMENT);
			text.add(part);
		}
		text.add(Box.createVerticalGlue());
		tile.add(text, BorderLayout.CENTER);

		if (onActivate != null)
		{
			JPanel chevronColumn = new JPanel(new BorderLayout());
			chevronColumn.setOpaque(false);
			chevronColumn.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
			chevronColumn.add(new Chevron(tile), BorderLayout.CENTER);
			tile.add(chevronColumn, BorderLayout.EAST);
		}
		return tile;
	}

	/** A readable sentence that may wrap (the Farming page's description). */
	static JComponent line(String text)
	{
		JLabel label = new WrappingHtmlLabel(text, false);
		label.setFont(SpyglassFonts.regular());
		label.setForeground(SpyglassTheme.TEXT_SECONDARY);
		return label;
	}

	/** Supporting detail: quieter and smaller, one line. */
	static JComponent quiet(String text)
	{
		JLabel label = new WrappingHtmlLabel(text, false);
		label.setFont(SpyglassFonts.small());
		label.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
		return label;
	}

	/**
	 * A pixel-drawn "›" (no font glyph): quiet at rest, cream while its tile is
	 * hovered or pressed, read at paint time so no listener is needed.
	 */
	static final class Chevron extends JComponent
	{
		private static final String[] MASK = {
			"##...",
			".##..",
			"..##.",
			"...##",
			"..##.",
			".##..",
			"##...",
		};
		private final RaisedTile tile;

		Chevron(RaisedTile tile)
		{
			this.tile = tile;
			setOpaque(false);
			setPreferredSize(new Dimension(MASK[0].length(), MASK.length));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Color color = tile.isHovered() || tile.isPressedVisual() ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TILE_TEXT_QUIET;
			g.setColor(color);
			int x0 = (getWidth() - MASK[0].length()) / 2;
			int y0 = (getHeight() - MASK.length) / 2;
			for (int y = 0; y < MASK.length; y++)
			{
				for (int x = 0; x < MASK[y].length(); x++)
				{
					if (MASK[y].charAt(x) == '#')
					{
						g.fillRect(x0 + x, y0 + y, 1, 1);
					}
				}
			}
		}
	}
}
