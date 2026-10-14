package com.osrstelemetry.plugin.skills;

/**
 * What Current Session reads to render a skill companion (generic; read at
 * render time on the EDT). Live state comes from the skill's collector as an
 * immutable {@link SkillCompanionView}; the TARGET is account planning state.
 */
public interface SkillCompanionPanelSource
{
	SkillCompanionDefinition definition();

	SkillCompanionView view();

	SkillGoal goal();

	void setGoal(SkillGoal goal);

	/** The skill's current absolute XP (for a level target); null when unknown. */
	Integer skillXp();

	/** What the account's bank holds of an item; null when the bank has never been observed. */
	Long bankQuantity(int itemId);

	/** ADDED (Skills Phase 6). An account storage outside the bank for a stockpile resource; NOT_APPLICABLE by default. */
	default SkillExternalStock externalStock(SkillResource resource)
	{
		return SkillExternalStock.NOT_APPLICABLE;
	}
}
