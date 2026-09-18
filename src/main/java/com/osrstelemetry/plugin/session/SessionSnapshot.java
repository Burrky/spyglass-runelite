package com.osrstelemetry.plugin.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fully immutable, defensively-
 * copied point-in-time view of a {@link Session} (and its
 * {@link SessionAggregates}), meant to be captured while holding
 * {@link SessionRuntimeCoordinator}'s own monitor and handed to a
 * reader thread (Swing/EDT) that must never see the live, concurrently-
 * mutable runtime objects.
 *
 * WHY THIS EXISTS: {@code SessionRuntimeCoordinator.getCurrentSession()}
 * (the earlier UI accessor) returned the SAME live {@link Session}
 * object the client thread / event-ledger listener keeps mutating
 * (XP map entries, loot groups, lastActiveAt/accumulatedActiveDurationMillis)
 * after the synchronized accessor call had already returned. Being
 * `synchronized` only protected the handoff of the reference itself --
 * every field read the EDT then performed on that same object, across
 * several separate getter calls, was completely unsynchronized against
 * concurrent mutation on another thread: a genuine, unguarded data race
 * (a torn/inconsistent read, not merely "stale data"). This class is
 * the fix: {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()}
 * builds one of these via {@link #capture(Session)} WHILE STILL HOLDING
 * the coordinator's monitor, so every field below is copied atomically
 * with respect to any concurrent runtime mutation. Once this object is
 * returned, it shares no mutable state with the runtime at all -- every
 * collection is a fresh, independent, unmodifiable copy, and every
 * scalar is a primitive/String/enum (already inherently immutable).
 *
 * DEPENDENCY DIRECTION: lives in the session/runtime package (not
 * ui.model) specifically so this package never has to depend on the UI
 * package -- {@code com.osrstelemetry.plugin.ui.model.CurrentSessionSnapshot}
 * depends on this class, never the reverse. {@link #capture(Session)}
 * is `public` (rather than package-private) only so
 * {@code CurrentSessionSnapshotTest} (in the ui.model package) can keep
 * building its fixtures the same way it always has -- via the real
 * {@link SessionLifecycleEngine} plus this one extra wrapping call --
 * without reaching into this package's internals; the coordinator's own
 * production call site is exactly the same public method, just invoked
 * from inside its own synchronized block.
 */
public final class SessionSnapshot
{
	private static final SessionSnapshot ABSENT = new SessionSnapshot(
		false, null, null, null, null, 0L, null,
		Collections.<String, Long>emptyMap(), null, null, Collections.<SessionAggregates.LootDropGroup>emptyList(), null,
		Collections.<String, Long>emptyMap(), Collections.<String, Long>emptyMap());

	private final boolean present;
	private final String sessionId;
	private final ActivityType activityType;
	private final String activityDisplayName;
	private final SessionState state;
	private final long accumulatedActiveDurationMillis;
	private final String lastActiveAt;
	private final Map<String, Long> xpGainedBySkill;
	private final SessionAggregates.ReliableCount reliableCount;
	private final Integer slayerProgressDelta;
	private final List<SessionAggregates.LootDropGroup> lootDrops;
	private final Integer latestSlayerCurrentRemaining;

	/**
	 * An independent, unmodifiable copy of whatever
	 * {@link LiveXpTracker#getPendingForSession(String)} returned for
	 * THIS session at capture time -- skill -&gt; XP gained since the
	 * last XP_CHANGE flush for that skill, not yet reflected in
	 * {@link #xpGainedBySkill} above. Always {@link Collections#emptyMap()}
	 * for a snapshot built via the legacy {@link #capture(Session)}
	 * overload (no live-XP-aware caller) -- see that overload's own
	 * javadoc.
	 */
	private final Map<String, Long> livePendingXpBySkill;

	/**
	 * An independent,
	 * unmodifiable copy of whatever
	 * {@code SessionRuntimeCoordinator.lastKnownAbsoluteXpBySkill} held at
	 * capture time -- skill name -&gt; the most recently observed ABSOLUTE
	 * (not session-delta) XP total for that skill, fed continuously by
	 * every raw StatChanged observation (see
	 * {@code SessionRuntimeCoordinator.noteAbsoluteXp()}). This is the
	 * ONLY thing the Current Session panel's per-skill level/progress-
	 * to-next-level display needs and durable session XP does not
	 * already provide -- the real, current account level for a skill can
	 * only be derived from its true absolute XP, never from a
	 * session-scoped delta. Deliberately NOT folded into
	 * {@link #xpGainedBySkill} or persisted anywhere -- this field exists
	 * purely so the UI layer can compute a read-only, derived level/
	 * progress view; it must never become a second durable XP aggregate
	 * (see {@code CurrentSessionSnapshot}'s own javadoc for the same
	 * constraint restated at the UI-model layer). Always
	 * {@link Collections#emptyMap()} for a snapshot built via either
	 * legacy {@link #capture(Session)} or {@link #capture(Session, Map)}
	 * overload (no absolute-XP-aware caller) -- see those overloads' own
	 * javadoc.
	 */
	private final Map<String, Long> absoluteXpBySkill;

	private SessionSnapshot(
		boolean present,
		String sessionId,
		ActivityType activityType,
		String activityDisplayName,
		SessionState state,
		long accumulatedActiveDurationMillis,
		String lastActiveAt,
		Map<String, Long> xpGainedBySkill,
		SessionAggregates.ReliableCount reliableCount,
		Integer slayerProgressDelta,
		List<SessionAggregates.LootDropGroup> lootDrops,
		Integer latestSlayerCurrentRemaining,
		Map<String, Long> livePendingXpBySkill,
		Map<String, Long> absoluteXpBySkill)
	{
		this.present = present;
		this.sessionId = sessionId;
		this.activityType = activityType;
		this.activityDisplayName = activityDisplayName;
		this.state = state;
		this.accumulatedActiveDurationMillis = accumulatedActiveDurationMillis;
		this.lastActiveAt = lastActiveAt;
		this.xpGainedBySkill = xpGainedBySkill;
		this.reliableCount = reliableCount;
		this.slayerProgressDelta = slayerProgressDelta;
		this.lootDrops = lootDrops;
		this.latestSlayerCurrentRemaining = latestSlayerCurrentRemaining;
		this.livePendingXpBySkill = livePendingXpBySkill;
		this.absoluteXpBySkill = absoluteXpBySkill;
	}

	/** No current session -- mirrors {@code SessionRuntimeCoordinator.getCurrentSession() == null}. */
	public static SessionSnapshot absent()
	{
		return ABSENT;
	}

	/**
	 * Builds a fully immutable, defensively-copied snapshot of
	 * {@code session} exactly as it stands at the moment this method
	 * runs. Every collection/nested value object below is a fresh copy
	 * -- nothing returned by this method ever shares a reference with
	 * {@code session} or its {@link SessionAggregates}.
	 *
	 * ATOMICITY REQUIREMENT: the ONE thing this method cannot enforce by
	 * itself is that {@code session} is not concurrently mutated WHILE
	 * this method is running. That guarantee is the caller's job --
	 * {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()} is
	 * the only production call site, and it calls this from inside a
	 * `synchronized` block on the coordinator's own monitor, the exact
	 * same monitor every runtime-mutating method in that class already
	 * holds while it mutates this same {@code session}/its aggregates --
	 * so in production, no mutation can be interleaved with this copy.
	 * (Test-only callers build a {@code session} via a single-threaded
	 * {@link SessionLifecycleEngine} with no concurrent mutator, so the
	 * same requirement is trivially satisfied there.)
	 *
	 * @return {@link #absent()} if {@code session} is null.
	 */
	public static SessionSnapshot capture(Session session)
	{
		return capture(session, Collections.<String, Long>emptyMap(), Collections.<String, Long>emptyMap());
	}

	/**
	 * Same contract as {@link #capture(Session)}, plus one
	 * extra piece of atomically-captured state: {@code livePendingXpBySkill}
	 * -- the NON-DURABLE, in-memory live XP {@link LiveXpTracker} is
	 * currently tracking for this exact session (already
	 * {@code LiveXpTracker.getPendingForSession()}'s own defensively-copied,
	 * unmodifiable map -- copied again here only for a consistent,
	 * defense-in-depth "this class never hands out a reference it did
	 * not itself create" guarantee, matching every other field here).
	 * {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()} is
	 * the only production caller, invoking this from inside the exact
	 * same synchronized block that owns both {@code session} and the
	 * {@link LiveXpTracker} instance -- so this remains just as atomic
	 * as every other field {@link #capture(Session)} already copies.
	 *
	 * @return {@link #absent()} if {@code session} is null (regardless
	 * of {@code livePendingXpBySkill} -- there is no session for it to
	 * attach to).
	 */
	public static SessionSnapshot capture(Session session, Map<String, Long> livePendingXpBySkill)
	{
		return capture(session, livePendingXpBySkill, Collections.<String, Long>emptyMap());
	}

	/**
	 * Same contract as
	 * {@link #capture(Session, Map)}, plus one more atomically-captured
	 * piece of state: {@code absoluteXpBySkill} -- a defensive,
	 * independent copy of whatever
	 * {@code SessionRuntimeCoordinator.lastKnownAbsoluteXpBySkill} holds
	 * at capture time (see {@link #absoluteXpBySkill}'s own javadoc).
	 * {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()} is the
	 * only production caller, invoking this from inside the exact same
	 * synchronized block that owns {@code session} AND
	 * {@code lastKnownAbsoluteXpBySkill} -- so this remains just as
	 * atomic as every other field capture() already copies.
	 *
	 * @return {@link #absent()} if {@code session} is null (regardless of
	 * either map argument -- there is no session for them to attach to).
	 */
	public static SessionSnapshot capture(
		Session session, Map<String, Long> livePendingXpBySkill, Map<String, Long> absoluteXpBySkill)
	{
		if (session == null)
		{
			return ABSENT;
		}

		ActivityIdentity identity = session.getActivityIdentity();
		SessionAggregates aggregates = session.getAggregates();

		return new SessionSnapshot(
			true,
			session.getSessionId(),
			identity == null ? null : identity.getActivityType(),
			identity == null ? null : identity.getDisplayName(),
			session.getState(),
			session.getAccumulatedActiveDurationMillis(),
			session.getLastActiveAt(),
			copyXp(aggregates),
			copyReliableCount(aggregates),
			aggregates == null ? null : aggregates.getSlayerProgressDelta(),
			copyLootDrops(aggregates),
			aggregates == null ? null : aggregates.getLatestSlayerCurrentRemaining(),
			livePendingXpBySkill == null
				? Collections.<String, Long>emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(livePendingXpBySkill)),
			absoluteXpBySkill == null
				? Collections.<String, Long>emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(absoluteXpBySkill)));
	}

	private static Map<String, Long> copyXp(SessionAggregates aggregates)
	{
		if (aggregates == null || aggregates.getXpGainedBySkill() == null)
		{
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(aggregates.getXpGainedBySkill()));
	}

	private static SessionAggregates.ReliableCount copyReliableCount(SessionAggregates aggregates)
	{
		if (aggregates == null || aggregates.getReliableCount() == null)
		{
			return null;
		}
		SessionAggregates.ReliableCount source = aggregates.getReliableCount();
		SessionAggregates.ReliableCount copy = new SessionAggregates.ReliableCount();
		copy.setKind(source.getKind());
		copy.setSourceKey(source.getSourceKey());
		copy.setAuthoritativeCurrentValue(source.getAuthoritativeCurrentValue());
		copy.setSessionOccurrences(source.getSessionOccurrences());
		return copy;
	}

	private static List<SessionAggregates.LootDropGroup> copyLootDrops(SessionAggregates aggregates)
	{
		if (aggregates == null || aggregates.getLootDrops() == null || aggregates.getLootDrops().isEmpty())
		{
			return Collections.emptyList();
		}

		List<SessionAggregates.LootDropGroup> copy = new ArrayList<>();
		for (SessionAggregates.LootDropGroup group : aggregates.getLootDrops())
		{
			if (group == null)
			{
				continue;
			}
			SessionAggregates.LootDropGroup groupCopy = new SessionAggregates.LootDropGroup();
			groupCopy.setSourceName(group.getSourceName());
			groupCopy.setObservedAt(group.getObservedAt());

			List<SessionAggregates.LootItemAggregate> itemsCopy = new ArrayList<>();
			if (group.getItems() != null)
			{
				for (SessionAggregates.LootItemAggregate item : group.getItems())
				{
					if (item == null)
					{
						continue;
					}
					SessionAggregates.LootItemAggregate itemCopy = new SessionAggregates.LootItemAggregate();
					itemCopy.setItemId(item.getItemId());
					itemCopy.setItemName(item.getItemName());
					itemCopy.setQuantity(item.getQuantity());
					itemsCopy.add(itemCopy);
				}
			}
			groupCopy.setItems(Collections.unmodifiableList(itemsCopy));
			copy.add(groupCopy);
		}
		return Collections.unmodifiableList(copy);
	}

	public boolean isPresent()
	{
		return present;
	}

	public String getSessionId()
	{
		return sessionId;
	}

	public ActivityType getActivityType()
	{
		return activityType;
	}

	public String getActivityDisplayName()
	{
		return activityDisplayName;
	}

	public SessionState getState()
	{
		return state;
	}

	public long getAccumulatedActiveDurationMillis()
	{
		return accumulatedActiveDurationMillis;
	}

	/** ISO-8601 String (matching {@link Session}'s own timestamp convention), or null. Copying a String copies nothing mutable. */
	public String getLastActiveAt()
	{
		return lastActiveAt;
	}

	/** Independent, unmodifiable copy -- never {@code session.getAggregates().getXpGainedBySkill()}. */
	public Map<String, Long> getXpGainedBySkill()
	{
		return xpGainedBySkill;
	}

	/** An independent copy of the source {@link SessionAggregates.ReliableCount}, or null. */
	public SessionAggregates.ReliableCount getReliableCount()
	{
		return reliableCount;
	}

	public Integer getSlayerProgressDelta()
	{
		return slayerProgressDelta;
	}

	/** Independent, unmodifiable, deep copy -- never {@code session.getAggregates().getLootDrops()} or any of its groups/items. */
	public List<SessionAggregates.LootDropGroup> getLootDrops()
	{
		return lootDrops;
	}

	/**
	 * A plain boxed Integer (or null) copied verbatim from
	 * {@code SessionAggregates.latestSlayerCurrentRemaining} -- copying
	 * an Integer copies nothing mutable, exactly like
	 * {@link #getSlayerProgressDelta()} above. Null when no
	 * SLAYER_TASK_PROGRESS event carrying a currentRemaining has been
	 * observed this session yet.
	 */
	public Integer getLatestSlayerCurrentRemaining()
	{
		return latestSlayerCurrentRemaining;
	}

	/**
	 * Independent, unmodifiable copy of
	 * the live, not-yet-durably-flushed XP {@link LiveXpTracker} was
	 * tracking for this session at capture time -- skill -&gt; XP gained
	 * since the last XP_CHANGE flush for that skill. Empty (never null)
	 * whenever this snapshot was built via the legacy
	 * {@link #capture(Session)} overload, or when no live XP is
	 * currently pending for this session.
	 */
	public Map<String, Long> getLivePendingXpBySkill()
	{
		return livePendingXpBySkill;
	}

	/**
	 * Independent,
	 * unmodifiable copy of the most recently observed ABSOLUTE XP per
	 * skill -- see {@link #absoluteXpBySkill}'s own javadoc. Empty
	 * (never null) for a snapshot built via {@link #capture(Session)} or
	 * {@link #capture(Session, Map)}.
	 */
	public Map<String, Long> getAbsoluteXpBySkill()
	{
		return absoluteXpBySkill;
	}
}
