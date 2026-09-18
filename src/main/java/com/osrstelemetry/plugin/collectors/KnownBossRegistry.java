package com.osrstelemetry.plugin.collectors;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

/**
 * An earlier design resolved a candidate boss name ONLY after
 * ActivityKillCountCollector had already parsed a "Your X kill count
 * is:" message for that account in the CURRENT runtime. That defeated
 * the entire point of the feature: a fresh plugin start facing a
 * brand-new boss (e.g. Zulrah) could never be recognized as
 * BOSS_ACTIVITY_CONTEXT until AFTER its first BOSS_KILL landed. See
 * this class's own tests for the regression coverage.
 *
 * A small, per-account registry of boss names this plugin can
 * recognize, used to resolve an NPC name (from InteractingChanged --
 * see BossActivityContextCollector) into a canonical boss display
 * name for the NEW, non-authoritative BOSS_ACTIVITY_CONTEXT signal.
 * Deliberately dependency-free: no RuneLite API type appears anywhere
 * in this class, so it is fully unit-testable with plain JUnit, same
 * as every other pure/testable class this project already has.
 *
 * TWO SOURCES OF TRUTH now feed recognition:
 *
 *  1. STATIC_SEED_BOSS_NAMES_NORMALIZED -- a small, explicit,
 *     project-owned, hardcoded map of normalized-name -> canonical
 *     display name for well-known boss encounters, present from the
 *     moment the plugin starts, with ZERO dependency on having
 *     observed any kill-count message in the current runtime. This is
 *     what makes fresh-start recognition possible at all. Keying by
 *     normalized name -> canonical display name (rather than a plain
 *     Set<String> of normalized names) is what lets seeded resolution
 *     return the correct canonical casing regardless of how the
 *     candidate name itself was cased/whitespaced.
 *
 *     IMPORTANT / HONEST SCOPE: no authoritative local RuneLite data
 *     source was available to derive this list from (re-audited: no
 *     RuneLite client jar is vendored or present in any Gradle/Maven
 *     cache in this environment -- `runeLiteVersion = 'latest.release'`
 *     in build.gradle is resolved fresh over the network at build
 *     time, never fetched or cached here; no NpcID/NpcNames/
 *     NpcOverrides/BossTimersPlugin reference exists anywhere in this
 *     repo; no vendor/docs/reference directory carries any upstream
 *     RuneLite source; no src/main/resources boss data file exists).
 *     This list is therefore a project-owned, manually curated SEED,
 *     not something derived from or verified against RuneLite
 *     internals. Kept deliberately small and limited to boss names
 *     the author is confident are canonical, single, unambiguous
 *     in-game NPC/encounter names -- extend it over time as more
 *     bosses are confirmed correct, rather than trying to be
 *     exhaustive up front.
 *
 *  2. CONFIRMED BOSS NAMES (per account) -- harvested for free from
 *     ActivityKillCountCollector's own existing "Your X kill count
 *     is:" chat-message parsing, the exact same authoritative source
 *     BOSS_KILL already trusts. See recordConfirmedBoss(), called from
 *     ActivityKillCountCollector.recordKillCount() once a boss name is
 *     parsed from a real kill-count message -- never fabricated,
 *     never guessed. This AUGMENTS the static seed: a boss not in the
 *     seed list still gets pre-kill recognition on its SECOND and
 *     later encounters, once its name has been confirmed once. It is
 *     never a PREREQUISITE for a boss already in the static seed.
 *
 * NPC_NAME_ALIASES -- a minimal, explicit, hand-maintained table ONLY
 * for the disclosed multi-NPC-name case: Dawn and Dusk (the two NPCs
 * that together make up the Grotesque Guardians boss encounter) both
 * alias to the single canonical boss display name "Grotesque
 * Guardians". This table is deliberately NOT a general NPC-ID-to-boss
 * database -- every other supported boss is resolved purely via
 * direct name equality (the NPC's own name matches the canonical boss
 * name directly, e.g. interacting with an NPC named "Zulrah" resolves
 * because "Zulrah" itself is either in the static seed or has been
 * confirmed). The alias TARGET string stored here is only ever used as
 * a lookup key into the same static-seed/confirmed resolution path
 * (see resolveRecognizedDisplayName()) -- it is never returned
 * literally, so a typo or miscasing in this table can never itself
 * leak into a returned display name; the canonical casing always comes
 * from STATIC_SEED_BOSS_NAMES_NORMALIZED or the per-account confirmed
 * map. KNOWN COVERAGE GAP: other bosses with multi-NPC-name or
 * phase-based naming (differently-named forms/phases) are NOT
 * supported by this alias table today -- only the Dawn/Dusk case has
 * been explicitly verified and added; do not assume broad generic
 * coverage of every multi-phase boss.
 *
 * An alias only ever resolves once the ALIAS TARGET itself is
 * actually recognized (via the static seed OR a per-account confirmed
 * kill) -- see resolveCandidateBossName() -- the alias table alone is
 * never sufficient proof by itself.
 *
 * This class never produces the final session ActivityIdentity/
 * activityKey itself -- ActivitySignalClassifier.bossIdentity() (the
 * SAME canonicalization BOSS_KILL already uses) is what turns the
 * display name this class resolves into a session identity, so there
 * is exactly one place in this codebase that owns that canonicalization.
 * This class's own normalize() is a separate, much smaller thing: pure
 * case/whitespace-insensitive MATCHING against the recognized-name
 * set, not identity-key production.
 */
