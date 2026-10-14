package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.activity.StructuredActivityDefinition;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.SessionAggregates;
import java.util.Locale;

/**
 * Generic structured-activity completion metric text ("Games: 8",
 * "Games/hr: 11.4"), shared by Current Session and History so both render
 * it identically in their existing secondary lines. No activity-specific
 * wording lives here: the label comes from the activity registry.
 */
public final class CompletionMetricText
{
	/** The one shared rate floor (see {@link com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot#MIN_DURATION_FOR_RATE_MILLIS}). */
	public static final long MIN_DURATION_FOR_RATE_MILLIS = com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot.MIN_DURATION_FOR_RATE_MILLIS;

	private CompletionMetricText()
	{
	}

	/**
	 * The label for a session's completion metric, or null when the session is
	 * not a structured activity with a known completion source.
	 */
	public static String labelFor(ActivityType type, String activityKey)
	{
		if (type != ActivityType.STRUCTURED_ACTIVITY)
		{
			return null;
		}
		StructuredActivityDefinition def = StructuredActivityRegistry.defaults().byKey(activityKey);
		return def == null || !def.hasCompletionSource() ? null : def.getCompletionLabel();
	}

	/** Completions this session (0 when none were observed yet). */
	public static int countOf(SessionAggregates.ActivityCompletionCount completions)
	{
		return completions == null ? 0 : completions.getSessionCompletions();
	}

	/** Null below the rate threshold or with no completions. */
	public static Double perHour(int count, long activeMillis)
	{
		if (count <= 0 || activeMillis < MIN_DURATION_FOR_RATE_MILLIS)
		{
			return null;
		}
		return count * 3_600_000d / activeMillis;
	}

	public static String countText(String label, int count)
	{
		return label + ": " + count;
	}

	/** The activity's native currency label ("Permits"), or null. */
	public static String currencyLabelFor(String activityKey)
	{
		StructuredActivityDefinition def = activityKey == null ? null : StructuredActivityRegistry.defaults().byKey(activityKey);
		return def == null ? null : def.getCurrencyLabel();
	}

	/** Player-facing label of one currency key ("Elemental", "Catalytic", "Permits"), or null when unknown. */
	public static String currencyLabelForKey(String currencyKey)
	{
		if (currencyKey == null)
		{
			return null;
		}
		switch (currencyKey)
		{
			case "elemental_points":
				return "Elemental";
			case "catalytic_points":
				return "Catalytic";
			case "reward_permits":
				return "Permits";
			case "commendation_points":
				return "Commendation points";
			default:
				return null;
		}
	}

	/** The activity's currency keys in display order (empty when it has a single unkeyed currency or none). */
	public static java.util.List<String> currencyKeysFor(String activityKey)
	{
		return com.osrstelemetry.plugin.activity.StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(activityKey)
			? java.util.Arrays.asList("elemental_points", "catalytic_points")
			: java.util.Collections.<String>emptyList();
	}

	/** What Avg / Best measure for the activity (tooltip wording). */
	public static String scoreNoun(String activityKey)
	{
		if (isPestControl(activityKey))
		{
			return "Game time";
		}
		if (isWintertodt(activityKey))
		{
			return "Points";
		}
		return com.osrstelemetry.plugin.activity.StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(activityKey)
			? "Total energy" : "Final points";
	}

	/**
	 * Activities whose History shows a dedicated SESSION block (games, rate,
	 * currencies, Avg/Best) instead of inline header text. Barrows keeps its
	 * accepted header presentation.
	 */
	public static boolean hasHistorySessionBlock(String activityKey)
	{
		return com.osrstelemetry.plugin.activity.StructuredActivityCatalog.TEMPOROSS.equals(activityKey)
			|| com.osrstelemetry.plugin.activity.StructuredActivityCatalog.GUARDIANS_OF_THE_RIFT.equals(activityKey)
			|| isPestControl(activityKey)
			|| isWintertodt(activityKey);
	}

	public static boolean isWintertodt(String activityKey)
	{
		return com.osrstelemetry.plugin.activity.StructuredActivityCatalog.WINTERTODT.equals(activityKey);
	}

