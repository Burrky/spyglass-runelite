package com.osrstelemetry.plugin.skills;

/**
 * ADDED (Skills Phase 6). What an account storage OUTSIDE the bank holds of a
 * stockpile resource (generic: a skill-specific store the bank container
 * never lists), in the resource's own counting unit. Three honest states:
 * the storage does not apply to this resource (nothing to add), its contents
 * are KNOWN (possibly 0), or they are UNKNOWN (never zero -- a stockpile
 * plan then shows bounds, not a made-up total).
 */
public final class SkillExternalStock
{
	/** No such storage for this resource. */
	public static final SkillExternalStock NOT_APPLICABLE = new SkillExternalStock(false, null, null, null);

	private final boolean applies;
	private final Long quantity;
	private final String storageName;
	private final String observedAt;

	private SkillExternalStock(boolean applies, Long quantity, String storageName, String observedAt)
	{
		this.applies = applies;
		this.quantity = quantity;
		this.storageName = storageName;
		this.observedAt = observedAt;
	}

	/** The storage holds exactly {@code quantity} (as last observed at {@code observedAt}, ISO-8601 or null for "now"). */
	public static SkillExternalStock known(String storageName, long quantity, String observedAt)
	{
		return new SkillExternalStock(true, Math.max(0L, quantity), storageName, observedAt);
	}

	/** The storage applies but has never been observed on this account. */
	public static SkillExternalStock unknown(String storageName)
	{
		return new SkillExternalStock(true, null, storageName, null);
	}

	public boolean applies()
	{
		return applies;
	}

	public boolean isKnown()
	{
		return applies && quantity != null;
	}

	/** Null when unknown or not applicable. */
	public Long getQuantity()
	{
		return quantity;
	}

	public String getStorageName()
	{
		return storageName;
	}

	public String getObservedAt()
	{
		return observedAt;
	}
}
