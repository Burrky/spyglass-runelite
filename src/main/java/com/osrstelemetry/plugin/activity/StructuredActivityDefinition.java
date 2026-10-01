package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, data-only description of one structured activity (minigame,
 * activity boss, repeatable arena). Everything the generic machinery needs
 * to know about an activity lives here, so adding an activity never means
 * touching the classifier, the lifecycle engine or the coordinator.
 *
 * RuneLite-free: regions are plain template region ids, skills are
 * lower-case skill names, NPC/boss/reward names are exact in-game strings
 * compared case-insensitively.
 */
public final class StructuredActivityDefinition
{
	private static final Set<String> COMBAT_SKILLS = Collections.unmodifiableSet(new LinkedHashSet<>(
		java.util.Arrays.asList("attack", "strength", "defence", "ranged", "magic", "hitpoints")));

	private final String key;
	private final String displayName;
	private final Map<Integer, LocationPolicy> regionPolicies;
	private final Set<String> expectedSkills;
	private final boolean combatExpected;
	private final Set<String> subordinateNames;
	private final Set<String> completionBossNames;
	private final Set<String> completionCountNames;
	private final Pattern completionChatPattern;
	private final java.util.List<StructuredRewardSource> rewardSources;
	private final String completionLabel;
	/** Player-facing name of the activity's native reward currency ("Permits"), or null. */
	private final String currencyLabel;

	private StructuredActivityDefinition(Builder b)
	{
		this.key = Objects.requireNonNull(b.key, "key");
		this.displayName = Objects.requireNonNull(b.displayName, "displayName");
		this.regionPolicies = Collections.unmodifiableMap(new LinkedHashMap<>(b.regionPolicies));
		this.expectedSkills = Collections.unmodifiableSet(new LinkedHashSet<>(b.expectedSkills));
		this.combatExpected = b.combatExpected;
		this.subordinateNames = Collections.unmodifiableSet(new LinkedHashSet<>(b.subordinateNames));
		this.completionBossNames = Collections.unmodifiableSet(new LinkedHashSet<>(b.completionBossNames));
		this.completionCountNames = Collections.unmodifiableSet(new LinkedHashSet<>(b.completionCountNames));
		this.completionChatPattern = b.completionChatPattern;
		java.util.List<StructuredRewardSource> sources = new java.util.ArrayList<>();
		for (RewardSpec r : b.rewardSpecs)
		{
			sources.add(r.build(b.key));
		}
		this.rewardSources = Collections.unmodifiableList(sources);
		this.completionLabel = b.completionLabel;
		this.currencyLabel = b.currencyLabel;
	}

	public static Builder builder(String key, String displayName)
	{
		return new Builder(key, displayName);
	}

	static String normalize(String s)
	{
		return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
	}

