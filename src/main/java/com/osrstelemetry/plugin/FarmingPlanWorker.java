package com.osrstelemetry.plugin;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPreferences;
import com.osrstelemetry.plugin.farming.FarmingPreparation;
import com.osrstelemetry.plugin.farming.FarmingRunIntent;
import com.osrstelemetry.plugin.travel.TransportAvailability;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import com.osrstelemetry.plugin.travel.TransportEvaluator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The Farming run's travel plan, computed off the client thread (canonical §28.12).
 *
 * <p>Live finding (2026-10-21): during preparation the game stuttered every tick. The whole travel catalogue (about 7,900
 * transports and journeys) was re-evaluated and the run re-planned on the client thread every tick, because the account
 * snapshot was a new object every tick: 40–200 ms and about 32 MB allocated per tick (measured). Guidance did the same every
 * ten ticks. Now the client thread only describes what the plan depends on ({@link Key}); one background thread computes the
 * newest request (older pending requests are dropped), and consumers use the latest result for the same account and run.
 * Supplies are still checked every tick against the live inventory by the (cheap) preparation evaluator, so the page follows
 * supply changes at once; only the choice of journeys waits for the background plan.
 *
 * <p>Nothing here touches the client: inputs are immutable snapshots captured on the client thread.
 */
@Slf4j
@Singleton
public class FarmingPlanWorker
{
	/** What a plan depends on. Identity for the snapshots (the adapters publish a new object only on change). */
	public static final class Key
	{
		final AccountCapability cap;
		final FarmingRunIntent intent;
		final FarmingPreferences prefs;
		final Object house;
		final Object knowledge;
		final long routes;
		final long refresh;
		/** The compost bins as last seen (by reference; folded in like the knowledge). */
		Map<String, com.osrstelemetry.plugin.farming.CompostBin.Seen> bins = java.util.Collections.emptyMap();

		public Key withBins(Map<String, com.osrstelemetry.plugin.farming.CompostBin.Seen> seen)
		{
			bins = seen == null ? java.util.Collections.emptyMap() : seen;
			return this;
		}

		public Key(AccountCapability cap, FarmingRunIntent intent, FarmingPreferences prefs, Object house, Object knowledge, long routes, long refresh)
		{
			this.cap = cap;
			this.intent = intent;
			this.prefs = prefs;
			this.house = house;
			this.knowledge = knowledge;
			this.routes = routes;
			this.refresh = refresh;
		}

		/** Same inputs, ignoring the patch knowledge (a fresh copy every call; it is folded in by the refresh). */
		boolean sameAs(Key o)
		{
			return o != null && cap == o.cap && intent == o.intent && prefs == o.prefs && house == o.house && routes == o.routes && refresh == o.refresh;
		}
	}

	/** A computed plan, with the key it was computed for and how long it took. */
	public static final class Result
	{
		private final Key key;
		private final List<TransportAvailability> availability;
		private final FarmingPreparation.Plan plan;
		private final long millis;

		Result(Key key, List<TransportAvailability> availability, FarmingPreparation.Plan plan, long millis)
		{
			this.key = key;
			this.availability = availability;
			this.plan = plan;
			this.millis = millis;
		}

		public FarmingPreparation.Plan getPlan()
		{
			return plan;
		}

		public List<TransportAvailability> getAvailability()
		{
			return availability;
		}

		public long getMillis()
		{
			return millis;
		}

		/** Usable for this account and this run (a plan for another account or run never is). */
		public boolean fits(AccountCapability cap, FarmingRunIntent intent)
		{
			return cap != null && intent != null && key.cap != null && java.util.Objects.equals(key.cap.getOwner(), cap.getOwner())
				&& key.intent != null && key.intent.getIntentId().equals(intent.getIntentId()); // preparation's plan carries into the run
		}
	}

	private final AtomicReference<Runnable> pending = new AtomicReference<>();
	private final AtomicLong generation = new AtomicLong();
	private final AtomicLong computed = new AtomicLong();
	private volatile Result latest;
	private volatile Key requested;
	private volatile ExecutorService executor;
	/** Tests and tools: compute on the caller instead of a background thread. */
	private volatile boolean direct;

	public synchronized void start()
	{
		if (executor == null)
		{
			executor = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "spyglass-farming-plan");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
	}

	public synchronized void stop()
	{
		reset();
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	/** Forget everything (logout, another account or profile, run ended): nothing in flight may be published. */
	public void reset()
	{
		generation.incrementAndGet();
		pending.set(null);
		requested = null;
		latest = null;
	}

	void setDirect(boolean direct)
	{
		this.direct = direct;
	}

	/** The newest finished plan (any thread), or null. Check {@link Result#fits} before use. */
	public Result latest()
	{
		return latest;
	}

	/** Plans computed so far (diagnostics and tests). */
	public long computed()
	{
		return computed.get();
	}

	/**
	 * Client thread: asks for a plan for these inputs unless the same was asked last. Returns at once; the newest request
	 * replaces any that has not started.
	 */
	public void request(Key key, TransportCatalog catalog, FarmingPatchCatalog patches, java.util.function.Supplier<EvidenceSource> evidenceOf, long now,
		Map<String, com.osrstelemetry.plugin.farming.PatchKnowledge> knowledge)
	{
		if (key.sameAs(requested) || catalog == null)
		{
			return;
		}
		requested = key;
		long gen = generation.get();
		EvidenceSource evidence = evidenceOf.get(); // read on the caller (client thread): immutable from here on
		Runnable job = () -> {
			long t0 = System.nanoTime();
			List<TransportAvailability> availability = TransportEvaluator.evaluateAll(catalog, key.cap, evidence, now);
			FarmingPreparation.Plan plan = FarmingPreparation.plan(key.intent, patches, key.cap, availability, evidence, key.prefs, knowledge, key.bins, now);
			computed.incrementAndGet();
			if (generation.get() == gen)
			{
				latest = new Result(key, availability, plan, (System.nanoTime() - t0) / 1_000_000);
			}
		};
		if (direct)
		{
			job.run();
			return;
		}
		ExecutorService ex = executor;
		if (ex == null)
		{
			return;
		}
		if (pending.getAndSet(job) == null)
		{
			try
			{
				ex.execute(this::drain);
			}
			catch (java.util.concurrent.RejectedExecutionException e)
			{
				pending.set(null);
			}
		}
	}

	private void drain()
	{
		Runnable job;
		while ((job = pending.getAndSet(null)) != null)
		{
			try
			{
				job.run();
			}
			catch (RuntimeException e)
			{
				log.debug("Spyglass farming plan failed", e);
			}
		}
	}
}
