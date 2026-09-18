package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/**
 * NOTE: written, not run in this file — same "written, not run" caveat
 * this project applies to every Java test (no JDK/RuneLite client jar
 * available in the cloud sandbox that authored this file; see plugin/
 * README.md and LootCollectorTest's identical note). The actual
 * `./gradlew test` run is Josh's to perform on his own machine.
 *
 * Targets ServerNpcLootCollector's pure, Client/EventLedger/RuneLite-
 * API-independent helpers (buildPayload(), isPickpocketMessage(),
 * shouldSuppress()) — this project has no Mockito dependency, so
 * NPCComposition/ItemStack/Client/ServerNpcLoot/ChatMessage are never
 * mocked, mirroring LootCollectorTest/NpcDeathCollectorTest's existing
 * pattern exactly.
 *
 * Guarantees that are architectural rather than
 * unit-testable in isolation without mocking RuneLite's EventBus (this
 * project deliberately avoids depending on that for tests), documented
 * here instead of faked as a test — see points 14/15 below.
 */
public class ServerNpcLootCollectorTest
{
	private static final Gson GSON = new Gson();

	private static EventPayloads.ServerNpcLootItem item(int itemId, String itemName, int quantity)
	{
		return new EventPayloads.ServerNpcLootItem(itemId, itemName, quantity);
	}

	// --- 1/8. one notification -> one payload containing all item stacks, original grouping preserved ---

	@Test
	public void buildPayload_oneNotification_preservesAllItemStacksTogether()
	{
		List<EventPayloads.ServerNpcLootItem> items = Arrays.asList(
			item(4151, "Abyssal whip", 1),
			item(995, "Coins", 42),
			item(1631, "Ensouled goblin head", 1)
		);

		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload("Gargoyle", 111, items);

		assertEquals(3, payload.getItems().size());
		assertEquals(items, payload.getItems());
	}

	// --- 2. source name preserved ---

	@Test
	public void buildPayload_preservesSourceName()
	{
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", 111, Collections.singletonList(item(4151, "Abyssal whip", 1)));

		assertEquals("Gargoyle", payload.getSourceName());
	}

	// --- 3. source id preserved when valid ---

	@Test
	public void buildPayload_preservesValidSourceId()
	{
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", 111, Collections.singletonList(item(4151, "Abyssal whip", 1)));

		assertEquals(Integer.valueOf(111), payload.getSourceId());
	}

	// --- 4. unavailable/invalid source id not fabricated ---

	@Test
	public void buildPayload_negativeSourceId_leftNullNotFabricated()
	{
		// NPCComposition#getId() carries no documented guarantee of
		// always returning a real value — mirroring NpcDeathCollector.
		// buildPayload()'s identical treatment of NPC#getId().
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", -1, Collections.singletonList(item(4151, "Abyssal whip", 1)));

		assertNull(payload.getSourceId());
	}

	@Test
	public void buildPayload_zeroSourceId_leftNullNotFabricated()
	{
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", 0, Collections.singletonList(item(4151, "Abyssal whip", 1)));

		assertNull(payload.getSourceId());
	}

	// --- 5/6/7. item id/name/quantity preserved ---

	@Test
	public void buildPayload_preservesItemIdNameAndQuantity()
	{
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Chicken", 41, Collections.singletonList(item(995, "Coins", 42)));

		EventPayloads.ServerNpcLootItem only = payload.getItems().get(0);
		assertEquals(995, only.getItemId());
		assertEquals("Coins", only.getItemName());
		assertEquals(42, only.getQuantity());
	}

	@Test
	public void buildPayload_itemNameNull_passedThroughNotFabricated()
	{
		// An ItemManager lookup that fails to resolve a name must not be
		// papered over with a placeholder like "Unknown" — pass-through,
		// not a decision, same discipline as LootCollector.buildPayload().
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", 111, Collections.singletonList(item(4151, null, 1)));

		assertNull(payload.getItems().get(0).getItemName());
	}

	// --- 9. empty/null-shaped input does not fabricate loot ---

	@Test
	public void buildPayload_emptyItemList_notFabricatedIntoNonEmptyLoot()
	{
		// A ServerNpcLoot notification with no items is not RuneLite
		// behavior this collector invented a workaround for — whatever
		// the notification actually carried (including nothing) is what
		// the payload carries; an empty items list must stay empty, not
		// be treated as "no event" (that decision belongs to the caller
		// deciding whether to call buildPayload() at all, not to this
		// pure function) and never padded with an invented item.
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", 111, Collections.emptyList());

		assertTrue(payload.getItems().isEmpty());
	}

	@Test
	public void buildPayload_nullSourceName_passedThroughNotFabricated()
	{
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			null, 111, Collections.singletonList(item(4151, "Abyssal whip", 1)));