	/** History's Wintertodt action breakdown ("Roots fed 120 • Kindling fed 64 • ..."), or null when none. */
	public static String wintertodtActions(java.util.Map<String, Long> counts)
	{
		if (counts == null || counts.isEmpty())
		{
			return null;
		}
		String[][] labels = {
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.ROOTS_FED, "Roots fed"},
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.KINDLING_FED, "Kindling fed"},
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.LIT, "Lit"},
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.REPAIRED, "Repaired"},
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.HEALED, "Healed"},
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.CHOPPED, "Chopped"},
			{com.osrstelemetry.plugin.activity.WintertodtRoundTracker.FLETCHED, "Fletched"}};
		StringBuilder sb = new StringBuilder();
		for (String[] l : labels)
		{
			Long v = counts.get(l[0]);
			if (v != null && v > 0)
			{
				sb.append(sb.length() == 0 ? "" : "   \u2022   ").append(l[1]).append(' ').append(String.format(Locale.ROOT, "%,d", v));
			}
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	public static boolean isPestControl(String activityKey)
	{
		return com.osrstelemetry.plugin.activity.StructuredActivityCatalog.PEST_CONTROL.equals(activityKey);
	}

	/**
	 * Pest Control COMMENDATIONS / HR: points earned per hour of ROUND time
	 * (island time, win or loss), so outpost / lander waiting never dilutes
	 * it. {@code liveRoundMillis} is the round in progress (Current Session
	 * only). Sessions recorded before round time existed fall back to active
	 * time. Null below the shared 5-minute floor or with nothing earned.
	 */
	public static Double commendationsPerHour(long gained, long roundMillis, int roundsTimed, long liveRoundMillis,
		long fallbackActiveMillis)
	{
		long denominator = roundsTimed > 0 || liveRoundMillis > 0 ? roundMillis + Math.max(0L, liveRoundMillis) : fallbackActiveMillis;
		if (gained <= 0 || denominator < MIN_DURATION_FOR_RATE_MILLIS)
		{
			return null;
		}
		return gained * 3_600_000d / denominator;
	}

	/** "2:04" (minutes:seconds), "1:02:04" past an hour. */
	public static String gameTime(long seconds)
	{
		long s = Math.max(0L, seconds);
		return s >= 3600
			? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
			: String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	/** "+11" -- earned this session (never a balance). */
	public static String currencyText(long gained)
	{
		return "+" + String.format(Locale.ROOT, "%,d", gained);
	}

	public static String pointsText(long points)
	{
		return String.format(Locale.ROOT, "%,d", points);
	}

	/**
	 * History's one-line session summary of an activity's stats:
	 * "Permits +11   •   Avg pts 4,920   •   Best pts 5,670" (parts only
	 * when present), or null when there is nothing to say.
	 */
	public static String statsSummary(String activityKey, SessionAggregates.ActivitySessionStats stats)
	{
		if (stats == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		String currency = currencyLabelFor(activityKey);
		if (currency != null && stats.getCurrencyGained() > 0)
		{
			sb.append(currency).append(' ').append(currencyText(stats.getCurrencyGained()));
		}
		Long avg = stats.averageScore();
		if (avg != null && stats.getBestScore() != null)
		{
			if (sb.length() > 0)
			{
				sb.append("   \u2022   ");
			}
			sb.append("Avg pts ").append(pointsText(avg)).append("   \u2022   Best pts ").append(pointsText(stats.getBestScore()));
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	/** The same facts as {@link #summary}, as a wrapping {@link FactLine} ("Games: 8  •  Games/hr: 11.4"). */
	static FactLine factLine(String label, int count, long activeMillis)
	{
		FactLine line = new FactLine(net.runelite.client.ui.FontManager.getRunescapeSmallFont(), SpyglassTheme.TEXT_SECONDARY);
		line.fact(countText(label, count));
		Double rate = perHour(count, activeMillis);
		if (rate != null)
		{
			line.fact(rateLabel(label) + ": " + String.format(Locale.ROOT, "%.1f", rate));
		}
		return line;
	}

	/** "Games: 8" or "Games: 8   •   Games/hr: 11.4". */
	public static String summary(String label, int count, long activeMillis)
	{
		String text = countText(label, count);
		Double rate = perHour(count, activeMillis);
		return rate == null ? text : text + "   •   " + rateLabel(label) + ": " + String.format(Locale.ROOT, "%.1f", rate);
	}

	/**
	 * The rate's label: the completion noun per hour. A qualified label keeps
	 * only its noun ("Games won" -> "Games/hr"), so "Games won/hr" never shows.
	 */
	public static String rateLabel(String label)
	{
		int space = label.indexOf(' ');
		return (space > 0 ? label.substring(0, space) : label) + "/hr";
	}

	/**
	 * The generic completion line plus the activity's single native currency
	 * earned this session ("Games won: 3  •  Games/hr: 9.0  •  Commendation
	 * points: +24"), for activities without a dedicated block.
	 */
	static FactLine factLine(String label, int count, long activeMillis, String currencyLabel, long currencyGained)
	{
		FactLine line = factLine(label, count, activeMillis);
		if (currencyLabel != null)
		{
			line.fact(currencyLabel + ": " + currencyText(currencyGained));
		}
		return line;
	}
}
