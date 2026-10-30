package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingPatchType;
import com.osrstelemetry.plugin.farming.FarmingPlanDraft;
import com.osrstelemetry.plugin.farming.FarmingPreferences;
import com.osrstelemetry.plugin.farming.FarmingRunType;
import org.junit.Test;

/** Pass 31: the compost choices in run setup (single and combined) and on the bins page share one saved model. */
public class CompostEditorTest
{
	@Test
	public void runSetupShowsThePerTypeChoices_andAChoiceIsSaved()
	{
		FarmingPreferences[] saved = {FarmingPreferences.defaults()};
		FarmingView v = new FarmingView();
		v.attachPreferences(() -> saved[0], p -> saved[0] = p);
		v.setSize(242, 3000);
		v.render(FarmingCategoryGridTest.model(FarmingCategoryGridTest.mixed(FarmingCategoryGridTest.T0), FarmingCategoryGridTest.T0));
		v.select(FarmingRunType.ALLOTMENT);
		assertNotNull("allotments", RecurringPagesTest.named(v, "compost.ALLOTMENT"));
		assertNotNull("and flowers, apart", RecurringPagesTest.named(v, "compost.FLOWER"));
		v.select(FarmingRunType.HERB);
		String t = RecurringPagesTest.text(v);
		assertTrue(t, t.contains("COMPOST ON PATCHES") && t.contains("Herbs · No compost"));
		v.chooseCompost(FarmingPatchType.HERB, FarmingPlanDraft.Compost.ULTRACOMPOST);
		assertEquals(FarmingPlanDraft.Compost.ULTRACOMPOST, saved[0].compostFor(FarmingPatchType.HERB));
		t = RecurringPagesTest.text(v);
		assertTrue(t, t.contains("Herbs · Use Ultracompost (bucket)"));
		assertTrue(t, t.contains("Last observed:"));
		// Combined: the same saved choice shows for its types.
		v.showOverview();
		v.toggleCombine();
		v.include(FarmingRunType.HERB, true);
		t = RecurringPagesTest.text(v);
		assertTrue(t, t.contains("Herbs · Use Ultracompost (bucket)"));
		// The bins page edits the same model.
		v.showOverview();
		v.select(FarmingRunType.COMPOST);
		assertTrue(RecurringPagesTest.text(v).contains("Herbs · Use Ultracompost (bucket)"));
	}
}
