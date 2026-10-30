package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEligibilityTest;
import com.osrstelemetry.plugin.history.HistoryEntry;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import org.junit.Before;
import org.junit.Test;

/** Pass 28: History lists reports; short records are counted out before drawing and stay one control away. */
public class HistoryShortRecordsTest
{
	private HistoryView view;

	@Before
	public void setUp()
	{
		view = new HistoryView(null, null, null, new OsrsTelemetryConfig()
		{
		}, id -> { });
		view.setSize(242, 2000);
	}

	private static HistoryEntry report(int i)
	{
		return HistoryEligibilityTest.entry("SLAYER", "bloodveld", 60 + i, "'slayerProgressDelta':4");
	}

	private static HistoryEntry fragment(int i)
	{
		return HistoryEligibilityTest.entry("COMBAT", "goblin", 5 + i % 20, "'xpGainedBySkill':{'HITPOINTS':4}");
	}

	private static void collect(Component c, String name, List<Component> into)
	{
		if (name.equals(c.getName()) && c.isVisible())
		{
			into.add(c);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collect(child, name, into);
			}
		}
	}

	private int rows()
	{
		List<Component> found = new ArrayList<>();
		collect(view, HistoryView.ROW_TITLE_NAME, found);
		return found.size();
	}

	private ActionButton toggle()
	{
		List<Component> found = new ArrayList<>();
		collect(view, HistoryView.SHORT_TOGGLE_NAME, found);
		return found.isEmpty() ? null : (ActionButton) found.get(0);
	}

	private boolean hasText(Component c, String text)
	{
		if (c instanceof JLabel && ((JLabel) c).getText() != null && ((JLabel) c).getText().contains(text))
		{
			return true;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				if (hasText(child, text))
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void shortRecords_areCountedOutBeforeDrawing_andOneControlAway()
	{
		List<HistoryEntry> entries = new ArrayList<>();
		for (int i = 0; i < 300; i++)
		{
			entries.add(i % 100 == 0 ? report(i) : fragment(i));
		}
		view.render(entries);
		assertEquals("only the 3 reports are drawn", 3, rows());
		ActionButton t = toggle();
		assertNotNull(t);
		assertEquals("Show 297 short records", t.getLabel());
		assertFalse(view.isShowingShortRecords());

		t.press();
		assertTrue(view.isShowingShortRecords());
		assertEquals(300, rows());
		assertEquals("Hide short records", toggle().getLabel());

		// A refresh with the same list keeps the choice and does not rebuild.
		Component first = toggle();
		view.render(new ArrayList<>(entries));
		assertTrue(first == toggle());

		toggle().press();
		assertEquals(3, rows());
	}

	@Test
	public void onlyShortRecords_saySoInsteadOfAnEmptyList()
	{
		view.render(Arrays.asList(fragment(1), fragment(2)));
		assertEquals(0, rows());
		assertTrue(hasText(view, HistoryView.NO_REPORTS_TEXT));
		assertEquals("Show 2 short records", toggle().getLabel());
		assertFalse(hasText(view, HistoryView.EMPTY_TITLE));
	}

	@Test
	public void noShortRecords_noControl_andEmptyStaysEmpty()
	{
		view.render(Arrays.asList(report(1), report(2)));
		assertEquals(2, rows());
		assertNull(toggle());
		view.render(Collections.emptyList());
		assertTrue(hasText(view, HistoryView.EMPTY_TITLE));
		assertNull(toggle());
		assertEquals("Show 1 short record", HistoryView.shortRecordsLabel(1, false));
	}

	@Test
	public void hiddenRecord_canStillBeOpenedByRoute()
	{
		HistoryEntry hidden = fragment(3);
		view.render(Arrays.asList(report(1), hidden));
		view.openDetail(hidden.getSessionId());
		assertEquals(hidden.getSessionId(), view.getOpenDetailSessionId());
		// The list refresh keeps it open: it is still a record of this account.
		view.render(Arrays.asList(report(1), hidden));
		assertEquals(hidden.getSessionId(), view.getOpenDetailSessionId());
	}
}
