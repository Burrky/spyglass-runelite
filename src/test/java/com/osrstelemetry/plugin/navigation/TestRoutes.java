package com.osrstelemetry.plugin.navigation;

import com.google.gson.Gson;

/**
 * Tests: installs a Gson for the bundled route table. In the plugin the client's injected Gson is installed at startup
 * (TravelCapabilityWiring); a test that routes without loading the transport catalogue first calls {@link #install()}.
 */
public final class TestRoutes
{
	private static final Gson GSON = new Gson();

	static
	{
		RouteTable.useGson(GSON);
	}

	private TestRoutes()
	{
	}

	/** Idempotent: the class's static initializer does the work. */
	public static void install()
	{
		RouteTable.useGson(GSON);
	}
}
