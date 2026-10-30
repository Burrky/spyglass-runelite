package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.*;
import static org.junit.Assert.assertEquals;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;
import org.junit.Test;

/** Readiness (has the metadata settled?) is separate from classification (which profile is this?). */
public class ProfileGateTest
{
	private final ProfileGate gate = new ProfileGate();

	@Test
	public void standardClassification_isNotTrustedUntilMetadataIsStable()
	{
		assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(1, FALADOR).build()));
		assertEquals(ProfileGate.Status.STANDARD, gate.evaluate(tick(2, FALADOR).build()));
	}

	@Test
	public void changingWorldMetadata_keepsItNotReady()
	{
		gate.evaluate(tick(1, FALADOR).world(330).build());
		assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(2, FALADOR).world(331).build()));
		assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(3, FALADOR).world(331).worldTypes(EnumSet.of(WorldType.MEMBERS, WorldType.PVP)).build()));
		assertEquals(ProfileGate.Status.STANDARD, gate.evaluate(tick(4, FALADOR).world(331).worldTypes(EnumSet.of(WorldType.MEMBERS, WorldType.PVP)).build()));
	}

	@Test
	public void missingAccountWorldOrClassification_isNotReady()
	{
		for (int i = 0; i < 3; i++)
		{
			assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(i, FALADOR).accountHash(-1L).build()));
			assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(i, FALADOR).world(0).build()));
			assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(i, FALADOR).profileType(null).build()));
		}
	}

	@Test
	public void emptyWorldTypeSet_isAnOrdinaryStandardWorld()
	{
		gate.evaluate(tick(1, FALADOR).worldTypes(EnumSet.noneOf(WorldType.class)).build());
		assertEquals(ProfileGate.Status.STANDARD, gate.evaluate(tick(2, FALADOR).worldTypes(EnumSet.noneOf(WorldType.class)).build()));
	}

	@Test
	public void nonStandardClassification_isUnsupported_onceReady()
	{
		gate.evaluate(tick(1, FALADOR).profileType(RuneScapeProfileType.BETA).build());
		assertEquals(ProfileGate.Status.UNSUPPORTED_PROFILE, gate.evaluate(tick(2, FALADOR).profileType(RuneScapeProfileType.BETA).build()));
	}

	@Test
	public void freshStartOrTournamentFlags_areNotASilentPolicy()
	{
		// RuneLite classifies these as STANDARD; whether Spyglass should exclude them is an open,
		// documented question, so the gate follows RuneLite's classification for now.
		gate.evaluate(tick(1, FALADOR).worldTypes(EnumSet.of(WorldType.FRESH_START_WORLD)).build());
		assertEquals(ProfileGate.Status.STANDARD, gate.evaluate(tick(2, FALADOR).worldTypes(EnumSet.of(WorldType.FRESH_START_WORLD)).build()));
	}

	@Test
	public void loggingOut_resetsReadiness()
	{
		gate.evaluate(tick(1, FALADOR).build());
		gate.evaluate(tick(2, FALADOR).build());
		assertEquals(ProfileGate.Status.NOT_LOGGED_IN, gate.evaluate(tick(3, FALADOR).loggedIn(false).build()));
		assertEquals(ProfileGate.Status.NOT_READY, gate.evaluate(tick(4, FALADOR).build()));
	}
}
