package com.osrstelemetry.plugin.events;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Append-only, one JSON object per line, per account.
 *
 * Same two fixes as LocalStateStore, for the same reasons (see its
 * javadoc): the envelope (including the payload) is built and
 * serialized to a String synchronously in append(), before handing
 * off to the background executor — a payload object's fields are
 * final/immutable by construction (see EventPayloads), but building
 * the JSON string synchronously means there is never a window where a
 * caller could observe or mutate anything after the fact and get a
 * different result than what's on disk. start()/shutdown() are
 * explicit lifecycle methods rather than constructor/finalizer, so
 * enable -> disable -> enable within one client process gets a fresh
 * working executor every time instead of reusing a shut-down one.
 */
@Slf4j
@Singleton
public class EventLedger
{
	private final Gson gson;
	private ExecutorService executor;
	private volatile String sessionId = UUID.randomUUID().toString();

	@Inject
	public EventLedger(Gson gson)
	{
		this.gson = gson;
	}

	// See TelemetryEventListener's javadoc for the full contract.
	// CopyOnWriteArrayList: registration/
	// unregistration (plugin enable/disable) is rare, notification (every
	// append()) is frequent -- optimizing the common case for lock-free
	// reads, matching this project's existing preference for simple,
	// well-understood concurrency primitives over anything bespoke.
	private final List<TelemetryEventListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Registration must be paired with removeListener() on plugin
	 * disable/shutDown() -- this list is NOT cleared by start()/
	 * shutdown() (those only manage the write executor), so a caller
	 * that adds itself again on every re-enable without ever removing
	 * itself would silently accumulate duplicate listeners and receive
	 * every event more than once. This is the exact same class of bug
	 * the client-thread lifecycle audit found and fixed for EventBus
	 * registration -- see OsrsTelemetryPlugin's own startUp()/shutDown()
	 * pairing for the established, already-correct pattern to mirror.
	 */
	public void addListener(TelemetryEventListener listener)
	{
		listeners.add(listener);
	}

	public void removeListener(TelemetryEventListener listener)
	{
		listeners.remove(listener);
	}

	/**
	 * Synchronous, on the calling thread, by design -- see
	 * TelemetryEventListener's javadoc. Every listener is isolated from
	 * every other and from the durable write: an ordinary listener
	 * failure is logged and swallowed here, never allowed to prevent or
	 * corrupt the append this notification accompanies ("session routing
	 * failure must not corrupt/drop the durable telemetry event").
	 *
	 * FAILURE-ISOLATION CONTRACT: this is the ONLY boundary in
	 * this class that separates a telemetry COLLECTOR (the caller of
	 * append()/appendAndWait(), e.g. SkillsCollector, ContainerCollector)
	 * from a session-runtime LISTENER (e.g. SessionRuntimeCoordinator).
	 * A broken listener must not be able to unwind into an unrelated
	 * collector's own control flow -- that includes both RuntimeException
	 * AND AssertionError (a real, previously-observed failure mode in
	 * this project's RuneLite development, and NOT a RuntimeException),
	 * so both are caught and isolated below.
	 *
	 * This does NOT extend to genuine JVM-fatal conditions --
	 * VirtualMachineError (OutOfMemoryError, StackOverflowError, and
	 * their siblings) and ThreadDeath. An earlier pass caught
	 * `Throwable` unconditionally, which was too broad: silently
	 * swallowing a condition that means the JVM itself is dying (or a
	 * thread is being deliberately, cooperatively stopped via
	 * ThreadDeath) hides a failure this method has no ability to
	 * meaningfully isolate or recover from, and every other component
	 * sharing this same JVM is about to be affected by it regardless of
	 * what this catch block does. Those conditions are deliberately
	 * re-thrown -- allowed to propagate out of notifyListeners() and out
	 * of append()/appendAndWait() -- rather than logged and suppressed.
	 * This is the standard "catch broadly, but never swallow VM-fatal
	 * conditions" idiom: `catch (Throwable t)` combined with an explicit
	 * rethrow for the fatal subset, since Java's multi-catch syntax
	 * cannot express "Throwable except VirtualMachineError/ThreadDeath"
	 * directly.
	 *
	 * Rethrowing here does not put the already-durably-accepted write at
	 * risk: by the time notifyListeners() runs, the write has already
	 * been submitted to the single-writer executor (see append()'s/
	 * appendAndWait()'s own ordering comments) -- an Error propagating
	 * out of this method at that point can no longer cancel, prevent, or
	 * otherwise affect that already-submitted durable write; it only
	 * affects whether THIS method call returns normally to its caller.
	 */
	private void notifyListeners(long accountHash, EventType type, Object payload, Instant observedAt)
	{
		for (TelemetryEventListener listener : listeners)
		{
			try
			{
				listener.onEvent(accountHash, type, payload, observedAt);
			}
			catch (VirtualMachineError | ThreadDeath fatal)
			{
				// Genuine JVM-fatal conditions are never swallowed --
				// see the class-level rationale above. Deliberately
				// rethrown before the broader catch below can absorb it.
				throw fatal;
			}
			catch (Throwable t)
			{
				// Everything else -- ordinary RuntimeExceptions AND
				// AssertionError -- is isolated here. This runs AFTER the
				// durable write has already been accepted (see append()/
				// appendAndWait()), so nothing caught here can still
				// reach back and prevent/cancel/corrupt that acceptance.
				log.warn("Telemetry event listener threw while handling {}; durable write is unaffected", type, t);
			}
		}
	}