		assertNull(payload.getSourceName());
	}

	// --- 10. serializes through the project's actual Gson mechanism ---

	@Test
	public void payload_roundTripsThroughGson_sameSerializerEventLedgerUses()
	{
		// EventLedger.append() serializes the payload with a plain
		// `new Gson()` (see EventLedger.java) — this test uses the same
		// mechanism directly on the payload object, isolated from
		// Client/EventBus/file I/O, mirroring LootCollectorTest's
		// identical pattern.
		EventPayloads.ServerNpcLoot payload = ServerNpcLootCollector.buildPayload(
			"Gargoyle", 111, Arrays.asList(item(4151, "Abyssal whip", 1), item(995, "Coins", 42)));

		String json = GSON.toJson(payload);
		EventPayloads.ServerNpcLoot roundTripped = GSON.fromJson(json, EventPayloads.ServerNpcLoot.class);

		assertEquals(payload, roundTripped);
		assertTrue("serialized JSON should carry the item name", json.contains("Abyssal whip"));
		assertTrue("serialized JSON should carry the source name", json.contains("Gargoyle"));
	}

	// --- helper: build an "armed" suppression state for a given tick/identity ---

	private static ServerNpcLootCollector.PickpocketSuppressionState armed(int tick, Integer npcId, String npcName)
	{
		return ServerNpcLootCollector.PickpocketSuppressionState.armed(tick, npcId, npcName);
	}

	private static ServerNpcLootCollector.PickpocketSuppressionState notArmed()
	{
		return ServerNpcLootCollector.PickpocketSuppressionState.notArmed();
	}

	// --- 11. same tick + SAME NPC identity (by id) => duplicate pickpocket-style loot stays suppressed ---

	@Test
	public void shouldSuppress_sameTickSameNpcIdById_suppressed()
	{
		// The pickpocket message was observed while interacting with NPC
		// id 41 ("Chicken"); the ServerNpcLoot notification on the same
		// tick is for the same id — this is the ordinary "avoid a
		// duplicate pickpocket-loot notification" case and must stay
		// suppressed exactly as RuneLite's own tick-only guard already
		// suppressed it.
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(12345, 41, "Chicken");
		assertTrue(ServerNpcLootCollector.shouldSuppress(state, 12345, "Chicken", 41));
	}

	@Test
	public void shouldSuppress_sameTickSameNpcNameOnly_suppressed_whenIdsUnavailable()
	{
		// Neither side has a resolvable positive id (e.g. getId()
		// returned an unresolved/non-positive value) — falls back to a
		// normalized name comparison and still suppresses the duplicate.
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(500, null, "Man");
		assertTrue(ServerNpcLootCollector.shouldSuppress(state, 500, "Man", 0));
	}

	// --- 12. same tick + DIFFERENT NPC/source kill loot => NOT suppressed (the actual fix) ---

	@Test
	public void shouldSuppress_sameTickDifferentNpcById_notSuppressed()
	{
		// This is the live defect being guarded against: an unrelated
		// pickpocket message (e.g. for a "Man") landing on the same
		// tick as a genuine, different NPC's ("Gargoyle") kill loot must
		// not silently discard that kill's ServerNpcLoot. Applies
		// uniformly to any NPC name — nothing Bandit-specific here.
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(777, 41, "Man");
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 777, "Gargoyle", 111));
	}

	@Test
	public void shouldSuppress_sameTickDifferentNpcByNameOnly_notSuppressed()
	{
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(900, null, "Man");
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 900, "Gargoyle", 0));
	}

	@Test
	public void shouldSuppress_idsDiffer_evenWhenNamesHappenToMatch_notSuppressed()
	{
		// Id comparison takes precedence over name when both sides have
		// a resolvable id — two different-id sources sharing a display
		// name (a generic/common NPC name) must not be treated as the
		// same NPC merely because the names happen to match.
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(42, 41, "Guard");
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 42, "Guard", 99));
	}

	// --- 13. ordinary NPC kill with no pickpocket signal at all => SERVER_NPC_LOOT emitted (not suppressed) ---

	@Test
	public void shouldSuppress_noPickpocketSignalAtAll_ordinaryKillLootNotSuppressed()
	{
		ServerNpcLootCollector.PickpocketSuppressionState state = notArmed();
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 0, "Gargoyle", 111));
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 1, "Gargoyle", 111));
	}

	// --- 14. no identity captured at pickpocket time (interaction target unresolvable) => safe tick-only fallback ---

	@Test
	public void shouldSuppress_noIdentityCapturedAtArmTime_fallsBackToTickOnlySuppression()
	{
		// getInteracting() returned null/non-NPC at the exact moment the
		// pickpocket message was observed -- both npcId and npcName are
		// null on the armed state. The original "never allow duplicate
		// pickpocket loot" guarantee still holds in this narrow case: a
		// same-tick ServerNpcLoot for ANY source is suppressed, exactly
		// as the pre-fix tick-only guard behaved.
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(2024, null, null);
		assertTrue(ServerNpcLootCollector.shouldSuppress(state, 2024, "Gargoyle", 111));
		assertTrue(ServerNpcLootCollector.shouldSuppress(state, 2024, "Chicken", 41));
	}

	// --- 15. tick rollover / stale suppression state cannot suppress a later unrelated kill ---

	@Test
	public void shouldSuppress_staleArmedStateFromEarlierTick_neverSuppressesLaterUnrelatedKill()
	{
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(100, 41, "Chicken");
		// Even for the SAME NPC identity, a different (later, or
		// wrapped-around) tick must never be treated as "still armed" --
		// the tick check is the first, unconditional gate.
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 101, "Chicken", 41));
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, Integer.MAX_VALUE, "Chicken", 41));
	}

	// --- 16. multiple NPCs on the same tick do not contaminate each other's suppression state ---

	@Test
	public void shouldSuppress_multipleNpcsSameTick_noCrossContamination()
	{
		// Suppression state captured for one NPC ("Man") on a given tick
		// must resolve independently per candidate ServerNpcLoot source
		// checked against it: the matching NPC stays suppressed, every
		// other NPC checked on that same tick is not.
		ServerNpcLootCollector.PickpocketSuppressionState state = armed(55, 41, "Man");
		assertTrue(ServerNpcLootCollector.shouldSuppress(state, 55, "Man", 41));
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 55, "Gargoyle", 111));
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 55, "Chicken", 7));
	}

	@Test
	public void shouldSuppress_notArmedSentinel_neverEqualsRealTick()
	{
		// NOT_ARMED_TICK (-1) must never accidentally suppress a
		// legitimate tick-0 (or any other real, non-negative)
		// ServerNpcLoot — see ServerNpcLootCollector's NOT_ARMED_TICK
		// javadoc for why -1 (not RuneLite's own implicit-0 default) was
		// chosen.
		ServerNpcLootCollector.PickpocketSuppressionState state = notArmed();
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 0, "Gargoyle", 111));
		assertFalse(ServerNpcLootCollector.shouldSuppress(state, 1, "Gargoyle", 111));
	}

	@Test
	public void isPickpocketMessage_matchesRuneLitesOwnPattern()
	{
		// Exact same pattern/match-style as current LootTrackerPlugin's
		// PICKPOCKET_REGEX — see class javadoc's verbatim quote.
		assertTrue(ServerNpcLootCollector.isPickpocketMessage("You pick the Man's pocket."));
		assertTrue(ServerNpcLootCollector.isPickpocketMessage("You pick Hard hitter's pocket and get some coins."));
	}

	@Test
	public void isPickpocketMessage_ordinaryLootMessage_doesNotMatch()
	{
		assertFalse(ServerNpcLootCollector.isPickpocketMessage("Valuable drop: Abyssal whip"));
		assertFalse(ServerNpcLootCollector.isPickpocketMessage("You have a funny feeling like you're being followed."));
	}

	// --- 17. config gating enforced by construction (documented, see class javadoc) ---
	//
	// onChatMessage()/onServerNpcLoot() both check config.collectLoot()
	// as their very first statement (mirroring LootCollector's identical
	// pattern) — not independently unit-testable without mocking
	// RuneLite's Client/EventBus/OsrsTelemetryConfig, which this project
	// deliberately avoids for tests (see LootCollectorTest's identical
	// "architectural, not unit-testable in isolation" notes).

	// --- 18/19. no dependency on NPC_DEATH or NPC_LOOT_ATTRIBUTED ---
	//
	// Enforced by construction — ServerNpcLootCollector has no reference
	// to NpcDeathCollector, EventType.NPC_DEATH, ActorDeath,
	// LootCollector, EventType.NPC_LOOT_ATTRIBUTED, or NpcLootReceived
	// anywhere in its source; its only @Subscribe handlers are
	// onChatMessage(ChatMessage) and onServerNpcLoot(ServerNpcLoot).

	// --- 20. no Slayer/kill ownership field on the payload ---

	@Test
	public void payload_declaresOnlyTheThreeExpectedFields_noOwnershipKillOrSlayerField()
	{
		Field[] fields = EventPayloads.ServerNpcLoot.class.getDeclaredFields();
		String[] fieldNames = Arrays.stream(fields)
			.map(Field::getName)
			.toArray(String[]::new);

		String[] expected = {"sourceName", "sourceId", "items"};
		String[] sortedActual = fieldNames.clone();
		String[] sortedExpected = expected.clone();
		Arrays.sort(sortedActual);
		Arrays.sort(sortedExpected);
		assertArrayEquals(sortedExpected, sortedActual);

		assertNoOwnershipKillOrSlayerField(fieldNames);
		assertNoOwnershipKillOrSlayerField(
			Arrays.stream(EventPayloads.ServerNpcLootItem.class.getDeclaredFields())
				.map(Field::getName)
				.toArray(String[]::new));
	}

	private static void assertNoOwnershipKillOrSlayerField(String[] fieldNames)
	{
		for (String name : fieldNames)
		{
			String lower = name.toLowerCase(Locale.ROOT);
			assertFalse("field " + name + " must not imply ownership", lower.contains("owner"));
			assertFalse("field " + name + " must not imply local-player ownership", lower.contains("mine") || lower.contains("belongsto"));
			assertFalse("field " + name + " must not imply kill attribution", lower.contains("kill"));
			assertFalse("field " + name + " must not imply death attribution", lower.contains("death"));
			assertFalse("field " + name + " must not imply Slayer attribution", lower.contains("slayer") || lower.contains("ontask"));
		}
	}
}
