package com.osrstelemetry.plugin.farming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.WorldType;

/**
 * The pure Farming observation state machine. Fed one immutable
 * {@link FarmingTickInput} per game tick; returns what that tick proved. It
 * never reads the client, writes files, renders or subscribes to anything.
 * Not thread-safe: one owner (the client thread, later) drives it.
 *
 * <h3>Settling (when a reading counts)</h3>
 * RuneLite's TimeTrackingPlugin reads with the PREVIOUS tick's location and
 * only when the previous and current tick share a map region; FarmingTracker
 * then gives a newly loaded region one plain scan before trusting changes, and
 * ignores growth-tick inference until more than one tick after a modal closed.
 * A shared numeric region is necessary but not enough, because bounds and plane
 * change which patches the varbits describe inside one region (Falador / Port
 * Sarim in 12083, Catherby upstairs). So Spyglass requires the same map region
 * AND the same resolved {@link TransmissionContext} on {@link #SETTLE_TICKS}
 * consecutive eligible ticks (this one and the two before it) before it reads
 * anything. Crossing between an area's owning and alias regions therefore
 * re-settles too, exactly as RuneLite skips a region-crossing tick.
 *
 * <h3>Continuity (when a change is evidence)</h3>
 * Every non-settled tick breaks the sequence: logged out, profile not ready or
 * unsupported, welcome screen, modal open, instance, no location, no patch
 * here, a different region or transmission context, or a missing tick (loading, hop,
 * reconnect). A missing varbit breaks that patch alone. After a break the next
 * settled read of each patch is a BASELINE. A CHANGE exists only between two
 * settled reads on consecutive ticks of one sequence, so nothing that happened
 * during a gap, or while settling, can ever be presented as an observed
 * transition: settling finishing never makes an unseen change eligible.
 *
 * <h3>Eligibility before reads</h3>
 * {@link #varbitsToRead} answers, without changing any state, which transmit
 * varbits this tick's {@link #onTick} would use. It is empty unless the tick
 * will be settled, so a live adapter reads farming varbits only when they can
 * count, and never during a modal, the welcome screen, an instance, settling
 * or on an unsupported or not-yet-ready profile.
 *
 * <h3>Ownership</h3>
 * An account (or profile) change discards every in-memory reading; the next
 * step reports {@link FarmingObserverStep#isOwnerChanged()}.
 */
public final class FarmingObserver
{
	/** The reading tick plus the two before it must share an eligible, identical transmission context. */
	public static final int SETTLE_TICKS = 3;

	/**
	 * TEMPORARY INTEGRATION GUARD (Phase B wiring, 2026-10-15), not a
	 * classification fact: RuneLite classifies these worlds as STANDARD, but
	 * whether their Farming state belongs in standard persistence is unresolved
	 * (Phase B prep §4). Tournament progress does not transfer to normal worlds
	 * (OSRS Wiki, "Update:Tournament Worlds &amp; Tithe"); Fresh Start Worlds ran
	 * on separate servers with new accounts later moved to the main game (OSRS
	 * Wiki, "Fresh Start Worlds"). Capture is suspended there, with evidence
	 * cleared exactly as for an unsupported profile, until that is settled.
	 */
	public static final Set<WorldType> SUSPENDED_WORLD_TYPES =
		Collections.unmodifiableSet(EnumSet.of(WorldType.TOURNAMENT_WORLD, WorldType.FRESH_START_WORLD));

	private static final class LastRead
	{
		final int raw;
		final PatchReading reading;
		final int tick;
		final long at;
		final long sequence;

		LastRead(int raw, PatchReading reading, int tick, long at, long sequence)
		{
			this.raw = raw;
			this.reading = reading;
			this.tick = tick;
			this.at = at;
			this.sequence = sequence;
		}
	}

	private final FarmingPatchCatalog catalog;
	private final ProfileGate gate = new ProfileGate();
	private final Map<String, LastRead> lastReads = new HashMap<>();

	private FarmingOwner owner;
	private boolean hasLastTick;
	private int lastTick;
	private String settleContextKey;
	private int settleCount;
	private boolean lastTickSettled;
	private long sequence;

