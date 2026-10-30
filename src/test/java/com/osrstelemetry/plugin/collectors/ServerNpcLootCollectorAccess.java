package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.events.EventPayloads;
import java.util.List;

/** Test access to ServerNpcLootCollector's pure mapping. */
public final class ServerNpcLootCollectorAccess
{
	private ServerNpcLootCollectorAccess()
	{
	}

	public static EventPayloads.StructuredReward independentReward(int npcId, String name, List<EventPayloads.ServerNpcLootItem> items)
	{
		return ServerNpcLootCollector.independentReward(npcId, name, items);
	}
}
