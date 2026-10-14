package com.osrstelemetry.plugin.skills;

import java.util.Objects;

/**
 * One skill companion's account-scoped TARGET (planning state, never
 * telemetry): either a LEVEL ("92 Mining") or a STOCKPILE ("5,000
 * Amethyst banked"). Immutable; the with-ers return a changed copy.
 */
public final class SkillGoal
{
	public enum Family
	{
		LEVEL("Level"),
		STOCKPILE("Stockpile");

		private final String label;

		Family(String label)
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

	public static final int MIN_LEVEL = 2;
	public static final int MAX_LEVEL = 99;
	public static final int DEFAULT_LEVEL = 99;
	public static final int MAX_QUANTITY = 10_000_000;
	public static final int DEFAULT_QUANTITY = 1_000;

	public static final SkillGoal DEFAULT = new SkillGoal(Family.LEVEL, DEFAULT_LEVEL, null, DEFAULT_QUANTITY);

	private final Family family;
	private final int targetLevel;
	/** STOCKPILE: the resource's item id; null = not chosen yet (the method's main resource is used). */
	private final Integer resourceItemId;
	private final int targetQuantity;

	public SkillGoal(Family family, int targetLevel, Integer resourceItemId, int targetQuantity)
	{
		this.family = family == null ? Family.LEVEL : family;
		this.targetLevel = clamp(targetLevel, MIN_LEVEL, MAX_LEVEL, DEFAULT_LEVEL);
		this.resourceItemId = resourceItemId;
		this.targetQuantity = clamp(targetQuantity, 1, MAX_QUANTITY, DEFAULT_QUANTITY);
	}

	private static int clamp(int v, int min, int max, int fallback)
	{
		return v < min || v > max ? fallback : v;
	}

	public Family getFamily()
	{
		return family;
	}

	public int getTargetLevel()
	{
		return targetLevel;
	}

	public Integer getResourceItemId()
	{
		return resourceItemId;
	}

	public int getTargetQuantity()
	{
		return targetQuantity;
	}

	public SkillGoal withFamily(Family f)
	{
		return new SkillGoal(f, targetLevel, resourceItemId, targetQuantity);
	}

	public SkillGoal withTargetLevel(int level)
	{
		return new SkillGoal(family, level, resourceItemId, targetQuantity);
	}

	public SkillGoal withResource(Integer itemId)
	{
		return new SkillGoal(family, targetLevel, itemId, targetQuantity);
	}

	public SkillGoal withTargetQuantity(int quantity)
	{
		return new SkillGoal(family, targetLevel, resourceItemId, quantity);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof SkillGoal))
		{
			return false;
		}
		SkillGoal g = (SkillGoal) o;
		return family == g.family && targetLevel == g.targetLevel && Objects.equals(resourceItemId, g.resourceItemId)
			&& targetQuantity == g.targetQuantity;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(family, targetLevel, resourceItemId, targetQuantity);
	}

	@Override
	public String toString()
	{
		return "SkillGoal{" + family + ", level " + targetLevel + ", item " + resourceItemId + " x" + targetQuantity + '}';
	}
}
