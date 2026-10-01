package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.collectors.StructuredActivityCollector;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * HARD RULE: Spyglass's Tempoross data comes only from native client state
 * (Jagex interface 437, varbits, game messages, location) -- never from
 * another RuneLite plugin, its overlay, its state, or reflection. These
 * checks fail the build if the Tempoross path ever grows such a dependency.
 */
public class TemporossNativeIndependenceTest
{
	private static final String MAIN = "src/main/java/com/osrstelemetry/plugin/";
	private static final String[] TEMPOROSS_PATH = {
		"activity/TemporossGameState.java",
		"activity/TemporossGameTracker.java",
		"collectors/StructuredActivityCollector.java",
		"ui/CurrentSessionView.java",
	};
	private static final String[] FORBIDDEN = {
		"net.runelite.client.plugins.", "PluginManager", "OverlayManager", "InfoBoxManager",
		"setAccessible(", "getDeclaredField(", "getDeclaredMethod(", "Class.forName(",
	};

	private static String source(String relative) throws Exception
	{
		Path p = Paths.get(MAIN + relative);
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	@Test
	public void temporossPath_importsNoOtherPlugin_andUsesNoReflection() throws Exception
	{
		for (String file : TEMPOROSS_PATH)
		{
			// Code only: prose may mention other plugins (e.g. to say why they are NOT used).
			String src = source(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
			for (String forbidden : FORBIDDEN)
			{
				assertFalse(file + " must not use " + forbidden, src.contains(forbidden));
			}
		}
	}

	@Test
	public void collector_readsTheNativeTitleTextAndVarbits()
	{
		String src;
		try
		{
			src = source("collectors/StructuredActivityCollector.java");
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		assertTrue(src.contains("InterfaceID.TemporossHud.ENERGY_TITLE"));
		assertTrue(src.contains("InterfaceID.TemporossHud.ESSENCE_TITLE"));
		assertTrue(src.contains("InterfaceID.TemporossHud.STORM_INTENSITY_TITLE"));
		assertTrue(src.contains("VarbitID.TEMPOROSS_CURRENT_POINTS"));
		assertTrue(src.contains("VarbitID.TEMPOROSS_REWARDPERMITS"));
	}

	@Test
	public void collector_isConstructedFromClientAndLedgerOnly_noPluginService()
	{
		for (Constructor<?> c : StructuredActivityCollector.class.getDeclaredConstructors())
		{
			for (Class<?> type : c.getParameterTypes())
			{
				assertFalse(c + " takes " + type.getName(), type.getName().startsWith("net.runelite.client.plugins."));
			}
		}
	}

	@Test
	public void tracker_isPure_andParsesOnlyTheKillCountMessage()
	{
		assertEquals(Integer.valueOf(1_234), TemporossGameTracker.killCountOf("Your Tempoross kill count is: 1,234."));
		assertNull(TemporossGameTracker.killCountOf("Your Zulrah kill count is: 5."));
		assertNull(TemporossGameTracker.killCountOf(null));
		TemporossGameTracker t = new TemporossGameTracker();
		assertNull("not a kill count", t.onGameMessage("You have helped to subdue the spirit!", 3_000));
		TemporossGameTracker.Result r = t.onGameMessage("Your Tempoross kill count is: 3.", 3_000);
		assertFalse("nothing was observed before: not trusted", r.isObservedFromStart());
		assertEquals(Integer.valueOf(3_000), r.getPoints());
	}
}
