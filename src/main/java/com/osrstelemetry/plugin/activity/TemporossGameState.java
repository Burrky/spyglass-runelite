package com.osrstelemetry.plugin.activity;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of the CURRENT Tempoross game, read from the game
 * itself while its in-game HUD is up: Energy / Essence / Storm Intensity
 * from the HUD's own text widgets (gameval InterfaceID.TemporossHud), and
 * the player's points from the game's varbit (VarbitID.TEMPOROSS_CURRENT_POINTS).
 * A value that cannot be read is null and simply not shown -- never guessed.
 * Runtime-only presentation state: no events, not persisted, not lifecycle
 * evidence.
 */
public final class TemporossGameState
{
	private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");

	private final Integer energy;
	private final Integer essence;
	private final Integer storm;
	private final Integer points;

	private TemporossGameState(Integer energy, Integer essence, Integer storm, Integer points)
	{
		this.energy = energy;
		this.essence = essence;
		this.storm = storm;
		this.points = points;
	}

	/** Null when nothing at all could be read. */
	public static TemporossGameState of(Integer energy, Integer essence, Integer storm, Integer points)
	{
		if (energy == null && essence == null && storm == null && points == null)
		{
			return null;
		}
		return new TemporossGameState(energy, essence, storm, points);
	}

	/** The percentage in a HUD text such as "Energy: 87%" (0-100), or null when the text holds none. */
	public static Integer percentOf(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = PERCENT.matcher(text);
		if (!m.find())
		{
			return null;
		}
		int value = Integer.parseInt(m.group(1));
		return value <= 100 ? value : null;
	}

	public Integer getEnergy()
	{
		return energy;
	}

	public Integer getEssence()
	{
		return essence;
	}

	public Integer getStorm()
	{
		return storm;
	}

	public Integer getPoints()
	{
		return points;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof TemporossGameState))
		{
			return false;
		}
		TemporossGameState s = (TemporossGameState) o;
		return Objects.equals(energy, s.energy) && Objects.equals(essence, s.essence)
			&& Objects.equals(storm, s.storm) && Objects.equals(points, s.points);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(energy, essence, storm, points);
	}

	@Override
	public String toString()
	{
		return "Tempoross{energy=" + energy + ", essence=" + essence + ", storm=" + storm + ", points=" + points + "}";
	}
}