	public synchronized void start()
	{
		if (executor != null && !executor.isShutdown())
		{
			return;
		}
		executor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "osrs-telemetry-event-ledger");
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * A "session" is one run of the client with this plugin active,
	 * not one game-account login — see plugin startUp(). Also called
	 * on an account switch (see AccountContext) so events from
	 * different accounts in the same client run never share a
	 * sessionId.
	 */
	public void startNewSession()
	{
		sessionId = UUID.randomUUID().toString();
	}

	public void append(long accountHash, EventType type, Object payload)
	{
		ExecutorService executor = this.executor;
		if (executor == null || executor.isShutdown())
		{
			log.warn("append() called while EventLedger is not started; dropping event {}", type);
			return;
		}

		Instant observedAt = Instant.now();
		String json = buildEnvelopeJson(accountHash, type, payload, observedAt);

		// Durable acceptance FIRST, structurally -- see class-level
		// ordering note above. If this event isn't even accepted for a
		// durable write, listeners are never told about it either.
		try
		{
			executor.submit(() -> appendNow(accountHash, json));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("append() rejected while EventLedger is shutting down; dropping event {}", type);
			return;
		}

		notifyListeners(accountHash, type, payload, observedAt);
	}

	/**
	 * Synchronous/waitable counterpart to append(), for shutdown-critical
	 * callers that must know an event actually reached disk before they
	 * can safely return -- e.g. ContainerCollector.finalizeBankSnapshotBlocking().
	 * The fire-and-forget append() above relies on shutdown()'s graceful
	 * drain, which is not guaranteed to complete an append still
	 * queued-but-not-yet-run when shutdown() tears the executor down --
	 * this method removes that reliance entirely for the one call site
	 * that cannot tolerate it.
	 *
	 * Builds and serializes the IDENTICAL TelemetryEvent envelope
	 * append() does -- same fresh random eventId, the ledger's current
	 * sessionId, same schema/payload shape, same Gson serializer -- and
	 * submits it through the SAME single-writer executor, so ordering
	 * relative to every other append()/appendAndWait() call is fully
	 * preserved (this is not a second, parallel write path). The only
	 * difference is that the calling thread then blocks (bounded by
	 * timeoutMs) until that specific submitted write has actually run
	 * and reports its own success/failure, instead of returning the
	 * instant the task is merely queued.
	 *
	 * This is deliberately NOT a replacement for append() in ordinary
	 * gameplay event handling -- it exists only for the narrow set of
	 * callers (currently: shutdown-time bank finalization) that cannot
	 * proceed without a durable answer.
	 *
	 * @return true only if this specific event was confirmed written to
	 * disk before timeoutMs elapsed. false covers every other outcome
	 * -- not started, rejected because shutdown is already underway,
	 * a genuine write failure, a timeout, or this thread being
	 * interrupted while waiting -- and callers must treat all of them
	 * identically: the event did not durably happen, full stop.
	 */
	public boolean appendAndWait(long accountHash, EventType type, Object payload, long timeoutMs)
	{
		ExecutorService executor = this.executor;
		if (executor == null || executor.isShutdown())
		{
			log.warn("appendAndWait() called while EventLedger is not started; dropping event {}", type);
			return false;
		}

		Instant observedAt = Instant.now();
		String json = buildEnvelopeJson(accountHash, type, payload, observedAt);

		// EXACT ENFORCED ORDER: (1) submit the durable write --
		// durably ACCEPTED onto the single-writer executor; (2)
		// synchronous listener notification, on this calling thread,
		// isolated per-listener by notifyListeners()'s own Throwable
		// catch; (3) THEN, and only then, wait (bounded by timeoutMs) for
		// the durable future submitted in step (1) to actually complete.
		// The durable future's own success/failure was already decided
		// independently back in step (1) -- it does not depend on
		// listener success in any way -- but this method, being
		// synchronous, still executes all of step (2)'s listener work
		// before it ever reaches step (3)'s durability wait. A slow or
		// misbehaving listener therefore delays how soon THIS caller
		// observes the durability result, even though it can never change
		// what that result is.
		Future<Boolean> future;
		try
		{
			future = executor.submit(() -> appendNow(accountHash, json));
		}
		catch (RejectedExecutionException e)
		{
			log.warn("appendAndWait() rejected while EventLedger is shutting down; dropping event {}", type);
			return false;
		}

		notifyListeners(accountHash, type, payload, observedAt);

		try
		{
			return Boolean.TRUE.equals(future.get(timeoutMs, TimeUnit.MILLISECONDS));
		}
		catch (InterruptedException e)
		{
			log.warn("appendAndWait() interrupted while waiting to append {}; treating as not durably written", type);
			return false;
		}
		catch (ExecutionException | TimeoutException e)
		{
			log.warn("appendAndWait() failed or timed out appending {}", type, e);
			return false;
		}
	}

