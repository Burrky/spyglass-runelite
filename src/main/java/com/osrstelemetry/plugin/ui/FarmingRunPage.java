package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.farming.FarmingModel;
import com.osrstelemetry.plugin.farming.FarmingPreparationView;
import com.osrstelemetry.plugin.farming.FarmingRunEvaluator;
import com.osrstelemetry.plugin.farming.FarmingRunView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Home → Farming while a run exists (canonical §16, §20, §24; wireframes H
 * and I, Phase E: no travel). The player carries out the run in game; this
 * page shows what Spyglass has observed: a header with the count, a NEXT card
 * (the patches here, else the next remaining one; never a route), one raised
 * card per included patch with its crop sprite, run status and live status,
 * Skip, and Browse patches / End run. Paused runs offer Resume / End run;
 * a completed run shows only observed facts and Done.
 *
 * Built once per structure (run, phase, end confirmation); every refresh
 * updates text in place, so scroll position, open controls and keyboard focus
 * survive the 1 s poll.
 */
final class FarmingRunPage
{
	/** What the page's controls do (wired by {@link FarmingView}). */
	interface Actions
	{
		void resume();

		void requestEnd();

		void confirmEnd();

		void cancelEnd();

		void browse();

		void skip(String patchId, boolean skip);

		default void skipBin(String binId, boolean skip)
		{
		}

		default void skipProtection(String patchId, boolean skip)
		{
		}

		default void skipCompost(String patchId, boolean skip)
		{
		}

		void done();

		/** Begin (in any of its wordings): close the preparation stage and show the run's patches. */
		void begin();

		/**
		 * Find: mark these item ids for the passive bank outline (null clears).
		 * Never types into the bank search, switches tabs or acts in the game.
		 */
		void find(int[] itemIds);

		/** The player-clicked bank filter: 0 = bank closed or nothing to show, 1 = available, 2 = showing run items. */
		default int bankFilter()
		{
			return 0;
		}

		/** Show only the run's items in the open bank, or return to the normal view (the player's click). */
		default void toggleBankFilter()
		{
		}

		/** The run option "wear an amulet of bounty" (saved with the run). */
		default void setBounty(boolean use)
		{
		}

		/** Pass 31: the compost choices for these patch types (the overview's own editor rows), or null when none is offered. */
		default JComponent compostChoices(List<com.osrstelemetry.plugin.farming.FarmingPatchType> types)
		{
			return null;
		}

		/** The live guidance (next stop and actions), or null when none is running. */
		default com.osrstelemetry.plugin.guidance.GuidanceModel guidance()
		{
			return null;
		}
	}

	static final String PREP_NOTE = "Spyglass only shows what to bring. You withdraw, equip and travel yourself.";

	static final String ORDER_NOTE = "A suggestion: go in any order. Patches tick off as Spyglass sees them.";

	static final String NOTE = "You do the run in game. Spyglass ticks each patch off when it sees the change.";

	private final FarmingView.SpriteSource sprites;
	private final Actions actions;
	private final Map<String, RunPatchCard> cards = new LinkedHashMap<>();
	private JLabel title;
	private JLabel count;
	private JLabel nextTitle;
	private JComponent nextCard;
	private JPanel nextLines;
	private List<String> shownNextLines = new ArrayList<>();
	private WrappingHtmlLabel pausedTitle;
	private JLabel pausedCount;
	private JPanel summaryLines;
	private JLabel nextReady;
	ActionButton resumeButton;
	ActionButton doneButton;
	ActionButton confirmEndButton;
	ActionButton keepGoingButton;
	FarmingView.Link endLink;
	FarmingView.Link browseLink;
	// Preparation (Phase H)
	private WrappingHtmlLabel prepHeadline;
	private JPanel prepSublines;
	private List<String> shownSublines = new ArrayList<>();
	private RaisedTile prepCard;
	final Map<String, SupplyRow> prepRows = new LinkedHashMap<>();
	final Map<String, DestinationRow> siteRows = new LinkedHashMap<>();
	private final Map<String, JLabel> sectionCounts = new LinkedHashMap<>();
	ActionButton beginButton;
	/** The need Find is showing (UI only; cleared when it is carried or the page leaves preparation). */
	String findKey;
	private FarmingPreparationView lastPrep;
	private String titlePrefix = "";

	FarmingRunPage(FarmingView.SpriteSource sprites, Actions actions)
	{
		this.sprites = sprites;
		this.actions = actions;
	}

	/** Structure for {@link FarmingView#structureKey}: rebuild only when this changes. */
	static String structureKey(FarmingRunView run, boolean endConfirm, FarmingPreparationView prep)
	{
		String base = structureKey(run, endConfirm);
		return run.getPhase() == FarmingRunView.Phase.PREPARING ? base + "|" + (prep == null ? "PREP-PENDING" : prep.structureKey()) : base;
	}

	static String structureKey(FarmingRunView run, boolean endConfirm)
	{
		StringBuilder k = new StringBuilder("RUN|").append(run.getIntent().getIntentId()).append('|').append(run.getPhase()).append('|')
			.append(endConfirm);
		// The patches, not their order: the list reorders in place (current stop first) without a rebuild, so scroll and
		// keyboard focus survive.
		java.util.TreeSet<String> ids = new java.util.TreeSet<>();
		for (FarmingRunView.PatchLine l : run.getLines())
		{
			ids.add(l.getPatchId());
		}
		for (String id : ids)
		{
			k.append('|').append(id);
		}
		return k.toString();
	}

	Map<String, RunPatchCard> cards()
	{
		return cards;
	}

	void build(JPanel content, FarmingRunView run, FarmingModel m, boolean endConfirm)
	{
		build(content, run, m, endConfirm, null);
	}

	void build(JPanel content, FarmingRunView run, FarmingModel m, boolean endConfirm, FarmingPreparationView prep)
	{
		prepRows.clear();
		siteRows.clear();
		sectionCounts.clear();
		prepHeadline = null;
		prepSublines = null;
		shownSublines = new ArrayList<>();
		prepCard = null;
		beginButton = null;
		if (run.getPhase() != FarmingRunView.Phase.PREPARING && findKey != null)
		{
			findKey = null;
			actions.find(null);
		}
		if (run.getPhase() == FarmingRunView.Phase.PREPARING)
		{
			cards.clear();
			endLink = null;
			browseLink = null;
			buildPreparation(content, run, prep, endConfirm);
			return;
		}
		cards.clear();
		resumeButton = null;
		doneButton = null;
		confirmEndButton = null;
		keepGoingButton = null;
		endLink = null;
		browseLink = null;
		pausedTitle = null;
		pausedCount = null;
		summaryLines = null;
		nextTitle = null;
		nextLines = null;
		nextCard = null;
		nextReady = null;
		shownNextLines = new ArrayList<>();

		title = heading(run.getTitle());
		count = new WrappingHtmlLabel(" ", false); // pass 32: wraps under the title at the narrowest widths
		count.setFont(SpyglassFonts.small());
		count.setForeground(SpyglassTheme.TEXT_SECONDARY);
		FitRow header = new FitRow(title, count); // the count drops under the title when both don't fit (larger text)
		content.add(fullWidth(header));
		content.add(Box.createVerticalStrut(4));

		if (run.getPhase() == FarmingRunView.Phase.COMPLETE)
		{
			buildComplete(content, run, m);
			update(run, m);
			return;
		}
		if (run.getPhase() == FarmingRunView.Phase.PAUSED)
		{
			content.add(pausedCard(run));
		}
		else
		{
			content.add(nextCard());
		}
		runGroups.clear();
		shownRunGroups.clear();
		for (FarmingRunView.PatchLine line : run.getLines())
		{
			RunPatchCard card = new RunPatchCard(line.getPatchId(), sprites, actions);
			cards.put(line.getPatchId(), card);
		}
		// Remaining work leads; completed work folds under a count; skipped work stays apart with Undo.
		content.add(runGroup(RunGroup.TODO, "TO DO", false));
		content.add(runGroup(RunGroup.DONE, "COMPLETED", true));
		content.add(runGroup(RunGroup.SKIPPED, "SKIPPED", false));
		binSection = growingPanel();
		shownBins = null;
		content.add(binSection);
		content.add(Box.createVerticalStrut(8));

		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		browseLink = new FarmingView.Link("Browse patches", actions::browse, SpyglassTheme.TEXT_SECONDARY, true);
		endLink = new FarmingView.Link("End run", actions::requestEnd, SpyglassTheme.TEXT_SECONDARY, true);
		footer.add(browseLink, BorderLayout.WEST);
		if (!endConfirm)
		{
			footer.add(endLink, BorderLayout.EAST);
		}
		content.add(fullWidth(footer));
		if (endConfirm)
		{
			content.add(Box.createVerticalStrut(6));
			content.add(endConfirmCard(run));
		}
		update(run, m);
	}

