package com.osrstelemetry.plugin.session;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * The typed
 * session record — both the in-memory working object the lifecycle
 * engine mutates AND the exact document persisted to
 * session_state.json / sessions/{sessionId}.json (see
 * SessionPersistence). One object serves both purposes deliberately,
 * matching this project's existing model-class convention (StorageState,
 * SlayerState, ActivityState all double as their own persisted
 * document) rather than inventing a parallel DTO.
 *
 * TIMESTAMP CONVENTION: every timestamp field is a nullable ISO-8601
 * String (Instant#toString()/Instant#parse()), matching every other
 * timestamp field in this project (StorageState.lastObservedAt,
 * SlayerState.lastUpdated, TelemetryEvent.occurredAt) rather than a
 * java.time.Instant field directly. This is a deliberate choice, not
 * an oversight: LocalStateStore serializes with a plain `new Gson()`/
 * `GsonBuilder` with no registered TypeAdapters (see its own javadoc
 * for why it isn't being redesigned), and Gson cannot
 * reliably round-trip java.time.Instant by reflection alone across
 * JDK versions. Storing Strings keeps Session directly compatible with
 * LocalStateStore's existing write()/writeAndWait()/readIfExists()
 * with zero changes to that class. The convenience *Instant()
 * accessors below exist so SessionLifecycleEngine/SessionPersistence
 * never have to sprinkle Instant.parse()/toString() calls inline.
 *
 * MUTATION IS PACKAGE-PRIVATE BY DESIGN — state cannot
 * make illegal transition paths through public API: setters are
 * `@Setter(AccessLevel.PACKAGE)`, so nothing outside
 * com.osrstelemetry.plugin.session can mutate state/timestamps/
 * accumulatedActiveDurationMillis directly. The only code that may
 * legally transition a Session is SessionLifecycleEngine (and
 * SessionPersistence's rehydration path, which delegates the actual
 * transition logic back to SessionLifecycleEngine.rehydrate() rather
 * than mutating fields itself). External code (a future UI/classifier)
 * can only read a Session, never force it into an illegal state.
 * Gson's field-reflection deserialization is unaffected by setter
 * visibility — it never calls setters at all.
 *
 * startingLoadout / endingLoadout below extend this same persisted
 * document with an optional, slot-exact inventory+equipment snapshot
 * (see LoadoutSnapshot). Both are nullable and additive: an old
 * sessions/{sessionId}.json written before these fields existed simply has
 * neither field, and Gson's default missing-field behavior leaves
 * them null on deserialization -- no migration, no rewriting of old
 * files, no risk to existing persistence (see LoadoutSnapshot's own
 * javadoc, "BACKWARD COMPATIBILITY"). Mutated the same
 * package-private way as every other field here -- only code in
 * com.osrstelemetry.plugin.session may attach a resolved loadout,
 * never SessionRuntimeCoordinator/
 * SessionLifecycleEngine, and never in a way that replaces an
 * already-attached startingLoadout (identity refinement
 * within the same sessionId must not replace the starting loadout --
 * satisfied automatically by resolving it at most once, the first
 * time this Session is persisted, and skipping resolution whenever
 * startingLoadout is already non-null).
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@EqualsAndHashCode
public class Session
{
	private String sessionId;
	private ActivityIdentity activityIdentity;
	private SessionState state;

	private LoadoutSnapshot startingLoadout;
	private LoadoutSnapshot endingLoadout;

	private String startedAt;
	private String lastActiveAt;
	private String suspendedAt;
	private String resumeWindowExpiresAt;
	private String finalizedAt;

	/**
	 * Sum of (qualifying-heartbeat-to-qualifying-heartbeat) elapsed
	 * time for the SAME activity while ACTIVE, per the exact rule in
	 * SessionLifecycleEngine's javadoc. Never includes any idle/
	 * suspended/offline interval. This is the only quantity future
	 * rate calculations (XP/hr, GP/hr) may divide by — never
	 * (now - startedAt).
	 */
	private long accumulatedActiveDurationMillis;

	private SessionAggregates aggregates = new SessionAggregates();

	Instant startedAtInstant()
	{
		return parse(startedAt);
	}

	Instant lastActiveAtInstant()
	{
		return parse(lastActiveAt);
	}

	Instant suspendedAtInstant()
	{
		return parse(suspendedAt);
	}

	Instant resumeWindowExpiresAtInstant()
	{
		return parse(resumeWindowExpiresAt);
	}

	/**
	 * Same
	 * nullable-String-to-Instant convenience as the four accessors
	 * above, added for LoadoutResolver's ending-loadout resolution
	 * (SessionPersistence.persistFinalized()) -- finalizedAt itself
	 * was already a field, this is only a missing accessor.
	 */
	Instant finalizedAtInstant()
	{
		return parse(finalizedAt);
	}

	private static Instant parse(String value)
	{
		return value == null ? null : Instant.parse(value);
	}
}
