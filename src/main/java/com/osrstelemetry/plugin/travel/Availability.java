package com.osrstelemetry.plugin.travel;

/**
 * Transport availability (canonical §12.2), in rank order for the
 * resource-backed states. The four distinctions the architecture requires:
 * <ul>
 *   <li>usable with what is carried / worn: {@link #READY}, {@link #CHARGES_UNKNOWN};</li>
 *   <li>known owned, needs retrieval or preparation: {@link #IN_BANK},
 *   {@link #ELSEWHERE}, {@link #DEPLETED} (owned, needs recharging / refilling);</li>
 *   <li>known blocked by a specific evidenced requirement: {@link #REQUIREMENTS_UNMET};</li>
 *   <li>Spyglass lacks the evidence: {@link #UNKNOWN}. Never "unavailable", never zero.</li>
 * </ul>
 * {@code DEPLETED} is a documented Phase F addition to the canonical table: a
 * carried uncharged variant is neither ready nor unknown.
 */
public enum Availability
{
	/** Usable now: requirements met, item carried / worn with charges, or spell castable with carried runes. */
	READY,
	/** Owned and carried, requirements met, but remaining charges or today's uses cannot be observed. */
	CHARGES_UNKNOWN,
	/** Requirements met; the item (or runes) seen in the bank. Needs a bank trip. */
	IN_BANK,
	/** Requirements met; seen in another storage (group storage). */
	ELSEWHERE,
	/** Only an uncharged / empty variant found; needs recharging or refilling. */
	DEPLETED,
	/** A specific requirement is known not to be met. */
	REQUIREMENTS_UNMET,
	/** Ownership or a requirement cannot be established from Spyglass's evidence. */
	UNKNOWN;

	/** Resource ranking: lower is better. */
	int resourceRank()
	{
		switch (this)
		{
			case READY:
				return 0;
			case CHARGES_UNKNOWN:
				return 1;
			case IN_BANK:
				return 2;
			case ELSEWHERE:
				return 3;
			case DEPLETED:
				return 4;
			default:
				return 5;
		}
	}

	static Availability better(Availability a, Availability b)
	{
		return a.resourceRank() <= b.resourceRank() ? a : b;
	}

	static Availability worse(Availability a, Availability b)
	{
		return a.resourceRank() >= b.resourceRank() ? a : b;
	}

	/** Usable during guidance without a preparation step (canonical §12.2). */
	public boolean usableNow()
	{
		return this == READY || this == CHARGES_UNKNOWN;
	}
}
