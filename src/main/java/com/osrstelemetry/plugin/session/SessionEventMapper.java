package com.osrstelemetry.plugin.session;

import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The
 * ONLY class in this package that imports com.osrstelemetry.plugin.events
 * -- everything else in this package (SignalKind's own javadoc says so
 * explicitly) is deliberately decoupled from EventType. This is that
 * decoupling's one required seam: a pure, static, Client-independent
 * translation from an already-normalized telemetry fact (EventType +
 * payload + the wall-clock instant it was durably recorded) to the
 * classifier's own SessionSignal vocabulary, or null when an EventType
 * carries no session-lifecycle meaning at all.
 *
 * Deliberately does NOT attach a gameTick -- that requires
 * `client.getTickCount()`, a Client-only read, and this class must
 * stay exactly as Client-free as every other pure class in this
 * package; tick context is attached at the safe ClientThread ingestion
 * point instead. SessionRuntimeCoordinator
 * calls this, then calls withGameTick(...) itself when it is safely on
 * the client thread.
 *
 * NO DUPLICATED TRUTH LOGIC: every field
 * read here is copied verbatim from the payload a collector already
 * produced -- nothing is independently re-derived from any RuneLite
 * API, and no EventType is reclassified into a stronger claim than its
 * own collector already made (NPC_DEATH remains NPC_DEATH-shaped,
 * never a kill; SLAYER_TASK_PROGRESS remains task-unit metadata, never
 * a kill count; NPC_LOOT_ATTRIBUTED remains non-authoritative and is
 * mapped only so the classifier can explicitly ignore it, exactly as
 * ActivitySignalClassifier.classify() already does for that kind).
 */
final class SessionEventMapper
{
	private SessionEventMapper()
	{
	}

