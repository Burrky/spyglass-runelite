package com.osrstelemetry.plugin.farming;

import static com.osrstelemetry.plugin.farming.FarmingFixtures.ACCOUNT;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.OTHER_ACCOUNT;
import static com.osrstelemetry.plugin.farming.FarmingFixtures.T0;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Run intent persistence, ownership, lifecycle (pause / resume / end / complete / expire) and the outcome record. */
public class FarmingRunStoreTest
{
	private static final Gson GSON = new Gson();
	private static final long MIN = 60_000L;
	private static final FarmingOwner A_OWNER = FarmingOwner.standard(ACCOUNT);
	private static final FarmingOwner B_OWNER = FarmingOwner.standard(OTHER_ACCOUNT);
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final int EMPTY = 3;
	private static final int RANARR_READY = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
	private static final int RANARR_SEED = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.GROWING, 0);

	private Path root;
	private Filepath rootPath;
	private LocalStateStore stateStore;
	private final Deque<Runnable> loads = new ArrayDeque<>();
	private Function<FarmingOwner, Filepath> dirs;
	private final Map<String, PatchKnowledge> known = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		root = Files.createTempDirectory("farming-runs");
		rootPath = TestFilepaths.rooted(root);
		stateStore = new LocalStateStore(GSON);
		stateStore.start();
		dirs = owner ->
		{
			Filepath dir = rootPath.joinSegment(Long.toString(owner.getAccountHash())).joinSegment("profiles").joinSegment(owner.getProfileKey())
				.joinSegment("farming");
			try
			{
				dir.createDirectories();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
			return dir;
		};
	}

	@After
	public void tearDown() throws Exception
	{
		stateStore.shutdown();
		rootPath.deleteRecursively();
	}

	private FarmingRunStore newStore()
	{
		return new FarmingRunStore(stateStore, dirs, loads::add, CATALOG);
	}

	private FarmingRunStore loaded(FarmingOwner owner)
	{
		FarmingRunStore s = newStore();
		s.switchOwner(owner, T0);
		drainLoads();
		return s;
	}

	private void drainLoads()
	{
		while (!loads.isEmpty())
		{
			loads.poll().run();
		}
	}

	private void drainWrites()
	{
		stateStore.writeAndWait(rootPath.joinSegment("barrier.json"), Collections.singletonMap("b", 1), 10_000);
	}

	private FarmingRunStore.Document readDoc(FarmingOwner owner)
	{
		return stateStore.readIfExists(dirs.apply(owner).joinSegment(FarmingRunStore.INTENT_FILE), FarmingRunStore.Document.class);
	}

	/** A herb run with no compost chosen (the tests of other optional work). */
	private FarmingRunRequest herbRunNoCompost(String... ids)
	{
		Map<String, FarmingProduce> planned = new LinkedHashMap<>();
		for (String id : ids)
		{
			planned.put(id, FarmingProduce.RANARR);
		}
		return new FarmingRunRequest(FarmingRunType.HERB, Arrays.asList(ids), Collections.emptyList(), planned, FarmingPlanDraft.Compost.NONE, false);
	}

	private FarmingRunRequest herbRun(String... ids)
	{
		Map<String, FarmingProduce> planned = new LinkedHashMap<>();
		for (String id : ids)
		{
			planned.put(id, FarmingProduce.RANARR);
		}
		return new FarmingRunRequest(FarmingRunType.HERB, Arrays.asList(ids), Collections.singletonList("herb.harmony"), planned,
			FarmingPlanDraft.Compost.ULTRACOMPOST, false);
	}

	@Test
	public void start_persistsTheIntent_andARestartLoadsItPaused_withEverythingIntact() throws Exception
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		Map<String, PatchKnowledge> before = new HashMap<>(known);
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunStore.StartResult r = store.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0);
		assertTrue(r.getProblems().toString(), r.isStarted());
		assertEquals("starting never touches knowledge", before, known);
		assertSame(before.get("herb.falador"), known.get("herb.falador"));
		drainWrites();

		FarmingRunStore restarted = loaded(A_OWNER);
		FarmingRunIntent back = restarted.intentFor(A_OWNER);
		assertNotNull(back);
		assertEquals(r.getIntent().getIntentId(), back.getIntentId());
		assertEquals("a restart never resumes silently", FarmingRunIntent.State.PAUSED, back.getState());
		assertEquals(Arrays.asList("herb.falador", "herb.catherby"), back.getIncludedPatchIds());
		assertEquals(Collections.singletonList("herb.harmony"), back.getExcludedPatchIds());
		assertEquals(FarmingProduce.RANARR, back.plannedCrop("herb.catherby"));
		assertEquals("ULTRACOMPOST", back.getOptions().getCompost());
		assertEquals(FarmingRunIntent.Baseline.Truth.OBSERVED, back.baseline("herb.falador").getTruth());
		assertEquals(RANARR_READY, back.baseline("herb.falador").getRaw());
		assertEquals("not tracked is kept, not dropped", FarmingRunIntent.Baseline.Truth.NEVER_SEEN, back.baseline("herb.catherby").getTruth());

		String json = new String(Files.readAllBytes(root.resolve(Long.toString(ACCOUNT)).resolve("profiles").resolve("standard").resolve("farming")
			.resolve(FarmingRunStore.INTENT_FILE)), StandardCharsets.UTF_8).toLowerCase();
		for (String cursor : new String[]{"step", "cursor", "currentsite", "nextsite", "route"})
		{
			assertFalse("no step cursor is persisted: " + cursor, json.contains("\"" + cursor));
		}
	}

	@Test
	public void startIsValidated_inPlainWords_andNeverReplacesAnOpenRun()
	{
		FarmingRunStore store = newStore();
		store.switchOwner(A_OWNER, T0);
		assertTrue(store.start(A_OWNER, herbRun("herb.falador"), known, T0).getProblems().get(0).contains("still loading"));
		drainLoads();
		assertTrue(store.start(B_OWNER, herbRun("herb.falador"), known, T0).getProblems().get(0).contains("Log in"));
		assertTrue(store.start(A_OWNER, herbRun(), known, T0).getProblems().get(0).contains("Include at least one patch"));
		Map<String, FarmingProduce> wrong = Collections.singletonMap("herb.falador", FarmingProduce.YEW);
		FarmingRunRequest badCrop = new FarmingRunRequest(FarmingRunType.HERB, Collections.singletonList("herb.falador"), Collections.emptyList(), wrong,
			null, false);
		assertTrue(store.start(A_OWNER, badCrop, known, T0).getProblems().get(0).contains("can't be planted"));
		assertTrue(store.start(A_OWNER, herbRun("herb.falador"), known, T0).isStarted());
		FarmingRunStore.StartResult again = store.start(A_OWNER, herbRun("herb.catherby"), known, T0 + MIN);
		assertFalse(again.isStarted());
		assertTrue(again.getProblems().get(0).contains("already in progress"));
		assertEquals(Collections.singletonList("herb.falador"), store.intentFor(A_OWNER).getIncludedPatchIds());
	}

	@Test
	public void ownership_anotherAccountNeverSeesOrResumesTheRun_andAStaleLoadIsDropped()
	{
		FarmingRunStore store = loaded(A_OWNER);
		store.start(A_OWNER, herbRun("herb.falador"), known, T0);
		drainWrites();
		store.switchOwner(B_OWNER, T0 + MIN);
		drainLoads();
		assertNull("B has no run", store.intentFor(B_OWNER));
		assertNull("A's run is not visible under B", store.intentFor(A_OWNER));
		store.resume(A_OWNER, T0);
		store.setSkipped(A_OWNER, "herb.falador", true, T0);
		drainWrites();
		assertNull("nothing was written for B", readDoc(B_OWNER));
		// A delayed load for A arriving after a switch to B is ignored.
		FarmingRunStore racing = newStore();
		racing.switchOwner(A_OWNER, T0);
		racing.switchOwner(B_OWNER, T0);
		drainLoads();
		assertNull(racing.intentFor(B_OWNER));
		// Switching back restores A's run, paused (it was saved on the switch away).
		store.switchOwner(A_OWNER, T0 + 2 * MIN);
		drainLoads();
		assertEquals(FarmingRunIntent.State.PAUSED, store.intentFor(A_OWNER).getState());
	}

	@Test
	public void logoutPauses_resumeReactivates_andProgressIsTrackedFromKnowledge()
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		store.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0);
		store.pause(T0 + MIN);
		drainWrites();
		assertEquals(FarmingRunIntent.State.PAUSED, readDoc(A_OWNER).intent.getState());
		store.resume(A_OWNER, T0 + 2 * MIN);
		assertEquals(FarmingRunIntent.State.ACTIVE, store.intentFor(A_OWNER).getState());
		store.track(A_OWNER, known, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 3 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 4 * MIN);
		FarmingRunEvaluator.Progress p = store.track(A_OWNER, known, T0 + 4 * MIN);
		assertEquals(1, p.getDone());
		assertEquals("progress time recorded", T0 + 4 * MIN, store.intentFor(A_OWNER).getLastProgressAt());
		assertTrue("one patch left: still open", store.intentFor(A_OWNER).isOpen());
	}

	@Test
	public void allSettled_completesTheRun_writesTheOutcome_andDoneClearsIt() throws Exception
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunIntent run = store.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0).getIntent();
		store.setSkipped(A_OWNER, "herb.catherby", true, T0 + MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 3 * MIN);
		store.track(A_OWNER, known, T0 + 3 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 4 * MIN);
		store.track(A_OWNER, known, T0 + 4 * MIN);
		FarmingRunIntent done = store.intentFor(A_OWNER);
		assertEquals(FarmingRunIntent.State.COMPLETE, done.getState());
		assertNull("completed, not ended", done.getEndReason());
		assertEquals(Long.valueOf(T0 + 4 * MIN), done.getFinishedAt());
		drainWrites();
		FarmingRunStore.Outcome outcome = stateStore.readIfExists(dirs.apply(A_OWNER).joinSegment(FarmingRunStore.RUNS_DIR)
			.joinSegment(run.getIntentId() + ".json"), FarmingRunStore.Outcome.class);
		assertNotNull("outcome record written", outcome);
		assertEquals("COMPLETE", outcome.result);
		assertEquals(1, outcome.replanted);
		assertEquals(1, outcome.skipped);
		assertEquals("REPLANTED", outcome.patches.get("herb.falador").detail);
		assertEquals("RANARR", outcome.patches.get("herb.falador").observedCrop);
		assertEquals(FarmingRunIntent.State.COMPLETE, readDoc(A_OWNER).intent.getState());
		store.acknowledge(A_OWNER);
		assertNull(store.intentFor(A_OWNER));
		drainWrites();
		assertNull("cleared on Done", readDoc(A_OWNER).intent);
	}

	@Test
	public void endRun_withSomethingDone_endsEarlyWithASummary_withNothingDone_simplyStops()
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		store.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0);
		store.end(A_OWNER, known, T0 + MIN);
		assertNull("nothing done: no summary, no run", store.intentFor(A_OWNER));

		store.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 3 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 4 * MIN);
		store.end(A_OWNER, known, T0 + 5 * MIN);
		FarmingRunIntent ended = store.intentFor(A_OWNER);
		assertEquals(FarmingRunIntent.State.COMPLETE, ended.getState());
		assertEquals(FarmingRunIntent.EndReason.ENDED, ended.getEndReason());
	}

	@Test
	public void anUnfinishedRun_staysSaved_withNoAgeRule_untilCompletedOrEnded() throws Exception
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunIntent run = store.start(A_OWNER, herbRun("herb.falador"), known, T0).getIntent();
		store.pause(T0 + MIN);
		long muchLater = T0 + 400L * 24 * 60 * MIN; // over a year
		store.track(A_OWNER, known, muchLater); // time alone changes nothing
		FarmingRunIntent still = store.intentFor(A_OWNER);
		assertEquals(run.getIntentId(), still.getIntentId());
		assertEquals("never ended or resumed by age", FarmingRunIntent.State.PAUSED, still.getState());
		assertNull(still.getEndReason());
		drainWrites();
		FarmingRunStore restarted = loaded(A_OWNER);
		assertEquals("still saved after a restart long after", run.getIntentId(), restarted.intentFor(A_OWNER).getIntentId());
		for (java.lang.reflect.Field f : FarmingRunStore.class.getDeclaredFields())
		{
			assertFalse("no age threshold exists: " + f.getName(), f.getName().matches("(?i).*(stale|expire|auto_?end|timeout).*"));
		}
		for (FarmingRunIntent.EndReason r : FarmingRunIntent.EndReason.values())
		{
			assertEquals("only an explicit End ends a run early", FarmingRunIntent.EndReason.ENDED, r);
		}
	}

	@Test
	public void aDamagedOrForeignFile_isIgnored_neverHalfUsed()
	{
		FarmingRunStore.Document foreign = new FarmingRunStore.Document();
		foreign.accountHash = OTHER_ACCOUNT;
		foreign.profile = "standard";
		stateStore.writeAndWait(dirs.apply(A_OWNER).joinSegment(FarmingRunStore.INTENT_FILE), foreign, 10_000);
		assertNull(loaded(A_OWNER).intentFor(A_OWNER));
		stateStore.writeAndWait(dirs.apply(A_OWNER).joinSegment(FarmingRunStore.INTENT_FILE),
			Collections.singletonMap("intent", Collections.singletonMap("schema", 1)), 10_000);
		assertNull(loaded(A_OWNER).intentFor(A_OWNER));
	}

	@Test
	public void preparation_opensAtStart_closesOnGo_orOnFirstProgress_andSurvivesARestart()
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingKnowledgeFixtures.see(known, "herb.catherby", RANARR_READY, T0 - MIN);
		FarmingRunStore s = loaded(A_OWNER);
		assertTrue(s.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0).isStarted());
		assertTrue("Start opens the preparation stage", s.intentFor(A_OWNER).isPreparing());
		drainWrites();
		FarmingRunStore restarted = loaded(A_OWNER);
		assertTrue("persisted: a restart comes back to preparation", restarted.intentFor(A_OWNER).isPreparing());
		restarted.resume(A_OWNER, T0 + MIN);
		s = restarted;
		s.closePreparation(B_OWNER);
		assertTrue("another owner cannot close it", s.intentFor(A_OWNER).isPreparing());
		s.closePreparation(A_OWNER);
		assertFalse(s.intentFor(A_OWNER).isPreparing());
		drainWrites();
		assertFalse(loaded(A_OWNER).intentFor(A_OWNER).isPreparing());

		FarmingRunStore t = loaded(B_OWNER);
		Map<String, PatchKnowledge> kb = new HashMap<>();
		FarmingKnowledgeFixtures.see(kb, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingKnowledgeFixtures.see(kb, "herb.catherby", RANARR_READY, T0 - MIN);
		t.start(B_OWNER, herbRun("herb.falador", "herb.catherby"), kb, T0);
		t.track(B_OWNER, kb, T0 + MIN);
		assertTrue(t.intentFor(B_OWNER).isPreparing());
		FarmingKnowledgeFixtures.change(kb, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(kb, "herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		t.track(B_OWNER, kb, T0 + 3 * MIN);
		assertFalse("the player clearly started: the stage closed by itself (D-F2)", t.intentFor(B_OWNER).isPreparing());
	}

	@Test
	public void aRunSavedBeforePhaseH_hasNoPreparationStage()
	{
		FarmingRunIntent old = new FarmingRunIntent(FarmingRunIntent.SCHEMA, "fr-old-herb", FarmingRunType.HERB, T0, T0, FarmingRunIntent.State.ACTIVE,
			null, null, Collections.singletonList("herb.falador"), Collections.emptyList(), Collections.emptyList(),
			Collections.singletonMap("herb.falador", "RANARR"), new FarmingRunIntent.Options("NONE", false), Collections.emptyMap());
		assertFalse(old.isPreparing());
		String json = new com.google.gson.Gson().toJson(old);
		assertFalse(json, json.contains("preparing"));
		assertFalse(new com.google.gson.Gson().fromJson(json, FarmingRunIntent.class).isPreparing());
		assertTrue(new com.google.gson.Gson().toJson(old.withPreparing(true)).contains("\"preparing\":true"));
	}

	// -- Farming correction pass: combined runs, generic plans, bounty, the suggested order ----------------

	private FarmingRunRequest combinedRun()
	{
		Map<String, String> plans = new LinkedHashMap<>();
		plans.put("herb.falador", FarmingRunIntent.REPLANT_ANY);
		plans.put("allotment.falador.nw", "POTATO");
		plans.put("tree.falador", FarmingRunIntent.HARVEST_ONLY);
		Map<FarmingRunType, FarmingPlanDraft.Compost> compost = new LinkedHashMap<>();
		compost.put(FarmingRunType.HERB, FarmingPlanDraft.Compost.ULTRACOMPOST);
		compost.put(FarmingRunType.ALLOTMENT, FarmingPlanDraft.Compost.COMPOST);
		Map<FarmingRunType, Boolean> protection = new LinkedHashMap<>();
		protection.put(FarmingRunType.ALLOTMENT, true);
		protection.put(FarmingRunType.HERB, true); // herbs offer no protection: ignored
		return new FarmingRunRequest(Arrays.asList(FarmingRunType.HERB, FarmingRunType.ALLOTMENT, FarmingRunType.TREE, FarmingRunType.HERB),
			Arrays.asList("herb.falador", "allotment.falador.nw", "tree.falador", "herb.falador"), Collections.emptyList(), plans, compost, protection,
			true);
	}

	@Test
	public void combinedRun_isOneSavedRun_withPerTypeChoices_dedupedPatches_andSurvivesARestart() throws Exception
	{
		FarmingRunRequest req = combinedRun();
		assertEquals("types once each", Arrays.asList(FarmingRunType.HERB, FarmingRunType.ALLOTMENT, FarmingRunType.TREE), req.getRunTypes());
		assertEquals("a patch listed twice counts once", 3, req.getIncluded().size());
		assertTrue(req.problems(CATALOG).toString(), req.problems(CATALOG).isEmpty());
		assertFalse("herbs can't be protected, whatever the draft says", req.protectionFor(FarmingRunType.HERB));
		assertTrue(req.protectionFor(FarmingRunType.ALLOTMENT));

		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunStore.StartResult r = store.start(A_OWNER, req, known, T0);
		assertTrue(r.getProblems().toString(), r.isStarted());
		FarmingRunIntent i = r.getIntent();
		assertTrue(i.isCombined());
		assertEquals("Herb + Allotments & Flowers + Tree", i.getTitle());
		assertTrue(i.getIntentId(), i.getIntentId().endsWith("-combined"));
		assertNull("you choose: no crop is invented", i.plannedCrop("herb.falador"));
		assertTrue("…but the patch is still to be planted", i.plansToPlant("herb.falador"));
		assertEquals(FarmingProduce.POTATO, i.plannedCrop("allotment.falador.nw"));
		assertFalse(i.plansToPlant("tree.falador"));
		assertEquals("ULTRACOMPOST", i.getOptions().compostFor(FarmingRunType.HERB));
		assertEquals("COMPOST", i.getOptions().compostFor(FarmingRunType.ALLOTMENT));
		assertTrue(i.getOptions().isUseBounty());

		store.setSuggestedOrder(A_OWNER, Arrays.asList("tree.falador", "herb.falador", "allotment.falador.nw"));
		store.setUseBounty(A_OWNER, false);
		drainWrites();
		FarmingRunIntent back = loaded(A_OWNER).intentFor(A_OWNER);
		assertNotNull(back);
		assertEquals(Arrays.asList(FarmingRunType.HERB, FarmingRunType.ALLOTMENT, FarmingRunType.TREE), back.getRunTypes());
		assertEquals("one order, shared by the prep and run pages, kept across a restart",
			Arrays.asList("tree.falador", "herb.falador", "allotment.falador.nw"), back.getSuggestedOrder());
		assertFalse("the bounty choice is saved", back.getOptions().isUseBounty());
		assertEquals("COMPOST", back.getOptions().compostFor(FarmingRunType.ALLOTMENT));
		assertTrue(back.plansToPlant("herb.falador"));
		assertNull(back.plannedCrop("herb.falador"));

		assertNull("another account never sees it", loaded(B_OWNER).intentFor(B_OWNER));
	}

	@Test
	public void aRequestWithNoTypes_orNoPatches_isRefusedInWords()
	{
		FarmingRunRequest none = new FarmingRunRequest(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
			Collections.emptyMap(), null, null, false);
		assertEquals(Collections.singletonList("Pick a run type first."), none.problems(CATALOG));
		FarmingRunRequest empty = new FarmingRunRequest(Collections.singletonList(FarmingRunType.HERB), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyMap(), null, null, false);
		assertEquals(Collections.singletonList("Include at least one patch to start."), empty.problems(CATALOG));
	}

	/** Files saved before the harvest-tracking removal (2026-10-21) still carry harvest fields: they load, and are otherwise intact. */
	@Test
	public void olderSavedRuns_withRemovedHarvestFields_loadSafely_andKeepTheirOutcomes() throws Exception
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunIntent open = store.start(A_OWNER, herbRun("herb.falador", "herb.catherby"), known, T0).getIntent();
		drainWrites();
		Path intentFile = root.resolve(Long.toString(ACCOUNT)).resolve("profiles").resolve("standard").resolve("farming").resolve(FarmingRunStore.INTENT_FILE);
		com.google.gson.JsonObject doc = GSON.fromJson(new String(Files.readAllBytes(intentFile), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
		com.google.gson.JsonObject oldHarvest = new com.google.gson.JsonObject();
		oldHarvest.addProperty("RANARR", 7);
		doc.getAsJsonObject("intent").add("harvest", oldHarvest);
		com.google.gson.JsonArray names = new com.google.gson.JsonArray();
		names.add("Grimy ranarr weed");
		doc.getAsJsonObject("intent").add("harvestNames", names);
		doc.getAsJsonObject("intent").addProperty("harvestUnobserved", true);
		Files.write(intentFile, GSON.toJson(doc).getBytes(StandardCharsets.UTF_8));

		FarmingRunStore restarted = loaded(A_OWNER);
		FarmingRunIntent back = restarted.intentFor(A_OWNER);
		assertNotNull("an older file with harvest fields still loads", back);
		assertEquals(open.getIntentId(), back.getIntentId());
		assertEquals(FarmingRunIntent.State.PAUSED, back.getState());
		assertEquals(Arrays.asList("herb.falador", "herb.catherby"), back.getIncludedPatchIds());
		drainWrites();
		String rewritten = new String(Files.readAllBytes(intentFile), StandardCharsets.UTF_8);
		assertFalse("the removed fields are not written again", rewritten.contains("harvestNames") || rewritten.contains("harvestUnobserved"));

		// An older outcome file with the removed harvest totals: History reads its actual outcome, never a harvest total.
		Path runs = intentFile.getParent().resolve(FarmingRunStore.RUNS_DIR);
		Files.createDirectories(runs);
		String old = "{\"schema\":" + FarmingRunIntent.SCHEMA + ",\"accountHash\":" + ACCOUNT + ",\"profile\":\"standard\",\"intentId\":\"fr-old\","
			+ "\"runType\":\"HERB\",\"startedAt\":" + T0 + ",\"finishedAt\":" + (T0 + 9 * MIN) + ",\"result\":\"ENDED\",\"total\":3,\"done\":2,"
			+ "\"replanted\":2,\"harvested\":2,\"skipped\":0,\"notApplicable\":0,\"unfinished\":1,\"harvest\":{\"RANARR\":14},"
			+ "\"harvestNames\":{\"RANARR\":\"Grimy ranarr weed\"},\"harvestComplete\":false,\"patches\":{\"herb.falador\":{\"outcome\":\"DONE\","
			+ "\"detail\":\"REPLANTED\",\"observedCrop\":\"RANARR\"}}}";
		Files.write(runs.resolve("fr-old.json"), old.getBytes(StandardCharsets.UTF_8));
		java.util.List<FarmingRunHistory.Record> records = FarmingRunHistory.find(stateStore, rootPath.joinSegment(Long.toString(ACCOUNT)), ACCOUNT,
			T0 - MIN, T0 + 10 * MIN, Collections.emptyList());
		assertEquals(1, records.size());
		FarmingRunHistory.Record r = records.get(0);
		assertEquals("fr-old", r.getIntentId());
		assertFalse("manually ended keeps its actual outcome", r.isComplete());
		assertEquals("2 of 3 patches · 2 replanted · 1 unfinished · ended early", r.summary());
		assertEquals(Collections.singletonMap("Ranarr", 1), r.getPlanted());
	}

	// -- optional work taken on at Start (canonical §28.16) -------------------------------------------------------------

	private static Map<String, CompostBin.Seen> bin(String id, int raw, long at)
	{
		return Collections.singletonMap(id, new CompostBin.Seen(raw, at, at));
	}

	@Test
	public void theLastStopsBin_keepsTheRunOpen_untilTakenOrSkipped_andSurvivesARestart() throws Exception
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunRequest request = herbRunNoCompost("herb.falador").withExtras(FarmingPreferences.defaults().withRunBin("bin.falador", true)
			.withBinUpgrade("bin.falador", CompostBin.Upgrade.ULTRA), true);
		FarmingRunIntent run = store.start(A_OWNER, request, known, T0).getIntent();
		assertEquals(Collections.singletonList("bin.falador"), run.getBins());
		assertEquals(CompostBin.Upgrade.ULTRA, run.binUpgrade("bin.falador"));
		assertEquals("protection decided at Start (none chosen)", Collections.emptyList(), run.getProtectTypes());
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		Map<String, CompostBin.Seen> ready = bin("bin.falador", 190, T0); // 15 ultracompost to take
		store.track(A_OWNER, known, ready, T0 + 3 * MIN);
		assertEquals("every patch done, the bin still to do: open", FarmingRunIntent.State.ACTIVE, store.intentFor(A_OWNER).getState());
		drainWrites();

		// Logout and back: the run and its bin come back (paused), nothing lost.
		store.pause(T0 + 4 * MIN);
		drainWrites();
		FarmingRunStore again = loaded(A_OWNER);
		FarmingRunIntent back = again.intentFor(A_OWNER);
		assertEquals(Collections.singletonList("bin.falador"), back.getBins());
		assertEquals(CompostBin.Upgrade.ULTRA, back.binUpgrade("bin.falador"));
		again.resume(A_OWNER, T0 + 5 * MIN);
		again.track(A_OWNER, known, ready, T0 + 5 * MIN);
		assertEquals(FarmingRunIntent.State.ACTIVE, again.intentFor(A_OWNER).getState());

		// Skipped: nothing holds the run; the next tick (even away from any patch) completes it.
		again.setBinSkipped(A_OWNER, "bin.falador", true, T0 + 6 * MIN);
		assertTrue(again.consumeRecheck());
		again.track(A_OWNER, known, ready, T0 + 6 * MIN);
		assertEquals(FarmingRunIntent.State.COMPLETE, again.intentFor(A_OWNER).getState());
	}

	@Test
	public void aBinStillRotting_isNeverWaitedFor_andATakenBinCompletesTheRun()
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		store.start(A_OWNER, herbRunNoCompost("herb.falador").withExtras(FarmingPreferences.defaults().withRunBin("bin.falador", true), true), known, T0);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		store.track(A_OWNER, known, bin("bin.falador", 95, T0 + 3 * MIN), T0 + 3 * MIN); // closed just now: rotting
		assertEquals(FarmingRunIntent.State.COMPLETE, store.intentFor(A_OWNER).getState());

		FarmingRunStore other = loaded(FarmingOwner.standard(OTHER_ACCOUNT));
		FarmingKnowledgeFixtures.see(known, "herb.catherby", RANARR_READY, T0 - MIN);
		other.start(FarmingOwner.standard(OTHER_ACCOUNT), herbRunNoCompost("herb.catherby").withExtras(FarmingPreferences.defaults().withRunBin("bin.catherby", true), true),
			known, T0);
		FarmingKnowledgeFixtures.change(known, "herb.catherby", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.catherby", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		other.track(FarmingOwner.standard(OTHER_ACCOUNT), known, bin("bin.catherby", 62, T0), T0 + 3 * MIN);
		assertEquals(FarmingRunIntent.State.ACTIVE, other.intentFor(FarmingOwner.standard(OTHER_ACCOUNT)).getState());
		other.track(FarmingOwner.standard(OTHER_ACCOUNT), known, bin("bin.catherby", 0, T0 + 4 * MIN), T0 + 4 * MIN); // taken: empty
		assertEquals(FarmingRunIntent.State.COMPLETE, other.intentFor(FarmingOwner.standard(OTHER_ACCOUNT)).getState());
	}

	@Test
	public void binsJoinARun_onlyWhenTheBinComponentIsIncluded()
	{
		FarmingPreferences prefs = FarmingPreferences.defaults().withRunBin("bin.falador", true);
		assertEquals("a crop run never picks up bins from settings", Collections.emptyList(),
			herbRunNoCompost("herb.falador").withExtras(prefs).getBins());
		FarmingRunRequest both = new FarmingRunRequest(Arrays.asList(FarmingRunType.HERB, FarmingRunType.COMPOST), Collections.singletonList("herb.falador"),
			Collections.emptyList(), Collections.singletonMap("herb.falador", "Ranarr"), Collections.emptyMap(), Collections.emptyMap(), false).withExtras(prefs);
		assertEquals(Collections.singletonList("bin.falador"), both.getBins());
		FarmingRunRequest none = new FarmingRunRequest(Collections.singletonList(FarmingRunType.COMPOST), Collections.emptyList(), Collections.emptyList(),
			Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), false).withExtras(FarmingPreferences.defaults());
		assertFalse("the bin component without a bin ticked asks for one", none.problems(CATALOG).isEmpty());
	}

	@Test
	public void aBinsOnlyRun_completesOnItsBins_andHistoryCountsTheBinWorked() throws Exception
	{
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunRequest request = new FarmingRunRequest(Collections.singletonList(FarmingRunType.COMPOST), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), false)
			.withExtras(FarmingPreferences.defaults().withRunBin("bin.catherby", true));
		assertTrue(request.problems(CATALOG).toString(), request.problems(CATALOG).isEmpty());
		FarmingRunIntent run = store.start(A_OWNER, request, known, T0).getIntent();
		assertEquals(Collections.singletonList("bin.catherby"), run.getBins());
		store.track(A_OWNER, known, bin("bin.catherby", 62, T0), T0 + MIN); // rotted compost to take
		assertEquals(FarmingRunIntent.State.ACTIVE, store.intentFor(A_OWNER).getState());
		store.track(A_OWNER, known, bin("bin.catherby", 0, T0 + 2 * MIN), T0 + 2 * MIN); // taken
		assertEquals(FarmingRunIntent.State.COMPLETE, store.intentFor(A_OWNER).getState());
		drainWrites();
		FarmingRunStore.Outcome outcome = stateStore.readIfExists(dirs.apply(A_OWNER).joinSegment(FarmingRunStore.RUNS_DIR)
			.joinSegment(run.getIntentId() + ".json"), FarmingRunStore.Outcome.class);
		assertNotNull(outcome);
		assertEquals(1, outcome.binsWorked);
		java.util.List<FarmingRunHistory.Record> records = FarmingRunHistory.find(stateStore, rootPath.joinSegment(Long.toString(ACCOUNT)), ACCOUNT,
			T0 - MIN, T0 + 10 * MIN, Collections.emptyList());
		assertEquals("1 bin worked · complete", records.get(0).summary());
	}

	@Test
	public void aBinThatOnlyRotted_isNotCountedAsWorked_butOpeningItIs()
	{
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunIntent run = store.start(A_OWNER, new FarmingRunRequest(Collections.singletonList(FarmingRunType.COMPOST), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), false)
			.withExtras(FarmingPreferences.defaults().withRunBin("bin.catherby", true)), known, T0).getIntent()
			.withBinStart(Collections.singletonMap("bin.catherby", 95)); // closed supercompost, rotting
		assertEquals(0, RunExtras.binsWorked(run, bin("bin.catherby", 95, T0)));
		assertEquals("rotting to rotted happens by itself", 0, RunExtras.binsWorked(run, bin("bin.catherby", 126, T0)));
		assertEquals("opened and taken from", 1, RunExtras.binsWorked(run, bin("bin.catherby", 62, T0)));
		assertEquals("never read now: nothing counted", 0, RunExtras.binsWorked(run, Collections.emptyMap()));
	}

	@Test
	public void aProtectionPaymentDue_keepsTheLastStopOpen_untilPaidOrSkipped()
	{
		int magicReady = FarmingKnowledgeFixtures.raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.HARVESTABLE, -1);
		int sapling = FarmingKnowledgeFixtures.raw(FarmingPatchType.TREE, FarmingProduce.MAGIC, PatchCondition.GROWING, 0);
		FarmingKnowledgeFixtures.see(known, "tree.varrock", magicReady, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		FarmingRunRequest request = new FarmingRunRequest(FarmingRunType.TREE, Collections.singletonList("tree.varrock"), Collections.emptyList(),
			Collections.singletonMap("tree.varrock", FarmingProduce.MAGIC), FarmingPlanDraft.Compost.NONE, false)
			.withExtras(FarmingPreferences.defaults().withProtect(FarmingRunType.TREE, true));
		store.start(A_OWNER, request, known, T0);
		FarmingKnowledgeFixtures.change(known, "tree.varrock", magicReady, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "tree.varrock", EMPTY, sapling, T0 + 3 * MIN);
		store.track(A_OWNER, known, Collections.emptyMap(), T0 + 3 * MIN);
		assertEquals("planted, protection still to pay: open", FarmingRunIntent.State.ACTIVE, store.intentFor(A_OWNER).getState());
		store.recordProtected(A_OWNER, "tree.varrock");
		store.track(A_OWNER, known, Collections.emptyMap(), T0 + 4 * MIN);
		assertEquals("the game's payment message: complete", FarmingRunIntent.State.COMPLETE, store.intentFor(A_OWNER).getState());
	}

	@Test
	public void anOldSavedRun_withoutTheNewFields_loadsAndCompletesAsBefore() throws Exception
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		store.start(A_OWNER, herbRun("herb.falador"), known, T0); // no extras: as a run saved before §28.16
		drainWrites();
		Path file;
		try (java.util.stream.Stream<Path> w = Files.walk(root))
		{
			file = w.filter(f -> f.getFileName().toString().equals(FarmingRunStore.INTENT_FILE)).findFirst().orElseThrow(AssertionError::new);
		}
		String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertFalse("nothing new written for it", json.contains("\"bins\"") || json.contains("protectTypes"));
		FarmingRunStore again = loaded(A_OWNER);
		FarmingRunIntent back = again.intentFor(A_OWNER);
		assertTrue(back.getBins().isEmpty());
		assertNull(back.getProtectTypes());
		again.resume(A_OWNER, T0 + MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		again.track(A_OWNER, known, bin("bin.falador", 62, T0), T0 + 3 * MIN);
		assertEquals("no bin of its own: complete when its patches are", FarmingRunIntent.State.COMPLETE, again.intentFor(A_OWNER).getState());
	}

	// -- compost across planting (canonical §28.17) ------------------------------------------------------------------

	private static final long COMPOST_AT_START = T0 - 3 * 3_600_000L;

	/** A supercompost herb run on Falador, whose old crop was composted hours before the run (the previous planting's). */
	private FarmingRunStore compostRun(Map<String, PatchCompost> compost)
	{
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_SEED, COMPOST_AT_START - MIN);
		compost.put("herb.falador", PatchCompost.of(PatchCompost.parse("You treat the herb patch with supercompost."), COMPOST_AT_START,
			known.get("herb.falador"), CATALOG.patch("herb.falador")));
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_SEED, RANARR_READY, T0 - MIN);
		FarmingRunStore store = loaded(A_OWNER);
		Map<String, FarmingProduce> planned = Collections.singletonMap("herb.falador", FarmingProduce.RANARR);
		store.start(A_OWNER, new FarmingRunRequest(FarmingRunType.HERB, Collections.singletonList("herb.falador"), Collections.emptyList(), planned,
			FarmingPlanDraft.Compost.SUPERCOMPOST, false).withExtras(FarmingPreferences.defaults()), known, T0);
		return store;
	}

	private PatchCompost compostMessage(Map<String, PatchCompost> compost, long at)
	{
		PatchCompost c = PatchCompost.of(PatchCompost.parse("You treat the herb patch with supercompost."), at, known.get("herb.falador"),
			CATALOG.patch("herb.falador"));
		compost.put("herb.falador", c);
		return c;
	}

	private FarmingRunView view(FarmingRunStore store, Map<String, PatchCompost> compost, long now)
	{
		FarmingSnapshot snap = new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, A_OWNER, new LinkedHashMap<>(known), Collections.emptyList(),
			store.intentFor(A_OWNER), Collections.singleton("herb.falador")).withCompost(compost);
		return FarmingModel.build(CATALOG, snap, now).getRun();
	}

	private static FarmingRunView.Step compostStep(FarmingRunView v)
	{
		for (FarmingRunView.Step st : v.getLines().get(0).getSteps())
		{
			if (st.getLabel().equals("Compost") || st.getLabel().equals("Super"))
			{
				return st;
			}
		}
		throw new AssertionError(v.getLines().get(0).getSteps().toString());
	}

	@Test
	public void harvest_clear_compost_plant_theOldCropsCompostNeverCounts_andThePrePlantCompostCarries()
	{
		Map<String, PatchCompost> compost = new HashMap<>();
		FarmingRunStore store = compostRun(compost);
		known.put("herb.falador", known.get("herb.falador").seenUnchanged(T0 + MIN)); // at the patch, harvesting
		assertNotNull("the old crop's record is still current for the old crop", compost.get("herb.falador").current(known.get("herb.falador")));
		assertEquals("while harvesting: the old crop's compost is not this planting's", FarmingRunView.Step.State.TODO,
			compostStep(view(store, compost, T0 + MIN)).getState());
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN); // harvested out
		compostMessage(compost, T0 + 3 * MIN); // on the bare patch
		assertEquals("Super", compostStep(view(store, compost, T0 + 3 * MIN)).getLabel());
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 4 * MIN); // planted
		store.track(A_OWNER, known, Collections.emptyMap(), compost, T0 + 4 * MIN);
		assertEquals("composted before planting, then planted: done", FarmingRunIntent.State.COMPLETE, store.intentFor(A_OWNER).getState());
	}

	@Test
	public void harvest_clear_plant_compost_plantingDoesNotFinishThePatch_untilTheCompost_evenAcrossARestart()
	{
		Map<String, PatchCompost> compost = new HashMap<>();
		FarmingRunStore store = compostRun(compost);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN); // planted first
		store.track(A_OWNER, known, Collections.emptyMap(), compost, T0 + 3 * MIN);
		assertEquals("planted, compost still due: open", FarmingRunIntent.State.ACTIVE, store.intentFor(A_OWNER).getState());
		FarmingRunView v = view(store, compost, T0 + 3 * MIN);
		assertTrue("listed as still to do", v.getLines().get(0).isCompostDue());
		assertTrue(v.getNextTitle(), v.getNextTitle().startsWith("Falador"));
		assertEquals("compost after planting is the next step", "Treat the patch with supercompost (just planted)", v.getNextLines().get(0));

		// Logout and back: the run and its compost task come back.
		store.pause(T0 + 4 * MIN);
		drainWrites();
		FarmingRunStore again = loaded(A_OWNER);
		again.resume(A_OWNER, T0 + 5 * MIN);
		again.track(A_OWNER, known, Collections.emptyMap(), compost, T0 + 5 * MIN);
		assertEquals(FarmingRunIntent.State.ACTIVE, again.intentFor(A_OWNER).getState());
		assertTrue(view(again, compost, T0 + 5 * MIN).getLines().get(0).isCompostDue());

		compostMessage(compost, T0 + 6 * MIN); // the game's message on the planted crop
		again.track(A_OWNER, known, Collections.emptyMap(), compost, T0 + 6 * MIN);
		assertEquals("the game showed it: complete", FarmingRunIntent.State.COMPLETE, again.intentFor(A_OWNER).getState());
	}

	@Test
	public void compostLeftDue_canBeSkipped_soItNeverTrapsTheRun_andAClickOrPreferenceIsNoProof()
	{
		Map<String, PatchCompost> compost = new HashMap<>();
		FarmingRunStore store = compostRun(compost);
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, T0 + 2 * MIN);
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, RANARR_SEED, T0 + 3 * MIN);
		store.track(A_OWNER, known, Collections.emptyMap(), compost, T0 + 3 * MIN);
		assertEquals("the choice of supercompost alone proves nothing", FarmingRunIntent.State.ACTIVE, store.intentFor(A_OWNER).getState());
		store.setCompostSkipped(A_OWNER, "herb.falador", true, T0 + 4 * MIN);
		assertTrue(store.consumeRecheck());
		store.track(A_OWNER, known, Collections.emptyMap(), compost, T0 + 4 * MIN);
		assertEquals(FarmingRunIntent.State.COMPLETE, store.intentFor(A_OWNER).getState());
	}
}
