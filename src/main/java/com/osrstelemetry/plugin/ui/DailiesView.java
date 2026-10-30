package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.home.Dailies;
import com.osrstelemetry.plugin.home.DailyTask;
import com.osrstelemetry.plugin.home.DailyUses;
import com.osrstelemetry.plugin.home.Recurring;
import com.osrstelemetry.plugin.home.TearsOfGuthix;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Home → Dailies &amp; Weeklies (canonical Home §11; pass 31: activity cards). Three groups: DAILY (the daily claims, reset at
 * 00:00 UTC), WEEKLY (Tears of Guthix, on its own seven-day clock) and DAILY USES (allowances such as the Explorer's ring's:
 * quiet, never a chore). Every activity is a card: its item, its name, a tick in the top corner for "track this" (a muted
 * green face when tracked, muted red when not) and what the game shows: where, whether it is claimable, what is left, and
 * when it comes back. Tracking and claiming are separate: claiming keeps the tick and changes only the status; the tick never
 * claims or unclaims anything. Nothing read yet is "Not read", never "to do" or "ready".
 */
final class DailiesView extends HomeDetailPage
{
	private HomeSource source = HomeSource.NONE;
	private java.util.function.LongSupplier clock = System::currentTimeMillis;
	private FarmingView.SpriteSource sprites;
	/** The card Home asked to show (its key), until shown. */
	private String focusKey;
	/** The card shown highlighted after a Home icon opened the page (until the page is rebuilt for something else). */
	private String highlightKey;

	void attach(HomeSource source)
	{
		this.source = source == null ? HomeSource.NONE : source;
		invalidateSignature();
	}

	void setSprites(FarmingView.SpriteSource sprites)
	{
		this.sprites = sprites;
		invalidateSignature();
	}

	/** Opened from a Home icon: scroll to that activity's card and highlight it ("daily.BATTLESTAVES", "recurring.TEARS"). */
	void focus(String key)
	{
		focusKey = key;
		highlightKey = key;
		invalidateSignature();
		render(clock.getAsLong());
	}

	@Override
	String signature(long now)
	{
		Dailies d = source.dailies();
		Recurring r = source.recurring();
		StringBuilder b = new StringBuilder().append(source.loggedIn()).append('|').append(highlightKey).append('|');
		for (HomeText.Activity a : activities(d, r, now))
		{
			b.append(a).append(';');
		}
		return b.append(HomeText.again(now)).toString();
	}

	/** Every card, in group order (the page and its signature). */
	List<HomeText.Activity> activities(Dailies d, Recurring r, long now)
	{
		List<HomeText.Activity> out = new ArrayList<>();
		for (DailyTask t : DailyTask.values())
		{
			out.add(HomeText.dailyActivity(d, t, now));
		}
		out.add(HomeText.tearsActivity(r, now));
		for (DailyUses.Status u : r.uses().statuses(source.charges(), now))
		{
			for (Recurring.Entry e : Recurring.Entry.of(Recurring.Group.DAILY_USES))
			{
				if (e.use() == u.getUse())
				{
					out.add(HomeText.useActivity(r, e, u));
				}
			}
		}
		return out;
	}

	@Override
	void build(long now)
	{
		Dailies d = source.dailies();
		Recurring r = source.recurring();
		List<HomeText.Activity> all = activities(d, r, now);
		boolean anyTracked = all.stream().anyMatch(a -> a.selected);
		if (!source.loggedIn() && d.statuses(now).stream().allMatch(s -> s.getState() == Dailies.State.NOT_READ))
		{
			add(sentence("Log in on a members' world: Spyglass reads what your account has unlocked and claimed.", SpyglassTheme.TEXT_MUTED));
			add(Box.createVerticalStrut(6));
		}
		if (!anyTracked)
		{
			add(sentence("Tick the activities you do: they show here in green and on Home when they are ready.", SpyglassTheme.TEXT_SECONDARY));
			add(Box.createVerticalStrut(6));
		}
		List<HomeText.Activity> daily = new ArrayList<>();
		List<HomeText.Activity> weekly = new ArrayList<>();
		List<HomeText.Activity> uses = new ArrayList<>();
		for (HomeText.Activity a : all)
		{
			(a.key.startsWith("daily.") ? daily : a.key.equals(HomeText.recurringKey(Recurring.Entry.TEARS)) ? weekly : uses).add(a);
		}
		group("DAILY", "Resets at 00:00 UTC · " + HomeText.again(now).replace("again ", ""), daily);
		gap();
		group("WEEKLY", "Each on its own clock, not a shared weekly reset", weekly);
		gap();
		group("DAILY USES", "Allowances: never counted as left to do", uses);
		if (focusKey != null)
		{
			String key = focusKey;
			focusKey = null;
			SwingUtilities.invokeLater(() -> reveal(key));
		}
	}

