package com.osrstelemetry.plugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Player-clicked bank filter for a run's preparation (Farming correction pass,
 * 2026-10-16; supersedes the passive-only Find decision for this one,
 * deliberate action). While a run is preparing and the bank is open, a "Run
 * items" button sits in the bank's title row (and the run page has the same
 * toggle). Clicking it shows only the bank items that are acceptable for the
 * run's still-unsatisfied needs; clicking again, or clicking any bank tab, View
 * all, Search, a tag tab or the potion store, or closing the bank, returns the
 * normal view.
 *
 * <p>How: the same supported client-script callbacks RuneLite's own Bank Tags
 * plugin uses to show a tag tab ({@code getSearchingTagTab},
 * {@code bankSearchFilter}, {@code bankBuildTab}), plus a re-layout of the
 * bank's own item container ({@code onInvTransmit}). Nothing is typed into the
 * search box, no varbit/varp/varc is written, no menu action is sent and no
 * item is withdrawn, deposited, equipped or moved. No other plugin is needed:
 * when Bank Tags is enabled our callbacks run after its (lower priority), and
 * we step aside whenever the player opens a tag tab or types a search.
 */
@Slf4j
@Singleton
public class BankRunFilter
{
	static final String SHOW = "Show run items";
	static final String HIDE = "Show all items";
	private static final int ORANGE = 0xFF981F;

	/** The pure state machine (tested without a client). */
	static final class State
	{
		private boolean bankOpen;
		private boolean active;
		private Set<Integer> items = Collections.emptySet();

		/** Updates the item set; true when the filter is active and the set changed (the bank must re-lay out). */
		boolean setItems(Set<Integer> next)
		{
			Set<Integer> n = next == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(next));
			boolean changed = !n.equals(items);
			items = n;
			return active && changed;
		}

		/** The player clicked the button: returns the new active state. Refused when the bank is closed, in the potion store, or nothing is left. */
		boolean toggle(boolean potionStore)
		{
			if (active)
			{
				active = false;
			}
			else if (bankOpen && !potionStore && !items.isEmpty())
			{
				active = true;
			}
			return active;
		}

		/** Any other bank view chosen, the bank closed, the run ended: back to normal. True when it was active. */
		boolean deactivate()
		{
			boolean was = active;
			active = false;
			return was;
		}

		void bankOpen(boolean open)
		{
			bankOpen = open;
			if (!open)
			{
				active = false;
			}
		}

		boolean isActive()
		{
			return active;
		}

		boolean isAvailable()
		{
			return bankOpen && !items.isEmpty();
		}

		boolean matches(int canonicalItemId)
		{
			return items.contains(canonicalItemId);
		}

