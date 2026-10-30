package com.osrstelemetry.plugin.travel;

import com.google.gson.Gson;

/**
 * The transport catalogue, loaded once for every test in the test JVM. Loading it (catalogue files, house routes and the world
 * network journeys) builds about 25,000 entries, about 60 MB retained. Gradle runs every test class in one worker JVM and a
 * loaded class keeps its static fields for that JVM's life, so a catalogue per test class kept 60 MB per class alive until the
 * suite ended: with ten such classes the default 512 MB test worker ran out of heap (Windows build, 2026-10-29). Tests only read
 * it; a test that needs a changed entry copies it first.
 */
public final class TestTransports
{
	public static final TransportCatalog CATALOG = TransportCatalog.load(new Gson());

	private TestTransports()
	{
	}
}
