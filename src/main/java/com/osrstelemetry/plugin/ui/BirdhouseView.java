package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.account.AccountCapability;
import com.osrstelemetry.plugin.farming.FarmingPreparation;
import com.osrstelemetry.plugin.farming.FarmingPreparationView;
import com.osrstelemetry.plugin.farming.FarmingText;
import com.osrstelemetry.plugin.guidance.GuidanceModel;
import com.osrstelemetry.plugin.hunter.BirdhouseChoice;
import com.osrstelemetry.plugin.hunter.BirdhouseGuide;
import com.osrstelemetry.plugin.hunter.BirdhousePlan;
import com.osrstelemetry.plugin.hunter.BirdhouseRun;
import com.osrstelemetry.plugin.hunter.BirdhouseSpace;
import com.osrstelemetry.plugin.hunter.BirdhouseTier;
import com.osrstelemetry.plugin.hunter.BirdhouseTraps;
import com.osrstelemetry.plugin.prep.NeedResult;
import com.osrstelemetry.plugin.prep.PreparationState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Skill;

/**
 * Home → Bird houses (its own Home card, pass 30), presented in Farming's language and from Farming's shared pieces:
 * <ol>
 *   <li><b>Overview</b>: one selectable card per space (location, the house the game shows there, its timer, and whether the
 *   next run takes it), the bird house tier the player chooses (never chosen for them), then Start run. Start is offered
 *   only once the run can be planned (a tier when a house must be built; at least one space; no Farming run in progress).</li>
 *   <li><b>Preparation</b>: Farming's preparation page on the bird house plan: the summary and Begin run, what is still
 *   needed (item sprites, quantities, where each was seen, Find), the journey (expandable), and what is already carried
 *   (collapsed).</li>
 *   <li><b>The run</b>: NEXT (the guidance's current action), then TO DO / COMPLETED / SKIPPED cards, each with its step.</li>
 *   <li><b>Complete</b>: the summary and Done; the timers stay on the overview.</li>
 * </ol>
 * The spaces' timers are always here, outside runs. Everything comes from the spaces as the game showed them and the account
 * as read; nothing is done in the game for the player. The selection and tier are saved per RuneScape profile.
 */
final class BirdhouseView extends HomeDetailPage
{
	private BirdhouseSource source = BirdhouseSource.NONE;
	private java.util.function.LongSupplier clock = System::currentTimeMillis;
	private java.util.function.Supplier<GuidanceModel> guidance = () -> GuidanceModel.OFF;
	private FarmingView.SpriteSource sprites;
	private boolean confirmEnd;
	private Runnable afterStart = () -> { };
	/** Page state that survives rebuilds: open READY group, expanded journey rows, open COMPLETED group. */
	private boolean readyOpen;
	private boolean doneOpen;
	private final Set<String> expandedStops = new java.util.HashSet<>();
	private String findKey;

	void attach(BirdhouseSource source)
	{
		this.source = source == null ? BirdhouseSource.NONE : source;
		invalidateSignature();
	}

	void attachGuidance(java.util.function.Supplier<GuidanceModel> guidance)
	{
		this.guidance = guidance == null ? () -> GuidanceModel.OFF : guidance;
	}

	void setSprites(FarmingView.SpriteSource sprites)
	{
		this.sprites = sprites;
		invalidateSignature();
	}

	void setClock(java.util.function.LongSupplier clock)
	{
		this.clock = clock;
	}

	/** After Start Run (the panel scrolls the page to the top). */
	void setAfterStart(Runnable r)
	{
		this.afterStart = r == null ? () -> { } : r;
	}

	private void act(Runnable r)
	{
		r.run();
		invalidateSignature();
		render(clock.getAsLong());
	}

	// -- texts (pure, shared by the signature and the build) ------------------------------------------------------------------

	/** A space's status: "Full", "Full in ~23m", "Needs seeds", "Empty space", "Not seen yet". */
	static String spaceStatus(BirdhouseTraps.Status s, long now)
	{
		switch (s.getKind())
		{
			case READY:
				return "Full";
			case CATCHING:
				// Seeded before Spyglass first saw it: it may be full already (a house left from long ago); only an upper bound.
				return s.isUpperBound() ? "May already be full" : "Full in " + FarmingText.remaining(s.getReadyAt() - now);
			case BUILT:
				return "Needs seeds";
			case PARTLY_SEEDED:
				return "Needs more seeds";
			case SPACE:
				return "Empty space";
			case UNKNOWN:
				return "Unreadable";
			case UNSEEN:
			default:
				return "Not seen yet";
		}
	}

	/** The quiet line on a space: the house the game shows there and when it was read. */
	static String spaceUnder(BirdhouseTraps.Status s, long now)
	{
		if (s.getKind() == BirdhouseTraps.Status.Kind.UNSEEN)
		{
			return "Read on Fossil Island";
		}
		String tier = s.getTier() == null ? "" : s.getTier().getShortTitle() + " · ";
		return tier + seen(now, s.getSeenAt());
	}

	static String seen(long now, long at)
	{
		return now - at < 120_000L ? "seen now" : "seen " + FarmingText.age(now - at) + " ago";
	}