		Set<Integer> items()
		{
			return items;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final State state = new State();
	private Supplier<Set<Integer>> source = Collections::emptySet;
	private IntUnaryOperator canonical = IntUnaryOperator.identity();
	private Widget button;

	@Inject
	public BankRunFilter(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** The run's still-needed item alternatives (canonical ids), and how to canonicalise a bank slot's id (placeholders). */
	public void attach(Supplier<Set<Integer>> items, IntUnaryOperator canonicalise)
	{
		this.source = items == null ? Collections::emptySet : items;
		this.canonical = canonicalise == null ? IntUnaryOperator.identity() : canonicalise;
	}

	/** For the sidebar (any thread). */
	public boolean isActive()
	{
		return state.isActive();
	}

	/** For the sidebar (any thread): the bank is open and something is still needed. */
	public boolean isAvailable()
	{
		return state.isAvailable();
	}

	/** The player's click, from the sidebar (any thread) or the in-bank button. */
	public void toggle()
	{
		clientThread.invoke(this::toggleOnClientThread);
	}

	/** Plugin start/stop: back to normal; a filtered bank is re-laid out and the button hidden (our callbacks are unregistered by then). */
	public void reset()
	{
		boolean was = state.deactivate();
		state.setItems(Collections.emptySet());
		state.bankOpen(false);
		Widget b = button;
		Widget bg = background;
		Widget fr = frame;
		button = null;
		background = null;
		frame = null;
		if (was || b != null)
		{
			clientThread.invoke(() ->
			{
				for (Widget w : new Widget[]{b, bg, fr})
				{
					if (w != null)
					{
						w.setHidden(true);
					}
				}
				if (was)
				{
					relayout();
				}
			});
		}
	}

	private void toggleOnClientThread()
	{
		boolean potionStore = client.getVarbitValue(VarbitID.BANK_CURRENTTAB) == 15;
		boolean was = state.isActive();
		boolean now = state.toggle(potionStore);
		if (was != now)
		{
			relayout();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Widget items = client.getGameState() == GameState.LOGGED_IN ? client.getWidget(InterfaceID.Bankmain.ITEMS) : null;
		boolean open = items != null && !items.isHidden();
		boolean wasActive = state.isActive();
		state.bankOpen(open);
		Set<Integer> next = source.get();
		boolean changed = state.setItems(next);
		if (state.isActive() && next.isEmpty())
		{
			state.deactivate(); // everything carried: nothing left to show
			changed = true;
		}
		if (open && (changed || wasActive != state.isActive()))
		{
			relayout();
		}
		if (open && button != null)
		{
			Widget universe = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
			if (universe != null)
			{
				position(universe); // the title (a value, a tab name) and the window size can change between builds
			}
		}
		updateButton();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			button = null; // a fresh bank interface: the old children are gone
			background = null;
			frame = null;
			placed = null;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			state.bankOpen(false);
			button = null;
			background = null;
			frame = null;
			placed = null;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (!state.isActive())
		{
			return;
		}
		int p1 = e.getParam1();
		if (button != null && e.getWidget() == button)
		{
			return;
		}
		String option = e.getMenuOption() == null ? "" : e.getMenuOption().toLowerCase();
		if (p1 == InterfaceID.Bankmain.TABS || p1 == InterfaceID.Bankmain.SEARCH || p1 == InterfaceID.Bankmain.POTIONSTORE_BUTTON
			|| (p1 == InterfaceID.Bankmain.ITEMS_CONTAINER && option.contains("tab")) || option.startsWith("view"))
		{
			state.deactivate(); // the player chose another view; the game (or Bank Tags) lays out that view itself
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired e)
	{
		if (e.getScriptId() == ScriptID.BANKMAIN_SEARCH_TOGGLE)
		{
			state.deactivate(); // Search pressed (button or keybind)
		}
		else if (e.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			ensureButton();
			if (state.isActive())
			{
				Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
				if (title != null)
				{
					title.setText("Run items <col=ff981f>(" + state.items().size() + " kinds)</col>");
				}
			}
		}
	}

	/** After the bank (and other plugins) set the title for this build: place the icon against the final title. */
	@Subscribe(priority = -2)
	public void onScriptPostFired(net.runelite.api.events.ScriptPostFired e)
	{
		if (e.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING && button != null)
		{
			Widget universe = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
			if (universe != null)
			{
				position(universe);
				updateButton();
			}
		}
	}

	@Subscribe(priority = -1) // after Bank Tags, so an open tag tab or a typed search keeps its own result unless we are active
	public void onScriptCallbackEvent(ScriptCallbackEvent e)
	{
		if (!state.isActive())
		{
			return;
		}
		int[] intStack = client.getIntStack();
		int intSize = client.getIntStackSize();
		Object[] objectStack = client.getObjectStack();
		int objectSize = client.getObjectStackSize();
		switch (e.getEventName())
		{
			case "getSearchingTagTab":
				intStack[intSize - 1] = 1;
				break;
			case "bankBuildTab":
				intStack[intSize - 1] = 1; // single view: no tab separators between the filtered items
				break;
			case "bankSearchFilter":
			{
				String typed = objectSize > 0 && objectStack[objectSize - 1] instanceof String ? (String) objectStack[objectSize - 1] : "";
				if (!typed.isEmpty())
				{
					state.deactivate(); // the player is searching: their search wins
					return;
				}
				int itemId = intStack[intSize - 1];
				intStack[intSize - 2] = itemId > -1 && state.matches(canonical.applyAsInt(itemId)) ? 1 : 0;
				break;
			}
			default:
				break;
		}
	}

	/** Re-runs the bank's own layout script (RuneLite's BankSearch.layoutBank does the same). Client thread. */
	private void relayout()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}
		Object[] args = items.getOnInvTransmitListener();
		if (args != null)
		{
			client.runScript(args);
		}
	}

	/** Farming skill icon (SpriteID.Staticons2.FARMING, gameval 1.13.1). */
	static final int FARMING_ICON = 217;
	/** The icon's largest size; in the title bar it is the bar's height less a small margin. */
	static final int ICON = 20;
	static final int MIN_ICON = 12;
	/** Space kept between the icon and the title text or another control. */
	static final int GAP = 4;
	private Widget frame;
	/** Where the icon sits (canvas), or null when the title bar has no free room: then it is hidden, never over the items. */
	private java.awt.Rectangle placed;

	/**
	 * The Farming icon inside the bank's top title bar, in the free space left of the title text (else right of it), clear
	 * of the close button and any other control there, ours or another plugin's; with a gold frame while the filter is on.
	 * Its hover text names the action ("Show run items" / "Show all items"). Placed again whenever the bank lays itself out
	 * or its title changes, so moving, resizing and UI scaling keep it in the bar.
	 */
	private void ensureButton()
	{
		Widget universe = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (universe == null)
		{
			return;
		}
		if (button == null)
		{
			button = universe.createChild(-1, WidgetType.GRAPHIC);
			button.setSpriteId(FARMING_ICON);
			button.setHasListener(true);
			button.setNoClickThrough(true);
			button.setOnOpListener((JavaScriptCallback) ev -> toggleOnClientThread());
			button.setName("<col=ff9040>Spyglass</col>");
			frame = universe.createChild(-1, WidgetType.RECTANGLE);
			frame.setFilled(false);
			frame.setTextColor(ORANGE);
		}
		position(universe);
		updateButton();
	}

	private Widget background;

	/** Places the icon in the title bar's free room (client thread). */
	private void position(Widget universe)
	{
		Point origin = universe.getCanvasLocation();
		Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		java.awt.Rectangle bar = title == null || title.isHidden() || origin == null ? null : title.getBounds();
		java.awt.Rectangle slot = null;
		if (bar != null && bar.height > 0)
		{
			java.util.List<java.awt.Rectangle> taken = new java.util.ArrayList<>();
			int[] text = textSpan(title, bar);
			if (text != null)
			{
				taken.add(new java.awt.Rectangle(text[0], bar.y, text[1] - text[0], bar.height));
			}
			collect(universe.getStaticChildren(), bar, taken, 1);
			collect(universe.getDynamicChildren(), bar, taken, 0);
			Widget steel = client.getWidget(InterfaceID.Bankmain.FRAME);
			if (steel != null)
			{
				collect(steel.getDynamicChildren(), bar, taken, 0);
				collect(steel.getStaticChildren(), bar, taken, 0);
			}
			slot = slot(bar, taken, Math.max(MIN_ICON, Math.min(ICON, bar.height - 2)));
		}
		placed = slot;
		if (slot == null)
		{
			return; // no room in the bar: hidden (updateButton), never placed over the items
		}
		int x = slot.x - origin.getX();
		int y = slot.y - origin.getY();
		if (button.getOriginalX() != x || button.getOriginalY() != y || button.getOriginalWidth() != slot.width)
		{
			button.setOriginalX(x);
			button.setOriginalY(y);
			button.setOriginalWidth(slot.width);
			button.setOriginalHeight(slot.height);
			button.revalidate();
			frame.setOriginalX(x - 1);
			frame.setOriginalY(y - 1);
			frame.setOriginalWidth(slot.width + 2);
			frame.setOriginalHeight(slot.height + 2);
			frame.revalidate();
		}
	}

	/** The title text's horizontal extent on the canvas (its alignment within the title widget), or null when empty. */
	private static int[] textSpan(Widget title, java.awt.Rectangle bar)
	{
		String t = title.getText() == null ? "" : title.getText().replaceAll("<[^>]*>", "");
		net.runelite.api.FontTypeFace f = title.getFont();
		if (t.isEmpty() || f == null)
		{
			return null;
		}
		int w = f.getTextWidth(t);
		int align = title.getXTextAlignment();
		int x0 = align == 0 ? bar.x : align == 2 ? bar.x + bar.width - w : bar.x + (bar.width - w) / 2;
		return new int[]{x0, x0 + w};
	}

	/** Visible controls overlapping the bar (not the bar itself, a backdrop or a frame line spanning it, nor ours). */
	private void collect(Widget[] ws, java.awt.Rectangle bar, java.util.List<java.awt.Rectangle> taken, int depth)
	{
		if (ws == null)
		{
			return;
		}
		for (Widget w : ws)
		{
			if (w == null || w.isHidden() || w == button || w == frame || w == background)
			{
				continue;
			}
			java.awt.Rectangle b = w.getBounds();
			if (b != null && b.width > 0 && b.height > 0 && b.intersects(bar) && b.width < bar.width * 6 / 10 && w.getId() != InterfaceID.Bankmain.TITLE)
			{
				taken.add(b);
			}
			if (depth > 0)
			{
				collect(w.getDynamicChildren(), bar, taken, depth - 1);
			}
		}
	}

	/**
	 * The first free square of this size in the bar: from its left edge towards the title text, else from its right edge
	 * back towards it, clear of everything taken by {@link #GAP}; null when there is none (canvas coordinates).
	 */
	static java.awt.Rectangle slot(java.awt.Rectangle bar, java.util.List<java.awt.Rectangle> taken, int size)
	{
		int y = bar.y + (bar.height - size) / 2;
		for (int pass = 0; pass < 2; pass++)
		{
			int from = pass == 0 ? bar.x + GAP + 2 : bar.x + bar.width - GAP - 2 - size;
			int step = pass == 0 ? 1 : -1;
			for (int x = from; x >= bar.x && x + size <= bar.x + bar.width; x += step)
			{
				java.awt.Rectangle r = new java.awt.Rectangle(x - GAP, y, size + 2 * GAP, size);
				boolean free = true;
				for (java.awt.Rectangle t : taken)
				{
					if (t.intersects(r))
					{
						free = false;
						break;
					}
				}
				if (free)
				{
					return new java.awt.Rectangle(x, y, size, size);
				}
			}
		}
		return null;
	}

	private void updateButton()
	{
		if (button == null)
		{
			return;
		}
		boolean show = (state.isActive() || state.isAvailable()) && placed != null;
		button.setHidden(!show);
		if (frame != null)
		{
			frame.setHidden(!show || !state.isActive()); // the visible "on" state
		}
		button.setAction(0, state.isActive() ? HIDE : SHOW);
		button.setOpacity(state.isActive() ? 0 : 60);
	}
}
