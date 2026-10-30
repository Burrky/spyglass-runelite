package com.osrstelemetry.plugin.farming;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.account.EvidenceSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Account facts Farming reads natively, each owned by the account / profile
 * the capability belongs to, and each UNKNOWN until the capability says the
 * account's variables were evidenced as loaded (never zero, empty or
 * unavailable by default):
 * <ul>
 *   <li>patch access ({@link FarmingAccess});</li>
 *   <li>the Farming Guild contract;</li>
 *   <li>the tool leprechaun's stored tools and compost;</li>
 *   <li>the Farming level.</li>
 * </ul>
 * Pure: built from a capability snapshot and evidence; no client access.
 */
public final class FarmingAccountFacts
{
	/** The Farming Guild contract, from {@code FARMGUILD_CONTRACT_TYPE} / {@code FARMGUILD_CONTRACT_COMPLETE}. */
	public static final class Contract
	{
		public enum State
		{
			/** The account's variables are not read yet. */
			NOT_READ,
			/** No contract held. */
			NONE,
			/** A contract held, crop known. */
			ACTIVE,
			/** Grown and harvested: return to Guildmaster Jane. */
			COMPLETE,
			/** A contract value Spyglass cannot name. */
			UNRECOGNISED
		}

		private final State state;
		private final FarmingProduce crop;

		Contract(State state, FarmingProduce crop)
		{
			this.state = state;
			this.crop = crop;
		}

		public State getState()
		{
			return state;
		}

		/** ACTIVE only: the crop to grow. */
		public FarmingProduce getCrop()
		{
			return crop;
		}

		static final Contract NOT_READ = new Contract(State.NOT_READ, null);
	}

	/**
	 * The tool leprechaun's storage, decoded exactly as the game's own tool-store
	 * script displays it (client script 1063, cache 2026-10-14-rev241, read from
	 * abextm/osrs-cache): each tool is {@code base + 2 × extra}, compost and
	 * supercompost {@code base + 256 × extra}, buckets {@code base + 32 × extra +
	 * 256 × extra2}, ultracompost its own varbit; secateurs are shown as magic
	 * secateurs when {@code FARMING_TOOLS_FAIRYSECATEURS} is 1; the bottomless
	 * bucket is absent (0), empty (1) or filled (more). Shared by every tool
	 * leprechaun. Its charges are not decoded.
	 */
	public static final class ToolStore
	{
		private final Map<Integer, Integer> counts;
		private final boolean magicSecateurs;
		private final int bottomless;

		ToolStore(Map<Integer, Integer> counts, boolean magicSecateurs, int bottomless)
		{
			this.counts = Collections.unmodifiableMap(counts);
			this.magicSecateurs = magicSecateurs;
			this.bottomless = bottomless;
		}

		/** Stored count of an item (RAKE, DIBBER, SPADE, SECATEURS / magic secateurs, TROWEL, BUCKET_EMPTY, the three composts, plant cure). */
		public int count(int itemId)
		{
			return counts.getOrDefault(itemId, 0);
		}

		public boolean isMagicSecateurs()
		{
			return magicSecateurs;
		}

		/** 0 none, 1 empty, 2+ filled (which compost is not decoded). */
		public int getBottomless()
		{
			return bottomless;
		}

		public Map<Integer, Integer> getCounts()
		{
			return counts;
		}
	}

	private final FarmingAccess.Resolver access;
	private final boolean accountRead;
	private final Contract contract;
	private final ToolStore toolStore;
	private final Integer farmingLevel;

	FarmingAccountFacts(FarmingAccess.Resolver access, boolean accountRead, Contract contract, ToolStore toolStore, Integer farmingLevel)
	{
		this.access = access;
		this.accountRead = accountRead;
		this.contract = contract;
		this.toolStore = toolStore;
		this.farmingLevel = farmingLevel;
	}

	/** No account facts (logged out, tests): every gated patch unverified, nothing else known. */
	public static final FarmingAccountFacts NONE = new FarmingAccountFacts(FarmingAccess.Resolver.NONE, false, Contract.NOT_READ, null, null);

	/** Tests and fixtures that predate access gating: every patch open, nothing else known. */
	public static final FarmingAccountFacts ALL_OPEN = new FarmingAccountFacts(patchId -> FarmingAccess.Access.OPEN, true, Contract.NOT_READ, null, null);

	public static FarmingAccountFacts of(AccountCapability cap, EvidenceSource evidence)
	{
		if (cap == null || cap.getOwner() == null)
		{
			return NONE;
		}
		boolean read = cap.isSettled();
		Integer level = cap.level(Skill.FARMING);
		return new FarmingAccountFacts(FarmingAccess.resolver(cap, evidence), read, read ? contract(cap) : Contract.NOT_READ,
			read ? toolStore(cap) : null, level);
	}

