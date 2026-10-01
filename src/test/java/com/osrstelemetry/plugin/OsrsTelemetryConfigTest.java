package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.junit.Test;

/**
 * Settings organization audit, read from RuneLite's own @ConfigSection /
 * @ConfigItem annotations (exactly what RuneLite's config panel renders
 * from): sections, section membership, deterministic positions, and that
 * every remaining keyName and default survived. Core collection toggles
 * are gone -- see CoreTelemetryCollectionNotConfigurableTest.
 */
public class OsrsTelemetryConfigTest
{
	private static final OsrsTelemetryConfig DEFAULTS = new OsrsTelemetryConfig()
	{
	};

	private static Map<String, String> sectionsByName() throws Exception
	{
		Map<String, String> sections = new LinkedHashMap<>();
		for (Field f : OsrsTelemetryConfig.class.getFields())
		{
			ConfigSection cs = f.getAnnotation(ConfigSection.class);
			if (cs != null)
			{
				sections.put(cs.name(), (String) f.get(null));
			}
		}
		return sections;
	}

	private static Map<String, Method> itemsByKey()
	{
		Map<String, Method> items = new HashMap<>();
		for (Method m : OsrsTelemetryConfig.class.getMethods())
		{
			ConfigItem ci = m.getAnnotation(ConfigItem.class);
			if (ci != null)
			{
				items.put(ci.keyName(), m);
			}
		}
		return items;
	}

	@Test
	public void sections_sharedThenCurrentSession_noEmptyLootTrackerOrHistorySections() throws Exception
	{
		Map<String, String> sections = sectionsByName();
		assertEquals(Arrays.asList("General", "Current Session"), orderedSectionNames());
		assertTrue(!sections.containsKey("Loot Tracker"));
		assertTrue("no setting is exclusively History's, so no History section", !sections.containsKey("History"));
	}

	private static List<String> orderedSectionNames() throws Exception
	{
		Map<Integer, String> byPosition = new java.util.TreeMap<>();
		for (Field f : OsrsTelemetryConfig.class.getFields())
		{
			ConfigSection cs = f.getAnnotation(ConfigSection.class);
			if (cs != null)
			{
				assertTrue("unique section positions", byPosition.put(cs.position(), cs.name()) == null);
			}
		}
		return new java.util.ArrayList<>(byPosition.values());
	}

	@Test
	public void everyExistingKeyNameAndDefault_preserved_andPlacedInTheAuditedSection() throws Exception
	{
		Map<String, String> sections = sectionsByName();
		String shared = sections.get("General");
		String currentSession = sections.get("Current Session");
		Map<String, Method> items = itemsByKey();

		// Shared: only genuinely cross-tab settings. lootValuationMode is read
		// by CurrentSessionView, LootTrackerView and HistoryDetailView.
		Map<String, Object> expectedShared = new LinkedHashMap<>();
		expectedShared.put("lootValuationMode", LootValuationMode.GRAND_EXCHANGE);

		for (Map.Entry<String, Object> e : expectedShared.entrySet())
		{
			Method m = items.get(e.getKey());
			assertNotNull("keyName unchanged: " + e.getKey(), m);
			assertEquals(shared, m.getAnnotation(ConfigItem.class).section());
			assertEquals("default unchanged: " + e.getKey(), e.getValue(), m.invoke(DEFAULTS));
		}

		Method order = items.get("currentSessionSectionOrder");
		assertEquals(currentSession, order.getAnnotation(ConfigItem.class).section());
		assertEquals(CurrentSessionSectionOrder.XP_FIRST, order.invoke(DEFAULTS));

		Method scope = items.get("slayerXpScope");
		assertNotNull(scope);
		assertEquals("Slayer XP scope", scope.getAnnotation(ConfigItem.class).name());
		assertEquals(currentSession, scope.getAnnotation(ConfigItem.class).section());
		assertEquals(SlayerXpScope.ALL_TASKS, scope.invoke(DEFAULTS));

		assertEquals("no settings added or lost", expectedShared.size() + 2, items.size());
		for (Method m : items.values())
		{
			ConfigItem ci = m.getAnnotation(ConfigItem.class);
			if (shared.equals(ci.section()))
			{
				assertTrue("Shared holds only cross-feature settings: " + ci.keyName(), expectedShared.containsKey(ci.keyName()));
			}
		}
	}

	@Test
	public void finalVisibleMenu_exactlyThreeSettings_inAuditedOrder() throws Exception
	{
		Map<String, String> sections = sectionsByName();
		Map<String, java.util.TreeMap<Integer, String>> bySection = new LinkedHashMap<>();
		for (Method m : itemsByKey().values())
		{
			ConfigItem ci = m.getAnnotation(ConfigItem.class);
			assertTrue("no hidden items: " + ci.keyName(), !ci.hidden());
			bySection.computeIfAbsent(ci.section(), k -> new java.util.TreeMap<>()).put(ci.position(), ci.name());
		}
		assertEquals(Arrays.asList("Loot valuation"), new java.util.ArrayList<>(bySection.get(sections.get("General")).values()));
		assertEquals(Arrays.asList("XP / Loot order", "Slayer XP scope"),
			new java.util.ArrayList<>(bySection.get(sections.get("Current Session")).values()));
		assertEquals("every section is non-empty and no other section exists", 2, bySection.size());
	}

	@Test
	public void positions_uniqueAndDeterministicWithinEachSection()
	{
		Map<String, Set<Integer>> seen = new HashMap<>();
		for (Method m : itemsByKey().values())
		{
			ConfigItem ci = m.getAnnotation(ConfigItem.class);
			assertTrue("every setting belongs to a section: " + ci.keyName(), !ci.section().isEmpty());
			assertTrue("duplicate position in section " + ci.section() + ": " + ci.keyName(),
				seen.computeIfAbsent(ci.section(), s -> new HashSet<>()).add(ci.position()));
		}
	}

	@Test
	public void slayerXpScope_labels()
	{
		assertEquals("All tasks", SlayerXpScope.ALL_TASKS.toString());
		assertEquals("Current task", SlayerXpScope.CURRENT_TASK.toString());
		assertEquals(2, SlayerXpScope.values().length);
	}
}
