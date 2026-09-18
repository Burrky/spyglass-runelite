package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.util.Text;

/**
 * See EventType.SERVER_NPC_LOOT's javadoc for the full producer-fact
 * naming rationale; summarized here for anyone reading only this file.
 *
 * SOURCE VERIFICATION (direct fetch of RuneLite source, not remembered
 * API names): confirmed the exact shape of every symbol this collector
 * depends on before writing any code against it.
 *
 *  - net.runelite.client.events.ServerNpcLoot: Lombok @Value, two
 *    fields — {@code NPCComposition composition} and
 *    {@code Collection<ItemStack> items} — with getters
 *    {@code getComposition()}/{@code getItems()}. Javadoc: "NPC loot
 *    received from the in-game loot tracker."
 *  - net.runelite.client.game.ItemStack: Lombok @Value, {@code int id},
 *    {@code int quantity} (a third, deprecated location-carrying
 *    constructor/accessor exists and is deliberately not used here).
 *  - net.runelite.client.plugins.loottracker.LootTrackerPlugin's own
 *    current {@code onServerNpcLoot(ServerNpcLoot event)}:
 *    <pre>
 *    final NPCComposition npc = event.getComposition();
 *    final Collection&lt;ItemStack&gt; items = event.getItems();
 *    final String name = Text.removeTags(npc.getName());
 *    final int combat = npc.getCombatLevel();
 *    if (ignorePickpocketLoot == client.getTickCount())
 *    {
 *        // server sends npc loot for pickpockets, ignore it
 *        return;
 *    }
 *    addLoot(name, combat, LootRecordType.NPC, buildNpcMetadata(npc), items);
 *    </pre>
 *  - The exact pickpocket regex that arms that same-tick guard, from
 *    the same file: {@code "You pick (the )?(?<target>.+)'s? pocket.*"},
 *    matched with {@code .matches()} (not {@code .find()}) against a
 *    chat message, in {@code onChatMessage()}, which then sets
 *    {@code ignorePickpocketLoot = client.getTickCount();} before
 *    handling the pickpocket loot itself via a separate
 *    inventory-diff path this project does not replicate (see the WHY
 *    THE PICKPOCKET FILTER EXISTS note below — this collector only
 *    needs to SUPPRESS ServerNpcLoot on that tick, not record
 *    pickpocket loot itself).
 *  - NPCComposition identity is read via {@code getName()}/{@code getId()}
 *    (and RuneLite's own handler additionally reads
 *    {@code getCombatLevel()}, not used here since this payload has no
 *    combat-level field — see EventPayloads.ServerNpcLoot).
 *
 * WHY THE PICKPOCKET FILTER EXISTS: {@code ServerNpcLoot} also fires for
 * pickpocket loot (a Thieving mechanic, not an NPC-death loot event),
 * and RuneLite's own core Loot Tracker has to explicitly filter it back
 * out for exactly that reason — see the source quoted above. This
 * collector replicates only the semantic effect (suppress
 * {@code ServerNpcLoot} on the tick a pickpocket chat message was just
 * observed), not RuneLite's fuller pickpocket-loot-recording machinery
 * (target-name disambiguation, inventory-diff collection): this
 * project has no Thieving/pickpocket telemetry goal, and broadening
 * into one is explicitly out of scope. Chat message text
 * is never persisted or written to telemetry — only whether it matched
 * the pickpocket pattern, and only the current game tick plus (when
 * resolvable) the identity of the NPC the local player was interacting
 * with at that instant, is retained, and only transiently.
 *
 * IDENTITY-AWARE PICKPOCKET SUPPRESSION: the ORIGINAL guard above
 * (unchanged from RuneLite's own core plugin, confirmed via the
 * verbatim source quote above) suppresses
 * {@code ServerNpcLoot} purely by same-tick coincidence, with NO check
 * that the suppressed loot notification actually belongs to the same
 * NPC the pickpocket message was about. Investigation of a live account
 * (Bandit {@code NPC_LOOT_ATTRIBUTED} events present, but zero Bandit
 * {@code SERVER_NPC_LOOT} events across the entire account history —
 * the only source with a complete blackout) confirmed this collector's
 * own suppression guard, not anything upstream of Spyglass, as the root
 * cause: Bandits are a common pickpocket target, so an unrelated
 * pickpocket-success message landing on the same tick as a genuine
 * Bandit-kill {@code ServerNpcLoot} was silently discarding the kill's
 * loot.
 *
 * The fix: {@code onChatMessage()} now additionally captures, at the
 * exact instant a pickpocket message is observed, the identity (NPC id
 * and/or name, when resolvable) of whatever NPC
 * {@code client.getLocalPlayer().getInteracting()} currently points at
 * — bundled with the tick into one immutable
 * {@link PickpocketSuppressionState}, published atomically via a single
 * volatile field so a concurrent reader never sees a torn combination.
 * {@link #shouldSuppress(PickpocketSuppressionState, int, String, int)}
 * then suppresses a same-tick {@code ServerNpcLoot} only when the
 * event's own source identity actually matches that captured identity
 * (NPC id first when both sides have one, otherwise a normalized name
 * comparison) — an unrelated NPC's kill loot landing on the same tick
 * as someone else's pickpocket message is no longer suppressed. When no
 * identity could be captured at all at pickpocket-message time (the
 * local player's interaction target could not be resolved to an NPC at
 * that instant), suppression falls back to the original tick-only rule,
 * so the "never allow duplicate pickpocket loot" guarantee still holds
 * in that narrow case. This fix applies uniformly to every NPC name —
 * there is no Bandit-specific (or any other name-specific) branch
 * anywhere in this file.
 *
 * HONEST LIMITATION: {@code net.runelite.client.events.ServerNpcLoot}'s
 * {@code NPCComposition} is DEFINITION-level (shared by every on-screen
 * instance of the same NPC type), not instance-level — RuneLite exposes
 * no per-instance NPC reference on this event at all. Identity-aware
 * suppression, however implemented, can therefore only ever confirm
 * "same NPC type," never "same specific NPC instance." Two different
 * instances of the exact same NPC type acting on the exact same tick
 * (one pickpocketed, one killed) remain indistinguishable by this or
 * any fix built on this event — a structural ceiling imposed by the
 * RuneLite API this collector consumes, not a shortcut taken here.
 *
 * INDEPENDENCE (see EventType.SERVER_NPC_LOOT's javadoc): this event
 * never depends on, gates on, or correlates with NPC_DEATH,
 * NPC_LOOT_ATTRIBUTED, SLAYER_TASK_PROGRESS, BOSS_KILL, or any
 * raid/activity completion event. No local-player kill is synthesized
 * from the mere existence of server loot, and no NPC_DEATH/kill
 * reference field exists anywhere on this payload. This collector's
 * only two inputs are RuneLite's own {@code ServerNpcLoot} event and
 * the pickpocket chat-message pattern (plus, now, the local player's
 * interaction target at that instant) used solely to suppress it —
 * nothing else.
 *
 * NPC_LOOT_ATTRIBUTED (LootCollector, built on the separate
 * {@code NpcLootReceived}/{@code LootManager} tile+tick-correlation
 * signal) is a separate, weaker signal, unmodified by this collector —
 * see LootCollector's own class javadoc.
 */
