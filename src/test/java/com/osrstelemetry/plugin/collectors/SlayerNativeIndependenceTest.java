package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.OsrsTelemetryPlugin;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.plugins.PluginDependency;
import org.junit.Test;

/**
 * HARD RULE: Spyglass must not require any other RuneLite plugin. Smart
 * Slayer's data comes only from native client state (Slayer varps/varbits,
 * the game cache's Slayer DB tables, game events) through runelite-api --
 * never from RuneLite's Slayer plugin, its SlayerPluginService, its state,
 * or reflection into it. These checks fail the build if that dependency
 * ever comes back.
 *
 * Legitimate RuneLite API stays allowed: net.runelite.api.* (Client,
 * gameval IDs, events) and core client infrastructure such as ClientThread
 * or the EventBus are not plugins.
 */
public class SlayerNativeIndependenceTest
{
	private static final Path MAIN = Paths.get("src/main/java");
	private static final String PKG = "com/osrstelemetry/plugin/";

	/** The Slayer data-source path: everything between native state and Smart Slayer's events. */
	private static final String[] SLAYER_PATH = {
		"collectors/SlayerCollector.java",
		"collectors/SlayerNativeTask.java",
		"collectors/SlayerNativeTaskResolver.java",
		"collectors/SlayerNativeTaskReader.java",
		"collectors/SlayerTaskLookup.java",
		"collectors/SlayerCommittedTask.java",
		"collectors/NpcDeathCollector.java",
		"model/SlayerState.java",
		"session/SlayerTaskFamilyRegistry.java",
	};

	private static final String[] REFLECTION = {
		"java.lang.reflect", "setAccessible(", "getDeclaredField(", "getDeclaredMethod(",
		"getDeclaredConstructor(", "Class.forName(", "MethodHandles",
	};

	/** Code only: prose may mention the Slayer plugin (e.g. to say why it is NOT used). */
	private static String code(Path file) throws IOException
	{
		String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	private static List<Path> allProductionSources() throws IOException
	{
		try (Stream<Path> files = Files.walk(MAIN))
		{
			return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
	}

	@Test
	public void noProductionSource_referencesTheRuneLiteSlayerPlugin() throws Exception
	{
		List<String> offenders = new ArrayList<>();
		List<Path> sources = allProductionSources();
		assertTrue("production sources found", sources.size() > 50);
		for (Path p : sources)
		{
			String src = code(p);
			if (src.contains("net.runelite.client.plugins.slayer") || src.contains("SlayerPlugin"))
			{
				offenders.add(p.toString());
			}
		}
		assertEquals("Slayer plugin referenced from production code", new ArrayList<String>(), offenders);
	}

	@Test
	public void noProductionSource_declaresAnyPluginDependency() throws Exception
	{
		for (Path p : allProductionSources())
		{
			assertFalse(p + " must not declare @PluginDependency", code(p).contains("PluginDependency"));
		}
		assertEquals("OsrsTelemetryPlugin carries no @PluginDependency at runtime", 0,
			OsrsTelemetryPlugin.class.getAnnotationsByType(PluginDependency.class).length);
	}

	@Test
	public void slayerPath_usesNoReflection_andImportsNoOtherPlugin() throws Exception
	{
		for (String file : SLAYER_PATH)
		{
			String src = code(MAIN.resolve(PKG + file));
			for (String forbidden : REFLECTION)
			{
				assertFalse(file + " must not use " + forbidden, src.contains(forbidden));
			}
			assertFalse(file + " must not import another plugin", src.contains("net.runelite.client.plugins."));
		}
	}

	@Test
	public void slayerCollectors_areConstructedWithoutAnyPluginType()
	{
		for (Class<?> collector : new Class<?>[]{SlayerCollector.class, NpcDeathCollector.class, SlayerNativeTaskReader.class})
		{
			for (Constructor<?> c : collector.getDeclaredConstructors())
			{
				for (Class<?> type : c.getParameterTypes())
				{
					assertFalse(c + " takes " + type.getName(), type.getName().startsWith("net.runelite.client.plugins."));
				}
			}
		}
	}

	@Test
	public void nativeReader_readsTheSlayerVarpsAndCacheTablesDirectly() throws Exception
	{
		String reader = code(MAIN.resolve(PKG + "collectors/SlayerNativeTaskReader.java"));
		for (String id : new String[]{
			"VarPlayerID.SLAYER_COUNT", "VarPlayerID.SLAYER_TARGET", "VarPlayerID.SLAYER_AREA",
			"VarPlayerID.SLAYER_COUNT_ORIGINAL", "VarbitID.SLAYER_TARGET_BOSSID", "VarbitID.SLAYER_MODIFIER_ID",
			"DBTableID.SlayerTask.", "DBTableID.SlayerTaskSublist.", "DBTableID.SlayerArea."})
		{
			assertTrue("native reader must read " + id, reader.contains(id));
		}
		String collector = code(MAIN.resolve(PKG + "collectors/SlayerCollector.java"));
		assertTrue(collector.contains("nativeTaskReader.read()"));
	}
}
