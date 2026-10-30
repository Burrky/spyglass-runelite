package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.farming.FarmingKnowledgeFixtures;
import com.osrstelemetry.plugin.farming.FarmingModel;
import com.osrstelemetry.plugin.farming.FarmingOwner;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPatchType;
import com.osrstelemetry.plugin.farming.FarmingProduce;
import com.osrstelemetry.plugin.farming.FarmingRunIntent;
import com.osrstelemetry.plugin.farming.FarmingRunRequest;
import com.osrstelemetry.plugin.farming.FarmingRunStore;
import com.osrstelemetry.plugin.farming.FarmingRunType;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.farming.PatchCondition;
import com.osrstelemetry.plugin.farming.PatchKnowledge;
import com.osrstelemetry.plugin.storage.LocalStateStore;
import com.osrstelemetry.plugin.storage.TestFilepaths;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import javax.swing.JPanel;
import java.util.Map;
import java.util.Set;
import javax.swing.JLabel;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Start Run, the run page and its controls, driven through a real run store (fixture knowledge, temp files). */
public class FarmingRunPageTest
{
	private static final long T0 = 1_760_000_000_000L;
	private static final long MIN = 60_000L;
	private static final FarmingOwner A = FarmingOwner.standard(4242L);
	private static final FarmingPatchCatalog CATALOG = FarmingPatchCatalog.standard();
	private static final int EMPTY = 3;
	private static final int RANARR_READY = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.RANARR, PatchCondition.HARVESTABLE, -1);
	private static final int SNAP_SEED = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 0);

	private final Map<String, PatchKnowledge> known = new HashMap<>();
	private final Set<String> here = new HashSet<>();
	private final List<String> calls = new ArrayList<>();
	private LocalStateStore stateStore;
	private Filepath root;
	private FarmingRunStore store;
	private FarmingView view;
	private long now = T0;

	@Before
	public void setUp() throws Exception
	{
		root = TestFilepaths.rooted(Files.createTempDirectory("run-page"));
		stateStore = new LocalStateStore(new Gson());
		stateStore.start();
		store = new FarmingRunStore(stateStore, o ->
		{
			Filepath dir = root.joinSegment(Long.toString(o.getAccountHash())).joinSegment("farming");
			try
			{
				dir.createDirectories();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
			return dir;
		}, Runnable::run, CATALOG);
		store.switchOwner(A, T0);
		FarmingKnowledgeFixtures.see(known, "herb.falador", RANARR_READY, T0 - 10 * MIN);
		FarmingKnowledgeFixtures.see(known, "herb.catherby", RANARR_READY, T0 - 10 * MIN);
		view = new FarmingView();
		view.setSize(225, 3000);
		view.attachRunActions(new FarmingView.RunActions()
		{
			@Override
			public FarmingRunStore.StartResult start(FarmingRunRequest request)
			{
				calls.add("start");
				return store.start(A, request, known, now);
			}

			@Override
			public void skip(String patchId, boolean skip)
			{
				store.setSkipped(A, patchId, skip, now);
			}

			@Override
			public void resume()
			{
				store.resume(A, now);
			}

			@Override
			public void end()
			{
				store.end(A, known, now);
			}

			@Override
			public void acknowledge()
			{
				store.acknowledge(A);
			}

			@Override
			public void refresh()
			{
				render();
			}

			@Override
			public void skipBin(String binId, boolean skip)
			{
				calls.add("skipBin " + binId + " " + skip);
				store.setBinSkipped(A, binId, skip, now);
			}

			@Override
			public void closePreparation()
			{
				calls.add("go");
				store.closePreparation(A);
			}
		});
		render();
	}

	@After
	public void tearDown() throws Exception
	{
		stateStore.shutdown();
		root.deleteRecursively();
	}

	private FarmingModel model()
	{
		return FarmingModel.build(CATALOG, new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, A, known, Collections.emptyList(),
			store.intentFor(A), here).withBins(bins), now);
	}

	private final Map<String, com.osrstelemetry.plugin.farming.CompostBin.Seen> bins = new java.util.HashMap<>();

	private static FarmingView.Link named(java.awt.Container c, String name)
	{
		for (java.awt.Component child : c.getComponents())
		{
			if (name.equals(child.getName()) && child instanceof FarmingView.Link)
			{
				return (FarmingView.Link) child;
			}
			if (child instanceof java.awt.Container)
			{
				FarmingView.Link found = named((java.awt.Container) child, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	@Test
	public void compostBins_areTheirOwnRunComponent_unseenNeverLooksEmpty_andABinsOnlyRunStarts()
	{
		bins.put("bin.catherby", FarmingKnowledgeFixtures.bin(62, T0 - 5 * MIN, T0 - 5 * MIN)); // 15 supercompost to take
		render();
		view.button(FarmingRunType.COMPOST).press();
		String page = text(view);
		assertTrue(page, page.contains("COMPOST BINS"));
		assertTrue("an unread bin says so", page.contains("Not seen yet"));
		assertFalse("never shown as empty", page.contains("Empty"));
		assertTrue(page, page.contains("Supercompost · 15 ready · 5 min ago") || page.contains("Supercompost · 15 ready"));
		assertTrue("the bin's next step", page.contains("Take supercompost from the bin"));
		assertFalse("no bin ticked: nothing to start", view.startButton().isEnabled());

		// A herb run never picks up a ticked bin.
		named(view, "bin.include.bin.catherby").click();
		assertTrue(view.prefs().isRunBin("bin.catherby"));
		assertTrue(view.startButton().isEnabled());
		view.button(FarmingRunType.HERB).press();
		view.startButton().press();
		assertEquals(Collections.emptyList(), store.intentFor(A).getBins());
		store.end(A, known, now);
		store.acknowledge(A);
		render();

		// The bins on their own.
		view.button(FarmingRunType.COMPOST).press();
		view.startButton().press();
		assertEquals(Collections.singletonList("bin.catherby"), store.intentFor(A).getBins());
		assertEquals(Collections.singletonList(FarmingRunType.COMPOST), store.intentFor(A).getRunTypes());
	}

	private void render()
	{
		view.render(model());
	}

	private void beginIfPreparing()
	{
		if (view.runPage() != null && view.runPage().beginButton != null && store.intentFor(A) != null && store.intentFor(A).isPreparing())
		{
			view.runPage().beginButton.press();
		}
		render();
	}

	/** Pass 32: an active bins-only run shows each bin as a card: picture, place, state, next action, one Skip that fires once. */
	@Test
	public void binsOnlyRun_cardsShowPictureStateAndNextAction_skipFiresOnce_unknownStaysUnknown()
	{
		bins.put("bin.catherby", FarmingKnowledgeFixtures.bin(62, T0 - 5 * MIN, T0 - 5 * MIN)); // 15 supercompost to take
		render();
		view.button(FarmingRunType.COMPOST).press();
		named(view, "bin.include.bin.catherby").click();
		named(view, "bin.include.bin.hosidius").click();
		view.startButton().press();
		beginIfPreparing();
		assertEquals(java.util.Arrays.asList("bin.catherby", "bin.hosidius"), store.intentFor(A).getBins());
		FarmingRunPage page = view.runPage();
		FarmingRunPage.RunBinCard cath = page.binCards.get("bin.catherby");
		FarmingRunPage.RunBinCard hos = page.binCards.get("bin.hosidius");
		assertNotNull(cath);
		assertNotNull(hos);
		assertEquals("the bin the run points at", FarmingRunPage.RunBinCard.Kind.CURRENT, cath.kind);
		assertFalse("a seen bin has its picture", cath.picture.isUnknown());
		assertTrue(cath.status.getText(), cath.status.getText().contains("Supercompost"));
		assertTrue(cath.next.getText(), cath.next.getText().contains("Take supercompost"));
		assertNotNull(cath.skip);
		assertEquals("Skip", cath.skip.getLabel());
		// Unknown stays unknown: the neutral mark, no task, no Skip, nothing recorded by drawing it.
		assertEquals(FarmingRunPage.RunBinCard.Kind.NOT_SEEN, hos.kind);
		assertTrue(hos.picture.isUnknown());
		assertNull(hos.skip);
		assertFalse(bins.containsKey("bin.hosidius"));
		String all = text(view);
		assertFalse("never the old plain line", all.contains("Catherby \u00b7 Take"));

		// Skip by mouse: once; the card says Skipped, the run's skip is recorded; Undo by keyboard: once.
		FarmingCategoryGridTest.layout(view);
		calls.clear();
		FarmingCategoryGridTest.click(cath.skip);
		assertEquals(Collections.singletonList("skipBin bin.catherby true"), calls);
		assertTrue(store.intentFor(A).isBinSkipped("bin.catherby"));
		cath = view.runPage().binCards.get("bin.catherby");
		assertEquals(FarmingRunPage.RunBinCard.Kind.SKIPPED, cath.kind);
		assertEquals("Undo skip", cath.skip.getLabel());
		FarmingCategoryGridTest.key(cath.skip, java.awt.event.KeyEvent.VK_SPACE);
		assertEquals(java.util.Arrays.asList("skipBin bin.catherby true", "skipBin bin.catherby false"), calls);
		assertFalse(store.intentFor(A).isBinSkipped("bin.catherby"));

		// The bin handled in game (taken out, now empty): nothing to do, shown as done, no Skip; the run is what decides the rest.
		bins.put("bin.catherby", FarmingKnowledgeFixtures.bin(0, T0, T0));
		render();
		cath = view.runPage().binCards.get("bin.catherby");
		assertEquals(FarmingRunPage.RunBinCard.Kind.NOTHING_TO_DO, cath.kind);
		assertNull(cath.skip);
		// A refresh with nothing changed keeps the same cards (no rebuild loop).
		render();
		assertSame(cath, view.runPage().binCards.get("bin.catherby"));
	}

	/** Pass 32: crops and a bin at the same stop: the bin is not current until the crops there are done. */
	@Test
	public void cropPlusBinRun_theBinComesAfterTheCropsAtItsStop()
	{
		bins.put("bin.catherby", FarmingKnowledgeFixtures.bin(62, T0 - 5 * MIN, T0 - 5 * MIN));
		render();
		view.button(FarmingRunType.COMPOST).press();
		named(view, "bin.include.bin.catherby").click();
		view.combineToggle().press();
		view.button(FarmingRunType.HERB).press();
		if (!view.combinedTypes().contains(FarmingRunType.COMPOST))
		{
			view.button(FarmingRunType.COMPOST).press();
		}
		assertTrue(view.combinedTypes().containsAll(java.util.Arrays.asList(FarmingRunType.HERB, FarmingRunType.COMPOST)));
		view.startButton().press();
		beginIfPreparing();
		assertEquals(Collections.singletonList("bin.catherby"), store.intentFor(A).getBins());
		FarmingRunPage.RunBinCard cath = view.runPage().binCards.get("bin.catherby");
		assertNotNull(cath);
		assertEquals("crops first: the bin waits as To do", FarmingRunPage.RunBinCard.Kind.TO_DO, cath.kind);
		assertNotNull(cath.skip);
	}


	private void startHerbRun()
	{
		view.button(FarmingRunType.HERB).press();
		assertNotNull("the preview has a Start button", view.startButton());
		assertEquals("Start Run", view.startButton().getLabel());
		view.startButton().press();
		assertTrue("Start opens the preparation stage first (Phase H)", text(view).contains("HERB RUN \u00b7 PREPARING"));
		assertNotNull(view.runPage().beginButton);
		view.runPage().beginButton.press();
		assertFalse("Begin (in any wording) shows the run's patches", store.intentFor(A).isPreparing());
	}

	@Test
	public void browsingAndPlanning_neverStartARun_onlyStartDoes()
	{
		view.button(FarmingRunType.HERB).press();
		view.button(FarmingRunType.TREE).press();
		view.button(FarmingRunType.HERB).press();
		view.rowViews().get("herb.falador").clickInclude();
		view.rowViews().get("herb.falador").clickInclude();
		view.rowViews().get("herb.falador").toggleExpanded();
		view.draft().setPlannedCrop("herb.falador", FarmingProduce.TORSTOL);
		render();
		assertNull(store.intentFor(A));
		assertTrue(calls.isEmpty());
		assertFalse(view.isRunPageShown());
	}

	@Test
	public void start_createsTheRun_withNotTrackedPatchesKept_andTheRunPageReplacesBrowse()
	{
		startHerbRun();
		FarmingRunIntent run = store.intentFor(A);
		assertNotNull(run);
		assertEquals(FarmingRunType.HERB, run.getRunType());
		assertTrue("observed ready patch", run.getIncludedPatchIds().contains("herb.falador"));
		assertTrue("never-seen patches are not silently dropped", run.getIncludedPatchIds().contains("herb.weiss"));
		assertEquals(FarmingRunIntent.Baseline.Truth.NEVER_SEEN, run.baseline("herb.weiss").getTruth());
		assertTrue(view.isRunPageShown());
		assertFalse("execution mode hides the run-type buttons", view.areRunTypeButtonsVisible());
		FarmingRunPage page = view.runPage();
		assertEquals(run.getIncludedPatchIds().size(), page.cards().size());
		String all = text(view);
		assertTrue(all, all.contains("HERB RUN"));
		assertTrue(all.contains("NEXT"));
		assertFalse("no redundant explainer on the run page (correction pass 2)", all.contains("You do the run in game"));
		for (String fake : new String[]{"READY TO GO", "TELEPORT", "SUPPLIES", "WITHDRAW", "GO ANYWAY"})
		{
			assertFalse("no later-phase promise: " + fake, all.toUpperCase().contains(fake));
		}
	}

	@Test
	public void startRun_atTheTopAndTheBottom_isOneAction_oneState_andOneRun()
	{
		view.button(FarmingRunType.HERB).press();
		assertNotNull("Start Run above the list", view.startTopButton());
		assertEquals("Start Run", view.startTopButton().getLabel());
		assertEquals(view.startButton().isEnabled(), view.startTopButton().isEnabled());
		for (FarmingView.PatchCardView card : view.rowViews().values())
		{
			if (card.box.isChecked())
			{
				card.clickInclude();
			}
		}
		assertFalse("both follow the selection", view.startTopButton().isEnabled() || view.startButton().isEnabled());
		view.rowViews().get("herb.falador").clickInclude();
		assertTrue(view.startTopButton().isEnabled() && view.startButton().isEnabled());
		ActionButton top = view.startTopButton();
		ActionButton bottom = view.startButton();
		top.press();
		assertTrue("the top button opens preparation too (never active guidance directly)", store.intentFor(A).isPreparing());
		String id = store.intentFor(A).getIntentId();
		top.press();
		bottom.press();
		assertEquals("pressing again (either button) never makes a second run", id, store.intentFor(A).getIntentId());
		assertEquals(Collections.singletonList("herb.falador"), store.intentFor(A).getIncludedPatchIds());
	}

	/** Pass 33: Start combined run under the Combine control and below the list: one action, one state, one run. */
	@Test
	public void startCombinedRun_underCombineAndBelowTheList_isOneAction_oneState_andOneRun()
	{
		view.combineToggle().press();
		ActionButton top = view.combineStartButton();
		assertNotNull(top);
		assertTrue("shown while combining", top.isVisible());
		assertEquals(FarmingView.START_COMBINED_LABEL, top.getLabel());
		assertFalse("nothing chosen: not startable", top.isEnabled());
		// Placed directly under the Combine control, before the categories.
		FarmingCategoryGridTest.layout(view);
		int yCombine = javax.swing.SwingUtilities.convertPoint(view.combineToggle(), 0, 0, view).y;
		int yTop = javax.swing.SwingUtilities.convertPoint(top, 0, 0, view).y;
		int yHerb = javax.swing.SwingUtilities.convertPoint(view.button(FarmingRunType.HERB), 0, 0, view).y;
		assertTrue(yCombine + " < " + yTop + " < " + yHerb, yCombine < yTop && yTop < yHerb);
		assertEquals("full width", view.combineToggle().getWidth(), top.getWidth());

		view.include(FarmingRunType.HERB, true);
		ActionButton bottom = view.startButton();
		assertNotNull(bottom);
		assertTrue(top.isEnabled() && bottom.isEnabled());
		assertEquals(FarmingView.START_COMBINED_LABEL, bottom.getLabel());
		// Browsing a category keeps the choice and the top button.
		view.openCategory(FarmingRunType.HERB);
		assertEquals(Collections.singletonList(FarmingRunType.HERB), view.combinedTypes());
		assertTrue(top.isVisible());
		for (FarmingView.PatchCardView card : view.rowViews().values())
		{
			if (card.box.isChecked())
			{
				card.clickInclude();
			}
		}
		assertFalse("no patch included: neither starts", top.isEnabled());
		top.press();
		assertNull("a disabled Start never starts", store.intentFor(A));
		view.rowViews().get("herb.falador").clickInclude();
		assertTrue(top.isEnabled());
		view.overviewButton().press();
		assertEquals("one summary", 1, text(view).split("In this run", -1).length - 1);
		assertTrue(view.startButton().isEnabled());
		top.press();
		assertTrue("the top button opens preparation", store.intentFor(A).isPreparing());
		String id = store.intentFor(A).getIntentId();
		top.press();
		bottom.press();
		if (view.startButton() != null)
		{
			view.startButton().press();
		}
		assertEquals("never a second run", id, store.intentFor(A).getIntentId());
		assertEquals(Collections.singletonList("herb.falador"), store.intentFor(A).getIncludedPatchIds());
		assertFalse("hidden once the run exists", view.combineStartButton().isVisible());
	}

	@Test
	public void anEmptyScope_disablesStart_andStartProblemsAreExplained()
	{
		view.button(FarmingRunType.HERB).press();
		for (FarmingView.PatchCardView card : view.rowViews().values())
		{
			if (card.box.isChecked())
			{
				card.clickInclude();
			}
		}
		assertFalse(view.startButton().isEnabled());
		view.startButton().press();
		assertTrue("a disabled Start does nothing", calls.isEmpty());
		assertTrue(text(view).contains("Include at least one patch to start."));

		view.rowViews().get("herb.falador").clickInclude();
		store.switchOwner(FarmingOwner.standard(1L), now); // the store no longer holds A: Start must refuse, in words
		view.startButton().press();
		assertFalse(view.startProblems().isEmpty());
		assertTrue(text(view), text(view).contains("Log in on a standard world"));
	}

	@Test
	public void theRunCards_showTheObservedCrop_andTickOffAsKnowledgeChanges_inAnyOrder()
	{
		startHerbRun();
		now = T0 + MIN;
		FarmingKnowledgeFixtures.change(known, "herb.catherby", RANARR_READY, EMPTY, now);
		render();
		FarmingRunPage.RunPatchCard catherby = view.runPage().cards().get("herb.catherby");
		assertEquals("no automatic crop: the player decides", "Plant", catherby.runStatus.plainText());
		now = T0 + 2 * MIN;
		FarmingKnowledgeFixtures.change(known, "herb.catherby", EMPTY, SNAP_SEED, now);
		render();
		assertSame("updated in place", catherby, view.runPage().cards().get("herb.catherby"));
		assertEquals("the crop actually planted is recorded", "✓ Planted · Snapdragon", catherby.runStatus.plainText());
		assertFalse("no plan is set against what was planted", catherby.runStatus.plainText().contains("planned"));
		assertEquals("the sprite is the crop actually there", ItemID.SNAPDRAGON, catherby.icon.getItemId());
		assertTrue(text(view).contains("1 / "));
	}

	@Test
	public void skip_undoSkip_andEndRunWithConfirmation()
	{
		startHerbRun();
		FarmingRunPage.RunPatchCard falador = view.runPage().cards().get("herb.falador");
		assertTrue(falador.skip.isVisible());
		assertTrue("Skip is keyboard reachable", falador.skip.isFocusable());
		falador.skip.click();
		falador = view.runPage().cards().get("herb.falador");
		assertEquals("Skipped", falador.runStatus.plainText());
		assertEquals("Undo skip", falador.skip.getText());
		falador.skip.click();
		assertEquals("Not visited yet", view.runPage().cards().get("herb.falador").runStatus.plainText());

		view.runPage().endLink.click();
		assertNotNull("End asks first", view.runPage().keepGoingButton);
		view.runPage().keepGoingButton.press();
		assertNotNull("kept going", store.intentFor(A));
		view.runPage().endLink.click();
		view.runPage().confirmEndButton.press();
		assertNull("nothing done: ended without a summary", store.intentFor(A));
		assertFalse(view.isRunPageShown());
		assertEquals("back on the preview", FarmingRunType.HERB, view.selectedType());
	}

	@Test
	public void browsePatches_isReadOnlyInspection_withAReturnBanner_andNoSecondStart()
	{
		startHerbRun();
		view.runPage().browseLink.click();
		assertTrue(view.isBrowsingDuringRun());
		assertFalse(view.isRunPageShown());
		view.button(FarmingRunType.TREE).press();
		String all = text(view);
		assertTrue(all, all.contains("Herb run in progress"));
		assertNull("no second Start while a run exists", view.startButton());
		assertEquals("start + go", 2, calls.size());
		view.button(FarmingRunType.HERB).press();
		for (Component c : all(view))
		{
			if (c instanceof FarmingView.Link && "Return".equals(((FarmingView.Link) c).getText()))
			{
				((FarmingView.Link) c).click();
				break;
			}
		}
		assertTrue(view.isRunPageShown());
	}

	@Test
	public void aPausedRun_offersResume_andNeverAnAgeWarning()
	{
		startHerbRun();
		store.pause(now);
		render();
		assertNotNull(view.runPage().resumeButton);
		assertTrue(text(view).contains("Run paused"));
		now = T0 + 30L * 24 * 60 * MIN; // a month later
		render();
		String all = text(view);
		assertTrue("still there, still paused", all.contains("Run paused"));
		assertFalse(all, all.matches("(?is).*(paused · from|run from|moved on|stale|expired|ended automatically).*"));
		view.runPage().resumeButton.press();
		assertEquals(FarmingRunIntent.State.ACTIVE, store.intentFor(A).getState());
		assertTrue(text(view).contains("NEXT"));
	}

	@Test
	public void completion_showsOnlyObservedFacts_andDoneReturnsToThePreview()
	{
		view.button(FarmingRunType.HERB).press();
		for (FarmingView.PatchCardView card : view.rowViews().values())
		{
			if (card.box.isChecked() && !card.patchId.equals("herb.falador"))
			{
				card.clickInclude();
			}
		}
		view.startButton().press();
		now = T0 + MIN;
		FarmingKnowledgeFixtures.change(known, "herb.falador", RANARR_READY, EMPTY, now);
		store.track(A, known, now);
		now = T0 + 2 * MIN;
		FarmingKnowledgeFixtures.change(known, "herb.falador", EMPTY, SNAP_SEED, now);
		store.track(A, known, now);
		render();
		String all = text(view);
		assertTrue(all, all.contains("HERB RUN COMPLETE"));
		assertTrue(all.contains("1 patch replanted"));
		assertFalse("no invented yield, XP or profit", all.matches("(?s).*(herbs harvested|xp|gp|profit).*"));
		assertNotNull(view.runPage().doneButton);
		view.runPage().doneButton.press();
		assertNull(store.intentFor(A));
		assertFalse(view.isRunPageShown());
		assertEquals(FarmingRunType.HERB, view.selectedType());
	}

	@Test
	public void refreshes_updateInPlace_andTheHomeCardLeadsWithTheRun()
	{
		startHerbRun();
		here.add("herb.falador");
		render();
		FarmingRunPage.RunPatchCard card = view.runPage().cards().get("herb.falador");
		for (int i = 0; i < 5; i++)
		{
			now += 1000;
			render();
		}
		assertSame(card, view.runPage().cards().get("herb.falador"));
		assertTrue(text(view), text(view).contains("Falador \u00b7 Herb"));
		FarmingModel.HomeSummary home = model().getHome();
		assertTrue(home.getHeadline(), home.getHeadline().startsWith("Herb run · 0 / "));
		assertEquals("at Falador", home.getFacts().get(0));
		store.pause(now);
		assertTrue(model().getHome().getHeadline().startsWith("Herb run paused"));
	}

	private static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}

	/** Visible text (labels, wrapped labels, links, buttons) of components that are visible. */
	// -- Farming correction pass -------------------------------------------------------------------

	@Test
	public void combine_offKeepsOneType_onTogglesTypes_andStartsOneRun()
	{
		view.button(FarmingRunType.HERB).press();
		view.button(FarmingRunType.TREE).press();
		assertEquals("combine off: the buttons still select one type", FarmingRunType.TREE, view.selectedType());
		assertFalse(view.button(FarmingRunType.HERB).isSelected());

		view.combineLink().click();
		assertTrue(view.isCombineMode());
		assertEquals("the current type starts the selection", java.util.Collections.singletonList(FarmingRunType.TREE), view.combinedTypes());
		view.button(FarmingRunType.HERB).press();
		assertTrue("highlighted", view.button(FarmingRunType.HERB).isSelected());
		assertTrue(view.button(FarmingRunType.TREE).isSelected());
		String all = text(view);
		assertTrue(all, all.contains("COMBINED RUN"));
		assertTrue(all, all.contains("Tree + Herb \u00b7 "));
		assertTrue(all, all.contains("unique patches"));
		assertEquals(FarmingView.START_COMBINED_LABEL, view.startButton().getLabel());
		view.button(FarmingRunType.TREE).press();
		assertFalse("pressing again removes it", view.button(FarmingRunType.TREE).isSelected());
		view.button(FarmingRunType.TREE).press();
		assertNull("choosing never starts anything", store.intentFor(A));

		view.startButton().press();
		FarmingRunIntent run = store.intentFor(A);
		assertNotNull(run);
		assertEquals("in the order chosen", java.util.Arrays.asList(FarmingRunType.HERB, FarmingRunType.TREE), run.getRunTypes());
		assertTrue(text(view), text(view).contains("HERB + TREE RUN"));
		assertFalse("combining ends with the run's start", view.isCombineMode());
	}

	@Test
	public void combine_leavingWithoutStarting_createsNoRun_andTheOverviewLinkIsLabelled()
	{
		view.combineToggle().press();
		assertTrue(text(view), text(view).contains(FarmingView.COMBINE_EMPTY));
		view.button(FarmingRunType.HERB).press();
		assertEquals("the shared Farming Overview control, labelled", FarmingView.OVERVIEW_BUTTON, view.overviewButton().getLabel());
		view.overviewButton().press();
		assertTrue("Overview keeps the combined choice", view.isCombineMode());
		assertNull(store.intentFor(A));
		view.combineToggle().press();
		assertFalse(view.isCombineMode());
		view.overviewButton().press();
		assertNull(store.intentFor(A));
		String all = text(view);
		assertTrue(all, all.contains("FARMING CONTRACT"));
		assertTrue("not read: said so, never invented", all.contains("Not observed yet"));
	}

	@Test
	public void anotherAccount_neverInheritsTheCombinedSelectionOrPlans()
	{
		view.combineLink().click();
		view.button(FarmingRunType.HERB).press();
		view.draft().setUseBounty(true);
		view.draft().setTypePlan(FarmingRunType.HERB, "RANARR");
		FarmingOwner b = FarmingOwner.standard(777L);
		view.render(FarmingModel.build(CATALOG, new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, b, new HashMap<>(), Collections.emptyList()), now));
		assertFalse(view.isCombineMode());
		assertTrue(view.combinedTypes().isEmpty());
		assertFalse(view.draft().isUseBounty());
		assertNull(view.draft().getTypePlan(FarmingRunType.HERB));
	}

	@Test
	public void thePlanSelector_defaultsToYouChoose_neverTheLastCropSeen()
	{
		view.button(FarmingRunType.HERB).press();
		FarmingView.PatchCardView falador = view.rowViews().get("herb.falador");
		assertEquals("Replant \u25be", falador.plan.getText());
	}

	@Test
	public void preparation_leadsWithWhatIsNeeded_collapsesTheRest_andOffersTheBankFilter()
	{
		int[] state = {0};
		List<String> toggles = new ArrayList<>();
		view.attachBankFilter(() -> state[0], () -> toggles.add("toggle"));
		java.util.Map<Integer, Integer> some = new java.util.HashMap<>();
		some.put(ItemID.DIBBER, 1);
		some.put(ItemID.SPADE, 1);
		cap = com.osrstelemetry.plugin.account.AccountFixtures.maxed().spellbookRaw(0).inventory(some).build();
		startPreparing("herb.falador", "herb.catherby");
		FarmingRunPage page = view.runPage();
		assertEquals(FarmingRunPage.BANK_FILTER_CLOSED, page.bankFilterButton.getLabel());
		assertFalse("bank closed: nothing to filter", page.bankFilterButton.isEnabled());
		state[0] = 1;
		render();
		assertEquals(FarmingRunPage.BANK_FILTER_SHOW, page.bankFilterButton.getLabel());
		page.bankFilterButton.press();
		assertEquals("the player's click is the only trigger", 1, toggles.size());
		state[0] = 2;
		render();
		assertEquals(FarmingRunPage.BANK_FILTER_HIDE, page.bankFilterButton.getLabel());

		JPanel ready = page.groupLists.get(com.osrstelemetry.plugin.farming.FarmingPreparationView.Group.READY);
		assertNotNull(ready);
		assertFalse("carried items are collapsed by default", ready.isVisible());
		assertTrue("…under a counted header", text(view).contains("READY"));
		JPanel needed = page.groupLists.get(com.osrstelemetry.plugin.farming.FarmingPreparationView.Group.NEEDED);
		assertTrue(needed.isVisible());
		assertTrue("the seeds lead", java.util.Arrays.asList(needed.getComponents()).contains(page.prepRows.get("seed.herb.any")));
		assertTrue("the carried dibber sits in Ready", java.util.Arrays.asList(ready.getComponents()).contains(page.prepRows.get("tool.dibber")));
		FarmingRunPage.SupplyRow seeds = page.prepRows.get("seed.herb.any");
		assertFalse("evidence is a tooltip, not a line on every card", seeds.support.isVisible());
	}

	private static String text(Container c)
	{
		StringBuilder b = new StringBuilder();
		for (Component child : c.getComponents())
		{
			if (!child.isVisible())
			{
				continue;
			}
			if (child instanceof JLabel)
			{
				b.append(((JLabel) child).getText().replaceAll("<[^>]+>", "")).append('\n');
			}
			if (child instanceof ActionButton)
			{
				b.append(((ActionButton) child).getLabel()).append('\n');
			}
			if (child instanceof Container)
			{
				b.append(text((Container) child));
			}
		}
		return b.toString();
	}

	// -- Phase H: preparation -------------------------------------------------------------

	private boolean bankOpen;
	private com.osrstelemetry.plugin.account.AccountCapability cap = com.osrstelemetry.plugin.account.AccountFixtures.maxed().spellbookRaw(0).build();
	private final List<int[]> finds = new ArrayList<>();

	private com.osrstelemetry.plugin.farming.FarmingPreparationView prepView()
	{
		FarmingRunIntent intent = store.intentFor(A);
		if (intent == null || !intent.isPreparing())
		{
			return null;
		}
		com.osrstelemetry.plugin.travel.TransportCatalog transports = com.osrstelemetry.plugin.travel.TestTransports.CATALOG;
		long t = com.osrstelemetry.plugin.account.AccountFixtures.NOW;
		com.osrstelemetry.plugin.farming.FarmingPreparation.Plan plan = com.osrstelemetry.plugin.farming.FarmingPreparation.plan(intent, CATALOG, cap,
			com.osrstelemetry.plugin.travel.TransportEvaluator.evaluateAll(transports, cap, com.osrstelemetry.plugin.account.EvidenceSource.NONE, t),
			com.osrstelemetry.plugin.account.EvidenceSource.NONE);
		return com.osrstelemetry.plugin.farming.FarmingPreparationView.of(plan,
			com.osrstelemetry.plugin.prep.PreparationEvaluator.evaluate(plan.getRequest(), cap, t), bankOpen, null, t);
	}

	private void startPreparing(String... onlyPatches)
	{
		view.attachPreparation(this::prepView, finds::add);
		view.button(FarmingRunType.HERB).press();
		view.draft().setTypePlan(FarmingRunType.HERB, "RANARR"); // the player's deliberate choice (there is no automatic crop)
		java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(onlyPatches));
		for (FarmingView.PatchCardView card : view.rowViews().values())
		{
			if (card.box.isChecked() != keep.contains(card.patchId))
			{
				card.clickInclude();
			}
		}
		view.startButton().press();
	}

	@Test
	public void preparation_listsWhatToBring_withLiveStatus_andBeginIsAlwaysOffered()
	{
		startPreparing("herb.falador", "herb.catherby");
		String all = text(view);
		assertTrue(all, all.contains("HERB RUN \u00b7 PREPARING"));
		assertTrue(all, all.contains("supply types to collect"));
		assertTrue(all, all.contains("Spyglass hasn't seen your bank yet"));
		assertTrue(all, all.contains("STILL NEEDED"));
		assertTrue(all, all.contains("Herb seeds"));
		assertTrue("quantity on its own row", all.contains("\u00d72"));
		assertTrue(all.contains("Bank not seen yet"));
		assertTrue(all.contains("SUGGESTED ORDER"));
		assertFalse("no redundant explainer on the prep page (correction pass 2)", all.contains("You withdraw, equip and travel yourself"));
		ActionButton begin = view.runPage().beginButton;
		assertNotNull(begin);
		assertEquals("bank unseen: supplies missing and travel unverified", "Begin anyway", begin.getLabel());
		assertEquals("Begin is always the obvious panel button; its label carries the caveat", ActionButton.Style.PRIMARY, begin.getStyle());
		assertTrue("keyboard reachable", begin.isFocusable());
		assertNotNull("End run stays a separate action", view.runPage().endLink);
		assertTrue("the patches are not shown while preparing", view.runPage().cards().isEmpty());
	}

	@Test
	public void bankOpen_findOnlyExplains_rowsUpdateInPlace_andConfirmedShowsBeginRun()
	{
		cap = com.osrstelemetry.plugin.account.AccountFixtures.maxed().spellbookRaw(0)
			.bank(com.osrstelemetry.plugin.account.AccountFixtures.bank(60_000L, ItemID.RANARR_SEED, 30, ItemID.BUCKET_ULTRACOMPOST, 30, ItemID.DIBBER, 1,
				ItemID.POH_TABLET_FALADORTELEPORT, 10, ItemID.POH_TABLET_CAMELOTTELEPORT, 10, ItemID.LAWRUNE, 100, ItemID.AIRRUNE, 1000,
				ItemID.WATERRUNE, 1000))
			.build();
		bankOpen = true;
		startPreparing("herb.falador", "herb.catherby");
		FarmingRunPage.SupplyRow seeds = view.runPage().prepRows.get("seed.herb.any");
		assertNotNull(seeds);
		assertTrue(seeds.status.plainText(), seeds.status.plainText().startsWith("Withdraw 2"));
		assertTrue("Find is offered for anything not carried", seeds.find.isVisible());
		seeds.find.click();
		assertEquals("Find only marks ids for the passive outline", 1, finds.size());
		assertTrue("every acceptable herb seed is outlined", java.util.Arrays.stream(finds.get(0)).anyMatch(id -> id == ItemID.RANARR_SEED));
		assertTrue("Find explains where it was seen", seeds.locate.isVisible());
		assertEquals("Hide", seeds.find.getText());
		seeds.find.click();
		assertNull("toggling Find again clears it", finds.get(1));
		assertFalse(seeds.locate.isVisible());
		seeds.find.click();
		// withdraw the seeds: same row object, new status; Find clears itself
		cap = com.osrstelemetry.plugin.account.AccountFixtures.maxed().spellbookRaw(0).inventory(java.util.Collections.singletonMap(ItemID.RANARR_SEED, 2))
			.bank(com.osrstelemetry.plugin.account.AccountFixtures.bank(60_000L, ItemID.RANARR_SEED, 28, ItemID.BUCKET_ULTRACOMPOST, 30, ItemID.DIBBER, 1,
				ItemID.POH_TABLET_FALADORTELEPORT, 10, ItemID.POH_TABLET_CAMELOTTELEPORT, 10, ItemID.LAWRUNE, 100, ItemID.AIRRUNE, 1000,
				ItemID.WATERRUNE, 1000))
			.build();
		render();
		assertTrue("same row instance", view.runPage().prepRows.get("seed.herb.any") == seeds);
		assertEquals("\u2713 Carried", seeds.status.plainText());
		assertNull("carried: Find has nothing left to show", view.runPage().findKey);
		assertNull(finds.get(finds.size() - 1));

		java.util.Map<Integer, Integer> everything = new java.util.HashMap<>();
		for (int id : new int[]{ItemID.RANARR_SEED, ItemID.BUCKET_ULTRACOMPOST, ItemID.DIBBER, ItemID.RAKE, ItemID.SPADE,
			ItemID.POH_TABLET_FALADORTELEPORT, ItemID.POH_TABLET_CAMELOTTELEPORT, ItemID.LAWRUNE, ItemID.AIRRUNE, ItemID.WATERRUNE, ItemID.FIRERUNE})
		{
			everything.put(id, 50);
		}
		cap = com.osrstelemetry.plugin.account.AccountFixtures.maxed().spellbookRaw(0).inventory(everything)
			.bank(com.osrstelemetry.plugin.account.AccountFixtures.bank(60_000L)).build();
		render();
		assertTrue(text(view), text(view).contains("Ready to begin"));
		ActionButton begin = view.runPage().beginButton;
		assertEquals("Begin run", begin.getLabel());
		assertEquals(ActionButton.Style.PRIMARY, begin.getStyle());
		begin.press();
		assertFalse(store.intentFor(A).isPreparing());
		assertTrue("the run page is shown", text(view).contains("NEXT"));
	}

	@Test
	public void destinationRows_expandInPlace_andSurviveRefresh()
	{
		startPreparing("herb.falador", "herb.catherby");
		FarmingRunPage.DestinationRow row = view.runPage().siteRows.values().iterator().next();
		assertFalse(row.details.isVisible());
		row.expand.getAccessibleContext();
		row.toggle();
		assertTrue(row.details.isVisible());
		assertTrue(row.details.getComponentCount() > 0);
		render();
		assertTrue("same row after a refresh", view.runPage().siteRows.values().iterator().next() == row);
		assertTrue("still expanded after a refresh", row.isExpanded() && row.details.isVisible());
	}
}