	void update(FarmingRunView run, FarmingModel m, FarmingPreparationView prep)
	{
		if (run.getPhase() == FarmingRunView.Phase.PREPARING)
		{
			updatePreparation(prep);
			return;
		}
		update(run, m);
	}

	void update(FarmingRunView run, FarmingModel m)
	{
		setText(count, run.getPhase() == FarmingRunView.Phase.COMPLETE ? " " : run.getCountText() + " · " + run.getElapsedText());
		if (pausedTitle != null)
		{
			pausedTitle.setPlainText("Run paused");
			setText(pausedCount, run.getCountText() + ".");
		}
		if (nextTitle != null)
		{
			com.osrstelemetry.plugin.guidance.GuidanceModel g = actions.guidance();
			boolean guided = g != null && g.isActive() && g.getTitle() != null;
			String t = guided ? g.getTitle() : run.getNextTitle();
			setText(nextTitle, t == null ? "Everything here is done" : t);
			// The current step only (canonical §28.13): one instruction; the whole stop (route still to go, every patch) is
			// the card's tooltip, an optional expansion that adds no control to the normal flow.
			List<String> all = guided ? g.getLines() : run.getNextLines();
			String step = guided && g.getGuide() != null ? g.getGuide() : firstOpen(all);
			List<String> lines = step == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(step);
			if (!lines.equals(shownNextLines))
			{
				shownNextLines = new ArrayList<>(lines);
				nextLines.removeAll();
				for (String line : lines)
				{
					nextLines.add(wrap(line, SpyglassTheme.TEXT_SECONDARY));
				}
				nextLines.revalidate();
				nextLines.repaint();
			}
			String tip = nextTooltip(guided ? g.getTooltip() : null, all, step);
			if (!java.util.Objects.equals(tip, nextCard.getToolTipText()))
			{
				nextCard.setToolTipText(tip);
				nextTitle.setToolTipText(tip);
			}
		}
		if (summaryLines != null && nextReady != null)
		{
			Long soonest = m.summary(run.getRunType()).getSoonestReadyAt();
			setText(nextReady, soonest == null ? " " : "Next " + run.getRunType().getTitle().toLowerCase() + " ready "
				+ com.osrstelemetry.plugin.farming.FarmingText.remaining(soonest - m.getNow()));
			nextReady.setVisible(soonest != null);
		}
		regroupRun(run);
		updateBins(run);
		for (FarmingRunView.PatchLine line : run.getLines())
		{
			RunPatchCard card = cards.get(line.getPatchId());
			if (card != null)
			{
				card.update(line);
			}
		}
	}

	// -- compost bins in the run (canonical §28.16) ---------------------------------------------

	private JPanel binSection;
	private String shownBins;

	/**
	 * The run's compost bins: each with its task now ("Take supercompost from the bin · 15"), Skip (or Undo), or what it is
	 * doing when there is nothing to do ("Supercompost rotting"). Hidden when the run took on no bins. Rebuilt only when the
	 * text changes, so refreshes keep focus and layout.
	 */
	void updateBins(FarmingRunView run)
	{
		if (binSection == null)
		{
			return;
		}
		List<com.osrstelemetry.plugin.farming.RunExtras.BinWork> work = run.getBinWork();
		String current = currentBin(run);
		StringBuilder sig = new StringBuilder(String.valueOf(binPictures != null)).append(';');
		for (com.osrstelemetry.plugin.farming.RunExtras.BinWork b : work)
		{
			sig.append(b.def.getBinId()).append('|').append(binText(b)).append('|').append(binState(b)).append('|').append(b.skipped).append('|')
				.append(b.def.getBinId().equals(current)).append('|').append(look(b)).append(';');
		}
		if (sig.toString().equals(shownBins))
		{
			return;
		}
		shownBins = sig.toString();
		// Keep the keyboard focus on the same bin's Skip / Undo when its card is rebuilt.
		String focusedBin = null;
		java.awt.Component owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (owner != null && owner.getName() != null && owner.getName().startsWith("run.bin.skip.") && javax.swing.SwingUtilities.isDescendingFrom(owner, binSection))
		{
			focusedBin = owner.getName();
		}
		binSection.removeAll();
		binCards.clear();
		if (!work.isEmpty())
		{
			binSection.add(Box.createVerticalStrut(8));
			JLabel head = label("COMPOST BINS", SpyglassFonts.smallBold(), SpyglassTheme.ACCENT_GOLD);
			head.setToolTipText("Optional: the bins you chose in Farming settings. The run waits for a bin with something to do until you do"
				+ " it or skip it; a bin still rotting is never waited for.");
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			binSection.add(head);
			for (com.osrstelemetry.plugin.farming.RunExtras.BinWork b : work)
			{
				binSection.add(Box.createVerticalStrut(4));
				RunBinCard card = new RunBinCard(b, b.def.getBinId().equals(current), binPictures, actions);
				binCards.put(b.def.getBinId(), card);
				binSection.add(fullWidth(card));
			}
		}
		binSection.revalidate();
		binSection.repaint();
		if (focusedBin != null)
		{
			RunBinCard again = binCards.get(focusedBin.substring("run.bin.skip.".length()));
			if (again != null && again.skip != null)
			{
				again.skip.requestFocusInWindow();
			}
		}
	}

	/** The run's bin cards by bin id, as shown (tests and the harness). */
	final Map<String, RunBinCard> binCards = new LinkedHashMap<>();

	private CompostBinPictures binPictures;

	/** The bins' pictures (the same drawings as the Compost bins page); without them the cards keep the neutral mark. */
	void setBinPictures(CompostBinPictures pictures)
	{
		this.binPictures = pictures;
		shownBins = null;
	}

	/** The bin the run points at now (the next stop is a bin: every crop there is done), or null. */
	static String currentBin(FarmingRunView run)
	{
		String next = run.getNextTitle();
		if (next == null || !next.endsWith(" \u00b7 Compost bin"))
		{
			return null;
		}
		String place = next.substring(0, next.length() - " \u00b7 Compost bin".length());
		for (com.osrstelemetry.plugin.farming.RunExtras.BinWork b : run.getBinWork())
		{
			if (b.isDue() && b.def.getPlace().equals(place))
			{
				return b.def.getBinId();
			}
		}
		return null;
	}

	/** What the bin holds, as last seen (no age: the card does not change every minute); unknown stays unknown. */
	static String binState(com.osrstelemetry.plugin.farming.RunExtras.BinWork b)
	{
		if (b.seen == null)
		{
			return "Not seen yet";
		}
		com.osrstelemetry.plugin.farming.CompostBin.State st = b.seen.state(b.def);
		if (st == null)
		{
			return "Not recognised";
		}
		if (st.phase == com.osrstelemetry.plugin.farming.CompostBin.Phase.ROTTED)
		{
			return st.contents.getLabel() + " rotted";
		}
		return com.osrstelemetry.plugin.farming.CompostBin.describe(b.def, st);
	}

	private static String look(com.osrstelemetry.plugin.farming.RunExtras.BinWork b)
	{
		com.osrstelemetry.plugin.farming.CompostBinLook l = b.seen == null ? null : com.osrstelemetry.plugin.farming.CompostBinLook.of(b.def, b.seen.state(b.def));
		return l == null ? "-" : l.key();
	}

	/**
	 * One compost bin in a run (pass 32): the Compost bins page's card (the bin's own picture for its state, its place, what it
	 * holds) with the run's controls instead of the setup ticks: the next action, and Skip / Undo skip while it has one. The
	 * bin the run points at now is raised and gold; a skipped bin is quiet and says so; a bin with nothing left to do says that.
	 * Purely a view of the run's bin work: drawing it never records anything.
	 */
	static final class RunBinCard extends RaisedTile
	{
		enum Kind
		{
			CURRENT,
			TO_DO,
			SKIPPED,
			NOTHING_TO_DO,
			NOT_SEEN
		}

		final Kind kind;
		final FarmingView.BinPicture picture;
		final ActionButton skip;
		final JLabel status;
		final JLabel next;

