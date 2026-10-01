package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Guardians of the Rift current-game presentation values: never guessed, never out of range. */
public class GotrGameStateTest
{
	@Test
	public void powerPercent_roundsDown_clamps_andNeedsAPositiveMax()
	{
		assertEquals(Integer.valueOf(62), GotrGameState.powerPercent(62, 100));
		assertEquals(Integer.valueOf(33), GotrGameState.powerPercent(333, 1000));
		assertEquals(Integer.valueOf(0), GotrGameState.powerPercent(0, 1000));
		assertEquals(Integer.valueOf(100), GotrGameState.powerPercent(1000, 1000));
		assertEquals(Integer.valueOf(100), GotrGameState.powerPercent(1500, 1000));
		assertEquals(Integer.valueOf(0), GotrGameState.powerPercent(-5, 1000));
		assertNull(GotrGameState.powerPercent(5, 0));
		assertNull(GotrGameState.powerPercent(null, 1000));
		assertNull(GotrGameState.powerPercent(5, null));
	}

	@Test
	public void eachFieldIsIndependentlyOptional_andNothingKnownIsNull()
	{
		assertNull(GotrGameState.of(null, null, null));
		assertNull("negative energy is unknown, not a value", GotrGameState.of(null, -1, -1));
		GotrGameState powerOnly = GotrGameState.of(55, null, null);
		assertEquals(Integer.valueOf(55), powerOnly.getGuardianPowerPercent());
		assertNull(powerOnly.getTotalEnergy());
		GotrGameState energyOnly = GotrGameState.of(null, 280, 310);
		assertNull(energyOnly.getGuardianPowerPercent());
		assertEquals(Integer.valueOf(590), energyOnly.getTotalEnergy());
	}

	@Test
	public void total_onlyWhenBothEnergiesAreKnown()
	{
		assertNull(GotrGameState.of(10, 280, null).getTotalEnergy());
		assertEquals(Integer.valueOf(0), GotrGameState.of(0, 0, 0).getTotalEnergy());
	}

	@Test
	public void equality_drivesChangeOnlyRefresh()
	{
		assertEquals(GotrGameState.of(62, 280, 310), GotrGameState.of(62, 280, 310));
		assertNotEquals(GotrGameState.of(62, 280, 310), GotrGameState.of(63, 280, 310));
	}
}
