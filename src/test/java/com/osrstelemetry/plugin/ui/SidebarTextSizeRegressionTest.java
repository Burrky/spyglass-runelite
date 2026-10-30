package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.SidebarTextContrast;
import com.osrstelemetry.plugin.SidebarTextSize;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * Pass 30: the sidebar's clipping audit (every Session activity type, History detail and list, Loot grouped and individual)
 * repeated at Large and Extra large text, in both contrasts: larger text may scroll further, but nothing is cut short or
 * runs off the side at the native width (242 px), or at 213 and 260 px.
 */
public class SidebarTextSizeRegressionTest
{
	@After
	public void restore()
	{
		Appearance.applyRoles(Appearance.DEFAULT);
	}

	private static void at(SidebarTextSize size, SidebarTextContrast contrast, ThrowingRunnable r) throws Exception
	{
		Appearance.applyRoles(new Appearance(size, contrast));
		try
		{
			r.run();
		}
		finally
		{
			Appearance.applyRoles(Appearance.DEFAULT);
		}
	}

	interface ThrowingRunnable
	{
		void run() throws Exception;
	}

	@Test
	public void session_history_loot_neverClip_atLargeAndExtraLarge() throws Exception
	{
		List<String> problems = new ArrayList<>();
		for (SidebarTextSize size : new SidebarTextSize[]{SidebarTextSize.LARGE, SidebarTextSize.EXTRA_LARGE})
		{
			for (SidebarTextContrast contrast : SidebarTextContrast.values())
			{
				SidebarClippingRegressionTest t = new SidebarClippingRegressionTest();
				for (ThrowingRunnable r : new ThrowingRunnable[]{t::currentSession_everyActivityType_neverClips_atAnySidebarWidth,
					t::history_detailAndList_neverClip_atAnySidebarWidth, t::loot_groupedAndIndividual_neverClip_atAnySidebarWidth})
				{
					try
					{
						at(size, contrast, r);
					}
					catch (AssertionError e)
					{
						// Larger text is checked from 213 px up: RuneLite's sidebar is never narrower than that, and the
						// shared audit's 150 / 180 px rows are a Standard-size stress test.
						for (String line : String.valueOf(e.getMessage()).split("\n"))
						{
							if (!line.contains("@150") && !line.contains("@180") && !line.trim().isEmpty())
							{
								problems.add(size + "/" + contrast + ": " + line);
							}
						}
					}
				}
			}
		}
		org.junit.Assert.assertTrue(String.join("\n", problems), problems.isEmpty());
	}
}