	public String getKey()
	{
		return key;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public Map<Integer, LocationPolicy> getRegionPolicies()
	{
		return regionPolicies;
	}

	/** Null when the region is not part of this activity. */
	public LocationPolicy policyFor(int regionId)
	{
		return regionPolicies.get(regionId);
	}

	/** Expected XP: compatible evidence in the location, never fragments the activity. */
	public boolean isExpectedSkill(String skill)
	{
		String s = normalize(skill);
		return s != null && (expectedSkills.contains(s) || (combatExpected && COMBAT_SKILLS.contains(s)));
	}

	/** Bosses/NPCs that belong to the activity and never switch its identity. */
	public boolean isSubordinate(String name)
	{
		String n = normalize(name);
		return n != null && (subordinateNames.contains(n) || completionBossNames.contains(n));
	}

	/** A "Your X kill count is" boss whose kill IS one completion of this activity. */
	public boolean isCompletionBoss(String bossName)
	{
		return completionBossNames.contains(normalize(bossName));
	}

	/** A "Your X count is" completion counter that IS one completion of this activity. */
	public boolean isCompletionCount(String activityName)
	{
		return completionCountNames.contains(normalize(activityName));
	}

	/** This activity's own, isolated chat completion fallback (null when it has none). */
	public Pattern getCompletionChatPattern()
	{
		return completionChatPattern;
	}

	/** This activity's canonical reward sources (each with exactly one producer). */
	public java.util.List<StructuredRewardSource> getRewardSources()
	{
		return rewardSources;
	}

	/** Plural label for the completion metric ("Games"); null when no completion source is known. */
	public String getCompletionLabel()
	{
		return completionLabel;
	}

	public String getCurrencyLabel()
	{
		return currencyLabel;
	}

	public boolean hasCompletionSource()
	{
		return completionLabel != null
			&& (completionChatPattern != null || !completionBossNames.isEmpty() || !completionCountNames.isEmpty());
	}

	private interface RewardSpec
	{
		StructuredRewardSource build(String activityKey);
	}

	public static final class Builder
	{
		private final String key;
		private final String displayName;
		private final Map<Integer, LocationPolicy> regionPolicies = new LinkedHashMap<>();
		private final Set<String> expectedSkills = new LinkedHashSet<>();
		private boolean combatExpected;
		private final Set<String> subordinateNames = new LinkedHashSet<>();
		private final Set<String> completionBossNames = new LinkedHashSet<>();
		private final Set<String> completionCountNames = new LinkedHashSet<>();
		private Pattern completionChatPattern;
		private final java.util.List<RewardSpec> rewardSpecs = new java.util.ArrayList<>();
		private String completionLabel;
		private String currencyLabel;

		private Builder(String key, String displayName)
		{
			this.key = key;
			this.displayName = displayName;
		}

		public Builder region(LocationPolicy policy, int... regionIds)
		{
			for (int id : regionIds)
			{
				regionPolicies.put(id, policy);
			}
			return this;
		}

		public Builder expectedSkills(String... skills)
		{
			for (String s : skills)
			{
				expectedSkills.add(normalize(s));
			}
			return this;
		}

		public Builder combatExpected()
		{
			this.combatExpected = true;
			return this;
		}

		public Builder subordinates(String... names)
		{
			for (String n : names)
			{
				subordinateNames.add(normalize(n));
			}
			return this;
		}

		public Builder completionBoss(String... bossNames)
		{
			for (String n : bossNames)
			{
				completionBossNames.add(normalize(n));
			}
			return this;
		}

		public Builder completionCount(String... activityNames)
		{
			for (String n : activityNames)
			{
				completionCountNames.add(normalize(n));
			}
			return this;
		}

		/** Group 1, when present, must be the authoritative running count. */
		public Builder completionChat(String regex)
		{
			this.completionChatPattern = Pattern.compile(regex);
			return this;
		}

		/** A reward shown in a reward interface whose item container holds the exact reward. */
		public Builder rewardInterface(String sourceKey, String displayName, int interfaceId, int containerId, String... aliases)
		{
			rewardSpecs.add(activityKey -> StructuredRewardSource.rewardInterface(sourceKey, displayName, activityKey,
				interfaceId, containerId, aliasSet(displayName, aliases)));
			return this;
		}

		/**
		 * A reward announced by a claim message in one of {@code regions}
		 * (prefix match when {@code prefix}, else substring), whose items are
		 * the next bounded inventory addition.
		 */
		public Builder rewardClaimMessage(String sourceKey, String displayName, String message, boolean prefix, int[] regions, String... aliases)
		{
			Set<Integer> regionSet = new LinkedHashSet<>();
			for (int r : regions)
			{
				regionSet.add(r);
			}
			rewardSpecs.add(activityKey -> StructuredRewardSource.claimMessage(sourceKey, displayName, activityKey,
				message, prefix, regionSet, aliasSet(displayName, aliases)));
			return this;
		}

		private static Set<String> aliasSet(String displayName, String... aliases)
		{
			Set<String> set = new LinkedHashSet<>();
			set.add(normalize(displayName));
			for (String a : aliases)
			{
				set.add(normalize(a));
			}
			return set;
		}

		public Builder completionLabel(String label)
		{
			this.completionLabel = label;
			return this;
		}

		/** Names the activity's native reward currency (session stats: "Permits +11"). */
		public Builder currencyLabel(String label)
		{
			this.currencyLabel = label;
			return this;
		}

		public StructuredActivityDefinition build()
		{
			return new StructuredActivityDefinition(this);
		}
	}
}
