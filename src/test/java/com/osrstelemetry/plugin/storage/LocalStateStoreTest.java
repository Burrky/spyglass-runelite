package com.osrstelemetry.plugin.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * NOTE: written, not run — no-network caveat as elsewhere.
 *
 * Covers: the plugin can be enabled, disabled, and
 * re-enabled within one client process without a RejectedExecutionException
 * or a dead writer, and shutdown() actually drains queued writes
 * rather than dropping them.
 */
public class LocalStateStoreTest
{
	/**
	 * FIX: plain Java, no Lombok — the test source set doesn't have
	 * Lombok on its compile classpath (real javac error: "package
	 * lombok does not exist"), and production dependency
	 * configuration is intentionally left alone rather than churned
	 * just to make a test fixture compile.
	 */
	private static class Doc
	{
		private String value;

		public Doc()
		{
		}

		public Doc(String value)
		{
			this.value = value;
		}

		public String getValue()
		{
			return value;
		}

		public void setValue(String value)
		{
			this.value = value;
		}
	}

	private File targetFile;

	@Before
	public void setUp()
	{
		targetFile = new File(System.getProperty("java.io.tmpdir"), "osrs-telemetry-test-" + System.nanoTime() + ".json");
	}

	@After
	public void tearDown()
	{
		targetFile.delete();
		new File(targetFile.getParentFile(), targetFile.getName() + ".tmp").delete();
	}

	@Test
	public void writeBeforeStartIsSafeNoOp() throws Exception
	{
		LocalStateStore store = new LocalStateStore();
		// Deliberately not calling start() — simulates a caller bug,
		// which must not throw.
		store.write(targetFile, new Doc("should not be written"));
		TimeUnit.MILLISECONDS.sleep(100);
		assertFalse("write before start() must not produce a file", targetFile.exists());
	}

	@Test
	public void writeAfterShutdownIsSafeNoOp() throws Exception
	{
		LocalStateStore store = new LocalStateStore();
		store.start();
		store.shutdown();

		store.write(targetFile, new Doc("should not be written either"));
		TimeUnit.MILLISECONDS.sleep(100);
		assertFalse("write after shutdown() must not produce a file", targetFile.exists());
	}

	@Test
	public void shutdownDrainsQueuedWrites() throws Exception
	{
		LocalStateStore store = new LocalStateStore();
		store.start();
		store.write(targetFile, new Doc("hello"));
		// No sleep — shutdown() itself is what must wait for this
		// queued write to complete before returning.
		store.shutdown();

		assertEquals("hello", readValue(targetFile));
	}

	@Test
	public void enableDisableEnableCycleProducesAWorkingStoreEachTime() throws Exception
	{
		LocalStateStore store = new LocalStateStore();

		store.start();
		store.write(targetFile, new Doc("first"));
		store.shutdown();
		assertEquals("first", readValue(targetFile));

		// Re-enable — this must not throw RejectedExecutionException,
		// and must produce a live writer again.
		store.start();
		store.write(targetFile, new Doc("second"));
		store.shutdown();
		assertEquals("second", readValue(targetFile));
	}

	@Test
	public void onWrittenCallbackDoesNotRunWhenTheWriteFails() throws Exception
	{
		// A write to a target whose
		// parent directory does not exist must fail (writeNow()
		// returns false), and the dependent callback must NOT run —
		// this is exactly the bug that could have let a BANK_SNAPSHOT
		// event reference a file that never reached disk.
		LocalStateStore store = new LocalStateStore();
		store.start();

		File badTarget = new File(
			new File(System.getProperty("java.io.tmpdir"), "osrs-telemetry-nonexistent-" + System.nanoTime()),
			"file.json"
		);

		final boolean[] callbackRan = {false};
		store.write(badTarget, new Doc("irrelevant"), () -> callbackRan[0] = true);
		TimeUnit.MILLISECONDS.sleep(200);

		assertFalse("write to a path with a missing parent directory must fail", badTarget.exists());
		assertFalse("onWritten must not run when the write failed", callbackRan[0]);

		store.shutdown();
	}

	@Test
	public void writeAndWaitReturnsFalseOnFailureAndTrueOnSuccess() throws Exception
	{
		LocalStateStore store = new LocalStateStore();
		store.start();

		File badTarget = new File(
			new File(System.getProperty("java.io.tmpdir"), "osrs-telemetry-nonexistent-" + System.nanoTime()),
			"file.json"
		);
		assertFalse(store.writeAndWait(badTarget, new Doc("irrelevant"), 1000));
		assertTrue(store.writeAndWait(targetFile, new Doc("ok"), 1000));
		assertEquals("ok", readValue(targetFile));

		store.shutdown();
	}

	private String readValue(File file) throws Exception
	{
		String json = new String(Files.readAllBytes(file.toPath()));
		// Cheap extraction, avoids pulling Gson into the test just to
		// read one field back.
		int start = json.indexOf(':') + 1;
		return json.substring(start).replaceAll("[\"{}\\s]", "");
	}
}
