package com.osrstelemetry.plugin.account;

import static com.osrstelemetry.plugin.account.AccountFixtures.items;
import static com.osrstelemetry.plugin.account.AccountFixtures.maxed;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Live finding 2026-10-21: the account snapshot was a new object every tick, so everything memoised on it (the whole travel
 * catalogue's evaluation and the run's plan) was redone every tick during preparation. The adapter now keeps the same
 * snapshot while its facts are the same; only the capture time may differ.
 */
public class AccountSnapshotIdentityTest
{
	@Test
	public void sameFacts_sameSnapshot_anyChangeIsANewOne()
	{
		AccountCapability a = maxed().inventory(items(8011, 3)).capturedAt(1_000L).build();
		AccountCapability b = maxed().inventory(items(8011, 3)).capturedAt(2_000L).build();
		assertTrue("only the capture time differs", a.sameContent(b));
		assertFalse("a withdrawal", a.sameContent(maxed().inventory(items(8011, 4)).build()));
		assertFalse("equipment", a.sameContent(maxed().inventory(items(8011, 3)).worn(items(9781, 1)).build()));
		assertFalse("an unlock read", a.sameContent(maxed().inventory(items(8011, 3)).varbit(18355, 1).build()));
		assertFalse("a level", a.sameContent(maxed().inventory(items(8011, 3)).level(net.runelite.api.Skill.FARMING, 98).build()));
		StorageEvidence bank1 = new StorageEvidence(StorageEvidence.Kind.BANK, items(995, 10), 5L);
		StorageEvidence bank2 = new StorageEvidence(StorageEvidence.Kind.BANK, items(995, 10), 6L);
		assertFalse("a new bank observation", maxed().bank(bank1).build().sameContent(maxed().bank(bank2).build()));
		assertTrue(maxed().bank(bank1).build().sameContent(maxed().bank(bank1).build()));
	}
}
