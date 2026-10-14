package com.osrstelemetry.plugin.skills;

import java.util.List;
import net.runelite.api.Skill;

/**
 * What a skill adapter tells the generic skill companion (UI and History)
 * about its own skill -- method names, resources, wording -- so the shared
 * chassis never hard-codes one skill's vocabulary. Registered skills:
 * {@link SkillCompanions}; every other skill keeps the plain SKILLING session.
 *
 * <h3>Framework contract (every adapter)</h3>
 * <ul>
 * <li><b>Session ownership</b> is the session engine's alone (the skill's own
 * XP). A companion only ADDS evidence, through exactly one of: method
 * evidence ({@link #methodNeedsOwnershipEvidence}: one method grants several
 * skills' XP at once) or context evidence (SkillTracker#drainContextEvidence:
 * OTHER skills' XP granted inside this skill's own activity -- a tool, a
 * contextual event, a second XP drop of the same action). Structured
 * activities are never claimed: production that no session of this skill
 * takes within SkillCompanionCollector#PENDING_LIMIT_MILLIS is dropped.</li>
 * <li><b>Resource semantics</b> (ProductionLedger): PRODUCED (TOTAL, only
 * ever grows) ⊇ AUTO-TRANSFORMED (never banked); BANKED ≤ produced (only
 * this session's own held production, only on a proven deposit); CONSUMED
 * inputs are never production; a {@link UnitFamily} counts units, never
 * items; an external account storage ({@link SkillExternalStock}) is OWNED
 * stock, never BANKED.</li>
 * <li><b>Unknown is never zero</b>: unseen bank / storage = null (lower /
 * upper bounds and no ETA), never 0.</li>
 * <li><b>Targets</b>: LEVEL is universal; STOCKPILE is offered for
 * {@link #stockpileResources} (rewards for {@link #productionIsReward}
 * skills). ETAs use observed rates only (XP/hr; BANKED/hr after
 * SkillGoalPlanner#MIN_BANK_TRIPS deposits) -- never production/hr.</li>
 * <li><b>Vocabulary</b>: every player-facing word the chassis shows comes
 * from a hook here; defaults are skill-neutral.</li>
 * <li><b>Telemetry</b>: one bounded SKILL_PRODUCTION per ~30 s of activity /
 * deposit / logout; never per item or per tick.</li>
 * </ul>
 */
public interface SkillCompanionDefinition
{
	/** The SKILLING session's activity key ("mining"). */
	String getSkillKey();

	Skill getSkill();

	/** Player-facing method name ("Amethyst"); null for an unknown / null key. */
	String methodLabel(String methodKey);

	/** The resource behind an item id, or null when it is not this skill's production. */
	SkillResource resource(Integer itemId);

	/** Whether inventory (or specialized storage) capacity is a real decision for this method (shows CURRENT TRIP). */
	boolean tripMatters(String methodKey);

	/** Whether the method's MAIN product is a grouped by-product family for other methods (Gem rocks: gems are the point). */
	boolean groupIsPrimary(String methodKey, String group);

	/**
	 * The production cell's caption when the session's main production is
	 * exactly the given resources ("IRON MINED", "AMETHYST", "GEMS MINED");
	 * the chassis uses "TOTAL" itself when several different resources mix.
	 */
	String productionCaption(String methodKey, List<SkillResource> mainResources);

	/** Unit word for the rate line ("ore" -> "1,103 ore/hr"). */
	String rateNoun(String methodKey);

	/** Past-tense verb for History's compact row ("1,284 mined"). */
	String producedVerb();

	/** History title for the produced-items grid ("Mined"). */
	String producedTitle();

	/** Resources a STOCKPILE target may name for this method (reliably identified, produced and banked). */
	List<SkillResource> stockpileResources(String methodKey);

	/**
	 * Whether the carried specialized storage is worth a CURRENT TRIP line for
	 * this method / session (Mining: only when gems matter; Fishing: whenever
	 * the fish barrel is carried).
	 */
	boolean storageRelevant(String methodKey, java.util.Set<Integer> producedItems);

	/** Whether an open storage catches this method's MAIN product (so an idle inventory is expected, not missing data). */
	boolean storageCatchesMain(String methodKey);

	/** Tooltip on the CURRENT TRIP storage line (what the container is and why its contents may be unknown). */
	default String storageTooltip()
	{
		return "Production your open container caught goes there instead of your inventory. Its contents become known "
			+ "once it is emptied (Spyglass never guesses what was in it before)";
	}

