package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.farming.FarmingModel;
import com.osrstelemetry.plugin.farming.FarmingOwner;
import com.osrstelemetry.plugin.farming.FarmingPlanDraft;
import com.osrstelemetry.plugin.farming.FarmingProduce;
import com.osrstelemetry.plugin.farming.FarmingRunRequest;
import com.osrstelemetry.plugin.farming.FarmingRunStore;
import com.osrstelemetry.plugin.farming.FarmingRunType;
import com.osrstelemetry.plugin.farming.FarmingRunView;
import com.osrstelemetry.plugin.farming.FarmingSnapshot;
import com.osrstelemetry.plugin.farming.FarmingTutorial;
import net.runelite.api.gameval.ItemID;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import com.osrstelemetry.plugin.farming.FarmingPreferences;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Home → Farming: Browse and Preview (canonical §2-§3), driven entirely by an
 * immutable {@link FarmingModel}.
 *
 * <ul>
 *   <li><b>Run-type grid</b>: Herb / Tree / Fruit, Bush / Hops / Allot., and
 *   Special ▸ revealing the less common types. One type is selected at a time
 *   and stays pressed in; pressing it again does nothing; "× overview" clears
 *   it. The selection is a hidden navigation preference that survives a client
 *   restart (an invalid saved value falls back to the overview).</li>
 *   <li><b>Overview</b> (nothing selected): one line per observed type, then
 *   the never-observed types grouped on one muted line.</li>
 *   <li><b>Preview</b>: one small raised card per patch (crop sprite, location,
 *   status, provenance; include box; a chevron that expands the planned-crop
 *   selector), light run options and the scope summary. Choices live in a
 *   session-only {@link FarmingPlanDraft} and never touch what was observed;
 *   the sprite always shows the observed crop, never the plan.</li>
 * </ul>
 *
 * <b>Start Run</b> (Phase E) sits under the preview: it hands the current
 * draft to {@link RunActions#start}, which validates it and persists a run
 * intent. Browsing, selecting, including and choosing crops never start
 * anything. While a run exists the page shows {@link FarmingRunPage} instead
 * (run-type buttons hidden); "Browse patches" opens the normal Browse with a
 * banner, and the Start button is replaced by "Return to run". There is no
 * route, transport, preparation or guidance in this phase.
 *
 * The content is rebuilt only when its structure changes (selection, state,
 * rows, expanded sections); otherwise each render just updates text in place,
 * so the 1 s refresh never rebuilds the page.
 */
final class FarmingView extends JPanel
{
	static final String OVERVIEW_HEADER = "WHAT'S GROWING";
	static final String HINT = "Pick a run type to see its patches.";
	static final String BACK_TO_OVERVIEW = "◂ Overview";
	private static final int GAP = 2;
	/** Recessed canvas between two cards, on top of each card's own 2 px stepped shadow. */
	static final int CARD_GAP = 2;

	private final Map<FarmingRunType, RunTypeButton> buttons = new EnumMap<>(FarmingRunType.class);
	private final RunTypeButton specialToggle;
	/** The less common types, consolidated into two labelled groups (Trees; Other crops). */
	private final JPanel specialGrid = new JPanel();
	private final JPanel content = new JPanel();
	private final FarmingPlanDraft draft = new FarmingPlanDraft();
	private final Map<String, PatchCardView> rowViews = new LinkedHashMap<>();
	/** Cards whose planning section is open (view state only: survives refreshes, never touches the draft). */
	private final Set<String> expanded = new HashSet<>();
	private final SpriteSource sprites;
	private final JPanel controls = new JPanel();
	private final FarmingRunPage runPage;
	private RunActions runActions;
	private java.util.function.Supplier<com.osrstelemetry.plugin.farming.FarmingPreparationView> preparationSource;
	private java.util.function.Consumer<int[]> findFocus;
	/** Browse opened from a run page ("Browse patches"): read-only inspection with a Return banner. */
	private boolean browsingDuringRun;
	private boolean endConfirm;
	private List<String> startProblems = new ArrayList<>();
	private ActionButton startButton;
	/** The same Start Run at the top of the selection list: one action, one selection, one enabled state. */
	private ActionButton startTopButton;
	/** Pass 33: Start combined run in the shared controls, under Combine (same action and state as the button below). */
	private ActionButton combineStart;
	private Component combineStartGap;
	/** A start is being handled: a second press (either button) does nothing. */
	private boolean starting;
	private WrappingHtmlLabel startHint;

	private FarmingRunType selected;
	private boolean specialsOpen;
	private boolean optionsOpen;
	private FarmingModel model;
	private FarmingOwner draftOwner;
	private String structureKey;
	private Consumer<String> selectionWriter;
	private JLabel summaryLabel;
	private JLabel untrackedLabel;
	private JLabel scopeLabel;

	/** No sprites (tests): every card still reserves its icon space. */
	FarmingView()
	{
		this((SpriteSource) null);
	}

	/** Sprites through RuneLite's ItemManager (asynchronous; repaint once loaded). */
	FarmingView(ItemManager itemManager)
	{
		this(itemManager == null ? null : (SpriteSource) (itemId, onLoaded) ->
		{
			AsyncBufferedImage image = itemManager.getImage(itemId);
			image.onLoaded(onLoaded);
			return image;
		});
	}

	FarmingView(SpriteSource sprites)
	{
		this.sprites = sprites;
		this.runPage = new FarmingRunPage(sprites, new RunPageActions());
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(SpyglassTheme.TILE_CANVAS);
		setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));

		JPanel mainGrid = new JPanel(new ReflowGridLayout(2, GAP, GAP));
		mainGrid.setOpaque(false);
		specialGrid.setOpaque(false);
		specialGrid.setLayout(new BoxLayout(specialGrid, BoxLayout.Y_AXIS));
		JPanel treeGrid = new JPanel(new ReflowGridLayout(2, GAP, GAP));
		JPanel otherGrid = new JPanel(new ReflowGridLayout(2, GAP, GAP));
		treeGrid.setOpaque(false);
		otherGrid.setOpaque(false);
		for (FarmingRunType type : FarmingRunType.values())
		{
			if (!type.isCrop())
			{
				continue; // compost bins: beside More types
			}
			RunTypeButton b = new RunTypeButton(type.getButtonLabel(), () -> press(type)).large();
			b.setName("farming.type." + type.name());
			b.setToolTipText(type.getTitle());
			buttons.put(type, b);
			(!type.isSpecial() ? mainGrid : SPECIAL_TREES.contains(type) ? treeGrid : otherGrid).add(b);
		}
		JLabel trees = small("TREES", SpyglassTheme.TILE_TEXT_QUIET);
		trees.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 0));
		JLabel others = small("OTHER CROPS", SpyglassTheme.TILE_TEXT_QUIET);
		others.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 0));
		specialGrid.add(fullWidth(trees));
		specialGrid.add(HomeDetailPage.growing(treeGrid));
		specialGrid.add(fullWidth(others));
		specialGrid.add(HomeDetailPage.growing(otherGrid));
		specialToggle = new RunTypeButton("More types ▸", this::toggleSpecials).large();
		specialToggle.setName("farming.more");
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setOpaque(false);
		// Pass 32: one shared navigation block on every browsing and setup page: Farming Overview, the Combine mode, then the
		// categories (two columns, larger; More types grouped as before).
		overviewButton = new RunTypeButton(OVERVIEW_BUTTON, this::goOverview).large();
		overviewButton.setName("farming.overview");
		overviewButton.setToolTipText("Every patch type at a glance (a run in progress stays open)");
		combineToggle = new RunTypeButton(COMBINE_TOGGLE_OFF, this::toggleCombine).large().emphasis();
		combineToggle.setName("farming.combine");
		combineToggle.setToolTipText("Choose several run types on the overview and start them as one run");
		controls.add(fullWidth(overviewButton));
		controls.add(Box.createVerticalStrut(GAP));
		controls.add(fullWidth(combineToggle));
		// Pass 33: Start combined run directly under the Combine control (the lower button stays; both share one action and
		// one enabled state). Shown only while combining with no run open.
		combineStart = new ActionButton(START_COMBINED_LABEL, ActionButton.Style.PRIMARY, this::startRun);
		combineStart.setName("farming.combine.start");
		combineStart.setAlignmentX(Component.LEFT_ALIGNMENT);
		combineStart.getAccessibleContext().setAccessibleName("Start combined run (top)");
		combineStartGap = Box.createVerticalStrut(GAP);
		controls.add(combineStartGap);
		controls.add(fullWidth(combineStart));
		combineStart.setVisible(false);
		combineStartGap.setVisible(false);
		controls.add(Box.createVerticalStrut(GAP + 2));
		controls.add(HomeDetailPage.growing(mainGrid));
		controls.add(Box.createVerticalStrut(GAP));
		RunTypeButton bins = new RunTypeButton(COMPOST_BUTTON, () -> press(FarmingRunType.COMPOST)).large();
		bins.setName("farming.type.COMPOST");
		bins.setToolTipText("Compost bins: what each bin holds, and runs to work them (alone or with crops)");
		buttons.put(FarmingRunType.COMPOST, bins);
		JPanel toggleRow = new JPanel(new ReflowGridLayout(2, GAP, GAP));
		toggleRow.setOpaque(false);
		toggleRow.add(bins);
		toggleRow.add(specialToggle);
		controls.add(HomeDetailPage.growing(toggleRow));
		controls.add(Box.createVerticalStrut(GAP));
		controls.add(HomeDetailPage.growing(specialGrid));
		specialGrid.setVisible(false);
		controls.add(Box.createVerticalStrut(4));
		combineLink = new Link(COMBINE_OFF, this::toggleCombine, SpyglassTheme.TEXT_SECONDARY, true);
		combineLink.setToolTipText("Pick several run types and start them as one run");
		combineLink.setVisible(false); // the mode control above replaces it (kept as the tests' handle)
		controls.add(fullWidth(combineLink));
		controls.add(Box.createVerticalStrut(8));
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(controls);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		// Grows with whatever it holds (never capped at its first, empty, height).
		content.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		add(content);
		render(FarmingModel.build(com.osrstelemetry.plugin.farming.FarmingPatchCatalog.standard(), FarmingSnapshot.LOGGED_OUT,
			System.currentTimeMillis()));
	}

	/** What Start Run and the run page's controls do (the adapter in production; a fake in tests). */
	interface RunActions
	{
		FarmingRunStore.StartResult start(FarmingRunRequest request);

		void skip(String patchId, boolean skip);

		/** Skip a run's compost bin work, or a protection payment still due (§28.16). */
		default void skipBin(String binId, boolean skip)
		{
		}

		default void skipProtection(String patchId, boolean skip)
		{
		}

		default void skipCompost(String patchId, boolean skip)
		{
		}

		void resume();

		void end();

		void acknowledge();

		/** Re-render from fresh state now (instead of waiting for the next poll). */
		void refresh();

		/** Go / Go anyway: close the run's preparation stage (Phase H). */
		void closePreparation();

		/** The preparation option "wear an amulet of bounty" (saved with the run). */
		default void setBounty(boolean use)
		{
		}

		/** Pass 31: a patch type's compost changed while a run is open. */
		default void setCompost(com.osrstelemetry.plugin.farming.FarmingPatchType type, FarmingPlanDraft.Compost choice)
		{
		}
	}

	/** The open preparation's view (Phase H) and where Find's focus goes (passive: an outline only); both optional. */
	void attachPreparation(java.util.function.Supplier<com.osrstelemetry.plugin.farming.FarmingPreparationView> source,
		java.util.function.Consumer<int[]> findFocus)
	{
		this.preparationSource = source;
		this.findFocus = findFocus;
	}

	private com.osrstelemetry.plugin.farming.FarmingPreparationView preparation(FarmingModel m)
	{
		FarmingRunView run = m.getRun();
		if (run == null || run.getPhase() != FarmingRunView.Phase.PREPARING || preparationSource == null)
		{
			return null;
		}
		return preparationSource.get();
	}

	private java.util.function.Supplier<com.osrstelemetry.plugin.guidance.GuidanceModel> guidanceSource;

	/** The live guidance model (the run page's next card reads it). */
	void attachGuidance(java.util.function.Supplier<com.osrstelemetry.plugin.guidance.GuidanceModel> source)
	{
		this.guidanceSource = source;
	}

	private java.util.function.IntSupplier bankFilterState;
	private Runnable bankFilterToggle;

	/** The player-clicked bank filter (0 closed / 1 available / 2 active) and its toggle; optional. */
	void attachBankFilter(java.util.function.IntSupplier state, Runnable toggle)
	{
		this.bankFilterState = state;
		this.bankFilterToggle = toggle;
	}

	void attachRunActions(RunActions actions)
	{
		this.runActions = actions;
	}

	FarmingRunPage runPage()
	{
		return runPage;
	}

	boolean isRunPageShown()
	{
		return model != null && runMode(model);
	}

	boolean areRunTypeButtonsVisible()
	{
		return controls.isVisible();
	}

	boolean isBrowsingDuringRun()
	{
		return browsingDuringRun;
	}

	ActionButton startButton()
	{
		return startButton;
	}

	ActionButton startTopButton()
	{
		return combineMode ? combineStart : startTopButton;
	}

	/** The upper Start combined run (in the shared controls). */
	ActionButton combineStartButton()
	{
		return combineStart;
	}

	/** Combining: whether Start combined run may start (the one rule both buttons follow). */
	private boolean combinedStartOk(FarmingModel m)
	{
		if (m == null || combined.isEmpty())
		{
			return false;
		}
		boolean binsOnly = combined.size() == 1 && combined.contains(FarmingRunType.COMPOST);
		int binCount = combined.contains(FarmingRunType.COMPOST) ? prefs().getRunBins().size() : 0;
		return binsOnly ? binCount > 0 : draft.scope(previewRows(m), m.getNow())[0] > 0;
	}

	/** Shows and enables the upper Start combined run from the same rule as the lower one. */
	private void syncCombineStart(FarmingModel m)
	{
		if (combineStart == null)
		{
			return;
		}
		boolean show = combineMode && m != null && m.getRun() == null;
		if (combineStart.isVisible() != show)
		{
			combineStart.setVisible(show);
			combineStartGap.setVisible(show);
			revalidate();
		}
		if (show)
		{
			combineStart.setEnabled(combinedStartOk(m));
		}
	}

	/** Start Run above the list too, when no run exists (the same action and state as the one below the list). */
	private void addTopStart(FarmingModel m)
	{
		if (m.getRun() != null)
		{
			return;
		}
		content.add(Box.createVerticalStrut(4));
		startTopButton = new ActionButton(combineMode ? START_COMBINED_LABEL : START_LABEL, ActionButton.Style.PRIMARY, this::startRun);
		startTopButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		startTopButton.getAccessibleContext().setAccessibleName("Start Run (top of the list)");
		content.add(startTopButton);
		content.add(Box.createVerticalStrut(6));
	}

	/** Both Start Run buttons follow the same rule (something included). */
	private void setStartEnabled(boolean on)
	{
		for (ActionButton b : new ActionButton[]{startButton, startTopButton})
		{
			if (b != null)
			{
				b.setEnabled(on);
			}
		}
	}

	static final String START_LABEL = "Start Run";

	List<String> startProblems()
	{
		return startProblems;
	}

	/** Restores the saved preview and persists every later selection (hidden, non-account value). */
	void attachSelectionStore(Supplier<String> reader, Consumer<String> writer)
	{
		String saved = null;
		try
		{
			saved = reader == null ? null : reader.get();
		}
		catch (RuntimeException e)
		{
			// Unreadable preference: same as none.
		}
		selectionWriter = writer;
		applySelection(FarmingRunType.parse(saved), false);
	}

	FarmingRunType selectedType()
	{
		return selected;
	}

	boolean isSpecialsOpen()
	{
		return specialsOpen;
	}

	RunTypeButton button(FarmingRunType type)
	{
		return buttons.get(type);
	}

	RunTypeButton specialToggle()
	{
		return specialToggle;
	}

	FarmingPlanDraft draft()
	{
		return draft;
	}

	static final String COMBINE_OFF = "☐ Combine run types";
	static final String COMBINE_ON = "☑ Combine run types";
	static final String COMBINE_TOGGLE_OFF = "Combine run types";
	static final String COMBINE_TOGGLE_ON = "✓ Combining run types";
	static final String START_COMBINED_LABEL = "Start combined run";
	static final String COMBINE_EMPTY = "Click the cards to choose what to combine. Nothing starts until you press Start combined run.";
	static final String COMBINE_HINT = "Click a card to put it in the run or take it out. View patches to adjust its patches.";
	/** The mode control (pass 31): off, or on with a gold face. */
	private RunTypeButton combineToggle;
	static final String OVERVIEW_BUTTON = "Farming Overview";
	/** Pass 32: the shared Overview control (selected while the overview, or the combined selection, is shown). */
	private RunTypeButton overviewButton;

	RunTypeButton overviewButton()
	{
		return overviewButton;
	}

	/**
	 * Farming Overview: back to the grid from any browsing page. Combining stays on (its overview is the combined selection);
	 * a run in progress stays open and reachable from the banner. Never starts or ends anything.
	 */
	void goOverview()
	{
		browseType = null;
		if (combineMode)
		{
			syncButtons();
			structureKey = null;
			if (model != null)
			{
				render(model);
			}
			return;
		}
		if (selected == null)
		{
			syncButtons();
			structureKey = null;
			if (model != null)
			{
				render(model);
			}
			return;
		}
		applySelection(null, true);
	}
	/** The combined selection while combining is switched off, restored when it is switched on again (pass 32). */
	private final java.util.Set<FarmingRunType> remembered = new java.util.LinkedHashSet<>();
	/** Combining, the category whose patches are open from the grid (null: the combined run itself). */
	private FarmingRunType browseType;

	RunTypeButton combineToggle()
	{
		return combineToggle;
	}

	FarmingRunType browseType()
	{
		return browseType;
	}
	private static final java.util.Set<FarmingRunType> SPECIAL_TREES = java.util.EnumSet.of(FarmingRunType.HARDWOOD, FarmingRunType.REDWOOD,
		FarmingRunType.SPIRIT, FarmingRunType.CALQUAT, FarmingRunType.CELASTRUS, FarmingRunType.CRYSTAL);
	private Link combineLink;
	static final String COMPOST_BUTTON = "Compost bins";
	/** Multi-select: the run-type buttons toggle membership instead of selecting one type. Session-only. */
	private boolean combineMode;
	private final java.util.Set<FarmingRunType> combined = new java.util.LinkedHashSet<>();

	boolean isCombineMode()
	{
		return combineMode;
	}

	List<FarmingRunType> combinedTypes()
	{
		return new ArrayList<>(combined);
	}

	Link combineLink()
	{
		return combineLink;
	}

	/** A run-type button: select it, or (combining) add or remove it. */
	void press(FarmingRunType type)
	{
		if (!combineMode)
		{
			select(type);
			return;
		}
		if (!combined.remove(type))
		{
			combined.add(type);
		}
		if (type.isSpecial() && combined.contains(type))
		{
			specialsOpen = true;
		}
		syncButtons();
		syncSpecials();
		structureKey = null;
		if (model != null)
		{
			render(model);
		}
	}

	/** Combine on: the current type starts the selection. Off: back to one type; nothing was started either way. */
	void toggleCombine()
	{
		combineMode = !combineMode;
		browseType = null;
		if (combineMode)
		{
			// Pass 32: switching combining off and on again keeps the categories chosen before (this account only).
			combined.clear();
			combined.addAll(remembered);
			if (combined.isEmpty() && selected != null)
			{
				combined.add(selected);
			}
		}
		else
		{
			remembered.clear();
			remembered.addAll(combined);
			FarmingRunType first = combined.isEmpty() ? selected : combined.iterator().next();
			combined.clear();
			selected = first;
		}
		startProblems = new ArrayList<>();
		syncButtons();
		syncSpecials();
		structureKey = null;
		if (model != null)
		{
			render(model);
		}
	}

	private void syncButtons()
	{
		for (Map.Entry<FarmingRunType, RunTypeButton> e : buttons.entrySet())
		{
			e.getValue().setSelected(combineMode ? combined.contains(e.getKey()) : e.getKey() == selected);
		}
		if (combineLink != null)
		{
			combineLink.setText(combineMode ? COMBINE_ON : COMBINE_OFF);
			combineLink.getAccessibleContext().setAccessibleName(combineMode ? "Combine run types: on" : "Combine run types: off");
		}
		if (overviewButton != null)
		{
			overviewButton.setSelected(browseType == null && (combineMode || selected == null));
		}
		if (combineToggle != null)
		{
			combineToggle.setLabel(combineMode ? COMBINE_TOGGLE_ON : COMBINE_TOGGLE_OFF);
			combineToggle.setSelected(combineMode);
			combineToggle.getAccessibleContext().setAccessibleName(combineMode ? "Combine run types: on" : "Combine run types: off");
		}
	}

	/** Pressing a run type: select it (pressing the selected one again does nothing). */
	void select(FarmingRunType type)
	{
		if (type == selected)
		{
			return;
		}
		applySelection(type, true);
	}

	/** "× overview": clear the selection. */
	void showOverview()
	{
		browseType = null;
		if (combineMode)
		{
			combineMode = false;
			remembered.clear();
			remembered.addAll(combined);
			combined.clear();
		}
		applySelection(null, true);
	}

	private void applySelection(FarmingRunType type, boolean persist)
	{
		selected = type;
		if (type != null && type.isSpecial())
		{
			specialsOpen = true;
		}
		syncButtons();
		syncSpecials();
		if (persist && selectionWriter != null)
		{
			try
			{
				selectionWriter.accept(type == null ? "" : type.name());
			}
			catch (RuntimeException e)
			{
				// A preference that cannot be written is not worth failing the click over.
			}
		}
		if (model != null)
		{
			render(model);
		}
	}

	private void toggleSpecials()
	{
		// Closing is refused while a special type is selected: its button must stay visible.
		if (specialsOpen && (combineMode ? combined.stream().anyMatch(FarmingRunType::isSpecial) : selected != null && selected.isSpecial()))
		{
			return;
		}
		specialsOpen = !specialsOpen;
		syncSpecials();
	}

	private void syncSpecials()
	{
		specialToggle.setLabel(specialsOpen ? "More types ▾" : "More types ▸");
		boolean specialChosen = combineMode ? combined.stream().anyMatch(FarmingRunType::isSpecial) : selected != null && selected.isSpecial();
		specialToggle.setSelected(specialChosen);
		if (specialGrid.isVisible() != specialsOpen)
		{
			specialGrid.setVisible(specialsOpen);
			revalidate();
			repaint();
		}
	}

	// -- render ------------------------------------------------------------------

	void render(FarmingModel m)
	{
		this.model = m;
		syncPrefs();
		carryCompostOver(m);
		if (m.getOwner() != null && !m.getOwner().equals(draftOwner))
		{
			if (draftOwner != null)
			{
				draft.clear();
				expanded.clear();
				combineMode = false; // another account: no combined selection carries over either
				combined.clear();
				remembered.clear();
				lockedOpen.clear();
				syncButtons();
				browsingDuringRun = false;
				endConfirm = false;
				startProblems = new ArrayList<>();
				structureKey = null; // fresh cards: nothing from the previous account carries over
			}
			draftOwner = m.getOwner();
		}
		boolean anySpecialReady = false;
		for (Map.Entry<FarmingRunType, RunTypeButton> e : buttons.entrySet())
		{
			boolean ready = m.summary(e.getKey()).getReady() > 0;
			e.getValue().setPip(ready);
			anySpecialReady |= ready && e.getKey().isSpecial();
		}
		specialToggle.setPip(anySpecialReady && !specialsOpen);
		buttons.get(FarmingRunType.COMPOST).setPip(binsWithWork(m) > 0);
		if (m.getRun() == null)
		{
			browsingDuringRun = false;
			endConfirm = false;
		}
		boolean hideControls = runMode(m) || settingsOpen;
		if (controls.isVisible() == hideControls)
		{
			controls.setVisible(!hideControls); // execution mode and settings: the run-type buttons are hidden (canonical §16)
			revalidate();
		}
		syncCombineStart(m);

		syncTutorial(m);
		String key = structureKey(m);
		if (!key.equals(structureKey))
		{
			structureKey = key;
			rebuild(m);
		}
		else
		{
			update(m);
		}
	}

	private boolean runMode(FarmingModel m)
	{
		return m.getState() == FarmingSnapshot.State.AVAILABLE && m.getRun() != null && !browsingDuringRun;
	}

	private String structureKey(FarmingModel m)
	{
		if (settingsOpen)
		{
			return "settings|" + m.getState() + '|' + m.getOwner();
		}
		String tip = "|tip:" + tutorialStep;
		if (runMode(m))
		{
			return FarmingRunPage.structureKey(m.getRun(), endConfirm, preparation(m)) + tip;
		}
		StringBuilder k = new StringBuilder().append(m.getState()).append('|').append(selected).append('|').append(optionsOpen)
			.append("|combine:").append(combineMode).append(combined).append("|browse:").append(browseType).append("|locked:").append(lockedOpen).append("|settings:").append(settingsOpen);
		FarmingRunView run = m.getRun();
		k.append("|run:").append(run == null ? "-" : run.getIntent().getIntentId() + run.getPhase());
		k.append("|problems:").append(startProblems).append(tip);
		if (m.getState() == FarmingSnapshot.State.AVAILABLE)
		{
			if (combineMode)
			{
				for (FarmingModel.Row r : previewRows(m))
				{
					k.append('|').append(r.getPatchId());
				}
			}
			else if (selected == null)
			{
				k.append('|').append(m.trackedTypes());
			}
			else
			{
				for (FarmingModel.Row r : m.rows(selected))
				{
					k.append('|').append(r.getPatchId());
				}
				k.append("|x").append(m.excluded(selected).size());
			}
		}
		return k.toString();
	}

	private void rebuild(FarmingModel m)
	{
		String focusName = focusedName();
		rebuildContent(m);
		restoreFocus(focusName);
	}

	/** The name of the focused control inside this page (named controls keep focus across a rebuild), or null. */
	private String focusedName()
	{
		Component f = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		return f != null && f.getName() != null && SwingUtilities.isDescendingFrom(f, this) ? f.getName() : null;
	}

	private void restoreFocus(String name)
	{
		if (name == null)
		{
			return;
		}
		Component c = findNamed(content, name);
		if (c != null)
		{
			c.requestFocusInWindow();
		}
	}

	private static Component findNamed(Container root, String name)
	{
		for (Component c : root.getComponents())
		{
			if (name.equals(c.getName()))
			{
				return c;
			}
			if (c instanceof Container)
			{
				Component found = findNamed((Container) c, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private void rebuildContent(FarmingModel m)
	{
		content.removeAll();
		rowViews.clear();
		summaryLabel = null;
		untrackedLabel = null;
		scopeLabel = null;
		startButton = null;
		startTopButton = null;
		startHint = null;
		if (settingsOpen)
		{
			content.add(buildSettings());
			content.revalidate();
			content.repaint();
			revalidate();
			repaint();
			return;
		}
		if (tutorialStep != null && m.getState() == FarmingSnapshot.State.AVAILABLE)
		{
			content.add(tutorialCard(m));
			content.add(Box.createVerticalStrut(6));
		}
		if (runMode(m))
		{
			runPage.build(content, m.getRun(), m, endConfirm, preparation(m));
			content.revalidate();
			content.repaint();
			revalidate();
			repaint();
			return;
		}
		if (m.getState() == FarmingSnapshot.State.AVAILABLE)
		{
			addRunBanner(m);
		}
		switch (m.getState())
		{
			case LOGGED_OUT:
				content.add(message("Log in to see your patches."));
				break;
			case LOADING:
				content.add(message("Loading your patches…"));
				break;
			case NOT_AVAILABLE_HERE:
				content.add(message("Farming is tracked on standard worlds only. Nothing is recorded here."));
				break;
			case AVAILABLE:
			default:
				if (combineMode && browseType != null)
				{
					buildBrowse(m, browseType);
				}
				else if (combineMode)
				{
					buildCombined(m);
				}
				else if (selected == null)
				{
					buildOverview(m);
				}
				else if (selected == FarmingRunType.COMPOST)
				{
					buildCompost(m);
				}
				else
				{
					buildPreview(m, selected);
				}
				break;
		}
		content.revalidate();
		content.repaint();
		revalidate();
		repaint();
	}

	private void update(FarmingModel m)
	{
		if (settingsOpen || m.getState() != FarmingSnapshot.State.AVAILABLE)
		{
			return;
		}
		if (!runMode(m) && (selected == FarmingRunType.COMPOST || combineMode && combined.contains(FarmingRunType.COMPOST)))
		{
			// A bin read or a minute passing changes a card's line: rebuilt only when the text changes.
			String sig = compostSignature(m);
			if (!sig.equals(compostSignature))
			{
				rebuild(m);
				return;
			}
		}
		if (runMode(m))
		{
			runPage.update(m.getRun(), m, preparation(m));
			return;
		}
		if (combineMode)
		{
			String sig = overviewSignature(m);
			if (!sig.equals(overviewSignature))
			{
				rebuild(m); // a category's status changed on the grid
				return;
			}
			for (FarmingModel.Row r : (browseType != null ? m.rows(browseType) : previewRows(m)))
			{
				PatchCardView v = rowViews.get(r.getPatchId());
				if (v != null)
				{
					v.update(r, draft.isIncluded(r, m.getNow()), plannedName(r, m));
				}
			}
			updateSummaries(m);
			return;
		}
		if (selected == null)
		{
			// A handful of lines: rebuilt only when their text changes (at most once a minute).
			String sig = overviewSignature(m);
			if (!sig.equals(overviewSignature))
			{
				rebuild(m);
			}
			return;
		}
		for (FarmingModel.Row r : m.rows(selected))
		{
			PatchCardView v = rowViews.get(r.getPatchId());
			if (v != null)
			{
				v.update(r, draft.isIncluded(r, m.getNow()), plannedName(r, m));
			}
		}
		updateSummaries(m);
	}

	// -- overview ----------------------------------------------------------------

	private String overviewSignature;

	/** The Farming Guild contract, as the game reports it; "not observed yet" until read (never guessed). */
	static String[] contractText(FarmingModel m)
	{
		com.osrstelemetry.plugin.farming.FarmingAccountFacts facts = m.getFacts();
		com.osrstelemetry.plugin.farming.FarmingAccountFacts.Contract c = facts == null ? null : facts.getContract();
		if (c == null || c.getState() == com.osrstelemetry.plugin.farming.FarmingAccountFacts.Contract.State.NOT_READ)
		{
			return new String[]{"Not observed yet", "Spyglass reads it from the game once you're logged in."};
		}
		switch (c.getState())
		{
			case NONE:
				return new String[]{"No contract", "Guildmaster Jane, Farming Guild"};
			case ACTIVE:
				return new String[]{"Grow " + c.getCrop().getDisplayName(), "Harvest it, then return to Guildmaster Jane"};
			case COMPLETE:
				return new String[]{"Complete", "Return to Guildmaster Jane for your reward"};
			default:
				return new String[]{"Held (crop not recognised)", "Spyglass can't name this contract's crop yet"};
		}
	}

	private JComponent contractCard(FarmingModel m)
	{
		RaisedTile tile = new RaisedTile(null, 5, 6);
		String[] t = contractText(m);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel kicker = smallWrap("FARMING CONTRACT", SpyglassTheme.TILE_TEXT_QUIET);
		kicker.setFont(SpyglassFonts.smallBold());
		JLabel head = new WrappingHtmlLabel(t[0], false);
		head.setFont(SpyglassFonts.regular());
		head.setForeground(SpyglassTheme.TEXT_PRIMARY);
		for (JComponent c : new JComponent[]{kicker, head, wrap(t[1], SpyglassTheme.TEXT_SECONDARY)})
		{
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(c);
		}
		tile.add(body, BorderLayout.CENTER);
		tile.getAccessibleContext().setAccessibleName("Farming contract: " + t[0]);
		tile.setAlignmentX(Component.LEFT_ALIGNMENT);
		return tile;
	}

	private static String overviewSignature(FarmingModel m)
	{
		StringBuilder b = new StringBuilder(String.join("/", contractText(m)));
		for (FarmingRunType t : m.trackedTypes())
		{
			b.append(t).append(m.overviewLine(t)).append(m.overviewTone(t)).append(';');
		}
		return b.toString();
	}

	private void buildOverview(FarmingModel m)
	{
		overviewSignature = overviewSignature(m);
		content.add(contractCard(m));
		content.add(Box.createVerticalStrut(8));
		content.add(sectionHeader(OVERVIEW_HEADER, null));
		content.add(Box.createVerticalStrut(4));
		List<FarmingRunType> tracked = m.trackedTypes();
		if (tracked.isEmpty())
		{
			content.add(message("Nothing tracked yet. Visit a patch and Spyglass starts keeping track."));
		}
		else
		{
			content.add(categoryGrid(m, tracked, false));
		}
		addUntracked(m, tracked);
		content.add(Box.createVerticalStrut(8));
		content.add(wrap(HINT, SpyglassTheme.TEXT_MUTED));
	}

	private void addUntracked(FarmingModel m, List<FarmingRunType> tracked)
	{
		List<String> main = new ArrayList<>();
		int specials = 0;
		for (FarmingRunType type : m.untrackedTypes())
		{
			if (type.isSpecial())
			{
				specials++;
			}
			else
			{
				main.add(type.getTitle());
			}
		}
		if (!tracked.isEmpty() && (!main.isEmpty() || specials > 0))
		{
			String text = "Not tracked yet: " + String.join(", ", main)
				+ (specials > 0 ? (main.isEmpty() ? "" : " and ") + specials + " more type" + (specials == 1 ? "" : "s") : "");
			content.add(Box.createVerticalStrut(6));
			content.add(wrap(text, SpyglassTheme.TEXT_MUTED));
		}
	}

	/** The item each category's card shows (what it grows). */
	static final Map<FarmingRunType, Integer> CATEGORY_ICONS = new java.util.EnumMap<>(FarmingRunType.class);

	static
	{
		CATEGORY_ICONS.put(FarmingRunType.HERB, ItemID.RANARR_WEED);
		CATEGORY_ICONS.put(FarmingRunType.TREE, ItemID.YEW_LOGS);
		CATEGORY_ICONS.put(FarmingRunType.FRUIT, ItemID.PAPAYA);
		CATEGORY_ICONS.put(FarmingRunType.BUSH, ItemID.POISONIVY_BERRIES);
		CATEGORY_ICONS.put(FarmingRunType.HOPS, ItemID.HAMMERSTONE_HOPS);
		CATEGORY_ICONS.put(FarmingRunType.ALLOTMENT, ItemID.WATERMELON);
		CATEGORY_ICONS.put(FarmingRunType.HARDWOOD, ItemID.MAHOGANY_LOGS);
		CATEGORY_ICONS.put(FarmingRunType.REDWOOD, ItemID.REDWOOD_LOGS);
		CATEGORY_ICONS.put(FarmingRunType.SPIRIT, ItemID.SPIRIT_TREE_SEED);
		CATEGORY_ICONS.put(FarmingRunType.CALQUAT, ItemID.CALQUAT_FRUIT);
		CATEGORY_ICONS.put(FarmingRunType.CELASTRUS, ItemID.CELASTRUS_TREE_SEED);
		CATEGORY_ICONS.put(FarmingRunType.CACTUS, ItemID.CACTUS_SPINE);
		CATEGORY_ICONS.put(FarmingRunType.SEAWEED, ItemID.GIANT_SEAWEED);
		CATEGORY_ICONS.put(FarmingRunType.MUSHROOM, ItemID.BITTERCAP_MUSHROOM);
		CATEGORY_ICONS.put(FarmingRunType.BELLADONNA, ItemID.NIGHTSHADE);
		CATEGORY_ICONS.put(FarmingRunType.HESPORI, ItemID.HESPORI_SEED);
		CATEGORY_ICONS.put(FarmingRunType.ANIMA, ItemID.KRONOS_SEED);
		CATEGORY_ICONS.put(FarmingRunType.CRYSTAL, ItemID.CRYSTAL_TREE_SEED);
		CATEGORY_ICONS.put(FarmingRunType.COMPOST, ItemID.BUCKET_COMPOST);
	}

	/** A category card's one status (pass 31): Ready (green), else Growing (white), else Dead (red); then the special states. */
	static final class CategoryStatus
	{
		final String text;
		final Color tone;

		CategoryStatus(String text, Color tone)
		{
			this.text = text;
			this.tone = tone;
		}

		@Override
		public String toString()
		{
			return text;
		}
	}

	static CategoryStatus categoryStatus(FarmingModel m, FarmingRunType type)
	{
		if (type == FarmingRunType.ANIMA && m.getAnima() != null && m.getAnima().getState() != com.osrstelemetry.plugin.farming.FarmingAnima.State.UNKNOWN)
		{
			return new CategoryStatus(capitalise(m.getAnima().text(m.getNow())), SpyglassTheme.TEXT_PRIMARY);
		}
		FarmingModel.TypeSummary t = m.summary(type);
		if (t.getReady() > 0)
		{
			return new CategoryStatus(t.getReady() + " Ready", SpyglassTheme.STATUS_SUCCESS);
		}
		if (t.getGrowing() > 0)
		{
			return new CategoryStatus(t.getGrowing() + " Growing", SpyglassTheme.TEXT_PRIMARY);
		}
		if (t.getDead() > 0)
		{
			return new CategoryStatus(t.getDead() + " Dead", SpyglassTheme.STATUS_DANGER);
		}
		if (t.getAttention() > 0)
		{
			return new CategoryStatus(t.getAttention() + " Diseased", SpyglassTheme.FARM_EMBER);
		}
		String line = m.overviewLine(type);
		if (line != null && line.startsWith("grown"))
		{
			return new CategoryStatus("Grown", SpyglassTheme.TEXT_PRIMARY);
		}
		if (t.getClear() > 0)
		{
			return new CategoryStatus(t.getClear() + " Clear", SpyglassTheme.TEXT_SECONDARY);
		}
		return new CategoryStatus("Check patch", SpyglassTheme.TEXT_MUTED);
	}

	private static String capitalise(String s)
	{
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** The category cards (combining: with their "include" ticks; the bins' card too). */
	private JComponent categoryGrid(FarmingModel m, List<FarmingRunType> types, boolean combining)
	{
		JPanel grid = new JPanel(new CardGridLayout(4, GAP, GAP));
		grid.setOpaque(false);
		grid.setName("farming.categories");
		for (FarmingRunType type : types)
		{
			CategoryStatus st = type == FarmingRunType.COMPOST ? binStatus(m) : categoryStatus(m, type);
			// Combining, the card itself puts the category in the run or takes it out (the tick says the same); View patches
			// opens its patches without changing that. Otherwise the card opens its patches.
			Runnable main = combining ? () -> include(type, !combined.contains(type)) : () -> openCategory(type);
			grid.add(new CategoryTile(type, st, sprites, main, combining, combined.contains(type), on -> include(type, on), () -> openCategory(type)));
		}
		return HomeDetailPage.growing(grid);
	}

	/** The bins' card on the combined grid: bins with work, or how many are seen. */
	private static CategoryStatus binStatus(FarmingModel m)
	{
		int work = binsWithWork(m);
		return work > 0 ? new CategoryStatus(work + " to work", SpyglassTheme.STATUS_SUCCESS) : new CategoryStatus("Nothing to do", SpyglassTheme.TEXT_SECONDARY);
	}

	/** A card's main area: its patches (combining: browsed without leaving the combined run). */
	void openCategory(FarmingRunType type)
	{
		if (!combineMode)
		{
			select(type);
			return;
		}
		browseType = type;
		structureKey = null;
		if (model != null)
		{
			render(model);
		}
	}

	/** A card's tick (combining): in or out of the combined run; never opens anything. */
	void include(FarmingRunType type, boolean on)
	{
		if (!combineMode || on == combined.contains(type))
		{
			return;
		}
		press(type);
	}

	/** Combining, one category's patches from the grid: the same cards and choices, then back to the combined run. */
	private void buildBrowse(FarmingModel m, FarmingRunType type)
	{
		overviewSignature = overviewSignature(m);
		content.add(sectionHeader(type.getTitle().toUpperCase(java.util.Locale.ROOT) + " PATCHES", null));
		// Browsing never changes the category's place in the run: that is the overview card's click (pass 32). Patch choices
		// here apply at once.
		boolean in = combined.contains(type);
		JLabel state = smallWrap(in ? "In the combined run · patch changes apply at once" : "Not in the combined run · click its card on the overview to add it",
			in ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.TEXT_SECONDARY);
		state.setName("farming.browse.state");
		content.add(fullWidth(state));
		content.add(Box.createVerticalStrut(6));
		if (!type.isCrop())
		{
			addBinCards(m);
			return;
		}
		addFamilySwitches(m, type);
		JPanel list = growingPanel();
		boolean first = true;
		for (FarmingModel.Row r : m.rows(type))
		{
			if (!first)
			{
				list.add(Box.createVerticalStrut(CARD_GAP));
			}
			first = false;
			list.add(card(r, m));
		}
		content.add(list);
		addLockedSection(m, type);
	}

	/**
	 * One category on the Farming overview (pass 31): its item, its name and one status. The card itself opens the category's
	 * patches; combining, the tick in its corner puts it in or out of the combined run (separate from opening it).
	 */
	static final class CategoryTile extends RaisedTile implements CardGridLayout.Comfortable
	{
		final FarmingRunType type;
		final CategoryStatus status;
		final TrackBox box;
		private final WrappingHtmlLabel name;
		private final WrappingHtmlLabel state;

		/** Combining: opens the category's patches without changing its place in the run (null otherwise). */
		final Link viewPatches;

		CategoryTile(FarmingRunType type, CategoryStatus status, SpriteSource sprites, Runnable onOpen, boolean combining, boolean included,
			java.util.function.Consumer<Boolean> onInclude)
		{
			this(type, status, sprites, onOpen, combining, included, onInclude, null);
		}

		CategoryTile(FarmingRunType type, CategoryStatus status, SpriteSource sprites, Runnable onOpen, boolean combining, boolean included,
			java.util.function.Consumer<Boolean> onInclude, Runnable onView)
		{
			super(onOpen, 5, 6);
			this.type = type;
			this.status = status;
			setName("category." + type.name());
			setLayout(new BorderLayout(0, 2));
			if (combining)
			{
				setFace(included ? SpyglassTheme.CARD_SELECTED : null, included ? SpyglassTheme.CARD_SELECTED_HOVER : null);
				setRail(included ? SpyglassTheme.ACCENT_SAGE : null);
			}
			CropIcon icon = new CropIcon(sprites);
			Integer id = CATEGORY_ICONS.get(type);
			icon.show(id == null ? -1 : id, type.getTitle());
			JPanel top = new JPanel(new BorderLayout());
			top.setOpaque(false);
			top.add(icon, BorderLayout.WEST);
			if (combining)
			{
				box = new TrackBox(included, "Include " + type.getTitle() + " in the combined run", onInclude);
				box.setName("include." + type.name());
				JPanel boxCell = new JPanel(new BorderLayout());
				boxCell.setOpaque(false);
				boxCell.add(box, BorderLayout.NORTH);
				top.add(boxCell, BorderLayout.EAST);
			}
			else
			{
				box = null;
			}
			name = new WrappingHtmlLabel(type.getTitle(), false);
			name.setFont(SpyglassFonts.bold());
			name.setForeground(SpyglassTheme.TEXT_PRIMARY);
			state = new WrappingHtmlLabel(status.text, false);
			state.setFont(SpyglassFonts.regular());
			state.setForeground(status.tone);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			for (WrappingHtmlLabel l : new WrappingHtmlLabel[]{name, state})
			{
				JPanel cell = new JPanel(new BorderLayout());
				cell.setOpaque(false);
				cell.add(l, BorderLayout.CENTER);
				cell.setAlignmentX(Component.LEFT_ALIGNMENT);
				text.add(cell);
			}
			add(top, BorderLayout.NORTH);
			add(text, BorderLayout.CENTER);
			if (combining && onView != null)
			{
				viewPatches = new Link("View patches ›", onView, SpyglassTheme.ACCENT_GOLD, true).wrapping();
				viewPatches.setFont(SpyglassFonts.regular());
				viewPatches.setName("view." + type.name());
				viewPatches.getAccessibleContext().setAccessibleName("View " + type.getTitle() + " patches");
				viewPatches.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
				add(viewPatches, BorderLayout.SOUTH);
			}
			else
			{
				viewPatches = null;
			}
			getAccessibleContext().setAccessibleName(type.getTitle() + ": " + status.text + (combining
				? included ? ", in the combined run. Press to take it out" : ", not in the combined run. Press to add it"
				: ". Opens its patches"));
		}

		/** The icon and tick, or the name and status on a line, whichever is wider (a long name wraps past 72 px of text). */
		@Override
		public int comfortableWidth()
		{
			java.awt.Insets in = getInsets();
			int text = Math.max(Math.min(SpyglassFonts.px(72), name.naturalWidth()), state.naturalWidth());
			int top = ItemIcon.WIDTH + (box == null ? 0 : SpyglassFonts.px(16) + 4);
			return in.left + in.right + Math.max(text, top);
		}
	}

	// -- preview -----------------------------------------------------------------

	/** The types the preview shows: the combined selection, or the one selected type. */
	private List<FarmingRunType> previewTypes()
	{
		if (combineMode)
		{
			return new ArrayList<>(combined);
		}
		return selected == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(selected);
	}

	/** Every row of the preview's types, a patch shared by two types counted once (first type wins). */
	private List<FarmingModel.Row> previewRows(FarmingModel m)
	{
		List<FarmingModel.Row> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (FarmingRunType t : previewTypes())
		{
			for (FarmingModel.Row r : m.rows(t))
			{
				if (seen.add(r.getPatchId()))
				{
					out.add(r);
				}
			}
		}
		return out;
	}

	/** Combined run: one section of cards per chosen type (shared patches once), per-type options, one start. */
	private void buildCombined(FarmingModel m)
	{
		overviewSignature = overviewSignature(m);
		content.add(sectionHeader("COMBINED RUN", null));
		content.add(wrap(COMBINE_HINT, SpyglassTheme.TEXT_MUTED));
		content.add(Box.createVerticalStrut(6));
		// Pass 31: the grid with ticks: a card's tick puts it in the run; the card itself opens its patches.
		List<FarmingRunType> types = new ArrayList<>(m.trackedTypes());
		for (FarmingRunType t : combined)
		{
			if (!types.contains(t))
			{
				types.add(t); // chosen on the buttons before it was ever seen
			}
		}
		if (!types.contains(FarmingRunType.COMPOST))
		{
			types.add(FarmingRunType.COMPOST);
		}
		content.add(categoryGrid(m, types, true));
		content.add(Box.createVerticalStrut(6));
		// One summary of the selection (pass 32): what is in the run and how many patches.
		summaryLabel = smallWrap(" ", SpyglassTheme.TEXT_PRIMARY);
		summaryLabel.setFont(SpyglassFonts.bold());
		summaryLabel.setName("farming.combined.summary");
		content.add(fullWidth(summaryLabel));
		untrackedLabel = small(" ", SpyglassTheme.TEXT_MUTED);
		untrackedLabel.setVisible(false);
		content.add(fullWidth(untrackedLabel));
		if (combined.isEmpty())
		{
			setText(summaryLabel, "Nothing chosen yet");
			content.add(message(COMBINE_EMPTY));
			return;
		}
		java.util.Set<String> seen = new java.util.HashSet<>();
		compostSignature = compostSignature(m);
		for (FarmingRunType type : combined)
		{
			content.add(Box.createVerticalStrut(6));
			content.add(fullWidth(smallWrap(type.getTitle().toUpperCase(java.util.Locale.ROOT), SpyglassTheme.TILE_TEXT_QUIET)));
			if (!type.isCrop())
			{
				content.add(Box.createVerticalStrut(3));
				addBinCards(m);
				continue;
			}
			addFamilySwitches(m, type);
			content.add(Box.createVerticalStrut(3));
			JPanel list = growingPanel();
			boolean first = true;
			int shown = 0;
			for (FarmingModel.Row r : m.rows(type))
			{
				if (!seen.add(r.getPatchId()))
				{
					continue; // already listed under an earlier type
				}
				if (!first)
				{
					list.add(Box.createVerticalStrut(CARD_GAP));
				}
				first = false;
				shown++;
				list.add(card(r, m));
			}
			if (shown == 0)
			{
				list.add(fullWidth(small(m.rows(type).isEmpty() ? "No usable patches" : "All listed above", SpyglassTheme.TEXT_MUTED)));
			}
			content.add(list);
			addLockedSection(m, type);
		}
		content.add(Box.createVerticalStrut(8));
		List<com.osrstelemetry.plugin.farming.FarmingPatchType> compostTypes = new ArrayList<>();
		for (FarmingRunType t : combined)
		{
			compostTypes.addAll(compostTypes(t));
		}
		if (!compostTypes.isEmpty())
		{
			addCompostEditor(m, compostTypes);
		}
		content.add(Box.createVerticalStrut(6));
		Link options = new Link(optionsOpen ? "Options ▾" : "Options ▸", () ->
		{
			optionsOpen = !optionsOpen;
			render(model);
		});
		content.add(fullWidth(options));
		if (optionsOpen)
		{
			List<FarmingRunType> crops = new ArrayList<>();
			for (FarmingRunType t : combined)
			{
				if (t.isCrop())
				{
					crops.add(t);
				}
			}
			content.add(buildOptions(crops));
		}
		content.add(Box.createVerticalStrut(4));
		scopeLabel = smallWrap(" ", SpyglassTheme.TEXT_PRIMARY);
		content.add(fullWidth(scopeLabel));
		content.add(Box.createVerticalStrut(8));
		buildStart(m, null);
		updateSummaries(m);
	}

	/**
	 * "Allotments & Flowers": include allotments and include flowers are
	 * independent switches (either, both or neither), saved with the
	 * preferences. Never one switched on by the other.
	 */
	private void addFamilySwitches(FarmingModel m, FarmingRunType type)
	{
		if (type != FarmingRunType.ALLOTMENT)
		{
			return;
		}
		int allot = 0;
		int flowers = 0;
		for (FarmingModel.Row r : m.rows(type))
		{
			if (r.getPatch().getType() == com.osrstelemetry.plugin.farming.FarmingPatchType.FLOWER)
			{
				flowers++;
			}
			else
			{
				allot++;
			}
		}
		JPanel row = new JPanel(new ReflowGridLayout(2, 4, 0));
		row.setOpaque(false);
		Link a = new Link((draft.isIncludeAllotments() ? "☑ " : "☐ ") + "Allotments (" + allot + ")",
			() -> setFamily(com.osrstelemetry.plugin.farming.FarmingPatchType.ALLOTMENT, !draft.isIncludeAllotments()), SpyglassTheme.TEXT_PRIMARY, true);
		Link f = new Link((draft.isIncludeFlowers() ? "☑ " : "☐ ") + "Flowers (" + flowers + ")",
			() -> setFamily(com.osrstelemetry.plugin.farming.FarmingPatchType.FLOWER, !draft.isIncludeFlowers()), SpyglassTheme.TEXT_PRIMARY, true);
		a.setToolTipText("Include every allotment patch");
		f.setToolTipText("Include every flower patch");
		a.getAccessibleContext().setAccessibleName("Include allotments: " + (draft.isIncludeAllotments() ? "on" : "off"));
		f.getAccessibleContext().setAccessibleName("Include flowers: " + (draft.isIncludeFlowers() ? "on" : "off"));
		row.add(a);
		row.add(f);
		content.add(HomeDetailPage.growing(row));
	}

	private void setFamily(com.osrstelemetry.plugin.farming.FarmingPatchType family, boolean on)
	{
		if (model == null)
		{
			return;
		}
		draft.setInclude(family, on, model.rows(FarmingRunType.ALLOTMENT));
		FarmingPreferences p = prefs();
		writePrefs(family == com.osrstelemetry.plugin.farming.FarmingPatchType.FLOWER ? p.withIncludeFlowers(on) : p.withIncludeAllotments(on));
		structureKey = null;
		render(model);
	}

	// -- preferences --------------------------------------------------------------------------

	private java.util.function.Supplier<FarmingPreferences> prefsReader;
	private java.util.function.Consumer<FarmingPreferences> prefsWriter;
	private FarmingPreferences loadedPrefs;
	private boolean settingsOpen;

	/** The profile's preferences (read every render) and where the player's changes go; optional (tests: defaults, kept in memory). */
	void attachPreferences(java.util.function.Supplier<FarmingPreferences> reader, java.util.function.Consumer<FarmingPreferences> writer)
	{
		this.prefsReader = reader;
		this.prefsWriter = writer;
		this.loadedPrefs = null;
	}

	FarmingPreferences prefs()
	{
		FarmingPreferences p = null;
		try
		{
			p = prefsReader == null ? memoryPrefs : prefsReader.get();
		}
		catch (RuntimeException e)
		{
			// unreadable: defaults
		}
		return p == null ? FarmingPreferences.defaults() : p;
	}

	private FarmingPreferences memoryPrefs;

	private void writePrefs(FarmingPreferences next)
	{
		memoryPrefs = next;
		loadedPrefs = next;
		if (prefsWriter != null)
		{
			try
			{
				prefsWriter.accept(next);
			}
			catch (RuntimeException e)
			{
				// a preference that cannot be saved is not worth failing the click over
			}
		}
	}

	/** Keeps the draft's family switches in step with the saved preferences (another profile, or a change elsewhere). */
	private void syncPrefs()
	{
		FarmingPreferences p = prefs();
		if (!p.equals(loadedPrefs))
		{
			loadedPrefs = p;
			draft.loadFamilies(p.isIncludeAllotments(), p.isIncludeFlowers());
			structureKey = null;
		}
	}

	boolean isSettingsOpen()
	{
		return settingsOpen;
	}

	/** The settings page (the panel's Farming settings route) or the Farming page. */
	void setSettingsOpen(boolean open)
	{
		if (settingsOpen == open)
		{
			return;
		}
		settingsOpen = open;
		structureKey = null;
		if (model != null)
		{
			render(model);
		}
	}

	private Supplier<List<JComponent>> settingsExtras;

	// -- tutorial (canonical §28.18) ------------------------------------------------------------------------------

	private Supplier<FarmingTutorial> tutorialReader;
	private Consumer<FarmingTutorial> tutorialWriter;
	private FarmingTutorial memoryTutorial;
	/** {bank seen, seed vault seen, house seen}: what the account's storage evidence shows (optional). */
	private Supplier<boolean[]> storageSeen;
	private FarmingTutorial.Step tutorialStep;
	private Runnable closeSettings;

	/** The profile's tutorial progress and the storage evidence it credits; without it (tests) there is no tutorial. */
	void attachTutorial(Supplier<FarmingTutorial> reader, Consumer<FarmingTutorial> writer, Supplier<boolean[]> storage)
	{
		attachTutorial(reader, writer, storage, () -> Boolean.FALSE);
	}

	/** {@code ranBefore}: the profile has started a Farming run before (null while not yet known: nothing is decided). */
	void attachTutorial(Supplier<FarmingTutorial> reader, Consumer<FarmingTutorial> writer, Supplier<boolean[]> storage, Supplier<Boolean> ranBefore)
	{
		this.tutorialReader = reader;
		this.tutorialWriter = writer;
		this.storageSeen = storage;
		this.ranBefore = ranBefore;
		structureKey = null;
	}

	private Supplier<Boolean> ranBefore;

	/** Leaves the settings page (the panel's way back). */
	void attachCloseSettings(Runnable close)
	{
		this.closeSettings = close;
	}

	FarmingTutorial tutorial()
	{
		FarmingTutorial t = null;
		try
		{
			t = tutorialReader == null ? memoryTutorial : tutorialReader.get();
		}
		catch (RuntimeException e)
		{
			// unreadable: from the start
		}
		return t == null ? (memoryTutorial == null ? FarmingTutorial.fresh() : memoryTutorial) : t;
	}

	private void writeTutorial(FarmingTutorial next)
	{
		memoryTutorial = next;
		if (tutorialWriter != null)
		{
			try
			{
				tutorialWriter.accept(next);
			}
			catch (RuntimeException e)
			{
				// not saved: shown again next time, never a failure here
			}
		}
	}

	FarmingTutorial.Facts tutorialFacts(FarmingModel m)
	{
		FarmingTutorial.Facts f = new FarmingTutorial.Facts();
		com.osrstelemetry.plugin.farming.FarmingAccountFacts facts = m.getFacts();
		f.accountRead = facts != null && facts.isAccountRead();
		f.farmingLevel = facts == null ? null : facts.getFarmingLevel();
		boolean[] seen = null;
		try
		{
			seen = storageSeen == null ? null : storageSeen.get();
		}
		catch (RuntimeException e)
		{
			// unknown: nothing credited
		}
		f.bankSeen = seen != null && seen.length > 0 && seen[0];
		f.vaultSeen = seen != null && seen.length > 1 && seen[1];
		f.houseSeen = seen != null && seen.length > 2 && seen[2];
		f.houseChosen = prefs().getTravel() == FarmingPreferences.Travel.HOUSE;
		f.typeSelected = selected != null || combineMode && !combined.isEmpty();
		FarmingRunView run = m.getRun();
		f.runOpen = run != null;
		f.preparing = run != null && run.getPhase() == FarmingRunView.Phase.PREPARING;
		f.patchesDone = run == null || run.getProgress() == null ? 0 : run.getProgress().getDone();
		f.runFinished = run != null && run.getPhase() == FarmingRunView.Phase.COMPLETE;
		f.binsOnly = run != null && run.getProgress() != null && run.getProgress().getTotal() == 0 && !run.getIntent().getBins().isEmpty();
		f.binsWorked = run == null ? 0 : com.osrstelemetry.plugin.farming.RunExtras.binsWorked(run.getIntent(), run.getBins());
		try
		{
			f.bankFilterOn = bankFilterState != null && bankFilterState.getAsInt() == 2;
		}
		catch (RuntimeException e)
		{
			f.bankFilterOn = false;
		}
		return f;
	}

	/** Credits what the game shows and picks the one step to show (none without a tutorial store, or once finished). */
	private void syncTutorial(FarmingModel m)
	{
		if (tutorialReader == null || m.getState() != FarmingSnapshot.State.AVAILABLE)
		{
			tutorialStep = null;
			return;
		}
		FarmingTutorial.Facts f = tutorialFacts(m);
		FarmingTutorial t = tutorial();
		if (t.isUndecided())
		{
			// First Farming visit on this profile: a first-time setup starts the tutorial; a profile that has run Farming
			// before has it marked complete (Replay in settings). Unknown yet (runs still loading): decide later.
			Boolean before = null;
			try
			{
				before = ranBefore == null ? Boolean.FALSE : ranBefore.get();
			}
			catch (RuntimeException e)
			{
				// unknown
			}
			if (before == null)
			{
				tutorialStep = null;
				return;
			}
			t = t.decide(before || m.getRun() != null, m.getNow());
			writeTutorial(t);
		}
		FarmingTutorial next = t.withEvidence(f);
		if (next != t)
		{
			writeTutorial(next);
		}
		tutorialStep = next.current(f);
		if (tutorialStep != null && !tutorialStep.isSetup() && startProblems.size() == 1 && startProblems.get(0).startsWith("Finish Farming setup"))
		{
			startProblems = new ArrayList<>();
		}
	}

	FarmingTutorial.Step tutorialStep()
	{
		return tutorialStep;
	}

	/** The one instruction now: where it sits in the tutorial, what to do, and Skip (or Got it); run tips can be hidden. */
	private JComponent tutorialCard(FarmingModel m)
	{
		FarmingTutorial.Facts f = tutorialFacts(m);
		FarmingTutorial.Step step = tutorialStep;
		int[] pos = FarmingTutorial.position(step, f);
		RaisedTile tile = new RaisedTile(null, 5, 7);
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel kicker = small((step.isSetup() ? "FARMING SETUP" : "FARMING TIPS") + " · " + pos[0] + " of " + pos[1], SpyglassTheme.ACCENT_GOLD);
		kicker.setFont(SpyglassFonts.smallBold());
		JLabel title = new JLabel(step.titleFor(f));
		title.setFont(SpyglassFonts.bold());
		title.setForeground(SpyglassTheme.TEXT_PRIMARY);
		JComponent detail = wrap(step.detailFor(f), SpyglassTheme.TEXT_SECONDARY);
		JComponent skipHint = step.getSkipHint() == null ? null : wrap(step.getSkipHint(), SpyglassTheme.TILE_TEXT_QUIET);
		JPanel links = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		links.setOpaque(false);
		Link primary = new Link(step.isSkippable() ? "Skip" : "Got it", () ->
		{
			FarmingTutorial.Facts now = tutorialFacts(model);
			writeTutorial(step.isSkippable() ? tutorial().withSkipped(step, now) : tutorial().withAcknowledged(step, now));
			startProblems = new ArrayList<>();
			structureKey = null;
			render(model);
		}, SpyglassTheme.ACCENT_GOLD, true);
		primary.setName("tutorial.next");
		primary.setToolTipText(!step.isSkippable() ? "Next" : step.getSkipHint() != null ? step.getSkipHint() : "Skip this tip");
		links.add(primary);
		if (!step.isSetup())
		{
			JLabel dot = small("   ·   ", SpyglassTheme.TEXT_MUTED);
			links.add(dot);
			Link hide = new Link("Hide tips", () ->
			{
				writeTutorial(tutorial().withFinished());
				structureKey = null;
				render(model);
			}, SpyglassTheme.TEXT_SECONDARY, true);
			hide.setName("tutorial.hide");
			hide.setToolTipText("Replay them any time from Farming settings");
			links.add(hide);
		}
		for (JComponent c : new JComponent[]{kicker, title, detail, skipHint, links})
		{
			if (c == null)
			{
				continue;
			}
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(c);
		}
		tile.add(body, BorderLayout.CENTER);
		tile.getAccessibleContext().setAccessibleName("Farming tip " + pos[0] + " of " + pos[1] + ": " + step.titleFor(f) + ". " + step.detailFor(f));
		tile.setAlignmentX(Component.LEFT_ALIGNMENT);
		return fullWidth(tile);
	}

	/** Further settings cards (the tutorial's replay), appended after Farming's own. */
	void attachSettingsExtras(Supplier<List<JComponent>> extras)
	{
		this.settingsExtras = extras;
	}

	/**
	 * Farming settings: choices that change what a run needs, in three cards in the plugin's own style (brown tiles, a gold
	 * rail and heading). Tree clearing offers the choice only where the game gives both ways (regular, fruit and hardwood
	 * trees: pay the gardener 200 coins, or chop and dig up the stump); a redwood is only ever removed by paying Alexandra
	 * 2,000 coins; the other families are cleared by hand, their own way shown at the patch. Explanations are tooltips,
	 * not footer lines. Each choice applies to unfinished work only.
	 */
	JComponent buildSettings()
	{
		FarmingPreferences p = prefs();
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setOpaque(false);
		box.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));

		List<JComponent> travel = new ArrayList<>();
		for (FarmingPreferences.Travel t : FarmingPreferences.Travel.values())
		{
			boolean on = p.getTravel() == t;
			boolean house = t == FarmingPreferences.Travel.HOUSE;
			Link l = new Link((on ? "◉ " : "○ ") + t.getLabel(), () -> writeAndRender(prefs().withTravel(t)),
				on ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY, true);
			l.setName("settings.travel." + t.name());
			l.setToolTipText(house ? "<html>Go through your house (a portal, the portal nexus, a jewellery box, a mounted item) whenever a house"
				+ " journey<br>you can use exists, so you need not carry the matching jewellery. Only facilities Spyglass has seen in your house"
				+ "<br>count, with a way in (tablet, cape or the spell). Otherwise the fastest way you can use.</html>"
				: t == FarmingPreferences.Travel.CURRENT ? "<html>Only journeys your setup already allows: your current spellbook (no journey whose"
				+ " spell, or any spell on the way,<br>is on another book), teleport items you own and house facilities Spyglass has seen."
				+ " Items or runes<br>in storage are still listed to withdraw.</html>"
				: "<html>The quickest journey Spyglass knows you can use, whatever it needs carried. A spell on another"
				+ "<br>spellbook is never planned; preparation names it as needing a spellbook change.</html>");
			l.getAccessibleContext().setAccessibleName("Travel: " + t.getLabel() + (on ? ", selected" : ""));
			travel.add(l);
		}
		box.add(settingsCard("TRAVEL", "How runs choose the way to each stop. Applies to stops still ahead.", travel));
		box.add(Box.createVerticalStrut(CARD_GAP));

		List<JComponent> trees = new ArrayList<>();
		trees.add(smallWrap("Regular, fruit and hardwood trees", SpyglassTheme.TEXT_SECONDARY));
		for (FarmingPreferences.TreeClearing c : FarmingPreferences.TreeClearing.values())
		{
			boolean on = p.getTreeClearing() == c;
			boolean pay = c == FarmingPreferences.TreeClearing.PAY;
			String text = (on ? "◉ " : "○ ") + (pay ? "Pay the gardener · " + FarmingPreferences.REMOVAL_COINS + " coins" : "Chop down, dig up the stump");
			Link l = new Link(text, () -> writeAndRender(prefs().withTreeClearing(c)), on ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY, true);
			l.setToolTipText(pay ? "That patch's gardener removes the grown tree. The coins are counted in preparation."
				: "Chop the tree down with an axe, then dig up the stump with a spade.");
			l.getAccessibleContext().setAccessibleName("Clear grown trees: " + c.getLabel() + (on ? ", selected" : ""));
			trees.add(l);
		}
		JLabel redwood = smallWrap("Redwood · Pay Alexandra " + String.format(java.util.Locale.ROOT, "%,d", FarmingPreferences.REDWOOD_REMOVAL_COINS) + " coins",
			SpyglassTheme.TEXT_MUTED);
		redwood.setToolTipText("A grown redwood can only be removed by paying Alexandra.");
		trees.add(redwood);
		box.add(settingsCard("TREE CLEARING", "Applies to trees not yet cleared. Calquat, spirit, celastrus and crystal trees are cleared by hand;"
			+ " the way for each is shown at the patch.", trees));
		box.add(Box.createVerticalStrut(CARD_GAP));

		Link fruit = new Link((p.isPickFruitFirst() ? "☑ " : "☐ ") + "Pick fruit before clearing",
			() -> writeAndRender(prefs().withPickFruitFirst(!prefs().isPickFruitFirst())), SpyglassTheme.TEXT_PRIMARY, true);
		fruit.setToolTipText("At a fruit tree being replaced, pick the fruit still on it first.");
		fruit.getAccessibleContext().setAccessibleName("Pick fruit before clearing: " + (p.isPickFruitFirst() ? "on" : "off"));
		box.add(settingsCard("FRUIT HARVESTING", null, java.util.Collections.singletonList(fruit)));
		box.add(Box.createVerticalStrut(CARD_GAP));

		JPanel grid = new JPanel(new ReflowGridLayout(2, 6, 1));
		grid.setOpaque(false);
		for (FarmingRunType t : FarmingRunType.values())
		{
			if (!t.offersProtection())
			{
				continue;
			}
			boolean on = p.isProtect(t);
			String name = t == FarmingRunType.ALLOTMENT ? "Allotments" : t.getTitle();
			Link l = new Link((on ? "☑ " : "☐ ") + name, () -> writeAndRender(prefs().withProtect(t, !prefs().isProtect(t))),
				on ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY, true);
			l.setToolTipText("Pay the " + name.toLowerCase(java.util.Locale.ROOT) + " gardener to protect what you plant.");
			l.getAccessibleContext().setAccessibleName("Protect " + name + ": " + (on ? "on" : "off"));
			grid.add(l);
		}
		box.add(settingsCard("CROP PROTECTION", "Payments follow the planting materials you carry. A crop counts as protected only once the game"
			+ " confirms the payment.", java.util.Collections.singletonList(grid)));
		if (tutorialReader != null)
		{
			Link replay = new Link("Replay the Farming tutorial", () ->
			{
				writeTutorial(tutorial().replay());
				structureKey = null;
				if (closeSettings != null)
				{
					closeSettings.run();
				}
			}, SpyglassTheme.TEXT_PRIMARY, true);
			replay.setName("settings.tutorial.replay");
			replay.setToolTipText("Shows the run tips again, one at a time. What Spyglass already knows stays credited.");
			box.add(Box.createVerticalStrut(CARD_GAP));
			box.add(settingsCard("TUTORIAL", null, java.util.Collections.singletonList(replay)));
		}
		if (settingsExtras != null)
		{
			for (JComponent extra : settingsExtras.get())
			{
				box.add(Box.createVerticalStrut(CARD_GAP));
				box.add(extra);
			}
		}
		return HomeDetailPage.growing(box); // its height follows the wrapped choices (pass 30: larger text sizes)
	}

	// -- compost bins (canonical §28.15) -----------------------------------------------------------------------

	private String compostSignature;

	/** Bins with a task now (pips the Compost bins button), whatever their inclusion. */
	static int binsWithWork(FarmingModel m)
	{
		int n = 0;
		for (com.osrstelemetry.plugin.farming.CompostBin.Def d : com.osrstelemetry.plugin.farming.CompostBin.ALL)
		{
			com.osrstelemetry.plugin.farming.CompostBin.Seen s = m.getBins().get(d.getBinId());
			if (s != null && com.osrstelemetry.plugin.farming.CompostBin.task(d, s, com.osrstelemetry.plugin.farming.CompostBin.Upgrade.NONE,
				m.getNow()) != null)
			{
				n++;
			}
		}
		return n;
	}

	/** Every bin card's lines and the patch compost lines: the page is rebuilt only when one changes. */
	private String compostSignature(FarmingModel m)
	{
		StringBuilder b = new StringBuilder();
		FarmingPreferences p = prefs();
		for (com.osrstelemetry.plugin.farming.CompostBin.Def d : com.osrstelemetry.plugin.farming.CompostBin.ALL)
		{
			b.append(String.join("/", binLines(d, m.getBins().get(d.getBinId()), p.binUpgrade(d.getBinId()), m.getNow()))).append(';');
		}
		for (FarmingModel.CompostLine l : m.getCompostLines())
		{
			b.append(compostLineText(l)).append(';');
		}
		return b.toString();
	}

	/** A bin card's state line and its next step (null: nothing to do there now). Never seen: said so, never "Empty". */
	static String[] binLines(com.osrstelemetry.plugin.farming.CompostBin.Def d, com.osrstelemetry.plugin.farming.CompostBin.Seen s,
		com.osrstelemetry.plugin.farming.CompostBin.Upgrade up, long now)
	{
		if (s == null)
		{
			return new String[]{"Not seen yet", null};
		}
		com.osrstelemetry.plugin.farming.CompostBin.State st = s.state(d);
		long age = Math.max(0, now - s.getAt());
		String when = age < 60_000L ? "just now" : com.osrstelemetry.plugin.farming.FarmingText.age(age) + " ago";
		String state = st == null ? "Not recognised" : com.osrstelemetry.plugin.farming.CompostBin.describe(d, st);
		if (st != null && st.phase == com.osrstelemetry.plugin.farming.CompostBin.Phase.ROTTED)
		{
			state = st.contents.getLabel() + " rotted";
		}
		com.osrstelemetry.plugin.farming.CompostBin.Task t = com.osrstelemetry.plugin.farming.CompostBin.task(d, s, up, now);
		return new String[]{state + " · " + when, t == null ? null : t.label};
	}

	/** "Herb · 4 ultra · 1 not treated · 2 not read" (planted patches only). */
	static String compostLineText(FarmingModel.CompostLine l)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<com.osrstelemetry.plugin.farming.PatchCompost.Kind, Integer> e : l.getTreated().entrySet())
		{
			parts.add(e.getValue() + " " + e.getKey().getStepLabel().toLowerCase(java.util.Locale.ROOT));
		}
		if (l.getUntreated() > 0)
		{
			parts.add(l.getUntreated() + " not treated");
		}
		if (l.getUnread() > 0)
		{
			parts.add(l.getUnread() + " not read");
		}
		return l.getType().getTitle() + " · " + String.join(" · ", parts);
	}

	private static final String UPGRADE_TIP = "<html>What runs make of this bin's compost: as rotted; supercompost (a dose of compost potion"
		+ " on compost);<br>or ultracompost (then volcanic ash on supercompost). Click to change.</html>";

	/** Compost bins on their own: the bins (include, make, state, next step), compost on the patches, and a bins-only start. */
	private void buildCompost(FarmingModel m)
	{
		compostSignature = compostSignature(m);
		content.add(sectionHeader("COMPOST BINS", null));
		int in = prefs().getRunBins().size();
		content.add(fullWidth(smallWrap(in == 0 ? "Tick the bins to work in a run" : in + (in == 1 ? " bin" : " bins") + " in runs",
			SpyglassTheme.TEXT_SECONDARY)));
		content.add(Box.createVerticalStrut(6));
		addBinCards(m);
		content.add(Box.createVerticalStrut(8));
		List<com.osrstelemetry.plugin.farming.FarmingPatchType> types = new ArrayList<>();
		for (FarmingRunType t : m.trackedTypes())
		{
			types.addAll(compostTypes(t));
		}
		addCompostEditor(m, types);
		content.add(Box.createVerticalStrut(8));
		FarmingRunView run = m.getRun();
		if (run != null)
		{
			buildStart(m, FarmingRunType.COMPOST);
			return;
		}
		startButton = new ActionButton(combineMode ? START_COMBINED_LABEL : START_LABEL, ActionButton.Style.PRIMARY, this::startRun);
		startButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		startButton.getAccessibleContext().setAccessibleName("Start a compost bin run");
		content.add(startButton);
		startHint = new WrappingHtmlLabel(" ", false);
		startHint.setFont(SpyglassFonts.small());
		startHint.setForeground(SpyglassTheme.TEXT_MUTED);
		startHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(Box.createVerticalStrut(4));
		content.add(startHint);
		setStartEnabled(in > 0);
		startHint.setPlainText(in > 0 ? "A run of the ticked bins. To add crops, combine run types." : "Tick at least one bin to start.");
		for (String problem : startProblems)
		{
			content.add(wrap(problem, SpyglassTheme.FARM_EMBER));
		}
	}

	// -- compost choices (pass 31) ------------------------------------------------------------------------------------------

	/** The patch types a run type's compost choices cover (Allotments &amp; Flowers: both, apart). */
	static List<com.osrstelemetry.plugin.farming.FarmingPatchType> compostTypes(FarmingRunType t)
	{
		List<com.osrstelemetry.plugin.farming.FarmingPatchType> out = new ArrayList<>();
		if (t != null && t.isCrop())
		{
			out.add(t.getPrimary());
			out.addAll(t.getCompanions());
		}
		return out;
	}

	/** The item each compost choice shows (the game's own buckets; Fertile Soil: the astral rune it is cast with). */
	static int compostIcon(FarmingPlanDraft.Compost c)
	{
		switch (c)
		{
			case COMPOST:
				return ItemID.BUCKET_COMPOST;
			case SUPERCOMPOST:
				return ItemID.BUCKET_SUPERCOMPOST;
			case ULTRACOMPOST:
				return ItemID.BUCKET_ULTRACOMPOST;
			case BOTTOMLESS:
				return ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED;
			case FERTILE_SOIL:
				return ItemID.ASTRALRUNE;
			case NONE:
			default:
				return ItemID.BUCKET_EMPTY;
		}
	}

	/** How it is applied, in a few words: "Use Ultracompost (bucket)", "Use the bottomless bucket", "Cast Fertile Soil". */
	static String compostMethod(FarmingPlanDraft.Compost c)
	{
		switch (c)
		{
			case COMPOST:
			case SUPERCOMPOST:
			case ULTRACOMPOST:
				return "Use " + c.getLabel() + " (bucket)";
			case BOTTOMLESS:
				return "Use the bottomless bucket";
			case FERTILE_SOIL:
				return "Cast Fertile Soil";
			case NONE:
			default:
				return "No compost";
		}
	}

	static String plural(com.osrstelemetry.plugin.farming.FarmingPatchType t)
	{
		String n = t.getDisplayName();
		if (n.endsWith("s") || n.equals("Seaweed") || n.equals("Anima") || n.equals("Hespori") || n.equals("Belladonna"))
		{
			return n;
		}
		if (n.endsWith("sh"))
		{
			return n + "es";
		}
		if (n.equals("Cactus"))
		{
			return "Cacti";
		}
		return n + "s";
	}

	/** "Last observed: 9 ultracomposted patches · 1 not treated" from the game's own words (never the choice). */
	static String observedText(FarmingModel.CompostLine l)
	{
		if (l == null)
		{
			return "Last observed: none planted that Spyglass has seen";
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<com.osrstelemetry.plugin.farming.PatchCompost.Kind, Integer> e : l.getTreated().entrySet())
		{
			int n = e.getValue();
			parts.add(n + " " + e.getKey().getLabel().toLowerCase(java.util.Locale.ROOT).replace("compost", "composted") + (n == 1 ? " patch" : " patches"));
		}
		if (l.getUntreated() > 0)
		{
			parts.add(l.getUntreated() + " not treated");
		}
		if (l.getUnread() > 0)
		{
			parts.add(l.getUnread() + " not read");
		}
		return "Last observed: " + (parts.isEmpty() ? "nothing yet" : String.join(" · ", parts));
	}

	/**
	 * COMPOST ON PATCHES as an editor (pass 31): one row per patch type, its saved choice with the native bucket (or rune)
	 * and how it is applied, and what the game last showed. A click opens a compact picker. The choice is a plan, saved per
	 * patch type and profile; it never marks a patch composted.
	 */
	private void addCompostEditor(FarmingModel m, List<com.osrstelemetry.plugin.farming.FarmingPatchType> types)
	{
		content.add(sectionHeader("COMPOST ON PATCHES", null));
		content.add(Box.createVerticalStrut(3));
		if (types.isEmpty())
		{
			content.add(wrap("Nothing planted that Spyglass has seen yet.", SpyglassTheme.TEXT_MUTED));
			return;
		}
		JPanel list = growingPanel();
		boolean first = true;
		FarmingPreferences p = prefs();
		for (com.osrstelemetry.plugin.farming.FarmingPatchType t : types)
		{
			if (!first)
			{
				list.add(Box.createVerticalStrut(CARD_GAP));
			}
			first = false;
			list.add(compostRow(t, p.compostFor(t), m.compostObserved(t)));
		}
		content.add(list);
	}

	private JComponent compostRow(com.osrstelemetry.plugin.farming.FarmingPatchType type, FarmingPlanDraft.Compost choice, FarmingModel.CompostLine seen)
	{
		RaisedTile[] holder = new RaisedTile[1];
		RaisedTile tile = new RaisedTile(() -> compostPicker(type, holder[0]), 4, 6);
		holder[0] = tile;
		tile.setName("compost." + type.name());
		tile.setLayout(new BorderLayout(6, 0));
		CropIcon icon = new CropIcon(sprites);
		icon.show(compostIcon(choice), choice.getLabel());
		JPanel iconColumn = new JPanel(new BorderLayout());
		iconColumn.setOpaque(false);
		iconColumn.add(icon, BorderLayout.NORTH);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel title = smallWrap(plural(type) + " · " + compostMethod(choice) + " ▾", choice == FarmingPlanDraft.Compost.NONE
			? SpyglassTheme.TEXT_SECONDARY : SpyglassTheme.TEXT_PRIMARY);
		title.setFont(SpyglassFonts.bold());
		JLabel under = smallWrap(observedText(seen), SpyglassTheme.TILE_TEXT_QUIET);
		for (JComponent c : new JComponent[]{title, under})
		{
			JPanel cell = new JPanel(new BorderLayout());
			cell.setOpaque(false);
			cell.add(c, BorderLayout.CENTER);
			cell.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(cell);
		}
		tile.add(iconColumn, BorderLayout.WEST);
		tile.add(body, BorderLayout.CENTER);
		tile.getAccessibleContext().setAccessibleName(plural(type) + ": " + compostMethod(choice) + ". " + observedText(seen) + ". Opens the compost choices");
		return HomeDetailPage.growing(tile);
	}

	/** The compact picker: every choice with its native icon, the saved one marked. */
	private void compostPicker(com.osrstelemetry.plugin.farming.FarmingPatchType type, JComponent anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		FarmingPlanDraft.Compost now = prefs().compostFor(type);
		javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
		for (FarmingPlanDraft.Compost c : FarmingPlanDraft.Compost.values())
		{
			javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(compostMethod(c), c == now);
			BufferedImage img = sprites == null ? null : sprites.load(compostIcon(c), () -> SwingUtilities.invokeLater(menu::repaint));
			if (img != null)
			{
				item.setIcon(new javax.swing.ImageIcon(img));
			}
			item.setFont(c == now ? SpyglassFonts.bold() : SpyglassFonts.regular());
			item.setName("compost.choice." + c.name());
			item.addActionListener(e -> chooseCompost(type, c));
			group.add(item);
			menu.add(item);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/** A compost choice: saved per patch type and profile; an open run's pending work follows (preparation recalculates). */
	void chooseCompost(com.osrstelemetry.plugin.farming.FarmingPatchType type, FarmingPlanDraft.Compost choice)
	{
		writePrefs(prefs().withCompost(type, choice));
		if (model != null && model.getRun() != null && runActions != null)
		{
			runActions.setCompost(type, choice);
		}
		structureKey = null;
		if (runActions != null)
		{
			runActions.refresh();
		}
		else if (model != null)
		{
			render(model);
		}
	}

	/**
	 * Once per profile (pass 31): the compost chosen for the open run, before choices were saved per patch type, becomes the
	 * saved choice, so nothing the player chose is reset by the update.
	 */
	private void carryCompostOver(FarmingModel m)
	{
		FarmingPreferences p = prefs();
		FarmingRunView run = m.getRun();
		if (p.isCompostChosen() || run == null || run.getIntent() == null || run.getIntent().getOptions() == null)
		{
			return;
		}
		Map<com.osrstelemetry.plugin.farming.FarmingPatchType, FarmingPlanDraft.Compost> seed = new java.util.EnumMap<>(
			com.osrstelemetry.plugin.farming.FarmingPatchType.class);
		for (FarmingRunType t : run.getIntent().getRunTypes())
		{
			for (com.osrstelemetry.plugin.farming.FarmingPatchType pt : compostTypes(t))
			{
				String c = run.getIntent().getOptions().compostFor(pt);
				try
				{
					seed.put(pt, c == null ? FarmingPlanDraft.Compost.NONE : FarmingPlanDraft.Compost.valueOf(c));
				}
				catch (IllegalArgumentException e)
				{
					// an unknown name: left unset
				}
			}
		}
		writePrefs(p.withCompost(seed));
	}

	/** One card per bin: include tick and name, what to make, the game's last word on it, and its next step. */
	private void addBinCards(FarmingModel m)
	{
		FarmingPreferences p = prefs();
		JPanel list = growingPanel();
		boolean first = true;
		for (com.osrstelemetry.plugin.farming.CompostBin.Def d : com.osrstelemetry.plugin.farming.CompostBin.ALL)
		{
			if (!first)
			{
				list.add(Box.createVerticalStrut(CARD_GAP));
			}
			first = false;
			list.add(binCard(d, m.getBins().get(d.getBinId()), p, m.getNow()));
		}
		content.add(list);
	}

	private JComponent binCard(com.osrstelemetry.plugin.farming.CompostBin.Def d, com.osrstelemetry.plugin.farming.CompostBin.Seen s,
		FarmingPreferences p, long now)
	{
		boolean on = p.isRunBin(d.getBinId());
		com.osrstelemetry.plugin.farming.CompostBin.Upgrade up = p.binUpgrade(d.getBinId());
		String[] lines = binLines(d, s, up, now);
		RaisedTile tile = new RaisedTile(null, 4, 6);
		tile.setRail(on && lines[1] != null ? SpyglassTheme.ACCENT_GOLD : null);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);

		Link include = new Link((on ? "☑ " : "☐ ") + d.getPlace(), () -> writeAndRender(prefs().withRunBin(d.getBinId(), !prefs().isRunBin(d.getBinId()))),
			on ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY, true);
		include.setFont(SpyglassFonts.regular());
		include.wrapping();
		include.setName("bin.include." + d.getBinId());
		include.setToolTipText("Include this bin when a run takes compost bins");
		include.getAccessibleContext().setAccessibleName(d.getPlace() + " compost bin: in runs " + (on ? "on" : "off") + ". " + lines[0]
			+ (lines[1] == null ? "" : ". Next: " + lines[1]));
		String upText = up == com.osrstelemetry.plugin.farming.CompostBin.Upgrade.NONE ? "Rotted"
			: up == com.osrstelemetry.plugin.farming.CompostBin.Upgrade.SUPER ? "Super" : "Ultra";
		Link make = new Link(upText + " ▾", () ->
		{
			com.osrstelemetry.plugin.farming.CompostBin.Upgrade[] all = com.osrstelemetry.plugin.farming.CompostBin.Upgrade.values();
			writeAndRender(prefs().withBinUpgrade(d.getBinId(), all[(prefs().binUpgrade(d.getBinId()).ordinal() + 1) % all.length]));
		}, SpyglassTheme.TEXT_SECONDARY, true);
		make.setName("bin.make." + d.getBinId());
		make.setToolTipText(UPGRADE_TIP.replace("then volcanic ash", "then " + d.ash() + " volcanic ash").replace("What runs make", "Make: what runs make"));
		make.getAccessibleContext().setAccessibleName(d.getPlace() + " compost bin: make " + upText + ". Change");
		// Pass 31: beside the bin's picture the name and the product are stacked (never squeezed onto one line).
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(include, BorderLayout.CENTER);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(top);
		JPanel makeRow = new JPanel(new BorderLayout());
		makeRow.setOpaque(false);
		makeRow.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
		makeRow.add(make, BorderLayout.WEST);
		makeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel state = smallWrap(lines[0], s == null ? SpyglassTheme.TEXT_MUTED : SpyglassTheme.TEXT_SECONDARY);
		state.setBorder(BorderFactory.createEmptyBorder(1, 14, 0, 0));
		if (s == null)
		{
			state.setToolTipText("Spyglass reads a bin when you are near it. Until then its contents are unknown, not empty.");
		}
		state.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(state);
		body.add(makeRow);
		if (lines[1] != null)
		{
			JComponent next = wrap("▸ " + lines[1], on ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_SECONDARY);
			next.setBorder(BorderFactory.createEmptyBorder(1, 14, 0, 0));
			next.setToolTipText("The bin's next step. In a run it is optional: do it or skip it.");
			next.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(next);
		}
		tile.setLayout(new BorderLayout(6, 0));
		BinPicture picture = new BinPicture(s == null ? null : com.osrstelemetry.plugin.farming.CompostBinLook.of(d, s.state(d)), binPictures,
			s == null ? "Not seen yet" : lines[0]);
		picture.setName("bin.picture." + d.getBinId());
		JPanel pictureColumn = new JPanel(new BorderLayout());
		pictureColumn.setOpaque(false);
		pictureColumn.add(picture, BorderLayout.NORTH);
		tile.add(pictureColumn, BorderLayout.WEST);
		tile.add(body, BorderLayout.CENTER);
		tile.setAlignmentX(Component.LEFT_ALIGNMENT);
		return fullWidth(tile);
	}

	private CompostBinPictures binPictures;

	/** The bins' pictures from the game's models (client); without them the cards keep their neutral mark. */
	void setBinPictures(CompostBinPictures pictures)
	{
		this.binPictures = pictures;
		structureKey = null;
		if (runPage != null)
		{
			runPage.setBinPictures(pictures); // pass 32: the run's bin cards use the same drawings
		}
	}

	/**
	 * A bin's picture (pass 31): the game's own bin drawn in its compost state (empty and open, filling, closed and rotting, open
	 * and full when the compost is ready), or a neutral mark for a bin never seen (never an empty bin).
	 */
	static final class BinPicture extends JComponent
	{
		final com.osrstelemetry.plugin.farming.CompostBinLook look;
		private BufferedImage image;

		BinPicture(com.osrstelemetry.plugin.farming.CompostBinLook look, CompostBinPictures pictures, String label)
		{
			this.look = look;
			setOpaque(false);
			Dimension d = new Dimension(ItemIcon.WIDTH, ItemIcon.HEIGHT);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
			getAccessibleContext().setAccessibleName(label);
			image = look == null || pictures == null ? null : pictures.picture(look, () ->
			{
				image = pictures.picture(look, null);
				repaint();
			});
		}

		/** For the harness: a picture drawn elsewhere. */
		void setImage(BufferedImage img)
		{
			image = img;
			repaint();
		}

		boolean isUnknown()
		{
			return look == null;
		}

		@Override
		public AccessibleContext getAccessibleContext()
		{
			if (accessibleContext == null)
			{
				accessibleContext = new AccessibleJComponent()
				{
					@Override
					public AccessibleRole getAccessibleRole()
					{
						return AccessibleRole.ICON;
					}
				};
			}
			return accessibleContext;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (image != null)
			{
				g.drawImage(image, (getWidth() - ItemIcon.WIDTH) / 2, (getHeight() - ItemIcon.HEIGHT) / 2, null);
				return;
			}
			// Not seen (or not drawn yet): a quiet dashed square with a question mark, never a bin.
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			int w = 24;
			int x = (getWidth() - w) / 2;
			int y = (getHeight() - w) / 2;
			g2.setColor(SpyglassTheme.TEXT_MUTED);
			g2.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 2f}, 0f));
			g2.drawRoundRect(x, y, w, w, 6, 6);
			if (look == null)
			{
				g2.setFont(SpyglassFonts.bold());
				java.awt.FontMetrics fm = g2.getFontMetrics();
				g2.drawString("?", x + (w - fm.stringWidth("?")) / 2 + 1, y + (w + fm.getAscent()) / 2 - 2);
			}
			g2.dispose();
		}
	}

	/** Settings choices wrap rather than being cut short at a large text size. */
	private static void wrapLinks(Component c)
	{
		if (c instanceof Link)
		{
			((Link) c).wrapping();
		}
		else if (c instanceof java.awt.Container)
		{
			for (Component k : ((java.awt.Container) c).getComponents())
			{
				wrapLinks(k);
			}
		}
	}

	/** One settings card: a brown tile with a gold rail and heading (its tooltip explains), then its controls. */
	private JComponent settingsCard(String title, String tip, List<? extends JComponent> rows)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel head = new WrappingHtmlLabel(title, false);
		head.setFont(SpyglassFonts.smallBold());
		head.setForeground(SpyglassTheme.ACCENT_GOLD);
		head.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		if (tip != null)
		{
			head.setToolTipText(tip);
			tile.setToolTipText(tip);
		}
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(head);
		for (JComponent r : rows)
		{
			r.setAlignmentX(Component.LEFT_ALIGNMENT);
			wrapLinks(r);
			body.add(r instanceof JPanel ? HomeDetailPage.growing(r) : r); // heights follow the width and text size
		}
		tile.add(body, BorderLayout.CENTER);
		return fullWidth(tile);
	}

	private void writeAndRender(FarmingPreferences next)
	{
		writePrefs(next);
		structureKey = null;
		if (runActions != null)
		{
			runActions.refresh();
		}
		else if (model != null)
		{
			render(model);
		}
	}

	private PatchCardView card(FarmingModel.Row r, FarmingModel m)
	{
		String id = r.getPatchId();
		PatchCardView v = new PatchCardView(id, sprites, include -> onInclude(id, include), this::openPlanMenu, this::onExpand);
		v.setExpanded(expanded.contains(id));
		v.update(r, draft.isIncluded(r, m.getNow()), plannedName(r, m));
		rowViews.put(id, v);
		return v;
	}

	/** Types whose locked / unverified section is open (view state). */
	private final java.util.Set<FarmingRunType> lockedOpen = java.util.EnumSet.noneOf(FarmingRunType.class);

	/**
	 * Patches this account can't be shown to use: collapsed under a counted
	 * header. Locked (a requirement evidenced as unmet) and Not confirmed
	 * (not evidenced yet) read differently; neither is ever in a run.
	 */
	private void addLockedSection(FarmingModel m, FarmingRunType type)
	{
		List<FarmingModel.Excluded> ex = m.excluded(type);
		if (ex.isEmpty())
		{
			return;
		}
		boolean open = lockedOpen.contains(type);
		content.add(Box.createVerticalStrut(4));
		Link head = new Link((open ? "▾ " : "▸ ") + "Locked / unverified patches (" + ex.size() + ")", () ->
		{
			if (!lockedOpen.remove(type))
			{
				lockedOpen.add(type);
			}
			structureKey = null;
			render(model);
		}, SpyglassTheme.TEXT_SECONDARY, true);
		head.setToolTipText("Patches Spyglass can't confirm you can use yet. They are left out of runs.");
		content.add(fullWidth(head));
		if (open)
		{
			for (FarmingModel.Excluded e : ex)
			{
				boolean locked = e.getAccess().getState() == com.osrstelemetry.plugin.farming.FarmingAccess.Access.State.LOCKED;
				List<String> reasons = new ArrayList<>();
				for (String r : e.getAccess().getReasons())
				{
					reasons.add(r.replace(": no evidence available", "").replace(" (no evidence available)", ""));
				}
				String why = String.join("; ", reasons);
				String text = e.getRow().getLocation() + " \u00b7 " + (locked ? "Locked" : "Not confirmed") + (why.isEmpty() ? "" : ": " + why);
				JComponent line = wrap(text, locked ? SpyglassTheme.TEXT_MUTED : SpyglassTheme.TEXT_SECONDARY);
				line.setBorder(BorderFactory.createEmptyBorder(1, 12, 1, 0));
				content.add(line);
			}
		}
	}

	private void buildPreview(FarmingModel m, FarmingRunType type)
	{
		content.add(sectionHeader(type.getTitle().toUpperCase() + " PATCHES", null));
		summaryLabel = smallWrap(" ", SpyglassTheme.TEXT_SECONDARY);
		content.add(fullWidth(summaryLabel));
		untrackedLabel = small(" ", SpyglassTheme.TEXT_MUTED);
		content.add(fullWidth(untrackedLabel));
		content.add(Box.createVerticalStrut(6));

		addTopStart(m);
		// Individual cards on the recessed canvas: the gap and each card's own
		// stepped shadow separate them (no continuous list face, no dividers).
		addFamilySwitches(m, type);
		JPanel list = growingPanel();
		boolean companionsStarted = false;
		boolean first = true;
		for (FarmingModel.Row r : m.rows(type))
		{
			if (r.isCompanion() && !companionsStarted)
			{
				companionsStarted = true;
				JLabel sub = small("FLOWER PATCHES", SpyglassTheme.TILE_TEXT_QUIET);
				sub.setBorder(BorderFactory.createEmptyBorder(8, 2, 3, 2));
				list.add(fullWidth(sub));
				first = true;
			}
			if (!first)
			{
				list.add(Box.createVerticalStrut(CARD_GAP));
			}
			first = false;
			list.add(card(r, m));
		}
		content.add(list);
		addLockedSection(m, type);
		content.add(Box.createVerticalStrut(8));
		addCompostEditor(m, compostTypes(type));
		content.add(Box.createVerticalStrut(6));

		Link options = new Link(optionsOpen ? "Options ▾" : "Options ▸", () ->
		{
			optionsOpen = !optionsOpen;
			render(model);
		});
		content.add(fullWidth(options));
		if (optionsOpen)
		{
			content.add(buildOptions(type));
		}
		content.add(Box.createVerticalStrut(4));
		scopeLabel = smallWrap(" ", SpyglassTheme.TEXT_PRIMARY);
		content.add(fullWidth(scopeLabel));
		content.add(Box.createVerticalStrut(8));
		buildStart(m, type);
		updateSummaries(m);
	}

	/**
	 * Start Run (canonical §4), or "Return to run" while a run exists (no
	 * concurrent runs; an open run is never replaced). Disabled only when the
	 * scope is empty; anything else that stops the start is explained in
	 * plain words under the button.
	 */
	private void buildStart(FarmingModel m, FarmingRunType type)
	{
		FarmingRunView run = m.getRun();
		if (run != null)
		{
			String what = run.getPhase() == FarmingRunView.Phase.COMPLETE ? run.getRunTitle() + " run finished"
				: run.getRunTitle() + " run in progress";
			content.add(fullWidth(small(what, SpyglassTheme.TEXT_SECONDARY)));
			content.add(Box.createVerticalStrut(4));
			ActionButton back = new ActionButton("Return to run", ActionButton.Style.PRIMARY, this::returnToRun);
			back.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(back);
			return;
		}
		startButton = new ActionButton(combineMode ? START_COMBINED_LABEL : START_LABEL, ActionButton.Style.PRIMARY, this::startRun);
		startButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(startButton);
		startHint = new WrappingHtmlLabel(" ", false);
		startHint.setFont(SpyglassFonts.small());
		startHint.setForeground(SpyglassTheme.TEXT_MUTED);
		startHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(Box.createVerticalStrut(4));
		content.add(startHint);
		for (String problem : startProblems)
		{
			content.add(wrap(problem, SpyglassTheme.FARM_EMBER));
		}
	}

	private void startRun()
	{
		List<FarmingRunType> types = previewTypes();
		if (starting || model == null || types.isEmpty() || (combineMode ? !combinedStartOk(model) : startButton != null && !startButton.isEnabled()))
		{
			return; // a second press while the first is handled, or nothing chosen
		}
		starting = true;
		try
		{
			startRunOnce(types);
		}
		finally
		{
			starting = false;
		}
	}

	/** Start Run opens preparation (the store makes the run in its preparing state); a run already open is never replaced. */
	private void startRunOnce(List<FarmingRunType> types)
	{
		if (tutorialReader != null && model != null && !tutorial().isSetupComplete(tutorialFacts(model)))
		{
			FarmingTutorial.Step s = tutorial().current(tutorialFacts(model));
			startProblems = java.util.Collections.singletonList(s == FarmingTutorial.Step.INTRO ? "Finish Farming setup first: read the introduction above."
				: "Finish Farming setup first: " + (s == null ? "" : s.getTitle().toLowerCase(java.util.Locale.ROOT)) + " (or skip it above).");
			structureKey = null;
			render(model);
			return;
		}
		List<FarmingRunType> ordered = new ArrayList<>(types);
		ordered.sort(java.util.Comparator.comparingInt(t -> t.isCrop() ? 0 : 1)); // a run is named by its crops first
		FarmingRunRequest request = FarmingRunRequest.from(model, ordered, draft).withExtras(prefs());
		FarmingRunStore.StartResult result = runActions == null
			? null : runActions.start(request);
		if (result == null)
		{
			startProblems = java.util.Collections.singletonList("Farming isn't connected yet. Log in on a standard world.");
		}
		else if (!result.isStarted())
		{
			startProblems = new ArrayList<>(result.getProblems());
		}
		else
		{
			startProblems = new ArrayList<>();
			browsingDuringRun = false;
			endConfirm = false;
			browseType = null;
			if (combineMode)
			{
				combineMode = false;
				selected = combined.iterator().next();
				remembered.clear();
				remembered.addAll(combined);
				combined.clear();
				syncButtons();
			}
		}
		structureKey = null;
		if (runActions != null)
		{
			runActions.refresh();
		}
		else
		{
			render(model);
		}
	}

	private void returnToRun()
	{
		browsingDuringRun = false;
		structureKey = null;
		render(model);
	}

	/** While a run exists: a Return banner over Browse. */
	private void addRunBanner(FarmingModel m)
	{
		FarmingRunView run = m.getRun();
		if (run != null && browsingDuringRun)
		{
			JPanel banner = new JPanel(new BorderLayout(6, 0));
			banner.setOpaque(true);
			banner.setBackground(SpyglassTheme.TILE_FACE);
			banner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, SpyglassTheme.ACCENT_GOLD),
				BorderFactory.createEmptyBorder(4, 6, 4, 6)));
			String what = run.getPhase() == FarmingRunView.Phase.COMPLETE ? " run finished" : " run in progress";
			banner.add(small(run.getRunTitle() + what + " \u00b7 " + run.getCountText(), SpyglassTheme.TEXT_PRIMARY), BorderLayout.CENTER);
			banner.add(new Link("Return", this::returnToRun, SpyglassTheme.ACCENT_GOLD, true), BorderLayout.EAST);
			content.add(fullWidth(banner));
			content.add(Box.createVerticalStrut(6));
		}
	}

	/** The run page's controls. */
	private final class RunPageActions implements FarmingRunPage.Actions
	{
		@Override
		public JComponent compostChoices(List<com.osrstelemetry.plugin.farming.FarmingPatchType> types)
		{
			JPanel box = growingPanel();
			JLabel head = smallWrap("COMPOST ON PATCHES", SpyglassTheme.TILE_TEXT_QUIET);
			head.setFont(SpyglassFonts.smallBold());
			box.add(fullWidth(head));
			box.add(Box.createVerticalStrut(3));
			FarmingPreferences p = prefs();
			boolean first = true;
			for (com.osrstelemetry.plugin.farming.FarmingPatchType t : types)
			{
				if (!first)
				{
					box.add(Box.createVerticalStrut(CARD_GAP));
				}
				first = false;
				box.add(compostRow(t, p.compostFor(t), model == null ? null : model.compostObserved(t)));
			}
			return box;
		}

		@Override
		public void begin()
		{
			if (findFocus != null)
			{
				findFocus.accept(null);
			}
			if (runActions != null)
			{
				runActions.closePreparation();
				runActions.refresh();
			}
		}

		@Override
		public void find(int[] itemIds)
		{
			if (findFocus != null)
			{
				findFocus.accept(itemIds);
			}
		}

		@Override
		public com.osrstelemetry.plugin.guidance.GuidanceModel guidance()
		{
			try
			{
				return guidanceSource == null ? null : guidanceSource.get();
			}
			catch (RuntimeException e)
			{
				return null;
			}
		}

		@Override
		public int bankFilter()
		{
			return bankFilterState == null ? 0 : bankFilterState.getAsInt();
		}

		@Override
		public void toggleBankFilter()
		{
			if (bankFilterToggle != null)
			{
				bankFilterToggle.run();
			}
		}

		@Override
		public void setBounty(boolean use)
		{
			if (runActions != null)
			{
				runActions.setBounty(use);
				runActions.refresh();
			}
		}

		@Override
		public void resume()
		{
			if (runActions != null)
			{
				runActions.resume();
				runActions.refresh();
			}
		}

		@Override
		public void requestEnd()
		{
			endConfirm = true;
			structureKey = null;
			render(model);
		}

		@Override
		public void confirmEnd()
		{
			endConfirm = false;
			structureKey = null;
			if (findFocus != null)
			{
				findFocus.accept(null); // ending stops the guidance and its bank outline
			}
			if (runActions != null)
			{
				runActions.end();
				runActions.refresh();
			}
		}

		@Override
		public void cancelEnd()
		{
			endConfirm = false;
			structureKey = null;
			render(model);
		}

		@Override
		public void browse()
		{
			browsingDuringRun = true;
			structureKey = null;
			render(model);
		}

		@Override
		public void skip(String patchId, boolean skip)
		{
			if (runActions != null)
			{
				runActions.skip(patchId, skip);
				runActions.refresh();
			}
		}

		@Override
		public void skipBin(String binId, boolean skip)
		{
			if (runActions != null)
			{
				runActions.skipBin(binId, skip);
				runActions.refresh();
			}
		}

		@Override
		public void skipProtection(String patchId, boolean skip)
		{
			if (runActions != null)
			{
				runActions.skipProtection(patchId, skip);
				runActions.refresh();
			}
		}

		@Override
		public void skipCompost(String patchId, boolean skip)
		{
			if (runActions != null)
			{
				runActions.skipCompost(patchId, skip);
				runActions.refresh();
			}
		}

		@Override
		public void done()
		{
			FarmingRunView run = model == null ? null : model.getRun();
			if (runActions != null)
			{
				runActions.acknowledge();
			}
			structureKey = null;
			if (run != null)
			{
				applySelection(run.getRunType(), true); // back to that run type's preview (canonical §20)
			}
			if (runActions != null)
			{
				runActions.refresh();
			}
		}
	}

	private JComponent buildOptions(FarmingRunType type)
	{
		return buildOptions(java.util.Collections.singletonList(type));
	}

	/** Per-type plan-for-all, compost and protection; the amulet of bounty once (allotments only). */
	private JComponent buildOptions(List<FarmingRunType> types)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setOpaque(false);
		box.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 0));
		boolean bounty = false;
		for (FarmingRunType type : types)
		{
			if (types.size() > 1)
			{
				box.add(fullWidth(small(type.getTitle().toUpperCase(java.util.Locale.ROOT), SpyglassTheme.TILE_TEXT_QUIET)));
			}
			addTypeOptions(box, type);
			bounty |= com.osrstelemetry.plugin.farming.FarmingPreparation.bountyApplies(type);
		}
		if (bounty)
		{
			boolean on = draft.isUseBounty();
			Link b = new Link((on ? "☑ " : "☐ ") + "Wear an amulet of bounty (allotments)", () ->
			{
				draft.setUseBounty(!draft.isUseBounty());
				structureKey = null;
				render(model);
			});
			b.setToolTipText("Optional. A 25% chance to plant an allotment with one seed instead of three. Spyglass still lists the full seed count:"
				+ " its charges can't be read.");
			box.add(fullWidth(b));
		}
		return fullWidth(box);
	}

	private void addTypeOptions(JPanel box, FarmingRunType type)
	{
		String typePlan = draft.getTypePlan(type);
		boolean harvestOnly = com.osrstelemetry.plugin.farming.FarmingRunIntent.HARVEST_ONLY.equals(typePlan);
		Link plan = new Link("Patches: " + (harvestOnly ? "Harvest only" : "Replant") + " ▾", null);
		plan.setToolTipText("For every patch without its own choice. You pick the seeds in game.");
		plan.setAction(() -> planMenu(type.getPrimary(), choice ->
		{
			draft.setTypePlan(type, choice);
			structureKey = null;
			render(model);
		}).show(plan, 0, plan.getHeight()));
		box.add(fullWidth(plan));
		// Pass 31: compost is chosen per patch type in the COMPOST section above (one saved choice for the bins page and run
		// setup), no longer here.
	}

	private void updateSummaries(FarmingModel m)
	{
		syncCombineStart(m);
		if (combineMode && summaryLabel != null)
		{
			List<FarmingModel.Row> rows = previewRows(m);
			List<String> names = new ArrayList<>();
			for (FarmingRunType t : combined)
			{
				names.add(t.getTitle());
			}
			setText(summaryLabel, combined.isEmpty() ? "Nothing chosen yet" : "In this run: " + String.join(" + ", names) + " · " + rows.size() + " unique "
				+ (rows.size() == 1 ? "patch" : "patches"));
			if (scopeLabel != null)
			{
				int[] scope = draft.scope(rows, m.getNow());
				int binCount = combined.contains(FarmingRunType.COMPOST) ? prefs().getRunBins().size() : 0;
				String bins = binCount == 0 ? "" : " · " + binCount + (binCount == 1 ? " bin" : " bins");
				setText(scopeLabel, scope[0] + " of " + scope[1] + " patches · " + scope[2] + (scope[2] == 1 ? " site" : " sites") + bins);
				if (startButton != null)
				{
					boolean binsOnly = combined.size() == 1 && combined.contains(FarmingRunType.COMPOST);
					boolean ok = binsOnly ? binCount > 0 : scope[0] > 0;
					setStartEnabled(ok);
					startHint.setPlainText(ok ? "One run, one checklist, one suggested order. You do the run in game."
						: binsOnly ? "Tick at least one bin to start." : "Include at least one patch to start.");
				}
			}
			return;
		}
		if (selected == null || summaryLabel == null || !selected.isCrop())
		{
			return;
		}
		FarmingModel.TypeSummary t = m.summary(selected);
		StringBuilder s = new StringBuilder().append(t.getTotal()).append(t.getTotal() == 1 ? " patch" : " patches");
		if (t.getReady() > 0)
		{
			s.append(" · ").append(t.getReady()).append(" ready");
		}
		if (t.getAttention() > 0)
		{
			s.append(" · ").append(t.getAttention()).append(" need").append(t.getAttention() == 1 ? "s" : "").append(" attention");
		}
		setText(summaryLabel, s.toString());
		int untracked = t.getTotal() - t.getTracked();
		setText(untrackedLabel, untracked > 0 ? untracked + " not tracked yet" : " ");
		untrackedLabel.setVisible(untracked > 0);
		int[] scope = draft.scope(m.rows(selected), m.getNow());
		setText(scopeLabel, scope[0] + " of " + scope[1] + " patches · " + scope[2] + (scope[2] == 1 ? " site" : " sites"));
		if (startButton != null)
		{
			setStartEnabled(scope[0] > 0);
			startHint.setPlainText(scope[0] > 0 ? "You do the run in game; Spyglass tracks what it sees."
				: "Include at least one patch to start.");
		}
	}

	private void onInclude(String patchId, boolean include)
	{
		draft.setIncluded(patchId, include);
		if (model != null)
		{
			update(model);
		}
	}

	/** Opening or closing a card's planning section: view state only (never the draft, never inclusion). */
	private void onExpand(String patchId, boolean open)
	{
		if (open)
		{
			expanded.add(patchId);
		}
		else
		{
			expanded.remove(patchId);
		}
		content.revalidate();
		content.repaint();
	}

	/** The plan's name for the selector ("Any herb seed", "Replant (you choose)", "Harvest only", "Snapdragon"). Never the last crop seen. */
	private String plannedName(FarmingModel.Row r, FarmingModel m)
	{
		return draft.planLabel(r);
	}

	/**
	 * The plan choices for a patch: replant (the player picks the seed in
	 * game; Spyglass records what was actually planted) or harvest only. No
	 * crop is ever suggested or chosen here.
	 */
	JPopupMenu planMenu(com.osrstelemetry.plugin.farming.FarmingPatchType type, Consumer<String> choose)
	{
		JPopupMenu menu = new JPopupMenu();
		String any = com.osrstelemetry.plugin.farming.FarmingRunIntent.REPLANT_ANY;
		JMenuItem anyItem = new JMenuItem(FarmingPlanDraft.label(any, type));
		anyItem.setToolTipText("You choose what to plant in game; Spyglass records what you actually planted.");
		anyItem.addActionListener(e -> choose.accept(any));
		menu.add(anyItem);
		JMenuItem harvest = new JMenuItem("Harvest only");
		harvest.addActionListener(e -> choose.accept(com.osrstelemetry.plugin.farming.FarmingRunIntent.HARVEST_ONLY));
		menu.add(harvest);
		return menu;
	}

	private void openPlanMenu(PatchCardView view, Component anchor)
	{
		if (model == null || previewTypes().isEmpty())
		{
			return;
		}
		FarmingModel.Row row = null;
		for (FarmingModel.Row r : previewRows(model))
		{
			if (r.getPatchId().equals(view.patchId))
			{
				row = r;
			}
		}
		if (row == null)
		{
			return;
		}
		String id = row.getPatchId();
		JPopupMenu menu = planMenu(row.getPatch().getType(), plan ->
		{
			if (com.osrstelemetry.plugin.farming.FarmingRunIntent.REPLANT_ANY.equals(plan))
			{
				draft.setReplantAny(id);
			}
			else if (com.osrstelemetry.plugin.farming.FarmingRunIntent.HARVEST_ONLY.equals(plan))
			{
				draft.setPlannedCrop(id, FarmingPlanDraft.HARVEST_ONLY);
			}
			else
			{
				draft.setPlannedCrop(id, FarmingProduce.valueOf(plan));
			}
			update(model);
		});
		menu.show(anchor, 0, anchor.getHeight());
	}

	// -- small components ------------------------------------------------------------

	static Color color(FarmingModel.Tone tone)
	{
		switch (tone)
		{
			case GOLD:
				return SpyglassTheme.ACCENT_GOLD;
			case EMBER:
				return SpyglassTheme.FARM_EMBER;
			case PRIMARY:
				return SpyglassTheme.TEXT_PRIMARY;
			case MUTED:
				return SpyglassTheme.TEXT_MUTED;
			case SAGE:
				return SpyglassTheme.ACCENT_SAGE;
			case GREEN:
				return SpyglassTheme.STATUS_SUCCESS;
			case RED:
				return SpyglassTheme.STATUS_DANGER;
			case SECONDARY:
			default:
				return SpyglassTheme.TEXT_SECONDARY;
		}
	}

	private static <T extends JComponent> T fullWidth(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (!(c instanceof WrappingHtmlLabel))
		{
			c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height)); // a wrapping line sizes itself
		}
		return c;
	}

	private static JLabel small(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(SpyglassFonts.small());
		l.setForeground(color);
		return l;
	}

	private static void setText(JLabel label, String text)
	{
		if (label instanceof WrappingHtmlLabel)
		{
			((WrappingHtmlLabel) label).setPlainText(text); // a no-op when unchanged
		}
		else if (label != null && !text.equals(label.getText()))
		{
			label.setText(text);
		}
	}

	/** A small line that wraps at the page's width (summaries that grow with the text size). */
	private static JLabel smallWrap(String text, Color color)
	{
		WrappingHtmlLabel l = new WrappingHtmlLabel(text, false);
		l.setFont(SpyglassFonts.small());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JComponent wrap(String text, Color color)
	{
		WrappingHtmlLabel l = new WrappingHtmlLabel(text, false);
		l.setFont(SpyglassFonts.small());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static JComponent message(String text)
	{
		JComponent l = wrap(text, SpyglassTheme.TEXT_SECONDARY);
		l.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		return l;
	}

	/** "WHAT'S GROWING" / "HERB PATCHES  × overview". */
	private JComponent sectionHeader(String title, Runnable onClose)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		JLabel t = smallWrap(title, SpyglassTheme.TILE_TEXT_QUIET);
		t.setFont(SpyglassFonts.smallBold());
		row.add(t, BorderLayout.CENTER);
		if (onClose != null)
		{
			Link back = new Link(BACK_TO_OVERVIEW, onClose, SpyglassTheme.ACCENT_GOLD, true);
			back.setToolTipText("Back to the overview of every run type");
			back.getAccessibleContext().setAccessibleName("Back to the Farming overview");
			row.add(back, BorderLayout.EAST);
		}
		return HomeDetailPage.growing(row);
	}

	/** One overview line: "Herb ........ 6 ready ›", clickable. */
	static final class OverviewLine extends JPanel
	{
		private final Runnable onClick;
		private boolean hover;
		private JLabel title;
		private JLabel stateLabel;

		/** Title and state on one line when they fit; otherwise the state drops below, right-aligned (never overlapping). */
		private boolean fitsOneLine(int width)
		{
			java.awt.Insets in = getInsets();
			return title.getPreferredSize().width + 6 + stateLabel.getPreferredSize().width <= width - in.left - in.right;
		}

		@Override
		public Dimension getPreferredSize()
		{
			java.awt.Insets in = getInsets();
			Dimension t = title.getPreferredSize();
			Dimension st = stateLabel.getPreferredSize();
			int width = getWidth() > 0 ? getWidth() : getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth()
				: t.width + 6 + st.width + in.left + in.right;
			int h = fitsOneLine(width) ? Math.max(t.height, st.height) : t.height + st.height;
			return new Dimension(width, h + in.top + in.bottom);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		public void doLayout()
		{
			java.awt.Insets in = getInsets();
			int w = getWidth() - in.left - in.right;
			Dimension t = title.getPreferredSize();
			Dimension st = stateLabel.getPreferredSize();
			if (fitsOneLine(getWidth()))
			{
				int h = Math.max(t.height, st.height);
				title.setBounds(in.left, in.top + (h - t.height) / 2, t.width, t.height);
				stateLabel.setBounds(in.left + w - st.width, in.top + (h - st.height) / 2, st.width, st.height);
			}
			else
			{
				title.setBounds(in.left, in.top, Math.min(w, t.width), t.height);
				int sw = Math.min(w, st.width);
				stateLabel.setBounds(in.left + w - sw, in.top + t.height, sw, st.height);
			}
		}

		OverviewLine(String title, String state, Color stateColor, Runnable onClick)
		{
			super(null);
			this.onClick = onClick;
			setOpaque(true);
			setBackground(SpyglassTheme.TILE_CANVAS);
			setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			JLabel t = new JLabel(title);
			t.setFont(SpyglassFonts.regular());
			t.setForeground(SpyglassTheme.TEXT_PRIMARY);
			JLabel s = new JLabel(state + "  ›");
			s.setFont(SpyglassFonts.small());
			s.setForeground(stateColor);
			add(t);
			add(s);
			this.title = t;
			stateLabel = s;
			setToolTipText(title + ": " + state);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					setBackground(SpyglassTheme.TILE_FACE);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					setBackground(SpyglassTheme.TILE_CANVAS);
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					if (contains(e.getPoint()))
					{
						OverviewLine.this.onClick.run();
					}
				}
			});
		}

		void click()
		{
			onClick.run();
		}
	}

	/** A panel whose height always follows its content (a BoxLayout parent never caps or stretches it). */
	private static JPanel growingPanel()
	{
		JPanel p = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** {@code a} blended toward {@code b} by {@code t} (0..1), pre-computed once: no alpha at paint time. */
	static Color mix(Color a, Color b, double t)
	{
		return new Color(
			(int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
			(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	/**
	 * Binds Space and Enter to {@code action} while {@code c} has focus. It
	 * fires on release, and only when the press also arrived here: an Enter
	 * that chose an item in a popup menu releases onto the control the menu
	 * returns focus to, and must not activate it again (that reopened the
	 * planned-crop menu).
	 */
	private static void bindActivate(JComponent c, Runnable action)
	{
		boolean[] armed = new boolean[1];
		for (String key : new String[]{"SPACE", "ENTER"})
		{
			String press = "farming-press-" + key;
			String release = "farming-activate-" + key;
			c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("pressed " + key), press);
			c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("released " + key), release);
			c.getActionMap().put(press, new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					armed[0] = true;
				}
			});
			c.getActionMap().put(release, new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					if (armed[0])
					{
						armed[0] = false;
						action.run();
					}
				}
			});
		}
		c.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				c.repaint();
				if (isTraversal(e.getCause()))
				{
					revealCard(c);
				}
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				armed[0] = false;
				c.repaint();
			}
		});
	}

	private static boolean isTraversal(FocusEvent.Cause cause)
	{
		return cause == FocusEvent.Cause.TRAVERSAL || cause == FocusEvent.Cause.TRAVERSAL_FORWARD
			|| cause == FocusEvent.Cause.TRAVERSAL_BACKWARD || cause == FocusEvent.Cause.TRAVERSAL_UP
			|| cause == FocusEvent.Cause.TRAVERSAL_DOWN;
	}

	/**
	 * Keyboard focus arriving on a card control scrolls that whole card into
	 * view (Swing does not follow focus on its own, so Tab could otherwise move
	 * focus below the visible page). Mouse focus never scrolls.
	 */
	static void revealCard(JComponent c)
	{
		Container card = SwingUtilities.getAncestorOfClass(PatchCardView.class, c);
		JComponent target = card instanceof JComponent ? (JComponent) card : c;
		target.scrollRectToVisible(new Rectangle(0, 0, target.getWidth(), target.getHeight()));
	}

	/** Runs {@code action} on a left-button release inside {@code c} (drag out and release cancels). */
	static void onClick(JComponent c, Runnable action)
	{
		c.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && c.contains(e.getPoint()))
				{
					action.run();
				}
			}
		});
	}

	/** A quiet clickable text (muted, cream on hover or keyboard focus). */
	static final class Link extends JLabel
	{
		private final Color base;
		private Runnable action;
		private boolean hover;

		Link(String text, Runnable action)
		{
			this(text, action, SpyglassTheme.TEXT_MUTED, false);
		}

		/** {@code keyboard}: focusable, Space / Enter activate. */
		Link(String text, Runnable action, Color base, boolean keyboard)
		{
			super(text);
			this.action = action;
			this.base = base;
			setFont(SpyglassFonts.small());
			setForeground(base);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					refresh();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					refresh();
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					if (contains(e.getPoint()) && Link.this.action != null)
					{
						Link.this.action.run();
					}
				}
			});
			setFocusable(keyboard);
			if (keyboard)
			{
				bindActivate(this, this::click);
				addFocusListener(new FocusAdapter()
				{
					@Override
					public void focusGained(FocusEvent e)
					{
						refresh();
					}

					@Override
					public void focusLost(FocusEvent e)
					{
						refresh();
					}
				});
			}
		}

		private void refresh()
		{
			setForeground(hover || isFocusOwner() ? SpyglassTheme.TEXT_PRIMARY : base);
		}

		private boolean wrapping;
		private String plain;

		/**
		 * A choice that wraps onto more lines when it doesn't fit the width its parent has (pass 30: settings and choices at a
		 * large text size), instead of being cut short. Used where the link has its parent's whole width.
		 */
		Link wrapping()
		{
			if (!wrapping)
			{
				wrapping = true;
				setText(getText());
			}
			return this;
		}

		@Override
		public void setText(String text)
		{
			if (wrapping && text != null && !text.startsWith("<html>"))
			{
				plain = text;
				super.setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
				return;
			}
			plain = text;
			super.setText(text);
		}

		/** The text as shown (no markup). */
		String plainText()
		{
			return plain;
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			if (!wrapping || getParent() == null)
			{
				return d;
			}
			java.awt.Container p = getParent();
			int avail = p.getWidth() - p.getInsets().left - p.getInsets().right;
			if (avail <= 0 || d.width <= avail)
			{
				return d;
			}
			javax.swing.text.View v = (javax.swing.text.View) getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
			if (v == null)
			{
				return d;
			}
			javax.swing.text.View m = javax.swing.plaf.basic.BasicHTML.createHTMLView(this, getText());
			m.setSize(avail, 0);
			return new Dimension(avail, (int) Math.ceil(m.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + getInsets().top + getInsets().bottom);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return wrapping ? getPreferredSize() : super.getMaximumSize();
		}

		private int settledWidth = -1;

		/** As a wrapping label: given a width at which it needs more lines than it was given, its containers lay out again. */
		@Override
		@SuppressWarnings("deprecation")
		public void reshape(int x, int y, int w, int h)
		{
			super.reshape(x, y, w, h);
			if (!wrapping || w <= 0 || w == settledWidth || getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null)
			{
				return;
			}
			settledWidth = w;
			javax.swing.text.View m = javax.swing.plaf.basic.BasicHTML.createHTMLView(this, getText());
			m.setSize(w, 0);
			if (Math.ceil(m.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + getInsets().top + getInsets().bottom > h)
			{
				SwingUtilities.invokeLater(this::revalidate);
			}
		}

		@Override
		public void setFont(java.awt.Font f)
		{
			super.setFont(f);
			settledWidth = -1;
		}

		void setAction(Runnable action)
		{
			this.action = action;
		}

		void click()
		{
			if (action != null)
			{
				action.run();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (isFocusOwner())
			{
				// Keyboard focus: a fine bronze underline under the text.
				g.setColor(SpyglassTheme.ACCENT_BRONZE);
				g.fillRect(0, getHeight() - 1, getWidth(), 1);
			}
		}
	}

	/**
	 * The include box: a painted check box, 11 px inside a 15 px target so the
	 * keyboard focus ring has room. Mouse click or Space / Enter toggle it; no
	 * other part of the card does. Unticked it is deliberately quiet.
	 */
	static final class IncludeBox extends JComponent
	{
		static final int SIZE = 15;
		private boolean checked;

		IncludeBox(Runnable onToggle)
		{
			Dimension d = new Dimension(SIZE, SIZE);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
			setAlignmentX(Component.CENTER_ALIGNMENT);
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText("Include in this plan");
			onClick(this, onToggle);
			bindActivate(this, onToggle);
		}

		void setChecked(boolean checked)
		{
			if (this.checked != checked)
			{
				this.checked = checked;
				if (accessibleContext != null)
				{
					accessibleContext.firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
						checked ? null : AccessibleState.CHECKED, checked ? AccessibleState.CHECKED : null);
				}
				repaint();
			}
		}

		boolean isChecked()
		{
			return checked;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int x = (getWidth() - 11) / 2;
			int y = (getHeight() - 11) / 2;
			if (isFocusOwner())
			{
				g.setColor(SpyglassTheme.ACCENT_BRONZE);
				g.drawRect(x - 2, y - 2, 14, 14);
			}
			g.setColor(SpyglassTheme.TILE_FACE_PRESSED);
			g.fillRect(x, y, 11, 11);
			g.setColor(checked ? SpyglassTheme.ACCENT_BRONZE : SpyglassTheme.BORDER_SOFT);
			g.drawRect(x, y, 10, 10);
			if (checked)
			{
				g.setColor(SpyglassTheme.ACCENT_GOLD);
				int[][] tick = {{2, 5}, {3, 6}, {4, 7}, {5, 6}, {6, 5}, {7, 4}, {8, 3}};
				for (int[] p : tick)
				{
					g.fillRect(x + p[0], y + p[1], 1, 2);
				}
			}
		}

		@Override
		public AccessibleContext getAccessibleContext()
		{
			if (accessibleContext == null)
			{
				accessibleContext = new AccessibleJComponent()
				{
					@Override
					public AccessibleRole getAccessibleRole()
					{
						return AccessibleRole.CHECK_BOX;
					}

					@Override
					public AccessibleStateSet getAccessibleStateSet()
					{
						AccessibleStateSet set = super.getAccessibleStateSet();
						if (checked)
						{
							set.add(AccessibleState.CHECKED);
						}
						return set;
					}
				};
			}
			return accessibleContext;
		}
	}

	/**
	 * The card's expand control: a small raised button with a pixel chevron
	 * (▸ closed, ▾ open) that shows or hides the planned-crop section. Mouse
	 * click or Space / Enter; it never touches inclusion.
	 */
	static final class ExpandToggle extends JComponent
	{
		static final int SIZE = 15;
		private boolean open;
		private boolean hover;
		private final String closedTip;
		private final String openTip;

		ExpandToggle(Runnable onToggle)
		{
			this(onToggle, "Planned crop", "Hide planned crop");
		}

		ExpandToggle(Runnable onToggle, String closedTip, String openTip)
		{
			this.closedTip = closedTip;
			this.openTip = openTip;
			Dimension d = new Dimension(SIZE, SIZE);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
			setAlignmentX(Component.CENTER_ALIGNMENT);
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText(closedTip);
			onClick(this, onToggle);
			bindActivate(this, onToggle);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
		}

		void setOpen(boolean open)
		{
			if (this.open != open)
			{
				this.open = open;
				setToolTipText(open ? openTip : closedTip);
				if (accessibleContext != null)
				{
					accessibleContext.firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
						open ? AccessibleState.COLLAPSED : AccessibleState.EXPANDED, open ? AccessibleState.EXPANDED : AccessibleState.COLLAPSED);
				}
				repaint();
			}
		}

		boolean isOpen()
		{
			return open;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			int s = 13;
			int x = (getWidth() - s) / 2;
			int y = (getHeight() - s) / 2;
			// A tiny raised button: rim, face, lit top edge, shaded bottom edge.
			g.setColor(isFocusOwner() ? SpyglassTheme.ACCENT_BRONZE : SpyglassTheme.TILE_RIM);
			g.drawRect(x, y, s - 1, s - 1);
			g.setColor(open ? SpyglassTheme.TILE_FACE_PRESSED : hover ? SpyglassTheme.TILE_EDGE_HIGHLIGHT : SpyglassTheme.TILE_FACE_HOVER);
			g.fillRect(x + 1, y + 1, s - 2, s - 2);
			if (!open)
			{
				g.setColor(SpyglassTheme.TILE_EDGE_HIGHLIGHT_HOVER);
				g.fillRect(x + 1, y + 1, s - 2, 1);
			}
			g.setColor(SpyglassTheme.TILE_EDGE_SHADE);
			g.fillRect(x + 1, y + s - 2, s - 2, 1);
			g.setColor(SpyglassTheme.TEXT_PRIMARY);
			int cx = x + s / 2;
			int cy = y + s / 2;
			if (open)
			{
				// ▾
				g.fillRect(cx - 3, cy - 1, 7, 1);
				g.fillRect(cx - 2, cy, 5, 1);
				g.fillRect(cx - 1, cy + 1, 3, 1);
				g.fillRect(cx, cy + 2, 1, 1);
			}
			else
			{
				// ▸
				g.fillRect(cx - 1, cy - 3, 1, 7);
				g.fillRect(cx, cy - 2, 1, 5);
				g.fillRect(cx + 1, cy - 1, 1, 3);
				g.fillRect(cx + 2, cy, 1, 1);
			}
		}

		@Override
		public AccessibleContext getAccessibleContext()
		{
			if (accessibleContext == null)
			{
				accessibleContext = new AccessibleJComponent()
				{
					@Override
					public AccessibleRole getAccessibleRole()
					{
						return AccessibleRole.TOGGLE_BUTTON;
					}

					@Override
					public AccessibleStateSet getAccessibleStateSet()
					{
						AccessibleStateSet set = super.getAccessibleStateSet();
						set.add(AccessibleState.EXPANDABLE);
						set.add(open ? AccessibleState.EXPANDED : AccessibleState.COLLAPSED);
						return set;
					}
				};
			}
			return accessibleContext;
		}
	}

	/** Loads a game item sprite; {@code onLoaded} may run on any thread once the pixels have arrived. */
	interface SpriteSource
	{
		BufferedImage load(int itemId, Runnable onLoaded);
	}

	/**
	 * The card's crop sprite: always reserves the native 36×32 item size (so
	 * nothing moves when the image arrives), draws it unscaled, and carries the
	 * full crop name as tooltip and accessible name. It has no click behaviour.
	 */
	static final class CropIcon extends JComponent
	{
		private final SpriteSource source;
		private int itemId = -1;
		private BufferedImage sprite;

		CropIcon(SpriteSource source)
		{
			this.source = source;
			setOpaque(false);
			Dimension d = new Dimension(ItemIcon.WIDTH, ItemIcon.HEIGHT);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		void show(int itemId, String label)
		{
			if (itemId != this.itemId)
			{
				this.itemId = itemId;
				sprite = source == null ? null : source.load(itemId, () -> SwingUtilities.invokeLater(this::repaint));
				repaint();
			}
			if (!label.equals(getToolTipText()))
			{
				setToolTipText(label);
				getAccessibleContext().setAccessibleName(label);
			}
		}

		int getItemId()
		{
			return itemId;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (sprite != null)
			{
				g.drawImage(sprite, (getWidth() - ItemIcon.WIDTH) / 2, (getHeight() - ItemIcon.HEIGHT) / 2, null);
			}
		}

		@Override
		public AccessibleContext getAccessibleContext()
		{
			if (accessibleContext == null)
			{
				accessibleContext = new AccessibleJComponent()
				{
					@Override
					public AccessibleRole getAccessibleRole()
					{
						return AccessibleRole.ICON;
					}
				};
			}
			return accessibleContext;
		}
	}

	/** Callback for a card's planned-crop selector. */
	interface PlanMenuOpener
	{
		void open(PatchCardView view, Component anchor);
	}

	/**
	 * One patch as a small raised card (canonical §3.2):
	 *
	 * <pre>
	 *  [sprite]  Location (strongest, wraps)        [☑]
	 *            Status / timer                      [▸]
	 *            Provenance (quiet, wraps, full width)
	 *            ─ Planned crop  Snapdragon ▾ ─  (only while expanded)
	 * </pre>
	 *
	 * The sprite shows the OBSERVED crop (its tooltip and accessible name carry
	 * the crop's full name, so the name is not repeated as text). Only the box
	 * changes inclusion; the chevron only opens the planning section. Excluding
	 * a patch quiets the box and the rail, never the location, status or
	 * provenance.
	 */
	static final class PatchCardView extends JPanel
	{
		final String patchId;
		final RaisedTile tile = new RaisedTile(null, 5, 6);
		final CropIcon icon;
		final WrappingHtmlLabel location = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel status = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel provenance = new WrappingHtmlLabel(" ", false);
		final IncludeBox box;
		final ExpandToggle expand;
		final JPanel planSection = new JPanel(new BorderLayout(6, 0));
		final Link plan;
		private final Consumer<Boolean> onInclude;
		private final BiConsumer<String, Boolean> onExpand;
		private FarmingModel.Tone lastTone;
		private String locationName = "";

		PatchCardView(String patchId, SpriteSource sprites, Consumer<Boolean> onInclude, PlanMenuOpener planOpener,
			BiConsumer<String, Boolean> onExpand)
		{
			super(new BorderLayout());
			this.patchId = patchId;
			this.onInclude = onInclude;
			this.onExpand = onExpand;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			icon = new CropIcon(sprites);
			box = new IncludeBox(this::clickInclude);
			expand = new ExpandToggle(this::toggleExpanded);

			tile.setLayout(new BorderLayout(6, 0));

			JPanel iconColumn = new JPanel(new BorderLayout());
			iconColumn.setOpaque(false);
			iconColumn.add(icon, BorderLayout.NORTH);

			location.setFont(SpyglassFonts.bold());
			location.setForeground(SpyglassTheme.TEXT_PRIMARY);
			status.setFont(SpyglassFonts.regular());
			provenance.setFont(SpyglassFonts.small());
			provenance.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
			// Three lines; the controls sit at the right end of the first two
			// (box beside the location, chevron beside the status) so the
			// provenance line gets the card's full text width.
			JPanel locationCell = new JPanel(new BorderLayout());
			locationCell.setOpaque(false);
			locationCell.add(location, BorderLayout.CENTER);
			JPanel boxCell = new JPanel(new BorderLayout());
			boxCell.setOpaque(false);
			boxCell.add(box, BorderLayout.NORTH);
			JPanel line1 = new JPanel(new BorderLayout(4, 0));
			line1.setOpaque(false);
			line1.add(locationCell, BorderLayout.CENTER);
			line1.add(boxCell, BorderLayout.EAST);

			JPanel line2 = new JPanel(new BorderLayout(4, 0));
			line2.setOpaque(false);
			JPanel statusCell = new JPanel(new BorderLayout()); // the status wraps at its own cell's width, beside the chevron
			statusCell.setOpaque(false);
			statusCell.add(status, BorderLayout.CENTER);
			line2.add(statusCell, BorderLayout.CENTER);
			line2.add(expand, BorderLayout.EAST);

			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			for (JComponent c : new JComponent[]{line1, line2, provenance})
			{
				c.setAlignmentX(Component.LEFT_ALIGNMENT);
				text.add(c);
			}

			JLabel planLabel = small("Planned crop", SpyglassTheme.TILE_TEXT_QUIET);
			plan = new Link(" ", null, SpyglassTheme.TEXT_SECONDARY, true);
			plan.setAction(() -> planOpener.open(this, plan));
			planSection.setOpaque(false);
			planSection.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createEmptyBorder(4, ItemIcon.WIDTH + 6, 0, 0),
				BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(1, 0, 0, 0, SpyglassTheme.TILE_EDGE_SHADE),
					BorderFactory.createEmptyBorder(3, 0, 0, 0))));
			planSection.add(planLabel, BorderLayout.WEST);
			planSection.add(plan, BorderLayout.CENTER);
			planSection.setVisible(false);

			tile.add(iconColumn, BorderLayout.WEST);
			tile.add(text, BorderLayout.CENTER);
			tile.add(planSection, BorderLayout.SOUTH);
			add(tile, BorderLayout.CENTER);
		}

		void update(FarmingModel.Row r, boolean included, String plannedCrop)
		{
			locationName = r.getLocation();
			location.setPlainText(locationName);
			String statusText = statusText(r);
			setText(status, statusText);
			if (r.getChipTone() != lastTone)
			{
				lastTone = r.getChipTone();
				status.setForeground(color(lastTone));
				status.setFont(lastTone == FarmingModel.Tone.GOLD ? SpyglassFonts.bold() : SpyglassFonts.regular());
			}
			provenance.setPlainText(r.getProvenance());
			String iconLabel = FarmingIcons.label(r);
			icon.show(FarmingIcons.itemFor(r), iconLabel);

			box.setChecked(included);
			tile.setRail(rail(r.getChipTone(), included));
			setText(plan, plannedCrop + " ▾");

			box.getAccessibleContext().setAccessibleName("Include " + locationName + " in the plan");
			expand.getAccessibleContext().setAccessibleName("Planned crop for " + locationName);
			plan.getAccessibleContext().setAccessibleName("Planned crop: " + plannedCrop);
			tile.getAccessibleContext().setAccessibleName(locationName + ", " + iconLabel + ", " + statusText);
		}

		/** The status line: a timer reads "Ready in ~1h 40m"; states read as themselves. */
		static String statusText(FarmingModel.Row r)
		{
			String chip = r.getChip();
			return chip.startsWith("~") ? "Ready in " + chip : chip;
		}

		/** Ready / attention rails; quieter (same width, so nothing shifts) when the patch is left out. */
		static Color rail(FarmingModel.Tone tone, boolean included)
		{
			Color c;
			if (tone == FarmingModel.Tone.GOLD)
			{
				c = SpyglassTheme.ACCENT_GOLD;
			}
			else if (tone == FarmingModel.Tone.EMBER)
			{
				c = SpyglassTheme.FARM_EMBER;
			}
			else
			{
				return null;
			}
			return included ? c : mix(c, SpyglassTheme.TILE_FACE, 0.6);
		}

		/** Exactly what a click on (or Space in) the box does. */
		void clickInclude()
		{
			onInclude.accept(!box.isChecked());
		}

		/** Exactly what a click on (or Space / Enter in) the chevron does. */
		void toggleExpanded()
		{
			setExpanded(!expand.isOpen());
			onExpand.accept(patchId, expand.isOpen());
		}

		void setExpanded(boolean open)
		{
			expand.setOpen(open);
			if (planSection.isVisible() != open)
			{
				planSection.setVisible(open);
				revalidate();
				repaint();
			}
		}

		boolean isExpanded()
		{
			return expand.isOpen();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** The next render rebuilds the page (the text size or contrast changed). */
	void invalidateStructure()
	{
		structureKey = null;
	}

	/** Cards on screen (tests). */
	Map<String, PatchCardView> rowViews()
	{
		return rowViews;
	}

	String scopeText()
	{
		return scopeLabel == null ? null : HomeView.plain(scopeLabel);
	}
}
