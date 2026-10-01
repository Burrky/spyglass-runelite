package com.osrstelemetry.plugin.ui;

/** Test helper: the text a player actually reads in a label (HTML markup stripped, entities decoded). */
final class UiText
{
	private UiText()
	{
	}

	/** A label's visible text, or a FactLine's joined facts. */
	static String visible(java.awt.Component c)
	{
		if (c instanceof FactLine)
		{
			return ((FactLine) c).text();
		}
		return c instanceof javax.swing.JLabel ? visible(((javax.swing.JLabel) c).getText()) : null;
	}

	static String visible(String text)
	{
		if (text == null)
		{
			return null;
		}
		return text.replaceAll("<[^>]*>", "")
			.replace("&nbsp;", " ")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&amp;", "&")
			.replace(' ', ' ')
			.trim();
	}
}