	static SessionSignal map(EventType type, Object payload, Instant observedAt)
	{
		switch (type)
		{
			case XP_CHANGE:
			{
				// p.getWindowStart() is threaded through as
				// this signal's own xpWindowStart, so classifyXpChange()
				// can tell "when this XP was actually earned" apart from
				// `observedAt` (this event's own flush/processing time).
				// Parsed defensively: a malformed/absent windowStart
				// (older/synthetic payloads) yields null, which is
				// completely equivalent to leaving it absent (see
				// SessionSignal's own xpWindowStart javadoc) -- never an
				// exception that could drop this durable XP event
				// entirely.
				//
				// p.getNewXp() -- this window's own
				// absolute new XP value, always present (a primitive int
				// on this payload, never absent/malformed) -- is also
				// threaded through as this signal's own xpNewValue, so a
				// window that SPANS a session switch can be split exactly
				// (see SessionSignal's own xpNewValue javadoc and
				// classifyXpChange()'s boundary-ownership rule).
				EventPayloads.XpChange p = (EventPayloads.XpChange) payload;
				Instant windowStart = parseInstantSafely(p.getWindowStart());
				return SessionSignal.xpChange(observedAt, p.getSkill(), p.getDelta(), windowStart, (long) p.getNewXp());
			}
			case QUEST_COMPLETED:
			{
				EventPayloads.QuestCompleted p = (EventPayloads.QuestCompleted) payload;
				return SessionSignal.questCompleted(observedAt, p.getQuestName());
			}
			case COMBAT_ACHIEVEMENT_COMPLETED:
			{
				EventPayloads.CombatAchievementCompleted p = (EventPayloads.CombatAchievementCompleted) payload;
				return SessionSignal.combatAchievementCompleted(observedAt, p.getTaskName());
			}
			case COLLECTION_LOG_NEW_ITEM:
			{
				EventPayloads.CollectionLogNewItem p = (EventPayloads.CollectionLogNewItem) payload;
				return SessionSignal.collectionLogNewItem(observedAt, p.getItemName());
			}
			case NPC_LOOT_ATTRIBUTED:
				// Mapped (not null) so the classifier's own, already-correct
				// ignore() branch for NPC_LOOT_ATTRIBUTED is what decides
				// this is session-irrelevant -- not a second, silent
				// decision made here. No fields are needed: the classifier
				// never reads any of them for this kind.
				return SessionSignal.npcLootAttributed(observedAt);
			case NPC_DEATH:
				// Same reasoning as NPC_LOOT_ATTRIBUTED -- mapped so the
				// classifier's own ignore() branch is the single place this
				// is decided. MUST NOT become a kill/session-start signal
				// (DO NOT DO list).
				return SessionSignal.npcDeath(observedAt);
			case BOSS_KILL:
			{
				EventPayloads.BossKill p = (EventPayloads.BossKill) payload;
				return SessionSignal.bossKill(observedAt, p.getDisplayName(), p.getKillCount());
			}
			case BOSS_ACTIVITY_CONTEXT:
			{
				// Mapped verbatim, same discipline as BOSS_KILL below --
				// nothing re-derived, nothing upgraded to a stronger claim.
				// The classifier's own classifyBossActivityContext() is the
				// single place this signal's non-authoritative, never-
				// reliableCount treatment is decided.
				EventPayloads.BossActivityContext p = (EventPayloads.BossActivityContext) payload;
				return SessionSignal.bossActivityContext(observedAt, p.getBossName());
			}
			case RAID_COMPLETION:
			{
				EventPayloads.RaidCompletion p = (EventPayloads.RaidCompletion) payload;
				return SessionSignal.raidCompletion(observedAt, p.getDisplayName());
			}
			case ACTIVITY_COMPLETION:
			{
				EventPayloads.ActivityCompletion p = (EventPayloads.ActivityCompletion) payload;
				return SessionSignal.activityCompletion(observedAt, p.getDisplayName());
			}
			case SLAYER_TASK_ASSIGNED:
			{
				EventPayloads.SlayerTaskAssigned p = (EventPayloads.SlayerTaskAssigned) payload;
				return SessionSignal.slayerTaskAssigned(observedAt, p.getMonster(), p.getLocation(), p.getAmount());
			}
			case SLAYER_TASK_PROGRESS:
			{
				// Live evidence: session_state.json's
				// slayerProgressDelta was observed holding an ABSOLUTE
				// remaining count, e.g. 118, instead of the accumulated
				// number of task units actually consumed across the
				// session. Root cause was here: this previously passed
				// p.getCurrentRemaining() -- raw, absolute Slayer task
				// state -- straight into the session signal. Must be
				// p.getTaskUnitsConsumed() instead: how many task units
				// THIS ONE observed progress event itself consumed
				// (normally 1, legitimately >1 for a missed intermediate
				// observation -- see EventPayloads.SlayerTaskProgress's
				// own javadoc), never previousRemaining/currentRemaining/
				// initialAmount or any other absolute task-state number.
				// SessionAggregateUpdater.apply() is what then accumulates
				// these per-event values across the whole session.
				//
				// p.getCurrentRemaining() is ALSO threaded
				// through, but as a SEPARATE, additive field on the
				// signal (slayerCurrentRemaining) -- never merged into or
				// substituted for taskUnitsConsumed above. This does not
				// revive the defect described above: taskUnitsConsumed is still
				// exactly what is summed into slayerProgressDelta;
				// currentRemaining is a completely separate, OVERWRITE-
				// latest-value field (see SessionAggregateUpdater.apply()'s
				// own handling of MetricUpdate.getSlayerCurrentRemaining()).
				EventPayloads.SlayerTaskProgress p = (EventPayloads.SlayerTaskProgress) payload;
				return SessionSignal.slayerTaskProgress(
					observedAt, p.getTaskName(), p.getTaskLocation(), p.getTaskUnitsConsumed(), p.getCurrentRemaining());
			}
			case SLAYER_TASK_COMPLETED:
			{
				EventPayloads.SlayerTaskCompleted p = (EventPayloads.SlayerTaskCompleted) payload;
				// No location field exists on this payload (see
				// EventPayloads.SlayerTaskCompleted) -- never fabricated.
				return SessionSignal.slayerTaskCompleted(observedAt, p.getMonster(), null);
			}
			case SERVER_NPC_LOOT:
			{
				EventPayloads.ServerNpcLoot p = (EventPayloads.ServerNpcLoot) payload;
				List<SessionSignal.LootDrop> drops = new ArrayList<>(p.getItems().size());
				for (EventPayloads.ServerNpcLootItem item : p.getItems())
				{
					drops.add(new SessionSignal.LootDrop(item.getItemId(), item.getItemName(), item.getQuantity()));
				}
				// The whole atomic
				// notification is passed as one grouped list -- never split
				// into independent per-item signals -- so
				// SessionAggregateUpdater.apply() preserves it as one
				// LootDropGroup (source/timestamp/items together).
				return SessionSignal.serverNpcLoot(observedAt, p.getSourceName(), drops);
			}
			case NPC_INTERACTION_TARGET:
			{
				// Mapped
				// verbatim, same discipline as BOSS_ACTIVITY_CONTEXT above
				// -- nothing re-derived, nothing upgraded to a stronger
				// claim. ActivitySignalClassifier's own NPC_INTERACTION_TARGET
				// branch is the single place this signal's context-only,
				// never-lifecycle-carrying treatment is decided.
				EventPayloads.NpcInteractionTarget p = (EventPayloads.NpcInteractionTarget) payload;
				return SessionSignal.npcInteractionTarget(observedAt, p.getNpcId(), p.getNpcName());
			}
			case RAW_COMBAT_XP_OBSERVED:
			{
				// Mapped verbatim -- skillName
				// only, never an xp value (EventPayloads.RawCombatXpObserved
				// structurally has no such field). See
				// SignalKind.RAW_COMBAT_XP_OBSERVED's own javadoc.
				EventPayloads.RawCombatXpObserved p = (EventPayloads.RawCombatXpObserved) payload;
				return SessionSignal.rawCombatXpObserved(observedAt, p.getSkillName());
			}
			case BANK_SNAPSHOT:
			case SEED_VAULT_SNAPSHOT:
				// Storage snapshots carry no session lifecycle activity --
				// mapped to the explicit
				// STORAGE_SNAPSHOT kind, which the classifier already
				// ignores, rather than returning null, so the same "every
				// EventType is at least considered" discipline applies here
				// too.
				return SessionSignal.storageSnapshot(observedAt);
			case LEVEL_UP:
				// LEVEL_UP has no corresponding SignalKind (see SignalKind's
				// closed enum) -- it is a milestone annotation over the same
				// XP data XP_CHANGE already reports; mapping it would either
				// require inventing a new SignalKind (out of scope: "do not
				// redesign the already-tested session model") or silently
				// double-counting XP already captured via XP_CHANGE. Not
				// session-relevant.
				return null;
			default:
				return null;
		}
	}

	/**
	 * Defensive ISO-8601 parse for
	 * EventPayloads.XpChange#getWindowStart() -- null/blank/malformed
	 * input yields null (never an exception), matching this signal
	 * field's own "null means the ownership guard is a no-op" contract
	 * (see SessionSignal's xpWindowStart javadoc).
	 */
	private static Instant parseInstantSafely(String value)
	{
		if (value == null || value.isEmpty())
		{
			return null;
		}
		try
		{
			return Instant.parse(value);
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
	}
}
