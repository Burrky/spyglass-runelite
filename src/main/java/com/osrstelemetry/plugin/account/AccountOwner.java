package com.osrstelemetry.plugin.account;

import java.util.Objects;

/**
 * Whose account knowledge this is: an account hash plus a RuneScape profile.
 * Account capability is collected and persisted for the STANDARD profile only
 * (canonical §22); evidence from one owner is never shown, merged or written
 * under another.
 */
public final class AccountOwner
{
	public static final String STANDARD_PROFILE = "standard";

	private final long accountHash;
	private final String profileKey;

	public AccountOwner(long accountHash, String profileKey)
	{
		this.accountHash = accountHash;
		this.profileKey = Objects.requireNonNull(profileKey);
	}

	public static AccountOwner standard(long accountHash)
	{
		return new AccountOwner(accountHash, STANDARD_PROFILE);
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
		if (!(o instanceof AccountOwner))
		{
			return false;
		}
		AccountOwner other = (AccountOwner) o;
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