		RunBinCard(com.osrstelemetry.plugin.farming.RunExtras.BinWork b, boolean current, CompostBinPictures pictures, Actions actions)
		{
			super(null, 4, 6);
			String id = b.def.getBinId();
			setName("run.bin." + id);
			kind = b.skipped ? Kind.SKIPPED : current ? Kind.CURRENT : b.task != null ? Kind.TO_DO : b.seen == null ? Kind.NOT_SEEN : Kind.NOTHING_TO_DO;
			if (kind == Kind.CURRENT)
			{
				setFace(SpyglassTheme.CARD_SELECTED, SpyglassTheme.CARD_SELECTED);
				setRail(SpyglassTheme.ACCENT_GOLD);
			}
			else if (kind == Kind.TO_DO)
			{
				setRail(SpyglassTheme.ACCENT_BRONZE);
			}
			else if (kind == Kind.NOTHING_TO_DO)
			{
				setRail(SpyglassTheme.ACCENT_SAGE);
			}
			setLayout(new BorderLayout(6, 0));
			String state = binState(b);
			com.osrstelemetry.plugin.farming.CompostBinLook look = b.seen == null ? null : com.osrstelemetry.plugin.farming.CompostBinLook.of(b.def, b.seen.state(b.def));
			picture = new FarmingView.BinPicture(look, pictures, b.seen == null ? "Not seen yet" : state);
			picture.setName("run.bin.picture." + id);
			JPanel pictureColumn = new JPanel(new BorderLayout());
			pictureColumn.setOpaque(false);
			pictureColumn.add(picture, BorderLayout.NORTH);
			add(pictureColumn, BorderLayout.WEST);

			JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setOpaque(false);
			boolean quiet = kind == Kind.SKIPPED || kind == Kind.NOTHING_TO_DO || kind == Kind.NOT_SEEN;
			JLabel place = wrapLabel(b.def.getPlace(), SpyglassFonts.bold(), quiet ? SpyglassTheme.TEXT_SECONDARY : SpyglassTheme.TEXT_PRIMARY);
			place.setName("run.bin.place." + id);
			body.add(place);
			String statusText;
			Color statusColor;
			switch (kind)
			{
				case CURRENT:
					statusText = "Now · " + state;
					statusColor = SpyglassTheme.ACCENT_GOLD;
					break;
				case SKIPPED:
					statusText = "Skipped · " + state;
					statusColor = SpyglassTheme.TEXT_MUTED;
					break;
				case NOTHING_TO_DO:
					statusText = "\u2713 " + state + " · nothing to do now";
					statusColor = SpyglassTheme.ACCENT_SAGE;
					break;
				case NOT_SEEN:
					statusText = "Not seen yet · contents unknown";
					statusColor = SpyglassTheme.TEXT_MUTED;
					break;
				default:
					statusText = state;
					statusColor = SpyglassTheme.TEXT_SECONDARY;
					break;
			}
			status = wrapLabel(statusText, SpyglassFonts.small(), statusColor);
			status.setName("run.bin.state." + id);
			body.add(status);
			if (b.task != null)
			{
				next = wrapLabel((b.skipped ? "" : "\u25b8 ") + b.task.label, SpyglassFonts.small(),
					b.skipped ? SpyglassTheme.TEXT_MUTED : kind == Kind.CURRENT ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY);
				next.setName("run.bin.next." + id);
				body.add(next);
				skip = new ActionButton(b.skipped ? "Undo skip" : "Skip", ActionButton.Style.SECONDARY, () -> actions.skipBin(id, !b.skipped));
				skip.setName("run.bin.skip." + id);
				skip.getAccessibleContext().setAccessibleName((b.skipped ? "Undo skip for the compost bin at " : "Skip the compost bin at ") + b.def.getPlace());
				skip.setAlignmentX(Component.LEFT_ALIGNMENT);
				body.add(Box.createVerticalStrut(4));
				JPanel skipRow = new JPanel(new BorderLayout());
				skipRow.setOpaque(false);
				skipRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				skipRow.add(skip, BorderLayout.WEST);
				body.add(skipRow);
				setToolTipText(b.task.label);
			}
			else
			{
				next = null;
				skip = null;
				if (b.seen == null)
				{
					setToolTipText("Spyglass reads a bin when you are near it. Until then its contents are unknown, not empty.");
				}
			}
			add(body, BorderLayout.CENTER);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			getAccessibleContext().setAccessibleName(b.def.getPlace() + " compost bin: " + statusText + (b.task == null ? "" : ". Next: " + b.task.label));
		}

		private static JLabel wrapLabel(String text, Font font, Color color)
		{
			WrappingHtmlLabel l = new WrappingHtmlLabel(text, false);
			l.setFont(font);
			l.setForeground(color);
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
			return l;
		}
	}

	/** The bin's line: its task, "skipped", or what it shows when there is nothing to do. */
	static String binText(com.osrstelemetry.plugin.farming.RunExtras.BinWork b)
	{
		if (b.task != null)
		{
			String t = b.task.label.replace(" the compost bin", " bin").replace(" from the bin", "").replace(" to the bin", "");
			return b.skipped ? t + " (skipped)" : t;
		}
		return b.seen == null ? "not seen yet" : com.osrstelemetry.plugin.farming.CompostBin.describe(b.def, b.seen.state(b.def));
	}

	// -- run groups (Farming correction pass 2) ------------------------------------------------

	enum RunGroup
	{
		TODO,
		DONE,
		SKIPPED
	}

	final Map<RunGroup, JPanel> runGroups = new java.util.EnumMap<>(RunGroup.class);
	private final Map<RunGroup, JComponent> runSections = new java.util.EnumMap<>(RunGroup.class);
	private final Map<RunGroup, List<String>> shownRunGroups = new java.util.EnumMap<>(RunGroup.class);
	/** Whether "Completed" is open (view state; survives refreshes and rebuilds). */
	boolean completedOpen;

	static RunGroup groupOf(FarmingRunView.PatchLine line)
	{
		FarmingRunEvaluator.Outcome o = line.getProgress().getOutcome();
		if (o == FarmingRunEvaluator.Outcome.SKIPPED)
		{
			return RunGroup.SKIPPED;
		}
		// A payment or compost still due is still to do (§28.16, §28.17).
		return o.isSettled() && !line.isProtectionDue() && !line.isCompostDue() ? RunGroup.DONE : RunGroup.TODO;
	}

	private JComponent runGroup(RunGroup group, String name, boolean collapsible)
	{
		JPanel section = growingPanel();
		section.add(Box.createVerticalStrut(8));
		JPanel h = new JPanel(new BorderLayout(6, 0));
		h.setOpaque(false);
		JLabel title = label(name, SpyglassFonts.smallBold(), group == RunGroup.DONE ? SpyglassTheme.ACCENT_SAGE
			: SpyglassTheme.TILE_TEXT_QUIET);
		JLabel c = label(" ", SpyglassFonts.small(), SpyglassTheme.TEXT_MUTED);
		sectionCounts.put("RUN_" + group.name(), c);
		h.add(title, BorderLayout.CENTER);
		JPanel east = new JPanel(new BorderLayout(4, 0));
		east.setOpaque(false);
		east.add(c, BorderLayout.CENTER);
		JPanel list = growingPanel();
		if (collapsible)
		{
			Runnable[] ref = new Runnable[1];
			FarmingView.ExpandToggle t = new FarmingView.ExpandToggle(() -> ref[0].run(), "Show completed patches", "Hide completed patches");
			t.getAccessibleContext().setAccessibleName("Show or hide completed patches");
			Runnable toggle = () ->
			{
				completedOpen = !completedOpen;
				t.setOpen(completedOpen);
				list.setVisible(completedOpen);
				section.revalidate();
				section.repaint();
			};
			ref[0] = toggle;
			FarmingView.onClick(title, toggle);
			title.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			t.setOpen(completedOpen);
			list.setVisible(completedOpen);
			east.add(t, BorderLayout.EAST);
		}
		h.add(east, BorderLayout.EAST);
		section.add(fullWidth(h));
		section.add(Box.createVerticalStrut(3));
		section.add(list);
		runGroups.put(group, list);
		runSections.put(group, section);
		return section;
	}

