package com.osrstelemetry.plugin.activity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The current Guardians of the Rift game as the game's own HUD reports it.
 * Presentation only -- never an event, never lifecycle evidence. Every field
 * is nullable and a null field is simply omitted (never guessed).
 *
 * SOURCES (RuneLite 1.13.1, all native -- no other plugin):
 * <ul>
 * <li>guardianPowerPercent: the GotR HUD's own update clientscript (5980),
 * whose arguments carry the Great Guardian's current and maximum power;
 * read via runelite-api's ScriptPreFired. Rounded down, clamped 0-100.</li>
 * <li>elementalEnergy / catalyticEnergy: varbits
 * GOTR_ELEMENTAL_EARNED_THIS_GAME (13686) / GOTR_CATALYTIC_EARNED_THIS_GAME
 * (13685) -- this player's energy earned in the current game.</li>
 * <li>activeElemental / activeCatalytic: the two guardians whose rifts are
 * open right now -- the same HUD script's guardian-index arguments (see
 * {@link GotrAltar} for the live-verified mapping). Null outside a game.</li>
 * <li>elementalUsable / catalyticUsable: whether this player can enter that
 * altar (Runecraft level and quest, read from the game); null = unknown.</li>
 * </ul>
 */
public final class GotrGameState
{
	private final Integer guardianPowerPercent;
	private final Integer elementalEnergy;
	private final Integer catalyticEnergy;
	private final GotrAltar activeElemental;
	private final GotrAltar activeCatalytic;
	private final Boolean elementalUsable;
	private final Boolean catalyticUsable;
	/** Held portal talismans (altar -> usable, null = unknown), in guardian order. */
	private final Map<GotrAltar, Boolean> talismans;

	private GotrGameState(Integer guardianPowerPercent, Integer elementalEnergy, Integer catalyticEnergy,
		GotrAltar activeElemental, GotrAltar activeCatalytic, Boolean elementalUsable, Boolean catalyticUsable,
		Map<GotrAltar, Boolean> talismans)
	{
		this.talismans = talismans;
		this.guardianPowerPercent = guardianPowerPercent;
		this.elementalEnergy = elementalEnergy;
		this.catalyticEnergy = catalyticEnergy;
		this.activeElemental = activeElemental;
		this.activeCatalytic = activeCatalytic;
		this.elementalUsable = activeElemental == null ? null : elementalUsable;
		this.catalyticUsable = activeCatalytic == null ? null : catalyticUsable;
	}

	/** Null when nothing at all is known. Negative energy values are treated as unknown. */
	public static GotrGameState of(Integer guardianPowerPercent, Integer elementalEnergy, Integer catalyticEnergy)
	{
		return of(guardianPowerPercent, elementalEnergy, catalyticEnergy, null, null, null, null);
	}

	/** As {@link #of(Integer, Integer, Integer)}, plus the active rifts and whether the player can use them. */
	public static GotrGameState of(Integer guardianPowerPercent, Integer elementalEnergy, Integer catalyticEnergy,
		GotrAltar activeElemental, GotrAltar activeCatalytic, Boolean elementalUsable, Boolean catalyticUsable)
	{
		Integer e = elementalEnergy == null || elementalEnergy < 0 ? null : elementalEnergy;
		Integer c = catalyticEnergy == null || catalyticEnergy < 0 ? null : catalyticEnergy;
		GotrAltar ae = activeElemental == null || activeElemental.getType() != GotrAltar.Type.ELEMENTAL ? null : activeElemental;
		GotrAltar ac = activeCatalytic == null || activeCatalytic.getType() != GotrAltar.Type.CATALYTIC ? null : activeCatalytic;
		if (guardianPowerPercent == null && e == null && c == null && ae == null && ac == null)
		{
			return null;
		}
		return new GotrGameState(guardianPowerPercent, e, c, ae, ac, elementalUsable, catalyticUsable, Collections.emptyMap());
	}