public class ServerNpcLootCollector
{
	/**
	 * Verified verbatim against current LootTrackerPlugin.java source —
	 * see class javadoc. Matched with {@code .matches()}, exactly as
	 * RuneLite's own handler does (a partial/{@code .find()} match
	 * against this pattern could behave differently for messages that
	 * merely contain, rather than consist of, matching text).
	 */
	private static final Pattern PICKPOCKET_REGEX =
		Pattern.compile("You pick (the )?(?<target>.+)'s? pocket.*");

	/**
	 * Sentinel meaning "no pickpocket message observed yet this
	 * client run" — deliberately NOT RuneLite's own default of {@code 0}
	 * (an {@code int} field's implicit initial value), since RuneLite's
	 * {@code client.getTickCount()} could theoretically already be
	 * {@code 0} at the moment this collector is constructed, which
	 * would make an unset guard falsely equal a real tick count. Tick
	 * counts are never negative, so {@code -1} is a safe, real "not
	 * armed" sentinel — the same "non-positive means not available"
	 * discipline this project already applies to nullable NPC ids
	 * elsewhere (see NpcDeathCollector.buildPayload()).
	 */
	private static final int NOT_ARMED_TICK = -1;

	/**
	 * Immutable snapshot of what this collector knows about the most
	 * recently observed pickpocket chat message: the tick it occurred
	 * on, and — when resolvable — the identity of the NPC the local
	 * player was interacting with at that exact instant. Bundled into a
	 * single object (rather than parallel fields on the collector)
	 * specifically so the whole snapshot can be published atomically
	 * via one volatile reference — see {@code pickpocketSuppression}
	 * below — with no risk of a concurrent reader ever observing a torn
	 * combination of tick/id/name assembled from two different
	 * pickpocket messages.
	 *
	 * {@code npcId}/{@code npcName} are both {@code null} exactly when
	 * no NPC identity could be resolved at pickpocket-message time
	 * (the local player's {@code getInteracting()} target was null, not
	 * an {@code NPC}, or its composition could not be read) — this is
	 * the explicit "identity unknown" case
	 * {@link #shouldSuppress(PickpocketSuppressionState, int, String, int)}
	 * falls back to the original tick-only behavior for.
	 *
	 * Package-private (not private) so
	 * {@code ServerNpcLootCollectorTest} can construct and inspect it
	 * directly, exactly like every other pure/testable piece of this
	 * collector.
	 */
	static final class PickpocketSuppressionState
	{
		private static final PickpocketSuppressionState NOT_ARMED =
			new PickpocketSuppressionState(NOT_ARMED_TICK, null, null);

