package com.osrstelemetry.plugin.farming;

/**
 * Player-facing time text without false precision (canonical §18.4): never
 * seconds; remaining time in whole minutes below an hour and in 10-minute
 * steps above, always with {@code ~}; ages rounded down.
 */
public final class FarmingText
{
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60 * MINUTE;
	private static final long DAY = 24 * HOUR;

	private FarmingText()
	{
	}

	/** "~12m", "~1h 40m", "~2d 4h". Rounded UP, so a crop is never promised early. */
	public static String remaining(long millis)
	{
		long minutes = Math.max(1, (millis + MINUTE - 1) / MINUTE);
		if (minutes < 60)
		{
			return "~" + minutes + "m";
		}
		long tens = (minutes + 9) / 10 * 10;
		long hours = tens / 60;
		long mins = tens % 60;
		if (hours >= 24)
		{
			long days = hours / 24;
			long h = hours % 24 + (mins > 0 ? 1 : 0);
			if (h == 24)
			{
				days++;
				h = 0;
			}
			return "~" + days + "d" + (h > 0 ? " " + h + "h" : "");
		}
		return "~" + hours + "h" + (mins > 0 ? " " + mins + "m" : "");
	}

	/** "just now", "4m", "1h 34m", "1d", "16d". Rounded down. */
	public static String age(long millis)
	{
		if (millis < MINUTE)
		{
			return "just now";
		}
		if (millis < HOUR)
		{
			return millis / MINUTE + "m";
		}
		if (millis < DAY)
		{
			long h = millis / HOUR;
			long m = millis % HOUR / MINUTE;
			return h + "h" + (m > 0 ? " " + m + "m" : "");
		}
		return millis / DAY + "d";
	}

	/** "Planted 1h 34m ago", "Seen just now", ... for a {@link PatchStatus}. */
	public static String provenance(PatchStatus s, long now)
	{
		long ago = Math.max(0, now - s.getProvenanceAt());
		String when = ago < MINUTE ? "just now" : age(ago) + " ago";
		switch (s.getProvenance())
		{
			case SEEN_JUST_NOW:
				return "Seen just now";
			case PLANTED:
				return "Planted " + when;
			case SEEN_GROWING:
				return "Seen growing " + when;
			case LAST_SEEN_DISEASED:
				return "Last seen diseased " + when;
			case LAST_SEEN_DEAD:
				return "Last seen dead " + when;
			case LAST_SEEN:
				return "Last seen " + when;
			case NONE:
			default:
				return "Visit once to start tracking";
		}
	}

	/**
	 * The patch's current condition in one short line, keeping what was seen
	 * apart from what is timed: "Ready · seen just now" (observed), "Ready"
	 * (timed; the source stays in PatchStatus), "Growing · ready in ~40m", "Diseased · last seen 2h ago",
	 * "Not seen yet".
	 */
	public static String condition(PatchStatus s, long now)
	{
		long ago = Math.max(0, now - s.getLastSeenAt());
		String seen = ago < MINUTE ? "seen just now" : "seen " + age(ago) + " ago";
		switch (s.getDisplay())
		{
			case READY:
				return s.getReadySource() == PatchStatus.ReadySource.ESTIMATED ? "Ready" : "Ready \u00b7 " + seen;
			case GROWING_TIMED:
				return "Growing \u00b7 ready in " + remaining(s.getReadyAt() - now);
			case GROWING:
				return "Growing \u00b7 " + seen;
			case GROWN:
				return "Grown \u00b7 " + seen;
			case DISEASED:
				return "Diseased \u00b7 " + seen;
			case DEAD:
				return "Dead \u00b7 " + seen;
			case CLEAR:
				return "Empty \u00b7 " + seen;
			case CHECK_PATCH:
				return "Couldn't read it \u00b7 " + seen;
			case NOT_TRACKED:
			default:
				return "Not seen yet";
		}
	}
}
