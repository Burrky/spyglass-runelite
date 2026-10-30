package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

/**
 * Phase H refinement: Find on the preparation page is passive. It never types
 * into the bank search, runs a client script, sends a menu action, changes a
 * varp/varc/widget, switches a bank tab or otherwise acts in the game. It only
 * marks item ids for the bank outline (drawing) and explains where Spyglass
 * last saw the item.
 */
public class FindIsPassiveTest
{
	private static final String[] FORBIDDEN = {
		"BankSearch", "setVarcStrValue", "setVarcIntValue", "runScript", "menuAction", "invokeMenuAction", "MenuAction", "setVarbit",
		"setVarpValue", "setText(", "setHidden(", "setScrollY", "revalidateScroll", "clientThread.invoke", "VarClientID", "KeyEvent", "Robot",
		"layoutBank", "initSearch", "setItemId", "setItemQuantity",
	};

	private static String code(String path) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	@Test
	public void theServiceAndTheOverlay_holdNoGameActionAtAll() throws Exception
	{
		for (String f : new String[]{"src/main/java/com/osrstelemetry/plugin/FarmingPreparationService.java",
			"src/main/java/com/osrstelemetry/plugin/prep/PreparationBankOverlay.java"})
		{
			String code = code(f);
			for (String forbidden : FORBIDDEN)
			{
				assertFalse(f + " contains " + forbidden, code.contains(forbidden));
			}
		}
	}

	@Test
	public void focus_touchesNothingButItsOwnField()
	{
		List<String> calls = new ArrayList<>();
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			calls.add(m.getName());
			return null;
		});
		FarmingPreparationService service = new FarmingPreparationService(client, null, null, null, null, new FarmingPlanWorker());
		service.focus(new int[]{5295, 21483});
		service.focus(null);
		service.focus(new int[]{5295});
		assertEquals("Find made no client call at all: " + calls, 0, calls.size());
		assertTrue("not open, no view: the overlay draws nothing extra", service.focus().isEmpty());
	}

	@Test
	public void readingTheOpenBankView_onlyReadsWidgets()
	{
		List<String> calls = new ArrayList<>();
		Widget[] children = new Widget[3];
		int[][] slots = {{5295, 30, 0}, {21483, 5, 1}, {952, 1, 0}};
		for (int i = 0; i < slots.length; i++)
		{
			int[] slot = slots[i];
			children[i] = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, a) ->
			{
				calls.add(m.getName());
				switch (m.getName())
				{
					case "getItemId":
						return slot[0];
					case "getItemQuantity":
						return slot[1];
					case "isHidden":
						return slot[2] == 1;
					default:
						return null;
				}
			});
		}
		Widget items = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (p, m, a) ->
		{
			calls.add(m.getName());
			return m.getName().equals("getChildren") ? children : null;
		});
		Set<Integer> visible = FarmingPreparationService.visibleItems(items);
		assertEquals("hidden slots (another tab) are not in view", new HashSet<>(Arrays.asList(5295, 952)), visible);
		for (String c : calls)
		{
			assertTrue("only reads widgets, never changes them: " + c, c.startsWith("get") || c.startsWith("is"));
		}
	}
}
