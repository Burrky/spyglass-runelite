package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.home.DailyTask;
import com.osrstelemetry.plugin.home.Dailies;
import com.osrstelemetry.plugin.home.Kingdom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words Home's Kingdom and Dailies cards and pages show, from the home state alone (pure, tested). A reading is said as
 * one (with its age when it is not this moment's); an estimate is said as one ("about"); nothing read is "not read", never a
 * value or a task left undone.
 */
final class HomeText
{
	private HomeText()
	{
	}

	/** A card's lines: headline, its tone (gold: act now, ember: attention, null: quiet), and up to two facts. */
	static final class Card
	{
		final String headline;
		final java.awt.Color tone;
		final List<String> facts;
		final java.awt.Color rail;
		/** Supporting detail on hover (how a value was worked out); never needed to read the card. */
		final String tip;

		Card(String headline, java.awt.Color tone, List<String> facts, java.awt.Color rail)
		{
			this(headline, tone, facts, rail, null);
		}

		Card(String headline, java.awt.Color tone, List<String> facts, java.awt.Color rail, String tip)
		{
			this(headline, tone, facts, rail, tip, java.util.Collections.emptyList());
		}

		/** Icons under the text (Dailies &amp; Weeklies: what is ready now; pass 31). */
		final List<Badge> badges;

		Card(String headline, java.awt.Color tone, List<String> facts, java.awt.Color rail, String tip, List<Badge> badges)
		{
			this.headline = headline;
			this.tone = tone;
			this.facts = facts;
			this.rail = rail;
			this.tip = tip;
			this.badges = badges == null ? java.util.Collections.emptyList() : badges;
		}
	}

	/** One ready activity's icon on Home (pass 31): clicking it opens Dailies &amp; Weeklies at that activity. */
	static final class Badge
	{
		/** The activity's card on the page ("daily.BATTLESTAVES", "recurring.TEARS"). */
		final String key;
		final int itemId;
		final String label;
		/** What is left of a counted claim ("4"), or null. */
		final String count;

		Badge(String key, int itemId, String label, String count)
		{
			this.key = key;
			this.itemId = itemId;
			this.label = label;
			this.count = count;
		}

		@Override
		public String toString()
		{
			return key + (count == null ? "" : "×" + count);
		}
	}

	/** One activity card on Dailies &amp; Weeklies (pass 31): the picture, name, tracking choice and what the game shows. */
	static final class Activity
	{
		final String key;
		final String title;
		final int itemId;
		final String where;
		final String status;
		final java.awt.Color tone;
		/** A quieter third line (Tears of Guthix's date note), or null. */
		final String detail;
		final boolean selected;
		/** Whether the checkbox can be ticked now (a locked daily cannot until it unlocks; it can always be unticked). */
		final boolean selectable;

		Activity(String key, String title, int itemId, String where, String status, java.awt.Color tone, String detail, boolean selected, boolean selectable)
		{
			this.key = key;
			this.title = title;
			this.itemId = itemId;
			this.where = where;
			this.status = status;
			this.tone = tone;
			this.detail = detail;
			this.selected = selected;
			this.selectable = selectable;
		}

		@Override
		public String toString()
		{
			return key + "|" + selected + "|" + status + "|" + detail + "|" + selectable;
		}
	}

	static String dailyKey(DailyTask t)
	{
		return "daily." + t.name();
	}

	static String recurringKey(com.osrstelemetry.plugin.home.Recurring.Entry e)
	{
		return "recurring." + e.name();
	}

	/** "again in 5h 12m": the next daily reset. */
	static String again(long now)
	{
		return "again in " + com.osrstelemetry.plugin.farming.FarmingText.remaining(Dailies.untilReset(now)).replace("~", "");
	}

	/** A daily claim's card: claimed keeps the tick; what is left, when it comes back, or what keeps it closed. */
	static Activity dailyActivity(Dailies d, DailyTask t, long now)
	{
		Dailies.Status s = d.status(t, now);
		boolean on = d.isTracked(t);
		String status;
		java.awt.Color tone;
		switch (s.getState())
		{
			case AVAILABLE:
				status = (s.getUses() > 0 ? s.getRemaining() + " of " + s.getUses() + " left" : "To claim") + (s.isNewDay() ? " · new day" : "");
				tone = SpyglassTheme.ACCENT_GOLD;
				break;
			case DONE:
				status = (s.getUses() > 0 ? "All " + s.getUses() + " used" : "Claimed") + " · " + again(now);
				tone = SpyglassTheme.ACCENT_SAGE;
				break;
			case NEEDS:
				status = s.getNote() == null ? "Needs something first" : s.getNote();
				tone = SpyglassTheme.TEXT_SECONDARY;
				break;
			case LOCKED:
				status = "Locked · " + (s.getNote() == null ? "Needs " + t.getUnlock() : s.getNote());
				tone = SpyglassTheme.TEXT_MUTED;
				break;
			case NOT_READ:
			default:
				status = "Not read · log in to read it";
				tone = SpyglassTheme.TEXT_MUTED;
		}
		return new Activity(dailyKey(t), t.getTitle(), t.getIconItem(), t.getWhere(), status, tone, null, on, on || s.getState() != Dailies.State.LOCKED);
	}

	static Activity tearsActivity(com.osrstelemetry.plugin.home.Recurring r, long now)
	{
		com.osrstelemetry.plugin.home.Recurring.Entry e = com.osrstelemetry.plugin.home.Recurring.Entry.TEARS;
		com.osrstelemetry.plugin.home.TearsOfGuthix.Status t = r.tears().status(now);
		String status = tearsStatus(t, now);
		boolean ready = t.getState() == com.osrstelemetry.plugin.home.TearsOfGuthix.State.READY;
		boolean counting = t.getState() == com.osrstelemetry.plugin.home.TearsOfGuthix.State.COUNTING_DOWN;
		if (counting && status.startsWith("in "))
		{
			status = "Back " + status;
		}
		return new Activity(recurringKey(e), e.getTitle(), e.getIconItem(), e.getWhere(), status,
			ready ? SpyglassTheme.ACCENT_GOLD : counting ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_MUTED, t.getDetail(), r.isShown(e), true);
	}

	static Activity useActivity(com.osrstelemetry.plugin.home.Recurring r, com.osrstelemetry.plugin.home.Recurring.Entry e,
		com.osrstelemetry.plugin.home.DailyUses.Status u)
	{
		String status = dailyUseStatus(u);
		return new Activity(recurringKey(e), e.getTitle(), e.getIconItem(), e.getWhere(), status,
			u.isKnown() && u.isFresh() ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_MUTED, u.getNote(), r.isShown(e), true);
	}

	/**
	 * Home's icons: every tracked daily claim the game shows claimable now (a counted one with what is left) and Tears of
	 * Guthix when ready. Never an unread daily, an unread date or unknown eligibility; daily uses are allowances, not chores.
	 */
	static List<Badge> readyBadges(Dailies d, com.osrstelemetry.plugin.home.Recurring rec, long now)
	{
		List<Badge> out = new ArrayList<>();
		if (d.isChosen())
		{
			for (DailyTask t : d.trackedTasks())
			{
				Dailies.Status s = d.status(t, now);
				if (s.getState() == Dailies.State.AVAILABLE)
				{
					String left = s.getUses() > 0 ? String.valueOf(s.getRemaining()) : null;
					out.add(new Badge(dailyKey(t), t.getIconItem(), t.getTitle() + (left == null ? ": to claim" : ": " + left + " of " + s.getUses() + " left"), left));
				}
			}
		}
		com.osrstelemetry.plugin.home.Recurring.Entry tears = com.osrstelemetry.plugin.home.Recurring.Entry.TEARS;
		if (rec.isShown(tears) && rec.tears().status(now).getState() == com.osrstelemetry.plugin.home.TearsOfGuthix.State.READY)
		{
			out.add(new Badge(recurringKey(tears), tears.getIconItem(), "Tears of Guthix: ready", null));
		}
		return out;
	}

	/** "2.1M", "640K", "9,500". */
	static String coins(int n)
	{
		if (n >= 1_000_000)
		{
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
		}
		if (n >= 100_000)
		{
			return (n / 1000) + "K";
		}
		return String.format(Locale.ROOT, "%,d", n);
	}

	/** "today", "1 day ago", "9 days ago". */
	static String daysAgo(int days)
	{
		return days <= 0 ? "today" : days == 1 ? "1 day ago" : days + " days ago";
	}

	static Card kingdomCard(Kingdom k, boolean live, long now)
	{
		List<String> facts = new ArrayList<>();
		if (k.getQuestDone() == null)
		{
			return new Card("Not read yet", null, facts, null);
		}
		if (!k.getQuestDone())
		{
			return new Card("Needs Throne of Miscellania", null, facts, null);
		}
		if (k.getSeenAt() == null)
		{
			facts.add("Visit Miscellania to read it");
			return new Card("Coffer & approval not seen", null, facts, null);
		}
		List<String> reminders = k.reminders(live, now);
		String headline;
		String tip = null;
		if (live)
		{
			headline = coins(k.getCoffer()) + " coffer · " + Kingdom.percent(k.getApproval()) + "%";
			facts.add("On the islands now");
		}
		else
		{
			// Today's values worked out from the last reading by the daily rules: shown plainly, the reading's age quietly under them.
			headline = coins(k.estimatedCoffer(now)) + " coffer · " + Kingdom.percent(k.estimatedApproval(now)) + "%";
			facts.add("Seen " + daysAgo(k.daysSinceVisit(now)));
			tip = calculatedTip(k, now);
		}
		if (!reminders.isEmpty())
		{
			facts.add(0, k.shortReminder(live, now));
			return new Card(headline, null, facts.subList(0, Math.min(2, facts.size())), SpyglassTheme.FARM_EMBER, tip);
		}
		return new Card(headline, null, facts, null, tip);
	}

	static Card dailiesCard(Dailies d, boolean loggedIn, long now)
	{
		List<String> facts = new ArrayList<>();
		if (!d.isChosen() || d.trackedTasks().isEmpty())
		{
			return new Card("Choose the dailies to track", null, facts, null);
		}
		List<String> ready = new ArrayList<>();
		int unread = 0;
		for (DailyTask t : d.trackedTasks())
		{
			Dailies.Status s = d.status(t, now);
			if (s.getState() == Dailies.State.AVAILABLE)
			{
				ready.add(t.getTitle());
			}
			unread += s.getState() == Dailies.State.NOT_READ ? 1 : 0;
		}
		if (!ready.isEmpty())
		{
			facts.add(ready.size() == 1 ? ready.get(0) : ready.get(0) + " and " + (ready.size() - 1) + " more");
			return new Card(ready.size() + " to claim", SpyglassTheme.ACCENT_GOLD, facts, SpyglassTheme.ACCENT_GOLD);
		}
		if (unread == d.trackedTasks().size())
		{
			facts.add(loggedIn ? "Read on a members' world" : "Log in to read them");
			return new Card("Not read yet", null, facts, null);
		}
		facts.add("Resets in " + com.osrstelemetry.plugin.farming.FarmingText.remaining(Dailies.untilReset(now)));
		return new Card(unread > 0 ? "Nothing to claim · " + unread + " not read" : "All claimed", null, facts, null);
	}

	/** A checklist row's right-hand status: "To claim", "Claimed", "9 left", "Not read", "Locked". */
	static String dailyStatus(Dailies.Status s)
	{
		switch (s.getState())
		{
			case AVAILABLE:
				return s.getUses() > 0 ? s.getRemaining() + " left" : "To claim";
			case DONE:
				return "Claimed";
			case NEEDS:
				return "Needs points";
			case LOCKED:
				return "Locked";
			case NOT_READ:
			default:
				return "Not read";
		}
	}

	/** The Kingdom card's hover detail: what the shown values are worked out from. */
	static String calculatedTip(Kingdom k, long now)
	{
		return "Worked out for today from what the game showed " + daysAgo(k.daysSinceVisit(now))
			+ " (coffer " + String.format(Locale.ROOT, "%,d", k.getCoffer()) + ", approval " + Kingdom.percent(k.getApproval()) + "%) by the daily rules.";
	}

	// -- dailies & weeklies (pass 29; bird houses moved to their own card in pass 30) -----------------------------------------

	/**
	 * Home's Dailies &amp; Weeklies card: what can be done now across the chosen daily claims and weekly activities (gold), else
	 * when the next thing comes back. Daily uses are allowances, never counted as left undone; bird houses have their own card.
	 */
	static Card recurringCard(Dailies d, com.osrstelemetry.plugin.home.Recurring rec, boolean loggedIn, long now)
	{
		List<String> facts = new ArrayList<>();
		boolean dailies = d.isChosen() && !d.trackedTasks().isEmpty();
		boolean tears = rec.isShown(com.osrstelemetry.plugin.home.Recurring.Entry.TEARS);
		if (!dailies && !tears)
		{
			if (!rec.shownIn(com.osrstelemetry.plugin.home.Recurring.Group.DAILY_USES).isEmpty())
			{
				facts.add("Daily uses on the page");
				return new Card("Nothing to claim", null, facts, null);
			}
			return new Card("Choose what to track", null, facts, null);
		}
		List<String> ready = new ArrayList<>();
		int unread = 0;
		int tracked = 0;
		for (DailyTask t : d.trackedTasks())
		{
			Dailies.Status s = d.status(t, now);
			tracked++;
			if (s.getState() == Dailies.State.AVAILABLE)
			{
				ready.add(t.getTitle());
			}
			unread += s.getState() == Dailies.State.NOT_READ ? 1 : 0;
		}
		if (tears)
		{
			tracked++;
			com.osrstelemetry.plugin.home.TearsOfGuthix.Status t = rec.tears().status(now);
			if (t.getState() == com.osrstelemetry.plugin.home.TearsOfGuthix.State.READY)
			{
				ready.add("Tears of Guthix");
			}
			unread += t.getState() == com.osrstelemetry.plugin.home.TearsOfGuthix.State.NOT_READ ? 1 : 0;
		}
		if (!ready.isEmpty())
		{
			// The count and the icons are the same list (pass 31).
			List<Badge> badges = readyBadges(d, rec, now);
			facts.add(ready.size() == 1 ? ready.get(0) : ready.get(0) + " and " + (ready.size() - 1) + " more");
			return new Card(badges.size() + " ready", SpyglassTheme.ACCENT_GOLD, facts, SpyglassTheme.ACCENT_GOLD, null, badges);
		}
		if (unread == tracked)
		{
			facts.add(loggedIn ? "Read on a members' world" : "Log in to read them");
			return new Card("Not read yet", null, facts, null);
		}
		if (dailies)
		{
			facts.add("Dailies reset in " + com.osrstelemetry.plugin.farming.FarmingText.remaining(Dailies.untilReset(now)));
		}
		return new Card(unread > 0 ? "Nothing ready · " + unread + " not read" : "Nothing ready", null, facts.subList(0, Math.min(2, facts.size())), null);
	}

	/**
	 * Home's Bird houses card, from the spaces as last read and the run (if any). Ready houses lead in gold; otherwise when the
	 * next one is full; nothing read is "Not seen yet", never empty spaces or a guessed timer.
	 */
	static Card birdhouseCard(com.osrstelemetry.plugin.hunter.BirdhouseTraps.Summary s, com.osrstelemetry.plugin.hunter.BirdhouseRun run,
		int[] runCounts, long now)
	{
		List<String> facts = new ArrayList<>();
		if (run != null && run.isActive())
		{
			if (run.isPreparing())
			{
				facts.add("Preparing a run");
				return new Card("Run started", SpyglassTheme.ACCENT_GOLD, facts, SpyglassTheme.ACCENT_GOLD);
			}
			int set = runCounts == null ? 0 : runCounts[0];
			int of = runCounts == null ? run.getSpaces().size() : runCounts[2] - runCounts[1];
			facts.add(set + " of " + of + " set");
			return new Card("Run in progress", SpyglassTheme.ACCENT_GOLD, facts, SpyglassTheme.ACCENT_GOLD);
		}
		if (s.unseen == 4)
		{
			facts.add("Read on Fossil Island");
			return new Card("Not seen yet", null, facts, null);
		}
		if (s.ready > 0)
		{
			if (s.maybeFull > 0)
			{
				facts.add(s.maybeFull + " more may be full");
			}
			if (s.catching > 0)
			{
				facts.add((s.catching == 1 ? "1 more full " : s.catching + " more full ") + inTime(s.nextReadyAt - now));
			}
			else if (s.needsSetup > 0)
			{
				facts.add(s.needsSetup + " to set up");
			}
			return new Card(s.ready + " ready", SpyglassTheme.ACCENT_GOLD, facts.subList(0, Math.min(2, facts.size())), SpyglassTheme.ACCENT_GOLD);
		}
		if (s.maybeFull > 0)
		{
			// Seeded before Spyglass saw them, with no reading of when (pass 31): never a countdown that may be a year late.
			facts.add("Seeded before Spyglass saw " + (s.maybeFull == 1 ? "it" : "them"));
			if (s.catching > 0)
			{
				facts.add((s.catching == 1 ? "1 more full " : s.catching + " more full ") + inTime(s.nextReadyAt - now));
			}
			return new Card(s.maybeFull + " may be full", SpyglassTheme.ACCENT_GOLD, facts, SpyglassTheme.ACCENT_GOLD);
		}
		if (s.catching > 0)
		{
			String next = "Full " + inTime(s.nextReadyAt - now);
			if (s.catching > 1 && s.allReadyAt > s.nextReadyAt + 60_000L)
			{
				facts.add("All " + s.catching + " full " + inTime(s.allReadyAt - now));
			}
			if (s.needsSetup > 0)
			{
				facts.add(s.needsSetup + " to set up");
			}
			if (s.unseen > 0)
			{
				facts.add(s.unseen + " not seen yet");
			}
			return new Card(next, null, facts.subList(0, Math.min(2, facts.size())), null);
		}
		if (s.needsSetup > 0)
		{
			if (s.unseen > 0)
			{
				facts.add(s.unseen + " not seen yet");
			}
			return new Card(s.needsSetup + " to set up", null, facts, null);
		}
		facts.add(s.unseen + " not seen yet");
		return new Card("Nothing ready", null, facts, null);
	}

	/** "in ~23m" (a timer from what was seen). */
	private static String inTime(long millis)
	{
		return com.osrstelemetry.plugin.farming.FarmingText.remaining(Math.max(0, millis)).replace("~", "in ~");
	}

	/** Tears of Guthix's right-hand status: "Ready", "in ~2d 4h", "Needs XP", "Not read", "Locked". */
	static String tearsStatus(com.osrstelemetry.plugin.home.TearsOfGuthix.Status s, long now)
	{
		switch (s.getState())
		{
			case READY:
				return "Ready";
			case COUNTING_DOWN:
				return s.getOpensAt() > now ? com.osrstelemetry.plugin.farming.FarmingText.remaining(s.getOpensAt() - now).replace("~", "in ~") : "Soon";
			case NEEDS_REQUIREMENT:
				return Boolean.FALSE.equals(s.getRequirementMet()) ? "Needs XP" : "Needs check";
			case LOCKED:
				return "Locked";
			case UNKNOWN_DATE:
				return "Date not read";
			case NOT_READ:
			default:
				return "Not read";
		}
	}

	/** The bird houses' status: "4 ready", "Full in ~23m", "2 to set up", "Not seen yet". */
	static String birdhouseStatus(com.osrstelemetry.plugin.hunter.BirdhouseTraps.Summary s, long now)
	{
		if (s.ready > 0 && s.catching == 0)
		{
			return s.ready == 1 ? "1 ready" : s.ready + " ready";
		}
		if (s.catching > 0)
		{
			return (s.ready > 0 ? s.ready + " ready · " : "") + "full " + com.osrstelemetry.plugin.farming.FarmingText.remaining(s.allReadyAt - now).replace("~", "in ~");
		}
		if (s.needsSetup > 0)
		{
			return s.needsSetup + " to set up";
		}
		return "Not seen yet";
	}

	/** A daily use's right-hand status: "30 left", "1 of 2 left", "Not read", "Locked". */
	static String dailyUseStatus(com.osrstelemetry.plugin.home.DailyUses.Status s)
	{
		if (s.isLocked())
		{
			return "Locked";
		}
		if (!s.isKnown())
		{
			return "Not read";
		}
		return s.getLimit() > 1 && s.getLimit() < 10 ? s.getLeft() + " of " + s.getLimit() + " left" : s.getLeft() + " left";
	}
}