@Singleton
public class KnownBossRegistry
{
	/**
	 * Project-owned static seed of normalized boss name -> canonical
	 * boss display name, present at construction with no
	 * account/runtime dependency. See class javadoc for the honest
	 * scope of this list -- it is a curated seed, NOT derived from any
	 * verified RuneLite data source available in this environment.
	 * Keyed by normalized name so lookup is case/whitespace-insensitive
	 * while the VALUE always preserves the canonical display casing --
	 * this is what lets seeded resolution return the correct canonical
	 * name regardless of how the candidate itself was cased/
	 * whitespaced. Kept modest and limited to boss names the author is
	 * confident are canonical, single, unambiguous in-game names.
	 * Extend as more bosses are confirmed.
	 */
	private static final Map<String, String> STATIC_SEED_BOSS_NAMES_NORMALIZED;

	static
	{
		Map<String, String> seed = new HashMap<>();
		addSeed(seed, "Zulrah");
		addSeed(seed, "Grotesque Guardians");
		addSeed(seed, "Vorkath");
		addSeed(seed, "General Graardor");
		addSeed(seed, "Kree'arra");
		addSeed(seed, "K'ril Tsutsaroth");
		addSeed(seed, "Commander Zilyana");
		addSeed(seed, "Kalphite Queen");
		addSeed(seed, "Giant Mole");
		addSeed(seed, "Dagannoth Rex");
		addSeed(seed, "Dagannoth Prime");
		addSeed(seed, "Dagannoth Supreme");
		addSeed(seed, "Cerberus");
		addSeed(seed, "Thermonuclear Smoke Devil");
		addSeed(seed, "Alchemical Hydra");
		addSeed(seed, "Callisto");
		addSeed(seed, "Venenatis");
		addSeed(seed, "Vet'ion");
		addSeed(seed, "Scorpia");
		STATIC_SEED_BOSS_NAMES_NORMALIZED = Collections.unmodifiableMap(seed);
	}

	private static void addSeed(Map<String, String> seed, String canonicalDisplayName)
	{
		seed.put(normalize(canonicalDisplayName), canonicalDisplayName);
	}

	/**
	 * NPC name (as it appears on the NPC actor / in InteractingChanged)
	 * -> canonical boss display name. Deliberately tiny -- see class
	 * javadoc. Extend this table only if a future boss encounter is
	 * confirmed to use multiple differently-named NPCs the same way
	 * Grotesque Guardians does; do not add single-NPC bosses here, they
	 * already resolve via direct name equality.
	 */
	private static final Map<String, String> NPC_NAME_ALIASES = new ConcurrentHashMap<>();

	static
	{
		NPC_NAME_ALIASES.put(normalize("Dawn"), "Grotesque Guardians");
		NPC_NAME_ALIASES.put(normalize("Dusk"), "Grotesque Guardians");
	}

