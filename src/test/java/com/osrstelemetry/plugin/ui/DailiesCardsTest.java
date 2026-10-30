package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.home.Dailies;
import com.osrstelemetry.plugin.home.DailyTask;
import com.osrstelemetry.plugin.home.Recurring;
import com.osrstelemetry.plugin.home.TearsOfGuthix;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Pass 31: Dailies &amp; Weeklies as cards. Tracking and claiming are separate (a claim keeps the tick, the tick never claims);
 * a counted claim keeps its icon and what is left; the icon comes back at the reset; Home's ready count is its icons; nothing
 * unread or of unknown eligibility counts as ready; a Home icon opens the page at its card.
 */
public class DailiesCardsTest
{
	static final long DAY = Dailies.DAY_MILLIS;
	static final long NOW = 20_754L * DAY + 10 * 3_600_000L;

	static Dailies.Read read(int zaff, int robin)
	{
		Dailies.Read r = new Dailies.Read();
		for (DailyTask t : DailyTask.values())
		{
			r.claim(t, 0);
			if (t.getGateVarbit() >= 0)
			{
				r.gate(t, t == DailyTask.SAND ? 160 : 1);
			}
		}
		r.gate(DailyTask.DYNAMITE, 0); // Kourend medium not done: locked
		r.claim(DailyTask.BATTLESTAVES, zaff).claim(DailyTask.BONEMEAL, robin);
		return r.morytania(1, 0).account(0, 20_000);
	}

	static RecurringPagesTest.FakeHome home(Dailies d)
	{
		RecurringPagesTest.FakeHome h = new RecurringPagesTest.FakeHome();
		h.dailies = d;
		return h;
	}

	@Test
	public void claimingKeepsTheTick_andTheTickNeverClaims()
	{
		Dailies d = Dailies.empty().with(read(0, 0), true, NOW).withTracked(DailyTask.BATTLESTAVES, true).withTracked(DailyTask.BONEMEAL, true);
		assertEquals("To claim", HomeText.dailyActivity(d, DailyTask.BATTLESTAVES, NOW).status);
		// Claimed in game: still ticked, the status says so and when it comes back.
		Dailies claimed = d.with(read(1, 0), false, NOW + 60_000);
		HomeText.Activity a = HomeText.dailyActivity(claimed, DailyTask.BATTLESTAVES, NOW + 60_000);
		assertTrue(a.selected);
		assertTrue(a.status, a.status.startsWith("Claimed · again in "));
		// Unticking and ticking again changes only the choice, never the claim.
		Dailies off = claimed.withTracked(DailyTask.BATTLESTAVES, false).withTracked(DailyTask.BATTLESTAVES, true);
		assertEquals(Dailies.State.DONE, off.status(DailyTask.BATTLESTAVES, NOW + 60_000).getState());
		// Partial: Robin's 26 uses (medium + hard), 10 used.
		Dailies partial = d.with(read(0, 10), false, NOW + 60_000);
		assertEquals("16 of 26 left", HomeText.dailyActivity(partial, DailyTask.BONEMEAL, NOW + 60_000).status);
	}

