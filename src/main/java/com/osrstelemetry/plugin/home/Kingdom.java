package com.osrstelemetry.plugin.home;

import java.util.ArrayList;
import java.util.List;

/**
 * The Kingdom of Miscellania as the game last showed it (canonical Home §10): immutable, saved per RuneScape profile as JSON
 * (field names are the format).
 *
 * <p>What is read, and when it counts: Throne of Miscellania done (varp {@code MISC_QUEST} above 0) and Royal Trouble's state
 * are read anywhere. The coffer ({@code MISC_COFFERS}) and approval ({@code MISC_APPROVAL}, 0 to 127) are recorded only while
 * the player is on the islands (regions 10044 and 10300): only then are they a current reading, and that visit is the one the
 * 30-day rule counts. Away from the islands the last reading is shown as what it is, with its age, never as live.
 *
 * <p>Verified mechanics (OSRS Wiki, "Managing Miscellania", read 2026-10-28): payment is taken from the coffer and resources
 * are collected at midnight UTC every day (since 6 September 2023); the daily payment is 10% of the coffer, up to 50,000
 * (75,000 after Royal Trouble); the coffer holds up to 5,000,000 (7,500,000); approval falls by 2.5% a day (1% after Royal
 * Trouble), shown as a percentage of 127 points, from 25% to 100%; after 30 days without a visit collection and payment
 * pause until the next one. The estimate between visits applies exactly those rules (the percentage as RuneLite's Kingdom
 * plugin shows it, points × 100 / 127, BSD-2); it is shown plainly, with the reading's age beside it (pass 30: no "~"). Reminders: approval strictly under
 * 90%, a coffer that can no longer pay the full daily maximum (under ten times it), a visit due before the 30-day pause; the
 * login warning repeats them at most once an hour per profile. Worker allocation has no verified reading (the management
 * screen's bars are drawn by the server; the {@code MISC_POINTS_*} variables match its six categories by name only), so it is
 * never shown or guessed.
 */
public final class Kingdom
{
	public static final int MAX_APPROVAL = 127;
	public static final int MAX_PAYMENT = 50_000;
	public static final int MAX_PAYMENT_ROYAL = 75_000;
	public static final int PAUSE_DAYS = 30;
	/** A reminder when approval is strictly below this percentage (agreed with Josh, 2026-10-28). */
	public static final int APPROVAL_REMINDER_PERCENT = 90;
	/** The login warning is not repeated within this long (saved per profile, so relogging, hopping or restarting can't bypass it). */
	public static final long WARNING_COOLDOWN_MILLIS = 3_600_000L;
	/** A visit reminder this many days after the last visit (before collection pauses). */
	public static final int VISIT_REMINDER_DAYS = 25;

	/** Field names are the saved JSON. */
	private Boolean questDone;
	private Boolean royalTrouble;
	/** The last reading on the islands (epoch ms; null: never). */
	private Long seenAt;
	private int coffer;
	private int approval;
	/** When the login warning last fired (epoch ms; null: never). */
	private Long warnedAt;

	public static Kingdom empty()
	{
		return new Kingdom();
	}

	private Kingdom copy()
	{
		Kingdom k = new Kingdom();
		k.questDone = questDone;
		k.royalTrouble = royalTrouble;
		k.seenAt = seenAt;
		k.coffer = coffer;
		k.approval = approval;
		k.warnedAt = warnedAt;
		return k;
	}

	/**
	 * This state with a read applied (the same instance when nothing to save changed). {@code onIslands}: the player stands in
	 * the kingdom now, so the coffer and approval are a reading.
	 */
	public Kingdom with(boolean questDone, boolean royalTrouble, boolean onIslands, int coffer, int approval, long now)
	{
		boolean reading = questDone && onIslands;
		if (Boolean.valueOf(questDone).equals(this.questDone) && Boolean.valueOf(royalTrouble).equals(this.royalTrouble)
			&& (!reading || this.seenAt != null && this.coffer == coffer && this.approval == approval
			&& Math.floorDiv(this.seenAt, Dailies.DAY_MILLIS) == Math.floorDiv(now, Dailies.DAY_MILLIS)))
		{
			return this;
		}
		Kingdom k = copy();
		k.questDone = questDone;
		k.royalTrouble = royalTrouble;
		if (reading)
		{
			k.seenAt = now;
			k.coffer = coffer;
			k.approval = approval;
		}
		return k;
	}

	/** Null: not read yet (logged out since install). */
	public Boolean getQuestDone()
	{
		return questDone;
	}

	public boolean isRoyalTrouble()
	{
		return Boolean.TRUE.equals(royalTrouble);
	}

	public Long getSeenAt()
	{
		return seenAt;
	}

