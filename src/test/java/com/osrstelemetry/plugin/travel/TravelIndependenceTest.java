package com.osrstelemetry.plugin.travel;

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
 * The generic core stays generic and self-contained (canonical Phase F
 * boundaries): account, travel and prep never import a domain (Farming), another
 * plugin (Quest Helper, Shortest Path, Time Tracking), a PluginMessage bridge,
 * external plugin configuration, reflection or the network. Only the account
 * adapter touches the client. Code only: comments may cite sources.
 */
public class TravelIndependenceTest
{
	private static final String[] FORBIDDEN = {
		"com.osrstelemetry.plugin.farming", "net.runelite.client.plugins.", "PluginMessage", "questhelper", "shortestpath", "timetracking",
		"getRSProfileConfiguration", "ConfigManager", "java.lang.reflect", "setAccessible(", "java.net.", "okhttp", "HttpClient",
		"GuidanceEngine", "WorldMapPoint", "OverlayManager",
	};
	private static final String[] CLIENT_ONLY_IN_ADAPTER = {"net.runelite.api.Client", "@Subscribe", "getVarbitValue", "getItemContainer"};

	private static String code(Path p) throws Exception
	{
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	@Test
	public void accountAndTravel_areGenericAndIndependent() throws Exception
	{
		int seen = 0;
		for (String pkg : new String[]{"account", "travel", "prep"})
		{
			List<Path> files;
			try (Stream<Path> s = Files.list(Paths.get("src/main/java/com/osrstelemetry/plugin/" + pkg)))
			{
				files = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
			}
			for (Path p : files)
			{
				seen++;
				String code = code(p);
				for (String f : FORBIDDEN)
				{
					assertFalse(p.getFileName() + " contains " + f, code.contains(f));
				}
				if (!p.getFileName().toString().equals("AccountCapabilityAdapter.java"))
				{
					for (String f : CLIENT_ONLY_IN_ADAPTER)
					{
						assertFalse(p.getFileName() + " (pure) contains " + f, code.contains(f));
					}
				}
			}
		}
		assertTrue(seen >= 25);
	}
}
