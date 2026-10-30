package com.osrstelemetry.plugin.hunter;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The player's selection on the bird house page (pass 30): which spaces the next run covers and the bird house tier they
 * chose. Saved per RuneScape profile ({@code birdhouseChoice}); Spyglass never picks a tier. Field names are the JSON;
 * immutable.
 */
public final class BirdhouseChoice
{
	/** Space names left out of runs (absent: all included, the first-use default). */
	private Set<String> excluded;
	/** The chosen tier's name, or null (none chosen yet). */
	private String tier;

	public static BirdhouseChoice empty()
	{
		return new BirdhouseChoice();
	}

	private BirdhouseChoice copy()
	{
		BirdhouseChoice c = new BirdhouseChoice();
		c.excluded = excluded == null ? null : new TreeSet<>(excluded);
		c.tier = tier;
		return c;
	}

	public boolean isIncluded(BirdhouseSpace s)
	{
		return excluded == null || !excluded.contains(s.name());
	}

	public Set<BirdhouseSpace> spaces()
	{
		Set<BirdhouseSpace> out = EnumSet.noneOf(BirdhouseSpace.class);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			if (isIncluded(s))
			{
				out.add(s);
			}
		}
		return out;
	}

	public BirdhouseChoice withIncluded(BirdhouseSpace s, boolean on)
	{
		BirdhouseChoice c = copy();
		if (c.excluded == null)
		{
			c.excluded = new TreeSet<>();
		}
		if (on)
		{
			c.excluded.remove(s.name());
		}
		else
		{
			c.excluded.add(s.name());
		}
		return c;
	}

	public BirdhouseTier getTier()
	{
		return BirdhouseTier.parse(tier);
	}

	public BirdhouseChoice withTier(BirdhouseTier t)
	{
		BirdhouseChoice c = copy();
		c.tier = t == null ? null : t.name();
		return c;
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof BirdhouseChoice))
		{
			return false;
		}
		BirdhouseChoice c = (BirdhouseChoice) o;
		return spaces().equals(c.spaces()) && java.util.Objects.equals(getTier(), c.getTier());
	}

	@Override
	public int hashCode()
	{
		return spaces().hashCode() * 31 + java.util.Objects.hashCode(getTier());
	}
}
