package com.osrstelemetry.plugin.skills;

import com.osrstelemetry.plugin.skills.agility.AgilityCompanionDefinition;
import com.osrstelemetry.plugin.skills.fishing.FishingCompanionDefinition;
import com.osrstelemetry.plugin.skills.herblore.HerbloreCompanionDefinition;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import com.osrstelemetry.plugin.skills.runecraft.RunecraftCompanionDefinition;
import com.osrstelemetry.plugin.skills.woodcutting.WoodcuttingCompanionDefinition;

/**
 * The skills that have a companion (Mining, Fishing, Agility, Woodcutting, Runecraft, Herblore). Every other skill keeps
 * the plain generic SKILLING session exactly as before.
 */
public final class SkillCompanions
{
	private SkillCompanions()
	{
	}

	/** Every registered companion definition, in registration order (one per SKILLING key). */
	public static java.util.List<SkillCompanionDefinition> all()
	{
		return java.util.Arrays.asList(MiningCompanionDefinition.INSTANCE, FishingCompanionDefinition.INSTANCE,
			AgilityCompanionDefinition.INSTANCE, WoodcuttingCompanionDefinition.INSTANCE, RunecraftCompanionDefinition.INSTANCE,
			HerbloreCompanionDefinition.INSTANCE);
	}

	/** The companion definition for a SKILLING session key ("mining"), or null when the skill has none. */
	public static SkillCompanionDefinition forSkillKey(String skillKey)
	{
		if (MiningCompanionDefinition.SKILL_KEY.equals(skillKey))
		{
			return MiningCompanionDefinition.INSTANCE;
		}
		if (FishingCompanionDefinition.SKILL_KEY.equals(skillKey))
		{
			return FishingCompanionDefinition.INSTANCE;
		}
		if (AgilityCompanionDefinition.SKILL_KEY.equals(skillKey))
		{
			return AgilityCompanionDefinition.INSTANCE;
		}
		if (WoodcuttingCompanionDefinition.SKILL_KEY.equals(skillKey))
		{
			return WoodcuttingCompanionDefinition.INSTANCE;
		}
		if (RunecraftCompanionDefinition.SKILL_KEY.equals(skillKey))
		{
			return RunecraftCompanionDefinition.INSTANCE;
		}
		if (HerbloreCompanionDefinition.SKILL_KEY.equals(skillKey))
		{
			return HerbloreCompanionDefinition.INSTANCE;
		}
		return null;
	}
}
