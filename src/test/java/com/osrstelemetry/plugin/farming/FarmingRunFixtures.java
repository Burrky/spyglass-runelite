package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.account.AccountFixtures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Run intents for tests outside this package. */
public final class FarmingRunFixtures
{
	private FarmingRunFixtures()
	{
	}

	/** A herb run replanting any crop on these patches. */
	public static FarmingRunIntent herbRun(String id, boolean preparing, String... patchIds)
	{
		Map<String, String> plans = new LinkedHashMap<>();
		for (String p : patchIds)
		{
			plans.put(p, FarmingRunIntent.REPLANT_ANY);
		}
		return new FarmingRunIntent(FarmingRunIntent.SCHEMA, id, FarmingRunType.HERB, AccountFixtures.NOW, AccountFixtures.NOW,
			FarmingRunIntent.State.ACTIVE, null, null, new ArrayList<>(plans.keySet()), Collections.emptyList(), Collections.emptyList(), plans,
			new FarmingRunIntent.Options("NONE", false), Collections.emptyMap()).withPreparing(preparing);
	}
}
