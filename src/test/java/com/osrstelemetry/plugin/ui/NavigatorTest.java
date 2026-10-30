package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.ui.NavRoute.Section;
import com.osrstelemetry.plugin.ui.Navigator.HistoryDetailDecision;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Navigation rules: route persistence/fallback and the parent-navigation state machine. */
public class NavigatorTest
{
	private static final String SESSION_A = "4f7c2a9e-1b3d-4c5e-8f60-0a1b2c3d4e5f";
	private static final String SESSION_B = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d";

	// -- persisted value -----------------------------------------------

	@Test
	public void noSavedRoute_isHome()
	{
		assertEquals(NavRoute.HOME, NavRoute.parse(null));
		assertEquals(NavRoute.HOME, NavRoute.parse(""));
		assertEquals(NavRoute.HOME, NavRoute.parse("   "));
	}

	@Test
	public void everySavedRoute_roundTrips()
	{
		for (NavRoute route : Arrays.asList(
			NavRoute.HOME,
			NavRoute.of(Section.HOME, NavRoute.FARMING),
			NavRoute.root(Section.SESSION),
			NavRoute.root(Section.LOOT),
			NavRoute.root(Section.HISTORY),
			NavRoute.of(Section.HISTORY, SESSION_A)))
		{
			assertEquals(route, NavRoute.parse(route.serialize()));
		}
		assertEquals("HOME/FARMING", NavRoute.of(Section.HOME, NavRoute.FARMING).serialize());
	}

	@Test
	public void malformedValue_fallsBackToHome()
	{
		for (String junk : Arrays.asList("garbage", "home", "SESSIONS", "/FARMING", "{\"section\":\"LOOT\"}", "LOOT TRACKER", "3"))
		{
			assertEquals(junk, NavRoute.HOME, NavRoute.parse(junk));
		}
	}

	@Test
	public void invalidChildOnARecognisedSection_fallsBackToThatSectionsRoot_notHome()
	{
		assertEquals(NavRoute.root(Section.LOOT), NavRoute.parse("LOOT/FARMING"));
		assertEquals(NavRoute.root(Section.SESSION), NavRoute.parse("SESSION/anything"));
		assertEquals(NavRoute.HOME, NavRoute.parse("HOME/NOTES")); // not a Home child yet
		assertEquals(NavRoute.HOME, NavRoute.parse("HOME/"));
		assertEquals(NavRoute.root(Section.HISTORY), NavRoute.parse("HISTORY/"));
		assertEquals(NavRoute.root(Section.HISTORY), NavRoute.parse("HISTORY/has spaces"));
		assertEquals(NavRoute.root(Section.HISTORY), NavRoute.parse("HISTORY/../../etc"));
	}

	// -- state machine -----------------------------------------------------

	@Test
	public void freshNavigator_startsAtHome()
	{
		assertEquals(NavRoute.HOME, new Navigator().current());
	}

	@Test
	public void restore_landsOnTheSavedRoute()
	{
		Navigator nav = new Navigator();
		nav.restore(NavRoute.parse("HOME/FARMING"));
		assertEquals(NavRoute.of(Section.HOME, NavRoute.FARMING), nav.current());
		assertEquals(Section.HOME, nav.current().getSection()); // House stays selected inside Farming
	}

	@Test
	public void house_alwaysReturnsToHomeRoot_includingFromFarming()
	{
		Navigator nav = new Navigator();
		nav.openChild(Section.HOME, NavRoute.FARMING);
		assertEquals(NavRoute.HOME, nav.house());
		nav.selectSection(Section.LOOT);
		assertEquals(NavRoute.HOME, nav.house());
		nav.openChild(Section.HISTORY, SESSION_A);
		assertEquals(NavRoute.HOME, nav.house());
	}

	@Test
	public void farmingCard_opensFarming_andUpReturnsToHomeRoot()
	{
		Navigator nav = new Navigator();
		assertEquals(NavRoute.of(Section.HOME, NavRoute.FARMING), nav.openChild(Section.HOME, NavRoute.FARMING));
		assertEquals(NavRoute.HOME, nav.toParent());
	}

	@Test
	public void inactiveSection_restoresItsLastChild()
	{
		Navigator nav = new Navigator();
		nav.selectSection(Section.HISTORY);
		nav.openChild(Section.HISTORY, SESSION_A);
		nav.selectSection(Section.LOOT);
		assertEquals(NavRoute.of(Section.HISTORY, SESSION_A), nav.selectSection(Section.HISTORY));
	}

	@Test
	public void activeSection_returnsToItsRoot_andForgetsTheChild()
	{
		Navigator nav = new Navigator();
		nav.selectSection(Section.HISTORY);
		nav.openChild(Section.HISTORY, SESSION_A);
		assertEquals(NavRoute.root(Section.HISTORY), nav.selectSection(Section.HISTORY));
		assertNull(nav.lastChild(Section.HISTORY));
		nav.selectSection(Section.SESSION);
		assertEquals("re-entering after going to root shows the list", NavRoute.root(Section.HISTORY), nav.selectSection(Section.HISTORY));

		assertEquals(NavRoute.root(Section.LOOT), nav.selectSection(Section.LOOT));
		assertEquals(NavRoute.root(Section.LOOT), nav.selectSection(Section.LOOT));
	}

