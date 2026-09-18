package com.osrstelemetry.plugin.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Small, representative
 * registry-level tests -- deliberately NOT one test per cataloged task
 * alias (per explicit instruction: "do not add dozens of combinatorial
 * tests for every task alias"). Behavioral pipeline coverage (how
 * membership actually participates in session classification) lives in
 * ActivitySignalClassifierTest and SessionRuntimeCoordinatorTest.
 */
public class SlayerTaskFamilyRegistryTest
{
	// Test 1: the worked example from the task spec itself.
	@Test
	public void pyrefiendBelongsToPyrefiendsFamily()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Pyrefiends", "Pyrefiend"));
	}

	// Test 2: the boss-alternative-name example from the task spec.
	@Test
	public void flamingPyrelordBelongsToPyrefiendsFamily()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Pyrefiends", "Flaming pyrelord"));
	}

	// Test 3: a genuinely unrelated NPC is never hand-waved in.
	@Test
	public void unrelatedNpcDoesNotBelongToPyrefiendsFamily()
	{
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily("Pyrefiends", "Goat"));
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily("Pyrefiends", "Cow"));
	}

	// Case normalization / whitespace: matching must not be case- or
	// whitespace-sensitive.
	@Test
	public void membershipIsCaseAndWhitespaceInsensitive()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("  PYREFIENDS  ", "flaming PYRELORD"));
	}

	// Multi-NPC task family (Gargoyles/Dawn/Dusk) -- the exact data the
	// boss-precedence regression test relies on NOT flattening
	// Grotesque Guardians (see ActivitySignalClassifierTest).
	@Test
	public void gargoylesFamilyIncludesDawnAndDusk()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Gargoyles", "Dawn"));
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Gargoyles", "Dusk"));
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Gargoyles", "Gargoyle"));
	}

	// Boss-alternative-target example named directly in the task spec.
	@Test
	public void blueDragonsFamilyIncludesVorkathBossAlternative()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Blue dragons", "Vorkath"));
	}

	// An uncatalogued task (not in the static table at all) falls back
	// ONLY to the exact same deterministic trailing-"s" strip RuneLite's
	// own SlayerPlugin unconditionally applies -- never a fuzzy/
	// approximate match, and never hand-waving an unrelated NPC in.
	@Test
	public void uncataloguedTaskFallsBackToSingularStripOnly()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Totally new task creatures", "Totally new task creature"));
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily("Totally new task creatures", "Some other monster"));
	}

	@Test
	public void nullTaskOrNpcNameNeverMatches()
	{
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily(null, "Pyrefiend"));
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily("Pyrefiends", null));
	}

	// ------------------------------------------------------------------
	// Superior-coverage note: RuneLite's own Task enum does not
	// (yet) list every currently-live Superior slayer monster as a
	// target-name alias -- confirmed by direct source inspection (see
	// this class's own SUPERIOR-COVERAGE SOURCE javadoc). Elder aquanite
	// is the gap this fills.
	// ------------------------------------------------------------------

	// An Elder aquanite kill must stay on-task for an assigned Aquanites
	// task rather than being proposed as an off-task candidate switch.
	@Test
	public void elderAquaniteBelongsToAquanitesFamily()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Aquanites", "Elder aquanite"));
	}

	// A superior alias already present (Nechryael ->
	// Nechryarch) must remain covered -- this registry only ever ADDS
	// entries, never removes/reshapes an existing one.
	@Test
	public void nechryarchStillBelongsToNechryaelFamily()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Nechryael", "Nechryarch"));
	}

	// A genuinely unrelated NPC is never hand-waved into a family this
	// pass touched, even one superficially "boss-like" in name.
	@Test
	public void unrelatedNpcNeverBelongsToAquanitesFamily()
	{
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily("Aquanites", "Goat"));
		assertFalse(SlayerTaskFamilyRegistry.belongsToTaskFamily("Aquanites", "General Graardor"));
	}

	// Basilisk Knight is a stronger, still-Basilisks-task-assignable NPC
	// (confirmed via its own OSRS Wiki infobox category, not invented) --
	// it and its own superior (Basilisk sentinel) both belong on the
	// EXISTING Basilisks family, never a separate invented task.
	@Test
	public void basiliskKnightAndItsSuperiorBelongToBasilisksFamily()
	{
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Basilisks", "Basilisk Knight"));
		assertTrue(SlayerTaskFamilyRegistry.belongsToTaskFamily("Basilisks", "Basilisk sentinel"));
	}
}
