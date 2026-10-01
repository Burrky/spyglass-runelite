package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One canonical reward source of a structured activity (the Barrows chest,
 * the Tempoross reward pool, ...). {@link #getKey()} is the stable identity
 * used everywhere (telemetry, Current Session, Loot Tracker); the display
 * name is presentation only; aliases let older observations of the SAME
 * causal source (e.g. RuneLite's "Reward pool (Tempoross)" event name
 * written by an earlier build) canonicalize to the same key.
 *
 * How the reward is observed is data too (see {@link Producer}), so the
 * collector interprets every source generically and each source has
 * exactly ONE producer -- one claim, one event.
 */
public final class StructuredRewardSource
{
	/** How Spyglass itself observes the claim -- never via another plugin. */
	public enum Producer
	{
		/**
		 * The game opens a reward interface whose item container holds the
		 * exact reward (e.g. the Barrows chest): WidgetLoaded(interfaceId),
		 * then read containerId.
		 */
		REWARD_INTERFACE,

		/**
		 * The game prints a claim message inside the reward region; the
		 * reward is the items ADDED by the next inventory change within a
		 * few ticks (one bounded, one-shot correlation -- never an open-ended
		 * inventory watch).
		 */
		CLAIM_MESSAGE
	}

	private final String key;
	private final String displayName;
	private final String activityKey;
	private final Producer producer;
	private final int interfaceId;
	private final int containerId;
	private final String claimMessage;
	private final boolean claimMessageIsPrefix;
	private final Set<Integer> claimRegions;
	private final Set<String> aliases;

	private StructuredRewardSource(String key, String displayName, String activityKey, Producer producer, int interfaceId, int containerId,
		String claimMessage, boolean claimMessageIsPrefix, Set<Integer> claimRegions, Set<String> aliases)
	{
		this.key = Objects.requireNonNull(key, "key");
		this.displayName = Objects.requireNonNull(displayName, "displayName");
		this.activityKey = Objects.requireNonNull(activityKey, "activityKey");
		this.producer = Objects.requireNonNull(producer, "producer");
		this.interfaceId = interfaceId;
		this.containerId = containerId;
		this.claimMessage = claimMessage;
		this.claimMessageIsPrefix = claimMessageIsPrefix;
		this.claimRegions = Collections.unmodifiableSet(new LinkedHashSet<>(claimRegions));
		this.aliases = Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
	}

	static StructuredRewardSource rewardInterface(String key, String displayName, String activityKey, int interfaceId, int containerId,
		Set<String> aliases)
	{
		return new StructuredRewardSource(key, displayName, activityKey, Producer.REWARD_INTERFACE, interfaceId, containerId,
			null, false, Collections.<Integer>emptySet(), aliases);
	}

	static StructuredRewardSource claimMessage(String key, String displayName, String activityKey, String message, boolean prefix,
		Set<Integer> regions, Set<String> aliases)
	{
		return new StructuredRewardSource(key, displayName, activityKey, Producer.CLAIM_MESSAGE, -1, -1, message, prefix, regions, aliases);
	}

	public String getKey()
	{
		return key;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	/** The structured activity this reward belongs to. */
	public String getActivityKey()
	{
		return activityKey;
	}

	public Producer getProducer()
	{
		return producer;
	}

	public int getInterfaceId()
	{
		return interfaceId;
	}

	public int getContainerId()
	{
		return containerId;
	}

	public Set<Integer> getClaimRegions()
	{
		return claimRegions;
	}

	Set<String> getAliases()
	{
		return aliases;
	}

	/** True when a tag-free game message, seen in {@code regionId}, is this source's claim message. */
	public boolean isClaimMessage(String message, int regionId)
	{
		if (producer != Producer.CLAIM_MESSAGE || message == null || !claimRegions.contains(regionId))
		{
			return false;
		}
		return claimMessageIsPrefix ? message.startsWith(claimMessage) : message.contains(claimMessage);
	}
}