	/**
	 * Shared envelope construction for append()/appendAndWait() --
	 * factored out so the two entry points can never drift in
	 * sessionId/eventId/schema semantics from one another. Building
	 * and serializing here, synchronously on the calling thread before
	 * either method touches the executor, mirrors this class's
	 * existing documented discipline (see class javadoc).
	 */
	private String buildEnvelopeJson(long accountHash, EventType type, Object payload, Instant observedAt)
	{
		TelemetryEvent event = new TelemetryEvent(
			UUID.randomUUID().toString(),
			Long.toString(accountHash),
			type,
			observedAt.toString(),
			sessionId,
			payload
		);
		return gson.toJson(event);
	}

	/** @return true if this event line was successfully and durably
	 * appended to disk. */
	private boolean appendNow(long accountHash, String json)
	{
		try
		{
			TelemetryPaths.eventsFile(accountHash).write(
				(json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND
			);
			return true;
		}
		catch (IOException e)
		{
			log.warn("Failed appending telemetry event for account {}", accountHash, e);
			return false;
		}
	}

	/**
	 * Graceful drain — same reasoning as LocalStateStore.shutdown().
	 */
	public void shutdown()
	{
		ExecutorService executor = this.executor;
		if (executor == null)
		{
			return;
		}
		executor.shutdown();
		try
		{
			if (!executor.awaitTermination(2, TimeUnit.SECONDS))
			{
				log.warn("EventLedger writer did not drain within the graceful window; it will keep draining in the background rather than being forcibly interrupted");
			}
		}
		catch (InterruptedException e)
		{
			log.warn("Interrupted while awaiting graceful drain of the EventLedger writer; leaving it to keep draining in the background");
		}
	}
}
