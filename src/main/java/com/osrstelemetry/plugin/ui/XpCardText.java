package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * The words a skill card shows, in its order of prominence (pure, tested): XP/hr first and brightest; then the player's chosen
 * second metric, XP remaining to the next level or the time to it; then the quieter level and XP gained. The time to the next
 * level uses the same rate as XP/hr and its five-minute readiness gate: before the rate is shown, or with no usable rate, the
 * card shows XP remaining instead (never an invented time, never infinity). At level 99 there is no next level and no second
 * metric.
 */
final class XpCardText
{
	/** The player's choice for the second metric (saved; shared by every skill card). */
	enum SecondMetric
	{
		XP_REMAINING,
		TIME_TO_LEVEL;

		static SecondMetric parse(String s)
		{
			return "TIME_TO_LEVEL".equals(s) ? TIME_TO_LEVEL : XP_REMAINING;
		}

		SecondMetric other()
		{
			return this == XP_REMAINING ? TIME_TO_LEVEL : XP_REMAINING;
		}
	}

	/** XP/hr ("35,812 XP/hr"), or its warm-up placeholder; null when neither applies. */
	final String rate;
	/** The rate is the real figure (bright), not its placeholder. */
	final boolean rateReady;
	/** The second metric ("367,694 XP to 97", "~2h 05m to 97"), null at level 99 or without level data. */
	final String second;
	/** What the second line shows, which may be XP remaining even when time was chosen (the rate isn't ready). */
	final SecondMetric shown;
	final String secondTip;
	/** "Lv 96 · +9,591 XP". */
	final String quiet;

	private XpCardText(String rate, boolean rateReady, String second, SecondMetric shown, String secondTip, String quiet)
	{
		this.rate = rate;
		this.rateReady = rateReady;
		this.second = second;
		this.shown = shown;
		this.secondTip = secondTip;
		this.quiet = quiet;
	}

	static XpCardText of(CurrentSessionSnapshot.XpEntry e, SecondMetric chosen)
	{
		Long perHour = e.getXpPerHour();
		boolean ready = perHour != null;
		String rate = ready ? n(perHour) + " XP/hr"
			: e.getRateWarmupRemainingMillis() == null ? null : CurrentSessionView.rateWarmupText(e.getRateWarmupRemainingMillis());
		CurrentSessionSnapshot.LevelProgress lp = e.getLevelProgress();
		String quiet = (lp == null ? "" : "Lv " + lp.getCurrentLevel() + " · ") + "+" + n(e.getXpGained()) + " XP";
		if (lp == null || lp.isMaxLevel() || lp.getXpRemainingToNextLevel() == null)
		{
			return new XpCardText(rate, ready, null, null, null, quiet);
		}
		int next = lp.getCurrentLevel() + 1;
		long left = lp.getXpRemainingToNextLevel();
		Long eta = ready && perHour > 0 ? (long) Math.ceil(left / (double) perHour * 3_600_000d) : null;
		String switchHint = " Click to show " + (chosen == SecondMetric.XP_REMAINING ? "the time to level" : "XP remaining") + " instead.";
		if (chosen == SecondMetric.TIME_TO_LEVEL && eta != null)
		{
			return new XpCardText(rate, ready, com.osrstelemetry.plugin.skills.SkillGoalPlanner.etaText(eta) + " to " + next, SecondMetric.TIME_TO_LEVEL,
				"At this session's XP/hr; " + n(left) + " XP to go." + switchHint, quiet);
		}
		String tip = chosen == SecondMetric.TIME_TO_LEVEL
			? "The time to level appears once XP/hr does (after 5 minutes of activity)." + switchHint
			: "Exact XP to level " + next + "." + switchHint;
		return new XpCardText(rate, ready, n(left) + " XP to " + next, SecondMetric.XP_REMAINING, tip, quiet);
	}

	private static String n(long v)
	{
		return NumberFormat.getIntegerInstance(Locale.US).format(v);
	}
}
