package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.collectors.ActivityKillCountCollector;
import com.osrstelemetry.plugin.collectors.CollectionLogCollector;
import com.osrstelemetry.plugin.collectors.CombatAchievementCollector;
import com.osrstelemetry.plugin.collectors.ContainerCollector;
import com.osrstelemetry.plugin.collectors.LoadoutArchiveCollector;
import com.osrstelemetry.plugin.collectors.LootCollector;
import com.osrstelemetry.plugin.collectors.NpcDeathCollector;
import com.osrstelemetry.plugin.collectors.PotionStorageCollector;
import com.osrstelemetry.plugin.collectors.QuestCollector;
import com.osrstelemetry.plugin.collectors.ServerNpcLootCollector;
import com.osrstelemetry.plugin.collectors.SkillsCollector;
import com.osrstelemetry.plugin.collectors.SlayerCollector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

/**
 * CORE TELEMETRY IS NOT USER-CONFIGURABLE.
 *
 * The 13 former collect* settings are deleted (not hidden), and no
 * collector has ANY path to OsrsTelemetryConfig -- so an old stored
 * "false" for one of those keys in a player's RuneLite profile has
 * nothing left that could read it, and every collector runs whenever the
 * plugin is enabled.
 */
public class CoreTelemetryCollectionNotConfigurableTest
{
	static final List<String> REMOVED_KEYS = Arrays.asList(
		"collectSkills", "collectInventoryEquipment", "collectBank", "collectSeedVault",
		"collectPotionStorage", "collectGroupStorage", "collectQuests", "collectSlayer",
		"collectCollectionLog", "collectCombatAchievements", "collectBossKc", "collectLoot",
		"collectNpcDeaths");

	/** Every collector that used to be gated by one of those keys. */
	private static final List<Class<?>> FORMERLY_GATED = Arrays.asList(
		SkillsCollector.class, ContainerCollector.class, LoadoutArchiveCollector.class,
		PotionStorageCollector.class, QuestCollector.class, SlayerCollector.class,
		CollectionLogCollector.class, CombatAchievementCollector.class, ActivityKillCountCollector.class,
		LootCollector.class, ServerNpcLootCollector.class, NpcDeathCollector.class);

	// 1 -----------------------------------------------------------------
	@Test
	public void noCollectSettingIsExposed_notEvenAsAHiddenItem()
	{
		for (Method m : OsrsTelemetryConfig.class.getMethods())
		{
			assertFalse("no collect* accessor survives: " + m.getName(), m.getName().startsWith("collect"));
			ConfigItem ci = m.getAnnotation(ConfigItem.class);
			if (ci != null)
			{
				assertFalse("removed key re-exposed: " + ci.keyName(), REMOVED_KEYS.contains(ci.keyName()));
				assertFalse("no hidden config items: " + ci.keyName(), ci.hidden());
			}
		}
	}

	// 2 + 3 -------------------------------------------------------------
	@Test
	public void noCollectorCanReadConfig_soAnOldStoredFalseCannotDisableIt()
	{
		for (Class<?> c : FORMERLY_GATED)
		{
			for (Field f : c.getDeclaredFields())
			{
				assertFalse(c.getSimpleName() + " still holds config field " + f.getName(),
					OsrsTelemetryConfig.class.isAssignableFrom(f.getType()));
			}
			for (Constructor<?> k : c.getDeclaredConstructors())
			{
				for (Class<?> p : k.getParameterTypes())
				{
					assertFalse(c.getSimpleName() + " constructor still takes OsrsTelemetryConfig",
						OsrsTelemetryConfig.class.isAssignableFrom(p));
				}
			}
		}
	}

	@Test
	public void pluginHasNoConfigChangeHandler_thatCouldPauseOrReseedCollection()
	{
		for (Method m : OsrsTelemetryPlugin.class.getDeclaredMethods())
		{
			for (Class<?> p : m.getParameterTypes())
			{
				assertFalse("no ConfigChanged subscriber: " + m.getName(),
					p.getName().equals("net.runelite.client.events.ConfigChanged"));
			}
		}
	}

	// 2 -----------------------------------------------------------------
	/** startUp() registers every formerly-gated collector on the EventBus with no condition around it. */
	@Test
	public void startUp_registersEveryCollectorUnconditionally() throws Exception
	{
		String src = new String(Files.readAllBytes(Paths.get(
			"src/main/java/com/osrstelemetry/plugin/OsrsTelemetryPlugin.java")), StandardCharsets.UTF_8).replace("\r\n", "\n");
		int start = src.indexOf("protected void startUp()");
		int end = src.indexOf("protected void shutDown()");
		assertTrue(start > 0 && end > start);
		String startUp = src.substring(start, end);
		String shutDown = src.substring(end);
		for (Class<?> c : FORMERLY_GATED)
		{
			String field = Character.toLowerCase(c.getSimpleName().charAt(0)) + c.getSimpleName().substring(1);
			if (c == QuestCollector.class)
			{
				// Not an EventBus subscriber: the plugin drives it directly --
				// seeded at login, then polled on its GameTick cadence.
				assertTrue(src.contains("questCollector.captureInitialStateWithoutBaseline();"));
				assertTrue(src.contains("questCollector.checkAndFlush();"));
				continue;
			}
			String line = "\n\t\teventBus.register(" + field + ");";
			assertTrue("startUp() registers " + field + " at method level (not inside a branch)", startUp.contains(line));
			assertTrue("shutDown() unregisters " + field, shutDown.contains("eventBus.unregister(" + field + ");"));
		}
		assertFalse("startUp() consults no collect* setting", startUp.contains("config.collect"));
		assertEquals("no collect* read left anywhere in the plugin", -1, src.indexOf("config.collect"));
	}
}