	/** As the 7-argument form, plus the portal talismans held (altar -> usable, null = unknown). */
	public static GotrGameState of(Integer guardianPowerPercent, Integer elementalEnergy, Integer catalyticEnergy,
		GotrAltar activeElemental, GotrAltar activeCatalytic, Boolean elementalUsable, Boolean catalyticUsable,
		Map<GotrAltar, Boolean> talismans)
	{
		GotrGameState base = of(guardianPowerPercent, elementalEnergy, catalyticEnergy, activeElemental, activeCatalytic,
			elementalUsable, catalyticUsable);
		if (base == null || talismans == null || talismans.isEmpty())
		{
			return base;
		}
		Map<GotrAltar, Boolean> held = new EnumMap<>(GotrAltar.class);
		for (Map.Entry<GotrAltar, Boolean> t : talismans.entrySet())
		{
			if (t.getKey() != null)
			{
				held.put(t.getKey(), t.getValue());
			}
		}
		return new GotrGameState(base.guardianPowerPercent, base.elementalEnergy, base.catalyticEnergy, base.activeElemental,
			base.activeCatalytic, base.elementalUsable, base.catalyticUsable, Collections.unmodifiableMap(held));
	}

	/**
	 * Every altar the player can enter right now: the open rifts first
	 * (elemental, catalytic), then each held portal talisman's altar that is
	 * not already open, in guardian order. Empty when nothing is known.
	 */
	public List<GotrCandidate> getCandidates()
	{
		List<GotrCandidate> out = new ArrayList<>();
		if (activeElemental != null)
		{
			out.add(new GotrCandidate(activeElemental, GotrCandidate.Access.OPEN, elementalUsable));
		}
		if (activeCatalytic != null)
		{
			out.add(new GotrCandidate(activeCatalytic, GotrCandidate.Access.OPEN, catalyticUsable));
		}
		for (Map.Entry<GotrAltar, Boolean> t : talismans.entrySet())
		{
			if (t.getKey() != activeElemental && t.getKey() != activeCatalytic)
			{
				out.add(new GotrCandidate(t.getKey(), GotrCandidate.Access.TALISMAN, t.getValue()));
			}
		}
		return out;
	}

	/** Altars of the portal talismans held (any order-stable view); empty when none. */
	public Map<GotrAltar, Boolean> getTalismans()
	{
		return talismans;
	}

	/** floor(current * 100 / max), clamped to 0..100; null when max is unknown or not positive. */
	public static Integer powerPercent(Integer current, Integer max)
	{
		if (current == null || max == null || max <= 0)
		{
			return null;
		}
		long pct = (long) Math.max(0, current) * 100L / max;
		return (int) Math.min(100L, pct);
	}

	public Integer getGuardianPowerPercent()
	{
		return guardianPowerPercent;
	}

	public Integer getElementalEnergy()
	{
		return elementalEnergy;
	}

	public Integer getCatalyticEnergy()
	{
		return catalyticEnergy;
	}

	/** The open elemental rift's altar, or null. */
	public GotrAltar getActiveElemental()
	{
		return activeElemental;
	}

	/** The open catalytic rift's altar, or null. */
	public GotrAltar getActiveCatalytic()
	{
		return activeCatalytic;
	}

	/** Whether the player can enter the open elemental altar; null when unknown. */
	public Boolean getElementalUsable()
	{
		return elementalUsable;
	}

	/** Whether the player can enter the open catalytic altar; null when unknown. */
	public Boolean getCatalyticUsable()
	{
		return catalyticUsable;
	}

	/** Elemental + catalytic energy (the game's own "total energy" notion), only when both are known. */
	public Integer getTotalEnergy()
	{
		return elementalEnergy == null || catalyticEnergy == null ? null : elementalEnergy + catalyticEnergy;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof GotrGameState))
		{
			return false;
		}
		GotrGameState that = (GotrGameState) o;
		return Objects.equals(guardianPowerPercent, that.guardianPowerPercent)
			&& Objects.equals(elementalEnergy, that.elementalEnergy)
			&& Objects.equals(catalyticEnergy, that.catalyticEnergy)
			&& activeElemental == that.activeElemental
			&& activeCatalytic == that.activeCatalytic
			&& Objects.equals(elementalUsable, that.elementalUsable)
			&& Objects.equals(catalyticUsable, that.catalyticUsable)
			&& talismans.equals(that.talismans);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(guardianPowerPercent, elementalEnergy, catalyticEnergy, activeElemental, activeCatalytic, elementalUsable, catalyticUsable, talismans);
	}

	@Override
	public String toString()
	{
		return "GotrGameState{power=" + guardianPowerPercent + "%, elemental=" + elementalEnergy + ", catalytic=" + catalyticEnergy
			+ ", rifts=" + activeElemental + "/" + activeCatalytic + ", talismans=" + talismans.keySet() + "}";
	}
}
