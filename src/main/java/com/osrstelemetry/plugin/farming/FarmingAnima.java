package com.osrstelemetry.plugin.farming;

import java.util.Map;

/**
 * The anima plant at the Farming Guild (canonical §28.8). The type and stage
 * come only from the decoded patch; nothing is chosen in Spyglass.
 *
 * <p>Mechanics (OSRS Wiki "Special patches", Anima, checked 2026-10-21): the
 * three plants (Kronos, Iasor, Attas) plant at Farming 76; each grows for
 * 8 × 640 minutes (85.3 hours) from a seedling into a withering state, which is
 * "one stage away from death"; once it dies it must be replanted. The effects
 * are active while the plant is in the patch. The wiki gives no numbers for
 * the effects: Kronos gives farming patches a chance to skip a growth stage,
 * Iasor lowers the chance of disease, Attas increases yield. The decoder has
 * nine growing values per plant (stages 0-8), so stage 8 is the withering
 * state; a dead plant reads as an empty or weedy patch.
 *
 * <p>How the effects are applied: Kronos never makes a timer earlier (a
 * skipped stage is simply observed, and readiness follows the stage seen);
 * Iasor and Attas change nothing that Spyglass predicts (disease is only ever
 * observed, and harvest yields are not predicted).
 *
 * <p>Time until withering is shown only when it is known exactly: the planting
 * was observed (85.3 hours from then) or a stage change was (eight stages from
 * stage 0). Otherwise only the observed state is shown. Past the latest time
 * the plant can still be alive with no newer reading, its state is unknown
 * and nothing is shown.
 */
public final class FarmingAnima
{
	public static final String PATCH_ID = "anima.farming_guild";
	static final long STAGE_MILLIS = 640L * 60_000L;
	static final int WITHERING_STAGE = 8;

	public enum State
	{
		/** Never seen, the reading is stale, or it may have died since. */
		UNKNOWN,
		/** Seen empty or weeds. */
		NONE,
		/** Growing (stages 0-7). */
		ACTIVE,
		/** The last living stage (8): still in effect, dies one stage later. */
		WITHERING
	}

	private final State state;
	private final FarmingProduce produce;
	/** When it reaches the withering stage, when exactly known; null otherwise. */
	private final Long withersAt;

	private FarmingAnima(State state, FarmingProduce produce, Long withersAt)
	{
		this.state = state;
		this.produce = produce;
		this.withersAt = withersAt;
	}

	public static FarmingAnima of(Map<String, PatchKnowledge> knowledge, FarmingPatchCatalog catalog, long now)
	{
		PatchKnowledge k = knowledge == null ? null : knowledge.get(PATCH_ID);
		FarmingPatchDef def = catalog.patch(PATCH_ID);
		if (k == null || def == null || k.getLastSeenAt() <= 0 || k.isDecodeStale())
		{
			return new FarmingAnima(State.UNKNOWN, null, null);
		}
		PatchReading r = FarmingDecoder.decode(def.getType(), k.getLastRaw());
		if (r.getCondition() != PatchCondition.GROWING || r.getProduce() == null || r.getProduce().getPatchType() != FarmingPatchType.ANIMA)
		{
			return new FarmingAnima(r.getCondition() == PatchCondition.UNKNOWN ? State.UNKNOWN : State.NONE, null, null);
		}
		int stage = Math.max(0, r.getStage());
		CropCycle c = k.getCycle();
		// The latest the current stage can have begun: when it was first seen.
		long stageStart = c != null && c.getLastStage() == stage && c.getStageFirstSeenAt() > 0 ? c.getStageFirstSeenAt() : k.getLastSeenAt();
		long latestDeath = stageStart + (WITHERING_STAGE + 1 - stage) * STAGE_MILLIS;
		if (now >= latestDeath)
		{
			return new FarmingAnima(State.UNKNOWN, r.getProduce(), null); // it may have died since it was last seen
		}
		Long withers = null;
		if (c != null && c.getPlantedAt() != null && c.getProduce() == r.getProduce())
		{
			withers = c.getPlantedAt() + WITHERING_STAGE * STAGE_MILLIS;
		}
		else if (c != null && c.getLastStage() == stage && c.isStageTransitionObserved())
		{
			withers = c.getStageFirstSeenAt() + (WITHERING_STAGE - stage) * STAGE_MILLIS;
		}
		if (stage >= WITHERING_STAGE || withers != null && now >= withers)
		{
			return new FarmingAnima(State.WITHERING, r.getProduce(), null);
		}
		return new FarmingAnima(State.ACTIVE, r.getProduce(), withers);
	}

	public State getState()
	{
		return state;
	}

	/** Attas, Iasor or Kronos as decoded; null when none is seen. */
	public FarmingProduce getProduce()
	{
		return produce;
	}

	/** When it reaches the withering stage, when known exactly; null otherwise. */
	public Long getWithersAt()
	{
		return withersAt;
	}

	/** Kronos is in the patch: crops may be ready sooner than their timers say (never later). */
	public boolean isKronosActive()
	{
		return (state == State.ACTIVE || state == State.WITHERING) && produce == FarmingProduce.KRONOS;
	}

	/** Compact overview status: "Kronos · withers in ~2d 3h", "Kronos · growing", "Attas · withering", "No anima plant"; null when unknown. */
	public String text(long now)
	{
		switch (state)
		{
			case ACTIVE:
				return produce.getDisplayName() + " · " + (withersAt != null ? "withers in " + FarmingText.remaining(withersAt - now) : "growing");
			case WITHERING:
				return produce.getDisplayName() + " · withering";
			case NONE:
				return "No anima plant";
			default:
				return null;
		}
	}

	/** What the plant does, as the chance it is ("crops may be ready sooner"); empty when none is in the patch. */
	public String effect()
	{
		return state == State.ACTIVE || state == State.WITHERING ? effect(produce) : "";
	}

	static String effect(FarmingProduce p)
	{
		switch (p)
		{
			case KRONOS:
				return "crops may be ready sooner";
			case IASOR:
				return "less disease";
			case ATTAS:
				return "bigger harvests likely";
			default:
				return "";
		}
	}
}
