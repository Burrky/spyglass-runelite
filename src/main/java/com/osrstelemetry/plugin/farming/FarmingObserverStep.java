package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.List;

/** What one tick produced. Immutable. */
public final class FarmingObserverStep
{
	public enum Status
	{
		NOT_LOGGED_IN,
		PROFILE_NOT_READY,
		UNSUPPORTED_PROFILE,
		/**
		 * Classified STANDARD, but on a world type whose persistence is not yet
		 * established ({@link FarmingObserver#SUSPENDED_WORLD_TYPES}). A temporary
		 * integration guard, not a classification fact: nothing is captured.
		 */
		WORLD_TYPE_SUSPENDED,
		WELCOME_SCREEN,
		MODAL_OPEN,
		INSTANCED,
		NO_LOCATION,
		NOT_AT_A_PATCH,
		/** Eligible, but the transmission context has not been stable long enough to read. */
		SETTLING,
		/** Settled: readings taken. */
		CAPTURED
	}

	private final Status status;
	private final FarmingOwner owner;
	private final boolean ownerChanged;
	private final List<PatchObservation> observations;
	private final List<String> unchangedPatchIds;
	private final List<CalibrationSample> calibrationSamples;
	private final long observedAt;

	FarmingObserverStep(Status status, FarmingOwner owner, boolean ownerChanged, List<PatchObservation> observations,
		List<String> unchangedPatchIds, List<CalibrationSample> calibrationSamples, long observedAt)
	{
		this.status = status;
		this.owner = owner;
		this.ownerChanged = ownerChanged;
		this.observations = Collections.unmodifiableList(observations);
		this.unchangedPatchIds = Collections.unmodifiableList(unchangedPatchIds);
		this.calibrationSamples = Collections.unmodifiableList(calibrationSamples);
		this.observedAt = observedAt;
	}

	public Status getStatus()
	{
		return status;
	}

	/** The account/profile this step's data belongs to; null when there is none yet. */
	public FarmingOwner getOwner()
	{
		return owner;
	}

	/** The owner differs from the previous step's: all earlier in-memory evidence was discarded. */
	public boolean isOwnerChanged()
	{
		return ownerChanged;
	}

	public List<PatchObservation> getObservations()
	{
		return observations;
	}

	/** Patches read on a settled tick whose value had not changed: freshness only, never logged. */
	public List<String> getUnchangedPatchIds()
	{
		return unchangedPatchIds;
	}

	public List<CalibrationSample> getCalibrationSamples()
	{
		return calibrationSamples;
	}

	public long getObservedAt()
	{
		return observedAt;
	}
}
