package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.activity.StructuredRewardSource;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.events.TelemetryEventListener;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.eventbus.Subscribe;
import org.junit.Test;

/**
 * Structured reward capture must not depend on RuneLite's optional Loot
 * Tracker plugin. Its LootReceived event is posted only by
 * LootTrackerPlugin.addLoot() (audited in RuneLite 1.12.39), so any use of it
 * -- or of that plugin -- would make rewards silently vanish when a player
 * disables the plugin.
 */
public class StructuredRewardSignalIndependenceTest
{
	private static String stripComments(String java)
	{
		return java.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	@Test
	public void noProductionClassUsesTheOptionalLootTrackerPlugin() throws Exception
	{
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (Path p : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList()))
			{
				String code = stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
				// Whole identifiers: core RuneLite's NpcLootReceived/PlayerLootReceived are fine.
				for (String forbidden : new String[] {"net\\.runelite\\.client\\.plugins\\.loottracker", "\\b(Plugin)?LootReceived\\b",
					"\\bLootTrackerPlugin\\b", "\\bPluginManager\\b", "\\bisPluginEnabled\\b", "\\bLootRecordType\\b"})
				{
					if (java.util.regex.Pattern.compile(forbidden).matcher(code).find())
					{
						offenders.add(p.getFileName() + " uses " + forbidden);
					}
				}
			}
		}
		assertEquals(Collections.emptyList(), offenders);
	}

	@Test
	public void collectorSubscribesOnlyToCoreRuneLiteEvents()
	{
		for (Method m : StructuredActivityCollector.class.getDeclaredMethods())
		{
			if (m.isAnnotationPresent(Subscribe.class))
			{
				String pkg = m.getParameterTypes()[0].getPackage().getName();
				assertTrue(m.getName() + " subscribes to " + pkg, pkg.equals("net.runelite.api.events"));
			}
		}
	}

	@Test
	public void everyDeclaredRewardSource_isProducedWithNoLootTrackerPluginPresent() throws Exception
	{
		Path root = Files.createTempDirectory("reward-independence-test");
		TelemetryPaths.init(TestFilepaths.rooted(root));
		EventLedger ledger = new EventLedger(new Gson());
		ledger.start();
		List<String> emitted = Collections.synchronizedList(new ArrayList<>());
		ledger.addListener((TelemetryEventListener) (a, type, payload, at) ->
		{
			if (type == EventType.STRUCTURED_REWARD)
			{
				emitted.add(((com.osrstelemetry.plugin.events.EventPayloads.StructuredReward) payload).getSourceKey());
			}
		});
		net.runelite.api.Client client = (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(), new Class<?>[] {net.runelite.api.Client.class},
			(proxy, method, args) -> method.getReturnType() == long.class ? 1L : method.getReturnType() == int.class ? 0
				: method.getReturnType() == boolean.class ? false : null);
		StructuredActivityCollector collector = new StructuredActivityCollector(client, id -> "Item " + id, ledger,
			StructuredActivityRegistry.defaults());
		List<String> expected = new ArrayList<>();
		for (com.osrstelemetry.plugin.activity.StructuredActivityDefinition d : StructuredActivityRegistry.defaults().all())
		{
			for (StructuredRewardSource source : d.getRewardSources())
			{
				expected.add(source.getKey());
				if (source.getProducer() == StructuredRewardSource.Producer.REWARD_INTERFACE)
				{
					collector.onRewardInterface(source, Collections.singletonMap(995, 10L), id -> "Coins");
				}
				else
				{
					collector.onRegion(source.getClaimRegions().iterator().next());
					collector.armClaim(source, Collections.<Integer, Long>emptyMap());
					collector.onInventoryChanged(Collections.singletonMap(995, 10L), id -> "Coins");
				}
			}
		}
		ledger.shutdown();
		TestFilepaths.rooted(root).deleteRecursively();
		assertEquals(expected, emitted);
		assertEquals(4, emitted.size());
	}
}
