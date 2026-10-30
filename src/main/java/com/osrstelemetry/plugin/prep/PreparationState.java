package com.osrstelemetry.plugin.prep;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A request's needs evaluated against one account at one moment. */
public final class PreparationState
{
	private final PreparationRequest request;
	private final List<NeedResult> results;

	PreparationState(PreparationRequest request, List<NeedResult> results)
	{
		this.request = request;
		this.results = Collections.unmodifiableList(results);
	}

	public PreparationRequest getRequest()
	{
		return request;
	}

	public List<NeedResult> getResults()
	{
		return results;
	}

	/**
	 * Every REQUIRED need is carried (canonical §6.1 step 4). Recommended needs
	 * never block. This is only the supplies half of readiness: a consumer
	 * with travel or access it cannot confirm reports that separately
	 * ({@link #readiness(int)}).
	 */
	public boolean isReadyToGo()
	{
		return requiredLeft() == 0;
	}

	/** Overall readiness, given how many things the consumer could not confirm (unknown charges, unconfirmed access, no usable transport). */
	public Readiness readiness(int unverified)
	{
		if (requiredLeft() > 0)
		{
			return Readiness.MISSING;
		}
		return unverified > 0 ? Readiness.UNVERIFIED : Readiness.CONFIRMED;
	}

	/** RECOMMENDED needs not carried (they never block; the page says which). */
	public List<NeedResult> optionalNotCarried()
	{
		List<NeedResult> out = new java.util.ArrayList<>();
		for (NeedResult r : results)
		{
			if (r.getNeed().getNecessity() == ItemNeed.Necessity.RECOMMENDED && !r.isSatisfied())
			{
				out.add(r);
			}
		}
		return out;
	}

	public NeedResult result(String key)
	{
		for (NeedResult r : results)
		{
			if (r.getNeed().getKey().equals(key))
			{
				return r;
			}
		}
		return null;
	}

	public int requiredLeft()
	{
		int n = 0;
		for (NeedResult r : results)
		{
			if (r.getNeed().getNecessity() == ItemNeed.Necessity.REQUIRED && !r.isSatisfied())
			{
				n++;
			}
		}
		return n;
	}

	/** Needs (any necessity) still to fetch from the bank. */
	public int inBank()
	{
		int n = 0;
		for (NeedResult r : results)
		{
			if (r.getStatus() == NeedStatus.IN_BANK)
			{
				n++;
			}
		}
		return n;
	}

	/** Item id -> items still to withdraw, for the in-bank highlight (IN_BANK needs only; charges become items). */
	public Map<Integer, Long> bankHighlights()
	{
		Map<Integer, Long> out = new LinkedHashMap<>();
		for (NeedResult r : results)
		{
			if (r.getStatus() == NeedStatus.IN_BANK && r.getBankItemId() > 0)
			{
				out.merge(r.getBankItemId(), r.getWithdrawItems(), Long::sum);
			}
		}
		return out;
	}
}