	static Color statusColor(BirdhouseTraps.Status.Kind k)
	{
		switch (k)
		{
			case READY:
				return SpyglassTheme.ACCENT_GOLD;
			case CATCHING:
				return SpyglassTheme.TEXT_PRIMARY;
			case BUILT:
			case PARTLY_SEEDED:
			case SPACE:
				return SpyglassTheme.ACCENT_AMBER;
			default:
				return SpyglassTheme.TEXT_SECONDARY;
		}
	}

	/** Why Start run is not offered now, or null when it is. */
	static String startBlocked(BirdhouseChoice c, BirdhouseTraps traps, boolean farmingRun, long now)
	{
		if (farmingRun)
		{
			return "Finish or end your Farming run first: guidance follows one run at a time.";
		}
		Set<BirdhouseSpace> spaces = c.spaces();
		if (spaces.isEmpty())
		{
			return "Select at least one bird house.";
		}
		if (c.getTier() == null && BirdhousePlan.tierRequired(spaces, traps, now))
		{
			return "Choose a bird house tier.";
		}
		return null;
	}

	@Override
	String signature(long now)
	{
		StringBuilder b = new StringBuilder();
		BirdhouseRun run = source.run();
		BirdhouseChoice c = source.choice();
		b.append(run == null ? "-" : run.getState() + "|" + run.isPreparing() + "|" + run.getTier() + "|" + confirmEnd).append('|').append(c.spaces())
			.append(c.getTier()).append('|').append(source.farmingRunActive()).append('|').append(readyOpen).append(doneOpen).append(expandedStops)
			.append(findKey).append('|');
		BirdhouseTraps traps = source.traps();
		for (BirdhouseTraps.Status s : traps.statuses(now))
		{
			b.append(spaceStatus(s, now)).append(spaceUnder(s, now)).append(';');
		}
		if (run != null)
		{
			for (BirdhouseRun.SpaceProgress p : run.progress(traps, now))
			{
				b.append(p.getStep()).append(BirdhouseGuide.stepText(p, run.getTier(), now)).append(';');
			}
			BirdhousePlan plan = source.plan();
			if (plan != null)
			{
				b.append(plan.getHousesToBuild()).append(plan.getHousesCarried()).append(plan.getClockworks()).append(plan.getSeedUnits())
					.append(plan.getUnmet()).append(plan.getUnknown()).append(plan.getNotes());
				for (BirdhousePlan.StopPlan s : plan.getStops())
				{
					b.append(s.getStop()).append(s.getTransport() == null ? "-" : s.getTransport().getTransport().id + s.getTransport().getState())
						.append(s.isChargeUncertain()).append(';');
				}
			}
			PreparationState p = source.preparation();
			if (p != null)
			{
				for (NeedResult r : p.getResults())
				{
					b.append(r.getNeed().getKey()).append(r.getStatus()).append(r.getCarried()).append(r.getBanked()).append(r.getStillNeeded())
						.append(r.getBankSeenAt() / 60_000L).append(';');
				}
			}
			b.append(source.readiness()).append(source.bankOpen());
			GuidanceModel g = guidance.get();
			b.append(g == null ? "" : g.getTitle() + g.getGuide() + g.getProgress());
			b.append(FarmingText.age(Math.max(0, (run.getFinishedAt() != null ? run.getFinishedAt() : now) - run.getStartedAt())));
		}
		AccountCapability cap = source.capability();
		b.append(cap == null || cap.getInventory() == null ? "" : carriedHouses(cap).toString());
		b.append(cap == null ? "" : cap.level(Skill.HUNTER) + "/" + cap.level(Skill.CRAFTING));
		return b.toString();
	}

	@Override
	void build(long now)
	{
		BirdhouseRun run = source.run();
		if (run != null && run.isPreparing())
		{
			preparation(run, now);
			return;
		}
		if (run != null && run.isActive())
		{
			active(run, now);
			return;
		}
		if (run != null)
		{
			finished(run, now);
			add(Box.createVerticalStrut(10));
		}
		overview(now);
	}

	// -- overview -----------------------------------------------------------------------------------------------------------

	private void overview(long now)
	{
		BirdhouseTraps traps = source.traps();
		BirdhouseChoice choice = source.choice();
		BirdhouseTraps.Summary sum = traps.summary(now);
		add(header("BIRD HOUSES", sum.unseen == 4 ? "Not seen yet" : HomeText.birdhouseStatus(sum, now),
			"About 50 minutes after seeding a house is full. Timers start from what Spyglass saw on Fossil Island, never from a guess."
				+ " Tick the houses your next run takes."));
		add(Box.createVerticalStrut(4));
		boolean first = true;
		for (BirdhouseTraps.Status s : traps.statuses(now))
		{
			if (!first)
			{
				add(Box.createVerticalStrut(FarmingView.CARD_GAP));
			}
			first = false;
			add(spaceCard(s, choice, now));
		}
		add(Box.createVerticalStrut(10));
		add(tierCard(choice));
		add(Box.createVerticalStrut(8));
		String blocked = startBlocked(choice, traps, source.farmingRunActive(), now);
		ActionButton start = new ActionButton("Start run", ActionButton.Style.PRIMARY, () -> {
			BirdhouseChoice c = source.choice();
			if (startBlocked(c, source.traps(), source.farmingRunActive(), clock.getAsLong()) == null && source.start(c.spaces(), c.getTier()))
			{
				afterStart.run();
			}
			invalidateSignature();
			render(clock.getAsLong());
		});
		start.setName("birdhouse.start");
		int n = choice.spaces().size();
		start.getAccessibleContext().setAccessibleName("Start a bird house run: " + n + (n == 1 ? " house" : " houses")
			+ (blocked == null ? "" : ". " + blocked));
		start.setEnabled(blocked == null);
		start.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(start);
		if (blocked != null)
		{
			add(Box.createVerticalStrut(4));
			add(wrapped(blocked, SpyglassTheme.TEXT_SECONDARY));
		}
	}

