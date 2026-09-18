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
 * are legal) and stores the result, the next render (roughly one
 * second later, via the poll timer) reads the real value and the loot
 * row re-sorts into its correct total-value position. A failed
 * resolution clears its pending state so a later render can retry
 * rather than being stuck at a permanent zero.
 */
final class ItemValuationCache
{
	private final ItemManager itemManager;
	private final ClientThread clientThread;
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

	ItemValuationCache(ItemManager itemManager, ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
	}

	/**
	 * Returns the cached Grand Exchange price for {@code itemId}, or
	 * 0 if not yet resolved. Safe to call from the Swing EDT. A miss
	 * schedules resolution on the client thread; a later render will
	 * see the real value.
	 */
	long getGePrice(int itemId)
	{
		return resolve(gePrices, itemId, () -> (long) Math.max(0, itemManager.getItemPrice(itemId)));
	}

	/**
	 * Returns the cached High Alch price for {@code itemId}, or 0 if
	 * not yet resolved. Safe to call from the Swing EDT. A miss
	 * schedules resolution on the client thread; a later render will
	 * see the real value.
	 */
	long getHighAlchPrice(int itemId)
	{
		return resolve(haPrices, itemId, () -> (long) Math.max(0, itemManager.getItemComposition(itemId).getHaPrice()));
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
		cache.resolveIfAbsent(itemId, id -> clientThread.invokeLater(() ->
		{
			try
			{
				cache.put(id, lookup.getAsLong());
				version++;
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