	static Contract contract(AccountCapability cap)
	{
		Integer type = cap.varbit(VarbitID.FARMGUILD_CONTRACT_TYPE);
		Integer complete = cap.varbit(VarbitID.FARMGUILD_CONTRACT_COMPLETE);
		if (type == null || complete == null)
		{
			return Contract.NOT_READ;
		}
		if (complete > 0)
		{
			return new Contract(Contract.State.COMPLETE, CONTRACT_CROPS.get(type));
		}
		if (type == 0)
		{
			return new Contract(Contract.State.NONE, null);
		}
		FarmingProduce crop = CONTRACT_CROPS.get(type);
		return crop == null ? new Contract(Contract.State.UNRECOGNISED, null) : new Contract(Contract.State.ACTIVE, crop);
	}

	static ToolStore toolStore(AccountCapability cap)
	{
		int[] needed = {VarbitID.FARMING_TOOLS_RAKE, VarbitID.FARMING_TOOLS_EXTRARAKES, VarbitID.FARMING_TOOLS_DIBBER, VarbitID.FARMING_TOOLS_EXTRADIBBERS,
			VarbitID.FARMING_TOOLS_SPADE, VarbitID.FARMING_TOOLS_EXTRASPADES, VarbitID.FARMING_TOOLS_SECATEURS, VarbitID.FARMING_TOOLS_EXTRASECATEURS,
			VarbitID.FARMING_TOOLS_FAIRYSECATEURS, VarbitID.FARMING_TOOLS_TROWEL, VarbitID.FARMING_TOOLS_EXTRATROWELS, VarbitID.FARMING_TOOLS_BUCKETS,
			VarbitID.FARMING_TOOLS_EXTRABUCKETS, VarbitID.FARMING_TOOLS_EXTRA2BUCKETS, VarbitID.FARMING_TOOLS_COMPOST, VarbitID.FARMING_TOOLS_EXTRACOMPOST,
			VarbitID.FARMING_TOOLS_SUPERCOMPOST, VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST, VarbitID.FARMING_TOOLS_ULTRACOMPOST,
			VarbitID.FARMING_TOOLS_PLANTCURE, VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE};
		for (int v : needed)
		{
			if (cap.varbit(v) == null)
			{
				return null; // not read: unknown, never "empty"
			}
		}
		Map<Integer, Integer> c = new HashMap<>();
		c.put(ItemID.RAKE, v(cap, VarbitID.FARMING_TOOLS_RAKE) + 2 * v(cap, VarbitID.FARMING_TOOLS_EXTRARAKES));
		c.put(ItemID.DIBBER, v(cap, VarbitID.FARMING_TOOLS_DIBBER) + 2 * v(cap, VarbitID.FARMING_TOOLS_EXTRADIBBERS));
		c.put(ItemID.SPADE, v(cap, VarbitID.FARMING_TOOLS_SPADE) + 2 * v(cap, VarbitID.FARMING_TOOLS_EXTRASPADES));
		boolean magic = v(cap, VarbitID.FARMING_TOOLS_FAIRYSECATEURS) == 1;
		c.put(magic ? ItemID.FAIRY_ENCHANTED_SECATEURS : ItemID.SECATEURS,
			v(cap, VarbitID.FARMING_TOOLS_SECATEURS) + 2 * v(cap, VarbitID.FARMING_TOOLS_EXTRASECATEURS));
		c.put(ItemID.TROWEL, v(cap, VarbitID.FARMING_TOOLS_TROWEL) + 2 * v(cap, VarbitID.FARMING_TOOLS_EXTRATROWELS));
		c.put(ItemID.BUCKET_EMPTY, v(cap, VarbitID.FARMING_TOOLS_BUCKETS) + 32 * v(cap, VarbitID.FARMING_TOOLS_EXTRABUCKETS)
			+ 256 * v(cap, VarbitID.FARMING_TOOLS_EXTRA2BUCKETS));
		c.put(ItemID.BUCKET_COMPOST, v(cap, VarbitID.FARMING_TOOLS_COMPOST) + 256 * v(cap, VarbitID.FARMING_TOOLS_EXTRACOMPOST));
		c.put(ItemID.BUCKET_SUPERCOMPOST, v(cap, VarbitID.FARMING_TOOLS_SUPERCOMPOST) + 256 * v(cap, VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST));
		c.put(ItemID.BUCKET_ULTRACOMPOST, v(cap, VarbitID.FARMING_TOOLS_ULTRACOMPOST));
		c.put(ItemID.PLANT_CURE, v(cap, VarbitID.FARMING_TOOLS_PLANTCURE));
		return new ToolStore(c, magic, v(cap, VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE));
	}

	private static int v(AccountCapability cap, int varbit)
	{
		Integer x = cap.varbit(varbit);
		return x == null ? 0 : x;
	}

