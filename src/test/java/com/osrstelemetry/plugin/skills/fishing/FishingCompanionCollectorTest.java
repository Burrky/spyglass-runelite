package com.osrstelemetry.plugin.skills.fishing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionSnapshot;
import com.osrstelemetry.plugin.skills.SkillCompanionView;
import com.osrstelemetry.plugin.skills.mining.MiningCompanionCollectorAccess;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/**
 * The Fishing companion through the SAME shared collector Mining uses:
 * bounded telemetry to the SKILLING/fishing session only, production no
 * Fishing session claims (Tempoross) dropped, and Mining and Fishing
 * collectors fed the same event stream never cross-route.
 */
public class FishingCompanionCollectorTest
{
	private static final long ACCOUNT = 4_242L;
	private static final Gson GSON = new Gson();

	private final List<Object[]> events = new ArrayList<>();
	private int tick = 100;
	private long now = 1_800_000_000_000L;
	private int fishingXp = 900_000;
	private Session current;
	private final Map<Integer, Long> inv = new LinkedHashMap<>();
	private FishingCompanionCollector fishing;
	private com.osrstelemetry.plugin.skills.mining.MiningCompanionCollector mining;

	@Before
	public void setUp()
	{
		EventLedger ledger = new EventLedger(GSON)
		{
			@Override
			public void append(long accountHash, EventType type, Object payload)
			{
				events.add(new Object[]{type, payload, accountHash});
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
				case "getSkillExperience":
					return 0;
				default:
					return null;
			}
		});
		LocalStateStore store = new LocalStateStore(GSON);
		fishing = new FishingCompanionCollector(client, ledger,
			() -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), store);
		fishing.setClock(() -> now);
		fishing.startUp();
		mining = MiningCompanionCollectorAccess.create(client, ledger,
			() -> current == null ? SessionSnapshot.absent() : SessionSnapshot.capture(current), store, () -> now);
		fishing.onStatChanged(new StatChanged(Skill.FISHING, fishingXp, 80, 80));
		mining.onStatChanged(new StatChanged(Skill.MINING, 400_000, 75, 75));
		pushInventory();
	}

	private static Session session(String id, String type, String key)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'" + type + "','activityKey':'" + key
			+ "','displayName':'X'},'state':'ACTIVE','startedAt':'2026-10-12T16:00:00Z','lastActiveAt':'2026-10-12T16:00:00Z',"
			+ "'accumulatedActiveDurationMillis':600000,'aggregates':{'xpGainedBySkill':{},'lootDrops':[]}}").replace('\'', '"'), Session.class);
	}

	private static ItemContainer container(Map<Integer, Long> items)
	{
		List<Item> list = new ArrayList<>();
		items.forEach((id, q) -> list.add(new Item(id, (int) (long) q)));
		Item[] arr = list.toArray(new Item[0]);
		return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
			(p, m, a) -> "getItems".equals(m.getName()) ? arr : null);
	}

	private void pushInventory()
	{
		inv.values().removeIf(v -> v <= 0);
		ItemContainerChanged e = new ItemContainerChanged(InventoryID.INV, container(inv));
		fishing.onItemContainerChanged(e);
		mining.onItemContainerChanged(e);
	}

	private void tick()
	{
		tick++;
		now += 600;
		fishing.onGameTick(new GameTick());
		mining.onGameTick(new GameTick());
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	/** One lobster, as the client delivers it, to BOTH collectors (they share the event bus). */
	private void catchLobster()
	{
		fishingXp += 90;
		StatChanged xp = new StatChanged(Skill.FISHING, fishingXp, 80, 80);
		fishing.onStatChanged(xp);
		mining.onStatChanged(xp);
		ChatMessage line = new ChatMessage(null, ChatMessageType.SPAM, "", "You catch a lobster.", null, 0);
		fishing.onChatMessage(line);
		mining.onChatMessage(line);
		inv.merge(ItemID.RAW_LOBSTER, 1L, Long::sum);
		pushInventory();
		ticks(3);
	}

	private List<EventPayloads.SkillProduction> updates(String skill)
	{
		List<EventPayloads.SkillProduction> out = new ArrayList<>();
		for (Object[] e : events)
		{
			if (e[0] == EventType.SKILL_PRODUCTION && skill.equals(((EventPayloads.SkillProduction) e[1]).getSkill()))
			{
				out.add((EventPayloads.SkillProduction) e[1]);
			}
		}
		return out;
	}

	private static long qty(List<EventPayloads.ProducedItem> items, int id)
	{
		long q = 0;
		for (EventPayloads.ProducedItem i : items)
		{
			if (i.getItemId() == id)
			{
				q += i.getQuantity();
			}
		}
		return q;
	}

	@Test
	public void fishingProduction_bindsToTheFishingSession_bounded()
	{
		for (int i = 0; i < 12; i++)
		{
			catchLobster();
		}
		assertTrue("no Fishing session yet: nothing sent", updates("FISHING").isEmpty());
		current = session("s-fish", "SKILLING", "fishing");
		ticks(60);
		List<EventPayloads.SkillProduction> u = updates("FISHING");
		assertTrue("bounded: one or two updates, never twelve", u.size() >= 1 && u.size() <= 2);
		long sent = 0;
		for (EventPayloads.SkillProduction p : u)
		{
			sent += qty(p.getProduced(), ItemID.RAW_LOBSTER);
		}
		assertEquals(12L, sent);
		assertEquals("lobster", u.get(0).getMethod());
		SkillCompanionView v = fishing.getView();
		assertEquals("s-fish", v.getBoundSessionId());
		assertEquals("fishing", v.getSkillKey());
	}

	@Test
	public void miningCollector_neverSeesFishingAsProduction()
	{
		current = session("s-fish", "SKILLING", "fishing");
		for (int i = 0; i < 5; i++)
		{
			catchLobster();
		}
		ticks(60);
		assertTrue("Mining sends nothing for fish", updates("MINING").isEmpty());
		assertTrue(mining.getView().getPendingProduced().isEmpty());
		assertNull("Mining never binds to a Fishing session", mining.getView().getBoundSessionId());
		assertNull(mining.getView().getMethodKey());
	}

	@Test
	public void fishingXpInsideTempoross_isDropped_neverMisattributed()
	{
		current = session("s-tempo", "STRUCTURED_ACTIVITY", "tempoross");
		for (int i = 0; i < 3; i++)
		{
			catchLobster();
		}
		ticks(50 * 8);
		current = session("s-fish-later", "SKILLING", "fishing");
		ticks(60);
		long sent = 0;
		for (EventPayloads.SkillProduction u : updates("FISHING"))
		{
			sent += qty(u.getProduced(), ItemID.RAW_LOBSTER);
		}
		assertEquals("never sent to Tempoross, never carried into a later session", 0L, sent);
	}

	@Test
	public void currentXp_isTheAbsoluteFishingXp_perSkill()
	{
		assertEquals(Integer.valueOf(900_000), fishing.currentXp());
		catchLobster();
		assertEquals(Integer.valueOf(900_090), fishing.currentXp());
		assertEquals("Mining's XP is its own", Integer.valueOf(400_000), mining.currentXp());
	}

	// ---- barbarian ownership evidence (cold start) -----------------------------------------------

	private final List<Object[]> notes = new ArrayList<>();

	private void captureEvidence()
	{
		fishing.setMethodEvidenceSink((skill, at) -> notes.add(new Object[]{skill, at}));
	}

	private void line(String message)
	{
		fishing.onChatMessage(new ChatMessage(null, ChatMessageType.SPAM, "", message, null, 0));
	}

	private static net.runelite.api.events.MenuOptionClicked click(String option, int npcId)
	{
		net.runelite.api.NPC npc = (net.runelite.api.NPC) Proxy.newProxyInstance(net.runelite.api.NPC.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.NPC.class}, (p, m, a) -> "getId".equals(m.getName()) ? npcId : null);
		net.runelite.api.MenuEntry entry = (net.runelite.api.MenuEntry) Proxy.newProxyInstance(net.runelite.api.MenuEntry.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.MenuEntry.class}, (p, m, a) ->
			{
				switch (m.getName())
				{
					case "getOption":
						return option;
					case "getTarget":
						return "<col=ffff00>Fishing spot";
					case "getNpc":
						return npc;
					default:
						return null;
				}
			});
		return new net.runelite.api.events.MenuOptionClicked(entry);
	}

	@Test
	public void aBarbarianCatchLine_reportsFishingOwnershipEvidence_withItsTime()
	{
		captureEvidence();
		line("You catch a leaping trout.");
		assertEquals(1, notes.size());
		assertEquals("fishing", notes.get(0)[0]);
		assertEquals(java.time.Instant.ofEpochMilli(now), notes.get(0)[1]);
	}

	@Test
	public void ordinaryCatches_neverReportOwnershipEvidence()
	{
		captureEvidence();
		line("You catch a lobster.");
		line("You catch a trout.");
		line("You catch some shrimps.");
		assertTrue("only a method that also grants other skills' XP needs it", notes.isEmpty());
	}

	@Test
	public void aUseRodClickOnABarbarianSpot_reportsEvidence_otherSpotsDoNot()
	{
		captureEvidence();
		fishing.onMenuOptionClicked(click("Lure", net.runelite.client.game.FishingSpot.SALMON.getIds()[0]));
		assertTrue(notes.isEmpty());
		fishing.onMenuOptionClicked(click("Use-rod", net.runelite.client.game.FishingSpot.BARB_FISH.getIds()[0]));
		assertEquals(1, notes.size());
		assertEquals("fishing", notes.get(0)[0]);
	}

	@Test
	public void evidence_isRateLimited_neverPerCatch()
	{
		captureEvidence();
		line("You catch a leaping trout.");
		now += 1_800;
		line("You catch a leaping salmon.");
		assertEquals("one note per 5 s at most", 1, notes.size());
		now += 4_000;
		line("You catch a leaping sturgeon.");
		assertEquals(2, notes.size());
	}

	@Test
	public void evidenceIsNeverTelemetry_andMiningNeverReportsAny()
	{
		captureEvidence();
		int before = events.size();
		line("You catch a leaping trout.");
		ChatMessage mine = new ChatMessage(null, ChatMessageType.SPAM, "", "You manage to mine some iron.", null, 0);
		mining.onChatMessage(mine);
		assertEquals("no event is written for evidence", before, events.size());
		assertEquals(1, notes.size());
	}

	@Test
	public void theFirstBarbarianCatch_makesTheMethodBarbarian_andCountsOnce()
	{
		current = session("s-fish", "SKILLING", "fishing");
		fishingXp += 50;
		StatChanged xp = new StatChanged(Skill.FISHING, fishingXp, 80, 80);
		fishing.onStatChanged(xp);
		line("You catch a leaping trout.");
		inv.merge(ItemID.BRUT_SPAWNING_TROUT, 1L, Long::sum);
		pushInventory();
		ticks(60);
		long sent = 0;
		for (EventPayloads.SkillProduction u : updates("FISHING"))
		{
			sent += qty(u.getProduced(), ItemID.BRUT_SPAWNING_TROUT);
		}
		assertEquals(1L, sent);
		assertEquals("barbarian", fishing.getView().getMethodKey());
	}
}
