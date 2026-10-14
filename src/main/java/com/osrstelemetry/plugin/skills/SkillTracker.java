package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What the shared {@link SkillCompanionCollector} needs from a skill's
 * tracker (the per-skill brain that turns native evidence into causal
 * production): its inputs in tick order, and its live outputs. Pure; client
 * thread only. Mining and Fishing implement it.
 */
public interface SkillTracker
{
	/** A proven deposit of this session's production (the banked rate). */
	interface Deposit
	{
		int getItemId();

		long getQuantity();
	}

	// ---- inputs ----

	/** The skill's own XP rose by {@code delta} on {@code tick}. */
	void onSkillXp(int tick, long delta, long nowMillis);

	/** A game / spam / mesbox line (tags removed). */
	void onChat(int tick, long nowMillis, String message);

	void onInventory(int tick, long nowMillis, Map<Integer, Long> items, int slotsUsed);

	void onBank(int tick, long nowMillis, Map<Integer, Long> items);

	void setBankOpen(boolean open);

	void setDepositBoxOpen(boolean open);

	void onTick(int tick, long nowMillis, int regionId);

	boolean hasInventoryBaseline();

	/** Settles everything observed so far (before a logout / hop send). */
	void settleAll();

	void onLogout();

	void reset();

	// ---- outputs ----

	ProductionLedger getLedger();

	MethodTracker getMethods();

	String getMethodKey();

	/** Bumped whenever something the player can see changed. */
	long getRevision();

	List<? extends Deposit> drainDeposits();

	SkillCompanionView.TripView trip(long nowMillis);

	List<SkillCompanionView.ContextLine> context(long nowMillis);

	/** CYCLES (Agility laps), or null for skills without cycles (Mining, Fishing). */
	default SkillCycleTracker getCycles()
	{
		return null;
	}

	/** The current cycle as the panel shows it, or null. */
	default SkillCompanionView.CycleView cycle(long nowMillis)
	{
		return null;
	}

	/** SUPPLIES for the current method, or null when it uses none. */
	default SkillCompanionView.SupplyView supply(long nowMillis)
	{
		return null;
	}

	/**
	 * STRONG, method-specific evidence the skill's own native signals gave
	 * since the last call (Fishing: a catch line naming the fish, or a fishing
	 * option clicked on a recognised spot) -- the method key and when; null
	 * when none. Drained (cleared) by the call. Evidence only: never a
	 * success, never production.
	 */
	default MethodEvidence drainMethodEvidence()
	{
		return null;
	}

	// ---- ADDED (Skills Phase 4) -- all optional, none for Mining / Fishing / Agility ----------

	/** Another skill's XP rose on {@code tick} (it may be part of this skill's activity). No-op by default. */
	default void onOtherSkillXp(String skillName, int tick, long delta, long nowMillis)
	{
	}

	/**
	 * Contextual occurrences counted since the last call (contextual events
	 * the player took part in, per kind), drained (cleared) by the
	 * call; empty by default.
	 */
	default java.util.Map<String, Long> drainOccurrences()
	{
		return java.util.Collections.emptyMap();
	}

	/** Occurrences counted but not drained yet (read-only). */
	default java.util.Map<String, Long> getPendingOccurrences()
	{
		return java.util.Collections.emptyMap();
	}

	/** Occurrences pending for a session that never came are dropped with the rest. */
	default void discardPendingOccurrences()
	{
	}

	/** The CURRENT FOCUS (the one resource node being worked), or null. */
	default SkillCompanionView.FocusView focus(long nowMillis)
	{
		return null;
	}

	/** A CONTEXTUAL EVENT running right now, or null. */
	default SkillCompanionView.EventView event(long nowMillis)
	{
		return null;
	}

	/**
	 * Proofs, since the last call, that another skill's XP right now belongs
	 * to this skill's activity (other skill name -> when), drained by the
	 * call; empty by default. Evidence only.
	 */
	default java.util.Map<String, Long> drainContextEvidence()
	{
		return java.util.Collections.emptyMap();
	}

	/** See {@link #drainMethodEvidence()}. */
	final class MethodEvidence
	{
		private final String methodKey;
		private final long atMillis;

		public MethodEvidence(String methodKey, long atMillis)
		{
			this.methodKey = methodKey;
			this.atMillis = atMillis;
		}

		public String getMethodKey()
		{
			return methodKey;
		}

		public long getAtMillis()
		{
			return atMillis;
		}
	}

	/** No context by default. */
	static List<SkillCompanionView.ContextLine> none()
	{
		return Collections.emptyList();
	}
}
