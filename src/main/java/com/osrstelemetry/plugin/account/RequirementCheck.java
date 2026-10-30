package com.osrstelemetry.plugin.account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of a {@link RequirementSet} against one account: what is met,
 * what is known not to be met, and what Spyglass lacks the evidence to judge.
 * The three lists are disjoint and never merged: missing evidence is never
 * reported as "unmet" and never as "met".
 */
public final class RequirementCheck
{
	private final List<String> met = new ArrayList<>();
	private final List<String> unmet = new ArrayList<>();
	private final List<String> missing = new ArrayList<>();

	void met(String s)
	{
		met.add(s);
	}

	void unmet(String s)
	{
		unmet.add(s);
	}

	void missing(String s)
	{
		missing.add(s);
	}

	public List<String> getMet()
	{
		return Collections.unmodifiableList(met);
	}

	public List<String> getUnmet()
	{
		return Collections.unmodifiableList(unmet);
	}

	/** Each entry names the requirement and the evidence that is missing. */
	public List<String> getMissing()
	{
		return Collections.unmodifiableList(missing);
	}

	public Known overall()
	{
		if (!unmet.isEmpty())
		{
			return Known.NO;
		}
		return missing.isEmpty() ? Known.YES : Known.UNKNOWN;
	}
}
