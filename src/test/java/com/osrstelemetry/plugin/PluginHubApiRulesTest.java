package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The Plugin Hub packager rejects a plugin that creates its own Gson or GsonBuilder ("always @Inject the client's Gson;
 * customise it with .newBuilder()"). Production code must only use the injected instance (2026-10-30, 0.2.0 packaging).
 */
public class PluginHubApiRulesTest
{
	private static final Pattern FRESH = Pattern.compile("new\\s+(com\\.google\\.gson\\.)?Gson(Builder)?\\s*\\(");

	@Test
	public void productionCode_neverCreatesAFreshGson()
	{
		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				try
				{
					List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
					for (int i = 0; i < lines.size(); i++)
					{
						if (FRESH.matcher(lines.get(i)).find())
						{
							found.add(p + ":" + (i + 1));
						}
					}
				}
				catch (IOException e)
				{
					throw new IllegalStateException(e);
				}
			});
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
		assertEquals(new ArrayList<String>(), found);
	}
}