	public int getCoffer()
	{
		return coffer;
	}

	public int getApproval()
	{
		return approval;
	}

	public static int percent(int points)
	{
		return points * 100 / MAX_APPROVAL;
	}

	public int maxPayment()
	{
		return isRoyalTrouble() ? MAX_PAYMENT_ROYAL : MAX_PAYMENT;
	}

	/** Midnights (UTC) since the reading that took payment: at most {@link #PAUSE_DAYS} (collection pauses after that). */
	public int paidDaysSince(long now)
	{
		if (seenAt == null)
		{
			return 0;
		}
		long days = Math.floorDiv(now, Dailies.DAY_MILLIS) - Math.floorDiv(seenAt, Dailies.DAY_MILLIS);
		return (int) Math.max(0, Math.min(PAUSE_DAYS, days));
	}

	/** Whole days since the last visit (0: today). */
	public int daysSinceVisit(long now)
	{
		return seenAt == null ? -1 : (int) Math.max(0, Math.floorDiv(now - seenAt, Dailies.DAY_MILLIS));
	}

	/** The coffer by the verified daily payment rule, from the last reading. */
	public int estimatedCoffer(long now)
	{
		int c = coffer;
		int max = maxPayment();
		for (int i = paidDaysSince(now); i > 0; i--)
		{
			c -= c > 10 * max ? max : c / 10;
		}
		return c;
	}

	/** Approval points by the verified daily fall, from the last reading (never under 25%). */
	public int estimatedApproval(long now)
	{
		float perDay = isRoyalTrouble() ? 0.010f : 0.025f;
		int a = approval - (int) (paidDaysSince(now) * perDay * MAX_APPROVAL);
		return Math.max(a, (MAX_APPROVAL + 3) / 4);
	}

	public boolean isCollectionPaused(long now)
	{
		return seenAt != null && now - seenAt >= PAUSE_DAYS * Dailies.DAY_MILLIS;
	}

	/**
	 * The coffer pays the full daily maximum only while it holds at least ten times that maximum: the payment is 10% of the
	 * coffer, up to 50,000 (75,000 after Royal Trouble) (OSRS Wiki, Managing Miscellania). Below it every day pays less, and
	 * fewer resources are collected.
	 */
	public int fullPaymentCoffer()
	{
		return 10 * maxPayment();
	}

	/** Approval to judge now: the reading on the islands (live), else the estimate from it; null when never read. */
	public Integer approvalNow(boolean live, long now)
	{
		if (!Boolean.TRUE.equals(questDone) || seenAt == null)
		{
			return null;
		}
		return live ? approval : estimatedApproval(now);
	}

	public Integer cofferNow(boolean live, long now)
	{
		if (!Boolean.TRUE.equals(questDone) || seenAt == null)
		{
			return null;
		}
		return live ? coffer : estimatedCoffer(now);
	}

	/** Approval strictly below {@link #APPROVAL_REMINDER_PERCENT}; never from an unread value. */
	public boolean approvalLow(boolean live, long now)
	{
		Integer a = approvalNow(live, now);
		return a != null && percent(a) < APPROVAL_REMINDER_PERCENT;
	}

	/** The coffer can no longer pay the full daily maximum; never from an unread value. */
	public boolean cofferLow(boolean live, long now)
	{
		Integer c = cofferNow(live, now);
		return c != null && c < fullPaymentCoffer();
	}

	/** The first reminder in a few words, for Home's card ("Approval 82%", "Coffer low", "Visit within 4 days"), or null. */
	public String shortReminder(long now)
	{
		return shortReminder(false, now);
	}

	public String shortReminder(boolean live, long now)
	{
		List<String> r = reminders(live, now);
		if (r.isEmpty())
		{
			return null;
		}
		String first = r.get(0);
		if (first.startsWith("Collection paused"))
		{
			return "Collection paused";
		}
		if (first.startsWith("Visit within"))
		{
			return first.substring(0, first.indexOf(" to keep"));
		}
		if (first.startsWith("Approval"))
		{
			return first.substring(0, first.indexOf(':'));
		}
		return "Coffer low";
	}

	public List<String> reminders(long now)
	{
		return reminders(false, now);
	}

