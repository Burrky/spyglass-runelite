package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.home.Kingdom;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Home → Kingdom (canonical Home §10; pass 31: a status display): approval as a large percentage over a bar that turns
 * ember below 90% and sage above, the coffer as the game's own coin stack (fuller as it nears what pays the full daily
 * maximum) and its amount, then what to do ("Raise approval", "Top up coffer") and, quietly, how old the reading is.
 * Readings on the islands are this moment's; away from them, the values are worked out from the last reading by the
 * verified daily rules, with the reading's age and the working on hover. Quest access shows only while it blocks the
 * kingdom. Worker allocation has no reliable reading, so it is not shown.
 */
final class KingdomView extends HomeDetailPage
{
	/** Coins (the item the game draws as a stack of the amount). */
	static final int COINS = 995;

	private HomeSource source = HomeSource.NONE;
	private ItemStackSource stacks;

	void attach(HomeSource source)
	{
		this.source = source == null ? HomeSource.NONE : source;
		invalidateSignature();
	}

	void setStacks(ItemStackSource stacks)
	{
		this.stacks = stacks;
		invalidateSignature();
	}

	@Override
	String signature(long now)
	{
		Kingdom k = source.kingdom();
		boolean live = source.onIslands();
		return k.getQuestDone() + "|" + k.isRoyalTrouble() + "|" + k.getSeenAt() + "|" + k.getCoffer() + "|" + k.getApproval() + "|" + live + "|"
			+ k.paidDaysSince(now) + "|" + k.daysSinceVisit(now) + "|" + k.actions(live, now) + "|" + ageText(k, live, now);
	}

	/** "Read on the islands now", "Read today on the islands", "Worked out from a reading 3 days ago". */
	static String ageText(Kingdom k, boolean live, long now)
	{
		if (k.getSeenAt() == null)
		{
			return "";
		}
		if (live)
		{
			return "Read on the islands now";
		}
		int days = k.daysSinceVisit(now);
		if (k.paidDaysSince(now) == 0)
		{
			return "Read " + HomeText.daysAgo(days) + " on the islands";
		}
		return "Worked out from the reading " + HomeText.daysAgo(days);
	}

	@Override
	void build(long now)
	{
		Kingdom k = source.kingdom();
		boolean live = source.onIslands();
		if (k.getQuestDone() == null)
		{
			add(card("KINGDOM", null, java.util.Collections.singletonList(sentence("Log in and Spyglass reads whether the kingdom is yours.",
				SpyglassTheme.TEXT_MUTED)), null));
			return;
		}
		if (!k.getQuestDone())
		{
			// The one requirement, only while it keeps the kingdom closed.
			List<JComponent> rows = new ArrayList<>();
			rows.add(sentence("Complete Throne of Miscellania to rule Miscellania and Etceteria.", SpyglassTheme.TEXT_PRIMARY));
			add(card("NEEDS THRONE OF MISCELLANIA", "Read from the game's quest progress", rows, SpyglassTheme.ACCENT_GOLD));
			return;
		}
		if (k.getSeenAt() == null)
		{
			add(card("KINGDOM", null, java.util.Collections.singletonList(sentence(
				"Not seen yet. Visit Miscellania or Etceteria and Spyglass reads the approval and coffer there.", SpyglassTheme.TEXT_MUTED)), null));
			return;
		}
		int approval = k.approvalNow(live, now);
		int coffer = k.cofferNow(live, now);
		String how = live ? null : HomeText.calculatedTip(k, now);
		add(approvalTile(approval, k.approvalLow(live, now), how));
		gap();
		add(cofferTile(k, coffer, k.cofferLow(live, now), how));
		List<Kingdom.Action> actions = k.actions(live, now);
		if (!actions.isEmpty())
		{
			gap();
			add(actionsTile(actions));
		}
		add(Box.createVerticalStrut(6));
		JComponent age = sentence(ageText(k, live, now), SpyglassTheme.TILE_TEXT_QUIET);
		age.setName("kingdom.age");
		if (how != null)
		{
			age.setToolTipText(how);
		}
		add(age);
	}

