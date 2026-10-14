package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.agility.AgilityCompanionDefinition;
import com.osrstelemetry.plugin.skills.fishing.FishingCompanionDefinition;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
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
 * Skills Phase 5 (Runecraft) left the earlier companions exactly as they
 * were: every new hook (consumed inputs, cycle yields, mix shares,
 * occurrence lines) defaults to "none" for Mining / Fishing / Agility /
 * Woodcutting, and the shared chassis classes carry no Runecraft vocabulary.
 */
public class SkillsPhase5IsolationTest
{
	@Test
	public void earlierCompanions_keepEveryPhase5HookAtItsDefault()
	{
		for (SkillCompanionDefinition d : Arrays.<SkillCompanionDefinition>asList(MiningCompanionDefinition.INSTANCE,
			FishingCompanionDefinition.INSTANCE, AgilityCompanionDefinition.INSTANCE, WoodcuttingCompanionDefinition.INSTANCE))
		{
			String n = d.getSkillKey();
			assertNull(n, d.consumedPhrase());
			assertNull(n, d.inputName(1436));
			assertTrue(n, d.isPrimaryInput(1));
			assertTrue(n, d.cycleYields().isEmpty());
			assertFalse(n, d.breakdownAsShares(null));
			assertTrue(n, d.occurrenceLines(null, Collections.singletonMap("x", 1L)).isEmpty());
			assertFalse(n, d.historyShowsProducedWithCycles());
		}
	}

	@Test
	public void sharedChassisClasses_carryNoRunecraftVocabulary() throws IOException
	{
		for (String f : Arrays.asList("skills/ProductionLedger.java", "skills/SkillTracker.java", "skills/SkillCompanionView.java",
			"skills/SkillCompanionCollector.java", "events/EventPayloads.java", "session/SkillProductionUpdate.java",
			"session/SessionAggregates.java", "session/SessionAggregateUpdater.java", "session/SessionSnapshot.java",
			"session/SessionEventMapper.java", "ui/SkillCompanionModel.java", "history/HistoryEntry.java"))
		{
			String text = new String(Files.readAllBytes(Paths.get("src/main/java/com/osrstelemetry/plugin").resolve(f)), StandardCharsets.UTF_8)
				.toLowerCase(Locale.ROOT);
			for (String word : Arrays.asList("runecraft", "pouch", "ourania", "arceuus", "talisman"))
			{
				assertFalse(f + " mentions " + word, text.contains(word));
			}
		}
	}
}
