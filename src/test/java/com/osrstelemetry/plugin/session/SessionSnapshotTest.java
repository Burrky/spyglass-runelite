package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import org.junit.Test;

/**
 * Proves {@link SessionSnapshot#capture(Session)} does exactly
 * what its own javadoc claims: every collection/nested value object it
 * returns is a fresh, independent copy that shares no reference with
 * the {@link Session}/{@link SessionAggregates} it was built from, so
 * mutating the source AFTER capture never changes an already-returned
 * snapshot (items B/C/D/E). Also proves
 * (item A) that {@link SessionRuntimeCoordinator#getCurrentSessionSnapshot()}'s
 * return type can never hand a caller a live {@link Session} reference,
 * and (item F) that one captured snapshot is a coherent identity/state/
 * aggregate combination -- true "by construction" here since capture()
 * is a single synchronous method with no partial-write window, the same
 * argument the production call site relies on under
 * SessionRuntimeCoordinator's monitor (see that method's own javadoc);
 * a single-threaded JUnit test cannot itself exercise concurrent
 * mutation, so this test demonstrates correctness-by-construction of
 * the copying code, not a live race.
 *
 * Builds every fixture via the real, public {@link SessionLifecycleEngine}
 * (onQualifyingActivity()), exactly like SessionLifecycleEngineTest and
 * CurrentSessionSnapshotTest -- Session's mutation surface is
 * intentionally package-private (see its own javadoc), so driving the
 * real engine is the production-faithful way to get an ACTIVE Session.
 * This test class itself lives in the session package (like
 * SessionRuntimeCoordinatorTest), so it may also mutate
 * Session#getAggregates()' public @Data fields directly to simulate
 * "the runtime kept mutating after the snapshot was taken" -- exactly
 * what a concurrent client-thread/scheduledExecutor mutation would do
 * in production.
 */
