package com.osrstelemetry.plugin.pestcontrol;

/** Combat style of a Void set: which helm completes it. */
public enum VoidStyle
{
	MELEE("Melee", VoidPiece.MELEE_HELM),
	RANGED("Ranged", VoidPiece.RANGER_HELM),
	MAGIC("Magic", VoidPiece.MAGE_HELM);

	private final String label;
	private final VoidPiece helm;

	VoidStyle(String label, VoidPiece helm)
	{
		this.label = label;
		this.helm = helm;
	}

	public String getLabel()
	{
		return label;
	}

	public VoidPiece getHelm()
	{
		return helm;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
