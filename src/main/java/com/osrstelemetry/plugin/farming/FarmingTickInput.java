package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * Everything the observer may know about one game tick, captured on the client
 * thread by a later live adapter and handed over as an immutable snapshot. The
 * observer never touches the client itself.
 *
 * {@code transmitValues} holds the raw values of the farming transmit varbits
 * read this tick; a varbit absent from the map was not read, and is never
 * treated as zero.
 */
public final class FarmingTickInput
{
	private final int tick;
	private final long epochMillis;
	private final boolean loggedIn;
	private final long accountHash;
	private final int world;
	private final Set<WorldType> worldTypes;
	private final RuneScapeProfileType profileType;
	private final boolean welcomeScreenVisible;
	private final boolean modalOpen;
	private final boolean instanced;
	private final FarmingLocation location;
	private final Map<Integer, Integer> transmitValues;

	private FarmingTickInput(Builder b)
	{
		this.tick = b.tick;
		this.epochMillis = b.epochMillis;
		this.loggedIn = b.loggedIn;
		this.accountHash = b.accountHash;
		this.world = b.world;
		this.worldTypes = Collections.unmodifiableSet(b.worldTypes.isEmpty() ? EnumSet.noneOf(WorldType.class) : EnumSet.copyOf(b.worldTypes));
		this.profileType = b.profileType;
		this.welcomeScreenVisible = b.welcomeScreenVisible;
		this.modalOpen = b.modalOpen;
		this.instanced = b.instanced;
		this.location = b.location;
		this.transmitValues = Collections.unmodifiableMap(new HashMap<>(b.transmitValues));
	}

	public static Builder builder()
	{
		return new Builder();
	}

	public int getTick()
	{
		return tick;
	}

	public long getEpochMillis()
	{
		return epochMillis;
	}

	public boolean isLoggedIn()
	{
		return loggedIn;
	}

	public long getAccountHash()
	{
		return accountHash;
	}

	public int getWorld()
	{
		return world;
	}

	public Set<WorldType> getWorldTypes()
	{
		return worldTypes;
	}

	/** RuneLite's classification ({@code RuneScapeProfileType.getCurrent(client)}); null when not computed. */
	public RuneScapeProfileType getProfileType()
	{
		return profileType;
	}

	public boolean isWelcomeScreenVisible()
	{
		return welcomeScreenVisible;
	}

	public boolean isModalOpen()
	{
		return modalOpen;
	}

	public boolean isInstanced()
	{
		return instanced;
	}

	public FarmingLocation getLocation()
	{
		return location;
	}

	/** The raw value of {@code varbit} read this tick, or null when it was not read. */
	public Integer transmitValue(int varbit)
	{
		return transmitValues.get(varbit);
	}

	public static final class Builder
	{
		private int tick;
		private long epochMillis;
		private boolean loggedIn = true;
		private long accountHash = -1L;
		private int world;
		private Set<WorldType> worldTypes = EnumSet.noneOf(WorldType.class);
		private RuneScapeProfileType profileType;
		private boolean welcomeScreenVisible;
		private boolean modalOpen;
		private boolean instanced;
		private FarmingLocation location;
		private final Map<Integer, Integer> transmitValues = new HashMap<>();

		public Builder tick(int tick)
		{
			this.tick = tick;
			return this;
		}

		public Builder epochMillis(long epochMillis)
		{
			this.epochMillis = epochMillis;
			return this;
		}

		public Builder loggedIn(boolean loggedIn)
		{
			this.loggedIn = loggedIn;
			return this;
		}

		public Builder accountHash(long accountHash)
		{
			this.accountHash = accountHash;
			return this;
		}

		public Builder world(int world)
		{
			this.world = world;
			return this;
		}

		public Builder worldTypes(Set<WorldType> worldTypes)
		{
			this.worldTypes = worldTypes == null ? EnumSet.noneOf(WorldType.class) : worldTypes;
			return this;
		}

		public Builder profileType(RuneScapeProfileType profileType)
		{
			this.profileType = profileType;
			return this;
		}

		public Builder welcomeScreenVisible(boolean visible)
		{
			this.welcomeScreenVisible = visible;
			return this;
		}

		public Builder modalOpen(boolean modalOpen)
		{
			this.modalOpen = modalOpen;
			return this;
		}

		public Builder instanced(boolean instanced)
		{
			this.instanced = instanced;
			return this;
		}

		public Builder location(FarmingLocation location)
		{
			this.location = location;
			return this;
		}

		public Builder transmit(int varbit, int value)
		{
			this.transmitValues.put(varbit, value);
			return this;
		}

		public FarmingTickInput build()
		{
			return new FarmingTickInput(this);
		}
	}
}
