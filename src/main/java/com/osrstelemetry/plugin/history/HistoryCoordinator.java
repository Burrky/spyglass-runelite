package com.osrstelemetry.plugin.history;

import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionPersistence;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns {@link HistoryIndex}'s lifecycle and keeps every bit of its file IO
 * off the Swing EDT (spec Part T/U) -- a dedicated single-thread
 * daemon executor, the same restartability contract (start()/
 * shutdown(), safe across repeated plugin enable/disable) already
 * established by LootTrackerCoordinator/LocalStateStore/EventLedger
 * in this codebase.
 *
 * READ MODEL: getRecentEntries() (called from the EDT) NEVER blocks: it
 * returns whatever HistoryIndex currently has cached in memory. Newly
 * finalized sessions are picked up by a background refresh() requested
 * when a finalized session file has actually been written (see
 * SessionPersistence's finalized-write listener, wired by the plugin),
 * on account load, and when the player opens History -- never by
 * polling, and never with file IO on the EDT. A changed entry list is
 * announced through the optional change listener.
 *
 * DELIBERATELY INDEPENDENT of SessionRuntimeCoordinator: this class
 * owns its own SessionPersistence instance (constructed with the
 * single-arg, no-archive constructor -- see that constructor's own
 * javadoc for why that is fully supported, not merely a test shim).
 * HistoryCoordinator only ever READS already-finalized session files
 * (loadFinalized()); it never persists a current/finalized session
 * itself, so it has no need of a LoadoutArchive -- loadout resolution
 * already happened once, at original finalization time, inside
 * SessionRuntimeCoordinator's own SessionPersistence, and is already
 * baked into the JSON file this class reads.
 *
 * ACCOUNT ISOLATION (spec Part S): ensureAccountLoaded() on a real
 * switch installs a BRAND NEW, empty HistoryIndex synchronously
 * (cheap -- no IO) before any background refresh for the new account
 * can run, so there is no window where the old account's cached
 * entries could be read under the new account's identity. A scan that
 * was already running for the old account reads its (account, index)
 * pair together at its start and publishes only if that index is still
 * current, so its result is dropped rather than shown or indexed under
 * the new account; the new account's own scan follows.
 *
 * DETAIL LOADING (spec Part R -- lazy): loadDetail() is a direct,
 * uncached SessionPersistence.loadFinalized() read, run on the same
 * background executor -- full aggregates/loadout are only ever
 * parsed here, when a specific entry is actually opened, never as
 * part of the lightweight list refresh (see HistoryEntry's own
 * javadoc).
 */
@Slf4j
@Singleton
public class HistoryCoordinator
{
	/** Spec's own "~10 day display window" -- filters HistoryIndex's snapshot at read time; never deletes anything on disk (spec Part A: display-only retention). */
	private static final Duration DISPLAY_WINDOW = Duration.ofDays(10);

	private final SessionPersistence persistence;

	private ExecutorService executor;
	private volatile HistoryIndex index = new HistoryIndex();
	private volatile long currentAccountHash;
	private volatile boolean accountLoaded = false;
	private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
	private final AtomicBoolean refreshRequestedAgain = new AtomicBoolean(false);
	/** Guards the (account, index, published list) triple; never held during disk I/O. */
	private final Object publishLock = new Object();
	private volatile List<HistoryEntry> cachedSnapshot = Collections.emptyList();
	/** True once the CURRENT account's sessions have been scanned at least once (reset on account switch). */
	private volatile boolean initialScanComplete;

	@Inject
	public HistoryCoordinator(LocalStateStore store)
	{
		this.persistence = new SessionPersistence(store);
	}

	public void start()
	{
		if (executor != null && !executor.isShutdown())
		{
			return;
		}
		executor = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "osrs-telemetry-history-index");
			t.setDaemon(true);
			return t;
		});
	}

	public void shutdown()
	{
		ExecutorService toShutdown = this.executor;
		if (toShutdown == null)
		{
			return;
		}
		toShutdown.shutdown();
		try
		{
			if (!toShutdown.awaitTermination(2, TimeUnit.SECONDS))
			{
				log.warn("HistoryCoordinator index executor did not drain within the graceful window; it will keep draining in the background rather than being forcibly interrupted");
			}
		}
		catch (InterruptedException e)
		{
			log.warn("Interrupted while awaiting graceful drain of the HistoryCoordinator index executor; leaving it to keep draining in the background");
		}
	}

	/**
	 * Called from OsrsTelemetryPlugin.handleLogin() on every LOGGED_IN
	 * transition, same as every other per-account coordinator here --
	 * a same-account call (fresh start already loaded, or a world
	 * hop/relog) is a no-op.
	 */
	public void ensureAccountLoaded(long accountHash)
	{
		if (accountLoaded && accountHash == currentAccountHash)
		{
			return;
		}
		synchronized (publishLock)
		{
			// Swapped together with the published list under the same lock a
			// refresh publishes under, so a refresh that scanned the OLD
			// account can never publish into the new one (see runRefreshes).
			currentAccountHash = accountHash;
			accountLoaded = true;
			index = new HistoryIndex();
			cachedSnapshot = Collections.emptyList();
			initialScanComplete = false;
		}
		notifyChanged();
		triggerBackgroundRefresh();
	}

	/**
	 * Called from the EDT. A pure, never-blocking read of the cached
	 * entries. Reads no longer kick a refresh (that re-scanned the sessions
	 * directory once a second); refreshes happen on account load, when a
	 * finalized session file is written ({@link #requestRefresh()}, wired
	 * from the session runtime) and when the player opens History.
	 */
	public List<HistoryEntry> getRecentEntries()
	{
		return cachedSnapshot;
	}

	/**
	 * Whether the current account's History has been scanned at least once,
	 * so an empty {@link #getRecentEntries()} really means "no sessions"
	 * rather than "not loaded yet" (logged out, or the first scan still
	 * running). Used only to decide the first-use welcome state.
	 */
	public boolean isInitialScanComplete()
	{
		return accountLoaded && initialScanComplete;
	}

	/** Re-scan finalized sessions in the background; coalesces with one already running. */
	public void requestRefresh()
	{
		triggerBackgroundRefresh();
	}

	private volatile Runnable changeListener;

	/** Best-effort notification (history executor thread) that the recent-entries list changed. */
	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener;
	}

	private void notifyChanged()
	{
		Runnable listener = changeListener;
		if (listener == null)
		{
			return;
		}
		try
		{
			listener.run();
		}
		catch (RuntimeException e)
		{
			log.debug("History change listener failed; the index is unaffected", e);
		}
	}

	private static boolean sameEntries(List<HistoryEntry> a, List<HistoryEntry> b)
	{
		if (a.size() != b.size())
		{
			return false;
		}
		for (int i = 0; i < a.size(); i++)
		{
			if (!a.get(i).getSessionId().equals(b.get(i).getSessionId()))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Requests one scan of the CURRENT account (read when the scan starts,
	 * never captured at request time). At most one worker runs; requests
	 * made while it runs are served by it re-scanning once more, so none is
	 * ever lost -- the request flag is raised BEFORE claiming the worker,
	 * and the worker re-checks it after releasing (see runRefreshes).
	 */
	private void triggerBackgroundRefresh()
	{
		if (!accountLoaded)
		{
			// Nothing to scan yet (and never the "account 0" directory).
			return;
		}
		refreshRequestedAgain.set(true);
		startWorkerIfIdle();
	}

	private void startWorkerIfIdle()
	{
		ExecutorService currentExecutor = this.executor;
		if (currentExecutor == null || currentExecutor.isShutdown())
		{
			return;
		}
		if (!refreshInFlight.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			currentExecutor.submit(this::runRefreshes);
		}
		catch (RejectedExecutionException e)
		{
			// Shut down between the check and the submit: release the worker
			// slot so a restarted coordinator is never stuck "in flight".
			refreshInFlight.set(false);
		}
	}

	private void runRefreshes()
	{
		try
		{
			while (refreshRequestedAgain.getAndSet(false))
			{
				long accountHash;
				HistoryIndex scanned;
				synchronized (publishLock)
				{
					accountHash = currentAccountHash;
					scanned = index;
				}
				try
				{
					List<HistoryEntry> refreshed = scanned.refresh(accountHash, persistence, DISPLAY_WINDOW, Instant.now());
					boolean changed;
					synchronized (publishLock)
					{
						if (scanned != index)
						{
							// The account changed while scanning: this result
							// belongs to the old account and is dropped (its own
							// ensureAccountLoaded already requested a new scan).
							continue;
						}
						changed = !sameEntries(cachedSnapshot, refreshed) || !initialScanComplete;
						cachedSnapshot = refreshed;
						initialScanComplete = true;
					}
					if (changed)
					{
						notifyChanged();
					}
				}
				catch (Exception e)
				{
					log.warn("History index refresh failed for account {}", accountHash, e);
				}
			}
		}
		finally
		{
			refreshInFlight.set(false);
			// A request raised after the loop's last check but before the
			// release above found the worker busy: serve it now.
			if (refreshRequestedAgain.get())
			{
				startWorkerIfIdle();
			}
		}
	}

	/**
	 * Lazy full-detail load for one entry (spec Part R) -- run on the
	 * background executor, never the EDT. `onLoaded` is invoked on
	 * that same background thread; callers touching Swing state from
	 * it must marshal back onto the EDT themselves (e.g.
	 * SwingUtilities.invokeLater()), the same obligation any other
	 * background-thread callback in this codebase already carries.
	 * Passes null to `onLoaded` if the file is missing/malformed (fail
	 * open, spec Part Y) or the executor is not currently running.
	 */
	public void loadDetail(long accountHash, String sessionId, Consumer<Session> onLoaded)
	{
		ExecutorService currentExecutor = this.executor;
		if (currentExecutor == null || currentExecutor.isShutdown())
		{
			onLoaded.accept(null);
			return;
		}
		try
		{
			currentExecutor.submit(() ->
			{
				try
				{
					onLoaded.accept(persistence.loadFinalized(accountHash, sessionId));
				}
				catch (Exception e)
				{
					log.warn("Failed to load History detail for session {}", sessionId, e);
					onLoaded.accept(null);
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			onLoaded.accept(null);
		}
	}

	/**
	 * Convenience overload using this coordinator's own already-tracked
	 * {@link #currentAccountHash}
	 * -- lets {@code OsrsTelemetryPanel} (which, like every other tab's
	 * view, has no reason to track an account hash of its own) trigger a
	 * detail load from a bare sessionId alone, the same way
	 * {@link #getRecentEntries()} needs no accountHash parameter either.
	 * Same off-EDT/background-thread contract as the 3-arg overload.
	 */
	public void loadDetail(String sessionId, Consumer<Session> onLoaded)
	{
		loadDetail(currentAccountHash, sessionId, onLoaded);
	}
}