	private void group(String title, String note, List<HomeText.Activity> cards)
	{
		JLabel head = new WrappingHtmlLabel(title, false);
		head.setFont(SpyglassFonts.smallBold());
		head.setForeground(SpyglassTheme.ACCENT_GOLD);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(growing(head));
		JComponent sub = sentence(note, SpyglassTheme.TILE_TEXT_QUIET);
		add(growing(sub));
		add(Box.createVerticalStrut(4));
		JPanel grid = new JPanel(new CardGridLayout(3, 4, 4));
		grid.setOpaque(false);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (HomeText.Activity a : cards)
		{
			grid.add(new ActivityTile(a, sprites, a.key.equals(highlightKey), on -> choose(a.key, on)));
		}
		add(growing(grid));
	}

	/** The tick: track or stop tracking (never a claim). */
	private void choose(String key, boolean on)
	{
		if (key.startsWith("daily."))
		{
			DailyTask t = DailyTask.valueOf(key.substring("daily.".length()));
			source.setTracked(t, on);
		}
		else
		{
			Recurring.Entry e = Recurring.Entry.valueOf(key.substring("recurring.".length()));
			source.setShown(e, on);
		}
		invalidateSignature();
		render(clock.getAsLong());
	}

	/** Scrolls the card into view and gives its tick the focus. */
	private void reveal(String key)
	{
		ActivityTile t = tile(this, key);
		if (t == null)
		{
			return;
		}
		Rectangle r = SwingUtilities.convertRectangle(t.getParent(), t.getBounds(), this);
		scrollRectToVisible(new Rectangle(r.x, Math.max(0, r.y - 8), r.width, r.height + 16));
		t.box.requestFocusInWindow();
	}

	static ActivityTile tile(java.awt.Container root, String key)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof ActivityTile && key.equals(((ActivityTile) c).getName()))
			{
				return (ActivityTile) c;
			}
			if (c instanceof java.awt.Container)
			{
				ActivityTile f = tile((java.awt.Container) c, key);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}

	void setClock(java.util.function.LongSupplier clock)
	{
		this.clock = clock;
	}

	/** One activity: [item] Name ☐ / where / status / detail, on a green (tracked) or red (not tracked) face. */
	static final class ActivityTile extends RaisedTile implements CardGridLayout.Comfortable
	{
		final TrackBox box;
		final HomeText.Activity activity;
		private final List<WrappingHtmlLabel> texts = new ArrayList<>();

		ActivityTile(HomeText.Activity a, FarmingView.SpriteSource sprites, boolean highlighted, java.util.function.Consumer<Boolean> onToggle)
		{
			super(null, 5, 6);
			this.activity = a;
			setName(a.key);
			setLayout(new BorderLayout(6, 0));
			setFace(a.selected ? SpyglassTheme.CARD_SELECTED : SpyglassTheme.CARD_UNSELECTED, null);
			setRail(highlighted ? SpyglassTheme.ACCENT_GOLD : null);
			FarmingView.CropIcon icon = new FarmingView.CropIcon(sprites);
			icon.show(a.itemId, a.title);
			JPanel iconColumn = new JPanel(new BorderLayout());
			iconColumn.setOpaque(false);
			iconColumn.add(icon, BorderLayout.NORTH);
			box = new TrackBox(a.selected, "Track " + a.title, onToggle);
			box.setName("track." + a.key);
			box.setEnabled(a.selectable);
			box.setToolTipText(a.selectable ? (a.selected ? "Tracked: shown on Home when ready. Untick to stop." : "Tick to track it") : a.status);
			JPanel boxColumn = new JPanel(new BorderLayout());
			boxColumn.setOpaque(false);
			boxColumn.add(box, BorderLayout.NORTH);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			text.add(line(a.title, SpyglassFonts.bold(), a.selected ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY));
			text.add(line(a.where, SpyglassFonts.small(), SpyglassTheme.TILE_TEXT_QUIET));
			text.add(line(a.status, SpyglassFonts.small(), a.tone));
			if (a.detail != null)
			{
				text.add(line(a.detail, SpyglassFonts.small(), SpyglassTheme.TEXT_MUTED));
			}
			add(iconColumn, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
			add(boxColumn, BorderLayout.EAST);
			getAccessibleContext().setAccessibleName(a.title + ", " + (a.selected ? "tracked" : "not tracked") + ". " + a.where + ". " + a.status);
		}

		private JComponent line(String s, java.awt.Font f, Color c)
		{
			WrappingHtmlLabel l = new WrappingHtmlLabel(s, false);
			l.setFont(f);
			l.setForeground(c);
			texts.add(l);
			// Each wrapping line in a cell of its own: measured at the text column's width (pass 30).
			JPanel cell = new JPanel(new BorderLayout());
			cell.setOpaque(false);
			cell.add(l, BorderLayout.CENTER);
			cell.setAlignmentX(Component.LEFT_ALIGNMENT);
			return cell;
		}

		/** Icon, gaps, tick and padding, plus the name or the status on one line (whichever is longer, up to 150 px of text). */
		@Override
		public int comfortableWidth()
		{
			int textW = 0;
			for (int i = 0; i < Math.min(3, texts.size()); i++)
			{
				textW = Math.max(textW, Math.min(SpyglassFonts.px(150), texts.get(i).naturalWidth()));
			}
			java.awt.Insets in = getInsets();
			return in.left + in.right + ItemIcon.WIDTH + 12 + SpyglassFonts.px(16) + textW;
		}

		String plainStatus()
		{
			return activity.status;
		}
	}
}
