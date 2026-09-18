package com.osrstelemetry.plugin.collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.events.EventPayloads;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Test;

/**
 * NOTE: written, not run in this file — this project's normal
 * no-network caveat (see plugin/README.md) applies to authoring.
 *
 * Tests the renamed
 * NPC_LOOT_ATTRIBUTED producer (LootCollector / EventPayloads.
 * NpcLootAttributed) — LOOT_DROP/LootDrop was renamed without adding
 * dedicated tests, since the runtime collection logic didn't change.
 * This file closes that gap.
 *
 * Targets LootCollector.buildPayload(), the pure Client/EventLedger/
 * RuneLite-API-independent core of the NPC_LOOT_ATTRIBUTED payload
 * construction — factored out of onNpcLootReceived() for exactly this
 * purpose, mirroring the same pattern already used throughout this
 * project (SlayerCollector.evaluateSlayerTransition(),
 * NpcDeathCollector.buildPayload()). This project has no Mockito
 * dependency, so RuneLite's NPC/Client/ItemManager/NpcLootReceived
 * types are never mocked — buildPayload() takes only primitives/
 * Strings already read off those objects.
 *
 * Guarantees that are architectural rather than
 * unit-testable in isolation without mocking RuneLite's EventBus/
 * NpcLootReceived (this project deliberately avoids depending on that
 * for tests), documented here instead of faked as a test:
 *
 *  - "Producing NPC_LOOT_ATTRIBUTED has no dependency on an
 *    NPC_DEATH event": enforced by construction — LootCollector's
 *    only import from another collector's package is none at all; it
 *    has no reference to NpcDeathCollector, EventType.NPC_DEATH, or
 *    ActorDeath anywhere in its source, and its only @Subscribe
 *    handler is onNpcLootReceived(NpcLootReceived).
 *  - "Producing NPC_LOOT_ATTRIBUTED has no dependency on a
 *    SLAYER_TASK_PROGRESS event": enforced by construction — same
 *    reasoning; LootCollector has no reference to SlayerCollector,
 *    EventType.SLAYER_TASK_PROGRESS, SlayerPluginService, or
 *    VarbitChanged anywhere in its source.
 */
public class LootCollectorTest
{
	private static final Gson GSON = new Gson();

	// --- 1. serializable through an isolated equivalent of the project's actual serialization path ---

	@Test
	public void payload_roundTripsThroughGson_sameSerializerEventLedgerUses()
	{
		// EventLedger.append() serializes the payload with a plain
		// `new Gson()` (see EventLedger.java) — this test uses the same
		// mechanism directly on the payload object, isolated from
		// Client/EventBus/file I/O, rather than duplicating
		// EventLedger's own tested append()/appendNow() logic.
		EventPayloads.NpcLootAttributed payload = LootCollector.buildPayload(
			"NPC", "Gargoyle", 111, 4151, "Abyssal whip", 1);

		String json = GSON.toJson(payload);
		EventPayloads.NpcLootAttributed roundTripped = GSON.fromJson(json, EventPayloads.NpcLootAttributed.class);

		assertEquals(payload, roundTripped);
		assertTrue("serialized JSON should carry the item name", json.contains("Abyssal whip"));
	}

	// --- 2. NPC/source identity preserved ---

	@Test
	public void buildPayload_preservesSourceIdentity()
	{
		EventPayloads.NpcLootAttributed payload = LootCollector.buildPayload(
			"NPC", "Gargoyle", 111, 4151, "Abyssal whip", 1);

		assertEquals("NPC", payload.getSourceType());
		assertEquals("Gargoyle", payload.getSourceName());
		assertEquals(111, payload.getSourceId());
	}

	// --- 3. item identity preserved ---

	@Test
	public void buildPayload_preservesItemIdentity()
	{
		EventPayloads.NpcLootAttributed payload = LootCollector.buildPayload(
			"NPC", "Gargoyle", 111, 4151, "Abyssal whip", 1);

		assertEquals(4151, payload.getItemId());
		assertEquals("Abyssal whip", payload.getItemName());
		assertEquals(1, payload.getQuantity());
	}

	@Test
	public void buildPayload_preservesQuantityGreaterThanOne()
	{
		// Stacked items (e.g. runes, coins) are a routine case — confirm
		// quantity is passed through unmodified, not clamped/assumed 1.
		EventPayloads.NpcLootAttributed payload = LootCollector.buildPayload(
			"NPC", "Chicken", 41, 995, "Coins", 42);

		assertEquals(42, payload.getQuantity());
	}

	// --- 4/5/6. no ownership, kill-attribution, or Slayer-attribution field exists ---

	@Test
	public void payload_declaresOnlyTheSixExpectedFields_noAttributionOrOwnershipField()
	{
		// Structural check on EventPayloads.NpcLootAttributed itself
		// (not on a specific instance) — the actual contract this test
		// requirement is about is "no such field EXISTS", not "no such
		// field happens to be set." Reflection over the declared field
		// set is a real assertion on the payload's shape, not a
		// duplication of buildPayload()'s pass-through logic.
		Field[] fields = EventPayloads.NpcLootAttributed.class.getDeclaredFields();
		String[] fieldNames = Arrays.stream(fields)
			.map(Field::getName)
			.toArray(String[]::new);

		String[] expected = {"sourceType", "sourceName", "sourceId", "itemId", "itemName", "quantity"};
		String[] sortedActual = fieldNames.clone();
		String[] sortedExpected = expected.clone();
		Arrays.sort(sortedActual);
		Arrays.sort(sortedExpected);
		assertArrayEquals(sortedExpected, sortedActual);

		for (String name : fieldNames)
		{
			String lower = name.toLowerCase(Locale.ROOT);
			assertFalse("field " + name + " must not imply ownership", lower.contains("owner"));
			assertFalse("field " + name + " must not imply local-player ownership", lower.contains("mine") || lower.contains("belongsto"));
			assertFalse("field " + name + " must not imply kill attribution", lower.contains("kill"));
			assertFalse("field " + name + " must not imply Slayer attribution", lower.contains("slayer") || lower.contains("ontask"));
		}
	}

	// --- 9. unavailable metadata is not fabricated ---

	@Test
	public void buildPayload_nullSourceName_passedThroughNotFabricated()
	{
		// npc.getName() can legitimately return null for some NPCs
		// (RuneLite's own NPC/Actor accessors carry no guarantee
		// otherwise). buildPayload() must never substitute a placeholder
		// like "Unknown" — it's a pass-through, not a decision.
		EventPayloads.NpcLootAttributed payload = LootCollector.buildPayload(
			"NPC", null, 111, 4151, "Abyssal whip", 1);

		assertEquals(null, payload.getSourceName());
	}

	@Test
	public void buildPayload_nullItemName_passedThroughNotFabricated()
	{
		EventPayloads.NpcLootAttributed payload = LootCollector.buildPayload(
			"NPC", "Gargoyle", 111, 4151, null, 1);

		assertEquals(null, payload.getItemName());
	}
}
