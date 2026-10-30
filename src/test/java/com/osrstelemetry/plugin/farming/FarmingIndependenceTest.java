package com.osrstelemetry.plugin.farming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Farming is Spyglass-owned: no other plugin (Time Tracking included) is imported
 * or read, and no reflection is used anywhere in the package. The foundations
 * stay pure (no client, no subscribers, no varbit reads); the one live adapter,
 * {@link FarmingCaptureAdapter}, is the only class that subscribes to events or
 * reads a varbit, and it reads them in exactly one place (after the observer's
 * read plan). Code only: comments may (and do) cite RuneLite sources by name.
 */
public class FarmingIndependenceTest
{
	private static final Path DIR = Paths.get("src/main/java/com/osrstelemetry/plugin/farming");
	private static final String ADAPTER = "FarmingCaptureAdapter.java";
	private static final String[] FORBIDDEN_EVERYWHERE = {
		"net.runelite.client.plugins.", "timetracking", "TimeTracking", "getRSProfileConfiguration",
		"EventBus", "java.lang.reflect", "setAccessible(",
	};
	private static final String[] FORBIDDEN_IN_FOUNDATIONS = {
		"@Subscribe", "net.runelite.client.eventbus", "net.runelite.api.Client", "ClientThread", "getVarbitValue",
	};

	private static String code(Path p) throws Exception
	{
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
			.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	@Test
	public void farmingCode_importsNoOtherPlugin_andOnlyTheAdapterTouchesTheClient() throws Exception
	{
		List<Path> sources;
		try (Stream<Path> files = Files.list(DIR))
		{
			sources = files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
		assertTrue(sources.size() > 10);
		boolean adapterSeen = false;
		for (Path p : sources)
		{
			String code = code(p);
			for (String forbidden : FORBIDDEN_EVERYWHERE)
			{
				assertFalse(p.getFileName() + " contains " + forbidden, code.contains(forbidden));
			}
			if (p.getFileName().toString().equals(ADAPTER))
			{
				adapterSeen = true;
				assertEquals("the adapter reads varbits in exactly one place", 1, code.split("getVarbitValue\\(", -1).length - 1);
				continue;
			}
			for (String forbidden : FORBIDDEN_IN_FOUNDATIONS)
			{
				assertFalse(p.getFileName() + " (a pure foundation) contains " + forbidden, code.contains(forbidden));
			}
		}
		assertTrue("the live adapter exists", adapterSeen);
	}
}