	/** What needs attention, most pressing first (empty: nothing, or nothing known). Calculated values read plainly. */
	public List<String> reminders(boolean live, long now)
	{
		List<String> out = new ArrayList<>();
		if (!Boolean.TRUE.equals(questDone) || seenAt == null)
		{
			return out;
		}
		if (isCollectionPaused(now) && !live)
		{
			out.add("Collection paused: visit Miscellania");
		}
		else if (!live && daysSinceVisit(now) >= VISIT_REMINDER_DAYS)
		{
			int left = PAUSE_DAYS - daysSinceVisit(now);
			out.add("Visit within " + left + (left == 1 ? " day" : " days") + " to keep collecting");
		}
		String mark = "";
		if (approvalLow(live, now))
		{
			out.add("Approval " + mark + percent(approvalNow(live, now)) + "%: under " + APPROVAL_REMINDER_PERCENT + "%");
		}
		if (cofferLow(live, now))
		{
			out.add("Coffer " + mark + coins(cofferNow(live, now)) + ": can't pay the full " + coins(maxPayment()) + " a day");
		}
		return out;
	}

	static String coins(int n)
	{
		if (n >= 1_000_000)
		{
			return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
		}
		if (n >= 10_000)
		{
			return (n / 1000) + "K";
		}
		return String.format(java.util.Locale.ROOT, "%,d", n);
	}

	/** The login warning's text, or null when nothing warrants it (approval below the threshold, or a coffer that can't pay in full). */
	public String loginWarning(boolean live, long now)
	{
		boolean approval = approvalLow(live, now);
		boolean money = cofferLow(live, now);
		if (!approval && !money)
		{
			return null;
		}
		String mark = "";
		List<String> parts = new ArrayList<>();
		if (approval)
		{
			parts.add("approval " + mark + percent(approvalNow(live, now)) + "%");
		}
		if (money)
		{
			parts.add("the coffer (" + mark + coins(cofferNow(live, now)) + ") can't pay the full " + coins(maxPayment()) + " a day");
		}
		return "Kingdom of Miscellania: " + String.join("; ", parts) + ".";
	}

	/** One thing to do for the kingdom, in a few words, with how (pass 31: the page and the overlay show these, not sentences). */
	public static final class Action
	{
		public final String title;
		public final String how;

		Action(String title, String how)
		{
			this.title = title;
			this.how = how;
		}

		@Override
		public String toString()
		{
			return title + " · " + how;
		}
	}

	/**
	 * What to do, most pressing first, from the same rules as {@link #reminders} (never from an unread value). The values
	 * themselves are shown once, beside the bar and the coins, so these never repeat them.
	 */
	public List<Action> actions(boolean live, long now)
	{
		List<Action> out = new ArrayList<>();
		if (!Boolean.TRUE.equals(questDone) || seenAt == null)
		{
			return out;
		}
		if (isCollectionPaused(now) && !live)
		{
			out.add(new Action("Visit the kingdom", "Collection paused after 30 days away; a visit resumes it"));
		}
		else if (!live && daysSinceVisit(now) >= VISIT_REMINDER_DAYS)
		{
			int left = PAUSE_DAYS - daysSinceVisit(now);
			out.add(new Action("Visit within " + left + (left == 1 ? " day" : " days"), "Collection pauses after 30 days away"));
		}
		if (approvalLow(live, now))
		{
			out.add(new Action("Raise approval", "Work on the islands: chop, mine, fish or rake. Under " + APPROVAL_REMINDER_PERCENT + "% collects less"));
		}
		if (cofferLow(live, now))
		{
			out.add(new Action("Top up coffer", "Deposit coins with Advisor Ghrim: " + coins(fullPaymentCoffer()) + " pays the full " + coins(maxPayment())
				+ " a day"));
		}
		return out;
	}

	/**
	 * How full to draw the coffer's coins, from the balance against what pays the full daily maximum: 0 empty, 1 a little,
	 * 2 some, 3 nearly enough, 4 enough (the game's own coin stacks, smallest to largest).
	 */
	public static int cofferLevel(int coins, int fullPayment)
	{
		if (coins <= 0)
		{
			return 0;
		}
		if (fullPayment <= 0 || coins >= fullPayment)
		{
			return 4;
		}
		return coins < fullPayment / 10 ? 1 : coins < fullPayment / 2 ? 2 : 3;
	}

	/** The coin count whose stack the game draws for that level (coins' stack sizes: 100, 250, 1,000, 10,000). */
	public static int cofferStackQuantity(int level)
	{
		switch (level)
		{
			case 1:
				return 100;
			case 2:
				return 250;
			case 3:
				return 1000;
			case 4:
				return 10_000;
			default:
				return 1;
		}
	}

	/** A warning fired within the last hour (the cooldown is saved with the profile). */
	public boolean isWarningCoolingDown(long now)
	{
		return warnedAt != null && now - warnedAt < WARNING_COOLDOWN_MILLIS && now >= warnedAt;
	}

	public Kingdom withWarned(long now)
	{
		Kingdom k = copy();
		k.warnedAt = now;
		return k;
	}

	public Long getWarnedAt()
	{
		return warnedAt;
	}
}
