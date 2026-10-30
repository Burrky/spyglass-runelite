package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.hunter.BirdhouseChoice;
import com.osrstelemetry.plugin.hunter.BirdhouseSpace;
import com.osrstelemetry.plugin.hunter.BirdhouseTier;
import com.osrstelemetry.plugin.hunter.BirdhouseTraps;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Pass 30: every piece of bird house state is per RuneScape profile: the selection and tier, the saved run, the timers and
 * the reminder's "already notified" mark. Switching profiles (RuneLite's profile-changed event) loads the other profile's
 * state and never carries one profile's into another; a restart (a new service on the same store) reads the same.
 */
public class BirdhouseServiceProfileTest
{
	static final long NOW = 1_800_000_000_000L;
	private final Map<String, Map<String, String>> profiles = new HashMap<>();
	private String profile = "A";

	private BirdhouseService service()
	{
		BirdhouseService s = new BirdhouseService(null, null, new Gson(), null, null, null, null, null, null);
		s.clock = () -> NOW;
		s.store = new BirdhouseService.ProfileStore()
		{
			@Override
			public String get(String key)
			{
				return profiles.computeIfAbsent(profile, p -> new HashMap<>()).get(key);
			}

			@Override
			public void set(String key, String json)
			{
				profiles.computeIfAbsent(profile, p -> new HashMap<>()).put(key, json);
			}

			@Override
			public void unset(String key)
			{
				profiles.computeIfAbsent(profile, p -> new HashMap<>()).remove(key);
			}
		};
		s.load();
		return s;
	}

	private void switchTo(BirdhouseService s, String p)
	{
		profile = p;
		s.onRuneScapeProfileChanged(null);
	}

	private static BirdhouseTraps full()
	{
		Map<BirdhouseSpace, Integer> built = new EnumMap<>(BirdhouseSpace.class);
		Map<BirdhouseSpace, Integer> seeded = new EnumMap<>(BirdhouseSpace.class);
		for (BirdhouseSpace sp : BirdhouseSpace.values())
		{
			built.put(sp, 25);
			seeded.put(sp, 27);
		}
		return BirdhouseTraps.empty().withReading(built, NOW - 3 * 3_600_000L).withReading(seeded, NOW - 2 * 3_600_000L);
	}

	@Test
	public void selection_run_timers_andReminderMark_stayWithTheirProfile()
	{
		// Profile A: timers read and already notified, two houses chosen with redwood, a run started.
		profiles.computeIfAbsent("A", p -> new HashMap<>()).put(BirdhouseService.TRAPS_KEY, new Gson().toJson(full().withNotified(NOW)));
		BirdhouseService s = service();
		assertEquals(4, s.traps().summary(NOW).ready);
		assertFalse("A was already notified for this filling", s.traps().shouldNotify(NOW));
		s.choose(BirdhouseChoice.empty().withIncluded(BirdhouseSpace.MEADOW_NORTH, false).withIncluded(BirdhouseSpace.MEADOW_SOUTH, false)
			.withTier(BirdhouseTier.REDWOOD));
		assertTrue(s.start(s.choice().spaces(), s.choice().getTier()));
		assertNotNull(s.run());

		// Profile B: nothing of A's.
		switchTo(s, "B");
		assertEquals(4, s.traps().summary(NOW).unseen);
		assertNull("A's run is not B's", s.run());
		assertEquals(EnumSet.allOf(BirdhouseSpace.class), s.choice().spaces());
		assertNull("no tier chosen for B (never picked for the player)", s.choice().getTier());
		assertFalse("tierless start refused while a house must be built", s.start(EnumSet.allOf(BirdhouseSpace.class), null));
		s.choose(BirdhouseChoice.empty().withTier(BirdhouseTier.OAK));

		// Back to A, and a restart (a new service on the same saved state): all of A's as left.
		switchTo(s, "A");
		assertEquals(BirdhouseTier.REDWOOD, s.choice().getTier());
		assertEquals(EnumSet.of(BirdhouseSpace.VALLEY_NORTH, BirdhouseSpace.VALLEY_SOUTH), s.choice().spaces());
		assertNotNull(s.run());
		assertEquals(BirdhouseTier.REDWOOD, s.run().getTier());
		assertFalse(s.traps().shouldNotify(NOW));
		BirdhouseService restarted = service();
		assertEquals(BirdhouseTier.REDWOOD, restarted.run().getTier());
		assertEquals(4, restarted.traps().summary(NOW).ready);
		profile = "B";
		restarted.load();
		assertEquals(BirdhouseTier.OAK, restarted.choice().getTier());
		assertNull(restarted.run());
	}
}
