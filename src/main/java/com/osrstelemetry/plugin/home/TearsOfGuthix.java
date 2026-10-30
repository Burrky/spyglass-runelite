package com.osrstelemetry.plugin.home;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tears of Guthix, the weekly minigame (OSRS Wiki, "Tears of Guthix (minigame)" and "Transcript:Juna", read 2026-10-28): after
 * the quest, Juna lets a player in again from the start of the seventh day after their last game (a 2015 change: the UTC day,
 * not seven times 24 hours from the minute; there is no common weekly reset day), and only when they have gained at least one
 * quest point or 100,000 total XP since that game. Someone who has never played may go at once. Immutable; saved per
 * RuneScape profile (field names are the JSON).
 *
 * <h3>Native evidence</h3>
 * <ul>
 *   <li>Quest: RuneLite's quest state for Tears of Guthix.</li>
 *   <li>{@code tog_qp_before_return} (varp {@code tog_minigame} 449, bits 22–31): the quest points at the last game; against the
 *   quest points now ({@code qp}, varp 101: the game's own side panel reads it), one quest point gained settles the rule.
 *   {@code tog_tears_collected} (bits 12–20): the last game's tears. Both zero: never played.</li>
 *   <li>{@code tog_minigame_collecting} (bit 6): set while collecting in the cave. Seen going from 1 to 0, the game just ended:
 *   the next one is from the start of the seventh UTC day after, and its XP mark is the total XP read then plus 100,000
 *   (read a few ticks later, after the reward; never earlier, so the mark can only be high, never low).</li>
 *   <li>Juna's own words when the player talks to her (dialogue text, read, never answered for them): "Come back tomorrow /
 *   in N days" (the day the cave opens), the narration "…until you have gained either one quest point or N total XP" (the XP
 *   still due), "Your stories have entertained me" (eligible now).</li>
 * </ul>
 * A game played where Spyglass did not see it (another client) shows as a change in those two values at login: the saved
 * date and mark are then dropped, never kept as if still true. Time passing alone never makes it ready: an unmet or unread
 * requirement keeps it not ready, and an unread date says so.
 */
public final class TearsOfGuthix
{
	public static final long DAY_MILLIS = 86_400_000L;
	public static final long XP_NEEDED = 100_000L;

	public enum State
	{
		/** Nothing read yet. */
		NOT_READ,
		/** The quest is not done. */
		LOCKED,
		/** Juna will let the player in now. */
		READY,
		/** The cave opens on a known day. */
		COUNTING_DOWN,
		/** The day has come, but the quest point or XP is not there yet. */
		NEEDS_REQUIREMENT,
		/** When it opens is not known (no game seen, Juna not asked). */
		UNKNOWN_DATE
	}

	/** One read of the game (client thread). */
	public static final class Read
	{
		final boolean questDone;
		final int qpNow;
		final int qpBefore;
		final int tearsLast;
		final boolean collecting;
		final long totalXp;

		public Read(boolean questDone, int qpNow, int qpBefore, int tearsLast, boolean collecting, long totalXp)
		{
			this.questDone = questDone;
			this.qpNow = qpNow;
			this.qpBefore = qpBefore;
			this.tearsLast = tearsLast;
			this.collecting = collecting;
			this.totalXp = totalXp;
		}
	}

	private Boolean questDone;
	private Integer qpNow;
	private Integer qpBefore;
	private Integer tearsLast;
	private Long totalXp;
	private boolean collecting;
	/** Start of the UTC day the cave opens (null: not known). */
	private Long opensAt;
	/** Total XP needed when no quest point has been gained (null: not known). */
	private Long xpMark;
	/** A game just ended: the XP mark waits for the reward (the first read at least this late sets it). */
	private Long markAfter;
	/** Juna said "Your stories have entertained me" at this time (eligible then). */
	private Long entertainedAt;
	private Long readAt;
	/** The opening day last notified (dedupes the optional notification). */
	private Long notifiedFor;

	public static TearsOfGuthix empty()
	{
		return new TearsOfGuthix();
	}

