package com.osrstelemetry.plugin.prep;

/**
 * D-F2's "natural automatic transition": the preparation stage closes by
 * itself when the player clearly sets off, i.e. readiness is CONFIRMED and the
 * bank, open while it was so, closes. Only confirmed readiness counts: with
 * unknown charges, unconfirmed access or missing evidence, closing the bank
 * closes nothing (the player decides, with Begin, or the first observed patch
 * progress closes it). Opening and closing the bank without being ready, or
 * being ready without having had the bank open, never closes it.
 */
public final class SetOffDetector
{
	private boolean readyWithBankOpen;

	/** Call once a tick; true when the stage should close now. */
	public boolean onTick(Readiness readiness, boolean bankOpen)
	{
		boolean confirmed = readiness == Readiness.CONFIRMED;
		if (confirmed && bankOpen)
		{
			readyWithBankOpen = true;
			return false;
		}
		boolean setOff = readyWithBankOpen && confirmed && !bankOpen;
		readyWithBankOpen = false;
		return setOff;
	}

	public void reset()
	{
		readyWithBankOpen = false;
	}
}
