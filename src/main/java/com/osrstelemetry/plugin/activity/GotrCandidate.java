package com.osrstelemetry.plugin.activity;

import java.util.Objects;

/**
 * One altar the player could enter right now in a Guardians of the Rift
 * game, and how: through its OPEN rift, or with a held portal TALISMAN of
 * that altar (OSRS Wiki: the talisman enters the guardian even while it is
 * inactive, and is consumed doing so unless the guardian is active).
 * {@code usable}: Runecraft level + quest, read from the game; null = unknown.
 */
public final class GotrCandidate
{
	public enum Access
	{
		OPEN("Open"),
		TALISMAN("Talisman");

		private final String label;

		Access(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private final GotrAltar altar;
	private final Access access;
	private final Boolean usable;

	public GotrCandidate(GotrAltar altar, Access access, Boolean usable)
	{
		this.altar = Objects.requireNonNull(altar);
		this.access = Objects.requireNonNull(access);
		this.usable = usable;
	}

	public GotrAltar getAltar()
	{
		return altar;
	}

	public Access getAccess()
	{
		return access;
	}

	public Boolean getUsable()
	{
		return usable;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof GotrCandidate))
		{
			return false;
		}
		GotrCandidate that = (GotrCandidate) o;
		return altar == that.altar && access == that.access && Objects.equals(usable, that.usable);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(altar, access, usable);
	}

	@Override
	public String toString()
	{
		return altar + "/" + access + (usable == null ? "?" : usable ? "" : "(locked)");
	}
}