	/**
	 * Whether {@code itemId} is one of the outputs of the multi-output method
	 * {@code methodKey} (fly fishing: trout and salmon), so a mix of them is
	 * still that one method's rate. False by default (every mix is a
	 * whole-session rate).
	 */
	default boolean methodProduces(String methodKey, int itemId)
	{
		return false;
	}

	/**
	 * Whether {@code methodKey} also grants OTHER skills' XP on the same
	 * success (barbarian fishing: Agility and Strength with every catch), so
	 * the session engine alone cannot tell which skill the activity is. For
	 * such a method the companion reports its fresh method evidence to the
	 * session engine (SessionRuntimeCoordinator#noteSkillMethodEvidence).
	 * False by default.
	 */
	default boolean methodNeedsOwnershipEvidence(String methodKey)
	{
		return false;
	}

	// ---- presentation of cycle-based skills (ADDED, Skills Phase 3: Agility) ------------------------

	/** What the companion calls a method ("Method"; Agility: "Course"). */
	default String methodTitle()
	{
		return "Method";
	}

	/** The skill's completed-cycle unit ("lap" / "laps"); null for skills without cycles (Mining, Fishing). */
	default String cycleNoun(boolean plural)
	{
		return null;
	}

	/** CURRENT cycle progress caption ("OBSTACLE"); null when the skill has no cycles. */
	default String cycleProgressLabel(String methodKey)
	{
		return null;
	}

	/** Reliably evidenced failure unit ("fall" / "falls"); null when the skill tracks none. */
	default String failureNoun(boolean plural)
	{
		return null;
	}

	/**
	 * Whether the "produced" resources are secondary REWARDS of the activity
	 * (Agility: Marks of Grace) rather than its production: shown as
	 * REWARDS (count + per hour), never as TOTAL / BANKED.
	 */
	default boolean productionIsReward()
	{
		return false;
	}

	/** STOCKPILE target before this session produced anything to choose from. */
	default String stockpileEmptyHint()
	{
		return "Produce something first to pick what to stockpile";
	}

	// ---- ADDED (Skills Phase 4) -- every default keeps Mining / Fishing / Agility unchanged ----

	/**
	 * Past-tense phrase for production a tool transformed the moment it was
	 * produced (verb -> "265 &lt;verb&gt;"); null when the skill has
	 * no such tool (the default).
	 */
	default String autoTransformedVerb()
	{
		return null;
	}

	/** Tooltip on the auto-transformed line (what transformed it and why it never banks). */
	default String autoTransformedTooltip()
	{
		return "Part of the total, transformed the moment it was produced (never in your inventory, never banked)";
	}

	/** Title of the CONTEXTUAL EVENT section. */
	default String eventTitle()
	{
		return "Event";
	}

	/** Tooltip on the focus's production cell. */
	default String focusCountTooltip()
	{
		return "What this gave you so far (every destination)";
	}

	/** Title of the CURRENT FOCUS section; null = the skill has no focus (the default). */
	default String focusTitle()
	{
		return null;
	}

	/** The focus's production caption. */
	default String focusCountCaption(String methodKey)
	{
		return "PRODUCED";
	}

	/** The focus's time caption. */
	default String focusTimeCaption()
	{
		return "TIME";
	}

	/**
	 * A SECONDARY resource family with a section of its own instead of a
	 * by-product line under PRODUCTION (count + rate cells, or one compact
	 * exact per-resource line).
	 */
	final class Secondary
	{
		private final String group;
		private final String caption;
		private final String rateCaption;
		private final boolean compactLine;
		private final String tooltip;

		public Secondary(String group, String caption, String rateCaption, boolean compactLine, String tooltip)
		{
			this.group = group;
			this.caption = caption;
			this.rateCaption = rateCaption;
			this.compactLine = compactLine;
			this.tooltip = tooltip;
		}

		/** SkillResource#getGroup() of the family's resources. */
		public String getGroup()
		{
			return group;
		}

		/** Count cell caption, or the compact line's title. */
		public String getCaption()
		{
			return caption;
		}

		/** Rate cell caption; unused for a compact line. */
		public String getRateCaption()
		{
			return rateCaption;
		}

		/** One exact per-resource line instead of count + rate cells. */
		public boolean isCompactLine()
		{
			return compactLine;
		}

		public String getTooltip()
		{
			return tooltip;
		}
	}

	/** Secondary families with a section of their own, in display order; none by default. */
	default List<Secondary> secondaries()
	{
		return java.util.Collections.emptyList();
	}

	/** Display name of a contextual occurrence key; null for an unknown key. */
	default String occurrenceLabel(String key)
	{
		return null;
	}

	/** History title for contextual occurrences; null = the skill has none (the default). */
	default String occurrencesTitle()
	{
		return null;
	}