	/**
	 * accountHash -> (normalized boss name -> confirmed display name).
	 * The display name preserves the exact casing
	 * ActivityKillCountCollector observed in the real kill-count
	 * message, so a resolved BOSS_ACTIVITY_CONTEXT candidate and a
	 * later BOSS_KILL for the same boss present identically. Per-account
	 * so confirming a boss for one account never leaks to another (see
	 * resetForAccountSwitch()). Additive on top of the static seed --
	 * never required by it.
	 */
	private final Map<Long, Map<String, String>> confirmedByAccount = new ConcurrentHashMap<>();

	/**
	 * Called from ActivityKillCountCollector.recordKillCount() once a
	 * boss name is parsed from a real, authoritative "Your X kill count
	 * is:" message -- never called speculatively, never called with a
	 * guessed name.
	 */
	public void recordConfirmedBoss(long accountHash, String displayName)
	{
		if (displayName == null || displayName.trim().isEmpty())
		{
			return;
		}
		confirmedByAccount
			.computeIfAbsent(accountHash, key -> new ConcurrentHashMap<>())
			.put(normalize(displayName), displayName.trim());
	}

	/**
	 * @return the canonical boss display name if `npcName` resolves to
	 * a boss this class recognizes -- either because it (or its alias
	 * target) is in the static seed list, or because it (or its alias
	 * target) has actually been confirmed for this account via a real
	 * kill-count message; null otherwise. An unrecognized NPC name, or
	 * a recognized alias whose target is neither seeded nor confirmed,
	 * both resolve to null rather than a guess.
	 */
	public String resolveCandidateBossName(long accountHash, String npcName)
	{
		if (npcName == null || npcName.trim().isEmpty())
		{
			return null;
		}

		String normalizedNpcName = normalize(npcName);
		Map<String, String> confirmed = confirmedByAccount.get(accountHash);

		String aliasTarget = NPC_NAME_ALIASES.get(normalizedNpcName);
		if (aliasTarget != null)
		{
			// Dawn/Dusk -> only resolves once "Grotesque Guardians" itself
			// is actually recognized (seed or confirmed) -- see class
			// javadoc. The alias table alone is never sufficient proof.
			// aliasTarget is used only as a lookup key here -- the actual
			// returned value always comes from the seed/confirmed map, so
			// the canonical casing is never taken from this literal.
			return resolveRecognizedDisplayName(aliasTarget, confirmed);
		}

		return resolveRecognizedDisplayName(npcName, confirmed);
	}

	/**
	 * Shared recognition check: seed first (present from construction,
	 * canonical casing looked up from STATIC_SEED_BOSS_NAMES_NORMALIZED's
	 * value, never the raw candidate string), then falls back to this
	 * account's confirmed set (canonical casing as observed in the real
	 * kill-count message).
	 */
	private static String resolveRecognizedDisplayName(String candidateName, Map<String, String> confirmedForAccount)
	{
		String normalizedCandidate = normalize(candidateName);

		String seededDisplayName = STATIC_SEED_BOSS_NAMES_NORMALIZED.get(normalizedCandidate);
		if (seededDisplayName != null)
		{
			return seededDisplayName;
		}

		if (confirmedForAccount != null)
		{
			String confirmedDisplayName = confirmedForAccount.get(normalizedCandidate);
			if (confirmedDisplayName != null)
			{
				return confirmedDisplayName;
			}
		}

		return null;
	}

	/**
	 * Per-account reset, mirroring every other collector's own
	 * resetForAccountSwitch() convention in this project (see
	 * ActivityKillCountCollector.resetForAccountSwitch(),
	 * ServerNpcLootCollector.resetForAccountSwitch()) -- confirming a
	 * boss for account A must never leak into account B's resolution.
	 * The static seed is unaffected -- it is not per-account state.
	 */
	public void resetForAccountSwitch(long accountHash)
	{
		confirmedByAccount.remove(accountHash);
	}

	/** Pure -- unit-testable without a Client. Trim + lowercase matching
	 * only, deliberately NOT the same method as
	 * ActivitySignalClassifier.bossIdentity() (this class never imports
	 * the session package) -- see class javadoc for why this is not
	 * "duplicated normalization logic": this is small, local, one-line
	 * MATCHING, while bossIdentity() alone owns producing the actual
	 * session ActivityIdentity/activityKey. */
	static String normalize(String s)
	{
		return s.trim().toLowerCase(Locale.ROOT);
	}
}
