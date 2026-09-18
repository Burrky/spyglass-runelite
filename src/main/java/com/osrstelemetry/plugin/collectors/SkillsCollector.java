package com.osrstelemetry.plugin.collectors;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.events.EventLedger;
import com.osrstelemetry.plugin.events.EventPayloads;
import com.osrstelemetry.plugin.events.EventType;
import com.osrstelemetry.plugin.model.SkillsState;
import com.osrstelemetry.plugin.session.SessionRuntimeCoordinator;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TelemetryPaths;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * State-file freshness and XP_CHANGE event
 * cadence are two independent things, driven by two separate
 * calls from the plugin's GameTick handler. flushStateIfDirty() can
 * still run every tick (cheap — one small JSON write) so skills.json
 * stays current, but closeXpWindowsIfDue() only actually emits/resets
 * on the slow cadence (~30s, see plugin's tick counter) — training
 * continuously no longer produces one XP_CHANGE event every 0.6s.
 *
 * LEVEL_UP remains immediate/undebounced in onStatChanged(), since
 * it's a discrete milestone, not a rate to aggregate.
 */
public class SkillsCollector
{
	private final Client client;
	private final LocalStateStore store;
	private final EventLedger eventLedger;
	private final OsrsTelemetryConfig config;
	private final SessionRuntimeCoordinator sessionRuntimeCoordinator;
	private final SkillsState state = new SkillsState();

	private final Map<String, Integer> windowBaselineXp = new HashMap<>();
	private final Map<String, String> windowStart = new HashMap<>();

	/**
	 * Separate baseline for LEVEL_UP
	 * detection, independent of state.getSkills() and of
	 * windowBaselineXp. See onStatChanged()'s javadoc for the full
	 * root-cause trace — this is what actually fixes the confirmed
	 * live-validation bug (a LEVEL_UP per skill, all with
	 * previousLevel=0, firing within milliseconds of login).
	 */
	private final Map<String, Integer> lastKnownLevel = new HashMap<>();

	/**
	 * The exact combat skills EventType.RAW_COMBAT_XP_OBSERVED
	 * is allowed to fire for -- Skill enum constants, not strings, so
	 * there is no casing ambiguity with skill.name(). Deliberately
	 * EXCLUDES Skill.MAGIC: mirrors ActivitySignalClassifier.isMagicXpSkill()'s
	 * own "Magic XP is lifecycle-ambiguous, never lifecycle proof" rule
	 * (teleports/alchemy/utility casts, not just combat) -- see that
	 * method's own javadoc. This set exists purely to bound EVENT VOLUME
	 * at the source (this collector emits far more often than
	 * closeXpWindowsIfDue()'s slow cadence, since it reacts to every raw
	 * StatChanged); ActivitySignalClassifier.classifyRawCombatXpObserved()
	 * independently re-checks isCombatSkill()/isMagicXpSkill() as
	 * defense in depth, so this set is never the sole guarantee.
	 */
	private static final Set<Skill> RAW_COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.HITPOINTS);

	/**
	 * Per-skill "last observed XP" baseline for the
	 * IMMEDIATE raw-pulse detection in onStatChanged() below --
	 * deliberately a SEPARATE map from windowBaselineXp (XP_CHANGE's own
	 * aggregation baseline): this code path must never change XP_CHANGE's
	 * accounting, so its own baseline map is left completely
	 * untouched. Same "seed-once-then-diff"
	 * discipline as windowBaselineXp/lastKnownLevel -- a skill's first
	 * observation this session (or since a reset) always seeds silently,
	 * never emits (see evaluateRawCombatXpPulse()).
	 */
	private final Map<String, Integer> lastKnownXpForRawPulse = new HashMap<>();

	/**
	 * Per-skill "last observed XP" baseline for the GENERIC
	 * (all-skills, not just combat) live-delta notification in
	 * onStatChanged() below -- a THIRD, separate baseline map from both
	 * windowBaselineXp (XP_CHANGE's own aggregation baseline) and
	 * lastKnownXpForRawPulse (RAW_COMBAT_XP_OBSERVED's own, combat-only
	 * lifecycle-evidence baseline): this code path must never change either of
	 * those, so its own baseline map is left completely
	 * untouched. Same "seed-once-then-diff,
	 * positive-only" discipline as the other two -- see
	 * evaluateLiveXpDelta().
	 */
	private final Map<String, Integer> lastKnownXpForLiveDelta = new HashMap<>();

	private volatile boolean dirty = false;

	@Inject
	public SkillsCollector(
		Client client,
		LocalStateStore store,
		EventLedger eventLedger,
		OsrsTelemetryConfig config,
		SessionRuntimeCoordinator sessionRuntimeCoordinator)
	{
		this.client = client;
		this.store = store;
		this.eventLedger = eventLedger;
		this.config = config;
		this.sessionRuntimeCoordinator = sessionRuntimeCoordinator;
	}

	/**
	 * Called on account switch, BEFORE AccountContext's stored
	 * accountHash is advanced to the new account — oldAccountHash is
	 * passed explicitly so any outstanding XP_CHANGE window is
	 * attributed to the account that actually earned it, not
	 * whichever account happens to be current by the time this runs.
	 */
	public void resetForAccountSwitch(long oldAccountHash)
	{
		closeXpWindowsFor(oldAccountHash);
		state.getSkills().clear();
		windowBaselineXp.clear();
		windowStart.clear();
		lastKnownLevel.clear();
		lastKnownXpForRawPulse.clear();
		lastKnownXpForLiveDelta.clear();
		dirty = false;
	}

	/**
	 * Called once on login/plugin enable to seed current levels/XP
	 * immediately rather than waiting for the next StatChanged. Gated
	 * on config, since this is
	 * called both on login and on re-enable via ConfigChanged — if
	 * Skills is disabled, this must not write state at all, not just
	 * skip emitting events.
	 *
	 * This deliberately does NOT ALSO seed
	 * windowBaselineXp/windowStart directly from this same read. Real
	 * telemetry forensics showed a burst of ~24 false XP_CHANGE events firing ~30s after
	 * every login, timed to XP_FLUSH_INTERVAL_TICKS — only possible if
	 * client.getSkillExperience(skill) here, called immediately on
	 * GameStateChanged(LOGGED_IN), was reading unsettled/incomplete XP
	 * for some skills, seeding a too-low baseline that the next,
	 * fully-synced periodic read then looked like a genuine gain
	 * against.
	 *
	 * This still writes state.getSkills() (so skills.json stays fresh
	 * immediately — freshness is not sacrificed) but no longer touches
	 * windowBaselineXp/windowStart at all. That job is now solely
	 * closeXpWindowsFor()'s: its own first-sighting guard (see its
	 * javadoc) silently establishes the baseline the first time each
	 * skill is evaluated on the plugin's existing periodic cadence,
	 * by which point RuneLite's data is reliably settled.
	 */
	public void captureInitialState()
	{
		if (!config.collectSkills())
		{
			return;
		}
		for (Skill skill : Skill.values())
		{
			int xp = client.getSkillExperience(skill);
			int level = client.getRealSkillLevel(skill);
			int boostedLevel = client.getBoostedSkillLevel(skill);
			state.getSkills().put(skill.name(), new SkillsState.SkillEntry(level, boostedLevel, xp));
		}
		dirty = true;
	}

	/**
	 * LEVEL_UP compares against lastKnownLevel rather than
	 * state.getSkills().get(skillName), because that map is
	 * populated by captureInitialState()'s own immediate, unsettled
	 * login-time read of client.getRealSkillLevel(skill) (the same
	 * client-sync-gap class of bug already fixed for quests/XP -- see
	 * QuestCollector/SkillsCollector's
	 * captureInitialState() javadocs). A prior implementation using that
	 * map produced ~10 LEVEL_UP events firing within milliseconds of
	 * login, every one with previousLevel=0 — only possible because
	 * captureInitialState() had already written a wrong level=0 entry
	 * into state.getSkills() for those skills, and the first genuine
	 * (correctly-synced) StatChanged then diffed against that poisoned
	 * baseline instead of treating it as a first observation.
	 *
	 * This is architecturally independent from the windowBaselineXp
	 * baseline: state.getSkills() is a DIFFERENT map
	 * from windowBaselineXp, seeded at a different point
	 * (captureInitialState(), not closeXpWindowsFor()), so the
	 * XP-window baseline and this one must each be handled on their own.
	 *
	 * LEVEL_UP compares against lastKnownLevel, a map that
	 * captureInitialState() deliberately never seeds (see its javadoc)
	 * — so the first StatChanged for each skill this session (or since
	 * the last reset) always finds previousLevel == null and silently
	 * seeds via evaluateLevelTransition() below, exactly mirroring
	 * evaluateXpWindow()'s null-baseline contract. Only a real later
	 * transition (e.g. 87 -> 88) against an already-established
	 * baseline emits LEVEL_UP.
	 *
	 * state.getSkills() itself is unchanged — it still updates on every
	 * StatChanged and is what skills.json is written from; it's simply
	 * no longer used as the LEVEL_UP comparison baseline.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (!config.collectSkills())
		{
			return;
		}

		Skill skill = event.getSkill();
		String skillName = skill.name();

		state.getSkills().put(
			skillName,
			new SkillsState.SkillEntry(event.getLevel(), event.getBoostedLevel(), event.getXp())
		);
		dirty = true;

		if (!windowBaselineXp.containsKey(skillName))
		{
			windowBaselineXp.put(skillName, event.getXp());
			windowStart.put(skillName, Instant.now().toString());
		}

		Integer previousLevel = lastKnownLevel.get(skillName);
		LevelTransitionResult result = evaluateLevelTransition(previousLevel, event.getLevel());

		if (result.shouldEmit)
		{
			eventLedger.append(
				client.getAccountHash(),
				EventType.LEVEL_UP,
				new EventPayloads.LevelUp(skillName, previousLevel, event.getLevel(), event.getXp())
			);
		}

		lastKnownLevel.put(skillName, event.getLevel());

		if (RAW_COMBAT_SKILLS.contains(skill))
		{
			Integer previousRawXp = lastKnownXpForRawPulse.get(skillName);
			lastKnownXpForRawPulse.put(skillName, event.getXp());
			RawCombatXpPulseResult pulseResult = evaluateRawCombatXpPulse(previousRawXp, event.getXp());
			if (pulseResult.shouldEmit)
			{
				eventLedger.append(
					client.getAccountHash(),
					EventType.RAW_COMBAT_XP_OBSERVED,
					new EventPayloads.RawCombatXpObserved(skillName)
				);
			}
		}

		// Deliberately GENERIC across every RuneLite skill above -- unlike
		// RAW_COMBAT_SKILLS above, which exists only to bound
		// RAW_COMBAT_XP_OBSERVED's own lifecycle-evidence volume. This is
		// a SEPARATE, small live-XP notification path (see
		// SessionRuntimeCoordinator.recordLiveXpDelta()'s own javadoc):
		// it never reuses RAW_COMBAT_XP_OBSERVED as an XP-value transport
		// (that signal structurally carries no XP value at all -- see its
		// own javadoc), never touches EventLedger/durable storage, and
		// never affects windowBaselineXp/lastKnownXpForRawPulse in any
		// way. lastKnownXpForLiveDelta is its own third, independent
		// baseline map -- see evaluateLiveXpDelta()'s own javadoc for the
		// seed-once-then-diff, positive-only contract that keeps a first
		// observation (or an account switch's fresh baseline) from ever
		// being misread as an instantaneous live gain, and a decrease/
		// reset/reseed from ever fabricating a positive one.
		Integer previousLiveXp = lastKnownXpForLiveDelta.get(skillName);
		lastKnownXpForLiveDelta.put(skillName, event.getXp());
		long liveDelta = evaluateLiveXpDelta(previousLiveXp, event.getXp());
		if (liveDelta > 0)
		{
			sessionRuntimeCoordinator.recordLiveXpDelta(skillName, liveDelta);
		}

		// Deliberately UNCONDITIONAL (unlike the gated
		// recordLiveXpDelta() dispatch above) -- this is not a display
		// concern, it is "the latest known absolute XP for this skill,
		// full stop," and even a skill's very first (seed-only)
		// observation is a genuine, exact absolute baseline worth
		// keeping. See SessionRuntimeCoordinator.noteAbsoluteXp()'s own
		// javadoc for how this feeds an exact split of an XP_CHANGE
		// window that spans a real session switch.
		sessionRuntimeCoordinator.noteAbsoluteXp(skillName, event.getXp());
	}

	/**
	 * Without this method, OsrsTelemetryPlugin's collectSkills
	 * re-enable branch calling ONLY captureInitialState() would leave a
	 * bug: since captureInitialState() correctly does not touch
	 * windowBaselineXp/windowStart/lastKnownLevel (see its own javadoc),
	 * and nothing else clears or reseeds those maps on disable either
	 * (onConfigChanged()'s disable branch is intentionally a no-op — see
	 * its own javadoc), they would simply keep whatever value they held
	 * from before Skills was disabled. Both onStatChanged() and
	 * closeXpWindowsFor() are gated
	 * on config.collectSkills() and no-op while disabled, so
	 * state.getSkills() also goes stale during that window — meaning any
	 * XP/level actually gained while disabled would, at the first real
	 * StatChanged after re-enable, get diffed against the STALE
	 * pre-disable baseline instead of a fresh one — fabricating a single
	 * XP_CHANGE/LEVEL_UP that misrepresents an entire disabled-period
	 * gap as an instantaneous post-re-enable change: "persistence state
	 * accidentally reused as comparison state." This method is what
	 * prevents it.
	 *
	 * Unlike the LOGIN case (where seeding must be deferred because the
	 * very first read can be unsettled — see captureInitialState()'s own
	 * javadoc), re-enabling mid-session has NO sync-gap risk: the client
	 * has already been logged in and running normally, so an immediate
	 * reseed from the read captureInitialState() just took is safe.
	 * Mirrors SlayerCollector.seedSilently() and
	 * QuestCollector.checkAndFlush() — both already reseed immediately
	 * and unconditionally at their own re-enable call sites for the same
	 * reason.
	 *
	 * Called ONLY from OsrsTelemetryPlugin.onConfigChanged()'s
	 * collectSkills re-enable branch, immediately after
	 * captureInitialState() has refreshed state.getSkills().
	 */
	public void reseedTransitionBaselinesSilently()
	{
		if (!config.collectSkills())
		{
			return;
		}
		windowBaselineXp.clear();
		windowStart.clear();
		lastKnownLevel.clear();
		lastKnownXpForRawPulse.clear();
		String now = Instant.now().toString();
		for (Map.Entry<String, SkillsState.SkillEntry> entry : state.getSkills().entrySet())
		{
			windowBaselineXp.put(entry.getKey(), entry.getValue().getXp());
			windowStart.put(entry.getKey(), now);
			lastKnownLevel.put(entry.getKey(), entry.getValue().getLevel());
			lastKnownXpForRawPulse.put(entry.getKey(), entry.getValue().getXp());
		}
	}

	/**
	 * Pure, Client/EventLedger-independent core of the LEVEL_UP
	 * decision — factored out for the same reason as
	 * evaluateXpWindow()/QuestCollector.justFinished(): unit-testable
	 * without mocking RuneLite's Client. previousLevel == null means
	 * this skill has never been observed since the last reset — always
	 * seed silently, never emit, regardless of currentLevel (this is
	 * the property that makes an initial/unsynced-then-corrected read
	 * safe: an existing level 87 first observed as 87 can never be
	 * misreported as 0 -> 87). A real transition only exists once a
	 * baseline is already established AND currentLevel is strictly
	 * greater than it.
	 */
	static LevelTransitionResult evaluateLevelTransition(Integer previousLevel, int currentLevel)
	{
		if (previousLevel == null)
		{
			return LevelTransitionResult.seed();
		}
		if (currentLevel > previousLevel)
		{
			return LevelTransitionResult.levelUp();
		}
		return LevelTransitionResult.noChange();
	}

	static final class LevelTransitionResult
	{
		final boolean shouldEmit;

		private LevelTransitionResult(boolean shouldEmit)
		{
			this.shouldEmit = shouldEmit;
		}

		static LevelTransitionResult seed()
		{
			return new LevelTransitionResult(false);
		}

		static LevelTransitionResult levelUp()
		{
			return new LevelTransitionResult(true);
		}

		static LevelTransitionResult noChange()
		{
			return new LevelTransitionResult(false);
		}
	}

	/**
	 * Pure, Client/EventLedger-independent core of the
	 * IMMEDIATE raw-combat-XP-pulse decision -- factored out for the
	 * exact same reason as evaluateXpWindow()/evaluateLevelTransition():
	 * unit-testable without mocking RuneLite's Client. previousXp ==
	 * null means this skill's raw-pulse baseline has never been observed
	 * since the last reset (fresh session, account switch, or Skills
	 * re-enable) -- always seed silently, never emit, regardless of
	 * currentXp's value, mirroring evaluateXpWindow()'s own "an existing
	 * large XP total must never be misread as an instantaneous gain on
	 * first observation" contract. A real pulse only exists once a
	 * baseline is already established AND currentXp is strictly greater
	 * than it (mirrors evaluateLevelTransition()'s strict-greater-than
	 * convention).
	 */
	static RawCombatXpPulseResult evaluateRawCombatXpPulse(Integer previousXp, int currentXp)
	{
		if (previousXp == null)
		{
			return RawCombatXpPulseResult.seed();
		}
		if (currentXp > previousXp)
		{
			return RawCombatXpPulseResult.pulse();
		}
		return RawCombatXpPulseResult.noChange();
	}

	static final class RawCombatXpPulseResult
	{
		final boolean shouldEmit;

		private RawCombatXpPulseResult(boolean shouldEmit)
		{
			this.shouldEmit = shouldEmit;
		}

		static RawCombatXpPulseResult seed()
		{
			return new RawCombatXpPulseResult(false);
		}

		static RawCombatXpPulseResult pulse()
		{
			return new RawCombatXpPulseResult(true);
		}

		static RawCombatXpPulseResult noChange()
		{
			return new RawCombatXpPulseResult(false);
		}
	}

	/**
	 * Pure, Client/EventLedger-independent core of the
	 * IMMEDIATE live-XP-delta decision, generalized across EVERY
	 * RuneLite skill -- unlike evaluateRawCombatXpPulse() above, this is
	 * never restricted to RAW_COMBAT_SKILLS, since Woodcutting/Agility/
	 * every other skill must update the live display too. Same
	 * "seed-once-then-diff, positive-only" discipline as
	 * evaluateRawCombatXpPulse()/evaluateXpWindow():
	 * {@code previousXp == null} means this skill's live-delta baseline
	 * has never been observed since the last reset (fresh session,
	 * account switch) -- always returns {@code 0L} (seed silently),
	 * regardless of {@code currentXp}'s value, exactly mirroring
	 * evaluateXpWindow()'s "an existing large XP total must never be
	 * misread as an instantaneous gain on first observation" contract.
	 * {@code currentXp <= previousXp} (no change, or a decrease/reset/
	 * reseed) also returns {@code 0L} -- this layer must never fabricate
	 * a positive live gain from either. Only a strictly-greater reading
	 * against an already-established baseline returns the genuine,
	 * positive delta.
	 */
	static long evaluateLiveXpDelta(Integer previousXp, int currentXp)
	{
		if (previousXp == null)
		{
			return 0L;
		}
		if (currentXp > previousXp)
		{
			return currentXp - previousXp;
		}
		return 0L;
	}

	public void flushStateIfDirty()
	{
		if (!config.collectSkills() || !dirty)
		{
			return;
		}
		dirty = false;
		state.setLastUpdated(Instant.now().toString());
		store.write(TelemetryPaths.stateFile(client.getAccountHash(), "skills"), state);
	}

	/** Called on the plugin's slow tick cadence (see plugin's
	 * XP_FLUSH_INTERVAL_TICKS), not every tick. */
	public void closeXpWindowsIfDue()
	{
		if (!config.collectSkills())
		{
			return;
		}
		closeXpWindowsFor(client.getAccountHash());
	}

	private void closeXpWindowsFor(long accountHash)
	{
		String now = Instant.now().toString();
		for (Map.Entry<String, SkillsState.SkillEntry> entry : state.getSkills().entrySet())
		{
			String skillName = entry.getKey();
			int currentXp = entry.getValue().getXp();

			Integer existingBaseline = windowBaselineXp.containsKey(skillName) ? windowBaselineXp.get(skillName) : null;
			XpWindowResult result = evaluateXpWindow(existingBaseline, currentXp);

			if (result.shouldSeed)
			{
				// First time this skill's window is evaluated this
				// session (or since a reset/clear) — silently establish
				// the baseline. See class javadoc: this is the FIX for
				// the startup false-XP_CHANGE-burst bug. Mirrors
				// QuestCollector.justFinished()'s null-previous guard:
				// XP that was already there before observation began
				// must never be reported as a gain. NOTE this also
				// fixes a pre-existing latent bug in the old
				// getOrDefault(...,currentXp) fallback, which computed
				// delta=0 correctly but never actually persisted a
				// baseline into the map — meaning an unseeded skill
				// could never self-bootstrap via this path at all.
				windowBaselineXp.put(skillName, currentXp);
				windowStart.put(skillName, now);
				continue;
			}

			if (result.delta > 0)
			{
				eventLedger.append(
					accountHash,
					EventType.XP_CHANGE,
					new EventPayloads.XpChange(
						skillName, existingBaseline, currentXp, result.delta,
						windowStart.getOrDefault(skillName, now), now
					)
				);
				windowBaselineXp.put(skillName, currentXp);
				windowStart.put(skillName, now);
			}
		}
	}

	/**
	 * Pure, Client/EventLedger-independent core of the "seed-once-then-
	 * diff" XP window decision — factored out for the same reason as
	 * QuestCollector.justFinished(): unit-testable without mocking
	 * RuneLite's Client. existingBaseline == null means this skill has
	 * never been evaluated since the last reset — always seed, never a
	 * delta, regardless of how large currentXp is (this is the
	 * property that makes an initial/incomplete-then-corrected read
	 * safe: whatever the first observed value is, it can only ever
	 * become the baseline, never a reported gain).
	 */
	static XpWindowResult evaluateXpWindow(Integer existingBaseline, int currentXp)
	{
		if (existingBaseline == null)
		{
			return XpWindowResult.seed();
		}
		return XpWindowResult.delta(currentXp - existingBaseline);
	}

	static final class XpWindowResult
	{
		final boolean shouldSeed;
		final int delta;

		private XpWindowResult(boolean shouldSeed, int delta)
		{
			this.shouldSeed = shouldSeed;
			this.delta = delta;
		}

		static XpWindowResult seed()
		{
			return new XpWindowResult(true, 0);
		}

		static XpWindowResult delta(int delta)
		{
			return new XpWindowResult(false, delta);
		}
	}
}
