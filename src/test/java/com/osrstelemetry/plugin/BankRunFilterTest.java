package com.osrstelemetry.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The player-clicked bank filter (Farming correction pass): activation only by
 * the player's click, an obvious way back, automatic reset whenever the player
 * picks another view or closes the bank, and nothing but display: no menu
 * action, typed search text, varbit/varp/varc write, withdrawal or other
 * plugin required.
 */
public class BankRunFilterTest
{
	private static Set<Integer> ids(Integer... i)
	{
		return new HashSet<>(Arrays.asList(i));
	}

	@Test
	public void onlyTheClick_turnsItOn_andTheSameClickTurnsItOff()
	{
		BankRunFilter.State s = new BankRunFilter.State();
		s.setItems(ids(5295, 21483));
		assertFalse("never on by itself", s.isActive());
		assertFalse("bank closed: refused", s.toggle(false));
		s.bankOpen(true);
		assertTrue(s.isAvailable());
		assertFalse("potion store open: refused", s.toggle(true));
		assertTrue(s.toggle(false));
		assertTrue(s.matches(5295));
		assertFalse(s.matches(952));
		assertFalse("the same button returns the normal view", s.toggle(false));
	}

	@Test
	public void anyOtherView_orClosingTheBank_returnsToNormal()
	{
		BankRunFilter.State s = new BankRunFilter.State();
		s.setItems(ids(5295));
		s.bankOpen(true);
		s.toggle(false);
		assertTrue(s.deactivate());
		assertFalse(s.isActive());
		s.toggle(false);
		s.bankOpen(false);
		assertFalse("bank closed", s.isActive());
		s.bankOpen(true);
		assertFalse("reopening the bank never re-applies it", s.isActive());
	}

	@Test
	public void theItemSetFollowsThePlan_andAChangeAsksForARelayoutOnlyWhileActive()
	{
		BankRunFilter.State s = new BankRunFilter.State();
		s.bankOpen(true);
		assertFalse("inactive: no relayout", s.setItems(ids(1, 2)));
		s.toggle(false);
		assertFalse("unchanged: no relayout", s.setItems(ids(1, 2)));
		assertTrue("withdrew one: the view updates", s.setItems(ids(2)));
		assertFalse(s.matches(1));
		s.setItems(Collections.emptySet());
		assertFalse("nothing left: not offered", s.isAvailable());
		BankRunFilter.State empty = new BankRunFilter.State();
		empty.bankOpen(true);
		assertFalse("nothing to show: refused", empty.toggle(false));
	}

	@Test
	public void theFilter_isDisplayOnly_andNeedsNoOtherPlugin() throws Exception
	{
		String code = new String(Files.readAllBytes(Paths.get("src/main/java/com/osrstelemetry/plugin/BankRunFilter.java")), StandardCharsets.UTF_8)
			.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
		for (String forbidden : new String[]{"menuAction", "invokeMenuAction", "setVarbit", "setVarpValue", "setVarcStrValue", "setVarcIntValue",
			"VarClientID", "KeyEvent", "Robot", "BankSearch", "initSearch", "setItemId", "setItemQuantity", "MESSAGE_LAYER", "banktags", "questhelper",
			"BankTagsService", "PluginManager"})
		{
			assertFalse("BankRunFilter contains " + forbidden, code.contains(forbidden));
		}
		// The only client script it runs is the bank's own layout (its item container's onInvTransmit listener).
		Matcher m = Pattern.compile("runScript\\(([^)]*)\\)").matcher(code);
		int calls = 0;
		while (m.find())
		{
			calls++;
			assertEquals("only the bank's own layout script", "args", m.group(1).trim());
		}
		assertEquals(1, calls);
		assertTrue(code.contains("getOnInvTransmitListener()"));
	}

	@Test
	public void theIcon_sitsInTheTitleBar_clearOfTheTitleAndTheCloseButton_orNowhere()
	{
		java.awt.Rectangle bar = new java.awt.Rectangle(100, 50, 480, 22);
		java.awt.Rectangle text = new java.awt.Rectangle(250, 50, 180, 22);
		java.awt.Rectangle close = new java.awt.Rectangle(552, 51, 26, 20);
		java.awt.Rectangle help = new java.awt.Rectangle(104, 52, 18, 18);
		java.awt.Rectangle s = BankRunFilter.slot(bar, java.util.Arrays.asList(text, close, help), 20);
		org.junit.Assert.assertNotNull(s);
		org.junit.Assert.assertTrue("inside the bar: " + s, bar.contains(s));
		for (java.awt.Rectangle r : new java.awt.Rectangle[]{text, close, help})
		{
			org.junit.Assert.assertFalse("clear of " + r + ": " + s, r.intersects(s));
		}
		org.junit.Assert.assertTrue("left of the title", s.x + s.width <= text.x);
		// A long title (a bank value) leaving no room on the left: the right side, before the close button.
		java.awt.Rectangle wide = new java.awt.Rectangle(110, 50, 400, 22);
		java.awt.Rectangle r = BankRunFilter.slot(bar, java.util.Arrays.asList(wide, close), 20);
		org.junit.Assert.assertNotNull(r);
		org.junit.Assert.assertTrue(r.x >= wide.x + wide.width && r.x + r.width <= close.x);
		// No room anywhere: no place at all (hidden), never over the items.
		org.junit.Assert.assertNull(BankRunFilter.slot(bar, java.util.Collections.singletonList(bar), 20));
	}
}