	/** Moves patch cards between To do / Completed / Skipped in place (cards are reused; focus on a card that stays survives). */
	private void regroupRun(FarmingRunView run)
	{
		// A card that moves group (Skip → SKIPPED, Undo → back) is removed and re-added; keep the keyboard focus where it was.
		Component focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		cardMoved = false;
		try
		{
			regroupRunCards(run);
		}
		finally
		{
			if (focused != null && cardMoved)
			{
				// Removing the focused card queues a focus transfer; ask for it back after that has run.
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					if (focused.isShowing())
					{
						focused.requestFocusInWindow();
					}
				});
			}
		}
	}

	private boolean cardMoved;

	private void regroupRunCards(FarmingRunView run)
	{
		for (RunGroup g : RunGroup.values())
		{
			JPanel list = runGroups.get(g);
			if (list == null)
			{
				continue;
			}
			List<String> keys = new ArrayList<>();
			for (FarmingRunView.PatchLine line : run.getLines())
			{
				if (groupOf(line) == g)
				{
					keys.add(line.getPatchId());
				}
			}
			if (!keys.equals(shownRunGroups.get(g)))
			{
				shownRunGroups.put(g, keys);
				cardMoved = true;
				for (Component c : list.getComponents())
				{
					if (!(c instanceof RunPatchCard) || !keys.contains(((RunPatchCard) c).patchId))
					{
						list.remove(c);
					}
				}
				int index = 0;
				for (String k : keys)
				{
					RunPatchCard card = cards.get(k);
					if (card == null)
					{
						continue;
					}
					if (index >= list.getComponentCount() || list.getComponent(index) != card)
					{
						list.add(card, index);
					}
					index++;
				}
				list.revalidate();
				list.repaint();
			}
			JComponent section = runSections.get(g);
			if (section != null && section.isVisible() == keys.isEmpty())
			{
				section.setVisible(!keys.isEmpty());
			}
			JLabel c = sectionCounts.get("RUN_" + g.name());
			if (c != null)
			{
				setText(c, String.valueOf(keys.size()));
			}
		}
	}

	// -- preparation (Phase H; simplified in the Farming correction pass) ---------------------

	/**
	 * Lead with what is still needed; the route; then optional extras and what
	 * is already covered, each collapsed behind a counted header. Evidence (tab,
	 * bank age, where it was seen) sits in each row's tooltip and in Find.
	 * Rows move between groups in place as the player withdraws, so the page
	 * never rebuilds (scroll and keyboard focus survive).
	 */
	private void buildPreparation(JPanel content, FarmingRunView run, FarmingPreparationView prep, boolean endConfirm)
	{
		lastPrep = prep;
		groupLists.clear();
		groupSections.clear();
		shownGroups.clear();
		bankFilterButton = null;
		slotsLine = null;
		bountyLink = null;
		String runTitle = run.getRunTitle().toUpperCase(java.util.Locale.ROOT);
		titlePrefix = runTitle + (runTitle.length() > 12 ? "" : " RUN") + " \u00b7 "; // long titles drop "RUN" so the state fits
		title = heading(" ");
		int bins = run.getIntent().getBins().size();
		count = label(run.getLines().isEmpty() && bins > 0 ? bins + (bins == 1 ? " bin" : " bins")
			: run.getLines().size() + (run.getLines().size() == 1 ? " patch" : " patches"), SpyglassFonts.small(),
			SpyglassTheme.TEXT_SECONDARY);
		FitRow header = new FitRow(title, count);
		content.add(fullWidth(header));
		content.add(Box.createVerticalStrut(4));

		prepCard = new RaisedTile(null, 6, 8);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		prepHeadline = new WrappingHtmlLabel(" ", false);
		prepHeadline.setFont(SpyglassFonts.bold());
		prepHeadline.setForeground(SpyglassTheme.TEXT_PRIMARY);
		prepHeadline.setAlignmentX(Component.LEFT_ALIGNMENT);
		prepSublines = new JPanel();
		prepSublines.setLayout(new BoxLayout(prepSublines, BoxLayout.Y_AXIS));
		prepSublines.setOpaque(false);
		prepSublines.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(prepHeadline);
		body.add(prepSublines);
		prepCard.add(body, BorderLayout.CENTER);
		content.add(prepCard);
		content.add(Box.createVerticalStrut(6));
		beginButton = new ActionButton("Begin run", ActionButton.Style.PRIMARY, actions::begin);
		beginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(beginButton);
		content.add(Box.createVerticalStrut(4));
		bankFilterButton = new ActionButton(BANK_FILTER_SHOW, ActionButton.Style.SECONDARY, actions::toggleBankFilter);
		bankFilterButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		bankFilterButton.setToolTipText("With your bank open: show only this run's items there (the same as the bank's \"Run items\" button)."
			+ " Spyglass never withdraws or moves anything.");
		content.add(bankFilterButton);

		if (prep == null)
		{
			content.add(Box.createVerticalStrut(6));
			content.add(wrap("Checking what this run needs\u2026", SpyglassTheme.TEXT_SECONDARY));
		}
		else
		{
			slotsLine = (WrappingHtmlLabel) wrap(" ", SpyglassTheme.TEXT_SECONDARY);
			content.add(slotsLine);
			for (FarmingPreparationView.NeedRow r : prep.getNeeds())
			{
				SupplyRow row = new SupplyRow(r, sprites, this::toggleFind);
				prepRows.put(r.getKey(), row);
			}
			content.add(groupSection(FarmingPreparationView.Group.NEEDED, "STILL NEEDED", false));
			if (!prep.getSites().isEmpty())
			{
				content.add(Box.createVerticalStrut(8));
				content.add(sectionHeader("SUGGESTED ORDER", null));
				content.add(Box.createVerticalStrut(3));
				JPanel sites = growingPanel();
				boolean first = true;
				for (FarmingPreparationView.SiteRow s : prep.getSites())
				{
					if (!first)
					{
						sites.add(Box.createVerticalStrut(FarmingView.CARD_GAP));
					}
					first = false;
					DestinationRow row = new DestinationRow(s, sprites);
					siteRows.put(s.getSite(), row);
					sites.add(row);
				}
				content.add(sites);
			}
			content.add(groupSection(FarmingPreparationView.Group.OPTIONAL, "OPTIONAL", true));
			content.add(groupSection(FarmingPreparationView.Group.READY, "READY", true));
			if (run.getIntent().getRunTypes().contains(com.osrstelemetry.plugin.farming.FarmingRunType.ALLOTMENT))
			{
				content.add(Box.createVerticalStrut(6));
				bountyLink = new FarmingView.Link(" ", this::toggleBounty, SpyglassTheme.TEXT_SECONDARY, true);
				bountyLink.setToolTipText("Allotments only: a 25% chance to plant with one seed instead of three. Worn; its charges can't be read.");
				content.add(fullWidth(bountyLink));
				bountyOn = run.getIntent().getOptions() != null && run.getIntent().getOptions().isUseBounty();
				setText(bountyLink, bountyOn ? "Don't use an amulet of bounty" : "Use an amulet of bounty (allotments)");
			}
			// Pass 31: the compost per patch type stays visible and editable here; preparation follows a change.
			List<com.osrstelemetry.plugin.farming.FarmingPatchType> compostTypes = new ArrayList<>();
			for (com.osrstelemetry.plugin.farming.FarmingRunType t : run.getIntent().getRunTypes())
			{
				compostTypes.addAll(FarmingView.compostTypes(t));
			}
			JComponent compost = compostTypes.isEmpty() ? null : actions.compostChoices(compostTypes);
			if (compost != null)
			{
				content.add(Box.createVerticalStrut(8));
				content.add(compost);
			}
			for (String n : prep.getNotes())
			{
				content.add(Box.createVerticalStrut(4));
				content.add(wrap(n, SpyglassTheme.TEXT_MUTED));
			}
		}
		content.add(Box.createVerticalStrut(8));
		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		endLink = new FarmingView.Link("End run", actions::requestEnd, SpyglassTheme.TEXT_SECONDARY, true);
		endLink.getAccessibleContext().setAccessibleName("End this run");
		if (!endConfirm)
		{
			footer.add(endLink, BorderLayout.EAST);
		}
		content.add(fullWidth(footer));
		if (endConfirm)
		{
			content.add(Box.createVerticalStrut(6));
			content.add(endConfirmCard(run));
		}
		updatePreparation(prep);
	}

	static final String BANK_FILTER_SHOW = "Show run items in bank";
	static final String BANK_FILTER_HIDE = "Show all bank items";
	static final String BANK_FILTER_CLOSED = "Open a bank to filter it";

	final Map<FarmingPreparationView.Group, JPanel> groupLists = new java.util.EnumMap<>(FarmingPreparationView.Group.class);
	private final Map<FarmingPreparationView.Group, JComponent> groupSections = new java.util.EnumMap<>(FarmingPreparationView.Group.class);
	private final Map<FarmingPreparationView.Group, List<String>> shownGroups = new java.util.EnumMap<>(FarmingPreparationView.Group.class);
	/** Collapsible groups the player opened (view state; survives refreshes and rebuilds). */
	final java.util.Set<FarmingPreparationView.Group> openGroups = java.util.EnumSet.noneOf(FarmingPreparationView.Group.class);
	ActionButton bankFilterButton;
	private WrappingHtmlLabel slotsLine;
	FarmingView.Link bountyLink;
	private boolean bountyOn;

	private void toggleBounty()
	{
		bountyOn = !bountyOn;
		setText(bountyLink, bountyOn ? "Don't use an amulet of bounty" : "Use an amulet of bounty (allotments)");
		actions.setBounty(bountyOn);
	}

	/** A counted group: header (with a chevron when collapsible) and its row list; hidden while empty. */
	private JComponent groupSection(FarmingPreparationView.Group group, String name, boolean collapsible)
	{
		JPanel section = growingPanel();
		section.add(Box.createVerticalStrut(8));
		JPanel h = new JPanel(new BorderLayout(6, 0));
		h.setOpaque(false);
		JLabel title = label(name, SpyglassFonts.smallBold(), SpyglassTheme.TILE_TEXT_QUIET);
		JLabel c = label(" ", SpyglassFonts.small(), SpyglassTheme.TEXT_MUTED);
		sectionCounts.put(group.name(), c);
		h.add(title, BorderLayout.CENTER);
		JPanel east = new JPanel(new BorderLayout(4, 0));
		east.setOpaque(false);
		east.add(c, BorderLayout.CENTER);
		JPanel list = growingPanel();
		if (collapsible)
		{
			Runnable[] ref = new Runnable[1];
			FarmingView.ExpandToggle t = new FarmingView.ExpandToggle(() -> ref[0].run(), "Show " + name.toLowerCase(java.util.Locale.ROOT),
				"Hide " + name.toLowerCase(java.util.Locale.ROOT));
			t.getAccessibleContext().setAccessibleName("Show or hide " + name.toLowerCase(java.util.Locale.ROOT) + " items");
			Runnable toggle = () ->
			{
				boolean open = !openGroups.contains(group);
				if (open)
				{
					openGroups.add(group);
				}
				else
				{
					openGroups.remove(group);
				}
				t.setOpen(open);
				list.setVisible(open);
				section.revalidate();
				section.repaint();
			};
			ref[0] = toggle;
			FarmingView.onClick(title, toggle);
			title.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			boolean open = openGroups.contains(group);
			t.setOpen(open);
			list.setVisible(open);
			east.add(t, BorderLayout.EAST);
		}
		h.add(east, BorderLayout.EAST);
		section.add(fullWidth(h));
		section.add(Box.createVerticalStrut(3));
		section.add(list);
		groupLists.put(group, list);
		groupSections.put(group, section);
		return section;
	}

	/** Re-files rows into their groups when membership changed (existing row components are reused). */
	private void regroup(FarmingPreparationView prep)
	{
		for (FarmingPreparationView.Group g : FarmingPreparationView.Group.values())
		{
			JPanel list = groupLists.get(g);
			if (list == null)
			{
				continue;
			}
			List<String> keys = new ArrayList<>();
			for (FarmingPreparationView.NeedRow r : prep.rows(g))
			{
				keys.add(r.getKey());
			}
			if (!keys.equals(shownGroups.get(g)))
			{
				// Minimal moves: rows that left are removed, rows that arrived are inserted in plan order. Rows that
				// stay are never removed, so keyboard focus on them survives a withdrawal elsewhere in the list.
				shownGroups.put(g, keys);
				for (Component c : list.getComponents())
				{
					if (!(c instanceof SupplyRow) || !keys.contains(((SupplyRow) c).key))
					{
						list.remove(c);
					}
				}
				int index = 0;
				for (String k : keys)
				{
					SupplyRow row = prepRows.get(k);
					if (row == null)
					{
						continue;
					}
					if (index >= list.getComponentCount() || list.getComponent(index) != row)
					{
						list.add(row, index);
					}
					index++;
				}
				list.revalidate();
				list.repaint();
			}
			JComponent section = groupSections.get(g);
			if (section != null && section.isVisible() == keys.isEmpty())
			{
				section.setVisible(!keys.isEmpty());
			}
			JLabel c = sectionCounts.get(g.name());
			if (c != null)
			{
				setText(c, String.valueOf(keys.size()));
			}
		}
	}

	private JComponent sectionHeader(String text, String countKey)
	{
		JPanel h = new JPanel(new BorderLayout(6, 0));
		h.setOpaque(false);
		h.add(label(text, SpyglassFonts.smallBold(), SpyglassTheme.TILE_TEXT_QUIET), BorderLayout.CENTER);
		if (countKey != null)
		{
			JLabel c = label(" ", SpyglassFonts.small(), SpyglassTheme.TEXT_MUTED);
			sectionCounts.put(countKey, c);
			h.add(c, BorderLayout.EAST);
		}
		return fullWidth(h);
	}

	/** Find on a row: show (or hide) where Spyglass last saw it, and mark it for the bank outline. Passive only. */
	void toggleFind(String key)
	{
		String next = key.equals(findKey) ? null : key;
		String previous = findKey;
		findKey = next;
		FarmingPreparationView.NeedRow focus = null;
		if (lastPrep != null && next != null)
		{
			for (FarmingPreparationView.NeedRow r : lastPrep.getNeeds())
			{
				if (r.getKey().equals(next))
				{
					focus = r;
				}
			}
		}
		actions.find(focus == null ? null : focus.getItemIds());
		for (String k : new String[]{previous, next})
		{
			SupplyRow row = k == null ? null : prepRows.get(k);
			if (row != null && lastPrep != null)
			{
				for (FarmingPreparationView.NeedRow r : lastPrep.getNeeds())
				{
					if (r.getKey().equals(k))
					{
						row.update(r, k.equals(findKey));
					}
				}
			}
		}
	}

	private void updatePreparation(FarmingPreparationView prep)
	{
		if (prepHeadline == null)
		{
			return;
		}
		lastPrep = prep;
		updateBankFilterButton();
		if (prep == null)
		{
			setText(title, titlePrefix + "PREPARING");
			prepHeadline.setPlainText("Getting ready");
			setSublines(java.util.Collections.singletonList("Spyglass is checking what you carry."));
			prepCard.setRail(SpyglassTheme.ACCENT_GOLD);
			beginButton.setLabel("Begin anyway");
			beginButton.setStyle(ActionButton.Style.PRIMARY);
			return;
		}
		boolean confirmed = prep.isReady();
		setText(title, titlePrefix + (confirmed ? "READY" : "PREPARING"));
		prepHeadline.setPlainText(prep.getHeadline());
		setSublines(prep.getSublines());
		prepCard.setRail(confirmed ? SpyglassTheme.ACCENT_SAGE : prep.getReadiness() == com.osrstelemetry.plugin.prep.Readiness.UNVERIFIED
			? SpyglassTheme.ACCENT_BRONZE : SpyglassTheme.ACCENT_GOLD);
		beginButton.setLabel(prep.getBeginLabel());
		beginButton.setStyle(ActionButton.Style.PRIMARY); // always the obvious action; its label says what is still missing
		beginButton.getAccessibleContext().setAccessibleName(prep.getBeginLabel() + ": " + prep.getHeadline());
		if (slotsLine != null)
		{
			slotsLine.setPlainText(prep.getSlots().isEmpty() ? " " : prep.getSlots());
			slotsLine.setVisible(!prep.getSlots().isEmpty());
		}
		for (FarmingPreparationView.NeedRow r : prep.getNeeds())
		{
			if (r.getKey().equals(findKey) && r.isSatisfied())
			{
				findKey = null; // carried now: Find has nothing left to show
				actions.find(null);
			}
			SupplyRow row = prepRows.get(r.getKey());
			if (row != null)
			{
				row.update(r, r.getKey().equals(findKey));
			}
		}
		regroup(prep);
		for (FarmingPreparationView.SiteRow s : prep.getSites())
		{
			DestinationRow row = siteRows.get(s.getSite());
			if (row != null)
			{
				row.update(s);
			}
		}
	}

	private void updateBankFilterButton()
	{
		if (bankFilterButton == null)
		{
			return;
		}
		int state = actions.bankFilter();
		String label = state == 2 ? BANK_FILTER_HIDE : state == 1 ? BANK_FILTER_SHOW : BANK_FILTER_CLOSED;
		bankFilterButton.setLabel(label);
		if (bankFilterButton.isEnabled() != (state != 0))
		{
			bankFilterButton.setEnabled(state != 0);
		}
	}

	private void setSublines(List<String> lines)
	{
		if (lines.equals(shownSublines))
		{
			return;
		}
		shownSublines = new ArrayList<>(lines);
		prepSublines.removeAll();
		for (String line : lines)
		{
			prepSublines.add(wrap(line, SpyglassTheme.TEXT_SECONDARY));
		}
		prepSublines.revalidate();
		prepSublines.repaint();
	}

	static Color toneColor(FarmingPreparationView.Tone tone)
	{
		switch (tone)
		{
			case DONE:
				return SpyglassTheme.ACCENT_SAGE;
			case ACTION:
				return SpyglassTheme.ACCENT_GOLD;
			case UNVERIFIED:
				return SpyglassTheme.ACCENT_BRONZE;
			case WARN:
				return SpyglassTheme.FARM_EMBER;
			default:
				return SpyglassTheme.TEXT_SECONDARY;
		}
	}

	/**
	 * One supply type: [sprite] name · quantity / status [Find] / tab and age.
	 * A compact status card (no hover); Find is its only control. Carried
	 * rows drop the supporting line but keep full-strength text.
	 */
	static final class SupplyRow extends JPanel
	{
		final String key;
		final RaisedTile tile = new RaisedTile(null, 4, 6);
		final FarmingView.CropIcon icon;
		final WrappingHtmlLabel name = new WrappingHtmlLabel(" ", false);
		final JLabel quantity = new JLabel(" ");
		final WrappingHtmlLabel status = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel support = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel locate = new WrappingHtmlLabel(" ", false);
		final FarmingView.Link find;
		private FarmingPreparationView.Tone lastTone;

		SupplyRow(FarmingPreparationView.NeedRow r, FarmingView.SpriteSource sprites, java.util.function.Consumer<String> onFind)
		{
			super(new BorderLayout());
			this.key = r.getKey();
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(FarmingView.CARD_GAP, 0, 0, 0)); // the gap between cards (rows move between groups)
			setAlignmentX(Component.LEFT_ALIGNMENT);
			icon = new FarmingView.CropIcon(sprites);
			tile.setLayout(new BorderLayout(6, 0));
			JPanel iconColumn = new JPanel(new BorderLayout());
			iconColumn.setOpaque(false);
			iconColumn.add(icon, BorderLayout.NORTH);
			name.setFont(SpyglassFonts.regular());
			name.setForeground(SpyglassTheme.TEXT_PRIMARY);
			quantity.setFont(SpyglassFonts.bold());
			quantity.setForeground(SpyglassTheme.TEXT_PRIMARY);
			status.setFont(SpyglassFonts.small());
			support.setFont(SpyglassFonts.small());
			support.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
			locate.setFont(SpyglassFonts.small());
			locate.setForeground(SpyglassTheme.TEXT_PRIMARY);
			find = new FarmingView.Link("Find", () -> onFind.accept(key), SpyglassTheme.ACCENT_GOLD, true);
			find.setToolTipText("Where Spyglass last saw it");
			// Each wrapping line sits in a cell of its own, so it wraps at the width it is really given beside the quantity or
			// Find (pass 30: measured at the whole line's width, a status that wrapped lost its last line).
			JPanel line1 = new JPanel(new BorderLayout(4, 0));
			line1.setOpaque(false);
			line1.add(cell(name), BorderLayout.CENTER);
			JPanel qty = new JPanel(new BorderLayout());
			qty.setOpaque(false);
			qty.add(quantity, BorderLayout.NORTH);
			line1.add(qty, BorderLayout.EAST);
			JPanel line2 = new JPanel(new BorderLayout(4, 0));
			line2.setOpaque(false);
			line2.add(cell(status), BorderLayout.CENTER);
			JPanel findCell = new JPanel(new BorderLayout());
			findCell.setOpaque(false);
			findCell.add(find, BorderLayout.NORTH);
			line2.add(findCell, BorderLayout.EAST);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			for (JComponent c : new JComponent[]{line1, line2, support, locate})
			{
				c.setAlignmentX(Component.LEFT_ALIGNMENT);
				text.add(c);
			}
			tile.add(iconColumn, BorderLayout.WEST);
			tile.add(text, BorderLayout.CENTER);
			add(tile, BorderLayout.CENTER);
			update(r, false);
		}

		void update(FarmingPreparationView.NeedRow r, boolean finding)
		{
			icon.show(r.getItemId(), r.getLabel());
			name.setPlainText(r.getLabel());
			// Only true extras read "Optional"; recommended tools and chosen payments read "Suggested" (never counted as missing).
			String kind = "Optional".equals(r.getSection()) ? "Optional" : "Protection".equals(r.getSection()) || "Equipment".equals(r.getSection())
				? "Chosen" : "Suggested";
			String q = r.isRequired() || r.isSatisfied() ? r.getQuantity() : r.getQuantity().isEmpty() ? kind : r.getQuantity() + " \u00b7 "
				+ kind.toLowerCase();
			setText(quantity, q.isEmpty() ? " " : q);
			quantity.setFont(r.isRequired() ? SpyglassFonts.bold() : SpyglassFonts.small());
			quantity.setForeground(r.isRequired() ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TILE_TEXT_QUIET);
			status.setPlainText(r.getStatus());
			if (r.getTone() != lastTone)
			{
				lastTone = r.getTone();
				status.setForeground(toneColor(lastTone));
			}
			boolean showSupport = !r.getSupport().isEmpty();
			String tip = showSupport ? r.getSupport() : null;
			if (!java.util.Objects.equals(tip, tile.getToolTipText()))
			{
				tile.setToolTipText(tip); // evidence (tab, bank age) is a hover away, not a line on every card
				status.setToolTipText(tip);
			}
			if (support.isVisible())
			{
				support.setVisible(false);
			}
			if (find.isVisible() != r.isFindable())
			{
				if (!r.isFindable() && find.isFocusOwner())
				{
					find.transferFocus(); // carried now: keyboard focus moves on rather than vanishing
				}
				find.setVisible(r.isFindable()); // in place: withdrawing never rebuilds the list
			}
			setText(find, finding ? "Hide" : "Find");
			find.getAccessibleContext().setAccessibleName((finding ? "Hide where " : "Find ") + r.getLabel() + (finding ? " is" : ""));
			boolean showLocate = finding && r.isFindable();
			locate.setPlainText(showLocate ? r.getLocate() : " ");
			if (locate.isVisible() != showLocate)
			{
				locate.setVisible(showLocate);
			}
			tile.setRail(finding ? SpyglassTheme.ACCENT_GOLD : null);
			tile.getAccessibleContext().setAccessibleName(r.getLabel() + (q.isEmpty() ? "" : " " + q) + ", " + r.getStatus()
				+ (showSupport ? ", " + r.getSupport() : ""));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/**
	 * One destination in the suggested order: [sprite] n · Location [▸] /
	 * transport / status. The expansion (keyboard: Space / Enter on the
	 * chevron) shows the chosen transport's evidence, other options and why
	 * the rest are not usable.
	 */
	static final class DestinationRow extends JPanel
	{
		final String site;
		final RaisedTile tile = new RaisedTile(null, 4, 6);
		final FarmingView.CropIcon icon;
		final WrappingHtmlLabel location = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel transport = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel status = new WrappingHtmlLabel(" ", false);
		final FarmingView.ExpandToggle expand;
		final JPanel details = new JPanel();
		private List<String> shownDetails = new ArrayList<>();
		private FarmingPreparationView.Tone lastTone;

		DestinationRow(FarmingPreparationView.SiteRow s, FarmingView.SpriteSource sprites)
		{
			super(new BorderLayout());
			this.site = s.getSite();
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			icon = new FarmingView.CropIcon(sprites);
			expand = new FarmingView.ExpandToggle(this::toggle, "Options and details", "Hide details");
			tile.setLayout(new BorderLayout(6, 0));
			JPanel iconColumn = new JPanel(new BorderLayout());
			iconColumn.setOpaque(false);
			iconColumn.add(icon, BorderLayout.NORTH);
			location.setFont(SpyglassFonts.bold());
			location.setForeground(SpyglassTheme.TEXT_PRIMARY);
			transport.setFont(SpyglassFonts.small());
			transport.setForeground(SpyglassTheme.TEXT_SECONDARY);
			status.setFont(SpyglassFonts.small());
			details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
			details.setOpaque(false);
			details.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
			details.setVisible(false);
			JPanel toggleCell = new JPanel(new BorderLayout());
			toggleCell.setOpaque(false);
			toggleCell.add(expand, BorderLayout.NORTH);
			JPanel line1 = new JPanel(new BorderLayout(4, 0));
			line1.setOpaque(false);
			// The title wraps at its own cell's width (beside the toggle), never the whole line's: a long site name takes two
			// lines instead of painting its second line over the transport below.
			JPanel locationCell = new JPanel(new BorderLayout());
			locationCell.setOpaque(false);
			locationCell.add(location, BorderLayout.CENTER);
			line1.add(locationCell, BorderLayout.CENTER);
			line1.add(toggleCell, BorderLayout.EAST);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			for (JComponent c : new JComponent[]{line1, transport, status, details})
			{
				c.setAlignmentX(Component.LEFT_ALIGNMENT);
				text.add(c);
			}
			tile.add(iconColumn, BorderLayout.WEST);
			tile.add(text, BorderLayout.CENTER);
			add(tile, BorderLayout.CENTER);
			update(s);
		}

		boolean isExpanded()
		{
			return expand.isOpen();
		}

		/** Told when the player opens or closes the details (a page that rebuilds keeps it open). */
		java.util.function.Consumer<Boolean> onToggled = open -> { };

		void toggle()
		{
			boolean open = !expand.isOpen();
			expand.setOpen(open);
			details.setVisible(open);
			revalidate();
			repaint();
			onToggled.accept(open);
		}

		void update(FarmingPreparationView.SiteRow s)
		{
			location.setPlainText(s.getOrder() + " \u00b7 " + s.getSite());
			transport.setPlainText(s.getTransport());
			if (s.getItemId() > 0)
			{
				icon.show(s.getItemId(), s.getTransport());
			}
			status.setPlainText(s.getStatus());
			if (s.getTone() != lastTone)
			{
				lastTone = s.getTone();
				status.setForeground(toneColor(lastTone));
				tile.setRail(lastTone == FarmingPreparationView.Tone.WARN ? SpyglassTheme.FARM_EMBER
					: lastTone == FarmingPreparationView.Tone.UNVERIFIED ? SpyglassTheme.ACCENT_BRONZE : null);
			}
			if (!s.getDetails().equals(shownDetails))
			{
				shownDetails = new ArrayList<>(s.getDetails());
				details.removeAll();
				for (String d : s.getDetails())
				{
					WrappingHtmlLabel l = new WrappingHtmlLabel(d, false);
					l.setFont(SpyglassFonts.small());
					l.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					details.add(l);
				}
				details.revalidate();
				details.repaint();
			}
			expand.getAccessibleContext().setAccessibleName("Options and details for " + s.getSite());
			tile.getAccessibleContext().setAccessibleName(s.getOrder() + ", " + s.getSite() + ", " + s.getTransport() + ", " + s.getStatus());
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	// -- pieces -----------------------------------------------------------------------

	/** The first line still to do ("○ …" or plain), without its mark; null when none. */
	static String firstOpen(List<String> lines)
	{
		for (String l : lines)
		{
			if (l != null && !l.isEmpty() && !l.startsWith("✓"))
			{
				return l.startsWith("○ ") ? l.substring(2) : l;
			}
		}
		return null;
	}

	/** The NEXT card's tooltip: the action's destination/cost line, then the whole stop; null when it adds nothing. */
	static String nextTooltip(String actionTip, List<String> all, String step)
	{
		List<String> rows = new ArrayList<>();
		if (actionTip != null && !actionTip.isEmpty())
		{
			rows.add(actionTip);
		}
		boolean more = false;
		for (String l : all)
		{
			String plain = l.startsWith("○ ") ? l.substring(2) : l;
			more |= !plain.equals(step);
		}
		if (more)
		{
			rows.addAll(all);
		}
		if (rows.isEmpty())
		{
			return null;
		}
		StringBuilder b = new StringBuilder("<html>");
		for (int i = 0; i < rows.size(); i++)
		{
			b.append(i == 0 ? "" : "<br>").append(rows.get(i).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
		}
		return b.append("</html>").toString();
	}

	private JComponent nextCard()
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		nextCard = tile;
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel kicker = label("NEXT", SpyglassFonts.smallBold(), SpyglassTheme.TILE_TEXT_QUIET);
		nextTitle = new WrappingHtmlLabel(" ", false); // pass 32: wraps at narrow widths and large text, never cut short
		nextTitle.setFont(SpyglassFonts.bold());
		nextTitle.setForeground(SpyglassTheme.TEXT_PRIMARY);
		nextLines = new JPanel();
		nextLines.setLayout(new BoxLayout(nextLines, BoxLayout.Y_AXIS));
		nextLines.setOpaque(false);
		for (JComponent c : new JComponent[]{kicker, nextTitle, nextLines})
		{
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(c);
		}
		tile.add(body, BorderLayout.CENTER);
		return tile;
	}

	private JComponent pausedCard(FarmingRunView run)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		pausedTitle = new WrappingHtmlLabel(" ", false);
		pausedTitle.setFont(SpyglassFonts.bold());
		pausedTitle.setForeground(SpyglassTheme.TEXT_PRIMARY);
		String why = "Your progress is kept until you finish or end the run. Resume when you're back at your patches.";
		JComponent text = wrap(why, SpyglassTheme.TEXT_SECONDARY);
		pausedCount = label(" ", SpyglassFonts.regular(), SpyglassTheme.TEXT_PRIMARY);
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		resumeButton = new ActionButton("Resume", ActionButton.Style.PRIMARY, actions::resume);
		ActionButton end = new ActionButton("End run", ActionButton.Style.SECONDARY, actions::requestEnd);
		buttons.add(resumeButton);
		buttons.add(end);
		for (JComponent c : new JComponent[]{pausedTitle, pausedCount, text})
		{
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(c);
		}
		body.add(Box.createVerticalStrut(6));
		body.add(HomeDetailPage.growing(buttons)); // its height follows the text size
		tile.add(body, BorderLayout.CENTER);
		return tile;
	}

	private JComponent endConfirmCard(FarmingRunView run)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(SpyglassTheme.FARM_EMBER);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel q = label("End this run?", SpyglassFonts.bold(), SpyglassTheme.TEXT_PRIMARY);
		String detail = run.getProgress().getDone() > 0 ? "What you've done so far is kept in a short summary."
			: "Nothing has been done yet, so there's nothing to summarise.";
		JComponent d = wrap(detail, SpyglassTheme.TEXT_SECONDARY);
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		confirmEndButton = new ActionButton("End run", ActionButton.Style.SECONDARY, actions::confirmEnd);
		keepGoingButton = new ActionButton("Keep going", ActionButton.Style.PRIMARY, actions::cancelEnd);
		buttons.add(confirmEndButton);
		buttons.add(keepGoingButton);
		q.setAlignmentX(Component.LEFT_ALIGNMENT);
		d.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(q);
		body.add(d);
		body.add(Box.createVerticalStrut(6));
		body.add(HomeDetailPage.growing(buttons)); // its height follows the text size
		tile.add(body, BorderLayout.CENTER);
		return tile;
	}

	private void buildComplete(JPanel content, FarmingRunView run, FarmingModel m)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(run.getIntent().getEndReason() == null ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.ACCENT_GOLD);
		summaryLines = new JPanel();
		summaryLines.setLayout(new BoxLayout(summaryLines, BoxLayout.Y_AXIS));
		summaryLines.setOpaque(false);
		JLabel head = label("✓ " + run.getCountText() + " · " + run.getElapsedText(), SpyglassFonts.bold(),
			run.getIntent().getEndReason() == null ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.TEXT_PRIMARY);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		summaryLines.add(head);
		for (String line : run.getSummary())
		{
			summaryLines.add(wrap(line, SpyglassTheme.TEXT_SECONDARY));
		}
		tile.add(summaryLines, BorderLayout.CENTER);
		content.add(tile);
		content.add(Box.createVerticalStrut(6));
		nextReady = label(" ", SpyglassFonts.small(), SpyglassTheme.TEXT_MUTED);
		content.add(fullWidth(nextReady));
		content.add(Box.createVerticalStrut(8));
		doneButton = new ActionButton("Done", ActionButton.Style.PRIMARY, actions::done);
		doneButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(doneButton);
	}

	// -- one patch --------------------------------------------------------------------

	/** One included patch: [sprite] Location · Skip / run status / live status. */
	static final class RunPatchCard extends JPanel
	{
		final String patchId;
		final RaisedTile tile = new RaisedTile(null, 5, 6);
		final FarmingView.CropIcon icon;
		final WrappingHtmlLabel location = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel runStatus = new WrappingHtmlLabel(" ", false);
		final WrappingHtmlLabel live = new WrappingHtmlLabel(" ", false);
		/** Compact evidence-only indicators: "✓ Harvest  ? Compost  ○ Plant". */
		final JLabel steps = new JLabel(" ");
		final FarmingView.Link skip;
		private boolean skipped;
		/** The link is about the protection payment still due on a planted patch (§28.16), not the patch itself. */
		private boolean paymentSkip;
		private boolean paymentSkipped;
		/** ... or about the compost still due on it (§28.17); compost comes first. */
		private boolean compostSkip;
		private boolean compostSkipped;
		private FarmingModel.Tone lastTone;

		RunPatchCard(String patchId, FarmingView.SpriteSource sprites, Actions actions)
		{
			super(new BorderLayout());
			this.patchId = patchId;
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(FarmingView.CARD_GAP, 0, 0, 0)); // cards move between groups: the gap travels with them
			setAlignmentX(Component.LEFT_ALIGNMENT);
			icon = new FarmingView.CropIcon(sprites);
			skip = new FarmingView.Link("Skip", null, SpyglassTheme.TEXT_SECONDARY, true);
			skip.setAction(() ->
			{
				if (compostSkip)
				{
					actions.skipCompost(patchId, !compostSkipped);
				}
				else if (paymentSkip)
				{
					actions.skipProtection(patchId, !paymentSkipped);
				}
				else
				{
					actions.skip(patchId, !skipped);
				}
			});
			tile.setLayout(new BorderLayout(6, 0));
			JPanel iconColumn = new JPanel(new BorderLayout());
			iconColumn.setOpaque(false);
			iconColumn.add(icon, BorderLayout.NORTH);
			location.setFont(SpyglassFonts.bold());
			location.setForeground(SpyglassTheme.TEXT_PRIMARY);
			runStatus.setFont(SpyglassFonts.regular());
			live.setFont(SpyglassFonts.small());
			live.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
			JPanel locationCell = new JPanel(new BorderLayout());
			locationCell.setOpaque(false);
			locationCell.add(location, BorderLayout.CENTER);
			JPanel skipCell = new JPanel(new BorderLayout());
			skipCell.setOpaque(false);
			skipCell.add(skip, BorderLayout.NORTH);
			JPanel line1 = new JPanel(new BorderLayout(4, 0));
			line1.setOpaque(false);
			line1.add(locationCell, BorderLayout.CENTER);
			line1.add(skipCell, BorderLayout.EAST);
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			steps.setFont(SpyglassFonts.small());
			steps.setForeground(SpyglassTheme.TEXT_SECONDARY);
			steps.setToolTipText("<html>\u2713 seen done this run \u00b7 \u25cb still to do<br>? not seen yet (compost: the game's message or Inspect shows it) \u00b7 \u2013 not needed</html>");
			for (JComponent c : new JComponent[]{line1, runStatus, steps, live})
			{
				c.setAlignmentX(Component.LEFT_ALIGNMENT);
				text.add(c);
			}
			tile.add(iconColumn, BorderLayout.WEST);
			tile.add(text, BorderLayout.CENTER);
			add(tile, BorderLayout.CENTER);
		}

		static String stepsText(List<FarmingRunView.Step> steps)
		{
			List<String> parts = new ArrayList<>();
			for (FarmingRunView.Step s : steps)
			{
				parts.add(s.getText());
			}
			return String.join("  ", parts);
		}

		void update(FarmingRunView.PatchLine line)
		{
			FarmingModel.Row row = line.getRow();
			String name = row == null ? line.getPatchId() : cardLabel(row);
			location.setPlainText(name);
			runStatus.setPlainText(line.getRunText());
			if (line.getRunTone() != lastTone)
			{
				lastTone = line.getRunTone();
				runStatus.setForeground(FarmingView.color(lastTone));
			}
			String st = stepsText(line.getSteps());
			setText(steps, st.isEmpty() ? " " : st);
			if (steps.isVisible() == st.isEmpty())
			{
				steps.setVisible(!st.isEmpty());
			}
			String now = line.getAccessNote() != null ? line.getAccessNote() : line.getCondition();
			// Still to do: the action and its step marks say it all. Completed: the growing time and evidence are the details.
			boolean details = line.getProgress().getOutcome().isSettled() || line.getAccessNote() != null;
			if (live.isVisible() != details)
			{
				live.setVisible(details);
			}
			if (row != null)
			{
				live.setPlainText(now == null || now.isEmpty() ? FarmingView.PatchCardView.statusText(row) : now);
				live.setToolTipText(row.getProvenance());
				String iconLabel = FarmingIcons.label(row);
				icon.show(FarmingIcons.itemFor(row), iconLabel);
			}
			skipped = line.isSkipped();
			paymentSkipped = line.isProtectionSkipped();
			compostSkipped = line.isCompostSkipped() && line.getProgress().getOutcome().isSettled();
			compostSkip = !line.isSkippable() && !skipped && (line.isCompostDue() || compostSkipped && !line.isProtectionDue());
			paymentSkip = !compostSkip && !line.isSkippable() && !skipped && (line.isProtectionDue() || paymentSkipped);
			boolean offer = line.isSkippable() || skipped || paymentSkip || compostSkip;
			if (skip.isVisible() != offer)
			{
				skip.setVisible(offer);
			}
			String word = compostSkip ? compostSkipped ? "Undo skip" : "Skip compost"
				: paymentSkip ? paymentSkipped ? "Undo skip" : "Skip payment" : skipped ? "Undo skip" : "Skip";
			setText(skip, word);
			skip.getAccessibleContext().setAccessibleName(compostSkip ? (compostSkipped ? "Compost after all at " : "Skip the compost at ") + name
				: paymentSkip ? (paymentSkipped ? "Pay for protection after all at " : "Skip the protection payment at ") + name
				: (skipped ? "Undo skip for " : "Skip ") + name);
			FarmingRunEvaluator.Outcome o = line.getProgress().getOutcome();
			Color rail = o == FarmingRunEvaluator.Outcome.DONE ? SpyglassTheme.ACCENT_SAGE
				: (line.isCurrent() || line.isHere()) && !o.isSettled() ? SpyglassTheme.ACCENT_GOLD : null;
			tile.setRail(rail);
			tile.getAccessibleContext().setAccessibleName((line.isCurrent() && !o.isSettled() ? "Current: " : "") + name + ", " + line.getRunText()
				+ (st.isEmpty() ? "" : ", " + st));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		/** "Falador · Herb", "Catherby · Allotment (N)": the place and the patch type, so stops with several patches read apart. */
		static String cardLabel(FarmingModel.Row row)
		{
			String loc = row.getLocation();
			String type = row.getPatch().getType().getDisplayName();
			int open = loc.lastIndexOf('(');
			String area = row.getPatch().getAreaName();
			String suffix = open >= 0 && loc.endsWith(")") ? " " + loc.substring(open) : "";
			return area + " \u00b7 " + type + suffix;
		}
	}

	// -- helpers ----------------------------------------------------------------------

	private static JLabel label(String text, Font font, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(color);
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

	private static void setText(JLabel label, String text)
	{
		if (label instanceof WrappingHtmlLabel)
		{
			((WrappingHtmlLabel) label).setPlainText(text);
		}
		else if (label != null && !text.equals(label.getText()))
		{
			label.setText(text);
		}
	}

	/** A wrapping line in a panel of its own (it wraps at the cell's width, not its row's). */
	static JPanel cell(JComponent c)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(c, BorderLayout.CENTER);
		return p;
	}

	private static JLabel heading(String text)
	{
		WrappingHtmlLabel l = new WrappingHtmlLabel(text, false);
		l.setFont(SpyglassFonts.smallBold());
		l.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
		return l;
	}

	private static <T extends JComponent> T fullWidth(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
		return c;
	}

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
}
