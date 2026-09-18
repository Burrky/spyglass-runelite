package com.osrstelemetry.plugin.session;

import java.util.Objects;

/**
 * A small,
 * immutable value describing "what counts as the same activity" for
 * lifecycle resume/switch decisions — nothing more. This class does
 * NOT implement the gameplay logic that produces these (no
 * XP_CHANGE-means-Woodcutting, no SERVER_NPC_LOOT-means-this-boss
 * heuristics anywhere in this class or elsewhere); the
 * engine only ever receives already-classified identities from a
 * separate classifier/router.
 *
 * activityKey is the stable semantic identity ("gargoyles:catacombs",
 * "zulrah", "woodcutting") — the thing that must match for a resume to
 * be considered "the same activity." displayName ("Gargoyles",
 * "Zulrah", "Woodcutting") is presentation-only and deliberately
 * EXCLUDED from equals()/hashCode(): two ActivityIdentity instances
 * with the same activityType+activityKey but a different displayName
 * (e.g. a future localization or a refined display label) must still
 * compare equal for resume purposes, per the explicit instruction that
 * "equality for resume/switch decisions must use stable semantic
 * identity, not display text alone." This is written out by hand
 * (not Lombok @Data/@EqualsAndHashCode) specifically so that
 * exclusion is impossible to silently break by an unrelated Lombok
 * annotation edit later.
 */
public final class ActivityIdentity
{
	private final ActivityType activityType;
	private final String activityKey;
	private final String displayName;

	public ActivityIdentity(ActivityType activityType, String activityKey, String displayName)
	{
		this.activityType = Objects.requireNonNull(activityType, "activityType");
		this.activityKey = Objects.requireNonNull(activityKey, "activityKey");
		this.displayName = displayName;
	}

	public ActivityType getActivityType()
	{
		return activityType;
	}

	public String getActivityKey()
	{
		return activityKey;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	/**
	 * Deliberately activityType + activityKey ONLY — see class javadoc.
	 * displayName never participates in equality.
	 */
	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ActivityIdentity))
		{
			return false;
		}
		ActivityIdentity other = (ActivityIdentity) o;
		return activityType == other.activityType && activityKey.equals(other.activityKey);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(activityType, activityKey);
	}

	@Override
	public String toString()
	{
		return "ActivityIdentity{" + activityType + ":" + activityKey + " (" + displayName + ")}";
	}
}
