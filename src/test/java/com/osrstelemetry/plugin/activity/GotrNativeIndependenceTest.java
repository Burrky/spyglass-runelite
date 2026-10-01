package com.osrstelemetry.plugin.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * HARD RULE: Spyglass's Guardians of the Rift data comes only from native
 * client state -- the game's own HUD (interface 746) and its update
 * clientscript (5980) arguments, the GotR varbits, game messages, the
 * inventory, Runecraft XP and location -- never from a GotR Plugin Hub
 * plugin, another plugin's overlay or state, or reflection. Native RuneLite
 * API / gameval constants stay allowed.
 */
public class GotrNativeIndependenceTest
{
	private static final String MAIN = "src/main/java/com/osrstelemetry/plugin/";
	private static final String[] GOTR_PATH = {
		"activity/GotrAdvice.java",
		"activity/GotrAltar.java",
		"activity/GotrCandidate.java",
		"activity/GotrPortalTalismans.java",
		"activity/GotrGameState.java",
		"activity/GotrRewardWallet.java",
		"GotrStrategy.java",
		"activity/GotrGameTracker.java",
		"activity/GotrRewardPointTracker.java",
		"activity/GotrRunes.java",
		"activity/ProductionRecipe.java",
		"activity/ProductionTracker.java",
		"collectors/StructuredActivityCollector.java",
		"ui/ProducedItemGrid.java",
		"ui/SlayerStatGrid.java",
		"ui/FactLine.java",
		"ui/CurrentSessionView.java",
		"ui/HistoryDetailView.java",
		"ui/OsrsTelemetryPanel.java",
	};
	private static final String[] FORBIDDEN = {
		"net.runelite.client.plugins.", "PluginManager", "OverlayManager", "InfoBoxManager",
		"setAccessible(", "getDeclaredField(", "getDeclaredMethod(", "Class.forName(", "java.lang.reflect",
		"com.datbear", "gotrprogressbar", "com.hawolt", "gotrrunetracker", "guardiansoftherift", "gotroptimizer",
	};

	private static String code(String relative) throws Exception
	{
		Path p = Paths.get(MAIN + relative);
		String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
		// Code only: prose may name other plugins (e.g. as evidence of the game's text).
		return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	@Test
	public void gotrPath_importsNoOtherPlugin_andUsesNoReflection() throws Exception
	{
		for (String file : GOTR_PATH)
		{
			String src = code(file);
			for (String forbidden : FORBIDDEN)
			{
				assertFalse(file + " must not use " + forbidden, src.contains(forbidden));
			}
		}
	}

	@Test
	public void collector_readsTheGamesOwnGotrState() throws Exception
	{
		String src = code("collectors/StructuredActivityCollector.java");
		assertTrue(src.contains("InterfaceID.GotrHud.CONTENT"));
		assertTrue(src.contains("VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME"));
		assertTrue(src.contains("VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME"));
		assertTrue(src.contains("VarbitID.GOTR_IS_PLAYING"));
		assertTrue(src.contains("ScriptPreFired"));
		assertTrue("power is read from script arguments, never the HUD's power text",
			!src.contains("GotrHud.ENERGY_TITLE"));
	}

	@Test
	public void openRifts_eligibility_andWallet_comeFromTheGameItself() throws Exception
	{
		String collector = code("collectors/StructuredActivityCollector.java");
		assertTrue("open rifts: the HUD script's own arguments", collector.contains("GOTR_SCRIPT_ARG_ACTIVE_ELEMENTAL")
			&& collector.contains("GOTR_SCRIPT_ARG_ACTIVE_CATALYTIC"));
		assertTrue("eligibility: the player's own level and quest state", collector.contains("getBoostedSkillLevel(Skill.RUNECRAFT)")
			&& collector.contains("getState(client)"));
		String altar = code("activity/GotrAltar.java");
		assertTrue(altar.contains("import net.runelite.api.Quest;"));
		String advice = code("activity/GotrAdvice.java");
		assertFalse("advice is pure: no client, no network", advice.contains("Client") || advice.contains("http"));
	}

	@Test
	public void runeRecipe_usesGamevalItemIds() throws Exception
	{
		String src = code("activity/GotrRunes.java");
		assertTrue(src.contains("ItemID.GOTR_GUARDIAN_ESSENCE"));
		assertTrue(src.contains("import net.runelite.api.gameval.ItemID;"));
	}

	@Test
	public void portalTalismans_andRunePouch_areReadNatively() throws Exception
	{
		String talismans = code("activity/GotrPortalTalismans.java");
		assertTrue("portal talismans: gameval item ids, never guessed numbers",
			talismans.contains("ItemID.GOTR_PORTAL_TALISMAN_BLOOD") && !talismans.matches("(?s).*\\b2689[0-9]\\b.*"));
		String runes = code("activity/GotrRunes.java");
		assertTrue("rune pouch: the game's own slot varbits", runes.contains("VarbitID.RUNE_POUCH_TYPE_1")
			&& runes.contains("VarbitID.RUNE_POUCH_QUANTITY_6"));
		String collector = code("collectors/StructuredActivityCollector.java");
		assertTrue("pouch rune types: the game's RUNEPOUCH_RUNE enum", collector.contains("EnumID.RUNEPOUCH_RUNE"));
		assertTrue("talismans come from the inventory container already observed", collector.contains("GotrPortalTalismans.heldIn(inventory)"));
		assertFalse("no Rune Pouch / Inventory Setup plugin", collector.contains("runepouch.") || collector.contains("inventorysetups"));
		String tracker = code("activity/ProductionTracker.java");
		assertFalse("correlation is pure: no client, no timers, no sleeps",
			tracker.contains("Client") || tracker.contains("Thread.sleep") || tracker.contains("Timer"));
	}
}
