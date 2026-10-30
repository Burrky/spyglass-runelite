package com.osrstelemetry.plugin;

/** The sidebar's text contrast (pass 30): Standard (already readable), or High contrast for every text role. */
public enum SidebarTextContrast
{
	STANDARD("Standard"),
	HIGH("High contrast");

	private final String label;

	SidebarTextContrast(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
