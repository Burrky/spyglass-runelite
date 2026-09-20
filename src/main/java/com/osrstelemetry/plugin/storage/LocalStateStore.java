package com.osrstelemetry.plugin.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Writes state documents to disk.
 *
 * CORRECTNESS NOTE: write() serializes the document to a String synchronously,
 * on the calling thread, before returning. Only the cheap "write this
 * already-built String to a file" step happens on the background
 * executor. This guarantees the persisted document reflects exactly
 * the logical state at the moment write() was called, even if the
 * caller's collector goes on to mutate the same object afterward (e.g.
 * SkillsState.skills is a live map a collector keeps updating). Gson
 * serialization of these small documents is on the order of tens of
 * microseconds — cheap enough that doing it on the client thread is
 * the correct tradeoff (correctness over avoiding client-thread work),
 * per the project's own stated priority.
 *
 * LIFECYCLE NOTE: the executor is created in start() and torn down in
 * shutdown(), not in
 * the constructor — Guice creates this as a singleton once per client
 * process, but the plugin itself can be toggled on and off many times
 * within that process, and each toggle needs a live executor. Calling
 * write() before start() or after shutdown() is a caller bug, not a
 * state this class tries to paper over.
 *
 * SHUTDOWN IS NON-INTERRUPTING (Plugin Hub maintainer review): shutdown()
 * never forcibly terminates the executor or re-asserts this thread's own
 * interrupt status -- both are forms of thread interruption this
 * project's Plugin Hub review disallows outright. A write that is still
 * running when the graceful drain window elapses is left to finish on
 * its own, in the background, on its own (already-daemon) thread rather
 * than being forcibly interrupted -- see shutdown()'s own javadoc.
 */
@Slf4j
@Singleton
public class LocalStateStore
{
	private final Gson gson;
	private ExecutorService writeExecutor;

	/**
	 * @param injectedGson RuneLite's shared, DI-managed Gson. Derived
	 * (never replaced) via {@link Gson#newBuilder()} to add pretty-
	 * printing for these on-disk documents, per the terminal-API rule
	 * against ever constructing a fresh {@code Gson}/{@code GsonBuilder}
	 * of this class's own.
	 */
	@Inject
	public LocalStateStore(Gson injectedGson)
	{
		this.gson = injectedGson.newBuilder().setPrettyPrinting().create();
	}

