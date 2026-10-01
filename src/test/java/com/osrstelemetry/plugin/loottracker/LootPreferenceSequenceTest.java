package com.osrstelemetry.plugin.loottracker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Integrity audit continuation: the persisted collapse state is always the newest action, bulk or single. */
public class LootPreferenceSequenceTest
{
	private static final long ACCOUNT = 700_900_904L;
	private Path testRoot;

	@Before
	public void setUp() throws Exception
	{
		testRoot = Files.createTempDirectory("loot-pref-sequence-test");
		TelemetryPaths.init(TestFilepaths.rooted(testRoot));
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(testRoot).deleteRecursively();
	}

	@Test
	public void openAll_collapseAll_openOne_persistsExactlyThat()
	{
		List<String> keys = Arrays.asList("k1", "k2", "k3");
		LocalStateStore store = new LocalStateStore(new Gson());
		store.start();
		LootTrackerPreferences prefs = new LootTrackerPreferences(store);
		prefs.loadForAccount(ACCOUNT);
		prefs.setCollapsed(keys, false);
		prefs.setCollapsed(keys, true);
		prefs.setCollapsed(Collections.singletonList("k2"), false);
		store.shutdown();

		LocalStateStore again = new LocalStateStore(new Gson());
		again.start();
		LootTrackerPreferences reloaded = new LootTrackerPreferences(again);
		reloaded.loadForAccount(ACCOUNT);
		assertTrue(reloaded.isCollapsed("k1"));
		assertFalse(reloaded.isCollapsed("k2"));
		assertTrue(reloaded.isCollapsed("k3"));
		again.shutdown();
	}
}
