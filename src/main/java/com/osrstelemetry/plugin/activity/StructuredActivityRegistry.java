package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Indexes {@link StructuredActivityDefinition}s by template region id,
 * activity key and reward event name, so every lookup the per-tick
 * collector and the classifier make is a single hash lookup (no scans).
 * Immutable after construction; {@link #defaults()} is shared.
 */
public final class StructuredActivityRegistry
{
	private static final StructuredActivityRegistry DEFAULTS = new StructuredActivityRegistry(StructuredActivityCatalog.definitions());

	private final List<StructuredActivityDefinition> definitions;
	private final Map<Integer, StructuredActivityDefinition> byRegion = new HashMap<>();
	private final Map<String, StructuredActivityDefinition> byKey = new HashMap<>();
	private final Map<String, StructuredRewardSource> rewardByKeyOrAlias = new HashMap<>();
	private final Map<Integer, StructuredRewardSource> rewardByInterface = new HashMap<>();
	private final java.util.List<StructuredRewardSource> claimMessageRewards = new java.util.ArrayList<>();

	public StructuredActivityRegistry(List<StructuredActivityDefinition> definitions)
	{
		this.definitions = Collections.unmodifiableList(definitions);
		for (StructuredActivityDefinition d : definitions)
		{
			if (byKey.put(d.getKey(), d) != null)
			{
				throw new IllegalArgumentException("Duplicate structured activity key: " + d.getKey());
			}
			for (Integer region : d.getRegionPolicies().keySet())
			{
				if (byRegion.put(region, d) != null)
				{
					throw new IllegalArgumentException("Region " + region + " claimed by two structured activities");
				}
			}
			for (StructuredRewardSource reward : d.getRewardSources())
			{
				indexReward(reward.getKey(), reward);
				for (String alias : reward.getAliases())
				{
					indexReward(alias, reward);
				}
				if (reward.getProducer() == StructuredRewardSource.Producer.REWARD_INTERFACE
					&& rewardByInterface.put(reward.getInterfaceId(), reward) != null)
				{
					throw new IllegalArgumentException("Reward interface " + reward.getInterfaceId() + " claimed twice");
				}
				if (reward.getProducer() == StructuredRewardSource.Producer.CLAIM_MESSAGE)
				{
					claimMessageRewards.add(reward);
				}
			}
		}
	}

	public static StructuredActivityRegistry defaults()
	{
		return DEFAULTS;
	}

	public List<StructuredActivityDefinition> all()
	{
		return definitions;
	}

	public StructuredActivityDefinition forRegion(int regionId)
	{
		return byRegion.get(regionId);
	}

	public StructuredActivityDefinition byKey(String key)
	{
		return key == null ? null : byKey.get(key);
	}

	private void indexReward(String keyOrAlias, StructuredRewardSource reward)
	{
		StructuredRewardSource previous = rewardByKeyOrAlias.put(StructuredActivityDefinition.normalize(keyOrAlias), reward);
		if (previous != null && previous != reward)
		{
			throw new IllegalArgumentException("Reward source name " + keyOrAlias + " claimed twice");
		}
	}

	/**
	 * The canonical reward source for a canonical key, its display name or a
	 * declared alias (exact, case-insensitive). Anything else -- an unrelated
	 * chest, a casket, a supply crate -- is null: never guessed.
	 */
	public StructuredRewardSource rewardSource(String keyOrName)
	{
		return keyOrName == null ? null : rewardByKeyOrAlias.get(StructuredActivityDefinition.normalize(keyOrName));
	}

	/** The reward source whose reward interface this is, or null. */
	public StructuredRewardSource rewardForInterface(int interfaceId)
	{
		return rewardByInterface.get(interfaceId);
	}

	/** The reward source whose claim message this is, in this region, or null. */
	public StructuredRewardSource rewardForClaimMessage(String message, int regionId)
	{
		for (StructuredRewardSource reward : claimMessageRewards)
		{
			if (reward.isClaimMessage(message, regionId))
			{
				return reward;
			}
		}
		return null;
	}

	/** Completion label for a session's activity key, or null. */
	public String completionLabel(String activityKey)
	{
		StructuredActivityDefinition d = byKey(activityKey);
		return d == null ? null : d.getCompletionLabel();
	}

	/** A chat completion match: the owning definition plus its optional authoritative count. */
	public static final class ChatCompletion
	{
		private final StructuredActivityDefinition definition;
		private final Integer count;

		ChatCompletion(StructuredActivityDefinition definition, Integer count)
		{
			this.definition = definition;
			this.count = count;
		}

		public StructuredActivityDefinition getDefinition()
		{
			return definition;
		}

		public Integer getCount()
		{
			return count;
		}
	}

	/** Runs each activity's own isolated completion pattern against a tag-free game message. */
	public ChatCompletion matchCompletionChat(String message)
	{
		if (message == null)
		{
			return null;
		}
		for (StructuredActivityDefinition d : definitions)
		{
			if (d.getCompletionChatPattern() == null)
			{
				continue;
			}
			Matcher m = d.getCompletionChatPattern().matcher(message);
			if (m.find())
			{
				Integer count = null;
				if (m.groupCount() >= 1 && m.group(1) != null)
				{
					count = Integer.parseInt(m.group(1).replace(",", ""));
				}
				return new ChatCompletion(d, count);
			}
		}
		return null;
	}
}
