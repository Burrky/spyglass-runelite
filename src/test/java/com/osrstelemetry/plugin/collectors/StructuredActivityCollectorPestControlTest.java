package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.activity.GotrGameState;
import com.osrstelemetry.plugin.activity.StructuredActivityCatalog;
import com.osrstelemetry.plugin.activity.StructuredActivityRegistry;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

/**
 * Pest Control through the real collector (live-QA repair, 2026-10-01): the
 * win arrives as NPC dialogue (ChatMessageType.DIALOG, "Squire|..."), twice;
 * it must become exactly one completion plus its exact commendation award.
 */
public class StructuredActivityCollectorPestControlTest
{
	private static final long ACCOUNT = 4242L;
	private static final int GOTR_REGION = 14484;
	private static final int ESS = ItemID.GOTR_GUARDIAN_ESSENCE;

	private final List<Object[]> events = new ArrayList<>();
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, String> widgetTexts = new HashMap<>();
	private boolean hudLoaded;
	private int tick = 100;
	private Map<Integer, Long> inventory = new HashMap<>();
	private StructuredActivityCollector collector;

	@Before
	public void setUp()
	{
		EventLedger ledger = new EventLedger(new Gson())
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload});
			}
		};
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getAccountHash":
					return ACCOUNT;
				case "getTickCount":
					return tick;
				case "getGameState":
					return GameState.LOGGED_IN;
				case "getVarbitValue":
					return a.length == 1 ? varbits.getOrDefault((Integer) a[0], 0) : 0;
				case "getWidget":
					if (a.length == 1 && a[0] instanceof Integer)
					{
						int id = (Integer) a[0];
						if (id == InterfaceID.GotrHud.CONTENT)
						{
							return hudLoaded ? widget(null) : null;
						}
						return widgetTexts.containsKey(id) ? widget(widgetTexts.get(id)) : null;
					}
					return null;
				case "getItemContainer":
					return container(inventory);
				default:
					return defaultValue(m.getReturnType());
			}
		});
		collector = new StructuredActivityCollector(client, id -> "item" + id, ledger, StructuredActivityRegistry.defaults());
		varbits.put(VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME, 0);
		varbits.put(VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME, 0);
	}

	private static Object defaultValue(Class<?> t)
	{
		if (t == boolean.class)
		{
			return false;
		}
		if (t == long.class)
		{
			return 0L;
		}
		if (t.isPrimitive() && t != void.class)
		{
			return 0;
		}
		return null;
	}

	private static Widget widget(String text)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(p, m, a) -> "getText".equals(m.getName()) ? text : defaultValue(m.getReturnType()));
	}

	private static ItemContainer container(Map<Integer, Long> items)
	{
		Item[] arr = new Item[items.size()];
		int i = 0;
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			arr[i++] = new Item(e.getKey(), (int) (long) e.getValue());
		}
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
			(p, m, a) -> "getItems".equals(m.getName()) ? arr : defaultValue(m.getReturnType()));
	}

	private void enterGotr()
	{
		collector.onRegion(GOTR_REGION);
		events.clear();
	}

	private void energy(int elemental, int catalytic)
	{
		varbits.put(VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME, elemental);
		varbits.put(VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME, catalytic);
		tick++;
		collector.trackGotrTick();
	}

	private void inventory(long... idQty)
	{
		inventory = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			if (idQty[i + 1] > 0)
			{
				inventory.put((int) idQty[i], idQty[i + 1]);
			}
		}
		collector.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, container(inventory)));
	}

	private void runecraftXp(int xp)
	{
		collector.onStatChanged(new StatChanged(Skill.RUNECRAFT, xp, 77, 77));
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> payloads(EventType type)
	{
		List<T> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == type)
			{
				out.add((T) e[1]);
			}
		}
		return out;
	}

	private static final String LIVE_WIN = "Congratulations! You managed to destroy all the portals! We've awarded you 8 Void Knight "
		+ "Commendation points. Please also accept these coins as a reward.";

	private void chat(net.runelite.api.ChatMessageType type, String name, String message)
	{
		collector.onChatMessage(new net.runelite.api.events.ChatMessage(null, type, name, message, "", 0));
	}

	private void dialog(String message)
	{
		chat(net.runelite.api.ChatMessageType.DIALOG, "", message);
	}

	private int completions()
	{
		return payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION).size();
	}

	private long points()
	{
		long total = 0;
		for (Object o : payloads(EventType.STRUCTURED_ACTIVITY_CURRENCY))
		{
			EventPayloads.StructuredActivityCurrency c = (EventPayloads.StructuredActivityCurrency) o;
			assertEquals(StructuredActivityCatalog.PEST_CONTROL, c.getActivityKey());
			assertEquals("commendation_points", c.getCurrency());
			total += c.getGained();
		}
		return total;
	}

	@Test
	public void exactLiveWinDialogue_isOneGameWon_plusItsEightPoints()
	{
		dialog("Squire|" + LIVE_WIN);
		List<EventPayloads.StructuredActivityCompletion> done = payloads(EventType.STRUCTURED_ACTIVITY_COMPLETION);
		assertEquals(1, done.size());
		assertEquals(StructuredActivityCatalog.PEST_CONTROL, done.get(0).getActivityKey());
		assertNull("a win line carries no running count", done.get(0).getCompletionCount());
		assertEquals(8, points());
	}

	@Test
	public void speakerInTheNameField_orTagged_alsoMatches()
	{
		chat(net.runelite.api.ChatMessageType.DIALOG, "Squire", "<col=000080>" + LIVE_WIN + "</col>");
		assertEquals(1, completions());
		assertEquals(8, points());
	}

	@Test
	public void theDuplicateDeliveryTwoSecondsLater_isTheSameWin()
	{
		dialog("Squire|" + LIVE_WIN);
		tick += 4; // live: 23:45:38 and 23:45:40
		dialog("Squire|" + LIVE_WIN);
		assertEquals(1, completions());
		assertEquals("never 16", 8, points());
	}

	@Test
	public void theNextGame_isASecondWin()
	{
		dialog("Squire|" + LIVE_WIN);
		tick += 300; // a whole new game later
		dialog("Squire|" + LIVE_WIN.replace("8 Void", "10 Void"));
		assertEquals(2, completions());
		assertEquals(18, points());
	}

	@Test
	public void awardAmountsAreParsed_neverHardcoded()
	{
		dialog("Squire|" + LIVE_WIN.replace("8 Void", "5 Void"));
		tick += 300;
		dialog("Squire|" + LIVE_WIN.replace("8 Void", "1,234 Void"));
		tick += 300;
		dialog("Squire|" + LIVE_WIN.replace("8 Void Knight Commendation points", "1 Void Knight Commendation point"));
		assertEquals(3, completions());
		assertEquals(5 + 1234 + 1, points());
	}

	@Test
	public void lossesAndUnrelatedPointsMessages_neverCount()
	{
		dialog("Squire|You failed to kill all the portals in 20 minutes. The Void Knight was killed. You have lost the game.");
		dialog("Squire|You must defend the Void Knight while the portals are unsummoned.");
		chat(net.runelite.api.ChatMessageType.MESBOX, "",
			"<col=000080>You now have <col=800000>11<col=000080> Void Knight Commendation points! You can speak to a Void Knight to exchange your points for rewards.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You have been awarded 8 points.");
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You've earned 8 Slayer reward points.");
		dialog("Some NPC|We've awarded you 8 reward points.");
		chat(net.runelite.api.ChatMessageType.PUBLICCHAT, "someone", LIVE_WIN);
		assertEquals(0, completions());
		assertEquals(0, points());
	}

	@Test
	public void dialogueNeverReachesTheGameMessageParsers()
	{
		dialog("Rewards Guardian|You found some loot: 50 x Nature rune");
		dialog("Someone|Amount of rifts you have closed: 99.");
		assertTrue(events.isEmpty());
	}

	@Test
	public void theSameWinAsAGameMessage_isStillOneGame()
	{
		chat(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", LIVE_WIN);
		dialog("Squire|" + LIVE_WIN);
		assertEquals(1, completions());
		assertEquals(8, points());
	}
}