	/**
	 * Whether History's compact row counts only PRIMARY production (never
	 * secondary families). False by default: Mining's row keeps
	 * counting everything it mined, gems included, exactly as before.
	 */
	default boolean historyCountsPrimaryOnly()
	{
		return false;
	}

	/** Whether History's per-method rows also show what each method produced (methodProduces). False by default. */
	default boolean historyMethodProduction()
	{
		return false;
	}

	// ---- ADDED (Skills Phase 5) -- inputs a transformation consumes; none by default ----

	/** Player-facing name of a consumed INPUT item (e.g. an essence), or null when it is not one of this skill's inputs. */
	default String inputName(Integer itemId)
	{
		return null;
	}

	/**
	 * Whether a consumed input counts toward the {@link #consumedPhrase()} total
	 * (e.g. essence) rather than being an extra input listed by name (a
	 * catalyst, an extract). True by default.
	 */
	default boolean isPrimaryInput(Integer itemId)
	{
		return true;
	}

	/**
	 * A per-cycle YIELD a skill records as occurrences (key prefix + method key
	 * -> summed over COUNTED cycles only), shown as a per-cycle average
	 * ("RUNES / TRIP"). The first one is a live cell; all of them are History rows.
	 */
	final class CycleYield
	{
		private final String keyPrefix;
		private final String caption;
		private final String historyLabel;

		public CycleYield(String keyPrefix, String caption, String historyLabel)
		{
			this.keyPrefix = keyPrefix;
			this.caption = caption;
			this.historyLabel = historyLabel;
		}

		public String getKeyPrefix()
		{
			return keyPrefix;
		}

		public String getCaption()
		{
			return caption;
		}

		public String getHistoryLabel()
		{
			return historyLabel;
		}
	}

	/** Per-cycle yields; none by default. */
	default List<CycleYield> cycleYields()
	{
		return java.util.Collections.emptyList();
	}

	/** Whether the production breakdown of {@code methodKey} reads as shares of the total ("Nature 18%") rather than counts. */
	default boolean breakdownAsShares(String methodKey)
	{
		return false;
	}

	/** Extra compact production lines a method derives from the session's occurrences (e.g. binds that held); none by default. */
	default List<String> occurrenceLines(String methodKey, java.util.Map<String, Long> occurrences)
	{
		return java.util.Collections.emptyList();
	}

	/** Whether History's compact row shows production next to completed cycles (false: cycles only, as Agility). */
	default boolean historyShowsProducedWithCycles()
	{
		return false;
	}

	/** Phrase after the consumed total on the production line (e.g. "essence used"); null = the skill consumes nothing. */
	default String consumedPhrase()
	{
		return null;
	}

	/** Plural title of a by-product family for its compact line ("gem" -> "Gems").
	 */
	default String groupTitle(String group)
	{
		return Character.toUpperCase(group.charAt(0)) + group.substring(1) + "s";
	}

	// ---- ADDED (Skills Phase 6) -- unit families, actions, inputs, History rows; none by default ----

	/**
	 * The UNIT family a resource belongs to (its production, banking and
	 * stockpile are then counted in units, never items); null = an ordinary
	 * item-counted resource (the default).
	 */
	default UnitFamily unitFamily(SkillResource resource)
	{
		return null;
	}

	/**
	 * Occurrence key prefix of the per-method ACTION count (key prefix + method
	 * key = successful operations of that method), shown as SESSION count +
	 * rate cells; null = the skill records no actions (the default).
	 */
	default String actionKeyPrefix()
	{
		return null;
	}

	/** SESSION count caption for {@code methodKey}'s actions ("MADE"); null = no SESSION cells for that method. */
	default String actionCaption(String methodKey)
	{
		return null;
	}

	/**
	 * INPUTS USED cells for {@code methodKey} from the session's exact consumed
	 * inputs (item id -> quantity); empty = no INPUTS USED section (the default:
	 * the consumed line under PRODUCTION, if any, is used instead).
	 */
	default List<SkillCompanionView.EventView.Field> inputFields(String methodKey, java.util.Map<Integer, Long> consumed,
		java.util.Map<String, Long> occurrences)
	{
		return java.util.Collections.emptyList();
	}

	/** Compact lines under INPUTS USED a method derives from the session's occurrences; none by default. */
	default List<String> inputLines(String methodKey, java.util.Map<String, Long> occurrences)
	{
		return java.util.Collections.emptyList();
	}

	/**
	 * Whether live PRODUCTION's main cell counts only what the CURRENT method
	 * produces ({@link #methodProduces}), with earlier methods' output on a
	 * compact "Also:" line instead of a blended TOTAL. False by default.
	 */
	default boolean productionFollowsMethod()
	{
		return false;
	}

