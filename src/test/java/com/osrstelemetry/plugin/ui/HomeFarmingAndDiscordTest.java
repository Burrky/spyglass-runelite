package com.osrstelemetry.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstelemetry.plugin.farming.FarmingModel;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/** Pass 32: Home's Farming card colours follow its chosen state; the Discord invite is prominent and opens once. */
public class HomeFarmingAndDiscordTest
{
	@After
	public void reset()
	{
		Appearance.applyRoles(Appearance.DEFAULT);
	}

	@Test
	public void farmingCard_textAndRailAgreeWithTheChosenState()
	{
		HomeView home = new HomeView(null, () -> { });
		long now = FarmingCategoryGridTest.T0;
		java.util.Map<String, com.osrstelemetry.plugin.farming.PatchKnowledge> known = FarmingCategoryGridTest.mixed(now);
		home.renderFarming(FarmingCategoryGridTest.model(known, now).getHome()); // 1 ready, 5 growing, 1 dead
		assertTrue(home.farmingHeadline().getText(), home.farmingHeadline().getText().contains("1 Ready"));
		assertEquals(SpyglassTheme.STATUS_SUCCESS, home.farmingHeadline().getForeground());
		assertEquals(SpyglassTheme.STATUS_SUCCESS, home.farmingCard().getRail());
		known.remove("herb.falador");
		home.renderFarming(FarmingCategoryGridTest.model(known, now).getHome());
		assertTrue(home.farmingHeadline().getText().contains("5 Growing"));
		assertEquals(SpyglassTheme.TEXT_PRIMARY, home.farmingHeadline().getForeground());
		assertNull("growing: no alarm accent", home.farmingCard().getRail());
		known.keySet().removeIf(k -> !k.equals("herb.ardougne"));
		home.renderFarming(FarmingCategoryGridTest.model(known, now).getHome());
		assertTrue(home.farmingHeadline().getText().contains("1 Dead"));
		assertEquals(SpyglassTheme.STATUS_DANGER, home.farmingHeadline().getForeground());
		assertEquals(SpyglassTheme.STATUS_DANGER, home.farmingCard().getRail());
	}

	@Test
	public void discord_isFullWidthBlue_opensTheExactInviteOnce_andGrowsWithTheText()
	{
		HomeView home = new HomeView(null, () -> { });
		List<String> opened = new ArrayList<>();
		home.browser = opened::add;
		home.setSize(new Dimension(225, 1400));
		FarmingCategoryGridTest.layout(home);
		ActionButton d = home.discordButton();
		assertEquals(ActionButton.Style.DISCORD, d.getStyle());
		assertEquals("Join Spyglass Discord", d.getLabel());
		int avail = home.getWidth() - home.getInsets().left - home.getInsets().right;
		assertEquals("full width", avail, d.getWidth());
		int standard = d.getPreferredSize().height;
		assertTrue("comfortably taller than an ordinary button", standard >= SpyglassFonts.px(36));

		FarmingCategoryGridTest.click(d);
		FarmingCategoryGridTest.key(d, java.awt.event.KeyEvent.VK_ENTER);
		assertEquals(java.util.Arrays.asList("https://discord.gg/tgdNjt6Xjp", "https://discord.gg/tgdNjt6Xjp"), opened);

		// Largest text: taller, never clipped (wraps when it has to).
		Appearance.apply(home, new Appearance(com.osrstelemetry.plugin.SidebarTextSize.values()[com.osrstelemetry.plugin.SidebarTextSize.values().length - 1], com.osrstelemetry.plugin.SidebarTextContrast.HIGH));
		home.setSize(new Dimension(225, 1600));
		FarmingCategoryGridTest.layout(home);
		java.awt.FontMetrics fm = d.getFontMetrics(d.getFont());
		assertTrue(d.getPreferredSize().height > standard);
		assertTrue(d.getPreferredSize().height >= fm.getHeight() * ActionButton.lines(d.getLabel(), fm, d.getWidth() - 12 - fm.getHeight() - 8).size() + 10);
	}
}
