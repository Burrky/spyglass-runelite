package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanions;
import com.osrstelemetry.plugin.skills.SkillResource;
import com.osrstelemetry.plugin.skills.agility.AgilityCompanionDefinition;
import com.osrstelemetry.plugin.skills.fishing.FishingCompanionDefinition;
import com.osrstelemetry.plugin.skills.herblore.HerbloreCompanionDefinition;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import com.osrstelemetry.plugin.skills.runecraft.RunecraftCompanionDefinition;
import com.osrstelemetry.plugin.skills.woodcutting.WoodcuttingCompanionDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import org.junit.Test;

/**
 * Skills Phase 6 (Herblore) left the earlier companions exactly as they
 * were: every new hook (unit families, actions, input cells, production
 * following the method, History rows / facts) is at its default for Mining /
 * Fishing / Agility / Woodcutting / Runecraft, and the shared chassis classes
 * carry no Herblore vocabulary.
 */
public class SkillsPhase6IsolationTest
{
	@Test
	public void earlierCompanions_keepEveryPhase6HookAtItsDefault()
	{
		for (SkillCompanionDefinition d : Arrays.<SkillCompanionDefinition>asList(MiningCompanionDefinition.INSTANCE,
			FishingCompanionDefinition.INSTANCE, AgilityCompanionDefinition.INSTANCE, WoodcuttingCompanionDefinition.INSTANCE,
			RunecraftCompanionDefinition.INSTANCE))
		{
			String n = d.getSkillKey();
			assertNull(n, d.actionKeyPrefix());
			assertNull(n, d.actionCaption("x"));
			assertFalse(n, d.productionFollowsMethod());
			assertTrue(n, d.inputFields("x", Collections.singletonMap(1, 1L), Collections.<String, Long>emptyMap()).isEmpty());
			assertTrue(n, d.inputLines("x", Collections.singletonMap("x", 1L)).isEmpty());
			assertNull(n, d.historyRows(new SkillCompanionDefinition.SessionTotals(null, null, null, null, null, 0L, 0L)));
			assertNull(n, d.historyRowFacts(new SkillCompanionDefinition.SessionTotals(null, null, null, null, null, 0L, 0L)));
			for (SkillResource r : d.stockpileResources(null))
			{
				assertNull(n, d.unitFamily(r));
			}
			assertNull(n, d.unitFamily(new SkillResource(1, "x", null, SkillResource.Role.PRIMARY, null, false)));
		}
		assertTrue(SkillCompanions.forSkillKey("herblore") == HerbloreCompanionDefinition.INSTANCE);
	}

	@Test
	public void sharedChassisClasses_carryNoHerbloreVocabulary() throws IOException
	{
		for (String f : Arrays.asList("skills/ProductionLedger.java", "skills/SkillTracker.java", "skills/SkillCompanionView.java",
			"skills/SkillCompanionCollector.java", "skills/SkillCompanionDefinition.java", "skills/SkillCompanionPanelSource.java",
			"skills/UnitFamily.java", "skills/SkillExternalStock.java", "skills/SkillGoalPlanner.java", "skills/SkillBanking.java",
			"events/EventPayloads.java", "session/SkillProductionUpdate.java", "session/SessionAggregates.java",
			"session/SessionAggregateUpdater.java", "session/SessionSnapshot.java", "session/SessionEventMapper.java",
			"ui/SkillCompanionModel.java", "history/HistoryEntry.java"))
		{
			String text = new String(Files.readAllBytes(Paths.get("src/main/java/com/osrstelemetry/plugin").resolve(f)), StandardCharsets.UTF_8)
				.toLowerCase(Locale.ROOT);
			for (String word : Arrays.asList("herblore", "super restore", "snapdragon", "potion storage", "potion", "mixology", "dose", "herb"))
			{
				assertFalse(f + " mentions " + word, text.contains(word));
			}
		}
	}
}
