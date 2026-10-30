package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingKnowledgeFixtures;
import com.osrstelemetry.plugin.farming.FarmingModel;
import com.osrstelemetry.plugin.farming.FarmingOwner;
import com.osrstelemetry.plugin.farming.FarmingPatchCatalog;
import com.osrstelemetry.plugin.farming.FarmingPatchType;
import com.osrstelemetry.plugin.farming.FarmingProduce;
import com.osrstelemetry.plugin.farming.FarmingRunType;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.farming.PatchCondition;
import com.osrstelemetry.plugin.farming.PatchKnowledge;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Pass 31: the Farming overview's category grid. One status per card, Ready over Growing over Dead; the card opens its
 * patches; "Combine run types" is a mode with ticks on the cards, separate from opening them, and one Start combined run.
 */
public class FarmingCategoryGridTest
{
	static final long T0 = 1_760_000_000_000L;
	static final long MIN = 60_000L;
	static final FarmingOwner A = FarmingOwner.standard(4242L);

	static int herb(FarmingProduce p, PatchCondition c, int stage)
	{
		return FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, p, c, stage);
	}

	static FarmingModel model(Map<String, PatchKnowledge> known, long now)
	{
		return FarmingModel.build(FarmingPatchCatalog.standard(), new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, A, known, Collections.emptyList()), now);
	}

	/** One herb seen ready long ago (timed), five growing just now, one dead. */
	static Map<String, PatchKnowledge> mixed(long now)
	{
		Map<String, PatchKnowledge> known = new HashMap<>();
		FarmingKnowledgeFixtures.see(known, "herb.falador", herb(FarmingProduce.RANARR, PatchCondition.GROWING, 0), now - 3 * 24 * 60 * MIN);
		for (String p : new String[]{"herb.harmony", "herb.troll_stronghold", "herb.weiss", "herb.farming_guild", "herb.catherby"})
		{
			FarmingKnowledgeFixtures.see(known, p, herb(FarmingProduce.RANARR, PatchCondition.GROWING, 0), now - MIN);
		}
		FarmingKnowledgeFixtures.see(known, "herb.ardougne", herb(null, PatchCondition.DEAD, -1), now - MIN);
		return known;
	}

	static void layout(java.awt.Container c)
	{
		c.doLayout();
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof java.awt.Container)
			{
				layout((java.awt.Container) k);
			}
		}
	}

	@Test
	public void oneStatusPerCard_readyThenGrowingThenDead()
	{
		long now = T0;
		Map<String, PatchKnowledge> known = mixed(now);
		FarmingModel m = model(known, now);
		FarmingModel.TypeSummary t = m.summary(FarmingRunType.HERB);
		assertEquals(1, t.getReady());
		assertEquals(1, t.getDead());
		assertEquals("1 Ready", FarmingView.categoryStatus(m, FarmingRunType.HERB).text);
		assertEquals(SpyglassTheme.STATUS_SUCCESS, FarmingView.categoryStatus(m, FarmingRunType.HERB).tone);
		known.remove("herb.falador");
		m = model(known, now);
		assertEquals("5 Growing", FarmingView.categoryStatus(m, FarmingRunType.HERB).text);
		assertEquals(SpyglassTheme.TEXT_PRIMARY, FarmingView.categoryStatus(m, FarmingRunType.HERB).tone);
		Map<String, PatchKnowledge> dead = new HashMap<>();
		FarmingKnowledgeFixtures.see(dead, "herb.ardougne", herb(null, PatchCondition.DEAD, -1), now - MIN);
		m = model(dead, now);
		assertEquals("1 Dead", FarmingView.categoryStatus(m, FarmingRunType.HERB).text);
		assertEquals(SpyglassTheme.STATUS_DANGER, FarmingView.categoryStatus(m, FarmingRunType.HERB).tone);
		Map<String, PatchKnowledge> clear = new HashMap<>();
		FarmingKnowledgeFixtures.see(clear, "herb.ardougne", FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, null, PatchCondition.EMPTY, -1), now - MIN);
		assertEquals("1 Clear", FarmingView.categoryStatus(model(clear, now), FarmingRunType.HERB).text);
		// Never seen: no card at all (not "0 ready"), and nothing counted as ready.
		assertFalse(model(clear, now).trackedTypes().contains(FarmingRunType.TREE));
		assertEquals(0, model(clear, now).summary(FarmingRunType.TREE).getReady());
	}

	/** Pass 32: Home's Farming card uses the grid's priority, Ready (green) > Growing (white) > Dead (red), with that count. */
	@Test
	public void home_readyThenGrowingThenDead_agreesWithTheGrid()
	{
		long now = T0;
		Map<String, PatchKnowledge> known = mixed(now); // 1 ready, 5 growing, 1 dead
		FarmingModel.HomeSummary h = model(known, now).getHome();
		assertEquals("1 Ready", h.getHeadline());
		assertEquals(FarmingModel.Tone.GREEN, h.getHeadlineTone());
		assertEquals(FarmingModel.Tone.GREEN, h.getRail());
		assertEquals(SpyglassTheme.STATUS_SUCCESS, FarmingView.color(h.getHeadlineTone()));

		known.remove("herb.falador"); // 5 growing, 1 dead: growing wins, white, no alarm rail
		h = model(known, now).getHome();
		assertEquals("5 Growing", h.getHeadline());
		assertEquals(FarmingModel.Tone.PRIMARY, h.getHeadlineTone());
		assertNull(h.getRail());
		assertEquals(SpyglassTheme.TEXT_PRIMARY, FarmingView.color(h.getHeadlineTone()));

		Map<String, PatchKnowledge> dead = new HashMap<>();
		FarmingKnowledgeFixtures.see(dead, "herb.ardougne", herb(null, PatchCondition.DEAD, -1), now - MIN);
		h = model(dead, now).getHome();
		assertEquals("1 Dead", h.getHeadline());
		assertEquals(FarmingModel.Tone.RED, h.getRail());
		assertEquals(SpyglassTheme.STATUS_DANGER, FarmingView.color(h.getHeadlineTone()));
		// Untracked patches are still mentioned, never as the headline.
		assertTrue(h.getFacts().toString(), h.getFacts().get(h.getFacts().size() - 1).endsWith("not tracked"));
	}

	static void click(java.awt.Component c)
	{
		int x = Math.max(1, c.getWidth() / 2);
		int y = Math.max(1, c.getHeight() / 2);
		c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
			java.awt.event.MouseEvent.BUTTON1));
		c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1));
	}

	static void key(javax.swing.JComponent c, int code)
	{
		for (int id : new int[]{java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED})
		{
			java.awt.event.KeyEvent e = new java.awt.event.KeyEvent(c, id, 0, 0, code, code == java.awt.event.KeyEvent.VK_SPACE ? ' ' : '\n');
			javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStrokeForEvent(e);
			Object name = c.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(ks);
			if (name != null)
			{
				c.getActionMap().get(name).actionPerformed(new java.awt.event.ActionEvent(c, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
			}
		}
	}

	private static FarmingView.CategoryTile tile(FarmingView v, FarmingRunType t)
	{
		layout(v);
		return (FarmingView.CategoryTile) RecurringPagesTest.named(v, "category." + t.name());
	}

	@Test
	public void theCardOpensItsPatches_outsideCombining()
	{
		FarmingView v = new FarmingView();
		v.setSize(242, 2000);
		v.render(model(mixed(T0), T0));
		FarmingView.CategoryTile herb = tile(v, FarmingRunType.HERB);
		assertNotNull(herb);
		assertNull("no ticks outside combining", herb.box);
		assertNull("no separate View patches outside combining", herb.viewPatches);
		click(herb);
		assertEquals("the card opens the herb patches", FarmingRunType.HERB, v.selectedType());
		// The shared controls are on the patch page too, and Farming Overview returns to the grid.
		assertNotNull(RecurringPagesTest.named(v, "farming.overview"));
		assertNotNull(RecurringPagesTest.named(v, "farming.combine"));
		v.overviewButton().press();
		assertNull(v.selectedType());
		assertTrue(v.overviewButton().isSelected());
	}

	/** Pass 32: combining, the card's click is the selection: no second step, the player stays on the overview. */
	@Test
	public void combining_theCardTogglesInclusionDirectly_andStaysOnTheOverview()
	{
		FarmingView v = new FarmingView();
		v.setSize(242, 2000);
		v.render(model(mixed(T0), T0));
		v.combineToggle().press();
		assertTrue(v.isCombineMode());
		assertTrue(v.combineToggle().isSelected());
		assertEquals(FarmingView.COMBINE_TOGGLE_ON, v.combineToggle().getLabel());

		FarmingView.CategoryTile h = tile(v, FarmingRunType.HERB);
		assertFalse(h.box.isSelected());
		click(h);
		assertEquals(Collections.singletonList(FarmingRunType.HERB), v.combinedTypes());
		assertNull("selecting never opens the category", v.browseType());
		h = tile(v, FarmingRunType.HERB);
		assertTrue("tick, face and state agree", h.box.isSelected());
		assertTrue(h.getAccessibleContext().getAccessibleName(), h.getAccessibleContext().getAccessibleName().contains("in the combined run"));
		assertTrue("the shared category button agrees", v.button(FarmingRunType.HERB).isSelected());
		// One summary only.
		String all = RecurringPagesTest.text(v);
		assertTrue(all, all.contains("In this run: Herb"));
		assertEquals(all, all.indexOf("In this run"), all.lastIndexOf("In this run"));
		assertEquals(FarmingView.START_COMBINED_LABEL, v.startButton().getLabel());
		assertEquals(FarmingView.START_COMBINED_LABEL, v.startTopButton().getLabel());
		// Clicking again takes it out.
		click(tile(v, FarmingRunType.HERB));
		assertTrue(v.combinedTypes().isEmpty());
		assertTrue(RecurringPagesTest.text(v).contains("Nothing chosen yet"));
	}

	/** Pass 32: the tick and View patches each fire once and never also fire the card. */
	@Test
	public void combining_tickAndViewPatches_fireOnce_byMouseAndKeyboard()
	{
		FarmingView v = new FarmingView();
		v.setSize(242, 2000);
		v.render(model(mixed(T0), T0));
		v.combineToggle().press();
		// Mouse on the tick: in, once (a double toggle would leave it out).
		click(tile(v, FarmingRunType.HERB).box);
		assertEquals(Collections.singletonList(FarmingRunType.HERB), v.combinedTypes());
		assertNull(v.browseType());
		// Keyboard on the tick: out, once.
		key(tile(v, FarmingRunType.HERB).box, java.awt.event.KeyEvent.VK_SPACE);
		assertTrue(v.combinedTypes().isEmpty());
		// Keyboard on the card: in, once.
		key(tile(v, FarmingRunType.HERB), java.awt.event.KeyEvent.VK_ENTER);
		assertEquals(Collections.singletonList(FarmingRunType.HERB), v.combinedTypes());
		// View patches: opens the patch list, inclusion untouched (by mouse, then by keyboard on another card).
		click(tile(v, FarmingRunType.HERB).viewPatches);
		assertSame(FarmingRunType.HERB, v.browseType());
		assertEquals(Collections.singletonList(FarmingRunType.HERB), v.combinedTypes());
		assertTrue(RecurringPagesTest.text(v).contains("HERB PATCHES"));
		assertTrue(RecurringPagesTest.text(v).contains("In the combined run"));
		v.overviewButton().press();
		assertNull(v.browseType());
		assertTrue("Overview keeps combining", v.isCombineMode());
		FarmingView.CategoryTile bins = tile(v, FarmingRunType.COMPOST);
		assertNotNull("the bins can join a combined run", bins);
		key(bins.viewPatches, java.awt.event.KeyEvent.VK_ENTER);
		assertSame(FarmingRunType.COMPOST, v.browseType());
		assertEquals("browsing the bins never adds them", Collections.singletonList(FarmingRunType.HERB), v.combinedTypes());
		assertTrue(RecurringPagesTest.text(v).contains("Not in the combined run"));
	}

	/** Pass 32: selections survive browsing, Overview, and switching combining off and on; navigation starts nothing. */
	@Test
	public void combining_selectionSurvivesNavigationAndModeSwitches()
	{
		FarmingView v = new FarmingView();
		v.setSize(242, 2000);
		v.render(model(mixed(T0), T0));
		v.combineToggle().press();
		click(tile(v, FarmingRunType.HERB));
		click(tile(v, FarmingRunType.COMPOST));
		java.util.List<FarmingRunType> both = java.util.Arrays.asList(FarmingRunType.HERB, FarmingRunType.COMPOST);
		assertEquals(both, v.combinedTypes());
		click(tile(v, FarmingRunType.HERB).viewPatches);
		v.overviewButton().press();
		assertEquals(both, v.combinedTypes());
		v.combineToggle().press(); // off
		assertFalse(v.isCombineMode());
		v.combineToggle().press(); // on again: the same choice
		assertEquals(both, v.combinedTypes());
		assertFalse("navigation never starts a run", v.isRunPageShown());
	}
}
