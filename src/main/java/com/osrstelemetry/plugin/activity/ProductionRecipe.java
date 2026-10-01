package com.osrstelemetry.plugin.activity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one confirmed PRODUCTION action looks like, for {@link ProductionTracker}:
 * items consumed, items produced, and the skill whose XP must corroborate
 * the action. Produced items are activity OUTPUT -- deliberately a separate
 * concept from loot/rewards (things received), so later production skills
 * (smithing, cooking, ...) can reuse it without being modelled as loot.
 */
public final class ProductionRecipe
{
	private final Set<Integer> consumedItemIds;
	private final Set<Integer> producedItemIds;
	private final String corroboratingSkill;

	public ProductionRecipe(Set<Integer> consumedItemIds, Set<Integer> producedItemIds, String corroboratingSkill)
	{
		this.consumedItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(consumedItemIds));
		this.producedItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(producedItemIds));
		this.corroboratingSkill = corroboratingSkill;
	}

	public Set<Integer> getConsumedItemIds()
	{
		return consumedItemIds;
	}

	public Set<Integer> getProducedItemIds()
	{
		return producedItemIds;
	}

	/** Lower-case skill name (e.g. "runecraft"). */
	public String getCorroboratingSkill()
	{
		return corroboratingSkill;
	}

	boolean isTracked(int itemId)
	{
		return consumedItemIds.contains(itemId) || producedItemIds.contains(itemId);
	}
}
