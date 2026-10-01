package com.osrstelemetry.plugin.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Integrity audit: chained writes are one ordered task serialized at call time; restarts keep order. */
public class LocalStateStoreOrderingTest
{
	private Path dir;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("store-ordering-test");
	}

	@After
	public void tearDown() throws Exception
	{
		TestFilepaths.rooted(dir).deleteRecursively();
	}

	private static String value(File f) throws Exception
	{
		String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		return new JsonParser().parse(json).getAsJsonObject().get("v").getAsString();
	}

	@Test
	public void writeChained_writesBoth_serializedAtCallTime_thenRunsCallbackOnce() throws Exception
	{
		File first = dir.resolve("first.json").toFile();
		File second = dir.resolve("second.json").toFile();
		LocalStateStore store = new LocalStateStore(new Gson());
		store.start();
		Map<String, String> a = new LinkedHashMap<>();
		a.put("v", "finalized");
		Map<String, String> b = new LinkedHashMap<>();
		b.put("v", "current-at-call");
		AtomicInteger callbacks = new AtomicInteger();

		store.writeChained(TestFilepaths.fromFile(first), a, TestFilepaths.fromFile(second), b, callbacks::incrementAndGet);
		// The caller keeps mutating its live object: the writer must not see it.
		b.put("v", "mutated-after-call");
		// A later write to the same target can never be overtaken by the chained one.
		Map<String, String> later = new LinkedHashMap<>();
		later.put("v", "later");
		store.write(TestFilepaths.fromFile(second), later);
		store.shutdown();

		assertEquals("finalized", value(first));
		assertEquals("later", value(second));
		assertEquals(1, callbacks.get());
	}

	@Test
	public void restartRightAfterShutdown_keepsWritesInOrder() throws Exception
	{
		File target = dir.resolve("prefs.json").toFile();
		LocalStateStore store = new LocalStateStore(new Gson());
		for (int cycle = 0; cycle < 5; cycle++)
		{
			store.start();
			for (int i = 0; i < 20; i++)
			{
				Map<String, String> doc = new LinkedHashMap<>();
				doc.put("v", cycle + ":" + i);
				store.write(TestFilepaths.fromFile(target), doc);
			}
			store.shutdown();
		}
		store.start();
		store.shutdown();
		assertEquals("4:19", value(target));
		assertTrue(!new File(target.getPath() + ".tmp").exists());
	}
}
