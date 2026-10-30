package com.osrstelemetry.plugin;

/** The sidebar's text size (pass 30): every Spyglass page's text scales by one factor; overlays are not affected. */
public enum SidebarTextSize
{
	STANDARD("Standard", 1f),
	LARGE("Large", 1.25f),
	EXTRA_LARGE("Extra large", 1.5f);

	private final String label;
	private final float scale;

	SidebarTextSize(String label, float scale)
	{
		this.label = label;
		this.scale = scale;
	}

	public float getScale()
	{
		return scale;
	}

	public String getLabel()
	{
		return label;
	}

	/** RuneLite's config UI renders enum dropdown options via toString(). */
	@Override
	public String toString()
	{
		return label;
	}
}