	@Test
	public void homeIcons_areTheReadyCount_keepPartialClaims_dropFinishedOnes_andReturnAtTheReset()
	{
		Recurring rec = Recurring.empty().withShown(Recurring.Entry.TEARS, true);
		Dailies d = Dailies.empty().with(read(0, 10), true, NOW).withTracked(DailyTask.BATTLESTAVES, true).withTracked(DailyTask.BONEMEAL, true)
			.withTracked(DailyTask.DYNAMITE, true);
		List<HomeText.Badge> b = HomeText.readyBadges(d, rec, NOW);
		assertEquals("[daily.BATTLESTAVES, daily.BONEMEAL×16]", b.toString());
		HomeText.Card card = HomeText.recurringCard(d, rec, true, NOW);
		assertEquals("2 ready", card.headline);
		assertEquals(card.badges.toString(), b.toString());
		// Tears of Guthix: its date not read is never ready (no icon).
		assertFalse(b.toString().contains("TEARS"));
		// Zaff claimed: its icon goes; Robin's stays with what is left.
		Dailies after = d.with(read(1, 12), false, NOW + 60_000);
		assertEquals("[daily.BONEMEAL×14]", HomeText.readyBadges(after, rec, NOW + 60_000).toString());
		assertEquals("1 ready", HomeText.recurringCard(after, rec, true, NOW + 60_000).headline);
		// After 00:00 UTC both are back (the game clears the claims; until read, the reset alone makes them claimable).
		assertEquals("[daily.BATTLESTAVES, daily.BONEMEAL×26]", HomeText.readyBadges(after, rec, NOW + DAY).toString());
		// Nothing read: no icons, never "ready".
		Dailies unread = Dailies.empty().withTracked(DailyTask.BATTLESTAVES, true);
		assertTrue(HomeText.readyBadges(unread, rec, NOW).isEmpty());
		assertEquals("Not read yet", HomeText.recurringCard(unread, rec, false, NOW).headline);
		// Tears ready: its icon.
		Recurring ready = rec.withTears(TearsOfGuthix.empty().with(new TearsOfGuthix.Read(true, 200, 200, 150, false, NOW - 9 * DAY), true, NOW));
		if (ready.tears().status(NOW).getState() == TearsOfGuthix.State.READY)
		{
			assertTrue(HomeText.readyBadges(after, ready, NOW + 60_000).toString().contains("recurring.TEARS"));
		}
	}

	@Test
	public void lockedCards_cantBeTicked_butCanBeUnticked_andSelectionsSurviveSaving()
	{
		Dailies d = Dailies.empty().with(read(0, 0), true, NOW);
		HomeText.Activity dyn = HomeText.dailyActivity(d, DailyTask.DYNAMITE, NOW);
		assertFalse(dyn.selectable);
		assertTrue(dyn.status, dyn.status.startsWith("Locked · Needs Kourend"));
		Dailies on = d.withTracked(DailyTask.DYNAMITE, true);
		assertTrue("a choice made before it locked can always be undone", HomeText.dailyActivity(on, DailyTask.DYNAMITE, NOW).selectable);
		Dailies back = new Gson().fromJson(new Gson().toJson(on.withTracked(DailyTask.SAND, true)), Dailies.class);
		assertTrue(back.isTracked(DailyTask.SAND) && back.isTracked(DailyTask.DYNAMITE));
	}

	@Test
	public void thePage_ticksByKeyboard_andAHomeIconOpensItAtThatCard()
	{
		RecurringPagesTest.FakeHome h = home(Dailies.empty().with(read(0, 0), true, NOW).withTracked(DailyTask.BATTLESTAVES, true));
		DailiesView v = new DailiesView();
		v.attach(h);
		v.setClock(() -> NOW);
		v.render(NOW);
		TrackBox sand = (TrackBox) RecurringPagesTest.named(v, "track.daily.SAND");
		assertNotNull(sand);
		sand.getActionMap().get("trackbox.toggle").actionPerformed(null); // Space
		assertTrue(h.dailies.isTracked(DailyTask.SAND));
		assertEquals("ticking never claims", Dailies.State.AVAILABLE, h.dailies.status(DailyTask.SAND, NOW).getState());
		TrackBox locked = (TrackBox) RecurringPagesTest.named(v, "track.daily.DYNAMITE");
		assertFalse(locked.isEnabled());
		// Home: the icons, then a click opens the page at that card (highlighted).
		HomeView home = new HomeView(null, () -> { });
		List<String> opened = new ArrayList<>();
		home.setOnOpenDaily(opened::add);
		home.renderHome(HomeText.kingdomCard(com.osrstelemetry.plugin.home.Kingdom.empty(), false, NOW),
			HomeText.recurringCard(h.dailies, h.recurring, true, NOW));
		assertEquals(2, home.badges().size());
		BadgeIcon icon = (BadgeIcon) home.badgePanel().getComponent(1);
		assertEquals("daily.SAND", icon.getBadge().key);
		icon.getActionMap().get("badge.open").actionPerformed(null);
		assertEquals("[daily.SAND]", opened.toString());
		v.focus("daily.SAND");
		DailiesView.ActivityTile t = DailiesView.tile(v, "daily.SAND");
		assertNotNull(t);
		assertEquals(SpyglassTheme.ACCENT_GOLD, t.getRail());
		assertNull(DailiesView.tile(v, "daily.BATTLESTAVES").getRail());
	}
}