	@Test
	public void homeSection_fromFarming_isHomeRoot()
	{
		Navigator nav = new Navigator();
		nav.openChild(Section.HOME, NavRoute.FARMING);
		assertEquals("the active section (Home) goes to its root", NavRoute.HOME, nav.selectSection(Section.HOME));
		nav.openChild(Section.HOME, NavRoute.FARMING);
		nav.selectSection(Section.SESSION);
		assertEquals("house never restores Farming", NavRoute.HOME, nav.house());
	}

	@Test
	public void restoreSeedsTheSectionsChild_soLeavingAndReturningKeepsIt()
	{
		Navigator nav = new Navigator();
		nav.restore(NavRoute.of(Section.HISTORY, SESSION_A));
		nav.selectSection(Section.SESSION);
		assertEquals(NavRoute.of(Section.HISTORY, SESSION_A), nav.selectSection(Section.HISTORY));
	}

	@Test
	public void childClosedOnItsOwn_dropsToThatRoot_onlyWhenShown()
	{
		Navigator nav = new Navigator();
		nav.openChild(Section.HISTORY, SESSION_A);
		assertEquals(NavRoute.root(Section.HISTORY), nav.childClosed(Section.HISTORY));

		nav.openChild(Section.HISTORY, SESSION_B);
		nav.selectSection(Section.LOOT);
		assertEquals("another section on screen is untouched", NavRoute.root(Section.LOOT), nav.childClosed(Section.HISTORY));
		assertEquals(NavRoute.root(Section.HISTORY), nav.selectSection(Section.HISTORY));
	}

	@Test
	public void depthIsCappedAtOne_openingAnotherChildReplacesIt()
	{
		Navigator nav = new Navigator();
		nav.openChild(Section.HISTORY, SESSION_A);
		nav.openChild(Section.HISTORY, SESSION_B);
		assertEquals("up is the root, never the previous detail", NavRoute.root(Section.HISTORY), nav.toParent());
	}

	// -- account-sensitive History detail -------------------------------------

	@Test
	public void historyDetail_waitsUntilTheCurrentAccountIsScanned()
	{
		assertEquals(HistoryDetailDecision.WAIT, Navigator.decideHistoryDetail(SESSION_A, false, Collections.<String>emptyList()));
		// Even a list that happens to hold the id is not trusted before the scan completes.
		assertEquals(HistoryDetailDecision.WAIT, Navigator.decideHistoryDetail(SESSION_A, false, Collections.singletonList(SESSION_A)));
	}

	@Test
	public void historyDetail_opensOnlyForTheAccountThatOwnsIt()
	{
		List<String> accountA = Arrays.asList(SESSION_A, "other");
		List<String> accountB = Collections.singletonList(SESSION_B);
		assertEquals(HistoryDetailDecision.OPEN, Navigator.decideHistoryDetail(SESSION_A, true, accountA));
		assertEquals("another account's session is never shown", HistoryDetailDecision.DROP, Navigator.decideHistoryDetail(SESSION_A, true, accountB));
		assertEquals(HistoryDetailDecision.DROP, Navigator.decideHistoryDetail(SESSION_A, true, Collections.<String>emptyList()));
	}

	@Test
	public void farmingSettings_isAChildOfFarming_kingdomAndDailiesOfHome()
	{
		Navigator nav = new Navigator();
		nav.openChild(Section.HOME, NavRoute.FARMING);
		assertEquals("HOME/FARMING_SETTINGS", nav.openChild(Section.HOME, NavRoute.FARMING_SETTINGS).serialize());
		assertEquals("settings go back to Farming, not Home", NavRoute.of(Section.HOME, NavRoute.FARMING), nav.toParent());
		assertEquals(NavRoute.HOME, nav.toParent());
		assertEquals(NavRoute.HOME, NavRoute.of(Section.HOME, NavRoute.KINGDOM).parent());
		assertEquals(NavRoute.of(Section.HOME, NavRoute.DAILIES), NavRoute.parse("HOME/DAILIES"));
		assertEquals("Farming settings", NavHeader.subHeaderTitle(NavRoute.of(Section.HOME, NavRoute.FARMING_SETTINGS)));
		assertEquals("Farming", NavHeader.parentLabel(NavRoute.of(Section.HOME, NavRoute.FARMING_SETTINGS)));
		assertTrue(NavHeader.showsGear(NavRoute.of(Section.HOME, NavRoute.FARMING)));
		assertFalse(NavHeader.showsGear(NavRoute.of(Section.HOME, NavRoute.KINGDOM)));
	}
}
