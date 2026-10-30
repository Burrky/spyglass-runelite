package com.osrstelemetry.plugin.skills.smithing;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One Smithing recipe as DATA: its process, its exact inputs (item + how many
 * one ACTION uses), and its one output item (+ how many one action makes).
 *
 * <p>Deliberately NOT shared with HerbloreRecipe (Skills Phase 7 decision):
 * Herblore counts doses, per-dose requirements, savings and recipe chains;
 * Smithing counts items per action across process variants (Blast Furnace /
 * catalyst coal halving, delayed mould output, dispenser collection). The
 * only overlap -- the limiting input of "have / per action" -- is a few lines.
 *
 * <p>Quantities exist ONLY for CURRENT BATCH and ACCOUNT POTENTIAL (planning).
 * Session production and consumed inputs are always what was actually
 * observed, never recipe theory.
 */
public final class SmithingRecipe
{
	/** One input: one exact item and how many one action uses. */
	public static final class Input
	{
		private final int itemId;
		private final String name;
		private final String caption;
		private final int perAction;
		private final boolean primary;
		private final boolean coal;

		Input(int itemId, String name, String caption, int perAction, boolean primary, boolean coal)
		{
			this.itemId = itemId;
			this.name = Objects.requireNonNull(name);
			this.caption = Objects.requireNonNull(caption);
			this.perAction = perAction;
			this.primary = primary;
			this.coal = coal;
		}

		public int getItemId()
		{
			return itemId;
		}

		/** Natural name ("Iron ore"). */
		public String getName()
		{
			return name;
		}

		/** Compact cell caption ("IRON ORE", "COAL", "MITHRIL BARS"). */
		public String getCaption()
		{
			return caption;
		}

		/** Items one action uses (the recipe's own ratio; coal at a normal furnace). */
		public int getPerAction()
		{
			return perAction;
		}

		/** Consuming it is part of the causal proof of an action (the ore, the bar, the part). */
		public boolean isPrimary()
		{
			return primary;
		}

		/** Coal: halved at the Blast Furnace and by a smithing catalyst; may come straight from a coal bag. */
		public boolean isCoal()
		{
			return coal;
		}
	}

	private final String key;
	private final String name;
	private final SmithingProcess process;
	private final List<Input> inputs;
	private final int outputItem;
	private final String outputName;
	private final int outputPerAction;
	private final String unitCaption;
	private final String tier;

	SmithingRecipe(String key, String name, SmithingProcess process, List<Input> inputs, int outputItem, String outputName,
		int outputPerAction, String unitCaption, String tier)
	{
		this.key = Objects.requireNonNull(key);
		this.name = Objects.requireNonNull(name);
		this.process = Objects.requireNonNull(process);
		this.inputs = Collections.unmodifiableList(inputs);
		this.outputItem = outputItem;
		this.outputName = Objects.requireNonNull(outputName);
		this.outputPerAction = outputPerAction;
		this.unitCaption = Objects.requireNonNull(unitCaption);
		this.tier = tier;
	}

	public String getKey()
	{
		return key;
	}

	/** Player-facing recipe name ("Steel bars", "Steel bars (BF)", "Mithril platebody", "Rune dart tips"). */
	public String getName()
	{
		return name;
	}

	public SmithingProcess getProcess()
	{
		return process;
	}

	public List<Input> getInputs()
	{
		return inputs;
	}

	public int getOutputItem()
	{
		return outputItem;
	}

	/** The output item's own name ("Steel bar", "Rune dart tip"). */
	public String getOutputName()
	{
		return outputName;
	}

	/** Items one action makes (10 dart tips, 4 cannonballs per bar, 15 nails...). */
	public int getOutputPerAction()
	{
		return outputPerAction;
	}

	/** SESSION count caption ("BARS", "ITEMS", "DART TIPS", "CANNONBALLS"). */
	public String getUnitCaption()
	{
		return unitCaption;
	}

	/** Metal tier ("steel"), or null. */
	public String getTier()
	{
		return tier;
	}

	public Input primary()
	{
		for (Input in : inputs)
		{
			if (in.isPrimary())
			{
				return in;
			}
		}
		return inputs.get(0);
	}

	public boolean usesCoal()
	{
		for (Input in : inputs)
		{
			if (in.isCoal())
			{
				return true;
			}
		}
		return false;
	}

	public Input input(int itemId)
	{
		for (Input in : inputs)
		{
			if (in.getItemId() == itemId)
			{
				return in;
			}
		}
		return null;
	}

	/**
	 * CAN MAKE (planning): output ITEMS what {@code available} can still make
	 * -- the limiting input decides. Coal per action is halved for the first
	 * {@code catalysts} actions (a smithing catalyst each), never at the Blast
	 * Furnace (its ratio is already the halved one). {@code extraCoal} is coal
	 * outside the inventory the process can draw on (a known coal bag).
	 */
	public long canMake(Map<Integer, Long> available, long catalysts, long extraCoal)
	{
		long actions = Long.MAX_VALUE;
		Input coalInput = null;
		for (Input in : inputs)
		{
			if (in.isCoal())
			{
				coalInput = in;
				continue;
			}
			actions = Math.min(actions, available.getOrDefault(in.getItemId(), 0L) / Math.max(1, in.getPerAction()));
		}
		if (coalInput != null)
		{
			long coal = available.getOrDefault(coalInput.getItemId(), 0L) + Math.max(0L, extraCoal);
			int full = Math.max(1, coalInput.getPerAction());
			long cat = process == SmithingProcess.BLAST_FURNACE ? 0L : Math.max(0L, catalysts);
			int half = Math.max(1, full / 2);
			long byCoal;
			if (coal >= cat * half)
			{
				byCoal = cat + (coal - cat * half) / full;
			}
			else
			{
				byCoal = coal / half;
			}
			actions = Math.min(actions, byCoal);
		}
		if (actions == Long.MAX_VALUE)
		{
			return 0L;
		}
		return Math.max(0L, actions) * outputPerAction;
	}

	@Override
	public String toString()
	{
		return key;
	}
}