	private TearsOfGuthix copy()
	{
		TearsOfGuthix t = new TearsOfGuthix();
		t.questDone = questDone;
		t.qpNow = qpNow;
		t.qpBefore = qpBefore;
		t.tearsLast = tearsLast;
		t.totalXp = totalXp;
		t.collecting = collecting;
		t.opensAt = opensAt;
		t.xpMark = xpMark;
		t.markAfter = markAfter;
		t.entertainedAt = entertainedAt;
		t.readAt = readAt;
		t.notifiedFor = notifiedFor;
		return t;
	}

	static long dayStart(long t)
	{
		return Math.floorDiv(t, DAY_MILLIS) * DAY_MILLIS;
	}

	/**
	 * This state with a read applied ({@code login}: the first read of a login, reconciling saved values with the game). The
	 * same instance when nothing worth saving changed.
	 */
	public TearsOfGuthix with(Read r, boolean login, long now)
	{
		TearsOfGuthix t = copy();
		boolean playedElsewhere = login && qpBefore != null && tearsLast != null && (qpBefore != r.qpBefore || tearsLast != r.tearsLast);
		boolean ended = collecting && !r.collecting && !login;
		if (ended)
		{
			// The game just ended: the cave opens at the start of the seventh UTC day after; the XP mark after the reward.
			t.opensAt = dayStart(now) + 7 * DAY_MILLIS;
			t.xpMark = null;
			t.markAfter = now + 6_000L;
			t.entertainedAt = null;
		}
		else if (playedElsewhere)
		{
			t.opensAt = null;
			t.xpMark = null;
			t.markAfter = null;
			t.entertainedAt = null;
		}
		if (t.markAfter != null && now >= t.markAfter)
		{
			t.xpMark = r.totalXp + XP_NEEDED;
			t.markAfter = null;
		}
		t.questDone = r.questDone;
		t.qpNow = r.qpNow;
		t.qpBefore = r.qpBefore;
		t.tearsLast = r.tearsLast;
		t.collecting = r.collecting;
		t.totalXp = r.totalXp;
		boolean changed = ended || playedElsewhere || !java.util.Objects.equals(questDone, t.questDone) || !java.util.Objects.equals(qpNow, t.qpNow)
			|| !java.util.Objects.equals(qpBefore, t.qpBefore) || !java.util.Objects.equals(tearsLast, t.tearsLast) || collecting != t.collecting
			|| !java.util.Objects.equals(xpMark, t.xpMark) || !java.util.Objects.equals(markAfter, t.markAfter)
			|| totalXp == null || Math.abs(totalXp - t.totalXp) >= 1000 || readAt == null || now - readAt > 3_600_000L;
		if (!changed)
		{
			return this;
		}
		t.readAt = now;
		return t;
	}

	static final Pattern COME_BACK = Pattern.compile("Come (?:back|again) (tomorrow|in (\\d+) days)");
	static final Pattern XP_DUE = Pattern.compile("gained either one quest point or ([\\d,]+) total XP");

	/**
	 * Juna's words (or the narration) as shown in the dialogue, with the total XP now: the day the cave opens, the XP still
	 * due, or that the player may enter. The same instance when the text says none of these.
	 */
	public TearsOfGuthix withDialogue(String text, long totalXpNow, long now)
	{
		if (text == null)
		{
			return this;
		}
		String m = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		TearsOfGuthix t = null;
		Matcher c = COME_BACK.matcher(m);
		if (c.find())
		{
			long days = "tomorrow".equals(c.group(1)) ? 1 : Long.parseLong(c.group(2));
			long opens = dayStart(now) + days * DAY_MILLIS;
			if (!Long.valueOf(opens).equals(opensAt))
			{
				t = copy();
				t.opensAt = opens;
			}
		}
		else if (m.contains("Come again when you have had more adventures") || m.contains("I will not let you into the cave again until you have had more adventures"))
		{
			// The day has come (or there is no day to wait); only the requirement is missing.
			if (opensAt == null || opensAt > now)
			{
				t = copy();
				t.opensAt = dayStart(now);
			}
		}
		Matcher x = XP_DUE.matcher(m);
		if (x.find())
		{
			long mark = totalXpNow + Long.parseLong(x.group(1).replace(",", ""));
			if (!Long.valueOf(mark).equals(xpMark))
			{
				t = t == null ? copy() : t;
				t.xpMark = mark;
				t.markAfter = null;
			}
		}
		if (m.contains("Your stories have entertained me"))
		{
			t = t == null ? copy() : t;
			t.entertainedAt = now;
			t.opensAt = dayStart(now);
		}
		return t == null ? this : t;
	}