	public FarmingObserver(FarmingPatchCatalog catalog)
	{
		this.catalog = catalog;
	}

	public FarmingObserverStep onTick(FarmingTickInput in)
	{
		if (hasLastTick && in.getTick() != lastTick + 1)
		{
			// A tick we never saw (loading, hop, reconnect, plugin paused): no evidence spans it.
			breakContinuity();
		}
		hasLastTick = true;
		lastTick = in.getTick();
		long now = in.getEpochMillis();
		boolean ownerChanged = false;

		ProfileGate.Status gateStatus = gate.evaluate(in);
		switch (gateStatus)
		{
			case NOT_LOGGED_IN:
				breakContinuity();
				return idle(FarmingObserverStep.Status.NOT_LOGGED_IN, null, false, now);
			case NOT_READY:
				breakContinuity();
				return idle(FarmingObserverStep.Status.PROFILE_NOT_READY, null, false, now);
			case UNSUPPORTED_PROFILE:
				// A profile transition: nothing observed elsewhere may continue into it, or back.
				resetEvidence();
				return idle(FarmingObserverStep.Status.UNSUPPORTED_PROFILE, null, false, now);
			case STANDARD:
			default:
				if (isSuspendedWorld(in))
				{
					resetEvidence();
					return idle(FarmingObserverStep.Status.WORLD_TYPE_SUSPENDED, null, false, now);
				}
				FarmingOwner current = FarmingOwner.standard(in.getAccountHash());
				if (!current.equals(owner))
				{
					ownerChanged = owner != null;
					resetEvidence();
					owner = current;
				}
				break;
		}

		if (in.isWelcomeScreenVisible())
		{
			breakContinuity();
			return idle(FarmingObserverStep.Status.WELCOME_SCREEN, owner, ownerChanged, now);
		}
		if (in.isModalOpen())
		{
			// Farming varbits are not sent while a modal is open (RuneLite FarmingTracker).
			breakContinuity();
			return idle(FarmingObserverStep.Status.MODAL_OPEN, owner, ownerChanged, now);
		}
		if (in.isInstanced())
		{
			breakContinuity();
			return idle(FarmingObserverStep.Status.INSTANCED, owner, ownerChanged, now);
		}
		if (in.getLocation() == null)
		{
			breakContinuity();
			return idle(FarmingObserverStep.Status.NO_LOCATION, owner, ownerChanged, now);
		}
		TransmissionContext context = catalog.resolve(in.getLocation());
		if (context.isEmpty())
		{
			breakContinuity();
			return idle(FarmingObserverStep.Status.NOT_AT_A_PATCH, owner, ownerChanged, now);
		}

		// Region AND resolved patches: RuneLite reads only when the previous tick was in the same
		// map region; bounds and plane can change the transmitted patches inside one region.
		String settleKey = in.getLocation().getRegionId() + "|" + context.getKey();
		if (settleKey.equals(settleContextKey))
		{
			settleCount++;
		}
		else
		{
			breakContinuity();
			settleContextKey = settleKey;
			settleCount = 1;
		}
		if (settleCount < SETTLE_TICKS)
		{
			lastTickSettled = false;
			return idle(FarmingObserverStep.Status.SETTLING, owner, ownerChanged, now);
		}
		if (!lastTickSettled)
		{
			sequence++;
		}
		lastTickSettled = true;

		List<PatchObservation> observations = new ArrayList<>();
		List<String> unchanged = new ArrayList<>();
		List<CalibrationSample> samples = new ArrayList<>();
		int tick = in.getTick();
		for (FarmingPatchDef patch : context.getPatches())
		{
			Integer value = in.transmitValue(patch.getVarbit());
			if (value == null)
			{
				// Not read this tick: this patch's comparison chain is broken.
				lastReads.remove(patch.getPatchId());
				continue;
			}
			int raw = value;
			PatchReading reading = FarmingDecoder.decode(patch.getType(), raw);
			LastRead prev = lastReads.get(patch.getPatchId());
			boolean continuous = prev != null && prev.sequence == sequence && prev.tick == tick - 1;
			if (!continuous)
			{
				observations.add(new PatchObservation(PatchObservation.Kind.BASELINE, patch, reading, null, now, 0L, sequence));
			}
			else if (prev.raw != raw)
			{
				observations.add(new PatchObservation(PatchObservation.Kind.CHANGE, patch, reading, prev.reading, now, prev.at, sequence));
				if (CalibrationSample.isGrowthTick(prev.reading, reading))
				{
					samples.add(new CalibrationSample(patch.getPatchId(), prev.reading.getProduce(), prev.reading.getProduce().getTickMinutes(),
						prev.reading, reading, prev.at, now, sequence));
				}
			}
			else
			{
				unchanged.add(patch.getPatchId());
			}
			lastReads.put(patch.getPatchId(), new LastRead(raw, reading, tick, now, sequence));
		}
		return new FarmingObserverStep(FarmingObserverStep.Status.CAPTURED, owner, ownerChanged, observations, unchanged, samples, now);
	}

