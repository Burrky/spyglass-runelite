package com.osrstelemetry.plugin.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * Judges a {@link RequirementSet} against an {@link AccountCapability} and a
 * domain {@link EvidenceSource}. Pure. Generic: transports and Farming patch
 * access use the same rules.
 *
 * <ul>
 *   <li>A fact Spyglass has not read is "missing evidence", never unmet.</li>
 *   <li>Skill levels are real (unboosted) levels.</li>
 *   <li>A PARTIAL quest requirement in progress is missing evidence (the needed
 *   stage is not observable).</li>
 *   <li>A diary tier with no verified completion signal is missing evidence.</li>
 *   <li>A proof requirement is met by any proof and otherwise missing evidence.</li>
 *   <li>Members-only content on a free-to-play world is unmet.</li>
 * </ul>
 */
public final class RequirementEvaluator
{
	private RequirementEvaluator()
	{
	}

	public static RequirementCheck check(RequirementSet req, AccountCapability cap, EvidenceSource evidence)
	{
		RequirementCheck out = new RequirementCheck();
		if (req == null)
		{
			return out;
		}
		EvidenceSource ev = evidence == null ? EvidenceSource.NONE : evidence;
		if (req.membersOnly())
		{
			Known members = cap.getMembersWorld();
			if (members == Known.NO)
			{
				out.unmet("Members world (on a free-to-play world)");
			}
			else if (members == Known.UNKNOWN)
			{
				out.missing("Members world: world type not read");
			}
		}
		for (RequirementSet.SkillReq s : RequirementSet.list(req.skills))
		{
			Integer level = cap.level(Skill.valueOf(s.skill));
			String label = title(s.skill) + " " + s.level;
			if (level == null)
			{
				out.missing(label + ": skill levels not loaded yet");
			}
			else if (level < s.level)
			{
				out.unmet(label + " (have " + level + ", unboosted)");
			}
			else
			{
				out.met(label);
			}
		}
		for (RequirementSet.QuestReq q : RequirementSet.list(req.quests))
		{
			quest(Quest.valueOf(q.quest), q.state, cap, out);
		}
		for (String key : RequirementSet.list(req.diaries))
		{
			diary(key, cap, out);
		}
		if (req.spellbook != null)
		{
			AccountReaders.Spellbook book = cap.spellbook();
			String label = title(req.spellbook) + " spellbook";
			if (book == null)
			{
				Integer raw = cap.getSpellbookRaw();
				out.missing(label + ": " + (raw == null ? "current spellbook not read yet" : "current spellbook value " + raw + " is not a known book"));
			}
			else if (book.name().equals(req.spellbook))
			{
				out.met(label);
			}
			else
			{
				out.unmet(label + " (on " + title(book.name()) + ")");
			}
		}
		for (RequirementSet.EvidenceReq e : RequirementSet.list(req.evidence))
		{
			Known v = variable(e.key, cap);
			Known k = v != null ? v : ev.get(e.key);
			String why = v != null ? (v == Known.UNKNOWN ? "not read yet" : "read from the game") : ev.explain(e.key);
			if (k == Known.YES)
			{
				out.met(e.name + " (" + why + ")");
			}
			else if (k == Known.NO)
			{
				out.unmet(e.name + " (" + why + ")");
			}
			else
			{
				out.missing(e.name + ": " + why);
			}
		}
		for (RequirementSet.ProofReq p : RequirementSet.list(req.proofs))
		{
			proof(p, cap, out);
		}
		return out;
	}

