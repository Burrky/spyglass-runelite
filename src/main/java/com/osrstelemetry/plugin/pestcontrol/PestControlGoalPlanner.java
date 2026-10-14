package com.osrstelemetry.plugin.pestcontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;
import net.runelite.api.Skill;

/**
 * Pure goal arithmetic: total commendation cost of the selected goal
 * (accounting for owned Void pieces), POINTS LEFT after the current
 * balance, and an ETA at the player's OBSERVED commendations/hr. Nothing
 * here guesses: unknown inputs produce an explicit "unknown" result.
 */
public final class PestControlGoalPlanner
{
	private PestControlGoalPlanner()
	{
	}

	/** The plan for one render. */
	public static final class Plan
	{
		private final Long cost;
		private final Integer balance;
		private final String detail;
		private final String problem;
		private final boolean complete;
		private final String owned;

		Plan(Long cost, Integer balance, String detail, String problem, boolean complete)
		{
			this(cost, balance, detail, problem, complete, null);
		}

		Plan(Long cost, Integer balance, String detail, String problem, boolean complete, String owned)
		{
			this.cost = cost;
			this.balance = balance;
			this.detail = detail;
			this.problem = problem;
			this.complete = complete;
			this.owned = owned;
		}

		/** Required pieces already owned ("Ranger helm, Gloves"), for a tooltip; null when none / not applicable. */
		public String getOwned()
		{
			return owned;
		}

		/** Total points the goal costs from here (owned pieces excluded); null when it cannot be computed. */
		public Long getCost()
		{
			return cost;
		}

		/** The commendation balance subtracted, or null when unknown (never assumed 0). */
		public Integer getBalance()
		{
			return balance;
		}

		/** POINTS LEFT: cost minus the known balance, never below 0; the full cost when the balance is unknown. */
		public Long getPointsLeft()
		{
			if (cost == null)
			{
				return null;
			}
			return balance == null ? cost : Math.max(0, cost - balance);
		}

		/** One concise line: what is still needed / owned / produced. */
		public String getDetail()
		{
			return detail;
		}

		/** Why no cost could be computed (e.g. "Needs level 25"), or null. */
		public String getProblem()
		{
			return problem;
		}

		/** The goal is already reached (owned / level reached). */
		public boolean isComplete()
		{
			return complete;
		}

		/** ETA in millis at {@code perHour}; null without a usable rate; 0 when nothing is left. */
		public Long etaMillis(Double perHour)
		{
			Long left = getPointsLeft();
			if (left == null)
			{
				return null;
			}
			if (left == 0)
			{
				return 0L;
			}
			if (perHour == null || perHour <= 0)
			{
				return null;
			}
			return (long) Math.ceil(left / perHour * 3_600_000d);
		}
	}

	/**
	 * @param ownership current Void ownership (never null)
	 * @param balance current commendation balance, or null when unknown
	 * @param exactXp the skill's exact XP, or a value &lt; 0 when unknown
	 */
	public static Plan plan(PestControlGoal goal, VoidOwnership.Snapshot ownership, Integer balance, ToLongFunction<Skill> exactXp)
	{
		switch (goal.getFamily())
		{
			case XP:
				return xp(goal, balance, exactXp);
			case SUPPLIES:
				return supplies(goal, balance);
			case VOID:
			default:
				return voidGoal(goal, ownership, balance);
		}
	}

	// ---- Void ---------------------------------------------------------------

	/** Fulfilled for planning: owned, or superseded by an owned piece (VoidPiece#satisfies). */
	static boolean owns(VoidOwnership.Snapshot o, VoidPiece piece)
	{
		return o.satisfies(piece);
	}

	/** Points to obtain {@code piece} from what is owned (an elite piece includes its base if missing). */
	static int costOf(VoidOwnership.Snapshot o, VoidPiece piece)
	{
		if (owns(o, piece))
		{
			return 0;
		}
		VoidPiece base = piece.getUpgradesFrom();
		return piece.getCost() + (base != null && !owns(o, base) ? base.getCost() : 0);
	}

	static List<VoidPiece> required(PestControlGoal goal, VoidOwnership.Snapshot o)
	{
		List<VoidPiece> pieces = new ArrayList<>();
		VoidStyle style = goal.getVoidStyle();
		switch (goal.getVoidGoal())
		{
			case PIECE:
				pieces.add(goal.getVoidPiece());
				break;
			case FULL_SET:
				pieces.add(style.getHelm());
				pieces.add(VoidPiece.TOP);
				pieces.add(VoidPiece.ROBE);
				pieces.add(VoidPiece.GLOVES);
				break;
			case ELITE_SET:
				pieces.add(style.getHelm());
				pieces.add(VoidPiece.ELITE_TOP);
				pieces.add(VoidPiece.ELITE_ROBE);
				pieces.add(VoidPiece.GLOVES);
				break;
			case ALL_STANDARD:
				pieces.addAll(VoidPiece.STANDARD);
				break;
			case NEXT_MISSING:
			default:
				VoidPiece next = nextMissing(style, o);
				if (next != null)
				{
					pieces.add(next);
				}
				break;
		}
		return pieces;
	}