	/** Every varbit these facts read (registered with the account adapter). */
	public static int[] varbits()
	{
		return new int[]{VarbitID.FARMGUILD_CONTRACT_TYPE, VarbitID.FARMGUILD_CONTRACT_COMPLETE, VarbitID.FARMING_TOOLS_RAKE,
			VarbitID.FARMING_TOOLS_EXTRARAKES, VarbitID.FARMING_TOOLS_DIBBER, VarbitID.FARMING_TOOLS_EXTRADIBBERS, VarbitID.FARMING_TOOLS_SPADE,
			VarbitID.FARMING_TOOLS_EXTRASPADES, VarbitID.FARMING_TOOLS_SECATEURS, VarbitID.FARMING_TOOLS_EXTRASECATEURS,
			VarbitID.FARMING_TOOLS_FAIRYSECATEURS, VarbitID.FARMING_TOOLS_TROWEL, VarbitID.FARMING_TOOLS_EXTRATROWELS, VarbitID.FARMING_TOOLS_BUCKETS,
			VarbitID.FARMING_TOOLS_EXTRABUCKETS, VarbitID.FARMING_TOOLS_EXTRA2BUCKETS, VarbitID.FARMING_TOOLS_COMPOST,
			VarbitID.FARMING_TOOLS_EXTRACOMPOST, VarbitID.FARMING_TOOLS_SUPERCOMPOST, VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST,
			VarbitID.FARMING_TOOLS_ULTRACOMPOST, VarbitID.FARMING_TOOLS_PLANTCURE, VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE};
	}

	/**
	 * {@code FARMGUILD_CONTRACT_TYPE} values per crop, from RuneLite's
	 * time-tracking {@code Produce.contractVarbitValue} (master @ 4baec1b,
	 * BSD-2), which its {@code FarmingContractManager} reads the same way.
	 */
	static final Map<Integer, FarmingProduce> CONTRACT_CROPS = new HashMap<>();

	static
	{
		Object[][] table = {
			{1, FarmingProduce.REDBERRIES}, {2, FarmingProduce.CADAVABERRIES}, {3, FarmingProduce.DWELLBERRIES}, {4, FarmingProduce.JANGERBERRIES},
			{5, FarmingProduce.WHITEBERRIES}, {6, FarmingProduce.POISON_IVY}, {7, FarmingProduce.OAK}, {8, FarmingProduce.WILLOW},
			{9, FarmingProduce.MAPLE}, {10, FarmingProduce.YEW}, {11, FarmingProduce.MAGIC}, {12, FarmingProduce.APPLE}, {13, FarmingProduce.BANANA},
			{14, FarmingProduce.ORANGE}, {15, FarmingProduce.CURRY}, {16, FarmingProduce.PINEAPPLE}, {17, FarmingProduce.PAPAYA},
			{18, FarmingProduce.PALM}, {19, FarmingProduce.DRAGONFRUIT}, {20, FarmingProduce.GUAM}, {21, FarmingProduce.MARRENTILL},
			{22, FarmingProduce.TARROMIN}, {23, FarmingProduce.HARRALANDER}, {24, FarmingProduce.RANARR}, {25, FarmingProduce.TOADFLAX},
			{26, FarmingProduce.IRIT}, {27, FarmingProduce.AVANTOE}, {28, FarmingProduce.KWUARM}, {29, FarmingProduce.SNAPDRAGON},
			{30, FarmingProduce.CADANTINE}, {31, FarmingProduce.LANTADYME}, {32, FarmingProduce.DWARF_WEED}, {33, FarmingProduce.TORSTOL},
			{34, FarmingProduce.MARIGOLD}, {35, FarmingProduce.ROSEMARY}, {36, FarmingProduce.NASTURTIUM}, {37, FarmingProduce.WOAD},
			{38, FarmingProduce.LIMPWURT}, {39, FarmingProduce.WHITE_LILY}, {40, FarmingProduce.POTATO}, {41, FarmingProduce.ONION},
			{42, FarmingProduce.CABBAGE}, {43, FarmingProduce.TOMATO}, {44, FarmingProduce.SWEETCORN}, {45, FarmingProduce.STRAWBERRY},
			{46, FarmingProduce.WATERMELON}, {47, FarmingProduce.SNAPE_GRASS}, {48, FarmingProduce.CACTUS}, {49, FarmingProduce.POTATO_CACTUS},
			{50, FarmingProduce.REDWOOD}, {51, FarmingProduce.CELASTRUS}};
		for (Object[] row : table)
		{
			CONTRACT_CROPS.put((Integer) row[0], (FarmingProduce) row[1]);
		}
	}

	public FarmingAccess.Resolver getAccess()
	{
		return access;
	}

	/** The account's variables were evidenced as loaded (gated patches can be judged). */
	public boolean isAccountRead()
	{
		return accountRead;
	}

	public Contract getContract()
	{
		return contract;
	}

	/** The tool leprechaun's storage, or null when not read (unknown: never "nothing stored"). */
	public ToolStore getToolStore()
	{
		return toolStore;
	}

	public Integer getFarmingLevel()
	{
		return farmingLevel;
	}

	/** Evidence wrapper for tests that only need access. */
	static FarmingAccountFacts accessOnly(FarmingAccess.Resolver access)
	{
		return new FarmingAccountFacts(access, true, Contract.NOT_READ, null, null);
	}
}
