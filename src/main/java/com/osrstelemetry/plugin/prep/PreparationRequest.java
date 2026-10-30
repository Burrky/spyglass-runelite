package com.osrstelemetry.plugin.prep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a consumer (Farming now; quests, clues, Slayer later) asks Bank
 * Preparation to present (canonical §8.2). Bank Preparation owns presenting
 * needs and their status; it never decides what is needed or which transport
 * is used.
 */
public final class PreparationRequest
{
	private final String consumerId;
	private final String title;
	private final List<ItemNeed> needs;

	public PreparationRequest(String consumerId, String title, List<ItemNeed> needs)
	{
		this.consumerId = consumerId;
		this.title = title;
		Set<String> keys = new LinkedHashSet<>();
		for (ItemNeed n : needs)
		{
			if (!keys.add(n.getKey()))
			{
				throw new IllegalArgumentException("Duplicate need " + n.getKey());
			}
		}
		this.needs = Collections.unmodifiableList(new ArrayList<>(needs));
	}

	public String getConsumerId()
	{
		return consumerId;
	}

	public String getTitle()
	{
		return title;
	}

	public List<ItemNeed> getNeeds()
	{
		return needs;
	}
}