		final int tick;
		final Integer npcId;
		final String npcName;

		private PickpocketSuppressionState(int tick, Integer npcId, String npcName)
		{
			this.tick = tick;
			this.npcId = npcId;
			this.npcName = npcName;
		}

		static PickpocketSuppressionState notArmed()
		{
			return NOT_ARMED;
		}

		static PickpocketSuppressionState armed(int tick, Integer npcId, String npcName)
		{
			return new PickpocketSuppressionState(tick, npcId, npcName);
		}
	}

	private final Client client;
	private final ItemManager itemManager;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;

	private volatile PickpocketSuppressionState pickpocketSuppression = PickpocketSuppressionState.notArmed();

	@Inject
	public ServerNpcLootCollector(Client client, ItemManager itemManager, EventLedger eventLedger, OsrsTelemetryConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.eventLedger = eventLedger;
		this.config = config;
	}

	/**
	 * Defensive reset on a real account switch, mirroring every other
	 * collector's resetForAccountSwitch() convention (see
	 * OsrsTelemetryPlugin.handleLogin()) even though the risk here is
	 * small (leftover suppression state from the outgoing account could
	 * only matter if the very next ServerNpcLoot for the new account
	 * arrived on the exact same client tick count AND matched the
	 * leftover captured identity) — consistent with this project's
	 * existing "never diff/suppress across an account boundary on
	 * leftover state" discipline.
	 */
	public void resetForAccountSwitch()
	{
		pickpocketSuppression = PickpocketSuppressionState.notArmed();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.collectLoot())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		if (isPickpocketMessage(message))
		{
			Integer npcId = null;
			String npcName = null;

			NPC interactingNpc = safeInteractingNpc(client);
			if (interactingNpc != null)
			{
				NPCComposition composition = safeTransformedComposition(interactingNpc);
				if (composition != null)
				{
					npcId = safeNpcId(interactingNpc);
					npcName = safeName(composition);
				}
			}

			pickpocketSuppression = PickpocketSuppressionState.armed(client.getTickCount(), npcId, npcName);
		}
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (!config.collectLoot())
		{
			return;
		}

