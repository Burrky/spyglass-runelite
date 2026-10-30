package com.osrstelemetry.plugin;

import com.osrstelemetry.plugin.navigation.NavAccess;
import com.osrstelemetry.plugin.navigation.NavPoint;
import com.osrstelemetry.plugin.navigation.RoutePlanner;
import com.osrstelemetry.plugin.navigation.RouteTable;
import java.util.Collections;

/** Drives {@link NavigationRuntime} for tests: a search answered after a reset must never be published. */
public final class NavigationRuntimeProbe
{
	public boolean staleResultDropped() throws InterruptedException
	{
		NavigationRuntime rt = new NavigationRuntime();
		rt.start();
		try
		{
			RouteTable t = RouteTable.bundled();
			java.util.Map<String, RoutePlanner.Goal> goals = Collections.singletonMap("stop:Catherby#allotment.catherby.n",
				new RoutePlanner.Goal(t.goals("stop:Catherby#allotment.catherby.n"), 0));
			int falador = NavPoint.pack(2964, 3378, 0);
			long until = System.currentTimeMillis() + 30_000;
			while (!rt.request("Catherby", falador, goals, NavAccess.UNGATED, 5000))
			{
				if (System.currentTimeMillis() > until)
				{
					return false;
				}
				Thread.sleep(50); // world data still loading
			}
			rt.reset(); // e.g. logout, another profile, the run ended, or a teleport made it moot
			while (rt.isBusy() && System.currentTimeMillis() < until)
			{
				Thread.sleep(20);
			}
			if (rt.latest() != null)
			{
				return false; // stale result published
			}
			// A fresh request after the reset is answered and published.
			while (!rt.request("Catherby", falador, goals, NavAccess.UNGATED, 5000) && System.currentTimeMillis() < until)
			{
				Thread.sleep(20);
			}
			while (rt.isBusy() && System.currentTimeMillis() < until)
			{
				Thread.sleep(20);
			}
			return rt.latest() != null && rt.latest().getPath() != null;
		}
		finally
		{
			rt.stop();
			// The shared router is left closed by stop(); other tests use it synchronously.
			com.osrstelemetry.plugin.navigation.AccountRouter.useExecutor(null);
		}
	}
}
