package com.osrstelemetry.plugin.account;

import static org.junit.Assert.assertEquals;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import net.runelite.client.config.RuneScapeProfileType;
import org.junit.Test;

public class AccountProfileGateTest
{
	private static final EnumSet<WorldType> MEMBERS = EnumSet.of(WorldType.MEMBERS);

	@Test
	public void standardAfterTwoStableTicks_only()
	{
		AccountProfileGate g = new AccountProfileGate();
		assertEquals(AccountProfileGate.Status.NOT_READY, g.evaluate(true, 1L, 330, MEMBERS, RuneScapeProfileType.STANDARD));
		assertEquals(AccountProfileGate.Status.STANDARD, g.evaluate(true, 1L, 330, MEMBERS, RuneScapeProfileType.STANDARD));
		assertEquals("a hop restarts the settle", AccountProfileGate.Status.NOT_READY, g.evaluate(true, 1L, 331, MEMBERS, RuneScapeProfileType.STANDARD));
	}

	@Test
	public void incompleteMetadata_logout_nonStandard_andSuspendedWorlds_neverStandard()
	{
		AccountProfileGate g = new AccountProfileGate();
		for (int i = 0; i < 3; i++)
		{
			assertEquals(AccountProfileGate.Status.NOT_READY, g.evaluate(true, -1L, 330, MEMBERS, RuneScapeProfileType.STANDARD));
			assertEquals(AccountProfileGate.Status.NOT_READY, g.evaluate(true, 1L, 0, MEMBERS, RuneScapeProfileType.STANDARD));
			assertEquals(AccountProfileGate.Status.NOT_READY, g.evaluate(true, 1L, 330, MEMBERS, null));
			assertEquals(AccountProfileGate.Status.NOT_LOGGED_IN, g.evaluate(false, 1L, 330, MEMBERS, RuneScapeProfileType.STANDARD));
		}
		AccountProfileGate seasonal = new AccountProfileGate();
		seasonal.evaluate(true, 1L, 330, EnumSet.of(WorldType.SEASONAL), RuneScapeProfileType.DEMONIC_PACTS_LEAGUE);
		assertEquals(AccountProfileGate.Status.NOT_STANDARD, seasonal.evaluate(true, 1L, 330, EnumSet.of(WorldType.SEASONAL), RuneScapeProfileType.DEMONIC_PACTS_LEAGUE));
		AccountProfileGate tournament = new AccountProfileGate();
		tournament.evaluate(true, 1L, 330, EnumSet.of(WorldType.TOURNAMENT_WORLD), RuneScapeProfileType.STANDARD);
		assertEquals(AccountProfileGate.Status.NOT_STANDARD, tournament.evaluate(true, 1L, 330, EnumSet.of(WorldType.TOURNAMENT_WORLD), RuneScapeProfileType.STANDARD));
	}
}