		NPCComposition composition = event.getComposition();
		String sourceName = composition.getName();
		int rawSourceId = composition.getId();

		if (shouldSuppress(pickpocketSuppression, client.getTickCount(), sourceName, rawSourceId))
		{
			// RuneLite's own Loot Tracker suppresses ServerNpcLoot on the
			// same tick a pickpocket chat message was just observed — see
			// class javadoc. This collector additionally requires the
			// suppressed loot to actually match the NPC identity captured
			// at pickpocket-message time (or, when no identity could be
			// captured at all, falls back to the original tick-only
			// guard) — see shouldSuppress()'s own javadoc for why.
			return;
		}

		List<EventPayloads.ServerNpcLootItem> items = new ArrayList<>();
		for (ItemStack itemStack : event.getItems())
		{
			String itemName = itemManager.getItemComposition(itemStack.getId()).getName();
			items.add(new EventPayloads.ServerNpcLootItem(itemStack.getId(), itemName, itemStack.getQuantity()));
		}

		EventPayloads.ServerNpcLoot payload = buildPayload(sourceName, rawSourceId, items);
		eventLedger.append(client.getAccountHash(), EventType.SERVER_NPC_LOOT, payload);
	}

	/**
	 * Defensive read of the local player's current interaction target,
	 * narrowed to an {@code NPC} — mirrors
	 * NpcInteractionTargetCollector's identical
	 * {@code InteractingChanged}-source-is-local-player technique, but
	 * reads the live value synchronously at pickpocket-message time
	 * instead of subscribing to a separate event, so this collector
	 * stays self-contained (no new cross-collector coupling for what
	 * should be a collector correctness fix, not a rewrite). Returns
	 * null for every unresolved/non-NPC/unavailable case rather than
	 * propagating — never treated as "an NPC by default."
	 */
	private static NPC safeInteractingNpc(Client client)
	{
		try
		{
			Actor localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				return null;
			}
			Actor interacting = localPlayer.getInteracting();
			return (interacting instanceof NPC) ? (NPC) interacting : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Same defensive {@code getTransformedComposition()} idiom
	 * NpcInteractionTargetCollector already uses — the current
	 * visible-form composition, correct across multi-form/transforming
	 * NPCs, never null-by-default.
	 */
	private static NPCComposition safeTransformedComposition(NPC npc)
	{
		try
		{
			return npc.getTransformedComposition();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String safeName(NPCComposition composition)
	{
		try
		{
			return composition.getName();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * npcId mirrors NpcDeathCollector.buildPayload()'s/this file's own
	 * buildPayload()'s "do not fabricate missing values" precedent: any
	 * non-positive raw id is treated as "not available" and left null.
	 */
	private static Integer safeNpcId(NPC npc)
	{
		try
		{
			int rawId = npc.getId();
			return rawId > 0 ? rawId : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Pure — unit-testable without a Client. Exact same regex/match
	 * style as RuneLite's own PICKPOCKET_REGEX handling (see class
	 * javadoc) so this collector suppresses on exactly the same
	 * messages RuneLite's own Loot Tracker would. */
	static boolean isPickpocketMessage(String message)
	{
		return PICKPOCKET_REGEX.matcher(message).matches();
	}

	/**
	 * Pure — unit-testable without a Client. Suppresses a
	 * {@code ServerNpcLoot} notification only when there is sufficient
	 * evidence it represents the SAME pickpocket action as the most
	 * recently observed pickpocket chat message — not merely that some
	 * pickpocket message happened to land on the same game tick as an
	 * unrelated NPC kill. Matching rule, in order:
	 *
	 * <ol>
	 *   <li>Different tick — never suppress (unchanged from the
	 *   original tick-only guard; {@code NOT_ARMED_TICK} (-1) never
	 *   equals a real, non-negative tick).</li>
	 *   <li>Same tick, but NO NPC identity was captured at
	 *   pickpocket-message time ({@code suppressionState.npcId} and
	 *   {@code suppressionState.npcName} both null — e.g. the local
	 *   player's interaction target could not be resolved to an NPC at
	 *   that instant) — fall back to the original tick-only
	 *   suppression, preserving the "never allow duplicate pickpocket
	 *   loot" guarantee in this narrow case where identity is simply
	 *   unavailable.</li>
	 *   <li>Same tick, identity WAS captured — suppress only when the
	 *   {@code ServerNpcLoot} event's own source identity matches:
	 *   compare by NPC id when both sides have a resolvable (positive)
	 *   id, otherwise fall back to a normalized ({@code Text.removeTags})
	 *   name comparison. A same-tick {@code ServerNpcLoot} for a
	 *   DIFFERENT NPC (by id, or by name when no id is available on
	 *   either side) is never suppressed — this is the fix for "an
	 *   unrelated NPC kill's loot disappears merely because a pickpocket
	 *   message for a different NPC happened to land on the same tick."
	 *   Applies uniformly to every NPC name — there is no Bandit-
	 *   specific (or any other name-specific) branch anywhere in this
	 *   method or this file.</li>
	 * </ol>
	 *
	 * {@code NPCComposition} identity as exposed by RuneLite's
	 * {@code ServerNpcLoot} event is definition-level (shared by every
	 * on-screen instance of the same NPC type), not instance-level —
	 * RuneLite exposes no per-instance NPC reference on this event at
	 * all. This match can therefore only ever confirm "same NPC type,"
	 * never "same specific NPC instance" — an honest ceiling imposed by
	 * the RuneLite API this collector consumes, not a shortcut taken
	 * here (see class javadoc's HONEST LIMITATION section).
	 */
	static boolean shouldSuppress(
		PickpocketSuppressionState suppressionState,
		int currentTick,
		String sourceName,
		int rawSourceId)
	{
		if (suppressionState.tick != currentTick)
		{
			return false;
		}

		if (suppressionState.npcId == null && suppressionState.npcName == null)
		{
			// No identity captured at pickpocket-message time at all —
			// fall back to the original tick-only behavior.
			return true;
		}

		Integer sourceId = rawSourceId > 0 ? rawSourceId : null;
		if (suppressionState.npcId != null && sourceId != null)
		{
			return suppressionState.npcId.equals(sourceId);
		}

		return namesMatch(suppressionState.npcName, sourceName);
	}

	private static boolean namesMatch(String a, String b)
	{
		if (a == null || b == null)
		{
			return false;
		}
		return Text.removeTags(a).equals(Text.removeTags(b));
	}

	/**
	 * Pure, Client/EventLedger/RuneLite-API-independent core of the
	 * SERVER_NPC_LOOT payload construction — factored out for the same
	 * reason as every other collector's buildPayload() helper in this
	 * project (see LootCollector.buildPayload()/NpcDeathCollector.
	 * buildPayload()): unit-testable without mocking NPCComposition/
	 * ItemStack/Client. This is a pass-through, not a decision — every
	 * value already read off RuneLite's own objects is stored exactly
	 * as given, never defaulted or fabricated, except sourceId's
	 * documented non-positive-means-unavailable normalization below.
	 *
	 * rawSourceId nullability: NPCComposition#getId() carries no
	 * documented guarantee of always returning a real, usable value —
	 * mirroring the same treatment NpcDeathCollector.buildPayload()
	 * already applies to NPC#getId(): any non-positive raw value is
	 * treated as "not available" and left null rather than stored as a
	 * misleading not-actually-real id.
	 *
	 * items is stored exactly as given, including when empty — an
	 * empty items list is never fabricated into "no event at all" or
	 * padded with an invented item; whatever RuneLite's own
	 * ServerNpcLoot notification actually carried is what this payload
	 * carries.
	 */
	static EventPayloads.ServerNpcLoot buildPayload(
		String sourceName,
		int rawSourceId,
		List<EventPayloads.ServerNpcLootItem> items)
	{
		Integer sourceId = rawSourceId > 0 ? rawSourceId : null;
		return new EventPayloads.ServerNpcLoot(sourceName, sourceId, items);
	}
}
