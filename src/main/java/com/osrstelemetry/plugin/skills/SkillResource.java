package com.osrstelemetry.plugin.skills;

import java.util.Objects;

/**
 * One kind of resource a skill can PRODUCE, as a skill adapter defines it
 * (generic: an item id, a display name, a role and an optional group).
 * <p>
 * ROLE: PRIMARY is what the method is for (Iron ore when mining iron);
 * SECONDARY is a legitimate by-product of the same skill action (a gem from
 * the mining gem table). The live companion never folds the two into one
 * number the player would read as the primary resource.
 * <p>
 * GROUP: an optional presentation family ("gem") so several secondary
 * resources can be shown on one compact line ("Gems: Sapphire 3 • Ruby 1").
 */
public final class SkillResource
{
	public enum Role
	{
		PRIMARY,
		SECONDARY
	}

	private final int itemId;
	private final String name;
	private final String shortName;
	private final Role role;
	private final String group;
	private final boolean stackable;

	public SkillResource(int itemId, String name, String shortName, Role role, String group, boolean stackable)
	{
		this.itemId = itemId;
		this.name = Objects.requireNonNull(name, "name");
		this.shortName = shortName == null ? name : shortName;
		this.role = Objects.requireNonNull(role, "role");
		this.group = group;
		this.stackable = stackable;
	}

	public int getItemId()
	{
		return itemId;
	}

	/** The item's own name ("Iron ore", "Uncut ruby"). */
	public String getName()
	{
		return name;
	}

	/** A compact name for breakdown lines ("Iron", "Ruby"). */
	public String getShortName()
	{
		return shortName;
	}

	public Role getRole()
	{
		return role;
	}

	/** Presentation family ("gem"), or null. */
	public String getGroup()
	{
		return group;
	}

	/** Stackable resources never use up more than one inventory slot. */
	public boolean isStackable()
	{
		return stackable;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