	public synchronized void start()
	{
		if (writeExecutor != null && !writeExecutor.isShutdown())
		{
			return;
		}
		writeExecutor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "osrs-telemetry-writer");
			t.setDaemon(true);
			return t;
		});
	}

	public void write(Filepath target, Object document)
	{
		write(target, document, () -> { });
	}

	/**
	 * onWritten only runs if the write actually succeeded — see
	 * writeNow()'s boolean return. This matters because a dependent
	 * write (e.g. a BANK_SNAPSHOT event) must never be triggered when
	 * its file never reached disk. A caller building a dependency chain
	 * (write A, then on success
	 * write B, then on success append an event) should nest calls to
	 * this method — each level only proceeds if the one before it
	 * actually succeeded.
	 */
	public void write(Filepath target, Object document, Runnable onWritten)
	{
		ExecutorService executor = this.writeExecutor;
		if (executor == null || executor.isShutdown())
		{
			log.warn("write() called while LocalStateStore is not started; dropping write to {}", target);
			return;
		}

		String json = gson.toJson(document);
		executor.submit(() ->
		{
			if (writeNow(target, json))
			{
				onWritten.run();
			}
		});
	}

	/**
	 * Blocking variant, used only where a caller genuinely needs to
	 * know a write finished (and whether it succeeded) before
	 * proceeding — currently just the shutdown-time bank-snapshot
	 * finalization (see ContainerCollector.flushPendingAndWait()).
	 * Ordinary collectors should use the async write() above; blocking
	 * on the calling thread is only acceptable here because it's used
	 * exclusively from the plugin's own shutDown(), never from an
	 * event handler on the client thread.
	 */
	public boolean writeAndWait(Filepath target, Object document, long timeoutMs)
	{
		ExecutorService executor = this.writeExecutor;
		if (executor == null || executor.isShutdown())
		{
			log.warn("writeAndWait() called while LocalStateStore is not started; dropping write to {}", target);
			return false;
		}

		String json = gson.toJson(document);
		java.util.concurrent.Future<Boolean> future = executor.submit(() -> writeNow(target, json));
		try
		{
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (Exception e)
		{
			log.warn("writeAndWait timed out or failed for {}", target, e);
			return false;
		}
	}

	/** @return true if the file was successfully and durably written. */
	private boolean writeNow(Filepath target, String json)
	{
		Filepath tmp = target.getParent().joinSegment(target.getFileName() + ".tmp");
		try
		{
			tmp.write(json.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.warn("Failed writing telemetry state to {}", target, e);
			return false;
		}

		try
		{
			// ATOMIC_MOVE where the filesystem supports it (all major
			// filesystems on Linux/macOS; NTFS on Windows for
			// same-volume moves, which this always is since tmp and
			// target share a parent directory). Falls back to a
			// plain (non-atomic) replace only where the OS/filesystem
			// genuinely cannot do better.
			tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		}
		catch (AtomicMoveNotSupportedException e)
		{
			try
			{
				tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
				return true;
			}
			catch (IOException e2)
			{
				log.warn("Non-atomic fallback move also failed for {}", target, e2);
				return false;
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to move {} into place at {}", tmp, target, e);
			return false;
		}
	}

	/**
	 * Graceful drain: stop accepting new work, wait briefly for
	 * queued writes to finish, and only log a warning if they don't --
	 * this never forcibly terminates the executor and never
	 * re-asserts this thread's own interrupt status if it is itself
	 * interrupted while waiting, per this project's Plugin Hub review
	 * requirement against any active thread interruption. A write that
	 * outlives the drain window keeps running to completion in the
	 * background on its own (already-daemon, so it can never keep the
	 * JVM alive) thread instead of being forcibly killed mid-write --
	 * a slower worst case than before, but one that can never leave a
	 * half-written file, and never interrupts I/O that may itself be
	 * mid-syscall.
	 */
	public void shutdown()
	{
		ExecutorService executor = this.writeExecutor;
		if (executor == null)
		{
			return;
		}
		executor.shutdown();
		try
		{
			if (!executor.awaitTermination(2, TimeUnit.SECONDS))
			{
				log.warn("LocalStateStore writer did not drain within the graceful window; it will keep draining in the background rather than being forcibly interrupted");
			}
		}
		catch (InterruptedException e)
		{
			log.warn("Interrupted while awaiting graceful drain of the LocalStateStore writer; leaving it to keep draining in the background");
		}
	}

	/**
	 * Best-effort synchronous read. Added for the bank-snapshot
	 * deduplication feature (seeding an in-memory baseline from
	 * previously-persisted state) — not used by the write path at all.
	 *
	 * Returns null on ANY failure — missing file, malformed JSON, IO
	 * error — by design: this is a fail-open primitive. It does not
	 * depend on the write executor's started/shut-down state, since
	 * reads have no threading concerns of their own; it's the caller's
	 * responsibility to decide whether it's appropriate to call this
	 * from the client thread (see ContainerCollector's bank-baseline
	 * loading for why that's an accepted, bounded, one-time cost
	 * rather than something this method tries to offload itself).
	 */
	public <T> T readIfExists(Filepath file, Class<T> type)
	{
		if (!file.exists())
		{
			return null;
		}
		try (InputStream in = file.openInputStream())
		{
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return gson.fromJson(json, type);
		}
		catch (Exception e)
		{
			log.warn("Failed reading {}; treating as absent (fail-open)", file, e);
			return null;
		}
	}
}
