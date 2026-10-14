package com.osrstelemetry.plugin.pestcontrol;

import java.util.Objects;
import net.runelite.api.Skill;

/**
 * The player's Pest Control reward goal (PLANNING state, never telemetry):
 * what they are grinding commendation points for. Immutable; each change
 * produces a new goal. Persisted per account by {@link PestControlGoalPreferences}.
 */
public final class PestControlGoal
{
	public enum Family
	{
		VOID("Void equipment"),
		XP("XP target"),
		SUPPLIES("Supplies");

		private final String label;

		Family(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public enum VoidGoal
	{
		NEXT_MISSING("Next missing piece"),
		PIECE("Specific piece"),
		FULL_SET("Full Void set"),
		ELITE_SET("Elite Void set"),
		ALL_STANDARD("All standard Void");

		private final String label;

		VoidGoal(String label)
		{
			this.label = label;
		}

		/** Whether this goal needs a combat style (which helm). */
		public boolean usesStyle()
		{
			return this == NEXT_MISSING || this == FULL_SET || this == ELITE_SET;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	public static final PestControlGoal DEFAULT = new PestControlGoal(Family.VOID, VoidGoal.NEXT_MISSING, VoidPiece.TOP,
		VoidStyle.MELEE, Skill.DEFENCE, 99, SupplyPack.MINERAL, 20);

	private final Family family;
	private final VoidGoal voidGoal;
	private final VoidPiece voidPiece;
	private final VoidStyle voidStyle;
	private final Skill xpSkill;
	private final int xpTargetLevel;
	private final SupplyPack pack;
	private final int packTarget;

	public PestControlGoal(Family family, VoidGoal voidGoal, VoidPiece voidPiece, VoidStyle voidStyle, Skill xpSkill,
		int xpTargetLevel, SupplyPack pack, int packTarget)
	{
		this.family = family == null ? Family.VOID : family;
		this.voidGoal = voidGoal == null ? VoidGoal.NEXT_MISSING : voidGoal;
		this.voidPiece = voidPiece == null ? VoidPiece.TOP : voidPiece;
		this.voidStyle = voidStyle == null ? VoidStyle.MELEE : voidStyle;
		this.xpSkill = PestControlXp.isEligible(xpSkill) ? xpSkill : Skill.DEFENCE;
		this.xpTargetLevel = Math.max(2, Math.min(99, xpTargetLevel));
		this.pack = pack == null ? SupplyPack.MINERAL : pack;
		this.packTarget = Math.max(1, Math.min(MAX_PACKS, packTarget));
	}

	/** A sane upper bound for a pack target (4,000-point cap / 15 points ~ 266 at once; this allows planning far beyond). */
	public static final int MAX_PACKS = 10_000;

	public Family getFamily()
	{
		return family;
	}

	public VoidGoal getVoidGoal()
	{
		return voidGoal;
	}

	public VoidPiece getVoidPiece()
	{
		return voidPiece;
	}

	public VoidStyle getVoidStyle()
	{
		return voidStyle;
	}

	public Skill getXpSkill()
	{
		return xpSkill;
	}

	public int getXpTargetLevel()
	{
		return xpTargetLevel;
	}

	public SupplyPack getPack()
	{
		return pack;
	}

	public int getPackTarget()
	{
		return packTarget;
	}

	public PestControlGoal withFamily(Family f)
	{
		return new PestControlGoal(f, voidGoal, voidPiece, voidStyle, xpSkill, xpTargetLevel, pack, packTarget);
	}

	public PestControlGoal withVoidGoal(VoidGoal g)
	{
		return new PestControlGoal(family, g, voidPiece, voidStyle, xpSkill, xpTargetLevel, pack, packTarget);
	}

	public PestControlGoal withVoidPiece(VoidPiece p)
	{
		return new PestControlGoal(family, voidGoal, p, voidStyle, xpSkill, xpTargetLevel, pack, packTarget);
	}

	public PestControlGoal withVoidStyle(VoidStyle s)
	{
		return new PestControlGoal(family, voidGoal, voidPiece, s, xpSkill, xpTargetLevel, pack, packTarget);
	}

	public PestControlGoal withXpSkill(Skill s)
	{
		return new PestControlGoal(family, voidGoal, voidPiece, voidStyle, s, xpTargetLevel, pack, packTarget);
	}

	public PestControlGoal withXpTargetLevel(int level)
	{
		return new PestControlGoal(family, voidGoal, voidPiece, voidStyle, xpSkill, level, pack, packTarget);
	}

	public PestControlGoal withPack(SupplyPack p)
	{
		return new PestControlGoal(family, voidGoal, voidPiece, voidStyle, xpSkill, xpTargetLevel, p, packTarget);
	}

	public PestControlGoal withPackTarget(int target)
	{
		return new PestControlGoal(family, voidGoal, voidPiece, voidStyle, xpSkill, xpTargetLevel, pack, target);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof PestControlGoal))
		{
			return false;
		}
		PestControlGoal g = (PestControlGoal) o;
		return family == g.family && voidGoal == g.voidGoal && voidPiece == g.voidPiece && voidStyle == g.voidStyle
			&& xpSkill == g.xpSkill && xpTargetLevel == g.xpTargetLevel && pack == g.pack && packTarget == g.packTarget;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(family, voidGoal, voidPiece, voidStyle, xpSkill, xpTargetLevel, pack, packTarget);
	}
}
