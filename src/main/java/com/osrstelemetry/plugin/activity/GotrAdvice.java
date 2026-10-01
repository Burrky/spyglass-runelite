package com.osrstelemetry.plugin.activity;

import com.osrstelemetry.plugin.GotrStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Guardians of the Rift altar advice over EVERY altar the player can enter
 * right now (pure): the open rifts plus the altars of held portal talismans
 * ({@link GotrGameState#getCandidates()}). A talisman only widens the set of
 * candidates -- it is never recommended for being a talisman.
 *
 * CONFIDENCE RULE: the open rifts must be known and every candidate's
 * eligibility (level + quest, from the game) known; a candidate the player
 * cannot use is never recommended; and a winner is named only when the
 * game's mechanics make the difference meaningful. Otherwise the outcome
 * says so -- FOCUS a side, EITHER, CAPPED, NONE or INSUFFICIENT_EVIDENCE.
 *
 * POINTS (OSRS Wiki, Guardians of the Rift + Strategies): a search costs one
 * elemental AND one catalytic point. Every guardian stone gives 2 energy of
 * its type whatever the altar, so normally only the SIDE matters; the altar
 * matters only through the barrier cell it charges (cells feed BOTH types).
 * Hence the wiki's even-points order, applied as-is: Blood / Death, then
 * Fire / Earth (a SPECIFIC altar, when exactly one candidate holds the best
 * cell rank -- else FOCUS its side); otherwise FOCUS the side with fewer
 * points (known wallet + this game's energy / 100; this game alone when the
 * wallet is unknown). A type at the 1,000-energy cap is never advised; at
 * the 1,200 total cap nothing is.
 *
 * XP: guardian essence gives each rune's standard Runecraft XP, so the
 * candidate with the most XP per essence wins when at least
 * {@link #MIN_XP_ADVANTAGE} ahead of every other usable one; near-ties of
 * one type FOCUS that type, near-ties across types are EITHER.
 */
public final class GotrAdvice
{
	public enum Outcome
	{
		RECOMMEND_ELEMENTAL,
		RECOMMEND_CATALYTIC,
		/** Craft on this side; which altar of it does not matter. */
		FOCUS_ELEMENTAL,
		FOCUS_CATALYTIC,
		/** Every usable candidate is equally good for the chosen strategy. */
		EITHER,
		/** This game's energy is capped: crafting more earns no points. */
		CAPPED,
		/** Nothing the player can use is advisable. */
		NONE,
		/** Spyglass does not know enough to judge. */
		INSUFFICIENT_EVIDENCE
	}

	/** How a candidate cell is marked. */
	public enum Mark
	{
		BEST("\u2605 BEST"),
		FOCUS("\u2605 FOCUS"),
		NONE(null);

		private final String label;

		Mark(String label)
		{
			this.label = label;
		}

		/** Player-facing label; null for NONE. */
		public String getLabel()
		{
			return label;
		}
	}

	public static final int ENERGY_CAP_PER_TYPE = 1_000;
	public static final int ENERGY_CAP_TOTAL = 1_200;
	/** Points closer than this (half a point) count as even. */
	static final double EVEN_POINTS = 0.5;
	/** XP per essence closer than this counts as equal. */
	static final double MIN_XP_ADVANTAGE = 1.0;

	private static final GotrAdvice UNKNOWN = new GotrAdvice(Outcome.INSUFFICIENT_EVIDENCE, null, null, null, null);

	private final Outcome outcome;
	private final GotrAltar altar;
	private final GotrAltar.Type focus;
	private final String reason;
	private final String detail;

	private GotrAdvice(Outcome outcome, GotrAltar altar, GotrAltar.Type focus, String reason, String detail)
	{
		this.outcome = outcome;
		this.altar = altar;
		this.focus = focus;
		this.reason = reason;
		this.detail = detail;
	}

	public Outcome getOutcome()
	{
		return outcome;
	}

	/** The recommended altar; null unless RECOMMEND_*. */
	public GotrAltar getAltar()
	{
		return altar;
	}

	/** The side to focus; null unless FOCUS_*. */
	public GotrAltar.Type getFocus()
	{
		return focus;
	}

	/** One concise, cell-sized reason; null for NONE / INSUFFICIENT_EVIDENCE. */
	public String getReason()
	{
		return reason;
	}

	/** A fuller sentence for tooltips; null when there is nothing to say. */
	public String getDetail()
	{
		return detail;
	}

	public boolean isRecommendation()
	{
		return outcome == Outcome.RECOMMEND_ELEMENTAL || outcome == Outcome.RECOMMEND_CATALYTIC;
	}

	public boolean isFocus()
	{
		return outcome == Outcome.FOCUS_ELEMENTAL || outcome == Outcome.FOCUS_CATALYTIC;
	}

	/** The mark a candidate cell carries: BEST for the recommended altar, FOCUS for usable altars of the focused side. */
	public Mark markOf(GotrCandidate candidate)
	{
		if (candidate == null || !Boolean.TRUE.equals(candidate.getUsable()))
		{
			return Mark.NONE;
		}
		if (isRecommendation())
		{
			return candidate.getAltar() == altar ? Mark.BEST : Mark.NONE;
		}
		if (isFocus())
		{
			return candidate.getAltar().getType() == focus ? Mark.FOCUS : Mark.NONE;
		}
		return Mark.NONE;
	}

	public static GotrAdvice of(GotrStrategy strategy, GotrGameState game, GotrRewardWallet wallet)
	{
		if (game == null || game.getActiveElemental() == null || game.getActiveCatalytic() == null)
		{
			return UNKNOWN;
		}
		List<GotrCandidate> candidates = game.getCandidates();
		for (GotrCandidate c : candidates)
		{
			if (c.getUsable() == null)
			{
				return UNKNOWN;
			}
		}
		return strategy == GotrStrategy.XP ? xp(candidates) : points(game, candidates, wallet);
	}

	private static GotrAdvice points(GotrGameState game, List<GotrCandidate> candidates, GotrRewardWallet wallet)
	{
		Integer e = game.getElementalEnergy();
		Integer c = game.getCatalyticEnergy();
		if (e == null || c == null)
		{
			return UNKNOWN;
		}
		if (e + c >= ENERGY_CAP_TOTAL)
		{
			return new GotrAdvice(Outcome.CAPPED, null, null, "Energy capped",
				"This game's energy is capped (" + ENERGY_CAP_TOTAL + " total): more crafting earns no more points");
		}
		boolean elementalOpen = e < ENERGY_CAP_PER_TYPE;
		boolean catalyticOpen = c < ENERGY_CAP_PER_TYPE;
		List<GotrAltar> usable = new ArrayList<>();
		for (GotrCandidate k : candidates)
		{
			GotrAltar a = k.getAltar();
			if (k.getUsable() && (a.getType() == GotrAltar.Type.ELEMENTAL ? elementalOpen : catalyticOpen))
			{
				usable.add(a);
			}
		}
		if (usable.isEmpty())
		{
			return new GotrAdvice(Outcome.NONE, null, null, null, null);
		}

		// 1) The wiki's priority altars: best cells, which feed BOTH types.
		int bestRank = 0;
		for (GotrAltar a : usable)
		{
			bestRank = Math.max(bestRank, cellPriority(a));
		}
		if (bestRank > 0)
		{
			List<GotrAltar> top = new ArrayList<>();
			for (GotrAltar a : usable)
			{
				if (cellPriority(a) == bestRank)
				{
					top.add(a);
				}
			}
			String detail = "Its barrier cells give energy to both types (wiki order: Blood/Death, then Fire/Earth)";
			if (top.size() == 1)
			{
				return recommend(top.get(0), "Best cells", detail);
			}
			// Several share the best rank -- always one side (Blood/Death, Fire/Earth).
			return focus(top.get(0).getType(), "Best cells", detail);
		}

		// 2) Otherwise the side you have fewer points for -- any altar of it.
		double ePoints = (wallet == null ? 0 : wallet.getElemental()) + e / 100.0;
		double cPoints = (wallet == null ? 0 : wallet.getCatalytic()) + c / 100.0;
		double gap = Math.abs(ePoints - cPoints);
		if (gap < EVEN_POINTS)
		{
			return new GotrAdvice(Outcome.EITHER, null, null, "Points even",
				"Elemental and catalytic points are even: either side keeps them balanced");
		}
		GotrAltar.Type behind = ePoints < cPoints ? GotrAltar.Type.ELEMENTAL : GotrAltar.Type.CATALYTIC;
		boolean any = false;
		for (GotrAltar a : usable)
		{
			any |= a.getType() == behind;
		}
		if (!any)
		{
			return new GotrAdvice(Outcome.NONE, null, null, null, null);
		}
		int points = Math.max(1, (int) Math.round(gap));
		String label = behind.getLabel().toLowerCase(Locale.ROOT);
		return wallet == null
			? focus(behind, "Behind this game", label + " is behind this game (reward balance unknown)")
			: focus(behind, points + " behind", label + " is " + points + " point" + (points == 1 ? "" : "s")
				+ " behind: a search needs one of each");
	}

	/** 2 = Blood / Death, 1 = Fire / Earth (the wiki's order), 0 = no priority. */
	static int cellPriority(GotrAltar altar)
	{
		switch (altar)
		{
			case BLOOD:
			case DEATH:
				return 2;
			case FIRE:
			case EARTH:
				return 1;
			default:
				return 0;
		}
	}

	private static GotrAdvice xp(List<GotrCandidate> candidates)
	{
		List<GotrAltar> usable = new ArrayList<>();
		for (GotrCandidate k : candidates)
		{
			if (k.getUsable())
			{
				usable.add(k.getAltar());
			}
		}
		if (usable.isEmpty())
		{
			return new GotrAdvice(Outcome.NONE, null, null, null, null);
		}
		if (usable.size() == 1)
		{
			return recommend(usable.get(0), "Only usable", "The only altar you can enter right now");
		}
		GotrAltar best = usable.get(0);
		for (GotrAltar a : usable)
		{
			if (a.getXpPerEssence() > best.getXpPerEssence())
			{
				best = a;
			}
		}
		double nextBest = 0;
		boolean nearTieAcrossTypes = false;
		for (GotrAltar a : usable)
		{
			if (a == best)
			{
				continue;
			}
			nextBest = Math.max(nextBest, a.getXpPerEssence());
			if (best.getXpPerEssence() - a.getXpPerEssence() < MIN_XP_ADVANTAGE && a.getType() != best.getType())
			{
				nearTieAcrossTypes = true;
			}
		}
		double lead = best.getXpPerEssence() - nextBest;
		if (lead >= MIN_XP_ADVANTAGE)
		{
			return recommend(best, "+" + perEssence(lead),
				xpText(best.getXpPerEssence()) + " XP per essence, " + xpText(lead) + " more than any other altar you can enter");
		}
		if (nearTieAcrossTypes)
		{
			return new GotrAdvice(Outcome.EITHER, null, null, "Similar XP", "The best altars give similar XP per essence");
		}
		return focus(best.getType(), "Similar XP", "The best " + best.getType().getLabel().toLowerCase(Locale.ROOT)
			+ " altars give similar XP per essence");
	}

	/**
	 * Compact XP-per-essence for the strategy grid: one decimal and a spaced
	 * slash ("5.5 / ess", "+1.5 / ess"). XP mode already says it is XP, and
	 * the old "XP/ess" read as "XP less" in the RuneScape font.
	 */
	public static String perEssence(double xp)
	{
		return String.format(Locale.ROOT, "%.1f / ess", xp);
	}

	public static String xpText(double xp)
	{
		return String.format(Locale.ROOT, "%.1f", xp).replace(".0", "");
	}

	private static GotrAdvice recommend(GotrAltar altar, String reason, String detail)
	{
		return new GotrAdvice(altar.getType() == GotrAltar.Type.ELEMENTAL ? Outcome.RECOMMEND_ELEMENTAL : Outcome.RECOMMEND_CATALYTIC,
			altar, null, reason, detail);
	}

	private static GotrAdvice focus(GotrAltar.Type type, String reason, String detail)
	{
		return new GotrAdvice(type == GotrAltar.Type.ELEMENTAL ? Outcome.FOCUS_ELEMENTAL : Outcome.FOCUS_CATALYTIC,
			null, type, reason, detail);
	}
}
