package com.osrstelemetry.plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Local development launcher ONLY — never packaged, submitted to the
 * Plugin Hub, or shipped. Not a JUnit test despite living under
 * src/test and being named "*Test" (that placement/naming matches
 * RuneLite's own example-plugin template convention for this exact
 * purpose — its wiki instructions for renaming a checked-out template
 * explicitly call out renaming "ExamplePluginTest" alongside the
 * plugin and config classes). It has no @Test-annotated methods, so
 * `./gradlew test` does not invoke it; only `./gradlew run` does (see
 * the new `run` task in build.gradle).
 *
 * Loads this plugin as if it were a core/builtin plugin, then boots
 * the real RuneLite client from source, so a runtime
 * verification plan can be run against a live game session. This was
 * simply missing before — this project's build.gradle was carried
 * forward as "matches the example-plugin template"
 * for its dependency/compiler settings, but the launcher piece was
 * never actually added, which is the direct cause of `gradlew run`
 * not existing.
 *
 * ExternalPluginManager.loadBuiltin(Class) is confirmed directly
 * against RuneLite's current official example-plugin template, which
 * uses this exact same two-line pattern
 * (ExternalPluginManager.loadBuiltin(ExamplePlugin.class); then
 * RuneLite.main(args);) in its own PluginTest launcher class — no
 * longer a community-convention inference, an actual template match.
 */
public class OsrsTelemetryPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsTelemetryPlugin.class);
		RuneLite.main(args);
	}
}
