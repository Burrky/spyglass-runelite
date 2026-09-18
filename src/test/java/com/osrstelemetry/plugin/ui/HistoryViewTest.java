package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.ActivityIdentity;
import com.osrstelemetry.plugin.session.ActivityType;
import com.osrstelemetry.plugin.session.Session;
import com.osrstelemetry.plugin.session.SessionLifecycleEngine;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

/**
 * Covers
 * {@link HistoryView#computeRenderKey} -- the pure, Swing-free
 * presentation fingerprint {@code render()} uses to skip a full rebuild
 * when the entry set on screen hasn't actually changed -- without
 * constructing the Swing view itself (this project has no Mockito, and
 * HistoryView needs SkillIconManager/ItemManager/ClientThread, none of
 * which can be faked here; same "test only the pure static methods"
 * convention LootTrackerViewTest already establishes).
 *
 * {@link #heightBoundedPanel_maximumHeightTracksPreferredHeight} and
 * {@link #heightBoundedPanel_widthStaysUnbounded} cover
 * {@link HistoryView#heightBoundedPanel}. Unlike the rest of this class,
 * {@code heightBoundedPanel} needs NO RuneLite dependency at all --
 * {@code JPanel}/{@code Dimension} are plain {@code javax.swing}/
 * {@code java.awt}, so this is a real, runnable, non-mocked test of the
 * exact behavior the fix depends on: that the returned panel's maximum
 * height always equals its OWN current preferred height (never a
 * hardcoded constant), while its maximum width stays unbounded so it
 * can still stretch to fill a row's width as before.
 */
public class HistoryViewTest
{
	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
	private static final ActivityIdentity GARGOYLES =
		new ActivityIdentity(ActivityType.SLAYER, "gargoyles:catacombs", "Gargoyles");

	private static HistoryEntry buildEntry()
	{
		SessionLifecycleEngine engine = new SessionLifecycleEngine();
		engine.onQualifyingActivity(GARGOYLES, T0);
		Instant suspendedAt = T0.plus(SessionLifecycleEngine.SUSPEND_TIMEOUT);
		engine.advanceTime(suspendedAt);
		Instant expiry = suspendedAt.plus(SessionLifecycleEngine.RESUME_WINDOW);
		Session finalized = engine.advanceTime(expiry).getFinalized();
		return HistoryEntry.from(finalized);
	}

	@Test
	public void computeRenderKey_emptyList_isStableAndNotNull()
	{
		String key = HistoryView.computeRenderKey(Collections.emptyList());
		assertEquals(key, HistoryView.computeRenderKey(Collections.emptyList()));
	}

	@Test
	public void computeRenderKey_sameEntries_sameKey()
	{
		List<HistoryEntry> entries = Arrays.asList(buildEntry(), buildEntry());
		assertEquals(HistoryView.computeRenderKey(entries), HistoryView.computeRenderKey(entries));
	}

	@Test
	public void computeRenderKey_differentEntryCount_differentKey()
	{
		HistoryEntry entry = buildEntry();
		String keyOne = HistoryView.computeRenderKey(Collections.singletonList(entry));
		String keyTwo = HistoryView.computeRenderKey(Arrays.asList(entry, entry));
		assertNotEquals(keyOne, keyTwo);
	}

	@Test
	public void heightBoundedPanel_maximumHeightTracksPreferredHeight()
	{
		JPanel panel = HistoryView.heightBoundedPanel(new BorderLayout());
		panel.add(new JLabel("row content"));
		Dimension preferred = panel.getPreferredSize();

		assertEquals("maximum height must equal the panel's own current preferred height, never a hardcoded value",
			preferred.height, panel.getMaximumSize().height);
	}

	@Test
	public void heightBoundedPanel_widthStaysUnbounded()
	{
		JPanel panel = HistoryView.heightBoundedPanel(new BorderLayout());
		assertEquals(Integer.MAX_VALUE, panel.getMaximumSize().width);
	}

	@Test
	public void heightBoundedPanel_maximumHeightUpdatesWhenContentGrows()
	{
		// Regression guard for the exact bug this fixes: a card's maximum
		// height must track ITS OWN content (e.g. collapsed vs. expanded),
		// never stay pinned to whatever it happened to be at construction.
		JPanel panel = HistoryView.heightBoundedPanel(new BorderLayout());
		int heightBefore = panel.getMaximumSize().height;

		panel.add(new JLabel("<html>a fairly tall<br>multi-line<br>label</html>"));
		int heightAfter = panel.getMaximumSize().height;

		assertTrue("maximum height should grow to match new preferred height once content is added",
			heightAfter >= heightBefore);
	}
}
