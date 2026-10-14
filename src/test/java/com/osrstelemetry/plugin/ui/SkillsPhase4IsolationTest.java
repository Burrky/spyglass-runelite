package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionDefinition;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.agility.AgilityCompanionDefinition;
import com.osrstelemetry.plugin.skills.fishing.FishingCompanionDefinition;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionDefinition;
import com.osrstelemetry.plugin.skills.woodcutting.WoodcuttingCompanionDefinition;
import com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/**
 * Skills Phase 4 left the earlier companions exactly as they were: every
 * new definition hook defaults to "none" for Mining / Fishing / Agility,
 * their panels gain no new section, and the shared chassis classes carry
 * no Woodcutting vocabulary (the auto-transformed / occurrence / focus /
 * event concepts are generic).
 */
public class SkillsPhase4IsolationTest
{
	private static final Gson GSON = new Gson();
	private static final Instant NOW = Instant.parse("2026-10-13T17:00:00Z");

	@Test
	public void earlierCompanions_keepEveryNewHookAtItsDefault()
	{
		for (SkillCompanionDefinition d : Arrays.<SkillCompanionDefinition>asList(MiningCompanionDefinition.INSTANCE,
			FishingCompanionDefinition.INSTANCE, AgilityCompanionDefinition.INSTANCE))
		{
			String n = d.getSkillKey();
			assertNull(n, d.autoTransformedVerb());
			assertNull(n, d.focusTitle());
			assertTrue(n, d.secondaries().isEmpty());
			assertNull(n, d.occurrencesTitle());
			assertFalse(n, d.historyCountsPrimaryOnly());
			assertFalse(n, d.historyMethodProduction());
			assertEquals(n, "Gems", d.groupTitle("gem"));
		}
	}

	@Test
	public void miningPanel_gainsNoNewSection()
	{
		Session s = GSON.fromJson(("{'sessionId':'s-m','activityIdentity':{'activityType':'SKILLING','activityKey':'mining',"
			+ "'displayName':'Mining'},'state':'ACTIVE','startedAt':'2026-10-13T15:58:00Z','lastActiveAt':'2026-10-13T17:00:00Z',"
			+ "'accumulatedActiveDurationMillis':3600000,'aggregates':{'xpGainedBySkill':{'MINING':40000},'lootDrops':[],"
			+ "'producedItems':[{'itemId':440,'itemName':'Iron ore','quantity':900},{'itemId':1623,'itemName':'Uncut sapphire','quantity':3}],"
			+ "'skillStats':{'skillKey':'mining','methodMillis':{'iron':3600000},'lastMethod':'iron'}}}").replace('\'', '"'), Session.class);
		MiningSource src = new MiningSource();
		CurrentSessionView v = new CurrentSessionView(null, null, null, new com.osrstelemetry.plugin.OsrsTelemetryConfig()
		{
		});
		v.setSkillCompanionSource(src);
		v.render(CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW));
		SkillCompanionModel m = v.skillCompanionModel(CurrentSessionSnapshot.from(SessionSnapshot.capture(s), NOW));
		assertNull(m.focus);
		assertNull(m.event);
		assertNull(m.secondary);
		assertNull(m.production.autoTransformedLine);
		assertEquals("gems stay Mining's own by-product line", "Gems: Sapphire 3", m.production.byproducts.get(0));
	}

	static final class MiningSource implements com.osrstelemetry.plugin.skills.SkillCompanionPanelSource
	{
		@Override
		public SkillCompanionDefinition definition()
		{
			return MiningCompanionDefinition.INSTANCE;
		}

		@Override
		public SkillCompanionView view()
		{
			return SkillCompanionView.EMPTY;
		}

		@Override
		public com.osrstelemetry.plugin.skills.SkillGoal goal()
		{
			return com.osrstelemetry.plugin.skills.SkillGoal.DEFAULT;
		}

		@Override
		public void setGoal(com.osrstelemetry.plugin.skills.SkillGoal goal)
		{
		}

		@Override
		public Integer skillXp()
		{
			return null;
		}

		@Override
		public Long bankQuantity(int itemId)
		{
			return null;
		}
	}

	@Test
	public void woodcuttingIsRegisteredAlongsideTheOthers()
	{
		assertEquals(WoodcuttingCompanionDefinition.INSTANCE, com.osrstelemetry.plugin.skills.SkillCompanions.forSkillKey("woodcutting"));
		assertEquals(MiningCompanionDefinition.INSTANCE, com.osrstelemetry.plugin.skills.SkillCompanions.forSkillKey("mining"));
		assertNull(com.osrstelemetry.plugin.skills.SkillCompanions.forSkillKey("firemaking"));
	}

	@Test
	public void sharedChassisClasses_carryNoWoodcuttingVocabulary() throws IOException
	{
		Path root = Paths.get("src/main/java/com/osrstelemetry/plugin");
		List<String> shared = Arrays.asList("skills/ProductionLedger.java", "skills/SkillTracker.java", "skills/SkillCompanionView.java",
			"skills/SkillCompanionDefinition.java", "skills/SkillCompanionCollector.java", "skills/SkillProductionMerge.java",
			"events/EventPayloads.java", "session/SkillProductionUpdate.java", "session/SessionAggregates.java",
			"session/SessionAggregateUpdater.java", "session/SessionSnapshot.java", "session/SessionEventMapper.java",
			"session/ClassifierContext.java", "session/SessionSignalBatchResolver.java", "ui/SkillCompanionModel.java",
			"ui/HistoryDetailView.java", "history/HistoryEntry.java");
		for (String f : shared)
		{
			String text = new String(Files.readAllBytes(root.resolve(f)), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
			for (String word : Collections.unmodifiableList(Arrays.asList("infernal", "forestry", "burned", "auto-burn")))
			{
				assertFalse(f + " mentions " + word, text.contains(word));
			}
			// Pre-existing references to existing regression tests' names (liveBug_...) are not vocabulary.
			assertFalse(f + " mentions woodcutting", text.replaceAll("livebug_[a-z_]*", "").contains("woodcutting"));
		}
	}
}
