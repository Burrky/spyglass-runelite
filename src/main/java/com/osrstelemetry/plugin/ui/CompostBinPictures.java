package com.osrstelemetry.plugin.ui;

import com.osrstelemetry.plugin.farming.CompostBinLook;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.ModelData;
import net.runelite.client.callback.ClientThread;

/**
 * The compost bins' pictures from the game's own models (pass 31): each look's models are loaded from the cache on the client
 * thread, recoloured as the bin's location definition says, and drawn once ({@link ModelIcon}); the pictures are kept for the
 * session (one per look: about a dozen). The sidebar asks from the EDT and gets the picture, or null and a repaint once drawn.
 */
public final class CompostBinPictures
{
	private final Client client;
	private final ClientThread clientThread;
	private final Map<String, BufferedImage> drawn = new ConcurrentHashMap<>();
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();

	public CompostBinPictures(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** The picture for a look, or null (still being drawn: {@code onDrawn} runs on the EDT once it is). */
	BufferedImage picture(CompostBinLook look, Runnable onDrawn)
	{
		if (look == null)
		{
			return null;
		}
		String key = look.key();
		BufferedImage img = drawn.get(key);
		if (img != null || failed.contains(key) || client == null || clientThread == null || !pending.add(key))
		{
			return img;
		}
		clientThread.invokeLater(() ->
		{
			try
			{
				BufferedImage pic = draw(look);
				if (pic == null)
				{
					failed.add(key);
				}
				else
				{
					drawn.put(key, pic);
					if (onDrawn != null)
					{
						SwingUtilities.invokeLater(onDrawn);
					}
				}
			}
			catch (RuntimeException e)
			{
				failed.add(key); // the card keeps its neutral mark
			}
			finally
			{
				pending.remove(key);
			}
		});
		return null;
	}

	/** Client thread: the bin's parts merged and recoloured, then drawn. Null when a model is not in the cache. */
	private BufferedImage draw(CompostBinLook look)
	{
		int[] ids = look.getModels();
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		ModelData merged = client.mergeModels(parts).cloneColors();
		short[] from = look.getRecolourFrom();
		short[] to = look.getRecolourTo();
		for (int i = 0; i < from.length; i++)
		{
			merged.recolor(from[i], to[i]);
		}
		return ModelIcon.render(merged.getVerticesX(), merged.getVerticesY(), merged.getVerticesZ(), merged.getFaceIndices1(),
			merged.getFaceIndices2(), merged.getFaceIndices3(), merged.getFaceColors(), merged.getFaceTransparencies(), CompostBinLook.AMBIENT,
			ItemIcon.WIDTH, ItemIcon.HEIGHT);
	}
}