	/** One space: [sprite] Location [☑] / status / the house and when it was read. Click or Space selects it for the run. */
	private JComponent spaceCard(BirdhouseTraps.Status s, BirdhouseChoice choice, long now)
	{
		BirdhouseSpace space = s.getSpace();
		boolean on = choice.isIncluded(space);
		Runnable toggle = () -> act(() -> source.choose(source.choice().withIncluded(space, !source.choice().isIncluded(space))));
		RaisedTile tile = new RaisedTile(toggle, 5, 6);
		tile.setName("birdhouse.space." + space.name());
		tile.setLayout(new BorderLayout(6, 0));
		FarmingView.CropIcon icon = new FarmingView.CropIcon(sprites);
		BirdhouseTier observed = s.getTier();
		icon.show(observed != null ? observed.getHouseItem() : -1, observed != null ? observed.getTitle() : "No bird house seen");
		JPanel iconColumn = new JPanel(new BorderLayout());
		iconColumn.setOpaque(false);
		iconColumn.add(icon, BorderLayout.NORTH);
		WrappingHtmlLabel location = new WrappingHtmlLabel(space.getTitle(), false);
		location.setFont(SpyglassFonts.bold());
		location.setForeground(on ? SpyglassTheme.TEXT_PRIMARY : SpyglassTheme.TEXT_SECONDARY);
		FarmingView.IncludeBox box = new FarmingView.IncludeBox(toggle);
		box.setChecked(on);
		box.setFocusable(false); // the card is the control (one stop per space for the keyboard)
		box.setToolTipText(on ? "In the next run" : "Not in the next run");
		JPanel boxCell = new JPanel(new BorderLayout());
		boxCell.setOpaque(false);
		boxCell.add(box, BorderLayout.NORTH);
		JPanel locationCell = new JPanel(new BorderLayout());
		locationCell.setOpaque(false);
		locationCell.add(location, BorderLayout.CENTER);
		JPanel line1 = new JPanel(new BorderLayout(4, 0));
		line1.setOpaque(false);
		line1.add(locationCell, BorderLayout.CENTER);
		line1.add(boxCell, BorderLayout.EAST);
		String statusText = spaceStatus(s, now);
		WrappingHtmlLabel status = new WrappingHtmlLabel(statusText, false);
		status.setFont(s.getKind() == BirdhouseTraps.Status.Kind.READY ? SpyglassFonts.bold() : SpyglassFonts.regular());
		status.setForeground(statusColor(s.getKind()));
		WrappingHtmlLabel under = new WrappingHtmlLabel(spaceUnder(s, now), false);
		under.setFont(SpyglassFonts.small());
		under.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
		if (s.isUpperBound() && s.isSet())
		{
			String tip = "Seeded before Spyglass first saw it: full by " + FarmingText.remaining(s.getReadyAt() - now).replace("~", "")
				+ " at the latest, and possibly already.";
			status.setToolTipText(tip);
			under.setToolTipText(tip);
		}
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		for (JComponent c : new JComponent[]{line1, status, under})
		{
			c.setAlignmentX(Component.LEFT_ALIGNMENT);
			text.add(c);
		}
		if (!on)
		{
			// Said in words, not only by the box: a space left out reads as such.
			WrappingHtmlLabel out = new WrappingHtmlLabel("Not in the next run", false);
			out.setFont(SpyglassFonts.small());
			out.setForeground(SpyglassTheme.TEXT_SECONDARY);
			out.setAlignmentX(Component.LEFT_ALIGNMENT);
			text.add(out);
			status.setForeground(SpyglassTheme.TEXT_SECONDARY);
		}
		tile.add(iconColumn, BorderLayout.WEST);
		tile.add(text, BorderLayout.CENTER);
		Color rail = s.getKind() == BirdhouseTraps.Status.Kind.READY ? SpyglassTheme.ACCENT_GOLD : null;
		tile.setRail(rail == null ? null : on ? rail : FarmingView.mix(rail, SpyglassTheme.TILE_FACE, 0.6));
		tile.getAccessibleContext().setAccessibleName(space.getTitle() + ": " + statusText + ". " + (on ? "In the next run" : "Not in the next run")
			+ ". Press Space to " + (on ? "leave it out" : "include it"));
		tile.setToolTipText(on ? "In the next run: click to leave it out" : "Not in the next run: click to include it");
		return fullWidth(tile);
	}

