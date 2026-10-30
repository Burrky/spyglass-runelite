package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.AccountFixtures;
import com.osrstelemetry.plugin.home.Dailies;
import com.osrstelemetry.plugin.home.DailyTask;
import com.osrstelemetry.plugin.home.Kingdom;
import com.osrstelemetry.plugin.home.Recurring;
import com.osrstelemetry.plugin.home.TearsOfGuthix;
import com.osrstelemetry.plugin.hunter.BirdhousePlan;
import com.osrstelemetry.plugin.hunter.BirdhouseRun;
import com.osrstelemetry.plugin.hunter.BirdhouseSpace;
import com.osrstelemetry.plugin.hunter.BirdhouseTier;
import com.osrstelemetry.plugin.hunter.BirdhouseTraps;
import com.osrstelemetry.plugin.prep.PreparationEvaluator;
import com.osrstelemetry.plugin.prep.PreparationState;
import com.osrstelemetry.plugin.prep.Readiness;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JLabel;
import org.junit.Test;

/** Pass 29: the Recurring page's groups and the bird house page's stages (start, prepare, run, complete), with their controls. */
public class RecurringPagesTest
{
	static
	{
		com.osrstelemetry.plugin.navigation.TestRoutes.install(); // the client's Gson in the plugin
	}

	static final long NOW = 1_800_000_000_000L;

	static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	static String text(Container c)
	{
		StringBuilder b = new StringBuilder();
		for (Component k : all(c))
		{
			if (k instanceof JLabel && ((JLabel) k).getText() != null)
			{
				b.append(((JLabel) k).getText().replaceAll("<[^>]*>", "")).append('\n');
			}
			if (k instanceof ActionButton)
			{
				b.append('[').append(((ActionButton) k).getLabel()).append("]\n");
			}
		}
		return b.toString();
	}

	static Component named(Container c, String name)
	{
		for (Component k : all(c))
		{
			if (name.equals(k.getName()))
			{
				return k;
			}
		}
		return null;
	}

	static final class FakeHome implements HomeSource
	{
		Dailies dailies = Dailies.empty();
		Recurring recurring = Recurring.empty();

		@Override
		public Dailies dailies()
		{
			return dailies;
		}

		@Override
		public Kingdom kingdom()
		{
			return Kingdom.empty();
		}

		@Override
		public boolean onIslands()
		{
			return false;
		}

		@Override
		public boolean loggedIn()
		{
			return true;
		}

		@Override
		public void setTracked(DailyTask task, boolean on)
		{
			dailies = dailies.withTracked(task, on);
		}

		@Override
		public Recurring recurring()
		{
			return recurring;
		}

		@Override
		public void setShown(Recurring.Entry entry, boolean on)
		{
			recurring = recurring.withShown(entry, on);
		}

	}