public class SessionSnapshotTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	// ------------------------------------------------------------------
	// A. absent()/capture(null) never exposes a live Session -- and
	// capture()'s return type is SessionSnapshot, never Session, so no
	// caller of it can ever obtain a live Session reference through it.
	// ------------------------------------------------------------------
	@Test
	public void captureNull_returnsAbsent()
	{
		SessionSnapshot snapshot = SessionSnapshot.capture(null);

		assertSame(SessionSnapshot.absent(), snapshot);
		assertFalse(snapshot.isPresent());
	}

	@Test
	public void absent_hasNoIdentityOrAggregateData()
	{
		SessionSnapshot snapshot = SessionSnapshot.absent();

		assertFalse(snapshot.isPresent());
		assertNull(snapshot.getSessionId());
		assertNull(snapshot.getActivityType());
		assertNull(snapshot.getState());
		assertTrue(snapshot.getXpGainedBySkill().isEmpty());
		assertNull(snapshot.getReliableCount());
		assertNull(snapshot.getSlayerProgressDelta());
		assertTrue(snapshot.getLootDrops().isEmpty());
		assertNull(snapshot.getLatestSlayerCurrentRemaining());
	}

	// ------------------------------------------------------------------
	// B. XP map independence: mutating the live Session's XP map AFTER
	// capture never changes the already-returned snapshot.
	// ------------------------------------------------------------------
	@Test
	public void xpMap_isIndependentOfLaterSessionMutation()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 100L);

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertEquals(Long.valueOf(100L), snapshot.getXpGainedBySkill().get("SLAYER"));
		assertNotSame(session.getAggregates().getXpGainedBySkill(), snapshot.getXpGainedBySkill());

		// Simulates a concurrent runtime mutation arriving after this
		// snapshot was already handed to a reader.
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 999L);
		session.getAggregates().getXpGainedBySkill().put("ATTACK", 50L);

		assertEquals(Long.valueOf(100L), snapshot.getXpGainedBySkill().get("SLAYER"));
		assertFalse(snapshot.getXpGainedBySkill().containsKey("ATTACK"));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void xpMap_isUnmodifiable()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 100L);

		SessionSnapshot.capture(session).getXpGainedBySkill().put("ATTACK", 1L);
	}

	// ------------------------------------------------------------------
	// C. Loot independence: mutating the live Session's loot groups/
	// items AFTER capture never changes the already-returned snapshot.
	// ------------------------------------------------------------------
	@Test
	public void lootDrops_areIndependentOfLaterSessionMutation()
	{
		Session session = activeSessionAt(T0);

		SessionAggregates.LootDropGroup group = new SessionAggregates.LootDropGroup();
		group.setSourceName("Gargoyle");
		group.setObservedAt(T0.toString());
		SessionAggregates.LootItemAggregate item = new SessionAggregates.LootItemAggregate();
		item.setItemId(561);
		item.setItemName("Nature rune");
		item.setQuantity(10L);
		group.setItems(new java.util.ArrayList<>(Collections.singletonList(item)));
		session.getAggregates().getLootDrops().add(group);

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertEquals(1, snapshot.getLootDrops().size());
		assertEquals(10L, snapshot.getLootDrops().get(0).getItems().get(0).getQuantity());

		// Simulates a concurrent runtime mutation arriving after this
		// snapshot was already handed to a reader: the quantity on the
		// SAME live item object changes, and a second group is added.
		item.setQuantity(999L);
		SessionAggregates.LootDropGroup secondGroup = new SessionAggregates.LootDropGroup();
		secondGroup.setSourceName("Gargoyle");
		session.getAggregates().getLootDrops().add(secondGroup);

		assertEquals(1, snapshot.getLootDrops().size());
		assertEquals(10L, snapshot.getLootDrops().get(0).getItems().get(0).getQuantity());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void lootDrops_outerListIsUnmodifiable()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getLootDrops().add(new SessionAggregates.LootDropGroup());

		SessionSnapshot.capture(session).getLootDrops().add(new SessionAggregates.LootDropGroup());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void lootDrops_innerItemListIsUnmodifiable()
	{
		Session session = activeSessionAt(T0);
		SessionAggregates.LootDropGroup group = new SessionAggregates.LootDropGroup();
		SessionAggregates.LootItemAggregate item = new SessionAggregates.LootItemAggregate();
		item.setItemName("Nature rune");
		group.setItems(Collections.singletonList(item));
		session.getAggregates().getLootDrops().add(group);

		SessionSnapshot.capture(session).getLootDrops().get(0).getItems()
			.add(new SessionAggregates.LootItemAggregate());
	}

	// ------------------------------------------------------------------
	// D. ReliableCount independence: it is a mutable object
	// (SessionAggregates.ReliableCount, a @Data class with setters), so
	// capture() must copy its fields into a NEW instance -- mutating the
	// live source object afterward must never change the snapshot's copy.
	// ------------------------------------------------------------------
	@Test
	public void reliableCount_isIndependentOfLaterSessionMutation()
	{
		Session session = activeSessionAt(T0);
		SessionAggregates.ReliableCount rc = new SessionAggregates.ReliableCount();
		rc.setKind(SessionAggregates.ReliableCountKind.KILLS);
		rc.setSourceKey("gargoyles:catacombs");
		rc.setSessionOccurrences(3);
		rc.setAuthoritativeCurrentValue(500);
		session.getAggregates().setReliableCount(rc);

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertNotSame(rc, snapshot.getReliableCount());
		assertEquals(3, snapshot.getReliableCount().getSessionOccurrences());

		// Simulates a concurrent runtime mutation of the SAME live
		// ReliableCount object arriving after this snapshot was already
		// handed to a reader.
		rc.setSessionOccurrences(4);
		rc.setAuthoritativeCurrentValue(501);

		assertEquals(3, snapshot.getReliableCount().getSessionOccurrences());
		assertEquals(Integer.valueOf(500), snapshot.getReliableCount().getAuthoritativeCurrentValue());
	}

	@Test
	public void reliableCount_absentStaysNull()
	{
		Session session = activeSessionAt(T0);
		assertNull(session.getAggregates().getReliableCount());

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertNull(snapshot.getReliableCount());
	}

	// ------------------------------------------------------------------
	// E. slayerProgressDelta: a boxed Integer is already an immutable
	// value once assigned -- capture() just needs to carry the correct
	// value through (no shared mutable wrapper is possible for a boxed
	// primitive), confirmed here by simple value equality plus a later
	// mutation of the SOURCE FIELD (not the value itself, which can't
	// be mutated) having no effect on the already-captured snapshot.
	// ------------------------------------------------------------------
	@Test
	public void slayerProgressDelta_copiedAsPlainValue()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setSlayerProgressDelta(7);

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertEquals(Integer.valueOf(7), snapshot.getSlayerProgressDelta());

		// Simulates a concurrent runtime mutation replacing the field's
		// value entirely after this snapshot was already captured.
		session.getAggregates().setSlayerProgressDelta(20);

		assertEquals(Integer.valueOf(7), snapshot.getSlayerProgressDelta());
	}

	// ------------------------------------------------------------------
	// K. latestSlayerCurrentRemaining: a boxed
	// Integer, copied verbatim exactly like slayerProgressDelta above
	// (test E), but tracking a DIFFERENT aggregate with OVERWRITE
	// semantics rather than SUM semantics -- SessionSnapshot must carry
	// it through defensively (correctly, since it is a plain immutable
	// boxed value) so CurrentSessionSnapshot (ui.model) can surface it
	// without ever reaching back into SessionAggregates/SlayerCollector.
	// ------------------------------------------------------------------
	@Test
	public void latestSlayerCurrentRemaining_copiedAsPlainValue()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().setLatestSlayerCurrentRemaining(98);

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertEquals(Integer.valueOf(98), snapshot.getLatestSlayerCurrentRemaining());

		// Simulates a concurrent runtime mutation (the next Gargoyle kill's
		// SLAYER_TASK_PROGRESS overwriting the field) after this snapshot
		// was already captured.
		session.getAggregates().setLatestSlayerCurrentRemaining(97);

		assertEquals(Integer.valueOf(98), snapshot.getLatestSlayerCurrentRemaining());
	}

	// ------------------------------------------------------------------
	// F. One captured snapshot is a coherent identity/state/aggregate
	// combination -- every field reflects the SAME point-in-time state,
	// never a mix of pre- and post-transition values. Correct-by-
	// construction: capture() is one synchronous method with no partial
	// -write window, so this test demonstrates the invariant holds for
	// a specific, non-trivial state (mid-SUSPENDED, with XP/loot/reliable
	// -count/slayer data already attached) rather than proving cross-
	// thread atomicity, which a single-threaded JUnit test cannot do --
	// see class javadoc.
	// ------------------------------------------------------------------
	@Test
	public void capturedSnapshot_isCoherentForItsExactState()
	{
		Session session = activeSessionAt(T0);
		session.getAggregates().getXpGainedBySkill().put("SLAYER", 5000L);
		SessionAggregates.ReliableCount rc = new SessionAggregates.ReliableCount();
		rc.setKind(SessionAggregates.ReliableCountKind.KILLS);
		rc.setSessionOccurrences(2);
		session.getAggregates().setReliableCount(rc);
		session.getAggregates().setSlayerProgressDelta(9);

		SessionSnapshot snapshot = SessionSnapshot.capture(session);

		// Every field on this ONE snapshot object reflects the exact
		// same moment: the pre-transition identity/session id together
		// with the aggregates attached to that same Session instance at
		// capture time.
		assertTrue(snapshot.isPresent());
		assertEquals(session.getSessionId(), snapshot.getSessionId());
		assertEquals(SessionState.ACTIVE, snapshot.getState());
		assertEquals(ActivityType.SLAYER, snapshot.getActivityType());
		assertEquals("Gargoyles", snapshot.getActivityDisplayName());
		assertEquals(Long.valueOf(5000L), snapshot.getXpGainedBySkill().get("SLAYER"));
		assertEquals(2, snapshot.getReliableCount().getSessionOccurrences());
		assertEquals(Integer.valueOf(9), snapshot.getSlayerProgressDelta());
		assertEquals(session.getAccumulatedActiveDurationMillis(), snapshot.getAccumulatedActiveDurationMillis());
		assertEquals(session.getLastActiveAt(), snapshot.getLastActiveAt());
	}

	@Test
	public void capturedSnapshot_suspendedState_isCoherent()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant t1 = T0.plus(Duration.ofMinutes(2));
		engine.onQualifyingActivity(GARGOYLES, t1);
		engine.advanceTime(t1.plus(Duration.ofMinutes(10)));

		Session session = engine.getCurrentSession();
		assertEquals(SessionState.SUSPENDED, session.getState());

		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertEquals(SessionState.SUSPENDED, snapshot.getState());
		assertEquals(Duration.ofMinutes(2).toMillis(), snapshot.getAccumulatedActiveDurationMillis());
		assertEquals(session.getSessionId(), snapshot.getSessionId());
	}

	private static Session activeSessionAt(Instant at)
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, at);
		return engine.getCurrentSession();
	}

	// ------------------------------------------------------------------
	// Immediate Current Session XP display:
	// the live-XP-aware capture(Session, Map) overload.
	// ------------------------------------------------------------------

	@Test
	public void legacyCapture_alwaysReturnsEmptyLivePendingXp()
	{
		Session session = activeSessionAt(T0);
		SessionSnapshot snapshot = SessionSnapshot.capture(session);
		assertTrue(snapshot.getLivePendingXpBySkill().isEmpty());
	}

	@Test
	public void captureWithLiveXp_carriesTheGivenMapVerbatim()
	{
		Session session = activeSessionAt(T0);
		java.util.Map<String, Long> live = new java.util.HashMap<>();
		live.put("Woodcutting", 40L);

		SessionSnapshot snapshot = SessionSnapshot.capture(session, live);

		assertEquals(Long.valueOf(40L), snapshot.getLivePendingXpBySkill().get("Woodcutting"));
	}

	@Test
	public void captureWithLiveXp_defensivelyCopies_neverALiveViewOfTheInputMap()
	{
		Session session = activeSessionAt(T0);
		java.util.Map<String, Long> live = new java.util.HashMap<>();
		live.put("Agility", 15L);

		SessionSnapshot snapshot = SessionSnapshot.capture(session, live);
		live.put("Agility", 999L);
		live.put("Fishing", 5L);

		assertEquals(Long.valueOf(15L), snapshot.getLivePendingXpBySkill().get("Agility"));
		assertFalse(snapshot.getLivePendingXpBySkill().containsKey("Fishing"));
	}

	@Test
	public void captureWithLiveXp_nullSession_stillReturnsAbsent()
	{
		java.util.Map<String, Long> live = new java.util.HashMap<>();
		live.put("Attack", 10L);
		SessionSnapshot snapshot = SessionSnapshot.capture(null, live);
		assertFalse(snapshot.isPresent());
	}

	@Test
	public void captureWithLiveXp_nullMap_treatedAsEmpty()
	{
		Session session = activeSessionAt(T0);
		SessionSnapshot snapshot = SessionSnapshot.capture(session, null);
		assertTrue(snapshot.getLivePendingXpBySkill().isEmpty());
	}

	// ------------------------------------------------------------------
	// CURRENT SESSION XP LEVEL-PROGRESS PASS: the absolute-XP-aware
	// capture(Session, Map, Map) overload -- same defensive-copy
	// contract already proven above for livePendingXpBySkill, applied to
	// the new third map.
	// ------------------------------------------------------------------

	@Test
	public void legacyCaptureOverloads_alwaysReturnEmptyAbsoluteXp()
	{
		Session session = activeSessionAt(T0);
		assertTrue(SessionSnapshot.capture(session).getAbsoluteXpBySkill().isEmpty());
		assertTrue(SessionSnapshot.capture(session, Collections.<String, Long>emptyMap())
			.getAbsoluteXpBySkill().isEmpty());
	}

	@Test
	public void captureWithAbsoluteXp_carriesTheGivenMapVerbatim()
	{
		Session session = activeSessionAt(T0);
		java.util.Map<String, Long> absolute = new java.util.HashMap<>();
		absolute.put("STRENGTH", 13_034_431L);

		SessionSnapshot snapshot = SessionSnapshot.capture(session, Collections.<String, Long>emptyMap(), absolute);

		assertEquals(Long.valueOf(13_034_431L), snapshot.getAbsoluteXpBySkill().get("STRENGTH"));
	}

	@Test
	public void captureWithAbsoluteXp_defensivelyCopies_neverALiveViewOfTheInputMap()
	{
		Session session = activeSessionAt(T0);
		java.util.Map<String, Long> absolute = new java.util.HashMap<>();
		absolute.put("MINING", 500L);

		SessionSnapshot snapshot = SessionSnapshot.capture(session, Collections.<String, Long>emptyMap(), absolute);
		absolute.put("MINING", 999_999L);
		absolute.put("FISHING", 5L);

		assertEquals(Long.valueOf(500L), snapshot.getAbsoluteXpBySkill().get("MINING"));
		assertFalse(snapshot.getAbsoluteXpBySkill().containsKey("FISHING"));
	}

	@Test
	public void captureWithAbsoluteXp_nullSession_stillReturnsAbsent()
	{
		java.util.Map<String, Long> absolute = new java.util.HashMap<>();
		absolute.put("ATTACK", 10L);
		SessionSnapshot snapshot = SessionSnapshot.capture(null, Collections.<String, Long>emptyMap(), absolute);
		assertFalse(snapshot.isPresent());
	}

	@Test
	public void captureWithAbsoluteXp_nullMap_treatedAsEmpty()
	{
		Session session = activeSessionAt(T0);
		SessionSnapshot snapshot = SessionSnapshot.capture(session, Collections.<String, Long>emptyMap(), null);
		assertTrue(snapshot.getAbsoluteXpBySkill().isEmpty());
	}
}
