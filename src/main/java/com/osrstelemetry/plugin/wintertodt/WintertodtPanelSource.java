package com.osrstelemetry.plugin.wintertodt;

import com.osrstelemetry.plugin.activity.WintertodtState;
import java.util.Set;
import net.runelite.api.Skill;

/**
 * What the Wintertodt companion reads (presentation / planning state only,
 * never telemetry). Implemented by the plugin from the structured activity
 * collector and the account's Wintertodt preferences; Swing-thread safe.
 */
public interface WintertodtPanelSource
{
	/** Where the player is, as far as Wintertodt presentation cares (native region only). */
	enum Location
	{
		/** The arena (region 6462): rounds and intermissions. */
		ARENA,
		/** The camp (region 6461): bank, Reward Cart. Never starts Wintertodt on its own. */
		CAMP,
		/** Anywhere else. */
		ELSEWHERE
	}

	/** The arena as the game shows it now, or null outside it. */
	WintertodtState state();

	/** Where the player is now (the arena is assumed whenever the arena state exists). */
	default Location location()
	{
		return state() != null ? Location.ARENA : Location.ELSEWHERE;
	}

	/**
	 * Rewards still owed in the Reward Cart now (the game's reward-pool varbit,
	 * or its own "now owed" / Check line); null = not known this login. A
	 * BALANCE: it can include rewards from before this session, and it drops
	 * as the cart is searched. Never session-earned rewards.
	 */
	default Integer cartRewards()
	{
		WintertodtState s = state();
		return s == null ? null : s.getCartRewards();
	}

	/** Exact XP of a skill as the game last reported it this login (Firemaking, Agility); null when unknown. */
	Integer skillXp(Skill skill);

	/** Tools carried or worn now. */
	Set<WintertodtTools> tools();

	/** Planning state; only its unique (the Reward Hunt target) is shown now. */
	WintertodtGoal goal();

	void setGoal(WintertodtGoal goal);

	/** Auto / Mass / Solo. */
	WintertodtPlaystyle.Method method();

	/** The focus shown beside a Mass / Solo method (the remembered one, XP by default). */
	WintertodtPlaystyle.Focus focus();

	void setMethod(WintertodtPlaystyle.Method method);

	void setFocus(WintertodtPlaystyle.Focus focus);

	/** Auto resolved by inference into an explicit pair (ignored unless the method is still Auto). */
	void resolveAuto(WintertodtPlaystyle.Method method, WintertodtPlaystyle.Focus focus);
}