	/**
	 * The varbits {@link #onTick} will read for {@code in} (same decision, no
	 * state change); empty when this tick will not be settled. {@code in} need
	 * not carry any transmit values yet.
	 */
	public List<Integer> varbitsToRead(FarmingTickInput in)
	{
		boolean tickGap = hasLastTick && in.getTick() != lastTick + 1;
		if (gate.peek(in) != ProfileGate.Status.STANDARD || isSuspendedWorld(in)
			|| in.isWelcomeScreenVisible() || in.isModalOpen() || in.isInstanced() || in.getLocation() == null)
		{
			return Collections.emptyList();
		}
		boolean ownerResets = !FarmingOwner.standard(in.getAccountHash()).equals(owner);
		TransmissionContext context = catalog.resolve(in.getLocation());
		if (context.isEmpty())
		{
			return Collections.emptyList();
		}
		String settleKey = in.getLocation().getRegionId() + "|" + context.getKey();
		int count = !tickGap && !ownerResets && settleKey.equals(settleContextKey) ? settleCount + 1 : 1;
		if (count < SETTLE_TICKS)
		{
			return Collections.emptyList();
		}
		List<Integer> varbits = new ArrayList<>();
		for (FarmingPatchDef patch : context.getPatches())
		{
			if (!varbits.contains(patch.getVarbit()))
			{
				varbits.add(patch.getVarbit());
			}
		}
		return Collections.unmodifiableList(varbits);
	}

	/** True on a world type whose Farming persistence is not yet established ({@link #SUSPENDED_WORLD_TYPES}). */
	public static boolean isSuspendedWorld(FarmingTickInput in)
	{
		for (WorldType type : in.getWorldTypes())
		{
			if (SUSPENDED_WORLD_TYPES.contains(type))
			{
				return true;
			}
		}
		return false;
	}

	/** The account the observer is currently attributing to, or null. */
	public FarmingOwner getOwner()
	{
		return owner;
	}

	/**
	 * An explicit lifecycle interruption (logout, HOPPING, LOADING,
	 * CONNECTION_LOST), applied immediately rather than inferred from the next
	 * tick number. Discards all continuous-observation evidence: settling,
	 * profile-readiness and every previous read, so capture resumes only after
	 * settling again, and every patch's next reading is a fresh BASELINE that no
	 * CHANGE or calibration sample can span. Persisted crop knowledge, planting
	 * records and history are untouched (they live in {@link FarmingStore}); the
	 * owner is kept, so an account switch is still detected and reported on the
	 * next tick. Produces no observation. Safe to call repeatedly.
	 */
	public void interrupt()
	{
		gate.reset();
		resetEvidence();
	}

	private FarmingObserverStep idle(FarmingObserverStep.Status status, FarmingOwner stepOwner, boolean ownerChanged, long now)
	{
		return new FarmingObserverStep(status, stepOwner, ownerChanged, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), now);
	}

	private void breakContinuity()
	{
		settleContextKey = null;
		settleCount = 0;
		lastTickSettled = false;
	}

	private void resetEvidence()
	{
		breakContinuity();
		lastReads.clear();
	}
}
