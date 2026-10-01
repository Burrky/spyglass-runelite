package com.osrstelemetry.plugin.ui;

import java.util.function.LongSupplier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Resolves Grand Exchange and High Alch unit prices for item IDs without ever calling
 * {@link ItemManager#getItemPrice(int)} or
 * {@link ItemManager#getItemComposition(int)} off RuneLite's client
 * thread.
 *
 * ROOT CAUSE this class fixes: those two ItemManager lookups call
 * into raw {@code Client} state and assert (via
 * {@code AssertionError}) that they run on RuneLite's client thread.
 * {@link CurrentSessionView#unitValue(int, com.osrstelemetry.plugin.LootValuationMode)}
 * used to call them directly from {@code render()}, which runs on the
 * Swing EDT (driven by {@code OsrsTelemetryPanel}'s
 * {@code javax.swing.Timer}) -- every render threw and aborted,
 * freezing the panel on its last successfully-rendered snapshot. This
 * is unlike {@link ItemManager#getImage(int, int, boolean)}, which is
 * already safe to call from any thread: it synchronously returns an
 * {@code AsyncBufferedImage} placeholder and dispatches its own pixel
 * loading onto the client thread internally -- confirmed by
 * inspecting RuneLite's own bytecode, and left untouched here.
 *
 * FIX: the Swing EDT only ever reads already-resolved values from
 * {@link #getGePrice(int)} / {@link #getHighAlchPrice(int)}, which
 * are backed by a {@link ResolvingCache} keyed by item ID. A cache
 * miss returns 0 immediately (never blocks the EDT) and schedules a
 * one-time resolution via {@link ClientThread#invokeLater(Runnable)}
 * -- RuneLite's own established, non-blocking mechanism for marshalling
 * work onto the client thread, the same primitive
 * {@code ItemManager.getImage(...)} uses internally. Once that
 * resolution runs (on the client thread, where the ItemManager calls
 * are legal) and stores the result, {@link #setOnResolved} asks the owning
 * view for one coalesced refresh, which reads the real value and re-sorts
 * the loot row into its correct total-value position. A failed
 * resolution clears its pending state so a later render can retry
 * rather than being stuck at a permanent zero.
 */
final class ItemValuationCache
{
	private final java.util.function.IntToLongFunction gePriceLookup;
	private final java.util.function.IntToLongFunction haPriceLookup;
	/** Runs a lookup on the client thread (ClientThread#invokeLater in production). */
	private final java.util.concurrent.Executor clientThreadExecutor;
	private final ResolvingCache<Integer, Long> gePrices = new ResolvingCache<>();
	private final ResolvingCache<Integer, Long> haPrices = new ResolvingCache<>();

	/**
	 * Bumped every time a
	 * price actually resolves (a real {@link ResolvingCache#put} below),
	 * never on a miss or a failure. A view that skips its expensive
	 * rebuild when nothing else has visibly changed (see
	 * LootTrackerView's own render-key mechanism) still needs to notice
	 * "a price I'm showing as 0/stale just resolved to a real value" --
	 * this counter is that signal, compared cheaply (a single long) rather
	 * than needing the view to re-walk every item id on every tick to
	 * check for a change. {@code volatile}: read from the EDT, written
	 * from the client thread (inside {@link ClientThread#invokeLater}).
	 */
	private volatile long version = 0L;

	/**
	 * Optional: runs (client thread) after each newly resolved price, so the
	 * owning view can request one coalesced refresh instead of waiting for
	 * the next poll. Many resolutions in one burst still coalesce into one
	 * EDT pass (see UiRefreshCoalescer).
	 */
	private volatile Runnable onResolved;

	void setOnResolved(Runnable onResolved)
	{
		this.onResolved = onResolved;
	}

	ItemValuationCache(ItemManager itemManager, ClientThread clientThread)
	{
		// No client thread (headless tests): prices simply stay unresolved.
		this(clientThread == null ? runnable -> { } : clientThread::invokeLater,
			itemId -> itemManager.getItemPrice(itemId),
			itemId -> itemManager.getItemComposition(itemId).getHaPrice());
	}

	/** Seam for tests: explicit client-thread executor and raw price lookups. */
	ItemValuationCache(java.util.concurrent.Executor clientThreadExecutor, java.util.function.IntToLongFunction gePriceLookup,
		java.util.function.IntToLongFunction haPriceLookup)
	{
		this.clientThreadExecutor = clientThreadExecutor;
		this.gePriceLookup = gePriceLookup;
		this.haPriceLookup = haPriceLookup;
	}

	/**
	 * Returns the cached Grand Exchange price for {@code itemId}, or
	 * 0 if not yet resolved. Safe to call from the Swing EDT. A miss
	 * schedules resolution on the client thread; a later render will
	 * see the real value.
	 */
	long getGePrice(int itemId)
	{
		return resolve(gePrices, itemId, () -> Math.max(0L, gePriceLookup.applyAsLong(itemId)));
	}

	/**
	 * Returns the cached High Alch price for {@code itemId}, or 0 if
	 * not yet resolved. Safe to call from the Swing EDT. A miss
	 * schedules resolution on the client thread; a later render will
	 * see the real value.
	 */
	long getHighAlchPrice(int itemId)
	{
		return resolve(haPrices, itemId, () -> Math.max(0L, haPriceLookup.applyAsLong(itemId)));
	}

	/** See {@link #version}'s own javadoc. Safe to call from the EDT. */
	long getVersion()
	{
		return version;
	}

	private long resolve(ResolvingCache<Integer, Long> cache, int itemId, LongSupplier lookup)
	{
		Long cached = cache.getIfResolved(itemId);
		if (cached != null)
		{
			return cached;
		}

		// resolveIfAbsent is a no-op if a resolution for this item ID is
		// already resolved or already in flight -- callers never pile up
		// duplicate ClientThread round-trips for the same item ID.
		cache.resolveIfAbsent(itemId, id -> clientThreadExecutor.execute(() ->
		{
			try
			{
				cache.put(id, lookup.getAsLong());
				version++;
				Runnable listener = onResolved;
				if (listener != null)
				{
					listener.run();
				}
			}
			catch (RuntimeException e)
			{
				// Leave it unresolved rather than caching a permanent 0 --
				// a later render is allowed to retry.
				cache.clearPending(id);
			}
		}));

		return 0L;
	}
}
