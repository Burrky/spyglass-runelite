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
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Browse / Preview behaviour: selection, persistence, Special row, draft
 * isolation, the patch cards (observed-crop sprite, include box, expandable
 * planned crop) and no Start Run anywhere.
 */
public class FarmingViewTest
{
	private static final long T0 = 1_760_000_000_000L;
	private static final FarmingOwner A = FarmingOwner.standard(4242L);
	private static final FarmingOwner B = FarmingOwner.standard(7777L);

	private final Map<String, String> config = new HashMap<>();

	private FarmingView view()
	{
		FarmingView v = new FarmingView();
		v.attachSelectionStore(() -> config.get("farmingPreview"), s -> config.put("farmingPreview", s));
		v.setSize(225, 2000);
		return v;
	}

	private static FarmingModel model(FarmingOwner owner, Map<String, PatchKnowledge> known)
	{
		return model(owner, known, T0);
	}

	private static FarmingModel model(FarmingOwner owner, Map<String, PatchKnowledge> known, long now)
	{
		return FarmingModel.build(FarmingPatchCatalog.standard(),
			new FarmingSnapshot(FarmingSnapshot.State.AVAILABLE, owner, known, Collections.emptyList()), now);
	}

	private static final long MIN = 60_000L;
	private static final int HERB_CLEAR = FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, null, PatchCondition.EMPTY, -1);

	private static int herb(FarmingProduce p, PatchCondition c, int stage)
	{
		return FarmingKnowledgeFixtures.raw(FarmingPatchType.HERB, p, c, stage);
	}

	/** Fires the key bindings exactly as a focused Space / Enter press and release would. */
	private static void key(JComponent c, String key)
	{
		press(c, key);
		release(c, key);
	}

	private static void press(JComponent c, String key)
	{
		binding(c, "pressed " + key);
	}

	private static void release(JComponent c, String key)
	{
		binding(c, "released " + key);
	}

	private static void binding(JComponent c, String stroke)
	{
		Object name = c.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke));
		assertNotNull(stroke + " is bound", name);
		c.getActionMap().get(name).actionPerformed(new ActionEvent(c, ActionEvent.ACTION_PERFORMED, stroke));
	}

	private static void click(Component c)
	{
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, 3, 3, 1, false, MouseEvent.BUTTON1));
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, 3, 3, 1, false, MouseEvent.BUTTON1));
	}

	private FarmingView.PatchCardView herbCard(FarmingView v, Map<String, PatchKnowledge> known, long now)
	{
		v.render(model(A, known, now));
		if (v.selectedType() != FarmingRunType.HERB)
		{
			v.button(FarmingRunType.HERB).press();
		}
		layout(v);
		return v.rowViews().get("herb.falador");
	}

	/** Lays the whole tree out at the view's sidebar width, so mouse events land on real bounds. */
	private static void layout(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layout((Container) child);
			}
		}
	}

	@Test
	public void selection_staysPressed_switchesInstantly_andTheSelectedOneCannotBeUnpressed()
	{
		FarmingView v = view();
		v.render(model(A, Collections.emptyMap()));
		assertNull(v.selectedType());
		v.button(FarmingRunType.HERB).press();
		assertEquals(FarmingRunType.HERB, v.selectedType());
		assertTrue(v.button(FarmingRunType.HERB).isSelected());
		v.button(FarmingRunType.HERB).press(); // again: nothing
		assertEquals(FarmingRunType.HERB, v.selectedType());
		assertTrue(v.button(FarmingRunType.HERB).isSelected());
		v.button(FarmingRunType.TREE).press();
		assertFalse(v.button(FarmingRunType.HERB).isSelected());
		assertTrue(v.button(FarmingRunType.TREE).isSelected());
		assertEquals(FarmingPatchCatalog.standard().allPatches().stream().filter(p -> FarmingRunType.TREE.covers(p.getType())).count(),
			v.rowViews().size());
		v.showOverview();
		assertNull(v.selectedType());
		assertTrue(v.rowViews().isEmpty());
	}

	@Test
	public void theSelectedPreview_survivesARestart_andAnInvalidValueFallsBackToTheOverview()
	{
		FarmingView first = view();
		first.button(FarmingRunType.REDWOOD).press();
		assertEquals("REDWOOD", config.get("farmingPreview"));
		FarmingView restarted = view();
		assertEquals(FarmingRunType.REDWOOD, restarted.selectedType());
		assertTrue("a special selection opens the Special row", restarted.isSpecialsOpen());
		config.put("farmingPreview", "GRAPEVINE");
		assertNull(view().selectedType());
		first.showOverview();
		assertEquals("", config.get("farmingPreview"));
	}

	@Test
	public void specialRow_toggles_butStaysOpenWhileASpecialIsSelected()
	{
		FarmingView v = view();
		assertFalse(v.isSpecialsOpen());
		v.specialToggle().press();
		assertTrue(v.isSpecialsOpen());
		v.button(FarmingRunType.HESPORI).press();
		v.specialToggle().press();
		assertTrue("its button must stay visible", v.isSpecialsOpen());
		v.button(FarmingRunType.HERB).press();
		v.specialToggle().press();
		assertFalse(v.isSpecialsOpen());
	}

	@Test
	public void includeToggle_changesOnlyTheDraft_andTheDraftResetsForAnotherAccount()
	{
		FarmingView v = view();
		Map<String, PatchKnowledge> known = new HashMap<>();
		FarmingModel m = model(A, known);
		v.render(m);
		v.button(FarmingRunType.HERB).press();
		FarmingView.PatchCardView falador = v.rowViews().get("herb.falador");
		assertTrue("not tracked yet: included by default", falador.box.isChecked());
		String before = v.scopeText();
		falador.clickInclude();
		assertFalse(falador.box.isChecked());
		assertFalse(before.equals(v.scopeText()));
		assertTrue("nothing observed was written", known.isEmpty());
		v.render(model(A, known));
		assertFalse("kept while browsing", v.rowViews().get("herb.falador").box.isChecked());
		v.render(model(B, known));
		assertTrue("another account starts clean", v.rowViews().get("herb.falador").box.isChecked());
	}

	@Test
	public void unchangedStructure_updatesInPlace_insteadOfRebuilding()
	{
		FarmingView v = view();
		v.render(model(A, Collections.emptyMap()));
		v.button(FarmingRunType.HERB).press();
		FarmingView.PatchCardView row = v.rowViews().get("herb.falador");
		v.render(model(A, Collections.emptyMap()));
		assertSame(row, v.rowViews().get("herb.falador"));
	}

	@Test
	public void notAvailable_states_showAMessage_notRows()
	{
		FarmingView v = view();
		v.button(FarmingRunType.HERB).press();
		v.render(FarmingModel.build(FarmingPatchCatalog.standard(), FarmingSnapshot.LOGGED_OUT, T0));
		assertTrue(v.rowViews().isEmpty());
		assertTrue(text(v).contains("Log in to see your patches."));
	}

	@Test
	public void startRun_appearsOnlyInARunPreview_andNothingPromisesGuidance()
	{
		FarmingView v = view();
		v.render(model(A, Collections.emptyMap()));
		assertNull("the overview has no Start", v.startButton());
		for (FarmingRunType t : FarmingRunType.values())
		{
			v.button(t).press();
			assertNotNull(t + " preview offers Start", v.startButton());
			assertEquals("Start Run", v.startButton().getLabel());
			if (!t.isCrop())
			{
				continue; // the compost bins page: one Start under its cards
			}
			assertEquals("the same action at the top of the list", "Start Run", v.startTopButton().getLabel());
			String all = text(v).toUpperCase();
			assertFalse(all.contains("GUIDE"));
			assertFalse(all.contains("TELEPORT"));
			assertFalse(all.contains("READY TO GO"));
			assertNotNull(v.scopeText());
		}
		v.showOverview();
		assertNull(v.startButton());
	}

	private static String text(Container c)
	{
		StringBuilder b = new StringBuilder();
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel)
			{
				b.append(((JLabel) child).getText()).append('\n');
			}
			if (child instanceof AbstractButton)
			{
				b.append(((AbstractButton) child).getText()).append('\n');
			}
			if (child instanceof RunTypeButton)
			{
				b.append(((RunTypeButton) child).getLabel()).append('\n');
			}
			if (child instanceof Container)
			{
				b.append(text((Container) child));
			}
		}
		return b.toString();
	}

	@Test
	public void cardSprite_followsTheObservedCrop_throughEveryCondition_andAPlanNeverChangesIt()
	{
		Map<String, PatchKnowledge> known = new HashMap<>();
		FarmingView v = view();
		long t = T0 - 3 * 60 * MIN;

		FarmingView.PatchCardView card = herbCard(v, known, t);
		assertEquals("untracked: the herb patch sprite", ItemID.SLAYER_HERB_SACK, card.icon.getItemId());
		assertEquals("Herb patch, not tracked yet", card.icon.getToolTipText());

		FarmingKnowledgeFixtures.see(known, "herb.falador", HERB_CLEAR, t);
		card = herbCard(v, known, t + MIN);
		assertEquals("clear: the same patch sprite", ItemID.SLAYER_HERB_SACK, card.icon.getItemId());
		assertEquals("Herb patch, clear", card.icon.getToolTipText());
		assertEquals("CLEAR", card.status.plainText());

		int seed = herb(FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 0);
		FarmingKnowledgeFixtures.change(known, "herb.falador", HERB_CLEAR, seed, t + 2 * MIN);
		card = herbCard(v, known, t + 3 * MIN);
		assertEquals("planted: the herb, not the seed", ItemID.SNAPDRAGON, card.icon.getItemId());
		assertEquals("Snapdragon", card.icon.getToolTipText());
		assertEquals("Snapdragon", card.icon.getAccessibleContext().getAccessibleName());
		assertEquals(AccessibleRole.ICON, card.icon.getAccessibleContext().getAccessibleRole());
		assertTrue(card.status.plainText(), card.status.plainText().startsWith("Ready in ~"));

		v.draft().setPlannedCrop("herb.falador", FarmingProduce.TORSTOL);
		card = herbCard(v, known, t + 4 * MIN);
		assertEquals("a planned replacement never changes the planted crop's sprite", ItemID.SNAPDRAGON, card.icon.getItemId());
		assertEquals("Torstol ▾", card.plan.getText());

		card = herbCard(v, known, T0 + 2 * 24 * 60 * MIN);
		assertEquals("READY", card.status.plainText());
		assertEquals("still the crop once ready", ItemID.SNAPDRAGON, card.icon.getItemId());

		int diseased = herb(FarmingProduce.SNAPDRAGON, PatchCondition.DISEASED, -1);
		FarmingKnowledgeFixtures.change(known, "herb.falador", seed, diseased, t + 30 * MIN);
		card = herbCard(v, known, T0 + 2 * 24 * 60 * MIN);
		assertEquals("observed disease overrides readiness", "DISEASED", card.status.plainText());
		assertEquals(ItemID.SNAPDRAGON, card.icon.getItemId());

		int dead = herb(null, PatchCondition.DEAD, -1); // a dead herb reads as no particular herb
		FarmingKnowledgeFixtures.change(known, "herb.falador", diseased, dead, t + 50 * MIN);
		card = herbCard(v, known, t + 51 * MIN);
		assertEquals("DEAD", card.status.plainText());
		assertEquals(ItemID.SNAPDRAGON, card.icon.getItemId());

		FarmingKnowledgeFixtures.change(known, "herb.falador", dead, HERB_CLEAR, t + 60 * MIN);
		card = herbCard(v, known, t + 61 * MIN);
		assertEquals("an observed clear returns to the patch sprite", ItemID.SLAYER_HERB_SACK, card.icon.getItemId());

		int ranarr = herb(FarmingProduce.RANARR, PatchCondition.GROWING, 0);
		FarmingKnowledgeFixtures.change(known, "herb.falador", HERB_CLEAR, ranarr, t + 70 * MIN);
		card = herbCard(v, known, t + 71 * MIN);
		assertEquals("an observed replant shows the new crop", ItemID.RANARR_WEED, card.icon.getItemId());

		FarmingKnowledgeFixtures.change(known, "herb.falador", ranarr, 220, t + 80 * MIN); // undecodable
		card = herbCard(v, known, t + 81 * MIN);
		assertEquals("CHECK PATCH?", card.status.plainText());
		assertEquals("an unknown reading keeps the retained crop", ItemID.RANARR_WEED, card.icon.getItemId());
		assertEquals("...without presenting it as newly confirmed", "Ranarr (last known)", card.icon.getToolTipText());
	}

	@Test
	public void onlyTheBox_changesInclusion_spriteAndExpandNeverDo_andAllWorkFromTheKeyboard()
	{
		FarmingView v = view();
		FarmingView.PatchCardView card = herbCard(v, new HashMap<>(), T0);
		assertTrue(card.box.isChecked());
		String scope = v.scopeText();

		click(card.icon);
		click(card.tile);
		click(card.location);
		assertTrue("the sprite, the face and the text never toggle inclusion", card.box.isChecked());

		assertFalse("collapsed by default: the plan is out of the way", card.isExpanded());
		assertFalse(card.planSection.isVisible());
		click(card.expand);
		assertTrue(card.isExpanded());
		assertTrue(card.planSection.isVisible());
		assertTrue("expanding never toggles inclusion", card.box.isChecked());
		assertEquals(scope, v.scopeText());
		assertEquals(AccessibleRole.TOGGLE_BUTTON, card.expand.getAccessibleContext().getAccessibleRole());
		assertTrue(card.expand.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.EXPANDED));

		key(card.expand, "ENTER");
		assertFalse("Enter closes it again", card.isExpanded());
		key(card.expand, "SPACE");
		assertTrue(card.isExpanded());
		assertTrue(card.box.isChecked());

		click(card.box);
		assertFalse("a click on the box toggles it", card.box.isChecked());
		assertFalse(scope.equals(v.scopeText()));
		key(card.box, "SPACE");
		assertTrue("Space on the focused box toggles it back", card.box.isChecked());
		assertEquals(AccessibleRole.CHECK_BOX, card.box.getAccessibleContext().getAccessibleRole());
		assertTrue(card.box.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
		assertEquals("Include Falador in the plan", card.box.getAccessibleContext().getAccessibleName());

		assertTrue(card.box.isFocusable());
		assertTrue(card.expand.isFocusable());
		assertTrue("the planned-crop selector is reachable by keyboard", card.plan.isFocusable());
		assertFalse("the card face itself is not a control", card.tile.isFocusable());
		assertNotNull(card.plan.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("released SPACE")));
	}

	@Test
	public void expansion_survivesRefreshes_andSwitchingPreviews()
	{
		FarmingView v = view();
		FarmingView.PatchCardView card = herbCard(v, new HashMap<>(), T0);
		card.toggleExpanded();
		v.render(model(A, Collections.emptyMap(), T0 + MIN));
		assertTrue(v.rowViews().get("herb.falador").isExpanded());
		v.button(FarmingRunType.TREE).press();
		assertFalse(v.rowViews().values().iterator().next().isExpanded());
		v.button(FarmingRunType.HERB).press();
		assertTrue("reopening the preview keeps it open", v.rowViews().get("herb.falador").isExpanded());
		assertFalse(v.rowViews().get("herb.catherby").isExpanded());
		v.render(model(B, Collections.emptyMap(), T0 + MIN));
		assertFalse("another account starts collapsed", v.rowViews().get("herb.falador").isExpanded());
	}

	@Test
	public void excludedCards_stayReadable_onlyTheBoxAndRailQuieten()
	{
		Map<String, PatchKnowledge> known = new HashMap<>();
		long t = T0 - 3 * 24 * 60 * MIN;
		FarmingKnowledgeFixtures.see(known, "herb.falador", HERB_CLEAR, t);
		FarmingKnowledgeFixtures.change(known, "herb.falador", HERB_CLEAR, herb(FarmingProduce.SNAPDRAGON, PatchCondition.GROWING, 0), t + MIN);
		FarmingView v = view();
		FarmingView.PatchCardView card = herbCard(v, known, T0);
		assertEquals("READY", card.status.plainText());
		assertTrue(card.box.isChecked());
		java.awt.Color includedStatus = card.status.getForeground();
		assertEquals(SpyglassTheme.ACCENT_GOLD, card.tile.getRail());

		card.clickInclude();
		assertFalse(card.box.isChecked());
		assertEquals(SpyglassTheme.TEXT_PRIMARY, card.location.getForeground());
		assertEquals(includedStatus, card.status.getForeground());
		assertEquals(SpyglassTheme.TILE_TEXT_QUIET, card.provenance.getForeground());
		assertEquals("the sprite stays", ItemID.SNAPDRAGON, card.icon.getItemId());
		assertNotNull("the rail stays (same width: nothing shifts)", card.tile.getRail());
		assertFalse("but quieter", SpyglassTheme.ACCENT_GOLD.equals(card.tile.getRail()));
	}

	@Test
	public void aReleaseWithoutItsPress_neverActivates_soAMenuEnterCannotReopenTheMenu()
	{
		FarmingView v = view();
		FarmingView.PatchCardView card = herbCard(v, new HashMap<>(), T0);
		boolean included = card.box.isChecked();
		release(card.box, "ENTER"); // the press went to a popup menu item
		release(card.expand, "ENTER");
		assertEquals(included, card.box.isChecked());
		assertFalse(card.isExpanded());
		key(card.expand, "ENTER");
		assertTrue("a full press + release still works", card.isExpanded());
	}
}
