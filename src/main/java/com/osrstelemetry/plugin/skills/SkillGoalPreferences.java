package com.osrstelemetry.plugin.skills;

import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Account-scoped skill companion TARGETS, one per skill, in this account's
 * own {@code skill_goals.json} (the per-account LocalStateStore pattern of
 * PestControlGoalPreferences / WintertodtPreferences). PLANNING state only:
 * nothing here ever reaches events.jsonl, and a target change is never
 * telemetry.
 */
@Singleton
public class SkillGoalPreferences
{
	static final String CATEGORY = "skill_goals";
	private static final long NO_ACCOUNT = -1L;

	/** Exactly what is persisted per skill; the family as a string so a renamed constant degrades to the default. */
	static final class GoalDocument
	{
		String family;
		int targetLevel;
		Integer resourceItemId;
		int targetQuantity;
	}

	static final class Document
	{
		Map<String, GoalDocument> goals = new LinkedHashMap<>();
	}

	private final LocalStateStore store;
	private long accountHash = NO_ACCOUNT;
	private Document document = new Document();

	@Inject
	public SkillGoalPreferences(LocalStateStore store)
	{
		this.store = store;
	}

	/** Login / account switch (a same-account call is a no-op). */
	public synchronized void loadForAccount(long accountHash)
	{
		if (accountHash == this.accountHash)
		{
			return;
		}
		this.accountHash = accountHash;
		Document loaded = store.readIfExists(TelemetryPaths.stateFile(accountHash, CATEGORY), Document.class);
		document = loaded != null ? loaded : new Document();
		if (document.goals == null)
		{
			document.goals = new LinkedHashMap<>();
		}
	}

	/** The skill's target; {@link SkillGoal#DEFAULT} when none was ever chosen. */
	public synchronized SkillGoal getGoal(String skillKey)
	{
		GoalDocument g = skillKey == null ? null : document.goals.get(key(skillKey));
		if (g == null)
		{
			return SkillGoal.DEFAULT;
		}
		return new SkillGoal(parseFamily(g.family), g.targetLevel, g.resourceItemId, g.targetQuantity);
	}

	public synchronized void setGoal(String skillKey, SkillGoal goal)
	{
		if (skillKey == null || goal == null || goal.equals(getGoal(skillKey)))
		{
			return;
		}
		GoalDocument g = new GoalDocument();
		g.family = goal.getFamily().name();
		g.targetLevel = goal.getTargetLevel();
		g.resourceItemId = goal.getResourceItemId();
		g.targetQuantity = goal.getTargetQuantity();
		document.goals.put(key(skillKey), g);
		if (accountHash != NO_ACCOUNT)
		{
			store.write(TelemetryPaths.stateFile(accountHash, CATEGORY), document);
		}
	}

	private static String key(String skillKey)
	{
		return skillKey.trim().toLowerCase(Locale.ROOT);
	}

	private static SkillGoal.Family parseFamily(String name)
	{
		if (name == null)
		{
			return SkillGoal.Family.LEVEL;
		}
		try
		{
			return SkillGoal.Family.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return SkillGoal.Family.LEVEL;
		}
	}
}
