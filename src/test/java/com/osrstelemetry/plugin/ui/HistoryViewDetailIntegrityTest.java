package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.osrstelemetry.plugin.LootValuationMode;
import com.osrstelemetry.plugin.OsrsTelemetryConfig;
import com.osrstelemetry.plugin.history.HistoryEntry;
import com.osrstelemetry.plugin.session.Session;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Integrity audit (History detail): a detail result is shown only for the
 * session the player opened and is still looking at; the detail closes when
 * its session leaves the list (e.g. another account's list); a loaded detail
 * re-values itself when prices / GE-HA change.
 */
public class HistoryViewDetailIntegrityTest
{
	private static final Gson GSON = new Gson();
	private LootValuationMode mode = LootValuationMode.GRAND_EXCHANGE;
	private final List<String> opened = new ArrayList<>();
	private HistoryView view;
	private Session x;
	private Session y;

	private static Session finalized(String id, Instant at)
	{
		return GSON.fromJson(("{'sessionId':'" + id + "','activityIdentity':{'activityType':'SKILLING','activityKey':'mining','displayName':'Mining'},"
			+ "'state':'FINALIZED','startedAt':'" + at.minusSeconds(600) + "','lastActiveAt':'" + at.minusSeconds(60)
			+ "','finalizedAt':'" + at + "','accumulatedActiveDurationMillis':180000,'aggregates':{'xpGainedBySkill':{'MINING':100},"
			+ "'lootDrops':[{'sourceName':'Rocks','observedAt':'" + at.minusSeconds(120) + "','items':[{'itemId':440,'itemName':'Iron ore','quantity':3}]}]}}")
			.replace('\'', '"'), Session.class);
	}

	@Before
	public void setUp()
	{
		view = new HistoryView(null, null, null, new OsrsTelemetryConfig()
		{
			@Override
			public LootValuationMode lootValuationMode()
			{
				return mode;
			}
		}, opened::add);
		x = finalized("x", Instant.now().minusSeconds(100));
		y = finalized("y", Instant.now().minusSeconds(200));
		view.render(Arrays.asList(HistoryEntry.from(x), HistoryEntry.from(y)));
	}

	private Object field(String name) throws Exception
	{
		java.lang.reflect.Field f = HistoryView.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(view);
	}

	private Component detail() throws Exception
	{
		return (Component) field("detailView");
	}

	/** Clicks the row for the index-th entry, exactly like the player. */
	private void clickRow(int index) throws Exception
	{
		List<Component> rows = new ArrayList<>();
		for (Component c : ((Container) field("listContent")).getComponents())
		{
			for (MouseListener l : c.getMouseListeners())
			{
				if (l.getClass().getSimpleName().equals("ClickListener"))
				{
					rows.add(c);
				}
			}
		}
		for (MouseListener l : rows.get(index).getMouseListeners())
		{
			l.mouseClicked(null);
		}
	}

	private static Object lastModel(Object detailView) throws Exception
	{
		java.lang.reflect.Field f = HistoryDetailView.class.getDeclaredField("lastModel");
		f.setAccessible(true);
		return f.get(detailView);
	}

	@Test
	public void openedDetail_showsItsOwnResult() throws Exception
	{
		clickRow(0);
		assertTrue(opened.contains("x"));
		view.showDetail(x);
		assertTrue(detail().isVisible());
		assertTrue(lastModel(detail()) != null);
	}

	@Test
	public void lateResultForAnotherSession_isIgnored() throws Exception
	{
		clickRow(1); // opens y
		view.showDetail(x); // a stale load for x arrives
		assertNull("x never replaces the loading y", lastModel(detail()));
	}

	@Test
	public void lateResultAfterBack_doesNotPopTheDetailBackOpen() throws Exception
	{
		clickRow(0);
		java.lang.reflect.Method back = HistoryView.class.getDeclaredMethod("showList");
		back.setAccessible(true);
		back.invoke(view);
		view.showDetail(x);
		assertFalse(detail().isVisible());
		view.showDetailUnavailable();
		assertFalse(detail().isVisible());
	}

	@Test
	public void listWithoutTheOpenSession_closesTheDetail() throws Exception
	{
		clickRow(0);
		view.showDetail(x);
		assertTrue(detail().isVisible());
		// e.g. another account's list after a switch
		view.render(Collections.singletonList(HistoryEntry.from(finalized("other-account", Instant.now().minusSeconds(50)))));
		assertFalse(detail().isVisible());
		view.showDetail(x); // and a late x result cannot reopen it
		assertFalse(detail().isVisible());
	}

	@Test
	public void listStillContainingTheOpenSession_keepsTheDetail() throws Exception
	{
		clickRow(0);
		view.showDetail(x);
		view.render(Arrays.asList(HistoryEntry.from(finalized("newer", Instant.now().minusSeconds(10))), HistoryEntry.from(x), HistoryEntry.from(y)));
		assertTrue(detail().isVisible());
	}

	@Test
	public void geToHighAlch_reValuesTheOpenDetail_andNothingElseRedraws() throws Exception
	{
		clickRow(0);
		view.showDetail(x);
		java.lang.reflect.Field cf = HistoryDetailView.class.getDeclaredField("content");
		cf.setAccessible(true);
		Container content = (Container) cf.get(detail());
		Component before = content.getComponent(0);
		view.refreshDetailValues();
		assertSame("unchanged prices and mode: no redraw", before, content.getComponent(0));
		mode = LootValuationMode.HIGH_ALCH;
		view.refreshDetailValues();
		assertNotSame(before, content.getComponent(0));
	}
}
