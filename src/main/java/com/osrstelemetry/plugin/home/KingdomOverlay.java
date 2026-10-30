package com.osrstelemetry.plugin.home;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The Kingdom box (pass 31): while the player stands on Miscellania or Etceteria and the game's readings there are in, the
 * approval as a bar with its percentage, the coffer as the game's coin stack with its balance, and a short upkeep prompt when
 * one is due. The same readings as the sidebar's Kingdom page (one {@link Kingdom}); no chat message of its own. Hidden off the
 * islands, logged out, before the first reading of the visit, or when turned off; removed when the plugin stops. Movable.
 */
public final class KingdomOverlay extends OverlayPanel
{
	static final Color GOLD = new Color(0xD4, 0xA9, 0x3A);
	static final Color SAGE = new Color(0x93, 0xAE, 0x7C);
	static final Color EMBER = new Color(0xE8, 0x86, 0x6A);
	static final Color TRACK = new Color(0x2B, 0x27, 0x23);

	/** What the box shows (null: hidden). Pure, for tests. */
	public static final class Model
	{
		public final int approvalPercent;
		public final boolean approvalLow;
		public final int coffer;
		public final boolean cofferLow;
		public final int cofferLevel;
		/** The most pressing upkeep prompt in a few words ("Raise approval"), or null. */
		public final String prompt;

		Model(int approvalPercent, boolean approvalLow, int coffer, boolean cofferLow, int cofferLevel, String prompt)
		{
			this.approvalPercent = approvalPercent;
			this.approvalLow = approvalLow;
			this.coffer = coffer;
			this.cofferLow = cofferLow;
			this.cofferLevel = cofferLevel;
			this.prompt = prompt;
		}
	}

	/**
	 * @param enabled  the player's setting
	 * @param here     standing on the islands this moment (logged in)
	 * @param readHere the observer has read the kingdom during this visit ({@link HomeObserver#isOnIslands})
	 */
	public static Model model(Kingdom k, boolean enabled, boolean here, boolean readHere)
	{
		if (!enabled || !here || !readHere || k == null || !Boolean.TRUE.equals(k.getQuestDone()) || k.getSeenAt() == null)
		{
			return null;
		}
		long now = k.getSeenAt(); // a live reading: no day has passed since it
		int approval = k.approvalNow(true, now);
		int coffer = k.cofferNow(true, now);
		List<Kingdom.Action> actions = k.actions(true, now);
		return new Model(Kingdom.percent(approval), k.approvalLow(true, now), coffer, k.cofferLow(true, now),
			Kingdom.cofferLevel(coffer, k.fullPaymentCoffer()), actions.isEmpty() ? null : actions.get(0).title);
	}

	private final Supplier<Kingdom> kingdom;
	private final BooleanSupplier enabled;
	private final BooleanSupplier here;
	private final BooleanSupplier readHere;
	/** Coin stack image for a quantity (the game's own stacks); cached per level. */
	private final IntFunction<BufferedImage> coins;
	private final BufferedImage[] stacks = new BufferedImage[5];

	public KingdomOverlay(Supplier<Kingdom> kingdom, BooleanSupplier enabled, BooleanSupplier here, BooleanSupplier readHere,
		IntFunction<BufferedImage> coins)
	{
		this.kingdom = kingdom;
		this.enabled = enabled;
		this.here = here;
		this.readHere = readHere;
		this.coins = coins;
		setPosition(OverlayPosition.TOP_LEFT);
		setMovable(true);
		getPanelComponent().setPreferredSize(new Dimension(150, 0));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Model m = model(kingdom.get(), enabled.getAsBoolean(), here.getAsBoolean(), readHere.getAsBoolean());
		if (m == null)
		{
			return null;
		}
		getPanelComponent().getChildren().add(TitleComponent.builder().text("Kingdom").color(GOLD).build());
		getPanelComponent().getChildren().add(LineComponent.builder().left("Approval").leftColor(Color.LIGHT_GRAY)
			.right(m.approvalPercent + "%").rightColor(m.approvalLow ? EMBER : Color.WHITE).build());
		ProgressBarComponent bar = new ProgressBarComponent();
		bar.setMinimum(0);
		bar.setMaximum(100);
		bar.setValue(m.approvalPercent);
		bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
		bar.setCenterLabel("");
		bar.setForegroundColor(m.approvalLow ? EMBER : SAGE);
		bar.setBackgroundColor(TRACK);
		bar.setPreferredSize(new Dimension(150, 8));
		getPanelComponent().getChildren().add(bar);
		LineComponent amount = LineComponent.builder().left(String.format(java.util.Locale.ROOT, "%,d", m.coffer))
			.leftColor(m.cofferLow ? EMBER : Color.WHITE).build();
		BufferedImage img = stack(m.cofferLevel);
		if (img != null)
		{
			getPanelComponent().getChildren().add(SplitComponent.builder().first(new ImageComponent(img)).second(amount)
				.orientation(ComponentOrientation.HORIZONTAL).gap(new java.awt.Point(4, 0)).build());
		}
		else
		{
			getPanelComponent().getChildren().add(LineComponent.builder().left("Coffer").leftColor(Color.LIGHT_GRAY)
				.right(String.format(java.util.Locale.ROOT, "%,d", m.coffer)).rightColor(m.cofferLow ? EMBER : Color.WHITE).build());
		}
		if (m.prompt != null)
		{
			getPanelComponent().getChildren().add(LineComponent.builder().left(m.prompt).leftColor(GOLD).build());
		}
		return super.render(g);
	}

	private BufferedImage stack(int level)
	{
		if (level <= 0 || coins == null)
		{
			return null;
		}
		if (stacks[level] == null)
		{
			stacks[level] = coins.apply(Kingdom.cofferStackQuantity(level));
		}
		return stacks[level];
	}
}