	/**
	 * Deterministic "next missing piece": body, legs, gloves, then the
	 * selected style's helm, then the other helms (melee, ranger, mage
	 * order), then the mace -- the first one not owned. Null when every
	 * standard piece is owned.
	 */
	public static VoidPiece nextMissing(VoidStyle style, VoidOwnership.Snapshot o)
	{
		List<VoidPiece> order = new ArrayList<>();
		order.add(VoidPiece.TOP);
		order.add(VoidPiece.ROBE);
		order.add(VoidPiece.GLOVES);
		order.add(style.getHelm());
		for (VoidStyle s : VoidStyle.values())
		{
			if (s != style)
			{
				order.add(s.getHelm());
			}
		}
		order.add(VoidPiece.MACE);
		for (VoidPiece p : order)
		{
			if (!owns(o, p))
			{
				return p;
			}
		}
		return null;
	}

	private static Plan voidGoal(PestControlGoal goal, VoidOwnership.Snapshot o, Integer balance)
	{
		List<VoidPiece> pieces = required(goal, o);
		if (pieces.isEmpty())
		{
			return new Plan(0L, balance, "All standard Void owned", null, true);
		}
		long cost = 0;
		List<String> need = new ArrayList<>();
		List<String> have = new ArrayList<>();
		for (VoidPiece p : pieces)
		{
			int c = costOf(o, p);
			cost += c;
			if (c == 0)
			{
				have.add(p.getShortName());
			}
			else if (p.isElite() && owns(o, p.getUpgradesFrom()))
			{
				need.add(p.getShortName() + " upgrade");
			}
			else
			{
				need.add(p.getShortName());
			}
		}
		String detail;
		if (cost == 0)
		{
			detail = "Owned";
		}
		else
		{
			detail = "Need: " + String.join(", ", need);
		}
		if (!o.isBankKnown() && cost > 0)
		{
			detail += " (bank not seen yet)";
		}
		return new Plan(cost, balance, detail, null, cost == 0, have.isEmpty() ? null : String.join(", ", have));
	}

	// ---- XP -------------------------------------------------------------------

	private static Plan xp(PestControlGoal goal, Integer balance, ToLongFunction<Skill> exactXp)
	{
		Skill skill = goal.getXpSkill();
		long current = exactXp.applyAsLong(skill);
		if (current < 0)
		{
			return new Plan(null, balance, null, skillName(skill) + " XP not known yet", false);
		}
		PestControlXp.Cost c = PestControlXp.costToLevel(skill, current, goal.getXpTargetLevel());
		if (c.getProblem() == PestControlXp.Problem.ALREADY_REACHED)
		{
			return new Plan(0L, balance, skillName(skill) + " " + goal.getXpTargetLevel() + " reached", null, true);
		}
		if (c.getProblem() == PestControlXp.Problem.BELOW_MINIMUM_LEVEL)
		{
			return new Plan(null, balance, null, "Needs " + PestControlXp.MIN_LEVEL + " " + skillName(skill), false);
		}
		if (c.getProblem() != null)
		{
			return new Plan(null, balance, null, "Not a Pest Control XP target", false);
		}
		return new Plan(c.getPoints(), balance, skillName(skill) + " " + goal.getXpTargetLevel() + " (bought 100 at a time)", null, false);
	}

	static String skillName(Skill skill)
	{
		String n = skill.getName();
		return n == null ? skill.name() : n;
	}

	// ---- Supplies ---------------------------------------------------------------

	private static Plan supplies(PestControlGoal goal, Integer balance)
	{
		SupplyPack pack = goal.getPack();
		int packs = goal.getPackTarget();
		long cost = (long) pack.getCost() * packs;
		String detail;
		if (pack.hasFixedContents())
		{
			detail = String.format(Locale.ROOT, "%,d coal • %,d iron ore", (long) pack.getCoalPerPack() * packs,
				(long) pack.getIronOrePerPack() * packs);
		}
		else
		{
			detail = packs + " " + pack.getItemName().toLowerCase(Locale.ROOT) + (packs == 1 ? "" : "s") + " • random contents";
		}
		return new Plan(cost, balance, detail, null, false);
	}

	// ---- Formatting ---------------------------------------------------------------

	/** "~4h 27m", "~35m", "<1m"; null in -> null out. */
	public static String etaText(Long millis)
	{
		if (millis == null)
		{
			return null;
		}
		if (millis == 0)
		{
			return "Ready";
		}
		long minutes = (millis + 59_999) / 60_000;
		if (minutes < 1)
		{
			return "<1m";
		}
		long h = minutes / 60;
		long m = minutes % 60;
		return h == 0 ? "~" + m + "m" : String.format(Locale.ROOT, "~%dh %02dm", h, m);
	}
}
