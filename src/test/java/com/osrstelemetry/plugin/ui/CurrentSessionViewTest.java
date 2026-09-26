package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.CurrentSessionSectionOrder;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import org.junit.Test;

/**
 * Current Session UI/config regressions: the empty-state text clipping fix
 * (real, headless Swing layout -- no RuneLite client needed, since the empty
 * state touches no ItemManager/SkillIconManager) and the XP / Loot order
 * setting.
 */
public class CurrentSessionViewTest
{
	private static final String EMPTY_TEXT = "Start playing normally and activity will appear here.";

	private static final OsrsTelemetryConfig DEFAULT_CONFIG = new OsrsTelemetryConfig()
	{
	};

	// ------------------------------------------------------------------
	// XP / Loot order
	// ------------------------------------------------------------------

	@Test
	public void sectionOrder_configDefault_isXpFirst_theExistingProductionOrder()
	{
		assertEquals(CurrentSessionSectionOrder.XP_FIRST, DEFAULT_CONFIG.currentSessionSectionOrder());
		assertEquals(Arrays.asList(CurrentSessionView.Section.XP, CurrentSessionView.Section.LOOT),
			CurrentSessionView.orderedSections(DEFAULT_CONFIG.currentSessionSectionOrder()));
	}

	@Test
	public void sectionOrder_xpFirst_placesXpBeforeLoot()
	{
		assertEquals(Arrays.asList(CurrentSessionView.Section.XP, CurrentSessionView.Section.LOOT),
			CurrentSessionView.orderedSections(CurrentSessionSectionOrder.XP_FIRST));
	}

	@Test
	public void sectionOrder_lootFirst_placesLootBeforeXp()
	{
		assertEquals(Arrays.asList(CurrentSessionView.Section.LOOT, CurrentSessionView.Section.XP),
			CurrentSessionView.orderedSections(CurrentSessionSectionOrder.LOOT_FIRST));
	}

	@Test
	public void sectionOrder_eachSectionRenderedExactlyOnce_nullFallsBackToProductionOrder()
	{
		for (CurrentSessionSectionOrder order : CurrentSessionSectionOrder.values())
		{
			assertEquals("both sections, each exactly once, for " + order, 2,
				CurrentSessionView.orderedSections(order).stream().distinct().count());
		}
		assertEquals(Arrays.asList(CurrentSessionView.Section.XP, CurrentSessionView.Section.LOOT),
			CurrentSessionView.orderedSections(null));
	}

	private static java.util.List<String> plan(CurrentSessionSectionOrder order, boolean hasXp, boolean hasLoot)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		CurrentSessionView.renderSections(CurrentSessionView.orderedSections(order),
			section -> section == CurrentSessionView.Section.XP ? hasXp : hasLoot,
			section -> out.add(section.name()),
			() -> out.add("GAP"));
		return out;
	}

	@Test
	public void sectionGap_exactlyOneBetweenSections_inBothOrders()
	{
		assertEquals(Arrays.asList("XP", "GAP", "LOOT"), plan(CurrentSessionSectionOrder.XP_FIRST, true, true));
		assertEquals(Arrays.asList("LOOT", "GAP", "XP"), plan(CurrentSessionSectionOrder.LOOT_FIRST, true, true));
		assertEquals(6, CurrentSessionView.SECTION_GAP);
	}

	@Test
	public void sectionGap_neverAddedWhenOnlyOneOrNoSectionRenders()
	{
		for (CurrentSessionSectionOrder order : CurrentSessionSectionOrder.values())
		{
			assertEquals(Arrays.asList("XP"), plan(order, true, false));
			assertEquals(Arrays.asList("LOOT"), plan(order, false, true));
			assertEquals(java.util.Collections.emptyList(), plan(order, false, false));
		}
	}

	@Test
	public void currentSessionLoot_stillUsesTheShared5ColumnGrid()
	{
		assertEquals(LootGridCell.GRID_COLUMNS, CurrentSessionView.LOOT_ITEMS_PER_ROW);
		assertEquals(5, LootGridCell.GRID_COLUMNS);
	}

	// ------------------------------------------------------------------
	// Empty-state clipping
	// ------------------------------------------------------------------

	/** Documents the root cause: Swing HTML scales CSS px by 1.3, so the old 180px div was ~233px wide. */
	@Test
	public void rootCause_oldFixedCssWidthLabel_isWiderThanTheSidebarContent()
	{
		JLabel old = new JLabel("<html><div style='text-align:center;width:180px;'>" + EMPTY_TEXT + "</div></html>");
		assertTrue("old label preferred width " + old.getPreferredSize().width + " exceeds the ~201px sidebar content width",
			old.getPreferredSize().width > 201);
	}

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

	@Test
	public void emptyState_textStaysInsideTheCardAndWraps_atRepresentativeSidebarWidths()
	{
		// 213 = RuneLite PluginPanel width (225) minus its 6px+6px padding;
		// plus narrower/wider cases -- behavior depends only on component width.
		for (int width : new int[] {150, 180, 213, 260})
		{
			CurrentSessionView view = new CurrentSessionView(null, null, null, DEFAULT_CONFIG, null);
			view.setSize(width, 600);
			layoutTree(view);
			layoutTree(view); // second pass: label height recomputed at the now-known width

			WrappingHtmlLabel label = find(view);
			assertNotNull("empty state must use the width-tracking label", label);
			Container content = label.getParent();
			int innerLeft = content.getInsets().left;
			int innerRight = content.getWidth() - content.getInsets().right;

			assertTrue("label must start inside the card at width " + width, label.getX() >= innerLeft);
			assertTrue("label must end inside the card at width " + width + " (x=" + label.getX()
				+ ", w=" + label.getWidth() + ", inner right=" + innerRight + ")", label.getX() + label.getWidth() <= innerRight);

			View html = (View) label.getClientProperty(BasicHTML.propertyKey);
			html.setSize(label.getWidth(), 0);
			assertTrue("wrapped text must fit the label's width at " + width,
				html.getMinimumSpan(View.X_AXIS) <= label.getWidth());
			assertTrue("label height must hold the wrapped text at " + width,
				label.getHeight() >= (int) html.getPreferredSpan(View.Y_AXIS));
		}
	}

	@Test
	public void emptyState_wrapsOntoMultipleLines_whenNarrow()
	{
		CurrentSessionView view = new CurrentSessionView(null, null, null, DEFAULT_CONFIG, null);
		view.setSize(150, 600);
		layoutTree(view);
		layoutTree(view);
		WrappingHtmlLabel label = find(view);
		JLabel oneLine = new JLabel(EMPTY_TEXT);
		oneLine.setFont(label.getFont());
		assertTrue("at 150px the message must wrap rather than overflow",
			label.getHeight() > oneLine.getPreferredSize().height);
	}
}