	/**
	 * An account variable as evidence: {@code varp:<id>&<mask>} (any bit set), {@code varbit:<id>=<value>} or
	 * {@code varbit:<id>><value>}, {@code varbit:<id><<value>}; UNKNOWN when not read. Null for any other key.
	 */
	static Known variable(String key, AccountCapability cap)
	{
		java.util.regex.Matcher m = key == null ? null : java.util.regex.Pattern.compile("^(varp|varbit):(\\d+)([&=><])(\\d+)$").matcher(key);
		if (m == null || !m.find())
		{
			return null;
		}
		int id = Integer.parseInt(m.group(2));
		int arg = Integer.parseInt(m.group(4));
		Integer value = cap == null ? null : "varp".equals(m.group(1)) ? cap.varp(id) : cap.varbit(id);
		if (value == null)
		{
			return Known.UNKNOWN;
		}
		switch (m.group(3))
		{
			case "&":
				return Known.of((value & arg) != 0);
			case ">":
				return Known.of(value > arg);
			case "<":
				return Known.of(value < arg);
			default:
				return Known.of(value == arg);
		}
	}

	private static void quest(Quest quest, String required, AccountCapability cap, RequirementCheck out)
	{
		QuestState st = cap.quest(quest);
		String label = quest.getName();
		if (st == null)
		{
			out.missing(label + ": quest state not read yet" + (cap.isSettled() ? "" : " (account data not confirmed loaded)"));
			return;
		}
		switch (required)
		{
			case "FINISHED":
				if (st == QuestState.FINISHED)
				{
					out.met(label);
				}
				else
				{
					out.unmet(label + " not complete (" + st + ")");
				}
				break;
			case "STARTED":
				if (st == QuestState.NOT_STARTED)
				{
					out.unmet(label + " not started");
				}
				else
				{
					out.met(label + " started");
				}
				break;
			default: // PARTIAL
				if (st == QuestState.FINISHED)
				{
					out.met(label);
				}
				else if (st == QuestState.NOT_STARTED)
				{
					out.unmet(label + " not started");
				}
				else
				{
					out.missing(label + " in progress: whether the needed stage is reached is not observable");
				}
				break;
		}
	}

	static Known diary(String key, AccountCapability cap, RequirementCheck out)
	{
		String label = diaryLabel(key) + " diary";
		if (AccountReaders.diaryVarbit(key) == null)
		{
			out.missing(label + ": no verified completion signal for this tier");
			return Known.UNKNOWN;
		}
		Known k = cap.diary(key);
		if (k == Known.YES)
		{
			out.met(label);
		}
		else if (k == Known.NO)
		{
			out.unmet(label + " not complete");
		}
		else if (AccountReaders.isProofOnly(key) && cap.isSettled())
		{
			out.missing(label + ": not provable (only a claimed diary reward proves completion)");
		}
		else
		{
			out.missing(label + ": not read yet");
		}
		return k;
	}

	private static void proof(RequirementSet.ProofReq p, AccountCapability cap, RequirementCheck out)
	{
		List<String> proofs = new ArrayList<>();
		for (String q : RequirementSet.list(p.anyQuestFinished))
		{
			Quest quest = Quest.valueOf(q);
			proofs.add(quest.getName() + " complete");
			if (cap.quest(quest) == QuestState.FINISHED)
			{
				out.met(p.name + " (proven by " + quest.getName() + ")");
				return;
			}
		}
		for (String q : RequirementSet.list(p.anyQuestStarted))
		{
			Quest quest = Quest.valueOf(q);
			proofs.add(quest.getName() + " started");
			QuestState st = cap.quest(quest);
			if (st == QuestState.IN_PROGRESS || st == QuestState.FINISHED)
			{
				out.met(p.name + " (proven by " + quest.getName() + " started)");
				return;
			}
		}
		for (String d : RequirementSet.list(p.anyDiary))
		{
			proofs.add(diaryLabel(d) + " diary");
			if (cap.diary(d) == Known.YES)
			{
				out.met(p.name + " (proven by the " + diaryLabel(d) + " diary)");
				return;
			}
		}
		out.missing(p.name + ": not observable directly; no proof seen yet (any of: " + String.join(", ", proofs) + ")");
	}

	public static String diaryLabel(String key)
	{
		return title(key.replace('_', ' '));
	}

	public static String title(String s)
	{
		String lower = s.toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}
}
