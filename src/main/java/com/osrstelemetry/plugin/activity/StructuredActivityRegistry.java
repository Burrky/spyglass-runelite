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
	/**
	 * Rewards that belong to no activity, reported by the game's own loot tracker as NPC loot from an NPC that is never killed
	 * for it. The Brimstone chest (Mount Karuulm, opened with a brimstone key from Konar's tasks): the game reports its reward as
	 * loot from Konar quo Maten (npc 8623, gameval {@code SLAYER_MASTER_8}; seen in Josh's own events, 2026-09-27: a chest of
	 * 5,095 noted pure essence during a Bloodveld task). Konar has no combat and drops nothing, so loot named for her is the chest.
	 */
	private static final List<StructuredRewardSource> INDEPENDENT = Collections.singletonList(
		StructuredRewardSource.serverNpcLoot("brimstone_chest", "Brimstone chest", 8623, Collections.singleton("Konar quo Maten")));

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
		for (StructuredRewardSource reward : INDEPENDENT)
		{
			indexReward(reward.getKey(), reward);
			for (String alias : reward.getAliases())
			{
				indexReward(alias, reward);
			}
			rewardByServerNpc.put(reward.getNpcId(), reward);
		}
	}

	private final Map<Integer, StructuredRewardSource> rewardByServerNpc = new HashMap<>();

	/** The independent reward the game's loot tracker reports as loot from this NPC (id first, else its name), or null. */
	public StructuredRewardSource rewardForServerNpcLoot(int npcId, String npcName)
	{
		StructuredRewardSource byId = npcId > 0 ? rewardByServerNpc.get(npcId) : null;
		if (byId != null)
		{
			return byId;
		}
		StructuredRewardSource byName = rewardSource(npcName);
		return byName != null && byName.isIndependent() ? byName : null;
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
		private final Integer amount;

		ChatCompletion(StructuredActivityDefinition definition, Integer count)
		{
			this(definition, count, null);
		}

		ChatCompletion(StructuredActivityDefinition definition, Integer count, Integer amount)
		{
			this.definition = definition;
			this.count = count;
			this.amount = amount;
		}

		/** The currency award the same message carries (named group {@code amount}), or null. */
		public Integer getAmount()
		{
			return amount;
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
		return matchCompletion(message, false);
	}

	/**
	 * The same, for an NPC dialogue line (ChatMessageType.DIALOG, "Name|text"
	 * with the speaker already removed): only activities that declare
	 * {@code completionInDialogue} are considered.
	 */
	public ChatCompletion matchCompletionDialogue(String text)
	{
		return matchCompletion(text, true);
	}

	private ChatCompletion matchCompletion(String message, boolean dialogue)
	{
		if (message == null)
		{
			return null;
		}
		for (StructuredActivityDefinition d : definitions)
		{
			if (d.getCompletionChatPattern() == null || (dialogue && !d.isCompletionInDialogue()))
			{
				continue;
			}
			Matcher m = d.getCompletionChatPattern().matcher(message);
			if (m.find())
			{
				String regex = d.getCompletionChatPattern().pattern();
				boolean namedCount = regex.contains("(?<count>");
				boolean namedAmount = regex.contains("(?<amount>");
				Integer count = null;
				if (namedCount)
				{
					count = parseNumber(m.group("count"));
				}
				else if (!namedAmount && m.groupCount() >= 1)
				{
					count = parseNumber(m.group(1));
				}
				Integer amount = namedAmount ? parseNumber(m.group("amount")) : null;
				return new ChatCompletion(d, count, amount);
			}
		}
		return null;
	}

	private static Integer parseNumber(String digits)
	{
		if (digits == null)
		{
			return null;
		}
		try
		{
			return Integer.parseInt(digits.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
