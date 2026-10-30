package com.osrstelemetry.plugin;

import static com.osrstelemetry.plugin.account.AccountFixtures.NOW;
import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountOwner;
import com.osrstelemetry.plugin.account.EvidenceSource;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPreferences;
import com.osrstelemetry.plugin.farming.FarmingRunIntent;
import com.osrstelemetry.plugin.travel.TransportCatalog;
import java.util.Collections;
import org.junit.Test;

/**
 * The run's plan is worked out once per change of its inputs, off the client thread (live finding 2026-10-21: preparation
 * stuttered because the whole catalogue was re-evaluated on the client thread every tick).
 */
public class FarmingPlanWorkerTest
{
	private static final TransportCatalog TRANSPORTS = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();

	private static FarmingRunIntent intent(String id)
	{
		return com.osrstelemetry.plugin.farming.FarmingRunFixtures.herbRun(id, true, "herb.catherby", "herb.falador");
	}

	private static FarmingPlanWorker.Key key(AccountCapability cap, FarmingRunIntent intent, Object house, long refresh)
	{
		return new FarmingPlanWorker.Key(cap, intent, FarmingPreferences.defaults(), house, null, 0, refresh);
	}

	private static void ask(FarmingPlanWorker w, FarmingPlanWorker.Key k)
	{
		w.request(k, TRANSPORTS, CATALOG, () -> EvidenceSource.NONE, NOW, Collections.emptyMap());
	}

	@Test
	public void samInputs_planOnce_aChangedSnapshotOrHouse_plansAgain_noRestart()
	{
		FarmingPlanWorker w = new FarmingPlanWorker();
		w.setDirect(true);
		AccountCapability cap = maxed().build();
		FarmingRunIntent run = intent("fr-a");
		FarmingPreferences prefs = FarmingPreferences.defaults();
		Object house = new Object();
		FarmingPlanWorker.Key k = new FarmingPlanWorker.Key(cap, run, prefs, house, null, 0, 0);
		ask(w, k);
		ask(w, new FarmingPlanWorker.Key(cap, run, prefs, house, null, 0, 0));
		assertEquals("same inputs every tick: one plan", 1, w.computed());
		// Withdrawing supplies publishes a new snapshot: one new plan.
		AccountCapability carried = maxed().inventory(items(8011, 2)).build();
		ask(w, new FarmingPlanWorker.Key(carried, run, prefs, house, null, 0, 0));
		assertEquals(2, w.computed());
		// A house visit records new facilities (a new knowledge object): the plan follows, no client restart.
		ask(w, new FarmingPlanWorker.Key(carried, run, prefs, new Object(), null, 0, 0));
		assertEquals(3, w.computed());
		// Route results arriving (the router's version moves): the plan follows.
		ask(w, new FarmingPlanWorker.Key(carried, run, prefs, new Object(), null, 1, 0));
		assertEquals(4, w.computed());
	}

	@Test
	public void aPlan_fitsOnlyItsAccountAndRun_andPreparationsPlanCarriesIntoTheRun()
	{
		FarmingPlanWorker w = new FarmingPlanWorker();
		w.setDirect(true);
		AccountCapability cap = maxed().build();
		FarmingRunIntent run = intent("fr-a");
		ask(w, key(cap, run, null, 0));
		FarmingPlanWorker.Result r = w.latest();
		assertNotNull(r);
		assertTrue(r.fits(cap, run));
		assertTrue("Begin run: the preparation plan is used until the next arrives", r.fits(cap, com.osrstelemetry.plugin.farming.FarmingRunFixtures.herbRun("fr-a", false, "herb.catherby", "herb.falador")));
		assertFalse("another run", r.fits(cap, intent("fr-b")));
		AccountCapability other = AccountCapability.builder().owner(AccountOwner.standard(999L)).build();
		assertFalse("another account", r.fits(other, run));
		w.reset();
		assertNull("logout / another profile: nothing kept", w.latest());
	}

	@Test
	public void inTheBackground_onlyTheNewestRequestRuns_andAResetDropsWhatWasInFlight() throws Exception
	{
		FarmingPlanWorker w = new FarmingPlanWorker();
		w.start();
		try
		{
			FarmingRunIntent run = intent("fr-a");
			for (int i = 0; i < 20; i++)
			{
				ask(w, key(maxed().inventory(items(8011, i + 1)).build(), run, null, 0)); // a burst of supply changes
			}
			w.reset(); // e.g. logout before any of them finished
			long deadline = System.currentTimeMillis() + 120_000;
			while (w.computed() == 0 && System.currentTimeMillis() < deadline)
			{
				Thread.sleep(20);
			}
			Thread.sleep(200);
			assertTrue("a burst collapses to the newest request, not one plan per change (" + w.computed() + ")", w.computed() <= 2);
			assertNull("results from before the reset are never published", w.latest());
		}
		finally
		{
			w.stop();
		}
	}
}