	static BirdhouseTraps full()
	{
		Map<BirdhouseSpace, Integer> built = new EnumMap<>(BirdhouseSpace.class);
		Map<BirdhouseSpace, Integer> seeded = new EnumMap<>(BirdhouseSpace.class);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			built.put(s, 25);
			seeded.put(s, 27);
		}
		return BirdhouseTraps.empty().withReading(built, NOW - 3 * 3_600_000L).withReading(seeded, NOW - 2 * 3_600_000L);
	}

	@Test
	public void dailiesAndWeekliesPage_groups_cardsWithTicks_andEmptyChoice()
	{
		FakeHome home = new FakeHome();
		DailiesView v = new DailiesView();
		v.attach(home);
		v.setClock(() -> NOW);
		v.render(NOW);
		String t = text(v);
		assertTrue(t, t.contains("Tick the activities you do"));
		assertTrue(t.contains("DAILY") && t.contains("WEEKLY") && t.contains("DAILY USES"));
		assertFalse("bird houses have their own Home card: no Timers group, no bird house choice", t.contains("Timers") || t.contains("Bird houses"));
		assertNull(named(v, "recurring.BIRDHOUSES"));
		TrackBox tears = (TrackBox) named(v, "track.recurring.TEARS");
		assertTrue(tears.isFocusable());
		assertFalse(tears.isSelected());
		tears.toggle(); // as a click or Space
		home.setShown(Recurring.Entry.PRAYER, true);
		home.recurring = home.recurring.withTears(TearsOfGuthix.empty().with(new TearsOfGuthix.Read(true, 200, 200, 150, false, 5_000_000L), true, NOW));
		v.invalidateSignature();
		v.render(NOW);
		t = text(v);
		assertTrue(home.recurring.isShown(Recurring.Entry.TEARS));
		assertTrue(t, t.contains("Tears of Guthix") && t.contains("Date not read"));
		assertTrue(((TrackBox) named(v, "track.recurring.TEARS")).isSelected());
		assertTrue(t.contains("Shield prayer") && t.contains("Not read"));
		DailiesView.ActivityTile prayer = (DailiesView.ActivityTile) named(v, "recurring.PRAYER");
		assertEquals(SpyglassTheme.CARD_SELECTED, prayer.getFace());
		assertEquals(SpyglassTheme.CARD_UNSELECTED, ((DailiesView.ActivityTile) named(v, "recurring.CABBAGE")).getFace());
	}

	static final class FakeBirds implements BirdhouseSource
	{
		BirdhouseTraps traps = full();
		BirdhouseRun run;
		AccountCapability cap = AccountFixtures.base(99).build();
		boolean farming;
		com.osrstelemetry.plugin.hunter.BirdhouseChoice choice = com.osrstelemetry.plugin.hunter.BirdhouseChoice.empty();

		@Override
		public com.osrstelemetry.plugin.hunter.BirdhouseChoice choice()
		{
			return choice;
		}

		@Override
		public void choose(com.osrstelemetry.plugin.hunter.BirdhouseChoice c)
		{
			choice = c;
		}

		@Override
		public BirdhouseTraps traps()
		{
			return traps;
		}

		@Override
		public BirdhouseRun run()
		{
			return run;
		}

		@Override
		public BirdhousePlan plan()
		{
			return run == null ? null : BirdhousePlan.plan(run, traps, cap, Collections.emptyList(), false, NOW);
		}

		@Override
		public PreparationState preparation()
		{
			return run == null || !run.isPreparing() ? null : PreparationEvaluator.evaluate(plan().getRequest(), cap, NOW);
		}

		@Override
		public Readiness readiness()
		{
			return Readiness.MISSING;
		}

		@Override
		public AccountCapability capability()
		{
			return cap;
		}

		@Override
		public boolean farmingRunActive()
		{
			return farming;
		}

		@Override
		public boolean onIsland()
		{
			return false;
		}

		@Override
		public boolean start(Set<BirdhouseSpace> spaces, BirdhouseTier tier)
		{
			if (farming || spaces.isEmpty() || tier == null && BirdhousePlan.tierRequired(spaces, traps, NOW))
			{
				return false; // as the service: the tier comes before Start
			}
			run = BirdhouseRun.start(spaces, tier, traps, NOW);
			return true;
		}

		@Override
		public void setTier(BirdhouseTier tier)
		{
			run = run.withTier(tier);
		}

		@Override
		public void skip(BirdhouseSpace space, boolean skip)
		{
			run = run.withSkipped(space, skip).observe(traps, NOW);
		}

		@Override
		public void begin()
		{
			run = run.begun();
		}

		@Override
		public void end()
		{
			run = run.ended(NOW);
		}

		@Override
		public void dismiss()
		{
			run = null;
		}

		@Override
		public void find(int[] itemIds)
		{
		}
	}

	@Test
	public void birdhousePage_select_tierBeforeStart_prepare_run_skip_complete()
	{
		FakeBirds b = new FakeBirds();
		BirdhouseView v = new BirdhouseView();
		v.attach(b);
		v.setClock(() -> NOW);
		v.render(NOW);
		String t = text(v);
		assertTrue(t, t.contains("Verdant Valley (Northeast)") && t.contains("Full") && t.contains("[Start run]"));
		assertTrue("a card per space, the card is the control", named(v, "birdhouse.space.MEADOW_NORTH").isFocusable());
		ActionButton start = (ActionButton) named(v, "birdhouse.start");
		assertFalse("no tier chosen: Start is not offered", start.isEnabled());
		assertTrue(t.contains("Choose a bird house tier."));
		start.press();
		assertNull("never a tierless run", b.run);
		assertFalse("the service refuses one too", b.start(EnumSet.allOf(BirdhouseSpace.class), null));
		// Leave two spaces out (as clicking their cards does), then choose the tier.
		((RaisedTile) named(v, "birdhouse.space.MEADOW_NORTH")).activate();
		v.render(NOW);
		((RaisedTile) named(v, "birdhouse.space.MEADOW_SOUTH")).activate();
		v.render(NOW);
		assertEquals(EnumSet.of(BirdhouseSpace.VALLEY_NORTH, BirdhouseSpace.VALLEY_SOUTH), b.choice.spaces());
		for (int i = 0; i < BirdhouseTier.REDWOOD.ordinal() + 1; i++)
		{
			((FarmingView.Link) named(v, "birdhouse.tier.next")).click();
		}
		assertEquals(BirdhouseTier.REDWOOD, b.choice.getTier());
		start = (ActionButton) named(v, "birdhouse.start");
		assertTrue(start.isEnabled());
		start.press();
		assertEquals(BirdhouseTier.REDWOOD, b.run.getTier());
		t = text(v);
		assertTrue(t, t.contains("BIRD HOUSE RUN · PREPARING") && t.contains("Redwood · 2 houses"));
		assertFalse("no four-house list and no emptying steps in preparation", t.contains("Empty the bird house") || t.contains("Choose a bird house"));
		assertTrue(t, t.contains("Redwood bird houses or logs") && t.contains("STILL NEEDED") && t.contains("TRAVEL"));
		assertTrue(((ActionButton) named(v, "birdhouse.begin")).isEnabled());
		b.begin();
		v.invalidateSignature();
		v.render(NOW);
		t = text(v);
		assertTrue(t, t.contains("NEXT") && t.contains("TO DO") && t.contains("Empty the bird house") && t.contains("0 of 2 set"));
		assertNotNull(named(v, "birdhouse.skip.VALLEY_NORTH"));
		b.skip(BirdhouseSpace.VALLEY_NORTH, true);
		v.invalidateSignature();
		v.render(NOW);
		t = text(v);
		assertTrue("a skipped house stays apart with Undo", t.contains("SKIPPED") && named(v, "birdhouse.unskip.VALLEY_NORTH") != null);
		b.skip(BirdhouseSpace.VALLEY_SOUTH, true);
		v.invalidateSignature();
		v.render(NOW);
		t = text(v);
		assertTrue(t, t.contains("Run complete") && t.contains("[Done]"));
		b.dismiss();
		v.invalidateSignature();
		v.render(NOW);
		assertTrue(text(v).contains("[Start run]"));
	}

	@Test
	public void housesOnlyNeedingSeeds_needNoTier()
	{
		FakeBirds b = new FakeBirds();
		Map<BirdhouseSpace, Integer> built = new EnumMap<>(BirdhouseSpace.class);
		for (BirdhouseSpace s : BirdhouseSpace.values())
		{
			built.put(s, 25);
		}
		b.traps = BirdhouseTraps.empty().withReading(built, NOW - 60_000L);
		assertFalse(BirdhousePlan.tierRequired(EnumSet.allOf(BirdhouseSpace.class), b.traps, NOW));
		BirdhouseView v = new BirdhouseView();
		v.attach(b);
		v.render(NOW);
		assertTrue(((ActionButton) named(v, "birdhouse.start")).isEnabled());
	}

	@Test
	public void birdhouseStart_waitsForAFarmingRun()
	{
		FakeBirds b = new FakeBirds();
		b.farming = true;
		b.choice = b.choice.withTier(BirdhouseTier.OAK);
		BirdhouseView v = new BirdhouseView();
		v.attach(b);
		v.render(NOW);
		assertFalse(((ActionButton) named(v, "birdhouse.start")).isEnabled());
		assertTrue(text(v).contains("Finish or end your Farming run first"));
		assertNull(b.run);
	}

	@Test
	public void preparation_journeyDetailsStayOpen_acrossRebuilds()
	{
		FakeBirds b = new FakeBirds();
		b.choice = b.choice.withTier(BirdhouseTier.OAK);
		BirdhouseView v = new BirdhouseView();
		v.attach(b);
		v.setClock(() -> NOW);
		v.render(NOW);
		((ActionButton) named(v, "birdhouse.start")).press();
		v.render(NOW);
		FarmingView.ExpandToggle first = (FarmingView.ExpandToggle) named(v, "birdhouse.stop.1");
		assertNotNull(first);
		first.setSize(15, 15);
		first.dispatchEvent(new java.awt.event.MouseEvent(first, java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
		assertTrue("opened", first.isOpen());
		v.invalidateSignature();
		v.render(NOW);
		assertTrue("the same journey is open after the page rebuilt", ((FarmingView.ExpandToggle) named(v, "birdhouse.stop.1")).isOpen());
		FarmingView.ExpandToggle second = (FarmingView.ExpandToggle) named(v, "birdhouse.stop.2");
		assertFalse("only that one", second != null && second.isOpen());
	}
}
