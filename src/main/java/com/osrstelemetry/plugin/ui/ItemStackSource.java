package com.osrstelemetry.plugin.ui;

import java.awt.image.BufferedImage;

/**
 * Loads a game item's sprite as the game draws a stack of that many (coins: a few coins, a pile, a heap; RuneLite's
 * {@code ItemManager.getImage(id, quantity, false)}); {@code onLoaded} may run on any thread once the pixels have arrived.
 */
interface ItemStackSource
{
	BufferedImage load(int itemId, int quantity, Runnable onLoaded);
}
