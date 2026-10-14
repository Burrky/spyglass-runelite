package com.osrstelemetry.plugin.skills;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An immutable read of one skill companion's LIVE state for Current Session
 * (generic). Durable session numbers come from the session itself; this
 * carries only what the session does not know yet or cannot know:
 *
 * <ul>
 * <li>the current METHOD;</li>
 * <li>production / banking / method time the companion already sent for
 * {@link #getBoundSessionId()} ("flushed": the session applies it a tick
 * later) and what it has not sent yet ("pending");</li>
 * <li>CURRENT TRIP (capacity, observed fill rate);</li>
 * <li>a few method context lines;</li>
 * <li>banking samples for a stockpile ETA.</li>
 * </ul>
 *
 * Merging rule (see {@link SkillProductionMerge}): for the session it is
 * bound to, a resource's live total is max(session, flushed) + pending -- so
 * the display never dips during the one-tick hand-off and never double
 * counts once the session caught up.
 */
public final class SkillCompanionView
{
	public static final SkillCompanionView EMPTY = new SkillCompanionView(null, null, null, Collections.<Integer, Long>emptyMap(),
		Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), Collections.<Integer, Long>emptyMap(),
		Collections.<Integer, Long>emptyMap(), Collections.<String, Long>emptyMap(), null, Collections.<ContextLine>emptyList(),
		Collections.<Integer, SkillGoalPlanner.BankingSample>emptyMap());

	/** One short method-specific line ("Water flowing: +15% success"). */
	public static final class ContextLine
	{
		public enum Tone
		{
			NORMAL,
			ALERT,
			QUIET
		}

		private final String text;
		private final Tone tone;
		private final String tooltip;

		public ContextLine(String text, Tone tone, String tooltip)
		{
			this.text = text;
			this.tone = tone == null ? Tone.NORMAL : tone;
			this.tooltip = tooltip;
		}

		public String getText()
		{
			return text;
		}

		public Tone getTone()
		{
			return tone;
		}

		public String getTooltip()
		{
			return tooltip;
		}
	}

	/** CURRENT TRIP: the capacity that limits this trip and how soon it fills at the observed rate. */
	public static final class TripView
	{
		private final Integer spaceLeft;
		private final Long estFullMillis;
		private final String storageName;
		private final Integer storageUsed;
		private final Integer storageCapacity;
		private final int storageAddedThisTrip;
		private final boolean storageCatching;

		public TripView(Integer spaceLeft, Long estFullMillis, String storageName, Integer storageUsed, Integer storageCapacity,
			int storageAddedThisTrip, boolean storageCatching)
		{
			this.spaceLeft = spaceLeft;
			this.estFullMillis = estFullMillis;
			this.storageName = storageName;
			this.storageUsed = storageUsed;
			this.storageCapacity = storageCapacity;
			this.storageAddedThisTrip = storageAddedThisTrip;
			this.storageCatching = storageCatching;
		}

		private boolean estIncludesStorage;

		/**
		 * The same trip, with EST. FULL computed over EFFECTIVE capacity (free
		 * inventory slots plus the open storage's KNOWN free room for what is
		 * being produced) at the observed production rate.
		 */
		public TripView withEstIncludingStorage()
		{
			TripView copy = new TripView(spaceLeft, estFullMillis, storageName, storageUsed, storageCapacity, storageAddedThisTrip,
				storageCatching);
			copy.estIncludesStorage = true;
			return copy;
		}

		public boolean isEstIncludesStorage()
		{
			return estIncludesStorage;
		}

		/** Free inventory slots; null = not observed yet. */
		public Integer getSpaceLeft()
		{
			return spaceLeft;
		}

		/** Until the inventory is full at the recent slot-fill rate; null = not enough observation yet. */
		public Long getEstFullMillis()
		{
			return estFullMillis;
		}

		/** Specialized storage carried ("Gem bag"), or null. */
		public String getStorageName()
		{
			return storageName;
		}

		/** Known contents; null while unknown. */
		public Integer getStorageUsed()
		{
			return storageUsed;
		}

		public Integer getStorageCapacity()
		{
			return storageCapacity;
		}

		public int getStorageAddedThisTrip()
		{
			return storageAddedThisTrip;
		}

		/** The storage is open and catching production before it reaches the inventory. */
		public boolean isStorageCatching()
		{
			return storageCatching;
		}
	}

	private final String skillKey;
	private final String methodKey;
	private final String boundSessionId;
	private final Map<Integer, Long> flushedProduced;
	private final Map<Integer, Long> flushedBanked;
	private final Map<String, Long> flushedMethodMillis;
	private final Map<Integer, Long> pendingProduced;
	private final Map<Integer, Long> pendingBanked;
	private final Map<String, Long> pendingMethodMillis;
	private final TripView trip;
	private final List<ContextLine> context;
	private final Map<Integer, SkillGoalPlanner.BankingSample> banking;

	public SkillCompanionView(String skillKey, String methodKey, String boundSessionId, Map<Integer, Long> flushedProduced,
		Map<Integer, Long> flushedBanked, Map<String, Long> flushedMethodMillis, Map<Integer, Long> pendingProduced,
		Map<Integer, Long> pendingBanked, Map<String, Long> pendingMethodMillis, TripView trip, List<ContextLine> context,
		Map<Integer, SkillGoalPlanner.BankingSample> banking)
	{
		this.skillKey = skillKey;
		this.methodKey = methodKey;
		this.boundSessionId = boundSessionId;
		this.flushedProduced = flushedProduced;
		this.flushedBanked = flushedBanked;
		this.flushedMethodMillis = flushedMethodMillis;
		this.pendingProduced = pendingProduced;
		this.pendingBanked = pendingBanked;
		this.pendingMethodMillis = pendingMethodMillis;
		this.trip = trip;
		this.context = context;
		this.banking = banking;
	}

	public String getSkillKey()
	{
		return skillKey;
	}

	/**
	 * SUPPLIES: the current method's consumable, what is usable now and its
	 * runway at the observed consumption (see SupplyTracker). Null when the
	 * method uses none (or none is known yet).
	 */
	public static final class SupplyView
	{
		private final String name;
		private final long count;
		private final Long runwayMillis;

		public SupplyView(String name, long count, Long runwayMillis)
		{
			this.name = name;
			this.count = count;
			this.runwayMillis = runwayMillis;
		}

		/** Natural name for the cell caption ("Karambwanji", "Feathers"). */
		public String getName()
		{
			return name;
		}

		public long getCount()
		{
			return count;
		}

		/** Null = not enough observation yet. */
		public Long getRunwayMillis()
		{
			return runwayMillis;
		}
	}

	private SupplyView supply;

	/** The same view plus a SUPPLIES reading (skills whose methods consume something). */
	public SkillCompanionView withSupply(SupplyView supplyView)
	{
		SkillCompanionView copy = copy();
		copy.supply = supplyView;
		return copy;
	}

	private SkillCompanionView copy()
	{
		SkillCompanionView copy = new SkillCompanionView(skillKey, methodKey, boundSessionId, flushedProduced, flushedBanked,
			flushedMethodMillis, pendingProduced, pendingBanked, pendingMethodMillis, trip, context, banking);
		copy.supply = supply;
		copy.cycle = cycle;
		copy.flushedCycles = flushedCycles;
		copy.pendingCycles = pendingCycles;
		copy.flushedAutoTransformed = flushedAutoTransformed;
		copy.pendingAutoTransformed = pendingAutoTransformed;
		copy.flushedOccurrences = flushedOccurrences;
		copy.pendingOccurrences = pendingOccurrences;
		copy.focus = focus;
		copy.event = event;
		copy.flushedConsumed = flushedConsumed;
		copy.pendingConsumed = pendingConsumed;
		return copy;
	}

	private Map<Integer, Long> flushedConsumed = Collections.emptyMap();
	private Map<Integer, Long> pendingConsumed = Collections.emptyMap();

	/** ADDED (Skills Phase 5). The same view plus consumed inputs (sent / not sent yet). */
	public SkillCompanionView withConsumed(Map<Integer, Long> flushed, Map<Integer, Long> pending)
	{
		SkillCompanionView copy = copy();
		copy.flushedConsumed = flushed == null ? Collections.<Integer, Long>emptyMap() : flushed;
		copy.pendingConsumed = pending == null ? Collections.<Integer, Long>emptyMap() : pending;
		return copy;
	}

	public Map<Integer, Long> getFlushedConsumed()
	{
		return flushedConsumed;
	}

	public Map<Integer, Long> getPendingConsumed()
	{
		return pendingConsumed;
	}

	// ---- ADDED (Skills Phase 4) -------------------------------------------------------

	/**
	 * The CURRENT FOCUS of the skill (the one resource node being worked): what
	 * it is, how much it gave so far and for how long. Never a remaining /
	 * depletion estimate.
	 */
	public static final class FocusView
	{
		private final String label;
		private final long produced;
		private final Long elapsedMillis;

		public FocusView(String label, long produced, Long elapsedMillis)
		{
			this.label = label;
			this.produced = produced;
			this.elapsedMillis = elapsedMillis;
		}

		public String getLabel()
		{
			return label;
		}

		/** Production from this focus so far (every destination, auto-transformed included). */
		public long getProduced()
		{
			return produced;
		}

		/** Null before the first success on it. */
		public Long getElapsedMillis()
		{
			return elapsedMillis;
		}
	}

	/**
	 * A CONTEXTUAL EVENT running right now (inside the skill's session):
	 * its name, how long it has run, and a few fields of its own. Shown only
	 * while it runs; never a separate session.
	 */
	public static final class EventView
	{
		/** One caption / value cell (value null = not known yet). */
		public static final class Field
		{
			private final String caption;
			private final String value;
			private final String tooltip;

			public Field(String caption, String value, String tooltip)
			{
				this.caption = caption;
				this.value = value;
				this.tooltip = tooltip;
			}

			public String getCaption()
			{
				return caption;
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

		private final String key;
		private final String name;
		private final long elapsedMillis;
		private final List<Field> fields;
		private final List<ContextLine> lines;
		/** Section title overriding the definition's eventTitle(); null = the definition's. */
		private String title;

		/** The same event under its own section title (e.g. a phase of the method rather than an event). */
		public EventView withTitle(String sectionTitle)
		{
			EventView copy = new EventView(key, name, elapsedMillis, fields, lines);
			copy.title = sectionTitle;
			return copy;
		}

		public String getTitle()
		{
			return title;
		}

		public EventView(String key, String name, long elapsedMillis, List<Field> fields, List<ContextLine> lines)
		{
			this.key = key;
			this.name = name;
			this.elapsedMillis = elapsedMillis;
			this.fields = fields == null ? Collections.<Field>emptyList() : Collections.unmodifiableList(fields);
			this.lines = lines == null ? Collections.<ContextLine>emptyList() : Collections.unmodifiableList(lines);
		}

		public String getKey()
		{
			return key;
		}

		public String getName()
		{
			return name;
		}

		public long getElapsedMillis()
		{
			return elapsedMillis;
		}

		public List<Field> getFields()
		{
			return fields;
		}

		public List<ContextLine> getLines()
		{
			return lines;
		}
	}

	private Map<Integer, Long> flushedAutoTransformed = Collections.emptyMap();
	private Map<Integer, Long> pendingAutoTransformed = Collections.emptyMap();
	private Map<String, Long> flushedOccurrences = Collections.emptyMap();
	private Map<String, Long> pendingOccurrences = Collections.emptyMap();
	private FocusView focus;
	private EventView event;

	/** The same view plus auto-transformed production, occurrences, the current focus and a running event. */
	public SkillCompanionView withExtras(Map<Integer, Long> flushedTransformed, Map<Integer, Long> pendingTransformed,
		Map<String, Long> flushedCounted, Map<String, Long> pendingCounted, FocusView currentFocus, EventView runningEvent)
	{
		SkillCompanionView copy = copy();
		copy.flushedAutoTransformed = flushedTransformed == null ? Collections.<Integer, Long>emptyMap() : flushedTransformed;
		copy.pendingAutoTransformed = pendingTransformed == null ? Collections.<Integer, Long>emptyMap() : pendingTransformed;
		copy.flushedOccurrences = flushedCounted == null ? Collections.<String, Long>emptyMap() : flushedCounted;
		copy.pendingOccurrences = pendingCounted == null ? Collections.<String, Long>emptyMap() : pendingCounted;
		copy.focus = currentFocus;
		copy.event = runningEvent;
		return copy;
	}

	public Map<Integer, Long> getFlushedAutoTransformed()
	{
		return flushedAutoTransformed;
	}

	public Map<Integer, Long> getPendingAutoTransformed()
	{
		return pendingAutoTransformed;
	}

	public Map<String, Long> getFlushedOccurrences()
	{
		return flushedOccurrences;
	}

	public Map<String, Long> getPendingOccurrences()
	{
		return pendingOccurrences;
	}

	/** Null when the skill has no focus or none is current. */
	public FocusView getFocus()
	{
		return focus;
	}

	/** Null when no contextual event runs. */
	public EventView getEvent()
	{
		return event;
	}

	/**
	 * The CURRENT CYCLE of a cycle-based skill (Agility: the current lap):
	 * which method it belongs to, route progress (denominator null when the
	 * method has no fixed count), elapsed time while a counted cycle runs, and
	 * whether its start was observed (an unsynchronised cycle never counts).
	 */
	public static final class CycleView
	{
		private final String methodKey;
		private final int progress;
		private final Integer denominator;
		private final Long elapsedMillis;
		private final boolean synced;

		public CycleView(String methodKey, int progress, Integer denominator, Long elapsedMillis, boolean synced)
		{
			this.methodKey = methodKey;
			this.progress = progress;
			this.denominator = denominator;
			this.elapsedMillis = elapsedMillis;
			this.synced = synced;
		}

		public String getMethodKey()
		{
			return methodKey;
		}

		public int getProgress()
		{
			return progress;
		}

		public Integer getDenominator()
		{
			return denominator;
		}

		/** Null when no counted cycle is running. */
		public Long getElapsedMillis()
		{
			return elapsedMillis;
		}

		public boolean isSynced()
		{
			return synced;
		}
	}

	private CycleView cycle;
	private Map<String, SkillCycleStats> flushedCycles = Collections.emptyMap();
	private Map<String, SkillCycleStats> pendingCycles = Collections.emptyMap();

	/** The same view plus cycle state (cycle-based skills only). */
	public SkillCompanionView withCycles(CycleView current, Map<String, SkillCycleStats> flushed, Map<String, SkillCycleStats> pending)
	{
		SkillCompanionView copy = copy();
		copy.cycle = current;
		copy.flushedCycles = flushed == null ? Collections.<String, SkillCycleStats>emptyMap() : flushed;
		copy.pendingCycles = pending == null ? Collections.<String, SkillCycleStats>emptyMap() : pending;
		return copy;
	}

	/** Null for skills without cycles, or before a method is known. */
	public CycleView getCycle()
	{
		return cycle;
	}

	public Map<String, SkillCycleStats> getFlushedCycles()
	{
		return flushedCycles;
	}

	public Map<String, SkillCycleStats> getPendingCycles()
	{
		return pendingCycles;
	}

	/** Null when the current method uses no consumable. */
	public SupplyView getSupply()
	{
		return supply;
	}

	public String getMethodKey()
	{
		return methodKey;
	}

	public String getBoundSessionId()
	{
		return boundSessionId;
	}

	public Map<Integer, Long> getFlushedProduced()
	{
		return flushedProduced;
	}

	public Map<Integer, Long> getFlushedBanked()
	{
		return flushedBanked;
	}

	public Map<String, Long> getFlushedMethodMillis()
	{
		return flushedMethodMillis;
	}

	public Map<Integer, Long> getPendingProduced()
	{
		return pendingProduced;
	}

	public Map<Integer, Long> getPendingBanked()
	{
		return pendingBanked;
	}

	public Map<String, Long> getPendingMethodMillis()
	{
		return pendingMethodMillis;
	}

	/** Null when the method has no meaningful trip (or none is known yet). */
	public TripView getTrip()
	{
		return trip;
	}

	public List<ContextLine> getContext()
	{
		return context;
	}

	/** Banking observed this session per resource (stockpile ETA), keyed by item id. */
	public Map<Integer, SkillGoalPlanner.BankingSample> getBanking()
	{
		return banking;
	}
}
