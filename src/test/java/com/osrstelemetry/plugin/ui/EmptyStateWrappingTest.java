package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import java.awt.Component;
import java.awt.Container;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import org.junit.Test;

/**
 * The remaining sidebar empty/help-state labels (History, Loot tab, Recent
 * Activity) use the shared WrappingHtmlLabel instead of the old fixed CSS
 * width:180px HTML (really ~233px in Swing, which clipped). Real headless
 * Swing layout -- same approach as CurrentSessionViewTest.
 */
public class EmptyStateWrappingTest
{
	private static final int[] WIDTHS = {150, 180, 213, 260};

	private static final OsrsTelemetryConfig DEFAULT_CONFIG = new OsrsTelemetryConfig()
	{
	};

	private static WrappingHtmlLabel find(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof WrappingHtmlLabel)
			{
				return (WrappingHtmlLabel) c;
			}
			if (c instanceof Container)
			{
				WrappingHtmlLabel nested = find((Container) c);
				if (nested != null)
				{
					return nested;
				}
			}
		}
		return null;
	}

	private static void layoutTree(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layoutTree((Container) child);
			}
		}
	}

	/** Lays the view out at `width` (twice: second pass sees the real parent width) and returns its wrapping label. */
	private static WrappingHtmlLabel layOut(Container view, int width)
	{
		view.setSize(width, 800);
		layoutTree(view);
		layoutTree(view);
		WrappingHtmlLabel label = find(view);
		assertNotNull("empty state must use the shared WrappingHtmlLabel", label);
		return label;
	}

	private static void assertFitsInsideParent(String what, WrappingHtmlLabel label, int width)
	{
		Container parent = label.getParent();
		int innerLeft = parent.getInsets().left;
		int innerRight = parent.getWidth() - parent.getInsets().right;
		assertTrue(what + ": label must start inside its container at " + width, label.getX() >= innerLeft);
		assertTrue(what + ": label must end inside its container at " + width + " (x=" + label.getX()
			+ ", w=" + label.getWidth() + ", inner right=" + innerRight + ")", label.getX() + label.getWidth() <= innerRight);

		View html = (View) label.getClientProperty(BasicHTML.propertyKey);
		html.setSize(label.getWidth(), 0);
		assertTrue(what + ": wrapped text must fit the label width at " + width,
			html.getMinimumSpan(View.X_AXIS) <= label.getWidth());
		assertTrue(what + ": label height must hold the wrapped text at " + width,
			label.getHeight() >= (int) html.getPreferredSpan(View.Y_AXIS));
		assertFalse(what + ": no fixed CSS width may remain in the label HTML", label.getText().contains("width:"));
	}

	private static void assertWrapsWhenNarrow(String what, Supplier<Container> viewFactory, int narrowWidth)
	{
		WrappingHtmlLabel label = layOut(viewFactory.get(), narrowWidth);
		JLabel oneLine = new JLabel("x");
		oneLine.setFont(label.getFont());
		assertTrue(what + ": text must wrap onto more lines (taller) at " + narrowWidth + "px",
			label.getHeight() > oneLine.getPreferredSize().height + label.getInsets().top + label.getInsets().bottom);
	}

	// ------------------------------------------------------------------ History

	private static Container historyEmpty()
	{
		return new HistoryView(null, null, null, DEFAULT_CONFIG, id -> { });
	}

	@Test
	public void historyEmptyState_staysInsideSidebar_atRepresentativeWidths()
	{
		for (int width : WIDTHS)
		{
			assertFitsInsideParent("History", layOut(historyEmpty(), width), width);
		}
	}

	@Test
	public void historyEmptyState_wrapsWhenNarrow()
	{
		assertWrapsWhenNarrow("History", EmptyStateWrappingTest::historyEmpty, 150);
	}

	// ------------------------------------------------------------------ Loot tab

	/** Hosts the Loot tab's real empty-state label exactly the way render() does: in `content` (BoxLayout Y, 6px border). */
	private static Container lootEmpty(String message)
	{
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		content.add(LootTrackerView.emptyStateLabel(message));
		return content;
	}

	@Test
	public void lootTabEmptyStates_stayInsideSidebar_atRepresentativeWidths()
	{
		for (String message : new String[] {"No loot recorded yet.", "No favorited sources yet.", "No items match your search."})
		{
			for (int width : WIDTHS)
			{
				WrappingHtmlLabel label = layOut(lootEmpty(message), width);
				assertFitsInsideParent("Loot tab '" + message + "'", label, width);
				assertTrue("Loot tab keeps its 16px top padding", label.getInsets().top == 16);
			}
		}
	}

	@Test
	public void lootTabEmptyState_wrapsWhenNarrow()
	{
		assertWrapsWhenNarrow("Loot tab", () -> lootEmpty("No items match your search."), 100);
	}

	// ------------------------------------------------------------------ Recent Activity

	@Test
	public void recentActivityPlaceholder_staysInsideSidebar_atRepresentativeWidths()
	{
		for (int width : WIDTHS)
		{
			assertFitsInsideParent("Recent Activity", layOut(new RecentActivityView(), width), width);
		}
	}

	@Test
	public void recentActivityPlaceholder_wrapsWhenNarrow()
	{
		assertWrapsWhenNarrow("Recent Activity", RecentActivityView::new, 100);
	}
}
