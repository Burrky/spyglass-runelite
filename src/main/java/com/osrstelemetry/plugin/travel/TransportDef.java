package com.osrstelemetry.plugin.travel;

import java.util.Collections;
import com.osrstelemetry.plugin.account.RequirementSet;
import java.util.List;

/**
 * One transport as defined in a catalogue JSON file (canonical §11.2). Plain
 * data parsed by Gson; {@link TransportCatalog} validates it. Java owns the
 * schema and evaluation; the files own the definitions.
 *
 * Every numeric id is paired with its {@code net.runelite.api.gameval} constant
 * name, and the catalogue test resolves the name by reflection (test-time
 * only) so a mistyped id cannot ship. Every entry carries provenance.
 */
public final class TransportDef
{
	public enum Type
	{
		SPELL,
		TABLET,
		ITEM_TELEPORT,
		FAIRY_RING,
		SPIRIT_TREE
	}

	/** How an item variant's uses are limited. */
	public enum Charges
	{
		/** No limit (Ectophial, elite diary items, capes). */
		UNLIMITED,
		/** The variant id encodes the charges left (jewellery: {@code count}). */
		FIXED,
		/** One item is used up per teleport (tablets, basalt). */
		CONSUMED,
		/** A per-day limit ({@code dailyLimit}); today's uses are not observable, so charges are unknown. */
		DAILY_LIMIT,
		/** Charges stored on the item and not observable natively (Xeric's talisman). */
		STORED_UNOBSERVED,
		/** This variant cannot teleport until recharged or refilled. */
		DEPLETED
	}

	public enum From
	{
		INVENTORY,
		WORN
	}

	public static final class Point
	{
		public int x;
		public int y;
		public int plane;
	}

	public static final class ItemRef
	{
		public int id;
		/** The gameval {@code ItemID} constant name; checked against {@link #id} by the catalogue test. */
		public String gameval;
	}

	public static final class ItemVariant
	{
		public int id;
		public String gameval;
		public Charges charges;
		/** FIXED: charges this variant holds. */
		public Integer count;
		/** DAILY_LIMIT: uses per day (-1 = unlimited). */
		public Integer dailyLimit;
		/** Where it can be used from; informational (a carried item can be worn in a click). */
		public List<From> from;
		public String note;
		/**
		 * DAILY_LIMIT / STORED_UNOBSERVED: the observed pool its charges or uses come from (ChargeKnowledge), or null when
		 * the game reports nothing Spyglass reads for it.
		 */
		public String pool;
	}

	public static final class RuneCost
	{
		public int id;
		public String gameval;
		public int qty;
	}

	public static final class WieldReq
	{
		public String name;
		public List<ItemRef> items;
		/** A diary key (AccountReaders.diaryKey) whose completion waives the requirement. */
		public String unlessDiary;
	}

	public static final class Provenance
	{
		/** runelite-derived(BSD-2) | wiki-checked | game-observed. */
		public String source;
		public String ref;
		/** Date checked; null = NOT verified, so the evaluator never reports better than UNKNOWN. */
		public String verified;
	}

	public String id;
	public String name;
	public Type type;
	/** For network transports (fairy rings, spirit trees): reached from any node of the network. */
	public String network;
	public Point arrival;
	/** Account requirements (generic: {@link RequirementSet}). */
	public RequirementSet requirements;
	/** An item that must be wielded to use the transport (fairy rings), unless a diary waives it. */
	public WieldReq wield;
	public List<RuneCost> runes;
	public List<ItemVariant> items;
	/** Facts shown with the result that do not change availability. */
	public List<String> notes;
	public Provenance provenance;
	/** Tiles walked before the final arrival (a journey through a fairy ring or spirit tree: the walk to the entrance); null = 0. */
	public Integer preWalk;
	/** A complete network journey (canonical §28.9): how the player reaches the network and which hop they take; null otherwise. */
	public Via via;
	/**
	 * The walk from the entry teleport to the entrance (canonical §28.10): generated route data with its transitions and their
	 * requirements, checked by {@link TransportEvaluator}. Derived, never read from or written to JSON; null otherwise.
	 */
	public transient com.osrstelemetry.plugin.navigation.RouteTable.Walk walk;
	/** The walk's route data keys (destination "node:<id>", start "arr:<teleport>"), resolved per account; null otherwise. */
	public transient String walkTo;
	public transient String walkFrom;

	/** Entry teleport → entrance node → network hop: the parts of a journey through a fairy ring or spirit tree. */
	public static final class Via
	{
		/** The teleport that starts the journey (its items, runes and requirements are this journey's). */
		public String entry;
		/** Where the entry teleport lands. */
		public Point entryAt;
		/** The fairy ring or spirit tree walked to (network-nodes.json id). */
		public String node;
		public String nodeName;
		public Point nodeAt;
		/** The network destination taken (its staff and requirements are this journey's too). */
		public String hop;
		public String network;
		/** Fairy ring code to dial, or the spirit tree destination's name. */
		public String code;
	}

	public int preWalkTiles()
	{
		return preWalk == null ? 0 : preWalk;
	}

	public boolean isVerified()
	{
		return provenance != null && provenance.verified != null && !provenance.verified.isEmpty();
	}

	public static <T> List<T> list(List<T> l)
	{
		return l == null ? Collections.emptyList() : l;
	}

	public RequirementSet requirements()
	{
		return requirements == null ? new RequirementSet() : requirements;
	}
}
