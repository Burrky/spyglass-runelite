package com.osrstelemetry.plugin.travel;

import java.util.Collections;
import java.util.List;

/**
 * One transport's availability for one account at one moment: a single final
 * {@link Availability} plus its reasons, kept apart so the diagnostic never
 * blurs them:
 * <ul>
 *   <li>{@link #getHave()}: what was found and where (carried, last seen in a
 *   storage with its age, depleted variants, charge knowledge);</li>
 *   <li>{@link #getMet()}: requirements evidenced as met;</li>
 *   <li>{@link #getUnmet()}: requirements evidenced as not met;</li>
 *   <li>{@link #getMissing()}: the evidence Spyglass lacks, each naming what it
 *   would decide.</li>
 * </ul>
 */
public final class TransportAvailability
{
	private final TransportDef transport;
	private final Availability state;
	private final List<String> have;
	private final List<String> met;
	private final List<String> unmet;
	private final List<String> missing;

	TransportAvailability(TransportDef transport, Availability state, List<String> have, List<String> met, List<String> unmet, List<String> missing)
	{
		this.transport = transport;
		this.state = state;
		this.have = Collections.unmodifiableList(have);
		this.met = Collections.unmodifiableList(met);
		this.unmet = Collections.unmodifiableList(unmet);
		this.missing = Collections.unmodifiableList(missing);
	}

	public TransportDef getTransport()
	{
		return transport;
	}

	public Availability getState()
	{
		return state;
	}

	public List<String> getHave()
	{
		return have;
	}

	public List<String> getMet()
	{
		return met;
	}

	public List<String> getUnmet()
	{
		return unmet;
	}

	public List<String> getMissing()
	{
		return missing;
	}
}
