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
 * READ MODEL: getRecentEntries() (called from the EDT -- e.g.
 * OsrsTelemetryPanel's existing ~1s poll Timer, only while the
 * History card is actually visible) NEVER blocks: it returns whatever
 * HistoryIndex currently has cached in memory synchronously, and
 * separately kicks off a background refresh() so the NEXT call sees
 * anything newly finalized since the last one -- this is what spec
 * Part R's "detect newly finalized sessions without restart" means in
 * practice, without ever doing file IO on the EDT to get there.
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
 * entries could be read under the new account's identity.
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
	private volatile List<HistoryEntry> cachedSnapshot = Collections.emptyList();

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
		currentAccountHash = accountHash;
		accountLoaded = true;
		index = new HistoryIndex();
		cachedSnapshot = Collections.emptyList();
		triggerBackgroundRefresh(accountHash);
	}

	/**
	 * Called from the EDT. Never blocks: returns whatever is currently
	 * cached and asynchronously kicks off a refresh so a LATER call
	 * sees newly-finalized sessions -- see class javadoc.
	 */
	public List<HistoryEntry> getRecentEntries()
	{
		triggerBackgroundRefresh(currentAccountHash);
		return cachedSnapshot;
	}

	private void triggerBackgroundRefresh(long accountHash)
	{
		ExecutorService currentExecutor = this.executor;
		if (currentExecutor == null || currentExecutor.isShutdown())
		{
			return;
		}
		if (!refreshInFlight.compareAndSet(false, true))
		{
			// A refresh is already running; this call's caller will see
			// current data on its NEXT call instead of queueing a
			// redundant second pass.
			return;
		}
		HistoryIndex indexAtSubmitTime = this.index;
		currentExecutor.submit(() ->
		{
			try
			{
				cachedSnapshot = indexAtSubmitTime.refresh(accountHash, persistence, DISPLAY_WINDOW, Instant.now());
			}
			catch (Exception e)
			{
				log.warn("History index refresh failed for account {}", accountHash, e);
			}
			finally
			{
				refreshInFlight.set(false);
			}
		});
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