	/** The tier the player builds: ‹ Mahogany bird house › with its levels; Spyglass never chooses or recommends one. */
	private JComponent tierCard(BirdhouseChoice choice)
	{
		BirdhouseTier tier = choice.getTier();
		AccountCapability cap = source.capability();
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(tier == null ? SpyglassTheme.ACCENT_GOLD : null);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		JLabel kicker = label("BIRD HOUSE TIER", SpyglassFonts.smallBold(), SpyglassTheme.TILE_TEXT_QUIET);
		kicker.setToolTipText("The bird house you build after emptying. Your choice: Spyglass never picks one for you.");
		BirdhouseTier[] all = BirdhouseTier.values();
		int at = tier == null ? -1 : tier.ordinal();
		FarmingView.Link prev = new FarmingView.Link("‹", () -> act(() -> source.choose(source.choice().withTier(at <= 0 ? all[0] : all[at - 1]))),
			SpyglassTheme.TEXT_PRIMARY, true);
		prev.setName("birdhouse.tier.prev");
		prev.setFont(SpyglassFonts.bold());
		prev.getAccessibleContext().setAccessibleName("Previous bird house tier");
		prev.setEnabled(at != 0);
		FarmingView.Link next = new FarmingView.Link("›", () -> act(() -> source.choose(source.choice().withTier(at < 0 ? all[0]
			: all[Math.min(all.length - 1, at + 1)]))), SpyglassTheme.TEXT_PRIMARY, true);
		next.setName("birdhouse.tier.next");
		next.setFont(SpyglassFonts.bold());
		next.getAccessibleContext().setAccessibleName("Next bird house tier");
		next.setEnabled(at != all.length - 1);
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setOpaque(false);
		line.add(prev, BorderLayout.WEST);
		FarmingView.CropIcon icon = new FarmingView.CropIcon(sprites);
		icon.show(tier == null ? -1 : tier.getHouseItem(), tier == null ? "No tier chosen" : tier.getTitle());
		JPanel mid = new JPanel(new BorderLayout(6, 0));
		mid.setOpaque(false);
		mid.add(icon, BorderLayout.WEST);
		WrappingHtmlLabel name = new WrappingHtmlLabel(tier == null ? "Choose a tier" : tier.getTitle(), false);
		name.setName("birdhouse.tier.name");
		name.setFont(SpyglassFonts.bold());
		name.setForeground(tier == null ? SpyglassTheme.ACCENT_GOLD : SpyglassTheme.TEXT_PRIMARY);
		JPanel nameCell = new JPanel(new BorderLayout());
		nameCell.setOpaque(false);
		nameCell.add(name, BorderLayout.CENTER);
		mid.add(nameCell, BorderLayout.CENTER);
		line.add(mid, BorderLayout.CENTER);
		line.add(next, BorderLayout.EAST);
		body.add(left(kicker));
		body.add(Box.createVerticalStrut(3));
		body.add(left(line));
		if (tier != null)
		{
			Integer hunter = cap == null ? null : cap.level(Skill.HUNTER);
			Integer crafting = cap == null ? null : cap.level(Skill.CRAFTING);
			body.add(left(levelLine("Hunter " + tier.getHunter() + " to place", hunter, tier.getHunter())));
			body.add(left(levelLine("Crafting " + tier.getCrafting() + " to make from " + tier.getLogTitle().toLowerCase(Locale.ROOT), crafting,
				tier.getCrafting())));
		}
		Map<BirdhouseTier, Integer> carried = carriedHouses(cap);
		if (!carried.isEmpty())
		{
			List<String> c = new ArrayList<>();
			carried.forEach((t, n) -> c.add(n + " " + t.getShortTitle().toLowerCase(Locale.ROOT)));
			body.add(left(wrapped("Carried: " + String.join(", ", c), SpyglassTheme.TEXT_SECONDARY)));
		}
		tile.add(body, BorderLayout.CENTER);
		tile.getAccessibleContext().setAccessibleName("Bird house tier: " + (tier == null ? "none chosen" : tier.getTitle()));
		return fullWidth(tile);
	}

	/** "Hunter 49 to place ✓" in sage, "(you have 40)" in ember, "(level not read)" quietly. */
	private static JComponent levelLine(String text, Integer have, int need)
	{
		boolean met = have != null && have >= need;
		String s = text + (have == null ? " · level not read" : met ? " ✓" : " · you have " + have);
		return wrapped(s, have == null ? SpyglassTheme.TEXT_SECONDARY : met ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.FARM_EMBER);
	}

	static Map<BirdhouseTier, Integer> carriedHouses(AccountCapability cap)
	{
		Map<BirdhouseTier, Integer> out = new java.util.EnumMap<>(BirdhouseTier.class);
		if (cap == null || cap.getInventory() == null)
		{
			return out;
		}
		for (BirdhouseTier t : BirdhouseTier.values())
		{
			int n = cap.getInventory().getOrDefault(t.getHouseItem(), 0);
			if (n > 0)
			{
				out.put(t, n);
			}
		}
		return out;
	}