	/** Its state now, with a short reason. */
	public static final class Status
	{
		private final State state;
		private final String detail;
		/** COUNTING_DOWN: when the cave opens. */
		private final long opensAt;
		/** The requirement: true met, false not met, null not known. */
		private final Boolean requirementMet;
		private final long xpLeft;

		Status(State state, String detail, long opensAt, Boolean requirementMet, long xpLeft)
		{
			this.state = state;
			this.detail = detail;
			this.opensAt = opensAt;
			this.requirementMet = requirementMet;
			this.xpLeft = xpLeft;
		}

		public State getState()
		{
			return state;
		}

		public String getDetail()
		{
			return detail;
		}

		public long getOpensAt()
		{
			return opensAt;
		}

		public Boolean getRequirementMet()
		{
			return requirementMet;
		}

		/** XP still to gain (when known and unmet); 0 otherwise. */
		public long getXpLeft()
		{
			return xpLeft;
		}
	}

	public boolean neverPlayed()
	{
		return qpBefore != null && tearsLast != null && qpBefore == 0 && tearsLast == 0;
	}

	/** The requirement (one quest point or 100,000 XP since the last game): true, false, or null when not known. */
	Boolean requirementMet()
	{
		if (neverPlayed() || entertainedAt != null)
		{
			return true;
		}
		if (qpNow != null && qpBefore != null && qpNow > qpBefore)
		{
			return true;
		}
		if (xpMark != null && totalXp != null)
		{
			return totalXp >= xpMark;
		}
		return null;
	}

	public Status status(long now)
	{
		if (questDone == null)
		{
			return new Status(State.NOT_READ, "Log in to read it", 0, null, 0);
		}
		if (!questDone)
		{
			return new Status(State.LOCKED, "Needs the Tears of Guthix quest", 0, null, 0);
		}
		if (collecting)
		{
			return new Status(State.COUNTING_DOWN, "In the cave now", dayStart(now) + 7 * DAY_MILLIS, null, 0);
		}
		if (neverPlayed())
		{
			return new Status(State.READY, "Never played: Juna lets you in", 0, true, 0);
		}
		Boolean met = requirementMet();
		long xpLeft = xpMark != null && totalXp != null && Boolean.FALSE.equals(met) ? Math.max(0, xpMark - totalXp) : 0;
		String need = Boolean.TRUE.equals(met) ? null : Boolean.FALSE.equals(met) ? "Needs 1 quest point or " + xp(xpLeft) + " more XP"
			: "Needs 1 quest point or 100K XP since your last game (talk to Juna to read it)";
		if (opensAt == null)
		{
			return new Status(State.UNKNOWN_DATE, "Next game not read: talk to Juna" + (need == null ? "" : " · " + need), 0, met, xpLeft);
		}
		if (now < opensAt)
		{
			return new Status(State.COUNTING_DOWN, need == null ? "Opens at 00:00 UTC" : need, opensAt, met, xpLeft);
		}
		if (!Boolean.TRUE.equals(met))
		{
			return new Status(State.NEEDS_REQUIREMENT, need, opensAt, met, xpLeft);
		}
		return new Status(State.READY, "Juna lets you in", opensAt, true, 0);
	}

	/** "45K", "1.2M", "800". */
	static String xp(long n)
	{
		if (n >= 1_000_000)
		{
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
		}
		if (n >= 1_000)
		{
			return (n + 999) / 1000 + "K";
		}
		return String.valueOf(n);
	}

	public boolean shouldNotify(long now)
	{
		Status s = status(now);
		return s.getState() == State.READY && !neverPlayed() && opensAt != null && !opensAt.equals(notifiedFor);
	}

	public TearsOfGuthix withNotified()
	{
		TearsOfGuthix t = copy();
		t.notifiedFor = opensAt;
		return t;
	}

	public Long getReadAt()
	{
		return readAt;
	}

	public Long getOpensAt()
	{
		return opensAt;
	}
}
