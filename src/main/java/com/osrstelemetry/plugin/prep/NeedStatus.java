package com.osrstelemetry.plugin.prep;

/**
 * A need's status (canonical §8.3, reconciled with D-F3 in Phase H).
 * {@code UNAVAILABLE} from the original table is deliberately not used: no set
 * of observed storages proves the account owns nothing (POH, STASH, death
 * storage, the tool leprechaun and others are not observed), so absence is
 * reported as {@link #NOT_FOUND}: "not in the places Spyglass looked", with
 * when it looked.
 */
public enum NeedStatus
{
	/** Enough carried (inventory, or worn / rune pouch / rune providers where allowed). */
	CARRIED,
	/**
	 * Not carried, but a store the player can use where the need is used holds
	 * enough (the tool leprechaun beside the patches). Counts as covered;
	 * always shown as "stored", never as carried.
	 */
	REACHABLE,
	/** Not enough carried; the bank had enough when last seen. */
	IN_BANK,
	/** Not enough carried or banked; another observed storage had it (group storage, seed vault). */
	ELSEWHERE,
	/** Not enough in any observed place; when those places were seen is part of the result. Never "the account has none". */
	NOT_FOUND,
	/** Spyglass lacks the evidence (inventory not read, bank never seen, rune pouch contents not read). */
	UNKNOWN
}
