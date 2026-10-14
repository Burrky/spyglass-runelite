package com.osrstelemetry.plugin.skills.agility;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillResource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * Agility's vocabulary for the generic skill companion. Agility is NOT a
 * production skill: its unit is the completed LAP of a COURSE (generic
 * cycles, see SkillCycleTracker), and the only "resources" are the course's
 * own REWARDS (Marks of Grace, crystal shards, pyramid tops, termites) --
 * collected items, never laps. The shared production plumbing carries those
 * rewards (so banking and stockpile targets work unchanged); the panel shows
 * them as REWARDS, never TOTAL / BANKED.
 */
public final class AgilityCompanionDefinition implements SkillCompanionDefinition
{
	public static final String SKILL_KEY = "agility";
	public static final AgilityCompanionDefinition INSTANCE = new AgilityCompanionDefinition();

	private static final Map<Integer, SkillResource> REWARDS = new LinkedHashMap<>();

	static
	{
		for (AgilityCourse c : AgilityCourses.all())
		{
			AgilityCourse.Reward r = c.getReward();
			if (r != null && !REWARDS.containsKey(r.getItemId()))
			{
				REWARDS.put(r.getItemId(), new SkillResource(r.getItemId(), r.getItemName(), r.getShortName(), SkillResource.Role.PRIMARY,
					null, true));
			}
		}
	}

	private AgilityCompanionDefinition()
	{
	}

	@Override
	public String getSkillKey()
	{
		return SKILL_KEY;
	}

	@Override
	public Skill getSkill()
	{
		return Skill.AGILITY;
	}

	@Override
	public String methodLabel(String methodKey)
	{
		AgilityCourse c = AgilityCourses.byKey(methodKey);
		if (c != null)
		{
			return c.getName();
		}
		if (methodKey == null || methodKey.isEmpty())
		{
			return null;
		}
		String s = methodKey.replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Override
	public SkillResource resource(Integer itemId)
	{
		return itemId == null ? null : REWARDS.get(itemId);
	}

	/** Laps, not inventory trips. */
	@Override
	public boolean tripMatters(String methodKey)
	{
		return false;
	}

	@Override
	public boolean groupIsPrimary(String methodKey, String group)
	{
		return false;
	}

	/** REWARDS caption of the course's reward ("MARKS"); a reward of another course keeps its own caption. */
	@Override
	public String productionCaption(String methodKey, List<SkillResource> mainResources)
	{
		AgilityCourse.Reward r = rewardOf(methodKey);
		if (r != null && mainResources != null && mainResources.size() == 1 && mainResources.get(0).getItemId() == r.getItemId())
		{
			return r.getCaption();
		}
		if (mainResources != null && mainResources.size() == 1)
		{
			AgilityCourse.Reward other = rewardFor(mainResources.get(0).getItemId());
			if (other != null)
			{
				return other.getCaption();
			}
		}
		return "REWARDS";
	}

	@Override
	public String rateNoun(String methodKey)
	{
		AgilityCourse.Reward r = rewardOf(methodKey);
		return r == null ? null : r.getRateNoun();
	}

	@Override
	public String producedVerb()
	{
		return "collected";
	}

	@Override
	public String producedTitle()
	{
		return "Rewards";
	}

	/** Reward targets: the course's own reward. */
	@Override
	public List<SkillResource> stockpileResources(String methodKey)
	{
		AgilityCourse.Reward r = rewardOf(methodKey);
		List<SkillResource> out = new ArrayList<>();
		if (r != null)
		{
			out.add(REWARDS.get(r.getItemId()));
		}
		return out;
	}

	@Override
	public boolean storageRelevant(String methodKey, Set<Integer> producedItems)
	{
		return false;
	}

	@Override
	public boolean storageCatchesMain(String methodKey)
	{
		return false;
	}

	@Override
	public String stockpileEmptyHint()
	{
		return "Run a course with a reward to pick a reward target";
	}

	// ---- cycle (lap) presentation -----------------------------------------------------------

	@Override
	public String methodTitle()
	{
		return "Course";
	}

	@Override
	public String cycleNoun(boolean plural)
	{
		return plural ? "laps" : "lap";
	}

	/** OBSTACLE n / N for a fixed route; OBSTACLES n where floors repeat (Pyramid, Penguin). */
	@Override
	public String cycleProgressLabel(String methodKey)
	{
		AgilityCourse c = AgilityCourses.byKey(methodKey);
		return c != null && c.isCountOnly() ? "OBSTACLES" : "OBSTACLE";
	}

	@Override
	public boolean productionIsReward()
	{
		return true;
	}

	@Override
	public String failureNoun(boolean plural)
	{
		return plural ? "falls" : "fall";
	}

	@Override
	public String failureTooltip()
	{
		return "Seen from the game itself (dropped off the course and took damage); never guessed from a missing XP drop";
	}

	@Override
	public String rewardTooltip()
	{
		return "Collected this session (picked up or handed out by the course; withdrawals and what you already carried never count)";
	}

	@Override
	public String rewardSourcesNoun()
	{
		return "courses";
	}

	/** The course's reward, or null when it hands out none. */
	public static AgilityCourse.Reward rewardOf(String methodKey)
	{
		AgilityCourse c = AgilityCourses.byKey(methodKey);
		return c == null ? null : c.getReward();
	}

	static AgilityCourse.Reward rewardFor(int itemId)
	{
		for (AgilityCourse c : AgilityCourses.all())
		{
			if (c.getReward() != null && c.getReward().getItemId() == itemId)
			{
				return c.getReward();
			}
		}
		return null;
	}

	/** Every reward resource (tests / presentation). */
	public static Map<Integer, SkillResource> rewards()
	{
		return Collections.unmodifiableMap(REWARDS);
	}
}
