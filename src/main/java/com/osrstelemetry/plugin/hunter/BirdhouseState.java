package com.osrstelemetry.plugin.hunter;

/**
 * One space's variable decoded. The cache's multiloc table for the space objects ({@code birdhouse_1..4}, multivar
 * {@code birdhouse_transmit_a..d}) gives value 0 the bare space ("Space", option Build) and, per tier {@code t} (0 =
 * regular … 8 = redwood): {@code 3t+1} "&lt;tier&gt; birdhouse (empty)" (option Seeds: built, no seeds), {@code 3t+2}
 * "Birdhouse" with Seeds and Dismantle (some seeds, not yet full), {@code 3t+3} "Birdhouse" with Interact, Seeds, Empty and
 * Reset (armed: seeded, catching birds). RuneLite's Time Tracking ({@code BirdHouseState}, BSD-2) times the house from the
 * armed state the same way. Immutable.
 */
public final class BirdhouseState
{
	public enum Stage
	{
		/** A bare space: nothing built. */
		SPACE,
		/** Built, no seeds. */
		BUILT,
		/** Some seeds in, not yet full. */
		PARTLY_SEEDED,
		/** Seeded: catching birds. */
		SEEDED,
		/** A value outside the table (newer game data): never guessed. */
		UNKNOWN
	}

	/** How long a seeded house takes to fill: about 50 minutes (OSRS Wiki; RuneLite's Time Tracking uses 50 minutes). */
	public static final long FILL_MILLIS = 50L * 60_000L;

	private final int value;
	private final Stage stage;
	private final BirdhouseTier tier;

	private BirdhouseState(int value, Stage stage, BirdhouseTier tier)
	{
		this.value = value;
		this.stage = stage;
		this.tier = tier;
	}

	public static BirdhouseState of(int value)
	{
		if (value == 0)
		{
			return new BirdhouseState(0, Stage.SPACE, null);
		}
		int tier = (value - 1) / 3;
		if (value < 0 || tier >= BirdhouseTier.values().length)
		{
			return new BirdhouseState(value, Stage.UNKNOWN, null);
		}
		int k = (value - 1) % 3;
		return new BirdhouseState(value, k == 0 ? Stage.BUILT : k == 1 ? Stage.PARTLY_SEEDED : Stage.SEEDED, BirdhouseTier.values()[tier]);
	}

	public int getValue()
	{
		return value;
	}

	public Stage getStage()
	{
		return stage;
	}

	/** The house's tier (null for a bare space or an unknown value). */
	public BirdhouseTier getTier()
	{
		return tier;
	}

	/** A house stands on the space (built, partly seeded or seeded). */
	public boolean hasHouse()
	{
		return tier != null;
	}
}
