package com.osrstelemetry.plugin.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One skill companion update (EventType.SKILL_PRODUCTION) as the session
 * pipeline sees it: which SKILLING session it belongs to (by skill key,
 * e.g. "mining"), produced and banked quantities, and method time.
 * Immutable.
 */
public final class SkillProductionUpdate
{
	private final String skillKey;
	private final String method;
	private final List<SessionSignal.LootDrop> produced;
	private final List<SessionSignal.LootDrop> banked;
	private final Map<String, Long> methodMillis;
	/** Completed cycles per method (Agility laps); empty for skills without cycles. */
	private Map<String, SessionAggregates.CycleAggregate> cycles = Collections.emptyMap();

	public SkillProductionUpdate(String skill, String method, List<SessionSignal.LootDrop> produced, List<SessionSignal.LootDrop> banked,
		Map<String, Long> methodMillis)
	{
		this.skillKey = Objects.requireNonNull(skill, "skill").trim().toLowerCase(Locale.ROOT);
		this.method = method;
		this.produced = produced == null ? Collections.<SessionSignal.LootDrop>emptyList() : Collections.unmodifiableList(produced);
		this.banked = banked == null ? Collections.<SessionSignal.LootDrop>emptyList() : Collections.unmodifiableList(banked);
		this.methodMillis = methodMillis == null ? Collections.<String, Long>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(methodMillis));
	}

	/** The SKILLING session key this update belongs to ("mining"): the same key ActivitySignalClassifier.skillingIdentity() uses. */
	public String getSkillKey()
	{
		return skillKey;
	}

	public String getMethod()
	{
		return method;
	}

	public List<SessionSignal.LootDrop> getProduced()
	{
		return produced;
	}

	public List<SessionSignal.LootDrop> getBanked()
	{
		return banked;
	}

	public Map<String, Long> getMethodMillis()
	{
		return methodMillis;
	}

	/** The same update with its completed cycles (method -> totals of this update). */
	public SkillProductionUpdate withCycles(Map<String, SessionAggregates.CycleAggregate> completed)
	{
		SkillProductionUpdate copy = new SkillProductionUpdate(skillKey, method, produced, banked, methodMillis);
		copy.cycles = completed == null ? Collections.<String, SessionAggregates.CycleAggregate>emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(completed));
		copy.autoTransformed = autoTransformed;
		copy.occurrences = occurrences;
		copy.consumed = consumed;
		return copy;
	}

	public Map<String, SessionAggregates.CycleAggregate> getCycles()
	{
		return cycles;
	}

	/** Part of produced a tool transformed at once; empty for skills without such a tool. */
	private List<SessionSignal.LootDrop> autoTransformed = Collections.emptyList();
	/** Contextual occurrences per kind key; empty for skills without them. */
	private Map<String, Long> occurrences = Collections.emptyMap();

	/** The same update with its auto-transformed part of production and its contextual occurrences. */
	public SkillProductionUpdate withExtras(List<SessionSignal.LootDrop> transformed, Map<String, Long> counted)
	{
		SkillProductionUpdate copy = new SkillProductionUpdate(skillKey, method, produced, banked, methodMillis);
		copy.cycles = cycles;
		copy.autoTransformed = transformed == null ? Collections.<SessionSignal.LootDrop>emptyList() : Collections.unmodifiableList(transformed);
		copy.consumed = consumed;
		copy.occurrences = counted == null ? Collections.<String, Long>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(counted));
		return copy;
	}

	/** Inputs a transformation used up (Skills Phase 5); empty for skills without them. */
	private List<SessionSignal.LootDrop> consumed = Collections.emptyList();

	/** The same update with its consumed inputs. */
	public SkillProductionUpdate withConsumed(List<SessionSignal.LootDrop> used)
	{
		SkillProductionUpdate copy = new SkillProductionUpdate(skillKey, method, produced, banked, methodMillis);
		copy.cycles = cycles;
		copy.autoTransformed = autoTransformed;
		copy.occurrences = occurrences;
		copy.consumed = used == null ? Collections.<SessionSignal.LootDrop>emptyList() : Collections.unmodifiableList(used);
		return copy;
	}

	public List<SessionSignal.LootDrop> getConsumed()
	{
		return consumed;
	}

	public List<SessionSignal.LootDrop> getAutoTransformed()
	{
		return autoTransformed;
	}

	public Map<String, Long> getOccurrences()
	{
		return occurrences;
	}

	public boolean isEmpty()
	{
		return produced.isEmpty() && banked.isEmpty() && methodMillis.isEmpty() && cycles.isEmpty() && autoTransformed.isEmpty()
			&& occurrences.isEmpty() && consumed.isEmpty();
	}
}