	// -- preparation (Farming's page on the bird house plan) ----------------------------------------------------------------

	/** The page's preparation view (tests), or null while it is on its way. */
	FarmingPreparationView preparationView(long now)
	{
		BirdhousePlan plan = source.plan();
		PreparationState state = source.preparation();
		if (plan == null || state == null)
		{
			return null;
		}
		List<FarmingPreparationView.SiteRow> sites = new ArrayList<>();
		int order = 0;
		for (BirdhousePlan.StopPlan s : plan.getStops())
		{
			order++;
			if (order == 1)
			{
				sites.add(FarmingPreparationView.siteRow(s.getTitle(), order, s.getTransport(), s.getNeedKeys(), s.isChargeUncertain(), false,
					Collections.emptyList(), Collections.emptyList(), s.getLegs(), s.getWalkTiles(), s.getAlternatives(), Collections.emptyList(),
					Collections.emptyList(), state));
			}
			else
			{
				// On Fossil Island already: no supplies, nothing to confirm.
				sites.add(new FarmingPreparationView.SiteRow(s.getTitle(), order, "On Fossil Island", -1, FarmingPreparationView.Tone.DONE,
					"✓ Walk or take the magic mushtree", Collections.singletonList("Guidance shows the way from the stop before."), false));
			}
		}
		List<String> notes = new ArrayList<>();
		for (String u : plan.getUnmet())
		{
			notes.add("Needs " + u + ".");
		}
		for (String u : plan.getUnknown())
		{
			notes.add("Not confirmed: " + u + ".");
		}
		notes.addAll(plan.getNotes());
		return FarmingPreparationView.of(state, sites, notes, source.bankOpen(), null, now);
	}

	private void preparation(BirdhouseRun run, long now)
	{
		int houses = run.getSpaces().size();
		BirdhouseTier tier = run.getTier();
		FarmingPreparationView prep = preparationView(now);
		add(header("BIRD HOUSE RUN · " + (prep != null && prep.isReady() ? "READY" : "PREPARING"),
			(tier == null ? "" : tier.getShortTitle() + " · ") + houses + (houses == 1 ? " house" : " houses"),
			"What the run needs, from what each space showed when it started. Carried counts; the bank as last seen."));
		add(Box.createVerticalStrut(4));
		RaisedTile card = new RaisedTile(null, 6, 8);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		if (prep == null)
		{
			body.add(left(wrapped("Getting ready", SpyglassTheme.TEXT_PRIMARY, SpyglassFonts.bold())));
			body.add(left(wrapped("Spyglass is checking what you carry.", SpyglassTheme.TEXT_SECONDARY)));
			card.setRail(SpyglassTheme.ACCENT_GOLD);
		}
		else
		{
			body.add(left(wrapped(prep.getHeadline(), SpyglassTheme.TEXT_PRIMARY, SpyglassFonts.bold())));
			for (String line : prep.getSublines())
			{
				body.add(left(wrapped(line, SpyglassTheme.TEXT_SECONDARY)));
			}
			card.setRail(prep.isReady() ? SpyglassTheme.ACCENT_SAGE : prep.getReadiness() == com.osrstelemetry.plugin.prep.Readiness.UNVERIFIED
				? SpyglassTheme.ACCENT_BRONZE : SpyglassTheme.ACCENT_GOLD);
		}
		card.add(body, BorderLayout.CENTER);
		add(fullWidth(card));
		add(Box.createVerticalStrut(6));
		String beginLabel = prep == null ? "Begin anyway" : prep.getBeginLabel();
		ActionButton begin = new ActionButton(beginLabel, ActionButton.Style.PRIMARY, () -> act(source::begin));
		begin.setName("birdhouse.begin");
		begin.getAccessibleContext().setAccessibleName(beginLabel + (prep == null ? "" : ": " + prep.getHeadline()));
		begin.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(begin);
		if (prep != null)
		{
			if (!prep.getSlots().isEmpty())
			{
				add(Box.createVerticalStrut(4));
				add(wrapped(prep.getSlots(), SpyglassTheme.TEXT_SECONDARY));
			}
			List<FarmingPreparationView.NeedRow> needed = new ArrayList<>(prep.rows(FarmingPreparationView.Group.NEEDED));
			needed.addAll(prep.rows(FarmingPreparationView.Group.OPTIONAL));
			if (!needed.isEmpty())
			{
				add(groupHeader("STILL NEEDED", needed.size(), null));
				for (FarmingPreparationView.NeedRow r : needed)
				{
					add(supplyRow(r));
				}
			}
			if (!prep.getSites().isEmpty())
			{
				add(groupHeader("TRAVEL", -1, null));
				boolean first = true;
				for (FarmingPreparationView.SiteRow s : prep.getSites())
				{
					if (!first)
					{
						add(Box.createVerticalStrut(FarmingView.CARD_GAP));
					}
					first = false;
					FarmingRunPage.DestinationRow row = new FarmingRunPage.DestinationRow(s, sprites);
					String key = s.getSite();
					row.expand.setName("birdhouse.stop." + s.getOrder());
					if (expandedStops.contains(key))
					{
						row.toggle();
					}
					row.onToggled = open -> {
						if (open)
						{
							expandedStops.add(key);
						}
						else
						{
							expandedStops.remove(key);
						}
					};
					add(row);
				}
			}
			List<FarmingPreparationView.NeedRow> ready = prep.rows(FarmingPreparationView.Group.READY);
			if (!ready.isEmpty())
			{
				add(groupHeader("READY", ready.size(), () -> act(() -> readyOpen = !readyOpen)));
				if (readyOpen)
				{
					for (FarmingPreparationView.NeedRow r : ready)
					{
						add(supplyRow(r));
					}
				}
			}
			for (String n : prep.getNotes())
			{
				add(Box.createVerticalStrut(4));
				add(wrapped(n, n.startsWith("Needs ") ? SpyglassTheme.FARM_EMBER : SpyglassTheme.TEXT_SECONDARY));
			}
		}
		add(Box.createVerticalStrut(8));
		footer(null);
	}

