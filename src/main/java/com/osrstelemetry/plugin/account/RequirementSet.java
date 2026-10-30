package com.osrstelemetry.plugin.account;

import java.util.Collections;
import java.util.List;

/**
 * A set of account requirements, shared by every consumer (transports,
 * Farming patch access). Plain data: parsed by Gson from catalogue JSON or
 * built in code; {@link RequirementEvaluator} judges it against an
 * {@link AccountCapability}. All lists are "all of".
 */
public final class RequirementSet
{
	public static final class SkillReq
	{
		/** A {@code net.runelite.api.Skill} name. */
		public String skill;
		public int level;

		public static SkillReq of(String skill, int level)
		{
			SkillReq r = new SkillReq();
			r.skill = skill;
			r.level = level;
			return r;
		}
	}

	public static final class QuestReq
	{
		/** A {@code net.runelite.api.Quest} constant name. */
		public String quest;
		/** FINISHED, STARTED (in progress or finished) or PARTIAL (finished = met, not started = unmet, in progress = unknown). */
		public String state;

		public static QuestReq of(String quest, String state)
		{
			QuestReq r = new QuestReq();
			r.quest = quest;
			r.state = state;
			return r;
		}
	}

	public static final class EvidenceReq
	{
		/** Answered by a domain through {@link EvidenceSource}. */
		public String key;
		public String name;
	}

	/**
	 * An unlock the game does not expose directly, established only by proof:
	 * met when any listed fact is observed (each one implies the unlock), and
	 * otherwise UNKNOWN. Never "unmet": the absence of a proof is not evidence
	 * that the unlock is missing.
	 */
	public static final class ProofReq
	{
		public String name;
		/** Quests whose completion implies the unlock. */
		public List<String> anyQuestFinished;
		/** Quests whose start (in progress or finished) implies the unlock. */
		public List<String> anyQuestStarted;
		/** Diary keys whose completion implies the unlock. */
		public List<String> anyDiary;
		/** Where the implication comes from (shown in diagnostics). */
		public String source;
	}

	public List<SkillReq> skills;
	public List<QuestReq> quests;
	/** Diary keys such as {@code LUMBRIDGE_ELITE}. */
	public List<String> diaries;
	/** STANDARD, ANCIENT, LUNAR or ARCEUUS. */
	public String spellbook;
	public List<EvidenceReq> evidence;
	public List<ProofReq> proofs;
	/** False only for content usable on free-to-play worlds; null / true = members only. */
	public Boolean members;

	public static <T> List<T> list(List<T> l)
	{
		return l == null ? Collections.emptyList() : l;
	}

	public boolean membersOnly()
	{
		return members == null || members;
	}
}