	/** Tooltip on PRODUCTION's BANKED cell. */
	default String bankedTooltip()
	{
		return "Of that, what Spyglass saw you deposit into your bank (never guessed: drops and unproven deposits don't count)";
	}

	/** One finished session's semantic totals, as History reads them (generic maps; never live state). */
	final class SessionTotals
	{
		private final java.util.Map<Integer, Long> produced;
		private final java.util.Map<Integer, Long> banked;
		private final java.util.Map<Integer, Long> consumed;
		private final java.util.Map<String, Long> methodMillis;
		private final java.util.Map<String, Long> occurrences;
		private final long activeMillis;
		private final long skillXp;

		public SessionTotals(java.util.Map<Integer, Long> produced, java.util.Map<Integer, Long> banked, java.util.Map<Integer, Long> consumed,
			java.util.Map<String, Long> methodMillis, java.util.Map<String, Long> occurrences, long activeMillis, long skillXp)
		{
			this.produced = nonNull(produced);
			this.banked = nonNull(banked);
			this.consumed = nonNull(consumed);
			this.methodMillis = nonNull(methodMillis);
			this.occurrences = nonNull(occurrences);
			this.activeMillis = activeMillis;
			this.skillXp = skillXp;
		}

		private static <K> java.util.Map<K, Long> nonNull(java.util.Map<K, Long> m)
		{
			return m == null ? java.util.Collections.<K, Long>emptyMap() : java.util.Collections.unmodifiableMap(m);
		}

		public java.util.Map<Integer, Long> getProduced()
		{
			return produced;
		}

		public java.util.Map<Integer, Long> getBanked()
		{
			return banked;
		}

		public java.util.Map<Integer, Long> getConsumed()
		{
			return consumed;
		}

		public java.util.Map<String, Long> getMethodMillis()
		{
			return methodMillis;
		}

		public java.util.Map<String, Long> getOccurrences()
		{
			return occurrences;
		}

		public long getActiveMillis()
		{
			return activeMillis;
		}

		/** The skill's own XP this session (0 when unknown). */
		public long getSkillXp()
		{
			return skillXp;
		}
	}

	/** One History detail row a skill builds itself: label, right-hand value (or null), tooltip (or null). */
	final class HistoryRow
	{
		private final String label;
		private final String value;
		private final String tooltip;

		public HistoryRow(String label, String value, String tooltip)
		{
			this.label = label;
			this.value = value;
			this.tooltip = tooltip;
		}

		public String getLabel()
		{
			return label;
		}

		public String getValue()
		{
			return value;
		}

		public String getTooltip()
		{
			return tooltip;
		}
	}

	/**
	 * History detail rows that REPLACE the generic produced / banked / consumed
	 * rows (XP and per-method time rows stay generic); null = the generic rows
	 * (the default). For skills whose outputs must never be summed into one
	 * number (each method's own counts and rates).
	 */
	default List<HistoryRow> historyRows(SessionTotals totals)
	{
		return null;
	}

	/**
	 * History list row facts that REPLACE the generic "method • N produced"
	 * (XP stays generic and last); null = the generic facts (the default).
	 */
	default List<String> historyRowFacts(SessionTotals totals)
	{
		return null;
	}

	// ---- ADDED (framework consolidation) -- the chassis' remaining tooltips, so no skill's vocabulary lives in
	// generic code. Every default is skill-neutral; a skill overrides only what it words differently. ----

	/** Tooltip on CURRENT FOCUS's time cell. */
	default String focusTimeTooltip()
	{
		return "Since its first success";
	}

	/** Tooltip on REWARDS' count cell (what counts as collected). */
	default String rewardTooltip()
	{
		return "Collected this session (withdrawals and what you already carried never count)";
	}

	/** Plural noun for the methods that hand out a reward, in REWARDS' rate tooltip ("methods"; Agility: "courses"). */
	default String rewardSourcesNoun()
	{
		return "methods";
	}

	/** Tooltip on the session's failure line (how a failure is evidenced). */
	default String failureTooltip()
	{
		return "Seen from the game itself; never guessed from a missing XP drop";
	}

	/** Tooltip on PRODUCTION's consumed line ({@link #consumedPhrase()}). */
	default String consumedTooltip()
	{
		return "Inputs this session's successes used up (only what Spyglass saw become output)";
	}

	/** Tooltip on PRODUCTION's "Also used" line (inputs that are not {@link #isPrimaryInput primary}). */
	default String otherInputsTooltip()
	{
		return "Other inputs this session's successes used up, by exact name (never added to the main input total)";
	}
}