	private JComponent supplyRow(FarmingPreparationView.NeedRow r)
	{
		FarmingRunPage.SupplyRow row = new FarmingRunPage.SupplyRow(r, sprites, key -> act(() -> {
			findKey = key.equals(findKey) ? null : key;
			source.find(findKey == null ? null : r.getItemIds());
		}));
		row.update(r, r.getKey().equals(findKey));
		row.find.setName("birdhouse.find." + r.getKey());
		return row;
	}

	// -- the run ------------------------------------------------------------------------------------------------------------

	private void active(BirdhouseRun run, long now)
	{
		BirdhouseTraps traps = source.traps();
		int[] c = run.counts(traps, now);
		add(header("BIRD HOUSE RUN", c[0] + " of " + (c[2] - c[1]) + " set · " + FarmingText.age(Math.max(0, now - run.getStartedAt())), null));
		add(Box.createVerticalStrut(4));
		add(nextCard(run, now));
		List<JComponent> todo = new ArrayList<>();
		List<JComponent> done = new ArrayList<>();
		List<JComponent> skipped = new ArrayList<>();
		for (BirdhouseRun.SpaceProgress p : run.progress(traps, now))
		{
			JComponent card = runCard(p, run, now);
			if (p.getStep() == BirdhouseRun.Step.DONE || p.getStep() == BirdhouseRun.Step.FILLING)
			{
				done.add(card);
			}
			else if (p.getStep() == BirdhouseRun.Step.SKIPPED)
			{
				skipped.add(card);
			}
			else
			{
				todo.add(card);
			}
		}
		section("TO DO", todo, null, true);
		section("COMPLETED", done, () -> act(() -> doneOpen = !doneOpen), doneOpen);
		section("SKIPPED", skipped, null, true);
		add(Box.createVerticalStrut(8));
		footer(null);
	}

	private void section(String name, List<JComponent> cards, Runnable toggle, boolean open)
	{
		if (cards.isEmpty())
		{
			return;
		}
		add(groupHeader(name, cards.size(), toggle));
		if (!open)
		{
			return;
		}
		for (JComponent c : cards)
		{
			add(c);
		}
	}

	/** NEXT: the guidance's current action in one line, and its instruction (Farming's card). */
	private JComponent nextCard(BirdhouseRun run, long now)
	{
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.add(left(label("NEXT", SpyglassFonts.smallBold(), SpyglassTheme.TILE_TEXT_QUIET)));
		GuidanceModel g = guidance.get();
		String title;
		String line;
		if (source.farmingRunActive())
		{
			title = "Farming run first";
			line = "Guidance is following your Farming run; this run continues when it ends.";
		}
		else if (g != null && g.isActive() && g.getTitle() != null)
		{
			title = g.getTitle();
			line = g.getGuide();
			if (g.getTooltip() != null)
			{
				tile.setToolTipText(g.getTooltip());
			}
		}
		else
		{
			BirdhouseRun.SpaceProgress first = null;
			for (BirdhouseRun.SpaceProgress p : run.progress(source.traps(), now))
			{
				if (!p.isSettled() && first == null)
				{
					first = p;
				}
			}
			title = first == null ? "Everything here is done" : first.getSpace().getTitle();
			line = first == null ? null : BirdhouseGuide.stepText(first, run.getTier(), now);
		}
		body.add(left(wrapped(title, SpyglassTheme.TEXT_PRIMARY, SpyglassFonts.bold())));
		if (line != null)
		{
			body.add(left(wrapped(line, SpyglassTheme.TEXT_SECONDARY)));
		}
		tile.add(body, BorderLayout.CENTER);
		tile.getAccessibleContext().setAccessibleName("Next: " + title + (line == null ? "" : ". " + line));
		return fullWidth(tile);
	}

