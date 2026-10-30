package com.osrstelemetry.plugin.farming;

import java.util.Collections;
import java.util.List;

/**
 * Which catalogued patches the farming varbits describe at one location: the
 * patches of every area registered for the location's map region (owning or
 * alias) whose bounds rule accepts the exact x / y / plane. Immutable.
 *
 * Two locations in the same numeric region can have DIFFERENT contexts
 * (Falador allotments vs. the Port Sarim spirit tree in 12083; Catherby's
 * upstairs exception), so continuity is judged on this, not on the region id.
 */
public final class TransmissionContext
{
	static final TransmissionContext NONE = new TransmissionContext(Collections.emptyList());

	private final List<FarmingPatchDef> patches;
	private final String key;

	TransmissionContext(List<FarmingPatchDef> patchesSortedById)
	{
		this.patches = Collections.unmodifiableList(patchesSortedById);
		StringBuilder sb = new StringBuilder();
		for (FarmingPatchDef p : patchesSortedById)
		{
			sb.append(p.getPatchId()).append(';');
		}
		this.key = sb.toString();
	}

	public List<FarmingPatchDef> getPatches()
	{
		return patches;
	}

	public boolean isEmpty()
	{
		return patches.isEmpty();
	}

	/** Equal keys = the same patches are being transmitted. */
	public String getKey()
	{
		return key;
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof TransmissionContext && key.equals(((TransmissionContext) o).key);
	}

	@Override
	public int hashCode()
	{
		return key.hashCode();
	}

	@Override
	public String toString()
	{
		return key.isEmpty() ? "<none>" : key;
	}
}
