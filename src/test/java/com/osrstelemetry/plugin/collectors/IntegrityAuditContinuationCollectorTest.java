package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredRewardSource;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integrity audit continuation (structured collector across disable /
 * re-enable): an armed reward claim never survives into the next enable,
 * while the remembered location does -- so leaving an activity while the
 * plugin was off still produces the exit event.
 */
public class IntegrityAuditContinuationCollectorTest
{
	private static final long ACCOUNT = 700_900_903L;
	private static final int TEMPOROSS_COVE = 12588;
	private static final int OUTSIDE = 12850;

	private Path testRoot;
	private EventLedger ledger;
	private StructuredActivityCollector collector;
	private final List<Object[]> events = new ArrayList<>();
	private final StructuredActivityRegistry registry = StructuredActivityRegistry.defaults();

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("structured-reenable-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
		ledger = new EventLedger(new Gson());
		ledger.start();
		ledger.addListener((TelemetryEventListener) (account, type, payload, at) ->
		{
			synchronized (events)
			{
				events.add(new Object[] {type, payload});
			}
		});
		Client client = (Client) java.lang.reflect.Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) ->
			{
				if ("getAccountHash".equals(method.getName()))
				{
					return ACCOUNT;
				}
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class)
				{
					return false;
				}
				if (rt.isPrimitive() && rt != void.class)
				{
					return rt == long.class ? (Object) 0L : (Object) 0;
				}
				return null;
			});
		collector = new StructuredActivityCollector(client, id -> "item" + id, ledger, registry);
	}

	@After
	public void tearDown() throws Exception
	{
		ledger.shutdown();
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	private List<Object> payloads(EventType type)
	{
		List<Object> out = new ArrayList<>();
		synchronized (events)
		{
			for (Object[] e : events)
			{
				if (e[0] == type)
				{
					out.add(e[1]);
				}
			}
		}
		return out;
	}

	private static Map<Integer, Long> inv(int itemId, long qty)
	{
		Map<Integer, Long> m = new HashMap<>();
		m.put(itemId, qty);
		return m;
	}

	@Test
	public void claimArmedBeforeDisable_cannotConsumeAnInventoryChangeAfterReEnable()
	{
		StructuredRewardSource pool = registry.rewardSource("tempoross_reward_pool");
		collector.armClaim(pool, inv(995, 100));
		collector.disarmPendingClaim(); // plugin startUp after a disable
		collector.onInventoryChanged(inv(995, 5100), id -> "Coins"); // gained while playing on
		assertEquals(0, payloads(EventType.STRUCTURED_REWARD).size());
	}

	@Test
	public void control_armedClaim_isConsumedOnce()
	{
		StructuredRewardSource pool = registry.rewardSource("tempoross_reward_pool");
		collector.armClaim(pool, inv(995, 100));
		collector.onInventoryChanged(inv(995, 5100), id -> "Coins");
		collector.onInventoryChanged(inv(995, 9100), id -> "Coins");
		assertEquals(1, payloads(EventType.STRUCTURED_REWARD).size());
	}

	@Test
	public void leavingTheActivityWhileDisabled_stillSendsTheExitAfterReEnable()
	{
		collector.onRegion(TEMPOROSS_COVE);
		assertEquals(1, payloads(EventType.STRUCTURED_ACTIVITY_STATE).size());
		collector.disarmPendingClaim(); // disable ... walk out ... enable
		collector.onRegion(OUTSIDE);
		List<Object> states = payloads(EventType.STRUCTURED_ACTIVITY_STATE);
		assertEquals("exit event after re-enable", 2, states.size());
		assertNull(((EventPayloads.StructuredActivityState) states.get(1)).getActivityKey());
	}
}
