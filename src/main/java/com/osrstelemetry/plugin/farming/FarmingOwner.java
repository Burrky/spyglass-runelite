package com.osrstelemetry.plugin.farming;

import java.util.Objects;

/**
 * Whose Farming state something is: an account on a RuneScape profile. Every
 * load, write and in-memory state carries its owner so it can never be applied
 * to, or written for, another account or profile. Only the STANDARD profile
 * is persisted in this era (canonical architecture §22).
 */
public final class FarmingOwner
{
	public static final String STANDARD_PROFILE = "standard";

	private final long accountHash;
	private final String profileKey;

	public FarmingOwner(long accountHash, String profileKey)
	{
		this.accountHash = accountHash;
		this.profileKey = Objects.requireNonNull(profileKey);
	}

	public static FarmingOwner standard(long accountHash)
	{
		return new FarmingOwner(accountHash, STANDARD_PROFILE);
	}

	public long getAccountHash()
	{
		return accountHash;
	}

	public String getProfileKey()
	{
		return profileKey;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof FarmingOwner))
		{
			return false;
		}
		FarmingOwner other = (FarmingOwner) o;
		return accountHash == other.accountHash && profileKey.equals(other.profileKey);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(accountHash, profileKey);
	}

	@Override
	public String toString()
	{
		return accountHash + "/" + profileKey;
	}
}
