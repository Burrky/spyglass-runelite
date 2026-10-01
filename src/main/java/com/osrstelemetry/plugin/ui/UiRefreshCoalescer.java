package com.osrstelemetry.plugin.ui;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Turns any number of "surface X changed" notifications -- from any thread
 * (client thread, EventLedger listeners, writer / history / price
 * callbacks) -- into at most ONE queued EDT pass at a time.
 *
 * The first dirty mark queues one task on {@code edt}; further marks before
 * it runs only union into the pending set. The task takes the whole set
 * atomically and hands it to {@code refresh}, which reads the newest state.
 * A mark arriving after that queues the next pass. There is no timer and no
 * debounce: coalescing is exactly one EDT queue turn.
 *
 * {@link #markDirty} is cheap and never blocks on the EDT; the lock guards
 * two fields and is never held while calling out, so it cannot take part in
 * a lock cycle. After {@link #shutdown()} marks are ignored and an
 * already-queued pass does nothing.
 */
final class UiRefreshCoalescer
{
	private final Executor edt;
	private final Consumer<Set<UiSurface>> refresh;

	private final Set<UiSurface> pending = EnumSet.noneOf(UiSurface.class);
	private boolean queued;
	private boolean shutdown;

	UiRefreshCoalescer(Executor edt, Consumer<Set<UiSurface>> refresh)
	{
		this.edt = edt;
		this.refresh = refresh;
	}

	void markDirty(UiSurface surface)
	{
		synchronized (this)
		{
			if (shutdown)
			{
				return;
			}
			pending.add(surface);
			if (queued)
			{
				return;
			}
			queued = true;
		}
		edt.execute(this::drain);
	}

	private void drain()
	{
		Set<UiSurface> dirty;
		synchronized (this)
		{
			queued = false;
			if (shutdown || pending.isEmpty())
			{
				pending.clear();
				return;
			}
			dirty = Collections.unmodifiableSet(EnumSet.copyOf(pending));
			pending.clear();
		}
		refresh.accept(dirty);
	}

	void shutdown()
	{
		synchronized (this)
		{
			shutdown = true;
			pending.clear();
		}
	}
}