	/** One space in the run: [sprite] Location · Skip / its step / the house and its timer (Farming's run card). */
	private JComponent runCard(BirdhouseRun.SpaceProgress p, BirdhouseRun run, long now)
	{
		BirdhouseSpace s = p.getSpace();
		BirdhouseRun.Step step = p.getStep();
		boolean done = step == BirdhouseRun.Step.DONE || step == BirdhouseRun.Step.FILLING;
		boolean skippedNow = step == BirdhouseRun.Step.SKIPPED;
		RaisedTile tile = new RaisedTile(null, 5, 6);
		tile.setLayout(new BorderLayout(6, 0));
		FarmingView.CropIcon icon = new FarmingView.CropIcon(sprites);
		BirdhouseTier shown = p.getStatus().getTier() != null ? p.getStatus().getTier() : run.getTier();
		icon.show(shown == null ? -1 : shown.getHouseItem(), shown == null ? "Bird house" : shown.getTitle());
		JPanel iconColumn = new JPanel(new BorderLayout());
		iconColumn.setOpaque(false);
		iconColumn.add(icon, BorderLayout.NORTH);
		WrappingHtmlLabel location = new WrappingHtmlLabel(s.getTitle(), false);
		location.setFont(SpyglassFonts.bold());
		location.setForeground(skippedNow ? SpyglassTheme.TEXT_SECONDARY : SpyglassTheme.TEXT_PRIMARY);
		FarmingView.Link skip = new FarmingView.Link(skippedNow ? "Undo skip" : "Skip", () -> act(() -> source.skip(s, !skippedNow)),
			SpyglassTheme.TEXT_SECONDARY, true);
		skip.setName("birdhouse." + (skippedNow ? "unskip." : "skip.") + s.name());
		skip.getAccessibleContext().setAccessibleName((skippedNow ? "Undo skip of " : "Skip ") + s.getTitle());
		skip.setVisible(!done);
		JPanel skipCell = new JPanel(new BorderLayout());
		skipCell.setOpaque(false);
		skipCell.add(skip, BorderLayout.NORTH);
		JPanel locationCell = new JPanel(new BorderLayout());
		locationCell.setOpaque(false);
		locationCell.add(location, BorderLayout.CENTER);
		JPanel line1 = new JPanel(new BorderLayout(4, 0));
		line1.setOpaque(false);
		line1.add(locationCell, BorderLayout.CENTER);
		line1.add(skipCell, BorderLayout.EAST);
		String stepText = step == BirdhouseRun.Step.FILLING ? "✓ Seeded before the run · nothing to do" : done ? "✓ Set and seeded" : skippedNow ? "Skipped" : BirdhouseGuide.stepText(p, run.getTier(), now);
		WrappingHtmlLabel runStatus = new WrappingHtmlLabel(stepText, false);
		runStatus.setFont(SpyglassFonts.regular());
		runStatus.setForeground(done ? SpyglassTheme.ACCENT_SAGE : skippedNow ? SpyglassTheme.TEXT_SECONDARY : SpyglassTheme.ACCENT_GOLD);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		line1.setAlignmentX(Component.LEFT_ALIGNMENT);
		runStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(line1);
		text.add(runStatus);
		if (done || skippedNow)
		{
			WrappingHtmlLabel live = new WrappingHtmlLabel(spaceStatus(p.getStatus(), now) + " · " + spaceUnder(p.getStatus(), now), false);
			live.setFont(SpyglassFonts.small());
			live.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
			live.setAlignmentX(Component.LEFT_ALIGNMENT);
			text.add(live);
		}
		tile.add(iconColumn, BorderLayout.WEST);
		tile.add(text, BorderLayout.CENTER);
		tile.setRail(done ? SpyglassTheme.ACCENT_SAGE : skippedNow ? null : SpyglassTheme.ACCENT_GOLD);
		tile.getAccessibleContext().setAccessibleName(s.getTitle() + ", " + stepText);
		JPanel wrap = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(FarmingView.CARD_GAP, 0, 0, 0));
		wrap.add(tile, BorderLayout.CENTER);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		return wrap;
	}

	/** "End run" at the right, or the confirmation card (Farming's). */
	private void footer(JComponent leftLink)
	{
		if (confirmEnd)
		{
			RaisedTile tile = new RaisedTile(null, 6, 8);
			tile.setRail(SpyglassTheme.FARM_EMBER);
			JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setOpaque(false);
			body.add(left(label("End this run?", SpyglassFonts.bold(), SpyglassTheme.TEXT_PRIMARY)));
			body.add(left(wrapped("The houses you set stay read; timers carry on.", SpyglassTheme.TEXT_SECONDARY)));
			body.add(Box.createVerticalStrut(6));
			JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
			buttons.setOpaque(false);
			ActionButton yes = new ActionButton("End run", ActionButton.Style.SECONDARY, () -> act(() -> {
				confirmEnd = false;
				source.end();
			}));
			yes.setName("birdhouse.end.confirm");
			ActionButton no = new ActionButton("Keep going", ActionButton.Style.PRIMARY, () -> act(() -> confirmEnd = false));
			no.setName("birdhouse.end.cancel");
			buttons.add(yes);
			buttons.add(no);
			buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
			body.add(left(buttons));
			tile.add(body, BorderLayout.CENTER);
			add(fullWidth(tile));
			return;
		}
		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		if (leftLink != null)
		{
			footer.add(leftLink, BorderLayout.WEST);
		}
		FarmingView.Link end = new FarmingView.Link("End run", () -> act(() -> confirmEnd = true), SpyglassTheme.TEXT_SECONDARY, true);
		end.setName("birdhouse.end");
		end.getAccessibleContext().setAccessibleName("End this run");
		end.setToolTipText("Stops guidance; what you did stays read");
		footer.add(end, BorderLayout.EAST);
		add(fullWidth(footer));
	}

	private void finished(BirdhouseRun run, long now)
	{
		BirdhouseTraps traps = source.traps();
		int[] c = run.counts(traps, now);
		boolean complete = run.getState() == BirdhouseRun.State.COMPLETE;
		add(header("BIRD HOUSE RUN", null, null));
		add(Box.createVerticalStrut(4));
		RaisedTile tile = new RaisedTile(null, 6, 8);
		tile.setRail(complete ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.ACCENT_GOLD);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		String age = FarmingText.age(Math.max(0, (run.getFinishedAt() == null ? now : run.getFinishedAt()) - run.getStartedAt()));
		body.add(left(wrapped((complete ? "✓ " : "") + c[0] + " set" + (c[1] > 0 ? " · " + c[1] + " skipped" : "") + " · " + age,
			complete ? SpyglassTheme.ACCENT_SAGE : SpyglassTheme.TEXT_PRIMARY, SpyglassFonts.bold())));
		body.add(left(wrapped(complete ? "Run complete." : "Run ended.", SpyglassTheme.TEXT_SECONDARY)));
		BirdhouseTraps.Summary s = traps.summary(now);
		if (s.catching > 0)
		{
			body.add(left(wrapped("All full " + FarmingText.remaining(s.allReadyAt - now).replace("~", "in ~") + ".", SpyglassTheme.TEXT_SECONDARY)));
		}
		tile.add(body, BorderLayout.CENTER);
		add(fullWidth(tile));
		add(Box.createVerticalStrut(8));
		ActionButton done = new ActionButton("Done", ActionButton.Style.PRIMARY, () -> act(source::dismiss));
		done.setName("birdhouse.done");
		done.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(done);
	}

	// -- pieces (Farming's page vocabulary) -----------------------------------------------------------------------------------

	/** The page's quiet heading line: "BIRD HOUSE RUN · PREPARING" with a count at the right; the tooltip explains. */
	private static JComponent header(String title, String right, String tip)
	{
		WrappingHtmlLabel t = new WrappingHtmlLabel(title, false); // wraps at a large text size rather than being cut short
		t.setFont(SpyglassFonts.smallBold());
		t.setForeground(SpyglassTheme.TILE_TEXT_QUIET);
		JLabel r = label(right == null ? " " : right, SpyglassFonts.small(), SpyglassTheme.TEXT_SECONDARY);
		FitRow row = new FitRow(t, r);
		if (tip != null)
		{
			row.setToolTipText(tip);
			t.setToolTipText(tip);
		}
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	/** A counted group header ("STILL NEEDED  2"); collapsible with a chevron when it has a toggle (Space / Enter). */
	private JComponent groupHeader(String name, int count, Runnable toggle)
	{
		JPanel section = new JPanel(new BorderLayout(6, 0));
		section.setOpaque(false);
		section.setBorder(BorderFactory.createEmptyBorder(8, 0, 3, 0));
		JLabel title = label(name, SpyglassFonts.smallBold(), SpyglassTheme.TILE_TEXT_QUIET);
		section.add(title, BorderLayout.CENTER);
		JPanel east = new JPanel(new BorderLayout(4, 0));
		east.setOpaque(false);
		if (count >= 0)
		{
			east.add(label(String.valueOf(count), SpyglassFonts.small(), SpyglassTheme.TEXT_SECONDARY), BorderLayout.CENTER);
		}
		if (toggle != null)
		{
			boolean open = "READY".equals(name) ? readyOpen : doneOpen;
			FarmingView.ExpandToggle t = new FarmingView.ExpandToggle(toggle, "Show " + name.toLowerCase(Locale.ROOT), "Hide " + name.toLowerCase(Locale.ROOT));
			t.setOpen(open);
			t.setName("birdhouse.group." + name.toLowerCase(Locale.ROOT));
			t.getAccessibleContext().setAccessibleName("Show or hide " + name.toLowerCase(Locale.ROOT));
			FarmingView.onClick(title, toggle);
			title.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			east.add(t, BorderLayout.EAST);
		}
		section.add(east, BorderLayout.EAST);
		return fullWidth(section);
	}

	private static JLabel label(String text, java.awt.Font font, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(color);
		return l;
	}

	private static JComponent wrapped(String text, Color color)
	{
		return wrapped(text, color, SpyglassFonts.small());
	}

	private static JComponent wrapped(String text, Color color, java.awt.Font font)
	{
		WrappingHtmlLabel l = new WrappingHtmlLabel(text, false);
		l.setFont(font);
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static <T extends JComponent> T left(T c)
	{
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JComponent fullWidth(JComponent c)
	{
		return HomeDetailPage.growing(c);
	}
}
