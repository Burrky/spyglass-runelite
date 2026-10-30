package com.osrstelemetry.plugin;

import com.osrstelemetry.plugin.ui.OsrsTelemetryPanel;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

/**
 * Pass 30: when the sidebar's text size or contrast changes in RuneLite's settings, the panel follows at once. Presentation
 * only, and deliberately not the plugin itself: no setting pauses, reseeds or otherwise touches telemetry collection.
 */
final class SidebarAppearanceListener
{
	private final Supplier<OsrsTelemetryPanel> panel;

	SidebarAppearanceListener(Supplier<OsrsTelemetryPanel> panel)
	{
		this.panel = panel;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"osrstelemetry".equals(event.getGroup()) || !("textSize".equals(event.getKey()) || "textContrast".equals(event.getKey())))
		{
			return;
		}
		OsrsTelemetryPanel p = panel.get();
		if (p != null)
		{
			SwingUtilities.invokeLater(p::appearanceConfigChanged);
		}
	}
}
