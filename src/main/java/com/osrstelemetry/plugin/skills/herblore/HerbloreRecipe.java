package com.osrstelemetry.plugin.skills.herblore;

import com.osrstelemetry.plugin.skills.UnitFamily;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One Herblore recipe as DATA (no class per potion): its natural name, its
 * process, its exact inputs, its output (a potion family counted in doses,
 * or plain items), and the rules that decide what can be claimed about it.
 *
 * <ul>
 * <li>Inputs are matched by exact item identity. The PRIMARY input is the one
 * each mix uses exactly one item of (the grimy herb, the herb, the
 * unfinished potion, the base potion): consuming it is part of the causal
 * evidence of a mix and the count of mixes.</li>
 * <li>Quantities ({@code perMix} / {@code perDose}) exist only for the current
 * batch's CAN MAKE and for ACTUAL saved inputs; a null quantity means
 * Spyglass does not claim one (no CAN MAKE limit, no savings), never a
 * guess.</li>
 * <li>Output doses: a fixed number per mix (3 for most potions, 4 for the
 * always-4-dose ones, 2 for barbarian mixes) or the base potion's own doses
 * (upgrades, extensions, divine potions).</li>
 * </ul>
 */
public final class HerbloreRecipe
{
	public enum Role
	{
		/** One item per mix (herb, unfinished potion, base potion). */
		PRIMARY,
		/** A vial of water (unfinished potions). */
		VIAL,
		/** Any other ingredient (secondaries, extra potions, catalysts). */
		SECONDARY
	}

	/** One input: its exact items (with the doses each holds when dosed), its role and how much one mix / dose uses. */
	public static final class Input
	{
		private final Role role;
		private final String name;
		private final String caption;
		private final Map<Integer, Integer> items;
		private final boolean dosed;
		private final Integer perMix;
		private final Integer perDose;
		private final boolean savable;

		Input(Role role, String name, String caption, Map<Integer, Integer> items, boolean dosed, Integer perMix, Integer perDose, boolean savable)
		{
			this.role = role;
			this.name = name;
			this.caption = caption;
			this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
			this.dosed = dosed;
			this.perMix = perMix;
			this.perDose = perDose;
			this.savable = savable;
		}

		public Role getRole()
		{
			return role;
		}

		/** The exact input's natural name ("Red spiders' eggs"). */
		public String getName()
		{
			return name;
		}

		/** Compact caption for the narrow panel ("UNFINISHED", "SECONDARY", "ANTIFIRE"). */
		public String getCaption()
		{
			return caption;
		}

		/** Item id -> doses it holds (1 for an undosed item). */
		public Map<Integer, Integer> getItems()
		{
			return items;
		}

		public boolean contains(int itemId)
		{
			return items.containsKey(itemId);
		}

		/** Counted in doses (a base potion of any dose). */
		public boolean isDosed()
		{
			return dosed;
		}

		/** Items one mix uses; null = per dose or not claimed. */
		public Integer getPerMix()
		{
			return perMix;
		}

		/** Items one base dose uses (upgrades); null = per mix or not claimed. */
		public Integer getPerDose()
		{
			return perDose;
		}

		/** Prescription goggles can save it (and its requirement is known exactly). */
		public boolean isSavable()
		{
			return savable && (perMix != null || perDose != null);
		}

		/** What {@code available} (item -> qty) holds of this input: doses when dosed, else items. */
		public long available(Map<Integer, Long> available)
		{
			long n = 0L;
			for (Map.Entry<Integer, Integer> e : items.entrySet())
			{
				long q = available.getOrDefault(e.getKey(), 0L);
				n += q * (dosed ? e.getValue() : 1);
			}
			return n;
		}
	}

	private final String key;
	private final String name;
	private final HerbloreProcess process;
	private final List<Input> inputs;
	/** Dose-normalized output family; null for undosed outputs. */
	private final UnitFamily outputFamily;
	/** Every output item (family members or the undosed item[s]). */
	private final Set<Integer> outputItems;
	/** Undosed output's display name (clean herb, unfinished potion, poison); null when dosed. */
	private final String outputName;
	/** Doses each mix makes; 0 = the base potion's own doses (process preserves doses). */
	private final int fixedDoses;
	private final boolean xpRequired;
	private final boolean bonusEligible;
	private final boolean storable;
	private final boolean batchReliable;

	HerbloreRecipe(String key, String name, HerbloreProcess process, List<Input> inputs, UnitFamily outputFamily, Set<Integer> outputItems,
		String outputName, int fixedDoses, boolean xpRequired, boolean bonusEligible, boolean storable, boolean batchReliable)
	{
		this.key = Objects.requireNonNull(key);
		this.name = Objects.requireNonNull(name);
		this.process = Objects.requireNonNull(process);
		this.inputs = Collections.unmodifiableList(inputs);
		this.outputFamily = outputFamily;
		this.outputItems = Collections.unmodifiableSet(new LinkedHashSet<>(outputItems));
		this.outputName = outputName;
		this.fixedDoses = fixedDoses;
		this.xpRequired = xpRequired;
		this.bonusEligible = bonusEligible;
		this.storable = storable;
		this.batchReliable = batchReliable;
	}

	public String getKey()
	{
		return key;
	}

	public String getName()
	{
		return name;
	}

	public HerbloreProcess getProcess()
	{
		return process;
	}

	public List<Input> getInputs()
	{
		return inputs;
	}

	/** The single PRIMARY input (every recipe has exactly one). */
	public Input primary()
	{
		for (Input i : inputs)
		{
			if (i.getRole() == Role.PRIMARY)
			{
				return i;
			}
		}
		throw new IllegalStateException(key + " has no primary input");
	}

	public UnitFamily getOutputFamily()
	{
		return outputFamily;
	}

	/** Output counted in doses (a potion family). */
	public boolean isDoseNormalized()
	{
		return outputFamily != null && outputFamily.getFullUnits() > 1;
	}

	public Set<Integer> getOutputItems()
	{
		return outputItems;
	}

	public boolean produces(int itemId)
	{
		return outputItems.contains(itemId);
	}

	/** Units one output item holds (doses for a potion family; 1 otherwise). */
	public int outputUnits(int itemId)
	{
		return outputFamily == null ? (outputItems.contains(itemId) ? 1 : 0) : outputFamily.unitsOf(itemId);
	}

	public String getOutputName()
	{
		return outputFamily != null ? outputFamily.getName() : outputName;
	}

	public int getFixedDoses()
	{
		return fixedDoses;
	}

	/** The output keeps the base potion's doses (upgrades, extensions, divine potions). */
	public boolean preservesDoses()
	{
		return fixedDoses == 0;
	}

	/** Each mix gives Herblore XP (false only for unfinished potions, which the game gives none for). */
	public boolean isXpRequired()
	{
		return xpRequired;
	}

	/** An amulet of chemistry / alchemist's amulet can add a dose (never for always-4-dose recipes). */
	public boolean isBonusEligible()
	{
		return bonusEligible;
	}

	/** The output can live in Potion Storage. */
	public boolean isStorable()
	{
		return storable;
	}

	/** Every input's per-mix / per-dose need is known exactly (CAN MAKE can be claimed). */
	public boolean isBatchReliable()
	{
		return batchReliable;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
