package com.osrstelemetry.plugin.wintertodt;

import com.osrstelemetry.plugin.activity.WintertodtRoundTracker;
import java.util.Map;

/**
 * How the player runs Wintertodt, as TWO independent choices (planning state,
 * never telemetry): METHOD (Auto / Mass / Solo) and FOCUS (XP / Points). They
 * change what the companion EMPHASISES -- never a calculation.
 * <p>
 * AUTO is a resolution mode: Spyglass observes this session's rounds and,
 * once BOTH dimensions are clearly supported, resolves Auto into an explicit
 * Method + Focus pair, which then stays put until the player changes it (no
 * flip-flopping). Ambiguous or thin evidence leaves Auto unresolved.
 */
public final class WintertodtPlaystyle
{
	public enum Method
	{
		AUTO("Auto"),
		MASS("Mass"),
		SOLO("Solo");

		private final String label;

		Method(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public enum Focus
	{
		XP("XP"),
		POINTS("Points");

		private final String label;

		Focus(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Neither dimension is inferred before this many subdues with this many complete rounds. */
	public static final int MIN_ROUNDS = 3;
	/** Average complete round at or under this (minutes): public mass games (a few minutes each). */
	static final double MASS_MAX_MINUTES = 6.0;
	/** Average complete round at or over this (minutes): a controlled solo game (OSRS Wiki: 15-20 min). */
	static final double SOLO_MIN_MINUTES = 10.0;
	/** Focus needs at least this many feeds (roots + kindling) across the sample. */
	static final int MIN_FEEDS = 60;
	/** Kindling share of feeds at or over this: Points; at or under XP_MAX_KINDLING_SHARE: XP; between: ambiguous. */
	static final double POINTS_MIN_KINDLING_SHARE = 0.6;
	static final double XP_MAX_KINDLING_SHARE = 0.25;

	private WintertodtPlaystyle()
	{
	}

	/**
	 * Mass or Solo from round length across complete rounds; null when the
	 * sample is too small or the rounds sit between the two (ambiguous).
	 */
	public static Method inferMethod(int subdues, int completeRounds, long completeRoundMillis)
	{
		if (subdues < MIN_ROUNDS || completeRounds < MIN_ROUNDS || completeRoundMillis <= 0)
		{
			return null;
		}
		double avgMinutes = completeRoundMillis / (double) completeRounds / 60_000d;
		if (avgMinutes <= MASS_MAX_MINUTES)
		{
			return Method.MASS;
		}
		if (avgMinutes >= SOLO_MIN_MINUTES)
		{
			return Method.SOLO;
		}
		return null;
	}

	/**
	 * XP or Points from the feeding mix across the session (kindling is how
	 * points are made); repairs and heals support Points only alongside a
	 * kindling-leaning mix -- never on their own. Null when thin or mixed.
	 */
	public static Focus inferFocus(int subdues, int completeRounds, Map<String, Long> actions)
	{
		if (subdues < MIN_ROUNDS || completeRounds < MIN_ROUNDS)
		{
			return null;
		}
		long roots = count(actions, WintertodtRoundTracker.ROOTS_FED);
		long kindling = count(actions, WintertodtRoundTracker.KINDLING_FED);
		if (roots + kindling < MIN_FEEDS)
		{
			return null;
		}
		double share = kindling / (double) (roots + kindling);
		if (share >= POINTS_MIN_KINDLING_SHARE)
		{
			return Focus.POINTS;
		}
		if (share <= XP_MAX_KINDLING_SHARE)
		{
			return Focus.XP;
		}
		long support = count(actions, WintertodtRoundTracker.REPAIRED) + count(actions, WintertodtRoundTracker.HEALED);
		if (share >= 0.4 && support >= 2L * completeRounds)
		{
			// Leaning kindling AND repeatedly repairing / healing every round.
			return Focus.POINTS;
		}
		return null;
	}

	/** "Mass • Points"; "Auto" while unresolved. */
	public static String label(Method method, Focus focus)
	{
		if (method == null || method == Method.AUTO || focus == null)
		{
			return Method.AUTO.getLabel();
		}
		return method.getLabel() + " • " + focus.getLabel();
	}

	/**
	 * Old single-choice preference values (before the Method + Focus split):
	 * returns {method, focus-or-null}. WORLD_HOP_XP is no longer a playstyle:
	 * it becomes unresolved Auto with XP as the remembered focus (never
	 * guessed as Mass or Solo). Unknown values become Auto.
	 */
	public static Object[] migrate(String legacy)
	{
		if (legacy == null)
		{
			return new Object[]{Method.AUTO, null};
		}
		switch (legacy)
		{
			case "MASS_XP":
				return new Object[]{Method.MASS, Focus.XP};
			case "MASS_REWARDS":
				return new Object[]{Method.MASS, Focus.POINTS};
			case "SOLO_XP":
				return new Object[]{Method.SOLO, Focus.XP};
			case "SOLO_REWARDS":
				return new Object[]{Method.SOLO, Focus.POINTS};
			case "WORLD_HOP_XP":
				return new Object[]{Method.AUTO, Focus.XP};
			case "AUTO":
			default:
				return new Object[]{Method.AUTO, null};
		}
	}

	private static long count(Map<String, Long> actions, String key)
	{
		Long v = actions == null ? null : actions.get(key);
		return v == null ? 0L : v;
	}
}