	private JComponent approvalTile(int points, boolean low, String how)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(low ? SpyglassTheme.FARM_EMBER : SpyglassTheme.ACCENT_SAGE);
		JPanel body = column();
		body.add(left(label("APPROVAL", SpyglassTheme.ACCENT_GOLD, SpyglassFonts.smallBold())));
		int pct = Kingdom.percent(points);
		JLabel value = label(pct + "%", low ? SpyglassTheme.FARM_EMBER : SpyglassTheme.TEXT_PRIMARY, SpyglassFonts.large());
		value.setName("kingdom.approval");
		WrappingHtmlLabel state = new WrappingHtmlLabel(low ? "Needs attention" : "Healthy", false);
		state.setFont(SpyglassFonts.regular());
		state.setForeground(low ? SpyglassTheme.FARM_EMBER : SpyglassTheme.ACCENT_SAGE);
		body.add(left(value));
		JPanel stateCell = new JPanel(new BorderLayout());
		stateCell.setOpaque(false);
		stateCell.add(state, BorderLayout.CENTER);
		body.add(left(stateCell));
		body.add(Box.createVerticalStrut(SpyglassFonts.px(3)));
		ApprovalBar bar = new ApprovalBar(points / (float) Kingdom.MAX_APPROVAL, Kingdom.APPROVAL_REMINDER_PERCENT / 100f, low);
		bar.getAccessibleContext().setAccessibleName("Approval " + pct + "%" + (low ? ", under " + Kingdom.APPROVAL_REMINDER_PERCENT + "%" : ""));
		body.add(left(bar));
		tile.add(body, BorderLayout.CENTER);
		if (how != null)
		{
			tile.setToolTipText(how);
		}
		tile.getAccessibleContext().setAccessibleName("Approval " + pct + "%, " + (low ? "needs attention" : "healthy"));
		return growing(tile);
	}

	private JComponent cofferTile(Kingdom k, int coins, boolean low, String how)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setLayout(new BorderLayout(8, 0));
		tile.setRail(low ? SpyglassTheme.FARM_EMBER : SpyglassTheme.ACCENT_SAGE);
		int level = Kingdom.cofferLevel(coins, k.fullPaymentCoffer());
		FarmingView.CropIcon icon = new FarmingView.CropIcon(stacks == null ? null : (quantity, onLoaded) -> stacks.load(COINS, quantity, onLoaded));
		icon.show(Kingdom.cofferStackQuantity(level), level == 0 ? "An empty coffer" : "Coins in the coffer");
		icon.setName("kingdom.coins");
		icon.setVisible(level > 0);
		JPanel iconColumn = new JPanel(new BorderLayout());
		iconColumn.setOpaque(false);
		iconColumn.add(icon, BorderLayout.NORTH);
		JPanel body = column();
		body.add(left(label("COFFER", SpyglassTheme.ACCENT_GOLD, SpyglassFonts.smallBold())));
		JLabel amount = new WrappingHtmlLabel(String.format(java.util.Locale.ROOT, "%,d", coins) + " coins", false);
		amount.setFont(SpyglassFonts.bold());
		amount.setForeground(low ? SpyglassTheme.FARM_EMBER : SpyglassTheme.TEXT_PRIMARY);
		amount.setName("kingdom.coffer");
		body.add(left(amount));
		// The amounts needed sit once, in "Top up coffer" below; here only whether the day's payment is full.
		JLabel pays = new WrappingHtmlLabel(low ? "Can't pay the full amount each day" : "Pays the full " + HomeText.coins(k.maxPayment()) + " a day", false);
		pays.setFont(SpyglassFonts.small());
		pays.setForeground(SpyglassTheme.TEXT_SECONDARY);
		pays.setToolTipText("Each midnight (UTC) 10% of the coffer is paid, up to " + HomeText.coins(k.maxPayment()) + ": the full amount needs "
			+ HomeText.coins(k.fullPaymentCoffer()) + " or more.");
		body.add(left(pays));
		tile.add(iconColumn, BorderLayout.WEST);
		tile.add(body, BorderLayout.CENTER);
		if (how != null)
		{
			tile.setToolTipText(how);
		}
		tile.getAccessibleContext().setAccessibleName("Coffer " + String.format(java.util.Locale.ROOT, "%,d", coins) + " coins" + (low ? ", low" : ""));
		return growing(tile);
	}

	private JComponent actionsTile(List<Kingdom.Action> actions)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = column();
		body.add(left(label("TO DO", SpyglassTheme.ACCENT_GOLD, SpyglassFonts.smallBold())));
		for (int i = 0; i < actions.size(); i++)
		{
			Kingdom.Action a = actions.get(i);
			if (i > 0)
			{
				body.add(Box.createVerticalStrut(SpyglassFonts.px(4)));
			}
			JLabel title = new WrappingHtmlLabel("▸ " + a.title, false);
			title.setFont(SpyglassFonts.bold());
			title.setForeground(SpyglassTheme.TEXT_PRIMARY);
			body.add(left(title));
			JLabel how = new WrappingHtmlLabel(a.how, false);
			how.setFont(SpyglassFonts.small());
			how.setForeground(SpyglassTheme.TEXT_SECONDARY);
			how.setBorder(BorderFactory.createEmptyBorder(0, SpyglassFonts.px(10), 0, 0));
			body.add(left(how));
		}
		tile.add(body, BorderLayout.CENTER);
		return growing(tile);
	}

	private static JPanel column()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		return body;
	}

	private static JLabel label(String s, Color c, java.awt.Font f)
	{
		JLabel l = new JLabel(s);
		l.setFont(f);
		l.setForeground(c);
		return l;
	}

	private static <T extends JComponent> T left(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	/**
	 * Approval as a bar: the share of the 127 points filled, a notch at the reminder threshold (90%), ember below it and sage
	 * at or above. The number beside it carries the value; the colour is never the only sign.
	 */
	static final class ApprovalBar extends JComponent
	{
		private final float fraction;
		private final float threshold;
		private final boolean low;

		ApprovalBar(float fraction, float threshold, boolean low)
		{
			this.fraction = Math.max(0f, Math.min(1f, fraction));
			this.threshold = threshold;
			this.low = low;
			setOpaque(false);
		}

		float getFraction()
		{
			return fraction;
		}

		boolean isLow()
		{
			return low;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(40, SpyglassFonts.px(10));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(SpyglassTheme.METER_TRACK);
			g2.fillRoundRect(0, 0, w, h, 4, 4);
			int fill = Math.round(w * fraction);
			g2.setColor(low ? SpyglassTheme.FARM_EMBER : SpyglassTheme.ACCENT_SAGE);
			g2.fillRoundRect(0, 0, fill, h, 4, 4);
			g2.setColor(SpyglassTheme.TILE_RIM);
			g2.drawRoundRect(0, 0, w - 1, h - 1, 4, 4);
			int notch = Math.round(w * threshold);
			g2.setColor(SpyglassTheme.TEXT_PRIMARY);
			g2.fillRect(notch, 0, 1, h);
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
						return AccessibleRole.PROGRESS_BAR;
					}
				};
			}
			return accessibleContext;
		}
	}
}
