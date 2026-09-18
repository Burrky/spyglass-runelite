package com.osrstelemetry.plugin.session;

import com.osrstelemetry.plugin.model.StorageState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * An
 * immutable, slot-exact inventory+equipment snapshot, persisted as a
 * finalized {@link Session}'s starting and/or ending loadout.
 *
 * SLOT-EXACT (non-optional core requirement):
 * {@code inventory} always carries exactly {@link #INVENTORY_SIZE}
 * entries, one per slot 0..27, INCLUDING explicit empty-slot entries
 * (itemId=-1, name=null, quantity=0) -- never a sparse occupied-only
 * list. {@code equipment} carries one entry per equipment slot, same
 * convention. Both reuse {@link StorageState.StorageItem} directly
 * rather than inventing a parallel item-shape class, since that
 * shape (slot / itemId / name / quantity / equipmentSlot) already
 * matches exactly what a loadout slot needs, and is already the
 * shape ContainerCollector.buildEquipmentItems() produces for
 * equipment today. The actual slot-complete CAPTURE logic (reading a
 * live ItemContainer into this shape for inventory too, which
 * ContainerCollector does not currently do -- see its own class
 * javadoc on buildSparseItems()) is handled elsewhere; this class is
 * purely the persisted/immutable data shape.
 *
 * IMMUTABILITY: both lists are defensively copied and wrapped
 * unmodifiable at construction, matching SessionSnapshot's existing
 * capture-time defensive-copy convention elsewhere in this package --
 * a caller can never mutate a persisted loadout through a reference
 * handed back by a getter. Note this guarantee is a CAPTURE-time
 * guarantee, same as every other model in this project: Gson's
 * reflective deserialization (LocalStateStore, no registered
 * TypeAdapters -- see its own javadoc) does not invoke this
 * constructor and will hand back plain (mutable) ArrayLists on a
 * round-trip from disk. That is accepted and consistent with how
 * every other persisted model in this codebase already behaves
 * (Session, SessionAggregates, StorageState itself) -- nothing else
 * in this codebase mutates a record freshly read back from an
 * immutable historical file.
 *
 * EMPTY-BUT-NEVER-NULL FOR UNAVAILABLE: when provenance is
 * UNAVAILABLE, inventory/equipment are empty lists (never null) and
 * capturedAt is null -- see {@link #unavailable()}. This keeps every
 * consumer (Gson, the History UI) able to treat the two list fields
 * uniformly regardless of provenance; only the provenance field
 * itself needs a branch.
 *
 * BACKWARD COMPATIBILITY: this type is entirely new, so
 * an old sessions/{id}.json written before this type existed has no
 * startingLoadout/endingLoadout field at all. Gson leaves both as
 * null on the deserialized Session (its ordinary missing-field
 * behavior -- see Session's own javadoc), which the History UI must
 * treat identically to an explicit {@link #unavailable()} snapshot.
 * This class itself never has to parse or migrate an old shape; see
 * SessionLoadoutBackwardCompatibilityTest for the round-trip proof.
 */
@Getter
@ToString
@EqualsAndHashCode
public final class LoadoutSnapshot
{
	/** Fixed OSRS inventory slot count -- a LoadoutSnapshot with real inventory data always carries exactly this many entries. */
	public static final int INVENTORY_SIZE = 28;

	private final List<StorageState.StorageItem> inventory;
	private final List<StorageState.StorageItem> equipment;

	/**
	 * ISO-8601 Instant#toString() of the moment this snapshot was
	 * actually observed -- deliberately NOT the session's own
	 * startedAt/finalizedAt, which may differ from this by however
	 * far the resolution ladder had to fall back (see
	 * {@link LoadoutProvenance}). Null only when provenance is
	 * UNAVAILABLE.
	 */
	private final String capturedAt;

	private final LoadoutProvenance provenance;

	public LoadoutSnapshot(List<StorageState.StorageItem> inventory, List<StorageState.StorageItem> equipment,
		String capturedAt, LoadoutProvenance provenance)
	{
		this.inventory = Collections.unmodifiableList(new ArrayList<>(inventory));
		this.equipment = Collections.unmodifiableList(new ArrayList<>(equipment));
		this.capturedAt = capturedAt;
		this.provenance = provenance;
	}

	/**
	 * The canonical "nothing could be resolved" instance -- never
	 * fabricates placeholder items, never leaves the lists null.
	 */
	public static LoadoutSnapshot unavailable()
	{
		return new LoadoutSnapshot(Collections.emptyList(), Collections.emptyList(), null, LoadoutProvenance.UNAVAILABLE);
	}
}
